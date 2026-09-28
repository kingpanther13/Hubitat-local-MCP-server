"""Exercise the real setup shell script with an offline MCP transport."""

import json
import os
import re
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]

# Every curl invocation is replaced; these tests cannot contact a hub.
TRANSPORT = r'''
import json
import os
import sys
from pathlib import Path

args = sys.argv[1:]
payload = json.loads(args[args.index("--data-binary") + 1] if "--data-binary" in args
                     else args[args.index("-d") + 1])
params = payload["params"]
name = params["name"]
root = Path(os.environ["RUNNER_TEMP"])
scenario = os.environ["SCENARIO"]
with (root / "calls").open("a") as log:
    log.write(json.dumps(params) + "\n")

def emit(value):
    print(json.dumps({"jsonrpc": "2.0", "id": 1, "result": {
        "content": [{"type": "text", "text": json.dumps(value)}]}}))

if name == "hub_get_info":
    if scenario == "rpc_error":
        print(json.dumps({"error": {"code": -32603, "message": "read failed"}}))
    elif scenario == "invalid_info":
        emit({"success": False})
    elif scenario == "string_boolean":
        emit({"developerModeEnabled": "false"})
    else:
        emit({"developerModeEnabled": scenario == "already_on" or (root / "enabled").exists(),
              "customRuleEngineEnabled": False})
elif name == "hub_set_mcp_developer_mode":
    assert "https://watchdog.invalid/mcp" in args
    assert params["arguments"] == {"appId": "194", "enabled": True, "confirm": True}
    if scenario != "refused":
        (root / "enabled").touch()
    if scenario == "lost_response":
        sys.exit(22)
    emit({"success": scenario != "refused"})
elif name == "hub_create_backup":
    emit({"success": True})
elif name == "hub_manage_mcp":
    assert params["arguments"]["tool"] == "hub_update_mcp_settings"
    assert params["arguments"]["args"]["settings"] == {"enableCustomRuleEngine": True, "useGateways": True}
    emit({"success": True})
else:
    raise AssertionError(name)
'''


@pytest.mark.parametrize("scenario, succeeds, bootstrap", [
    ("already_on", True, False),
    ("enable", True, True),
    ("lost_response", True, True),
    ("refused", False, True),
    ("rpc_error", False, False),
    ("invalid_info", False, False),
    ("string_boolean", False, False),
])
def test_setup_bootstraps_only_verified_off_state(tmp_path, scenario, succeeds, bootstrap):
    bindir = tmp_path / "bin"
    bindir.mkdir()
    curl = bindir / "curl"
    curl.write_text(f"#!{sys.executable}\n" + TRANSPORT)
    curl.chmod(0o755)
    sleep = bindir / "sleep"
    sleep.write_text("#!/bin/sh\nexit 0\n")
    sleep.chmod(0o755)
    result = subprocess.run(
        ["bash", str(ROOT / ".github/scripts/mcp_setup_env.sh")],
        env={**os.environ, "PATH": f"{bindir}:{os.environ['PATH']}",
             "RUNNER_TEMP": str(tmp_path), "SCENARIO": scenario,
             "MCP_URL": "https://main.invalid/mcp", "WATCHDOG_URL": "https://watchdog.invalid/mcp",
             "HUBITAT_APP_ID": "194"},
        capture_output=True, text=True, check=False,
    )
    assert (result.returncode == 0) == succeeds, result.stdout + result.stderr
    calls = [json.loads(line) for line in (tmp_path / "calls").read_text().splitlines()]
    names = [call["name"] for call in calls]
    assert names.count("hub_set_mcp_developer_mode") == int(bootstrap)
    if succeeds:
        assert names[-2:] == ["hub_create_backup", "hub_manage_mcp"]
        assert json.loads((tmp_path / "mcp_pre_state.json").read_text()) == {
            "enableCustomRuleEngine": False,
        }
        if bootstrap:
            assert names[:3] == ["hub_get_info", "hub_set_mcp_developer_mode", "hub_get_info"]
    else:
        assert "hub_create_backup" not in names
        assert "hub_manage_mcp" not in names
        assert not (tmp_path / "mcp_pre_state.json").exists()


WORKFLOWS = [
    "unit-tests.yml", "groovy24-parse.yml", "groovy2x-spock.yml", "python-tests.yml",
    "ruff.yml", "sandbox-lint.yml", "pr-guard.yml", "self-deploy-recovery.yml",
    "lease-scripts-test.yml", "lane-gate-test.yml",
]


@pytest.mark.parametrize("scenario", ["green", "missing", "wrong_sha", "failed", "newer_pending"])
def test_watchdog_maintenance_requires_complete_current_ci(tmp_path, scenario):
    runs = [
        {"path": f".github/workflows/{name}", "head_sha": "expected",
         "status": "completed", "conclusion": "success", "run_number": 1, "run_attempt": 1}
        for name in WORKFLOWS
    ]
    if scenario == "missing":
        runs.pop()
    elif scenario == "wrong_sha":
        runs[-1]["head_sha"] = "old"
    elif scenario == "failed":
        runs[-1]["conclusion"] = "failure"
    elif scenario == "newer_pending":
        runs.append({**runs[-1], "run_number": 2, "status": "in_progress", "conclusion": None})
    payload = tmp_path / "runs.json"
    payload.write_text(json.dumps({"workflow_runs": runs}))
    gh = tmp_path / "gh"
    gh.write_text(f'#!/bin/sh\ncat "{payload}"\n')
    gh.chmod(0o755)
    result = subprocess.run(
        ["bash", str(ROOT / ".github/scripts/watchdog_maintenance_ci_gate.sh")],
        env={**os.environ, "PATH": f"{tmp_path}:{os.environ['PATH']}",
             "GITHUB_SHA": "expected", "GITHUB_REPOSITORY": "fixture/repo"},
        capture_output=True, text=True, check=False,
    )
    assert (result.returncode == 0) == (scenario == "green"), result.stdout + result.stderr


def test_watchdog_maintenance_is_exclusive_and_never_called_by_e2e():
    source = (ROOT / ".github/workflows/hub-e2e.yml").read_text()
    for job in ["e2e", "probe", "fork-bundle"]:
        # Split at the next two-space job key, not the indented body.
        body = re.split(r"\n  [a-z][\w-]*:\n", source.split(f"\n  {job}:\n", 1)[1])[0]
        assert "inputs.watchdog_update != 'true'" in body
        assert "watchdog_maintenance.sh" not in body
    maintenance = source.split("\n  watchdog-maintenance:\n", 1)[1].split("\n  probe:\n", 1)[0]
    assert "github.event_name == 'workflow_dispatch' && inputs.watchdog_update == 'true'" in maintenance
    assert "!cancelled() && needs.approve.result == 'success'" in maintenance
    assert "group: hub-e2e-serialized" in maintenance
    assert "watchdog_maintenance_ci_gate.sh" in maintenance
    assert 'lease_acquire.sh' in maintenance and 'lease_release.sh' in maintenance
    assert maintenance.index("watchdog_maintenance_ci_gate.sh") < maintenance.index("lease_acquire.sh")
    assert "tests/e2e_test.py" not in maintenance
