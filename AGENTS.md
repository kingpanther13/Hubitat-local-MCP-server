# AGENTS.md — instructions for AI contributors

This file is the contract for any AI agent (Claude Code, Codex, Cursor, Aider, Copilot, Gemini Code Assist, web-based Claude with the HA-MCP connector, etc.) opening or modifying a PR against this repo. Read it once at the start of a session and follow it.

Humans: this is an AI-only contract. The equivalent rules for human contributors are encoded in `.github/pull_request_template.md` and `.gemini/styleguide.md`.

## TL;DR

1. **Use the PR template.** `.github/pull_request_template.md` is mandatory — don't strip its sections.
2. **Title prefix matches the ticked "Type of change" checkbox.** `feat:` / `fix:` / `chore:` / `refactor:` / `docs:` / `test:` / `ci:`. (`build:` is reserved for Dependabot.)
3. **Mandatory `## Release Notes` section with bullets.** This is what HPM users see in the update prompt. Write for end users, not developers.
4. **Don't bump versions, don't edit `packageManifest.json` `releaseNotes`/`dateReleased`, don't edit `CHANGELOG.md`, don't edit `README.md` Version History.** All bookkeeping is bot-only and `pr_guard.py` will block your PR if you touch any of it.
5. **Open PRs as drafts.** The maintainer (kingpanther13) flips ready-for-review themselves.
6. **Sign commits with the noreply email** (`kingpanther13@users.noreply.github.com`) when committing on the maintainer's behalf — GitHub blocks pushes signed with the private email.

If any of this conflicts with the maintainer's direct instructions in your session, follow the maintainer.

## PR conventions

### Required body sections

The PR template at `.github/pull_request_template.md` is the source of truth. Every non-Dependabot PR body MUST contain:

- `## Summary` — 1–3 bullets: what + why
- `## Type of change` — checklist; tick exactly one prefix
- `## Changes` — key changes; reference issue numbers (Closes #N / Fixes #N / Part of #N)
- `## Release Notes` — **bulleted, user-facing notes**. This section feeds HPM's `packageManifest.json` `releaseNotes` field via `.github/scripts/release_bump.py`. End users see these bullets in the HPM update prompt. Sub-bullets are supported (2-space indent). Prose is silently skipped; sections with no bullets emit a `::warning::` at release time.
- `## Testing` — how you verified
- `## Checklist` — tick everything that applies

Verify before claiming a PR is ready (where `<N>` is the pull request number):

```bash
gh pr view <N> --json body --jq '.body' | grep -iE "^#+ (Type of change|Release Notes):?\s*"
```

The grep matches the lenient form `.gemini/styleguide.md` defines: case-insensitive, any heading level (h1–h6), optional trailing colon. Both lines must appear, and the Release Notes section must contain at least one `- ` bullet. Run this every time, including when rebasing or editing an existing PR — older PRs may pre-date the template.

### Title prefix ↔ checkbox congruence

The prefix on the PR title and the box ticked under `## Type of change` must agree (`feat:` ↔ feat, `fix:` ↔ fix, etc.). Gemini Code Assist requests this on every review. Both should also reflect what the diff actually does — a `feat:` whose diff is purely an internal refactor should be relabeled.

### What `## Release Notes` should and shouldn't contain

| ✅ Write this | ❌ Skip this |
|---|---|
| New tools / new behaviour / opt-in flags | Internal refactors |
| Default changes that affect existing installs | Test additions |
| Migration steps / things to do after updating | Line-count metrics |
| User-visible bug fixes | PR/issue numbers (the bot adds them) |
| Compatibility notes | Implementation file names |

End users updating via HPM see exactly what's in this section. Write short, scannable, jargon-free.

## Bookkeeping is bot-only

`pr_guard.py` (CI: `.github/workflows/pr-guard.yml`) blocks contributor PRs that modify any of:

- Version strings in `hubitat-mcp-server.groovy`, `hubitat-mcp-rule.groovy`, `packageManifest.json`, `README.md`, or anywhere else (the post-merge release bot bumps every location atomically)
- `packageManifest.json` `releaseNotes` field — bot derives this from your PR's `## Release Notes` section at release time
- `packageManifest.json` `dateReleased` field — bot sets this at release time
- `README.md` `## Version History` section — bot prepends entries at release time
- `CHANGELOG.md` — bot generates entries from merged PR titles + bodies at release time

If you need to communicate something to end users, the path is your PR's `## Release Notes` section, not direct edits to the bookkeeping files. See [docs/release-automation-design.md](docs/release-automation-design.md) for the full design.

## Code conventions

### Comments

- Default to none. Add a comment only when the **WHY** is non-obvious.
- No multi-paragraph docblocks above functions. One short line maximum.
- Don't reference the current PR / issue / fix / caller (`# added for #136`, `# used by handleX`) — those rot.
- Don't restate WHAT the code does — well-named identifiers do that.

### Hubitat sandbox limits (`hubitat-mcp-server.groovy`, `hubitat-mcp-rule.groovy`)

The Hubitat hub runs Groovy under a restrictive sandbox. Do NOT use:

- `eval()`, `Eval.*`, `GroovyShell`
- Filesystem reads/writes outside `manage_files` (which goes through the hub's File Manager API, not direct disk)
- `getClass()` (sandbox blocks reflection)
- `log.isDebugEnabled()` (not exposed)
- `Date.format(String, Locale)` — only the no-Locale overload is allowed

Use `atomicState` for thread-safe persistence and `state` for UI/counter values. Always compare device IDs as strings (`.toString()`).

### Tests

- Spock 2.3 + `eighty20results/hubitat_ci` v0.28.6 via JitPack. JDK 11+.
- `./gradlew test` runs the suite. Specs live under `src/test/groovy/`.
- See [docs/testing.md](docs/testing.md) for the harness contract — `HarnessSpec` does the sandbox compile-once-per-class, and the three method-class dispatch mechanisms are easy to get wrong.
- New MCP tools require unit tests (the PR template's first checklist item).

### Lint

```
python tests/sandbox_lint.py
```

Catches sandbox violations the JVM compiler accepts but the Hubitat runtime rejects. Run before pushing.

## Git hygiene

- **Always use the noreply email**: `kingpanther13@users.noreply.github.com`. The private email triggers a GitHub push protection block.
- Set BOTH author AND committer email when amending: `GIT_COMMITTER_EMAIL=kingpanther13@users.noreply.github.com git commit --amend --author="kingpanther13 <kingpanther13@users.noreply.github.com>"`
- **Do not skip hooks** (`--no-verify`, `--no-gpg-sign`, etc.) without explicit authorization from the maintainer. If a hook fails, fix the underlying issue.
- **Open PRs as drafts** by default (`gh pr create --draft`). The maintainer owns the ready-for-review flip.
- **Don't edit other contributors' PR descriptions.** Mention requested changes in your review instead.

## Pre-merge checklist for AI contributors

Before claiming a PR is ready, verify all of:

- [ ] `## Type of change` and `## Release Notes` sections present in PR body (run the grep above)
- [ ] Title prefix matches the ticked checkbox
- [ ] Release Notes bullets describe user-visible impact, not internal mechanics
- [ ] `./gradlew test` passes locally (or CI is green)
- [ ] `python tests/sandbox_lint.py` passes
- [ ] No version strings, `releaseNotes`, `dateReleased`, README Version History, or `CHANGELOG.md` edits in the diff
- [ ] All commits authored with the noreply email
- [ ] PR is in draft state unless the maintainer asked otherwise

## Reference docs

- [`.github/pull_request_template.md`](.github/pull_request_template.md) — the template (source of truth for body structure)
- [`.gemini/styleguide.md`](.gemini/styleguide.md) — Gemini Code Assist's review checks (matches this file's conventions)
- [`docs/release-automation-design.md`](docs/release-automation-design.md) — full design of the release pipeline and why bookkeeping is bot-only
- [`docs/testing.md`](docs/testing.md) — Spock + hubitat_ci harness, dispatch interception cheat sheet
- [`README.md`](README.md) — project overview, installed-tool catalog
- [`TOOL_GUIDE.md`](TOOL_GUIDE.md) — per-tool reference for the MCP server
