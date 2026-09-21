#!/usr/bin/env python3
"""Build the HPM bundle ZIP for the MCP Rule Server's #include libraries (issue #209).

Produces bundles/mcp-libraries.zip in the layout Hubitat's Bundle Manager
expects (the format proven in production by level99/Hubitat-VeSync, which
migrated to bundles[] after the older libraries[] manifest array silently
dropped libraries on HPM update):

  mcp.McpDashboardsLib.groovy  <- a library source, renamed to <namespace>.<name>.groovy
  mcp.McpRoomsLib.groovy       <- (one .groovy entry per library in LIBS)
  install.txt                  <- bundle install manifest
  update.txt                   <- bundle update manifest (identical content)

install.txt / update.txt declare the namespace, the bundle name, then one
`library <namespace>.<name>.groovy` line per library:

  <namespace>
  <bundle_name>
  library <namespace>.<name>.groovy
  ...

On HPM install/update the hub extracts each .groovy into Libraries Code under
the declared namespace + name, making each resolvable via its `#include`
(e.g. `#include mcp.McpRoomsLib`).

Hosting: delivery is UNIFIED on the bundle-artifacts branch, fed by
publish-bundle-artifact.yml on every push (branches/<branch>/ + shas/<sha>/
entries). packageManifest.json's `bundles[]` location points at branches/main/
-- the same mechanism the e2e deploy installs and byte-verifies on every run.
Nothing under bundles/ is committed (the output dir is gitignored). The build
is deterministic (fixed DOS epoch, pinned deflate level) so two builds of the
same library source on the same zlib are byte-identical and can be compared
directly (the e2e cmp-byte-verifies the published artifact against its own CI rebuild).

Each library is shipped with its whole-line `//` developer comments blanked
(issue #451): those comments are ~40% of the bytes the hub's Libraries Code page
and /hub2/userLibraries have to serve, and Groovy discards them at compile time.
Only lines that consist of nothing but a `//` comment are emptied -- inline
trailing comments, string contents and every code line are untouched -- and each
emptied line is kept as an empty line, so hub line numbers still map to the repo
file (offset by the one notice line inserted below the `library(...)` declaration,
which points at the fully commented source on GitHub). verify_library_transform()
re-checks both properties, per library, at build time.

Run:  python3 tools/build-bundle.py
"""

from __future__ import annotations

import re
import sys
import zipfile
import zlib
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
LIB_DIR = REPO_ROOT / "libraries"
OUTPUT_DIR = REPO_ROOT / "bundles"
OUTPUT_ZIP = OUTPUT_DIR / "mcp-libraries.zip"

NAMESPACE = "mcp"
BUNDLE_NAME = "mcp_libraries"

LIBS = [
    {
        "source": LIB_DIR / "mcp-dashboards-lib.groovy",
        "dest": f"{NAMESPACE}.McpDashboardsLib.groovy",
    },
    {
        "source": LIB_DIR / "mcp-rooms-lib.groovy",
        "dest": f"{NAMESPACE}.McpRoomsLib.groovy",
    },
    {
        "source": LIB_DIR / "mcp-bundles-lib.groovy",
        "dest": f"{NAMESPACE}.McpBundlesLib.groovy",
    },
    {
        "source": LIB_DIR / "mcp-visual-rules-lib.groovy",
        "dest": f"{NAMESPACE}.McpVisualRulesLib.groovy",
    },
    {
        "source": LIB_DIR / "mcp-files-lib.groovy",
        "dest": f"{NAMESPACE}.McpFilesLib.groovy",
    },
    {
        "source": LIB_DIR / "mcp-item-backups-lib.groovy",
        "dest": f"{NAMESPACE}.McpItemBackupsLib.groovy",
    },
    {
        "source": LIB_DIR / "mcp-debug-logging-lib.groovy",
        "dest": f"{NAMESPACE}.McpDebugLoggingLib.groovy",
    },
    {
        "source": LIB_DIR / "mcp-diagnostics-lib.groovy",
        "dest": f"{NAMESPACE}.McpDiagnosticsLib.groovy",
    },
    {
        "source": LIB_DIR / "mcp-system-lib.groovy",
        "dest": f"{NAMESPACE}.McpSystemLib.groovy",
    },
    {
        "source": LIB_DIR / "mcp-devices-lib.groovy",
        "dest": f"{NAMESPACE}.McpDevicesLib.groovy",
    },
    {
        "source": LIB_DIR / "mcp-virtual-devices-lib.groovy",
        "dest": f"{NAMESPACE}.McpVirtualDevicesLib.groovy",
    },
    {
        "source": LIB_DIR / "mcp-variables-lib.groovy",
        "dest": f"{NAMESPACE}.McpVariablesLib.groovy",
    },
    {
        "source": LIB_DIR / "mcp-custom-rules-lib.groovy",
        "dest": f"{NAMESPACE}.McpCustomRulesLib.groovy",
    },
    {
        "source": LIB_DIR / "mcp-code-management-lib.groovy",
        "dest": f"{NAMESPACE}.McpCodeManagementLib.groovy",
    },
    {
        "source": LIB_DIR / "mcp-hpm-lib.groovy",
        "dest": f"{NAMESPACE}.McpHpmLib.groovy",
    },
    {
        "source": LIB_DIR / "mcp-self-admin-lib.groovy",
        "dest": f"{NAMESPACE}.McpSelfAdminLib.groovy",
    },
    {
        "source": LIB_DIR / "mcp-app-cloner-lib.groovy",
        "dest": f"{NAMESPACE}.McpAppClonerLib.groovy",
    },
    {
        "source": LIB_DIR / "mcp-discovery-lib.groovy",
        "dest": f"{NAMESPACE}.McpDiscoveryLib.groovy",
    },
    {
        "source": LIB_DIR / "mcp-native-rules-lib.groovy",
        "dest": f"{NAMESPACE}.McpNativeRulesLib.groovy",
    },
]

