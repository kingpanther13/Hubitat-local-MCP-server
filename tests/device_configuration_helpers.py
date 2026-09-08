"""Assertions for the test driver's independent, bounded native snapshots."""

import json


def _rows_by_name(document, surface):
    rows = document.get(surface)
    assert isinstance(rows, list), f"native/configuration {surface} is unavailable: {rows!r}"
    indexed = {}
    for row in rows:
        assert isinstance(row, dict) and isinstance(row.get("name"), str), f"invalid {surface} row: {row!r}"
        name = row["name"]
        assert name not in indexed, f"duplicate {surface} preference {name}"
        indexed[name] = row
    return indexed


def _native_wire(value):
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, list):
        return ",".join(value)
    if isinstance(value, (int, float)):
        return str(value)
    return value


def assert_native_preferences(native, configuration, expected):
    """Require both raw native collections and typed MCP values to match known writes.

    The test supplies the exact fixture values. This oracle never calls or copies
    the production preference reader, and never treats empty discovery as success.
    """
    assert expected, "expected fixture preferences must not be empty"
    settings = _rows_by_name(native, "settings")
    inputs = _rows_by_name(native, "inputValues")
    preferences = _rows_by_name(configuration, "preferences")
    for name, (kind, value) in expected.items():
        for surface, rows in (("settings", settings), ("inputValues", inputs), ("preferences", preferences)):
            assert name in rows, f"{surface} is missing expected preference {name}"
        assert settings[name].get("type") == kind, f"native {name} type differs: {settings[name]}"
        for surface, row, key in (("settings", settings[name], "value"), ("inputValues", inputs[name], "inputValue")):
            assert key in row, f"native {surface} {name} is missing {key}"
            expected_wire = [_native_wire(value)]
            if isinstance(value, list):
                expected_wire.extend((json.dumps(value), json.dumps(value, separators=(",", ":"))))
            assert _native_wire(row[key]) in expected_wire, \
                f"native {surface} {name} saved {row[key]!r}, expected {value!r}"
        pref = preferences[name]
        assert pref.get("type") == kind, f"configuration {name} type differs: {pref}"
        assert pref.get("valuePresent") is True and pref.get("valueStatus") == "stored", \
            f"configuration {name} does not identify a stored value: {pref}"
        actual = pref.get("value")
        assert type(actual) is type(value) and actual == value, \
            f"configuration {name} saved {actual!r} ({type(actual).__name__}), expected {value!r} ({type(value).__name__})"
