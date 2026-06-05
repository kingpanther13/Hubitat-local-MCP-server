# Issue 209 — `#include` smoke-test + dev-update tool game plan

Durable handoff doc (written 2026-06-05). Captures state, decisions, and the remaining plan so a
fresh context can continue. Part of the issue #209 monolith-split effort.

## Where we are

- **PR #239 (PR-1, smoke-test bundle) — MERGED to `main` (2.0.1).** Proved HPM `bundles[]` delivery:
  one HPM *Update* click installs `McpSmokeTestLib` on a real hub. Verified on the maintainer's hub
  (library **id 7**, byte-identical, marker `smoke-ok-v1`). The release bot preserved the `bundles[]`
  array through the version bump.
- **PR #240 (firmware research) — draft, docs only, parked.** `docs/firmware-update-research.md`.
- **PR #241 (`hub_list_libraries`) — draft, CI fully green (incl. live e2e), Gemini review resolved,
  merge-ready, awaiting maintainer merge.** Branch `feat/hub-list-libraries`. Adds a read tool that
  enumerates Libraries Code via `/hub2/userLibraries` (projects to id/name/namespace/version, omits
  source; cursor pagination). Verified live (returned `McpSmokeTestLib`).
- **This branch `feat/include-smoke-test-209`** is stacked on `feat/hub-list-libraries` (so it has
  `hub_list_libraries`). PR-2 lands here.

## The one unproven thing

- **Library DELIVERY via HPM bundle = PROVEN** (PR-1).
- **Library LOADING via `#include` (app `#include`s it → compiles/loads cleanly) = NOT PROVEN.**
  This is the load-bearing goal of PR-2.

## Decisions (locked)

1. **Dev-update tool** (working name `hub_update_package`): a **dev-mode-gated WRITE tool** giving a
   low-context LLM a one-call way to push the full package during dev — mirrors today's
   `hub_update_app(importUrl)` but covers libraries too.
   - Arg: `ref` (branch/tag/SHA). Logic: fetch app source at ref → parse `#include mcp.<Name>`
     directives → for each, install/update the library **idempotently** (`hub_list_libraries` → find
     by namespace+name → `hub_update_library(id, importUrl)` if present, else
     `hub_create_library(importUrl)`) → `hub_update_app(importUrl)` at ref. **Libraries first** so the
     include resolves. Source/importUrl push — **NOT** the bundle zip.
   - **Only present when `enableDeveloperMode` is ON** (maintainer requirement). Lives in the
     Developer-Mode surface (the `hub_manage_mcp` gateway is the dev-mode self-admin gateway; gate the
     same way — `getEffectiveDisabledTools()` hides it when dev mode off).
   - `#include mcp.<Name>` → `libraries/<file>.groovy` source-path mapping is the one detail to settle
     in the build (current lib: `libraries/mcp-smoke-test-lib.groovy` for `#include mcp.McpSmokeTestLib`
     — kebab transform, OR rename lib files to match the include Name 1:1; prefer a small explicit map
     or a `*Lib`→file convention, document it).
2. **e2e validation of "won't break via HPM" = COMPILE-EQUIVALENCE (option i).** e2e deploys
   app(+`#include`) + library and asserts the app **compiles with the include resolving** (marker
   observable). HPM yields the same end state (library installed + app), so a green compile here proves
   an HPM install can't break on the `#include`. **Do NOT drive real HPM in e2e** (button-sim + `/main`
   rewrite is too heavy; maintainer doesn't care about testing HPM itself).
3. **No big `hub_install_package` mini-HPM tool. No `hub_install_bundle` needed** for the smoke test
   (the zip path is already proven on the maintainer's hub via PR-1). The "partial clone of HPM" is
   just the dev tool's orchestration of existing granular tools + the e2e deploy.
4. Maintainer will test the **first HPM update on their personal hub**; after confirming, they use the
   dev tool for dev work.

## Restructured tasks (PR-2, this branch)

- **T3 — Build `hub_update_package` (dev-mode WRITE tool).** ref → update app + `#include`'d libraries,
  idempotent via `hub_list_libraries`. Dev-mode gated. Spock unit + dispatch tests; classify as a
  **write** tool (add to `expectedWrites`, NOT `getReadOnlyToolNames`); `outputSchema`; bump tool count
  (this branch is at **89** → 90) in `McpToolAnnotationsSpec` (`names.size()`) + docs
  (README/SKILL/TOOL_GUIDE/tool-reference — `sandbox_lint` enforces the doc count).
- **T4 — Add the single `#include mcp.McpSmokeTestLib` to `hubitat-mcp-server.groovy` + the shared CI
  include-resolver.** No CI lane resolves `#include` today. Build one resolver: scan `#include
  mcp.Name` → read `libraries/<name>.groovy` → strip its `library(...)` decl → append body **before**
  compile/parse. Wire into `HarnessSpec.compileSharedScript` (~lines 233-240, the `HubitatAppSandbox`
  call), `ci/groovy24-parse/parse_check.groovy` (before `cu.compile`), and the `ci/groovy2x-spock`
  scaffold. Add an observable marker — fold `mcpSmokeTestMarker()` into a read tool's output (e.g.
  `toolGetHubPerformance`/`hub_get_metrics` ~line 11211, or `hub_get_info`). Add a `ResolverSpec` + a
  marker-assertion spec. KEEP `preferences{}` (lines 32-37) and `mappings{}` (651-665) at file scope —
  they cannot be `#include`d.
- **T5 — Prove `#include` compiles/loads on the e2e hub** (the load-bearing validation). Use the dev
  tool / deploy script to push app(+`#include`) + library from the PR SHA to the leased e2e hub, then
  assert the app compiled with the include resolving (marker present via the read tool). Blocked by
  T3, T4.
