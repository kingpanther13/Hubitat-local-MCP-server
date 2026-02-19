# Bot Acceptance Test (BAT) Suite — v2

Updated for v0.8.0+ final architecture (21 core + 9 gateways = 30 on tools/list, 48 proxied).

Comprehensive test scenarios for the Hubitat MCP Rule Server. Modeled after ha-mcp's BAT framework.

Each test is a JSON scenario with optional `setup_prompt`, required `test_prompt`, and optional `teardown_prompt`. Run each prompt in the same AI session (setup → test → teardown). Each TEST SCENARIO starts a fresh session.

## Safety Rules

- **All tests use the `BAT` prefix** for artifacts (rules, devices, rooms, files, variables) for easy identification and cleanup
- **All rules are marked `testRule: true`** to skip backup on deletion
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

These tools appear directly on `tools/list` in both v0.7.7 (all 74 tools) and v0.8.0 (21 core tools). Every AI should find and use them without difficulty.

### T01 — list_devices (basic)

```json
{
  "test_prompt": "List all my smart home devices. Just give me a summary count and the first 5 device names."
}
```

**Expected**: Calls `list_devices`. Returns device count and names.

### T02 — list_devices (pagination)

```json
{
  "test_prompt": "List all my devices with full details. Use pagination with a limit of 10 devices per page. Show me just the first page."
}
```

**Expected**: Calls `list_devices` with `detailed=true, limit=10`.

### T03 — get_device

```json
{
  "setup_prompt": "List my devices briefly so I can see what's available.",
  "test_prompt": "Get the full details of the first device you found — all attributes, commands, and capabilities."
}
```

**Expected**: Calls `get_device` with a valid device ID. Returns attributes, commands, capabilities.

### T04 — get_attribute

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT Attr Test'.",
  "test_prompt": "Check the current switch state of 'BAT Attr Test'. Is it on or off?",
  "teardown_prompt": "Delete the virtual device 'BAT Attr Test'."
}
```

**Expected**: Calls `get_attribute` with device ID and `attribute=switch`.

### T05 — send_command (on/off)

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT Command Test'.",
  "test_prompt": "Turn on the virtual switch 'BAT Command Test', then check its state to confirm it turned on.",
  "teardown_prompt": "Turn off 'BAT Command Test', then delete the virtual device."
}
```

**Expected**: Calls `send_command` with `command=on`, then `get_attribute` to verify.

### T06 — send_command (setLevel)

```json
{
  "setup_prompt": "Create a virtual dimmer called 'BAT Dimmer Test'.",
  "test_prompt": "Set 'BAT Dimmer Test' to 50% brightness. Confirm the level.",
  "teardown_prompt": "Turn off 'BAT Dimmer Test', then delete the virtual device."
}
```

**Expected**: Calls `send_command` with `command=setLevel, args=[50]`. Verifies with `get_attribute`.

### T07 — get_device_events

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT Events Test'. Turn it on, then off, then on again.",
  "test_prompt": "Show me the last 5 events for 'BAT Events Test'.",
  "teardown_prompt": "Delete the virtual device 'BAT Events Test'."
}
```

**Expected**: Calls `get_device_events`. Returns recent on/off events.

### T08 — list_rules

```json
{
  "test_prompt": "Show me all the automation rules. Tell me how many exist and their names and statuses."
}
```

**Expected**: Calls `list_rules`. Returns rule count, names, enabled/disabled status.

### T09 — get_rule

```json
{
  "setup_prompt": "Create a test rule called 'BAT Get Rule' with a time trigger at 23:59 and a log action saying 'test'. Mark as test rule.",
  "test_prompt": "Show me the full configuration of the rule 'BAT Get Rule' — triggers, conditions, and actions.",
  "teardown_prompt": "Delete the rule 'BAT Get Rule'."
}
```

**Expected**: Calls `get_rule` with rule ID. Returns full structure.

### T10 — create_rule

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT Rule Device'.",
  "test_prompt": "Create a rule called 'BAT Create Test' that triggers at 23:59 and logs 'BAT test triggered'. Mark it as a test rule.",
  "teardown_prompt": "Delete the rule 'BAT Create Test'. Delete the virtual device 'BAT Rule Device'."
}
```

**Expected**: Calls `create_rule` with proper structure and `testRule=true`.

### T11 — update_rule

```json
{
  "setup_prompt": "Create a test rule called 'BAT Update Test' with a time trigger at 23:59 and a log action. Mark as test rule.",
  "test_prompt": "Update the rule 'BAT Update Test' to change the trigger time from 23:59 to 06:00.",
  "teardown_prompt": "Delete the rule 'BAT Update Test'."
}
```

**Expected**: Calls `update_rule` with modified trigger.

### T12 — update_rule enable/disable

```json
{
  "setup_prompt": "Create a test rule called 'BAT Toggle Test' with a time trigger at 23:59 and a log action. Mark as test rule.",
  "test_prompt": "Disable the rule 'BAT Toggle Test', confirm it's disabled, then re-enable it and confirm it's enabled again.",
  "teardown_prompt": "Delete the rule 'BAT Toggle Test'."
}
```

**Expected**: Calls `update_rule` with `enabled=false`, verifies, calls `update_rule` with `enabled=true`, verifies.

### T13 — update_device (label)

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT Label Test'.",
  "test_prompt": "Change the label of 'BAT Label Test' to 'BAT Label Changed'. Then verify the change.",
  "teardown_prompt": "Delete the virtual device 'BAT Label Changed'."
}
```

**Expected**: Calls `update_device` with `label` parameter. Verifies with `get_device`.

### T14 — get_hub_info

```json
{
  "test_prompt": "What hub am I connected to? Give me the basic information."
}
```

**Expected**: Calls `get_hub_info`. Returns hub name, model, firmware version.

### T15 — get_modes

```json
{
  "test_prompt": "What are the available location modes? What's the current mode? Don't change anything."
}
```

**Expected**: Calls `get_modes`. Lists modes and current mode.

### T16 — get_hsm_status

```json
{
  "test_prompt": "What's the current Home Security Monitor status? Just tell me the arm state, don't change it."
}
```

**Expected**: Calls `get_hsm_status`. Returns current arm state.

### T17 — get_tool_guide

```json
{
  "test_prompt": "Show me the tool guide section about device authorization rules."
}
```

**Expected**: Calls `get_tool_guide` with section parameter. Returns reference content.

### T18 — set_mode (read-only verification)

```json
{
  "test_prompt": "What modes are available on my hub? List them all. Do NOT change the current mode."
}
```

**Expected**: Calls `get_modes`. Reads only, does not call `set_mode`.

### T19 — manage_virtual_device (create)

```json
{
  "test_prompt": "Create a virtual switch called 'BAT Virtual Test'.",
  "teardown_prompt": "Delete the virtual device 'BAT Virtual Test'."
}
```

**Expected**: Calls `manage_virtual_device` with `action="create"`. Creates virtual device.

### T19b — list_virtual_devices

```json
{
  "test_prompt": "Show me all the virtual devices that are managed by MCP."
}
```

**Expected**: Calls `list_virtual_devices`. Lists MCP-managed virtual devices.

### T19c — manage_virtual_device (delete)

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT Delete Virtual'.",
  "test_prompt": "Delete the virtual device 'BAT Delete Virtual'."
}
```

**Expected**: Calls `manage_virtual_device` with `action="delete"`. Deletes virtual device.

---

## Section 2: Gateway Discovery Tests

