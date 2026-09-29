"""Run maintenance and its shared helpers with an offline curl transport."""

import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
SOURCE_PARTS = ["// prior watchdog\n", "def oldTool() { '☃' }\n", "// last line\n\n"]

# Only external transport and waiting are replaced; all shell validation,
# source preservation, deployment confirmation, and bootstrap logic stays real.
TRANSPORT = r'''
import json
import os
import sys
from pathlib import Path

args = sys.argv[1:]
rpc = json.loads(args[args.index("--data-binary") + 1])
root = Path(os.environ["RUNNER_TEMP"])
fixture = json.loads((root / "fixture.json").read_text())
with (root / "calls").open("a") as log:
    log.write(json.dumps(rpc) + "\n")

def emit(value):
    print(json.dumps({"jsonrpc": "2.0", "id": 1, "result": {
        "content": [{"type": "text", "text": json.dumps(value)}]}}))

if rpc["method"] == "tools/list":
    print(json.dumps({"jsonrpc": "2.0", "id": 1, "result": {
        "tools": [{"name": "hub_set_mcp_developer_mode"}]}}))
    sys.exit(0)
params = rpc["params"]
name = params["name"]
arguments = params["arguments"]
if name == "hub_read_file":
    assert arguments == {"fileName": "e2e-deadman-v2.json"}
    emit(fixture["flag"])
elif name == "hub_list_apps":
    emit({"success": True, "apps": [
        {"id": "42", "namespace": "mcp", "name": "E2E Dead-Man Watchdog v2"}]})
elif name == "hub_get_source":
    offset = arguments["offset"]
    assert arguments == {"type": "app", "id": "42", "offset": offset,
                         "length": 32000, "noSave": True}
    emit(fixture["pages"][str(offset)])
elif name == "hub_get_info":
    if "https://main.invalid/apps/194/mcp?access_token=fixture" in args:
        emit({"developerModeEnabled": (root / "enabled").exists()})
    else:
        emit({"lastSelfDeploy": {"appId": "42", "success": True,
                                 "at": 2 if (root / "deployed").exists() else 1}})
elif name == "hub_update_app":
    assert arguments == {
        "appId": "42", "selfUpdate": True, "selfClassId": "42", "confirm": True,
        "importUrl": "https://raw.githubusercontent.com/fixture/repo/expected/e2e-deadman-watchdog-v2.groovy",
    }
    (root / "source-at-deploy").write_bytes((root / "watchdog-before.groovy").read_bytes())
    (root / "deployed").touch()
    emit({"success": True})
elif name == "hub_set_mcp_developer_mode":
    assert arguments == {"appId": "194", "enabled": True, "confirm": True}
    (root / "enabled").touch()
    emit({"success": True})
else:
    raise AssertionError(name)
'''


def run_maintenance(tmp_path, *, flag=None, pages=None, prepare_only=False, damage=None):
    if flag is None:
        flag = {"success": True, "hasMore": False, "content": '{"armed":false}'}
    if pages is None:
        pages = {}
        offset = 0
        for index, source in enumerate(SOURCE_PARTS):
            pages[str(offset)] = {
                "success": True, "source": source, "offset": offset,
                "hasMore": index < len(SOURCE_PARTS) - 1,
                "nextOffset": offset + len(source),
            }
            offset += len(source)
    (tmp_path / "fixture.json").write_text(json.dumps({"flag": flag, "pages": pages}))
    bindir = tmp_path / "bin"
    bindir.mkdir()
    curl = bindir / "curl"
    curl.write_text(f"#!{sys.executable}\n" + TRANSPORT)
    curl.chmod(0o755)
    sleep = bindir / "sleep"
    sleep.write_text("#!/bin/sh\nexit 0\n")
    sleep.chmod(0o755)
    env = {**os.environ, "PATH": f"{bindir}:{os.environ['PATH']}",
           "RUNNER_TEMP": str(tmp_path), "GITHUB_SHA": "expected",
           "GITHUB_REPOSITORY": "fixture/repo", "WD_RPC_ATTEMPTS": "1",
           "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "1",
           "MCP_URL": "https://main.invalid/apps/194/mcp?access_token=fixture",
           "WATCHDOG_URL": "https://watchdog.invalid/mcp"}

    def invoke(phase):
        return subprocess.run(
            ["bash", str(ROOT / ".github/scripts/watchdog_maintenance.sh"), phase],
            env=env, capture_output=True, text=True, check=False, timeout=30,
        )

    result = invoke("prepare")
    if result.returncode == 0 and not prepare_only:
        # Model the artifact download into a separate directory.
        verified = tmp_path / "watchdog-maintenance-verified"
        verified.mkdir()
        for name in ("watchdog-before.groovy", "watchdog-before.json"):
            source = tmp_path / name
            if source.exists():
                shutil.copyfile(source, verified / name)
        if damage:
            damage(verified)
        result = invoke("deploy")
    calls = [json.loads(line) for line in (tmp_path / "calls").read_text().splitlines()]
    return result, calls


