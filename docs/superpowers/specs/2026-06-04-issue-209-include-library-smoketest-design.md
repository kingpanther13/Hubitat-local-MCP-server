# Issue 209 — `#include` library split: HPM/`#include` smoke-test canary

- **Issue:** [#209](https://github.com/kingpanther13/Hubitat-local-MCP-server/issues/209) — investigate splitting `hubitat-mcp-server.groovy` into library files
- **Date:** 2026-06-04
- **Branch:** `feat/include-library-smoketest-209`, based on `main` (PR #236 has since merged; rebased onto `main` 2.0.0)
- **Scope:** the canary from step 1 of the issue-209 game plan, restructured into a safety-gated two-PR sequence.

## Goal

De-risk **Path A** (Hubitat `#include` libraries distributed via HPM `bundles[]`) before any real
code is extracted from the 23K-line monolith. Three things must be proven, cheaply and reversibly:

1. **CI-provable** — the toolchain (eighty20results Spock harness, Groovy 2.4 parse lane, Groovy 2.5
   lane, sandbox lint) stays green with a real `#include` in the production app file.
2. **HPM one-click download** — merging a patch and clicking *Check for Updates → Update* once in HPM
   pulls the library onto the hub, with **no separate user flow**. This is the maintainer's go/no-go
   test; if it requires a separate per-update step, Path A is abandoned.
3. **Dev-loop push** — the maintainer's on-the-fly `hub_create_library` / `hub_update_app` MCP push
   still works once the app references a library.

## Key constraint discovered during design

A `#include`d library is **compile-coupled** to the consuming app: the hub appends the library
source into the app's compiled class at parse time, so the app **will not compile** unless the
library is already on the hub. This is unlike the existing **"MCP Rule" child app**, which is
*decoupled* — a separate `apps[]` entry, a separate compiled app instantiated via `addChildApp`,
with no compile dependency on the parent.

Consequence: the moment `#include mcp.McpSmokeTestLib` ships in the parent on `main`, **every** user's
next HPM update pulls a parent that fails to compile unless the bundle also landed — i.e. a bundle
failure takes down the MCP server for all users. The game plan's "non-required, zero-risk" framing
does **not** hold once the parent `#include`s the library, because `#include` makes the library
mandatory for compilation regardless of the manifest `required` flag.

**Resolution — gate the risky half behind the safe half:**

A `required: true` bundle installs the library on every hub via the one Update click **without** any
app `#include`ing it. HPM installs a required bundle regardless of whether anything references it;
Hubitat leaves an unreferenced library inert (it is not even compiled until something includes it).
So we can prove the *download* (test #2) with zero breakage risk, then add the *`#include`* (tests #1
and the compile/auto-recompile) only after download is confirmed on a real hub.

## Architecture — two stacked PRs

### PR-1 — prove the download (this branch)

Required bundle; library installs on every hub; **nothing `#include`s it**. Zero blast radius.

| Component | Path | Notes |
|---|---|---|
| Library source | `libraries/mcp-smoke-test-lib.groovy` | top-level `libraries/`, deliberately **not** under `src/main/groovy/` (Gradle must not try to compile a Hubitat DSL file) |
| Bundle builder | `tools/build-bundle.py` | deterministic: zips the lib + Hubitat bundle manifest files; reusable for real extractions |
| Built bundle | `bundles/mcp-smoke-test.zip` | committed binary; served at a stable raw-`main` URL |
| Manifest entry | `packageManifest.json` → new `bundles[]` array | `required: true`; `location` = `raw.githubusercontent.com/.../main/bundles/mcp-smoke-test.zip` |

CI impact: none requiring a resolver (no `#include` in the production file). For hygiene the library
file is added to `tests/sandbox_lint.py`'s `GROOVY_FILES` and the Groovy 2.4 parse-lane file list so
the shipped library is itself linted/parsed (`library(...)` is valid Groovy syntax, so the parse lane
passes).

**Validation (maintainer, on real hub, after merge):**
1. Click *Check for Updates → Update* once in HPM → confirm `McpSmokeTestLib` appears in the hub's
   library list. (Same single-click flow the child app already uses.)
2. Confirm the dev-loop push: `hub_create_library(sourceFile=…, confirm=true)` installs it directly.
3. (Update-flow confirmation) bump the marker + bundle version, rebuild the zip, merge a one-line
   patch → confirm the next single Update click re-pulls it.

### PR-2 — prove the `#include` (gated on PR-1's hub result)

Lands only after PR-1's download is confirmed reliable.

| Component | Path | Notes |
|---|---|---|
| Shared include-resolver | new helper (exact home decided in plan) | scans `#include namespace.Name`, loads `libraries/<name>.groovy`, strips its `library(...)` declaration, appends the body — mirrors how the hub inlines at parse |
| Harness wiring | `src/test/groovy/support/HarnessSpec.groovy` | resolve includes before `HubitatAppSandbox.run()`; covers unit-tests + the Groovy 2.5 scaffold |
| Parse-lane wiring | `ci/groovy24-parse/parse_check.groovy` | resolve includes before `cu.compile(CONVERSION)` |
| The `#include` | `hubitat-mcp-server.groovy` | one line, near the top |
| Observable marker | a read-only diagnostic tool's returned map | `mcpSmokeTestMarker()` folded into e.g. `toolGetHubPerformance` output, visible via a read the maintainer already calls |
| Tests | `ResolverSpec` + a marker-assertion spec | the marker spec only passes if the resolver inlined the lib — that is the CI proof of test #1 |

Revert at any time: delete the one `#include` line + the one marker assignment.

## Component designs

### The throwaway library

`libraries/mcp-smoke-test-lib.groovy`, matching the header convention of the existing `.groovy` files,
then immediately the declaration — **BP20: zero file-scope comments between the header and `library(`:**

```groovy
library(name: "McpSmokeTestLib", namespace: "mcp", author: "kingpanther13", description: "Throwaway #include canary for issue 209")

String mcpSmokeTestMarker() { "smoke-ok-v1" }
```

### The HPM bundle

A `.zip` containing the library `.groovy` (renamed to `<namespace>.<name>.groovy`) plus `install.txt`
and `update.txt` (identical), each:

```
<namespace>
<bundle_name>
library <namespace>.<name>.groovy
```

**Format verified** against level99's production `tools/build-bundle.py` (the pipeline they migrated to
after `libraries[]` silently dropped on update). Our `tools/build-bundle.py` mirrors it and is
deterministic (fixed DOS epoch, stored entries) so the committed `.zip` rebuilds byte-identical. Use
`bundles[]`, never `libraries[]`.

Manifest entry shape (added alongside the existing `apps[]`/`drivers[]`):

```json
"bundles": [
  {
    "id": "<uuid>",
    "name": "MCP Smoke Test Lib",
    "namespace": "mcp",
    "location": "https://raw.githubusercontent.com/kingpanther13/Hubitat-local-MCP-server/main/bundles/mcp-smoke-test.zip",
    "required": true
  }
]
```

Bot-safety: `pr_guard.py` forbids only `version` / `dateReleased` / `releaseNotes` in the manifest;
a new `bundles[]` array is allowed, and `release_bump.py` preserves fields it does not touch, so the
post-merge bot does not clobber it.

### Dev-loop push sequence (with an `#include` present, PR-2 onward)

Library-first, because the hub does not compile-check a library at install but the app `#include`ing
it will fail to compile if the library is absent:
1. `hub_create_library(source|sourceFile|importUrl, confirm=true)` → confirm `libraryId`.
2. `hub_update_app` / `hub_create_app` with the `#include`d source.
Order matters only when the library is new or its source changed; app-only edits remain a single push.

## Branch / PR strategy

- This branch is stacked on `feature/native-app-tool-split-137`; the PR base is that branch (clean
  diff). It auto-retargets to `main` when #236 merges; rebase if #236 moves.
- Worktree is isolated at `…/worktrees/hubitat-local-mcp-server/include-library-smoketest-209`; the
  236 checkout is never touched.
- PR-2 stacks on PR-1 (or on `main` once both 236 and PR-1 have merged).

## Conventions honored

- BP20 (no file-scope comments before `library(`).
- AGENTS.md is source of truth; `cp AGENTS.md CLAUDE.md` to keep them byte-identical (PR Guard checks).
- No edits to bot-only bookkeeping (`version`, `currentVersion()`, header version strings,
  `releaseNotes`, `dateReleased`, `CHANGELOG.md`, README `## Version History`). User-facing changes go
  in the PR's `## Release Notes` section; the post-merge bot writes the rest.
- PR body uses the template with `## Type of change` + `## Release Notes`; opened as draft.

## Open items to verify at implementation

1. ~~Exact Hubitat bundle `.zip` format~~ — DONE (PR-1): mirrored from level99's production
   `build-bundle.py`; `install.txt`/`update.txt` format confirmed and built reproducibly.
2. Exact diagnostic-tool hook for the marker (`toolGetHubPerformance` vs `hub_get_hub_info`) — PR-2,
   pinned during planning by reading the current code on this branch.
3. Whether the Groovy 2.5 lane (allow-failure) needs the same resolver wiring as the primary harness —
   PR-2.

## Revert plan

PR-1: remove the `bundles[]` entry + the committed `.zip` + the library file (and a follow-up release
drops the library from hubs). PR-2: delete the one `#include` line + the one marker assignment. At no
point is the parent app left depending on an unproven mechanism.
