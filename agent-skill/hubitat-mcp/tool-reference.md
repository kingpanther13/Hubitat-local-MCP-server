# Tool Reference

Quick reference for all 72 MCP tools. The server exposes **31 items on `tools/list`**: 21 core tools + 10 gateway tools. Each gateway proxies additional tools — call with no args for full schemas, or with `tool` and `args` to execute.

For the most authoritative reference, call `get_tool_guide` via MCP.

## Core Tools (21) — Always visible on tools/list

### Device Tools (5)

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `list_devices` | List accessible devices. Use `detailed=false` first, paginate `detailed=true` (limit 20-30). | None |
| `get_device` | Full device details: attributes, commands, capabilities, room. | None |
| `get_attribute` | Get specific attribute value from a device. | None |
| `send_command` | Send a command to a device (on, off, setLevel, etc.). | None |
| `get_device_events` | Recent events for a device. Default 10, recommended max 50. | None |

### Rule Tools (6)

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `list_rules` | List all rules with status, last triggered. | None |
| `get_rule` | Full rule details (triggers, conditions, actions). | None |
| `create_rule` | Create a new automation rule. | None |
| `update_rule` | Update rule triggers, conditions, or actions. | None |
| `enable_rule` | Enable a disabled rule. | None |
| `disable_rule` | Disable a rule without deleting. | None |

### Device Management (1)

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `update_device` | Update device properties (label, name, room, preferences, enabled). | Varies by property |

### Virtual Device Tools (3)

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `create_virtual_device` | Create an MCP-managed virtual device (15 types available). | Hub Admin Write |
| `list_virtual_devices` | List all MCP-managed virtual devices. | None |
| `delete_virtual_device` | Delete an MCP-managed virtual device. | Hub Admin Write |

### System Tools (5)

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `get_hub_info` | Comprehensive hub info (hardware, health, MCP stats) always available; PII/location data (name, IP, timezone, coordinates, zip) requires Hub Admin Read. | None |
| `get_modes` | List location modes. | None |
| `set_mode` | Change location mode (Home, Away, Night, etc.). | None |
| `get_hsm_status` | Get Home Security Monitor status. | None |
| `set_hsm` | Change HSM arm mode. | None |

### Reference (1)

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `get_tool_guide` | Full tool reference from the MCP server itself. | None |

---

## Gateway Tools (10) — Each proxies multiple tools

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

### manage_hub_variables (3 tools)

Manage hub connector and rule engine variables.

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `list_variables` | List all hub connector and rule engine variables. | None |
| `get_variable` | Get a specific variable value. | None |
| `set_variable` | Set a variable value (creates if doesn't exist). | None |

### manage_rooms (5 tools)

Manage hub rooms: list, view details, create, delete, and rename.

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `list_rooms` | List all rooms with device counts. | None |
| `get_room` | Room details with full device info. Accepts name or ID. | None |
| `create_room` | Create a new room. | Hub Admin Write |
| `delete_room` | Delete a room (devices become unassigned). | Hub Admin Write |
| `rename_room` | Rename an existing room. | Hub Admin Write |

### manage_hub_info (3 tools)

Hub information: radio details and update checks.

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `get_zwave_details` | Z-Wave radio info. | Hub Admin Read |
| `get_zigbee_details` | Zigbee radio info. | Hub Admin Read |
| `check_for_update` | Check for MCP server updates. | None |

### manage_hub_maintenance (5 tools)

Hub maintenance: backups, reboot, shutdown, Z-Wave repair, and device deletion.

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `create_hub_backup` | Create full hub database backup. | Hub Admin Write |
| `reboot_hub` | Reboot hub (1-3 min downtime). | Hub Admin Write |
| `shutdown_hub` | Power off hub (needs manual restart). | Hub Admin Write |
| `zwave_repair` | Start Z-Wave network repair (5-30 min). | Hub Admin Write |
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

### manage_code_changes (7 tools)

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

### manage_logs (6 tools)

Hub and MCP log access and configuration.

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `get_hub_logs` | Hub log entries. Default 100, max 500. Use filters. | Hub Admin Read |
| `get_device_history` | Up to 7 days of device event history. | Hub Admin Read |
| `get_debug_logs` | Retrieve MCP debug log entries. Filter by level. | None |
| `clear_debug_logs` | Clear all MCP debug logs. | None |
| `set_log_level` | Set MCP log level (debug/info/warn/error). | None |
| `get_logging_status` | View logging system statistics. | None |

### manage_diagnostics (7 tools)

Performance monitoring, health checks, diagnostics, and state capture.

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `get_hub_performance` | Memory, temperature, database size. | Hub Admin Read |
| `device_health_check` | Find stale/offline devices. | Hub Admin Read |
| `get_rule_diagnostics` | Comprehensive diagnostics for a specific rule. | None |
| `generate_bug_report` | Generate comprehensive diagnostic report. | None |
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
