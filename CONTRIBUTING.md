# Contributing

Thanks for taking the time to contribute. This file is the human-contributor guide. If you're an AI coding agent (Claude Code, Codex, Cursor, etc.), see [AGENTS.md](AGENTS.md) instead — the rules there are stricter and apply on top of what's below.

## Quick checklist

Before you push a PR:

1. `./gradlew test` passes locally (or CI confirms).
2. `python tests/sandbox_lint.py` passes — the Hubitat Groovy sandbox blocks several JVM features and the lint catches them before the hub does.
3. New MCP tools ship with unit tests (golden path + error path) under `src/test/groovy/server/` (or `src/test/groovy/rules/` for rule-engine work). See [docs/testing.md](docs/testing.md).
4. PR title starts with one of the prefixes below and matches the ticked **Type of change** box in the PR template.
5. Live-hub BAT scenarios in [`tests/BAT-v2.md`](tests/BAT-v2.md) updated if tool behaviour changed.

## Local dev setup

- **JDK**: 11+ locally (CI runs JDK 17 Temurin). The Gradle toolchain handles version pinning.
- **Build**: Gradle 8.10 via the wrapper — `./gradlew test`. No global Gradle install required.
- **Test framework**: Spock 2.3 on Groovy 3.0, riding [eighty20results/hubitat_ci](https://github.com/eighty20results/hubitat_ci) for the sandbox harness. The pinned tag lives in `build.gradle` and is bumped by `.github/workflows/hubitat-ci-version-check.yml`.
- **Sandbox lint**: `python tests/sandbox_lint.py` (Python 3.12+). Runs structural checks on the Groovy sandbox patterns. CI runs the same lint on every PR.

Useful commands:

```bash
./gradlew test                                # full Spock suite (~1 min)
./gradlew test --tests "*ToolGetHubLogsSpec"  # a single spec
./gradlew test --info                         # verbose
python tests/sandbox_lint.py                  # Groovy sandbox lint
```

HTML test report at `build/reports/tests/test/index.html`. CI uploads it as the `test-report-*` artifact on failure.

The full Spock harness — fixtures, mocks, dispatch-cheat-sheet, recipe for adding a new tool spec — lives in [docs/testing.md](docs/testing.md). Read that before adding your first spec.

## PR workflow

Use [`.github/pull_request_template.md`](.github/pull_request_template.md) — keep every section. The template walks through:

- **Summary** — 1–3 bullets, what and why.
- **Type of change** — tick exactly one box. The ticked box must match your title prefix.
- **Changes** — key changes; reference issues with `Closes #N` / `Fixes #N` / `Part of #N`.
- **Release Notes** — bulleted, end-user-facing (see next section).
- **Testing** — how you verified the change.
- **Checklist** — unit tests, sandbox lint, `./gradlew test`, BAT updates, doc updates.

Open the PR as a draft (`gh pr create --draft`). The maintainer flips it to ready-for-review once it looks good.

### Title prefixes

The PR title must start with one of these, lowercase and colon-terminated:

| Prefix | When to use |
|---|---|
| `feat:` | New feature or capability |
| `fix:` | Bug fix |
| `chore:` | Maintenance, dependency bump, housekeeping |
| `refactor:` | Code restructure with no behaviour change |
| `docs:` | Documentation only |
| `test:` | Tests only |
| `ci:` | CI / workflow change |

`build:` is reserved for Dependabot — contributors should not use it.

Pick the prefix that matches the diff, and tick the matching `Type of change` box. Gemini Code Assist will flag a mismatch.

### Release Notes section

The `## Release Notes` bullets in your PR body are what HPM users see in the update prompt when they upgrade the rule app. Write for end users, not developers:

- Short, scannable lines.
- No internal jargon, file paths, or PR numbers.
- Sub-bullets are supported (nest with 2-space indent) to group related details under one top-level point.

The post-merge release bot reads this section and writes it into `packageManifest.json`'s `releaseNotes` field via `.github/scripts/release_bump.py`. If you skip the section the release entry falls back to the PR title. If the section is present but unbulleted, the bot warns.

You can verify your PR body has the required headings with:

```bash
gh pr view <N> --json body --jq '.body' | grep -iE "^#+\s*(Type of change|Release Notes)\s*:?\s*$"
```

The full design is in [docs/release-automation-design.md](docs/release-automation-design.md).

### Tests must ship with new tools

Every new MCP tool needs both a direct-call (unit) test and a dispatch-envelope (integration) test — see the "Test pyramid" section in [docs/testing.md](docs/testing.md). PRs that add tools without tests will be asked to add them before merge.

### Live-hub BAT tests

[`tests/BAT-v2.md`](tests/BAT-v2.md) is the behaviour-acceptance suite — scripted JSON scenarios (`setup_prompt` → `test_prompt` → `teardown_prompt`) you run against the MCP server through an AI client connected to a real hub. Every artifact is prefixed `BAT` and every rule is marked `testRule: true` so cleanup is unambiguous.

If your PR changes tool behaviour, add or update the matching BAT scenarios in the same PR.

### PR review flow

- Open as draft. The maintainer flips to ready-for-review.
- [Gemini Code Assist](https://gemini.google.com/) auto-reviews using [`.gemini/styleguide.md`](.gemini/styleguide.md). Its first comment will always lead with "I am an AI and make mistakes…" — pushback is welcome and the maintainer treats Gemini's checks as suggestions, not blockers.
- The bot's three checks are: Release Notes presence, title-prefix / Type-of-change agreement, and prefix-matches-the-diff. All three are soft requests, not hard blocks.
- The maintainer makes the final call on merge.

## Things to avoid

- **Don't edit version strings, `packageManifest.json` `releaseNotes` / `dateReleased`, `README.md`'s `## Version History`, or `CHANGELOG.md`.** All four are bot-managed; the `pr_guard.py` CI check flags any of them. Communicate user-facing changes through your PR's `## Release Notes` section instead — see [docs/release-automation-design.md](docs/release-automation-design.md).
- **Don't add new feature work to `hubitat-mcp-rule.groovy` (the child app).** The custom rule engine surfaced through `custom_*` tools is legacy — bug fixes are accepted, new features should go on the native Rule Machine surface (`create_native_app` / `update_native_app` / `delete_native_app` and the `manage_native_rules_and_apps` group).
- **Don't force-push, delete branches, bypass hooks (`--no-verify` / `--no-gpg-sign`), or edit other contributors' PR descriptions** without asking the maintainer first.

## License

By contributing, you agree your contributions are licensed under the [MIT License](LICENSE).
