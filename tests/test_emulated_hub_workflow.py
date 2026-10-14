"""Keep the public software lane independent of hub labels and credentials."""

import importlib.util
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW_PATH = ROOT / ".github/workflows/emulated-hub.yml"
WORKFLOW_TEXT = WORKFLOW_PATH.read_text(encoding="utf-8")
# BaseLoader preserves GitHub's `on` key instead of YAML 1.1's boolean coercion.
WORKFLOW = yaml.load(WORKFLOW_TEXT, Loader=yaml.BaseLoader)
SPEC = importlib.util.spec_from_file_location(
    "verify_emulated_hub_results", ROOT / ".github/scripts/verify_emulated_hub_results.py"
)
RESULTS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RESULTS)


def test_public_lane_triggers_for_each_pr_revision_without_filters():
    triggers = WORKFLOW["on"]
    assert set(triggers) == {"pull_request", "workflow_dispatch", "push"}
    assert triggers["pull_request"] == {"types": ["opened", "synchronize", "reopened"]}
    assert "main" in triggers["push"]["branches"]
    assert "ci/**" in triggers["push"]["branches"]
    assert "labels" not in WORKFLOW_TEXT
    assert "e2e:skip" not in WORKFLOW_TEXT
    assert "paths" not in triggers["push"]


def test_public_lane_has_no_privileged_checkout_or_hub_configuration():
    assert WORKFLOW["permissions"] == {"contents": "read"}
    assert "secrets." not in WORKFLOW_TEXT
    assert "MCP_URL" not in WORKFLOW_TEXT
    assert "WATCHDOG" not in WORKFLOW_TEXT
    assert "pull_request_target" not in WORKFLOW["on"]
    assert "workflow_run" not in WORKFLOW["on"]
    assert "repository_dispatch" not in WORKFLOW["on"]
    for job in WORKFLOW["jobs"].values():
        assert "if" not in job
        assert "environment" not in job
        assert "permissions" not in job
        assert "continue-on-error" not in job
        assert job["runs-on"] == "ubuntu-latest"
        checkout = next(step for step in job["steps"] if step.get("uses", "").startswith("actions/checkout@"))
        assert checkout["with"] == {"persist-credentials": "false"}
        for step in job["steps"]:
            assert "continue-on-error" not in step
            if "if" in step:
                assert step["if"] == "always()"
                assert step["uses"].startswith("actions/upload-artifact@")


def test_public_lane_runs_suite_and_verifies_reports_before_success():
    assert set(WORKFLOW["jobs"]) == {"emulated-hub"}
    assert "needs" not in WORKFLOW["jobs"]["emulated-hub"]
    steps = WORKFLOW["jobs"]["emulated-hub"]["steps"]
    suite = next(step for step in steps if step.get("run", "").startswith("./gradlew"))
    assert suite["run"] == "./gradlew -p ci/emulated-hub clean test --no-daemon --console=plain"
    verify = next(step for step in steps if "verify_emulated_hub_results.py" in step.get("run", ""))
    assert "ci/emulated-hub/build/test-results/test" in verify["run"]
    assert "--suite server.EmulatedHubHttpSpec" in verify["run"]
    assert "--min-tests 5" in verify["run"]
    assert steps.index(suite) < steps.index(verify)
    artifact = next(step for step in steps if step.get("uses", "").startswith("actions/upload-artifact@"))
    assert artifact["if"] == "always()"
    assert artifact["with"]["if-no-files-found"] == "error"
    assert "ci/emulated-hub/build/test-results/test/" in artifact["with"]["path"]
    assert "ci/emulated-hub/build/reports/problems/" in artifact["with"]["path"]
    assert "ci/emulated-hub/build/scenario-*.log" in artifact["with"]["path"]


def write_report(tmp_path, body):
    (tmp_path / "TEST-server.EmulatedHubHttpSpec.xml").write_text(body, encoding="utf-8")
    return tmp_path