@pytest.mark.parametrize("flag", [
    {"success": True, "hasMore": False, "content": '{"armed":true}'},
    {"success": True, "hasMore": False, "content": '{"armed":'},
    {"success": True, "hasMore": False, "content": '{}'},
    {"success": True, "hasMore": True, "content": '{"armed":false}'},
    {"success": True, "content": '{"armed":false}'},
    {"success": False, "hasMore": False, "content": '{"armed":false}'},
], ids=["armed", "malformed", "missing-armed", "incomplete", "missing-has-more", "read-failed"])
def test_maintenance_refuses_unverified_disarm_before_any_deploy(tmp_path, flag):
    result, calls = run_maintenance(tmp_path, flag=flag)
    assert result.returncode != 0, result.stdout + result.stderr
    assert [call["params"]["name"] for call in calls] == ["hub_read_file"]
    assert not (tmp_path / "deployed").exists()


@pytest.mark.parametrize("restore", [
    {"runId": "123"},
    {"runId": "123", "restoreFor": "122", "restoreResult": "restored"},
    {"runId": "123", "restoreFor": "123", "restoreResult": "running"},
    {"runId": "123", "restoreFor": "123"},
    {"restoreResult": "restored"},
    {"runId": "", "restoreFor": "", "restoreResult": "restored"},
], ids=["pending", "different-run", "unknown-result", "missing-result",
        "missing-run", "empty-run"])
def test_maintenance_refuses_unfinished_disarm_restore(tmp_path, restore):
    flag = {"success": True, "hasMore": False,
            "content": json.dumps({"armed": False, "intent": "disarm", **restore})}
    result, calls = run_maintenance(tmp_path, flag=flag)
    assert result.returncode != 0, result.stdout + result.stderr
    assert [call["params"]["name"] for call in calls] == ["hub_read_file"]
    assert not (tmp_path / "deployed").exists()


@pytest.mark.parametrize("restore_result", ["restored", "failed"])
@pytest.mark.parametrize("restore_for", ["123", 123], ids=["string-id", "numeric-id"])
def test_maintenance_accepts_terminal_restore_and_warns_on_failure(tmp_path, restore_result, restore_for):
    flag = {"success": True, "hasMore": False, "content": json.dumps({
        "armed": False, "intent": "disarm", "runId": "123", "restoreFor": restore_for,
        "restoreResult": restore_result,
    })}
    result, _ = run_maintenance(tmp_path, flag=flag)
    assert result.returncode == 0, result.stdout + result.stderr
    assert (tmp_path / "deployed").exists()
    warnings = [line.lower() for line in result.stdout.splitlines() if "::warning::" in line]
    if restore_result == "failed":
        assert any("restore" in line and "failed" in line and "maintenance" in line
                   for line in warnings), result.stdout
    else:
        assert not warnings, result.stdout


