"""Hub-free regressions for focused coverage and fail-closed live label reads."""

import importlib.util
import json
import os
import re
import shutil
import subprocess
import textwrap
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]


@pytest.mark.parametrize("changed_files, expected", [
    ("TOOL_GUIDE.md\nAGENTS.md\nCLAUDE.md\nSKILL.md\ntools/build-tool-guide.py\n"
     "tests/sandbox_lint.py\ntests/test_sandbox_lint.py\n.github/workflows/sandbox-lint.yml", False),
    ("TOOL_GUIDE.md\nlibraries/mcp-variables-lib.groovy", True),
])
@pytest.mark.parametrize("pr_author", ["fixture", "renovate[bot]", "dependabot[bot]"])
def test_guide_stack_gate_uses_actual_changed_files(changed_files, expected, pr_author, tmp_path):
    workflow = (ROOT / ".github/workflows/hub-e2e.yml").read_text()
    # Both workflows must accept the guide's base, without trusting arbitrary bases.
    for name, event in [("hub-e2e.yml", "pull_request_target"), ("pr-guard.yml", "pull_request")]:
        source = (ROOT / ".github/workflows" / name).read_text()
        event_body = re.search(rf"^  {event}:\n((?:    .*\n|\n)*)", source, re.M).group(1)
        branches = re.search(r"^    branches: \[(.*?)\]", event_body, re.M).group(1)
        assert {b.strip() for b in branches.split(",")} == {"main", "pr/fixes-447-449-455-471"}
    step = workflow.split("id: gate\n", 1)[1].split("\n      - ", 1)[0]
    command = textwrap.dedent(step.split("        run: |\n", 1)[1])
    command = command.replace("${{ github.event_name }}", "pull_request_target")
    command = re.sub(r"\$\{\{.*?\}\}", "fixture", command)
    script = '''
gh() {
  case "$*" in
    *'/files'*) printf '%s\\n' "$CHANGED_FILES" ;;
    *) printf '%s\\n' 'e2e:full' ;;
  esac
}
''' + command
    output = tmp_path / "output"
    result = subprocess.run(["bash", "-eo", "pipefail", "-c", script], cwd=ROOT,
                            env={**os.environ, "CHANGED_FILES": changed_files,
                                 "MCP_URL": "unused", "WATCHDOG_URL": "unused",
                                 "PR_AUTHOR": pr_author, "TESTS_INPUT": "",
                                 "GITHUB_OUTPUT": str(output),
                                 "GITHUB_STEP_SUMMARY": str(tmp_path / "summary")},
                            capture_output=True, text=True, check=True)
    assert f"available={str(expected).lower()}" in output.read_text(), result.stdout
    if not expected:
        assert "WITHOUT running the suite" in result.stdout


