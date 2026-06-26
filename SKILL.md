---
name: hubitat-mcp-server
description: Guide for developing and maintaining the Hubitat MCP Rule Server — a Groovy-based MCP server running natively on Hubitat Elevation hubs, exposing 115 tools (36 on tools/list via category gateway proxy) for device control, virtual device management, room management, rule automation, hub admin, file management, app/driver/library management, installed-app visibility, Rule Machine interoperability, native rule CRUD, Easy Dashboard CRUD, HPM package state introspection, and Developer Mode self-administration.
license: MIT
---

# Hubitat MCP Rule Server — Development Skill

## Project Overview

This project is a **Model Context Protocol (MCP) server** implemented as a native **Hubitat Elevation** Groovy app. It consists of two files:

- **`hubitat-mcp-server.groovy`** — Parent app (MCP server, tool definitions, hub admin tools, helpers)
- **`hubitat-mcp-rule.groovy`** — Child app (individual automation rules with triggers, conditions, actions)

The server exposes an OAuth-secured HTTP endpoint that speaks JSON-RPC 2.0 per the MCP protocol specification. AI assistants connect to this endpoint and invoke tools to control devices, manage automation rules, query hub state, and administer the hub.

The Hubitat-runtime code has no external dependencies -- everything runs inside the Hubitat Groovy sandbox. Development tooling (under `tests/`) does include test frameworks: Spock unit tests via Gradle, a sandbox-pattern lint, an end-to-end live-hub test, and the wizard-state regression probe (`tests/wizard_probe.py`). See README.md "Testing" for details.

**Documentation files:**
- `README.md` — User-facing documentation
- `SKILL.md` — Developer reference (this file)
- `TOOL_GUIDE.md` — Human-readable tool reference (same content available to AI via `hub_get_tool_guide` MCP tool)

## Architecture

```
┌─────────────────────────────────────────────────┐
│  Hubitat Hub (Groovy Sandbox)                   │
│                                                 │
│  ┌───────────────────────────────────────────┐  │
│  │  MCP Rule Server (parent app)             │  │
│  │  - OAuth endpoint: /apps/api/<id>/mcp     │  │
│  │  - JSON-RPC 2.0 handler                   │  │
│  │  - 115 tools (36 on tools/list + gateways)│  │
│  │  - Device access gate (selectedDevices)   │  │
│  │  - Hub Admin tools (internal API calls)   │  │
│  │  - Hub Security cookie auth               │  │
│  │  - Virtual device mgmt (child devices)    │  │
│  │  - Debug logging system                   │  │
│  │  - Version update checker                 │  │
│  ├───────────────────────────────────────────┤  │
│  │  MCP Rule (child app, one per rule)       │  │
│  │  - Trigger subscriptions & evaluation     │  │
│  │  - Condition evaluation                   │  │
│  │  - Action execution                       │  │
│  │  - Execution loop guard (auto-disable)    │  │
│  │  - Isolated settings & state per rule     │  │
│  └───────────────────────────────────────────┘  │
│                                                 │
│  Hub Internal API (http://127.0.0.1:8080)       │
│  - /hub/advanced/* (memory, temp, db size)      │
│  - /hub2/* (apps, drivers, zwave, zigbee)       │
│  - /app/ajax/*, /driver/ajax/* (code mgmt)      │
│  - /hub/backup, /hub/reboot, /hub/shutdown      │
└─────────────────────────────────────────────────┘
```

## Key Conventions

### File Structure (Section Comments)

The server file is organized with prominent section delimiters:
```groovy
// ==================== APP LIFECYCLE ====================
// ==================== MCP REQUEST HANDLERS ====================
// ==================== DEVICE TOOLS ====================
// ==================== RULE TOOLS (Child App Based) ====================
// ==================== RULE EXPORT/IMPORT/CLONE TOOLS ====================
// ==================== EXPORT/IMPORT HELPERS ====================
// ==================== SYSTEM TOOLS ====================
// ==================== CAPTURED STATE TOOLS ====================
// ==================== VALIDATION FUNCTIONS ====================
// ==================== HELPER FUNCTIONS ====================
// ==================== HUB SECURITY & INTERNAL API HELPERS ====================
// ==================== ITEM BACKUP TOOLS ====================
// ==================== FILE MANAGER TOOLS ====================
// ==================== MCP DEBUG LOGGING SYSTEM ====================
// ==================== DEBUG TOOL IMPLEMENTATIONS ====================
// ==================== HUB ADMIN READ TOOL IMPLEMENTATIONS ====================
// ==================== MONITORING TOOL IMPLEMENTATIONS ====================
// ==================== HUB ADMIN WRITE TOOL IMPLEMENTATIONS ====================
// ==================== HUB ADMIN APP/DRIVER MANAGEMENT ====================
// ==================== DEVICE ADMIN TOOL IMPLEMENTATIONS ====================
// ==================== VIRTUAL DEVICE MANAGEMENT TOOL IMPLEMENTATIONS ====================
// ==================== ROOM MANAGEMENT ====================
// ==================== VERSION UPDATE CHECK ====================
// ==================== CATEGORY GATEWAY PROXY ====================
// ==================== TOOL GUIDE ====================
```

New code should be placed in the appropriate section. New sections should follow the same `// ==== NAME ====` delimiter pattern.

### Category Gateway Proxy (v0.8.0+)

The server uses a **category gateway proxy** pattern to reduce the MCP `tools/list` from 115 items to 36. This keeps frequently-used tools immediately accessible while organizing lesser-used tools behind domain-named gateways. Gateways come in two flavors: `hub_read_<noun>` gateways whose every sub-tool is read-only, and `hub_manage_<noun>` gateways that contain at least one write (mixed read+write or write-only). A tool MAY appear in more than one gateway (multi-membership) — reads are listed in BOTH their mixed `manage_` gateway AND a pure-read `read_` gateway.

**Architecture:**
- `getGatewayConfig()` — defines 23 gateways, each with a description, tools list, and summaries map
- `getToolDefinitions()` — returns 13 core tools + 23 gateway tool definitions (client-visible)
- `getAllToolDefinitions()` — returns all 115 tool definitions (used internally by gateway catalog and `executeTool()` dispatch)
- `handleGateway(gatewayName, toolName, toolArgs)` — catalog mode (no args → full schemas) or execute mode (tool + args → dispatch)

**Gateway calling convention:**
1. AI calls `<gateway>()` with no args → gets full tool schemas (catalog mode)
2. AI calls `<gateway>(tool="tool_name", args={...})` → executes the proxied tool

**23 gateways (8 read + 15 manage):**

