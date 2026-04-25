# MCP Tool Guide

Detailed reference for MCP Rule Server tools. Consult this when tool descriptions need clarification.

## Category Gateway Proxy (v0.8.0+)

As of v0.8.0, the server uses **domain-named gateways** to organize lesser-used tools behind gateway tools. The MCP `tools/list` shows 33 items (22 core + 11 gateways) covering 83 total tools. Use `search_tools` to find any tool by natural language query.

**How to use a gateway:**
1. Call the gateway with no arguments to see full parameter schemas for all its tools
2. Call with `tool='<tool_name>'` and `args={...}` to execute a specific tool

**Gateways:** `manage_rules_admin` (5), `manage_hub_variables` (3), `manage_rooms` (5), `manage_destructive_hub_ops` (3), `manage_apps_drivers` (6), `manage_app_driver_code` (7), `manage_logs` (8), `manage_diagnostics` (11), `manage_files` (4), `manage_installed_apps` (4), `manage_rule_machine` (5)

All safety gates (Hub Admin Read/Write, confirm, backup checks) are preserved — they are enforced in the handler functions, not the dispatch layer.

### Disabling Gateways (Flat Tool List)

Gateways exist because most MCP clients struggle with long tool lists. Some clients now ship their own progressive-disclosure layer (deferred tools, built-in BM25 search, etc.) and don't need ours. For those, the **Consolidate tools behind category gateways** setting in the app preferences can be turned off.

The total tool inventory stays at 83 either way — only the *advertised* `tools/list` shape changes:

- **Gateways ON (default):** 33 entries (22 core + 11 gateways); the other 61 tools are reached via gateway dispatch.
- **Gateways OFF:** 82 entries — every tool except `search_tools`, which is hidden because its only purpose is finding tools hidden behind gateways.

Default is **ON** (gateways enabled). Existing installations keep the gateway behavior on update. If a stale or cached client tries to call a gateway name (e.g. `manage_rooms`) while the toggle is off, the server returns a soft `isError` pointing at the underlying sub-tools rather than silently servicing the call with a gateway-shaped response.

## Device Authorization (CRITICAL)

**Exact match rule:**
- If user specifies a device name that EXACTLY matches a device label (case-insensitive OK), use it directly
- Example: User says "turn on Kitchen Light" and device "Kitchen Light" exists → use it

**Non-exact match rule:**
- If no exact match exists, search for similar devices
- Present options to user and **wait for explicit confirmation** before using any device
- Example: User says "use test switch" but only "Virtual Test Switch" exists → ask "Did you mean 'Virtual Test Switch'?"

**Tool failure rule:**
- If a tool fails (e.g., `manage_virtual_device` returns an error), report the failure to the user
- Do NOT silently fall back to using existing devices as a workaround
- Example: If creating a virtual device fails, don't just grab an existing device to use instead

**Why this matters:**
- Wrong device could control critical systems (HVAC, locks, security)
- User trust depends on AI only controlling what they explicitly authorized

---

## Hub Admin Write Tools - Pre-Flight Checklist

All Hub Admin Write tools require these steps:

1. **Backup check**: Ensure `create_hub_backup` was called within the last 24 hours
2. **Inform user**: Tell them what you're about to do
3. **Get confirmation**: Wait for explicit "yes", "confirm", or "proceed"
4. **Set confirm=true**: Pass the confirm parameter

### Tool-Specific Requirements

**reboot_hub** (via `manage_destructive_hub_ops`)
- Effects: 1-3 min downtime, all automations stop, scheduled jobs lost, radios restart
- Only use when user explicitly requests a reboot

**shutdown_hub** (via `manage_destructive_hub_ops`)
- Effects: Powers OFF completely, requires physical restart (unplug/replug)
- This is NOT a reboot - hub stays off until manually restarted
- Only use when user explicitly requests shutdown

**delete_device** (via `manage_destructive_hub_ops`)
- MOST DESTRUCTIVE tool - NO UNDO
- Intended for: ghost/orphaned devices, stale DB records, stuck virtual devices
- Additional steps: Use `get_device` to verify correct device, warn if recent activity
- For Z-Wave/Zigbee: Warn user to do proper exclusion first to avoid ghost nodes
- All device details logged to MCP debug logs for audit

