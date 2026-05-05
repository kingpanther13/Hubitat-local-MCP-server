# Tool Reference

Quick reference for all 88 MCP tools. The server exposes **34 items on `tools/list`**: 22 core tools + 12 gateway tools. Each gateway proxies additional tools — call with no args for full schemas, or with `tool` and `args` to execute.

For the most authoritative reference, call `get_tool_guide` via MCP.

## Core Tools (22) — Always visible on tools/list

### Device Tools (5)

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `list_devices` | List accessible devices. Use `detailed=false` first, paginate `detailed=true` (limit 20-30). | None |
| `get_device` | Full device details: attributes, commands, capabilities, room. | None |
| `get_attribute` | Get specific attribute value from a device. | None |
| `send_command` | Send a command to a device (on, off, setLevel, etc.). | None |
| `get_device_events` | Recent events for a device. Default 10, recommended max 50. | None |

### Rule Tools (4)

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `list_rules` | List all rules with status, last triggered. | None |
| `get_rule` | Full rule details (triggers, conditions, actions). | None |
| `create_rule` | Create a new automation rule. | None |
| `update_rule` | Update rule triggers, conditions, or actions. Also handles enable/disable via `enabled=true/false`. | None |

### Device Management (1)

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `update_device` | Update device properties (label, name, room, preferences, enabled). | Varies by property |

### Virtual Device Tools (2)

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `manage_virtual_device` | Create or delete MCP-managed virtual devices (action="create"/"delete", 15 types available). | Hub Admin Write |
| `list_virtual_devices` | List all MCP-managed virtual devices. | None |

### System Tools (8)

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `get_hub_info` | Comprehensive hub info (hardware, health, MCP stats) always available; PII/location data (name, IP, timezone, coordinates, zip) requires Hub Admin Read. | None |
| `get_modes` | List location modes. | None |
| `set_mode` | Change location mode (Home, Away, Night, etc.). | None |
| `get_hsm_status` | Get Home Security Monitor status. | None |
| `set_hsm` | Change HSM arm mode. | None |
| `create_hub_backup` | Create full hub database backup. | Hub Admin Write |
| `check_for_update` | Check for MCP server updates. | None |
| `generate_bug_report` | Generate comprehensive diagnostic report. | None |

### Reference (2)

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `get_tool_guide` | Full tool reference from the MCP server itself. | None |
| `search_tools` | BM25 natural language search across all 88 tools — returns matching tools ranked by relevance, with gateway attribution so the AI knows how to call each. | None |

---

## Gateway Tools (12) — Each proxies multiple tools

Call a gateway with no arguments to see full parameter schemas for all its tools. Call with `tool='<name>'` and `args={...}` to execute a specific tool.

### manage_rules_admin (5 tools)

Rule administration: delete, test, export, import, and clone rules.

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `delete_rule` | Delete a rule (auto-backs up first). | None |
| `test_rule` | Dry-run: see what would happen without executing. | None |
| `export_rule` | Export rule as portable JSON. | None |
| `import_rule` | Import a rule from exported JSON. | None |
| `clone_rule` | Duplicate an existing rule. | None |

### manage_hub_variables (4 tools)

