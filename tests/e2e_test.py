#!/usr/bin/env python3
"""
Hubitat MCP Server — End-to-End Test Runner

Sends real JSON-RPC 2.0 requests to a Hubitat MCP Server endpoint and validates
responses. Requires a running hub with the MCP server app installed.

Configuration: tests/e2e_config.json (gitignored) or env vars
    HUBITAT_HUB_URL, HUBITAT_APP_ID, HUBITAT_ACCESS_TOKEN

Usage:
    python tests/e2e_test.py                    # run all tests
    python tests/e2e_test.py --group devices    # run one group
    python tests/e2e_test.py --test trigger     # run tests matching substring
    python tests/e2e_test.py --cleanup-only     # just sweep BAT_E2E_ artifacts
    python tests/e2e_test.py -v                 # verbose request/response logging
"""

from __future__ import annotations

import argparse
import json
import os
import random
import sys
import time
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import requests

# ---------------------------------------------------------------------------
# Artifact prefix — every test-created resource uses this for safe cleanup
# ---------------------------------------------------------------------------

PREFIX = "BAT_E2E_"

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


# ---------------------------------------------------------------------------
# MCP Client
# ---------------------------------------------------------------------------


def _op_key(name: str, arguments: dict | None) -> str:
    """Resolve the per-op timing key for the run summary: the gateway sub-tool (args['tool']) when present,
    else the flat tool name; hub_set_rule is split into :create (no inner appId) vs :edit so fixture cost is
    separable from mutation cost. Pure dict logic (unit-tested in test_e2e_test_helpers.py)."""
    args = arguments or {}
    key = args.get("tool", name)
    if key == "hub_set_rule":
        key += ":create" if not (args.get("args") or {}).get("appId") else ":edit"
    return key


class HubitatMcpClient:
    """Thin client for the Hubitat MCP Server JSON-RPC 2.0 endpoint."""

    def __init__(self, hub_url: str, app_id: str, access_token: str, verbose: bool = False):
        self.hub_url = hub_url.rstrip("/")
        self.app_id = app_id
        # Hubitat URL conventions differ between local hub and Hubitat cloud:
        #   Local: http://<hub-ip>/apps/api/<id>/mcp?access_token=...
        #   Cloud: https://cloud.hubitat.com/api/<UUID>/apps/<id>/mcp?access_token=...
        # In the cloud form, hub_url already includes /api/<UUID>/, so the
        # path under the app is just /apps/<id>/<endpoint> (no extra /api/).
        # Detect cloud by the cloud.hubitat.com host marker and adjust.
        if "cloud.hubitat.com" in self.hub_url:
            self._app_path_prefix = f"{self.hub_url}/apps/{app_id}"
        else:
            self._app_path_prefix = f"{self.hub_url}/apps/api/{app_id}"
        self.endpoint = f"{self._app_path_prefix}/mcp"
        self.access_token = access_token
        self.verbose = verbose
        self._request_id = 0
        # Per-op wall-clock timings (op_key, seconds) for the end-of-run "Per-op wall-clock" summary --
        # the only place real per-operation cost (RM create vs edit vs delete, etc.) is visible, since
        # the >> call traces are verbose-gated and never reach the CI log.
        self.op_timings: list[tuple[str, float]] = []
        # Mask token for safe logging: show first 4 chars only
        self._masked_token = access_token[:4] + "..." if len(access_token) > 4 else "****"

    def _log(self, msg: str) -> None:
        if self.verbose:
            print(f"    [DEBUG] {msg}")

    def _send(self, method: str, params: dict | None = None) -> dict:
        """Send a JSON-RPC 2.0 request and return the parsed result.

        Retries transient HTTP 5xx and network errors (cloud relay flake) with
        exponential backoff. Never retries on 4xx (real auth/request errors)
        or on JSON-RPC error responses (intentional tool behavior we're
        trying to test).

        Retries JSONDecodeError on the same budget — this catches transient
        Cloudflare HTML error pages on cloud endpoints under load.
        """
        self._request_id += 1
        payload: dict[str, Any] = {
            "jsonrpc": "2.0",
            "id": self._request_id,
            "method": method,
        }
        if params is not None:
            payload["params"] = params

        self._log(f">> {method} {json.dumps(params or {})[:300]}")

        # Rate-limit: don't overwhelm the hub
        time.sleep(0.2)

        last_exc: Exception | None = None
        data: dict | None = None
        resp = None
        for attempt in range(3):
            resp = None
            try:
                resp = requests.post(
                    self.endpoint,
                    params={"access_token": self.access_token},
                    json=payload,
                    timeout=60,
                )
                if 500 <= resp.status_code < 600:
                    # Hub or cloud relay returned a transient error. Heavy
                    # queries (e.g. hub_get_performance_stats) sometimes 504.
                    last_exc = requests.HTTPError(f"{resp.status_code} {resp.reason} on {method}")
                    self._log(f"<< HTTP {resp.status_code} (attempt {attempt + 1}/3) — retrying")
                    # Exponential backoff with jitter to avoid thundering-herd if
                    # multiple consumers ever retry simultaneously.
                    time.sleep((2 ** attempt) + random.uniform(0, 1))  # ~1-2s, ~2-3s, ~4-5s
                    continue
                resp.raise_for_status()
                data = resp.json()
                break
            except (requests.ConnectionError, requests.Timeout,
                    requests.exceptions.ChunkedEncodingError,
                    json.JSONDecodeError) as exc:
                last_exc = exc
                snippet = ""
                if isinstance(exc, json.JSONDecodeError) and resp is not None:
                    try:
                        snippet = f" body[:200]={resp.text[:200]!r}"
                    except Exception:
                        pass
                self._log(f"<< network/decode error (attempt {attempt + 1}/3): {exc}{snippet} -- retrying")
                time.sleep((2 ** attempt) + random.uniform(0, 1))
        else:
            # Exhausted retries — surface the last transient failure with method context.
            if isinstance(last_exc, json.JSONDecodeError):
                snippet = ""
                try:
                    if resp is not None:
                        snippet = f" body[:200]={resp.text[:200]!r}"
                except Exception:
                    pass
                raise McpError(f"JSON decode failed on {method}{snippet}") from last_exc
            raise last_exc if last_exc else McpError(f"transport failure on {method}")

        # Reaching here means the loop broke on a successful decode (the for-else
        # above always raises on exhaustion), so data is a dict.
        assert data is not None
        self._log(f"<< {json.dumps(data)[:500]}")

        if "error" in data:
            raise McpError(f"JSON-RPC error: {data['error']}")

        return data.get("result", {})

    # -- MCP protocol methods ------------------------------------------------

    def initialize(self, protocol_version: str = "2024-11-05") -> dict:
        return self._send("initialize", {
            "protocolVersion": protocol_version,
            "capabilities": {},
            "clientInfo": {"name": "e2e-test", "version": "1.0.0"},
        })

    def raw_request(self, payload: Any) -> requests.Response:
        """POST a raw JSON-RPC body (single object, batch array, or notification)
        and return the raw requests.Response — no result-unwrapping, no
        error-raising. Retries transient 5xx/network flake like _send. Used by
        transport/protocol tests that must inspect the raw HTTP status and
        envelope (batch caps, 202-for-notifications, JSON-RPC framing) — paths
        the result-unwrapping call_tool/_send helpers deliberately hide.
        """
        time.sleep(0.2)
        last_exc: Exception | None = None
        for attempt in range(3):
            try:
                resp = requests.post(
                    self.endpoint,
                    params={"access_token": self.access_token},
                    json=payload,
                    timeout=60,
                )
                if 500 <= resp.status_code < 600:
                    last_exc = requests.HTTPError(f"{resp.status_code} {resp.reason} on raw_request")
                    time.sleep((2 ** attempt) + random.uniform(0, 1))
                    continue
                return resp
            except (requests.ConnectionError, requests.Timeout,
                    requests.exceptions.ChunkedEncodingError) as exc:
                last_exc = exc
                time.sleep((2 ** attempt) + random.uniform(0, 1))
        raise last_exc if last_exc else McpError("transport failure on raw_request")

    def list_tools(self) -> dict:
        """Fetch the full tool catalog, iterating cursor-based pagination per MCP 2024-11-05.

        Returns a single combined response dict {"tools": [...]} so callers don't need to know
        about pagination. Caps at 20 pages defensively to avoid runaway on a buggy server.
        """
        combined: list = []
        params: dict | None = None
        for _ in range(20):
            page_result = self._send("tools/list", params)
            combined.extend(page_result.get("tools", []))
            next_cursor = page_result.get("nextCursor")
            if not next_cursor:
                return {"tools": combined}
            params = {"cursor": next_cursor}
        raise McpError("tools/list pagination did not terminate within 20 pages")

    def call_tool(self, name: str, arguments: dict | None = None) -> Any:
        """Call an MCP tool. Returns parsed content text (dict/list/str)."""
        args = arguments or {}
        op_key = _op_key(name, args)   # gateway sub-tool / flat name; hub_set_rule split create-vs-edit
        _t0 = time.monotonic()
        result = self._send("tools/call", {"name": name, "arguments": args})
        self.op_timings.append((op_key, time.monotonic() - _t0))

        # Check for tool-level error
        if result.get("isError"):
            content_text = ""
            for c in result.get("content", []):
                if c.get("type") == "text":
                    content_text = c["text"]
            raise McpToolError(name, content_text)

        # Parse the text content
        for c in result.get("content", []):
            if c.get("type") == "text":
                try:
                    return json.loads(c["text"])
                except (json.JSONDecodeError, TypeError):
                    return c["text"]

        return result

    # -- Convenience: REST health endpoint -----------------------------------

    def get_health(self) -> dict:
        """GET the /health REST endpoint (not JSON-RPC)."""
        url = f"{self._app_path_prefix}/health"
        resp = requests.get(url, params={"access_token": self.access_token}, timeout=15)
        resp.raise_for_status()
        return resp.json()


# ---------------------------------------------------------------------------
# Test registry
# ---------------------------------------------------------------------------

TEST_REGISTRY: list[tuple[str, str, str]] = []  # (group, display_name, method_name)


def test(group: str):
    """Decorator that registers a TestRunner method in a named group."""
    def decorator(func):
        TEST_REGISTRY.append((group, func.__name__, func.__name__))
        return func
    return decorator


# ---------------------------------------------------------------------------
# Test runner
# ---------------------------------------------------------------------------


