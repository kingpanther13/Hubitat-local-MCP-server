# MCP Tool Guide

Detailed reference for MCP Rule Server tools. Consult this when tool descriptions need clarification.

## Category Gateway Proxy (v0.8.0+)

As of v0.8.0, the server uses **domain-named gateways** to organize lesser-used tools behind gateway tools. The MCP `tools/list` shows 36 items (23 core + 13 gateways) covering 103 total tools. Use `search_tools` to find any tool by natural language query.

**How to use a gateway:**
1. Call the gateway with no arguments to see full parameter schemas for all its tools
2. Call with `tool='<tool_name>'` and `args={...}` to execute a specific tool

**Gateways:** `manage_rules_admin` (5), `manage_hub_variables` (8), `manage_rooms` (5), `manage_destructive_hub_ops` (3), `manage_apps_drivers` (7), `manage_app_driver_code` (10), `manage_logs` (8), `manage_diagnostics` (11), `manage_files` (4), `manage_installed_apps` (4), `manage_hpm` (2), `manage_native_rules_and_apps` (12), `manage_mcp_self` (1)

All safety gates (Hub Admin Read/Write, confirm, backup checks) are preserved — they are enforced in the handler functions, not the dispatch layer.

### Disabling Gateways (Flat Tool List)

Gateways exist because most MCP clients struggle with long tool lists. Some clients now ship their own progressive-disclosure layer (deferred tools, built-in BM25 search, etc.) and don't need ours. For those, the **Consolidate tools behind category gateways** setting in the app preferences can be turned off — `tools/list` then advertises every tool individually and `search_tools` is suppressed because it's only useful for navigating gateway-hidden tools.

When the toggle is off, the dispatch contract still holds: every gateway sub-tool already has its own case in `executeTool()`, so a flat-mode client calling `list_rooms` directly hits the same handler as a gateway-mode client calling `manage_rooms` with `tool: "list_rooms"`. If a stale or cached client tries to call a gateway name (e.g. `manage_rooms`) while the toggle is off, the server returns a soft `isError` pointing at the underlying sub-tools rather than silently servicing the call with a gateway-shaped response.

Default is **ON** (gateways enabled). Existing installations keep the gateway behavior on update. Counts here describe the shipped catalog; runtime `tools/list` size varies based on enabled settings (Built-in App Tools, Custom Rule Engine, and the gateway toggle all add or remove entries).

### `tools/list` Returns the Full Catalog in One Response

`tools/list` returns the complete tool catalog in a single response (no pagination). Pagination was tried briefly (page size 50, cursor-based) but removed because many MCP clients — including the Claude.ai connector — do NOT iterate `nextCursor` automatically, which silently truncated the catalog at the first 50 tools. The full-catalog response is backstopped by the universal response-size guard inside `handleMcpRequest` (124,000-byte threshold) that emits a loud `-32603 "Response too large"` envelope if the catalog ever exceeds the hub's 128KB JSON-RPC limit — better to fail loud than silently lose tools. Stale clients that pass a `cursor` param get the full catalog and find no `nextCursor`, so any iteration loop terminates after one call. Opt-in cursor pagination on `tools/call` (`list_devices`, `list_installed_apps`, `list_rm_rules`, etc.) is unaffected — see the next section.

### `tools/call` Response-Size Guard (v1.3.x+, fail-soft)

