"""pytest unit tests for scripts/app_config_audit.py

Covers: check_invariants (pure shape-validation function) and
load_targets_from_config (file-based helper). No network calls are made.
"""

import json
import sys
import os

# Make app_config_audit importable without installing it.
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "scripts"))

import pytest
import app_config_audit as aca


# ---------------------------------------------------------------------------
# check_invariants — top-level shape
# ---------------------------------------------------------------------------

def _good_payload(sections=None):
    """Minimal valid payload that satisfies all invariants."""
    return {
        "app": {"id": 42, "name": "My App"},
        "configPage": {
            "sections": sections if sections is not None else [],
        },
    }


def test_check_invariants_minimal_valid_payload():
    """Minimal valid payload (empty sections, no settings) passes all invariants."""
    assert aca.check_invariants(_good_payload()) == []


def test_check_invariants_not_a_dict():
    """Non-dict top-level returns a single 'top-level is ..., expected object' failure."""
    failures = aca.check_invariants("a string")
    assert len(failures) == 1
    assert "top-level is str" in failures[0]
    assert "expected object" in failures[0]


def test_check_invariants_list_top_level():
    """List at top-level is also caught as non-dict."""
    failures = aca.check_invariants([{"app": {}}])
    assert any("top-level is list" in f for f in failures)


def test_check_invariants_none_top_level():
    """None at top-level is caught."""
    failures = aca.check_invariants(None)
    assert len(failures) == 1
    assert "top-level is NoneType" in failures[0]


# ---------------------------------------------------------------------------
# check_invariants — 'app' field
# ---------------------------------------------------------------------------

def test_check_invariants_missing_app():
    """Missing 'app' field is flagged."""
    payload = {"configPage": {"sections": []}}
    failures = aca.check_invariants(payload)
    assert any("missing or non-object 'app'" in f for f in failures)


def test_check_invariants_app_not_dict():
    """Non-dict 'app' is flagged."""
    payload = {"app": "not a dict", "configPage": {"sections": []}}
    failures = aca.check_invariants(payload)
    assert any("missing or non-object 'app'" in f for f in failures)


def test_check_invariants_app_missing_id():
    """app.id missing is flagged."""
    payload = {"app": {"name": "Test"}, "configPage": {"sections": []}}
    failures = aca.check_invariants(payload)
    assert any("app.id missing" in f for f in failures)


def test_check_invariants_app_missing_name():
    """app.name missing is flagged."""
    payload = {"app": {"id": 1}, "configPage": {"sections": []}}
    failures = aca.check_invariants(payload)
    assert any("app.name missing" in f for f in failures)


# ---------------------------------------------------------------------------
# check_invariants — 'configPage' and 'sections'
# ---------------------------------------------------------------------------

def test_check_invariants_missing_config_page():
    """Missing 'configPage' is flagged and early-returns (no cascade)."""
    payload = {"app": {"id": 1, "name": "App"}}
    failures = aca.check_invariants(payload)
    assert any("missing or non-object 'configPage'" in f for f in failures)


def test_check_invariants_config_page_not_dict():
    """Non-dict 'configPage' is flagged."""
    payload = {"app": {"id": 1, "name": "App"}, "configPage": []}
    failures = aca.check_invariants(payload)
    assert any("missing or non-object 'configPage'" in f for f in failures)


def test_check_invariants_sections_not_list():
    """Non-list 'sections' is flagged."""
    payload = {"app": {"id": 1, "name": "App"}, "configPage": {"sections": "bad"}}
    failures = aca.check_invariants(payload)
    assert any("configPage.sections is not a list" in f for f in failures)


def test_check_invariants_sections_missing():
    """Missing 'sections' key is flagged (None is not a list)."""
    payload = {"app": {"id": 1, "name": "App"}, "configPage": {}}
    failures = aca.check_invariants(payload)
    assert any("configPage.sections is not a list" in f for f in failures)


# ---------------------------------------------------------------------------
# check_invariants — section shape
# ---------------------------------------------------------------------------

def test_check_invariants_section_not_dict():
    """A section that is not a dict is flagged."""
    payload = _good_payload(sections=["not a dict"])
    failures = aca.check_invariants(payload)
    assert any("sections[0] is str" in f for f in failures)


def test_check_invariants_section_with_valid_inputs():
    """Section with well-formed inputs passes."""
    section = {
        "input": [{"name": "myInput", "type": "capability.switch"}]
    }
    payload = _good_payload(sections=[section])
    assert aca.check_invariants(payload) == []


def test_check_invariants_input_not_list():
    """section.input that is not a list is flagged."""
    section = {"input": "not a list"}
    payload = _good_payload(sections=[section])
    failures = aca.check_invariants(payload)
    assert any("sections[0].input is str" in f for f in failures)


def test_check_invariants_input_entry_not_dict():
    """An input entry that is not a dict is flagged."""
    section = {"input": ["not a dict"]}
    payload = _good_payload(sections=[section])
    failures = aca.check_invariants(payload)
    assert any("sections[0].input[0] is str" in f for f in failures)


def test_check_invariants_input_name_not_string():
    """input.name that is not a string is flagged."""
    section = {"input": [{"name": 42, "type": "text"}]}
    payload = _good_payload(sections=[section])
    failures = aca.check_invariants(payload)
    assert any("sections[0].input[0].name is not a string" in f for f in failures)


def test_check_invariants_input_type_not_string():
    """input.type that is not a string is flagged."""
    section = {"input": [{"name": "myInput", "type": None}]}
    payload = _good_payload(sections=[section])
    failures = aca.check_invariants(payload)
    assert any("sections[0].input[0].type is not a string" in f for f in failures)


