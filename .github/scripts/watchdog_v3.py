#!/usr/bin/env python3
"""Drive the E2E hub through watchdog v3: prepare a run, install a commit, restore main.

V3 never restores anything by itself, so every step here is explicit. A hub write is
submitted once; a lost response is followed by reading status, never by a resubmission.
"""

import argparse
import hashlib
import io
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

V3_APP_NAME = "E2E Dead-Man Watchdog v3"
ENDPOINT_RE = re.compile(r"https://cloud\.hubitat\.com/api/[0-9a-f-]+/apps/[0-9]+/mcp\?access_token=[A-Za-z0-9-]+")
PACKAGE_APPS = ("MCP Rule", "MCP Rule Server")


class HubError(RuntimeError):
    """A refusal or failure whose message is safe to print (never carries a URL or token)."""


class Transport:
    def __init__(self, timeout=60):
        self.timeout = timeout

    def rpc(self, url, method, params):
        body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).encode()
        request = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                result = json.load(response)
        except (OSError, ValueError):
            # urllib exceptions can contain the URL and its OAuth token.
            raise OSError("Endpoint did not return a usable response") from None
        if not isinstance(result, dict) or result.get("error") or not isinstance(result.get("result"), dict):
            raise OSError("Endpoint rejected the MCP request")
        return result["result"]

    def call(self, url, name, args):
        result = self.rpc(url, "tools/call", {"name": name, "arguments": args})
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
            "clientInfo": {"name": "e2e-watchdog-v3", "version": "1"},
        })
        if not result.get("serverInfo"):
            raise OSError("Endpoint initialization failed")
        if not self.rpc(url, "tools/list", {}).get("tools"):
            raise OSError("Endpoint tool catalog is unavailable")


def log(message):
    print(message, flush=True)


def mask(url):
    if os.environ.get("GITHUB_ACTIONS") == "true":
        print(f"::add-mask::{url}", flush=True)
        print(f"::add-mask::{url.split('access_token=')[-1]}", flush=True)


def resolve_v3_url(transport, mcp_url, watchdog_url, cache=None):
    """Return v3's endpoint. Until WATCHDOG_MCP_URL points at v3, read it from the v3 app page."""
    if cache is not None and cache.exists():
        return cache.read_text().strip()
    try:
        configured = transport.call(watchdog_url, "hub_get_info", {})
    except OSError:
        raise HubError("The configured watchdog endpoint does not answer") from None
    if configured.get("watchdogVersion") == 3:
        url = watchdog_url
    else:
        instances = transport.call(watchdog_url, "hub_list_app_instances", {}).get("apps") or []
        matches = [item for item in instances if item.get("type") == V3_APP_NAME]
        if len(matches) != 1:
            raise HubError(f"Expected one installed {V3_APP_NAME} instance, found {len(matches)}")
        config = transport.call(mcp_url, "hub_read_apps_code", {
            "tool": "hub_get_app_config", "args": {"appId": str(matches[0]["id"])},
        })
        urls = set(ENDPOINT_RE.findall(json.dumps(config)))
        if len(urls) != 1:
            raise HubError("The v3 app page does not show exactly one endpoint")
        url = urls.pop()
        mask(url)
        if transport.call(url, "hub_get_info", {}).get("watchdogVersion") != 3:
            raise HubError("The discovered endpoint is not watchdog v3")
    if cache is not None:
        cache.write_text(url)
        cache.chmod(0o600)
    return url


def instance_snapshot(transport, v3):
    apps = transport.call(v3, "hub_list_app_instances", {}).get("apps")
    if not isinstance(apps, list) or not apps:
        raise HubError("Cannot read the installed app instances")
    return sorted((str(a["id"]), str(a.get("parentId")), a.get("type"), a.get("disabled")) for a in apps)


def code_versions(transport, v3):
    types = transport.call(v3, "hub_list_apps", {"scope": "types"}).get("apps") or []
    versions = {}
    for name in PACKAGE_APPS:
        ids = [item["id"] for item in types if item.get("namespace") == "mcp" and item.get("name") == name]
        if len(ids) != 1:
            raise HubError(f"Expected one {name} code class, found {len(ids)}")
        head = transport.call(v3, "hub_get_source", {
            "type": "app", "id": str(ids[0]), "offset": 0, "length": 1, "noSave": True,
        })
        if head.get("success") is not True:
            raise HubError(f"Cannot read the {name} code version")
        versions[name] = (head.get("version"), head.get("totalLength"))
    return versions


