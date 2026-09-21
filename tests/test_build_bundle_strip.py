"""pytest: the bundle builder blanks whole-line `//` comments and nothing else (issue #451).

Libraries ship to hubs with their developer comments blanked -- those comments were ~30% of the bytes
the hub's Libraries Code page and /hub2/userLibraries have to serve, ~43% of the largest library, and
Groovy discards them at compile time. The transform must be exactly "blank a line that is only a
comment and sits outside a triple-quoted string, then add one notice line": every code line, every inline trailing comment and every line inside a string stays
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


def test_a_protected_comment_does_not_suppress_later_blanking(tmp_path):
    """A kept comment holding one marker must not leave the tracker inside a string: while it did,
    one stray marker forfeited every blanking in the rest of the file."""
    text = ('library(name: "X")\n// mentions """ once\ndef d = """desc"""\n'
            "// blank me\ndef a = 1\n")
    shipped = builder.prepare_library_source(_write(tmp_path, text)).split("\n")
    assert shipped[2] == '// mentions """ once'
    assert shipped[4] == "", f"later comment was not blanked: {shipped[4]!r}"


def test_protection_does_not_spare_comments_between_two_descriptions(tmp_path):
    """The veto must stay narrow: a comment sitting between two complete descriptions is
    outside both and still gets blanked."""
    text = 'library(name: "X")\ndef a = """one"""\n// blank me\ndef b = """two"""\n'
    shipped = builder.prepare_library_source(_write(tmp_path, text)).split("\n")
    assert shipped[3] == ""


def test_no_real_library_carries_a_protected_comment_line():
    """The regex veto pairs markers across the WHOLE file, so one unpaired marker in a new
    comment re-phases every later pairing and quietly keeps thousands of comments. No library
    trips it today; this pins that, because the saving would fall without any test failing."""
    for lib in builder.LIBS:
        text = lib["source"].read_text(encoding="utf-8").replace("\r\n", "\n")
        protected = builder._triple_quote_protected_lines(text)
        kept = [n for n, line in enumerate(text.split("\n"), 1)
                if line.lstrip().startswith("//") and n - 1 in protected]
        assert not kept, (
            f"{lib['source'].name}: comment lines {kept[:5]} sit inside a triple-quoted region. "
            "A stray marker in a comment re-phases the pairing and forfeits the size win -- "
            "reword the comment that introduced the marker.")


def test_the_package_still_ships_substantially_less_than_it_stores():
    """The point of the transform. `test_real_libraries_actually_shrink` is satisfied by one
    blanked line; this holds the aggregate, which is ~69.5% today."""
    source = sum(len(lib["source"].read_text(encoding="utf-8").replace("\r\n", "\n"))
                 for lib in builder.LIBS)
    shipped = sum(len(builder.prepare_library_source(lib["source"])) for lib in builder.LIBS)
    assert shipped / source < 0.75, (
        f"shipped {shipped:,} chars of {source:,} ({shipped / source:.1%}) -- the comment strip "
        "has largely stopped working; check for a stray triple-quote marker in a comment.")


def test_the_builder_refuses_a_multi_line_slashy_string(tmp_path):
    """A `//` line inside a multi-line Groovy slashy string (/.../) is string CONTENT, and
    neither scan reads it: it would be blanked and the string check would not notice. No
    library writes one, so the builder refuses the form rather than ship broken Groovy."""
    text = 'library(name: "X")\ndef re = /^abc\n// part of the string\nxyz/\n'
    with pytest.raises(RuntimeError, match="slashy string that does not close"):
        builder.prepare_library_source(_write(tmp_path, text))


def test_the_builder_refuses_a_multi_line_dollar_slashy_string(tmp_path):
    text = 'library(name: "X")\ndef re = $/^abc\n// part of the string\nxyz/$\n'
    with pytest.raises(RuntimeError, match="dollar-slashy string that does not close"):
        builder.prepare_library_source(_write(tmp_path, text))


def test_the_builder_refuses_an_escaped_triple_quote_marker(tmp_path):
    """Every scan here pairs markers raw, so an escaped marker would close a pairing early
    and a later string line could be blanked AND accepted by the same flawed reading."""
    text = 'library(name: "X")\ndef d = "a \\""" b"\ndef a = 1\n'
    with pytest.raises(RuntimeError, match="escapes a triple-quote marker"):
        builder.prepare_library_source(_write(tmp_path, text))


def test_a_single_line_slashy_regex_is_not_refused(tmp_path):
    """The real libraries are full of these, including ones ending in the `$` anchor, and
    prose like "health:/success:" inside a comment must not read as an opener."""
    text = ('library(name: "X")\ndef a = s.replaceAll(/ +$/, "")\n'
            'def b = (v =~ /^(\\d+)([mhd])$/)\n// populates health:/success: in the envelope\n')
    shipped = builder.prepare_library_source(_write(tmp_path, text))
    assert 'replaceAll(/ +$/, "")' in shipped


def test_real_libraries_pass_the_unsupported_form_check():
    for lib in builder.LIBS:
        text = lib["source"].read_text(encoding="utf-8").replace("\r\n", "\n")
        builder._reject_unsupported_string_forms(lib["source"].name, text)


def test_declaration_end_rejects_a_file_that_does_not_open_with_the_declaration():
    with pytest.raises(RuntimeError, match="does not open with a library"):
        builder._declaration_end(["// a header comment", 'library(name: "X")'])


def test_declaration_end_rejects_an_unterminated_declaration():
    with pytest.raises(RuntimeError, match="unterminated"):
        builder._declaration_end(["library(", '    name: "X",'])


def test_declaration_end_rejects_an_empty_file():
    with pytest.raises(RuntimeError, match="empty"):
        builder._declaration_end(["", "   "])
