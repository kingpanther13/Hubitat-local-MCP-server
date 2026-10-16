"""Hub-free regressions for the e2e lanes' gates, live label reads and ephemeral hub relay."""

import json
import os
import re
import shutil
import subprocess
import textwrap
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]


def _lane_decision(files, labels=(), action="opened", label=None, author=("fixture", "User")):
    """lane_gate.js's verdict for a pull_request_target event; files are paths or file dicts."""
    payload = {"action": action, "label": {"name": label} if label else None,
               "pull_request": {"labels": [{"name": n} for n in labels],
                                "user": {"login": author[0], "type": author[1]}}}
    files = [f if isinstance(f, dict) else {"filename": f} for f in files]
    code = ("const { decide } = require(process.argv[1]); const [payload, files] = JSON.parse(process.argv[2]);"
            "console.log(decide('pull_request_target', payload, 1, files));")
    result = subprocess.run(["node", "-e", code, str(ROOT / ".github/scripts/lane_gate.js"),
                             json.dumps([payload, files])], capture_output=True, text=True, check=True)
    return result.stdout.strip()


@pytest.mark.parametrize("changed_files, relevant", [
    (["TOOL_GUIDE.md", "AGENTS.md", "CLAUDE.md", "SKILL.md", "tools/build-tool-guide.py",
      "tests/sandbox_lint.py", "tests/test_sandbox_lint.py", ".github/workflows/sandbox-lint.yml"], False),
    (["TOOL_GUIDE.md", "libraries/mcp-variables-lib.groovy"], True),
])
@pytest.mark.parametrize("pr_author", [("fixture", "User"), ("renovate[bot]", "Bot"), ("dependabot[bot]", "Bot")])
def test_guide_stack_gate_uses_actual_changed_files(changed_files, relevant, pr_author):
    # Both workflows must accept the guide's base, without trusting arbitrary bases.
    for name, event in [("hub-e2e.yml", "pull_request_target"), ("pr-guard.yml", "pull_request")]:
        source = (ROOT / ".github/workflows" / name).read_text()
        event_body = re.search(rf"^  {event}:\n((?:    .*\n|\n)*)", source, re.M).group(1)
        branches = re.search(r"^    branches: \[(.*?)\]", event_body, re.M).group(1)
        assert {b.strip() for b in branches.split(",")} == {"main", "pr/fixes-447-449-455-471"}
    # A docs-only diff gets the gate without the hub, with or without the full-run label.
    unlabeled = _lane_decision(changed_files, author=pr_author)
    labeled = _lane_decision(changed_files, ["e2e:full"], "labeled", "e2e:full", pr_author)
    assert (unlabeled, labeled) == (("pending", "run") if relevant else ("not-required", "not-required"))


@pytest.mark.parametrize("labels, available, lane", [
    ("e2e:full", "true", "full"),
    ("bug release:minor", "true", "full"),
    ("bug", "false", "pending"),
    ("", "false", "pending"),
    ("e2e:skip e2e:full", "false", "skipped"),
])
def test_e2e_gate_books_the_hub_only_with_a_live_full_run_label(labels, available, lane, tmp_path):
    workflow = (ROOT / ".github/workflows/hub-e2e.yml").read_text()
    assert "e2e_scope" not in workflow and "--groups" not in workflow and "focused" not in workflow.lower()
    step = workflow.split("id: gate\n", 1)[1].split("\n      - ", 1)[0]
    command = textwrap.dedent(step.split("        run: |\n", 1)[1])
    command = command.replace("${{ github.event_name }}", "pull_request_target")
    command = re.sub(r"\$\{\{.*?\}\}", "fixture", command)
    script = "gh() { [ -z \"$LABELS\" ] || printf '%s\\n' $LABELS; }\n" + command
    output = tmp_path / "output"
    subprocess.run(["bash", "-eo", "pipefail", "-c", script], cwd=ROOT,
                   env={**os.environ, "LABELS": labels, "MCP_URL": "unused", "WATCHDOG_URL": "unused",
                        "TESTS_INPUT": "", "GITHUB_OUTPUT": str(output),
                        "GITHUB_STEP_SUMMARY": str(tmp_path / "summary")},
                   capture_output=True, text=True, check=True)
    assert output.read_text().splitlines() == [f"available={available}", f"lane={lane}"]
    if lane != "full":
        assert "E2E NOT RUN" in (tmp_path / "summary").read_text()


