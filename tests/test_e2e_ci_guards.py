"""Hub-free regressions for focused coverage and fail-closed live label reads."""

import importlib.util
import re
import subprocess
import textwrap
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]


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
    assert {"devices", "developer_mode", "system_tools", "deadman"} <= set(scope.FILE_GROUP_MAP["hubitat-mcp-server.groovy"])
    assert "deadman" in scope.FILE_GROUP_MAP["libraries/mcp-code-management-lib.groovy"]
    # e2e never deploys the watchdog app, so mapping its sources would run tests that cannot see them.
    assert not any(source.startswith("e2e-deadman-watchdog") for source in scope.FILE_GROUP_MAP)
    assert "error_verification" in scope.SMOKE_GROUPS
