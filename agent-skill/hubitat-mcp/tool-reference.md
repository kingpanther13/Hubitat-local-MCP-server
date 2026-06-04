# Tool Reference

Quick reference for all 88 MCP tools. The server exposes **30 items on `tools/list`**: 11 flat core tools + 19 gateway tools. Each gateway proxies additional tools — call with no args for full schemas, or with `tool` and `args` to execute. A tool MAY appear under more than one gateway (multi-membership); read-only tools inside a mixed `hub_manage_*` gateway are also surfaced under a pure-read `hub_read_*` gateway.

For the most authoritative reference, call `hub_get_tool_guide` via MCP.

## Tool Naming Conventions

All tools in this server follow these conventions. Use the conventions to predict tool shape even before consulting the per-tool entries below.

- Every tool name begins with `hub_`.
- Tool names follow verb-noun order. The allowed verbs are: `list`, `get`, `search`, `test`, `create`, `update`, `delete`, `set`, `call`, `manage`, `restore`, `import`, `export`, `clone`, plus `read`/`write` for file-manager tools and the destructive-ops exceptions `reboot` / `shutdown`. There is one locked-to-one-tool verb: `report` (used only by `hub_report_issue`).
- Gateways carry one of two verbs. `hub_read_<noun>` names a gateway whose every sub-tool is read-only; `hub_manage_<noun>` names a gateway that contains at least one write (mixed read+write or write-only). Call any gateway with no args to see the catalog of sub-tools; call with `tool=<name>` and `args={...}` to execute one. There is a narrow exception: a flat tool with a small action enum (e.g. `hub_manage_virtual_device` with `action: "create"/"delete"`) may also use `manage_`. The `read`/`write` file-manager leaf verbs (`hub_read_file`, `hub_write_file`) are distinct from the `hub_read_*` gateway prefix — the noun disambiguates.
- Per-tool entries below use the names current at the time of writing. Predict shape from the conventions, not the names.

## Universal response-size guard + opt-in cursor pagination

Every `tools/call` response is bounded by a 120 KB wire-encoded size guard. Oversized responses come back as a structured `{response_too_large: true, truncated: true, estimatedBytes, sizeLimitBytes, tool, suggestion}` envelope rather than vanishing into a hub `-32603`. The `suggestion` field gives the LLM a specific narrowing hint per tool.

Opt-in cursor pagination is wired into the read-only list-returning tools below. All follow the same shape: omit `cursor` for the full list (backward-compatible), pass `cursor: ""` for the first page, iterate `nextCursor` until absent. Non-numeric / out-of-range cursors reject as `-32602`.

**Tools with cursor:** `hub_list_devices` (including `filter='virtual'`), `hub_list_apps` (both `scope=types` and `scope=instances`), `hub_list_drivers`, `hub_list_hpm_packages`, `hub_list_rules`, `hub_get_custom_rule` (list mode, ruleId omitted), `hub_list_variables`, `hub_list_captured_states`, `hub_list_backups`, `hub_list_files`, `hub_list_rooms`, `hub_get_device_health`, `hub_list_device_dependents`, `hub_get_logs`, `hub_get_memory_history`, `hub_get_debug_logs`. See [TOOL_GUIDE.md](../../TOOL_GUIDE.md) for per-tool page sizes.

## Core Tools (11) — Always flat and visible on tools/list

These 11 tools are never behind a gateway. Every other tool is reachable through one or more of the 19 gateways below.

### Virtual Device Tools (1)

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `hub_manage_virtual_device` | Create or delete MCP-managed virtual devices (action="create"/"delete"). For create, provide exactly ONE of: `deviceType` (15 built-in types -- not-found is isError platform error) or `customDriver={namespace, name}` (installed driver -- not-found is -32602 input error with hub_list_drivers hint). The two are mutually exclusive (including blank/whitespace `deviceType` with `customDriver`). Create response: `{success, message, tips, device: {id, name, label, deviceNetworkId, driverNamespace, driverType, typeName (deprecated alias), capabilities, commands, attributes}}`. Delete response: `{success, deviceId, deviceNetworkId, deviceLabel, message}`. To list MCP-managed virtual devices with their states, use `hub_list_devices` with `filter='virtual'`. | Hub Admin Write |

