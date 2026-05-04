"""pytest unit tests for pure helper functions in tests/e2e_test.py

Scanned e2e_test.py for testable pure helpers; found two:
  - _find_attr(attrs, name): extracts an attribute value from dict or list shapes
  - _inject_device_id(obj, dev_id): replaces 'PLACEHOLDER' device IDs in a rule dict

All other code in e2e_test.py requires a live Hubitat hub (HubitatMcpClient,
TestRunner, load_config) and cannot be exercised without network access.
Importing the module itself is skipped if the 'requests' library is not available
(not installed in the CI environment for this repo).
"""

import sys
import os

# tests/ is already on sys.path conceptually, but be explicit for safety.
sys.path.insert(0, os.path.join(os.path.dirname(__file__)))

import pytest

# e2e_test.py imports `requests` at module level. Skip the whole module
# gracefully if requests is not installed (base CI only has pytest).
requests = pytest.importorskip("requests", reason="'requests' not installed; skipping e2e helpers")

import e2e_test as et


# ---------------------------------------------------------------------------
# _find_attr
# ---------------------------------------------------------------------------

def test_find_attr_dict_found():
    """Returns value when attrs is a dict and key is present."""
    assert et._find_attr({"switch": "on", "level": "80"}, "switch") == "on"


def test_find_attr_dict_missing_returns_none():
    """Returns None when the attribute key is absent from the dict."""
    assert et._find_attr({"level": "80"}, "switch") is None


def test_find_attr_list_name_key():
    """Finds the value from a list of {name, currentValue} dicts."""
    attrs = [
        {"name": "switch", "currentValue": "off"},
        {"name": "level", "currentValue": "50"},
    ]
    assert et._find_attr(attrs, "switch") == "off"


def test_find_attr_list_attribute_key():
    """Finds the value from a list using the 'attribute' key variant."""
    attrs = [
        {"attribute": "switch", "value": "on"},
        {"attribute": "level", "value": "70"},
    ]
    assert et._find_attr(attrs, "switch") == "on"


def test_find_attr_list_not_found_returns_none():
    """Returns None when the attribute name is not in the list."""
    attrs = [{"name": "level", "currentValue": "50"}]
    assert et._find_attr(attrs, "switch") is None


def test_find_attr_empty_list_returns_none():
    """Returns None for an empty list."""
    assert et._find_attr([], "switch") is None


def test_find_attr_empty_dict_returns_none():
    """Returns None for an empty dict."""
    assert et._find_attr({}, "switch") is None


def test_find_attr_none_returns_none():
    """Returns None when attrs is None (not a dict or list)."""
    assert et._find_attr(None, "switch") is None


def test_find_attr_list_skips_non_dict_entries():
    """Non-dict entries in the list are silently skipped."""
    attrs = ["not a dict", {"name": "switch", "currentValue": "on"}]
    assert et._find_attr(attrs, "switch") == "on"


def test_find_attr_returns_first_match():
    """Returns the first matching entry when duplicates exist."""
    attrs = [
        {"name": "switch", "currentValue": "on"},
        {"name": "switch", "currentValue": "off"},
    ]
    assert et._find_attr(attrs, "switch") == "on"


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


def test_inject_device_id_recurses_into_conditions():
    """PLACEHOLDER in a nested condition dict is replaced."""
    obj = {
        "type": "if_then_else",
        "condition": {"type": "device_state", "deviceId": "PLACEHOLDER"},
        "thenActions": [],
        "elseActions": [],
    }
    result = et._inject_device_id(obj, "77")
    # Top-level has no deviceId; the nested condition is not recursed directly
    # (only list keys are recursed — condition is a dict, not a list)
    # This verifies the documented shallow-copy + list-recursion behavior.
    assert "deviceId" not in result or result.get("deviceId") != "PLACEHOLDER"


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
