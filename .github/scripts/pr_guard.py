#!/usr/bin/env python3
"""PR guard: block contributor PRs that modify bookkeeping files.

Release bookkeeping (version strings, packageManifest.json releaseNotes and
dateReleased, README Version History, CHANGELOG.md) is maintained exclusively
by the release bot. Contributors don't write CHANGELOG entries either — the
bot generates CHANGELOG entries from merged PR titles (and optional
'## Release notes' sections in PR bodies) at release time.

Reads env:
  BASE_REF - git ref of PR base branch (e.g., 'origin/main')

Exits 0 on pass, 1 on fail. Emits ::error:: annotations for CI.
"""

import json
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def git(*args: str) -> str:
    return subprocess.run(
        ["git", *args], capture_output=True, text=True, check=True, cwd=ROOT
    ).stdout


def file_at_ref(ref: str, path: str) -> str:
    try:
        return git("show", f"{ref}:{path}")
    except subprocess.CalledProcessError:
        return ""


def extract_first(text: str, pattern: str, flags: int = 0) -> str | None:
    m = re.search(pattern, text, flags)
    return m.group(1) if m else None


def extract_version_history(readme: str) -> str:
    m = re.search(
        r"^## Version History\s*\n(.*?)(?=^## )",
        readme,
        re.MULTILINE | re.DOTALL,
    )
    return m.group(1) if m else ""


def check_bookkeeping(base_ref: str) -> list[str]:
    errors: list[str] = []

    version_sources = [
        (
            "hubitat-mcp-server.groovy",
            r"^\s*\*\s*Version:\s*(\d+\.\d+\.\d+)",
            re.MULTILINE,
            "server header comment",
        ),
        (
            "hubitat-mcp-server.groovy",
            r'def\s+currentVersion\s*\(\)\s*\{\s*\n\s*return\s+"(\d+\.\d+\.\d+)"',
            0,
            "currentVersion() return value",
        ),
        (
            "hubitat-mcp-rule.groovy",
            r"^\s*\*\s*Version:\s*(\d+\.\d+\.\d+)",
            re.MULTILINE,
            "rule header comment",
        ),
    ]
    for path, pattern, flags, label in version_sources:
        before = extract_first(file_at_ref(base_ref, path), pattern, flags)
        after = extract_first((ROOT / path).read_text(), pattern, flags)
        if before != after:
            errors.append(
                f"{path}: {label} changed ({before} -> {after}). "
                "Contributors cannot bump versions — the release bot handles this."
            )

    before_manifest_text = file_at_ref(base_ref, "packageManifest.json")
    after_manifest_text = (ROOT / "packageManifest.json").read_text()
    try:
        before_manifest = json.loads(before_manifest_text) if before_manifest_text else {}
        after_manifest = json.loads(after_manifest_text)
    except json.JSONDecodeError as e:
        errors.append(f"packageManifest.json: invalid JSON ({e}).")
        return errors

    for field in ("version", "dateReleased", "releaseNotes"):
        if before_manifest.get(field) != after_manifest.get(field):
            errors.append(
                f"packageManifest.json: '{field}' changed. "
                "Contributors cannot modify this field — the release bot handles this."
            )

    before_readme = file_at_ref(base_ref, "README.md")
    after_readme = (ROOT / "README.md").read_text()
    if extract_version_history(before_readme) != extract_version_history(after_readme):
        errors.append(
            "README.md: '## Version History' section changed. "
            "Contributors cannot modify this section — the release bot handles this."
        )

    before_changelog = file_at_ref(base_ref, "CHANGELOG.md")
    after_changelog = (ROOT / "CHANGELOG.md").read_text()
    if before_changelog and before_changelog != after_changelog:
        errors.append(
            "CHANGELOG.md: changed. Contributors cannot modify CHANGELOG — "
            "the release bot generates entries from merged PR titles at release time. "
            "To customize your entry, add a '## Release notes' section to your PR body."
        )

    return errors


def main() -> int:
    base_ref = os.environ.get("BASE_REF", "origin/main")
    errors = check_bookkeeping(base_ref)
    for e in errors:
        print(f"::error::{e}")
    if errors:
        print(f"\n{len(errors)} PR guard check(s) failed.", file=sys.stderr)
        return 1
    print("PR guard: all checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