def wait_until_settled(transport, v3, *, samples=3, interval=15, attempts=40):
    """A save still compiling bumps a code version when it lands; require a quiet stretch."""
    quiet, last = 0, None
    for _ in range(attempts):
        try:
            current = code_versions(transport, v3)
        except OSError:
            quiet, last = 0, None
        else:
            quiet = quiet + 1 if current == last else 1
            last = current
            if quiet >= samples:
                return
        time.sleep(interval)
    raise HubError("The package code versions kept changing; a hub write is still in flight")


def clear_hold(transport, v3, *, interval=15, attempts=80, settle=wait_until_settled):
    """Release whatever deployment hold an earlier run left behind. Returns the status it cleared."""
    held = None
    for _ in range(attempts):
        try:
            held = transport.call(v3, "hub_get_info", {}).get("packageDeployment")
        except OSError:
            time.sleep(interval)
            continue
        if not held or held.get("hold") is not True:
            return None
        if held.get("workerActive") is not True or held.get("workerStale") is True:
            break
        log(f"Deployment {held.get('requestId')} is still running ({held.get('phase')}); waiting for it to rest")
        time.sleep(interval)
    else:
        raise HubError("A package deployment is still running on the hub, or the watchdog does not answer")
    settle(transport, v3)
    released = transport.call(v3, "hub_set_package_deployment", {
        "requestId": held["requestId"], "abandon": True, "writesSettled": True, "confirm": True,
    })
    if released.get("hold") is not False:
        raise HubError(f"Could not release the leftover hold: {released.get('error')}")
    log(f"Released the leftover hold of {held.get('requestId')} (was {held.get('phase')}: {held.get('error')})")
    return held


def submit(transport, v3, arguments, *, interval=20, attempts=75):
    """Submit once. Only refusals that state nothing was scheduled are retried."""
    request_id = arguments["requestId"]
    cleared = False
    for _ in range(attempts):
        try:
            accepted = transport.call(v3, "hub_update_package", arguments)
        except OSError:
            return  # The request may have reached the hub; the caller reads status by its ID.
        if accepted.get("success") is True and accepted.get("requestId") == request_id:
            return
        if accepted.get("busy") is True:
            log(f"A manual write ({accepted.get('activeTool')}) is still running; nothing was scheduled, retrying")
            time.sleep(interval)
            continue
        if accepted.get("heldRequestId") and not cleared:
            clear_hold(transport, v3)
            cleared = True
            continue
        raise HubError(f"Deployment refused: {accepted.get('error')}")
    raise HubError("The watchdog stayed busy with a manual write; nothing was scheduled")


def verify_endpoints(transport, v3, mcp, baseline, *, interval=15, wait_s=1500):
    """The original endpoint URLs and tokens still answer and no app instance changed.

    The MCP server can stay dark for many minutes after its code is saved (14 observed), so this
    waits generously and says which endpoint it is waiting on.
    """
    started = time.monotonic()
    while True:
        waiting_on = "watchdog v3"
        try:
            transport.probe(v3)
            waiting_on = "the MCP server"
            transport.probe(mcp)
            waiting_on = "watchdog v3"
            after = instance_snapshot(transport, v3)
        except OSError:
            elapsed = int(time.monotonic() - started)
            if elapsed >= wait_s:
                raise HubError(f"{waiting_on} did not answer for {elapsed}s after the deployment; "
                               "safety hold retained") from None
            log(f"Installed; waiting for {waiting_on} to answer ({elapsed}s)")
            time.sleep(interval)
            continue
        if after != baseline:
            raise HubError("Installed app instance identity changed; safety hold retained")
        return


