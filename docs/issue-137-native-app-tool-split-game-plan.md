# Issue #137 — Native-app tool split game plan

> **Canonical home** of the issue #137 (refactor native-app CRUD into app-specific tools) design + execution plan, paired with [Issue #137](https://github.com/kingpanther13/Hubitat-local-MCP-server/issues/137). This file is the authoritative version.

## Problem

`hub_update_native_app` was one tool for "any classic SmartApp," but its input schema is ~95% Rule Machine-specific orchestration: `addTrigger`, `addTriggers`, `addRequiredExpression`, `addAction`, `addActions`, `patches`, `replaceActions`, `removeAction`, `clearActions`, `moveAction`, `removeTrigger`, `modifyTrigger`, `walkStep`, `addLocalVariable` — each with a large description. Only `settings`, `button`, `pageName`, `stateAttribute` are generic. So the LLM saw the full RM trigger/action schema even when editing a Room Lighting or Notifier instance. The issue's goal: split the specialized RM logic out so the schema the model sees matches the app type it's editing, while keeping atomic single-call updates and a shared backend.

`hub_delete_native_app` and the reads (`hub_get_app_config`, `hub_get_rule_health`) and `hub_clone/export/import_native_app` are already type-agnostic — they carry no per-type schema. **Per-app-type specialization is only needed on the create+edit ("set") path**, because that is the only place the type-specific authoring schema lives.

## Decisions (maintainer-confirmed)

