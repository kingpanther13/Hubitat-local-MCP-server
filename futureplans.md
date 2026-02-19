# Future Plans

> **Blue-sky ideas** — everything below is speculative and needs further research to determine feasibility. None of these features are guaranteed or committed to.
>
> **Status key:** `[ ]` = not started | `[~]` = in progress / partially done | `[x]` = completed | `[?]` = needs research / feasibility unknown
>
> **Feasibility research conducted February 2025.** Each item now includes a difficulty rating (1–5), effort estimate, and implementation notes based on a thorough codebase analysis.
>
> **Difficulty key:** `1` = trivial | `2` = straightforward | `3` = moderate | `4` = complex | `5` = extremely complex
>
> **Effort key:** `S` = small (hours) | `M` = medium (1–3 days) | `L` = large (4+ days)

---

## Rule Engine Enhancements

### Trigger Enhancements

- [x] **Conditional triggers (evaluate at trigger time)** — `Difficulty: 1 | Effort: S`
  > *Already implemented.* The `evaluateTriggerCondition()` method evaluates a per-trigger `condition` field using the full condition system. Every trigger handler already calls this. May benefit from better documentation and MCP tool schema updates to make the feature more discoverable.

- [x] **Cron/periodic triggers** — `Difficulty: 1 | Effort: S`
  > *Already implemented.* The `periodic` trigger type internally generates cron expressions via `schedule()`. To expose raw cron support, add a `cron` sub-type that passes user-provided cron expressions directly. Hubitat accepts standard 7-field cron expressions. User-provided strings would need validation.

- [ ] **Endpoint/webhook triggers** — `Difficulty: 3 | Effort: M`
  > *Feasible.* Add a single dispatcher endpoint (e.g., `/mcp/webhook?ruleKey=<key>`) in the parent app's `mappings` block. The parent looks up the child rule by key and calls `executeRule("webhook", webhookEvt)` with the request body/params as event data. Per-rule dynamic paths are not possible since `mappings` are compile-time, but a shared endpoint with a query parameter works. The OAuth access token already protects the path.
  >
  > **Implementation plan:**
  > 1. Add `GET/POST /webhook` mapping to parent app
  > 2. Add `webhook` trigger type to child app's `subscribeToTriggers()`
  > 3. Parent dispatches to matching child rule via key lookup
  > 4. Package request body, headers, and query params into pseudo-event for variable substitution

- [ ] **Hub variable change triggers** — `Difficulty: 3 | Effort: M`
  > *Partially feasible.* Hubitat does not expose a native `subscribe()` for hub variable changes. Two approaches: **(A)** Polling — use `schedule()` every 5–10 seconds to compare current value against last known value in `atomicState`. Adds latency and hub load. **(B)** Variable Connector workaround — Hubitat's Variable Connector feature (firmware ≥ 2.3.4) exposes hub variables as device attributes, which can be subscribed to via the existing `device_event` trigger. Option B is cleaner but requires the user to create Variable Connector devices.
  >
  > **Implementation plan:**
  > 1. Add `variable_change` trigger type with configurable polling interval
  > 2. Store last-known values in `atomicState.variableSnapshots`
  > 3. On each poll cycle, compare and fire rule if changed
  > 4. Document Variable Connector approach as the preferred alternative

- [ ] **System start trigger** — `Difficulty: 2 | Effort: S`
  > *Feasible.* Hubitat supports `subscribe(location, "systemStart", handler)`. Add a `system_start` trigger type. After hub reboot, the app restores, `initialize()` → `subscribeToTriggers()` runs, and the systemStart event fires the rule. Minor edge case: the event may fire before all apps finish restoring — needs testing on hardware.
  >
  > **Implementation plan:**
  > 1. Add `system_start` trigger type to child app
  > 2. Subscribe to `location "systemStart"` event in `subscribeToTriggers()`
  > 3. Handler calls `executeRule("system_start")`

- [ ] **Date range triggers** — `Difficulty: 2 | Effort: S`
  > *Feasible.* Better implemented as a condition than a trigger. A `date_range` condition type checks `new Date()` against start/end dates, following the same pattern as `time_range` and `days_of_week`. If implemented as a trigger, use `schedule()` to fire at range start. `java.util.Calendar` is available in the sandbox.
  >
  > **Implementation plan:**
  > 1. Add `date_range` condition type to `evaluateCondition()`
  > 2. Accept `startDate` and `endDate` (ISO format)
  > 3. Optionally add a `date_range` trigger that schedules a one-time event at range start

### Condition Enhancements

- [ ] **Required Expressions (rule gates) with in-flight action cancellation** — `Difficulty: 4 | Effort: L`
  > *Feasible but complex.* A "gate" is a condition continuously monitored during action execution. If it becomes false, in-flight delayed actions are cancelled. The existing `cancelledDelayIds` mechanism provides the foundation for cancellation. The gate needs its own `subscribe()` calls for relevant devices, with a handler that checks the gate condition and marks all pending delay IDs as cancelled. This is the most architecturally complex enhancement — it requires asynchronous monitoring during delayed action chains and careful state management.
  >
  > **Implementation plan:**
  > 1. Add `requiredExpressions` array to rule structure alongside `conditions`
  > 2. Subscribe to gate-relevant device events separately
  > 3. Gate handler evaluates conditions and cancels all active delays if false
  > 4. Track all active delay IDs per rule in `atomicState.activeDelayIds`
  > 5. Add cleanup logic when rule execution completes normally

- [ ] **Full boolean expression builder (AND/OR/XOR/NOT with nesting)** — `Difficulty: 4 | Effort: L`
  > *Feasible.* Replace the flat conditions array + `conditionLogic` toggle with a recursive tree structure: `{operator: "AND", operands: [...]}`. Rewrite `evaluateConditions()` as a tree walker. NOT wraps a single operand; XOR checks exactly one true. The existing `evaluateCondition()` for leaf nodes is already modular. Main challenge is MCP tool ergonomics and backward compatibility — the flat array format would need migration logic.
  >
  > **Implementation plan:**
  > 1. Define tree data structure with `operator` and `operands` fields
  > 2. Implement recursive `evaluateConditionTree()` method
  > 3. Support both legacy flat format and new tree format (migration path)
  > 4. Update `create_rule`/`update_rule` tool schemas
  > 5. Update `describeCondition()` for recursive formatting

