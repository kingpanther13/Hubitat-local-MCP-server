#!/usr/bin/env python3
"""PR guard: block PRs that modify bookkeeping files.

Release bookkeeping (version strings, packageManifest.json releaseNotes and
dateReleased, README Version History, CHANGELOG.md) is maintained exclusively
by the release bot. Don't hand-write CHANGELOG entries in PRs either — the
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
    # `|\Z` handles the case where Version History is the last section in the
    # file — otherwise the lookahead fails and we silently return empty.
    m = re.search(
        r"^## Version History\s*\n(.*?)(?=^## |\Z)",
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
                "Version bumps in PRs are blocked — the release bot handles this."
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
                "This field cannot be modified in PRs — the release bot handles this."
            )

    before_readme = file_at_ref(base_ref, "README.md")
    after_readme = (ROOT / "README.md").read_text()
    if extract_version_history(before_readme) != extract_version_history(after_readme):
        errors.append(
            "README.md: '## Version History' section changed. "
            "This section cannot be modified in PRs — the release bot handles this."
        )

    before_changelog = file_at_ref(base_ref, "CHANGELOG.md")
    after_changelog = (ROOT / "CHANGELOG.md").read_text()
    if before_changelog and before_changelog != after_changelog:
        errors.append(
            "CHANGELOG.md: changed. This file cannot be modified in PRs — "
            "the release bot generates entries from merged PR titles at release time. "
            "To customize your entry, add a '## Release notes' section to your PR body."
        )

    return errors


def check_agents_claude_sync() -> list[str]:
    """AGENTS.md and CLAUDE.md must coexist and be byte-identical.

    Most agent harnesses (Codex, Cursor, Aider, etc.) auto-load AGENTS.md;
    Claude Code auto-loads CLAUDE.md. Symlinks don't ride cleanly through
    Windows checkouts (core.symlinks=false silently materializes the symlink
    as a 10-byte text file containing the target path), so we ship two real
    files and enforce sync here. Edit AGENTS.md (the source of truth) and
    run `cp AGENTS.md CLAUDE.md` before committing.

    Skip rule: if NEITHER file exists (e.g. fork that hasn't adopted the
    convention), pass silently. If exactly ONE exists, that's the drift case
    we want to catch — fail.
    """
    errors: list[str] = []
    agents = ROOT / "AGENTS.md"
    claude = ROOT / "CLAUDE.md"
    if not agents.exists() and not claude.exists():
        return errors
    if not agents.exists():
        errors.append(
            "CLAUDE.md exists but AGENTS.md is missing. AGENTS.md is the source "
            "of truth — restore it (or `cp CLAUDE.md AGENTS.md` if your edits "
            "are in CLAUDE.md) and commit both."
        )
        return errors
    if not claude.exists():
        errors.append(
            "AGENTS.md exists but CLAUDE.md is missing. Run `cp AGENTS.md CLAUDE.md` "
            "and commit both — Claude Code reads CLAUDE.md, the rest read AGENTS.md."
        )
        return errors
    if agents.read_bytes() != claude.read_bytes():
        errors.append(
            "AGENTS.md and CLAUDE.md have drifted. AGENTS.md is the source of truth — "
            "if you edited CLAUDE.md, port the change back to AGENTS.md first, then "
            "run `cp AGENTS.md CLAUDE.md` and commit both."
        )
    return errors


def check_workflow_main_push_credentials() -> list[str]:
    """Any workflow that pushes to MAIN must check out with the release deploy key.

    Run 27445202166 (post-#269): rebuild-bundle.yml pushed to main with the default
    GITHUB_TOKEN and was rejected by main's ruleset (GH013, required status checks),
    leaving main's committed bundle zip stale against the app's #includes until a
    hotfix. The incompatibility is knowable statically -- release.yml documents that
    a GITHUB_TOKEN push to main is rejected and pushes via secrets.RELEASE_DEPLOY_KEY
    -- so this REQUIRED check fails the PR that wires such a workflow, instead of
    letting it fail on its first post-merge run. (Static limit: this proves the
    credential is WIRED, not that the key works -- release.yml proves the key on
    every release.)
    """
    errors: list[str] = []
    push_to_main = re.compile(r"git push\b[^\n|&;]*\borigin\b[^\n|&;]*\b(?:HEAD:)?main\b")
    deploy_key = "ssh-key: ${{ secrets.RELEASE_DEPLOY_KEY }}"
    workflows = sorted((ROOT / ".github" / "workflows").glob("*.yml"))
    matched: list[str] = []
    for wf in workflows:
        text = wf.read_text(encoding="utf-8")
        if not push_to_main.search(text):
            continue
        matched.append(wf.name)
        if deploy_key not in text:
            errors.append(
                f".github/workflows/{wf.name} pushes to main without checking out via "
                f"'{deploy_key}'. main's ruleset rejects a GITHUB_TOKEN push (GH013, run "
                "27445202166) -- the push will fail on its first post-merge run. Add the "
                "deploy-key checkout the way release.yml and rebuild-bundle.yml do."
            )
    # Self-check against a vacuous pattern: the known main-pushers must match, or the
    # regex has drifted and this guard is silently checking nothing.
    for known in ("release.yml", "rebuild-bundle.yml"):
        if (ROOT / ".github" / "workflows" / known).exists() and known not in matched:
            errors.append(
                f"pr_guard self-check: {known} no longer matches the push-to-main pattern -- "
                "either its push changed shape (update the pattern in "
                "check_workflow_main_push_credentials) or it stopped pushing to main "
                "(update the known-pushers list)."
            )
    return errors


def main() -> int:
    base_ref = os.environ.get("BASE_REF", "origin/main")
    errors = check_bookkeeping(base_ref) + check_agents_claude_sync() + check_workflow_main_push_credentials()
    for e in errors:
        print(f"::error::{e}")
    if errors:
        print(f"\n{len(errors)} PR guard check(s) failed.", file=sys.stderr)
        return 1
    print("PR guard: all checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