@pytest.mark.parametrize("case, expected", [
    ("dependencies", "dependency_skip=true"),
    ("runtime-second-page", "run=true"),
    ("renamed-runtime", "run=true"),
    ("api-failure", "run=true"),
    ("malformed", "run=true"),
    ("truncated", "run=true"),
    ("head-changed", "run=true"),
])
def test_bot_gate_cli_verifies_complete_current_diff(case, expected, tmp_path):
    """Exercise the real CLI; only GitHub's read API is replaced with fixtures."""
    pages = [[{"filename": "build.gradle"}], [{
        "filename": ".github/workflows/hub-e2e.yml",
        "changes": 2,
        "patch": "@@ -1 +1 @@\n-      - uses: actions/checkout@v6\n+      - uses: actions/checkout@v7",
    }]]
    if case == "runtime-second-page":
        pages[1] = [{"filename": "libraries/mcp-variables-lib.groovy"}]
    if case == "renamed-runtime":
        pages[0][0]["previous_filename"] = "hubitat-mcp-server.groovy"
    event = {
        "action": "synchronize",
        "sender": {"login": "kingpanther13"},
        "pull_request": {
            "number": 42, "head": {"sha": "a" * 40}, "labels": [],
            "user": {"login": "renovate[bot]", "type": "Bot"},
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
    if case == "dependencies":
        assert "run=false" in outputs
        assert "skip=false" in outputs
    else:
        assert "dependency_skip=false" in outputs


@pytest.mark.parametrize("dependency_skip, expected_state", [("true", "success"), ("false", "pending")])
def test_no_hub_gate_posts_explicit_status(dependency_skip, expected_state, tmp_path):
    workflow = (ROOT / ".github/workflows/hub-e2e.yml").read_text()
    step = workflow.split("name: Post e2e gate status (no hub run)\n", 1)[1].split("\n      - ", 1)[0]
    command = textwrap.dedent(step.split("        run: |\n", 1)[1].split("\n  # Pre-approval", 1)[0])
    command = command.replace("${{ github.repository }}", "owner/repo")
    script = '''
gh() { printf '%s\\n' "$@" > "$POSTED_ARGS"; }
''' + command
    summary = tmp_path / "summary"
    posted = tmp_path / "posted"
    subprocess.run(["bash", "-eo", "pipefail", "-c", script], check=True,
                   env={**os.environ, "DEPENDENCY_SKIP": dependency_skip,
                        "GATE_SHA": "a" * 40, "GITHUB_STEP_SUMMARY": str(summary),
                        "POSTED_ARGS": str(posted)})
    args = posted.read_text().splitlines()
    assert f"repos/owner/repo/statuses/{'a' * 40}" in args
    assert f"state={expected_state}" in args
    assert "context=Full e2e (runs with label)" in args
    assert "E2E NOT RUN" in summary.read_text()


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


def test_focused_coverage_includes_shared_device_access_and_watchdog():
    spec = importlib.util.spec_from_file_location("e2e_scope", ROOT / ".github/scripts/e2e_scope.py")
    scope = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(scope)
    assert {"devices", "developer_mode", "system_tools", "app_code_update"} <= set(scope.FILE_GROUP_MAP["hubitat-mcp-server.groovy"])
    assert "app_code_update" in scope.FILE_GROUP_MAP["libraries/mcp-code-management-lib.groovy"]
    # e2e never deploys the watchdog app, so mapping its sources would run tests that cannot see them.
    assert not any(source.startswith("e2e-deadman-watchdog") for source in scope.FILE_GROUP_MAP)
    assert "error_verification" in scope.SMOKE_GROUPS


EPHEMERAL = (ROOT / ".github/workflows/hub-e2e-ephemeral.yml").read_text()
REQUEST = (ROOT / ".github/workflows/hub-e2e-ephemeral-request.yml").read_text()
FORK_SHA = "b" * 40


@pytest.mark.parametrize("case, files, expected", [
    ("unconfigured", "hubitat-mcp-server.groovy", "Ephemeral hub lane not configured."),
    ("docs", "docs/testing.md\nREADME.md", "no hub-relevant files changed"),
    ("dependency", "hubitat-mcp-server.groovy", "not required"),
    ("code", "docs/a.md\nlibraries/mcp-rooms-lib.groovy", f"sha={FORK_SHA}"),
    ("request-script", ".github/scripts/request_ephemeral_hub.sh", f"sha={FORK_SHA}"),
    ("e2e-skip-label", "tests/e2e_test.py", f"sha={FORK_SHA}"),
    ("unreadable", None, f"sha={FORK_SHA}"),
])
def test_ephemeral_gate_skips_cleanly_and_resolves_the_tested_head(case, files, expected, tmp_path):
    step = REQUEST.split("      - id: decide\n", 1)[1].split("\n      - uses: actions/upload-artifact", 1)[0]
    command = textwrap.dedent(step.split("        run: |\n", 1)[1])
    script = '''
gh() {
  [ -n "$CHANGED_FILES" ] || return 1
  printf '%s\\n' "$CHANGED_FILES"
}
''' + command
    output, summary = tmp_path / "output", tmp_path / "summary"
    env = {**os.environ, "CONFIGURED": "false" if case == "unconfigured" else "true",
           "LANE_RUN": "false" if case in ("dependency", "e2e-skip-label") else "true",
           "LANE_SKIP": "true" if case == "e2e-skip-label" else "false",
           "PR_NUMBER": "42", "PR_HEAD_SHA": FORK_SHA, "PR_HEAD_REPO": "contributor/fork",
           "GITHUB_REPOSITORY": "owner/repo", "CHANGED_FILES": files or "", "RUNNER_TEMP": str(tmp_path),
           "GITHUB_OUTPUT": str(output), "GITHUB_STEP_SUMMARY": str(summary)}
    result = subprocess.run([shutil.which("bash") or "bash", "-eo", "pipefail", "-c", script],
                            env=env, capture_output=True, text=True, check=True)
    outputs = output.read_text()
    assert expected in result.stdout + outputs
    runs = case in ("code", "request-script", "e2e-skip-label", "unreadable")
    assert ("run=true" in outputs) is runs and ("run=false" in outputs) is not runs
    if runs:
        assert "fork=true" in outputs and not (tmp_path / "unavailable").exists()
    else:
        # The test run skips on this reason instead of waiting for a hub.
        assert expected in summary.read_text()
        assert expected in (tmp_path / "unavailable" / "reason.txt").read_text()


def _jobs(workflow):
    head, body = workflow.split("\njobs:\n", 1)
    return head, dict(re.findall(r"^  ([\w-]+):\n((?:    .*\n|\n)*)", body + "\n", re.M))


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


def test_ephemeral_request_workflow_never_runs_pr_code():
    head, jobs = _jobs(REQUEST)
    assert "on:\n  pull_request_target:\n    types: [opened, synchronize, reopened]\n\npermissions:" in head
    assert "\npermissions:\n  contents: read\n" in head
    assert set(jobs) == {"gate", "request-hub"}
    # Base checkouts only, and nothing that installs or runs the head's Python.
    assert "ref:" not in REQUEST and "allow-unsafe-pr-checkout" not in REQUEST
    assert "python tests/" not in REQUEST and "pip install" not in REQUEST and "cache" not in REQUEST
    assert "contents: write" in jobs["request-hub"] and "contents: write" not in jobs["gate"]
    assert jobs["request-hub"].count("secrets.PRIVATE_CI_TOKEN") == 1
    assert "openssl" not in REQUEST and "session-priv" not in REQUEST
    assert "request_ephemeral_hub.sh consumer" in jobs["request-hub"]
    assert "ephemeral-hub-session-${{ steps.consumer.outputs.run_id }}-${{ steps.consumer.outputs.attempt }}" in REQUEST


def test_ephemeral_artifact_names_agree_across_workflows():
    script = (ROOT / ".github/scripts/request_ephemeral_hub.sh").read_text()
    # Both sides key the public key and the session by the test run and its attempt.
    assert '"ephemeral-hub-public-key-$2"' in script and "ephemeral-hub-public-key-${{ github.run_attempt }}" in EPHEMERAL
    assert 'SESSION="ephemeral-hub-session-$GITHUB_RUN_ID-$GITHUB_RUN_ATTEMPT"' in EPHEMERAL
    assert "ephemeral-hub-session-${{ github.run_id }}-${{ github.run_attempt }}" in EPHEMERAL
    assert "--workflow hub-e2e-ephemeral.yml --event pull_request" in script
    assert "-f seconds=7200 -f watchdog=false" in script and 'consumer_run_id="$CONSUMER_RUN_ID"' in script


@pytest.mark.parametrize("event, base, request_state, artifacts, outcome", [
    ("pull_request", "present", "7 completed success", "ephemeral-hub-unavailable", "skip"),
    ("pull_request", "present", "7 in_progress ", "ephemeral-hub-session-100-1", "session"),
    ("pull_request", "present", "7 completed success", "ephemeral-hub-session-100-2", "fail"),  # served another attempt
    ("pull_request", "present", "7 completed failure", "", "fail"),
    ("pull_request", "present", "", "", "fail"),  # no request run ever appears
    ("pull_request", "absent", "", "", "base-skip"),  # base predates the request workflow
    ("pull_request", "error", "7 in_progress ", "ephemeral-hub-session-100-1", "session"),  # lookup error waits as usual
    ("push", "success", "success", "ephemeral-hub-unavailable", "skip"),
    ("push", "absent", "", "ephemeral-hub-session-100-1", "session"),  # no base check outside PRs
    ("push", "present", "failure", "ephemeral-hub-unavailable", "fail"),  # stale reason from an earlier attempt
])
def test_ephemeral_test_job_waits_for_its_own_session_or_a_skip_reason(event, base, request_state, artifacts, outcome, tmp_path):
    step = EPHEMERAL.split("        id: session\n", 1)[1].split("\n      - name: Mask hub identity", 1)[0]
    loop = textwrap.dedent(step.split("        run: |\n", 1)[1]).split("\nkey=", 1)[0]
    script = '''
sleep() { SECONDS=$((SECONDS + 600)); }
gh() {
  case "$1 $2" in
    "api repos/owner/repo/contents/.github/workflows/hub-e2e-ephemeral-request.yml")
      [[ " $* " == *" ref=base-branch "* ]] || { echo "gh: missing ref" >&2; return 1; }
      case "$BASE" in
        absent) echo "gh: Not Found (HTTP 404)" >&2; return 1 ;;
        error) echo "gh: Server Error (HTTP 502)" >&2; return 1 ;;
      esac ;;
    "run list") printf '%s\\n' "$REQUEST_STATE" ;;
    "api repos/owner/repo/actions/runs/100/attempts/1/jobs") printf '%s\\n' "$REQUEST_STATE" ;;
    "api repos/owner/repo/actions/runs/"*/artifacts) printf '%s\\n' "$ARTIFACTS" ;;
    "run download")
      shift 2; while [ "$1" != --dir ]; do shift; done
      mkdir -p "$2"; echo "Ephemeral hub lane not configured." > "$2/reason.txt" ;;
    *) return 1 ;;
  esac
}
''' + loop + "\necho FOUND\n"
    output, summary = tmp_path / "output", tmp_path / "summary"
    env = {**os.environ, "EVENT_NAME": event, "HEAD_SHA": FORK_SHA, "GITHUB_REPOSITORY": "owner/repo",
           "BASE": base, "BASE_REF": "base-branch",
           "GITHUB_RUN_ID": "100", "GITHUB_RUN_ATTEMPT": "1", "REQUEST_STATE": request_state, "ARTIFACTS": artifacts,
           "RUNNER_TEMP": str(tmp_path), "GITHUB_OUTPUT": str(output), "GITHUB_STEP_SUMMARY": str(summary)}
    result = subprocess.run([shutil.which("bash") or "bash", "-c", script], env=env, capture_output=True, text=True)
    if outcome == "fail":
        assert result.returncode == 1 and "::error::" in result.stdout and "FOUND" not in result.stdout
    elif outcome == "skip":
        assert result.returncode == 0 and "ready=false" in output.read_text()
        assert "not configured" in summary.read_text() and "FOUND" not in result.stdout
    elif outcome == "base-skip":
        message = "Ephemeral hub lane: request workflow not on the base branch; skipped."
        assert result.returncode == 0 and "ready=false" in output.read_text() and message in result.stdout
        assert message in summary.read_text() and "FOUND" not in result.stdout
    else:
        assert result.returncode == 0 and "FOUND" in result.stdout and not output.exists()
