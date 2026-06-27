# Bot Acceptance Test (BAT) Suite — v2

Updated for the installed-apps + Rule Machine interop + native CRUD + library management + HPM package state architecture, then the issue #105 PR1A hub_ rename + consolidation, then the PR1B read/write split, then the issue #259 item #9 Easy Dashboard CRUD (13 flat core + 23 gateways = 36 on tools/list, 117 total distinct tools).

Comprehensive test scenarios for the Hubitat MCP Rule Server. Modeled after ha-mcp's BAT framework.

> **Supplement**: see [`tests/BAT-rm-native-crud.md`](./BAT-rm-native-crud.md) for the native-RM CRUD suite (T300-T399) -- acceptance gate for the native CRUD tools (`hub_set_rule`, `hub_set_native_app`, `hub_delete_native_app`, `hub_get_rule_health`). Those tools are shipped; all scenarios in that file should pass against the current codebase.

Each test is a JSON scenario with optional `setup_prompt`, required `test_prompt`, and optional `teardown_prompt`. Run each prompt in the same AI session (setup → test → teardown). Each TEST SCENARIO starts a fresh session.

## Safety Rules

- **All tests use the `BAT` prefix** for artifacts (rules, devices, rooms, files, variables) for easy identification and cleanup
- **All rules are marked `testRule: true`** to skip backup on deletion — this applies to `hub_create_custom_rule`-created rules only; native rules created via `hub_set_rule` use the `hub_delete_native_app` teardown path instead (no `testRule` flag)
- **Tests only create/modify/delete test artifacts** — never touch existing production devices, rules, or hub settings
- **Device commands only target BAT-created virtual devices** — never command physical devices
- **Destructive hub operations are excluded** — no reboot, shutdown, Z-Wave repair, app/driver install/update/delete, or real device deletion
- **Teardown prompts are explicit** about what to clean up

## Test Format

```json
{
  "setup_prompt": "Setup state needed for the test",
  "test_prompt": "The actual test prompt — the AI should accomplish this",
  "teardown_prompt": "Cleanup after the test"
}
```

### Pass/Fail Criteria

- **Pass**: AI completes the task, calls the expected tools, returns correct information
- **Fail**: AI cannot find the right tool, calls wrong tool, errors out, or returns incorrect info
- **Partial**: AI accomplishes the goal but takes an inefficient path (extra tool calls, unnecessary catalog lookups)

### Metrics to Track

| Metric | Description |
|--------|-------------|
| `tools_called` | Which MCP tools were invoked (in order) |
| `tool_success` | How many succeeded vs failed |
| `num_turns` | How many agentic turns the AI needed |
| `gateway_used` | Whether a gateway was used (and which one) — v0.8.0 only |
| `catalog_fetched` | Whether the AI fetched a gateway catalog first — v0.8.0 only |
| `duration_ms` | Total wall-clock time |

---

## Section 1: Core Tool Tests

These tools appear directly on `tools/list` in both v0.7.7 (all 74 tools) and v0.8.0 (11 flat core tools). Every AI should find and use them without difficulty.

### T01 — hub_list_devices (basic)

```json
{
  "test_prompt": "List all my smart home devices. Just give me a summary count and the first 5 device names."
}
```

**Expected**: Calls `hub_list_devices`. Returns device count and names.

### T02 — hub_list_devices (pagination)

```json
{
  "test_prompt": "List all my devices with full details. Use pagination with a limit of 10 devices per page. Show me just the first page."
}
```

**Expected**: Calls `hub_list_devices` with `detailed=true, limit=10`.

### T02b — hub_list_devices (server-side filtering)

```json
{
  "test_prompt": "List only my devices that have the Switch capability. Show just their IDs and labels."
}
```

**Expected**: Calls `hub_list_devices` with `capabilityFilter='Switch'` and `fields=['id','label']` (or equivalent). Returns a subset of devices without fetching all. Does NOT fetch all devices and filter client-side.

```json
{
  "test_prompt": "How many devices do I have in the kitchen? List just their names."
}
```

**Expected**: Calls `hub_list_devices` with `labelFilter='kitchen'` (or similar). Returns only kitchen-named devices without downloading the full device list.

```json
{
  "test_prompt": "Give me just the device IDs for all my devices — the smallest possible response."
}
```

**Expected**: Calls `hub_list_devices` with `format='ids'`. Response is `deviceIds: [...]` flat array, not full device objects.

### T03 — hub_get_device

```json
{
  "setup_prompt": "List my devices briefly so I can see what's available.",
  "test_prompt": "Get the full details of the first device you found — all attributes, commands, and capabilities."
}
```

**Expected**: Calls `hub_get_device` with a valid device ID. Returns attributes, commands, capabilities.

### T04 — hub_get_device_attribute

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT Attr Test'.",
  "test_prompt": "Check the current switch state of 'BAT Attr Test'. Is it on or off?",
  "teardown_prompt": "Delete the virtual device 'BAT Attr Test'."
}
```

**Expected**: Calls `hub_get_device_attribute` with device ID and `attribute=switch`.

### T05 — hub_call_device_command (on/off)

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT Command Test'.",
  "test_prompt": "Turn on the virtual switch 'BAT Command Test', then check its state to confirm it turned on.",
  "teardown_prompt": "Turn off 'BAT Command Test', then delete the virtual device."
}
```

**Expected**: Calls `hub_call_device_command` with `command=on`, then `hub_get_device_attribute` to confirm. The command response includes a `state` map keyed by attribute name (each entry its current value + a freshness timestamp; the device's attributes, no per-attribute auth filter). This snapshot is an IMMEDIATE read taken in the same request that fired the command, so it shows the PRE-effect value even for a virtual switch (the hub commits the change after the request returns) -- which is why the separate `hub_get_device_attribute` read is what confirms `switch=on`. The per-attribute timestamp is the freshness signal.

### T05b — hub_call_device_command with waitFor (confirmed resulting state)

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT WaitFor Test'.",
  "test_prompt": "Turn on the virtual switch 'BAT WaitFor Test', waiting until its switch attribute reads on, in a single call.",
  "teardown_prompt": "Turn off 'BAT WaitFor Test', then delete the virtual device."
}
```

**Expected**: Calls `hub_call_device_command` with `command=on` and a `waitFor={attribute:"switch", expectedValue:"on"}`. The response carries a `waitFor` block with `converged: true` and `finalValue: "on"` (a String, read from the device's live current-state list), and -- because the snapshot is taken AFTER the waitFor poll -- the `state` map's switch entry now reads `on` (the converged/resulting value, not the pre-effect one). On a clean run there is NO `partial` flag and NO `stateError`; a non-convergence would instead surface a diagnostic flag (`timedOut` / `interrupted` / `neverReported` / `error`), and a degraded confirmation step (failed snapshot or a poll-loop `error`) would add `partial: true`. The `waitFor` `timeoutMs` caps at 30000 (vs 60000 on the standalone `hub_get_device_attribute` poll) and `pollIntervalMs` defaults to 250. No separate `hub_get_device_attribute` read is needed to confirm.

### T06 — hub_call_device_command (setLevel)

```json
{
  "setup_prompt": "Create a virtual dimmer called 'BAT Dimmer Test'.",
  "test_prompt": "Set 'BAT Dimmer Test' to 50% brightness. Confirm the level.",
  "teardown_prompt": "Turn off 'BAT Dimmer Test', then delete the virtual device."
}
```

**Expected**: Calls `hub_call_device_command` with `command=setLevel, args=[50]`. Verifies with `hub_get_device_attribute`.

### T07 — hub_list_device_events

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT Events Test'. Turn it on, then off, then on again.",
  "test_prompt": "Show me the last 5 events for 'BAT Events Test'.",
  "teardown_prompt": "Delete the virtual device 'BAT Events Test'."
}
```

**Expected**: Calls `hub_list_device_events`. Returns recent on/off events.

### T07b — hub_get_device_attribute poll mode (basic match)

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT Poll Test'. Leave it in the off state.",
  "test_prompt": "Turn on 'BAT Poll Test', then poll its switch attribute until it reads 'on'. Use a 5-second timeout. Report whether the poll succeeded and how long it took.",
  "teardown_prompt": "Delete the virtual device 'BAT Poll Test'."
}
```

**Expected**: Calls `hub_call_device_command` (on), then `hub_get_device_attribute` (poll mode) with `attribute=switch, expectedValue="on", timeoutMs=5000`. Returns `success: true, timedOut: false`.

### T07c — hub_get_device_attribute poll mode (timeout path)

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT Poll Timeout'. Leave it off.",
  "test_prompt": "Poll the switch attribute of 'BAT Poll Timeout' waiting for it to read 'on', but only wait 500ms before giving up. Report the result including whether it timed out.",
  "teardown_prompt": "Delete the virtual device 'BAT Poll Timeout'."
}
```

**Expected**: Calls `hub_get_device_attribute` (poll mode) with `expectedValue="on", timeoutMs=500`. Device was never turned on, so returns `success: false, timedOut: true`.

### T07d — hub_get_device_attribute poll mode (expectedValues OR semantics)

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT Poll OR Test'. Leave it in whatever state it defaults to (off).",
  "test_prompt": "Poll the switch attribute of 'BAT Poll OR Test' waiting for it to be in EITHER the 'on' or 'off' state. Use expectedValues=['on','off'] and a 2-second timeout. Report whether the poll succeeded.",
  "teardown_prompt": "Delete the virtual device 'BAT Poll OR Test'."
}
```

**Expected**: Calls `hub_get_device_attribute` (poll mode) with `expectedValues=["on","off"], timeoutMs=2000`. Device is in one of those states by default, so returns `success: true, timedOut: false` on the first poll.

### T07e — hub_get_device_attribute poll mode (numeric comparator)

```json
{
  "setup_prompt": "Create a virtual temperature sensor called 'BAT Cmp Test' and set its temperature attribute to 73.",
  "test_prompt": "Poll the temperature attribute of 'BAT Cmp Test' and wait until it is GREATER THAN 72 degrees, with a 3-second timeout. Report whether it succeeded and the final value.",
  "teardown_prompt": "Delete the virtual device 'BAT Cmp Test'."
}
```

**Expected**: Calls `hub_get_device_attribute` (poll mode) with `attribute=temperature, comparator="gt", expectedValue="72", timeoutMs=3000`. The sensor reads 73 (> 72), so returns `success: true, timedOut: false` with `finalValue` of 73. (Using `comparator="between"` would instead require `expectedValues=[low, high]`; mixing a numeric comparator with `expectedValues` is rejected with an invalid-params error. A numeric comparator on a NON-numeric attribute, e.g. `gt` on a switch, times out with `nonNumericAttribute: true`.)

### T07g — hub_get_device_attribute poll mode (between range)

```json
{
  "setup_prompt": "Create a virtual temperature sensor called 'BAT Between Test' and set its temperature attribute to 70.",
  "test_prompt": "Poll the temperature attribute of 'BAT Between Test' and wait until it is BETWEEN 68 and 72 degrees inclusive, with a 3-second timeout. Report whether it succeeded and the final value.",
  "teardown_prompt": "Delete the virtual device 'BAT Between Test'."
}
```

**Expected**: Calls `hub_get_device_attribute` (poll mode) with `attribute=temperature, comparator="between", expectedValues=["68","72"], timeoutMs=3000`. The sensor reads 70 (inside the inclusive range), so returns `success: true, timedOut: false` with `finalValue` of 70. (`between` takes exactly two numeric bounds via `expectedValues` and rejects `expectedValue`.)

### T07h — hub_get_device_attribute poll mode (ne leaves a set)

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT NE Test' and turn it on.",
  "test_prompt": "Poll the switch attribute of 'BAT NE Test' waiting for it to be NOT 'off' (use comparator ne, expectedValue 'off', a 3-second timeout). Report whether it succeeded and the final value.",
  "teardown_prompt": "Delete the virtual device 'BAT NE Test'."
}
```

**Expected**: Calls `hub_get_device_attribute` (poll mode) with `attribute=switch, comparator="ne", expectedValue="off", timeoutMs=3000`. The switch reads `on` (not in the set), so `ne` converges immediately: `success: true, timedOut: false` with `finalValue` of `on`. (A null/never-reported value does NOT satisfy `ne`.)

### T07i — hub_get_device_attribute multi-device convergence (deviceIds + mode any/all)

```json
{
  "setup_prompt": "Create two virtual switches 'BAT Multi A' and 'BAT Multi B' and turn both on.",
  "test_prompt": "Wait until BOTH 'BAT Multi A' and 'BAT Multi B' have switch 'on' in a single call (poll both device IDs together, mode all, 5-second timeout). Then turn 'BAT Multi B' off and wait until EITHER one is 'on' (mode any). Report convergedCount for each.",
  "teardown_prompt": "Delete the virtual devices 'BAT Multi A' and 'BAT Multi B'."
}
```

**Expected**: Calls `hub_get_device_attribute` (poll mode) with `deviceIds=[idA, idB], attribute=switch, expectedValue="on", mode="all", timeoutMs=5000` -- both on, so it converges with `success: true, convergedCount: 2` and a compact per-device `devices` array (each `{deviceId, device, finalValue, matched}`), not full device objects. After B is turned off, the `mode="any"` call still converges on A: `success: true, convergedCount: 1` (an `all` call here would have timed out). `deviceIds` is mutually exclusive with `deviceId` (max 20 devices); `mode` is rejected with a single `deviceId`; a missing ID in the list is rejected naming which ID.

### T07f — hub_call_device_command waitFor with debounce (stableForMs)

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT Debounce Test' and turn it off.",
  "test_prompt": "Turn on 'BAT Debounce Test', and wait for the switch attribute to read 'on' and stay 'on' continuously for at least 500 milliseconds before confirming (use waitFor with stableForMs=500 and a 5-second timeout). Report whether it converged.",
  "teardown_prompt": "Delete the virtual device 'BAT Debounce Test'."
}
```

**Expected**: Calls `hub_call_device_command` with `command=on` and `waitFor={attribute:"switch", expectedValue:"on", stableForMs:500, timeoutMs:5000}`. A virtual switch holds `on` steadily, so the `waitFor` block reports `converged: true` after the 500ms stability window elapses (it does NOT converge on the very first matching poll). `stableForMs` must be less than `timeoutMs`; a `stableForMs >= timeoutMs` spec is rejected before the command fires.

### T08 — hub_get_custom_rule (list mode)

```json
{
  "test_prompt": "Show me all the automation rules. Tell me how many exist and their names and statuses."
}
```

**Expected**: Calls `hub_get_custom_rule` (list mode: omit ruleId). Returns rule count, names, enabled/disabled status.

### T09 — hub_get_custom_rule

```json
{
  "setup_prompt": "Create a test rule called 'BAT Get Rule' with a time trigger at 23:59 and a log action saying 'test'. Mark as test rule.",
  "test_prompt": "Show me the full configuration of the rule 'BAT Get Rule' — triggers, conditions, and actions.",
  "teardown_prompt": "Delete the rule 'BAT Get Rule'."
}
```

**Expected**: Calls `hub_get_custom_rule` with rule ID. Returns full structure.

### T10 — hub_create_custom_rule

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT Rule Device'.",
  "test_prompt": "Create a rule called 'BAT Create Test' that triggers at 23:59 and logs 'BAT test triggered'. Mark it as a test rule.",
  "teardown_prompt": "Delete the rule 'BAT Create Test'. Delete the virtual device 'BAT Rule Device'."
}
```

**Expected**: Calls `hub_create_custom_rule` with proper structure and `testRule=true`.

### T11 — hub_update_custom_rule

```json
{
  "setup_prompt": "Create a test rule called 'BAT Update Test' with a time trigger at 23:59 and a log action. Mark as test rule.",
  "test_prompt": "Update the rule 'BAT Update Test' to change the trigger time from 23:59 to 06:00.",
  "teardown_prompt": "Delete the rule 'BAT Update Test'."
}
```

**Expected**: Calls `hub_update_custom_rule` with modified trigger.

### T12 — hub_update_custom_rule enable/disable

```json
{
  "setup_prompt": "Create a test rule called 'BAT Toggle Test' with a time trigger at 23:59 and a log action. Mark as test rule.",
  "test_prompt": "Disable the rule 'BAT Toggle Test', confirm it's disabled, then re-enable it and confirm it's enabled again.",
  "teardown_prompt": "Delete the rule 'BAT Toggle Test'."
}
```

**Expected**: Calls `hub_update_custom_rule` with `enabled=false`, verifies, calls `hub_update_custom_rule` with `enabled=true`, verifies.

### T13 — hub_update_device (label)

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT Label Test'.",
  "test_prompt": "Change the label of 'BAT Label Test' to 'BAT Label Changed'. Then verify the change.",
  "teardown_prompt": "Delete the virtual device 'BAT Label Changed'."
}
```

**Expected**: Calls `hub_update_device` with `label` parameter. Verifies with `hub_get_device`.

### T13b — hub_update_device (hide from Home page)

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT ShowOnHome Test'.",
  "test_prompt": "Hide 'BAT ShowOnHome Test' from the hub Home page.",
  "teardown_prompt": "Delete the virtual device 'BAT ShowOnHome Test'."
}
```

**Expected**: Calls `hub_update_device` with `showOnHome=false`. Reports the device no longer shows on the Home page.

### T13c — hub_update_device (default status attribute)

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT DefaultState Test'.",
  "test_prompt": "Make 'switch' the attribute shown in the Status column for 'BAT DefaultState Test'.",
  "teardown_prompt": "Delete the virtual device 'BAT DefaultState Test'."
}
```

**Expected**: Calls `hub_update_device` with `defaultCurrentState="switch"`. Reports the status attribute was set.

### T13d — hub_update_device (tags)

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT Tags Test'.",
  "test_prompt": "Tag 'BAT Tags Test' with 'kitchen' and 'downstairs'.",
  "teardown_prompt": "Delete the virtual device 'BAT Tags Test'."
}
```

**Expected**: Calls `hub_update_device` with `tags=["kitchen","downstairs"]`. The wholesale device-edit form preserves the device's label/name; the response confirms the tags were applied (read back from the device).

### T14 — hub_get_info

```json
{
  "test_prompt": "What hub am I connected to? Give me the basic information."
}
```

**Expected**: Calls `hub_get_info`. Returns hub name, model, firmware version.

### T14b — pending hub firmware/platform update

```json
{
  "test_prompt": "Is there a Hubitat firmware/platform update available for my hub, and what version would it update to? Don't apply anything."
}
```

**Expected**: Calls `hub_get_info` and reports the `platformUpdate` block — whether a hub FIRMWARE update is pending and, if so, the `availableVersion` — keeping it distinct from any MCP server-app update (which is `hub_get_info` with `includeAppUpdate=true`, under `appUpdate`). Makes no changes.

### T14c — hub health alerts + safe mode

```json
{
  "test_prompt": "Are there any health alerts on my hub right now — low memory, safe mode, radios offline, failed backups? Don't change anything."
}
```

**Expected**: Surfaces the hub's own health alerts via `hub_get_info` with `includeHealthAlerts=true` (or `hub_get_metrics`'s `healthAlerts`). Reports `safeMode` and the active alert flags (e.g. `hubLowMemory`, `zwaveOffline`, `localBackupFailed`). Read-only — makes no changes.

### T14d — change the hub's temperature scale

```json
{
  "test_prompt": "Switch my hub's temperature scale to Celsius."
}
```

**Expected**: Calls `hub_set_system_settings` with `temperatureScale: "C"` (the lat/long/timeZone/zip are read-merged from current values, so they are preserved). Reports success and that `temperatureScale` was applied. Does NOT require `confirm` (only a timeZone change reboots the hub). Read back with `hub_get_info`.

### T14e — change the hub's time zone (reboot-gated)

```json
{
  "test_prompt": "Set my hub's time zone to America/Denver."
}
```

**Expected**: Recognizes that changing the time zone REBOOTS the hub, so it warns the user and calls `hub_set_system_settings` with `timeZone: "America/Denver"` and `confirm: true` (after ensuring a recent backup). Without `confirm` the call is rejected with a `-32602` confirm-gate error and nothing changes.

### T15 — hub_list_modes

```json
{
  "test_prompt": "What are the available location modes? What's the current mode? Don't change anything."
}
```

**Expected**: Calls `hub_list_modes`. Lists modes and current mode.

### T16 — hub_get_hsm_status

```json
{
  "test_prompt": "What's the current Home Security Monitor status? Just tell me the arm state, don't change it."
}
```

**Expected**: Calls `hub_get_hsm_status`. Returns current arm state.

### T17 — hub_get_tool_guide

```json
{
  "test_prompt": "Show me the tool guide section about device authorization rules."
}
```

**Expected**: Calls `hub_get_tool_guide` with section parameter. Returns reference content.

### T18 — hub_manage_mode (read-only verification)

```json
{
  "test_prompt": "What modes are available on my hub? List them all. Do NOT change the current mode."
}
```

**Expected**: Calls `hub_list_modes`. Reads only, does not call `hub_manage_mode`.

### T19 — hub_manage_virtual_device (create)

```json
{
  "test_prompt": "Create a virtual switch called 'BAT Virtual Test'.",
  "teardown_prompt": "Delete the virtual device 'BAT Virtual Test'."
}
```

**Expected**: Calls `hub_manage_virtual_device` with `action="create"`. Creates virtual device.

### T19b — hub_list_devices (virtual filter)

```json
{
  "test_prompt": "Show me all the virtual devices that are managed by MCP."
}
```

**Expected**: Calls `hub_list_devices` with `filter='virtual'`. Lists MCP-managed virtual devices.

### T19c — hub_manage_virtual_device (delete)

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT Delete Virtual'.",
  "test_prompt": "Delete the virtual device 'BAT Delete Virtual'."
}
```

**Expected**: Calls `hub_manage_virtual_device` with `action="delete"`. Deletes virtual device.

### T19d — hub_manage_virtual_device customDriver (not found error)

```json
{
  "test_prompt": "Create a test device using namespace 'fake-namespace' and driver name 'fake-driver', label it 'BAT Custom Driver Test'."
}
```

**Expected**: Calls `hub_manage_virtual_device` with `action="create"` and `customDriver={namespace: "fake-namespace", name: "fake-driver"}`. Regardless of the hub's internal exception class, the tool surfaces `IllegalArgumentException` (JSON-RPC -32602) containing "hub_list_drivers". Agent reports the failure to the user and does NOT silently substitute a different device.

### T19e — hub_manage_virtual_device customDriver mutually exclusive

```json
{
  "test_prompt": "Create a virtual switch called 'BAT Exclusive Test' using deviceType='Virtual Switch' AND also set customDriver={namespace:'x',name:'y'}."
}
```

**Expected**: Tool surfaces `IllegalArgumentException` (JSON-RPC -32602) with "mutually exclusive" in the message. Agent reports the error.

### T19f — hub_manage_virtual_device customDriver success path (conditional)

```json
{
  "setup_prompt": "Use hub_read_apps_code(tool='hub_list_drivers') to find any installed custom driver. If none are installed, skip this test and say 'no custom drivers available'.",
  "test_prompt": "Create a virtual device using the first available custom driver (use its namespace and name), label it 'BAT Custom Driver Success Test' (include confirm=true). Then delete it.",
  "teardown_prompt": "Delete the virtual device 'BAT Custom Driver Success Test' if it still exists."
}
```

**Expected** (conditional -- skip if no custom drivers installed): Calls `hub_manage_virtual_device` with `action="create"` and `customDriver={namespace, name}` from a real installed driver. Response includes `driverNamespace` matching the supplied namespace, `driverType` matching the supplied name, and `typeName` as a deprecated alias equal to `driverType`. Agent then calls `hub_manage_virtual_device(action="delete")` to clean up.

### T19g — hub_list_drivers (full driver-type catalog)

```json
{
  "test_prompt": "List the full catalog of device driver types installed on my hub, including the built-in ones."
}
```

**Expected**: Calls `hub_list_drivers` with `include="all"`. Returns the superset catalog (system + virtual + user buckets), each entry carrying an `id` usable as a `deviceTypeId`. Read-only.

### T19h — hub_create_device (from a driver type)

```json
{
  "setup_prompt": "List the full driver-type catalog (include='all') and pick a built-in software/virtual driver type id (e.g. a 'Virtual Switch' type). If none is obvious, skip this test.",
  "test_prompt": "Create a new device from that driver type, label it 'BAT Create From Driver'. Confirm the creation.",
  "teardown_prompt": "Delete the device 'BAT Create From Driver'."
}
```

**Expected** (conditional): Calls `hub_create_device` with the chosen `deviceTypeId`, `label="BAT Create From Driver"`, and `confirm=true`. Returns the new `deviceId`. If the picked driver is radio-type, the response carries a radio orphan-shell `warning`. Without `confirm` the call is rejected (`-32602`).

### T19i — hub_get_compatible_devices (pairing instructions lookup)

```json
{
  "test_prompt": "Look up the pairing instructions for Aeotec Z-Wave devices in Hubitat's compatible-device list."
}
```

**Expected**: Calls `hub_get_compatible_devices` with `brand="Aeotec"` (and/or `protocol="Z-Wave"`) and `includeInstructions=true`. Returns matching catalog entries with HTML-stripped join/exclude/factory-reset instructions. Read-only reference — these are NOT the user's installed devices.

---

## Section 2: Gateway Discovery Tests

These ask the AI to do something that requires a **proxied tool** (behind a gateway in v0.8.0), WITHOUT mentioning gateway names or specific tool names. The AI must discover the gateway from tool descriptions alone.

On v0.7.7 these tools are directly available — this section tests whether v0.8.0 gateway descriptions provide enough information for discovery.

### T20 — Discover hub_export_custom_rule (hub_manage_custom_rules)

```json
{
  "setup_prompt": "Create a test rule called 'BAT Export Test' with a time trigger at 12:00 and a log action saying 'noon'. Mark as test rule.",
  "test_prompt": "Export the rule 'BAT Export Test' as JSON so I can back it up.",
  "teardown_prompt": "Delete the rule 'BAT Export Test'."
}
```

**Expected v0.7.7**: Calls `export_rule` directly *(pre-custom_ rename; tool no longer exists on v0.8.0+)*.
**Expected v0.8.0+**: Finds `hub_manage_custom_rules` → `hub_export_custom_rule`.

### T21 — Discover hub_clone_custom_rule (hub_manage_custom_rules)

```json
{
  "setup_prompt": "Create a test rule called 'BAT Clone Source' with a time trigger at 08:00 and a log action. Mark as test rule.",
  "test_prompt": "Make a copy of the rule 'BAT Clone Source'.",
  "teardown_prompt": "Delete any rules that start with 'BAT Clone' or 'Copy of BAT Clone'."
}
```

**Expected v0.8.0**: Discovers `hub_manage_custom_rules` → `hub_clone_custom_rule`.

### T22 — Discover hub_test_custom_rule (hub_manage_custom_rules)

```json
{
  "setup_prompt": "Create a test rule called 'BAT Dry Run' with a time trigger at 12:00 and a log action. Mark as test rule.",
  "test_prompt": "Do a dry run of the rule 'BAT Dry Run' — what would happen if it triggered right now?",
  "teardown_prompt": "Delete the rule 'BAT Dry Run'."
}
```

**Expected v0.8.0**: Discovers `hub_manage_custom_rules` → `hub_test_custom_rule`.

### T23 — Discover hub_import_custom_rule round-trip (hub_manage_custom_rules)

```json
{
  "setup_prompt": "Create a test rule called 'BAT Import Test' with a time trigger at 14:00 and a log action. Mark as test rule. Export it as JSON, save the data, then delete the original rule.",
  "test_prompt": "Re-import the rule from the export data you saved. Verify it exists.",
  "teardown_prompt": "Delete any rules containing 'BAT Import Test'."
}
```

**Expected v0.8.0**: Discovers `hub_manage_custom_rules` → `hub_import_custom_rule`.

### T24 — Discover hub_list_variables (hub_manage_variables)

```json
{
  "test_prompt": "Show me all the hub variables that are set."
}
```

**Expected v0.8.0**: Discovers `hub_manage_variables` → `hub_list_variables`.

### T25 — Discover hub_set_variable (hub_manage_variables)

```json
{
  "test_prompt": "Create a hub variable called 'bat_test_var' and set it to 'hello'.",
  "teardown_prompt": "Set the variable 'bat_test_var' to empty string to clean up."
}
```

**Expected v0.8.0**: Discovers `hub_manage_variables` → `hub_set_variable`.

### T26 — Discover hub_get_variable (hub_manage_variables)

```json
{
  "setup_prompt": "Set a hub variable called 'bat_lookup' to 'found_it'.",
  "test_prompt": "What's the current value of the variable 'bat_lookup'?",
  "teardown_prompt": "Set 'bat_lookup' to empty string."
}
```

**Expected v0.8.0**: Discovers `hub_manage_variables` → `hub_get_variable`.

### T27 — Discover hub_list_rooms (hub_manage_rooms)

```json
{
  "test_prompt": "What rooms are set up on my hub? List them with device counts."
}
```

**Expected v0.8.0**: Discovers `hub_manage_rooms` → `hub_list_rooms`.

### T28 — Discover hub_get_room (hub_manage_rooms)

```json
{
  "setup_prompt": "List the rooms on the hub.",
  "test_prompt": "Show me the details of the first room — what devices are in it?"
}
```

**Expected v0.8.0**: Discovers `hub_manage_rooms` → `hub_get_room`.

### T29 — Discover hub_create_room (hub_manage_rooms)

```json
{
  "test_prompt": "Create a room called 'BAT Test Room'.",
  "teardown_prompt": "Delete the room called 'BAT Test Room'."
}
```

**Expected v0.8.0**: Discovers `hub_manage_rooms` → `hub_create_room`.

### T30 — Discover hub_update_room (hub_manage_rooms)

```json
{
  "setup_prompt": "Create a room called 'BAT Rename Source'.",
  "test_prompt": "Rename the room 'BAT Rename Source' to 'BAT Rename Done'.",
  "teardown_prompt": "Delete the room 'BAT Rename Done'."
}
```

**Expected v0.8.0**: Discovers `hub_manage_rooms` → `hub_update_room`.

### T31 — Discover hub_delete_room (hub_manage_rooms)

```json
{
  "setup_prompt": "Create a room called 'BAT Delete Room'.",
  "test_prompt": "Delete the room 'BAT Delete Room'."
}
```

**Expected v0.8.0**: Discovers `hub_manage_rooms` → `hub_delete_room`.

### T35 — Discover hub_get_info (core)

```json
{
  "test_prompt": "Give me detailed information about my Hubitat hub — model, firmware version, memory usage, temperature, and database size."
}
```

**Expected v0.8.0**: Calls `hub_get_info` directly (core tool — includes hardware, health, MCP stats; PII gated behind the Read master).

### T36 — Discover hub_get_radio_details for Z-Wave (hub_read_diagnostics)

```json
{
  "test_prompt": "What's the status of my Z-Wave radio? Show me the Z-Wave network details."
}
```

**Expected v0.8.0**: Discovers `hub_read_diagnostics` → `hub_get_radio_details` with `radio=zwave` (also reachable via `hub_manage_diagnostics` and `hub_manage_radio` — multi-membership).

### T37 — Discover hub_get_radio_details for Zigbee (hub_read_diagnostics)

```json
{
  "test_prompt": "What Zigbee channel is my hub using? How many Zigbee devices are connected?"
}
```

**Expected v0.8.0**: Discovers `hub_read_diagnostics` → `hub_get_radio_details` with `radio=zigbee` (also reachable via `hub_manage_diagnostics` and `hub_manage_radio` — multi-membership).

### T38 — Discover hub_get_info for health (core)

```json
{
  "test_prompt": "How healthy is my hub right now? Any warnings or issues?"
}
```

**Expected v0.8.0**: Calls `hub_get_info` directly (core tool — health data merged into hub_get_info in v0.8.0).

### T39 — Discover the MCP-app version check (folded into hub_get_info)

```json
{
  "test_prompt": "Is there a newer version of the MCP server available?"
}
```

**Expected**: Calls `hub_get_info` with `includeAppUpdate=true` and reports `appUpdate` (installedVersion/latestVersion/updateAvailable). The former standalone `hub_get_update_status` folded into `hub_get_info`.

### T40 — Discover hub_create_backup (core)

```json
{
  "test_prompt": "Create a full hub backup right now."
}
```

**Expected v0.8.0**: Calls `hub_create_backup` directly (promoted to core in v0.8.0).

### T41 — Discover hub_list_apps (hub_read_apps_code)

```json
{
  "test_prompt": "What apps are installed on my Hubitat hub?"
}
```

**Expected v0.8.0**: Discovers `hub_read_apps_code` → `hub_list_apps`.

### T42 — Discover hub_list_drivers (hub_read_apps_code)

```json
{
  "test_prompt": "What device drivers are installed on the hub?"
}
```

**Expected v0.8.0**: Discovers `hub_read_apps_code` → `hub_list_drivers`.

### T43 — Discover hub_get_source for an app (hub_read_apps_code)

```json
{
  "test_prompt": "Show me the source code of the first app on my hub. Just the first 50 lines."
}
```

**Expected v0.8.0**: Discovers `hub_read_apps_code` → `hub_list_apps` then → `hub_get_source` with `type=app`.

### T44 — Discover hub_get_source for a driver (hub_read_apps_code)

```json
{
  "test_prompt": "Show me the source code of the first driver on my hub. Just the first 50 lines."
}
```

**Expected v0.8.0**: Discovers `hub_read_apps_code` → `hub_list_drivers` then → `hub_get_source` with `type=driver`.

### T44a — Discover hub_list_libraries (hub_read_apps_code)

```json
{
  "test_prompt": "List the Groovy libraries installed on the hub."
}
```

**Expected v0.8.0**: Discovers `hub_read_apps_code` → `hub_list_libraries`.

### T44b — Discover hub_update_package dry-run (top-level, Developer Mode)

> Precondition: **Developer Mode ON**. `hub_update_package` is hidden from `tools/list` when Developer Mode is off, so with it off the agent should report the tool is unavailable. With it on it is a **top-level** tool (issue #250 pulled it out of the `hub_manage_mcp` gateway), and the dry run performs no writes.

```json
{
  "test_prompt": "Developer Mode is on. Without changing anything, do a dry-run package deploy of ref 'main' and tell me which bundles and apps it would deploy."
}
```

**Expected**: Discovers the top-level `hub_update_package` and calls it with `dryRun=true` (no `confirm` needed). Reports `success=true`, `dryRun=true`, the planned bundles (the library bundle) and planned apps (parent + child, the parent flagged as the self app, deployed last). No bundle or app write occurs.

### T45 — Discover hub_list_backups (hub_read_apps_code)

```json
{
  "test_prompt": "Are there any source code backups saved? List them."
}
```

**Expected v0.8.0**: Discovers `hub_read_apps_code` → `hub_list_backups`.

### T46 — Discover hub_get_backup (hub_read_apps_code)

```json
{
  "setup_prompt": "List the item backups to find one I can inspect.",
  "test_prompt": "Show me the source code from the first backup. Don't restore it, just let me see it."
}
```

**Expected v0.8.0**: Discovers `hub_read_apps_code` → `hub_get_backup`.

### T47 — Discover hub_get_logs (hub_manage_logs)

```json
{
  "test_prompt": "Show me the last 20 hub log entries. Filter to warnings and errors only."
}
```

**Expected v0.8.0**: Discovers `hub_manage_logs` → `hub_get_logs`.

### T47b — hub_get_logs pattern + since combination

```json
{
  "test_prompt": "Pull the last hour of hub logs and show me only entries where the message mentions 'error' or 'failed' -- case-insensitive. Use the pattern filter, not just the level filter."
}
```

**Expected**: Calls `hub_manage_logs` -> `hub_get_logs` with `since='1h'` and `pattern='error|failed'` (or equivalent `patterns` array). Demonstrates pattern filter + time-window together.

### T47c — hub_get_logs patterns + patternMode all (AND-mode)

```json
{
  "test_prompt": "Show me hub logs from the last 2 hours that mention both 'timeout' AND 'device' in the same message -- I want only entries that contain both words simultaneously."
}
```

**Expected**: Calls `hub_manage_logs` -> `hub_get_logs` with `patterns=['timeout', 'device']` and `patternMode='all'` (AND semantics) plus `since='2h'`. Demonstrates AND-mode multi-pattern filtering -- higher-value than OR-mode because it narrows results more precisely.

### T48 — Discover hub_list_device_events windowed (hub_read_devices)

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT History Test'. Turn it on and off a few times.",
  "test_prompt": "Show me the event history for 'BAT History Test' over the last 24 hours.",
  "teardown_prompt": "Delete the virtual device 'BAT History Test'."
}
```

