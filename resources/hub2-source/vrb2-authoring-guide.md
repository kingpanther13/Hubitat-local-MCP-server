> **Source:** Hubitat's own VRB 2.0 authoring guide, served verbatim by the hub's built-in AI Connector Integration as the MCP resource `hubitat://vrb2/schema`.
> **Captured:** 2026-09-03 from a hub on platform **2.5.1.181**.
> **License:** Hubitat's text, not ours — vendored under the same terms as the bundles (see `README.md` § *License note*); do not redistribute outside this repo.

# Visual Rule Builder 2.0 JSON format

Visual Rule Builder 2.0 (VRB2) is a deliberately constrained, JSON-driven rule
engine. One installed VRB2 app contains one connected rule. A rule may have
multiple alternative triggers, but all triggers enter one decision and ultimately
use the same two action branches. Independent workflows belong in separate app
instances.

This document describes schema version `1`, the only version currently accepted.
VRB2 does not migrate Visual Rule Builder 1 documents.

## Storage and lifecycle

The source document is stored in the installed application's `state.ruleJson`
field. It is not an app setting and is not directly editable on the app page. The
current app page displays pretty-printed JSON for inspection only.

On save, the hub:

1. stores the submitted text in `state.ruleJson`;
2. invokes the app's `updated()` method;
3. parses, validates, and normalizes the document;
4. activates subscriptions, schedules, and the runtime graph only if validation
   succeeds; and
5. registers all recognized device references with Hubitat's "in use by"
   tracking, even when another graph error prevents activation.

The stored source and activated runtime are intentionally distinct. Invalid JSON
can be stored successfully, but it deactivates the rule and removes its runtime
subscriptions and schedules. It does not continue executing an older graph that
differs from the stored source. Opening the rule revalidates device, command,
attribute, and mode references so a future editor can highlight resources that
changed after save.

The serialized document is limited to 100,000 UTF-8 bytes. Oversized submissions
are rejected before application state is changed.

## Complete example

This rule runs when either motion starts or the hub enters mode `2`. It tests two
conditions with AND semantics, executes one of two branches, merges the branches,
and sends a common notification.

```json
{
  "version": 1,
  "nodes": [
    {
      "id": "motion-trigger",
      "kind": "trigger",
      "type": "motion",
      "config": {
        "motionSensors": [101, 102],
        "motionSensorEvent": "Motion starts"
      }
    },
    {
      "id": "mode-trigger",
      "kind": "trigger",
      "type": "systemMode",
      "config": {
        "modes": [2]
      }
    },
    {
      "id": "trigger-merge",
      "kind": "merge",
      "type": "triggerMerge",
      "config": {}
    },
    {
      "id": "decision",
      "kind": "decision",
      "type": "all",
      "config": {
        "conditions": [
          {
            "id": "weekday",
            "type": "daysOfWeek",
            "config": {
              "daysOfWeek": [1, 2, 3, 4, 5]
            }
          },
          {
            "id": "after-dark",
            "type": "timeIsBetween",
            "config": {
              "triggerCondition": "sunsetToSunrise"
            }
          }
        ]
      }
    },
    {
      "id": "then-light",
      "kind": "action",
      "type": "setBrightness",
      "config": {
        "dimmers": [201],
        "brightness": 45
      }
    },
    {
      "id": "else-light",
      "kind": "action",
      "type": "turnOff",
      "config": {
        "switches": [201]
      }
    },
    {
      "id": "branch-merge",
      "kind": "merge",
      "type": "branchMerge",
      "config": {}
    },
    {
      "id": "notify",
      "kind": "action",
      "type": "sendNotification",
      "config": {
        "notificationDevices": [301],
        "notificationMessage": "Hallway rule completed"
      }
    }
  ],
  "edges": [
    {"from": "motion-trigger", "to": "trigger-merge", "port": "next"},
    {"from": "mode-trigger", "to": "trigger-merge", "port": "next"},
    {"from": "trigger-merge", "to": "decision", "port": "next"},
    {"from": "decision", "to": "then-light", "port": "true"},
    {"from": "then-light", "to": "branch-merge", "port": "next"},
    {"from": "decision", "to": "else-light", "port": "false"},
    {"from": "else-light", "to": "branch-merge", "port": "next"},
    {"from": "branch-merge", "to": "notify", "port": "next"}
  ]
}
```

## Top-level document

| Field | Type | Required | Meaning |
| --- | --- | --- | --- |
| `version` | integer | yes | Must be `1`. |
| `nodes` | array | yes | Nonempty array of node objects with unique IDs. |
| `edges` | array | yes | Directed connections between nodes. May be empty only where the topology permits no action branch. |