### System Tools (8)

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `hub_get_info` | Comprehensive hub info (hardware, health, MCP stats) always available; PII/location data (name, IP, timezone, coordinates, zip) requires Hub Admin Read. | None |
| `hub_list_modes` | List location modes. | None |
| `hub_set_mode` | Change location mode (Home, Away, Night, etc.). | None |
| `hub_get_hsm_status` | Get Home Security Monitor status. | None |
| `hub_set_hsm` | Change HSM arm mode. | None |
| `hub_create_backup` | Create full hub database backup. | Hub Admin Write |
| `hub_get_update_status` | Check for MCP server updates. | None |
| `hub_report_issue` | Generate comprehensive diagnostic report. | None |

### Reference (2)

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `hub_get_tool_guide` | Full tool reference from the MCP server itself. | None |
| `hub_search_tools` | BM25 natural language search across all 88 tools — returns matching tools ranked by relevance, with gateway attribution so the AI knows how to call each. | None |

---

## Gateway Tools (19) — Each proxies multiple tools

Call a gateway with no arguments to see full parameter schemas for all its tools. Call with `tool='<name>'` and `args={...}` to execute a specific tool.

Seven gateways are pure-read (`hub_read_*`, every sub-tool read-only); twelve carry at least one write (`hub_manage_*`). A read-only tool that lives inside a mixed `hub_manage_*` gateway is also surfaced under a `hub_read_*` gateway (multi-membership; same tool, no duplication).

### hub_read_apps_code (9 tools)

Read-only access to apps, drivers, libraries, backups, installed-app inventory, and HPM package state: list, view source, browse backups, inspect configs, and discover page names.

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `hub_list_apps` | List apps. `scope='types'` enumerates installable app types; `scope='instances'` enumerates all installed apps (built-in + user) with parent/child tree, filterable by `all`/`builtin`/`user`/`disabled`/`parents`/`children`. Optional `cursor` opt-in pagination. | Hub Admin Read |
| `hub_list_drivers` | List installed user drivers. | Hub Admin Read |
| `hub_get_source` | Get Groovy source code for an app, driver, or library (`type`: "app", "driver", "library"; `id`). Chunked-read support via `offset`/`length`. Large files auto-saved to File Manager. | Hub Admin Read |
| `hub_list_backups` | List all source code backups. | None |
| `hub_get_backup` | Retrieve source from a backup. | None |
| `hub_list_device_dependents` | Given a `deviceId`, list apps referencing it (Room Lighting, Rule Machine, Groups, Mode Manager, dashboards, Maker API, etc.). | Built-in App Read |
| `hub_get_app_config` | Read an installed app's configuration page (Rule Machine, Room Lighting, Basic Rules, HPM, etc.). Returns sections/inputs/values; multi-page apps via `pageName`. Workflow: hub_list_apps(scope=instances) or hub_list_rules -> hub_get_app_config with appId; multi-page apps accept pageName (HPM: prefPkgUninstall for full list). Read-only. | Hub Admin Read |
| `hub_list_app_pages` | List known page names for a multi-page app (HPM, Room Lighting, etc.). Returns curated directory + live primary page. Use before hub_get_app_config on multi-page apps. | Hub Admin Read |
| `hub_list_hpm_packages` | List all HPM-tracked packages with full component inventory (apps, drivers, files). **Pass `includeDrift=true`** to also cross-reference HPM-tracked state against the hub; results nest under a `drift` key. Requires Hub Admin Read and HPM installed; auto-discovers HPM's installed-app ID unless `hpmAppId` is supplied. See `hub_get_tool_guide` for the full drift response shape. | Hub Admin Read |