def deploy(transport, v3, mcp, plan, request_id, *, interval=10, attempts=270, retry_interrupted=True):
    """Deploy one commit and release its hold. Returns the completed status."""
    transport.probe(v3)
    baseline = instance_snapshot(transport, v3)
    log(f"Starting package operation {request_id} at {plan['ref']}")
    submit(transport, v3, {**plan, "requestId": request_id, "confirm": True})
    last, unseen, release_failures, silent = None, 0, 0, 0
    for _ in range(attempts):
        try:
            status = transport.call(v3, "hub_get_package_deployment", {"requestId": request_id})
        except OSError:
            silent += 1  # relay drop, or the hub is restarting
            if silent % 6 == 0:
                log(f"{request_id}: the watchdog has not answered {silent} status reads in a row")
            time.sleep(interval)
            continue
        silent = 0
        if status.get("requestId") != request_id:
            unseen += 1
            if unseen >= 6:
                raise HubError(f"The watchdog has no record of {request_id}: {status.get('error')}")
            time.sleep(interval)
            continue
        phase = status.get("phase")
        seen = (phase, status.get("component"), status.get("detail"))
        if seen != last:
            detail = f" ({status['detail']})" if status.get("detail") else ""
            log(f"{request_id}: {phase}; {status.get('component') or ''}{detail}")
            last = seen
        if phase == "complete" and status.get("hold") is False:
            return status
        if phase == "interrupted":
            if not retry_interrupted:
                raise HubError("The hub restarted during the retried deployment; safety hold retained")
            log("::warning::The hub restarted during the deployment. Releasing its hold and deploying once more.")
            clear_hold(transport, v3)
            return deploy(transport, v3, mcp, plan, f"{request_id}-retry", interval=interval,
                          attempts=attempts, retry_interrupted=False)
        if phase in ("stopped", "abandoned"):
            raise HubError(f"Deployment {phase}: {status.get('error')}")
        if phase == "awaiting_verification" and status.get("workerActive") is not True:
            verify_endpoints(transport, v3, mcp, baseline)
            try:
                released = transport.call(v3, "hub_set_package_deployment", {
                    "requestId": request_id, "endpointVerified": True, "confirm": True,
                })
            except OSError:
                log(f"{request_id}: the release got no answer; it may have landed, reading status")
                time.sleep(interval)
                continue
            if released.get("phase") == "complete" and released.get("hold") is False:
                return released
            release_failures += 1
            if release_failures >= 5:
                raise HubError(f"Completion was refused: {released.get('error')}")
            log(f"Completion not accepted yet ({released.get('error')}); the hold is retained, retrying")
        time.sleep(interval)
    raise HubError("Deployment observation timed out; safety hold retained")


def plan_from_bundle(ref, bundle_bytes):
    """The expected library names and hashes, read from a libraries-only bundle."""
    if not re.fullmatch(r"[0-9a-f]{40}", ref):
        raise ValueError("ref must be an immutable 40-character commit SHA")
    with zipfile.ZipFile(io.BytesIO(bundle_bytes)) as bundle:
        manifest = bundle.read("install.txt").decode().splitlines()
        if manifest[:2] != ["mcp", "mcp_libraries"] or bundle.read("install.txt") != bundle.read("update.txt"):
            raise ValueError("Unexpected package bundle identity")
        entries = manifest[2:]
        if not entries or any(not re.fullmatch(r"library mcp\.[A-Za-z0-9_]+\.groovy", item) for item in entries):
            raise ValueError("The package bundle must contain libraries only")
        names = [item.split(" ", 1)[1] for item in entries]
        if len(set(names)) != len(names) or sorted(bundle.namelist()) != sorted([*names, "install.txt", "update.txt"]):
            raise ValueError("Unexpected or duplicate bundle contents")
        libraries = [{"name": name[len("mcp."):-len(".groovy")],
                      "sha256": hashlib.sha256(bundle.read(name)).hexdigest()} for name in names]
    return {"ref": ref, "libraries": libraries}


def fetch(url, *, attempts=3, interval=5):
    """Return the bytes at a public URL, or None when it does not exist."""
    for attempt in range(attempts):
        try:
            with urllib.request.urlopen(url, timeout=60) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            if error.code == 404:
                return None
        except OSError:
            pass
        if attempt < attempts - 1:
            time.sleep(interval)
    raise HubError(f"Could not download {url}")


def artifact_url(base, sha):
    return f"{base}/bundle-artifacts/shas/{sha}/mcp-libraries.zip"


def endpoints():
    cache = Path(os.environ["RUNNER_TEMP"], "watchdog-v3-endpoint") if os.environ.get("RUNNER_TEMP") else None
    transport = Transport()
    mcp = os.environ["MCP_URL"]
    return transport, resolve_v3_url(transport, mcp, os.environ["WATCHDOG_URL"], cache), mcp


def operation_id(suffix):
    return f"e2e-{os.environ.get('GITHUB_RUN_ID', 'manual')}-{os.environ.get('GITHUB_RUN_ATTEMPT', '1')}-{suffix}"


def command_prepare(_args):
    transport, v3, _mcp = endpoints()
    info = transport.call(v3, "hub_get_info", {})
    log(f"Watchdog v3 answers: firmware {info.get('firmwareVersion')}, free memory {info.get('freeMemoryKB')} KB")
    try:
        peer = transport.call(v3, "hub_get_info", {"peer": True}).get("peerEndpoint") or {}
        log(f"MCP endpoint available: {peer.get('available')} {peer.get('reason') or ''}".rstrip())
    except OSError:
        # Not fatal: the install below replaces the MCP package whatever state it is in.
        log("::warning::The MCP endpoint check got no answer; continuing.")
    clear_hold(transport, v3)