Unknown schema versions are rejected. IDs are strings, must be nonblank, and are
case-sensitive. Document order has no execution meaning; edges define order.

## Nodes

Every flow node has this shape:

```json
{
  "id": "unique-node-id",
  "kind": "trigger|merge|decision|action",
  "type": "type-specific-value",
  "config": {}
}
```

Conditions are not flow nodes. They are nested entries in the one decision node:

```json
{
  "id": "decision",
  "kind": "decision",
  "type": "all",
  "config": {
    "conditions": [
      {"id": "condition-id", "type": "switchCondition", "config": {"switches": [12], "switchState": "Turned on"}}
    ]
  }
}
```

The decision type is `all` when every nested condition must pass or `any` when at
least one nested condition must pass. Condition IDs must be unique within the
decision. An empty conditions array is valid only for `all`, where it makes the
decision true; an `any` decision requires at least one condition.

## Edges and supported topology

An edge has `from`, `to`, and `port` string fields:

```json
{"from": "source-id", "to": "destination-id", "port": "next"}
```

Valid source ports are:

| Source | Port |
| --- | --- |
| trigger | `next` |
| `triggerMerge` | `next` |
| decision | `true` or `false` |
| action | `next` |
| `branchMerge` | `next` |

The topology is:

```text
trigger ─┐
trigger ─┼─> triggerMerge ─> decision ─true─> linear THEN actions ─┐
trigger ─┘                              └false─> linear ELSE actions ─┼─> optional branchMerge ─> linear common actions
```

The validator requires one or more triggers, exactly one `triggerMerge`, exactly
one `all` or `any` decision, and zero or one `branchMerge`. Every trigger connects
directly to the trigger merge, which connects directly to the decision. Each
decision output is a linear chain. If present, both branches converge on the
branch merge, which can have one linear common tail.

Cycles, fan-out, arbitrary joins, nested decisions, disconnected nodes, multiple
independent workflows, and loops are rejected. `branchMerge` is merge-any
continuation, not a synchronization barrier: only the selected branch runs.

## Common value formats

- Device fields contain nonempty arrays of positive device IDs. Numeric strings
  are accepted and normalized to positive 32-bit integers. Duplicate IDs are
  rejected.
- Mode fields contain nonempty arrays of positive mode IDs; `mode` contains one
  positive mode ID. Legacy strings ending in `-<id>` are accepted and normalized.
- Times are four-digit, 24-hour `HHmm` strings such as `"0730"` or `"2215"`.
- Durations use integer `minutes` and `seconds` fields. At least one component
  must make the duration positive, and the combined duration cannot exceed 24
  hours. Trigger-specific prefixes are described below.
- Numeric thresholds must be JSON numbers, not numeric strings.
- Percent values are inclusive integers from `0` through `100`.
- Enum labels are exact and case-sensitive.

## Triggers

Multiple trigger nodes have OR semantics: one matching platform event enters the
shared decision once. Ordinary device triggers apply only to the event-producing
configured device. The explicit `Everyone leaves` trigger examines the entire
configured device group.

| Type | Required config |
| --- | --- |
| `timeOfDay` | `timeOfDay`: `HHmm` |
| `sunriseSunset` | `triggerCondition`: `beforeSunrise`, `sunrise`, `afterSunrise`, `beforeSunset`, `sunset`, or `afterSunset`; offset selectors also require the corresponding nonnegative `minutesBeforeSunrise`, `minutesAfterSunrise`, `minutesBeforeSunset`, or `minutesAfterSunset` |
| `motion` | `motionSensors`; `motionSensorEvent`: `Motion starts`, `Motion stops`, or `Motion stops and stays inactive for...` |
| `contact` | `contactSensors`; `contactSensorEvent`: `Contact opens`, `Contact closes`, `Contact opens and stays open for...`, or `Contact closes and stays closed for...` |
| `presence` | `presenceSensors`; `presenceSensorEvent`: `Everyone leaves` or `Someone arrives` |
| `acceleration` | `accelerationSensors`; `accelerationSensorEvent`: `Acceleration or vibration has started`, `Acceleration or vibration has stopped`, or `Acceleration or vibration has stopped and stayed inactive for...` |
| `water` | `waterSensors`; `waterSensorEvent`: `Water is leaking` or `Water sensor is dry` |
| `smoke` | `smokeSensors`; `smokeSensorEvent`: `Smoke is present` or `Smoke has cleared` |
| `co` | `coSensors`; `coSensorEvent`: `Carbon monoxide is present` or `Carbon monoxide has cleared` |
| `alarm` | `alarms`; `alarmEvent`: `Alarm turns on` matches `siren`, `strobe`, or `both`; `Alarm turns off` matches `off` |
| `temperature` | `temperatureSensors`; `temperatureSensorEvent`: `Temperature has risen above...` or `Temperature has fallen below...`; numeric `temperature` |
| `humidity` | `humiditySensors`; `humiditySensorEvent`: `Humidity has risen above...` or `Humidity has fallen below...`; numeric `humidity` from 0 to 100 |
| `illuminance` | `illuminanceSensors`; `illuminanceSensorEvent`: `Illuminance has risen above...` or `Illuminance has fallen below...`; nonnegative numeric `illuminance` |
| `power` | `powerMeters`; `powerMeterEvent`: `Power has risen above...`, `Power has fallen below...`, `Power has become and stayed above...`, or `Power has become and stayed below...`; nonnegative numeric `power` |
| `switch` | `switches`; `switchEvent`: `Turns on`, `Turns off`, `Turns on and stays on for...`, or `Turns off and stays off for...` |
| `button` | `buttons`; `buttonEvent`: `Pushed`, `Held`, `Released`, or `Double tapped`; positive integer `buttonIndex` supported by every selected button |
| `lock` | `locks`; `lockEvent`: `Locked` or `Unlocked` |
| `shock` | `shockSensors`; `shockSensorEvent`: `Shock has been detected` or `Shock has been cleared` |
| `systemMode` | `modes` |