class TestRunner:
    def __init__(self, client: HubitatMcpClient, verbose: bool = False):
        self.client = client
        self.verbose = verbose
        self.results: list[dict] = []  # {name, group, status, message, duration}

        # Cleanup tracking
        self.created_device_dnis: list[str] = []
        self.created_rule_ids: list[str] = []
        self.created_native_app_ids: list[str] = []
        # When set (the CI 'Run E2E tests' step only), per-test native-rule fixture deletes are SKIPPED
        # (see _delete_native + cleanup Layer 4) and the rules are reaped by the disarm step's force
        # sweep over WATCHDOG_URL, overlapping the restore-poll wait instead of adding to the test
        # critical path. Defaults OFF, so local runs + the post-restore --cleanup-only backstop are
        # unchanged. The lifecycle/delete-assertion tests delete inline (not via _delete_native), so
        # they are unaffected.
        self.defer_native_deletes = os.environ.get("E2E_DEFER_NATIVE_DELETES") == "1"
        self.created_variable_names: list[str] = []

        # Cached helpers
        self._first_device_id: str | None = None
        self._test_start_time: str | None = None  # ISO for log check

    # -- Helpers -------------------------------------------------------------

    def get_first_device_id(self) -> str:
        """Get and cache the first device ID from hub_list_devices."""
        if self._first_device_id is None:
            result = self.client.call_tool("hub_list_devices")
            devices = result if isinstance(result, list) else result.get("devices", [])
            if not devices:
                raise RuntimeError("No devices available on hub -- cannot run tests")
            self._first_device_id = str(devices[0]["id"])
        return self._first_device_id

    def get_test_switch_id(self) -> str:
        """Get or create a BAT_E2E_ virtual switch for tests needing a commandable device.

        Tests NEVER send commands to real devices — only to virtual devices we create.
        """
        if hasattr(self, "_test_switch_id") and self._test_switch_id:
            return self._test_switch_id

        # Check if one already exists from a previous test group. A REUSED switch must
        # prove it still processes events before any test depends on it: every run's
        # rule fixtures subscribe to this device and are then FORCE-deleted (which
        # bypasses subscription teardown), and the device has been seen wedged across
        # runs -- commands accepted, no event, no state change -- until a reboot
        # rebuilt the subscription table. A wedged scaffolding switch is useless AND
        # poisons every device-command assertion, so recreate it instead of reusing.
        try:
            vdevs = self.client.call_tool("hub_list_devices", {"labelFilter": PREFIX})
            dev_list = vdevs if isinstance(vdevs, list) else vdevs.get("devices", [])
            for d in dev_list:
                lbl = d.get("label") or d.get("name") or ""
                if f"{PREFIX}Action_Switch" in lbl:
                    found_id = str(d["id"])
                    if self._probe_switch_responsive(found_id):
                        self._test_switch_id = found_id
                        return self._test_switch_id
                    print(f"  [WARN] scaffolding switch {found_id} is unresponsive "
                          f"(command accepted, state never changed) -- deleting and recreating it")
                    dni = str(d.get("deviceNetworkId", d.get("dni", "")))
                    if dni:
                        self.client.call_tool("hub_manage_virtual_device", {
                            "action": "delete", "deviceNetworkId": dni, "confirm": True,
                        })
                    break
        except Exception:
            pass

        # Create one
        result = self.client.call_tool("hub_manage_virtual_device", {
            "action": "create",
            "deviceType": "Virtual Switch",
            "deviceLabel": f"{PREFIX}Action_Switch",
            "confirm": True,
        })
        # The test switch is PERSISTENT scaffolding, not a fixture-under-test: rule/trigger tests merely
        # reference it. Deliberately NOT tracked in created_device_dnis, so teardown leaves it on the hub
        # for the next run to find-and-reuse (the existing-device lookup at the top of this method) --
        # skipping a create+delete of the switch every run. Devices that ARE under test (test_create_*)
        # still track + delete themselves. One inert virtual switch persists on the test hub; harmless.
        dev_id = result.get("id", result.get("deviceId", ""))

        # Response may not include ID directly — look it up
        if not dev_id:
            time.sleep(0.3)
            vdevs = self.client.call_tool("hub_list_devices", {"labelFilter": PREFIX})
            dev_list = vdevs if isinstance(vdevs, list) else vdevs.get("devices", [])
            for d in dev_list:
                lbl = d.get("label") or d.get("name") or ""
                if f"{PREFIX}Action_Switch" in lbl:
                    dev_id = str(d["id"])
                    break

        self._test_switch_id = str(dev_id) if dev_id else ""
        assert self._test_switch_id, "Failed to create test switch"
        return self._test_switch_id

    def _probe_switch_responsive(self, dev_id: str) -> bool:
        """One command round-trip proving the device still PROCESSES events. The
        block-poll matches on currentValue, so the probe must command the OPPOSITE
        of the current state -- polling for the state the device is already in
        would pass without any event processing at all. False on any error: an
        unprobeable device is as useless to the tests as a wedged one."""
        try:
            cur = self.client.call_tool("hub_get_device_attribute", {
                "deviceId": dev_id, "attribute": "switch",
            })
            target = "off" if (cur.get("value") if isinstance(cur, dict) else None) == "on" else "on"
            self.client.call_tool("hub_call_device_command", {
                "deviceId": dev_id, "command": target,
            })
            res = self.client.call_tool("hub_get_device_attribute", {
                "deviceId": dev_id, "attribute": "switch",
                "expectedValue": target, "timeoutMs": 4000,
            })
            return isinstance(res, dict) and res.get("timedOut") is False
        except Exception:
            return False

    def get_test_temperature_ids(self) -> tuple[str, str]:
        """Get or create two BAT_E2E_ virtual temperature sensors for device-relative tests.

        compareToDevice compares one device's reading to another's on the SAME capability,
        so the walker needs two Temperature-capable devices. Like the test switch, these are
        persistent scaffolding (NOT tracked in created_device_dnis) so teardown leaves them
        for the next run to find-and-reuse.
        """
        if getattr(self, "_test_temp_ids", None):
            return self._test_temp_ids

        labels = [f"{PREFIX}Temp_A", f"{PREFIX}Temp_B"]
        found: dict[str, str] = {}
        try:
            vdevs = self.client.call_tool("hub_list_devices", {"labelFilter": PREFIX})
            dev_list = vdevs if isinstance(vdevs, list) else vdevs.get("devices", [])
            for d in dev_list:
                lbl = d.get("label") or d.get("name") or ""
                for want in labels:
                    if want in lbl:
                        found[want] = str(d["id"])
        except Exception:
            pass

        for want in labels:
            if want in found:
                continue
            result = self.client.call_tool("hub_manage_virtual_device", {
                "action": "create",
                "deviceType": "Virtual Temperature Sensor",
                "deviceLabel": want,
                "confirm": True,
            })
            dev_id = result.get("id", result.get("deviceId", ""))
            if not dev_id:
                time.sleep(0.3)
                vdevs = self.client.call_tool("hub_list_devices", {"labelFilter": PREFIX})
                dev_list = vdevs if isinstance(vdevs, list) else vdevs.get("devices", [])
                for d in dev_list:
                    lbl = d.get("label") or d.get("name") or ""
                    if want in lbl:
                        dev_id = str(d["id"])
                        break
            assert dev_id, f"Failed to create test temperature sensor {want}"
            found[want] = str(dev_id)

        self._test_temp_ids = (found[labels[0]], found[labels[1]])
        return self._test_temp_ids

    def _record(self, name: str, group: str, status: str,
                message: str = "", duration: float = 0.0) -> None:
        tag = {"pass": "[PASS]", "fail": "[FAIL]", "skip": "[SKIP]"}[status]
        suffix = f": {message}" if message else ""
        print(f"  {tag} {name}{suffix}")
        self.results.append({
            "name": name,
            "group": group,
            "status": status,
            "message": message,
            "duration": duration,
        })

    def _run_one(self, group: str, name: str, method_name: str) -> None:
        method = getattr(self, method_name)
        t0 = time.monotonic()
        try:
            method()
            elapsed = time.monotonic() - t0
            self._record(name, group, "pass", duration=elapsed)
        except SkipTest as exc:
            elapsed = time.monotonic() - t0
            self._record(name, group, "skip", message=str(exc), duration=elapsed)
        except Exception as exc:
            elapsed = time.monotonic() - t0
            self._record(name, group, "fail", message=str(exc)[:200], duration=elapsed)

    # -- Rule helper: create, verify, delete ---------------------------------

    def _create_rule_and_verify(self, name: str, rule_def: dict) -> str:
        """Create a rule, verify it was created, return ruleId."""
        rule_def.setdefault("name", name)
        rule_def.setdefault("testRule", True)
        result = self.client.call_tool("hub_create_custom_rule", rule_def)
        rule_id = str(result.get("ruleId", result.get("id", "")))
        assert rule_id, f"hub_create_custom_rule did not return a ruleId: {result}"
        self.created_rule_ids.append(rule_id)

        # Verify creation
        fetched = self.client.call_tool("hub_get_custom_rule", {"ruleId": rule_id})
        assert fetched.get("name") == name or fetched.get("name", "").startswith(PREFIX), \
            f"Rule name mismatch: expected '{name}', got '{fetched.get('name')}'"
        return rule_id

    def _assert_rule_types(self, rule_id: str, key: str, expected_types: list[str],
                           normalize_away: tuple[str, ...] = ()) -> None:
        """Fetch a custom rule and assert its triggers/conditions/actions array carries the expected COUNT
        AND each expected TYPE -- catches the legacy engine silently dropping a type OR drop-and-duplicating
        one (which a length-only check would miss). `normalize_away` lists input types the engine rewrites
        server-side (triggers: 'sunrise'/'sunset' -> 'time'), so they aren't required to appear under their
        original name; the count still must match."""
        fetched = self.client.call_tool("hub_get_custom_rule", {"ruleId": rule_id})
        arr = fetched.get(key)
        assert isinstance(arr, list), f"custom rule '{key}' is not a list: {fetched.get(key)!r}"
        got = [e.get("type") for e in arr if isinstance(e, dict)]
        assert len(arr) == len(expected_types), \
            f"custom rule '{key}': expected {len(expected_types)} entries, got {len(arr)} -- a type was rejected/dropped: {got!r}"
        missing = [t for t in expected_types if t not in normalize_away and t not in got]
        assert not missing, \
            f"custom rule '{key}': types missing after round-trip: {missing} (got {got!r}) -- a type was dropped or replaced by a duplicate"

    def _delete_rule_safe(self, rule_id: str) -> None:
        """Delete a rule, swallowing errors."""
        try:
            self.client.call_tool("hub_delete_custom_rule", {"ruleId": rule_id, "confirm": True})
        except Exception as exc:
            print(f"[WARN] _delete_rule_safe({rule_id}) failed: {exc}")
        if rule_id in self.created_rule_ids:
            self.created_rule_ids.remove(rule_id)

    def _create_variable(self, name: str, var_type: str = "String",
                         value: str = "test") -> None:
        """Create a hub variable via the gateway."""
        self.client.call_tool("hub_manage_variables", {
            "tool": "hub_set_variable", "args": {"name": name, "type": var_type, "value": value},
        })
        self.created_variable_names.append(name)

    def _delete_variable_safe(self, name: str) -> None:
        try:
            # confirm=true is required by Hub Admin Write gate; without it the
            # delete is silently skipped (try/except swallows the refusal),
            # leaving the variable stranded.
            self.client.call_tool("hub_manage_variables", {
                "tool": "hub_delete_variable", "args": {"name": name, "confirm": True},
            })
        except Exception as exc:
            print(f"[WARN] _delete_variable_safe({name}) failed: {exc}")
        if name in self.created_variable_names:
            self.created_variable_names.remove(name)

    # -----------------------------------------------------------------------
    # GROUP 1: infrastructure (7 tests)
    # -----------------------------------------------------------------------

    @test("infrastructure")
    def test_initialize(self) -> None:
        result = self.client.initialize()
        assert "serverInfo" in result, f"Missing serverInfo in initialize response: {list(result.keys())}"
        assert "capabilities" in result, "Missing capabilities in initialize response"

    @test("infrastructure")
    def test_tools_list(self) -> None:
        result = self.client.list_tools()
        tools = result.get("tools", [])
        names = {t.get("name") for t in tools}
        # hub_update_package is a Developer-Mode-only TOP-LEVEL tool (issue #250): it shows on
        # tools/list ONLY with Developer Mode on (this e2e hub has it on -- a documented precondition).
        # The documented DEFAULT catalog is 30 (11 core + 19 gateways); exclude the dev-mode tool so
        # the count matches the default regardless of the toggle, then assert the dev-mode tool is
        # present on this dev-on hub.
        default_tools = [t for t in tools if t.get("name") != "hub_update_package"]
        assert len(default_tools) == 30, \
            f"Expected 30 default tools (11 core + 19 gateways), got {len(default_tools)}: {sorted(names)}"
        assert "hub_update_package" in names, \
            "hub_update_package must be a top-level tool when Developer Mode is on (issue #250)"

    @test("infrastructure")
    def test_tools_list_titles(self) -> None:
        # Issue #245: every tools/list entry (core tools, gateways, dev-mode tools)
        # carries a human-readable friendly name in annotations.title -- the field
        # claude.ai renders in place of the bare tool name.
        result = self.client.list_tools()
        tools = result.get("tools", [])
        assert tools, "tools/list returned no tools"
        missing = [
            t.get("name") for t in tools
            if not isinstance((t.get("annotations") or {}).get("title"), str)
            or not (t.get("annotations") or {}).get("title", "").strip()
        ]
        assert not missing, f"tools/list entries missing annotations.title: {missing}"
        by_name = {t["name"]: t for t in tools}
        info_title = by_name["hub_get_info"]["annotations"]["title"]
        assert info_title == "Get Hub Info", f"hub_get_info title unexpected: {info_title!r}"
        gw_title = by_name["hub_read_devices"]["annotations"]["title"]
        assert gw_title == "Read Devices", f"hub_read_devices title unexpected: {gw_title!r}"

    @test("infrastructure")
    def test_tools_list_annotation_hints(self) -> None:
        # Issue #238: every tools/list entry ships the boolean annotation hints --
        # readOnlyHint/idempotentHint/openWorldHint always, destructiveHint on writes.
        result = self.client.list_tools()
        tools = result.get("tools", [])
        assert tools, "tools/list returned no tools"
        bad = []
        for t in tools:
            ann = t.get("annotations") or {}
            for key in ("readOnlyHint", "idempotentHint", "openWorldHint"):
                if not isinstance(ann.get(key), bool):
                    bad.append(f"{t.get('name')}.{key}")
            if ann.get("readOnlyHint") is False and ann.get("destructiveHint") is not True:
                bad.append(f"{t.get('name')}.destructiveHint")
        assert not bad, f"entries with missing or mistyped annotation hints: {bad}"
        by_name = {t["name"]: (t.get("annotations") or {}) for t in tools}
        assert by_name["hub_update_package"]["openWorldHint"] is True, \
            "hub_update_package must be open-world (GitHub fetches)"
        assert by_name["hub_read_devices"]["idempotentHint"] is True, \
            "pure-read gateway must roll up idempotent"
        assert by_name["hub_read_devices"]["openWorldHint"] is False, \
            "pure-read gateway must be closed-world"
        assert by_name["hub_read_diagnostics"]["openWorldHint"] is True, \
            "diagnostics gateway must roll up open-world (hub_get_device_health pingHosts)"
        assert by_name["hub_read_diagnostics"]["idempotentHint"] is True, \
            "pure-read diagnostics gateway must roll up idempotent"

    @test("infrastructure")
    def test_gateway_catalog_titles(self) -> None:
        # Issue #245: the gateway no-arg catalog disclosure also surfaces each
        # sub-tool's friendly title next to its bare name and schema.
        catalog = self.client.call_tool("hub_read_rooms", {})
        assert catalog.get("mode") == "catalog", f"Expected catalog mode, got: {catalog.get('mode')}"
        entries = catalog.get("tools", [])
        assert entries, "hub_read_rooms catalog returned no tools"
        missing = [e.get("name") for e in entries
                   if not isinstance(e.get("title"), str) or not e.get("title", "").strip()]
        assert not missing, f"gateway catalog entries missing title: {missing}"
        rooms = next((e for e in entries if e.get("name") == "hub_list_rooms"), None)
        assert rooms is not None, "hub_list_rooms not found in catalog"
        assert rooms["title"] == "List Rooms", f"hub_list_rooms catalog title unexpected: {rooms['title']!r}"

    @test("infrastructure")
    def test_health_endpoint(self) -> None:
        data = self.client.get_health()
        assert data.get("status") == "ok", f"Health status != ok: {data.get('status')}"
        assert "version" in data, "Health response missing version"

    @test("infrastructure")
    def test_set_rule_guide_param(self) -> None:
        # guide:true returns the hub_set_rule capability reference inline (no
        # separate hub_get_tool_guide call) and makes NO rule change -- a pure static
        # early-return alongside the discover-mode short-circuit. Pins the new param.
        result = self.client.call_tool("hub_manage_rule_machine", {
            "tool": "hub_set_rule", "args": {"guide": True},
        })
        blob = str(result)
        assert "addTrigger" in blob and "walkStep" in blob, \
            f"guide:true response missing the capability reference content: {blob[:200]}"

    # -----------------------------------------------------------------------
    # GROUP 2: devices (4 tests)
    # -----------------------------------------------------------------------

    @test("devices")
    def test_list_devices(self) -> None:
        result = self.client.call_tool("hub_list_devices")
        devices = result if isinstance(result, list) else result.get("devices", [])
        assert isinstance(devices, list), "hub_list_devices did not return a list"
        assert len(devices) > 0, "hub_list_devices returned empty list"
        first = devices[0]
        assert "id" in first, "Device missing 'id'"
        assert "label" in first or "name" in first, "Device missing label/name"

    @test("devices")
    def test_get_device(self) -> None:
        dev_id = self.get_first_device_id()
        result = self.client.call_tool("hub_get_device", {"deviceId": dev_id})
        assert "attributes" in result or "currentStates" in result, \
            "hub_get_device response missing attributes"

    @test("devices")
    def test_get_attribute(self) -> None:
        # Find a switch device
        all_devs = self.client.call_tool("hub_list_devices")
        devices = all_devs if isinstance(all_devs, list) else all_devs.get("devices", [])
        switch_dev = None
        for d in devices:
            label = (d.get("label") or d.get("name") or "").lower()
            caps = [c.lower() if isinstance(c, str) else c.get("name", "").lower()
                    for c in d.get("capabilities", [])]
            if "switch" in label or "switch" in caps:
                switch_dev = d
                break
        if switch_dev is None:
            # No real switch on the hub -- fall back to the persistent scaffolding switch
            # (get_test_switch_id find-or-reuse) instead of provisioning + deleting a throwaway probe.
            # This test ALWAYS runs (skips are failures).
            switch_id = self.get_test_switch_id()
        else:
            switch_id = str(switch_dev["id"])
        result = self.client.call_tool("hub_get_device_attribute", {
            "deviceId": switch_id,
            "attribute": "switch",
        })
        # Result should contain the value (on/off or similar)
        assert result is not None, "hub_get_device_attribute returned None"

    @test("devices")
    def test_send_command_error(self) -> None:
        try:
            self.client.call_tool("hub_call_device_command", {
                "deviceId": "99999",
                "command": "on",
            })
            raise AssertionError("hub_call_device_command with bogus device should have raised an error")
        except (McpToolError, McpError):
            pass  # expected — server may return JSON-RPC error or tool error

    # -----------------------------------------------------------------------
    # GROUP 3: virtual_device_lifecycle (4 tests)
    # -----------------------------------------------------------------------

    @test("virtual_device_lifecycle")
    def test_create_virtual_switch(self) -> None:
        result = self.client.call_tool("hub_manage_virtual_device", {
            "action": "create",
            "deviceType": "Virtual Switch",
            "deviceLabel": f"{PREFIX}Switch_Test",
            "confirm": True,
        })
        # Response may be {success: true, message: "..."} without device IDs at top level
        # Track DNI if available, otherwise look it up via hub_list_devices (labelFilter)
        dni = result.get("deviceNetworkId", result.get("dni", ""))
        if dni:
            self.created_device_dnis.append(str(dni))
        elif result.get("success"):
            # Look up the created device to get its DNI for cleanup
            vdevs = self.client.call_tool("hub_list_devices", {"labelFilter": PREFIX})
            dev_list = vdevs if isinstance(vdevs, list) else vdevs.get("devices", [])
            for d in dev_list:
                lbl = d.get("label") or d.get("name") or ""
                if f"{PREFIX}Switch_Test" in lbl:
                    found_dni = str(d.get("deviceNetworkId", d.get("dni", "")))
                    if found_dni:
                        self.created_device_dnis.append(found_dni)
                    break
        assert result.get("success") or result.get("id") or result.get("deviceId") or dni, \
            f"create virtual device failed: {result}"

    @test("virtual_device_lifecycle")
    def test_command_virtual_switch(self) -> None:
        # Command round-trips don't need the just-created device (creation is covered by
        # test_create_virtual_switch and its state by test_list/delete): use the persistent
        # scaffolding switch, which has a real prior state -- a FRESH Virtual Switch is born
        # with switch=null, which is exactly the edge that made this test flake.
        dev_id = self.get_test_switch_id()

        # Event processing can lag on a busy hub, so block-poll the attribute instead
        # of the old fixed sleep + single read (which flaked as "Expected switch=on,
        # got None"). Each poll stays WELL under the ~10s cloud-relay budget (a single
        # 10s block-poll holds the request past the relay timeout and 504s); patience
        # comes from retrying the short polls.
        def _poll_switch(expected: str) -> Any:
            result: Any = {}
            for _ in range(3):
                result = self.client.call_tool("hub_get_device_attribute", {
                    "deviceId": dev_id,
                    "attribute": "switch",
                    "expectedValue": expected,
                    "timeoutMs": 4000,
                })
                if isinstance(result, dict) and result.get("timedOut") is False:
                    return result
            return result

        def _switch_diagnostics() -> str:
            # On a poll timeout the bare result can't distinguish "the command never
            # processed" (a wedged hub) from "something instantly reverted it" (a rule
            # stranded by a prior run, still subscribed to this persistent device).
            # The event history shows which one happened -- a revert leaves an on->off
            # pair with the producing app in the description -- and the dependents
            # list names any app still subscribed. Best-effort: diagnostics must
            # never mask the original failure.
            parts = []
            try:
                ev = self.client.call_tool("hub_list_device_events", {
                    "deviceId": dev_id, "limit": 12,
                })
                rows = ev.get("events", []) if isinstance(ev, dict) else []
                parts.append("recent events: " + json.dumps([
                    {k: r.get(k) for k in ("name", "value", "description", "date")}
                    for r in rows if isinstance(r, dict)]))
            except Exception as exc:
                parts.append(f"event-history fetch failed: {exc}")
            try:
                deps = self.client.call_tool("hub_read_apps_code", {
                    "tool": "hub_list_device_dependents", "args": {"deviceId": dev_id},
                })
                parts.append(f"apps using this device: {json.dumps(deps)[:800]}")
            except Exception as exc:
                parts.append(f"dependents fetch failed: {exc}")
            # _run_one truncates failure messages to 200 chars for the summary table,
            # which clipped this diagnosis mid-first-event when it mattered. Print the
            # full blob to the run log here; the assert carries only a pointer.
            print(f"    DIAG[{dev_id}] " + " | ".join(parts))
            return "full diagnostics printed above (DIAG line in the test output)"

        cmd = self.client.call_tool("hub_call_device_command", {"deviceId": dev_id, "command": "on"})
        assert not (isinstance(cmd, dict) and cmd.get("success") is False), \
            f"'on' command reported failure: {cmd}"
        result = _poll_switch("on")
        assert result.get("timedOut") is False and result.get("finalValue") == "on", \
            f"Expected switch=on within the poll budget, got: {result}\n    DIAG {_switch_diagnostics()}"

        # Turn off
        cmd = self.client.call_tool("hub_call_device_command", {"deviceId": dev_id, "command": "off"})
        assert not (isinstance(cmd, dict) and cmd.get("success") is False), \
            f"'off' command reported failure: {cmd}"
        result = _poll_switch("off")
        assert result.get("timedOut") is False and result.get("finalValue") == "off", \
            f"Expected switch=off within the poll budget, got: {result}\n    DIAG {_switch_diagnostics()}"

    @test("virtual_device_lifecycle")
    def test_list_virtual_devices(self) -> None:
        result = self.client.call_tool("hub_list_devices", {"labelFilter": PREFIX})
        dev_list = result if isinstance(result, list) else result.get("devices", [])
        found = any(
            f"{PREFIX}Switch_Test" in (d.get("label") or d.get("name") or "")
            for d in dev_list
        )
        assert found, f"{PREFIX}Switch_Test not found in virtual device list"

    @test("virtual_device_lifecycle")
    def test_delete_virtual_switch(self) -> None:
        # Find DNI of our test device
        vdevs = self.client.call_tool("hub_list_devices", {"labelFilter": PREFIX})
        dev_list = vdevs if isinstance(vdevs, list) else vdevs.get("devices", [])
        target_dni = None
        for d in dev_list:
            lbl = d.get("label") or d.get("name") or ""
            if f"{PREFIX}Switch_Test" in lbl:
                target_dni = str(d.get("deviceNetworkId", d.get("dni", "")))
                break
        if not target_dni:
            raise AssertionError(f"{PREFIX}Switch_Test not found for deletion -- the upstream create test must have failed")

        self.client.call_tool("hub_manage_virtual_device", {
            "action": "delete",
            "deviceNetworkId": target_dni,
            "confirm": True,
        })
        if target_dni in self.created_device_dnis:
            self.created_device_dnis.remove(target_dni)

        # Verify it is gone
        vdevs2 = self.client.call_tool("hub_list_devices", {"labelFilter": PREFIX})
        dev_list2 = vdevs2 if isinstance(vdevs2, list) else vdevs2.get("devices", [])
        still_there = any(
            f"{PREFIX}Switch_Test" in (d.get("label") or d.get("name") or "")
            for d in dev_list2
        )
        assert not still_there, f"{PREFIX}Switch_Test still present after deletion"

    # -----------------------------------------------------------------------
    # GROUP 4: rule_crud (4 tests)
    # -----------------------------------------------------------------------

    @test("rule_crud")
    def test_create_rule(self) -> None:
        dev_id = self.get_first_device_id()
        rule_id = self._create_rule_and_verify(f"{PREFIX}Rule_CRUD", {
            "triggers": [{"type": "device_event", "deviceId": dev_id, "attribute": "switch"}],
            "actions": [{"type": "log", "message": "E2E test rule fired"}],
        })
        assert rule_id

    @test("rule_crud")
    def test_get_rule(self) -> None:
        # Use the rule created in test_create_rule (last tracked rule)
        rule_id = self._last_rule_id()
        if not rule_id:
            raise AssertionError("No rule created to get -- the upstream create-rule test must have failed")
        result = self.client.call_tool("hub_get_custom_rule", {"ruleId": rule_id})
        assert result.get("name", "").startswith(PREFIX), \
            f"Rule name mismatch: {result.get('name')}"
        assert "triggers" in result or "trigger" in result, "Missing triggers in hub_get_custom_rule"
        assert "actions" in result, "Missing actions in hub_get_custom_rule"

    @test("rule_crud")
    def test_update_rule(self) -> None:
        rule_id = self._last_rule_id()
        if not rule_id:
            raise AssertionError("No rule created to update -- the upstream create-rule test must have failed")
        self.client.call_tool("hub_update_custom_rule", {
            "ruleId": rule_id,
            "name": f"{PREFIX}Rule_CRUD_Updated",
        })
        fetched = self.client.call_tool("hub_get_custom_rule", {"ruleId": rule_id})
        assert "Updated" in fetched.get("name", ""), \
            f"Rule name not updated: {fetched.get('name')}"

    @test("rule_crud")
    def test_delete_rule(self) -> None:
        rule_id = self._last_rule_id()
        if not rule_id:
            raise AssertionError("No rule created to delete -- the upstream create-rule test must have failed")
        self.client.call_tool("hub_delete_custom_rule", {"ruleId": rule_id, "confirm": True})
        if rule_id in self.created_rule_ids:
            self.created_rule_ids.remove(rule_id)
        # Verify it's gone
        try:
            self.client.call_tool("hub_get_custom_rule", {"ruleId": rule_id})
            raise AssertionError("hub_get_custom_rule should fail after deletion")
        except (McpToolError, McpError):
            pass

    def _last_rule_id(self) -> str | None:
        return self.created_rule_ids[-1] if self.created_rule_ids else None

    # -----------------------------------------------------------------------
    # GROUP 4b: native_apps -- the hub_set_rule / hub_set_native_app split
    # plus basic_rule appType, button-rule create via buttonRule, and walkStep
    # on the generic tool, end-to-end against the live hub.
    # These are distinct from rule_crud above, which drives the LEGACY custom
    # rule engine (custom_* tools); these drive the NATIVE Rule Machine + classic
    # SmartApp surface that appears in Hubitat's own UI.
    # -----------------------------------------------------------------------

    def _untrack_native_app(self, app_id) -> None:
        if str(app_id) in self.created_native_app_ids:
            self.created_native_app_ids.remove(str(app_id))

    @test("native_apps")
    def test_set_rule_native_lifecycle(self) -> None:
        # CREATE a native RM rule in ONE call: hub_set_rule with no appId, bundling
        # a (device-free) Time trigger + a log action -- the headline new capability.
        created = self.client.call_tool("hub_manage_rule_machine", {
            "tool": "hub_set_rule",
            "args": {
                "name": f"{PREFIX}NativeRule",
                "addTrigger": {
                    "capability": "Certain Time (and optional date)",
                    "time": "A specific time", "atTime": "17:00",
                },
                "addActions": [{"capability": "log", "message": "E2E native rule fired"}],
                "confirm": True,
            },
        })
        app_id = created.get("appId")
        assert app_id, f"hub_set_rule create did not return an appId: {created}"
        self.created_native_app_ids.append(str(app_id))

        # VERIFY: the new rule shows up in the NATIVE RM rule list (RMUtils).
        rules = self.client.call_tool("hub_manage_rule_machine", {"tool": "hub_list_rules", "args": {}})
        rule_list = rules if isinstance(rules, list) else rules.get("rules", [])
        found = any(
            str(r.get("id")) == str(app_id) or f"{PREFIX}NativeRule" in (r.get("name") or r.get("label") or "")
            for r in rule_list
        )
        assert found, f"created native rule {app_id} not found in hub_list_rules"

        # EDIT: hub_set_rule WITH appId routes to the edit engine -- add a second action.
        edited = self.client.call_tool("hub_manage_rule_machine", {
            "tool": "hub_set_rule",
            "args": {"appId": app_id, "addAction": {"capability": "log", "message": "second action"}, "confirm": True},
        })
        assert edited.get("success") is not False, f"hub_set_rule edit reported failure: {edited}"

        # DELETE via the cross-listed hub_delete_native_app.
        self.client.call_tool("hub_manage_rule_machine", {
            "tool": "hub_delete_native_app", "args": {"appId": app_id, "confirm": True},
        })
        self._untrack_native_app(app_id)

    @test("native_apps")
    def test_set_native_app_lifecycle(self) -> None:
        # hub_set_native_app: the GENERIC create-or-edit upsert. Create via the
        # registry-driven create path, rename via a raw settings write, then delete.
        created = self.client.call_tool("hub_manage_native_rules_and_apps", {
            "tool": "hub_set_native_app",
            "args": {"appType": "rule_machine", "name": f"{PREFIX}NativeApp", "confirm": True},
        })
        app_id = created.get("appId")
        assert app_id, f"hub_set_native_app create did not return an appId: {created}"
        self.created_native_app_ids.append(str(app_id))

        # EDIT: generic settings write (rename via origLabel) -- the lean edit path.
        edited = self.client.call_tool("hub_manage_native_rules_and_apps", {
            "tool": "hub_set_native_app",
            "args": {"appId": app_id, "settings": {"origLabel": f"{PREFIX}NativeApp_Renamed"}, "confirm": True},
        })
        assert edited.get("success") is not False, f"hub_set_native_app edit reported failure: {edited}"

        # VERIFY the RENAME actually applied via the read-only hub_get_app_config.
        # Identity (label/name) is nested under the `app` object (toolGetAppConfig
        # shape). Asserting the post-rename token (not just PREFIX, which the create
        # label already carries) proves the settings edit landed, not just succeeded.
        cfg = self.client.call_tool("hub_read_apps_code", {"tool": "hub_get_app_config", "args": {"appId": app_id}})
        app_obj = cfg.get("app") or {}
        label = str(app_obj.get("label") or app_obj.get("name") or "")
        assert "_Renamed" in label, f"hub_set_native_app rename did not land; label={label!r} (cfg keys: {list(cfg.keys())})"

        # DELETE.
        self.client.call_tool("hub_manage_native_rules_and_apps", {
            "tool": "hub_delete_native_app", "args": {"appId": app_id, "confirm": True},
        })
        self._untrack_native_app(app_id)

    @test("native_apps")
    def test_set_native_app_allows_walkstep(self) -> None:
        # The rmOnly reject on walkStep was removed: it's a generic classic-
        # dynamicPage walker that routes to the shared edit engine and works on
        # any classic app. The introspect op itself doesn't modify the app
        # (a pre-walkStep backup snapshot is still written).
        app_id = self._create_native_rule("WalkStep")
        try:
            result = self.client.call_tool("hub_manage_native_rules_and_apps", {
                "tool": "hub_set_native_app",
                "args": {"appId": app_id, "walkStep": {"page": "mainPage", "operation": "introspect"}, "confirm": True},
            })
            assert result.get("page") == "mainPage", f"walkStep should route through the native-app tool, got: {result}"
        finally:
            self._delete_native(app_id)

    @test("native_apps")
    def test_set_native_app_basic_rule_lifecycle(self) -> None:
        # basic_rule is a registered appType (a classic dynamicPage app, not a
        # Vue SPA). Create via generic createchild, edit a setting -- which must
        # NOT poison the page with the "For input string: updateRule" error
        # (the commitButton=null fix) -- then delete.
        created = self.client.call_tool("hub_manage_native_rules_and_apps", {
            "tool": "hub_set_native_app",
            "args": {"appType": "basic_rule", "name": f"{PREFIX}BasicRule", "confirm": True},
        })
        app_id = created.get("appId")
        assert app_id, f"basic_rule create did not return an appId: {created}"
        self.created_native_app_ids.append(str(app_id))

        try:
            # The created Basic Rule renders a real classic configPage (proves it's not
            # a Vue-SPA redirect that would silently swallow writes).
            cfg = self.client.call_tool("hub_read_apps_code", {"tool": "hub_get_app_config", "args": {"appId": app_id}})
            assert (cfg.get("app") or {}).get("name") == "Basic Rule-1.0", f"unexpected Basic Rule config: {cfg}"

            # EDIT: write the Notes field. NO updateRule click fires (Basic Rule
            # is submitOnChange), so the render stays clean.
            edited = self.client.call_tool("hub_manage_native_rules_and_apps", {
                "tool": "hub_set_native_app",
                "args": {"appId": app_id, "settings": {"comments": f"{PREFIX}note"}, "confirm": True},
            })
            assert "updateRule" not in str(edited.get("configPageError") or ""), \
                f"Basic Rule edit poisoned the render with the updateRule error: {edited}"
            assert edited.get("success") is not False, f"Basic Rule edit reported failure: {edited}"
        finally:
            # DELETE inline (not just via the global-cleanup backstop) so an
            # assertion failure above doesn't strand the fixture mid-run.
            self.client.call_tool("hub_manage_native_rules_and_apps", {
                "tool": "hub_delete_native_app", "args": {"appId": app_id, "confirm": True},
            })
            self._untrack_native_app(app_id)

    @test("native_apps")
    def test_button_rule_create_via_controller(self) -> None:
        # A Button Rule is a grandchild of a Button Controller and
        # only renders when created through the controller's add-button flow. Create
        # a controller + a virtual button device, then create a button rule via the
        # buttonRule param, author an action via hub_set_rule, and clean up.
        # The "Button Controllers" built-in parent app is auto-installed by
        # _discoverParentAppId (via the Add Built-In App / sysApp endpoint) when absent,
        # so this runs on a clean hub (e.g. the CI test hub) that doesn't have it yet.
        controller_id = None
        button_dni = None
        try:
            # Virtual button device for the controller to bind to.
            dev = self.client.call_tool("hub_manage_virtual_device", {
                "action": "create", "deviceType": "Virtual Button",
                "deviceLabel": f"{PREFIX}BtnDev", "confirm": True,
            })
            button_dni = str((dev.get("device") or {}).get("deviceNetworkId") or dev.get("deviceNetworkId") or "")
            device_id = str((dev.get("device") or {}).get("id") or dev.get("deviceId") or dev.get("id") or "")
            assert device_id, f"virtual button create did not return a device id: {dev}"
            if button_dni:
                self.created_device_dnis.append(button_dni)

            # Button Controller-5.1 instance + assign its button device.
            ctrl = self.client.call_tool("hub_manage_native_rules_and_apps", {
                "tool": "hub_set_native_app",
                "args": {"appType": "button_controller", "name": f"{PREFIX}BtnCtrl", "confirm": True},
            })
            controller_id = ctrl.get("appId")
            assert controller_id, f"button controller create did not return an appId: {ctrl}"
            self.created_native_app_ids.append(str(controller_id))
            assigned = self.client.call_tool("hub_manage_native_rules_and_apps", {
                "tool": "hub_set_native_app",
                "args": {"appId": controller_id, "settings": {"buttonDev": [device_id]}, "confirm": True},
            })
            assert assigned.get("success") is not False, f"buttonDev settings write reported failure: {assigned}"
            assert "buttonDev" in (assigned.get("settingsApplied") or []), (
                f"buttonDev fell out of the page schema "
                f"(settingsSkipped={assigned.get('settingsSkipped')}): {assigned}"
            )
            # Read the assignment back BEFORE the buttonRule step. The write used
            # to report success while the trailing mainPage Done re-submitted
            # settings[buttonDev]="" and wiped it (statusJson reports value=null
            # for capability settings), so the failure surfaced one call later
            # with no diagnostics. Asserting the persisted shape here pins the
            # _rmLiveSettingsFromStatus fix and fails AT the write on regression.
            cfg = self.client.call_tool("hub_read_apps_code", {
                "tool": "hub_get_app_config",
                "args": {"appId": controller_id, "includeSettings": True},
            })
            persisted = (cfg.get("settings") or {}).get("buttonDev")
            assert isinstance(persisted, dict) and persisted, (
                f"buttonDev did not persist on controller {controller_id} "
                f"(settings.buttonDev={persisted!r})"
            )

            # Create the button rule (button 1 pushed) through the controller.
            br = self.client.call_tool("hub_manage_native_rules_and_apps", {
                "tool": "hub_set_native_app",
                "args": {"buttonRule": {"controllerId": controller_id, "buttonNumber": 1, "event": "pushed"}, "confirm": True},
            })
            rule_id = br.get("buttonRuleId")
            assert br.get("success") and rule_id, f"buttonRule create failed: {br}"

            # The rule is RM-wire-format: author an action via hub_set_rule, and the
            # health should be clean (it renders -- not the broken orphan a bare
            # createchild produces).
            assert (br.get("health") or {}).get("ok") is not False, f"new button rule is unhealthy: {br}"
            acted = self.client.call_tool("hub_manage_rule_machine", {
                "tool": "hub_set_rule",
                "args": {"appId": rule_id, "addAction": {"capability": "log", "message": "E2E button rule"}, "confirm": True},
            })
            assert acted.get("success") is not False, f"authoring the button rule's action failed: {acted}"
        finally:
            # Deleting the controller cascades to its grandchild rules. Guarded:
            # an unguarded raise here would REPLACE the real test failure, and
            # the controller stays tracked for the global-cleanup backstop.
            if controller_id:
                try:
                    self.client.call_tool("hub_manage_native_rules_and_apps", {
                        "tool": "hub_delete_native_app", "args": {"appId": controller_id, "force": True, "confirm": True},
                    })
                    self._untrack_native_app(controller_id)
                except (McpToolError, McpError) as exc:
                    print(f"  [WARN] button-rule e2e cleanup: delete controller {controller_id} failed: {exc}")
            # Delete the virtual button device now (not just via global cleanup) so the hub
            # stays clean even if a later test fails or the run is interrupted.
            if button_dni:
                try:
                    self.client.call_tool("hub_manage_virtual_device", {
                        "action": "delete", "deviceNetworkId": button_dni, "confirm": True,
                    })
                    if button_dni in self.created_device_dnis:
                        self.created_device_dnis.remove(button_dni)
                except (McpToolError, McpError) as exc:
                    print(f"  [WARN] button-rule e2e cleanup: delete device {button_dni} failed: {exc}")

    @test("native_apps")
    def test_set_rule_addaction_missing_required_field_fails_fast(self) -> None:
        # Several (capability, action) pairs were verified on a live hub to
        # register the action row but NEVER bake when their key field is omitted
        # (mainPage keeps the 'Define Actions' placeholder) -- a latent silent
        # failure. The build now throws up front; the single-addAction edit path
        # surfaces it as success:false + a field-naming error instead of a
        # confusing partial. Spot-check colorTemp.setColorTemp (needs 'kelvin').
        # No device is needed -- the field throw fires before any device write.
        app_id = self._create_native_rule("CondReq")
        try:
            result = self.client.call_tool("hub_manage_rule_machine", {
                "tool": "hub_set_rule",
                "args": {"appId": app_id, "addAction": {"capability": "colorTemp", "action": "setColorTemp"}, "confirm": True},
            })
            assert result.get("success") is False, f"omitting kelvin should fail fast (not partial): {result}"
            assert "kelvin" in str(result.get("error", "")), f"error should name 'kelvin': {result}"
        except (McpToolError, McpError) as exc:
            assert "kelvin" in str(exc), f"fail-fast error should name 'kelvin': {exc}"
        finally:
            self._delete_native(app_id)

    # ---- shared helpers for the native-authoring coverage below ----

    def _create_native_rule(self, suffix: str) -> Any:
        """Create an empty native RM rule via hub_set_rule (no appId), track it."""
        created = self.client.call_tool("hub_manage_rule_machine", {
            "tool": "hub_set_rule", "args": {"name": f"{PREFIX}{suffix}", "confirm": True},
        })
        app_id = created.get("appId")
        assert app_id, f"hub_set_rule create did not return appId: {created}"
        self.created_native_app_ids.append(str(app_id))
        return app_id

    def _set_rule(self, app_id: Any, extra: dict) -> Any:
        """hub_set_rule edit (appId present) with the given shortcut args."""
        args = {"appId": app_id, "confirm": True}
        args.update(extra)
        result = self.client.call_tool("hub_manage_rule_machine", {"tool": "hub_set_rule", "args": args})
        assert result.get("success") is not False, f"hub_set_rule({list(extra)}) reported failure: {result}"
        return result

    def _assert_rule_healthy(self, app_id: Any) -> None:
        h = self.client.call_tool("hub_manage_rule_machine", {"tool": "hub_get_rule_health", "args": {"appId": app_id}})
        assert h.get("ok") is not False, f"hub_get_rule_health reports the rule broken: {h}"

    def _delete_native(self, app_id: Any, gateway: str = "hub_manage_rule_machine") -> None:
        # Fixture-teardown delete. When deferral is on, skip it (rule stays tracked) so it's reaped by
        # the disarm sweep during the restore window, not inline on the test critical path. Tests whose
        # delete IS the assertion call hub_delete_native_app directly (not this helper), so they keep
        # deleting inline regardless.
        if self.defer_native_deletes:
            return
        self.client.call_tool(gateway, {"tool": "hub_delete_native_app", "args": {"appId": app_id, "force": True, "confirm": True}})
        self._untrack_native_app(app_id)

    # ---- one test per shortcut category that the new tools route to ----

    @test("native_apps")
    def test_set_rule_edit_trigger_and_action(self) -> None:
        # hub_set_rule edit -> the device-state addTrigger + addAction wizard paths.
        sw = int(self.get_test_switch_id())
        app_id = self._create_native_rule("Trig_Act")
        self._set_rule(app_id, {"addTrigger": {"capability": "Switch", "deviceIds": [sw], "state": "on"}})
        self._set_rule(app_id, {"addAction": {"capability": "switch", "action": "on", "deviceIds": [sw]}})
        self._assert_rule_healthy(app_id)
        self._delete_native(app_id)

    @test("native_apps")
    def test_set_rule_custom_attribute_enum_no_false_partial(self) -> None:
        # hub_set_rule edit -> addTrigger Custom Attribute on an ENUM-recognized
        # attribute. A virtual switch's 'switch' attribute is the canonical enum
        # case: picking it reveals the enum value picker (tstate<N>) and HIDES the
        # free comparator field (ReltDev<N>). The value must land in tstate<N>, and
        # the now-absent ReltDev<N> must NOT be written -- an unconditional comparator
        # write there is rejected not_in_schema and spuriously flips partial=true even
        # though the trigger built correctly. This pins the no-false-partial contract
        # the Spock regression specs guard, end-to-end against a live hub. Covers BOTH
        # the trigger row (_rmAddTrigger, ReltDev<N>) and the conditional-trigger
        # condition (_rmBuildCondition, RelrDev_<N>) -- the two share the enum bug.
        sw = int(self.get_test_switch_id())
        app_id = self._create_native_rule("CustEnum")
        try:
            # --- trigger row: tCustomAttr<N> / tstate<N> / ReltDev<N> ---
            result = self.client.call_tool("hub_manage_rule_machine", {
                "tool": "hub_set_rule",
                "args": {
                    "appId": app_id,
                    "addTrigger": {"capability": "Custom Attribute", "deviceIds": [sw],
                                   "attribute": "switch", "comparator": "=", "state": "on"},
                    "confirm": True,
                },
            })
            assert result.get("success") is not False, f"addTrigger reported failure: {result}"
            # The enum value landed in the value picker (tstate<N>) ...
            applied = result.get("settingsApplied") or []
            assert any(str(k).startswith("tstate") for k in applied), \
                f"enum value did not land in a tstate field; settingsApplied={applied}"
            # ... and the hidden comparator was NOT written, so no ReltDev not_in_schema
            # skip was produced -- the false-positive not_in_schema partial this guards.
            skipped = result.get("settingsSkipped") or []
            bad = [s for s in skipped if isinstance(s, dict)
                   and (s.get("key") or "").startswith("ReltDev")
                   and s.get("reason") == "not_in_schema"]
            assert not bad, f"unexpected ReltDev not_in_schema skip (the enum false-partial bug): {bad}"
            # The contract discriminator: partial stays falsy.
            assert not result.get("partial"), \
                f"trigger falsely flagged partial despite building correctly: {result}"

            # --- condition path: a conditional trigger whose condition is the same
            #     enum Custom Attribute (rCustomAttr_<N> / state_<N> / RelrDev_<N>) ---
            cond_result = self.client.call_tool("hub_manage_rule_machine", {
                "tool": "hub_set_rule",
                "args": {
                    "appId": app_id,
                    "addTrigger": {"capability": "Switch", "deviceIds": [sw], "state": "on",
                                   "condition": {"capability": "Custom Attribute", "deviceIds": [sw],
                                                 "attribute": "switch", "comparator": "=", "state": "on"}},
                    "confirm": True,
                },
            })
            assert cond_result.get("success") is not False, \
                f"conditional addTrigger reported failure: {cond_result}"
            cond_applied = cond_result.get("settingsApplied") or []
            assert any(str(k).startswith("state_") for k in cond_applied), \
                f"condition enum value did not land in a state_<N> field; settingsApplied={cond_applied}"
            cond_skipped = cond_result.get("settingsSkipped") or []
            cond_bad = [s for s in cond_skipped if isinstance(s, dict)
                        and (s.get("key") or "").startswith("RelrDev_")
                        and s.get("reason") == "not_in_schema"]
            assert not cond_bad, \
                f"unexpected RelrDev_<N> not_in_schema skip on the condition path: {cond_bad}"
            assert not cond_result.get("partial"), \
                f"conditional trigger falsely flagged partial: {cond_result}"

            self._assert_rule_healthy(app_id)
        finally:
            self._delete_native(app_id)

    @test("native_apps")
    def test_set_rule_walker_custom_attribute_enum_no_hard_error(self) -> None:
        # hub_set_rule edit -> addRequiredExpression (STPage reveal walker) with an
        # ENUM-recognized Custom Attribute condition. The walker pre-fix THREW
        # ("RelrDev_<N> not revealed") because the enum re-render hides the comparator
        # and reveals state_<N> directly. The fix branches to the enum path: it writes
        # the value to state_<N>, skips the comparator, does NOT throw, and does NOT
        # flag partial. This pins the walker enum contract end-to-end on a live hub.
        sw = int(self.get_test_switch_id())
        app_id = self._create_native_rule("WalkEnum")
        try:
            result = self.client.call_tool("hub_manage_rule_machine", {
                "tool": "hub_set_rule",
                "args": {
                    "appId": app_id,
                    "addRequiredExpression": {"conditions": [
                        {"capability": "Custom Attribute", "deviceIds": [sw],
                         "attribute": "switch", "comparator": "=", "state": "on"}]},
                    "confirm": True,
                },
            })
            # The whole point: the walker no longer hard-errors on the enum attribute.
            assert result.get("success") is not False, \
                f"addRequiredExpression hard-errored on an enum Custom Attribute (the walker bug): {result}"
            applied = result.get("settingsApplied") or []
            assert any(str(k).startswith("state_") for k in applied), \
                f"walker enum value did not land in a state_<N> field; settingsApplied={applied}"
            skipped = result.get("settingsSkipped") or []
            bad = [s for s in skipped if isinstance(s, dict)
                   and (s.get("key") or "").startswith("RelrDev_")
                   and s.get("reason") == "not_in_schema"]
            assert not bad, f"unexpected RelrDev_<N> not_in_schema skip on the walker enum path: {bad}"
            assert not result.get("partial"), \
                f"walker enum condition falsely flagged partial: {result}"
            self._assert_rule_healthy(app_id)
        finally:
            self._delete_native(app_id)

    @test("native_apps")
    def test_set_rule_walker_custom_attribute_enum_doactpage_no_hard_error(self) -> None:
        # Same shared walker (_rmWalkConditionReveal), but reached via the doActPage
        # surface (addAction ifThen) rather than STPage (addRequiredExpression). The
        # 4th of the four wizard surfaces that carry the enum-recognized Custom
        # Attribute bug. The enum re-render hides the comparator RelrDev_<N> and
        # reveals state_<N> directly; the fix branches to the enum path (writes
        # state_<N>, skips the comparator, does NOT throw, does NOT flag partial).
        sw = int(self.get_test_switch_id())
        app_id = self._create_native_rule("WalkEnumAct")
        try:
            result = self.client.call_tool("hub_manage_rule_machine", {
                "tool": "hub_set_rule",
                "args": {
                    "appId": app_id,
                    "addAction": {"capability": "ifThen", "expression": {"conditions": [
                        {"capability": "Custom Attribute", "deviceIds": [sw],
                         "attribute": "switch", "comparator": "=", "state": "on"}]}},
                    "confirm": True,
                },
            })
            # The whole point: the doActPage walker no longer hard-errors on the enum attr.
            assert result.get("success") is not False, \
                f"addAction ifThen hard-errored on an enum Custom Attribute (the walker bug): {result}"
            applied = result.get("settingsApplied") or []
            assert any(str(k).startswith("state_") for k in applied), \
                f"doActPage walker enum value did not land in a state_<N> field; settingsApplied={applied}"
            skipped = result.get("settingsSkipped") or []
            bad = [s for s in skipped if isinstance(s, dict)
                   and (s.get("key") or "").startswith("RelrDev_")
                   and s.get("reason") == "not_in_schema"]
            assert not bad, f"unexpected RelrDev_<N> not_in_schema skip on the doActPage walker enum path: {bad}"
            assert not result.get("partial"), \
                f"doActPage walker enum condition falsely flagged partial: {result}"
            # Close the IF block (THEN body + endIf) before the whole-rule health
            # check. An ifThen opener added alone is a valid intermediate tool state,
            # but it leaves the rule with an unclosed IF that the live hub's
            # rule-health check correctly flags -- the enum-condition contract under
            # test is already proven by the assertions above; completing the block is
            # what makes the end-to-end health assertion meaningful.
            self._set_rule(app_id, {"addAction": {"capability": "log", "message": "fired"}})
            self._set_rule(app_id, {"addAction": {"capability": "endIf"}})
            self._assert_rule_healthy(app_id)
        finally:
            self._delete_native(app_id)

    @test("native_apps")
    def test_set_rule_walker_compare_to_device(self) -> None:
        # hub_set_rule edit -> addRequiredExpression (STPage reveal walker) with a
        # device-relative compareToDevice condition. This is the only automated guard
        # against wire-format drift for two client-observable behaviours this feature
        # changes: (1) the device-relative RHS now actually lands (isDev_<N> toggles,
        # relDevice_<N> is written, the rule renders "Temperature of A is > B - 2.0"),
        # and (2) a literal RHS (state/value) combined with compareToDevice is now a hard
        # reject, not a silent literal fallback. The unit Spock suite proves the logic in
        # isolation; this proves it end-to-end against a live hub, where the feature was
        # unit-green but live-wrong before the wire-up fix.
        dev_a, dev_b = self.get_test_temperature_ids()
        app_id = self._create_native_rule("CtdWalk")
        try:
            result = self.client.call_tool("hub_manage_rule_machine", {
                "tool": "hub_set_rule",
                "args": {
                    "appId": app_id,
                    "addRequiredExpression": {"conditions": [
                        {"capability": "Temperature", "deviceIds": [int(dev_a)],
                         "comparator": ">",
                         "compareToDevice": {"deviceId": int(dev_b),
                                             "attribute": "temperature", "offset": -2}}]},
                    "confirm": True,
                },
            })
            # (1) The device-relative condition committed cleanly.
            assert result.get("success") is not False, \
                f"compareToDevice addRequiredExpression reported failure: {result}"
            assert not result.get("partial"), \
                f"compareToDevice condition falsely flagged partial (the live-wrong false-partial): {result}"
            applied = result.get("settingsApplied") or []
            assert any(str(k).startswith("isDev_") for k in applied), \
                f"isDev_<N> toggle did not land -- the device-relative RHS was not wired: {applied}"
            assert any(str(k).startswith("relDevice_") for k in applied), \
                f"relDevice_<N> reference picker did not land: {applied}"
            # The rule renders device-relative text, NOT a literal threshold / "A > 0".
            cfg = self.client.call_tool("hub_read_apps_code", {
                "tool": "hub_get_app_config", "args": {"appId": app_id},
            })
            blob = str(cfg)
            assert ("Temperature of" in blob) or ("temperature of" in blob.lower()), \
                f"rule paragraph does not render a device-relative Temperature comparison: {blob[:600]}"
            # No degradation skip for the empty device-picker option list (normal case).
            skipped = result.get("settingsSkipped") or []
            bad = [s for s in skipped if isinstance(s, dict)
                   and s.get("key") == "compareToDevice-validation"]
            assert not bad, f"unexpected compareToDevice-validation skip (empty device-picker options are normal): {bad}"

            # (2) compareToDevice + a literal state RHS is now a HARD reject (fail-loud),
            # not a silent literal fallback. The mutual-exclusion guard is a pre-write
            # check inside the walker, so exercise it on a FRESH rule: adding a second
            # Required Expression to a rule that already has one (the first-RE committed
            # above) takes a different path that never reaches the per-condition walker
            # check, masking the guard's error behind the second-RE failure.
            reject_app_id = self._create_native_rule("CtdReject")
            try:
                try:
                    rej = self.client.call_tool("hub_manage_rule_machine", {
                        "tool": "hub_set_rule",
                        "args": {
                            "appId": reject_app_id,
                            "addRequiredExpression": {"conditions": [
                                {"capability": "Temperature", "deviceIds": [int(dev_a)],
                                 "comparator": ">", "state": 70,
                                 "compareToDevice": {"deviceId": int(dev_b),
                                                     "attribute": "temperature"}}]},
                            "confirm": True,
                        },
                    })
                    assert rej.get("success") is False, \
                        f"compareToDevice + literal state should hard-reject (not partial/success): {rej}"
                    assert "cannot be combined with 'state'/'value'" in str(rej.get("error", "")), \
                        f"reject error should name the mutual-exclusion: {rej}"
                except (McpToolError, McpError) as exc:
                    assert "cannot be combined with 'state'/'value'" in str(exc), \
                        f"compareToDevice + literal state fail-loud should name the mutual-exclusion: {exc}"
            finally:
                self._delete_native(reject_app_id)

            self._assert_rule_healthy(app_id)
        finally:
            self._delete_native(app_id)

    @test("native_apps")
    def test_set_rule_required_expression_and_local_var(self) -> None:
        # hub_set_rule edit -> addLocalVariable + addRequiredExpression (STPage) wizards.
        sw = int(self.get_test_switch_id())
        app_id = self._create_native_rule("RE_Var")
        self._set_rule(app_id, {"addLocalVariable": {"name": "batCounter", "type": "Number", "value": 0}})
        self._set_rule(app_id, {"addRequiredExpression": {"conditions": [
            {"capability": "Switch", "deviceIds": [sw], "state": "on"}]}})
        self._assert_rule_healthy(app_id)
        self._delete_native(app_id)

    @test("native_apps")
    def test_set_rule_create_with_required_expression(self) -> None:
        # hub_set_rule CREATE (no appId) bundling addRequiredExpression. Pre-fix the
        # create arm read only addTriggers/addActions, so a bundled RE was silently
        # dropped and the call returned success=True on an empty shell. The fix honors
        # addRequiredExpression on create (runs the RE walk post-create) and surfaces
        # its outcome under result.requiredExpression. This pins create-with-RE
        # end-to-end: the RE field is present (NOT dropped), the RE actually lands on
        # the rule, and the rule is healthy.
        sw = int(self.get_test_switch_id())
        created = self.client.call_tool("hub_manage_rule_machine", {
            "tool": "hub_set_rule",
            "args": {
                "name": f"{PREFIX}CreateRE",
                "addRequiredExpression": {"conditions": [
                    {"capability": "Switch", "deviceIds": [sw], "state": "on"}]},
                "confirm": True,
            },
        })
        app_id = created.get("appId")
        assert app_id, f"create-with-RE did not return appId: {created}"
        self.created_native_app_ids.append(str(app_id))
        try:
            # The whole point: the bundled RE was honored, not silently dropped.
            re_result = created.get("requiredExpression")
            assert re_result is not None, \
                f"addRequiredExpression was silently dropped on create (no requiredExpression in result): {created}"
            assert re_result.get("success") is not False, \
                f"bundled addRequiredExpression failed on create: {re_result}"
            # The RE actually landed: a condition index was returned by the walk.
            assert re_result.get("conditionIndices"), \
                f"create-with-RE produced no conditionIndices -- the expression did not land: {re_result}"
            self._assert_rule_healthy(app_id)
        finally:
            self._delete_native(app_id)

    @test("native_apps")
    def test_set_rule_action_mutations(self) -> None:
        # hub_set_rule edit -> addActions (bulk) + removeAction + clearActions + replaceActions.
        app_id = self._create_native_rule("Act_Mut")
        self._set_rule(app_id, {"addActions": [
            {"capability": "log", "message": "one"},
            {"capability": "log", "message": "two"},
        ]})
        first = self._set_rule(app_id, {"addAction": {"capability": "log", "message": "three"}})
        idx = first.get("actionIndex")
        assert idx is not None, f"addAction did not return an actionIndex (contract regression): {first}"
        self._set_rule(app_id, {"removeAction": {"index": idx}})
        self._set_rule(app_id, {"clearActions": True})
        self._set_rule(app_id, {"replaceActions": [{"capability": "log", "message": "final"}]})
        self._assert_rule_healthy(app_id)
        self._delete_native(app_id)

    @test("native_apps")
    def test_set_rule_move_action(self) -> None:
        # hub_set_rule edit -> moveAction (isolated, short). On a slow hub the
        # move-arrow click can commit late; the tool does one short re-check then
        # returns a soft asyncCommitLikely envelope instead of a hard
        # false-negative. Accept either a confirmed shift OR the soft envelope --
        # call hub_set_rule directly since _set_rule would raise on success=False.
        app_id = self._create_native_rule("Move")
        self._set_rule(app_id, {"addActions": [
            {"capability": "log", "message": "a"},
            {"capability": "log", "message": "b"},
            {"capability": "log", "message": "c"},
        ]})
        result = self.client.call_tool("hub_manage_rule_machine", {
            "tool": "hub_set_rule",
            "args": {"appId": app_id, "moveAction": {"index": 1, "direction": "down"}, "confirm": True},
        })
        assert result.get("success") is True or result.get("asyncCommitLikely") is True, \
            f"moveAction must confirm the shift OR report asyncCommitLikely (never a hard false-negative): {result}"
        self._assert_rule_healthy(app_id)
        self._delete_native(app_id)

    @test("native_apps")
    def test_set_rule_trigger_mutations(self) -> None:
        # hub_set_rule edit -> modifyTrigger (state) + removeTrigger.
        sw = int(self.get_test_switch_id())
        app_id = self._create_native_rule("Trig_Mut")
        added = self._set_rule(app_id, {"addTrigger": {"capability": "Switch", "deviceIds": [sw], "state": "on"}})
        tidx = added.get("triggerIndex")
        assert tidx is not None, f"addTrigger did not return a triggerIndex (contract regression): {added}"
        mod = self._set_rule(app_id, {"modifyTrigger": {"index": tidx, "mods": {"state": "off"}}})
        # modifyTrigger now reads the PERSISTED tstate (configure/json), so
        # verifiedState echoes the new value instead of always being null
        # (the old readback hit the closed selectTriggers wizard page).
        if mod.get("verificationFetchFailed") is not True:
            assert mod.get("verifiedState") == "off", \
                f"modifyTrigger verifiedState should echo the persisted new state 'off', got {mod.get('verifiedState')!r}: {mod}"
        self._set_rule(app_id, {"removeTrigger": {"index": tidx}})
        self._assert_rule_healthy(app_id)
        self._delete_native(app_id)

    @test("native_apps")
    def test_set_rule_patches(self) -> None:
        # hub_set_rule edit -> patches (atomic multi-op).
        app_id = self._create_native_rule("Patches")
        self._set_rule(app_id, {"patches": [
            {"addAction": {"capability": "log", "message": "p1"}},
            {"addAction": {"capability": "log", "message": "p2"}},
        ]})
        self._assert_rule_healthy(app_id)
        self._delete_native(app_id)

    @test("native_apps")
    def test_set_rule_raw_settings_and_button(self) -> None:
        # hub_set_rule edit -> the generic raw settings + button (page-transition) path,
        # then read the value back via hub_get_app_config. (BAT-confirmed shapes.)
        app_id = self._create_native_rule("Raw")
        self._set_rule(app_id, {"settings": {"comments": "BAT_E2E raw settings", "logging": ["Triggers", "Actions"]}})
        self._set_rule(app_id, {"button": "updateRule"})
        cfg = self.client.call_tool("hub_read_apps_code", {"tool": "hub_get_app_config", "args": {"appId": app_id, "includeSettings": True}})
        settings = cfg.get("settings") or {}
        assert settings.get("comments") == "BAT_E2E raw settings", f"comments did not round-trip: {settings.get('comments')!r}"
        self._delete_native(app_id)

    @test("native_apps")
    def test_set_rule_walkstep_introspect(self) -> None:
        # hub_set_rule edit -> walkStep (schema-aware single-step walker), read-only op.
        app_id = self._create_native_rule("Walk")
        result = self._set_rule(app_id, {"walkStep": {"page": "selectTriggers", "operation": "introspect"}})
        assert isinstance(result, dict), f"walkStep introspect returned non-dict: {result}"
        self._delete_native(app_id)

    @test("native_apps")
    def test_set_rule_discover_meta(self) -> None:
        # hub_set_rule meta-call routing: {addTrigger:{discover:true}} returns the live
        # schema with NO appId / NO mutation (routed to the edit engine's short-circuit).
        result = self.client.call_tool("hub_manage_rule_machine", {
            "tool": "hub_set_rule", "args": {"addTrigger": {"discover": True}},
        })
        blob = str(result)
        assert "capability" in blob, f"addTrigger discover did not return a schema: {blob[:200]}"

    @test("native_apps")
    def test_delete_native_app_from_native_gateway(self) -> None:
        # hub_delete_native_app is cross-listed in BOTH gateways. Create via the RM
        # gateway, delete via the native gateway, and confirm it is gone.
        app_id = self._create_native_rule("CrossGwDelete")
        self.client.call_tool("hub_manage_native_rules_and_apps", {
            "tool": "hub_delete_native_app", "args": {"appId": app_id, "force": True, "confirm": True},
        })
        self._untrack_native_app(app_id)
        try:
            cfg = self.client.call_tool("hub_read_apps_code", {"tool": "hub_get_app_config", "args": {"appId": app_id}})
            assert cfg.get("success") is False, f"app {app_id} should be gone after delete, got: {cfg}"
        except (McpToolError, McpError):
            pass  # also acceptable: the read errors because the app no longer exists

    @test("native_apps")
    def test_set_native_app_button_edit(self) -> None:
        # hub_set_native_app edit path also drives a page-transition button (generic).
        created = self.client.call_tool("hub_manage_native_rules_and_apps", {
            "tool": "hub_set_native_app",
            "args": {"appType": "rule_machine", "name": f"{PREFIX}GenericButton", "confirm": True},
        })
        app_id = created.get("appId")
        assert app_id, f"hub_set_native_app create did not return appId: {created}"
        self.created_native_app_ids.append(str(app_id))
        edited = self.client.call_tool("hub_manage_native_rules_and_apps", {
            "tool": "hub_set_native_app", "args": {"appId": app_id, "button": "updateRule", "confirm": True},
        })
        assert edited.get("success") is not False, f"hub_set_native_app button edit failed: {edited}"
        self._delete_native(app_id, gateway="hub_manage_native_rules_and_apps")

    # -----------------------------------------------------------------------
    # GROUP 4b2: visual_rules -- the Visual Rules Builder tools
    # (hub_get_visual_rule / hub_set_visual_rule / hub_delete_visual_rule).
    # VRB rules are Vue-JSON apps (NOT classic dynamicPage apps), saved over the
    # /app/ruleBuilderJson endpoint family in one of two wire formats the hub
    # FIRMWARE picks at creation: 'classic' ({whenNodes, thenNodes, elseNodes})
    # or 'graph' ({version, nodes, edges}).
    # -----------------------------------------------------------------------

    def _vrb_definition(self, fmt: str, switch_id: int, switch_event: str) -> dict:
        """Equivalent VRB definition in either wire format: when the test switch
        fires `switch_event`, re-assert that same state. The firmware (not the
        caller) decides which format newly-created rules speak, so the lifecycle
        test needs the same semantic rule expressible both ways.

        HARMLESS-STRAND INVARIANT: the action always MATCHES the trigger event
        ('Turns off' -> turnOff, 'Turns on' -> turnOn). These fixtures live on the
        shared hub until their delete commits, and a delete can silently strand
        (the disarm force-delete trusts an HTTP 302; admin-endpoint writes are
        known to commit late on this firmware). A stranded copy of a matched rule
        can only re-assert the state the switch already reached -- it can never
        revert a test command. An OPPOSING action ('Turns on' -> turnOff) stranded
        across runs instantly reverts every 'on' the switch-command test sends."""
        action = "turnOff" if switch_event == "Turns off" else "turnOn"
        if fmt == "classic":
            return {
                "whenNodes": [{"triggerType": "switch", "switches": [switch_id], "deviceIds": [switch_id],
                               "switchEvent": switch_event, "index": 0, "type": "when"}],
                "thenNodes": [{"actionType": action, "switches": [switch_id], "deviceIds": [switch_id],
                               "index": 0, "type": "then"}],
                "elseNodes": [],
            }
        return {
            "version": 1,
            "nodes": [
                {"id": "t1", "type": "trigger", "triggerCondition": "trigger", "triggerType": "switch",
                 "switches": [switch_id], "deviceIds": [switch_id], "switchEvent": switch_event},
                {"id": "a1", "type": "action", "actionType": action,
                 "switches": [switch_id], "deviceIds": [switch_id]},
            ],
            "edges": [{"from": "t1", "to": "a1", "port": "next"}],
        }

    def _get_visual_rule(self, app_id: Any = None) -> Any:
        """hub_get_visual_rule through the PURE-READ gateway (hub_read_rules cross-listing)."""
        args = {} if app_id is None else {"appId": app_id}
        return self.client.call_tool("hub_read_rules", {"tool": "hub_get_visual_rule", "args": args})

    @test("visual_rules")
    def test_visual_rule_classic_lifecycle(self) -> None:
        # Full VRB round-trip: create (classic-first, one graph retry -- the hub
        # firmware decides the native format), read back via the pure-read gateway,
        # list, rename+pause, resume, wholesale replace, delete-with-verify.
        switch_id = int(self.get_test_switch_id())
        name = f"{PREFIX}VisualRule"
        created = self.client.call_tool("hub_manage_rule_machine", {
            "tool": "hub_set_visual_rule",
            "args": {"name": name, "confirm": True,
                     "definition": self._vrb_definition("classic", switch_id, "Turns off")},
        })
        if created.get("success") is False and created.get("hubNativeFormat") == "graph":
            # This firmware's builder creates graph-format rules (the tool already
            # force-deleted the orphan shell); retry once with the equivalent graph
            # definition so the lifecycle still executes deterministically.
            created = self.client.call_tool("hub_manage_rule_machine", {
                "tool": "hub_set_visual_rule",
                "args": {"name": name, "confirm": True,
                         "definition": self._vrb_definition("graph", switch_id, "Turns off")},
            })
        app_id = created.get("appId")
        assert app_id, f"hub_set_visual_rule create did not return an appId: {created}"
        self.created_native_app_ids.append(str(app_id))
        assert created.get("success") is True, f"hub_set_visual_rule create did not verify: {created}"
        assert created.get("created") is True, f"create response missing created=true: {created}"
        fmt = created.get("format")
        assert fmt in ("classic", "graph"), f"create returned an unknown format {fmt!r}: {created}"

        try:
            # READ back through the pure-read gateway: name/format/definition round-trip.
            got = self._get_visual_rule(app_id)
            assert got.get("success") is True, f"read-back of new VRB rule {app_id} failed: {got}"
            assert got.get("name") == name, f"read-back name mismatch: {got.get('name')!r} != {name!r}"
            assert got.get("format") == fmt, f"read-back format {got.get('format')!r} != create format {fmt!r}"
            # Pull the trigger node in whichever shape the rule speaks and assert
            # the test switch's id is in its deviceIds array -- a blob substring
            # match would false-pass on any field that happens to contain the digits.
            if fmt == "classic":
                trigger_node = (got.get("whenNodes") or [None])[0]
            else:
                trigger_node = next(
                    (n for n in (got.get("definition") or {}).get("nodes", [])
                     if n.get("type") == "trigger"), None)
            assert isinstance(trigger_node, dict), \
                f"no trigger node in the {fmt} read-back: {got}"
            assert int(switch_id) in (trigger_node.get("deviceIds") or []), \
                f"trigger device {switch_id} not in the trigger node's deviceIds: {trigger_node}"

            # LIST: no-args mode must include the new rule.
            listed = self._get_visual_rule()
            assert listed.get("success") is True, f"hub_get_visual_rule list mode failed: {listed}"
            ids = [str(r.get("appId")) for r in (listed.get("rules") or [])]
            assert str(app_id) in ids, f"created VRB rule {app_id} not in the listing: {ids}"

            # RENAME + PAUSE in one call, then prove both landed via an independent read.
            renamed = f"{PREFIX}VisualRuleRenamed"
            res = self.client.call_tool("hub_manage_rule_machine", {
                "tool": "hub_set_visual_rule",
                "args": {"appId": app_id, "name": renamed, "paused": True, "confirm": True},
            })
            assert res.get("success") is True, f"rename+pause reported failure: {res}"
            got = self._get_visual_rule(app_id)
            assert got.get("name") == renamed, f"rename did not land: {got.get('name')!r}"
            assert got.get("rulePaused") is True, f"pause did not land: {got}"

            # RESUME.
            res = self.client.call_tool("hub_manage_rule_machine", {
                "tool": "hub_set_visual_rule",
                "args": {"appId": app_id, "paused": False, "confirm": True},
            })
            assert res.get("success") is True, f"resume reported failure: {res}"
            got = self._get_visual_rule(app_id)
            assert got.get("rulePaused") is False, f"resume did not land: {got}"

            # REPLACE: wholesale definition edit in the SAME format the rule speaks
            # ('Turns on' variant), verified by the tool's read-back AND our own.
            res = self.client.call_tool("hub_manage_rule_machine", {
                "tool": "hub_set_visual_rule",
                "args": {"appId": app_id, "confirm": True,
                         "definition": self._vrb_definition(fmt, switch_id, "Turns on")},
            })
            assert res.get("success") is True and res.get("verified") is True, \
                f"definition replacement not verified: {res}"
            got = self._get_visual_rule(app_id)
            replaced_blob = json.dumps(got.get("whenNodes") if fmt == "classic" else got.get("definition"))
            assert "Turns on" in replaced_blob, \
                f"replaced definition did not round-trip 'Turns on': {replaced_blob[:300]}"
        finally:
            # DELETE inline -- the delete contract IS part of the lifecycle under
            # test (the tracked-id sweep stays as backstop if this raises).
            deleted = self.client.call_tool("hub_manage_rule_machine", {
                "tool": "hub_delete_visual_rule", "args": {"appId": app_id, "confirm": True},
            })
            assert deleted.get("success") is True, f"hub_delete_visual_rule reported failure: {deleted}"
            assert deleted.get("verified") is True, f"delete not verified gone: {deleted}"
            assert deleted.get("predeleteDefinition"), \
                f"delete response missing the predeleteDefinition recovery aid: {deleted}"
            gone = self._get_visual_rule(app_id)
            assert gone.get("success") is False, f"rule {app_id} still readable after delete: {gone}"
            # Untrack only after the independent gone-read: a false-verified delete
            # leaves the id tracked so the cleanup sweep reaps it.
            self._untrack_native_app(app_id)

    @test("visual_rules")
    def test_visual_rule_backup_restore(self) -> None:
        # VRB-aware backup/restore round-trip: hub_delete_native_app's pre-delete
        # snapshot must capture the rule's VRB definition (Vue children may not
        # serve configure/json), and hub_restore_backup must RECREATE the deleted
        # rule from it under a NEW appId -- proven by reading the recreated rule
        # back, not by trusting the restore response flags alone.
        switch_id = int(self.get_test_switch_id())
        name = f"{PREFIX}VrbRestore"
        created = self.client.call_tool("hub_manage_rule_machine", {
            "tool": "hub_set_visual_rule",
            "args": {"name": name, "confirm": True,
                     "definition": self._vrb_definition("classic", switch_id, "Turns off")},
        })
        if created.get("success") is False and created.get("hubNativeFormat") == "graph":
            # Same firmware-decides-the-format adaptation as the lifecycle test
            # (the tool already force-deleted the orphan classic shell).
            created = self.client.call_tool("hub_manage_rule_machine", {
                "tool": "hub_set_visual_rule",
                "args": {"name": name, "confirm": True,
                         "definition": self._vrb_definition("graph", switch_id, "Turns off")},
            })
        app_id = created.get("appId")
        assert app_id, f"hub_set_visual_rule create did not return an appId: {created}"
        self.created_native_app_ids.append(str(app_id))
        assert created.get("success") is True, f"hub_set_visual_rule create did not verify: {created}"
        fmt = created.get("format")
        assert fmt in ("classic", "graph"), f"create returned an unknown format {fmt!r}: {created}"

        # DELETE through hub_delete_native_app -- THIS path takes the VRB-aware
        # snapshot (the feature's entry point); hub_delete_visual_rule does not.
        # If any of its asserts fire, the original id stays tracked for the sweep.
        deleted = self.client.call_tool("hub_manage_native_rules_and_apps", {
            "tool": "hub_delete_native_app",
            "args": {"appId": app_id, "force": True, "confirm": True},
        })
        assert deleted.get("success") is True, f"hub_delete_native_app reported failure: {deleted}"
        backup_key = (deleted.get("backup") or {}).get("backupKey")
        assert backup_key, f"delete response carries no backup.backupKey to restore from: {deleted}"
        gone = self._get_visual_rule(app_id)
        assert gone.get("success") is False, f"rule {app_id} still readable after delete: {gone}"
        # Untrack only after the independent gone-read (lifecycle-test discipline).
        self._untrack_native_app(app_id)

        # RESTORE from the snapshot. Nested try: the finally below also runs when a
        # restore assert fires mid-flight, reaping a recreated-but-unverified rule
        # (when the restore failed before minting one, only the original tracked id
        # mattered -- it is already deleted; the sweep handles strays).
        new_id = None
        try:
            restored = self.client.call_tool("hub_manage_code", {
                "tool": "hub_restore_backup",
                "args": {"backupKey": backup_key, "confirm": True},
            })
            # Track the recreated id IMMEDIATELY -- even a failed-verification
            # restore leaves a live recreated app behind to clean up.
            new_id = restored.get("ruleId")
            if new_id:
                self.created_native_app_ids.append(str(new_id))
            assert restored.get("success") is True, f"hub_restore_backup reported failure: {restored}"
            assert restored.get("type") == "visual-rule", \
                f"restore should route through the visual-rule arm: {restored}"
            assert restored.get("recreated") is True, \
                f"restoring a DELETED rule must recreate, not patch in place: {restored}"
            assert restored.get("verified") is True, f"restore read-back did not verify: {restored}"
            assert restored.get("format") == fmt, \
                f"restored format {restored.get('format')!r} != created format {fmt!r}: {restored}"
            assert new_id and str(new_id) != str(app_id), \
                f"recreate must mint a NEW appId (original {app_id} is gone): {restored}"

            # Round-trip read-back: the restored rule speaks the original name,
            # format, and trigger device (same node extraction as the lifecycle
            # test -- a blob substring match would false-pass).
            got = self._get_visual_rule(new_id)
            assert got.get("success") is True, f"read-back of restored rule {new_id} failed: {got}"
            assert got.get("name") == name, \
                f"restored name mismatch: {got.get('name')!r} != {name!r}"
            assert got.get("format") == fmt, \
                f"restored rule format {got.get('format')!r} != {fmt!r}"
            if fmt == "classic":
                trigger_node = (got.get("whenNodes") or [None])[0]
            else:
                trigger_node = next(
                    (n for n in (got.get("definition") or {}).get("nodes", [])
                     if n.get("type") == "trigger"), None)
            assert isinstance(trigger_node, dict), \
                f"no trigger node in the restored {fmt} read-back: {got}"
            assert int(switch_id) in (trigger_node.get("deviceIds") or []), \
                f"trigger device {switch_id} not in the restored trigger node's deviceIds: {trigger_node}"
        finally:
            # Cleanup the RESTORED rule (the original is already gone). Same delete
            # contract + untrack-after-gone-assert discipline as the lifecycle test.
            if new_id:
                deleted = self.client.call_tool("hub_manage_rule_machine", {
                    "tool": "hub_delete_visual_rule", "args": {"appId": new_id, "confirm": True},
                })
                assert deleted.get("success") is True, \
                    f"cleanup delete of restored rule {new_id} failed: {deleted}"
                gone = self._get_visual_rule(new_id)
                assert gone.get("success") is False, \
                    f"restored rule {new_id} still readable after cleanup delete: {gone}"
                self._untrack_native_app(new_id)

    @test("visual_rules")
    def test_visual_rule_error_contracts(self) -> None:
        # (a) Nonexistent appId -> structured runtime error (success:false envelope,
        # NOT a throw), enriched via /installedapp/json so the model can re-route.
        res = self._get_visual_rule(99999999)
        assert res.get("success") is False, f"nonexistent appId should report success:false: {res}"
        assert "no installed app" in str(res.get("error", "")).lower(), \
            f"not-found error should name the missing app: {res}"

        # (b) confirm safety gate on the write: same refusal contract as the rooms
        # gates -- a missing REQUIRED confirm surfaces as an isError envelope
        # (returned by call_tool as the parsed dict) or a raised McpError/-32602.
        switch_id = int(self.get_test_switch_id())
        no_confirm_name = f"{PREFIX}VisualNoConfirm"
        refused = False
        detail = None
        try:
            detail = self.client.call_tool("hub_manage_rule_machine", {
                "tool": "hub_set_visual_rule",
                "args": {"name": no_confirm_name,
                         "definition": self._vrb_definition("classic", switch_id, "Turns off")},
            })
            if isinstance(detail, dict) and detail.get("appId"):
                # The gate regressed and actually created a rule -- track it so the
                # cleanup backstop reaps it; the refusal assert below still fails.
                self.created_native_app_ids.append(str(detail["appId"]))
            blob = (detail if isinstance(detail, str) else json.dumps(detail)).lower()
            refused = (isinstance(detail, dict) and bool(detail.get("isError"))) \
                or "confirm" in blob or "required parameter" in blob
        except McpError as e:  # also catches McpToolError (subclass): a raised envelope / -32602
            detail = str(e)
            refused = any(s in detail.lower() for s in ("confirm", "safety check", "required parameter"))
        assert refused, \
            f"hub_set_visual_rule without confirm should have been refused by the safety gate, got: {detail}"
        listed = self._get_visual_rule()
        assert not any(
            r.get("name") == no_confirm_name for r in (listed.get("rules") or [])
        ), "hub_set_visual_rule without confirm must NOT create the rule"

        # (c) hub_set_native_app refuses appType=visual_rule (VRB children are
        # Vue-JSON apps the classic wizard cannot configure) and redirects to the
        # dedicated tool. IAE -> -32602; mirrors the kelvin fail-fast pattern.
        try:
            res = self.client.call_tool("hub_manage_native_rules_and_apps", {
                "tool": "hub_set_native_app",
                "args": {"appType": "visual_rule", "name": f"{PREFIX}VisualViaNative", "confirm": True},
            })
            blob = res if isinstance(res, str) else json.dumps(res)
            assert "hub_set_visual_rule" in blob, \
                f"appType=visual_rule should redirect to hub_set_visual_rule, got: {res}"
        except McpError as exc:
            assert "hub_set_visual_rule" in str(exc), \
                f"appType=visual_rule error should point at hub_set_visual_rule: {exc}"

    @test("visual_rules")
    def test_visual_rule_type_gate(self) -> None:
        # The VRB tools are type-gated: an RM rule's appId must be refused by the
        # read AND by the delete (forcedelete removes ANY installed app, so the
        # gate is all that stands between a wrong id and a destroyed RM rule).
        rm_id = self._create_native_rule("VrbTypeGate")
        try:
            got = self._get_visual_rule(rm_id)
            assert got.get("success") is False, f"hub_get_visual_rule must refuse an RM rule id: {got}"
            assert str(got.get("appType", "")).startswith("Rule"), \
                f"type-gate error should carry the real appType (Rule-5.1): {got}"
            assert "hub_set_rule" in str(got.get("note", "")), \
                f"type-gate note should route to the RM tools: {got}"

            deleted = self.client.call_tool("hub_manage_rule_machine", {
                "tool": "hub_delete_visual_rule", "args": {"appId": rm_id, "confirm": True},
            })
            assert deleted.get("success") is False, \
                f"hub_delete_visual_rule must refuse (not delete) an RM rule: {deleted}"
            assert "not a visual rules builder rule" in str(deleted.get("error", "")).lower(), \
                f"refused delete should explain the type gate: {deleted}"

            # The RM rule must have survived the refused delete: health still reads
            # it (a deleted rule fetch fails -> ok:false, label:null).
            health = self.client.call_tool("hub_manage_rule_machine", {
                "tool": "hub_get_rule_health", "args": {"appId": rm_id},
            })
            assert f"{PREFIX}VrbTypeGate" in str(health.get("label") or ""), \
                f"RM rule {rm_id} did not survive the type-gated delete: {health}"
            assert health.get("ok") is not False, \
                f"RM rule {rm_id} unhealthy after the refused delete: {health}"
        finally:
            self._delete_native(rm_id)

    # -----------------------------------------------------------------------
    # GROUP 4c: deadman (1 test) -- the issue #243 install-commit fix, the exact
    # bug the E2E Dead-Man Watchdog tripped on. installAsUserApp must actually
    # COMMIT the install (submit Done) so initialize() runs and the instance is
    # live -- the pre-#243 path returned success:true / "installed() fired" yet left
    # an inert shell (app.installed==false, schedules never registered; the `committed`
    # field is introduced by this PR). This installs the
    # throwaway tests/fixtures/deadman-test-target.groovy (so a misfire can't
    # touch anything real), then asserts BOTH the tool's committed flag AND, via
    # an independent hub_get_app_config read, app.installed==true -- the shell
    # would report installed:false, the old silent false-pass.
    # -----------------------------------------------------------------------

    @test("deadman")
    def test_install_as_user_app_commits(self) -> None:
        fixture = (Path(__file__).resolve().parent
                   / "fixtures" / "deadman-test-target.groovy")
        source = fixture.read_text(encoding="utf-8")

        code_app_id = None
        instance_app_id = None
        try:
            # 1) Install the throwaway app CODE (inline source) -> code class id.
            created_code = self.client.call_tool("hub_manage_code", {
                "tool": "hub_create_app",
                "args": {"source": source, "confirm": True},
            })
            code_app_id = created_code.get("appId")
            assert code_app_id, f"hub_create_app(source) did not return an appId (code class): {created_code}"

            # 2) Create a RUNNING instance from that code -> the #243 commit path.
            installed = self.client.call_tool("hub_manage_code", {
                "tool": "hub_create_app",
                "args": {"installAsUserApp": code_app_id, "confirm": True},
            })
            instance_app_id = installed.get("instanceAppId")
            committed = installed.get("committed")
            assert committed is True, \
                f"installAsUserApp did not commit (committed={committed!r}) -- the #243 install fix regressed, leaving an inert shell: {installed}"
            assert instance_app_id, f"installAsUserApp committed but returned no instanceAppId: {installed}"

            # 3) INDEPENDENT verification: hub_get_app_config must report app.installed==true.
            # A shell (the old false-pass) reads installed:false even though the tool said committed.
            cfg = self.client.call_tool("hub_read_apps_code", {
                "tool": "hub_get_app_config",
                "args": {"appId": instance_app_id},
            })
            app_obj = (cfg.get("app") or {}) if isinstance(cfg, dict) else {}
            installed_flag = app_obj.get("installed")
            assert installed_flag is True, \
                f"hub_get_app_config reports app.installed={installed_flag!r} -- the instance is an inert shell, not a committed install: {app_obj}"

            print(f"    DEADMAN_INSTALL_COMMIT committed={committed} installed={installed_flag}")
        finally:
            # Clean up: delete the running instance first, then the code class.
            if instance_app_id:
                try:
                    self.client.call_tool("hub_manage_native_rules_and_apps", {
                        "tool": "hub_delete_native_app",
                        "args": {"appId": instance_app_id, "force": True, "confirm": True},
                    })
                except Exception as exc:
                    print(f"  [WARN] deadman cleanup: delete instance {instance_app_id} failed: {exc}")
            if code_app_id:
                try:
                    self.client.call_tool("hub_manage_code", {
                        "tool": "hub_delete_item",
                        "args": {"type": "app", "id": code_app_id, "confirm": True},
                    })
                except Exception as exc:
                    print(f"  [WARN] deadman cleanup: delete code class {code_app_id} failed: {exc}")

    # -----------------------------------------------------------------------
    # GROUP 4d: app_code_update (1 test) -- the hub_update_app code-deploy path
    # (POST /app/saveOrUpdateJson). One throwaway code class, four legs before its delete:
    # a real round-trip edit (success + version advance + source landed), the
    # hub's verbatim compile error on broken Groovy (not our generic fallback),
    # the client-side expectedVersion optimistic lock (refused, no write), and a
    # hub_restore_backup of the pre-update auto-backup (V1 back, undo key returned).
    # -----------------------------------------------------------------------

    @test("app_code_update")
    def test_update_app_code_lifecycle(self) -> None:
        # Throwaway Apps Code class (code only, never installed as an instance). The name
        # deliberately starts with "Deadman Test Target" (namespace mcptest) so the cleanup
        # Layer 5 startswith sweep reclaims a stranded copy if a crash skips the finally below.
        source_v1 = '''\
definition(
    name: "Deadman Test Target Update",
    namespace: "mcptest",
    author: "ci",
    description: "Throwaway e2e app-code update-leg target",
    category: "Utility",
    iconUrl: "https://raw.githubusercontent.com/hubitat/HubitatPublic/master/app-dev/icon.png",
    iconX2Url: "https://raw.githubusercontent.com/hubitat/HubitatPublic/master/app-dev/icon.png"
)

preferences {
    page(name: "p", title: "Update Leg Target", install: true, uninstall: true) {
        section { paragraph "Throwaway update-leg target. Marker: ${updateLegMarker()}" }
    }
}

def installed() {}
def updated() {}

def updateLegMarker() { return "UPDATE-LEG-MARKER-V1" }
'''
        code_app_id = None
        try:
            created = self.client.call_tool("hub_manage_code", {
                "tool": "hub_create_app",
                "args": {"source": source_v1, "confirm": True},
            })
            code_app_id = created.get("appId")
            assert code_app_id, f"hub_create_app(source) did not return an appId (code class): {created}"

            before = self.client.call_tool("hub_read_apps_code", {
                "tool": "hub_get_source",
                "args": {"type": "app", "id": code_app_id},
            })
            assert before.get("success") is True and before.get("version") is not None \
                and "UPDATE-LEG-MARKER-V1" in (before.get("source") or ""), \
                f"could not read back the created code class: {before}"
            version_before = int(before["version"])

            # Leg 1: round-trip edit -- valid modified source must save, advance the hub's
            # version counter, and be readable back via hub_get_source.
            source_v2 = source_v1.replace("UPDATE-LEG-MARKER-V1", "UPDATE-LEG-MARKER-V2")
            updated = self.client.call_tool("hub_manage_code", {
                "tool": "hub_update_app",
                "args": {"appId": code_app_id, "source": source_v2, "confirm": True},
            })
            assert updated.get("success") is True, f"hub_update_app round-trip failed: {updated}"
            assert updated.get("previousVersion") is not None, \
                f"hub_update_app success carries no previousVersion: {updated}"
            after = self.client.call_tool("hub_read_apps_code", {
                "tool": "hub_get_source",
                "args": {"type": "app", "id": code_app_id},
            })
            assert "UPDATE-LEG-MARKER-V2" in (after.get("source") or ""), \
                f"updated source did not land on the hub: {after}"
            version_after = int(after["version"])
            assert version_after > version_before, \
                f"version did not advance after update ({version_before} -> {version_after})"

            # Leg 2: compile error -- the hub's verbatim compiler text must ride back in
            # `error`, not our generic fallback string.
            source_broken = source_v2.replace(
                "def updated() {}",
                "def updated() { new ClassThatDoesNotExistBatE2e() }",
            )
            failed = self.client.call_tool("hub_manage_code", {
                "tool": "hub_update_app",
                "args": {"appId": code_app_id, "source": source_broken, "confirm": True},
            })
            assert failed.get("success") is False, f"broken Groovy was accepted: {failed}"
            err = str(failed.get("error") or "")
            assert err and err != "Update failed - the hub returned an error", \
                f"compile failure did not surface the hub's error text: {failed}"
            assert "unable to resolve" in err.lower() or "ClassThatDoesNotExistBatE2e" in err, \
                f"error text is not the hub's compiler output: {err!r}"

            # Leg 3: optimistic lock -- a stale expectedVersion must be refused client-side
            # with conflict:true, before anything is written.
            source_v3 = source_v2.replace("UPDATE-LEG-MARKER-V2", "UPDATE-LEG-MARKER-V3")
            conflicted = self.client.call_tool("hub_manage_code", {
                "tool": "hub_update_app",
                "args": {"appId": code_app_id, "source": source_v3,
                         "expectedVersion": 99999, "confirm": True},
            })
            assert conflicted.get("success") is False, \
                f"stale expectedVersion was accepted: {conflicted}"
            assert conflicted.get("conflict") is True, \
                f"conflict flag missing on optimistic-lock refusal: {conflicted}"

            # One re-read proves NEITHER refused leg wrote anything: still V2, no V3 marker,
            # version unchanged since the round-trip edit.
            final = self.client.call_tool("hub_read_apps_code", {
                "tool": "hub_get_source",
                "args": {"type": "app", "id": code_app_id},
            })
            final_src = final.get("source") or ""
            assert "UPDATE-LEG-MARKER-V2" in final_src and "UPDATE-LEG-MARKER-V3" not in final_src, \
                f"a refused update mutated the stored source: {final}"
            assert int(final["version"]) == version_after, \
                f"a refused update advanced the version ({version_after} -> {final.get('version')})"

            # Leg 4: restore -- the auto-backup snapped before the FIRST update still
            # holds the V1 source (backupItemSource keeps the pre-edit original for an
            # hour rather than re-snapshotting on the later legs), so hub_restore_backup
            # must bring V1 back and hand back a pre-restore backup key as the undo path.
            restored = self.client.call_tool("hub_manage_code", {
                "tool": "hub_restore_backup",
                "args": {"backupKey": f"app_{code_app_id}", "confirm": True},
            })
            assert restored.get("success") is True, f"hub_restore_backup failed: {restored}"
            pre_restore_key = restored.get("preRestoreBackup")
            assert pre_restore_key == f"prerestore_app_{code_app_id}", \
                f"restore did not return the pre-restore backup key: {restored}"
            after_restore = self.client.call_tool("hub_read_apps_code", {
                "tool": "hub_get_source",
                "args": {"type": "app", "id": code_app_id},
            })
            restored_src = after_restore.get("source") or ""
            assert "UPDATE-LEG-MARKER-V1" in restored_src and "UPDATE-LEG-MARKER-V2" not in restored_src, \
                f"restore did not bring back the pre-update source: {after_restore}"
            assert int(after_restore["version"]) > version_after, \
                f"restore reported success but the version did not advance ({version_after} -> {after_restore.get('version')})"

            print(f"    APP_CODE_UPDATE ok -- v{version_before}->v{version_after}; compile error + lock conflict both refused with no write; restore brought V1 back (undo key {pre_restore_key})")
        finally:
            if code_app_id:
                try:
                    self.client.call_tool("hub_manage_code", {
                        "tool": "hub_delete_item",
                        "args": {"type": "app", "id": code_app_id, "confirm": True},
                    })
                except Exception as exc:
                    print(f"  [WARN] app-code update cleanup: delete code class {code_app_id} failed: {exc}")

    # -----------------------------------------------------------------------
    # GROUP 4e: driver_code_update (1 test) -- the hub_update_driver code-deploy
    # path (POST /driver/saveOrUpdateJson), mirroring the app leg above: one
    # throwaway driver code class, a round-trip edit (success + version advance +
    # source landed) and the hub's verbatim compile error on broken Groovy.
    # -----------------------------------------------------------------------

    @test("driver_code_update")
    def test_update_driver_code_lifecycle(self) -> None:
        # Throwaway Drivers Code class (code only, never assigned to a device). The
        # name deliberately starts with "Deadman Test Target" (namespace mcptest) so
        # the cleanup Layer 5 startswith sweep reclaims a stranded copy if a crash
        # skips the finally below.
        source_v1 = '''\
metadata {
    definition(name: "Deadman Test Target Driver", namespace: "mcptest", author: "ci") {
        capability "Switch"
    }
}

def installed() {}
def updated() {}
def on() { sendEvent(name: "switch", value: "on") }
def off() { sendEvent(name: "switch", value: "off") }

def driverLegMarker() { return "DRIVER-LEG-MARKER-V1" }
'''
        driver_id = None
        try:
            created = self.client.call_tool("hub_manage_code", {
                "tool": "hub_create_driver",
                "args": {"source": source_v1, "confirm": True},
            })
            driver_id = created.get("driverId")
            assert created.get("success") is True and driver_id, \
                f"hub_create_driver(source) failed or returned no driverId: {created}"

            before = self.client.call_tool("hub_read_apps_code", {
                "tool": "hub_get_source",
                "args": {"type": "driver", "id": driver_id},
            })
            assert before.get("success") is True and before.get("version") is not None \
                and "DRIVER-LEG-MARKER-V1" in (before.get("source") or ""), \
                f"could not read back the created driver code class: {before}"
            version_before = int(before["version"])

            # Leg 1: round-trip edit -- valid modified source must save, advance the
            # hub's version counter, and be readable back via hub_get_source.
            source_v2 = source_v1.replace("DRIVER-LEG-MARKER-V1", "DRIVER-LEG-MARKER-V2")
            updated = self.client.call_tool("hub_manage_code", {
                "tool": "hub_update_driver",
                "args": {"driverId": driver_id, "source": source_v2, "confirm": True},
            })
            assert updated.get("success") is True, f"hub_update_driver round-trip failed: {updated}"
            assert updated.get("previousVersion") is not None, \
                f"hub_update_driver success carries no previousVersion: {updated}"
            after = self.client.call_tool("hub_read_apps_code", {
                "tool": "hub_get_source",
                "args": {"type": "driver", "id": driver_id},
            })
            assert "DRIVER-LEG-MARKER-V2" in (after.get("source") or ""), \
                f"updated driver source did not land on the hub: {after}"
            version_after = int(after["version"])
            assert version_after > version_before, \
                f"driver version did not advance after update ({version_before} -> {version_after})"

            # Leg 2: compile error -- the hub's verbatim compiler text must ride back
            # in `error`, not our generic fallback string.
            source_broken = source_v2.replace(
                "def updated() {}",
                "def updated() { new ClassThatDoesNotExistBatE2eDrv() }",
            )
            failed = self.client.call_tool("hub_manage_code", {
                "tool": "hub_update_driver",
                "args": {"driverId": driver_id, "source": source_broken, "confirm": True},
            })
            assert failed.get("success") is False, f"broken driver Groovy was accepted: {failed}"
            err = str(failed.get("error") or "")
            assert err and err != "Update failed - the hub returned an error", \
                f"driver compile failure did not surface the hub's error text: {failed}"
            assert "unable to resolve" in err.lower() or "ClassThatDoesNotExistBatE2eDrv" in err, \
                f"error text is not the hub's compiler output: {err!r}"

            # One re-read proves the refused leg wrote nothing: still V2, no broken
            # marker, version unchanged since the round-trip edit.
            final = self.client.call_tool("hub_read_apps_code", {
                "tool": "hub_get_source",
                "args": {"type": "driver", "id": driver_id},
            })
            final_src = final.get("source") or ""
            assert "DRIVER-LEG-MARKER-V2" in final_src and "ClassThatDoesNotExistBatE2eDrv" not in final_src, \
                f"a refused driver update mutated the stored source: {final}"
            assert int(final["version"]) == version_after, \
                f"a refused driver update advanced the version ({version_after} -> {final.get('version')})"

            print(f"    DRIVER_CODE_UPDATE ok -- v{version_before}->v{version_after}; compile error refused with the hub's verbatim text")
        finally:
            if driver_id:
                try:
                    self.client.call_tool("hub_manage_code", {
                        "tool": "hub_delete_item",
                        "args": {"type": "driver", "id": driver_id, "confirm": True},
                    })
                except Exception as exc:
                    print(f"  [WARN] driver-code update cleanup: delete driver code class {driver_id} failed: {exc}")

    # -----------------------------------------------------------------------
    # GROUP 4f: installed_app_reads (2 tests) -- the thin app-summary mode of
    # hub_get_app_config (/installedapp/json/<id>) and the per-app events mode
    # of hub_list_device_events (/installedapp/eventsJson/<id>).
    # -----------------------------------------------------------------------

    @test("installed_app_reads")
    def test_get_app_config_summary_mode(self) -> None:
        # summary:true returns the thin identity payload WITHOUT the rendered config
        # page -- the cheap existence/identity probe for installed apps. Pin it on a
        # throwaway RM rule so the identity fields are deterministic.
        app_id = self._create_native_rule("CfgSummary")
        try:
            result = self.client.call_tool("hub_read_apps_code", {
                "tool": "hub_get_app_config",
                "args": {"appId": str(app_id), "summary": True},
            })
            assert result.get("success") is True, f"summary fetch failed: {result}"
            ident = result.get("app") if isinstance(result.get("app"), dict) else result
            assert str(ident.get("id")) == str(app_id), \
                f"summary identity id mismatch (expected {app_id}): {result}"
            label_blob = " ".join(str(ident.get(k) or "") for k in ("label", "name", "type"))
            assert PREFIX in label_blob, \
                f"summary identity carries no recognizable name/label for the fixture rule: {result}"
            # The point of summary mode: no rendered config page rides along.
            assert not result.get("page") and not result.get("configPage"), \
                f"summary:true must omit the rendered config page: {sorted(result.keys())}"
        finally:
            self._delete_native(app_id)

    @test("installed_app_reads")
    def test_list_app_events_structural(self) -> None:
        # Per-app events -- structural contract only. There is no cheap deterministic
        # way to make an app emit an event on demand (RM rules only write events when
        # they actually fire), so this pins the envelope (source=='app', list payload)
        # against the MCP server's own instance, which always exists; row-shape keys
        # are asserted only when rows came back.
        result = self.client.call_tool("hub_list_device_events", {
            "appId": str(self.client.app_id), "limit": 10,
        })
        assert isinstance(result, dict), f"app-events mode did not return an object: {result!r}"
        assert result.get("source") == "app", f"expected source=='app': {result}"
        events = result.get("events")
        assert isinstance(events, list), f"app-events mode did not return an events list: {result}"
        if events:
            row = events[0]
            assert isinstance(row, dict) and "name" in row and "date" in row, \
                f"app event row missing name/date: {row}"
        if result.get("count") is not None:
            assert int(result["count"]) == len(events), f"count != len(events): {result}"

        # deviceId and appId address different event tables; the combination must be
        # refused outright, not silently resolved to one of them.
        try:
            self.client.call_tool("hub_list_device_events", {
                "appId": str(self.client.app_id), "deviceId": self.get_first_device_id(),
            })
            raise AssertionError("hub_list_device_events accepted deviceId+appId together")
        except (McpToolError, McpError) as exc:
            blob = str(exc).lower()
            assert "appid" in blob or "deviceid" in blob or "exclusive" in blob, \
                f"mutual-exclusivity refusal does not name the conflicting params: {exc}"

    # -----------------------------------------------------------------------
    # GROUP 4g: device_swap (1 test) -- hub_call_device_swap child-device
    # ineligibility: an MCP-created virtual switch must be refused by the
    # hub's Swap Device tool with the structured eligibility error, and the
    # transient Swap Device instance must not leak.
    # -----------------------------------------------------------------------

    def _create_swap_switch(self, label: str) -> tuple[str, str]:
        """Create a BAT_E2E_ virtual-switch fixture and return (deviceId, dni).
        Tracked in created_device_dnis immediately so the Layer 1/2 cleanup reclaims
        it if the swap test crashes before its own teardown."""
        result = self.client.call_tool("hub_manage_virtual_device", {
            "action": "create",
            "deviceType": "Virtual Switch",
            "deviceLabel": label,
            "confirm": True,
        })
        dev_id = str(result.get("id", result.get("deviceId", "")) or "")
        dni = str(result.get("deviceNetworkId", result.get("dni", "")) or "")
        if not dev_id or not dni:
            # Response may not carry the ids -- look the device up by label.
            time.sleep(0.3)
            vdevs = self.client.call_tool("hub_list_devices", {"labelFilter": PREFIX})
            devices_list = vdevs if isinstance(vdevs, list) else (vdevs.get("devices", []) if isinstance(vdevs, dict) else [])
            for d in devices_list:
                if label in (d.get("label") or d.get("name") or ""):
                    dev_id = dev_id or str(d["id"])
                    dni = dni or str(d.get("deviceNetworkId", d.get("dni", "")) or "")
                    break
        assert dev_id and dni, f"failed to create swap fixture switch '{label}'"
        self.created_device_dnis.append(dni)
        return dev_id, dni

    def _swap_device_instance_ids(self) -> set[str]:
        """Ids of installed 'Swap Device' app instances (the transient instances the
        direct/swapDevice alias creates on every resolve). A single un-cursored
        hub_list_apps call returns the FULL flattened instance list (pagination only
        kicks in once a cursor is passed); includeHidden covers a pending
        (installed:false) transient instance, should the hub list it as hidden."""
        listing = self.client.call_tool("hub_read_apps_code", {
            "tool": "hub_list_apps", "args": {"filter": "builtin", "includeHidden": True},
        })
        return {
            str(a.get("id"))
            for a in (listing.get("apps") or [])
            if isinstance(a, dict)
            and "swap device" in f"{a.get('type') or ''} {a.get('name') or ''}".lower()
        }

    @test("device_swap")
    def test_call_device_swap_child_device_ineligibility(self) -> None:
        # WHY there is no happy-path swap scenario here: the hub's built-in Swap
        # Device app offers only FREE-STANDING devices in its pickers (and oldDev
        # additionally lists only devices referenced by at least one app) -- devices
        # owned as another app's child/component device appear in NEITHER list
        # (verified live on fw 2.5.0.143). Every device this suite can create goes
        # through hub_manage_virtual_device -> addChildDevice, i.e. is an MCP child
        # device and therefore permanently ineligible; free-standing fixtures cannot
        # be created through the MCP tool surface, and the suite must not touch
        # non-BAT devices. The full swap round-trip is therefore BAT/manual-only
        # (tests/BAT-v2.md T642, with hub-UI-created free-standing switches). This
        # scenario still exercises the whole chain end-to-end: the direct-alias
        # resolver (transient Swap Device instance creation), the wizard
        # configure/json fetch, the oldDev eligibility pre-check, the structured
        # error envelope, and the transient-instance cleanup.
        id_a, dni_a = self._create_swap_switch(f"{PREFIX}Swap_A")
        id_b, dni_b = self._create_swap_switch(f"{PREFIX}Swap_B")
        try:
            swap_instances_before = self._swap_device_instance_ids()

            result = self.client.call_tool("hub_manage_devices", {
                "tool": "hub_call_device_swap",
                "args": {"from_device_id": id_a, "to_device_id": id_b, "confirm": True},
            })
            assert result.get("success") is False, \
                f"swap of an MCP child device unexpectedly succeeded -- hub eligibility rules changed? {result}"
            blob = f"{result.get('error') or ''} {result.get('note') or ''}".lower()
            assert "does not offer" in blob or "child" in blob, \
                f"ineligibility failure does not name the eligibility rule: {result}"

            # Cleanup contract: the failed call must not leak its transient Swap
            # Device instance into the hub's Apps list. Compared as a before/after
            # id-set delta so pre-existing leaked instances (prior runs, manual UI
            # visits) cannot false-fail the run.
            leaked = self._swap_device_instance_ids() - swap_instances_before
            assert not leaked, \
                f"hub_call_device_swap leaked transient Swap Device instance(s): {sorted(leaked)}"

            print(f"    DEVICE_SWAP ok -- child-device fixture {id_a} refused as ineligible, no instance leak")
        finally:
            for dni in (dni_a, dni_b):
                try:
                    self.client.call_tool("hub_manage_virtual_device", {
                        "action": "delete", "deviceNetworkId": dni, "confirm": True,
                    })
                    if dni in self.created_device_dnis:
                        self.created_device_dnis.remove(dni)
                except Exception as exc:
                    print(f"  [WARN] device-swap cleanup: delete device DNI={dni} failed: {exc}")

    # -----------------------------------------------------------------------
    # GROUP 4h: hub_variables (1 test) -- hub-NAMESPACE variable lifecycle
    # (the system Hub Variables app, not the legacy rule_engine map).
    # -----------------------------------------------------------------------

    @test("hub_variables")
    def test_hub_variable_create_get_delete_round_trip(self) -> None:
        # create/delete drive the Hub Variables system app's wizard (its instance id
        # resolved via the direct-alias redirect); get reads back through getGlobalVar,
        # so a green round-trip proves the wizard writes landed in the real namespace.
        var_name = f"{PREFIX}HubVar_RT"
        # Track BEFORE creating: there is no prefix sweep for variables, so a crash
        # between the create landing and a later append would strand it.
        self.created_variable_names.append(var_name)
        created = self.client.call_tool("hub_manage_variables", {
            "tool": "hub_create_variable",
            "args": {"name": var_name, "type": "String", "value": "round-trip-v1", "confirm": True},
        })
        assert created.get("success") is True and created.get("source") == "hub", \
            f"hub_create_variable did not create in the hub namespace: {created}"

        got = self.client.call_tool("hub_manage_variables", {
            "tool": "hub_get_variable", "args": {"name": var_name},
        })
        assert got.get("source") == "hub", f"created variable not visible in the hub namespace: {got}"
        assert got.get("value") == "round-trip-v1", f"hub variable value mismatch: {got}"
        assert got.get("type"), f"hub variable read-back carries no type metadata: {got}"

        deleted = self.client.call_tool("hub_manage_variables", {
            "tool": "hub_delete_variable", "args": {"name": var_name, "confirm": True},
        })
        assert deleted.get("success") is True and deleted.get("deleted") is True, \
            f"hub_delete_variable failed: {deleted}"
        assert deleted.get("source") == "hub", f"delete resolved the wrong namespace: {deleted}"
        assert deleted.get("previousValue") == "round-trip-v1", \
            f"delete did not report the previous value: {deleted}"

        # Verify gone from BOTH namespaces (hub_get_variable searches hub first,
        # then falls back to rule_engine -- a not-found proves both are clean).
        try:
            self.client.call_tool("hub_manage_variables", {
                "tool": "hub_get_variable", "args": {"name": var_name},
            })
            raise AssertionError(f"{var_name} still retrievable after hub-namespace delete")
        except (McpToolError, McpError) as exc:
            assert "not found" in str(exc).lower(), f"unexpected error after delete: {exc}"
        if var_name in self.created_variable_names:
            self.created_variable_names.remove(var_name)

    # -----------------------------------------------------------------------
    # GROUP 5: trigger_types (1 batched test -- all trigger types in one rule)
    # -----------------------------------------------------------------------

    @test("trigger_types")
    def test_trigger_types(self) -> None:
        """Legacy custom engine: every trigger TYPE parses + lands. Batched into ONE rule (1 create +
        1 delete instead of 6 per-type rules) -- keeps per-type create coverage and the count assert
        catches a silently-dropped type, while cutting the fixture churn the legacy engine doesn't
        warrant exhaustively. New trigger types: add to this list, not a new rule."""
        dev_id = self.get_first_device_id()
        triggers = [
            _inject_device_id({"type": "device_event", "deviceId": "PLACEHOLDER", "attribute": "switch"}, dev_id),
            {"type": "time", "time": "08:00"},
            {"type": "periodic", "interval": 30, "unit": "minutes"},
            {"type": "mode_change", "mode": "Away"},
            {"type": "sunrise", "offset": 0},
            {"type": "sunset", "offset": -30},
        ]
        rule_id = self._create_rule_and_verify(f"{PREFIX}Trigger_Types", {
            "triggers": triggers,
            "actions": [{"type": "log", "message": "trigger types batch"}],
        })
        self._assert_rule_types(rule_id, "triggers", [t["type"] for t in triggers],
                                normalize_away=("sunrise", "sunset"))
        self._delete_rule_safe(rule_id)

    # -----------------------------------------------------------------------
    # GROUP 6: condition_types (1 batched test -- all condition types in one rule)
    # -----------------------------------------------------------------------

    @test("condition_types")
    def test_condition_types(self) -> None:
        """Legacy custom engine: every condition TYPE parses + lands. Batched into ONE rule (1 create +
        1 delete instead of 7). The variable condition needs a backing hub variable. New condition
        types: add to this list."""
        dev_id = self.get_first_device_id()
        var_name = f"{PREFIX}CondVar"
        self._create_variable(var_name, "String", "test")
        try:
            conditions = [
                _inject_device_id({"type": "device_state", "deviceId": "PLACEHOLDER", "attribute": "switch", "operator": "==", "value": "on"}, dev_id),
                _inject_device_id({"type": "device_was", "deviceId": "PLACEHOLDER", "attribute": "switch", "operator": "==", "value": "on", "forSeconds": 300}, dev_id),
                {"type": "time_range", "start": "08:00", "end": "22:00"},
                {"type": "mode", "mode": "Day"},
                {"type": "variable", "variableName": var_name, "operator": "==", "value": "1"},
                {"type": "days_of_week", "days": ["Monday", "Wednesday", "Friday"]},
                {"type": "sun_position", "position": "up"},
            ]
            rule_id = self._create_rule_and_verify(f"{PREFIX}Condition_Types", {
                "triggers": [{"type": "time", "time": "03:00"}],
                "conditions": conditions,
                "actions": [{"type": "log", "message": "condition types batch"}],
            })
            self._assert_rule_types(rule_id, "conditions", [c["type"] for c in conditions])
            self._delete_rule_safe(rule_id)
        finally:
            self._delete_variable_safe(var_name)

    # -----------------------------------------------------------------------
    # GROUP 7: action_types (1 batched test -- all action types in one rule)
    # -----------------------------------------------------------------------

    @test("action_types")
    def test_action_types(self) -> None:
        """Legacy custom engine: every action TYPE parses + lands. Batched into ONE rule (1 create +
        1 delete instead of 13). Covers device commands, variable/mode/delay, control flow
        (if_then_else, repeat, cancel_delayed, stop) and log/http/comment. 'stop' is placed LAST so it
        can't truncate the stored action list. set_variable needs a backing hub variable. New action
        types: add to this list."""
        dev_id = self.get_first_device_id()
        switch_id = self.get_test_switch_id()
        var_name = f"{PREFIX}ActVar"
        self._create_variable(var_name, "String", "initial")
        try:
            actions = [
                {"type": "device_command", "deviceId": switch_id, "command": "on"},
                {"type": "toggle_device", "deviceId": switch_id},
                {"type": "set_variable", "variableName": var_name, "value": "hello"},
                {"type": "set_local_variable", "variableName": "localTestVar", "value": "42"},
                {"type": "set_mode", "mode": "Day"},
                {"type": "delay", "seconds": 5},
                {"type": "cancel_delayed"},
                {"type": "if_then_else",
                 "condition": {"type": "device_state", "deviceId": dev_id, "attribute": "switch", "operator": "==", "value": "on"},
                 "thenActions": [{"type": "log", "message": "then branch"}],
                 "elseActions": [{"type": "log", "message": "else branch"}]},
                {"type": "repeat", "count": 3, "actions": [{"type": "log", "message": "repeat iteration"}]},
                {"type": "log", "message": "E2E test log action"},
                {"type": "http_request", "method": "GET", "url": "http://example.com"},
                {"type": "comment", "text": "This is a test comment"},
                {"type": "stop"},
            ]
            rule_id = self._create_rule_and_verify(f"{PREFIX}Action_Types", {
                "triggers": [{"type": "time", "time": "03:00"}],
                "actions": actions,
            })
            self._assert_rule_types(rule_id, "actions", [a["type"] for a in actions])
            self._delete_rule_safe(rule_id)
        finally:
            self._delete_variable_safe(var_name)

    # -----------------------------------------------------------------------
    # GROUP 8: complex_patterns (2 tests)
    # -----------------------------------------------------------------------

    @test("complex_patterns")
    def test_nested_if_then_else(self) -> None:
        dev_id = self.get_first_device_id()
        rule_id = self._create_rule_and_verify(f"{PREFIX}Nested_ITE", {
            "triggers": [{"type": "time", "time": "03:00"}],
            "actions": [{
                "type": "if_then_else",
                "condition": {
                    "type": "device_state", "deviceId": dev_id,
                    "attribute": "switch", "operator": "==", "value": "on",
                },
                "thenActions": [{
                    "type": "if_then_else",
                    "condition": {
                        "type": "mode", "mode": "Day",
                    },
                    "thenActions": [{"type": "log", "message": "nested then"}],
                    "elseActions": [{"type": "log", "message": "nested else"}],
                }],
                "elseActions": [{"type": "log", "message": "outer else"}],
            }],
        })
        self._delete_rule_safe(rule_id)

    @test("complex_patterns")
    def test_and_or_conditions(self) -> None:
        dev_id = self.get_first_device_id()
        rule_id = self._create_rule_and_verify(f"{PREFIX}OR_Conditions", {
            "triggers": [{"type": "time", "time": "03:00"}],
            "conditions": [
                {"type": "device_state", "deviceId": dev_id,
                 "attribute": "switch", "operator": "==", "value": "on"},
                {"type": "mode", "mode": "Day"},
            ],
            "conditionLogic": "OR",
            "actions": [{"type": "log", "message": "OR condition test"}],
        })
        self._delete_rule_safe(rule_id)

    # -----------------------------------------------------------------------
    # GROUP 9: system_tools (5 tests)
    # -----------------------------------------------------------------------

    @test("system_tools")
    def test_get_modes(self) -> None:
        result = self.client.call_tool("hub_list_modes")
        # Should have modes list and currentMode
        assert result is not None, "hub_list_modes returned None"
        # Accept various response shapes
        has_modes = ("modes" in result if isinstance(result, dict) else isinstance(result, list))
        assert has_modes or "currentMode" in result, \
            f"hub_list_modes response missing modes/currentMode: {list(result.keys()) if isinstance(result, dict) else type(result)}"

    @test("system_tools")
    def test_manage_hub_variables_list(self) -> None:
        result = self.client.call_tool("hub_manage_variables", {
            "tool": "hub_list_variables",
        })
        # Should return a list or dict with variables
        assert result is not None, "hub_list_variables returned None"

    @test("system_tools")
    def test_get_hub_info(self) -> None:
        result = self.client.call_tool("hub_get_info")
        assert result is not None, "hub_get_info returned None"
        assert isinstance(result, dict), f"hub_get_info returned {type(result)}"
        # issue #209 load-bearing #include proof: the deployed app `#include mcp.McpSmokeTestLib`,
        # so mcpSmokeTestMarker() must be callable and folded into the info output. By the time this
        # test runs, the watchdog PR-install step has re-delivered McpSmokeTestLib via the package
        # bundle .zip and resaved the app, so this marker rides on the BUNDLE-delivered library. If the
        # include had not resolved on the hub, the app would not have compiled or this field would be
        # missing -- either way this assertion catches a broken library load.
        # Removed together with the smoke test once the modularization split is validated.
        assert result.get("smokeTestMarker") == "smoke-ok-v1", (
            "hub_get_info.smokeTestMarker missing/wrong -- the #include of McpSmokeTestLib did not "
            f"resolve on the hub (got {result.get('smokeTestMarker')!r})"
        )

    @test("system_tools")
    def test_list_libraries(self) -> None:
        result = self.client.call_tool("hub_list_libraries")
        libs = result if isinstance(result, list) else result.get("libraries", [])
        assert isinstance(libs, list), "hub_list_libraries did not return a list"
        source = result.get("source") if isinstance(result, dict) else None
        # The library-PRESENCE assertions below require the hub's library API to return its
        # populated JSON-array shape (source == "hub_api"). The degraded shapes
        # (hub_api_raw / unavailable) return an empty list, so requiring hub_api here turns a
        # genuinely-unreadable library API into a clear failure instead of a misleading
        # "McpRoomsLib not found (got [])". level99's hub returns the array today.
        assert source == "hub_api", (
            f"hub_list_libraries did not return the populated hub API shape (source={source!r}); "
            "cannot validate bundle-delivered libraries"
        )
        for lib in libs:
            assert "id" in lib and "name" in lib, "library summary missing id/name"
            assert "source" not in lib, "hub_list_libraries should omit source (read it via hub_get_source)"
        # issue #209: the watchdog PR-install step delivers the package's libraries (mcp
        # namespace) into Libraries Code via the bundle .zip (the #include's library leg), so they must
        # be present here. Proves the libraries were actually added to Libraries Code on the hub (not
        # just that the app compiled).
        lib_names = [lib.get("name") for lib in libs]
        # McpRoomsLib is the first REAL extracted module (hub_*_room impls) -- permanent.
        assert any(
            lib.get("name") == "McpRoomsLib" and lib.get("namespace") == "mcp" for lib in libs
        ), f"McpRoomsLib not found in hub libraries (got {lib_names})"
        # McpSmokeTestLib is the throwaway #209 canary -- removed once the split is validated.
        assert any(
            lib.get("name") == "McpSmokeTestLib" and lib.get("namespace") == "mcp" for lib in libs
        ), f"McpSmokeTestLib not found in hub libraries (got {lib_names})"

    @test("system_tools")
    def test_manage_diagnostics(self) -> None:
        result = self.client.call_tool("hub_manage_diagnostics", {
            "tool": "hub_get_metrics",
        })
        assert result is not None, "hub_get_metrics returned None"

    @test("system_tools")
    def test_hub_get_info_platform_update_and_safemode(self) -> None:
        # #12/#13: hub_get_info folds in the pending platform/firmware update + safeMode from
        # /hub2/hubData. On a reachable hub the endpoint is readable, so these resolve (not the
        # null degrade path); the full alerts block stays out unless opted in.
        info = self.client.call_tool("hub_get_info", {})
        assert "platformUpdate" in info, f"hub_get_info missing platformUpdate: {sorted(info)}"
        pu = info["platformUpdate"]
        assert "currentVersion" in pu, f"platformUpdate missing currentVersion: {pu}"
        assert isinstance(pu.get("available"), bool), \
            f"platformUpdate.available not resolved -- /hub2/hubData unreadable? {pu}"
        if pu["available"]:
            assert pu.get("availableVersion"), f"available=true but no availableVersion: {pu}"
        assert "safeMode" in info, f"hub_get_info missing safeMode: {sorted(info)}"
        assert "healthAlerts" not in info, "healthAlerts must be absent without includeHealthAlerts=true"

    @test("system_tools")
    def test_hub_get_info_health_alerts_opt_in(self) -> None:
        # #13: the full alerts block appears only with includeHealthAlerts=true.
        info = self.client.call_tool("hub_get_info", {"includeHealthAlerts": True})
        ha = info.get("healthAlerts")
        assert ha is not None, f"includeHealthAlerts=true but healthAlerts absent/null: {sorted(info)}"
        for k in ("safeMode", "active", "details"):
            assert k in ha, f"healthAlerts missing '{k}': {ha}"
        assert isinstance(ha["active"], list), f"healthAlerts.active not a list: {ha['active']}"
        assert isinstance(ha["details"], dict), f"healthAlerts.details not a dict: {ha['details']}"
        # platform-update fields are surfaced via platformUpdate, not duplicated in the alert details
        assert "platformUpdateAvailable" not in ha["details"], \
            f"platformUpdate leaked into healthAlerts.details: {sorted(ha['details'])}"

    @test("system_tools")
    def test_hub_get_update_status_platform_update(self) -> None:
        # #12: hub_get_update_status surfaces the hub firmware update separately from the MCP-app check.
        res = self.client.call_tool("hub_get_update_status", {})
        assert "installedVersion" in res, f"missing MCP-app installedVersion: {sorted(res)}"
        assert "platformUpdate" in res, f"missing platformUpdate: {sorted(res)}"
        assert "available" in res["platformUpdate"], f"platformUpdate shape wrong: {res['platformUpdate']}"

    @test("system_tools")
    def test_hub_get_metrics_health_alerts(self) -> None:
        # #13: hub_get_metrics folds in the full healthAlerts block alongside the trend metrics.
        res = self.client.call_tool("hub_manage_diagnostics", {"tool": "hub_get_metrics"})
        assert "healthAlerts" in res, f"hub_get_metrics missing healthAlerts: {sorted(res)}"
        ha = res["healthAlerts"]
        assert ha is not None and "active" in ha and "safeMode" in ha, f"healthAlerts shape wrong: {ha}"

    @test("system_tools")
    def test_get_memory_history(self) -> None:
        result = self.client.call_tool("hub_manage_diagnostics", {
            "tool": "hub_get_memory_history",
        })
        assert isinstance(result, dict), f"hub_get_memory_history returned {type(result)}"
        assert "entries" in result, "hub_get_memory_history missing 'entries'"
        assert "summary" in result, "hub_get_memory_history missing 'summary'"
        entries = result["entries"]
        assert isinstance(entries, list), "entries should be a list"
        if entries:
            first = entries[0]
            assert "timestamp" in first, "Entry missing 'timestamp'"
            assert "freeMemoryKB" in first, "Entry missing 'freeMemoryKB'"
            assert "cpuLoad5min" in first, "Entry missing 'cpuLoad5min'"
        summary = result["summary"]
        assert "totalEntries" in summary, "Summary missing 'totalEntries'"

    @test("system_tools")
    def test_force_garbage_collection(self) -> None:
        result = self.client.call_tool("hub_manage_diagnostics", {
            "tool": "hub_call_gc",
        })
        assert isinstance(result, dict), f"hub_call_gc returned {type(result)}"
        assert "beforeFreeMemoryKB" in result, "Missing 'beforeFreeMemoryKB'"
        assert "afterFreeMemoryKB" in result, "Missing 'afterFreeMemoryKB'"
        assert "summary" in result, "Missing 'summary'"
        # deltaKB should exist if both memory reads succeeded
        if result["beforeFreeMemoryKB"] is not None and result["afterFreeMemoryKB"] is not None:
            assert "deltaKB" in result, "Missing 'deltaKB'"

    @test("system_tools")
    def test_get_memory_history_with_limit(self) -> None:
        """Verify limit parameter works (v0.9.0 fix for response-too-large)."""
        result = self.client.call_tool("hub_manage_diagnostics", {
            "tool": "hub_get_memory_history",
            "args": {"limit": 5},
        })
        assert isinstance(result, dict), f"hub_get_memory_history returned {type(result)}"
        assert "entries" in result, "Missing 'entries'"
        entries = result["entries"]
        assert len(entries) <= 5, f"limit=5 but got {len(entries)} entries"
        summary = result["summary"]
        assert "totalEntries" in summary, "Summary missing 'totalEntries'"

    @test("system_tools")
    def test_get_performance_stats_device(self) -> None:
        """Test hub_get_performance_stats with type=device (default)."""
        result = self.client.call_tool("hub_manage_logs", {
            "tool": "hub_get_performance_stats",
        })
        assert isinstance(result, dict), f"hub_get_performance_stats returned {type(result)}"
        assert "uptime" in result, "Missing 'uptime'"
        assert "deviceSummary" in result, "Missing 'deviceSummary'"
        assert "deviceStats" in result, "Missing 'deviceStats'"
        assert isinstance(result["deviceStats"], list), "deviceStats should be a list"
        ds = result["deviceSummary"]
        assert "deviceCount" in ds, "deviceSummary missing 'deviceCount'"
        # Default limit is 20
        assert len(result["deviceStats"]) <= 20, \
            f"Default limit is 20 but got {len(result['deviceStats'])} entries"
        if result["deviceStats"]:
            entry = result["deviceStats"][0]
            assert "name" in entry, "Stats entry missing 'name'"
            assert "count" in entry, "Stats entry missing 'count'"

    @test("system_tools")
    def test_get_performance_stats_app(self) -> None:
        """Test hub_get_performance_stats with type=app."""
        result = self.client.call_tool("hub_manage_logs", {
            "tool": "hub_get_performance_stats",
            "args": {"type": "app", "limit": 5},
        })
        assert isinstance(result, dict), f"hub_get_performance_stats returned {type(result)}"
        assert "appSummary" in result, "Missing 'appSummary'"
        assert "appStats" in result, "Missing 'appStats'"
        assert isinstance(result["appStats"], list), "appStats should be a list"
        assert len(result["appStats"]) <= 5, \
            f"limit=5 but got {len(result['appStats'])} entries"
        # Should NOT have device stats when type=app
        assert "deviceStats" not in result, "type=app should not include deviceStats"

    @test("system_tools")
    def test_get_performance_stats_both(self) -> None:
        """Test hub_get_performance_stats with type=both."""
        result = self.client.call_tool("hub_manage_logs", {
            "tool": "hub_get_performance_stats",
            "args": {"type": "both", "limit": 3},
        })
        assert isinstance(result, dict), f"hub_get_performance_stats returned {type(result)}"
        assert "deviceStats" in result, "type=both missing 'deviceStats'"
        assert "appStats" in result, "type=both missing 'appStats'"

    @test("system_tools")
    def test_get_hub_jobs(self) -> None:
        """Test hub_get_jobs returns scheduled jobs and hub actions."""
        result = self.client.call_tool("hub_manage_logs", {
            "tool": "hub_get_jobs",
        })
        assert isinstance(result, dict), f"hub_get_jobs returned {type(result)}"
        assert "uptime" in result, "Missing 'uptime'"
        assert "scheduledJobs" in result, "Missing 'scheduledJobs'"
        assert "runningJobs" in result, "Missing 'runningJobs'"
        assert "hubActions" in result, "Missing 'hubActions'"
        sj = result["scheduledJobs"]
        assert "count" in sj, "scheduledJobs missing 'count'"
        assert "jobs" in sj, "scheduledJobs missing 'jobs'"
        assert isinstance(sj["jobs"], list), "scheduledJobs.jobs should be a list"
        if sj["jobs"]:
            job = sj["jobs"][0]
            assert "name" in job, "Job missing 'name'"

    @test("system_tools")
    def test_manage_rooms_list(self) -> None:
        result = self.client.call_tool("hub_manage_rooms", {
            "tool": "hub_list_rooms",
        })
        # May be empty list, but should not error
        assert result is not None, "hub_list_rooms returned None"

    @test("system_tools")
    def test_manage_rooms_create_get_rename_delete(self) -> None:
        """Issue #209: validate the FULL McpRoomsLib-backed Rooms flow live.

        Room create/get/update/delete impls now live in the McpRoomsLib #include
        library; this exercises all five room tools (create, get, update/rename, list,
        delete) against the real hub through the deployed app, including the
        device-assignment path (create WITH a device -> hub_get_room renders it ->
        hub_delete_room unassigns it). The Spock suite covers the logic in isolation;
        this proves the extracted library actually runs end-to-end. Self-cleaning;
        cleanup()'s room sweep reclaims a strand if this crashes mid-way.
        """
        dev_id = self.get_first_device_id()
        name = f"{PREFIX}RoomLib"
        renamed = f"{PREFIX}RoomLib2"
        room_id = None
        try:
            # Self-heal: a doubly-crashed prior run could leave BAT_E2E_RoomLib/RoomLib2 behind,
            # which would make hub_create_room/hub_update_room fail on the duplicate-name guard.
            # Delete any pre-existing same-named rooms first (mirrors get_test_switch_id reuse).
            pre = self.client.call_tool("hub_manage_rooms", {"tool": "hub_list_rooms"})
            for r in (pre.get("rooms", []) if isinstance(pre, dict) else []):
                if r.get("name") in (name, renamed):
                    try:
                        self.client.call_tool("hub_manage_rooms", {
                            "tool": "hub_delete_room",
                            "args": {"room": str(r.get("id")), "confirm": True},
                        })
                    except Exception as exc:
                        print(f"  [WARN] rooms flow pre-sweep: delete {r.get('id')} failed: {exc}")

            # hub_create_room WITH a device assigned at creation.
            created = self.client.call_tool("hub_manage_rooms", {
                "tool": "hub_create_room",
                "args": {"name": name, "deviceIds": [dev_id], "confirm": True},
            })
            assert created.get("success") is True, f"hub_create_room did not succeed: {created}"
            room = created.get("room") or {}
            room_id = str(room.get("id") or "")
            assert room_id, f"hub_create_room returned no room id: {created}"
            assert room.get("deviceCount") == 1, \
                f"hub_create_room did not assign the device at creation (deviceCount={room.get('deviceCount')!r}): {created}"

            # hub_get_room (singular read): must return the room WITH the assigned device.
            got = self.client.call_tool("hub_manage_rooms", {
                "tool": "hub_get_room",
                "args": {"room": room_id},
            })
            assert str(got.get("id")) == room_id, f"hub_get_room returned wrong room: {got}"
            assert got.get("name") == name, f"hub_get_room name mismatch: {got}"
            got_devices = got.get("devices") or []
            assert any(str(d.get("id")) == dev_id for d in got_devices), \
                f"hub_get_room did not list the assigned device {dev_id}: {got}"

            # hub_get_room also resolves by NAME (not just id).
            got_by_name = self.client.call_tool("hub_manage_rooms", {
                "tool": "hub_get_room",
                "args": {"room": name},
            })
            assert str(got_by_name.get("id")) == room_id, \
                f"hub_get_room by name did not resolve to the same room: {got_by_name}"

            # hub_update_room (rename).
            renamed_res = self.client.call_tool("hub_manage_rooms", {
                "tool": "hub_update_room",
                "args": {"room": room_id, "newName": renamed, "confirm": True},
            })
            assert renamed_res.get("success") is True, f"hub_update_room did not succeed: {renamed_res}"
            assert (renamed_res.get("room") or {}).get("name") == renamed, \
                f"hub_update_room did not apply the new name: {renamed_res}"

            # hub_list_rooms must reflect the rename.
            listed = self.client.call_tool("hub_manage_rooms", {"tool": "hub_list_rooms"})
            rooms = listed.get("rooms", []) if isinstance(listed, dict) else []
            assert any(str(r.get("id")) == room_id and r.get("name") == renamed for r in rooms), \
                f"renamed room {room_id} not found as '{renamed}' in hub_list_rooms: {rooms}"

            # hub_delete_room (unassigns the device, does NOT delete the device).
            deleted = self.client.call_tool("hub_manage_rooms", {
                "tool": "hub_delete_room",
                "args": {"room": room_id, "confirm": True},
            })
            assert deleted.get("success") is True, f"hub_delete_room did not succeed: {deleted}"
            assert deleted.get("devicesUnassigned") == 1, \
                f"hub_delete_room did not report the device unassigned: {deleted}"
            room_id = None  # deleted cleanly; skip the finally sweep
            print(f"    ROOMS_LIB_FLOW create+get+rename+delete OK ({renamed}, dev {dev_id})")
        finally:
            if room_id:
                try:
                    self.client.call_tool("hub_manage_rooms", {
                        "tool": "hub_delete_room",
                        "args": {"room": room_id, "confirm": True},
                    })
                except Exception as exc:
                    print(f"  [WARN] rooms flow cleanup: delete {room_id} failed: {exc}")

    @test("system_tools")
    def test_manage_rooms_error_contracts(self) -> None:
        """Issue #209: validate the McpRoomsLib tools' error/validation contracts live.

        The round-trip proves the happy paths; this proves the failure modes traverse
        the MCP transport correctly on a real hub: not-found lookup, the duplicate-name
        and rename-collision guards, and the confirm safety gate on the destructive room
        writes (create + delete). These are unit-covered in ToolRoomsSpec; here we confirm
        the same contracts end-to-end. Self-cleaning (finally + Layer-6 sweep).
        """
        name_a = f"{PREFIX}RoomErrA"
        name_b = f"{PREFIX}RoomErrB"
        ghost = f"{PREFIX}RoomGhostNope"
        created_ids = []
        try:
            # Pre-sweep same-named strands from a doubly-crashed prior run.
            pre = self.client.call_tool("hub_manage_rooms", {"tool": "hub_list_rooms"})
            for r in (pre.get("rooms", []) if isinstance(pre, dict) else []):
                if r.get("name") in (name_a, name_b):
                    try:
                        self.client.call_tool("hub_manage_rooms", {
                            "tool": "hub_delete_room",
                            "args": {"room": str(r.get("id")), "confirm": True},
                        })
                    except Exception:
                        pass

            # 1) hub_get_room on a non-existent room -> -32602 (McpError).
            try:
                self.client.call_tool("hub_manage_rooms", {
                    "tool": "hub_get_room", "args": {"room": ghost},
                })
                assert False, "hub_get_room on a non-existent room should have raised"
            except McpError as e:
                # Both are valid "the room isn't there" errors: "not found" when other rooms
                # exist, "no rooms configured" when the hub has none (the e2e hub often has zero).
                msg = str(e).lower()
                assert "not found" in msg or "no rooms configured" in msg, (
                    f"hub_get_room error was not a not-found: {e}"
                )

            # 2) confirm safety gate: hub_create_room without confirm -> refused, no room created.
            # `confirm` is a REQUIRED schema param (the convention for every destructive tool), so a
            # missing confirm is refused by the dispatch's required-param check. That surfaces as an
            # isError envelope ("Missing required parameter: confirm") whose isError lives in the
            # content, so call_tool RETURNS it as the parsed dict rather than raising; a raised
            # McpError/-32602 is also acceptable. The load-bearing guarantee is refusal + no room.
            refused = False
            detail = None
            try:
                detail = self.client.call_tool("hub_manage_rooms", {
                    "tool": "hub_create_room", "args": {"name": name_a},
                })
                blob = (detail if isinstance(detail, str) else json.dumps(detail)).lower()
                refused = (isinstance(detail, dict) and bool(detail.get("isError"))) \
                    or "confirm" in blob or "required parameter" in blob
            except McpError as e:  # also catches McpToolError (subclass): a raised envelope / -32602
                detail = str(e)
                refused = any(s in detail.lower() for s in ("confirm", "safety check", "required parameter"))
            assert refused, \
                f"hub_create_room without confirm should have been refused by the safety gate, got: {detail}"
            after = self.client.call_tool("hub_manage_rooms", {"tool": "hub_list_rooms"})
            assert not any(
                r.get("name") == name_a
                for r in (after.get("rooms", []) if isinstance(after, dict) else [])
            ), "hub_create_room without confirm must NOT create the room"

            # 3) duplicate-name guard: create roomA, then a second create with the same name -> refused.
            created = self.client.call_tool("hub_manage_rooms", {
                "tool": "hub_create_room", "args": {"name": name_a, "confirm": True},
            })
            assert created.get("success") is True, f"setup create roomA failed: {created}"
            id_a = str((created.get("room") or {}).get("id") or "")
            assert id_a, f"setup create roomA returned no id: {created}"
            created_ids.append(id_a)
            try:
                self.client.call_tool("hub_manage_rooms", {
                    "tool": "hub_create_room", "args": {"name": name_a, "confirm": True},
                })
                assert False, "duplicate hub_create_room should have raised"
            except McpError as e:
                assert "already exists" in str(e).lower(), f"duplicate-create error unexpected: {e}"

            # 4) rename-collision guard: create roomB, rename it to roomA's name -> refused.
            created_b = self.client.call_tool("hub_manage_rooms", {
                "tool": "hub_create_room", "args": {"name": name_b, "confirm": True},
            })
            assert created_b.get("success") is True, f"setup create roomB failed: {created_b}"
            id_b = str((created_b.get("room") or {}).get("id") or "")
            assert id_b, f"setup create roomB returned no id: {created_b}"
            created_ids.append(id_b)
            try:
                self.client.call_tool("hub_manage_rooms", {
                    "tool": "hub_update_room",
                    "args": {"room": id_b, "newName": name_a, "confirm": True},
                })
                assert False, "renaming roomB to roomA's name should have raised"
            except McpError as e:
                assert "already exists" in str(e).lower(), f"collision-rename error unexpected: {e}"

            # 5) confirm safety gate on delete: hub_delete_room without confirm -> refused, room survives.
            # Same refusal shape as the create gate above: a missing REQUIRED confirm comes back as an
            # isError envelope (returned by call_tool) or a raise -- accept either; room must survive.
            del_refused = False
            del_detail = None
            try:
                del_detail = self.client.call_tool("hub_manage_rooms", {
                    "tool": "hub_delete_room", "args": {"room": id_a},
                })
                blob = (del_detail if isinstance(del_detail, str) else json.dumps(del_detail)).lower()
                del_refused = (isinstance(del_detail, dict) and bool(del_detail.get("isError"))) \
                    or "confirm" in blob or "required parameter" in blob
            except McpError as e:
                del_detail = str(e)
                del_refused = any(s in del_detail.lower() for s in ("confirm", "safety check", "required parameter"))
            assert del_refused, \
                f"hub_delete_room without confirm should have been refused by the safety gate, got: {del_detail}"
            still = self.client.call_tool("hub_manage_rooms", {"tool": "hub_list_rooms"})
            assert any(
                str(r.get("id")) == id_a
                for r in (still.get("rooms", []) if isinstance(still, dict) else [])
            ), "roomA must survive a no-confirm delete attempt"

            print("    ROOMS_LIB_ERRORS not-found + confirm-gate + duplicate + collision OK")
        finally:
            for rid in created_ids:
                try:
                    self.client.call_tool("hub_manage_rooms", {
                        "tool": "hub_delete_room",
                        "args": {"room": rid, "confirm": True},
                    })
                except Exception as exc:
                    print(f"  [WARN] rooms error-contract cleanup: delete {rid} failed: {exc}")

    # -----------------------------------------------------------------------
    # Bundle tools (issue #209): McpBundlesLib-backed hub_list_bundles /
    # hub_export_bundle / hub_delete_bundle. These prove that, after the
    # modularization, the libraries actually load as a bundle on the real hub
    # and the new tools work end-to-end. They ride on the watchdog PR-install step
    # (the "Watchdog - install PR" job in hub-e2e.yml) that delivers the
    # mcp-libraries bundle before tests run.
    # -----------------------------------------------------------------------

    @test("system_tools")
    def test_list_bundles(self) -> None:
        """hub_list_bundles lists installed bundles, and the package's libraries bundle (delivered
        by the watchdog PR-install step) is present with its libraries -- proof the split libraries
        load as a bundle on the real hub."""
        result = self.client.call_tool("hub_read_apps_code", {"tool": "hub_list_bundles"})
        assert result.get("source") == "hub_api", \
            f"hub_list_bundles did not return the populated hub API shape (source={result.get('source')!r})"
        bundles = result.get("bundles", []) if isinstance(result, dict) else []
        mcp_bundle = next(
            (b for b in bundles if b.get("namespace") == "mcp"
             and "McpRoomsLib" in ((b.get("contains") or {}).get("libraries") or [])),
            None,
        )
        assert mcp_bundle and mcp_bundle.get("id"), \
            f"the mcp libraries bundle (containing McpRoomsLib) was not found: {[b.get('name') for b in bundles]}"
        print(f"    BUNDLES_LIST ok -- '{mcp_bundle.get('name')}' contains {(mcp_bundle.get('contains') or {}).get('libraries')}")

    @test("system_tools")
    def test_export_bundle(self) -> None:
        """hub_export_bundle saves a bundle's .zip to the File Manager (independently confirmed via
        hub_list_files). Self-cleaning."""
        listed = self.client.call_tool("hub_read_apps_code", {"tool": "hub_list_bundles"})
        bundles = listed.get("bundles", []) if isinstance(listed, dict) else []
        target = next(
            (b for b in bundles if b.get("namespace") == "mcp"
             and "McpRoomsLib" in ((b.get("contains") or {}).get("libraries") or [])),
            None,
        )
        assert target and target.get("id"), "no mcp libraries bundle available to export"
        bid = str(target["id"])
        fname = f"{PREFIX}bundle_export_{bid}.zip"
        try:
            result = self.client.call_tool("hub_manage_code", {
                "tool": "hub_export_bundle",
                "args": {"bundleId": bid, "saveAs": fname},
            })
            assert result.get("success") is True, f"hub_export_bundle did not succeed: {result}"
            assert (result.get("bytes") or 0) > 0, f"hub_export_bundle saved 0 bytes: {result}"
            assert result.get("fileName") == fname, f"hub_export_bundle filename mismatch: {result}"
            files = self.client.call_tool("hub_read_files", {"tool": "hub_list_files"})
            names = [f.get("name") for f in (files.get("files", []) if isinstance(files, dict) else [])]
            assert fname in names, f"exported bundle file {fname} not found in File Manager: {names}"
            print(f"    BUNDLE_EXPORT ok -- {fname} ({result.get('bytes')} B)")
        finally:
            # hub_delete_file auto-backs-up a normal file before deleting it, so deleting the export
            # leaves "{base}_backup_<ts>.zip" behind. Delete the export, THEN sweep the backup(s) it
            # spawned (their names carry "_backup_", so deleting them makes no further backup-of-backup).
            try:
                self.client.call_tool("hub_manage_files", {
                    "tool": "hub_delete_file", "args": {"fileName": fname, "confirm": True},
                })
            except Exception as exc:
                print(f"  [WARN] bundle export cleanup: delete {fname} failed: {exc}")
            try:
                files = self.client.call_tool("hub_read_files", {"tool": "hub_list_files"})
                for nm in [f.get("name") for f in (files.get("files", []) if isinstance(files, dict) else [])]:
                    if isinstance(nm, str) and nm.startswith(f"{PREFIX}bundle_export_") and "_backup_" in nm:
                        self.client.call_tool("hub_manage_files", {
                            "tool": "hub_delete_file", "args": {"fileName": nm, "confirm": True},
                        })
            except Exception as exc:
                print(f"  [WARN] bundle export backup sweep failed: {exc}")

    @test("system_tools")
    def test_delete_bundle(self) -> None:
        """hub_delete_bundle removes a bundle, verified by re-list. Uses a self-contained throwaway
        bundle (mcptest namespace, fetched from the PR head) so it NEVER touches the live mcp
        libraries bundle. Skipped on local runs where the PR raw URL env isn't set."""
        raw_base = os.environ.get("PR_RAW_BASE")
        sha = os.environ.get("PR_HEAD_SHA_RESOLVED")
        if not (raw_base and sha):
            print("    SKIP test_delete_bundle: PR_RAW_BASE/PR_HEAD_SHA_RESOLVED not set (local run)")
            return
        url = f"{raw_base}/{sha}/tests/fixtures/mcp-e2e-throwaway-bundle.zip"
        bid = None
        try:
            installed = self.client.call_tool("hub_manage_code", {
                "tool": "hub_install_bundle", "args": {"importUrl": url, "confirm": True},
            })
            assert installed.get("success") is True, f"throwaway bundle install failed: {installed}"
            listed = self.client.call_tool("hub_read_apps_code", {"tool": "hub_list_bundles"})
            bundles = listed.get("bundles", []) if isinstance(listed, dict) else []
            tw = next((b for b in bundles if b.get("namespace") == "mcptest"), None)
            assert tw and tw.get("id"), \
                f"throwaway bundle not listed after install: {[b.get('name') for b in bundles]}"
            bid = str(tw["id"])
            deleted = self.client.call_tool("hub_manage_code", {
                "tool": "hub_delete_bundle", "args": {"bundleId": bid, "confirm": True},
            })
            assert deleted.get("success") is True, f"hub_delete_bundle did not succeed: {deleted}"
            assert deleted.get("verified") is True, f"hub_delete_bundle did not verify the id gone: {deleted}"
            relisted = self.client.call_tool("hub_read_apps_code", {"tool": "hub_list_bundles"})
            rb = relisted.get("bundles", []) if isinstance(relisted, dict) else []
            assert not any(b.get("namespace") == "mcptest" for b in rb), \
                "throwaway bundle still present after hub_delete_bundle"
            bid = None
            print("    BUNDLE_DELETE ok -- throwaway installed, listed, deleted, verified gone")
        finally:
            if bid:
                try:
                    self.client.call_tool("hub_manage_code", {
                        "tool": "hub_delete_bundle", "args": {"bundleId": bid, "confirm": True},
                    })
                except Exception as exc:
                    print(f"  [WARN] throwaway bundle cleanup: delete {bid} failed: {exc}")
            # Deleting the bundle removes only the container; Hubitat does NOT cascade-delete the library
            # it delivered (mcptest.E2eThrowawayLib), so remove that too or it pollutes Libraries Code
            # across runs. The disarm no-stale gate is scoped to the 'mcp' namespace and won't touch this.
            try:
                libs = self.client.call_tool("hub_read_apps_code", {"tool": "hub_list_libraries"})
                for lib in (libs.get("libraries", []) if isinstance(libs, dict) else []):
                    if lib.get("namespace") == "mcptest" and lib.get("id"):
                        self.client.call_tool("hub_manage_code", {
                            "tool": "hub_delete_item",
                            "args": {"type": "library", "id": str(lib["id"]), "confirm": True},
                        })
            except Exception as exc:
                print(f"  [WARN] throwaway library cleanup failed: {exc}")

    # -----------------------------------------------------------------------
    # GROUP 10: developer_mode (11 tests — Section 12 of BAT-v2.md + review-fix coverage + #250 dry-run)
    # -----------------------------------------------------------------------
    # Preconditions (provided by .github/scripts/mcp_setup_env.sh in CI, or
    # set manually for local runs):
    #   - enableDeveloperMode: true   (UI-only to enable; lockout protection)
    #   - enableWrite: true (default ON; only an explicit false disables writes)
    #   - lastBackupTimestamp within 24h
    #   - enableCustomRuleEngine, enableRead: true (enableRead default ON)
    #
    # T219 (toggle-OFF refusal) is omitted — would require briefly disabling
    # Developer Mode via UI, which CI can't do (toggle excluded from
    # hub_update_mcp_settings allowlist by design). Covered by ToolUpdateMcpSettingsSpec
    # at the unit level + manual BAT.

    @test("developer_mode")
    def test_t220_update_mcp_settings_boolean_flip(self) -> None:
        """T220: hub_update_mcp_settings flips a boolean setting end-to-end."""
        # debugLogging isn't surfaced in hub_get_info; just round-trip through
        # hub_update_mcp_settings — true → false → true and assert success each time.
        for value in (True, False, True):
            result = self.client.call_tool("hub_manage_mcp", {
                "tool": "hub_update_mcp_settings",
                "args": {"settings": {"debugLogging": value}, "confirm": True},
            })
            assert result.get("success") is True, f"flip to {value} did not succeed: {result}"
            assert result.get("updated") == {"debugLogging": value}, f"updated field mismatch for {value}: {result}"
            assert "Updated 1 setting" in (result.get("message") or ""), f"message missing 'Updated 1 setting': {result}"

    @test("developer_mode")
    def test_t221_update_mcp_settings_allowlist_rejection(self) -> None:
        """T221: rejects setting outside the allowlist (enableWrite is excluded -- footgun)."""
        try:
            self.client.call_tool("hub_manage_mcp", {
                "tool": "hub_update_mcp_settings",
                "args": {"settings": {"enableWrite": False}, "confirm": True},
            })
            assert False, "Expected -32602 rejection for enableWrite (footgun)"
        except McpError as e:
            msg = str(e)
            assert "enableWrite" in msg, f"error didn't mention the rejected key: {msg}"
            assert "not allowed" in msg, f"error didn't say 'not allowed': {msg}"
            # Should list allowed keys for caller to correct
            assert "Allowed:" in msg or "mcpLogLevel" in msg, f"error didn't list allowed keys: {msg}"

    @test("developer_mode")
    def test_t222_atomic_batch_one_bad_key_blocks_all(self) -> None:
        """T222: a single bad key in a multi-key batch rejects the whole batch (no partial writes)."""
        # Capture pre-state: read debugLogging via hub_update_mcp_settings round-trip
        # is not feasible from this side, so assert via behavior — flip debugLogging
        # to a known value first (true), then attempt mixed-batch with a bad key,
        # then verify debugLogging is STILL true (i.e., the OTHER keys did not flip).
        self.client.call_tool("hub_manage_mcp", {
            "tool": "hub_update_mcp_settings",
            "args": {"settings": {"debugLogging": True}, "confirm": True},
        })
        # Mixed batch: one valid (debugLogging=false), one invalid (enableWrite).
        try:
            self.client.call_tool("hub_manage_mcp", {
                "tool": "hub_update_mcp_settings",
                "args": {
                    "settings": {"debugLogging": False, "enableWrite": False},
                    "confirm": True,
                },
            })
            assert False, "Expected mixed batch to be rejected atomically"
        except McpError:
            pass
        # debugLogging should STILL be true — flip again with that single key
        # and assert no-op-like success (atomic validation prevented partial write).
        # Best assertion we can make from outside: after a mixed-batch reject,
        # writing the same value again should still succeed.
        result = self.client.call_tool("hub_manage_mcp", {
            "tool": "hub_update_mcp_settings",
            "args": {"settings": {"debugLogging": True}, "confirm": True},
        })
        assert result.get("success") is True, f"post-rejection write didn't succeed: {result}"

    @test("developer_mode")
    def test_t223_update_mcp_settings_reconnect_hint(self) -> None:
        """T223: response message includes a client-reconnect hint."""
        result = self.client.call_tool("hub_manage_mcp", {
            "tool": "hub_update_mcp_settings",
            "args": {"settings": {"enableCustomRuleEngine": False}, "confirm": True},
        })
        assert result.get("success") is True
        msg = result.get("message") or ""
        assert "reconnect" in msg.lower(), f"message missing 'reconnect' hint: {msg}"
        assert "tool schemas" in msg, f"message missing 'tool schemas' phrase: {msg}"
        # Restore so the rest of the suite (rule_crud etc.) keeps working.
        restore = self.client.call_tool("hub_manage_mcp", {
            "tool": "hub_update_mcp_settings",
            "args": {"settings": {"enableCustomRuleEngine": True}, "confirm": True},
        })
        assert restore.get("success") is True

    @test("developer_mode")
    def test_t223c_update_mcp_settings_enable_read_allowlisted(self) -> None:
        """T223c: enableRead is allowlisted (Read master self-toggle) and returns the reconnect hint.

        Under the universal Read/Write masters, enableRead is the read-master
        toggle and IS allowlisted for self-administration (unlike enableWrite,
        which would footgun the tool's own write path -- see T221). Flipping it
        must succeed and emit the same client-reconnect hint as other enable*
        toggles. Restore enableRead:true so the rest of the suite keeps its
        read tools.
        """
        result = self.client.call_tool("hub_manage_mcp", {
            "tool": "hub_update_mcp_settings",
            "args": {"settings": {"enableRead": False}, "confirm": True},
        })
        assert result.get("success") is True, f"enableRead flip did not succeed: {result}"
        assert result.get("updated") == {"enableRead": False}, f"updated field mismatch: {result}"
        msg = result.get("message") or ""
        assert "reconnect" in msg.lower(), f"message missing 'reconnect' hint: {msg}"
        assert "tool schemas" in msg, f"message missing 'tool schemas' phrase: {msg}"
        # Restore so the rest of the suite keeps its read tools.
        restore = self.client.call_tool("hub_manage_mcp", {
            "tool": "hub_update_mcp_settings",
            "args": {"settings": {"enableRead": True}, "confirm": True},
        })
        assert restore.get("success") is True

    @test("developer_mode")
    def test_t224_delete_variable_round_trip(self) -> None:
        """T224: set → delete → verify gone."""
        var_name = f"{PREFIX}DELETE_T224"
        # Setup
        self.client.call_tool("hub_manage_variables", {
            "tool": "hub_set_variable",
            "args": {"name": var_name, "value": "scratch-t224"},
        })
        # Track for cleanup in case assertion fails partway
        self.created_variable_names.append(var_name)
        # Delete
        result = self.client.call_tool("hub_manage_variables", {
            "tool": "hub_delete_variable",
            "args": {"name": var_name, "confirm": True},
        })
        assert result.get("success") is True
        assert result.get("deleted") is True
        assert result.get("source") == "rule_engine"
        assert result.get("previousValue") == "scratch-t224"
        assert result.get("brokenConsumers") is None  # no rules reference this var
        # Verify gone
        try:
            self.client.call_tool("hub_manage_variables", {
                "tool": "hub_get_variable",
                "args": {"name": var_name},
            })
            assert False, f"{var_name} should not be retrievable after delete"
        except McpError as e:
            assert "not found" in str(e).lower(), f"unexpected error after delete: {e}"
        # Don't double-cleanup
        if var_name in self.created_variable_names:
            self.created_variable_names.remove(var_name)

    @test("developer_mode")
    def test_t225_delete_variable_not_in_either_namespace(self) -> None:
        """T225: refusal when the variable doesn't exist in either namespace."""
        # hub_delete_variable now addresses both hub-variables and rule_engine
        # namespaces (PR #151), so the refusal mentions both.
        try:
            self.client.call_tool("hub_manage_variables", {
                "tool": "hub_delete_variable",
                "args": {"name": f"{PREFIX}DEFINITELY_NONEXISTENT_T225", "confirm": True},
            })
            assert False, "Expected refusal for variable missing from both namespaces"
        except McpError as e:
            msg = str(e)
            assert "not found in either the hub-variables namespace or the rule_engine namespace" in msg, f"wrong refusal text: {msg}"

    @test("developer_mode")
    def test_t226_delete_variable_no_confirm(self) -> None:
        """T226: hub_delete_variable refuses when the confirm flag is absent.

        The gateway-layer required-param check returns the refusal as isError. Per the
        #209 envelope contract (handleToolsCall flags a tool-returned isError on the
        JSON-RPC result), call_tool RAISES McpToolError when isError lands top-level; an
        isError-in-content-only envelope comes back as a dict. Accept EITHER -- both prove
        the destructive delete was refused for the missing confirm (same as the rooms
        confirm-gate check).
        """
        var_name = f"{PREFIX}NO_CONFIRM_T226"
        self.client.call_tool("hub_manage_variables", {
            "tool": "hub_set_variable",
            "args": {"name": var_name, "value": "safe"},
        })
        self.created_variable_names.append(var_name)

        refused = False
        detail = None
        try:
            detail = self.client.call_tool("hub_manage_variables", {
                "tool": "hub_delete_variable",
                "args": {"name": var_name},  # no confirm
            })
            blob = (detail if isinstance(detail, str) else json.dumps(detail)).lower()
            refused = (isinstance(detail, dict) and bool(detail.get("isError"))) and (
                "confirm" in blob or "required parameter" in blob
            )
        except McpError as e:
            detail = str(e)
            refused = any(s in detail.lower() for s in ("confirm", "safety check", "required parameter"))
        assert refused, f"hub_delete_variable without confirm should be refused by the safety gate, got: {detail}"

        # Variable should still exist (the refusal must not have deleted it).
        verify = self.client.call_tool("hub_manage_variables", {
            "tool": "hub_get_variable",
            "args": {"name": var_name},
        })
        assert verify.get("value") == "safe", f"variable was deleted despite missing confirm: {verify}"

    @test("developer_mode")
    def test_per_key_mcplogs_validation_atomic(self) -> None:
        """Atomic rejection — bad mcpLogLevel in a mixed batch with debugLogging blocks both."""
        # Set known baseline for debugLogging (true) — needs to NOT change despite the bad key.
        self.client.call_tool("hub_manage_mcp", {
            "tool": "hub_update_mcp_settings",
            "args": {"settings": {"debugLogging": True}, "confirm": True},
        })
        try:
            self.client.call_tool("hub_manage_mcp", {
                "tool": "hub_update_mcp_settings",
                "args": {
                    "settings": {"debugLogging": False, "mcpLogLevel": "blarg"},
                    "confirm": True,
                },
            })
            assert False, "Expected -32602 rejection for mcpLogLevel='blarg'"
        except McpError as e:
            msg = str(e)
            assert "mcpLogLevel" in msg, f"error didn't mention mcpLogLevel: {msg}"
            assert "blarg" in msg, f"error didn't surface the rejected value: {msg}"

    @test("developer_mode")
    def test_type_coercion_string_to_bool(self) -> None:
        """Type coercion — JSON-RPC clients sending string 'true'/'false' get coerced to native bool."""
        # JSON in this test runner naturally encodes Python bool as JSON true/false,
        # so to actually exercise the coercion path we have to send a string literal.
        result = self.client.call_tool("hub_manage_mcp", {
            "tool": "hub_update_mcp_settings",
            "args": {"settings": {"debugLogging": "false"}, "confirm": True},
        })
        assert result.get("success") is True
        # Re-read via a write that should be a no-op if coercion landed correctly:
        # writing native False should also succeed and round-trip cleanly.
        result2 = self.client.call_tool("hub_manage_mcp", {
            "tool": "hub_update_mcp_settings",
            "args": {"settings": {"debugLogging": True}, "confirm": True},
        })
        assert result2.get("success") is True
        assert result2.get("updated") == {"debugLogging": True}

    @test("developer_mode")
    def test_type_coercion_rejects_invalid_bool_string(self) -> None:
        """Type coercion — strings that aren't 'true'/'false' get rejected, not silently coerced."""
        try:
            self.client.call_tool("hub_manage_mcp", {
                "tool": "hub_update_mcp_settings",
                "args": {"settings": {"debugLogging": "yes"}, "confirm": True},
            })
            assert False, "Expected rejection for ambiguous bool-coerced string 'yes'"
        except McpError as e:
            msg = str(e)
            assert "debugLogging" in msg, f"error didn't mention the key: {msg}"
            assert "boolean" in msg.lower(), f"error didn't say boolean: {msg}"

    @test("developer_mode")
    def test_update_package_dry_run(self) -> None:
        """Issue #250: hub_update_package dry-run plans the full HPM repair with ZERO writes.

        It is now a TOP-LEVEL tool (pulled out of the hub_manage_mcp gateway). dryRun fetches
        packageManifest.json at the ref and reports the bundle(s) + apps it WOULD deploy (the self
        app last), making no changes and needing no confirm. Deploy ref 'main' so the plan is stable
        regardless of the PR under test. The REAL deploy leg is intentionally not e2e'd -- it
        recompiles the running server mid-call (issue #237), which is exactly what the watchdog exists
        to drive; only the no-write dryRun is safe to exercise here.
        """
        result = self.client.call_tool("hub_update_package", {"ref": "main", "dryRun": True})
        assert result.get("success") is True, f"dry-run did not succeed: {result}"
        assert result.get("dryRun") is True, f"dryRun flag not echoed: {result}"
        # The library bundle is planned, re-anchored to the deploy ref.
        bundles = result.get("plannedBundles") or []
        assert any((b.get("url") or "").endswith(".zip") and "/main/" in (b.get("url") or "")
                   for b in bundles), f"expected a planned library bundle re-anchored to 'main': {bundles}"
        # Both apps are planned; exactly one self app (the parent), and it is listed LAST.
        apps = result.get("plannedApps") or []
        names = [a.get("name") for a in apps]
        assert "MCP Rule Server" in names, f"parent app missing from the plan: {apps}"
        self_apps = [a for a in apps if a.get("isSelf")]
        assert len(self_apps) == 1 and self_apps[0].get("name") == "MCP Rule Server", \
            f"expected exactly one self app (MCP Rule Server): {apps}"
        assert apps and apps[-1].get("isSelf") is True, \
            f"the self app must be planned LAST (deployed last so its recompile is the final act): {apps}"

    # -----------------------------------------------------------------------
    # GROUP 11: hub_get_device_attribute poll mode (2 tests -- wall-clock coverage, I7)
    # These exercise the real pauseExecution + now() path that Spock unit tests
    # cannot reach because the test harness fixes now() to a constant.
    # -----------------------------------------------------------------------

    @test("poll_until_attribute")
    def test_poll_immediate_match(self) -> None:
        """Happy path: device already in expected state -> polledCount=1, success=true."""
        # Use the shared virtual switch; get_or_create ensures it exists in 'off' state.
        dev_id = self.get_test_switch_id()
        # Drive it to 'off' first so we know its state.
        self.client.call_tool("hub_call_device_command", {"deviceId": dev_id, "command": "off"})
        time.sleep(0.3)

        result = self.client.call_tool("hub_get_device_attribute", {
            "deviceId": dev_id,
            "attribute": "switch",
            "expectedValue": "off",
            "timeoutMs": 5000,
        })
        assert result.get("success") is True, f"Expected success=true, got: {result}"
        assert result.get("timedOut") is False, f"Expected timedOut=false, got: {result}"
        assert result.get("polledCount", 0) >= 1, f"Expected polledCount>=1, got: {result}"

    @test("poll_until_attribute")
    def test_poll_timeout(self) -> None:
        """Timeout path: value won't match -> timedOut=true, elapsedMs approx timeoutMs."""
        dev_id = self.get_test_switch_id()
        # Ensure switch is 'off' so 'on' won't match.
        self.client.call_tool("hub_call_device_command", {"deviceId": dev_id, "command": "off"})
        time.sleep(0.3)

        import time as _time
        t0 = _time.monotonic()
        result = self.client.call_tool("hub_get_device_attribute", {
            "deviceId": dev_id,
            "attribute": "switch",
            "expectedValue": "on",
            "timeoutMs": 2000,
        })
        elapsed_wall = (_time.monotonic() - t0) * 1000

        assert result.get("success") is False, f"Expected success=false, got: {result}"
        assert result.get("timedOut") is True, f"Expected timedOut=true, got: {result}"
        # Wall clock should reflect roughly the timeout (within 1 second of variance)
        assert elapsed_wall >= 1800, f"Wall clock too short ({elapsed_wall:.0f}ms); poll may not have blocked"

    # -----------------------------------------------------------------------
    # GROUP 12: error_verification (1 test)
    # -----------------------------------------------------------------------

    @test("error_verification")
    def test_no_hub_errors(self) -> None:
        """Soft check: look for hub errors logged during the test window."""
        try:
            result = self.client.call_tool("hub_manage_logs", {
                "tool": "hub_get_logs",
                "args": {"level": "error"},
            })
            logs = result if isinstance(result, list) else result.get("logs", [])
            if logs:
                # Filter to logs during our test window
                recent = []
                if self._test_start_time:
                    for entry in logs:
                        ts = entry.get("time", entry.get("timestamp", ""))
                        if ts >= self._test_start_time:
                            recent.append(entry)
                else:
                    recent = logs

                if recent:
                    print(f"    [WARN] {len(recent)} hub error(s) found during test window:")
                    for e in recent[:5]:
                        msg = e.get("message", e.get("msg", str(e)))[:120]
                        print(f"           - {msg}")
                    # Soft check: warn but don't fail
        except Exception as exc:
            print(f"    [WARN] Could not check hub logs: {exc}")

    # -----------------------------------------------------------------------
    # GROUP 13: protocol (6 tests — initialize echo-allowlist, instructions,
    # inbound batch cap, and 202-for-notifications. These exercise the
    # transport/protocol layer end-to-end through the cloud relay, which the
    # Spock harness (in-process dispatch) cannot reach.)
    # -----------------------------------------------------------------------

    @test("protocol")
    def test_initialize_echoes_supported_protocol(self) -> None:
        """Echo-allowlist: a supported protocolVersion is echoed back verbatim."""
        result = self.client.initialize("2025-06-18")
        assert result.get("protocolVersion") == "2025-06-18", \
            f"Expected echoed 2025-06-18, got: {result.get('protocolVersion')}"

    @test("protocol")
    def test_initialize_echoes_alt_supported_protocol(self) -> None:
        """A second supported version (2025-03-26) is also echoed — proves the
        allowlist isn't hardcoded to a single value."""
        result = self.client.initialize("2025-03-26")
        assert result.get("protocolVersion") == "2025-03-26", \
            f"Expected echoed 2025-03-26, got: {result.get('protocolVersion')}"

    @test("protocol")
    def test_initialize_falls_back_on_unsupported_protocol(self) -> None:
        """An unsupported protocolVersion falls back to the server default
        (2024-11-05) rather than erroring."""
        result = self.client.initialize("1999-01-01")
        assert result.get("protocolVersion") == "2024-11-05", \
            f"Expected fallback 2024-11-05, got: {result.get('protocolVersion')}"

    @test("protocol")
    def test_initialize_returns_instructions(self) -> None:
        """initialize advertises a non-empty instructions string (gateway +
        pagination usage hint) so MCP clients can surface server guidance."""
        result = self.client.initialize()
        instructions = result.get("instructions")
        assert isinstance(instructions, str) and instructions.strip(), \
            f"Expected non-empty instructions string, got: {instructions!r}"

    @test("protocol")
    def test_batch_too_large_rejected(self) -> None:
        """A JSON-RPC batch over the 50-element cap is rejected wholesale with a
        single -32600 error before any element runs (inbound batch cap)."""
        batch = [{"jsonrpc": "2.0", "id": i, "method": "tools/list"} for i in range(51)]
        resp = self.client.raw_request(batch)
        data = resp.json()
        # A single error object, NOT an array — the cap trips before per-element dispatch.
        assert isinstance(data, dict), f"Expected one error object, got {type(data).__name__}: {data}"
        err = data.get("error", {})
        assert err.get("code") == -32600, f"Expected -32600, got: {data}"
        assert "batch too large" in err.get("message", ""), \
            f"Expected 'batch too large' message, got: {err.get('message')!r}"

    @test("protocol")
    def test_notification_returns_202(self) -> None:
        """An all-notifications POST (no id) returns HTTP 202 Accepted with an
        empty body per MCP Streamable HTTP — replaces the prior 204, which some
        clients/relays mishandle."""
        resp = self.client.raw_request({"jsonrpc": "2.0", "method": "notifications/initialized"})
        assert resp.status_code == 202, \
            f"Expected HTTP 202 for a notification, got {resp.status_code}: {resp.text[:200]!r}"
        assert resp.text.strip() == "", f"Expected empty body for 202, got: {resp.text[:200]!r}"

    # -----------------------------------------------------------------------
    # Cleanup
    # -----------------------------------------------------------------------

    def cleanup(self) -> None:
        """Multi-layer cleanup of BAT_E2E_ artifacts:
        1. Tracked artifacts (device DNIs, rule IDs, variables)
        2. Virtual devices (prefix sweep)
        3. Custom rules (prefix sweep)
        4. Native RM apps + Visual Rules (tracked + prefix sweeps)
        5. mcptest throwaway app + driver code classes (namespace+name)
        6. Rooms (prefix sweep)
        """
        print("\n--- Cleanup ---")

        # Layer 1: tracked artifacts
        for rule_id in list(self.created_rule_ids):
            try:
                print(f"  Deleting tracked rule {rule_id}")
                self.client.call_tool("hub_delete_custom_rule", {"ruleId": rule_id, "confirm": True})
            except Exception as exc:
                print(f"  [WARN] Failed to delete rule {rule_id}: {exc}")
        self.created_rule_ids.clear()

        for dni in list(self.created_device_dnis):
            try:
                print(f"  Deleting tracked device DNI={dni}")
                self.client.call_tool("hub_manage_virtual_device", {
                    "action": "delete",
                    "deviceNetworkId": dni,
                    "confirm": True,
                })
            except Exception as exc:
                print(f"  [WARN] Failed to delete device DNI={dni}: {exc}")
        self.created_device_dnis.clear()

        for var_name in list(self.created_variable_names):
            try:
                print(f"  Deleting tracked variable {var_name}")
                self.client.call_tool("hub_manage_variables", {
                    "tool": "hub_delete_variable",
                    "args": {"name": var_name, "confirm": True},
                })
            except Exception as exc:
                print(f"  [WARN] Failed to delete variable {var_name}: {exc}")
        self.created_variable_names.clear()

        # Layer 2: sweep virtual devices with BAT_E2E_ prefix
        try:
            vdevs = self.client.call_tool("hub_list_devices", {"labelFilter": PREFIX})
            dev_list = vdevs if isinstance(vdevs, list) else vdevs.get("devices", [])
            for d in dev_list:
                lbl = d.get("label") or d.get("name") or ""
                # Keep the persistent scaffolding switch (get_test_switch_id find-and-reuse): sweeping it
                # would defeat the reuse and pay a create every run. Narrow suffix match, NOT a blanket
                # prefix skip, so genuine under-test device leftovers are still reclaimed.
                if lbl.endswith("Action_Switch"):
                    continue
                if PREFIX in lbl:
                    dni = str(d.get("deviceNetworkId", d.get("dni", "")))
                    if dni:
                        try:
                            print(f"  Sweep: deleting virtual device '{lbl}' (DNI={dni})")
                            self.client.call_tool("hub_manage_virtual_device", {
                                "action": "delete",
                                "deviceNetworkId": dni,
                                "confirm": True,
                            })
                        except Exception as exc:
                            print(f"  [WARN] Sweep delete failed for '{lbl}': {exc}")
        except Exception as exc:
            print(f"  [WARN] Virtual device sweep failed: {exc}")

        # Layer 3: sweep rules with BAT_E2E_ prefix
        try:
            rules_result = self.client.call_tool("hub_get_custom_rule")
            rules = rules_result if isinstance(rules_result, list) else rules_result.get("rules", [])
            for r in rules:
                rname = r.get("name", "")
                if PREFIX in rname:
                    rid = str(r.get("id", r.get("ruleId", "")))
                    if rid:
                        try:
                            print(f"  Sweep: deleting rule '{rname}' (id={rid})")
                            self.client.call_tool("hub_delete_custom_rule", {"ruleId": rid, "confirm": True})
                        except Exception as exc:
                            print(f"  [WARN] Sweep delete failed for rule '{rname}': {exc}")
        except Exception as exc:
            print(f"  [WARN] Rule sweep failed: {exc}")

        # Layer 4: native RM rules / classic apps (issue #137). Tracked ids first,
        # then a list-based sweep for anything a failed native_apps test left behind.
        # When deferral is on, the disarm step's force sweep (over WATCHDOG_URL, overlapping the
        # restore poll) owns these deletes, so skip them here to keep them off the test critical path.
        # The post-restore --cleanup-only step runs WITHOUT the flag, so it's the idempotent backstop.
        if self.defer_native_deletes:
            deferred_ids = {str(a) for a in self.created_native_app_ids}
            # Also fold in any PREFIX-matched native rule a FAILED test created but never tracked (the rule
            # is hub-created before its id is appended), so the disarm exact-id sweep reaps those too --
            # otherwise an untracked leftover would survive until the post-restore --cleanup-only prefix
            # sweep. This is the deferral-branch equivalent of the non-deferral prefix sweep below.
            try:
                nrules = self.client.call_tool("hub_manage_rule_machine", {"tool": "hub_list_rules", "args": {}})
                for r in (nrules if isinstance(nrules, list) else nrules.get("rules", [])):
                    if PREFIX in (r.get("name") or r.get("label") or ""):
                        rid = str(r.get("id", r.get("appId", "")))
                        if rid:
                            deferred_ids.add(rid)
            except Exception as exc:
                print(f"  [WARN] could not list native rules for the deferred union (tracked ids still deferred): {exc}")
            # hub_list_rules lists RM rules only, so fold in PREFIX-matched Visual
            # Rules Builder children via the VRB list (one call) the same way --
            # force-delete in the disarm sweep works on VRB children too.
            try:
                vlisted = self._get_visual_rule()
                for r in (vlisted.get("rules", []) if isinstance(vlisted, dict) else []):
                    if str(r.get("name") or "").startswith(PREFIX):
                        vid = str(r.get("appId") or "")
                        if vid:
                            deferred_ids.add(vid)
            except Exception as exc:
                print(f"  [WARN] could not list Visual Rules for the deferred union (tracked ids still deferred): {exc}")
            deferred_ids = sorted(deferred_ids)
            print(f"  Layer 4: deferring {len(deferred_ids)} native-rule delete(s) to the disarm sweep")
            # Hand the EXACT instance ids to the disarm sweep via File Manager so it force-deletes ONLY
            # these (no guessing the /hub2/appsList shape -> no risk of deleting the wrong app). The
            # post-restore --cleanup-only prefix sweep (no flag) is the backstop if this list is missed.
            try:
                self.client.call_tool("hub_manage_files", {
                    "tool": "hub_write_file",
                    "args": {"fileName": "e2e-deferred-native-rules.json",
                             "content": json.dumps(deferred_ids), "confirm": True},
                })
            except Exception as exc:
                print(f"  [WARN] could not write the deferred-rule id list; the prefix backstop will reap them: {exc}")
        else:
            for app_id in list(self.created_native_app_ids):
                try:
                    print(f"  Deleting tracked native app {app_id}")
                    # force: tracked artifacts can carry children (a Button
                    # Controller's grandchild button rules); the soft delete
                    # refuses those and would strand the whole subtree.
                    # Tracked ids may also be Visual Rules Builder children --
                    # force-delete works on those too; the VRB prefix sweep
                    # below backstops the untracked ones.
                    self.client.call_tool("hub_manage_rule_machine", {
                        "tool": "hub_delete_native_app", "args": {"appId": app_id, "force": True, "confirm": True},
                    })
                except Exception as exc:
                    print(f"  [WARN] Failed to delete native app {app_id}: {exc}")
            self.created_native_app_ids.clear()
            try:
                nrules = self.client.call_tool("hub_manage_rule_machine", {"tool": "hub_list_rules", "args": {}})
                nlist = nrules if isinstance(nrules, list) else nrules.get("rules", [])
                for r in nlist:
                    rname = r.get("name") or r.get("label") or ""
                    if PREFIX in rname:
                        rid = str(r.get("id", r.get("appId", "")))
                        if rid:
                            try:
                                print(f"  Sweep: deleting native rule '{rname}' (id={rid})")
                                self.client.call_tool("hub_manage_rule_machine", {
                                    "tool": "hub_delete_native_app", "args": {"appId": rid, "confirm": True},
                                })
                            except Exception as exc:
                                print(f"  [WARN] Native rule sweep delete failed for '{rname}': {exc}")
            except Exception as exc:
                print(f"  [WARN] Native rule sweep failed: {exc}")
            # VRB backstop: hub_list_rules above lists RM rules only, so untracked
            # Visual Rules Builder leftovers need their own prefix sweep (one VRB
            # list call; the generic force-delete works on VRB children).
            try:
                vlisted = self._get_visual_rule()
                for r in (vlisted.get("rules", []) if isinstance(vlisted, dict) else []):
                    vname = str(r.get("name") or "")
                    if vname.startswith(PREFIX):
                        vid = str(r.get("appId") or "")
                        if vid:
                            try:
                                print(f"  Sweep: deleting Visual Rule '{vname}' (id={vid})")
                                self.client.call_tool("hub_manage_rule_machine", {
                                    "tool": "hub_delete_native_app",
                                    "args": {"appId": vid, "force": True, "confirm": True},
                                })
                            except Exception as exc:
                                print(f"  [WARN] Visual Rule sweep delete failed for '{vname}': {exc}")
            except Exception as exc:
                print(f"  [WARN] Visual Rule sweep failed: {exc}")

        # Layer 5: stranded mcptest throwaways. The @test("deadman") test installs 'Deadman Test
        # Target' (instance + code class), the @test("app_code_update") test creates the
        # 'Deadman Test Target Update' code class, and the @test("driver_code_update") test
        # creates the 'Deadman Test Target Driver' driver code class (all named to ride this
        # same startswith match); none carry the BAT_E2E_ prefix, so a crash/kill between a
        # create and its finally would strand them past the other sweeps. Reclaim instance(s)
        # + code classes by namespace+name (idempotent across runs).
        try:
            dtypes = self.client.call_tool("hub_read_apps_code",
                                           {"tool": "hub_list_apps", "args": {"scope": "types"}})
            for a in (dtypes.get("apps", []) if isinstance(dtypes, dict) else []):
                if a.get("namespace") == "mcptest" and str(a.get("name") or "").startswith("Deadman Test Target"):
                    for u in a.get("usedBy", []) or []:
                        try:
                            print(f"  Sweep: deleting stranded deadman instance {u.get('id')}")
                            self.client.call_tool("hub_manage_native_rules_and_apps", {
                                "tool": "hub_delete_native_app",
                                "args": {"appId": str(u.get("id")), "force": True, "confirm": True},
                            })
                        except Exception as exc:
                            print(f"  [WARN] deadman sweep: instance delete failed: {exc}")
                    try:
                        print(f"  Sweep: deleting stranded deadman code class {a.get('id')}")
                        self.client.call_tool("hub_manage_code", {
                            "tool": "hub_delete_item",
                            "args": {"type": "app", "id": str(a.get("id")), "confirm": True},
                        })
                    except Exception as exc:
                        print(f"  [WARN] deadman sweep: code-class delete failed: {exc}")
        except Exception as exc:
            print(f"  [WARN] deadman target sweep failed: {exc}")

        # Layer 5 (drivers): the driver-code throwaway rides the same namespace+name
        # convention but lives in Drivers Code, which the app-type listing above
        # never sees -- sweep it through the driver list.
        try:
            ddrvs = self.client.call_tool("hub_read_apps_code", {"tool": "hub_list_drivers", "args": {}})
            for d in (ddrvs.get("drivers", []) if isinstance(ddrvs, dict) else []):
                if d.get("namespace") == "mcptest" and str(d.get("name") or "").startswith("Deadman Test Target"):
                    try:
                        print(f"  Sweep: deleting stranded throwaway driver code class {d.get('id')}")
                        self.client.call_tool("hub_manage_code", {
                            "tool": "hub_delete_item",
                            "args": {"type": "driver", "id": str(d.get("id")), "confirm": True},
                        })
                    except Exception as exc:
                        print(f"  [WARN] driver sweep: code-class delete failed: {exc}")
        except Exception as exc:
            print(f"  [WARN] throwaway driver sweep failed: {exc}")

        # Layer 6: rooms with the BAT_E2E_ prefix (issue #209 McpRoomsLib round-trip).
        # The create/rename/delete test cleans up in its own finally; this reclaims a
        # room a crashed run stranded.
        try:
            rooms_result = self.client.call_tool("hub_manage_rooms", {"tool": "hub_list_rooms"})
            rlist = rooms_result.get("rooms", []) if isinstance(rooms_result, dict) else []
            for rm in rlist:
                rname = rm.get("name") or ""
                if PREFIX in rname:
                    rid = str(rm.get("id", ""))
                    if rid:
                        try:
                            print(f"  Sweep: deleting room '{rname}' (id={rid})")
                            self.client.call_tool("hub_manage_rooms", {
                                "tool": "hub_delete_room",
                                "args": {"room": rid, "confirm": True},
                            })
                        except Exception as exc:
                            print(f"  [WARN] Room sweep delete failed for '{rname}': {exc}")
        except Exception as exc:
            print(f"  [WARN] Room sweep failed: {exc}")

        # Layer 7: throwaway bundle from the hub_delete_bundle e2e (mcptest namespace). The test
        # deletes it in its own finally; this reclaims one a crashed run stranded.
        try:
            bres = self.client.call_tool("hub_read_apps_code", {"tool": "hub_list_bundles"})
            for b in (bres.get("bundles", []) if isinstance(bres, dict) else []):
                if b.get("namespace") == "mcptest" and b.get("id"):
                    try:
                        print(f"  Sweep: deleting throwaway bundle '{b.get('name')}' (id={b.get('id')})")
                        self.client.call_tool("hub_manage_code", {
                            "tool": "hub_delete_bundle",
                            "args": {"bundleId": str(b.get("id")), "confirm": True},
                        })
                    except Exception as exc:
                        print(f"  [WARN] throwaway bundle sweep delete failed for '{b.get('name')}': {exc}")
        except Exception as exc:
            print(f"  [WARN] throwaway bundle sweep failed: {exc}")

        # Layer 7b: the throwaway LIBRARY (mcptest namespace) the bundle delivered. Bundle delete does
        # not cascade it, and the disarm no-stale gate only sweeps the 'mcp' namespace, so a crashed run
        # can strand it in Libraries Code. Reclaim it here.
        try:
            lres = self.client.call_tool("hub_read_apps_code", {"tool": "hub_list_libraries"})
            for lib in (lres.get("libraries", []) if isinstance(lres, dict) else []):
                if lib.get("namespace") == "mcptest" and lib.get("id"):
                    try:
                        print(f"  Sweep: deleting throwaway library '{lib.get('name')}' (id={lib.get('id')})")
                        self.client.call_tool("hub_manage_code", {
                            "tool": "hub_delete_item",
                            "args": {"type": "library", "id": str(lib.get("id")), "confirm": True},
                        })
                    except Exception as exc:
                        print(f"  [WARN] throwaway library sweep delete failed for '{lib.get('name')}': {exc}")
        except Exception as exc:
            print(f"  [WARN] throwaway library sweep failed: {exc}")

        print("--- Cleanup complete ---\n")

    def verify_native_rules_clean(self) -> list[str] | None:
        """Re-list native RM rules AND Visual Rules and return the BAT_E2E_ ones still present
        (empty list = clean). hub_list_rules sees RM rules only, so without the VRB list a
        stranded Visual Rule -- still subscribed to the persistent test switch -- passes this
        gate invisibly and sabotages the next run's device-command tests. Returns None if
        either listing could not be fetched after retries -- the caller treats that as
        'cannot prove cleanup' and fails closed. Retries ride out a transient transport blip."""
        leftovers: list[str] | None = None
        for attempt in range(1, 4):
            try:
                nrules = self.client.call_tool("hub_manage_rule_machine", {"tool": "hub_list_rules", "args": {}})
                rlist = nrules if isinstance(nrules, list) else nrules.get("rules", [])
                leftovers = [
                    f"{r.get('name') or r.get('label')} (id={r.get('id', r.get('appId'))})"
                    for r in rlist
                    if PREFIX in (r.get("name") or r.get("label") or "")
                ]
                break
            except Exception as exc:
                print(f"  [WARN] verify_native_rules_clean: list attempt {attempt}/3 failed: {exc}")
                time.sleep(2)
        if leftovers is None:
            return None
        for attempt in range(1, 4):
            try:
                vlisted = self._get_visual_rule()
                leftovers += [
                    f"{r.get('name')} (visual, id={r.get('appId')})"
                    for r in (vlisted.get("rules", []) if isinstance(vlisted, dict) else [])
                    if str(r.get("name") or "").startswith(PREFIX)
                ]
                return leftovers
            except Exception as exc:
                print(f"  [WARN] verify_native_rules_clean: VRB list attempt {attempt}/3 failed: {exc}")
                time.sleep(2)
        return None

    # -----------------------------------------------------------------------
    # Run
    # -----------------------------------------------------------------------

    def run(self, filter_group: str | None = None,
            filter_test: str | None = None) -> bool:
        """Run tests. Returns True if all passed."""
        self._test_start_time = datetime.now(UTC).isoformat()

        tests_to_run = []
        for group, display_name, method_name in TEST_REGISTRY:
            if filter_group and group != filter_group:
                continue
            if filter_test and filter_test not in display_name:
                continue
            tests_to_run.append((group, display_name, method_name))

        if not tests_to_run:
            print("No tests matched the filter criteria.")
            return True

        # Group for display
        current_group = None
        for group, display_name, method_name in tests_to_run:
            if group != current_group:
                current_group = group
                print(f"\n[{group}]")
            self._run_one(group, display_name, method_name)

        # Always clean up
        self.cleanup()

        # Print summary
        return self._print_summary()

    def _print_summary(self) -> bool:
        """Print results table. Returns True if all passed."""
        print("\n" + "=" * 60)
        print("E2E Test Results")
        print("=" * 60)

        # Group-level summary
        groups: dict[str, dict[str, int]] = {}
        for r in self.results:
            g = r["group"]
            if g not in groups:
                groups[g] = {"pass": 0, "fail": 0, "skip": 0}
            groups[g][r["status"]] += 1

        for g, counts in groups.items():
            total = counts["pass"] + counts["fail"] + counts["skip"]
            status_parts = []
            if counts["pass"]:
                status_parts.append(f"{counts['pass']} passed")
            if counts["fail"]:
                status_parts.append(f"{counts['fail']} FAILED")
            if counts["skip"]:
                status_parts.append(f"{counts['skip']} skipped")
            print(f"  {g:<30s} {'/'.join(status_parts):>20s}  ({total} tests)")

        print("-" * 60)

        total_pass = sum(c["pass"] for c in groups.values())
        total_fail = sum(c["fail"] for c in groups.values())
        total_skip = sum(c["skip"] for c in groups.values())
        total_all = total_pass + total_fail + total_skip
        total_dur = sum(r["duration"] for r in self.results)

        print(f"  Total: {total_pass}/{total_all} passed, "
              f"{total_fail} failed, {total_skip} skipped  "
              f"({total_dur:.1f}s)")

        # Slowest tests (diagnostic -- surface optimization targets; the suite is the biggest e2e cost).
        slow = sorted(self.results, key=lambda r: r.get("duration", 0.0), reverse=True)[:15]
        if slow:
            print("\n  Slowest tests:")
            for r in slow:
                print(f"    {r.get('duration', 0.0):6.1f}s  {r.get('group', '?')}/{r['name']}")

        # Per-op wall-clock (diagnostic -- real per-operation cost, the basis for fixture/cleanup
        # optimization: how much is RM create vs edit vs delete vs reads). Aggregated by op key.
        ops = getattr(self.client, "op_timings", [])
        if ops:
            agg: dict[str, list[float]] = {}
            for op_key, dur in ops:
                slot = agg.setdefault(op_key, [0, 0.0])
                slot[0] += 1
                slot[1] += dur
            print("\n  Per-op wall-clock (total / count / avg, slowest total first):")
            for op_key, (cnt, tot) in sorted(agg.items(), key=lambda kv: kv[1][1], reverse=True)[:20]:
                print(f"    {tot:6.1f}s  {int(cnt):3d}x  {tot / cnt:4.1f}s avg  {op_key}")

        # List failures
        failures = [r for r in self.results if r["status"] == "fail"]
        if failures:
            print("\nFailures:")
            for r in failures:
                print(f"  - {r['name']}: {r['message']}")

        # List skips loudly. A skip means a test could NOT prove what it set out to (a missing
        # precondition, a failed upstream create, etc.) -- it is NOT a pass. The run fails on any
        # skip so the e2e can never go green while silently not validating something.
        skips = [r for r in self.results if r["status"] == "skip"]
        if skips:
            print("\nSkipped (treated as FAILURES -- nothing may be silently skipped):")
            for r in skips:
                print(f"  - {r['name']}: {r['message']}")

        print("=" * 60)
        # Green ONLY when every test ran AND passed -- zero failures and zero skips.
        return total_fail == 0 and total_skip == 0