### hub_read_devices (4 tools)

Read-only device access: list, view details, read attributes, and recent events.

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `hub_list_devices` | List accessible devices. Pagination, `labelFilter` (substring), `capabilityFilter` (exact), `format='ids'` (flat ID array), `fields=[...]` (projection), `filter='virtual'` (only MCP-managed virtual devices, with their states). Use `detailed=false` first, paginate `detailed=true` (limit 20-30). | None |
| `hub_get_device` | Full device details: attributes, commands, capabilities, room. | None |
| `hub_get_device_attribute` | Get specific attribute value from a device. Pass `expectedValue` (or any of `expectedValues`; both may be set, OR semantics) to block-poll the attribute until it matches or times out -- `timeoutMs` in MILLISECONDS (default 5000ms = 5 seconds, max 60000ms). Polling BLOCKS the MCP request; use sparingly and prefer event-driven flows. When polling, returns `success`, `finalValue`, `elapsedMs`, `polledCount`, `timedOut`; adds `neverReported: true` if the attribute was null throughout. Use after `hub_call_device_command` to verify a state change in a single round-trip. | None |
| `hub_list_device_events` | Recent events for a device. Default 10, recommended max 50. Add `hoursBack` for a time window (up to 7 days of device or location event history); omit `deviceId` for mode/HSM/hub-variable/sendLocationEvent location events. | None |

### hub_read_diagnostics (9 tools)

Read-only diagnostics: metrics, memory/CPU history, scheduled-job-free health checks, radio info, device health, and state-capture inventory.

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `hub_get_logs` | Hub log entries, most recent first. Default 100, max 500. Filter by level/source/pattern (regex) or multi-pattern with AND/OR mode; time-window via `since`/`until` (ISO-8601 or relative offset like `'30m'`, max 30d -- throws if exceeded); or scope server-side to a single `deviceId` / `appId`. `pattern` matches the message field only (not source/name). Pathological regex like `(.*)*` may hang the matcher; prefer simple alternation. (Device/location event *history* is in `hub_list_device_events` via `hoursBack`.) | Hub Admin Read |
| `hub_get_performance_stats` | Device/app performance stats from `/logs`: method call counts, % busy, cumulative total ms, state size, events. Sortable. | Hub Admin Read |
| `hub_get_jobs` | Scheduled and running jobs on the hub. | Hub Admin Read |
| `hub_get_debug_logs` | Retrieve MCP debug log entries. Filter by level. Pass `mode='status'` to view logging system statistics instead. | None |
| `hub_get_metrics` | Retrieve hub metrics with CSV trend history (read-only; `recordSnapshot` defaults to false). | Hub Admin Read |
| `hub_get_memory_history` | Free OS memory + CPU load history (with Java heap + NIO buffer tracking for leak detection). | Hub Admin Read |
| `hub_get_device_health` | Find stale/offline devices. Optional `cursor` opt-in pagination over `staleDevices` (page size 100). | Hub Admin Read |
| `hub_get_radio_details` | Radio info -- Z-Wave (firmware, devices) or Zigbee (channel, PAN ID, devices). `radio`: "zwave" or "zigbee"; omit for both. | Hub Admin Read |
| `hub_list_captured_states` | List saved device state snapshots. | None |

### hub_read_files (2 tools)

Read-only File Manager access: list and read files stored on the hub.

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `hub_list_files` | List all files in File Manager. | None |
| `hub_read_file` | Read a file (inline for <60KB, URL for larger). | None |

### hub_read_rooms (2 tools)

Read-only room access: list rooms and view room details.

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `hub_list_rooms` | List all rooms with device counts. | None |
| `hub_get_room` | Room details with full device info. Accepts name or ID. | None |

### hub_read_rules (4 tools)