- **T6 — e2e: deploy full PR package via the dev tool from PR-SHA URLs; add `libraries/**` to the
  `hub-e2e.yml` path filter; strengthen `test_list_libraries` to ASSERT `McpSmokeTestLib` present
  (currently tolerant); assert the compile (the option-(i) won't-break-via-HPM proof).** Blocked by T3.

## Technical reference (for a fresh context)

- **Repo:** `kingpanther13/Hubitat-local-MCP-server` (maintainer's MAIN repo; `level99` is the fork).
  `AGENTS.md`/`CLAUDE.md` are byte-identical (`cp AGENTS.md CLAUDE.md` if editing AGENTS.md).
- **Existing code tools** (gateway `hub_manage_code`, all support `importUrl`): `hub_create_app`,
  `hub_update_app` (`/app/save`), `hub_create_driver`, `hub_update_driver`, `hub_create_library`,
  `hub_update_library` (`/library/saveOrUpdateJson`), `hub_delete_item`. Read: `hub_get_source`,
  `hub_list_libraries` (new, `/hub2/userLibraries`) in `hub_read_apps_code`.
- **Library endpoints:** list `/hub2/userLibraries` → `[{id, source, version, name, namespace}]`;
  install/update `/library/saveOrUpdateJson` (`{id, source, version}`); single
  `/library/list/single/data/{id}`.
- **Bundle endpoint** (NOT needed for this plan, for reference): `/bundle2/uploadZipFromUrl?url=&pwd=&private=`
  (FW ≥ 2.3.8.108, httpGET) vs `/bundle/uploadZipFromUrl` (older, POST JSON). HPM source:
  `HubitatCommunity/hubitatpackagemanager` `apps/Package_Manager.groovy`, `installBundle` ~4112-4164
  (re-fetch via `gh api -H "Accept: application/vnd.github.raw" repos/.../contents/apps/Package_Manager.groovy`).
- **Adding a tool (checklist, learned building `hub_list_libraries`):** tool def in
  `getToolDefinitions` (after a sibling block); impl fn; dispatch `case` in `executeTool`; classify in
  `getReadOnlyToolNames` (READ only — a WRITE/dev tool stays out); gateway membership
  (`getGatewayConfig`: tools[] + summaries + searchHints); `McpToolAnnotationsSpec` total count
  (`names.size() == N`) AND the `expectedReadOnly`/`expectedWrites` snapshots; Spock unit + dispatch
  (`@Unroll useGateways`) tests; e2e scenario; doc tool-counts (README/SKILL/TOOL_GUIDE/tool-reference)
  + tables (`sandbox_lint`'s `[TOOL_COUNT]` gate enforces the doc counts). Annotations are auto via
  `annotationsForLeaf()` from the read/write classification.
- **e2e deploy:** `.github/workflows/hub-e2e.yml` + `.github/scripts/mcp_deploy_source.sh` — `mcp_call`
  curls JSON-RPC `tools/call` to `MCP_URL`; deploys the parent app via `hub_update_app(importUrl=PR_SOURCE_URL)`
  (PR-SHA raw URL, computed in the workflow from the PR head SHA); verifies via `hub_get_source`
  char-count change. `mcp_restore_source.sh` restores the app after. Path filter:
  `hubitat-mcp-server.groovy`, `hubitat-mcp-rule.groovy`, `tests/e2e_test.py`, `hub-e2e.yml`,
  `.github/scripts/mcp_*.sh`. **e2e is non-blocking/flaky** (cloud test hub; PRs merge with it red).
  Dev mode must be ON on the test hub (`mcp_setup_env.sh` checks). 30-min lease (`lease_acquire.sh`).
- **CI lanes:** unit-tests (Spock 2×2 matrix normal/strict × gateway/flat, eighty20results hubitat_ci,
  JDK 11, ~2min — **rerun only the changed spec locally, let CI do the full matrix**), groovy24-parse,
  groovy2x-spock (allow-failure), sandbox-lint (python), pr-guard (bot-only: version/`currentVersion()`/
  header version strings/`releaseNotes`/`dateReleased`/CHANGELOG/README `## Version History`).
- **`#include` facts (from recon):** no lane resolves `#include` today (`HubitatAppSandbox` has no
  include hook). No `@Field static` in the file; closures are method-scoped (extract whole methods).
  `preferences{}` (32-37) + `mappings{}` (651-665) must stay file-scope. Wizard mega-methods
  `_rmClickAppButton` (16208-20954) + `_createNativeAppShell` (22361-24107) are whole-only extractions
  (much later). The smoke-test lib: `libraries/mcp-smoke-test-lib.groovy` =
  `library(name:"McpSmokeTestLib", namespace:"mcp", ...)` + `String mcpSmokeTestMarker(){ "smoke-ok-v1" }`.

## Maintainer preferences (durable; also in memory)

- No emojis anywhere. **Show the exact PR/issue/comment draft + get approval before posting** (pushing
  code is always fine). **Rerun only the fixed spec locally, let CI handle the rest.** No local-env
  (Termux/proot) details in PRs/commits. Attribute AI analysis when posting under the maintainer's
  account. Don't dismiss bugs as pre-existing; don't defer in-scope work for size (but genuinely
  dependent, not-yet-built work sequences into its own PR). One RM write at a time on a hub.

## Worktrees

- `feat/hub-list-libraries` → `.../worktrees/hubitat-local-mcp-server/hub-list-libraries` (PR #241).
- `feat/include-smoke-test-209` → `.../worktrees/hubitat-local-mcp-server/include-smoke-test-209` (PR-2,
  this branch, stacked on #241).
