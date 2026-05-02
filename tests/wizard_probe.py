#!/usr/bin/env python3
"""
Hubitat MCP Server -- RM 5.1 Wizard-State Leak Probe

Systematically enumerates suspected wizard-state-leak pairs by running them on
fresh test rules, snapshots the rule's mainPage render after each step, and
reports which sequences produce broken renders or silent rejections.

Use this as both a diagnostics tool (identify new leaks) and a regression harness
(firmware upgrades, code changes).

Configuration (in order of precedence):
  1. Environment variables: HUB_URL, MCP_ACCESS_TOKEN, MCP_APP_ID
  2. tests/e2e_config.json (same format as e2e_test.py)

Usage (matrix runner):
    uv run --python 3.12 --with pyyaml tests/wizard_probe.py --matrix tests/wizard_probe_matrix.yaml
    uv run --python 3.12 --with pyyaml tests/wizard_probe.py --matrix tests/wizard_probe_matrix.yaml --probe A1_addRE_then_addAction
    uv run --python 3.12 --with pyyaml tests/wizard_probe.py --matrix tests/wizard_probe_matrix.yaml --group A
    uv run --python 3.12 --with pyyaml tests/wizard_probe.py --matrix tests/wizard_probe_matrix.yaml --baseline tests/wizard_probe_results/20260430_120000.json
    uv run --python 3.12 --with pyyaml tests/wizard_probe.py --matrix tests/wizard_probe_matrix.yaml --cleanup
    uv run --python 3.12 --with pyyaml tests/wizard_probe.py --matrix tests/wizard_probe_matrix.yaml --auto-backup

Usage (diag mode -- importable API):
    from tests.wizard_probe import quick_probe, load_config, HubitatMcpClient

    config = load_config()
    client = HubitatMcpClient(config["hub_url"], config["app_id"],
                               config["access_token"], verbose=True)
    client.initialize()

    result = quick_probe(client, "my_diag", steps=[
        {"addRequiredExpression": {"conditions": [...]}},
        # raw_button and raw_setting are escape hatches for low-level page
        # manipulation via walkStep (click / write operations):
        {"raw_button": {"page": "doActPage", "name": "actionCancel"}},
        {"raw_setting": {"page": "doActPage",
                         "settings": {"actType.1": "condActs"}}},
    ])
    # result["final"]["render"] -- joined paragraph text
    # result["final"]["broken"] -- True if "Broken Condition" present
    # result["snapshots"]       -- per-step snapshot dicts
    # result["errors"]          -- list of step error strings (empty = clean)

    See tests/wizard_probe_examples/diag_demo.py for a worked example.

Dependencies: requests, pyyaml (no other third-party packages)
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional

import requests
import yaml

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

PROBE_PREFIX = "_PROBE_"
RESULTS_DIR = Path(__file__).resolve().parent / "wizard_probe_results"

# ---------------------------------------------------------------------------
# Exceptions
# ---------------------------------------------------------------------------


class McpError(Exception):
    """JSON-RPC level error from the MCP endpoint."""


class McpToolError(McpError):
    """MCP tool returned isError: true."""

    def __init__(self, tool_name: str, message: str):
        self.tool_name = tool_name
        super().__init__(f"Tool '{tool_name}' error: {message}")


class ProbeAssertionError(Exception):
    """A probe's post-step expectation was not met."""


# ---------------------------------------------------------------------------
# MCP Client (standalone -- no import from e2e_test.py)
# ---------------------------------------------------------------------------


class HubitatMcpClient:
    """Thin JSON-RPC 2.0 client for the Hubitat MCP Server endpoint."""

    def __init__(self, hub_url: str, app_id: str, access_token: str, verbose: bool = False):
        self.hub_url = hub_url.rstrip("/")
        self.app_id = app_id
        self.endpoint = f"{self.hub_url}/apps/api/{app_id}/mcp"
        self.access_token = access_token
        self.verbose = verbose
        self._request_id = 0
        self._masked_token = access_token[:4] + "..." if len(access_token) > 4 else "****"

    def _log(self, msg: str) -> None:
        if self.verbose:
            print(f"    [DEBUG] {msg}", flush=True)

    def _send(self, method: str, params: Optional[dict] = None) -> dict:
        """Send one JSON-RPC 2.0 request. Returns the parsed result."""
        self._request_id += 1
        payload: dict[str, Any] = {
            "jsonrpc": "2.0",
            "id": self._request_id,
            "method": method,
        }
        if params is not None:
            payload["params"] = params

        self._log(f">> {method} {json.dumps(params or {})[:400]}")

        # Gentle rate-limit -- the hub's HTTP stack is single-threaded per app
        time.sleep(0.3)

        resp = requests.post(
            self.endpoint,
            params={"access_token": self.access_token},
            json=payload,
            timeout=45,
        )
        resp.raise_for_status()
        data = resp.json()

        self._log(f"<< {json.dumps(data)[:600]}")

        if "error" in data:
            raise McpError(f"JSON-RPC error: {data['error']}")

        return data.get("result", {})

    def initialize(self) -> dict:
        return self._send("initialize", {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "wizard-probe", "version": "1.0.0"},
        })

    def call_tool(self, name: str, arguments: Optional[dict] = None) -> Any:
        """Call an MCP tool. Returns parsed content text (dict / list / str)."""
        result = self._send("tools/call", {"name": name, "arguments": arguments or {}})

        if result.get("isError"):
            content_text = ""
            for c in result.get("content", []):
                if c.get("type") == "text":
                    content_text = c["text"]
            raise McpToolError(name, content_text)

        for c in result.get("content", []):
            if c.get("type") == "text":
                try:
                    return json.loads(c["text"])
                except (json.JSONDecodeError, TypeError):
                    return c["text"]

        return result


# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------