Read-only custom-rule access plus dry-run testing and native-rule listing/health.

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `hub_get_custom_rule` | Full rule details (triggers, conditions, actions). Omit `ruleId` to list all rules with status and last-triggered; pass `detailed=true` for comprehensive diagnostics on a specific rule. | None |
| `hub_test_custom_rule` | Dry-run: see what would happen without executing. | None |
| `hub_list_rules` | List all Rule Machine rules (RM 4.x + 5.x combined, deduplicated by id). | Built-in App Tools |
| `hub_get_rule_health` | Read-only health check on any installed app -- surfaces broken markers, multiple-flag poison, configPage errors. | Built-in App Tools |

### hub_read_variables (3 tools)

Read-only variable access: list, get value/metadata, and observe recent changes.

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `hub_list_variables` | List all hub variables (with type/connector linkage) and rule-engine variables. | None |
| `hub_get_variable` | Get a variable's value + metadata (type, deviceId, attribute). | None |
| `hub_list_variable_changes` | Recent hub-variable changes since the MCP app last started. Filter by name, sinceMs, limit. | None |

### hub_manage_devices (6 tools)

Device control and property edits, plus the read tools (also surfaced under `hub_read_devices`).

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `hub_call_device_command` | Send a command to a device (on, off, setLevel, etc.). | None |
| `hub_update_device` | Update device properties (label, name, room, preferences, enabled). | Varies by property |
| `hub_list_devices` | List accessible devices. Pagination, `labelFilter` (substring), `capabilityFilter` (exact), `format='ids'` (flat ID array), `fields=[...]` (projection), `filter='virtual'` (only MCP-managed virtual devices, with their states). Use `detailed=false` first, paginate `detailed=true` (limit 20-30). | None |
| `hub_get_device` | Full device details: attributes, commands, capabilities, room. | None |
| `hub_get_device_attribute` | Get specific attribute value from a device. Block-poll via `expectedValue`/`expectedValues` until match or `timeoutMs` (MILLISECONDS, default 5000ms, max 60000ms). Use after `hub_call_device_command` to verify a state change in a single round-trip. | None |
| `hub_list_device_events` | Recent events for a device. Default 10, recommended max 50. Add `hoursBack` for a time window (up to 7 days of device or location event history); omit `deviceId` for mode/HSM/hub-variable/sendLocationEvent location events. | None |

### hub_manage_custom_rules (8 tools)

Custom-rule administration: get, create, update, delete, test, export, import, and clone MCP-native rules.

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `hub_get_custom_rule` | Full rule details (triggers, conditions, actions). Omit `ruleId` to list all rules with status and last-triggered; pass `detailed=true` for comprehensive diagnostics on a specific rule. | None |
| `hub_create_custom_rule` | Create a new automation rule. | None |
| `hub_update_custom_rule` | Update rule triggers, conditions, or actions. Also handles enable/disable via `enabled=true/false`. | None |
| `hub_delete_custom_rule` | Delete a rule (auto-backs up first). | None |
| `hub_test_custom_rule` | Dry-run: see what would happen without executing. | None |
| `hub_export_custom_rule` | Export rule as portable JSON (persists to File Manager via saveAs). | Hub Admin Write |
| `hub_import_custom_rule` | Import a rule from exported JSON. | None |
| `hub_clone_custom_rule` | Duplicate an existing rule. | None |

> **Built-in rule redirect:** `hub_get_custom_rule`, `hub_export_custom_rule`, `hub_update_custom_rule`, `hub_delete_custom_rule`, `hub_test_custom_rule`, and `hub_clone_custom_rule` operate only on MCP-native rules. If you pass an id belonging to a Hubitat built-in rule (Rule Machine, Room Lighting, Basic Rules, Visual Rules), the error message includes a redirect hint pointing to `hub_read_apps_code -> hub_get_app_config(appId=<id>)` (read) or, for write and delete verbs, the appropriate `hub_manage_native_rules_and_apps` CRUD tool. The test verb hint includes `hub_call_rule` only for Rule Machine rules; other built-in rule-likes receive `hub_get_app_config` for inspection because `hub_call_rule` is RM-only. This redirect fires only when Built-in App Tools are enabled. See `TOOL_GUIDE.md` "Hubitat Built-in Rule Redirect" for full details.