**zwave_repair** (via `manage_diagnostics`)
- Effects: 5-30 min duration, Z-Wave devices may be unresponsive
- Best run during off-peak hours
- Only use when user explicitly requests Z-Wave repair

**delete_room** (via `manage_rooms`)
- Devices become unassigned (not deleted)
- List affected devices to user before proceeding
- Dashboard layouts and automations referencing room may be affected

**delete_app / delete_driver** (via `manage_app_driver_code`)
- Remove app instances via Hubitat UI first (for apps)
- Change devices to different driver first (for drivers)
- Source code auto-backed up before deletion

---

## Virtual Device Types

When using `manage_virtual_device` (action: "create"), these types are available:

| Type | Description | Common Use Case |
|------|-------------|-----------------|
| Virtual Switch | on/off toggle | Boolean flags, triggers |
| Virtual Button | pushable button | Triggering automations |
| Virtual Contact Sensor | open/closed | Simulate door/window state |
| Virtual Motion Sensor | active/inactive | Simulate motion detection |
| Virtual Presence Sensor | present/not present | Presence simulation |
| Virtual Lock | lock/unlock | Lock state simulation |
| Virtual Temperature Sensor | numeric temp | Temperature reporting |
| Virtual Humidity Sensor | numeric humidity | Humidity reporting |
| Virtual Dimmer | switch + level 0-100 | Dimmable light simulation |
| Virtual RGBW Light | color-controllable | Color light simulation |
| Virtual Shade | open/close + position | Window shade control |
| Virtual Garage Door Opener | open/close | Garage door state |
| Virtual Water Sensor | wet/dry | Water leak simulation |
| Virtual Omni Sensor | multi-purpose | Combined sensor types |
| Virtual Fan Controller | fan speed control | Fan simulation |

MCP-managed virtual devices:
- Auto-accessible to all MCP device tools without manual selection
- Appear in Hubitat UI for Maker API, Dashboard, Rule Machine sharing
- Use `manage_virtual_device` (action: "delete") to remove (not `delete_device`)

---

## update_device Properties

| Property | API Used | Requires Hub Admin Write |
|----------|----------|-------------------------|
| label | setLabel (official) | No |
| name | setName (official) | No |
| deviceNetworkId | setDeviceNetworkId (official) | No |
| dataValues | updateDataValue (official) | No |
| preferences | updateSetting (official) | No |
| room | hub internal API | **Yes** |
| enabled | hub internal API | **Yes** |

**Preferences format:**
```json
{
  "pollInterval": {"type": "number", "value": 30},
  "debugLogging": {"type": "bool", "value": true}
}
```

**Valid preference types:** bool, number, string, enum, decimal, text

**Room assignment:** Use exact room name as it appears in Hubitat (case-sensitive)

---

## Rule Structure Reference

### Triggers
- `device_event` - Device attribute changes (supports duration for debounce, multi-device via deviceIds array)
- `button_event` - Button pushed/held/doubleTapped
- `time` - Specific time (HH:mm) or sunrise/sunset with offset
- `periodic` - Interval-based
- `mode_change` - Location mode changes
- `hsm_change` - HSM status changes

**Multi-device trigger example:**
```json
{
  "type": "device_event",
  "deviceIds": ["id1", "id2"],
  "attribute": "switch",
  "value": "on",
  "matchMode": "all"
}
```

**Time trigger examples:**
```json
{"type": "time", "time": "08:30"}
{"type": "time", "sunrise": true, "offset": 30}
{"type": "time", "sunset": true, "offset": -15}
```
Offset is in minutes (positive = after, negative = before)

### Conditions
- `device_state` - Current device attribute value
- `device_was` - Device was in state for X seconds
- `time_range` - Time window (supports sunrise/sunset)
- `mode` - Current location mode
- `variable` - Hub variable value
- `days_of_week` - Specific days
- `sun_position` - Sun above/below horizon
- `hsm_status` - Current HSM state

### Actions
- `device_command` - Send command to device
- `toggle_device` - Toggle device state
- `activate_scene` - Activate a scene
- `set_variable` / `set_local_variable` - Set variable value
- `set_mode` - Change location mode
- `set_hsm` - Change HSM state
- `delay` - Wait (with ID for targeted cancel)
- `if_then_else` - Conditional logic
- `cancel_delayed` - Cancel pending delayed actions
- `repeat` - Loop actions
- `stop` - Stop rule execution
- `log` - Log message
- `set_thermostat` - Mode/setpoints/fan
- `http_request` - GET/POST to URL
- `speak` - TTS with optional volume
- `comment` - Documentation only
- `set_valve` - Open/close valve
- `set_fan_speed` - low/medium/high/auto
- `set_shade` - open/close/position
- `variable_math` - Arithmetic on variables (add/subtract/multiply/divide/modulo/set)

