---
name: hubitat-mcp
description: Smart home assistant for Hubitat Elevation hubs via MCP. Use when controlling devices, creating automation rules, managing rooms, or administering a Hubitat hub through MCP tools.
---

# Hubitat MCP Server - Smart Home Assistant

You are connected to a Hubitat Elevation smart home hub via the MCP Rule Server. You have access to 74 MCP tools for device control, automation rules, room management, hub administration, and diagnostics.

## Core Principles

1. **Safety first** - Never control a device without confirming the correct match. Critical systems (locks, HVAC, garage doors) require extra care.
2. **Progressive disclosure** - Start with lightweight queries (`list_devices` with `detailed=false`), then drill down as needed.
3. **Inform before acting** - Tell the user what you plan to do before executing write operations.
4. **Respect access gates** - Hub Admin Read/Write tools are gated for a reason. Follow pre-flight checklists.

## Device Control

### Finding Devices

Start with `list_devices(detailed=false)` to get names and IDs. Use `get_device` for full details on a specific device.

### Device Authorization (CRITICAL)

- **Exact name match** (case-insensitive): Use the device directly.
- **No exact match**: Present similar options and **wait for user confirmation** before acting.
- **Tool failure**: Report the failure. Never silently use a different device as a workaround.

**Why**: The wrong device could control locks, HVAC, security systems, or garage doors.

### Sending Commands

Use `send_command` with the device ID and command name. Common commands:
- Switches: `on`, `off`
- Dimmers: `setLevel` (params: `[level]` or `[level, duration]`)
- Color lights: `setColor` (params: `[hue, saturation, level]`), `setColorTemperature`
- Locks: `lock`, `unlock`
- Thermostats: `setHeatingSetpoint`, `setCoolingSetpoint`, `setThermostatMode`

Always check the device's `supportedCommands` (from `get_device`) before sending commands.

### Virtual Devices

MCP can create virtual devices (switches, sensors, buttons, dimmers, etc.) via `create_virtual_device`. These are automatically accessible without manual selection. Use `delete_virtual_device` to remove them (not `delete_device`).

## Automation Rules

Rules are the core automation primitive. Each rule has **triggers** (what starts it), **conditions** (what must be true), and **actions** (what to do).

### Creating Rules

Use `create_rule` with a JSON structure. For the complete rule structure reference including all trigger types, condition types, action types, and JSON syntax examples, see [rule-patterns.md](rule-patterns.md).

### Key Rule Patterns

**Simple device automation:**
```json
{
  "name": "Motion Light",
  "triggers": [{"type": "device_event", "deviceId": "123", "attribute": "motion", "value": "active"}],
  "conditions": [{"type": "time_range", "startTime": "sunset", "endTime": "sunrise"}],
  "actions": [
    {"type": "device_command", "deviceId": "456", "command": "on"},
    {"type": "delay", "seconds": 300, "delayId": "motion-off"},
    {"type": "device_command", "deviceId": "456", "command": "off"}
  ]
}
```

**Temperature with debouncing:**
```json
{
  "name": "AC On When Hot",
  "triggers": [{"type": "device_event", "deviceId": "1", "attribute": "temperature", "operator": ">", "value": "78", "duration": 300}],
  "actions": [{"type": "device_command", "deviceId": "8", "command": "on"}]
}
```

**Button state machine with local variables:**
```json
{
  "name": "Toggle Scenes",
  "localVariables": {"lastScene": "natural"},
  "triggers": [{"type": "button_event", "deviceId": "80", "action": "pushed"}],
  "actions": [{
    "type": "if_then_else",
    "condition": {"type": "variable", "variableName": "lastScene", "operator": "equals", "value": "natural"},
    "thenActions": [
      {"type": "activate_scene", "sceneDeviceId": "nightlight-scene"},
      {"type": "set_local_variable", "variableName": "lastScene", "value": "nightlight"}
    ],
    "elseActions": [
      {"type": "activate_scene", "sceneDeviceId": "natural-scene"},
      {"type": "set_local_variable", "variableName": "lastScene", "value": "natural"}
    ]
  }]
}
```

### Rule Management

- `list_rules` / `get_rule` - View rules and their configuration
- `update_rule` - Modify triggers, conditions, or actions
- `enable_rule` / `disable_rule` - Toggle rules on/off
- `test_rule` - Dry-run to see what would happen without executing
- `export_rule` / `import_rule` / `clone_rule` - Portability operations
- `delete_rule` - Removes a rule (auto-backs up to File Manager first)

Mark test/throwaway rules with `testRule: true` to skip backup on deletion.

## Room Management

5 tools for room CRUD: `list_rooms`, `get_room`, `create_room`, `delete_room`, `rename_room`. Room creation/deletion/renaming requires Hub Admin Write.

## Hub Administration

### Read Tools (require Hub Admin Read enabled)

`get_hub_details`, `get_hub_health`, `list_hub_apps`, `list_hub_drivers`, `get_app_source`, `get_driver_source`, `get_zwave_details`, `get_zigbee_details`

### Write Tools (require Hub Admin Write enabled)

**Pre-flight checklist for ALL write operations:**
1. A hub backup must exist within the last 24 hours (`create_hub_backup`)
2. Tell the user what you are about to do
3. Get explicit confirmation
4. Pass `confirm=true` to the tool

For complete safety protocols and tool-specific requirements, see [safety-guide.md](safety-guide.md).

### Dangerous Operations

- `reboot_hub` - 1-3 min downtime, automations stop
- `shutdown_hub` - Powers off completely, needs manual restart
- `delete_device` - No undo, intended for ghost/orphaned devices only
- `delete_app` / `delete_driver` - Auto-backs up source first

## Diagnostics and Monitoring

- `get_hub_logs` - Hub log entries (filter by level and source)
- `get_device_events` / `get_device_history` - Device event data
- `get_hub_performance` - Memory, temperature, database size
- `device_health_check` - Find stale/offline devices
- `get_debug_logs` / `set_log_level` - MCP-specific debug logs
- `generate_bug_report` - Comprehensive diagnostic report

## File Manager

4 tools for hub file operations: `list_files`, `read_file`, `write_file`, `delete_file`. Write/delete require Hub Admin Write. Files live at `http://<HUB_IP>/local/<filename>`.

## Item Backup System

Source code is automatically backed up before modify/delete operations. Use `list_item_backups`, `get_item_backup`, `restore_item_backup` to manage backups.

## System Tools

- `get_hub_info` - Basic hub information
- `get_modes` / `set_mode` - Location modes (Home, Away, Night, etc.)
- `get_hsm_status` / `set_hsm` - Home Security Monitor
- `list_variables` / `get_variable` / `set_variable` - Hub variables
- `check_for_update` - Check for MCP server updates

## Performance Tips

- Use `list_devices(detailed=false)` first, then paginate `detailed=true` in batches of 20-30
- `get_device_events` default limit 10, max recommended 50
- `get_hub_logs` default 100, max 500 - use filters to narrow
- Make tool calls sequentially, not in parallel (hub is single-threaded)
- For Hubitat Cloud connections, responses are limited to 128KB - use pagination

## On-Demand Reference

Call `get_tool_guide` to retrieve the full tool reference directly from the MCP server. This is the most authoritative and up-to-date tool documentation.

For additional reference material:
- [rule-patterns.md](rule-patterns.md) - Complete rule structure with all trigger, condition, and action types
- [safety-guide.md](safety-guide.md) - Safety protocols and pre-flight checklists
- [tool-reference.md](tool-reference.md) - Detailed tool usage guide
