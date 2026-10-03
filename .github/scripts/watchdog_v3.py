#!/usr/bin/env python3
"""Drive the E2E hub through watchdog v3: prepare a run, install a commit, purge its fixtures.

V3 never restores anything by itself, so every step here is explicit. A hub write is
submitted once; a lost response is followed by reading status, never by a resubmission.
"""

import argparse
import hashlib
import http.client
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
    """A refusal or failure whose message is safe to print (never an endpoint URL or its token)."""


class ToolError(OSError):
    """The hub answered with an error of its own. Its message is the hub's and safe to print."""

    def __init__(self, message, *, invalid=False):
        super().__init__(message)
        self.invalid = invalid  # the hub rejected the arguments: nothing was done


class Unreadable(OSError):
    """The hub answered but could not read what was asked. Retryable; the message is safe to print."""


class Transport:
    def __init__(self, timeout=60):
        self.timeout = timeout
        self.deadline = None  # time.monotonic() after which every call fails as a lost response

    def rpc(self, url, method, params):
        timeout = self.timeout
        if self.deadline is not None:
            timeout = min(timeout, self.deadline - time.monotonic())
            if timeout <= 0:
                raise OSError("The time budget for this step is spent")
        body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).encode()
        request = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                result = json.load(response)
        except (OSError, ValueError, http.client.HTTPException):
            # A body cut off mid-read is an HTTPException, not an OSError; it is a lost response too.
            # urllib exceptions can contain the URL and its OAuth token.
            raise OSError("Endpoint did not return a usable response") from None
        error = result.get("error") if isinstance(result, dict) else None
        if isinstance(error, dict) and error.get("message"):
            raise ToolError(str(error["message"])[:300], invalid=error.get("code") == -32602)
        if not isinstance(result, dict) or error or not isinstance(result.get("result"), dict):
            raise OSError("Endpoint rejected the MCP request")
        return result["result"]

    def call(self, url, name, args):
        result = self.rpc(url, "tools/call", {"name": name, "arguments": args})
        try:
            text = result["content"][0]["text"]
            value = json.loads(text)
        except ValueError:
            # A tool that threw answers in plain text; that is a failure on the hub, not a lost response.
            if text.startswith("Tool error:"):
                raise ToolError(text[:300]) from None
            raise OSError("Tool returned an invalid result") from None
        except (KeyError, IndexError, TypeError, AttributeError):
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
    """The instances whose identity a deployment must preserve: the MCP server and the watchdog.

    Everything else is left out on purpose: test fixtures (rules, child apps) come and go while a
    deployment runs, and the restore runs alongside the fixture purge.
    """
    answer = transport.call(v3, "hub_list_app_instances", {})
    apps = answer.get("apps")
    if not isinstance(apps, list) or not apps:
        raise Unreadable(f"Cannot read the installed app instances: {answer.get('error')}")
    kept = sorted((str(a.get("id")), a.get("type"), a.get("disabled")) for a in apps
                  if isinstance(a, dict) and a.get("type") in ("MCP Rule Server", V3_APP_NAME))
    if not any(kind == "MCP Rule Server" for _id, kind, _disabled in kept):
        raise HubError("The MCP Rule Server instance is not installed")
    return kept


def code_versions(transport, v3):
    """The code versions of everything a deployment writes: both apps and every mcp library."""
    answer = transport.call(v3, "hub_list_apps", {"scope": "types"})
    types = answer.get("apps")
    if not isinstance(types, list) or not types:
        raise Unreadable(f"Cannot read the app code list: {answer.get('error') or answer.get('note')}")
    versions = {}
    for name in PACKAGE_APPS:
        ids = [item.get("id") for item in types
               if isinstance(item, dict) and item.get("namespace") == "mcp" and item.get("name") == name]
        if len(ids) != 1:
            raise HubError(f"Expected one {name} code class, found {len(ids)}")
        head = transport.call(v3, "hub_get_source", {
            "type": "app", "id": str(ids[0]), "offset": 0, "length": 1, "noSave": True,
        })
        if head.get("success") is not True:
            raise Unreadable(f"Cannot read the {name} code version: {head.get('error')}")
        versions[name] = (head.get("version"), head.get("totalLength"))
    listing = transport.call(v3, "hub_list_libraries", {})
    libraries = listing.get("libraries")
    if not isinstance(libraries, list) or listing.get("source") != "hub_api":
        raise Unreadable(f"Cannot read the library list: {listing.get('error') or listing.get('note')}")
    versions["libraries"] = sorted((str(item.get("name")), str(item.get("version"))) for item in libraries
                                   if isinstance(item, dict) and item.get("namespace") == "mcp")
    return versions


