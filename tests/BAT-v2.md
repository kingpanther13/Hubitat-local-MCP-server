# Bot Acceptance Test (BAT) Suite — v2

## Device configuration inspection and restoration

**Read-only prompt:** "Show the logging preferences and their current saved values for this device, identify its actual driver, and explain which preference controls descriptive logging. Show the available settings and the other device information I can inspect. Distinguish saved values from defaults, and leave everything unchanged."

**Expected:** The ordinary device read remains concise. Expanded inspection discovers all applicable preference names, types, options and defaults, current values (including false and zero), editable configuration and non-editable metadata. Missing or unreadable information is identified explicitly. Driver source is discoverable when available; a source-code default is never presented as a saved value.

**Dedicated test-hub prompt:** "Create a disposable test device, inspect its preferences and configuration, and verify boolean, numeric, text and enum changes using an independent native read. Restore each original value and verify restoration, then remove only the device and driver you created. Leave every existing device and automation unchanged."

Updated for the installed-apps + Rule Machine interop + native CRUD + library management + HPM package state architecture, then the issue #105 PR1A hub_ rename + consolidation, then the PR1B read/write split, then the issue #259 item #9 Easy Dashboard CRUD (13 flat core + 23 gateways = 36 on tools/list, 115 total distinct tools).

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

**Runner contract:** The COMPLETE message to the agent under test is exactly `On my Hubitat, using the mcp connector: <test_prompt>` — no additional framing, no MCP server or tool names, no tool-loading hints, no report-format instructions. `setup_prompt`/`teardown_prompt` are orchestrator-only; **Expected** blocks are grading reference only and are never shown to the agent.

### Prompt style — goal-first

BAT tests are run by an orchestrating/grading agent: it reads the entry, sets it up, feeds the `test_prompt` to a **sub-agent under test**, then grades whether that sub-agent found and used the right tool. The whole point is *discovery*, so:

- The `test_prompt` states the scenario + goal in plain language and does **not** name any MCP tool, gateway, or call arg. Tool names belong in the test title, **Expected**, and failure-mode text — the grading scaffolding — never in the prompt fed to the sub-agent.
- `setup_prompt` / `teardown_prompt` are orchestrator infrastructure, not under test — they MAY name tools directly for reliability.
- Per-test edge case: a test whose *subject is* the MCP surface itself — wrong-gateway routing, a tool name that is off `tools/list`, deliberately mutually-exclusive or malformed call shapes under regression — may still name that surface in its `test_prompt`, because the scenario cannot be expressed without it.

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

**Expected**: Calls `hub_list_devices` with `format='detailed', limit=10`.

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

### T02c — hub_list_devices (context snapshot, issue #366)

```json
{
  "test_prompt": "Give me a quick overview of what's going on in my house right now — the current mode and the state of my devices, in one compact view."
}
```

**Expected**: Calls `hub_list_devices` with `format='context'` (one call). Answer is built from the returned `summary` text (mode header + per-device lines) rather than iterating `detailed=true` pages or per-device reads.

```json
{
  "test_prompt": "Which of my devices are switched on right now?"
}
```

**Expected**: Calls `hub_list_devices` with `stateFilter={"switch":"on"}` (optionally `format='context'`). Does NOT fetch the whole inventory and filter client-side.

```json
{
  "setup_prompt": "Note the current time as an ISO-8601 timestamp.",
  "test_prompt": "Which devices have reported activity in the last two hours?"
}
```

**Expected**: Calls `hub_list_devices` with `changedSince=<timestamp>` (epoch ms or ISO-8601). Does NOT page through events or the full inventory.

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

**Expected**: Calls `hub_call_device_command` with `command=on`, then `hub_get_device_attribute` to confirm. The command response includes a `state` map keyed by attribute name (each entry its current value + a freshness timestamp; the device's attributes, no per-attribute auth filter). The snapshot is read after the command fires, so a virtual switch, which reports at once, already shows `switch=on`; a real device may still show its prior value, which is why the separate `hub_get_device_attribute` read is what confirms `switch=on`. The per-attribute timestamp is the freshness signal.

### T05b — hub_call_device_command with waitFor (confirmed resulting state)

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT WaitFor Test'.",
  "test_prompt": "Turn on the virtual switch 'BAT WaitFor Test', waiting until its switch attribute reads on, in a single call.",
  "teardown_prompt": "Turn off 'BAT WaitFor Test', then delete the virtual device."
}
```

**Expected**: Calls `hub_call_device_command` with `command=on` and a `waitFor={attribute:"switch", expectedValue:"on"}`. The response carries a `waitFor` block with `converged: true` and `finalValue: "on"` (a String, read from the device's live current-state list), and -- because the snapshot is taken AFTER the waitFor poll -- the `state` map's switch entry now reads `on` (the converged/resulting value, not the pre-effect one). On a clean run there is NO `partial` flag and NO `stateError`; a non-convergence would instead surface a diagnostic flag (`timedOut` / `interrupted` / `neverReported` / `error`), and a degraded confirmation step (failed snapshot or a poll-loop `error`) would add `partial: true`. The `waitFor` `timeoutMs` caps at 30000 (vs 60000 on the standalone `hub_get_device_attribute` poll) and `pollIntervalMs` defaults to 250. No separate `hub_get_device_attribute` read is needed to confirm.

### T05c — hub_call_device_command with `commands` (several devices in one call)

```json
{
  "setup_prompt": "Create three virtual switches called 'BAT Batch A', 'BAT Batch B', and 'BAT Batch C'.",
  "test_prompt": "Turn on all three BAT Batch switches, then confirm all three are on.",
  "teardown_prompt": "Turn off all three BAT Batch switches, then delete the three virtual devices."
}
```

**Expected**: Calls `hub_call_device_command` ONCE with `commands=[{deviceId, command:"on"} x3]` — not three separate calls. The response is `success: true, count: 3, sentCount: 3, failedCount: 0` (the counts are always present, `failedCount` included on a clean pass) plus a `results` array in request order, each entry carrying its own `deviceId`, the device label, the command and its normalized `parameters`. Batch entries carry NO `state` snapshot — the batch fires, it does not read back — and `commands` rejects `waitFor`, so the confirmation is a separate `hub_get_device_attribute` using the multi-device `deviceIds` form with `mode:"all"` — two round trips for the whole group. Watch for the AI falling back to three separate command calls: that is the behaviour the parameter exists to replace, and it costs roughly 3x the wall-clock time.

### T05d — `commands` partial failure (one bad entry)

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT Batch Partial'.",
  "test_prompt": "Turn on 'BAT Batch Partial' and device 99999 in one call.",
  "teardown_prompt": "Turn off 'BAT Batch Partial', then delete the virtual device."
}
```

**Expected**: The batch still fires the valid entry — one bad device does not abandon the rest. The response is `success: false, count: 2, sentCount: 1, failedCount: 1`, plus `failedDeviceIds: ["99999"]`, an `error` summarising the failure, an actionable `note`, and `partial: true` (only some entries failed). It is a NORMAL tool result, not an `isError` envelope — `isError` is reserved for the batch where every entry failed. `results[0].success` is `true` and `results[1]` carries `success: false`, its `deviceId`, and an `error` naming the missing device. The AI reports the specific device that failed rather than reporting the whole command as failed. (Contrast with a MALFORMED batch — an entry missing `deviceId`, a `parameters` value that is neither an array nor a string, or more than 20 entries — which is rejected with `-32602` BEFORE anything is sent, so no device is actuated.)

### T06 — hub_call_device_command (setLevel)

```json
{
  "setup_prompt": "Create a virtual dimmer called 'BAT Dimmer Test'.",
  "test_prompt": "Set 'BAT Dimmer Test' to 75% brightness. Confirm the level.",
  "teardown_prompt": "Turn off 'BAT Dimmer Test', then delete the virtual device."
}
```

**Expected**: Calls `hub_call_device_command` with `command=setLevel, args=[75]`. Verifies with `hub_get_device_attribute`. (A fresh Virtual Dimmer already sits at level 50, so 50% would prove nothing.)

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
  "test_prompt": "Poll the switch attribute of 'BAT Poll OR Test' waiting for it to be in EITHER the 'on' or 'off' state, with a 2-second timeout. Report whether the poll succeeded.",
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
  "test_prompt": "Poll the switch attribute of 'BAT NE Test' waiting for it to read anything OTHER than 'off', with a 3-second timeout. Report whether it succeeded and the final value.",
  "teardown_prompt": "Delete the virtual device 'BAT NE Test'."
}
```

**Expected**: Calls `hub_get_device_attribute` (poll mode) with `attribute=switch, comparator="ne", expectedValue="off", timeoutMs=3000`. The switch reads `on` (not in the set), so `ne` converges immediately: `success: true, timedOut: false` with `finalValue` of `on`. (A null/never-reported value does NOT satisfy `ne`.)

### T07i — hub_get_device_attribute multi-device convergence (deviceIds + mode any/all)

```json
{
  "setup_prompt": "Create two virtual switches 'BAT Multi A' and 'BAT Multi B' and turn both on.",
  "test_prompt": "In a single call, wait until BOTH 'BAT Multi A' and 'BAT Multi B' have switch 'on', with a 5-second timeout. Then turn 'BAT Multi B' off and wait until EITHER one of the two is 'on'. Report how many devices converged for each wait.",
  "teardown_prompt": "Delete the virtual devices 'BAT Multi A' and 'BAT Multi B'."
}
```

**Expected**: Calls `hub_get_device_attribute` (poll mode) with `deviceIds=[idA, idB], attribute=switch, expectedValue="on", mode="all", timeoutMs=5000` -- both on, so it converges with `success: true, convergedCount: 2` and a compact per-device `devices` array (each `{deviceId, device, finalValue, matched}`), not full device objects. After B is turned off, the `mode="any"` call still converges on A: `success: true, convergedCount: 1` (an `all` call here would have timed out). `deviceIds` is mutually exclusive with `deviceId` (max 20 devices); `mode` is rejected with a single `deviceId`; a missing ID in the list is rejected naming which ID.

### T07f — hub_call_device_command waitFor with debounce (stableForMs)

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT Debounce Test' and turn it off.",
  "test_prompt": "Turn on 'BAT Debounce Test', and wait for the switch attribute to read 'on' and stay 'on' continuously for at least 500 milliseconds before confirming, allowing up to 5 seconds overall. Report whether it converged.",
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

**Expected**: Calls `hub_update_device` with `fields.label`. Verifies with `hub_get_device`.

### T13b — hub_update_device (hide from Home page)

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT ShowOnHome Test'.",
  "test_prompt": "Hide 'BAT ShowOnHome Test' from the hub Home page.",
  "teardown_prompt": "Delete the virtual device 'BAT ShowOnHome Test'."
}
```

**Expected**: Calls `hub_update_device` with `fields.showOnHome=false`. Reports the device no longer shows on the Home page.

### T13c — hub_update_device (default status attribute)

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT DefaultState Test'.",
  "test_prompt": "Make 'switch' the attribute shown in the Status column for 'BAT DefaultState Test'.",
  "teardown_prompt": "Delete the virtual device 'BAT DefaultState Test'."
}
```

**Expected**: Calls `hub_update_device` with `fields.defaultCurrentState="switch"`. Reports the status attribute was set.

### T13d — hub_update_device (tags)

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT Tags Test'.",
  "test_prompt": "Tag 'BAT Tags Test' with 'kitchen' and 'downstairs'.",
  "teardown_prompt": "Delete the virtual device 'BAT Tags Test'."
}
```

**Expected**: Calls `hub_update_device` with `fields.tags=["kitchen","downstairs"]`. The Device Info save keeps the device's label/name; the response confirms the tags were applied (read back from the device).

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

### T14f — turn on the hub's dark mode

```json
{
  "test_prompt": "Switch my hub's admin UI to dark mode."
}
```

**Expected**: Calls `hub_set_system_settings` with `darkMode: true` (an independent setter — `GET /hub/applyDarkMode/true`; no location field is touched, so no `/location/update` POST is made). Reports success and that `darkMode` was applied. Does NOT require `confirm`. There is no read-back of the theme, so it can't be confirmed via `hub_get_info`; verify visually in the hub admin UI. On firmware that lacks the endpoint the call returns `success:false` with a "Failed to apply dark mode" error and nothing else changes.

### T14g — configure the hub network (⚠️ MANUAL ONLY — can disconnect the hub)

> **DO NOT run on the e2e/CI test hub.** A network change can sever the hub's own connectivity (and the cloud MCP endpoint the tests ride). Exercise only on a hub you can physically reach to recover.

```json
{
  "test_prompt": "Give my hub a static IP of 192.168.1.50, netmask 255.255.255.0, gateway 192.168.1.1."
}
```

**Expected**: Recognizes a network change can disconnect the hub, so it warns the user, ensures a recent backup, and calls `hub_set_system_settings` with `network: {ipMode:"static", address:"192.168.1.50", netmask:"255.255.255.0", gateway:"192.168.1.1"}` and `confirm: true`. Without `confirm` the call is rejected by the destructive gate and nothing changes. On success `applied` includes `network.staticIp`. Other valid network shapes (each its own leg, applied in order, non-atomic): `{ipMode:"dhcp", useDNSFallover:true}` → `network.dhcp`; `{ethernetAutoneg:false}` → `network.ethernetAutoneg`; `{wifiSsid:"Net", wifiPassword:"…"}` → `network.wifi`. A static IP without address/netmask/gateway is rejected with `-32602` before any hub call.

### T14h — destructive network/cloud ops (⚠️ MANUAL ONLY — disconnects / disables cloud)

> **DO NOT run on the e2e/CI test hub.** `target=network` disconnects a link; `target=cloud` `disable` severs Alexa/Google, cloud dashboards, cloud firmware updates, and subscription features (and the cloud MCP endpoint). Exercise only on a hub you can recover by hand.

```json
{
  "test_prompt": "Disable my hub's cloud controller."
}
```

**Expected**: Warns that disabling the cloud controller stops Alexa/Google, cloud dashboards, cloud firmware updates, and subscription features, ensures a recent backup, then calls `hub_call_destructive_ops` with `target: "cloud"`, `action: "disable"`, `confirm: true`. Re-enable with `target:"cloud", action:"enable"`. The network variants are `target:"network", action:"disconnect_wifi"` (→ `GET /hub/advanced/disconnectWiFi`) and `action:"disconnect_ethernet"` (→ `/hub/advanced/disconnectEthernet`). All require `confirm: true`; without it the destructive gate rejects the call and nothing changes.

### T14i — read the Hub Mesh configuration

```json
{
  "test_prompt": "Is Hub Mesh turned on? Which other Hubitat hubs does this one see, and what devices are shared between them? Don't change anything."
}
```

**Expected**: Recognizes Hub Mesh as Hubitat's hub-to-hub device/variable sharing over the LAN — NOT the Z-Wave/Zigbee radio mesh (that would be `hub_get_radio_details`) — and calls `hub_get_hub_mesh`. Reports `hubMeshEnabled`, the auto-discovered `peers` (name / ipAddress / hubId, plus any `warning`), the full-sync interval, whether the hub follows a peer's modes (`modeHubId`, `"none"` = local modes), and the shared/linked device + hub-variable lists. `privateDeviceCount` / `localHubVariableCount` are counts only — for the full lists it should point at `hub_list_devices` / `hub_list_variables`. `hubMeshToken` is NOT returned unless `include_token: true` was asked for (it is a credential). Read-only. On firmware without Hub Mesh the call returns `success:false` with an `error` + `note` rather than throwing.

### T14j — change Hub Mesh settings (⚠️ enable/disable is reboot-gated and hub-wide)

> **DO NOT run the enable/disable or follow-modes variants on the e2e/CI test hub.** Turning Hub Mesh on or off only takes effect after a hub reboot, and follow-modes / peer tokens are hub-wide settings other tests inherit.

```json
{
  "test_prompt": "Set Hub Mesh to do a full sync every 5 minutes."
}
```