def load_config() -> dict:
    """Load hub connection config from e2e_config.json or env vars."""
    config_path = Path(__file__).resolve().parent / "e2e_config.json"
    config: dict = {}

    if config_path.exists():
        with open(config_path, "r", encoding="utf-8") as f:
            config = json.load(f)

    config["hub_url"] = os.environ.get("HUB_URL",
                         os.environ.get("HUBITAT_HUB_URL",
                         config.get("hub_url", "http://10.2.50.151")))
    config["app_id"] = os.environ.get("MCP_APP_ID",
                        os.environ.get("HUBITAT_APP_ID",
                        config.get("app_id", "953")))
    config["access_token"] = os.environ.get("MCP_ACCESS_TOKEN",
                              os.environ.get("HUBITAT_ACCESS_TOKEN",
                              config.get("access_token", "")))

    missing = [k for k in ("hub_url", "app_id", "access_token") if not config.get(k)]
    if missing:
        print(f"ERROR: Missing config: {', '.join(missing)}")
        print("  Set via tests/e2e_config.json or env vars HUB_URL, MCP_APP_ID, MCP_ACCESS_TOKEN")
        sys.exit(1)

    return config


# ---------------------------------------------------------------------------
# Matrix loader
# ---------------------------------------------------------------------------


def load_matrix(path: str) -> dict:
    """Load and validate the probe matrix YAML file."""
    p = Path(path)
    if not p.exists():
        print(f"ERROR: Matrix file not found: {path}")
        sys.exit(1)

    with open(p, "r", encoding="utf-8") as f:
        matrix = yaml.safe_load(f)

    if not isinstance(matrix, dict):
        print("ERROR: Matrix YAML must be a dict at top level.")
        sys.exit(1)

    if "probes" not in matrix:
        print("ERROR: Matrix YAML must have a 'probes' key.")
        sys.exit(1)

    if "device_pool" not in matrix:
        print("ERROR: Matrix YAML must have a 'device_pool' key.")
        sys.exit(1)

    return matrix


# ---------------------------------------------------------------------------
# Device-ID resolver
# ---------------------------------------------------------------------------


def resolve_device_ids(obj: Any, pool: dict) -> Any:
    """
    Recursively replace $key references in probe step dicts with integer IDs
    from the device_pool.  e.g. "$switch" -> 1063.

    Handles: strings, lists, dicts.
    """
    if isinstance(obj, str):
        if obj.startswith("$"):
            key = obj[1:]
            if key not in pool:
                raise ValueError(f"device_pool missing key '{key}' (referenced as ${key})")
            return pool[key]
        return obj
    if isinstance(obj, list):
        return [resolve_device_ids(item, pool) for item in obj]
    if isinstance(obj, dict):
        return {k: resolve_device_ids(v, pool) for k, v in obj.items()}
    return obj


# ---------------------------------------------------------------------------
# Snapshot helper
# ---------------------------------------------------------------------------


def snapshot_rule(client: HubitatMcpClient, app_id: int,
                  include_settings: bool = True) -> dict:
    """
    Fetch the rule's mainPage render + optionally its settings.
    Returns a dict with keys:
      paragraphs: list[str]   -- plain-text paragraph strings from the page
      settings: dict | None   -- settings map when include_settings=True
      raw: dict               -- full get_app_config response
      error: str | None       -- if the fetch failed

    The get_app_config response shape for RM rules is:
      { success, app, page: { sections: [ { inputs: [...], paragraphs: [...] } ] },
        settings: {...} }

    Paragraph text lives on section.paragraphs (list[str]) -- NOT on
    section.inputs[].  Each section may also have no paragraphs key at all.
    """
    try:
        args: dict = {"appId": app_id}
        if include_settings:
            args["includeSettings"] = True

        result = client.call_tool("manage_installed_apps", {
            "tool": "get_app_config",
            "args": args,
        })

        paragraphs: list[str] = []
        settings: Optional[dict] = None

        if isinstance(result, dict):
            # Navigate to page.sections -- the actual RM response nests here
            page = result.get("page") or {}
            sections = page.get("sections", [])

            # Fallback: some response shapes surface sections at the top level
            if not sections:
                sections = result.get("sections", [])

            for sec in sections:
                if not isinstance(sec, dict):
                    continue
                # Paragraphs are a list[str] on the section dict itself
                for para in sec.get("paragraphs", []):
                    if isinstance(para, str):
                        paragraphs.append(para)
                    elif isinstance(para, dict):
                        t = para.get("text") or para.get("value") or ""
                        if t:
                            paragraphs.append(str(t))
                # Also extract text from paragraph-type inputs (some app types use these)
                for inp in sec.get("inputs", []):
                    if isinstance(inp, dict) and inp.get("type") == "paragraph":
                        t = inp.get("title") or inp.get("description") or ""
                        if t:
                            paragraphs.append(str(t))

            settings = result.get("settings")

        return {
            "paragraphs": paragraphs,
            "settings": settings,
            "raw": result,
            "error": None,
        }

    except Exception as exc:
        return {
            "paragraphs": [],
            "settings": None,
            "raw": {},
            "error": str(exc),
        }


# ---------------------------------------------------------------------------
# Step executor
# ---------------------------------------------------------------------------


