#!/usr/bin/env python3
"""Rewrite statement-level Groovy closure iterations into plain loops (issue #522).

Every closure literal compiles to its own JVM class on the hub, so a closure that is only an
iteration body is pure compile-time and Metaspace cost. This tool rewrites the provably
equivalent cases and leaves everything else alone:

  <expr>.each { x -> BODY }             ->  for (x in <expr>) { BODY }
  <expr>.each { BODY }                  ->  for (it in <expr>) { BODY }
  <expr>.each { k, v -> BODY }          ->  for (Map.Entry e in <expr>) { def k = e.key; def v = e.value; BODY }
  <expr>.eachWithIndex { x, i -> BODY } ->  int i = -1; for (x in <expr>) { i++; BODY }
  n.times { BODY }                      ->  for (int i = 0; i < n; i++) { BODY }

A bare `return` at the closure's own level becomes `continue`. Anything that is not provably
equivalent is skipped and reported: the call's value is used (not a statement, chained, or the
implicit return value of its method/closure), a `return` carries a value or sits inside a nested
loop, a nested closure that may run later captures the loop variable, a generated name could
collide with another declaration, typed or defaulted closure parameters, multi-line receivers.

Usage: declosure.py [--check] [--quiet] FILE...
  --check  print the summary and exit 1 if anything would change; files are not written.
"""
import argparse
import pathlib
import re
import sys
from collections import Counter

KEYWORDS = {
    "as", "assert", "break", "case", "catch", "class", "const", "continue", "def", "default",
    "do", "else", "enum", "extends", "false", "finally", "for", "goto", "if", "implements",
    "import", "in", "instanceof", "interface", "new", "null", "package", "return", "super",
    "switch", "this", "throw", "throws", "trait", "true", "try", "while", "void",
    "boolean", "byte", "char", "short", "int", "long", "float", "double",
}
VALUE_KEYWORDS = {"this", "super", "null", "true", "false"}
CONTROL_HEADERS = {"if", "for", "while", "switch", "catch", "synchronized"}
# Methods that run their closure argument before returning; a nested closure passed to one of
# these cannot observe a later value of a captured loop variable.
EAGER = {
    "each", "eachWithIndex", "collect", "collectEntries", "collectMany", "findAll", "find",
    "findResult", "findResults", "findIndexOf", "findIndexValues", "any", "every", "count",
    "sum", "max", "min", "sort", "toSorted", "groupBy", "countBy", "inject", "unique",
    "toUnique", "removeAll", "retainAll", "takeWhile", "dropWhile", "times", "upto", "downto",
    "step", "with", "split", "eachLine", "eachMatch", "replaceAll", "sortBy", "minBy", "maxBy",
}
ASSIGN_OPS = {"=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=", ">>>=", "**=", "++", "--"}
OPS = sorted(
    """>>>= ..< <=> ==~ >>> <<= >>= ?. *. .& .@ .. -> =~ == != <= >= && || << ++ -- += -= *= /=
    %= &= |= ^= ** ?: ::""".split(),
    key=len,
    reverse=True,
)
_SKIP_RE = re.compile(r"(?:[ \t\r\f]+|\n|//[^\n]*|/\*.*?\*/)+", re.S)
_ID_RE = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*")
_NUM_RE = re.compile(r"0[xX][0-9a-fA-F_]+[lLgGiI]?|\d[\d_]*(?:\.\d[\d_]*)?(?:[eE][+-]?\d+)?[lLgGiIdDfF]?")


class Tok:
    __slots__ = ("end", "inner", "kind", "nl", "start", "text")

    def __init__(self, kind, text, start, end, nl, inner=None):
        self.kind, self.text, self.start, self.end, self.nl, self.inner = kind, text, start, end, nl, inner

    def __repr__(self):
        return f"Tok({self.kind},{self.text!r})"


class LexError(Exception):
    pass


def _slashy_allowed(prev):
    if prev is None:
        return True
    if prev.kind in ("num", "str"):
        return False
    if prev.kind == "id":
        return prev.text in KEYWORDS and prev.text not in VALUE_KEYWORDS
    return prev.text not in (")", "]", "}", "++", "--")


