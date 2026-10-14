"""Public Software E2E status consumption never deploys or exposes external diagnostics."""

import http.client
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


def http_error(code, headers=None):
    return urllib.error.HTTPError("https://external.invalid", code, "UNTRUSTED", headers, None)


def assert_no_api_text(captured):
    assert "UNTRUSTED" not in captured.out + captured.err
    assert "external.invalid" not in captured.out + captured.err


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
    group = WORKFLOW["concurrency"]["group"]
    for key in ("github.event_name", "github.sha", "github.event.pull_request.number"):
        assert key in group
    assert WORKFLOW["concurrency"]["cancel-in-progress"] == "${{ github.event_name != 'workflow_dispatch' }}"


def test_pr_selection_uses_exact_head_instead_of_merge_revision_and_ignores_labels():
    source = event()
    assert RESULTS.select_revision("pull_request", source, OLD_SHA) == SHA
    source["pull_request"]["labels"] = [{"name": "arbitrary"}]
    assert RESULTS.select_revision("pull_request", source, OLD_SHA) == SHA


@pytest.mark.parametrize("change", ["fork", "other_author", "no_author"])
def test_unsupported_pr_sources_are_explicitly_ineligible(change):
    source = event()
    if change == "fork":
        source["pull_request"]["head"]["repo"]["full_name"] = "other/fork"
    elif change == "other_author":
        source["pull_request"]["user"]["login"] = "other"
    else:
        source["pull_request"]["user"] = None
    assert RESULTS.select_revision("pull_request", source, OLD_SHA) is None


def test_main_and_manual_events_select_current_revision():
    source = {"repository": {"full_name": RESULTS.REPOSITORY}, "ref": "refs/heads/main", "after": SHA}
    assert RESULTS.select_revision("push", source, SHA) == SHA
    assert RESULTS.select_revision("workflow_dispatch", source, SHA) == SHA
    source["ref"] = "refs/heads/other"
    assert RESULTS.select_revision("push", source, SHA) is None


def test_push_whose_after_differs_from_the_run_revision_is_refused():
    source = {"repository": {"full_name": RESULTS.REPOSITORY}, "ref": "refs/heads/main", "after": OLD_SHA}
    with pytest.raises(ValueError):
        RESULTS.select_revision("push", source, SHA)


@pytest.mark.parametrize("repository", [{"full_name": "other/repo"}, None, {}])
def test_event_from_another_repository_is_ineligible(repository):
    source = event()
    source["repository"] = repository
    assert RESULTS.select_revision("pull_request", source, OLD_SHA) is None
    assert RESULTS.select_revision("workflow_dispatch", source, SHA) is None


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
def test_terminal_result_states_are_fixed(state, expected):
    assert RESULTS.result_state(payload(state), SHA) == expected


def test_other_revision_is_unavailable_and_other_context_never_establishes_success():
    assert RESULTS.result_state(payload("success", OLD_SHA), SHA) == RESULTS.UNAVAILABLE
    assert RESULTS.wait_for_result(SHA, lambda: payload("success", OLD_SHA), timeout=2, interval=1,
                                   sleep=lambda seconds: pytest.fail("a mismatched revision must not wait")) == RESULTS.UNAVAILABLE
    source = payload("success")
    source["statuses"][0]["context"] = "other"
    assert RESULTS.result_state(source, SHA) == RESULTS.WAITING


@pytest.mark.parametrize("statuses", [None, {"context": "Software E2E"}, ["success"],
                                      [payload("success")["statuses"][0], None]])
def test_malformed_statuses_are_unavailable(statuses):
    assert RESULTS.result_state({"sha": SHA, "statuses": statuses}, SHA) == RESULTS.UNAVAILABLE


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


TRANSIENT = [OSError("UNTRUSTED PATH TOKEN URL"),
             urllib.error.URLError("UNTRUSTED PATH TOKEN URL"),
             http_error(503),
             http.client.IncompleteRead(b"UNTRUSTED partial"),
             json.JSONDecodeError("UNTRUSTED", "UNTRUSTED body", 0)]


@pytest.mark.parametrize("error", TRANSIENT)
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
    captured = capsys.readouterr()
    assert captured.out == ""
    assert f": {RESULTS.describe(error)}, retrying" in captured.err
    assert "(transient failure 1)" in captured.err and "(transient failure 2)" in captured.err
    assert_no_api_text(captured)


@pytest.mark.parametrize("error", TRANSIENT)
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
    captured = capsys.readouterr()
    assert "(transient failure 1)" in captured.err
    assert_no_api_text(captured)


@pytest.mark.parametrize(("code", "headers", "expected_sleep"), [
    (429, {}, 30),
    (429, {"retry-after": "120"}, 120),
    (403, {"retry-after": "90"}, 90),
    (403, {"x-ratelimit-remaining": "0", "x-ratelimit-reset": "1300"}, 300),
])
def test_rate_limit_waits_for_reset_then_reads_again(code, headers, expected_sleep, capsys):
    clock = [0]
    sleeps = []
    def sleep(seconds):
        sleeps.append(seconds)
        clock[0] += seconds
    def fetch():
        if not sleeps:
            raise http_error(code, headers)
        return payload("success")
    assert RESULTS.wait_for_result(SHA, fetch, interval=30, monotonic=lambda: clock[0], sleep=sleep,
                                   now=lambda: 1000) == RESULTS.PASSED
    assert sleeps == [expected_sleep]
    captured = capsys.readouterr()
    assert f"HTTPError {code} rate limited, retrying in {expected_sleep} s (transient failure 1)." in captured.err
    assert_no_api_text(captured)