---

## App/Driver Code Management

### Reading Source Code
- `get_app_source` / `get_driver_source` support chunked reading for large files
- Large files auto-saved to File Manager as `mcp-source-app-{id}.groovy` or `mcp-source-driver-{id}.groovy`
- Use this saved file with `sourceFile` parameter in update tools

### Updating Code
Three modes available:
1. **source** - Provide full source directly (for files <64KB)
2. **sourceFile** - Read from File Manager file (for large files, avoids cloud limits)
3. **resave** - Re-save current source without changes (recompile, trigger backup)

Auto-backs up before modifying. Rapid edits within 1 hour preserve the original.

### After Installation
- Apps: Add via Apps > Add User App in Hubitat web UI
- Drivers: Can be assigned to devices immediately

---

## Backup System

### Hub Backups
- `create_hub_backup` creates full hub database backup
- Required within 24 hours before any Hub Admin Write operation
- Only Hub Admin Write tool that doesn't require a prior backup

### Source Code Backups (Automatic)
- Created automatically when using update_app_code, update_driver_code, delete_app, delete_driver
- Stored in File Manager as `.groovy` files
- Persist even if MCP uninstalled
- Max 20 kept, oldest pruned
- Rapid edits preserve original (1-hour protection)

### Rule Backups (Automatic)
- `delete_rule` auto-backs up to File Manager as `mcp_rule_backup_<name>_<timestamp>.json`
- Restore via: `read_file` → `import_rule`
- Skip backup for test rules: set `testRule: true` when creating/updating

---

## File Manager

Files stored locally on hub at `http://<HUB_IP>/local/<filename>`

**File name rules:**
- Must match `^[A-Za-z0-9][A-Za-z0-9._-]*$`
- No spaces, no leading period
- Valid: `my-config.json`, `backup_2024.txt`
- Invalid: `.hidden`, `my file.txt`, `../escape`

**Chunked reading:**
- Use `offset` and `length` parameters for files >60KB
- Each chunk must be <60KB due to response size limits

---

## Performance Tips

**list_devices:**
- Use `detailed=false` for initial discovery
- Summary response (always returned) includes: id, name (driver type), label, room, `disabled`, `deviceNetworkId`, `lastActivity`, `parentDeviceId` — enough for most filtering without `get_device` round-trips. Summary mode also returns `currentStates`; detailed mode replaces it with `capabilities`/`attributes`/`commands`. To count children of a parent device, group the response on `parentDeviceId` client-side
- Use `filter` for server-side narrowing before pagination: `'enabled'`, `'disabled'`, or `'stale:<hours>'` (e.g. `'stale:24'` = devices with no activity in the last 24h). Use this for boolean and time-relative queries; leave name/label/capability filtering to client-side (AI scans returned JSON)
- With `detailed=true`, paginate: 20-30 devices per request. Detailed adds capabilities, attributes, commands
- Make tool calls sequentially, not in parallel

**get_device_events:**
- Default limit 10, recommended max 50
- Higher values (100+) may cause delays on busy devices

**get_hub_logs:**
- Returns most recent entries first
- Default 100 entries, max 500
- Use level and source filters to narrow results
- For single-device or single-app logs, pass `deviceId` or `appId` — this is a server-side scope filter (mutually exclusive) and is much cheaper than post-filtering the full buffer

**get_device_history:**
- Up to 7 days of history
- Use attribute filter to reduce data volume

**get_app_config:**
- Reads any legacy SmartApp's configuration page: RM rules, Room Lighting instances, Basic Rules, HPM, Mode Manager, Button Controllers, third-party community apps
- Default response includes `app` (identity), `page` (section/input structure with current values), `childApps` summary
- Raw app-internal `settings` map (~100-1000 keys with app-specific encoding) omitted by default — pass `includeSettings=true` for power-user inspection
- Multi-page apps (HPM, multi-page Room Lighting) expose sub-pages via `pageName`. For HPM specifically: `pageName="prefPkgUninstall"` returns the FULL installed-package list as an enum; `pageName="prefPkgModify"` returns only the subset with optional components; `pageName="prefOptions"` is the main menu (navigation links, no package data).
- Read-only, does not modify anything. Requires Hub Admin Read.

