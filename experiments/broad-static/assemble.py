"""Intersect remote plans, validate exact inputs, and prepare a reviewable patch."""

import argparse
import difflib
import hashlib
import json
import os
from pathlib import Path


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def apply_edits(text: str, edits: list[dict]) -> str:
    # Groovy AST offsets count UTF-16 code units, including surrogate pairs.
    encoded = text.encode("utf-16-le")
    previous = len(encoded) // 2 + 1
    for edit in sorted(edits, key=lambda item: (item["start"], item["end"]), reverse=True):
        start, end = edit["start"], edit["end"]
        if not 0 <= start <= end <= previous:
            raise ValueError(f"Overlapping or invalid edit: {start}..{end}")
        expected = edit["before"].encode("utf-16-le")
        if encoded[2 * start : 2 * end] != expected:
            raise ValueError(f"Source mismatch at {start}..{end}")
        encoded = (
            encoded[: 2 * start]
            + edit["replacement"].encode("utf-16-le")
            + encoded[2 * end :]
        )
        previous = start
    return encoded.decode("utf-16-le")


def main() -> None:
    if os.environ.get("GITHUB_ACTIONS") != "true":
        raise SystemExit("Run assembly only in GitHub Actions")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--results", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--apply", action="store_true", help="Apply to this disposable Actions checkout after all checksum guards")
    args = parser.parse_args()
    root = args.root.resolve()
    reports = [
        json.loads((args.results / flavor / "report.json").read_text(encoding="utf-8"))
        for flavor in ("groovy24", "groovy25", "groovy30")
    ]
    if any(report["revision"] != reports[0]["revision"] for report in reports):
        raise ValueError("Reports came from different revisions")
    if any(report["files"] != reports[0]["files"] for report in reports):
        raise ValueError("Reports used different source files")
    for relative, expected_hash in reports[0]["files"].items():
        path = (root / relative).resolve()
        if not path.is_relative_to(root) or digest(path.read_bytes()) != expected_hash:
            raise ValueError(f"Preparation input changed: {relative}")
    by_runtime = [{plan["method"]: plan for plan in report["selected"]} for report in reports]
    common = set.intersection(*(set(runtime) for runtime in by_runtime))
    accepted, incompatible = [], []
    for method in sorted(common):
        plans = [runtime[method] for runtime in by_runtime]
        # Compiler diagnostics may differ. Actual edits and helper code must agree.
        if all(
            plan["edits"] == plans[0]["edits"] and plan["helpers"] == plans[0]["helpers"]
            for plan in plans[1:]
        ):
            accepted.append(plans[0])
        else:
            incompatible.append(method)
    if not accepted:
        raise ValueError("No compatible plans across all three runtimes")
    edits_by_path: dict[str, list[dict]] = {}
    helpers: dict[str, str] = {}
    for plan in accepted:
        for edit in plan["edits"]:
            edits_by_path.setdefault(edit["path"], []).append(edit)
        for name, code in plan["helpers"].items():
            if name in helpers and helpers[name] != code:
                raise ValueError(f"Helper contract conflict: {name}")
            helpers[name] = code
    app_name = "hubitat-mcp-server.groovy"
    edits_by_path.setdefault(app_name, [])
    patch, files, pending = [], [], []
    args.output.mkdir(parents=True, exist_ok=True)
    for relative, edits in sorted(edits_by_path.items()):
        path = (root / relative).resolve()
        if not path.is_relative_to(root):
            raise ValueError("Source path escapes checkout")
        original_bytes = path.read_bytes()
        if digest(original_bytes) != reports[0]["files"][relative]:
            raise ValueError(f"Input checksum changed: {relative}")
        original = original_bytes.decode("utf-8")
        candidate = apply_edits(original, edits)
        if relative == app_name:
            candidate += "\n" + "\n".join(helpers[name] for name in sorted(helpers))
        candidate_bytes = candidate.encode("utf-8")
        pending.append((path, candidate_bytes))
        destination = args.output / "source" / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(candidate_bytes)
        patch.extend(
            difflib.unified_diff(
                original.splitlines(keepends=True),
                candidate.splitlines(keepends=True),
                fromfile="a/" + relative,
                tofile="b/" + relative,
            )
        )
        files.append(
            {
                "path": relative,
                "originalSha256": digest(original_bytes),
                "candidateSha256": digest(candidate_bytes),
                "edits": len(edits),
            }
        )
    (args.output / "candidate.patch").write_text("".join(patch), encoding="utf-8", newline="")
    manifest = {
        "revision": reports[0]["revision"],
        "addedStaticBodies": len(accepted),
        "retainedStaticBodies": reports[0]["retainedStaticBodies"],
        "explicitMethodReturns": sum(plan.get("explicitReturnType") is not None for plan in accepted),
        "explicitLocalVariables": sum(len(plan.get("explicitLocalTypes", [])) for plan in accepted),
        "dynamicAdapters": len(helpers),
        "dynamicIslands": sum(len(plan["islands"]) for plan in accepted),
        "storeBoundaries": sum(
            len(plan.get("storeBoundaries", [])) for plan in accepted
        ),
        "storagePolicy": "Original state/atomicState proxies and complete setter operations; no relocation, copying, casting, or caching",
        "selected": [plan["method"] for plan in accepted],
        "incompatiblePlans": incompatible,
        "files": files,
        "discoveryMetrics": {report["groovy"]: report["after"] for report in reports},
        "validation": "Assembled source requires remote parse/measure plus all 11 complete non-E2E lanes",
    }
    (args.output / "manifest.json").write_text(
        json.dumps(manifest, indent=2) + "\n", encoding="utf-8"
    )
    if args.apply:
        # Every path/edit/checksum was validated before any checkout write.
        for path, candidate_bytes in pending:
            path.write_bytes(candidate_bytes)
    print(f"Prepared {len(accepted)} static bodies with {len(helpers)} dynamic adapters")


if __name__ == "__main__":
    main()
