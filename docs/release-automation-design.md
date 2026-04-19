# Release Automation — Design

**Status:** Proposed
**Date:** 2026-04-19
**Author:** kingpanther13 (with Claude)

## Problem

Every PR today requires the maintainer (or contributor) to hand-edit four synchronized version strings, prepend a paragraph to `packageManifest.json` `releaseNotes`, and (ideally, though often missed) prepend a bullet to `README.md` Version History. The work is mechanical, error-prone, and has already drifted — PR 63 updated the manifest but not the README.

The repo has no `CHANGELOG.md`, no git tags, and no GitHub Releases. Release notes live in two places that drift independently (`packageManifest.json` `releaseNotes` field and `README.md` Version History section), plus a nonexistent third place (`CHANGELOG.md`).

## Goals

1. Every merged PR automatically bumps the version number in all four locations.
2. `CHANGELOG.md` is the source of truth for release notes; entries auto-inject into `packageManifest.json` `releaseNotes` and `README.md` Version History at release time.
3. Contributors cannot modify version strings, `releaseNotes`, `dateReleased`, or the README Version History section. Those files/sections are bot-only.
4. Every PR is required to document its change under a `## [Unreleased]` section in `CHANGELOG.md`. PRs that don't are blocked.
5. Every PR is required to have a `release:patch|minor|major` label before merge. Maintainer applies the label after approving.
6. Git tags (`vX.Y.Z`) are created automatically at each release.
7. Works with the existing constraint: HPM polls `https://raw.githubusercontent.com/kingpanther13/Hubitat-local-MCP-server/main/packageManifest.json`, and this URL cannot be changed without stranding existing installations.

## Non-goals

- Multi-branch development model (develop → main). Single-branch only.
- Sentinel values (e.g., `"unreleased"`, `"0.0.0-dev"`) in version strings on main. Main always carries a valid, shipped version.
- Conventional Commits discipline. Release type is signaled by PR label, not commit message format.
- Batched releases. Each merged PR produces its own version bump by default.
- GitHub merge queue. Not needed for this design.
- Migrating existing HPM installations. URL stays at `main/packageManifest.json`.

## Accepted limitations

- **HPM staleness window:** Between the time a feature PR merges and the time the post-merge bot pushes its `chore(release)` commit, `main` holds the new feature code under the *previous* version number. For ~10-60 seconds, a fresh HPM installer could fetch code labeled as the older version. The maintainer has explicitly accepted this as a minor edge case.
- **Per-PR version churn:** Five small PRs in a week produces five version bumps, which generates five HPM update prompts for end users. This is considered acceptable churn in exchange for fine-grained traceability.
- **CDN cross-file staleness:** `raw.githubusercontent.com` caches each file with independent TTLs (~5 minutes). It is theoretically possible for a user to fetch a new `packageManifest.json` while a cached older `.groovy` file is still served. This is considered rare and not worth code-side mitigation.

## Versioning convention

Non-standard semver, maintainer judgment required:

- **Major (first digit):** Reserved. Stays at `0` until explicit maintainer decision.
- **Minor (second digit):** "Big feature changes" — multiple new tools, architectural shifts, breaking changes. Historical examples: v0.6.0 (virtual device subsystem), v0.7.0 (room management suite), v0.8.0 (category gateway proxy), v0.9.0 (perf-stats + hub-jobs).
- **Patch (third digit):** Single-tool enhancements, single-tool additions, bug fixes. Historical precedent: v0.9.1 added the entire new `search_tools` tool and was a patch bump.

The maintainer applies a `release:patch|minor|major` label to each PR based on this judgment. The bot does not attempt to infer type.

## Architecture

### Source of truth hierarchy

All three artifacts below are **bot-maintained**; contributors never edit them.

1. **`CHANGELOG.md`** — Canonical. Entries generated at release time from merged PR metadata (titles, authors, PR URLs, optional `## Release notes` sections in PR bodies).
2. **`packageManifest.json` `releaseNotes`** — Derived. Bot prepends a flattened paragraph from the new CHANGELOG entry.
3. **`README.md` Version History** — Derived. Bot prepends a compact bullet with PR references.

Contributors contribute release-notes content via their PR title and (optionally) a `## Release notes` section in the PR body. The bot gathers these at release time and assembles the entry.

### Four version string locations (unchanged from today)

1. `hubitat-mcp-server.groovy` header comment: `* Version: X.Y.Z`
2. `hubitat-mcp-server.groovy` `currentVersion()` function: `return "X.Y.Z"`
3. `hubitat-mcp-rule.groovy` header comment: `* Version: X.Y.Z`
4. `packageManifest.json` `"version": "X.Y.Z"`

All four must always be equal and must always be a valid shipped version on `main`. Enforced by bookkeeping lint.

