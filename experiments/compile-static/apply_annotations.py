"""Apply only the exact method names accepted by the remote discovery artifact."""

import argparse
import json
import re
from pathlib import Path

ANNOTATION = "@groovy.transform.CompileStatic"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("selection", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    names = json.loads(args.selection.read_text(encoding="utf-8"))
    if not isinstance(names, list) or not names or len(set(names)) != len(names):
        raise ValueError("Expected a nonempty list of unique method names")
    files = [root / "hubitat-mcp-server.groovy", *sorted((root / "libraries").glob("*.groovy"))]
    texts = {path: path.read_bytes().decode("utf-8") for path in files}
    changes: dict[Path, list[tuple[int, str]]] = {}
    for name in names:
        if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z_]\w*", name):
            raise ValueError(f"Invalid method name: {name!r}")
        pattern = re.compile(
            rf"(?m)^(?:(?:private|protected|public|static|final)\s+)*"
            rf"(?:[A-Za-z_][\w.<>, ?\[\]]*\s+)?{re.escape(name)}\s*\("
        )
        matches = [(path, match) for path, text in texts.items() for match in pattern.finditer(text)]
        if len(matches) != 1:
            raise ValueError(f"{name}: expected exactly one source definition, found {len(matches)}")
        path, match = matches[0]
        if texts[path][:match.start()].rstrip().endswith(ANNOTATION):
            continue
        changes.setdefault(path, []).append((match.start(), name))
    # Validate every lookup before changing any source file; preserve its newline style.
    for path, insertions in changes.items():
        text = texts[path]
        newline = "\r\n" if "\r\n" in text else "\n"
        for offset, name in sorted(insertions, reverse=True):
            text = text[:offset] + ANNOTATION + newline + text[offset:]
            print(f"{path.relative_to(root)}: {name}")
        path.write_bytes(text.encode("utf-8"))


if __name__ == "__main__":
    main()
