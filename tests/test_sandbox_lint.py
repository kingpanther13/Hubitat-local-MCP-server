"""pytest unit tests for tests/sandbox_lint.py

Migrates each SELF_TEST_CASES entry from sandbox_lint's --self-test mode into
individual parametrized pytest tests, then adds coverage for:
  - strip_comments_and_strings (comment/string stripping)
  - scan_source (rule detection)
  - format_finding / format_annotation (output formatting)

All 19 original self-test cases are preserved with zero coverage loss.
"""

import sys
import os

# sandbox_lint lives in tests/ — add that directory to the path.
sys.path.insert(0, os.path.join(os.path.dirname(__file__)))

import pytest
import sandbox_lint as sl


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def hits(source: str) -> set[str]:
    """Return the set of rule IDs that scan_source finds in the given source line."""
    findings = sl.scan_source(source, "<test>")
    return {f["rule"] for f in findings}


# ---------------------------------------------------------------------------
# Parametrized migration of all SELF_TEST_CASES (19 cases)
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("desc,source,expected", sl.SELF_TEST_CASES)
def test_self_test_case(desc, source, expected):
    """Each SELF_TEST_CASES entry: rule fires (or not) as declared."""
    rule_hits = hits(source)
    for rule_id, should_match in expected:
        if should_match:
            assert rule_id in rule_hits, (
                f"[{desc}] expected rule {rule_id} to fire, but it did not.\n"
                f"  source: {source!r}\n"
                f"  stripped: {sl.strip_comments_and_strings(source)!r}"
            )
        else:
            assert rule_id not in rule_hits, (
                f"[{desc}] expected rule {rule_id} NOT to fire, but it did.\n"
                f"  source: {source!r}\n"
                f"  stripped: {sl.strip_comments_and_strings(source)!r}"
            )


# ---------------------------------------------------------------------------
# strip_comments_and_strings — additional direct coverage
# ---------------------------------------------------------------------------

def test_strip_line_comment_blanked():
    """Line comment after code is stripped; code before // is kept."""
    stripped = sl.strip_comments_and_strings("def x = 1 // comment here")
    result = stripped[0]
    assert "def x = 1" in result
    assert "comment" not in result


def test_strip_block_comment_same_line_blanked():
    """Inline block comment on same line is replaced with spaces."""
    stripped = sl.strip_comments_and_strings("def x = /* block */ 1")
    result = stripped[0]
    assert "def x = " in result
    assert "block" not in result
    assert "1" in result


def test_strip_block_comment_multiline():
    """Block comment spanning lines: commented lines become empty."""
    source = "start\n/* comment\nstill comment\n*/\nend"
    stripped = sl.strip_comments_and_strings(source)
    assert stripped[0] == "start"
    assert stripped[1] == ""   # line inside block comment
    assert stripped[2] == ""   # line inside block comment
    assert "end" in stripped[4]


def test_strip_single_quoted_string_blanked():
    """Contents of single-quoted strings are blanked."""
    stripped = sl.strip_comments_and_strings("def s = 'hello world'")
    result = stripped[0]
    assert "hello" not in result
    assert "world" not in result


def test_strip_double_quoted_literal_blanked():
    """Literal text in a double-quoted string is blanked."""
    stripped = sl.strip_comments_and_strings('def s = "hello world"')
    result = stripped[0]
    assert "hello" not in result
    assert "world" not in result


def test_strip_gstring_interpolation_preserved():
    """${...} body inside a double-quoted string is preserved for rule scanning."""
    stripped = sl.strip_comments_and_strings('def s = "value=${foo.bar}"')
    result = stripped[0]
    # The interpolation body (foo.bar) must survive for rules to match
    assert "foo.bar" in result


def test_strip_triple_single_quoted_blanked():
    """Triple-single-quoted string content is fully blanked."""
    stripped = sl.strip_comments_and_strings("def s = '''getClass() here'''")
    result = stripped[0]
    assert "getClass" not in result


def test_strip_triple_double_quoted_interpolation_preserved():
    """${...} inside triple-double-quoted GString is preserved."""
    stripped = sl.strip_comments_and_strings('def s = """prefix ${obj.getClass()} suffix"""')
    result = stripped[0]
    assert "getClass" in result


def test_strip_preserves_line_count():
    """Output list has the same number of lines as the input."""
    source = "line1\nline2\nline3\nline4"
    stripped = sl.strip_comments_and_strings(source)
    assert len(stripped) == 4


# ---------------------------------------------------------------------------
# scan_source — rule-specific spot checks
# ---------------------------------------------------------------------------

