# MCP Tool Guide

Detailed reference for MCP Rule Server tools. Consult this when tool descriptions need clarification.

## Category Gateway Proxy (v0.8.0+)

As of v0.8.0, the server uses **domain-named gateways** to organize lesser-used tools behind gateway tools. The MCP `tools/list` shows 35 items (23 core + 12 gateways) covering 101 total tools. Use `search_tools` to find any tool by natural language query.

**How to use a gateway:**
1. Call the gateway with no arguments to see full parameter schemas for all its tools
2. Call with `tool='<tool_name>'` and `args={...}` to execute a specific tool

**Gateways:** `manage_rules_admin` (5), `manage_hub_variables` (8), `manage_rooms` (5), `manage_destructive_hub_ops` (3), `manage_apps_drivers` (7), `manage_app_driver_code` (10), `manage_logs` (8), `manage_diagnostics` (11), `manage_files` (4), `manage_installed_apps` (4), `manage_native_rules_and_apps` (12), `manage_mcp_self` (1)

All safety gates (Hub Admin Read/Write, confirm, backup checks) are preserved — they are enforced in the handler functions, not the dispatch layer.

### Disabling Gateways (Flat Tool List)

Gateways exist because most MCP clients struggle with long tool lists. Some clients now ship their own progressive-disclosure layer (deferred tools, built-in BM25 search, etc.) and don't need ours. For those, the **Consolidate tools behind category gateways** setting in the app preferences can be turned off — `tools/list` then advertises every tool individually and `search_tools` is suppressed because it's only useful for navigating gateway-hidden tools.

When the toggle is off, the dispatch contract still holds: every gateway sub-tool already has its own case in `executeTool()`, so a flat-mode client calling `list_rooms` directly hits the same handler as a gateway-mode client calling `manage_rooms` with `tool: "list_rooms"`. If a stale or cached client tries to call a gateway name (e.g. `manage_rooms`) while the toggle is off, the server returns a soft `isError` pointing at the underlying sub-tools rather than silently servicing the call with a gateway-shaped response.

Default is **ON** (gateways enabled). Existing installations keep the gateway behavior on update. Counts here describe the shipped catalog; runtime `tools/list` size varies based on enabled settings (Built-in App Tools, Custom Rule Engine, and the gateway toggle all add or remove entries).

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

**install_app** (via `manage_app_driver_code`)
- Accepts `source` (inline Groovy) OR `sourceFile` (filename in File Manager). Provide exactly one.
- Token-economy tip: upload large source via local CLI first, then pass the filename as `sourceFile`. Avoids re-sending multi-KB source strings on each install attempt.
- Performs post-install verification: after the hub creates the item, fetches it back to confirm the Groovy compiled cleanly. Returns `success: false` with `appId` populated when the hub reports a compile error or the verify response is empty/unparseable, so the error can be inspected via `get_app_source`. If the hub returns no item ID at all (no `Location` header), `appId` is `null`. Transient verify-fetch failures keep `success: true` but set `verified: false` plus `verifyError`.
- Requires Hub Admin Write + confirm + backup <24h.

**install_driver** (via `manage_app_driver_code`)
- Single-driver mode: supply `source` (inline Groovy) OR `sourceFile` (filename in File Manager). Provide exactly one.
- Bulk mode: supply an `installs` array of objects, each with `source` or `sourceFile`. Cannot combine with single-driver fields.
  - Continue-on-error: errors on individual items do not abort remaining installs. Returns a per-item status array with each item's `driverId`.
  - Top-level `success: true` only if ALL items succeeded.
  - Practical limit: ~10-20 drivers per call (each install is a sequential on-hub compilation, ~1-5 seconds each).
  - Token-economy pattern: upload all driver source files via local CLI, then call bulk `install_driver` once with all `{sourceFile}` entries.
- Performs post-install verification: after the hub creates each item, fetches it back to confirm the Groovy compiled cleanly. Returns `success: false` with `driverId` populated when the hub reports a compile error or the verify response is empty/unparseable, so the error can be inspected via `get_driver_source`. If the hub returns no item ID at all (no `Location` header), `driverId` is `null`. Transient verify-fetch failures keep `success: true` but set `verified: false` plus `verifyError`.
- Requires Hub Admin Write + confirm + backup <24h.

