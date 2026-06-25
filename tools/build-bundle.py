#!/usr/bin/env python3
"""Build the HPM bundle ZIP for the MCP Rule Server's #include libraries (issue #209).

Produces bundles/mcp-libraries.zip in the layout Hubitat's Bundle Manager
expects (the format proven in production by level99/Hubitat-VeSync, which
migrated to bundles[] after the older libraries[] manifest array silently
dropped libraries on HPM update):

  mcp.McpSmokeTestLib.groovy   <- a library source, renamed to <namespace>.<name>.groovy
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
is deterministic (fixed DOS epoch, stored entries) so any two builds of the
same library source are byte-identical and can be compared directly.

Run:  python3 tools/build-bundle.py
"""

from __future__ import annotations

import sys
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
LIB_DIR = REPO_ROOT / "libraries"
OUTPUT_DIR = REPO_ROOT / "bundles"
OUTPUT_ZIP = OUTPUT_DIR / "mcp-libraries.zip"

NAMESPACE = "mcp"
BUNDLE_NAME = "mcp_libraries"

LIBS = [
    {
        "source": LIB_DIR / "mcp-smoke-test-lib.groovy",
        "dest": f"{NAMESPACE}.McpSmokeTestLib.groovy",
    },
    {
        "source": LIB_DIR / "mcp-smoke-test2-lib.groovy",
        "dest": f"{NAMESPACE}.McpSmokeTest2Lib.groovy",
    },
    {
        "source": LIB_DIR / "mcp-canary3-lib.groovy",
        "dest": f"{NAMESPACE}.McpCanary3Lib.groovy",
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

# Fixed DOS-epoch timestamp + stored (uncompressed) entries make rebuilds
# byte-identical, so builds of the same source compare equal byte-for-byte.
_FIXED_DT = (1980, 1, 1, 0, 0, 0)


def _add(zf: zipfile.ZipFile, name: str, data: bytes) -> None:
    info = zipfile.ZipInfo(filename=name, date_time=_FIXED_DT)
    info.compress_type = zipfile.ZIP_STORED
    info.external_attr = 0o644 << 16
    # ZipInfo defaults create_system from the running platform (0 on Windows,
    # 3 elsewhere) -- pin it so a zip built on Windows is byte-identical to the
    # rebuild on any other machine (CI workflows, the e2e deploy).
    info.create_system = 3
    zf.writestr(info, data)


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
            # Read as text + normalize CRLF->LF so the committed ZIP is byte-identical
            # regardless of the builder's git core.autocrlf / platform.
            content = (
                lib["source"]
                .read_text(encoding="utf-8")
                .replace("\r\n", "\n")
                .encode("utf-8")
            )
            _add(zf, lib["dest"], content)
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
