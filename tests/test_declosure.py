"""pytest: tools/declosure.py rewrites only the closure iterations that are provably loops."""

import importlib.util
import textwrap
from pathlib import Path

import pytest

_PATH = Path(__file__).resolve().parent.parent / "tools" / "declosure.py"
_spec = importlib.util.spec_from_file_location("declosure", _PATH)
declosure = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(declosure)


def run(src):
    rw, out = declosure.process("t.groovy", textwrap.dedent(src))
    return out, rw


def same(src):
    out, rw = run(src)
    assert out == textwrap.dedent(src)
    return rw


def test_named_each_becomes_for_in():
    out, rw = run("""
        void f(List xs) {
            xs.each { x ->
                log.info x
            }
        }
    """)
    assert "for (x in xs) {\n        log.info x\n    }" in out
    assert rw.done["each"] == 1


def test_implicit_each_uses_it():
    out, _ = run("""
        void f(List xs) {
            xs.each { log.info it }
        }
    """)
    assert "for (it in xs) { log.info it }" in out


def test_receiver_chain_and_safe_navigation():
    out, _ = run("""
        void f(Map a) {
            (a?.b ?: []).findAll { it }.each { y -> g(y) }
            a?.c?.each { z -> g(z) }
        }
    """)
    assert "for (y in (a?.b ?: []).findAll { it }) { g(y) }" in out
    assert "for (z in a?.c) { g(z) }" in out


def test_bare_return_becomes_continue_but_nested_closure_return_does_not():
    out, _ = run("""
        void f(List xs) {
            xs.each { x ->
                if (!x) return
                def y = x.findAll { if (it) return true; false }
                switch (x) { case 1: return }
                g(y)
            }
        }
    """)
    assert "if (!x) continue" in out
    assert "case 1: continue" in out
    assert "if (it) return true" in out


@pytest.mark.parametrize(
    ("body", "reason"),
    [
        ("if (x) return 5", "return with a value"),
        ("for (y in x) { if (y) return }", "return inside a nested loop"),
        ("while (x) g()", "braceless nested loop"),
        ("g(it)", "outer `it`"),
        ("def later = { g(x) }", "may run later"),
    ],
)
def test_unsafe_bodies_are_skipped(body, reason):
    rw = same(f"""
        void f(List xs) {{
            xs.each {{ x ->
                {body}
            }}
        }}
    """)
    assert reason in rw.skipped[0][2]


def test_eager_nested_closure_may_capture_loop_variable():
    out, _ = run("""
        void f(List xs, List ys) {
            xs.each { x -> g(ys.findAll { it == x }) }
        }
    """)
    assert "for (x in xs) { g(ys.findAll { it == x }) }" in out


def test_two_param_each_reads_map_entries():
    out, _ = run("""
        void f(Map m) {
            m.each { k, v ->
                g(k, v)
            }
            m.each { k, v -> g(k, v) }
        }
    """)
    assert "for (Map.Entry entry in m) {\n        def k = entry.key\n        def v = entry.value\n        g(k, v)" in out
    assert "for (Map.Entry entry in m) { def k = entry.key; def v = entry.value; g(k, v) }" in out


def test_entry_name_avoids_names_used_in_method():
    out, _ = run("""
        void f(Map m, entry) {
            m.each { k, v -> g(k, v, entry) }
        }
    """)
    assert "for (Map.Entry e in m) {" in out


def test_each_with_index_counts_from_minus_one():
    out, rw = run("""
        void f(List xs) {
            xs.eachWithIndex { x, i ->
                if (!x) return
                g(x, i)
            }
        }
    """)
    assert "int i = -1\n    for (x in xs) {\n        i++\n        if (!x) continue" in out
    assert rw.done["eachWithIndex"] == 1


def test_each_with_index_skips_reused_index_name():
    rw = same("""
        void f(List xs) {
            xs.eachWithIndex { x, i -> g(x, i) }
            for (int i = 0; i < 2; i++) g(i)
        }
    """)
    assert "used elsewhere" in rw.skipped[0][2]


def test_times_with_literal_and_local_bound():
    out, _ = run("""
        void f() {
            int n = 3
            2.times { g() }
            n.times { k -> g(k) }
        }
    """)
    assert "for (int i = 0; i < 2; i++) { g() }" in out
    assert "for (int k = 0; k < n; k++) { g(k) }" in out


def test_times_with_computed_bound_is_skipped():
    rw = same("""
        void f(List xs) {
            xs.size().times { g() }
        }
    """)
    assert "times receiver" in rw.skipped[0][2]