These ask the AI to do something that requires a **proxied tool** (behind a gateway in v0.8.0), WITHOUT mentioning gateway names or specific tool names. The AI must discover the gateway from tool descriptions alone.

On v0.7.7 these tools are directly available — this section tests whether v0.8.0 gateway descriptions provide enough information for discovery.

### T20 — Discover export_rule (manage_rules_admin)

```json
{
  "setup_prompt": "Create a test rule called 'BAT Export Test' with a time trigger at 12:00 and a log action saying 'noon'. Mark as test rule.",
  "test_prompt": "Export the rule 'BAT Export Test' as JSON so I can back it up.",
  "teardown_prompt": "Delete the rule 'BAT Export Test'."
}
```

**Expected v0.7.7**: Calls `export_rule` directly.
**Expected v0.8.0**: Finds `manage_rules_admin` → `export_rule`.

### T21 — Discover clone_rule (manage_rules_admin)

```json
{
  "setup_prompt": "Create a test rule called 'BAT Clone Source' with a time trigger at 08:00 and a log action. Mark as test rule.",
  "test_prompt": "Make a copy of the rule 'BAT Clone Source'.",
  "teardown_prompt": "Delete any rules that start with 'BAT Clone' or 'Copy of BAT Clone'."
}
```

**Expected v0.8.0**: Discovers `manage_rules_admin` → `clone_rule`.

### T22 — Discover test_rule (manage_rules_admin)

```json
{
  "setup_prompt": "Create a test rule called 'BAT Dry Run' with a time trigger at 12:00 and a log action. Mark as test rule.",
  "test_prompt": "Do a dry run of the rule 'BAT Dry Run' — what would happen if it triggered right now?",
  "teardown_prompt": "Delete the rule 'BAT Dry Run'."
}
```

**Expected v0.8.0**: Discovers `manage_rules_admin` → `test_rule`.

### T23 — Discover import_rule round-trip (manage_rules_admin)

```json
{
  "setup_prompt": "Create a test rule called 'BAT Import Test' with a time trigger at 14:00 and a log action. Mark as test rule. Export it as JSON, save the data, then delete the original rule.",
  "test_prompt": "Re-import the rule from the export data you saved. Verify it exists.",
  "teardown_prompt": "Delete any rules containing 'BAT Import Test'."
}
```

**Expected v0.8.0**: Discovers `manage_rules_admin` → `import_rule`.

### T24 — Discover list_variables (manage_hub_variables)

```json
{
  "test_prompt": "Show me all the hub variables that are set."
}
```

**Expected v0.8.0**: Discovers `manage_hub_variables` → `list_variables`.

### T25 — Discover set_variable (manage_hub_variables)

```json
{
  "test_prompt": "Create a hub variable called 'bat_test_var' and set it to 'hello'.",
  "teardown_prompt": "Set the variable 'bat_test_var' to empty string to clean up."
}
```

**Expected v0.8.0**: Discovers `manage_hub_variables` → `set_variable`.

### T26 — Discover get_variable (manage_hub_variables)

```json
{
  "setup_prompt": "Set a hub variable called 'bat_lookup' to 'found_it'.",
  "test_prompt": "What's the current value of the variable 'bat_lookup'?",
  "teardown_prompt": "Set 'bat_lookup' to empty string."
}
```

**Expected v0.8.0**: Discovers `manage_hub_variables` → `get_variable`.

### T27 — Discover list_rooms (manage_rooms)

```json
{
  "test_prompt": "What rooms are set up on my hub? List them with device counts."
}
```

**Expected v0.8.0**: Discovers `manage_rooms` → `list_rooms`.

### T28 — Discover get_room (manage_rooms)

```json
{
  "setup_prompt": "List the rooms on the hub.",
  "test_prompt": "Show me the details of the first room — what devices are in it?"
}
```

**Expected v0.8.0**: Discovers `manage_rooms` → `get_room`.

### T29 — Discover create_room (manage_rooms)

```json
{
  "test_prompt": "Create a room called 'BAT Test Room'.",
  "teardown_prompt": "Delete the room called 'BAT Test Room'."
}
```

**Expected v0.8.0**: Discovers `manage_rooms` → `create_room`.

### T30 — Discover rename_room (manage_rooms)

```json
{
  "setup_prompt": "Create a room called 'BAT Rename Source'.",
  "test_prompt": "Rename the room 'BAT Rename Source' to 'BAT Rename Done'.",
  "teardown_prompt": "Delete the room 'BAT Rename Done'."
}
```

**Expected v0.8.0**: Discovers `manage_rooms` → `rename_room`.

### T31 — Discover delete_room (manage_rooms)

```json
{
  "setup_prompt": "Create a room called 'BAT Delete Room'.",
  "test_prompt": "Delete the room 'BAT Delete Room'."
}
```

**Expected v0.8.0**: Discovers `manage_rooms` → `delete_room`.

### T35 — Discover get_hub_info (core)

```json
{
  "test_prompt": "Give me detailed information about my Hubitat hub — model, firmware version, memory usage, temperature, and database size."
}
```

**Expected v0.8.0**: Calls `get_hub_info` directly (core tool — includes hardware, health, MCP stats; PII gated behind Hub Admin Read).

### T36 — Discover get_zwave_details (manage_diagnostics)

```json
{
  "test_prompt": "What's the status of my Z-Wave radio? Show me the Z-Wave network details."
}
```

**Expected v0.8.0**: Discovers `manage_diagnostics` → `get_zwave_details`.

### T37 — Discover get_zigbee_details (manage_diagnostics)

```json
{
  "test_prompt": "What Zigbee channel is my hub using? How many Zigbee devices are connected?"
}
```

**Expected v0.8.0**: Discovers `manage_diagnostics` → `get_zigbee_details`.

### T38 — Discover get_hub_info for health (core)

```json
{
  "test_prompt": "How healthy is my hub right now? Any warnings or issues?"
}
```

**Expected v0.8.0**: Calls `get_hub_info` directly (core tool — health data merged into get_hub_info in v0.8.0).

### T39 — Discover check_for_update (core)

```json
{
  "test_prompt": "Is there a newer version of the MCP server available?"
}
```

**Expected v0.8.0**: Calls `check_for_update` directly (promoted to core in v0.8.0).

### T40 — Discover create_hub_backup (core)

```json
{
  "test_prompt": "Create a full hub backup right now."
}
```

**Expected v0.8.0**: Calls `create_hub_backup` directly (promoted to core in v0.8.0).

### T41 — Discover list_hub_apps (manage_apps_drivers)

```json
{
  "test_prompt": "What apps are installed on my Hubitat hub?"
}
```

**Expected v0.8.0**: Discovers `manage_apps_drivers` → `list_hub_apps`.

### T42 — Discover list_hub_drivers (manage_apps_drivers)

```json
{
  "test_prompt": "What device drivers are installed on the hub?"
}
```

**Expected v0.8.0**: Discovers `manage_apps_drivers` → `list_hub_drivers`.

### T43 — Discover get_app_source (manage_apps_drivers)

```json
{
  "test_prompt": "Show me the source code of the first app on my hub. Just the first 50 lines."
}
```

**Expected v0.8.0**: Discovers `manage_apps_drivers` → `list_hub_apps` then → `get_app_source`.

### T44 — Discover get_driver_source (manage_apps_drivers)

```json
{
  "test_prompt": "Show me the source code of the first driver on my hub. Just the first 50 lines."
}
```

**Expected v0.8.0**: Discovers `manage_apps_drivers` → `list_hub_drivers` then → `get_driver_source`.