@pytest.mark.parametrize("case, expected", [
    ("dependencies", "dependency_skip=true"),
    # Without a full-run label a hub-relevant or unverified diff only holds the gate pending.
    ("runtime-second-page", "pending=true"),
    ("runtime-labeled", "run=true"),
    ("renamed-runtime", "pending=true"),
    ("api-failure", "pending=true"),
    ("malformed", "pending=true"),
    ("truncated", "pending=true"),
    ("head-changed", "pending=true"),
    ("docs-human", "not_required=true"),
])
def test_gate_cli_verifies_complete_current_diff(case, expected, tmp_path):
    """Exercise the real CLI; only GitHub's read API is replaced with fixtures."""
    pages = [[{"filename": "build.gradle"}], [{
        "filename": ".github/workflows/hub-e2e.yml",
        "changes": 2,
        "patch": "@@ -1 +1 @@\n-      - uses: actions/checkout@v6\n+      - uses: actions/checkout@v7",
    }]]
    if case.startswith("runtime-"):
        pages[1] = [{"filename": "libraries/mcp-variables-lib.groovy"}]
    if case == "renamed-runtime":
        pages[0][0]["previous_filename"] = "hubitat-mcp-server.groovy"
    if case == "docs-human":
        pages = [[{"filename": "README.md"}], [{"filename": "docs/testing.md"}]]
    event = {
        "action": "synchronize",
        "sender": {"login": "kingpanther13"},
        "pull_request": {
            "number": 42, "head": {"sha": "a" * 40},
            "labels": [{"name": "e2e:full"}] if case == "runtime-labeled" else [],
            "user": {"login": "kingpanther13", "type": "User"} if case == "docs-human"
            else {"login": "renovate[bot]", "type": "Bot"},
        },
    }
    current = {
        "head": {"sha": ("b" if case == "head-changed" else "a") * 40},
        "changed_files": 3 if case == "truncated" else 2,
    }
    (tmp_path / "event.json").write_text(json.dumps(event))
    (tmp_path / "pages.json").write_text("invalid" if case == "malformed" else json.dumps(pages))
    (tmp_path / "current.json").write_text(json.dumps(current))
    gh = tmp_path / "gh"
    gh.write_text('''#!/usr/bin/env python3
import os
import sys
from pathlib import Path

root = Path(os.environ["FIXTURES"])
if os.environ["CASE"] == "api-failure":
    sys.exit(1)
args = sys.argv[1:]
if args == ["api", "--paginate", "--slurp", "repos/owner/repo/pulls/42/files?per_page=100"]:
    print((root / "pages.json").read_text())
elif args == ["api", "repos/owner/repo/pulls/42"]:
    print((root / "current.json").read_text())
else:
    sys.exit("Unexpected API request: " + repr(args))
''')
    gh.chmod(0o755)
    output = tmp_path / "output"
    result = subprocess.run(
        ["node", str(ROOT / ".github/scripts/lane_gate.js")],
        env={**os.environ, "PATH": f"{tmp_path}:{os.environ['PATH']}",
             "FIXTURES": str(tmp_path), "CASE": case, "GITHUB_REPOSITORY": "owner/repo",
             "GITHUB_EVENT_PATH": str(tmp_path / "event.json"), "GITHUB_RUN_ATTEMPT": "2",
             "GITHUB_EVENT_NAME": "pull_request_target", "GITHUB_OUTPUT": str(output)},
        capture_output=True, text=True, check=True,
    )
    outputs = output.read_text().splitlines()
    assert expected in outputs, result.stdout + result.stderr
    # Exactly one verdict output is true; the ephemeral lane's relevance comes from the same file list.
    verdicts = [o for o in outputs if not o.startswith("ephemeral_relevant=")]
    assert sorted(o.split("=")[0] for o in verdicts) == ["dependency_skip", "not_required", "pending", "run", "skip"]
    assert [o for o in verdicts if o.endswith("=true")] == [expected]
    assert f"ephemeral_relevant={str(case not in ('docs-human', 'dependencies')).lower()}" in outputs


@pytest.mark.parametrize("verdict, expected_state, description", [
    ("DEPENDENCY_SKIP", "success", "dependency-only bot PR"),
    ("NOT_REQUIRED", "success", "no hub-relevant change"),
    ("PENDING", "pending", "add release:* or e2e:full"),
    ("SKIP", "pending", "e2e:skip label"),
])
def test_no_hub_gate_posts_explicit_status(verdict, expected_state, description, tmp_path):
    workflow = (ROOT / ".github/workflows/hub-e2e.yml").read_text()
    step = workflow.split("name: Post e2e gate status (no hub run)\n", 1)[1].split("\n      - ", 1)[0]
    command = textwrap.dedent(step.split("        run: |\n", 1)[1].split("\n  # Pre-approval", 1)[0])
    command = command.replace("${{ github.repository }}", "owner/repo")
    script = '''
gh() { printf '%s\\n' "$@" > "$POSTED_ARGS"; }
''' + command
    summary = tmp_path / "summary"
    posted = tmp_path / "posted"
    flags = {name: "true" if name == verdict else "false" for name in ("DEPENDENCY_SKIP", "NOT_REQUIRED", "PENDING")}
    subprocess.run(["bash", "-eo", "pipefail", "-c", script], check=True,
                   env={**os.environ, **flags,
                        "GATE_SHA": "a" * 40, "GITHUB_STEP_SUMMARY": str(summary),
                        "POSTED_ARGS": str(posted)})
    args = posted.read_text().splitlines()
    assert f"repos/owner/repo/statuses/{'a' * 40}" in args
    assert f"state={expected_state}" in args
    assert "context=Full e2e (runs with label)" in args
    assert any(a.startswith("description=") and description in a for a in args), args
    assert "E2E NOT RUN" in summary.read_text()
    # The step runs for every verdict that posts here, and only for those.
    condition = step.split("        if: >-\n", 1)[1].split("        env:", 1)[0]
    assert sorted(re.findall(r"steps\.decide\.outputs\.(\w+) == 'true'", condition)) == [
        "dependency_skip", "not_required", "pending", "skip"]