def test_sandbox_001_getclass_bare():
    """SANDBOX-001 fires on bare getClass() call."""
    assert "SANDBOX-001" in hits("def t = obj.getClass().simpleName")


def test_sandbox_002_locale():
    """SANDBOX-002 fires on Locale.default."""
    assert "SANDBOX-002" in hits("def loc = Locale.default")


def test_sandbox_003_date_format_with_locale():
    """SANDBOX-003 fires on Date.format(String, Locale) overload."""
    assert "SANDBOX-003" in hits("def d = new Date().format('yyyy', Locale.US)")


def test_sandbox_004_log_is_enabled():
    """SANDBOX-004 fires on log.isDebugEnabled()."""
    assert "SANDBOX-004" in hits("if (log.isDebugEnabled()) { log.debug 'x' }")


def test_sandbox_005_eval():
    """SANDBOX-005 fires on Eval.me(...)."""
    assert "SANDBOX-005" in hits("def r = Eval.me('1+1')")


def test_sandbox_006_new_thread():
    """SANDBOX-006 fires on new Thread(...)."""
    assert "SANDBOX-006" in hits("def t = new Thread({ -> run() })")


def test_sandbox_007_class_for_name():
    """SANDBOX-007 fires on Class.forName(...)."""
    assert "SANDBOX-007" in hits("def c = Class.forName('java.lang.String')")


def test_sandbox_008_new_file():
    """SANDBOX-008 fires on new File(...)."""
    assert "SANDBOX-008" in hits("def f = new File('/tmp/test')")


def test_sandbox_009_runtime_exec():
    """SANDBOX-009 fires on Runtime.exec(...)."""
    assert "SANDBOX-009" in hits("Runtime.exec('ls')")


def test_sandbox_010_atomicstate_nested_mutation():
    """SANDBOX-010 fires on nested atomicState mutation (warning)."""
    result = sl.scan_source("atomicState.myMap['key'] = 'value'", "<test>")
    ids = {f["rule"] for f in result}
    assert "SANDBOX-010" in ids


def test_sandbox_011_hubaction():
    """SANDBOX-011 fires on new HubAction(...)."""
    assert "SANDBOX-011" in hits("def ha = new HubAction('...')")


def test_clean_source_no_findings():
    """Clean Groovy code produces no findings."""
    source = "def greet(String name) { log.debug 'hello' }"
    assert hits(source) == set()


# ---------------------------------------------------------------------------
# format_finding / format_annotation
# ---------------------------------------------------------------------------

def _sample_finding(severity="error", line=42):
    return {
        "file": "hubitat-mcp-server.groovy",
        "line": line,
        "rule": "SANDBOX-001",
        "message": "getClass() blocked in Hubitat sandbox",
        "severity": severity,
        "source": "def t = obj.getClass()",
    }


def test_format_finding_contains_rule_and_message():
    """format_finding output includes the rule ID and message text."""
    text = sl.format_finding(_sample_finding())
    assert "SANDBOX-001" in text
    assert "getClass() blocked" in text


def test_format_finding_contains_file_and_line():
    """format_finding output includes the file:line reference."""
    text = sl.format_finding(_sample_finding())
    assert "hubitat-mcp-server.groovy:42" in text


def test_format_finding_includes_source_snippet():
    """format_finding output includes the source code snippet."""
    text = sl.format_finding(_sample_finding())
    assert "obj.getClass()" in text


def test_format_finding_zero_line_omits_line_number():
    """When line=0 (version checks), format_finding shows just the filename."""
    finding = _sample_finding(line=0)
    text = sl.format_finding(finding)
    assert ":0" not in text
    assert "hubitat-mcp-server.groovy" in text


def test_format_finding_warning_severity():
    """Warning-severity findings include 'WARNING' in output."""
    finding = _sample_finding(severity="warning")
    text = sl.format_finding(finding)
    assert "WARNING" in text


def test_format_annotation_error():
    """format_annotation produces a ::error:: annotation for error severity."""
    text = sl.format_annotation(_sample_finding())
    assert text.startswith("::error")
    assert "SANDBOX-001" in text
    assert "hubitat-mcp-server.groovy" in text


def test_format_annotation_warning():
    """format_annotation produces a ::warning:: annotation for warning severity."""
    finding = _sample_finding(severity="warning")
    text = sl.format_annotation(finding)
    assert text.startswith("::warning")


def test_format_annotation_line_number_present():
    """format_annotation includes the line number when line > 0."""
    text = sl.format_annotation(_sample_finding(line=10))
    assert "line=10" in text


def test_format_annotation_zero_line_omits_line_part():
    """format_annotation omits the line part when line=0."""
    text = sl.format_annotation(_sample_finding(line=0))
    assert "line=" not in text