### T45 — Discover list_item_backups (manage_apps_drivers)

```json
{
  "test_prompt": "Are there any source code backups saved? List them."
}
```

**Expected v0.8.0**: Discovers `manage_apps_drivers` → `list_item_backups`.

### T46 — Discover get_item_backup (manage_apps_drivers)

```json
{
  "setup_prompt": "List the item backups to find one I can inspect.",
  "test_prompt": "Show me the source code from the first backup. Don't restore it, just let me see it."
}
```

**Expected v0.8.0**: Discovers `manage_apps_drivers` → `get_item_backup`.

### T47 — Discover get_hub_logs (manage_logs)

```json
{
  "test_prompt": "Show me the last 20 hub log entries. Filter to warnings and errors only."
}
```

**Expected v0.8.0**: Discovers `manage_logs` → `get_hub_logs`.

### T48 — Discover get_device_history (manage_logs)

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT History Test'. Turn it on and off a few times.",
  "test_prompt": "Show me the event history for 'BAT History Test' over the last 24 hours.",
  "teardown_prompt": "Delete the virtual device 'BAT History Test'."
}
```

**Expected v0.8.0**: Discovers `manage_logs` → `get_device_history`.

### T49 — Discover get_set_hub_metrics (manage_diagnostics)

```json
{
  "test_prompt": "What's the hub's current performance? Memory, temperature, and database size."
}
```

**Expected v0.8.0**: Discovers `manage_diagnostics` → `get_set_hub_metrics`.

### T50 — Discover device_health_check (manage_diagnostics)

```json
{
  "test_prompt": "Run a health check on all my devices. Are any stale or offline?"
}
```

**Expected v0.8.0**: Discovers `manage_diagnostics` → `device_health_check`.

### T51 — Discover get_debug_logs (manage_logs)

```json
{
  "test_prompt": "Show me the MCP debug logs."
}
```

**Expected v0.8.0**: Discovers `manage_logs` → `get_debug_logs`.

### T52 — Discover generate_bug_report (manage_diagnostics)

```json
{
  "test_prompt": "Generate a bug report with diagnostics that I can submit to GitHub."
}
```

**Expected v0.8.0**: Discovers `manage_diagnostics` → `generate_bug_report`.

### T53 — Discover get_rule_diagnostics (manage_diagnostics)

```json
{
  "setup_prompt": "Create a test rule called 'BAT Diag Test' with a time trigger and a log action. Mark as test rule.",
  "test_prompt": "Run diagnostics on the rule 'BAT Diag Test'. Give me a comprehensive health report.",
  "teardown_prompt": "Delete the rule 'BAT Diag Test'."
}
```

**Expected v0.8.0**: Discovers `manage_diagnostics` → `get_rule_diagnostics`.

### T54 — Discover set_log_level (manage_logs)

```json
{
  "test_prompt": "Change the MCP log level to 'warn', then show me the current logging status.",
  "teardown_prompt": "Set the MCP log level back to 'info'."
}
```

**Expected v0.8.0**: Discovers `manage_logs` → `set_log_level` and → `get_logging_status`.

### T55 — Discover list_captured_states (manage_diagnostics)

```json
{
  "test_prompt": "Are there any captured device state snapshots saved? List them."
}
```

**Expected v0.8.0**: Discovers `manage_diagnostics` → `list_captured_states`.

### T56 — Discover list_files (manage_files)

```json
{
  "test_prompt": "What files are stored in the hub's file manager?"
}
```

**Expected v0.8.0**: Discovers `manage_files` → `list_files`.

### T57 — Discover read_file (manage_files)

```json
{
  "setup_prompt": "List the files in the file manager. Find a small file.",
  "test_prompt": "Read the contents of the first file you found."
}
```

**Expected v0.8.0**: Discovers `manage_files` → `read_file`.

### T58 — Discover write_file + delete_file (manage_files)

```json
{
  "test_prompt": "Write a file called 'bat-test.txt' with content 'Hello from BAT test' to the hub file manager.",
  "teardown_prompt": "Delete the file 'bat-test.txt' from the file manager."
}
```

**Expected v0.8.0**: Discovers `manage_files` → `write_file` (and `delete_file` in teardown).

### T59 — Discover clear_debug_logs (manage_logs)

```json
{
  "test_prompt": "Clear all the MCP debug logs. Then confirm they're empty by checking them."
}
```

**Expected v0.8.0**: Discovers `manage_logs` → `clear_debug_logs`, then → `get_debug_logs`.

---

## Section 3: Gateway Behavior Tests (v0.8.0 only)

These test gateway-specific behaviors: catalog mode, skip-catalog optimization, and error handling.

### T60 — Catalog then execute pattern

```json
{
  "test_prompt": "I need to manage some rooms. First show me what room management tools are available and their full parameter schemas, then list my rooms."
}
```

**Expected**: Calls `manage_rooms()` with no args (catalog), then `manage_rooms(tool=list_rooms)`.

### T61 — Skip catalog when confident

```json
{
  "test_prompt": "List all my rooms."
}
```

**Expected**: Calls `manage_rooms(tool=list_rooms)` directly — no catalog fetch needed.

### T62 — Catalog for parameter discovery

```json
{
  "test_prompt": "I want to get some hub diagnostics but I'm not sure what's available. Show me the full parameter schema for the diagnostic tools."
}
```

**Expected**: Calls `manage_diagnostics()` catalog to see available diagnostic tools and their schemas.

### T63 — Invalid tool in gateway (error handling)

```json
{
  "test_prompt": "Try to call a tool called 'nonexistent_tool' through the manage_rooms gateway. Report what happens."
}
```

**Expected**: Gateway returns error about unknown tool. AI reports the error.

### T64 — Tool name mentioned but not on tools/list

```json
{
  "setup_prompt": "Create a test rule called 'BAT Proxy Test' with a time trigger and log action. Mark as test rule.",
  "test_prompt": "Call the export_rule tool to export my rule 'BAT Proxy Test'.",
  "teardown_prompt": "Delete the rule 'BAT Proxy Test'."
}
```

**Expected**: AI recognizes `export_rule` is behind `manage_rules_admin` and routes correctly. Does NOT report "tool not found."

### T65 — Wrong gateway for tool (error handling)

```json
{
  "test_prompt": "Use the manage_files gateway to call list_rooms. Report what error you get."
}
```

**Expected**: Gateway returns error — `list_rooms` is not in `manage_files`.

---

## Section 4: Natural Language Routing Tests

Casual natural language prompts that must route to the correct tool/gateway.

### T70 — "Check my hub" → hub info

```json
{
  "test_prompt": "Check up on my hub. How's it doing?"
}
```

**Expected**: Routes to `get_hub_info` (core tool — includes health, hardware, and MCP stats).

### T71 — "What variables do I have?" → hub variables

```json
{
  "test_prompt": "What variables have I set up?"
}
```

**Expected**: Routes to `list_variables`.

### T72 — "Save a counter" → hub variables

```json
{
  "test_prompt": "Save a variable called 'bat_counter' with value 42.",
  "teardown_prompt": "Set the variable 'bat_counter' to empty string."
}
```

**Expected**: Routes to `set_variable`.

### T73 — "Show me what's in files" → file manager

```json
{
  "test_prompt": "I need to see what's saved in the hub's local storage. Show me the file list."
}
```

**Expected**: Routes to `list_files`.

### T74 — "Why isn't my rule working?" → diagnostics

```json
{
  "setup_prompt": "Create a disabled test rule called 'BAT Broken Rule' with a time trigger and log action. Mark as test rule.",
  "test_prompt": "The rule 'BAT Broken Rule' isn't working. Can you diagnose what's wrong?",
  "teardown_prompt": "Delete the rule 'BAT Broken Rule'."
}
```

**Expected**: Uses `get_rule_diagnostics` or `get_rule`. Should notice the rule is disabled.

### T75 — "What apps do I have installed?" → apps/drivers

```json
{
  "test_prompt": "What Groovy apps and drivers are installed on my hub?"
}
```

**Expected**: Routes to `list_hub_apps` and/or `list_hub_drivers`.

### T76 — "Find stale devices" → diagnostics

```json
{
  "test_prompt": "Are any of my devices offline or haven't reported in a while?"
}
```

**Expected**: Routes to `device_health_check`.

### T77 — "Duplicate my rule" → rules admin

```json
{
  "setup_prompt": "Create a test rule called 'BAT Original' with a time trigger at 07:00 and a log action. Mark as test rule.",
  "test_prompt": "Duplicate the rule 'BAT Original'.",
  "teardown_prompt": "Delete all rules containing 'BAT Original' or 'Copy of BAT Original'."
}
```

**Expected**: Routes to `clone_rule`.

### T78 — "Back up my rule" → rules admin

```json
{
  "setup_prompt": "Create a test rule called 'BAT Backup Test' with a time trigger and log action. Mark as test rule.",
  "test_prompt": "Back up the rule 'BAT Backup Test' — I want to be able to restore it later.",
  "teardown_prompt": "Delete the rule 'BAT Backup Test'."
}
```

**Expected**: Routes to `export_rule`.

### T79 — "Show me the logs" → hub logs (not debug logs)

```json
{
  "test_prompt": "Show me the logs."
}
```

**Expected**: Routes to `get_hub_logs` (system logs), not `get_debug_logs` (MCP-specific).

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
1. `create_rule` (core)
2. `get_rule` (core)
3. `manage_rules_admin(tool=test_rule)` (gateway)
4. `update_rule` with `enabled=false` (core)
5. `manage_rules_admin(tool=export_rule)` (gateway)
6. `manage_rules_admin(tool=delete_rule)` (gateway)

### T81 — Virtual device workflow

```json
{
  "test_prompt": "1. Create a virtual switch called 'BAT Workflow Switch'.\n2. List virtual devices to confirm it exists.\n3. Turn it on.\n4. Check its state.\n5. Delete the virtual device.\nReport each step."
}
```

**Expected (v0.8.0)**: All core tools: `manage_virtual_device(action="create")`, `list_virtual_devices`, `send_command`, `get_attribute`, `manage_virtual_device(action="delete")`.

### T82 — Room management workflow

```json
{
  "test_prompt": "1. List all rooms.\n2. Create a room called 'BAT Room Test'.\n3. Show the room details.\n4. Rename the room to 'BAT Room Renamed'.\n5. Delete the room.\nReport each step."
}
```

**Expected**: All via `manage_rooms` gateway.

### T83 — Diagnostics workflow

```json
{
  "test_prompt": "I want a comprehensive health report:\n1. Hub performance metrics\n2. Device health check\n3. Recent hub log warnings/errors (last 10)\n4. MCP debug logs\nGive me a summary of each."
}
```

**Expected**: Split across `manage_diagnostics` (get_set_hub_metrics, device_health_check) and `manage_logs` (get_hub_logs, get_debug_logs).

### T84 — Cross-gateway workflow

```json
{
  "test_prompt": "Gather a comprehensive audit:\n1. List my rooms\n2. List virtual devices\n3. List hub variables\n4. List files in file manager\n5. List item backups\n6. Hub health status\n7. Device health check\nGive me a one-line summary for each."
}
```

**Expected (v0.8.0)**: Uses 5 gateways plus core: `manage_rooms` (list_rooms), core (`list_virtual_devices`), `manage_hub_variables` (list_variables), `manage_files` (list_files), `manage_apps_drivers` (list_item_backups), core (`get_hub_info`), `manage_diagnostics` (device_health_check).

### T85 — Rule with virtual device end-to-end

```json
{
  "test_prompt": "1. Create a virtual motion sensor called 'BAT Motion'\n2. Create a virtual switch called 'BAT Light'\n3. Create a rule called 'BAT Motion Light' that turns on 'BAT Light' when 'BAT Motion' detects motion, then turns it off after 30 seconds. Mark as test rule.\n4. Show me the rule to verify.\n5. Dry-run the rule.\nReport everything.",
  "teardown_prompt": "Delete the rule 'BAT Motion Light'. Delete both virtual devices 'BAT Motion' and 'BAT Light'."
}
```

**Expected**: Core tools (`manage_virtual_device` x2, `create_rule`, `get_rule`) and `manage_rules_admin` (`test_rule`).

### T86 — Variable round-trip workflow

```json
{
  "test_prompt": "1. Set a variable called 'bat_workflow' to 'step1'\n2. Read its value to confirm\n3. Update it to 'step2'\n4. Read again to confirm\n5. List all variables to see it in context",
  "teardown_prompt": "Set variable 'bat_workflow' to empty string."
}
```

**Expected**: All via `manage_hub_variables` (set, get, set, get, list).

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

**Expected**: Routes to `delete_rule`, not `delete_device`.

### T91 — "Health" ambiguity (hub vs device)

```json
{
  "test_prompt": "Check the health of my system."
}
```

**Expected**: Could call `get_hub_info` or `device_health_check` or both. Should not call unrelated tools.

### T92 — "Status" ambiguity (comprehensive)

```json
{
  "test_prompt": "What's the status of everything?"
}
```

**Expected**: Calls multiple read-only tools: `get_hub_info`, `get_modes`, `get_hsm_status`, possibly hub health.

### T93 — "Source code" ambiguity (app vs driver)

```json
{
  "test_prompt": "Show me the source code of the MCP Rule Server app."
}
```

**Expected**: Routes to `get_app_source` (app, not driver).

### T94 — "Performance" ambiguity (hub_info vs diagnostics)

```json
{
  "test_prompt": "What's the performance of my hub?"
}
```

**Expected**: `get_set_hub_metrics` (in `manage_diagnostics`), not `get_hub_info` (core). Performance metrics = diagnostics gateway.

### T95 — User references wrong gateway domain

```json
{
  "test_prompt": "Use the hub admin tools to check my device health."
}
```

**Expected**: `device_health_check` is in `manage_diagnostics`. AI should find the right gateway despite the misdirection.

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

**Expected**: `get_device` returns error. AI reports device not found.

### T101 — Invalid rule ID

```json
{
  "test_prompt": "Get the details of rule with ID 99999."
}
```

**Expected**: `get_rule` returns error. AI reports rule not found.

### T102 — Send command to non-existent device

```json
{
  "test_prompt": "Turn on device ID 99999."
}
```

**Expected**: `send_command` fails. AI reports device not found.

### T103 — Create rule with invalid structure

```json
{
  "test_prompt": "Create a rule called 'BAT Bad Rule' with no triggers and no actions.",
  "teardown_prompt": "Delete 'BAT Bad Rule' if it was created."
}
```

**Expected**: `create_rule` returns validation error.

### T104 — Gateway anti-recursion (v0.8.0)

```json
{
  "test_prompt": "Try to use manage_diagnostics to call manage_rooms. Report what happens."
}
```

**Expected**: Error — "Cannot call a gateway from within a gateway."

### T105 — Missing required args through gateway (v0.8.0)

```json
{
  "test_prompt": "Call manage_rooms with tool='get_room' but don't specify which room. Report what happens."
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

---

## Section 8: Comparison/Regression Tests

Run these prompts on BOTH v0.7.7 (all 74 on tools/list) and v0.8.0 (21 + 10 gateways) to compare.

### T110 — Tool count verification

```json
{
  "test_prompt": "How many MCP tools are available? List all of them."
}
```

**v0.7.7**: 74 tools listed. **v0.8.0**: 31 items (21 core + 10 gateways). Compare token usage, completeness.

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

**v0.7.7**: Calls `list_variables` directly. **v0.8.0**: Via `manage_hub_variables`. Compare discovery efficiency.

### T113 — Export a rule (moved to gateway in v0.8.0)

```json
{
  "setup_prompt": "Create a test rule called 'BAT Compare Export' with a time trigger and log action. Mark as test rule.",
  "test_prompt": "Export the rule 'BAT Compare Export' as JSON.",
  "teardown_prompt": "Delete 'BAT Compare Export'."
}
```

**v0.7.7**: Calls `export_rule` directly. **v0.8.0**: Via `manage_rules_admin`. Compare extra turns for discovery.

### T114 — Hub logs (moved to gateway in v0.8.0)

```json
{
  "test_prompt": "Show me the last 10 hub log entries at warning level or above."
}
```

**v0.7.7**: Calls `get_hub_logs` directly. **v0.8.0**: Via `manage_logs`. Compare discovery.

### T115 — Delete rule (moved to gateway in v0.8.0)

```json
{
  "setup_prompt": "Create a test rule called 'BAT Compare Delete' with a time trigger and log action. Mark as test rule.",
  "test_prompt": "Delete the rule 'BAT Compare Delete'."
}
```

**v0.7.7**: `delete_rule` directly. **v0.8.0**: Via `manage_rules_admin`. Did AI try direct call first and fail?

### T116 — Multi-tool hub status (regression baseline)

```json
{
  "test_prompt": "Give me a complete hub status: basic info, current mode, HSM status, hub health, and recent error logs."
}
```

**Expected**: Both versions call multiple tools. Compare total turns, tool calls, duration.

---

## Section 9: Stress Tests

### T120 — All 10 gateways in one session (v0.8.0)

```json
{
  "setup_prompt": "Create a test rule called 'BAT Stress Test' with a time trigger at 23:59 and a log action. Mark as test rule.",
  "test_prompt": "For each of these, just read what's available (don't modify anything except the backup):\n1. List rooms\n2. List hub variables\n3. Hub details (model, firmware)\n4. Create a hub backup\n5. List files in file manager\n6. List installed apps\n7. Hub performance metrics\n8. System logs (last 5)\n9. Check for MCP server updates\n10. Export the rule 'BAT Stress Test' as JSON\nConfirm each one worked.",
  "teardown_prompt": "Delete the rule 'BAT Stress Test'."
}
```

**Expected**: 10 calls across core tools and gateways (manage_rooms, manage_hub_variables, get_hub_info (core), create_hub_backup (core), manage_files, manage_apps_drivers, manage_diagnostics, manage_logs, check_for_update (core), manage_rules_admin). `manage_app_driver_code` excluded — all its tools are destructive. All should succeed.

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

**Expected**: Multiple `list_devices` calls with increasing offset. Verifies pagination loop.

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

**Expected**: `list_devices` with `detailed=false`. Returns count and names.
**Equivalent to**: T01

#### T201 — Show me everything about a device

```json
{
  "setup_prompt": "List my devices briefly so I can see what's available.",
  "test_prompt": "That first one looks interesting. Tell me everything about it — what can it do, what state is it in, what room is it in?"
}
```

**Expected**: `get_device` with a valid device ID.
**Equivalent to**: T03

#### T202 — Is this thing on?

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT NL Switch Check'.",
  "test_prompt": "Hey, is 'BAT NL Switch Check' turned on or off right now?",
  "teardown_prompt": "Delete the virtual device 'BAT NL Switch Check'."
}
```

**Expected**: `get_attribute` with attribute=switch.
**Equivalent to**: T04

#### T203 — Flip a switch and verify

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT NL Flip Test'.",
  "test_prompt": "Can you flip on 'BAT NL Flip Test' for me? And double-check that it actually turned on.",
  "teardown_prompt": "Turn off 'BAT NL Flip Test', then delete the virtual device."
}
```

