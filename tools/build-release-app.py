#!/usr/bin/env python3
"""Build the release app files HPM installs (issue #522).

dist/hubitat-mcp-server.groovy: the parent with each `#include ns.Name` line replaced by that
library's body minus its first-line library(...) declaration -- the hub's paste, as
src/test/groovy/support/IncludeResolver.groovy resolves it -- with every libraries/*.groovy included
exactly once. dist/hubitat-mcp-rule.groovy: the child. Both are stripped by tools/strip_source.py.
Output bytes are a pure function of the source bytes (CRLF normalized), so any rebuild matches.

Run:  python3 tools/build-release-app.py [--root CHECKOUT]   (writes CHECKOUT/dist/)
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from strip_source import strip

REPO_ROOT = Path(__file__).resolve().parent.parent
PARENT = "hubitat-mcp-server.groovy"
CHILD = "hubitat-mcp-rule.groovy"

INCLUDE_LINE = re.compile(r"^[ \t]*#include[ \t]+([A-Za-z0-9_]+)\.([A-Za-z0-9_]+)[ \t]*$")
LIBRARY_DECL = re.compile(r"^library\s*\((.*)\)\s*$")
HEADER = re.compile(r"\s*/\*.*?\*/", re.S)


class BuildError(Exception):
    pass


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8").replace("\r\n", "\n")


def _decl_field(decl: str, field: str) -> str | None:
    m = re.search(rf"\b{field}\s*:\s*[\"']([^\"']+)[\"']", decl)
    return m.group(1) if m else None


def index_libraries(lib_dir: Path) -> dict[str, tuple[str, str]]:
    """'ns.Name' -> (library file name, body without its declaration line)."""
    index: dict[str, tuple[str, str]] = {}
    for path in sorted(lib_dir.glob("*.groovy")):
        first, _, body = _read(path).partition("\n")
        m = LIBRARY_DECL.match(first)
        ns = _decl_field(m.group(1), "namespace") if m else None
        name = _decl_field(m.group(1), "name") if m else None
        if not (ns and name):
            raise BuildError(f"libraries/{path.name}: line 1 is not a library(name: ..., namespace: ...) declaration")
        key = f"{ns}.{name}"
        if key in index:
            raise BuildError(f"libraries/{path.name} and libraries/{index[key][0]} both declare {key}")
        index[key] = (path.name, body.strip())
    return index


def inline(source: str, index: dict[str, tuple[str, str]], used: set[str], label: str) -> str:
    out = []
    for n, line in enumerate(source.split("\n"), 1):
        m = INCLUDE_LINE.match(line)
        if not m:
            out.append(line)
            continue
        key = f"{m.group(1)}.{m.group(2)}"
        if key not in index:
            raise BuildError(f"{label}:{n}: #include {key} has no libraries/*.groovy declaring it")
        if key in used:
            raise BuildError(f"{label}:{n}: #include {key} appears more than once")
        used.add(key)
        out.append(index[key][1])
    return "\n".join(out)


def build(root: Path) -> dict[str, str]:
    """Output file name -> built text."""
    index = index_libraries(root / "libraries")
    used: set[str] = set()
    built = {}
    for name in (PARENT, CHILD):
        source = _read(root / name)
        text = strip(inline(source, index, used, name))
        header = HEADER.match(source)
        if header and not text.startswith(header.group().lstrip()):
            raise BuildError(f"{name}: the leading /* */ file header did not survive the build")
        built[name] = text
    unused = sorted(f"libraries/{index[k][0]} ({k})" for k in index.keys() - used)
    if unused:
        raise BuildError(f"library files no app #includes: {', '.join(unused)}")
    return built


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--root", type=Path, default=REPO_ROOT, help="checkout to build (default: this repo)")
    root = ap.parse_args(argv).root.resolve()
    try:
        built = build(root)
    except BuildError as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 1
    dist = root / "dist"
    dist.mkdir(exist_ok=True)
    for name, text in built.items():
        data = text.encode("utf-8")
        (dist / name).write_bytes(data)
        print(f"Built dist/{name} ({len(data):,} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
