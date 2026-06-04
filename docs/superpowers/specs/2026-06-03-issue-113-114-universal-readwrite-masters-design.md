# Design — Universal Read/Write masters + per-tool/per-gateway overrides (issues #113, #114)

Status: **Approved (design)** — pending written-spec review.
Base: branch `feat/issue-113-114-toggle-overrides`, cut from PR **#233's head** — remote branch `origin/worktree-issue-105-pr2b-robustness` (its PR title is "PR2b/2c…"; there is no separate `pr2c` remote branch), at commit `dec33b26`. Rebase onto `main` once #233 merges (#233 completes issue #105 and lands before this).
Packaging: **one combined draft PR** closing #113 and #114.

## 1. Problem & current state (post-#233)

Permission gating is inconsistent. Some tools are gated (Hub Admin Read/Write, Built-in App), and **many writes and reads are not gated at all** — `hub_call_device_command`, `hub_set_mode`, `hub_set_hsm`, `hub_update_device`, `hub_delete_captured_state`, plus every core read like `hub_list_devices`. The original #113 ("collapse 3 toggles → 2") is stale: `enableBuiltinAppRead` was already retired, MCP annotations + the `hub_read_*`/`hub_manage_*` gateway read/write split + `getReadOnlyToolNames()` classification all shipped (#202, #225/PR1B, #233). What remains is the **toggle taxonomy** and the **per-tool runtime filter** that #114 depends on.

What #233 already gives us, and which this design leans on:
- **`getReadOnlyToolNames()`** (single source of truth: positive read-only set; everything else is write).
- **`getToolDefinitions()`** builds one **`hideByName` Set** that filters *every* surface (flat base tools, gateway sub-tool catalogs) and auto-drops empty gateways (L1795). This is the one hook point for all hiding.
- **`executeTool(toolName, args)`** (L5057) is the **single dispatch chokepoint** — both base-tool calls (`handleToolsCall` → `executeTool`) and gateway sub-tool calls (`handleGateway` → `executeTool`) pass through it, and it already hosts the custom-engine gate.
- `annotationsForLeaf` / `annotationsForGateway` recompute read/write hints from the *visible* sub-tool set, so hiding propagates into annotations for free.

## 2. Goals

- **#113:** Every tool gated by one of **two universal master toggles** — **Read** (all read tools) and **Write** (all write tools). Not "Hub Admin" — *any* read / *any* write. Remove the **Built-in App** toggle. Keep **Developer Mode** (off) and **Custom Rule Engine** (off) as additional layered gates, and **Gateway mode** (on). No ungated tools remain.
- **#114:** An **Advanced** sub-page on the app settings page that lets users disable individual tools and whole gateways (**deny-only**, below the masters), via two list settings feeding the same runtime filter.

## 3. Non-goals

