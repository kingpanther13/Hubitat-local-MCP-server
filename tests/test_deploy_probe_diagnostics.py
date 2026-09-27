"""pytest: the deploy's endpoint probes survive a bad response and report what came back.

Regression guard for ``.github/scripts/mcp_watchdog_deploy.sh``. Its post-deploy bind-check grepped the
``tools/list`` body for a missing-library signature; grep exits 1 on no match, so under the script's
``set -euo pipefail`` the first attempt without a catalog ended the step with a bare exit 1 -- no retry,
no error, nothing about the response. These tests run the script's own ``mcp_probe`` function and
``BAD_LIB`` line (extracted from the file, not copied) under the same shell options.
"""

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