Every `tools/call` response is measured before send. If the wire-encoded response exceeds the universal 120 KB cap (8 KB headroom under the hub's 128 KB JSON-RPC limit), the inner content is replaced with a structured fail-soft envelope:

```json
{
  "response_too_large": true,
  "truncated": true,
  "estimatedBytes": 145320,
  "sizeLimitBytes": 120000,
  "tool": "list_installed_apps",
  "suggestion": "Set includeHidden=false (the default), narrow via filter ..., or pass cursor to page through the apps list."
}
```

The outer JSON-RPC envelope still reports success (this is not a tool error — the tool ran, the result just didn't fit). Treat `response_too_large=true` as a hint to either (a) narrow your query — the per-tool `suggestion` field names the specific knob — or (b) opt into pagination on tools that support it. The `tool` field reflects the actual sub-tool on gateway-routed calls so you can re-issue a narrower call directly.

Opt-in cursor pagination is currently wired into the following read-only tools. All follow the same contract: omit `cursor` for the full list (backward-compatible, backstopped by the size guard), pass `cursor: ""` for the first page, then iterate `nextCursor` until absent. Cursor is opaque per the MCP convention; non-numeric / out-of-range values reject as `-32602`.

These tools follow an explicit opt-in convention so pre-`cursor` callers see no behaviour change — pagination is genuinely opt-in. (Pre-PR `tools/list` had its own different shape — unconditional pagination at 50/page — which is now removed; see the previous section.)

| Tool | Page size | Notes |
|---|---|---|
| `list_devices` | 50 (when `limit` unset) | Cursor is an alias for the existing `offset`+`limit` shape; `nextCursor` is emitted alongside `nextOffset`. |
| `list_installed_apps` | 50 | Cursor respects `filter` — pages the filtered set. |
| `list_hub_apps` | 50 | Catalog of installable apps on the hub. |
| `list_hub_drivers` | 50 | Catalog of installable drivers. |
| `list_hpm_packages` | 25 | Smaller page because each HPM entry carries the full app/driver/file inventory. |
| `list_rm_rules` | 50 | RM 4.x + 5.x deduplicated rules. |
| `custom_list_rules` | 50 | Legacy MCP rule engine. |
| `list_variables` | 100 | Pages `hubVariables`; `ruleVariables` stays in full alongside the page. |
| `list_captured_states` | 50 | Capacity warnings still emitted regardless of pagination. |
| `list_item_backups` | 50 | Sorted newest-first; page boundaries are stable as long as the manifest doesn't change. |
| `list_files` | 100 | File Manager listing. |
| `list_virtual_devices` | 50 | MCP-managed virtual devices. |
| `list_rooms` | 100 | Rooms with device counts. |
| `device_health_check` | 100 | Pages `staleDevices`; `unknownDevices` (and `healthyDevices` when `includeHealthy=true`) stay in full. |
| `get_device_in_use_by` | 100 | Pages `appsUsing`. |
| `get_hub_logs` | 100 | Filters + `limit` apply first; cursor pages within the filtered result. |
| `get_memory_history` | 100 | `limit=0` + cursor pages the full hub ring buffer (the only way to retrieve every entry without losing data). |
| `get_debug_logs` | 100 | Filters apply first; cursor pages within. |

Tools without cursor support (`get_app_config`, `export_native_app`, `get_app_source`, `get_driver_source`, `get_library_source`) rely on their existing controls (`includeSettings=false`, `saveAs=<file>`, `list_files`/`read_file` round-trip) plus the universal size guard as the backstop.

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

**update_app_code** (via `manage_app_driver_code`)
- Supply `appId` + one of `source` / `sourceFile` / `resave`.
- Self-update guard: applies only when `appId` matches this MCP server's own app instance. Refuses to overwrite that source unless **Enable Developer Mode Tools** is on in the MCP Rule Server app settings (a bad self-update bricks the MCP loop; recovery requires the Hubitat UI or SSH). Every self-update attempt — blocked OR allowed — is audit-logged at WARN under the `hub-admin` component. If the app context is unavailable at call time (rare lifecycle window) the guard fails closed with an ERROR audit log; retry the call.
- Optional `expectedVersion` (integer): optimistic-lock guard. When supplied and the hub's version differs, the update returns `success: false` + `conflict: true` with both `expectedVersion` and `currentVersion` echoed. Use when an agent's read-modify-write spans turns or runs alongside other agents/tools that could mutate the same app. Backup still happens on conflict (intentional: `backupItemSource` has a 1h cache so the parallel-agent retry costs nothing, and the first caller losing the race still has a recovery point). Stringified integers are coerced; explicit `null` is rejected (omit the field entirely to skip the lock).
- Requires Hub Admin Write + confirm + backup <24h.

**update_driver_code** (via `manage_app_driver_code`)
- Single-driver mode (unchanged): supply `driverId` + one of `source` / `sourceFile` / `resave`. Optional `expectedVersion` (integer): same optimistic-lock semantics as `update_app_code` — conflict envelope carries `driverId` instead of `appId`.
- Bulk mode: supply an `updates` array of objects, each with `driverId` and one of `sourceFile` / `source` / `resave`, plus optional per-item `expectedVersion`. Cannot combine with single-driver fields (including a top-level `expectedVersion` — put it on each entry instead).
  - Continue-on-error: errors on individual items do not abort remaining updates. Returns a per-item status array. Per-item conflicts (failed lock) carry `conflict: true` + `expectedVersion` + `currentVersion`; per-item thrown errors carry `error` + `errorClass`. Other items in the batch still apply.
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
Use `manage_apps_drivers(tool="list_hub_drivers")` to see installed drivers and their namespace + name values. The namespace and name must match exactly as registered. If the driver is not found (or any other hub error), the tool surfaces an `IllegalArgumentException` (-32602) pointing to `list_hub_drivers`. If a built-in `deviceType` is not found on the hub, the tool surfaces an isError platform error (firmware gap, not a caller error).

**Create response shape** (both modes): `{ success, message, tips, device: { id, name, label, deviceNetworkId, driverNamespace, driverType, typeName, capabilities, commands, attributes } }`. `typeName` is a deprecated alias for `driverType` -- prefer `driverType` in new code.

**Delete response shape** (`action=delete`): `{ success, deviceId, deviceNetworkId, deviceLabel, message }`.

**List response shape** (`list_virtual_devices`): `{ devices: [...], count, message }`. Per-device: `{ id, name, label, deviceNetworkId, driverNamespace, driverType, typeName, capabilities, commands, currentStates }`. `currentStates` is a map of attribute-name to current-value. Note: create returns device state as `attributes` (list) while list returns it as `currentStates` (map) -- different shapes for the same concept because create returns the freshly-read attribute list and list returns a compact state map. `typeName` is a deprecated alias for `driverType` -- prefer `driverType` in new code. `driverNamespace` is authoritative for devices created by this tool (the namespace is persisted at create time); for devices created before this version or by other means it falls back to a best-effort derivation that may report `"hubitat"`.

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
- **Location-scope mode**: omit `deviceId` to return location events instead of device events. Returns mode changes (`name: 'mode'`), HSM status/alerts (`'hsmStatus'`, `'hsmAlert'`), hub variable changes (name = the variable's name), and any `sendLocationEvent(...)` emissions from rules/apps. `attribute` filter applies to event name in the same way. Response includes `source: 'location'` and omits `device`/`deviceId`.

**get_app_config:**
- Reads any legacy SmartApp's configuration page: RM rules, Room Lighting instances, Basic Rules, HPM, Mode Manager, Button Controllers, third-party community apps
- Default response includes `app` (identity), `page` (section/input structure with current values), `childApps` summary
- Raw app-internal `settings` map (~100-1000 keys with app-specific encoding) omitted by default — pass `includeSettings=true` for power-user inspection
- Multi-page apps (HPM, multi-page Room Lighting) expose sub-pages via `pageName`. For HPM specifically: `pageName="prefPkgUninstall"` returns the FULL installed-package list as an enum; `pageName="prefPkgModify"` returns only the subset with optional components; `pageName="prefOptions"` is the main menu (navigation links, no package data).
- Read-only, does not modify anything. Requires Hub Admin Read.

---

## Built-in App Tools

Tools in `manage_installed_apps` and `manage_native_rules_and_apps` gateways have mixed gate requirements. `list_installed_apps` and `get_device_in_use_by` require the **Enable Built-in App Tools** toggle (`requireBuiltinApp`). `get_app_config` and `list_app_pages` require **Hub Admin Read** (`requireHubAdminRead`). Both `manage_hpm` tools (`list_hpm_packages` and `get_hpm_drift`) require **Hub Admin Read** only -- no Built-in App Tools toggle needed. `manage_native_rules_and_apps` tools require the **Enable Built-in App Tools** toggle for reads and **Hub Admin Write** (`requireHubAdminWrite`) for the CRUD path (`create_native_app`, `update_native_app`, `delete_native_app`); Hub Admin Write also enforces a backup-within-24h gate before any write. If the user sees "Built-in App Tools are disabled", "Hub Admin Read is disabled", or "Hub Admin Write is disabled" errors, direct them to the MCP Rule Server app settings page to enable the relevant toggle. Note: Hub Admin Write operations additionally require a hub backup within the last 24 hours -- if the write gate blocks with a backup-age message, use `create_hub_backup` first.

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

### manage_hpm (2 tools)

HPM package state introspection. Both tools require **Hub Admin Read** and HPM itself must be installed on the hub. Auto-discovers HPM's installed-app ID unless `hpmAppId` is supplied explicitly. All `IllegalArgumentException`s raised by either tool (multi-instance throw, wrong app type, missing HPM, non-numeric `hpmAppId`) are surfaced by the MCP dispatcher as JSON-RPC error `-32602` "Invalid params" -- the response is a JSON-RPC error, **not** a tool-result map with `success=false`.

**`actualTypeName` diagnostic label sets — two distinct enums:** HPM tooling reports the parsed-JSON runtime type via an `actualTypeName` token at two distinct sites with different label sets. (1) `_hpmFetchManifests` parse-shape errors (surfaced as JSON-RPC `-32602` "Unexpected HPM statusJson shape" / "Unexpected HPM manifests shape"): `<actualTypeName>` is one of `List`, `null`, or `non-object` (any non-Map, non-List, non-null scalar). (2) `orphanDetection` / `orphanDriverDetection` registry-shape errors (surfaced in `reason` strings on a `success=true` response with `enabled=false`): `<actualTypeName>` is one of `Map`, `null`, or `unknown` (any non-List, non-null, non-Map shape). The label sets diverge because the expected shape at each site is different (a Map for statusJson, a List for the registries).

- **`list_hpm_packages`** — return all packages tracked by Hubitat Package Manager with full component inventory
  - Each package: `manifestUrl`, `packageName`, `version`, `beta`, `author`, `apps[]`, `drivers[]`, `files[]`
  - Each app/driver component: `id` (UUID from manifest), `name`, `heID` (Hubitat code ID or null if not installed), `required`, `version`; if `heID` is an empty/whitespace-only string it is cleared to null and `_warning` is added (e.g. `"empty heID string '' normalized to null"` for an empty string, or `"empty heID string '  ' normalized to null"` for a whitespace-only string); if `heID` is a whitespace-padded string (e.g. `' 142 '`) it is normalized to the trimmed value and `_warning` records the normalization (e.g. `"whitespace-padded heID ' 142 ' normalized to '142'"`); heID remains non-null; if `heID` is a non-scalar type it is cleared to null and `_warning` is added
  - `files[]` entries have no `heID` (File Manager assets tracked by name only)
  - Top-level `count` (number of packages returned); `hpmAppId` (HPM's installed-app ID, echoed so callers can cache it and skip discovery on subsequent calls)
  - Top-level `skippedMalformed[]`: manifest URLs whose value was not a Map (package skipped entirely)
  - Per-package `skippedAppCount`, `skippedDriverCount`, `skippedFileCount` (each omitted when 0): counts of app/driver/file entries that were not Maps
  - Auto-discovers HPM if `hpmAppId` omitted; when multiple HPM instances are detected, throws with a bracketed ID list capped at 10 entries with `"and N more (total M)"` suffix; when `hpmAppId` is supplied explicitly but points at an app that is not Hubitat Package Manager, throws `IllegalArgumentException` with the actual type disclosed (e.g. `"hpmAppId N is not Hubitat Package Manager (actual type: Simple Automation Rules)"`)
  - Use to audit what HPM-managed code is on the hub and compare against expected packages

- **`get_hpm_drift`** — cross-reference HPM-tracked state against what is actually installed
  - Currently the only drift signal types are: `missing-required` (required=true, heID null), `orphan-app` (heID present but app code no longer in Apps Code registry), `orphan-driver` (heID present but driver code no longer in Drivers Code registry). Data-quality issues are emitted in a separate `dataQualityWarnings[]` aggregate and do NOT inflate `totalDriftSignals`.
  - Optional `packageFilter` (case-insensitive substring) narrows to specific packages
  - Auto-discovers HPM if `hpmAppId` omitted (including the multi-instance throw with up to 10 ids and `"and N more (total M)"` suffix); when `hpmAppId` is supplied explicitly but points at an app that is not Hubitat Package Manager, throws `IllegalArgumentException` with the actual type disclosed (e.g. `"hpmAppId N is not Hubitat Package Manager (actual type: Simple Automation Rules)"`)
  - Response: `hpmAppId` (echoed, same semantics as `list_hpm_packages`); `packagesChecked` (not `count`) **because** `packageFilter` may narrow the set examined -- 'checked' reflects the filtered population, distinguishing it from `list_hpm_packages`'s naive `count`; `packagesWithActionableDrift` (count of packages with at least one actionable signal -- excludes data-quality-only packages); `totalDriftSignals` (actionable drift only -- missing-required, orphan-app, orphan-driver -- does NOT count data-quality warnings); `drift[]` (one entry per package with any signal or warning -- each has `manifestUrl`, `packageName`, `version`, `signals[]`, and optionally `dataQualityWarnings[]`, `skippedAppCount`, `skippedDriverCount`); `summary` sentence. Note: `drift[].length` may exceed `packagesWithActionableDrift` when data-quality-only packages exist -- those entries appear for visibility but are not counted in the summary.
  - Each `signals[]` entry has: `type`, `componentType`, `componentName`, `componentId`, `note`. `orphan-app` and `orphan-driver` entries additionally carry `heID` (the orphaned id); `missing-required` entries omit `heID` (the value is null by definition of the signal). If the manifest carried a non-null but invalid heID (e.g. whitespace-padded `"  "`) that was normalized to null, the original raw value is recoverable from the sibling `dataQualityWarnings[]` entry -- join on `(componentType, componentName, componentId)`. `skipped-malformed-component` warnings cannot be joined because they lack `componentName`/`componentId`.
  - If any data-quality warnings exist across packages, they are also aggregated at top-level `result.dataQualityWarnings[]`
  - `orphanDetection` (`{enabled, reason?}`) -- `enabled=false` means the Apps Code registry fetch failed; `reason` discloses whether the body was empty ("Empty response from /hub2/userAppTypes") or had an unexpected shape ("expected JSON array, got `<actualTypeName>`: ..."). `<actualTypeName>` is one of: `Map`, `null`, or `unknown` (any non-List, non-null, non-Map shape -- the parsed JSON's runtime type). When the payload preview exceeds 200 chars, `reason` truncates to 200 and appends `" (truncated)"`
  - `orphanDriverDetection` (`{enabled, reason?}`) -- same structure as `orphanDetection` but for the Drivers Code registry (`/hub2/userDeviceTypes`)
  - When one detection system is disabled the `summary` appends `"(partial: <name> disabled this call -- see <name> reason)"`; when both are disabled the suffix uses `"reasons"` (plural): `"(partial: orphanDetection/orphanDriverDetection disabled this call -- see orphanDetection/orphanDriverDetection reasons)"`. Note: when `orphanDetection.enabled` or `orphanDriverDetection.enabled` is `false`, the corresponding orphan-* signals cannot fire. `packagesWithActionableDrift` will reflect only packages with a `missing-required` signal, and may UNDER-COUNT the actual drift state. Inspect `orphanDetection` / `orphanDriverDetection` before treating `packagesWithActionableDrift == 0` as 'clean'.
  - Separately, a `required=true` component whose heID is non-scalar (List/Map/Boolean) produces a `heid-non-scalar-dropped` data-quality warning rather than a `missing-required` signal. The component is dropped before the required check, so a broken-but-required component will not contribute to `packagesWithActionableDrift`. Inspect `dataQualityWarnings[]` for `heid-non-scalar-dropped` entries on required components before treating a zero count as fully clean.
  - Data-quality issues land in `dataQualityWarnings[]` on the per-package entry and are aggregated at top-level `result.dataQualityWarnings[]` -- they do NOT inflate `totalDriftSignals`. Currently the only data-quality warning types are: `heid-whitespace-normalized` (whitespace-padded heID normalized to trimmed value; component is **kept**, drift checks continue against the trimmed heID), `heid-non-scalar-dropped` (heID is not a Number or String; component is **dropped**, no drift checks run), `empty-heid` (blank heID normalized to null; entry has `componentName`, `componentId`, `_warning`), `skipped-malformed-component` (non-Map component entry; entry has only `type`, `componentType`, `_warning` -- no `componentName`/`componentId` because the source was not a Map). The kept/dropped distinction lets consumers determine whether drift checks ran for a given component.
  - Files are not checked for drift **because** HPM tracks file components by name only (no heID), so there is no registry membership to verify; `skippedFileCount` is accordingly not emitted on drift entries
  - Top-level `skippedMalformed[]`: manifest URLs whose value was not a Map (symmetric with `list_hpm_packages`)
  - When `packageFilter` matched nothing: `filterMatchedZero=true` plus `availablePackages[]` for filter-spelling sanity check; in this case `packagesChecked == 0` and `summary` reads `"No drift detected across 0 tracked packages."`
  - Limitation: heID-presence-only. HPM stores no source hashes, so post-install edits via `update_app_code` are not detectable.
  - Example call: `manage_hpm(tool="get_hpm_drift", args={packageFilter: "BOND"})` — checks only packages whose name contains "BOND"
  - Design note: `list_hpm_packages` emits `_warning` inline on each component **because** consumers typically enumerate components per-package; `get_hpm_drift` emits `dataQualityWarnings[]` as a separate aggregate **because** consumers need to distinguish actionable drift signals from data-quality issues without conflating them in a single `signals[]` count

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

#### `update_native_app` capability reference

Reference for the `update_native_app` structured shortcuts (`addTrigger`, `addAction`, `addRequiredExpression`). The schema descriptions point here so flat-mode `tools/list` can stay under the 124 KB cap; gateway-mode catalog responses still carry the full enumerations inline. For machine-readable schemas, pass `{discover: true}` on `addTrigger` or `addAction` -- both return live structured Maps from the running code.

##### `addTrigger` capability families

- **Device-state** (Switch / Motion / Contact / Lock / Garage / Door / Valve / Window Shade / Presence / Power source): `capability`, `deviceIds`, `state` (`'on'`, `'active'`, `'open'`, `'unlocked'`, etc.)
- **Multi-device "all of these"**: add `allOfThese=true` to the device-state spec
- **Numeric** (Temperature / Humidity / Battery / Illuminance / Power / Energy / CO2 / Dimmer / Thermostat setpoints): `capability`, `deviceIds`, `comparator` (`=`, `<`, `>`, `<=`, `>=`, `*changed*`), `value`
- **Button** (`capability='Button'`): `deviceIds`, `buttonNumber`, `state` (`pushed` | `held` | `doubleTapped` | `released`)
- **Custom Attribute** (`capability='Custom Attribute'`): `deviceIds`, `attribute` (the attribute name), `comparator`, `value`
- **And-stays sticky modifier** (any device-state or numeric trigger): add `andStays={hours, minutes, seconds}` to the spec
- **Time / Sunrise / Sunset** (`capability='Certain Time (and optional date)'`): `time` (`'A specific time'` | `'Sunrise'` | `'Sunset'`), `atTime`, `offset` (minutes, for sunrise/sunset)
  - `atTime` semantic: `'HH:mm'` form (e.g. `'17:00'`) = **DAILY-recurring** trigger that fires every day at that wall-clock time. Full ISO datetime (e.g. `'2026-04-29T17:00:00'` or `'2026-04-29T17:00:00.000-0500'`) = **ONE-SHOT dated** trigger that fires once on that specific date. Forms without timezone are auto-normalized to hub local tz; explicit-offset and Zulu forms are normalized to UTC equivalent.
- **Mode** (`capability='Mode'`): `state='Night'` OR `state=['Away','Night']` (mode names, case-insensitive) OR `modeIds=['3']` OR `modeIds=['3','5']` (IDs directly, from `get_modes`).
  - **IMPORTANT:** writes `modesX<N>` internally — do NOT pass `tstate` or `rawSettings.tstate` for Mode triggers (silently ignored; renders as Broken Trigger). Use `get_modes` to list valid mode names/IDs.
- **Periodic Schedule** (`capability='Periodic Schedule'`): recurring schedule via the dedicated periodic sub-page. Spec:
  ```
  periodic={
    frequency: 'Hourly'|'Daily'|'Cron String'|...,
    everyN: <int>,                 // for "every N <unit>" mode (Hourly/Daily)
    startingTime: 'HH:mm',         // start-time for everyN modes
    weekdaysOnly: <bool>,          // Daily-only
    selectedHours: [9,12],         // Hourly-only, alternative to everyN
    selectedDaysOfMonth: [1,15],   // Daily-only, alternative to everyN/weekdays
    minutesOffset: <int>,          // Hourly-only, when not using everyN (startingHCX1)
    cronString: '0 * * * *',       // Cron String mode
    rawSettings: {…}               // escape hatch for periodic-page fields not yet mapped
  }
  ```
  Without `periodic`, RM commits a phantom row with description `?`. The tool walks the periodic sub-page (`whichPeriod1` → `everyN`/select → time → Done) so the trigger description bakes correctly.

- **Inline conditional trigger** (`trigger.condition` Map): `{capability, deviceIds?, state?, comparator?, value?, attribute?, variable?, compareToVariable?, not?, rawSettings?}`. Drives the conditional-trigger sub-wizard inline (sets `isCondTrig.<N>=true` + `condTrig.<N>=a` + the condition fields + clicks hasAll). Convenience: pass singular `deviceId: N` (integer) instead of `deviceIds: [N]` (array) -- the dispatcher normalizes before writing. If both `deviceId` and `deviceIds` are supplied, `deviceIds` wins.
  - The following per-capability extended shapes work on `trigger.condition`: Variable (incl. `compareToVariable`), Custom Attribute (`attribute` + `comparator` + `state`/`value`), and enum/numeric device-state conditions. **Mode-via-picker (`modeIds`), Between two times (`start`/`end`), and `compareToDevice` Maps are NOT yet supported on `trigger.condition`** -- `_rmBuildCondition` is a static direct-write helper, not the shared `_rmWalkConditionReveal` walker. For those shapes use `addRequiredExpression.conditions[]` or `addAction.expression.conditions[]` instead.
  - `compareToVariable` is **addTrigger-only** (Variable LHS compared to another hub variable). `addRequiredExpression` and `addAction` IF-expressions support `value` (literal numeric RHS) for Variable conditions but NOT `compareToVariable` (variable-vs-variable) -- the `selectTriggers` sub-wizard exposes the `xVarR_<N>` field for variable-vs-variable comparison, while STPage and doActPage do not expose an equivalent field in tested firmware versions.

##### `addAction` capability families

For machine-readable per-field schemas (with `action` enums and per-action required fields), see `docs/rm_action_subtype_schemas.md` — that doc is generated from `_rmActionSchemaForDiscover()` and stays in sync with the live code.

- **Switch** (`capability='switch'`): `action='on'`/`'off'`/`'toggle'`/`'flash'` + `deviceIds`. `action='setPerMode' + deviceIds + perMode={modeIdOrName: 'on'|'off', ...}`. `action='choosePerMode' + perMode={modeIdOrName: {on: [devIds], off: [devIds]}, ...}`.
  - **NOTE:** `action='flash'` starts a flash schedule on devices that support `.flash()` (Hue groups, many Z-Wave/Zigbee dimmer modules). RM 5.1 has NO native "stop flash" action subtype — calling `switch.on`/`.off` afterward does NOT cancel the flash schedule. To stop a running flash from within a rule, use `capability='runCommand'` with `command='flashOff'` on the same device list.
- **Dimmer** (`capability='dimmer'`):
  - `setLevel` + `deviceIds` + `level` (0–100) [required] + optional `fadeSeconds`
  - `toggle` + `deviceIds` + `level` (0–100) [required — the level to set when toggling from off to on] + optional `fadeSeconds`
  - `adjust` + `deviceIds` + `adjustBy` (-100..100) [required] + optional `fadeSeconds`
  - `fade` + `deviceIds` + `targetLevel` [required] + `minutes` [required] + `direction='raise'|'lower'` + optional `intervalSeconds`
  - `stopFade` (no fields)
  - `startRaiseLower` + `deviceIds` + `direction='raise'|'lower'`
  - `stopChanging` + `deviceIds`
  - `setLevelPerMode` + `deviceIds` + `perMode={modeIdOrName: level, ...}` + optional `fadeSeconds`
- **Color** (`capability='color'`, RGBW bulbs):
  - `setColor` + `deviceIds` + `colorName` + optional `level`
  - `toggleColor` + `deviceIds` + `colorName` + optional `level`
  - `setColorPerMode` + `deviceIds` + `perMode={modeIdOrName: {color: 'Red', level: 70}, ...}`
- **Color Temperature** (`capability='colorTemp'`):
  - `setColorTemp` + `deviceIds` + `kelvin` + optional `level`
  - `toggleColorTemp` + `deviceIds` + `kelvin` + optional `level`
  - `fadeColorTemp` + `deviceIds` + `targetKelvin` + `minutes` + `direction='raise'|'lower'`
  - `stopColorTempFade` (no fields)
  - `setColorTempPerMode` + `deviceIds` + `perMode={modeIdOrName: {kelvin: 2700, level: 70}, ...}`
- **Button** (`capability='button'`, pushable-button devices): `push` + `deviceIds` + `buttonNumber`. `pushPerMode` + `deviceIds` + `perMode={modeIdOrName: buttonNumber, ...}`. `choosePerMode` + `buttonNumber` + `perMode={modeIdOrName: [deviceIds], ...}`.
- **Run Custom Action** (`capability='runCommand'`): `command` + `deviceIds` + `capabilityFilter` (default `'Switch'`) + optional `parameters=[{type:'number',value:75},...]` + optional `useLastEventDevice`. Each parameter entry may be a literal (`{type:'number', value:75}`) or variable-sourced (`{type:'number', variable:'myVar'}`); the two forms may be mixed across slots. The `type` field is lowercase (`number`, `decimal`, `string`). Calls any device-driver command (`off`, `on`, `setLevel`, `flashOff`, `refresh`, custom-driver verbs, etc.) on the device list. Use this to call commands not exposed by the higher-level capability mappings.
- **File IO** (`capability='fileWrite'`/`'fileAppend'`/`'fileDelete'`): `fileWrite` + `fileName` + `content` (overwrites). `fileAppend` + `fileName` + `content` (file must exist; `localFile` is an enum picker). `fileDelete` + `fileName`.
- **Z-Wave Polling** (`capability='zwavePoll'`): `action='start'`/`'stop'` + `deviceIds` (Z-Wave switches/dimmers only) + `target='switches'|'dimmers'`.
- **Lock** (`capability='lock'`): `action='lock'`/`'unlock'` + `deviceIds`.
- **Thermostat** (`capability='thermostat'`): `action=(any)` + `deviceIds` + optional `mode`/`fanMode`/`heatingSetpoint`/`coolingSetpoint`/`adjustHeating`/`adjustCooling`.
- **Shade/blind** (`capability='shade'`): `open`/`close`/`stop` + `deviceIds`. `setPosition` + `deviceIds` + `position` (0–100).
- **Fan** (`capability='fan'`): `setSpeed` + `deviceIds` + `speed` (low/med/high/auto/etc.). `cycle` + `deviceIds`.
- **Mode** (`capability='mode'`): `action='setMode'` + `modeId` (Integer) or `modeName` (String, case-insensitive). When `modeName` is supplied it is resolved to the numeric mode ID via `location.modes` before the write; an unknown name fails fast with the list of valid mode names. Use `get_modes` to inspect available modes first. Note: `addAction` mode uses the `modeName` field for explicit name-based resolution; `addTrigger` mode uses the generic `state` field instead because triggers cover a superset of device-state events where a single field serves multiple capability types -- `modeName` vs `state` is an intentional surface difference, not a typo.
- **Hub Variable** (`capability='setVariable'`, alias `'variable'`): `variable` + `value` (numeric constant) OR `sourceVariable` (copy from another hub variable). Both `variable` and `sourceVariable` must be existing hub variable names -- unknown names are rejected before any write. `value` and `sourceVariable` are mutually exclusive; providing both is rejected. See `addAction setVariable` in `docs/rm_action_subtype_schemas.md` for the full field reference.
- **Logging / Messaging**: `capability='log' + message`. `capability='notification' + deviceIds + message`. `capability='httpGet' + url`. `capability='httpPost' + url + body + optional contentType`. `capability='ping' + ip`.
- **Music/Sound** (`capability='volume'`/`'mute'`/`'chime'`/`'siren'`): `volume + deviceIds + level`. `mute + action='mute'/'unmute' + deviceIds`. `chime + deviceIds + optional playStop/soundNumber`. `siren + deviceIds + optional sirenAction`.
- **Rules** (`capability='privateBoolean'`/`'runRule'`/`'cancelTimers'`/`'pauseRule'`): `privateBoolean + ruleIds + value (Boolean)`. `runRule + ruleIds` (runs actions). `cancelTimers + ruleIds`. `pauseRule + action='pause'/'resume' + ruleIds`.
- **Device control**: `capability='capture' + deviceIds`. `capability='restore'` (no fields). `capability='refresh' + deviceIds`. `capability='poll' + deviceIds`. `capability='disableDevice' + action='disable'/'enable' + deviceIds`.
- **Flow control** (delay/wait/repeat/exit/comment/conditional):
  - `delay` + `hours`/`minutes`/`seconds` + optional `cancelable`/`random` OR `variable=<varName>` (variable-sourced seconds)
  - `delayPerMode` + `perMode={modeIdOrName: {hours, minutes, seconds}, ...}`
  - `cancelDelay`, `exitRule`, `stopRepeat` (no fields)
  - `comment` + `text`
  - `repeat` + `hours`/`minutes`/`seconds` + optional `times` + `stoppable`
  - `repeatWhile` + `expression={conditions:[...], operator?:..., operators?:[...]}` + optional `hours`/`minutes`/`seconds`/`times`/`stoppable`
  - `waitExpression` + `expression={...}` + optional `delay={hours,minutes,seconds}` + `useDuration=true|false`
  - `waitEvents` + `events=[{capability, deviceIds, state, andStays?}, ...]`. **LIMIT**: only ONE `waitEvents` action per rule; RM 5.1 stores wait events in global per-rule settings (not per-action), so a second `waitEvents` action silently overwrites the first. Combine multiple waits into one action's `events` array, or split into chained sub-rules.
  - `ifThen` + `expression={...}` (opens IF block; close with `endIf`)
  - `elseIf` + `expression={...}` (continues IF block; needs preceding `ifThen`)
  - `else` (no fields; needs preceding `ifThen` or `elseIf`)
  - `endIf` (no fields; closes the IF block)

  Per-condition shape for `expression.conditions[]` (same as `addRequiredExpression`): `{capability, deviceIds?, state?, comparator?, value?, attribute?, not?, rawSettings?}`. Convenience: pass singular `deviceId: N` (integer) instead of `deviceIds: [N]` (array) -- the dispatcher normalizes before writing. If both `deviceId` and `deviceIds` are supplied, `deviceIds` wins. The same per-capability extended shapes apply -- Mode `modeIds`, Between two times `start`/`end` (`{type:'clock'|'sunrise'|'sunset', time?:'HH:mm', offset?:<minutes>}`; clock `time` is hub-local wall-clock, converted to ISO datetime internally; requires hub `location.timeZone` to be set in Settings > Location and Modes), Variable `variable`+`comparator`, Custom Attribute `attribute`+`comparator`, compareToDevice Map (when the RHS-type toggle is absent on the firmware, `settingsSkipped` entries carry `fallbackApplied: true|false`). The shared walker `_rmWalkConditionReveal` handles all per-capability reveal sequences for both `ifThen`/`elseIf`/`repeatWhile`/`waitExpression` (doActPage) and `addRequiredExpression` (STPage). `compareToVariable` (variable-vs-variable) is NOT supported on this row -- it is `addTrigger.condition`-only. See the "Extended per-capability spec shapes" section below.

  **Note: nested `subExpression` is REQUIRED-EXPRESSION-ONLY today.** `addAction` (ifThen/elseIf/repeatWhile/waitExpression) rejects nested `subExpression` in `expression.conditions[]` with a targeted error (`"nested subExpression on this row is not yet supported"`) -- flatten the conditions list or move the nested expression to a Required Expression. `addRequiredExpression` supports nesting today.

  Note: some sensor capabilities (Water sensor, Smoke detector, Carbon monoxide detector, Tamper alert, Acceleration) report discrete events -- use capability-specific state names (e.g. `'wet'`/`'dry'`, `'detected'`/`'clear'`, `'active'`/`'inactive'`) rather than numeric comparator conditions. Carbon dioxide sensor is intentionally EXCLUDED here: `CarbonDioxideMeasurement` is numeric ppm (comparator + value), not a discrete enum.

##### `addRequiredExpression` STPage capability list

RM 5.1 Required Expression conditions accept these `capability` values (per-condition):

- **Device-state**: `Switch`, `Motion`, `Contact`, `Lock`, `Presence`, `Smoke detector`, `Water sensor`, `Tamper alert`, `Acceleration`, `Carbon monoxide detector`, `Carbon dioxide sensor`, `Power source`, `Window Shade`
- **Numeric**: `Battery`, `Dimmer`, `Energy meter`, `Fan Speed`, `Humidity`, `Illuminance`, `Power meter`, `Temperature`, `Thermostat cool setpoint`, `Thermostat fan mode`, `Thermostat heat setpoint`, `Thermostat mode`, `Thermostat state`
- **Time-based**: `Days of week`, `Between two dates`, `Between two times`, `On a Day`
- **Hub state**: `Mode`, `Private Boolean`
- **Variable comparison**: `Variable`
- **Custom / other**: `Custom Attribute`, `Last Event Device`, `Lock codes`

Note: `Private Boolean` is only valid in Required Expressions -- it does NOT appear in the IF-expression capability list used by `ifThen`/`elseIf`/`repeatWhile`/`waitExpression`.

Note: some sensor capabilities (Water sensor, Smoke detector, Carbon monoxide detector, Tamper alert, Acceleration) report discrete events rather than a continuous enum state. Pass `state: 'wet'` / `state: 'dry'` for Water sensor, `state: 'detected'` / `state: 'clear'` for detector types, `state: 'active'` / `state: 'inactive'` for Acceleration -- NOT a comparator-based numeric condition. Carbon dioxide sensor is intentionally EXCLUDED: `CarbonDioxideMeasurement` is numeric ppm (use comparator + value), not a discrete enum; the names look superficially symmetric to Carbon monoxide detector but RM 5.1 treats them differently. See `docs/rm_action_subtype_schemas.md` for the full state-value table.

##### Extended per-capability spec shapes

Applies to `addRequiredExpression.conditions[]` (STPage) and `addAction.expression.conditions[]` (doActPage); the shared walker `_rmWalkConditionReveal` handles every per-capability reveal sequence below. For `addTrigger.condition`, see the narrowed support list in the "Inline conditional trigger" entry above -- Mode-via-picker / Between two times / compareToDevice are NOT yet available on the trigger row.

**Mode**: `{capability:'Mode', state:'Night'}` or `{capability:'Mode', modeIds:['3']}`.
  The walker resolves mode names to IDs via `location.modes` and writes the firmware-assigned `modes<N>` picker (e.g. `modes1`) discovered from the live schema (not hardcoded). Note: triggers use `modesX<N>`; STPage/doActPage conditions use `modes<N>` (no `X` prefix).

**Between two times**: `{capability:'Between two times', start:{type:'clock'|'sunrise'|'sunset', time?:'HH:mm', offset?:<minutes>}, end:{...same shape}}`.
  `time` is required when `type='clock'` (walker converts `HH:mm` to ISO datetime with hub-local TZ offset internally); `offset` is required when `type='sunrise'` or `'sunset'`. User-supplied `HH:mm` is interpreted as hub-local wall-clock time; the walker constructs ISO datetime with the anchor-date timezone offset internally so DST shifts between now and the January anchor do not affect rendering. Firmware fields: `starting<N>` (type enum), `startingA<N>` (clock time), `ending<N>`, `endingA<N>`/`endSunriseOffset<N>`. Precondition: hub timezone must be configured (Settings > Location and Modes). If `location.timeZone` is null, the walker throws before touching the wizard -- set hub timezone first.

**Variable comparison**: `{capability:'Variable', variable:'<hubVarName>', comparator:'=', value:<v>}`.
  Writes the firmware-assigned variable-name picker discovered from the live schema. Fail-loud if the variable name is not in the revealed enum AND the schema option list is non-empty. When the schema option list is empty (firmware lazily-populates the enum, or probe-timing race), the walker logs warn + pushes a `variable-validation` / `api_unavailable` sentinel to `settingsSkipped` and proceeds with the write unvalidated (`partial:true`).

**Device-relative comparison**: `{capability:'Temperature', deviceIds:[N], comparator:'>', compareToDevice:{deviceId:M, attribute:'temperature', offset?:-2}}`.
  Compares a device's attribute to another device's attribute (with optional numeric offset) rather than a literal threshold. The RHS-type selector is discovered from the live schema. Firmware-variant field names the walker discovers between: `rDev2_<N>`/`refDev_<N>`/`compareDevId_<N>` for reference-device, `rCustomAttr2_<N>`/`refAttr_<N>`/`compareAttr_<N>` for reference-attribute, `offset_<N>`/`devOffset_<N>` for optional offset. The walker fails loud if reference-device or reference-attribute is absent post-rhsType (spec-required), and degrades with an `offset_field_not_revealed` sentinel if optional offset is absent.

**Sub-expressions (parens) -- addRequiredExpression-only**: for nested expressions like `P1 AND (P2 OR P3)`, a condition entry can also be `{subExpression: {conditions: [<inner conds>], operator?: 'AND'|'OR'|'XOR', operators?: [...]}}`. The STPage walker recursively handles nested sub-expressions of arbitrary depth. `addAction` (`ifThen`/`elseIf`/`repeatWhile`/`waitExpression`) does **NOT** support `subExpression` on this row -- the doActPage walker rejects it with `"nested subExpression on this row is not yet supported"`. Flatten the conditions list or move the nested expression into a Required Expression.

##### Partial-success and trailing-updateRule response slots

`addRequiredExpression`, `addTrigger`, and `addAction` all return rich envelopes. Beyond `success`/`partial`/`settingsSkipped`/`repairHints`, the following slots help callers diagnose without log-grep:

- `settingsSkipped[]` sentinel reasons callers may see:
  - `rhs_type_not_revealed` -- compareToDevice RHS-type toggle absent on firmware. Entry also carries `fallbackApplied: true` (literal `state_<N>` fallback was written) or `false` (no state/value to fall back to; condition is incomplete).
  - `offset_field_not_revealed` -- compareToDevice optional offset field absent. Entry carries the requested `offset` value for reference. Flips `partial:true`.
  - `api_unavailable` paired with `key: "variable-validation"` -- Variable picker returned an empty option list. Schema-side validation was skipped; write still proceeds. Flips `partial:true`.
  - `reveal_fallback_to_existing_field` -- the walker matched an already-visible field instead of a newly-revealed one (static-schema firmware path). **Informational** -- does NOT flip `partial` by itself.
- Trailing-updateRule failure slots:
  - `addRequiredExpression`: `updateRuleFailed: true` + `expressionNotLive: true` + `updateRuleError: <message>` when the post-commit `updateRule` click is rejected. `success` flips false and `partial` flips true; `repairHints` adds a recovery line pointing at `update_native_app(button='updateRule', confirm=true)`.
  - `addTrigger`: `updateRuleFailed: true` + `subscriptionsNotLive: true` + `updateRuleError: <message>` with the same `success`/`partial` flip. The trigger row IS in the rule's appSettings but the running rule instance never re-subscribed to the device events -- retry `updateRule` to populate subscriptions.
  - `addLocalVariable`: `updateRuleFailed: true` + `variableNotLive: true` + `updateRuleError: <message>` with the same `success`/`partial` flip. The variable IS created on the hub but the rule's action map never re-evaluates against the new variable until updateRule fires -- retry as above.
  - `addTriggers` / `addActions` (bulk path): `updateRuleFailed: true` + `subscriptionsNotLive: true` + `updateRuleError: <message>` with the same `success`/`partial` flip. The per-item adds IS committed (triggers/actions arrays still surface on the success-shape keys) but the running rule instance never re-subscribed -- retry as above.
  - `patches`: `updateRuleFailed: true` + `patchesNotLive: true` + `updateRuleError: <message>` with the same `success`/`partial` flip. The patch ops landed but the rule will not re-evaluate / re-subscribe until updateRule fires -- retry as above.
  - `removeTrigger` / `modifyTrigger` / `removeAction` / `clearActions` / `replaceActions` / `moveAction`: `updateRuleFailed: true` + `subscriptionsNotLive: true` + `updateRuleError: <message>` with the same `success`/`partial` flip. The mutation IS committed but the rule never re-subscribed -- retry as above.

**Naming-divergence rationale.** The `*NotLive` slot name encodes the **consequence** of the trailing-updateRule failure, not the operation that committed. Each tool surfaces the consequence that matters most to a caller deciding how to recover:

- `expressionNotLive` (addRequiredExpression): the rule's gate evaluator never re-picked-up the new expression -- the gate stays at its prior state until updateRule fires. The recovery path is the same retry, but the diagnostic story callers tell themselves is "my new gate isn't being evaluated yet."
- `subscriptionsNotLive` (addTrigger, bulk addTriggers/addActions, action-mutation, trigger-mutation): the running rule instance never re-subscribed to the device events its trigger depends on -- the rule will not fire on those events until updateRule fires. Diagnostic story: "my new/changed trigger doesn't get the event."
- `variableNotLive` (addLocalVariable): the variable IS created on the hub but the rule's action map never re-evaluates against the new variable until updateRule fires. Diagnostic story: "my actions can't read this variable yet."
- `patchesNotLive` (patches): the patch ops landed but the rule won't re-evaluate / re-subscribe (catch-all because patches can bundle any mix of the above). Diagnostic story: "I just patched a bunch of stuff and nothing is taking effect."

All four share `updateRuleFailed` and `updateRuleError` for the common facts. The slot-name divergence is intentional -- a caller-facing diagnostic, not a code-side typology. Single-name unification was considered and rejected because it would force every caller to read the operation-type to interpret the consequence.

##### `clearActions` / `replaceActions` commit semantics + rare defensive partial

Arg shapes: `clearActions: true` (boolean, no index -- removes every action). `replaceActions: [<spec>, ...]` (array; each entry takes the same shape as an `addAction` item). `replaceActions: []` clears all actions without adding any -- equivalent to `clearActions: true`.

`clearActions` / `replaceActions` commit the trashActs delete **synchronously**: the tool submits the full selectActions page form (the complete form-action envelope plus the serialized page state, exactly as the native UI does), which makes Rule Machine run its `trashActs` submitOnChange handler in-band during the POST. The selected actions are deleted by the time the call returns, so the common-case response is a normal success with the actions gone.

A bare settings-write of `trashActs` (the prior approach) stored the value but never ran the handler, so the delete could strand. The full-form submit fixes that root cause.

The tool keeps a thin defensive net: it still verifies the delete via a short retry loop after the submit. In the rare residual case where verification keeps seeing the actions present (a stuck `state.editAct` no-op, or an uncommon firmware commit lag), the tool returns the eventual-consistency partial below rather than a hard failure. This should almost never fire now that the handler runs synchronously:

```json
{
  "success": false,
  "partial": true,
  "asyncCommitLikely": true,
  "appId": "<id>",
  "stage": "clearActions.verify_absent",
  "httpWriteStatus": 200,
  "actionsRequestedForRemoval": [1, 2],
  "actionsStillPresent": [1, 2],
  "possibleStateEditAct": false,
  "wizardStuck": false,
  "error": "<existing diagnostic message>",
  "safeRecovery": {
    "recommended": "verify-then-decide",
    "verifyVia": "get_app_config(appId: <id>)",
    "ifActionsAbsent": "treat as success -- clearActions committed post-response",
    "ifActionsPresent": "wait 15s, then call get_app_config to re-check. If actions still present, retry clearActions, or clear state.editAct via update_native_app(button='cancelAct', pageName='doActPage', confirm=true) first.",
    "avoid": ["cancelTrash"]
  },
  "backup": "<backup>",
  "restoreHint": "<restoreHint>",
  "verifyHint": "<verifyHint>"
}
```

The caller's recovery path is to call `get_app_config(appId)` and inspect the actions list. If absent, treat the operation as a success -- the delete committed between the POST return and this verify fetch. If still present, retry. **Do not** invoke `cancelTrash` to recover -- in trash-confirmation mode it can commit pending deletes rather than abort.

For `replaceActions`, this same retry-window-expired case on the inner clearActions step yields `stage: 'replaceActions.clear_committed_late_no_add'`. The add half is **not attempted** -- because the clear may have committed asynchronously, completing the add would risk a double-write if the caller retries the whole replace after seeing the partial. The original action specs are echoed back as `pendingActionsToAdd` so the caller can finish the replace via `addAction` (or bulk `addActions`) once `get_app_config` confirms the clear committed. `clearActionsResult` exposes a fingerprint subset of the standalone clearActions partial: `{stage, asyncCommitLikely, actionsRequestedForRemoval, actionsStillPresent, error}` -- the outer envelope carries the `safeRecovery` / `backup` / `restoreHint` / `verifyHint` slots. On the replaceActions sub-shape, `verifyHint` AND `error` are replaced with copy that names the data-loss-protection rationale rather than echoing clearActions' diagnostic.

`removeAction`, `removeTrigger`, and `modifyTrigger` remain on the legacy flat error shape (no `asyncCommitLikely` envelope) **because** each operates on a single row and there is no add half, so the data-loss case (add-half double-write on retry) that justifies the richer recovery contract does not apply.

When `clearActions` / `replaceActions` appears inside a `patches` sub-spec and hits the retry-window-expired case, the raw `[asyncCommitLikely]` marker is stripped from the per-patch error **but** the structured envelope is NOT emitted -- because patches continues over subsequent ops and the per-patch `success: false` plus the outer `partial:` flag already surface the failure, the caller instead receives a flat `{success: false, op: '...', error: '...', spec: ...}` entry in `patches[i]` and should match on the "after 10s of retries" phrase to identify the eventual-consistency case.

Implication: when a `patches` sub-spec containing `clearActions` / `replaceActions` hits the async-commit case, subsequent patch ops in the same call still execute and the trailing `updateRule` still fires. The first patch is reported as failed in `patches[i]` but the call is not aborted. For atomic clear-then-add semantics, use top-level `replaceActions` (which skips the add half on async-commit) rather than chaining patches.

Degenerate-case semantics: `actionsRequestedForRemoval: null` indicates the pre-write snapshot fetch failed; `actionsStillPresent: null` indicates the post-window re-fetch failed; `possibleStateEditAct: false` is the safe default when `_rmGetStateEditAct` fails. These three are best-effort diagnostic slots -- `null` does NOT mean "field absent," it means "fetch failed on this side; the structured envelope is still actionable via the other fields and `safeRecovery`."

#### `create_native_app` reference

##### appType options

`appType` (default: `rule_machine`) selects which class of native app to create:

- `rule_machine` — Rule Machine 5.1 (the only registered type today; verified live).
- Other classic SmartApps (Room Lighting, Button Controllers, Basic Rules, Notifier, Groups+Scenes, Visual Rules) use the same endpoint family — register them in `_appTypeRegistry` to enable creation. `update_native_app` / `delete_native_app` already work on them today via their `appId`.

##### Partial-success protocol

The tool ALWAYS creates the rule shell (you get an `appId` back) even if some triggers/actions fail to fully bake. Inspect the result:

- `partial: true` + `partialTriggers: [N, ...]` / `partialActions: [N, ...]` → some pieces are incomplete (this includes any per-item result with `partial: true` OR `success: false`).
- `repairHints: [...]` → concrete next-step instructions.
- Each per-trigger / per-action result has its own `success`, `partial`, `settingsSkipped`, `repairHints`, and `health` block. `success: true, partial: true` on an inner result means the row was written but needs repair.

The right move when `partial: true` is to follow the `repairHints`, NOT to delete the rule and retry from scratch. Tool-only repair via `update_native_app(walkStep={...})` / `replaceActions` / `removeAction` can usually finish the job. Only declare failure after exhausting those repair attempts.

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
