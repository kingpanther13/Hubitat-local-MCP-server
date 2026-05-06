# AGENTS.md

Conventions for AI coding agents working on this repo (OpenAI Codex, Cursor, GitHub Copilot, Aider, Windsurf, Zed, etc.). Claude Code reads `CLAUDE.md`, which is kept byte-identical to this file — `.github/scripts/pr_guard.py` fails CI on drift. Edit AGENTS.md, then `cp AGENTS.md CLAUDE.md` before committing.

This file is for AI agents. Human contributors follow `.github/pull_request_template.md` and `.gemini/styleguide.md`.

## Commands

```bash
./gradlew test                     # full Spock suite (~10 min)
./gradlew test --tests "<spec>"    # single spec
python tests/sandbox_lint.py       # Groovy sandbox lint
```

Run both before pushing. CI runs the same.

## Code style

**Hubitat Groovy sandbox** (`hubitat-mcp-server.groovy`, `hubitat-mcp-rule.groovy`) blocks several JVM features the runtime would otherwise expose. Lint enforces them; runtime crashes if you slip past:

- `Eval.*`, `GroovyShell` — not allowed
- `getClass()` — reflection blocked
- `log.isDebugEnabled()` — not exposed
- `Date.format(String, Locale)` — only the no-Locale overload works
- Filesystem reads/writes — only via `manage_files` (hub File Manager API), never direct disk

Use `atomicState` for thread-safe persistence, `state` for UI/counters. Compare device IDs as strings (`.toString()`).

**Comments**: only when the WHY is non-obvious. No multi-paragraph docblocks. Don't reference the current PR/issue/caller.

## PR workflow

Use `.github/pull_request_template.md` — keep every section.

- **Title prefix** matches the ticked `## Type of change` box: `feat:` / `fix:` / `chore:` / `refactor:` / `docs:` / `test:` / `ci:` (`build:` reserved for Dependabot).
- **`## Release Notes`** is mandatory and must contain bullets. `.github/scripts/release_bump.py` parses this section into `packageManifest.json` `releaseNotes` — what HPM users see in the update prompt. Write for end users, not developers.
- **Open as draft** (`gh pr create --draft`); the maintainer flips to ready-for-review.
- **Verify before claiming a PR is ready** (`<N>` = PR number):

  ```bash
  gh pr view <N> --json body --jq '.body' | grep -iE "^#+ (Type of change|Release Notes):?\s*"
  ```

  Both lines must appear and the Release Notes section must contain at least one bullet. Run this when rebasing/editing too — pre-#146 PRs may not satisfy the template.

**Git identity**: commit with your GitHub noreply email (`<your-username>@users.noreply.github.com`), never your private email — GitHub push protection will block the push. Set both `--author` and `GIT_COMMITTER_EMAIL` when amending.

## Boundaries

**🚫 Never edit** — `pr_guard.py` (CI: `.github/workflows/pr-guard.yml`) fails the check on any of these:
- Version strings anywhere (`.groovy`, `packageManifest.json`, `README.md`, etc.)
- `packageManifest.json` `releaseNotes` or `dateReleased`
- `README.md` `## Version History` section
- `CHANGELOG.md`

All bookkeeping is bot-only. Communicate user-facing changes through your PR's `## Release Notes` section; the post-merge bot handles every file. See [docs/release-automation-design.md](docs/release-automation-design.md).

**⚠️ Ask first** — destructive ops (force-push, branch deletion, hook bypass with `--no-verify` / `--no-gpg-sign`), edits to other contributors' PR descriptions, anything that touches Z-Wave or Zigbee radios on a live test hub.

**✅ Default-OK** — local edits, running tests, opening draft PRs, updating your own PR's description, refactors and bug fixes covered by tests.

## Reference

- [`.github/pull_request_template.md`](.github/pull_request_template.md) — PR body structure (source of truth)
- [`.gemini/styleguide.md`](.gemini/styleguide.md) — Gemini Code Assist's review checks
- [`docs/release-automation-design.md`](docs/release-automation-design.md) — full release-pipeline design and the bookkeeping rule
- [`docs/testing.md`](docs/testing.md) — Spock + hubitat_ci harness, dispatch interception cheat sheet