**Expected**: `send_command` (on), then `get_attribute` to verify.
**Equivalent to**: T05

#### T204 — Dim a light

```json
{
  "setup_prompt": "Create a virtual dimmer called 'BAT NL Dimmer'.",
  "test_prompt": "'BAT NL Dimmer' is too bright. Bring it down to about half and confirm it changed.",
  "teardown_prompt": "Turn off 'BAT NL Dimmer', then delete the virtual device."
}
```

**Expected**: `send_command` with setLevel ~50, then verify.
**Equivalent to**: T06

#### T205 — What's been happening with this device?

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT NL Activity'. Turn it on, then off, then on again.",
  "test_prompt": "What's been going on with 'BAT NL Activity'? Show me its recent activity.",
  "teardown_prompt": "Delete the virtual device 'BAT NL Activity'."
}
```

**Expected**: `get_device_events`.
**Equivalent to**: T07

#### T206 — I want full details on everything

```json
{
  "test_prompt": "I want to see detailed info on all my devices — what they can do, what state they're in, everything. There's a lot of them so don't dump it all at once, just show me the first batch."
}
```

**Expected**: `list_devices` with `detailed=true`, paginated.
**Equivalent to**: T02

---

### Automations & Rules

#### T210 — What automations do I have?

```json
{
  "test_prompt": "What automations do I have set up? Are they all running or are some paused?"
}
```

**Expected**: `list_rules`.
**Equivalent to**: T08

#### T211 — Walk me through this automation

```json
{
  "setup_prompt": "Create a test rule called 'BAT NL Rule View' with a time trigger at 23:59 and a log action saying 'test'. Mark as test rule.",
  "test_prompt": "I want to understand how 'BAT NL Rule View' works. Walk me through what triggers it and what it does.",
  "teardown_prompt": "Delete the rule 'BAT NL Rule View'."
}
```

**Expected**: `get_rule`.
**Equivalent to**: T09

#### T212 — Build me an automation

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT NL Rule Device'.",
  "test_prompt": "I want an automation that writes a log message every night at 11:59 PM. Call it 'BAT NL Auto Test' and make sure it's flagged as a test rule.",
  "teardown_prompt": "Delete the rule 'BAT NL Auto Test'. Delete the virtual device 'BAT NL Rule Device'."
}
```