def test_check_invariants_input_name_missing():
    """input.name absent (None from .get) is flagged."""
    section = {"input": [{"type": "text"}]}
    payload = _good_payload(sections=[section])
    failures = aca.check_invariants(payload)
    assert any(".name is not a string" in f for f in failures)


def test_check_invariants_input_type_missing():
    """input.type absent (None from .get) is flagged."""
    section = {"input": [{"name": "x"}]}
    payload = _good_payload(sections=[section])
    failures = aca.check_invariants(payload)
    assert any(".type is not a string" in f for f in failures)


def test_check_invariants_input_absent_is_ok():
    """Section with no 'input' key passes (input is optional)."""
    section = {"body": [{"text": "header"}]}
    payload = _good_payload(sections=[section])
    assert aca.check_invariants(payload) == []


# ---------------------------------------------------------------------------
# check_invariants — 'body' field
# ---------------------------------------------------------------------------

def test_check_invariants_body_not_list():
    """section.body that is not a list is flagged."""
    section = {"body": "not a list"}
    payload = _good_payload(sections=[section])
    failures = aca.check_invariants(payload)
    assert any("sections[0].body is str" in f for f in failures)


def test_check_invariants_body_list_is_ok():
    """section.body as a list passes."""
    section = {"body": [{"text": "header text"}]}
    payload = _good_payload(sections=[section])
    assert aca.check_invariants(payload) == []


def test_check_invariants_body_absent_is_ok():
    """Section with no 'body' key passes."""
    section = {}
    payload = _good_payload(sections=[section])
    assert aca.check_invariants(payload) == []


# ---------------------------------------------------------------------------
# check_invariants — 'settings' field
# ---------------------------------------------------------------------------

def test_check_invariants_settings_dict_is_ok():
    """Optional 'settings' dict passes."""
    payload = _good_payload()
    payload["settings"] = {"key": "value"}
    assert aca.check_invariants(payload) == []


def test_check_invariants_settings_absent_is_ok():
    """Absent 'settings' is fine (it's optional)."""
    payload = _good_payload()
    assert "settings" not in payload
    assert aca.check_invariants(payload) == []


def test_check_invariants_settings_not_dict():
    """'settings' that is not a dict is flagged."""
    payload = _good_payload()
    payload["settings"] = "bad"
    failures = aca.check_invariants(payload)
    assert any("'settings' is str" in f for f in failures)


def test_check_invariants_settings_list_flagged():
    """'settings' as a list is flagged."""
    payload = _good_payload()
    payload["settings"] = []
    failures = aca.check_invariants(payload)
    assert any("'settings' is list" in f for f in failures)


# ---------------------------------------------------------------------------
# check_invariants — multiple sections
# ---------------------------------------------------------------------------

def test_check_invariants_multi_section_both_valid():
    """Two valid sections with inputs both pass."""
    sections = [
        {"input": [{"name": "s1i1", "type": "text"}]},
        {"input": [{"name": "s2i1", "type": "bool"}]},
    ]
    payload = _good_payload(sections=sections)
    assert aca.check_invariants(payload) == []


def test_check_invariants_multi_section_second_broken():
    """Error in the second section is reported with correct index."""
    sections = [
        {"input": [{"name": "ok", "type": "text"}]},
        {"input": [{"name": 99, "type": "text"}]},  # name is int, not string
    ]
    payload = _good_payload(sections=sections)
    failures = aca.check_invariants(payload)
    assert any("sections[1].input[0].name is not a string" in f for f in failures)
    # First section must not be flagged
    assert not any("sections[0]" in f for f in failures)


# ---------------------------------------------------------------------------
# load_targets_from_config
# ---------------------------------------------------------------------------

def test_load_targets_from_config_basic(tmp_path):
    """Reads a minimal valid config and returns target tuples."""
    config = {
        "targets": [
            {"description": "Test App", "appId": 101, "pageName": None},
            {"description": "HPM Main", "appId": 202},
        ]
    }
    cfg_file = tmp_path / "audit_config.json"
    cfg_file.write_text(json.dumps(config))

    result = aca.load_targets_from_config(str(cfg_file))
    assert len(result) == 2
    assert result[0] == ("Test App", 101, None)
    assert result[1] == ("HPM Main", 202, None)


def test_load_targets_from_config_with_page_name(tmp_path):
    """pageName field is preserved when present."""
    config = {
        "targets": [
            {"description": "HPM Sub-page", "appId": 5, "pageName": "addApps"},
        ]
    }
    cfg_file = tmp_path / "audit_config.json"
    cfg_file.write_text(json.dumps(config))

    result = aca.load_targets_from_config(str(cfg_file))
    assert result[0] == ("HPM Sub-page", 5, "addApps")


def test_load_targets_from_config_empty_targets(tmp_path):
    """Empty targets list returns []."""
    cfg_file = tmp_path / "audit_config.json"
    cfg_file.write_text(json.dumps({"targets": []}))

    result = aca.load_targets_from_config(str(cfg_file))
    assert result == []


def test_load_targets_from_config_app_id_coerced_to_int(tmp_path):
    """appId is coerced to int even when stored as a string in JSON."""
    config = {"targets": [{"description": "X", "appId": "77"}]}
    cfg_file = tmp_path / "audit_config.json"
    cfg_file.write_text(json.dumps(config))

    result = aca.load_targets_from_config(str(cfg_file))
    assert isinstance(result[0][1], int)
    assert result[0][1] == 77
