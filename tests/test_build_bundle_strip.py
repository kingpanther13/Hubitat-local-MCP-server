"""pytest: the bundle builder blanks whole-line `//` comments and nothing else (issue #451).

Libraries ship to hubs with their developer comments blanked -- those comments were ~40% of the bytes
the hub's Libraries Code page and /hub2/userLibraries have to serve, and Groovy discards them at
compile time. The transform must be exactly "blank a line that is only a comment, then add one notice
line": every code line, every inline trailing comment and every line inside a string stays
byte-identical, and the line count is preserved so a hub line number still maps to the repo file.
`tools/build-bundle.py` re-checks that per library at build time; these tests pin the checker itself,
including the case it exists to catch -- a `//` line inside a tool description.
"""

import importlib.util
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parent.parent
BUILDER = REPO_ROOT / "tools" / "build-bundle.py"


def _load_builder():
    spec = importlib.util.spec_from_file_location("build_bundle", BUILDER)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


builder = _load_builder()

SAMPLE = '''library(name: "McpSampleLib", namespace: "mcp", author: "x", description: "y (parens) here")
// whole-line comment at column 0
def foo() {
    // indented whole-line comment
    def url = "http://example.test/path"  // trailing comment stays
    def other = 1
    return url  //// four slashes is still a trailing comment
}
\t// tab-indented whole-line comment
'''

STRIPPED = '''library(name: "McpSampleLib", namespace: "mcp", author: "x", description: "y (parens) here")

def foo() {

    def url = "http://example.test/path"  // trailing comment stays
    def other = 1
    return url  //// four slashes is still a trailing comment
}

'''


def _write(tmp_path, text, name="mcp-sample-lib.groovy"):
    src = tmp_path / name
    src.write_text(text, encoding="utf-8")
    return src


def test_strip_blanks_only_whole_line_comments():
    assert builder.strip_comment_lines(SAMPLE) == STRIPPED


def test_strip_preserves_line_count():
    assert builder.strip_comment_lines(SAMPLE).count("\n") == SAMPLE.count("\n")


def test_strip_leaves_comment_lines_inside_a_triple_quoted_string():
    text = 'def d = """Line one.\n// inside a string\n    // also inside\nDone."""\n'
    assert builder.strip_comment_lines(text) == text


def test_notice_goes_below_the_library_declaration(tmp_path):
    """Never above it: the hub parses that declaration server-side and we have no evidence it
    tolerates a line before it."""
    shipped = builder.prepare_library_source(_write(tmp_path, SAMPLE)).split("\n")
    assert shipped[0].startswith("library(")
    assert shipped[1].startswith("// Developer comments are blanked")
    assert shipped[1].endswith("/libraries/mcp-sample-lib.groovy")
    assert "\n".join(shipped[:1] + shipped[2:]) == STRIPPED


def test_notice_clears_a_multi_line_library_declaration(tmp_path):
    text = 'library(\n    name: "McpSampleLib",\n    description: "has ) a paren in a string"\n)\n// c\ndef f() { }\n'
    shipped = builder.prepare_library_source(_write(tmp_path, text)).split("\n")
    assert shipped[3] == ")"
    assert shipped[4].startswith("// Developer comments are blanked")


def test_prepare_normalizes_crlf(tmp_path):
    shipped = builder.prepare_library_source(_write(tmp_path, SAMPLE.replace("\n", "\r\n")))
    assert "\r" not in shipped


def test_verify_rejects_a_blanked_line_inside_a_string(tmp_path):
    """The one input that could silently lose content: a description line that starts with `//`.
    The builder must fail instead of shipping a truncated tool description."""
    src = _write(tmp_path, 'library(name: "X")\ndef d = """desc\n// looks like a comment\n"""\n')
    corrupted = 'library(name: "X")\n// notice\ndef d = """desc\n\n"""\n'
    with pytest.raises(RuntimeError, match="string content changed"):
        builder.verify_library_transform(src, corrupted)


def test_verify_rejects_a_dropped_code_line(tmp_path):
    src = _write(tmp_path, 'library(name: "X")\ndef a = 1\ndef b = 2\n')
    with pytest.raises(RuntimeError, match="neither the source line"):
        builder.verify_library_transform(src, 'library(name: "X")\n// notice\ndef a = 1\n\n')


def test_verify_rejects_a_changed_line_count(tmp_path):
    src = _write(tmp_path, 'library(name: "X")\ndef a = 1\n')
    with pytest.raises(RuntimeError, match="expected exactly one added notice line"):
        builder.verify_library_transform(src, 'library(name: "X")\n// notice\n')


def test_verify_accepts_what_the_builder_produces():
    for lib in builder.LIBS:
        builder.verify_library_transform(lib["source"], builder.prepare_library_source(lib["source"]))


def test_real_libraries_actually_shrink():
    for lib in builder.LIBS:
        original = lib["source"].read_text(encoding="utf-8")
        assert len(builder.prepare_library_source(lib["source"])) < len(original), lib["source"].name


def test_real_libraries_keep_their_declaration_first():
    for lib in builder.LIBS:
        shipped = builder.prepare_library_source(lib["source"]).split("\n")
        assert shipped[0].startswith("library("), lib["source"].name


def test_a_comment_that_contains_triple_quotes_is_left_alone(tmp_path):
    """`// \"\"\" example \"\"\"` must survive: blanking it would delete a pair of markers the
    build's string-content check reads, failing the build on a legitimate comment."""
    text = 'library(name: "X")\n// example: """ inline """\ndef a = 1\n'
    src = _write(tmp_path, text)
    shipped = builder.prepare_library_source(src)
    assert '// example: """ inline """' in shipped
    builder.verify_library_transform(src, shipped)


def test_a_comment_holding_one_marker_is_left_alone(tmp_path):
    """One unpaired marker in a comment pairs with the next real one, so every line between
    them is protected rather than risked."""
    text = 'library(name: "X")\n// mentions """ once\ndef d = """desc"""\n'
    src = _write(tmp_path, text)
    shipped = builder.prepare_library_source(src)
    assert '// mentions """ once' in shipped
    builder.verify_library_transform(src, shipped)


def test_protection_does_not_spare_comments_between_two_descriptions(tmp_path):
    """The veto must stay narrow: a comment sitting between two complete descriptions is
    outside both and still gets blanked."""
    text = 'library(name: "X")\ndef a = """one"""\n// blank me\ndef b = """two"""\n'
    shipped = builder.prepare_library_source(_write(tmp_path, text)).split("\n")
    assert shipped[3] == ""