**Expected**: `create_rule` with time trigger and log action, `testRule=true`.
**Equivalent to**: T10

#### T213 — Change when my automation runs

```json
{
  "setup_prompt": "Create a test rule called 'BAT NL Reschedule' with a time trigger at 23:59 and a log action. Mark as test rule.",
  "test_prompt": "The timing on 'BAT NL Reschedule' is wrong — I actually want it to fire at 6 AM, not midnight.",
  "teardown_prompt": "Delete the rule 'BAT NL Reschedule'."
}
```

**Expected**: `update_rule` with modified trigger time.
**Equivalent to**: T11

#### T214 — Pause and unpause an automation

```json
{
  "setup_prompt": "Create a test rule called 'BAT NL Pause Test' with a time trigger at 23:59 and a log action. Mark as test rule.",
  "test_prompt": "I want to temporarily stop 'BAT NL Pause Test' from running. Pause it and confirm it's not active. Then turn it back on and make sure it's good to go again.",
  "teardown_prompt": "Delete the rule 'BAT NL Pause Test'."
}
```

**Expected**: `update_rule` with `enabled=false`, verify, `update_rule` with `enabled=true`, verify.
**Equivalent to**: T12

#### T215 — Save a backup of my automation

```json
{
  "setup_prompt": "Create a test rule called 'BAT NL Backup' with a time trigger at 12:00 and a log action. Mark as test rule.",
  "test_prompt": "I want to save a copy of 'BAT NL Backup' — give me the full configuration as JSON so I could restore it later if something goes wrong.",
  "teardown_prompt": "Delete the rule 'BAT NL Backup'."
}
```

