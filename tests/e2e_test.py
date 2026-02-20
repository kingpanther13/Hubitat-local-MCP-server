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
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional

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
        self.endpoint = f"{self.hub_url}/apps/api/{app_id}/mcp"
        self.access_token = access_token
        self.verbose = verbose
        self._request_id = 0
        # Mask token for safe logging: show first 4 chars only
        self._masked_token = access_token[:4] + "..." if len(access_token) > 4 else "****"

    def _log(self, msg: str) -> None:
        if self.verbose:
            print(f"    [DEBUG] {msg}")

    def _send(self, method: str, params: Optional[dict] = None) -> dict:
        """Send a JSON-RPC 2.0 request and return the parsed result."""
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

        resp = requests.post(
            self.endpoint,
            params={"access_token": self.access_token},
            json=payload,
            timeout=30,
        )
        resp.raise_for_status()
        data = resp.json()

        self._log(f"<< {json.dumps(data)[:500]}")

        if "error" in data:
            raise McpError(f"JSON-RPC error: {data['error']}")

        return data.get("result", {})

    # -- MCP protocol methods ------------------------------------------------

    def initialize(self) -> dict:
        return self._send("initialize", {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "e2e-test", "version": "1.0.0"},
        })

    def list_tools(self) -> dict:
        return self._send("tools/list")

    def call_tool(self, name: str, arguments: Optional[dict] = None) -> Any:
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
        url = f"{self.hub_url}/apps/api/{self.app_id}/health"
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
        self.created_variable_names: list[str] = []

        # Cached helpers
        self._first_device_id: Optional[str] = None
        self._test_start_time: Optional[str] = None  # ISO for log check

    # -- Helpers -------------------------------------------------------------

    def get_first_device_id(self) -> str:
        """Get and cache the first device ID from list_devices."""
        if self._first_device_id is None:
            result = self.client.call_tool("list_devices")
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
            vdevs = self.client.call_tool("list_virtual_devices")
            dev_list = vdevs if isinstance(vdevs, list) else vdevs.get("devices", [])
            for d in dev_list:
                lbl = d.get("label") or d.get("name") or ""
                if f"{PREFIX}Action_Switch" in lbl:
                    self._test_switch_id = str(d["id"])
                    return self._test_switch_id
        except Exception:
            pass

        # Create one
        result = self.client.call_tool("manage_virtual_device", {
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
            vdevs = self.client.call_tool("list_virtual_devices")
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
        result = self.client.call_tool("create_rule", rule_def)
        rule_id = str(result.get("ruleId", result.get("id", "")))
        assert rule_id, f"create_rule did not return a ruleId: {result}"
        self.created_rule_ids.append(rule_id)

        # Verify creation
        fetched = self.client.call_tool("get_rule", {"ruleId": rule_id})
        assert fetched.get("name") == name or fetched.get("name", "").startswith(PREFIX), \
            f"Rule name mismatch: expected '{name}', got '{fetched.get('name')}'"
        return rule_id

    def _delete_rule_safe(self, rule_id: str) -> None:
        """Delete a rule, swallowing errors."""
        try:
            self.client.call_tool("delete_rule", {"ruleId": rule_id, "confirm": True})
        except Exception:
            pass
        if rule_id in self.created_rule_ids:
            self.created_rule_ids.remove(rule_id)

    def _create_variable(self, name: str, var_type: str = "String",
                         value: str = "test") -> None:
        """Create a hub variable via the gateway."""
        self.client.call_tool("manage_hub_variables", {
            "tool": "set_variable", "args": {"name": name, "type": var_type, "value": value},
        })
        self.created_variable_names.append(name)

    def _delete_variable_safe(self, name: str) -> None:
        try:
            self.client.call_tool("manage_hub_variables", {
                "tool": "delete_variable", "args": {"name": name},
            })
        except Exception:
            pass
        if name in self.created_variable_names:
            self.created_variable_names.remove(name)

    # -----------------------------------------------------------------------
    # GROUP 1: infrastructure (3 tests)
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
        assert len(tools) == 30, f"Expected 30 tools, got {len(tools)}"

    @test("infrastructure")
    def test_health_endpoint(self) -> None:
        data = self.client.get_health()
        assert data.get("status") == "ok", f"Health status != ok: {data.get('status')}"
        assert "version" in data, "Health response missing version"

    # -----------------------------------------------------------------------
    # GROUP 2: devices (4 tests)
    # -----------------------------------------------------------------------

    @test("devices")
    def test_list_devices(self) -> None:
        result = self.client.call_tool("list_devices")
        devices = result if isinstance(result, list) else result.get("devices", [])
        assert isinstance(devices, list), "list_devices did not return a list"
        assert len(devices) > 0, "list_devices returned empty list"
        first = devices[0]
        assert "id" in first, "Device missing 'id'"
        assert "label" in first or "name" in first, "Device missing label/name"

    @test("devices")
    def test_get_device(self) -> None:
        dev_id = self.get_first_device_id()
        result = self.client.call_tool("get_device", {"deviceId": dev_id})
        assert "attributes" in result or "currentStates" in result, \
            "get_device response missing attributes"

    @test("devices")
    def test_get_attribute(self) -> None:
        # Find a switch device
        all_devs = self.client.call_tool("list_devices")
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
            raise SkipTest("No switch device found on hub")
        result = self.client.call_tool("get_attribute", {
            "deviceId": str(switch_dev["id"]),
            "attribute": "switch",
        })
        # Result should contain the value (on/off or similar)
        assert result is not None, "get_attribute returned None"

    @test("devices")
    def test_send_command_error(self) -> None:
        try:
            self.client.call_tool("send_command", {
                "deviceId": "99999",
                "command": "on",
            })
            raise AssertionError("send_command with bogus device should have raised an error")
        except (McpToolError, McpError):
            pass  # expected — server may return JSON-RPC error or tool error

    # -----------------------------------------------------------------------
    # GROUP 3: virtual_device_lifecycle (4 tests)
    # -----------------------------------------------------------------------

    @test("virtual_device_lifecycle")
    def test_create_virtual_switch(self) -> None:
        result = self.client.call_tool("manage_virtual_device", {
            "action": "create",
            "deviceType": "Virtual Switch",
            "deviceLabel": f"{PREFIX}Switch_Test",
            "confirm": True,
        })
        # Response may be {success: true, message: "..."} without device IDs at top level
        # Track DNI if available, otherwise look it up from list_virtual_devices
        dni = result.get("deviceNetworkId", result.get("dni", ""))
        if dni:
            self.created_device_dnis.append(str(dni))
        elif result.get("success"):
            # Look up the created device to get its DNI for cleanup
            vdevs = self.client.call_tool("list_virtual_devices")
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
        # Find the device we just created via list_virtual_devices (core tool)
        vdevs = self.client.call_tool("list_virtual_devices")
        dev_list = vdevs if isinstance(vdevs, list) else vdevs.get("devices", [])
        target = None
        for d in dev_list:
            lbl = d.get("label") or d.get("name") or ""
            if f"{PREFIX}Switch_Test" in lbl:
                target = d
                break
        if target is None:
            raise SkipTest(f"Could not find {PREFIX}Switch_Test virtual device")

        dev_id = str(target["id"])

        # Turn on
        self.client.call_tool("send_command", {"deviceId": dev_id, "command": "on"})
        time.sleep(0.5)
        detail = self.client.call_tool("get_device", {"deviceId": dev_id})
        attrs = detail.get("attributes", detail.get("currentStates", []))
        switch_val = _find_attr(attrs, "switch")
        assert switch_val == "on", f"Expected switch=on, got {switch_val}"

        # Turn off
        self.client.call_tool("send_command", {"deviceId": dev_id, "command": "off"})
        time.sleep(0.5)
        detail = self.client.call_tool("get_device", {"deviceId": dev_id})
        attrs = detail.get("attributes", detail.get("currentStates", []))
        switch_val = _find_attr(attrs, "switch")
        assert switch_val == "off", f"Expected switch=off, got {switch_val}"

    @test("virtual_device_lifecycle")
    def test_list_virtual_devices(self) -> None:
        result = self.client.call_tool("list_virtual_devices")
        dev_list = result if isinstance(result, list) else result.get("devices", [])
        found = any(
            f"{PREFIX}Switch_Test" in (d.get("label") or d.get("name") or "")
            for d in dev_list
        )
        assert found, f"{PREFIX}Switch_Test not found in virtual device list"

    @test("virtual_device_lifecycle")
    def test_delete_virtual_switch(self) -> None:
        # Find DNI of our test device
        vdevs = self.client.call_tool("list_virtual_devices")
        dev_list = vdevs if isinstance(vdevs, list) else vdevs.get("devices", [])
        target_dni = None
        for d in dev_list:
            lbl = d.get("label") or d.get("name") or ""
            if f"{PREFIX}Switch_Test" in lbl:
                target_dni = str(d.get("deviceNetworkId", d.get("dni", "")))
                break
        if not target_dni:
            raise SkipTest(f"{PREFIX}Switch_Test not found for deletion")

        self.client.call_tool("manage_virtual_device", {
            "action": "delete",
            "deviceNetworkId": target_dni,
            "confirm": True,
        })
        if target_dni in self.created_device_dnis:
            self.created_device_dnis.remove(target_dni)

        # Verify it is gone
        vdevs2 = self.client.call_tool("list_virtual_devices")
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
            raise SkipTest("No rule created to get")
        result = self.client.call_tool("get_rule", {"ruleId": rule_id})
        assert result.get("name", "").startswith(PREFIX), \
            f"Rule name mismatch: {result.get('name')}"
        assert "triggers" in result or "trigger" in result, "Missing triggers in get_rule"
        assert "actions" in result, "Missing actions in get_rule"

    @test("rule_crud")
    def test_update_rule(self) -> None:
        rule_id = self._last_rule_id()
        if not rule_id:
            raise SkipTest("No rule created to update")
        self.client.call_tool("update_rule", {
            "ruleId": rule_id,
            "name": f"{PREFIX}Rule_CRUD_Updated",
        })
        fetched = self.client.call_tool("get_rule", {"ruleId": rule_id})
        assert "Updated" in fetched.get("name", ""), \
            f"Rule name not updated: {fetched.get('name')}"

    @test("rule_crud")
    def test_delete_rule(self) -> None:
        rule_id = self._last_rule_id()
        if not rule_id:
            raise SkipTest("No rule created to delete")
        self.client.call_tool("delete_rule", {"ruleId": rule_id, "confirm": True})
        if rule_id in self.created_rule_ids:
            self.created_rule_ids.remove(rule_id)
        # Verify it's gone
        try:
            self.client.call_tool("get_rule", {"ruleId": rule_id})
            raise AssertionError("get_rule should fail after deletion")
        except (McpToolError, McpError):
            pass

    def _last_rule_id(self) -> Optional[str]:
        return self.created_rule_ids[-1] if self.created_rule_ids else None

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
        result = self.client.call_tool("get_modes")
        # Should have modes list and currentMode
        assert result is not None, "get_modes returned None"
        # Accept various response shapes
        has_modes = ("modes" in result if isinstance(result, dict) else isinstance(result, list))
        assert has_modes or "currentMode" in result, \
            f"get_modes response missing modes/currentMode: {list(result.keys()) if isinstance(result, dict) else type(result)}"

    @test("system_tools")
    def test_manage_hub_variables_list(self) -> None:
        result = self.client.call_tool("manage_hub_variables", {
            "tool": "list_variables",
        })
        # Should return a list or dict with variables
        assert result is not None, "list_variables returned None"

    @test("system_tools")
    def test_get_hub_info(self) -> None:
        result = self.client.call_tool("get_hub_info")
        assert result is not None, "get_hub_info returned None"
        assert isinstance(result, dict), f"get_hub_info returned {type(result)}"

    @test("system_tools")
    def test_manage_diagnostics(self) -> None:
        result = self.client.call_tool("manage_diagnostics", {
            "tool": "get_set_hub_metrics",
            "args": {"recordSnapshot": False},
        })
        assert isinstance(result, dict), f"get_set_hub_metrics returned {type(result)}"
        assert "current" in result, "get_set_hub_metrics missing 'current'"

    @test("system_tools")
    def test_get_memory_history(self) -> None:
        result = self.client.call_tool("manage_diagnostics", {
            "tool": "get_memory_history",
        })
        assert isinstance(result, dict), f"get_memory_history returned {type(result)}"
        assert "entries" in result, "get_memory_history missing 'entries'"
        assert "summary" in result, "get_memory_history missing 'summary'"
        entries = result["entries"]
        assert isinstance(entries, list), "entries should be a list"
        if entries:
            first = entries[0]
            assert "timestamp" in first, "Entry missing 'timestamp'"
            assert "freeMemoryKB" in first, "Entry missing 'freeMemoryKB'"
            assert "cpuLoad5min" in first, "Entry missing 'cpuLoad5min'"
        summary = result["summary"]
        assert "entryCount" in summary, "Summary missing 'entryCount'"

    @test("system_tools")
    def test_force_garbage_collection(self) -> None:
        result = self.client.call_tool("manage_diagnostics", {
            "tool": "force_garbage_collection",
        })
        assert isinstance(result, dict), f"force_garbage_collection returned {type(result)}"
        assert "beforeFreeMemoryKB" in result, "Missing 'beforeFreeMemoryKB'"
        assert "afterFreeMemoryKB" in result, "Missing 'afterFreeMemoryKB'"
        assert "summary" in result, "Missing 'summary'"
        # deltaKB should exist if both memory reads succeeded
        if result["beforeFreeMemoryKB"] is not None and result["afterFreeMemoryKB"] is not None:
            assert "deltaKB" in result, "Missing 'deltaKB'"

    @test("system_tools")
    def test_manage_rooms_list(self) -> None:
        result = self.client.call_tool("manage_rooms", {
            "tool": "list_rooms",
        })
        # May be empty list, but should not error
        assert result is not None, "list_rooms returned None"

    # -----------------------------------------------------------------------
    # GROUP 10: error_verification (1 test)
    # -----------------------------------------------------------------------

    @test("error_verification")
    def test_no_hub_errors(self) -> None:
        """Soft check: look for hub errors logged during the test window."""
        try:
            result = self.client.call_tool("manage_logs", {
                "tool": "get_hub_logs",
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
    # Cleanup
    # -----------------------------------------------------------------------

    def cleanup(self, artifacts_only: bool = False) -> None:
        """Three-layer cleanup:
        1. Delete tracked artifacts (device DNIs, rule IDs, variables)
        2. Sweep for any BAT_E2E_ virtual devices
        3. Sweep for any BAT_E2E_ rules
        """
        print("\n--- Cleanup ---")

        # Layer 1: tracked artifacts
        for rule_id in list(self.created_rule_ids):
            try:
                print(f"  Deleting tracked rule {rule_id}")
                self.client.call_tool("delete_rule", {"ruleId": rule_id, "confirm": True})
            except Exception as exc:
                print(f"  [WARN] Failed to delete rule {rule_id}: {exc}")
        self.created_rule_ids.clear()

        for dni in list(self.created_device_dnis):
            try:
                print(f"  Deleting tracked device DNI={dni}")
                self.client.call_tool("manage_virtual_device", {
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
                self.client.call_tool("manage_hub_variables", {
                    "tool": "delete_variable",
                    "args": {"name": var_name},
                })
            except Exception as exc:
                print(f"  [WARN] Failed to delete variable {var_name}: {exc}")
        self.created_variable_names.clear()

        # Layer 2: sweep virtual devices with BAT_E2E_ prefix
        try:
            vdevs = self.client.call_tool("list_virtual_devices")
            dev_list = vdevs if isinstance(vdevs, list) else vdevs.get("devices", [])
            for d in dev_list:
                lbl = d.get("label") or d.get("name") or ""
                if PREFIX in lbl:
                    dni = str(d.get("deviceNetworkId", d.get("dni", "")))
                    if dni:
                        try:
                            print(f"  Sweep: deleting virtual device '{lbl}' (DNI={dni})")
                            self.client.call_tool("manage_virtual_device", {
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
            rules_result = self.client.call_tool("list_rules")
            rules = rules_result if isinstance(rules_result, list) else rules_result.get("rules", [])
            for r in rules:
                rname = r.get("name", "")
                if PREFIX in rname:
                    rid = str(r.get("id", r.get("ruleId", "")))
                    if rid:
                        try:
                            print(f"  Sweep: deleting rule '{rname}' (id={rid})")
                            self.client.call_tool("delete_rule", {"ruleId": rid, "confirm": True})
                        except Exception as exc:
                            print(f"  [WARN] Sweep delete failed for rule '{rname}': {exc}")
        except Exception as exc:
            print(f"  [WARN] Rule sweep failed: {exc}")

        print("--- Cleanup complete ---\n")

    # -----------------------------------------------------------------------
    # Run
    # -----------------------------------------------------------------------

    def run(self, filter_group: Optional[str] = None,
            filter_test: Optional[str] = None) -> bool:
        """Run tests. Returns True if all passed."""
        self._test_start_time = datetime.now(timezone.utc).isoformat()

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

        print("=" * 60)
        return total_fail == 0


# ---------------------------------------------------------------------------
# Sentinel exception for skipping tests
# ---------------------------------------------------------------------------

class SkipTest(Exception):
    """Raised to skip a test with a reason."""


# ---------------------------------------------------------------------------
# Utility functions
# ---------------------------------------------------------------------------


def _find_attr(attrs: Any, name: str) -> Optional[str]:
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
        with open(config_path, "r", encoding="utf-8") as f:
            config = json.load(f)

    # Env var overrides
    config["hub_url"] = os.environ.get("HUBITAT_HUB_URL", config.get("hub_url", ""))
    config["app_id"] = os.environ.get("HUBITAT_APP_ID", config.get("app_id", ""))
    config["access_token"] = os.environ.get("HUBITAT_ACCESS_TOKEN", config.get("access_token", ""))

    # Validate
    missing = [k for k in ("hub_url", "app_id", "access_token") if not config.get(k)]
    if missing:
        print(f"ERROR: Missing config values: {', '.join(missing)}")
        print(f"  Set via tests/e2e_config.json or env vars "
              f"HUBITAT_HUB_URL, HUBITAT_APP_ID, HUBITAT_ACCESS_TOKEN")
        if not config_path.exists():
            print(f"  Config file not found: {config_path}")
            print(f"  Copy e2e_config.example.json to e2e_config.json and fill in values.")
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
        print(f"  Check that the hub is online and the URL is correct.")
        sys.exit(1)
    except Exception as exc:
        print(f"  ERROR: Initialize failed: {exc}")
        sys.exit(1)

    all_passed = runner.run(filter_group=args.group, filter_test=args.test)
    sys.exit(0 if all_passed else 1)


if __name__ == "__main__":
    main()