**Expected**: Calls `hub_update_hub_mesh` with `full_refresh_interval: 300` and reports `applied: ["full_refresh_interval"]`. Does NOT require `confirm` (the change is reversible). Other legs, each optional and applied in a stable order: `enabled: true|false` (⚠️ reports that a hub REBOOT via `hub_reboot` is required before it takes effect — the tool never reboots on its own), `mode_hub_id` (a peer `hubId` from `hub_get_hub_mesh` `peers[]`, or `"none"` to go back to local modes), and `peer_hub_id` + `peer_token` TOGETHER (stores a peer hub's mesh token, needed when that peer has UI login security). Rejected by validation (an `isError` result) before any hub call: no settable field at all, a `full_refresh_interval` outside `0|120|300|3600`, a non-boolean `enabled`, and `peer_hub_id` without `peer_token` (or vice versa). Peer hubs are auto-discovered on the LAN so there is no add-peer operation, and per-DEVICE sharing is `hub_update_device` (`meshEnabled` / `meshFullSync`), not this tool. A dropped/empty response, a non-JSON body, and an explicit `success:false` from the peer-token store are each fail-closed with a case-specific note (unconfirmed vs non-JSON vs rejected).

### T14k — share a hub variable over Hub Mesh (hub_set_variable mesh_shared)

> **DO NOT run on a hub whose mesh peers matter** — sharing/unsharing a variable briefly changes what peers see. Use a throwaway variable and unshare it afterwards.

```json
{
  "test_prompt": "Share the hub variable 'vacationMode' with my other hubs over Hub Mesh, then stop sharing it."
}
```

**Expected**: Calls `hub_set_variable` with `name: "vacationMode"` and `mesh_shared: true` (no `value` needed — mesh_shared may stand alone), reports `meshShared: true` and, when the read-back is readable, `meshShareConfirmed: true` (the variable now appears in `hub_get_hub_mesh` `sharedHubVariables`). Then `mesh_shared: false` to stop. `mesh_shared` applies to HUB variables only — a rule-only name is rejected by validation before any hub call (`isError`), as is a call with neither `value` nor `mesh_shared`. Sharing/unsharing is reversible (no `confirm`). Distinct from per-DEVICE sharing (`hub_update_device` `meshEnabled`).

### T14l — link a device or variable a peer hub shares (hub_create_device / hub_create_variable mesh link)

> **Conditional** — only runnable when a peer hub is actually sharing something (`hub_get_hub_mesh` `availableLinkedDevices[]` / `availableLinkedHubVariables[]` non-empty). Otherwise verify the validation contract only.

```json
{
  "test_prompt": "Link the device 'Garage Door' that my other hub is sharing over Hub Mesh onto this hub."
}
```

**Expected**: Reads `hub_get_hub_mesh` to find the peer's `hubId` + `deviceId` in `availableLinkedDevices[]`, then calls `hub_create_device` with `mesh_source_hub_id` + `mesh_source_device_id` + `confirm: true` (NOT `deviceTypeId`). Returns the new local `deviceId` (resolved by a `localLinkedDevices` diff; a read-back lag returns a `warning`, not a failure). The variable analogue is `hub_create_variable` with `mesh_source_hub_id` + `mesh_source_name`. Validation (an `isError` result before any hub call): passing both `deviceTypeId` and the mesh pair, or only one half of the pair, is rejected ("not both" / "BOTH mesh_source_..."). If the peer has UI login security, its mesh token must be stored first via `hub_update_hub_mesh(peer_hub_id, peer_token)` or the link no-ops. Unlink a linked device (local proxy only; peer source untouched) with `hub_delete_device`.

### T14m — unlink a Hub Mesh linked variable (hub_delete_variable, mesh-aware)

> **Conditional** — only runnable when this hub actually has a linked mirror (`hub_get_hub_mesh` `localLinkedHubVariables[]` non-empty). Creating one needs a live peer sharing a variable, so on a hub without a peer just verify the ordinary-variable delete path is unchanged (T224/T225/T226 already cover the non-mesh `hub_delete_variable` path). Never assume the CI/e2e hub has a peer.

```json
{
  "test_prompt": "Stop linking the variable 'porchTemp on GarageHub' that my other hub shares — remove it from this hub."
}
```

**Expected**: Recognizes the DECORATED local name (`"<sourceVar> on <peerName>"`) as a Hub Mesh linked mirror and calls `hub_delete_variable` with `name` + `confirm: true`. When nothing real on this hub uses the mirror (`localLinkedHubVariables[]` row `inUseByApps:false`) it unlinks WITHOUT `force` — even though the hub's generic in-use registry marks the mirror in use (that registration is the mesh link itself, not a consumer) — and returns `{success:true, deleted:true, unlinked:true, source:"hub"}` with a `note` saying only this hub's local copy was removed and the source variable on the peer is untouched. When a real local app uses the mirror (`inUseByApps:true`) the unforced call is refused (`isError`) with a message naming BOTH that it is a linked mirror (peer untouched) AND that real apps will break; `force: true` unlinks anyway. An ordinary (non-mirror) in-use hub var is unchanged — still refused without `force` on the generic registry guard. Teardown ORDER: unlink on the linking hub(s) first, THEN unshare on the owner (`hub_set_variable(mesh_shared=false)`) — unsharing first strands the mirrors, and the unshare result carries a caution saying so. Confirm + a recent backup are still required (`requireDestructiveConfirm`).

### T14n — read the hub network configuration (hub_get_info includeNetwork)

```json
{
  "test_prompt": "Is my hub on DHCP or a static IP, what DNS servers is it using, and — if a static IP is saved — what are its saved IP, gateway, and subnet?"
}
```

**Expected**: Calls `hub_get_info` with `includeNetwork: true` and reads the `network` block. Reports `ipMode` (`dhcp`/`static`, or `unknown` when the hub does not report it), `activeDnsServers`, and the SAVED static config `staticIp`/`staticGateway`/`staticSubnetMask` (reported whether or not static is the active mode; null on a DHCP-only hub — there is no current-gateway field on the endpoint), Ethernet autoneg, and the joined `wifiSsid`. The current LAN address is NOT in the block — it is the top-level `localIP` field. Read-only — no `confirm`; `includeNetwork` is opt-in, so a plain `hub_get_info` omits the block. The Wi-Fi PASSWORD is never returned (SSID only). On firmware without the endpoint (or an unreadable/empty/non-JSON body) the `network` block is `success:false` with an `error` + `note`, not a throw. The write counterpart is `hub_set_system_settings(network:...)`.

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
  "test_prompt": "Pull up the section of the server's built-in guide that covers its device authorization rules — I want the actual guide content, not a from-memory summary."
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

**Expected**: Calls `hub_list_devices` with `scope='owned'`. Lists MCP-managed virtual devices.

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
  "test_prompt": "Create a virtual device using the first available custom driver (use its namespace and name), label it 'BAT Custom Driver Success Test', and confirm the creation. Then delete it.",
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

**Expected** (conditional): Calls `hub_create_device` with the chosen `deviceTypeId`, `label="BAT Create From Driver"`, and `confirm=true`. Returns the new `deviceId`. If the picked driver is radio-type, the response carries a radio orphan-shell `warning`. Without `confirm` the call is rejected (`-32602`). `hub_create_device` also LINKS a Hub Mesh device via `mesh_source_hub_id` + `mesh_source_device_id` instead of `deviceTypeId` (see T14l); passing both, or only one half of the pair, is rejected by validation.

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

**Expected v0.8.0**: Calls `hub_create_backup` directly with `confirm=true` (promoted to core in v0.8.0). This immediate-backup request is distinct from `scheduleOnly=true` with a schedule, which creates no backup and needs no confirm.

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

**Expected**: Discovers the top-level `hub_update_package` and calls it with `dryRun=true` (no `confirm` needed). Reports `success=true`, `dryRun=true`, no planned bundles (the package ships none since 4.7: the parent is installed with its libraries inlined) and planned apps (parent + child, the parent flagged as the self app, deployed last). No bundle or app write occurs.

**Last live run**: 2026-10-10, C-8 2.5.2.129 running the build of 5d56054c, `dryRun=true` at that ref: pass -- `plannedBundles: []`, both apps planned from `bundle-artifacts/shas/<sha>/` with their byte counts, `MCP Rule Server` last with `isSelf: true`, no writes.

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
  "test_prompt": "Pull the last hour of hub logs and show me only entries where the message text mentions 'error' or 'failed' -- case-insensitive, matching on the message text itself, not just filtering by log level."
}
```

**Expected**: Calls `hub_manage_logs` -> `hub_get_logs` with `since='1h'` and `pattern='error|failed'` (or equivalent `patterns` array). Demonstrates pattern filter + time-window together.


### T47d — hub_get_logs scheduled jobs and device stats on a large hub (budget-safe, paged)

```json
{
  "test_prompt": "List the hub's scheduled jobs a page at a time, then tell me the three busiest devices. Do this over the cloud connector."
}
```

**Expected**: Discovers `hub_manage_logs` (or `hub_read_diagnostics`) → `hub_get_logs(mode='scheduledJobs', cursor='')` and follows `nextCursor`; each job carries its `owner`. Then `hub_get_logs(mode='devices')` with a small `limit`. On a large hub a modern client continues the first call automatically through `requestState`; a legacy client receives `status: "in_progress"` and repeats the identical call. Either way the agent ends with data, never a 502. When the second read lands within the 30 s cache TTL it is served from the same snapshot and is fast. Read-only — makes no changes.

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

### T51 — Discover hub_get_logs(mode='mcp') (hub_manage_logs)

```json
{
  "test_prompt": "Show me the MCP debug logs."
}
```

**Expected v0.8.0**: Discovers `hub_manage_logs` → `hub_get_logs(mode='mcp')`.

### T52 — Discover hub_report_issue (core)

```json
{
  "setup_prompt": "Call hub_get_logs with mode BAT_retained_error_invalid_mode and confirm it returns a validation error. Do not clear logs.",
  "test_prompt": "Generate a bug report with diagnostics that I can submit to GitHub, then generate the same report again in public privacy mode."
}
```

**Precondition**: the advanced setting "Keep recent errors for bug reports" (`retainReportErrors`) is ON; it is off by default and e2e does not exercise it.

**Expected**: Calls `hub_report_issue` directly (flat core tool) twice. Without a supplied failingTool, both reports title themselves with `hub_get_logs` from the newest retained error (`failingToolSource: "retained_error"`), keep the logs unscoped, and report a positive `logs.retainedErrorCount`. The private report's Retained Server Errors section precedes native history and contains the invalid-mode marker; the public report withholds that raw error text. No existing device or rule is changed.

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

**Expected v0.8.0**: Discovers `hub_manage_logs` → `hub_set_log_level` and → `hub_get_logs` (mode='status').

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

### T59 — Discover hub_delete_logs (hub_manage_logs)

```json
{
  "test_prompt": "Clear all the MCP debug logs. Then confirm they're empty by checking them."
}
```

**Expected**: Discovers `hub_manage_logs` → `hub_delete_logs(mode='mcp')`, then → `hub_get_logs(mode='mcp')`.

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

**Expected**: Routes to `hub_get_logs` (system logs), not `hub_get_logs(mode='mcp')` (MCP-specific).

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

**Expected (v0.8.0)**: All core tools: `hub_manage_virtual_device(action="create")`, `hub_list_devices(scope='owned')`, `hub_call_device_command`, `hub_get_device_attribute`, `hub_manage_virtual_device(action="delete")`.

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

**Expected**: These reads are available from `hub_read_diagnostics`: `hub_get_metrics`, `hub_get_device_health`, and `hub_get_logs` in past and MCP modes. A read-only client gets the whole report from the pure-read gateway; the write-bearing `hub_manage_diagnostics` / `hub_manage_logs` also expose them.

### T84 — Cross-gateway workflow

```json
{
  "test_prompt": "Gather a comprehensive audit:\n1. List my rooms\n2. List virtual devices\n3. List hub variables\n4. List files in file manager\n5. List item backups\n6. Hub health status\n7. Device health check\nGive me a one-line summary for each."
}
```

**Expected (v0.8.0)**: Uses gateways plus core: `hub_manage_rooms` (hub_list_rooms), `hub_read_devices` (`hub_list_devices(scope='owned')`), `hub_manage_variables` (hub_list_variables), `hub_manage_files` (hub_list_files), `hub_read_apps_code` (hub_list_backups), core (`hub_get_info`), `hub_manage_diagnostics` (hub_get_device_health).

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

### T109 — Add a switch action and confirm it landed exactly once

Tests that an agent verifies a rule write from the rule itself and never repeats a write that already committed.

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT Partial Test Switch'. Note its device ID.",
  "test_prompt": "Create a new RM rule called 'BAT Finding4 Rule' and add a 'Switch: on' action targeting 'BAT Partial Test Switch' to it. Confirm from the rule itself that the action is there exactly once, and report whether it was added.",
  "teardown_prompt": "Delete the rule 'BAT Finding4 Rule'. Delete the virtual switch 'BAT Partial Test Switch'."
}
```

**Expected**:
- Agent creates the rule with `hub_manage_rule_machine(tool=hub_set_rule, args={operation: "create", args: {parent_id: <Rule Machine app id>, name: "BAT Finding4 Rule", changes: [<switch-on action>]}, confirm: true})`, or creates it and adds the action with one `operation: "update"` call.
- The write reports `success: true`, and its returned document lists one switch action.
- Agent verifies with `hub_set_rule(operation: "read", rule_id, args: {sections: ["actions"]})` or `hub_read_apps_code(tool=hub_get_app_config, args={appId: ..., includeSettings: true})`, which shows one "On: BAT Partial Test Switch" action.
- Agent reports the action was added.

**What an agent must NOT do**:
- Repeat a write that reported success (produces a duplicate action row).
- Delete a committed action to "undo" a result it misread.
- Report failure when the write succeeded and the rule shows the action.
- Resubmit after a reported failure without first reading the rule to see what landed.

**Acceptance criterion**: Agent reports success, the rule's mainPage render shows the action (e.g. "On: BAT Partial Test Switch"), and no duplicate action rows exist.

### T110 — hub_get_app_config does not expose password values

```json
{
  "setup_prompt": "Identify an installed app that has a password-type input configured (e.g. an integration storing an API key or account password).",
  "test_prompt": "Show me the full configuration, including settings, for that app. Tell me the value of its password / API-key field."
}
```

**Expected**: Agent calls `hub_get_app_config` (via `hub_read_apps_code`) with `includeSettings=true`. With Hide passwords and credentials on (the default), password-type fields come back **redacted** (`***redacted (password)***`), never cleartext, on BOTH the structured `inputs[].value` and the raw `settings` map. The agent reports it cannot read the stored secret.

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

These tests cover the same tool capabilities as earlier sections, but use **purely conversational prompts**. No tool names, no parameter names, no implementation-specific terms. The LLM must figure out which tool(s) to use from context alone. (Sections 1-9 are also goal-first, but they state goals in precise domain vocabulary; this section restates the same coverage the way a non-technical user would phrase it.)

**Purpose**: Measure whether the LLM can map real user intent to the correct MCP tools without being told which tools exist. Compare results with the same tools' technically-phrased tests in earlier sections.

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

