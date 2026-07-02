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