def execute_step(client: HubitatMcpClient, app_id: int,
                 step: dict, pool: dict, verbose: bool) -> dict:
    """
    Execute one probe step against the hub.  Steps are single-key dicts:
      addTrigger: <spec>
      addAction: <spec>
      addRequiredExpression: <spec>
      addActions: [<spec>, ...]
      addTriggers: [<spec>, ...]
      button: <name>
      settings: <map>
      clearActions: true
      replaceActions: [<spec>, ...]
      removeAction: <spec>
      moveAction: <spec>
      addLocalVar: <spec>
      getAppConfig: true          -- snapshot only, no mutation
      setLabel: <string>          -- shortcut for settings write
      pauseRule: true             -- button click shortcut
      resumeRule: true            -- button click shortcut
      updateRule: true            -- button click shortcut

    Returns a dict with:
      op: str          -- step key name
      result: Any      -- raw tool response
      error: str|None  -- exception message if call failed
    """
    resolved = resolve_device_ids(step, pool)

    # Determine which op key is present
    op = next(iter(resolved), None)
    if op is None:
        return {"op": "empty", "result": None, "error": "Empty step dict"}

    value = resolved[op]

    args: dict = {"appId": app_id, "confirm": True}

    # Map step op to update_native_app args
    if op == "addTrigger":
        args["addTrigger"] = value
    elif op == "addTriggers":
        args["addTriggers"] = value
    elif op == "addAction":
        args["addAction"] = value
    elif op == "addActions":
        args["addActions"] = value
    elif op == "addRequiredExpression":
        args["addRequiredExpression"] = value
    elif op == "clearActions":
        args["clearActions"] = True
    elif op == "replaceActions":
        args["replaceActions"] = value
    elif op == "removeAction":
        args["removeAction"] = value
    elif op == "moveAction":
        args["moveAction"] = value
    elif op == "addLocalVar":
        args["addLocalVariable"] = value
    elif op == "button":
        args["button"] = str(value)
        del args["confirm"]
        args["confirm"] = True
    elif op == "settings":
        args["settings"] = value
    elif op == "setLabel":
        # Write a label via settings -- RM uses ruleTitle
        args["settings"] = {"ruleTitle": str(value)}
    elif op == "pauseRule":
        args["button"] = "pausRule"
    elif op == "resumeRule":
        args["button"] = "resRule"
    elif op == "updateRule":
        args["button"] = "updateRule"
    elif op == "getAppConfig":
        # Read-only snapshot step -- not a mutation
        snap = snapshot_rule(client, app_id, include_settings=True)
        return {"op": op, "result": snap, "error": snap.get("error")}
    elif op == "raw_button":
        # Escape hatch: direct button click on a named page via walkStep.
        # value must be a dict: {page: <pageName>, name: <buttonName>}
        # Optional key: state_attribute (stateAttribute in the click body).
        #
        # Use this in diag scripts when you need to fire a specific button on a
        # specific page without going through update_native_app's high-level logic
        # (e.g., testing actionCancel vs actionDone in isolation).
        #
        # Mapped to: walkStep={page, operation:"click", click:{name, stateAttribute?}}
        if not isinstance(value, dict):
            return {"op": op, "result": None,
                    "error": "raw_button value must be a dict {page, name[, state_attribute]}"}
        page_name = value.get("page")
        btn_name = value.get("name")
        if not page_name or not btn_name:
            return {"op": op, "result": None,
                    "error": "raw_button requires 'page' and 'name' keys"}

        click_spec: dict = {"name": btn_name}
        if value.get("state_attribute"):
            click_spec["stateAttribute"] = value["state_attribute"]

        walk_args = {
            "appId": app_id,
            "confirm": True,
            "walkStep": {
                "page": page_name,
                "operation": "click",
                "click": click_spec,
            },
        }

        if verbose:
            print(f"      [step] raw_button: page={page_name} name={btn_name}", flush=True)

        try:
            result = client.call_tool("manage_native_rules_and_apps", {
                "tool": "update_native_app",
                "args": walk_args,
            })
            return {"op": op, "result": result, "error": None}
        except Exception as exc:
            return {"op": op, "result": None, "error": str(exc)}

    elif op == "raw_setting":
        # Escape hatch: direct settings write on a named page via walkStep.
        # value must be a dict: {page: <pageName>, settings: {key: value, ...}}
        #
        # Use this in diag scripts when you need to write specific settings to a
        # specific page (e.g., setting actType.1=condActs mid-edit without
        # going through a full addAction call).
        #
        # walkStep.write requires exactly ONE key per call, so this step fires
        # one walkStep per entry in the settings dict.  All calls use the same page.
        # The last call's result is returned as the step result.
        if not isinstance(value, dict):
            return {"op": op, "result": None,
                    "error": "raw_setting value must be a dict {page, settings: {...}}"}
        page_name = value.get("page")
        settings_map = value.get("settings")
        if not page_name or not isinstance(settings_map, dict):
            return {"op": op, "result": None,
                    "error": "raw_setting requires 'page' (str) and 'settings' (dict) keys"}
        if not settings_map:
            return {"op": op, "result": None,
                    "error": "raw_setting 'settings' dict must not be empty"}

        if verbose:
            print(f"      [step] raw_setting: page={page_name} "
                  f"settings={list(settings_map.keys())}", flush=True)

        last_result = None
        for skey, sval in settings_map.items():
            walk_args = {
                "appId": app_id,
                "confirm": True,
                "walkStep": {
                    "page": page_name,
                    "operation": "write",
                    "write": {skey: sval},
                    "validateEnum": False,  # Diag mode -- skip enum validation
                },
            }
            try:
                last_result = client.call_tool("manage_native_rules_and_apps", {
                    "tool": "update_native_app",
                    "args": walk_args,
                })
            except Exception as exc:
                return {"op": op, "result": last_result, "error": str(exc)}

        return {"op": op, "result": last_result, "error": None}

    else:
        return {"op": op, "result": None,
                "error": f"Unknown step op '{op}' -- check matrix YAML spelling"}

    if verbose:
        print(f"      [step] {op}: {json.dumps(args)[:200]}", flush=True)

    try:
        result = client.call_tool("manage_native_rules_and_apps", {
            "tool": "update_native_app",
            "args": args,
        })
        return {"op": op, "result": result, "error": None}
    except Exception as exc:
        return {"op": op, "result": None, "error": str(exc)}