def test_rate_limit_wait_is_bounded_by_the_deadline():
    clock = [0]
    def fetch():
        raise http_error(429, {"retry-after": "9999"})
    def sleep(seconds):
        clock[0] += seconds
    assert RESULTS.wait_for_result(SHA, fetch, timeout=100, interval=30, monotonic=lambda: clock[0],
                                   sleep=sleep) == RESULTS.UNAVAILABLE
    assert clock[0] == 100


@pytest.mark.parametrize(("error", "logged"), [
    (http_error(403, {"x-ratelimit-remaining": "12"}), "HTTPError 403"),
    (http_error(404), "HTTPError 404"),
    (ValueError("UNTRUSTED response"), "ValueError"),
])
def test_permanent_fetch_failure_is_unavailable_without_retry(error, logged, capsys):
    def fetch():
        raise error
    assert RESULTS.wait_for_result(SHA, fetch, timeout=2,
                                   sleep=lambda seconds: pytest.fail("permanent failure must not retry")) == RESULTS.UNAVAILABLE
    captured = capsys.readouterr()
    assert captured.err == f"Software E2E status read: {logged}, giving up.\n"
    assert_no_api_text(captured)


def test_status_request_uses_only_fixed_public_endpoint_and_rejects_redirects(monkeypatch):
    calls = []
    class Opener:
        def open(self, request, timeout):
            calls.append((request, timeout))
            return io.BytesIO(json.dumps(payload("success")).encode())
    def build_opener(*handlers):
        assert len(handlers) == 1 and isinstance(handlers[0], RESULTS.NoRedirect)
        return Opener()
    monkeypatch.setattr(RESULTS.urllib.request, "build_opener", build_opener)
    assert RESULTS.fetch_status(SHA, "read-only-token") == payload("success")
    assert calls[0][0].full_url == f"https://api.github.com/repos/{RESULTS.REPOSITORY}/commits/{SHA}/status"
    assert calls[0][0].get_method() == "GET"
    assert calls[0][0].get_header("Authorization") == "Bearer read-only-token"
    assert calls[0][1] == 30
    assert RESULTS.NoRedirect().redirect_request(None, None, 302, "redirect", {}, "https://external.invalid") is None


@pytest.mark.parametrize("token", ["token\nInjected: header", "token value", "", None])
def test_malformed_token_is_refused_before_any_request(token, monkeypatch):
    monkeypatch.setattr(RESULTS.urllib.request, "build_opener", lambda *handlers: pytest.fail("no request may be built"))
    with pytest.raises(ValueError):
        RESULTS.fetch_status(SHA, token)


def test_cli_outputs_only_fixed_result_text(monkeypatch, capsys):
    monkeypatch.setattr(RESULTS, "fetch_status", lambda sha, token: payload("success"))
    monkeypatch.setattr("sys.argv", ["software_e2e_result.py", "--sha", SHA, "--timeout-seconds", "1"])
    assert RESULTS.main() == 0
    assert capsys.readouterr() == (RESULTS.MESSAGES[RESULTS.PASSED] + "\n", "")


def reject_404(sha, token):
    raise http_error(404)


@pytest.mark.parametrize(("fetch", "outcome", "logged"), [
    (lambda sha, token: payload("failure"), RESULTS.FAILED, ""),
    (reject_404, RESULTS.UNAVAILABLE, "HTTPError 404, giving up."),
    (None, RESULTS.UNAVAILABLE, "ValueError, giving up."),
])
def test_cli_fails_for_failed_unavailable_and_missing_token(fetch, outcome, logged, monkeypatch, capsys):
    if fetch is None:
        monkeypatch.delenv("GITHUB_TOKEN", raising=False)
        monkeypatch.setattr(RESULTS.urllib.request, "build_opener", lambda *handlers: pytest.fail("no request without a token"))
    else:
        monkeypatch.setenv("GITHUB_TOKEN", "read-only-token")
        monkeypatch.setattr(RESULTS, "fetch_status", fetch)
    monkeypatch.setattr("sys.argv", ["software_e2e_result.py", "--sha", SHA, "--timeout-seconds", "1"])
    assert RESULTS.main() == 1
    captured = capsys.readouterr()
    assert captured.out == RESULTS.MESSAGES[outcome] + "\n"
    assert logged in captured.err
    assert_no_api_text(captured)


@pytest.mark.parametrize(("login", "expected"), [
    ("kingpanther13", f"eligible=true\nsha={SHA}\n"),
    ("other", "eligible=false\nsha=\n"),
])
def test_cli_writes_exact_eligibility_outputs(login, expected, tmp_path, monkeypatch, capsys):
    source = event()
    source["pull_request"]["user"]["login"] = login
    event_file, output = tmp_path / "event.json", tmp_path / "output"
    event_file.write_text(json.dumps(source), encoding="utf-8")
    output.write_text("existing=kept\n", encoding="utf-8")
    monkeypatch.setattr("sys.argv", ["software_e2e_result.py", "--event-file", str(event_file), "--event-name",
                                     "pull_request", "--fallback-sha", OLD_SHA, "--output", str(output)])
    assert RESULTS.main() == 0
    assert output.read_text(encoding="utf-8") == "existing=kept\n" + expected
    assert capsys.readouterr().err == ""


def test_cli_reports_malformed_event_class_without_writing_outputs(tmp_path, monkeypatch, capsys):
    event_file, output = tmp_path / "event.json", tmp_path / "output"
    event_file.write_text("{UNTRUSTED", encoding="utf-8")
    monkeypatch.setattr("sys.argv", ["software_e2e_result.py", "--event-file", str(event_file), "--event-name",
                                     "pull_request", "--fallback-sha", SHA, "--output", str(output)])
    assert RESULTS.main() == 1
    assert not output.exists()
    captured = capsys.readouterr()
    assert captured.err == "Software E2E check failed: JSONDecodeError\n"
    assert_no_api_text(captured)