@pytest.mark.parametrize("step_name, flag", [
    ("id: gate", "labels_ok"),
    ("name: Post e2e gate status", "LABELS_OK"),
])
@pytest.mark.parametrize("succeed_on", [0, 2])
def test_live_label_read_retries_failed_gh(step_name, flag, succeed_on, tmp_path):
    workflow = (ROOT / ".github/workflows/hub-e2e.yml").read_text()
    step = workflow.split(step_name + "\n", 1)[1].split("\n      - ", 1)[0]
    # The two steps indent their loops differently; `done` sits at the loop's own indentation.
    loop = re.search(r"^( +)for attempt in 1 2 3; do\n.*?\n\1done", step, re.S | re.M)
    assert loop is not None
    command = re.sub(r"\$\{\{.*?\}\}", "fixture", textwrap.dedent(loop.group()))
    # Match Actions' implicit bash -e versus explicit bash --noprofile --norc -eo pipefail.
    shell = ["bash", "-e"]
    if "shell: bash" in step:
        shell += ["-o", "pipefail"]
    attempts = tmp_path / "attempts"
    script = f'''
gh() {{
  echo called >> '{attempts}'
  count=$(wc -l < '{attempts}')
  if [ "$count" -eq {succeed_on} ]; then
    echo e2e:skip
    return 0
  fi
  return 1
}}
sleep() {{ :; }}
PR=42
{flag}=false
{command}
echo "label_read_ok=${{{flag}}}"
'''
    result = subprocess.run([*shell, "-c", script], capture_output=True, text=True, check=True)
    assert attempts.read_text().splitlines() == ["called"] * (succeed_on or 3)
    assert f"label_read_ok={'true' if succeed_on else 'false'}" in result.stdout


EPHEMERAL = (ROOT / ".github/workflows/hub-e2e-ephemeral.yml").read_text()
REQUEST = (ROOT / ".github/workflows/hub-e2e-ephemeral-request.yml").read_text()
FORK_SHA = "b" * 40


def _lane_relevance(file_lists):
    """For each file list (None = unread), lane_gate.js's physical verdict and ephemeral relevance."""
    code = ("const { decide, touchesLane } = require(process.argv[1]);"
            "const payload = { action: 'opened', pull_request: { labels: [] } };"
            "console.log(JSON.stringify(JSON.parse(process.argv[2]).map(files => ["
            "decide('pull_request_target', payload, 1, files ?? undefined), touchesLane(files ?? undefined, 'ephemeral')])));")
    lists = [None if f is None else [{"filename": p} for p in f] for f in file_lists]
    result = subprocess.run(["node", "-e", code, str(ROOT / ".github/scripts/lane_gate.js"), json.dumps(lists)],
                            capture_output=True, text=True, check=True)
    return json.loads(result.stdout)


def _ephemeral_gate(tmp_path, ephemeral_relevant, configured="true", dependency_skip="false"):
    step = REQUEST.split("      - id: decide\n", 1)[1].split("\n      - uses: actions/upload-artifact", 1)[0]
    command = textwrap.dedent(step.split("        run: |\n", 1)[1])
    output, summary = tmp_path / "output", tmp_path / "summary"
    env = {**os.environ, "CONFIGURED": configured, "DEPENDENCY_SKIP": dependency_skip,
           "EPHEMERAL_RELEVANT": ephemeral_relevant, "PR_HEAD_SHA": FORK_SHA, "PR_HEAD_REPO": "contributor/fork",
           "GITHUB_REPOSITORY": "owner/repo", "RUNNER_TEMP": str(tmp_path),
           "GITHUB_OUTPUT": str(output), "GITHUB_STEP_SUMMARY": str(summary)}
    result = subprocess.run([_bash(), "-eo", "pipefail", "-c", command], env=env, capture_output=True, text=True,
                            check=True)
    return result, output.read_text(), summary


@pytest.mark.parametrize("case, files, expected", [
    ("unconfigured", ["hubitat-mcp-server.groovy"], "Ephemeral hub lane not configured."),
    ("docs", ["docs/testing.md", "README.md"], "no hub-relevant files changed"),
    ("dependency", ["hubitat-mcp-server.groovy"], "not required"),
    ("code", ["docs/a.md", "libraries/mcp-rooms-lib.groovy"], f"sha={FORK_SHA}"),
    ("request-script", [".github/scripts/request_ephemeral_hub.sh"], f"sha={FORK_SHA}"),
    ("unlabeled", ["tests/e2e_test.py"], f"sha={FORK_SHA}"),  # the physical lane's labels do not apply
    ("unreadable", None, f"sha={FORK_SHA}"),
])
def test_ephemeral_gate_skips_cleanly_and_resolves_the_tested_head(case, files, expected, tmp_path):
    relevant = str(_lane_relevance([files])[0][1]).lower()
    result, outputs, summary = _ephemeral_gate(tmp_path, relevant, "false" if case == "unconfigured" else "true",
                                               "true" if case == "dependency" else "false")
    assert expected in result.stdout + outputs
    runs = case in ("code", "request-script", "unlabeled", "unreadable")
    assert ("run=true" in outputs) is runs and ("run=false" in outputs) is not runs
    if runs:
        assert "fork=true" in outputs and not (tmp_path / "unavailable").exists()
    else:
        # The test run skips on this reason instead of waiting for a hub.
        assert expected in summary.read_text()
        assert expected in (tmp_path / "unavailable" / "reason.txt").read_text()


