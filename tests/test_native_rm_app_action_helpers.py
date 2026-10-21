"""Pure public-response helper coverage for cloud app-action acceptance."""

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


def test_scheduler_mode_evidence_comes_from_rendered_paragraphs_not_options_or_settings():
    configuration = {"settings": {"perX": ["Day"], "tempX": 68}, "page": {"sections": [
        {"inputs": [{"name": "perX", "options": [{"Day": "Day"}]}],
         "paragraphs": ["<b>Night</b> Heat 65 Cool 75"]}]}}
    assert native_apps.scheduler_mode_rows(configuration, "Day") == []
    assert native_apps.scheduler_mode_rows(configuration, "Night") == ["Night Heat 65 Cool 75"]


def test_scheduler_rendered_mode_matching_preserves_names_without_substring_matches():
    configuration = {"page": {"sections": [{"paragraphs": ["Daytime Heat 68", "<span>Day &amp; Away</span> Heat 68.0"]}]}}
    assert native_apps.scheduler_mode_rows(configuration, "Day") == ["Day & Away Heat 68.0"]
    assert native_apps.scheduler_mode_rows(configuration, "Day & Away") == ["Day & Away Heat 68.0"]
    assert native_apps.page_paragraphs(configuration) == ["Daytime Heat 68", "Day & Away Heat 68.0"]


def test_owned_parent_identity_refuses_an_unexpected_parent_relationship():
    with pytest.raises(AssertionError, match="parent changed"):
        native_apps.native_fixture_identity({"id": "28", "type": "Thermostat Controllers", "parentId": "7"},
                                           28, "Thermostat Controllers", None)