### “Stays for” duration fields

The duration field prefix is based on trigger type:

| Trigger | Fields |
| --- | --- |
| motion | `motionStaysMinutes`, `motionStaysSeconds` |
| contact | `contactStaysMinutes`, `contactStaysSeconds` |
| acceleration | `accelerationStaysMinutes`, `accelerationStaysSeconds` |
| power | `powerStaysMinutes`, `powerStaysSeconds` |
| switch | `switchStaysMinutes`, `switchStaysSeconds` |

Each countdown is device-specific, including acceleration and switch. A change
back to the opposite state cancels only that device's countdown. Pending trigger
countdowns survive app startup or an unchanged runtime rebuild, but are canceled
when the graph changes or the rule is paused. A delayed trigger has not yet
started an action execution.

Numeric crossing state is tracked independently by trigger, device, and attribute.
The first event can use device history as its baseline. Crossing means the prior
value was on or beyond the threshold's opposite side and the new value is
strictly across it.

Scheduled triggers are grouped by logical local minute. Delivery of a current
schedule callback is authoritative; callbacks from an older runtime generation
and duplicate callbacks for the same local day/minute are ignored. Sunrise and
sunset schedules are refreshed when the location reports updated solar times.

## Conditions

The decision node combines its nested conditions with AND semantics for type
`all` or OR semantics for type `any`. This decision-level aggregation is separate
from how one condition handles multiple selected devices. Unless the table notes
otherwise, a multi-device state or numeric condition is true when **any** selected
device matches. An unavailable device never creates an accidental “all devices
match” result.

| Type | Required config and meaning |
| --- | --- |
| `timeIsBetween` | `triggerCondition`: `specificTimes`, `sunriseToSunset`, or `sunsetToSunrise`. `specificTimes` also needs `startTime` and `endTime` as `HHmm`. A window crossing midnight is supported. Sunrise/sunset values are supplied by the location. |
| `daysOfWeek` | `daysOfWeek`: nonempty integer array. `0` Sunday, `1` Monday, …, `6` Saturday. Evaluation uses `location.timeZone`. |
| `motionCondition` | `motionSensors`; `motionSensorState`: `Motion is active` or `Motion is inactive` |
| `contactCondition` | `contactSensors`; `contactSensorState`: `Contact is open` or `Contact is closed` |
| `presenceCondition` | `presenceSensors`; `presenceSensorState`: `Presence is detected` requires **all** devices present; `No presence is detected` requires any device not present |
| `accelerationCondition` | `accelerationSensors`; `accelerationSensorState`: `Acceleration or vibration is detected` or `No acceleration or vibration is detected` |
| `waterCondition` | `waterSensors`; `waterSensorState`: `Water leak is detected` or `Water sensor is dry` |
| `smokeCondition` | `smokeSensors`; `smokeSensorState`: `Smoke is detected` or `Smoke is cleared` |
| `coCondition` | `coSensors`; `coSensorState`: `Carbon monoxide is detected` or `Carbon monoxide is cleared` |
| `temperatureCondition` | `temperatureSensors`; `temperatureSensorState`: `Temperature is above...` or `Temperature is below...`; numeric `temperature` |
| `humidityCondition` | `humiditySensors`; `humiditySensorState`: `Humidity is above...` or `Humidity is below...`; numeric `humidity` from 0 to 100 |
| `illuminanceCondition` | `illuminanceSensors`; `illuminanceSensorState`: `Illuminance is above...` or `Illuminance is below...`; nonnegative numeric `illuminance` |
| `powerCondition` | `powerMeters`; `powerMeterState`: `Power is above...` or `Power is below...`; nonnegative numeric `power` |
| `switchCondition` | `switches`; `switchState`: `Turned on` or `Turned off` |
| `lockCondition` | `locks`; `lockState`: `Locked` requires **all** devices locked; `Unlocked` requires any device unlocked |
| `thermostatModeCondition` | `thermostats`; `thermostatMode`: `auto`, `cool`, `heat`, `emergency heat`, or `off` |
| `systemModeCondition` | `modes`; true when the current mode ID is selected |