**Expected**: `export_rule`.
**Equivalent to**: T20

#### T216 — Duplicate an automation

```json
{
  "setup_prompt": "Create a test rule called 'BAT NL Original' with a time trigger at 08:00 and a log action. Mark as test rule.",
  "test_prompt": "I like how 'BAT NL Original' works. Can you make a copy of it so I can tweak the duplicate without breaking the original?",
  "teardown_prompt": "Delete any rules that start with 'BAT NL Original' or 'Copy of BAT NL Original'."
}
```

**Expected**: `clone_rule`.
**Equivalent to**: T21

#### T217 — Simulate an automation

```json
{
  "setup_prompt": "Create a test rule called 'BAT NL Simulate' with a time trigger at 12:00 and a log action. Mark as test rule.",
  "test_prompt": "I'm curious what would happen if 'BAT NL Simulate' triggered right now. Can you walk me through it without actually running anything?",
  "teardown_prompt": "Delete the rule 'BAT NL Simulate'."
}
```

**Expected**: `test_rule` (dry run).
**Equivalent to**: T22

#### T218 — Restore an automation from backup

```json
{
  "setup_prompt": "Create a test rule called 'BAT NL Restore Test' with a time trigger at 14:00 and a log action. Mark as test rule. Export it as JSON, save the data, then delete the original rule.",
  "test_prompt": "Remember that automation you backed up? Bring it back from the JSON data you saved. Make sure it's there.",
  "teardown_prompt": "Delete any rules containing 'BAT NL Restore Test'."
}
```

**Expected**: `import_rule`.
**Equivalent to**: T23

#### T219 — Get rid of an automation

```json
{
  "setup_prompt": "Create a test rule called 'BAT NL Trash Rule' with a time trigger and log action. Mark as test rule.",
  "test_prompt": "I don't need 'BAT NL Trash Rule' anymore. Get rid of it."
}
```

**Expected**: `delete_rule`.
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

**Expected**: `manage_virtual_device` with `action="create"`, type Virtual Switch.
**Equivalent to**: T19

#### T221 — What simulated devices do I have?

```json
{
  "test_prompt": "Do I have any simulated or fake devices set up for testing?"
}
```

**Expected**: `list_virtual_devices`.
**Equivalent to**: T19b

#### T222 — Remove a test device

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT NL Cleanup Device'.",
  "test_prompt": "I'm done testing with 'BAT NL Cleanup Device'. Take it off my hub."
}
```

**Expected**: `manage_virtual_device` with `action="delete"`.
**Equivalent to**: T19c

#### T223 — Rename a device

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT NL Ugly Name'.",
  "test_prompt": "The name 'BAT NL Ugly Name' is terrible. Can you change it to 'BAT NL Better Name'? Make sure the change actually stuck.",
  "teardown_prompt": "Delete the virtual device 'BAT NL Better Name'."
}
```

**Expected**: `update_device` with label change, then `get_device` to verify.
**Equivalent to**: T13

---

### Rooms

#### T230 — How is my hub organized?

```json
{
  "test_prompt": "How is my smart home organized? What rooms do I have and how many gadgets are in each one?"
}
```

**Expected**: `list_rooms`.
**Equivalent to**: T27

#### T231 — What's in this room?

```json
{
  "setup_prompt": "List the rooms on the hub.",
  "test_prompt": "Tell me about the first room you found — what devices are in it and what are they doing?"
}
```

**Expected**: `get_room`.
**Equivalent to**: T28

#### T232 — Add a new room

```json
{
  "test_prompt": "I'm rearranging my house. I need a new room added to the hub — call it 'BAT NL New Room'.",
  "teardown_prompt": "Delete the room called 'BAT NL New Room'."
}
```

**Expected**: `create_room`.
**Equivalent to**: T29

#### T233 — Change a room's name

```json
{
  "setup_prompt": "Create a room called 'BAT NL Placeholder'.",
  "test_prompt": "I came up with a better name. Can you change 'BAT NL Placeholder' to 'BAT NL Final Name'?",
  "teardown_prompt": "Delete the room 'BAT NL Final Name'."
}
```

**Expected**: `rename_room`.
**Equivalent to**: T30

#### T234 — Remove a room

```json
{
  "setup_prompt": "Create a room called 'BAT NL Temp Room'.",
  "test_prompt": "Never mind about 'BAT NL Temp Room'. I don't need it anymore, take it down."
}
```

**Expected**: `delete_room`.
**Equivalent to**: T31

---

### System & Hub Info

#### T240 — What hub is this?

```json
{
  "test_prompt": "What kind of hub am I running? Give me the basics — name, model, software version."
}
```

**Expected**: `get_hub_info`.
**Equivalent to**: T14

#### T241 — What modes can my hub be in?

```json
{
  "test_prompt": "Does my hub have different modes like Home, Away, or Night? What are the options and which one is active right now? Don't change anything."
}
```

**Expected**: `get_modes`. Should NOT call `set_mode`.
**Equivalent to**: T15, T18

#### T242 — Is my security system armed?

```json
{
  "test_prompt": "Is my alarm system armed right now? Just tell me the status, don't touch it."
}
```

**Expected**: `get_hsm_status`.
**Equivalent to**: T16

#### T243 — How should I safely control devices?

```json
{
  "test_prompt": "Before I start telling you to control things in my home, are there safety rules I should know about? Like how do you make sure you're controlling the right device?"
}
```

**Expected**: `get_tool_guide` with a section related to device authorization.
**Equivalent to**: T17

---

### Variables

#### T250 — What values have I stored?

```json
{
  "test_prompt": "Have I saved any custom values or counters on my hub?"
}
```

**Expected**: `list_variables`.
**Equivalent to**: T24

#### T251 — Remember a number for me

```json
{
  "test_prompt": "I need to remember a number. Store the value 42 under the name 'bat_nl_counter'.",
  "teardown_prompt": "Set the variable 'bat_nl_counter' to empty string."
}
```

**Expected**: `set_variable`.
**Equivalent to**: T25

#### T252 — What did I store?

```json
{
  "setup_prompt": "Set a hub variable called 'bat_nl_lookup' to 'found_it'.",
  "test_prompt": "What did I save under 'bat_nl_lookup'?",
  "teardown_prompt": "Set 'bat_nl_lookup' to empty string."
}
```

**Expected**: `get_variable`.
**Equivalent to**: T26

---

### Hub Administration

#### T260 — Full hub specs

```json
{
  "test_prompt": "I want the full specs on my hub — model number, firmware, how much memory it has, the temperature, how big the database is. The whole picture."
}
```

**Expected**: `get_hub_info` (core).
**Equivalent to**: T35

#### T261 — Z-Wave network info