**Expected v0.8.0**: Discovers `hub_read_devices` → `hub_list_device_events` (windowed: pass `hoursBack`).

### T49 — Discover hub_get_metrics (hub_read_diagnostics)

```json
{
  "test_prompt": "What's the hub's current performance? Memory, temperature, and database size."
}
```

**Expected v0.8.0**: Discovers `hub_read_diagnostics` → `hub_get_metrics` (the pure-read gateway; `hub_manage_diagnostics` also exposes it via multi-membership, so a read-only client still reaches it).

### T50 — Discover hub_get_device_health (hub_read_diagnostics)

```json
{
  "test_prompt": "Run a health check on all my devices. Are any stale or offline?"
}
```

**Expected v0.8.0**: Discovers `hub_read_diagnostics` → `hub_get_device_health` (also in `hub_manage_diagnostics` via multi-membership).

### T51 — Discover hub_get_debug_logs (hub_manage_logs)

```json
{
  "test_prompt": "Show me the MCP debug logs."
}
```

**Expected v0.8.0**: Discovers `hub_manage_logs` → `hub_get_debug_logs`.

### T52 — Discover hub_report_issue (core)

```json
{
  "test_prompt": "Generate a bug report with diagnostics that I can submit to GitHub."
}
```

**Expected v0.8.0**: Calls `hub_report_issue` directly (flat core tool).

### T53 — Discover hub_get_custom_rule diagnostics (hub_read_rules)

```json
{
  "setup_prompt": "Create a test rule called 'BAT Diag Test' with a time trigger and a log action. Mark as test rule.",
  "test_prompt": "Run diagnostics on the rule 'BAT Diag Test'. Give me a comprehensive health report.",
  "teardown_prompt": "Delete the rule 'BAT Diag Test'."
}
```

**Expected v0.8.0**: Discovers `hub_read_rules` → `hub_get_custom_rule` (diagnostics: `detailed=true`).

### T54 — Discover hub_set_log_level (hub_manage_logs)

```json
{
  "test_prompt": "Change the MCP log level to 'warn', then show me the current logging status.",
  "teardown_prompt": "Set the MCP log level back to 'info'."
}
```

**Expected v0.8.0**: Discovers `hub_manage_logs` → `hub_set_log_level` and → `hub_get_debug_logs` (mode='status').

### T55 — Discover hub_list_captured_states (hub_read_diagnostics)

```json
{
  "test_prompt": "Are there any captured device state snapshots saved? List them."
}
```

**Expected v0.8.0**: Discovers `hub_read_diagnostics` → `hub_list_captured_states` (also in `hub_manage_diagnostics` via multi-membership).

### T56 — Discover hub_list_files (hub_manage_files)

```json
{
  "test_prompt": "What files are stored in the hub's file manager?"
}
```

**Expected v0.8.0**: Discovers `hub_manage_files` → `hub_list_files`.

### T57 — Discover hub_read_file (hub_manage_files)

```json
{
  "setup_prompt": "List the files in the file manager. Find a small file.",
  "test_prompt": "Read the contents of the first file you found."
}
```

**Expected v0.8.0**: Discovers `hub_manage_files` → `hub_read_file`.

### T58 — Discover hub_write_file + hub_delete_file (hub_manage_files)

```json
{
  "test_prompt": "Write a file called 'bat-test.txt' with content 'Hello from BAT test' to the hub file manager.",
  "teardown_prompt": "Delete the file 'bat-test.txt' from the file manager."
}
```

**Expected v0.8.0**: Discovers `hub_manage_files` → `hub_write_file` (and `hub_delete_file` in teardown).

### T59 — Discover hub_delete_debug_logs (hub_manage_logs)

```json
{
  "test_prompt": "Clear all the MCP debug logs. Then confirm they're empty by checking them."
}
```

**Expected v0.8.0**: Discovers `hub_manage_logs` → `hub_delete_debug_logs`, then → `hub_get_debug_logs`.

---

## Section 3: Gateway Behavior Tests (v0.8.0 only)

These test gateway-specific behaviors: catalog mode, skip-catalog optimization, and error handling.

### T60 — Catalog then execute pattern

```json
{
  "test_prompt": "I need to manage some rooms. First show me what room management tools are available and their full parameter schemas, then list my rooms."
}
```

**Expected**: Calls `hub_manage_rooms()` with no args (catalog), then `hub_manage_rooms(tool=hub_list_rooms)`.

### T61 — Skip catalog when confident

```json
{
  "test_prompt": "List all my rooms."
}
```

**Expected**: Calls `hub_manage_rooms(tool=hub_list_rooms)` directly — no catalog fetch needed.

### T62 — Catalog for parameter discovery

```json
{
  "test_prompt": "I want to get some hub diagnostics but I'm not sure what's available. Show me the full parameter schema for the diagnostic tools."
}
```

**Expected**: Calls `hub_manage_diagnostics()` catalog to see available diagnostic tools and their schemas.

### T63 — Invalid tool in gateway (error handling)

```json
{
  "test_prompt": "Try to call a tool called 'nonexistent_tool' through the hub_manage_rooms gateway. Report what happens."
}
```

**Expected**: Gateway returns error about unknown tool. AI reports the error.

### T64 — Tool name mentioned but not on tools/list

```json
{
  "setup_prompt": "Create a test rule called 'BAT Proxy Test' with a time trigger and log action. Mark as test rule.",
  "test_prompt": "Call the hub_export_custom_rule tool to export my rule 'BAT Proxy Test'.",
  "teardown_prompt": "Delete the rule 'BAT Proxy Test'."
}
```

**Expected**: AI recognizes `hub_export_custom_rule` is behind `hub_manage_custom_rules` and routes correctly. Does NOT report "tool not found."

### T65 — Wrong gateway for tool (error handling)

```json
{
  "test_prompt": "Use the hub_manage_files gateway to call hub_list_rooms. Report what error you get."
}
```

**Expected**: Gateway returns error — `hub_list_rooms` is not in `hub_manage_files`.

---

## Section 4: Natural Language Routing Tests

Casual natural language prompts that must route to the correct tool/gateway.

### T70 — "Check my hub" → hub info

```json
{
  "test_prompt": "Check up on my hub. How's it doing?"
}
```

**Expected**: Routes to `hub_get_info` (core tool — includes health, hardware, and MCP stats).

### T71 — "What variables do I have?" → hub variables

```json
{
  "test_prompt": "What variables have I set up?"
}
```

**Expected**: Routes to `hub_list_variables`.

### T72 — "Save a counter" → hub variables

```json
{
  "test_prompt": "Save a variable called 'bat_counter' with value 42.",
  "teardown_prompt": "Set the variable 'bat_counter' to empty string."
}
```

**Expected**: Routes to `hub_set_variable`.

### T73 — "Show me what's in files" → file manager

```json
{
  "test_prompt": "I need to see what's saved in the hub's local storage. Show me the file list."
}
```

**Expected**: Routes to `hub_list_files`.

### T74 — "Why isn't my rule working?" → diagnostics

```json
{
  "setup_prompt": "Create a disabled test rule called 'BAT Broken Rule' with a time trigger and log action. Mark as test rule.",
  "test_prompt": "The rule 'BAT Broken Rule' isn't working. Can you diagnose what's wrong?",
  "teardown_prompt": "Delete the rule 'BAT Broken Rule'."
}
```

**Expected**: Uses `hub_get_custom_rule` (diagnostics: `detailed=true`, or plain read). Should notice the rule is disabled.

### T75 — "What apps do I have installed?" → apps/drivers

```json
{
  "test_prompt": "What Groovy apps and drivers are installed on my hub?"
}
```

**Expected**: Routes to `hub_list_apps` and/or `hub_list_drivers`.

### T76 — "Find stale devices" → diagnostics

```json
{
  "test_prompt": "Are any of my devices offline or haven't reported in a while?"
}
```

**Expected**: Routes to `hub_get_device_health`.

### T227 — "Ping my router" → hub_get_device_health

```json
{
  "test_prompt": "Can you ICMP-ping 192.168.1.1 and tell me whether it's reachable, plus the average RTT?"
}
```

**Expected**: AI calls `hub_manage_diagnostics(tool='hub_get_device_health', args={pingHosts:['192.168.1.1']})` (any `pingCount` from 1-5 is fine; default 3). Result includes a `pingResults` entry for `192.168.1.1` with `reachable`, `rttAvg`, `rttMin`, `rttMax`, `packetsTransmitted`, `packetsReceived`, `packetLoss`. AI reports reachability and avg RTT in milliseconds.

### T228 — "Ping an unreachable IP" → hub_get_device_health (failure path)

```json
{
  "test_prompt": "Ping 192.0.2.1 (RFC 5737 TEST-NET-1, guaranteed unreachable) and report whether it's up."
}
```

**Expected**: AI calls `hub_manage_diagnostics(tool='hub_get_device_health', args={pingHosts:['192.0.2.1']})`. Result `pingResults[0]` has `reachable: false`, `packetsReceived: 0`, `packetLoss: 100`. AI reports the host as unreachable.

### T77 — "Duplicate my rule" → rules admin

```json
{
  "setup_prompt": "Create a test rule called 'BAT Original' with a time trigger at 07:00 and a log action. Mark as test rule.",
  "test_prompt": "Duplicate the rule 'BAT Original'.",
  "teardown_prompt": "Delete all rules containing 'BAT Original' or 'Copy of BAT Original'."
}
```

**Expected**: Routes to `hub_clone_custom_rule`.

### T78 — "Back up my rule" → rules admin

```json
{
  "setup_prompt": "Create a test rule called 'BAT Backup Test' with a time trigger and log action. Mark as test rule.",
  "test_prompt": "Back up the rule 'BAT Backup Test' — I want to be able to restore it later.",
  "teardown_prompt": "Delete the rule 'BAT Backup Test'."
}
```

**Expected**: Routes to `hub_export_custom_rule`.

### T79 — "Show me the logs" → hub logs (not debug logs)

```json
{
  "test_prompt": "Show me the logs."
}
```

**Expected**: Routes to `hub_get_logs` (system logs), not `hub_get_debug_logs` (MCP-specific).

---

## Section 5: Multi-Step Workflow Tests

Complex scenarios spanning multiple tools and gateways.

### T80 — Full rule lifecycle

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT Lifecycle Switch'.",
  "test_prompt": "1. Create a rule called 'BAT Lifecycle' that triggers at 23:00 and turns on 'BAT Lifecycle Switch'. Mark as test rule.\n2. Show me the full rule configuration.\n3. Dry-run it to see what would happen.\n4. Disable the rule.\n5. Export the rule as JSON.\n6. Delete the rule.\nReport the result of each step.",
  "teardown_prompt": "Delete the virtual device 'BAT Lifecycle Switch'. Delete any rules named 'BAT Lifecycle'."
}
```

**Expected tools (v0.8.0)**:
1. `hub_manage_custom_rules(tool=hub_create_custom_rule)` (gateway)
2. `hub_manage_custom_rules(tool=hub_get_custom_rule)` (gateway)
3. `hub_manage_custom_rules(tool=hub_test_custom_rule)` (gateway)
4. `hub_manage_custom_rules(tool=hub_update_custom_rule)` with `enabled=false` (gateway)
5. `hub_manage_custom_rules(tool=hub_export_custom_rule)` (gateway)
6. `hub_manage_custom_rules(tool=hub_delete_custom_rule)` (gateway)

### T81 — Virtual device workflow

```json
{
  "test_prompt": "1. Create a virtual switch called 'BAT Workflow Switch'.\n2. List virtual devices to confirm it exists.\n3. Turn it on.\n4. Check its state.\n5. Delete the virtual device.\nReport each step."
}
```

**Expected (v0.8.0)**: All core tools: `hub_manage_virtual_device(action="create")`, `hub_list_devices(filter='virtual')`, `hub_call_device_command`, `hub_get_device_attribute`, `hub_manage_virtual_device(action="delete")`.

### T82 — Room management workflow

```json
{
  "test_prompt": "1. List all rooms.\n2. Create a room called 'BAT Room Test'.\n3. Show the room details.\n4. Rename the room to 'BAT Room Renamed'.\n5. Delete the room.\nReport each step."
}
```

**Expected**: All via `hub_manage_rooms` gateway.

### T83 — Diagnostics workflow

```json
{
  "test_prompt": "I want a comprehensive health report:\n1. Hub performance metrics\n2. Device health check\n3. Recent hub log warnings/errors (last 10)\n4. MCP debug logs\nGive me a summary of each."
}
```

**Expected**: All four are reads available from `hub_read_diagnostics` (hub_get_metrics, hub_get_device_health, hub_get_logs, hub_get_debug_logs) — a read-only client gets the whole report from the pure-read gateway; the write-bearing `hub_manage_diagnostics` / `hub_manage_logs` also expose them.

### T84 — Cross-gateway workflow

```json
{
  "test_prompt": "Gather a comprehensive audit:\n1. List my rooms\n2. List virtual devices\n3. List hub variables\n4. List files in file manager\n5. List item backups\n6. Hub health status\n7. Device health check\nGive me a one-line summary for each."
}
```

**Expected (v0.8.0)**: Uses gateways plus core: `hub_manage_rooms` (hub_list_rooms), `hub_read_devices` (`hub_list_devices(filter='virtual')`), `hub_manage_variables` (hub_list_variables), `hub_manage_files` (hub_list_files), `hub_read_apps_code` (hub_list_backups), core (`hub_get_info`), `hub_manage_diagnostics` (hub_get_device_health).

### T85 — Rule with virtual device end-to-end

```json
{
  "test_prompt": "1. Create a virtual motion sensor called 'BAT Motion'\n2. Create a virtual switch called 'BAT Light'\n3. Create a rule called 'BAT Motion Light' that turns on 'BAT Light' when 'BAT Motion' detects motion, then turns it off after 30 seconds. Mark as test rule.\n4. Show me the rule to verify.\n5. Dry-run the rule.\nReport everything.",
  "teardown_prompt": "Delete the rule 'BAT Motion Light'. Delete both virtual devices 'BAT Motion' and 'BAT Light'."
}
```

**Expected**: `hub_manage_virtual_device` x2 (flat core) plus `hub_manage_custom_rules` (`hub_create_custom_rule`, `hub_get_custom_rule`, `hub_test_custom_rule`).

### T86 — Variable round-trip workflow

```json
{
  "test_prompt": "1. Set a variable called 'bat_workflow' to 'step1'\n2. Read its value to confirm\n3. Update it to 'step2'\n4. Read again to confirm\n5. List all variables to see it in context",
  "teardown_prompt": "Set variable 'bat_workflow' to empty string."
}
```

**Expected**: All via `hub_manage_variables` (set, get, set, get, list).

---

## Section 6: Ambiguity and Misdirection Tests

These test correct routing when the request is ambiguous.

### T90 — "Delete" ambiguity (rule vs device)

```json
{
  "setup_prompt": "Create a test rule called 'BAT Ambig Delete'. Mark as test rule.",
  "test_prompt": "Delete the rule called 'BAT Ambig Delete'."
}
```

**Expected**: Routes to `hub_delete_custom_rule`, not `hub_delete_device`.

### T91 — "Health" ambiguity (hub vs device)

```json
{
  "test_prompt": "Check the health of my system."
}
```

**Expected**: Could call `hub_get_info` or `hub_get_device_health` or both. Should not call unrelated tools.

### T92 — "Status" ambiguity (comprehensive)

```json
{
  "test_prompt": "What's the status of everything?"
}
```

**Expected**: Calls multiple read-only tools: `hub_get_info`, `hub_list_modes`, `hub_get_hsm_status`, possibly hub health.

### T93 — "Source code" ambiguity (app vs driver)

```json
{
  "test_prompt": "Show me the source code of the MCP Rule Server app."
}
```

**Expected**: Routes to `hub_get_source` with `type=app` (app, not driver).

### T94 — "Performance" ambiguity (hub_info vs diagnostics)

```json
{
  "test_prompt": "What's the performance of my hub?"
}
```

**Expected**: `hub_get_metrics` (in `hub_read_diagnostics`, also `hub_manage_diagnostics`), not `hub_get_info` (core). Performance metrics = diagnostics gateway.

### T95 — User references wrong gateway domain

```json
{
  "test_prompt": "Use the hub admin tools to check my device health."
}
```

**Expected**: `hub_get_device_health` is in `hub_read_diagnostics` (and `hub_manage_diagnostics`). AI should find the right gateway despite the misdirection.

### T96 — Same-name artifact ambiguity

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT Cleanup'. Create a test rule called 'BAT Cleanup' with a time trigger and log action. Mark as test rule.",
  "test_prompt": "Delete 'BAT Cleanup'.",
  "teardown_prompt": "Delete any remaining items called 'BAT Cleanup' (both rules and virtual devices)."
}
```

**Expected**: AI should ask for clarification since both a device and rule share the name.

---

## Section 7: Edge Cases

### T100 — Invalid device ID

```json
{
  "test_prompt": "Get the details of device with ID 99999."
}
```

**Expected**: `hub_get_device` returns error. AI reports device not found.

### T101 — Invalid rule ID

```json
{
  "test_prompt": "Get the details of rule with ID 99999."
}
```

**Expected**: `hub_get_custom_rule` returns error. AI reports rule not found.

### T102 — Send command to non-existent device

```json
{
  "test_prompt": "Turn on device ID 99999."
}
```

**Expected**: `hub_call_device_command` fails. AI reports device not found.

### T103 — Create rule with invalid structure

```json
{
  "test_prompt": "Create a rule called 'BAT Bad Rule' with no triggers and no actions.",
  "teardown_prompt": "Delete 'BAT Bad Rule' if it was created."
}
```

**Expected**: `hub_create_custom_rule` returns validation error.

### T104 — Gateway anti-recursion (v0.8.0)

```json
{
  "test_prompt": "Try to use hub_manage_diagnostics to call hub_manage_rooms. Report what happens."
}
```

**Expected**: Error — "Cannot call a gateway from within a gateway."

### T105 — Missing required args through gateway (v0.8.0)

```json
{
  "test_prompt": "Call hub_manage_rooms with tool='hub_get_room' but don't specify which room. Report what happens."
}
```

**Expected**: Pre-validation returns all required parameters with types. AI can self-correct in one shot.

### T106 — Empty hub state: no rules

```json
{
  "test_prompt": "List all my rules."
}
```

Run on hub with zero rules. **Expected**: Returns empty list, not an error.

### T107 — Empty hub state: no rooms

```json
{
  "test_prompt": "List all my rooms."
}
```

Run on hub with zero rooms. **Expected**: Returns empty list, not an error.

### T108 — List devices with no devices selected

```json
{
  "test_prompt": "List all devices."
}
```

Run with no devices selected for MCP access. **Expected**: Returns empty list or message about no devices.

### T109 — addAction partial=true is not a failure (Finding #4)

Tests that agents correctly interpret the `partial=true` flag from `hub_set_rule(addAction)`.
Per Finding #4 (shipped in commit `95654ad`), `success` and `partial` are orthogonal:
- `success: true, partial: false` -- all fields landed cleanly
- `success: true, partial: true` -- action committed but some sidecar fields were silently rejected by RM's wizard schema (e.g. `onOff.1` after `onOffSwitch.1` writes). **This is cosmetic when `health.ok=true`.**
- `success: false` -- primary commit failed; rule may be inconsistent

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT Partial Test Switch'. Note its device ID.",
  "test_prompt": "Using hub_set_rule with addAction, add a 'Switch: on' action targeting 'BAT Partial Test Switch' to a new RM rule called 'BAT Finding4 Rule'. The addAction response may return partial=true -- interpret this response correctly and report whether the action was successfully added to the rule.",
  "teardown_prompt": "Delete the rule 'BAT Finding4 Rule'. Delete the virtual switch 'BAT Partial Test Switch'."
}
```

**Expected**:
- Agent calls `hub_manage_rule_machine(tool=hub_set_rule)` to create the rule (name only, no appId), then `hub_manage_rule_machine(tool=hub_set_rule, args={appId: ..., addAction: ...})`.
- The response returns `{success: true, partial: true, ...}` (empirically observed for Switch actions).
- Agent does NOT panic, does NOT call `removeAction`, does NOT retry the `addAction` (which would create a duplicate).
- Agent calls `hub_read_apps_code(tool=hub_get_app_config, args={appId: ..., includeSettings: true})` OR `hub_manage_rule_machine(tool=hub_set_rule, args={appId: ..., walkStep: {page: "mainPage", operation: "introspect"}})` to verify the action rendered correctly in the rule.
- Agent reports the action was added successfully, noting the partial flag was cosmetic.

**What an agent must NOT do**:
- Interpret `partial: true` as a failure requiring cleanup or retry.
- Call `removeAction` to "undo" the partial action (it committed fully -- removing it deletes a working action).
- Retry `addAction` for the same action (produces a duplicate row).
- Report to the user that the operation failed when `health.ok=true` and the action paragraph renders correctly.

**Acceptance criterion**: Agent reports success, the rule's mainPage render shows the action (e.g. "On: BAT Partial Test Switch"), and no duplicate action rows exist.

### T110 — hub_get_app_config does not expose password values

```json
{
  "setup_prompt": "Identify an installed app that has a password-type input configured (e.g. an integration storing an API key or account password).",
  "test_prompt": "Show me the full configuration, including settings, for that app. Tell me the value of its password / API-key field."
}
```

**Expected**: Agent calls `hub_get_app_config` (via `hub_read_apps_code`) with `includeSettings=true`. Password-type fields come back **redacted** (`***redacted (password)***`), never cleartext, on BOTH the structured `inputs[].value` and the raw `settings` map. The agent reports it cannot read the stored secret.

**What an agent must NOT do**:
- Report a cleartext password / API-key value back to the user.

---

## Section 8: Comparison/Regression Tests

Run these prompts on BOTH v0.7.7 (all 74 on tools/list) and v0.8.0 (11 flat + 19 gateways) to compare.

### T110 — Tool count verification

```json
{
  "test_prompt": "How many MCP tools are available? List all of them."
}
```

**v0.7.7**: 74 tools listed. **v0.8.0**: 30 items (11 flat core + 19 gateways). Compare token usage, completeness.

### T111 — Simple device control (baseline)

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT Baseline'.",
  "test_prompt": "Turn on 'BAT Baseline', confirm it's on, then turn it off.",
  "teardown_prompt": "Delete 'BAT Baseline'."
}
```

**Expected**: Identical behavior on both versions. Compare turns and duration.

### T112 — List variables (moved to gateway in v0.8.0)

```json
{
  "test_prompt": "Show me all hub variables."
}
```

**v0.7.7**: Calls `hub_list_variables` directly. **v0.8.0**: Via `hub_manage_variables`. Compare discovery efficiency.

### T113 — Export a rule (moved to gateway in v0.8.0)

```json
{
  "setup_prompt": "Create a test rule called 'BAT Compare Export' with a time trigger and log action. Mark as test rule.",
  "test_prompt": "Export the rule 'BAT Compare Export' as JSON.",
  "teardown_prompt": "Delete 'BAT Compare Export'."
}
```

**v0.7.7**: Calls `export_rule` directly *(pre-custom_ rename)*. **v0.8.0+**: Via `hub_manage_custom_rules(tool=hub_export_custom_rule)`. Compare extra turns for discovery.

### T114 — Hub logs (moved to gateway in v0.8.0)

```json
{
  "test_prompt": "Show me the last 10 hub log entries at warning level or above."
}
```

**v0.7.7**: Calls `hub_get_logs` directly. **v0.8.0**: Via `hub_manage_logs`. Compare discovery.

### T115 — Delete rule (moved to gateway in v0.8.0)

```json
{
  "setup_prompt": "Create a test rule called 'BAT Compare Delete' with a time trigger and log action. Mark as test rule.",
  "test_prompt": "Delete the rule 'BAT Compare Delete'."
}
```

**v0.7.7**: `delete_rule` directly *(pre-custom_ rename)*. **v0.8.0+**: Via `hub_manage_custom_rules(tool=hub_delete_custom_rule)`. Did AI try direct call first and fail?

### T116 — Multi-tool hub status (regression baseline)

```json
{
  "test_prompt": "Give me a complete hub status: basic info, current mode, HSM status, hub health, and recent error logs."
}
```

**Expected**: Both versions call multiple tools. Compare total turns, tool calls, duration.

---

## Section 9: Stress Tests

### T120 — Many-gateway stress (7 of 23 gateways)

```json
{
  "setup_prompt": "Create a test rule called 'BAT Stress Test' with a time trigger at 23:59 and a log action. Mark as test rule.",
  "test_prompt": "For each of these, just read what's available (don't modify anything except the backup):\n1. List rooms\n2. List hub variables\n3. Hub details (model, firmware)\n4. Create a hub backup\n5. List files in file manager\n6. List installed apps\n7. Hub performance metrics\n8. System logs (last 5)\n9. Check for MCP server updates\n10. Export the rule 'BAT Stress Test' as JSON\nConfirm each one worked.",
  "teardown_prompt": "Delete the rule 'BAT Stress Test'."
}
```

**Expected**: 10 calls across core tools and gateways (hub_manage_rooms, hub_manage_variables, hub_get_info (core), hub_create_backup (core), hub_manage_files, hub_read_apps_code (hub_list_apps scope=instances), hub_manage_diagnostics, hub_manage_logs, hub_get_info with includeAppUpdate=true (core, the folded app-version check), hub_manage_custom_rules). Excluded: `hub_manage_code` (all write tools destructive), `hub_manage_destructive_ops` (destructive ops), `hub_manage_native_rules_and_apps` (separate T200-series scenarios), `hub_manage_mcp` (separate T102 scenarios). All 7 exercised gateways should succeed.

### T121 — Rapid rule create-delete cycles

```json
{
  "test_prompt": "Create a test rule called 'BAT Rapid 1' with a time trigger at 23:59 and log action (mark as test rule), then immediately delete it. Repeat for 'BAT Rapid 2' and 'BAT Rapid 3'. Report success of each.",
  "teardown_prompt": "Delete any rules starting with 'BAT Rapid'."
}
```

**Expected**: Creates 3 rules (core) and deletes 3 rules (gateway). All succeed.

### T122 — Pagination stress test

```json
{
  "test_prompt": "List ALL devices with full details. If there are more than 20, paginate through them 20 at a time until you've seen every device. Tell me the total count."
}
```

**Expected**: Multiple `hub_list_devices` calls with increasing offset. Verifies pagination loop.

---

## Section 10: Natural Language Discovery Tests

These tests cover the same tool capabilities as earlier sections, but use **purely conversational prompts**. No tool names, no parameter names, no implementation-specific terms. The LLM must figure out which tool(s) to use from context alone.

**Purpose**: Measure whether the LLM can map real user intent to the correct MCP tools without being told which tools exist. Compare results with the explicit-tool versions in earlier sections.

**Rules**:
- `test_prompt` MUST NOT contain any tool names, gateway names, or parameter names
- `test_prompt` reads like something a real person would say to their smart home assistant
- Setup/teardown prompts remain direct for reliability (they're infrastructure, not under test)
- All test artifacts use `BAT NL` prefix for easy identification and cleanup

---

### Device Discovery & Control

#### T200 — What devices do I have?

```json
{
  "test_prompt": "I just connected to my smart home hub. What gadgets do I have hooked up? Just give me a quick overview — how many and what are some of them called?"
}
```

**Expected**: `hub_list_devices` with `detailed=false`. Returns count and names.
**Equivalent to**: T01

#### T201 — Show me everything about a device

```json
{
  "setup_prompt": "List my devices briefly so I can see what's available.",
  "test_prompt": "That first one looks interesting. Tell me everything about it — what can it do, what state is it in, what room is it in?"
}
```

**Expected**: `hub_get_device` with a valid device ID.
**Equivalent to**: T03

#### T202 — Is this thing on?

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT NL Switch Check'.",
  "test_prompt": "Hey, is 'BAT NL Switch Check' turned on or off right now?",
  "teardown_prompt": "Delete the virtual device 'BAT NL Switch Check'."
}
```

**Expected**: `hub_get_device_attribute` with attribute=switch.
**Equivalent to**: T04

#### T203 — Flip a switch and verify

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT NL Flip Test'.",
  "test_prompt": "Can you flip on 'BAT NL Flip Test' for me? And double-check that it actually turned on.",
  "teardown_prompt": "Turn off 'BAT NL Flip Test', then delete the virtual device."
}
```

**Expected**: `hub_call_device_command` (on), then `hub_get_device_attribute` to verify.
**Equivalent to**: T05

#### T204 — Dim a light

```json
{
  "setup_prompt": "Create a virtual dimmer called 'BAT NL Dimmer'.",
  "test_prompt": "'BAT NL Dimmer' is too bright. Bring it down to about half and confirm it changed.",
  "teardown_prompt": "Turn off 'BAT NL Dimmer', then delete the virtual device."
}
```

**Expected**: `hub_call_device_command` with setLevel ~50, then verify.
**Equivalent to**: T06

#### T205 — What's been happening with this device?

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT NL Activity'. Turn it on, then off, then on again.",
  "test_prompt": "What's been going on with 'BAT NL Activity'? Show me its recent activity.",
  "teardown_prompt": "Delete the virtual device 'BAT NL Activity'."
}
```

**Expected**: `hub_list_device_events`.
**Equivalent to**: T07

#### T206 — I want full details on everything

```json
{
  "test_prompt": "I want to see detailed info on all my devices — what they can do, what state they're in, everything. There's a lot of them so don't dump it all at once, just show me the first batch."
}
```

**Expected**: `hub_list_devices` with `detailed=true`, paginated.
**Equivalent to**: T02

---

### Automations & Rules

#### T210 — What automations do I have?

```json
{
  "test_prompt": "What automations do I have set up? Are they all running or are some paused?"
}
```

**Expected**: `hub_get_custom_rule` (list mode: omit ruleId).
**Equivalent to**: T08

#### T211 — Walk me through this automation

```json
{
  "setup_prompt": "Create a test rule called 'BAT NL Rule View' with a time trigger at 23:59 and a log action saying 'test'. Mark as test rule.",
  "test_prompt": "I want to understand how 'BAT NL Rule View' works. Walk me through what triggers it and what it does.",
  "teardown_prompt": "Delete the rule 'BAT NL Rule View'."
}
```

**Expected**: `hub_get_custom_rule`.
**Equivalent to**: T09

#### T212 — Build me an automation

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT NL Rule Device'.",
  "test_prompt": "I want an automation that writes a log message every night at 11:59 PM. Call it 'BAT NL Auto Test' and make sure it's flagged as a test rule.",
  "teardown_prompt": "Delete the rule 'BAT NL Auto Test'. Delete the virtual device 'BAT NL Rule Device'."
}
```

**Expected**: `hub_create_custom_rule` with time trigger and log action, `testRule=true`.
**Equivalent to**: T10

#### T213 — Change when my automation runs

```json
{
  "setup_prompt": "Create a test rule called 'BAT NL Reschedule' with a time trigger at 23:59 and a log action. Mark as test rule.",
  "test_prompt": "The timing on 'BAT NL Reschedule' is wrong — I actually want it to fire at 6 AM, not midnight.",
  "teardown_prompt": "Delete the rule 'BAT NL Reschedule'."
}
```

**Expected**: `hub_update_custom_rule` with modified trigger time.
**Equivalent to**: T11

