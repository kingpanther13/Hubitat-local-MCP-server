"""pytest unit tests for pure helper functions in tests/e2e_test.py

Scanned e2e_test.py for testable pure helpers; found one:
  - _inject_device_id(obj, dev_id): replaces 'PLACEHOLDER' device IDs in a rule dict

All other code in e2e_test.py requires a live Hubitat hub (HubitatMcpClient,
TestRunner, load_config) and cannot be exercised without network access.
Importing the module itself is skipped if the 'requests' library is not available
(not installed in the CI environment for this repo).
"""

import os
import sys

# tests/ is already on sys.path conceptually, but be explicit for safety.
sys.path.insert(0, os.path.join(os.path.dirname(__file__)))

import pytest

# e2e_test.py imports `requests` at module level. Skip the whole module
# gracefully if requests is not installed (base CI only has pytest).
requests = pytest.importorskip("requests", reason="'requests' not installed; skipping e2e helpers")

import e2e_test as et  # noqa: E402 -- must follow the importorskip above (e2e_test imports requests at module level)

# ---------------------------------------------------------------------------
# _inject_device_id
# ---------------------------------------------------------------------------

def test_inject_device_id_replaces_placeholder():
    """PLACEHOLDER in deviceId is replaced with the given device ID."""
    obj = {"type": "device_event", "deviceId": "PLACEHOLDER", "attribute": "switch"}
    result = et._inject_device_id(obj, "99")
    assert result["deviceId"] == "99"


def test_inject_device_id_non_placeholder_unchanged():
    """Non-PLACEHOLDER deviceId values are left unchanged."""
    obj = {"type": "device_command", "deviceId": "42", "command": "on"}
    result = et._inject_device_id(obj, "99")
    assert result["deviceId"] == "42"


def test_inject_device_id_no_device_id_key():
    """Object with no deviceId key passes through unchanged."""
    obj = {"type": "log", "message": "hello"}
    result = et._inject_device_id(obj, "99")
    assert result == obj


def test_inject_device_id_does_not_mutate_original():
    """Original dict is not mutated (shallow copy)."""
    obj = {"deviceId": "PLACEHOLDER", "type": "x"}
    original_id = obj["deviceId"]
    et._inject_device_id(obj, "55")
    assert obj["deviceId"] == original_id


def test_inject_device_id_does_not_recurse_into_dict_condition():
    """Singular `condition` is a dict, not a list — _inject_device_id only
    recurses into list-valued keys (conditions/thenActions/elseActions/actions),
    so a PLACEHOLDER inside a dict-typed `condition` is left untouched. This
    locks in the documented limitation; a future refactor that adds dict
    recursion will need to update this test."""
    obj = {
        "type": "if_then_else",
        "condition": {"type": "device_state", "deviceId": "PLACEHOLDER"},
        "thenActions": [],
        "elseActions": [],
    }
    result = et._inject_device_id(obj, "77")
    assert result["condition"]["deviceId"] == "PLACEHOLDER"


def test_inject_device_id_recurses_into_actions_list():
    """PLACEHOLDER inside an 'actions' list entry is replaced."""
    obj = {
        "type": "rule",
        "actions": [
            {"type": "device_command", "deviceId": "PLACEHOLDER", "command": "on"},
            {"type": "log", "message": "done"},
        ],
    }
    result = et._inject_device_id(obj, "33")
    assert result["actions"][0]["deviceId"] == "33"
    assert result["actions"][1].get("deviceId") is None


def test_inject_device_id_recurses_into_then_actions():
    """PLACEHOLDER inside 'thenActions' list entry is replaced."""
    obj = {
        "type": "if_then_else",
        "thenActions": [{"type": "device_command", "deviceId": "PLACEHOLDER"}],
        "elseActions": [],
    }
    result = et._inject_device_id(obj, "11")
    assert result["thenActions"][0]["deviceId"] == "11"


