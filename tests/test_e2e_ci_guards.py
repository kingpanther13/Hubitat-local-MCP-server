"""Hub-free regressions for focused coverage and fail-closed live label reads."""

import importlib.util
import json
import os
import re
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