**update_driver_code** (via `manage_app_driver_code`)
- Single-driver mode (unchanged): supply `driverId` + one of `source` / `sourceFile` / `resave`.
- Bulk mode: supply an `updates` array of objects, each with `driverId` and one of `sourceFile` / `source` / `resave`. Cannot combine with single-driver fields.
  - Continue-on-error: errors on individual items do not abort remaining updates. Returns a per-item status array.
  - Top-level `success: true` only if ALL items succeeded.
  - Practical limit: ~20 drivers per call (each update is a sequential on-hub compilation, ~1-5 seconds each).
  - Token-economy pattern: upload all updated driver files via local CLI (curl -F / PowerShell Invoke-RestMethod), then call bulk `update_driver_code` once with all `{driverId, sourceFile}` pairs.

**delete_app / delete_driver / delete_library** (via `manage_app_driver_code`)
- Remove app instances via Hubitat UI first (for apps)
- Change devices to different driver first (for drivers)
- Ensure no drivers/apps reference the library via #include before deleting (for libraries)
- Source code auto-backed up before deletion

---

## Virtual Device Types

`manage_virtual_device` (action: "create") supports two mutually exclusive driver selection methods:

**Option A: deviceType** -- built-in Hubitat virtual drivers (pass one of the values below):

| Type | Description | Common Use Case |
|------|-------------|-----------------|
| Virtual Switch | on/off toggle | Boolean flags, triggers |
| Virtual Button | pushable button | Triggering automations |
| Virtual Contact Sensor | open/closed | Simulate door/window state |
| Virtual Motion Sensor | active/inactive | Simulate motion detection |
| Virtual Presence | present/not present | Presence simulation |
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

**Option B: customDriver** -- user-installed drivers (pass `{namespace, name}`); mutually exclusive with `deviceType`:
```json
{
  "action": "create",
  "deviceLabel": "Kitchen Humidifier Test",
  "customDriver": { "namespace": "level99-vesync", "name": "Levoit Classic 200S Humidifier" },
  "confirm": true
}
```
Use `manage_apps_drivers(tool="list_hub_drivers")` to see installed drivers and their namespace + name values. The namespace and name must match exactly as registered. If the driver is not found (or any other hub error), the tool surfaces an `IllegalArgumentException` pointing to `list_hub_drivers`.

**Create response shape** (both modes): `{ success, device: { id, name, label, deviceNetworkId, driverNamespace, driverType, capabilities, commands, attributes } }`

**List response shape** (`list_virtual_devices`): each entry includes `driverNamespace`, `driverType`, and `typeName` (deprecated alias for `driverType` -- prefer `driverType` in new code).

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

## App/Driver/Library Code Management

### Reading Source Code
- `get_app_source` / `get_driver_source` (via `manage_apps_drivers`) / `get_library_source` (via `manage_apps_drivers`) support chunked reading for large files
- Large files auto-saved to File Manager as `mcp-source-app-{id}.groovy`, `mcp-source-driver-{id}.groovy`, or `mcp-source-library-{id}.groovy`
- Use this saved file with `sourceFile` parameter in update tools

### Updating Code
Three modes available:
1. **source** - Provide full source directly (for files <64KB)
2. **sourceFile** - Read from File Manager file (for large files, avoids cloud limits)
3. **resave** - Re-save current source without changes (recompile, trigger backup)

Auto-backs up before modifying. Rapid edits within 1 hour preserve the original.

### Libraries
Hubitat Groovy libraries are shared code modules included by drivers and apps via `#include namespace.LibraryName`. The library management tools mirror the app/driver pattern:
- `install_library` - Install a new library from Groovy source (inline or via File Manager file)
- `update_library_code` - Update existing library source (source/sourceFile/resave modes)
- `delete_library` - Delete a library (auto-backs up source first)
- `get_library_source` - Read library source with chunked reading support

Libraries must include a valid `library()` definition block. Before deleting a library, ensure no installed drivers or apps reference it via `#include` -- deleting a library in use causes compilation errors in the referencing code.

PREFER curl-upload + sourceFile (bypasses agent context, no transcript size limits). Use inline `source` only for stub-size snippets:
```bash
# Without Hub Security:
curl -F "uploadFile=@mylib.groovy" -F "folder=/" http://<HUB_IP>/hub/fileManager/upload

# With Hub Security enabled (cookie-based):
curl -c cookies.txt -d "username=USER&password=PASS" http://<HUB_IP>/login
curl -b cookies.txt -F "uploadFile=@mylib.groovy" -F "folder=/" http://<HUB_IP>/hub/fileManager/upload
```
Then call `install_library` or `update_library_code` with `sourceFile: 'mylib.groovy'`.