Manage hub connector and rule engine variables.

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `list_variables` | List all hub connector and rule engine variables. | None |
| `get_variable` | Get a specific variable value. | None |
| `set_variable` | Set a variable value (creates if doesn't exist). | None |
| `delete_variable` | Permanently delete a rule engine variable (DESTRUCTIVE — no undo). Connector-namespace deletion not yet supported via MCP. | Hub Admin Write + recent backup |

### manage_rooms (5 tools)

Manage hub rooms: list, view details, create, delete, and rename.

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `list_rooms` | List all rooms with device counts. | None |
| `get_room` | Room details with full device info. Accepts name or ID. | None |
| `create_room` | Create a new room. | Hub Admin Write |
| `delete_room` | Delete a room (devices become unassigned). | Hub Admin Write |
| `rename_room` | Rename an existing room. | Hub Admin Write |

### manage_destructive_hub_ops (3 tools)

Destructive hub operations: reboot, shutdown, and device deletion.

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `reboot_hub` | Reboot hub (1-3 min downtime). | Hub Admin Write |
| `shutdown_hub` | Power off hub (needs manual restart). | Hub Admin Write |
| `delete_device` | Permanently delete a device. **NO UNDO.** For ghost/orphaned devices only. | Hub Admin Write |

### manage_apps_drivers (6 tools)

Read-only access to hub apps and drivers: list, view source, and browse backups.

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `list_hub_apps` | List installed user apps. | Hub Admin Read |
| `list_hub_drivers` | List installed user drivers. | Hub Admin Read |
| `get_app_source` | Get app source code. Large files auto-saved to File Manager. | Hub Admin Read |
| `get_driver_source` | Get driver source code. Large files auto-saved to File Manager. | Hub Admin Read |
| `list_item_backups` | List all source code backups. | None |
| `get_item_backup` | Retrieve source from a backup. | None |

### manage_app_driver_code (7 tools)

Write operations for apps and drivers: install, update, delete, and restore code.

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `install_app` | Install a new Groovy app from source. | Hub Admin Write |
| `install_driver` | Install a new Groovy driver from source. | Hub Admin Write |
| `update_app_code` | Update existing app source code. | Hub Admin Write |
| `update_driver_code` | Update existing driver source code. | Hub Admin Write |
| `delete_app` | Delete an installed app (auto-backs up). | Hub Admin Write |
| `delete_driver` | Delete an installed driver (auto-backs up). | Hub Admin Write |
| `restore_item_backup` | Restore app/driver to backed-up version. | Hub Admin Write |

### manage_logs (8 tools)

Hub and MCP log access, performance stats, and log configuration.

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `get_hub_logs` | Hub log entries, most recent first. Default 100, max 500. Filter by level/source, or scope to a single `deviceId` / `appId` (server-side). | Hub Admin Read |
| `get_device_history` | Up to 7 days of device event history. | Hub Admin Read |
| `get_performance_stats` | Device/app performance stats from `/logs`: method call counts, % busy, cumulative total ms, state size, events. Sortable. | Hub Admin Read |
| `get_hub_jobs` | Scheduled and running jobs on the hub. | Hub Admin Read |
| `get_debug_logs` | Retrieve MCP debug log entries. Filter by level. | None |
| `clear_debug_logs` | Clear all MCP debug logs. | None |
| `set_log_level` | Set MCP log level (debug/info/warn/error). | None |
| `get_logging_status` | View logging system statistics. | None |

### manage_diagnostics (11 tools)

Performance monitoring, health checks, diagnostics, radio info, memory / GC, and state capture.

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `get_set_hub_metrics` | Record/retrieve hub metrics with CSV trend history. | Hub Admin Read |
| `device_health_check` | Find stale/offline devices. | Hub Admin Read |
| `get_rule_diagnostics` | Comprehensive diagnostics for a specific rule. | None |
| `get_zwave_details` | Z-Wave radio info. | Hub Admin Read |
| `get_zigbee_details` | Zigbee radio info. | Hub Admin Read |
| `get_memory_history` | Free OS memory + CPU load history (with Java heap + NIO buffer tracking for leak detection). | Hub Admin Read |
| `force_garbage_collection` | Force JVM GC and return before/after memory comparison. | Hub Admin Read |
| `zwave_repair` | Start Z-Wave network repair (5-30 min). | Hub Admin Write |
| `list_captured_states` | List saved device state snapshots. | None |
| `delete_captured_state` | Delete a specific state snapshot. | None |
| `clear_captured_states` | Delete all state snapshots. | None |

### manage_files (4 tools)

Manage hub File Manager: list, read, write, and delete files stored on the hub.

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `list_files` | List all files in File Manager. | None |
| `read_file` | Read a file (inline for <60KB, URL for larger). | None |
| `write_file` | Create/update a file (auto-backs up existing). | Hub Admin Write |
| `delete_file` | Delete a file (auto-backs up first). | Hub Admin Write |

### manage_installed_apps (4 tools)

Read-only visibility into all installed apps (built-in + user): enumerate with parent/child tree, find apps using a specific device, inspect an app's configuration page, discover page names for multi-page apps.

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `list_installed_apps` | Enumerate all apps on the hub (built-in + user) with parent/child tree. Filter by `all`/`builtin`/`user`/`disabled`/`parents`/`children`. | Built-in App Read |
| `get_device_in_use_by` | Given a `deviceId`, list apps referencing it (Room Lighting, Rule Machine, Groups, Mode Manager, dashboards, Maker API, etc.). | Built-in App Read |
| `get_app_config` | Read an installed app's configuration page (Rule Machine, Room Lighting, Basic Rules, HPM, etc.). Returns sections/inputs/values; multi-page apps via `pageName`. Workflow: list_installed_apps or list_rm_rules -> get_app_config with appId; multi-page apps accept pageName (HPM: prefPkgUninstall for full list). Read-only. | Hub Admin Read |
| `list_app_pages` | List known page names for a multi-page app (HPM, Room Lighting, etc.). Returns curated directory + live primary page. Use before get_app_config on multi-page apps. | Hub Admin Read |

### manage_rule_machine (5 tools)

Rule Machine interop via the official `hubitat.helper.RMUtils` helper class: list, trigger, pause/resume, and set Private Boolean on existing RM rules. **Cannot create, modify, or delete RM rules** — Hubitat platform blocks third-party apps from mutating built-in app children; use the native RM UI for configuration. Requires Built-in App Tools enabled.

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `list_rm_rules` | List all Rule Machine rules (RM 4.x + 5.x combined, deduplicated by id). | Built-in App Read |
| `run_rm_rule` | Trigger an existing RM rule. `action`: `rule` (full), `actions` (bypass conditions), or `stop` (cancel in-flight). | Built-in App Read |
| `pause_rm_rule` | Pause an RM rule (reversible; paused rules don't fire on triggers). | Built-in App Read |
| `resume_rm_rule` | Resume a paused RM rule. | Built-in App Read |
| `set_rm_rule_boolean` | Set an RM rule's private boolean (true or false only; string values must be lowercase `"true"`/`"false"`) — flips the flag that rules can reference in conditions. | Built-in App Read |

### manage_mcp_self (1 tool)

Developer Mode self-administration: tools that let an LLM agent or CI/CD pipeline manage the MCP rule app's own configuration without manual UI intervention. Requires the opt-in `enableDeveloperMode` toggle in the MCP rule app settings (default OFF). Each successful write is logged at WARN level for audit. First gateway under the Developer Mode pattern — additional self-admin tools (device-access management, true Hub Variables namespace support, artifact cleanup) are planned as follow-ups under the same toggle.

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `update_mcp_settings` | Update one or more of the MCP rule app's own settings (toggles, log level, tuning params). Allowlisted: `mcpLogLevel`, `debugLogging`, `maxCapturedStates`, `loopGuardMax`, `loopGuardWindowSec`, `enableHubAdminRead`, `enableBuiltinAppRead`, `enableRuleEngine`. After flipping any `enable*` toggle, MCP clients may need to reconnect to refresh their cached tool schema. | Developer Mode + Hub Admin Write + recent backup |