def command_deploy_pr(args):
    transport, v3, mcp = endpoints()
    base, sha = os.environ["PR_RAW_BASE"], os.environ["PR_HEAD_SHA_RESOLVED"]
    built = Path(args.bundle).read_bytes()
    plan = plan_from_bundle(sha, built)
    published = fetch(artifact_url(base, sha))
    if published is None:
        log(f"::warning::No bundle-artifacts entry for {sha}. The deployment succeeds only if the hub's "
            "libraries already match this commit.")
    elif published != built:
        raise HubError("The published bundle differs from the bundle built from this checkout")
    result = deploy(transport, v3, mcp, {**plan, "baseUrl": base, "bundleBaseUrl": base}, operation_id("pr"))
    log(f"Installed {sha}: {json.dumps({k: result.get(k) for k in ('requestId', 'phase', 'hold', 'elapsedMs')})}")


def current_main_sha(repository):
    try:
        listing = subprocess.run(["git", "ls-remote", f"https://github.com/{repository}.git", "refs/heads/main"],
                                 capture_output=True, text=True, timeout=60, check=True).stdout
    except (OSError, subprocess.SubprocessError):
        return None
    sha = listing.split()[0] if listing.split() else ""
    return sha if re.fullmatch(r"[0-9a-f]{40}", sha) else None


def purge_fixtures(transport, v3):
    try:
        purged = transport.call(v3, "hub_purge_e2e_artifacts", {"confirm": True})
        log("Fixture purge: " + json.dumps({k: purged.get(k) for k in (
            "success", "inFlight", "cached", "deletedCount", "failedCount",
            "variablesDeletedCount", "variablesFailedCount", "error")}))
    except OSError:
        # The sweep outlives the relay timeout and keeps running on the hub.
        log("::warning::The fixture purge returned no response; it may still be running on the hub.")


def command_restore_main(args):
    transport, v3, mcp = endpoints()
    if args.cancelled:
        # GitHub ends a cancelled job after about five minutes, less than a deployment takes, and a
        # cancel is normally followed by a run that installs its own code. So leave the package as
        # it is and spend the time on the cleanup steps that follow.
        clear_hold(transport, v3, attempts=4)
        purge_fixtures(transport, v3)
        log("Run cancelled: main was NOT restored. The next run's install replaces the package.")
        return
    repository = os.environ["GITHUB_REPOSITORY"]
    base = f"https://raw.githubusercontent.com/{repository}"
    # Main can move while a run is in flight; restore what main is now, not what it was at the start.
    candidates = [sha for sha in (current_main_sha(repository), os.environ.get("MAIN_SHA")) if sha]
    for sha in dict.fromkeys(candidates):
        bundle = fetch(artifact_url(base, sha))
        if bundle is not None:
            break
        log(f"::warning::No bundle-artifacts entry for main at {sha} yet; trying the run's starting main.")
    else:
        raise HubError("No published bundle for main; cannot restore")
    # A failed PR install leaves its hold, and a hold blocks the purge, so release it first.
    clear_hold(transport, v3)
    purge_fixtures(transport, v3)
    plan = plan_from_bundle(sha, bundle)
    result = deploy(transport, v3, mcp, {**plan, "baseUrl": base, "bundleBaseUrl": base}, operation_id("main"))
    log(f"Restored main {sha}: {json.dumps({k: result.get(k) for k in ('requestId', 'phase', 'hold', 'elapsedMs')})}")


def command_endpoint(_args):
    _transport, v3, _mcp = endpoints()
    print(v3)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("prepare").set_defaults(run=command_prepare)
    deploy_pr = commands.add_parser("deploy-pr")
    deploy_pr.add_argument("--bundle", required=True, help="mcp-libraries.zip built from the checkout")
    deploy_pr.set_defaults(run=command_deploy_pr)
    restore = commands.add_parser("restore-main")
    restore.add_argument("--cancelled", action="store_true", help="the run was cancelled: clean up, deploy nothing")
    restore.set_defaults(run=command_restore_main)
    commands.add_parser("endpoint").set_defaults(run=command_endpoint)
    args = parser.parse_args(argv)
    try:
        args.run(args)
    except HubError as error:
        raise SystemExit(f"::error::{error}") from None
    except (OSError, ValueError, KeyError, zipfile.BadZipFile):
        # Never print the exception: it can carry an endpoint URL and its token.
        raise SystemExit("::error::An endpoint or input was unavailable; no write was retried") from None


if __name__ == "__main__":
    main(sys.argv[1:])