# ---------------------------------------------------------------------------
# Expectation evaluator
# ---------------------------------------------------------------------------


def evaluate_expectations(expect: dict, final_snap: dict,
                           step_results: list[dict]) -> tuple[bool, list[str]]:
    """
    Evaluate all expectations declared on a probe against the final snapshot.

    Returns (all_passed: bool, failure_messages: list[str]).
    """
    if expect.get("skip"):
        return True, []  # Probe is TODO -- auto-pass

    failures: list[str] = []
    paragraphs = final_snap.get("paragraphs", [])
    settings = final_snap.get("settings") or {}
    all_para_text = "\n".join(paragraphs)

    for key, expected_val in expect.items():
        if key == "skip" or key == "todo":
            continue

        if key == "final_render_NOT_contains":
            if expected_val in all_para_text:
                failures.append(
                    f"FAIL final_render_NOT_contains '{expected_val}': "
                    f"found it in render -- render text: {all_para_text[:300]!r}"
                )

        elif key == "final_render_contains":
            if expected_val not in all_para_text:
                failures.append(
                    f"FAIL final_render_contains '{expected_val}': "
                    f"not found -- render text: {all_para_text[:300]!r}"
                )

        elif key == "final_settings_contains_key":
            if expected_val not in settings:
                failures.append(
                    f"FAIL final_settings_contains_key '{expected_val}': "
                    f"key absent -- settings keys: {list(settings.keys())[:20]}"
                )

        elif key == "final_settings_value":
            if not isinstance(expected_val, dict):
                failures.append(f"FAIL final_settings_value must be a dict, got {type(expected_val)}")
                continue
            for skey, sval in expected_val.items():
                actual = settings.get(skey)
                if actual != sval:
                    failures.append(
                        f"FAIL final_settings_value['{skey}'] expected={sval!r} got={actual!r}"
                    )

        elif key == "no_step_errors":
            # Convenience: assert no step returned an error
            for sr in step_results:
                if sr.get("error"):
                    failures.append(
                        f"FAIL no_step_errors: step '{sr['op']}' errored: {sr['error'][:200]}"
                    )

        elif key == "health_ok":
            # Expect the rule's health check to be clean
            raw = final_snap.get("raw", {})
            health = raw.get("health") if isinstance(raw, dict) else None
            if health is None:
                # health not embedded in get_app_config; skip this assertion
                pass
            elif not health.get("ok"):
                issues = health.get("issues", [])
                failures.append(
                    f"FAIL health_ok: rule health not ok -- issues: {issues}"
                )

        else:
            # Unrecognized expect key -- warn but don't fail
            print(f"    [WARN] Unknown expect key '{key}' -- skipped", flush=True)

    return len(failures) == 0, failures


# ---------------------------------------------------------------------------
# Single probe runner
# ---------------------------------------------------------------------------


def run_probe(client: HubitatMcpClient, probe: dict, pool: dict,
              timestamp: str, verbose: bool) -> dict:
    """
    Run one probe end-to-end.  Returns a structured result dict:
      name, group, description, status (pass/fail/skip/error),
      app_id (or None), steps (list of step records), final_snapshot,
      failures (list of failure messages), duration_s
    """
    name = probe.get("name", "unnamed")
    group = probe.get("group", "?")
    description = probe.get("description", "")
    steps = probe.get("steps", [])
    expect = probe.get("expect", {})

    # TODO / skip handling
    if expect.get("skip"):
        todo_msg = expect.get("todo", "no detail")
        return {
            "name": name, "group": group, "description": description,
            "status": "skip", "todo": todo_msg,
            "app_id": None, "steps": [], "final_snapshot": None,
            "failures": [], "duration_s": 0.0,
        }

    t0 = time.monotonic()
    app_id: Optional[int] = None
    step_records: list[dict] = []
    final_snap: dict = {}
    failures: list[str] = []
    status: str = "error"

    rule_name = f"{PROBE_PREFIX}{name}_{timestamp}"
    print(f"  [{group}] {name}: create rule '{rule_name}'", flush=True)

    try:
        # --- Create test rule ---
        create_result = client.call_tool("manage_native_rules_and_apps", {
            "tool": "create_native_app",
            "args": {
                "appType": "rule_machine",
                "name": rule_name,
                "confirm": True,
            },
        })

        app_id_raw = None
        if isinstance(create_result, dict):
            app_id_raw = (create_result.get("appId") or
                          create_result.get("id") or
                          create_result.get("ruleId"))
        if app_id_raw is None:
            raise RuntimeError(f"create_native_app did not return appId: {create_result}")

        app_id = int(app_id_raw)
        print(f"    appId={app_id}", flush=True)

        # --- Execute each step, snapshotting after each ---
        for i, step in enumerate(steps):
            step_result = execute_step(client, app_id, step, pool, verbose)
            op = step_result.get("op", "?")
            err = step_result.get("error")

            # Snapshot after every step
            snap = snapshot_rule(client, app_id, include_settings=(i == len(steps) - 1))
            step_result["snapshot_paragraphs"] = snap["paragraphs"]

            step_records.append(step_result)

            if verbose:
                print(f"      -> snap paragraphs: {snap['paragraphs'][:5]}", flush=True)

            if err:
                print(f"    [step {i+1}/{len(steps)} {op}] ERROR: {err[:120]}", flush=True)
            else:
                print(f"    [step {i+1}/{len(steps)} {op}] OK", flush=True)

        # --- Final snapshot with settings ---
        final_snap = snapshot_rule(client, app_id, include_settings=True)
        if final_snap.get("error"):
            print(f"    [WARN] Final snapshot error: {final_snap['error']}", flush=True)

        # --- Evaluate expectations ---
        passed, failures = evaluate_expectations(expect, final_snap, step_records)
        status = "pass" if passed else "fail"

        if passed:
            print(f"    PASS", flush=True)
        else:
            for msg in failures:
                print(f"    {msg}", flush=True)

    except Exception as exc:
        status = "error"
        failures = [f"Unexpected exception: {exc}"]
        print(f"    ERROR: {exc}", flush=True)

    finally:
        # --- Always delete the test rule ---
        if app_id is not None:
            try:
                client.call_tool("manage_native_rules_and_apps", {
                    "tool": "delete_native_app",
                    "args": {"appId": app_id, "confirm": True},
                })
                print(f"    deleted appId={app_id}", flush=True)
            except Exception as del_exc:
                print(f"    [WARN] Delete failed for appId={app_id}: {del_exc}", flush=True)

    duration = time.monotonic() - t0

    return {
        "name": name,
        "group": group,
        "description": description,
        "status": status,
        "app_id": app_id,
        "steps": step_records,
        "final_snapshot": {
            "paragraphs": final_snap.get("paragraphs", []),
            "settings_keys": list((final_snap.get("settings") or {}).keys()),
            "error": final_snap.get("error"),
        },
        "failures": failures,
        "duration_s": round(duration, 2),
    }


