"""Keep the live preference oracle independent from the server's parser."""

from copy import deepcopy

import pytest
from device_configuration_helpers import assert_native_preferences


def fixture():
    expected = {
        "probeBool": ("bool", False),
        "probeNumber": ("number", 0),
        "probeText": ("text", "literal & + = café"),
        "probeEnum": ("enum", "eco"),
        "probeMultiple": ("enum", ["red", "blue"]),
    }
    wire = ["false", "0", "literal & + = café", "eco", "red,blue"]
    native = {
        "settings": [{"name": name, "type": kind, "value": value}
                     for (name, (kind, _)), value in zip(expected.items(), wire, strict=True)],
        "inputValues": [{"name": name, "inputValue": value}
                        for name, value in zip(expected, wire, strict=True)],
    }
    configuration = {"preferences": [
        {"name": name, "type": kind, "value": value,
         "valuePresent": True, "valueStatus": "stored"}
        for name, (kind, value) in expected.items()
    ]}
    return native, configuration, expected


def test_accepts_native_wire_values_and_exact_typed_configuration():
    native, configuration, expected = fixture()
    assert_native_preferences(native, configuration, expected)


@pytest.mark.parametrize("surface", ["settings", "inputValues"])
@pytest.mark.parametrize("wire", ['["red","blue"]', '["red", "blue"]'])
def test_accepts_native_json_array_storage_without_changing_typed_expectation(surface, wire):
    native, configuration, expected = fixture()
    native[surface][-1]["value" if surface == "settings" else "inputValue"] = wire
    assert_native_preferences(native, configuration, expected)


@pytest.mark.parametrize("wire", ['["red"]', '["blue","red"]', '["red","blue","red"]'])
def test_json_array_storage_with_a_different_selection_fails(wire):
    native, configuration, expected = fixture()
    native["settings"][-1]["value"] = wire
    with pytest.raises(AssertionError, match="probeMultiple"):
        assert_native_preferences(native, configuration, expected)


def test_empty_json_array_is_a_saved_empty_selection():
    native, configuration, expected = fixture()
    expected["probeMultiple"] = ("enum", [])
    native["settings"][-1]["value"] = '[]'
    native["inputValues"][-1]["inputValue"] = '[]'
    configuration["preferences"][-1]["value"] = []
    assert_native_preferences(native, configuration, expected)


@pytest.mark.parametrize("surface", ["settings", "inputValues", "preferences"])
def test_missing_expected_row_cannot_become_a_vacuous_pass(surface):
    native, configuration, expected = fixture()
    target = configuration if surface == "preferences" else native
    target[surface] = target[surface][1:]
    with pytest.raises(AssertionError, match="probeBool"):
        assert_native_preferences(native, configuration, expected)


@pytest.mark.parametrize("surface", ["settings", "inputValues"])
def test_native_saved_value_mismatch_fails_even_when_mcp_reports_success(surface):
    native, configuration, expected = fixture()
    native[surface][0]["value" if surface == "settings" else "inputValue"] = "true"
    with pytest.raises(AssertionError, match="probeBool"):
        assert_native_preferences(native, configuration, expected)


@pytest.mark.parametrize("replacement", [0, "false", None])
def test_boolean_configuration_value_requires_actual_boolean(replacement):
    native, configuration, expected = fixture()
    configuration["preferences"][0]["value"] = replacement
    with pytest.raises(AssertionError, match="probeBool"):
        assert_native_preferences(native, configuration, expected)


def test_zero_cannot_be_reported_as_false():
    native, configuration, expected = fixture()
    configuration["preferences"][1]["value"] = False
    with pytest.raises(AssertionError, match="probeNumber"):
        assert_native_preferences(native, configuration, expected)


@pytest.mark.parametrize("status", ["unknown", "unset", "invalid"])
def test_stored_values_cannot_be_reported_unavailable(status):
    native, configuration, expected = fixture()
    configuration["preferences"][0]["valueStatus"] = status
    with pytest.raises(AssertionError, match="probeBool"):
        assert_native_preferences(native, configuration, expected)


def test_nested_settings_cannot_substitute_for_native_root():
    native, configuration, expected = fixture()
    native["device"] = {"settings": native.pop("settings")}
    with pytest.raises(AssertionError, match="settings"):
        assert_native_preferences(native, configuration, expected)


def test_duplicate_native_names_fail_instead_of_overwriting_evidence():
    native, configuration, expected = fixture()
    native["inputValues"].append(deepcopy(native["inputValues"][0]))
    with pytest.raises(AssertionError, match=r"duplicate.*probeBool"):
        assert_native_preferences(native, configuration, expected)


def test_empty_expected_preferences_are_not_proof():
    with pytest.raises(AssertionError, match="expected"):
        assert_native_preferences({"settings": [], "inputValues": []}, {"preferences": []}, {})
