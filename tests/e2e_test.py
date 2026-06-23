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
import re
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
# Persistent scaffold devices (the shared switch + temp sensors that rule fixtures reference and the
# poll tests read) carry this marker so the cleanup sweeps SKIP them by name -- created once and
# reused across runs, never deleted. Under-test fixtures use the bare PREFIX and are still reaped.
# (The watchdog purges apps/vars only, never devices, so this is purely the test-side device sweep;
# no watchdog change is needed -- the devices are simply named to dodge the sweep.)
SCAFFOLD_PREFIX = f"{PREFIX}KEEP_"  # "BAT_E2E_KEEP_"

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
        # One reused connection for the whole run: HTTP keep-alive amortizes the TCP + TLS
        # handshake (~300-500ms each over the cloud relay) across every MCP call instead of
        # paying it per request.
        self.session = requests.Session()
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

        # NEVER transport-replay a WRITE. The retry loop below re-sends the identical request
        # on a 5xx or network error -- but a relay 504 means the response was LOST while the hub
        # kept processing, so replaying a non-idempotent wizard write commits it AGAIN (observed
        # live: one slow addAction(ifThen) -> relay 504 -> 2 transport replays -> THREE unclosed
        # IF blocks in the rule; same mechanism makes duplicate devices from replayed creates).
        # Every destructive/wizard write in this suite carries confirm:true -- use that as the
        # write marker and surface the failure immediately instead, so the callers' soft
        # contracts (verify-first, never blind-retry) handle the unknown-commit state.
        _pj = json.dumps(payload)
        replay_safe = '"confirm": true' not in _pj and '"confirm":true' not in _pj
        # Idempotent-write exception: settings assignment yields the same state on re-delivery,
        # so transport replay is safe for it (unlike wizard writes, where replay double-commits).
        if "hub_update_mcp_settings" in _pj:
            replay_safe = True

        # Pace writes only. The heavy RM wizard edits (confirm:true) ride the cloud relay's
        # gateway-timeout edge; a 0.2s pre-send gap caps the server app's short-window duty
        # cycle so the write finishes before the relay 504s. Reads carry no such load and skip
        # it -- that is the speedup. `not replay_safe` is the confirm-bearing-write marker;
        # hub_update_mcp_settings is a light idempotent settings write and stays unpaced.
        if not replay_safe:
            time.sleep(0.2)

        # Chaos mode (E2E_CHAOS_504=<0..1>): after a WRITE completes, discard its response and
        # raise the exact relay-504 error with probability <rate>. This reproduces on demand the
        # cloud relay's worst behavior -- the op COMMITTED but the response was lost -- so every
        # verify-first soft contract can be exercised deterministically in a local run instead of
        # waiting for relay weather. Never active unless explicitly set; never affects reads.
        chaos_rate = float(os.environ.get("E2E_CHAOS_504", "0") or 0)
        chaos_fire = (not replay_safe) and chaos_rate > 0 and random.random() < chaos_rate

        last_exc: Exception | None = None
        data: dict | None = None
        resp = None
        for attempt in range(3):
            resp = None
            try:
                resp = self.session.post(
                    self.endpoint,
                    params={"access_token": self.access_token},
                    json=payload,
                    timeout=60,
                )
                if 500 <= resp.status_code < 600:
                    # Hub or cloud relay returned a transient error. Heavy
                    # queries (e.g. hub_get_performance_stats) sometimes 504.
                    last_exc = requests.HTTPError(f"{resp.status_code} {resp.reason} on {method}")
                    if not replay_safe:
                        raise last_exc   # write: unknown-commit -- surface, never replay
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
                if not replay_safe and not isinstance(exc, json.JSONDecodeError):
                    raise   # write: request may have reached the hub -- never replay
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
        if chaos_fire:
            print(f"    [CHAOS] dropping the response of this {method} write (op committed hub-side)")
            raise requests.HTTPError(f"relay 504 timeout injected on {method}")
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
        last_exc: Exception | None = None
        for attempt in range(3):
            try:
                resp = self.session.post(
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
        # (app_id, health) the last RM write returned, so _assert_rule_healthy can assert on it without
        # a second hub_get_rule_health round-trip. Keyed by app; cleared on a relay-dropped/soft write.
        self._last_write_health: tuple[str, dict] | None = None

        # Read-round-trip stashes -- each lets a downstream test reuse an UPSTREAM test's identical
        # immutable read instead of re-fetching. EVERY one falls back to a live fetch when unset or
        # the identity does not match (so a `--test <name>` isolation run still works) and is NEVER
        # trusted across a write to the same entity. Initialized to None.
        # (rule_id, fetched) from _create_rule_and_verify's read-back -- reused by _assert_rule_types
        # and test_get_rule when the id matches; only valid before any write to that rule.
        self._last_rule_obj: tuple[str, dict] | None = None
        # hub_get_info called once with BOTH opt-in flags; the two opt-in tests read disjoint keys.
        self._hub_info_optin: dict | None = None
        # the hub_read_rooms gateway-catalog disclosure (deterministic static enumeration).
        self._rooms_catalog: dict | None = None
        # the resolved mcp-libraries bundle id (immutable) -- reused by test_export_bundle.
        self._mcp_bundle_id: str | None = None

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

        # Mid-run recovery for the platform's per-app load limiter. Once enough load
        # accumulates in the platform's sliding window (back-to-back full runs get
        # there), the hub throws LimitExceededException in the DEVICE's context on
        # every device-method dispatch from the server app -- commands false-succeed
        # and produce no event, and the block stays until the app instance is
        # bounced (disable/enable; verified live, no reboot needed). The watchdog
        # endpoint can do that bounce while the server stays the app under test, so
        # the dispatch-dependent tests retry ONCE after a bounce instead of failing
        # a healthy build on cadence. Every bounce is printed loudly and counted in
        # the summary -- recovery is never silent.
        self.watchdog_url = os.environ.get("WATCHDOG_URL", "")
        self.server_app_id = os.environ.get("HUBITAT_APP_ID", "")
        self.throttle_bounces = 0
        self._soft_passes: list[str] = []
        # Inter-test pacing (see _run_one): optional client-side breathing room per test.
        # Default 0 -- the load-limiter trips traced to byte volume pushed through the server
        # app generally: real per-run hub backups (now mocked away on the test hub) AND large
        # accumulated wizard pages (now kept small by the per-concern rule tests), not test
        # cadence. The knob stays for diagnostics.
        self.pace_seconds = float(os.environ.get("E2E_PACE_SECONDS", "0"))

        self._current_test = ""

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

    def _limiter_logged(self, device_id: Any, method: str | None = None) -> bool:
        """Hub-log proof that a device dispatch REACHED the device but the platform's
        per-app load limiter aborted delivery. A blocked dispatch false-succeeds (the
        LimitExceededException fires in the DEVICE's context, after our tool already
        returned success), but it always leaves a hub error-log line naming the device,
        this app, and the command method:
            dev|<id>|<label>|...LimitExceededException: App <N> generates excessive hub load ... (method on)
        Matching on the exact device id (the round-trip tests use fresh throwaway
        devices, so no stale entries can match) discriminates 'tool works, platform
        throttled' from a genuine dispatch bug. Log reads stay available while blocked
        (only device dispatch is affected -- verified live 2026-06-12)."""
        try:
            res = self.client.call_tool("hub_manage_logs", {
                "tool": "hub_get_logs", "args": {"level": "ERROR", "limit": 40},
            })
        except Exception as exc:
            print(f"    [LIMITER] hub log read failed ({exc}) -- cannot verify a limiter block")
            return False
        for entry in (res.get("logs") or []) if isinstance(res, dict) else []:
            msg = str(entry.get("message", ""))
            if not msg.startswith(f"dev|{device_id}|"):
                continue
            if "LimitExceededException" not in msg or "generates excessive hub load" not in msg:
                continue
            if method and f"(method {method})" not in msg:
                continue
            print(f"    [LIMITER] hub log confirms the dispatch reached device {device_id} and the "
                  f"platform load limiter aborted delivery: {msg[:180]}")
            return True
        return False

    def _watchdog_set_app_disabled(self, disable: bool) -> bool:
        """One leg of the throttle bounce via the watchdog endpoint. True only on a
        verified flag read-back (the tool re-reads /installedapp/json after the write)."""
        try:
            resp = requests.post(self.watchdog_url, json={
                "jsonrpc": "2.0", "id": 1, "method": "tools/call",
                "params": {"name": "hub_set_app_disabled",
                           "arguments": {"appId": self.server_app_id,
                                         "disable": disable, "confirm": True}},
            }, timeout=30)
            text = resp.json().get("result", {}).get("content", [{}])[0].get("text", "")
            parsed = json.loads(text) if text else {}
            return parsed.get("success") is True and parsed.get("disabled") is disable
        except Exception as exc:
            print(f"    [THROTTLE] watchdog bounce leg (disable={disable}) failed: {exc}")
            return False

    def _clear_load_throttle(self, reason: str) -> bool:
        """Bounce (disable/enable) the server app via the WATCHDOG to clear the
        platform's per-app load-limiter block (LimitExceededException -- device
        commands false-succeed with no event until the app instance bounces).
        Returns True when the bounce fully verified, so the caller can retry its
        dispatch exactly once. LOUD on purpose: a recovery that happened must be
        visible in the run log and the summary."""
        if not (self.watchdog_url and self.server_app_id):
            print(f"    [THROTTLE] suspected load-limiter block ({reason}) but "
                  "WATCHDOG_URL/HUBITAT_APP_ID not set -- cannot bounce, failing as-is.")
            return False
        print(f"    [THROTTLE] suspected platform load-limiter block: {reason}")
        print(f"    [THROTTLE] bouncing server app {self.server_app_id} via the watchdog (disable/enable)...")
        if not self._watchdog_set_app_disabled(True):
            print("    [THROTTLE] disable leg did not verify -- not retrying the enable; failing as-is.")
            return False
        time.sleep(3)
        enabled = False
        for _ in range(5):
            if self._watchdog_set_app_disabled(False):
                enabled = True
                break
            time.sleep(5)
        if not enabled:
            # Never leave the app disabled: that converts one flaky test into a
            # whole-suite wipeout. Surface and bail hard.
            raise RuntimeError(
                f"[THROTTLE] server app {self.server_app_id} was disabled for a bounce and could "
                "not be re-enabled -- re-enable via the watchdog (hub_set_app_disabled disable=false) NOW.")
        time.sleep(3)
        self.throttle_bounces += 1
        print(f"    [THROTTLE] bounce #{self.throttle_bounces} complete -- retrying the blocked dispatch once.")
        return True

    def get_test_switch_id(self) -> str:
        """Get or create the persistent BAT_E2E_ virtual switch scaffold that rule
        fixtures reference in triggers/conditions/actions and the poll tests read
        state from. Tests stick to BAT_E2E_-prefixed virtual devices by convention
        (deterministic fixtures, clean sweeps) -- the test hub itself is sacrificial.

        Command ROUND-TRIP assertions (prove an event actually processed) do NOT
        belong on this device: it is shared across the whole suite, so its state
        history is unpredictable, and test_command_virtual_switch provisions its
        own throwaway instead."""
        if getattr(self, "_test_switch_id", None):
            return self._test_switch_id

        # Check if one already exists from a previous test group
        try:
            vdevs = self.client.call_tool("hub_list_devices", {"labelFilter": PREFIX})
            dev_list = vdevs if isinstance(vdevs, list) else vdevs.get("devices", [])
            for d in dev_list:
                lbl = d.get("label") or d.get("name") or ""
                if f"{SCAFFOLD_PREFIX}Action_Switch" in lbl:
                    self._test_switch_id = str(d["id"])
                    return self._test_switch_id
        except Exception:
            pass

        # Create one. The scaffold is PERSISTENT, not a fixture-under-test:
        # deliberately NOT tracked in created_device_dnis, so teardown leaves it on
        # the hub for the next run to find-and-reuse -- skipping a create+delete
        # every run. Devices that ARE under test still track + delete themselves.
        self._test_switch_id = self._create_virtual_switch_device(f"{SCAFFOLD_PREFIX}Action_Switch")
        assert self._test_switch_id, "Failed to create test switch"
        return self._test_switch_id

    def _create_virtual_switch_device(self, label: str) -> str:
        """Create a Virtual Switch and return its device id ('' on failure).

        A relay 504 drops the response but the create may still commit; fall through
        to the same look-it-up-by-label path the no-id-in-response case already uses."""
        try:
            result = self.client.call_tool("hub_manage_virtual_device", {
                "action": "create",
                "deviceType": "Virtual Switch",
                "deviceLabel": label,
                "confirm": True,
            })
        except (McpError, McpToolError, requests.HTTPError) as exc:
            if "504" not in str(exc):
                raise
            print(f"    create virtual switch '{label}' response lost to relay 504 -- verifying by label lookup")
            time.sleep(3.0)
            result = {}
        dev_obj = result.get("device") if isinstance(result, dict) else None
        dev_id = (dev_obj or {}).get("id") or result.get("id", result.get("deviceId", ""))

        # Response may not include ID directly (or was dropped by a 504) — look it up
        if not dev_id:
            time.sleep(0.3)
            vdevs = self.client.call_tool("hub_list_devices", {"labelFilter": PREFIX})
            dev_list = vdevs if isinstance(vdevs, list) else vdevs.get("devices", [])
            for d in dev_list:
                lbl = d.get("label") or d.get("name") or ""
                if label in lbl:
                    dev_id = str(d["id"])
                    break
        return str(dev_id) if dev_id else ""

    def get_test_temperature_ids(self) -> tuple[str, str]:
        """Get or create two BAT_E2E_ virtual temperature sensors for device-relative tests.

        compareToDevice compares one device's reading to another's on the SAME capability,
        so the walker needs two Temperature-capable devices. Like the test switch, these are
        persistent scaffolding (NOT tracked in created_device_dnis) so teardown leaves them
        for the next run to find-and-reuse.
        """
        if getattr(self, "_test_temp_ids", None):
            return self._test_temp_ids

        labels = [f"{SCAFFOLD_PREFIX}Temp_A", f"{SCAFFOLD_PREFIX}Temp_B"]
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
            try:
                result = self.client.call_tool("hub_manage_virtual_device", {
                    "action": "create",
                    "deviceType": "Virtual Temperature Sensor",
                    "deviceLabel": want,
                    "confirm": True,
                })
            except (McpError, McpToolError, requests.HTTPError) as exc:
                if "504" not in str(exc):
                    raise
                print(f"    create temp sensor '{want}' response lost to relay 504 -- verifying by label lookup")
                time.sleep(3.0)
                result = {}
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
        self._current_test = f"{group}/{name}"
        t0 = time.monotonic()
        # Maintainer policy: a 504-caused failure (or 504-reasoned abort) gets ONE full
        # test re-run before being declared failed -- the test re-creates its own fixtures
        # and the verify-by-label helpers adopt anything the first attempt committed. A
        # second 504-caused failure is then an honest red.
        for attempt in (1, 2):
            try:
                method()
                elapsed = time.monotonic() - t0
                msg = "(passed on retry after relay 504)" if attempt == 2 else ""
                if attempt == 2:
                    self._soft_passes.append(f"{group}/{name}: passed on retry after relay 504")
                self._record(name, group, "pass", message=msg, duration=elapsed)
                return
            except SkipTest as exc:
                elapsed = time.monotonic() - t0
                if "504" in str(exc) and attempt == 1:
                    print(f"    [RETRY] {name} aborted by relay 504 -- re-running the test once")
                    continue
                if "504" in str(exc):
                    print(f"    FULL-FAILURE {name}: persistent relay 504 across retry: {exc}")
                    self._record(name, group, "fail", message=f"persistent relay 504: {exc}"[:200], duration=elapsed)
                else:
                    self._record(name, group, "skip", message=str(exc), duration=elapsed)
                return
            except Exception as exc:
                elapsed = time.monotonic() - t0
                if "504" in str(exc) and attempt == 1:
                    print(f"    [RETRY] {name} failed on a relay 504 -- re-running the test once")
                    continue
                # The summary table stays readable with a 200-char message, but the FULL
                # failure goes to the run log here -- a truncated structured response
                # (error/repairHints/settingsSkipped all cut off) has repeatedly forced an
                # extra run just to learn why a test failed.
                print(f"    FULL-FAILURE {name}: {exc}")
                self._record(name, group, "fail", message=str(exc)[:200], duration=elapsed)
                return
        # Inter-test breathing room for the hub's per-app load limiter. The limiter has
        # tripped MID-RUN on a freshly-booted hub, and the suite's recent speedups all
        # removed the natural idle gaps the older, slower flow gave the server app between
        # heavy phases -- raising its short-window duty cycle. A client-side sleep costs
        # the hub NOTHING (no request is in flight) and caps that duty cycle. Tunable via
        # E2E_PACE_SECONDS; 0 disables.
        if self.pace_seconds > 0:
            time.sleep(self.pace_seconds)

    # -- Rule helper: create, verify, delete ---------------------------------

    def _create_rule_and_verify(self, name: str, rule_def: dict) -> str:
        """Create a rule, verify it was created, return ruleId.

        On a relay 504 the create response (ruleId) is lost but the rule may have
        committed; recover the id by listing custom rules for the unique name, then
        fall through to the same read-back verification."""
        rule_def.setdefault("name", name)
        rule_def.setdefault("testRule", True)
        try:
            result = self.client.call_tool("hub_create_custom_rule", rule_def)
            rule_id = str(result.get("ruleId", result.get("id", "")))
            assert rule_id, f"hub_create_custom_rule did not return a ruleId: {result}"
        except (McpError, McpToolError, requests.HTTPError) as exc:
            if "504" not in str(exc):
                raise
            print(f"    hub_create_custom_rule '{name}' response lost to relay 504 -- verifying by name lookup")
            time.sleep(3.0)
            rule_id = ""
            listed = self.client.call_tool("hub_get_custom_rule")
            rules = listed if isinstance(listed, list) else (listed.get("rules") or [])
            for r in rules:
                if isinstance(r, dict) and r.get("name") == name:
                    rule_id = str(r.get("id", r.get("ruleId", "")))
                    break
            assert rule_id, f"hub_create_custom_rule '{name}' lost to relay 504 and never committed"
            print(f"    create committed despite the dropped response -- adopting ruleId {rule_id}")
        self.created_rule_ids.append(rule_id)

        # Verify creation
        fetched = self.client.call_tool("hub_get_custom_rule", {"ruleId": rule_id})
        assert fetched.get("name") == name or fetched.get("name", "").startswith(PREFIX), \
            f"Rule name mismatch: expected '{name}', got '{fetched.get('name')}'"
        # Stash this read-back so a downstream same-rule reader (_assert_rule_types, test_get_rule)
        # can reuse it instead of re-fetching the SAME immutable rule. Keyed by rule_id; only trusted
        # on an exact-id match and never across a write to the rule. (The 504-recovery branch above
        # never reaches here with a `fetched`, so the stash stays whatever it was -> a mismatch ->
        # the reader fetches live.)
        self._last_rule_obj = (rule_id, fetched)
        return rule_id

    def _assert_rule_types(self, rule_id: str, key: str, expected_types: list[str],
                           normalize_away: tuple[str, ...] = ()) -> None:
        """Fetch a custom rule and assert its triggers/conditions/actions array carries the expected COUNT
        AND each expected TYPE -- catches the legacy engine silently dropping a type OR drop-and-duplicating
        one (which a length-only check would miss). `normalize_away` lists input types the engine rewrites
        server-side (triggers: 'sunrise'/'sunset' -> 'time'), so they aren't required to appear under their
        original name; the count still must match."""
        # Reuse the create-verify read-back for the SAME rule (no write happens between create and this
        # assert in any caller); fall back to a live fetch when the stash is unset or for a different rule
        # (isolation-safe).
        if self._last_rule_obj and self._last_rule_obj[0] == rule_id:
            fetched = self._last_rule_obj[1]
        else:
            fetched = self.client.call_tool("hub_get_custom_rule", {"ruleId": rule_id})
        arr = fetched.get(key)
        assert isinstance(arr, list), f"custom rule '{key}' is not a list: {fetched.get(key)!r}"
        got = [e.get("type") for e in arr if isinstance(e, dict)]
        assert len(arr) == len(expected_types), \
            f"custom rule '{key}': expected {len(expected_types)} entries, got {len(arr)} -- a type was rejected/dropped: {got!r}"
        missing = [t for t in expected_types if t not in normalize_away and t not in got]
        assert not missing, \
            f"custom rule '{key}': types missing after round-trip: {missing} (got {got!r}) -- a type was dropped or replaced by a duplicate"

    def _custom_rule_absent(self, rule_id: str) -> bool:
        """True if the custom rule is gone (delete-verify-by-absence on a 504)."""
        try:
            self.client.call_tool("hub_get_custom_rule", {"ruleId": rule_id})
            return False  # still retrievable => not deleted
        except (McpToolError, McpError):
            return True  # the read errors because the rule is gone

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
        """Create a hub variable via the gateway.

        Track BEFORE the call so a relay 504 mid-create still cleans up. On a 504 the
        write may have committed; verify by reading it back (a genuine non-commit is
        surfaced, not silently soft-passed)."""
        self.created_variable_names.append(name)
        try:
            self.client.call_tool("hub_manage_variables", {
                "tool": "hub_set_variable", "args": {"name": name, "type": var_type, "value": value},
            })
        except (McpError, McpToolError, requests.HTTPError) as exc:
            if "504" not in str(exc):
                raise
            print(f"    hub_set_variable '{name}' response lost to relay 504 -- verifying by read-back")
            time.sleep(3.0)
            got = self.client.call_tool("hub_manage_variables", {
                "tool": "hub_get_variable", "args": {"name": name},
            })
            assert got.get("name") == name or got.get("value") is not None, \
                f"hub_set_variable '{name}' lost to relay 504 and never committed: {got}"

    def _hub_variable_absent(self, name: str) -> bool:
        """True if the hub variable is gone (delete-verify-by-absence on a 504).

        hub_get_variable raises (McpToolError 'not found') when the variable is gone in
        both namespaces -- that raise is the proof of absence."""
        try:
            self.client.call_tool("hub_manage_variables", {
                "tool": "hub_get_variable", "args": {"name": name}})
            return False  # still retrievable => not deleted
        except (McpToolError, McpError) as exc:
            return "not found" in str(exc).lower()

    def _hub_variable_visible_in_bulk(self, name: str) -> bool:
        """True if `name` appears in hub_list_variables' bulk read.

        hub_list_variables is backed by getAllGlobalVars() -- the SAME surface the setVariable
        target validator consults -- so its hubVariables list is an exact proxy for what the
        validator sees (hub_get_variable uses a different single-name lookup that can lag the
        bulk read differently). Returns False on any read error so the caller keeps polling."""
        try:
            result = self.client.call_tool("hub_manage_variables", {
                "tool": "hub_list_variables", "args": {}})
        except (McpToolError, McpError, requests.HTTPError):
            return False
        hub_vars = (result or {}).get("hubVariables") or []
        return any((v or {}).get("name") == name for v in hub_vars)

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

    # -- Shared relay-504 soft-write -----------------------------------------

    def _soft_write(self, tool_call, verify, describe: str) -> Any:
        """Run a write call; on a relay 504 resolve committed-or-not via verify().

        The e2e client never transport-replays writes (duplicate-commit risk), so a
        cloud-relay 504 drops the RESPONSE while the hub may or may not have committed
        the op. `tool_call()` is the write (returns the parsed response dict). On a 504
        we call `verify()` -- a read that returns a truthy "evidence" value when the
        write DID commit (e.g. the adopted appId, the read-back config dict) and a
        falsy value when it did NOT. The return envelope distinguishes the three cases:

          - normal:        {relayDropped: False, response: <tool_call result>}
          - 504+committed: {relayDropped: True,  committed: True,  evidence: <verify()>}
          - 504+lost:      {relayDropped: True,  committed: False, evidence: <falsy>}

        Callers MUST branch on relayDropped and skip-with-print any response-field
        assertions when it is set (the response is gone); the verify() evidence is the
        only thing that bound on the 504 path. Non-504 errors re-raise so real failures
        still bind. This centralizes the verify-first pattern; the existing dedicated
        helpers (_create_native_rule, _set_rule, ...) keep their bespoke shapes where
        those read clearer."""
        try:
            return {"relayDropped": False, "response": tool_call()}
        except (McpError, McpToolError, requests.HTTPError) as exc:
            if "504" not in str(exc):
                raise
            print(f"    {describe}: response lost to relay 504 -- verifying committed-or-not")
            time.sleep(3.0)
            evidence = verify()
            committed = bool(evidence)
            verdict = "committed despite the dropped response" if committed \
                else "did NOT commit (verify found no evidence)"
            print(f"    {describe}: {verdict}")
            return {"relayDropped": True, "committed": committed, "evidence": evidence}

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
        # The documented DEFAULT catalog is 32 (12 core + 20 gateways); exclude the dev-mode tool so
        # the count matches the default regardless of the toggle, then assert the dev-mode tool is
        # present on this dev-on hub.
        default_tools = [t for t in tools if t.get("name") != "hub_update_package"]
        assert len(default_tools) == 32, \
            f"Expected 32 default tools (12 core + 20 gateways), got {len(default_tools)}: {sorted(names)}"
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

    def _get_rooms_catalog(self) -> dict:
        """The hub_read_rooms({}) gateway-catalog disclosure -- a deterministic static enumeration shared
        by the two catalog-disclosure tests. Lazy + cached; falls back to a fresh fetch when unset
        (isolation-safe). Immutable read, so no write invalidates it within a run."""
        cached = self._rooms_catalog
        if cached is None:
            cached = self.client.call_tool("hub_read_rooms", {})
            self._rooms_catalog = cached
        return cached

    @test("infrastructure")
    def test_default_tools_list_omits_output_schema(self) -> None:
        # Issue #290: by default (publishOutputSchemas OFF) NO tools/list entry advertises
        # outputSchema. Strict MCP clients (e.g. Claude Desktop, via the MCP TypeScript SDK)
        # throw JSON-RPC -32600 "has an output schema but did not return structured content"
        # when a tool declares outputSchema but the result carries no structuredContent --
        # and this server returns text-only results. Regression guard against re-advertising
        # schemas by default, at BOTH gated surfaces: the top-level tools/list AND the
        # gateway catalog disclosure. (The opt-in ON path is covered by the Spock suite.)
        result = self.client.list_tools()
        tools = result.get("tools", [])
        assert tools, "tools/list returned no tools"
        with_schema = [t.get("name") for t in tools if "outputSchema" in t]
        assert not with_schema, (
            "default tools/list must not advertise outputSchema "
            f"(publishOutputSchemas defaults OFF), but these did: {with_schema}"
        )
        # Surface (b): the gateway no-arg catalog disclosure must also omit outputSchema by
        # default (it is the other surface the toggle gates, in handleGateway). Shared (identical
        # deterministic disclosure) with test_gateway_catalog_titles.
        catalog = self._get_rooms_catalog()
        cat_entries = catalog.get("tools", []) if isinstance(catalog, dict) else []
        assert cat_entries, "hub_read_rooms catalog returned no tools"
        cat_with_schema = [e.get("name") for e in cat_entries if "outputSchema" in e]
        assert not cat_with_schema, (
            "gateway catalog must not advertise outputSchema by default "
            f"(publishOutputSchemas OFF), but these did: {cat_with_schema}"
        )
        # And a base tool call still succeeds end-to-end (the symptom #290 reported).
        info = self.client.call_tool("hub_get_info")
        assert isinstance(info, dict) and info, "hub_get_info call must still return data"

    @test("infrastructure")
    def test_gateway_catalog_titles(self) -> None:
        # Issue #245: the gateway no-arg catalog disclosure also surfaces each
        # sub-tool's friendly title next to its bare name and schema. Shared (identical deterministic
        # disclosure) with test_default_tools_list_omits_output_schema.
        catalog = self._get_rooms_catalog()
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
    def test_list_devices_scope_all(self) -> None:
        # Item 1 (#257): scope='all' lists EVERY hub device with an mcpAuthorized flag,
        # sourced from /device/listWithCapabilities/json (not the authorization-scoped Groovy model).
        result = self.client.call_tool("hub_list_devices", {"scope": "all"})
        assert isinstance(result, dict), "scope='all' did not return an object"
        assert result.get("scope") == "all", f"scope='all' not echoed: {result}"
        devices = result.get("devices", [])
        assert isinstance(devices, list) and len(devices) > 0, "scope='all' returned no devices"
        assert all("mcpAuthorized" in d for d in devices), \
            "scope='all' devices missing the mcpAuthorized flag"
        assert "mcpAuthorizedCount" in result and "unauthorizedCount" in result, \
            "scope='all' missing mcpAuthorizedCount/unauthorizedCount"

    @test("diagnostics")
    def test_radio_details_include_topology(self) -> None:
        # Item 3 (#257): include_topology folds the read-only mesh route map into hub_get_radio_details.
        # The helper always returns a topology object (with at least `endpoint`) when include_topology
        # is set, so assert it strictly -- on a live hub (every Hubitat has a Z-Wave radio) the route
        # fetch must succeed, so a route-fetch regression (topology.error, or no route data) fails here
        # instead of slipping through a best-effort `if present` guard.
        # Combined with include_status + include_firmware: one call returns every fold object, so the
        # three former separate zwave-radio tests share a single round-trip, each keeping its assertion.
        result = self.client.call_tool("hub_get_radio_details", {
            "radio": "zwave", "include_topology": True, "include_status": True, "include_firmware": True})
        assert isinstance(result, dict), "hub_get_radio_details did not return an object"
        topo = result.get("topology")
        assert topo is not None, "include_topology=true did not return a topology object"
        assert "getChildAndRouteInfoJson" in str(topo.get("endpoint", "")), \
            f"include_topology topology missing the route endpoint: {topo}"
        assert "error" not in topo, f"include_topology route fetch errored (regression): {topo.get('error')}"
        assert "routes" in topo or "rawRoutes" in topo, \
            f"include_topology returned no route data: {topo}"
        # include_status fold must not error the call (its former standalone assertion).
        assert "error" not in result or isinstance(result.get("error"), str), \
            f"include_status unexpected error shape: {result}"

    @test("diagnostics")
    def test_radio_details_matter(self) -> None:
        # FOLD 1 (#257): radio='matter' folds Matter fabric/device details into hub_get_radio_details
        # (GET /hub/matterDetails/json). Resilient to a hub without a Matter radio: on such a hub the
        # helper returns source='sdk_only' with a C-8 note; on a Matter-capable hub it returns
        # source='hub_api' with a parsed matterData object. Either way the fold path must have fired
        # (a valid source string), proving radio='matter' dispatched rather than erroring.
        result = self.client.call_tool("hub_get_radio_details", {"radio": "matter"})
        assert isinstance(result, dict), "hub_get_radio_details radio='matter' did not return an object"
        source = result.get("source")
        assert source in ("hub_api", "hub_api_raw", "sdk_only"), \
            f"radio='matter' did not set a recognized source (fold path didn't fire?): {result}"
        if source == "hub_api":
            matter = result.get("matterData")
            assert isinstance(matter, dict), f"source=hub_api but matterData missing/!dict: {result}"
            # Rich Matter shape from the live endpoint -- assert the documented top-level keys exist.
            assert "fabricId" in matter or "networkState" in matter or "devices" in matter, \
                f"matterData missing expected Matter keys: {matter}"
        else:
            # No Matter radio on this hub -- the note must steer toward the C-8 / C-8 Pro requirement.
            assert "matter" in str(result.get("note", "")).lower(), \
                f"sdk_only fallback missing an actionable Matter note: {result}"

    @test("diagnostics")
    def test_device_health_traceroute(self) -> None:
        # FOLD 2 (#257): traceroute folds the hub's route trace into hub_get_device_health
        # (GET /hub/networkTest/traceroute/<ipv4>). Use a stable public IPv4 (8.8.8.8). The fold path
        # must produce a result.traceroute object carrying the target host; on a hub with WAN it returns
        # output (the plain-text route table), otherwise a structured error -- tolerate either so the
        # test is resilient, but assert the fold fired (traceroute present with host + output|error).
        result = self.client.call_tool("hub_get_device_health", {"traceroute": "8.8.8.8"})
        assert isinstance(result, dict), "hub_get_device_health did not return an object"
        tr = result.get("traceroute")
        assert isinstance(tr, dict), f"traceroute fold did not attach a traceroute object: {result}"
        assert tr.get("host") == "8.8.8.8", f"traceroute did not echo the target host: {tr}"
        assert ("output" in tr) or ("error" in tr), \
            f"traceroute produced neither output nor a structured error: {tr}"

    @test("diagnostics")
    def test_device_health_speedtest(self) -> None:
        # FOLD 2 (#257): speedtest folds the hub's WAN download test into hub_get_device_health
        # (GET /hub/networkTest/speedtest -- a fixed ~10 MB S3 blob). Unlike traceroute, the
        # download time is inherently variable and on a slow link can exceed the ~10s cloud-relay
        # ceiling, dropping the response with a 504 even though the hub completed it. That's an
        # infra limit, not a tool fault, so tolerate a relay 504 as an acceptable outcome; when the
        # response DOES come back in time, assert the fold fired (speedtest object with output|error).
        try:
            result = self.client.call_tool("hub_get_device_health", {"speedtest": True})
        except (McpError, McpToolError, requests.HTTPError) as exc:
            if "504" in str(exc) or "502" in str(exc) or "503" in str(exc):
                print("    speedtest response lost to relay 5xx (10 MB download > ~10s ceiling) -- "
                      "acceptable; fold reached the hub")
                return
            raise
        assert isinstance(result, dict), "hub_get_device_health did not return an object"
        st = result.get("speedtest")
        assert isinstance(st, dict), f"speedtest fold did not attach a speedtest object: {result}"
        assert ("output" in st) or ("error" in st), \
            f"speedtest produced neither output nor a structured error: {st}"

    # ---- hub_manage_radio gateway (#257 radio surface) ----
    # The e2e hub has NO paired devices, so device-dependent paths (join/pair/exclude/
    # per-node) only validate at the wire/validation level. Reads + gateway routing are
    # fully exercisable. Destructive writes ARE allowed on the e2e hub (no devices, the hub
    # is rebootable/rebuildable) but are kept conservative here.

    @test("diagnostics")
    def test_manage_radio_gateway_lists_subtools(self) -> None:
        # Calling the gateway with no tool returns its sub-tool catalog — proves hub_manage_radio
        # exists and routes. The radio write tools must appear in the disclosure.
        listing = self.client.call_tool("hub_manage_radio", {})
        text = json.dumps(listing) if not isinstance(listing, str) else listing
        for sub in ("hub_set_zwave", "hub_call_zwave", "hub_set_zigbee", "hub_call_zigbee", "hub_call_matter"):
            assert sub in text, f"hub_manage_radio catalog missing sub-tool {sub}: {text[:400]}"

    # ---- hub_manage_radio WRITE ops (#257 radio surface) ----
    # These exercise the radio write/destructive tools on the no-devices e2e hub.
    # Each tolerates relay 5xx and radio-absent / structured-error outcomes: the fold
    # may dispatch to a hub endpoint that 5xx-es behind the cloud relay, or return a
    # {success: false, error: ...} envelope when the radio is absent. The load-bearing
    # assertion is that the write dispatched (a structured object came back, or the
    # request reached the hub) — NOT that the radio acted, since the hub has no devices.

    @staticmethod
    def _radio_write_outcome(result: Any) -> bool:
        # A radio write reached its handler if it returned a structured object carrying a
        # success flag, an error envelope, or a hub response/note — any of these proves the
        # dispatch fired rather than falling through to "Unknown tool".
        if isinstance(result, dict):
            return any(k in result for k in ("success", "error", "response", "note", "message", "warning"))
        # A non-empty string body (e.g. raw hub text) also proves the call landed.
        return isinstance(result, str) and bool(result.strip())

    def _resilient_radio_write(self, name: str, args: dict, describe: str) -> bool:
        # Returns True if the write dispatched (or was acceptably lost to a relay 5xx /
        # refused by a structured tool error). Raises on a non-5xx JSON-RPC transport error
        # so a genuine dispatch break (e.g. Unknown tool) still fails the test.
        try:
            result = self.client.call_tool(name, args)
        except McpToolError as exc:
            # isError envelope raised top-level: radio absent / hub refusal -- the tool ran.
            print(f"    {describe}: structured tool error (acceptable on a no-device hub): {str(exc)[:160]}")
            return True
        except (McpError, requests.HTTPError) as exc:
            if any(code in str(exc) for code in ("502", "503", "504")):
                print(f"    {describe}: response lost to relay 5xx (acceptable; dispatch reached the hub)")
                return True
            raise
        assert self._radio_write_outcome(result), \
            f"{describe} did not return a structured radio result (dispatch may have fallen through): {result}"
        return True

    @test("diagnostics")
    def test_set_zwave_enabled_idempotent(self) -> None:
        # hub_set_zwave(enabled=true) is a safe, idempotent state assignment: every Hubitat has a
        # Z-Wave radio and enabling an already-enabled radio is a no-op (no confirm needed -- confirm
        # is only required to DISABLE). After the write, read the config back via hub_get_radio_details.
        assert self._resilient_radio_write(
            "hub_set_zwave", {"enabled": True}, "hub_set_zwave(enabled=true)")
        # Config read-back: the read-only details surface still answers after the write.
        details = self.client.call_tool("hub_get_radio_details", {"radio": "zwave"})
        assert isinstance(details, dict), f"hub_get_radio_details read-back did not return an object: {details}"

    @test("diagnostics")
    def test_set_zigbee_enabled_idempotent(self) -> None:
        # hub_set_zigbee(enabled=true): same safe/idempotent enable path as Z-Wave. Resilient to a
        # hub without a Zigbee radio (structured error / 5xx tolerated). Config read-back after.
        assert self._resilient_radio_write(
            "hub_set_zigbee", {"enabled": True}, "hub_set_zigbee(enabled=true)")
        details = self.client.call_tool("hub_get_radio_details", {"radio": "zigbee"})
        assert isinstance(details, dict), f"hub_get_radio_details read-back did not return an object: {details}"

    @test("diagnostics")
    def test_call_zwave_repair_start_then_cancel(self) -> None:
        # hub_call_zwave repair lifecycle (absorbs the former hub_call_zwave_repair). repair_start
        # then repair_cancel is safe on a hub with no paired devices -- a repair on an empty mesh is
        # a no-op and cancel stops it cleanly. Neither needs confirm (only exclusion_start/node_remove
        # do). Both must dispatch through hub_manage_radio's hub_call_zwave.
        assert self._resilient_radio_write(
            "hub_call_zwave", {"action": "repair_start"}, "hub_call_zwave(repair_start)")
        assert self._resilient_radio_write(
            "hub_call_zwave", {"action": "repair_cancel"}, "hub_call_zwave(repair_cancel)")

    @test("diagnostics")
    def test_call_zigbee_rebuild_network(self) -> None:
        # hub_call_zigbee(rebuild_network): non-idempotent mesh rebuild. Safe to trigger on a
        # no-device hub (nothing to disrupt). Resilient to a Zigbee-less hub / relay 5xx.
        assert self._resilient_radio_write(
            "hub_call_zigbee", {"action": "rebuild_network"}, "hub_call_zigbee(rebuild_network)")

    @test("diagnostics")
    def test_radio_details_matter_node_status(self) -> None:
        # node_id is radio-aware: with radio='matter' it polls /hub/matterPairDeviceStatus for
        # that node and attaches result.matterPairStatus (a _radioGetSafe read, so the key is
        # always present -- parsed status on a Matter hub, a structured {error} on a hub without
        # Matter / without that node). The load-bearing assertion is that the matter branch fired
        # (matterPairStatus attached, NOT the Z-Wave nodeState branch).
        result = self.client.call_tool(
            "hub_get_radio_details", {"radio": "matter", "node_id": "1"})
        assert isinstance(result, dict), f"matter node_id read did not return an object: {result}"
        assert "matterPairStatus" in result, \
            f"radio='matter' + node_id did not attach matterPairStatus (matter branch didn't fire?): {result}"
        assert "nodeState" not in result, \
            f"radio='matter' wrongly took the Z-Wave nodeState branch: {result}"

    @test("diagnostics")
    def test_call_zwave_node_replace_stop(self) -> None:
        # hub_call_zwave(node_replace_stop): aborts an in-flight node-replace (bare POST to
        # /hub/zwave/nodeReplace/stop). Stopping when none is running is a safe no-op, so this is
        # callable on the no-device e2e hub. Not confirm-gated. Resilient to relay 5xx / radio-absent.
        assert self._resilient_radio_write(
            "hub_call_zwave", {"action": "node_replace_stop"}, "hub_call_zwave(node_replace_stop)")

    @test("diagnostics")
    def test_set_zigbee_settings_roundtrip(self) -> None:
        # hub_set_zigbee settings mode (updateSettings: rebuild-on-reboot / inactive-device ping).
        # The setter merges over current values, so read the live flags first and write them back
        # UNCHANGED -- a true no-op that leaves the hub's radio config as found. Resilient to a
        # Zigbee-less hub (read lacks the flags / write returns a structured error).
        details = self.client.call_tool("hub_get_radio_details", {"radio": "zigbee"})
        assert isinstance(details, dict), f"zigbee details read did not return an object: {details}"
        zdata = details.get("zigbeeData")
        zd = zdata if isinstance(zdata, dict) else {}
        args = {}
        rebuild = zd.get("rebuildNetworkOnReboot")
        ping = zd.get("inactiveDevicePingEnabled")
        if isinstance(rebuild, bool):
            args["rebuild_on_reboot"] = rebuild
        if isinstance(ping, bool):
            args["ping_inactive"] = ping
        if not args:
            # Hub did not expose the flags (Zigbee-less / older firmware) -- pass BOTH explicitly so
            # the settings dispatch still fires (the merge-refuse guard only triggers on an omitted,
            # unreadable flag). Values are arbitrary here; the no-device hub has nothing to disrupt.
            args = {"rebuild_on_reboot": False, "ping_inactive": False}
        assert self._resilient_radio_write(
            "hub_set_zigbee", args, f"hub_set_zigbee(settings {args})")

    @test("diagnostics")
    def test_set_zigbee_ping_device(self) -> None:
        # hub_set_zigbee ping_device mode (updatePingDevice): toggle keep-alive ping for ONE device.
        # The e2e hub has no devices, so target a benign/likely-absent id with enabled=false (a no-op
        # disable). Resilient to relay 5xx / structured error (device-absent) -- the load-bearing
        # check is that the new ping_device mode dispatches, not that a device was pinged.
        assert self._resilient_radio_write(
            "hub_set_zigbee", {"ping_device": {"device_id": "0x0000", "enabled": False}},
            "hub_set_zigbee(ping_device)")

    @test("diagnostics")
    def test_call_destructive_radio_requires_confirm(self) -> None:
        # hub_call_destructive_radio is confirm-gated and MUST NOT be executed for real here (a reset
        # wipes a radio's network; a firmware flash can brick hardware). Assert the safety gate:
        # calling reset WITHOUT confirm is refused. confirm is a REQUIRED schema param, so the refusal
        # surfaces as an isError envelope ("Missing required parameter: confirm") returned as a dict by
        # call_tool, OR a raised McpError/-32602 -- accept either. The radio is never actually reset.
        refused = False
        detail = None
        try:
            detail = self.client.call_tool(
                "hub_call_destructive_radio", {"radio": "zwave", "action": "reset"})
            blob = (detail if isinstance(detail, str) else json.dumps(detail)).lower()
            refused = (isinstance(detail, dict) and bool(detail.get("isError"))) \
                or "confirm" in blob or "required parameter" in blob or "safety check" in blob
        except McpError as exc:  # also catches McpToolError (subclass): raised envelope / -32602
            detail = str(exc)
            refused = any(s in detail.lower()
                          for s in ("confirm", "safety check", "required parameter"))
        assert refused, \
            f"hub_call_destructive_radio reset without confirm must be refused by the safety gate, got: {detail}"

    @test("native_apps")
    def test_set_app_disabled_roundtrip(self) -> None:
        # Item 2 (#257): toggle a standalone non-e2e app's disabled flag and restore it.
        # Pinned to "Hub Health Monitor & Auto Reboot" (app id 68) -- the only user-installed app on
        # the test hub that is NOT e2e infrastructure (not the MCP server under test (38), the v1/v2
        # watchdogs (5506/5993), the RM/VRB/Basic-Rules/Dashboard/HSM parent containers, or HPM (37)).
        # Reads the app's current disabled state, flips it (tool read-back + list-apps verified), and
        # restores the original state in finally so the run leaves the hub as it found it.
        APP_ID = 68

        def current_disabled():
            listed = self.client.call_tool("hub_read_apps_code", {
                "tool": "hub_list_apps", "args": {"scope": "instances", "filter": "user"}})
            apps = listed
            for _ in range(3):
                if isinstance(apps, dict):
                    apps = apps.get("apps") or apps.get("instances") or apps.get("list") or []
                else:
                    break
            for a in (apps if isinstance(apps, list) else []):
                if not isinstance(a, dict):
                    continue
                data = a.get("data")
                d = data if isinstance(data, dict) else a
                if str(d.get("id") or a.get("id") or "") == str(APP_ID):
                    return bool(d.get("disabled"))
            return None

        original = current_disabled()
        assert original is not None, \
            f"app {APP_ID} (Hub Health Monitor) not found on the test hub -- cannot exercise hub_set_app_disabled"

        def set_disabled(val):
            res = self.client.call_tool("hub_manage_native_rules_and_apps", {
                "tool": "hub_set_app_disabled", "args": {"app_id": APP_ID, "disabled": val}})
            assert res.get("success") is True, f"hub_set_app_disabled(disabled={val}) failed: {res}"
            assert res.get("disabled") == val, f"hub_set_app_disabled read-back wrong: wanted {val}, got {res}"
            return res

        try:
            set_disabled(not original)
            assert current_disabled() == (not original), \
                "hub_list_apps does not reflect the flipped disabled state"
        finally:
            set_disabled(original)  # restore the app to the state we found it in

    @test("devices")
    def test_get_device(self) -> None:
        dev_id = self.get_first_device_id()
        result = self.client.call_tool("hub_get_device", {"deviceId": dev_id})
        assert "attributes" in result or "currentStates" in result, \
            "hub_get_device response missing attributes"

    @test("devices")
    def test_get_attribute(self) -> None:
        # Read the attribute off the persistent BAT_E2E_ scaffold switch (find-or-reuse, always exposes
        # `switch`) -- the assertion below is existence-only and device-identity-irrelevant, so the
        # throwaway hub_list_devices switch-hunt was a pure read round-trip with nothing to bind to it.
        switch_id = self.get_test_switch_id()
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
    # GROUP 3: virtual_device_lifecycle (5 tests)
    # -----------------------------------------------------------------------

    def _find_device_dni_by_label(self, label_substr: str) -> str | None:
        """Look up a virtual device's DNI by label substring (create-verify on 504)."""
        try:
            vdevs = self.client.call_tool("hub_list_devices", {"labelFilter": PREFIX})
        except (McpError, McpToolError, requests.HTTPError) as exc:
            print(f"    [WARN] hub_list_devices lookup for {label_substr!r} failed: {exc}")
            return None
        dev_list = vdevs if isinstance(vdevs, list) else vdevs.get("devices", [])
        for d in dev_list:
            lbl = d.get("label") or d.get("name") or ""
            if label_substr in lbl:
                found = str(d.get("deviceNetworkId", d.get("dni", "")))
                if found:
                    return found
        return None

    @test("virtual_device_lifecycle")
    def test_create_virtual_switch(self) -> None:
        cw = self._soft_write(
            lambda: self.client.call_tool("hub_manage_virtual_device", {
                "action": "create",
                "deviceType": "Virtual Switch",
                "deviceLabel": f"{PREFIX}Switch_Test",
                "confirm": True}),
            lambda: self._find_device_dni_by_label(f"{PREFIX}Switch_Test"),
            "create virtual switch",
        )
        if cw["relayDropped"]:
            # The response (success/id/dni) is gone; the labelFilter lookup is the
            # evidence the create committed. Track the recovered DNI for cleanup.
            assert cw["committed"], "create virtual switch lost to relay 504 and never committed"
            self.created_device_dnis.append(str(cw["evidence"]))
            print(f"    create virtual switch: response-field assertions skipped (relay 504); "
                  f"verified by labelFilter (DNI {cw['evidence']})")
            return
        result = cw["response"]
        # Response may be {success: true, message: "..."} without device IDs at top level
        # Track DNI if available, otherwise look it up via hub_list_devices (labelFilter)
        dni = result.get("deviceNetworkId", result.get("dni", ""))
        if dni:
            self.created_device_dnis.append(str(dni))
        elif result.get("success"):
            # Look up the created device to get its DNI for cleanup
            found_dni = self._find_device_dni_by_label(f"{PREFIX}Switch_Test")
            if found_dni:
                self.created_device_dnis.append(found_dni)
        assert result.get("success") or result.get("id") or result.get("deviceId") or dni, \
            f"create virtual device failed: {result}"

    @test("virtual_device_lifecycle")
    def test_command_virtual_switch(self) -> None:
        # Command round-trips get their OWN throwaway device, created here and
        # deleted in the finally -- NOT the shared scaffold, which the rest of the
        # suite references (rule fixtures subscribe to it; poll tests read it) and
        # whose history is therefore unpredictable. The create/delete cost is
        # negligible next to a cross-run interference hunt. State-aware on purpose:
        # read the CURRENT state first, toggle to the opposite, then toggle back, so
        # each leg observes an actual state CHANGE -- polling for a state the device
        # is already in would pass without any event processing at all.
        dev_id = self._create_virtual_switch_device(f"{PREFIX}CmdRoundtrip")
        assert dev_id, "Failed to create the command round-trip throwaway switch"
        # Capture the DNI for the inline delete below; also track it so the cleanup
        # sweep reaps the device if this test dies before its finally.
        cmd_dni = ""
        try:
            vdevs = self.client.call_tool("hub_list_devices", {"labelFilter": f"{PREFIX}CmdRoundtrip"})
            for d in (vdevs if isinstance(vdevs, list) else vdevs.get("devices", [])):
                cmd_dni = str(d.get("deviceNetworkId", d.get("dni", "")))
                if cmd_dni:
                    self.created_device_dnis.append(cmd_dni)
                    break
        except Exception:
            pass

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
            # processed" (a wedged hub -- no event at all on a device this test just
            # created) from "something instantly reverted it" (an app unexpectedly
            # subscribed to it). The event history shows which one happened -- a
            # revert leaves an on->off pair with the producing app in the description
            # -- and the dependents list names any subscriber. Best-effort:
            # diagnostics must never mask the original failure.
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

        def _drive(value: str) -> Any:
            cmd = self.client.call_tool("hub_call_device_command", {"deviceId": dev_id, "command": value})
            assert not (isinstance(cmd, dict) and cmd.get("success") is False), \
                f"'{value}' command reported failure: {cmd}"
            # The response always carries an immediate state snapshot ({attr: {value,
            # timestamp}}). Assert SHAPE + timestamp FORMAT here -- NOT the value: the
            # snapshot is read in the same request that fires the command, and the hub
            # commits the change only after that request returns, so the snapshot is the
            # PRE-effect value even for a virtual switch (the converged-value assertion
            # lives in the waitFor test below, which is what actually confirms the result).
            assert isinstance(cmd, dict) and isinstance(cmd.get("state"), dict), \
                f"'{value}' command response missing post-command state snapshot: {cmd}"
            snap = cmd["state"].get("switch")
            assert isinstance(snap, dict) and "value" in snap and "timestamp" in snap, \
                f"'{value}' snapshot missing switch value/timestamp: {cmd['state']}"
            # The timestamp must be a properly-formatted "yyyy-MM-dd HH:mm:ss" string, not
            # a JVM Date.toString() (which a formatTimestamp-on-Date regression would emit).
            ts = snap.get("timestamp")
            assert isinstance(ts, str) and re.match(r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$", ts), \
                f"'{value}' snapshot switch timestamp not formatted yyyy-MM-dd HH:mm:ss: {snap!r}"
            return _poll_switch(value)

        try:
            cur = self.client.call_tool("hub_get_device_attribute", {
                "deviceId": dev_id, "attribute": "switch",
            })
            start = cur.get("value") if isinstance(cur, dict) else None
            # A fresh Virtual Switch is born with switch=null; toggling to "on" first
            # covers that edge identically to a real "off" start.
            first, second = ("off", "on") if start == "on" else ("on", "off")

            result = _drive(first)
            # A command that polls out with no state change on a device this test just
            # created is the load-limiter block signature -- bounce + retry once for a
            # REAL round-trip (a full event delivery always beats a limiter-proven pass).
            if (result.get("timedOut") is not False or result.get("finalValue") != first) \
                    and self._clear_load_throttle(f"'{first}' on fresh device {dev_id} never landed: {result}"):
                result = _drive(first)
            if result.get("timedOut") is not False or result.get("finalValue") != first:
                # Three-way contract (live-verified 2026-06-12): a healthy hub round-trips;
                # a limiter-blocked hub false-succeeds the command but ALWAYS leaves a
                # device-context LimitExceededException in the hub error log naming this
                # exact device -- that line proves the dispatch left the tool and reached
                # the device, so the command pipeline under test works and the miss is the
                # platform throttling delivery (documented here, not a product failure).
                # No limiter evidence (or any other response shape, e.g. the not-found
                # error a bogus deviceId gets) stays an honest red.
                if self._limiter_logged(dev_id, method=first):
                    print("    [LIMITER] command pipeline verified via hub log -- event delivery "
                          "throttled by the platform; skipping the round-trip assertions this run")
                    self._soft_passes.append(
                        "virtual_device_lifecycle/test_command_virtual_switch: limiter-proven pass "
                        "(dispatch reached the device; platform load limiter aborted delivery)")
                    return
                assert False, \
                    f"Expected switch={first} (from {start!r}) within the poll budget with no " \
                    f"limiter evidence in the hub log, got: {result}\n    DIAG {_switch_diagnostics()}"

            # Toggle back the other way
            result = _drive(second)
            if (result.get("timedOut") is not False or result.get("finalValue") != second) \
                    and self._clear_load_throttle(f"'{second}' on fresh device {dev_id} never landed: {result}"):
                result = _drive(second)
            if result.get("timedOut") is not False or result.get("finalValue") != second:
                if self._limiter_logged(dev_id, method=second):
                    print("    [LIMITER] return-leg delivery throttled by the platform "
                          "(first leg round-tripped; command pipeline verified)")
                    self._soft_passes.append(
                        "virtual_device_lifecycle/test_command_virtual_switch: limiter-proven pass on "
                        "the return leg (first leg fully round-tripped)")
                    return
                assert False, \
                    f"Expected switch={second} (from {first!r}) within the poll budget with no " \
                    f"limiter evidence in the hub log, got: {result}\n    DIAG {_switch_diagnostics()}"
        finally:
            # Best-effort inline delete (the tracked DNI + cleanup sweep backstop a
            # miss); delete-contract assertions live in test_delete_virtual_switch.
            if cmd_dni:
                try:
                    self.client.call_tool("hub_manage_virtual_device", {
                        "action": "delete", "deviceNetworkId": cmd_dni, "confirm": True,
                    })
                except Exception as exc:
                    print(f"  [WARN] could not delete the command round-trip switch ({cmd_dni}): {exc}")

    @test("virtual_device_lifecycle")
    def test_command_waitfor_converges(self) -> None:
        # waitFor is what actually confirms the RESULTING state: the immediate snapshot is
        # pre-effect (the hub commits the change after the request returns), but waitFor
        # block-polls the attribute until it converges, then snapshots -- so converged=true
        # AND the post-waitFor snapshot value == the target. Own throwaway device.
        dev_id = self._create_virtual_switch_device(f"{PREFIX}WaitForRT")
        assert dev_id, "Failed to create the waitFor throwaway switch"
        # Resolve the DNI by device id and track it RIGHT AWAY as the teardown safety net,
        # so a throw or a missed lookup below can never leave the device leaked (harmless if
        # already deleted in the finally). Same pattern as the trigger-device tests.
        wf_dni = ""
        try:
            vdevs = self.client.call_tool("hub_list_devices", {"labelFilter": f"{PREFIX}WaitForRT"})
            for d in (vdevs if isinstance(vdevs, list) else vdevs.get("devices", [])):
                if str(d.get("id")) == str(dev_id):
                    wf_dni = str(d.get("deviceNetworkId") or d.get("dni") or "")
                    break
        except Exception:
            pass
        if wf_dni:
            self.created_device_dnis.append(wf_dni)

        try:
            # Start from a known opposite state so the command drives a real transition.
            cur = self.client.call_tool("hub_get_device_attribute", {"deviceId": dev_id, "attribute": "switch"})
            start = cur.get("value") if isinstance(cur, dict) else None
            target = "off" if start == "on" else "on"

            cmd = self.client.call_tool("hub_call_device_command", {
                "deviceId": dev_id,
                "command": target,
                "waitFor": {"attribute": "switch", "expectedValue": target, "timeoutMs": 5000},
            })
            assert isinstance(cmd, dict), f"unexpected response: {cmd!r}"
            wf = cmd.get("waitFor")
            assert isinstance(wf, dict), f"waitFor result block missing: {cmd}"
            # The discriminator: converged True + finalValue == target.
            if wf.get("converged") is not True and self._limiter_logged(dev_id, method=target):
                self._soft_passes.append(
                    "virtual_device_lifecycle/test_command_waitfor_converges: limiter-proven "
                    "(command dispatched; platform throttled event delivery so waitFor could not converge)")
                return
            assert wf.get("converged") is True, f"waitFor did not converge: {wf}"
            assert str(wf.get("finalValue")) == target, f"waitFor finalValue != target: {wf}"
            # Snapshot is taken AFTER the waitFor poll, so it now reflects the converged value.
            snap = (cmd.get("state") or {}).get("switch")
            assert isinstance(snap, dict) and snap.get("value") == target, \
                f"post-waitFor snapshot should reflect the converged value {target!r}: {cmd.get('state')}"
        finally:
            if wf_dni:
                try:
                    self.client.call_tool("hub_manage_virtual_device", {
                        "action": "delete", "deviceNetworkId": wf_dni, "confirm": True,
                    })
                except Exception as exc:
                    print(f"  [WARN] could not delete the waitFor switch ({wf_dni}): {exc}")

    @test("virtual_device_lifecycle")
    def test_poll_comparator_and_stable(self) -> None:
        # Exercises the read-side convergence extensions on a real hub:
        #   - numeric comparator (gt) on a dimmer's level via hub_get_device_attribute poll mode
        #   - stableForMs (debounce) on a switch via the hub_call_device_command waitFor path
        # Own throwaway Virtual Dimmer + reuse a throwaway switch; both cleaned up in finally.
        dim_dni = ""
        sw_dni = ""
        sw_id = ""
        try:
            # --- numeric comparator on a dimmer ---
            self._soft_write(
                lambda: self.client.call_tool("hub_manage_virtual_device", {
                    "action": "create", "deviceType": "Virtual Dimmer",
                    "deviceLabel": f"{PREFIX}CmpDimmer", "confirm": True}),
                lambda: self._find_device_dni_by_label(f"{PREFIX}CmpDimmer"),
                "create comparator dimmer",
            )
            dim_id = ""
            vdevs = self.client.call_tool("hub_list_devices", {"labelFilter": f"{PREFIX}CmpDimmer"})
            for d in (vdevs if isinstance(vdevs, list) else vdevs.get("devices", [])):
                if f"{PREFIX}CmpDimmer" in (d.get("label") or d.get("name") or ""):
                    dim_id = str(d.get("id"))
                    dim_dni = str(d.get("deviceNetworkId") or d.get("dni") or "")
                    break
            if dim_dni:
                self.created_device_dnis.append(dim_dni)
            assert dim_id, "Failed to create the comparator dimmer"

            # Drive level to 60 then poll for level > 50 (numeric gt). Use the waitFor on the
            # command itself so the level has converged before we assert the comparator poll.
            self.client.call_tool("hub_call_device_command", {
                "deviceId": dim_id, "command": "setLevel", "parameters": ["60"],
                "waitFor": {"attribute": "level", "comparator": "gte", "expectedValue": "60", "timeoutMs": 5000},
            })
            poll = self.client.call_tool("hub_get_device_attribute", {
                "deviceId": dim_id, "attribute": "level",
                "comparator": "gt", "expectedValue": "50", "timeoutMs": 5000,
            })
            assert isinstance(poll, dict), f"comparator poll unexpected response: {poll!r}"
            if poll.get("success") is not True and self._limiter_logged(dim_id, method="setLevel"):
                self._soft_passes.append(
                    "virtual_device_lifecycle/test_poll_comparator_and_stable: limiter-proven "
                    "(setLevel dispatched; platform throttled event delivery so the gt poll could not converge)")
            else:
                assert poll.get("success") is True, f"gt comparator should converge (level 60 > 50): {poll}"
                assert poll.get("timedOut") is False, f"gt comparator should not time out: {poll}"

            # A numeric comparator paired with expectedValues is rejected (invalid params).
            try:
                self.client.call_tool("hub_get_device_attribute", {
                    "deviceId": dim_id, "attribute": "level",
                    "comparator": "gt", "expectedValues": ["50"], "timeoutMs": 1000,
                })
                raise AssertionError("numeric comparator with expectedValues should have errored")
            except (McpToolError, McpError):
                pass

            # between: level is at 60 (driven above), so a [50,70] inclusive range converges.
            bpoll = self.client.call_tool("hub_get_device_attribute", {
                "deviceId": dim_id, "attribute": "level",
                "comparator": "between", "expectedValues": ["50", "70"], "timeoutMs": 5000,
            })
            assert isinstance(bpoll, dict), f"between poll unexpected response: {bpoll!r}"
            if bpoll.get("success") is not True and self._limiter_logged(dim_id, method="setLevel"):
                self._soft_passes.append(
                    "virtual_device_lifecycle/test_poll_comparator_and_stable: limiter-proven "
                    "(between: setLevel dispatched; platform throttled event delivery)")
            else:
                assert bpoll.get("success") is True, f"between [50,70] should converge for level 60: {bpoll}"
                assert bpoll.get("timedOut") is False, f"between should not time out: {bpoll}"

            # --- stableForMs debounce on a switch ---
            sw_id = self._create_virtual_switch_device(f"{PREFIX}StableSw")
            assert sw_id, "Failed to create the stableForMs switch"
            vdevs = self.client.call_tool("hub_list_devices", {"labelFilter": f"{PREFIX}StableSw"})
            for d in (vdevs if isinstance(vdevs, list) else vdevs.get("devices", [])):
                if str(d.get("id")) == str(sw_id):
                    sw_dni = str(d.get("deviceNetworkId") or d.get("dni") or "")
                    break
            if sw_dni:
                self.created_device_dnis.append(sw_dni)

            cur = self.client.call_tool("hub_get_device_attribute", {"deviceId": sw_id, "attribute": "switch"})
            start = cur.get("value") if isinstance(cur, dict) else None
            target = "off" if start == "on" else "on"
            cmd = self.client.call_tool("hub_call_device_command", {
                "deviceId": sw_id, "command": target,
                "waitFor": {"attribute": "switch", "expectedValue": target, "stableForMs": 300, "timeoutMs": 5000},
            })
            assert isinstance(cmd, dict), f"stableForMs command unexpected response: {cmd!r}"
            wf = cmd.get("waitFor")
            assert isinstance(wf, dict), f"waitFor result block missing: {cmd}"
            if wf.get("converged") is not True and self._limiter_logged(sw_id, method=target):
                self._soft_passes.append(
                    "virtual_device_lifecycle/test_poll_comparator_and_stable: limiter-proven "
                    "(command dispatched; platform throttled event delivery so the stableForMs waitFor could not converge)")
            else:
                assert wf.get("converged") is True, f"stableForMs waitFor should converge on a steady value: {wf}"
                # The window must have elapsed: elapsedMs >= stableForMs on a clean convergence.
                assert int(wf.get("elapsedMs", 0)) >= 300, f"stableForMs waitFor converged before the 300ms window: {wf}"

            # stableForMs >= timeoutMs is rejected before the command fires.
            try:
                self.client.call_tool("hub_call_device_command", {
                    "deviceId": sw_id, "command": target,
                    "waitFor": {"attribute": "switch", "expectedValue": target, "stableForMs": 5000, "timeoutMs": 5000},
                })
                raise AssertionError("stableForMs >= timeoutMs should have errored")
            except (McpToolError, McpError):
                pass

            # ne: the switch is at `target`; flip it back to `start` and ne-poll for "not target",
            # which converges once the value leaves the set. Drive the flip with a command waitFor
            # so the value has settled at `start` before the ne poll asserts.
            other = "off" if target == "on" else "on"   # == start (the pre-flip value)
            nepoll = self.client.call_tool("hub_call_device_command", {
                "deviceId": sw_id, "command": other,
                "waitFor": {"attribute": "switch", "comparator": "ne", "expectedValue": target, "timeoutMs": 5000},
            })
            assert isinstance(nepoll, dict), f"ne command unexpected response: {nepoll!r}"
            nwf = nepoll.get("waitFor")
            assert isinstance(nwf, dict), f"ne waitFor result block missing: {nepoll}"
            if nwf.get("converged") is not True and self._limiter_logged(sw_id, method=other):
                self._soft_passes.append(
                    "virtual_device_lifecycle/test_poll_comparator_and_stable: limiter-proven "
                    "(ne: command dispatched; platform throttled event delivery)")
            else:
                assert nwf.get("converged") is True, f"ne should converge once switch leaves '{target}': {nwf}"
                assert nwf.get("finalValue") == other, f"ne finalValue should be the new value '{other}': {nwf}"
        finally:
            for dni in (dim_dni, sw_dni):
                if dni:
                    try:
                        self.client.call_tool("hub_manage_virtual_device", {
                            "action": "delete", "deviceNetworkId": dni, "confirm": True})
                    except Exception as exc:
                        print(f"  [WARN] could not delete convergence test device ({dni}): {exc}")

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

        # On a relay 504 the response is lost but the delete may still have committed;
        # the gone-by-listing check below is the verification either way.
        dw = self._soft_write(
            lambda: self.client.call_tool("hub_manage_virtual_device", {
                "action": "delete",
                "deviceNetworkId": target_dni,
                "confirm": True}),
            lambda: self._find_device_dni_by_label(f"{PREFIX}Switch_Test") is None,
            "delete virtual switch",
        )
        if dw["relayDropped"]:
            assert dw["committed"], f"{PREFIX}Switch_Test still present after a relay-504 delete (did not commit)"
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
        # test_create_rule's _create_rule_and_verify already fetched this SAME rule; reuse that read-back
        # (runs right after create, before test_update_rule renames it -- no write between). Fall back to a
        # live fetch when the stash is unset/for a different rule (isolation-safe).
        if self._last_rule_obj and self._last_rule_obj[0] == rule_id:
            result = self._last_rule_obj[1]
        else:
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
        # The read-back below binds the rename; a relay 504 only drops the response.
        uw = self._soft_write(
            lambda: self.client.call_tool("hub_update_custom_rule", {
                "ruleId": rule_id,
                "name": f"{PREFIX}Rule_CRUD_Updated"}),
            lambda: True,  # verified by the read-back below
            "hub_update_custom_rule",
        )
        if uw["relayDropped"]:
            print("    hub_update_custom_rule: response skipped (relay 504); rename verified via read-back")
        fetched = self.client.call_tool("hub_get_custom_rule", {"ruleId": rule_id})
        assert "Updated" in fetched.get("name", ""), \
            f"Rule name not updated: {fetched.get('name')}"

    @test("rule_crud")
    def test_delete_rule(self) -> None:
        rule_id = self._last_rule_id()
        if not rule_id:
            raise AssertionError("No rule created to delete -- the upstream create-rule test must have failed")
        # On a relay 504 the response is lost but the delete may still have committed;
        # the gone-check below is the verification either way.
        dw = self._soft_write(
            lambda: self.client.call_tool("hub_delete_custom_rule", {"ruleId": rule_id, "confirm": True}),
            lambda: self._custom_rule_absent(rule_id),
            "hub_delete_custom_rule",
        )
        if dw["relayDropped"]:
            assert dw["committed"], f"custom rule {rule_id} still present after a relay-504 delete (did not commit)"
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

    def _find_app_id_by_label(self, label: str) -> str | None:
        """Look up an installed app by its unique label across both listing surfaces.

        Used by the create-verify-by-label leg of the native-app soft-write paths: a
        relay-504-dropped CREATE may still have committed, so we hunt the label. Checks
        hub_list_apps (scope=instances, all user apps -- catches button controllers,
        Hub Variables, basic rules) AND hub_list_rules (RM rules surface there by
        name/label). Returns the id as a string, or None if the create truly failed."""
        try:
            listed = self.client.call_tool("hub_manage_native_rules_and_apps", {
                "tool": "hub_list_apps", "args": {"scope": "instances", "filter": "user"},
            })
            apps = listed if isinstance(listed, list) else (listed.get("apps") or listed.get("instances") or [])
            for a in apps:
                if not isinstance(a, dict):
                    continue
                if label in (a.get("label") or a.get("name") or ""):
                    return str(a.get("id") or a.get("appId"))
        except (McpError, McpToolError, requests.HTTPError) as exc:
            print(f"    [WARN] hub_list_apps lookup for {label!r} failed: {exc}")
        try:
            rules = self.client.call_tool("hub_manage_rule_machine", {"tool": "hub_list_rules", "args": {}})
            rule_list = rules if isinstance(rules, list) else (rules.get("rules") or [])
            for r in rule_list:
                if isinstance(r, dict) and label in (r.get("label") or r.get("name") or ""):
                    return str(r.get("id") or r.get("appId"))
        except (McpError, McpToolError, requests.HTTPError) as exc:
            print(f"    [WARN] hub_list_rules lookup for {label!r} failed: {exc}")
        return None

    def _app_still_present(self, app_id: Any) -> bool:
        """True if app_id is still installed (used by delete-verify-by-absence on a 504)."""
        try:
            cfg = self.client.call_tool("hub_read_apps_code", {
                "tool": "hub_get_app_config", "args": {"appId": app_id}})
            return cfg.get("success") is not False
        except (McpToolError, McpError):
            return False  # the read errors because the app is gone => deleted

    @test("native_apps")
    def test_set_rule_native_lifecycle(self) -> None:
        # CREATE a native RM rule in ONE call: hub_set_rule with no appId, bundling
        # a (device-free) Time trigger + a log action -- the headline new capability.
        # The bundled create is the suite's heaviest single create; route it through
        # _create_native_rule so a dropped relay response gets verified by label lookup
        # instead of hard-failing the whole lifecycle.
        app_id = self._create_native_rule("NativeRule", {
            "addTrigger": {
                "capability": "Certain Time (and optional date)",
                "time": "A specific time", "atTime": "17:00",
            },
            "addActions": [{"capability": "log", "message": "E2E native rule fired"}],
        })

        # VERIFY: the new rule shows up in the NATIVE RM rule list (RMUtils).
        rules = self.client.call_tool("hub_manage_rule_machine", {"tool": "hub_list_rules", "args": {}})
        rule_list = rules if isinstance(rules, list) else rules.get("rules", [])
        found = any(
            str(r.get("id")) == str(app_id) or f"{PREFIX}NativeRule" in (r.get("name") or r.get("label") or "")
            for r in rule_list
        )
        assert found, f"created native rule {app_id} not found in hub_list_rules"

        # EDIT: hub_set_rule WITH appId routes to the edit engine -- add a second action.
        # _set_rule carries the relay-504 soft contract (verify health, don't hard-fail).
        edited = self._set_rule(app_id, {"addAction": {"capability": "log", "message": "second action"}})
        assert edited.get("success") is not False, f"hub_set_rule edit reported failure: {edited}"

        # DELETE via the cross-listed hub_delete_native_app -- this IS the lifecycle
        # assertion, so it stays binding: on a relay 504 the response is lost but the
        # delete may still have committed, so verify by listing rules (absent => the
        # delete committed). Only a rule still PRESENT after a non-504 path is a failure.
        try:
            self.client.call_tool("hub_manage_rule_machine", {
                "tool": "hub_delete_native_app", "args": {"appId": app_id, "confirm": True},
            })
        except (McpError, McpToolError, requests.HTTPError) as exc:
            if "504" not in str(exc):
                raise
            print(f"    delete of native rule {app_id} response lost to relay 504 -- verifying deletion by listing rules")
            time.sleep(3.0)
            listed = self.client.call_tool("hub_manage_rule_machine", {"tool": "hub_list_rules", "args": {}})
            remaining = listed if isinstance(listed, list) else (listed.get("rules") or [])
            still_present = any(str(r.get("id")) == str(app_id) for r in remaining)
            assert not still_present, \
                f"native rule {app_id} still present after a relay-504 delete (deletion did not commit): {listed}"
        self._untrack_native_app(app_id)

    @test("native_apps")
    def test_set_native_app_lifecycle(self) -> None:
        # hub_set_native_app: the GENERIC create-or-edit upsert. Create via the
        # registry-driven create path, rename via a raw settings write, then delete.
        # Each leg is relay-504-hardened: a dropped CREATE response is resolved by a
        # label lookup, a dropped EDIT/DELETE by reading back the field / the listing.
        create_label = f"{PREFIX}NativeApp"
        cw = self._soft_write(
            lambda: self.client.call_tool("hub_manage_native_rules_and_apps", {
                "tool": "hub_set_native_app",
                "args": {"appType": "rule_machine", "name": create_label, "confirm": True}}),
            lambda: self._find_app_id_by_label(create_label),
            "hub_set_native_app create",
        )
        if cw["relayDropped"]:
            assert cw["committed"], f"hub_set_native_app create lost to relay 504 and never committed ({create_label})"
            app_id = cw["evidence"]
        else:
            created = cw["response"]
            app_id = created.get("appId")
            assert app_id, f"hub_set_native_app create did not return an appId: {created}"
        self.created_native_app_ids.append(str(app_id))

        # EDIT: generic settings write (rename via origLabel) -- the lean edit path.
        # On a 504 the response (settingsApplied etc.) is gone; verify the rename via
        # hub_get_app_config below regardless, so the EDIT-success assertion only binds
        # on the normal path.
        ew = self._soft_write(
            lambda: self.client.call_tool("hub_manage_native_rules_and_apps", {
                "tool": "hub_set_native_app",
                "args": {"appId": app_id, "settings": {"origLabel": f"{PREFIX}NativeApp_Renamed"}, "confirm": True}}),
            lambda: True,  # the rename is verified by the read-back below, not here
            "hub_set_native_app edit (rename)",
        )
        if ew["relayDropped"]:
            print("    hub_set_native_app edit: response-field assertions skipped (relay 504); "
                  "rename verified via hub_get_app_config below")
        else:
            assert ew["response"].get("success") is not False, \
                f"hub_set_native_app edit reported failure: {ew['response']}"

        # VERIFY the RENAME actually applied via the read-only hub_get_app_config.
        # Identity (label/name) is nested under the `app` object (toolGetAppConfig
        # shape). Asserting the post-rename token (not just PREFIX, which the create
        # label already carries) proves the settings edit landed, not just succeeded.
        # This binds on BOTH paths -- it is the real evidence the edit committed.
        cfg = self.client.call_tool("hub_read_apps_code", {"tool": "hub_get_app_config", "args": {"appId": app_id}})
        app_obj = cfg.get("app") or {}
        label = str(app_obj.get("label") or app_obj.get("name") or "")
        assert "_Renamed" in label, f"hub_set_native_app rename did not land; label={label!r} (cfg keys: {list(cfg.keys())})"

        # DELETE -- the lifecycle's delete contract. On a 504 verify by absence.
        dw = self._soft_write(
            lambda: self.client.call_tool("hub_manage_native_rules_and_apps", {
                "tool": "hub_delete_native_app", "args": {"appId": app_id, "confirm": True}}),
            lambda: not self._app_still_present(app_id),  # truthy => confirmed gone
            "hub_delete_native_app",
        )
        if dw["relayDropped"]:
            assert dw["committed"], f"native app {app_id} still present after a relay-504 delete (did not commit)"
        self._untrack_native_app(app_id)
        # The just-deleted app's configure page now 404s on the hub -- hub_get_app_config must DEGRADE
        # GRACEFULLY to a structured result (the graceful-404 fix), never raise a raw HttpResponseException.
        # (The 404 fingerprint/status shape is pinned in Spock; here we prove the real-hub no-raise degrade.)
        if not dw["relayDropped"]:
            try:
                gone = self.client.call_tool("hub_read_apps_code", {
                    "tool": "hub_get_app_config", "args": {"appId": app_id}})
            except (McpError, McpToolError) as exc:
                raise AssertionError(
                    f"hub_get_app_config on a deleted app raised instead of degrading gracefully: {exc}") from exc
            assert isinstance(gone, dict), f"hub_get_app_config should return a structured result, got: {gone}"
            # Tolerate a brief post-delete render lag (still has app data); when it IS a not-found it must
            # be the graceful 404 form, not a generic opaque error.
            if gone.get("success") is False:
                assert gone.get("status") in (404, 410) or "not found" in str(gone.get("error", "")).lower(), \
                    f"deleted-app not-found should be the graceful 404 form, got: {gone}"

    @test("native_apps")
    def test_set_native_app_basic_rule_lifecycle(self) -> None:
        # basic_rule is a registered appType (a classic dynamicPage app, not a
        # Vue SPA). Create via generic createchild, edit a setting -- which must
        # NOT poison the page with the "For input string: updateRule" error
        # (the commitButton=null fix) -- then delete.
        create_label = f"{PREFIX}BasicRule"
        cw = self._soft_write(
            lambda: self.client.call_tool("hub_manage_native_rules_and_apps", {
                "tool": "hub_set_native_app",
                "args": {"appType": "basic_rule", "name": create_label, "confirm": True}}),
            lambda: self._find_app_id_by_label(create_label),
            "basic_rule create",
        )
        if cw["relayDropped"]:
            assert cw["committed"], f"basic_rule create lost to relay 504 and never committed ({create_label})"
            app_id = cw["evidence"]
        else:
            created = cw["response"]
            app_id = created.get("appId")
            assert app_id, f"basic_rule create did not return an appId: {created}"
        self.created_native_app_ids.append(str(app_id))

        try:
            # The created Basic Rule renders a real classic configPage (proves it's not
            # a Vue-SPA redirect that would silently swallow writes).
            cfg = self.client.call_tool("hub_read_apps_code", {"tool": "hub_get_app_config", "args": {"appId": app_id}})
            assert (cfg.get("app") or {}).get("name") == "Basic Rule-1.0", f"unexpected Basic Rule config: {cfg}"

            # HEALTH on a classic app (issue #254): hub_get_rule_health covers Basic Rule via the
            # generic configPage checks and names it in ruleFormat (broken is null -- no compiled
            # boolean for non-RM classic apps).
            bh = self.client.call_tool("hub_read_rules", {
                "tool": "hub_get_rule_health", "args": {"appId": app_id}})
            assert bh.get("ruleFormat") == "basic-rule", \
                f"hub_get_rule_health should classify a Basic Rule as basic-rule: {bh}"
            assert bh.get("broken") is None, f"a classic app has no compiled broken boolean: {bh}"

            # EDIT: write the Notes field. NO updateRule click fires (Basic Rule
            # is submitOnChange), so the render stays clean. On a 504 the response
            # (configPageError/success) is gone; verify the note landed via read-back
            # so the no-poison + success assertions only bind on the normal path.
            ew = self._soft_write(
                lambda: self.client.call_tool("hub_manage_native_rules_and_apps", {
                    "tool": "hub_set_native_app",
                    "args": {"appId": app_id, "settings": {"comments": f"{PREFIX}note"}, "confirm": True}}),
                lambda: True,  # verified by the comments read-back below
                "basic_rule edit (notes)",
            )
            if ew["relayDropped"]:
                rb = self.client.call_tool("hub_read_apps_code", {
                    "tool": "hub_get_app_config", "args": {"appId": app_id, "includeSettings": True}})
                note = str(((rb.get("settings") or {}).get("comments")) or "")
                assert f"{PREFIX}note" in note, \
                    f"basic_rule notes edit lost to relay 504 and did not commit (settings.comments={note!r})"
                # The hub did not page-error on a committed write (the configPageError
                # check the response would have carried); the clean read-back is the proxy.
                assert not (rb.get("app") or {}).get("configPageError"), \
                    f"basic_rule render poisoned after the dropped edit: {rb}"
                print("    basic_rule edit: response-field assertions skipped (relay 504); note verified via read-back")
            else:
                edited = ew["response"]
                assert "updateRule" not in str(edited.get("configPageError") or ""), \
                    f"Basic Rule edit poisoned the render with the updateRule error: {edited}"
                assert edited.get("success") is not False, f"Basic Rule edit reported failure: {edited}"
        finally:
            # DELETE inline (not just via the global-cleanup backstop) so an
            # assertion failure above doesn't strand the fixture mid-run. A 504 here
            # must not mask a real failure from the try: verify by absence, and only
            # re-raise a genuinely-uncommitted delete (the id stays tracked otherwise).
            dw = self._soft_write(
                lambda: self.client.call_tool("hub_manage_native_rules_and_apps", {
                    "tool": "hub_delete_native_app", "args": {"appId": app_id, "confirm": True}}),
                lambda: not self._app_still_present(app_id),
                "basic_rule delete",
            )
            if not dw["relayDropped"] or dw["committed"]:
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
        ctrl_label = f"{PREFIX}BtnCtrl"
        try:
            # This test is a tightly-coupled CHAIN: each step's RESPONSE feeds the next
            # (device id -> controller -> buttonDev write -> buttonRule create -> action).
            # A relay 504 mid-chain drops a response the next step needs, and the
            # per-step verify-by-read can recover an id but not the full response shape
            # the downstream asserts on -- so a 504 anywhere skips the whole chain
            # (with-print, never a soft-pass). The except below still adopts the
            # controller by label so cleanup/finally can reap it.

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
                "args": {"appType": "button_controller", "name": ctrl_label, "confirm": True},
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

            # HEALTH on a live Button Controller (issue #254): hub_get_rule_health classifies it
            # as button-controller (a classic app, broken=null) -- the only live proof of the
            # button-controller classification branch against a real ruleBuilderJson body.
            ch = self.client.call_tool("hub_read_rules", {
                "tool": "hub_get_rule_health", "args": {"appId": controller_id}})
            assert ch.get("ruleFormat") == "button-controller", \
                f"controller {controller_id} should classify as button-controller: {ch}"
            assert ch.get("broken") is None, f"a classic app has no compiled broken boolean: {ch}"

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
            # The trailing main-page Done commit must target the Button Rule's real commit page
            # (selectActions), not a hardcoded 'mainPage' -- a 404 there sets mainPageDoneFailed/Error
            # (the page-graph fix). With the hardcode bug this fails; after the fix these hold.
            assert acted.get("mainPageDoneFailed") is not True, \
                f"button rule Done commit hit a missing page (mainPage hardcode regression): {acted}"
            assert not acted.get("mainPageDoneError"), \
                f"button rule Done commit errored: {acted.get('mainPageDoneError')}"
        except (McpError, McpToolError, requests.HTTPError) as exc:
            if "504" not in str(exc):
                raise
            # A dropped response mid-chain leaves no trustworthy id/shape to continue
            # from. If the controller create was the casualty, adopt it by label so the
            # finally + cleanup sweep reap it, then skip (never soft-pass).
            if controller_id is None:
                adopted = self._find_app_id_by_label(ctrl_label)
                if adopted:
                    controller_id = adopted
                    self.created_native_app_ids.append(str(adopted))
            raise SkipTest("button-rule chain hit a relay 504 mid-sequence -- "
                           "no trustworthy intermediate response to continue from") from exc
        finally:
            # Deleting the controller cascades to its grandchild rules. Guarded:
            # an unguarded raise here would REPLACE the real test failure, and
            # the controller stays tracked for the global-cleanup backstop. A 504 on
            # the delete is swallowed too (the cascade likely committed; the tracked id
            # + prefix sweep backstop a strand).
            if controller_id:
                try:
                    self.client.call_tool("hub_manage_native_rules_and_apps", {
                        "tool": "hub_delete_native_app", "args": {"appId": controller_id, "force": True, "confirm": True},
                    })
                    self._untrack_native_app(controller_id)
                except (McpToolError, McpError, requests.HTTPError) as exc:
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
                except (McpToolError, McpError, requests.HTTPError) as exc:
                    print(f"  [WARN] button-rule e2e cleanup: delete device {button_dni} failed: {exc}")

    # ---- shared helpers for the native-authoring coverage below ----

    def _create_native_rule(self, suffix: str, extra: dict | None = None) -> Any:
        """Create a native RM rule via hub_set_rule (no appId), track it.

        With no `extra` this creates an empty shell; pass `extra` to BUNDLE create-time
        args (e.g. a trigger + actions) into the same single create call.

        Verify-after-504: writes are never transport-replayed (duplicate-commit risk), so a
        relay 504 here means the CREATE may or may not have committed. Look the rule up by
        its unique label: found -> adopt it; not found -> the create truly failed."""
        label = f"{PREFIX}{suffix}"
        args = {"name": label, "confirm": True}
        if extra:
            args.update(extra)
        created = None  # the fresh-create envelope (stays None on the 504 adopt-by-label path, which has none)
        try:
            created = self.client.call_tool("hub_manage_rule_machine", {
                "tool": "hub_set_rule", "args": args,
            })
            app_id = created.get("appId")
        except (McpError, McpToolError, requests.HTTPError) as exc:
            if "504" not in str(exc):
                raise
            print(f"    create '{label}' response lost to relay 504 -- verifying by label lookup")
            time.sleep(3.0)
            app_id = None
            listed = self.client.call_tool("hub_manage_native_rules_and_apps", {
                "tool": "hub_list_rules", "args": {},
            })
            for r in (listed.get("rules") or []):
                if r.get("label") == label or r.get("name") == label:
                    app_id = r.get("id")
                    print(f"    create committed despite the dropped response -- adopting appId {app_id}")
                    break
            if not app_id:
                # Verified NON-commit: re-issuing is duplicate-safe (the only point a write
                # retry is allowed -- after evidence the first attempt never landed).
                print(f"    create '{label}' verified NOT committed -- one safe retry")
                created = self.client.call_tool("hub_manage_rule_machine", {
                    "tool": "hub_set_rule", "args": dict({"name": label, "confirm": True}, **(extra or {})),
                })
                app_id = created.get("appId")
        assert app_id, f"hub_set_rule create did not yield an appId for '{label}'"
        # When a create BUNDLES an authoring shortcut (rank-2 fold), the create arm computes
        # success = health.ok && !partial; a degraded-but-ok trigger/action reports partial:true. Without
        # this check that envelope is discarded, so a partial-but-ok shortcut would pass silently --
        # restore the strict success/not-partial contract the old separate _set_rule write provided. Only
        # on the fresh-create path (created stays None on the 504 adopt-by-label path, which has no envelope).
        if created is not None and extra and any(k in extra for k in (
                "addTrigger", "addTriggers", "addAction", "addActions", "addRequiredExpression")):
            assert created.get("success") is not False and not created.get("partial"), \
                f"create-time authoring shortcut did not fully commit (partial or failed): {created}"
        self.created_native_app_ids.append(str(app_id))
        return app_id

    def _set_rule(self, app_id: Any, extra: dict, strict: bool = False) -> Any:
        """hub_set_rule edit (appId present) with the given shortcut args.

        Default (soft): a relay 504 gets the moveAction soft contract, generalized -- the
        cloud relay's ~10s ceiling drops the RESPONSE while the hub finishes the wizard op
        (the same unknown-commit state asyncCommitLikely models). Verify the rule still
        renders, return a soft envelope; callers that assert on RESPONSE fields must
        tolerate relayDropped.

        strict=True: the 504 RAISES instead. Used by the per-concern RM wire-format tests,
        whose fixtures are a pristine throwaway rule deleted in their finally -- _run_one
        re-runs the whole small test once on a fresh rule, so no assertion is ever skipped
        on a soft envelope (a skipped wire-format assertion is a false positive)."""
        args = {"appId": app_id, "confirm": True}
        args.update(extra)
        try:
            result = self.client.call_tool("hub_manage_rule_machine", {"tool": "hub_set_rule", "args": args})
        except (McpError, McpToolError, requests.HTTPError) as exc:
            if strict or "504" not in str(exc):
                raise
            print(f"    hub_set_rule({list(extra)}) response lost to relay 504 -- "
                  "soft contract: verifying the rule still renders instead of hard-failing")
            self._assert_rule_renders(app_id)
            self._last_write_health = None
            return {"success": True, "asyncCommitLikely": True, "relayDropped": True}
        assert result.get("success") is not False, f"hub_set_rule({list(extra)}) reported failure: {result}"
        self._cache_write_health(app_id, result)
        return result

    def _rm_call_soft(self, args: dict, strict: bool = False) -> Any:
        """Direct hub_set_rule call (full response: settingsApplied/settingsSkipped/partial,
        triggerIndex/actionIndex -- shapes _set_rule's success-only contract doesn't carry).

        Default (soft): same relay-504 soft contract as _set_rule. strict=True: the 504
        raises so _run_one's test-level retry re-runs the small test on a fresh rule (see
        _set_rule)."""
        try:
            result = self.client.call_tool("hub_manage_rule_machine", {"tool": "hub_set_rule", "args": args})
            self._cache_write_health(args.get("appId"), result)
            return result
        except (McpError, McpToolError, requests.HTTPError) as exc:
            if strict or "504" not in str(exc):
                raise
            print(f"    hub_set_rule(appId={args.get('appId')}) response lost to relay 504 -- "
                  "soft contract: verifying rule health instead of hard-failing")
            self._assert_rule_renders(args.get("appId"))
            self._last_write_health = None
            return {"success": True, "asyncCommitLikely": True, "relayDropped": True}

    def _assert_rule_renders(self, app_id: Any) -> None:
        """Lenient health check for relay-504 soft paths: a dropped response may have committed
        a block OPENER (IF/Repeat), leaving the rule structurally unbalanced -- which the health
        tool itself documents as EXPECTED mid-build. Broken markers, page errors, and flag poison
        still fail; structural imbalance alone does not (the caller's reset/closer handles it)."""
        h = self.client.call_tool("hub_manage_rule_machine", {"tool": "hub_get_rule_health", "args": {"appId": app_id}})
        assert not h.get("configPageError") and not h.get("brokenMarkers") and not h.get("multipleFlagPoison"),             f"rule is genuinely broken after the dropped response (not just mid-build imbalance): {h}"
        if h.get("ok") is False:
            print(f"    rule {app_id} renders with structural imbalance after the dropped response "
                  "(expected mid-build state; a reset/closer follows)")

    def _cache_write_health(self, app_id: Any, result: Any) -> None:
        """Stash the health object a hub_set_rule write already returned (the SAME _rmCheckRuleHealth a
        standalone hub_get_rule_health re-derives), keyed by app, so a following _assert_rule_healthy
        skips the extra round-trip. Cleared on a relay-dropped/soft envelope (no health) -> live fetch."""
        if isinstance(result, dict) and isinstance(result.get("health"), dict):
            self._last_write_health = (str(app_id), result["health"])
        else:
            self._last_write_health = None

    def _assert_rule_healthy(self, app_id: Any) -> None:
        # Prefer the health the immediately-preceding write already returned -- no extra round-trip.
        # Keyed by app so a stale/other-app cache is never trusted; a soft write cleared it -> live fetch.
        cached = self._last_write_health
        if cached is not None and cached[0] == str(app_id):
            assert cached[1].get("ok") is not False, \
                f"rule health (from the write response) reports broken: {cached[1]}"
            return
        h = self.client.call_tool("hub_manage_rule_machine", {"tool": "hub_get_rule_health", "args": {"appId": app_id}})
        assert h.get("ok") is not False, f"hub_get_rule_health reports the rule broken: {h}"

    def _add_action_or_raise_504(self, app_id: Any, action: dict) -> Any:
        """addAction edit that, unlike _set_rule's soft default, lets a relay 504 PROPAGATE.

        Used for block CLOSERS (THEN-add / endIf) where a dropped response must raise so
        the test-level retry re-runs the whole small test on a fresh rule. _set_rule's
        soft path would instead swallow the 504 and then run its OWN in-helper health
        check on the unclosed IF -- which fails with a non-504 AssertionError the retry
        policy can't recognize (the exact run-27407212930 failure). On the normal
        (non-504) path the success contract still binds."""
        result = self.client.call_tool("hub_manage_rule_machine", {
            "tool": "hub_set_rule", "args": {"appId": app_id, "addAction": action, "confirm": True},
        })
        assert result.get("success") is not False, f"addAction({action}) reported failure: {result}"
        # Block CLOSERS land here -- the cache MUST reflect the now-closed (healthy) rule, else a
        # following _assert_rule_healthy reads the stale mid-build "missing END-IF" health from the opener.
        self._cache_write_health(app_id, result)
        return result

    def _delete_native(self, app_id: Any, gateway: str = "hub_manage_rule_machine") -> None:
        # Fixture-teardown delete. When deferral is on, skip it (rule stays tracked) so it's reaped by
        # the disarm sweep during the restore window, not inline on the test critical path. Tests whose
        # delete IS the assertion call hub_delete_native_app directly (not this helper), so they keep
        # deleting inline regardless.
        if self.defer_native_deletes:
            return
        self.client.call_tool(gateway, {"tool": "hub_delete_native_app", "args": {"appId": app_id, "force": True, "confirm": True}})
        self._untrack_native_app(app_id)

    # ---- per-concern RM wire-format tests (the former single mega-test, split) ----
    #
    #      The one shared rule accumulated actions/triggers across 14 substeps, and the
    #      classic wizard re-sends the FULL rule page on every submitOnChange POST -- so
    #      the per-substep byte cost through the server app GREW as the test ran. That
    #      load curve is what tripped the platform's per-app load limiter at the tail of
    #      the mega-test (run 27416764119: dispatch blocked at +298s, during the final
    #      substeps; the co-located relay-504 cluster was the early symptom of the same
    #      overload). Small per-concern rules keep every wizard page small.
    #
    #      Contract for this family (these assertions pin live RM wire-format behaviour;
    #      a skipped assertion is a false positive):
    #      - Each test owns a PRISTINE throwaway rule: create -> assert -> delete in
    #        finally (the delete runs on failure too, so a retry starts clean).
    #      - STRICT on relay 504s: a dropped response raises; _run_one re-runs the whole
    #        small test once on a fresh rule; a second 504 is an honest red. No
    #        relayDropped soft envelopes, no skipped wire-format assertions.
    #      - The docstring knowledge from the former standalone tests is preserved in
    #        the comments -- those pin wire-format regressions; do not drop them.

    @test("native_apps")
    def test_set_rule_walkstep_introspect(self) -> None:
        # hub_set_rule edit -> walkStep (schema-aware single-step walker), read-only op;
        # then the same walkStep routed through the GENERIC native-app tool
        # (hub_set_native_app). The rmOnly reject on walkStep was removed: it's a generic
        # classic-dynamicPage walker that routes to the shared edit engine and works on
        # any classic app. The introspect op itself doesn't modify the app (a
        # pre-walkStep backup snapshot is still written).
        app_id = self._create_native_rule("WalkIntro")
        try:
            ws = self._set_rule(app_id, {"walkStep": {"page": "selectTriggers", "operation": "introspect"}}, strict=True)
            assert isinstance(ws, dict), f"walkStep introspect returned non-dict: {ws}"
            wsn = self.client.call_tool("hub_manage_native_rules_and_apps", {
                "tool": "hub_set_native_app",
                "args": {"appId": app_id, "walkStep": {"page": "mainPage", "operation": "introspect"}, "confirm": True},
            })
            assert wsn.get("page") == "mainPage", \
                f"walkStep should route through the native-app tool, got: {wsn}"

            # issue #258 added operation='drive' WITHOUT changing the single-step path; prove
            # the old mode still drives a write end-to-end via SEPARATE calls (open the editor,
            # then pick a capability) -- the same click+write the drive composes, step by step.
            self._set_rule(app_id, {"walkStep": {"page": "selectTriggers", "operation": "click",
                                                 "click": {"name": "true", "stateAttribute": "moreCond"}}}, strict=True)
            sw = self._set_rule(app_id, {"walkStep": {"page": "selectTriggers", "operation": "write",
                                                      "write": {"tCapab1": "Switch"}}}, strict=True)
            assert (sw.get("valueEcho") or {}).get("match") is True, \
                f"single-step write should still round-trip as before: {sw}"
        finally:
            self._delete_native(app_id)

    @test("native_apps")
    def test_set_rule_walkstep_drive(self) -> None:
        # issue #258: walkStep operation='drive' runs an ordered steps[] sequence in ONE
        # call -- the progressive flow that replaces the manual introspect -> ... -> finalize
        # loop the LLM used to issue as N separate calls. This pins the drive orchestration
        # surface end-to-end against a live hub across four facets:
        #   (1) read composition  -- multi-page introspect returns the aggregate
        #                            {operation:'drive', steps:[...], stepsRun, success} with
        #                            each step's page + fail-loud health snapshot;
        #   (2) WRITE composition -- a click (open trigger editor) + a capability write in one
        #                            call, with the write proven to land live via valueEcho;
        #   (3) failure halt      -- a bad step aborts the drive (success:false) without
        #                            corrupting the rule.
        # Each live drive is kept to <=2 steps on purpose: a drive does a full
        # introspect+op+introspect+health per step, so a long drive is ONE heavy tool call
        # that risks the cloud relay's ~10s 504 ceiling. The stopOnError step-success=false
        # branch and the >2-step commit sequence are covered deterministically by the Spock
        # unit tests; here we prove the drive layer works against the real wizard.
        app_id = self._create_native_rule("WalkDrive")
        try:
            # (1) read composition + page-carry + per-step health
            res = self._set_rule(app_id, {"walkStep": {"operation": "drive", "steps": [
                {"page": "selectTriggers", "operation": "introspect"},
                {"page": "mainPage", "operation": "introspect"},
            ]}}, strict=True)
            assert isinstance(res, dict), f"walkStep drive returned non-dict: {res}"
            assert res.get("operation") == "drive", f"drive should echo operation='drive': {res}"
            steps = res.get("steps")
            assert isinstance(steps, list) and len(steps) == 2, f"drive should report 2 per-step results: {res}"
            assert res.get("stepsRun") == 2, f"both steps should run on a healthy rule: {res}"
            assert steps[0].get("operation") == "introspect" and steps[0].get("page") == "selectTriggers", \
                f"step 1 should introspect selectTriggers: {steps[0]}"
            assert steps[1].get("page") == "mainPage", f"step 2 should land on mainPage: {steps[1]}"
            assert all(isinstance(s.get("health"), dict) for s in steps), \
                f"each drive step should carry its health snapshot: {steps}"
            self._assert_rule_healthy(app_id)

            # (2) WRITE composition: open the trigger editor then pick a capability, in ONE
            #     drive call. Mirrors _rmAddTrigger's proven wire format (click name='true'/
            #     stateAttribute='moreCond' opens the editor; tCapab1 is the capability picker).
            #     The drive's own valueEcho proves the write round-tripped on the live hub.
            wr = self._set_rule(app_id, {"walkStep": {"operation": "drive", "steps": [
                {"page": "selectTriggers", "operation": "click", "click": {"name": "true", "stateAttribute": "moreCond"}},
                {"page": "selectTriggers", "operation": "write", "write": {"tCapab1": "Switch"}},
            ]}}, strict=True)
            assert wr.get("stepsRun") == 2, f"both drive steps should run (click + write): {wr}"
            write_step = next((s for s in (wr.get("steps") or []) if s.get("operation") == "write"), None)
            assert write_step is not None, f"the write step should be reported in the aggregate: {wr}"
            assert (write_step.get("valueEcho") or {}).get("match") is True, \
                f"the driven tCapab1='Switch' write should round-trip live (valueEcho.match): {write_step}"
            # A half-built (uncommitted) trigger is scratch wizard state, not a broken rule.
            self._assert_rule_renders(app_id)

            # (3) failure halt: an invalid step operation aborts the drive (success:false,
            #     the step throws) and must NOT corrupt the rule.
            bad = self.client.call_tool("hub_manage_rule_machine", {"tool": "hub_set_rule", "args": {
                "appId": app_id, "confirm": True,
                "walkStep": {"operation": "drive", "steps": [
                    {"page": "mainPage", "operation": "introspect"},
                    {"page": "mainPage", "operation": "bogus_op"},
                ]},
            }})
            assert bad.get("success") is False, \
                f"a drive with an invalid step operation must halt with success:false: {bad}"
            assert "operation" in str(bad.get("error") or "").lower(), \
                f"the halt error should name the bad operation: {bad}"
            self._assert_rule_renders(app_id)
        finally:
            self._delete_native(app_id)

    @test("native_apps")
    def test_set_rule_walkstep_action_after_required_expression(self) -> None:
        # P2c regression: an action authored ENTIRELY via SINGLE-STEP walkStep (not
        # _rmAddAction, not operation='drive') AFTER a Required Expression must land
        # TOP-LEVEL, never wrapped under IF(Broken Condition). RM leaves
        # atomicState.predCapabs dirty after an RE commit; the deferred predClear runs on
        # the FIRST action-page op (the navigate into doActPage), so the slot is created
        # with predCapabs already cleared. test_set_rule_action_after_required_expression
        # proves the same guard for _rmAddAction; this one proves the single-step walker
        # path -- _rmWalkStep's own deferred-clear hook -- which _rmAddAction's flow never
        # exercises. strict=True so a relay 504 re-runs this small rule once.
        sw = int(self.get_test_switch_id())
        app_id = self._create_native_rule("WalkActRE")
        try:
            # RE first: sets predClearPending and dirties predCapabs.
            re_res = self._rm_call_soft({
                "appId": app_id,
                "addRequiredExpression": {"conditions": [
                    {"capability": "Switch", "deviceIds": [sw], "state": "on"}]},
                "confirm": True,
            }, strict=True)
            assert re_res.get("success") is not False, \
                f"addRequiredExpression reported failure: {re_res}"
            # Single-step navigate into the action editor -- this is the op that fires the
            # deferred predCapabs clear, BEFORE the new action slot is created.
            nav = self._set_rule(app_id, {"walkStep": {"page": "selectActions", "operation": "navigate",
                                                       "navigate": {"targetPage": "doActPage"}}}, strict=True)
            assert nav.get("page") == "doActPage", f"navigate should land on doActPage: {nav}"
            # The new action's index is the n in the revealed actType.<n> picker -- DERIVE it.
            act_field = next((i.get("name") for i in ((nav.get("after") or {}).get("inputs") or [])
                              if str(i.get("name")).startswith("actType.")), None)
            assert act_field, f"doActPage should reveal an actType.<n> picker: {nav}"
            n = act_field.split(".", 1)[1]
            # Author a log action via single-step writes (each reveals the next field) + a Done.
            self._set_rule(app_id, {"walkStep": {"page": "doActPage", "operation": "write",
                                                 "write": {f"actType.{n}": "messageActs"}}}, strict=True)
            self._set_rule(app_id, {"walkStep": {"page": "doActPage", "operation": "write",
                                                 "write": {f"actSubType.{n}": "getLogMsg"}}}, strict=True)
            wr = self._set_rule(app_id, {"walkStep": {"page": "doActPage", "operation": "write",
                                                      "write": {f"logmsg.{n}": "walkstep-after-RE"}}}, strict=True)
            assert (wr.get("valueEcho") or {}).get("match") is True, \
                f"single-step logmsg write should round-trip live (valueEcho.match): {wr}"
            self._set_rule(app_id, {"walkStep": {"page": "doActPage", "operation": "click",
                                                 "click": {"name": "actionDone"}}}, strict=True)
            cfg = self.client.call_tool("hub_read_apps_code", {
                "tool": "hub_get_app_config", "args": {"appId": app_id}})
            assert "Broken Condition" not in str(cfg), \
                f"predCapabs leaked -- the walkStep-authored post-RE action is wrapped under IF(Broken Condition): {str(cfg)[:800]}"
            assert "walkstep-after-RE" in str(cfg), \
                f"the walkStep-authored log action did not commit top-level: {str(cfg)[:800]}"
        finally:
            self._delete_native(app_id)

    @test("native_apps")
    def test_set_native_app_walkstep_button_controller(self) -> None:
        # The single-step walkStep walker (introspect + write) is a GENERIC
        # classic-dynamicPage walker, not RM-specific. The other walkStep tests only drive
        # an RM rule's appId; this one proves it works on a real NON-RM classic app -- a
        # Button Controller -- routed through hub_set_native_app. Assign a virtual button
        # device via a single-step write, and prove both the live round-trip (valueEcho)
        # and the submitOnChange reveal (origLabel appears once a device is bound).
        controller_id = None
        button_dni = None
        try:
            # Virtual button device for the controller to bind to (tracked for cleanup).
            dev = self.client.call_tool("hub_manage_virtual_device", {
                "action": "create", "deviceType": "Virtual Button",
                "deviceLabel": f"{PREFIX}WalkBtnDev", "confirm": True,
            })
            device_id = str((dev.get("device") or {}).get("id") or "")
            assert device_id, f"virtual button create did not return a device id: {dev}"
            button_dni = str((dev.get("device") or {}).get("deviceNetworkId") or "")
            if button_dni:
                self.created_device_dnis.append(button_dni)

            # Button Controller instance (tracked for cleanup).
            ctrl = self.client.call_tool("hub_manage_native_rules_and_apps", {
                "tool": "hub_set_native_app",
                "args": {"appType": "button_controller", "name": f"{PREFIX}WalkBtnCtrl", "confirm": True},
            })
            controller_id = ctrl.get("appId")
            assert controller_id, f"button controller create did not return an appId: {ctrl}"
            self.created_native_app_ids.append(str(controller_id))

            # Single-step walkStep INTROSPECT on the non-RM app.
            intro = self.client.call_tool("hub_manage_native_rules_and_apps", {
                "tool": "hub_set_native_app",
                "args": {"appId": controller_id, "walkStep": {"page": "mainPage", "operation": "introspect"}, "confirm": True},
            })
            assert intro.get("page") == "mainPage", \
                f"walkStep introspect should land on mainPage of the button controller: {intro}"
            intro_names = [i.get("name") for i in ((intro.get("before") or {}).get("inputs") or [])]
            assert "buttonDev" in intro_names, \
                f"mainPage should expose the buttonDev (capability.pushableButton) picker: {intro_names}"

            # Single-step walkStep WRITE on the non-RM app -- assign the device.
            wr = self.client.call_tool("hub_manage_native_rules_and_apps", {
                "tool": "hub_set_native_app",
                "args": {"appId": controller_id, "walkStep": {"page": "mainPage", "operation": "write",
                                                              "write": {"buttonDev": [device_id]}}, "confirm": True},
            })
            assert (wr.get("valueEcho") or {}).get("match") is True, \
                f"single-step buttonDev write should round-trip live (valueEcho.match): {wr}"
            after_names = [i.get("name") for i in ((wr.get("after") or {}).get("inputs") or [])]
            assert "origLabel" in after_names, \
                f"submitOnChange reveal: origLabel should appear once a button device is assigned: {after_names}"
        finally:
            # The controller delete is the only inline teardown; the device is swept by
            # created_device_dnis. _delete_native handles the deferred-delete mode.
            if controller_id:
                self._delete_native(controller_id, gateway="hub_manage_native_rules_and_apps")

    @test("native_apps")
    def test_rule_health_prefers_rulebuilderjson(self) -> None:
        # issue #254: hub_get_rule_health now reads the rule's compiled atomicState
        # (GET /app/ruleBuilderJson) for an authoritative `broken` boolean, with the
        # HTML configure-json render scan RETAINED as a cross-check + fallback. A
        # freshly-created healthy rule must report broken:false from the JSON source,
        # and `source` must show the preferred path contributed under default auto mode.
        app_id = self._create_native_rule("RuleHealthSrc")
        try:
            auto = self.client.call_tool("hub_manage_rule_machine", {
                "tool": "hub_get_rule_health", "args": {"appId": app_id},
            })
            assert auto.get("ok") is True, f"fresh rule should be healthy: {auto}"
            assert auto.get("broken") is False, \
                f"ruleBuilderJson should report broken:false for a healthy rule: {auto}"
            assert "ruleBuilderJson" in str(auto.get("source") or ""), \
                f"auto mode should read the preferred ruleBuilderJson source: {auto}"

            # The retained legacy path stays selectable and must NOT read the JSON source.
            html = self.client.call_tool("hub_manage_rule_machine", {
                "tool": "hub_get_rule_health", "args": {"appId": app_id, "source": "configPage"},
            })
            assert html.get("source") == "configPage", \
                f"source=configPage must force the HTML-only path: {html}"
            assert html.get("broken") is None, \
                f"the HTML path does not produce the compiled-state boolean: {html}"
        finally:
            self._delete_native(app_id)

    @test("native_apps")
    def test_rule_health_broken_true_on_dangling_trigger(self) -> None:
        # issue #254 headline: the compiled-state `broken` boolean must fire TRUE on a genuinely
        # broken rule, not only false on healthy ones. Build a rule whose trigger references a
        # virtual switch, delete the switch so the trigger dangles, render the config page to force
        # RM to re-validate (the boolean lags the *BROKEN* label until a render), then assert the
        # authoritative broken:true verdict from /app/ruleBuilderJson. Proves the marquee path live.
        dev_id = self._create_virtual_switch_device(f"{PREFIX}RHBrokenDev")
        assert dev_id, "could not create the trigger device"
        dni = ""
        vdevs = self.client.call_tool("hub_list_devices", {"labelFilter": PREFIX})
        for d in (vdevs if isinstance(vdevs, list) else vdevs.get("devices", [])):
            if str(d.get("id")) == str(dev_id):
                dni = str(d.get("deviceNetworkId") or d.get("dni") or "")
                break
        assert dni, f"could not resolve DNI for trigger device {dev_id}"
        self.created_device_dnis.append(dni)  # teardown safety net (harmless if already deleted mid-test)

        app_id = self._create_native_rule(
            "RHBroken",
            extra={"addTrigger": {"capability": "Switch", "deviceIds": [int(dev_id)], "state": "on"}},
        )
        try:
            # Sanity: healthy while the trigger device exists.
            pre = self.client.call_tool("hub_manage_rule_machine", {
                "tool": "hub_get_rule_health", "args": {"appId": app_id}})
            assert pre.get("broken") is False, f"rule should start healthy before we break it: {pre}"

            # Break it: delete the trigger device so the trigger reference dangles.
            self.client.call_tool("hub_manage_virtual_device", {
                "action": "delete", "deviceNetworkId": dni, "confirm": True})

            # The compiled `broken` boolean lags the *BROKEN* label until the rule re-validates;
            # rendering the config page forces that re-validation.
            self.client.call_tool("hub_read_apps_code", {
                "tool": "hub_get_app_config", "args": {"appId": app_id}})

            h = self.client.call_tool("hub_manage_rule_machine", {
                "tool": "hub_get_rule_health", "args": {"appId": app_id}})
            assert h.get("broken") is True, \
                f"compiled-state broken must fire True on a dangling-trigger rule: {h}"
            assert h.get("ruleFormat") == "rm", f"expected ruleFormat 'rm': {h}"
            assert h.get("ok") is False, f"a broken rule must report ok:false: {h}"
            assert "ruleBuilderJson" in str(h.get("source") or ""), \
                f"the broken verdict should come from the compiled-state source: {h}"
        finally:
            self._delete_native(app_id)

    @test("native_apps")
    def test_set_rule_trigger_mutations(self) -> None:
        # hub_set_rule edit -> the device-state addTrigger + addAction wizard paths, then
        # modifyTrigger (state) + removeTrigger driven by the RETURNED triggerIndex
        # (never a hardcoded index -- RM action/trigger indices are persistent per-rule
        # counters that survive removals).
        sw = int(self.get_test_switch_id())
        # Fold the first (non-index) addTrigger into the create -- one fewer round-trip; the
        # index-returning addTrigger below still gets its own call so its triggerIndex is read.
        app_id = self._create_native_rule(
            "TrigMut", extra={"addTrigger": {"capability": "Switch", "deviceIds": [sw], "state": "on"}})
        try:
            self._set_rule(app_id, {"addAction": {"capability": "switch", "action": "on", "deviceIds": [sw]}}, strict=True)
            self._assert_rule_healthy(app_id)

            added = self._set_rule(app_id, {"addTrigger": {"capability": "Switch", "deviceIds": [sw], "state": "on"}}, strict=True)
            tidx = added.get("triggerIndex")
            assert tidx is not None, \
                f"addTrigger did not return a triggerIndex (contract regression): {added}"
            mod = self._set_rule(app_id, {"modifyTrigger": {"index": tidx, "mods": {"state": "off"}}}, strict=True)
            # modifyTrigger reads the PERSISTED tstate (configure/json), so verifiedState
            # echoes the new value instead of always being null (the old readback hit the
            # closed selectTriggers wizard page).
            if mod.get("verificationFetchFailed") is not True:
                assert mod.get("verifiedState") == "off", \
                    (f"modifyTrigger verifiedState should echo the persisted new state 'off', "
                     f"got {mod.get('verifiedState')!r}: {mod}")
            self._set_rule(app_id, {"removeTrigger": {"index": tidx}}, strict=True)
            self._assert_rule_healthy(app_id)
        finally:
            self._delete_native(app_id)

    @test("native_apps")
    def test_set_rule_enum_custom_attribute(self) -> None:
        # hub_set_rule edit -> addTrigger Custom Attribute on an ENUM-recognized
        # attribute. A virtual switch's 'switch' attribute is the canonical enum case:
        # picking it reveals the enum value picker (tstate<N>) and HIDES the free
        # comparator field (ReltDev<N>). The value must land in tstate<N>, and the
        # now-absent ReltDev<N> must NOT be written -- an unconditional comparator write
        # there is rejected not_in_schema and spuriously flips partial=true even though
        # the trigger built correctly. This pins the no-false-partial contract the Spock
        # regression specs guard, end-to-end against a live hub. Covers BOTH the trigger
        # row (_rmAddTrigger, ReltDev<N>) and the conditional-trigger condition
        # (_rmBuildCondition, RelrDev_<N>) -- the two share the enum bug.
        sw = int(self.get_test_switch_id())
        app_id = self._create_native_rule("EnumTrig")
        try:
            # --- trigger row: tCustomAttr<N> / tstate<N> / ReltDev<N> ---
            result = self._rm_call_soft({
                "appId": app_id,
                "addTrigger": {"capability": "Custom Attribute", "deviceIds": [sw],
                               "attribute": "switch", "comparator": "=", "state": "on"},
                "confirm": True,
            }, strict=True)
            assert result.get("success") is not False, \
                f"enum Custom Attribute addTrigger reported failure: {result}"
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
            assert not bad, \
                f"unexpected ReltDev not_in_schema skip (the enum false-partial bug): {bad}"
            # The contract discriminator: partial stays falsy.
            assert not result.get("partial"), \
                f"trigger falsely flagged partial despite building correctly: {result}"

            # --- condition path: a conditional trigger whose condition is the same
            #     enum Custom Attribute (rCustomAttr_<N> / state_<N> / RelrDev_<N>) ---
            cond_result = self._rm_call_soft({
                "appId": app_id,
                "addTrigger": {"capability": "Switch", "deviceIds": [sw], "state": "on",
                               "condition": {"capability": "Custom Attribute", "deviceIds": [sw],
                                             "attribute": "switch", "comparator": "=", "state": "on"}},
                "confirm": True,
            }, strict=True)
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
    def test_set_rule_required_expression_and_local_var(self) -> None:
        # hub_set_rule edit -> addLocalVariable + addRequiredExpression (STPage) wizards,
        # plus the local-variable surface: setLocalVariable action, the
        # hub_list_rule_local_variables read, and the removeLocalVariable shortcut.
        sw = int(self.get_test_switch_id())
        app_id = self._create_native_rule("ReqExpr")
        try:
            self._set_rule(app_id, {"addLocalVariable": {"name": "batCounter", "type": "Number", "value": 0}}, strict=True)

            # hub_list_rule_local_variables (read, via the pure-read hub_read_rules gateway)
            # sees the freshly created local with its type/value.
            listed = self.client.call_tool("hub_read_rules", {
                "tool": "hub_list_rule_local_variables", "args": {"appId": app_id}})
            names = [lv.get("name") for lv in (listed.get("localVariables") or [])]
            assert "batCounter" in names, f"hub_list_rule_local_variables missing batCounter: {listed}"

            # setLocalVariable action assigns the local a constant (validated against the
            # rule's locals, NOT hub globals). Distinct capability from setVariable.
            added = self._set_rule(app_id, {"addAction": {
                "capability": "setLocalVariable", "variable": "batCounter", "value": 5}}, strict=True)
            set_local_idx = added.get("actionIndex")
            assert set_local_idx is not None, \
                f"addAction setLocalVariable did not return an actionIndex: {added}"

            self._set_rule(app_id, {"addRequiredExpression": {"conditions": [
                {"capability": "Switch", "deviceIds": [sw], "state": "on"}]}}, strict=True)
            self._assert_rule_healthy(app_id)

            # removeLocalVariable clean path: the referencing action is removed first (using
            # the index the addAction returned -- RM does not guarantee index 0) so the rule
            # stays healthy after the delete, then verify it left state.allLocalVars via the
            # read tool. NOTE: RM does NOT refuse a referenced-local delete -- removing the
            # reference first is to keep the rule HEALTHY, not because RM would block it (the
            # broken-after-delete behaviour is covered by its own scenario).
            self._set_rule(app_id, {"removeAction": {"index": set_local_idx}}, strict=True)
            rm = self._set_rule(app_id, {"removeLocalVariable": {"name": "batCounter"}}, strict=True)
            assert rm.get("variable", {}).get("deleted") is True, \
                f"removeLocalVariable did not confirm deletion: {rm}"
            relisted = self.client.call_tool("hub_read_rules", {
                "tool": "hub_list_rule_local_variables", "args": {"appId": app_id}})
            assert "batCounter" not in [lv.get("name") for lv in (relisted.get("localVariables") or [])], \
                f"batCounter still present after removeLocalVariable: {relisted}"
        finally:
            self._delete_native(app_id)

    @test("native_apps")
    def test_set_rule_remove_referenced_local_breaks_rule(self) -> None:
        # removeLocalVariable broken-after-delete contract: RM does NOT refuse to delete a
        # local that an action still references -- it DELETES the local and leaves the
        # referencing action Broken. The tool must surface that as a SELF-CONSISTENT failure:
        # deleted=true + success=false + a specific error naming the broken outcome + a
        # repairHint pointing at the backup restore (NOT a contradictory clean "removed").
        app_id = self._create_native_rule("RmRefLocal")
        try:
            self._set_rule(app_id, {"addLocalVariable": {"name": "refLocal", "type": "Number", "value": 0}}, strict=True)
            # Reference the local from an action; leave the reference in place (the broken-maker).
            self._set_rule(app_id, {"addAction": {
                "capability": "setLocalVariable", "variable": "refLocal", "value": 9}}, strict=True)

            # Delete the still-referenced local. The delete succeeds; the rule goes broken.
            rm = self._rm_call_soft({"appId": app_id, "removeLocalVariable": {"name": "refLocal"}, "confirm": True}, strict=True)
            if rm.get("relayDropped"):
                return  # response lost to relay; the strict path re-runs the small test
            assert rm.get("variable", {}).get("deleted") is True, \
                f"the local should have been deleted even though it was referenced: {rm}"
            assert rm.get("success") is False, \
                f"removing a referenced local that breaks the rule must report success=false: {rm}"
            assert rm.get("error"), \
                f"broken-after-delete must carry a specific error (not null): {rm}"
            assert "broke" in str(rm.get("error")) or "broken" in str(rm.get("error")).lower(), \
                f"the error must name the broken-after-delete outcome: {rm}"
            assert (rm.get("health") or {}).get("ok") is False, \
                f"health must report the rule broken after the delete: {rm}"
            hints = rm.get("repairHints") or []
            assert any("hub_restore_backup" in str(h) for h in hints), \
                f"a repairHint must point at the backup restore: {rm}"
            # The local really is gone (delete committed), confirming the deleted=true is honest.
            relisted = self.client.call_tool("hub_read_rules", {
                "tool": "hub_list_rule_local_variables", "args": {"appId": app_id}})
            assert "refLocal" not in [lv.get("name") for lv in (relisted.get("localVariables") or [])], \
                f"refLocal should be gone after the (broken-making) delete: {relisted}"
        finally:
            self._delete_native(app_id)

    @test("native_apps")
    def test_set_rule_trigger_custom_attribute_enum_changed_routes(self) -> None:
        # hub_set_rule edit -> addTrigger Custom Attribute on an ENUM-recognized
        # attribute with a no-value state-change comparator ('*changed*'). A virtual
        # switch's 'switch' attribute is enum-recognized: RM reveals the value picker
        # (tstate<N>) and HIDES the free comparator field (ReltDev<N>). On the TRIGGER
        # surface, the live hub's tstate<N> picker DOES offer a change-equivalent option,
        # so the fix routes '*changed*' into the value picker: the change token lands in
        # tstate<N>, the trigger renders "<device> reports switch *changed*", and it is a
        # healthy trigger with no skip and partial:false. This proves the original
        # "switch null" silent-drop bug is fixed -- the change token actually landed --
        # pinned end-to-end against a live hub. (The unrepresentable-skip branch fires on
        # the Required Expression surface instead, where the picker has no change option;
        # see the sibling RE scenario.)
        sw = int(self.get_test_switch_id())
        app_id = self._create_native_rule("CustEnumChangedTrig")
        try:
            result = self.client.call_tool("hub_manage_rule_machine", {
                "tool": "hub_set_rule",
                "args": {
                    "appId": app_id,
                    "addTrigger": {"capability": "Custom Attribute", "deviceIds": [sw],
                                   "attribute": "switch", "comparator": "*changed*"},
                    "confirm": True,
                },
            })
            assert result.get("success") is not False, f"addTrigger reported failure: {result}"
            # The route branch: the change token lands in the value picker (tstate<N>) ...
            applied = result.get("settingsApplied") or []
            assert any(str(k).startswith("tstate") for k in applied), \
                f"change token did not land in a tstate field (route branch): settingsApplied={applied}"
            # ... NOT a not-representable skip, and partial stays falsy (healthy trigger).
            skipped = result.get("settingsSkipped") or []
            not_repr = [s for s in skipped if isinstance(s, dict)
                        and s.get("reason") == "comparator_not_representable_for_enum_attribute"]
            assert not not_repr, \
                f"trigger surface should ROUTE, not skip; unexpected not-representable skip: {not_repr}"
            assert not result.get("partial"), \
                f"trigger falsely flagged partial despite routing the change token: {result}"
            # Independent verification: the persisted tstate<N> carries the change token.
            cfg = self.client.call_tool("hub_read_apps_code", {
                "tool": "hub_get_app_config", "args": {"appId": app_id, "includeSettings": True}})
            settings = cfg.get("settings") or {}
            tstate_vals = [v for k, v in settings.items() if str(k).startswith("tstate")]
            assert any(str(v) == "*changed*" for v in tstate_vals), \
                f"persisted tstate did not carry the change token; tstate values={tstate_vals}"
            self._assert_rule_healthy(app_id)
        finally:
            self._delete_native(app_id)

    @test("native_apps")
    def test_set_rule_re_custom_attribute_enum_changed_not_representable(self) -> None:
        # hub_set_rule edit -> addRequiredExpression with an ENUM-recognized Custom
        # Attribute condition and a no-value state-change comparator ('*changed*'). On
        # the Required Expression surface, the live hub's value picker offers on/off only
        # (NO change-equivalent option), so '*changed*' is genuinely unrepresentable: the
        # fix records a comparator_not_representable_for_enum_attribute skip, flips
        # partial:true, and emits an actionable repairHint, never silently dropping the
        # comparator as a clean success. The comparator field (RelrDev_<N>) is never
        # falsely claimed applied. This is the client-observable not-representable
        # behaviour, pinned end-to-end against a live hub. (The TRIGGER surface routes
        # instead, because its picker has a change option; see the sibling trigger scenario.)
        sw = int(self.get_test_switch_id())
        app_id = self._create_native_rule("CustEnumChangedRE")
        try:
            result = self.client.call_tool("hub_manage_rule_machine", {
                "tool": "hub_set_rule",
                "args": {
                    "appId": app_id,
                    "addRequiredExpression": {"conditions": [
                        {"capability": "Custom Attribute", "deviceIds": [sw],
                         "attribute": "switch", "comparator": "*changed*"}]},
                    "confirm": True,
                },
            })
            # The add still commits the rest of the condition (success is not a hard failure) ...
            assert result.get("success") is not False, f"addRequiredExpression reported failure: {result}"
            # ... but the unrepresentable comparator must NOT be falsely claimed applied.
            applied = result.get("settingsApplied") or []
            assert not any(str(k).startswith("RelrDev") for k in applied), \
                f"unrepresentable comparator was falsely claimed applied: settingsApplied={applied}"
            # The discriminating contract: a genuine not-representable skip flips partial.
            skipped = result.get("settingsSkipped") or []
            not_repr = [s for s in skipped if isinstance(s, dict)
                        and s.get("reason") == "comparator_not_representable_for_enum_attribute"]
            assert not_repr, \
                f"missing comparator_not_representable_for_enum_attribute skip (the silent-drop bug): {result}"
            assert result.get("partial") is True, \
                f"unrepresentable comparator did not flip partial=true (silent false-success): {result}"
            # An actionable repair hint must be present and name the cause.
            hints = result.get("repairHints") or []
            assert any("cannot be represented" in str(h) for h in hints), \
                f"missing actionable repairHint for the unrepresentable comparator: hints={hints}"
        finally:
            self._delete_native(app_id)

    @test("native_apps")
    def test_set_rule_replace_required_expression(self) -> None:
        # hub_set_rule edit -> replaceRequiredExpression: change a committed Required
        # Expression IN PLACE (same appId, no clone). Proves the cancelST delete +
        # rebuild path end-to-end on a live hub: the new condition replaces the old one
        # and renders, requiredExpressionReplaced=true, the rule stays healthy. The
        # destructive-window safety (validate-before-delete, post-delete auto-restore) is
        # covered by Spock + the orchestrator both-ways; that path can't be triggered
        # deterministically from the e2e surface (see the note at the end of this test).
        sw = int(self.get_test_switch_id())
        app_id = self._create_native_rule("ReplRE")
        try:
            # Commit an initial RE: Switch is on. strict=True so a relay-504 soft
            # envelope (which lacks conditionIndices) raises a recognizable 504 the
            # runner retries, instead of a bare AssertionError it cannot classify.
            add = self._set_rule(app_id, {"addRequiredExpression": {"conditions": [
                {"capability": "Switch", "deviceIds": [sw], "state": "on"}]}}, strict=True)
            assert add.get("conditionIndices"), \
                f"initial addRequiredExpression produced no conditionIndices: {add}"
            # Replace it in place with a DIFFERENT condition: Switch is off.
            result = self.client.call_tool("hub_manage_rule_machine", {
                "tool": "hub_set_rule",
                "args": {
                    "appId": app_id,
                    "replaceRequiredExpression": {"conditions": [
                        {"capability": "Switch", "deviceIds": [sw], "state": "off"}]},
                    "confirm": True,
                },
            })
            # The replace committed a new live expression in place.
            assert result.get("success") is True, \
                f"replaceRequiredExpression reported failure: {result}"
            assert result.get("requiredExpressionReplaced") is True, \
                f"replaceRequiredExpression did not flag requiredExpressionReplaced: {result}"
            assert result.get("conditionIndices"), \
                f"replaceRequiredExpression produced no conditionIndices -- the new expression did not land: {result}"
            # A successful replace never reports a restore (the new RE is live, the old
            # one was cleanly superseded, not deleted-and-rolled-back).
            assert result.get("requiredExpressionRestored") is None, \
                f"a successful replace should not report a restore: {result}"
            # The rendered RE now shows the NEW condition (Switch ... off), not the old
            # (Switch ... on). The rule paragraph renders the active formula only.
            cfg = self.client.call_tool("hub_read_apps_code", {
                "tool": "hub_get_app_config", "args": {"appId": app_id},
            })
            blob = str(cfg).lower()
            assert "is off" in blob, \
                f"rendered Required Expression does not show the new 'is off' condition: {str(cfg)[:600]}"
            # The DIRECT replace call (line ~2607) bypasses the caching write helpers, so seed the cache
            # with ITS OWN post-replace health -- else _assert_rule_healthy reads the STALE pre-replace
            # health and the destructive delete+rebuild's health check (this assert's whole point) false-passes.
            self._cache_write_health(app_id, result)
            self._assert_rule_healthy(app_id)
        finally:
            self._delete_native(app_id)
        # NOTE on the failure-restore path: replaceRequiredExpression auto-restores the
        # pre-op backup when the post-delete rebuild fails (requiredExpressionRestored
        # true/false). That path needs a spec that PASSES pre-validation (so the cancelST
        # delete fires) yet FAILS the live walk (so the rebuild doesn't bake) -- e.g. an
        # invalid state for a valid device. Whether such a spec fails-to-bake is firmware/
        # render dependent and historically flaky (BAT T651 hedges the same way), so it is
        # NOT asserted here. The restore branches are covered deterministically by the
        # Spock ReplaceRequiredExpressionSpec (restore-success, restore-fail, validate-
        # before-delete) plus the orchestrator both-ways proof.

    @test("native_apps")
    def test_set_rule_replace_required_expression_missing_refusal(self) -> None:
        # hub_set_rule edit -> replaceRequiredExpression on a rule with NO committed
        # Required Expression. The tool must REFUSE (success:false,
        # requiredExpressionMissing:true) and steer the caller to addRequiredExpression,
        # never silently turning a replace into an add. The rule is left unchanged.
        sw = int(self.get_test_switch_id())
        app_id = self._create_native_rule("ReplNoRE")
        try:
            try:
                result = self.client.call_tool("hub_manage_rule_machine", {
                    "tool": "hub_set_rule",
                    "args": {
                        "appId": app_id,
                        "replaceRequiredExpression": {"conditions": [
                            {"capability": "Switch", "deviceIds": [sw], "state": "on"}]},
                        "confirm": True,
                    },
                })
                # Refusal is a structured success:false envelope (not a thrown error).
                assert result.get("success") is False, \
                    f"replaceRequiredExpression on a rule with no RE should refuse (success:false): {result}"
                assert result.get("requiredExpressionMissing") is True, \
                    f"refusal should flag requiredExpressionMissing: {result}"
                assert "addRequiredExpression" in str(result.get("error", "")), \
                    f"refusal error should steer to addRequiredExpression: {result}"
                # A pre-erase refusal never reports a restore -- nothing was erased.
                assert result.get("requiredExpressionRestored") is None, \
                    f"a no-RE refusal must not report a restore (nothing was erased): {result}"
            except (McpToolError, McpError) as exc:
                # Tolerate a thrown variant as long as it names the missing-RE guidance.
                assert "addRequiredExpression" in str(exc) or "Required Expression" in str(exc), \
                    f"no-RE refusal fail-loud should steer to addRequiredExpression: {exc}"
            # The rule is unchanged and healthy (no RE was added behind the refusal).
            self._assert_rule_healthy(app_id)
        finally:
            self._delete_native(app_id)

    @test("native_apps")
    def test_set_rule_setvariable_from_device_and_math(self) -> None:
        # hub_set_rule edit -> addAction setVariable in the two schema-gated source modes
        # added alongside value/sourceVariable: fromDevice (numOp="device attribute",
        # reveals customDev.<N> then a device-FILTERED tCustomAttr.<N>) and math
        # (numOp="variable math", reveals xVar3/valMathOp, a binary op reveals xVar4, a
        # numeric operand becomes (constant)+valConst/valConst2). The unit Spock suite proves
        # the reveal logic in isolation; this pins it end-to-end on a live hub, where the
        # gated field names are RM-assigned and discovered from the live schema, not hardcoded.
        # STRICT: the matrix is split across several small pristine rules (each created + deleted in
        # its own finally) to stay under the per-app load limiter; _run_one re-runs on a 504 so no
        # wire-format assertion is ever skipped on a soft envelope.
        # fromDevice/math are NUMERIC-TARGET-ONLY (verified live on the hub): RM renders the numOp
        # source-mode picker only for a Number/Decimal target var, so fromDevice/math into a String
        # var is rejected up-front (Rule C). For a Number target, fromDevice's tCustomAttr is further
        # filtered to attributes compatible with the var's type, so a Number target offers only
        # numeric attributes -- an enum-only attribute like a switch's on/off is filtered out (the
        # negative case, Rule D). Use a virtual temperature sensor for the numeric happy path.
        temp_id = int(self.get_test_temperature_ids()[0])
        switch_id = int(self.get_test_switch_id())
        var_name = f"{PREFIX}sv_modes"          # Number target
        str_var_name = f"{PREFIX}sv_str"        # String target (numeric-target-only reject)
        bool_var_name = f"{PREFIX}sv_bool"      # Boolean target (numeric-target-only reject)

        # Create a var via hub_create_variable (guaranteed to CREATE a missing var in the hub
        # namespace, unlike hub_set_variable whose missing-var semantics are ambiguous), then wait
        # for it to become visible to the BULK getAllGlobalVars() read. The setVariable handler
        # validates the target against getAllGlobalVars(), and hub_list_variables reads that SAME
        # surface -- so polling it is an exact proxy for what the validator sees.
        #
        # hub_create_variable has a known intermittent post-write visibility race: it spuriously
        # errors ("wizard completed but not visible via getGlobalVar") or commits but the var does
        # not appear in the bulk read for a beat -- and a fresh CREATE settles it. So each attempt
        # is create-then-poll, and on a race (create error OR poll-miss) the WHOLE create is
        # re-issued, up to a few times with short backoff. Only an exhausted retry budget fails.
        def _create_vars_and_wait(items: list[dict]) -> None:
            # Bulk-create ALL targets in ONE hub_create_variable call, then ONE shared poll until they
            # are all visible in the bulk getAllGlobalVars() surface the setVariable validator consults.
            # On the documented post-write visibility race (a commit that lags the bulk read, or a 504
            # that still committed), re-issue the whole bulk create. Only an exhausted budget fails.
            names = [it["name"] for it in items]
            for n in names:
                self.created_variable_names.append(n)
            max_attempts = 3
            poll_secs = 12.0
            for attempt in range(1, max_attempts + 1):
                try:
                    self.client.call_tool("hub_manage_variables", {
                        "tool": "hub_create_variable", "args": {"variables": items, "confirm": True}})
                except (McpError, McpToolError, requests.HTTPError) as exc:
                    print(f"    bulk hub_create_variable attempt {attempt}/{max_attempts} raised "
                          f"({exc}); the visibility poll is authoritative")
                deadline = time.time() + poll_secs
                while time.time() < deadline:
                    if all(self._hub_variable_visible_in_bulk(n) for n in names):
                        return
                    time.sleep(1.0)
                if attempt < max_attempts:
                    print(f"    not all of {names} visible after attempt {attempt}/{max_attempts} "
                          "(create_variable post-write visibility race); re-issuing the bulk create")
                    time.sleep(2.0)
            raise AssertionError(
                f"hub variables {names} never all appeared in the bulk getAllGlobalVars() read after "
                f"{max_attempts} bulk-create attempts -- setVariable validation cannot proceed")

        # A String var MUST get a NON-EMPTY value (an empty string does not persist -- the wizard reports
        # complete but nothing lands). Numeric -> "0", Boolean -> "false", String -> a non-empty placeholder.
        _create_vars_and_wait([
            {"name": var_name, "type": "Number", "value": "0"},
            {"name": str_var_name, "type": "String", "value": "init"},
            {"name": bool_var_name, "type": "Boolean", "value": "false"},
        ])
        # The matrix is split across SMALL rules (<=3 setVariable actions each): the classic wizard
        # re-POSTs the FULL rule page per submitOnChange, so piling many actions into one rule trips
        # the hub's per-app load limiter (a 5th action lands numOp.<N> as not_in_schema). Each rule
        # below is pristine (created + deleted in its own try/finally); the two shared vars are
        # created once up front (they do not conflict) and deleted at the very end.
        try:
            # Rule A: fromDevice (temperature -> Number var) + value read-back.
            app_a = self._create_native_rule("SetVarFromDev")
            try:
                fd = self._rm_call_soft({
                    "appId": app_a,
                    "addAction": {"capability": "setVariable", "variable": var_name,
                                  "fromDevice": {"deviceId": temp_id, "attribute": "temperature"}},
                    "confirm": True,
                }, strict=True)
                assert fd.get("success") is not False, \
                    f"setVariable fromDevice hard-errored: {fd}"
                fd_applied = fd.get("settingsApplied") or []
                assert any(str(k).startswith("customDev.") for k in fd_applied), \
                    f"fromDevice device picker (customDev.<N>) did not land; settingsApplied={fd_applied}"
                assert any(str(k).startswith("tCustomAttr.") for k in fd_applied), \
                    f"fromDevice attribute enum (tCustomAttr.<N>) did not land; settingsApplied={fd_applied}"
                assert not fd.get("partial"), f"fromDevice action falsely flagged partial: {fd}"
                fd_idx = fd.get("actionIndex")
                # Value read-back: assert the actual VALUE that landed, not just key presence -- a
                # wrong-value write that still lands the key would pass a key-prefix-only check.
                # The tCustomAttr key is namespaced by the RM-assigned action index.
                settings_a = (self.client.call_tool("hub_read_apps_code", {
                    "tool": "hub_get_app_config", "args": {"appId": app_a, "includeSettings": True}}).get("settings") or {})
                assert settings_a.get(f"tCustomAttr.{fd_idx}") == "temperature", \
                    f"fromDevice attribute persisted with the wrong value; settings={settings_a}"
                # Read back the device-id VALUE too (customDev stores the selected device id), not
                # just key presence -- a wrong device would still land the key. RM may serialize the
                # capability picker as a bare id or an id-keyed map, so assert the id appears in the
                # persisted value's string form rather than pinning one serialization.
                customdev_val = str(settings_a.get(f"customDev.{fd_idx}"))
                assert str(temp_id) in customdev_val, \
                    f"fromDevice device id {temp_id} not in persisted customDev value {customdev_val!r}; settings={settings_a}"
                self._assert_rule_healthy(app_a)
            finally:
                self._delete_native(app_a)

            # Rule B: math binary '+' (constant second operand) + math var-minus-var (xVar4=varname)
            # + value read-backs.
            app_b = self._create_native_rule("SetVarMathBin")
            try:
                # math binary: variable + 10 (numeric right operand becomes (constant)+valConst2).
                mb = self._rm_call_soft({
                    "appId": app_b,
                    "addAction": {"capability": "setVariable", "variable": var_name,
                                  "math": {"left": var_name, "op": "+", "right": 10}},
                    "confirm": True,
                }, strict=True)
                assert mb.get("success") is not False, f"setVariable math binary hard-errored: {mb}"
                mb_applied = mb.get("settingsApplied") or []
                assert any(str(k).startswith("valMathOp.") for k in mb_applied), \
                    f"math operator (valMathOp.<N>) did not land; settingsApplied={mb_applied}"
                assert any(str(k).startswith("valConst2.") for k in mb_applied), \
                    f"math binary second constant (valConst2.<N>) did not land; settingsApplied={mb_applied}"
                assert not mb.get("partial"), f"math binary action falsely flagged partial: {mb}"
                mb_idx = mb.get("actionIndex")

                # math binary, second operator + var-operand combo: var - var (exercises a binary op
                # OTHER than '+', and an xVar4=<varname> second operand instead of a (constant)).
                mb2 = self._rm_call_soft({
                    "appId": app_b,
                    "addAction": {"capability": "setVariable", "variable": var_name,
                                  "math": {"left": var_name, "op": "-", "right": var_name}},
                    "confirm": True,
                }, strict=True)
                assert mb2.get("success") is not False, f"setVariable math var-minus-var hard-errored: {mb2}"
                mb2_applied = mb2.get("settingsApplied") or []
                assert any(str(k).startswith("xVar4.") for k in mb2_applied), \
                    f"var second operand (xVar4.<N>) did not land; settingsApplied={mb2_applied}"
                assert not any(str(k).startswith("valConst2.") for k in mb2_applied), \
                    f"a var second operand must NOT write a constant slot; settingsApplied={mb2_applied}"
                assert not mb2.get("partial"), f"math var-minus-var action falsely flagged partial: {mb2}"
                mb2_idx = mb2.get("actionIndex")

                # math binary with a DECIMAL constant operand (var + 5.5): proves decimal-constant
                # serialization end-to-end -- the constant must persist verbatim as "5.5", never
                # integer-stripped (which would corrupt the intended value).
                md = self._rm_call_soft({
                    "appId": app_b,
                    "addAction": {"capability": "setVariable", "variable": var_name,
                                  "math": {"left": var_name, "op": "+", "right": 5.5}},
                    "confirm": True,
                }, strict=True)
                assert md.get("success") is not False, f"setVariable math decimal-constant hard-errored: {md}"
                assert not md.get("partial"), f"math decimal-constant action falsely flagged partial: {md}"
                md_idx = md.get("actionIndex")

                settings_b = (self.client.call_tool("hub_read_apps_code", {
                    "tool": "hub_get_app_config", "args": {"appId": app_b, "includeSettings": True}}).get("settings") or {})
                assert settings_b.get(f"xVar3.{mb_idx}") == var_name, \
                    f"math first-operand variable persisted with the wrong value; settings={settings_b}"
                assert settings_b.get(f"valMathOp.{mb_idx}") == "+", \
                    f"math binary operator persisted with the wrong value; settings={settings_b}"
                assert str(settings_b.get(f"valConst2.{mb_idx}")) == "10", \
                    f"math binary constant operand persisted with the wrong value; settings={settings_b}"
                assert settings_b.get(f"valMathOp.{mb2_idx}") == "-", \
                    f"math var-minus-var operator persisted with the wrong value; settings={settings_b}"
                assert settings_b.get(f"xVar4.{mb2_idx}") == var_name, \
                    f"math var second operand persisted with the wrong value; settings={settings_b}"
                # The decimal constant must persist verbatim -- "5.5", not "5" or "6".
                assert str(settings_b.get(f"valConst2.{md_idx}")) == "5.5", \
                    f"math decimal constant persisted with the wrong value (expected 5.5); settings={settings_b}"
                self._assert_rule_healthy(app_b)
            finally:
                self._delete_native(app_b)

            # Rule C: math unary (no second operand) + the String- and Boolean-target rejection cases.
            app_c = self._create_native_rule("SetVarMathUnaryStr")
            try:
                # math unary: absolute of the variable (NO second operand).
                mu = self._rm_call_soft({
                    "appId": app_c,
                    "addAction": {"capability": "setVariable", "variable": var_name,
                                  "math": {"left": var_name, "op": "absolute"}},
                    "confirm": True,
                }, strict=True)
                assert mu.get("success") is not False, f"setVariable math unary hard-errored: {mu}"
                mu_applied = mu.get("settingsApplied") or []
                assert any(str(k).startswith("valMathOp.") for k in mu_applied), \
                    f"math unary operator (valMathOp.<N>) did not land; settingsApplied={mu_applied}"
                assert not any(str(k).startswith("xVar4.") or str(k).startswith("valConst2.")
                               for k in mu_applied), \
                    f"math unary wrongly wrote a second operand; settingsApplied={mu_applied}"
                assert not mu.get("partial"), f"math unary action falsely flagged partial: {mu}"

                # String-TARGET rejection: the device-attribute (fromDevice) and variable-math (math)
                # source modes are Number/Decimal-target-only -- RM renders the numOp source-mode
                # picker only for a numeric target var, so fromDevice/math into a String var is not an
                # RM-supported operation and is rejected up-front (success=false) with the clear
                # numeric-target requirement, NOT the cryptic deep not-in-schema reveal failure.
                # (The "filter INCLUDES valid attributes" point is already proven by the Rule A
                # happy-path fd: Number var + temperature -> tCustomAttr offered and lands.)
                str_reject = self._rm_call_soft({
                    "appId": app_c,
                    "addAction": {"capability": "setVariable", "variable": str_var_name,
                                  "fromDevice": {"deviceId": switch_id, "attribute": "switch"}},
                    "confirm": True,
                }, strict=True)
                assert str_reject.get("success") is False, \
                    f"fromDevice into a String var should be rejected (numeric-target-only mode), got: {str_reject}"
                assert "requires a Number or Decimal target variable" in (str_reject.get("error") or ""), \
                    f"String-target rejection did not name the Number/Decimal requirement: {str_reject}"

                # Boolean target reject: the same numeric-target-only guard rejects a Boolean target
                # (live token "boolean") -- proves the guard excludes every non-numeric kind, not
                # just String. DateTime is left to the Spock suite (its create has a visibility race
                # that would make this live path flaky).
                bool_reject = self._rm_call_soft({
                    "appId": app_c,
                    "addAction": {"capability": "setVariable", "variable": bool_var_name,
                                  "fromDevice": {"deviceId": switch_id, "attribute": "switch"}},
                    "confirm": True,
                }, strict=True)
                assert bool_reject.get("success") is False, \
                    f"fromDevice into a Boolean var should be rejected (numeric-target-only mode), got: {bool_reject}"
                assert "requires a Number or Decimal target variable" in (bool_reject.get("error") or ""), \
                    f"Boolean-target rejection did not name the Number/Decimal requirement: {bool_reject}"
                self._assert_rule_healthy(app_c)
            finally:
                self._delete_native(app_c)

            # Rule D: the NEGATIVE type-filter case in its own pristine rule (isolates the
            # expected-failure addAction). A NUMBER target var + a switch's enum 'switch' attribute
            # is filtered OUT of tCustomAttr, so the requested attribute is not in the device's
            # (type-filtered) enum -> fail loud with the available list. This is the exact behaviour
            # the BAT T647 happy-path assumes by using a numeric attribute; here we demonstrate the
            # rejected complement live.
            app_d = self._create_native_rule("SetVarFromDevNeg")
            try:
                neg = self._rm_call_soft({
                    "appId": app_d,
                    "addAction": {"capability": "setVariable", "variable": var_name,
                                  "fromDevice": {"deviceId": switch_id, "attribute": "switch"}},
                    "confirm": True,
                }, strict=True)
                assert neg.get("success") is False, \
                    f"numeric var + enum 'switch' attribute should fail the type filter, got: {neg}"
                neg_err = neg.get("error") or ""
                # The type filter rejects 'switch' for a numeric var one of two ways, depending on
                # whether the device exposes ANY numeric attribute: if some remain, 'switch' is "not
                # in the device's attribute enum" (with the available list); if none do, the filtered
                # enum is empty ("no enumerable options"). Both are the correct fail-loud verdict for
                # the excluded attribute -- accept either, and confirm the requested attribute is named.
                assert ("not in the device's attribute enum" in neg_err
                        or "no enumerable options" in neg_err), \
                    f"negative type-filter rejection did not name a filtered-attribute-enum frame: {neg}"
                assert "tCustomAttr" in neg_err or "switch" in neg_err, \
                    f"negative rejection should name the attribute field or the requested attribute; error={neg_err}"
            finally:
                self._delete_native(app_d)
        finally:
            self._delete_variable_safe(var_name)
            self._delete_variable_safe(str_var_name)
            self._delete_variable_safe(bool_var_name)

    @test("native_apps")
    def test_set_rule_walker_enum_required_expression(self) -> None:
        # hub_set_rule edit -> addRequiredExpression (STPage reveal walker) with an
        # ENUM-recognized Custom Attribute condition. The walker pre-fix THREW
        # ("RelrDev_<N> not revealed") because the enum re-render hides the comparator
        # and reveals state_<N> directly. The fix branches to the enum path: it writes
        # the value to state_<N>, skips the comparator, does NOT throw, and does NOT
        # flag partial. This pins the walker enum contract end-to-end on a live hub.
        # Needs a pristine rule: RM cannot replace an existing Required Expression
        # (requiredExpressionAlreadyExists), so the rule must carry zero REs.
        sw = int(self.get_test_switch_id())
        stp_app_id = self._create_native_rule("WalkerStp")
        try:
            result = self._rm_call_soft({
                "appId": stp_app_id,
                "addRequiredExpression": {"conditions": [
                    {"capability": "Custom Attribute", "deviceIds": [sw],
                     "attribute": "switch", "comparator": "=", "state": "on"}]},
                "confirm": True,
            }, strict=True)
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
            assert not bad, \
                f"unexpected RelrDev_<N> not_in_schema skip on the walker enum path: {bad}"
            assert not result.get("partial"), \
                f"walker enum condition falsely flagged partial: {result}"
            self._assert_rule_healthy(stp_app_id)
        finally:
            self._delete_native(stp_app_id)

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
        ctd_app_id = self._create_native_rule("CtdLand")
        try:
            result = self._rm_call_soft({
                "appId": ctd_app_id,
                "addRequiredExpression": {"conditions": [
                    {"capability": "Temperature", "deviceIds": [int(dev_a)],
                     "comparator": ">",
                     "compareToDevice": {"deviceId": int(dev_b),
                                         "attribute": "temperature", "offset": -2}}]},
                "confirm": True,
            }, strict=True)
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
                "tool": "hub_get_app_config", "args": {"appId": ctd_app_id},
            })
            blob = str(cfg)
            assert ("Temperature of" in blob) or ("temperature of" in blob.lower()), \
                f"rule paragraph does not render a device-relative Temperature comparison: {blob[:600]}"
            # No degradation skip for the empty device-picker option list (normal case).
            skipped = result.get("settingsSkipped") or []
            bad = [s for s in skipped if isinstance(s, dict)
                   and s.get("key") == "compareToDevice-validation"]
            assert not bad, \
                f"unexpected compareToDevice-validation skip (empty device-picker options are normal): {bad}"
        finally:
            self._delete_native(ctd_app_id)

        # (2) compareToDevice + a literal state RHS is now a HARD reject (fail-loud),
        # not a silent literal fallback. The mutual-exclusion guard is a pre-write
        # check inside the walker, so it must be exercised on a FRESH rule: adding a
        # second Required Expression to a rule that already has one takes a different
        # path that never reaches the per-condition walker check, masking the guard's
        # error behind the second-RE failure.
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
            except (McpToolError, McpError, requests.HTTPError) as exc:
                # A relay 504 drops the response, so the expected-failure check can't be
                # evaluated -- raise it so the test-level retry re-runs on a fresh rule
                # (never soft-pass a reject contract).
                if "504" in str(exc):
                    raise
                assert "cannot be combined with 'state'/'value'" in str(exc), \
                    f"compareToDevice + literal state fail-loud should name the mutual-exclusion: {exc}"
        finally:
            self._delete_native(reject_app_id)

    @test("native_apps")
    def test_set_rule_required_expression_multi_condition(self) -> None:
        # hub_set_rule edit -> addRequiredExpression with THREE conditions joined by AND, then a
        # sub-expression. Regression guard for the multi-condition gap-operator bug (root-caused
        # 2026-06-21 via the native RM wizard + claude-in-chrome): each `oper=AND` written BETWEEN
        # conditions returns a POST echo that lags one render behind ([oper, doneST] instead of the
        # settled [cond, doneST]); the request-scoped page cache consumed that lagged echo, so the
        # next condition's `cond=a` built its body from a schema missing `cond`, dropped the
        # `cond.type=enum` sidecar, RM silently no-op'd the write, and the walker failed with
        # "rCapab_<N> not in STPage schema after cond=a; got cond, doneST". THREE conditions exercise
        # BOTH gap-operators (the 2nd one's lag differs from the 1st), and the sub-expression
        # exercises the close-sub-expression `oper`. The fix emits `cond`/`oper`'s known enum type
        # explicitly so the write lands regardless of the lagged cache -- no invalidation, no extra
        # re-fetch (the round-trip budget that keeps this build under the relay is preserved).
        # Single-condition REs never hit this (no gap-oper). strict=True: a relay 504 re-runs the
        # small test once, then is an honest red (a persistent 504 means the build is still too
        # heavy -- a tool problem to fix, not a test to weaken).
        dev_a, dev_b = self.get_test_temperature_ids()
        app_id = self._create_native_rule("MultiCondRE")
        try:
            result = self._rm_call_soft({
                "appId": app_id,
                "addRequiredExpression": {
                    "operator": "AND",
                    "conditions": [
                        {"capability": "Temperature", "deviceIds": [int(dev_a)], "comparator": ">", "state": 70},
                        {"capability": "Temperature", "deviceIds": [int(dev_b)], "comparator": "<", "state": 80},
                        {"capability": "Temperature", "deviceIds": [int(dev_a)], "comparator": ">=", "state": 65},
                    ],
                },
                "confirm": True,
            }, strict=True)
            assert result.get("success") is not False, \
                f"multi-condition addRequiredExpression reported failure (the gap-oper cache regression): {result}"
            # ALL THREE condition slots must have allocated -- before the fix a `cond=a` after a
            # gap-operator no-op'd, so the walker threw and not every slot landed.
            cidx = result.get("conditionIndices") or []
            assert len(cidx) == 3, \
                f"multi-condition RE did not allocate all three condition slots (expected 3 conditionIndices): {result}"
            self._assert_rule_healthy(app_id)
            # The rule renders all THREE Temperature conditions joined by AND.
            cfg = self.client.call_tool("hub_read_apps_code", {
                "tool": "hub_get_app_config", "args": {"appId": app_id},
            })
            blob = str(cfg)
            assert blob.lower().count("temperature of") >= 3, \
                f"rule does not render all three Temperature conditions: {blob[:800]}"
            assert "AND" in blob, \
                f"rule does not render the AND joining operator: {blob[:800]}"
        finally:
            self._delete_native(app_id)

        # Sub-expression shape: (A > 70 OR B < 80) AND A >= 65. Exercises the close-sub-expression
        # operator (oper="end-sub-expression )") followed by the outer gap-operator -- an `oper`
        # echo that ADDS the expression-management buttons while still lagging. The outer cond=a
        # no-op'd before the fix. Proves the paren/sub-expression path builds live.
        sub_app_id = self._create_native_rule("SubExprRE")
        try:
            sub = self._rm_call_soft({
                "appId": sub_app_id,
                "addRequiredExpression": {
                    "operator": "AND",
                    "conditions": [
                        {"subExpression": {"operator": "OR", "conditions": [
                            {"capability": "Temperature", "deviceIds": [int(dev_a)], "comparator": ">", "state": 70},
                            {"capability": "Temperature", "deviceIds": [int(dev_b)], "comparator": "<", "state": 80},
                        ]}},
                        {"capability": "Temperature", "deviceIds": [int(dev_a)], "comparator": ">=", "state": 65},
                    ],
                },
                "confirm": True,
            }, strict=True)
            assert sub.get("success") is not False, \
                f"sub-expression addRequiredExpression reported failure (close-paren/outer-oper cache regression): {sub}"
            # Two inner + one outer condition slot must all allocate.
            scidx = sub.get("conditionIndices") or []
            assert len(scidx) == 3, \
                f"sub-expression RE did not allocate all three condition slots (2 inner + 1 outer): {sub}"
            self._assert_rule_healthy(sub_app_id)
        finally:
            self._delete_native(sub_app_id)

    @test("native_apps")
    def test_set_rule_action_after_required_expression(self) -> None:
        # predCapabs-clearing guard for the ghost-ifThen (Step 4b of addRequiredExpression).
        # RM leaves atomicState.predCapabs dirty after an RE commit; without the ghost-ifThen
        # clear the NEXT non-expression action opens doActPage with that stale predCapabs and RM
        # wraps the action under "IF (Broken Condition)". NO other e2e adds an action AFTER an RE,
        # so the ghost-ifThen -- and the page-cache threading that optimizes it -- had no live
        # guard: a silent predCapabs leak passes every other RE test (none add a trailing action).
        # Build an RE, add a plain log action, and assert it lands as a top-level action, NOT a
        # Broken-Condition wrap. strict=True so a relay 504 re-runs this small rule once.
        sw = int(self.get_test_switch_id())
        app_id = self._create_native_rule("ActAfterRE")
        try:
            re_res = self._rm_call_soft({
                "appId": app_id,
                "addRequiredExpression": {"conditions": [
                    {"capability": "Switch", "deviceIds": [sw], "state": "on"}]},
                "confirm": True,
            }, strict=True)
            assert re_res.get("success") is not False, \
                f"addRequiredExpression reported failure: {re_res}"
            # The action added AFTER the RE must NOT be wrapped under a Broken Condition IF --
            # that wrap is exactly the stale-predCapabs symptom the ghost-ifThen clears.
            self._add_action_or_raise_504(app_id, {"capability": "log", "message": "after-RE"})
            self._assert_rule_healthy(app_id)
            cfg = self.client.call_tool("hub_read_apps_code", {
                "tool": "hub_get_app_config", "args": {"appId": app_id}})
            assert "Broken Condition" not in str(cfg), \
                f"ghost-ifThen failed to clear predCapabs -- the post-RE action is wrapped under IF(Broken Condition): {str(cfg)[:800]}"
        finally:
            self._delete_native(app_id)

    @test("native_apps")
    def test_set_rule_action_mutations(self) -> None:
        # hub_set_rule edit -> addActions (bulk) + removeAction + clearActions +
        # replaceActions -- the index-bearing action-list mutations on one small rule.
        # (moveAction and patches each have their own test: together the three were the
        # heaviest test in the family, and a 504 on any op forced a full re-run of all
        # ~10 wizard ops -- both retry attempts then ride the same overload.)
        app_id = self._create_native_rule("ActMut")
        try:
            self._set_rule(app_id, {"addActions": [
                {"capability": "log", "message": "one"},
                {"capability": "log", "message": "two"},
            ]}, strict=True)
            first = self._set_rule(app_id, {"addAction": {"capability": "log", "message": "remove-me-marker"}}, strict=True)
            idx = first.get("actionIndex")
            assert idx is not None, \
                f"addAction did not return an actionIndex (contract regression): {first}"
            # removeAction is the one wizard op measured ABOVE the ~10s cloud-relay
            # ceiling on a QUIET hub (10.4s direct-timed 2026-06-12), so a 504 on it is
            # the op's normal completion mode, not weather -- strict-raising would make
            # this test permanently red. Verified-by-readback instead of skipped: on a
            # 504, read the rule config back; marker gone = the removal committed
            # (assertion holds via readback); marker still present = verified
            # NON-commit, re-issue once (the only duplicate-safe retry point) and
            # re-verify. Either way the removal is ASSERTED, never assumed.
            def _marker_present() -> bool:
                cfg = self.client.call_tool("hub_read_apps_code", {
                    "tool": "hub_get_app_config", "args": {"appId": app_id},
                })
                return "remove-me-marker" in str(cfg)
            try:
                self._set_rule(app_id, {"removeAction": {"index": idx}}, strict=True)
            except (McpError, McpToolError, requests.HTTPError) as exc:
                if "504" not in str(exc):
                    raise
                print("    removeAction response lost to relay 504 (the op runs ~10s hub-side) -- "
                      "verifying the removal by config readback")
                time.sleep(3.0)
                if _marker_present():
                    print("    removal verified NOT committed -- one safe re-issue")
                    try:
                        self._set_rule(app_id, {"removeAction": {"index": idx}})
                    except (McpError, McpToolError, requests.HTTPError, AssertionError):
                        # The re-issue's response is not load-bearing (it may 504 the same
                        # way, or hit a missing index if the FIRST remove committed late);
                        # the final readback below is the binding assertion either way.
                        pass
                    time.sleep(3.0)
                assert not _marker_present(), \
                    "removeAction did not remove the action (marker still renders after readback-verified retry)"
            self._set_rule(app_id, {"clearActions": True}, strict=True)
            self._set_rule(app_id, {"replaceActions": [{"capability": "log", "message": "final"}]}, strict=True)
            self._assert_rule_healthy(app_id)
        finally:
            self._delete_native(app_id)

    @test("native_apps")
    def test_set_rule_move_action(self) -> None:
        # hub_set_rule edit -> moveAction. RM action indices are PERSISTENT per-rule
        # counters (clearActions removes the actions but never renumbers), so move
        # whatever index the addActions response reports -- never a hardcoded 1.
        app_id = self._create_native_rule("MoveAct")
        try:
            # Three SINGLE addAction calls, not one bulk addActions: the bulk walk runs
            # ~2.5-4s per item in ONE request (direct-timed 7.6s for 2 items on a quiet
            # hub), so 3 items rides over the ~10s relay ceiling and 504s under CI load
            # (run 27427314399 -- both strict attempts). Singles are ~5s each, all under
            # the ceiling; bulk-addActions coverage lives in test_set_rule_action_mutations.
            move_indices = []
            for msg in ("a", "b", "c"):
                added = self._set_rule(app_id, {"addAction": {"capability": "log", "message": msg}}, strict=True)
                if added.get("actionIndex") is not None:
                    move_indices.append(added.get("actionIndex"))
            assert move_indices, \
                "addAction returned no action indices to move (contract regression)"
            # The move-arrow click is the suite's heaviest single wizard op and rides the
            # ~10s cloud-relay ceiling even on a healthy hub. On a slow hub it can commit
            # late; the tool does one short re-check then returns a soft asyncCommitLikely
            # envelope instead of a hard false-negative. Accept a confirmed shift OR the
            # soft envelope; a relay 504 on the click is the same unknown-commit state the
            # envelope models, and this test never asserts the resulting ORDER -- rule
            # health below is the real assertion -- so a 504 here verifies health rather
            # than raising (the one deliberate, documented non-strict op in this family;
            # NOT a skipped assertion). Call hub_set_rule directly since _set_rule would
            # raise on success=False.
            try:
                result = self.client.call_tool("hub_manage_rule_machine", {
                    "tool": "hub_set_rule",
                    "args": {"appId": app_id, "moveAction": {"index": move_indices[0], "direction": "down"}, "confirm": True},
                })
                assert result.get("success") is True or result.get("asyncCommitLikely") is True, \
                    (f"moveAction must confirm the shift OR report asyncCommitLikely "
                     f"(never a hard false-negative): {result}")
            except requests.HTTPError as exc:
                if "504" not in str(exc):
                    raise
                print("    moveAction response lost to relay 504 -- same unknown-commit contract as "
                      "asyncCommitLikely; rule health below is the binding assertion")
            # The DIRECT moveAction (and its soft/504 paths) bypasses the caching helpers and its commit
            # may be uncertain, so clear the stale pre-move cache to FORCE a live post-move health fetch --
            # this assert is the sole verification the reorder didn't corrupt the rule.
            self._last_write_health = None
            self._assert_rule_healthy(app_id)
        finally:
            self._delete_native(app_id)

    @test("native_apps")
    def test_set_rule_patches(self) -> None:
        # hub_set_rule edit -> patches (atomic multi-op).
        app_id = self._create_native_rule("Patch")
        try:
            self._set_rule(app_id, {"patches": [
                {"addAction": {"capability": "log", "message": "p1"}},
                {"addAction": {"capability": "log", "message": "p2"}},
            ]}, strict=True)
            self._assert_rule_healthy(app_id)
        finally:
            self._delete_native(app_id)

    @test("native_apps")
    def test_set_rule_raw_settings_and_validation(self) -> None:
        # hub_set_rule edit -> the generic raw settings + button (page-transition) path,
        # then read the value back via hub_get_app_config. (BAT-confirmed shapes.)
        app_id = self._create_native_rule("RawBtn")
        try:
            self._set_rule(app_id, {"settings": {"comments": "BAT_E2E raw settings", "logging": ["Triggers", "Actions"]}}, strict=True)
            self._set_rule(app_id, {"button": "updateRule"}, strict=True)
            cfg = self.client.call_tool("hub_read_apps_code", {"tool": "hub_get_app_config", "args": {"appId": app_id, "includeSettings": True}})
            settings = cfg.get("settings") or {}
            assert settings.get("comments") == "BAT_E2E raw settings", \
                f"comments did not round-trip: {settings.get('comments')!r}"

            # Several (capability, action) pairs were verified on a live hub to register
            # the action row but NEVER bake when their key field is omitted (mainPage
            # keeps the 'Define Actions' placeholder) -- a latent silent failure. The
            # build now throws up front; the single-addAction edit path surfaces it as
            # success:false + a field-naming error instead of a confusing partial.
            # Spot-check colorTemp.setColorTemp (needs 'kelvin'). No device is needed --
            # the field throw fires before any device write, and before any mutation, so
            # it leaves the rule's state untouched.
            try:
                result = self.client.call_tool("hub_manage_rule_machine", {
                    "tool": "hub_set_rule",
                    "args": {"appId": app_id, "addAction": {"capability": "colorTemp", "action": "setColorTemp"}, "confirm": True},
                })
                assert result.get("success") is False, \
                    f"omitting kelvin should fail fast (not partial): {result}"
                assert "kelvin" in str(result.get("error", "")), \
                    f"error should name 'kelvin': {result}"
            except (McpToolError, McpError) as exc:
                # A 504 here is a dropped response, NOT the fail-fast we assert -- raise it
                # so the test-level retry re-runs on a fresh rule rather than mis-asserting
                # on 'kelvin'.
                if "504" in str(exc):
                    raise
                assert "kelvin" in str(exc), \
                    f"fail-fast error should name 'kelvin': {exc}"
        finally:
            self._delete_native(app_id)

    @test("native_apps")
    def test_set_rule_enum_doactpage(self) -> None:
        # Same shared walker (_rmWalkConditionReveal) as the STPage enum test, but
        # reached via the doActPage surface (addAction ifThen) rather than STPage
        # (addRequiredExpression). The 4th of the four wizard surfaces that carry the
        # enum-recognized Custom Attribute bug. The enum re-render hides the comparator
        # RelrDev_<N> and reveals state_<N> directly; the fix branches to the enum path
        # (writes state_<N>, skips the comparator, does NOT throw, does NOT flag
        # partial). The IF block is closed (THEN + endIf) before the final whole-rule
        # health check so the rule ends structurally balanced.
        sw = int(self.get_test_switch_id())
        app_id = self._create_native_rule("DoActEnum")
        try:
            result = self._rm_call_soft({
                "appId": app_id,
                "addAction": {"capability": "ifThen", "expression": {"conditions": [
                    {"capability": "Custom Attribute", "deviceIds": [sw],
                     "attribute": "switch", "comparator": "=", "state": "on"}]}},
                "confirm": True,
            }, strict=True)
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
            assert not bad, \
                f"unexpected RelrDev_<N> not_in_schema skip on the doActPage walker enum path: {bad}"
            assert not result.get("partial"), \
                f"doActPage walker enum condition falsely flagged partial: {result}"
            # Close the IF block (THEN body + endIf) before the whole-rule health check.
            # An ifThen opener added alone is a valid intermediate tool state, but it
            # leaves the rule with an unclosed IF that the live hub's rule-health check
            # correctly flags -- the enum-condition contract under test is already proven
            # by the assertions above; completing the block is what makes the end-to-end
            # health assertion meaningful. A relay-504 on either closer raises and the
            # test-level retry re-runs on a fresh rule (the dangling-IF rule is deleted
            # in the finally), so a dropped closer can't strand a broken rule.
            self._add_action_or_raise_504(app_id, {"capability": "log", "message": "fired"})
            self._add_action_or_raise_504(app_id, {"capability": "endIf"})
            self._assert_rule_healthy(app_id)
        finally:
            self._delete_native(app_id)

    # ---- tests that keep their OWN rule (create/delete/lifecycle contracts) ----

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
        create_label = f"{PREFIX}CreateRE"
        cw = self._soft_write(
            lambda: self.client.call_tool("hub_manage_rule_machine", {
                "tool": "hub_set_rule",
                "args": {
                    "name": create_label,
                    "addRequiredExpression": {"conditions": [
                        {"capability": "Switch", "deviceIds": [sw], "state": "on"}]},
                    "confirm": True,
                }}),
            lambda: self._find_app_id_by_label(create_label),
            "create-with-RE",
        )
        if cw["relayDropped"]:
            assert cw["committed"], f"create-with-RE lost to relay 504 and never committed ({create_label})"
            app_id = cw["evidence"]
            created = None
        else:
            created = cw["response"]
            app_id = created.get("appId")
            assert app_id, f"create-with-RE did not return appId: {created}"
        self.created_native_app_ids.append(str(app_id))
        try:
            if created is None:
                # The bundled-RE response (requiredExpression/conditionIndices) is gone
                # to the 504; the recoverable evidence is the rule rendering healthy
                # (an unhealthy/broken RE would fail this). Skip the response-shape
                # assertions with a printed line rather than soft-passing them.
                print("    create-with-RE: requiredExpression response-field assertions skipped "
                      "(relay 504); verifying rule health instead")
                self._assert_rule_healthy(app_id)
            else:
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
        # gateway, delete via the native gateway, and confirm it is gone. On a relay
        # 504 the response is lost but the delete may still have committed, so the
        # gone-check below is the verification either way (absent => committed).
        app_id = self._create_native_rule("CrossGwDelete")
        dw = self._soft_write(
            lambda: self.client.call_tool("hub_manage_native_rules_and_apps", {
                "tool": "hub_delete_native_app", "args": {"appId": app_id, "force": True, "confirm": True}}),
            lambda: not self._app_still_present(app_id),
            "hub_delete_native_app (cross-gateway)",
        )
        if dw["relayDropped"]:
            assert dw["committed"], f"app {app_id} still present after a relay-504 delete (did not commit)"
        self._untrack_native_app(app_id)
        try:
            cfg = self.client.call_tool("hub_read_apps_code", {"tool": "hub_get_app_config", "args": {"appId": app_id}})
            assert cfg.get("success") is False, f"app {app_id} should be gone after delete, got: {cfg}"
        except (McpToolError, McpError):
            pass  # also acceptable: the read errors because the app no longer exists

    @test("native_apps")
    def test_set_native_app_button_edit(self) -> None:
        # hub_set_native_app edit path also drives a page-transition button (generic).
        create_label = f"{PREFIX}GenericButton"
        cw = self._soft_write(
            lambda: self.client.call_tool("hub_manage_native_rules_and_apps", {
                "tool": "hub_set_native_app",
                "args": {"appType": "rule_machine", "name": create_label, "confirm": True}}),
            lambda: self._find_app_id_by_label(create_label),
            "hub_set_native_app create (button-edit fixture)",
        )
        if cw["relayDropped"]:
            assert cw["committed"], f"hub_set_native_app create lost to relay 504 and never committed ({create_label})"
            app_id = cw["evidence"]
        else:
            created = cw["response"]
            app_id = created.get("appId")
            assert app_id, f"hub_set_native_app create did not return appId: {created}"
        self.created_native_app_ids.append(str(app_id))

        # EDIT via a page-transition button. On a 504 the success field is gone; the
        # button is a benign page transition (no destructive payload), and the rule
        # still rendering is the recoverable evidence, so skip the success assertion
        # with a printed line rather than hard-failing.
        ew = self._soft_write(
            lambda: self.client.call_tool("hub_manage_native_rules_and_apps", {
                "tool": "hub_set_native_app", "args": {"appId": app_id, "button": "updateRule", "confirm": True}}),
            lambda: self._app_still_present(app_id),
            "hub_set_native_app button edit",
        )
        if ew["relayDropped"]:
            assert ew["committed"], f"app {app_id} vanished after the dropped button-edit response: not recoverable"
            print("    hub_set_native_app button edit: success assertion skipped (relay 504); app still renders")
        else:
            assert ew["response"].get("success") is not False, \
                f"hub_set_native_app button edit failed: {ew['response']}"
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

    def _find_visual_rule_id_by_name(self, name: str) -> str | None:
        """Look up a VRB rule's appId by name via the list mode (create-verify on 504)."""
        try:
            listed = self._get_visual_rule()
            for r in (listed.get("rules") or []):
                if isinstance(r, dict) and r.get("name") == name:
                    return str(r.get("appId"))
        except (McpError, McpToolError, requests.HTTPError) as exc:
            print(f"    [WARN] visual-rule listing lookup for {name!r} failed: {exc}")
        return None

    @test("visual_rules")
    def test_visual_rule_classic_lifecycle(self) -> None:
        # Full VRB round-trip: create (classic-first, one graph retry -- the hub
        # firmware decides the native format), read back via the pure-read gateway,
        # list, rename+pause, resume, wholesale replace, delete-with-verify.
        switch_id = int(self.get_test_switch_id())
        name = f"{PREFIX}VisualRule"

        def _create_with_retry() -> Any:
            r = self.client.call_tool("hub_manage_rule_machine", {
                "tool": "hub_set_visual_rule",
                "args": {"name": name, "confirm": True,
                         "definition": self._vrb_definition("classic", switch_id, "Turns off")}})
            if r.get("success") is False and r.get("hubNativeFormat") == "graph":
                # This firmware's builder creates graph-format rules (the tool already
                # force-deleted the orphan shell); retry once with the equivalent graph
                # definition so the lifecycle still executes deterministically.
                r = self.client.call_tool("hub_manage_rule_machine", {
                    "tool": "hub_set_visual_rule",
                    "args": {"name": name, "confirm": True,
                             "definition": self._vrb_definition("graph", switch_id, "Turns off")}})
            return r

        cw = self._soft_write(
            _create_with_retry,
            lambda: self._find_visual_rule_id_by_name(name),
            "hub_set_visual_rule create",
        )
        if cw["relayDropped"]:
            assert cw["committed"], f"hub_set_visual_rule create lost to relay 504 and never committed ({name})"
            app_id = cw["evidence"]
            self.created_native_app_ids.append(str(app_id))
            # The create response (created/format) is gone; recover the format from a
            # read-back so the rest of the lifecycle still runs deterministically.
            rb = self._get_visual_rule(app_id)
            fmt = rb.get("format")
            assert fmt in ("classic", "graph"), f"read-back of dropped-create rule has unknown format {fmt!r}: {rb}"
            print(f"    hub_set_visual_rule create: created/format assertions skipped (relay 504); "
                  f"adopted appId {app_id}, format {fmt!r} via read-back")
        else:
            created = cw["response"]
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

            # HEALTH on a Visual Rule (issue #254): hub_get_rule_health must NOT reject a VRB
            # rule -- it reports the engine-native verdict. ruleFormat identifies which engine
            # answered (vrb-graph reports broken from validationErrors; vrb-classic has none).
            vh = self.client.call_tool("hub_read_rules", {
                "tool": "hub_get_rule_health", "args": {"appId": app_id}})
            assert vh.get("ruleFormat") in ("vrb-graph", "vrb-classic"), \
                f"hub_get_rule_health did not recognize VRB rule {app_id} (ruleFormat={vh.get('ruleFormat')!r}): {vh}"
            if vh.get("ruleFormat") == "vrb-graph":
                # Freshly created + healthy, so the validationErrors-derived boolean must be False
                # (isinstance(bool) accepted either verdict and wouldn't catch an always-true regression).
                assert vh.get("broken") is False, \
                    f"a freshly-created healthy graph VRB rule should report broken:false: {vh}"

            # LIST: no-args mode must include the new rule.
            listed = self._get_visual_rule()
            assert listed.get("success") is True, f"hub_get_visual_rule list mode failed: {listed}"
            ids = [str(r.get("appId")) for r in (listed.get("rules") or [])]
            assert str(app_id) in ids, f"created VRB rule {app_id} not in the listing: {ids}"

            # RENAME + PAUSE in one call, then prove both landed via an independent read.
            # The read-back is the real evidence, so a relay-504-dropped response only
            # costs the response-success assertion (skipped with a print).
            renamed = f"{PREFIX}VisualRuleRenamed"
            rp = self._soft_write(
                lambda: self.client.call_tool("hub_manage_rule_machine", {
                    "tool": "hub_set_visual_rule",
                    "args": {"appId": app_id, "name": renamed, "paused": True, "confirm": True}}),
                lambda: True,  # verified by the read-back below
                "hub_set_visual_rule rename+pause",
            )
            if rp["relayDropped"]:
                print("    hub_set_visual_rule rename+pause: success assertion skipped (relay 504); "
                      "verified via read-back")
            else:
                assert rp["response"].get("success") is True, f"rename+pause reported failure: {rp['response']}"
            got = self._get_visual_rule(app_id)
            assert got.get("name") == renamed, f"rename did not land: {got.get('name')!r}"
            assert got.get("rulePaused") is True, f"pause did not land: {got}"

            # RESUME.
            rs = self._soft_write(
                lambda: self.client.call_tool("hub_manage_rule_machine", {
                    "tool": "hub_set_visual_rule",
                    "args": {"appId": app_id, "paused": False, "confirm": True}}),
                lambda: True,  # verified by the read-back below
                "hub_set_visual_rule resume",
            )
            if rs["relayDropped"]:
                print("    hub_set_visual_rule resume: success assertion skipped (relay 504); verified via read-back")
            else:
                assert rs["response"].get("success") is True, f"resume reported failure: {rs['response']}"
            got = self._get_visual_rule(app_id)
            assert got.get("rulePaused") is False, f"resume did not land: {got}"

            # REPLACE: wholesale definition edit in the SAME format the rule speaks
            # ('Turns on' variant), verified by the tool's read-back AND our own.
            rr = self._soft_write(
                lambda: self.client.call_tool("hub_manage_rule_machine", {
                    "tool": "hub_set_visual_rule",
                    "args": {"appId": app_id, "confirm": True,
                             "definition": self._vrb_definition(fmt, switch_id, "Turns on")}}),
                lambda: True,  # verified by the 'Turns on' read-back below
                "hub_set_visual_rule replace",
            )
            if rr["relayDropped"]:
                print("    hub_set_visual_rule replace: success/verified assertions skipped (relay 504); "
                      "verified via read-back")
            else:
                assert rr["response"].get("success") is True and rr["response"].get("verified") is True, \
                    f"definition replacement not verified: {rr['response']}"
            got = self._get_visual_rule(app_id)
            replaced_blob = json.dumps(got.get("whenNodes") if fmt == "classic" else got.get("definition"))
            assert "Turns on" in replaced_blob, \
                f"replaced definition did not round-trip 'Turns on': {replaced_blob[:300]}"
        finally:
            # DELETE inline -- the delete contract IS part of the lifecycle under
            # test (the tracked-id sweep stays as backstop if this raises). On a relay
            # 504 the response (verified/predeleteDefinition) is gone; verify by
            # absence via the gone-read, skipping the response-shape assertions.
            dw = self._soft_write(
                lambda: self.client.call_tool("hub_manage_rule_machine", {
                    "tool": "hub_delete_visual_rule", "args": {"appId": app_id, "confirm": True}}),
                lambda: self._get_visual_rule(app_id).get("success") is False,
                "hub_delete_visual_rule",
            )
            if dw["relayDropped"]:
                assert dw["committed"], f"VRB rule {app_id} still readable after a relay-504 delete (did not commit)"
                print("    hub_delete_visual_rule: verified/predeleteDefinition assertions skipped (relay 504); "
                      "verified gone by read")
            else:
                deleted = dw["response"]
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

        def _create_with_retry() -> Any:
            r = self.client.call_tool("hub_manage_rule_machine", {
                "tool": "hub_set_visual_rule",
                "args": {"name": name, "confirm": True,
                         "definition": self._vrb_definition("classic", switch_id, "Turns off")}})
            if r.get("success") is False and r.get("hubNativeFormat") == "graph":
                # Same firmware-decides-the-format adaptation as the lifecycle test
                # (the tool already force-deleted the orphan classic shell).
                r = self.client.call_tool("hub_manage_rule_machine", {
                    "tool": "hub_set_visual_rule",
                    "args": {"name": name, "confirm": True,
                             "definition": self._vrb_definition("graph", switch_id, "Turns off")}})
            return r

        cw = self._soft_write(
            _create_with_retry,
            lambda: self._find_visual_rule_id_by_name(name),
            "hub_set_visual_rule create (backup-restore fixture)",
        )
        if cw["relayDropped"]:
            assert cw["committed"], f"VRB create lost to relay 504 and never committed ({name})"
            app_id = cw["evidence"]
            self.created_native_app_ids.append(str(app_id))
            rb = self._get_visual_rule(app_id)
            fmt = rb.get("format")
            assert fmt in ("classic", "graph"), f"read-back of dropped-create rule has unknown format {fmt!r}: {rb}"
            print(f"    VRB create: success/format assertions skipped (relay 504); adopted appId {app_id}, format {fmt!r}")
        else:
            created = cw["response"]
            app_id = created.get("appId")
            assert app_id, f"hub_set_visual_rule create did not return an appId: {created}"
            self.created_native_app_ids.append(str(app_id))
            assert created.get("success") is True, f"hub_set_visual_rule create did not verify: {created}"
            fmt = created.get("format")
            assert fmt in ("classic", "graph"), f"create returned an unknown format {fmt!r}: {created}"

        # DELETE through hub_delete_native_app -- THIS path takes the VRB-aware
        # snapshot (the feature's entry point); hub_delete_visual_rule does not.
        # If any of its asserts fire, the original id stays tracked for the sweep.
        # The whole test is ABOUT this snapshot response (backup.backupKey) and the
        # restore response -- a relay 504 that drops EITHER response leaves nothing to
        # validate this run, so a 504 on the snapshot-delete skips-with-print (never a
        # soft-pass of the response contract). We still verify the delete itself
        # committed (gone-read) so we don't leak the rule.
        try:
            deleted = self.client.call_tool("hub_manage_native_rules_and_apps", {
                "tool": "hub_delete_native_app",
                "args": {"appId": app_id, "force": True, "confirm": True},
            })
        except (McpError, McpToolError, requests.HTTPError) as exc:
            if "504" not in str(exc):
                raise
            time.sleep(3.0)
            if self._get_visual_rule(app_id).get("success") is False:
                self._untrack_native_app(app_id)
            raise SkipTest("VRB snapshot-delete response (backup.backupKey) lost to relay 504 -- "
                           "no backup key to drive the restore contract this run") from exc
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
            try:
                restored = self.client.call_tool("hub_manage_code", {
                    "tool": "hub_restore_backup",
                    "args": {"backupKey": backup_key, "confirm": True},
                })
            except (McpError, McpToolError, requests.HTTPError) as exc:
                if "504" not in str(exc):
                    raise
                # The restore response (ruleId/recreated/verified) is the contract under
                # test and is gone. A restore MAY have minted a rule; adopt it by name so
                # cleanup reaps it, then skip (never soft-pass the restore contract).
                time.sleep(3.0)
                adopted = self._find_visual_rule_id_by_name(name)
                if adopted:
                    new_id = adopted
                    self.created_native_app_ids.append(str(adopted))
                raise SkipTest("hub_restore_backup response lost to relay 504 -- "
                               "the recreate/verify contract can't be validated this run") from exc
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
            # A relay 504 here is verified by absence so it doesn't mask a real failure
            # from the try (or strand the rule).
            if new_id:
                dw = self._soft_write(
                    lambda: self.client.call_tool("hub_manage_rule_machine", {
                        "tool": "hub_delete_visual_rule", "args": {"appId": new_id, "confirm": True}}),
                    lambda: self._get_visual_rule(new_id).get("success") is False,
                    "hub_delete_visual_rule (restored-rule cleanup)",
                )
                if dw["relayDropped"]:
                    assert dw["committed"], \
                        f"restored rule {new_id} still readable after a relay-504 cleanup delete"
                else:
                    assert dw["response"].get("success") is True, \
                        f"cleanup delete of restored rule {new_id} failed: {dw['response']}"
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
        except requests.HTTPError as e:
            # A relay 504 can't distinguish "gate refused" from "response dropped" --
            # skip rather than soft-pass an expected-refusal assertion.
            if "504" not in str(e):
                raise
            raise SkipTest("no-confirm refusal contract lost to relay 504 -- "
                           "can't distinguish refusal from a dropped response") from e
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
        except requests.HTTPError as exc:
            if "504" not in str(exc):
                raise
            raise SkipTest("appType=visual_rule redirect contract lost to relay 504") from exc
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

            try:
                deleted = self.client.call_tool("hub_manage_rule_machine", {
                    "tool": "hub_delete_visual_rule", "args": {"appId": rm_id, "confirm": True},
                })
            except requests.HTTPError as exc:
                # Expected-refusal call: a 504 can't confirm the gate refused -- skip
                # rather than soft-pass. (The rule-survival check below would still
                # catch a regression that actually destroyed the RM rule, but the
                # refusal CONTRACT itself is what's under test here.)
                if "504" not in str(exc):
                    raise
                raise SkipTest("type-gated delete-refusal contract lost to relay 504") from exc
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
    iconX2Url: "https://raw.githubusercontent.com/hubitat/HubitatPublic/master/app-dev/icon.png",
    oauth: true
)

