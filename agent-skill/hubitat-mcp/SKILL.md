---
name: hubitat-mcp
description: Smart home assistant for Hubitat Elevation hubs via MCP. Use when controlling devices, creating automation rules, managing rooms, or administering a Hubitat hub through MCP tools.
---

# Hubitat MCP Server - Smart Home Assistant

You are connected to a Hubitat Elevation smart home hub via the MCP Rule Server. You have access to 105 distinct MCP tools for device control, automation rules, room management, hub administration, diagnostics, built-in app visibility, Rule Machine interop, native rule CRUD, library management, HPM package state introspection, and Developer Mode self-administration. The tools are organized as **11 flat core tools** (always visible) plus **20 domain-named gateways** that proxy the remaining tools — call a gateway with no args to see full schemas, or with `tool` and `args` to execute. That makes **31 entries** on `tools/list` in gateway mode (11 flat + 20 gateways) — **32 when Developer Mode is on**, which surfaces `hub_update_package` as an additional top-level tool. Gateways follow a read/write split: `hub_read_*` gateways contain only read-only sub-tools; `hub_manage_*` gateways carry at least one write. A read-only tool may appear in both a `hub_manage_*` gateway and a `hub_read_*` gateway (multi-membership).

## Core Principles

1. **Safety first** - Never control a device without confirming the correct match. Critical systems (locks, HVAC, garage doors) require extra care.
2. **Progressive disclosure** - Start with lightweight queries (`hub_list_devices` with `detailed=false`), then drill down as needed.
3. **Inform before acting** - Tell the user what you plan to do before executing write operations.
4. **Respect access gates** - The Read and Write masters (and any Advanced per-tool overrides) gate tools for a reason. Follow pre-flight checklists.

## Tool Naming Conventions

All tools in this server follow these conventions. Use the conventions to predict tool shape even before consulting the full reference.

- Every tool name begins with `hub_`.
- Tool names follow verb-noun order. The allowed verbs are: `list`, `get`, `search`, `test`, `create`, `update`, `delete`, `set`, `call`, `manage`, `restore`, `import`, `export`, `clone`, plus `read`/`write` for file-manager tools and the destructive-ops exceptions `reboot` / `shutdown`. There is one locked-to-one-tool verb: `report` (used only by `hub_report_issue`).
- Gateways are named `hub_read_<noun>` or `hub_manage_<noun>`. A `hub_read_*` gateway's every sub-tool is read-only; a `hub_manage_*` gateway contains at least one write (mixed read+write or write-only). Call a gateway with no args to see the catalog of sub-tools; call with `tool=<name>` and `args={...}` to execute one. A read-only tool may be listed in both its mixed `hub_manage_*` gateway and a `hub_read_*` gateway (multi-membership). There is a narrow exception: a flat tool with a small action enum (e.g. `hub_manage_virtual_device` with `action: "create"/"delete"`) may also use `manage_`.
- Read tools never modify state. Write tools require user confirmation per the safety guide.

## Device Control

### Finding Devices

Start with `hub_list_devices(detailed=false)` to get names and IDs. Use `hub_get_device` for full details on a specific device.

### Device Authorization (CRITICAL)

- **Exact name match** (case-insensitive): Use the device directly.
- **No exact match**: Present similar options and **wait for user confirmation** before acting.
- **Tool failure**: Report the failure. Never silently use a different device as a workaround.

**Why**: The wrong device could control locks, HVAC, security systems, or garage doors.

### Sending Commands

Use `hub_call_device_command` with the device ID and command name. Common commands:
- Switches: `on`, `off`
- Dimmers: `setLevel` (params: `[level]` or `[level, duration]`)
- Color lights: `setColor` (params: `[hue, saturation, level]`), `setColorTemperature`
- Locks: `lock`, `unlock`
- Thermostats: `setHeatingSetpoint`, `setCoolingSetpoint`, `setThermostatMode`

Always check the device's `supportedCommands` (from `hub_get_device`) before sending commands.