def test_result_verifier_accepts_real_passing_scenarios(tmp_path):
    directory = write_report(tmp_path, '<testsuite name="server.EmulatedHubHttpSpec" tests="5" failures="0" errors="0" skipped="0"><testcase name="flat"/><testcase name="gateway"/><testcase name="isolation"/><testcase name="strict APIs and timers"/><testcase name="shutdown"/></testsuite>')
    assert RESULTS.validate_reports(directory, "server.EmulatedHubHttpSpec", minimum_tests=5) == 5


def test_result_verifier_fails_when_no_reports_exist(tmp_path):
    with pytest.raises(ValueError, match="No JUnit test reports"):
        RESULTS.validate_reports(tmp_path, "server.EmulatedHubHttpSpec")


@pytest.mark.parametrize("tag", ["failure", "error", "skipped"])
def test_result_verifier_rejects_nonpassing_scenarios(tmp_path, tag):
    directory = write_report(tmp_path, f'<testsuite name="server.EmulatedHubHttpSpec"><testcase name="CRUD"><{tag}/></testcase></testsuite>')
    with pytest.raises(ValueError, match="Scenario did not pass"):
        RESULTS.validate_reports(directory, "server.EmulatedHubHttpSpec")


@pytest.mark.parametrize(("body", "message"), [
    ("<testsuite>", "Malformed JUnit report"),
    ("<other/>", "Missing test suite"),
    ('<testsuite name="server.EmulatedHubHttpSpec"/>', "Empty test suite"),
    ('<testsuite name="other"><testcase name="pass"/></testsuite>', "Required integration suite"),
])
def test_result_verifier_rejects_incomplete_reports(tmp_path, body, message):
    directory = write_report(tmp_path, body)
    with pytest.raises(ValueError, match=message):
        RESULTS.validate_reports(directory, "server.EmulatedHubHttpSpec")


@pytest.mark.parametrize(("attribute", "value"), [
    ("tests", "0"), ("tests", "2"), ("tests", "bogus"),
    ("failures", "1"), ("errors", "1"), ("skipped", "1"),
])
def test_result_verifier_rejects_inconsistent_report_counts(tmp_path, attribute, value):
    directory = write_report(tmp_path, f'<testsuite name="server.EmulatedHubHttpSpec" {attribute}="{value}"><testcase name="CRUD"/></testsuite>')
    with pytest.raises(ValueError, match="count"):
        RESULTS.validate_reports(directory, "server.EmulatedHubHttpSpec")


def test_result_verifier_requires_all_planned_scenarios(tmp_path):
    directory = write_report(tmp_path, '<testsuite name="server.EmulatedHubHttpSpec"><testcase name="CRUD"/></testsuite>')
    (tmp_path / "TEST-other.xml").write_text('<testsuite name="other"><testcase name="one"/><testcase name="two"/><testcase name="three"/><testcase name="four"/></testsuite>', encoding="utf-8")
    with pytest.raises(ValueError, match="expected at least 5"):
        RESULTS.validate_reports(directory, "server.EmulatedHubHttpSpec", minimum_tests=5)


def test_result_verifier_requires_shutdown_scenario(tmp_path):
    directory = write_report(tmp_path, '<testsuite name="server.EmulatedHubHttpSpec"><testcase name="flat"/><testcase name="gateway"/><testcase name="isolation"/><testcase name="strict APIs and timers"/></testsuite>')
    with pytest.raises(ValueError, match="executed 4 scenarios; expected at least 5"):
        RESULTS.validate_reports(directory, "server.EmulatedHubHttpSpec", minimum_tests=5)


@pytest.mark.parametrize("minimum", [0, -1])
def test_result_verifier_rejects_invalid_minimum(tmp_path, minimum):
    with pytest.raises(ValueError, match="must be positive"):
        RESULTS.validate_reports(tmp_path, "server.EmulatedHubHttpSpec", minimum_tests=minimum)
