# Bot Acceptance Test (BAT) Suite

Comprehensive test scenarios for the Hubitat MCP Rule Server. Modeled after ha-mcp's BAT framework.

Each test is a JSON scenario with optional `setup_prompt`, required `test_prompt`, and optional `teardown_prompt`. Run each prompt in the same AI session (setup → test → teardown). Each TEST SCENARIO starts a fresh session.

## Safety Rules

- **All tests use the `BAT` prefix** for artifacts (rules, devices, rooms, files, variables) for easy identification and cleanup
- **All rules are marked `testRule: true`** to skip backup on deletion
- **Tests only create/modify/delete test artifacts** — never touch existing production devices, rules, or hub settings
- **Device commands only target BAT-created virtual devices** — never command physical devices
- **Destructive hub operations are excluded** — no reboot, shutdown, Z-Wave repair, app/driver install/update/delete, or real device deletion
- **Teardown prompts are explicit** about what to clean up (including gateway paths when applicable for v0.8.0)

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

These tools appear directly on `tools/list` in both v0.7.7 (all 74 tools) and v0.8.0 (18 core tools). Every AI should find and use them without difficulty.

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

### T12 — enable_rule / disable_rule

```json
{
  "setup_prompt": "Create a test rule called 'BAT Toggle Test' with a time trigger at 23:59 and a log action. Mark as test rule.",
  "test_prompt": "Disable the rule 'BAT Toggle Test', confirm it's disabled, then re-enable it and confirm it's enabled again.",
  "teardown_prompt": "Delete the rule 'BAT Toggle Test'."
}
```

**Expected**: Calls `disable_rule`, verifies, calls `enable_rule`, verifies.

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

### T32 — Discover create_virtual_device (manage_virtual_devices)

```json
{
  "test_prompt": "Create a virtual switch called 'BAT Virtual Test'.",
  "teardown_prompt": "Delete the virtual device 'BAT Virtual Test'."
}
```

**Expected v0.8.0**: Discovers `manage_virtual_devices` → `create_virtual_device`.

### T33 — Discover list_virtual_devices (manage_virtual_devices)

```json
{
  "test_prompt": "Show me all the virtual devices that are managed by MCP."
}
```

**Expected v0.8.0**: Discovers `manage_virtual_devices` → `list_virtual_devices`.

