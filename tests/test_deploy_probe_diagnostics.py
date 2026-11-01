"""pytest: the deploy's endpoint probes survive a bad response and report what came back.

Regression guard for ``.github/scripts/mcp_watchdog_deploy.sh``. Its post-deploy bind-check grepped the
``tools/list`` body for a missing-library signature; grep exits 1 on no match, so under the script's
``set -euo pipefail`` the first attempt without a catalog ended the step with a bare exit 1 -- no retry,
no error, nothing about the response. These tests run the script's own ``mcp_probe`` function and
``BAD_LIB`` line (extracted from the file, not copied) under the same shell options, and its
``reset_runtime_stats`` function against the real watchdog lib with ``mcp_call`` stubbed.
"""

import json
import re
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parent.parent / ".github" / "scripts" / "mcp_watchdog_deploy.sh"
CATALOG = b'{"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"hub_get_info"}]}}'


def _probe_function() -> str:
    text = SCRIPT.read_text()
    match = re.search(r"^mcp_probe\(\) \{\n.*?^\}\n", text, re.S | re.M)
    assert match, f"{SCRIPT.name} no longer defines mcp_probe()"
    return match.group(0)


def _bad_lib_line() -> str:
    lines = [ln.strip() for ln in SCRIPT.read_text().splitlines() if ln.strip().startswith("BAD_LIB=$(")]
    assert len(lines) == 1, f"expected exactly one BAD_LIB assignment in {SCRIPT.name}, found {len(lines)}"
    return lines[0]


def _bash(script: str) -> subprocess.CompletedProcess:
    return subprocess.run(["bash", "-c", "set -euo pipefail\n" + script], capture_output=True, text=True, timeout=60)


class _Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length") or 0))
        if self.path == "/slow":
            time.sleep(3)
        status, ctype, body = {
            "/catalog": (200, "application/json", CATALOG),
            "/bad-gateway": (502, "text/html", b"<html>502 Bad Gateway</html>"),
            "/truncated": (200, "application/json", CATALOG[:40]),
            "/slow": (200, "application/json", CATALOG),
        }[self.path]
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


@pytest.fixture(scope="module")
def server():
    srv = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{srv.server_address[1]}"
    srv.shutdown()


def _probe(url: str, max_time: int = 10) -> subprocess.CompletedProcess:
    return _bash(_probe_function() + (
        f"mcp_probe '{url}' '{{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}}' {max_time}\n"
        'printf "BODY=%s\\nDIAG=%s\\n" "$PROBE_BODY" "$PROBE_DIAG"\n'
    ))


def test_bad_lib_grep_does_not_end_the_script_on_a_body_without_the_signature():
    for body in ("", "<html>502 Bad Gateway</html>", '{"jsonrpc":"2.0","id":1,"error":{"code":-32603}}'):
        result = _bash(f"TL_RESP='{body}'\n{_bad_lib_line()}\necho \"alive BAD_LIB=[$BAD_LIB]\"\n")
        assert result.returncode == 0 and "alive BAD_LIB=[]" in result.stdout, (
            f"the BAD_LIB line ended the script on body {body!r} (exit {result.returncode}) -- the bind-check "
            "would skip its retries and every error message"
        )


def test_bad_lib_still_names_a_missing_part_method():
    body = '{"error":{"message":"No signature of method: _getAllToolDefinitions_partMcpRoomsLib()"}}'
    result = _bash(f"TL_RESP='{body}'\n{_bad_lib_line()}\necho \"BAD_LIB=[$BAD_LIB]\"\n")
    assert "BAD_LIB=[_getAllToolDefinitions_partMcpRoomsLib]" in result.stdout, result.stdout + result.stderr


def test_probe_returns_the_body_on_success(server):
    result = _probe(f"{server}/catalog")
    assert result.returncode == 0, result.stderr
    assert f"BODY={CATALOG.decode()}" in result.stdout
    assert "curl exit 0" in result.stdout and "http=200" in result.stdout


