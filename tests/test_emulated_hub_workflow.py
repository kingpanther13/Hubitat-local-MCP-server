"""Public Software E2E status consumption never deploys or exposes external diagnostics."""

import importlib.util
import io
import json
import urllib.error
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW_TEXT = (ROOT / ".github/workflows/emulated-hub.yml").read_text(encoding="utf-8")
WORKFLOW = yaml.load(WORKFLOW_TEXT, Loader=yaml.BaseLoader)
SPEC = importlib.util.spec_from_file_location("software_e2e_result", ROOT / ".github/scripts/software_e2e_result.py")
RESULTS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RESULTS)
SHA = "a" * 40
OLD_SHA = "b" * 40


def event():
    return {"repository": {"full_name": RESULTS.REPOSITORY}, "pull_request": {
        "user": {"login": "kingpanther13"}, "head": {"sha": SHA, "repo": {"full_name": RESULTS.REPOSITORY}},
        "labels": [{"name": "e2e:skip"}],
    }}


def payload(state, sha=SHA):
    return {"sha": sha, "statuses": [{"context": "Software E2E", "state": state,
                                      "description": "UNTRUSTED DESCRIPTION", "target_url": "https://external.invalid/raw"}]}


def test_workflow_has_read_only_permissions_and_no_label_or_secret_gate():
    assert WORKFLOW["permissions"] == {"contents": "read", "statuses": "read"}
    assert set(WORKFLOW["on"]) == {"pull_request", "push", "workflow_dispatch"}
    assert WORKFLOW["on"]["pull_request"]["types"] == ["opened", "synchronize", "reopened"]
    assert WORKFLOW["on"]["push"]["branches"] == ["main"]
    assert "labels" not in WORKFLOW_TEXT and "e2e:skip" not in WORKFLOW_TEXT
    assert "secrets." not in WORKFLOW_TEXT
    assert "upload-artifact" not in WORKFLOW_TEXT
    assert "./gradlew" not in WORKFLOW_TEXT
    assert "continue-on-error" not in WORKFLOW_TEXT
    assert WORKFLOW["jobs"]["software-e2e"]["if"] == "needs.revision.outputs.eligible == 'true'"
    assert WORKFLOW["jobs"]["software-e2e"]["timeout-minutes"] == "135"
    assert "--timeout-seconds 7800" in WORKFLOW_TEXT
    for job in WORKFLOW["jobs"].values():
        checkout = next(step for step in job["steps"] if step.get("uses", "").startswith("actions/checkout@"))
        assert checkout["with"]["persist-credentials"] == "false"


def test_concurrency_preserves_latest_automatic_and_independent_manual_waits():
    assert WORKFLOW["concurrency"] == {
        "group": "software-e2e-${{ github.event_name }}-${{ github.event_name == 'workflow_dispatch' && github.sha || github.event.pull_request.number || github.ref }}",
        "cancel-in-progress": "${{ github.event_name != 'workflow_dispatch' }}",
    }


def test_pr_selection_uses_exact_head_instead_of_merge_revision_and_ignores_labels():
    source = event()
    assert RESULTS.select_revision("pull_request", source, OLD_SHA) == SHA
    source["pull_request"]["labels"] = [{"name": "arbitrary"}]
    assert RESULTS.select_revision("pull_request", source, OLD_SHA) == SHA


@pytest.mark.parametrize("change", ["fork", "other_author"])
def test_unsupported_pr_sources_are_explicitly_ineligible(change):
    source = event()
    if change == "fork":
        source["pull_request"]["head"]["repo"]["full_name"] = "other/fork"
    else:
        source["pull_request"]["user"]["login"] = "other"
    assert RESULTS.select_revision("pull_request", source, OLD_SHA) is None


def test_main_and_manual_events_select_current_revision():
    source = {"repository": {"full_name": RESULTS.REPOSITORY}, "ref": "refs/heads/main", "after": SHA}
    assert RESULTS.select_revision("push", source, SHA) == SHA
    assert RESULTS.select_revision("workflow_dispatch", source, SHA) == SHA
    source["ref"] = "refs/heads/other"
    assert RESULTS.select_revision("push", source, SHA) is None


@pytest.mark.parametrize("sha", ["a" * 39, "A" * 40, "../" + SHA, SHA + "\n"])
def test_invalid_sha_is_refused(sha):
    source = event()
    source["pull_request"]["head"]["sha"] = sha
    with pytest.raises(ValueError):
        RESULTS.select_revision("pull_request", source, OLD_SHA)


@pytest.mark.parametrize(("state", "expected"), [
    ("success", RESULTS.PASSED), ("failure", RESULTS.FAILED), ("error", RESULTS.FAILED),
    ("pending", RESULTS.WAITING), ("unknown", RESULTS.UNAVAILABLE),
])
def test_terminal_result_states_are_fixed_and_descriptions_are_ignored(state, expected):
    assert RESULTS.result_state(payload(state), SHA) == expected
    assert "UNTRUSTED" not in RESULTS.MESSAGES.get(expected, "")
    assert "external.invalid" not in RESULTS.MESSAGES.get(expected, "")


def test_old_sha_and_other_context_never_establish_success():
    assert RESULTS.result_state(payload("success", OLD_SHA), SHA) == RESULTS.WAITING
    source = payload("success")
    source["statuses"][0]["context"] = "other"
    assert RESULTS.result_state(source, SHA) == RESULTS.WAITING


