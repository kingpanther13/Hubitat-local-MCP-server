"""Select only branch-changed source files for the manual CodeQL lane."""

import json
import os
import subprocess
import sys
from pathlib import Path, PurePosixPath

REFERENCE_ROOT = "resources/hub2-source/"


def select_paths(paths, language):
    selected = []
    for path in paths:
        if path.startswith(REFERENCE_ROOT):
            continue
        suffix = PurePosixPath(path).suffix.lower()
        matches = {
            "python": suffix in {".py", ".pyw"},
            "javascript": suffix in {".js", ".jsx", ".mjs", ".cjs", ".ts", ".tsx", ".mts", ".cts"},
            "actions": suffix in {".yml", ".yaml"} and (
                path.startswith(".github/workflows/") or PurePosixPath(path).name in {"action.yml", "action.yaml"}
            ),
        }
        if matches[language]:
            # CodeQL paths are glob patterns; do not silently broaden an odd filename.
            if any(char in path for char in "*?[]!\\"):
                raise ValueError(f"Cannot represent this source path as an exact CodeQL filter: {path}")
            selected.append(path)
    return sorted(set(selected))


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args]).decode("utf-8")


def main(argv):
    head, base, language, output = argv
    destination = Path(output)
    destination.mkdir(parents=True, exist_ok=True)
    base_sha = git(base, "rev-parse", "HEAD").strip()
    changed = git(head, "diff", "--no-renames", "--name-only", "-z", "--diff-filter=ACM", f"{base_sha}...HEAD").split("\0")
    head_paths = select_paths(filter(None, changed), language)
    base_paths = [path for path in head_paths if (Path(base) / path).is_file()]
    scope = {
        "head_sha": git(head, "rev-parse", "HEAD").strip(),
        "base_sha": base_sha,
        "merge_base": git(head, "merge-base", base_sha, "HEAD").strip(),
        "head_paths": head_paths,
        "base_paths": base_paths,
        "excluded_reference_paths": [path for path in changed if path.startswith(REFERENCE_ROOT)],
    }
    (destination / "scope.json").write_text(json.dumps(scope, indent=2) + "\n", encoding="utf-8")
    for revision, paths in (("head", head_paths), ("base", base_paths)):
        (destination / f"{revision}-config.json").write_text(json.dumps({"paths": paths}) + "\n", encoding="utf-8")
    with Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as stream:
        stream.write(f"head={str(bool(head_paths)).lower()}\nbase={str(bool(base_paths)).lower()}\n")
    print(json.dumps(scope, indent=2))


if __name__ == "__main__":
    main(sys.argv[1:])
