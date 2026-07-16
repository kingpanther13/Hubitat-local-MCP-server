# MCP Tool Guide

Detailed reference for MCP Rule Server tools. Consult this when tool descriptions need clarification.

## Category Gateway Proxy (v0.8.0+)

As of v0.8.0, the server uses **domain-named gateways** to organize lesser-used tools behind gateway tools. The MCP `tools/list` shows 36 items (13 core + 23 gateways) covering 117 total tools. Use `hub_search_tools` to find any tool by natural language query.

**How to use a gateway:**
1. Call the gateway with no arguments to see full parameter schemas for all its tools
2. Call with `tool='<tool_name>'` and `args={...}` to execute a specific tool

Gateway verbs encode mutation: **`hub_read_*`** gateways are pure-read (every sub-tool read-only), **`hub_manage_*`** gateways contain at least one write. A read tool may appear in BOTH a `hub_read_*` gateway and a mixed `hub_manage_*` gateway (multi-membership).

**Read gateways (8):** `hub_read_apps_code` (11), `hub_read_dashboards` (2), `hub_read_devices` (5), `hub_read_diagnostics` (9), `hub_read_files` (2), `hub_read_rooms` (2), `hub_read_rules` (6), `hub_read_variables` (3)

**Manage gateways (15):** `hub_manage_backup` (4), `hub_manage_code` (10), `hub_manage_custom_rules` (8), `hub_manage_dashboards` (6), `hub_manage_destructive_ops` (4), `hub_manage_devices` (9), `hub_manage_diagnostics` (7), `hub_manage_files` (4), `hub_manage_logs` (6), `hub_manage_mcp` (1), `hub_manage_native_rules_and_apps` (11), `hub_manage_radio` (6), `hub_manage_rooms` (5), `hub_manage_rule_machine` (11), `hub_manage_variables` (8)

All safety gates are preserved: the Read/Write master gate runs centrally in `executeTool()` and re-applies per sub-tool when a gateway routes back through it, and the destructive `confirm`+backup checks run in the handlers of the destructive write tools.

### Disabling Gateways (Flat Tool List)

Gateways exist because most MCP clients struggle with long tool lists. Some clients now ship their own progressive-disclosure layer (deferred tools, built-in BM25 search, etc.) and don't need ours. For those, the **Consolidate tools behind category gateways** setting in the app preferences can be turned off — `tools/list` then advertises every tool individually and `hub_search_tools` is suppressed because it's only useful for navigating gateway-hidden tools.

When the toggle is off, the dispatch contract still holds: every gateway sub-tool already has its own case in `executeTool()`, so a flat-mode client calling `hub_list_rooms` directly hits the same handler as a gateway-mode client calling `hub_manage_rooms` with `tool: "hub_list_rooms"`. If a stale or cached client tries to call a gateway name (e.g. `hub_manage_rooms`) while the toggle is off, the server returns a soft `isError` pointing at the underlying sub-tools rather than silently servicing the call with a gateway-shaped response.

Default is **ON** (gateways enabled). Existing installations keep the gateway behavior on update. Counts here describe the shipped catalog; runtime `tools/list` size varies based on enabled settings (the Read/Write masters, the Custom Rule Engine, the gateway toggle, and any Advanced per-tool/per-gateway overrides all add or remove entries).

### Permission Model

Every tool is gated by two universal masters, **Read** and **Write**, both **ON by default**:

- **Read** (`enableRead`) exposes every read-only / non-destructive tool. With it OFF, those tools vanish from `tools/list` and `hub_search_tools`, and a cached call is rejected: "Read tools are disabled…".
- **Write** (`enableWrite`) exposes every state-changing tool. With it OFF, those tools vanish, and a cached call is rejected: "Write tools are disabled…".

Enforcement is central: one classification gate at the top of `executeTool()` checks each tool against `getReadOnlyToolNames()`. Only an explicit OFF blocks (unset = ON). Gateway *names* aren't classified here — they route back through `executeTool()`, which gates each sub-tool on re-entry. The destructive write tools additionally require `confirm=true` + a backup within 24h (the `confirm`+backup tier below), independent of the Write master.