### hub_manage_variables (8 tools)

Manage hub variables (every type — Number, Decimal, String, Boolean, DateTime), their connector devices, and rule-engine variables. Full read/write CRUD via the modern Hub Variable API; observe changes via `hub_list_variable_changes`.

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `hub_list_variables` | List all hub variables (with type/connector linkage) and rule-engine variables. | None |
| `hub_get_variable` | Get a variable's value + metadata (type, deviceId, attribute). | None |
| `hub_set_variable` | Set an existing variable's value. Falls back to rule_engine namespace when no hub var matches. | None |
| `hub_create_variable` | Create a new hub variable. Type enum: Number / Decimal / String / Boolean / DateTime. | Hub Admin Write |
| `hub_delete_variable` | Permanently delete a variable (DESTRUCTIVE — also removes its connector if any). `force=true` if rules reference it. | Hub Admin Write + recent backup |
| `hub_create_connector` | Create a virtual-device connector for an existing hub variable. | Hub Admin Write |
| `hub_delete_connector` | Remove the connector device for a hub variable (the variable itself is unchanged). | Hub Admin Write |
| `hub_list_variable_changes` | Recent hub-variable changes since the MCP app last started. Filter by name, sinceMs, limit. | None |

### hub_manage_rooms (5 tools)

Manage hub rooms: list, view details, create, delete, and rename.

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `hub_list_rooms` | List all rooms with device counts. | None |
| `hub_get_room` | Room details with full device info. Accepts name or ID. | None |
| `hub_create_room` | Create a new room. | Hub Admin Write |
| `hub_delete_room` | Delete a room (devices become unassigned). | Hub Admin Write |
| `hub_update_room` | Rename an existing room. | Hub Admin Write |

### hub_manage_destructive_ops (3 tools)

Destructive hub operations: reboot, shutdown, and device deletion.

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `hub_reboot` | Reboot hub (1-3 min downtime). | Hub Admin Write |
| `hub_shutdown` | Power off hub (needs manual restart). | Hub Admin Write |
| `hub_delete_device` | Permanently delete a device. **NO UNDO.** For ghost/orphaned devices only. | Hub Admin Write |

### hub_manage_code (8 tools)

Write operations for apps, drivers, and libraries: install, update, delete, and restore code.

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `hub_create_app` | Install a new Groovy app from `source` (inline) or `sourceFile` (File Manager filename). Verifies install compiled cleanly. | Hub Admin Write |
| `hub_create_driver` | Install a new Groovy driver from `source` (inline) or `sourceFile` (File Manager filename). Bulk mode: `installs=[{source|sourceFile},...]` (continue-on-error). Verifies each install compiled cleanly. | Hub Admin Write |
| `hub_create_library` | Install a new Groovy library (`#include namespace.Name`). Library source must include a `library()` block with `name`, `namespace`, `author`, `description` (4 required; `category` optional). | Hub Admin Write |
| `hub_update_app` | Update existing app source code (source, sourceFile, or resave). | Hub Admin Write |
| `hub_update_driver` | Update existing driver source code. Single-driver mode (driverId + source/sourceFile/resave) or bulk mode (updates array of {driverId, sourceFile} pairs, continue-on-error). | Hub Admin Write |
| `hub_update_library` | Update existing library source code (libraryId + source/sourceFile/resave). Auto-backs up before modifying. | Hub Admin Write |
| `hub_delete_item` | Delete an installed app, driver, or library (`type`: "app", "driver", "library"; auto-backs up). For libraries, ensure no apps/drivers `#include` it first. | Hub Admin Write |
| `hub_restore_backup` | Restore app/driver to backed-up version. | Hub Admin Write |

