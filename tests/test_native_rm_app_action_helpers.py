"""Pure public-response helper coverage for cloud app-action acceptance."""

import json

import native_rm_parity_app_actions_e2e as native_apps
import pytest


def test_native_fixture_identity_uses_actual_public_instance_fields():
    row = {"id": "42", "name": "Owned scheduler", "type": "Thermostat Scheduler 2.0", "parentId": "28"}
    assert native_apps.native_fixture_identity(row, 42, "Thermostat Scheduler 2.0", 28) is row


@pytest.mark.parametrize("row", [None, {}, {"id": "99", "type": "Scene-1.2", "parentId": "28"},
                                 {"id": "42", "type": "Group-2.1", "parentId": "28"}])
def test_native_fixture_identity_refuses_missing_changed_id_and_changed_type(row):
    with pytest.raises(AssertionError, match="identity changed"):
        native_apps.native_fixture_identity(row, 42, "Scene-1.2", 28)


def test_native_fixture_identity_refuses_another_parent_before_cleanup():
    with pytest.raises(AssertionError, match="parent changed"):
        native_apps.native_fixture_identity({"id": "42", "type": "Scene-1.2", "parentId": "99"},
                                           42, "Scene-1.2", 28)


def test_scheduler_modes_read_exact_owned_app_state_not_transient_settings():
    table = {"1": {"heat": 68, "used": True}}
    exported = {"success": True, "jsonContent": json.dumps({"appData": {
        "42": {"state": {"modeTable": table}, "appSettings": [{"name": "perX", "value": ["3"]}]},
        "99": {"state": {"modeTable": {"3": {"heat": 75, "used": True}}}},
    }})}
    assert native_apps.exported_scheduler_modes(exported, 42) == table
    assert "3" not in native_apps.exported_scheduler_modes(exported, 42)


@pytest.mark.parametrize("exported", [
    {"success": False, "jsonContent": "{}"},
    {"success": True, "jsonContent": '{"appData":{"99":{"state":{"modeTable":{}}}}}'},
    {"success": True, "jsonContent": '{"appData":{"42":{"state":{}}}}'},
    {"success": True, "jsonContent": '{"appData":{"42":{"state":{"modeTable":[]}}}}'},
])
def test_scheduler_export_rejects_failure_wrong_app_or_missing_table(exported):
    with pytest.raises(AssertionError):
        native_apps.exported_scheduler_modes(exported, 42)


def test_owned_parent_identity_refuses_an_unexpected_parent_relationship():
    with pytest.raises(AssertionError, match="parent changed"):
        native_apps.native_fixture_identity({"id": "28", "type": "Thermostat Controllers", "parentId": "7"},
                                           28, "Thermostat Controllers", None)