def test_hub_lanes_share_one_path_list_and_agree_per_file(tmp_path):
    """hub_relevant_paths.json is the only path list; each consumer gives every file the same verdict."""
    rules = json.loads((ROOT / ".github/scripts/hub_relevant_paths.json").read_text())
    assert set(rules) == {"both", "physical", "ephemeral"}
    listed = [r for lane in rules.values() for r in lane]
    assert len(listed) == len(set(listed))
    # No workflow keeps its own copy.
    for workflow in (ROOT / ".github/workflows/hub-e2e.yml").read_text(), REQUEST:
        assert 'case "$f"' not in workflow and "/files" not in workflow
    assert "steps.lane.outputs.ephemeral_relevant" in REQUEST

    def expected(path, lane):
        return any(path.startswith(r) if r.endswith("/") else path == r for r in rules["both"] + rules[lane])

    paths = [r + "nested/x.groovy" if r.endswith("/") else r for r in listed]
    paths += ["README.md", "docs/testing.md", "libraries", "tests/lane_gate_test.js", ".github/workflows/ruff.yml"]
    verdicts = _lane_relevance([[p] for p in paths])
    for i, (path, (physical, ephemeral)) in enumerate(zip(paths, verdicts, strict=True)):
        assert physical == ("pending" if expected(path, "physical") else "not-required"), path
        assert ephemeral is expected(path, "ephemeral"), path
        (tmp_path / str(i)).mkdir()
        _, outputs, _ = _ephemeral_gate(tmp_path / str(i), str(ephemeral).lower())
        assert ("run=true" in outputs) is expected(path, "ephemeral"), path
        if path in rules["both"] or any(path.startswith(r) for r in rules["both"] if r.endswith("/")):
            assert physical == "pending" and ephemeral, path


def _jobs(workflow):
    head, body = workflow.split("\njobs:\n", 1)
    return head, dict(re.findall(r"^  ([\w-]+):\n((?:    .*\n|\n)*)", body + "\n", re.M))


def _run_block(workflow, marker):
    """The dedented `run: |` body of the first step at or after marker."""
    body = workflow.split(marker, 1)[1].split("        run: |\n", 1)[1]
    lines = []
    for line in body.splitlines():
        if line.strip() and not line.startswith("          "):
            break
        lines.append(line)
    return textwrap.dedent("\n".join(lines)) + "\n"


def _bash():
    return shutil.which("bash") or "bash"


def test_ephemeral_pr_code_runs_only_under_pull_request():
    head, jobs = _jobs(EPHEMERAL)
    assert "\npermissions:\n  contents: read\n" in head
    triggers = head.split("\non:\n", 1)[1].split("\npermissions:", 1)[0]
    assert "  pull_request:\n    types: [opened, synchronize, reopened]" in triggers and "pull_request_target" not in triggers
    assert set(jobs) == {"request-hub", "e2e"}
    request, e2e = jobs["request-hub"], jobs["e2e"]
    assert "if: github.event_name != 'pull_request'" in request and "contents: write" not in EPHEMERAL
    assert "secrets." not in e2e and "environment:" not in EPHEMERAL and "allow-unsafe-pr-checkout" not in EPHEMERAL
    assert "permissions:\n      contents: read\n      actions: read" in e2e
    assert e2e.index("ephemeral-hub-public-key-") < e2e.index("ephemeral-hub-session-") < e2e.index("actions/checkout")
    assert e2e.index("Mask hub identity") < e2e.index("actions/checkout")
    assert "--workflow hub-e2e-ephemeral-request.yml" in e2e and "ephemeral-hub-unavailable" in e2e
    assert "E2E_LANE: ephemeral" in e2e and "python tests/sdk_conformance_test.py" in e2e
    assert request.count("secrets.PRIVATE_CI_TOKEN") == 2  # the configured check and the provider step
    assert "python tests/" not in request
    # Provider-run results skip the head checkout, the Python install and the hub_get_info read.
    report = e2e.split("name: Report provider-run results\n", 1)[1].split("\n      - ", 1)[0]
    assert "if: steps.session.outputs.suite == 'true'" in report
    assert not any(word in report for word in ("checkout", "pip install", "python", "curl"))
    assert e2e.index("Report provider-run results") < e2e.index("Mask hub identity")
    for step in ("name: Mask hub identity", "uses: actions/checkout", "uses: actions/setup-python",
                 "name: Install Python dependencies", "name: Run E2E tests"):
        assert "if: steps.session.outputs.ready == 'true'" in e2e.split(step, 1)[1].split("\n      - ", 1)[0], step
    assert "(steps.deps.outcome == 'success' || steps.session.outputs.suite == 'true')" in e2e


KIND_MATRIX = "    strategy:\n      fail-fast: false\n      matrix:\n        kind: [os, jvm]\n"


def test_ephemeral_mode_toggle_and_kind_matrix_agree_and_reach_the_script():
    for workflow in (EPHEMERAL, REQUEST):
        head, jobs = _jobs(workflow)
        assert "\nenv:\n  EPHEMERAL_RUN_SUITE: 'true'\n\n" in head and "EPHEMERAL_HUB_KIND" not in workflow
        request = jobs["request-hub"]
        assert KIND_MATRIX in request and "    name: Request ephemeral hub (${{ matrix.kind }})\n" in request
        # The key or consumer lookup and the dispatch both take this job's kind.
        assert request.count("HUB_KIND: ${{ matrix.kind }}") == 2 and "RUN_SUITE: ${{ env.EPHEMERAL_RUN_SUITE }}" in request
        # Exactly one relay per mode: the results, or the encrypted session.
        assert request.count("env.EPHEMERAL_RUN_SUITE == 'true'") == 1 and request.count("env.EPHEMERAL_RUN_SUITE != 'true'") == 1
    e2e = _jobs(EPHEMERAL)[1]["e2e"]
    assert KIND_MATRIX in e2e and "    name: e2e (ephemeral hub, ${{ matrix.kind }})\n" in e2e
    assert "matrix" not in _jobs(REQUEST)[1]["gate"]
    # The test job finds its own run's request job by the name the matrix renders.
    assert 'export JOB="Request ephemeral hub ($KIND)"' in e2e and "select(.name == $ENV.JOB)" in e2e
    script = (ROOT / ".github/scripts/request_ephemeral_hub.sh").read_text()
    assert 'kind="${HUB_KIND:-os}"' in script and 'suite="${RUN_SUITE:-true}"' in script