Note: `get_library_source` (read-only, Hub Admin Read) lives in `manage_apps_drivers` alongside `get_app_source` and `get_driver_source`. The write operations (`install_library`, `update_library_code`, `delete_library`) live in `manage_app_driver_code` (Hub Admin Write).

### After Installation
- Apps: Add via Apps > Add User App in Hubitat web UI
- Drivers: Can be assigned to devices immediately
- Libraries: Available immediately for `#include` in driver/app source

---

## Backup System

### Hub Backups
- `create_hub_backup` creates full hub database backup
- Required within 24 hours before any Hub Admin Write operation
- Only Hub Admin Write tool that doesn't require a prior backup

### Source Code Backups (Automatic)
- Created automatically when using update_app_code, update_driver_code, update_library_code, delete_app, delete_driver, delete_library
- Stored in File Manager as `.groovy` files
- Persist even if MCP uninstalled
- Max 20 kept, oldest pruned
- Rapid edits preserve original (1-hour protection)

### Custom-engine Rule Backups (Automatic)
- `custom_delete_rule` auto-backs up to File Manager as `mcp_rule_backup_<name>_<timestamp>.json`
- Restore via: `read_file` → `custom_import_rule`
- Skip backup for test rules: set `testRule: true` when creating/updating

### Native RM Rule Backups (Automatic)
- `update_rm_rule` and `delete_rm_rule` snapshot configure/json + statusJson to File Manager as `mcp-rm-backup-<ruleId>-<timestamp>.json`
- Snapshots register in the unified `state.itemBackupManifest` with type=`rm-rule`
- Use `list_item_backups` to enumerate, `restore_item_backup` (in `manage_apps_drivers`) with the backupKey to roll back
- If the rule still exists, settings are replayed in place; if deleted, a fresh empty rule is recreated and the saved settings replayed onto it

### Hubitat Built-in Rule Redirect
When `custom_get_rule`, `custom_export_rule`, `custom_update_rule`, `custom_delete_rule`, `custom_test_rule`, or `custom_clone_rule` fails
with "Rule not found", the error message may include a redirect hint if the ID belongs to a
Hubitat built-in rule-like app (Rule Machine, Room Lighting, Basic Rules, Visual Rules Builder).

Example redirect message:
> "Rule 832 is a Hubitat built-in Rule-5.1 app. Use `manage_installed_apps -> get_app_config(appId=832)` to read its configuration."

- Read verbs (`custom_get_rule`, `custom_export_rule`, `custom_clone_rule`): points to `get_app_config` and notes these tools only handle MCP's own rule engine.
- Write verbs (`custom_update_rule`): points to `get_app_config` for inspection and `manage_native_rules_and_apps -> update_native_app` for programmatic modification (requires Built-in App Tools + Hub Admin Write).
- Delete verb (`custom_delete_rule`): points to `manage_native_rules_and_apps -> delete_native_app` for programmatic deletion.
- Test verb (`custom_test_rule`): points to `manage_installed_apps -> get_app_config` for inspection. For Rule Machine rules specifically, the hint also includes a pointer to `manage_native_rules_and_apps -> run_rm_rule` to trigger them; non-RM rule-likes (Room Lighting, Basic Rules, Visual Rule Builder) receive only the `get_app_config` pointer because `run_rm_rule` routes through `RMUtils.sendAction` and is RM-only.
- The redirect check is best-effort: if the hub appsList call fails, a plain "Rule not found" message is returned with no secondary error.
- `custom_list_rules` and `custom_create_rule` are not affected (they do not take a rule id as input).

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
- Summary response always includes: id, name (driver type), label, room, `disabled`, `deviceNetworkId`, `lastActivity`, `parentDeviceId` + `currentStates` dict. Detailed mode replaces `currentStates` with `capabilities`, `attributes`, `commands`. To count children of a parent device, group on `parentDeviceId` client-side
- **Server-side filters** (all applied before pagination, composable with each other):
  - `filter` — `'enabled'` / `'disabled'` / `'stale:<hours>'` (boolean and time-relative queries)
  - `labelFilter` — case-insensitive substring match on device label (e.g. `'kitchen'` returns all devices whose label contains "kitchen")
  - `capabilityFilter` — case-insensitive exact match on capability name (e.g. `'Switch'`, `'TemperatureMeasurement'`)
- **Format shortcuts**: `format='ids'` returns a flat integer array `deviceIds: [1,2,3]` (cheapest shape for "which devices exist" queries)
- **Field projection**: `fields=['id','label']` skips `currentStates` and `attributes` (the expensive ones -- they trigger per-device hub reads); `capabilities` and `commands` are in-memory and cheap. Only named fields are populated -- reduces both payload size and hub CPU. Unknown field names throw. `id` is always included regardless of projection.
- With `detailed=true` (or `format='detailed'`), paginate: 20-30 devices per request
- Make tool calls sequentially, not in parallel