- [ ] **Private Boolean per rule** — `Difficulty: 2 | Effort: S`
  > *Feasible.* Already achievable via `atomicState.localVariables`. Add a dedicated `set_rule_boolean` action type that calls `parent.setRuleBooleanOnChild(targetRuleId, boolName, value)`, which updates the target child's local variables. Add a `rule_boolean` condition type that reads a specific rule's local variables. The parent mediates cross-rule access via `getChildAppById()`.
  >
  > **Implementation plan:**
  > 1. Add `set_rule_boolean` action type to child app
  > 2. Add `rule_boolean` condition type to `evaluateCondition()`
  > 3. Add `setRuleBooleanOnChild()` method to parent app
  > 4. Update MCP tool schemas

### Action Enhancements

- [ ] **Fade dimmer over time** — `Difficulty: 3 | Effort: M`
  > *Feasible.* Many dimmers natively support `setLevel(level, duration)` which the codebase already uses. For a software fade: calculate step size and interval, then use `runIn()` with incremental steps. Example: 0→100 over 60s = 5% every 3 seconds (20 steps). Limit steps to ~20–30 to avoid overwhelming the hub's scheduler. Share the stepping utility with color temperature fading and ramp actions.
  >
  > **Implementation plan:**
  > 1. Add `fade_dimmer` action type with `startLevel`, `endLevel`, `durationSeconds`
  > 2. Implement `rampValue()` utility for stepped `runIn()` scheduling
  > 3. Use `[overwrite: false]` on `runIn()` for non-conflicting callbacks
  > 4. Fall back to native `setLevel(level, duration)` when device supports it

- [ ] **Change color temperature over time** — `Difficulty: 3 | Effort: M`
  > *Feasible.* Same pattern as fade dimmer. Use the shared `rampValue()` utility with `setColorTemperature()` calls at each step. Some devices support `setColorTemperature(temp, level, duration)` natively. Temperature ranges vary by device (typically 2000K–6500K).
  >
  > **Implementation plan:**
  > 1. Add `fade_color_temperature` action type with `startTemp`, `endTemp`, `durationSeconds`
  > 2. Reuse the `rampValue()` utility from fade dimmer
  > 3. Handle integer rounding for temperature steps

- [ ] **Per-mode actions** — `Difficulty: 2 | Effort: S`
  > *Feasible.* Add a `per_mode` action type containing a map of mode-name to action-list. At execution time, check `location.mode` and execute the matching action list. Structurally similar to `if_then_else` but with multiple branches. The existing `if_then_else` with a `mode` condition already provides this capability less ergonomically.
  >
  > **Implementation plan:**
  > 1. Add `per_mode` action type with `modeActions` map
  > 2. Look up `location.mode` at execution time
  > 3. Execute matching action list using existing `executeAction()` infrastructure

- [ ] **Wait for Event / Wait for Expression** — `Difficulty: 5 | Effort: L`
  > *Partially feasible.* Requires pausing execution mid-stream until an external event occurs. In Hubitat's single-threaded model, there is no blocking wait. Implementation: save current execution state (action index, context) to `atomicState`, subscribe to the target event, return from execution, then resume when the event fires — similar to the `delay` pattern but event-triggered. A mandatory timeout is essential. Race conditions between the wait subscription and other triggers are possible.
  >
  > **Implementation plan:**
  > 1. Add `wait_for_event` action type with target device/attribute and mandatory timeout
  > 2. Save execution state to `atomicState` (same pattern as `delay`)
  > 3. Create temporary subscription for the target event
  > 4. On event fire or timeout, resume execution from saved state
  > 5. Clean up subscription after resume

- [ ] **Repeat While / Repeat Until** — `Difficulty: 4 | Effort: L`
  > *Partially feasible.* Extend the existing `repeat` action to evaluate conditions before/after each iteration. Critical safety: hard cap at 100 iterations (matching existing `repeat` cap), mandatory max iteration parameter, and loop guard protection. If the loop body contains delays, each iteration needs the save-state/resume pattern, making implementation extremely complex. Recommend supporting synchronous loops only (no delays in body) initially.
  >
  > **Implementation plan:**
  > 1. Add `repeat_while` and `repeat_until` action types
  > 2. Evaluate condition via `evaluateCondition()` each iteration
  > 3. Enforce mandatory `maxIterations` cap (≤100)
  > 4. Phase 1: synchronous loops only (no delay actions in body)
  > 5. Phase 2 (future): delay-compatible loops with state persistence

- [ ] **Rule-to-rule control** — `Difficulty: 2 | Effort: M`
  > *Feasible.* Add `enable_rule`, `disable_rule`, and `trigger_rule` action types. Child calls parent, which looks up the target child and invokes existing `enableRule()`/`disableRule()`/`executeRule()`. To prevent cross-rule ping-pong loops, pass a "trigger chain depth" counter and refuse execution beyond depth 5.
  >
  > **Implementation plan:**
  > 1. Add `enable_rule`, `disable_rule`, `trigger_rule` action types
  > 2. Implement `triggerChildRule(targetRuleId, depth)` in parent
  > 3. Add depth counter to `executeRule()` to prevent cascading loops
  > 4. Validate target rule exists via `getChildAppById()`

- [ ] **File write/append/delete** — `Difficulty: 2 | Effort: S`
  > *Feasible.* The parent already has `uploadHubFile()`/`downloadHubFile()` wrappers. New action types `file_write`, `file_append`, `file_delete` call parent methods. Append does a read-modify-write cycle (not atomic). Requires Hub Admin Write gate. Variable substitution in content enables dynamic log files.
  >
  > **Implementation plan:**
  > 1. Add `file_write`, `file_append`, `file_delete` action types
  > 2. Child calls `parent.writeFileFromRule(fileName, content, mode)`
  > 3. Validate filenames against `[A-Za-z0-9._-]` pattern
  > 4. Apply Hub Admin Write gate for safety

- [ ] **Music/siren control** — `Difficulty: 2 | Effort: S`
  > *Feasible.* Convenience wrappers around existing `device_command`. Hubitat has `capability.musicPlayer` (play, pause, stop, setVolume, playTrack) and `capability.alarm` (both, siren, strobe, off). The existing `speak` action demonstrates the pattern for TTS with volume. These would be ergonomic action types with built-in validation for the right capabilities.
  >
  > **Implementation plan:**
  > 1. Add `play_music` action type with device, command, volume, track params
  > 2. Add `activate_siren` action type with device, mode (siren/strobe/both/off)
  > 3. Validate device has required capability before execution