1. **Verb convention: `set` upsert + generic `delete`/`get`.** Merge create+update into one `set_<type>` tool per app type (omit `appId` → create, provide `appId` → edit; a create can bundle its authoring in one call). Keep ONE generic type-agnostic `hub_delete_native_app` and the existing generic reads for all types. Per-type tools exist only for `set`. This is the convention-correct realization of the issue's "per-app-type split"; `set` reads as a PUT-like upsert (the verb table's "author judgement applies per-tool" clause covers the incremental edit params riding along). The maintainer's shorthand in earlier comments said `create_room_lighting`, but the ratified AGENTS.md verb table makes `set_` the correct verb for wholesale upsert.
2. **Noun: `rule`, not `rm_rule`.** AGENTS.md says "drop redundant qualifiers — `rule` is preferred over `rm_rule` where context disambiguates," and the existing RM runtime tools already use bare `rule` (`hub_call_rule`, `hub_set_rule_paused`, `hub_get_rule_health`). the legacy custom engine's tools all carry the `custom` qualifier (`hub_create_custom_rule` / `hub_update_custom_rule`), so bare `hub_set_rule` is unambiguous. → **`hub_set_rule`**.
3. **Scope of THIS PR:** the RM-vs-generic split + the generic fallback + formalizing the registry pattern so future per-type tools slot in. Room Lighting / Button Controller / Basic Rule / Visual Rule get their own `set_<type>` tools in their own later issues (#185 etc.).
4. **Gateway layout:** separate the two gateways, keep the small RM runtime tools cross-listed in both. The FAT RM authoring schema lives in `hub_manage_rule_machine`; the generic gateway becomes genuinely generic.
5. **Hard rename, no aliases** (repo policy). `hub_create_native_app` / `hub_update_native_app` are removed, not aliased.

## Target tool surface

| Tool | Schema | Behavior | Gateway |
|---|---|---|---|
| **`hub_set_rule`** (NEW) | FAT (all RM authoring params) | Upsert an RM rule. Omit `appId` → create (`name`; optionally bundle `addTriggers`/`addActions`); provide `appId` → edit | `hub_manage_rule_machine` |
| **`hub_set_native_app`** (NEW) | LEAN (`appId`, `appType`, `name`, `settings`, `button`, `pageName`, `stateAttribute`, `confirm`) | Generic upsert for any NON-RM classic app. No trigger/action sugar | `hub_manage_native_rules_and_apps` |
| `hub_delete_native_app` | unchanged (type-agnostic) | delete (soft/force) | both (cross-listed) |
| `hub_get_rule_health`, `hub_get_app_config`, `hub_clone/export/import_native_app` | unchanged | reads / round-trip | unchanged |

The old `hub_create_native_app` + `hub_update_native_app` are removed.

## Architecture — physically split the schema, share the backend

The bloat is the *input schema the model sees*, not the backend. So:

- **`toolSetRule(args)`** and **`toolSetNativeApp(args)`** are thin create-or-edit dispatchers:
  - no `appId` → `_createNativeAppShell(args)` (createchild + name-set; `toolSetRule` additionally bundles RM `triggers`/`actions`).
  - `appId` present → `_applyNativeAppEdit(args)` (the shared edit engine: discover/guide meta-calls, the full RM wizard ladder, and the settings/button fall-through that the generic tool reaches).
- **`_createNativeAppShell`** (was `toolCreateNativeApp`) and **`_applyNativeAppEdit`** (was `toolUpdateNativeApp`) keep their intricate, well-tested bodies. The createchild + name-set is type-agnostic; the trigger/action bundling and the RM wizard ladder are RM-only and only reached when `hub_set_rule` passes those params (the lean `hub_set_native_app` schema can't carry them).
- Shared private helpers are untouched: the settings writer `_rmUpdateAppSettings`, `_rmClickAppButton`, `_rmBackupRuleSnapshot`, etc. are type-agnostic; `_rmAddTrigger`/`_rmAddAction`/`_rmWalkStep`/… are RM-only.
- **Registry:** `_appTypeRegistry()` (appType → `{namespace, appName, parentTypeName}`) is the formalized seam. A future per-type `set_<type>` tool registers its createchild params there and adds its own authoring schema, with zero churn to the generic path.

## Gateways

- `hub_manage_rule_machine` → RM runtime (kept) **+ `hub_set_rule`** + cross-listed `hub_delete_native_app`. The FAT RM schema now lives here; this is the routing target for "create a rule machine rule."
- `hub_manage_native_rules_and_apps` → **`hub_set_native_app`** (lean) + `hub_delete_native_app` + clone/export/import + the small RM runtime tools (kept). The FAT RM authoring schema is gone from here — the literal fix for "the LLM won't see RM trigger specs when editing a Room Lighting instance."

## Implementation summary (this PR)

- Tool defs: deleted the `hub_create_native_app` + `hub_update_native_app` definition blocks; added `hub_set_rule` (FAT, transformed in place from the update def to preserve the tuned prose) and `hub_set_native_app` (LEAN). Both carry an `outputSchema`; annotations remain centrally injected (both stay out of `getReadOnlyToolNames()` → auto write+destructive).
- Dispatch (`executeTool`): `hub_set_rule` → `toolSetRule`, `hub_set_native_app` → `toolSetNativeApp`; delete unchanged.
- Gateways, permission-gating list, `isGatedMetaCall` (guide/discover bypass), and ~90 cross-references (descriptions, error/repairHint strings, the embedded TOOL_GUIDE bodies) repointed: RM-authoring → `hub_set_rule`; generic → `hub_set_native_app`; CRUD-family sentences name all three with corrected gateway attributions.
- Guide-section keys renamed: `update_native_app_reference` → `set_rule_reference`; `create_native_app_reference` → `set_rule_create_reference` (and the matching TOOL_GUIDE.md headings + `tests/sandbox_lint.py` heading-hint/anchor map).
- Docs swept: README, SKILL, TOOL_GUIDE, AGENTS (+ byte-identical CLAUDE mirror), `agent-skill/hubitat-mcp/tool-reference.md`, BAT scenario docs.

## Testing

- Renamed unit/integration assertions across `ToolRmNativeCrudSpec`, `UpdateNativeAppSchemaTrimSpec`, `HandleMcpRequestDispatchSpec`, `McpToolAnnotationsSpec`, `RuleNotFoundRedirectSpec`, `GatewayToggleSpec` to the new names/gateways/section keys.
- New coverage per the CONTRIBUTING "direct-call + dispatch-envelope test per new tool" rule: `hub_set_rule` create-when-`appId`-omitted (with bundled authoring) and `hub_set_native_app` create + edit + a schema-leanness pin (its inputSchema carries none of the RM authoring params).
- `python tests/sandbox_lint.py` and `./gradlew test` (JDK 11 toolchain) green before push.

## Out of scope (named, not deferred problems)

These are genuine separate efforts the maintainer already tracks, not problems discovered here:

- Future per-app-type `set` tools: `hub_set_room_lighting`, `hub_set_button_controller`, `hub_set_basic_rule`, `hub_set_visual_rule`. This PR formalizes the registry so they slot in.
- **#185** — register `basic_rule` + `button_rule_5_1` in `_appTypeRegistry()`.
- **#215** — first-class Visual Rule Builder 2.0 tools (different JSON-blob backend).
- **#209** — splitting the monolith into `#include` libraries (orthogonal).
