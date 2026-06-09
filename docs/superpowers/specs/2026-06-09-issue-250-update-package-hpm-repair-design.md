# Issue #250 — `hub_update_package`: full HPM-repair-style deploy

## Goal

Make `hub_update_package` deploy a git ref the way Hubitat Package Manager's
`performRepair` does — **override whatever is installed** — but anchored to the
**manifest at the target ref** (HPM repair reads the package's *published* stored
manifest; this tool reads `packageManifest.json` at `ref` so it can install
*unmerged* PRs). Also surface it as a top-level Developer-Mode tool, and bring the
e2e dead-man watchdog v2 deploy to the same full-repair flow.

Triggering the real installed HPM app was considered and rejected: HPM repair reads
the published manifest (cannot target a PR ref), exposes no clean API (only its
fragile classic `dynamicPage` UI), and cannot be assumed present on every hub. So we
replicate HPM's repair *method* in-process using primitives we already ship.

## #1 — Surface as a standalone top-level dev-mode tool

Remove `"hub_update_package"` from the `hub_manage_mcp` gateway membership in
`getGatewayConfig()`. It already lives in `getDeveloperModeOnlyToolNames()`, so it
stays hidden when Developer Mode is off and appears as a **top-level leaf** (both
gateway and flat modes) when on. The `executeTool()` dispatch case is unchanged.
`hub_manage_mcp` keeps `hub_update_mcp_settings`.

## #2 — Full HPM-repair deploy (rewrite `toolUpdatePackage`)

Mirror `performRepair`'s sequence (bundles → apps), anchored to the PR ref:

1. **Pre-checks (kept):** dev-mode gate, `ref` validation, `confirm`+backup gate,
   fetch app source at ref, parse `#include`s (used only for the coverage guard).
2. **Fetch the manifest at the ref:** `${base}/${ref}/packageManifest.json`; parse.
   New abort reasons `manifest_fetch_failed` / `manifest_unparseable` (fail-closed).
3. **Re-anchor URLs to the ref:** for each `manifest.bundles[].location` and
   `manifest.apps[].location`, strip the
   `https?://raw.githubusercontent.com/<owner>/<repo>/<ref>/` prefix → repo-relative
   path → re-anchor to `${base}/${ref}/<relpath>` (same transform as
   `mcp_watchdog_deploy.sh`). Handles the PR-ref requirement HPM can't.
4. **Coverage guard** (mirrors the watchdog): `#include`s present but the manifest
   declares no bundle → abort `bundle_required_but_undeclared`.
5. **dryRun** → return the plan (bundles + apps + resolved class ids), zero writes.
6. **Bundles FIRST (override):** `toolInstallBundle([importUrl, confirm:true])` per
   `manifest.bundles[]`. The `/bundle2/uploadZipFromUrl` endpoint overwrites in place
   (true repair). Abort-before-apps on any failure (`bundle_install_failed` /
   `bundle_install_threw`) — apps never touched.
7. **Apps LAST:** for each `manifest.apps[]`, resolve its Apps Code class id by
   `namespace`+`name` via `/hub2/userAppTypes`, then
   `toolUpdateAppCode([appId, importUrl, confirm:true])`. Deploy the **self** app
   (`mcp` / `MCP Rule Server`, the running parent) **last**, all non-self apps first,
   so the self-recompile that drops the in-flight response (#237) is the final act.
   The #237 `lastSelfDeploy` capture is preserved for the self leg. If a manifest
   app's class id can't be resolved → abort `app_class_unresolved`.
8. **Remove** `getPackageLibraryRegistry()`, the per-`#include`→`libraries/<file>`
   mapping, the per-file `toolInstallLibrary`/`toolUpdateLibraryCode` install loop,
   the `/hub2/userLibraries` snapshot, and the `unmapped_include` / `library_*` abort
   reasons. (`toolInstallLibrary` / `toolUpdateLibraryCode` stay — they back the
   `hub_create_library` / `hub_update_library` tools.) Output: `bundles[]` + `apps[]`
   replace `libraries[]` / `plannedLibraries`.

**Why it fixes the chicken-and-egg:** the bundle at the target ref already ships
whatever libraries that ref needs; the hub unpacks them. Nothing in the running
(older) build needs to know about a new library.

## #3 — Docstring scope

Rewrite the description: full HPM-repair deploy of the package at a ref — overrides
the installed library **bundle(s)** + **every manifest app** (parent + child),
bundles first, apps last, self app last; `dryRun` plans with no writes; gated on
Developer Mode + Write master + `confirm` + recent backup. Keep within the
~1.5 KB/tool `tools/list` size budget. Update the `abortReason` enum in
`outputSchema`.

## Watchdog v2 — full repair (same PR)

`mcp_watchdog_deploy.sh` today installs all manifest bundles + the **parent app
only**. Convert it to full repair: after the bundle step, iterate **`manifest.apps`**
(not just the parent) and deploy each via the existing `resolve_class_id` +
`deploy_app_via_watchdog` helpers, in manifest order. The watchdog is a separate app,
so deploying the MCP server is not a self-recompile for it; the relay-dropped
response on the large parent app is already handled per-app by
`deploy_app_via_watchdog`. No watchdog Groovy change is needed (`adminInstallBundle` +
`deploy_app_via_watchdog` already exist). Update `tests/test_deploy_bundle_only.py` if
it pins single-app deploy.

## Ripple (directly implicated — in this PR)

- `tests/sandbox_lint.py` `check_include_library_lockstep()`: drop the registry leg →
  triplet (`#include` ⇄ library file ⇄ `LIBS`).
- `tests/test_sandbox_lint.py`: drop the registry test cases/params.
- `AGENTS.md` + `CLAUDE.md` (byte-identical): library checklist step 3 (remove
  "register in `getPackageLibraryRegistry()`"), quartet→triplet wording, and the
  "steps 3+4 lockstep / unmapped `#include` aborts `hub_update_package`" note.

## Watchdog question (answered)

Watchdog v2 is **not** affected by the chicken-and-egg registry problem: its restore
path uses the local File-Manager cache (no registry), and its deploy path already
installs via `hub_install_bundle`. This PR brings its deploy *into line* with full
repair (adds the child app), it does not fix a registry defect there.

## Testing

- **Spock** — rewrite `ToolUpdatePackageSpec`: manifest fetch → ref-anchored
  bundle+app URLs → bundles-first → apps (self last); aborts (manifest fetch/parse,
  bundle install, coverage guard) before any app; dryRun plan; dev-mode gate.
  `McpToolAnnotationsSpec`: `hub_update_package` now top-level (not in
  `hub_manage_mcp`), still write+destructive + dev-gated. `GatewayToggleSpec`: still
  routable in both modes.
- **e2e** — `tests/e2e_test.py` dryRun-only scenario (zero writes; the real-deploy leg
  recompiles the server so it can't be e2e'd). Update `tests/BAT-v2.md` T44b.
- **Manual live-hub** — real-deploy test against the maintainer's hub (backup first,
  Apps-editor recovery path), confirmed step-by-step before any live write.
