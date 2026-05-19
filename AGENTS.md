# AGENTS.md

Conventions for AI coding agents working on this repo (OpenAI Codex, Cursor, Aider, Windsurf, Zed, etc.). Claude Code reads `CLAUDE.md`, which is kept byte-identical to this file — the PR Guard CI workflow flags drift. **AGENTS.md is the source of truth.** Edit it, then `cp AGENTS.md CLAUDE.md` before committing. (GitHub Copilot uses `.github/copilot-instructions.md`; Gemini Code Assist uses `.gemini/styleguide.md`.)

This file is for AI agents. Human contributors follow `.github/pull_request_template.md` and `.gemini/styleguide.md`.

## Commands

```bash
./gradlew test                     # full Spock suite (~1 min)
./gradlew test --tests "<spec>"    # single spec
python tests/sandbox_lint.py       # Groovy sandbox lint
```

Run both before pushing. CI runs the same.

## Code style

**Hubitat Groovy sandbox** (`hubitat-mcp-server.groovy`, `hubitat-mcp-rule.groovy`) blocks several JVM features the runtime would otherwise expose. Highlights — see `tests/sandbox_lint.py` for the full set:

- `Eval.*`, `GroovyShell`, `Class.forName`, `Runtime.exec`, `new Thread`, `new File` / `java.io.File` — not allowed
- `getClass()` — reflection blocked
- `log.isDebugEnabled()` — not exposed
- `Date.format(String, Locale)` — only the no-Locale overload works
- Filesystem only via the hub File Manager API (`/hub/fileManager`); MCP-tool surface is `list_files` / `read_file` / `write_file` / `delete_file`

Use `atomicState` for thread-safe persistence, `state` for UI/counters. Compare device IDs as strings (`.toString()`).

**Comments**: only when the WHY is non-obvious. No multi-paragraph docblocks. Don't reference the current PR/issue/caller.

## The custom MCP rule engine is legacy

`hubitat-mcp-rule.groovy` (the MCP child app, surfaced through the `custom_*` tools) is **legacy**. It still ships and still gets bug fixes, but it is **closed to new feature work** — Hubitat's native Rule Machine is the supported path now and exposes equivalent functionality through `create_native_app` / `update_native_app` / `delete_native_app` and the rest of the `manage_native_rules_and_apps` group. New rule-related capabilities should land on the parent app's native-RM tools, not on the child app. If a feature request lands on the child app, propose it for the native side instead.

## PR workflow

Use `.github/pull_request_template.md` — keep every section.

- **Title prefix** matches the ticked `## Type of change` box: `feat:` / `fix:` / `chore:` / `refactor:` / `docs:` / `test:` / `ci:` (`build:` reserved for Dependabot).
- **`## Release Notes`** drives `packageManifest.json` `releaseNotes` (what HPM users see in the update prompt) via `.github/scripts/release_bump.py`. Strongly recommended; write user-facing bullets. If you skip the section the PR title is the fallback. The bot warns on a present-but-unbulleted section.
- **Open as draft** (`gh pr create --draft`); the maintainer flips to ready-for-review.
- **Verify the PR body has both required headings** before claiming the PR is ready (`<N>` = PR number):

  ```bash
  gh pr view <N> --json body --jq '.body' | grep -iE "^#+\s*(Type of change|Release Notes)\s*:?\s*$"
  ```

  Lenient match (case-insensitive, any heading level, optional trailing colon) — same shape `release_bump.py` parses with. Run on rebase/edit too; pre-#146 PRs may not satisfy the template.

## Boy Scout rule

**"Always leave the code better than you found it."**

When you touch a file, you fix the adjacent small stuff you notice — broken comments, stale references, dead pointers, weakly-asserting tests, mis-named variables, redundant code. Not because the PR title told you to, but because you were already in the file and you saw it. The accumulating effect over many PRs is what keeps the codebase from rotting. Skipping the small fix and walking past it is the antipattern, even when it's "not what the PR is about."

This applies to anything within the file or function you're already editing, and to anything one or two file-jumps away that the fix you're making clearly implicates (e.g. a stale comment that references the function you just rewrote, a test assertion that pins the old behaviour, a pointer that points at something you renamed).

## Scope discipline

Scope discipline is the partner rule to Boy Scout — they're different things. Boy Scout governs the small adjacent fixes you make without asking. Scope discipline governs the larger ones you don't.

- **Small or medium fix surfaced during the work** — Boy Scout it. Roll it into the current PR. Examples: a `.md` pointer that's unreachable from the runtime, a test that asserts on file presence but not runtime availability, a header comment that's stale after a related rewrite. The PR's commit messages document the extra fix; the PR body's Changes section names it.
- **Large fix** (touches a broad surface, a separate subsystem, or would substantially expand the PR's review surface) — **ASK the user**. Present the option as: roll it in / open a follow-up PR / file a tracking issue / drop it. **The user decides scope, not the AI.**
- **Banned phrasing** — "deferred for follow-up", "out of scope for this fix", "tracking issue", "separate concern", or any variant that defers a real problem the AI just discovered, without the user explicitly saying so. The deflection IS the antipattern; it shifts the cost of remembering onto the maintainer and lets known problems pile up.

When in doubt, treat the problem as in-scope, name it, and surface the trade-off to the user. The user can always shrink the scope; only the user can grow it.

## Boundaries

**🚫 Never edit** — `pr_guard.py` (CI: `.github/workflows/pr-guard.yml`) flags any of these on every PR:
- Version strings in tracked locations (server header comment, `currentVersion()`, rule header, manifest `version`)
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