def test_inject_device_id_recurses_into_else_actions():
    """PLACEHOLDER inside 'elseActions' list entry is replaced."""
    obj = {
        "type": "if_then_else",
        "thenActions": [],
        "elseActions": [{"type": "device_command", "deviceId": "PLACEHOLDER"}],
    }
    result = et._inject_device_id(obj, "22")
    assert result["elseActions"][0]["deviceId"] == "22"


# ---------------------------------------------------------------------------
# _op_key (per-op timing key resolution)
# ---------------------------------------------------------------------------

def test_op_key_gateway_set_rule_create():
    """Gateway-wrapped hub_set_rule with no inner appId resolves to a :create op."""
    assert et._op_key("hub_manage_rule_machine", {"tool": "hub_set_rule", "args": {}}) == "hub_set_rule:create"


def test_op_key_gateway_set_rule_edit():
    """An inner appId marks an :edit (mutation), so fixture-create cost stays separable in the summary."""
    assert et._op_key("hub_manage_rule_machine", {"tool": "hub_set_rule", "args": {"appId": "5"}}) == "hub_set_rule:edit"


def test_op_key_gateway_other_subtool_uses_sub_tool():
    """A gateway call resolves to its sub-tool, not the gateway name."""
    assert et._op_key("hub_manage_rule_machine", {"tool": "hub_list_rules", "args": {}}) == "hub_list_rules"


def test_op_key_flat_tool_uses_name():
    """A flat (non-gateway) call resolves to the tool name; None args are tolerated."""
    assert et._op_key("hub_get_info", {}) == "hub_get_info"
    assert et._op_key("hub_get_info", None) == "hub_get_info"


# ---------------------------------------------------------------------------
# _gateway_route_from_catalog (issue #319 leaf -> gateway reverse map)
# ---------------------------------------------------------------------------

def _gw(name: str, subtools: list[str]) -> dict:
    """A minimal gateway-mode tools/list gateway entry (the {tool, args} envelope)."""
    return {
        "name": name,
        "inputSchema": {
            "type": "object",
            "properties": {
                "tool": {"type": "string", "enum": subtools},
                "args": {"type": "object"},
            },
        },
    }


def _leaf(name: str, props: dict | None = None) -> dict:
    """A minimal core/leaf tools/list entry (no {tool, args} envelope)."""
    return {"name": name, "inputSchema": {"type": "object", "properties": props or {}}}


def test_route_map_routes_leaf_to_its_gateway():
    route = et._gateway_route_from_catalog([_gw("hub_manage_rooms", ["hub_create_room", "hub_delete_room"])])
    assert route == {"hub_create_room": "hub_manage_rooms", "hub_delete_room": "hub_manage_rooms"}


def test_route_map_ignores_core_leaf_entries():
    """Core tools (no tool+args envelope) contribute nothing -- they are never routed."""
    route = et._gateway_route_from_catalog([
        _leaf("hub_get_info"),
        _leaf("hub_manage_virtual_device", {"action": {"type": "string", "enum": ["create", "delete"]}}),
        _gw("hub_manage_rooms", ["hub_create_room"]),
    ])
    assert "hub_get_info" not in route
    assert "hub_manage_virtual_device" not in route
    assert route["hub_create_room"] == "hub_manage_rooms"


def test_route_map_multi_gateway_read_prefers_pure_read_gateway():
    """A read living in both a manage_ and a read_ gateway routes through the read surface,
    regardless of catalog order."""
    entries = [
        _gw("hub_manage_devices", ["hub_list_devices", "hub_call_device_command"]),
        _gw("hub_read_devices", ["hub_list_devices"]),
    ]
    route = et._gateway_route_from_catalog(entries)
    assert route["hub_list_devices"] == "hub_read_devices"
    assert route["hub_call_device_command"] == "hub_manage_devices"
    # ...and with the read gateway FIRST it stays on the read surface.
    route_rev = et._gateway_route_from_catalog(list(reversed(entries)))
    assert route_rev["hub_list_devices"] == "hub_read_devices"


