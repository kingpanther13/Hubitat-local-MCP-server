"""Run the fast-CI gate script against a fake gh that serves canned GitHub API responses."""
import base64
import json
import os
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
REPO = "base/server"
FORK = {"full_name": "fork/server", "owner": {"login": "fork"}}
SHA = "a" * 40
HEAD = {"ref": "feature", "sha": SHA, "repo": FORK}
PR = {"number": 7, "state": "open", "head": HEAD, "base": {"repo": {"full_name": REPO}}, "changed_files": 1}
MOVED = {**PR, "head": {**HEAD, "sha": "b" * 40}}
REQUIRED = ["unit-tests", "pr-guard", "groovy2x-spock"]
FILES = "pulls/7/files?per_page=100"


def run_row(name, job_id, *, trigger="pull_request", status="completed", conclusion="success"):
    return {"id": job_id, "path": f".github/workflows/{name}.yml", "run_number": 3,
            "run_attempt": 1, "event": trigger, "head_sha": SHA, "head_branch": HEAD["ref"],
            "head_repository": FORK, "pull_requests": [], "status": status, "conclusion": conclusion}


def endpoint(event):
    return f"actions/runs?head_sha={SHA}&event={event}&per_page=100"


@pytest.fixture
def github(tmp_path):
    gh = tmp_path / "gh"
    # A {"__sequence__": [...]} entry answers each read with the next item, repeating the last.
    # fail-once fails one read of a path with a retryable 502, refuse-once with a 403.
    gh.write_text(f"#!{sys.executable}\n" + """
import json, os, sys
from pathlib import Path
root = Path(os.environ["FAKE_GITHUB"])
path = sys.argv[-1].removeprefix("repos/base/server/")
for name, error in (("fail-once", "Server Error (HTTP 502)"), ("refuse-once", "API rate limit exceeded (HTTP 403)")):
    fault = root / name
    if fault.exists() and fault.read_text() == path:
        fault.unlink()
        print("gh: " + error, file=sys.stderr)
        sys.exit(1)
api = json.loads((root / "api").read_text())
value = api[path]
if isinstance(value, dict) and "__sequence__" in value:
    items = value["__sequence__"]
    value = items.pop(0) if len(items) > 1 else items[0]
    (root / "api").write_text(json.dumps(api))
print(json.dumps(value))
""")
    gh.chmod(0o755)
    # libraries/a.groovy makes groovy24-parse and sandbox-lint (rows 4 and 5) applicable.
    rows = [run_row(name, i) for i, name in enumerate([*REQUIRED, "groovy24-parse", "sandbox-lint"], 1)]
    api = {"pulls/7": PR, FILES: [[{"filename": "libraries/a.groovy", "status": "modified"}]],
           endpoint("pull_request"): [{"workflow_runs": rows}]}

    def execute(payload, wait=0):
        (tmp_path / "api").write_text(json.dumps(api))
        (tmp_path / "event").write_text(json.dumps(payload))
        for name in ("outputs", "summary"):
            (tmp_path / name).write_text("")
        env = {**os.environ, "PATH": str(tmp_path) + os.pathsep + os.environ["PATH"], "FAKE_GITHUB": str(tmp_path),
               "GITHUB_REPOSITORY": REPO, "GITHUB_EVENT_PATH": str(tmp_path / "event"), "GITHUB_SHA": SHA,
               "GITHUB_OUTPUT": str(tmp_path / "outputs"), "GITHUB_STEP_SUMMARY": str(tmp_path / "summary"),
               "FAST_CI_WAIT_SECONDS": str(wait), "FAST_CI_POLL_SECONDS": "0"}
        done = subprocess.run(["ruby", ".github/scripts/ephemeral_fast_ci.rb"], cwd=ROOT, env=env, check=True,
                              capture_output=True, text=True)
        (tmp_path / "log").write_text(done.stdout + done.stderr)
        return (tmp_path / "outputs").read_text().splitlines()[-1]
    return api, rows, execute


def change_files(api, files):
    api[FILES] = [files]
    api["pulls/7"] = {**PR, "changed_files": len(files)}