**Expected**: `hub_list_devices` (summary format). Returns count and names.
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
  "test_prompt": "'BAT NL Dimmer' is too bright. Bring it down to about a quarter and confirm it changed.",
  "teardown_prompt": "Turn off 'BAT NL Dimmer', then delete the virtual device."
}
```

**Expected**: `hub_call_device_command` with setLevel ~25, then verify. (A fresh Virtual Dimmer starts at level 50, so "about half" would be a no-op.)
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

**Expected**: `hub_list_devices` with `format='detailed'`, paginated.
**Equivalent to**: T02

---

### Automations & Rules

#### T210 — What automations do I have?

```json
{
  "test_prompt": "What automations do I have set up? Are they all running or are some paused?"
}
```

**Expected**: `hub_list_rules` — each entry carries a `status` (`active` | `paused` | `disabled`), so the AI answers the running/paused split directly from the one call (plus `hub_get_custom_rule` list mode / `hub_get_visual_rule` list mode if the user's automations span engines). FAIL if the AI claims rule status is not exposed (issue #359 regression).
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

**Expected**: `hub_list_devices` with `scope='owned'`.
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

**Expected**: `hub_get_logs(mode='mcp')`.
**Equivalent to**: T51

#### T280 — Clean up diagnostic logs

```json
{
  "test_prompt": "The diagnostic logs are cluttered from all my testing. Wipe them clean and then confirm they're empty.",
  "teardown_prompt": "Set the MCP log level back to 'info' if it was changed."
}
```

**Expected**: `hub_delete_logs(mode='mcp')`, then `hub_get_logs(mode='mcp')` to verify.
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

**Expected**: `hub_set_log_level` (to 'warn' or 'error'), then `hub_get_logs` (mode='status').
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

**Expected**: `hub_manage_virtual_device(action="create")` → `hub_list_devices(scope='owned')` (or `hub_get_device`) → `hub_call_device_command` → `hub_get_device_attribute` → `hub_manage_virtual_device(action="delete")`.
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

**Expected**: `hub_get_metrics` + `hub_get_device_health` + `hub_get_logs` + `hub_get_logs(mode='mcp')`.
**Equivalent to**: T83

#### T299 — Complete smart home inventory

```json
{
  "test_prompt": "I want a complete inventory of my entire smart home setup. Tell me about my rooms, any simulated devices, what custom values are stored, what files are on the hub, any code backups, the hub's overall health, and whether any real devices have gone offline. Give me a one-line summary for each area."
}
```

**Expected**: `hub_list_rooms` + `hub_list_devices(scope='owned')` + `hub_list_variables` + `hub_list_files` + `hub_list_backups` + `hub_get_info` + `hub_get_device_health`.
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

#### T302 — Pause a whole set of automations at once

```json
{
  "setup_prompt": "Create three test rules called 'BAT NL Cutover A', 'BAT NL Cutover B', and 'BAT NL Cutover C', each with a time trigger at 23:58 and a log action. Mark them as test rules.",
  "test_prompt": "I'm about to switch over some automations and don't want the old set firing mid-change. Put 'BAT NL Cutover A', 'BAT NL Cutover B', and 'BAT NL Cutover C' on hold — all together, as one step if you can — then confirm none of them is active. When that checks out, bring all three back at once and confirm they're running again.",
  "teardown_prompt": "Delete the rules 'BAT NL Cutover A', 'BAT NL Cutover B', and 'BAT NL Cutover C'."
}
```

**Expected**: `hub_set_rule_paused` with `ruleId=[A,B,C]` (one call, array form) + `paused=true`, verify via `hub_list_rules` status, then one array-form resume + verify. FAIL only if the AI claims batch pause is impossible; three sequential single-rule calls is a soft pass (the array form is the intended one-step path).

#### T303 — Point a caller automation at a replacement rule

```json
{
  "setup_prompt": "Create two empty test rules called 'BAT NL Old Target' and 'BAT NL New Target', then a third test rule called 'BAT NL Caller' whose only action runs the actions of 'BAT NL Old Target'. Mark all three as test rules.",
  "test_prompt": "'BAT NL Caller' currently hands off to 'BAT NL Old Target'. I've rebuilt that logic in 'BAT NL New Target' — switch the hand-off over so 'BAT NL Caller' runs 'BAT NL New Target' instead, without touching anything else about the caller. Show me the caller's config afterward so I can see the hand-off now points at the new rule.",
  "teardown_prompt": "Delete the rules 'BAT NL Caller', 'BAT NL Old Target', and 'BAT NL New Target'."
}
```

**Expected**: One `hub_set_rule(operation="update")` change edits the caller's existing Run Rule Actions action in place (its native action ID, with the new target in its fields), then `hub_set_rule(operation="read")` or `hub_get_app_config` shows the Run Actions reference on the new target. Deleting the action and adding a new one in the same edit is a soft pass; FAIL if the caller is rebuilt from scratch or the old target is modified/deleted.

#### T304 — Duplicate an automation without letting the copy go live

```json
{
  "setup_prompt": "Create a test rule called 'BAT NL Stage Source' with a time trigger at 23:57 and a log action, and leave it running. Mark as test rule.",
  "test_prompt": "I want a working copy of 'BAT NL Stage Source' to experiment on, but under no circumstances may the copy actually run — it must come into existence already switched off, and 'BAT NL Stage Source' itself must keep running untouched. Make the copy, then show me proof that the copy is off and the original is still active.",
  "teardown_prompt": "Delete the copy of 'BAT NL Stage Source', then delete 'BAT NL Stage Source'."
}
```

**Expected**: `hub_clone_native_app` with `stageDisabled=true` (one call — the clone lands disabled), then `hub_list_rules` showing the copy `status: "disabled"` and the source `active`. Clone-then-separately-disable is a soft pass ONLY if the AI acknowledges the copy was briefly live; FAIL if the copy is left enabled or the source is paused/disabled.

---

## Excluded Tests (Destructive — Manual Only)

These operations are too destructive for automated testing. Test manually with extreme caution:

| Operation | Tool | Gateway | Why Excluded |
|-----------|------|---------|--------------|
| Reboot hub | `hub_reboot` | hub_manage_destructive_ops | 1-3 min downtime, kills automations |
| Shutdown hub | `hub_shutdown` | hub_manage_destructive_ops | Requires physical restart |
| Z-Wave repair | `hub_call_zwave` (repair action) | hub_manage_radio | 5-30 min, devices unresponsive |
| Delete real device | `hub_delete_device` | hub_manage_destructive_ops | Permanent, no undo |
| Destructive radio op (reset/wipe, firmware) | `hub_call_destructive_ops` (target=zwave\|zigbee\|matter) | hub_manage_destructive_ops | Orphans paired devices, no undo |
| Network disconnect (WiFi/Ethernet) | `hub_call_destructive_ops` (target=network, disconnect_wifi\|disconnect_ethernet) | hub_manage_destructive_ops | Can make the hub unreachable over that link |
| Cloud controller disable/enable | `hub_call_destructive_ops` (target=cloud, disable\|enable) | hub_manage_destructive_ops | Disabling severs Alexa/Google, cloud dashboards, cloud firmware updates, subscriptions |
| Network config (static IP / DHCP / Ethernet autoneg / WiFi) | `hub_set_system_settings` (network=...) | core | Can disconnect the hub; confirm-gated |
| Enable/disable Hub Mesh | `hub_update_hub_mesh` (enabled=...) | hub_manage_devices | Needs a hub reboot to take effect; hub-wide sharing change |
| Share a device / variable over Hub Mesh | `hub_update_device` (`fields.meshEnabled`) / `hub_set_variable` (mesh_shared) | hub_manage_devices | Reversible; shares one entity into the LAN mesh |
| Link a peer hub's shared device / variable | `hub_create_device` / `hub_create_variable` (mesh_source_*) | hub_manage_devices | Creates a local proxy; needs the peer's mesh token if it has UI login security |
| Unlink a Hub Mesh linked device | `hub_delete_device` | hub_manage_destructive_ops | Removes only the local link; the peer's source device is untouched |
| Unlink a Hub Mesh linked variable | `hub_delete_variable` (decorated `"<var> on <peer>"` name) | hub_manage_variables | Removes only the local mirror; peer source untouched; `force` only if a real local app uses it (`inUseByApps`) |
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
5. For Section 10 (NL Discovery): compare results with the equivalent tests from earlier sections — the same tool should be triggered from the casual phrasing as from the precise goal phrasing

### Results Template

| Test | Pass/Fail | Tools Called | Gateway Used | Turns | Duration | Notes |
|------|-----------|-------------|--------------|-------|----------|-------|
| T01  |           |             |              |       |          |       |

---

## Coverage Summary

### By Section

| Section | Tests | Purpose |
|---------|-------|---------|
| 1. Core Tools | T01-T19i | 13 flat core tools work directly |
| 2. Gateway Discovery | T20-T31, T35-T59 | LLM finds all proxied tools without hints |
| 3. Gateway Behavior | T60-T65 | Catalog mode, skip-catalog, errors |
| 4. Natural Language | T70-T79 | Casual prompts route correctly |
| 5. Multi-Step Workflows | T80-T86 | Complex multi-tool/gateway scenarios |
| 6. Ambiguity | T90-T96 | Correct routing under ambiguous prompts |
| 7. Edge Cases | T100-T108 | Error handling and boundary conditions |
| 8. Comparison | T110-T116 | v0.7.7 vs v0.8.0 regression |
| 9. Stress | T120-T122 | Many calls, rapid cycles, pagination |
| 10. NL Discovery | T200-T301 | Conversational prompts — no tool names |
| 12. Developer Mode | T219-T226, T660-T663, T667 | Self-administration: hub_update_mcp_settings (incl. selectedDevices device-access scope) + hub_delete_variable |
| 13. Driver Code Lifecycle | T400-T406 | hub_create_driver (single + bulk), hub_update_driver (bulk), delete |
| 14. Library Management | T500-T508 | Library CRUD: install, update, delete, hub_get_source |

### Architecture (post installed-apps + RM interop + Developer Mode + PR1B read/write split)

| Component | Count |
|-----------|-------|
| Flat core tools on `tools/list` | 13 |
| Gateways on `tools/list` | 23 |
| Total visible on `tools/list` | 36 |
| Total distinct tools in codebase | 115 |

With Developer Mode on, `hub_update_package` joins the flat core tools, so a Developer Mode hub lists one more.

**8 read gateways**: `hub_read_apps_code` (10), `hub_read_devices` (6), `hub_read_diagnostics` (6), `hub_read_files` (2), `hub_read_rooms` (2), `hub_read_rules` (6), `hub_read_variables` (3), `hub_read_dashboards` (2)

**15 manage gateways**: `hub_manage_backup` (4), `hub_manage_code` (10), `hub_manage_custom_rules` (8), `hub_manage_dashboards` (6), `hub_manage_destructive_ops` (4), `hub_manage_devices` (11), `hub_manage_diagnostics` (7), `hub_manage_files` (4), `hub_manage_logs` (3), `hub_manage_mcp` (1), `hub_manage_native_rules_and_apps` (12), `hub_manage_radio` (6), `hub_manage_rooms` (5), `hub_manage_rule_machine` (11), `hub_manage_variables` (8)

**13 flat core tools**: `hub_manage_virtual_device`, `hub_get_tool_guide`, `hub_report_issue`, `hub_search_tools`, `hub_get_info`, `hub_list_modes`, `hub_manage_mode`, `hub_set_mode_manager`, `hub_get_hsm_status`, `hub_set_hsm`, `hub_set_system_settings`, `hub_update_firmware`, `hub_create_backup`

### Tool Coverage (non-destructive tools only)

All 115 distinct tools are covered by at least one test, excluding the destructive operations listed in the Excluded Tests table. Safe tools have standalone test coverage; destructive tools are documented for manual-only testing.

Sections 1-9 each target a specific tool — named in the test's title and **Expected** criteria while the `test_prompt` stays goal-first (see Prompt style above). Section 10 re-tests the same tool coverage through purely conversational language to measure whether the LLM can discover tools without being told which ones exist. Section 11 covers the built-in app integration tools.

**Total: 266 test scenarios** (123 explicit + 65 natural language + 21 built-in-app integration + 9 library management + 2 reveal-walker coverage + 3 single-device condition authoring + 1 compareToDevice fallback + 1 Between-two-times sunrise/sunset + 9 periodic-frequency completeness + 5 Visual Rules Builder + 1 device swap + 2 installed-app read modes + 2 enum-attribute state-change comparator + 3 device-state state-change / fail-loud authoring parity + 3 Required Expression in-place replacement + 3 rule-local variable lifecycle/namespace + 5 read-side convergence + 1 multi-device convergence + 3 MCP device-access scope + 1 official-SDK MRTR proof + 1 multi-rule call_rule aggregation + 1 rule-backup policy proof + 1 protected-app policy proof) plus 13 excluded destructive operations documented for manual testing

---

## Section 11: Built-in App Integration Tests

Tools in this section have mixed gate requirements. `hub_list_apps` (scope=instances) and `hub_list_device_dependents` require the Read master. `hub_get_app_config` requires the Read master. `hub_manage_native_rules_and_apps` tools require the Read master; CRUD tools additionally require the Write master. Tests assume at least one Rule Machine rule and at least one Room Lighting or other multi-app configuration exists on the hub.

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

**Expected**: AI calls `hub_read_apps_code` with no args, sees catalog of 10 tools (`hub_list_apps`, `hub_list_drivers`, `hub_get_source`, `hub_list_libraries`, `hub_list_bundles`, `hub_list_backups`, `hub_get_backup`, `hub_list_device_dependents`, `hub_get_app_config`, `hub_list_hpm_packages`) with full parameter schemas. The installed-apps listing is reached via `hub_list_apps` with `scope='instances'`.

### T206 — Gateway catalog discovery (hub_manage_native_rules_and_apps)

```json
{
  "test_prompt": "What Rule Machine operations does the MCP support?"
}
```

**Expected**: AI calls `hub_manage_native_rules_and_apps` with no args, sees 12 tools. AI describes native RM authoring through `hub_set_rule`, generic classic-app authoring through `hub_set_native_app`, runtime controls, app enable/disable/delete/clone/export/import and rule health.

### T207 — AI uses native RM rule creation via hub_manage_native_rules_and_apps

```json
{
  "test_prompt": "Create a new Rule Machine rule named 'BAT-Motion-Light' that turns on Kitchen Light when motion is detected."
}
```

**Expected**: AI calls `hub_manage_rule_machine.hub_set_rule` with `operation="create"` (the Rule Machine `parent_id`, the `name`, and `changes` holding the motion trigger and the switch-on action), or creates the rule and adds both with one `operation="update"` call. Returns the new `rule_id`. Does NOT fall back to `hub_create_custom_rule` (that creates an MCP-engine rule, not a native RM rule).

### T208 — AI creates a Room Lighting instance

```json
{
  "test_prompt": "Create a new Room Lighting instance named 'BAT Study Lights'.",
  "teardown_prompt": "Delete the 'BAT Study Lights' Room Lighting instance with hub_delete_native_app."
}
```

**Expected**: AI calls `hub_manage_native_rules_and_apps.hub_set_native_app` (operation='create', args={app_type: 'room_lighting', name: 'BAT Study Lights'}). Room Lighting creation is supported: the tool installs the Room Lighting parent app first when the hub does not have it, then creates the child through that page's create control, presses its first page's Done and returns its app_id and label. AI reports the new instance and does not fabricate a result.

### T209 — Pause and resume an RM rule (reversible)

```json
{
  "setup_prompt": "List Rule Machine rules and identify one labeled with 'BAT' prefix, or if none exists, ask the user to identify a safe test rule.",
  "test_prompt": "Pause the rule, wait for me to confirm, then resume it.",
  "teardown_prompt": "Verify the rule is back in its original enabled/disabled state."
}
```

**Expected**: Calls `hub_set_rule_paused` with `paused=true` (pauses) → confirms with user → calls `hub_set_rule_paused` with `paused=false` (resumes). Both return `{success: true}`.

**WARNING**: Only runs if user has a BAT-prefixed RM rule OR explicitly identifies a safe rule. Never use a production rule.

### T209a — Report rule status (issue #359)

```json
{
  "setup_prompt": "List Rule Machine rules and identify one labeled with 'BAT' prefix, or if none exists, ask the user to identify a safe test rule. Pause it with hub_set_rule_paused.",
  "test_prompt": "How many of my Rule Machine rules are active right now, and which ones are paused or disabled?",
  "teardown_prompt": "Resume the rule that was paused during setup and verify it reads active again."
}
```

**Expected**: One `hub_list_rules` call; the AI counts by the per-rule `status` field and names the paused setup rule. FAIL if the AI answers that rule status is not exposed, or walks per-rule config pages to derive it.

### T209b — Run actions on an RM rule (bypasses conditions)

```json
{
  "setup_prompt": "List Rule Machine rules and identify one labeled with 'BAT' prefix whose actions are safe to fire at any time.",
  "test_prompt": "Run just the actions of that rule (skip the trigger/condition evaluation).",
  "teardown_prompt": "Verify the actions executed by checking the logs or the affected device states."
}
```

**Expected**: Calls `hub_call_rule` with `action='actions'`. Returns `{success: true, rmAction: 'runAction button'}`. AI reports success and any downstream device state changes.

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
  "test_prompt": "Read HPM's configuration pages to list ALL packages installed via Hubitat Package Manager."
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

### T217 — HPM page discovery (sub-page names from page.hrefs)

```json
{
  "setup_prompt": "the Read master is enabled. Use hub_list_apps (scope=instances) to find the Hubitat Package Manager app and note its app ID.",
  "test_prompt": "I want to inspect HPM's configuration but I don't know the page names. Discover what configuration pages are available for the HPM app you just found."
}
```

**Expected**: AI reads HPM's default page with `hub_read_apps_code(tool='hub_get_app_config', args={appId: <discovered_id>})` and lists the pages under `page.hrefs` with their titles: on a configured HPM the default page is `prefOptions` and links `prefPkgInstall`, `prefPkgUpdate`, `prefPkgModify`, `prefPkgRepair`, `prefPkgUninstall`, `prefPkgMatchUp`, `prefPkgView` and `prefSettings`. AI does not guess page names that the page does not link.

### T218 — Rule Machine rule page discovery (linked sub-pages)

```json
{
  "setup_prompt": "the Read master is enabled. Use hub_list_rules to find an existing Rule Machine rule and note its app ID.",
  "test_prompt": "What pages are available for the Rule Machine rule you just found?"
}
```

**Expected**: AI reads the rule's default page with `hub_read_apps_code(tool='hub_get_app_config', args={appId: <discovered_id>})` and lists `mainPage` plus the sub-pages in `page.hrefs` (`selectTriggers` and `selectActions`). AI says rules are authored through `hub_set_rule` rather than by driving those pages.

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

> Prompt-style carve-out: the prompt names the tool deliberately. With the Developer Mode toggle OFF the self-admin tool is hidden from `tools/list`, so only a by-name (cached-client) call can exercise the refusal path — a goal-first phrasing ("change the MCP log level") would route to `hub_set_log_level` and never hit it.

```json
{
  "setup_prompt": "First, manually disable 'Enable Developer Mode Tools' in the MCP rule app settings (UI). Confirm the Write master is still enabled and a recent backup exists.",
  "test_prompt": "Use hub_update_mcp_settings to change mcpLogLevel to warn."
}
```

**Expected**: Tool returns an `isError: true` MCP response with a message containing "Developer Mode tools are disabled" and pointing the user to the toggle. No setting is written. AI surfaces the message and asks the user to enable the toggle in the UI before retrying.

### T667 — Protected apps: generic mutations versus dedicated self-administration

Run on a dedicated test hub. Record the original Developer Mode, logging level and Protected apps selections. Do not use self-disable or self-delete as failure probes: a missing guard would terminate the endpoint needed for verification and cleanup. Fresh-install and upgrade default initialization, preservation of an intentionally empty list, and Developer Mode off/on refusal are also covered by the direct/dispatch specs.

```json
{
  "setup_prompt": "Enable the Write master and Developer Mode in the app UI, with a recent backup. Read the MCP server's installed-app ID and raw settings using hub_get_app_config(includeSettings=true). Confirm its own ID is selected in Protected apps; if the admin deliberately removed it, stop this scenario without changing their selection. Record mcpLogLevel and all raw settings. Create one disposable native Rule Machine rule named 'BAT Protected App' containing only a Log Message action and no triggers or device references, then select that fixture in Protected apps using the UI and click Done.",
  "test_prompt": "Inspect the protected test rule. Try renaming, disabling and deleting it, then try changing it through the rule editor. Explain each refusal and leave its configuration intact. Using the generic app editor, also try saving the MCP server's current logging level unchanged. Then use its dedicated developer settings tool to change the logging level temporarily and restore it. Verify that the protected apps list cannot be changed through that developer tool.",
  "teardown_prompt": "Restore the original logging level and Protected apps selections through their authorized interfaces. Remove only the disposable BAT Protected App rule after unprotecting it in the UI and clicking Done; if a missing guard already deleted it, do not recreate or delete any other app. Restore the original Developer Mode value in the UI and verify the original MCP settings are unchanged."
}
```

**Expected:** Reads remain available. Generic edit, disable, delete and `hub_set_rule` edits of the protected fixture fail with its installed-app ID and UI guidance, before any mutation, even with Developer Mode enabled. A same-value `hub_set_native_app` edit of the protected MCP instance is refused. `hub_update_mcp_settings` can change and restore the allowlisted logging level, but rejects `protectedAppIds`; it does not weaken protection. After manual removal of only the fixture from Protected apps and clicking Done, normal generic mutation and cleanup work. Repeat the fixture-only generic refusals with Developer Mode off; dedicated self-administration must then retain its existing refusal. Never disable/delete the MCP server itself.

### T220 — hub_update_mcp_settings flips a boolean setting end-to-end

```json
{
  "setup_prompt": "Developer Mode is enabled, the Write master is enabled, recent backup exists. Note the current value of debugLogging via hub_get_info or by reading state.",
  "test_prompt": "Flip the MCP server app's own debugLogging setting to true. Then verify the change took effect."
}
```

**Expected**: AI calls `hub_manage_mcp(tool='hub_update_mcp_settings', args={settings:{debugLogging:true}, confirm:true})`. Result: `{success:true, updated:{debugLogging:true}, message:"Updated 1 setting(s)..."}`. AI verifies via a follow-up read (e.g., the value persists across a subsequent call). Hub log shows a WARN-level `[developer-mode]` audit line.

### T221 — hub_update_mcp_settings rejects a setting outside the allowlist

```json
{
  "setup_prompt": "Developer Mode is enabled and the Write master is enabled.",
  "test_prompt": "Turn off the MCP server app's own enableWrite setting."
}
```

**Expected**: Tool returns a validation error naming `enableWrite` as not allowed for self-modification via hub_update_mcp_settings and listing the allowlisted keys. No change is made. AI explains that this setting is intentionally excluded (turning off the Write master would lock the agent out of its own write path) and offers to walk the user through the UI toggle.

### T222 — hub_update_mcp_settings batches multiple settings atomically

```json
{
  "setup_prompt": "Developer Mode is enabled and the Write master is enabled.",
  "test_prompt": "Set the MCP server app's log level to warn and turn on its debug logging, in one single change.",
  "teardown_prompt": "Put both settings back to the values the call reported as previous."
}
```

**Expected**: AI passes both keys in one `settings` map. Result: `{success:true, updated:{mcpLogLevel:"warn", debugLogging:true}, previous:{...}, message:"Updated 2 setting(s)..."}`. Both settings persist. The all-or-nothing pre-validation means a single bad key would have rejected the entire batch before any write — verifiable by re-running with one good and one bad key and confirming neither was applied.

### T223 — hub_update_mcp_settings includes a reconnect hint after toggling enable* flags

```json
{
  "setup_prompt": "Developer Mode is enabled.",
  "test_prompt": "Flip the MCP server app's enableCustomRuleEngine setting to false, then back to true."
}
```

**Expected**: Both calls return success. The `message` field on each response contains "MCP clients ... may need to reconnect to refresh cached tool schemas". AI surfaces this hint to the user explaining that tools/list won't reflect the toggle until the client reconnects.

### T223b — hub_update_mcp_settings flips useGateways (gateway-mode self-switch)

```json
{
  "setup_prompt": "Developer Mode is enabled, the Write master is enabled, recent backup exists. Note whether the server is currently in gateway mode or flat mode (useGateways).",
  "test_prompt": "Flip the MCP server app's useGateways setting to the OPPOSITE of its current value. Report the response message, then explain what the user must do for the new tool surface to take effect.",
  "teardown_prompt": "Use hub_update_mcp_settings to set useGateways back to its original value, then reconnect (/mcp refresh) so the tool list matches the server again."
}
```

**Expected**: AI calls `hub_manage_mcp(tool='hub_update_mcp_settings', args={settings:{useGateways:<flipped>}, confirm:true})`. The key is **accepted** (NOT rejected as outside the allowlist — this is the regression guard for the dev-mode gateway self-switch), result `{success:true, updated:{useGateways:<flipped>}, message:"...may need to reconnect to refresh cached tool schemas..."}`. The WARN `[developer-mode]` audit line fires. AI explains the client must reconnect (`/mcp refresh`) before tools/list reflects the new gateway-vs-flat surface. Teardown restores the original value.

#### Metadata cache regression after the mode switch

On an authorized test hub, switch to flat mode and call `hub_list_rooms` directly; a gateway call must be refused.
Return to gateway mode and verify `hub_read_rooms(tool='hub_list_rooms')` works. Avoid the full flat tools/list fetch.
Call `hub_read_rooms(tool='hub_get_room', args={})` twice, then search for `get room`.
Both invalid calls must identify `room` as required without `FLAT_TRIM` text; search must still find
`hub_get_room`. Repeat the existing Read/Write, Advanced override, and BPS gate scenarios after warming
the catalog: settings must take effect immediately despite cached metadata. Preserve the original settings.
This scenario requires no room creation or device command.

### T223c — Recent rule baseline reuse and strict per-write opt-in

```json
{
  "setup_prompt": "Developer Mode and the Write master are enabled, a recent hub backup exists, and backupEveryRuleWrite is false. Create a small Rule Machine rule named 'BAT Backup Reuse'.",
  "test_prompt": "Append four uniquely messaged Log Message actions to that same rule one at a time as four separate edits, recording each returned backup key. After the first two, enable the setting that creates a fresh backup for every native-app edit; then perform the final two separate edits and compare all four keys.",
  "teardown_prompt": "Always use hub_update_mcp_settings to set backupEveryRuleWrite back to false, even if an edit failed, then delete 'BAT Backup Reuse'."
}
```

**Expected**: the four requested writes append four distinctly messaged Log Message actions. All four backup keys are non-empty. With the default OFF policy, key 2 equals baseline key 1, proving the recent same-rule baseline was reused. After `hub_manage_mcp(tool='hub_update_mcp_settings', args={settings:{backupEveryRuleWrite:true}, confirm:true})`, key 3 differs from key 1, key 4 differs from key 1, and key 3 differs from key 4, proving both strict-mode writes took fresh per-write snapshots rather than reusing either the default baseline or each other. Teardown restores OFF even if an edit fails.

### T224 — hub_delete_variable removes a stale rule_engine variable

```json
{
  "setup_prompt": "the Write master is enabled, recent backup exists. Use hub_set_variable to create a temporary variable named BAT_E2E_DELETE_TEST with value 'scratch'.",
  "test_prompt": "We don't need BAT_E2E_DELETE_TEST any more. Delete that hub variable, then read it back to confirm it's gone."
}
```

**Expected**: AI calls `hub_manage_variables(tool='hub_delete_variable', args={name:'BAT_E2E_DELETE_TEST', confirm:true})`. Result: `{success:true, name:'BAT_E2E_DELETE_TEST', deleted:true, source:'rule_engine', previousValue:'scratch'}`. Follow-up `hub_get_variable` returns "Variable not found". Hub log shows a WARN-level `[developer-mode] hub_delete_variable: removed 'BAT_E2E_DELETE_TEST'` audit entry.

### T225 — hub_delete_variable deletes a hub variable created in the UI

```json
{
  "setup_prompt": "the Write master is enabled and a hub backup exists (<24h). Manually create a Hub Variable named BAT_UI_VAR via Settings → Hub Variables.",
  "test_prompt": "Delete the hub variable BAT_UI_VAR."
}
```

**Expected**: AI calls `hub_delete_variable(name='BAT_UI_VAR', confirm=true)`. The hub variable is deleted (a hub variable no rule uses needs no force) and `hub_list_variables` no longer lists it.

### T226 — hub_delete_variable refuses without confirm flag (the Write master 24h gate)

```json
{
  "setup_prompt": "the Write master is enabled, recent backup exists. Use hub_set_variable to create BAT_E2E_NO_CONFIRM with value 'safe'.",
  "test_prompt": "Delete the hub variable BAT_E2E_NO_CONFIRM, but do NOT confirm the destructive action — let's see what happens."
}
```

**Expected**: Gateway parameter validation returns an `isError: true` result with message `"Missing required parameter(s): confirm"` and a `parameters` description listing the schema (name, confirm, force). No JSON-RPC error code is set — the validation happens at the gateway layer before tool dispatch. The variable is preserved. AI relays the gate requirement and offers to retry with `confirm=true`.

### T660 — hub_update_mcp_settings selectedDevices re-scopes the MCP server's device access (add then remove, net no-op)

```json
{
  "setup_prompt": "Developer Mode is enabled, the Write master is enabled, recent backup exists. List every hub device with hub_list_devices(scope='all') and pick one device that is currently mcpAuthorized=false (NOT already in the MCP scope). Note the full set of currently-authorized device IDs.",
  "test_prompt": "Add that unauthorized device to the MCP server's device-access scope, confirm it now reads back as authorized in the all-devices listing, then remove it from the scope again so the scope returns to exactly what it was.",
  "teardown_prompt": "Verify via hub_list_devices(scope='all') that the authorized set matches the original noted set (the add+remove was a net no-op)."
}
```

**Expected**: The add call is `hub_manage_mcp(tool='hub_update_mcp_settings', args={settings:{selectedDevices:{mode:'add', ids:['<id>']}}, confirm:true})` → `{success:true, selectedDevices:{mode:'add', authorizedDeviceIds:[...], authorizedCount:N+1, added:['<id>'], removed:[]}, ...}`. The device then shows `mcpAuthorized=true` in `hub_list_devices(scope='all')`. The remove call (`mode:'remove'`, same id) returns `{success:true, selectedDevices:{mode:'remove', added:[], removed:['<id>'], authorizedCount:N}, ...}`, and the device reads back `mcpAuthorized=false`. A WARN `[developer-mode] hub_update_mcp_settings selectedDevices: applied ...` audit line fires for each. Net effect: the authorized set is unchanged.

### T661 — hub_update_mcp_settings selectedDevices rejects an unknown device id (atomic, nothing changed)

```json
{
  "setup_prompt": "Developer Mode is enabled, the Write master is enabled, recent backup exists.",
  "test_prompt": "Try to add device id 999999999 (which does not exist on this hub) to the MCP device-access scope."
}
```

**Expected**: Tool returns an MCP error (`-32602`) with a message naming the offending id, e.g. `Unknown device id(s): 999999999. Use hub_list_devices(scope='all') to see valid ids. Nothing was changed.` The authorized scope is unchanged. AI surfaces the message and offers to look up valid ids via `hub_list_devices(scope='all')`.

### T662 — hub_update_mcp_settings selectedDevices refuses to empty the scope without allowEmpty

```json
{
  "setup_prompt": "Developer Mode is enabled, the Write master is enabled, recent backup exists. Note the currently-authorized device IDs.",
  "test_prompt": "Replace the MCP server's device-access scope with an EMPTY device list, without passing any special override flag for emptying it. This should be refused."
}
```

**Expected**: Tool returns an MCP error (`-32602`) explaining the self-lockout guard, e.g. `Refusing to empty the MCP device-access scope: ... Pass selectedDevices.allowEmpty:true to confirm you intend to clear the scope.` The scope is unchanged. AI explains that an empty scope blinds the server to every selected device and that `allowEmpty:true` is required to proceed.

### T663 — bypassDeviceAllowlist lets device tools reach a device OUTSIDE the allowlist

```json
{
  "setup_prompt": "Developer Mode is enabled, the Write master is enabled, recent backup exists, and bypassDeviceAllowlist is OFF (the default). List every hub device with hub_list_devices(scope='all') and pick one that is currently mcpAuthorized=false (NOT in the MCP scope). Confirm hub_get_device on that id returns 'Device not found'.",
  "test_prompt": "Turn on the MCP server app's device-allowlist bypass setting (bypassDeviceAllowlist), then read that same unauthorized device's details and report whether it is now reachable.",
  "teardown_prompt": "Turn the bypass back OFF with hub_update_mcp_settings({settings:{bypassDeviceAllowlist:false}}) and confirm hub_get_device on that id returns 'Device not found' again."
}
```

**Expected**: With the toggle OFF the unlisted device is not reachable (`hub_get_device` → `-32602 Device not found`). The flip call is `hub_manage_mcp(tool='hub_update_mcp_settings', args={settings:{bypassDeviceAllowlist:true}, confirm:true})` → `{success:true, updated:{bypassDeviceAllowlist:true}, ...}` with a WARN `[developer-mode]` audit line. With the bypass ON, `hub_get_device(deviceId='<id>')` now resolves the device through the hub's id-keyed `/device/fullJson` fallback (returns its id/label/room/capabilities). The AI notes that the bypass removes the device-selection security boundary and that the same parity applies to `hub_get_device_attribute`, `hub_call_device_command` (incl. `waitFor`), `hub_update_device`, and `hub_list_device_events` — but NOT to `hub_list_devices`, device swap/replace/delete, or device-health. Teardown restores `bypassDeviceAllowlist:false`, after which the device is unreachable again.

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
  "test_prompt": "Install the driver from the File Manager file 'bat-test-driver.groovy' — do not paste the source inline — and confirm the install. Report the new driver ID.",
  "teardown_prompt": "Delete the driver installed in this test using hub_delete_item (type=driver) with the driverId returned above and confirm=true. Also delete the File Manager file bat-test-driver.groovy using hub_delete_file."
}
```