### hub_manage_logs (6 tools)

Hub and MCP log access, performance stats, and log configuration.

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `hub_get_logs` | Hub log entries, most recent first. Default 100, max 500. Filter by level/source/pattern (regex) or multi-pattern with AND/OR mode; time-window via `since`/`until` (ISO-8601 or relative offset like `'30m'`, max 30d -- throws if exceeded); or scope server-side to a single `deviceId` / `appId`. `pattern` matches the message field only (not source/name). Pathological regex like `(.*)*` may hang the matcher; prefer simple alternation. (Device/location event *history* is in `hub_list_device_events` via `hoursBack`.) | Hub Admin Read |
| `hub_get_performance_stats` | Device/app performance stats from `/logs`: method call counts, % busy, cumulative total ms, state size, events. Sortable. | Hub Admin Read |
| `hub_get_jobs` | Scheduled and running jobs on the hub. | Hub Admin Read |
| `hub_get_debug_logs` | Retrieve MCP debug log entries. Filter by level. Pass `mode='status'` to view logging system statistics instead. | None |
| `hub_delete_debug_logs` | Clear all MCP debug logs. | None |
| `hub_set_log_level` | Set MCP log level (debug/info/warn/error). | None |

### hub_manage_diagnostics (8 tools)

Performance monitoring, health checks, diagnostics, radio info, memory / GC, and state capture.

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `hub_get_metrics` | Retrieve hub metrics with CSV trend history (read-only; `recordSnapshot` defaults to false). | Hub Admin Read |
| `hub_get_device_health` | Find stale/offline devices. Optional `cursor` opt-in pagination over `staleDevices` (page size 100). | Hub Admin Read |
| `hub_get_radio_details` | Radio info -- Z-Wave (firmware, devices) or Zigbee (channel, PAN ID, devices). `radio`: "zwave" or "zigbee"; omit for both. | Hub Admin Read |
| `hub_get_memory_history` | Free OS memory + CPU load history (with Java heap + NIO buffer tracking for leak detection). | Hub Admin Read |
| `hub_call_gc` | Force JVM GC and return before/after memory comparison. | Hub Admin Read |
| `hub_call_zwave_repair` | Start Z-Wave network repair (5-30 min). | Hub Admin Write |
| `hub_list_captured_states` | List saved device state snapshots. | None |
| `hub_delete_captured_state` | Delete a captured device state snapshot. Omit `stateId` to delete all snapshots. | None |

### hub_manage_files (4 tools)

Manage hub File Manager: list, read, write, and delete files stored on the hub.

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `hub_list_files` | List all files in File Manager. | None |
| `hub_read_file` | Read a file (inline for <60KB, URL for larger). | None |
| `hub_write_file` | Create/update a file (auto-backs up existing). | Hub Admin Write |
| `hub_delete_file` | Delete a file (auto-backs up first). | Hub Admin Write |

### hub_manage_native_rules_and_apps (11 tools)