def test_ephemeral_request_workflow_never_runs_pr_code():
    head, jobs = _jobs(REQUEST)
    assert "on:\n  pull_request_target:\n    types: [opened, synchronize, reopened]\n\npermissions:" in head
    assert "\npermissions:\n  contents: read\n" in head
    assert set(jobs) == {"gate", "approve", "request-hub"}
    # Only contributors with a merged change skip the reviewer-gated environment, and no hub is
    # requested before that job succeeds.
    trusted = """contains(fromJSON('["OWNER", "MEMBER", "COLLABORATOR", "CONTRIBUTOR"]'), github.event.pull_request.author_association)"""
    assert f"      name: ${{{{ {trusted} && 'e2e-auto' || 'e2e-approval' }}}}\n" in jobs["approve"]
    assert "    permissions: {}\n" in jobs["approve"] and "secrets." not in jobs["approve"]
    assert "    needs: [gate, approve]\n" in jobs["request-hub"] and "always()" not in jobs["request-hub"]
    # Base checkouts only, and nothing that installs or runs the head's Python.
    assert "ref:" not in REQUEST and "allow-unsafe-pr-checkout" not in REQUEST
    assert "python tests/" not in REQUEST and "pip install" not in REQUEST and "cache" not in REQUEST
    assert "contents: write" in jobs["request-hub"] and "contents: write" not in jobs["gate"]
    assert jobs["request-hub"].count("secrets.PRIVATE_CI_TOKEN") == 1
    assert "openssl" not in REQUEST and "session-priv" not in REQUEST
    assert "request_ephemeral_hub.sh consumer" in jobs["request-hub"]
    run = "${{ steps.consumer.outputs.run_id }}-${{ steps.consumer.outputs.attempt }}-${{ matrix.kind }}"
    assert f"ephemeral-hub-session-{run}\n" in REQUEST and f"ephemeral-hub-results-{run}\n" in REQUEST


def test_ephemeral_artifact_names_agree_across_workflows():
    script = (ROOT / ".github/scripts/request_ephemeral_hub.sh").read_text()
    # Both sides key the public key, the session and the results by the test run, its attempt and the kind.
    assert '"ephemeral-hub-public-key-$2-$kind"' in script
    assert "ephemeral-hub-public-key-${{ github.run_attempt }}-${{ matrix.kind }}\n" in EPHEMERAL
    assert 'SESSION="ephemeral-hub-session-$GITHUB_RUN_ID-$GITHUB_RUN_ATTEMPT-$KIND"' in EPHEMERAL
    assert "ephemeral-hub-session-${{ github.run_id }}-${{ github.run_attempt }}-${{ matrix.kind }}\n" in EPHEMERAL
    assert "--workflow hub-e2e-ephemeral.yml --event pull_request" in script
    assert "-f seconds=7200 -f watchdog=false" in script and 'consumer_run_id="$CONSUMER_RUN_ID"' in script
    assert 'RESULTS="ephemeral-hub-results-$GITHUB_RUN_ID-$GITHUB_RUN_ATTEMPT-$KIND"' in EPHEMERAL
    assert "ephemeral-hub-results-${{ github.run_id }}-${{ github.run_attempt }}-${{ matrix.kind }}\n" in EPHEMERAL
    assert "ephemeral-hub-unavailable-${{ matrix.kind }}\n" in EPHEMERAL
    assert "ephemeral-e2e-results-${{ github.run_attempt }}-${{ matrix.kind }}\n" in EPHEMERAL
    assert '"ephemeral-hub-results-$1"' in script and '-f kind="$kind" -f run_suite="$suite"' in script
    # Every artifact either job uploads carries the kind; only the PR gate's shared skip reason does not.
    for workflow in (EPHEMERAL, REQUEST):
        for job, body in _jobs(workflow)[1].items():
            for name in re.findall(r"^          name: (.*)$", body, re.M):
                assert name.endswith("-${{ matrix.kind }}") or (job, name) == ("gate", "ephemeral-hub-unavailable"), name


@pytest.mark.parametrize("kind", ["os", "jvm"])
def test_ephemeral_request_script_fetches_the_key_of_its_kind(kind, tmp_path):
    script = '''
gh() { printf '%s\\n' "$@" > "$STUB/download"; }
openssl() { :; }
export -f gh openssl
bash "$SCRIPT" key 100 2 "$STUB/pub"
'''
    env = {**os.environ, "STUB": str(tmp_path), "SCRIPT": str(ROOT / ".github/scripts/request_ephemeral_hub.sh"),
           "GITHUB_REPOSITORY": "owner/repo", "HUB_KIND": kind}
    result = subprocess.run([_bash(), "-c", script], env=env, capture_output=True, text=True)
    assert result.returncode == 0, result.stdout + result.stderr
    assert f"ephemeral-hub-public-key-2-{kind}" in (tmp_path / "download").read_text().splitlines()


