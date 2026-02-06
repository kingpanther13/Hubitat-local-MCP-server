# Safety Guide

Safety protocols and pre-flight checklists for Hubitat MCP Server operations.

## Device Authorization

### The Golden Rules

1. **Exact match = proceed**: If the user says "Kitchen Light" and a device named "Kitchen Light" exists, use it.
2. **Fuzzy match = ask first**: If no exact match, present similar options and wait for confirmation.
3. **Failure = report**: If a tool fails, tell the user. Never silently use an alternative device.

### Why This Matters

Smart homes contain critical infrastructure:
- **Locks** - Unauthorized unlock is a security risk
- **HVAC** - Wrong thermostat settings can cause damage
- **Garage doors** - Opening unexpectedly is dangerous
- **Irrigation** - Running at wrong times wastes water or floods
- **Security systems** - Disarming without intent leaves the home unprotected

Always confirm device identity before acting on critical systems.

---

## Hub Admin Write - Pre-Flight Checklist

ALL Hub Admin Write tools require these steps in order:

1. **Verify backup**: `create_hub_backup` was called within the last 24 hours
2. **Inform user**: Explain what you are about to do and its effects
3. **Get confirmation**: Wait for explicit "yes", "confirm", or "proceed"
4. **Execute with confirm=true**: Pass the confirmation flag

### Tool-Specific Safety

#### reboot_hub
- **Effects**: 1-3 minute downtime, all automations stop, scheduled jobs lost, Z-Wave/Zigbee radios restart
- **Only when**: User explicitly requests a reboot
- **Never**: Reboot as a troubleshooting step without asking

#### shutdown_hub
- **Effects**: Hub powers OFF completely. Requires physical power cycle to restart.
- **This is NOT a reboot** - the hub stays off until someone manually unplugs and replugs it
- **Only when**: User explicitly requests shutdown (e.g., for maintenance or moving the hub)

#### zwave_repair
- **Effects**: 5-30 minute background process, Z-Wave devices may be unresponsive during
- **Best run**: During off-peak hours when automations aren't critical
- **Only when**: User reports Z-Wave issues and explicitly requests repair

#### delete_device
- **THE MOST DESTRUCTIVE TOOL - NO UNDO**
- **Intended for**: Ghost/orphaned Z-Wave nodes, stale database records, stuck virtual devices
- **Pre-flight**:
  1. Use `get_device` to verify correct device
  2. Check for recent activity (warn if device was active recently)
  3. For Z-Wave/Zigbee: warn user to do proper exclusion/removal first
  4. All device details logged to MCP debug logs for audit trail
- **Never**: Delete a working device. If user wants to remove a device, guide them through proper exclusion first.

#### delete_app / delete_driver
- Source code is auto-backed up before deletion
- For apps: Remind user to remove app instances via Hubitat UI first
- For drivers: Remind user to switch devices to a different driver first

#### delete_room
- Devices become unassigned (not deleted)
- List affected devices to the user before proceeding
- Dashboard layouts referencing the room may be affected

#### install_app / install_driver
- Verify source code looks reasonable before installing
- Warn about namespace conflicts with existing apps/drivers

#### update_app_code / update_driver_code
- Source is auto-backed up before update (1-hour protection window preserves original)
- Uses optimistic locking to prevent concurrent edit conflicts
- Supports three modes: `source` (direct), `sourceFile` (from File Manager), `resave` (recompile)

---

## Virtual Device Safety

- Use `create_virtual_device` / `delete_virtual_device` for MCP-managed virtual devices
- Do NOT use `delete_device` for virtual devices created by MCP
- Virtual devices created by MCP are automatically accessible to all device tools
- 15 supported types: Virtual Switch, Button, Contact Sensor, Motion Sensor, Presence Sensor, Lock, Temperature Sensor, Humidity Sensor, Dimmer, RGBW Light, Shade, Garage Door Opener, Water Sensor, Omni Sensor, Fan Controller

---

## Rule Safety

### Execution Loop Guard

The rule engine has an automatic loop guard that detects infinite event loops (e.g., "when Switch A turns on, turn on Switch A"). If a rule fires too many times within a sliding window, it automatically:
1. Disables the rule
2. Unsubscribes from all events
3. Sends push notifications to configured devices
4. Fires a `mcpLoopGuard` location event

The rule must be manually re-enabled after fixing the loop logic.

Defaults: 30 executions within 60 seconds. Configurable in app settings.

### Rule Deletion

- `delete_rule` auto-backs up the rule to File Manager before deletion
- Backup format: `mcp_rule_backup_<name>_<timestamp>.json`
- Restore via: `read_file(fileName)` then `import_rule(exportData: <json>)`
- Mark test rules with `testRule: true` to skip backup on deletion

---

## File Manager Safety

- `write_file` auto-backs up existing file before overwriting
- `delete_file` auto-backs up file before deletion
- Both require Hub Admin Write + confirm
- File names must match `^[A-Za-z0-9][A-Za-z0-9._-]*$` (no spaces, no leading period)
- Files persist on hub even if MCP is uninstalled

---

## Item Backup System

Source code backups are created automatically before modify/delete operations.

- Stored in File Manager as `.groovy` files
- Max 20 backups kept (oldest pruned automatically)
- 1-hour protection: rapid edits preserve the pre-edit original
- Backups persist even if MCP is uninstalled
- Accessible at `http://<HUB_IP>/local/<filename>`

### Manual Recovery (Without MCP)

If MCP itself is broken:
1. Go to Hubitat web UI > Settings > File Manager
2. Find backup files (named `mcp-backup-app-<id>.groovy` or `mcp-backup-driver-<id>.groovy`)
3. Download the file
4. Go to Apps Code (or Drivers Code) > select the item > paste source > Save

---

## General Guidelines

- **Make tool calls sequentially** - The Hubitat hub is single-threaded; parallel calls can cause timeouts
- **Hubitat Cloud has a 128KB response limit** - Use pagination for large data
- **Duration triggers max out at 2 hours** (7200 seconds)
- **Captured states default to 20 max** (configurable 1-100)
- **Hub properties can throw on some firmware versions** - This is normal; the tools handle it gracefully