@pytest.mark.parametrize("path, expected", [
    ("/bad-gateway", ["curl exit 0", "http=502", "type=text/html", "body: <html>502 Bad Gateway</html>"]),
    ("/truncated", ["http=200", "bytes=40", f"body: {CATALOG[:40].decode()}"]),
])
def test_probe_reports_an_unusable_response(server, path, expected):
    result = _probe(f"{server}{path}")
    assert result.returncode == 0, f"mcp_probe ended the script: {result.stderr}"
    diag = result.stdout.split("DIAG=", 1)[1]
    for fragment in expected:
        assert fragment in diag, f"{fragment!r} missing from the probe diagnostic: {diag}"


def test_probe_reports_a_timeout(server):
    result = _probe(f"{server}/slow", max_time=1)
    assert result.returncode == 0, f"mcp_probe ended the script: {result.stderr}"
    diag = result.stdout.split("DIAG=", 1)[1]
    assert "curl exit 28" in diag and "http=000" in diag and "body: <empty>" in diag, diag


def test_probe_reports_a_refused_connection():
    result = _probe("http://127.0.0.1:9/mcp", max_time=5)
    assert result.returncode == 0, f"mcp_probe ended the script: {result.stderr}"
    diag = result.stdout.split("DIAG=", 1)[1]
    assert "curl exit 7" in diag and "body: <empty>" in diag, diag


LIB = SCRIPT.parent / "mcp_watchdog_lib.sh"


def _reset_function() -> str:
    match = re.search(r"^reset_runtime_stats\(\) \{\n.*?^\}\n", SCRIPT.read_text(), re.S | re.M)
    assert match, f"{SCRIPT.name} no longer defines reset_runtime_stats()"
    return match.group(0)


def _tool_answer(payload: dict) -> str:
    return json.dumps({"jsonrpc": "2.0", "id": 1, "result": {"content": [{"type": "text", "text": json.dumps(payload)}]}})


_RESET_OK = {"success": True, "appId": "38", "before": {"totalMs": 936332, "count": 10},
             "after": {"totalMs": 0, "count": 0}, "uptimeSeconds": 864000, "thresholdPct": 33,
             "limitMs": 285120000, "verified": True}


def _run_reset(tmp_path: Path, answers: list[str]) -> tuple[subprocess.CompletedProcess, int]:
    """Run the script's reset_runtime_stats against the real watchdog lib with mcp_call stubbed to
    return `answers` in order (the last one repeats); returns the result and the number of calls."""
    answers_dir = tmp_path / "answers"
    answers_dir.mkdir()
    for index, answer in enumerate(answers, 1):
        (answers_dir / str(index)).write_text(answer)
    (answers_dir / "last").write_text(answers[-1])
    calls = tmp_path / "calls"
    calls.write_text("")
    result = _bash(
        f"WD_RPC_ATTEMPTS=3; WD_RPC_RETRY_SLEEP=0\nsource '{LIB.as_posix()}'\n"
        f"mcp_call() {{ echo x >> '{calls.as_posix()}'; local n; n=$(wc -l < '{calls.as_posix()}' | tr -d ' ')\n"
        f"  if [ -f '{answers_dir.as_posix()}/'$n ]; then cat '{answers_dir.as_posix()}/'$n; "
        f"else cat '{answers_dir.as_posix()}/last'; fi; }}\n"
        + _reset_function()
        + 'rc=0; reset_runtime_stats 38 || rc=$?\necho "RC=$rc"\n'
    )
    return result, len(calls.read_text().splitlines())


def test_reset_retries_an_empty_answer_then_warns_and_continues(tmp_path):
    result, calls = _run_reset(tmp_path, [""])
    assert "RC=0" in result.stdout and calls == 3, (calls, result.stdout, result.stderr)
    assert "::warning::" in result.stdout and "no usable answer after 3 attempt(s) (empty answer)" in result.stdout


def test_reset_retries_a_busy_refusal_until_it_lands(tmp_path):
    busy = _tool_answer({"success": False, "busy": True, "error": "A manual write is still running"})
    result, calls = _run_reset(tmp_path, [busy, _tool_answer(_RESET_OK)])
    assert "RC=0" in result.stdout and calls == 2, (calls, result.stdout, result.stderr)
    assert "Runtime statistics reset for app 38: before 936332 ms, after 0 ms" in result.stdout, result.stdout