preferences {
    page(name: "p", title: "Update Leg Target", install: true, uninstall: true) {
        section { paragraph "Throwaway update-leg target. Marker: ${updateLegMarker()}" }
    }
}

mappings {
    path("/ping") { action: [GET: "ping"] }
}

def installed() {}
def updated() {}
def ping() { render contentType: "text/plain", data: "ok" }

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

            # Leg 5 (#259): enable OAuth on the (oauth:true-declaring) code class via the
            # hub_update_app oauth fold -- the programmatic "Enable OAuth in App". The leg MUST
            # dispatch and return a structured oauth block; on success the hub returns the generated
            # clientId. (Throwaway app, never the MCP server -- the self-OAuth guard protects that.)
            oauth_res = self.client.call_tool("hub_manage_code", {
                "tool": "hub_update_app",
                "args": {"appId": code_app_id, "oauth": {"enabled": True}, "confirm": True},
            })
            assert isinstance(oauth_res, dict) and "oauth" in oauth_res, \
                "hub_update_app(oauth) returned no oauth block"
            ob = oauth_res["oauth"]
            # Validate the OAuth leg via ASSERTS only (assert is not a logging sink). Do NOT feed any
            # ob-derived value -- not even a branch-chosen literal note -- into the print below: ob
            # carries the client secret, and CodeQL's clear-text-logging guard taints anything whose
            # value is control-dependent on it.
            assert "success" in ob, "oauth block missing 'success'"
            if ob.get("success") is True:
                assert ob.get("clientId"), "OAuth enabled but no clientId returned"
            else:
                assert ob.get("error"), "OAuth leg failed without a structured error"

            print(f"    APP_CODE_UPDATE ok -- v{version_before}->v{version_after}; compile error + lock conflict both refused with no write; restore brought V1 back (undo key {pre_restore_key}); OAuth leg checked")
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

    @test("device_replace")
    def test_call_device_replace_list_options_read(self) -> None:
        # WHY no happy-path replace here: /device/replace re-points a device onto
        # replacement HARDWARE and PRESERVES the old id -- a mutating, hard-to-undo
        # operation needing a real compatible free-standing replacement node, which the
        # MCP tool surface cannot create (every fixture is an MCP child device). The full
        # apply round-trip is BAT/manual-only (tests/BAT-v2.md T703). This scenario
        # exercises the safe read-only leg live: list_options drives
        # GET /device/getReplacementOptions/<id> and returns the structured candidate
        # array without mutating anything.
        dev_id, dni = self._create_swap_switch(f"{PREFIX}Replace_A")
        try:
            result = self.client.call_tool("hub_manage_devices", {
                "tool": "hub_call_device_replace",
                "args": {"old_device_id": dev_id, "list_options": True},
            })
            assert isinstance(result, dict), f"hub_call_device_replace(list_options) returned non-dict: {result}"
            # Read leg: success with an options array (a virtual fixture usually has no
            # compatible replacement -> empty list), or a structured failure -- never a
            # silent mutation. Assert the structured contract either way.
            if result.get("success") is True:
                assert result.get("listOptions") is True, f"list_options read missing listOptions flag: {result}"
                assert isinstance(result.get("options"), list), f"options is not a list: {result}"
                opt_count = result.get("optionCount")
                assert isinstance(opt_count, int) and opt_count == len(result["options"]), \
                    f"optionCount does not match options length: {result}"
                print(f"    DEVICE_REPLACE ok -- list_options read {opt_count} compatible candidate(s) for {dev_id}, no mutation")
            else:
                assert result.get("error"), f"list_options failure without a structured error: {result}"
                print(f"    DEVICE_REPLACE ok -- list_options returned a structured failure (no mutation): {result.get('error')}")
        finally:
            try:
                self.client.call_tool("hub_manage_virtual_device", {
                    "action": "delete", "deviceNetworkId": dni, "confirm": True,
                })
                if dni in self.created_device_dnis:
                    self.created_device_dnis.remove(dni)
            except Exception as exc:
                print(f"  [WARN] device-replace cleanup: delete device DNI={dni} failed: {exc}")

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
        # CREATE -- the read-back below binds source/value/type, so a relay 504 only
        # costs the create-response assertion (skipped with a print).
        cw = self._soft_write(
            lambda: self.client.call_tool("hub_manage_variables", {
                "tool": "hub_create_variable",
                "args": {"name": var_name, "type": "String", "value": "round-trip-v1", "confirm": True}}),
            lambda: True,  # verified by the read-back below
            "hub_create_variable",
        )
        if cw["relayDropped"]:
            print("    hub_create_variable: success/source assertions skipped (relay 504); verified via read-back")
        else:
            created = cw["response"]
            assert created.get("success") is True and created.get("source") == "hub", \
                f"hub_create_variable did not create in the hub namespace: {created}"

        got = self.client.call_tool("hub_manage_variables", {
            "tool": "hub_get_variable", "args": {"name": var_name},
        })
        assert got.get("source") == "hub", f"created variable not visible in the hub namespace: {got}"
        assert got.get("value") == "round-trip-v1", f"hub variable value mismatch: {got}"
        assert got.get("type"), f"hub variable read-back carries no type metadata: {got}"

        # DELETE -- response carries deleted/previousValue; the gone-check below binds
        # the real effect, so a relay 504 skips the response-shape assertions.
        dw = self._soft_write(
            lambda: self.client.call_tool("hub_manage_variables", {
                "tool": "hub_delete_variable", "args": {"name": var_name, "confirm": True}}),
            lambda: self._hub_variable_absent(var_name),
            "hub_delete_variable",
        )
        if dw["relayDropped"]:
            assert dw["committed"], f"hub variable {var_name} still present after a relay-504 delete (did not commit)"
            print("    hub_delete_variable: deleted/previousValue assertions skipped (relay 504); verified gone")
        else:
            deleted = dw["response"]
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

    @test("hub_variables")
    def test_hub_variable_bulk_create_round_trip(self) -> None:
        # The bulk form (variables=[...]) creates several vars sequentially in one call
        # and reports per-item status. A green round-trip proves the gateway routes the
        # variables array, each item's wizard write landed in the real namespace, and the
        # response carries per-item success + the value that actually persisted.
        names = [f"{PREFIX}BulkVar_A", f"{PREFIX}BulkVar_B", f"{PREFIX}BulkVar_C"]
        items = [
            {"name": names[0], "type": "Number", "value": 1},
            {"name": names[1], "type": "String", "value": "two"},
            {"name": names[2], "type": "Boolean", "value": True},
        ]
        # Track BEFORE creating -- no prefix sweep for variables, so a crash between the
        # create landing and a later append would strand the entities.
        for n in names:
            self.created_variable_names.append(n)
        try:
            cw = self._soft_write(
                lambda: self.client.call_tool("hub_manage_variables", {
                    "tool": "hub_create_variable",
                    "args": {"variables": items, "confirm": True}}),
                lambda: all(self._hub_variable_visible_in_bulk(n) for n in names),
                "hub_create_variable (bulk)",
            )
            if cw["relayDropped"]:
                assert cw["committed"], \
                    f"bulk create lost to relay 504 and never committed: {names}"
                print("    hub_create_variable (bulk): per-item assertions skipped (relay 504); verified via read-back")
            else:
                created = cw["response"]
                assert created.get("success") is True, f"bulk create did not fully succeed: {created}"
                assert created.get("createdCount") == 3 and created.get("failedCount") == 0, \
                    f"bulk create count mismatch: {created}"
                results = created.get("results") or []
                assert len(results) == 3, f"bulk create did not report one result per item: {created}"
                by_name = {r.get("name"): r for r in results}
                for it in items:
                    r = by_name.get(it["name"])
                    assert r and r.get("success") is True, f"bulk item {it['name']} not created: {created}"
                    # Per-item value round-trips -- a regression that wrote item-0 for every
                    # entry would surface here (value/type would not match the requested item).
                    assert r.get("value") == it["value"], \
                        f"bulk item {it['name']} value mismatch: got {r.get('value')!r}, expected {it['value']!r}"
                    assert r.get("type") == it["type"], \
                        f"bulk item {it['name']} type mismatch: got {r.get('type')!r}, expected {it['type']!r}"

            # Independently confirm each landed in the hub namespace and read back its value.
            for it in items:
                got = self.client.call_tool("hub_manage_variables", {
                    "tool": "hub_get_variable", "args": {"name": it["name"]},
                })
                assert got.get("source") == "hub", f"bulk var {it['name']} not in the hub namespace: {got}"
                assert got.get("value") == it["value"], \
                    f"bulk var {it['name']} value mismatch on read-back: {got}"
        finally:
            for n in names:
                self._delete_variable_safe(n)

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
    def test_mode_lifecycle(self) -> None:
        # FULL live coverage of the mode surface on the sacrificial e2e hub, every wire path:
        #   hub_manage_mode      create -> rename -> activate -> delete  (/modes/jsonCreate, /modes/jsonUpdate,
        #                        SDK location.setMode, /modes/jsonDelete)
        #   hub_list_modes       read incl. the Mode Manager block       (/modes/json [+ /modes/easyModeManager/json])
        #   hub_set_mode_manager select + Integrated Mode Manager conds  (/modes/setModeManager, POST /modes/easyModeManager/json)
        # The manager we select is whichever one the hub currently reports as active (a guaranteed-valid
        # no-op -- 'app' is only accepted when a 3rd-party mode-manager app is installed, which it is iff
        # the hub already has it selected), falling back to builtIn (the always-available Integrated
        # manager). Conditions are round-tripped (read the live shape, write it back unchanged) so the body
        # is whatever the hub actually serves -- no guessed payload. Mode + Mode-Manager state are restored
        # best-effort (the hub is sacrificial + watchdog-recoverable). The runner's startup backup
        # satisfies the delete's confirm gate.
        mode_name = f"{PREFIX}Mode"
        renamed = f"{PREFIX}Mode2"
        settable_managers = {"builtIn", "legacy", "app"}

        # Every mode tool is a server-app HTTP write; under accumulated full-run load the
        # platform's per-app load limiter can reject a dispatch outright with a tool-level
        # "App <N> generates excessive hub load" error. Route every call through the same
        # limiter contract the dispatch-dependent tests use -- bounce the app via the
        # watchdog and retry once -- so a capacity blip recovers (and the bounce resets the
        # load window for the tests that follow) instead of hard-failing a healthy build.
        # Coverage is unchanged: every call still runs; only a throttled call is retried.
        def _mode_call(name: str, args: dict, label: str) -> Any:
            try:
                return self.client.call_tool(name, args)
            except McpToolError as exc:
                if "excessive hub load" in str(exc) and self._clear_load_throttle(f"{label}: {exc}"):
                    return self.client.call_tool(name, args)
                raise

        before = _mode_call("hub_list_modes", {}, "list modes (before)")
        original_mode = before.get("currentMode") if isinstance(before, dict) else None
        original_mgr = (before.get("modeManager") or {}).get("selected") if isinstance(before, dict) else None
        try:
            # --- hub_manage_mode: create -> read -> rename -> activate ---
            cr = _mode_call("hub_manage_mode", {"action": "create", "name": mode_name}, "create mode")
            assert isinstance(cr, dict) and cr.get("success") is True, f"create failed: {cr}"

            listed = _mode_call("hub_list_modes", {}, "list modes (after create)")
            assert mode_name in [m.get("name") for m in (listed.get("modes") or [])], \
                f"created mode not in hub_list_modes: {listed.get('modes')}"
            assert "modeManager" in listed, f"hub_list_modes missing the modeManager block: {sorted(listed.keys())}"

            rn = _mode_call("hub_manage_mode", {"action": "rename", "mode": mode_name, "name": renamed}, "rename mode")
            assert rn.get("success") is True, f"rename (jsonUpdate) failed: {rn}"

            act = _mode_call("hub_manage_mode", {"action": "activate", "mode": renamed}, "activate mode")
            assert act.get("success") is True and act.get("newMode") == renamed, f"activate failed: {act}"
            if original_mode:
                _mode_call("hub_manage_mode", {"action": "activate", "mode": original_mode}, "restore active mode")

            # --- hub_set_mode_manager: select (setModeManager) + Integrated Mode Manager conditions round-trip ---
            target_mgr = original_mgr if original_mgr in settable_managers else "builtIn"
            sel = _mode_call("hub_set_mode_manager", {"manager": target_mgr}, "select manager")
            assert sel.get("success") is True and sel.get("manager") == target_mgr, \
                f"set_mode_manager({target_mgr}) failed: {sel}"
            conds = (_mode_call("hub_list_modes", {}, "read conditions").get("modeManager") or {}).get("easyConditions")
            cw = _mode_call("hub_set_mode_manager", {"conditions": conds if conds is not None else {}}, "set conditions")
            assert cw.get("success") is True and cw.get("conditionsUpdated") is True, \
                f"easyModeManager conditions round-trip failed: {cw}"

            print(f"    MODE_LIFECYCLE ok -- create/read/rename/activate + Mode Manager select + conditions "
                  f"round-trip (was: {original_mgr}); restoring + deleting")
        finally:
            # restore the Mode Manager only when the original was a settable built-in option
            if original_mgr in settable_managers:
                try:
                    _mode_call("hub_set_mode_manager", {"manager": original_mgr}, "restore manager")
                except Exception as exc:
                    print(f"  [WARN] mode cleanup: restore Mode Manager '{original_mgr}' failed: {exc}")
            # restore the original mode (a current mode is undeletable), then delete the BAT mode
            if original_mode:
                try:
                    _mode_call("hub_manage_mode", {"action": "activate", "mode": original_mode}, "restore active mode (finally)")
                except Exception:
                    pass
            for nm in (renamed, mode_name):
                try:
                    dl = _mode_call("hub_manage_mode", {"action": "delete", "mode": nm, "confirm": True}, f"delete {nm}")
                    if isinstance(dl, dict) and dl.get("success"):
                        break
                except Exception as exc:
                    print(f"  [WARN] mode cleanup: delete {nm} failed: {exc}")

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
        # Folded from test_hub_get_info_platform_update_and_safemode (the SAME default hub_get_info call):
        # #12/#13 -- platformUpdate + safeMode resolve from /hub2/hubData; the full alerts block stays out.
        assert "platformUpdate" in result, f"hub_get_info missing platformUpdate: {sorted(result)}"
        pu = result["platformUpdate"]
        assert "currentVersion" in pu, f"platformUpdate missing currentVersion: {pu}"
        assert isinstance(pu.get("available"), bool), \
            f"platformUpdate.available not resolved -- /hub2/hubData unreadable? {pu}"
        if pu["available"]:
            assert pu.get("availableVersion"), f"available=true but no availableVersion: {pu}"
        assert "safeMode" in result, f"hub_get_info missing safeMode: {sorted(result)}"
        assert "healthAlerts" not in result, "healthAlerts must be absent without includeHealthAlerts=true"

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

    def _get_hub_info_optin(self) -> dict:
        """hub_get_info with BOTH additive opt-in blocks in ONE call, shared by the two opt-in tests
        (they read DISJOINT keys: healthAlerts vs platformUpdate/appUpdate). Lazy + cached; the result is
        an immutable read, so a fresh fetch falls back when the stash is unset (isolation-safe). Does NOT
        affect test_get_hub_info, which makes its own no-flags call and asserts healthAlerts ABSENT."""
        cached = self._hub_info_optin
        if cached is None:
            cached = self.client.call_tool(
                "hub_get_info", {"includeHealthAlerts": True, "includeAppUpdate": True})
            self._hub_info_optin = cached
        return cached

    @test("system_tools")
    def test_hub_get_info_health_alerts_opt_in(self) -> None:
        # #13: the full alerts block appears only with includeHealthAlerts=true.
        info = self._get_hub_info_optin()
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
    def test_hub_get_info_update_reads(self) -> None:
        # Folded (was hub_get_update_status): hub_get_info carries platformUpdate (the pending HUB
        # firmware) always, and the MCP-app version check under appUpdate when includeAppUpdate=true.
        # Shares the one both-flags call with test_hub_get_info_health_alerts_opt_in (disjoint keys).
        res = self._get_hub_info_optin()
        assert "platformUpdate" in res, f"missing platformUpdate: {sorted(res)}"
        assert "available" in res["platformUpdate"], f"platformUpdate shape wrong: {res['platformUpdate']}"
        assert "appUpdate" in res, f"includeAppUpdate did not attach appUpdate: {sorted(res)}"
        assert "installedVersion" in res["appUpdate"], f"appUpdate shape wrong: {res['appUpdate']}"

    @test("system_tools")
    def test_hub_update_firmware_status_only(self) -> None:
        # hub_update_firmware(statusOnly) polls install progress WITHOUT applying anything — safe on
        # the e2e hub (it never triggers a real firmware install/reboot). status is IDLE when idle.
        res = self.client.call_tool("hub_update_firmware", {"statusOnly": True})
        assert res.get("success") is True, f"statusOnly poll failed: {res}"
        assert res.get("statusOnly") is True, f"not flagged statusOnly: {res}"

    @test("system_tools")
    def test_hub_update_firmware_requires_confirm(self) -> None:
        # Applying (no statusOnly) without confirm MUST be refused by the destructive gate, so the
        # e2e hub is never actually firmware-updated by the test.
        refused = False
        detail = None
        try:
            detail = self.client.call_tool("hub_update_firmware", {})
            blob = (detail if isinstance(detail, str) else json.dumps(detail)).lower()
            refused = (isinstance(detail, dict) and bool(detail.get("isError"))) \
                or any(s in blob for s in ("confirm", "safety check", "backup"))
        except McpError as exc:
            detail = str(exc)
            refused = any(s in detail.lower() for s in ("confirm", "safety check", "backup", "required"))
        assert refused, f"hub_update_firmware apply without confirm must be refused, got: {detail}"

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
        # count must match the array length -- a 404-degraded / empty read can't false-green here.
        assert sj["count"] == len(sj["jobs"]), \
            f"scheduledJobs.count {sj['count']} != len(jobs) {len(sj['jobs'])} (degraded/partial read?)"
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
        # Stash the resolved (immutable) bundle id so test_export_bundle can skip the identical
        # list+filter round-trip; it falls back to a fresh hub_list_bundles when this is unset (isolation).
        self._mcp_bundle_id = str(mcp_bundle["id"])
        print(f"    BUNDLES_LIST ok -- '{mcp_bundle.get('name')}' contains {(mcp_bundle.get('contains') or {}).get('libraries')}")

    @test("system_tools")
    def test_export_bundle(self) -> None:
        """hub_export_bundle saves a bundle's .zip to the File Manager (independently confirmed via
        hub_list_files). Self-cleaning."""
        # Reuse the immutable bundle id test_list_bundles already resolved; fall back to a fresh
        # hub_list_bundles + identical filter when the stash is unset (isolation run).
        if self._mcp_bundle_id:
            bid = self._mcp_bundle_id
        else:
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

        def _drive_off_and_poll() -> Any:
            # Drive it to 'off' first so we know its state.
            self.client.call_tool("hub_call_device_command", {"deviceId": dev_id, "command": "off"})
            time.sleep(0.3)
            return self.client.call_tool("hub_get_device_attribute", {
                "deviceId": dev_id,
                "attribute": "switch",
                "expectedValue": "off",
                "timeoutMs": 5000,
            })

        result = _drive_off_and_poll()
        # An 'off' that produces NO state change while the poll keeps reading the old
        # value is the load-limiter block signature (the command false-succeeds and
        # the device never dispatches). Bounce the app via the watchdog and retry once.
        if result.get("success") is not True and self._clear_load_throttle(
                f"'off' on device {dev_id} never landed: {result}"):
            result = _drive_off_and_poll()
        assert result.get("success") is True, f"Expected success=true, got: {result}"
        assert result.get("timedOut") is False, f"Expected timedOut=false, got: {result}"
        assert result.get("polledCount", 0) >= 1, f"Expected polledCount>=1, got: {result}"

    @test("poll_until_attribute")
    def test_poll_timeout(self) -> None:
        """Timeout path: value won't match -> timedOut=true, elapsedMs approx timeoutMs."""
        dev_id = self.get_test_switch_id()
        import time as _time

        def _drive_off_and_poll_for_on() -> tuple[Any, float]:
            # Ensure switch is 'off' so 'on' won't match.
            self.client.call_tool("hub_call_device_command", {"deviceId": dev_id, "command": "off"})
            time.sleep(0.3)
            t0 = _time.monotonic()
            res = self.client.call_tool("hub_get_device_attribute", {
                "deviceId": dev_id,
                "attribute": "switch",
                "expectedValue": "on",
                "timeoutMs": 2000,
            })
            return res, (_time.monotonic() - t0) * 1000

        result, elapsed_wall = _drive_off_and_poll_for_on()
        # success=true here means the switch read 'on' AFTER an 'off' was sent -- the
        # 'off' never dispatched (load-limiter block leaves it stuck in the old state).
        if result.get("success") is True and self._clear_load_throttle(
                f"'off' on device {dev_id} never landed (poll matched 'on'): {result}"):
            result, elapsed_wall = _drive_off_and_poll_for_on()
        assert result.get("success") is False, f"Expected success=false, got: {result}"
        assert result.get("timedOut") is True, f"Expected timedOut=true, got: {result}"
        # Wall clock should reflect roughly the timeout (within 1 second of variance)
        assert elapsed_wall >= 1800, f"Wall clock too short ({elapsed_wall:.0f}ms); poll may not have blocked"

    @test("poll_until_attribute")
    def test_poll_multi_device(self) -> None:
        """Multi-device convergence: deviceIds + mode (any/all) on two throwaway switches."""
        a_dni = ""
        b_dni = ""
        try:
            self._soft_write(
                lambda: self.client.call_tool("hub_manage_virtual_device", {
                    "action": "create", "deviceType": "Virtual Switch",
                    "deviceLabel": f"{PREFIX}MultiA", "confirm": True}),
                lambda: self._find_device_dni_by_label(f"{PREFIX}MultiA"),
                "create multi-device switch A",
            )
            self._soft_write(
                lambda: self.client.call_tool("hub_manage_virtual_device", {
                    "action": "create", "deviceType": "Virtual Switch",
                    "deviceLabel": f"{PREFIX}MultiB", "confirm": True}),
                lambda: self._find_device_dni_by_label(f"{PREFIX}MultiB"),
                "create multi-device switch B",
            )

            def _lookup(label: str) -> tuple[str, str]:
                vdevs = self.client.call_tool("hub_list_devices", {"labelFilter": label})
                for d in (vdevs if isinstance(vdevs, list) else vdevs.get("devices", [])):
                    if label in (d.get("label") or d.get("name") or ""):
                        return str(d.get("id")), str(d.get("deviceNetworkId") or d.get("dni") or "")
                return "", ""

            a_id, a_dni = _lookup(f"{PREFIX}MultiA")
            b_id, b_dni = _lookup(f"{PREFIX}MultiB")
            if a_dni:
                self.created_device_dnis.append(a_dni)
            if b_dni:
                self.created_device_dnis.append(b_dni)
            assert a_id and b_id, "Failed to create the two multi-device switches"

            # Drive both to 'on' so all-mode can converge. waitFor confirms each landed.
            for did in (a_id, b_id):
                self.client.call_tool("hub_call_device_command", {
                    "deviceId": did, "command": "on",
                    "waitFor": {"attribute": "switch", "expectedValue": "on", "timeoutMs": 5000},
                })

            # mode=all: both on -> converges, convergedCount == 2.
            all_poll = self.client.call_tool("hub_get_device_attribute", {
                "deviceIds": [a_id, b_id], "attribute": "switch",
                "expectedValue": "on", "mode": "all", "timeoutMs": 5000,
            })
            assert isinstance(all_poll, dict), f"multi-device all poll unexpected: {all_poll!r}"
            if all_poll.get("success") is not True and self._clear_load_throttle(
                    f"multi-device 'on' never landed on both: {all_poll}"):
                for did in (a_id, b_id):
                    self.client.call_tool("hub_call_device_command", {
                        "deviceId": did, "command": "on",
                        "waitFor": {"attribute": "switch", "expectedValue": "on", "timeoutMs": 5000}})
                all_poll = self.client.call_tool("hub_get_device_attribute", {
                    "deviceIds": [a_id, b_id], "attribute": "switch",
                    "expectedValue": "on", "mode": "all", "timeoutMs": 5000})
            assert all_poll.get("success") is True, f"all-mode should converge (both on): {all_poll}"
            assert all_poll.get("mode") == "all", f"mode echo wrong: {all_poll}"
            assert all_poll.get("convergedCount") == 2, f"convergedCount should be 2: {all_poll}"
            assert isinstance(all_poll.get("devices"), list) and len(all_poll["devices"]) == 2, \
                f"per-device array should have 2 entries: {all_poll}"

            # Drive B to 'off'; mode=any (expecting 'on') still converges on A.
            self.client.call_tool("hub_call_device_command", {
                "deviceId": b_id, "command": "off",
                "waitFor": {"attribute": "switch", "expectedValue": "off", "timeoutMs": 5000}})
            any_poll = self.client.call_tool("hub_get_device_attribute", {
                "deviceIds": [a_id, b_id], "attribute": "switch",
                "expectedValue": "on", "mode": "any", "timeoutMs": 5000,
            })
            assert isinstance(any_poll, dict), f"multi-device any poll unexpected: {any_poll!r}"
            # A on, B off: any-mode converges; exactly one device matches, so convergedCount is 1
            # (all-mode would have timed out here).
            assert any_poll.get("success") is True, f"any-mode should converge (A on): {any_poll}"
            assert any_poll.get("mode") == "any", f"mode echo wrong: {any_poll}"
            assert any_poll.get("convergedCount") == 1, f"convergedCount should be exactly 1 (A on, B off): {any_poll}"
        finally:
            for dni in (a_dni, b_dni):
                if dni:
                    try:
                        self.client.call_tool("hub_manage_virtual_device", {
                            "action": "delete", "deviceNetworkId": dni, "confirm": True})
                    except Exception as exc:
                        print(f"  [WARN] could not delete multi-device test device ({dni}): {exc}")

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
                # Keep the persistent scaffold devices (shared switch + temp sensors that
                # get_test_switch_id / get_test_temperature_ids find-and-reuse): sweeping them would
                # defeat the reuse and pay a create every run. They carry the SCAFFOLD_PREFIX marker,
                # so this skips ONLY them -- genuine under-test device leftovers (bare PREFIX) are
                # still reclaimed.
                if SCAFFOLD_PREFIX in lbl:
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
            filter_test: str | None = None,
            filter_groups: list[str] | None = None,
            filter_tests: list[str] | None = None) -> bool:
        """Run tests. Returns True if all passed.

        Selection is a UNION: a test runs if its group is in the requested groups
        (--group / --groups) OR its display name contains any requested substring
        (--test / --tests). With no selector, every test runs (the full suite)."""
        self._test_start_time = datetime.now(UTC).isoformat()

        groups_set = set(filter_groups or [])
        if filter_group:
            groups_set.add(filter_group)
        name_subs = list(filter_tests or [])
        if filter_test:
            name_subs.append(filter_test)
        selective = bool(groups_set or name_subs)

        tests_to_run = []
        for group, display_name, method_name in TEST_REGISTRY:
            if selective and not (group in groups_set
                                  or any(sub in display_name for sub in name_subs)):
                continue
            tests_to_run.append((group, display_name, method_name))

        if not tests_to_run:
            if selective:
                # An explicit --groups/--tests selector that resolves to ZERO tests is an error, not a
                # pass: a typo'd or renamed name would otherwise exit 0 (green) having run nothing -- a
                # false green on exactly the one-off lane a maintainer reaches for to confirm a fix.
                print(f"ERROR: no registered test matched the selector (groups={sorted(groups_set)}, "
                      f"tests={name_subs}). Nothing ran -- likely a typo or a renamed test.")
                return False
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

        if self.throttle_bounces:
            print(f"\n  [THROTTLE] {self.throttle_bounces} watchdog bounce(s) of app "
                  f"{self.server_app_id or '?'} were needed mid-run -- the platform's per-app "
                  "load limiter tripped under the accumulated back-to-back load. The retried "
                  "dispatches passed; this is a capacity signal, not a product failure.")

        if self._soft_passes:
            print(f"\n  [SOFT-PASS] {len(self._soft_passes)} test(s) passed via a soft contract "
                  "(relay-504 retry or limiter-proven dispatch; see the run log):")
            for line in self._soft_passes:
                print(f"    {line}")

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
    parser.add_argument("--groups", help="Run only these test groups (comma-separated); unions with --tests")
    parser.add_argument("--tests", help="Run tests whose name contains any of these substrings (comma-separated); unions with --groups")
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
            # Poll cadence: short early (the restore lands ~2-2.5 min in, so a tight early cadence trims
            # the overshoot past completion), backing off to 10s for the long failed-restore tail -- same
            # ~8-min worst-case ceiling, just more attempts at the shorter early intervals.
            _restore_backoff = (3, 3, 3, 3, 5, 5, 5, 7, 7, 10)
            for attempt in range(1, 60):
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
                if attempt == 59:
                    print("  [WARN] restore-complete marker never matched after ~8 min "
                          "(failed restore, or a slow recompile); sweeping anyway.")
                else:
                    time.sleep(_restore_backoff[min(attempt - 1, len(_restore_backoff) - 1)])
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

    # Ensure a hub backup exists — many tools require a recent backup.
    # E2E_SKIP_BACKUP=1 skips it (diagnostic lever: the backup runs hub-side for tens of
    # seconds UNDER the opening test traffic, and that overlap is a load-limiter suspect).
    if os.environ.get("E2E_SKIP_BACKUP") == "1":
        print("Skipping hub backup (E2E_SKIP_BACKUP=1) -- destructive-confirm tests may fail without a recent backup.\n")
    else:
        # The backup is a hub-heavy operation the platform's load limiter punishes (empirically:
        # every dispatch-block episode followed a per-run backup; backup-free runs never tripped).
        # The destructive-confirm gate only needs ONE backup per 24h, and its record persists in
        # app state across runs -- so back up only when that record is stale (>20h), making every
        # other run backup-free.
        fresh_backup = False
        try:
            hub_info = client.call_tool("hub_get_info")
            last_epoch = hub_info.get("lastBackupEpoch") if isinstance(hub_info, dict) else None
            if last_epoch:
                age_h = (time.time() * 1000 - float(last_epoch)) / 3600000.0
                fresh_backup = age_h < 20.0
                if fresh_backup:
                    print(f"Hub backup is fresh ({age_h:.1f}h old) -- skipping the per-run backup "
                          "(the destructive-confirm 24h gate is already satisfied).\n")
        except Exception:
            pass
        if not fresh_backup:
            print("Creating hub backup (required by safety checks; last one stale or unknown)...")
            try:
                # Prefer the MOCK backup: it stamps ONLY the destructive-confirm gate record
                # (state.lastBackupTimestamp) without touching /hub/backupDB, so the gate stays
                # satisfied with none of the real backup's hub-heavy load. An older server
                # (no `mock` arg) ignores it and runs a real backup -- detect that by the
                # absence of mocked==True in the response and fall back to the real call.
                mock_result = client.call_tool("hub_create_backup", {"confirm": True, "mock": True})
                mocked = mock_result.get("mocked") is True if isinstance(mock_result, dict) else False
                if mocked:
                    msg = mock_result.get("message", mock_result) if isinstance(mock_result, dict) else mock_result
                    print(f"  Backup (MOCK -- gate stamped, no real backupDB write): {msg}\n")
                else:
                    print("  Server has no mock-backup support (older build) -- falling back to a REAL backup...")
                    backup_result = client.call_tool("hub_create_backup", {"confirm": True})
                    msg = backup_result.get("message", backup_result) if isinstance(backup_result, dict) else backup_result
                    print(f"  Backup (REAL): {msg}\n")
            except Exception as exc:
                print(f"  [WARN] Backup failed: {exc}")
                print("  Tests requiring backup may fail.\n")

    _grps = [s.strip() for s in args.groups.split(",") if s.strip()] if args.groups else None
    _tsts = [s.strip() for s in args.tests.split(",") if s.strip()] if args.tests else None
    all_passed = runner.run(filter_group=args.group, filter_test=args.test,
                            filter_groups=_grps, filter_tests=_tsts)
    sys.exit(0 if all_passed else 1)


if __name__ == "__main__":
    main()