**get_device_events:**
- Default limit 10, recommended max 50
- Higher values (100+) may cause delays on busy devices

**poll_until_attribute:**
- BLOCKS the MCP request up to `timeoutMs` MILLISECONDS (default 5000ms = 5 seconds, max 60000ms = 60 seconds). Use sparingly; prefer event-driven flows when available.
- Concurrent MCP requests queue while this call blocks; avoid parallel `poll_until_attribute` calls.
- At least one of `expectedValue` or `expectedValues` must be provided. Both may be set simultaneously -- the poll succeeds if the current value matches either (OR semantics, not XOR).
- Re-reads the attribute every `pollIntervalMs` MILLISECONDS (default 200ms, min 50ms, max 5000ms)
- Returns `success: true` with `finalValue`, `elapsedMs`, `polledCount`, `timedOut: false` when the value matches
- Returns `success: false` with `timedOut: true` and the last-read `finalValue` on timeout; adds `neverReported: true` if the attribute never returned a non-null value during the entire poll window
- Returns `success: false` with `interrupted: true` (plus `finalValue`, `elapsedMs`, `polledCount`) if the hub interrupted the sleep (e.g. app reload during poll)
- `pollIntervalMs` is automatically clamped to `timeoutMs` if larger, ensuring at least one poll
- For passive one-shot reads, use `get_attribute` instead -- this tool is for waiting on state transitions
- Common pattern after `send_command`: poll for the resulting attribute state rather than sleeping client-side

**get_hub_logs:**
- Returns most recent entries first
- Default 100 entries, max 500
- Filter pipeline order: scope (deviceId/appId, server-side) -> level -> source -> pattern -> patterns -> time window (since/until) -> limit
- `level` and `source` (substring) are simple string filters applied before regex
- `pattern` (string): case-insensitive regex against the message field; compiled once; throws on invalid syntax
- `patterns` (array of strings): multiple regexes; `patternMode='any'` (default) = OR, `patternMode='all'` = AND; compatible with `pattern` (both apply)
- `since` / `until`: ISO-8601 timestamp (e.g. `'2024-01-15T10:30:00Z'`) or relative offset (`'30m'`, `'2h'`, `'1d'`, `'7d'`); relative offset subtracted from now; max 30d (throws if exceeded -- use ISO-8601 for longer ranges); entries with unparseable time fields pass through rather than being excluded. ISO-8601 timestamps without an explicit TZ marker (e.g., `'2024-01-15T10:30:00'` or `'2024-01-15 10:30:00.000'`) are parsed as UTC.
- For single-device or single-app logs, pass `deviceId` or `appId` -- this is a server-side scope filter (mutually exclusive) and is much cheaper than post-filtering the full buffer

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

Tools in `manage_installed_apps` and `manage_native_rules_and_apps` gateways have mixed gate requirements. `list_installed_apps` and `get_device_in_use_by` require the **Enable Built-in App Tools** toggle (`requireBuiltinApp`). `get_app_config` and `list_app_pages` require **Hub Admin Read** (`requireHubAdminRead`). `manage_native_rules_and_apps` tools require the **Enable Built-in App Tools** toggle for reads and **Hub Admin Write** (`requireHubAdminWrite`) for the CRUD path (`create_native_app`, `update_native_app`, `delete_native_app`); Hub Admin Write also enforces a backup-within-24h gate before any write. If the user sees "Built-in App Tools are disabled", "Hub Admin Read is disabled", or "Hub Admin Write is disabled" errors, direct them to the MCP Rule Server app settings page to enable the relevant toggle. Note: Hub Admin Write operations additionally require a hub backup within the last 24 hours -- if the write gate blocks with a backup-age message, use `create_hub_backup` first.

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

### manage_native_rules_and_apps (12 tools)

Two surfaces under one gateway: RMUtils-based runtime control for RM rules (RM-only because RMUtils is RM-only) plus admin-layer CRUD that works uniformly across any classic SmartApp (RM, Room Lighting, Button Controllers, Basic Rules, Notifier, etc.).

**RMUtils control (5 tools, RM-only):**

- **`list_rm_rules`** — enumerate Rule Machine rules (RM 4.x + 5.x combined, deduplicated by id)
- **`run_rm_rule`** — trigger an existing RM rule via `RMUtils.sendAction`
  - `action="rule"` (default, full evaluation): runs triggers + conditions + actions as if the rule fired
  - `action="actions"`: runs only the actions, bypassing conditions (useful for manual override)
  - `action="stop"`: stops running actions (cancels in-flight delays)