def test_route_map_multi_manage_membership_first_gateway_wins():
    """A write in two manage_ gateways routes through the first in catalog order (deterministic)."""
    route = et._gateway_route_from_catalog([
        _gw("hub_manage_rule_machine", ["hub_delete_native_app"]),
        _gw("hub_manage_native_rules_and_apps", ["hub_delete_native_app"]),
    ])
    assert route["hub_delete_native_app"] == "hub_manage_rule_machine"


def test_route_map_flat_catalog_yields_empty_map():
    """A flat-mode catalog (every tool a leaf) builds an empty map -- every call falls
    through to direct dispatch."""
    assert et._gateway_route_from_catalog([_leaf("hub_list_rooms"), _leaf("hub_get_info")]) == {}


def test_route_map_tolerates_missing_schema_and_enum():
    """Entries without inputSchema/properties/enum are skipped, not crashed on."""
    route = et._gateway_route_from_catalog([
        {"name": "hub_weird"},
        {"name": "hub_no_props", "inputSchema": {"type": "object"}},
        {"name": "hub_no_enum", "inputSchema": {"type": "object", "properties": {"tool": {"type": "string"}, "args": {"type": "object"}}}},
        {"name": "hub_nondict_tool", "inputSchema": {"type": "object", "properties": {"tool": None, "args": {"type": "object"}}}},
        _gw("hub_manage_rooms", ["hub_create_room"]),
    ])
    assert route == {"hub_create_room": "hub_manage_rooms"}


# ---------------------------------------------------------------------------
# _gateway_members_from_catalog + the call_tool membership guard (issue #319)
# ---------------------------------------------------------------------------

def test_members_map_lists_each_gateways_subtools():
    members = et._gateway_members_from_catalog([
        _gw("hub_manage_rooms", ["hub_list_rooms", "hub_create_room"]),
        _gw("hub_read_devices", ["hub_list_devices"]),
        _leaf("hub_get_info"),
    ])
    assert members == {
        "hub_manage_rooms": {"hub_list_rooms", "hub_create_room"},
        "hub_read_devices": {"hub_list_devices"},
    }
    assert "hub_get_info" not in members   # core tools are not gateways


def test_members_map_flat_catalog_is_empty():
    assert et._gateway_members_from_catalog([_leaf("hub_list_rooms"), _leaf("hub_get_info")]) == {}


def _client_with_catalog(tools: list) -> "et.HubitatMcpClient":
    """A client whose catalog maps are pre-seeded from `tools` without any network I/O."""
    c = et.HubitatMcpClient.__new__(et.HubitatMcpClient)
    c._gateway_members = et._gateway_members_from_catalog(tools)
    c._gateway_route = et._gateway_route_from_catalog(tools)
    return c


def test_membership_guard_rejects_wrong_gateway():
    """A gateway-envelope call whose sub-tool is NOT a member of the named gateway raises
    loudly -- the guard against the _find_app_id_by_label class of silent bug."""
    c = _client_with_catalog([
        _gw("hub_manage_native_rules_and_apps", ["hub_list_rules", "hub_set_native_app"]),
        _gw("hub_read_apps_code", ["hub_list_apps"]),
    ])
    # hub_list_apps is a member of hub_read_apps_code, NOT hub_manage_native_rules_and_apps.
    with pytest.raises(et.McpError) as ei:
        c.call_tool("hub_manage_native_rules_and_apps",
                    {"tool": "hub_list_apps", "args": {"scope": "instances"}})
    msg = str(ei.value)
    assert "not a member" in msg and "hub_list_apps" in msg and "hub_manage_native_rules_and_apps" in msg