---

## Built-in App Tools

Tools in `manage_installed_apps` and `manage_rule_machine` gateways have mixed gate requirements. `list_installed_apps` and `get_device_in_use_by` require the **Enable Built-in App Tools** toggle (`requireBuiltinAppRead`). `get_app_config` and `list_app_pages` require **Hub Admin Read** (`requireHubAdminRead`). `manage_rule_machine` tools require the **Enable Built-in App Tools** toggle. If the user sees "Built-in App Tools are disabled" errors, direct them to the MCP Rule Server app settings page.

### manage_installed_apps (4 tools)

- **`list_installed_apps`** — enumerate ALL apps on the hub (built-in + user) with parent/child tree
  - `filter="all"` (default) | `"builtin"` | `"user"` | `"disabled"` | `"parents"` | `"children"`
  - Each entry: `id`, `name`, `type`, `disabled`, `user`, `hidden`, `parentId`, `hasChildren`, `childCount`
  - Built-in apps have `user=false` (Rule Machine, Room Lighting, Groups and Scenes, Mode Manager, HSM, Dashboards, Maker API, etc.)
  - User apps have `user=true` (Awair, Ecobee, HPM, etc.)
  - Parent/child tree flattened with `parentId` pointers. Hidden parents are excluded from output but their children are promoted to the nearest visible ancestor (or `null` at root).

- **`get_device_in_use_by`** — find apps that reference a specific device
  - Use BEFORE deleting a device, disabling a device, or troubleshooting unexpected behavior
  - Returns `appsUsing` array with each app's `id`, `name` (type like "Room Lights" or "Rule-5.1"), `label` (user-visible), `trueLabel` (HTML-stripped), `disabled`
  - Answers "if I delete/disable this device, which automations break?"

- **`get_app_config`** — read an installed app's configuration page (Hub Admin Read required)
  - See usage tips above for full details on response shape, pageName navigation, and includeSettings flag
  - Workflow: `list_installed_apps` or `list_rm_rules` to find an `appId`, then `get_app_config` to inspect it

- **`list_app_pages`** — list known page names for a multi-page app (Hub Admin Read required)
  - Returns the primary page (introspected from the hub) plus a curated directory of known sub-pages for well-known app types
  - Curated directories: HPM (prefOptions, prefPkgUninstall, prefPkgModify, prefPkgInstall, prefPkgMatchUp), RM rules (mainPage only -- single-page), Room Lighting (mainPage), Mode Manager (mainPage)
  - Unknown app types: returns the primary page only plus a note about consulting the app's source or Web UI for additional page names
  - Use this before `get_app_config` on multi-page apps to avoid guessing page names
  - Args: `appId` (required)

### manage_rule_machine (5 tools)

**Read + trigger existing RM rules only. Cannot create, modify, or delete RM rules — Hubitat platform blocks third-party apps from mutating built-in app children.**

- **`list_rm_rules`** — enumerate Rule Machine rules (RM 4.x + 5.x combined, deduplicated by id)

- **`run_rm_rule`** — trigger an existing RM rule via `RMUtils.sendAction`
  - `action="rule"` (default, full evaluation): runs triggers + conditions + actions as if the rule fired
  - `action="actions"`: runs only the actions, bypassing conditions (useful for manual override)
  - `action="stop"`: stops running actions (cancels in-flight delays)

- **`pause_rm_rule`** / **`resume_rm_rule`** — reversible toggle; paused rules don't fire on triggers

- **`set_rm_rule_boolean`** — set an RM rule's private boolean (true or false only; string values must be lowercase `"true"`/`"false"`). RM rules can use "Private Boolean" in conditions — this lets MCP flip that flag from outside.

### CRITICAL: Refuse invalid RM/RL operations

If a user asks "create a new RM rule" or "set up a new Room Lighting":

1. Explain this is not possible via MCP (platform limitation, not a missing feature)
2. Offer the alternative: create an equivalent rule using MCP's own rule engine via `create_rule`
3. Or direct them to the native Rule Machine / Room Lighting UI for configuration

**Do NOT invent fake tools like `create_rm_rule` or pretend to call one.** This is the most important safety rule for these tools.
