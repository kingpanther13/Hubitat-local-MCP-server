#!/usr/bin/env python3
"""Strip comments and blank lines from Groovy sources without touching string literals (issue #522).

Uses the tokenizer from tools/declosure.py: everything between two tokens is whitespace or
comments, and string literals (including GString ${} bodies, triple-quoted, slashy and
dollar-slashy strings) are single tokens, so they are never edited. Per gap between tokens:
comments are removed (a block comment spanning lines leaves its newlines, an inline one leaves a
space so tokens never merge), trailing whitespace before a newline is dropped, and lines left
empty are removed. Lines are never joined and indentation is kept, so the token stream and its
statement-separating newlines are unchanged. A leading /* */ file header is kept (it holds the
` * Version:` line the release tooling reads).

Usage: strip_source.py [--check] FILE...   (rewrites in place; --check exits 1 if a file would change)
"""
import argparse
import itertools
import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from declosure import lex

_GAP_PART = re.compile(r"//[^\n]*|/\*.*?\*/|[^/]+", re.S)


def _strip_gap(gap, first, last):
    pieces = []
    for m in _GAP_PART.finditer(gap):
        text = m.group()
        if text.startswith("/*"):
            pieces.append("\n" * text.count("\n") or " ")
        elif not text.startswith("//"):
            pieces.append(text)
    lines = "".join(pieces).split("\n")
    if len(lines) == 1:
        return lines[0]
    if first:
        return lines[-1]  # nothing precedes the first token: no leading blank line
    if last:
        return lines[0].rstrip() + "\n"
    return lines[0].rstrip() + "\n" + lines[-1]


def strip(src):
    toks = lex(src)
    if not toks:
        return ""
    head = src[: toks[0].start]
    # The leading /* */ file header carries the ` * Version:` line that release_bump.py,
    # pr_guard.py and sandbox_lint.py read, so it is kept verbatim.
    m = re.match(r"\s*/\*.*?\*/", head, re.S)
    if m:
        out = [m.group().lstrip() + "\n", _strip_gap(head[m.end() :], True, False)]
    else:
        out = [_strip_gap(head, True, False)]
    for prev, tok in itertools.pairwise(toks):
        out.append(src[prev.start : prev.end])
        out.append(_strip_gap(src[prev.end : tok.start], False, False))
    out.append(src[toks[-1].start : toks[-1].end])
    tail = src[toks[-1].end :]
    out.append(_strip_gap(tail, False, True) if "\n" in tail else _strip_gap(tail, False, False).rstrip())
    return "".join(out)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("files", nargs="+")
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args(argv)
    changed = False
    for f in a.files:
        p = pathlib.Path(f)
        src = p.read_text(encoding="utf-8")
        out = strip(src)
        print(f"{p}: {len(src.encode())} -> {len(out.encode())} bytes")
        if out != src:
            changed = True
            if not a.check:
                p.write_text(out, encoding="utf-8", newline="")
    return 1 if a.check and changed else 0


if __name__ == "__main__":
    sys.exit(main())
