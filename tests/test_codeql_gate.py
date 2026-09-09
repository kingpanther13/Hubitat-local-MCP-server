"""The branch scan must not turn missing reports or failed analysis green."""

import importlib.util
import json
from pathlib import Path

import pytest

SPEC = importlib.util.spec_from_file_location(
    "codeql_gate", Path(__file__).resolve().parents[1] / ".github/scripts/codeql_gate.py"
)
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)


def report(results=None):
    return {
        "version": "2.1.0",
        "runs": [{"invocations": [{"executionSuccessful": True}], "results": results or []}],
    }


@pytest.mark.parametrize("findings,expected", [([], 0), ([{"ruleId": "py/test"}], 1)])
def test_cli_verdict(tmp_path, findings, expected):
    path = tmp_path / "scan.sarif"
    path.write_text(json.dumps(report(findings)), encoding="utf-8")
    assert gate.main([str(path)]) == expected


def test_multiple_runs_include_later_findings():
    data = report()
    data["runs"].extend(report([{"ruleId": "js/test"}])["runs"])
    assert gate.check_report(data) == 1


@pytest.mark.parametrize("data", [None, {}, {"version": "2.1.0", "runs": []}])
def test_missing_scan_evidence(data):
    with pytest.raises(ValueError):
        gate.check_report(data)


@pytest.mark.parametrize("field,value", [
    ("invocations", None), ("invocations", []),
    ("invocations", [{"executionSuccessful": False}]),
    ("invocations", [{"executionSuccessful": "true"}]),
    ("invocations", [{}]), ("results", None), ("results", {}),
])
def test_incomplete_or_failed_analysis(field, value):
    data = report()
    data["runs"][0][field] = value
    with pytest.raises(ValueError):
        gate.check_report(data)


def test_cli_missing_or_invalid_report(tmp_path):
    path = tmp_path / "scan.sarif"
    assert gate.main([str(path)]) == 2
    path.write_text("invalid json", encoding="utf-8")
    assert gate.main([str(path)]) == 2
    assert gate.main([]) == 2