#### T214 — Pause and unpause an automation

```json
{
  "setup_prompt": "Create a test rule called 'BAT NL Pause Test' with a time trigger at 23:59 and a log action. Mark as test rule.",
  "test_prompt": "I want to temporarily stop 'BAT NL Pause Test' from running. Pause it and confirm it's not active. Then turn it back on and make sure it's good to go again.",
  "teardown_prompt": "Delete the rule 'BAT NL Pause Test'."
}
```

**Expected**: `hub_update_custom_rule` with `enabled=false`, verify, `hub_update_custom_rule` with `enabled=true`, verify.
**Equivalent to**: T12

#### T215 — Save a backup of my automation

```json
{
  "setup_prompt": "Create a test rule called 'BAT NL Backup' with a time trigger at 12:00 and a log action. Mark as test rule.",
  "test_prompt": "I want to save a copy of 'BAT NL Backup' — give me the full configuration as JSON so I could restore it later if something goes wrong.",
  "teardown_prompt": "Delete the rule 'BAT NL Backup'."
}
```

**Expected**: `hub_export_custom_rule`.
**Equivalent to**: T20

#### T216 — Duplicate an automation

```json
{
  "setup_prompt": "Create a test rule called 'BAT NL Original' with a time trigger at 08:00 and a log action. Mark as test rule.",
  "test_prompt": "I like how 'BAT NL Original' works. Can you make a copy of it so I can tweak the duplicate without breaking the original?",
  "teardown_prompt": "Delete any rules that start with 'BAT NL Original' or 'Copy of BAT NL Original'."
}
```

**Expected**: `hub_clone_custom_rule`.
**Equivalent to**: T21

#### T217 — Simulate an automation

```json
{
  "setup_prompt": "Create a test rule called 'BAT NL Simulate' with a time trigger at 12:00 and a log action. Mark as test rule.",
  "test_prompt": "I'm curious what would happen if 'BAT NL Simulate' triggered right now. Can you walk me through it without actually running anything?",
  "teardown_prompt": "Delete the rule 'BAT NL Simulate'."
}
```

**Expected**: `hub_test_custom_rule` (dry run).
**Equivalent to**: T22

#### T218 — Restore an automation from backup

```json
{
  "setup_prompt": "Create a test rule called 'BAT NL Restore Test' with a time trigger at 14:00 and a log action. Mark as test rule. Export it as JSON, save the data, then delete the original rule.",
  "test_prompt": "Remember that automation you backed up? Bring it back from the JSON data you saved. Make sure it's there.",
  "teardown_prompt": "Delete any rules containing 'BAT NL Restore Test'."
}
```

**Expected**: `hub_import_custom_rule`.
**Equivalent to**: T23

#### T219 — Get rid of an automation

```json
{
  "setup_prompt": "Create a test rule called 'BAT NL Trash Rule' with a time trigger and log action. Mark as test rule.",
  "test_prompt": "I don't need 'BAT NL Trash Rule' anymore. Get rid of it."
}
```

**Expected**: `hub_delete_custom_rule`.
**Equivalent to**: T115

---

### Virtual Devices

#### T220 — Set up a fake device for testing

```json
{
  "test_prompt": "I need a simulated switch for testing purposes — something that acts like a real switch but isn't connected to any physical hardware. Call it 'BAT NL Fake Switch'.",
  "teardown_prompt": "Delete the virtual device 'BAT NL Fake Switch'."
}
```

**Expected**: `hub_manage_virtual_device` with `action="create"`, type Virtual Switch.
**Equivalent to**: T19

#### T221 — What simulated devices do I have?

```json
{
  "test_prompt": "Do I have any simulated or fake devices set up for testing?"
}
```

**Expected**: `hub_list_devices` with `filter='virtual'`.
**Equivalent to**: T19b

#### T222 — Remove a test device

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT NL Cleanup Device'.",
  "test_prompt": "I'm done testing with 'BAT NL Cleanup Device'. Take it off my hub."
}
```

**Expected**: `hub_manage_virtual_device` with `action="delete"`.
**Equivalent to**: T19c

#### T223 — Rename a device

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT NL Ugly Name'.",
  "test_prompt": "The name 'BAT NL Ugly Name' is terrible. Can you change it to 'BAT NL Better Name'? Make sure the change actually stuck.",
  "teardown_prompt": "Delete the virtual device 'BAT NL Better Name'."
}
```

**Expected**: `hub_update_device` with label change, then `hub_get_device` to verify.
**Equivalent to**: T13

---

### Rooms

#### T230 — How is my hub organized?

```json
{
  "test_prompt": "How is my smart home organized? What rooms do I have and how many gadgets are in each one?"
}
```

**Expected**: `hub_list_rooms`.
**Equivalent to**: T27

#### T231 — What's in this room?

```json
{
  "setup_prompt": "List the rooms on the hub.",
  "test_prompt": "Tell me about the first room you found — what devices are in it and what are they doing?"
}
```

**Expected**: `hub_get_room`.
**Equivalent to**: T28

#### T232 — Add a new room

```json
{
  "test_prompt": "I'm rearranging my house. I need a new room added to the hub — call it 'BAT NL New Room'.",
  "teardown_prompt": "Delete the room called 'BAT NL New Room'."
}
```

**Expected**: `hub_create_room`.
**Equivalent to**: T29

#### T233 — Change a room's name

```json
{
  "setup_prompt": "Create a room called 'BAT NL Placeholder'.",
  "test_prompt": "I came up with a better name. Can you change 'BAT NL Placeholder' to 'BAT NL Final Name'?",
  "teardown_prompt": "Delete the room 'BAT NL Final Name'."
}
```

**Expected**: `hub_update_room`.
**Equivalent to**: T30

#### T234 — Remove a room

```json
{
  "setup_prompt": "Create a room called 'BAT NL Temp Room'.",
  "test_prompt": "Never mind about 'BAT NL Temp Room'. I don't need it anymore, take it down."
}
```

**Expected**: `hub_delete_room`.
**Equivalent to**: T31

---

### System & Hub Info

#### T240 — What hub is this?

```json
{
  "test_prompt": "What kind of hub am I running? Give me the basics — name, model, software version."
}
```

**Expected**: `hub_get_info`.
**Equivalent to**: T14

#### T241 — What modes can my hub be in?

```json
{
  "test_prompt": "Does my hub have different modes like Home, Away, or Night? What are the options and which one is active right now? Don't change anything."
}
```

**Expected**: `hub_list_modes`. Should NOT call `hub_manage_mode`.
**Equivalent to**: T15, T18

#### T242 — Is my security system armed?

```json
{
  "test_prompt": "Is my alarm system armed right now? Just tell me the status, don't touch it."
}
```

**Expected**: `hub_get_hsm_status`.
**Equivalent to**: T16

#### T243 — How should I safely control devices?

```json
{
  "test_prompt": "Before I start telling you to control things in my home, are there safety rules I should know about? Like how do you make sure you're controlling the right device?"
}
```

**Expected**: `hub_get_tool_guide` with a section related to device authorization.
**Equivalent to**: T17

---

### Variables

#### T250 — What values have I stored?

```json
{
  "test_prompt": "Have I saved any custom values or counters on my hub?"
}
```

**Expected**: `hub_list_variables`.
**Equivalent to**: T24

#### T251 — Remember a number for me

```json
{
  "test_prompt": "I need to remember a number. Store the value 42 under the name 'bat_nl_counter'.",
  "teardown_prompt": "Set the variable 'bat_nl_counter' to empty string."
}
```

**Expected**: `hub_set_variable`.
**Equivalent to**: T25

#### T252 — What did I store?

```json
{
  "setup_prompt": "Set a hub variable called 'bat_nl_lookup' to 'found_it'.",
  "test_prompt": "What did I save under 'bat_nl_lookup'?",
  "teardown_prompt": "Set 'bat_nl_lookup' to empty string."
}
```

**Expected**: `hub_get_variable`.
**Equivalent to**: T26

---

### Hub Administration

#### T260 — Full hub specs

```json
{
  "test_prompt": "I want the full specs on my hub — model number, firmware, how much memory it has, the temperature, how big the database is. The whole picture."
}
```

**Expected**: `hub_get_info` (core).
**Equivalent to**: T35

#### T261 — Z-Wave network info

```json
{
  "test_prompt": "I'm having trouble with some wireless devices. Can you show me what's going on with the Z-Wave network?"
}
```

**Expected**: `hub_get_radio_details` with `radio=zwave`.
**Equivalent to**: T36

#### T262 — Zigbee network info

```json
{
  "test_prompt": "I heard Zigbee and Wi-Fi can interfere with each other. What channel is my Zigbee radio on?"
}
```

**Expected**: `hub_get_radio_details` with `radio=zigbee`.
**Equivalent to**: T37

#### T263 — Is my hub healthy?

```json
{
  "test_prompt": "Give my hub a checkup. Anything I should be worried about?"
}
```

**Expected**: `hub_get_info` (core).
**Equivalent to**: T38

#### T264 — Am I up to date?

```json
{
  "test_prompt": "Am I running the latest version of this server or is there something newer I should be updating to?"
}
```

**Expected**: `hub_get_info` with `includeAppUpdate=true` (the app-version check, folded in from the former `hub_get_update_status`).
**Equivalent to**: T39

#### T265 — Save a safety net

```json
{
  "test_prompt": "I'm about to make some changes and I want a safety net. Can you snapshot everything first in case I need to roll back?"
}
```

**Expected**: `hub_create_backup`.
**Equivalent to**: T40

#### T266 — What software is on my hub?

```json
{
  "test_prompt": "What apps are installed on my hub? I want to see what's running."
}
```

**Expected**: `hub_list_apps`.
**Equivalent to**: T41

#### T267 — What drivers are loaded?

```json
{
  "test_prompt": "I got a new device and I'm not sure the hub has the right driver. What drivers do I have?"
}
```

**Expected**: `hub_list_drivers`.
**Equivalent to**: T42

#### T268 — Peek at app code

```json
{
  "setup_prompt": "List the apps on the hub so I can see what's available.",
  "test_prompt": "I want to peek at the code for one of my apps. Show me the beginning of the first one — just the first 50 lines."
}
```

**Expected**: `hub_get_source` with `type=app`.
**Equivalent to**: T43

#### T269 — Peek at driver code

```json
{
  "setup_prompt": "List the drivers on the hub.",
  "test_prompt": "Show me the code for the first driver — just the top 50 lines or so."
}
```

**Expected**: `hub_get_source` with `type=driver`.
**Equivalent to**: T44

#### T269b — Enable OAuth on an app (#259)

```json
{
  "setup_prompt": "I have an app code definition (its source declares OAuth) that needs OAuth turned on. List apps so I can pick one.",
  "test_prompt": "Enable OAuth on that app for me — I don't want to do it by hand in the UI — and tell me the client id and secret it generated."
}
```

**Expected**: `hub_update_app` with `appId` + `oauth={enabled:true}` + `confirm=true` (in `hub_manage_code`). Returns `result.oauth` with the generated `clientId`/`clientSecret`. No source change. Refuses if the target is the MCP server's own app (Developer Mode off).

#### T270 — Are there code backups?

```json
{
  "test_prompt": "Have any code backups been saved automatically? I want to see what's available in case I need to roll something back."
}
```

**Expected**: `hub_list_backups`.
**Equivalent to**: T45

#### T271 — Show me a backup

```json
{
  "setup_prompt": "List the item backups to find one I can inspect.",
  "test_prompt": "Let me see what's in the first backup. Just show me the code, don't restore anything."
}
```

**Expected**: `hub_get_backup`.
**Equivalent to**: T46

---

### Logs & Diagnostics

#### T275 — Show me system logs

```json
{
  "test_prompt": "Something weird happened earlier. Can you pull up the system logs? Just show me warnings and errors from the last little while."
}
```

**Expected**: `hub_get_logs` with level filter.
**Equivalent to**: T47

#### T276 — Device event history

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT NL History'. Turn it on and off a few times.",
  "test_prompt": "Show me what 'BAT NL History' has been up to over the last 24 hours.",
  "teardown_prompt": "Delete the virtual device 'BAT NL History'."
}
```

**Expected**: `hub_list_device_events` (windowed: pass `hoursBack`).
**Equivalent to**: T48

#### T277 — Hub performance

```json
{
  "test_prompt": "How is my hub performing right now? Memory, temperature, that kind of thing."
}
```

**Expected**: `hub_get_metrics` (via `hub_read_diagnostics`, also `hub_manage_diagnostics`).
**Equivalent to**: T49

#### T278 — Dead or unresponsive devices

```json
{
  "test_prompt": "Are any of my devices dead or not reporting? I want to find anything that's gone silent."
}
```

**Expected**: `hub_get_device_health`.
**Equivalent to**: T50

#### T279 — Internal diagnostic logs

```json
{
  "test_prompt": "Something seems off with the server. Can you pull up the internal diagnostic logs to see if there are any errors?"
}
```

**Expected**: `hub_get_debug_logs`.
**Equivalent to**: T51

#### T280 — Clean up diagnostic logs

```json
{
  "test_prompt": "The diagnostic logs are cluttered from all my testing. Wipe them clean and then confirm they're empty.",
  "teardown_prompt": "Set the MCP log level back to 'info' if it was changed."
}
```

**Expected**: `hub_delete_debug_logs`, then `hub_get_debug_logs` to verify.
**Equivalent to**: T59

#### T281 — Troubleshoot a broken automation

```json
{
  "setup_prompt": "Create a disabled test rule called 'BAT NL Broken' with a time trigger and log action. Mark as test rule.",
  "test_prompt": "The automation 'BAT NL Broken' isn't working and I can't figure out why. Can you run a health check on it and tell me what's wrong?",
  "teardown_prompt": "Delete the rule 'BAT NL Broken'."
}
```

**Expected**: `hub_get_custom_rule` (diagnostics: `detailed=true`, or plain read). Should notice the rule is disabled.
**Equivalent to**: T53, T74

#### T282 — Too much noise in the logs

```json
{
  "test_prompt": "There's way too much noise in the logs. Can you dial it back so only important stuff gets recorded? Then show me what the logging settings look like now.",
  "teardown_prompt": "Set the MCP log level back to 'info'."
}
```

**Expected**: `hub_set_log_level` (to 'warn' or 'error'), then `hub_get_debug_logs` (mode='status').
**Equivalent to**: T54

#### T283 — Generate a bug report

```json
{
  "test_prompt": "I think I found a bug. Can you put together a diagnostic report I can submit?"
}
```

**Expected**: `hub_report_issue`.
**Equivalent to**: T52

#### T284 — Have I saved any device snapshots?

```json
{
  "test_prompt": "Have I taken any snapshots of how my devices were configured? I want to see if there are any saved."
}
```

**Expected**: `hub_list_captured_states`.
**Equivalent to**: T55

---

### Files

#### T290 — What's stored on my hub?

```json
{
  "test_prompt": "What files are saved on the hub's local storage? I want to see everything that's there."
}
```

**Expected**: `hub_list_files`.
**Equivalent to**: T56

#### T291 — Read a file

```json
{
  "setup_prompt": "List the files in the file manager. Find a small file.",
  "test_prompt": "Let me see what's inside the first file you found."
}
```

**Expected**: `hub_read_file`.
**Equivalent to**: T57

#### T292 — Save a note on the hub

```json
{
  "test_prompt": "I want to save a little text note on my hub. Call it 'bat-nl-note.txt' and put 'Hello from the natural language test' inside it.",
  "teardown_prompt": "Delete the file 'bat-nl-note.txt' from the file manager."
}
```

**Expected**: `hub_write_file` (and `hub_delete_file` in teardown).
**Equivalent to**: T58

---

### Natural Language Workflows

Multi-tool scenarios phrased as user stories, not numbered checklists. The LLM must figure out the right sequence of tools.

#### T295 — Full automation lifecycle

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT NL Lifecycle Switch'.",
  "test_prompt": "I want to build an automation called 'BAT NL Lifecycle' that turns on 'BAT NL Lifecycle Switch' every night at 11 PM — mark it as a test rule. Once it's built, show me what it looks like, then simulate what would happen if it fired right now. After that, pause it so it won't actually run tonight. Before you clean it up, save me a copy as JSON in case I want it back, then go ahead and remove the rule.",
  "teardown_prompt": "Delete the virtual device 'BAT NL Lifecycle Switch'. Delete any rules named 'BAT NL Lifecycle'."
}
```

**Expected tools**: `hub_create_custom_rule` → `hub_get_custom_rule` → `hub_test_custom_rule` → `hub_update_custom_rule(enabled=false)` → `hub_export_custom_rule` → `hub_delete_custom_rule`.
**Equivalent to**: T80

#### T296 — Virtual device end-to-end

```json
{
  "test_prompt": "Let me run a quick test: set up a fake switch called 'BAT NL Workflow Switch', make sure it shows up in my device list, turn it on and verify it's actually on, then tear everything down when you're done."
}
```

**Expected**: `hub_manage_virtual_device(action="create")` → `hub_list_devices(filter='virtual')` (or `hub_get_device`) → `hub_call_device_command` → `hub_get_device_attribute` → `hub_manage_virtual_device(action="delete")`.
**Equivalent to**: T81

#### T297 — Room management end-to-end

```json
{
  "test_prompt": "I want to reorganize my rooms. Show me what I have now, then add a new one called 'BAT NL Room Flow'. Show me its details, then rename it to 'BAT NL Room Renamed' because I changed my mind. Actually, I don't need it at all — get rid of it."
}
```

**Expected**: `hub_list_rooms` → `hub_create_room` → `hub_get_room` → `hub_update_room` → `hub_delete_room`.
**Equivalent to**: T82

#### T298 — Full diagnostic workup

```json
{
  "test_prompt": "I want a comprehensive health report on my smart home. Tell me how the hub is performing, whether any devices seem dead or unresponsive, show me any warnings or errors from the system logs, and check the internal diagnostic logs too. Give me a summary of each."
}
```

**Expected**: `hub_get_metrics` + `hub_get_device_health` + `hub_get_logs` + `hub_get_debug_logs`.
**Equivalent to**: T83

#### T299 — Complete smart home inventory

```json
{
  "test_prompt": "I want a complete inventory of my entire smart home setup. Tell me about my rooms, any simulated devices, what custom values are stored, what files are on the hub, any code backups, the hub's overall health, and whether any real devices have gone offline. Give me a one-line summary for each area."
}
```

**Expected**: `hub_list_rooms` + `hub_list_devices(filter='virtual')` + `hub_list_variables` + `hub_list_files` + `hub_list_backups` + `hub_get_info` + `hub_get_device_health`.
**Equivalent to**: T84

#### T300 — Motion-activated light from scratch

```json
{
  "test_prompt": "I want to build a motion-activated light from scratch. Set up a fake motion sensor called 'BAT NL Motion' and a fake light called 'BAT NL Light'. Then create an automation called 'BAT NL Motion Light' that turns the light on when motion is detected and turns it off 30 seconds later — mark it as a test rule. Show me what you built and simulate it firing.",
  "teardown_prompt": "Delete the rule 'BAT NL Motion Light'. Delete both virtual devices 'BAT NL Motion' and 'BAT NL Light'."
}
```

**Expected**: `hub_manage_virtual_device` (x2) → `hub_create_custom_rule` → `hub_get_custom_rule` → `hub_test_custom_rule`.
**Equivalent to**: T85

#### T301 — Variable round-trip

```json
{
  "test_prompt": "Let me test that variable storage works properly. Save 'step1' in something called 'bat_nl_flow', read it back to confirm, change it to 'step2', read it again, then show me where it sits among all the other stored values.",
  "teardown_prompt": "Set variable 'bat_nl_flow' to empty string."
}
```

**Expected**: `hub_set_variable` → `hub_get_variable` → `hub_set_variable` → `hub_get_variable` → `hub_list_variables`.
**Equivalent to**: T86

---

## Excluded Tests (Destructive — Manual Only)

These operations are too destructive for automated testing. Test manually with extreme caution:

| Operation | Tool | Gateway | Why Excluded |
|-----------|------|---------|--------------|
| Reboot hub | `hub_reboot` | hub_manage_destructive_ops | 1-3 min downtime, kills automations |
| Shutdown hub | `hub_shutdown` | hub_manage_destructive_ops | Requires physical restart |
| Z-Wave repair | `hub_call_zwave` (repair action) | hub_manage_radio | 5-30 min, devices unresponsive |
| Delete real device | `hub_delete_device` | hub_manage_destructive_ops | Permanent, no undo |
| Destructive radio op (reset/wipe, firmware) | `hub_call_destructive_radio` | hub_manage_destructive_ops | Orphans paired devices, no undo |
| Install app | `hub_create_app` | hub_manage_code | Modifies hub code |
| Install driver | `hub_create_driver` | hub_manage_code | Modifies hub code |
| Update app code | `hub_update_app` | hub_manage_code | Modifies production code |
| Update driver code | `hub_update_driver` | hub_manage_code | Modifies production code |
| Delete app/driver/library | `hub_delete_item` (type: app\|driver\|library) | hub_manage_code | Permanent code removal |
| Restore item backup | `hub_restore_backup` | hub_manage_backup | Overwrites current code |
| Set HSM | `hub_set_hsm` | core | Changes security system state |
| Manage Mode | `hub_manage_mode` | core | Create/rename/delete/activate a mode (delete is destructive + confirm-gated) |

---

## Test Execution Checklist

### Prerequisites
- [ ] Hubitat hub accessible on local network
- [ ] MCP server installed and configured
- [ ] OAuth endpoint URL and token available
- [ ] the Read master **enabled** (for T35-T55, T83, T116)
- [ ] the Write master **enabled** (for virtual device/room create/delete tests)
- [ ] A recent hub backup exists (for the Write master operations)
- [ ] AI client connected (Claude Code, Claude Desktop, claude.ai, or other)

### Running Tests
1. Run each test scenario as a **fresh AI session** (new conversation)
2. Setup → Test → Teardown all happen in the **same session**
3. Record metrics per the tracking table
4. For Section 8 (Comparison): run same prompts on v0.7.7 and v0.8.0
5. For Section 10 (NL Discovery): compare results with the equivalent explicit tests from earlier sections — same tool should be triggered, but the LLM must discover it without hints

### Results Template

| Test | Pass/Fail | Tools Called | Gateway Used | Turns | Duration | Notes |
|------|-----------|-------------|--------------|-------|----------|-------|
| T01  |           |             |              |       |          |       |

---

## Coverage Summary

### By Section

| Section | Tests | Purpose |
|---------|-------|---------|
| 1. Core Tools | T01-T19i | 11 flat core tools work directly |
| 2. Gateway Discovery | T20-T31, T35-T59 | LLM finds all proxied tools without hints |
| 3. Gateway Behavior | T60-T65 | Catalog mode, skip-catalog, errors |
| 4. Natural Language | T70-T79 | Casual prompts route correctly |
| 5. Multi-Step Workflows | T80-T86 | Complex multi-tool/gateway scenarios |
| 6. Ambiguity | T90-T96 | Correct routing under ambiguous prompts |
| 7. Edge Cases | T100-T108 | Error handling and boundary conditions |
| 8. Comparison | T110-T116 | v0.7.7 vs v0.8.0 regression |
| 9. Stress | T120-T122 | Many calls, rapid cycles, pagination |
| 10. NL Discovery | T200-T301 | Conversational prompts — no tool names |
| 12. Developer Mode | T219-T226, T660-T662 | Self-administration: hub_update_mcp_settings (incl. selectedDevices device-access scope) + hub_delete_variable |
| 13. Driver Code Lifecycle | T400-T406 | hub_create_driver (single + bulk), hub_update_driver (bulk), delete |
| 14. Library Management | T500-T508 | Library CRUD: install, update, delete, hub_get_source |

### Architecture (post installed-apps + RM interop + Developer Mode + PR1B read/write split)

| Component | Count |
|-----------|-------|
| Flat core tools on `tools/list` | 13 |
| Gateways on `tools/list` | 23 |
| Total visible on `tools/list` | 36 |
| Total distinct tools in codebase | 117 |

**8 read gateways**: `hub_read_apps_code` (11), `hub_read_devices` (4), `hub_read_diagnostics` (9), `hub_read_files` (2), `hub_read_rooms` (2), `hub_read_rules` (6), `hub_read_variables` (3), `hub_read_dashboards` (2)

**15 manage gateways**: `hub_manage_backup` (4), `hub_manage_code` (10), `hub_manage_custom_rules` (8), `hub_manage_dashboards` (6), `hub_manage_destructive_ops` (4), `hub_manage_devices` (7), `hub_manage_diagnostics` (7), `hub_manage_files` (4), `hub_manage_logs` (6), `hub_manage_mcp` (1), `hub_manage_native_rules_and_apps` (11), `hub_manage_radio` (6), `hub_manage_rooms` (5), `hub_manage_rule_machine` (11), `hub_manage_variables` (8)

**13 flat core tools**: `hub_manage_virtual_device`, `hub_get_tool_guide`, `hub_report_issue`, `hub_search_tools`, `hub_get_info`, `hub_list_modes`, `hub_manage_mode`, `hub_set_mode_manager`, `hub_get_hsm_status`, `hub_set_hsm`, `hub_set_system_settings`, `hub_update_firmware`, `hub_create_backup`

### Tool Coverage (non-destructive tools only)

All 117 distinct tools are covered by at least one test, excluding the destructive operations listed in the Excluded Tests table. Safe tools have standalone test coverage; destructive tools are documented for manual-only testing.

Sections 1-9 use explicit or semi-explicit tool references. Section 10 re-tests the same tool coverage through purely conversational language to measure whether the LLM can discover tools without being told which ones exist. Section 11 covers the built-in app integration tools.

**Total: 262 test scenarios** (124 explicit + 65 natural language + 21 built-in-app integration + 9 library management + 2 reveal-walker coverage + 3 deviceId normalization + 1 subExpression rejection + 1 reveal-fallback sentinel + 1 compareToDevice fallback + 1 Between-two-times sunrise/sunset + 10 periodic-frequency completeness + 3 Visual Rules Builder + 1 device swap + 2 installed-app read modes + 2 enum-attribute state-change comparator + 4 replaceRequiredExpression in-place RE replace + 3 rule-local variable lifecycle/namespace + 5 read-side convergence + 1 multi-device convergence + 3 MCP device-access scope) plus 13 excluded destructive operations documented for manual testing

---

## Section 11: Built-in App Integration Tests

Tools in this section have mixed gate requirements. `hub_list_apps` (scope=instances) and `hub_list_device_dependents` require the Read master. `hub_get_app_config` and `hub_list_app_pages` require the Read master. `hub_manage_native_rules_and_apps` tools require the Read master; CRUD tools additionally require the Write master. Tests assume at least one Rule Machine rule and at least one Room Lighting or other multi-app configuration exists on the hub.

### Safety Rules for Section 11

- Tests are **read-only or reversibly-trigger** — no create/modify/delete of RM rules or RL instances (platform blocks that anyway)
- `hub_call_rule`, `hub_set_rule_paused`, `hub_set_rule_private_boolean` tests must target a BAT-created or explicitly user-identified rule, NEVER a random production rule
- Tests skip entirely if the Read master is disabled — that's the expected behavior of `the Read master gate()`

### T200 — List installed apps (default)

```json
{
  "test_prompt": "List all installed apps on my hub."
}
```

**Expected**: Calls `hub_read_apps_code` with `tool='hub_list_apps', args={scope:'instances'}` (via gateway). Returns list with at least one entry; each entry has id, name, type, disabled, user, parentId, hasChildren. AI reports the count.

### T201 — List only built-in apps

```json
{
  "test_prompt": "Show me only the built-in Hubitat apps, not my custom ones."
}
```

**Expected**: Calls `hub_list_apps` with `scope='instances', filter='builtin'`. Returns apps where `user` is false. AI doesn't show user-installed apps like Ecobee or Awair.

### T202 — List Rule Machine rules

```json
{
  "test_prompt": "What Rule Machine rules do I have?"
}
```

**Expected**: Calls `hub_manage_native_rules_and_apps.hub_list_rules`. Returns list with ids and labels. AI reports count. If Rule Machine is not installed, AI gracefully reports "none found" or equivalent.

### T203 — Find apps using a device

```json
{
  "setup_prompt": "List devices briefly so I can see IDs.",
  "test_prompt": "Which apps are using device ID {first_device_id}?"
}
```

**Expected**: Calls `hub_list_device_dependents` with valid deviceId. Returns `appsUsing` array. AI lists app names/labels.

### T204 — Find apps using a fake device (error handling)

```json
{
  "test_prompt": "Which apps are using device ID 99999999?"
}
```

**Expected**: Tool returns `{success: false}` or similar error. AI reports device not found — does NOT fabricate results.

### T205 — Gateway catalog discovery (hub_read_apps_code)

```json
{
  "test_prompt": "What can the hub_read_apps_code gateway do?"
}
```

**Expected**: AI calls `hub_read_apps_code` with no args, sees catalog of 9 tools (`hub_list_apps`, `hub_list_drivers`, `hub_get_source`, `hub_list_backups`, `hub_get_backup`, `hub_list_device_dependents`, `hub_get_app_config`, `hub_list_app_pages`, `hub_list_hpm_packages`) with full parameter schemas. The installed-apps listing is reached via `hub_list_apps` with `scope='instances'`.

### T206 — Gateway catalog discovery (hub_manage_native_rules_and_apps)

```json
{
  "test_prompt": "What Rule Machine operations does the MCP support?"
}
```

**Expected**: AI calls `hub_manage_native_rules_and_apps` with no args, sees 11 tools. AI describes them (list/run/pause/set_boolean + create/update/delete/clone/export/hub_import_native_app + hub_get_rule_health).

### T207 — AI uses native RM rule creation via hub_manage_native_rules_and_apps

```json
{
  "test_prompt": "Create a new Rule Machine rule named 'BAT-Motion-Light' that turns on Kitchen Light when motion is detected."
}
```

**Expected**: AI calls `hub_manage_rule_machine.hub_set_rule` (name only, no appId) to create the rule, then `hub_set_rule(appId=N, ...)` to add the motion trigger and switch action. Returns the new appId. Does NOT fall back to `hub_create_custom_rule` (that creates an MCP-engine rule, not a native RM rule).

### T208 — AI correctly refuses Room Lighting creation

```json
{
  "test_prompt": "Create a new Room Lighting instance for the Study."
}
```

**Expected**: AI attempts `hub_manage_native_rules_and_apps.hub_set_native_app` (appType=room_lighting) for Room Lighting. Since Room Lighting is not yet in the `_appTypeRegistry`, the tool returns an error listing supported appTypes. AI relays the error and suggests using the native UI. Does not fabricate a fake result.

### T209 — Pause and resume an RM rule (reversible)

```json
{
  "setup_prompt": "List Rule Machine rules and identify one labeled with 'BAT' prefix, or if none exists, ask the user to identify a safe test rule.",
  "test_prompt": "Pause the rule, wait for me to confirm, then resume it.",
  "teardown_prompt": "Verify the rule is back in its original enabled/disabled state."
}
```

**Expected**: Calls `hub_set_rule_paused` with `value=true` (pauses) → confirms with user → calls `hub_set_rule_paused` with `value=false` (resumes). Both return `{success: true}`.

**WARNING**: Only runs if user has a BAT-prefixed RM rule OR explicitly identifies a safe rule. Never use a production rule.

### T209b — Run actions on an RM rule (bypasses conditions)

```json
{
  "setup_prompt": "List Rule Machine rules and identify one labeled with 'BAT' prefix whose actions are safe to fire at any time.",
  "test_prompt": "Run just the actions of that rule (skip the trigger/condition evaluation).",
  "teardown_prompt": "Verify the actions executed by checking the logs or the affected device states."
}
```

**Expected**: Calls `hub_call_rule` with `action='actions'`. Returns `{success: true, rmAction: 'runRuleAct'}`. AI reports success and any downstream device state changes.

**WARNING**: Only use a BAT-prefixed rule whose actions are idempotent/reversible. Running arbitrary production rule actions could toggle locks or change HVAC.

### T209c — Set an RM rule's private boolean

```json
{
  "setup_prompt": "Identify a BAT-prefixed RM rule that uses Private Boolean in its conditions.",
  "test_prompt": "Set the rule's private boolean to true, then set it back to false.",
  "teardown_prompt": "Verify the rule's boolean is in its original state."
}
```

**Expected**: Calls `hub_set_rule_private_boolean` with `value=true`, then `value=false`. Both return `{success: true, rmAction: 'setRuleBooleanTrue'}` and `{rmAction: 'setRuleBooleanFalse'}` respectively.

### T210 — Filter for disabled apps

```json
{
  "test_prompt": "List all installed apps that are currently disabled or paused."
}
```

**Expected**: Calls `hub_list_apps` with `scope='instances', filter='disabled'`. AI reports the count and names.

### T211 — Read master disabled (built-in rule reads blocked)

```json
{
  "setup_prompt": "For this test, assume the Read master is disabled in MCP settings.",
  "test_prompt": "List my Rule Machine rules."
}
```

**Expected**: `hub_list_rules` returns `IllegalArgumentException: Read tools are disabled...`. AI reports the Read master requirement and points user to the MCP app settings page.

### T212 — Read an installed app's config page (hub_get_app_config — hub_read_apps_code gateway)

```json
{
  "setup_prompt": "the Read master is enabled. Use hub_list_apps (scope=instances) to find any Rule Machine rule and note its app ID.",
  "test_prompt": "Show me the configuration of that Rule Machine rule — what conditions and actions does it have?",
  "teardown_prompt": "No teardown needed — hub_get_app_config is read-only."
}
```

**Expected**: AI first calls `hub_list_apps` (scope=instances, or `hub_list_rules`) to discover a Rule Machine app ID, then calls `hub_read_apps_code(tool='hub_get_app_config', args={appId: <discovered_id>})`. Returns `app` (label, type), `page` (sections with inputs showing configured triggers, conditions, actions). AI summarizes the rule's behavior from the structured response. Tool is accessed via the `hub_read_apps_code` gateway.