@pytest.mark.parametrize("event, base, request_state, artifacts, outcome", [
    ("pull_request", "present", "7 completed success", "ephemeral-hub-unavailable", "skip"),  # the gate's shared reason
    ("pull_request", "present", "7 in_progress ", "ephemeral-hub-session-100-1-jvm\nephemeral-hub-session-100-1-os", "session"),
    ("pull_request", "present", "7 completed success", "ephemeral-hub-session-100-2-os", "fail"),  # served another attempt
    ("pull_request", "present", "7 completed success", "ephemeral-hub-session-100-1-jvm", "fail"),  # served the other kind
    ("pull_request", "present", "7 completed failure", "", "fail"),
    ("pull_request", "present", "", "", "fail"),  # no request run ever appears
    ("pull_request", "absent", "", "", "base-skip"),  # the default branch predates the request workflow
    ("pull_request", "error", "7 in_progress ", "ephemeral-hub-session-100-1-os", "session"),  # lookup error waits as usual
    ("push", "success", "success", "ephemeral-hub-unavailable-jvm\nephemeral-hub-unavailable-os", "skip"),
    ("push", "success", "success", "ephemeral-hub-unavailable-jvm", "fail"),  # only the other kind's reason
    ("push", "absent", "", "ephemeral-hub-session-100-1-os", "session"),  # no base check outside PRs
    ("push", "present", "failure", "ephemeral-hub-unavailable-os", "fail"),  # stale reason from an earlier attempt
])
def test_ephemeral_test_job_waits_for_its_own_session_or_a_skip_reason(event, base, request_state, artifacts, outcome, tmp_path):
    result, output, summary = _wait_for_hub(tmp_path, event, base, request_state, artifacts, "false")
    downloads = (tmp_path / "downloads").read_text() if (tmp_path / "downloads").exists() else ""
    if outcome == "fail":
        assert result.returncode == 1 and "::error::" in result.stdout and "FOUND" not in result.stdout
    elif outcome == "skip":
        assert result.returncode == 0 and "ready=false" in output.read_text()
        assert "not configured" in summary.read_text() and "FOUND" not in result.stdout
        assert "-jvm" not in downloads
    elif outcome == "base-skip":
        message = "Ephemeral hub lane: request workflow not on the default branch; skipped."
        assert result.returncode == 0 and "ready=false" in output.read_text() and message in result.stdout
        assert message in summary.read_text() and "FOUND" not in result.stdout
    else:
        assert result.returncode == 0 and "FOUND" in result.stdout and not output.exists()
        assert downloads.split() == ["--name", "ephemeral-hub-session-100-1-os"]


@pytest.mark.parametrize("event, request_state, artifacts, run_suite, outcome", [
    ("pull_request", "7 in_progress ", "ephemeral-hub-results-100-1-os", "true", "results"),
    ("pull_request", "7 in_progress ", "ephemeral-hub-results-100-1-os", "false", "results"),  # base requests the other mode
    ("push", "", "ephemeral-hub-results-100-1-os", "true", "results"),
    ("pull_request", "7 completed success", "ephemeral-hub-results-100-2-os", "true", "ended"),  # another attempt's results
    ("pull_request", "7 completed success", "ephemeral-hub-results-100-1-jvm", "true", "ended"),  # the other kind's results
    ("push", "failure", "", "true", "ended"),
    ("pull_request", "7 in_progress ", "", "true", "90 minutes"),
    ("pull_request", "7 in_progress ", "", "false", "45 minutes"),
])
def test_ephemeral_test_job_takes_provider_run_results(event, request_state, artifacts, run_suite, outcome, tmp_path):
    result, output, _ = _wait_for_hub(tmp_path, event, "present", request_state, artifacts, run_suite)
    if outcome == "results":
        assert result.returncode == 0 and "suite=true" in output.read_text() and "FOUND" not in result.stdout
        assert (tmp_path / "results" / "reason.txt").exists() and not (tmp_path / "session").exists()
    elif outcome == "ended":
        assert result.returncode == 1 and "without results or a session for this attempt" in result.stdout
    else:
        assert result.returncode == 1 and f"No hub results or session within {outcome}." in result.stdout


def _wait_for_hub(tmp_path, event, base, request_state, artifacts, run_suite):
    loop = _run_block(EPHEMERAL, "        id: session\n").split("\nkey=", 1)[0]
    script = '''
sleep() { SECONDS=$((SECONDS + 600)); }
gh() {
  case "$1 $2" in
    "api repos/owner/repo/contents/.github/workflows/hub-e2e-ephemeral-request.yml")
      [[ " $* " == *" ref=default-branch "* ]] || { echo "gh: missing ref" >&2; return 1; }
      case "$BASE" in
        absent) echo "gh: Not Found (HTTP 404)" >&2; return 1 ;;
        error) echo "gh: Server Error (HTTP 502)" >&2; return 1 ;;
      esac ;;
    "run list") printf '%s\\n' "$REQUEST_STATE" ;;
    "api repos/owner/repo/actions/runs/100/attempts/1/jobs") printf '%s\\n' "$REQUEST_STATE" ;;
    "api repos/owner/repo/actions/runs/"*/artifacts) printf '%s\\n' "$ARTIFACTS" ;;
    "run download")
      shift 2; while [ "$1" != --dir ]; do [ "$1" != --name ] || echo "$1 $2" >> "$RUNNER_TEMP/downloads"; shift; done
      mkdir -p "$2"; echo "Ephemeral hub lane not configured." > "$2/reason.txt" ;;
    *) return 1 ;;
  esac
}
''' + loop + "\necho FOUND\n"
    output, summary = tmp_path / "output", tmp_path / "summary"
    env = {**os.environ, "EVENT_NAME": event, "HEAD_SHA": FORK_SHA, "GITHUB_REPOSITORY": "owner/repo",
           "BASE": base, "DEFAULT_BRANCH": "default-branch", "KIND": "os",
           "GITHUB_RUN_ID": "100", "GITHUB_RUN_ATTEMPT": "1", "REQUEST_STATE": request_state, "ARTIFACTS": artifacts,
           "EPHEMERAL_RUN_SUITE": run_suite,
           "RUNNER_TEMP": str(tmp_path), "GITHUB_OUTPUT": str(output), "GITHUB_STEP_SUMMARY": str(summary)}
    result = subprocess.run([_bash(), "-c", script], env=env, capture_output=True, text=True)
    return result, output, summary