# ---------------------------------------------------------------------------
# Sentinel exception for skipping tests
# ---------------------------------------------------------------------------

class SkipTest(Exception):
    """Raised to skip a test with a reason."""


# ---------------------------------------------------------------------------
# Utility functions
# ---------------------------------------------------------------------------


def _inject_device_id(obj: dict, dev_id: str) -> dict:
    """Replace 'PLACEHOLDER' device IDs in a dict (shallow copy)."""
    result = dict(obj)
    if result.get("deviceId") == "PLACEHOLDER":
        result["deviceId"] = dev_id
    # Recurse into nested structures
    for key in ("conditions", "thenActions", "elseActions", "actions"):
        if key in result and isinstance(result[key], list):
            result[key] = [_inject_device_id(item, dev_id) if isinstance(item, dict)
                           else item for item in result[key]]
    return result


def load_config() -> dict:
    """Load config from e2e_config.json, with env var overrides."""
    config_path = Path(__file__).resolve().parent / "e2e_config.json"
    config = {}

    if config_path.exists():
        with open(config_path, encoding="utf-8") as f:
            config = json.load(f)

    # Env var overrides
    config["hub_url"] = os.environ.get("HUBITAT_HUB_URL", config.get("hub_url", ""))
    config["app_id"] = os.environ.get("HUBITAT_APP_ID", config.get("app_id", ""))
    config["access_token"] = os.environ.get("HUBITAT_ACCESS_TOKEN", config.get("access_token", ""))

    # Validate
    missing = [k for k in ("hub_url", "app_id", "access_token") if not config.get(k)]
    if missing:
        print(f"ERROR: Missing config values: {', '.join(missing)}")
        print("  Set via tests/e2e_config.json or env vars "
              "HUBITAT_HUB_URL, HUBITAT_APP_ID, HUBITAT_ACCESS_TOKEN")
        if not config_path.exists():
            print(f"  Config file not found: {config_path}")
            print("  Copy e2e_config.example.json to e2e_config.json and fill in values.")
        sys.exit(1)

    return config


