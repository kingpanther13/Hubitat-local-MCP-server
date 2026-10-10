"""pytest: tools/build-release-app.py inlines every library exactly once and strips without changing tokens."""

import importlib.util
import re
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
from groovy_lex import lex  # noqa: E402 -- tools/ is put on sys.path above

_spec = importlib.util.spec_from_file_location("build_release_app", ROOT / "tools" / "build-release-app.py")
bra = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(bra)

HEADER = "/**\n * Demo\n *\n * Version: 1.2.3\n */\n"


def _tokens(src):
    return [(t.kind, t.text) for t in lex(src)]


def _repo(tmp_path, parent, libraries, child='definition(name: "Child")\ndef c() { 1 }\n'):
    (tmp_path / bra.PARENT).write_text(parent, newline="")
    (tmp_path / bra.CHILD).write_text(child, newline="")
    lib_dir = tmp_path / "libraries"
    lib_dir.mkdir()
    for filename, text in libraries.items():
        (lib_dir / filename).write_text(text, newline="")
    return tmp_path


def _lib(name, body, ns="mcp"):
    return f'library(name: "{name}", namespace: "{ns}", author: "x", description: "has (parens)")\n{body}'


def test_include_is_replaced_at_its_site_by_the_body_without_its_declaration(tmp_path):
    parent = HEADER + 'definition(name: "P")\n\n// libraries\n#include mcp.A\n  #include other.B  \ndef tail() { 2 }\n'
    root = _repo(tmp_path, parent, {
        # Resolution is by the declared (namespace, name), not the file name.
        "zz.groovy": _lib("A", "\n// a helper\ndef a() { 'A' }\n\n"),
        "b-lib.groovy": _lib("B", "def b() { \"// kept\" }\n", ns="other"),
    })
    out = bra.build(root)[bra.PARENT]
    assert out == HEADER + 'definition(name: "P")\ndef a() { \'A\' }\ndef b() { "// kept" }\ndef tail() { 2 }\n'
    assert "library(" not in out


@pytest.mark.parametrize(
    ("parent", "libraries", "error"),
    [
        ("#include mcp.A\n#include mcp.Missing\n", {"a.groovy": _lib("A", "")}, "#include mcp.Missing has no"),
        ("#include mcp.A\n#include mcp.A\n", {"a.groovy": _lib("A", "")}, "appears more than once"),
        ("#include mcp.A\n", {"a.groovy": _lib("A", ""), "b.groovy": _lib("B", "")}, "no app #includes: libraries/b.groovy (mcp.B)"),
        ("#include mcp.A\n", {"a.groovy": _lib("A", ""), "b.groovy": _lib("A", "")}, "both declare mcp.A"),
        ("#include mcp.A\n", {"a.groovy": "// note\n" + _lib("A", "")}, "libraries/a.groovy: line 1 is not a library"),
    ],
)
def test_lockstep_violations_fail_the_build(tmp_path, parent, libraries, error):
    with pytest.raises(bra.BuildError, match=re.escape(error)):
        bra.build(_repo(tmp_path, parent, libraries))


def test_main_writes_both_files_and_reports_errors(tmp_path, capsys):
    root = _repo(tmp_path, HEADER + "#include mcp.A\n", {"a.groovy": _lib("A", "def a() {}\n")})
    assert bra.main(["--root", str(root)]) == 0
    assert (root / "dist" / bra.PARENT).read_text() == HEADER + "def a() {}\n"
    assert (root / "dist" / bra.CHILD).read_text() == 'definition(name: "Child")\ndef c() { 1 }\n'
    (root / "libraries" / "b.groovy").write_text(_lib("B", ""))
    assert bra.main(["--root", str(root)]) == 1
    assert "no app #includes" in capsys.readouterr().err


def test_output_is_deterministic_and_line_ending_independent(tmp_path):
    parent = HEADER + "#include mcp.A\ndef p() {\n    // c\n    1\n}\n"
    lf = bra.build(_repo(tmp_path / "lf", parent, {"a.groovy": _lib("A", "def a() {}\n")}))
    crlf = bra.build(_repo(tmp_path / "crlf", parent.replace("\n", "\r\n"),
                           {"a.groovy": _lib("A", "def a() {}\n").replace("\n", "\r\n")}))
    assert lf == crlf
    assert lf == bra.build(tmp_path / "lf")


@pytest.fixture(scope="module")
def real():
    index = bra.index_libraries(ROOT / "libraries")
    source = bra._read(ROOT / bra.PARENT)
    return index, source, bra.inline(source, index, set(), bra.PARENT), bra.build(ROOT)


def test_real_repo_inlines_every_library_body_exactly_once(real):
    index, _, inlined, built = real
    assert len(index) == len(list((ROOT / "libraries").glob("*.groovy")))
    for filename, body in index.values():
        assert inlined.count(body) == 1, filename
    for text in built.values():
        assert not re.search(r"(?m)^[ \t]*#include\b", text)
        assert not re.search(r"(?m)^library\s*\(", text)


def test_real_repo_build_keeps_header_definition_and_every_token(real):
    _, source, inlined, built = real
    parent = built[bra.PARENT]
    header = source[: source.index("*/") + 2]
    assert parent.startswith(header + "\n")
    assert re.search(r"(?m)^ \* Version: \S", parent)
    assert re.search(r"(?m)^definition\(", parent)
    assert "\n\n" not in parent
    assert _tokens(parent) == _tokens(inlined)
    child_source = bra._read(ROOT / bra.CHILD)
    assert _tokens(built[bra.CHILD]) == _tokens(child_source)
