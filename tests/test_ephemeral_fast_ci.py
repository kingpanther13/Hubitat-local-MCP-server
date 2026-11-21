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
    gh.write_text(f"#!{sys.executable}\n" + """
import json, os, sys
from pathlib import Path
root = Path(os.environ["FAKE_GITHUB"])
path = sys.argv[-1].removeprefix("repos/base/server/")
fault = root / "fail-once"
if fault.exists() and fault.read_text() == path:
    fault.unlink()
    print("gh: Server Error (HTTP 502)", file=sys.stderr)
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
        env = {**os.environ, "PATH": str(tmp_path) + os.pathsep + os.environ["PATH"], "FAKE_GITHUB": str(tmp_path),
               "GITHUB_REPOSITORY": REPO, "GITHUB_EVENT_PATH": str(tmp_path / "event"), "GITHUB_SHA": SHA,
               "GITHUB_OUTPUT": str(tmp_path / "outputs"), "GITHUB_STEP_SUMMARY": str(tmp_path / "summary"),
               "FAST_CI_WAIT_SECONDS": str(wait), "FAST_CI_POLL_SECONDS": "0"}
        subprocess.run(["ruby", ".github/scripts/ephemeral_fast_ci.rb"], cwd=ROOT, env=env, check=True)
        return (tmp_path / "outputs").read_text().splitlines()[-1]
    return api, rows, execute


def change_files(api, files):
    api[FILES] = [files]
    api["pulls/7"] = {**PR, "changed_files": len(files)}


@pytest.mark.parametrize("case,expected", [
    ("green", "ready"), ("missing-match", "deferred"), ("other-fork", "deferred"),
    ("older-green", "deferred"), ("cancelled", "deferred"), ("queued", "deferred"),
    ("stale", "superseded"), ("rename", "deferred"), ("scheduled-red", "deferred"),
    ("filter-narrowed", "ready"), ("filter-removed", "ready"), ("file-301", "deferred"),
])
def test_pr_gate(github, case, expected):
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
        api["pulls/7"] = {**PR, "head": {**HEAD, "sha": "b" * 40}}
    elif case == "rename":
        # The old path makes python-tests and ruff applicable; neither ran.
        api[FILES][0][0]["previous_filename"] = "scripts/old.py"
    elif case == "scheduled-red":
        # GitHub ran a lane the path emulation did not predict, and it failed.
        rows.append(run_row("python-tests", 20, conclusion="failure"))
    elif case in ("filter-narrowed", "filter-removed"):
        # Python Tests watches every fast workflow file, so it runs for either edit.
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


def in_progress(rows):
    return [{**row, "status": "in_progress", "conclusion": None} if row is rows[0] else row for row in rows]


@pytest.mark.parametrize("case,expected", [
    ("turns-green", "ready"), ("turns-red", "deferred"), ("head-moves", "superseded"), ("never-finishes", "deferred"),
])
def test_gate_waits_for_running_checks(github, tmp_path, case, expected):
    api, rows, execute = github
    waiting = [{"workflow_runs": in_progress(rows)}]
    done = [{"workflow_runs": rows}]
    red = [{"workflow_runs": [{**rows[0], "conclusion": "failure"}, *rows[1:]]}]
    api[endpoint("pull_request")] = {"__sequence__": [waiting, waiting, red if case == "turns-red" else done]}
    if case == "never-finishes":
        api[endpoint("pull_request")] = waiting
    if case == "head-moves":
        api["pulls/7"] = {"__sequence__": [PR, PR, {**PR, "head": {**HEAD, "sha": "b" * 40}}]}
    assert execute({"pull_request": PR}, wait=0 if case == "never-finishes" else 60) == f"result={expected}"
    summary = tmp_path / "summary"
    reported = summary.read_text() if summary.exists() else ""
    if case == "turns-red":
        assert "fast CI did not pass: unit-tests" in reported
    elif case == "never-finishes":
        assert "still waiting for unit-tests" in reported
    else:
        assert reported == ""


def test_incomplete_file_list_fails_the_gate(github):
    api, _, execute = github
    api["pulls/7"] = {**PR, "changed_files": 2}
    with pytest.raises(subprocess.CalledProcessError):
        execute({"pull_request": PR})


def test_transient_workflow_lookup_on_a_green_revision(github, tmp_path):
    _, _, execute = github
    (tmp_path / "fail-once").write_text(endpoint("pull_request"))
    assert execute({"pull_request": PR}) == "result=ready"


def test_main_push_waits_for_its_own_unit_run(github):
    api, rows, execute = github
    repo = {"full_name": REPO}
    unit = {**rows[0], "event": "push", "head_repository": repo, "head_branch": "main"}
    api["git/ref/heads/main"] = {"object": {"sha": SHA}}
    running = [{"workflow_runs": [{**unit, "status": "in_progress", "conclusion": None}]}]
    api[endpoint("push")] = {"__sequence__": [running, [{"workflow_runs": [unit]}]]}
    assert execute({"repository": repo}, wait=60) == "result=ready"
    api[endpoint("push")] = [{"workflow_runs": [{**unit, "conclusion": "failure"}]}]
    assert execute({"repository": repo}) == "result=deferred"
    api["git/ref/heads/main"] = {"object": {"sha": "b" * 40}}
    assert execute({"repository": repo}) == "result=superseded"


def test_gate_can_read_every_fast_workflow():
    # An unreadable name or filter would fail every PR's gate.
    subprocess.run(["ruby", "-r", "./.github/scripts/ephemeral_fast_ci", "-e", """
      EphemeralFastCI::REQUIRED.each { |name| File.read(EphemeralFastCI.workflow_path(name)) }
      EphemeralFastCI::FILTERED.each do |name|
        path = EphemeralFastCI.workflow_path(name)
        EphemeralFastCI.pull_request_paths(File.read(path), path) or abort "#{name} lost its pull_request trigger"
      end
    """], cwd=ROOT, check=True)