# ---------------------------------------------------------------------------
# CLI entry point
# ---------------------------------------------------------------------------


def main() -> None:
    parser = argparse.ArgumentParser(description="Hubitat MCP Server E2E Tests")
    parser.add_argument("--test", help="Run tests matching this substring")
    parser.add_argument("--group", help="Run only this test group")
    parser.add_argument("--cleanup-only", action="store_true",
                        help="Just clean up BAT_E2E_ test artifacts")
    parser.add_argument("--verbose", "-v", action="store_true",
                        help="Show request/response details")
    args = parser.parse_args()

    config = load_config()
    masked_token = config["access_token"][:4] + "..." \
        if len(config["access_token"]) > 4 else "****"

    print("=" * 60)
    print("Hubitat MCP Server — E2E Test Runner")
    print("=" * 60)
    print(f"  Hub:   {config['hub_url']}")
    print(f"  App:   {config['app_id']}")
    print(f"  Token: {masked_token}")
    print()

    client = HubitatMcpClient(
        hub_url=config["hub_url"],
        app_id=config["app_id"],
        access_token=config["access_token"],
        verbose=args.verbose,
    )

    runner = TestRunner(client, verbose=args.verbose)

    if args.cleanup_only:
        # The disarm step fires the watchdog's restore-to-main asynchronously, so this
        # step races a ~3-5 min window where the hub recompiles the restored main app
        # and every MCP call 504s through the cloud relay. A single liveness probe is
        # not enough -- the recompile opens at an unpredictable point and can land
        # MID-sweep (seen live: probe answered on attempt 3, sweeps still 504ed three
        # minutes later). Gate on restore COMPLETION instead: the watchdog stamps the
        # canonical-main SHA marker only after its restore verifies, so marker ==
        # MAIN_SHA means the recompile is behind us. Each poll rides through the 504s;
        # on budget exhaustion (or a failed restore, which leaves the marker cleared)
        # sweep anyway -- the fail-closed verify below still decides the outcome.
        main_sha = os.environ.get("MAIN_SHA", "")
        if main_sha:
            print("Waiting for the watchdog's restore-to-main to complete (canonical-main marker)...")
            for attempt in range(1, 49):
                try:
                    marker = runner.client.call_tool("hub_manage_files", {
                        "tool": "hub_read_file",
                        "args": {"fileName": "mcp-main-deployed-sha.txt"},
                    })
                    content = (marker.get("content") or "").strip() if isinstance(marker, dict) else ""
                    if content == main_sha:
                        print(f"  Restore complete: marker matches main SHA (attempt {attempt}).")
                        break
                except Exception:
                    pass
                if attempt == 48:
                    print("  [WARN] restore-complete marker never matched after ~8 min "
                          "(failed restore, or a slow recompile); sweeping anyway.")
                else:
                    time.sleep(10)
        runner.cleanup()
        # Gating verification: cleanup() and the disarm-time deferred sweep are otherwise all
        # best-effort (warn-only), so a silently-failed native-rule cleanup could leave BAT_E2E_ RM
        # apps on the SHARED hub behind a green run. This backstop FAILS CLOSED -- re-list and exit
        # nonzero if any BAT_E2E_ native rule survived, or if the hub can't be listed to prove it.
        leftovers = runner.verify_native_rules_clean()
        if leftovers is None:
            print("ERROR: cleanup-only could not list native rules to verify cleanup -- failing "
                  "closed (cannot prove the shared hub is free of BAT_E2E_ rules).")
            sys.exit(1)
        if leftovers:
            print(f"ERROR: cleanup-only left {len(leftovers)} BAT_E2E_ native rule(s) on the hub: "
                  f"{leftovers}")
            sys.exit(1)
        print("Cleanup-only mode complete; verified no BAT_E2E_ native rules remain.")
        sys.exit(0)

    # Verify connectivity before running tests
    print("Verifying hub connectivity...")
    try:
        client.initialize()
        print("  Hub is reachable. MCP server responded to initialize.\n")
    except requests.exceptions.ConnectionError:
        print(f"  ERROR: Cannot connect to hub at {config['hub_url']}")
        print("  Check that the hub is online and the URL is correct.")
        sys.exit(1)
    except Exception as exc:
        print(f"  ERROR: Initialize failed: {exc}")
        sys.exit(1)

    # Ensure a hub backup exists — many tools require a recent backup
    print("Creating hub backup (required by safety checks)...")
    try:
        backup_result = client.call_tool("hub_create_backup", {"confirm": True})
        msg = backup_result.get("message", backup_result) if isinstance(backup_result, dict) else backup_result
        print(f"  Backup: {msg}\n")
    except Exception as exc:
        print(f"  [WARN] Backup failed: {exc}")
        print("  Tests requiring backup may fail.\n")

    all_passed = runner.run(filter_group=args.group, filter_test=args.test)
    sys.exit(0 if all_passed else 1)


if __name__ == "__main__":
    main()