Read gateways (`hub_read_*`, every sub-tool read-only):
| Gateway | Tools | Domain |
|---------|-------|--------|
| `hub_read_apps_code` | 11 | List apps/drivers/libraries/bundles, get source, backups (list/get), device-in-use-by lookup, app config inspection, page-name directory, HPM package state (read-only) |
| `hub_read_devices` | 4 | List/get devices, device attributes, device events (read-only) |
| `hub_read_diagnostics` | 9 | Logs, performance stats, hub jobs, debug logs, metrics, memory history, device health, radio details (zwave/zigbee), captured states (read-only) |
| `hub_read_files` | 2 | File Manager list + read (read-only) |
| `hub_read_rooms` | 2 | Room list + get (read-only) |
| `hub_read_rules` | 6 | Custom-engine rule get/test, native rule list, rule health, rule local variables, Visual Rules Builder rule list/read (read-only) |
| `hub_read_variables` | 3 | Hub connector and rule engine variable list/get + change history (read-only) |
| `hub_read_dashboards` | 2 | Easy Dashboard list + get config by id (read-only) |

Manage gateways (`hub_manage_*`, contain at least one write):
| Gateway | Tools | Domain |
|---------|-------|--------|
| `hub_manage_custom_rules` | 8 | Custom-engine rule get/create/update/delete/test/export/import/clone |
| `hub_manage_variables` | 8 | Hub connector and rule engine variables (CRUD + connector + history) |
| `hub_manage_rooms` | 5 | Room CRUD |
| `hub_manage_destructive_ops` | 4 | Hub reboot, shutdown, device deletion, destructive radio ops (reset/wipe + radio firmware via `hub_call_destructive_radio`) (write) |
| `hub_manage_code` | 11 | Install/update apps+drivers+libraries, install/delete/export HPM-style bundles, delete item (app/driver/library), restore backup (write) |
| `hub_manage_devices` | 7 | Device command/swap/update (writes) + list/get devices, attributes, events (reads) |
| `hub_manage_logs` | 6 | Logs, performance stats, hub jobs, debug tools (read + clear/set-level write) |
| `hub_manage_diagnostics` | 7 | Diagnostics, state capture/delete, radio details (zwave/zigbee), memory history, metrics, GC |
| `hub_manage_files` | 4 | File Manager CRUD |
| `hub_manage_radio` | 6 | Z-Wave/Zigbee/Matter radio admin — radio details, configure (`hub_set_zwave`/`hub_set_zigbee`), and radio ops incl. Z-Wave network repair (`hub_call_zwave`/`hub_call_zigbee`/`hub_call_matter`); destructive radio ops are in `hub_manage_destructive_ops` |
| `hub_manage_native_rules_and_apps` | 11 | Rule Machine RMUtils interop (list/run/set-paused/boolean) + generic admin-layer CRUD on any classic SmartApp (`hub_set_native_app` create/edit, `hub_set_app_disabled`, delete, clone, export, import + `hub_get_rule_health` — Room Lighting, Button Controllers, Basic Rules, Notifier, etc.). RM rule authoring (`hub_set_rule`) lives in `hub_manage_rule_machine`. |
| `hub_manage_rule_machine` | 11 | Rule Machine authoring (`hub_set_rule` create/edit) + RMUtils interop (list/run/set-paused/boolean) + rule health + local-variable list (`hub_list_rule_local_variables`) + delete (`hub_delete_native_app`) + Visual Rules Builder CRUD (`hub_get_visual_rule` / `hub_set_visual_rule` / `hub_delete_visual_rule`) |
| `hub_manage_mcp` | 1 | Developer Mode self-administration — update MCP rule app's own settings, including the device-access scope `selectedDevices` (allowlist-gated; requires `enableDeveloperMode`) |
| `hub_manage_dashboard` | 6 | Easy Dashboard CRUD — list/get + create/update (wholesale replace)/delete/clone touch-friendly device dashboards (write) |

`hub_set_rule` `clearActions` / `replaceActions` shortcuts: the trashActs delete commits synchronously via a full selectActions page-form submit (the complete form-action envelope plus serialized page state, mirroring the native UI), which runs RM's `trashActs` submitOnChange handler in-band -- the actions are gone by the time the call returns. A thin defensive verify-retry remains: on the rare residual where verification still sees the actions (stuck `state.editAct`, or an uncommon firmware commit lag) the tool returns `partial:true, asyncCommitLikely:true` with a `stage` discriminator and a `safeRecovery` block. Verify via `hub_get_app_config` rather than rolling back if that fires. See TOOL_GUIDE.md for the full response shape.