PROVIDER_RESULTS = {
    "schema_version": 1, "kind": "jvm", "public_sha": FORK_SHA, "public_repo": "contributor/fork",
    "started_at": "2026-10-15T10:00:00Z", "finished_at": "2026-10-15T10:12:00Z",
    "e2e": {"status": "passed", "exit_code": 0, "passed": 300, "failed": 0, "skipped": 4, "total": 304,
            "failed_tests": []},
    "sdk": {"status": "passed", "exit_code": 0, "passed": 12, "failed": 0, "failed_tests": []},
}
FAILED_E2E = {"status": "failed", "exit_code": 1, "passed": 298, "failed": 2, "skipped": 4, "total": 304,
              "failed_tests": [{"name": "test_alpha", "message": "boom\ntrace"}, {"name": "test_beta", "message": "x"}]}


@pytest.mark.parametrize("case, changes, passes", [
    ("passed", {}, True),
    ("sdk-not-run", {"sdk": {"status": "not_run", "exit_code": None, "passed": 0, "failed": 0, "failed_tests": []}}, False),
    ("e2e-failed", {"e2e": FAILED_E2E}, False),
    ("e2e-error", {"e2e": {**PROVIDER_RESULTS["e2e"], "status": "error", "exit_code": 2}}, False),
    ("sdk-failed", {"sdk": {"status": "failed", "exit_code": 1, "passed": 11, "failed": 1,
                            "failed_tests": [{"name": "test_sdk_gamma", "message": "y"}]}}, False),
    ("other-revision", {"public_sha": "c" * 40}, False),
    ("other-kind", {"kind": "os"}, False),
    ("schema", {"schema_version": 2}, False),
    ("missing", None, False),
    ("unexpected-file", {}, False),
])
def test_ephemeral_provider_results_print_and_gate(case, changes, passes, tmp_path):
    results = tmp_path / "results"
    results.mkdir()
    if changes is not None:
        (results / "results.json").write_text(json.dumps({**PROVIDER_RESULTS, **changes}))
    if case == "unexpected-file":
        (results / "extra.sh").write_text("echo hi\n")
    (results / "e2e.log").write_text("PASS via abc-12.trycloudflare.com aa:bb:cc:dd:ee:ff\n::error::from a test\n")
    (results / "sdk.log").write_text("SDK OK\n")
    summary = tmp_path / "summary"
    env = {**os.environ, "RUNNER_TEMP": str(tmp_path), "EXPECTED_SHA": FORK_SHA, "KIND": "jvm",
           "GITHUB_STEP_SUMMARY": str(summary)}
    result = subprocess.run([_bash(), "-c", _run_block(EPHEMERAL, "name: Report provider-run results\n")],
                            env=env, capture_output=True, text=True)
    out = result.stdout
    assert (result.returncode == 0) is passes, out + result.stderr
    if case in ("missing", "unexpected-file"):
        message = "have no results.json" if case == "missing" else "hold an unexpected entry"
        assert f"::error::The provider-run results {message}." in out and "SDK OK" not in out
        return
    # All three logs print with workflow commands off, and the uploaded copies carry the same scrub.
    stop = re.search(r"^::stop-commands::(\w+)$", out, re.M).group(1)
    assert out.index("::stop-commands::") < out.index("::error::from a test") < out.index(f"::{stop}::")
    assert "SDK OK" in out and "PASS via *** ***" in out
    assert "PASS via *** ***" in (results / "e2e.log").read_text()
    if case in ("other-revision", "other-kind", "schema"):
        assert not summary.exists() and "::error::" in out.split(f"::{stop}::")[-1]
        return
    text = summary.read_text()
    e2e, sdk = {**PROVIDER_RESULTS, **changes}["e2e"], {**PROVIDER_RESULTS, **changes}["sdk"]
    assert f"E2E {e2e['status']}: passed {e2e['passed']}, failed {e2e['failed']}, skipped 4 of 304" in text
    assert f"SDK conformance {sdk['status']}: passed {sdk['passed']}" in text
    if case == "sdk-not-run":
        assert "exit none." in text and "::error::The SDK conformance leg did not run, and it may not skip." in out
    else:
        assert "did not run" not in out
    if case == "e2e-failed":
        assert "- test_alpha: boom\n" in text and "- test_beta: x" in text
    if case == "sdk-failed":
        assert "- test_sdk_gamma: y" in text
    if not passes:
        assert "::error::The provider-run suite failed; the step summary lists the failed tests." in out


@pytest.mark.parametrize("case", ["results", "no-results", "no-results-json", "unexpected-file",
                                  "unsafe-name", "nested-dir", "session", "bad-kind"])
