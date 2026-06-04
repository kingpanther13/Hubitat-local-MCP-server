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

## Gateway Calling Convention (v0.8.0+)

Many safety-critical tools are now accessed via gateways (e.g., `hub_manage_destructive_ops`, `hub_manage_code`). The gateway does **not** bypass any safety checks — the Read/Write master gates, backup requirements, and confirm flags are all enforced. A gateway name routes back through `executeTool()`, which re-applies the central master gate to each sub-tool and then runs the handler. When calling a tool through a gateway, the same pre-flight checklists apply.

Example: To reboot the hub, call `hub_manage_destructive_ops(tool="hub_reboot", args={"confirm": true})` — the same backup and confirmation requirements apply as if `hub_reboot` were called directly.

---

## Permission Model

Every MCP tool is gated by two universal masters, **Read** and **Write**, both **ON by default**:

- **Read** master exposes every read-only / non-destructive tool. With it OFF, those tools vanish from the client and a cached call is rejected with "Read tools are disabled…".
- **Write** master exposes every state-changing tool (device control, modes, variables, rooms, files, native rules, hub admin). With it OFF, those tools vanish and a cached call is rejected with "Write tools are disabled…".

Both are enforced centrally at dispatch — no tool slips past. Turning a master OFF removes that entire class; only an explicit OFF blocks (the default-unset state is ON).

**Destructive write tools require more.** Beyond the Write master, the destructive/sensitive tools (reboot, shutdown, delete-device, file write/delete, native-rule CRUD, etc.) still demand `confirm=true` **and** a hub backup within 24h — see the pre-flight checklist below.

**Advanced per-tool / per-gateway overrides (deny-only).** Under *Advanced: Per-tool Overrides*, individual tools or whole gateways can be disabled below the masters. A disabled tool drops from `tools/list` and `hub_search_tools` everywhere it appears (including a tool shared across gateways, and every tool inside a disabled gateway), and a cached call returns a distinct error: "…is disabled in Advanced settings (Per-tool Overrides)…". A disabled tool still appears in `hub_get_tool_guide` documentation. These overrides can only turn things OFF — they never re-enable something a master already hid.

---

## Destructive Write - Pre-Flight Checklist

ALL destructive write tools (`confirm`+backup tier) require these steps in order:

1. **Verify backup**: `hub_create_backup` was called within the last 24 hours
2. **Inform user**: Explain what you are about to do and its effects
3. **Get confirmation**: Wait for explicit "yes", "confirm", or "proceed"
4. **Execute with confirm=true**: Pass the confirmation flag

### Tool-Specific Safety

#### hub_reboot
- **Effects**: 1-3 minute downtime, all automations stop, scheduled jobs lost, Z-Wave/Zigbee radios restart
- **Only when**: User explicitly requests a reboot
- **Never**: Reboot as a troubleshooting step without asking

#### hub_shutdown
- **Effects**: Hub powers OFF completely. Requires physical power cycle to restart.
- **This is NOT a reboot** - the hub stays off until someone manually unplugs and replugs it
- **Only when**: User explicitly requests shutdown (e.g., for maintenance or moving the hub)

#### hub_call_zwave_repair (via hub_manage_diagnostics)
- **Effects**: 5-30 minute background process, Z-Wave devices may be unresponsive during
- **Best run**: During off-peak hours when automations aren't critical
- **Only when**: User reports Z-Wave issues and explicitly requests repair

#### hub_delete_device (via hub_manage_destructive_ops)
- **THE MOST DESTRUCTIVE TOOL - NO UNDO**
- **Intended for**: Ghost/orphaned Z-Wave nodes, stale database records, stuck virtual devices
- **Pre-flight**:
  1. Use `hub_get_device` to verify correct device
  2. Check for recent activity (warn if device was active recently)
  3. For Z-Wave/Zigbee: warn user to do proper exclusion/removal first
  4. All device details logged to MCP debug logs for audit trail
