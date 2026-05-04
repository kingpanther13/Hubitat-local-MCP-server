# Gemini Code Assist Style Guide — Hubitat MCP Server

## How to start every PR review

The very first review comment you post on any PR must begin with this exact sentence, verbatim:

> I am an AI and make mistakes. If you feel that any of my review items are incorrect, please don't hesitate to push back.

This is a hard requirement. Authors should know up front that pushback is welcome and respected.

## Dependabot exemption

PRs authored by `dependabot[bot]` are automatic dependency bumps. They use a `build:` title prefix, do not use this repo's PR template, and do not have a `## Release Notes` section by design. **Skip the three custom checks below** for those PRs. Continue to apply your standard code-quality review.

## Repository conventions to refer to

When reviewing code, refer to (do not duplicate or quote) these existing repo docs for project conventions, conventions for tool design, testing patterns, and release automation:

- [README.md](../README.md) — project overview and feature catalog
- [SKILL.md](../SKILL.md) — tool reference and usage patterns
- [TOOL_GUIDE.md](../TOOL_GUIDE.md) — detailed per-tool documentation
- [docs/testing.md](../docs/testing.md) — Spock test harness conventions, including the rule that every new MCP tool must ship with unit tests
- [docs/release-automation-design.md](../docs/release-automation-design.md) — how the release bot consumes PR metadata

If `AGENTS.md` or `CONTRIBUTING.md` exist at the repo root at review time, refer to those as well — they're planned and may be present in newer PRs.

Link to these docs from your review comments when relevant; do not paste their content.

## Custom review checks

The three checks below are **soft requests**, not blockers. Phrase them as suggestions in your review ("Consider adding…", "Could you clarify…"). Do not mark a PR as blocked, do not use a "Request changes" review state, and do not lower the overall review verdict solely because of one of these. The author may push back; if they do, defer to them.

### 1. Release Notes section presence

Every non-Dependabot PR description should contain a `## Release Notes` heading followed by at least one bulleted item (`-` or `*`). The matcher is lenient: the heading match is case-insensitive ("release notes", "Release Notes", "RELEASE NOTES" all valid), an optional trailing colon is fine, and any markdown heading level (h1–h4) counts.

If the section is missing, empty, or contains only prose with no bullets, ask the author to add bulleted release notes. In your comment, explain why this matters: **these bullets are what HPM users see in the update prompt when they upgrade the rule app**. They should be written for end users, not developers — short, scannable, free of internal jargon. Sub-bullets are supported (nested 2-space indent) for grouping related details under one top-level point.

### 2. Title prefix matches the "Type of change" checkbox

Every non-Dependabot PR title should start with one of these prefixes (lowercase, trailing colon):

- `feat:` — new feature
- `fix:` — bug fix
- `chore:` — maintenance / non-feature work
- `refactor:` — code restructuring with no behavior change
- `docs:` — documentation only
- `test:` — tests only
- `ci:` — CI / workflow changes only

(`build:` is reserved for Dependabot — contributors are instructed not to use it.)

The PR template has a "Type of change" checkbox section. The prefix the author chose for the PR title and the box they ticked must agree. If they disagree (for example, the title is `feat:` but the `fix` box is ticked), call it out as a suggestion and ask the author to correct one of them so the metadata is consistent.

### 3. Prefix and checkbox match what the PR actually does

Best-effort judgment: read the diff and decide whether the prefix and ticked checkbox accurately describe the change.

- If the title says `feat:` but the diff is purely an internal refactor with no new user-visible capability, suggest a `refactor:` reclassification.
- If the title says `fix:` but the diff adds a new tool or new behavior, suggest `feat:`.
- If the title says `docs:` but the diff edits Groovy logic, suggest the appropriate code prefix.

This check is judgmental and you may misread the diff. That is expected and acceptable. Raise it as a suggestion only ("This looks more like a refactor than a feature — would you consider relabeling?"), not as a hard finding. Author pushback ends the discussion.