```json
{
  "test_prompt": "I'm having trouble with some wireless devices. Can you show me what's going on with the Z-Wave network?"
}
```

**Expected**: `get_zwave_details`.
**Equivalent to**: T36

#### T262 — Zigbee network info

```json
{
  "test_prompt": "I heard Zigbee and Wi-Fi can interfere with each other. What channel is my Zigbee radio on?"
}
```

**Expected**: `get_zigbee_details`.
**Equivalent to**: T37

#### T263 — Is my hub healthy?

```json
{
  "test_prompt": "Give my hub a checkup. Anything I should be worried about?"
}
```

**Expected**: `get_hub_info` (core).
**Equivalent to**: T38

#### T264 — Am I up to date?

```json
{
  "test_prompt": "Am I running the latest version of this server or is there something newer I should be updating to?"
}
```

**Expected**: `check_for_update`.
**Equivalent to**: T39

#### T265 — Save a safety net

```json
{
  "test_prompt": "I'm about to make some changes and I want a safety net. Can you snapshot everything first in case I need to roll back?"
}
```

**Expected**: `create_hub_backup`.
**Equivalent to**: T40

#### T266 — What software is on my hub?

```json
{
  "test_prompt": "What apps are installed on my hub? I want to see what's running."
}
```

**Expected**: `list_hub_apps`.
**Equivalent to**: T41

#### T267 — What drivers are loaded?

```json
{
  "test_prompt": "I got a new device and I'm not sure the hub has the right driver. What drivers do I have?"
}
```

**Expected**: `list_hub_drivers`.
**Equivalent to**: T42

#### T268 — Peek at app code

```json
{
  "setup_prompt": "List the apps on the hub so I can see what's available.",
  "test_prompt": "I want to peek at the code for one of my apps. Show me the beginning of the first one — just the first 50 lines."
}
```

**Expected**: `get_app_source`.
**Equivalent to**: T43

#### T269 — Peek at driver code

```json
{
  "setup_prompt": "List the drivers on the hub.",
  "test_prompt": "Show me the code for the first driver — just the top 50 lines or so."
}
```

**Expected**: `get_driver_source`.
**Equivalent to**: T44

#### T270 — Are there code backups?

```json
{
  "test_prompt": "Have any code backups been saved automatically? I want to see what's available in case I need to roll something back."
}
```

**Expected**: `list_item_backups`.
**Equivalent to**: T45

#### T271 — Show me a backup

```json
{
  "setup_prompt": "List the item backups to find one I can inspect.",
  "test_prompt": "Let me see what's in the first backup. Just show me the code, don't restore anything."
}
```

**Expected**: `get_item_backup`.
**Equivalent to**: T46

---

### Logs & Diagnostics

#### T275 — Show me system logs

```json
{
  "test_prompt": "Something weird happened earlier. Can you pull up the system logs? Just show me warnings and errors from the last little while."
}
```

**Expected**: `get_hub_logs` with level filter.
**Equivalent to**: T47