Two surfaces: RMUtils-based runtime control for RM rules (read/trigger/pause-resume) plus admin-layer CRUD that works uniformly across any classic SmartApp (RM, Room Lighting, Button Controllers, Basic Rules, Notifier, etc.). Requires Built-in App Tools enabled; CRUD operations additionally require Hub Admin Write.

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `hub_list_rules` | List all Rule Machine rules (RM 4.x + 5.x combined, deduplicated by id). | Built-in App Tools |
| `hub_call_rule` | Trigger an existing RM rule. `action`: `rule` (full), `actions` (bypass conditions), or `stop` (cancel in-flight). | Built-in App Tools |
| `hub_set_rule_paused` | Pause or resume an RM rule (`value=true` pauses, `value=false` resumes; reversible; paused rules don't fire on triggers). | Built-in App Tools |
| `hub_set_rule_private_boolean` | Set an RM rule's private boolean (true or false only; string values must be lowercase `"true"`/`"false"`). | Built-in App Tools |
| `hub_create_native_app` | Create a new empty classic SmartApp (RM 5.1 by default; `appType` enum extends to other types). Returns `appId`. | Built-in App Tools + Hub Admin Write |
| `hub_update_native_app` | Modify any classic native app by appId. Structured shortcuts: addTrigger, addAction, addRequiredExpression, clearActions, replaceActions, patches, etc. Auto-snapshots before writing. clearActions / replaceActions commit the delete synchronously via a full selectActions page-form submit (RM's trashActs handler runs in-band), so the actions are gone when the call returns; a thin defensive verify-retry returns `partial:true, asyncCommitLikely:true` with `stage` + `safeRecovery` on the rare residual -- verify via `hub_get_app_config` rather than rolling back. | Built-in App Tools + Hub Admin Write |
| `hub_delete_native_app` | Delete a classic native app (auto-snapshot to File Manager before deleting). `force=true` for hard delete. | Built-in App Tools + Hub Admin Write |
| `hub_clone_native_app` | Clone an existing classic SmartApp via Hubitat's `appCloner` endpoint. Returns the new `appId`. | Built-in App Tools + Hub Admin Write |
| `hub_export_native_app` | Export a classic SmartApp to JSON (persists to File Manager), round-trippable with `hub_import_native_app`. Useful for backup, sharing, or export-mutate-import editing of complex rules. | Built-in App Tools + Hub Admin Write |
| `hub_import_native_app` | Import previously-exported app JSON into a new instance. Pairs with `hub_export_native_app`. Returns the new `appId`. | Built-in App Tools + Hub Admin Write |
| `hub_get_rule_health` | Read-only health check on any installed app -- surfaces broken markers, multiple-flag poison, configPage errors. | Built-in App Tools |

### hub_manage_rule_machine (5 tools)

RMUtils-based runtime control for Rule Machine rules: list, trigger, pause/resume, set private boolean, and check health. (CRUD and other classic-app operations live in `hub_manage_native_rules_and_apps`.)

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `hub_list_rules` | List all Rule Machine rules (RM 4.x + 5.x combined, deduplicated by id). | Built-in App Tools |
| `hub_call_rule` | Trigger an existing RM rule. `action`: `rule` (full), `actions` (bypass conditions), or `stop` (cancel in-flight). | Built-in App Tools |
| `hub_set_rule_paused` | Pause or resume an RM rule (`value=true` pauses, `value=false` resumes; reversible; paused rules don't fire on triggers). | Built-in App Tools |
| `hub_set_rule_private_boolean` | Set an RM rule's private boolean (true or false only; string values must be lowercase `"true"`/`"false"`). | Built-in App Tools |
| `hub_get_rule_health` | Read-only health check on any installed app -- surfaces broken markers, multiple-flag poison, configPage errors. | Built-in App Tools |

### hub_manage_mcp (1 tool)

Developer Mode self-administration: tools that let an LLM agent or CI/CD pipeline manage the MCP rule app's own configuration without manual UI intervention. Requires the opt-in `enableDeveloperMode` toggle in the MCP rule app settings (default OFF). Each successful write is logged at WARN level for audit. First gateway under the Developer Mode pattern — additional self-admin tools (device-access management, true Hub Variables namespace support, artifact cleanup) are planned as follow-ups under the same toggle.

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `hub_update_mcp_settings` | Update one or more of the MCP rule app's own settings (toggles, log level, tuning params). Allowlisted: `mcpLogLevel`, `debugLogging`, `maxCapturedStates`, `loopGuardMax`, `loopGuardWindowSec`, `enableRead`, `enableCustomRuleEngine`, `useGateways`. After flipping any `enable*` toggle or `useGateways`, MCP clients may need to reconnect to refresh their cached tool schema. | Developer Mode + Write master + confirm + recent backup |