def test_reset_warns_on_a_watchdog_that_predates_the_tool(tmp_path):
    unknown = json.dumps({"jsonrpc": "2.0", "id": 1, "error": {
        "code": -32602, "message": "Invalid params: Unknown tool: hub_delete_app_runtime_stats."}})
    result, calls = _run_reset(tmp_path, [unknown])
    assert "RC=0" in result.stdout and calls == 1, (calls, result.stdout, result.stderr)
    assert "::warning::The standing watchdog predates hub_delete_app_runtime_stats" in result.stdout


def test_reset_fails_the_step_on_any_other_json_rpc_error_without_retrying(tmp_path):
    internal = json.dumps({"jsonrpc": "2.0", "id": 1, "error": {"code": -32603, "message": "Internal error: boom"}})
    result, calls = _run_reset(tmp_path, [internal])
    assert "RC=1" in result.stdout and calls == 1, (calls, result.stdout, result.stderr)
    assert "::error::Runtime-statistics reset for app 38 failed" in result.stdout and "Internal error: boom" in result.stdout


_RESET_UNANSWERED = {"success": False, "error": "GET /hub/advanced/resetAppRuntimeStats got no HTTP answer "
                     "(timeout or dead web stack), so the counters may not have reset."}


def test_reset_retries_a_reset_the_hub_did_not_answer_then_warns_and_continues(tmp_path):
    result, calls = _run_reset(tmp_path, [_tool_answer(_RESET_UNANSWERED)])
    assert "RC=0" in result.stdout and calls == 3, (calls, result.stdout, result.stderr)
    assert "::warning::" in result.stdout and "hub did not answer the reset" in result.stdout, result.stdout
    assert "::error::" not in result.stdout, result.stdout


def test_reset_lands_after_the_hub_did_not_answer_once(tmp_path):
    result, calls = _run_reset(tmp_path, [_tool_answer(_RESET_UNANSWERED), _tool_answer(_RESET_OK)])
    assert "RC=0" in result.stdout and calls == 2, (calls, result.stdout, result.stderr)
    assert "Runtime statistics reset for app 38: before 936332 ms, after 0 ms" in result.stdout, result.stdout


def test_reset_fails_the_step_on_an_explicit_refusal(tmp_path):
    refused = _tool_answer({"success": False, "error": "GET /hub/advanced/resetAppRuntimeStats answered 'no'"})
    result, calls = _run_reset(tmp_path, [refused])
    assert "RC=1" in result.stdout and calls == 1, (calls, result.stdout, result.stderr)
    assert "::error::Runtime-statistics reset for app 38 failed" in result.stdout and "answered 'no'" in result.stdout


def test_reset_reports_an_unknown_budget_without_the_short_uptime_warning(tmp_path):
    unknown_budget = dict(_RESET_OK, limitMs=None, thresholdPct=None, thresholdError="threshold read failed")
    result, _ = _run_reset(tmp_path, [_tool_answer(unknown_budget)])
    assert "RC=0" in result.stdout, result.stdout + result.stderr
    assert "busy budget unknown (threshold/uptime unreadable: threshold read failed)" in result.stdout, result.stdout
    assert "1,400,000" not in result.stdout, result.stdout


def test_reset_warns_when_uptime_is_too_short_for_a_full_run(tmp_path):
    short = dict(_RESET_OK, uptimeSeconds=3600, limitMs=1188000)
    result, _ = _run_reset(tmp_path, [_tool_answer(short)])
    assert "RC=0" in result.stdout, result.stdout + result.stderr
    assert "::warning::Busy budget after the reset is 1188000 ms (< 1,400,000 ms" in result.stdout, result.stdout


def test_reset_warns_when_the_read_back_was_unavailable(tmp_path):
    unverified = dict(_RESET_OK, verified=False, note="stats read-back failed after the reset")
    result, _ = _run_reset(tmp_path, [_tool_answer(unverified)])
    assert "RC=0" in result.stdout, result.stdout + result.stderr
    assert "::warning::Runtime-statistics reset for app 38 was accepted but not verified: " \
           "stats read-back failed after the reset" in result.stdout, result.stdout