### T34 — Discover delete_virtual_device (manage_virtual_devices)

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT Delete Virtual'.",
  "test_prompt": "Delete the virtual device 'BAT Delete Virtual'."
}
```

**Expected v0.8.0**: Discovers `manage_virtual_devices` → `delete_virtual_device`.

### T35 — Discover get_hub_details (manage_hub_admin)

```json
{
  "test_prompt": "Give me detailed information about my Hubitat hub — model, firmware version, memory usage, temperature, and database size."
}
```

**Expected v0.8.0**: Discovers `manage_hub_admin` → `get_hub_details`.

### T36 — Discover get_zwave_details (manage_hub_admin)

```json
{
  "test_prompt": "What's the status of my Z-Wave radio? Show me the Z-Wave network details."
}
```

**Expected v0.8.0**: Discovers `manage_hub_admin` → `get_zwave_details`.

### T37 — Discover get_zigbee_details (manage_hub_admin)

```json
{
  "test_prompt": "What Zigbee channel is my hub using? How many Zigbee devices are connected?"
}
```

**Expected v0.8.0**: Discovers `manage_hub_admin` → `get_zigbee_details`.

### T38 — Discover get_hub_health (manage_hub_admin)

```json
{
  "test_prompt": "How healthy is my hub right now? Any warnings or issues?"
}
```

**Expected v0.8.0**: Discovers `manage_hub_admin` → `get_hub_health`.

### T39 — Discover check_for_update (manage_hub_admin)

```json
{
  "test_prompt": "Is there a newer version of the MCP server available?"
}
```

**Expected v0.8.0**: Discovers `manage_hub_admin` → `check_for_update`.

### T40 — Discover create_hub_backup (manage_hub_admin)

```json
{
  "test_prompt": "Create a full hub backup right now."
}
```

**Expected v0.8.0**: Discovers `manage_hub_admin` → `create_hub_backup`.

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

### T47 — Discover get_hub_logs (manage_logs_diagnostics)

```json
{
  "test_prompt": "Show me the last 20 hub log entries. Filter to warnings and errors only."
}
```

**Expected v0.8.0**: Discovers `manage_logs_diagnostics` → `get_hub_logs`.

### T48 — Discover get_device_history (manage_logs_diagnostics)

```json
{
  "setup_prompt": "Create a virtual switch called 'BAT History Test'. Turn it on and off a few times.",
  "test_prompt": "Show me the event history for 'BAT History Test' over the last 24 hours.",
  "teardown_prompt": "Delete the virtual device 'BAT History Test'."
}
```

**Expected v0.8.0**: Discovers `manage_logs_diagnostics` → `get_device_history`.

### T49 — Discover get_hub_performance (manage_logs_diagnostics)

```json
{
  "test_prompt": "What's the hub's current performance? Memory, temperature, and database size."
}
```

**Expected v0.8.0**: Discovers `manage_logs_diagnostics` → `get_hub_performance`.

### T50 — Discover device_health_check (manage_logs_diagnostics)

```json
{
  "test_prompt": "Run a health check on all my devices. Are any stale or offline?"
}
```

**Expected v0.8.0**: Discovers `manage_logs_diagnostics` → `device_health_check`.

### T51 — Discover get_debug_logs (manage_logs_diagnostics)

```json
{
  "test_prompt": "Show me the MCP debug logs."
}
```

**Expected v0.8.0**: Discovers `manage_logs_diagnostics` → `get_debug_logs`.

### T52 — Discover generate_bug_report (manage_logs_diagnostics)

```json
{
  "test_prompt": "Generate a bug report with diagnostics that I can submit to GitHub."
}
```

**Expected v0.8.0**: Discovers `manage_logs_diagnostics` → `generate_bug_report`.

### T53 — Discover get_rule_diagnostics (manage_logs_diagnostics)

```json
{
  "setup_prompt": "Create a test rule called 'BAT Diag Test' with a time trigger and a log action. Mark as test rule.",
  "test_prompt": "Run diagnostics on the rule 'BAT Diag Test'. Give me a comprehensive health report.",
  "teardown_prompt": "Delete the rule 'BAT Diag Test'."
}
```

**Expected v0.8.0**: Discovers `manage_logs_diagnostics` → `get_rule_diagnostics`.

### T54 — Discover set_log_level (manage_logs_diagnostics)

```json
{
  "test_prompt": "Change the MCP log level to 'warn', then show me the current logging status.",
  "teardown_prompt": "Set the MCP log level back to 'info'."
}
```

**Expected v0.8.0**: Discovers `manage_logs_diagnostics` → `set_log_level` and → `get_logging_status`.

### T55 — Discover list_captured_states (manage_logs_diagnostics)

```json
{
  "test_prompt": "Are there any captured device state snapshots saved? List them."
}
```

**Expected v0.8.0**: Discovers `manage_logs_diagnostics` → `list_captured_states`.

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

### T59 — Discover clear_debug_logs (manage_logs_diagnostics)

```json
{
  "test_prompt": "Clear all the MCP debug logs. Then confirm they're empty by checking them."
}
```

**Expected v0.8.0**: Discovers `manage_logs_diagnostics` → `clear_debug_logs`, then → `get_debug_logs`.

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
  "test_prompt": "I want to create a virtual device but I'm not sure what types are available. Show me the full parameter schema."
}
```

**Expected**: Calls `manage_virtual_devices()` catalog to see `create_virtual_device` schema.

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

Casual natural language prompts that must route to the correct gateway.

### T70 — "Check my hub" → hub admin

```json
{
  "test_prompt": "Check up on my hub. How's it doing?"
}
```

**Expected**: Routes to `get_hub_health` or `get_hub_details`.

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
4. `disable_rule` (core)
5. `manage_rules_admin(tool=export_rule)` (gateway)
6. `manage_rules_admin(tool=delete_rule)` (gateway)

### T81 — Virtual device workflow

```json
{
  "test_prompt": "1. Create a virtual switch called 'BAT Workflow Switch'.\n2. List virtual devices to confirm it exists.\n3. Turn it on.\n4. Check its state.\n5. Delete the virtual device.\nReport each step."
}
```

**Expected (v0.8.0)**: Mix of gateway (create/list/delete virtual devices) and core (send_command, get_attribute).

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

**Expected**: All via `manage_logs_diagnostics` gateway (4 different tools).

### T84 — Cross-gateway workflow

```json
{
  "test_prompt": "Gather a comprehensive audit:\n1. List my rooms\n2. List virtual devices\n3. List hub variables\n4. List files in file manager\n5. List item backups\n6. Hub health status\n7. Device health check\nGive me a one-line summary for each."
}
```

**Expected (v0.8.0)**: Uses 7 different gateways: manage_rooms, manage_virtual_devices, manage_hub_variables, manage_files, manage_apps_drivers, manage_hub_admin, manage_logs_diagnostics.

### T85 — Rule with virtual device end-to-end

```json
{
  "test_prompt": "1. Create a virtual motion sensor called 'BAT Motion'\n2. Create a virtual switch called 'BAT Light'\n3. Create a rule called 'BAT Motion Light' that turns on 'BAT Light' when 'BAT Motion' detects motion, then turns it off after 30 seconds. Mark as test rule.\n4. Show me the rule to verify.\n5. Dry-run the rule.\nReport everything.",
  "teardown_prompt": "Delete the rule 'BAT Motion Light'. Delete both virtual devices 'BAT Motion' and 'BAT Light'."
}
```