#### T276 — Device event history

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT NL History'. Turn it on and off a few times.",
  "test_prompt": "Show me what 'BAT NL History' has been up to over the last 24 hours.",
  "teardown_prompt": "Delete the virtual device 'BAT NL History'."
}
```

**Expected**: `get_device_history`.
**Equivalent to**: T48

#### T277 — Hub performance

```json
{
  "test_prompt": "How is my hub performing right now? Memory, temperature, that kind of thing."
}
```

**Expected**: `get_set_hub_metrics` (via `manage_diagnostics`).
**Equivalent to**: T49

#### T278 — Dead or unresponsive devices

```json
{
  "test_prompt": "Are any of my devices dead or not reporting? I want to find anything that's gone silent."
}
```

**Expected**: `device_health_check`.
**Equivalent to**: T50

#### T279 — Internal diagnostic logs

```json
{
  "test_prompt": "Something seems off with the server. Can you pull up the internal diagnostic logs to see if there are any errors?"
}
```

**Expected**: `get_debug_logs`.
**Equivalent to**: T51

#### T280 — Clean up diagnostic logs

```json
{
  "test_prompt": "The diagnostic logs are cluttered from all my testing. Wipe them clean and then confirm they're empty.",
  "teardown_prompt": "Set the MCP log level back to 'info' if it was changed."
}
```

**Expected**: `clear_debug_logs`, then `get_debug_logs` to verify.
**Equivalent to**: T59

#### T281 — Troubleshoot a broken automation

```json
{
  "setup_prompt": "Create a disabled test rule called 'BAT NL Broken' with a time trigger and log action. Mark as test rule.",
  "test_prompt": "The automation 'BAT NL Broken' isn't working and I can't figure out why. Can you run a health check on it and tell me what's wrong?",
  "teardown_prompt": "Delete the rule 'BAT NL Broken'."
}
```

**Expected**: `get_rule_diagnostics` or `get_rule`. Should notice the rule is disabled.
**Equivalent to**: T53, T74

#### T282 — Too much noise in the logs

```json
{
  "test_prompt": "There's way too much noise in the logs. Can you dial it back so only important stuff gets recorded? Then show me what the logging settings look like now.",
  "teardown_prompt": "Set the MCP log level back to 'info'."
}
```

**Expected**: `set_log_level` (to 'warn' or 'error'), then `get_logging_status`.
**Equivalent to**: T54

#### T283 — Generate a bug report

```json
{
  "test_prompt": "I think I found a bug. Can you put together a diagnostic report I can submit?"
}
```

**Expected**: `generate_bug_report`.
**Equivalent to**: T52

#### T284 — Have I saved any device snapshots?

```json
{
  "test_prompt": "Have I taken any snapshots of how my devices were configured? I want to see if there are any saved."
}
```

**Expected**: `list_captured_states`.
**Equivalent to**: T55

---

### Files

#### T290 — What's stored on my hub?

```json
{
  "test_prompt": "What files are saved on the hub's local storage? I want to see everything that's there."
}
```

**Expected**: `list_files`.
**Equivalent to**: T56

#### T291 — Read a file

```json
{
  "setup_prompt": "List the files in the file manager. Find a small file.",
  "test_prompt": "Let me see what's inside the first file you found."
}
```

**Expected**: `read_file`.
**Equivalent to**: T57

#### T292 — Save a note on the hub

```json
{
  "test_prompt": "I want to save a little text note on my hub. Call it 'bat-nl-note.txt' and put 'Hello from the natural language test' inside it.",
  "teardown_prompt": "Delete the file 'bat-nl-note.txt' from the file manager."
}
```

**Expected**: `write_file` (and `delete_file` in teardown).
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

**Expected tools**: `create_rule` → `get_rule` → `test_rule` → `update_rule(enabled=false)` → `export_rule` → `delete_rule`.
**Equivalent to**: T80

#### T296 — Virtual device end-to-end

```json
{
  "test_prompt": "Let me run a quick test: set up a fake switch called 'BAT NL Workflow Switch', make sure it shows up in my device list, turn it on and verify it's actually on, then tear everything down when you're done."
}
```

**Expected**: `manage_virtual_device(action="create")` → `list_virtual_devices` (or `get_device`) → `send_command` → `get_attribute` → `manage_virtual_device(action="delete")`.
**Equivalent to**: T81

#### T297 — Room management end-to-end

```json
{
  "test_prompt": "I want to reorganize my rooms. Show me what I have now, then add a new one called 'BAT NL Room Flow'. Show me its details, then rename it to 'BAT NL Room Renamed' because I changed my mind. Actually, I don't need it at all — get rid of it."
}
```

**Expected**: `list_rooms` → `create_room` → `get_room` → `rename_room` → `delete_room`.
**Equivalent to**: T82

#### T298 — Full diagnostic workup

```json
{
  "test_prompt": "I want a comprehensive health report on my smart home. Tell me how the hub is performing, whether any devices seem dead or unresponsive, show me any warnings or errors from the system logs, and check the internal diagnostic logs too. Give me a summary of each."
}
```

**Expected**: `get_set_hub_metrics` + `device_health_check` + `get_hub_logs` + `get_debug_logs`.
**Equivalent to**: T83

#### T299 — Complete smart home inventory

```json
{
  "test_prompt": "I want a complete inventory of my entire smart home setup. Tell me about my rooms, any simulated devices, what custom values are stored, what files are on the hub, any code backups, the hub's overall health, and whether any real devices have gone offline. Give me a one-line summary for each area."
}
```

**Expected**: `list_rooms` + `list_virtual_devices` + `list_variables` + `list_files` + `list_item_backups` + `get_hub_info` + `device_health_check`.
**Equivalent to**: T84

#### T300 — Motion-activated light from scratch

```json
{
  "test_prompt": "I want to build a motion-activated light from scratch. Set up a fake motion sensor called 'BAT NL Motion' and a fake light called 'BAT NL Light'. Then create an automation called 'BAT NL Motion Light' that turns the light on when motion is detected and turns it off 30 seconds later — mark it as a test rule. Show me what you built and simulate it firing.",
  "teardown_prompt": "Delete the rule 'BAT NL Motion Light'. Delete both virtual devices 'BAT NL Motion' and 'BAT NL Light'."
}
```

**Expected**: `manage_virtual_device` (x2) → `create_rule` → `get_rule` → `test_rule`.
**Equivalent to**: T85

#### T301 — Variable round-trip

```json
{
  "test_prompt": "Let me test that variable storage works properly. Save 'step1' in something called 'bat_nl_flow', read it back to confirm, change it to 'step2', read it again, then show me where it sits among all the other stored values.",
  "teardown_prompt": "Set variable 'bat_nl_flow' to empty string."
}
```

**Expected**: `set_variable` → `get_variable` → `set_variable` → `get_variable` → `list_variables`.
**Equivalent to**: T86

---

## Excluded Tests (Destructive — Manual Only)

These operations are too destructive for automated testing. Test manually with extreme caution:

| Operation | Tool | Gateway | Why Excluded |
|-----------|------|---------|--------------|
| Reboot hub | `reboot_hub` | manage_destructive_hub_ops | 1-3 min downtime, kills automations |
| Shutdown hub | `shutdown_hub` | manage_destructive_hub_ops | Requires physical restart |
| Z-Wave repair | `zwave_repair` | manage_diagnostics | 5-30 min, devices unresponsive |
| Delete real device | `delete_device` | manage_destructive_hub_ops | Permanent, no undo |
| Install app | `install_app` | manage_app_driver_code | Modifies hub code |
| Install driver | `install_driver` | manage_app_driver_code | Modifies hub code |
| Update app code | `update_app_code` | manage_app_driver_code | Modifies production code |
| Update driver code | `update_driver_code` | manage_app_driver_code | Modifies production code |
| Delete app | `delete_app` | manage_app_driver_code | Permanent code removal |
| Delete driver | `delete_driver` | manage_app_driver_code | Permanent code removal |
| Restore item backup | `restore_item_backup` | manage_app_driver_code | Overwrites current code |
| Set HSM | `set_hsm` | core | Changes security system state |
| Set Mode | `set_mode` | core | Changes hub mode (may trigger automations) |

---

## Test Execution Checklist

### Prerequisites
- [ ] Hubitat hub accessible on local network
- [ ] MCP server installed and configured
- [ ] OAuth endpoint URL and token available
- [ ] Hub Admin Read **enabled** (for T35-T55, T83, T116)
- [ ] Hub Admin Write **enabled** (for virtual device/room create/delete tests)
- [ ] A recent hub backup exists (for Hub Admin Write operations)
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
| 1. Core Tools | T01-T19c | 21 core tools work directly |
| 2. Gateway Discovery | T20-T31, T35-T59 | LLM finds all proxied tools without hints |
| 3. Gateway Behavior | T60-T65 | Catalog mode, skip-catalog, errors |
| 4. Natural Language | T70-T79 | Casual prompts route correctly |
| 5. Multi-Step Workflows | T80-T86 | Complex multi-tool/gateway scenarios |
| 6. Ambiguity | T90-T96 | Correct routing under ambiguous prompts |
| 7. Edge Cases | T100-T108 | Error handling and boundary conditions |
| 8. Comparison | T110-T116 | v0.7.7 vs v0.8.0 regression |
| 9. Stress | T120-T122 | Many calls, rapid cycles, pagination |
| 10. NL Discovery | T200-T301 | Conversational prompts — no tool names |

### Architecture (v0.8.0)

| Component | Count |
|-----------|-------|
| Core tools on `tools/list` | 21 |
| Gateways on `tools/list` | 9 |
| Total visible on `tools/list` | 30 |
| Tools proxied behind gateways | 48 |
| Total tools in codebase | 69 |

**9 Gateways**: `manage_rules_admin` (5), `manage_hub_variables` (3), `manage_rooms` (5), `manage_destructive_hub_ops` (3), `manage_apps_drivers` (6), `manage_app_driver_code` (7), `manage_logs` (6), `manage_diagnostics` (9), `manage_files` (4)

### Tool Coverage (non-destructive tools only)

All 69 tools are covered by at least one test, excluding the destructive operations listed in the Excluded Tests table. Safe tools have standalone test coverage; destructive tools are documented for manual-only testing.

Sections 1-9 use explicit or semi-explicit tool references. Section 10 re-tests the same tool coverage through purely conversational language to measure whether the LLM can discover tools without being told which ones exist.

**Total: 172 test scenarios** (107 explicit + 65 natural language) plus 13 excluded destructive operations documented for manual testing

---

## Changes from BAT v1

Key differences from the original BAT.md (which targets the pre-v0.8.0 architecture):

1. **Architecture**: 18 core + 8 gateways (26 total) → **21 core + 9 gateways (30 total, 69 tools)**
2. **Merged tools**: `enable_rule`/`disable_rule` → `update_rule` (enabled=true/false); `create_virtual_device`/`delete_virtual_device` → `manage_virtual_device` (action enum)
3. **Promoted to core**: `create_hub_backup`, `check_for_update`, `generate_bug_report`
4. **Dissolved gateway**: `manage_hub_info` — radio details moved to `manage_diagnostics`, other tools merged into `get_hub_info` (core) or promoted
5. **Gateway renames**: `manage_hub_maintenance` → `manage_destructive_hub_ops` (3 tools); `manage_code_changes` → `manage_app_driver_code` (7 tools)
6. **Gateway splits from v1**: `manage_apps_drivers` → `manage_apps_drivers` (6 read) + `manage_app_driver_code` (7 write); `manage_logs_diagnostics` → `manage_logs` (6) + `manage_diagnostics` (9)
7. **T62 rewritten**: Was testing `manage_virtual_devices` catalog (removed gateway) → now tests `manage_diagnostics` catalog
8. **T104 updated**: Anti-recursion test uses `manage_diagnostics` gateway
9. **Excluded tests expanded**: 10 → 13 (separate rows for each app/driver operation, added gateway column)
10. **Corrected test count**: 159 → 172 (was undercounted in v1)