## PR-time CI checks (required status checks)

Two checks must pass before a PR can merge.

### Check 1: Sandbox lint (existing, extended)

`tests/sandbox_lint.py` runs on every PR touching `*.groovy` or `packageManifest.json`. Validates anti-patterns, verifies the four version strings match each other, AND asserts the manifest version matches strict semver regex `^\d+\.\d+\.\d+$`. Prevents typos like `0.10.0-rc1` that would silently break `isNewerVersion()` on user hubs.

### Check 2: Bookkeeping lint (new)

`.github/scripts/pr_guard.py` runs on every PR. Fails if the PR's diff modifies any of:

- Any of the four version strings
- `packageManifest.json` `releaseNotes` or `dateReleased`
- `README.md` `## Version History` section
- `CHANGELOG.md` (entire file — bot-only)

Contributors don't touch these files. Bot commits go directly to main (not through PRs), so this workflow only runs on contributor PRs.

### No changelog entry requirement

Contributors do not write CHANGELOG entries. The release bot generates entries from merged PR titles (and optional `## Release notes` sections in PR bodies) at release time. Eliminates merge-conflict risk between concurrent PRs and removes contributor bookkeeping responsibility.

If a contributor wants richer prose than their PR title, they add a `## Release notes` section to the PR body — the bot uses that in place of the title.

### No release label requirement

The release label is NOT required to merge. Unlabeled PRs merge normally — they just don't trigger a version bump. The next labeled PR merge produces a release whose notes include all PRs merged since the previous `vX.Y.Z` tag (labeled and unlabeled alike).

Flow:
- **Labeled PR merge** → bot gathers all PRs merged since last tag, writes CHANGELOG entry, propagates to manifest + README, bumps versions, tags.
- **Unlabeled PR merge** → code lands on main, no version bump; PR is picked up in the next labeled release's notes.

## Post-merge release workflow

### Trigger

`pull_request.closed` event where `merged == true` and base branch is `main`.

### Concurrency control

```yaml
concurrency:
  group: release
  cancel-in-progress: false
```

Serializes all release workflow runs. If PRs A and B merge seconds apart, B's workflow waits for A's to complete, ensuring B reads the post-A version state before computing its own bump.

### Steps

1. **Read the release label** from the merged PR. Accept exactly one of `release:patch`, `release:minor`, `release:major`.
   - **No label**: exit cleanly. PR's content is picked up in the next labeled release.
   - **Multiple release labels**: fail the workflow (ambiguous intent).
2. **Determine current version** from the latest git tag matching `v*.*.*`. Fall back to reading `packageManifest.json` `version` if no tags exist.
3. **Compute next version** per the label. Minor bumps reset patch to zero; major bumps reset minor and patch to zero.
4. **Gather PRs merged since the last tag** via `git log <last-tag>..HEAD --merges --format=%s` and parse `Merge pull request #NN` from each merge commit subject. Fallback to the triggering PR (from env) if no prior tag exists.
5. **For each PR**, fetch metadata + commit list via `gh pr view <N> --json number,title,url,author,commits`. Build a markdown bullet per PR with two parts:
   - Title line: `- <PR title> ([#NN](url), @author)`
   - Extended description (optional, renders as indented list-item continuation): the extended commit description (body) from the PR's **main commit** — i.e. the first commit on the branch. That's the commit where authors explain the WHY in a multi-paragraph body. Follow-up commits (fixups, doc tweaks) typically have no body and aren't release-notes material, so we don't walk past the first commit if its body is empty. Auto-generated trailers (`Co-authored-by:`, `🤖 Generated with...`) are stripped. The merge commit's body is NOT used — under merge-commit strategy it's usually just the PR title, which is useless noise.
6. **Rewrite all four version strings** to the new version.
7. **Prepend the new entry to `CHANGELOG.md`** as `## [X.Y.Z] - YYYY-MM-DD` followed by the bullets.
8. **Flatten the bullets (title + body) into a prose paragraph** (converting `[#NN](url)` → `#NN (url)` so HPM, which may render releaseNotes as plain text, still shows usable links) and prepend to `packageManifest.json` `releaseNotes`. Each PR appears as `Title (#NN (url), @author): body…` with PRs separated by ` / `.
9. **Generate a compact README bullet** summarizing the release — titles and PR refs only, no bodies — and prepend under `## Version History`. README stays scannable; the rich per-PR descriptions live in CHANGELOG.md and `releaseNotes`.
10. **Update `dateReleased`** in `packageManifest.json` to today (UTC).
11. **Commit** as `chore(release): X.Y.Z` directly to `main`, with `Co-authored-by: <PR author>` for the triggering PR.
12. **Create and push tag** `vX.Y.Z` pointing at the release commit.