# Deterministic build: a fixed DOS-epoch timestamp + a pinned deflate level make
# rebuilds of the same source byte-identical, so the e2e can byte-compare the
# published artifact against its own CI rebuild (cmp -s).
# DEFLATE (not STORED) keeps the zip well under the hub's ~2MB
# /bundle2/uploadZipFromUrl fetch cap: ~2MB of library source compresses to ~0.5MB.
# A STORED bundle over ~2,000,000 bytes is rejected by the hub with the generic
# "Cannot retrieve zip file" (the cap is on the fetched-file bytes, not the
# uncompressed content -- verified live: identical content installs deflated and
# fails stored).
_FIXED_DT = (1980, 1, 1, 0, 0, 0)
_DEFLATE_LEVEL = 9  # pinned so deflate output is reproducible build-to-build


SOURCE_URL_BASE = (
    "https://github.com/kingpanther13/Hubitat-local-MCP-server/blob/main/libraries/"
)

_TRIPLE_QUOTES = ('"""', "'''")


def strip_comment_lines(text: str) -> str:
    """Blank every line that is only a `//` comment, keeping the line count.

    Lines inside a triple-quoted string are left alone even when they start with
    `//` (a tool description could legitimately contain one). The tracker is
    deliberately simple -- it toggles on each `\"\"\"` / `'''` seen on a code line
    -- because the only thing that must never happen is blanking a line that is
    part of a string; a comment line can't open or close a string, so it is never
    counted.
    """
    out = []
    open_quote = None
    for line in text.split("\n"):
        stripped = line.lstrip()
        if open_quote is None and stripped.startswith("//"):
            out.append("")
            continue
        out.append(line)
        for quote in _TRIPLE_QUOTES:
            if open_quote is None and quote in line:
                if line.count(quote) % 2 == 1:
                    open_quote = quote
            elif open_quote == quote and quote in line:
                if line.count(quote) % 2 == 1:
                    open_quote = None
    return "\n".join(out)


def _declaration_end(lines: list[str]) -> int:
    """Index of the last line of the leading ``library(...)`` declaration.

    The notice line goes AFTER it, never before: the hub parses that declaration
    server-side to name the library, and nothing in the vendored hub UI source
    shows whether it tolerates anything above it -- so don't find out on a user's
    hub. Parentheses inside quoted text (the description) are not counted.
    """
    for index, line in enumerate(lines):
        if not line.strip():
            continue
        if not line.startswith("library("):
            raise RuntimeError(
                f"library file does not open with a library(...) declaration: {line[:60]!r}"
            )
        depth = 0
        for scan in range(index, len(lines)):
            quote = None
            for char in lines[scan]:
                if quote:
                    if char == quote:
                        quote = None
                elif char in "\"'":
                    quote = char
                elif char == "(":
                    depth += 1
                elif char == ")":
                    depth -= 1
            if depth <= 0:
                return scan
        raise RuntimeError("unterminated library(...) declaration")
    raise RuntimeError("library file is empty")


def prepare_library_source(source: Path) -> str:
    """The text shipped for one library: CRLF-normalized, whole-line comments
    blanked, and one notice line inserted just below the ``library(...)``
    declaration pointing at the fully commented source."""
    text = source.read_text(encoding="utf-8").replace("\r\n", "\n")
    lines = strip_comment_lines(text).split("\n")
    notice = (
        "// Developer comments are blanked in this hub copy so the hub's code pages stay fast -- "
        "Groovy discards them anyway. Below this line, a line number here is the repository file's "
        f"plus one. Full source: {SOURCE_URL_BASE}{source.name}"
    )
    at = _declaration_end(lines) + 1
    return "\n".join([*lines[:at], notice, *lines[at:]])


