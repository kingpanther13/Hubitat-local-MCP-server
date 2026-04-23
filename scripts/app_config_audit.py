#!/usr/bin/env python3
"""
Shape-drift audit for the get_app_config tool's underlying hub endpoint.

Fetches /installedapp/configure/json/<id>[/<pageName>] for a set of known app IDs,
asserts the top-level shape invariants that the Groovy tool depends on, and reports
any drift. Run nightly or after each firmware update to catch contract changes before
they surface as runtime errors for users.

Invariants checked (per PR description of get_app_config):
  - Response is a JSON object
  - Top-level has 'app' (object), 'configPage' (object), and 'settings' (object or absent)
  - configPage has 'sections' (array)
  - Each section has 'input' (array or absent) and 'body' (array or absent)
  - Each input has 'name' (string) and 'type' (string)

Expected sample IDs are declared inline — edit the TARGETS list to match your hub.
A minimum set that covers all three legacy-app rendering paths (RM, Room Lighting, HPM
non-default page) is recommended. At least one user-created rule and one system app.

Usage:
    uv run --python 3.12 scripts/app_config_audit.py --hub-url http://<hub>
    uv run --python 3.12 scripts/app_config_audit.py --config scripts/audit_config.json

Note: Requires Hub Security to be disabled (or the hub to accept unauthenticated requests
on /installedapp/configure/json). On hubs with Hub Security enabled the request is redirected
to the login page; urllib.request has no cookie/session support so this script will parse the
login-redirect HTML as JSON and report contract failures as "top-level not an object".

Exit codes:
    0 — all targets passed invariants
    1 — at least one target failed invariants (drift detected)
    2 — network/config error

Non-goals: does not validate app-specific content (trigger types, Room Lighting setting
encoding, etc.) — those are fragile by design. Only the SDK-level shape contract is audited.
"""

import argparse
import json
import sys
import urllib.request
import urllib.error
from typing import Any

# Edit to match the sample IDs on your hub. Each target is a (description, appId, pageName|None).
# Pick one of each rendering path so a firmware change that only affects one is still caught:
#   - Rule Machine rule (Rule-5.x)
#   - Room Lighting instance (Room Lights)
#   - Basic Rules (if any)
#   - HPM main page
#   - HPM sub-page (tests pageName navigation)
DEFAULT_TARGETS: list[tuple[str, int, str | None]] = [
    # ("description", appId, pageName or None)
]


def load_targets_from_config(path: str) -> list[tuple[str, int, str | None]]:
    with open(path) as f:
        raw = json.load(f)
    return [(t["description"], int(t["appId"]), t.get("pageName")) for t in raw.get("targets", [])]


def fetch(hub_url: str, app_id: int, page_name: str | None, timeout: int = 15) -> dict[str, Any]:
    url = f"{hub_url.rstrip('/')}/installedapp/configure/json/{app_id}"
    if page_name:
        url += f"/{page_name}"
    req = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        body = resp.read().decode("utf-8", errors="replace")
    return json.loads(body)


def check_invariants(payload: Any) -> list[str]:
    """Return a list of failure messages. Empty list means all invariants held."""
    failures: list[str] = []

    if not isinstance(payload, dict):
        return [f"top-level is {type(payload).__name__}, expected object"]

    if not isinstance(payload.get("app"), dict):
        failures.append("missing or non-object 'app'")
    else:
        # app must have at least id and name
        app = payload["app"]
        if "id" not in app:
            failures.append("app.id missing")
        if "name" not in app:
            failures.append("app.name missing")

    config_page = payload.get("configPage")
    if not isinstance(config_page, dict):
        failures.append("missing or non-object 'configPage'")
        return failures

    sections = config_page.get("sections")
    if not isinstance(sections, list):
        failures.append("configPage.sections is not a list")
        return failures

    # Check each section's shape
    for si, section in enumerate(sections):
        if not isinstance(section, dict):
            failures.append(f"sections[{si}] is {type(section).__name__}, expected object")
            continue
        inputs = section.get("input")
        if inputs is not None:
            if not isinstance(inputs, list):
                failures.append(f"sections[{si}].input is {type(inputs).__name__}, expected list")
            else:
                for ii, inp in enumerate(inputs):
                    if not isinstance(inp, dict):
                        failures.append(f"sections[{si}].input[{ii}] is {type(inp).__name__}, expected object")
                        continue
                    if not isinstance(inp.get("name"), str):
                        failures.append(f"sections[{si}].input[{ii}].name is not a string")
                    if not isinstance(inp.get("type"), str):
                        failures.append(f"sections[{si}].input[{ii}].type is not a string")
        body = section.get("body")
        if body is not None and not isinstance(body, list):
            failures.append(f"sections[{si}].body is {type(body).__name__}, expected list")

    settings = payload.get("settings")
    if settings is not None and not isinstance(settings, dict):
        failures.append(f"'settings' is {type(settings).__name__}, expected object or absent")

    return failures


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    parser.add_argument("--hub-url", required=True, help="Hub URL (e.g. http://hubitat.local)")
    parser.add_argument("--config", help="JSON file with target app IDs to audit (see scripts/audit_config.example.json)")
    args = parser.parse_args()

    targets = DEFAULT_TARGETS[:]
    if args.config:
        try:
            targets.extend(load_targets_from_config(args.config))
        except (OSError, json.JSONDecodeError, KeyError, ValueError) as e:
            print(f"Failed to load config {args.config}: {e}", file=sys.stderr)
            return 2

    if not targets:
        print("No targets to audit. Pass --config with audit_config.json, or populate DEFAULT_TARGETS.", file=sys.stderr)
        return 2

    total_failures = 0
    for desc, app_id, page_name in targets:
        label = f"{desc} (appId={app_id}{', page=' + page_name if page_name else ''})"
        try:
            payload = fetch(args.hub_url, app_id, page_name)
        except (urllib.error.URLError, urllib.error.HTTPError, json.JSONDecodeError, OSError) as e:
            print(f"[FAIL] {label}: fetch error: {e}", file=sys.stderr)
            total_failures += 1
            continue

        failures = check_invariants(payload)
        if failures:
            print(f"[FAIL] {label}:", file=sys.stderr)
            for f in failures:
                print(f"    - {f}", file=sys.stderr)
            total_failures += 1
        else:
            print(f"[OK]   {label}")

    print()
    print(f"Audited {len(targets)} target(s). {total_failures} failure(s).")
    return (1 if total_failures else 0)


if __name__ == "__main__":
    sys.exit(main())