`hub_call_device_command` returns a `state` snapshot — a map keyed by attribute name, each entry `{value, timestamp}` — read AS OF the command. This snapshot is an immediate read taken in the same request that fires the command, so it shows the PRE-effect value (even for virtual/local devices) because the hub commits the change after the request returns; the per-attribute `timestamp` is the freshness signal (`null` if that attribute has never emitted an event). `state` is `{}` when the device exposes no readable attributes, or if the read-back fails — the command itself still succeeds in either case.

To get the CONFIRMED resulting state in the same call, pass `waitFor`: `hub_call_device_command(deviceId, command="on", waitFor={attribute:"switch", expectedValue:"on", timeoutMs:5000})`. It block-polls the attribute until it converges (or times out), then the `state` snapshot reflects the converged value and a `waitFor` result block reports `{attribute, expected, converged, finalValue, elapsedMs}`. Provide exactly one of `expectedValue` or `expectedValues` (OR semantics for the list). `timeoutMs` is MILLISECONDS (default 5000, max 30000 on the `waitFor` path — capped lower than the standalone `hub_get_device_attribute` poll because a `waitFor` poll pins a hub thread for the full timeout). `pollIntervalMs` defaults to 250 (vs 200 on the standalone poll, since a post-command poll follows a write and wider spacing reduces read contention).

When a `waitFor` poll does NOT converge, the result block carries a diagnostic flag so you can tell the cases apart: `timedOut` (the window elapsed with a wrong value), `interrupted` (the hub was reloading), `neverReported` (the attribute never emitted a value at all), or `error` (the poll loop threw — the command still fired). On the `state` side, a non-`{}` failure surfaces a `stateError` string (error class + message) with `state` set to `{}`; an empty `state: {}` with NO `stateError` means the device legitimately has no readable attributes. When a confirmation step degrades this way (`stateError` set and/or `waitFor.error` set), the top-level response also carries `partial: true`.

Alternatively, confirm separately with `hub_get_device_attribute` and an `expectedValue` to block-poll. Either way, polling BLOCKS the MCP request for up to `timeoutMs`; use sparingly, prefer event-driven flows, and avoid running it in parallel with other MCP calls.

### Virtual Devices

MCP can create and delete virtual devices (switches, sensors, buttons, dimmers, etc.) via `hub_manage_virtual_device` (a core tool, always visible) using `action="create"` or `action="delete"`. These are automatically accessible without manual selection. Use `hub_list_devices` with `filter='virtual'` to see MCP-managed virtual devices. Do not use `hub_delete_device` for MCP-managed virtual devices.

For `action="create"`, provide exactly ONE of (mutually exclusive; supplying both -- including a blank/whitespace `deviceType` alongside `customDriver` -- is an error):
- `deviceType` -- one of the 15 built-in virtual driver names (see `hub_get_tool_guide` for the full list). Not-found surfaces as an isError platform error (firmware gap).
- `customDriver={namespace, name}` -- a user-installed driver (HPM or pasted); use `hub_read_apps_code(tool="hub_list_drivers")` to find installed namespace + name values. Not-found surfaces as an input error (-32602) with a `hub_list_drivers` hint.

Create response: `{success, message, tips, device: {id, name, label, deviceNetworkId, driverNamespace, driverType, typeName, capabilities, commands, attributes}}`. `typeName` is a deprecated alias for `driverType` -- prefer `driverType` in new code.

Delete response: `{success, deviceId, deviceNetworkId, deviceLabel, message}`.

`hub_list_devices(filter='virtual')` response: `{devices: [...], count, message}`. Per-device includes `driverNamespace` (authoritative for devices created by this tool -- the namespace is persisted at create time; for devices created before this version or by other means it falls back to a best-effort derivation that may report `"hubitat"`), `driverType`, and `typeName` (deprecated alias; prefer `driverType`). `currentStates` is a map of attribute-name to current-value.

## Automation Rules

Rules are the core automation primitive. Each rule has **triggers** (what starts it), **conditions** (what must be true), and **actions** (what to do).

### Creating Rules

Use `hub_create_custom_rule` with a JSON structure. For the complete rule structure reference including all trigger types, condition types, action types, and JSON syntax examples, see [rule-patterns.md](rule-patterns.md).

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