**Expected**: Mix of manage_virtual_devices (create), core (create_rule, get_rule), manage_rules_admin (test_rule).

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

**Expected**: Could call `get_hub_health` or `device_health_check` or both. Should not call unrelated tools.

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

### T94 — "Performance" ambiguity (hub_admin vs logs_diagnostics)

```json
{
  "test_prompt": "What's the performance of my hub?"
}
```

**Expected**: `get_hub_performance` (in manage_logs_diagnostics), not `get_hub_health` (in manage_hub_admin).

### T95 — User references wrong gateway domain

```json
{
  "test_prompt": "Use the hub admin tools to check my device health."
}
```

**Expected**: `device_health_check` is in `manage_logs_diagnostics`, NOT `manage_hub_admin`. AI should find the right gateway despite the misdirection.

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
  "test_prompt": "Try to use manage_hub_admin to call manage_rooms. Report what happens."
}
```

**Expected**: Error — "Cannot call a gateway from within a gateway."

### T105 — Missing required args through gateway (v0.8.0)

```json
{
  "test_prompt": "Call manage_rooms with tool='get_room' but don't specify which room. Report what happens."
}
```

**Expected**: `get_room` returns validation error about missing room parameter.

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

Run these prompts on BOTH v0.7.7 (all 74 on tools/list) and v0.8.0 (18 + 8 gateways) to compare.

### T110 — Tool count verification

```json
{
  "test_prompt": "How many MCP tools are available? List all of them."
}
```

**v0.7.7**: 74 tools listed. **v0.8.0**: 26 items (18 core + 8 gateways). Compare token usage, completeness.

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

**v0.7.7**: Calls `get_hub_logs` directly. **v0.8.0**: Via `manage_logs_diagnostics`. Compare discovery.

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

### T120 — All 8 gateways in one session (v0.8.0)

```json
{
  "test_prompt": "For each of these, just read what's available (don't modify anything):\n1. List rooms\n2. List hub variables\n3. List virtual devices\n4. List files in file manager\n5. List item backups\n6. Hub health status\n7. Device health check\n8. MCP debug logs\nConfirm each one worked."
}
```

**Expected**: 8 gateway calls across 7 different gateways. All should succeed.

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

## Excluded Tests (Destructive — Manual Only)

These operations are too destructive for automated testing. Test manually with extreme caution:

| Operation | Tool | Why Excluded |
|-----------|------|--------------|
| Reboot hub | `reboot_hub` | 1-3 min downtime, kills automations |
| Shutdown hub | `shutdown_hub` | Requires physical restart |
| Z-Wave repair | `zwave_repair` | 5-30 min, devices unresponsive |
| Delete real device | `delete_device` | Permanent, no undo |
| Install app/driver | `install_app/driver` | Modifies hub code |
| Update app/driver code | `update_app_code/driver_code` | Modifies production code |
| Delete app/driver | `delete_app/driver` | Permanent code removal |
| Restore item backup | `restore_item_backup` | Overwrites current code |
| Set HSM | `set_hsm` | Changes security system state |
| Set Mode | `set_mode` | Changes hub mode (may trigger automations) |

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

### Results Template

| Test | Pass/Fail | Tools Called | Gateway Used | Turns | Duration | Notes |
|------|-----------|-------------|--------------|-------|----------|-------|
| T01  |           |             |              |       |          |       |

---

## Coverage Summary

### By Section

| Section | Tests | Purpose |
|---------|-------|---------|
| 1. Core Tools | T01-T18 | 18 ungrouped tools work directly |
| 2. Gateway Discovery | T20-T59 | LLM finds all proxied tools without hints |
| 3. Gateway Behavior | T60-T65 | Catalog mode, skip-catalog, errors |
| 4. Natural Language | T70-T79 | Casual prompts route correctly |
| 5. Multi-Step Workflows | T80-T86 | Complex multi-tool/gateway scenarios |
| 6. Ambiguity | T90-T96 | Correct routing under ambiguous prompts |
| 7. Edge Cases | T100-T108 | Error handling and boundary conditions |
| 8. Comparison | T110-T116 | v0.7.7 vs v0.8.0 regression |
| 9. Stress | T120-T122 | Many calls, rapid cycles, pagination |

### Tool Coverage (non-destructive tools only)

All 74 tools are covered by at least one test, excluding the destructive operations listed in the Excluded Tests table. Safe tools have standalone test coverage; destructive tools are documented for manual-only testing.

**Total: 94 test scenarios** (plus 10 excluded destructive operations documented for manual testing)