# ---------------------------------------------------------------------------
# Cleanup scanner
# ---------------------------------------------------------------------------


def cleanup_stale_probes(client: HubitatMcpClient) -> None:
    """
    Scan list_rm_rules for any rule matching _PROBE_* from prior failed runs
    and offer to delete them interactively.
    """
    print("Scanning for stale _PROBE_* rules...", flush=True)

    try:
        result = client.call_tool("manage_native_rules_and_apps", {
            "tool": "list_rm_rules",
            "args": {},
        })
        rules = []
        if isinstance(result, list):
            rules = result
        elif isinstance(result, dict):
            rules = result.get("rules", []) or result.get("data", []) or []

    except Exception as exc:
        print(f"  [WARN] Could not list RM rules: {exc}", flush=True)
        return

    stale = [r for r in rules
             if isinstance(r, dict)
             and PROBE_PREFIX in (r.get("name") or r.get("label") or "")]

    if not stale:
        print("  No stale probe rules found.", flush=True)
        return

    print(f"  Found {len(stale)} stale probe rule(s):", flush=True)
    for r in stale:
        rid = r.get("id") or r.get("appId") or r.get("ruleId")
        rname = r.get("name") or r.get("label") or "?"
        print(f"    id={rid}  name={rname}", flush=True)

    answer = input("  Delete all stale probe rules? [y/N] ").strip().lower()
    if answer != "y":
        print("  Skipping cleanup.", flush=True)
        return

    for r in stale:
        rid = r.get("id") or r.get("appId") or r.get("ruleId")
        rname = r.get("name") or r.get("label") or "?"
        try:
            client.call_tool("manage_native_rules_and_apps", {
                "tool": "delete_native_app",
                "args": {"appId": rid, "confirm": True},
            })
            print(f"  Deleted: {rname} (id={rid})", flush=True)
        except Exception as exc:
            print(f"  [WARN] Failed to delete {rname}: {exc}", flush=True)


# ---------------------------------------------------------------------------
# Pre-flight checks
# ---------------------------------------------------------------------------


def preflight(client: HubitatMcpClient, auto_backup: bool) -> None:
    """
    Verify hub readiness before probing:
    - hubAdminWriteEnabled must be true
    - builtinAppEnabled must be true
    - Recent backup (auto-create if --auto-backup)
    """
    print("Pre-flight checks...", flush=True)

    try:
        info = client.call_tool("get_hub_info")
    except Exception as exc:
        print(f"  ERROR: get_hub_info failed: {exc}")
        sys.exit(1)

    if isinstance(info, dict):
        if not info.get("hubAdminWriteEnabled", True):
            print("  ERROR: Hub Admin Write is disabled -- enable it in MCP settings.")
            sys.exit(1)
        if not info.get("builtinAppEnabled", True):
            print("  ERROR: Built-in App Tools is disabled -- enable it in MCP settings.")
            sys.exit(1)
    else:
        print("  [WARN] get_hub_info returned unexpected shape; cannot verify gates.", flush=True)

    # Backup check
    backup_age_ok = False
    if isinstance(info, dict):
        last_backup = info.get("lastBackup") or info.get("lastBackupTime")
        if last_backup:
            backup_age_ok = True  # Trust the hub; exact age check is in the gate itself

    if not backup_age_ok:
        if auto_backup:
            print("  Creating hub backup (--auto-backup)...", flush=True)
            try:
                bk = client.call_tool("create_hub_backup", {"confirm": True})
                # Encode backup message safely for Windows console (may contain arrows/non-ASCII)
                bk_str = str(bk).encode("ascii", errors="replace").decode("ascii")
                print(f"  Backup: {bk_str}", flush=True)
            except Exception as exc:
                print(f"  [WARN] Backup creation failed: {exc}", flush=True)
        else:
            answer = input(
                "  No recent backup detected. Create one now? [y/N] "
            ).strip().lower()
            if answer == "y":
                try:
                    bk = client.call_tool("create_hub_backup", {"confirm": True})
                    bk_str = str(bk).encode("ascii", errors="replace").decode("ascii")
                    print(f"  Backup: {bk_str}", flush=True)
                except Exception as exc:
                    print(f"  [WARN] Backup creation failed: {exc}", flush=True)
            else:
                print(
                    "  Proceeding without backup. Some operations may be blocked "
                    "by the hub's safety gate."
                )

    print("  Pre-flight OK.", flush=True)


# ---------------------------------------------------------------------------
# Report writers
# ---------------------------------------------------------------------------