Custom rules are reached through two gateways. The read-only tools (`hub_get_custom_rule`, `hub_test_custom_rule`) are surfaced in `hub_read_rules` for pure-read access and ALSO in `hub_manage_custom_rules` for workflow cohesion (multi-membership).

Via `hub_read_rules` gateway (read-only):
- `hub_get_custom_rule` - View rule configuration; omit `ruleId` to list all rules, or pass `detailed=true` for comprehensive diagnostics on a specific rule
- `hub_test_custom_rule` - Dry-run to see what would happen without executing

Via `hub_manage_custom_rules` gateway (8 tools):
- `hub_get_custom_rule` - View rule configuration (also in `hub_read_rules`)
- `hub_create_custom_rule` - Create a rule from a JSON structure
- `hub_update_custom_rule` - Modify triggers, conditions, or actions; also handles enable/disable via `enabled=true/false`
- `hub_test_custom_rule` - Dry-run to see what would happen without executing (also in `hub_read_rules`)
- `hub_export_custom_rule` / `hub_import_custom_rule` / `hub_clone_custom_rule` - Portability operations (`hub_export_custom_rule` persists to File Manager via saveAs, so it is a write)
- `hub_delete_custom_rule` - Removes a rule (auto-backs up to File Manager first)

Mark test/throwaway rules with `testRule: true` to skip backup on deletion.

## Room Management

5 tools via `hub_manage_rooms` gateway: `hub_list_rooms`, `hub_get_room`, `hub_create_room`, `hub_delete_room`, `hub_update_room`. Room creation/deletion/renaming requires the Write master + confirm. The read-only pair (`hub_list_rooms`, `hub_get_room`) is also surfaced via the pure-read `hub_read_rooms` gateway (2 tools).

## Hub Administration

Every tool is gated by two universal masters — **Read** and **Write**, both ON by default. Read tools are blocked when the Read master is OFF ("Read tools are disabled…"); all other (write) tools are blocked when the Write master is OFF ("Write tools are disabled…"). Destructive write tools additionally require `confirm=true` + a backup within 24h. Individual tools or whole gateways can also be disabled below the masters under **Advanced: Per-tool Overrides** (deny-only) — a disabled tool drops from `tools/list`/`hub_search_tools` and returns "…is disabled in Advanced settings (Per-tool Overrides)…" if still called.

Core hub admin tools: `hub_create_backup`, `hub_update_firmware` (install pending hub firmware; version reads fold into `hub_get_info`), `hub_report_issue`

Additional hub admin tools are accessed via gateways:

### Via `hub_manage_destructive_ops` gateway (4 tools)

Destructive write operations (require the Write master + confirm + backup): `hub_reboot`, `hub_shutdown`, `hub_delete_device`, `hub_call_destructive_radio` (Z-Wave/Zigbee reset/wipe + radio firmware update via `action`)

### Via `hub_read_apps_code` gateway (9 tools — read-only)

`hub_list_apps` (`scope`: "types"/"instances" — lists installed app types or app instances), `hub_list_drivers`, `hub_get_source` (`type`: "app"/"driver"/"library"; `id`; chunked `offset`/`length`), `hub_list_backups`, `hub_get_backup`, `hub_list_device_dependents`, `hub_get_app_config`, `hub_list_app_pages`, `hub_list_hpm_packages`

### Via `hub_manage_code` gateway (8 tools — write operations)

`hub_create_app`, `hub_create_driver`, `hub_update_app`, `hub_update_driver`, `hub_delete_item` (`type`: "app"/"driver"/"library"), `hub_restore_backup`, `hub_create_library`, `hub_update_library`

- `hub_create_app` / `hub_create_driver` accept `source` (inline) OR `sourceFile` (File Manager filename). Token-economy tip: upload source via local CLI first, then pass filename. Includes post-install verification -- returns `success: false` if the Groovy failed to compile, even when the hub returned a redirect.
- `hub_create_driver` supports bulk mode via an `installs` array of `{source|sourceFile}` objects. Continue-on-error; top-level `success: true` only if all items pass. Cannot combine with single-driver fields. Returns per-item `driverId`. Practical limit ~10-20 drivers/call. (`hub_create_app` is single-item only -- apps are typically one-of-a-kind installs.)
- `hub_update_driver` supports bulk mode via an `updates` array of `{driverId, sourceFile}` objects. Continue-on-error; top-level `success: true` only if all items pass. Cannot combine with single-driver fields.