**Advanced: Per-tool Overrides (#114, deny-only).** Under the app's Advanced settings, `disabled_tools` / `disabled_gateways` can switch off individual tools or whole gateways **below** the masters — they only turn things OFF, never re-enable. A disabled tool (or every tool inside a disabled gateway, including tools shared across gateways) drops from `tools/list` and `hub_search_tools`, and a cached call returns a **distinct** error: "…is disabled in Advanced settings (Per-tool Overrides)…". A disabled tool stays documented in `hub_get_tool_guide` — only its discoverability/dispatch is removed, not its reference.

### `tools/list` Returns the Full Catalog in One Response

`tools/list` returns the complete tool catalog in a single response (no pagination). Pagination was tried briefly (page size 50, cursor-based) but removed because many MCP clients — including the Claude.ai connector — do NOT iterate `nextCursor` automatically, which silently truncated the catalog at the first 50 tools. The full-catalog response is backstopped by the universal response-size guard inside `handleMcpRequest` (124,000-byte threshold) that emits a loud `-32603 "Response too large"` envelope if the catalog ever exceeds the hub's 128KB JSON-RPC limit — better to fail loud than silently lose tools. Stale clients that pass a `cursor` param get the full catalog and find no `nextCursor`, so any iteration loop terminates after one call. Opt-in cursor pagination on `tools/call` (`hub_list_devices`, `hub_list_apps`, `hub_list_rules`, etc.) is unaffected — see the next section.

### `tools/call` Response-Size Guard (v1.3.x+, fail-soft)

Every `tools/call` response is measured before send. If the wire-encoded response exceeds the universal 120,000-byte cap (~11 KB headroom under the hub's 131072-byte (128 KiB) JSON-RPC limit), the inner content is replaced with a structured fail-soft envelope:

```json
{
  "response_too_large": true,
  "truncated": true,
  "estimatedBytes": 145320,
  "sizeLimitBytes": 120000,
  "tool": "hub_list_apps",
  "suggestion": "Set includeHidden=false (the default), narrow via filter ..., or pass cursor to page through the apps list."
}
```

The outer JSON-RPC envelope still reports success (this is not a tool error — the tool ran, the result just didn't fit). Treat `response_too_large=true` as a hint to either (a) narrow your query — the per-tool `suggestion` field names the specific knob — or (b) opt into pagination on tools that support it. The `tool` field reflects the actual sub-tool on gateway-routed calls so you can re-issue a narrower call directly.

Opt-in cursor pagination is currently wired into the following read-only tools. All follow the same contract: omit `cursor` for the full list (backward-compatible, backstopped by the size guard), pass `cursor: ""` for the first page, then iterate `nextCursor` until absent. Cursor is opaque per the MCP convention; non-numeric / out-of-range values reject as `-32602`.

These tools follow an explicit opt-in convention so pre-`cursor` callers see no behaviour change — pagination is genuinely opt-in. (Pre-PR `tools/list` had its own different shape — unconditional pagination at 50/page — which is now removed; see the previous section.)

| Tool | Page size | Notes |
|---|---|---|
| `hub_list_devices` | 50 (when `limit` unset) | Cursor is an alias for the existing `offset`+`limit` shape; `nextCursor` is emitted alongside `nextOffset`. |
| `hub_list_apps` | 50 | `scope='types'` (default): catalog of installable apps. `scope='instances'`: installed app instances; cursor respects `filter` — pages the filtered set. |
| `hub_list_drivers` | 50 | Catalog of installable drivers. |
| `hub_list_libraries` | 50 | Installed Groovy libraries (id, name, namespace, version); source omitted (read via `hub_get_source`). |
| `hub_list_bundles` | 50 | Installed code bundles (Bundle-Manager containers); id, name, namespace, private, and a contains summary. |
| `hub_list_hpm_packages` | 25 | Smaller page because each HPM entry carries the full app/driver/file inventory. |
| `hub_list_rules` | 50 | RM 4.x + 5.x deduplicated rules. |
| `hub_get_custom_rule` | 50 | Legacy MCP rule engine; list mode (omit `ruleId`). |
| `hub_list_variables` | 100 | Pages `hubVariables`; `ruleVariables` stays in full alongside the page. |
| `hub_list_captured_states` | 50 | Capacity warnings still emitted regardless of pagination. |
| `hub_list_backups` | 50 | Sorted newest-first; page boundaries are stable as long as the manifest doesn't change. |
| `hub_list_files` | 100 | File Manager listing. |
| `hub_list_devices` | 50 | `filter='virtual'` mode: MCP-managed virtual devices. |
| `hub_list_rooms` | 100 | Rooms with device counts. |
| `hub_get_device_health` | 100 | Pages `staleDevices`; `unknownDevices` (and `healthyDevices` when `includeHealthy=true`) stay in full. |
| `hub_list_device_dependents` | 100 | Pages `appsUsing`. |
| `hub_get_logs` | 100 | Filters + `limit` apply first; cursor pages within the filtered result. |
| `hub_get_memory_history` | 100 | `limit=0` + cursor pages the full hub ring buffer (the only way to retrieve every entry without losing data). |
| `hub_get_debug_logs` | 100 | Filters apply first; cursor pages within. |

Tools without cursor support (`hub_get_app_config`, `hub_export_native_app`, `hub_get_source`) rely on their existing controls (`includeSettings=false`, `saveAs=<file>`, `hub_list_files`/`hub_read_file` round-trip) plus the universal size guard as the backstop.

## Device Authorization (CRITICAL)

**Exact match rule:**
- If user specifies a device name that EXACTLY matches a device label (case-insensitive OK), use it directly
- Example: User says "turn on Kitchen Light" and device "Kitchen Light" exists → use it

**Non-exact match rule:**
- If no exact match exists, search for similar devices
- Present options to user and **wait for explicit confirmation** before using any device
- Example: User says "use test switch" but only "Virtual Test Switch" exists → ask "Did you mean 'Virtual Test Switch'?"

**Tool failure rule:**
- If a tool fails (e.g., `hub_manage_virtual_device` returns an error), report the failure to the user
- Do NOT silently fall back to using existing devices as a workaround
- Example: If creating a virtual device fails, don't just grab an existing device to use instead

**Why this matters:**
- Wrong device could control critical systems (HVAC, locks, security)
- User trust depends on AI only controlling what they explicitly authorized

**Device allowlist bypass (`bypassDeviceAllowlist`, default OFF):**
- The device tools normally resolve a `deviceId` only against the operator-selected device list (`selectedDevices`) plus MCP-managed virtual devices; an id outside that set returns `Device not found`.
- When the operator turns ON `bypassDeviceAllowlist` (Device Access settings page, or `hub_update_mcp_settings`), that "not found" becomes a fallback that reaches the device through the hub's id-keyed admin endpoints — the per-device tools `hub_get_device`, `hub_get_device_attribute`, `hub_call_device_command` (incl. `waitFor`), `hub_update_device`, and `hub_list_device_events` all gain full parity on ANY hub device by id. `hub_list_devices`, device swap/replace/delete, and device-health are NOT bypassed. Listed and virtual devices are unchanged.
- This is a deliberate operator choice that **removes the device-selection security boundary**. The exact-match / confirm-before-use discipline above matters even more when it is on.

---

## Best-Practice Reference

Surfaced via `hub_get_tool_guide(section='best_practice_reference')`. This section backs the two best-practice aids (issue #299):

- **Require Best-Practice Guide Acknowledgment** (`enableMandatoryBPS`) — **ON by default**. While on, every write tool is blocked until the AI reads this guide section and passes the acknowledgment key it publishes as the `bestPracticeKey` argument. The **Acknowledgment key** value lives only in the in-app guide section (served by `hub_get_tool_guide`), never in this doc, so reading the guide is the only way to obtain it. `hub_get_tool_guide` and `hub_update_mcp_settings` are exempt from the gate, so the AI can always read the key or toggle the gate off — it can never lock itself out.
- **Reactive best-practice hints** — **always on, no toggle**. When a write tool errors, the error gains a one-line pointer to **that tool's own** guide section (not a generic page), so the AI can recover from the failing tool's reference.

The best practices it teaches:

- Prefer **native Rule Machine** (`hub_manage_native_rules_and_apps` / `hub_set_rule`) over the legacy `custom_*` rule engine for new automation work.
- Resolve devices by exact `deviceId` (compare as strings) with `hub_list_devices` before acting.
- Destructive writes need `confirm=true` plus a backup within 24h (`hub_create_backup`).

This is the consolidation target: best-practice content can move here from individual write-tool descriptions over time, kept lean on the wire while the gate guarantees the AI has read it.

---

## Destructive Write Tools - Pre-Flight Checklist

All destructive write tools (the `confirm`+backup tier) require these steps:

1. **Backup check**: Ensure `hub_create_backup` was called within the last 24 hours
2. **Inform user**: Tell them what you're about to do
3. **Get confirmation**: Wait for explicit "yes", "confirm", or "proceed"
4. **Set confirm=true**: Pass the confirm parameter

### Tool-Specific Requirements

**hub_reboot** (via `hub_manage_destructive_ops`)
- Effects: 1-3 min downtime, all automations stop, scheduled jobs lost, radios restart
- Only use when user explicitly requests a reboot

**hub_shutdown** (via `hub_manage_destructive_ops`)
- Effects: Powers OFF completely, requires physical restart (unplug/replug)
- This is NOT a reboot - hub stays off until manually restarted
- Only use when user explicitly requests shutdown

**hub_delete_device** (via `hub_manage_destructive_ops`)
- MOST DESTRUCTIVE tool - NO UNDO
- Intended for: ghost/orphaned devices, stale DB records, stuck virtual devices
- Additional steps: Use `hub_get_device` to verify correct device, warn if recent activity
- For Z-Wave/Zigbee: Warn user to do proper exclusion first to avoid ghost nodes
- All device details logged to MCP debug logs for audit

**hub_call_destructive_ops** (via `hub_manage_destructive_ops`)
- Destructive hub operations selected by `target` + `action` — NO UNDO / can disconnect:
  - `target=zwave|zigbee|matter`: `reset` wipes that radio's network/fabric (orphans all its paired devices); firmware flashes (`device_firmware_start`/`device_firmware_abort`, `zwave_chip_firmware`, `zigbee_firmware`) can brick hardware if interrupted
  - `target=network`: `disconnect_wifi` / `disconnect_ethernet` drop that link — the hub may become unreachable over it
  - `target=cloud`: `disable` severs the cloud controller (Alexa/Google, cloud dashboards, cloud firmware updates, Hub Protect/subscriptions all stop); `enable` restores it
- Three-layer safety gate: Write master + hub backup within 24h + explicit `confirm=true`
- Warn the user about the exact impact before proceeding; only use when the user explicitly requests the operation
- Do NOT power-cycle the hub or device during a firmware flash

**hub_call_zwave** (via `hub_manage_radio`)
- Z-Wave radio operations selected by `action` — network repair is one action
- Repair effects: 5-30 min duration, Z-Wave devices may be unresponsive; best run during off-peak hours
- Only use when user explicitly requests the operation

**hub_delete_room** (via `hub_manage_rooms`)
- Devices become unassigned (not deleted)
- List affected devices to user before proceeding
- Dashboard layouts and automations referencing room may be affected

**hub_create_app** (via `hub_manage_code`)
- Accepts `source` (inline Groovy) OR `sourceFile` (filename in File Manager). Provide exactly one.
- Token-economy tip: upload large source via local CLI first, then pass the filename as `sourceFile`. Avoids re-sending multi-KB source strings on each install attempt.
- Performs post-install verification: after the hub creates the item, fetches it back to confirm the Groovy compiled cleanly. Returns `success: false` with `appId` populated when the hub reports a compile error or the verify response is empty/unparseable, so the error can be inspected via `hub_get_source` (type=app, id). If the hub returns no item ID at all (no `Location` header), `appId` is `null`. Transient verify-fetch failures keep `success: true` but set `verified: false` plus `verifyError`.
- Requires the Write master + confirm + backup <24h.

**hub_create_driver** (via `hub_manage_code`)
- Single-driver mode: supply `source` (inline Groovy) OR `sourceFile` (filename in File Manager). Provide exactly one.
- Bulk mode: supply an `installs` array of objects, each with `source` or `sourceFile`. Cannot combine with single-driver fields.
  - Continue-on-error: errors on individual items do not abort remaining installs. Returns a per-item status array with each item's `driverId`.
  - Top-level `success: true` only if ALL items succeeded.
  - Practical limit: ~10-20 drivers per call (each install is a sequential on-hub compilation, ~1-5 seconds each).
  - Token-economy pattern: upload all driver source files via local CLI, then call bulk `hub_create_driver` once with all `{sourceFile}` entries.
- Performs post-install verification: after the hub creates each item, fetches it back to confirm the Groovy compiled cleanly. Returns `success: false` with `driverId` populated when the hub reports a compile error or the verify response is empty/unparseable, so the error can be inspected via `hub_get_source` (type=driver, id). If the hub returns no item ID at all (no `Location` header), `driverId` is `null`. Transient verify-fetch failures keep `success: true` but set `verified: false` plus `verifyError`.
- Requires the Write master + confirm + backup <24h.

**hub_update_app** (via `hub_manage_code`)
- Supply `appId` + one of `source` / `sourceFile` / `resave` (and/or `oauth`, below).
- **OAuth fold (apps only):** pass `oauth = {enabled (bool, default true), client_id?, client_secret?, refresh_secret? (bool)}` to enable/configure OAuth on the app code definition — the programmatic equivalent of the UI's "Enable OAuth in App". Use it to ship apps with OAuth already on instead of the manual UI step. The result comes back under `result.oauth = {success, enabled, clientId, clientSecret}` (the hub generates `clientId`/`clientSecret` on first enable; omit them to preserve the app's current values, which mirrors the UI's pre-fill — but if the hub can't read the current creds to preserve them, the OAuth leg fails (`success:false`) instead of submitting empty and blanking a live client, so pass `client_id`+`client_secret` to override). Works alone (no source mode required) or alongside a source update (`partial: true` if the source saved but the OAuth leg failed). The app's source must declare OAuth (an `oauth` block in `definition` + `mappings`) for the enable to succeed. **Self-protection:** refuses to alter the MCP server's own app OAuth unless Developer Mode is on — its client id/secret back the live `/mcp` access token, so changing them would break the connection.
- Self-update guard: applies when `appId` matches this MCP server's own app — its installed instance id OR its Apps Code class id (resolved via `_resolveSelfAppClassId`, since a code update targets the class id, which differs from the instance id). Refuses to overwrite that source unless **Enable Developer Mode Tools** is on in the MCP Rule Server app settings (a bad self-update bricks the MCP loop; recovery requires the Hubitat UI or SSH). Every self-update attempt — blocked OR allowed — is audit-logged at WARN under the `hub-admin` component. If the app context is unavailable at call time (rare lifecycle window) the guard fails closed with an ERROR audit log; retry the call.
- Optional `expectedVersion` (integer): optimistic-lock guard. When supplied and the hub's version differs, the update returns `success: false` + `conflict: true` with both `expectedVersion` and `currentVersion` echoed. Use when an agent's read-modify-write spans turns or runs alongside other agents/tools that could mutate the same app. Backup still happens on conflict (intentional: `backupItemSource` has a 1h cache so the parallel-agent retry costs nothing, and the first caller losing the race still has a recovery point). Stringified integers are coerced; explicit `null` is rejected (omit the field entirely to skip the lock).
- Requires the Write master + confirm + backup <24h.

**hub_update_driver** (via `hub_manage_code`)
- Single-driver mode (unchanged): supply `driverId` + one of `source` / `sourceFile` / `resave`. Optional `expectedVersion` (integer): same optimistic-lock semantics as `hub_update_app` — conflict envelope carries `driverId` instead of `appId`.
- Bulk mode: supply an `updates` array of objects, each with `driverId` and one of `sourceFile` / `source` / `resave`, plus optional per-item `expectedVersion`. Cannot combine with single-driver fields (including a top-level `expectedVersion` — put it on each entry instead).
  - Continue-on-error: errors on individual items do not abort remaining updates. Returns a per-item status array. Per-item conflicts (failed lock) carry `conflict: true` + `expectedVersion` + `currentVersion`; per-item thrown errors carry `error` + `errorClass`. Other items in the batch still apply.
  - Top-level `success: true` only if ALL items succeeded.
  - Practical limit: ~20 drivers per call (each update is a sequential on-hub compilation, ~1-5 seconds each).
  - Token-economy pattern: upload all updated driver files via local CLI (curl -F / PowerShell Invoke-RestMethod), then call bulk `hub_update_driver` once with all `{driverId, sourceFile}` pairs.

**hub_delete_item** (type: app|driver|library, via `hub_manage_code`)
- Remove app instances via Hubitat UI first (for apps)
- Change devices to different driver first (for drivers)
- Ensure no drivers/apps reference the library via #include before deleting (for libraries)
- Source code auto-backed up before deletion

---

## Virtual Device Types

`hub_manage_virtual_device` (action: "create") supports two mutually exclusive driver selection methods:

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
Use `hub_read_apps_code(tool="hub_list_drivers")` to see installed drivers and their namespace + name values. The namespace and name must match exactly as registered. If the driver is not found (or any other hub error), the tool surfaces an `IllegalArgumentException` (-32602) pointing to `hub_list_drivers`. If a built-in `deviceType` is not found on the hub, the tool surfaces an isError platform error (firmware gap, not a caller error).

**Create response shape** (both modes): `{ success, message, tips, device: { id, name, label, deviceNetworkId, driverNamespace, driverType, typeName, capabilities, commands, attributes } }`. `typeName` is a deprecated alias for `driverType` -- prefer `driverType` in new code.

**Delete response shape** (`action=delete`): `{ success, deviceId, deviceNetworkId, deviceLabel, message }`.

**List response shape** (`hub_list_devices` with `filter='virtual'`): `{ devices: [...], count, message }`. Per-device: `{ id, name, label, deviceNetworkId, driverNamespace, driverType, typeName, capabilities, commands, currentStates }`. `currentStates` is a map of attribute-name to current-value. Note: create returns device state as `attributes` (list) while list returns it as `currentStates` (map) -- different shapes for the same concept because create returns the freshly-read attribute list and list returns a compact state map. `typeName` is a deprecated alias for `driverType` -- prefer `driverType` in new code. `driverNamespace` is authoritative for devices created by this tool (the namespace is persisted at create time); for devices created before this version or by other means it falls back to a best-effort derivation that may report `"hubitat"`.

MCP-managed virtual devices:
- Auto-accessible to all MCP device tools without manual selection
- Appear in Hubitat UI for Maker API, Dashboard, Rule Machine sharing
- Use `hub_manage_virtual_device` (action: "delete") to remove (not `hub_delete_device`)

---

## hub_update_device Properties

| Property | API Used | Requires Write master |
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
- `hub_get_source` (type=app|driver|library, id; via `hub_read_apps_code`) supports chunked reading for large files
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
- `hub_create_library` - Install a new library from Groovy source (inline or via File Manager file)
- `hub_update_library` - Update existing library source (source/sourceFile/resave modes)
- `hub_delete_item` (type=library) - Delete a library (auto-backs up source first)
- `hub_get_source` (type=library, id) - Read library source with chunked reading support

Libraries must include a valid `library()` definition block. Before deleting a library, ensure no installed drivers or apps reference it via `#include` -- deleting a library in use causes compilation errors in the referencing code.

PREFER curl-upload + sourceFile (bypasses agent context, no transcript size limits). Use inline `source` only for stub-size snippets:
```bash
# Without Hub Security:
curl -F "uploadFile=@mylib.groovy" -F "folder=/" http://<HUB_IP>/hub/fileManager/upload

# With Hub Security enabled (cookie-based):
curl -c cookies.txt -d "username=USER&password=PASS" http://<HUB_IP>/login
curl -b cookies.txt -F "uploadFile=@mylib.groovy" -F "folder=/" http://<HUB_IP>/hub/fileManager/upload
```
Then call `hub_create_library` or `hub_update_library` with `sourceFile: 'mylib.groovy'`.

Note: `hub_get_source` (type=library; read-only, Read master) lives in `hub_read_apps_code` and serves apps, drivers, and libraries via its `type` discriminator. The write operations (`hub_create_library`, `hub_update_library`, `hub_delete_item` with type=library) live in `hub_manage_code` (Write master).

#### Bundles (`hub_install_bundle`)
A bundle is a `.zip` that Hubitat Package Manager (HPM) fetches and unpacks into the hub's Libraries/Apps/Drivers Code in one shot — it is how an installed package actually delivers its library files to the hub. `hub_install_bundle` performs that exact server-side install (`importUrl` -> the hub's `/bundle2/uploadZipFromUrl`, falling back to `/bundle/uploadZipFromUrl` on firmware older than 2.3.8.108), so a package's bundle can be installed and verified the HPM way without driving the HPM UI. Requires `confirm: true` and a recent backup. `installer: true` marks the bundle as the installer's primary copy (matches HPM's "private" flag). Unlike `hub_create_library` (single library from inline/File-Manager source), this installs whatever the zip contains, in HPM's install order.

### After Installation
- Apps: Add via Apps > Add User App in Hubitat web UI
- Drivers: Can be assigned to devices immediately
- Libraries: Available immediately for `#include` in driver/app source

---

## Backup System

### Hub Backups
- `hub_create_backup` creates full hub database backup
- Required within 24 hours before any destructive write operation (the `confirm`+backup tier)
- Only destructive write tool that doesn't require a prior backup
- Managed via the `hub_manage_backup` gateway: `hub_list_backups` (`scope=hub_local`/`hub_cloud`/`hub` for whole-hub DB backups; default `scope=source` lists code backups), `hub_get_backup`, `hub_restore_backup`, and `hub_delete_backup` (delete a whole-hub DB backup, local or cloud)

### Source Code Backups (Automatic)
- Created automatically when using hub_update_app, hub_update_driver, hub_update_library, hub_delete_item (type=app|driver|library)
- Stored in File Manager as `.groovy` files
- Persist even if MCP uninstalled
- Max 20 kept, oldest pruned
- Rapid edits preserve original (1-hour protection)

### Custom-engine Rule Backups (Automatic)
- `hub_delete_custom_rule` auto-backs up to File Manager as `mcp_rule_backup_<name>_<timestamp>.json`
- Restore via: `hub_read_file` → `hub_import_custom_rule`
- Skip backup for test rules: set `testRule: true` when creating/updating

### Native RM Rule Backups (Automatic)
- `update_rm_rule` and `delete_rm_rule` snapshot configure/json + statusJson to File Manager as `mcp-rm-backup-<ruleId>-<timestamp>.json`
- Snapshots register in the unified `state.itemBackupManifest` with type=`rm-rule`
- Use `hub_list_backups` (in `hub_read_apps_code` / `hub_manage_backup`) to enumerate, `hub_restore_backup` (in `hub_manage_backup`) with the backupKey to roll back
- If the rule still exists, settings are replayed in place; if deleted, a fresh empty rule is recreated and the saved settings replayed onto it

### Hubitat Built-in Rule Redirect
When `hub_get_custom_rule`, `hub_export_custom_rule`, `hub_update_custom_rule`, `hub_delete_custom_rule`, `hub_test_custom_rule`, or `hub_clone_custom_rule` fails
with "Rule not found", the error message may include a redirect hint if the ID belongs to a
Hubitat built-in rule-like app (Rule Machine, Room Lighting, Basic Rules, Visual Rules Builder).

Example redirect message:
> "Rule 832 is a Hubitat built-in Rule-5.1 app. Use `hub_read_apps_code -> hub_get_app_config(appId=832)` to read its configuration."

- Read verbs (`hub_get_custom_rule`, `hub_export_custom_rule`, `hub_clone_custom_rule`): points to `hub_get_app_config` and notes these tools only handle MCP's own rule engine.
- Write verbs (`hub_update_custom_rule`): points to `hub_get_app_config` for inspection and `hub_manage_rule_machine -> hub_set_rule` for programmatic modification (requires the Write master).
- Delete verb (`hub_delete_custom_rule`): points to `hub_manage_native_rules_and_apps -> hub_delete_native_app` for programmatic deletion.
- Test verb (`hub_test_custom_rule`): points to `hub_read_apps_code -> hub_get_app_config` for inspection. For Rule Machine rules specifically, the hint also includes a pointer to `hub_manage_native_rules_and_apps -> hub_call_rule` to trigger them; non-RM rule-likes (Room Lighting, Basic Rules, Visual Rule Builder) receive only the `hub_get_app_config` pointer because `hub_call_rule` routes through `RMUtils.sendAction` and is RM-only.
- The redirect check is best-effort: if the hub appsList call fails, a plain "Rule not found" message is returned with no secondary error.
- `hub_get_custom_rule` in list mode (ruleId omitted) and `hub_create_custom_rule` are not affected (they do not take a rule id as input).

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

**hub_list_devices:**
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

**hub_list_device_events:**
- Default limit 10, recommended max 50
- Higher values (100+) may cause delays on busy devices

**hub_get_device_attribute (poll mode):**
- Poll mode activates when any of `expectedValue`/`expectedValues`/`comparator`/`stableForMs`/`timeoutMs`/`pollIntervalMs` is supplied, OR `deviceIds`/`mode` is present (the multi-device keys route on key presence, so a present-but-null `deviceIds`/`mode` still reaches poll mode and is rejected with an actionable null-guard rather than silently falling to a one-shot read). An expected value is REQUIRED in poll mode -- supplying only `comparator`/`stableForMs` (or only a timing arg) still routes to poll mode and is then rejected for the missing `expectedValue`/`expectedValues`. A bare one-shot call (no poll args) still requires `deviceId` -- omitting both `deviceId` and `deviceIds` is rejected with an actionable message. BLOCKS the MCP request up to `timeoutMs` MILLISECONDS (default 5000ms = 5 seconds, max 60000ms = 60 seconds). Use sparingly; prefer event-driven flows when available.
- Concurrent MCP requests queue while this call blocks; avoid parallel poll-mode `hub_get_device_attribute` calls.
- Provide EXACTLY ONE of `expectedValue` or `expectedValues` -- passing both is rejected (same as the `hub_call_device_command` `waitFor` path). For `eq`, a multi-element `expectedValues` set is OR -- the poll succeeds when the value matches ANY member; for `ne`, it succeeds when the value matches NONE of them (it left the whole set). (The numeric comparators below take only `expectedValue`; `between` takes only `expectedValues`.)
- `comparator` (default `eq`) controls the match operator:
  - `eq` / `ne`: string-set membership (`eq` = value IS in the expectedValue/expectedValues set; `ne` = value is NOT in the set). `ne` does not match a null/never-reported value.
  - `gt` / `gte` / `lt` / `lte`: NUMERIC compare of the value (parsed as a decimal) against a single threshold in `expectedValue`. `expectedValues` is rejected for these.
  - `between`: NUMERIC inclusive range `low <= value <= high`, bounds from `expectedValues` (exactly 2 numeric strings `[low, high]`, `low <= high`). `expectedValue` is rejected.
  - Numeric comparators never match a null/non-numeric value -- the poll keeps going (and times out if it never becomes numeric-and-in-range). If the attribute reported a value the whole window but it never parsed numeric (e.g. `gt` on `switch="on"`), the timeout adds `nonNumericAttribute: true` (the comparator can never match -- use `eq`/`ne` for a string attribute).
- `stableForMs` (default 0 = return on first match): the matched condition must hold CONTINUOUSLY for this many MILLISECONDS before the poll converges (debounce). A value that flaps out of the condition restarts the window. Must be `< timeoutMs` (a value `>= timeoutMs` is rejected -- it could never converge). A continuously-flapping value never stabilizes and times out (correct).
- Re-reads the attribute every `pollIntervalMs` MILLISECONDS (default 200ms, min 50ms, max 5000ms)
- Returns `success: true` with `finalValue`, `elapsedMs`, `polledCount`, `timedOut: false` when the value matches
- Returns `success: false` with `timedOut: true` and the last-read `finalValue` on timeout; adds `neverReported: true` if the attribute never returned a non-null value during the entire poll window, or `nonNumericAttribute: true` (with a `note`) if a numeric comparator was used on an attribute that reported a non-numeric value the whole window. Adds `readError: true` (on SUCCESS or timeout) if reading the device threw on any poll (e.g. it was removed mid-poll) -- that tick was treated as unread rather than aborting the poll
- Returns `success: false` with `interrupted: true` (plus `finalValue`, `elapsedMs`, `polledCount`) if the hub interrupted the sleep (e.g. app reload during poll)
- `pollIntervalMs` is automatically clamped to `timeoutMs` if larger, ensuring at least one poll
- For passive one-shot reads, omit `expectedValue`/`expectedValues` (plain read mode) -- poll mode is for waiting on state transitions
- Common pattern after `hub_call_device_command`: that tool's `state` snapshot is an immediate read taken in the same request, so it is the PRE-effect value (the hub commits the change after the request returns). To confirm the RESULTING state, prefer `hub_call_device_command`'s own `waitFor` arg (block-polls then snapshots the converged value); use this tool standalone to poll an attribute some other actor is changing
- `hub_call_device_command`'s `waitFor` reuses this poll engine (including `comparator` and `stableForMs`, same semantics) but caps `timeoutMs` at 30000ms (vs 60000ms here) because it BLOCKS a hub thread for the full timeout while a write is in flight; its `pollIntervalMs` defaults to 250ms (vs 200ms here) since a post-command poll follows a write and wider spacing reduces read contention. A bad `waitFor` spec (including an invalid comparator/stableForMs) is rejected BEFORE the command fires, so the device is not actuated.

**hub_get_device_attribute (multi-device convergence):**
- Pass `deviceIds` (a list of device IDs) instead of `deviceId` to await the condition across SEVERAL devices in one bounded call. `deviceIds` is mutually exclusive with `deviceId` -- provide exactly one (passing both is rejected). Max 20 devices per poll (the cap bounds the per-interval work, since every device is re-read each interval).
- The SAME condition (`attribute`, `comparator`, `expectedValue`/`expectedValues`, `stableForMs`) is applied to every device -- validated once. Each device must support the attribute; a missing device ID or an unsupported attribute is rejected up front, naming WHICH device.
- `mode` (default `all`) controls the aggregate: `all` converges when EVERY device matches; `any` converges as soon as ONE device matches. `mode` applies only to `deviceIds` -- passing it with a single `deviceId` is rejected; an invalid value lists `[any, all]`. The aggregate predicate also drives `stableForMs` -- the whole any/all condition must hold continuously for the window. In `all` mode any single device flapping out resets the window (the aggregate goes false). In `any` mode the aggregate stays satisfied while ANY other device still matches, so one device flapping resets the window only when it was the sole match.
- Returns a COMPACT per-device array, never full device objects: `success` (the mode predicate converged), `mode`, `devices: [{deviceId, device, finalValue, matched}, ...]`, `convergedCount` (devices currently matched), `elapsedMs`, `polledCount`, `timedOut`. A device entry adds `readError: true` (on SUCCESS or timeout) if reading THAT device threw on any poll (e.g. it was removed mid-poll) -- only that device's tick degrades to unread; the other devices' state is still returned, the poll never errors out. On TIMEOUT, each device entry also adds `neverReported: true` / `nonNumericAttribute: true` only where true; an aggregate `transitioning` (any device still changing) and a `note` (present when >=1 device is a can-never-match non-numeric) round it out. A hub-reload interrupt returns `interrupted: true` with the per-device array.
- Multi-device is the standalone poll ONLY. `hub_call_device_command`'s `waitFor` stays single-device because it blocks that one command's in-flight write thread -- there is no multi-device command to await. To confirm a multi-device command sequence, fire the commands, then call `hub_get_device_attribute` with `deviceIds` to await the aggregate.

**hub_get_logs:**
- Returns most recent entries first
- Default 100 entries, max 500
- Filter pipeline order: scope (deviceId/appId, server-side) -> level -> source -> pattern -> patterns -> time window (since/until) -> limit
- `level` and `source` (substring) are simple string filters applied before regex
- `pattern` (string): case-insensitive regex against the message field; compiled once; throws on invalid syntax
- `patterns` (array of strings): multiple regexes; `patternMode='any'` (default) = OR, `patternMode='all'` = AND; compatible with `pattern` (both apply)
- `since` / `until`: ISO-8601 timestamp (e.g. `'2024-01-15T10:30:00Z'`) or relative offset (`'30m'`, `'2h'`, `'1d'`, `'7d'`); relative offset subtracted from now; max 30d (throws if exceeded -- use ISO-8601 for longer ranges); entries with unparseable time fields pass through rather than being excluded. ISO-8601 timestamps without an explicit TZ marker (e.g., `'2024-01-15T10:30:00'` or `'2024-01-15 10:30:00.000'`) are parsed as UTC.
- For single-device or single-app logs, pass `deviceId` or `appId` -- this is a server-side scope filter (mutually exclusive) and is much cheaper than post-filtering the full buffer

**hub_list_device_events (windowed mode):**
- Windowed mode activates when `hoursBack` OR `since` is supplied: up to 7 days of history
- `hoursBack` is a relative window; `since` is an absolute bookmark -- return only events AFTER an exact timestamp. `since` accepts ISO-8601 in the same format the tool emits in `date`/`sinceTimestamp` -- a numeric offset with no colon, e.g. `2026-06-23T10:00:00.000-0600` (a trailing `Z` for UTC and a millis-less variant are also accepted), or epoch milliseconds. `since` takes precedence over `hoursBack`; a future `since` yields an empty list
- **Change-watching loop**: record a returned event `date`, run your action, then pass that `date` back as `since` to get exactly the new events since that bookmark. The response echoes `sinceMode` (`"explicit"` when `since` drove the window, `"relative"` for `hoursBack`) plus the bounding field (`since` or `hoursBack`); `sinceTimestamp` is always the actual window start used
- Use attribute filter to reduce data volume
- **Location-scope mode**: omit `deviceId` to return location events instead of device events. Returns mode changes (`name: 'mode'`), HSM status/alerts (`'hsmStatus'`, `'hsmAlert'`), hub variable changes (name = the variable's name), and any `sendLocationEvent(...)` emissions from rules/apps. `attribute` filter applies to event name in the same way. Response includes `source: 'location'` and omits `device`/`deviceId`.

**hub_get_app_config:**
- Reads any legacy SmartApp's configuration page: RM rules, Room Lighting instances, Basic Rules, HPM, Mode Manager, Button Controllers, third-party community apps
- Default response includes `app` (identity), `page` (section/input structure with current values), `childApps` summary
- Raw app-internal `settings` map (~100-1000 keys with app-specific encoding) omitted by default — pass `includeSettings=true` for power-user inspection
- Multi-page apps (HPM, multi-page Room Lighting) expose sub-pages via `pageName`. For HPM specifically: `pageName="prefPkgUninstall"` returns the FULL installed-package list as an enum; `pageName="prefPkgModify"` returns only the subset with optional components; `pageName="prefOptions"` is the main menu (navigation links, no package data).
- Read-only, does not modify anything. Gated by the Read master.

---

## Installed-App & Native-Rule Tools

The installed-apps reads (`hub_list_apps` scope=instances, `hub_list_device_dependents`, `hub_get_app_config`, `hub_list_app_pages`, `hub_list_hpm_packages`) live in the `hub_read_apps_code` gateway; the native-app CRUD is split across `hub_manage_native_rules_and_apps` (generic classic apps via `hub_set_native_app`, plus delete/clone/export/import) and `hub_manage_rule_machine` (RM rule authoring via `hub_set_rule`, plus the Visual Rules Builder tools `hub_get_visual_rule` / `hub_set_visual_rule` / `hub_delete_visual_rule`). All of these are gated by the universal masters: the installed-app reads (and the native-rule reads like `hub_list_rules`) are gated by the **Read master**, and the native-app CRUD path (`hub_set_rule`, `hub_set_native_app`, `hub_delete_native_app`, etc.) by the **Write master** — the destructive CRUD additionally enforces `confirm=true` + a hub backup within 24h. If the user sees "Read tools are disabled" or "Write tools are disabled" errors, direct them to the MCP Rule Server app settings page to turn the relevant master back ON (both default ON). If a destructive write blocks with a backup-age message, use `hub_create_backup` first. A tool can also be switched off individually under **Advanced: Per-tool Overrides** — that path returns "…is disabled in Advanced settings (Per-tool Overrides)…" and is re-enabled in the same settings page.

### Installed-app reads (in `hub_read_apps_code`)

- **`hub_list_apps` (scope=instances)** — enumerate ALL apps on the hub (built-in + user) with parent/child tree (the former `hub_list_installed_apps`, now folded into `hub_list_apps`; pass `scope='instances'`, default `scope='types'` returns the installable-app catalog)
  - `filter="all"` (default) | `"builtin"` | `"user"` | `"disabled"` | `"parents"` | `"children"`
  - Each entry: `id`, `name`, `type`, `disabled`, `user`, `hidden`, `parentId`, `hasChildren`, `childCount`
  - Built-in apps have `user=false` (Rule Machine, Room Lighting, Groups and Scenes, Mode Manager, HSM, Dashboards, Maker API, etc.)
  - User apps have `user=true` (Awair, Ecobee, HPM, etc.)
  - Parent/child tree flattened with `parentId` pointers. Hidden parents are excluded from output but their children are promoted to the nearest visible ancestor (or `null` at root).

- **`hub_list_device_dependents`** — find apps that reference a specific device
  - Use BEFORE deleting a device, disabling a device, or troubleshooting unexpected behavior
  - Returns `appsUsing` array with each app's `id`, `name` (type like "Room Lights" or "Rule-5.1"), `label` (user-visible), `trueLabel` (HTML-stripped), `disabled`
  - Answers "if I delete/disable this device, which automations break?"

- **`hub_get_app_config`** — read an installed app's configuration page (Read master)
  - See usage tips above for full details on response shape, pageName navigation, and includeSettings flag
  - Workflow: `hub_list_apps` (scope=instances) or `hub_list_rules` to find an `appId`, then `hub_get_app_config` to inspect it

- **`hub_list_app_pages`** — list known page names for a multi-page app (Read master)
  - Returns the primary page (introspected from the hub) plus a curated directory of known sub-pages for well-known app types
  - Curated directories: HPM (prefOptions, prefPkgUninstall, prefPkgModify, prefPkgInstall, prefPkgMatchUp), RM rules (mainPage only -- single-page), Room Lighting (mainPage), Mode Manager (mainPage)
  - Unknown app types: returns the primary page only plus a note about consulting the app's source or Web UI for additional page names
  - Use this before `hub_get_app_config` on multi-page apps to avoid guessing page names
  - Args: `appId` (required)

### HPM package introspection — `hub_list_hpm_packages` (in `hub_read_apps_code`)

HPM package state introspection. The tool is gated by the **Read master** and HPM itself must be installed on the hub. Auto-discovers HPM's installed-app ID unless `hpmAppId` is supplied explicitly. All `IllegalArgumentException`s raised (multi-instance throw, wrong app type, missing HPM, non-numeric `hpmAppId`) are surfaced by the MCP dispatcher as JSON-RPC error `-32602` "Invalid params" -- the response is a JSON-RPC error, **not** a tool-result map with `success=false`.

**`actualTypeName` diagnostic label sets — two distinct enums:** HPM tooling reports the parsed-JSON runtime type via an `actualTypeName` token at two distinct sites with different label sets. (1) `_hpmFetchManifests` parse-shape errors (surfaced as JSON-RPC `-32602` "Unexpected HPM statusJson shape" / "Unexpected HPM manifests shape"): `<actualTypeName>` is one of `List`, `null`, or `non-object` (any non-Map, non-List, non-null scalar). (2) `orphanDetection` / `orphanDriverDetection` registry-shape errors (surfaced in `reason` strings on a `success=true` response with `enabled=false`): `<actualTypeName>` is one of `Map`, `null`, or `unknown` (any non-List, non-null, non-Map shape). The label sets diverge because the expected shape at each site is different (a Map for statusJson, a List for the registries).

- **`hub_list_hpm_packages`** — return all packages tracked by Hubitat Package Manager with full component inventory
  - Each package: `manifestUrl`, `packageName`, `version`, `beta`, `author`, `apps[]`, `drivers[]`, `files[]`
  - Each app/driver component: `id` (UUID from manifest), `name`, `heID` (Hubitat code ID or null if not installed), `required`, `version`; if `heID` is an empty/whitespace-only string it is cleared to null and `_warning` is added (e.g. `"empty heID string '' normalized to null"` for an empty string, or `"empty heID string '  ' normalized to null"` for a whitespace-only string); if `heID` is a whitespace-padded string (e.g. `' 142 '`) it is normalized to the trimmed value and `_warning` records the normalization (e.g. `"whitespace-padded heID ' 142 ' normalized to '142'"`); heID remains non-null; if `heID` is a non-scalar type it is cleared to null and `_warning` is added
  - `files[]` entries have no `heID` (File Manager assets tracked by name only)
  - Top-level `count` (number of packages returned); `hpmAppId` (HPM's installed-app ID, echoed so callers can cache it and skip discovery on subsequent calls)
  - Top-level `skippedMalformed[]`: manifest URLs whose value was not a Map (package skipped entirely)
  - Per-package `skippedAppCount`, `skippedDriverCount`, `skippedFileCount` (each omitted when 0): counts of app/driver/file entries that were not Maps
  - Auto-discovers HPM if `hpmAppId` omitted; when multiple HPM instances are detected, throws with a bracketed ID list capped at 10 entries with `"and N more (total M)"` suffix; when `hpmAppId` is supplied explicitly but points at an app that is not Hubitat Package Manager, throws `IllegalArgumentException` with the actual type disclosed (e.g. `"hpmAppId N is not Hubitat Package Manager (actual type: Simple Automation Rules)"`)
  - Use to audit what HPM-managed code is on the hub and compare against expected packages

- **`hub_list_hpm_packages` with `includeDrift=true`** — also cross-reference HPM-tracked state against what is actually installed; drift results nest under a `drift` key on the response
  - Currently the only drift signal types are: `missing-required` (required=true, heID null), `orphan-app` (heID present but app code no longer in Apps Code registry), `orphan-driver` (heID present but driver code no longer in Drivers Code registry). Data-quality issues are emitted in a separate `dataQualityWarnings[]` aggregate and do NOT inflate `totalDriftSignals`.
  - Optional `packageFilter` (case-insensitive substring) narrows to specific packages
  - Auto-discovers HPM if `hpmAppId` omitted (including the multi-instance throw with up to 10 ids and `"and N more (total M)"` suffix); when `hpmAppId` is supplied explicitly but points at an app that is not Hubitat Package Manager, throws `IllegalArgumentException` with the actual type disclosed (e.g. `"hpmAppId N is not Hubitat Package Manager (actual type: Simple Automation Rules)"`)
  - Response: `hpmAppId` (echoed, same semantics as `hub_list_hpm_packages`); `packagesChecked` (not `count`) **because** `packageFilter` may narrow the set examined -- 'checked' reflects the filtered population, distinguishing it from `hub_list_hpm_packages`'s naive `count`; `packagesWithActionableDrift` (count of packages with at least one actionable signal -- excludes data-quality-only packages); `totalDriftSignals` (actionable drift only -- missing-required, orphan-app, orphan-driver -- does NOT count data-quality warnings); `drift[]` (one entry per package with any signal or warning -- each has `manifestUrl`, `packageName`, `version`, `signals[]`, and optionally `dataQualityWarnings[]`, `skippedAppCount`, `skippedDriverCount`); `summary` sentence. Note: `drift[].length` may exceed `packagesWithActionableDrift` when data-quality-only packages exist -- those entries appear for visibility but are not counted in the summary.
  - Each `signals[]` entry has: `type`, `componentType`, `componentName`, `componentId`, `note`. `orphan-app` and `orphan-driver` entries additionally carry `heID` (the orphaned id); `missing-required` entries omit `heID` (the value is null by definition of the signal). If the manifest carried a non-null but invalid heID (e.g. whitespace-padded `"  "`) that was normalized to null, the original raw value is recoverable from the sibling `dataQualityWarnings[]` entry -- join on `(componentType, componentName, componentId)`. `skipped-malformed-component` warnings cannot be joined because they lack `componentName`/`componentId`.
  - If any data-quality warnings exist across packages, they are also aggregated at top-level `result.dataQualityWarnings[]`
  - `orphanDetection` (`{enabled, reason?}`) -- `enabled=false` means the Apps Code registry fetch failed; `reason` discloses whether the body was empty ("Empty response from /hub2/userAppTypes") or had an unexpected shape ("expected JSON array, got `<actualTypeName>`: ..."). `<actualTypeName>` is one of: `Map`, `null`, or `unknown` (any non-List, non-null, non-Map shape -- the parsed JSON's runtime type). When the payload preview exceeds 200 chars, `reason` truncates to 200 and appends `" (truncated)"`
  - `orphanDriverDetection` (`{enabled, reason?}`) -- same structure as `orphanDetection` but for the Drivers Code registry (`/hub2/userDeviceTypes`)
  - When one detection system is disabled the `summary` appends `"(partial: <name> disabled this call -- see <name> reason)"`; when both are disabled the suffix uses `"reasons"` (plural): `"(partial: orphanDetection/orphanDriverDetection disabled this call -- see orphanDetection/orphanDriverDetection reasons)"`. Note: when `orphanDetection.enabled` or `orphanDriverDetection.enabled` is `false`, the corresponding orphan-* signals cannot fire. `packagesWithActionableDrift` will reflect only packages with a `missing-required` signal, and may UNDER-COUNT the actual drift state. Inspect `orphanDetection` / `orphanDriverDetection` before treating `packagesWithActionableDrift == 0` as 'clean'.
  - Separately, a `required=true` component whose heID is non-scalar (List/Map/Boolean) produces a `heid-non-scalar-dropped` data-quality warning rather than a `missing-required` signal. The component is dropped before the required check, so a broken-but-required component will not contribute to `packagesWithActionableDrift`. Inspect `dataQualityWarnings[]` for `heid-non-scalar-dropped` entries on required components before treating a zero count as fully clean.
  - Data-quality issues land in `dataQualityWarnings[]` on the per-package entry and are aggregated at top-level `result.dataQualityWarnings[]` -- they do NOT inflate `totalDriftSignals`. Currently the only data-quality warning types are: `heid-whitespace-normalized` (whitespace-padded heID normalized to trimmed value; component is **kept**, drift checks continue against the trimmed heID), `heid-non-scalar-dropped` (heID is not a Number or String; component is **dropped**, no drift checks run), `empty-heid` (blank heID normalized to null; entry has `componentName`, `componentId`, `_warning`), `skipped-malformed-component` (non-Map component entry; entry has only `type`, `componentType`, `_warning` -- no `componentName`/`componentId` because the source was not a Map). The kept/dropped distinction lets consumers determine whether drift checks ran for a given component.
  - Files are not checked for drift **because** HPM tracks file components by name only (no heID), so there is no registry membership to verify; `skippedFileCount` is accordingly not emitted on drift entries
  - Top-level `skippedMalformed[]`: manifest URLs whose value was not a Map (symmetric with `hub_list_hpm_packages`)
  - When `packageFilter` matched nothing: `filterMatchedZero=true` plus `availablePackages[]` for filter-spelling sanity check; in this case `packagesChecked == 0` and `summary` reads `"No drift detected across 0 tracked packages."`
  - Limitation: heID-presence-only. HPM stores no source hashes, so post-install edits via `hub_update_app` are not detectable.
  - Example call: `hub_read_apps_code(tool="hub_list_hpm_packages", args={includeDrift: true, packageFilter: "BOND"})` — checks drift for only packages whose name contains "BOND"
  - Design note: the base package inventory emits `_warning` inline on each component **because** consumers typically enumerate components per-package; the `includeDrift=true` output emits `dataQualityWarnings[]` as a separate aggregate **because** consumers need to distinguish actionable drift signals from data-quality issues without conflating them in a single `signals[]` count

### hub_manage_native_rules_and_apps (11 tools)

Two surfaces under one gateway: RMUtils-based runtime control for RM rules (RM-only because RMUtils is RM-only) plus admin-layer CRUD that works uniformly across any classic SmartApp (RM, Room Lighting, Button Controllers, Basic Rules, Notifier, etc.). The four RMUtils control tools below are ALSO surfaced (alongside `hub_get_rule_health`) in the `hub_manage_rule_machine` gateway; the read-only members (`hub_list_rules`, `hub_get_rule_health`) additionally appear in `hub_read_rules`.

**RMUtils control (4 tools, RM-only):**

- **`hub_list_rules`** — enumerate Rule Machine rules (RM 4.x + 5.x combined, deduplicated by id)
- **`hub_call_rule`** — trigger an existing RM rule via `RMUtils.sendAction`
  - `action="rule"` (default, full evaluation): runs triggers + conditions + actions as if the rule fired
  - `action="actions"`: runs only the actions, bypassing conditions (useful for manual override)
  - `action="stop"`: stops running actions (cancels in-flight delays)
- **`hub_set_rule_paused`** — reversible toggle (`paused=true` pauses / `false` resumes); paused rules don't fire on triggers
- **`hub_set_rule_private_boolean`** — set an RM rule's private boolean (true or false only; string values must be lowercase `"true"`/`"false"`). RM rules can use "Private Boolean" in conditions — this lets MCP flip that flag from outside.

**Admin-layer CRUD (7 tools, generic across all classic SmartApps):**

(`hub_set_native_app`, `hub_set_app_disabled`, `hub_delete_native_app`, `hub_clone_native_app`, `hub_export_native_app`, `hub_import_native_app`, `hub_get_rule_health`. `hub_set_rule` is referenced below for create+edit context but lives in `hub_manage_rule_machine`.)

If a user asks "create a new RM rule" or "modify this Room Lighting instance":

1. **`hub_set_rule(appId?, addTrigger|addAction|..., confirm)`** — create+edit upsert for Rule Machine rules (lives in `hub_manage_rule_machine`). Omit `appId` to create a new RM 5.1 rule; pass it to edit an existing one. Carries the FAT RM authoring schema (`addTrigger`, `addAction`, `addRequiredExpression`, `replaceRequiredExpression`, `walkStep`, `patches`, `replaceActions`, `removeAction`, `clearActions`, `moveAction`, `removeTrigger`, `modifyTrigger`, `addLocalVariable`, `removeLocalVariable`). **Create-arm bundle (no `appId`)**: bundles `addTrigger`/`addTriggers`/`addAction`/`addActions`/`addRequiredExpression` to populate the new rule in one call (the bundled RE's outcome is surfaced under `result.requiredExpression`). The remaining edit-only shortcuts (`replaceRequiredExpression`/`walkStep`/`patches`/`replaceActions`/`removeAction`/`clearActions`/`moveAction`/`removeTrigger`/`modifyTrigger`/`addLocalVariable`/`removeLocalVariable`) make no sense on a brand-new rule and are LOUDLY REJECTED on create (never silently dropped to an empty shell) -- create the rule first, then re-call with the returned `appId`. Auto-snapshots before every write.
2. **`hub_set_native_app(appId?, appType, name, settings|button|buttonRule|walkStep, ...)`** — create+edit upsert for any classic SmartApp (Button Controller, Notifier, Groups+Scenes, Basic Rules; lives in `hub_manage_native_rules_and_apps`; Visual Rules are edit/delete-only by appId — create them with `hub_set_visual_rule`). Omit `appId` to create (`appType` enum-driven; expand `_appTypeRegistry` for other types), pass it to modify by appId. Create a **Button Rule** (a grandchild of a Button Controller) via `buttonRule={controllerId, buttonNumber, event}` — it routes through the controller's add-button flow and returns `buttonRuleId` (then author its actions via `hub_set_rule`). `walkStep` (a generic classic-page walker) and the RM authoring shortcuts also work here on EDIT (`appId` present) for RM-wire-format classic apps -- the create arm honors none of them and loudly rejects rather than drops; the schema stays lean (no FAT trigger/action sugar). Multi-device capability `multiple=true` contract emitted automatically. Auto-snapshots before every write.
3. **`hub_set_app_disabled(appId, disabled)`** — enable or disable any installed app (the red-X toggle) via POST `/installedapp/disable`; reversible (no confirm/backup needed). Read-back verified.
4. **`hub_delete_native_app(appId, force, confirm)`** — soft delete (default) or `force=true` for `forcedelete/quiet` (the path the hub UI uses). Auto-snapshots first.
5. **`hub_clone_native_app(appId, newName, confirm)`** — clone an existing classic SmartApp via Hubitat's `appCloner` endpoint. Returns the new `appId`.
6. **`hub_export_native_app(appId)`** — export a classic SmartApp to JSON (round-trippable with `hub_import_native_app`). Useful for the export-mutate-import editing pattern when the wizard surface is too lossy to drive directly.
7. **`hub_import_native_app(appType, exportData, name, confirm)`** — import previously-exported app JSON into a new instance. Returns the new `appId`.
8. **`hub_get_rule_health(appId, source)`** — read-only health check on any installed app, across **both** rule engines. For a Rule Machine rule it prefers the compiled-state `broken` boolean (`GET /app/ruleBuilderJson`), with the HTML render scan retained as a cross-check and fallback (broken markers — with per-marker occurrence counts in `brokenMarkerCounts` — multiple-flag poison, configPage errors, structural imbalance, compiled `predicate`). For a **Visual Rules Builder** rule it reports the engine-native health: a graph rule's `validationErrors` (`GET /app/ruleBuilder20Json`) become `broken=true` when non-empty; a classic VRB rule is recognized (`ruleFormat="vrb-classic"`) with no compiled boolean. Other classic apps (Button Controller, Basic Rule, …) share RM's configPage protocol, so the configPage checks (e.g. `configPage.error`, multiple-flag poison) cover them too — they report `ruleFormat` `button-controller` / `basic-rule` / `classic-app` with `broken: null` (no compiled boolean). The `ruleFormat` field says what was inspected. `source` defaults to `auto` (runs both paths + the cross-check); pass `ruleBuilderJson` or `configPage` to force a single path. `hub_set_rule` attaches this report as `health` on every response (success AND error); `hub_set_visual_rule` attaches it on every response that resolves to a rule id (early CREATE failures carry no appId).

**Cross-references** (live in other gateways but commonly used together):
- **`hub_list_rule_local_variables`** (in `hub_read_rules` and `hub_manage_rule_machine`) — read-only list of a Rule Machine rule's LOCAL variables (the per-rule variables created via `hub_set_rule` `addLocalVariable` / `removeLocalVariable` — distinct from hub-global variables, which `hub_list_variables` covers). Reads `state.allLocalVars` from the rule's statusJson appState; returns `{appId, localVariables:[{name, type, value}], total}`. Use to confirm a local exists (and its type) before targeting it with the `setLocalVariable` action or `removeLocalVariable` shortcut. The `type` is the INTERNAL token, which differs from the `addLocalVariable` `type` enum — translate when piping list output back into `addLocalVariable`: **Number->`integer`, Decimal->`bigdecimal`, String->`string`, Boolean->`boolean`, DateTime->`datetime`**.
- **`hub_get_app_config`** (in `hub_read_apps_code`) — read any installed app's current page schema, settings, and child apps. Use BEFORE every `hub_set_rule` / `hub_set_native_app` edit to discover the right input names.
- **`hub_list_backups`** (in `hub_read_apps_code` / `hub_manage_backup`) + **`hub_restore_backup`** (in `hub_manage_backup`) — enumerate and restore native-app snapshots (`type="rm-rule"` entries). Restore re-applies settings in place if the app exists, or recreates the app and replays settings if it was deleted.

**Basic Rules** are registered: `hub_set_native_app(appType="basic_rule", name=...)` creates one via generic `createchild`, and editing its settings works cleanly (Basic Rule is a submitOnChange classic app — `commitButton` is null in the registry so no spurious `updateRule` click poisons its render). **Button Rules** are grandchildren of a Button Controller and can't be created standalone — use `buttonRule` (on `hub_set_native_app` or `hub_set_rule`), which drives the controller's add-button flow and returns the new rule's `appId`; then author its actions with `hub_set_rule(appId=<buttonRuleId>, addAction=...)`. For Room Lighting and other not-yet-registered types, `hub_set_native_app` (edit) and `hub_delete_native_app` already work by appId; creation needs the `_appTypeRegistry()` entry (namespace + appName + parentTypeName, verified live). If a registered type's built-in **parent** app isn't installed on the hub (e.g. a fresh hub with no Button Controllers), creation self-bootstraps it via the "Add Built-In App" endpoint (`GET /installedapp/sysApp/<parentTypeName>`) and retries — no manual install needed.

#### `hub_set_rule` capability reference

Reference for the `hub_set_rule` structured shortcuts (`addTrigger`, `addAction`, `addRequiredExpression`). The schema descriptions point here so flat-mode `tools/list` can stay under the 124 KB cap; gateway-mode catalog responses still carry the full enumerations inline. For machine-readable schemas, pass `{discover: true}` on `addTrigger` or `addAction` -- both return live structured Maps from the running code.

To READ a rule's current configuration -- before an edit to discover the right input names, or to verify after a write -- use `hub_read_apps_code -> hub_get_app_config(appId)`. It is NOT in the `hub_manage_rule_machine` / `hub_manage_native_rules_and_apps` rule gateways; the rule-read tool lives in `hub_read_apps_code`.

##### `addTrigger` capability families

- **Device-state** (Switch / Motion / Contact / Lock / Garage / Door / Valve / Window Shade / Presence / Power source): `capability`, `deviceIds`, `state` (`'on'`, `'active'`, `'open'`, `'unlocked'`, etc.). To fire on ANY change of the attribute (not one specific value) pass `comparator:'*changed*'` and OMIT `state`: a device-state trigger has no separate comparator field, so the change token rides the value picker (`tstate<N>`) and renders e.g. "Switch changed". A bare change token in `state` (`state:'changed'`) is rejected fail-loud, steering you to `comparator:'*changed*'`.
- **Multi-device "all of these"**: add `allOfThese=true` to the device-state spec
- **Numeric** (Temperature / Humidity / Battery / Illuminance / Power / Energy / CO2 / Dimmer / Thermostat setpoints): `capability`, `deviceIds`, `comparator` (`=`, `<`, `>`, `<=`, `>=`, `*changed*`), `value`
- **Button** (`capability='Button'`): `deviceIds`, `buttonNumber`, `state` (`pushed` | `held` | `doubleTapped` | `released`)
- **Custom Attribute** (`capability='Custom Attribute'`): `deviceIds`, `attribute` (the attribute name), `comparator`, `value`
- **And-stays sticky modifier** (any device-state or numeric trigger): add `andStays={hours, minutes, seconds}` to the spec
- **Time / Sunrise / Sunset** (`capability='Certain Time (and optional date)'`): `time` (`'A specific time'` | `'Sunrise'` | `'Sunset'`), `atTime`, `offset` (minutes, for sunrise/sunset)
  - `atTime` semantic: `'HH:mm'` form (e.g. `'17:00'`) = **DAILY-recurring** trigger that fires every day at that wall-clock time. Full ISO datetime (e.g. `'2026-04-29T17:00:00'` or `'2026-04-29T17:00:00.000-0500'`) = **ONE-SHOT dated** trigger that fires once on that specific date. Forms without timezone are auto-normalized to hub local tz; explicit-offset and Zulu forms are normalized to UTC equivalent.
- **Mode** (`capability='Mode'`): `state='Night'` OR `state=['Away','Night']` (mode names, case-insensitive) OR `modeIds=['3']` OR `modeIds=['3','5']` (IDs directly, from `hub_list_modes`).
  - **IMPORTANT:** writes `modesX<N>` internally — do NOT pass `tstate` or `rawSettings.tstate` for Mode triggers (silently ignored; renders as Broken Trigger). Use `hub_list_modes` to list valid mode names/IDs.
- **Periodic Schedule** (`capability='Periodic Schedule'`): recurring schedule via the dedicated periodic sub-page. Spec:
  ```
  periodic={
    frequency: 'Seconds'|'Minutes'|'Hourly'|'Daily'|'Weekly'|'Monthly'|'Yearly'|'Cron String',
    everyN: <int>,                 // "every N <unit>" mode (Seconds/Minutes/Hourly/Daily)
                                   //   REQUIRED even when =1 for Daily AND Hourly (omitting renders null)
                                   //   Seconds/Minutes: whole number from [1,2,3,4,5,6,10,12,15,20,30] (firmware-imposed; Hourly/Daily accept any positive integer; fractional truncates, 5.5->5)
    startingTime: 'HH:mm',         // start-time (Hourly/Daily/Weekly/Monthly/Yearly; Seconds has none); for Hourly-everyN, pass it (omitting renders a cosmetic trailing "starting at " blank)
    weekdaysOnly: <bool>,          // Daily-only
    selectedHours: [9,12],         // Hourly-only, alternative to everyN
    selectedMinutes: [0,30],       // Minutes-only, "at specific minutes", alternative to everyN
    selectedDaysOfMonth: [1,15],   // Daily-only, alternative to everyN/weekdays
    daysOfWeek: ['Monday','Friday'], // Weekly-only, MULTI day-of-week
    dayOfWeek: 'Monday',           // Monthly/Yearly nth-weekday, SINGLE day-of-week (distinct from daysOfWeek)
    dayOfMonth: <int>,             // Monthly by-day "on day number" (pair with everyNMonths; exclusive with weekOfMonth)
    everyNMonths: <int>,           // "of every N months" (Monthly, both modes; free integer)
    months: 'December',            // Yearly only -- single nth-weekday month (String); Monthly does NOT take months
    weekOfMonth: 'First',          // Monthly/Yearly nth-weekday: First|Second|Third|Fourth|Last (presence selects nth-weekday)
    minutesOffset: <int>,          // Hourly-only, when not using everyN (startingHCX1)
    cronString: '0 * * * *',       // Cron String mode
    rawSettings: {…}               // escape hatch for periodic-page fields not yet mapped
  }
  ```
  **Monthly has two mutually-exclusive modes:** *by-day* (`dayOfMonth` + `everyNMonths` -- BOTH required or the row renders `null`) and *nth-weekday* (`weekOfMonth` + `dayOfWeek` + `everyNMonths` -- e.g. "the Second Monday of every month"). Passing both `dayOfMonth` and `weekOfMonth` is rejected. Monthly "specific months" ("on day N of selected months") is NOT yet supported (an order-sensitive third sub-mode) -- use `rawSettings`. **Yearly is ALWAYS nth-weekday:** `weekOfMonth` + `dayOfWeek` + single `months` (e.g. "the First Monday of December"); the month alone never completes, because RM 5.1 exposes no by-day calendar-day field for Yearly -- only the nth-weekday picker. **Seconds** exposes only the count enum -- no toggle, no `startingTime`. The Seconds/Minutes restricted enum is firmware-imposed; Hourly/Daily `everyN` accept any positive integer. A `Periodic Schedule` with no `periodic` map is rejected up front (`success=false`, naming any stray top-level keys) rather than committing a phantom `?` row. The tool walks the periodic sub-page (`whichPeriod<N>` → `everyN`/select → time → Done, where `<N>` is the per-trigger sub-page index) so the trigger description bakes correctly. A Seconds/Minutes `everyN` outside the restricted enum, or a Monthly `dayOfMonth`+`weekOfMonth` combo, is rejected with `success=false` and a structured error (the whole-tool backup-and-catch envelope surfaces the validation failure as a structured map, not a thrown -32602).

  **Fail-loud shape guards** (both reject before any hub write, returning `success=false` with a structured error rather than committing a broken trigger): (1) a state-change token supplied as `state` with no `comparator` (e.g. `state:'changed'`/`'increased'` on a device-state or numeric trigger) is rejected and steered to `comparator:'*changed*'`; **Mode / Variable / Custom Attribute are exempt** because their `state` legitimately carries a mode name or an enum value. (2) A `Periodic Schedule` with no `periodic` map is rejected, naming the stray top-level keys you passed instead.

  **Fail-loud `addAction` shape guards** (both reject before any hub write -- "RM is not touched"): (1) a condition-bearing action subtype (`ifThen`/`elseIf`/`repeatWhile`/`waitExpression`, matched case-insensitively) passed a flat top-level `conditions` array is steered to the `expression:{conditions:[...], operator|operators}` wrapper; (2) an action-driven capability (any capability whose action schema exposes an `action` enum -- switch/dimmer/color/colorTemp/lock/shade/fan/button/..., plus the `Window Shade` display name) passed a trigger-style `state:` instead of `action:` is steered to `action:`.

  **Did-you-mean on unknown capability names:** an unknown capability on `addTrigger`, `addRequiredExpression`, `ifThen`/`elseIf`/`repeatWhile`/`waitExpression`, or `waitEvents` fails loud with a closest-match suggestion drawn from the live picker's own option list (so the suggested name is one the picker actually accepts).

  **Comma-joined mode steer:** a mode passed as a single comma-joined string (`state:'Day,Evening'`) is steered to the list shape (`state:['Day','Evening']`) instead of an opaque "unknown mode", across the trigger Mode path, per-mode actions, the `mode` action, and Mode conditions.

- **Inline conditional trigger** (`trigger.condition` Map): `{capability, deviceIds?, state?, comparator?, value?, attribute?, variable?, compareToVariable?, not?, rawSettings?}`. Drives the conditional-trigger sub-wizard inline (sets `isCondTrig.<N>=true` + `condTrig.<N>=a` + the condition fields + clicks hasAll). Convenience: pass singular `deviceId: N` (integer) instead of `deviceIds: [N]` (array) -- the dispatcher normalizes before writing. If both `deviceId` and `deviceIds` are supplied, `deviceIds` wins.
  - The following per-capability extended shapes work on `trigger.condition`: Variable (incl. `compareToVariable`), Custom Attribute (`attribute` + `comparator` + `state`/`value`), and enum/numeric device-state conditions. **Mode-via-picker (`modeIds`), Between two times (`start`/`end`), and `compareToDevice` Maps are NOT yet supported on `trigger.condition`** -- `_rmBuildCondition` is a static direct-write helper, not the shared `_rmWalkConditionReveal` walker. For those shapes use `addRequiredExpression.conditions[]` or `addAction.expression.conditions[]` instead. The time/date-window capabilities reject fail-loud rather than committing a broken condition: `Between two times` steers you to `addRequiredExpression` or an `ifThen` action (where its start/end time-picker walk IS implemented), and `Between two dates` / `Days of week` / `On a Day` -- whose date/day pickers are unmodelled on EVERY structured condition surface -- steer you to author them directly via `rawSettings` or a `walkStep` call. A state-change comparator (`*changed*`/`*became*`) on a device-state condition capability (with no explicit value) is also rejected on every condition surface and steered to a trigger row -- conditions are point-in-time, so a change comparator has no meaning there.
  - `compareToVariable` (Variable LHS compared to another hub variable) works on `addTrigger.condition`, `addRequiredExpression`, AND `addAction` IF-expressions (ifThen/elseIf/repeatWhile/waitExpression). On `addTrigger.condition` the `selectTriggers` sub-wizard writes the fixed `xVarR_<N>` field; on the walker pages (STPage/doActPage) the walker toggles `isVar_<N>=true` and discovers the firmware-assigned right-hand variable picker from the live schema (it does NOT hardcode `xVarR_<N>` -- `selectTriggers` consistently exposes `xVarR`, but the walker pages can expose a differently-suffixed field, so the walker discovers whatever the live schema reveals). On Variable conditions, for value-comparison comparators (`=`/`!=`/`<`/`>`/`<=`/`>=`) supply exactly one of `value`/`state` (constant RHS) or `compareToVariable` (variable RHS) -- they are mutually exclusive; passing both is rejected. State-change comparators (`*changed*`/`*became*`) take no RHS, so omit both. If the revealed RHS picker has an empty option list, the name is written best-effort and `settingsSkipped` carries a `compareToVariable-validation` / `api_unavailable` sentinel (`partial=true`).

##### `addAction` capability families

For machine-readable per-field schemas (with `action` enums and per-action required fields), see `docs/rm_action_subtype_schemas.md` — a hand-maintained copy; keep it in step with `_rmActionSchemaForDiscover()`.

- **Switch** (`capability='switch'`): `action='on'`/`'off'`/`'toggle'`/`'flash'` + `deviceIds`. `action='setPerMode' + deviceIds + perMode={modeIdOrName: 'on'|'off', ...}`. `action='choosePerMode' + perMode={modeIdOrName: {on: [devIds], off: [devIds]}, ...}`. Optional `onlyOn` (Boolean, on/off only): RM's "command only switches that are on?" toggle -- when true the command reaches ONLY switches currently ON (`off` skips already-off switches; `on` is a no-op refresh of already-on ones).
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
- **HSM** (`capability='hsm'`): `command=armAway/armHome/armNight/disarm/rearm/disarmAll/armRules/cancelAlerts`. No `deviceIds` (**because** HSM is a hub-level service, not device-based). `getSetHSM` appears only when HSM is installed on the hub; there is no `armAll` -- use `armRules`.
- **Thermostat** (`capability='thermostat'`): `action=(any)` + `deviceIds` + optional `mode`/`fanMode`/`heatingSetpoint`/`coolingSetpoint`/`adjustHeating`/`adjustCooling`.
- **Shade/blind** (`capability='shade'`): `open`/`close`/`stop` + `deviceIds`. `setPosition` + `deviceIds` + `position` (0–100).
- **Fan** (`capability='fan'`): `setSpeed` + `deviceIds` + `speed` (low/med/high/auto/etc.). `cycle` + `deviceIds`.
- **Mode** (`capability='mode'`): `action='setMode'` + `modeId` (Integer) or `modeName` (String, case-insensitive). When `modeName` is supplied it is resolved to the numeric mode ID via `location.modes` before the write; an unknown name fails fast with the list of valid mode names. Use `hub_list_modes` to inspect available modes first. Note: `addAction` mode uses the `modeName` field for explicit name-based resolution; `addTrigger` mode uses the generic `state` field instead because triggers cover a superset of device-state events where a single field serves multiple capability types -- `modeName` vs `state` is an intentional surface difference, not a typo.
- **Hub Variable** (`capability='setVariable'`, alias `'variable'`): `variable` (target) + exactly ONE source mode -- `value` (numeric constant), `sourceVariable` (copy from another hub variable), `fromDevice` (`{deviceId, attribute}` -- read a device attribute), or `math` (`{left, op, right}` -- structured variable math). All variable names (`variable`, `sourceVariable`, `math` var-operands) must be existing hub variable names -- unknown names are rejected before any write. The four source modes are mutually exclusive; providing more than one is rejected. `math` binary operators (`+ - * / %`) require `right`; unary operators (`negate absolute round random sqrt sin cos tan asin acos atan log toRadians toDegrees`) reject `right`. A `math` operand that is a number becomes a literal constant; a string operand is a variable name. `fromDevice` reads from any hub device (not just MCP-selected); an attribute not in the device's filtered enum is rejected with `success=false` and the device's available-attribute list. `fromDevice` and `math` require a Number or Decimal target variable -- RM does not offer those source modes for String/Boolean/DateTime targets, so they are rejected with `success=false` before any write. See `addAction setVariable` in `docs/rm_action_subtype_schemas.md` for the full field reference.
- **Logging / Messaging**: `capability='log' + message`. `capability='notification' + deviceIds + message`. `capability='httpGet' + url`. `capability='httpPost' + url + body + optional contentType`. `capability='ping' + ip`.
- **Music/Sound** (`capability='volume'`/`'mute'`/`'chime'`/`'siren'`): `volume + deviceIds + level`. `mute + action='mute'/'unmute' + deviceIds`. `chime + deviceIds + optional playStop/soundNumber`. `siren + deviceIds + optional sirenAction`.
- **Rules** (`capability='privateBoolean'`/`'runRule'`/`'cancelTimers'`/`'pauseRule'`): `privateBoolean + ruleIds + value (Boolean)`. `runRule + ruleIds` (runs actions). `cancelTimers + ruleIds`. `pauseRule + action='pause'/'resume' + ruleIds`. For all four, each `ruleIds` target must resolve to an existing Rule Machine rule -- checked against the live RM rule list before any write -- and a target id that is not an existing rule is rejected fail-loud ("RM is not touched"), steering to `hub_list_rules`, rather than baking a dangling rule reference that renders broken and never fires. On a hub whose rule list can't be resolved (RM not installed or the app-tree read failed) the check is skipped and the write proceeds -- a genuinely dangling id then still bakes broken downstream, matching the conservative transient-skip stance of the device-id guard. A hub with zero rules is NOT a can't-resolve case: every rule target is then rejected fail-loud (none can exist).
- **Activate a Scene / Room Lighting group**: RM 5.1 has no dedicated activate-scene action subtype. Each Scene / Room Lighting instance spawns an activator device that carries the `switch` capability -- activate it via the Switch action: `capability='switch'` + `action='on'` + `deviceIds=[<activatorDeviceId>]` (use `action='off'` to send an off/deactivate command, whose effect is configuration-dependent). Note the discoverability trap: the `activate_scene` action exists ONLY on the legacy custom rule engine (the `hub_*_custom_rule` tools, documented under `hub_get_tool_guide(section='rules')`) -- it is NOT part of this native `addAction` surface.
- **Device control**: `capability='capture' + deviceIds`. `capability='restore'` (no fields). `capability='refresh' + deviceIds`. `capability='poll' + deviceIds`. `capability='disableDevice' + action='disable'/'enable' + deviceIds`.
- **Flow control** (delay/wait/repeat/exit/comment/conditional):
  - `delay` + `hours`/`minutes`/`seconds` + optional `cancelable`/`random` OR `variable=<varName>` (variable-sourced seconds)
  - `delayPerMode` + `perMode={modeIdOrName: {hours, minutes, seconds}, ...}`
  - `cancelDelay`, `exitRule`, `stopRepeat` (no fields)
  - `comment` + `text`
  - `repeat` + `hours`/`minutes`/`seconds` + optional `times` + `stoppable`
  - `repeatWhile` + `expression={conditions:[...], operator?:..., operators?:[...]}` + optional `hours`/`minutes`/`seconds`/`times`/`stoppable`
  - `waitExpression` + `expression={...}` + optional `delay={hours,minutes,seconds}` + `useDuration=true|false`
  - `waitEvents` + `events=[{capability, deviceIds, state, andStays?}, ...]`. Per-event `andStays` is either `true` (wait until the state has held, zero extra duration; an empty map `{}` is equivalent) OR `{hours?, minutes?, seconds?}` to require it hold that long (writes the DASH-indexed `SHours-/SMins-/SSecs-<N>` -- distinct from the trigger's no-dash `SHours<N>`, **because** the doActPage and selectTriggers wizards use different field-name conventions for this slot). A **Mode** event uses `{capability:'Mode', state:<mode name or list of names>}` or `{capability:'Mode', modeIds:[...]}` (`deviceIds` is rejected on a Mode event; the mode is written to RM's mode picker, not `tstate`). **LIMIT**: only ONE `waitEvents` action per rule; RM 5.1 stores wait events in global per-rule settings (not per-action), so a second `waitEvents` action silently overwrites the first. Combine multiple waits into one action's `events` array, or split into chained sub-rules.
  - `ifThen` + `expression={...}` (opens IF block; close with `endIf`)
  - `elseIf` + `expression={...}` (continues IF block; needs preceding `ifThen`)
  - `else` (no fields; needs preceding `ifThen` or `elseIf`)
  - `endIf` (no fields; closes the IF block)

  Per-condition shape for `expression.conditions[]` (same as `addRequiredExpression`): `{capability, deviceIds?, state?, comparator?, value?, compareToVariable?, attribute?, not?, rawSettings?}`. Convenience: pass singular `deviceId: N` (integer) instead of `deviceIds: [N]` (array) -- the dispatcher normalizes before writing. If both `deviceId` and `deviceIds` are supplied, `deviceIds` wins. The same per-capability extended shapes apply -- Mode `modeIds`, Between two times `start`/`end` (`{type:'clock'|'sunrise'|'sunset', time?:'HH:mm', offset?:<minutes>}`; clock `time` is hub-local wall-clock, converted to ISO datetime internally; requires hub `location.timeZone` to be set in Settings > Location and Modes), Variable `variable`+`comparator` (with optional `compareToVariable` for a variable RHS), Custom Attribute `attribute`+`comparator`, compareToDevice Map (`{deviceId, attribute?, offset?}` -- device-relative RHS: the walker toggles `isDev_<N>=true` to reveal the `relDevice_<N>` reference-device picker and writes the optional offset to `state_<N>`; if the offset slot is absent on the firmware, `settingsSkipped` carries `offset_field_not_revealed` and `partial:true`. compareToDevice is mutually exclusive with `state`/`value` and `compareToVariable`). The shared walker `_rmWalkConditionReveal` handles all per-capability reveal sequences for both `ifThen`/`elseIf`/`repeatWhile`/`waitExpression` (doActPage) and `addRequiredExpression` (STPage). `compareToVariable` (variable-vs-variable) IS supported on this row -- the walker toggles `isVar_<N>=true` and discovers the firmware-assigned right-hand variable picker from the live schema; `compareToVariable` and `value`/`state` are mutually exclusive. See the "Extended per-capability spec shapes" section below.

  **Note: nested `subExpression` is REQUIRED-EXPRESSION-ONLY today.** `addAction` (ifThen/elseIf/repeatWhile/waitExpression) rejects nested `subExpression` in `expression.conditions[]` with a targeted error (`"nested subExpression on this row is not yet supported"`) -- flatten the conditions list or move the nested expression to a Required Expression. `addRequiredExpression` supports nesting today.

  Note: some sensor capabilities (Water sensor, Smoke detector, Carbon monoxide detector, Tamper alert, Acceleration) report discrete events -- use capability-specific state names (e.g. `'wet'`/`'dry'`, `'detected'`/`'clear'`, `'active'`/`'inactive'`) rather than numeric comparator conditions. Carbon dioxide sensor is intentionally EXCLUDED here: `CarbonDioxideMeasurement` is numeric ppm (comparator + value), not a discrete enum.

##### `addRequiredExpression` STPage capability list

RM 5.1 Required Expression conditions accept these `capability` values (per-condition):

- **Device-state**: `Switch`, `Motion`, `Contact`, `Lock`, `Presence`, `Smoke detector`, `Water sensor`, `Tamper alert`, `Acceleration`, `Carbon monoxide detector`, `Carbon dioxide sensor`, `Power source`, `Window Shade`
- **Numeric**: `Battery`, `Dimmer`, `Energy meter`, `Fan Speed`, `Humidity`, `Illuminance`, `Power meter`, `Temperature`, `Thermostat cool setpoint`, `Thermostat fan mode`, `Thermostat heat setpoint`, `Thermostat mode`, `Thermostat state`
- **Time-based**: `Days of week`, `Between two dates`, `Between two times`, `On a Day`
- **Hub state**: `Mode`, `Private Boolean`
- **Variable comparison**: `Variable`
- **Custom / other**: `Custom Attribute`, `Last Event Device` (not a condition -- see note below), `Lock codes` (not authorable here -- see note below)

Note: `Last Event Device` appears in the STPage condition picker but is not usable as a condition -- it references the device that fired the rule's trigger (an action-side reference, used in actions such as running a command on the triggering device), not a testable state, and it is not a trigger capability either. It is rejected fail-loud on every structured condition surface (`addRequiredExpression`, `addTrigger.condition`, and the `addAction` expression subtypes); remove it from the expression. `Lock codes` likewise appears in the STPage condition picker but cannot be authored through the structured condition path -- a Lock codes condition needs a lock device plus a specific code name and the tool has no field for either, so it is rejected fail-loud with a pointer to the Rule Machine UI; author it there or use a different testable capability.

Note: `Private Boolean` is only valid in Required Expressions -- it does NOT appear in the IF-expression capability list used by `ifThen`/`elseIf`/`repeatWhile`/`waitExpression`.

Note: some sensor capabilities (Water sensor, Smoke detector, Carbon monoxide detector, Tamper alert, Acceleration) report discrete events rather than a continuous enum state. Pass `state: 'wet'` / `state: 'dry'` for Water sensor, `state: 'detected'` / `state: 'clear'` for detector types, `state: 'active'` / `state: 'inactive'` for Acceleration -- NOT a comparator-based numeric condition. Carbon dioxide sensor is intentionally EXCLUDED: `CarbonDioxideMeasurement` is numeric ppm (use comparator + value), not a discrete enum; the names look superficially symmetric to Carbon monoxide detector but RM 5.1 treats them differently. See `docs/rm_action_subtype_schemas.md` for the full state-value table.

##### `replaceRequiredExpression` -- change an existing Required Expression in place

`addRequiredExpression` refuses (success:false, `requiredExpressionAlreadyExists:true`) when the rule already has a committed Required Expression. To CHANGE the existing expression, use `replaceRequiredExpression` -- same `appId`, no clone. The spec shape is IDENTICAL to `addRequiredExpression` (`{conditions:[...], operator|operators}`, with the same per-condition fields and every extended per-capability shape including nested `subExpression`), so the replacement can be single-condition, multi-condition, or nested.

Mechanism (full-formula replace, matching `addRequiredExpression`'s whole-expression semantics): the tool deletes the whole committed expression (clicks `cancelST`, "Delete Required Expression"), then builds the new condition(s) by delegating to the same validated `addRequiredExpression` walker -- which navigates fresh from `mainPage`, sets `useST`, reaches the `cond` new-condition selector, walks the condition(s), seals via `hasRule`/`doneST`, submits the sub-page Done back-nav -- and fires `updateRule`.

- **Precondition.** A committed Required Expression MUST already exist. If none does, `replaceRequiredExpression` returns `success:false, requiredExpressionMissing:true` with an error steering you to `addRequiredExpression` -- it never silently turns a replace into an add.
- **Destructive-window contract (the key safety property).** The `cancelST` delete is immediately destructive -- the committed gate is gone the instant it is clicked. Two invariants protect your data: (1) the ENTIRE new-conditions spec is validated BEFORE the click (conditions/operator/operators rules, deviceId existence), so a malformed spec fails with the OLD expression still intact (no delete); (2) after the delete succeeds, ANY failure auto-restores the pre-op backup the tool took. The covered window extends through the TRAILING finalize: the rebuild, the trailing `updateRule` click, AND the post-commit health check are all inside it, so a post-commit health flip (the rebuild baked but left the rule unhealthy, e.g. a ghost-`ifThen` clear wrapped it in `IF(**Broken Condition**)`) OR a rejected trailing `updateRule` also triggers the restore -- not just a rebuild that fails to bake or a post-delete re-read failure. The result then carries `requiredExpressionReplaced:false` plus one of three honest restore outcomes: `requiredExpressionRestored:true` ("the replace failed during the rebuild; the original Required Expression was restored from backup <key>"); OR `requiredExpressionRestored:false` when the original expression could NOT be put back in place -- no pre-op backup was available, the backup replay returned a partial failure, the replay could not be confirmed back on STPage (the rule may be left UNGATED), or the restore itself threw ("the original Required Expression was DELETED and auto-restore did not complete -- manually restore via `hub_restore_backup(backupKey=<key>)`"); OR `requiredExpressionRestored:false` plus `requiredExpressionRestoredAs:<newId>` when the auto-restore could not reuse the original `appId` and recreated the rule under a NEW id (the original `appId` is now a dead husk -- use the new id and delete the husk via `hub_delete_native_app(appId=<original>)`). A post-delete failure is NEVER reported as a benign no-op.
- **Fail-loud delete.** If the `cancelST` delete is silently rejected (STPage still shows the committed-expression controls afterward), the tool auto-restores the pre-op backup and returns `success:false` naming the step; the existing expression is preserved. Inspect via `hub_get_app_config(appId)`.
- **Success envelope.** On a clean success (the rebuild baked, the trailing `updateRule` fired, and the post-commit health check passed) the response carries `requiredExpressionReplaced:true` (a NEW expression was COMMITTED, not merely the old one deleted) plus the same `conditionIndices` / `settingsApplied` / `settingsSkipped` / `partial` / `repairHints` envelope as `addRequiredExpression`. Unlike `addRequiredExpression`, the replace path does NOT leave a committed-but-not-live result on a trailing-`updateRule` failure: because the delete already happened, a rejected `updateRule` (or a post-commit health flip) is treated as a post-delete failure and AUTO-RESTORES the pre-op backup (see the destructive-window contract above) -- so on that path you get `success:false` + `requiredExpressionReplaced:false` + `requiredExpressionRestored:*`, with the trailing-updateRule slots (`updateRuleFailed` / `expressionNotLive` / `updateRuleError`) still populated so you can see WHY the finalize failed.
- **Deleted-condition residue.** The replaced condition's underlying settings (e.g. `rCapab_<N>` / `rDev_<N>` / `state_<N>` / `modes<N>`) linger in the settings pool but are NOT part of the active formula -- this is harmless and renders cleanly (a config/STPage read surfaces only active-formula slots, so the orphans do not even appear); no extra cleanup write is issued. The new condition slot indices may continue past the deleted slot (allocation does not reuse the deleted index).
- **Committed-RE detection.** A committed Required Expression is detected by the `cancelST` + `editST` control pair on STPage (the no-RE state offers neither, and the condition-building state is ruled out separately). This is intentionally a two-field tell, narrower than `addRequiredExpression`'s three-field check, because the `cancelST`/`editST` pair is the firmware-stable marker on RM 5.1.8 whereas the third field (`stopOnST`) varies across revisions.

`replaceRequiredExpression` is also a valid `patches[]` op (reported as `op: 'replaceRequiredExpression'`). Inside a `patches[]` batch its auto-restore is scoped to a per-op snapshot taken just before the op, so a failed replace op does NOT revert earlier successful ops in the same batch.

##### Extended per-capability spec shapes

Applies to `addRequiredExpression.conditions[]` (STPage) and `addAction.expression.conditions[]` (doActPage); the shared walker `_rmWalkConditionReveal` handles every per-capability reveal sequence below. For `addTrigger.condition`, see the narrowed support list in the "Inline conditional trigger" entry above -- Mode-via-picker / Between two times / compareToDevice are NOT yet available on the trigger row.

**Mode**: `{capability:'Mode', state:'Night'}` or `{capability:'Mode', modeIds:['3']}`.
  The walker resolves mode names to IDs via `location.modes` and writes the firmware-assigned `modes<N>` picker (e.g. `modes1`) discovered from the live schema (not hardcoded). Note: triggers use `modesX<N>`; STPage/doActPage conditions use `modes<N>` (no `X` prefix).

**Between two times**: `{capability:'Between two times', start:{type:'clock'|'sunrise'|'sunset', time?:'HH:mm', offset?:<minutes>}, end:{...same shape}}`.
  `time` is required when `type='clock'` (walker converts `HH:mm` to ISO datetime with hub-local TZ offset internally); `offset` is required when `type='sunrise'` or `'sunset'`. User-supplied `HH:mm` is interpreted as hub-local wall-clock time; the walker constructs ISO datetime with the anchor-date timezone offset internally so DST shifts between now and the January anchor do not affect rendering. Firmware fields: `starting<N>` (type enum), `startingA<N>` (clock time), `ending<N>`, `endingA<N>`/`endSunriseOffset<N>`. Precondition: hub timezone must be configured (Settings > Location and Modes). If `location.timeZone` is null, the walker throws before touching the wizard -- set hub timezone first.

**Variable comparison**: `{capability:'Variable', variable:'<hubVarName>', comparator:'=', value:<v>}` (constant RHS) or `{...comparator:'=', compareToVariable:'<otherHubVarName>'}` (variable RHS).
  A free-valued (String) variable (and a free-valued Custom Attribute) also accepts the STRING comparator `*contains*` (substring match, written verbatim -- keep the asterisks; the substring is the `value`). There is no "does not contain": express negation with `not:true` + `*contains*`. A numeric variable uses `=`/`<`/`>`/etc. instead.
  Writes the firmware-assigned variable-name picker discovered from the live schema. Fail-loud if the variable name is not in the revealed enum AND the schema option list is non-empty. When the schema option list is empty (firmware lazily-populates the enum, or probe-timing race), the walker logs warn + pushes a `variable-validation` / `api_unavailable` sentinel to `settingsSkipped` and proceeds with the write unvalidated (`partial:true`). For `compareToVariable` the walker toggles `isVar_<N>=true` and discovers the right-hand picker rather than hardcoding `xVarR_<N>` -- **because** `selectTriggers` consistently exposes `xVarR` but the walker pages (STPage/doActPage) can expose a differently-suffixed field, so the walker resolves whatever the live schema reveals; an empty RHS option list degrades with a `compareToVariable-validation` / `api_unavailable` sentinel (`partial:true`). For value-comparison comparators `value` and `compareToVariable` are mutually exclusive (passing both is rejected); state-change comparators (`*changed*`/`*became*`) take no RHS.

**Device-relative comparison**: `{capability:'Temperature', deviceIds:[N], comparator:'>', compareToDevice:{deviceId:M, attribute?:'temperature', offset?:-2}}`.
  Compares a device's attribute to another device's reading on the SAME capability, optionally offset by a decimal amount, rather than a literal threshold (e.g. "Temperature of devN is > devM minus 2.0"). The walker writes the comparator `RelrDev_<N>`, toggles `isDev_<N>=true` ("Relative to a device?") to reveal the SINGLE reference-device picker `relDevice_<N>`, writes the reference device id, then writes the optional offset to `state_<N>` (omit -> offset 0). `relDevice_<N>` is a capability.* device picker locked to the LHS capability; on normal firmware RM populates its dropdown client-side, so the configPage schema exposes no options and an empty option list is the normal case (does NOT flag `partial`). The reference `deviceId` is existence-validated hub-wide before any write (the same `/device/fullJson` check the LHS `deviceIds` get); a nonexistent id is rejected up front. On a rare firmware variant that DOES surface device-picker options, the walker additionally defensively rejects a reference id absent from that list. Capability-mismatch (a reference device lacking the LHS capability) otherwise surfaces in the rendered/broken state. compareToDevice is rejected with a literal RHS (`state`/`value`) or a `compareToVariable` RHS -- mutually exclusive; supply exactly one RHS shape. Passing compareToDevice on a non-numeric capability (Mode / Between two times / Variable / Custom Attribute) is rejected up front with a fail-loud error naming the capability -- it is NOT silently dropped. There is NO separate reference-attribute picker: the compared attribute is implied by the shared capability, so `compareToDevice.attribute` is OPTIONAL and informational (no wire consumer; neither validated nor written). Fail-loud if the comparator is omitted or if `relDevice_<N>` does not reveal after `isDev_<N>=true`. If the offset field (`state_<N>`) is absent it drops the offset with an `offset_field_not_revealed` sentinel (`partial:true`). **Intentional isDev/isVar asymmetry (do not "fix"):** an EMPTY option list is NORMAL for `compareToDevice`'s `relDevice_<N>` BECAUSE it is a capability.* DEVICE picker (RM fills it client-side) -- no options, no sentinel, no partial. This deliberately differs from `compareToVariable`, whose right-hand picker is an ENUM picker where an empty option list IS an anomaly and emits an `api_unavailable` sentinel with `partial:true`. The divergence reflects picker type (device vs enum), not an oversight.

**Sub-expressions (parens) -- addRequiredExpression-only**: for nested expressions like `P1 AND (P2 OR P3)`, a condition entry can also be `{subExpression: {conditions: [<inner conds>], operator?: 'AND'|'OR'|'XOR', operators?: [...]}}`. The STPage walker recursively handles nested sub-expressions of arbitrary depth. `addAction` (`ifThen`/`elseIf`/`repeatWhile`/`waitExpression`) does **NOT** support `subExpression` on this row -- the doActPage walker rejects it with `"nested subExpression on this row is not yet supported"`. Flatten the conditions list or move the nested expression into a Required Expression.

##### Partial-success and trailing-updateRule response slots

`addRequiredExpression`, `addTrigger`, and `addAction` all return rich envelopes. Beyond `success`/`partial`/`settingsSkipped`/`repairHints`, the following slots help callers diagnose without log-grep:

- `settingsSkipped[]` sentinel reasons callers may see:
  - `offset_field_not_revealed` -- compareToDevice optional offset field (`state_<N>`) absent after the reference-device write. Entry carries the requested `offset` value for reference; the offset is dropped but the device-relative comparison is otherwise complete. Flips `partial:true`.
  - `api_unavailable` paired with `key: "variable-validation"` (LHS variable picker) OR `key: "compareToVariable-validation"` (RHS variable picker for `compareToVariable`) -- the ENUM picker returned an empty option list, so schema-side validation could not run. The write still PROCEEDS best-effort with the caller-supplied value (success stays true; retry is NOT needed) and `partial:true` flags only that validation was skipped. NOTE (intentional isDev/isVar asymmetry -- do not "fix"): `compareToDevice` does NOT emit this. Its `relDevice_<N>` reference picker is a capability.* DEVICE picker, which exposes no options client-side -- an empty option list is normal there and is NOT a validation gap (no sentinel, no `partial`). This deliberately differs from `compareToVariable`'s ENUM picker, where an empty option list IS an anomaly. The divergence reflects picker type (device vs enum), not an oversight. A wrong-capability reference device surfaces in the rendered/broken state, not a pre-write check.
  - `reveal_fallback_to_existing_field` -- the walker matched an already-visible field instead of a newly-revealed one (static-schema firmware path). **Informational** -- does NOT flip `partial` by itself.
  - `useST_idempotent_noop` -- the idempotent `useST=true` mainPage toggle (Step 1 of `addRequiredExpression`) was already set, so the write did not advance the schema. Cosmetic, not a lost value. **Informational** -- does NOT flip `partial` by itself.
  - `device_list_committed_schema_unchanged` -- an action's device-picker field (`shadeOpenClose.<N>` / `onOffSwitch.<N>` / `lockLockUnlock.<N>` / `fanRL.<N>` / ...) was the LAST write of the action and revealed no further schema, so the write could not be seen to "advance" and was initially tagged `silent_rejection` -- but the requested device IDs DID commit (value-echo-gated against `statusJson`, entry carries `committedDeviceIds`). Cosmetic on `addAction` only (**because** on the trigger/condition paths the device picker always reveals a subsequent schema field, so that path never produces the cosmetic silent_rejection this reclassifies). **Informational** -- does NOT flip `partial` by itself. A genuine device-write FAILURE (IDs absent from the echo) keeps `silent_rejection` and still flips `partial`.
  - `comparator_force_written_unverified` -- on a Custom Attribute add, the exposure-probe re-fetch (issued after writing the attribute to decide whether the comparator field is still exposed) failed transiently. The comparator was force-written straight to the page as a fallback so the value is not silently dropped, but the write could not be schema-confirmed. The add does NOT abort (`success` stays true; the field is in `settingsApplied`), but this flips `partial:true` so callers know to verify via `hub_get_app_config`.
  - `comparator_force_write_failed` -- the force-write fallback above ALSO failed (the hub rejected the POST, e.g. a stale version token). The comparator did not land. The add still does NOT abort -- the rest of the trigger/condition committed -- but this is genuine degradation and flips `partial:true`. Re-add the comparator via `hub_set_rule(walkStep=...)` or rebuild the row.
  - `comparator_not_representable_for_enum_attribute` -- a state-change comparator (`*changed*` / the `*became*` family, which carry no right-hand value) was requested on a Custom Attribute the hub recognizes as an ENUM (switch/motion/contact/lock/...). For such an attribute RM exposes only the value picker (e.g. on/off) and no comparator slot, so a no-RHS change comparator has nowhere to land and cannot be represented through this path. The rest of the trigger/condition still commits, but the comparator is genuinely dropped, so this flips `partial:true` and adds a repair hint. To express "this attribute changed", trigger on the device's native capability instead (e.g. `capability:'Switch'`), or use a non-built-in attribute name (which RM treats as free-valued and exposes a real comparator). This applies across all four wizard surfaces (trigger row, conditional-trigger condition, STPage, doActPage). If the value picker DOES happen to offer a change-equivalent option, the helper routes it there instead and no skip is produced.
  - `change_comparator_not_representable_for_device_state` -- the device-state sibling of the entry above, on an `addTrigger` device-state capability (Switch/Motion/Contact/Lock/Water/Smoke/...). This skip is trigger-only **because** a device-state change comparator has no meaning on a condition (conditions are point-in-time, so `addTrigger.condition` / `addRequiredExpression` / `ifThen` reject a change comparator on a device-state capability outright rather than degrading). A device-state capability has no comparator field; its `tstate<N>` value picker carries both the state enum and any change option. When a no-RHS state-change comparator (`*changed*`/`*became*`) is requested but the live value picker offers no matching change option, the change token cannot land and is reported here (rather than being written to the absent `ReltDev<N>`, which would render the trigger "turns null"). Flips `partial:true`; the skip carries the picker's `pickerOptions` and a `hint` to write `tstate<N>` directly via a `walkStep` call. Routing is decided by the LIVE rendered field, so it covers every device-state capability -- not only the ones the discover schema enumerates.
  - `state_change_comparator_ignored_explicit_value` -- **Informational** (does NOT flip `partial`). A no-RHS state-change comparator was requested ALONGSIDE an explicit `state`/`value` (a contradictory spec). It fires on a device-state or enum Custom Attribute trigger AND on an enum Custom Attribute condition across the condition surfaces (the conditional-trigger condition wizard, STPage, and doActPage). The explicit value wins into the value picker and the row works as an equals-check, so the dropped change intent is reported informationally. Keyed on a synthetic non-field key (`comparator@tstate<N>` on the device-state trigger path; the hidden `ReltDev<N>` on the Custom Attribute trigger path; the hidden `RelrDev_<N>` on the Custom Attribute condition surfaces) **because** the real value-picker field is already listed in `settingsApplied` (the explicit value committed), and repeating it in `settingsSkipped` would double-list it and violate the applied/skipped-disjoint invariant.
  - `state_change_route_unverified_fetch_failed` -- the post-device-write `selectTriggers` re-fetch (needed to inspect which field the wizard renders before placing a state-change comparator) failed transiently, so the change token could not be verifiably placed. This applies only to a non-numeric (device-state/unknown) family; a numeric capability instead force-writes its `ReltDev<N>` comparator best-effort (`comparator_force_written_unverified`) since `*changed*` is a real option there. Rather than aborting or force-writing a possibly-wrong value, the device-state add degrades to `partial:true` with a `hint` to verify via `hub_get_app_config` and, if needed, write `tstate<N>` (device-state) or `ReltDev<N>` (numeric) via a `walkStep` call. Because the re-fetch failed, the real target field is unknown, so this skip keys on the synthetic `comparator@tstate<N>` -- the repair target lives in the `hint`, not the key.
  - `not_in_schema` -- a written field was absent from the current page schema, so the value did not land. This IS genuine degradation and flips `partial` on `addTrigger`, the condition wizard, AND the walker pages STPage/doActPage: a field the helper tried to write but the live schema never exposed means the value was lost. Note that a state-change comparator such as `*changed*` is itself written as a VALUE into the comparator field (e.g. `ReltDev_<N>`), not an absent RHS, so a clean `*changed*` trigger on a free-valued attribute (where the comparator field IS exposed) produces NO `not_in_schema` skip on its comparator -- if one appears on a real field, the value genuinely failed to write and the partial flag is real. (When `*changed*` is requested on an ENUM-recognized attribute -- where RM exposes no comparator slot at all -- the comparator is reported via the dedicated `comparator_not_representable_for_enum_attribute` skip above, not `not_in_schema`.) Two non-degrading exemptions do NOT flip `partial`:
    - The cosmetic `isCondTrig.<N>` finalize toggle on `addTrigger`: after a non-conditional trigger commits, the helper writes `isCondTrig.<N>=false` best-effort to dismiss the residual "Conditional Trigger?" prompt RM only sometimes exposes; when the prompt already closed the field is gone and that skip is exempt.
    - The enum-recognized **Custom Attribute** comparator across all FOUR wizard surfaces: the trigger row (`ReltDev<N>`), the conditional-trigger condition wizard (`RelrDev_<N>`), the STPage walker, and the doActPage walker. When the hub treats the chosen attribute as an ENUM (switch, motion, contact, lock, ...), picking it re-renders the page to reveal the enum value picker (`tstate<N>` / `state_<N>`) and HIDE the comparator field. The helper detects the value picker is exposed and deliberately does NOT write a VALUE comparator (`=`/`<`/etc.) -- so NO skip is produced for that case (it is exempt by construction, not by exempting a produced skip) and `partial` stays false; the requested value lands in the picker. The ONE exception is a no-RHS state-change comparator (`*changed*` / `*became*`): there is no comparator slot to hold it AND no value to put in the picker, so unless the picker itself offers a change-equivalent option the comparator is genuinely unrepresentable and the helper emits a `comparator_not_representable_for_enum_attribute` skip (flips `partial`, see above) rather than reporting a false clean success. A free-valued Custom Attribute still reveals the comparator and writes it normally. The walker handles the *neither-field-rendered* edge case asymmetrically between its two Custom Attribute sites: the dedicated capability-block (Site A) THROWS because its reveal-step contract has no field to write into without a revealed target, whereas the default enum/numeric block (Site B) still attempts the write because its `writeST` POSTs-then-verifies (no schema-containment pre-gate) -- on a hidden field the post-write verify records a `silent_rejection` skip that flips `partial`, surfacing the degradation without hard-failing the wizard. On the trigger row and condition wizard the analogous neither-rendered write goes through `_rmWriteSettingOnPage`, which DOES schema-gate, so the comparator is not POSTed and a `not_in_schema` skip flips `partial` instead. On a TRANSIENT exposure-probe re-fetch failure (empty/unparseable response after the attribute write), all four surfaces degrade gracefully rather than aborting: the comparator is force-written best-effort and a `comparator_force_written_unverified` skip flips `partial` (verify via `hub_get_app_config`).
- Trailing-updateRule failure slots:
  - `addRequiredExpression`: `updateRuleFailed: true` + `expressionNotLive: true` + `updateRuleError: <message>` when the post-commit `updateRule` click is rejected. `success` flips false and `partial` flips true; the expression IS committed but not live (`repairHints` adds a recovery line pointing at `hub_set_rule(button='updateRule', confirm=true)` -- re-fire to make it live, no restore needed since nothing was deleted).
  - `replaceRequiredExpression`: a rejected trailing `updateRule` is handled DIFFERENTLY from `addRequiredExpression`. Because the replace already deleted the old expression, a rejected `updateRule` is a post-delete failure: it AUTO-RESTORES the pre-op backup, so the result is `success:false` + `requiredExpressionReplaced:false` + `requiredExpressionRestored:*` (NOT a committed-but-not-live result), with `updateRuleFailed: true` + `expressionNotLive: true` + `updateRuleError: <message>` still set so you can see why the finalize failed. On a clean replace it returns `requiredExpressionReplaced:true`; when there is no committed expression to replace it returns `requiredExpressionMissing:true` (success:false). See the destructive-window contract above for the full restore-outcome set.
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
    "verifyVia": "hub_get_app_config(appId: <id>)",
    "ifActionsAbsent": "treat as success -- clearActions committed post-response",
    "ifActionsPresent": "wait 15s, then call hub_get_app_config to re-check. If actions still present, retry clearActions, or clear state.editAct via hub_set_rule(button='cancelAct', pageName='doActPage', confirm=true) first.",
    "avoid": ["cancelTrash"]
  },
  "backup": "<backup>",
  "restoreHint": "<restoreHint>",
  "verifyHint": "<verifyHint>"
}
```

The caller's recovery path is to call `hub_get_app_config(appId)` and inspect the actions list. If absent, treat the operation as a success -- the delete committed between the POST return and this verify fetch. If still present, retry. **Do not** invoke `cancelTrash` to recover -- in trash-confirmation mode it can commit pending deletes rather than abort.

For `replaceActions`, this same retry-window-expired case on the inner clearActions step yields `stage: 'replaceActions.clear_committed_late_no_add'`. The add half is **not attempted** -- because the clear may have committed asynchronously, completing the add would risk a double-write if the caller retries the whole replace after seeing the partial. The original action specs are echoed back as `pendingActionsToAdd` so the caller can finish the replace via `addAction` (or bulk `addActions`) once `hub_get_app_config` confirms the clear committed. `clearActionsResult` exposes a fingerprint subset of the standalone clearActions partial: `{stage, asyncCommitLikely, actionsRequestedForRemoval, actionsStillPresent, error}` -- the outer envelope carries the `safeRecovery` / `backup` / `restoreHint` / `verifyHint` slots. On the replaceActions sub-shape, `verifyHint` AND `error` are replaced with copy that names the data-loss-protection rationale rather than echoing clearActions' diagnostic.

`removeAction`, `removeTrigger`, and `modifyTrigger` remain on the legacy flat error shape (no `asyncCommitLikely` envelope) **because** each operates on a single row and there is no add half, so the data-loss case (add-half double-write on retry) that justifies the richer recovery contract does not apply.

When `clearActions` / `replaceActions` appears inside a `patches` sub-spec and hits the retry-window-expired case, the raw `[asyncCommitLikely]` marker is stripped from the per-patch error **but** the structured envelope is NOT emitted -- because patches continues over subsequent ops and the per-patch `success: false` plus the outer `partial:` flag already surface the failure, the caller instead receives a flat `{success: false, op: '...', error: '...', spec: ...}` entry in `patches[i]` and should match on the "after 10s of retries" phrase to identify the eventual-consistency case.

Implication: when a `patches` sub-spec containing `clearActions` / `replaceActions` hits the async-commit case, subsequent patch ops in the same call still execute and the trailing `updateRule` still fires. The first patch is reported as failed in `patches[i]` but the call is not aborted. For atomic clear-then-add semantics, use top-level `replaceActions` (which skips the add half on async-commit) rather than chaining patches.

Degenerate-case semantics: `actionsRequestedForRemoval: null` indicates the pre-write snapshot fetch failed; `actionsStillPresent: null` indicates the post-window re-fetch failed; `possibleStateEditAct: false` is the safe default when `_rmGetStateEditAct` fails. These three are best-effort diagnostic slots -- `null` does NOT mean "field absent," it means "fetch failed on this side; the structured envelope is still actionable via the other fields and `safeRecovery`."

#### `hub_set_rule` create reference

##### appType options

`appType` selects which class of native app to create. NOTE: this selector belongs to `hub_set_native_app` — `hub_set_rule` always creates `rule_machine` rules (default: `rule_machine`):

- `rule_machine` — Rule Machine 5.1. Creates an RM 5.1 rule specifically; RM 5.0 is not selectable.
- `button_controller` — Button Controller-5.1 instance. (Create button RULES under it with `buttonRule`, not `appType`.)
- `groups_scenes` — Group-2.1. `notifier` — Notifier. (Visual Rules are NOT an `appType` here — create them with `hub_set_visual_rule`; `hub_set_native_app` only edits/deletes them by `appId`.)
- `basic_rule` — Basic Rule-1.0. A classic dynamicPage app (generic `createchild` works); edits are clean because its `commitButton` is null (submitOnChange, no `updateRule` button).
- Button Rules are NOT an `appType` — they're grandchildren of a Button Controller; use the `buttonRule` param. Other classic SmartApps (Room Lighting, etc.) edit/delete by `appId` today; add a `_appTypeRegistry` entry to enable their creation.

##### Partial-success protocol

The tool ALWAYS creates the rule shell (you get an `appId` back) even if some triggers/actions fail to fully bake. Inspect the result:

- `partial: true` + `partialTriggers: [N, ...]` / `partialActions: [N, ...]` → some pieces are incomplete (this includes any per-item result with `partial: true` OR `success: false`).
- `repairHints: [...]` → concrete next-step instructions.
- Each per-trigger / per-action result has its own `success`, `partial`, `settingsSkipped`, `repairHints`, and `health` block. `success: true, partial: true` on an inner result means the row was written but needs repair.

The right move when `partial: true` is to follow the `repairHints`, NOT to delete the rule and retry from scratch. Tool-only repair via `hub_set_rule(walkStep={...})` / `replaceActions` / `removeAction` can usually finish the job. Only declare failure after exhausting those repair attempts.

#### `hub_set_rule` rule-level boolean flags

Two rule-level toggles are plain mainPage booleans, distinct from the `addTrigger`/`addAction`/`addRequiredExpression` shortcuts. Set them on an EDIT call via the raw `settings` map -- `hub_set_rule(appId=<id>, settings={<flag>: true}, confirm=true)` (they are NOT the condition/trigger-level `rawSettings` escape hatch). Both are mainPage writes, so they auto-commit via the implicit `updateRule` that re-initializes the rule.

- **`useST`** (boolean) -- the "Use Required Expression" flag. `settings={useST: true}` only EXPOSES the Required Expression sub-page (the "Define Required Expression" href); it authors no condition. Prefer `addRequiredExpression`, which sets `useST` for you and builds the actual expression -- setting `useST: true` bare, with no expression, just reveals an empty Required Expression surface.
- **`isFunction`** (boolean) -- the "function mode" flag. `settings={isFunction: true}` marks the rule as a function that returns a value, so other rules can call it as a function. There is no structured shortcut; write it directly.

### Visual Rules Builder tools (in `hub_manage_rule_machine`; read also in `hub_read_rules`)

- **`hub_get_visual_rule(appId?)`** — list every Visual Rules Builder rule (omit `appId`: `{appId, name, disabled}` entries) or read one rule's full definition. Every single-rule success response carries `format`: `'classic'` (`{whenNodes, thenNodes, elseNodes}`) or `'graph'` (`{version, nodes, edges}`). Read master.
- **`hub_set_visual_rule(appId?, name, definition, paused?, confirm)`** — create (omit `appId`; `name` + `definition` required) or edit (the `definition` replaces wholesale, `name` renames, `paused` pauses/resumes). The definition's format must match the rule's existing format; responses include a read-back `verified` flag. Write master + `confirm=true` + a backup within 24h.
- **`hub_delete_visual_rule(appId, confirm)`** — type-gated delete (refuses ids that are not VRB rules); returns the rule's `predeleteDefinition` so it can be recreated. Write master + `confirm=true` + a backup within 24h.

Full node schemas, field catalog, and a worked example: the "Visual Rules Builder reference" section below (`hub_get_tool_guide(section='visual_rule_reference')`).

---

## Visual Rules Builder reference (`hub_get_visual_rule` / `hub_set_visual_rule` / `hub_delete_visual_rule`)

Visual Rules Builder (VRB) is the PRIMARY rule engine for new automations; each rule is stored as ONE clean JSON definition (no wizard, no settings[] protocol). A VRB rule is: one or more trigger events, an optional condition gate, and then/else action branches — if/then/else logic is fully supported (a condition node routes execution to thenNodes or elseNodes). Pretty much everything can be done with it; use `hub_set_rule` (Rule Machine) when something complex is needed — nested or multiple condition blocks, loops, variables and expressions, capture/restore, waiting on a device-state expression (VRB's `wait` waits a fixed duration), or device commands outside the action catalog below.

### Two serializations (`format` in every single-rule success response)

A VRB rule speaks exactly one of two wire formats, decided by the hub firmware at creation. `hub_get_visual_rule` reports which; an edit's `definition` must match it.

**classic** — `{whenNodes: [...], thenNodes: [...], elseNodes: [...]}` (the when/then/else editor; what current firmware creates):
- Every node: `triggerType` (or `actionType`), `deviceIds` (ALWAYS present; mirrors the per-type device array), `index` (int, 0-based per list), `type` ("when"/"then"/"else"), optional `description` (HTML label).
- whenNode example (switch trigger): `{"triggerType": "switch", "switches": [59], "deviceIds": [59], "switchEvent": "Turns off", "index": 0, "type": "when"}`
- thenNode example (turn off): `{"actionType": "turnOff", "switches": [122], "deviceIds": [122], "index": 0, "type": "then"}`
- At least one whenNode must be a REAL trigger (the builder refuses rules whose only triggers are `timeIsBetween`/`daysOfWeek`).

**graph** — `{version: 1, nodes: [...], edges: [...]}` (the dormant 2.0 graph editor):
- Node: `{id, type: "trigger"|"condition"|"action", deviceIds: [...]}` + `triggerType`/`actionType` + per-type fields. Stored graph nodes put the node KIND in `triggerCondition` and the sub-condition in `condition`.
- Edge: `{from, to, port}`. Ports: `next` (trigger/action source), `true`/`false` (condition source). Triggers have no incoming edges; conditions/actions exactly one. No cycles.
- On the wire the graph travels as a JSON STRING inside `{name, ruleJson}` — the tool handles the double-encoding for you; always pass `definition` as a normal JSON object.

### Field catalog (classic + graph dialogs share these)

Triggers (`triggerType` → device array + event field):
- `switch` → `switches`, `switchEvent`: "Turns on" | "Turns off" | "Turns on and stays on for..." | "Turns off and stays off for..." (+ `switchStaysMinutes`/`switchStaysSeconds` on the stays variants)
- `motion` → `motionSensors`, `motionSensorEvent`: "Motion starts" | "Motion stops" | "Motion stops and stays inactive for..." (+ `motionStaysMinutes`/`motionStaysSeconds`)
- `contact` → `contactSensors`, `contactSensorEvent`: "Contact opens" | "Contact closes" | "...and stays open/closed for..." (+ `contactStaysMinutes`/`contactStaysSeconds`)
- `presence` → `presenceSensors`, `presenceSensorEvent`: "Everyone leaves" | "Someone arrives"
- `lock` → `locks`, `lockEvent`: "Locked" | "Unlocked"
- `button` → `buttons`, `buttonEvent`: "Pushed" | "Held" | "Released" | "Double tapped", `buttonIndex` (int)
- `temperature`/`humidity`/`illuminance` → `temperatureSensors`/`humiditySensors`/`illuminanceSensors`, `<type>SensorEvent`: "<Type> has risen above..." | "<Type> has fallen below...", value in `temperature`/`humidity`/`illuminance`
- `power` → `powerMeters`, `powerMeterEvent` (risen above / fallen below / become and stayed above|below + `power`, `powerStaysMinutes`/`Seconds`)
- `water`/`smoke`/`co`/`acceleration`/`shock` → `<type>Sensors` + `<type>SensorEvent` (exact English sentences from the builder UI)
- `timeOfDay` → `timeOfDay`: "HHMM" colon-less string (e.g. "0730")
- `sunriseSunset` → sub-condition beforeSunrise/sunrise/afterSunrise/beforeSunset/sunset/afterSunset + `minutesBefore/AfterSunrise|Sunset`
- `systemMode` → `modes`: [mode ids from hub_list_modes]

Conditions (classic: appear as whenNodes with condition `triggerType`s; graph: `type:"condition"` nodes): `switchCondition` (`switchState`: "Turned on"|"Turned off"), `motionCondition` (`motionSensorState`: "Motion is active"|"Motion is inactive"), `contactCondition`, `presenceCondition`, `lockCondition` (`lockState`), `temperatureCondition`/`humidityCondition`/`illuminanceCondition`/`powerCondition` ("... is above..."|"... is below..." + value), `systemModeCondition` (`modes`), `timeIsBetween` (specificTimes + `startTime`/`endTime` "HHMM", or sunriseToSunset/sunsetToSunrise), `daysOfWeek` (`daysOfWeek`: [0-6], 0=Sunday).

Actions (`actionType`): `turnOn`/`turnOff`/`toggle` (`switches`), `setBrightness` (`dimmers`, `brightness` 0-100), `setColorTemp` (`colorTempBulbs`, `colorTemp` Kelvin), `setColor` (`colorBulbs`, `color` {h,s,b}), `lock`/`unlock` (`locks`), `openValve`/`closeValve`, `openGarageDoor`/`closeGarageDoor`, `openWindowShade`/`closeWindowShade`, `pushButton` (`button` single id, `buttonIndex`), `sendNotification` (`notificationDevices`, `notificationMessage`), `speakNotification` (`speechDevices`, `speakMessage`), `controlPlayer` (`musicPlayers`, `musicPlayerAction`), `controlThermostat` (`thermostats`, setMode/mode, setFanMode/fanMode, setHeatingSetpoint/heatingSetpoint, setCoolingSetpoint/coolingSetpoint), `setMode`/`setModeUnlessAway` (`mode` single id), `exitAwayMode`, `wait` (`minutes`, `seconds` — cancelable), `cancelWait`.

Gotchas: event/state strings are EXACT English sentences including the trailing "..."; `deviceIds` must mirror the per-type device array; device ids are integers from hub_list_devices; times are colon-less "HHMM" strings.

### Worked example (classic create)

hub_set_visual_rule(name="Hallway motion light", confirm=true, definition={
  "whenNodes": [{"triggerType": "motion", "motionSensors": [42], "deviceIds": [42], "motionSensorEvent": "Motion starts", "index": 0, "type": "when"}],
  "thenNodes": [{"actionType": "turnOn", "switches": [17], "deviceIds": [17], "index": 0, "type": "then"}],
  "elseNodes": []
})

Then verify with hub_get_visual_rule(appId=<returned appId>) — the response echoes the persisted definition. Pause/resume with hub_set_visual_rule(appId=N, paused=true|false, confirm=true).

---

## Developer Mode

The `hub_manage_mcp` gateway exposes self-administration tools that let an LLM agent or CI/CD pipeline manage the MCP rule app's own configuration without manual UI intervention. **Requires the opt-in `Enable Developer Mode Tools` toggle** in the MCP rule app settings page (default OFF). Each successful write is logged at WARN level for audit. If the user sees "Developer Mode tools are disabled" errors, direct them to enable the toggle in the MCP Rule Server app settings.

### hub_manage_mcp (1 tool)

- **`hub_update_mcp_settings`** — update one or more of the MCP rule app's own settings (toggles, log level, tuning params, and the device-access scope `selectedDevices`)
  - Args: `settings` (map of `{key: value}`), `confirm=true`
  - Allowlisted keys (intentionally conservative): `mcpLogLevel`, `debugLogging`, `maxCapturedStates`, `loopGuardMax`, `loopGuardWindowSec`, `enableRead`, `enableCustomRuleEngine`, `useGateways`, `publishOutputSchemas`, `enableMandatoryBPS`, `bypassDeviceAllowlist`, and `selectedDevices`
  - **`publishOutputSchemas`** (bool, default OFF) — **leave OFF if using Claude Desktop** (or any spec-validating client): when ON, any inaccuracy in an advertised schema makes every call to that tool fail client-side (issues #290, #342, #354). Nothing requires this setting; do not enable it to "fix" a connection. Also reset OFF once by the issue #354 migration; enabling it after that is a deliberate choice and persists.
  - **`bypassDeviceAllowlist`** (bool, default OFF) — **DANGEROUS.** When ON, the per-device tools ignore the device allowlist (`selectedDevices`) and reach ANY device on the hub by id, with full parity. Covered tools (5): `hub_get_device`, `hub_get_device_attribute` (incl. block-poll `expectedValue`/`waitFor` convergence), `hub_call_device_command` (incl. `waitFor`), `hub_update_device` (config writes), and `hub_list_device_events` all work on unlisted devices. **NOT bypassed:** `hub_list_devices`, device swap/replace/delete, and device-health — those still honour the allowlist. The effect is **independent of Developer Mode** — this tool is Developer-Mode-gated, but once the flag is ON the bypass works in normal operation. Settable here OR via the app's **Device Access** settings page (a checkbox with a loud warning). Leave OFF unless you intend to expose the whole hub. Unlisted-device access uses the hub's id-keyed admin endpoints (`/device/fullJson`, `/device/eventsJson`, `/device/runmethod`, `/device/updateRoom` (by room NAME, existence-checked), `/device/disable`, `/device/preference/save`, `/device/update` (the wholesale form -- `label`/`name`/`deviceNetworkId` ride it, since the dedicated `/device/updateLabel` setter 404s on some firmwares)) rather than the Groovy device object; listed and MCP-managed virtual devices keep the existing rich path unchanged. On the bypass path `hub_call_device_command` can return `success: false` (a structured hub-rejection / not-confirmed error) where the listed path is fire-and-forget. `showOnHome`/`defaultCurrentState`/`tags`/`dataValues` writes are not supported on unlisted devices **because** their only id-keyed route is the wholesale `/device/update` form whose field encoding for those properties is unverified. Bypass attribute discovery (for `hub_get_device_attribute`) is limited to attributes that have already reported a value.
  - **`selectedDevices` — the MCP device-access scope** (the `selectedDevices` authorization that `findDevice`/`hub_list_devices` read), set without the Hubitat UI authorize step. Unlike the other (scalar) allowlisted keys, its value is a structured object **because** it is a `capability.*` multi-select that needs a List write plus atomic id validation — it cannot go through the scalar value coercion. Pass `{"mode":"replace"|"add"|"remove", "ids":["42","108"], "allowEmpty":false}` — or a bare array as the replace shorthand (`{"selectedDevices":["42","108"]}` == `{mode:"replace", ids:["42","108"]}`). `replace` sets the authorized set to exactly `ids`; `add` unions `ids` with the current set (safest for "grant one device" — no need to re-enumerate the whole list); `remove` subtracts `ids`. Discover ids with `hub_list_devices(scope='all')` (each carries an `mcpAuthorized` flag).
    - **Atomic validation (replace/add):** every id is checked against the full hub device list (`/device/listWithCapabilities/json`); an unknown id is rejected (`-32602`) with the offending id named and nothing in the whole batch is written. `remove` does NOT validate ids **because** removing an unknown/already-absent id is a harmless no-op — so a since-deleted device can still be cleaned out of scope (forcing a validation fetch there would block that legitimate cleanup)
    - **Self-lockout guard:** if the resulting set would be empty, the call is refused unless `selectedDevices.allowEmpty=true` (an empty scope blinds the server to every selected device). MCP-managed virtual devices stay reachable regardless
    - On success the response carries a `selectedDevices` sub-key `{mode, authorizedDeviceIds, authorizedCount, added, removed}`. Device visibility changes immediately — MCP clients may need to reconnect
  - **Excluded** from the allowlist: `enableWrite` (footgun — would disable own write path mid-session), `enableDeveloperMode` (lockout protection — must remain UI-only to disable), and `disabled_tools`/`disabled_gateways` (could self-disable this tool — UI-only on the Advanced page)
  - After changing any `enable*` toggle or `useGateways`, MCP clients (Claude Code, etc.) may need to reconnect to refresh the cached tool schema
  - Gated on: `enableDeveloperMode` + the Write master + `confirm=true` + a recent backup

### hub_update_package (top-level, Developer Mode)

`hub_update_package` is its own **top-level** tool (issue #250 pulled it out of the `hub_manage_mcp` gateway), surfaced on `tools/list` only when Developer Mode is on.

- **`hub_update_package`** — full HPM-repair self-deploy of the whole package at a git `ref`, in one call: **OVERRIDES whatever is installed**, the same way Hubitat Package Manager's Repair does, but anchored to `packageManifest.json` AT the `ref` so an unmerged PR installs (HPM repair reads only the published manifest).
  - Args: `ref` (branch/tag/SHA), `dryRun?` (plan-only, no writes), `baseUrl?` (raw-source base override for forks/CI), `confirm=true` (real deploy only)
  - Flow: fetch `packageManifest.json` at `ref` → install every declared library **bundle** first (the hub fetches + unpacks the `.zip`, overwriting libraries in place) → then deploy every declared **app**, the **self** app (`mcp`/`MCP Rule Server`) **last** so its recompile (which can drop the response, #237) is the final act. Deploys the parent app, the child app (`mcp`/`MCP Rule`), and the library bundle (each app's Apps Code class id is resolved at runtime by namespace+name).
  - **Brick-safe:** any failure before the self app save (app/manifest fetch, an unresolved app class, a bundle install, a non-self app) aborts **before** touching the self app — the running server is left exactly as-is and still updatable via `hub_update_app`, which is never modified and always available as the escape hatch.
  - **Hidden from `tools/list` when Developer Mode is off** (not merely runtime-refused). Use `dryRun=true` to fetch + parse + plan with zero writes (no `confirm`/backup needed) and see the bundles + apps that would deploy.
  - Gated on: `enableDeveloperMode` (hidden when off) + the Write master + `confirm=true` + a recent backup

### hub_manage_variables — `hub_delete_variable`

The `hub_delete_variable` op (DESTRUCTIVE, no undo) removes a rule_engine variable. Useful for sweeping orphaned `BAT_E2E_*` artifacts after CI runs, removing stale lease variables, or general cleanup. Connector-namespace deletion is not yet supported via MCP — use the Settings → Hub Variables UI for those.

## Hub Variables

Reference for the hub-variable tools (hub_get_variable, hub_create_variable, hub_delete_variable, hub_create_connector). Per-tool details below.

### hub_get_variable

The returned `source` field says which one matched (the hub-variable namespace is searched first, then rule-engine variables). For hub variables it also returns metadata: `type`, plus `deviceId`/`attribute` when a connector is linked.

### hub_create_variable

Create a new hub variable (global variable visible to apps and Rule Machine), one at a time or several in one call. Single form: name + type + value.

**Bulk form:**
- `variables=[{name,type,value}, ...]` — mutually exclusive with the single form (i.e. mutually exclusive with `name`/`type`/`value`).
- Bulk items are created sequentially; each succeeds or fails independently and the result reports per-item status.

**Why create first (vs hub_set_variable):**
- Use this before `hub_set_variable` for a name that doesn't exist yet — Hubitat's `setGlobalVar` cannot create, only update.
- Drives the Settings → Hub Variables wizard, since creation isn't exposed via the public app API.

**Constraints:**
- Name must not contain any of these characters: `' " \ ~ [ : ] < >`. (This applies to the single-form `name` and to each bulk item's `name`.)
- A String variable's initial value must be non-empty (an empty String reports success but never persists).

**Expose to device-only apps:**
- To also expose the variable to device-only apps, follow up with `hub_create_connector`.

### hub_delete_variable

Useful for sweeping orphaned `BAT_E2E_*` artifacts after CI runs, removing stale lease variables, or general cleanup.

**Why the reference-safety refusal matters:** the tool refuses by default when a child rule app references the variable because deletion would silently break those rules — null lookups → false conditions, and a literal `%varname%` left in substitutions. Pass `force=true` to proceed anyway after acknowledging the breakage.

### hub_list_variable_changes

Audit/debug what changed a hub variable and when, without polling hub_get_variable. This buffer caps at 200 entries and clears on hub restart. For the hub's authoritative, complete, restart-surviving change log, call hub_list_device_events with no deviceId (location-event mode).

### hub_create_connector

For Number/Decimal vars, Hubitat shows a connector-type chooser (Dimmer/Variable/etc.); pass `connectorType` to pick, default `Variable`. For String/Boolean/DateTime vars, the chooser is skipped. The full Number/Decimal `connectorType` set is: Dimmer, Variable, Volume, ColorTemp, Humidity, Illuminance.

## Dashboards

Reference for the dashboard tools (hub_list_dashboards, hub_get_dashboard, hub_create_dashboard, hub_update_dashboard, hub_delete_dashboard, hub_clone_dashboard). These cover TWO kinds of dashboard, distinguished by the `type` field every tool reports:

- **Easy Dashboard** (`type: "easy"`) — the modern touch-friendly device dashboard: a device list plus tile toggles, navigation, theme, and optional PINs. Config is replaced wholesale.
- **Legacy Hubitat® Dashboard** (`type: "legacy"`) — the classic, richly-customizable dashboard app: an explicit tile grid you lay out yourself (each tile's position, size, template, plus grid colors and fonts). Edited via a full layout replace or granular tile ops.

Per-tool details below.

### hub_list_dashboards

Read-only; each entry carries id, name, and `type` ('easy' or 'legacy'). Easy entries also include tile/theme config. Resolves the dashboard token automatically, so no pinToken is normally needed.

### hub_get_dashboard

Read-only. For an Easy Dashboard: tiles, navigation, devices, and PINs. For a legacy dashboard: its authorized `deviceIds` plus a nested `layout` object (see "Legacy layout shape" below). Read before the wholesale `hub_update_dashboard` and pass its output straight back. A read that partly fails returns `partial: true` with a note — the missing fields are unavailable, not defaulted.

### hub_create_dashboard

Write op. `type` selects the kind: `easy` (default) or `legacy`.

- **Easy** — needs >=1 device. Tiles default off; theme defaults to `legacy` (the theme name, unrelated to the dashboard kind).

  **`options` (optional config object):**
  - `showModeTile`, `showClockTile`, `showCalendarTile`, `showHSMTile` (bool)
  - `showEdit`, `showNavigation`, `showTutorial` (bool)
  - `navigationSelection`
  - `theme` — one of `legacy` | `light` | `dark` | `auto`
  - `dashboardPin`
  - `hsmPin`
- **Legacy** (`type: "legacy"`) — creates the dashboard with an EMPTY layout (no tiles). `name` is required; `deviceIds` is OPTIONAL and sets the dashboard's authorized-device list (NOT tiles). The `options` arg is REJECTED (Easy-only) — a legacy dashboard's look lives in its layout, set via `hub_update_dashboard` after creation. Requires the built-in "Hubitat® Dashboard" app to be installed.

### hub_update_dashboard

Update by id; the behavior depends on the dashboard's kind.

**Easy Dashboard** — wholesale config replace:
- **Read `hub_get_dashboard` first** and pass the FULL config back; this is a wholesale replace, not a partial patch. `name` and `deviceIds` (>=1) are required; any omitted field (PINs included) reverts to its default.
- `options`: same keys as `hub_create_dashboard.options`.
- Passing any legacy-only arg (`layout`, `setOptions`, `addTiles`, `updateTiles`, `removeTileIds`) at an Easy Dashboard is rejected.

**Legacy Hubitat® Dashboard** — edit the label, devices, and/or layout:
- `name` — renames the dashboard's app label.
- `deviceIds` — wholesale-replaces the authorized-device list.
- Layout edits are EITHER `layout` OR the granular ops, never both:
  - `layout` — a full layout object (as returned by `hub_get_dashboard`), replaced wholesale.
  - Granular ops — `removeTileIds`, `updateTiles`, `addTiles`, `setOptions`. They apply in that fixed order in ONE save: removals → updates → adds → options. All validation runs before the save, so a bad op leaves the layout untouched.
- Retry-safe semantics: `removeTileIds` skips an id that is already gone (with a warning), and `addTiles` skips a tile identical to an existing one (with a warning), so a retried call cannot stack duplicates. An `updateTiles` entry for an unknown tile id throws.
- The `options` arg does NOT apply to legacy — pass grid/color/font fields via `setOptions` instead.
- Result: `applied` (the ops that ran), `tileCount`, the saved `layout` echo, and any `warnings`.

**Legacy layout shape:**
- Top-level fields: `cols`, `rows`, `colWidth`, `rowHeight`, `gridGap`, `bgColor`, `iconSize`, `fontSize`, `customColors`, `roundedCorners`, `hideLabels`, and `tiles`. `setOptions` merges any of the top-level fields (the `tiles` and `name` keys are rejected there).
- Each tile: `id` (integer, auto-assigned max+1 on add), `template`, `device` (a device id), `col`, `row` (1-indexed, top-left cell is col 1 / row 1), `colSpan`, `rowSpan` (default 1), and optional `templateExtra`. `addTiles` requires `template`, `col`, and `row`.
- **Device authorization:** a tile's `device` must be in the dashboard's authorized `deviceIds` or the tile renders empty. Adds/updates referencing an unauthorized device still apply but surface a warning; authorize it via `deviceIds`.
- Tile `template` names: acceleration, attribute, battery, bulb, bulb-color, buttons, carbon-dioxide, carbon-monoxide, clock, clock-analog, clock-date, contact, dashboard, date, dimmer, door, door-control, energy, fan, garage, garage-control, generic, hsm, humidity, illuminance, images, level-step, level-vertical, links, lock, mode, momentary, motion, multi, music-player, outlet, power, presence, relay, scene, shades, shock, smoke, switches, temperature, texttile, thermostat, valve, variable-bool, variable-date, variable-decimal, variable-number, variable-string, variable-time, video-player, volume, water, weather, window.
- **localAccess caveat:** a legacy dashboard with 'Allow LAN access' disabled can block its layout endpoint; reads/writes then report the layout as unavailable and note the LAN-access setting.

### hub_delete_dashboard

Devices are NOT deleted. Write op; needs `confirm=true` + a backup within 24h.

- `confirm` (param) — Confirms a recent backup + user approval.
- A legacy dashboard is removed through the classic force-delete (the Easy `/dashboard/delete` endpoint is a no-op for it); removal is confirmed by effect and the result carries its `type`.

### hub_clone_dashboard

Write op; clone-by-value (the source is never touched).

- **Easy** — copies the source's config into a new dashboard (theme may default).
- **Legacy** — creates a new legacy dashboard with the same authorized devices, then copies the source's layout into it wholesale.

## Bundles

Reference for the bundle tools (hub_install_bundle, hub_list_bundles, hub_delete_bundle, hub_export_bundle). A bundle is a packaged .zip of apps, drivers, and/or libraries.

### hub_install_bundle

- **Verify the install** afterward with hub_list_libraries / hub_get_source.
- **Endpoint routing:** uses /bundle2/uploadZipFromUrl on firmware >= 2.3.8.108, else the legacy /bundle/uploadZipFromUrl (the chosen path is also surfaced in the result's `endpoint` field).

### hub_list_bundles

Each entry: id, name, namespace, a private flag, and a `contains` summary of the apps/drivers/libraries the bundle delivers.

### hub_export_bundle

`saveAs` filename sanitization: `.zip` is appended if missing, and non-filename characters are replaced with `_`. The result returns the final `fileName`.

## Rooms

Reference for the room tools (hub_list_rooms, hub_get_room, hub_create_room, hub_update_room, hub_delete_room). hub_delete_room's destructive behaviour is under "Destructive Write Tools".

### hub_get_room

A device the MCP server cannot reach is returned with `accessible=false` and no `currentStates` (label "(device not accessible via MCP)").

### hub_create_room

To move EXISTING devices into an existing room, set each device's room via hub_update_device -- do NOT create a room for that.

### hub_update_room

Renaming a room preserves device assignments, but may require updating automations/dashboards that reference the room by name.

## Slow ops (opToken recovery + in_progress resume)

Surfaced via `hub_get_tool_guide(section='slow_ops')`. A slow write can outlive its transport — the cloud relay severs calls at a fixed ceiling, and an MCP client's own request timeout can kill a long LAN call. Either way the hub still runs the operation to completion and commits it, but the RESPONSE is lost and the client sees an opaque transport error (a gateway/timeout error). RE-RUNNING THE CALL IS THE WRONG RECOVERY: it double-commits the write. Recovery is always a POLL, and the write tool itself is the poll.

### Idempotency token (`opToken`): pass one, and re-issue with the SAME one

EVERY tool — reads included — accepts an optional `opToken` the caller invents (8-128 chars of `A-Za-z0-9._-`); the known-slow class advertises it in its schemas (`hub_set_rule`, `hub_set_native_app`, the code save/update tools, `hub_install_bundle`, `hub_update_package`, `hub_create_backup`, `hub_restore_backup`, `hub_delete_variable`). The server records it before running the call and buffers the terminal result under it on completion. A read cannot double-commit, but an expensive read that outlives its transport still completes and buffers, so the tokened re-issue serves the buffered result instead of re-running the work. Tokens are per-call nonces: concurrent calls with different tokens never interfere.

If the response is lost, do NOT re-run the operation and do NOT invent a fresh token. Re-issue the SAME tool call with the SAME `opToken` — the token alone is enough (e.g. `{tool: "hub_update_package", opToken: "<yours>"}` with no other arguments). The server answers from the token record without running anything twice:

- `status: "running"` — still executing; poll again shortly by re-issuing the same tokened call (the write commits even though the response dropped).
- `replayed: true` — it finished; this IS the original buffered result (including `isError` if that attempt failed).
- `status: "unknown"` (returned only to a token-only poll) — no operation with this token ever started; the original call never arrived. Re-issue the ORIGINAL call (full arguments) with this same token.
- `status: "indeterminate"` — the operation completed here but its buffered result is gone (buffering failed, or swept opportunistically once older than ~24h; the sweep runs on later tokened calls, so expiry is not prompt). Do NOT re-issue blindly; verify current state via reads first. Only `unknown` means the call never arrived.

A token is SPENT once its operation completes — errors included; to retry with corrected arguments, invent a FRESH token. A replayed result whose `status` is `in_progress` carries `replayNote`: it is the original paused envelope, not new progress — a spent token cannot drive a resume; re-issue the remaining work with a fresh token.

### hub_update_package: never re-run on a timeout

The package deploy is monolithic (it cannot checkpoint-pause) and takes minutes — it is the write most likely to outlive a client timeout, and a re-run repeats the WHOLE bundle+apps repair on a hub that is already mid-deploy. On any transport error or timeout: (1) poll with the same `opToken` (token-only re-issue, as above); (2) the hub also refuses a concurrent second deploy outright while one is in flight; (3) `hub_get_info`'s `lastSelfDeploy` is the done-signal — its `at` flipping fresh (check `ageMs`) means the self-app leg ran, and `success`/`error` carry the outcome even if every response was lost.

### in_progress resume (multi-step writes only)

`hub_set_rule` / `hub_set_native_app` multi-step edits pause BETWEEN steps once the time budget for the request's transport is reached and return a success-shaped `status: "in_progress"` envelope — completed steps are already committed. A paused `walkStep(operation='drive')` returns `pausedAtStep`, `stepsRemaining`, and `page` (resume the drive with `steps = stepsRemaining` and that `page`); a paused patch edit returns `patchResults` plus `patchesRemaining` (resume the edit with `patches = patchesRemaining`; the rule finalize/`updateRule` runs when the remaining patches complete). Attach the same `opToken` on a resume only if the original call carried none, else use a fresh token. Once the budget is spent, a walkStep op also SHEDS its trailing health probe (the result's health carries skipped: true) so the committed op returns under the transport ceiling -- verify via hub_get_rule_health when you see it; an UNREADABLE probe (transient fetch failure) never fails committed work, only positive evidence of breakage does. The budget is per-transport: the advanced `relayBudgetMs` setting for cloud-relay requests (default 8000 ms, 0 disables) and `lanBudgetMs` for LAN requests (default 0 = off; set it just under the MCP client's request timeout). With the LAN knob unset, LAN behaviour is unchanged.