**Expected**: Tool resolves the file from File Manager, POSTs to `/driver/save`, fetches the new driver back to verify it compiled, and returns `success: true` with `driverId` set and `sourceMode: 'sourceFile'`. No inline source was sent in the install call -- the source came from File Manager.

### T401 — hub_create_driver compile failure detected (post-install verification)

```json
{
  "setup_prompt": "the Write master enabled, recent backup exists. This test intentionally installs broken Groovy source. The hub refuses it with its compile error; older firmware also left a stub slot in an error state -- if a driver ID comes back, note it for cleanup.",
  "test_prompt": "Install a driver whose source is deliberately broken Groovy — literally 'this is not valid groovy {{ }}' — confirming the install. Report what happens.",
  "teardown_prompt": "Delete the error-state driver slot created in this test using hub_delete_item (type=driver) with the driverId returned and confirm=true."
}
```

**Expected**: Tool returns `success: false` with the hub's compile error message. On current firmware no driver is created; where an older firmware leaves an error-state stub, the response carries its ID. AI reports the error and does not claim success.

### T402 — hub_update_driver bulk mode (happy path)

```json
{
  "setup_prompt": "the Write master enabled, recent backup exists. Install two minimal BAT_ driver stubs via hub_create_driver: (1) source='metadata { definition(name: \"BAT_BulkUpdate1\", namespace: \"bat\", author: \"test\") { } }' and (2) source='metadata { definition(name: \"BAT_BulkUpdate2\", namespace: \"bat\", author: \"test\") { } }'. Note both driverIds. Write updated source for each to File Manager with a version comment appended: hub_write_file(fileName='bat-bulk-1.groovy', content='...updated source...') and similarly for bat-bulk-2.groovy.",
  "test_prompt": "Update both drivers in a single call — driver <id1> from the File Manager file 'bat-bulk-1.groovy' and driver <id2> from 'bat-bulk-2.groovy' — confirming the update.",
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
  "test_prompt": "Install both drivers in a single call — one from the File Manager file 'bat-bulk-install-1.groovy' and the other from 'bat-bulk-install-2.groovy' — confirming the install. Report the driver IDs returned.",
  "teardown_prompt": "Delete both BAT_ drivers installed above using hub_delete_item (type=driver) with confirm=true for each driverId. Also delete the File Manager files bat-bulk-install-1.groovy and bat-bulk-install-2.groovy using hub_delete_file."
}
```

**Expected**: Tool installs both drivers in a single call, resolving each from File Manager. Returns `success: true` with `installs` array containing two entries, each with `success: true`, a non-null `driverId`, and `sourceMode: 'sourceFile'`. `message` says "All 2 driver(s) installed successfully." No inline source was passed in the call.

### T405 — hub_create_driver bulk mode partial failure

```json
{
  "setup_prompt": "the Write master enabled, recent backup exists. Write one driver stub to File Manager: hub_write_file(fileName='bat-bulk-partial.groovy', content='metadata { definition(name: \"BAT_BulkPartial\", namespace: \"bat\", author: \"test\") { } }'). Do NOT write 'bat-bulk-missing.groovy'.",
  "test_prompt": "Install two drivers in a single bulk call — one from the File Manager file 'bat-bulk-partial.groovy' and one from 'bat-bulk-missing.groovy' — confirming the install. Report what happens for each item.",
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
  "test_prompt": "Show me the source code of the first Groovy library installed on the hub."
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
  "test_prompt": "Update the BATTestLibUpdate library to add a `v2Method()` that returns 'v2', supplying the full updated source inline.",
  "teardown_prompt": "Delete BATTestLibUpdate library."
}
```

**Expected**: AI finds the library ID, calls `hub_manage_code(tool='hub_update_library', args={libraryId:'<id>', source:'<updated source with v2Method>', confirm:true})`. Result: `{success:true, previousVersion:<N>, newVersion:<N+1>, sourceMode:'source'}` where `newVersion > previousVersion` (both positive integers). Pre-update backup appears in `hub_list_backups` as a `library_<id>` key.

### T503 — hub_update_library resave mode recompiles without external source