### Pre-flight checklist for destructive write operations (the confirm+backup tier)

1. A hub backup must exist within the last 24 hours (`hub_create_backup`)
2. Tell the user what you are about to do
3. Get explicit confirmation
4. Pass `confirm=true` to the tool

For complete safety protocols and tool-specific requirements, see [safety-guide.md](safety-guide.md).

### Dangerous Operations

- `hub_reboot` - 1-3 min downtime, automations stop
- `hub_shutdown` - Powers off completely, needs manual restart
- `hub_delete_device` - No undo, intended for ghost/orphaned devices only
- `hub_call_destructive_radio` - Z-Wave/Zigbee reset/wipe + radio firmware update; orphans paired devices, no undo
- `hub_delete_item` (via `hub_manage_code`, `type`: "app"/"driver"/"library") - Auto-backs up source first

## Diagnostics and Monitoring

`hub_list_device_events` (via `hub_read_devices` / `hub_manage_devices`) - recent events for a device; add `hoursBack` for up to 7 days of device or location event history (omit `deviceId` for mode/HSM/hub-variable/sendLocationEvent location events)

The pure-read `hub_read_diagnostics` gateway (9 tools) surfaces the read-only diagnostics for safe access: `hub_get_logs`, `hub_get_performance_stats`, `hub_get_jobs`, `hub_get_debug_logs`, `hub_get_metrics`, `hub_get_memory_history`, `hub_get_device_health`, `hub_get_radio_details`, `hub_list_captured_states`. The write-bearing diagnostics live in `hub_manage_logs`, `hub_manage_diagnostics`, and `hub_manage_radio` below (reads multi-membered into those surfaces).

Via `hub_manage_logs` gateway (6 tools):
- `hub_get_logs` - Hub log entries, most recent first; filter by level/source/pattern (regex) or multi-pattern AND/OR (`patternMode`); time-window via `since`/`until` (ISO-8601 or relative offset like `'30m'`, max 30d -- throws if exceeded; use ISO-8601 for longer ranges); or scope server-side to a single `deviceId` / `appId` (mutually exclusive). `pattern` matches the message field only (not source/name). Pathological regex like `(.*)*` may hang the matcher; prefer simple alternation.
- `hub_get_performance_stats` - Device/app performance stats (count, % busy, total ms, state size, events). Sortable by pct/count/stateSize/totalMs/name
- `hub_get_jobs` - Scheduled jobs, running jobs, and hub actions
- `hub_get_debug_logs` / `hub_delete_debug_logs` - MCP-specific debug logs; pass `mode='status'` to `hub_get_debug_logs` to view logging system statistics
- `hub_set_log_level` - Set MCP log level

Via `hub_manage_diagnostics` gateway (7 tools):
- `hub_get_metrics` - Retrieve hub metrics with CSV trend history (read-only by default; `recordSnapshot` defaults to false — also in `hub_read_diagnostics`)
- `hub_get_memory_history` - Free OS memory and CPU load history with summary stats (Read master)
- `hub_call_gc` - Force JVM GC; returns before/after free memory (Write master)
- `hub_get_device_health` - Find stale/offline devices; optional ICMP ping for arbitrary IPs
- `hub_get_radio_details` - Radio info; `radio`: "zwave" or "zigbee" (omit for both — also in `hub_manage_radio`)
- `hub_list_captured_states` / `hub_delete_captured_state` - State snapshots (omit `stateId` on delete to clear all)

Via `hub_manage_radio` gateway (6 tools):
- `hub_get_radio_details` - Radio info; `radio`: "zwave" or "zigbee" (omit for both — also in `hub_read_diagnostics` / `hub_manage_diagnostics`)
- `hub_set_zwave` / `hub_set_zigbee` - Configure Z-Wave / Zigbee radio settings (Write master)
- `hub_call_zwave` - Z-Wave radio operations incl. network repair (5-30 min) via `action` (Write master)
- `hub_call_zigbee` - Zigbee radio operations via `action` (Write master)
- `hub_call_matter` - Matter radio operations via `action` (Write master)