def lex(src, pos=0, interp=False):
    """Tokenize src from pos. With interp=True, stop at the `}` closing a `${` interpolation
    and return (tokens, index of that brace)."""
    toks = []
    depth = 0
    n = len(src)
    nl = False
    while pos < n:
        m = _SKIP_RE.match(src, pos)
        if m:
            if "\n" in m.group():
                nl = True
            pos = m.end()
            continue
        c = src[pos]
        prev = toks[-1] if toks else None
        if c in "\"'":
            tok_end, inner = _lex_quoted(src, pos)
            toks.append(Tok("str", src[pos:tok_end], pos, tok_end, nl, inner))
            pos = tok_end
        elif c == "$" and src.startswith("$/", pos):
            tok_end, inner = _lex_dollar_slashy(src, pos)
            toks.append(Tok("str", src[pos:tok_end], pos, tok_end, nl, inner))
            pos = tok_end
        elif c == "/" and _slashy_allowed(prev) and not src.startswith("/=", pos):
            tok_end, inner = _lex_slashy(src, pos)
            toks.append(Tok("str", src[pos:tok_end], pos, tok_end, nl, inner))
            pos = tok_end
        elif c.isalpha() or c in "_$":
            m = _ID_RE.match(src, pos)
            toks.append(Tok("id", m.group(), pos, m.end(), nl))
            pos = m.end()
        elif c.isdigit():
            m = _NUM_RE.match(src, pos)
            toks.append(Tok("num", m.group(), pos, m.end(), nl))
            pos = m.end()
        else:
            for op in OPS:
                if src.startswith(op, pos):
                    break
            else:
                op = c
            if interp:
                if op == "{":
                    depth += 1
                elif op == "}":
                    if depth == 0:
                        return toks, pos
                    depth -= 1
            toks.append(Tok("op", op, pos, pos + len(op), nl))
            pos += len(op)
        nl = False
    if interp:
        raise LexError("unterminated ${} interpolation")
    return toks