def verify_library_transform(source: Path, shipped: str) -> None:
    """Fail the build unless the shipped text differs from the source ONLY by blanked
    comment lines plus the one inserted notice.

    The line-by-line check catches a stripper that touches code. The string-literal
    check is deliberately an INDEPENDENT reading of both texts (regex over triple-quoted
    bodies, not the stripper's own line-state tracking), so a `//` line that lives inside
    a tool description -- the one input that could silently lose string content -- fails
    here instead of shipping.
    """
    original = source.read_text(encoding="utf-8").replace("\r\n", "\n").split("\n")
    out = shipped.split("\n")
    if len(out) != len(original) + 1:
        raise RuntimeError(
            f"{source.name}: shipped {len(out)} lines from {len(original)} source lines "
            "(expected exactly one added notice line)"
        )
    notice_at = _declaration_end(out) + 1
    for offset, line in enumerate(out):
        if offset == notice_at:
            continue
        src = original[offset if offset < notice_at else offset - 1]
        if line == src:
            continue
        if line == "" and src.lstrip().startswith("//"):
            continue
        raise RuntimeError(
            f"{source.name}: line {offset + 1} of the shipped library is neither the source line "
            f"nor a blanked comment: {src[:70]!r} -> {line[:70]!r}"
        )
    for quotes in ('"""', "'''"):
        pattern = re.compile(re.escape(quotes) + "(.*?)" + re.escape(quotes), re.DOTALL)
        if pattern.findall("\n".join(original)) != pattern.findall("\n".join(out)):
            raise RuntimeError(
                f"{source.name}: {quotes}-quoted string content changed -- a comment-looking line "
                "inside a string was blanked. Reword that line in the source."
            )


def _add(zf: zipfile.ZipFile, name: str, data: bytes) -> None:
    info = zipfile.ZipInfo(filename=name, date_time=_FIXED_DT)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o644 << 16
    # ZipInfo defaults create_system from the running platform (0 on Windows,
    # 3 elsewhere) -- pin it so a zip built on Windows is byte-identical to the
    # rebuild on any other machine (CI workflows, the e2e deploy).
    info.create_system = 3
    # Pass the level HERE: a ZipFile(compresslevel=...) is IGNORED for a pre-built
    # ZipInfo (CPython only copies it onto entries it builds from an arcname string),
    # so the pin only takes effect when handed to writestr.
    zf.writestr(info, data, compresslevel=_DEFLATE_LEVEL)


def build() -> str:
    for lib in LIBS:
        if not lib["source"].exists():
            print(
                f"ERROR: source library not found at {lib['source']}", file=sys.stderr
            )
            raise SystemExit(1)

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    lib_lines = "\n".join(f"library {lib['dest']}" for lib in LIBS)
    manifest = f"{NAMESPACE}\n{BUNDLE_NAME}\n{lib_lines}\n"

    with zipfile.ZipFile(OUTPUT_ZIP, "w") as zf:
        for lib in LIBS:
            shipped = prepare_library_source(lib["source"])
            verify_library_transform(lib["source"], shipped)
            _add(zf, lib["dest"], shipped.encode("utf-8"))
        _add(zf, "install.txt", manifest.encode())
        _add(zf, "update.txt", manifest.encode())
    return manifest


def verify(manifest: str) -> None:
    """Re-open the built ZIP and verify the layout HPM requires.

    Uses explicit raises (not assert) so the checks still run under ``python -O``.
    """
    expected = {lib["dest"] for lib in LIBS} | {"install.txt", "update.txt"}
    with zipfile.ZipFile(OUTPUT_ZIP) as zf:
        names = set(zf.namelist())
        if names != expected:
            raise RuntimeError(
                f"bundle entries {sorted(names)} != expected {sorted(expected)}"
            )
        if zf.read("install.txt").decode() != manifest:
            raise RuntimeError("install.txt content drifted from the builder")
        if zf.read("update.txt").decode() != manifest:
            raise RuntimeError("update.txt != install.txt")
        lines = manifest.splitlines()
        if lines[0] != NAMESPACE:
            raise RuntimeError(f"manifest line 1 {lines[0]!r} != namespace")
        if lines[1] != BUNDLE_NAME:
            raise RuntimeError(f"manifest line 2 {lines[1]!r} != bundle name")
        for lib in LIBS:
            if f"library {lib['dest']}" not in lines:
                raise RuntimeError(f"missing library line for {lib['dest']}")
        # Every entry must be DEFLATE at the pinned level. A ZipFile(compresslevel=...) arg is
        # silently IGNORED for pre-built ZipInfo entries, so this guards that exact regression and
        # keeps rebuilds byte-reproducible (the e2e cmp-compares the artifact against its CI rebuild).
        for info in zf.infolist():
            data = zf.read(info.filename)
            co = zlib.compressobj(_DEFLATE_LEVEL, zlib.DEFLATED, -15)
            expected = len(co.compress(data) + co.flush())
            if info.compress_type != zipfile.ZIP_DEFLATED or info.compress_size != expected:
                raise RuntimeError(
                    f"{info.filename}: stored compress_size {info.compress_size} (type "
                    f"{info.compress_type}) != level-{_DEFLATE_LEVEL} deflate {expected} -- the pinned "
                    f"deflate level is not applied; rebuilds may not be byte-reproducible."
                )


def main() -> int:
    manifest = build()
    verify(manifest)
    size = OUTPUT_ZIP.stat().st_size
    print(f"Built {OUTPUT_ZIP.relative_to(REPO_ROOT)} ({size:,} bytes)")
    with zipfile.ZipFile(OUTPUT_ZIP) as zf:
        for info in zf.infolist():
            print(f"  {info.filename:32s} {info.file_size:>6,} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
