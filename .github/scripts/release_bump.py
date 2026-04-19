#!/usr/bin/env python3
"""Release bump: gather PRs merged since the last tag, write a new dated
CHANGELOG entry, propagate to packageManifest.json releaseNotes + README
Version History, bump all 4 version strings, update dateReleased.

Invoked by .github/workflows/release.yml after a PR with a release:* label
merges to main. Contributors never touch bookkeeping files — the bot gathers
release notes from merged PR titles (and optional '## Release notes' sections
in PR bodies) since the previous vX.Y.Z tag.

Reads env:
  LABEL               - release:patch | release:minor | release:major
  TRIGGER_PR_NUMBER   - number of the PR that triggered this workflow (fallback)

Outputs (GITHUB_OUTPUT):
  new_version=X.Y.Z

Files modified:
  CHANGELOG.md, packageManifest.json, README.md,
  hubitat-mcp-server.groovy, hubitat-mcp-rule.groovy
"""

import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "packageManifest.json"
CHANGELOG = ROOT / "CHANGELOG.md"
README = ROOT / "README.md"
SERVER = ROOT / "hubitat-mcp-server.groovy"
RULE = ROOT / "hubitat-mcp-rule.groovy"

SEMVER_RE = re.compile(r"^\d+\.\d+\.\d+$")