- **`pause_rm_rule`** / **`resume_rm_rule`** — reversible toggle; paused rules don't fire on triggers
- **`set_rm_rule_boolean`** — set an RM rule's private boolean (true or false only; string values must be lowercase `"true"`/`"false"`). RM rules can use "Private Boolean" in conditions — this lets MCP flip that flag from outside.

**Admin-layer CRUD (7 tools, generic across all classic SmartApps):**

If a user asks "create a new RM rule" or "modify this Room Lighting instance":

1. **`create_native_app(appType, name, confirm)`** — creates an empty classic SmartApp. `appType` is enum-driven (initially `rule_machine`; expand `_appTypeRegistry` for other types). Returns `appId`.
2. **`update_native_app(appId, settings|button, ...)`** — modifies any classic SmartApp instance by appId. Multi-device capability `multiple=true` contract emitted automatically. Auto-snapshots before every write.
3. **`delete_native_app(appId, force, confirm)`** — soft delete (default) or `force=true` for `forcedelete/quiet` (the path the hub UI uses). Auto-snapshots first.
4. **`clone_native_app(appId, newName, confirm)`** — clone an existing classic SmartApp via Hubitat's `appCloner` endpoint. Returns the new `appId`.
5. **`export_native_app(appId)`** — export a classic SmartApp to JSON (round-trippable with `import_native_app`). Useful for the export-mutate-import editing pattern when the wizard surface is too lossy to drive directly.
6. **`import_native_app(appType, exportData, name, confirm)`** — import previously-exported app JSON into a new instance. Returns the new `appId`.
7. **`check_rule_health(appId)`** — read-only health check on any installed app. Surfaces broken markers, multiple-flag poison, configPage errors. Use after a destructive operation to confirm the app is still well-formed.

**Cross-references** (live in other gateways but commonly used together):
- **`get_app_config`** (in `manage_installed_apps`) — read any installed app's current page schema, settings, and child apps. Use BEFORE every `update_native_app` to discover the right input names.
- **`list_item_backups`** + **`restore_item_backup`** (in `manage_apps_drivers` / `manage_app_driver_code`) — enumerate and restore native-app snapshots (`type="rm-rule"` entries). Restore re-applies settings in place if the app exists, or recreates the app and replays settings if it was deleted.

For Room Lighting / Button Controllers / Basic Rules: `update_native_app` and `delete_native_app` already work today (they take any classic-app appId). `create_native_app` will work for them once their entries are added to `_appTypeRegistry()` — same endpoint family, just need namespace + appName + parentTypeName per type.

---

## Developer Mode

The `manage_mcp_self` gateway exposes self-administration tools that let an LLM agent or CI/CD pipeline manage the MCP rule app's own configuration without manual UI intervention. **Requires the opt-in `Enable Developer Mode Tools` toggle** in the MCP rule app settings page (default OFF). Each successful write is logged at WARN level for audit. If the user sees "Developer Mode tools are disabled" errors, direct them to enable the toggle in the MCP Rule Server app settings.

### manage_mcp_self (1 tool)

- **`update_mcp_settings`** — update one or more of the MCP rule app's own settings (toggles, log level, tuning params)
  - Args: `settings` (map of `{key: value}`), `confirm=true`
  - Allowlisted keys (intentionally conservative for v1): `mcpLogLevel`, `debugLogging`, `maxCapturedStates`, `loopGuardMax`, `loopGuardWindowSec`, `enableHubAdminRead`, `enableBuiltinApp`, `enableCustomRuleEngine`
  - **Excluded** from v1 allowlist: `enableHubAdminWrite` (footgun — would disable own write path mid-session), `enableDeveloperMode` (lockout protection — must remain UI-only to disable), `selectedDevices` (different wire format, separate tool planned)
  - After changing any `enable*` toggle, MCP clients (Claude Code, etc.) may need to reconnect to refresh the cached tool schema
  - Gated on: `enableDeveloperMode` + `requireHubAdminWrite` + recent backup

### manage_hub_variables — `delete_variable`

The `delete_variable` op (DESTRUCTIVE, no undo) removes a rule_engine variable. Useful for sweeping orphaned `BAT_E2E_*` artifacts after CI runs, removing stale lease variables, or general cleanup. Connector-namespace deletion is not yet supported via MCP — use the Settings → Hub Variables UI for those.
