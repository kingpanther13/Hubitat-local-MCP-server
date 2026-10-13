"""Apply exact remotely generated callback sources; no builds or tests."""
import argparse
import hashlib
import json
from pathlib import Path


def sha(data):
    return hashlib.sha256(data).hexdigest()


def reconstruct(before, edits, appended):
    raw = before.decode("utf-8").encode("utf-16-le")
    ordered = sorted(edits, key=lambda e: (e["start"], -e["end"]))
    chunks, cursor = [], 0
    for edit in ordered:
        start, end = edit["start"] * 2, edit["end"] * 2
        if not (0 <= start < end <= len(raw)):
            raise ValueError("Invalid source span")
        if raw[start:end].decode("utf-16-le") != edit["original"]:
            raise ValueError("Original source span mismatch")
        if start >= cursor:
            chunks.extend([raw[cursor:start], edit["replacement"].encode("utf-16-le")])
            cursor = end
        elif end > cursor:
            raise ValueError("Crossing source spans")
    chunks.append(raw[cursor:])
    chunks.append(appended.encode("utf-16-le"))
    return b"".join(chunks).decode("utf-16-le").encode("utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("repo", type=Path)
    parser.add_argument("artifact", type=Path)
    parser.add_argument("--check-only", action="store_true")
    args = parser.parse_args()
    root, artifact = args.repo.resolve(), args.artifact.resolve()
    manifest = json.loads((artifact / "manifest.json").read_text(encoding="utf-8"))
    if (manifest["formatVersion"] != 2 or manifest["generator"] != "named-method-callbacks"
            or manifest["groovyVersion"] != "2.4.21"):
        raise ValueError("Unexpected generation artifact")
    inventory = {"hubitat-mcp-server.groovy"}
    inventory.update(p.relative_to(root).as_posix() for p in (root / "libraries").glob("*.groovy"))
    listed = [record["path"] for record in manifest["files"]]
    if len(set(listed)) != len(listed) or set(listed) != inventory:
        raise ValueError("Parent/library inventory differs from generation input")
    staged, count = [], 0
    for record in manifest["files"]:
        relative = Path(record["path"])
        destination, generated = (root / relative).resolve(), (artifact / "rewritten" / relative).resolve()
        if relative.is_absolute() or root not in destination.parents or artifact not in generated.parents:
            raise ValueError("Unsafe artifact path")
        before, after = destination.read_bytes(), generated.read_bytes()
        if sha(before) != record["originalSha256"]:
            raise ValueError("Stale source input: " + record["path"])
        if sha(after) != record["rewrittenSha256"]:
            raise ValueError("Corrupt generated output: " + record["path"])
        callbacks, helpers = record["callbacks"], record["helpers"]
        if len(callbacks) != len(helpers) or len(callbacks) != record["removedClosures"]:
            raise ValueError("Callback/helper count mismatch")
        if sum(edit["callbacks"] for edit in record["edits"]) != len(callbacks):
            raise ValueError("Operation/callback count mismatch")
        raw = before.decode("utf-8").encode("utf-16-le")
        for callback in callbacks:
            original = raw[callback["start"] * 2:callback["end"] * 2].decode("utf-16-le")
            if original != callback["original"]:
                raise ValueError("Callback source span mismatch")
        if reconstruct(before, record["edits"], record["appendedSource"]) != after:
            raise ValueError("Output differs from exact recorded edits")
        count += len(callbacks)
        if before != after:
            staged.append((destination, after))
    if count != manifest["removedClosures"]:
        raise ValueError("Manifest total mismatch")
    if not args.check_only:
        for destination, after in staged:
            destination.write_bytes(after)
    print(("Validated" if args.check_only else "Applied") +
          f" {count} named callbacks in {len(staged)} files")


if __name__ == "__main__":
    main()