**Flat (top-level) tools (12):** `hub_manage_virtual_device` (action enum: "create", "delete"), `hub_get_info` (comprehensive: hardware, health — memory, temp, DB size — and MCP stats always available; PII/location data — name, IP, timezone, coordinates, zip — included whenever the Read master is ON, which is the default, and excluded only when Read is explicitly OFF), `hub_list_modes`, `hub_manage_mode`, `hub_set_mode_manager`, `hub_get_hsm_status`, `hub_set_hsm`, `hub_create_backup`, `hub_update_firmware` (install the hub's pending platform/firmware update; app/firmware version reads fold into `hub_get_info` via `includeAppUpdate`), `hub_report_issue`, `hub_get_tool_guide`, `hub_search_tools` (BM25 natural language search across all tools).

Device tools (`hub_list_devices` with `filter='virtual'` to list only MCP-managed virtual devices, `hub_get_device`, `hub_get_device_attribute` — pass `expectedValue`/`expectedValues` to block-poll the attribute until it matches or times out, optionally refined by `comparator` (eq/ne/gt/gte/lt/lte/between) and `stableForMs` (debounce; must be < timeoutMs) -- a numeric comparator on an attribute that reports a non-numeric value the whole window times out with `nonNumericAttribute: true`; for MULTI-DEVICE convergence pass `deviceIds` (a list, mutually exclusive with `deviceId`, max 20) with `mode` (`all` = converge when every device matches, default; `any` = on the first to match), returning a compact per-device array plus `convergedCount`; `timeoutMs` in MILLISECONDS, default 5000ms = 5 seconds, max 60000ms; polling BLOCKS the MCP request, use sparingly, prefer event-driven flows — `hub_call_device_command`, `hub_update_device`, and `hub_list_device_events` with `hoursBack` for a relative window up to 7 days (or `since` for an absolute bookmark -- events after an exact timestamp; round-trip a returned `date`) of device or location event history; omit `deviceId` for mode/HSM/hub-variable/sendLocationEvent location events) live in the `hub_read_devices` / `hub_manage_devices` gateways; `hub_call_device_swap` (replace a device across ALL apps/rules that reference it, via the hub's built-in Swap Device tool) is a write and lives only in `hub_manage_devices`. The MCP custom rule engine tools (`hub_get_custom_rule` — omit `ruleId` to list all custom-engine rules, `detailed=true` for comprehensive diagnostics on one rule — `hub_create_custom_rule`, `hub_update_custom_rule`) live in the `hub_read_rules` / `hub_manage_custom_rules` gateways; this engine is distinct from native Rule Machine, whose authoring lives in the `hub_manage_rule_machine` gateway via `hub_set_rule`, while the other classic apps (Room Lighting, Button Controllers, Basic Rules, etc.) plus delete and health are in the `hub_manage_native_rules_and_apps` gateway via `hub_set_native_app` / `hub_delete_native_app` / `hub_get_rule_health`. Visual Rules Builder rules (Vue-JSON apps; VRB is the primary engine for new automations) are read/written via `hub_get_visual_rule` / `hub_set_visual_rule` / `hub_delete_visual_rule` in the `hub_manage_rule_machine` gateway (the read is also in `hub_read_rules`).

**Safety gates are preserved:** The Read/Write master gate runs centrally at the top of `executeTool()` (the dispatch chokepoint), and the destructive-tier `confirm`+backup check (`requireDestructiveConfirm(args.confirm)`) runs in the handlers of the destructive write tools. A gateway name routes back through `executeTool()`, which re-applies the master gate per sub-tool before the handler runs. No safety check is bypassed.

### Adding a New Tool (Checklist)

Every new tool requires changes in exactly three places:

**Design rules first** (see `AGENTS.md` § Tool design rules for full rationale and citations):

0a. **Tool name follows the design rules** — `hub_` service prefix, verb from the allowed vocabulary, `manage_` only on gateways (or the narrow flat-multi-action exception), verb-noun order.
0b. **All four annotation hints set explicitly** — `readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint`. Defaults for unannotated tools are the cautious posture; explicit is safer than implicit.
0c. **Parameter names are unambiguous** — `device_id` not `id`, `user_id` not `user`. Name parameters after what they semantically are.

**Then the three placement changes:**

1. **Tool definition** in `getAllToolDefinitions()` — a map with `name`, `description`, and `inputSchema` (JSON Schema format)
2. **Case statement** in `executeTool()` — dispatches `toolName` to the implementation method
3. **Implementation method** — prefixed with `tool` (e.g., `toolMyNewTool`)

**Gateway consideration (v0.8.0+):** Decide whether the new tool should be:
- **Core** (on `tools/list` directly) — for frequently-used tools that AI needs instant access to
- **Behind a gateway** — for lesser-used or admin tools. Add the tool name to the appropriate gateway's `tools` list and `summaries` map in `getGatewayConfig()`. The tool definition stays in `getAllToolDefinitions()` but gets filtered out of the client-visible `getToolDefinitions()`.

Additionally, update tool count in:
4. **`packageManifest.json`** — version field + releaseNotes with updated tool count
5. **`README.md`** — "MCP Tools (N total)" header + category table + version history
6. **`SKILL.md`** — description frontmatter + architecture diagram tool count
7. **Version strings** — if this is a version bump, update ALL version references (search for the current version string)

### Tool Definition Pattern

```groovy
[
    name: "tool_name",
    description: """Concise first line describing what the tool does.

Additional guidance for the AI on when/how to use this tool.
Include performance notes, pagination guidance, or behavioral expectations.

For write tools, include safety warnings and mandatory pre-flight checklists.""",
    inputSchema: [
        type: "object",
        properties: [
            requiredParam: [type: "string", description: "What this param is"],
            optionalParam: [type: "boolean", description: "What this does", default: false],
            enumParam: [type: "string", enum: ["value1", "value2"], description: "Allowed values"]
        ],
        required: ["requiredParam"]
    ]
]
```

**Canonical example with annotations + outputSchema (new tools should follow this shape):**

```groovy
[
    name: "hub_get_room_health",
    description: """Returns a health verdict for one room.

Returns a structured map of room state plus a single `healthy` boolean roll-up so
the AI can decide whether to drill in. Use this before sending commands to devices
in the room — it surfaces stale-state warnings the device-level tools don't.""",
    inputSchema: [
        type: "object",
        properties: [
            room_id: [type: "string", description: "Room ID (string). Use hub_list_rooms to discover IDs."]
        ],
        required: ["room_id"]
    ],
    outputSchema: [
        type: "object",
        properties: [
            healthy: [type: "boolean", description: "True if every device in the room reported within the freshness window."],
            stale_devices: [type: "array", items: [type: "string"], description: "device IDs with no recent activity"],
            warnings: [type: "array", items: [type: "string"], description: "human-readable warnings, empty when healthy"]
        ],
        required: ["healthy", "stale_devices", "warnings"]
    ],
    annotations: [
        readOnlyHint: true,
        destructiveHint: false,
        idempotentHint: true,
        openWorldHint: false
    ]
]
```

Full rationale and citations: `AGENTS.md` § Tool design rules.

Rules:
- `inputSchema` root is always `type: "object"` with `properties`
- `required` array is only present when there are required params
- No-argument tools use `properties: [:]`
- Descriptions should include usage guidance for the AI (this text is what the LLM sees when deciding which tool to call)
- Write tools must have strong safety warnings in their descriptions with mandatory pre-flight checklists

### executeTool() Dispatch Pattern

```groovy
case "tool_name": return toolMyNewTool(args)
```

- Arguments are destructured from `args` inline: `return toolFoo(args.deviceId, args.offset ?: 0)`
- Some tools pass the entire `args` map: `return toolBar(args)`
- Default values use the Elvis operator `?:` inline
- Tools are grouped by category with comment headers

### Tool Implementation Pattern

```groovy
def toolMyNewTool(args) {
    // 1. Read/Write masters are enforced centrally in executeTool() by tool
    //    classification (getReadOnlyToolNames) -- no per-handler master gate needed.
    //    Destructive write tools additionally call:
    requireDestructiveConfirm(args.confirm)   // confirm=true + backup <24h (destructive tools only)

    // 2. Input validation
    if (!args.requiredParam) throw new IllegalArgumentException("requiredParam is required")

    // 3. Implementation with error handling
    try {
        // ... do work ...
        mcpLog("info", "component-name", "Description of what happened")
        return [
            success: true,
            // ... result fields ...
        ]
    } catch (Exception e) {
        mcpLog("error", "component-name", "What failed: ${e.message}")
        return [
            success: false,
            error: "Human-readable error: ${e.message}",
            note: "Actionable guidance for the user or AI"
        ]
    }
}
```

Conventions:
- **Validation errors**: Throw `IllegalArgumentException` with descriptive message (caught by `handleToolsCall` as JSON-RPC `-32602`)
- **Runtime errors**: Return `[success: false, error: ..., note: ...]` maps (don't throw, so the AI gets useful error info)
- **Device IDs**: Always compare and return as strings: `device.id.toString()`
- **Logging**: Use `mcpLog(level, component, message)` for MCP-accessible logs
- **Hub properties**: Wrap in individual try/catch with `"unavailable"` fallback (hub properties can throw depending on firmware)

### Safety Gate Pattern

Access control has a central master gate plus a destructive confirmation tier, with deny-only advanced overrides on top.

**Two universal masters — Read and Write (both default ON).** A single classification-driven gate at the top of `executeTool()` (the dispatch chokepoint) enforces them: a tool in `getReadOnlyToolNames()` is blocked when `settings.enableRead == false` ("Read tools are disabled…"); every other (write) tool is blocked when `settings.enableWrite == false` ("Write tools are disabled…"). Only an explicit `== false` blocks — null/unset is ON. Gateway *names* are skipped here (they re-enter `executeTool()` per sub-tool, which is gated on re-entry). Reads include device/hub/variable/diagnostics reads, `hub_get_tool_guide`, `hub_search_tools`, etc.; everything else is a write.

**`requireDestructiveConfirm(args.confirm)`** — runs in the handlers of the destructive/sensitive write tools, orthogonal to the masters (the Write master already gated them centrally). Two-layer check:
1. `args.confirm` must be `true` (explicit confirmation parameter)
2. `state.lastBackupTimestamp` must be within the last 24 hours

Exception: `toolCreateHubBackup` checks `confirm` directly without requiring a prior backup (it IS the backup operation).

**Advanced per-tool / per-gateway overrides (deny-only).** `settings.disabled_tools` / `settings.disabled_gateways` (set under *Advanced: Per-tool Overrides*) feed `getEffectiveDisabledTools()` → `getHiddenToolNames()`, hiding tools from `tools/list` and `hub_search_tools`, and an `executeTool()` guard rejects a cached call with a distinct error ("…is disabled in Advanced settings (Per-tool Overrides)…"). They apply below the masters (can only turn things OFF) and a disabled tool remains documented in `hub_get_tool_guide`.

**`backupItemSource(type, id)`** — Automatic item-level backup for modify/delete operations:
- Called by `hub_update_app`, `hub_update_driver`, `hub_delete_item` (type=app|driver) before making changes
- Fetches current source code and saves as a `.groovy` file in the hub's local File Manager via `uploadHubFile()`
- Metadata (type, id, version, timestamp, fileName, sourceLength) stored in `state.itemBackupManifest` keyed by `"app_<id>"` or `"driver_<id>"`
- 1-hour window: if a backup of the same item exists within the last hour, it is kept (preserves the pre-edit original across a series of edits)
- Prunes to max 20 entries; oldest file deleted via `deleteHubFile()` when limit exceeded
- No size limit — full source always stored (File Manager has ~1GB capacity)
- Not needed for install tools (nothing to lose when creating new)
- Files persist even if MCP app is uninstalled; accessible at `http://<HUB_IP>/local/<filename>`
- Requires firmware ≥2.3.4.132 for `uploadHubFile()` support

**Item Backup Tools** (3 tools — reads available under the Read master, restore under the Write master):
- `hub_list_backups` — lists all backups with metadata (type, id, version, age, size) and direct download URLs
- `hub_get_backup` — retrieves full source code from a backup via `downloadHubFile()` by key (e.g., `app_123`); returns source inline for files ≤60KB, otherwise provides download URL
- `hub_restore_backup` — reads backup via `downloadHubFile()` and pushes source back to the hub via `hub_update_app`/`hub_update_driver` (requires the Write master); removes manifest entry first so the current code gets backed up during restore; on failure, puts the manifest entry back
- Every tool response includes `howToRestore` and `manualRestore` instructions for user recovery without MCP
- All operations are fully local — no cloud involvement

**File Manager Tools** (4 tools):
- `hub_list_files` — lists all files via `/hub/fileManager/json` internal API endpoint; always available, no access gate
- `hub_read_file` — reads file via `downloadHubFile()`; returns content inline for files ≤60KB, otherwise provides download URL; always available
- `hub_write_file` — writes via `uploadHubFile()`; requires the Write master + confirm + a recent backup; automatically backs up existing file before overwriting (backup named `<original>_backup_<timestamp>.<ext>`)
- `hub_delete_file` — deletes via `deleteHubFile()`; requires the Write master + confirm + a recent backup; automatically backs up file before deletion
- File name validation: must match `^[A-Za-z0-9][A-Za-z0-9._-]*$` (no spaces, no leading period)

**Device Authorization Safety** (v0.7.2+):
- Device tools require AI to confirm before using non-exact device matches
- If user specifies an exact device name that matches, AI can use it directly
- If no exact match: AI must suggest similar devices and **ask user to confirm** before using any of them
- When a tool fails (e.g., `hub_manage_virtual_device`), AI must report the failure — not silently use existing devices as a workaround
- This prevents accidentally controlling critical systems (HVAC, locks) when user meant a different device
- The `hub_delete_device` tool has its own extensive safety checklist (requires recent backup, explicit confirmation, audit logging)

**Optimized Tool Descriptions** (v0.7.2+):
- Tool descriptions reduced by ~387 lines for better token efficiency
- Based on MCP best practices from Anthropic and modelcontextprotocol.io
- All critical safety rules preserved: pre-flight checklists, confirm requirements, backup requirements
- Descriptions follow "explain like to a new hire" principle — concise but complete
- Reduces context consumption when tools are loaded into AI context
- New `hub_get_tool_guide` tool provides detailed reference on-demand (embedded in server, accessible via MCP)

**Custom-engine Rule Deletion Safety** (hub_delete_custom_rule):
- Automatically backs up rule to File Manager before deletion as `mcp_rule_backup_<name>_<timestamp>.json`
- Backup includes full rule export (triggers, conditions, actions, device manifest)
- Restore via: `hub_read_file(fileName)` → `hub_import_custom_rule(exportData: <json>)`
- **Test rules**: Set `testRule: true` in `hub_create_custom_rule` or `hub_update_custom_rule` to skip backup on deletion
- `skipBackupCheck: true` parameter forces skip regardless of testRule flag (rarely needed)
- Test rule flag visible in `hub_get_custom_rule` (both single-rule and list mode, i.e. with `ruleId` omitted) responses
- Gated by the Write master only — no destructive `confirm`+backup tier (rules are MCP-managed, not hub-level resources)

### Hub Internal API Helpers

Three helpers for calling the hub's internal HTTP API at `http://127.0.0.1:8080`:

| Helper | Use Case | Content Type |
|--------|----------|--------------|
| `hubInternalGet(path, query?, timeout?)` | Read endpoints (default 30s timeout), returns response text | text |
| `hubInternalPost(path, body?)` | Simple POST endpoints (reboot, shutdown, zwave repair) | text |
| `hubInternalPostForm(path, body, timeout?)` | Form-encoded POST (app/driver install/update) | `application/x-www-form-urlencoded` |

All three:
- Call `getHubSecurityCookie()` and attach cookie header if Hub Security is enabled
- Use `shouldRetryWithFreshCookie(e, isRetry)` to detect 401/403 errors and clear the cached cookie for automatic re-auth retry
- Use `ignoreSSLIssues: true`

### Shared Helper Methods (v0.7.6+, updated v0.7.7)

| Helper | Purpose |
|--------|---------|
| `currentVersion()` | Single source of truth for version string |
| `buildRuleExport(ruleData)` | Build portable rule export map (used by export and delete backup) |
| `toolInstallItem(type, args)` | Shared install logic for apps and drivers |
| `toolDeleteItem(type, idParam, deletePath, args)` | Shared delete logic for apps and drivers |
| `hubInternalPostJson(path, jsonBody, isRetry?)` | POST to hub internal API with JSON body (used by library endpoints); returns parsed Map/List or null |
| `updateRuleFromParent(data)` | Child app method — handles all rule updates including enable/disable via `enabled=true/false` |
| `shouldRetryWithFreshCookie(e, isRetry)` | Hub Security auth retry detection |
| `clampPercent(value)` | Clamp integer to 0-100 range (in rule.groovy) |
| `rescheduleSunTrigger(type, handler)` | Shared sunrise/sunset trigger rescheduling (in rule.groovy, v0.7.7+) |

### Hub Security Cookie Pattern

When Hub Security is enabled on the hub, internal API calls require authentication:

```groovy
def getHubSecurityCookie() {
    // 1. Return null if Hub Security not enabled
    // 2. Return cached cookie if not expired (30-minute cache)
    // 3. POST to /login with username/password
    // 4. Extract cookie from Set-Cookie header
    // 5. Cache with 30-minute expiry
}
```

The cookie is cached in `state.hubSecurityCookie` with expiry in `state.hubSecurityCookieExpiry`.

### State Management

**Parent app `state.*`:**
| Key | Type | Purpose |
|-----|------|---------|
| `accessToken` | String | OAuth token for MCP endpoint |
| `ruleVariables` | Map | Global variables shared across rules |
| `capturedDeviceStates` | Map | Snapshots of device states |
| `debugLogs` | Map | `{entries: [], config: {logLevel, maxEntries}}` circular buffer |
| `hubSecurityCookie` | String | Cached auth cookie |
| `hubSecurityCookieExpiry` | Long | Cookie expiry epoch ms |
| `lastBackupTimestamp` | Long | Last hub backup epoch ms (24-hour write safety gate) |
| `itemBackupManifest` | Map | Metadata for source code backups stored in File Manager, keyed by `"app_<id>"` / `"driver_<id>"` / `"library_<id>"`, max 20 entries |
| `updateCheck` | Map | `{latestVersion, checkedAt, updateAvailable}` |

**Child app uses `atomicState`** for `triggers`, `conditions`, `actions`, `localVariables`, `durationTimers`, `durationFired`, and `cancelledDelayIds`. This is critical — `atomicState` provides immediate persistence and prevents race conditions when scheduled callbacks (`runIn`) fire in separate execution contexts. Always use read-modify-write pattern with atomicState maps:
```groovy
def timers = atomicState.durationTimers ?: [:]
timers[key] = value
atomicState.durationTimers = timers  // Write back entire map
```
Direct nested mutation (`atomicState.map[key] = value`) silently fails to persist. Regular `state` is used for UI editor state, counters, and timestamps. `cancelledDelayIds` is cleared on `initialize()` since `unschedule()` in `updated()` cancels all pending callbacks.

**Execution Loop Guard** — `executeRule()` tracks recent execution timestamps in `atomicState.recentExecutions`. If a rule fires too many times within a sliding window, it auto-disables (`ruleEnabled = false`), unsubscribes from events, and unschedules all timers. Thresholds are configurable via parent app settings: `loopGuardMax` (default: 30, range: 5–200) and `loopGuardWindowSec` (default: 60s, range: 10–300s). This prevents infinite event loops (e.g., "trigger on Switch A on → action: turn on Switch A") from crashing the hub. When triggered, `notifyLoopGuard()` sends push notifications to any `capability.notification` devices in the parent's selected devices and fires a `mcpLoopGuard` location event (name=`mcpLoopGuard`, value=rule name) that other automations (Rule Machine, etc.) can subscribe to. The rule must be manually re-enabled after fixing the loop. The guard uses `atomicState` for immediate persistence across rapid-fire event handlers.

### Parent-Child Communication

```groovy
// Parent -> Child
childApp.getRuleData()              // Get full rule structure
childApp.testRuleFromParent()       // Dry-run test
childApp.updateRuleFromParent(args) // Update rule data
childApp.getSetting("ruleName")     // Access child settings

// Child -> Parent
parent.getSelectedDevices()         // Access device list
parent.findDevice(deviceId)         // Device lookup
```

### Device Access

All device access goes through `findDevice(deviceId)`:
```groovy
def findDevice(deviceId) {
    if (!deviceId) return null
    // Search selected devices first, then MCP-managed child devices (virtual devices)
    def device = settings.selectedDevices?.find { it.id.toString() == deviceId.toString() }
    if (!device) {
        device = getChildDevices()?.find { it.id.toString() == deviceId.toString() }
    }
    return device
}
```

Devices are accessible from two sources:
1. **`settings.selectedDevices`** — the user explicitly selects which physical/existing devices to expose to MCP (security boundary)
2. **`getChildDevices()`** — MCP-managed virtual devices created via `hub_manage_virtual_device` are automatically accessible without manual selection

`hub_list_devices` also combines both sources (deduplicating by ID) and marks child devices with `mcpManaged: true`.

### Virtual Device Management

Virtual devices are managed via the unified `hub_manage_virtual_device` tool (action enum: "create", "delete") as **child devices** of the MCP Rule Server app using `addChildDevice()` — the officially supported Hubitat API. Key design:

- **`addChildDevice(namespace, driverName, dni, null, [name: ..., label: ..., isComponent: false])`** — 5-argument form with `null` hub ID for cross-firmware compatibility. Namespace is `"hubitat"` for built-in virtual drivers, or the user-supplied namespace for custom drivers.
- **Two mutually exclusive create modes**: `deviceType` (one of 15 built-in virtual driver names, namespace hardcoded to `"hubitat"`) **because** built-in types are a finite validated set requiring only a type name; OR `customDriver={namespace, name}` (user-installed driver with any namespace) **because** custom drivers require both a namespace discriminator and a type name to be uniquely identified on the hub. Exactly one must be provided -- supplying both is an error. Blank-after-trim `deviceType` with `customDriver` also raises the mutex error.
- **`isComponent: false`** — device appears independently in the Hubitat UI, can be edited/deleted, and can be shared with other apps (Maker API, Dashboard, Rule Machine, HA, etc.)
- **`getChildDevices()`** returns only child *devices* (not child apps/rules — those use `getChildApps()`)
- **`deleteChildDevice(dni)`** removes by device network ID
- Auto-generated DNIs use format `mcp-virtual-<hex-timestamp>-<hex-random>` with retry logic to avoid collisions
- Supports 15 built-in virtual device types: Virtual Switch, Virtual Button, Virtual Contact Sensor, Virtual Motion Sensor, Virtual Presence, Virtual Lock, Virtual Temperature Sensor, Virtual Humidity Sensor, Virtual Dimmer, Virtual RGBW Light, Virtual Shade, Virtual Garage Door Opener, Virtual Water Sensor, Virtual Omni Sensor, Virtual Fan Controller
- For custom drivers: `customDriver={namespace, name}` -- namespace + name are coerced to String then trimmed before use; whitespace-only or numeric values that trim to empty are rejected with a descriptive error before reaching the hub. Any exception from `addChildDevice` on the custom-driver path is translated to an `IllegalArgumentException` pointing to `hub_list_drivers` (fail-closed regardless of hub error text).
- **Response shape** (`hub_manage_virtual_device create`): `{success, message, tips, device: {id, name, label, deviceNetworkId, driverNamespace, driverType, typeName, capabilities, commands, attributes}}`. `typeName` is a deprecated alias for `driverType` **because** existing callers reading `result.device.typeName` after create must not silently break; prefer `driverType` in new code.
- **Response shape** (`hub_manage_virtual_device delete`): `{success, deviceId, deviceNetworkId, deviceLabel, message}`.
- **Response shape** (`hub_list_devices` with `filter='virtual'`): `{devices: [...], count, message}`. Per-device: `{id, name, label, deviceNetworkId, driverNamespace, driverType, typeName, capabilities, commands, currentStates}`. `currentStates` is a map of attribute-name to current-value (not a list -- create returns `attributes` as a list while list returns `currentStates` as a map; both expose device state but under different shapes because create returns the freshly-read attribute list and list returns a compact state map). `typeName` is a deprecated alias for `driverType` -- prefer `driverType` in new code. `driverNamespace` is authoritative for devices created by this tool (the namespace is persisted as a device data value at create time); for devices created before this version or by other means it falls back to a best-effort derivation that may report `"hubitat"`.
- **Error contract (N.36)**: `customDriver` not-found throws `IllegalArgumentException` (JSON-RPC -32602) because the bad driver spec is caller-supplied and recoverable by fixing args. Built-in not-found throws `RuntimeException` (isError:true) because hub firmware not including a built-in driver is a platform condition, not a caller error. This is a deliberate exception to the general `return [success:false]` convention -- the two-class split reflects the distinction between caller-fixable vs platform-gap failures.
- Requires the Write master (with confirm + backup verification) for create/delete operations

#### hub_update_device Tool

The `hub_update_device` tool modifies properties on any accessible device (selected or MCP-managed). It accepts all parameters as optional — only specified fields are changed:

- **label** — `device.setLabel(value)` (official API)
- **name** — `device.setName(value)` (official API)
- **deviceNetworkId** — `device.setDeviceNetworkId(value)` (official API)
- **dataValues** — `device.updateDataValue(key, value)` for each entry (official API)
- **preferences** — `device.updateSetting(key, [type: type, value: value])` for each entry (official API, requires `type` field: `bool`, `number`, `decimal`, `text`, `enum`, `time`, `hub`)
- **room** — resolves room name → ID via `getRooms()` (case-insensitive), then POSTs JSON to `/room/save` with `roomId`, `name`, and `deviceIds` fields. Uses **safe move pattern**: adds device to new room first, then removes from old room. This prevents "device limbo" (device in no room) if the second API call fails — worst case, device appears in both rooms temporarily, which is recoverable. Uses `Content-Type: application/json` (NOT form-encoded — the endpoint returns 500 with form data). The API field is `roomId` (not `id` — using `id` returns `{"roomId":null,"error":"Invalid room id"}`). Post-save verification via `getRooms()`. Requires the Write master.
- **enabled** — POSTs to `/device/disable` with `id` and `disable` as body params (undocumented API, must be POST not GET; requires the Write master)

Room assignment and enable/disable use the hub's internal API at `http://127.0.0.1:8080` and are gated by the Write master. All other properties use the official Hubitat Groovy API and work on any accessible device. Driver type cannot be changed — must delete and recreate the device.

### Room Management

5 tools for full room CRUD, using `POST /room/save` (JSON) and `getRooms()` for verification:

| Tool | Access Gate | Description |
|------|------------|-------------|
| `hub_list_rooms` | None | Lists all rooms with IDs, names, device counts via `getRooms()` |
| `hub_get_room` | None | Room details with full device info/states. Accepts name (case-insensitive) or ID |
| `hub_create_room` | Write master + confirm | Creates room via `POST /room/save` with `roomId: 0` (Grails create convention) |
| `hub_delete_room` | Write master + confirm | Deletes room via `POST /room/delete/<id>` or `GET /room/delete/<id>`. Devices become unassigned |
| `hub_update_room` | Write master + confirm | Renames room via `POST /room/save` with existing `roomId` and new `name`. Preserves device assignments |

**Key API details:**
- All room mutations use `POST /room/save` at `http://127.0.0.1:8080` with `Content-Type: application/json`
- The JSON body uses `roomId` (not `id`) — the API returns `{"roomId":null,"error":"Invalid room id"}` if `id` is used
- Body format: `{"roomId": <int>, "name": "<string>", "deviceIds": [<int>, ...]}`
- For room creation, `roomId: 0` triggers create behavior (Grails convention)
- `getRooms()` is a built-in Hubitat SDK method returning `[[id:1, name:"Bedroom", deviceIds:[5, 6]], ...]`
- All write tools verify the operation via `getRooms()` after the API call
- Form-encoded bodies return 500 — the endpoint strictly requires JSON

### Version Management

As of v0.7.6, most version references in `hubitat-mcp-server.groovy` are **centralized** via the `currentVersion()` function. Runtime code (handleInitialize, toolExportRule, toolGetLoggingStatus, toolGenerateBugReport, toolGetHubInfo, mainPage display) all call `currentVersion()` instead of hardcoding the version string. As of v0.7.7, tool execution errors use `isError: true` in the MCP result per protocol spec instead of JSON-RPC errors.

When bumping the version, update these locations:
- `currentVersion()` return value — **single source of truth** for runtime version
- File header comment (`hubitat-mcp-server.groovy` line 7) — for source code readers
- `hubitat-mcp-rule.groovy` — file header comment version
- `packageManifest.json` — version field, dateReleased, and releaseNotes
- `README.md` — "New in vX.Y.Z" section + Version History table

Search for the current version string across all files to verify no hardcoded references remain.

### Groovy/Hubitat Idioms

- **Elvis operator** `?:` for defaults: `args.offset ?: 0`
- **Safe navigation** `?.` everywhere: `device.capabilities?.collect { it.name }`
- **Dynamic method invocation**: `device."${command}"(*convertedParams)` with spread operator
- **`settings.*`** for user-configured preferences (persisted by Hubitat framework)
- **`state.*`** for persistent app-level storage
- **`atomicState.*`** for thread-safe persistent storage
- **`location.*`** for hub-level info (modes, timezone, HSM, etc.)
- **`httpGet`/`httpPost`** for synchronous HTTP; `asynchttpGet` for async
- **`subscribe(device, attribute, handler)`** for device event subscriptions
- **`schedule(cron, method)`** / `runIn(seconds, method)` for scheduling
- **`render(contentType: "application/json", data: jsonString)`** for HTTP responses
- **`groovy.json.JsonOutput.toJson()`** and **`new groovy.json.JsonSlurper().parseText()`** for JSON

### Hubitat Sandbox Limitations

The Hubitat Groovy sandbox restricts:
- No `Eval.me()` or dynamic code evaluation
- No file system access
- No arbitrary network access (only `httpGet`/`httpPost` to allowed destinations)
- No thread creation
- Limited class imports (no `java.io`, limited `java.net`)
- `state` writes are eventually consistent; `atomicState` is immediately consistent
- Hub properties (e.g., `hub.uptime`, `hub.zigbeeChannel`) can throw exceptions on some firmware versions — always wrap in try/catch

### Hub Internal API Endpoints Reference

These are undocumented endpoints on the Hubitat hub at `http://127.0.0.1:8080`:

**Read endpoints (GET):**
| Path | Returns |
|------|---------|
| `/hub/advanced/freeOSMemory` | Free memory in KB (text) |
| `/hub/advanced/internalTempCelsius` | CPU temperature (text) |
| `/hub/advanced/databaseSize` | Database size in KB (text) |
| `/hub/advanced/blinkLED` | Fires the hub's identify-LED sequence (blue → red → green) once. Returns the literal text `true`. Single GET, no body, self-resetting. Surfaced via the opt-in `identifyHub` flag on `hub_get_info` and `hub_get_device_health`. |
| `/hub2/userAppTypes` | Apps Code definitions (JSON array: id, name, namespace). Each entry is a code definition, NOT a running instance -- child-app templates appear here even with zero active instances. Distinct from the `userAppTypes[]` key embedded in `/hub2/appsList` (which is the instance tree). Used by `hub_list_apps` and `hub_list_hpm_packages` (includeDrift=true; orphan-app detection). |
| `/hub2/userDeviceTypes` | Drivers Code definitions (JSON array: id, name, namespace, capabilities, lastModified, usedBy[]). Despite the name, this is the Drivers Code registry (code definitions, not device instances). Used by `hub_list_drivers` and `hub_list_hpm_packages` (includeDrift=true; orphan-driver detection). Note: hub uses `userDeviceTypes` for the driver registry while apps use `userAppTypes` -- the naming asymmetry is a hub convention, not an error. |
| `/hub2/zwaveInfo` | Z-Wave radio details (JSON) |
| `/hub2/zigbeeInfo` | Zigbee radio details (JSON) |
| `/app/ajax/code` with query `id=<id>` | App source code (JSON: source, version, status) |
| `/driver/ajax/code` with query `id=<id>` | Driver source code (JSON: source, version, status) |
| `/hub2/userLibraries` | Installed user libraries (JSON array: id, version, name, namespace, author, description, usedByAppTypes, usedByDeviceTypes -- no source field) |
| `/library/list/single/data/<id>` | Single library with full source (JSON array of one: id, version, name, namespace, source, lastModified, usedByDeviceTypes, etc.) |
| `/hub/backupDB` with query `fileName=latest` | Creates fresh backup and returns .lzf binary |
| `/hub/fileManager/json` | Lists all files in File Manager (JSON array: name, size, date) |
| `/hub2/roomsList` | List of rooms as JSON (alternative to `getRooms()` SDK method) |
| `/logs/past/json` | Hub log buffer as JSON array of tab-delimited strings (chronological order, oldest first — reverse client-side for newest-first). Accepts optional `?type=dev&id=<deviceId>` or `?type=app&id=<appId>` to scope server-side to a single source. |
| `/hub2/appsList` | All installed apps (built-in + user) as JSON. Keys: `systemAppTypes[]`, `userAppTypes[]`, `apps[]` (instance tree). Each `apps[]` entry has `{key, id, data: {id, name, type, disabled, user, hidden, appTypeId}, parent: bool, child: bool, children: [...]}`. Used by `hub_list_apps` (`scope=instances`). |
| `/device/fullJson/<id>` | Comprehensive device JSON — includes `appsUsing` array (apps referencing this device: `{id, name, label, trueLabel, disabled}`), `appsUsingCount`, `parentApp`, plus device commands/attributes/settings/dashboards. Used by `hub_list_device_dependents`. |
| `/installedapp/configure/json/<id>[/<pageName>]` | SDK-level config-page serialization for any installed app using `dynamicPage()`. Returns `{app, configPage: {name, title, sections: [{title, input: [...], body: [...]}]}, settings, childApps}`. `app` carries identity (label, name, appType, disabled, parentAppId). Sections hold typed inputs with current values. The Web UI itself consumes this endpoint. Used by `hub_get_app_config`. |
| `/installedapp/statusJson/<id>` | Raw Groovy `state` map for any installed app. Returns `{id, appState: [{name, value}, ...], appSettings: [...]}`. `appState[].value` shape varies: live hubs typically return the value already parsed as a Map (JsonSlurper recursively decoded the inner JSON); older firmwares or large payloads may leave it as a JSON-encoded String requiring a second parse. The implementation handles both: if value is already a Map, use it directly; if String, parse again. Used by `hub_list_hpm_packages` (including its `includeDrift=true` mode) to read HPM's `state.manifests` package registry. Gated by the Read master. |
| `/device/listWithCapabilities/json` | Every hub device as a JSON array (`{id, label, capabilities}`) regardless of MCP authorization — the only view of devices the app isn't granted (the Groovy device model is authorization-scoped). Used by `hub_list_devices` (`scope='all'`, tagging each `mcpAuthorized`) and by `hub_update_mcp_settings` (the `selectedDevices` scope key) to validate requested device ids against the full hub set. |

**Write endpoints (POST):**
| Path | Body | Purpose |
|------|------|---------|
| `/hub/reboot` | none | Reboot hub |
| `/hub/shutdown` | none | Shutdown hub |
| `/hub/zwaveRepair` | none | Start Z-Wave network repair |
| `/app/saveOrUpdateJson` | JSON: `{"id": <id\|null>, "source": "<code>", "version": <ver\|1>}` | Install (id=null) or update (id=N) app code. Returns `{success, id, message}`; compile errors ride verbatim in `message`. MUST use `Content-Type: application/json`. `version` is enforced hub-side: a save carrying a stale version is rejected with a version-mismatch error (observed live — a web-UI save after an MCP-driven update errors out). |
| `/driver/saveOrUpdateJson` | JSON: `{"id": <id\|null>, "source": "<code>", "version": <ver\|1>}` | Install (id=null) or update (id=N) driver code — same contract as the app endpoint. |
| `/app/ajax/update` | `id=<id>, version=<ver>, source=<code>` | Legacy form-encoded app-code update; still works on fw 2.5.x (the e2e watchdog still uses it). Response: `{status, errorMessage}`. Same shape at `/driver/ajax/update`. |
| `/library/saveOrUpdateJson` | JSON: `{"id": null, "source": "<code>", "version": null}` | Install new library (id=null creates; id=N updates with version=N for optimistic lock). Returns `{success, message, id, version}`. MUST use `Content-Type: application/json`. |
| `/login` | `username=<u>, password=<p>, submit=Login` | Hub Security login |
| `/device/save` | `id=<deviceId>, label=<label>, name=<name>, deviceNetworkId=<dni>, type.id=<typeId>` | Update device properties (flat field names, Grails convention). NOTE: silently ignores `roomId` — use `/room/save` instead |
| `/device/disable` | `id=<deviceId>, disable=<true\|false>` | Enable or disable a device (MUST be POST, not GET) |
| `/room/save` | JSON: `{"roomId": <int>, "name": "<str>", "deviceIds": [<int>,...]}` | Create (roomId=0) or update room. MUST use `Content-Type: application/json` — form-encoded returns 500. Field is `roomId` not `id` |

**Delete endpoints (GET or POST):**
| Path | Method | Purpose |
|------|--------|---------|
| `/app/edit/deleteJsonSafe/<id>` | GET | Delete app (returns JSON with `status: true`) |
| `/driver/editor/deleteJson/<id>` | GET | Delete driver (returns JSON with `status: true`) |
| `/library/edit/deleteJson/<id>` | GET | Delete library (returns JSON with `success: true, message: null`) |
| `/room/delete/<roomId>` | POST or GET | Delete room (try POST first, fall back to GET) |

### MCP Protocol Implementation

The server implements MCP protocol version `2024-11-05`:
- **Transport**: Streamable HTTP (not SSE/stdio) with OAuth access token (`?access_token=<token>`)
- **Format**: JSON-RPC 2.0 (supports batch requests)
- **Methods**: `initialize`, `tools/list`, `tools/call`, `ping`
- **Notifications**: Handled silently (HTTP 204)
- **Error codes**: `-32700` (parse), `-32600` (invalid request), `-32601` (method not found), `-32602` (invalid params from `IllegalArgumentException`), `-32603` (internal error)
- **`tools/list` returns the full catalog in one response.** Pagination was tried (page size 50, cursor-based) but removed because many MCP clients — including the Claude.ai connector — don't iterate `nextCursor`, which silently truncated the flat-mode catalog at 50 tools. The full-catalog response is backstopped by the universal response-size guard at `handleMcpRequest` (124,000-byte threshold) that emits a loud `-32603 "Response too large"` envelope if the catalog ever exceeds the hub cap. Stale clients passing a `cursor` get the full catalog and find no `nextCursor`. Opt-in cursor pagination on `tools/call` (`hub_list_devices`, `hub_list_apps`, `hub_list_rules`, etc.) is unaffected.

### Common Pitfalls

1. **Don't forget trailing commas** in `getToolDefinitions()` — it's a Groovy list literal, so every tool definition except the last needs a trailing comma after its closing `]`
2. **Device IDs are strings in MCP but integers internally** — always use `.toString()` for comparison and return values
3. **`state` vs `atomicState`** — use `atomicState` in the child app for rule data (triggers, conditions, actions) and cross-execution state (durationTimers, durationFired, cancelledDelayIds, localVariables); always use read-modify-write pattern for nested maps; use `state` only for UI editor state, counters, and timestamps
4. **Hub properties can throw** — always wrap `hub?.propertyName` in try/catch with `"unavailable"` fallback
5. **Hub internal API responses vary by firmware** — always handle both JSON and non-JSON responses with nested try/catch for parsing
6. **Numeric parsing of API responses** — hub endpoints like `/hub/advanced/freeOSMemory` return text that might not be numeric; wrap `as Integer` / `as Double` conversions in try/catch
7. **OAuth token** — created once in `initialize()` via `createAccessToken()` and stored in `state.accessToken`; never regenerate it or users lose their MCP endpoint URL
8. **Version strings in 9+ locations** — when bumping version, search for the current version string to find all locations across `hubitat-mcp-server.groovy`, `hubitat-mcp-rule.groovy`, `packageManifest.json`, `README.md`, and `SKILL.md`
9. **Date/timestamp parsing** — `formatTimestamp()` tries 6 ISO 8601 format variations (with/without millis, with Z/offset/no timezone, space-separated) to handle differences across firmware versions and upstream APIs. Falls back to truncated raw string if no format matches. Never use a single strict `Date.parse()` format

## Future Plans (Blue-Sky — Needs Research)

These are speculative feature ideas that need feasibility research before implementation. When the user asks "what should I work on next?" or similar, reference this list.

### HPM Integration
- Search HPM repositories for packages by keyword
- Install/uninstall packages via HPM programmatically
- Check for updates across all installed packages

### App/Integration Discovery (Outside HPM)
- Search for and install official Hubitat integrations not yet enabled
- Discover and install community apps/drivers from GitHub, forums, etc.

### Dashboard Management
- Create, modify, delete dashboards programmatically
- Prefer official Hubitat dashboards (home screen + mobile app visibility)
- If official API isn't available, explore alternatives that can be set as defaults

### Rule Machine Interoperability
- Read native RM rules via their export/import/clone UI mechanism (API unknown)
- Export MCP rules in RM-importable format
- Import directly into native Rule Machine if API exists
- Bidirectional sync between MCP rules and RM rules (long-shot)

### Additional Ideas
- Device creation — *partially implemented in v0.6.0* (MCP-managed child virtual devices via `addChildDevice()`). Future: create standalone virtual devices via hub internal API (`POST /device/save`) that appear in the regular Devices section independent of the MCP app
- Device pairing assistance (Z-Wave inclusion, Zigbee pairing, cloud device setup)
- Notification/alert management (granular routing)
- Scene management (create/modify/manage beyond activate_scene)
- Energy monitoring aggregation and reports
- Scheduled automated reports (hub health, device status, rule history)
