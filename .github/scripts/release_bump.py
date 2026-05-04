#!/usr/bin/env python3
"""Release bump: gather PRs merged since the last tag, write a new dated
CHANGELOG entry, propagate to packageManifest.json releaseNotes + README
Version History, bump all 4 version strings, update dateReleased.

Invoked by .github/workflows/release.yml after a PR with a release:* label
merges to main. Contributors never touch bookkeeping files — the bot gathers
release notes from each PR's title plus an optional `## Release Notes`
section in the PR body since the previous vX.Y.Z tag.

Release-notes parser semantics (see parse_release_notes):
  - Heading match is case-insensitive ('release notes', any heading level,
    optional trailing colon).
  - Only top-level bullets ('- ' / '* ') and their nested sub-bullets are
    collected. Prose and blank lines inside the section are silently skipped.
  - The next markdown heading terminates the section.
  - If no section is present or the section has no bullets, the release
    entry falls back to title-only for that PR.

Manifest releaseNotes accumulation (see bump_manifest):
  - release:patch keeps prior entries from the existing releaseNotes that
    match the new version's MAJOR.MINOR, dropping everything older. So
    v0.11.1 retains v0.11.0; v0.11.2 retains v0.11.0/v0.11.1; etc.
  - release:minor and release:major reset the field entirely — only the
    new entry remains. CHANGELOG.md keeps the full history for repo
    browsers; the manifest stays scoped so HPM update prompts aren't a
    65 KB wall of text.

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
    """Return the latest 'v*.*.*' tag, or None if no tags exist OR if the tag
    is found but doesn't match strict semver.

    Two distinct failure modes that look similar at the call site but mean
    different things:
      - `subprocess.CalledProcessError` ⇒ git found no matching tag (normal
        first-run state). Return None silently.
      - tag exists but isn't strict semver (e.g. someone pushed `v0.10.0-rc1`
        or `v0.10.0 ` with trailing whitespace) ⇒ emit a `::warning::` so the
        run page surfaces the malformed tag. Still return None so the caller
        falls back to the manifest version, but the operator now knows.
    """
    try:
        result = run("git", "describe", "--tags", "--abbrev=0", "--match", "v*.*.*")
    except subprocess.CalledProcessError:
        return None
    tag = result.stdout.strip()
    if SEMVER_RE.match(tag.lstrip("v")):
        return tag
    print(
        f"::warning::latest_tag: tag {tag!r} does not match strict semver "
        "(X.Y.Z); falling back to manifest version. Fix the tag or this "
        "release will compute its bump from the wrong base.",
        file=sys.stderr,
    )
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
    """Fetch PR metadata via gh CLI. Returns None on failure.

    On failure, emits a `::warning::` so the run page surfaces the issue.
    Without this, the placeholder bullet `- PR #NN` looks identical to a
    success path in every artifact (CHANGELOG, manifest, README), and the
    workflow turns green while shipping garbage.

    Common failures: gh CLI missing, GH_TOKEN expired or lacking
    `pull-requests: read` scope, GitHub API outage, deleted PR number,
    network blip.
    """
    try:
        result = run(
            "gh", "pr", "view", str(number),
            "--json", "number,title,url,author,body",
        )
    except subprocess.CalledProcessError as e:
        stderr = (e.stderr or "").strip().splitlines()[-1] if e.stderr else "(no stderr)"
        print(
            f"::warning::fetch_pr: gh CLI failed for PR #{number}: {stderr}. "
            "Bullet will fall back to '- PR #N' placeholder.",
            file=sys.stderr,
        )
        return None
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError as e:
        print(
            f"::warning::fetch_pr: gh CLI returned non-JSON for PR #{number}: {e}. "
            "Bullet will fall back to '- PR #N' placeholder.",
            file=sys.stderr,
        )
        return None


_RELEASE_NOTES_HEADING_RE = re.compile(r"^#+\s*release\s+notes\s*:?\s*$", re.IGNORECASE)
_NEXT_HEADING_RE = re.compile(r"^#+\s+\S")
_TOP_BULLET_RE = re.compile(r"^[-*]\s+\S")
_NESTED_BULLET_RE = re.compile(r"^\s+[-*]\s+\S")


def parse_release_notes(body: str | None) -> list[str]:
    """Extract bulleted items from a `## Release Notes` section in a PR body.

    Heading match is case-insensitive, allows any markdown heading level
    (h1-h6), and an optional trailing colon. Only top-level bullets ('- '
    or '* ' at column 0) and their nested sub-bullets (any indented '- ' /
    '* ' line) are collected. Prose and blank lines inside the section are
    silently skipped — they do NOT terminate the section. The next
    markdown heading terminates the section.

    Returns a list of bullet strings, each potentially multi-line (the
    top-level bullet line plus any nested sub-bullet lines underneath it).
    Empty list if no Release Notes section is found or if the section
    contains no bullets.

    Examples
    --------
    >>> parse_release_notes("## Release Notes\\n- one\\n- two")
    ['- one', '- two']

    >>> parse_release_notes("## Release Notes\\n- main\\n  - sub\\n- another")
    ['- main\\n  - sub', '- another']

    >>> parse_release_notes("Some intro\\n## release notes:\\n- ok")
    ['- ok']

    >>> parse_release_notes("## Release Notes\\n- ok\\n## Next section\\n- ignored")
    ['- ok']

    >>> parse_release_notes("No section here at all")
    []
    """
    if not body:
        return []

    lines = body.splitlines()
    section_start = None
    for i, line in enumerate(lines):
        if _RELEASE_NOTES_HEADING_RE.match(line):
            section_start = i + 1
            break
    if section_start is None:
        return []

    bullets: list[str] = []
    current: list[str] = []

    def flush() -> None:
        if current:
            bullets.append("\n".join(current))
            current.clear()

    for line in lines[section_start:]:
        if _NEXT_HEADING_RE.match(line):
            break
        if _TOP_BULLET_RE.match(line):
            flush()
            current.append(line.rstrip())
        elif _NESTED_BULLET_RE.match(line) and current:
            current.append(line.rstrip())
        # else: prose, blank line, or indented bullet with no parent — skip

    flush()
    return bullets


def _has_release_notes_section(body: str | None) -> bool:
    """Return True if the body contains a `## Release Notes`-ish heading.

    Used by build_bullets to distinguish "PR has no Release Notes section"
    from "PR has a Release Notes section but it has no bullets" — the second
    case means the author wrote prose-only content that parse_release_notes
    silently dropped, which is a degradation worth surfacing to the
    workflow log so it gets fixed before the release ships.
    """
    if not body:
        return False
    return any(
        _RELEASE_NOTES_HEADING_RE.match(line)
        for line in body.splitlines()
    )


def build_bullets(pr_numbers: list[int]) -> list[str]:
    """Return one bullet per PR.

    Each bullet is the PR title with full PR reference (number, URL,
    author) in markdown form for CHANGELOG.md. If the PR body has a
    `## Release Notes` section with bulleted items, those bullets are
    appended as nested sub-bullets indented 2 spaces under the title line.
    Sub-bullets within the author's release notes (indented further)
    are preserved at their original indent — markdown nesting carries
    through unchanged.

    The bullet shape produced here is the "rich" CHANGELOG-friendly form;
    `manifest_block_from_bullets` derives the plain-text form HPM displays.

    If a PR body has a `## Release Notes` section but it contains only
    prose (no bullets), a `::warning::` is emitted: the author signaled
    intent to write release notes, but parse_release_notes silently dropped
    the content because it isn't bulleted. The bullet falls back to title-
    only either way, but the warning makes the degradation visible.
    """
    bullets = []
    for n in pr_numbers:
        pr = fetch_pr(n)
        if pr is None:
            bullets.append(f"- PR #{n}")
            continue
        author = (pr.get("author") or {}).get("login", "")
        url = pr["url"]
        title = " ".join(pr["title"].splitlines()).strip()
        suffix = f"([#{n}]({url}), @{author})" if author else f"([#{n}]({url}))"
        header = f"- {title} {suffix}"

        body = pr.get("body")
        notes = parse_release_notes(body)
        if notes:
            indented = "\n".join(
                f"  {line}" for note in notes for line in note.splitlines()
            )
            bullets.append(f"{header}\n{indented}")
            continue

        # Section-was-found-but-empty path: author intended release notes but
        # wrote only prose. Surface it; the operator can decide to re-run after
        # asking the author to bullet-format their content.
        if _has_release_notes_section(body):
            print(
                f"::warning::PR #{n} has a `## Release Notes` section but no "
                "bulleted items — falling back to title-only. Ask the author to "
                "reformat their notes as bullets ('- ' or '* ') so they reach "
                "HPM users.",
                file=sys.stderr,
            )

        bullets.append(header)
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


_VERSION_BLOCK_HEADER_RE = re.compile(r"^v(\d+\.\d+\.\d+)\b.*$", re.MULTILINE)


def split_release_blocks(text: str) -> list[tuple[str, str]]:
    """Split a releaseNotes string into per-version blocks.

    A block starts at a line beginning `vMAJOR.MINOR.PATCH` and runs until
    the next such line or the end of the text. Returns a list of
    `(version, block_text)` tuples in the order they appear.

    Tolerates extra whitespace/blank lines between blocks. Does NOT
    validate that the block contents match any expected shape — this is
    used both on the new structured format and on legacy run-on prose
    entries that may still be present in the manifest at first-run time.
    """
    matches = list(_VERSION_BLOCK_HEADER_RE.finditer(text))
    blocks = []
    for i, m in enumerate(matches):
        version = m.group(1)
        start = m.start()
        end = matches[i + 1].start() if i + 1 < len(matches) else len(text)
        blocks.append((version, text[start:end].rstrip()))
    return blocks


def filter_same_minor(
    blocks: list[tuple[str, str]], new_version: str
) -> list[tuple[str, str]]:
    """Keep only blocks whose version's MAJOR.MINOR matches new_version's."""
    target = ".".join(new_version.split(".")[:2])
    return [(v, b) for v, b in blocks if ".".join(v.split(".")[:2]) == target]


def manifest_block_from_bullets(
    bullets: list[str], version: str, date: str
) -> str:
    """Build the HPM-facing per-release block.

    Format:
        v<version> - <date>
        - PR Title (#<n>)
          - Release note bullet 1
          - Release note bullet 2
        - Another PR (#<n>)

    The CHANGELOG-style ([#NN](url), @author) suffix on each title line is
    rewritten to plain `(#NN)` — HPM renders this field as plain text, so
    raw markdown links would look ugly to end users, and the @author handle
    is developer metadata that doesn't help someone deciding whether to
    update. CHANGELOG.md keeps the full reference for repo browsers.

    Sub-bullets (indented continuations of a bullet) pass through unchanged.
    """
    plain_bullets = []
    for b in bullets:
        if not b:
            continue
        lines = b.splitlines()
        first_line = re.sub(
            r"\(\[#(\d+)\]\([^)]+\)(?:,\s*@[\w-]*)?\)",
            r"(#\1)",
            lines[0],
        )
        plain_bullets.append("\n".join([first_line] + lines[1:]))

    body = "\n".join(plain_bullets)
    return f"v{version} - {date}\n{body}"


def bump_manifest(new_version: str, label: str, new_block: str) -> None:
    """Write `new_block` into manifest.releaseNotes with same-minor accumulation.

    For release:patch, prior entries with the same MAJOR.MINOR as
    `new_version` are preserved in order (so 0.11.1 retains 0.11.0; 0.11.2
    retains both 0.11.0 and 0.11.1). Entries from older minors are dropped.

    For release:minor and release:major the field is replaced with just
    `new_block` — the new minor or major starts the HPM-facing history
    fresh. CHANGELOG.md preserves the full long-term record.
    """
    manifest = json.loads(MANIFEST.read_text())
    manifest["version"] = new_version
    manifest["dateReleased"] = datetime.now(timezone.utc).strftime("%Y-%m-%d")

    if label == "release:patch":
        existing_blocks = split_release_blocks(manifest.get("releaseNotes") or "")
        kept = filter_same_minor(existing_blocks, new_version)
        # Sanity check on each kept block's body shape. The splitter regex looks
        # for line-starts matching `vMAJOR.MINOR.PATCH`, which would also match
        # if a hand-edit ever introduced a continuation line beginning with
        # `vX.Y.Z`. A "block" with no bullet body and no other content is the
        # tell — emit a warning so the operator can sanity-check the manifest
        # before the next release. We still keep the block (don't drop content
        # silently) so legacy hand-edited entries survive.
        for version, block_text in kept:
            body_lines = [ln for ln in block_text.splitlines()[1:] if ln.strip()]
            if body_lines and not any(ln.lstrip().startswith(("-", "*"))
                                       for ln in body_lines):
                print(
                    f"::warning::bump_manifest: prior block v{version} has no "
                    "bullet body — keeping it as-is, but verify it isn't a "
                    "split-mid-prose artifact from a legacy hand-edit. "
                    "First non-blank body line: "
                    f"{body_lines[0][:80]!r}",
                    file=sys.stderr,
                )

        kept_text = "\n\n".join(b for _, b in kept)
        manifest["releaseNotes"] = (
            f"{new_block}\n\n{kept_text}" if kept_text else new_block
        )
    else:
        manifest["releaseNotes"] = new_block

    MANIFEST.write_text(json.dumps(manifest, indent=4, ensure_ascii=False) + "\n")

    # Real post-write assertions (replaces the previous round-trip-loads which
    # only confirmed the file was still parseable JSON — a property json.dumps
    # guarantees by definition). Catch the failure modes that actually matter:
    # a regression to the field-assignment ordering, an empty releaseNotes
    # field, or the new block somehow not making it in.
    written = json.loads(MANIFEST.read_text())
    assert written["version"] == new_version, (
        f"manifest version after write: {written.get('version')!r} != "
        f"expected {new_version!r}"
    )
    assert written.get("releaseNotes"), "releaseNotes is empty after write"
    assert new_block in written["releaseNotes"], (
        "new_block is missing from written releaseNotes — the assignment "
        "branch must have dropped it"
    )


def prepend_changelog_entry(new_version: str, date: str, bullets: list[str]) -> None:
    text = CHANGELOG.read_text()
    # Keep the list tight (no blank lines between bullets, so renders without
    # per-item <p> wrappers) when every bullet is single-line. If any bullet
    # has an indented continuation, the list is already loose by virtue of
    # the internal newlines inside that bullet, so use \n\n separators to
    # keep the output visually readable in the raw source.
    any_multiline = any("\n" in b for b in bullets)
    separator = "\n\n" if any_multiline else "\n"
    entry = f"## [{new_version}] - {date}\n\n" + separator.join(bullets) + "\n\n"
    m = re.search(r"^## \[", text, re.MULTILINE)
    if m is None:
        raise RuntimeError("CHANGELOG.md has no existing '## [X.Y.Z]' heading to insert before")
    new_text = text[: m.start()] + entry + text[m.start():]
    CHANGELOG.write_text(new_text)


def bullet_title_line(bullet: str) -> str:
    """Return just the first line of a bullet (the title/link line), stripping
    any indented continuation that follows it."""
    first = bullet.splitlines()[0] if bullet else ""
    return first.strip()


def prepend_readme_bullet(new_version: str, bullets: list[str]) -> None:
    """Prepend a single compact bullet summarizing all PRs in this release.

    The README Version History entry keeps only the titles + PR refs — the
    extended descriptions live in CHANGELOG.md and packageManifest.json so
    README stays scannable.
    """
    text = README.read_text()
    summaries = []
    refs = []
    for b in bullets:
        title_line = bullet_title_line(b)
        body = title_line[2:].strip() if title_line.startswith("- ") else title_line
        link_match = re.search(r"\[#(\d+)\]\(([^)]+)\)", body)
        if link_match:
            refs.append(link_match.group(0))
            # Author suffix is optional (author may be empty).
            body = re.sub(
                r"\s*\(\[#\d+\]\([^)]+\)(?:,?\s*@[\w-]*)?\)\s*$",
                "",
                body,
            ).strip()
        summaries.append(_strip_trailing_period(body))
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


def _strip_trailing_period(text: str) -> str:
    """Remove exactly ONE trailing period, preserving ellipses or version-like
    suffixes (e.g., 'v1.0.' keeps the penultimate '.0', an ellipsis '...'
    stays intact)."""
    return re.sub(r"(?<=[^.])\.$", "", text)


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

    new_manifest_block = manifest_block_from_bullets(bullets, new_version, date)

    prepend_changelog_entry(new_version, date, bullets)
    bump_groovy_header(SERVER, new_version)
    bump_current_version_fn(SERVER, new_version)
    bump_groovy_header(RULE, new_version)
    bump_manifest(new_version, label, new_manifest_block)
    prepend_readme_bullet(new_version, bullets)

    write_github_output("new_version", new_version)
    print(f"::notice::Bumped {current} -> {new_version} ({len(bullets)} PR(s))")
    return 0


if __name__ == "__main__":
    sys.exit(main())