Threshold equality is neither above nor below.

## Actions

Action branches execute in edge order. A failure is caught per device and command,
logged with node/device/command context, and does not prevent other selected
devices or later branch nodes from running.

| Type | Required config and behavior |
| --- | --- |
| `turnOn` / `turnOff` | `switches`; calls `on()` / `off()` when the device supports `switch` and the matching command. A FanControl-only device may instead support `speed` and `setSpeed`; the action calls `setSpeed("on")` / `setSpeed("off")` as a fallback. |
| `toggle` | `switches`; reads `switch`, then calls the opposite command |
| `setBrightness` | `dimmers`; integer `brightness` 0–100; calls `setLevel()` |
| `setColorTemp` | `colorTempBulbs`; positive integer `colorTemp`; calls `setColorTemperature()` |
| `setColor` | `colorBulbs`; `color` object with numeric `h`, `s`, and `b`, each 0–100; converted to Hubitat `hue`, `saturation`, and `level` |
| `setLightEffect` | `effectDevices`; positive integer `effectId` advertised by every device's `lightEffects` map; calls `setEffect(effectId)` |
| `lock` / `unlock` | `locks`; calls the same-named command |
| `turnOnAlarm` / `turnOffAlarm` | `alarms`; requires the `alarm` attribute; calls `both()` / `off()` |
| `openValve` / `closeValve` | `valves`; calls `open()` / `close()` |
| `openGarageDoor` / `closeGarageDoor` | `garageDoors`; calls `open()` / `close()` |
| `openWindowShade` / `closeWindowShade` | `windowShades`; calls `open()` / `close()` |
| `pushButton` | single `button`; `buttonAction`: `Push`, `Hold`, `Release`, or `Double Tap`; positive `buttonIndex`; calls `push`, `hold`, `release`, or `doubleTap` |
| `sendNotification` | `notificationDevices`; nonblank `notificationMessage`; calls `deviceNotification()` |
| `speakNotification` | `speechDevices`; nonblank `speakMessage`; calls `speak()` |
| `controlPlayer` | `musicPlayers`; `musicPlayerAction`: `previousTrack`, `play`, `pause`, `nextTrack`, `volumeUp`, `volumeDown`, `mute`, `unmute`, `togglePlayPause`, `toggleMuteUnmute`, `stop`, or `setVolume`. `setVolume` also needs integer `musicPlayerVolume` 0–100. Toggle actions read `status` or `mute`. |
| `controlThermostat` | `thermostats`; select at least one operation with `setThermostatMode`, `setThermostatFanMode`, `setThermostatHeatingSetpoint`, or `setThermostatCoolingSetpoint`. Supply the corresponding `thermostatMode`, `thermostatFanMode`, `thermostatHeatingSetpoint`, or `thermostatCoolingSetpoint`. Legacy flag/value aliases are accepted. |
| `setFanSpeed` | `fans`; nonblank `fanSpeed` supported by every selected FanControl device; calls `setSpeed(fanSpeed)`. Devices without a `supportedFanSpeeds` catalog use the standard `low`, `medium-low`, `medium`, `medium-high`, `high`, `on`, `off`, and `auto` choices. |
| `setMode` | `mode`; changes the location mode |
| `setModeUnlessAway` | `mode`; changes mode unless the hub is in Away mode |
| `exitAwayMode` | no config; applies the existing exit-away behavior |
| `runRule` | positive integer `appId`; resolves that installed app and invokes its no-argument `runRule()` method |
| `wait` | `minutes`, `seconds`; suspends this execution and resumes at its next edge |
| `cancelWait` | no config; globally cancels every pending action wait in this app |
| `sample` | no config; intentional no-op |

Action waits are persisted across startup and unchanged graph rebuilds. They are
canceled when the graph changes. Pausing prevents new executions but does not
stop a branch that already started; an already-started branch may therefore
resume from `wait` and finish while the rule is paused.