def _interp(src, pos, inner):
    """At a `$` inside an interpolating string: consume `${...}` or `$name.name`."""
    if src.startswith("${", pos):
        toks, close = lex(src, pos + 2, interp=True)
        inner.append(toks)
        return close + 1
    m = re.compile(r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*").match(src, pos + 1)
    if m:
        inner.append([Tok("id", m.group().split(".")[0], pos + 1, pos + 1 + len(m.group().split(".")[0]), False)])
        return m.end()
    return pos + 1


def _lex_quoted(src, pos):
    q = src[pos]
    triple = src.startswith(q * 3, pos)
    delim = q * 3 if triple else q
    i = pos + len(delim)
    inner = []
    n = len(src)
    while i < n:
        c = src[i]
        if c == "\\":
            i += 2
        elif src.startswith(delim, i):
            return i + len(delim), inner
        elif c == "$" and q == '"':
            i = _interp(src, i, inner)
        elif c == "\n" and not triple:
            raise LexError(f"newline in string at offset {pos}")
        else:
            i += 1
    raise LexError(f"unterminated string at offset {pos}")


def _lex_slashy(src, pos):
    i = pos + 1
    inner = []
    n = len(src)
    while i < n:
        c = src[i]
        if c == "\\" and i + 1 < n and src[i + 1] == "/":
            i += 2
        elif c == "/":
            return i + 1, inner
        elif c == "$" and i + 1 < n and (src[i + 1] == "{" or src[i + 1].isalpha() or src[i + 1] == "_"):
            i = _interp(src, i, inner)
        else:
            i += 1
    raise LexError(f"unterminated slashy string at offset {pos}")


def _lex_dollar_slashy(src, pos):
    i = pos + 2
    inner = []
    n = len(src)
    while i < n:
        if src.startswith("/$", i):
            return i + 2, inner
        if src.startswith("$$", i) or src.startswith("$/", i):
            i += 2
        elif src[i] == "$" and i + 1 < n and (src[i + 1] == "{" or src[i + 1].isalpha() or src[i + 1] == "_"):
            i = _interp(src, i, inner)
        else:
            i += 1
    raise LexError(f"unterminated dollar-slashy string at offset {pos}")


class Structure:
    """Bracket matching plus classification of every `{` as closure / block / method body."""

    def __init__(self, toks):
        self.toks = toks
        n = len(toks)
        self.match = [-1] * n
        self.encl = [-1] * n  # innermost enclosing `{` index for each token
        self.kind = {}  # `{` index -> "closure" | "block" | "method"
        self.depth = {}  # `{` index -> number of enclosing `{`
        self.params = {}  # closure `{` index -> (param list or None for implicit, arrow index)
        stack, braces = [], []
        pairs = {")": "(", "]": "[", "}": "{"}
        for i, t in enumerate(toks):
            self.encl[i] = braces[-1] if braces else -1
            if t.kind != "op":
                continue
            if t.text in "([{" and len(t.text) == 1:
                stack.append(i)
                if t.text == "{":
                    self.depth[i] = len(braces)
                    self.kind[i] = self._classify(i)
                    braces.append(i)
            elif t.text in pairs:
                if not stack or toks[stack[-1]].text != pairs[t.text]:
                    raise LexError(f"unbalanced {t.text!r} at offset {t.start}")
                j = stack.pop()
                self.match[i], self.match[j] = j, i
                if t.text == "}":
                    braces.pop()
        if stack:
            raise LexError(f"unclosed {toks[stack[-1]].text!r} at offset {toks[stack[-1]].start}")
        for i, k in self.kind.items():
            if k == "closure":
                self.params[i] = self._parse_params(i)

    def _classify(self, i):
        toks = self.toks
        p = toks[i - 1] if i > 0 else None
        if p is None:
            return "block"
        if p.text == ")":
            j = self.match[i - 1]
            q = toks[j - 1] if j > 0 else None
            if q is not None and q.text in CONTROL_HEADERS:
                return "block"
            return "method" if not self._open_braces_before(i) else "closure"
        if p.kind == "id" and p.text in ("else", "try", "finally", "do"):
            return "block"
        return "closure"

    def _open_braces_before(self, i):
        # depth is assigned before _classify runs, so it is exact here
        return self.depth[i] > 0

    def _parse_params(self, i):
        toks = self.toks
        j = i + 1
        while j < len(toks):
            t = toks[j]
            if t.text == "->":
                break
            if t.kind == "id" or t.text in (",", ".", "<", ">", "[", "]", "?", "=") or t.kind in ("num", "str"):
                j += 1
                continue
            return None, -1
        else:
            return None, -1
        params, cur = [], []
        for t in toks[i + 1 : j]:
            if t.text == ",":
                params.append(cur)
                cur = []
            else:
                cur.append(t)
        if cur:
            params.append(cur)
        return params, j

    def body_header_keyword(self, b):
        """For a block `{`, the keyword that introduces it (if/for/while/else/try/...)."""
        toks = self.toks
        p = toks[b - 1]
        if p.text == ")":
            return toks[self.match[b - 1] - 1].text
        return p.text

_STRUCT_CACHE = {}


def _inner_structure(part):
    key = id(part)
    if key not in _STRUCT_CACHE:
        _STRUCT_CACHE[key] = (part, Structure(part))
    return _STRUCT_CACHE[key][1]


def _iter_ids(toks, st, lo, hi, shadowed):
    implicit_stack = []
    for j in range(lo, hi):
        while implicit_stack and j > implicit_stack[-1]:
            implicit_stack.pop()
        t = toks[j]
        if t.kind == "op" and t.text == "{" and st.kind.get(j) == "closure" and st.params[j][0] is None:
            implicit_stack.append(st.match[j])
        inside = shadowed or bool(implicit_stack)
        if t.kind == "id" and t.text not in KEYWORDS:
            if j > 0 and toks[j - 1].text in (".", "?.", "*.", ".&", ".@"):
                continue
            yield j, t.text, inside
        elif t.kind == "str" and t.inner:
            for part in t.inner:
                for _, name, sh in _iter_ids(part, _inner_structure(part), 0, len(part), inside):
                    yield j, name, sh


def _line_start(src, pos):
    return src.rfind("\n", 0, pos) + 1


def _indent_of(src, pos):
    ls = _line_start(src, pos)
    m = re.match(r"[ \t]*", src[ls:])
    return m.group()


class Rewriter:
    def __init__(self, path, src):
        self.path = path
        self.src = src
        self.toks = lex(src)
        self.st = Structure(self.toks)
        self.edits = []  # (start, end, replacement)
        self.rewritten = {}  # closure `{` index -> set of loop variable names it now binds
        self.done = Counter()
        self.skipped = []  # (line, pattern, reason)

    # ---- helpers -------------------------------------------------------------------------
    def line(self, i):
        return self.src.count("\n", 0, self.toks[i].start) + 1

    def skip(self, i, pattern, reason):
        self.skipped.append((self.line(i), pattern, reason))

    def _iter_ids(self, lo, hi):
        """Yield (index, name, shadow_it) for identifier references in toks[lo:hi], including
        names inside GString interpolations. shadow_it is True when the reference sits inside a
        nested implicit-parameter closure (so `it` there is that closure's own parameter)."""
        return _iter_ids(self.toks, self.st, lo, hi, False)

    def free_refs(self, names, lo, hi):
        for _, name, shadow_it in self._iter_ids(lo, hi):
            if name in names and not (name == "it" and shadow_it):
                return True
        return False

    def method_range(self, i):
        """Token range of the outermost brace enclosing token i."""
        b = self.st.encl[i]
        while b >= 0 and self.st.encl[b] >= 0:
            b = self.st.encl[b]
        if b < 0:
            return 0, len(self.toks)
        lo = self.st.match[b - 1] if self.st.kind[b] == "method" else b  # include the parameters
        return lo, self.st.match[b]

    def name_used_outside(self, name, i, lo, hi):
        mlo, mhi = self.method_range(i)
        toks = self.toks
        for j in list(range(mlo, lo)) + list(range(hi + 1, mhi + 1)):
            t = toks[j]
            if t.kind == "id" and t.text == name:
                return True
            if t.kind == "str" and t.inner and any(x.text == name for part in t.inner for x in part):
                return True
        return False

    def name_used_anywhere(self, name, i):
        _, mhi = self.method_range(i)
        return self.name_used_outside(name, i, mhi + 1, mhi)

    # ---- receiver and statement boundaries --------------------------------------------------
    def receiver_start(self, dot):
        """Start index of the primary-expression chain ending just before toks[dot]."""
        toks, st = self.toks, self.st
        j = dot - 1
        while j >= 0:
            t = toks[j]
            if t.text in (")", "]") and t.kind == "op":
                m = st.match[j]
                b = toks[m - 1] if m > 0 else None
                if b is not None and not t.nl and not toks[m].nl and (
                    (b.kind == "id" and (b.text not in KEYWORDS or b.text in VALUE_KEYWORDS))
                    or b.text in (")", "]", "}")
                ):
                    if t.text == ")" and b.kind == "id" and b.text in CONTROL_HEADERS:
                        return None
                    j = m - 1
                    continue
                return m
            if t.text == "}" and t.kind == "op":
                m = st.match[j]
                if st.kind.get(m) != "closure":
                    return None
                j = m - 1
                continue
            if t.kind == "id":
                if t.text in KEYWORDS and t.text not in VALUE_KEYWORDS:
                    return None
                p = toks[j - 1] if j > 0 else None
                if p is not None and p.text in (".", "?."):
                    j -= 2
                    continue
                if p is not None and p.text in ("*.", ".&", ".@"):
                    return None
                if p is not None and p.text == "new":
                    return j - 1
                return j
            if t.kind in ("str", "num"):
                return j
            return None
        return None

    def statement_starts_at(self, s):
        toks, st = self.toks, self.st
        if s == 0:
            return True
        p = toks[s - 1]
        if p.text in ("{", ";", "->") and p.kind == "op":
            return True
        if not toks[s].nl:
            return False
        if p.kind == "id":
            # a bare return/break/continue ends at the newline
            return p.text not in KEYWORDS or p.text in VALUE_KEYWORDS or p.text in ("return", "break", "continue")
        if p.kind in ("num", "str"):
            return True
        if p.text == ")":
            q = toks[st.match[s - 1] - 1]
            return q.text not in CONTROL_HEADERS
        return p.text in ("]", "}", "++", "--")

    def _chain_head(self, b):
        """First token index of the if/else or try/catch/finally chain that block b belongs to."""
        toks, st = self.toks, self.st
        kw = st.body_header_keyword(b)
        if kw == "if":
            k = st.match[b - 1] - 1
            if k > 0 and toks[k - 1].text == "else":
                prev_close = k - 2
                if toks[prev_close].text != "}" or st.kind.get(st.match[prev_close]) != "block":
                    return None
                return self._chain_head(st.match[prev_close])
            return k
        if kw == "try":
            return b - 1
        if kw in ("else", "finally"):
            prev_close = b - 2
        elif kw == "catch":
            prev_close = st.match[b - 1] - 2
        else:
            return None
        if toks[prev_close].text != "}" or st.kind.get(st.match[prev_close]) != "block":
            return None
        return self._chain_head(st.match[prev_close])

    def _chain_end(self, head):
        toks, st = self.toks, self.st
        i = head
        while True:
            t = toks[i]
            if t.text in ("if", "catch"):
                if toks[i + 1].text != "(":
                    return None
                i = st.match[i + 1] + 1
            elif t.text in ("try", "finally", "else"):
                i += 1
                if toks[i].text == "if":
                    continue
            if toks[i].text != "{" or st.kind.get(i) != "block":
                return None
            close = st.match[i]
            nxt = toks[close + 1] if close + 1 < len(toks) else None
            if nxt is not None and nxt.text in ("else", "catch", "finally"):
                i = close + 1
                continue
            return close

    def value_unused(self, last):
        """True when the statement ending at toks[last] is not the implicit value of anything."""
        toks, st = self.toks, self.st
        if last + 1 >= len(toks):
            return False
        n = toks[last + 1]
        n2i = last + 2 if n.text == ";" else last + 1
        if n2i >= len(toks):
            return False
        n2 = toks[n2i]
        if n2.text == "}" and n2.kind == "op":
            b = st.match[n2i]
            kind = st.kind.get(b)
            if kind == "closure":
                return b in self.rewritten
            if kind == "method":
                name_paren = st.match[b - 1]
                return name_paren >= 2 and toks[name_paren - 2].text == "void"
            kw = st.body_header_keyword(b)
            if kw in ("for", "while", "finally"):
                return True
            if kw in ("if", "else", "try", "catch"):
                head = self._chain_head(b)
                if head is None:
                    return False
                end = self._chain_end(head)
                if end is None:
                    return False
                return self.value_unused(end)
            return False
        if n2.kind == "id" and n2.text in ("case", "default"):
            return False
        if n.text != ";" and not n2.nl:
            return False
        # a new line starting with `(` or `[` is a new statement in Groovy; `.` continues the chain
        if n2.kind == "op" and n2.text not in ("(", "[", "!", "-", "~", "++", "--"):
            return False
        return True

    # ---- closure-body analysis ------------------------------------------------------------
    def analyse_body(self, open_i, loop_names):
        """Return (returns_to_rewrite, reason_or_None) for the closure body at open_i."""
        toks, st = self.toks, self.st
        close = st.match[open_i]
        params, arrow = st.params[open_i]
        lo = arrow + 1 if params is not None else open_i + 1
        returns = []
        loop_ends = []  # close indices of nested loop blocks at closure level
        j = lo
        while j < close:
            t = toks[j]
            if t.kind == "op" and t.text == "{":
                kind = st.kind.get(j)
                if kind == "closure":
                    if not self._eager_closure(j) and self.free_refs(loop_names, j, st.match[j]):
                        return None, "a nested closure that may run later captures the loop variable"
                    j += 1
                    continue
                if st.body_header_keyword(j) in ("for", "while"):
                    loop_ends.append(st.match[j])
            if t.kind == "str" and t.inner and any(
                part and part[0].text == "->" and any(x.kind == "id" and x.text in loop_names for x in part)
                for part in t.inner
            ):
                return None, "a lazy GString closure captures the loop variable"
            if t.kind == "id" and t.text in ("for", "while") and toks[j + 1].text == "(":
                after = st.match[j + 1] + 1
                if toks[after].text != "{":
                    return None, "braceless nested loop"
            if t.kind == "id" and t.text == "return" and self._closure_level(j, open_i):
                nxt = toks[j + 1]
                if not nxt.nl and nxt.text not in ("}", ";"):
                    return None, "return with a value"
                if any(j < e for e in loop_ends):
                    return None, "return inside a nested loop"
                returns.append(j)
            j += 1
        return returns, None

    def _closure_level(self, j, open_i):
        """True when token j belongs to the closure at open_i, not to a closure nested in it."""
        b = self.st.encl[j]
        while b != open_i:
            if self.st.kind.get(b) == "closure":
                return False
            b = self.st.encl[b]
        return True

    def _eager_closure(self, j):
        toks = self.toks
        p = toks[j - 1]
        if p.text == ")":
            p = toks[self.st.match[j - 1] - 1]
            k = self.st.match[j - 1] - 1
        else:
            k = j - 1
        return p.kind == "id" and p.text in EAGER and k > 0 and toks[k - 1].text in (".", "?.")

    def enclosing_binds_it(self, i):
        """Whether `it` is already a variable at token i (implicit closure param or an `it` loop)."""
        st = self.st
        b = st.encl[i]
        while b >= 0:
            if st.kind.get(b) == "closure":
                if b in self.rewritten:
                    if "it" in self.rewritten[b]:
                        return True
                else:
                    params = st.params[b][0]
                    if params is None or any(p[-1].text == "it" for p in params):
                        return True
            b = st.encl[b]
        return False

    # ---- the rewrite ------------------------------------------------------------------------
    def run(self):
        toks, st = self.toks, self.st
        for open_i in sorted(i for i, k in st.kind.items() if k == "closure"):
            name_tok = toks[open_i - 1]
            if name_tok.kind != "id" or name_tok.text not in ("each", "eachWithIndex", "times"):
                continue
            if open_i < 2 or toks[open_i - 2].text not in (".", "?."):
                continue
            pattern = name_tok.text
            reason = self._try(open_i, pattern)
            if reason:
                self.skip(open_i, pattern, reason)
            else:
                self.done[pattern] += 1

    def _try(self, open_i, pattern):
        toks, st, src = self.toks, self.st, self.src
        dot = open_i - 2
        close = st.match[open_i]
        params, arrow = st.params[open_i]
        start = self.receiver_start(dot)
        if start is None:
            return "receiver is not a simple expression chain"
        if not self.statement_starts_at(start):
            return "call value is used (not a standalone statement)"
        if any(t.nl for t in toks[start + 1 : dot + 1]):
            return "multi-line receiver"
        if not self.value_unused(close):
            return "call value is used (chained, or the implicit return value of its block)"
        if params is not None:
            for p in params:
                if len(p) != 1 or p[0].kind != "id":
                    return "typed or defaulted closure parameter"
        names = [p[0].text for p in params] if params is not None else None
        recv = src[toks[start].start : toks[dot].start]
        body_lo = arrow + 1 if params is not None else open_i + 1

        if pattern == "times":
            if names is not None and len(names) != 1:
                return "times closure with more than one parameter"
            if not (dot - start == 1 and toks[start].kind in ("num", "id")):
                return "times receiver is not a literal or a single variable"
            if toks[start].kind == "id" and (
                self.free_refs({toks[start].text}, body_lo, close) or not self._is_local(toks[start].text, start)
            ):
                return "times bound is not a local that the body leaves untouched"
            var = names[0] if names else self._fresh(["i", "j", "k", "n"], start)
            if var is None:
                return "no free loop-variable name"
            if self._assigned(var, body_lo, close):
                return "body assigns the loop counter"
            if names is None and self.free_refs({"it"}, body_lo, close):
                return "implicit it used in times body"
            loop_names = {var}
            header = f"for (int {var} = 0; {var} < {recv}; {var}++) {{"
            prefix_lines = ""
            inserted = None
        elif pattern == "each":
            if names is None or len(names) == 1:
                var = names[0] if names else "it"
                if var == "it" and self.enclosing_binds_it(start):
                    return "`it` is already bound by an enclosing closure or loop"
                if names is not None and self.free_refs({"it"}, body_lo, close):
                    return "body refers to an outer `it`"
                loop_names = {var}
                header = f"for ({var} in {recv}) {{"
                inserted = None
            elif len(names) == 2:
                if self.free_refs({"it"}, body_lo, close):
                    return "body refers to an outer `it`"
                entry = self._fresh(["entry", "e", "kv", "mapEntry"], start)
                if entry is None:
                    return "no free entry-variable name"
                loop_names = set(names) | {entry}
                header = f"for (Map.Entry {entry} in {recv}) {{"
                inserted = [f"def {names[0]} = {entry}.key", f"def {names[1]} = {entry}.value"]
            else:
                return "each closure with more than two parameters"
            prefix_lines = ""
        else:  # eachWithIndex
            if names is None or len(names) != 2:
                return "eachWithIndex without exactly two named parameters"
            if self.free_refs({"it"}, body_lo, close):
                return "body refers to an outer `it`"
            item, idx = names
            if self._assigned(idx, body_lo, close):
                return "body assigns the index parameter"
            if self.name_used_outside(idx, start, start, close):
                return f"index name `{idx}` is used elsewhere in the enclosing method"
            if _line_start(src, toks[start].start) + len(_indent_of(src, toks[start].start)) != toks[start].start:
                return "statement does not start its line"
            loop_names = {item, idx}
            prefix_lines = f"int {idx} = -1\n{_indent_of(src, toks[start].start)}"
            header = f"for ({item} in {recv}) {{"
            inserted = [f"{idx}++"]

        if self.free_refs(loop_names, start, dot):
            return "receiver refers to a loop-variable name"
        returns, reason = self.analyse_body(open_i, loop_names)
        if reason:
            return reason

        # header: from receiver start through `->` (or the `{` for implicit closures)
        hdr_end = toks[arrow].end if params is not None else toks[open_i].end
        single_line = not toks[body_lo].nl if body_lo < close else True
        if inserted:
            if single_line:
                header += " " + "; ".join(inserted) + ";"
            else:
                # place the declarations after the rest of the header line (keeps a trailing comment)
                eol = src.index("\n", hdr_end)
                ind = _indent_of(src, toks[body_lo].start)
                self.edits.append((eol + 1, eol + 1, "".join(f"{ind}{d}\n" for d in inserted)))
        self.edits.append((toks[start].start, hdr_end, prefix_lines + header))
        for r in returns:
            self.edits.append((toks[r].start, toks[r].end, "continue"))
        self.rewritten[open_i] = loop_names
        return None

    def _fresh(self, candidates, i):
        # generated names are loop-scoped, so only loops this run already wrapped around i can clash
        taken = set()
        b = self.st.encl[i]
        while b >= 0:
            taken |= self.rewritten.get(b, set())
            b = self.st.encl[b]
        for c in candidates:
            if c not in taken and not self.name_used_anywhere(c, i):
                return c
        return None

    def _assigned(self, name, lo, hi):
        toks = self.toks
        for j in range(lo, hi):
            t = toks[j]
            if t.kind == "id" and t.text == name and toks[j - 1].text not in (".", "?."):
                if toks[j + 1].text in ASSIGN_OPS or toks[j - 1].text in ("++", "--"):
                    return True
        return False

    def _is_local(self, name, i):
        mlo, _ = self.method_range(i)
        toks = self.toks
        for j in range(mlo, i):
            if toks[j].kind == "id" and toks[j].text == name and toks[j - 1].kind == "id" and (
                toks[j - 1].text in ("def", "int", "long", "Integer")
            ):
                return True
        return False

    def apply(self):
        out = self.src
        for s, e, r in sorted(self.edits, key=lambda x: (x[0], x[1]), reverse=True):
            out = out[:s] + r + out[e:]
        return out


def count_closures(toks):
    st = Structure(toks)
    total = sum(1 for k in st.kind.values() if k == "closure")
    for t in toks:
        if t.kind == "str" and t.inner:
            for part in t.inner:
                if len(part) > 1:
                    total += count_closures(part)
    return total


def process(path, src):
    rw = Rewriter(path, src)
    rw.run()
    return rw, rw.apply()


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("files", nargs="+")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--quiet", action="store_true", help="omit the per-location skip list")
    a = ap.parse_args(argv)
    totals, reasons, changed = Counter(), Counter(), []
    for f in a.files:
        p = pathlib.Path(f)
        src = p.read_text(encoding="utf-8")
        rw, out = process(p, src)
        before = count_closures(rw.toks)
        after = count_closures(lex(out)) if out != src else before
        totals.update(rw.done)
        print(f"{p}: closures {before} -> {after}; rewritten {dict(rw.done) or 0}; skipped {len(rw.skipped)}")
        for line, pattern, reason in rw.skipped:
            reasons[(pattern, reason)] += 1
            if not a.quiet:
                print(f"  skip {p.name}:{line} {pattern}: {reason}")
        if out != src:
            changed.append(p)
            if not a.check:
                p.write_text(out, encoding="utf-8", newline="")
    print(f"rewritten: {dict(totals)}")
    for (pattern, reason), cnt in sorted(reasons.items()):
        print(f"skipped {pattern}: {reason}: {cnt}")
    if a.check and changed:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