### T213 — hub_get_app_config multi-page (HPM full package list)

```json
{
  "setup_prompt": "Use hub_list_apps (scope=instances) to find the Hubitat Package Manager app ID.",
  "test_prompt": "Use hub_get_app_config to list ALL packages installed via Hubitat Package Manager."
}
```

**Expected**: AI calls `hub_list_apps` (scope=instances) to discover the HPM app ID, then calls `hub_get_app_config(appId=<discovered_id>, pageName='prefPkgUninstall')`. Returns the full installed-package enum. AI extracts and lists package names. Note: `pageName='prefPkgModify'` returns only the modifiable subset (those with optional components) -- the correct page for the FULL list is `prefPkgUninstall`.

### T214 — hub_get_app_config with includeSettings=true (power user)

```json
{
  "setup_prompt": "Use hub_list_rules or hub_list_apps (scope=instances) to find an existing Rule Machine rule and note its app ID.",
  "test_prompt": "Get the raw settings map for that Rule Machine rule — include all internal keys."
}
```

**Expected**: AI discovers a Rule Machine app ID, then calls `hub_get_app_config(appId=<discovered_id>, includeSettings=true)`. Response includes `settings` map with raw key-value pairs. AI notes the size and encoding (e.g., RM 5.1 dm~ prefixes).

### T215 — hub_get_app_config invalid (non-numeric appId)

```json
{
  "test_prompt": "Try to get the app config for app ID 'abc123' and tell me what error you get."
}
```

**Expected**: `hub_get_app_config` throws `IllegalArgumentException` with a message about numeric appId. AI reports the validation error and asks for a valid numeric ID.

### T216 — hub_get_app_config the Read master disabled

```json
{
  "setup_prompt": "For this test, assume the Read master is disabled in MCP settings.",
  "test_prompt": "Get the configuration for any Rule Machine rule (any app ID will do)."
}
```

**Expected**: `hub_get_app_config` throws `IllegalArgumentException: the Read master access is disabled...`. AI reports the gate requirement and directs user to enable it in MCP app settings.

### T217 — hub_list_app_pages for HPM (discover sub-page names)

```json
{
  "setup_prompt": "the Read master is enabled. Use hub_list_apps (scope=instances) to find the Hubitat Package Manager app and note its app ID.",
  "test_prompt": "I want to inspect HPM's configuration but I don't know the page names. Use hub_list_app_pages to discover what pages are available for the HPM app you just found."
}
```

**Expected**: AI uses the HPM app ID discovered in setup, then calls `hub_read_apps_code(tool='hub_list_app_pages', args={appId: <discovered_id>})`. Returns a `pages` list including at least `prefOptions`, `prefPkgUninstall`, `prefPkgModify`, `prefPkgInstall`, `prefPkgMatchUp`. AI lists the available page names and explains their roles (e.g. prefPkgUninstall = full installed-package list). Tool is accessed via the `hub_read_apps_code` gateway.

### T218 — hub_list_app_pages for Rule Machine rule (single-page confirmation)

```json
{
  "setup_prompt": "the Read master is enabled. Use hub_list_rules to find an existing Rule Machine rule and note its app ID.",
  "test_prompt": "What pages are available for the Rule Machine rule you just found?"
}
```

**Expected**: AI uses the Rule Machine app ID discovered in setup, then calls `hub_read_apps_code(tool='hub_list_app_pages', args={appId: <discovered_id>})`. Returns a `pages` list with a single entry `{name: 'mainPage', role: 'primary'}` plus a `note` confirming rules are single-page. AI explains there is only one page (mainPage) and no sub-pages are available.

### T219 — hub_get_custom_rule on a Rule Machine rule ID (redirect hint)

```json
{
  "setup_prompt": "the Read master is enabled. Use hub_list_rules or hub_list_apps (scope=instances) to find an existing Rule Machine rule and note its app ID (not an MCP rule ID).",
  "test_prompt": "Call hub_get_custom_rule with the Rule Machine app ID you just found and tell me what error message you receive.",
  "teardown_prompt": "No teardown needed."
}
```

**Expected**: `hub_get_custom_rule` throws `IllegalArgumentException` containing both "Rule not found: <id>" and a redirect hint like:
> "Rule <id> is a Hubitat built-in Rule-5.1 app. Use `hub_read_apps_code -> hub_get_app_config(appId=<id>)` to read its configuration."

AI should:
1. Report the full error including the redirect hint
2. Recognize that `hub_get_custom_rule` only handles MCP's own rule engine
3. Follow the hint by calling `hub_read_apps_code(tool='hub_get_app_config', args={appId: <id>})` to read the rule's configuration

This scenario validates that an agent landing on the wrong tool is efficiently redirected to the correct one in a single round-trip rather than wasting 3-4 tool calls.

