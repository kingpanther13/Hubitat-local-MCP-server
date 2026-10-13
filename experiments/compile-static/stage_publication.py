"""Verify the current run's clone artifact and stage its isolated experiment paths."""

import argparse
import hashlib
import json
import os
import re
from pathlib import Path

APPS = ("hubitat-mcp-server.groovy", "hubitat-mcp-rule.groovy")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifact", type=Path, required=True)
    parser.add_argument("--target", type=Path, required=True)
    parser.add_argument("--validation", type=Path, required=True)
    args = parser.parse_args()
    sha = os.environ["GITHUB_SHA"]
    if os.environ["GITHUB_REF"] != "refs/heads/experiment/529-compile-static" or not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("Invalid experiment ref or SHA")
    clone = args.artifact / "dist/experiment-hpm"
    provenance = json.loads((clone / "provenance.json").read_text(encoding="utf-8"))
    manifest = json.loads((clone / "packageManifest.json").read_text(encoding="utf-8"))
    measurement = json.loads((args.artifact / "experiments/compile-static/results/measurement.json").read_text(encoding="utf-8"))
    validation = json.loads(args.validation.read_text(encoding="utf-8"))
    if provenance["sourceSha"] != sha or validation["sourceSha"] != sha or measurement["mode"] != "verify":
        raise ValueError("Artifact provenance, CI validation, or verification mode differs")
    expected_base = f"https://raw.githubusercontent.com/{os.environ['GITHUB_REPOSITORY']}/bundle-artifacts/experiments/529/{sha}"
    if len(manifest["apps"]) != 2 or {app["namespace"] for app in manifest["apps"]} != {"codex.bat529"}:
        raise ValueError("The artifact is not the isolated experiment package")
    if {app["location"] for app in manifest["apps"]} != {f"{expected_base}/{name}" for name in APPS}:
        raise ValueError("The manifest does not point at this immutable experiment SHA")
    records = {record["file"]: record for record in provenance["files"]}
    if set(records) != set(APPS):
        raise ValueError("Expected exactly the two cloned apps")
    payloads = {}
    for name in (*APPS, "packageManifest.json", "provenance.json"):
        payloads[name] = (clone / name).read_bytes()
    for name in APPS:
        data = payloads[name]
        if len(data) != records[name]["bytes"] or hashlib.sha256(data).hexdigest() != records[name]["sha256"]:
            raise ValueError(f"Artifact checksum or size differs: {name}")
        payloads[f"{name}.size"] = str(len(data)).encode("ascii")
    destination = args.target / f"experiments/529/{sha}"
    for name, data in payloads.items():
        existing = destination / name
        if existing.exists() and existing.read_bytes() != data:
            raise ValueError(f"Immutable published artifact already differs: {name}")
    destination.mkdir(parents=True, exist_ok=True)
    for name, data in payloads.items():
        (destination / name).write_bytes(data)
    (destination / "non-e2e-validation.json").write_bytes(args.validation.read_bytes())
    (destination / "measurement.json").write_text(json.dumps(measurement, indent=2) + "\n", encoding="utf-8")
    (args.target / "experiments/529/packageManifest.json").write_bytes(payloads["packageManifest.json"])
    print(f"Staged isolated HPM package experiments/529/{sha}")


if __name__ == "__main__":
    main()