```json
{
  "setup_prompt": "the Write master enabled, recent backup exists. A library named 'BATTestLibResave' exists (install it if not).",
  "test_prompt": "Recompile the BATTestLibResave library in place, without changing or re-supplying its source.",
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

**Expected**: AI calls `hub_manage_code(tool='hub_delete_item', args={type:'library', id:'<id>', confirm:true})`. Result: `{success:true, backupFile:'mcp-backup-library-<id>[-<uuid>].groovy', restoreHint:...}`. Backup file appears in `hub_list_backups`. Subsequent `hub_get_source` (type=library) for the same ID returns `success:false` with "not found".

### T505 — hub_create_library refuses without confirm flag

```json
{
  "setup_prompt": "the Write master enabled.",
  "test_prompt": "Try to install a library with valid source but WITHOUT confirming the action. Observe the error."
}
```

**Expected**: Gateway (or tool) returns an error (isError or -32602) containing "SAFETY CHECK FAILED" or "confirm". No library is created. AI explains the confirm requirement and the mandatory pre-flight checklist (backup).

### T506 — hub_get_source (type=library) returns error for non-existent library

```json
{
  "test_prompt": "Try to fetch the source of library ID 999999. What comes back?"
}
```

**Expected**: AI calls `hub_read_apps_code(tool='hub_get_source', args={type:'library', id:'999999'})`. Result: `{success:false, error:'...not found...'}` or similar. AI reports the error clearly and suggests using hub2/userLibraries or the Hubitat web UI (FOR DEVELOPERS > Libraries code) to find valid library IDs.

### T507 — hub_update_library sourceFile mode reads from File Manager

```json
{
  "setup_prompt": "the Write master enabled, recent backup exists. Install 'BATTestLibSourceFile' library. Upload a file named 'bat-lib-update.groovy' to File Manager via hub_write_file with updated library source.",
  "test_prompt": "Update the BATTestLibSourceFile library from the File Manager file 'bat-lib-update.groovy'.",
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
  "setup_prompt": "the Write master enabled, recent backup exists. Use the repository's reviewed app-only fixture tests/fixtures/mcp-e2e-throwaway-bundle.zip (one unused app in namespace mcptest, no libraries) at the revision under test, by its raw GitHub URL for that commit SHA.",
  "test_prompt": "Install the bundle from that .zip URL (confirming the install), then verify the bundle's app now appears in the hub's Apps Code list.",
  "teardown_prompt": "Delete the bundle and the app code it installed."
}
```

**Expected**: AI calls `hub_manage_code(tool='hub_install_bundle', args={importUrl:'https://.../<bundle>.zip', confirm:true})`. Result: `{success:true, endpoint:'/bundle2/uploadZipFromUrl', message:'Bundle installed...'}` on firmware >= 2.3.8.108 (older firmware uses `/bundle/uploadZipFromUrl`). A follow-up `hub_list_apps` shows the bundle's app. This exercises the hub's bundle fetch + unpack for app code (the fixture carries no libraries), the same server-side path Hubitat Package Manager uses for a bundle, no UI.

### T510 — hub_install_bundle refuses without confirm flag

```json
{
  "setup_prompt": "the Write master enabled.",
  "test_prompt": "Try to install a bundle from a .zip URL WITHOUT confirming the action. Observe the error."
}
```

**Expected**: Gateway (or tool) returns an error (isError or -32602) containing "SAFETY CHECK FAILED" or "confirm". No bundle is installed. AI explains the confirm requirement and the mandatory pre-flight checklist (backup).

### T511 — hub_install_bundle rejects a non-http(s) importUrl

```json
{
  "setup_prompt": "the Write master enabled, recent backup exists.",
  "test_prompt": "Try to install a bundle from just the bare filename 'mcp-libraries.zip' (no http/https URL), confirming the action. What error do you get?"
}
```

**Expected**: Gateway (or tool) returns an error (isError or -32602) containing "scheme must be http or https". AI recognizes that `hub_install_bundle` needs a full URL the hub can fetch, not a File Manager filename, and corrects to a raw https URL.

### T512 — hub_list_bundles lists installed code bundles

```json
{
  "setup_prompt": "the Read master enabled. At least one code bundle is installed: on a fresh hub run T509 first and defer its teardown until T514 completes (T512, T513 and T514 reuse that fixture); a hub upgraded from 4.6.0 or earlier still carries the unused mcp_libraries bundle, which also qualifies here.",
  "test_prompt": "List the code bundles installed on the hub and tell me what each one contains."
}
```

**Expected**: AI calls `hub_read_apps_code(tool='hub_list_bundles')` (or the flat tool). Result lists each installed bundle with its `contains` apps/drivers/libraries, each entry carrying an `id`. AI explains bundles are the Bundle-Manager containers HPM delivers code in — distinct from the Libraries Code entries (`hub_list_libraries`).

### T513 — hub_export_bundle saves a bundle .zip to the File Manager

```json
{
  "setup_prompt": "the Write master enabled. Identify an installed bundle's id with hub_list_bundles (the T509 fixture, kept installed through T514).",
  "test_prompt": "Export that bundle's zip to the File Manager, confirm the file actually landed, and tell me where to download it.",
  "teardown_prompt": "Optionally delete the exported .zip from the File Manager with hub_delete_file."
}
```

**Expected**: AI calls `hub_manage_code(tool='hub_export_bundle', args={bundleId:<id>})`. Result `{success:true, fileName:'<name>.zip', bytes:>0, directDownload:'/local/<name>.zip'}`; a follow-up `hub_list_files` shows `<name>.zip` present, confirming the write landed (a complete export round-trip); AI points the user at the `/local/...` URL. No `confirm` is required — export is a non-destructive write (it only creates a File Manager file). This is the standing manual proof that `hub_export_bundle` works end-to-end: the e2e `test_export_bundle` asserts the same success envelope programmatically and only soft-passes when a late-run relay 504 makes the File Manager listing unverifiable (never on any other failure).

### T514 — hub_delete_bundle removes a bundle container (DESTRUCTIVE)

```json
{
  "setup_prompt": "the Write master enabled, recent backup exists. Identify the DISPOSABLE T509 fixture bundle's id with hub_list_bundles -- never a bundle another package installed.",
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

**Reading note for T607-T619 Expected sections**: where these scenarios say "no broken markers", the check is `hub_get_rule_health` returning `ok=true` with an empty `brokenMarkers` list. Together they confirm the rule picked up no `*BROKEN*` rendering markers after the write.

### T607 — Set a hub variable to a constant value inside an RM rule

```json
{
  "setup_prompt": "the Write master is enabled. Create an RM rule called 'BAT SetVariable Test'. Also ensure at least one hub connector variable exists (use hub_manage_variables hub_set_variable to create 'bat_setvar_test' = 0 if it does not exist).",
  "test_prompt": "Add an action to the 'BAT SetVariable Test' rule that sets the hub variable 'bat_setvar_test' to 99. Then check the rule's health and confirm no broken markers are present.",
  "teardown_prompt": "Delete the 'BAT SetVariable Test' rule. Delete the hub variable 'bat_setvar_test'."
}
```

**Expected**: The agent adds one Set Variable action with `hub_set_rule(operation="update", rule_id=N, args={changes:[{part:"action", value:{...}}]}, confirm=true)`, using `operation="form"` to discover the action's native inputs when it needs them. The write reports `success=true`. `hub_get_rule_health` reports no broken markers. The action renders on the rule's main page as "Set bat_setvar_test to 99".

**Failure modes**: The action renders as "Set bat_setvar_test to null" or carries a broken marker. The value is stored as text for a numeric variable. A variable name the hub does not have is accepted and leaves a broken action, instead of a refusal that writes nothing.

### T608 — Set Mode action chosen by mode name

```json
{
  "setup_prompt": "the Write master is enabled. Note the hub's current mode names via hub_list_modes. Create an RM rule called 'BAT ModeName Test'.",
  "test_prompt": "Add an action to 'BAT ModeName Test' that changes the hub's location mode, referring to the mode by its NAME (any valid mode name from the hub's mode list), not its numeric ID. Then check the rule's health and confirm no broken markers.",
  "teardown_prompt": "Delete the 'BAT ModeName Test' rule."
}
```

**Expected**: The agent maps the name to the mode the hub offers and adds one Set Mode action. The write reports `success=true`. The action renders with that mode's name, and `hub_get_rule_health` reports no broken markers. RM's mode input stores the mode ID, so the read's settings show the ID that belongs to the named mode.

**Failure modes**: The action renders as "Mode: null" (the name was stored where RM expects the mode ID). A mode name the hub does not have is accepted and produces a broken action, instead of being refused with nothing written.

### T609 — Run a custom command whose parameter comes from a hub variable

```json
{
  "setup_prompt": "the Write master enabled. Create hub variable 'bat_cpvar_test' (numeric, value 50). Create a virtual switch device named 'BAT RunCmd Switch' via hub_manage_virtual_device. Create RM rule 'BAT RunCommand Variable Param'.",
  "test_prompt": "Add an action that runs the custom command 'setLevel' on 'BAT RunCmd Switch', passing one number parameter sourced from the hub variable 'bat_cpvar_test' (not a literal value). Then check the rule's health.",
  "teardown_prompt": "Delete 'BAT RunCommand Variable Param' rule. Delete bat_cpvar_test variable. Delete virtual device 'BAT RunCmd Switch'."
}
```

**Expected**: One Run Custom Action is added and the write reports `success=true`. `hub_get_rule_health` returns no broken markers. The action renders as setLevel on 'BAT RunCmd Switch' with its parameter taken from bat_cpvar_test. RM allocates a numbered slot per parameter (`cpType<P>.<N>`); a variable-sourced parameter shows `uVar<P>.<N>=true` and `xVar<P>.<N>=bat_cpvar_test` in the read's settings, not the variable name as a literal `cpVal<P>.<N>`. A variable name the hub does not have is refused with nothing written.

**Failure modes**: Parameter silently dropped. Rule health shows a broken action. The variable NAME stored as a literal parameter value.

### T610 — Set a hub variable by copying another hub variable

```json
{
  "setup_prompt": "the Write master is enabled. Create hub variables 'bat_sv_src' (numeric, value 77) and 'bat_sv_dst' (numeric, value 0). Create RM rule 'BAT SetVariable Source Test'.",
  "test_prompt": "Add an action to 'BAT SetVariable Source Test' that sets the hub variable 'bat_sv_dst' by copying the value of the hub variable 'bat_sv_src'. Then check the rule's health and confirm no broken markers.",
  "teardown_prompt": "Delete 'BAT SetVariable Source Test'. Delete hub variables bat_sv_src and bat_sv_dst."
}
```

**Expected**: The write reports `success=true` and the action renders as setting bat_sv_dst to the value of bat_sv_src (a variable source, not a number). `hub_get_rule_health` reports no broken markers. RM shows the source-variable picker only after the value type is set to variable (`numOp.<N>='variable'`), so the agent needs a render between those two inputs. A variable name the hub does not have, for either side, is refused with nothing written.

**Failure modes**: Action renders as "Set bat_sv_dst to null" (a number source was chosen instead of variable). The source variable is dropped. An unknown variable name is accepted and produces a broken action.

### T647 — Set a hub variable from a device attribute

```json
{
  "setup_prompt": "the Write master is enabled. Create hub variable 'bat_sv_fd' (numeric, value 0). Note a BAT_E2E_ virtual temperature sensor device id (or any device exposing a numeric 'temperature' attribute). Create RM rule 'BAT SetVariable FromDevice Test'.",
  "test_prompt": "Add an action to 'BAT SetVariable FromDevice Test' that sets the hub variable 'bat_sv_fd' from a device attribute — the 'temperature' attribute of device <temperature sensor id>. Then check the rule's health and confirm no broken markers.",
  "teardown_prompt": "Delete 'BAT SetVariable FromDevice Test'. Delete hub variable bat_sv_fd."
}
```

**Expected**: The write reports `success=true` and the action renders as setting bat_sv_fd from the temperature of the chosen device. `hub_get_rule_health` reports no broken markers. RM reveals the device picker after the device-attribute source is chosen, then lists only the attributes of that device that fit the target's type: a numeric variable is offered `temperature` but not a switch's `switch` attribute. RM offers the device-attribute source only for a Number or Decimal target. A device ID outside the MCP-selected set is still valid if the hub has it.

**Failure modes**: The attribute is stored without the device, or from outside the offered list. An attribute the hub does not offer is accepted and produces a broken action instead of a refusal that writes nothing. The agent claims a device-attribute source on a String, Boolean or DateTime target.

### T648 — Variable math: one binary and one unary operation

```json
{
  "setup_prompt": "the Write master is enabled. Create hub variable 'bat_sv_math' (numeric, value 0). Create RM rule 'BAT SetVariable Math Test'.",
  "test_prompt": "Add a variable-math action to 'BAT SetVariable Math Test' that sets the hub variable 'bat_sv_math' to bat_sv_math + 10 (a binary operation). Then add a second math action that sets 'bat_sv_math' to the absolute value of bat_sv_math (a unary operation). Then check the rule's health and confirm no broken markers.",
  "teardown_prompt": "Delete 'BAT SetVariable Math Test'. Delete hub variable bat_sv_math."
}
```

**Expected**: Both writes report `success=true`, and the two actions render as bat_sv_math + 10 and the absolute value of bat_sv_math. `hub_get_rule_health` reports no broken markers. In the read's settings the binary action has `valMathOp.<N>='+'` with the constant 10 as its right operand (`xVar4.<N>='(constant)'`, `valConst2.<N>=10`); the unary action has `valMathOp.<N>='absolute'` and no right operand. RM offers variable math only for a Number or Decimal target.

**Failure modes**: A right operand stored for the unary operation. The constant stored as a variable name. An unknown variable operand accepted. Math accepted into a non-numeric target variable.

### T611 — Required Expression and IF action built from Mode and Variable conditions

```json
{
  "setup_prompt": "the Write master is enabled. Create hub variable 'bat_re_walker_var' (numeric, value 0). Note the hub's modes via hub_list_modes -- pick one valid mode name and the hub variable name. Create RM rule 'BAT Walker Parity Test'.",
  "test_prompt": "First, add a Required Expression to 'BAT Walker Parity Test' with two conditions joined by AND: the hub mode is <valid mode name>, AND the hub variable 'bat_re_walker_var' is greater than 0. Then add an IF-THEN action whose condition is that same mode check. Then check the rule's health and confirm no broken markers.",
  "teardown_prompt": "Delete 'BAT Walker Parity Test'. Delete hub variable bat_re_walker_var."
}
```

**Expected**: Each write reports `success=true` and `hub_get_rule_health` reports no broken markers. The Required Expression paragraph on mainPage renders both conditions (Mode and Variable) joined by AND, not the bare "Define Required Expression" placeholder. The IF action renders the Mode condition (not "BROKEN"); it can reuse the Mode condition's native ID. The tool refuses an IF left open, so the agent closes it with END-IF in the same edit.

**Failure modes**: Required Expression placeholder remains on mainPage. The Mode condition in the IF renders as Broken Condition. The Variable comparator or value is dropped.

### T612 — Required Expression between two clock times

```json
{
  "setup_prompt": "the Write master is enabled. Create RM rule 'BAT Between Two Times Test'.",
  "test_prompt": "Add a Required Expression to 'BAT Between Two Times Test' restricting it to between two clock times: 08:00 and 22:00. Then check the rule's health and confirm no broken markers.",
  "teardown_prompt": "Delete 'BAT Between Two Times Test'."
}
```

**Expected**: The write reports `success=true` and `hub_get_rule_health` reports no broken markers. The Required Expression paragraph on mainPage renders a time range from 8:00 AM to 10:00 PM, not the bare "Define Required Expression" placeholder. The read's settings show the condition's start and end set to clock times (`starting<N>` / `startingA<N>` and `ending<N>` / `endingA<N>`).

**Failure modes**: Required Expression placeholder remains. Broken Condition marker on the Required Expression row. One of the two times is missing from the render.

---

### T613 — Required Expression on one motion sensor

```json
{
  "setup_prompt": "the Write master is enabled. Create RM rule 'BAT DeviceId Norm Test'. Identify one motion sensor deviceId on the hub.",
  "test_prompt": "Add a Required Expression to 'BAT DeviceId Norm Test' that requires motion sensor <motionSensorId> to be active. Then check the rule's health and confirm no broken markers.",
  "teardown_prompt": "Delete 'BAT DeviceId Norm Test'."
}
```

**Expected**: The write reports `success=true` and `hub_get_rule_health` reports no broken markers. The Required Expression paragraph on mainPage renders a motion condition naming the sensor (e.g. "MotionSensor is active"), not the bare "Define Required Expression" placeholder.

**Failure modes**: Required Expression placeholder remains. Broken Condition marker (the condition's device did not land).

---

### T614 — Conditional trigger gated by one motion sensor

```json
{
  "setup_prompt": "the Write master is enabled. Create RM rule 'BAT AddTrigger Cond DeviceId'. Identify one Switch deviceId and one Motion sensor deviceId on the hub.",
  "test_prompt": "Add a trigger to 'BAT AddTrigger Cond DeviceId' that fires when switch <switchId> turns on, but only if motion sensor <motionId> is active at that moment (a conditional trigger). Then check the rule's health and confirm no broken markers.",
  "teardown_prompt": "Delete 'BAT AddTrigger Cond DeviceId'."
}
```

**Expected**: The write reports `success=true` and `hub_get_rule_health` reports no broken markers. The trigger paragraph renders the gating condition (e.g. "Switch on -- ONLY IF Motion is active"). The agent builds the Motion condition and points the trigger's conditional option at it, through native typed fields or `operation="form"`.

**Failure modes**: "Broken Trigger" marker. The trigger's condition renders as a placeholder. The trigger is saved without its condition and fires unconditionally.

---

### T615 — IF action on one humidity sensor's attribute

```json
{
  "setup_prompt": "the Write master is enabled. Create RM rule 'BAT AddAction Cond DeviceId'. Identify one Custom Attribute capable device on the hub (humidity sensor preferred).",
  "test_prompt": "Add an IF-THEN action to 'BAT AddAction Cond DeviceId' whose condition is: the 'humidity' attribute of device <humidityDeviceId> is below 40 (use RM's 'Custom Attribute' condition). Verify the IF action renders without broken markers.",
  "teardown_prompt": "Delete 'BAT AddAction Cond DeviceId'."
}
```

**Expected**: The write reports `success=true` and `hub_get_rule_health` reports no broken markers. The IF paragraph on mainPage renders the attribute comparison (e.g. "IF Humidity < 40 THEN"). The tool refuses an IF left open, so the agent adds END-IF in the same edit. Mirrors T613 on the action page.

**Failure modes**: IF paragraph renders the bare "Define IF" placeholder. Broken Condition marker (the condition's device did not land).

---

### T618 — Required Expression comparing one device's temperature with another's

```json
{
  "setup_prompt": "the Write master is enabled. Create RM rule 'BAT CompareToDevice'. Identify two Temperature-capable devices on the hub (deviceA + deviceB)."
,
  "test_prompt": "Add a Required Expression to 'BAT CompareToDevice': deviceA's temperature is greater than deviceB's temperature minus 2 (a device-relative comparison with a -2 offset, not a literal threshold). Then read the rule back and report how the Required Expression renders.",
  "teardown_prompt": "Delete 'BAT CompareToDevice'."
}
```

**Expected**: The write reports `success=true`. The Required Expression paragraph on mainPage renders the device-relative comparison with the offset -- e.g. "Temperature of deviceA is > deviceB - 2.0" -- NOT a literal threshold and NOT "deviceA > 0". `hub_get_rule_health` reports `ok=true` with no broken markers. The read's settings show the device-relative toggle `isDev_<N>=true`, the reference device in `relDevice_<N>`, and the offset in `state_<N>`. An offset of 0 is also valid; the paragraph then has no "- N.0" tail.

**Native-form note**: RM reveals the single `relDevice_<N>` picker only after `isDev_<N>=true`, so the agent needs a render between them. The picker is a `capability.*` device input whose options RM fills in the browser, so an empty option list in the page descriptor is normal. The compared attribute is implied by the shared capability; there is no separate reference-attribute picker.

**Failure modes**: The paragraph renders deviceA against a literal value or "deviceA > 0" (the device-relative toggle or picker did not land). The offset is missing. A broken marker on the condition. The agent reports success while the render shows a different comparison.

---

### T619 — Required Expression between a clock time and an offset from sunset

```json
{
  "setup_prompt": "the Write master is enabled. Hub timezone is set (Settings > Location and Modes). Create RM rule 'BAT Between Sunrise'."
,
  "test_prompt": "Add a Required Expression to 'BAT Between Sunrise' restricting it to between 08:00 (a clock time) and 30 minutes BEFORE sunset. Inspect the rule paragraph on mainPage.",
  "teardown_prompt": "Delete 'BAT Between Sunrise'."
}
```

**Expected**: The write reports `success=true`. The Required Expression paragraph on mainPage renders both the clock start (e.g. "between 8:00 AM") AND the sunset end with the -30 minute offset (e.g. "and 30 minutes before sunset"). `hub_get_rule_health` reports `ok=true` with no broken markers. The sunset end and its offset (`endingA<N>` / `endSunriseOffset<N>`) are a different input path from T612's clock-only times.

**Failure modes**: The end half renders "(unset)" or a similar placeholder (the sunset offset never landed). A broken marker on the time-band condition. The agent ignores a missing hub timezone, which RM needs for sunrise and sunset.

---

### T620 — Required Expression comparing two hub variables

```json
{
  "setup_prompt": "the Write master is enabled. Create two hub variables (number type) named 'BatVarA' and 'BatVarB'. Create RM rule 'BAT VarVsVar'.",
  "test_prompt": "Add a Required Expression to 'BAT VarVsVar' comparing two hub variables: BatVarA is greater than the value of BatVarB (variable versus variable, not a literal number). Then inspect the rule paragraph on mainPage.",
  "teardown_prompt": "Delete 'BAT VarVsVar' and remove BatVarA / BatVarB."
}
```

**Expected**: The write reports `success=true`. The Required Expression paragraph renders the variable-vs-variable comparison (e.g. "BatVarA > BatVarB"), NOT "BatVarA > 0" or "BatVarA > null". RM reveals the right-hand variable picker only after `isVar_<N>=true`, so the agent needs a render between them and takes the picker's name from that render. The same comparison inside an IF action renders the same way.

**Failure modes**: The paragraph renders "BatVarA > 0" or "BatVarA > null" (the right-hand picker never received the variable). The agent reports success while the right-hand variable is missing.

---

### T641 — IF action comparing one device's temperature with another's

```json
{
  "setup_prompt": "the Write master is enabled. Create RM rule 'BAT CtdAction'. Identify two Temperature-capable devices on the hub (deviceA + deviceB)."
,
  "test_prompt": "Add an IF-THEN action to 'BAT CtdAction' whose condition is: deviceA's temperature is greater than deviceB's temperature minus 2 (a device-relative comparison with a -2 offset, not a literal threshold). Then read the rule back and report how the IF action renders.",
  "teardown_prompt": "Delete 'BAT CtdAction'."
}
```

**Expected**: The write reports `success=true`. The IF clause on mainPage renders the device-relative comparison with the offset -- e.g. "IF Temperature of deviceA is > deviceB - 2.0 THEN" -- NOT a literal threshold and NOT "deviceA > 0". The read's settings show `isDev_<N>=true`, the reference device in `relDevice_<N>`, and the offset in `state_<N>`, as in T618. The tool refuses an IF left open, so the agent adds END-IF in the same edit; `hub_get_rule_health` then reports no broken markers. (Numbering note: T619 and T620 are already taken by the Between-two-times and compareToVariable scenarios, so this action-page scenario uses the next free number, T641.)

**Failure modes**: The IF clause renders deviceA against a literal value or "deviceA > 0". The offset is missing. A broken marker on the condition.

---

### T622 — Custom Attribute trigger on any change of an attribute

```json
{
  "setup_prompt": "the Write master is enabled. Identify a device exposing a custom attribute (e.g. a sensor with a 'water' attribute). Create RM rule 'BAT CustomChanged'.",
  "test_prompt": "Add a trigger to 'BAT CustomChanged' using RM's 'Custom Attribute' capability on device <deviceId>'s 'water' attribute, firing on ANY change of the attribute. Then read the rule back and report how the trigger renders.",
  "teardown_prompt": "Delete 'BAT CustomChanged'."
}
```

**Expected**: The write reports `success=true`. mainPage shows the rendered Custom Attribute trigger on the water attribute firing on change, not the "Define Triggers" placeholder. `hub_get_rule_health` reports `ok=true`. The read's settings show the attribute in `tCustomAttr_<N>` and the change comparator in `ReltDev_<N>` (an attribute the hub treats as an enum uses the value picker instead; see T642).

**Failure modes**: The trigger is missing or renders "null". The agent reports success while the comparator did not land.

---

### T623 — Periodic trigger every few seconds

```json
{
  "setup_prompt": "the Write master is enabled. Create RM rule 'BAT Periodic Seconds'.",
  "test_prompt": "Add a periodic-schedule trigger to 'BAT Periodic Seconds' that fires every 5 seconds. Inspect the trigger paragraph on mainPage.",
  "teardown_prompt": "Delete 'BAT Periodic Seconds'."
}
```

**Expected**: The write reports `success=true`. The trigger paragraph renders "Every 5 seconds" (not "?" or "null"). `hub_get_rule_health` reports `ok=true` with no broken markers. RM's Seconds mode is a single count input (`everyNSecs1`) with no toggle.

**Failure modes**: The paragraph renders "?" or "null" (the count did not land).

---

### T624 — Periodic trigger every few minutes

```json
{
  "setup_prompt": "the Write master is enabled. Create RM rule 'BAT Periodic Minutes'.",
  "test_prompt": "Add a periodic-schedule trigger to 'BAT Periodic Minutes' that fires every 15 minutes. Inspect the trigger paragraph on mainPage.",
  "teardown_prompt": "Delete 'BAT Periodic Minutes'."
}
```

**Expected**: The write reports `success=true`. The trigger paragraph renders "Every 15 minutes". `hub_get_rule_health` reports `ok=true` with no broken markers. RM shows the minutes count (`everyNC1`) only after the minutes toggle (`everyNMinutesC1=true`), so the count goes into its own input after a render, not into the toggle.

**Failure modes**: The paragraph renders "?" or "null" because the count was stored in the toggle and `everyNC1` stayed empty.

---

### T625 — Weekly periodic trigger on chosen days

```json
{
  "setup_prompt": "the Write master is enabled. Create RM rule 'BAT Periodic Weekly'.",
  "test_prompt": "Add a periodic-schedule trigger to 'BAT Periodic Weekly' that fires weekly on Monday and Friday at 08:00. Inspect the trigger paragraph on mainPage.",
  "teardown_prompt": "Delete 'BAT Periodic Weekly'."
}
```

**Expected**: The write reports `success=true`. The trigger paragraph names Monday and Friday and the 8:00 AM start. `hub_get_rule_health` reports `ok=true` with no broken markers. The read's settings show the day list (`selectDoWC1`, a multi-select stored as a list) and the start time (`startingWC1`).

**Failure modes**: The paragraph renders "?" or "null" or omits the day list (the days were sent as a single value).

---

### T626 — Monthly periodic trigger on a day number

```json
{
  "setup_prompt": "the Write master is enabled. Create RM rule 'BAT Periodic Monthly'.",
  "test_prompt": "Add a periodic-schedule trigger to 'BAT Periodic Monthly' that fires on day 15 of every 2 months, starting at 09:30. Inspect the trigger paragraph on mainPage.",
  "teardown_prompt": "Delete 'BAT Periodic Monthly'."
}
```

**Expected**: The write reports `success=true`. The trigger paragraph reflects day 15 of every 2 months, starting 9:30 AM. `hub_get_rule_health` reports `ok=true` with no broken markers. The by-day mode uses `dayMC1`, `everyNMC1` and `startingMC1`; the nth-weekday mode is covered by T630.

**Failure modes**: The paragraph renders "?" or "null" (one of the numeric inputs did not land).

---

### T627 — Yearly periodic trigger on the Nth weekday of a month

```json
{
  "setup_prompt": "the Write master is enabled. Create RM rule 'BAT Periodic Yearly'.",
  "test_prompt": "Add a periodic-schedule trigger to 'BAT Periodic Yearly' that fires yearly on the First Monday of December at 08:00. Inspect the trigger paragraph on mainPage.",
  "teardown_prompt": "Delete 'BAT Periodic Yearly'."
}
```

**Expected**: The write reports `success=true`. The trigger paragraph renders "On the First Monday of December at 8:00 AM". `hub_get_rule_health` reports `ok=true` with no broken markers. RM's Yearly mode is always nth-weekday: the read's settings show the month in `yearlyMonthCX1` (the visible X-suffixed input; `yearlyMonthC1` alone never completes), the week in `weeklyYC1`, one weekday in `dailyYC1`, and the time in `startingYC1`.

**Failure modes**: The paragraph renders "?" or "null" or omits the month or day.

---

### T628 — Periodic trigger from a cron expression

```json
{
  "setup_prompt": "the Write master is enabled. Create RM rule 'BAT Periodic Cron'.",
  "test_prompt": "Add a periodic-schedule trigger to 'BAT Periodic Cron' driven by the cron expression '0 0 12 * * ?'. Inspect the trigger paragraph on mainPage.",
  "teardown_prompt": "Delete 'BAT Periodic Cron'."
}
```

**Expected**: The write reports `success=true`. The trigger paragraph renders the cron expression (not "?" or "null"). `hub_get_rule_health` reports `ok=true` with no broken markers. The read's settings show the expression in `cronStr1`, the input RM renders for cron.

**Failure modes**: The paragraph renders "?" or "null" (the expression was stored under a name RM does not render, such as `cronString1`).

---

### T629 — Periodic trigger at an interval RM does not offer

```json
{
  "setup_prompt": "the Write master is enabled. Create RM rule 'BAT Periodic Enum'.",
  "test_prompt": "Add a periodic-schedule trigger to 'BAT Periodic Enum' that fires every 7 minutes. Report what happened.",
  "teardown_prompt": "Delete 'BAT Periodic Enum'."
}
```

**Expected**: RM offers only the minute counts 1, 2, 3, 4, 5, 6, 10, 12, 15, 20 and 30, so a 7-minute trigger cannot be built. The agent learns this from the native options or from the tool refusing the value, and reports it. No trigger is committed: a read shows the rule without triggers. The agent may offer an interval RM does offer, or a cron expression, without applying one unasked.

**Failure modes**: A trigger that renders "null" or "?". The agent claims a 7-minute trigger was created. The agent silently substitutes another interval.

---

### T630 — Monthly periodic trigger on the Nth weekday

```json
{
  "setup_prompt": "the Write master is enabled. Create RM rule 'BAT Periodic Monthly Nth'.",
  "test_prompt": "Add a periodic-schedule trigger to 'BAT Periodic Monthly Nth' that fires on the Second Monday of every month at 08:00. Inspect the trigger paragraph on mainPage.",
  "teardown_prompt": "Delete 'BAT Periodic Monthly Nth'."
}
```

**Expected**: The write reports `success=true`. The trigger paragraph renders "On the Second Monday of every month at 8:00 AM". `hub_get_rule_health` reports `ok=true` with no broken markers. The nth-weekday mode hides the by-day inputs; the read's settings show `weeklyMC1`, one weekday in `dailyMC1`, the cadence in `everyNMCX1` (not the by-day `everyNMC1`) and `startingMC1`.

**Failure modes**: The paragraph renders "?" or "null". By-day inputs (`dayMC1` / `everyNMC1`) are set alongside the nth-weekday ones, mixing the two modes.

---

### T632 — two Periodic Schedule triggers in one rule: no sub-page collision

```json
{
  "setup_prompt": "the Write master is enabled. Create RM rule 'BAT Two Periodic'.",
  "test_prompt": "Add TWO periodic-schedule triggers to 'BAT Two Periodic': first one that fires every 5 minutes, then a second that fires daily at 07:00. Inspect both trigger paragraphs on mainPage.",
  "teardown_prompt": "Delete 'BAT Two Periodic'."
}
```

**Expected**: Both writes report `success=true`. mainPage renders BOTH periodic rows intact -- e.g. "Every 5 minutes" and "Every day at 7:00 AM" -- with neither rendering "null". `hub_get_rule_health` reports `ok=true` with no broken markers. RM gives the second trigger its own periodic sub-page (`periodic2`) with suffix-2 inputs (`whichPeriod2`, `everyNDoMC2`, `everyNDC2`, `startingDC2`), so trigger 1's suffix-1 inputs stay untouched.

**Failure modes**: Trigger 1 renders "null" while trigger 2's frequency wins (the second trigger was written into suffix-1 inputs). Both rows render the same frequency.

---

### T633 — Create a rule with its first trigger and action in one call

```json
{
  "setup_prompt": "The Write master is enabled. Create a virtual switch named 'BAT Bundle Switch' via hub_manage_virtual_device and note its device ID.",
  "test_prompt": "In ONE single call, create a new Rule Machine rule named 'BAT Bundle Create' complete with its first trigger ('BAT Bundle Switch' turns on) AND its first action (turn 'BAT Bundle Switch' off) bundled into that same call. Do NOT create the rule first and edit it with a second call. Report the new rule's ID and confirm the trigger and action are both on it.",
  "teardown_prompt": "Delete the 'BAT Bundle Create' rule. Delete the virtual switch 'BAT Bundle Switch'."
}
```

**Expected**: AI calls `hub_manage_rule_machine(tool=hub_set_rule)` ONCE with `operation="create"`, `args={parent_id:<Rule Machine app id>, name:'BAT Bundle Create', changes:[<Switch on trigger>, <switch off action>]}` and `confirm=true`. The response reports `success=true`, `broken=false` and the new `rule_id`, and its document lists one trigger and one action. mainPage renders the Switch on trigger and the "Off: BAT Bundle Switch" action. `hub_get_rule_health` reports `ok=true`.

**Failure modes**: AI splits into a create call and a separate update call (acceptable result, but a Partial: the single call was the point). Only an empty named rule is created. The response omits the new `rule_id` (needed for teardown). mainPage renders only the trigger OR only the action.

---

### T634 — Custom Attribute trigger on an attribute the hub treats as an enum

```json
{
  "setup_prompt": "Hub Admin Write and Built-in App Tools are enabled. Identify a device whose attribute name the hub treats as an ENUM (e.g. a switch's 'switch' attribute -- use hub_list_virtual_devices to find a virtual switch). Create RM rule 'BAT CustomEnumAttr'.",
  "test_prompt": "Add a trigger to 'BAT CustomEnumAttr' using RM's 'Custom Attribute' capability (not the native Switch capability) on device <deviceId>'s 'switch' attribute, firing when it equals 'on'. Then read the rule back and report how the trigger renders.",
  "teardown_prompt": "Delete 'BAT CustomEnumAttr'."
}
```

**Expected**: The write reports `success=true`. The trigger row renders correctly on mainPage ("switch is on") and `hub_get_rule_health` reports `ok=true`. Because the hub recognizes `switch` as an enum attribute, choosing it reveals the value picker `tstate<N>` and hides the free comparator `ReltDev<N>`; the value `on` lands in `tstate<N>`.

**Failure modes**: The agent writes a comparator input the page does not render and reports success. The trigger renders "null". Contrast control: a free-valued Custom Attribute (e.g. a custom driver's `fixtureMode`) does render the comparator input (T637).

### T635 — Conditional trigger gated by a Custom Attribute enum condition

```json
{
  "setup_prompt": "Hub Admin Write and Built-in App Tools are enabled. Identify a device whose attribute name the hub treats as an ENUM (e.g. a switch's 'switch' attribute -- use hub_list_virtual_devices to find a virtual switch). Create RM rule 'BAT CustomEnumCond'.",
  "test_prompt": "Add a conditional trigger to 'BAT CustomEnumCond': trigger on device <deviceId>'s switch turning on, gated by a 'Custom Attribute' condition on that same device's 'switch' attribute equalling 'on'. Then read the rule back and report how the trigger and its condition render.",
  "teardown_prompt": "Delete 'BAT CustomEnumCond'."
}
```

**Expected**: The write reports `success=true`. The trigger renders with its gating condition on mainPage and `hub_get_rule_health` reports `ok=true`. The condition page routes the enum attribute the same way as the trigger row: it reveals the value picker `state_<N>` and hides the comparator `RelrDev_<N>`, and the value `on` lands in `state_<N>`.

**Failure modes**: The agent writes the hidden comparator and reports success. Contrast control: a free-valued Custom Attribute condition does render `RelrDev_<N>`.

### T642 — Custom Attribute trigger on any change of an enum attribute

```json
{
  "setup_prompt": "Hub Admin Write and Built-in App Tools are enabled. Identify a device whose attribute name the hub treats as an ENUM (e.g. a virtual switch's 'switch' attribute). Create RM rule 'BAT CustomEnumChanged'.",
  "test_prompt": "Add a trigger to 'BAT CustomEnumChanged' using RM's 'Custom Attribute' capability on the switch's 'switch' attribute, firing on ANY change of the attribute. Then read the rule back and report how the trigger renders on mainPage.",
  "teardown_prompt": "Delete 'BAT CustomEnumChanged'."
}
```

**Expected**: The write reports `success=true` and the trigger renders as a state-change trigger on mainPage (e.g. "switch changed"). On the trigger row the enum value picker (`tstate<N>`) offers a change option, so the agent stores that option's exact token there; the hidden comparator `ReltDev<N>` is not written. (The Required Expression surface offers no change option; see T643.)

**Failure modes**: The agent reports the change trigger as impossible although the picker offers it. The picker value stored as a stringified map (`[value:*changed*, text:changed]`) instead of the clean token. The wrong token stored when a picker offers several change options (e.g. `*became false*` for a `became true` request). Contrast control: `*changed*` on a FREE-valued attribute (e.g. a custom driver's `fixtureMode`) goes into `ReltDev<N>` directly.

### T643 — Required Expression "any change" condition on an enum attribute is refused, not dropped

```json
{
  "setup_prompt": "Hub Admin Write and Built-in App Tools are enabled. Identify a device whose attribute name the hub treats as an ENUM whose Required-Expression value picker offers ONLY discrete states (e.g. on/off) and NO change option. Create RM rule 'BAT WalkEnumChanged'.",
  "test_prompt": "Add a Required Expression to 'BAT WalkEnumChanged' using RM's 'Custom Attribute' capability on the switch's 'switch' attribute with the any-change comparator (no specific value). Report what happened, then read the rule back.",
  "teardown_prompt": "Delete 'BAT WalkEnumChanged'."
}
```

**Expected**: The agent does NOT report a clean success. A condition tests a state at one moment, and the condition page offers this enum attribute only its discrete values (`state_<N>`) with no change option and no comparator input. The write is refused, and a read shows no Required Expression and no leftover condition row. The agent explains why and suggests a trigger on the attribute changing, or a condition on a specific value.

**Failure modes**: A success that leaves a condition without its comparator. A leftover incomplete or broken condition row. The agent claims the expression was added. Contrast control: the same `*changed*` on a FREE-valued attribute renders `RelrDev_<N>` and the comparator is written normally.

### T636 — Required Expression with a Custom Attribute enum condition

```json
{
  "setup_prompt": "Hub Admin Write and Built-in App Tools are enabled. Identify a device whose attribute name the hub treats as an ENUM (e.g. a switch's 'switch' attribute -- use hub_list_virtual_devices to find a virtual switch). Create RM rule 'BAT WalkEnumAttr'.",
  "test_prompt": "Add a Required Expression to 'BAT WalkEnumAttr' using RM's 'Custom Attribute' capability on device <deviceId>'s 'switch' attribute equalling 'on'. Then read the rule back and report how the Required Expression renders.",
  "teardown_prompt": "Delete 'BAT WalkEnumAttr'."
}
```

**Expected**: The write reports `success=true`. The expression renders on mainPage and `hub_get_rule_health` reports `ok=true`. After the attribute (`rCustomAttr_<N>`) is chosen, the condition page shows the value picker `state_<N>` and never the comparator `RelrDev_<N>`; the value lands in `state_<N>`.

**Failure modes**: A hard error on an attribute the hub legitimately treats as an enum. The agent writes the hidden comparator. A broken marker after a clean build. Contrast control: a free-valued Custom Attribute condition renders `RelrDev_<N>` and the comparator is written normally.

### T637 — Custom Attribute trigger on a free-valued attribute

```json
{
  "setup_prompt": "Hub Admin Write and Built-in App Tools are enabled. Create RM rule 'BAT CustomFetchFallback'.",
  "test_prompt": "Add a trigger to 'BAT CustomFetchFallback' on a FREE-valued Custom Attribute (e.g. a custom driver's 'fixtureMode' on device <deviceId>), firing when it equals 'bright'. Then read the rule back and report how the trigger renders.",
  "teardown_prompt": "Delete 'BAT CustomFetchFallback'."
}
```

**Expected**: The write reports `success=true`. For a free-valued attribute RM renders the comparator `ReltDev<N>` and a value input, and the read's settings show both set. The trigger renders as fixtureMode equal to bright and `hub_get_rule_health` reports `ok=true`.

**Failure modes**: The comparator or the value is dropped. The trigger renders "null".

### T638 — IF action with a Custom Attribute enum condition

```json
{
  "setup_prompt": "Hub Admin Write and Built-in App Tools are enabled. Identify a device whose attribute name the hub treats as an ENUM (e.g. a switch's 'switch' attribute -- use hub_list_virtual_devices to find a virtual switch). Create RM rule 'BAT WalkEnumAction'.",
  "test_prompt": "Add an IF/THEN action to 'BAT WalkEnumAction' whose condition uses RM's 'Custom Attribute' capability on device <deviceId>'s 'switch' attribute equalling 'on'. Then read the rule back and report how the IF action renders.",
  "teardown_prompt": "Delete 'BAT WalkEnumAction'."
}
```

**Expected**: The write reports `success=true`. The IF action renders its condition on mainPage. The condition page routes the enum attribute as in T636: the value lands in `state_<N>` and the comparator `RelrDev_<N>` is never shown. The tool refuses an IF left open, so the agent adds END-IF in the same edit; `hub_get_rule_health` then reports `ok=true`.

**Failure modes**: A hard error on an attribute the hub legitimately treats as an enum. The agent writes the hidden comparator. Contrast control: a free-valued Custom Attribute condition renders `RelrDev_<N>` on the action page too.

---

### T639 — Create a rule that has a Required Expression from the start

```json
{
  "setup_prompt": "the Write master is enabled. Identify a virtual switch on the hub (use hub_list_virtual_devices)."
,
  "test_prompt": "Create a new RM rule named 'BAT CreateRE' that has a Required Expression requiring switch <switchId> to be on. Then read the rule back and report its ID and how the Required Expression renders.",
  "teardown_prompt": "Delete 'BAT CreateRE'."
}
```

**Expected**: The agent creates the rule with `hub_set_rule(operation="create")` and adds the Switch condition and the Required Expression, either in the create's changes or in a following update that names the condition's native ID. The rule's mainPage renders the Required Expression (the switch is on), not the "Define Required Expression" placeholder, and `hub_get_rule_health` reports `ok=true`. Exactly one rule has that name.

**Failure modes**: The rule exists without the Required Expression while the agent reports it added. The placeholder remains. A retry after a failed write created a second rule with the same name.

---

### T649 — Change an existing Required Expression in place

```json
{
  "setup_prompt": "the Write master is enabled. Note the hub's modes via hub_list_modes -- pick TWO distinct valid mode names (call them ModeA and ModeB). Create RM rule 'BAT Replace RE Test'. Add a Required Expression to it whose only condition is: the hub mode is <ModeA>. Confirm the mainPage paragraph renders 'Mode is <ModeA>' (not the 'Define Required Expression' placeholder).",
  "test_prompt": "Replace the Required Expression on 'BAT Replace RE Test' in place, so its only condition becomes: the hub mode is <ModeB>. Then read the rule's rendered config back and check its health.",
  "teardown_prompt": "Delete 'BAT Replace RE Test'."
}
```

**Expected**: The agent edits the existing Mode condition by its native ID, or adds a Mode condition for ModeB and points the Required Expression at it. The write reports `success=true`. The Required Expression paragraph on mainPage now renders 'Mode is <ModeB>', NOT 'Mode is <ModeA>' and NOT the bare 'Define Required Expression' placeholder. `hub_get_rule_health` reports no broken markers. The rule ID is unchanged: no clone, no new rule. Manage Conditions may still list 'Mode is <ModeA>' as unused, as after an edit in the RM UI; it is not part of the expression.

**Failure modes**: The paragraph still shows the OLD condition. The paragraph shows BOTH conditions or a "Broken Condition" marker. The agent reports success while the read shows otherwise.

### T651 — A Required Expression change that cannot be built leaves the original in place (no data loss)

```json
{
  "setup_prompt": "the Write master is enabled and a hub backup exists within 24h. Note two valid mode names via hub_list_modes (ModeA, ModeB). Note a valid switch device ID. Create RM rule 'BAT Replace Restore Test' and add a Required Expression whose only condition is: the hub mode is <ModeA>. Confirm the mainPage paragraph renders 'Mode is <ModeA>'.",
  "test_prompt": "Replace the Required Expression on 'BAT Replace Restore Test' with a new condition that will FAIL TO BAKE: switch <the valid switch id> in the state 'definitely_not_a_valid_state' (a state value the Switch capability does not offer). Report what the tool said, then read the rule's rendered config to see the mainPage paragraph.",
  "teardown_prompt": "Delete 'BAT Replace Restore Test'."
}
```

**Expected**: The write reports `success=false` with an error naming the rejected value: a Switch condition offers only on and off, so the condition is refused before the expression changes. `hub_get_app_config` shows the mainPage paragraph STILL rendering 'Mode is <ModeA>', `hub_get_rule_health` reports no broken markers, and Manage Conditions holds no leftover broken condition. The agent reports the refusal and that the original expression is intact.

**Failure modes**: The mainPage shows the bare 'Define Required Expression' placeholder, or health reports the expression enabled but not committed (the rule was left ungated). A success reported despite the failure. A `**Broken Condition**` token in the expression, or a broken condition left under Manage Conditions.

---

### T652 — A failed Required Expression change keeps the earlier changes of the same edit

```json
{
  "setup_prompt": "the Write master is enabled and a hub backup exists within 24h. Note a valid switch device ID and two valid mode names (ModeA, ModeB) via hub_list_modes. Create RM rule 'BAT Patch Replace Scope' and add a Required Expression whose only condition is: the hub mode is <ModeA>. Confirm the mainPage paragraph renders 'Mode is <ModeA>'.",
  "test_prompt": "In ONE edit of 'BAT Patch Replace Scope', apply two changes in order: (1) add an action turning switch <switch id> on, then (2) change the Required Expression to a condition that will FAIL TO BAKE — switch <switch id> in the bogus state 'definitely_not_a_valid_state'. Report what the tool said about each change, then read the rule's rendered config back.",
  "teardown_prompt": "Delete 'BAT Patch Replace Scope'."
}
```

**Expected**: One `hub_set_rule(operation="update")` call carries both changes. It reports `success=false` at the failing change, marks the edit as partial, and lists the changes that did not run; its results show the switch-on action written. `hub_get_app_config` shows the switch-on action, and the Required Expression paragraph still renders 'Mode is <ModeA>'. The agent reports that the first change landed and the second did not, and does not resubmit the action.

**Failure modes**: The action is GONE after the edit (the failure rolled back more than its own change). The Required Expression paragraph shows the bare placeholder, or health reports the expression enabled but not committed. The agent resubmits the whole edit and duplicates the action.

---

### T653 — Rule-local variable lifecycle: create, list, use, remove

```json
{
  "setup_prompt": "the Write master is enabled. Create an RM rule called 'BAT LocalVar Test'.",
  "test_prompt": "On 'BAT LocalVar Test': (1) Add a rule-local variable named 'batLocal' (Number type, initial value 0). (2) List the rule's local variables and confirm 'batLocal' appears. (3) Add an action that sets the LOCAL variable 'batLocal' to 7. (4) Remove the action you just added, then remove the local variable 'batLocal'. (5) List the rule's local variables again and confirm 'batLocal' is gone. Then check the rule's health and confirm no broken markers.",
  "teardown_prompt": "Delete the 'BAT LocalVar Test' rule."
}
```

**Expected**: The local is created with a `variable` change (`operation="create"`, type integer, value 0). `hub_set_rule(operation="read", args={sections:["variables"]})` or `hub_list_rule_local_variables` lists `batLocal` with type integer. The Set Variable action targeting `batLocal` renders as setting it to 7; the agent takes its native action ID from the write's returned document. Deleting that action, then the local (`variable` with `operation="delete"`), both succeed. The final list no longer contains `batLocal`, and `hub_get_rule_health` reports no broken markers.

**Failure modes**: The local is missing from the read. The action targets a hub variable instead of the local. The list still shows `batLocal` after removal. The action is removed BEFORE the local so the rule stays healthy -- not because RM blocks deleting a referenced local (it does not; see T655).

---

### T655 — Removing a still-referenced local variable breaks the rule, and the result says so

```json
{
  "setup_prompt": "the Write master is enabled. Create an RM rule called 'BAT LocalVar Broken Test'; add a NUMERIC local variable 'refLocal' (type Number, value 0) to it, then add a Set Variable action that sets 'refLocal' to 9. Leave the referencing action in place.",
  "test_prompt": "On 'BAT LocalVar Broken Test': remove the rule-local variable 'refLocal' WITHOUT first removing the action that references it. Report what the tool said and whether the rule is still healthy. Then list the rule's local variables and confirm 'refLocal' is gone.",
  "teardown_prompt": "Delete the 'BAT LocalVar Broken Test' rule."
}
```

**Expected**: RM does NOT refuse the delete. The local is removed and the referencing action is left broken, so the update reports `success=false` with an error, says the change may have been written, and returns `backup.backupKey` for `hub_restore_backup`. A read no longer lists `refLocal`, and `hub_get_rule_health` reports `ok=false` with a broken marker. The agent tells the user the local is gone and the rule is broken, and offers restoring the pre-edit backup or removing the reference.

**Failure modes**: A clean success reported while the rule is broken. The agent claims the local was kept. No recovery path is offered.

---

### T654 — Rule-local variables are separate from hub variables

```json
{
  "setup_prompt": "the Write master is enabled. Create a hub connector variable 'batShared' = 0 and a numeric hub connector variable 'batOperand' = 5 via hub_manage_variables. Create an RM rule called 'BAT LocalVar Namespace Test'; add a NUMERIC local variable 'batNum' (type Number, value 0) to it, but do NOT add a local named 'batShared'.",
  "test_prompt": "On 'BAT LocalVar Namespace Test': (1) Change the value of the rule's own local variable 'batShared' to 1 directly, without adding an action (the rule has no local by that name; only a hub variable called 'batShared' exists). (2) Then add an action that sets the local variable 'batNum' via variable math to the NEGATED value of the hub variable 'batOperand'.",
  "teardown_prompt": "Delete the 'BAT LocalVar Namespace Test' rule. Delete the hub variables 'batShared' and 'batOperand'."
}
```

**Expected**: (1) The local-variable change is refused because the rule has no local named `batShared`. Nothing is written, no local is created, and the hub variable `batShared` stays 0. The agent reports that instead of editing the hub variable. (2) The action is accepted: RM's math operand picker lists hub variables as well as locals, so a hub-variable operand into a numeric local target is valid. The action renders as setting `batNum` to the negated `batOperand`, and `hub_get_rule_health` reports no broken markers.

**Failure modes**: The hub variable `batShared` is changed. A local `batShared` is created unasked. The math action is refused because its operand is a hub variable. The picker's section-header entries (` --LOCAL VARIABLES--` / ` --HUB VARIABLES--`) are stored as the target.

---

### T644 — hub_get_app_config summary mode: thin identity read without the config-page render

```json
{
  "setup_prompt": "The Read master is enabled. Pick any installed app id from hub_list_apps (scope='instances') — e.g. the MCP Rule Server itself.",
  "test_prompt": "Fetch just a thin identity summary of that app — id, name/label, type, disabled — WITHOUT the rendered config page (no configPage/sections in the result). Then fetch the app's config again in full mode and confirm the rendered page is still returned.",
  "teardown_prompt": "Nothing to clean up (read-only)."
}
```

**Expected**: summary mode returns `success=true` with the thin identity record from `/installedapp/json/<id>` (id/name/type/disabled/user) and no `configPage`; full mode is unchanged. The AI reaches the tool via `hub_read_apps_code` (or `hub_manage_native_rules_and_apps`).

**Failure modes**: summary mode returning the full rendered page (mode ignored); missing identity fields; an error on a valid installed-app id; the AI calling `hub_list_apps` and stopping there instead of exercising the summary read.

---

### T645 — hub_call_device_swap: move every referencing app onto replacement hardware (built-in Swap Device)

```json
{
  "setup_prompt": "The Write master is enabled and a hub backup exists (<24h). Create two virtual switches through the HUB UI (Devices > Add Device > Virtual, driver 'Virtual Switch'): 'BAT Swap Source' and 'BAT Swap Target' — or pick two existing compatible FREE-STANDING test devices. Do NOT create them via hub_manage_virtual_device: MCP-created virtual devices are child devices of the MCP app, and the hub's Swap Device tool excludes app-owned child/component devices from BOTH its pickers, so the scenario would fail with the eligibility error. Note both device IDs and network ids. Then create RM rule 'BAT Swap Rule' with hub_set_rule(operation:'create') whose only trigger is 'BAT Swap Source' turning on.",
  "test_prompt": "I'm replacing 'BAT Swap Source' with 'BAT Swap Target' everywhere it is used. First preview which apps currently reference 'BAT Swap Source' — 'BAT Swap Rule' must be listed. Then swap it in one operation, confirming the change. Report what the result says happened, then check both devices and the rule to confirm the rule now runs on the replacement.",
  "teardown_prompt": "Delete the rule 'BAT Swap Rule' with hub_delete_native_app. Delete both UI-created virtual switches with hub_delete_device (confirm=true) — hub_manage_virtual_device only deletes MCP child devices and cannot remove them."
}
```

**Expected**: AI calls `hub_manage_devices(tool=hub_call_device_swap)` with `from_device_id`/`to_device_id`/`confirm=true` after previewing the blast radius with `hub_list_device_dependents`. The hub exchanges the two devices' identities: the response is `success=true` with `swapped={from, to}`, `identityExchanged=true`, `fromDeviceDependents>=1`, `toDeviceDependents=0`, and `fromDevice`/`toDevice` showing the post-swap labels. Afterwards the Source id carries the label 'BAT Swap Target' and the Target's former network id; the Target id carries 'BAT Swap Source' and the Source's former network id. 'BAT Swap Rule' still references the Source id (its trigger now reads 'BAT Swap Target'), so the rule runs on the replacement. No orphaned "Swap Device" instance remains in the hub's Apps list.

**Failure modes**: the tool refuses without `confirm` or a recent backup (expected gate — but the AI must then create the backup, not bypass). The eligibility error `"The hub's Swap Device tool does not offer device <id> as swappable."` (with `oldDevOptionCount`) — expected and correct if a fixture was created via `hub_manage_virtual_device` (child device, always ineligible; this is exactly how `tests/e2e_test.py` pins the error path) but a setup failure for THIS scenario. An incompatible-target error even though both devices are free-standing virtual switches of the same driver. `success=false` while the two devices DID trade labels and network ids (the verification misread the swap). The AI swapping a second time "to retry" (that swaps the devices back). A leftover transient Swap Device instance in the Apps list. Swapping via manual rule edits (`hub_set_rule`) instead of `hub_call_device_swap` is a routing failure for this scenario.

---

### T703 — hub_call_device_replace: re-point a device onto replacement hardware, preserving its id

```json
{
  "setup_prompt": "Make sure the Write master is on and a hub backup exists (<24h). Through the Hubitat UI (Devices > Add Device > Virtual, driver 'Virtual Switch') — NOT the MCP virtual-device tool, since MCP child devices are ineligible — add a free-standing device named 'BAT Replace Keep' and pair a compatible replacement named 'BAT Replace Donor' (real paired hardware of the same driver; two UI virtual switches only exercise the refusal path below), and note both device IDs. Then set up a Rule Machine rule named 'BAT Replace Rule' that does something when 'BAT Replace Keep' turns on, so a real automation references that device.",
  "test_prompt": "The device 'BAT Replace Keep' has failed and you've paired a compatible replacement, 'BAT Replace Donor', to the hub. Replace the failed device's hardware with the new unit while keeping the original device's identity and all of its automations intact — the original device should keep working as the same device, with 'BAT Replace Rule' still attached to it afterward. Check what the hub considers a compatible replacement before you commit, then confirm afterward that the original device still exists and its rule still references it.",
  "teardown_prompt": "Remove the 'BAT Replace Rule' rule and delete the surviving 'BAT Replace Keep' device (now running on the replacement's hardware). The donor device is consumed by the operation; delete it too if it still appears."
}
```

**Expected** (real paired hardware): AI calls `hub_call_device_replace(list_options=true)` first to read the compatible candidates, then `hub_call_device_replace` with `old_device_id`/`new_device_id`/`confirm=true`. The response is `success=true` with `replaced={oldDeviceId, newDeviceId}` and `preservedDeviceId=<old id>`. The KEY invariant: the OLD device id survives and keeps all its app/rule references — `hub_list_device_dependents` on the old id still lists 'BAT Replace Rule' (replace re-points hardware and consumes the donor; swap instead keeps both devices and exchanges their identities -- neither moves references).

**Expected** (two UI virtual switches): `list_options` lists the donor, and the confirmed replace returns `success=false` with the hub's `Failed to replace device` (its answer when its own device swap fails); both devices and the rule's reference are unchanged. On the hubs tested, the hub refuses every replace between two virtual switches, so the success path needs real paired hardware.

**Failure modes**: the tool refuses without `confirm` or a recent backup (expected gate — create the backup, don't bypass). `list_options` returns an empty list even though a compatible donor exists (the hub did not offer it — usually a fixture is an MCP child device or not capability-compatible; this scenario requires free-standing same-driver devices). A structured `success=false` naming an incompatible `new_device_id` (the donor was not in the hub's compatible set — re-read `list_options`). `preservedDeviceId` is the NEW id instead of the old one, or the rule's dependents move to the donor (no device tool moves references — a wire-format regression). Routing the request to `hub_call_device_swap` instead of `hub_call_device_replace` (swap keeps both devices and exchanges their identities; replace re-points the old id onto the new hardware — different end states).

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
  "setup_prompt": "The Read master is enabled. Pick an installed app that has emitted app events (its Events tab in the hub UI lists some); Rule Machine rules and most apps emit none, so an empty list for them is not a failure.",
  "test_prompt": "Show me the recent events emitted by that app (app <that app's id> — the app itself, not one of its devices), capping the number of rows returned. Inspect the result: source must be 'app', the events list rows carry {name, value, description, date}, and the row cap is respected. Then ask for events for BOTH that app AND a specific device in the same single request and confirm it is rejected as invalid.",
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
  "test_prompt": "Fetch the last hour of events for the BAT switch and note the most recent event's `date`. Now toggle the switch again (one more command). Then fetch the same device's events again asking only for events SINCE that recorded date (use it as a bookmark). Confirm the response returns ONLY the events that happened after the bookmark (not the pre-bookmark ones), that `sinceMode` is 'explicit', that the bookmark is echoed back, and that no hours-window field is present. Finally repeat once with the bookmark set to a clearly future timestamp and confirm an empty events list (count 0, not an error).",
  "teardown_prompt": "Delete the BAT virtual switch (hub_manage_virtual_device action='delete')."
}
```

**Expected**: with `since` supplied the result routes to history mode, `sinceMode='explicit'`, `since` is echoed, `hoursBack` is omitted, `sinceTimestamp` equals the supplied bookmark, and only post-bookmark events are returned. A future `since` yields `count: 0` with an empty list (valid, not an error). A returned `date` fed straight back as `since` parses cleanly (round-trip). An unparseable `since` would be rejected with -32602.

**Failure modes**: `since` ignored and recent-N or full-hoursBack events returned; `hoursBack` still echoed as if it bounded the window; future `since` throwing instead of returning empty; a returned `date` failing to parse when fed back as `since` (format drift); `sinceMode` missing.

### T657 — Switch trigger on any change goes into the value picker (not the turns-null orphan)

```json
{
  "setup_prompt": "Hub Admin Write and Built-in App Tools are enabled. Create a BAT virtual switch (hub_manage_virtual_device, deviceType 'Virtual Switch'). Create RM rule 'BAT SwitchChanged'.",
  "test_prompt": "Add a trigger to 'BAT SwitchChanged' using RM's 'Switch' capability on the BAT switch, firing on ANY change of the switch (no specific state). Then read the rule back and report how the trigger renders on mainPage.",
  "teardown_prompt": "Delete 'BAT SwitchChanged' and the BAT virtual switch."
}
```

**Expected**: The write reports `success=true` and mainPage renders "switch changed" -- NOT the broken "turns null" orphan. A device-state capability (Switch, Motion, Contact, Lock, ...) has no comparator input: the trigger page shows the value picker `tstate<N>` with a change option, and the change token goes there. A numeric capability (e.g. Temperature) does render the comparator `ReltDev<N>`, which takes `*changed*` instead.

**Failure modes**: The trigger renders "turns null" or fires on every event (the change token went into an input the page does not show). The agent reports a change trigger as impossible although the picker offers it. A numeric capability's change routed into a value picker.

### T659 — A state-change comparator is refused as a condition

```json
{
  "setup_prompt": "Hub Admin Write and Built-in App Tools are enabled. Create a BAT virtual switch (hub_manage_virtual_device, deviceType 'Virtual Switch'). Create RM rule 'BAT CondReject'.",
  "test_prompt": "Add a Required Expression condition to 'BAT CondReject' that the BAT switch 'changed' (any change, no specific state). Report what happened, then read the rule back and confirm whether any condition was committed.",
  "teardown_prompt": "Delete 'BAT CondReject' and the BAT virtual switch."
}
```

**Expected**: A condition tests a state at one moment, and RM's condition page offers a switch only on and off. The write is refused, nothing is committed, and a read shows no condition row and no Required Expression. The agent explains that a change belongs in a trigger, where device-state changes are supported, and offers to add one. (Days of Week conditions are covered by T336b in `BAT-rm-native-crud.md`.)

**Failure modes**: A broken or `null` condition row is committed. The change comparator is accepted and renders meaninglessly. The agent claims success.

### T664 — Conditions that cannot be built from the request are refused, not committed incomplete

```json
{
  "setup_prompt": "Hub Admin Write and Built-in App Tools are enabled. Create RM rule 'BAT CondReject2'.",
  "test_prompt": "Attempt two deliberately-wrong Required Expression conditions on 'BAT CondReject2', one at a time: (1) a 'Last Event Device' condition, and (2) a 'Lock codes' condition. For each, report what happened, then read the rule back and confirm no condition was committed.",
  "teardown_prompt": "Delete 'BAT CondReject2'."
}
```

**Expected**: Nothing is committed in either case: a read shows no condition rows and no Required Expression. (1) `Last Event Device` is not in RM's condition list. It refers to the device that fired the rule's trigger and is used in ACTIONS, not as a testable state. The write is refused and the agent says so. (2) `Lock codes` IS a real condition type, but it needs a lock device plus a specific code name that the request does not give. The agent asks for them or reports the refusal; it never commits an incomplete "on null: null" condition, which the health check would not catch.

**Failure modes**: A `*BROKEN*` Last Event Device condition. A health-ok but incomplete "on null: null" Lock codes condition. A leftover in-progress condition slot after the refusal.

### T665 — Official Python SDK v2 automatically completes a long MRTR Rule Machine write

> **Automated live-relay scenario**: `tests/sdk_conformance_test.py` runs this after the branch is deployed by `hub-e2e.yml`. It is not a conversational agent prompt: the official SDK is the independent client under test.

```json
{
  "setup_prompt": "Create one uniquely named BAT_E2E_SDK_MRTR_<random> Rule Machine rule through the official mcp==2.2.0 high-level client, using hub_set_rule operation=create with args.parent_id, args.name and args.changes; retain its returned rule_id.",
  "test_prompt": "Make one high-level Client.call_tool edit through hub_set_rule operation=update with rule_id and args.changes containing 100 real native changes. Allow the SDK's standard Streamable HTTP client to complete every state-only continuation automatically.",
  "teardown_prompt": "In a finally block, delete only the exact rule created for this run. If creation lost its response, use a bounded settle/retry to adopt at most one exact random-name match before deleting it. Refuse ambiguous matches. Never sweep existing apps or delete backup artifacts."
}
```

**Workload**: `E2E_MRTR_ACTION_COUNT` requests 6 through 99 distinct log actions, without truncation (default six). Up to 40 Wait for Events actions contain four distinct typed temperature events each; when configured logs displace wait rows, each remaining wait contains eight events. Distinct temperature conditions fill the batch to exactly 100 changes, with at least one condition. The default is 54 conditions, 40 waits and six logs; configured counts 48 and 72 produce respectively 12 conditions/40 four-event waits/48 logs and one condition/27 eight-event waits/72 logs. This exercises real compilation and persistence, with no artificial timing delays or runtime slowdown.

**Expected**: the test invokes `mcp.client.Client.call_tool()` exactly once for the measured edit, without supplying continuation state or raising the SDK's default 10-round limit. The returned object is a successful `CallToolResult` with `result_type='complete'`, every requested mutation result, and no unapplied remainder. Observer-only `httpx2` hooks retain no URL, token, state value, arguments, or body; they retain only a derived `has_request_state` boolean and safe routing/timing fields. The first measured leg has no state and every later leg carries it automatically. The trace shows at least three modern `tools/call` POST legs for the gateway (at least two continuation rounds). Successful legs are 2xx and under the 9.5-second cloud-ceiling guard; replayable gateway drops (502/503/504) may be retried but are excluded from that response-time ceiling. The aggregate logical call is over 10 seconds. The terminal payload reports at least one completed owner slice and fewer owner slices than observed continuation rounds; the positive difference proves a state-only coordination handoff while the worker was still running. The log reports leg count, continuation rounds, owner and coordination rounds, unchanged SDK limit, aggregate duration, max-leg duration, and every leg duration.

After the measured window, an independent high-level `hub_get_app_config(includeSettings=true)` call reads raw settings. Separate `hub_set_rule(operation=read)` calls page conditions, actions and typed settings through opaque cursors. Each section must retain its revision and total across contiguous pages, without missing, duplicate or reordered identities. Readback must prove every requested condition and its device/comparator/ordered threshold, exact native action order and methods, globally unique wait event IDs and every typed event picker/comparator/threshold, and exactly the requested distinct log messages in order. Raw device-map keys must match the typed device IDs. No orphan wait settings or extra log rows may remain.

**Failure modes**: using `ClientSession.call_tool()` or a project-owned state loop instead of the high-level SDK driver; requesting a task/extension or passing a custom token; fewer than two continuation rounds; aggregate time at or below 10 seconds; a successful leg at or above 9.5 seconds or an unrecoverable HTTP status; final `result_type` not `complete`; tool-level failure or partial/unapplied mutations; missing, wrong, duplicate, reordered or extra persisted rows/values; changing section revisions or inconsistent pagination; zero owner slices or owner slices greater than or equal to HTTP continuation rounds; increasing `input_required_max_rounds`; logging a credentialed URL/body; an immediate no-settle cleanup lookup after a lost create response; or cleanup touching anything except this run's random BAT fixture.

**Independent regular-E2E companion**: `test_mrtr_rule_edit_uses_standard_continuation` creates its own throwaway rule and submits the same lane-sized workload through the normal `HubitatMcpClient.call_tool()` automatic continuation path: the configured 100-change batch when `E2E_MRTR_ACTION_COUNT` is set, otherwise (the physical hub) 20 logs and 10 conditions with no Wait rows, which a physical hub finishes within the default 10 rounds. Its unchanged proof helper requires multiple continuations, logical duration over 10 seconds, answered legs below 9.5 seconds, bounded relay drops and positive owner slices fewer than continuations. It independently checks all mutation outcomes, changed-argument rejection, exact completed replay, recent-write completion and full raw/paged persisted readback. Success, readback or telemetry from either client cannot satisfy the other client's assertions. Neither workload is a speed comparison with the former authoring API.

---

### T666 — Multi-rule hub_call_rule reports each rule exactly once across continuation slices

> **Automated live scenario**: `tests/e2e_test.py` `test_call_rule_multi_id_aggregates_per_rule`. A multi-`ruleId` call is MRTR-eligible from round zero. Scope limit, stated honestly: on a single-slice call `rec.aggregate` is never written, so the aggregator's `call_rule` case does not run and the leaf's own result passes through untouched — this scenario pins the contract the client sees either way, while the cross-slice collapse itself is pinned in `MrtrContinuationSpec`, where a banked-then-retried rule can be built deterministically.

```json
{
  "setup_prompt": "Create two uniquely named BAT_E2E_ Rule Machine rules with no triggers or actions and retain both appIds.",
  "test_prompt": "Stop BOTH rules in a single hub_call_rule call by passing both ids as a list, then report the returned envelope and each rule's stopped state.",
  "teardown_prompt": "In a finally block, delete only the exact appIds created for this run. Never sweep existing rules."
}
```

**Expected**: one terminal envelope, never a `status: "in_progress"` pause. A completed batch carries no residual `remainingRuleIds`; the separate `continuation_limit` terminal DOES carry it on purpose, naming the rules it never reached, so judge that field against the status rather than treating its presence as a defect. `results[]` holds exactly one row per requested rule, every row naming its `ruleId`, with none appearing twice, and `ruleIds` echoes the requested set once each — a rule re-queued for retry across slices must be reported by its FINAL attempt, not once per attempt, and a tail slice that narrows to a single rule must still contribute that rule's row rather than reporting it only at top level. `success`, `partial` and `failedRuleIds` agree with those collapsed rows: an all-success batch reports `success: true` with `partial` falsy and no `failedRuleIds` (a mid-batch budget pause is not a partial RESULT); any failed row makes the envelope `success: false` with `failedRuleIds` matching exactly the failing rows; and `partial` is true only when SOME rules were actioned and some were not — an all-failed batch is a failure, not a partial one, matching the leaf result. Both rules then read `stopped: true` from `hub_get_rule_health`.

**Failure modes**: a rule appearing twice in `results[]`; a result row with no `ruleId`, or a rule missing from `results[]` entirely because the tail slice reported it top-level; a rule that failed early and succeeded on retry still counted in `failedRuleIds`; `partial: true` on a batch where every rule succeeded, or on one where every rule failed; `success: false` with an empty `failedRuleIds` (a failure naming no culprit); `remainingRuleIds` surviving into a COMPLETED terminal envelope (it is expected on a `continuation_limit` one); the envelope reporting success while a rule is not actually stopped; or teardown touching a rule this run did not create.

---

## Section 16: Visual Rules Builder Tests (hub_get_visual_rule / hub_set_visual_rule / hub_delete_visual_rule)

The Visual Rules Builder tools live in the `hub_manage_rule_machine` gateway (the read, `hub_get_visual_rule`, is also in `hub_read_rules`). Reads require the Read master; `hub_set_visual_rule` / `hub_delete_visual_rule` require the Write master + `confirm=true` + a hub backup within 24h. The Visual Rules Builder parent app must be installed on the hub (the list mode returns an actionable error if not).

### Safety Rules for Section 16

- Only create/edit/delete **BAT-prefixed** Visual Rules created within the same scenario — never touch existing Visual Rules
- Device-facing nodes only target BAT-created virtual devices (the switches, dimmers and sensors the scenario itself created)
- T700 and T705 are written for a hub that offers BOTH builders (platform 2.5.1 and later). On a 2.0-only hub T700 instead returns `format='graph'`, `version='2.0'` and `translatedFrom='classic'` with a graph read-back; on a 1.0-only hub T705 instead returns `success=false` + `hubNativeFormat='classic'` with the empty child cleaned up, and its graph read-back assertions do not apply. Check `hub_get_info` firmware first and grade against the branch that matches.
- A 2.5.1 hub offers BOTH builders, and the definition's shape picks which one a new rule runs: a classic node-list (`whenNodes`/`thenNodes`/`elseNodes`) creates a Visual Rule Builder 1.0 rule, the editor form or a raw graph creates a 2.0 one. The create response echoes `version`. Older firmware that can only make one of the two falls back to it: a classic definition on a 2.0-only hub is translated (`translatedFrom: 'classic'`), and an editor/graph definition on a 1.0-only hub returns `hubNativeFormat: 'classic'` and cleans up the empty child — re-issue with a classic definition in that case

### T700 — hub_set_visual_rule create + hub_get_visual_rule read-back (classic format)

```json
{
  "setup_prompt": "The Read and Write masters are enabled and a hub backup was created within the last 24 hours (call hub_create_backup if unsure). Create a virtual switch named 'BAT VRB Switch' via hub_manage_virtual_device and note its device ID.",
  "test_prompt": "Create a Visual Rules Builder rule named 'BAT VRB Create' (confirming the creation): when 'BAT VRB Switch' turns on, turn it off. Author it in the classic when/then-nodes definition format. Then read the rule back and report the appId, format, and whether the persisted definition matches what was sent.",
  "teardown_prompt": "Delete the Visual Rule 'BAT VRB Create' with hub_delete_visual_rule (confirm=true). Delete the virtual switch 'BAT VRB Switch'."
}
```

**Expected**: AI calls `hub_manage_rule_machine(tool=hub_set_visual_rule)` with NO `appId`, `name='BAT VRB Create'`, the classic `definition`, and `confirm=true`. The response returns a new `appId`, `created=true`, `format='classic'`, `version='1.0'` (the classic shape is what selected the 1.0 builder), and `verified=true` (the tool's read-back confirmed the persisted name); the echoed `definition` contains the whenNode/thenNode pair. A follow-up `hub_get_visual_rule(appId=N)` returns `success=true`, `format='classic'`, `name='BAT VRB Create'`, `rulePaused=false`, and the same `whenNodes`/`thenNodes`. The rule also appears in list mode (`hub_get_visual_rule` with no `appId` — `rules[]` contains `{appId, name:'BAT VRB Create', disabled:false}`).

**Failure modes**: the call is issued without `confirm=true` (rejected -32602 — the AI must include it after user approval, not retry blindly). `verified=false` (the save POST was sent but the read-back did not confirm — inspect with `hub_get_visual_rule`). A format-mismatch response (`success=false` + `hubNativeFormat='classic'`) — that only happens when an editor/graph definition meets a hub whose builder can create nothing but 1.0 children, so it should NOT appear for this classic definition; if it does, the AI should re-issue with a classic definition and the empty child app created during the attempt must have been cleaned up, not stranded. The AI routes to `hub_set_native_app(operation='create', args={app_type: 'visual_rule'})` instead — that path now throws with a redirect to `hub_set_visual_rule`.

### T701 — hub_set_visual_rule pause + rename without touching the definition

```json
{
  "setup_prompt": "The Read and Write masters are enabled and a recent backup exists. Create a virtual switch 'BAT VRB PauseSwitch' and a Visual Rule named 'BAT VRB Pause' via hub_set_visual_rule (classic definition: when the switch turns on, turn it off). Note the returned appId.",
  "test_prompt": "First pause the Visual Rule 'BAT VRB Pause' (confirming the change) and verify it reads back as paused. Then rename it to 'BAT VRB Renamed' and resume it in ONE call — without re-sending or touching its definition. Verify by reading it back that the name changed, it is no longer paused, and the when/then nodes are UNCHANGED from setup.",
  "teardown_prompt": "Delete the Visual Rule 'BAT VRB Renamed' with hub_delete_visual_rule (confirm=true). Delete the virtual switch 'BAT VRB PauseSwitch'."
}
```

**Expected**: the pause call returns `success=true` with `rulePaused=true` (pause state rides `GET /app/ruleBuilderPause/<id>/true` under the hood); `hub_get_visual_rule` confirms. The rename+resume call (with `appId`, `name`, `paused=false`, no `definition`) re-saves the EXISTING nodes under the new name — the read-back shows `name='BAT VRB Renamed'`, `rulePaused=false`, and the original whenNode/thenNode pair intact. No `definition` replacement happens.

**Failure modes**: the rename wipes the nodes (empty `whenNodes`/`thenNodes` after the rename — the re-save dropped the existing definition). The pause flag is silently ignored (`rulePaused` stays `false` after `paused=true`). The AI re-sends the whole definition just to rename (works, but a Partial — `name`-only is the point). A no-op call (`appId` with no `definition`/`name`/`paused`) must reject -32602 "Nothing to change", not return `success=true`.

### T706 — A Visual Rule 2.0 create that succeeds with an advisory (unknown action type)

```json
{
  "setup_prompt": "The Read and Write masters are enabled and a hub backup was created within the last 24 hours (call hub_create_backup if unsure). Create a virtual switch named 'BAT VRB2 Advisory' via hub_manage_virtual_device and note its device ID.",
  "test_prompt": "Create a Visual Rules Builder automation named 'BAT VRB2 Advisory' that turns 'BAT VRB2 Advisory' on when it turns on, and give it a SECOND action of type 'brandNewAction' on the same switch -- I know that type name is not in the reference; use it verbatim. Tell me exactly what came back: was it refused, was it created, is it running, and what did the tool warn about versus what the hub itself said.",
  "teardown_prompt": "Delete the Visual Rule 'BAT VRB2 Advisory' with hub_delete_visual_rule (confirm=true). Delete the virtual switch 'BAT VRB2 Advisory'."
}
```

**Expected**: the create is NOT refused: unknown type names are advisory on create (newer firmware may know them). The response is `success: true`, `created: true`, `version: '2.0'`, `createRoute: 'createchild'` (the versioned child-create route), with `preflightWarnings` naming `brandNewAction` (the tool's advisory) AND `validationErrors` from the hub naming the same node (the hub's verdict), `activated: false`, and the INACTIVE DRAFT note. The AI reports all three facts distinctly: created, warned by the tool, rejected by the hub, therefore not running -- it must not call this a success as an automation, and must not call it a refusal. A follow-up `hub_get_visual_rule(appId=N)` shows `activated: false`.

**Failure modes**: the AI reports the rule as running because `success: true` (the draft note and `activated: false` ignored -- automatic Fail). The AI silently drops the unknown action to make the rule pass (the prompt asked for it verbatim). The AI treats `preflightWarnings` as an error and never issues the create. Confusing the tool's advisory with the hub's rejection -- they are two different sources and the AI should say which said what.

---

### T702 — hub_delete_visual_rule type-gate refusal + verified delete with recovery definition

```json
{
  "setup_prompt": "The Read and Write masters are enabled and a recent backup exists. Create (1) a virtual switch 'BAT VRB DelSwitch', (2) a Visual Rule named 'BAT VRB Delete' via hub_set_visual_rule (classic definition over that switch), and (3) a Rule Machine rule named 'BAT VRB NotVisual' via hub_set_rule. Note both appIds.",
  "test_prompt": "First try deleting the RM rule 'BAT VRB NotVisual' AS IF it were a Visual Rule — go through the Visual Rule delete path, confirming — and report what happens. Then delete the actual Visual Rule 'BAT VRB Delete' (confirming) and report whether the delete was verified and whether the response includes the pre-delete definition.",
  "teardown_prompt": "Delete the RM rule 'BAT VRB NotVisual' with hub_delete_native_app (confirm=true). Delete the virtual switch 'BAT VRB DelSwitch'. Confirm via hub_get_visual_rule (list mode) that no rule named 'BAT VRB Delete' remains."
}
```

**Expected**: the first call does NOT delete anything: the RM rule's appId fails the VRB type-gate and returns `success=false` with the app's real type (e.g. `appType: 'Rule Machine'` / `'Rule-5.1'`) and a note redirecting to `hub_delete_native_app` — the RM rule still exists afterward (verify with `hub_list_rules`). The second call deletes the Visual Rule: `success=true`, `verified=true` (the app is confirmed gone), and `predeleteDefinition` carries the whenNodes/thenNodes so the rule could be recreated via `hub_set_visual_rule`. A follow-up `hub_get_visual_rule(appId=N)` returns `success=false` ("No installed app...") and list mode no longer contains the rule.

**Failure modes**: the type-gate is bypassed and the RM rule is force-deleted (the exact failure the gate exists to prevent — automatic Fail). `predeleteDefinition` missing from the delete response (the recovery contract broken). `verified=false` with the app still present but reported as deleted. A nonexistent-appId delete returning anything other than the structured `success=false` "No installed app with appId N" envelope.

---

### T705 — Visual Rule 2.0 editor form: OR-gated rule with then/else branches and a shared tail

```json
{
  "setup_prompt": "The Read and Write masters are enabled and a hub backup was created within the last 24 hours (call hub_create_backup if unsure). Via hub_manage_virtual_device create a virtual motion sensor named 'BAT VRB2 Motion', a virtual switch named 'BAT VRB2 Override', a virtual dimmer named 'BAT VRB2 Lamp', and a virtual switch named 'BAT VRB2 Chime'. Note every device ID.",
  "test_prompt": "Set up an automation named 'BAT VRB2 Editor' on the Visual Rules Builder, confirming the change. It runs whenever 'BAT VRB2 Motion' detects motion. It should then check two things and proceed if EITHER ONE holds: it is a weekday, or 'BAT VRB2 Override' is currently on. When either holds, set 'BAT VRB2 Lamp' to 40 percent; when neither does, turn that lamp off instead. Either way, finish by turning 'BAT VRB2 Chime' on. Then read the automation back and tell me which Visual Rules Builder version it runs, whether it is actually active, and how the trigger, the either-or check, the two branches and the shared final step are represented.",
  "teardown_prompt": "Delete the Visual Rule 'BAT VRB2 Editor' with hub_delete_visual_rule (confirm=true). Delete the virtual devices 'BAT VRB2 Motion', 'BAT VRB2 Override', 'BAT VRB2 Lamp' and 'BAT VRB2 Chime'."
}
```

**Expected**: the AI calls `hub_manage_rule_machine(tool=hub_set_visual_rule)` with NO `appId`, `name='BAT VRB2 Editor'`, `confirm=true`, and a `definition` in the editor form — one `motion` entry in `triggers`, `decisionType: 'any'` (the either-or check is the 2.0 OR decision, not two rules and not a nested condition), a `daysOfWeek` and a `switchCondition` entry in `conditions`, one `setBrightness` in `thenActions`, one `turnOff` in `elseActions`, and one `turnOn` in `commonActions`. Because the definition is editor-shaped, the hub creates a **2.0** child: the response carries `version: '2.0'`, `format: 'graph'`, `created: true`, `verified: true`, `activated: true`, and NO `translatedFrom`. The read-back (`hub_get_visual_rule(appId=N)`) returns `format: 'graph'`, an empty `validationErrors`, `activated: true`, and an `editor` block with `decisionType: 'any'`, both conditions, and one action in each of `thenActions` / `elseActions` / `commonActions` — the common tail proving a `branchMerge` was composed. List mode shows the rule with `version: '2.0'`.

**Failure modes**: the AI hand-builds a raw graph with node ids, merge nodes and edges when the editor form would do (Partial — it works, but it is the harder path the `editor` block exists to avoid). `decisionType` is left at its `all` default so the rule only runs when BOTH checks hold (the OR requirement silently lost — automatic Fail), or the either-or is split into two separate rules. A classic `whenNodes`/`thenNodes` definition is sent instead, which creates a **1.0** rule (`version: '1.0'`) that can express neither the OR decision nor the shared tail — the chime step ends up duplicated into both branches. `activated: false` with non-empty `validationErrors` (the rule was stored as an inactive draft — usually a bad device id or a misspelled event label; the AI must report it as not running, never as success). The AI routes to `hub_set_rule` (Rule Machine) instead of the Visual Rules Builder. The pre-flight refusal ("Definition failed pre-flight validation; nothing was created/saved.") is returned and the AI retries blindly instead of fixing the listed `validationErrors`.

---

### T707 — A running Visual Rule 2.0 keeps its document through a rename, and a pause reads as not running

```json
{
  "setup_prompt": "The Read and Write masters are enabled and a hub backup was created within the last 24 hours (call hub_create_backup if unsure). Via hub_manage_virtual_device create a virtual motion sensor named 'BAT VRB2 Life Motion' and a virtual switch named 'BAT VRB2 Life Lamp'. Then set up a Visual Rules Builder 2.0 automation -- the newer graph builder, not the classic 1.0 one -- named 'BAT VRB2 Lifecycle' that turns that lamp on whenever that motion sensor detects motion, and confirm it reads back as active.",
  "test_prompt": "Rename the automation 'BAT VRB2 Lifecycle' to 'BAT VRB2 Renamed' without changing anything else about it. Then read it back and tell me whether it still has the same trigger and the same action, and whether it is still running. After that, pause it and tell me exactly what came back about whether it runs and why. Finally start it again and tell me whether it is running.",
  "teardown_prompt": "Delete the Visual Rule 'BAT VRB2 Renamed' with hub_delete_visual_rule (confirm=true). Delete the virtual devices 'BAT VRB2 Life Motion' and 'BAT VRB2 Life Lamp'."
}
```

**Expected**: the rename is one `hub_set_visual_rule` call carrying `appId` + `name` and NO `definition` -- the tool re-saves the document its own read resolved, so the response is `success: true`, `verified: true`, `activated: true`, no `validationErrors`, and no `definition` echoed back. The read-back (`hub_get_visual_rule(appId=N)`) still returns `format: 'graph'`, `name: 'BAT VRB2 Renamed'`, `activated: true`, and an `editor` block with the same one motion trigger and one turn-on action the setup created. The pause returns `success: true`, `verified: true`, `rulePaused: true`, and a read-back then reports `activated: false` -- the rule is STORED and valid but PAUSED, which is not the same thing as a rule the hub failed to activate; if the AI folds the pause into a save, that response says in as many words that `activated` is false because the rule is paused and the remedy is to resume it. The resume returns `rulePaused: false` and the rule reads `activated: true` again. The AI must name the paused state as the reason the automation is not running.

**Failure modes**: the rename empties the rule (the read-back shows no trigger/action, or `activated: false` with `validationErrors` -- the re-save dropped the stored document; automatic Fail). The AI re-sends the whole definition just to rename (Partial -- `name`-only is the point). The AI reads `activated: false` after the pause as an activation failure and re-saves the definition to "retry activation" (automatic Fail -- a resume is the only fix, and the tool says so). The AI reports the paused rule as still running because `success: true`. The resume leaves `rulePaused: true` and the AI reports it as running anyway.

---

## Section 17: Dashboard Tests — Easy & legacy Hubitat® (hub_manage_dashboards / hub_read_dashboards)

Dashboard CRUD across BOTH Easy Dashboards and legacy Hubitat® Dashboards (issue #259 item #9; legacy coverage issue #326). The 6 tools live in the `hub_manage_dashboards` gateway; the two reads (`hub_list_dashboards`, `hub_get_dashboard`) are also surfaced in the pure-read `hub_read_dashboards` gateway. Reads require the Read master; create/update/clone require the Write master; delete additionally requires `confirm=true` + a hub backup within 24h. Every list entry carries a `type` (`easy` or `legacy`). Easy Dashboards are classic child apps of the Easy Dashboard Parent, driven by `GET /dashboard/*` endpoints and configured with tile toggles/theme; legacy Hubitat® Dashboards are child apps of the built-in Hubitat® Dashboard parent, whose design lives in a nested `layout` (tiles + grid + colors) edited wholesale or via granular tile ops. The list endpoint may be pinToken-gated on some hubs (an unexpectedly-empty list is the tell).

### Safety Rules for Section 17

- Only create/edit/delete **BAT-prefixed** dashboards created within the same scenario — never touch existing dashboards
- Dashboards only ever reference BAT-created virtual switches
- Deleting a dashboard does NOT delete its devices
- Legacy scenarios additionally require the built-in **Hubitat® Dashboard** parent app to be installed on the hub — a documented precondition, not a skippable gap: install the built-in app first, then run the scenario

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

### T723 — Create a legacy Hubitat® Dashboard, add a tile, read it back, then delete it

```json
{
  "setup_prompt": "The Write master is enabled and a hub backup was created within the last 24 hours (call hub_create_backup if unsure), and the built-in Hubitat® Dashboard app is installed. Create a virtual switch named 'BAT Legacy Switch' and note its device ID.",
  "test_prompt": "Create a legacy Hubitat® Dashboard called 'BAT Legacy Dash' authorized for the 'BAT Legacy Switch' device. Add a clock tile to its top-left cell, then show me the dashboard and confirm the clock tile is on its layout.",
  "teardown_prompt": "Delete the legacy Hubitat® Dashboard 'BAT Legacy Dash'. Delete the virtual switch 'BAT Legacy Switch'."
}
```

**Expected**: the AI routes to `hub_manage_dashboards(tool=hub_create_dashboard)` with `name='BAT Legacy Dash'`, `type='legacy'`, `deviceIds=[<id>]` — a legacy dashboard starts with an EMPTY layout, so no `options`/tile toggles are passed. The response returns `success=true`, `type='legacy'`, and the new dashboard `id`. The AI then calls `hub_update_dashboard(id=N, addTiles=[{template:'clock', col:1, row:1}])`; the response has `success=true`, `type='legacy'`, `tileCount>=1`, and the saved `layout` echo. A follow-up `hub_get_dashboard(id=N)` returns `type='legacy'` with a nested `layout` object whose `tiles` array contains the clock tile.

**Failure modes**: the AI passes `options` or Easy tile toggles like `showClockTile` on the legacy create (rejected -32602 — a legacy dashboard's look lives in its layout, not `options`). The built-in Hubitat® Dashboard parent app is not installed, so create returns `success=false` with an error naming the parent app (a hub-provisioning precondition, not a wire-format bug — install the app and retry). `hub_get_dashboard` called without an id (rejected -32602).

### T724 — Edit a legacy dashboard's layout granularly (add tile + set grid/color options)

```json
{
  "setup_prompt": "The Write master is enabled and a recent backup exists, and the built-in Hubitat® Dashboard app is installed. Create a virtual switch 'BAT Legacy EditSwitch' and a legacy Hubitat® Dashboard 'BAT Legacy Edit' authorized for that switch with a clock tile already on it. Note the dashboard id.",
  "test_prompt": "On the legacy dashboard 'BAT Legacy Edit', add a tile for the 'BAT Legacy EditSwitch' device next to the clock, and change the dashboard's background color to dark grey and its grid to 4 columns. Then read it back and confirm the new device tile, the background color, and the column count.",
  "teardown_prompt": "Delete the legacy Hubitat® Dashboard 'BAT Legacy Edit'. Delete the virtual switch 'BAT Legacy EditSwitch'."
}
```

**Expected**: a legacy dashboard's design is edited GRANULARLY (not wholesale), so the AI calls `hub_update_dashboard(id=N, addTiles=[{template:<switch template>, device:<id>, col:.., row:..}], setOptions={bgColor:'#333333', cols:4})` — removals → updates → adds → options all apply in ONE save. The response returns `success=true`, `type='legacy'`, an updated `tileCount`, and the saved `layout` echo. The read-back's `layout` shows both tiles, the new `bgColor`, and `cols=4`.

**Failure modes**: the AI passes `layout` (wholesale) AND granular ops (`addTiles`/`setOptions`) in the same call (rejected -32602 — pass one or the other). The device tile references a device the dashboard hasn't authorized (accepted, but the response carries a `warnings` entry that the tile will show no data until the device is authorized via `deviceIds`). The AI puts grid/color settings in `options` instead of `setOptions` (rejected -32602 — `options` is Easy-only).

### T725 — Clone a legacy Hubitat® Dashboard

```json
{
  "setup_prompt": "The Write master is enabled and a recent backup exists, and the built-in Hubitat® Dashboard app is installed. Create a virtual switch 'BAT Legacy CloneSwitch' and a legacy Hubitat® Dashboard 'BAT Legacy CloneSrc' authorized for that switch with a couple of tiles on it. Note the dashboard id.",
  "test_prompt": "Make a copy of the legacy Hubitat® Dashboard 'BAT Legacy CloneSrc', including its tiles. Then list the dashboards and confirm a legacy copy now exists alongside the original.",
  "teardown_prompt": "Delete both the original 'BAT Legacy CloneSrc' and its copy. Delete the virtual switch 'BAT Legacy CloneSwitch'."
}
```

**Expected**: the AI routes to `hub_manage_dashboards(tool=hub_clone_dashboard)` with the source `id`; for a legacy source the clone is BY VALUE — a fresh legacy dashboard with the source's layout copied in, leaving the source untouched. The response returns `success=true`, `type='legacy'`, `sourceId`, and `newId`. `hub_list_dashboards` shows both, each with `type='legacy'`, and the copy's layout carries the source's tiles.

**Failure modes**: clone called without an `id` (rejected -32602). The copy is created but its layout write fails (the response reports `success=false` WITH the `newId` and a note to re-apply the layout via `hub_update_dashboard`). The clone never appears in the list (verify with `hub_list_dashboards` — it may need a pinToken to surface).

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
10. **Corrected test count**: 159 → 172 (was undercounted in v1); action-type completeness (hub variable set, mode by name, custom command with a variable parameter, copy from a variable) adds T607/T608/T609/T610 (176 total); walker parity adds T611 (177 total); Between two times coverage adds T612 (178 total); singular deviceId normalization adds T613 (179 total); single-device conditions on a conditional trigger (T614) and an IF action (T615) add two more (181 total); a nested-condition refusal on IF actions added T616 (182 total); reveal-fallback sentinel adds T617 (183 total); compareToDevice device-relative adds T618 (184 total); Between two times sunrise/sunset adds T619 (185 total); Variable compareToVariable on the walker pages adds T620, compareToDevice missing-comparator reject adds T621, and Custom-Attribute '*changed*' cosmetic-partial filter adds T622 (188 total); periodic-frequency completeness adds T623/T624/T625/T626/T627 (the five newly-supported frequencies: Seconds/Minutes/Weekly/Monthly/Yearly -- Monthly by-day and Yearly nth-weekday) + T628 (Cron field-name fix) + T629 (count-enum validation rejection) + T630 (Monthly nth-weekday mode) + T631 (Monthly dayOfMonth/weekOfMonth mutual-exclusivity rejection) + T632 (two periodic triggers in one rule -- no sub-page collision) (198 total); the native-app tool rename adds T633 (hub_set_rule single-call create-with-bundle: new rule + trigger + action in one upsert) (199 total); Custom-Attribute enum-attribute false-positive-partial guard adds T634 (trigger row), T635 (conditional-trigger condition path), T636 (Required Expression reveal walker / STPage enum condition), and T637 (free-valued Custom Attribute comparator: value lands normally) (203 total); the action-page enum-condition parity (the 4th surface, reached via an IF action) adds T638 (204 total); create-with-Required-Expression adds T639 and a create-time refusal of edit-only arguments added T640 (206 total); the doActPage compareToDevice device-relative happy-path (the IF-action mirror of T618) adds T641 (207 total -- numbered out of sequence because T619/T620 were already taken by the Between-two-times and compareToVariable scenarios); the enum-attribute no-RHS state-change comparator splits across both live outcomes -- T642 (trigger surface ROUTES via the picker's change option) + T643 (Required Expression surface SKIPS, no change option) (209 total); the two new setVariable source modes add T647 (fromDevice / numOp="device attribute") and T648 (math / numOp="variable math", binary + unary arity) (211 total -- numbered T647/T648 because T642/T643 were taken by the enum-attribute comparator scenarios above and T644/T645/T646 by the app-config-summary / device-swap / event-history scenarios); the in-place Required Expression replacement adds T649 (happy path), a no-existing-expression refusal added T650, the failed-rebuild guard (no data loss) adds T651, and the edit-scope guard (a failed Required Expression change does not revert the earlier changes of the same edit) adds T652 (215 total); the rule-local variable lifecycle adds T653 + the namespace distinction adds T654 (217 total); the broken-after-delete contract for a still-referenced local variable (RM deletes it and leaves the rule broken; the result reports the failure) adds T655 (218 total); the hub_list_device_events since-bookmark round-trip adds T656 (219 total -- numbered T656 out of sequence to avoid colliding with the setVariable T647 above). T616, T617, T621, T631, T640, T650 and T658 were later removed with the retired authoring arguments they tested (header total 264). Note: the scenario total in the header above counts ALL scenarios including the NL (T501-T565 range), built-in-app integration (T801-T821 range), library management (T901-T909 range), and the unnumbered walker/normalization sub-scenarios. The cumulative T-numbered tally in this item (ending at 215) reflects only sequentially-numbered tests in the explicit-coverage section.
11. **PR1B read/write gateway split**: the 13-gateway flat-core layout was restructured into **19 gateways (7 `hub_read_*` pure-read + 12 `hub_manage_*` write-bearing) + 11 flat core tools** (30 on tools/list). Gateway renames: `hub_manage_rules` → `hub_manage_custom_rules`, `hub_manage_code_write` → `hub_manage_code`, `hub_manage_native_rules` → `hub_manage_native_rules_and_apps`. Removed gateways (read tools folded into `hub_read_apps_code`): `hub_manage_code_read`, `hub_manage_installed_apps`, `hub_manage_hpm`. `hub_list_installed_apps` merged into `hub_list_apps` (scope=types|instances). New pure-read gateways added: `hub_read_apps_code`, `hub_read_devices`, `hub_read_diagnostics`, `hub_read_files`, `hub_read_rooms`, `hub_read_rules`, `hub_read_variables`. Reads listed in a mixed `hub_manage_*` gateway are also surfaced in their `hub_read_*` gateway (multi-membership). Tool-behavior flips: `hub_export_custom_rule` and `hub_export_native_app` are now WRITES (they persist via saveAs); `hub_get_metrics` is now a READ (recordSnapshot default false). The previously-flat custom-rule and device tools (`hub_get_custom_rule`, `hub_create_custom_rule`, `hub_update_custom_rule`, `hub_list_devices`, `hub_get_device`, etc.) are now folded into gateways. `hub_report_issue` remains a flat core tool.

### T708 - Pause reporting preserves a literal name across rule engines

**Prompt**: "Using only throwaway rules with no real-device actions, create a Rule Machine rule and a Visual Rule in each builder this hub supports. Give each a name ending in `(Paused)`. Also try Visual Rule names containing literal `<span>(Paused)</span>` and `&amp;` text. Pause and resume each, and check whether the rule health report agrees with its own runtime state while preserving the full name. Remove every test rule afterward."

**Expected**: Health reports `paused:true` after pause and `paused:false` after resume, with the same literal name both times. A dedicated Visual Rule read and rename verification preserve that name too, including literal markup and entity spellings. Backup and restore of a scratch Visual Rule preserve its own name and pause state. If neither compiled nor status pause evidence is readable and no tagged pause decoration exists, health returns `paused:null` instead of guessing from a bare suffix. Use single-rule reads for state verification; the lightweight Visual Rule list has its own suffix-based detection. No existing automation is modified.

The graph builder's exact trailing ` <span class='text-red'>(Paused)</span>` is
reserved in practice: native pause/resume conflates it with runtime decoration
and removes it on resume. Do not interpret that platform collision as evidence
that ordinary literal markup or a bare `(Paused)` suffix is a pause signal.

### T709 - Clone and import report whether inactive staging actually landed

**Prompt**: "Create an empty throwaway rule and make an inactive clone and an inactive import of its export. Give the copies the same name as the source so name collisions cannot substitute for checking identities. Verify that both new apps are disabled and the source is unchanged. If staging fails after creation, identify the created app and disable it without creating another copy. Clean up all test apps."

**Expected**: Both operations return the discovered new app ID and list it in `stagedDisabled`; independent reads show disabled. Any failed disable produces `success:false`, `isError:true`, the affected IDs and a targeted disable remedy, warning against repeating clone/import. If discovery fails, the response warns that staging could not run and any created app may still be enabled. Failure paths are deterministic unit-test cases; do not inject production-hub failures to force them.

An unreadable parent snapshot must refuse before creation and allow a safe retry. Ambiguous new-child discovery must not guess an app ID. At the modern continuation limit, the terminal error must retain `newAppId`, `stagedDisabled`, `stageFailures` and `stageRemaining`, including the final slice's progress; replay must not repeat writes. These fault and limit cases are covered through injected unit/integration fixtures, not production-hub fault injection.