@pytest.mark.parametrize("case,expected", [
    ("green", "ready"), ("missing-match", "not-started"), ("other-fork", "not-started"),
    ("older-green", "not-started"), ("cancelled", "not-started"), ("queued", "not-started"),
    ("stale", "superseded"), ("rename", "not-started"), ("scheduled-red", "not-started"),
    ("filter-narrowed", "ready"), ("filter-removed", "ready"), ("file-301", "not-started"),
])
def test_pr_gate(github, tmp_path, case, expected):
    api, rows, execute = github
    parse = rows[3]
    if case == "missing-match":
        rows.remove(parse)
    elif case == "other-fork":
        parse["head_repository"] = {"full_name": "another/fork"}
    elif case == "older-green":
        rows.append({**parse, "run_attempt": 2, "conclusion": "failure"})
    elif case == "cancelled":
        parse["conclusion"] = "cancelled"
    elif case == "queued":
        parse.update(status="queued", conclusion=None)
    elif case == "stale":
        api["pulls/7"] = MOVED
    elif case == "rename":
        # The old path makes python-tests and ruff applicable; neither ran.
        api[FILES][0][0]["previous_filename"] = "scripts/old.py"
    elif case == "scheduled-red":
        # GitHub ran a lane the path emulation did not predict, and it failed.
        rows.append(run_row("python-tests", 20, conclusion="failure"))
    elif case in ("filter-narrowed", "filter-removed"):
        # Python Tests watches every workflow file, so it runs for either edit.
        rows.append(run_row("python-tests", 20))
        lane = "lane-gate-test" if case == "filter-narrowed" else "lease-scripts-test"
        status = "modified" if case == "filter-narrowed" else "removed"
        change_files(api, [api[FILES][0][0], {"filename": f".github/workflows/{lane}.yml", "status": status}])
        narrowed = "name: lane-gate-test\non:\n  pull_request:\n    paths: ['nothing/**']\n"
        api[f"contents/.github/workflows/lane-gate-test.yml?ref={SHA}"] = {"content": base64.b64encode(narrowed.encode()).decode()}
    elif case == "file-301":
        # GitHub evaluates up to 3,000 files, so python-tests and ruff apply; neither ran.
        change_files(api, [{"filename": f"libraries/f{i}.groovy", "status": "modified"} for i in range(300)]
                     + [{"filename": "tests/test_x.py", "status": "added"}])
    assert execute({"pull_request": PR}) == f"result={expected}"
    # The request run relays this line to the test run; no other outcome writes one.
    reasons = [line for line in (tmp_path / "outputs").read_text().splitlines() if line.startswith("reason=")]
    assert len(reasons) == (expected == "not-started")
    assert all(line.startswith("reason=Ephemeral hubs not started: fast CI ") for line in reasons)


def with_first(rows, **change):
    return [{"workflow_runs": [{**rows[0], **change}, *rows[1:]]}]


@pytest.mark.parametrize("case,expected,reported", [
    ("turns-green", "ready", ""),
    ("turns-red", "not-started", "fast CI did not pass: unit-tests"),
    ("head-moves-while-waiting", "superseded", ""),
    ("head-moves-after-green", "superseded", ""),
    ("times-out", "not-started", "fast CI not finished after 0 minutes: unit-tests"),
    ("red-while-another-runs", "not-started", "fast CI did not pass: pr-guard"),
    ("approved-then-green", "ready", ""),
    ("never-approved", "not-started", "unit-tests (awaiting workflow approval)"),
    ("read-refused-once", "ready", ""),
])
def test_gate_waits_for_running_checks(github, tmp_path, case, expected, reported):
    api, rows, execute = github
    runs = endpoint("pull_request")
    running = with_first(rows, status="in_progress", conclusion=None)
    held = with_first(rows, conclusion="action_required")
    done = [{"workflow_runs": rows}]
    wait = 60
    if case == "turns-green":
        api[runs] = {"__sequence__": [running, running, done]}
    elif case == "turns-red":
        api[runs] = {"__sequence__": [running, running, with_first(rows, conclusion="failure")]}
    elif case == "head-moves-while-waiting":
        api[runs] = running
        api["pulls/7"] = {"__sequence__": [PR, PR, MOVED]}
    elif case == "head-moves-after-green":
        api["pulls/7"] = {"__sequence__": [PR, MOVED]}
    elif case == "times-out":
        # One second of real polling, then the deadline ends the wait.
        api[runs], wait = running, 1
    elif case == "red-while-another-runs":
        api[runs] = [{"workflow_runs": [{**rows[0], "status": "in_progress", "conclusion": None},
                                        {**rows[1], "conclusion": "failure"}, *rows[2:]]}]
    elif case == "approved-then-green":
        api[runs] = {"__sequence__": [held, held, done]}
    elif case == "never-approved":
        api[runs], wait = held, 0
    elif case == "read-refused-once":
        (tmp_path / "refuse-once").write_text(runs)
    assert execute({"pull_request": PR}, wait=wait) == f"result={expected}"
    assert reported in (tmp_path / "summary").read_text()
    assert bool(reported) == bool((tmp_path / "summary").read_text())
    log = (tmp_path / "log").read_text()
    if case in ("turns-green", "turns-red", "head-moves-while-waiting", "times-out"):
        assert "Waiting for unit-tests" in log
    elif case == "approved-then-green":
        assert "Waiting for unit-tests (awaiting workflow approval)" in log
    elif case == "read-refused-once":
        assert "retrying at the next poll" in log
    else:
        assert "Waiting for" not in log


