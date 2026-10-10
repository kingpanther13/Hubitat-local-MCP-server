#!/usr/bin/env python3
"""Inline libraries/*.groovy into an app source that uses `#include ns.Name` lines.

Mirrors src/test/groovy/support/IncludeResolver.groovy: each directive line is replaced by the
matching library's body with its leading library(...) call stripped and the result trimmed.
Libraries are matched by (namespace, name) from that call, not by filename.

Usage: inline_server.py APP_SOURCE [--libraries DIR] [--namespace-map from=to] [-o OUT]
"""
import argparse
import pathlib
import re
import sys

INCLUDE_LINE = re.compile(r"^[ \t]*#include[ \t]+([A-Za-z0-9_]+)\.([A-Za-z0-9_]+)[ \t]*\r?$")


def _library_paren_span(text):
    m = re.search(r"\blibrary\s*\(", text)
    if not m:
        return None
    start = m.start()
    i = text.index("(", start)
    depth, quote, n = 0, None, len(text)
    while i < n:
        c = text[i]
        if quote:
            if c == "\\":
                i += 2
                continue
            if c == quote:
                quote = None
        elif text.startswith("//", i):
            nl = text.find("\n", i + 2)
            i = n if nl < 0 else nl
            continue
        elif text.startswith("/*", i):
            end = text.find("*/", i + 2)
            i = n if end < 0 else end + 2
            continue
        elif c in "\"'":
            quote = c
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return start, text.index("(", start), i
        i += 1
    return None


def index_libraries(lib_dir):
    index = {}
    for f in sorted(pathlib.Path(lib_dir).glob("*.groovy")):
        text = f.read_text(encoding="utf-8")
        span = _library_paren_span(text)
        if span is None:
            continue
        args = text[span[1] + 1 : span[2]]
        ns = re.search(r"\bnamespace\s*:\s*[\"']([^\"']+)[\"']", args)
        name = re.search(r"\bname\s*:\s*[\"']([^\"']+)[\"']", args)
        if ns and name:
            index[ns.group(1) + "." + name.group(1)] = (text[: span[0]] + text[span[2] + 1 :]).strip()
    return index


def inline(source, lib_dir, ns_map=None):
    ns_map = ns_map or {}
    index = index_libraries(lib_dir)
    seen, out = set(), []
    for line in source.split("\n"):
        m = INCLUDE_LINE.match(line)
        if not m:
            out.append(line)
            continue
        key = ns_map.get(m.group(1), m.group(1)) + "." + m.group(2)
        if key in seen:
            continue
        seen.add(key)
        if key not in index:
            raise SystemExit(f"#include {key} has no matching library in {lib_dir}")
        out.append("\n" + index[key] + "\n")
    return "\n".join(out)


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("source")
    ap.add_argument("--libraries", default=None)
    ap.add_argument("--namespace-map", action="append", default=[], help="from=to, e.g. mcpprobe=mcp")
    ap.add_argument("-o", "--output")
    a = ap.parse_args(argv)
    src = pathlib.Path(a.source)
    lib_dir = a.libraries or src.resolve().parent / "libraries"
    ns_map = dict(kv.split("=", 1) for kv in a.namespace_map)
    result = inline(src.read_text(encoding="utf-8"), lib_dir, ns_map)
    if a.output:
        pathlib.Path(a.output).write_text(result, encoding="utf-8", newline="")
    else:
        sys.stdout.write(result)


if __name__ == "__main__":
    main()
