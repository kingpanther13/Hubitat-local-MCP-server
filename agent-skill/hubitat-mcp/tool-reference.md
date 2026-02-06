# Tool Reference

Quick reference for all 74 MCP tools organized by category. For the most authoritative reference, call `get_tool_guide` via MCP.

## Device Tools (5)

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `list_devices` | List accessible devices. Use `detailed=false` first, paginate `detailed=true` (limit 20-30). | None |
| `get_device` | Full device details: attributes, commands, capabilities, room. | None |
| `get_attribute` | Get specific attribute value from a device. | None |
| `send_command` | Send a command to a device (on, off, setLevel, etc.). | None |
| `get_device_events` | Recent events for a device. Default 10, recommended max 50. | None |

## Virtual Device Tools (4)

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `create_virtual_device` | Create an MCP-managed virtual device (15 types available). | Hub Admin Write |
| `list_virtual_devices` | List all MCP-managed virtual devices. | None |
| `delete_virtual_device` | Delete an MCP-managed virtual device. | Hub Admin Write |
| `update_device` | Update device properties (label, name, room, preferences, enabled). | Varies by property |

## Room Tools (5)

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `list_rooms` | List all rooms with device counts. | None |
| `get_room` | Room details with full device info. Accepts name or ID. | None |
| `create_room` | Create a new room. | Hub Admin Write |
| `delete_room` | Delete a room (devices become unassigned). | Hub Admin Write |
| `rename_room` | Rename an existing room. | Hub Admin Write |

## Rule Tools (11)

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `list_rules` | List all rules with status, last triggered. | None |
| `get_rule` | Full rule details (triggers, conditions, actions). | None |
| `create_rule` | Create a new automation rule. | None |
| `update_rule` | Update rule triggers, conditions, or actions. | None |
| `delete_rule` | Delete a rule (auto-backs up first). | None |
| `enable_rule` | Enable a disabled rule. | None |
| `disable_rule` | Disable a rule without deleting. | None |
| `test_rule` | Dry-run: see what would happen without executing. | None |
| `export_rule` | Export rule as portable JSON. | None |
| `import_rule` | Import a rule from exported JSON. | None |
| `clone_rule` | Duplicate an existing rule. | None |

## System Tools (9)

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `get_hub_info` | Basic hub information. | None |
| `get_modes` | List location modes. | None |
| `set_mode` | Change location mode (Home, Away, Night, etc.). | None |
| `get_hsm_status` | Get Home Security Monitor status. | None |
| `set_hsm` | Change HSM arm mode. | None |
| `list_variables` | List all hub variables. | None |
| `get_variable` | Get a specific variable value. | None |
| `set_variable` | Set a variable value. | None |
| `check_for_update` | Check for MCP server updates. | None |

## State Capture Tools (3)

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `list_captured_states` | List saved device state snapshots. | None |
| `delete_captured_state` | Delete a specific state snapshot. | None |
| `clear_captured_states` | Delete all state snapshots. | None |

## Debug/Diagnostics Tools (6)

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `get_debug_logs` | Retrieve MCP debug log entries. Filter by level. | None |
| `clear_debug_logs` | Clear all MCP debug logs. | None |
| `get_rule_diagnostics` | Comprehensive diagnostics for a specific rule. | None |
| `set_log_level` | Set MCP log level (debug/info/warn/error). | None |
| `get_logging_status` | View logging system statistics. | None |
| `generate_bug_report` | Generate comprehensive diagnostic report. | None |

## Monitoring Tools (4)

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `get_hub_logs` | Hub log entries. Default 100, max 500. Use filters. | Hub Admin Read |
| `get_device_history` | Up to 7 days of device event history. | Hub Admin Read |
| `get_hub_performance` | Memory, temperature, database size. | Hub Admin Read |
| `device_health_check` | Find stale/offline devices. | Hub Admin Read |

## Hub Admin Read Tools (8)

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `get_hub_details` | Model, firmware, IP, uptime, memory, temp, database. | Hub Admin Read |
| `list_hub_apps` | List installed user apps. | Hub Admin Read |
| `list_hub_drivers` | List installed user drivers. | Hub Admin Read |
| `get_zwave_details` | Z-Wave radio info. | Hub Admin Read |
| `get_zigbee_details` | Zigbee radio info. | Hub Admin Read |
| `get_hub_health` | Health dashboard with warnings. | Hub Admin Read |
| `get_app_source` | Get app source code. Large files auto-saved to File Manager. | Hub Admin Read |
| `get_driver_source` | Get driver source code. Large files auto-saved to File Manager. | Hub Admin Read |

## Hub Admin Write Tools (10)

All require: Hub Admin Write enabled + backup within 24h + confirm=true.

| Tool | Description |
|------|-------------|
| `create_hub_backup` | Create full hub database backup. |
| `reboot_hub` | Reboot hub (1-3 min downtime). |
| `shutdown_hub` | Power off hub (needs manual restart). |
| `zwave_repair` | Start Z-Wave network repair (5-30 min). |
| `install_app` | Install a new Groovy app from source. |
| `install_driver` | Install a new Groovy driver from source. |
| `update_app_code` | Update existing app source code. |
| `update_driver_code` | Update existing driver source code. |
| `delete_app` | Delete an installed app (auto-backs up). |
| `delete_driver` | Delete an installed driver (auto-backs up). |

## Device Admin Tools (1)

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `delete_device` | Permanently delete a device. NO UNDO. For ghost/orphaned devices only. | Hub Admin Write |

## Item Backup Tools (3)

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `list_item_backups` | List all source code backups. | None |
| `get_item_backup` | Retrieve source from a backup. | None |
| `restore_item_backup` | Restore app/driver to backed-up version. | Hub Admin Write |

## File Manager Tools (4)

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `list_files` | List all files in File Manager. | None |
| `read_file` | Read a file (inline for <60KB, URL for larger). | None |
| `write_file` | Create/update a file (auto-backs up existing). | Hub Admin Write |
| `delete_file` | Delete a file (auto-backs up first). | Hub Admin Write |

## Reference Tools (1)

| Tool | Description | Access Gate |
|------|-------------|-------------|
| `get_tool_guide` | Full tool reference from the MCP server itself. | None |
