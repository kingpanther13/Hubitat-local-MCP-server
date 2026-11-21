"""Run the gate and coordinator scripts against a fake gh that serves canned GitHub API responses."""
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
CONSUMER = "hub-e2e-ephemeral"
REQUESTER = "hub-e2e-ephemeral-request"
OPEN_PRS = "pulls?state=open&head=fork%3Afeature&per_page=100"
FILES = "pulls/7/files?per_page=100"


def run_row(name, job_id, *, trigger="pull_request", status="completed", conclusion="success"):
    return {"id": job_id, "path": f".github/workflows/{name}.yml", "run_number": 3,
            "run_attempt": 1, "event": trigger, "head_sha": SHA, "head_branch": HEAD["ref"],
            "head_repository": FORK, "pull_requests": [], "status": status, "conclusion": conclusion}


def endpoint(event):
    return f"actions/runs?head_sha={SHA}&event={event}&per_page=100"


def jobs(run_id):
    return f"actions/runs/{run_id}/attempts/1/jobs?per_page=100"


def gate_job(job_id, deferred, *, status="completed", conclusion="success"):
    return {"id": job_id, "name": "Fast CI prerequisites", "status": status, "conclusion": conclusion,
            "steps": [{"name": "Defer hubs until fast CI passes", "conclusion": "success" if deferred else "skipped"}]}


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
if "POST" in sys.argv:
    with (root / "posts").open("a") as out:
        print(path, file=out)
else:
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
    rows = [run_row(name, i) for i, name in enumerate([*REQUIRED, "groovy24-parse", "sandbox-lint", CONSUMER], 1)]
    api = {"pulls/7": PR, OPEN_PRS: [[PR]],
           FILES: [[{"filename": "libraries/a.groovy", "status": "modified"}]],
           endpoint("pull_request"): [{"workflow_runs": rows}],
           endpoint("pull_request_target"): [{"workflow_runs": [run_row(REQUESTER, 7, trigger="pull_request_target")]}],
           jobs(6): [{"jobs": [gate_job(66, True)]}],
           jobs(7): [{"jobs": [gate_job(77, True)]}]}

    api["actions/runs/6"] = rows[-1]
    api["actions/runs/7"] = api[endpoint("pull_request_target")][0]["workflow_runs"][0]

    def execute(script, payload):
        (tmp_path / "api").write_text(json.dumps(api))
        (tmp_path / "event").write_text(json.dumps(payload))
        env = {**os.environ, "PATH": str(tmp_path) + os.pathsep + os.environ["PATH"], "FAKE_GITHUB": str(tmp_path),
               "GITHUB_REPOSITORY": REPO, "GITHUB_EVENT_PATH": str(tmp_path / "event"), "GITHUB_SHA": SHA,
               "GITHUB_OUTPUT": str(tmp_path / "outputs")}
        env.pop("GITHUB_STEP_SUMMARY", None)
        subprocess.run(["ruby", f".github/scripts/{script}.rb"], cwd=ROOT, env=env, check=True)
        if script == "resume_ephemeral_ci":
            posts = tmp_path / "posts"
            return posts.read_text().splitlines() if posts.exists() else []
        return (tmp_path / "outputs").read_text().splitlines()[-1]
    return api, rows, execute


def change_files(api, files):
    api[FILES] = [files]
    api["pulls/7"] = {**PR, "changed_files": len(files)}