def run(*args: str, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(args, capture_output=True, text=True, check=check, cwd=ROOT)


def latest_tag() -> str | None:
    """Return the latest 'v*.*.*' tag, or None if no tags exist."""
    try:
        result = run("git", "describe", "--tags", "--abbrev=0", "--match", "v*.*.*")
        tag = result.stdout.strip()
        if SEMVER_RE.match(tag.lstrip("v")):
            return tag
    except subprocess.CalledProcessError:
        pass
    return None


def current_manifest_version() -> str:
    version = json.loads(MANIFEST.read_text())["version"]
    if not SEMVER_RE.match(version):
        raise ValueError(f"Manifest version not strict semver: {version!r}")
    return version


def compute_next(current: str, label: str) -> str:
    major, minor, patch = (int(x) for x in current.split("."))
    if label == "release:major":
        return f"{major + 1}.0.0"
    if label == "release:minor":
        return f"{major}.{minor + 1}.0"
    if label == "release:patch":
        return f"{major}.{minor}.{patch + 1}"
    raise ValueError(f"Unknown release label: {label!r}")


def merged_pr_numbers_since(tag: str | None) -> list[int]:
    """Return PR numbers referenced in commit subjects between `tag` (exclusive)
    and HEAD, in chronological order (oldest first). Handles both merge-commit
    format ('Merge pull request #NN') and squash-merge format ('Title (#NN)').
    Deduplicates in case multiple commits reference the same PR.
    """
    if tag is None:
        return []
    try:
        result = run("git", "log", f"{tag}..HEAD", "--format=%s")
    except subprocess.CalledProcessError:
        return []
    numbers: list[int] = []
    seen: set[int] = set()
    pr_re = re.compile(r"(?:Merge pull request #| \(#)(\d+)\b")
    for line in result.stdout.splitlines():
        m = pr_re.search(line)
        if m:
            n = int(m.group(1))
            if n not in seen:
                seen.add(n)
                numbers.append(n)
    return list(reversed(numbers))


def fetch_pr(number: int) -> dict | None:
    """Fetch PR metadata via gh CLI. Returns None on failure."""
    try:
        result = run("gh", "pr", "view", str(number), "--json", "number,title,url,body,author")
    except subprocess.CalledProcessError:
        return None
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError:
        return None


def extract_release_notes_override(body: str) -> str | None:
    """Return the content of a '## Release notes' section in a PR body, or None.

    Lets a contributor override the default (PR title) with richer prose.
    """
    if not body:
        return None
    m = re.search(
        r"^##\s+Release [Nn]otes\s*\n(.*?)(?=^##\s|\Z)",
        body,
        re.MULTILINE | re.DOTALL,
    )
    if not m:
        return None
    content = m.group(1).strip()
    return content or None


def build_bullets(pr_numbers: list[int]) -> list[str]:
    """Return one markdown bullet per PR: title + PR link + author."""
    bullets = []
    for n in pr_numbers:
        pr = fetch_pr(n)
        if pr is None:
            bullets.append(f"- PR #{n}")
            continue
        author = (pr.get("author") or {}).get("login", "")
        url = pr["url"]
        override = extract_release_notes_override(pr.get("body") or "")
        body = override or pr["title"]
        # Flatten multi-line overrides so bullets stay valid markdown on one line.
        body = " ".join(body.splitlines()).strip()
        suffix = f"([#{n}]({url}), @{author})" if author else f"([#{n}]({url}))"
        bullets.append(f"- {body} {suffix}")
    return bullets


def bump_groovy_header(path: Path, new_version: str) -> None:
    text = path.read_text()
    new_text, n = re.subn(
        r"^(\s*\*\s*Version:\s*)\d+\.\d+\.\d+(.*)$",
        rf"\g<1>{new_version}\g<2>",
        text,
        count=1,
        flags=re.MULTILINE,
    )
    if n != 1:
        raise RuntimeError(f"Could not find version header in {path.name}")
    path.write_text(new_text)


def bump_current_version_fn(path: Path, new_version: str) -> None:
    text = path.read_text()
    new_text, n = re.subn(
        r'(def\s+currentVersion\s*\(\)\s*\{\s*\n\s*return\s+")\d+\.\d+\.\d+(")',
        rf"\g<1>{new_version}\g<2>",
        text,
        count=1,
    )
    if n != 1:
        raise RuntimeError(f"Could not find currentVersion() in {path.name}")
    path.write_text(new_text)


def bump_manifest(new_version: str, paragraph: str) -> None:
    manifest = json.loads(MANIFEST.read_text())
    manifest["version"] = new_version
    manifest["dateReleased"] = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    manifest["releaseNotes"] = paragraph + "\n\n" + manifest["releaseNotes"]
    MANIFEST.write_text(json.dumps(manifest, indent=4, ensure_ascii=False) + "\n")
    json.loads(MANIFEST.read_text())  # round-trip validation


def prepend_changelog_entry(new_version: str, date: str, bullets: list[str]) -> None:
    text = CHANGELOG.read_text()
    entry = f"## [{new_version}] - {date}\n\n" + "\n".join(bullets) + "\n\n"
    m = re.search(r"^## \[", text, re.MULTILINE)
    if m is None:
        raise RuntimeError("CHANGELOG.md has no existing '## [X.Y.Z]' heading to insert before")
    new_text = text[: m.start()] + entry + text[m.start():]
    CHANGELOG.write_text(new_text)


def prepend_readme_bullet(new_version: str, bullets: list[str]) -> None:
    """Prepend a single compact bullet summarizing all PRs in this release."""
    text = README.read_text()
    summaries = []
    refs = []
    for b in bullets:
        body = b[2:].strip()
        link_match = re.search(r"\[#(\d+)\]\(([^)]+)\)", body)
        if link_match:
            refs.append(link_match.group(0))
            # Author suffix is optional (author may be empty).
            body = re.sub(
                r"\s*\(\[#\d+\]\([^)]+\)(?:,?\s*@[\w-]*)?\)\s*$",
                "",
                body,
            ).strip()
        summaries.append(body.rstrip("."))
    summary = "; ".join(summaries)
    bullet = (
        f"- **v{new_version}** - {summary}. PRs: {', '.join(refs)}\n"
        if refs
        else f"- **v{new_version}** - {summary}\n"
    )
    new_text, n = re.subn(
        r"(## Version History\s*\n+)",
        rf"\g<1>{bullet}",
        text,
        count=1,
    )
    if n != 1:
        raise RuntimeError("README.md missing '## Version History' section")
    README.write_text(new_text)


def paragraph_from_bullets(bullets: list[str]) -> str:
    """Flatten bullets into one prose paragraph for packageManifest.json releaseNotes.

    Converts '[#63](url)' → '#63 (url)' so HPM (which may render as plain text)
    still shows a usable link.
    """
    parts = []
    for b in bullets:
        text = b[2:].strip()
        text = re.sub(r"\[#(\d+)\]\(([^)]+)\)", r"#\1 (\2)", text)
        parts.append(text.rstrip("."))
    return ". ".join(parts) + "."


def write_github_output(key: str, value: str) -> None:
    path = os.environ.get("GITHUB_OUTPUT")
    if path:
        with open(path, "a") as f:
            f.write(f"{key}={value}\n")


def main() -> int:
    label = os.environ.get("LABEL", "").strip()
    trigger_pr = os.environ.get("TRIGGER_PR_NUMBER", "").strip()
    if not label:
        print("::error::LABEL env var not set", file=sys.stderr)
        return 1

    tag = latest_tag()
    current = tag.lstrip("v") if tag else current_manifest_version()
    new_version = compute_next(current, label)
    date = datetime.now(timezone.utc).strftime("%Y-%m-%d")

    pr_numbers = merged_pr_numbers_since(tag)
    if not pr_numbers and trigger_pr:
        pr_numbers = [int(trigger_pr)]
    if not pr_numbers:
        print("::warning::No PRs found to include in release; using placeholder entry")
        bullets = [f"- Release {new_version}"]
    else:
        bullets = build_bullets(pr_numbers)

    paragraph = f"v{new_version} - " + paragraph_from_bullets(bullets)

    prepend_changelog_entry(new_version, date, bullets)
    bump_groovy_header(SERVER, new_version)
    bump_current_version_fn(SERVER, new_version)
    bump_groovy_header(RULE, new_version)
    bump_manifest(new_version, paragraph)
    prepend_readme_bullet(new_version, bullets)

    write_github_output("new_version", new_version)
    print(f"::notice::Bumped {current} -> {new_version} ({len(bullets)} PR(s))")
    return 0


if __name__ == "__main__":
    sys.exit(main())