def test_missing_or_ambiguous_status_cannot_pass():
    assert RESULTS.result_state({"sha": SHA, "statuses": []}, SHA) == RESULTS.WAITING
    source = payload("success")
    source["statuses"].append({"context": "Software E2E", "state": "failure"})
    assert RESULTS.result_state(source, SHA) == RESULTS.UNAVAILABLE


def test_pending_or_missing_status_has_a_bounded_unavailable_result():
    class Clock:
        now = 0
        def read(self):
            return self.now
        def sleep(self, seconds):
            self.now += seconds
    clock = Clock()
    outcome = RESULTS.wait_for_result(SHA, lambda: {"sha": SHA, "statuses": []}, timeout=2, interval=1,
                                      monotonic=clock.read, sleep=clock.sleep)
    assert outcome == RESULTS.UNAVAILABLE
    assert clock.now == 2


def test_terminal_failure_is_returned_without_sleeping_or_swallowing_it():
    assert RESULTS.wait_for_result(SHA, lambda: payload("failure"), timeout=2, interval=1,
                                   sleep=lambda seconds: pytest.fail("terminal failure must not sleep")) == RESULTS.FAILED


def test_missing_status_can_be_scheduled_after_ten_minutes_and_wait_is_still_bounded():
    clock = [0]
    def sleep(seconds):
        clock[0] += seconds
    def fetch():
        return payload("success") if clock[0] >= 900 else {"sha": SHA, "statuses": []}
    assert RESULTS.wait_for_result(SHA, fetch, interval=30, monotonic=lambda: clock[0], sleep=sleep) == RESULTS.PASSED
    assert clock[0] == 900
    clock[0] = 0
    assert RESULTS.wait_for_result(SHA, lambda: payload("pending"), interval=30,
                                   monotonic=lambda: clock[0], sleep=sleep) == RESULTS.UNAVAILABLE
    assert clock[0] == 7800


@pytest.mark.parametrize("error", [OSError("UNTRUSTED PATH TOKEN URL"),
                                  urllib.error.HTTPError("https://external.invalid", 503, "UNTRUSTED", {}, None)])
def test_network_failure_retries_until_bounded_unavailable_result(error, capsys):
    clock = [0]
    attempts = []
    def sleep(seconds):
        clock[0] += seconds
    def fail():
        attempts.append(clock[0])
        raise error
    assert RESULTS.wait_for_result(SHA, fail, timeout=2, interval=1,
                                   monotonic=lambda: clock[0], sleep=sleep) == RESULTS.UNAVAILABLE
    assert attempts == [0, 1]
    assert clock[0] == 2
    assert capsys.readouterr() == ("", "")


@pytest.mark.parametrize("error", [OSError("UNTRUSTED PATH TOKEN URL"),
                                  urllib.error.URLError("UNTRUSTED PATH TOKEN URL"),
                                  urllib.error.HTTPError("https://external.invalid", 503, "UNTRUSTED", {}, None)])
def test_transient_failure_recovers_to_exact_revision_result(error, capsys):
    clock = [0]
    attempts = []
    def sleep(seconds):
        clock[0] += seconds
    def fetch():
        attempts.append(clock[0])
        if len(attempts) == 1:
            raise error
        return payload("success")
    assert RESULTS.wait_for_result(SHA, fetch, timeout=2, interval=1,
                                   monotonic=lambda: clock[0], sleep=sleep) == RESULTS.PASSED
    assert attempts == [0, 1]
    assert capsys.readouterr() == ("", "")


@pytest.mark.parametrize("error", [urllib.error.HTTPError("https://external.invalid", 403, "UNTRUSTED", {}, None),
                                  ValueError("UNTRUSTED response")])
def test_permanent_fetch_failure_is_unavailable_without_retry(error, capsys):
    def fetch():
        raise error
    assert RESULTS.wait_for_result(SHA, fetch, timeout=2,
                                   sleep=lambda seconds: pytest.fail("permanent failure must not retry")) == RESULTS.UNAVAILABLE
    assert capsys.readouterr() == ("", "")


def test_status_request_uses_only_fixed_public_endpoint_and_rejects_redirects(monkeypatch):
    calls = []
    class Opener:
        def open(self, request, timeout):
            calls.append((request, timeout))
            return io.BytesIO(json.dumps(payload("success")).encode())
    monkeypatch.setattr(RESULTS.urllib.request, "build_opener", lambda handler: Opener())
    RESULTS.fetch_status(SHA, "read-only-token")
    assert calls[0][0].full_url == f"https://api.github.com/repos/{RESULTS.REPOSITORY}/commits/{SHA}/status"
    assert calls[0][0].get_method() == "GET"
    assert calls[0][1] == 30
    assert RESULTS.NoRedirect().redirect_request(None, None, 302, "redirect", {}, "https://external.invalid") is None


def test_cli_outputs_only_fixed_result_text(monkeypatch, capsys):
    monkeypatch.setattr(RESULTS, "fetch_status", lambda sha, token: payload("success"))
    monkeypatch.setattr("sys.argv", ["software_e2e_result.py", "--sha", SHA, "--timeout-seconds", "1"])
    assert RESULTS.main() == 0
    assert capsys.readouterr().out == RESULTS.MESSAGES[RESULTS.PASSED] + "\n"