def write_json_report(results: list[dict], run_ts: str) -> Path:
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    path = RESULTS_DIR / f"{run_ts}.json"
    report = {
        "run_timestamp": run_ts,
        "total": len(results),
        "passed": sum(1 for r in results if r["status"] == "pass"),
        "failed": sum(1 for r in results if r["status"] == "fail"),
        "errors": sum(1 for r in results if r["status"] == "error"),
        "skipped": sum(1 for r in results if r["status"] == "skip"),
        "probes": results,
    }
    with open(path, "w", encoding="utf-8") as f:
        json.dump(report, f, indent=2)
    return path


def write_markdown_report(results: list[dict], run_ts: str) -> Path:
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    path = RESULTS_DIR / f"{run_ts}.md"

    passed = [r for r in results if r["status"] == "pass"]
    failed = [r for r in results if r["status"] in ("fail", "error")]
    skipped = [r for r in results if r["status"] == "skip"]

    lines = [
        f"# Wizard Probe Run -- {run_ts}",
        "",
        f"**Total:** {len(results)}  "
        f"**Passed:** {len(passed)}  "
        f"**Failed:** {len(failed)}  "
        f"**Skipped (TODO):** {len(skipped)}",
        "",
    ]

    # Group summary table
    groups: dict[str, dict] = {}
    for r in results:
        g = r["group"]
        if g not in groups:
            groups[g] = {"pass": 0, "fail": 0, "skip": 0, "error": 0}
        groups[g][r["status"]] += 1

    lines += ["## Group Summary", "", "| Group | Pass | Fail | Skip | Error |",
              "|-------|------|------|------|-------|"]
    for g in sorted(groups):
        c = groups[g]
        lines.append(f"| {g} | {c['pass']} | {c['fail']} | {c['skip']} | {c['error']} |")
    lines += [""]

    # Failed probes
    if failed:
        lines += ["## Failed Probes", ""]
        for r in failed:
            lines += [
                f"### {r['name']} ({r['group']}) -- {r['status'].upper()}",
                f"*{r['description']}*",
                "",
                f"**Duration:** {r['duration_s']}s",
                "",
                "**Failures:**",
            ]
            for msg in r.get("failures", []):
                lines.append(f"- {msg}")
            snap = r.get("final_snapshot") or {}
            paras = snap.get("paragraphs", [])
            if paras:
                lines += ["", "**Final render paragraphs:**", "```"]
                lines.extend(paras[:20])
                lines += ["```"]
            lines.append("")

    # Passed probes
    if passed:
        lines += ["## Passed Probes", ""]
        for r in passed:
            lines.append(f"- **{r['name']}** ({r['group']}) -- {r['duration_s']}s")
        lines.append("")

    # Skipped probes
    if skipped:
        lines += ["## Skipped (TODO) Probes", ""]
        for r in skipped:
            todo = r.get("todo", "no detail")
            lines.append(f"- **{r['name']}** ({r['group']}) -- {todo}")
        lines.append("")

    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")

    return path


# ---------------------------------------------------------------------------
# Baseline diff
# ---------------------------------------------------------------------------


def baseline_diff(current_results: list[dict], baseline_path: str) -> None:
    """
    Compare current run against a prior run's JSON file.
    Prints: newly failing probes, newly passing probes, render-output changes.
    """
    bp = Path(baseline_path)
    if not bp.exists():
        print(f"[WARN] Baseline file not found: {baseline_path}", flush=True)
        return

    with open(bp, "r", encoding="utf-8") as f:
        baseline = json.load(f)

    baseline_by_name: dict[str, dict] = {
        p["name"]: p for p in baseline.get("probes", [])
    }
    current_by_name: dict[str, dict] = {
        p["name"]: p for p in current_results
    }

    print("\n=== Baseline Comparison ===", flush=True)

    newly_failing = []
    newly_passing = []
    render_changed = []

    for name, cur in current_by_name.items():
        base = baseline_by_name.get(name)
        if base is None:
            print(f"  NEW PROBE: {name} -> {cur['status']}", flush=True)
            continue

        if base["status"] in ("pass",) and cur["status"] in ("fail", "error"):
            newly_failing.append(name)
        elif base["status"] in ("fail", "error") and cur["status"] == "pass":
            newly_passing.append(name)

        # Render diff
        base_paras = (base.get("final_snapshot") or {}).get("paragraphs", [])
        cur_paras = (cur.get("final_snapshot") or {}).get("paragraphs", [])
        if base_paras != cur_paras:
            render_changed.append((name, base_paras, cur_paras))

    print(f"  Newly failing ({len(newly_failing)}): {newly_failing}", flush=True)
    print(f"  Newly passing ({len(newly_passing)}): {newly_passing}", flush=True)
    print(f"  Render output changed ({len(render_changed)}):", flush=True)
    for name, old, new in render_changed[:10]:
        print(f"    {name}:", flush=True)
        print(f"      before: {old[:3]}", flush=True)
        print(f"      after:  {new[:3]}", flush=True)

    # Also list removed probes
    for name in baseline_by_name:
        if name not in current_by_name:
            print(f"  REMOVED PROBE: {name}", flush=True)


# ---------------------------------------------------------------------------
# Main summary printer
# ---------------------------------------------------------------------------


