"""Groovy tokenizer shared by the release builder (issue #522).

String literals (single/double/triple-quoted, slashy, dollar-slashy, with GString ${} bodies) are
single tokens; whitespace and comments between tokens are skipped.
"""
import re

KEYWORDS = {
    "as", "assert", "break", "case", "catch", "class", "const", "continue", "def", "default",
    "do", "else", "enum", "extends", "false", "finally", "for", "goto", "if", "implements",
    "import", "in", "instanceof", "interface", "new", "null", "package", "return", "super",
    "switch", "this", "throw", "throws", "trait", "true", "try", "while", "void",
    "boolean", "byte", "char", "short", "int", "long", "float", "double",
}
VALUE_KEYWORDS = {"this", "super", "null", "true", "false"}
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