- **Never**: Delete a working device. If user wants to remove a device, guide them through proper exclusion first.

#### hub_delete_item (type: app | driver | library)
- Source code is auto-backed up before deletion
- For apps (`type="app"`): Remind user to remove app instances via Hubitat UI first
- For drivers (`type="driver"`): Remind user to switch devices to a different driver first
- For libraries (`type="library"`): Check that no apps or drivers reference the library via `#include namespace.LibraryName` before deleting -- deletion breaks any code that still includes it
- Deletion is permanent; restore the source via `hub_update_app` / `hub_update_driver` / `hub_update_library` with the backup source

#### hub_delete_room
- Devices become unassigned (not deleted)
- List affected devices to the user before proceeding
- Dashboard layouts referencing the room may be affected

#### hub_create_app / hub_create_driver
- Verify source code looks reasonable before installing
- Warn about namespace conflicts with existing apps/drivers

#### hub_create_library
- Verify source includes a valid `library()` definition block before installing
- Warn about namespace conflicts with existing libraries (`#include namespace.LibraryName` references must match exactly)

#### hub_update_app / hub_update_driver
- Source is auto-backed up before update (1-hour protection window preserves original)
- Uses optimistic locking to prevent concurrent edit conflicts
- Supports three modes: `source` (direct), `sourceFile` (from File Manager), `resave` (recompile)

#### hub_update_library
- Source is auto-backed up before update (1-hour protection window preserves original); backup failure aborts the update
- Uses optimistic locking to prevent concurrent edit conflicts
- Supports three modes: `source` (direct), `sourceFile` (from File Manager), `resave` (recompile)

---

## Virtual Device Safety

- Use `hub_manage_virtual_device` with `action="create"` or `action="delete"` for MCP-managed virtual devices
- Do NOT use `hub_delete_device` for virtual devices created by MCP
- Virtual devices created by MCP are automatically accessible to all device tools
- 15 supported types: Virtual Switch, Button, Contact Sensor, Motion Sensor, Presence, Lock, Temperature Sensor, Humidity Sensor, Dimmer, RGBW Light, Shade, Garage Door Opener, Water Sensor, Omni Sensor, Fan Controller

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

- `hub_delete_custom_rule` auto-backs up the rule to File Manager before deletion
- Backup format: `mcp_rule_backup_<name>_<timestamp>.json`
- Restore via: `hub_read_file(fileName)` then `hub_import_custom_rule(exportData: <json>)`
- Mark test rules with `testRule: true` to skip backup on deletion

---

## File Manager Safety

- `hub_write_file` auto-backs up existing file before overwriting
- `hub_delete_file` auto-backs up file before deletion
- Both require the Write master ON + confirm + a recent backup
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
2. Find backup files (named `mcp-backup-app-<id>.groovy`, `mcp-backup-driver-<id>.groovy`, or `mcp-backup-library-<id>.groovy`)
3. Download the file
4. Go to Apps Code (or Drivers Code, or FOR DEVELOPERS > Libraries code) > select the item > paste source > Save

---

## General Guidelines

- **Make tool calls sequentially** - The Hubitat hub is single-threaded; parallel calls can cause timeouts
- **Hubitat Cloud has a 128KB response limit** - Use pagination for large data
- **Duration triggers max out at 2 hours** (7200 seconds)
- **Captured states default to 20 max** (configurable 1-100)
- **Hub properties can throw on some firmware versions** - This is normal; the tools handle it gracefully

## Tool annotation hints

Every tool now carries four annotation hints in its definition: `readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint`. These are UX / risk signals to the MCP client — they help a client decide how to render the tool, whether to confirm before calling, and how to interpret retries.

**Annotations are NOT a security boundary.** They don't make the model resist prompt injection or substitute for the universal Read / Write master gates and `confirm` parameter checks. The annotation is the tool's read/write *declaration*; the masters are the *enforcement*. The gates remain the authoritative safety surface; annotations are advisory metadata.
