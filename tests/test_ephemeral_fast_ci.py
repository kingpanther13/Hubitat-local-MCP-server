"""Exercise the gate and coordinator against the same GitHub API contract as Actions."""
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


def run_row(name, job_id, *, trigger="pull_request", status="completed", conclusion="success"):
    return {"id": job_id, "path": f".github/workflows/{name}.yml", "run_number": 3,
            "run_attempt": 1, "event": trigger, "head_sha": SHA, "head_branch": HEAD["ref"],
            "head_repository": FORK, "pull_requests": [], "status": status, "conclusion": conclusion}


def endpoint(event):
    return f"actions/runs?head_sha={SHA}&event={event}&per_page=100"


def gate_job(job_id, deferred):
    return {"id": job_id, "name": "Fast CI prerequisites", "status": "completed", "conclusion": "success",
            "steps": [{"name": "Defer hubs until fast CI passes", "conclusion": "success" if deferred else "skipped"}]}


@pytest.fixture
def github(tmp_path):
    gh = tmp_path / "gh"
    gh.write_text(f"#!{sys.executable}\n" + """
import json, os, sys
from pathlib import Path
root = Path(os.environ["FAKE_GITHUB"])
path = sys.argv[-1].removeprefix("repos/base/server/")
if "POST" in sys.argv:
    with (root / "posts").open("a") as out:
        print(path, file=out)
else:
    print(json.dumps(json.loads((root / "api").read_text())[path]))
""")
    gh.chmod(0o755)
    rows = [run_row(name, i) for i, name in enumerate([*REQUIRED, "groovy24-parse", "sandbox-lint", CONSUMER], 1)]
    api = {"pulls/7": PR, "pulls?state=open&head=fork%3Afeature&per_page=100": [[PR]],
           "pulls/7/files?per_page=100": [[{"filename": "libraries/a.groovy", "status": "modified"}]],
           endpoint("pull_request"): [{"workflow_runs": rows}],
           endpoint("pull_request_target"): [{"workflow_runs": [run_row(REQUESTER, 7, trigger="pull_request_target")]}],
           "actions/runs/6/attempts/1/jobs?per_page=100": [{"jobs": [gate_job(66, True)]}],
           "actions/runs/7/attempts/1/jobs?per_page=100": [{"jobs": [gate_job(77, True)]}]}

    api["actions/runs/6"] = rows[-1]
    api["actions/runs/7"] = api[endpoint("pull_request_target")][0]["workflow_runs"][0]

    def execute(script, payload):
        (tmp_path / "api").write_text(json.dumps(api))
        (tmp_path / "event").write_text(json.dumps(payload))
        env = {**os.environ, "PATH": str(tmp_path) + os.pathsep + os.environ["PATH"], "FAKE_GITHUB": str(tmp_path),
               "GITHUB_REPOSITORY": REPO, "GITHUB_EVENT_PATH": str(tmp_path / "event"), "GITHUB_SHA": SHA,
               "GITHUB_OUTPUT": str(tmp_path / "outputs")}
        subprocess.run(["ruby", f".github/scripts/{script}.rb"], cwd=ROOT, env=env, check=True)
        if script == "resume_ephemeral_ci":
            posts = tmp_path / "posts"
            return posts.read_text().splitlines() if posts.exists() else []
        return (tmp_path / "outputs").read_text().splitlines()[-1]
    return api, rows, execute


@pytest.mark.parametrize("case,expected", [
    ("green", "ready"), ("missing-match", "deferred"), ("other-fork", "deferred"),
    ("older-green", "deferred"), ("cancelled", "deferred"), ("queued", "deferred"),
    ("stale", "superseded"), ("rename", "deferred"),
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
        api["pulls/7/files?per_page=100"][0][0]["previous_filename"] = "scripts/old.py"
    assert execute("ephemeral_fast_ci", {"pull_request": PR}) == f"result={expected}"


@pytest.mark.parametrize("case,expected", [
    ("both-deferred", [66, 77]), ("already-started", []), ("other-fast-red", []),
    ("superseded", []), ("consumer-ready", [77]), ("missing-applicable", []),
])
def test_fast_completion_resumes_only_deferred_jobs(github, case, expected):
    api, rows, execute = github
    if case in ("already-started", "consumer-ready"):
        api["actions/runs/6/attempts/1/jobs?per_page=100"][0]["jobs"] = [gate_job(66, False)]
    if case == "already-started":
        api["actions/runs/7/attempts/1/jobs?per_page=100"][0]["jobs"] = [gate_job(77, False)]
    if case == "other-fast-red":
        rows[1]["conclusion"] = "failure"
    if case == "missing-applicable":
        rows.pop(3)
    if case == "superseded":
        api["pulls?state=open&head=fork%3Afeature&per_page=100"] = [[]]
    assert execute("resume_ephemeral_ci", {"workflow_run": rows[0]}) == [f"actions/jobs/{i}/rerun" for i in expected]


def test_main_push_uses_its_own_unit_run(github):
    api, rows, execute = github
    repo = {"full_name": REPO}
    unit = {**rows[0], "event": "push", "head_repository": repo, "head_branch": "main"}
    api["git/ref/heads/main"] = {"object": {"sha": SHA}}
    api[endpoint("push")] = [{"workflow_runs": [unit]}]
    assert execute("ephemeral_fast_ci", {"repository": repo}) == "result=ready"
    unit["conclusion"] = "failure"
    assert execute("ephemeral_fast_ci", {"repository": repo}) == "result=deferred"


def test_every_fast_workflow_can_wake_the_hubs():
    subprocess.run(["ruby", "-r", "./.github/scripts/ephemeral_fast_ci", "-e", """
      names = (EphemeralFastCI::REQUIRED + EphemeralFastCI::FILTERED).map do |name|
        YAML.safe_load_file(EphemeralFastCI.workflow_path(name)).fetch('name')
      end
      workflow = YAML.safe_load_file('.github/workflows/ephemeral-fast-ci-completed.yml')
      abort 'Fast workflow subscription drift' unless names.sort == workflow.fetch(true).fetch('workflow_run').fetch('workflows').sort
    """], cwd=ROOT, check=True)