def wait_until_settled(transport, v3, *, samples=3, interval=15, attempts=40):
    """A save or bundle import still running bumps a code version when it lands; require a quiet stretch."""
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
    """Release whatever deployment hold is in place. Returns the status it cleared, or None.

    A hold is abandoned only once its worker has stopped (or v3 reports it stale) and the package
    code versions have been quiet.
    """
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
        raise HubError(f"Could not release the hold of {held.get('requestId')}: {released.get('error')}")
    log(f"Released the hold of {held.get('requestId')} (was {held.get('phase')}: {held.get('error')})")
    return held


def submit(transport, v3, arguments, *, interval=20, attempts=75):
    """Submit once. Only refusals that state nothing was scheduled are retried."""
    request_id = arguments["requestId"]
    cleared = False
    for _ in range(attempts):
        try:
            accepted = transport.call(v3, "hub_update_package", arguments)
        except ToolError as error:
            if error.invalid:
                raise HubError(f"Deployment refused: {error}") from None
            log(f"::warning::{request_id}: the start request failed on the hub ({error}); reading its status")
            return
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
    """The original endpoint URLs and tokens still answer, and the MCP server and watchdog instances are unchanged.

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


def start(transport, v3, plan, request_id):
    """Submit one deployment. Returns the instance baseline its release is checked against."""
    transport.probe(v3)
    baseline = instance_snapshot(transport, v3)
    log(f"Starting package operation {request_id} at {plan['ref']}")
    submit(transport, v3, {**plan, "requestId": request_id, "confirm": True})
    return baseline


def deploy(transport, v3, mcp, plan, request_id, **options):
    """Deploy one commit and release its hold. Returns the completed status."""
    return follow(transport, v3, mcp, plan, request_id, start(transport, v3, plan, request_id), **options)


def follow(transport, v3, mcp, plan, request_id, baseline, *, interval=10, attempts=270,
           retry_interrupted=True, endpoint_wait_s=1500, wait_s=None):
    """Follow a submitted deployment to its end and release its hold."""
    last, unseen, release_failures, silent = None, 0, 0, 0
    # Status reads that time out take far longer than `interval`, so the attempts are also capped by time.
    deadline = time.monotonic() + (attempts * max(interval, 1) * 2 if wait_s is None else wait_s)
    for _ in range(attempts):
        if time.monotonic() > deadline:
            break
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
                          attempts=attempts, retry_interrupted=False, endpoint_wait_s=endpoint_wait_s,
                          wait_s=wait_s)
        if phase in ("stopped", "abandoned"):
            raise HubError(f"Deployment {phase}: {status.get('error')}")
        if status.get("workerStale") is True:
            raise HubError(f"The deployment worker went silent in {phase} ({status.get('component') or 'no component'}); "
                           "safety hold retained")
        if phase == "awaiting_verification" and status.get("workerActive") is not True:
            verify_endpoints(transport, v3, mcp, baseline, wait_s=endpoint_wait_s)
            try:
                released = transport.call(v3, "hub_set_package_deployment", {
                    "requestId": request_id, "endpointVerified": True, "confirm": True,
                })
            except ToolError as error:
                release_failures += 1
                if release_failures >= 5:
                    raise HubError(f"Completion failed on the hub: {error}") from None
                log(f"{request_id}: the release failed on the hub ({error}); the hold is retained, retrying")
                time.sleep(interval)
                continue
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
    where = f"{last[0]} ({last[1] or 'no component'})" if last else "no status was ever read"
    raise HubError(f"Deployment observation timed out at {where}; safety hold retained")


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
    """Return the bytes at a public URL, or None when it does not exist (404 only)."""
    status = "no response"
    for attempt in range(attempts):
        try:
            with urllib.request.urlopen(url, timeout=60) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            if error.code == 404:
                return None
            status = f"HTTP {error.code}"
        except (OSError, http.client.HTTPException):
            status = "no response"
        if attempt < attempts - 1:
            time.sleep(interval)
    raise HubError(f"Could not download {url} ({status})")


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
        # Not fatal: the install step replaces the MCP package whatever state it is in.
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


def purge_fixtures(transport, v3, *, interval=15, attempts=60):
    """Run the hub-local BAT_E2E_ sweep and return its finished result. A lost response or an
    in-flight marker is followed by asking again: v3 never starts a second sweep for the same
    prefix and serves a finished one from its cache."""
    for attempt in range(attempts):
        try:
            purged = transport.call(v3, "hub_purge_e2e_artifacts", {"confirm": True})
        except OSError:
            # The sweep outlives the relay timeout and keeps running on the hub.
            purged = None
        if purged is not None and not purged.get("inFlight") and not purged.get("busy"):
            log("Fixture purge: " + json.dumps({k: purged.get(k) for k in (
                "success", "cached", "deletedCount", "failedCount", "variablesDeletedCount",
                "variablesFailedCount", "otherDeleted", "otherFailed", "error")}))
            return purged
        if attempt + 1 < attempts:
            time.sleep(interval)
    raise HubError("The fixture purge did not report a finished sweep in time")


def command_teardown(args):
    """Release the run's hold and purge its fixtures. The PR's package stays installed: the next
    run installs over it, and install-main restores main on demand."""
    transport, v3, _mcp = endpoints()
    if args.cancelled:
        # GitHub ends a cancelled job after about five minutes and the lease release still has to
        # fit, so be quick and leave anything unfinished to the next run's prepare step.
        transport.timeout = 20
        # Calls stop at 110 s; with the sleeps below the worst case stays under the step's 3-minute
        # timeout, so the warning always prints.
        transport.deadline = time.monotonic() + 110
        try:
            clear_hold(transport, v3, interval=5, attempts=3,
                       settle=lambda t, url: wait_until_settled(t, url, interval=5, attempts=4))
            if purge_fixtures(transport, v3, interval=5, attempts=4).get("success") is not True:
                log("::warning::The purge reported failures; the fixtures were left for the next run.")
        except (HubError, ToolError, Unreadable) as error:
            log(f"::warning::The hold or the fixtures were left for the next run: {error}")
        except OSError:
            log("::warning::The watchdog did not answer; the hold and the fixtures were left for the next run.")
        return
    clear_hold(transport, v3)
    purged = purge_fixtures(transport, v3)
    if purged.get("success") is not True:
        raise HubError(purged.get("error") or "The fixture purge reported failures")


def current_main_sha(repository):
    try:
        listing = subprocess.run(["git", "ls-remote", f"https://github.com/{repository}.git", "refs/heads/main"],
                                 capture_output=True, text=True, timeout=60, check=True).stdout
    except (OSError, subprocess.SubprocessError):
        listing = ""
    sha = listing.split()[0] if listing.split() else ""
    return sha if re.fullmatch(r"[0-9a-f]{40}", sha) else None


def command_install_main(_args):
    """Install main as it is now, for maintenance or to recover from a broken PR install."""
    transport, v3, mcp = endpoints()
    repository = os.environ["GITHUB_REPOSITORY"]
    base = f"https://raw.githubusercontent.com/{repository}"
    sha = current_main_sha(repository)
    if sha is None:
        raise HubError("Could not resolve main's current SHA")
    bundle = fetch(artifact_url(base, sha))
    if bundle is None:
        raise HubError(f"No published bundle for main at {sha}")
    clear_hold(transport, v3)
    result = deploy(transport, v3, mcp, {**plan_from_bundle(sha, bundle), "baseUrl": base, "bundleBaseUrl": base},
                    operation_id("main"))
    log(f"Installed main {sha}: {json.dumps({k: result.get(k) for k in ('requestId', 'phase', 'hold', 'elapsedMs')})}")


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
    teardown = commands.add_parser("teardown")
    teardown.add_argument("--cancelled", action="store_true", help="the run was cancelled: best effort, never fails")
    teardown.set_defaults(run=command_teardown)
    commands.add_parser("install-main").set_defaults(run=command_install_main)
    commands.add_parser("endpoint").set_defaults(run=command_endpoint)
    args = parser.parse_args(argv)
    try:
        args.run(args)
    except (HubError, ToolError, Unreadable) as error:
        raise SystemExit(f"::error::{error}") from None
    except (OSError, ValueError, KeyError, zipfile.BadZipFile):
        # Never print the exception: it can carry an endpoint URL and its token.
        raise SystemExit("::error::An endpoint or input was unavailable; no write was retried") from None


if __name__ == "__main__":
    main(sys.argv[1:])