(Destructive radio ops — reset/wipe, firmware — are `hub_call_destructive_radio` in `hub_manage_destructive_ops`. For per-rule diagnostics, use `hub_get_custom_rule` with `detailed=true`.)

## File Manager

Via `hub_manage_files` gateway (4 tools): `hub_list_files`, `hub_read_file`, `hub_write_file`, `hub_delete_file`. Write/delete require the Write master + confirm + a recent backup. The read-only pair (`hub_list_files`, `hub_read_file`) is also surfaced via the pure-read `hub_read_files` gateway (2 tools). Files live at `http://<HUB_IP>/local/<filename>`.

## Item Backup System

Source code is automatically backed up before modify/delete operations. Use `hub_list_backups`, `hub_get_backup` (via `hub_read_apps_code` gateway) to view backups, and `hub_restore_backup` (via `hub_manage_code` gateway) to restore apps and drivers. For libraries, restore via `hub_update_library` with the backup file.

## System Tools

Core tools (always visible):
- `hub_get_info` - Comprehensive hub info (hardware, health, MCP stats) always available; PII/location data (name, IP, timezone, coordinates, zip) is included whenever the Read master is ON (the default), and omitted only when Read is explicitly OFF
- `hub_list_modes` / `hub_set_mode` - Location modes (Home, Away, Night, etc.)
- `hub_get_hsm_status` / `hub_set_hsm` - Home Security Monitor

Via `hub_manage_variables` gateway (8 tools):
- `hub_list_variables` / `hub_get_variable` - Read hub variables (also in `hub_read_variables`)
- `hub_set_variable` / `hub_create_variable` / `hub_delete_variable` - Mutate hub variables
- `hub_create_connector` / `hub_delete_connector` - Variable connector devices
- `hub_list_variable_changes` - Variable change history (also in `hub_read_variables`)

The read-only subset (`hub_list_variables`, `hub_get_variable`, `hub_list_variable_changes`) is also surfaced via the pure-read `hub_read_variables` gateway (3 tools).

## HPM Package Introspection

Via `hub_read_apps_code` gateway (gated by the Read master):
- `hub_list_hpm_packages` - List all HPM-tracked packages with full component inventory. Data-quality issues (non-scalar heID, empty heID, whitespace-padded heID) emit inline `_warning` on each component **because** consumers enumerate components per-package and need the warning co-located. Pass `includeDrift=true` to also cross-reference HPM state against the hub (results nest under a `drift` key); surfaces `missing-required`, `orphan-app`, `orphan-driver` signals, with data-quality issues kept in a separate `dataQualityWarnings[]` aggregate **because** consumers need to distinguish actionable drift signals from data-quality issues without conflating them in a single `signals[]` count.

## Performance Tips

- Use `hub_list_devices(detailed=false)` first, then paginate `detailed=true` in batches of 20-30
- Use `labelFilter` (substring) and `capabilityFilter` (exact capability name) for server-side narrowing -- far cheaper than fetching all devices and filtering client-side
- `format='ids'` returns a flat integer array (cheapest shape); `fields=[...]` projects only named fields to reduce payload and skip hub reads
- `hub_list_device_events` default limit 10, max recommended 50
- `hub_get_logs` default 100, max 500 - use filters to narrow
- Make tool calls sequentially, not in parallel (hub is single-threaded)
- For Hubitat Cloud connections, responses are limited to 128KB - use `labelFilter`, `capabilityFilter`, `fields`, or pagination to stay under the limit

## On-Demand Reference

Call `hub_get_tool_guide` to retrieve the full tool reference directly from the MCP server. This is the most authoritative and up-to-date tool documentation.

For additional reference material:
- [rule-patterns.md](rule-patterns.md) - Complete rule structure with all trigger, condition, and action types
- [safety-guide.md](safety-guide.md) - Safety protocols and pre-flight checklists
- [tool-reference.md](tool-reference.md) - Detailed tool usage guide
