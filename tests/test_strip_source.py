"""pytest: tools/strip_source.py drops comments and blank lines and never edits string literals."""

import importlib.util
import sys
import textwrap
from pathlib import Path

_TOOLS = Path(__file__).resolve().parent.parent / "tools"
sys.path.insert(0, str(_TOOLS))
_spec = importlib.util.spec_from_file_location("strip_source", _TOOLS / "strip_source.py")
strip_source = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(strip_source)
lex = strip_source.lex
strip = strip_source.strip


def stream(src):
    toks = lex(src)
    return [(t.kind, t.text, t.nl and i > 0) for i, t in enumerate(toks)]


SOURCE = textwrap.dedent('''\
    /**
     * Demo app
     *
     * Version: 1.2.3
     */

    // a comment-only line
    definition(name: "Demo", namespace: "demo") // trailing comment

    /* block
       comment */
    def f(List xs) {
        def url = "http://example.com/*not-a-comment*/"   // real comment
        def s = 'a // b /* c */ d'
        def g = "${xs.collect { "{" + it + "}" }.join('//')} // still string"
        def t = """first line

    // inside a triple-quoted string, kept
    /* also kept */
        """
        def u = \'\'\'single

        triple\'\'\'
        def r = ~/https?:\\/\\/[^\\/]+/   /* inline */ ; def z = 1
        def d = $/dollar // slashy/$
        def a = 1 /* inline block */ + 2


        return a
    }
    ''')


def test_comments_and_blank_lines_are_removed_strings_kept():
    out = strip(SOURCE)
    assert "comment-only" not in out
    assert "trailing comment" not in out
    assert "block\n" not in out
    assert "real comment" not in out
    assert "inline block" not in out
    for literal in [
        '"http://example.com/*not-a-comment*/"',
        "'a // b /* c */ d'",
        "\"${xs.collect { \"{\" + it + \"}\" }.join('//')} // still string\"",
        '"""first line\n\n// inside a triple-quoted string, kept\n/* also kept */\n    """',
        "'''single\n\n    triple'''",
        r"~/https?:\/\/[^\/]+/",
        "$/dollar // slashy/$",
    ]:
        assert literal in out
    assert "\n\n    return" not in out
    assert "def a = 1   + 2" in out


def test_header_and_definition_line_are_kept_and_indentation_untouched():
    out = strip(SOURCE)
    assert out.startswith("/**\n * Demo app\n *\n * Version: 1.2.3\n */\ndefinition(")
    assert '\ndefinition(name: "Demo", namespace: "demo")\ndef f(List xs) {\n    def url' in out


def test_library_line_stays_first():
    src = 'library(name: "L", namespace: "n")\n\n// helper\ndef h() { 1 }\n'
    assert strip(src) == 'library(name: "L", namespace: "n")\ndef h() { 1 }\n'


def test_token_stream_and_line_breaks_are_unchanged():
    out = strip(SOURCE)
    assert stream(out) == stream(SOURCE)


def test_inline_block_comment_never_merges_tokens():
    assert strip("def f() {\n    a/*x*/b\n}\n") == "def f() {\n    a b\n}\n"
    out = strip("def f() {\n    a /* x\n    y */ b\n}\n")
    assert out == "def f() {\n    a\n b\n}\n"  # the newline inside the comment survives


def test_idempotent():
    once = strip(SOURCE)
    assert strip(once) == once
