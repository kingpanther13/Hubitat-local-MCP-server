# Issue #105 — PR1 game plan

> **Canonical home** of the PR1 (tool audit) game plan, paired with [Issue #105](https://github.com/kingpanther13/Hubitat-local-MCP-server/issues/105). The same content is also linked from a comment on #105 for the issue discussion thread; this file is the authoritative version, pickup-able from any computer.

## PR1 game plan — pre-audit and execution strategy

_Generated 2026-05-26 during PR3 (PR #210) cleanup. Implementation does NOT start until #208 merges. This document captures the audit + plan so it isn't lost in the gap._

### Context & sequencing

- **PR3 (PR #210)** is merged (`2eba7a9`). It added the "Tool design rules" section to `AGENTS.md` — the verb vocabulary, naming conventions, consolidation guidelines, and annotation rules this PR1 audit applies.
- **PR #208** is the in-flight consolidation PR (still draft, blocked on #203). It reduces 103 → 95 tools. PR1 starts on the **post-#208 baseline** (95 tools).
- **#208's renames don't conform to AGENTS.md.** It introduces `save_app` / `save_driver` / `save_library` — and `save` is NOT in the verb vocabulary. PR1 will rename these to `set_*` per the `set_X` = full-replacement/PUT-like/upsert rule established in AGENTS.md, matching the maintainer's earlier instinct that install+update pairs are natural upsert candidates.
- **Issue #207** carries two more consolidations deferred from #208 (list_apps merge, list_virtual_devices cut). PR1 should absorb both since they apply the same merge pattern.

### PR1 sub-structure (maintainer-approved 2026-05-26)

- **PR1a** — combined rename + consolidations. Every tool gets its final `hub_`-prefixed verb-noun name AND every consolidation lands in the same pass. No rename-then-delete waste.
- **PR1b** — gateway read/write splits (split mixed gateways per AGENTS.md SHOULD-rule).
- **PR1c** — docstring + schema audit (Anthropic-quality descriptions, add `outputSchema` to structured-return tools).

### Tool inventory snapshot

- **Current main**: 103 tools, 23 core flat + 80 behind 13 gateways. Zero have the `hub_` prefix.
- **Post-#208**: 95 tools (8 removed via consolidation). PR1a's rename map starts from here.
- **Gateways**: 13 today. PR1b may split some mixed gateways into read/write siblings.
- Full inventory with handler functions, safety gates, and read/write classification is in the pre-audit working file (not posted inline — too long for a comment, available in the implementation kickoff).

### PR1a rename + merge map (post-#208 baseline)

Categorized by what's happening to each tool.

#### A. Mechanical `hub_` prefix only (no other change)

These tools already use a vocabulary-conformant verb and a clean noun. Apply `hub_` and move on.

| Current (post-#208) | New |
|---|---|
| list_devices | hub_list_devices |
| get_device | hub_get_device |
| get_attribute | hub_get_attribute |
| get_device_events | hub_get_device_events |
| custom_get_rule | hub_custom_get_rule _(`custom_` prefix kept — denotes legacy engine, distinct from native rules)_ |
| custom_create_rule | hub_custom_create_rule |
| custom_update_rule | hub_custom_update_rule |
| custom_delete_rule | hub_custom_delete_rule |
| custom_test_rule | hub_custom_test_rule |
| custom_export_rule | hub_custom_export_rule |
| custom_import_rule | hub_custom_import_rule |
| custom_clone_rule | hub_custom_clone_rule |
| custom_get_rule_diagnostics | hub_custom_get_rule_diagnostics |
| get_hub_info | hub_get_info _(noun-trim: drop redundant `hub_` qualifier — `hub_` prefix is universal)_ |
| get_modes | hub_get_modes |
| set_mode | hub_set_mode |
| get_hsm_status | hub_get_hsm_status |
| set_hsm | hub_set_hsm |
| create_hub_backup | hub_create_backup _(noun-trim: drop `hub_` qualifier)_ |
| list_virtual_devices | hub_list_virtual_devices |
| update_device | hub_update_device |
| manage_virtual_device | hub_manage_virtual_device _(flat-multi-action exception per AGENTS.md L39)_ |
| get_tool_guide | hub_get_tool_guide |
| search_tools | hub_search_tools |
| list_variables | hub_list_variables |
| get_variable | hub_get_variable |
| set_variable | hub_set_variable |
| create_variable | hub_create_variable |
| delete_variable | hub_delete_variable _(handler-name drift `toolDeleteHubVariable` should also rename for consistency)_ |
| get_variable_history | hub_get_variable_history |
| list_rooms | hub_list_rooms |
| get_room | hub_get_room |
| create_room | hub_create_room |
| delete_room | hub_delete_room |
| reboot_hub | hub_reboot _(noun-trim: drop `hub_` qualifier)_ |
| shutdown_hub | hub_shutdown _(noun-trim)_ |
| delete_device | hub_delete_device |
| list_hub_apps | _(deferred to #207's list_apps merge — see merge section)_ |
| list_hub_drivers | hub_list_drivers _(noun-trim: drop `hub_` qualifier; or `hub_list_hub_drivers` if `drivers` alone is ambiguous → controller decide)_ |
| get_app_source | _(see triple-merge in section B)_ |
| get_driver_source | _(see triple-merge)_ |
| get_library_source | _(see triple-merge)_ |
| list_item_backups | hub_list_item_backups |
| get_item_backup | hub_get_item_backup |
| save_app _(#208's name)_ | hub_set_app _(also rename `save_app` → `set_app` since `save` isn't in vocab)_ |
| save_driver _(#208's name)_ | hub_set_driver |
| save_library _(#208's name)_ | hub_set_library |
| delete_app | hub_delete_app |
| delete_driver | hub_delete_driver |
| delete_library | hub_delete_library |
| restore_item_backup | hub_restore_item_backup |
| get_hub_logs | hub_get_logs _(noun-trim)_ |
| get_device_history | hub_get_device_history |
| get_performance_stats | hub_get_performance_stats |
| get_hub_jobs | hub_get_jobs _(noun-trim)_ |
| get_debug_log_state _(#208's name)_ | hub_get_debug_log_state |
| update_debug_logs _(#208's name)_ | hub_update_debug_logs |
| get_memory_history | hub_get_memory_history |
| get_zwave_details | hub_get_zwave_details |
| get_zigbee_details | hub_get_zigbee_details |
| list_captured_states | hub_list_captured_states |
| list_files | hub_list_files |
| read_file | hub_read_file _(file-manager-domain exception per AGENTS.md L62)_ |
| write_file | hub_write_file _(file-manager-domain exception)_ |
| delete_file | hub_delete_file |
| get_app_config | hub_get_app_config |
| list_app_pages | hub_list_app_pages |
| list_hpm_packages | hub_list_hpm_packages |
| get_hpm_drift | hub_get_hpm_drift |
| list_rm_rules | hub_list_rules _(noun-trim: drop `rm_` qualifier — no other rule surface here)_ |
| create_native_app | hub_create_native_app |
| update_native_app | hub_update_native_app |
| delete_native_app | hub_delete_native_app |
| export_native_app | hub_export_native_app |
| import_native_app | hub_import_native_app |
| clone_native_app | hub_clone_native_app |
| set_rm_rule_boolean | hub_set_rule_private_boolean _(noun: drop `rm_`, attribute-naming `private_boolean` per Hubitat RM convention)_ |
| update_mcp_settings | hub_update_mcp_settings _(or promote to flat — see decision Q3)_ |
| **Gateways (all 13):** manage_X | hub_manage_X (where X may be trimmed — e.g. `manage_native_rules_and_apps` → `hub_manage_native_rules`) |

#### B. Verb-vocab corrections + noun rewrites

Tools whose current verb isn't in the AGENTS.md vocabulary — fold per the verb table.

| Current (post-#208) | New | Rationale |
|---|---|---|
| send_command | hub_call_device_command | `send` → `call`; noun was implicit, made explicit (per AGENTS.md L37) |
| generate_bug_report | hub_report_issue | `generate` dropped; `report` locked to this one tool per AGENTS.md L64 |
| check_for_update | hub_get_update_status | `check` → `get`; noun rewrite to clarify it returns the status not the update itself |
| check_rule_health | hub_get_rule_health | `check` → `get` |
| device_health_check | hub_get_device_health | verb-first ordering + `check` → `get` |
| force_garbage_collection | hub_call_gc | `force` → `call`; noun-trim `garbage_collection` → `gc` (common abbreviation) |
| zwave_repair | hub_call_zwave_repair | verb-first ordering; `repair` action → `call` (mutating action invocation) |
| run_rm_rule | hub_call_rule | `run` → `call`; noun-trim `rm_rule` → `rule` |
| rename_room | _(merged into hub_update_room — see section C)_ | `rename` → `update` |
| remove_connector | _(merged into hub_delete_variable — see section C)_ | `remove` → `delete`; also folded into variable lifecycle |
| clear_debug_logs | _(removed by #208)_ | folded into `update_debug_logs(action=clear)` per #208; PR1 may further rename the action |
| clear_captured_states | _(merged into hub_delete_captured_state — see section C)_ | `clear` → `delete` (no id = delete all) |
| delete_captured_state | hub_delete_captured_state | absorbs `clear_captured_states` |

#### C. Verb-pair and feasible consolidation merges

These pairs/triples merge into single tools. Each merge listed with: source tools → target tool + rationale.

| Sources | Target | Rationale |
|---|---|---|
| pause_rm_rule + resume_rm_rule | hub_set_rule_paused (boolean) | AGENTS.md consolidation guideline #1 (verb-pair → `set_<noun>_<attribute>`); the canonical example in the spec |
| clear_captured_states + delete_captured_state | hub_delete_captured_state | Per AGENTS.md guideline #5 (`clear` → `delete`, no-id = delete all) |
| rename_room | hub_update_room | `rename` → `update` with `name` param |
| remove_connector + create_connector | hub_delete_variable + hub_create_variable with `createConnector` flag | Connector is a variable-wrapper; pairing follows variable lifecycle. **DECISION NEEDED — see Q1.** |
| get_app_source + get_driver_source + get_library_source | hub_get_source(type=app\|driver\|library, id) | Triple already shares helper `toolGetItemSource`. Type discriminator is the obvious axis. **DECISION NEEDED — see Q2.** |
| #208's save_app + save_driver + save_library | hub_set_item(type=app\|driver\|library, id?, ...) OR keep as hub_set_app + hub_set_driver + hub_set_library | Triple shares helper `toolSetItem` after #208's merge. Further consolidation into one tool with `type` discriminator is the next step — keep both halves of upsert in one tool. **DECISION NEEDED — see Q2.** |
| delete_app + delete_driver + delete_library | hub_delete_item(type=app\|driver\|library, id) OR keep three siblings | Same axis as above. **DECISION NEEDED — see Q2.** |
| list_hub_apps + list_installed_apps | hub_list_apps(scope=code\|instances) | Deferred from #208 to #207. AGENTS.md guideline #4 (overlapping noun + filter). **DECISION NEEDED — see Q4.** |
| list_virtual_devices | hub_list_devices(filter=virtuals\|...) | Deferred from #208 to #207. **DECISION NEEDED — see Q4.** |
| list_rm_rules + custom_list_rules | _(no merge — different rule engines)_ | Custom and native are intentionally separate surfaces per AGENTS.md § "Custom MCP rule engine is legacy" |
| set_mode + set_hsm | _(no merge — different attributes of the hub)_ | Both legitimate first-class `set_X` tools |

### Decisions pending maintainer input

These merges have semantic ambiguity beyond mechanical verb-folds. Each is laid out below as a question.

**Q1 — Connector + variable merge.** A "connector" is a wrapper around a hub variable that exposes it as a virtual device. Today they're paired tools (`create_connector` / `remove_connector`) under `manage_hub_variables`. Two options:
- **Merge** into `hub_create_variable(..., createConnector?)` + `hub_delete_variable(..., includeConnector?)` — fewer tools, follows the lifecycle pairing.
- **Keep separate** as `hub_create_connector` + `hub_delete_connector` — clearer intent, connector lifecycle becomes independent of variable lifecycle.

**Q2 — App/driver/library triple merge.** Three near-identical tool families (get source, save/upsert, delete) operate on a `type ∈ {app, driver, library}` axis. Today they're siblings (3×3 = 9 tools). #208 already keeps them split (`save_app`, `save_driver`, `save_library`). Two options:
- **Merge** into single tools with `type` discriminator: `hub_get_source(type, id)` + `hub_set_item(type, id?)` + `hub_delete_item(type, id)` — 3 tools instead of 9. Internal helpers already shared.
- **Keep split** as `hub_get_app_source`/`hub_set_app`/`hub_delete_app` × 3 — 9 tools, but each has tighter type-specific docs and safety semantics. App vs driver vs library have different bulk semantics (driver/library support `installs=[...]` bulk arrays, apps reject them) — the discriminator approach would still need per-type branching in the merged tool.

**Q3 — `manage_mcp_self` (1-tool gateway).** Today `manage_mcp_self` is a gateway with one sub-tool (`update_mcp_settings`). Two options:
- **Promote to flat:** drop the gateway, expose as `hub_update_mcp_settings` directly on `tools/list`. Less indirection; the Developer-Mode toggle still gates access.
- **Keep as gateway:** preserves a logical home for future Developer-Mode tools without re-introducing a gateway later.

**Q4 — #207 deferred merges.** Two consolidations were deferred from #208 to issue #207. Two options:
- **Absorb into PR1a:** roll the `list_hub_apps`+`list_installed_apps` → `hub_list_apps(scope=)` merge AND the `list_virtual_devices` → `hub_list_devices(filter=virtuals)` fold into PR1a. Single consolidated sweep.
- **Leave for separate #207 PR:** keep PR1a scoped to the rename + verb-vocab work; #207 handles these merges in its own PR before or after PR1a.

**Q5 — `get_set_hub_metrics` (compound verb) — removed by #208.** Closed by #208's removal. No action.

**Q6 — Custom-engine prefix.** Today the custom rule engine tools all carry `custom_` prefix (`custom_get_rule`, etc.). After `hub_` is added universally, the names become `hub_custom_get_rule`. Two options:
- **Keep `custom_`** as a meaningful sub-namespace distinguishing the legacy engine from native RM tools.
- **Drop `custom_`** since the `hub_` prefix is universal and the native-engine tools have their own clear nouns (`native_app`). Custom tools become `hub_get_rule`, `hub_create_rule`, etc. — risks confusion with native-rule shorthand.

**Q7 — `device_health_check` LED side effect.** The `identifyHub` optional flag triggers a physical hub LED blink — a mild observable write that lives inside a read-classified tool. Two options:
- **Keep merged:** annotate `readOnlyHint: true` with a comment justifying it (LED blink is not state-mutating).
- **Split:** keep `hub_get_device_health` as pure read; add a separate `hub_call_identify_hub` write tool for the LED action.

**Q8 — `export_native_app` saveAs side effect.** When `saveAs` is provided, `export_native_app` writes a file to the hub's File Manager. Same shape as Q7. Two options:
- **Keep merged:** read with optional side effect, documented in the description.
- **Split:** `hub_export_native_app` pure read; add `hub_write_native_app_export` for the saveAs write path.

### PR1b — gateway read/write splits (audit candidates)

Mixed-mode gateways per the AGENTS.md SHOULD-rule. For each, the read/write breakdown is shown. PR1b decides per-gateway whether to split or note it as a justified mixed case.

| Gateway | Reads | Writes | Notes |
|---|---|---|---|
| manage_rules_admin | test, export | delete, import, clone | Mixed; could split into manage_rule_diagnostics (test/export) + manage_rule_admin_writes (delete/import/clone). All currently `custom_*` engine; legacy. |
| manage_hub_variables | list, get, history | set, create, delete, create_connector, remove_connector | Mixed; clean split available. |
| manage_rooms | list, get | create, delete, rename(→update) | Mixed; clean split. |
| manage_destructive_hub_ops | — | reboot, shutdown, delete_device | Pure write ✓ (no action) |
| manage_apps_drivers | list_hub_apps, list_hub_drivers, get_*_source, list_item_backups, get_item_backup | — | Pure read ✓ |
| manage_app_driver_code | — | save_* (set_*), delete_*, restore_item_backup | Pure write ✓ |
| manage_logs | get_*, get_debug_log_state | update_debug_logs | Mostly read; one write (the #208-merged debug logs writer). Borderline; could split or annotate. |
| manage_diagnostics | get_set_hub_metrics(removed by #208), get_memory_history, get_zwave_details, get_zigbee_details, list_captured_states, get_rule_diagnostics, device_health_check | force_garbage_collection (→call_gc), zwave_repair, delete_captured_state, clear_captured_states (folded) | Mixed; clean split available. |
| manage_files | list, read | write, delete | Mixed; file-manager domain — split would give manage_files_read + manage_files_write. |
| manage_installed_apps | list, get | — | Pure read ✓ |
| manage_hpm | list, get | — | Pure read ✓ |
| manage_native_rules_and_apps | list, export, check_rule_health(→get_rule_health) | run(→call), pause(→set_paused), resume(→set_paused), set_boolean, create, update, delete, clone, import | Mixed; clean split available. Largest gateway. |
| manage_mcp_self | — | update_mcp_settings | Pure write ✓ (Q3 may promote to flat anyway) |

**Pure single-mode today (no action):** manage_destructive_hub_ops, manage_apps_drivers, manage_app_driver_code, manage_installed_apps, manage_hpm, manage_mcp_self.

**Splits to evaluate in PR1b:** manage_rules_admin, manage_hub_variables, manage_rooms, manage_logs (borderline), manage_diagnostics, manage_files, manage_native_rules_and_apps.

### PR1c — docstring + schema audit candidates

Per AGENTS.md § Schema design — every new tool that returns structured content should add `outputSchema`. Today: zero tools have one. PR1c retroactively adds `outputSchema` to at least:

- All `hub_get_*` tools that return objects (high-traffic reads).
- All `hub_list_*` tools (return `{items, nextCursor, ...}` shape).
- All `hub_create_*` / `hub_update_*` / `hub_set_*` writes that return the modified entity.

PR1c also applies Anthropic description-quality guidance per-tool: concise first line, usage guidance, semantic IDs over UUIDs, recovery-oriented error text.

### Implementation plan — multi-agent dispatch for PR1a

When #208 + #203 land and the rebased baseline is stable:

1. Re-check the rename map above against the post-#208 surface — any drift gets reconciled before agent dispatch.
2. Branch `issue-105-pr1a-rename-consolidate` off then-current main.
3. Dispatch one implementer agent per **tool-domain slice**, sequentially (NOT in parallel — they all touch cross-cutting code: `getAllToolDefinitions()`, `executeTool()`, `getGatewayConfig()`):
   - Slice 1 — Device & virtual-device tools (`list_devices`, `get_device`, `get_attribute`, `send_command`→`call_device_command`, `get_device_events`, `update_device`, `manage_virtual_device`, `list_virtual_devices`, `delete_device`).
   - Slice 2 — Hub system tools (`get_hub_info`, `get_modes`, `set_mode`, `get_hsm_status`, `set_hsm`, `check_for_update`→`get_update_status`, `create_hub_backup`, `generate_bug_report`→`report_issue`, `reboot_hub`, `shutdown_hub`).
   - Slice 3 — Custom rule engine (all `custom_*` tools).
   - Slice 4 — Native rule engine + apps (`manage_native_rules_and_apps` tools, including verb-pair merge of pause/resume).
   - Slice 5 — Hub variables + rooms (`manage_hub_variables`, `manage_rooms`).
   - Slice 6 — Apps/drivers/libraries (`manage_apps_drivers`, `manage_app_driver_code` — including any approved Q2 triple merges and the `save_*` → `set_*` rename).
   - Slice 7 — Files, item backups, HPM (`manage_files`, item backup tools, `manage_hpm`).
   - Slice 8 — Diagnostics, logs, installed apps, MCP self (`manage_diagnostics`, `manage_logs`, `manage_installed_apps`, `manage_mcp_self`).
   - Slice 9 — Gateway tools themselves (rename all `manage_*` to `hub_manage_*`, drop any redundant noun qualifiers).
   - Slice 10 (sweep) — Tests + TOOL_GUIDE.md + agent-skill/hubitat-mcp/tool-reference.md + BAT scenarios + search-tools BM25 index reconciliation.
4. Each agent gets:
   - The rename map (or its slice).
   - The current `AGENTS.md` § "Tool design rules" content.
   - The "Hard rules for agents" block below.
   - Explicit list of files to touch.
5. Each agent commits per-slice; final PR opens as draft.

### Hard rules for PR1a implementation agents

1. **Merge decisions in the map above are pre-approved.** Do NOT recommend "keep these tools separate" — the maintainer has already decided. If a merge is technically infeasible (e.g. an install vs update flow really can't unify because of a guard or semantic that can't be re-expressed), STOP and report DONE_WITH_CONCERNS with the specific block. Do NOT silently leave them separate.
2. **Read the current `AGENTS.md` § "Tool design rules" before starting.** That section is the source of truth for naming, verb vocabulary, parameter naming, annotations, error contracts. The map above is the authoritative output; AGENTS.md is the authoring rule set behind it.
3. **Every renamed/merged tool keeps all four annotation hints** (`readOnlyHint`/`destructiveHint`/`idempotentHint`/`openWorldHint`). PR #202 baseline already has the first two; PR1c adds the missing two — but PR1a should NOT regress what's there.
4. **Never `cd && git` compound commands.** Use `git -C <path>` or absolute paths. CLAUDE.md blocks the pattern.
5. **No deprecation aliases.** Hard rename. Old tool names are gone from `getAllToolDefinitions()` after this PR.
6. **Tests update with renames.** Direct-call test and dispatch-envelope test per tool. If a merge changes the behaviour surface, BOTH layers' tests update in the same commit.
7. **`tests/BAT-v2.md` updates in the same PR** for any tool whose behaviour surface changes.
8. **No version-string bumps, no CHANGELOG edits, no packageManifest.json releaseNotes edits.** Bot-managed; pr_guard.py-protected.
9. **AGENTS.md and CLAUDE.md stay byte-identical.** If PR1a touches AGENTS.md for any reason (it shouldn't, but if a description-quality drift is spotted while editing tools, surface it), mirror to CLAUDE.md in the same commit.
10. **Surface contradictions, don't silently rewrite.** Any pre-existing content (in docs, tests, BAT scenarios) that directly contradicts a new rule gets surfaced to the maintainer in the PR description — don't paper over it.