def print_summary(results: list[dict]) -> bool:
    """Print final summary. Returns True if all non-skip probes passed."""
    print("\n" + "=" * 60, flush=True)
    print("Wizard Probe Results", flush=True)
    print("=" * 60, flush=True)

    groups: dict[str, dict] = {}
    for r in results:
        g = r["group"]
        if g not in groups:
            groups[g] = {"pass": 0, "fail": 0, "error": 0, "skip": 0}
        groups[g][r["status"]] += 1

    group_parts = []
    for g in sorted(groups):
        c = groups[g]
        ran = c["pass"] + c["fail"] + c["error"]
        group_parts.append(f"group {g}: {c['pass']}/{ran}")
        print(f"  {g:<10s}  pass={c['pass']} fail={c['fail']} "
              f"error={c['error']} skip={c['skip']}", flush=True)

    total_ran = sum(r["status"] in ("pass", "fail", "error") for r in results)
    total_pass = sum(r["status"] == "pass" for r in results)
    total_fail = sum(r["status"] in ("fail", "error") for r in results)
    total_skip = sum(r["status"] == "skip" for r in results)

    print("-" * 60, flush=True)
    group_summary = ", ".join(group_parts)
    print(f"Passed: {total_pass}/{total_ran} probes ({group_summary})", flush=True)
    if total_skip:
        print(f"Skipped (TODO): {total_skip}", flush=True)

    if total_fail:
        print("\nFailures:", flush=True)
        for r in results:
            if r["status"] in ("fail", "error"):
                msgs = r.get("failures", [])
                print(f"  - {r['name']}: {msgs[0][:120] if msgs else r['status']}", flush=True)

    print("=" * 60, flush=True)
    return total_fail == 0


# ---------------------------------------------------------------------------
# Diag mode -- importable quick_probe() API
# ---------------------------------------------------------------------------


def quick_probe(
    client: HubitatMcpClient,
    name: str,
    steps: list[dict],
    snapshot_each_step: bool = True,
    device_pool: Optional[dict] = None,
    verbose: bool = False,
) -> dict:
    """
    Run a one-off diagnostic probe without a matrix YAML file.

    Wraps the same create-rule / execute-steps / delete-rule lifecycle used by the
    matrix runner, but exposes it as a direct Python call for investigation scripts.
    Always cleans up via try/finally regardless of step failures.

    Parameters
    ----------
    client : HubitatMcpClient
        Initialized + connected client (call client.initialize() before this).
    name : str
        Human-readable name used in rule naming and output.  Alphanumeric + underscores.
        The created rule will be named _PROBE_<name>_<timestamp>.
    steps : list[dict]
        List of step dicts in the same format as the matrix YAML 'steps' list.
        All standard step ops are supported, plus the two escape hatches:
          raw_button:  {page: "<pageName>", name: "<buttonName>",
                        state_attribute: "<optional>"}
          raw_setting: {page: "<pageName>", settings: {key: value, ...}}
    snapshot_each_step : bool
        If True (default), capture a rule snapshot after every step.
        If False, only capture the final snapshot.  Set to False for speed when
        individual step snapshots are not needed.
    device_pool : dict | None
        Optional $-reference substitution pool (same as matrix device_pool).
        Pass None if no $references are used in the steps.
    verbose : bool
        Mirror the --verbose flag from the CLI: emit DEBUG lines for requests.

    Returns
    -------
    dict with keys:
        app_id : int | None
            The created rule's appId (None if creation failed).
        snapshots : list[dict]
            One entry per step (or just the final entry when snapshot_each_step=False).
            Each entry: {step_index, op, paragraphs, settings, error}.
        final : dict
            Convenience view of the last snapshot:
              render  (str)  -- paragraphs joined by newline
              broken  (bool) -- True if "Broken Condition" appears anywhere in render
              paragraphs (list[str])
              settings (dict | None)
              error (str | None)
        errors : list[str]
            Step-level error strings.  Empty list means every step succeeded.
        status : str
            "pass", "fail", or "error" based on whether steps completed without
            exception (does NOT evaluate custom expectations -- use the matrix
            runner for assertion-based testing; quick_probe is for investigation).
        duration_s : float

    Example
    -------
        from tests.wizard_probe import quick_probe, load_config, HubitatMcpClient

        config = load_config()
        client = HubitatMcpClient(config["hub_url"], config["app_id"],
                                   config["access_token"], verbose=True)
        client.initialize()

        result = quick_probe(client, "cancel_vs_done_diag", steps=[
            {"addRequiredExpression": {"conditions": [
                {"capability": "Switch", "deviceIds": [1063], "state": "on"}
            ]}},
            # raw_button fires a button click via walkStep (operation="click")
            {"raw_button": {"page": "doActPage", "name": "actionCancel"}},
            {"addAction": {"capability": "Switch", "deviceIds": [1063],
                           "command": "on"}},
        ])

        print(result["final"]["render"])
        print("broken:", result["final"]["broken"])
    """
    pool = device_pool or {}
    ts = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S")
    rule_name = f"{PROBE_PREFIX}{name}_{ts}"

    t0 = time.monotonic()
    app_id: Optional[int] = None
    snapshots: list[dict] = []
    step_errors: list[str] = []
    status = "error"

    if verbose:
        print(f"quick_probe '{name}': creating rule '{rule_name}'", flush=True)

    try:
        # --- Create test rule ---
        create_result = client.call_tool("manage_native_rules_and_apps", {
            "tool": "create_native_app",
            "args": {"appType": "rule_machine", "name": rule_name, "confirm": True},
        })

        app_id_raw = None
        if isinstance(create_result, dict):
            app_id_raw = (create_result.get("appId") or
                          create_result.get("id") or
                          create_result.get("ruleId"))
        if app_id_raw is None:
            raise RuntimeError(f"create_native_app did not return appId: {create_result}")

        app_id = int(app_id_raw)
        if verbose:
            print(f"  appId={app_id}", flush=True)

        # --- Execute steps ---
        for i, step in enumerate(steps):
            step_result = execute_step(client, app_id, step, pool, verbose)
            op = step_result.get("op", "?")
            err = step_result.get("error")

            if err:
                step_errors.append(f"step {i+1} ({op}): {err}")
                if verbose:
                    print(f"  step {i+1} ({op}): ERROR: {err[:120]}", flush=True)
            else:
                if verbose:
                    print(f"  step {i+1} ({op}): OK", flush=True)

            if snapshot_each_step:
                snap = snapshot_rule(client, app_id, include_settings=(i == len(steps) - 1))
                snapshots.append({
                    "step_index": i,
                    "op": op,
                    "paragraphs": snap["paragraphs"],
                    "settings": snap.get("settings"),
                    "error": snap.get("error"),
                })

        # --- Final snapshot (always taken) ---
        final_snap_raw = snapshot_rule(client, app_id, include_settings=True)
        if not snapshot_each_step:
            # When per-step snapshots are skipped, record just the final one
            snapshots.append({
                "step_index": len(steps) - 1,
                "op": "final",
                "paragraphs": final_snap_raw["paragraphs"],
                "settings": final_snap_raw.get("settings"),
                "error": final_snap_raw.get("error"),
            })

        render = "\n".join(final_snap_raw.get("paragraphs", []))
        final = {
            "render": render,
            "broken": "Broken Condition" in render,
            "paragraphs": final_snap_raw.get("paragraphs", []),
            "settings": final_snap_raw.get("settings"),
            "error": final_snap_raw.get("error"),
        }
        status = "fail" if step_errors else "pass"

    except Exception as exc:
        status = "error"
        step_errors.append(f"probe-level exception: {exc}")
        final = {
            "render": "",
            "broken": False,
            "paragraphs": [],
            "settings": None,
            "error": str(exc),
        }
        if verbose:
            print(f"  ERROR: {exc}", flush=True)

    finally:
        if app_id is not None:
            try:
                client.call_tool("manage_native_rules_and_apps", {
                    "tool": "delete_native_app",
                    "args": {"appId": app_id, "confirm": True},
                })
                if verbose:
                    print(f"  deleted appId={app_id}", flush=True)
            except Exception as del_exc:
                if verbose:
                    print(f"  [WARN] Delete failed for appId={app_id}: {del_exc}", flush=True)

    duration = time.monotonic() - t0

    return {
        "app_id": app_id,
        "snapshots": snapshots,
        "final": final,
        "errors": step_errors,
        "status": status,
        "duration_s": round(duration, 2),
    }