Events may start additional executions while earlier executions are suspended at
`wait`. The number of pending executions is intentionally unlimited. Each newly
pending execution is logged with its execution ID, wait node, resume timestamp,
and the total pending count. `state.pendingExecutions` contains the complete map,
and the app status page exposes its current size for future UI use.

`runRule` targets are checked when the graph is validated and resolved again
when the action executes. A missing target is logged with the action node and
application ID. An exception from the target's `runRule()` method is logged by
the application dispatcher. In either case, the caller's branch continues. A
rule cannot directly target itself. VRB2 does not detect indirect cycles spanning
multiple installed rules, so graphs must not form chains such as rule A running
rule B while rule B runs rule A.

## Manual and compatibility execution

- `runActions()` represents a manual trigger event. It bypasses trigger nodes,
  evaluates the current decision conditions, and runs THEN or ELSE normally.
- `runRule()` is a compatibility entry point that bypasses both triggers and
  conditions and starts the THEN branch directly.
- Both entry points respect pause.

## Validation output

Validation errors are human-readable strings. `validationIssues` contains the
same problems in editor-oriented form:

```json
{
  "nodeId": "motion-trigger",
  "field": "motionSensors",
  "message": "Node 'motion-trigger' config.motionSensors references missing device '999'"
}
```

`nodeId` can identify a flow node or a nested condition ID. `field` can be null
for graph-level or node-level problems. A future editor should retain the source
document, display the message, and highlight the referenced node/field when
available.

Validation includes:

- JSON and top-level schema shape;
- schema version;
- node/condition/edge identity and type;
- the constrained topology and connectivity;
- cycles, fan-out, invalid ports, and duplicate edges;
- config field shapes, enums, numeric ranges, times, and durations;
- device ID normalization and duplicate IDs;
- current device existence and required attributes/commands;
- current mode existence when location mode metadata is available; and
- button-number limits when device metadata exposes them.

## Save response contract

Every completed VRB2 save attempt uses the same core fields, including exceptional
storage and activation paths:

```json
{
  "success": true,
  "storedSuccessfully": true,
  "activatedSuccessfully": true,
  "storageError": null,
  "activationError": null,
  "validationErrors": [],
  "validationIssues": [],
  "referencedDeviceIds": [101, 102, 201, 301]
}
```

`success` is retained for endpoint compatibility and has exactly the same meaning
as `storedSuccessfully`. Clients must inspect `activatedSuccessfully` before
claiming the rule is active.

A document that is stored but fails validation looks like:

```json
{
  "success": true,
  "storedSuccessfully": true,
  "activatedSuccessfully": false,
  "storageError": null,
  "activationError": null,
  "validationErrors": ["Node 'motion-trigger' config.motionSensors references missing device '999'"],
  "validationIssues": [
    {
      "nodeId": "motion-trigger",
      "field": "motionSensors",
      "message": "Node 'motion-trigger' config.motionSensors references missing device '999'"
    }
  ],
  "referencedDeviceIds": [999]
}
```

An exceptional storage failure still returns the core contract:

```json
{
  "success": false,
  "storedSuccessfully": false,
  "activatedSuccessfully": false,
  "storageError": "storage failure detail",
  "activationError": null,
  "validationErrors": [],
  "validationIssues": [],
  "referencedDeviceIds": []
}
```

An oversized document is not stored. Its response has `success: false`,
`storedSuccessfully: false`, `activatedSuccessfully: false`, a null
`storageError`, and the size problem in `validationErrors` and
`validationIssues`.

An exception after storage sets `activationError`, while ordinary validation
failure leaves `activationError` null and reports validation errors. Additional
post-storage fields can include `name`, `graphDocument`, `ruleJson`, and
`deviceTrackingError`.

The corresponding read response contains `name`, `rulePaused`, `ruleJson`,
`validationErrors`, `validationIssues`, `referencedDeviceIds`, `graphDocument`,
and a compact `runtimeGraph` summary (or null when no runtime is active).

## Editor guidance

- Generate stable, opaque node IDs; never use display labels as identities.
- Keep one workflow per installed app.
- Always submit `version: 1`.
- Treat the enum strings in this document as protocol values, not display text
  that may be freely rewritten.
- Store device and mode IDs, not names.
- Preserve the user's source text when activation fails and show both storage
  and activation results.
- Re-fetch validation when opening a rule because devices, capabilities,
  commands, and modes can change after the original save.
- Do not add runtime observation fields such as numeric crossing baselines to
  authored JSON; those are runtime state, not schema fields.