- [x] **Custom Action (any capability + command)** — `Difficulty: 1 | Effort: S`
  > *Already implemented.* The existing `device_command` action type accepts any device ID, command, and parameters via dynamic invocation (`device."${command}"(*params)`). This is the "any capability + command" feature.

- [ ] **Disable/Enable a device** — `Difficulty: 1 | Effort: S`
  > *Feasible (partially done).* The `update_device` MCP tool already supports the `enabled` property via the internal `/device/disable` endpoint. A new `set_device_enabled` rule action type wraps the same call. Requires Hub Admin Write. Should warn if the target device is used in active rule triggers.
  >
  > **Implementation plan:**
  > 1. Add `set_device_enabled` action type with `deviceId` and `enabled` boolean
  > 2. Call `parent.setDeviceEnabled(deviceId, enabled)`
  > 3. Check if device is used in any rule triggers and warn

- [ ] **Ramp actions (continuous raise/lower)** — `Difficulty: 3 | Effort: M`
  > *Partially feasible.* Same software-stepping pattern as fade dimmer/color temp. A generic `ramp` action targets any numeric attribute. True continuous raise/lower (like holding a physical dimmer button) uses `startLevelChange(direction)` / `stopLevelChange()` but can't be time-bounded from software. Shares the `rampValue()` utility with fade actions.
  >
  > **Implementation plan:**
  > 1. Add `ramp` action type with device, attribute, start, end, duration, steps
  > 2. Reuse `rampValue()` utility from fade actions
  > 3. For devices with `startLevelChange`/`stopLevelChange`, offer a hardware ramp option

- ~~[ ] **Ping IP address**~~ — `Difficulty: 3 | Effort: M`
  > *Not directly feasible.* Hubitat's sandboxed Groovy environment does not provide ICMP, `HubAction`, raw sockets, or `Runtime.exec()`. Only driver code can use `HubAction` for certain protocols.
  >
  > **Alternative: HTTP reachability check** *(added below as a replacement item)*

- [ ] **HTTP reachability check** *(alternative to Ping)* — `Difficulty: 2 | Effort: S`
  > *Feasible.* Use `httpGet()` to make an HTTP request to the target IP and interpret success/failure as reachable/unreachable. Not a true ICMP ping, but achieves network reachability testing for devices with web servers. Another option: control a community driver that exposes ping functionality via the existing `device_command` action.
  >
  > **Implementation plan:**
  > 1. Add `http_check` action type with target URL and timeout
  > 2. Use `httpGet()` in a try-catch block
  > 3. Set result in a variable (reachable/unreachable) for use in conditions
  > 4. Document as "HTTP reachability check" rather than "ping"

### Variable System

- [ ] **Hub Variable Connectors (expose as device attributes)** — `Difficulty: 4 | Effort: L`
  > *Partially feasible.* Hubitat's built-in Variable Connector feature (firmware ≥ 2.3.4) already handles hub variables. For MCP rule engine variables, exposing them as device attributes requires: (1) a custom virtual device driver, (2) the parent creating an instance via `addChildDevice()`, (3) the parent updating attributes via `sendEvent()` on variable writes. The custom driver adds a third file to the project and install complexity.
  >
  > **Implementation plan:**
  > 1. Create `MCP Variable Connector` driver (new Groovy file)
  > 2. Parent auto-creates a child device on first variable write (or on demand)
  > 3. Extend `setRuleVariable()` to also `sendEvent()` on the connector device
  > 4. Add driver to HPM package manifest
  > 5. Document that hub variables should use Hubitat's built-in connectors instead

- [ ] **Variable change events** — `Difficulty: 3 | Effort: M`
  > *Feasible.* For MCP rule engine variables: extend `setRuleVariable()` to fire `sendLocationEvent(name: "ruleVariableChanged", value: varName, data: newValue)`. Child rules with a `variable_change` trigger subscribe to this event. For hub variables: use the polling approach from "Hub variable change triggers" above or leverage Variable Connectors.
  >
  > **Implementation plan:**
  > 1. Add `sendLocationEvent()` call to parent's `setRuleVariable()`
  > 2. Add `variable_change` trigger type to child app
  > 3. Child subscribes to `location "ruleVariableChanged"` event
  > 4. Filter by variable name in the handler