- Renaming/reordering tools or gateways (that surface is settled by #105/#225).
- Re-scoping which tools require `confirm`+backup (preserve today's set exactly — see §5).
- Setting overrides via an MCP tool (issue #114 explicitly out of scope).
- Elicitation-based confirmation (future direction noted in #113).

## 4. Decisions (with rationale)

| # | Decision | Rationale |
|---|---|---|
| D1 | Two universal masters **Read** + **Write**, both **default ON**, gating every tool | User directive. Default-ON ⇒ zero breakage on upgrade; masters are kill-switches. Closes the "ungated tools" gap (every tool now has a gate even if on by default). |
| D2 | New setting keys **`enableRead`** / **`enableWrite`** (default ON) | `null → ON` means existing installs need no migration shim and nothing breaks. The old `enableHubAdminRead`/`enableHubAdminWrite` inputs are removed; orphaned stored values are inert. |
| D3 | **Remove** the Built-in App toggle (`enableBuiltinApp`) | "3 → 2": Hub Admin Read + Hub Admin Write + Built-in App → Read + Write. Built-in App's tools reclassify under Read/Write (native CRUD = write + the existing confirm/backup). |
| D4 | Central master gate in `executeTool`, driven by `getReadOnlyToolNames()` | Single chokepoint guarantees completeness and auto-covers new tools; avoids editing ~100 handlers. |
| D5 | Keep `confirm`+24h-backup on exactly the **same 36 tools** that have it today | No safety regression. Decouple the *toggle* (now central/universal) from the *destructive confirmation* (stays in handler). |
| D6 | Keep **Developer Mode** + **Custom Rule Engine** as additional layered gates | User directive. Dev Mode = extra gate on self-admin writes; Custom Engine = `customEngineMode` visibility on `custom_*`. |
| D7 | **#114 disabling is global** | A tool in multiple gateways disabled anywhere is disabled everywhere; disabling a gateway disables all its tools incl. shared. Maps directly onto the global `hideByName` set. |
| D8 | Advanced-disabled tools: drop from catalog + `hub_search_tools`; **keep in `hub_get_tool_guide`**; distinct error on direct call | User directive. Guide is a static doc, so "kept" is automatic; the distinct error tells users where to re-enable. |
| D9 | One combined PR | Filter is inert without the UI that populates it; tested end-to-end together. |

## 5. Architecture — gate refactor (#113)

### 5.1 Central master gate
At the top of `executeTool(toolName, args)` (alongside the existing custom-engine gate), add a universal classification-driven gate:

```
def readOnly = getReadOnlyToolNames()
def isRead = readOnly.contains(toolName)
if (isRead && settings.enableRead == false)
    throw IllegalArgumentException("Read tools are disabled. Enable 'Read Tools' in MCP Rule Server settings.")
if (!isRead && settings.enableWrite == false)
    throw IllegalArgumentException("Write tools are disabled. Enable 'Write Tools' in MCP Rule Server settings.")
```

Note `== false` (not falsy): unset ⇒ default ON. Gateway entries themselves are not leaf tools and are not dispatched through this path as a callable; only leaf `toolName`s reach here.

### 5.2 Remove redundant per-handler gates
- Delete the **19** `requireHubAdminRead()` calls and the **16** `requireBuiltinApp()` calls (central gate + classification now cover them).
- `requireHubAdminWrite(confirm)` → **`requireDestructiveConfirm(confirm)`**: drop its `enableHubAdminWrite` check (now central via Write master); keep the `confirm==true` + `state.lastBackupTimestamp` ≤24h check verbatim. Apply to the **same 36 tools** that call it today (rename the call site only). Net behavior: destructive tools keep confirm+backup; the toggle layer is the universal Write master (default ON).
- Delete `requireHubAdminRead`/`requireBuiltinApp` function defs; rename `requireHubAdminWrite` → `requireDestructiveConfirm` and drop its first guard.

### 5.3 Visibility (`getToolDefinitions` → `hideByName`)
Extend the `hideByName` builder:
- `enableRead == false` → add **all** read-only tool names.
- `enableWrite == false` → add **all** write tool names (`getAllToolDefinitions().name − readOnly`).
- Existing custom-engine and (removed) built-in blocks: rework so `customEngineMode` no longer references `enableBuiltinApp`.

Empty gateways drop automatically; gateway annotations recompute from `visibleSubTools`. With both masters default ON, default catalog is unchanged (still 30 entries in the e2e default config, modulo the Built-in App removal — recompute, see §8).

### 5.4 `customEngineMode` rederivation (Built-in App removed)
Old: `full` (engine ON) / `readonly` (engine OFF + builtinApp ON) / `off` (both OFF).
New: `full` (engine ON) / `readonly` (engine OFF + **Read master ON**) / `off` (engine OFF + Read master OFF). Update both `getToolDefinitions()` and the `executeTool` custom-engine gate prose/logic. The "you can still see/toggle existing custom rules with the engine off" behavior is preserved, now keyed off the Read master.

### 5.5 Developer Mode layering
Self-admin tools (e.g. `hub_update_mcp_settings`) are writes → already under the Write master centrally; they additionally keep their `enableDeveloperMode` check + per-write WARN audit. No change to Dev Mode semantics beyond it now sitting on top of the universal Write gate.

## 6. Architecture — Advanced overrides (#114)

### 6.1 Settings + page
- `preferences{}` registers a third page: `page(name: "advancedOverridesPage")` (pattern at L32-35).
- mainPage "Settings" section gets an `href name: "advancedOverrides", page: "advancedOverridesPage", title: "Advanced: Per-tool Overrides", description: …` (pattern at L53).
- `def advancedOverridesPage()` → `dynamicPage(name: "advancedOverridesPage", …)`.

### 6.2 Two list settings (deny-only, global)
- **`disabled_tools`** — list of tool names. Fed into `hideByName` ⇒ each name vanishes from base + every gateway + search.
- **`disabled_gateways`** — list of gateway names. Each expands to all of that gateway's `config.tools` (shared tools included), added to the effective disabled set; the gateway entry then drops via the existing empty-gateway path.

The effective disabled set = `disabled_tools ∪ (⋃ tools of disabled_gateways)`. This set is unioned into `hideByName` and consulted in `executeTool`.

### 6.3 Layout — Flat (decided)
Two multi-select inputs, matching issue #114's literal settings keys:
- `input "disabled_tools", "enum", multiple: true, options: <all ~111 unique tool names>` — "Disable these tools".
- `input "disabled_gateways", "enum", multiple: true, options: <all 19 gateways>` — "Disable these gateways".

Each tool appears exactly once, so "disable globally" is intrinsic (no cross-section union). Runtime read is two `settings` lookups; Reset clears two settings. The page also shows brief help text and (for orientation) a read-only paragraph listing which gateway each tool belongs to. A **Reset** `input "resetOverridesBtn", "button"` clears both settings via `appButtonHandler` (pattern at L253/L275). The options lists are generated at render time from `getAllToolDefinitions()` / `getGatewayConfig()` so they never drift from the live tool surface.

### 6.4 Filter wiring + errors
- `executeTool`: after the master gate, if `toolName` ∈ effective-disabled-set → throw a **distinct** error: "… is disabled in Advanced settings (Per-tool Overrides). Re-enable it there." (distinguishable from the master/coarse-toggle error).
- `hub_search_tools` corpus: exclude the effective-disabled set (and master-hidden set) so disabled tools are not discoverable.
- `hub_get_tool_guide`: untouched (static doc) ⇒ disabled tools still documented.

### 6.5 Self-admin allowlist (`hub_update_mcp_settings`)
- Allow `enableRead` (replaces `enableHubAdminRead`).
- **Exclude** `enableWrite` (footgun: writing `false` disables the tool's own path), `disabled_tools`, `disabled_gateways` (footgun: could disable `hub_update_mcp_settings` itself). UI-only.
- Update the tool description's allowlist enumeration and the `searchHints` keyword string accordingly.

## 7. Read/Write classification completeness

Every leaf tool must be classified. `getReadOnlyToolNames()` is the read set; the complement is write. Add a **CI/unit guard**: assert that every name in `getAllToolDefinitions()` is either in `getReadOnlyToolNames()` or provably reachable by the write branch (i.e., the partition is total and disjoint), so a new tool can never be "unclassified/ungated". Reclassify the formerly Built-in-App-gated tools explicitly: `hub_list_rules`, `hub_get_rule_health`, `hub_list_device_dependents`, `hub_get_app_config` → read; `hub_call_rule`, `hub_set_rule_paused`, `hub_set_rule_private_boolean`, `hub_create/update/delete/clone/export/import_native_app` → write (already consistent with current `getReadOnlyToolNames()`).

## 8. Test plan (unit + e2e + BAT — all in scope)

**Unit (Spock, `src/test/groovy/`):**
- Update the **17 specs** referencing `enableBuiltinApp` and the **22** setting `enableHubAdminRead/enableHubAdminWrite` → new `enableRead`/`enableWrite` keys and the Built-in-App-removed model. `McpToolAnnotationsSpec` included.
- New specs: (a) master-gate completeness — every tool classified, read-tool blocked when `enableRead=false`, write-tool blocked when `enableWrite=false`; (b) master visibility — `getToolDefinitions()` hides all reads/writes and collapses gateways when a master is off, gateway annotations flip; (c) deny-filter — `disabled_tools` global hide across multiple gateways, `disabled_gateways` expansion incl. shared tools, distinct error on dispatch, search-corpus exclusion, guide untouched; (d) `requireDestructiveConfirm` keeps confirm+backup on the 36; (e) `customEngineMode` rederivation off the Read master; (f) advanced page renders + Reset clears.
- `tests/sandbox_lint.py` clean; existing read/write-split CI guard still green.

**E2E (`tests/e2e_test.py`, live hub):**
- Recompute and update the `len(tools) == 30` assertion (L414) for the Built-in-App-removed default config; update the recommended-config comment block (L1079-1082) to `enableRead`/`enableWrite`.
- Update allowlist tests (T221, L1105-1114) `enableHubAdminWrite` → `enableWrite` (still excluded/footgun). Keep `enableCustomRuleEngine` toggle tests.
- Add e2e coverage: flip Read off ⇒ read tools gone from `tools/list` + call rejected; flip Write off ⇒ writes gone; set `disabled_tools`/`disabled_gateways` ⇒ targeted hide + distinct error.

**BAT (`tests/BAT-v2.md`):** add scenarios — master kill-switch behavior; advanced per-tool/gateway disable from the UI; confirm destructive tools still demand confirm+backup. Gateway mode only; serialize RM writes; no Z-Wave/Zigbee.

## 9. Docs

- **AGENTS.md/CLAUDE.md**: toggle taxonomy section → Read/Write masters + Dev Mode + Custom Engine + Gateway mode; note Built-in App removal. The read/write-split gateway rule already present — keep, cross-reference the masters.
- **SKILL.md**, **TOOL_GUIDE.md**, **agent-skill/hubitat-mcp/safety-guide.md**: replace "Built-in App Tools" references with the master model; document advanced overrides + the distinct disabled-by-advanced error.
- **README.md** (settings/permission section), **SECURITY.md** (permission model). Version strings/CHANGELOG/manifest are bot-only — do not touch (PR `## Release Notes` drives them).

## 10. Risks & mitigations

- **Large blast radius** (~50 source sites, ~39 test files): mitigated by the central-gate design (few source edits beyond deletions) + the completeness guard test.
- **Removing per-handler gates could miss a write** ⇒ the completeness guard + dispatch-level central gate make any miss fail loudly, not silently expose a tool.
- **`hub_update_mcp_settings` self-lockout** ⇒ `enableWrite`/`disabled_*` excluded from the allowlist; `enableDeveloperMode` already UI-only.
- **Built-in App stored value orphaned** ⇒ inert; optionally swept in a follow-up Dev-Mode cleanup tool (not this PR).

## 11. Acceptance criteria

- [ ] `enableRead`/`enableWrite` masters (default ON) gate every tool centrally; Built-in App toggle removed; Dev Mode/Custom Engine/Gateway mode retained.
- [ ] No tool is unclassified or ungated (completeness guard green).
- [ ] `confirm`+24h-backup preserved on the current 36 destructive tools.
- [ ] Advanced sub-page disables individual tools + whole gateways, deny-only, **global** semantics; Reset works.
- [ ] Disabled tools drop from catalog + `hub_search_tools`, stay in `hub_get_tool_guide`, and return a distinct error on direct call.
- [ ] Self-admin allowlist updated (`enableRead` in; `enableWrite`/`disabled_*` excluded).
- [ ] Unit + e2e + sandbox_lint green; BAT scenarios added; docs updated.
- [ ] One combined draft PR closes #113 and #114, based on #233.
