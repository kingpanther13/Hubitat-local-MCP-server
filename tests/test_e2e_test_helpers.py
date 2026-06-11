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