def test_ephemeral_request_script_relays_provider_results_or_session(case, tmp_path):
    script = '''
gh() {
  case "$1 $2" in
    "workflow run") printf '%s\\n' "$@" > "$STUB/dispatch" ;;
    "run list") echo 555 ;;
    "run view") echo completed ;;
    "api repos/owner/provider/actions/runs/555/artifacts") [[ " $PROVIDER_ARTIFACTS " != *" $ARTIFACT "* ]] || echo 9 ;;
    "run download")
      shift 2; while [ "$1" != --dir ]; do shift; done; mkdir -p "$2"
      if [ "$ARTIFACT" = ephemeral-hub-555 ]; then printf x > "$2/session.key.enc"; printf x > "$2/session.json.enc"
      else
        for f in e2e.log sdk.log harness.log; do echo "$f from owner/provider with tok-secret" > "$2/$f"; done
        [ "$CASE" = no-results-json ] || echo '{"schema_version": 1}' > "$2/results.json"
        case "$CASE" in
          unexpected-file) echo hi > "$2/extra.sh" ;;
          unsafe-name) echo hi > "$2/evil name;x.log" ;;
          nested-dir) mkdir "$2/nested" ;;
        esac
      fi ;;
    *) return 1 ;;
  esac
}
sleep() { :; }
export -f gh sleep
bash "$SCRIPT" hub "$STUB/key.pem" "$STUB/out"
'''
    (tmp_path / "key.pem").write_text("public key")
    artifacts = "ephemeral-hub-555" if case == "session" else "" if case == "no-results" else "ephemeral-hub-results-555"
    env = {**os.environ, "STUB": str(tmp_path), "CASE": case, "PROVIDER_ARTIFACTS": artifacts,
           "SCRIPT": str(ROOT / ".github/scripts/request_ephemeral_hub.sh"), "GITHUB_REPOSITORY": "owner/repo",
           "GH_TOKEN": "tok-secret", "PROVIDER": "owner/provider", "PUBLIC_SHA": FORK_SHA,
           "PUBLIC_REPO": "contributor/fork", "CONSUMER_RUN_ID": "100",
           "HUB_KIND": "arm" if case == "bad-kind" else "os", "RUN_SUITE": "false" if case == "session" else "true"}
    result = subprocess.run([_bash(), "-c", script], env=env, capture_output=True, text=True)
    out, dispatch, relayed = result.stdout, tmp_path / "dispatch", tmp_path / "out"
    if case == "bad-kind":
        assert result.returncode == 1 and "HUB_KIND must be jvm or os" in out and not dispatch.exists()
        return
    args = dispatch.read_text().splitlines()
    assert "kind=os" in args and f"run_suite={env['RUN_SUITE']}" in args
    expected_error = {
        "no-results": "The hub provider run completed without suite results.",
        "no-results-json": "The provider's suite results have no results.json.",
        "unexpected-file": "The provider's suite results hold an unexpected file: extra.sh",
        # A name outside the safe set is never echoed.
        "unsafe-name": "The provider's suite results hold an entry that is not a plain, safely named file.",
        "nested-dir": "The provider's suite results hold an entry that is not a plain, safely named file.",
    }.get(case)
    if expected_error:
        assert result.returncode == 1 and f"::error::{expected_error}" in out and "evil" not in out
        # Rejected before the scrub ran.
        assert case == "no-results" or "tok-secret" in (relayed / "e2e.log").read_text()
    elif case == "results":
        assert result.returncode == 0, out + result.stderr
        for f in ("e2e.log", "sdk.log"):
            assert (relayed / f).read_text() == f"{f} from *** with ***\n"
        # The provider's stage log is accepted but never relayed.
        assert sorted(p.name for p in relayed.iterdir()) == ["e2e.log", "results.json", "sdk.log"]
    else:
        assert result.returncode == 0, out + result.stderr
        assert (relayed / "session.key.enc").exists() and not (relayed / "results.json").exists()


@pytest.mark.parametrize("kind, expected", [("os", "3"), ("jvm", "1")])
def test_ephemeral_request_script_matches_provider_run_by_consumer_token_and_kind(kind, expected):
    """The provider run is found by " for <repo>#<run id> (<kind>," since both kinds share the run id."""
    script = (ROOT / ".github/scripts/request_ephemeral_hub.sh").read_text()
    assert 'export MATCH=" for ${GITHUB_REPOSITORY}#${CONSUMER_RUN_ID} (${kind}," SINCE="$since"' in script
    program = re.search(r"--jq '(\[\.\[\] \| select\(\(\.displayTitle.*?)'", script).group(1)
    runs = json.dumps([
        {"databaseId": 1, "createdAt": "2026-10-15T10:00:00Z", "displayTitle": "Ephemeral hub a for o/r#42 (jvm, suite)"},
        {"databaseId": 2, "createdAt": "2026-10-15T10:00:00Z", "displayTitle": "Ephemeral hub a for o/r#420 (os, suite)"},
        {"databaseId": 3, "createdAt": "2026-10-15T10:00:00Z", "displayTitle": "Ephemeral hub a for o/r#42 (os, suite)"},
        {"databaseId": 4, "createdAt": "2026-10-15T09:00:00Z", "displayTitle": "Ephemeral hub a for o/r#42 (os, suite)"},
        {"databaseId": 5, "createdAt": "2026-10-15T10:00:00Z", "displayTitle": "Ephemeral hub a for o/r#42"},
    ])
    if not shutil.which("jq"):
        pytest.skip("jq is not installed")
    env = {**os.environ, "MATCH": f" for o/r#42 ({kind},", "SINCE": "2026-10-15T09:30:00Z"}
    picked = subprocess.run(["jq", "-r", program], input=runs, capture_output=True, text=True, env=env, check=True)
    assert picked.stdout.strip() == expected
