"""Build an isolated HPM package from CI's already-built release app files."""

import argparse
import hashlib
import json
import re
from pathlib import Path

PARENT = "hubitat-mcp-server.groovy"
CHILD = "hubitat-mcp-rule.groovy"
NAMESPACE = "codex.bat529"
PARENT_NAME = "BAT529 MCP Rule Server Static"
CHILD_NAME = "BAT529 MCP Rule Static"


def clone_source(source: str, filename: str, version: str) -> str:
    source = source.replace("MCP Rule Server", PARENT_NAME)
    # Avoid replacing the MCP Rule prefix in the renamed parent a second time.
    source = source.replace('"MCP Rule"', f'"{CHILD_NAME}"')
    source = source.replace('namespace: "mcp"', f'namespace: "{NAMESPACE}"')
    source = source.replace('addChildApp("mcp",', f'addChildApp("{NAMESPACE}",')
    source = source.replace(f'parent: "mcp:{PARENT_NAME}"', f'parent: "{NAMESPACE}:{PARENT_NAME}"')
    source, headers = re.subn(r"(?m)^( \* Version: )\S+", rf"\g<1>{version}", source, count=1)
    if headers != 1:
        raise ValueError(f"{filename}: expected one version header")
    if filename == PARENT:
        source, methods = re.subn(
            r'(def currentVersion\(\)\s*\{\s*return ")[^"]+("\s*\})',
            rf"\g<1>{version}\g<2>", source,
        )
        if methods != 1:
            raise ValueError("Expected one parent currentVersion()")
    if 'name: "MCP Rule Server"' in source or 'name: "MCP Rule"' in source:
        raise ValueError("Production app identity remains")
    if re.search(r"(?m)^[ \t]*#include[ \t]+", source):
        raise ValueError("Clone must use the inlined release build")
    return source


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--source-dir", type=Path, default=Path("dist"))
    parser.add_argument("--output", type=Path, default=Path("dist/experiment-hpm"))
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9a-f]{40}", args.source_sha):
        raise ValueError("source-sha must be a full immutable commit SHA")
    if not re.fullmatch(r"\d+\.\d+\.\d+", args.version):
        raise ValueError("version must be numeric x.y.z")
    args.output.mkdir(parents=True, exist_ok=True)
    base = (
        "https://raw.githubusercontent.com/kingpanther13/Hubitat-local-MCP-server/"
        f"bundle-artifacts/experiments/529/{args.source_sha}"
    )
    records = []
    for filename in (PARENT, CHILD):
        text = clone_source((args.source_dir / filename).read_text(encoding="utf-8"), filename, args.version)
        data = text.encode("utf-8")
        (args.output / filename).write_bytes(data)
        records.append({"file": filename, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()})
    manifest = {
        "packageName": PARENT_NAME,
        "author": "kingpanther13",
        "version": args.version,
        "minimumHEVersion": "2.5.2",
        "releaseNotes": "Throwaway compile-static timing experiment; isolated app identity.",
        "apps": [
            {"id": "932b85ca-c9e3-4f21-9e89-532d818ad529", "name": PARENT_NAME,
             "namespace": NAMESPACE, "location": f"{base}/{PARENT}",
             "required": True, "oauth": True, "primary": True},
            {"id": "318579bf-7591-43e5-a14c-5cd7bdaca529", "name": CHILD_NAME,
             "namespace": NAMESPACE, "location": f"{base}/{CHILD}",
             "required": True, "oauth": False, "primary": False},
        ],
    }
    (args.output / "packageManifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    (args.output / "provenance.json").write_text(
        json.dumps({"sourceSha": args.source_sha, "version": args.version, "files": records}, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps({"sourceSha": args.source_sha, "version": args.version, "files": records}))


if __name__ == "__main__":
    main()
