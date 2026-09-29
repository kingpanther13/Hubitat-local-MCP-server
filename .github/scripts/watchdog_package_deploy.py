#!/usr/bin/env python3
"""Explicit manual package probe; never updates or restarts the watchdog."""

import argparse
import hashlib
import io
import json
import os
import re
import time
import urllib.error
import urllib.request
import uuid
import zipfile
from pathlib import Path

RAW_BASE = "https://raw.githubusercontent.com/kingpanther13/Hubitat-local-MCP-server"


class Transport:
    def rpc(self, url, method, params):
        body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).encode()
        request = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                result = json.load(response)
        except (OSError, ValueError):
            # urllib exceptions can contain the URL and its OAuth token.
            raise OSError("Endpoint did not return a usable response") from None
        if result.get("error") or not isinstance(result.get("result"), dict):
            raise OSError("Endpoint rejected the MCP request")
        return result["result"]

    def call(self, url, name, args):
        result = self.rpc(url, "tools/call", {"name": name, "arguments": args})
        if result.get("isError"):
            raise OSError("Tool reported an execution error")
        try:
            value = json.loads(result["content"][0]["text"])
        except (KeyError, IndexError, TypeError, ValueError):
            raise OSError("Tool returned an invalid result") from None
        if not isinstance(value, dict):
            raise OSError("Tool returned an invalid result")
        return value

    def probe(self, url):
        result = self.rpc(url, "initialize", {
            "protocolVersion": "2025-03-26", "capabilities": {},
            "clientInfo": {"name": "watchdog-package-probe", "version": "1"},
        })
        if not result.get("serverInfo"):
            raise OSError("Endpoint initialization failed")
        catalog = self.rpc(url, "tools/list", {})
        if not catalog.get("tools"):
            raise OSError("Endpoint tool catalog is unavailable")


def instance_snapshot(transport, url):
    result = transport.call(url, "hub_list_app_instances", {})
    apps = result.get("apps")
    if not isinstance(apps, list) or not apps:
        raise RuntimeError("Cannot verify installed app instances; safety hold retained")
    return sorted((str(a["id"]), str(a.get("parentId")), a.get("type"), a.get("disabled")) for a in apps)


def deploy(transport, watchdog_url, mcp_url, plan, request_id, *, attempts=240, interval=10):
    transport.probe(watchdog_url)
    transport.probe(mcp_url)
    baseline = instance_snapshot(transport, watchdog_url)
    arguments = {**plan, "requestId": request_id, "confirm": True}
    print(f"Starting package operation {request_id} at {plan['ref']}", flush=True)
    try:
        accepted = transport.call(watchdog_url, "hub_update_package", arguments)
    except OSError:
        # The request may have reached the hub. Only read its caller-chosen ID.
        accepted = None
    if accepted is not None and (accepted.get("success") is not True or accepted.get("requestId") != request_id):
        raise RuntimeError("Deployment was refused; no retry submitted")
    for _ in range(attempts):
        try:
            status = transport.call(watchdog_url, "hub_get_package_deployment", {"requestId": request_id})
        except OSError:
            time.sleep(interval)
            continue
        if status.get("requestId") != request_id:
            raise RuntimeError("Operation identity mismatch; safety hold retained")
        phase = status.get("phase")
        print(f"{request_id}: {phase}; {status.get('component', '')}; "
              f"stage elapsed {status.get('stageElapsedMs', 0) // 1000}s", flush=True)
        if phase == "stopped" or status.get("success") is False:
            raise RuntimeError("Deployment stopped; inspect watchdog status. Safety hold retained")
        if phase == "awaiting_verification":
            try:
                transport.probe(watchdog_url)
                transport.probe(mcp_url)
                after = instance_snapshot(transport, watchdog_url)
            except OSError:
                time.sleep(interval)
                continue
            if after != baseline:
                raise RuntimeError("Installed app instance identity changed; safety hold retained")
            # Both ORIGINAL endpoint URLs and tokens still work. The watchdog rechecks
            # code hashes before accepting this acknowledgement.
            try:
                released = transport.call(watchdog_url, "hub_set_package_deployment", {
                    "requestId": request_id, "endpointVerified": True, "confirm": True,
                })
            except OSError:
                # A lost acknowledgement is read back, never replayed.
                released = transport.call(watchdog_url, "hub_get_package_deployment", {"requestId": request_id})
            if released.get("requestId") == request_id and released.get("phase") == "complete" and released.get("hold") is False:
                return released
            raise RuntimeError("Completion was not confirmed; inspect safety hold before further writes")
        time.sleep(interval)
    raise RuntimeError("Deployment observation timed out; safety hold retained. Do not resubmit or restart either app")


def plan_from_bundle(ref, bundle_bytes):
    if not re.fullmatch(r"[0-9a-f]{40}", ref):
        raise ValueError("ref must be an immutable 40-character commit SHA")
    with zipfile.ZipFile(io.BytesIO(bundle_bytes)) as bundle:
        manifest = bundle.read("install.txt").decode().splitlines()
        if manifest[:2] != ["mcp", "mcp_libraries"] or bundle.read("install.txt") != bundle.read("update.txt"):
            raise ValueError("Unexpected package bundle identity")
        entries = manifest[2:]
        if not entries or any(not re.fullmatch(r"library mcp\.[A-Za-z0-9_]+\.groovy", item) for item in entries):
            raise ValueError("Package probe accepts a libraries-only bundle")
        names = [item.split(" ", 1)[1] for item in entries]
        if len(set(names)) != len(names) or sorted(bundle.namelist()) != sorted([*names, "install.txt", "update.txt"]):
            raise ValueError("Unexpected or duplicate bundle contents")
        libraries = [{"name": name[len("mcp."):-len(".groovy")],
                      "sha256": hashlib.sha256(bundle.read(name)).hexdigest()} for name in names]
    return {"ref": ref, "libraries": libraries}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", required=True, help="Validated commit SHA")
    parser.add_argument("--bundle", type=Path, required=True, help="GitHub-built mcp-libraries.zip from that SHA")
    parser.add_argument("--lease-held", action="store_true", required=True,
                        help="Operator confirms exclusive E2E hub lease for the entire probe")
    args = parser.parse_args()
    local = args.bundle.read_bytes()
    plan = plan_from_bundle(args.ref, local)
    artifact = f"{RAW_BASE}/bundle-artifacts/shas/{args.ref}/mcp-libraries.zip"
    with urllib.request.urlopen(artifact, timeout=60) as response:
        if response.read() != local:
            raise ValueError("Published artifact does not match the GitHub-built bundle")
    result = deploy(Transport(), os.environ["WATCHDOG_URL"], os.environ["MCP_URL"], plan, str(uuid.uuid4()))
    print(json.dumps(result))


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        # Do not dump request URLs/tokens or issue cleanup writes on failure.
        message = str(error) if isinstance(error, RuntimeError) else "Probe could not proceed; check inputs and endpoint availability"
        raise SystemExit(message) from None