@pytest.mark.parametrize(
    "src",
    [
        "def f(List xs) {\n    def r = xs.each { g(it) }\n    r\n}\n",
        "def f(List xs) {\n    xs.each { g(it) }.size()\n}\n",
        "def f(List xs) {\n    xs.each { g(it) }\n}\n",  # implicit return value of a def method
        "def f(List xs) {\n    return xs.each { g(it) }\n}\n",
        "def f(List xs) {\n    h(xs.each { g(it) })\n    1\n}\n",
        "def f(List xs) {\n    def c = { xs.each { y -> g(y) } }\n    c\n}\n",  # last statement of a closure
        "def f(List xs) {\n    if (xs) {\n        xs.each { g(it) }\n    }\n}\n",  # if as the method's value
        "def f(List xs) {\n    if (xs) xs.each { g(it) }\n    1\n}\n",  # braceless if body
    ],
)
def test_used_values_are_left_alone(src):
    out, rw = run(src)
    assert out == src
    assert rw.skipped


def test_tail_of_void_method_and_of_if_followed_by_statement_is_rewritten():
    out, _ = run("""
        void f(List xs) {
            xs.each { g(it) }
        }
        def h(List xs) {
            if (xs) {
                xs.each { g(it) }
            }
            1
        }
    """)
    assert out.count("for (it in xs)") == 2


def test_it_loop_is_not_nested_in_an_implicit_it_scope():
    out, rw = run("""
        void f(List xs) {
            xs.each { it.ys.each { y -> g(y) }; it.zs.each { g(it) }; h() }
            xs.collect { it.ys.each { g(it) }; 1 }
        }
    """)
    assert "for (it in xs) { for (y in it.ys) { g(y) }; it.zs.each { g(it) }; h() }" in out
    assert "xs.collect { it.ys.each { g(it) }; 1 }" in out
    assert sum("`it` is already bound" in r for _, _, r in rw.skipped) == 2


def test_receiver_mentioning_the_loop_name_is_skipped():
    rw = same("""
        void f(List xs) {
            xs.findAll { it != x }.each { x -> g(x) }
        }
    """)
    assert "receiver refers" in rw.skipped[0][2]


def test_typed_and_multiline_receivers_are_skipped():
    rw = same("""
        void f(List xs) {
            xs.each { String s -> g(s) }
            xs
                .findAll { it }
                .each { y -> g(y) }
        }
    """)
    reasons = sorted(r for _, _, r in rw.skipped)
    assert reasons == ["multi-line receiver", "typed or defaulted closure parameter"]


def test_strings_and_comments_with_braces_and_each_are_untouched():
    src = """
        void f(List xs) {
            def a = "xs.each { y -> } ${xs.collect { "}" }.size()}"
            def b = 'xs.each { y -> }'
            def c = '''xs.each { y -> }
            }'''
            def d = \"\"\"xs.each { ${xs.size()} }\"\"\"
            def e = /xs.each \\{ y -> \\}/
            def m = "a" ==~ /\\d+{/
            // xs.each { y -> }
            /* xs.each { y -> } */
            xs.each { y -> g("}", '{', /}/, y) }
        }
    """
    out, rw = run(src)
    assert out == textwrap.dedent(src).replace(
        "xs.each { y -> g(", "for (y in xs) { g("
    )
    assert rw.done["each"] == 1


def test_gstring_interpolation_is_scanned_for_it():
    rw = same("""
        void f(List xs) {
            xs.each { x -> g("${it}") }
        }
    """)
    assert "outer `it`" in rw.skipped[0][2]
    out, _ = run("""
        void f(List xs) {
            xs.each { x -> g("${x.collect { it }}") }
        }
    """)
    assert "for (x in xs)" in out


def test_nested_rewrites_and_idempotence():
    src = """
        void f(Map m, List xs) {
            xs.each { x ->
                if (!x) return
                m.each { k, v ->
                    if (!v) return
                    g(x, k, v)
                }
                x.eachWithIndex { z, n -> g(z, n) }
            }
        }
    """
    once, _ = run(src)
    assert "for (x in xs) {" in once
    assert "for (Map.Entry entry in m) {" in once
    assert "int n = -1" in once
    assert once.count("continue") == 2
    twice, rw = declosure.process("t.groovy", once)
    assert twice == once
    assert not rw.done


def test_count_closures():
    toks = declosure.lex('def f() { if (a) { b.each { c } } else { d.collect { "${e.find { 1 }}" } } }')
    assert declosure.count_closures(toks) == 3