### Error modes

- No release label: workflow exits cleanly without bumping. PR's content is picked up in the next labeled release's notes (bot gathers all PRs merged since the last tag). Normal flow for bundled releases.
- Multiple release labels: workflow fails with a clear error. Maintainer removes extras and re-triggers via the Actions UI, or merges a follow-up labeled PR.
- JSON invalidity after rewrite: workflow fails before committing. Nothing pushed. Maintainer investigates.
- Git push rejected (concurrent push from another source): workflow retries with rebase up to N times, then fails.
- `gh pr view` failure for a merged PR number: bot falls back to a bare `- PR #NN` bullet for that one PR and continues.

## Hardening items

### 1. Strict semver regex on manifest

Extend `tests/sandbox_lint.py` to assert `packageManifest.json` `version` matches `^\d+\.\d+\.\d+$`. Rejects typos like `0.10.0-rc1`, `v0.10.0`, stray whitespace. Ensures `isNewerVersion()` never sees a non-numeric component.

### 2. Harden `isNewerVersion()`

`hubitat-mcp-server.groovy:7288` currently does `tokenize('.').collect { it as int }` which throws `NumberFormatException` on non-numeric tokens. The exception is caught, but the function silently returns `false` — meaning an installed hub with a corrupted version string would silently stop seeing updates. Add explicit regex validation before the tokenize; if the version doesn't match `^\d+\.\d+\.\d+$`, log and return `false` with a clear error message.

### 3. JSON validity post-check

After the bot rewrites `packageManifest.json` `releaseNotes`, parse the entire file with `JsonSlurper` (Groovy) or `json.load` (Python) to confirm it's valid JSON. Fail the workflow if parse errors — a corrupted manifest silently breaks HPM for every user.

### 4. Concurrency group

`concurrency: group: release, cancel-in-progress: false`. See Post-merge release workflow > Concurrency control.

### 5. Multiple-label guard

Release workflow validates exactly one `release:*` label is set on the triggering PR. Zero labels = skip (normal unlabeled flow). Two or more = fail with explicit error message for the maintainer to resolve.

### 7. Bot commit visibility under squash merges

Squash merge absorbs the bot's `chore(release):` commit into a single main-branch commit. Cosmetic-only; tag logic keys off "main's tip after push", which works under squash, merge-commit, and rebase. No branch-protection restriction needed on merge strategy.

### 8. `Co-authored-by` preservation

Bot's commit includes `Co-authored-by: <PR author name> <PR author email>` trailer. Under squash merge, GitHub's squash commit template setting must be configured to preserve trailers (repo Settings → General → Pull Requests → "Allow trailers").

### 9. `dateReleased` timezone

Bot uses UTC. Documented here. Users in other timezones may see "tomorrow's date" when glancing at dateReleased, but this is cosmetic.

## Migration plan

### Bootstrapping `CHANGELOG.md`

`CHANGELOG.md` is seeded with the most recent release (v0.9.2 — bundling #63 + #64 that merged before automation) and v0.9.1 (prior shipped release) as the historical anchor. Pre-0.9.1 history stays in `packageManifest.json` `releaseNotes` and `README.md` Version History; it may be migrated into `CHANGELOG.md` opportunistically but is not required for the automation to work.

### Initial tag

Before enabling the release workflow, create a git tag `v0.9.2` pointing at the current tip of `main` (post-PR-63/64 merge). Seeds the "latest tag" lookup for the first automated release.

```
git tag v0.9.2 <commit-sha-of-main-tip>
git push origin v0.9.2
```

### Rollout order

1. PR 63 + PR 64 are merged (already done — main is at 0.9.2).
2. Tag `v0.9.2` on main's current tip.
3. Merge the automation PR (this design doc + workflows + scripts + seed CHANGELOG.md) — do NOT label it with `release:*` so the workflow doesn't trigger a bump. This PR is infrastructure, not a user-facing release.
4. Configure branch protection on `main` to require `Sandbox Lint` + `PR Guard` status checks.
5. Test end-to-end with a small docs-only PR: contributor's PR has only code / doc changes, you review + approve + label `release:patch` + merge. Bot should bump to v0.9.3 and write a CHANGELOG entry from the PR title.

## Open questions

None outstanding. All concerns raised in design review have been addressed in Hardening items or Accepted limitations.

## References

- Prior design discussions: captured in conversation log
- Idiot-checker review 1 and 2: raised landmines around HPM CDN caching, `isNewerVersion()` silent failure, concurrent merge races, JSON escape corruption, label ordering. All addressed.
- Repo state as of 2026-04-19: v0.9.1 shipped, PR 63 open at v0.10.0 (pending manual revert per option (b)).