def test_membership_guard_allows_valid_membership_then_routes():
    """A valid gateway-envelope call passes the guard. (It would then be sent as-is; we
    stop before network I/O by asserting no guard error is raised for a real member.)"""
    c = _client_with_catalog([_gw("hub_manage_rooms", ["hub_list_rooms", "hub_delete_room"])])
    # No McpError from the guard for a real member; _send would be next (not exercised here).
    # Drive only the guard by monkeypatching _send to short-circuit.
    c._request_id = 0
    c.op_timings = []
    c._send = lambda method, params: {"content": [{"type": "text", "text": "{}"}]}
    c.call_tool("hub_manage_rooms", {"tool": "hub_delete_room", "args": {"room": "X", "confirm": True}})


def test_membership_guard_skipped_for_flat_calls():
    """flat=True bypasses the guard entirely (deliberate flat-dispatch proofs)."""
    c = _client_with_catalog([_gw("hub_manage_rooms", ["hub_list_rooms"])])
    c._send = lambda method, params: {"content": [{"type": "text", "text": "{}"}]}
    c.op_timings = []
    # A leaf name with flat=True is never treated as a gateway envelope; no guard, no raise.
    c.call_tool("hub_list_rooms", flat=True)


# ---------------------------------------------------------------------------
# TestRunner._list_all_file_names (issue #342: size-guard-immune paginated listing)
# ---------------------------------------------------------------------------

class _PagedFilesClient:
    """Stub client: hub_read_files/hub_list_files pops one canned page per call."""

    def __init__(self, pages):
        self._pages = list(pages)
        self.calls = []

    def call_tool(self, name, arguments=None, **_kw):
        self.calls.append((name, arguments))
        item = self._pages.pop(0)
        if isinstance(item, Exception):
            raise item
        return item


def _list_files_via(pages):
    runner = object.__new__(et.TestRunner)  # no __init__: only .client is needed
    runner.client = _PagedFilesClient(pages)
    return et.TestRunner._list_all_file_names(runner), runner.client


def test_list_all_file_names_accumulates_across_pages_and_forwards_cursor():
    """Names accumulate across pages; each nextCursor is forwarded verbatim."""
    (names, authoritative), client = _list_files_via([
        {"files": [{"name": "a.txt"}, {"name": "b.txt"}], "nextCursor": "2"},
        {"files": [{"name": "c.txt"}]},
    ])
    assert names == ["a.txt", "b.txt", "c.txt"]
    assert authoritative is True
    assert client.calls[0][1]["args"]["cursor"] == ""
    assert client.calls[1][1]["args"]["cursor"] == "2"


def test_list_all_file_names_response_too_large_is_non_authoritative():
    """A response_too_large envelope must NOT read as an authoritative empty listing
    (the false-'absent' verdict that failed test_export_bundle)."""
    (names, authoritative), _ = _list_files_via([
        {"files": [{"name": "a.txt"}], "nextCursor": "1"},
        {"response_too_large": True, "truncated": True},
    ])
    assert authoritative is False
    assert names == ["a.txt"]  # partial names kept: presence evidence stays usable


def test_list_all_file_names_degraded_blind_empty_page_is_non_authoritative():
    """A blind empty page with a message/error marker (File Manager degraded under
    load) is non-authoritative."""
    (names, authoritative), _ = _list_files_via([
        {"files": [], "message": "File Manager unavailable"},
    ])
    assert authoritative is False
    assert names == []


def test_list_all_file_names_clean_empty_listing_is_authoritative():
    """A marker-free empty page is a real (authoritative) empty File Manager."""
    (names, authoritative), _ = _list_files_via([{"files": []}])
    assert authoritative is True
    assert names == []


def test_list_all_file_names_transport_error_is_non_authoritative():
    """A transport-level failure mid-enumeration keeps partial names, drops authority."""
    (names, authoritative), _ = _list_files_via([
        {"files": [{"name": "a.txt"}], "nextCursor": "1"},
        et.McpError("relay 504"),
    ])
    assert authoritative is False
    assert names == ["a.txt"]
