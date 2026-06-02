# Issue #105 — PR1 game plan (revised)

> **Canonical home** of the PR1 (tool audit) game plan, paired with [Issue #105](https://github.com/kingpanther13/Hubitat-local-MCP-server/issues/105). This is the authoritative version.
>
> **Revised 2026-06-01** after PR #208 was reverted (#216). The original plan assumed #208 would land first and PR1 would rename its output; that premise is dead. This revision re-baselines on **current master (103 tools)** and folds #208's consolidation work *into* PR1, redone with AGENTS.md-conformant names.
>
> **Status (2026-06-02):** **PR1A is complete** — shipped as PR #224 (103 → 89 tools, universal `hub_` rename + 11 merges; full Spock suite + sandbox_lint green). The two follow-on PRs that finish the #105 PR1 audit — **PR1B (gateways)** and **PR1C (descriptions + schema)** — are detailed in *Remaining PR1 work* at the bottom.

## What changed since the first draft

- **#208 merged then was reverted (#216).** It collided with the concurrent #203 and predated the AGENTS.md "Tool design rules"; revert-and-redo-clean beat untangling it. Its *consolidations were sound* — they're re-folded here, not discarded.
- **PR #213 landed after the revert**, adding `importUrl` (all six install/update tools), `installAsUserApp` (install-only), and `triggerUpdated` (update-only, apps). These tools stay separate (see Approach) so the divergence is a non-issue.
- **Baseline is 103 tools** (23 core flat + 80 across 13 gateways), zero `hub_` prefix.
- **Maintainer constraints for PR1A (2026-06-01):**
  1. **Consolidations only — nothing is split.** Every change is a rename or a merge. (`get_set_hub_metrics` has a compound verb but stays ONE tool — renamed `hub_get_metrics`, keeping its optional `recordSnapshot`/`trendPoints` trend-history params; the CSV append is a benign opt-in side effect, not a hub-state write.)
  2. **Verb-noun, verb-first after `hub_`.** The verb leads. Qualifiers fold into the noun: legacy custom-rule tools are `hub_get_custom_rule`, NOT `hub_custom_get_rule`.
  3. `manage_` is reserved for gateways. No new flat `manage_`-style action tools (so connectors and debug-log writes stay as separate single-verb tools, not merged under a `manage_` action dispatcher).
  4. **Consolidations land before renames** within PR1A. PR1A is a single consolidation + rename pass (sized like #208) — it is *not* internally sub-divided into a/b/c. The gateway work and the description/schema work the original #105 plan bundled under PR1 ship as their own follow-on PRs **PR1B** and **PR1C** (see *Remaining PR1 work* at the bottom).
  5. **Don't break gateways.** When merged tools replace their sources inside a gateway, update the gateway's `tools[]`/`summaries`/`searchHints`. A tool MAY live in more than one gateway.

## Approach

PR1A, two ordered phases in one branch (`issue-105-pr1a-consolidate-rename`, cut from current master):

1. **Phase 1 — consolidations** (the 11 merges below). Each merged tool lands at its final `hub_` name. Risky structural work first, with direct-call + dispatch-envelope tests per change.
2. **Phase 2 — renames.** Every surviving non-merged tool + all 13 gateways get the `hub_` verb-noun name. Mostly mechanical.

Net: **103 → 89 tools** (11 merges remove 14). No deprecation aliases — cached MCP clients refresh on update.

## Consolidation map (merge-only)

Grounded in a per-handler audit of `hubitat-mcp-server.groovy`. "Mechanism" = how one schema carries both behaviours without splitting.

### Read merges

| Sources | Target | Mechanism |
|---|---|---|
| `get_attribute` + `poll_until_attribute` | `hub_get_device_attribute` | optional `expectedValue`/`expectedValues` (+`timeoutMs`/`pollIntervalMs`) flips one-shot read → block-poll-until-match; poll kept as internal helper |
| `custom_list_rules` + `custom_get_rule` + `custom_get_rule_diagnostics` | `hub_get_custom_rule` | `ruleId` omitted → list; present → single rule; `detailed=true` adds diagnostics. Also referenced from `hub_manage_diagnostics` (multi-gateway) so the diagnostic path survives |
| `get_device_events` + `get_device_history` | `hub_list_device_events` | optional `hoursBack` (omit → recent-N `events`, present → `eventsSince` window); omit `deviceId` → location/mode/HSM events |
| `get_app_source` + `get_driver_source` + `get_library_source` | `hub_get_source` | `type=app\|driver\|library`, unified `id`; shared `toolGetItemSource` already backs app+driver. Replaces the three inside `hub_manage_code_read` |
| `get_debug_logs` + `get_logging_status` | `hub_get_debug_logs` | `mode=logs\|status` (default logs); both read the same `state.debugLogs` |
| `list_hpm_packages` + `get_hpm_drift` | `hub_list_hpm_packages` | opt-in `includeDrift` (default false); both share all three `_hpm*` helpers + gate |
| `get_zwave_details` + `get_zigbee_details` | `hub_get_radio_details` | `radio=zwave\|zigbee` (omit → both); near-identical handlers, trivial `_getRadioDetails(type)` |
| `list_virtual_devices` | `hub_list_devices` | fold as `filter=virtual` on `list_devices` (the #207 fold) |

### Write merges

| Sources | Target | Mechanism |
|---|---|---|
| `pause_rm_rule` + `resume_rm_rule` | `hub_set_rule_paused` | `value:boolean` via shared `sendRmAction`; canonical verb-pair (AGENTS.md guideline 1) |
| `delete_app` + `delete_driver` + `delete_library` | `hub_delete_item` | `type=app\|driver\|library`; shared backup-delete-verify; per-type `restoreHint` preserved. Replaces the three inside `hub_manage_code_write` |
| `delete_captured_state` + `clear_captured_states` | `hub_delete_captured_state` | optional `stateId` (omit = delete all, per verb table) |

### Resolved decisions

- **D1 — metrics:** keep merged as ONE tool → `hub_get_metrics` (single `get` verb; retains `recordSnapshot`/`trendPoints`). The compound `get_set` name was the only problem; not split, not folded into `hub_get_info`.
- **D2 — debug-log writes:** rename-only → `hub_delete_debug_logs` + `hub_set_log_level` (merging would need a `manage_`-style tool, reserved for gateways).
- **D3 — connectors:** stay separate → `hub_create_connector` + `hub_delete_connector` (same reason as D2).
- **D4 — install/update:** stay separate (no `hub_set_app` upsert) → `install_*`→`hub_create_*`, `update_*_code`→`hub_update_*`. (Mooted the further `hub_set_item` super-merge.)
- **D5 — `hub_manage_mcp`:** keep the gateway (room for roadmapped Developer-Mode tools).

## Rename map (Phase 2 — surviving non-merged tools)

| Current | New | | Current | New |
|---|---|---|---|---|
| list_devices | hub_list_devices | | install_app | hub_create_app |
| get_device | hub_get_device | | update_app_code | hub_update_app |
| send_command | hub_call_device_command | | install_driver | hub_create_driver |
| manage_virtual_device | hub_manage_virtual_device | | update_driver_code | hub_update_driver |
| update_device | hub_update_device | | install_library | hub_create_library |
| delete_device | hub_delete_device | | update_library_code | hub_update_library |
| get_device_in_use_by | hub_list_device_dependents | | list_hub_apps | hub_list_apps |
| custom_create_rule | hub_create_custom_rule | | list_hub_drivers | hub_list_drivers |
| custom_update_rule | hub_update_custom_rule | | list_item_backups | hub_list_backups |
| custom_delete_rule | hub_delete_custom_rule | | get_item_backup | hub_get_backup |
| custom_test_rule | hub_test_custom_rule | | restore_item_backup | hub_restore_backup |
| custom_export_rule | hub_export_custom_rule | | get_hub_info | hub_get_info |
| custom_import_rule | hub_import_custom_rule | | get_modes | hub_list_modes |
| custom_clone_rule | hub_clone_custom_rule | | set_mode | hub_set_mode |
| list_rm_rules | hub_list_rules | | get_hsm_status | hub_get_hsm_status |
| run_rm_rule | hub_call_rule | | set_hsm | hub_set_hsm |
| set_rm_rule_boolean | hub_set_rule_private_boolean | | check_for_update | hub_get_update_status |
| create_native_app | hub_create_native_app | | create_hub_backup | hub_create_backup |
| update_native_app | hub_update_native_app | | generate_bug_report | hub_report_issue |
| delete_native_app | hub_delete_native_app | | reboot_hub | hub_reboot |
| clone_native_app | hub_clone_native_app | | shutdown_hub | hub_shutdown |
| export_native_app | hub_export_native_app | | get_memory_history | hub_get_memory_history |
| import_native_app | hub_import_native_app | | force_garbage_collection | hub_call_gc |
| check_rule_health | hub_get_rule_health | | device_health_check | hub_get_device_health |
| list_variables | hub_list_variables | | zwave_repair | hub_call_zwave_repair |
| get_variable | hub_get_variable | | list_captured_states | hub_list_captured_states |
| set_variable | hub_set_variable | | get_hub_logs | hub_get_logs |
| create_variable | hub_create_variable | | get_performance_stats | hub_get_performance_stats |
| delete_variable | hub_delete_variable | | get_hub_jobs | hub_get_jobs |
| create_connector | hub_create_connector | | clear_debug_logs | hub_delete_debug_logs |
| remove_connector | hub_delete_connector | | set_log_level | hub_set_log_level |
| get_variable_history | hub_list_variable_changes | | list_files | hub_list_files |
| list_rooms | hub_list_rooms | | read_file | hub_read_file |
| get_room | hub_get_room | | write_file | hub_write_file |
| create_room | hub_create_room | | delete_file | hub_delete_file |
| delete_room | hub_delete_room | | update_mcp_settings | hub_update_mcp_settings |
| rename_room | hub_update_room | | get_tool_guide | hub_get_tool_guide |
| list_installed_apps | hub_list_installed_apps | | search_tools | hub_search_tools |
| get_app_config | hub_get_app_config | | list_app_pages | hub_list_app_pages |

### Gateway renames (all 13)

| Current | New | | Current | New |
|---|---|---|---|---|
| manage_rules_admin | hub_manage_rules | | manage_diagnostics | hub_manage_diagnostics |
| manage_hub_variables | hub_manage_variables | | manage_files | hub_manage_files |
| manage_rooms | hub_manage_rooms | | manage_installed_apps | hub_manage_installed_apps |
| manage_destructive_hub_ops | hub_manage_destructive_ops | | manage_hpm | hub_manage_hpm |
| manage_apps_drivers | hub_manage_code_read | | manage_native_rules_and_apps | hub_manage_native_rules |
| manage_app_driver_code | hub_manage_code_write | | manage_mcp_self | hub_manage_mcp |
| manage_logs | hub_manage_logs | | | |

## Implementation gotchas (from the handler audit — wire these into every commit)

1. **`getReadOnlyToolNames()`** lists every read tool by current name — must update in lockstep or `readOnlyHint` silently flips to write+destructive. The "every leaf classified" + readonly-mode spec tests catch misses. (`get_set_hub_metrics`→`hub_get_metrics` keeps its current classification.)
2. **Flat-mode hard guard** (`getToolDefinitions()` ~L1469–1478) literally asserts a tool named `search_tools` exists and throws if absent. Renaming → `hub_search_tools` must update that literal + guard string + comments.
3. **Description cross-references** hard-cite sibling tools by current name — rewrite in the same PR or LLM chaining points at dead names. Known cites: `get_app_config`→list_installed_apps/list_rm_rules/custom_list_rules/list_app_pages; `get_hpm_drift`→update_app_code; `manage_virtual_device` delete-branch→list_virtual_devices (now `hub_list_devices(filter=virtual)`); native-app delete/update→restore/list_item_backups; `get_tool_guide` is cited inline by ~15 tools.
4. **Gateway `tools[]`/`summaries`/`searchHints` + multi-gateway membership** update with renames. `_paginateList(..., 'custom_list_rules')` hardcodes the old name as a cursor namespace — update or break in-flight cursors.
5. **Preserve gate asymmetry through merges.** RMUtils runtime tools gate on `requireBuiltinApp` only; native-app CRUD adds `requireHubAdminWrite`+confirm+backup. `hub_delete_captured_state` and debug-log reads have no gate. No merge may silently widen/narrow a gate.
6. **`hub_delete_item`/`hub_restore_backup` per-type branches** diverge (library restoreHint → `hub_create_library`; `restore` rejects `type=library`, multi-dispatches `type=rm-rule`) — preserve.
7. **`tools/list` 124–128KB hub cap.** Keep merged-tool entries lean (~1.5KB) so `useGateways=false` `tools/list` doesn't tip past the cap → `-32603`.
8. **sandbox_lint canonical tool counts** (`tests/sandbox_lint.py` `check_tool_counts`) + e2e `tools/list` count assertion (36 → new core/gateway split) must update to the final surface.

## Hard rules for PR1A implementation

1. **Merge decisions above are pre-approved.** If a merge proves infeasible at the code level, STOP and report — don't silently leave tools separate, and don't split anything beyond the one metrics tool.
2. **Read AGENTS.md § "Tool design rules" before starting.** It is the authoring rule set; this doc is its applied output.
3. **All four annotation hints stay** on every renamed/merged tool.
4. **`git -C` / absolute paths**, never `cd && git`.
5. **No deprecation aliases.** Hard rename.
6. **Tests track every rename/merge** — direct-call + dispatch-envelope, same commit. `tests/BAT-v2.md` updates for behaviour-surface changes.
7. **No version bumps / CHANGELOG / manifest releaseNotes edits** — bot-managed, `pr_guard.py`-protected.
8. **AGENTS.md and CLAUDE.md stay byte-identical** if either is touched.
9. **Surface contradictions, don't paper over them** — note in the PR description.

## Remaining PR1 work — PR1B (gateways) and PR1C (descriptions + schema)

PR1A (above) shipped as **PR #224**. Two follow-on PRs finish the #105 PR1 audit. They were deliberately held out of PR1A to keep it #208-sized and low-risk: PR1A touched every tool's *name*, so layering gateway reorganization and per-tool schema work on top would have ballooned the review surface. Each lands on the post-PR1A (89-tool, all-`hub_`) baseline.

### PR1B — gateway audit + read/write organization

The gateways themselves, now that every tool is `hub_`-named. **No tool merges/splits** (those are done in PR1A) — this is gateway organization + naming only.

- **Read/write split audit** per AGENTS.md ("gateways SHOULD be read-only OR write-only where the domain permits"). Classify each of the 13 gateways and either split a mixed gateway into read/write siblings or document why it stays mixed. Status today:
  - *Already pure:* `hub_manage_code_read` (read), `hub_manage_code_write` (write), `hub_manage_destructive_ops` (write), `hub_manage_installed_apps` (read), `hub_manage_hpm` (read), `hub_manage_mcp` (write), `hub_manage_files` — review.
  - *Mixed → review for split:* `hub_manage_variables` (list/get/changes reads + set/create/delete + connector writes), `hub_manage_rooms` (list/get + create/delete/update), `hub_manage_diagnostics` (metrics/memory/radio/health reads + `hub_call_gc`/`hub_call_zwave_repair`/`hub_delete_captured_state` writes), `hub_manage_native_rules` (list/export/`hub_get_rule_health` reads + `hub_call_rule`/`hub_set_rule_*`/CRUD writes), `hub_manage_logs` (reads + `hub_delete_debug_logs`/`hub_set_log_level`), `hub_manage_rules` (test/export reads + delete/import/clone writes).
- **Gateway naming rework.** Settle the convention for read/write gateway pairs — the current `hub_manage_code_read` / `hub_manage_code_write` bake read/write into the noun, which is the only place a gateway does that. Decide the final pattern and apply it consistently if more pairs are created by the split audit.
- **Backup tool placement cross-refs.** `hub_list_backups`/`hub_get_backup` (read gateway) and `hub_restore_backup` (write gateway) are split across the read/write code gateways. PR1A patched the stale prose; PR1B should decide whether that split is the intended end state or whether the backup tools should regroup.
- **Single-tool gateways.** `hub_manage_hpm` (1 tool) and `hub_manage_mcp` (1 tool) — decide keep-as-gateway vs promote-to-flat now that the surface is settled.
- **Flat-only `manage_`** (clarified in AGENTS.md this round) — confirm `hub_manage_virtual_device` is the only flat `manage_` tool and is justified.

### PR1C — description quality + `outputSchema` audit

Per-tool description and schema quality, per AGENTS.md § "Tool descriptions" + "Schema design".

- **`outputSchema` on every structured-return tool.** Today **zero** tools have one; AGENTS.md requires it (servers MUST conform to a published `outputSchema`; clients SHOULD validate). Add to: all `hub_get_*` object reads, all `hub_list_*` (`{items, nextCursor, …}` shape), and `hub_create_*`/`hub_update_*`/`hub_set_*` writes that return the modified entity. Mind the multi-mode merged tools (`hub_get_custom_rule`, `hub_get_debug_logs`, `hub_list_hpm_packages`, `hub_get_radio_details`, `hub_list_device_events`) — their output shape varies by mode, so the schema needs `oneOf`/per-mode handling or a documented superset.
- **Anthropic-quality descriptions** per-tool: concise first line, usage/when-to-use body, semantic IDs over opaque UUIDs (expose both name + id where chaining needs it), recovery-oriented error text. Prioritize the high-traffic reads and the merged multi-mode tools.
- **Param descriptions** unambiguous (`device_id` not `id`), `enum` where a fixed set exists, `required` present only when there are required params.
- **Watch the 124–128KB `tools/list` cap** — `outputSchema` adds bytes and flat-mode is the size-constrained path. Use `[[FLAT_TRIM]]` markers for the heavy parts so the constrained surface stays under the cap.

### Beyond PR1 (context only — not part of PR1)

- **PR2** — everything *outside* the tools: server/backend, gateway dispatch internals, permission/gate model, transport.
- **PR3** — AGENTS.md / styleguide / doc updates so future tools conform without another audit.
