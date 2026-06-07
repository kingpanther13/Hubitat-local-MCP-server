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
        result = self._send("tools/call", {"name": name, "arguments": arguments or {}})

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

        # Check if one already exists from a previous test group
        try:
            vdevs = self.client.call_tool("hub_list_devices", {"labelFilter": PREFIX})
            dev_list = vdevs if isinstance(vdevs, list) else vdevs.get("devices", [])
            for d in dev_list:
                lbl = d.get("label") or d.get("name") or ""
                if f"{PREFIX}Action_Switch" in lbl:
                    self._test_switch_id = str(d["id"])
                    return self._test_switch_id
        except Exception:
            pass

        # Create one
        result = self.client.call_tool("hub_manage_virtual_device", {
            "action": "create",
            "deviceType": "Virtual Switch",
            "deviceLabel": f"{PREFIX}Action_Switch",
            "confirm": True,
        })
        dni = result.get("deviceNetworkId", result.get("dni", ""))
        if dni:
            self.created_device_dnis.append(str(dni))
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
                    found_dni = str(d.get("deviceNetworkId", d.get("dni", "")))
                    if found_dni and found_dni not in self.created_device_dnis:
                        self.created_device_dnis.append(found_dni)
                    break

        self._test_switch_id = str(dev_id) if dev_id else ""
        assert self._test_switch_id, "Failed to create test switch"
        return self._test_switch_id

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
    # GROUP 1: infrastructure (4 tests)
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
        assert len(tools) == 30, f"Expected 30 tools (11 core + 19 gateways), got {len(tools)}"

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
            # No switch on the hub -- provision a throwaway virtual one so this test ALWAYS runs
            # (never skips; skips are failures). Labeled with the BAT_E2E_ prefix so the standard
            # cleanup sweep removes it.
            self.client.call_tool("hub_manage_virtual_device", {
                "action": "create",
                "deviceType": "Virtual Switch",
                "deviceLabel": f"{PREFIX}AttrProbe",
                "confirm": True,
            })
            vdevs = self.client.call_tool("hub_list_devices", {"labelFilter": PREFIX})
            dev_list = vdevs if isinstance(vdevs, list) else (vdevs.get("devices", []) if isinstance(vdevs, dict) else [])
            for d in dev_list:
                if f"{PREFIX}AttrProbe" in (d.get("label") or d.get("name") or ""):
                    switch_dev = d
                    dni = str(d.get("deviceNetworkId", d.get("dni", "")))
                    if dni:
                        self.created_device_dnis.append(dni)
                    break
            assert switch_dev is not None, \
                "no switch on the hub and could not provision a virtual one for the attribute read"
        result = self.client.call_tool("hub_get_device_attribute", {
            "deviceId": str(switch_dev["id"]),
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
        # Find the device we just created via hub_list_devices (core tool)
        vdevs = self.client.call_tool("hub_list_devices", {"labelFilter": PREFIX})
        dev_list = vdevs if isinstance(vdevs, list) else vdevs.get("devices", [])
        target = None
        for d in dev_list:
            lbl = d.get("label") or d.get("name") or ""
            if f"{PREFIX}Switch_Test" in lbl:
                target = d
                break
        if target is None:
            raise AssertionError(f"Could not find {PREFIX}Switch_Test virtual device -- the upstream create test must have failed")

        dev_id = str(target["id"])

        # Turn on
        self.client.call_tool("hub_call_device_command", {"deviceId": dev_id, "command": "on"})
        time.sleep(0.5)
        detail = self.client.call_tool("hub_get_device", {"deviceId": dev_id})
        attrs = detail.get("attributes", detail.get("currentStates", []))
        switch_val = _find_attr(attrs, "switch")
        assert switch_val == "on", f"Expected switch=on, got {switch_val}"

        # Turn off
        self.client.call_tool("hub_call_device_command", {"deviceId": dev_id, "command": "off"})
        time.sleep(0.5)
        detail = self.client.call_tool("hub_get_device", {"deviceId": dev_id})
        attrs = detail.get("attributes", detail.get("currentStates", []))
        switch_val = _find_attr(attrs, "switch")
        assert switch_val == "off", f"Expected switch=off, got {switch_val}"

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
    # GROUP 4b: native_apps (3 tests) -- the issue #137 hub_set_rule /
    # hub_set_native_app split, exercised end-to-end against the live hub.
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
    def test_set_native_app_rejects_rm_params(self) -> None:
        # The lean generic tool must REJECT Rule Machine authoring params with a
        # pointer to hub_set_rule (rather than silently dropping them). No mutation.
        try:
            self.client.call_tool("hub_manage_native_rules_and_apps", {
                "tool": "hub_set_native_app",
                "args": {"appId": 1, "addTrigger": {"capability": "Switch"}, "confirm": True},
            })
            raise AssertionError("hub_set_native_app should reject RM authoring params")
        except (McpToolError, McpError) as exc:
            assert "hub_set_rule" in str(exc), f"rejection should point to hub_set_rule: {exc}"

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
    # GROUP 5: trigger_types (6 tests)
    # -----------------------------------------------------------------------

    def _test_trigger(self, suffix: str, trigger: dict) -> None:
        """Helper: create rule with given trigger, verify, delete."""
        name = f"{PREFIX}Trigger_{suffix}"
        dev_id = self.get_first_device_id()
        # Replace placeholder device ID
        trigger = _inject_device_id(trigger, dev_id)
        rule_id = self._create_rule_and_verify(name, {
            "triggers": [trigger],
            "actions": [{"type": "log", "message": f"trigger test: {suffix}"}],
        })
        self._delete_rule_safe(rule_id)

    @test("trigger_types")
    def test_trigger_device_event(self) -> None:
        self._test_trigger("device_event", {
            "type": "device_event", "deviceId": "PLACEHOLDER", "attribute": "switch",
        })

    @test("trigger_types")
    def test_trigger_time(self) -> None:
        self._test_trigger("time", {"type": "time", "time": "08:00"})

    @test("trigger_types")
    def test_trigger_periodic(self) -> None:
        self._test_trigger("periodic", {
            "type": "periodic", "interval": 30, "unit": "minutes",
        })

    @test("trigger_types")
    def test_trigger_mode_change(self) -> None:
        self._test_trigger("mode_change", {"type": "mode_change", "mode": "Away"})

    @test("trigger_types")
    def test_trigger_sunrise(self) -> None:
        self._test_trigger("sunrise", {"type": "sunrise", "offset": 0})

    @test("trigger_types")
    def test_trigger_sunset(self) -> None:
        self._test_trigger("sunset", {"type": "sunset", "offset": -30})

    # -----------------------------------------------------------------------
    # GROUP 6: condition_types (7 tests)
    # -----------------------------------------------------------------------

    def _test_condition(self, suffix: str, condition: dict,
                        setup=None, teardown=None) -> None:
        """Create rule with given condition, verify, delete. Optional setup/teardown callables."""
        if setup:
            setup()
        try:
            name = f"{PREFIX}Cond_{suffix}"
            dev_id = self.get_first_device_id()
            condition = _inject_device_id(condition, dev_id)
            rule_id = self._create_rule_and_verify(name, {
                "triggers": [{"type": "time", "time": "03:00"}],
                "conditions": [condition],
                "actions": [{"type": "log", "message": f"condition test: {suffix}"}],
            })
            self._delete_rule_safe(rule_id)
        finally:
            if teardown:
                teardown()

    @test("condition_types")
    def test_condition_device_state(self) -> None:
        self._test_condition("device_state", {
            "type": "device_state", "deviceId": "PLACEHOLDER",
            "attribute": "switch", "operator": "==", "value": "on",
        })

    @test("condition_types")
    def test_condition_device_was(self) -> None:
        self._test_condition("device_was", {
            "type": "device_was", "deviceId": "PLACEHOLDER",
            "attribute": "switch", "operator": "==", "value": "on", "forSeconds": 300,
        })

    @test("condition_types")
    def test_condition_time_range(self) -> None:
        self._test_condition("time_range", {
            "type": "time_range", "start": "08:00", "end": "22:00",
        })

    @test("condition_types")
    def test_condition_mode(self) -> None:
        self._test_condition("mode", {
            "type": "mode", "mode": "Day",
        })

    @test("condition_types")
    def test_condition_variable(self) -> None:
        var_name = f"{PREFIX}TestVar"
        self._test_condition(
            "variable",
            {"type": "variable", "variableName": var_name, "operator": "==", "value": "1"},
            setup=lambda: self._create_variable(var_name, "String", "test"),
            teardown=lambda: self._delete_variable_safe(var_name),
        )

    @test("condition_types")
    def test_condition_days_of_week(self) -> None:
        self._test_condition("days_of_week", {
            "type": "days_of_week", "days": ["Monday", "Wednesday", "Friday"],
        })

    @test("condition_types")
    def test_condition_sun_position(self) -> None:
        self._test_condition("sun_position", {
            "type": "sun_position", "position": "up",
        })

    # -----------------------------------------------------------------------
    # GROUP 7: action_types (13 tests)
    # -----------------------------------------------------------------------

    def _test_action(self, suffix: str, actions: list[dict],
                     setup=None, teardown=None) -> None:
        """Create rule with given actions, verify, delete."""
        if setup:
            setup()
        try:
            name = f"{PREFIX}Action_{suffix}"
            dev_id = self.get_first_device_id()
            actions = [_inject_device_id(a, dev_id) for a in actions]
            rule_id = self._create_rule_and_verify(name, {
                "triggers": [{"type": "time", "time": "03:00"}],
                "actions": actions,
            })
            self._delete_rule_safe(rule_id)
        finally:
            if teardown:
                teardown()

    @test("action_types")
    def test_action_device_command(self) -> None:
        switch_id = self.get_test_switch_id()
        self._test_action("device_command", [
            {"type": "device_command", "deviceId": switch_id, "command": "on"},
        ])

    @test("action_types")
    def test_action_toggle(self) -> None:
        switch_id = self.get_test_switch_id()
        self._test_action("toggle", [
            {"type": "toggle_device", "deviceId": switch_id},
        ])

    @test("action_types")
    def test_action_set_variable(self) -> None:
        var_name = f"{PREFIX}ActionVar"
        self._test_action(
            "set_variable",
            [{"type": "set_variable", "variableName": var_name, "value": "hello"}],
            setup=lambda: self._create_variable(var_name, "String", "initial"),
            teardown=lambda: self._delete_variable_safe(var_name),
        )

    @test("action_types")
    def test_action_set_local_variable(self) -> None:
        self._test_action("set_local_variable", [
            {"type": "set_local_variable", "variableName": "localTestVar", "value": "42"},
        ])

    @test("action_types")
    def test_action_set_mode(self) -> None:
        self._test_action("set_mode", [
            {"type": "set_mode", "mode": "Day"},
        ])

    @test("action_types")
    def test_action_delay(self) -> None:
        self._test_action("delay", [
            {"type": "delay", "seconds": 5},
        ])

    @test("action_types")
    def test_action_if_then_else(self) -> None:
        dev_id = self.get_first_device_id()
        self._test_action("if_then_else", [{
            "type": "if_then_else",
            "condition": {
                "type": "device_state", "deviceId": dev_id,
                "attribute": "switch", "operator": "==", "value": "on",
            },
            "thenActions": [{"type": "log", "message": "then branch"}],
            "elseActions": [{"type": "log", "message": "else branch"}],
        }])

    @test("action_types")
    def test_action_cancel_delayed(self) -> None:
        self._test_action("cancel_delayed", [
            {"type": "cancel_delayed"},
        ])

    @test("action_types")
    def test_action_repeat(self) -> None:
        self._test_action("repeat", [{
            "type": "repeat",
            "count": 3,
            "actions": [{"type": "log", "message": "repeat iteration"}],
        }])

    @test("action_types")
    def test_action_stop(self) -> None:
        self._test_action("stop", [{"type": "stop"}])

    @test("action_types")
    def test_action_log(self) -> None:
        self._test_action("log", [
            {"type": "log", "message": "E2E test log action"},
        ])

    @test("action_types")
    def test_action_http_request(self) -> None:
        self._test_action("http_request", [
            {"type": "http_request", "method": "GET", "url": "http://example.com"},
        ])

    @test("action_types")
    def test_action_comment(self) -> None:
        self._test_action("comment", [
            {"type": "comment", "text": "This is a test comment"},
        ])

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
        # test runs, the "Install bundle the HPM way" CI step has re-delivered McpSmokeTestLib via the
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
        # issue #209: the "Install bundle the HPM way" CI step delivers the package's libraries (mcp
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
    # and the new tools work end-to-end. They ride on the CI "Install bundle the
    # HPM way" step that delivers the mcp-libraries bundle before tests run.
    # -----------------------------------------------------------------------

    @test("system_tools")
    def test_list_bundles(self) -> None:
        """hub_list_bundles lists installed bundles, and the package's libraries bundle (delivered
        by the CI HPM-install step) is present with its libraries -- proof the split libraries load
        as a bundle on the real hub."""
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
            try:
                self.client.call_tool("hub_manage_files", {
                    "tool": "hub_delete_file", "args": {"fileName": fname, "confirm": True},
                })
            except Exception as exc:
                print(f"  [WARN] bundle export cleanup: delete {fname} failed: {exc}")

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

    # -----------------------------------------------------------------------
    # GROUP 10: developer_mode (10 tests — Section 12 of BAT-v2.md + review-fix coverage)
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
        """T226: hub_delete_variable refuses when confirm flag is absent.

        Note: gateway-layer parameter validation returns the refusal as
        `isError: true` in result content (not as JSON-RPC -32602). Same
        wire-format pattern other gateway-required-param checks use.
        """
        var_name = f"{PREFIX}NO_CONFIRM_T226"
        self.client.call_tool("hub_manage_variables", {
            "tool": "hub_set_variable",
            "args": {"name": var_name, "value": "safe"},
        })
        self.created_variable_names.append(var_name)
        result = self.client.call_tool("hub_manage_variables", {
            "tool": "hub_delete_variable",
            "args": {"name": var_name},  # no confirm
        })
        assert result.get("isError") is True, f"Expected isError result for missing confirm: {result}"
        assert "confirm" in str(result.get("error", "")).lower(), f"refusal didn't mention confirm: {result}"
        # Variable should still exist
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
        4. Native RM apps (tracked + prefix sweep)
        5. Deadman install-fix throwaway (namespace+name)
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
        for app_id in list(self.created_native_app_ids):
            try:
                print(f"  Deleting tracked native app {app_id}")
                self.client.call_tool("hub_manage_rule_machine", {
                    "tool": "hub_delete_native_app", "args": {"appId": app_id, "confirm": True},
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

        # Layer 5: stranded deadman install-fix throwaway. The @test("deadman") test installs
        # 'Deadman Test Target' (namespace mcptest) + its code class; neither carries the BAT_E2E_
        # prefix, so a crash/kill between its create and finally would strand it past the other
        # sweeps. Reclaim instance(s) + code class by namespace+name (idempotent across runs).
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

        print("--- Cleanup complete ---\n")

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


def _find_attr(attrs: Any, name: str) -> str | None:
    """Extract an attribute value from various response shapes."""
    if isinstance(attrs, dict):
        return attrs.get(name)
    if isinstance(attrs, list):
        for a in attrs:
            if isinstance(a, dict):
                attr_name = a.get("name", a.get("attribute", ""))
                if attr_name == name:
                    return a.get("currentValue", a.get("value"))
    return None


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
        runner.cleanup()
        print("Cleanup-only mode complete.")
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