def test_a_refused_read_at_the_deadline_fails_the_gate(github, tmp_path):
    _, _, execute = github
    (tmp_path / "refuse-once").write_text(endpoint("pull_request"))
    with pytest.raises(subprocess.CalledProcessError):
        execute({"pull_request": PR})


def test_incomplete_file_list_fails_the_gate(github):
    api, _, execute = github
    api["pulls/7"] = {**PR, "changed_files": 2}
    with pytest.raises(subprocess.CalledProcessError):
        execute({"pull_request": PR})


def test_transient_workflow_lookup_on_a_green_revision(github, tmp_path):
    _, _, execute = github
    (tmp_path / "fail-once").write_text(endpoint("pull_request"))
    assert execute({"pull_request": PR}) == "result=ready"
    assert "retrying at the next poll" not in (tmp_path / "log").read_text()


def test_main_push_waits_for_its_own_unit_run(github):
    api, rows, execute = github
    repo = {"full_name": REPO}
    unit = {**rows[0], "event": "push", "head_repository": repo, "head_branch": "main"}
    api["git/ref/heads/main"] = {"object": {"sha": SHA}}
    running = [{"workflow_runs": [{**unit, "status": "in_progress", "conclusion": None}]}]
    api[endpoint("push")] = {"__sequence__": [running, [{"workflow_runs": [unit]}]]}
    assert execute({"repository": repo}, wait=60) == "result=ready"
    api[endpoint("push")] = [{"workflow_runs": [{**unit, "conclusion": "failure"}]}]
    assert execute({"repository": repo}) == "result=not-started"
    api["git/ref/heads/main"] = {"object": {"sha": "b" * 40}}
    assert execute({"repository": repo}) == "result=superseded"


def test_gate_can_read_every_fast_workflow_and_outlasts_its_wait():
    # A missing workflow file or an unreadable filter would fail every PR's gate, and a job
    # timeout at or below the wait would kill the gate before it reports why no hub started.
    subprocess.run(["ruby", "-r", "./.github/scripts/ephemeral_fast_ci", "-e", """
      EphemeralFastCI::REQUIRED.each { |name| File.read(EphemeralFastCI.workflow_path(name)) }
      EphemeralFastCI::FILTERED.each do |name|
        path = EphemeralFastCI.workflow_path(name)
        EphemeralFastCI.pull_request_paths(File.read(path), path) or abort "#{name} lost its pull_request trigger"
      end
      %w[hub-e2e-ephemeral hub-e2e-ephemeral-request].each do |name|
        limit = YAML.safe_load_file(EphemeralFastCI.workflow_path(name)).fetch('jobs').fetch('fast-ci').fetch('timeout-minutes')
        abort "#{name}: fast-ci timeout too close to the wait" unless limit * 60 >= EphemeralFastCI::WAIT_SECONDS + 240
      end
    """], cwd=ROOT, check=True)