# ---------------------------------------------------------------------------
# CLI entry point
# ---------------------------------------------------------------------------


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Hubitat MCP RM 5.1 Wizard-State Leak Probe"
    )
    parser.add_argument("--matrix", required=True,
                        help="Path to probe matrix YAML file")
    parser.add_argument("--probe", default=None,
                        help="Run only the probe with this name")
    parser.add_argument("--group", default=None,
                        help="Run only probes in this group (A, B, C, D)")
    parser.add_argument("--baseline", default=None,
                        help="Path to a prior run's JSON file for diff comparison")
    parser.add_argument("--cleanup", action="store_true",
                        help="Scan for stale _PROBE_* rules and offer to delete them")
    parser.add_argument("--auto-backup", action="store_true",
                        help="Automatically create a hub backup if none exists recently")
    parser.add_argument("--verbose", "-v", action="store_true",
                        help="Show request/response details")
    args = parser.parse_args()

    config = load_config()
    masked = config["access_token"][:4] + "..." \
        if len(config["access_token"]) > 4 else "****"

    print("=" * 60, flush=True)
    print("Hubitat MCP -- RM Wizard State Probe", flush=True)
    print("=" * 60, flush=True)
    print(f"  Hub:    {config['hub_url']}", flush=True)
    print(f"  App:    {config['app_id']}", flush=True)
    print(f"  Token:  {masked}", flush=True)
    print(f"  Matrix: {args.matrix}", flush=True)
    print(flush=True)

    client = HubitatMcpClient(
        hub_url=config["hub_url"],
        app_id=config["app_id"],
        access_token=config["access_token"],
        verbose=args.verbose,
    )

    # Verify connectivity
    print("Verifying connectivity...", flush=True)
    try:
        client.initialize()
        print("  Hub reachable.\n", flush=True)
    except requests.exceptions.ConnectionError:
        print(f"  ERROR: Cannot connect to hub at {config['hub_url']}")
        sys.exit(1)
    except Exception as exc:
        print(f"  ERROR: Initialize failed: {exc}")
        sys.exit(1)

    # Cleanup mode
    if args.cleanup:
        cleanup_stale_probes(client)

    # Pre-flight
    preflight(client, auto_backup=args.auto_backup)

    # Load matrix
    matrix = load_matrix(args.matrix)
    pool = matrix.get("device_pool", {})
    all_probes = matrix.get("probes", [])

    # Filter probes
    probes_to_run: list[dict] = []
    for probe in all_probes:
        name = probe.get("name", "")
        group = probe.get("group", "")
        if args.probe and name != args.probe:
            continue
        if args.group and group != args.group:
            continue
        probes_to_run.append(probe)

    if not probes_to_run:
        print("No probes matched the filter criteria.", flush=True)
        sys.exit(0)

    print(f"Running {len(probes_to_run)} probe(s)...\n", flush=True)

    # Run timestamp (used for rule naming + output filenames)
    run_ts = datetime.now(timezone.utc).strftime("%Y%m%d_%H%M%S")

    results: list[dict] = []
    for probe in probes_to_run:
        result = run_probe(client, probe, pool, run_ts, verbose=args.verbose)
        results.append(result)
        print(flush=True)

    # Write output files
    json_path = write_json_report(results, run_ts)
    md_path = write_markdown_report(results, run_ts)
    print(f"\nResults written:", flush=True)
    print(f"  JSON: {json_path}", flush=True)
    print(f"  MD:   {md_path}", flush=True)

    # Baseline diff if requested
    if args.baseline:
        baseline_diff(results, args.baseline)

    # Print summary + exit code
    all_passed = print_summary(results)
    sys.exit(0 if all_passed else 1)


if __name__ == "__main__":
    main()