@pytest.mark.parametrize("case,expected", [
    ("green", "ready"), ("missing-match", "deferred"), ("other-fork", "deferred"),
    ("older-green", "deferred"), ("cancelled", "deferred"), ("queued", "deferred"),
    ("stale", "superseded"), ("rename", "deferred"), ("scheduled-red", "deferred"),
    ("filter-narrowed", "ready"), ("filter-removed", "ready"), ("beyond-300", "ready"),
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
    elif case == "beyond-300":
        # GitHub ignores file 301, so python-tests and ruff never start.
        change_files(api, [{"filename": f"libraries/f{i}.groovy", "status": "modified"} for i in range(300)]
                     + [{"filename": "tests/test_x.py", "status": "added"}])
    assert execute("ephemeral_fast_ci", {"pull_request": PR}) == f"result={expected}"


def test_incomplete_file_list_fails_the_gate(github):
    api, _, execute = github
    api["pulls/7"] = {**PR, "changed_files": 2}
    with pytest.raises(subprocess.CalledProcessError):
        execute("ephemeral_fast_ci", {"pull_request": PR})


@pytest.mark.parametrize("case,expected", [
    ("both-deferred", [77, 66]), ("already-started", []), ("other-fast-red", []),
    ("no-open-pr", []), ("consumer-ready", [77]), ("missing-applicable", []),
    ("gate-failed", [77, 66]), ("gate-not-started", [77]), ("ambiguous-pr", []),
    ("associated-pr", [77, 66]), ("requester-rerun-elsewhere", [66]), ("moved-before-rerun", []),
])
def test_fast_completion_resumes_only_deferred_jobs(github, case, expected):
    api, rows, execute = github
    if case in ("already-started", "consumer-ready"):
        api[jobs(6)][0]["jobs"] = [gate_job(66, False)]
    if case == "already-started":
        api[jobs(7)][0]["jobs"] = [gate_job(77, False)]
    if case == "other-fast-red":
        rows[1]["conclusion"] = "failure"
    if case == "missing-applicable":
        rows.pop(3)
    if case == "no-open-pr":
        api[OPEN_PRS] = [[]]
    if case == "gate-failed":
        api[jobs(6)][0]["jobs"] = [gate_job(66, False, conclusion="failure")]
    if case == "gate-not-started":
        api[jobs(6)][0]["jobs"] = [gate_job(66, False, status="pending", conclusion=None)]
    if case in ("ambiguous-pr", "associated-pr"):
        api[OPEN_PRS] = [[PR, {**PR, "number": 8}]]
    if case == "associated-pr":
        rows[0]["pull_requests"] = [{"number": 7}]
    if case == "requester-rerun-elsewhere":
        api["actions/runs/7"] = {**api["actions/runs/7"], "run_attempt": 2}
    if case == "moved-before-rerun":
        # Current for the decision's two reads, then pushed before the first re-run.
        api["pulls/7"] = {"__sequence__": [PR, PR, {**PR, "head": {**HEAD, "sha": "b" * 40}}]}
    assert execute("resume_ephemeral_ci", {"workflow_run": rows[0]}) == [f"actions/jobs/{i}/rerun" for i in expected]


def test_lost_rerun_reply_is_repeated_only_when_nothing_ran(github, tmp_path):
    _, rows, execute = github
    (tmp_path / "fail-once").write_text("actions/jobs/77/rerun")
    assert execute("resume_ephemeral_ci", {"workflow_run": rows[0]}) == ["actions/jobs/77/rerun", "actions/jobs/66/rerun"]


def test_transient_workflow_lookup_on_a_green_revision(github, tmp_path):
    _, _, execute = github
    (tmp_path / "fail-once").write_text(endpoint("pull_request"))
    assert execute("ephemeral_fast_ci", {"pull_request": PR}) == "result=ready"


def main_push(api, rows):
    repo = {"full_name": REPO, "owner": {"login": "base"}}
    unit = {**rows[0], "event": "push", "head_repository": repo, "head_branch": "main"}
    consumer = {**rows[-1], "event": "push", "head_repository": repo, "head_branch": "main"}
    api["git/ref/heads/main"] = {"object": {"sha": SHA}}
    api[endpoint("push")] = [{"workflow_runs": [unit, consumer]}]
    api["actions/runs/6"] = consumer
    return repo, unit


def test_main_push_uses_its_own_unit_run(github):
    api, rows, execute = github
    repo, unit = main_push(api, rows)
    assert execute("ephemeral_fast_ci", {"repository": repo}) == "result=ready"
    unit["conclusion"] = "failure"
    assert execute("ephemeral_fast_ci", {"repository": repo}) == "result=deferred"


@pytest.mark.parametrize("moved", [False, True])
def test_main_push_resumes_only_the_consumer_on_the_current_head(github, moved):
    api, rows, execute = github
    _, unit = main_push(api, rows)
    if moved:
        api["git/ref/heads/main"] = {"object": {"sha": "b" * 40}}
    assert execute("resume_ephemeral_ci", {"workflow_run": unit}) == ([] if moved else ["actions/jobs/66/rerun"])


def test_every_fast_workflow_can_wake_the_hubs():
    subprocess.run(["ruby", "-r", "./.github/scripts/ephemeral_fast_ci", "-e", """
      names = (EphemeralFastCI::REQUIRED + EphemeralFastCI::FILTERED).map do |name|
        YAML.safe_load_file(EphemeralFastCI.workflow_path(name)).fetch('name')
      end
      workflow = YAML.safe_load_file('.github/workflows/ephemeral-fast-ci-completed.yml')
      abort 'Fast workflow subscription drift' unless names.sort == workflow.fetch(true).fetch('workflow_run').fetch('workflows').sort
      # Every filter must stay readable by the gate, or every PR's gate fails.
      EphemeralFastCI::FILTERED.each do |name|
        path = EphemeralFastCI.workflow_path(name)
        EphemeralFastCI.pull_request_paths(File.read(path), path) or abort "#{name} lost its pull_request trigger"
      end
    """], cwd=ROOT, check=True)


def test_coordinator_finds_both_gates_by_name():
    subprocess.run(["ruby", "-r", "./.github/scripts/ephemeral_fast_ci", "-e", """
      [EphemeralFastCI::CONSUMER, EphemeralFastCI::REQUESTER].each do |name|
        gate = YAML.safe_load_file(EphemeralFastCI.workflow_path(name)).fetch('jobs').fetch('fast-ci')
        abort "#{name}: gate job renamed" unless gate.fetch('name') == EphemeralFastCI::GATE_JOB
        abort "#{name}: defer step renamed" unless gate.fetch('steps').any? { |s| s['name'] == EphemeralFastCI::DEFERRED_STEP }
      end
    """], cwd=ROOT, check=True)