- [ ] **Local variable triggers** — `Difficulty: 2 | Effort: S`
  > *Feasible.* After `set_local_variable` or `variable_math` modifies a local variable, check for matching `local_variable_change` triggers and re-trigger asynchronously via `runIn(0, handler)`. High risk of infinite loops if a rule triggers itself — recommend only firing from external changes (another rule setting this rule's local variable via rule-to-rule control). The loop guard provides a safety net.
  >
  > **Implementation plan:**
  > 1. Add `local_variable_change` trigger type
  > 2. After local variable modification, schedule async re-evaluation
  > 3. Add `triggerSource` tracking to prevent self-triggering loops
  > 4. Rely on loop guard as safety net

---

## Built-in Automation Equivalents

> **Philosophy: prefer native Hubitat apps.** The MCP server was built to complement Hubitat, not replace it. These native apps (Room Lighting, Mode Manager, Button Controller, etc.) are well-maintained, have proper UIs, and are battle-tested. The MCP can already interact with the *effects* of these apps — it can read/set modes, control devices, trigger on device events, and see virtual devices they create.
>
> **The AI assistant is the wizard.** Rather than building dedicated wizard tools that generate MCP rules to replicate what native apps already do, the AI can compose rules on the fly using existing `create_rule` and the full rule engine. Dedicated MCP tooling for these patterns is **low priority** and would only be implemented if the MCP genuinely cannot interact with the native app's functionality in some way. Each item will be reviewed on a case-by-case basis.

- [ ] **Room Lighting (room-centric lighting with vacancy mode)** — `Low priority`
  > *Native app preferred.* Hubitat's built-in Room Lighting app handles this well. The MCP can already control all the same devices, trigger on motion events, and use `if_then_else` / `delay` / `cancel_delayed` to build equivalent logic via `create_rule` if needed. No dedicated MCP tool required unless a gap is identified where MCP cannot interact with Room Lighting's behavior.

- [ ] **Zone Motion Controller (multi-sensor zones)** — `Low priority`
  > *Native app preferred.* Hubitat's built-in Zone Motion Controller creates a virtual motion device that aggregates multiple sensors. If the user adds this virtual device to MCP's selected devices, MCP can already see and trigger on it. The AI can also replicate the logic using `create_virtual_device` + `create_rule` with multi-device triggers if needed. Only implement if MCP cannot adequately interact with the native app's output device.

- [ ] **Mode Manager (automated mode changes)** — `Low priority`
  > *Native app preferred.* Hubitat's built-in Mode Manager handles time-based and presence-based mode changes. The MCP can already read/set modes via `get_modes`/`set_mode`, trigger on `mode_change`, and build time/presence-triggered rules that call `set_mode`. No dedicated tool needed unless a specific interaction gap is found.

- [ ] **Button Controller (streamlined button-to-action mapping)** — `Low priority`
  > *Native app preferred.* Hubitat's built-in Button Controller handles this natively. The MCP rule engine already has `button_event` triggers with full support for button numbers (1–20) and action types (pushed/held/doubleTapped/released). The AI can create these rules directly via `create_rule`. No dedicated tool needed.

- [ ] **Thermostat Scheduler (schedule-based setpoints)** — `Low priority`
  > *Native app preferred.* Hubitat's built-in Thermostat Scheduler handles schedule-based setpoints. The MCP rule engine already has `time` triggers, `set_thermostat` actions, `mode` and `days_of_week` conditions — the AI can compose schedule rules directly. No dedicated tool needed unless MCP cannot interact with the native scheduler's effects.

- [ ] **Lock Code Manager** — `Low priority — review needed`
  > *May warrant a dedicated tool.* Hubitat's built-in Lock Code Manager handles code management via a UI. The MCP can already send lock code commands via `send_command` (`setCode`, `deleteCode`) and read `lockCodes`/`lastCodeName` attributes, so basic interaction is possible today. However, the native app's internal code inventory and temporary code scheduling are not directly accessible. A dedicated tool may add value for programmatic code management if the native app's outputs prove insufficient. **Needs case-by-case review.**

- ~~[ ] **Groups and Scenes (Zigbee group messaging)**~~ — `Not feasible`
  > *Not feasible.* The `zigbee` object and `sendHubCommand()` with `Protocol.ZIGBEE` are only available in drivers, not apps. The MCP server is an app and cannot send raw Zigbee commands or manage Zigbee group IDs. Zigbee group management is handled by closed-source platform internals with no documented HTTP API endpoints.
  >
  > **Alternatives already available:**
  > - **Leverage built-in Groups and Scenes app**: Guide users to create Zigbee groups via the built-in app, then control the resulting group activator device through MCP's `send_command` (group activator devices are regular switch/dimmer devices that MCP can already control)
  > - **Software-level group control**: Create rules that send commands to multiple devices sequentially — already possible via multi-device rules or `device_command` actions
  > - **Scene capture/restore**: The existing `capture_state`/`restore_state` actions provide scene-like functionality across multiple devices

---

## HPM & App/Integration Management

- [ ] **Search HPM repositories by keyword** — `Difficulty: 2 | Effort: S`
  > *Feasible.* HPM uses a public GraphQL API at `https://hubitatpackagemanager.azurewebsites.net/graphql`. A `search_hpm_packages` tool sends a GraphQL query via `httpPost()` and returns matching packages with name, description, author, and manifest URL. The master repository list at `https://raw.githubusercontent.com/HubitatCommunity/hubitat-packagerepositories/master/repositories.json` provides offline browsing.
  >
  > **Implementation plan:**
  > 1. Create `search_hpm_packages` MCP tool
  > 2. Send GraphQL `Search` query to the Azure endpoint
  > 3. Parse and return results with pagination for large result sets
  > 4. Cache results in `state` to reduce API calls

- [ ] **Install/uninstall packages via HPM** — `Difficulty: 4 | Effort: L`
  > *Partially feasible.* HPM has no programmatic API — it's purely UI-driven. **Bypass approach**: fetch the package manifest JSON, download each app/driver source, and install via existing `install_app`/`install_driver` tools. However, packages installed this way won't appear in HPM's "Installed" list, creating a fragmented experience. Uninstall requires removing running app instances (not just code) via poorly documented `/installedapp/` endpoints.
  >
  > **Implementation plan:**
  > 1. Create `install_package` MCP tool using the bypass approach
  > 2. Fetch manifest → download sources → install via existing tools
  > 3. Track installed packages in File Manager for update checking
  > 4. Document the limitation: HPM won't know about these installations
  > 5. For uninstall: `delete_app`/`delete_driver` for code, investigate `/installedapp/disable` for instances

- [ ] **Check for updates across installed packages** — `Difficulty: 3 | Effort: M`
  > *Partially feasible.* For MCP-tracked packages (from the install tool above): fetch each manifest URL and compare versions — same pattern as the existing `checkForUpdate()` for the MCP server itself. For HPM-managed packages: HPM's internal state is not accessible from another app. A parallel tracking database would be needed.
  >
  > **Implementation plan:**
  > 1. Create `check_package_updates` MCP tool
  > 2. Read MCP-tracked package list from File Manager
  > 3. Fetch each manifest URL and compare version fields
  > 4. Return list of packages with available updates
  > 5. Handle fetch failures gracefully (GitHub rate limiting, network issues)

- [ ] **Search for official integrations not yet enabled** — `Difficulty: 3 | Effort: M`
  > *Partially feasible.* No documented endpoint for enumerating available built-in apps. The `list_hub_apps` tool returns user-installed app types, not built-in ones. **Practical approach**: maintain a hardcoded catalog of known official integrations (Hue Bridge, Sonos, Alexa, Google Home, HomeKit, etc.) and check which ones have running instances. The list only changes with firmware updates.
  >
  > **Implementation plan:**
  > 1. Create `list_available_integrations` MCP tool
  > 2. Maintain hardcoded catalog of official integrations with descriptions
  > 3. Check installed app instances to identify which are already enabled
  > 4. Return available-but-not-enabled integrations with setup guidance
  > 5. Update catalog with each MCP server release

- [ ] **Discover community apps/drivers from GitHub, forums, etc.** — `Difficulty: 3 | Effort: M`
  > *Partially feasible.* Primary mechanism: HPM repository search (above). GitHub API search (`https://api.github.com/search/repositories?q=hubitat+driver`) works but is rate-limited to 10 req/min unauthenticated. The Hubitat community forum has no search API. A curated list in File Manager is a practical fallback.
  >
  > **Implementation plan:**
  > 1. Create `search_community_packages` MCP tool
  > 2. Primary: search HPM repositories via GraphQL
  > 3. Secondary: optional GitHub API search with rate-limit handling
  > 4. Return combined results with source attribution
  > 5. Optionally maintain a curated popular-packages list in File Manager

---

## Dashboard Management

- [ ] **Create, modify, delete dashboards programmatically** — `Difficulty: 4 | Effort: L`
  > *Feasible via internal HTTP endpoints (needs empirical testing).* Hubitat's dashboard system uses a parent-child app pattern: "Hubitat Dashboard" is the parent, each individual dashboard is a child app. Deep research uncovered the `/installedapp/createchild/` internal endpoint that the web UI uses.
  >
  > **Key discoveries:**
  > - **Dashboard listing**: `GET /dashboard/all?pinToken=<token>` or via `GET /hub2/appsList` filtering for dashboard children
  > - **Dashboard creation**: `GET /installedapp/createchild/hubitat/Dashboard/parent/{dashboardParentAppId}` — creates a new child dashboard under the parent app. Returns a redirect to `/installedapp/configure/{newChildId}`
  > - **Dashboard layout read**: `GET /apps/api/<parentAppId>/dashboard/<childAppId>/layout?access_token=<token>`
  > - **Dashboard layout write**: `POST /apps/api/<parentAppId>/dashboard/<childAppId>/layout` with Bearer token auth
  > - **Dashboard deletion**: Likely `POST /installedapp/configure/{childId}` with remove action, or `GET /installedapp/remove/{childId}` (exact endpoint needs testing)
  > - **Child app type**: namespace `hubitat`, name `Dashboard` (confirmed from error messages in community forums)
  >
  > **Important caveats:**
  > - `addChildApp()` in Groovy **cannot** create dashboard children from the MCP app (parent mismatch), so the HTTP endpoint approach is required
  > - The `createchild` endpoint returns an HTTP redirect (302), not JSON — need to extract new child ID from the Location header
  > - Post-creation configuration (dashboard name, authorized devices) requires a separate POST to `/installedapp/configure/{id}` with form-encoded data
  > - The Dashboard parent app must already be installed on the hub
  > - The `pinToken` for `/dashboard/all` needs to be obtained from the Dashboard parent app or may not be required for local API calls
  > - Firmware ≥ 2.3.9 introduced "Easy Dashboard" as an alternative — its internal structure may differ
  > - All endpoints are undocumented and may change across firmware versions
  >
  > **Implementation plan:**
  > 1. Discover Dashboard parent app ID via `GET /hub2/appsList` (filter by app name)
  > 2. Create `create_dashboard` tool: call `GET /installedapp/createchild/hubitat/Dashboard/parent/{parentId}`, extract new child ID from redirect
  > 3. Configure new dashboard: `POST /installedapp/configure/{newId}` with name and authorized devices
  > 4. Create `list_dashboards` tool via `/dashboard/all` or `/hub2/appsList`
  > 5. Create `get_dashboard_layout` / `update_dashboard_layout` tools for layout JSON read/write
  > 6. Create `delete_dashboard` tool: test `/installedapp/remove/{childId}` endpoint
  > 7. Add Dashboard parent app ID and access token to MCP app preferences
  > 8. Requires Hub Admin Write gate for safety
  > 9. **Phase 1**: Implement list + read/modify layout (known-working endpoints)
  > 10. **Phase 2**: Implement create/delete (requires empirical testing on hub hardware)

---

## Rule Machine Interoperability

> **Feasibility confirmed** — creating/modifying RM rules is not possible (closed-source, undocumented format). However, controlling existing RM rules IS feasible via the `hubitat.helper.RMUtils` class, which is available in the Hubitat app sandbox.

- [ ] **List all RM rules via `RMUtils.getRuleList()`** — `Difficulty: 1 | Effort: S`
  > *Feasible.* Confirmed working. `RMUtils.getRuleList("5.0")` returns RM 5.x rules; `getRuleList()` returns legacy rules. Returns a list suitable for enum options with rule IDs and names.
  >
  > **Implementation plan:**
  > 1. Add `import hubitat.helper.RMUtils` to parent app
  > 2. Create `list_rm_rules` MCP tool
  > 3. Call both `getRuleList("5.0")` and `getRuleList()` for full coverage
  > 4. Handle the case where Rule Machine is not installed

- [ ] **Enable/disable RM rules** — `Difficulty: 2 | Effort: S`
  > *Feasible.* Uses `RMUtils.sendAction(ruleIds, "pauseRule"/"resumeRule", app.label, "5.0")`. "Pause" is equivalent to "disable" in RM terminology.
  >
  > **Implementation plan:**
  > 1. Create `control_rm_rule` MCP tool with `action` parameter
  > 2. Support actions: `pause` (disable), `resume` (enable)
  > 3. Validate rule ID exists via `getRuleList()` first

- [ ] **Trigger RM rule actions via `RMUtils.sendAction()`** — `Difficulty: 2 | Effort: S`
  > *Feasible.* Supported actions: `"runRuleAct"` (execute actions, skip conditions), `"runRule"` (evaluate conditions then run), `"stopRuleAct"` (cancel delayed/repeating actions). Note: `"runRule"` not applicable to Rule 4.x+.
  >
  > **Implementation plan:**
  > 1. Add `run_actions`, `evaluate`, `stop_actions` to the `control_rm_rule` tool
  > 2. Map to RM action strings internally
  > 3. Document which actions work with which RM versions

- [ ] **Pause/resume RM rules** — `Difficulty: 2 | Effort: S`
  > *Feasible.* Same mechanism as enable/disable above. Can be combined into the unified `control_rm_rule` tool.

- [ ] **Set RM Private Booleans** — `Difficulty: 2 | Effort: S`
  > *Feasible.* Uses `RMUtils.sendAction(ruleIds, "setRuleBooleanTrue"/"setRuleBooleanFalse", app.label, "5.0")`. Straightforward API.
  >
  > **Implementation plan:**
  > 1. Add `set_boolean_true` and `set_boolean_false` to `control_rm_rule` tool
  > 2. Accept rule ID and boolean value, map to appropriate sendAction call

- [x] **Hub variable bridge for cross-engine coordination** — `Difficulty: 2 | Effort: S`
  > *Already ~90% implemented.* The existing `set_variable`/`get_variable` tools work with Hubitat's global connector variables via `getGlobalConnectorVariable()`/`setGlobalConnectorVariable()`. These are the same variables Rule Machine reads/writes. Variables set via MCP are immediately visible to RM and vice versa. To formalize: document the convention that shared variables should use hub connector variables.

---

## Integration & Streaming

- [ ] **Event streaming / webhooks (real-time POST of device events)** — `Difficulty: 3 | Effort: M`
  > *Feasible.* Subscribe to device events and `asynchttpPost()` payloads to registered URLs. The rule engine already implements `http_request` actions via `httpPost()`. Use `asynchttpPost` (non-blocking) to avoid blocking the event queue during bursts. Rate limiting is important.
  >
  > **Implementation plan:**
  > 1. Create `configure_webhook` MCP tool to register endpoint URLs and event filters
  > 2. Store webhook configs in `state.webhookSubscriptions`
  > 3. Subscribe to relevant device events in `initialize()`
  > 4. Event handler checks filters, formats payload, and POSTs asynchronously
  > 5. Include rate limiting (configurable events per minute per webhook)
  > 6. Payload format: `{deviceId, label, attribute, value, timestamp, description}`

- ~~[ ] **MQTT client (bridge to Node-RED, Home Assistant, etc.)**~~ — `Difficulty: 4 | Effort: L`
  > *Not directly feasible from an app.* Hubitat's `interfaces.mqtt` API is only available in drivers, not apps. The Groovy sandbox also doesn't allow `java.net.Socket` or arbitrary Java imports for a custom MQTT implementation.
  >
  > **Alternative: Companion driver approach** *(added below)*

- [ ] **MQTT via companion driver** *(alternative to direct MQTT)* — `Difficulty: 4 | Effort: L`
  > *Feasible via workaround.* Create a custom `MCP MQTT Bridge Driver` that uses `interfaces.mqtt` to connect to a broker. The MCP app creates this driver as a child device via `addChildDevice()`. Communication flows through device commands (app→driver: `publish(topic, message)`) and events (driver→app: `sendEvent()` + `subscribe()`). This adds a third Groovy file to the project.
  >
  > **Implementation plan:**
  > 1. Create `MCP MQTT Bridge` driver with `interfaces.mqtt`
  > 2. Driver exposes commands: `connect`, `disconnect`, `publish`, `subscribe`
  > 3. Driver fires events on incoming messages and connection status
  > 4. Parent app creates driver instance via `addChildDevice()`
  > 5. Add `mqtt_publish`, `mqtt_subscribe`, `mqtt_status` MCP tools
  > 6. Add driver to HPM package manifest
  >
  > **Alternative (simpler):** Use the existing `http_request` action to bridge via HTTP-to-MQTT gateways (Node-RED HTTP-in nodes, HiveMQ REST API, EMQX REST API).

---

## Advanced Automation Patterns

> These patterns don't require new MCP tools — the AI assistant can already compose them using existing `create_rule`, `set_variable`, `create_virtual_device`, and other tools. They're documented here as reference patterns showing what's achievable today with the current rule engine. No dedicated wizard tools are planned unless a specific gap is identified.

- [ ] **Occupancy / room state machine** — `No new tools needed`
  > *Already achievable.* The AI can compose this using existing primitives: a hub variable `roomState_<room>` holds state (vacant/occupied/engaged/checking). `device_event` triggers on motion/contact sensors feed into `if_then_else` chains with `set_variable` actions for state transitions. Duration-based triggers handle timeouts. Other rules check room state via `variable` conditions. No dedicated tool required — the AI can build this pattern on request using `create_rule` and `set_variable`.

- [ ] **Presence-based automation (first-to-arrive, last-to-leave)** — `No new tools needed`
  > *Already achievable.* The AI can compose this: a hub variable `homeCount` tracks present people. `device_event` triggers on presence sensors increment/decrement via `variable_math`. Rules with `variable` conditions fire when `homeCount` transitions 0→1 (first arrive) or 1→0 (last leave). All building blocks exist today.

- [ ] **Weather-based triggers** — `No new tools needed`
  > *Already achievable with weather device drivers.* Many Hubitat users have weather drivers installed (OpenWeatherMap, Weather Underground, etc.) that expose weather data as device attributes. If the user selects the weather device in MCP, it's already triggerable via `device_event` triggers and `device_state` conditions — zero code changes needed. For users without a weather driver, the AI could create a `periodic` rule with `http_request` actions to poll a weather API and store results in hub variables.

- [ ] **Vacation mode (random light cycling, auto-lock, energy savings)** — `No new tools needed`
  > *Already achievable.* The AI can compose this using existing primitives: `mode_change` trigger → `capture_state` + lock commands + thermostat setpoints. A `periodic` trigger with `mode` condition cycles random lights (the rule engine has `repeat` actions and `delay` with variable offsets). Mode return triggers `restore_state`. `new Random()` is available in the sandbox for randomized timing. All building blocks exist today.

---

## Monitoring & Diagnostics

- [ ] **Device health watchdog** — `Difficulty: 2 | Effort: S`
  > *Feasible.* The existing `device_health_check` tool is on-demand only. Enhancement: add a scheduled background check (every 4–6 hours) that proactively detects stale/offline devices and low batteries. Push alerts via notification devices. Write results to a CSV for trend analysis. Fire a `mcpDeviceHealthAlert` location event for rule integration.
  >
  > **Implementation plan:**
  > 1. Add `schedule("0 0 */6 ? * *", "runHealthWatchdog")` to parent app
  > 2. Reuse `toolDeviceHealthCheck()` logic
  > 3. Add battery monitoring: check `device.currentValue("battery")` for low levels
  > 4. Send notifications for new stale/low-battery devices
  > 5. Fire `sendLocationEvent(name: "mcpDeviceHealthAlert")` for rule integration
  > 6. Log to CSV for trend tracking

- [ ] **Z-Wave ghost device detection** — `Difficulty: 3 | Effort: M`
  > *Partially feasible (detection only).* Fetch Z-Wave node table via `/hub/zwaveDetails/json`, cross-reference with the device list, and identify nodes with no matching device or with failed states. Automated removal is not possible — ghost removal requires the hub's web UI Z-Wave Details page.
  >
  > **Implementation plan:**
  > 1. Create `detect_zwave_ghosts` MCP tool (requires Hub Admin Read)
  > 2. Fetch Z-Wave node table and device list
  > 3. Cross-reference: nodes with no deviceId or failed states are ghosts
  > 4. Return report with ghost nodes and recommended manual actions
  > 5. Link to hub UI Z-Wave Details page for remediation

- [ ] **Event history / analytics** — `Difficulty: 3 | Effort: M–L`
  > *Partially feasible.* The 7-day limit on `eventsSince()` is a platform constraint. For longer history: add a background scheduler that periodically samples device states and writes to CSV files in File Manager. For analytics: compute event frequency, state duration percentages, min/max/avg for numeric attributes from available history data. Cross-device correlation is computationally expensive in the sandbox.
  >
  > **Implementation plan:**
  > 1. Create `analyze_device_history` MCP tool for analytics on existing 7-day data
  > 2. Add scheduled CSV logging for long-term device state sampling
  > 3. Compute statistics: event frequency, state duration %, numeric min/max/avg
  > 4. Return structured analytics per device
  > 5. Note: computation-heavy analytics may time out with many devices

- [x] **Hub performance trend monitoring** — `Difficulty: 1 | Effort: S`
  > *Mostly already implemented.* The `get_set_hub_metrics` tool records snapshots to CSV, maintains a 500-point rolling window, returns configurable trend points, and includes threshold warnings. **Incremental enhancement:** add scheduled periodic sampling (every 4 hours) instead of only recording when the AI calls the tool. Add trend direction analysis (rate of change, declining memory detection).
  >
  > **Implementation plan (incremental):**
  > 1. Add `schedule("0 0 */4 ? * *", "recordPerformanceSnapshot")` to parent
  > 2. Add trend analysis: compare latest N points for direction/rate of change
  > 3. Alert on sustained declining trends (not just current thresholds)

---

## Notification Enhancements

- [ ] **Pushover integration with priority levels** — `Difficulty: 2 | Effort: M`
  > *Feasible.* Simple `httpPost()` to `https://api.pushover.net/1/messages.json`. The Hubitat ecosystem already has a built-in Pushover driver, but a direct API integration gives more control (priority levels -2 to 2, sounds, supplementary URL, emergency retry/expire).
  >
  > **Implementation plan:**
  > 1. Add Pushover API key and User key to app preferences
  > 2. Create `send_pushover` MCP tool with message, title, priority, sound params
  > 3. `httpPost()` to Pushover API with form-encoded body
  > 4. For emergency priority (2), include `retry` and `expire` parameters
  > 5. Add as a notification channel for rule actions

- [ ] **Email notifications via SendGrid** — `Difficulty: 2 | Effort: M`
  > *Feasible.* JSON POST to `https://api.sendgrid.com/v3/mail/send` with Bearer token auth. The existing code already does JSON POST with custom headers (room save code uses `requestContentType: "application/json"`).
  >
  > **Implementation plan:**
  > 1. Add SendGrid API key, sender email, default recipient to app preferences
  > 2. Create `send_email` MCP tool with to, subject, body, optional html params
  > 3. `httpPost()` to SendGrid v3 API with JSON body and Bearer auth header
  > 4. Add as a notification channel for rule actions

- [ ] **Rate limiting / throttling** — `Difficulty: 2 | Effort: S`
  > *Feasible.* Pure in-app logic using `state`/`atomicState` and `now()`. Store timestamps of recent notifications per channel. Check cooldown before sending. Follow the existing loop guard pattern for sliding-window rate limiting. Configurable per-channel cooldown and max-per-hour limits.
  >
  > **Implementation plan:**
  > 1. Add `state.notificationHistory` tracking per-channel timestamps
  > 2. Check cooldown before each notification send
  > 3. Add configurable thresholds in app preferences
  > 4. Return throttle status in tool response when rate-limited

- [ ] **Notification routing by severity** — `Difficulty: 3 | Effort: M`
  > *Feasible.* Define severity levels (info/warning/critical/emergency). Map each severity to notification channels in app preferences. Dispatch to appropriate channels. Depends on Pushover and SendGrid being implemented first.
  >
  > **Implementation plan:**
  > 1. Define severity levels: info, warning, critical, emergency
  > 2. Add severity-to-channel routing in app preferences
  > 3. Create `send_alert` MCP tool with message and severity params
  > 4. Routing logic reads config and dispatches to matching channels
  > 5. Each channel (Pushover, SendGrid, hub notification device) is a separate method

---

## Additional Ideas

- [ ] **Standalone virtual device creation (independent of MCP app)** — `Difficulty: 2 | Effort: S–M`
  > *Feasible but unverified.* The hub internal API `POST /device/save` may support device creation with `id=""` (Grails convention). The created device would appear in the regular Devices section and persist even if MCP is uninstalled. However: this endpoint is documented for updates, not creation — needs empirical testing. Built-in driver type IDs may not be discoverable via `/hub2/userDeviceTypes`.
  >
  > **Implementation plan:**
  > 1. Test `/device/save` with `id=""` on actual hub hardware
  > 2. Discover built-in driver type IDs for Virtual Switch, Virtual Dimmer, etc.
  > 3. Create `create_standalone_device` MCP tool (Hub Admin Write required)
  > 4. Note: device won't auto-appear in MCP's device list without user selection
  > 5. Fallback: use existing `create_virtual_device` with `isComponent: false`

- [ ] **Scene management (create/modify beyond activate_scene)** — `Difficulty: 3 | Effort: M`
  > *Partially feasible.* Native Groups and Scenes CRUD is not possible (no API). However, the existing `capture_state`/`restore_state` system is already a de facto scene manager. Enhancement: wrap with scene-oriented terminology — `create_scene` (capture), `list_scenes` (list captures), `activate_scene` (restore), `delete_scene` (delete capture). Extend captured attributes beyond switch/level/color to include fan speed, shade position, thermostat setpoints.
  >
  > **Implementation plan:**
  > 1. Create scene alias tools wrapping existing captured state tools
  > 2. Extend `capture_state` to save additional attributes (fan speed, shade position, thermostat)
  > 3. Add `modify_scene` tool that re-captures specific devices in an existing scene
  > 4. Document that these are MCP-managed scenes, not native Hubitat scenes

- [ ] **Energy monitoring dashboard** — `Difficulty: 3 | Effort: M`
  > *Feasible.* Create a `get_energy_summary` tool that aggregates `power` and `energy` attributes across all PowerMeter/EnergyMeter capable devices. Add scheduled CSV logging for trend data. "Dashboard" is a JSON summary in MCP context (no UI rendering), but could optionally write an HTML file to File Manager accessible at `http://<HUB_IP>/local/energy-dashboard.html`.
  >
  > **Implementation plan:**
  > 1. Create `get_energy_summary` MCP tool
  > 2. Find all devices with PowerMeter/EnergyMeter capabilities
  > 3. Aggregate current power (W) and cumulative energy (kWh)
  > 4. Return per-device breakdown and totals
  > 5. Add scheduled CSV logger for energy trend data
  > 6. Optionally: generate static HTML file for visual dashboard

- [ ] **Scheduled automated reports** — `Difficulty: 3 | Effort: M`
  > *Feasible.* Scheduled via `schedule()` with cron expressions. Reports aggregate data from existing monitoring tools (device health, hub performance, rule activity). Save to File Manager as JSON. Push summary via notification devices. For email delivery, use SendGrid integration (if implemented) or `httpPost()` to external services.
  >
  > **Implementation plan:**
  > 1. Add `configure_report_schedule` MCP tool
  > 2. Define report templates: hub health, device status, rule activity, energy
  > 3. Schedule via `schedule()` with user-configured cron expression
  > 4. Aggregate data from existing tool methods
  > 5. Save full report to File Manager as JSON
  > 6. Push summary notification via configured channels

- ~~[ ] **Device pairing assistance (Z-Wave, Zigbee, cloud)**~~ — `Difficulty: 4 | Effort: L`
  > *Not feasible for active pairing.* Device pairing (Z-Wave inclusion, Zigbee pairing) is an interactive, real-time, radio-level operation. The hub's Z-Wave/Zigbee inclusion mode endpoints are undocumented and pairing is a multi-step, timing-sensitive process incompatible with MCP's request-response model. Cloud integrations require OAuth UI flows.
  >
  > **Alternative: Pairing guidance tool** *(added below)*

- [ ] **Device pairing guidance** *(alternative to active pairing)* — `Difficulty: 2 | Effort: S`
  > *Feasible.* A `guide_device_pairing` tool that provides step-by-step textual instructions for using the Hubitat web UI to pair devices. After pairing, the AI can help configure the device (driver selection, room assignment, label, preferences) using existing MCP tools like `update_device`, `send_command`, and room management tools.
  >
  > **Implementation plan:**
  > 1. Create `guide_device_pairing` MCP tool
  > 2. Accept device type (Z-Wave, Zigbee, cloud) and optional model info
  > 3. Return step-by-step instructions with direct links to hub UI pages
  > 4. After pairing, offer to configure via existing MCP tools

---

## Infeasible Items Summary

> The following items have been determined to be not achievable due to platform constraints. They are listed here with explanations and the alternatives that have been added to the plan above.

- ~~**Groups and Scenes (Zigbee group messaging)**~~ — The `zigbee` object and `sendHubCommand(Protocol.ZIGBEE)` are driver-only APIs. No HTTP endpoint exists for Zigbee group management. **→ Use software group commands or the built-in Groups and Scenes app's activator devices via `send_command`**

- ~~**Ping IP address**~~ — No ICMP, raw sockets, or `Runtime.exec()` in the app sandbox. **→ HTTP reachability check added as replacement**

- ~~**MQTT client (direct)**~~ — `interfaces.mqtt` is driver-only. No raw TCP sockets in apps. **→ Companion driver approach added as alternative**

- ~~**Device pairing assistance (active)**~~ — Radio inclusion is interactive and undocumented. MCP's request-response model can't handle multi-step pairing flows. **→ Pairing guidance tool added as alternative**

---

## Recommended Implementation Priority

> Based on the ratio of user value to implementation effort. Excludes items that the AI can already compose using existing tools (occupancy, presence, vacation mode, weather, and native app equivalents like Mode Manager, Button Controller, etc.).

### Phase 1: Quick Wins (Small effort, high value)
1. **Rule Machine Interoperability** (list, control, trigger, booleans) — All use `RMUtils`, implement as 1–2 tools
2. **Search HPM repositories** — Public GraphQL API, immediate discovery value
3. **Rate limiting / throttling** — Pure in-app logic, enables safer notifications
4. **System start trigger** — Single `subscribe()` call
5. **Date range condition** — Follows existing condition patterns
6. **Device health watchdog** — Add scheduled task to existing tool
7. **Private Boolean per rule** — Cross-rule coordination via parent mediation
8. **Disable/Enable a device action** — Wraps existing `update_device` capability
9. **File write/append/delete actions** — Wraps existing parent file methods
10. **Music/siren control actions** — Convenience wrappers around `device_command`

### Phase 2: Core Enhancements (Medium effort, high value)
11. **Webhook triggers** — New endpoint + trigger type
12. **Event streaming / webhooks** — Subscribe + async POST
13. **Pushover integration** — Simple API integration
14. **Email via SendGrid** — Simple API integration
15. **Fade dimmer / color temp** — Shared ramp utility
16. **Rule-to-rule control** — Parent mediation, existing methods
17. **Notification routing** — Layer on top of Pushover/SendGrid
18. **Z-Wave ghost detection** — Node table cross-reference
19. **Variable change events** — Location event on variable write
20. **Per-mode actions** — Multi-branch action type

### Phase 3: Advanced Features (Large effort, specialized value)
21. **Boolean expression builder** — Recursive tree evaluation + migration
22. **Required expressions / gates** — Continuous monitoring architecture
23. **MQTT via companion driver** — Third file, driver development
24. **Dashboard create/modify/delete** — Internal endpoint approach, needs hub testing
25. **Scheduled reports** — Aggregation + scheduling + delivery
26. **Energy monitoring** — Aggregate power/energy across devices
27. **Scene management** — Scene-oriented wrappers around captured state

### Phase 4: Exploratory (Needs testing or has significant limitations)
28. **Hub variable change triggers** — Polling trade-offs
29. **Wait for Event / Expression** — Complex state persistence
30. **Repeat While / Until** — Loop safety concerns
31. **Hub Variable Connectors** — Custom driver dependency
32. **Install packages via HPM** — HPM sync fragmentation
33. **Standalone virtual devices** — API endpoint unverified
34. **Event history / analytics** — Platform 7-day limit

### Low Priority: Native App Equivalents (only if MCP interaction gaps found)
35. **Room Lighting** — Use native app; review if MCP can't interact with its effects
36. **Zone Motion Controller** — Use native app; MCP can already see its virtual device
37. **Mode Manager** — Use native app; MCP already reads/sets modes
38. **Button Controller** — Use native app; MCP already has `button_event` triggers
39. **Thermostat Scheduler** — Use native app; MCP already has `set_thermostat` actions
40. **Lock Code Manager** — Use native app; review if `send_command` proves insufficient
