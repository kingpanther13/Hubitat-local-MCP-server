# Native Rule Machine (Rule 5.1) — Test Matrix

> **Status:** Catalog complete. Behavioral coverage via BAT suite — see `tests/BAT-rm-native-crud.md` (139 scenarios, T300–T452).
>
> **Implementation ticket:** #120 (the tools themselves)
>
> This matrix catalogs every feature of Hubitat Rule Machine 5.1 that the new native-RM CRUD tools (`create_rm_rule` / `update_rm_rule` / `delete_rm_rule` / `get_rm_rule`) must be able to produce and round-trip faithfully. **No row ships unverified.** Behavioral coverage lives in `tests/BAT-rm-native-crud.md` (LLM-driven tests run against a live hub). Unit-test coverage is added by the #120 Phase 2 PR itself, using the existing `ToolSpecBase` + `HubInternalGetMock` harness and inline `script.metaClass.hubInternalPostForm` stubs (pattern in `src/test/groovy/server/ToolAppDriverCodeSpec.groovy`).
>
> Source of truth for this catalog: [RM 5.1 docs](https://docs2.hubitat.com/en/apps/rule-machine/rule-5-1) and [RM main page](https://docs2.hubitat.com/en/apps/rule-machine).

## BAT coverage map

Every matrix section is covered by a T### range in `tests/BAT-rm-native-crud.md`:

| Matrix section | BAT range |
|---|---|
| §1 Rule-level structure + §7 Rule lifecycle verbs | T300–T316 |
| §3 Trigger/condition capabilities + trigger-option variants | T320–T349 |
| §4 Actions (all 13 categories) | T350–T387 |
| §2 Expressions + §4a Conditional + §4l Repeat + §4m Delay/Wait + §5 Variables + §6 Private Boolean | T400–T429 |
| §8 HTTP endpoint surface + edge cases | T430–T452 |

**Critical regression guards (callouts for the #120 Phase 1 findings):**

- **T321 / T346 / T443** — `multiple=true` flag verification on multi-device capability TRIGGER inputs (the flag-poisoning bug from Phase 1)
- **T350 / T358 / T362 / T373 / T379 / T412 / T424** — same `multiple=true` flag verification on multi-device capability ACTION inputs (same Phase 1 bug class, action-side)
- **T442** — orphan-cleanup after a failed mid-wizard create (MUST exercise the cleanup code path, not just pre-validate)
- **T444** — `multiple=true` flag persists through `update_rm_rule` (update-path regression guard)
- **T450 / T451** — negative paths for `update_rm_rule` / `delete_rm_rule` on non-existent IDs

**Aspirational / environment-dependent tests** (may be skipped depending on hub state):

- **T445** — flag-poisoning recovery/self-heal (requires ability to intentionally poison, which may not be safely exposable)
- **T446** — stuck `state.editCond` recovery (requires reaching a stuck state)
- **T449** — Rule Machine not installed (requires a hub without RM; usually can't be safely uninstalled on production hubs)
- **T452** — MCP feature-flag gating (requires the legacy-gating setting to actually exist, which is a #120 Phase 3 concern)
- **T359 / T361 / T372** — SKIP-on-precondition (require pre-existing BAT-prefixed Scene, HSM custom rule, or Room Lighting instance respectively — mark SKIPPED if absent rather than fabricating)

## Legend

| Column | Meaning |
|---|---|
| **Feature** | RM capability or construct being tested |
| **Input schema** | Page + input name(s) in the `configure/json` response that define this feature (filled during fixture capture) |
| **Fixture** | Relative path under `src/test/resources/fixtures/rm_5_1/` holding the captured page JSON that exercises this feature |
| **Unit test** | Spock spec that asserts payload-shape + round-trip correctness (empty = not written yet) |
| **Live smoke** | ✅ / ⚠️ / ❌ — whether the live-hub smoke bundle exercises this feature |
| **Notes** | Implementation gotchas, marshal-flag caveats, known quirks |

**Marshal-flag rule:** for any capability input with `multiple: true`, the tool MUST emit `<name>.type=<capability.X>` + `<name>.multiple=true` + `settings[<name>]=<csv>` as a group in the same POST to `/installedapp/update/json`. If the `.multiple=true` field is omitted, Hubitat silently rewrites the AppSetting DB record's `multiple` flag from true to false, causing runtime marshaling of the setting as a singleton `Device` instead of `List<Device>`. RM then crashes with `IllegalArgumentException: Command 'size' is not supported by device '<firstDeviceLabel>'` on every page render (line 1958), `eventSubscriptions` stays at 0, and the rule is inert until the flag is re-written. The flag is sticky — value-only re-writes do not restore it; only re-POSTing the full three-field group does. See `docs/testing.md` for the full context and BAT regression guards T321 / T346 / T443 / T444 / T450 / T451 for the load-bearing tests.

---

## 1. Rule-level structure

| Feature | Input schema | Fixture | Unit test | Live smoke | Notes |
|---|---|---|---|---|---|
| Create empty rule (name only) | mainPage: `origLabel` | `rule_empty.json` | | ❌ | Verifies `createchild` → initial wizard page |
| Rename existing rule | mainPage: `origLabel` | `rule_rename.json` | | ❌ | Single-input update |
| Set rule notes (comments) | mainPage: `comments` | `rule_comments.json` | | ❌ | textarea input |
| Enable Required Expression | mainPage: `useST` | `rule_req_expr.json` | | ❌ | Unlocks required-expression editor page |
| Set rule to function mode | mainPage: `isFunction` | `rule_function.json` | | ❌ | Rule returns a value |
| Logging: Events | mainPage: `logging` | `rule_logging_events.json` | | ❌ | enum multi-select |
| Logging: Triggers | mainPage: `logging` | (same) | | ❌ | |
| Logging: Actions | mainPage: `logging` | (same) | | ❌ | |
| Display current values | mainPage: `dValues` | `rule_dvalues.json` | | ❌ | bool |
| Delete rule (soft) | N/A — `/installedapp/delete/<id>` | N/A | | ❌ | Returns `{success, message}`, refuses if has children |
| Delete rule (force) | N/A — `/installedapp/forcedelete/<id>/quiet` | N/A | | ❌ | 302 redirect, always succeeds |

## 2. Expression / condition language

Expressions are used in: Required Expression, `IF (expression) THEN`, `Wait for Expression`, `Repeat While Expression`, `Repeat Until Expression`.

| Feature | Input schema | Fixture | Unit test | Live smoke | Notes |
|---|---|---|---|---|---|
| Single condition | TBD | `expr_single.json` | | ❌ | Baseline case |
| `AND` of two conditions | TBD | `expr_and.json` | | ❌ | |
| `OR` of two conditions | TBD | `expr_or.json` | | ❌ | |
| `XOR` of two conditions | TBD | `expr_xor.json` | | ❌ | |
| `NOT` on a condition | TBD | `expr_not.json` | | ❌ | Binds tightest |
| Parenthesized sub-expression | TBD | `expr_parens.json` | | ❌ | |
| Nested sub-expressions | TBD | `expr_nested.json` | | ❌ | Innermost evaluated first |
| Left-to-right equal precedence of AND/OR/XOR | TBD | `expr_precedence.json` | | ❌ | Round-trip must preserve operator order |
| Conditional trigger (single condition attached to one trigger) | selectTriggers: `isCondTrig.<N>` | `cond_trigger.json` | | ❌ | Evaluated AFTER trigger event (contrast required-expression) |

## 3. Trigger / condition capabilities

48 capability types. Each row tests: create a rule with this trigger, round-trip via read, update to a different capability, delete.

### 3a. Device-state capabilities

| Capability | Input name pattern | Fixture | Unit test | Live smoke | Notes |
|---|---|---|---|---|---|
| Acceleration | `tCapab<N>=Acceleration` | `trig_acceleration.json` | | ❌ | `active`/`inactive` |
| Battery | `tCapab<N>=Battery` | `trig_battery.json` | | ❌ | numeric value + comparator |
| Button (triggers only) | `tCapab<N>=Button` | `trig_button.json` | | ❌ | `pushed`/`held`/`doubleTapped`/`released` |
| Carbon dioxide sensor | `tCapab<N>=CarbonDioxide` | `trig_co2.json` | | ❌ | numeric |
| Carbon monoxide sensor | `tCapab<N>=CarbonMonoxide` | `trig_co.json` | | ❌ | `detected`/`clear`/`tested` |
| Contact | `tCapab<N>=Contact` | `trig_contact.json` | | ❌ | `open`/`closed` |
| Custom attribute | `tCapab<N>=Custom` | `trig_custom_attr.json` | | ❌ | Arbitrary device capability |
| Digital switch (triggers only) | `tCapab<N>=DigitalSwitch` | `trig_digital_sw.json` | | ❌ | `switch` event with `type=digital` |
| Dimmer level | `tCapab<N>=DimmerLevel` | `trig_dimmer.json` | | ❌ | numeric + comparator |
| Door | `tCapab<N>=Door` | `trig_door.json` | | ❌ | `open`/`closed` |
| Energy meter | `tCapab<N>=Energy` | `trig_energy.json` | | ❌ | numeric |
| Fan speed | `tCapab<N>=FanSpeed` | `trig_fan_speed.json` | | ❌ | enum of supported speeds |
| Garage door | `tCapab<N>=GarageDoor` | `trig_garage.json` | | ❌ | `open`/`closed`/`opening`/`closing`/`unknown` |
| Gas detector | `tCapab<N>=Gas` | `trig_gas.json` | | ❌ | `clear`/`detected`/`tested` |
| Humidity | `tCapab<N>=Humidity` | `trig_humidity.json` | | ❌ | numeric + comparator |
| Illuminance | `tCapab<N>=Illuminance` | `trig_illuminance.json` | | ❌ | numeric |
| Keypad codes | `tCapab<N>=KeypadCodes` | `trig_keypad.json` | | ❌ | code name |
| Lock | `tCapab<N>=Lock` | `trig_lock.json` | | ❌ | `locked`/`unlocked` |
| Lock codes | `tCapab<N>=LockCodes` | `trig_lock_codes.json` | | ❌ | code names |
| Motion | `tCapab<N>=Motion` | `trig_motion.json` | | ❌ | `active`/`inactive` |
| Music player | `tCapab<N>=MusicPlayer` | `trig_music.json` | | ❌ | `playing`/`paused`/`stopped` |
| Physical dimmer level (triggers only) | `tCapab<N>=PhysicalDimmer` | `trig_phys_dimmer.json` | | ❌ | `level` event with `type=physical` |
| Physical switch (triggers only) | `tCapab<N>=PhysicalSwitch` | `trig_phys_switch.json` | | ❌ | `switch` event with `type=physical` |
| Power meter | `tCapab<N>=Power` | `trig_power.json` | | ❌ | numeric |
| Power source | `tCapab<N>=PowerSource` | `trig_power_src.json` | | ❌ | `mains`/`battery` |
| Presence | `tCapab<N>=Presence` | `trig_presence.json` | | ❌ | `present`/`not present` or `arrives`/`leaves` |
| Shock sensor | `tCapab<N>=Shock` | `trig_shock.json` | | ❌ | `clear`/`detected` |
| Smoke detector | `tCapab<N>=Smoke` | `trig_smoke.json` | | ❌ | `clear`/`detected`/`tested` |
| Sound | `tCapab<N>=Sound` | `trig_sound.json` | | ❌ | `detected`/`not detected` |
| Switch | `tCapab<N>=Switch` | `trig_switch.json` | | ❌ | `on`/`off`/`*changed*` — the canonical marshaling-flag case |
| Tamper alert | `tCapab<N>=Tamper` | `trig_tamper.json` | | ❌ | `detected`/`clear` |
| Temperature | `tCapab<N>=Temperature` | `trig_temperature.json` | | ❌ | numeric + comparator |
| Thermostat cool setpoint | `tCapab<N>=CoolSetpoint` | `trig_cool_sp.json` | | ❌ | numeric |
| Thermostat fan mode | `tCapab<N>=ThermFanMode` | `trig_therm_fan.json` | | ❌ | enum |
| Thermostat heat setpoint | `tCapab<N>=HeatSetpoint` | `trig_heat_sp.json` | | ❌ | numeric |
| Thermostat mode | `tCapab<N>=ThermMode` | `trig_therm_mode.json` | | ❌ | `heat`/`cool`/`auto`/`off`/`emergency heat` |
| Thermostat state | `tCapab<N>=ThermState` | `trig_therm_state.json` | | ❌ | `heating`/`cooling`/`fan only`/`idle`/`pending heat`/`pending cool` |
| Valve | `tCapab<N>=Valve` | `trig_valve.json` | | ❌ | `open`/`closed` |
| Water sensor | `tCapab<N>=Water` | `trig_water.json` | | ❌ | `dry`/`wet` |
| Window shade | `tCapab<N>=WindowShade` | `trig_shade.json` | | ❌ | `closed`/`open`/`opening`/`closing`/`partially open`/`unknown` |

### 3b. Time/date capabilities

| Capability | Input name pattern | Fixture | Unit test | Live smoke | Notes |
|---|---|---|---|---|---|
| Between two dates (conditions only) | TBD | `trig_between_dates.json` | | ❌ | start/end month/day or variable |
| Between two times (conditions only) | TBD | `trig_between_times.json` | | ❌ | start/end time of day or variable |
| Certain Time (triggers only) | TBD | `trig_certain_time.json` | | ❌ | time + optional date, sunrise/sunset with offset |
| Days of Week | TBD | `trig_days_of_week.json` | | ❌ | days only (condition) or days+time (trigger) |
| On a day | TBD | `trig_on_a_day.json` | | ❌ | specific date or variable |
| Periodic schedule (triggers only) | TBD | `trig_periodic.json` | | ❌ | minutes / hourly / daily / weekly / monthly / yearly |
| Time of day | TBD | `trig_time_of_day.json` | | ❌ | specific time / sunrise / sunset |
| Time since event | TBD | `trig_time_since.json` | | ❌ | attribute + minimum time since event |

### 3c. Hub / system capabilities

| Capability | Input name pattern | Fixture | Unit test | Live smoke | Notes |
|---|---|---|---|---|---|
| HSM alert (triggers only) | TBD | `trig_hsm_alert.json` | | ❌ | intrusion variants, smoke, water, rule, arming |
| HSM status | TBD | `trig_hsm_status.json` | | ❌ | armed/disarmed variants |
| Location event | TBD | `trig_location_event.json` | | ❌ | mode, sunrise/sunset, sunriseTime/sunsetTime, systemStart, severeLoad, zigbeeOff/zigbeeOn, zwaveCrashed |
| Mode | TBD | `trig_mode.json` | | ❌ | any of hub's modes |
| Private Boolean | TBD | `trig_private_bool.json` | | ❌ | true/false; rule-referencing input |
| Rule paused | TBD | `trig_rule_paused.json` | | ❌ | triggers on another rule's pause state |
| Security keypads (triggers only) | TBD | `trig_security_keypad.json` | | ❌ | armed away/home/night, disarmed, changed |
| Variable | TBD | `trig_variable.json` | | ❌ | hub variable value — numeric or string comparison |

### 3d. HTTP / special capabilities

| Capability | Input name pattern | Fixture | Unit test | Live smoke | Notes |
|---|---|---|---|---|---|
| Local End Point (triggers only) | TBD | `trig_local_endpoint.json` | | ❌ | Generates local URL for HTTP GET/POST |
| Cloud End Point (triggers only) | TBD | `trig_cloud_endpoint.json` | | ❌ | Generates cloud URL |
| Last Event Device (triggers only) | TBD | `trig_last_event_device.json` | | ❌ | References prior trigger's device |

### 3e. Trigger option variants (orthogonal to capability type)

| Variant | Fixture | Unit test | Live smoke | Notes |
|---|---|---|---|---|
| Conditional Trigger | `trig_conditional.json` | | ❌ | `isCondTrig.<N>=true` + attached condition; evaluated AFTER event |
| "And stays" (sticky trigger) | `trig_and_stays.json` | | ❌ | event must persist for duration |
| Conditional + And stays combined | `trig_cond_and_stays.json` | | ❌ | condition evaluated at event time; and-stays timer starts on event |
| Multiple triggers (OR semantics) | `trig_multiple.json` | | ❌ | rule fires on any of N triggers |
| Disable individual trigger | `trig_disabled.json` | | ❌ | `disableT<N>=true` |
| Multi-device trigger (multiple=true) | `trig_multi_device.json` | | ❌ | **CRITICAL** — the `multiple=true` marshaling-flag case we discovered |

## 4. Action categories

Each row: create a rule with this action, round-trip via read, update its parameters, delete.

### 4a. Conditional actions

| Action | Input schema | Fixture | Unit test | Live smoke | Notes |
|---|---|---|---|---|---|
| `IF (expression) THEN ... END-IF` | TBD | `act_if_then.json` | | ❌ | Must round-trip expression + nested actions |
| `ELSE-IF` branch | TBD | `act_else_if.json` | | ❌ | Chained after IF-THEN |
| `ELSE` branch | TBD | `act_else.json` | | ❌ | Final fallback |
| Nested IF inside IF | TBD | `act_nested_if.json` | | ❌ | Textual indentation preserved |
| Simple Conditional Action | TBD | `act_simple_cond.json` | | ❌ | `IF (expr) <single-action>` |

### 4b. Switches / buttons

| Action | Fixture | Unit test | Live smoke | Notes |
|---|---|---|---|---|
| Turn switches on/off | `act_switch_onoff.json` | | ❌ | Basic device command |
| Toggle switches | `act_switch_toggle.json` | | ❌ | |
| Flash switches | `act_switch_flash.json` | | ❌ | |
| Set switches per mode | `act_switch_per_mode.json` | | ❌ | Different action per hub mode |
| Choose switches per mode | `act_switch_choose_per_mode.json` | | ❌ | |
| Push button | `act_button_push.json` | | ❌ | |
| Push button per mode | `act_button_push_per_mode.json` | | ❌ | |
| Choose button per mode | `act_button_choose_per_mode.json` | | ❌ | |

### 4c. Dimmers / bulbs

| Action | Fixture | Unit test | Live smoke | Notes |
|---|---|---|---|---|
| Set dimmer | `act_dim_set.json` | | ❌ | level + optional fade |
| Toggle dimmer | `act_dim_toggle.json` | | ❌ | |
| Adjust dimmer | `act_dim_adjust.json` | | ❌ | +/- delta |
| Set dimmer per mode | `act_dim_per_mode.json` | | ❌ | |
| Fade dimmer over time | `act_dim_fade.json` | | ❌ | |
| Stop dimmer fade | `act_dim_stop_fade.json` | | ❌ | |
| Start raising/lowering dimmer | `act_dim_start_change.json` | | ❌ | |
| Stop changing dimmer | `act_dim_stop_change.json` | | ❌ | |
| Set color | `act_color_set.json` | | ❌ | hue/sat/level or named |
| Toggle color | `act_color_toggle.json` | | ❌ | |
| Set color per mode | `act_color_per_mode.json` | | ❌ | |
| Set color temperature | `act_ct_set.json` | | ❌ | Kelvin |
| Toggle color temperature | `act_ct_toggle.json` | | ❌ | |
| Set CT per mode | `act_ct_per_mode.json` | | ❌ | |
| Change CT over time | `act_ct_change_over_time.json` | | ❌ | |
| Stop changing CT | `act_ct_stop.json` | | ❌ | |

### 4d. Shades / blinds / fans / scenes

| Action | Fixture | Unit test | Live smoke | Notes |
|---|---|---|---|---|
| Open/Close shades/blinds | `act_shade_openclose.json` | | ❌ | |
| Set shade/blind position | `act_shade_position.json` | | ❌ | |
| Stop shade/blind | `act_shade_stop.json` | | ❌ | |
| Set fan speed | `act_fan_speed.json` | | ❌ | |
| Cycle fans | `act_fan_cycle.json` | | ❌ | |
| Activate Scenes | `act_scene_activate.json` | | ❌ | |
| Activate Scenes per Mode | `act_scene_per_mode.json` | | ❌ | |

### 4e. HSM / garage / locks / valves

| Action | Fixture | Unit test | Live smoke | Notes |
|---|---|---|---|---|
| Arm Away | `act_hsm_arm_away.json` | | ❌ | |
| Arm Home | `act_hsm_arm_home.json` | | ❌ | |
| Arm Night | `act_hsm_arm_night.json` | | ❌ | |
| Disarm | `act_hsm_disarm.json` | | ❌ | |
| Disarm All | `act_hsm_disarm_all.json` | | ❌ | |
| Arm All HSM Rules | `act_hsm_arm_all_rules.json` | | ❌ | |
| Cancel All Alerts | `act_hsm_cancel_alerts.json` | | ❌ | |
| Arm/Disarm HSM Rule | `act_hsm_rule_armdisarm.json` | | ❌ | Specific custom rule |
| Cancel HSM Rule Alert | `act_hsm_rule_cancel.json` | | ❌ | |
| Open/Close garage door | `act_garage.json` | | ❌ | |
| Lock/Unlock locks | `act_lock.json` | | ❌ | |
| Open/Close valves | `act_valve.json` | | ❌ | |

### 4f. Thermostats

| Action | Fixture | Unit test | Live smoke | Notes |
|---|---|---|---|---|
| Set thermostats | `act_therm_set.json` | | ❌ | |
| Set Thermostat Scheduler | `act_therm_sched.json` | | ❌ | |
| Set Thermostat Controller sensors | `act_therm_ctrl.json` | | ❌ | |

### 4g. Messages / HTTP

| Action | Fixture | Unit test | Live smoke | Notes |
|---|---|---|---|---|
| Send/Speak a Message | `act_speak.json` | | ❌ | string input with %var% substitution |
| Log a Message | `act_log.json` | | ❌ | |
| Send HTTP Get | `act_http_get.json` | | ❌ | |
| Send HTTP Post | `act_http_post.json` | | ❌ | |
| Ping IP address | `act_ping.json` | | ❌ | Sets `text` + `value` (packet loss) |

### 4h. Audio

| Action | Fixture | Unit test | Live smoke | Notes |
|---|---|---|---|---|
| Control Music Player | `act_music_control.json` | | ❌ | |
| Set Volume | `act_volume.json` | | ❌ | |
| Mute/Unmute | `act_mute.json` | | ❌ | |
| Sound Tone | `act_tone.json` | | ❌ | |
| Sound Chime | `act_chime.json` | | ❌ | |
| Control Siren | `act_siren.json` | | ❌ | |

### 4i. Variables / mode / files / custom

| Action | Fixture | Unit test | Live smoke | Notes |
|---|---|---|---|---|
| Set Variable (literal) | `act_var_literal.json` | | ❌ | |
| Set Variable (variable math) | `act_var_math.json` | | ❌ | arithmetic |
| Set Variable (Token op) | `act_var_token.json` | | ❌ | regex split — Groovy `split()` semantics |
| Set Variable (device attribute) | `act_var_dev_attr.json` | | ❌ | |
| Set Variable (string interpolation with %var%) | `act_var_interp.json` | | ❌ | |
| Set Mode | `act_set_mode.json` | | ❌ | |
| Run Custom Action | `act_custom.json` | | ❌ | Arbitrary device command + params |
| Write to local file | `act_file_write.json` | | ❌ | |
| Append to local file | `act_file_append.json` | | ❌ | |
| Delete local file | `act_file_delete.json` | | ❌ | |

### 4j. Rule interaction (cross-rule)

| Action | Fixture | Unit test | Live smoke | Notes |
|---|---|---|---|---|
| Set Private Booleans (same rule) | `act_pb_self.json` | | ❌ | |
| Set Private Booleans (other rule) | `act_pb_other.json` | | ❌ | |
| Run Rule Actions (other rule) | `act_run_other.json` | | ❌ | Note: bypasses required expression unless `Cancel pending...` enabled |
| Cancel Rule Timers | `act_cancel_timers.json` | | ❌ | Cancels all delays, waits, repeats |
| Pause Rules | `act_pause.json` | | ❌ | |
| Resume Rules | `act_resume.json` | | ❌ | |
| Activate Room Lights for Mode/Period | `act_room_lights_activate.json` | | ❌ | |
| Turn Off Room Lights | `act_room_lights_off.json` | | ❌ | |

### 4k. State management

| Action | Fixture | Unit test | Live smoke | Notes |
|---|---|---|---|---|
| Capture Devices | `act_capture.json` | | ❌ | |
| Restore Devices | `act_restore.json` | | ❌ | |
| Refresh devices | `act_refresh.json` | | ❌ | |
| Poll devices | `act_poll.json` | | ❌ | |
| Disable/Enable devices | `act_dev_enable.json` | | ❌ | |
| Start/Stop Z-Wave Polling | `act_zw_poll.json` | | ❌ | |

### 4l. Repeat

| Action | Fixture | Unit test | Live smoke | Notes |
|---|---|---|---|---|
| Repeat Actions | `rep_actions.json` | | ❌ | with interval |
| Repeat Actions n times | `rep_n_times.json` | | ❌ | for-loop semantics |
| Repeat While Expression | `rep_while.json` | | ❌ | |
| Repeat Until Expression | `rep_until.json` | | ❌ | Always runs at least once |
| Stop Repeating Actions | `rep_stop.json` | | ❌ | Requires `Stoppable?` on the Repeat |
| Stoppable Repeat option | `rep_stoppable.json` | | ❌ | Variant on Repeat Actions |
| END-REP marker | (covered by repeat fixtures) | | ❌ | Required terminator |

### 4m. Delay / wait / exit / comment

| Action | Fixture | Unit test | Live smoke | Notes |
|---|---|---|---|---|
| Delay Actions (fixed) | `del_fixed.json` | | ❌ | hrs:min:sec |
| Delay Actions (variable) | `del_var.json` | | ❌ | from local/hub variable |
| Delay Actions Per Mode | `del_per_mode.json` | | ❌ | |
| Delay (Cancelable) | `del_cancelable.json` | | ❌ | |
| Delay? option on an action (not standalone) | `del_inline.json` | | ❌ | Every action supports this |
| Cancel Delayed Actions | `del_cancel.json` | | ❌ | |
| Wait for Events (single) | `wait_events_single.json` | | ❌ | |
| Wait for Events (multiple, any) | `wait_events_any.json` | | ❌ | |
| Wait for Events (multiple, All of these) | `wait_events_all.json` | | ❌ | |
| Wait for Events with Timeout | `wait_events_timeout.json` | | ❌ | |
| Wait for Events with And-Stays | `wait_events_and_stays.json` | | ❌ | |
| Wait for Events (Elapsed Time only) | `wait_elapsed.json` | | ❌ | Equivalent to cancellable delay |
| Wait for Expression | `wait_expr.json` | | ❌ | |
| Wait for Expression with Timeout | `wait_expr_timeout.json` | | ❌ | |
| Wait for Expression with Use Duration | `wait_expr_duration.json` | | ❌ | Duration starts when action reached |
| Exit Rule | `act_exit.json` | | ❌ | Skips remaining actions; doesn't cancel scheduled |
| Comment | `act_comment.json` | | ❌ | Decorative only; logged if Actions logging on |

## 5. Variables

| Feature | Fixture | Unit test | Live smoke | Notes |
|---|---|---|---|---|
| Create local variable (number) | `var_local_num.json` | | ❌ | |
| Create local variable (decimal) | `var_local_dec.json` | | ❌ | |
| Create local variable (string) | `var_local_str.json` | | ❌ | |
| Create local variable (boolean) | `var_local_bool.json` | | ❌ | |
| Create local variable (date/time) | `var_local_datetime.json` | | ❌ | |
| Edit local variable value | `var_local_edit.json` | | ❌ | |
| Delete local variable | `var_local_delete.json` | | ❌ | |
| Reference hub variable (%varName%) | `var_hub_ref.json` | | ❌ | |
| Built-in %device% | `var_builtin_device.json` | | ❌ | |
| Built-in %value% | `var_builtin_value.json` | | ❌ | |
| Built-in %text% | `var_builtin_text.json` | | ❌ | |
| Built-in %date% | `var_builtin_date.json` | | ❌ | |
| Built-in %time% | `var_builtin_time.json` | | ❌ | |
| Built-in %now% | `var_builtin_now.json` | | ❌ | |
| Variable math: arithmetic | `var_math_arith.json` | | ❌ | |
| Variable math: Token (regex split) | `var_math_token.json` | | ❌ | Groovy split semantics |
| Variable math: device attribute | `var_math_dev_attr.json` | | ❌ | |
| String interpolation in Send/Speak | `var_interp_speak.json` | | ❌ | |
| String interpolation in HTTP body | `var_interp_http.json` | | ❌ | |
| Track event switch/dimmer | `var_track_event.json` | | ❌ | Sources action value from trigger event |

## 6. Private Boolean

| Feature | Fixture | Unit test | Live smoke | Notes |
|---|---|---|---|---|
| Reference in required expression | `pb_req_expr.json` | | ❌ | |
| Reference in conditional trigger | `pb_cond_trigger.json` | | ❌ | |
| Reference in `IF` condition | `pb_if_cond.json` | | ❌ | |
| Set true (same rule) | `pb_set_true_self.json` | | ❌ | |
| Set false (same rule) | `pb_set_false_self.json` | | ❌ | |
| Set true (from another rule) | `pb_set_true_other.json` | | ❌ | |
| Set false (from another rule) | `pb_set_false_other.json` | | ❌ | |
| Default value after Start | `pb_default.json` | | ❌ | Always true after Start |

## 7. Rule lifecycle verbs

| Verb | How invoked | Fixture | Unit test | Live smoke | Notes |
|---|---|---|---|---|---|
| `runRule` | RMUtils (not 4.x+) | N/A | | ❌ | Legacy; evaluate rule |
| `runRuleAct` | RMUtils or `runAction` button | `lc_run_act.json` | | ❌ | |
| `stopRuleAct` | RMUtils or `stopRule` button | `lc_stop.json` | | ❌ | Cancels delays, periodic triggers, repeats |
| `pauseRule` | RMUtils or `pauseRule` button | `lc_pause.json` | | ❌ | |
| `resumeRule` | RMUtils or equivalent button | `lc_resume.json` | | ❌ | |
| `setRuleBooleanTrue` | RMUtils | N/A | | ❌ | Duplicates Private Boolean action |
| `setRuleBooleanFalse` | RMUtils | N/A | | ❌ | |
| `Done` button (re-init) | `/installedapp/btn` with `name=Done` | `lc_done.json` | | ❌ | Re-runs initialize(), resubscribes |
| `Update Rule` button | `/installedapp/btn` with `name=updateRule` | `lc_update_rule.json` | | ❌ | Same as Done but stays on page |
| `Remove` button | `/installedapp/btn` | N/A | | ❌ | Removes single rule |
| `Start` button (post-Stop) | `/installedapp/btn` | `lc_start.json` | | ❌ | Also resets Private Boolean to true |

## 8. HTTP endpoint surface (RM's own, not admin)

Triggers of type `Local End Point` / `Cloud End Point` generate RM-owned URLs that accept:

| Path | Fixture | Unit test | Live smoke | Notes |
|---|---|---|---|---|
| `/runRuleAct=<id1>&<id2>` | `ep_runact.json` | | ❌ | |
| `/stopRuleAct=<id1>` | `ep_stop.json` | | ❌ | |
| `/pauseRule=<id>` | `ep_pause.json` | | ❌ | |
| `/resumeRule=<id>` | `ep_resume.json` | | ❌ | |
| `/setRuleBooleanTrue=<id>` | `ep_pb_true.json` | | ❌ | |
| `/setRuleBooleanFalse=<id>` | `ep_pb_false.json` | | ❌ | |
| `/runRule=<id>` (legacy) | N/A | | ❌ | |
| `/getRuleList` | `ep_getrulelist.json` | | ❌ | Returns `{id: name}` JSON |
| `/setHubVariable=<name>:<urlEncodedValue>` | `ep_set_hub_var.json` | | ❌ | RM 5.1+ |
| `/setHubVariableEncoded=<encName>:<encValue>` | `ep_set_hub_var_enc.json` | | ❌ | For names with spaces |
| `/setGlobalVariable=<name>:<value>` | N/A | | ❌ | Legacy only |
| `/<arbitraryString>` (sets %value%) | `ep_set_value.json` | | ❌ | Not conflicting with a verb |

## Fixture capture checklist

For every fixture referenced above, capture from a live Hubitat hub on the *most recent* RM 5.1 firmware:

- [ ] Create a minimal scratch rule that exercises ONLY the feature being tested
- [ ] `GET /installedapp/configure/json/<id>[/<subpage>]` — save full response to the fixture path
- [ ] `GET /installedapp/statusJson/<id>` — save alongside as `<fixture_name>.status.json` to capture post-write `appSettings[].multiple` flags and `eventSubscriptions` state
- [ ] Strip any PII (device labels, hub names) before committing
- [ ] `GET /installedapp/forcedelete/<id>/quiet` to clean up the scratch rule

## Acceptance for Phase 2 merge (#120)

Before the native-RM CRUD PR can merge:

- [ ] Every fixture path in this matrix populated with real captured hub responses
- [ ] Every "Unit test" column populated with a Spock spec name, and every named spec passing on `./gradlew test`
- [ ] `scripts/smoke-rm-crud.sh` (or equivalent) implemented and green against a live hub, covering at least one rule per action category (12 representative cases minimum)
- [ ] Post-write verification loop (assert `appSettings[].multiple` matches input declaration; re-POST if divergent) exercised by ≥2 unit tests + the live smoke
- [ ] This matrix document itself updated to reflect any discoveries during implementation (new gotchas, different input-name patterns, etc.)

## Outstanding questions (resolved as implementation proceeds)

- Exact `tCapab<N>` enum values for each capability (TBD during fixture capture; update section 3 tables)
- Exact action-type identifiers used by `actType.<N>` / `actSubType.<N>` / `cCmd.<N>` (update section 4 tables during fixture capture)
- How RM stores expression trees in settings (flat key list vs structured) — affects `IF-THEN-ELSE` and expression round-trip complexity
- Multi-page wizard structure for complex action types (variable math, Custom Action with parameters)
- Room Lighting / Basic Rules / Button Controller schemas (parallel matrices to build per the scope-expansion comment on #120)