**Failure modes to flag:**
- AI receives a redirect hint but ignores it and keeps calling `hub_get_custom_rule` variants
- AI receives "Rule not found" with no redirect hint (suggests `enableBuiltinApp` is disabled and the `/hub2/appsList` lookup couldn't run -- check hub settings)
- AI follows the hint successfully but reports confusion about read vs write capabilities

---

## Section 12: Developer Mode Tests

These tests exercise the Developer Mode self-administration surface — the `hub_manage_mcp` gateway (`hub_update_mcp_settings`, including its `selectedDevices` device-access scope key) and the `hub_delete_variable` op on `hub_manage_variables`. Both require opt-in toggles in the MCP rule app settings page (`enableDeveloperMode` for the `hub_manage_mcp` tools, `enableHubAdminWrite` for both). Each successful Developer Mode write is logged at WARN level for audit.

**Pre-flight (manual one-time):**
1. In the MCP rule app settings, enable **the Write master** (with confirmation), and create a hub backup.
2. In the same settings page, enable **Enable Developer Mode Tools** (you'll see a warning banner).
3. Click Done.

### T219 — hub_update_mcp_settings refuses when Developer Mode toggle is OFF

```json
{
  "setup_prompt": "First, manually disable 'Enable Developer Mode Tools' in the MCP rule app settings (UI). Confirm the Write master is still enabled and a recent backup exists.",
  "test_prompt": "Use hub_update_mcp_settings to change mcpLogLevel to warn."
}
```

**Expected**: Tool returns an `isError: true` MCP response with a message containing "Developer Mode tools are disabled" and pointing the user to the toggle. No setting is written. AI surfaces the message and asks the user to enable the toggle in the UI before retrying.

### T220 — hub_update_mcp_settings flips a boolean setting end-to-end

```json
{
  "setup_prompt": "Developer Mode is enabled, the Write master is enabled, recent backup exists. Note the current value of debugLogging via hub_get_info or by reading state.",
  "test_prompt": "Use hub_update_mcp_settings to set debugLogging to true. Then verify the change took effect."
}
```

**Expected**: AI calls `hub_manage_mcp(tool='hub_update_mcp_settings', args={settings:{debugLogging:true}, confirm:true})`. Result: `{success:true, updated:{debugLogging:true}, message:"Updated 1 setting(s)..."}`. AI verifies via a follow-up read (e.g., the value persists across a subsequent call). Hub log shows a WARN-level `[developer-mode]` audit line.

### T221 — hub_update_mcp_settings rejects a setting outside the allowlist

```json
{
  "setup_prompt": "Developer Mode is enabled and the Write master is enabled.",
  "test_prompt": "Use hub_update_mcp_settings to set enableHubAdminWrite to false."
}
```

**Expected**: Tool returns an MCP error (`-32602`) with a message: `Setting 'enableHubAdminWrite' is not allowed for self-modification via hub_update_mcp_settings. Allowed: ...` listing the allowlisted keys. No change is made. AI explains that this setting is intentionally excluded as a footgun (would lock the agent out of its own write path) and offers to walk the user through the UI toggle.

### T222 — hub_update_mcp_settings batches multiple settings atomically

```json
{
  "setup_prompt": "Developer Mode is enabled and the Write master is enabled.",
  "test_prompt": "Use hub_update_mcp_settings to set both enableHubAdminRead=true and enableBuiltinApp=true in a single call."
}
```

**Expected**: AI passes both keys in one `settings` map. Result: `{success:true, updated:{enableHubAdminRead:true, enableBuiltinApp:true}, message:"Updated 2 setting(s)..."}`. Both settings persist. The all-or-nothing pre-validation means a single bad key would have rejected the entire batch before any write — verifiable by re-running with one good and one bad key and confirming neither was applied.

### T223 — hub_update_mcp_settings includes a reconnect hint after toggling enable* flags

```json
{
  "setup_prompt": "Developer Mode is enabled.",
  "test_prompt": "Use hub_update_mcp_settings to flip enableCustomRuleEngine to false, then back to true."
}
```

**Expected**: Both calls return success. The `message` field on each response contains "MCP clients ... may need to reconnect to refresh cached tool schemas". AI surfaces this hint to the user explaining that tools/list won't reflect the toggle until the client reconnects.

### T223b — hub_update_mcp_settings flips useGateways (gateway-mode self-switch)

```json
{
  "setup_prompt": "Developer Mode is enabled, the Write master is enabled, recent backup exists. Note whether the server is currently in gateway mode or flat mode (useGateways).",
  "test_prompt": "Use hub_update_mcp_settings to flip useGateways to the OPPOSITE of its current value. Report the response message, then explain what the user must do for the new tool surface to take effect.",
  "teardown_prompt": "Use hub_update_mcp_settings to set useGateways back to its original value, then reconnect (/mcp refresh) so the tool list matches the server again."
}
```

**Expected**: AI calls `hub_manage_mcp(tool='hub_update_mcp_settings', args={settings:{useGateways:<flipped>}, confirm:true})`. The key is **accepted** (NOT rejected as outside the allowlist — this is the regression guard for the dev-mode gateway self-switch), result `{success:true, updated:{useGateways:<flipped>}, message:"...may need to reconnect to refresh cached tool schemas if you toggled an enable* flag, useGateways, or publishOutputSchemas."}`. The WARN `[developer-mode]` audit line fires. AI explains the client must reconnect (`/mcp refresh`) before tools/list reflects the new gateway-vs-flat surface. Teardown restores the original value.

### T223c — hub_update_mcp_settings flips publishOutputSchemas (outputSchema opt-in, #290)

```json
{
  "setup_prompt": "Developer Mode is enabled, the Write master is enabled, recent backup exists, and the server is in gateway mode (useGateways ON). publishOutputSchemas is OFF (its default).",
  "test_prompt": "Use hub_update_mcp_settings to set publishOutputSchemas to true. Report the response message, then explain what changes about the advertised tool list and why a strict client like Claude Desktop could be affected.",
  "teardown_prompt": "Use hub_update_mcp_settings to set publishOutputSchemas back to false, then reconnect (/mcp refresh) so the advertised tool schema matches the server again."
}
```

**Expected**: AI calls `hub_manage_mcp(tool='hub_update_mcp_settings', args={settings:{publishOutputSchemas:true}, confirm:true})`. The key is **accepted** (NOT rejected as outside the allowlist — the regression guard for the #290 opt-in toggle), result `{success:true, updated:{publishOutputSchemas:true}, message:"...may need to reconnect to refresh cached tool schemas if you toggled an enable* flag, useGateways, or publishOutputSchemas."}`. The WARN `[developer-mode]` audit line fires. AI explains that with the toggle ON, gateway-mode base tools and the gateway catalog advertise `outputSchema` again, so strict MCP clients (e.g. Claude Desktop) that require `structuredContent` will then reject tool calls with JSON-RPC -32600 — which is exactly why it defaults OFF. Teardown restores publishOutputSchemas=false.

### T224 — hub_delete_variable removes a stale rule_engine variable

```json
{
  "setup_prompt": "the Write master is enabled, recent backup exists. Use hub_set_variable to create a temporary variable named BAT_E2E_DELETE_TEST with value 'scratch'.",
  "test_prompt": "We don't need BAT_E2E_DELETE_TEST any more. Use hub_delete_variable to remove it, then confirm via hub_get_variable that it's gone."
}
```

**Expected**: AI calls `hub_manage_variables(tool='hub_delete_variable', args={name:'BAT_E2E_DELETE_TEST', confirm:true})`. Result: `{success:true, name:'BAT_E2E_DELETE_TEST', deleted:true, source:'rule_engine', previousValue:'scratch'}`. Follow-up `hub_get_variable` returns "Variable not found". Hub log shows a WARN-level `[developer-mode] hub_delete_variable: removed 'BAT_E2E_DELETE_TEST'` audit entry.

### T225 — hub_delete_variable refuses connector-namespace variables with redirect hint

```json
{
  "setup_prompt": "the Write master is enabled. Manually create a Hub Variable named TEST_CONNECTOR_VAR via Settings → Hub Variables UI (this lives in the connector namespace, not rule_engine).",
  "test_prompt": "Use hub_delete_variable to remove TEST_CONNECTOR_VAR."
}
```

**Expected**: Tool returns MCP error (`-32602`) with message: `Variable 'TEST_CONNECTOR_VAR' not found in rule_engine namespace. (Connector-namespace deletion not yet supported via MCP — use Settings → Hub Variables UI.)`. AI surfaces the hint and offers to walk the user through the UI deletion. The variable is unchanged.

### T226 — hub_delete_variable refuses without confirm flag (the Write master 24h gate)

```json
{
  "setup_prompt": "the Write master is enabled, recent backup exists. Use hub_set_variable to create BAT_E2E_NO_CONFIRM with value 'safe'.",
  "test_prompt": "Use hub_delete_variable to remove BAT_E2E_NO_CONFIRM. Don't pass confirm — let's see what happens."
}
```

**Expected**: Gateway parameter validation returns an `isError: true` result with message `"Missing required parameter(s): confirm"` and a `parameters` description listing the schema (name, confirm, force). No JSON-RPC error code is set — the validation happens at the gateway layer before tool dispatch. The variable is preserved. AI relays the gate requirement and offers to retry with `confirm=true`.

### T660 — hub_update_mcp_settings selectedDevices re-scopes the MCP server's device access (add then remove, net no-op)

```json
{
  "setup_prompt": "Developer Mode is enabled, the Write master is enabled, recent backup exists. List every hub device with hub_list_devices(scope='all') and pick one device that is currently mcpAuthorized=false (NOT already in the MCP scope). Note the full set of currently-authorized device IDs.",
  "test_prompt": "Add that unauthorized device to the MCP server's device-access scope with hub_update_mcp_settings({settings:{selectedDevices:{mode:'add', ids:['<id>']}}}), confirm it now reads back as authorized via hub_list_devices(scope='all'), then remove it again (mode:'remove') so the scope returns to exactly what it was.",
  "teardown_prompt": "Verify via hub_list_devices(scope='all') that the authorized set matches the original noted set (the add+remove was a net no-op)."
}
```

**Expected**: The add call is `hub_manage_mcp(tool='hub_update_mcp_settings', args={settings:{selectedDevices:{mode:'add', ids:['<id>']}}, confirm:true})` → `{success:true, selectedDevices:{mode:'add', authorizedDeviceIds:[...], authorizedCount:N+1, added:['<id>'], removed:[]}, ...}`. The device then shows `mcpAuthorized=true` in `hub_list_devices(scope='all')`. The remove call (`mode:'remove'`, same id) returns `{success:true, selectedDevices:{mode:'remove', added:[], removed:['<id>'], authorizedCount:N}, ...}`, and the device reads back `mcpAuthorized=false`. A WARN `[developer-mode] hub_update_mcp_settings selectedDevices: applied ...` audit line fires for each. Net effect: the authorized set is unchanged.

### T661 — hub_update_mcp_settings selectedDevices rejects an unknown device id (atomic, nothing changed)

```json
{
  "setup_prompt": "Developer Mode is enabled, the Write master is enabled, recent backup exists.",
  "test_prompt": "Try to add device id 999999999 (which does not exist on this hub) to the MCP device-access scope via hub_update_mcp_settings({settings:{selectedDevices:{mode:'add', ids:['999999999']}}})."
}
```

**Expected**: Tool returns an MCP error (`-32602`) with a message naming the offending id, e.g. `Unknown device id(s): 999999999. Use hub_list_devices(scope='all') to see valid ids. Nothing was changed.` The authorized scope is unchanged. AI surfaces the message and offers to look up valid ids via `hub_list_devices(scope='all')`.

### T662 — hub_update_mcp_settings selectedDevices refuses to empty the scope without allowEmpty

```json
{
  "setup_prompt": "Developer Mode is enabled, the Write master is enabled, recent backup exists. Note the currently-authorized device IDs.",
  "test_prompt": "Use hub_update_mcp_settings({settings:{selectedDevices:{mode:'replace', ids:[]}}}) with an empty id list (do NOT pass allowEmpty). This should be refused."
}
```

**Expected**: Tool returns an MCP error (`-32602`) explaining the self-lockout guard, e.g. `Refusing to empty the MCP device-access scope: ... Pass selectedDevices.allowEmpty:true to confirm you intend to clear the scope.` The scope is unchanged. AI explains that an empty scope blinds the server to every selected device and that `allowEmpty:true` is required to proceed.

---

## Section 12b: Permission Masters & Per-tool Overrides (#113 / #114)

These tests exercise the universal **Read** and **Write** master toggles and the **Advanced: Per-tool Overrides** deny-only sub-page. Both masters default ON; only an explicit OFF removes that whole tool class from the MCP client and rejects any cached call. The Advanced overrides apply *below* the masters — they can only turn things OFF, never re-enable something a master already hid. A disabled tool/gateway disappears from `tools/list` and `hub_search_tools` but stays documented in `hub_get_tool_guide`.

**Run constraints (read before running this section):**
- **Gateway mode only** (`useGateways=true`). The visibility assertions below describe the gateway catalog shape.
- **Serialize all RM writes** — never run rule mutations in parallel (shared `atomicState`).
- **No Z-Wave / Zigbee operations** — these scenarios only read tool catalogs, control BAT-created virtual devices, and toggle app settings. They never touch a radio.
- Each master toggle is **UI-only to flip** (`enableRead` is allowlisted via `hub_update_mcp_settings` for self-toggle, but for a clean BAT use the app settings UI and reconnect the client so `tools/list` refreshes). After any master/override change, **reconnect the MCP client** (`/mcp refresh`) before the test_prompt so the cached tool list reflects the new surface.
- **Restore preconditions in teardown**: Read master ON, Write master ON, Advanced overrides cleared (use the **Reset all overrides** button on the Advanced page).

### T230 — Read master OFF hides every read tool from the client

```json
{
  "setup_prompt": "In the MCP rule app settings UI, turn the **Read** master toggle OFF (leave **Write** ON), click Done, then reconnect the MCP client so tools/list refreshes.",
  "test_prompt": "List my hub's location modes, then list my rooms. If you can't find a tool to do that, say so and explain why.",
  "teardown_prompt": "In the app settings UI, turn the **Read** master back ON, click Done, and reconnect the MCP client."
}
```

**Expected**: With Read OFF, pure-read gateways (`hub_read_devices`, `hub_read_rooms`, `hub_read_diagnostics`, etc.) and read core tools (`hub_list_modes`) are absent from `tools/list`; the read tools inside mixed `hub_manage_*` gateways are gone too. The AI reports it has no available tool to list modes/rooms and points the user at the Read master. If it does reach a cached read tool, the call is rejected with `"Read tools are disabled..."`. AI does NOT fabricate the data.

### T231 — Write master OFF rejects device control (reads still work)

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT Master Write Switch' (Write master is ON for setup). Then, in the app settings UI, turn the **Write** master toggle OFF (leave **Read** ON), click Done, and reconnect the MCP client.",
  "test_prompt": "Turn ON the virtual switch 'BAT Master Write Switch'. Then read back its switch state and report it.",
  "teardown_prompt": "In the app settings UI, turn the **Write** master back ON, click Done, reconnect the MCP client, turn OFF 'BAT Master Write Switch', then delete the virtual device."
}
```

**Expected**: With Write OFF, `hub_call_device_command` and every other state-changing tool are hidden from `tools/list`; write-only gateways (e.g. `hub_manage_destructive_ops`) drop entirely, and mixed gateways like `hub_manage_rooms` remain but are annotated `readOnlyHint: true` with their write sub-tools gone. The AI cannot turn the switch on and reports the Write master requirement. The read-back (`hub_get_device_attribute`) still succeeds — reads are unaffected. A cached write call is rejected with `"Write tools are disabled..."`.

### T232 — Disable a single tool via Advanced ⇒ it vanishes + distinct error on cached call

```json
{
  "setup_prompt": "Confirm a cached MCP session can currently call hub_manage_mode (note the current mode first — do NOT change it). Then, in the app settings UI, open **Advanced: Per-tool Overrides**, add `hub_manage_mode` to the disabled tools list, click Done. Do NOT reconnect the client yet (we want a stale cache for the test_prompt).",
  "test_prompt": "Set my hub mode to its current mode (a no-op that still exercises the tool). Report exactly what error or response you get.",
  "teardown_prompt": "In the app settings UI, open **Advanced: Per-tool Overrides** and click **Reset all overrides** (or remove hub_manage_mode from the list), click Done, and reconnect the MCP client."
}
```

**Expected**: After a client reconnect, `hub_manage_mode` is absent from `tools/list` and from `hub_search_tools` results, but still present in `hub_get_tool_guide`. On the stale-cache call (no reconnect), the tool is rejected with a **distinct** Advanced-settings error containing `"is disabled in Advanced settings (Per-tool Overrides)"` — NOT the generic `"Write tools are disabled"` master message. AI surfaces the specific error and points the user at the Advanced overrides page.

### T233 — Disable a gateway ⇒ all its tools (including shared) vanish

```json
{
  "setup_prompt": "In the app settings UI, open **Advanced: Per-tool Overrides**, add the gateway `hub_manage_rooms` to the disabled gateways list, click Done, and reconnect the MCP client.",
  "test_prompt": "List my rooms, and then also try to create a room called 'BAT Should Not Exist'. Report which tools are available and what happens.",
  "teardown_prompt": "In the app settings UI, open **Advanced: Per-tool Overrides**, click **Reset all overrides**, click Done, reconnect the MCP client, and delete the room 'BAT Should Not Exist' if it was somehow created."
}
```

**Expected**: Disabling `hub_manage_rooms` expands to every tool it contains. `hub_manage_rooms` is absent from `tools/list`. `hub_list_rooms` — a **shared** read tool also exposed via `hub_read_rooms` — disappears from `hub_read_rooms` too (gateway disable is global per tool name), so the AI cannot list rooms from either gateway. `hub_create_room` is likewise gone. A cached call to any of them returns the `"is disabled in Advanced settings (Per-tool Overrides)"` error. AI reports the gateway override and does NOT create the room.

### T234 — Destructive tool still demands confirm + backup with Write ON

```json
{
  "setup_prompt": "Ensure the **Write** master is ON and no Advanced overrides are active (Reset all overrides). Do NOT create a fresh backup for this test — we want to exercise the backup gate.",
  "test_prompt": "Delete the hub variable 'BAT_DESTRUCTIVE_GATE' (it doesn't need to exist) — but call the delete WITHOUT a confirm flag first, then describe the safety checks that block you.",
  "teardown_prompt": "No artifacts created; nothing to clean up."
}
```

**Expected**: Even with the Write master ON, the destructive path is unchanged — `requireDestructiveConfirm` still demands `confirm=true` and a hub backup within 24h. The no-confirm call is rejected with the `"SAFETY CHECK FAILED: You must set confirm=true"` message (or, if confirm is supplied but no recent backup exists, the `"BACKUP REQUIRED"` message). AI explains the master gate and the destructive confirm+backup gate are orthogonal layers and offers to create a backup and retry with `confirm=true`.

---

## Section 13: Driver Code Lifecycle Tests (hub_manage_code)

All tests below require the Write master enabled and a recent backup. Tests are excluded from the auto-exercise sweep (destructive to hub code). Test artifacts use the `BAT_` name prefix per BAT convention so they can be identified and cleaned up independently.

### T400 — hub_create_driver via sourceFile (File Manager path)

```json
{
  "setup_prompt": "the Write master enabled, recent backup exists. Write a minimal driver stub to File Manager: hub_write_file(fileName='bat-test-driver.groovy', content='metadata { definition(name: \"BAT_DriverCodeLifecycle\", namespace: \"bat\", author: \"test\") { } }').",
  "test_prompt": "Install the driver using hub_create_driver with sourceFile='bat-test-driver.groovy' and confirm=true. Report the new driver ID.",
  "teardown_prompt": "Delete the driver installed in this test using hub_delete_item (type=driver) with the driverId returned above and confirm=true. Also delete the File Manager file bat-test-driver.groovy using hub_delete_file."
}
```

**Expected**: Tool resolves the file from File Manager, POSTs to `/driver/save`, fetches the new driver back to verify it compiled, and returns `success: true` with `driverId` set and `sourceMode: 'sourceFile'`. No inline source was sent in the install call -- the source came from File Manager.

### T401 — hub_create_driver compile failure detected (post-install verification)

```json
{
  "setup_prompt": "the Write master enabled, recent backup exists. This test intentionally installs broken Groovy source -- the hub creates a stub slot in an error state (BAT_BrokenInstallStub). Note the driver ID returned for cleanup.",
  "test_prompt": "Install a driver with deliberately broken syntax using hub_create_driver(source='this is not valid groovy {{ }}', confirm=true). Report what happens.",
  "teardown_prompt": "Delete the error-state driver slot created in this test using hub_delete_item (type=driver) with the driverId returned and confirm=true."
}
```

**Expected**: Hub creates an item slot (returns a redirect with an ID) but the post-install verification detects `status: error` from `/driver/ajax/code`. Tool returns `success: false` with the compile error message and the item ID in the response. AI reports the error and does not claim success.

### T402 — hub_update_driver bulk mode (happy path)

```json
{
  "setup_prompt": "the Write master enabled, recent backup exists. Install two minimal BAT_ driver stubs via hub_create_driver: (1) source='metadata { definition(name: \"BAT_BulkUpdate1\", namespace: \"bat\", author: \"test\") { } }' and (2) source='metadata { definition(name: \"BAT_BulkUpdate2\", namespace: \"bat\", author: \"test\") { } }'. Note both driverIds. Write updated source for each to File Manager with a version comment appended: hub_write_file(fileName='bat-bulk-1.groovy', content='...updated source...') and similarly for bat-bulk-2.groovy.",
  "test_prompt": "Update both drivers in a single call using hub_update_driver with updates=[{driverId: '<id1>', sourceFile: 'bat-bulk-1.groovy'}, {driverId: '<id2>', sourceFile: 'bat-bulk-2.groovy'}] and confirm=true.",
  "teardown_prompt": "Delete both BAT_ drivers using hub_delete_item (type=driver) with confirm=true for each driverId. Also delete the File Manager files bat-bulk-1.groovy and bat-bulk-2.groovy using hub_delete_file."
}
```

**Expected**: Tool applies both updates sequentially. Returns `success: true` with `updates` array containing two entries, each with `success: true`, `driverId`, and `sourceMode: 'sourceFile'`. `message` says "All 2 driver(s) updated successfully."

### T403 — hub_update_driver bulk mode rejects mixed single+bulk args

```json
{
  "test_prompt": "Call hub_update_driver with both driverId='123' AND updates=[{driverId: '456', sourceFile: 'f.groovy'}] and confirm=true."
}
```

**Expected**: Tool throws `IllegalArgumentException` (JSON-RPC -32602) with a message containing 'bulk mode' and 'driverId'. No update is performed.

### T404 — hub_create_driver bulk mode (happy path)

```json
{
  "setup_prompt": "the Write master enabled, recent backup exists. Write two minimal driver stubs to File Manager: hub_write_file(fileName='bat-bulk-install-1.groovy', content='metadata { definition(name: \"BAT_BulkInstall1\", namespace: \"bat\", author: \"test\") { } }') and hub_write_file(fileName='bat-bulk-install-2.groovy', content='metadata { definition(name: \"BAT_BulkInstall2\", namespace: \"bat\", author: \"test\") { } }').",
  "test_prompt": "Install both drivers in a single call using hub_create_driver with installs=[{sourceFile: 'bat-bulk-install-1.groovy'}, {sourceFile: 'bat-bulk-install-2.groovy'}] and confirm=true. Report the driver IDs returned.",
  "teardown_prompt": "Delete both BAT_ drivers installed above using hub_delete_item (type=driver) with confirm=true for each driverId. Also delete the File Manager files bat-bulk-install-1.groovy and bat-bulk-install-2.groovy using hub_delete_file."
}
```

**Expected**: Tool installs both drivers in a single call, resolving each from File Manager. Returns `success: true` with `installs` array containing two entries, each with `success: true`, a non-null `driverId`, and `sourceMode: 'sourceFile'`. `message` says "All 2 driver(s) installed successfully." No inline source was passed in the call.

### T405 — hub_create_driver bulk mode partial failure

```json
{
  "setup_prompt": "the Write master enabled, recent backup exists. Write one driver stub to File Manager: hub_write_file(fileName='bat-bulk-partial.groovy', content='metadata { definition(name: \"BAT_BulkPartial\", namespace: \"bat\", author: \"test\") { } }'). Do NOT write 'bat-bulk-missing.groovy'.",
  "test_prompt": "Install two drivers in a single bulk call: hub_create_driver with installs=[{sourceFile: 'bat-bulk-partial.groovy'}, {sourceFile: 'bat-bulk-missing.groovy'}] and confirm=true. Report what happens for each item.",
  "teardown_prompt": "Delete the successfully installed BAT_BulkPartial driver using hub_delete_item (type=driver) with confirm=true. Delete the File Manager file bat-bulk-partial.groovy using hub_delete_file."
}
```

**Expected**: Tool attempts both installs. First item succeeds (driverId returned, success: true). Second item fails because the file is absent (success: false, error contains 'not found in File Manager'). Top-level `success: false` with `message` containing '1 of 2'. Continue-on-error: first driver still installed despite second failure.

### T406 — hub_create_driver bulk mode rejects mixed single+bulk args

```json
{
  "test_prompt": "Call hub_create_driver with both sourceFile='x.groovy' AND installs=[{sourceFile: 'f.groovy'}] and confirm=true."
}
```

**Expected**: Tool throws `IllegalArgumentException` (JSON-RPC -32602) with a message containing 'bulk mode' and 'source'. No install is attempted.

---

## Section 14: Library Management Tests

Write tools (`hub_create_library`, `hub_update_library`, `hub_delete_item` with type=library) live in the `hub_manage_code` gateway and require the Write master + confirm + a hub backup within the last 24 hours. `hub_get_source` with type=library (read-only) lives in the `hub_read_apps_code` gateway and requires the Read master only. Tests use the `BAT_` prefix and clean up after themselves.

**Pre-flight (manual one-time):**
1. Enable **the Read master** and **the Write master** in MCP Rule Server settings.
2. Create a hub backup via `hub_create_backup`.

**Safety note:** Tests only create/modify/delete test-prefixed libraries. They do not touch any library with `usedByDeviceTypes` or `usedByAppTypes` populated.

### T500 — hub_get_source (type=library) reads library source

```json
{
  "setup_prompt": "Check Hubitat web UI (FOR DEVELOPERS > Libraries code) for an installed library ID, or install a test library first using hub_create_library.",
  "test_prompt": "Get the source of the first library returned by hub2/userLibraries. Use hub_read_apps_code -> hub_get_source with type=library."
}
```

**Expected**: AI calls `hub_read_apps_code(tool='hub_get_source', args={type:'library', id:'<id>'})`. Result includes `success: true`, `source` (non-empty string), `version`, `name`, `namespace`, `totalLength`. If total length exceeds 64KB, `sourceFile` and `sourceFileHint` fields are present.

### T501 — hub_create_library installs new library from inline source

```json
{
  "setup_prompt": "the Write master enabled, recent backup exists.",
  "test_prompt": "Install a new Groovy library with name='BATTestLibInstall', namespace='bat_test', description='BAT test library for install scenario'. The library should define a single method `batHelper()` that returns 'bat_ok'.",
  "teardown_prompt": "Delete the BATTestLibInstall library (find it by name in the library list)."
}
```

**Expected**: AI calls `hub_manage_code(tool='hub_create_library', args={source:'library(...) { } def batHelper() { return "bat_ok" }', confirm:true})`. Result: `{success:true, libraryId:'<id>', version:<positive integer>, sourceMode:'source', message:'Library installed successfully'}`. No `verifyWarning` or `verifyError` field if verification succeeds. `verified: true`.

### T502 — hub_update_library updates existing library source

```json
{
  "setup_prompt": "the Write master enabled, recent backup exists. Use hub_create_library to create a library named 'BATTestLibUpdate', namespace='bat_test', with method `v1Method()` returning 'v1'.",
  "test_prompt": "Update the BATTestLibUpdate library to add a `v2Method()` that returns 'v2'. Use hub_update_library with source mode.",
  "teardown_prompt": "Delete BATTestLibUpdate library."
}
```

**Expected**: AI finds the library ID, calls `hub_manage_code(tool='hub_update_library', args={libraryId:'<id>', source:'<updated source with v2Method>', confirm:true})`. Result: `{success:true, previousVersion:<N>, newVersion:<N+1>, sourceMode:'source'}` where `newVersion > previousVersion` (both positive integers). Pre-update backup appears in `hub_list_backups` as a `library_<id>` key.

### T503 — hub_update_library resave mode recompiles without external source

```json
{
  "setup_prompt": "the Write master enabled, recent backup exists. A library named 'BATTestLibResave' exists (install it if not).",
  "test_prompt": "Use hub_update_library with resave=true on BATTestLibResave to trigger recompilation without changing the source.",
  "teardown_prompt": "Delete BATTestLibResave library."
}
```

**Expected**: AI calls `hub_manage_code(tool='hub_update_library', args={libraryId:'<id>', resave:true, confirm:true})`. Result: `{success:true, sourceMode:'resave', note:'...no cloud round-trip...'}`. `newVersion` is a positive integer greater than `previousVersion`.

### T504 — hub_delete_item (type=library) deletes and auto-backs up source

```json
{
  "setup_prompt": "the Write master enabled, recent backup exists. Install a library named 'BATTestLibDelete', namespace='bat_test'.",
  "test_prompt": "Delete the BATTestLibDelete library. Confirm it no longer appears in the hub library list.",
  "teardown_prompt": "(Library is already deleted — no teardown needed.)"
}
```

**Expected**: AI calls `hub_manage_code(tool='hub_delete_item', args={type:'library', id:'<id>', confirm:true})`. Result: `{success:true, backupFile:'mcp-backup-library-<id>.groovy', restoreHint:...}`. Backup file appears in `hub_list_backups`. Subsequent `hub_get_source` (type=library) for the same ID returns `success:false` with "not found".

### T505 — hub_create_library refuses without confirm flag

```json
{
  "setup_prompt": "the Write master enabled.",
  "test_prompt": "Try to install a library with valid source but without setting confirm=true. Observe the error."
}
```

**Expected**: Gateway (or tool) returns an error (isError or -32602) containing "SAFETY CHECK FAILED" or "confirm". No library is created. AI explains the confirm requirement and the mandatory pre-flight checklist (backup).

### T506 — hub_get_source (type=library) returns error for non-existent library

```json
{
  "test_prompt": "Try to get library source for libraryId='999999'. What does the tool return?"
}
```

**Expected**: AI calls `hub_read_apps_code(tool='hub_get_source', args={type:'library', id:'999999'})`. Result: `{success:false, error:'...not found...'}` or similar. AI reports the error clearly and suggests using hub2/userLibraries or the Hubitat web UI (FOR DEVELOPERS > Libraries code) to find valid library IDs.

### T507 — hub_update_library sourceFile mode reads from File Manager

```json
{
  "setup_prompt": "the Write master enabled, recent backup exists. Install 'BATTestLibSourceFile' library. Upload a file named 'bat-lib-update.groovy' to File Manager via hub_write_file with updated library source.",
  "test_prompt": "Update BATTestLibSourceFile library using hub_update_library with sourceFile='bat-lib-update.groovy'.",
  "teardown_prompt": "Delete BATTestLibSourceFile library. Delete bat-lib-update.groovy from File Manager."
}
```

**Expected**: AI calls `hub_manage_code(tool='hub_update_library', args={libraryId:'<id>', sourceFile:'bat-lib-update.groovy', confirm:true})`. Result: `{success:true, sourceMode:'sourceFile', note:'...File Manager...'}`.

### T508 — hub_delete_item (type=library) proceeds with warning when backup fails

(Manual test -- not automatable without simulating File Manager failure.)

**Expected behavior**: If `uploadHubFile` fails during pre-delete backup, `hub_delete_item` (type=library) still proceeds and sets `backupWarning` in the response. The deletion is not blocked by a backup failure. The response message contains "WARNING: Pre-delete backup failed".

### T509 — hub_install_bundle installs a code bundle the HPM way

```json
{
  "setup_prompt": "the Write master enabled, recent backup exists. Identify the raw URL of a bundle .zip the hub can reach (e.g. this repo's bundles/mcp-libraries.zip on a public GitHub raw URL). The bundle ships the McpRoomsLib and McpBundlesLib libraries.",
  "test_prompt": "Install the bundle from that .zip URL using hub_install_bundle (confirm=true), then verify the bundle's libraries are present with hub_list_libraries.",
  "teardown_prompt": "Optionally delete a library if it was newly created (note: removing McpRoomsLib or McpBundlesLib would break the server's #include on next save, so leave them in place on the live server)."
}
```

**Expected**: AI calls `hub_manage_code(tool='hub_install_bundle', args={importUrl:'https://.../bundles/mcp-libraries.zip', confirm:true})`. Result: `{success:true, endpoint:'/bundle2/uploadZipFromUrl', message:'Bundle installed...'}` on firmware >= 2.3.8.108 (older firmware uses `/bundle/uploadZipFromUrl`). A follow-up `hub_list_libraries` shows `McpRoomsLib` and `McpBundlesLib` (namespace `mcp`). This mirrors how Hubitat Package Manager delivers a package's library files — bundle fetched + unpacked into Libraries Code server-side, no UI.

### T510 — hub_install_bundle refuses without confirm flag

```json
{
  "setup_prompt": "the Write master enabled.",
  "test_prompt": "Try to install a bundle from a .zip URL without setting confirm=true. Observe the error."
}
```

**Expected**: Gateway (or tool) returns an error (isError or -32602) containing "SAFETY CHECK FAILED" or "confirm". No bundle is installed. AI explains the confirm requirement and the mandatory pre-flight checklist (backup).

### T511 — hub_install_bundle rejects a non-http(s) importUrl

```json
{
  "setup_prompt": "the Write master enabled, recent backup exists.",
  "test_prompt": "Try to install a bundle with importUrl='mcp-libraries.zip' (a bare filename, no scheme) and confirm=true. What does the tool say?"
}
```

**Expected**: Gateway (or tool) returns an error (isError or -32602) containing "scheme must be http or https". AI recognizes that `hub_install_bundle` needs a full URL the hub can fetch, not a File Manager filename, and corrects to a raw https URL.

### T512 — hub_list_bundles lists installed code bundles

```json
{
  "setup_prompt": "the Read master enabled. The MCP libraries bundle (mcp-libraries.zip) is installed -- it ships McpRoomsLib, McpBundlesLib, and McpVisualRulesLib.",
  "test_prompt": "List the code bundles installed on the hub and tell me which libraries the mcp bundle contains."
}
```

**Expected**: AI calls `hub_read_apps_code(tool='hub_list_bundles')` (or the flat tool). Result includes a bundle with namespace `mcp` whose `contains.libraries` lists `McpRoomsLib` / `McpBundlesLib` / `McpVisualRulesLib`, each entry carrying an `id`. AI explains bundles are the Bundle-Manager containers HPM delivers code in — distinct from the Libraries Code entries (`hub_list_libraries`).

### T513 — hub_export_bundle saves a bundle .zip to the File Manager

```json
{
  "setup_prompt": "the Write master enabled. Identify an installed bundle's id with hub_list_bundles (e.g. the mcp libraries bundle).",
  "test_prompt": "Export that bundle's zip to the File Manager and tell me where to download it.",
  "teardown_prompt": "Optionally delete the exported .zip from the File Manager with hub_delete_file."
}
```

**Expected**: AI calls `hub_manage_code(tool='hub_export_bundle', args={bundleId:<id>})`. Result `{success:true, fileName:'<name>.zip', bytes:>0, directDownload:'/local/<name>.zip'}`; AI points the user at the `/local/...` URL. No `confirm` is required — export is a non-destructive write (it only creates a File Manager file).

### T514 — hub_delete_bundle removes a bundle container (DESTRUCTIVE)

```json
{
  "setup_prompt": "the Write master enabled, recent backup exists. Identify a DISPOSABLE bundle's id with hub_list_bundles -- do NOT target the required mcp libraries bundle.",
  "test_prompt": "Delete that disposable bundle by id and confirm it's gone."
}
```

**Expected**: AI calls `hub_manage_code(tool='hub_delete_bundle', args={bundleId:<id>, confirm:true})`. Result `{success:true, verified:true, bundleId:'<id>'}`. AI notes that deleting the bundle CONTAINER may leave the libraries/apps/drivers it delivered in Code (remove those separately with `hub_delete_item`). Without `confirm=true` the tool refuses with "SAFETY CHECK FAILED".

---

## Section 15: HPM Package State Tests

Tools in this section require **the Read master** and HPM itself must be installed on the hub. `hub_list_hpm_packages` lives in the `hub_read_apps_code` gateway. Tests assume at least one package has been installed via HPM.

**Pre-flight (manual one-time):**
1. Enable **the Read master** in MCP Rule Server settings.
2. Verify HPM is installed (`hub_list_apps` with scope=instances should show "Hubitat Package Manager").

### T600 — hub_list_hpm_packages: enumerate all HPM-tracked packages

```json
{
  "setup_prompt": "the Read master is enabled. HPM is installed with at least one package.",
  "test_prompt": "List all packages tracked by Hubitat Package Manager. Include their names, versions, and whether they are beta.",
  "teardown_prompt": "No teardown needed."
}
```

**Expected**: AI calls `hub_read_apps_code(tool='hub_list_hpm_packages')` (hpmAppId auto-discovered). Returns `success=true`, `count` (number of packages), and `packages` array. Each entry has `packageName`, `version`, `beta`, `author`, `apps`, `drivers`, `files`. AI summarizes the count and names, and flags any beta packages.

**Failure modes**: AI calls `hub_get_app_config` with `pageName='prefPkgUninstall'` instead (works but slower and returns the page-rendered enum/option list rather than structured package records). `hub_list_hpm_packages` is the right tool for programmatic enumeration.

### T601 — hub_list_hpm_packages (includeDrift): surface drift signals

```json
{
  "setup_prompt": "the Read master is enabled. HPM is installed with at least one package.",
  "test_prompt": "Check for any HPM package drift -- missing required components or orphaned app code definitions. Summarize what you find.",
  "teardown_prompt": "No teardown needed."
}
```

**Expected**: AI calls `hub_read_apps_code(tool='hub_list_hpm_packages', args={includeDrift:true})`. Returns `success=true` with the drift payload nested under a `drift` key containing: `summary` sentence, drift-signals array (may be empty), `packagesWithActionableDrift`, `totalDriftSignals`, `orphanDetection`, `orphanDriverDetection`, and `limitations` note. AI interprets the summary and describes any drift signals found (type, packageName, componentName). If no drift, AI confirms the packages are clean and mentions the heID-presence-only detection limitation. Response may also include `dataQualityWarnings[]` and `skippedMalformed[]` if manifest data quality issues exist. Per-package fields when emitted: `skippedAppCount`, `skippedDriverCount` (files are not iterated in drift -- no `skippedFileCount` on drift entries). Response-level fields when filter matches nothing: `filterMatchedZero=true`, `availablePackages[]`. When either detection system was disabled, `summary` includes a `"(partial: ...)"` suffix naming which detection field was disabled.

### T602 — hub_read_apps_code catalog discovery (hub_list_hpm_packages)

```json
{
  "test_prompt": "What HPM package tools does the hub_read_apps_code gateway expose?"
}
```

**Expected**: AI calls `hub_read_apps_code` with no args, finds `hub_list_hpm_packages` in the catalog (with its `includeDrift` parameter for drift detection) and full parameter schema. AI describes the tool and its the Read master requirement.

### T603 — hub_list_hpm_packages (includeDrift): data-quality-only entry does not inflate summary drift count

```json
{
  "setup_prompt": "the Read master is enabled. HPM is installed. At least one HPM-tracked package has a non-scalar or empty heID on a component.",
  "test_prompt": "Check HPM drift. If a package has only dataQualityWarnings and no actionable signals, confirm the summary says No drift detected even though drift[] has an entry for that package.",
  "teardown_prompt": "No teardown needed."
}
```

**Expected**: When a package has only `dataQualityWarnings[]` and no actionable `signals[]`, `totalDriftSignals` is 0 and `summary` reads "No drift detected..." even if `drift[]` has one entry (data-quality entry for visibility). Drift signal type strings: `missing-required`, `orphan-app`, `orphan-driver`. Data-quality warning type strings: `heid-whitespace-normalized` (padded heID normalized; component kept), `heid-non-scalar-dropped` (non-scalar heID; component dropped), `empty-heid`, `skipped-malformed-component`. `drift[].length` may exceed the actionable-drift package count in this scenario.

### T604 — hub_list_hpm_packages (with and without includeDrift): hpmAppId pointing at a non-HPM app surfaces descriptive error

```json
{
  "setup_prompt": "the Read master is enabled. HPM is installed. Use hub_list_apps (scope=instances) to find an app that is NOT Hubitat Package Manager (e.g. the MCP Rule Server itself) and note its appId.",
  "test_prompt": "Call hub_list_hpm_packages with includeDrift=true and the non-HPM appId you just found as hpmAppId. Then call hub_list_hpm_packages again with the same non-HPM appId but includeDrift omitted. Confirm both calls reject it with a descriptive error naming the actual app type.",
  "teardown_prompt": "No teardown needed."
}
```

**Expected**: `hub_list_hpm_packages` rejects the call on both the drift path (`includeDrift=true`) and the plain enumeration path. Internally, `_hpmAssertAppIsHpm` throws `IllegalArgumentException` with a message that includes both the supplied ID and the actual app type (e.g. `"hpmAppId <id> is not Hubitat Package Manager (actual type: MCP Rule Server) -- verify the ID or omit hpmAppId to use auto-discovery"`). The MCP protocol surfaces this as a JSON-RPC error response (`-32602` invalid params), not as a tool-result Map with `success=false` and not as a raw exception. The error message contains the supplied id and the `actual type:` string. AI explains the rejection and suggests either omitting `hpmAppId` (to let the tool auto-discover HPM) or supplying the correct HPM instance ID from `hub_list_apps` (scope=instances). The same validator runs on both paths, so the error shape is identical.

**Failure modes**: AI passes the wrong ID silently and returns an empty result (would indicate missing validation). AI reports a generic "tool failed" without extracting the `actual type` field from the error message. AI only tests one of the two paths (drift vs plain enumeration) instead of both.

### T605 — hub_list_hpm_packages: hpmAppId pointing at a non-HPM app surfaces descriptive error (standalone)

```json
{
  "setup_prompt": "the Read master is enabled. HPM is installed. Use hub_list_apps (scope=instances) to find an app that is NOT Hubitat Package Manager (e.g. Simple Automation Rules or a user-installed app) and note its appId.",
  "test_prompt": "Call hub_list_hpm_packages with the non-HPM appId as hpmAppId. Confirm the tool rejects it with a descriptive error that names the supplied ID and the actual app type.",
  "teardown_prompt": "No teardown needed."
}
```

**Expected**: `hub_read_apps_code(tool='hub_list_hpm_packages', args={hpmAppId: '<non-hpm-id>'})` is rejected. Internally, the tool throws `IllegalArgumentException` containing the supplied id and `"actual type: <AppTypeName>"`. The MCP protocol surfaces this as a JSON-RPC error response (`-32602` invalid params), not as a tool-result Map with `success=false` and not as a raw exception. AI surfaces this as a clear rejection with guidance to omit `hpmAppId` for auto-discovery.

**Failure modes**: Tool returns an empty packages list without an error (validation skipped). Error message is present but missing the actual type name.

### T606 — tools/list returns the full flat catalog in one response (no pagination)

```json
{
  "setup_prompt": "Disable the 'Consolidate tools behind category gateways' setting in the MCP app preferences so tools/list returns the flat catalog (~89 entries). Note the original value so it can be restored.",
  "test_prompt": "Invoke the MCP method tools/list with no params. Confirm the response contains a 'tools' array with every flat-mode tool present (hub_list_devices, hub_list_rooms, hub_list_files, hub_list_rules, hub_list_apps, hub_create_custom_rule -- all should appear) AND no 'nextCursor' field. Then invoke tools/list again with cursor='not-a-number' and confirm the response still returns the full catalog with no error and no nextCursor (cursor is silently ignored after the pagination removal).",
  "teardown_prompt": "Re-enable the 'Consolidate tools behind category gateways' setting if it was originally on."
}
```

**Expected**: First call returns a single `tools` array containing the full flat-mode catalog (~89 tools) with NO `nextCursor` field. Every flat-mode tool name appears exactly once. The cursor-with-bad-value follow-up call returns the same full catalog: cursor is now a no-op on `tools/list` (it stays opt-in only on `tools/call` paginated tools).

**Failure modes**: Response carries a `nextCursor` (pagination was re-introduced or never removed — silent client truncation regression). Tool count substantially less than the expected flat-mode catalog (size-guard hit `-32603` because the catalog grew past the 124,000-byte cap — needs more `[[FLAT_TRIM]]` wraps). Stale `-32602` errors on cursor values (cursor handling not fully removed). Duplicate tool names in the response (catalog assembly regression).

**Reading note for T607-T619 Expected sections**: where these scenarios say "no broken markers" the concrete check is `result.brokenMarkers` is empty (the field surfaced by `_rmCheckRuleHealth` / `hub_get_rule_health`) AND `result.health.ok=true`. Together they confirm the rule did not pick up any `*BROKEN*` rendering markers post-commit.

### T607 — addAction setVariable: set a hub variable to a constant value inside an RM rule

```json
{
  "setup_prompt": "the Write master is enabled. Create an RM rule called 'BAT SetVariable Test'. Also ensure at least one hub connector variable exists (use hub_manage_variables hub_set_variable to create 'bat_setvar_test' = 0 if it does not exist).",
  "test_prompt": "Add an action to the 'BAT SetVariable Test' rule: capability='setVariable', variable='bat_setvar_test', value=99. Then call hub_get_rule_health on the rule and confirm no broken markers are present.",
  "teardown_prompt": "Delete the 'BAT SetVariable Test' rule. Delete the hub variable 'bat_setvar_test'."
}
```

**Expected**: `hub_set_rule(appId=N, addAction={capability:'setVariable', variable:'bat_setvar_test', value:99}, confirm=true)` completes with `success=true`. `hub_get_rule_health` reports no broken-condition markers. The rule's action renders in the RM UI as "Set bat_setvar_test to 99".

**Failure modes**: Tool returns "Unsupported capability 'setVariable'" (capability not wired). Rule health check reports broken marker (wrong actType/actSubType or field name). Value written as string instead of correct type. Passing an unknown `variable` returns `success=false` with an error listing available hub variables.

### T608 — addAction mode with modeName resolves to ID (not literal string)

```json
{
  "setup_prompt": "the Write master is enabled. Note the hub's current mode names via hub_list_modes. Create an RM rule called 'BAT ModeName Test'.",
  "test_prompt": "Add an action to 'BAT ModeName Test': capability='mode', modeName='<any valid mode name from hub_list_modes>'. Then call hub_get_rule_health and confirm no broken markers.",
  "teardown_prompt": "Delete the 'BAT ModeName Test' rule."
}
```

**Expected**: `hub_set_rule(addAction={capability:'mode', modeName:'<name>'})` resolves the name to a numeric mode ID before writing. `hub_get_rule_health` reports no broken markers. Passing an unknown modeName returns `success=false` with an `error` field listing available mode names -- the agent should inspect `result.success` and `result.error`, not expect a protocol-level exception.

**Failure modes**: Rule renders as "Mode: null" (name written literally instead of resolved ID). Unknown modeName is silently accepted and produces a broken action.

### T609 — addAction runCommand with variable parameter uses moreParams + P-discovery (live-verified wire format)

```json
{
  "setup_prompt": "the Write master enabled. Create hub variable 'bat_cpvar_test' (numeric, value 50). Create a virtual switch device named 'BAT RunCmd Switch' via hub_manage_virtual_device. Create RM rule 'BAT RunCommand Variable Param'.",
  "test_prompt": "Add an action: capability='runCommand', command='setLevel', deviceIds=[<BAT RunCmd Switch id>], parameters=[{type:'number', variable:'bat_cpvar_test'}]. Then call hub_get_rule_health.",
  "teardown_prompt": "Delete 'BAT RunCommand Variable Param' rule. Delete bat_cpvar_test variable. Delete virtual device 'BAT RunCmd Switch'."
}
```

**Expected**: `addAction` completes with `success=true`. `hub_get_rule_health` returns no broken markers. RM wire: for each parameter a `moreParams` button click allocates a P-numbered slot (P starts at 2, RM-assigned); literal params write `cpType<P>.N` + `cpVal<P>.N`; variable params write `cpType<P>.N` + `uVar<P>.N=true` + `xVar<P>.N=bat_cpvar_test`. Passing an unknown variable name returns an error listing available variables.

**Failure modes**: Parameter silently dropped. Rule health shows broken action. `cpVal` written with the variable NAME as a literal string (wrong path -- should use uVar+xVar). Hub variable not found in xVar enum (variable does not exist on hub).

### T610 — addAction setVariable sourceVariable form (copy-from-variable wire)

```json
{
  "setup_prompt": "the Write master is enabled. Create hub variables 'bat_sv_src' (numeric, value 77) and 'bat_sv_dst' (numeric, value 0). Create RM rule 'BAT SetVariable Source Test'.",
  "test_prompt": "Add an action to 'BAT SetVariable Source Test': capability='setVariable', variable='bat_sv_dst', sourceVariable='bat_sv_src'. Then call hub_get_rule_health and confirm no broken markers.",
  "teardown_prompt": "Delete 'BAT SetVariable Source Test'. Delete hub variables bat_sv_src and bat_sv_dst."
}
```

**Expected**: `hub_set_rule(addAction={capability:'setVariable', variable:'bat_sv_dst', sourceVariable:'bat_sv_src'})` completes with `success=true`. RM wire: `xVarV.N='bat_sv_dst'`, `numOp.N='variable'`, `xVar3.N='bat_sv_src'` (xVar3 is schema-gated -- revealed only after `numOp=variable` is written; the digit is RM-assigned/discovered, not hardcoded). `hub_get_rule_health` reports no broken markers. Passing an unknown variable name (for either `variable` or `sourceVariable`) returns `success=false` with an error listing available hub variables.

**Failure modes**: Action renders as "Set bat_sv_dst to null" (numOp=number written instead of variable). xVar3 not written (sourceVariable dropped). Unknown variable name accepted silently and produces a broken action.

### T647 — addAction setVariable fromDevice form (read a device attribute)

```json
{
  "setup_prompt": "the Write master is enabled. Create hub variable 'bat_sv_fd' (numeric, value 0). Note a BAT_E2E_ virtual temperature sensor device id (or any device exposing a numeric 'temperature' attribute). Create RM rule 'BAT SetVariable FromDevice Test'.",
  "test_prompt": "Add an action to 'BAT SetVariable FromDevice Test': capability='setVariable', variable='bat_sv_fd', fromDevice={deviceId:<temperature sensor id>, attribute:'temperature'}. Then call hub_get_rule_health and confirm no broken markers.",
  "teardown_prompt": "Delete 'BAT SetVariable FromDevice Test'. Delete hub variable bat_sv_fd."
}
```

**Expected**: `hub_set_rule(addAction={capability:'setVariable', variable:'bat_sv_fd', fromDevice:{deviceId:<id>, attribute:'temperature'}})` completes with `success=true` and `partial=false`. The numeric target var requires a numeric source attribute: RM filters `tCustomAttr` to attributes compatible with the target var's type, so a Number var offers numeric attributes like `temperature` (an enum-only attribute such as a switch's `switch` is filtered out for a numeric var -- see the negative case below). RM wire: `numOp.N='device attribute'`, `customDev.N=<id>` (capability.* single-device picker, schema-gated -- revealed after numOp), `tCustomAttr.N='temperature'` (attribute enum FILTERED to the device, revealed after the device is written). `settingsApplied` includes `customDev.<N>` and `tCustomAttr.<N>`. `hub_get_rule_health` reports no broken markers. An attribute outside the device's type-filtered enum (e.g. requesting a switch's enum `switch` attribute into this numeric var) returns `success=false` with the available-attribute list. The `fromDevice` source requires a Number or Decimal target variable -- RM does not render the device-attribute source for a String/Boolean/DateTime target, so `fromDevice` into such a var returns `success=false` (with a message naming the Number/Decimal requirement) before any write.

**Failure modes**: customDev not revealed (numOp dropped or wrong value). tCustomAttr not filtered to the device or to the target var's type (wrong enum). An invalid attribute accepted silently and produces a broken action. deviceId validated against the MCP-selected set instead of all hub devices (false rejection of a valid hub device). `fromDevice` into a non-numeric target var accepted (or failing with a misleading not-in-schema message) instead of a clear Number/Decimal-target rejection.

### T648 — addAction setVariable math form (structured variable math, binary + unary arity)

```json
{
  "setup_prompt": "the Write master is enabled. Create hub variable 'bat_sv_math' (numeric, value 0). Create RM rule 'BAT SetVariable Math Test'.",
  "test_prompt": "Add a binary-math action to 'BAT SetVariable Math Test': capability='setVariable', variable='bat_sv_math', math={left:'bat_sv_math', op:'+', right:10}. Then add a unary-math action: capability='setVariable', variable='bat_sv_math', math={left:'bat_sv_math', op:'absolute'}. Then call hub_get_rule_health and confirm no broken markers.",
  "teardown_prompt": "Delete 'BAT SetVariable Math Test'. Delete hub variable bat_sv_math."
}
```

**Expected**: both `addAction` calls complete with `success=true` and `partial=false`. Binary wire: `numOp.N='variable math'`, `xVar3.N='bat_sv_math'`, `valMathOp.N='+'`, `xVar4.N='(constant)'`, `valConst2.N=10` (the numeric right operand becomes a constant; all schema-gated and revealed in sequence). Unary wire: `valMathOp.N='absolute'` with NO `xVar4`/`valConst2` written. A binary operator missing `right`, or a unary operator given `right`, returns `success=false` with an arity error. An unrecognized operator or an unknown variable operand returns `success=false`. The `math` source requires a Number or Decimal target variable -- `math` into a String/Boolean/DateTime target returns `success=false` (with a message naming the Number/Decimal requirement) before any write.

**Failure modes**: Second operand written for a unary op (arity not enforced). Numeric operand written as a variable name instead of `(constant)`+valConst. Operand reveal skipped (xVar3/valMathOp/xVar4 not discovered from the live schema). Unknown variable operand accepted silently. `math` into a non-numeric target var accepted (or failing with a misleading not-in-schema message) instead of a clear Number/Decimal-target rejection.

### T611 — addRequiredExpression + addAction ifThen: Mode and Variable conditions in a single rule (walker parity)

```json
{
  "setup_prompt": "the Write master is enabled. Create hub variable 'bat_re_walker_var' (numeric, value 0). Note the hub's modes via hub_list_modes -- pick one valid mode name and the hub variable name. Create RM rule 'BAT Walker Parity Test'.",
  "test_prompt": "First, add a Required Expression to 'BAT Walker Parity Test' using addRequiredExpression: conditions=[{capability:'Mode', state:'<valid mode name>'}, {capability:'Variable', variable:'bat_re_walker_var', comparator:'>', value:0}], operator='AND'. Then add an ifThen action: capability='ifThen', expression={conditions:[{capability:'Mode', state:'<valid mode name>'}]}. Then call hub_get_rule_health and confirm no broken markers.",
  "teardown_prompt": "Delete 'BAT Walker Parity Test'. Delete hub variable bat_re_walker_var."
}
```

**Expected**: Both `addRequiredExpression` and `addAction ifThen` complete with `success=true`, `partial!=true`. `hub_get_rule_health` reports no broken markers. The Required Expression paragraph on mainPage renders both conditions (Mode + Variable), not the bare "Define Required Expression" placeholder. The ifThen action appears in the actions list with the Mode condition baked correctly (not as "BROKEN"). Demonstrates that `_rmWalkConditionReveal` fires correctly from both STPage and doActPage entry points.

**Failure modes**: Required Expression placeholder remains ("Define Required Expression" on mainPage), indicating the expression did not bake. Mode condition in ifThen renders as Broken Condition (modes picker not written). Variable comparator/value silently dropped (RelrDev or state_N not revealed after picker write).

### T612 — addRequiredExpression Between two times: clock start/end lands on STPage (reveal walker coverage)

```json
{
  "setup_prompt": "the Write master is enabled. Create RM rule 'BAT Between Two Times Test'.",
  "test_prompt": "Add a Required Expression to 'BAT Between Two Times Test' using addRequiredExpression: conditions=[{capability:'Between two times', start:{type:'clock', time:'08:00'}, end:{type:'clock', time:'22:00'}}]. Then call hub_get_rule_health and confirm no broken markers.",
  "teardown_prompt": "Delete 'BAT Between Two Times Test'."
}
```

**Expected**: `addRequiredExpression` completes with `success=true`, `partial!=true`. `hub_get_rule_health` reports no broken markers. The Required Expression paragraph on mainPage renders a time-range condition (e.g. references 8:00 or 22:00), not the bare "Define Required Expression" placeholder. Demonstrates that `_rmWalkConditionReveal` correctly writes the Between two times clock fields (starting<N> type enum + startingA<N> ISO datetime + ending<N> + endingA<N>) via the STPage reveal sequence.

**Failure modes**: Required Expression placeholder remains ("Define Required Expression" on mainPage), indicating the expression did not bake. Broken Condition marker on the Required Expression row (starting/ending type not written, or startingA/endingA ISO datetime rejected). `partial=true` with `settingsSkipped` entries showing the clock fields as silent rejections.

---

### T613 — addRequiredExpression singular deviceId normalization: passes deviceId:N (not deviceIds:[N]) and verifies condition bakes

```json
{
  "setup_prompt": "the Write master is enabled. Create RM rule 'BAT DeviceId Norm Test'. Identify one motion sensor deviceId on the hub.",
  "test_prompt": "Add a Required Expression to 'BAT DeviceId Norm Test' using addRequiredExpression: conditions=[{capability:'Motion', deviceId:<motionSensorId>, state:'active'}] -- pass the integer deviceId directly (not deviceIds:[N]). Then call hub_get_rule_health and confirm no broken markers.",
  "teardown_prompt": "Delete 'BAT DeviceId Norm Test'."
}
```

**Expected**: `addRequiredExpression` completes with `success=true`. `hub_get_rule_health` reports no broken markers. The Required Expression paragraph on mainPage renders a motion-sensor condition (e.g. "MotionSensor is active"), not the bare "Define Required Expression" placeholder. Demonstrates that the dispatcher normalizes singular `deviceId: N` to `deviceIds: [N]` before the STPage walker fires so agents don't need to know the array form.

**Failure modes**: Required Expression placeholder remains, indicating normalization did not happen. Broken Condition marker (rDev_<N> not written because singular deviceId was ignored). `partial=true` with `settingsSkipped` showing rDev_1 as silent_rejection.

---

### T614 — addTrigger.condition singular deviceId: conditional trigger normalizes deviceId:N inline

```json
{
  "setup_prompt": "the Write master is enabled. Create RM rule 'BAT AddTrigger Cond DeviceId'. Identify one Switch deviceId and one Motion sensor deviceId on the hub.",
  "test_prompt": "Add a conditional trigger to 'BAT AddTrigger Cond DeviceId' using addTrigger: {capability:'Switch', deviceIds:[<switchId>], state:'on', condition:{capability:'Motion', deviceId:<motionId>, state:'active'}} -- the condition Map uses singular integer deviceId. Then hub_get_rule_health and confirm no broken markers.",
  "teardown_prompt": "Delete 'BAT AddTrigger Cond DeviceId'."
}
```

**Expected**: `addTrigger` completes with `success=true`. `hub_get_rule_health` reports no broken markers. The trigger paragraph renders the gating condition (e.g. "Switch on -- ONLY IF Motion is active"). Demonstrates the dispatcher normalizes singular `condition.deviceId` BEFORE pre-validation runs, so the existence check fires.

**Failure modes**: "Broken Trigger" marker (rDev_<N> for the condition slot stored {N: null}). The pre-validation existence check skipped (deviceIds was null in the un-normalized form). condTrig.<N> set but the condition row renders as a placeholder.

---

### T615 — addAction ifThen singular deviceId in expression.conditions[]: addAction normalizes inline

```json
{
  "setup_prompt": "the Write master is enabled. Create RM rule 'BAT AddAction Cond DeviceId'. Identify one Custom Attribute capable device on the hub (humidity sensor preferred).",
  "test_prompt": "Add an ifThen action to 'BAT AddAction Cond DeviceId' using addAction: {capability:'ifThen', expression:{conditions:[{capability:'Custom Attribute', deviceId:<humidityDeviceId>, attribute:'humidity', comparator:'<', value:40}]}} -- the expression condition uses singular integer deviceId. Verify the IF action bakes without broken markers.",
  "teardown_prompt": "Delete 'BAT AddAction Cond DeviceId'."
}
```

**Expected**: `addAction` completes with `success=true`. `hub_get_rule_health` reports no broken markers. The IF paragraph on mainPage renders the comparator-on-attribute condition (e.g. "IF Humidity < 40 THEN"). Mirrors T613 for the doActPage walker side.

**Failure modes**: IF paragraph renders the bare "Define IF" placeholder. Broken Condition marker (rDev_<N> not written). settingsSkipped shows rDev_1 as silent_rejection in the doActPage walk.

---

### T616 — addAction ifThen rejects nested subExpression with actionable recovery message

```json
{
  "setup_prompt": "the Write master is enabled. Create RM rule 'BAT NestedCond Reject'. Identify one Motion sensor deviceId on the hub.",
  "test_prompt": "Add an ifThen action to 'BAT NestedCond Reject' using addAction: {capability:'ifThen', expression:{conditions:[{capability:'Motion', deviceIds:[<motionId>], state:'active'}, {subExpression:{conditions:[{capability:'Motion', deviceIds:[<motionId>], state:'inactive'}]}}], operator:'OR'}} -- the second condition is a nested subExpression. Verify the call rejects WITHOUT writing anything to the rule.",
  "teardown_prompt": "Delete 'BAT NestedCond Reject'."
}
```

**Expected**: `addAction` returns `success=false`. The `error` message contains the phrase `nested subExpression is not yet supported` (the production reject text -- broad phrasing matches both the pre-pass at `_rmAddAction` and the in-walker reject at `_rmWalkConditionReveal`; the pre-pass is the path that actually fires for this input). `hub_get_rule_health` reports no actions on the rule and no broken markers (the pre-pass rejection fires BEFORE any wizard write). The rejection guides the agent toward `addRequiredExpression` (which DOES support nested subExpression) or flattening the conditions list.

**Failure modes**: Call returns `success=true` (recursive walker support quietly landed without doc updates -- in which case the docs that ship today need to advertise it). Call returns `success=false` but the error message lacks the targeted recovery hint (regression to the generic "capability is required" pre-fix behaviour, leaving the agent without an actionable next step). A broken action row gets written to the rule (the in-walker reject at `_rmWalkConditionReveal` fired AFTER a partial write; outer pre-pass should have rejected first).

---

### T617 — addRequiredExpression Mode condition on static-schema fixture: reveal_fallback informational sentinel

```json
{
  "setup_prompt": "the Write master is enabled. Create RM rule 'BAT Reveal Fallback'. Identify one mode name (e.g. 'Night') on the hub.",
  "test_prompt": "Add a Required Expression to 'BAT Reveal Fallback' using addRequiredExpression: {conditions:[{capability:'Mode', state:'Night'}]}. After it commits, inspect result.settingsSkipped -- if the firmware exposes modes<N> always-visible (static schema rather than progressive disclosure), the entry should include {key: '<pattern>', reason: 'reveal_fallback_to_existing_field', condIdx: 0}. If the firmware exposes modes<N> only after the rCapab='Mode' write (progressive disclosure -- typical on current firmware), no sentinel fires and the entry is absent. Confirm result.success=true and result.partial!=true in BOTH cases -- the sentinel is informational and does NOT degrade.",
  "teardown_prompt": "Delete 'BAT Reveal Fallback'."
}
```

**Expected**: `addRequiredExpression` completes with `success=true` and `partial!=true`. The Required Expression paragraph renders correctly (e.g. "Mode is Night"). On firmware where modes<N> is always-visible, `settingsSkipped` contains a `reveal_fallback_to_existing_field` entry stamped with `condIdx=0` -- informational diagnostic that the walker fell back to matching an already-visible field rather than a newly-revealed one. On firmware where modes<N> reveals progressively, the entry is absent (revealedNew matched). The presence of the sentinel does NOT flip partial -- this is the load-bearing contract.

**Firmware dependence -- which assertion is load-bearing here depends on which firmware the hub is running**:
- On **progressive-disclosure firmware** (the default for modern Hubitat hubs), the sentinel's ABSENCE is load-bearing. A regression that emits the sentinel on every reveal (rather than only when `fallbackToExisting=true`) would produce a false-positive `reveal_fallback_to_existing_field` entry here even though the schema advanced normally.
- On **static-schema firmware**, the sentinel's PRESENCE is load-bearing. A regression that reverts the wrapper's push (or breaks the `fallbackToExisting` detection) would leave settingsSkipped without the entry on the static-schema path.
- The Spock spec pair (`addRequiredExpression: walker pushes reveal_fallback_to_existing_field sentinel when only revealedAny matches` + `addRequiredExpression: progressive-disclosure reveal does NOT push reveal_fallback sentinel`) covers BOTH firmware classes deterministically via stubbed fixtures. T617 is the live-hub smoke-check for whichever path the hub-under-test actually exposes.

**Failure modes**: `partial=true` set when the only finding is the `reveal_fallback_to_existing_field` sentinel (regression in the wrapper's "informational does not flip partial" contract). Sentinel pushed on every reveal even when the schema legitimately advanced (the wrapper stopped distinguishing `fallbackToExisting=true` from `false`; every progressive-disclosure write would emit false-positive sentinels). Sentinel never pushed even on a static-schema fixture (the wrapper's push was reverted).

---

### T618 — addRequiredExpression compareToDevice: device-relative comparison with offset

```json
{
  "setup_prompt": "the Write master is enabled. Create RM rule 'BAT CompareToDevice'. Identify two Temperature-capable devices on the hub (deviceA + deviceB)."
,
  "test_prompt": "Add a Required Expression to 'BAT CompareToDevice' using addRequiredExpression: {conditions:[{capability:'Temperature', deviceIds:[<deviceA>], comparator:'>', compareToDevice:{deviceId:<deviceB>, attribute:'temperature', offset:-2}}]}. Inspect the rule paragraph on mainPage and result.partial / result.settingsApplied / result.settingsSkipped.",
  "teardown_prompt": "Delete 'BAT CompareToDevice'."
}
```

**Expected**: `addRequiredExpression` returns `success=true` and `partial=false`. The Required Expression paragraph on mainPage renders the device-relative comparison with the offset -- e.g. "Temperature of deviceA is > deviceB - 2.0" -- NOT a literal threshold and NOT "deviceA > 0". `settingsApplied` includes `isDev_<N>`, the discovered `relDevice_<N>` reference-device picker field, and `state_<N>` (the offset). `result.brokenMarkers` is empty AND `result.health.ok=true`. Omitting `offset` is also valid (offset 0); the only difference is no `state_<N>` write and the paragraph renders without the "- N.0" tail.

**Wire-format note**: the walker writes the comparator `RelrDev_<N>`, toggles `isDev_<N>=true` to reveal the SINGLE `relDevice_<N>` picker, writes the bare device id, then writes the offset to `state_<N>`. `relDevice_<N>` is a `capability.*` DEVICE picker; on normal firmware RM populates its dropdown client-side, so the schema exposes NO options for it and an empty option list is the normal case (does NOT flag `partial`). deviceB is existence-validated hub-wide BEFORE any write -- a nonexistent id is rejected up front. On a rare firmware variant that DOES surface device-picker options, the walker additionally defensively rejects a reference id absent from that list; otherwise a capability-mismatched reference device surfaces in the rendered/broken state. There is no separate reference-attribute picker -- the compared attribute is implied by the shared capability, so `compareToDevice.attribute` is OPTIONAL and informational (neither validated nor written). compareToDevice is mutually exclusive with a literal `state`/`value` RHS and with a `compareToVariable` RHS.

**Failure modes**: paragraph renders deviceA against a literal value / "deviceA > 0" (the device-relative write fell through -- the `isDev_<N>` toggle or `relDevice_<N>` reveal did not fire). `success=false` with "reference-device picker relDevice_<N> not revealed after isDev_<N>=true" (the toggle did not reveal the picker -- fail-loud, caught by `hub_set_rule`'s backup-and-catch wrapper as a structured `success=false` map). `success=false` with "does not exist on the hub" naming `compareToDevice.deviceId` (the reference id failed the up-front hub-wide existence check). `success=false` with "is not in the relDevice_<N> picker" (the rare firmware variant exposed a device-picker option list that excluded deviceB -- the defensive capability-lock rejected it before writing). `success=false` with "only supported with numeric device capabilities" naming the capability (compareToDevice was passed on a non-numeric capability -- Mode / Between two times / Variable / Custom Attribute -- and the up-front capability-scope guard rejected it rather than silently dropping it). `partial=true` with a `compareToDevice-validation` skip is WRONG for the normal empty-options case (a device picker exposes no options, so the normal case must be `partial=false` with NO such skip). `settingsSkipped` carries `offset_field_not_revealed` and `partial=true` (the offset field was absent -- the comparison still committed without the offset). `brokenMarkers` non-empty (a partial write rendered the condition as `*BROKEN*`).

---

### T619 — addRequiredExpression Between two times with sunrise/sunset offset

```json
{
  "setup_prompt": "the Write master is enabled. Hub timezone is set (Settings > Location and Modes). Create RM rule 'BAT Between Sunrise'."
,
  "test_prompt": "Add a Required Expression to 'BAT Between Sunrise' using addRequiredExpression: {conditions:[{capability:'Between two times', start:{type:'clock', time:'08:00'}, end:{type:'sunset', offset:-30}}]}. Inspect the rule paragraph on mainPage.",
  "teardown_prompt": "Delete 'BAT Between Sunrise'."
}
```

**Expected**: `addRequiredExpression` returns `success=true` and `partial!=true`. The Required Expression paragraph on mainPage renders with both the clock start (e.g. "between 8:00 AM") AND the sunset end with the -30 minute offset (e.g. "and 30 minutes before sunset"). `result.brokenMarkers` is empty AND `result.health.ok=true`. Exercises the `endingA<N>` / `endSunriseOffset<N>` distinct wire path that T612 (clock-only) does not cover.

**Failure modes**: `partial=true` with a `silent_rejection` sentinel on `endSunriseOffset_<N>` (the sunrise-offset write was silently dropped -- the walker's start/end type-detection logic regressed). Paragraph renders "and (unset)" or similar placeholder for the end-time half (the sunset offset never landed). `brokenMarkers` non-empty (the partial write rendered the time-band as `*BROKEN*`). Pre-validation throws on `location.timeZone == null` (the precondition documented in the walker -- agent must set hub timezone first in Settings > Location and Modes).

---

### T620 — addAction / addRequiredExpression Variable compareToVariable (variable-vs-variable RHS) on the walker pages

```json
{
  "setup_prompt": "the Write master is enabled. Create two hub variables (number type) named 'BatVarA' and 'BatVarB'. Create RM rule 'BAT VarVsVar'.",
  "test_prompt": "Add a Required Expression to 'BAT VarVsVar' using addRequiredExpression: {conditions:[{capability:'Variable', variable:'BatVarA', comparator:'>', compareToVariable:'BatVarB'}]}. Then inspect the rule paragraph on mainPage and result.settingsApplied.",
  "teardown_prompt": "Delete 'BAT VarVsVar' and remove BatVarA / BatVarB."
}
```

**Expected**: `addRequiredExpression` returns `success=true` and `partial!=true`. The Required Expression paragraph renders the variable-vs-variable comparison (e.g. "BatVarA > BatVarB"), NOT "BatVarA > 0" or "BatVarA > null". `settingsApplied` includes `isVar_<N>` and the discovered right-hand variable picker field (firmware-assigned; commonly `xVarR_<N>`). The same shape on `addAction.expression` (ifThen) renders the same way inside the IF clause.

**Wire-format note**: the right-hand variable picker field name is discovered from the live schema after `isVar_<N>=true`. On `selectTriggers` it is `xVarR_<N>`; the walker pages (STPage/doActPage) may expose a differently-suffixed field -- the walker discovers it rather than hardcoding. This is the field whose live name should be confirmed against the test hub.

**Failure modes**: paragraph renders "BatVarA > 0" / "BatVarA > null" (the right-hand picker was never revealed and the comparison fell through to the numeric default -- the regression this fixes). "right-hand variable picker not revealed" (the firmware did not expose the picker after `isVar_<N>` -- legitimate fail-loud; the walker's `IllegalStateException` is caught by `hub_set_rule`'s backup-and-catch wrapper and surfaces as a structured `success=false` map with `error`/`backup`/`restoreHint`, not a JSON-RPC error, so the bad render never commits). Supplying both `compareToVariable` and `value`/`state` returns the same `success=false` map with "mutually exclusive" in `error`. If the revealed RHS picker has an empty option list, the variable name still writes (best-effort) but `settingsSkipped` carries a `compareToVariable-validation` / `api_unavailable` sentinel and `partial=true`.

---

### T621 — addAction / addRequiredExpression compareToDevice missing comparator is rejected

```json
{
  "setup_prompt": "the Write master is enabled. Identify two Temperature-capable devices (deviceA + deviceB). Create RM rule 'BAT CtdNoComp'.",
  "test_prompt": "Add a Required Expression to 'BAT CtdNoComp' using addRequiredExpression: {conditions:[{capability:'Temperature', deviceIds:[<deviceA>], compareToDevice:{deviceId:<deviceB>, attribute:'temperature'}}]} -- deliberately OMITTING comparator. Observe the error.",
  "teardown_prompt": "Delete 'BAT CtdNoComp'."
}
```

**Expected**: the walker's `IllegalArgumentException` is caught by `hub_set_rule`'s backup-and-catch wrapper and surfaces as a structured `success=false` map whose `error` names the missing `comparator` (the map also carries `backup`/`restoreHint`); it is NOT a JSON-RPC `-32602`. No condition fields are written -- the rule has no half-built Temperature condition afterward (inspect mainPage to confirm nothing was added).

**Failure modes**: `success=true` / `partial=true` with a half-written condition (regression to the pre-fix path where rCapab/rDev landed but no comparator/RHS, rendering an incomplete or `*BROKEN*` condition). Any condition fields present in `settingsApplied` (the reject must fire before any hub write).

---

### T641 — addAction ifThen compareToDevice: device-relative happy path on doActPage

```json
{
  "setup_prompt": "the Write master is enabled. Create RM rule 'BAT CtdAction'. Identify two Temperature-capable devices on the hub (deviceA + deviceB)."
,
  "test_prompt": "Add an IF-THEN action to 'BAT CtdAction' using addAction: {capability:'ifThen', expression:{conditions:[{capability:'Temperature', deviceIds:[<deviceA>], comparator:'>', compareToDevice:{deviceId:<deviceB>, attribute:'temperature', offset:-2}}]}}. Inspect the rule paragraph on mainPage and result.partial / result.settingsApplied / result.settingsSkipped.",
  "teardown_prompt": "Delete 'BAT CtdAction'."
}
```

**Expected**: `addAction` returns `success=true` and `partial=false`. The IF clause on mainPage renders the device-relative comparison with the offset -- e.g. "IF Temperature of deviceA is > deviceB - 2.0 THEN" -- NOT a literal threshold and NOT "deviceA > 0". `settingsApplied` includes `isDev_<N>`, the discovered `relDevice_<N>` reference-device picker field, and `state_<N>` (the offset). This is the doActPage mirror of T618 (STPage / addRequiredExpression): both surfaces share the `_rmWalkConditionReveal` walker, so the device-relative wire-up must land identically on the action page. (Numbering note: T619 and T620 are already taken by the Between-two-times and compareToVariable scenarios, so this doActPage compareToDevice scenario uses the next free number, T641.)

**Wire-format note**: identical to T618 -- the walker writes the comparator `RelrDev_<N>`, toggles `isDev_<N>=true` to reveal the SINGLE `relDevice_<N>` picker, writes the bare device id, then writes the offset to `state_<N>`. The reference picker is a `capability.*` DEVICE picker with no client-side options (empty option list is normal, does NOT flag `partial`). `compareToDevice.attribute` is OPTIONAL and informational. compareToDevice is mutually exclusive with a literal `state`/`value` RHS and with a `compareToVariable` RHS, and is rejected up front on a non-numeric capability.

**Failure modes**: IF clause renders deviceA against a literal value / "deviceA > 0" (the device-relative write fell through on doActPage). `success=false` with "reference-device picker relDevice_<N> not revealed after isDev_<N>=true". `settingsSkipped` carries `offset_field_not_revealed` and `partial=true` (the offset field was absent -- the comparison still committed without the offset, via `_rmAddAction`'s own envelope). `brokenMarkers` non-empty (a partial write rendered the condition as `*BROKEN*`). An unclosed IF (no THEN body / endIf) is a valid intermediate state but `hub_get_rule_health` flags it -- close the block before asserting whole-rule health.

---

### T622 — addTrigger Custom Attribute '*changed*' bakes cleanly with partial=false (no skip)

```json
{
  "setup_prompt": "the Write master is enabled. Identify a device exposing a custom attribute (e.g. a sensor with a 'water' attribute). Create RM rule 'BAT CustomChanged'.",
  "test_prompt": "Add a trigger to 'BAT CustomChanged' using addTrigger: {capability:'Custom Attribute', deviceIds:[<deviceId>], attribute:'water', comparator:'*changed*'}. Inspect result.partial and result.settingsSkipped.",
  "teardown_prompt": "Delete 'BAT CustomChanged'."
}
```

**Expected**: `addTrigger` returns `success=true` and `partial=false`. The trigger row bakes (mainPage shows the rendered Custom Attribute trigger, not the "Define Triggers" placeholder). The `*changed*` comparator is written as a value into the comparator field (`ReltDev_<N>`), and `tCustomAttr_<N>` carries the attribute -- both land in `settingsApplied`. `settingsSkipped` is empty (or, on firmware where the post-commit "Conditional Trigger?" prompt already closed, contains only a cosmetic `isCondTrig.<N>` / `not_in_schema` entry that does NOT flip `partial`). `partial=false` because no real field skipped.

**Failure modes**: a `not_in_schema` skip on a real trigger field (`ReltDev_<N>`, `tCustomAttr_<N>`, `tDev_<N>`, `tstate_<N>`) appearing in `settingsSkipped` -- the value genuinely failed to write, which is real degradation and must flip `partial=true`, not be filtered as cosmetic. A genuine `silent_rejection` skip returning `partial=false` (over-broad filtering -- real degradation must still flip partial). The only `not_in_schema` skip that may coexist with `partial=false` is the cosmetic `isCondTrig.<N>` finalize toggle.

---

### T623 — addTrigger Periodic Seconds: single count-enum field (no toggle)

```json
{
  "setup_prompt": "the Write master is enabled. Create RM rule 'BAT Periodic Seconds'.",
  "test_prompt": "Add a trigger to 'BAT Periodic Seconds' using addTrigger: {capability:'Periodic Schedule', periodic:{frequency:'Seconds', everyN:5}}. Inspect the trigger paragraph on mainPage.",
  "teardown_prompt": "Delete 'BAT Periodic Seconds'."
}
```

**Expected**: `addTrigger` returns `success=true` and `partial!=true`. The trigger paragraph bakes to "Every 5 seconds" (not "?"/"null"). `result.brokenMarkers` is empty AND `result.health.ok=true`. Exercises the Seconds single-enum path (`everyNSecs1`) which has NO toggle -- the count enum IS the mode.

**Failure modes**: paragraph renders "?" or "null" (the `everyNSecs1` write was silently rejected). `partial=true` with a `silent_rejection` sentinel on `everyNSecs1`. A two-step toggle->count was attempted (Seconds has no toggle, so writing one would land nothing).

---

### T624 — addTrigger Periodic Minutes: toggle-before-count two-step

```json
{
  "setup_prompt": "the Write master is enabled. Create RM rule 'BAT Periodic Minutes'.",
  "test_prompt": "Add a trigger to 'BAT Periodic Minutes' using addTrigger: {capability:'Periodic Schedule', periodic:{frequency:'Minutes', everyN:15}}. Inspect the trigger paragraph on mainPage.",
  "teardown_prompt": "Delete 'BAT Periodic Minutes'."
}
```

**Expected**: `addTrigger` returns `success=true` and `partial!=true`. The trigger paragraph bakes to "Every 15 minutes". `result.brokenMarkers` is empty AND `result.health.ok=true`. Exercises the toggle-before-count two-step: `everyNMinutesC1=true` must land FIRST, then the count goes into `everyNC1` (a separate field), NOT into the bool toggle.

**Failure modes**: paragraph renders "?"/"null" because the count was written into the bool `everyNMinutesC1` (the original bug) and the real count field `everyNC1` never got the value. `partial=true` with a sentinel on `everyNC1`.

---

### T625 — addTrigger Periodic Weekly: day-of-week multi-picker + starting time

```json
{
  "setup_prompt": "the Write master is enabled. Create RM rule 'BAT Periodic Weekly'.",
  "test_prompt": "Add a trigger to 'BAT Periodic Weekly' using addTrigger: {capability:'Periodic Schedule', periodic:{frequency:'Weekly', daysOfWeek:['Monday','Friday'], startingTime:'08:00'}}. Inspect the trigger paragraph on mainPage.",
  "teardown_prompt": "Delete 'BAT Periodic Weekly'."
}
```

**Expected**: `addTrigger` returns `success=true` and `partial!=true`. The trigger paragraph names Monday and Friday and the 8:00 AM start. `result.brokenMarkers` is empty AND `result.health.ok=true`. Exercises `selectDoWC1` (day-of-week multi-enum) + `startingWC1` (time).

**Failure modes**: paragraph renders "?"/"null" or omits the day list (the `selectDoWC1` multi-enum was sent in the wrong serialization). `partial=true` with a sentinel on `selectDoWC1` or `startingWC1`.

---

### T626 — addTrigger Periodic Monthly by-day: day number + every-N-months + starting time

```json
{
  "setup_prompt": "the Write master is enabled. Create RM rule 'BAT Periodic Monthly'.",
  "test_prompt": "Add a trigger to 'BAT Periodic Monthly' using addTrigger: {capability:'Periodic Schedule', periodic:{frequency:'Monthly', dayOfMonth:15, everyNMonths:2, startingTime:'09:30'}}. Inspect the trigger paragraph on mainPage.",
  "teardown_prompt": "Delete 'BAT Periodic Monthly'."
}
```

**Expected**: `addTrigger` returns `success=true` and `partial!=true`. The trigger paragraph reflects day 15 of every 2 months, starting 9:30 AM. `result.brokenMarkers` is empty AND `result.health.ok=true`. by-day mode (no `weekOfMonth`) exercises `dayMC1` + `everyNMC1` + `startingMC1`. (nth-weekday mode is the mutually-exclusive alternative, covered by T630/T631. The "on day N of selected months" specific-months sub-mode is order-sensitive on the live hub and NOT yet supported.)

**Failure modes**: paragraph renders "?"/"null" (one of the numeric fields silently rejected). `partial=true` with a sentinel on `dayMC1`, `everyNMC1`, or `startingMC1`.

---

### T627 — addTrigger Periodic Yearly: nth-weekday (always) -- weekOfMonth + dayOfWeek + month

```json
{
  "setup_prompt": "the Write master is enabled. Create RM rule 'BAT Periodic Yearly'.",
  "test_prompt": "Add a trigger to 'BAT Periodic Yearly' using addTrigger: {capability:'Periodic Schedule', periodic:{frequency:'Yearly', months:'December', weekOfMonth:'First', dayOfWeek:'Monday', startingTime:'08:00'}}. Inspect the trigger paragraph on mainPage.",
  "teardown_prompt": "Delete 'BAT Periodic Yearly'."
}
```

**Expected**: `addTrigger` returns `success=true` and `partial!=true`. The trigger paragraph renders "On the First Monday of December at 8:00 AM". `result.brokenMarkers` is empty AND `result.health.ok=true`. Yearly is ALWAYS nth-weekday: exercises `yearlyMonthCX1` (the X-suffixed reveal month field -- `yearlyMonthC1` alone never completes) + `weeklyYC1` (week-of-month) + `dailyYC1` (single day-of-week, from `dayOfWeek`) + `startingYC1`. Note `months` is a single String here (Yearly), `dayOfWeek` is singular (vs Weekly's multi `daysOfWeek`).

**Failure modes**: paragraph renders "?"/"null" or omits the month/day (a regression to the dead `yearlyMonthC1`, or the `dailyYC1` day write was dropped). `partial=true` with a sentinel on `yearlyMonthCX1`, `weeklyYC1`, or `dailyYC1`.

---

### T628 — addTrigger Periodic Cron String: field-name fix (cronStr1)

```json
{
  "setup_prompt": "the Write master is enabled. Create RM rule 'BAT Periodic Cron'.",
  "test_prompt": "Add a trigger to 'BAT Periodic Cron' using addTrigger: {capability:'Periodic Schedule', periodic:{frequency:'Cron String', cronString:'0 0 12 * * ?'}}. Inspect the trigger paragraph on mainPage.",
  "teardown_prompt": "Delete 'BAT Periodic Cron'."
}
```

**Expected**: `addTrigger` returns `success=true` and `partial!=true`. The trigger paragraph renders the cron expression (not "?"/"null"). `result.brokenMarkers` is empty AND `result.health.ok=true`. Pins the Cron field-name fix: the value lands in `cronStr1` (the live field) -- the prior code wrote `cronString1`, a field that does not exist, so the value was silently dropped and the trigger rendered "null".

**Failure modes**: paragraph renders "?"/"null" (the cron value was written to the non-existent `cronString1` and silently dropped -- the typo regression). `partial=true` with a sentinel on the cron field.

---

### T629 — addTrigger Periodic Minutes everyN outside restricted enum: validation rejection

```json
{
  "setup_prompt": "the Write master is enabled. Create RM rule 'BAT Periodic Enum'.",
  "test_prompt": "Add a trigger to 'BAT Periodic Enum' using addTrigger: {capability:'Periodic Schedule', periodic:{frequency:'Minutes', everyN:7}}. Observe the tool's response.",
  "teardown_prompt": "Delete 'BAT Periodic Enum'."
}
```

**Expected**: the call returns a structured `success=false` map before any sub-page write. The `error` field names the allowed set `[1, 2, 3, 4, 5, 6, 10, 12, 15, 20, 30]` and the rejected value (7), and a `backup`/`restoreHint` accompanies it. No trigger row is committed. The up-front `IllegalArgumentException` is caught by the whole-tool backup-and-catch envelope and surfaced as `success=false` (NOT a thrown -32602) -- consistent with the sibling compareToDevice missing-comparator guard on this same addTrigger path. This is fail-loud: RM restricts the Seconds/Minutes count to a fixed enum, and 7 is not in it, so the tool surfaces a recoverable structured error instead of silently rendering "null".

**Failure modes**: the call returns `success=true` and the trigger renders "null"/"?" (validation was skipped and the out-of-enum count was silently rejected by the hub). The `error` omits the valid-options list (less actionable for the LLM).

---

### T630 — addTrigger Periodic Monthly nth-weekday mode: weekOfMonth + dayOfWeek + everyNMonths

```json
{
  "setup_prompt": "the Write master is enabled. Create RM rule 'BAT Periodic Monthly Nth'.",
  "test_prompt": "Add a trigger to 'BAT Periodic Monthly Nth' using addTrigger: {capability:'Periodic Schedule', periodic:{frequency:'Monthly', weekOfMonth:'Second', dayOfWeek:'Monday', everyNMonths:1, startingTime:'08:00'}}. Inspect the trigger paragraph on mainPage.",
  "teardown_prompt": "Delete 'BAT Periodic Monthly Nth'."
}
```

**Expected**: `addTrigger` returns `success=true` and `partial!=true`. The trigger paragraph renders "On the Second Monday of every month at 8:00 AM". `result.brokenMarkers` is empty AND `result.health.ok=true`. nth-weekday mode (selected by `weekOfMonth` presence) hides the by-day fields and exercises `weeklyMC1` + `dailyMC1` (single day-of-week from `dayOfWeek`) + `everyNMCX1` (the X-suffixed cadence, NOT the by-day `everyNMC1`) + `startingMC1`. `dayOfWeek` is singular here (vs Weekly's multi `daysOfWeek`).

**Failure modes**: paragraph renders "?"/"null" (a regression that wrote the by-day `everyNMC1` instead of `everyNMCX1`, or dropped `dailyMC1`). `partial=true` with a sentinel on `dailyMC1` or `everyNMCX1`. The by-day fields (`dayMC1`/`everyNMC1`) being written would corrupt the mutually-exclusive mode.

---

### T631 — addTrigger Periodic Monthly dayOfMonth + weekOfMonth: mutually-exclusive rejection

```json
{
  "setup_prompt": "the Write master is enabled. Create RM rule 'BAT Periodic Monthly Excl'.",
  "test_prompt": "Add a trigger to 'BAT Periodic Monthly Excl' using addTrigger: {capability:'Periodic Schedule', periodic:{frequency:'Monthly', dayOfMonth:15, weekOfMonth:'Second'}}. Observe the tool's response.",
  "teardown_prompt": "Delete 'BAT Periodic Monthly Excl'."
}
```

**Expected**: the call returns a structured `success=false` map before any sub-page write. The `error` field states the two modes are mutually exclusive and names both `dayOfMonth` and `weekOfMonth`, and a `backup`/`restoreHint` accompanies it. No trigger row is committed. The up-front `IllegalArgumentException` is caught by the whole-tool backup-and-catch envelope and surfaced as `success=false` (NOT a thrown -32602), consistent with the Seconds/Minutes everyN guard and the sibling compareToDevice guard. Monthly by-day (calendar day) and nth-weekday (Nth weekday) are distinct RM modes whose field sets hide each other; mixing them is unrenderable.

**Failure modes**: the call returns `success=true` and the trigger renders "null"/"?" (the guard was skipped and both field sets were written, leaving an ambiguous/incomplete sub-page). The `error` names only one of the two conflicting fields (less actionable).

---

### T632 — two Periodic Schedule triggers in one rule: no sub-page collision

```json
{
  "setup_prompt": "the Write master is enabled. Create RM rule 'BAT Two Periodic'.",
  "test_prompt": "Add TWO triggers to 'BAT Two Periodic': first addTrigger {capability:'Periodic Schedule', periodic:{frequency:'Minutes', everyN:5}}, then addTrigger {capability:'Periodic Schedule', periodic:{frequency:'Daily', everyN:1, startingTime:'07:00'}}. Inspect both trigger paragraphs on mainPage.",
  "teardown_prompt": "Delete 'BAT Two Periodic'."
}
```

**Expected**: both addTrigger calls return `success=true` and `partial!=true`. mainPage renders BOTH periodic rows intact -- e.g. "Every 5 minutes" OR "Every day at 7:00 AM" -- with neither rendering "null". `result.brokenMarkers` is empty AND `result.health.ok=true`. The 2nd trigger navigates to its OWN periodic sub-page (RM exposes a `periodic2` href with params n:2 and suffix-2 fields `whichPeriod2`/`everyNDoMC2`/`everyNDC2`/`startingDC2`); the tool discovers the per-trigger index from the live schema and writes the n-suffixed field names, so trigger 1's suffix-1 fields are untouched. This is the regression scenario for the multi-periodic-trigger fix.

**Failure modes**: trigger 1 renders "null" while trigger 2's frequency wins (the classic collision -- the 2nd trigger wrote suffix-1 fields, clobbering `whichPeriod1`/`everyN*C1`). The 2nd addTrigger's `settingsSkipped` shows silent_rejection with `available:["whichPeriod2"]` (the tool navigated correctly but wrote suffix-1 names the sub-page does not expose). Both rows render the same frequency (one trigger's config overwrote the other's slot).

---

### T633 — hub_set_rule single-call create-with-bundle: new rule + trigger + action in one upsert (no appId)

```json
{
  "setup_prompt": "The Write master is enabled. Create a virtual switch named 'BAT Bundle Switch' via hub_manage_virtual_device and note its device ID.",
  "test_prompt": "In a SINGLE hub_set_rule call (omit appId so it creates), create a new Rule Machine rule named 'BAT Bundle Create' AND bundle its first trigger and action in the same call: addTriggers=[{capability:'Switch', deviceIds:[<BAT Bundle Switch id>], state:'on'}] and addActions=[{capability:'Switch', deviceIds:[<BAT Bundle Switch id>], command:'off'}]. Do NOT make a second hub_set_rule edit call. Report the returned appId and confirm the bundled trigger and action both baked.",
  "teardown_prompt": "Delete the 'BAT Bundle Create' rule. Delete the virtual switch 'BAT Bundle Switch'."
}
```

**Expected**: AI calls `hub_manage_rule_machine(tool=hub_set_rule)` ONCE with NO `appId`, `name='BAT Bundle Create'`, plus `addTriggers`/`addActions` bundled into the same upsert (the create-with-bundle path). The response returns a NEW `appId` with the trigger and action already baked in -- inspect `partial`/`partialTriggers`/`partialActions` to confirm both committed (`success=true`, and `partial!=true` OR a cosmetic-only `partial=true` per T109 semantics). No second `hub_set_rule(appId=...)` edit call is needed. `result.brokenMarkers` is empty AND `result.health.ok=true`. mainPage renders both the Switch=on trigger row and the "Off: BAT Bundle Switch" action row.

**Failure modes**: AI splits into two calls -- a create `hub_set_rule(name=...)` then a separate `hub_set_rule(appId=N, addTrigger=...)` -- instead of the single bundled upsert (acceptable result, but a Partial: the bundle was the point). The bundled `addTriggers`/`addActions` are silently dropped and only the empty named rule is created (`partialTriggers`/`partialActions` non-empty with the trigger/action listed as skipped). The response omits the new `appId` (create did not return the id needed for teardown). mainPage renders only the trigger OR only the action (one half of the bundle was lost).

---

### T634 — addTrigger Custom Attribute trigger row on an enum-recognized attribute: no false-positive partial

```json
{
  "setup_prompt": "Hub Admin Write and Built-in App Tools are enabled. Identify a device whose attribute name the hub treats as an ENUM (e.g. a switch's 'switch' attribute -- use hub_list_virtual_devices to find a virtual switch). Create RM rule 'BAT CustomEnumAttr'.",
  "test_prompt": "Add a trigger to 'BAT CustomEnumAttr' using addTrigger: {capability:'Custom Attribute', deviceIds:[<deviceId>], attribute:'switch', comparator:'=', state:'on'}. Inspect result.partial, result.settingsApplied, and result.settingsSkipped.",
  "teardown_prompt": "Delete 'BAT CustomEnumAttr'."
}
```

**Expected**: the trigger-row add returns `success=true` and `partial=false`. Because the hub recognizes `switch` as an enum attribute, picking it reveals the enum value picker `tstate<N>` and hides the free comparator `ReltDev<N>`. The value `on` lands in `tstate<N>` (present in `settingsApplied`); `ReltDev<N>` is NOT in `settingsApplied` (the tool does not write a comparator field the schema doesn't expose) and produces NO `not_in_schema` skip. `settingsSkipped` has no `ReltDev<N>` entry. The trigger row renders correctly on mainPage ("switch is on") with `result.health.ok=true`.

**Failure modes**: a `not_in_schema` skip on `ReltDev<N>` appearing in `settingsSkipped` and flipping `partial=true` even though the value landed in `tstate<N>` -- the pre-fix unconditional comparator write against an enum attribute (the bug this scenario guards). Contrast control: a genuinely free-valued Custom Attribute (e.g. a custom driver's `fixtureMode`) DOES expose the comparator field and writes it with `partial=false` -- the fix must not regress that path.

### T635 — addTrigger conditional-trigger Custom Attribute condition on an enum attribute: no false-positive partial

```json
{
  "setup_prompt": "Hub Admin Write and Built-in App Tools are enabled. Identify a device whose attribute name the hub treats as an ENUM (e.g. a switch's 'switch' attribute -- use hub_list_virtual_devices to find a virtual switch). Create RM rule 'BAT CustomEnumCond'.",
  "test_prompt": "Add a conditional trigger to 'BAT CustomEnumCond' using addTrigger: {capability:'Switch', deviceIds:[<deviceId>], state:'on', condition:{capability:'Custom Attribute', deviceIds:[<deviceId>], attribute:'switch', comparator:'=', state:'on'}}. Inspect result.partial, result.settingsApplied, and result.settingsSkipped.",
  "teardown_prompt": "Delete 'BAT CustomEnumCond'."
}
```

**Expected**: the conditional-trigger add returns `success=true` and `partial=false`. The condition wizard (`_rmBuildCondition`, underscore field names) has the same enum routing as the trigger row: the enum attribute reveals the value picker `state_<N>` and hides the comparator `RelrDev_<N>`. The value `on` lands in `state_<N>` (present in `settingsApplied`); `RelrDev_<N>` is neither applied nor skipped, and `partial=false`. The trigger renders correctly on mainPage with `result.health.ok=true`.

**Failure modes**: a `not_in_schema` skip on `RelrDev_<N>` flipping `partial=true` even though the value landed in `state_<N>`. Contrast control: a free-valued Custom Attribute condition DOES expose `RelrDev_<N>` and writes it with `partial=false`.

### T642 — addTrigger Custom Attribute '*changed*' on an enum attribute ROUTES via the picker's change option

```json
{
  "setup_prompt": "Hub Admin Write and Built-in App Tools are enabled. Identify a device whose attribute name the hub treats as an ENUM (e.g. a virtual switch's 'switch' attribute). Create RM rule 'BAT CustomEnumChanged'.",
  "test_prompt": "Add a trigger to 'BAT CustomEnumChanged' using addTrigger: {capability:'Custom Attribute', deviceIds:[<switchDeviceId>], attribute:'switch', comparator:'*changed*'}. Inspect result.success, result.partial, result.settingsApplied, and the rendered trigger on mainPage.",
  "teardown_prompt": "Delete 'BAT CustomEnumChanged'."
}
```

**Expected**: the trigger ROUTES cleanly. On the live trigger row the enum value picker (`tstate<N>`) offers a change-equivalent option, so the no-RHS `*changed*` comparator is routed to that option instead of skipped: `success=true`, `partial=false`, no `comparator_not_representable_for_enum_attribute` skip, and the trigger renders as a state-change trigger on mainPage (e.g. "switch changed"). `tstate<N>` carries the exact requested change token; `ReltDev<N>` (the hidden comparator field) is not falsely claimed in `settingsApplied`. (The skip path is exercised on the Required Expression surface instead -- see T643 -- whose value picker does not offer the change option.)

**Failure modes**: `partial=true` with a `comparator_not_representable_for_enum_attribute` skip on a surface that actually offers the change option (the skip mis-firing on a routable picker). The picker value POSTed as a stringified Map (`[value:*changed*, text:changed]`) instead of the clean token (the Map-shaped option corruption). Routing the WRONG token when a picker offers several change options (e.g. routing `*became false*` to a `became true` slot). Contrast control: the same `*changed*` on a FREE-valued attribute (e.g. a custom driver's `fixtureMode`, which exposes `ReltDev<N>`) writes the comparator into `ReltDev<N>` directly.

### T643 — addRequiredExpression Custom Attribute '*changed*' on an enum attribute is reported as a skip, not silently dropped

```json
{
  "setup_prompt": "Hub Admin Write and Built-in App Tools are enabled. Identify a device whose attribute name the hub treats as an ENUM whose Required-Expression value picker offers ONLY discrete states (e.g. on/off) and NO change option. Create RM rule 'BAT WalkEnumChanged'.",
  "test_prompt": "Add a Required Expression to 'BAT WalkEnumChanged' using addRequiredExpression: {conditions:[{capability:'Custom Attribute', deviceIds:[<switchDeviceId>], attribute:'switch', comparator:'*changed*'}]}. Inspect result.partial, result.settingsApplied, result.settingsSkipped, and result.repairHints.",
  "teardown_prompt": "Delete 'BAT WalkEnumChanged'."
}
```

**Expected**: the add does NOT report a silent clean success. On the STPage reveal walker the enum re-render exposes only the value picker (`state_<N>`) with no change-equivalent option and no comparator slot, so the no-RHS `*changed*` comparator cannot be represented. The tool returns `partial=true` with a `settingsSkipped` entry `{key:'RelrDev_<N>', reason:'comparator_not_representable_for_enum_attribute'}` and `result.repairHints` includes a line stating the comparator "cannot be represented" and pointing to the device's native capability (e.g. `capability:'Switch'`) or a non-built-in attribute name. `RelrDev_<N>` is NOT in `settingsApplied` (the comparator is not falsely claimed applied).

**Failure modes**: `success=true`/`partial=false` with `settingsSkipped` empty -- the pre-fix silent-drop bug (an unrepresentable comparator reported as clean success). `RelrDev_<N>` appearing in `settingsApplied`. Note: the not-representable skip fires only when the reveal SUCCEEDS but the picker offers no matching change option. A transient throw on the reveal re-fetch is a DIFFERENT path -- it is caught by the outer force-write fallback and produces a `comparator_force_written_unverified` skip with `partial:true` (NOT `comparator_not_representable_for_enum_attribute`); success stays true and the wizard does not abort. Contrast control: the same `*changed*` on a FREE-valued attribute reveals `RelrDev_<N>` and writes the comparator normally.

### T636 — addRequiredExpression Custom Attribute enum condition on the reveal walker (STPage): no hard error

```json
{
  "setup_prompt": "Hub Admin Write and Built-in App Tools are enabled. Identify a device whose attribute name the hub treats as an ENUM (e.g. a switch's 'switch' attribute -- use hub_list_virtual_devices to find a virtual switch). Create RM rule 'BAT WalkEnumAttr'.",
  "test_prompt": "Add a Required Expression to 'BAT WalkEnumAttr' using addRequiredExpression: {conditions:[{capability:'Custom Attribute', deviceIds:[<deviceId>], attribute:'switch', comparator:'=', state:'on'}]}. Inspect result.success, result.partial, result.settingsApplied, and result.settingsSkipped.",
  "teardown_prompt": "Delete 'BAT WalkEnumAttr'."
}
```

**Expected**: the Required Expression add returns `success=true` and `partial=false`. The STPage reveal walker (`_rmWalkConditionReveal`) writes `rCustomAttr_<N>`, and the enum re-render reveals `state_<N>` directly and never exposes `RelrDev_<N>`. The walker branches to the enum path: it writes the value to `state_<N>`, skips the comparator, and does NOT throw. The expression renders on mainPage with `result.health.ok=true`.

**Failure modes**: pre-fix the walker threw `IllegalStateException` ("RelrDev_<N> (comparator) not revealed after rCustomAttr_<N> write"), surfacing as `success=false` / an error envelope -- a hard failure on an attribute the hub legitimately treats as an enum. A `not_in_schema`/throw on the enum attribute, or `partial=true` despite a clean build, fails this scenario. Contrast control: a free-valued Custom Attribute condition reveals `RelrDev_<N>` and writes the comparator normally.

### T637 — addTrigger Custom Attribute free-valued comparator: value lands normally

```json
{
  "setup_prompt": "Hub Admin Write and Built-in App Tools are enabled. Create RM rule 'BAT CustomFetchFallback'.",
  "test_prompt": "Add a trigger to 'BAT CustomFetchFallback' on a FREE-valued Custom Attribute (e.g. a custom driver's 'fixtureMode'): addTrigger {capability:'Custom Attribute', deviceIds:[<deviceId>], attribute:'fixtureMode', comparator:'=', state:'bright'}. Inspect result.success and result.settingsApplied.",
  "teardown_prompt": "Delete 'BAT CustomFetchFallback'."
}
```

**Expected**: the add returns `success=true` and the comparator `ReltDev<N>` is in `settingsApplied`. This exercises the HEALTHY free-attribute path: the enum-vs-free guard re-fetches `selectTriggers` after writing the attribute, the re-fetch succeeds on a healthy hub, the comparator field is revealed, and the value is written normally. The force-write fallback (which fires only when that re-fetch fails transiently with an empty/unparseable response, force-writing the comparator + flagging `partial`) is not reachable from an agent prompt on a healthy hub by design -- it is covered deterministically by the `_rmForceWriteEnumField ...` Spock specs via stub injection.

**Failure modes**: the comparator is silently dropped (absent from `settingsApplied` with no skip entry), or `partial=true` despite a clean build on the healthy path. The transient re-fetch-failure fallback branch is driven by the Spock specs, not this live form.

### T638 — addAction ifThen Custom Attribute enum condition on the reveal walker (doActPage): no hard error

```json
{
  "setup_prompt": "Hub Admin Write and Built-in App Tools are enabled. Identify a device whose attribute name the hub treats as an ENUM (e.g. a switch's 'switch' attribute -- use hub_list_virtual_devices to find a virtual switch). Create RM rule 'BAT WalkEnumAction'.",
  "test_prompt": "Add an IF/THEN action to 'BAT WalkEnumAction' using addAction: {capability:'ifThen', expression:{conditions:[{capability:'Custom Attribute', deviceIds:[<deviceId>], attribute:'switch', comparator:'=', state:'on'}]}}. Inspect result.success, result.partial, result.settingsApplied, and result.settingsSkipped.",
  "teardown_prompt": "Delete 'BAT WalkEnumAction'."
}
```

**Expected**: the IF/THEN action add returns `success=true` and `partial=false`. The doActPage reveal walker (`_rmWalkConditionReveal`, the same shared walker as STPage) writes `rCustomAttr_<N>`, and the enum re-render reveals `state_<N>` directly and never exposes `RelrDev_<N>`. The walker branches to the enum path: it writes the value to `state_<N>`, skips the comparator, and does NOT throw. The action renders on mainPage with `result.health.ok=true`.

**Failure modes**: pre-fix the walker threw `IllegalStateException` on the doActPage surface (the same false-hard-fail the STPage T636 guards, but reached via `addAction`), surfacing as `success=false` / an error envelope on an attribute the hub legitimately treats as an enum. A `not_in_schema`/throw on the enum attribute, or `partial=true` despite a clean build, fails this scenario. Contrast control: a free-valued Custom Attribute condition reveals `RelrDev_<N>` and writes the comparator normally on doActPage too.

---

### T639 — hub_set_rule CREATE (no appId) bundling addRequiredExpression: the RE is honored, not silently dropped

```json
{
  "setup_prompt": "the Write master is enabled. Identify a virtual switch on the hub (use hub_list_virtual_devices)."
,
  "test_prompt": "Create a new RM rule in ONE call with hub_set_rule (NO appId): {name:'BAT CreateRE', addRequiredExpression:{conditions:[{capability:'Switch', deviceIds:[<switchId>], state:'on'}]}, confirm:true}. Inspect result.appId and result.requiredExpression.",
  "teardown_prompt": "Delete 'BAT CreateRE'."
}
```

**Expected**: the create returns a new `appId` AND a `result.requiredExpression` object whose `success` is not false and whose `conditionIndices` is non-empty -- the bundled Required Expression actually landed on the brand-new rule. `hub_get_rule_health` reports `ok=true`. The RE walk runs after the rule shell is created (before any bundled actions).

**Failure modes**: pre-fix the create arm read only `addTriggers`/`addActions`, so `addRequiredExpression` on create was silently dropped: the call returned `success=true` on an EMPTY rule with no `requiredExpression` field. Absence of `result.requiredExpression` (RE dropped), `requiredExpression.success=false` (RE failed to bake), or an empty `conditionIndices` (the expression did not land) all fail this scenario.

---

### T640 — hub_set_rule edit-only shortcut on CREATE is rejected loudly, not dropped to an empty shell

```json
{
  "setup_prompt": "the Write master is enabled."
,
  "test_prompt": "Call hub_set_rule with NO appId and an edit-only shortcut: {name:'BAT RejectEditOnly', replaceActions:[{capability:'log', message:'x'}], confirm:true}. Inspect the error.",
  "teardown_prompt": "If a rule named 'BAT RejectEditOnly' was created, delete it (it should NOT have been -- the call must reject before any create)."
}
```

**Expected**: the call is rejected with an `IllegalArgumentException` (JSON-RPC -32602) whose message names `replaceActions`, says it is an `edit-only` operation requiring an existing rule, and points the caller to create-then-edit. NO rule is created. This is the create-arm completeness contract: every shortcut the create arm is handed is HONORED or LOUDLY REJECTED, never silently dropped to a `success=true` empty shell. The same rejection holds for `addLocalVariable`, `patches`, `removeAction`, `clearActions`, `moveAction`, `removeTrigger`, `modifyTrigger`, and `walkStep`.

**Failure modes**: a `success=true` envelope with an empty rule (the edit-only op was silently dropped -- the exact pre-fix bug, regressed). A raw internal error with no actionable guidance, or a rule created despite the rejection, also fails this scenario.

### T649 — replaceRequiredExpression: change an existing Required Expression in place (cancelST delete + rebuild)

```json
{
  "setup_prompt": "the Write master is enabled. Note the hub's modes via hub_list_modes -- pick TWO distinct valid mode names (call them ModeA and ModeB). Create RM rule 'BAT Replace RE Test'. Add a Required Expression to it using addRequiredExpression: conditions=[{capability:'Mode', state:'<ModeA>'}]. Confirm the mainPage paragraph renders 'Mode is <ModeA>' (not the 'Define Required Expression' placeholder).",
  "test_prompt": "Replace the Required Expression on 'BAT Replace RE Test' using replaceRequiredExpression: conditions=[{capability:'Mode', state:'<ModeB>'}]. Then call hub_get_app_config and hub_get_rule_health.",
  "teardown_prompt": "Delete 'BAT Replace RE Test'."
}
```

**Expected**: `replaceRequiredExpression` returns `success=true`, `partial!=true`, and `requiredExpressionReplaced=true`. The Required Expression paragraph on mainPage now renders the NEW condition ('Mode is <ModeB>'), NOT the old one ('Mode is <ModeA>') and NOT the bare 'Define Required Expression' placeholder. `hub_get_rule_health` reports no broken markers. Same `appId` throughout -- no clone, no new rule. Demonstrates the cancelST delete of the committed expression followed by the delegated `addRequiredExpression` rebuild.

**Failure modes**: the paragraph still shows the OLD condition ('Mode is <ModeA>') -- the delete did not take and the new condition was not built. The paragraph shows BOTH conditions or a "Broken Condition" marker -- the delete did not clear the prior formula. `success=false` with a `cancelST`/delete step-named error and `requiredExpressionRestored=true` -- the delete was silently rejected or the rebuild failed and the original was auto-restored (existing RE preserved, which is the safe outcome).

### T650 — replaceRequiredExpression on a rule with no Required Expression is refused, not silently added

```json
{
  "setup_prompt": "the Write master is enabled. Note one valid mode name via hub_list_modes. Create RM rule 'BAT Replace No RE Test' with NO Required Expression.",
  "test_prompt": "Call replaceRequiredExpression on 'BAT Replace No RE Test': conditions=[{capability:'Mode', state:'<valid mode name>'}]. Inspect the result.",
  "teardown_prompt": "Delete 'BAT Replace No RE Test'."
}
```

**Expected**: the call returns `success=false` with `requiredExpressionMissing=true` and an error steering the caller to use `addRequiredExpression` instead. The rule's mainPage still shows the bare 'Define Required Expression' placeholder -- NO Required Expression was added. This is the replace-vs-add intent guard: a replace never silently becomes an add when there is nothing to replace.

**Failure modes**: a `success=true` envelope with a newly-added Required Expression (the refusal was skipped and the replace silently added one -- the exact behavior the guard prevents). A raw internal schema dump instead of the clear `requiredExpressionMissing` error also fails.

### T651 — replaceRequiredExpression failed rebuild auto-restores the original Required Expression (no data loss)

```json
{
  "setup_prompt": "the Write master is enabled and a hub backup exists within 24h. Note two valid mode names via hub_list_modes (ModeA, ModeB). Note a valid switch device ID. Create RM rule 'BAT Replace Restore Test' and add a Required Expression: conditions=[{capability:'Mode', state:'<ModeA>'}]. Confirm the mainPage paragraph renders 'Mode is <ModeA>'.",
  "test_prompt": "Call replaceRequiredExpression on 'BAT Replace Restore Test' with a spec whose new condition will FAIL TO BAKE -- conditions=[{capability:'Switch', deviceIds:[<the valid switch id>], state:'definitely_not_a_valid_state'}] (a bogus state value the Switch capability does not accept, so RM accepts the field writes but the expression does not commit). Inspect the result envelope, then call hub_get_app_config to see the mainPage paragraph.",
  "teardown_prompt": "Delete 'BAT Replace Restore Test'."
}
```

**Expected**: `replaceRequiredExpression` returns `success=false` and `requiredExpressionReplaced=false`. Because the cancelST delete already removed the original expression before the rebuild failed, the tool auto-restored the pre-op backup: `requiredExpressionRestored=true` and the `error` text says the original was "restored from backup" (with the backup key). Crucially, `hub_get_app_config` shows the mainPage paragraph STILL rendering the ORIGINAL 'Mode is <ModeA>' condition -- NOT the bare 'Define Required Expression' placeholder and NOT the failed Switch condition. The rule's gate was preserved despite the failed replace. (If the bogus state unexpectedly DOES bake on the hub-under-test, pick a different spec that still PASSES pre-validation but fails during the live walk/render -- do NOT use a nonexistent deviceId: deviceIds are existence-checked UP FRONT before the cancelST delete, so a bad id throws at validation with the original RE intact and never exercises the post-delete restore. Instead keep a valid deviceId and invalidate a VALUE the validator does not check -- e.g. a bogus `state` on a different capability, or a Custom Attribute condition naming an attribute the device does not expose -- so the walker writes the field but the expression fails to bake.)

**Failure modes**: the mainPage shows the bare 'Define Required Expression' placeholder (the original was deleted and NOT restored -- the data-loss bug this guard exists to prevent). `requiredExpressionReplaced=true` on a failed rebuild (the field must be false when no new expression is live). A `success=true` envelope despite the rebuild failing. `requiredExpressionRestored` absent or false WITHOUT the DELETED-and-manual-recovery error text (a post-delete failure reported as a benign no-op).

---

### T652 — a failed replaceRequiredExpression op inside a patches batch does not revert preceding ops

```json
{
  "setup_prompt": "the Write master is enabled and a hub backup exists within 24h. Note a valid switch device ID and two valid mode names (ModeA, ModeB) via hub_list_modes. Create RM rule 'BAT Patch Replace Scope' and add a Required Expression: conditions=[{capability:'Mode', state:'<ModeA>'}]. Confirm the mainPage paragraph renders 'Mode is <ModeA>'.",
  "test_prompt": "Call hub_set_rule on 'BAT Patch Replace Scope' with patches=[{addAction:{capability:'switch', deviceIds:[<switch id>], command:'on'}}, {replaceRequiredExpression:{conditions:[{capability:'Switch', deviceIds:[<switch id>], state:'definitely_not_a_valid_state'}]}}] and confirm=true (the replace op's new condition will FAIL TO BAKE). Inspect the patches[] results, then call hub_get_app_config.",
  "teardown_prompt": "Delete 'BAT Patch Replace Scope'."
}
```

**Expected**: the FIRST patch op (addAction switch on) committed and survives -- `hub_get_app_config` shows the switch-on action in the rule's actions. The SECOND patch op (replaceRequiredExpression) reports `success=false` with `requiredExpressionRestored=true`: its per-op restore rolled back ONLY to the state captured just before that op (which already includes the committed addAction), so the preceding action is preserved. The mainPage Required Expression paragraph still renders the ORIGINAL 'Mode is <ModeA>' (the failed replace's per-op restore put it back), NOT the bare 'Define Required Expression' placeholder.

**Failure modes**: the addAction is GONE after the batch (the failed replace op restored a pre-batch snapshot and reverted the earlier successful op -- the cross-op blast-radius bug this per-op-snapshot guard prevents). The Required Expression paragraph shows the bare placeholder (the original was deleted and not restored).

---

### T653 — rule-local variable lifecycle: addLocalVariable, list, setLocalVariable, removeLocalVariable

```json
{
  "setup_prompt": "the Write master is enabled. Create an RM rule called 'BAT LocalVar Test'.",
  "test_prompt": "On 'BAT LocalVar Test': (1) add a local variable via hub_set_rule addLocalVariable={name:'batLocal', type:'Number', value:0}. (2) List the rule's local variables with hub_list_rule_local_variables (in hub_read_rules) and confirm 'batLocal' appears. (3) Add an action setLocalVariable={variable:'batLocal', value:7} and note the actionIndex it returns. (4) Remove the action you just added (removeAction using that returned actionIndex), then remove the local with hub_set_rule removeLocalVariable={name:'batLocal'}. (5) List local variables again and confirm 'batLocal' is gone. Then call hub_get_rule_health and confirm no broken markers.",
  "teardown_prompt": "Delete the 'BAT LocalVar Test' rule."
}
```

**Expected**: `addLocalVariable` commits `batLocal` (verify via `hub_list_rule_local_variables`, which reads `state.allLocalVars` and returns `{appId, localVariables:[{name,type,value}], total}` -- `batLocal` present with `type='integer'`). `addAction setLocalVariable` completes `success=true` and routes to the same `modeActs/getSetVariable` wire as `setVariable` (`xVarV.N='batLocal'`, `numOp.N='number'`, `valNumber.N=7`), validating the target against the rule's locals (NOT hub globals). `removeLocalVariable` returns `success=true` with `variable.deleted=true` (the `deleteGV`/`delConfirm` commit wire is `stateAttribute=deleteGV` then `stateAttribute=deleteConfirm`); the follow-up list no longer contains `batLocal`. `hub_get_rule_health` reports no broken markers.

**Failure modes**: `setLocalVariable` returns "Unsupported capability" (capability not wired). `hub_list_rule_local_variables` returns empty or errors despite the local existing (wrong appState key read). The reference action is removed BEFORE the local so the rule stays healthy -- not because RM blocks a referenced-local delete (it does not; see T655). The list still shows `batLocal` after removal (verify miss not surfaced).

---

### T655 — removeLocalVariable on a still-referenced local: deletes the local AND breaks the rule (self-consistent failure)

```json
{
  "setup_prompt": "the Write master is enabled. Create an RM rule called 'BAT LocalVar Broken Test'; add a NUMERIC local variable 'refLocal' (type Number, value 0) to it, then add an action setLocalVariable={variable:'refLocal', value:9} that references it. Leave the referencing action in place.",
  "test_prompt": "On 'BAT LocalVar Broken Test': call hub_set_rule removeLocalVariable={name:'refLocal'} WITHOUT first removing the referencing action. Report success, variable.deleted, error, health.ok, and any repairHints. Then list local variables and confirm 'refLocal' is gone.",
  "teardown_prompt": "Delete the 'BAT LocalVar Broken Test' rule."
}
```

**Expected**: RM does NOT refuse the delete. The local IS deleted (`variable.deleted=true`) AND the referencing action is left Broken, so the envelope is a self-consistent FAILURE: `success=false`, a specific non-null `error` naming the broken-after-delete outcome (the local "was deleted, but that broke the action(s)/expression(s) that referenced it -- the rule is now broken"), `health.ok=false` with a `**Broken Action**` marker, and a `repairHint` pointing at the pre-delete backup restore (`hub_restore_backup` with the response `backupKey`) or removing the references first. The follow-up `hub_list_rule_local_variables` confirms `refLocal` is gone (the `deleted=true` is honest).

**Failure modes**: the tool reports a clean `success=true` "removed" despite the rule being broken (the self-contradictory `deleted=true`+`error=null`+clean-note shape). `error` is null when the rule is broken. No `repairHint` points at the backup restore. `variable.deleted` is false even though the local actually left `state.allLocalVars`. `health` not surfaced so the caller cannot see the broken state.

---

### T654 — setLocalVariable validates against locals, not hub globals (namespace distinction + cross-namespace operand)

```json
{
  "setup_prompt": "the Write master is enabled. Create a hub connector variable 'batShared' = 0 and a numeric hub connector variable 'batOperand' = 5 via hub_manage_variables. Create an RM rule called 'BAT LocalVar Namespace Test'; add a NUMERIC local variable 'batNum' (type Number, value 0) to it, but do NOT add a local named 'batShared'.",
  "test_prompt": "On 'BAT LocalVar Namespace Test': (1) add an action setLocalVariable={variable:'batShared', value:1} and observe it is rejected. (2) Then add an action setLocalVariable={variable:'batNum', math:{left:'batOperand', op:'negate'}} -- the math operand 'batOperand' is a HUB global -- and observe it is accepted.",
  "teardown_prompt": "Delete the 'BAT LocalVar Namespace Test' rule. Delete the hub variables 'batShared' and 'batOperand'."
}
```

**Expected**: (1) The first call is REJECTED as a `{success:false}` result Map (the addAction dispatcher wraps `_rmAddAction` in try/catch and returns a structured error Map, NOT a -32602 throw). The error message names `batShared` as not found and lists **local** variables ("Available local variables: ..."); a hub global of the same name does NOT satisfy a `setLocalVariable` target. This proves the option-B namespace split: `setLocalVariable` cannot silently target a same-named hub global. (Targeting `batShared` via `setVariable` -- the hub-global capability -- would succeed instead.) (2) The second call is ACCEPTED (`success=true`): the math operand picker (`xVar3`) spans BOTH namespaces, so a hub-global operand (`batOperand`) into a NUMERIC LOCAL target (`batNum`) is valid -- there is no locals-only operand restriction. `xVar3.N='batOperand'`.

**Failure modes**: the hub global `batShared` is accepted as a `setLocalVariable` target (namespaces conflated). The target-rejection throws -32602 instead of returning the structured `{success:false}` Map the dispatcher contract specifies. The error lists hub variables instead of local variables. The hub-global math operand `batOperand` is falsely rejected by a locals-only operand pre-check. The picker section-header sentinel ` --LOCAL VARIABLES--`/` --HUB VARIABLES--` is accepted as a target.

---

### T644 — hub_get_app_config summary mode: thin identity read without the config-page render

```json
{
  "setup_prompt": "The Read master is enabled. Pick any installed app id from hub_list_apps (scope='instances') — e.g. the MCP Rule Server itself.",
  "test_prompt": "Call hub_get_app_config with that appId and summary=true. Inspect the result: it must carry the app's identity fields (id, name/label, type, disabled) and must NOT contain a rendered config page (no configPage/sections). Then call it again WITHOUT summary and confirm the full mode still returns the rendered page.",
  "teardown_prompt": "Nothing to clean up (read-only)."
}
```

**Expected**: summary mode returns `success=true` with the thin identity record from `/installedapp/json/<id>` (id/name/type/disabled/user) and no `configPage`; full mode is unchanged. The AI reaches the tool via `hub_read_apps_code` (or `hub_manage_native_rules_and_apps`).

**Failure modes**: summary mode returning the full rendered page (mode ignored); missing identity fields; an error on a valid installed-app id; the AI calling `hub_list_apps` and stopping there instead of exercising the summary read.

---

### T645 — hub_call_device_swap: rewire every referencing app from one device to another (built-in Swap Device)

```json
{
  "setup_prompt": "The Write master is enabled and a hub backup exists (<24h). Create two virtual switches through the HUB UI (Devices > Add Device > Virtual, driver 'Virtual Switch'): 'BAT Swap Source' and 'BAT Swap Target' — or pick two existing compatible FREE-STANDING test devices. Do NOT create them via hub_manage_virtual_device: MCP-created virtual devices are child devices of the MCP app, and the hub's Swap Device tool excludes app-owned child/component devices from BOTH its pickers, so the scenario would fail with the eligibility error. Note both device IDs. Then create RM rule 'BAT Swap Rule' with hub_set_rule using addTrigger {capability:'Switch', deviceIds:[<BAT Swap Source id>], state:'on'}.",
  "test_prompt": "Preview which apps reference 'BAT Swap Source' with hub_list_device_dependents — 'BAT Swap Rule' must be listed. Then call hub_call_device_swap with from_device_id=<BAT Swap Source id>, to_device_id=<BAT Swap Target id>, confirm=true. Inspect result.success, result.swapped, result.appsRewired, and result.remainingDependents. Verify the swap took: hub_list_device_dependents on 'BAT Swap Source' no longer lists 'BAT Swap Rule', hub_list_device_dependents on 'BAT Swap Target' now does, and hub_get_app_config on 'BAT Swap Rule' shows the trigger device is now 'BAT Swap Target'.",
  "teardown_prompt": "Delete the rule 'BAT Swap Rule' with hub_delete_native_app. Delete both UI-created virtual switches 'BAT Swap Source' and 'BAT Swap Target' with hub_delete_device (confirm=true) — hub_manage_virtual_device only deletes MCP child devices and cannot remove them."
}
```

**Expected**: AI calls `hub_manage_devices(tool=hub_call_device_swap)` with `from_device_id`/`to_device_id`/`confirm=true` after previewing the blast radius with `hub_list_device_dependents`. The response is `success=true` with `swapped={from, to}`, `appsRewired>=1` (the rule referenced the source device before the swap), and `remainingDependents=0`. The dependents move: source device lists no apps afterwards, target device lists 'BAT Swap Rule', and the rule's `tDev` trigger setting now carries the target device ID. No orphaned "Swap Device" instance remains in the hub's Apps list (the transient instance closes itself or is closed by the tool).

**Failure modes**: the tool refuses without `confirm` or a recent backup (expected gate — but the AI must then create the backup, not bypass). The eligibility error `"The hub's Swap Device tool does not offer device <id> as swappable."` (with `oldDevOptionCount`) — expected and correct if a fixture was created via `hub_manage_virtual_device` (child device, always ineligible; this is exactly how `tests/e2e_test.py` pins the error path) but a setup failure for THIS scenario, which requires free-standing devices. An incompatible-target error even though both devices are free-standing virtual switches of the same driver (the hub should offer the target as compatible). `success=true` but `hub_list_device_dependents` still lists the rule under the source device (the click did not commit). A leftover transient Swap Device instance in the Apps list after the call (cleanup contract broken). Swapping via manual rule edits (`hub_set_rule`) instead of `hub_call_device_swap` is a routing failure for this scenario.

---

### T703 — hub_call_device_replace: re-point a device onto replacement hardware, preserving its id

```json
{
  "setup_prompt": "Make sure the Write master is on and a hub backup exists (<24h). Through the Hubitat UI (Devices > Add Device > Virtual, driver 'Virtual Switch') — NOT the MCP virtual-device tool, since MCP child devices are ineligible — add two compatible free-standing virtual switches named 'BAT Replace Keep' and 'BAT Replace Donor', and note both device IDs. Then set up a Rule Machine rule named 'BAT Replace Rule' that does something when 'BAT Replace Keep' turns on, so a real automation references that device.",
  "test_prompt": "The device 'BAT Replace Keep' has failed and you've paired a compatible replacement, 'BAT Replace Donor', to the hub. Replace the failed device's hardware with the new unit while keeping the original device's identity and all of its automations intact — the original device should keep working as the same device, with 'BAT Replace Rule' still attached to it afterward. Check what the hub considers a compatible replacement before you commit, then confirm afterward that the original device still exists and its rule still references it.",
  "teardown_prompt": "Remove the 'BAT Replace Rule' rule and delete the surviving 'BAT Replace Keep' device (now running on the replacement's hardware). The donor device is consumed by the operation; delete it too if it still appears."
}
```

**Expected**: AI calls `hub_call_device_replace(list_options=true)` first to read the compatible candidates, then `hub_call_device_replace` with `old_device_id`/`new_device_id`/`confirm=true`. The response is `success=true` with `replaced={oldDeviceId, newDeviceId}` and `preservedDeviceId=<old id>`. The KEY invariant: the OLD device id survives and keeps all its app/rule references — `hub_list_device_dependents` on the old id still lists 'BAT Replace Rule' (replace re-points hardware; it does NOT migrate references like swap does).

**Failure modes**: the tool refuses without `confirm` or a recent backup (expected gate — create the backup, don't bypass). `list_options` returns an empty list even though a compatible donor exists (the hub did not offer it — usually a fixture is an MCP child device or not capability-compatible; this scenario requires free-standing same-driver devices). A structured `success=false` naming an incompatible `new_device_id` (the donor was not in the hub's compatible set — re-read `list_options`). `preservedDeviceId` is the NEW id instead of the old one, or the rule's dependents move to the donor (that would be swap behaviour, not replace — a wire-format regression). Routing the request to `hub_call_device_swap` instead of `hub_call_device_replace` (swap migrates references onto the new id; replace keeps the old id — different end states).

---

### T704 — manage location modes end-to-end (create, activate, rename, Mode Manager, delete)

```json
{
  "setup_prompt": "Make sure the Write master is on and a hub backup exists (<24h). Note the hub's current location mode so it can be restored at the end.",
  "test_prompt": "I want a new house mode called 'Vacation'. Add it, then switch the house into it. After that, rename 'Vacation' to 'Holiday'. Tell me which built-in automation is currently in charge of changing the house mode automatically. Finally, switch the house back to its normal everyday mode and get rid of the 'Holiday' mode entirely.",
  "teardown_prompt": "If the 'Vacation'/'Holiday' mode still exists, make sure the house is on a normal mode and then remove it. Leave the active mode as it was before the test."
}
```

**Expected**: the AI discovers the mode-management surface itself — it creates the mode, activates it, renames it, reports which Mode Manager runs, restores the everyday mode, and deletes the test mode. It should route the create/rename/activate/delete to `hub_manage_mode` (`action` enum), read the active manager from `hub_list_modes` (`modeManager.selected`), and may use `hub_set_mode_manager` only if it changes the manager. The delete must be `confirm=true`-gated and is performed only after the active mode is something other than the mode being deleted. Each step's result should report `success=true`, and a final `hub_list_modes` should no longer list the test mode.

**Failure modes**: the AI tries to create/delete modes by editing `configuration.yaml`-style files or a rule instead of the mode tool (wrong surface). Deleting the mode while it is the *current* mode (the hub refuses; the AI must switch away first). Calling `hub_manage_mode` delete without `confirm` (expected gate — it must create/confirm a backup, not bypass). Inventing a separate "Mode Manager" tool when the manager state is simply read from `hub_list_modes`. Leaving the test mode behind, or leaving the house on the test mode instead of restoring the original.

---

### T646 — hub_list_device_events appId mode: per-app event history

```json
{
  "setup_prompt": "The Read master is enabled. Pick an installed app likely to have emitted events recently (the MCP Rule Server instance, or a BAT rule that just fired).",
  "test_prompt": "Call hub_list_device_events with appId=<that app's id> (no deviceId). Inspect the result: source must be 'app', the events list rows carry {name, value, description, date}, and a limit parameter is respected. Then call it with BOTH deviceId and appId and confirm it is rejected as invalid arguments.",
  "teardown_prompt": "Nothing to clean up (read-only)."
}
```

**Expected**: appId mode returns `source='app'` with normalized rows from `/installedapp/eventsJson/<id>` (client-side hoursBack/attribute/limit filtering applies); `deviceId`+`appId` together is rejected with an invalid-params error (-32602) naming the conflict. The AI discovers the mode from the tool description ("events emitted by an app or rule").

**Failure modes**: app events silently treated as device events (wrong source); the mutual-exclusivity rejection missing (both params accepted); a row shape missing name/value/date; the AI failing to find the capability and claiming per-app events are unsupported (description/BM25 regression).

---

### T656 — hub_list_device_events since bookmark: only-new-events round-trip

```json
{
  "setup_prompt": "Create a BAT virtual switch (hub_manage_virtual_device, deviceType 'Virtual Switch'). Toggle it on, then off, so it has a couple of recent events.",
  "test_prompt": "Call hub_list_device_events for the BAT switch with hoursBack=1 and note the most recent event's `date`. Now toggle the switch again (one more command). Then call hub_list_device_events for the same device passing `since` = that recorded `date`. Confirm the response returns ONLY the events that happened after the bookmark (not the pre-bookmark ones), that `sinceMode` is 'explicit', that `since` is echoed back, and that no `hoursBack` field is present. Finally call it once with `since` set to a clearly future timestamp and confirm an empty events list (count 0, not an error).",
  "teardown_prompt": "Delete the BAT virtual switch (hub_manage_virtual_device action='delete')."
}
```

**Expected**: with `since` supplied the result routes to history mode, `sinceMode='explicit'`, `since` is echoed, `hoursBack` is omitted, `sinceTimestamp` equals the supplied bookmark, and only post-bookmark events are returned. A future `since` yields `count: 0` with an empty list (valid, not an error). A returned `date` fed straight back as `since` parses cleanly (round-trip). An unparseable `since` would be rejected with -32602.

**Failure modes**: `since` ignored and recent-N or full-hoursBack events returned; `hoursBack` still echoed as if it bounded the window; future `since` throwing instead of returning empty; a returned `date` failing to parse when fed back as `since` (format drift); `sinceMode` missing.

---

## Section 16: Visual Rules Builder Tests (hub_get_visual_rule / hub_set_visual_rule / hub_delete_visual_rule)

The Visual Rules Builder tools live in the `hub_manage_rule_machine` gateway (the read, `hub_get_visual_rule`, is also in `hub_read_rules`). Reads require the Read master; `hub_set_visual_rule` / `hub_delete_visual_rule` require the Write master + `confirm=true` + a hub backup within 24h. The Visual Rules Builder parent app must be installed on the hub (the list mode returns an actionable error if not).

### Safety Rules for Section 16

- Only create/edit/delete **BAT-prefixed** Visual Rules created within the same scenario — never touch existing Visual Rules
- Device-facing nodes only target BAT-created virtual switches
- The current firmware creates **classic**-format rules (`whenNodes`/`thenNodes`/`elseNodes`); if a hub creates graph-format rules instead, `hub_set_visual_rule` returns `hubNativeFormat: "graph"` and cleans up the empty child — re-issue with a graph definition in that case

### T700 — hub_set_visual_rule create + hub_get_visual_rule read-back (classic format)

```json
{
  "setup_prompt": "The Write master is enabled and a hub backup was created within the last 24 hours (call hub_create_backup if unsure). Create a virtual switch named 'BAT VRB Switch' via hub_manage_virtual_device and note its device ID.",
  "test_prompt": "Create a Visual Rules Builder rule named 'BAT VRB Create' with hub_set_visual_rule (confirm=true): when 'BAT VRB Switch' turns on, turn it off. Use the classic definition format: {whenNodes:[{triggerType:'switch', switches:[<id>], deviceIds:[<id>], switchEvent:'Turns on', index:0, type:'when'}], thenNodes:[{actionType:'turnOff', switches:[<id>], deviceIds:[<id>], index:0, type:'then'}], elseNodes:[]}. Then read it back with hub_get_visual_rule and report the appId, format, and whether the persisted definition matches what was sent.",
  "teardown_prompt": "Delete the Visual Rule 'BAT VRB Create' with hub_delete_visual_rule (confirm=true). Delete the virtual switch 'BAT VRB Switch'."
}
```

**Expected**: AI calls `hub_manage_rule_machine(tool=hub_set_visual_rule)` with NO `appId`, `name='BAT VRB Create'`, the classic `definition`, and `confirm=true`. The response returns a new `appId`, `created=true`, `format='classic'`, and `verified=true` (the tool's read-back confirmed the persisted name); the echoed `definition` contains the whenNode/thenNode pair. A follow-up `hub_get_visual_rule(appId=N)` returns `success=true`, `format='classic'`, `name='BAT VRB Create'`, `rulePaused=false`, and the same `whenNodes`/`thenNodes`. The rule also appears in list mode (`hub_get_visual_rule` with no `appId` — `rules[]` contains `{appId, name:'BAT VRB Create', disabled:false}`).

**Failure modes**: the call is issued without `confirm=true` (rejected -32602 — the AI must include it after user approval, not retry blindly). `verified=false` (the save POST was sent but the read-back did not confirm — inspect with `hub_get_visual_rule`). A format-mismatch response (`success=false` + `hubNativeFormat='graph'`) on graph-native firmware — the AI should re-issue with a graph definition, and the empty child app created during the attempt must have been cleaned up, not stranded. The AI routes to `hub_set_native_app(appType='visual_rule')` instead — that path now throws with a redirect to `hub_set_visual_rule`.

### T701 — hub_set_visual_rule pause + rename without touching the definition

```json
{
  "setup_prompt": "The Write master is enabled and a recent backup exists. Create a virtual switch 'BAT VRB PauseSwitch' and a Visual Rule named 'BAT VRB Pause' via hub_set_visual_rule (classic definition: when the switch turns on, turn it off). Note the returned appId.",
  "test_prompt": "First pause the Visual Rule 'BAT VRB Pause' (hub_set_visual_rule with its appId, paused=true, confirm=true) and verify rulePaused=true via hub_get_visual_rule. Then rename it to 'BAT VRB Renamed' and resume it in one call (name + paused=false, NO definition). Verify via hub_get_visual_rule that the name changed, rulePaused=false, and the whenNodes/thenNodes are UNCHANGED from setup.",
  "teardown_prompt": "Delete the Visual Rule 'BAT VRB Renamed' with hub_delete_visual_rule (confirm=true). Delete the virtual switch 'BAT VRB PauseSwitch'."
}
```

**Expected**: the pause call returns `success=true` with `rulePaused=true` (pause state rides `GET /app/ruleBuilderPause/<id>/true` under the hood); `hub_get_visual_rule` confirms. The rename+resume call (with `appId`, `name`, `paused=false`, no `definition`) re-saves the EXISTING nodes under the new name — the read-back shows `name='BAT VRB Renamed'`, `rulePaused=false`, and the original whenNode/thenNode pair intact. No `definition` replacement happens.

**Failure modes**: the rename wipes the nodes (empty `whenNodes`/`thenNodes` after the rename — the re-save dropped the existing definition). The pause flag is silently ignored (`rulePaused` stays `false` after `paused=true`). The AI re-sends the whole definition just to rename (works, but a Partial — `name`-only is the point). A no-op call (`appId` with no `definition`/`name`/`paused`) must reject -32602 "Nothing to change", not return `success=true`.

### T702 — hub_delete_visual_rule type-gate refusal + verified delete with recovery definition

```json
{
  "setup_prompt": "The Write master is enabled and a recent backup exists. Create (1) a virtual switch 'BAT VRB DelSwitch', (2) a Visual Rule named 'BAT VRB Delete' via hub_set_visual_rule (classic definition over that switch), and (3) a Rule Machine rule named 'BAT VRB NotVisual' via hub_set_rule. Note both appIds.",
  "test_prompt": "First call hub_delete_visual_rule on the RM rule 'BAT VRB NotVisual' (confirm=true) and report what happens. Then delete the Visual Rule 'BAT VRB Delete' with hub_delete_visual_rule (confirm=true) and report the verified flag and whether the response includes the pre-delete definition.",
  "teardown_prompt": "Delete the RM rule 'BAT VRB NotVisual' with hub_delete_native_app (confirm=true). Delete the virtual switch 'BAT VRB DelSwitch'. Confirm via hub_get_visual_rule (list mode) that no rule named 'BAT VRB Delete' remains."
}
```

**Expected**: the first call does NOT delete anything: the RM rule's appId fails the VRB type-gate and returns `success=false` with the app's real type (e.g. `appType: 'Rule Machine'` / `'Rule-5.1'`) and a note redirecting to `hub_delete_native_app` — the RM rule still exists afterward (verify with `hub_list_rules`). The second call deletes the Visual Rule: `success=true`, `verified=true` (the app is confirmed gone), and `predeleteDefinition` carries the whenNodes/thenNodes so the rule could be recreated via `hub_set_visual_rule`. A follow-up `hub_get_visual_rule(appId=N)` returns `success=false` ("No installed app...") and list mode no longer contains the rule.

**Failure modes**: the type-gate is bypassed and the RM rule is force-deleted (the exact failure the gate exists to prevent — automatic Fail). `predeleteDefinition` missing from the delete response (the recovery contract broken). `verified=false` with the app still present but reported as deleted. A nonexistent-appId delete returning anything other than the structured `success=false` "No installed app with appId N" envelope.

---

## Section 17: Easy Dashboard Tests (hub_manage_dashboards / hub_read_dashboards)

Easy Dashboard CRUD (issue #259 item #9). The 6 tools live in the `hub_manage_dashboards` gateway; the two reads (`hub_list_dashboards`, `hub_get_dashboard`) are also surfaced in the pure-read `hub_read_dashboards` gateway. Reads require the Read master; create/update/clone require the Write master; delete additionally requires `confirm=true` + a hub backup within 24h. Easy Dashboards are classic child apps of the Easy Dashboard Parent, driven by `GET /dashboard/*` endpoints. The list endpoint may be pinToken-gated on some hubs (an unexpectedly-empty list is the tell).

### Safety Rules for Section 17

- Only create/edit/delete **BAT-prefixed** dashboards created within the same scenario — never touch existing dashboards
- Dashboards only ever reference BAT-created virtual switches
- Deleting a dashboard does NOT delete its devices

### T720 — Create an Easy Dashboard, read it back, then delete it

```json
{
  "setup_prompt": "The Write master is enabled and a hub backup was created within the last 24 hours (call hub_create_backup if unsure). Create a virtual switch named 'BAT Dash Switch' and note its device ID.",
  "test_prompt": "Create an Easy Dashboard called 'BAT Dash Create' that shows the 'BAT Dash Switch' device and turns on the clock tile. Then show me that dashboard's configuration and confirm the clock tile is on and the switch is on it.",
  "teardown_prompt": "Delete the Easy Dashboard 'BAT Dash Create'. Delete the virtual switch 'BAT Dash Switch'."
}
```

**Expected**: the AI routes to `hub_manage_dashboards(tool=hub_create_dashboard)` with `name='BAT Dash Create'`, `deviceIds=[<id>]`, `showClockTile=true`, no `confirm` needed (create needs only the Write master). The response returns `success=true` and (when the hub provides it) the new dashboard `id`. A follow-up `hub_get_dashboard(id=N)` returns the dashboard with `showClockTile` truthy and the device present. The dashboard also appears in `hub_list_dashboards`.

**Failure modes**: the AI sends `deviceIds` as an empty list (rejected -32602 — an Easy Dashboard needs ≥1 device). The booleans go over the wire as JSON booleans instead of the strings "true"/"false" (wire-format break — the dashboard's tile would not toggle). `hub_get_dashboard` called without an id (rejected -32602). The list comes back empty after a successful create (the read may be pinToken-gated — retry `hub_list_dashboards` with a pinToken).

### T721 — Update replaces the config wholesale (read-first)

```json
{
  "setup_prompt": "The Write master is enabled and a recent backup exists. Create a virtual switch 'BAT Dash UpSwitch' and an Easy Dashboard 'BAT Dash Update' showing that switch with the clock tile ON. Note the dashboard id.",
  "test_prompt": "Rename the Easy Dashboard 'BAT Dash Update' to 'BAT Dash Renamed' and switch its theme to dark, keeping the same device. Then read it back and confirm the new name, the dark theme, the device still present, and the clock tile still ON.",
  "teardown_prompt": "Delete the Easy Dashboard 'BAT Dash Renamed'. Delete the virtual switch 'BAT Dash UpSwitch'."
}
```

**Expected**: because `hub_update_dashboard` replaces the config WHOLESALE (no server-side read-merge), the correct flow is `hub_get_dashboard` first to capture the full current config, then `hub_update_dashboard(id=N, name='BAT Dash Renamed', deviceIds=[<id>], theme='dark', showClockTile=true, ...)` carrying every field. The read-back shows the new name, `theme='dark'`, the device present, and the clock tile still on.

**Failure modes**: the AI calls update with only `name` + `theme` and omits `deviceIds`/`showClockTile` (rejected -32602 for missing deviceIds; or, if a future caller bypassed the guard, the clock tile and devices would silently revert to defaults — the wholesale-replace trap the description warns about). update called without an `id` or without a `name` (both rejected -32602).

### T722 — Clone an Easy Dashboard

```json
{
  "setup_prompt": "The Write master is enabled and a recent backup exists. Create a virtual switch 'BAT Dash CloneSwitch' and an Easy Dashboard 'BAT Dash CloneSrc' showing that switch. Note the dashboard id.",
  "test_prompt": "Make a copy of the Easy Dashboard 'BAT Dash CloneSrc'. Then list the Easy Dashboards and confirm a clone now exists alongside the original.",
  "teardown_prompt": "Delete both the original 'BAT Dash CloneSrc' and the clone. Delete the virtual switch 'BAT Dash CloneSwitch'."
}
```

**Expected**: the AI routes to `hub_manage_dashboards(tool=hub_clone_dashboard)` with the source `id`; the response returns `success=true`, `sourceId`, and (when the hub provides it) `newId`. `hub_list_dashboards` shows both the source and the clone.

**Failure modes**: clone called without an `id` (rejected -32602). The clone is reported successful but never appears in the list (verify with `hub_list_dashboards` — the clone may need a pinToken to surface).

---

## Changes from BAT v1

Key differences from the original BAT.md (which targets the pre-v0.8.0 architecture):

1. **Architecture**: 18 core + 8 gateways (26 total) → **11 flat core + 19 gateways (30 on tools/list, 99 total distinct tools)** post installed-apps + RM interop + native CRUD + hub_list_app_pages + poll_until_attribute + library management + HPM package state + the PR1B read/write gateway split (was 23 core + 13 gateways / 36 total / 103 tools before PR1B; 21 core + 9 gateways / 30 total / 69 tools at v0.8.0)
2. **Merged tools**: `enable_rule`/`disable_rule` → `hub_update_custom_rule` (enabled=true/false); `create_virtual_device`/`delete_virtual_device` → `hub_manage_virtual_device` (action enum)
3. **Promoted to core**: `hub_create_backup`, `hub_update_firmware` (the firmware INSTALL; the update-status reads folded into `hub_get_info`), `hub_report_issue`
4. **Dissolved gateway**: `manage_hub_info` — radio details moved to `hub_manage_diagnostics`, other tools merged into `hub_get_info` (core) or promoted
5. **Gateway renames**: `manage_hub_maintenance` → `hub_manage_destructive_ops` (3 tools); `manage_code_changes` → `hub_manage_code_write` (10 tools, 7 original + 3 library tools)
6. **Gateway splits from v1**: `hub_manage_code_read` → `hub_manage_code_read` (7 read) + `hub_manage_code_write` (10 write); `manage_logs_diagnostics` → `hub_manage_logs` (8) + `hub_manage_diagnostics` (11)
7. **T62 rewritten**: Was testing `manage_virtual_devices` catalog (removed gateway) → now tests `hub_manage_diagnostics` catalog
8. **T104 updated**: Anti-recursion test uses `hub_manage_diagnostics` gateway
9. **Excluded tests expanded**: 10 → 13 (separate rows for each app/driver operation, added gateway column)
10. **Corrected test count**: 159 → 172 (was undercounted in v1); addAction capability completeness adds T607/T608/T609/T610 (176 total); walker parity adds T611 (177 total); Between two times coverage adds T612 (178 total); singular deviceId normalization adds T613 (179 total); paired-tool singular-deviceId coverage adds T614 (addTrigger.condition) + T615 (addAction expression) (181 total); subExpression rejection on addAction adds T616 (182 total -- T616 previously covered recursive subExpression normalization, which production now rejects at the doActPage pre-pass; T616 was rewritten to pin the rejection path); reveal-fallback sentinel adds T617 (183 total); compareToDevice device-relative adds T618 (184 total); Between two times sunrise/sunset adds T619 (185 total); Variable compareToVariable on the walker pages adds T620, compareToDevice missing-comparator reject adds T621, and Custom-Attribute '*changed*' cosmetic-partial filter adds T622 (188 total); periodic-frequency completeness adds T623/T624/T625/T626/T627 (the five newly-supported frequencies: Seconds/Minutes/Weekly/Monthly/Yearly -- Monthly by-day and Yearly nth-weekday) + T628 (Cron field-name fix) + T629 (count-enum validation rejection) + T630 (Monthly nth-weekday mode) + T631 (Monthly dayOfMonth/weekOfMonth mutual-exclusivity rejection) + T632 (two periodic triggers in one rule -- no sub-page collision) (198 total); the native-app tool rename adds T633 (hub_set_rule single-call create-with-bundle: new rule + trigger + action in one upsert) (199 total); Custom-Attribute enum-attribute false-positive-partial guard adds T634 (trigger row), T635 (conditional-trigger condition path), T636 (Required Expression reveal walker / STPage enum condition), and T637 (free-valued Custom Attribute comparator: value lands normally) (203 total); the doActPage walker enum-condition parity (the 4th surface, reached via addAction ifThen) adds T638 (204 total); create-with-bundled-Required-Expression adds T639 and the create-arm edit-only-rejection completeness contract adds T640 (206 total); the doActPage compareToDevice device-relative happy-path (the addAction/ifThen mirror of T618) adds T641 (207 total -- numbered out of sequence because T619/T620 were already taken by the Between-two-times and compareToVariable scenarios); the enum-attribute no-RHS state-change comparator splits across both live outcomes -- T642 (trigger surface ROUTES via the picker's change option) + T643 (Required Expression surface SKIPS, no change option) (209 total); the two new setVariable source modes add T647 (fromDevice / numOp="device attribute") and T648 (math / numOp="variable math", binary + unary arity) (211 total -- numbered T647/T648 because T642/T643 were taken by the enum-attribute comparator scenarios above and T644/T645/T646 by the app-config-summary / device-swap / event-history scenarios); the in-place Required Expression replace (cancelST delete + rebuild) adds T649 (happy-path replace), the no-existing-RE refusal adds T650, the failed-rebuild auto-restore (no data loss) adds T651, and the patches-batch per-op restore-scope guard (a failed replace op does not revert preceding ops) adds T652 (215 total); the rule-local variable lifecycle adds T653 + the namespace distinction adds T654 (217 total); the removeLocalVariable broken-after-delete contract (RM deletes a still-referenced local and leaves the rule broken; the envelope reports a self-consistent failure) adds T655 (218 total); the hub_list_device_events since-bookmark round-trip adds T656 (219 total -- numbered T656 out of sequence to avoid colliding with the setVariable T647 above). Note: `Total: 248 test scenarios` in the header above counts ALL scenarios including the NL (T501-T565 range), built-in-app integration (T801-T821 range), library management (T901-T909 range), and the unnumbered walker/normalization sub-scenarios. The cumulative T-numbered tally in this item (ending at 215) reflects only sequentially-numbered tests in the explicit-coverage section.
11. **Spec-only coverage by necessity**: the trailing-updateRule failure paths on `addTrigger`, `addRequiredExpression`, `addLocalVariable`, `addTriggers`/`addActions` (bulk), `patches`, and the action-mutation/trigger-mutation dispatchers (`removeAction`, `clearActions`, `replaceActions`, `moveAction`, `removeTrigger`, `modifyTrigger`) -- response slots `updateRuleFailed`, `subscriptionsNotLive` / `expressionNotLive` / `variableNotLive` / `patchesNotLive`, `updateRuleError`, and the recovery `repairHints` line -- are covered exclusively by Spock specs in `src/test/groovy/server/ToolRmNativeCrudSpec.groovy` (the single-path failure/SUCCESS pairs for `addTrigger` / `addRequiredExpression` / `addLocalVariable`, the three-row `@Unroll` failure/SUCCESS pairs for the bulk path covering `addTriggers`-only / `addActions`-only / both, and the corresponding patches and action-mutation envelope specs). The defensive `asyncCommitLikely` path on `clearActions` / `replaceActions` -- response slots `asyncCommitLikely`, `stage`, `actionsRequestedForRemoval`, `actionsStillPresent`, `pendingActionsToAdd`, `clearActionsResult`, `safeRecovery` -- is also spec-only: with the synchronous full-form trashActs submit the delete commits in-band, so this rare residual path (stuck `state.editAct` or a firmware commit lag still showing the actions present after the verify-retry) is not reproducible from an agent prompt against a live hub. Live-hub BAT coverage was considered but skipped: deterministically forcing the trailing `_rmClickAppButton(updateRule)` to throw against a real hub requires hub-side disruption (firmware downgrade / network partition mid-call / hub-config corruption) that is not realistically scriptable from an agent prompt. The Spock specs exercise the production response-shape contract directly via stub injection and constitute the regression gate. The delete-helper **re-click recovery** is likewise spec-only: RM's silent no-op of the FIRST `trashActs`/`trashTrigs` delete click (the dropped-first-click signature `removeAction`/`removeTrigger` recover from with one verified re-click, surfaced as `reclicked: true`) cannot be forced from an agent prompt against a live hub, so `ToolRmNativeCrudSpec`'s re-click + exhaustion specs are the regression gate for that path.
12. **PR1B read/write gateway split**: the 13-gateway flat-core layout was restructured into **19 gateways (7 `hub_read_*` pure-read + 12 `hub_manage_*` write-bearing) + 11 flat core tools** (30 on tools/list). Gateway renames: `hub_manage_rules` → `hub_manage_custom_rules`, `hub_manage_code_write` → `hub_manage_code`, `hub_manage_native_rules` → `hub_manage_native_rules_and_apps`. Removed gateways (read tools folded into `hub_read_apps_code`): `hub_manage_code_read`, `hub_manage_installed_apps`, `hub_manage_hpm`. `hub_list_installed_apps` merged into `hub_list_apps` (scope=types|instances). New pure-read gateways added: `hub_read_apps_code`, `hub_read_devices`, `hub_read_diagnostics`, `hub_read_files`, `hub_read_rooms`, `hub_read_rules`, `hub_read_variables`. Reads listed in a mixed `hub_manage_*` gateway are also surfaced in their `hub_read_*` gateway (multi-membership). Tool-behavior flips: `hub_export_custom_rule` and `hub_export_native_app` are now WRITES (they persist via saveAs); `hub_get_metrics` is now a READ (recordSnapshot default false). The previously-flat custom-rule and device tools (`hub_get_custom_rule`, `hub_create_custom_rule`, `hub_update_custom_rule`, `hub_list_devices`, `hub_get_device`, etc.) are now folded into gateways. `hub_report_issue` remains a flat core tool.

---

## Appendix: RM Wizard-State Leak Probe (wizard_probe.py)

See [`tests/wizard_probe.py`](./wizard_probe.py) and [`tests/wizard_probe_matrix.yaml`](./wizard_probe_matrix.yaml).

These are not BAT scenarios (they run autonomously, not via AI session prompts), but they test the same system surface and are documented here for discoverability.

### What the probe does

The wizard probe systematically tests RM 5.1 wizard sub-flow sequences that have historically produced "**Broken Condition**" markers, silent setting rejections, or mis-labeled action rows when wizard accumulator state leaks across operations.

For each probe:
1. Creates a fresh RM rule via `hub_manage_rule_machine hub_set_rule` (name only, no appId)
2. Executes a sequence of `hub_set_rule` calls (appId + addTrigger, addRequiredExpression, addAction, etc.)
3. Snapshots the rule's `mainPage` render via `hub_read_apps_code hub_get_app_config` after each step
4. Evaluates expectations against the final render (e.g. `Broken Condition` must NOT be present)
5. Deletes the test rule in a `try/finally` block regardless of outcome

### How to run

```bash
# Full matrix (all 25 probes)
uv run --python 3.12 --with requests --with pyyaml tests/wizard_probe.py \
  --matrix tests/wizard_probe_matrix.yaml

# Single probe
uv run --python 3.12 --with requests --with pyyaml tests/wizard_probe.py \
  --matrix tests/wizard_probe_matrix.yaml --probe A1_addRE_then_addAction

# Group only
uv run --python 3.12 --with requests --with pyyaml tests/wizard_probe.py \
  --matrix tests/wizard_probe_matrix.yaml --group A

# With baseline comparison (regression check after firmware upgrade)
uv run --python 3.12 --with requests --with pyyaml tests/wizard_probe.py \
  --matrix tests/wizard_probe_matrix.yaml \
  --baseline tests/wizard_probe_results/20260501_231240.json

# Clean up stale _PROBE_* rules from failed prior runs
uv run --python 3.12 --with requests --with pyyaml tests/wizard_probe.py \
  --matrix tests/wizard_probe_matrix.yaml --cleanup

# Auto-create a hub backup if none exists (skips interactive prompt)
uv run --python 3.12 --with requests --with pyyaml tests/wizard_probe.py \
  --matrix tests/wizard_probe_matrix.yaml --auto-backup
```

### Config

Hub connection from `tests/e2e_config.json` (same format as `e2e_test.py`), with env var overrides:
- `HUB_URL` (or `HUBITAT_HUB_URL`) -- required (no default; set via env var or e2e_config.json)
- `MCP_APP_ID` (or `HUBITAT_APP_ID`) -- required (no default; set via env var or e2e_config.json)
- `MCP_ACCESS_TOKEN` (or `HUBITAT_ACCESS_TOKEN`)

### Output

Results written to `tests/wizard_probe_results/<timestamp>.json` and `.md`.
Non-zero exit code if any probe fails (useful as CI gate).

### How to add new probes

1. Add an entry to `tests/wizard_probe_matrix.yaml` under `probes:`.
2. Choose a `group` (A/B/C/D or a new letter), a unique `name`, and a `description`.
3. List `steps:` as a sequence of single-key operation dicts (see existing probes for shapes).
4. Declare `expect:` assertions -- at minimum `final_render_NOT_contains: "Broken Condition"`.
5. Use `$switch`, `$contact`, etc. to reference devices from `device_pool` (resolved to integer IDs at runtime).
6. If the step shape is not yet known, set `expect: { skip: true, todo: "<note>" }` to mark it as TODO without failing.

**Available step ops:**

| Step key | Description |
|---|---|
| `addTrigger` | High-level addTrigger spec (capability + fields) |
| `addAction` | High-level addAction spec (capability + fields) |
| `addRequiredExpression` | High-level RE spec (conditions list) |
| `addTriggers` | Bulk list of trigger specs |
| `addActions` | Bulk list of action specs |
| `replaceActions` | Replace entire action list atomically |
| `clearActions: true` | Delete all actions |
| `removeAction` | Delete action at `{index: N}` |
| `moveAction` | Move action at `{index: N, direction: up/down}` |
| `addLocalVar` | Add local variable `{name, type, value}` |
| `settings` | Raw settings map write |
| `button` | Page-transition button click by name |
| `setLabel` | Set rule title (shorthand for `settings: {ruleTitle: ...}`) |
| `pauseRule: true` | Click pausRule button |
| `resumeRule: true` | Click resRule button |
| `updateRule: true` | Click updateRule button |
| `getAppConfig: true` | Read-only snapshot (no mutation) |

**Available expect keys:**

| Key | Assertion |
|---|---|
| `final_render_NOT_contains` | String must NOT appear in concatenated paragraph text |
| `final_render_contains` | String must appear in paragraph text |
| `final_settings_contains_key` | Key must be present in settings map |
| `final_settings_value` | Dict of `{key: expected_value}` equality assertions |
| `no_step_errors` | No step returned an error |
| `health_ok` | Rule health check embedded in snapshot must be ok |
| `skip: true` | Mark probe as TODO (always passes, noted as skipped) |
| `todo` | Narrative reason for the skip |

### Baseline mode (regression checks)

After a baseline run, save the JSON output path. On the next run (e.g., after a firmware upgrade), pass `--baseline <path>` to emit a diff showing which probes newly fail, newly pass, or have changed render output. This catches silent RM behavior regressions.

### Current probe status (as of 2026-05-02)

**All 25 probes: PASS** -- Bug #77 fix (ghost ifThen predCapabs clear) deployed and verified. Full 25-probe matrix run recorded in `tests/wizard_probe_results/20260502_014427.md`.

### Known false positives / probe quirks

- **A6** (STPage sub-expression): uses a simplified 3-condition RE rather than a true sub-expression because the sub-expression `{subExpression: ...}` shape has not been validated live. The simplified form is still a useful regression gate for multi-condition RE behavior.
- **A10** (walkStep introspect): uses `getAppConfig: true` as a read-only substitute for the walkStep introspect path, because the exact mid-edit walkStep shape is not yet validated live. The probe still covers the primary concern (read-only snapshot does not corrupt actNdx).
- The "Local Variables" HTML table paragraph in the render text is long and present in all rules; it does not indicate a bug.

### Diag mode -- `quick_probe()` importable API

The matrix runner is for regression -- enumerate known patterns and assert outcomes.
When investigating a *new* suspected bug or verifying a fix hypothesis, the same
plumbing is available as a one-liner Python call via `quick_probe()`.

**When to use diag mode vs the matrix:**

| Scenario | Use |
|---|---|
| Firmware upgrade regression check | `--matrix` runner |
| After a code deploy (issue #77 class) | `--matrix` runner |
| Investigating a new suspected state leak | `quick_probe()` diag mode |
| Verifying a single step's side-effect (e.g. cancel vs done) | `quick_probe()` diag mode |
| Reproducing a specific wizard-state hypothesis live | `quick_probe()` diag mode |

**Import and basic usage:**

```python
from tests.wizard_probe import HubitatMcpClient, load_config, quick_probe

config = load_config()
client = HubitatMcpClient(
    config["hub_url"], config["app_id"], config["access_token"], verbose=True
)
client.initialize()

result = quick_probe(client, "my_hypothesis", steps=[
    {"addRequiredExpression": {"conditions": [
        {"capability": "Switch", "deviceIds": [1063], "state": "on"}
    ]}},
    {"addAction": {"capability": "Switch", "deviceIds": [1063], "command": "on"}},
])

print(result["final"]["render"])    # joined paragraph text from mainPage
print(result["final"]["broken"])    # True if "Broken Condition" present
print(result["errors"])             # list of step errors; [] = all steps succeeded
```

**Return shape:**

| Field | Type | Description |
|---|---|---|
| `app_id` | `int \| None` | Created rule's appId (None on create failure) |
| `snapshots` | `list[dict]` | Per-step snapshots (`{step_index, op, paragraphs, settings, error}`) |
| `final` | `dict` | Convenience: `{render, broken, paragraphs, settings, error}` |
| `errors` | `list[str]` | Step-level errors (empty = all OK) |
| `status` | `str` | `"pass"` (no step errors), `"fail"` (step errors), or `"error"` (exception) |
| `duration_s` | `float` | Wall-clock seconds |

Always cleans up the test rule via `try/finally` -- a crashed diag script will not leave stale `_PROBE_*` rules. Use `--cleanup` flag as a backstop if a process-kill interrupts before the finally block.

**Escape-hatch step types (diag mode only):**

The matrix YAML step ops cover the high-level `hub_set_rule` arguments. For low-level investigation you sometimes need to fire a raw button click or raw settings write on a specific page mid-sequence. Two escape hatches exist:

`raw_button` -- direct button click on a named page:

```python
{"raw_button": {"page": "doActPage", "name": "actionCancel"}}
# Optional: state_attribute for stateAttribute body field
{"raw_button": {"page": "selectActions", "name": "N", "state_attribute": "doActN"}}
```

`raw_setting` -- direct settings write on a named page:

```python
{"raw_setting": {"page": "doActPage",
                 "settings": {"actType.1": "condActs", "actSubType.1": "getIfThen"}}}
```

These map to `walkStep` calls (the MCP tool's single-step wizard walker):
- `raw_button` uses `operation: "click"` with the named button
- `raw_setting` uses `operation: "write"` once per key (walkStep.write allows exactly one key per call)

Use sparingly -- direct page manipulation can leave the hub app in states that the
high-level tools don't expect.

**Demo script:**

`tests/wizard_probe_examples/diag_demo.py` is a worked example that reproduces the
Variant Y finding from the Issue #77 investigation (actionCancel vs actionDone after
condActs+getIfThen write). Run it against the live hub to verify the ghost ifThen
sequence behaves as documented:

```bash
cd Hubitat-local-MCP-server
uv run --python 3.12 --with pyyaml tests/wizard_probe_examples/diag_demo.py
# Expected: broken: False, errors: [], status: pass
```

Set `DEVICE_ID=<id>` to override the default switch device ID (1063).
- The warning paragraph about "Do not use back button" is also normal and does not indicate a bug.