@pytest.mark.parametrize("page", [
    {"success": False, "offset": 0, "source": "old", "hasMore": False},
    {"success": True, "offset": 1, "source": "old", "hasMore": False},
    {"success": True, "offset": 0, "source": None, "hasMore": False},
    {"success": True, "offset": 0, "source": "old"},
    {"success": True, "offset": 0, "source": "old", "hasMore": "false"},
    {"success": True, "offset": 0, "source": "old", "hasMore": True},
    {"success": True, "offset": 0, "source": "old", "hasMore": True, "nextOffset": 0},
], ids=["read-failed", "wrong-offset", "non-string-source", "missing-has-more",
        "non-boolean-has-more", "missing-next-offset", "nonadvancing-next-offset"])
def test_maintenance_refuses_invalid_source_pages_before_deploy(tmp_path, page):
    result, calls = run_maintenance(tmp_path, pages={"0": page})
    assert result.returncode != 0, result.stdout + result.stderr
    assert [call["params"]["name"] for call in calls] == [
        "hub_read_file", "hub_list_apps", "hub_get_source",
    ]
    assert not (tmp_path / "deployed").exists()


def test_maintenance_preserves_every_source_page_before_deployment(tmp_path):
    result, calls = run_maintenance(tmp_path)
    assert result.returncode == 0, result.stdout + result.stderr
    expected = "// prior watchdog\ndef oldTool() { '☃' }\n// last line\n\n".encode()
    assert (tmp_path / "watchdog-before.groovy").read_bytes() == expected
    assert (tmp_path / "source-at-deploy").read_bytes() == expected
    source_reads = [call["params"]["arguments"] for call in calls
                    if call["params"].get("name") == "hub_get_source"]
    assert [read["offset"] for read in source_reads] == [0, 18, 40]
    assert sum(call["params"].get("name") == "hub_update_app" for call in calls) == 1
    assert (tmp_path / "enabled").exists()


def test_prepare_preserves_source_without_deploying(tmp_path):
    result, calls = run_maintenance(tmp_path, prepare_only=True)
    assert result.returncode == 0, result.stdout + result.stderr
    assert not (tmp_path / "deployed").exists()
    assert not any(call["params"].get("name") == "hub_update_app" for call in calls)
    metadata = json.loads((tmp_path / "watchdog-before.json").read_text())
    assert metadata["classId"] == "42"


@pytest.mark.parametrize("damage", [
    lambda directory: (directory / "watchdog-before.groovy").unlink(missing_ok=True),
    lambda directory: (directory / "watchdog-before.json").unlink(missing_ok=True),
    lambda directory: (directory / "watchdog-before.groovy").write_text("truncated"),
    lambda directory: (directory / "watchdog-before.json").write_text('{}'),
], ids=["missing-source", "missing-metadata", "corrupt-source", "wrong-identity"])
def test_deploy_requires_verified_downloaded_backup(tmp_path, damage):
    result, calls = run_maintenance(tmp_path, damage=damage)
    assert result.returncode != 0, result.stdout + result.stderr
    assert not (tmp_path / "deployed").exists()
    assert not any(call["params"].get("name") == "hub_update_app" for call in calls)


def test_deploy_rechecks_disarm_after_backup_download(tmp_path):
    def arm_watchdog(directory):
        fixture_path = directory.parent / "fixture.json"
        fixture = json.loads(fixture_path.read_text())
        fixture["flag"]["content"] = '{"armed":true}'
        fixture_path.write_text(json.dumps(fixture))

    result, calls = run_maintenance(tmp_path, damage=arm_watchdog)
    assert result.returncode != 0, result.stdout + result.stderr
    assert "Cannot verify a disarmed watchdog" in result.stdout
    assert not any(call["params"].get("name") == "hub_update_app" for call in calls)
    assert (tmp_path / "watchdog-maintenance-verified/watchdog-before.groovy").read_bytes() == (
        "".join(SOURCE_PARTS).encode()
    )
