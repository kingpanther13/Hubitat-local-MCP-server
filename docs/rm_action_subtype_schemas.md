# RM 5.1 Action Capability Reference

Generated from `_rmActionSchemaForDiscover()` in `hubitat-mcp-server.groovy`.
Use `addAction({discover: true})` to retrieve this schema live from the current code.

The `capability` field (case-insensitive) is the discriminator. Pass it as
`addAction.capability` in real calls. `action` is required for multi-variant
capabilities; optional or absent for single-variant ones.

---

## Device capabilities

### switch
Actions: `on`, `off`, `toggle`, `flash`, `setPerMode`, `choosePerMode`

| Field | Type | Notes |
|---|---|---|
| `action` | enum | Required |
| `deviceIds` | List\<Integer\> | Required for all actions |
| `perMode` | Map | For setPerMode: `{modeIdOrName: 'on'|'off', ...}`. For choosePerMode: `{modeIdOrName: {on: [devIds], off: [devIds]}, ...}` |
| `delay` | Map | `{hours?, minutes?, seconds?, cancelable?}` |
| `rawSettings` | Map | Escape hatch |

Note: `flash` starts a flash schedule; use `runCommand` with `command='flashOff'` to stop it.

### dimmer
Actions: `setLevel`, `toggle`, `adjust`, `fade`, `stopFade`, `startRaiseLower`, `stopChanging`, `setLevelPerMode`

| Field | Type | Notes |
|---|---|---|
| `action` | enum | Required |
| `deviceIds` | List\<Integer\> | Required |
| `level` | Integer | 0-100, required for setLevel/toggle |
| `adjustBy` | Integer | -100..100, required for adjust |
| `fadeSeconds` | Integer | Optional for setLevel/toggle/adjust |
| `targetLevel` | Integer | Required for fade |
| `minutes` | Integer | Required for fade |
| `direction` | enum | `raise` or `lower`, required for fade and startRaiseLower |
| `intervalSeconds` | Integer | Optional for fade |
| `perMode` | Map | For setLevelPerMode: `{modeIdOrName: level, ...}` |
| `levelVariable` | String | Hub variable name -- use instead of `level` for variable-sourced setLevel |
| `delay` | Map | |
| `rawSettings` | Map | |

### color
Actions: `setColor`, `toggleColor`, `setColorPerMode`

| Field | Type | Notes |
|---|---|---|
| `action` | enum | Required |
| `deviceIds` | List\<Integer\> | Required |
| `colorName` | String | Required for setColor/toggleColor |
| `level` | Integer | 0-100 |
| `perMode` | Map | For setColorPerMode: `{modeIdOrName: {color: 'Red', level: 70}, ...}` |
| `delay` | Map | |
| `rawSettings` | Map | |

### colorTemp
Actions: `setColorTemp`, `toggleColorTemp`, `fadeColorTemp`, `stopColorTempFade`, `setColorTempPerMode`

| Field | Type | Notes |
|---|---|---|
| `action` | enum | Required |
| `deviceIds` | List\<Integer\> | Required |
| `kelvin` | Integer | Required for setColorTemp and toggleColorTemp |
| `targetKelvin` | Integer | Required for fadeColorTemp (NOT `kelvin`) |
| `level` | Integer | |
| `minutes` | Integer | Required for fadeColorTemp |
| `direction` | enum | `raise` or `lower`, required for fadeColorTemp |
| `perMode` | Map | For setColorTempPerMode: `{modeIdOrName: {kelvin: 2700, level: 70}, ...}` |
| `delay` | Map | |
| `rawSettings` | Map | |

### lock
Actions: `lock`, `unlock`

| Field | Type | Notes |
|---|---|---|
| `action` | enum | Required |
| `deviceIds` | List\<Integer\> | Required |
| `delay` | Map | |
| `rawSettings` | Map | |

Note: `lockRL.<N>` field -- `true`=UNLOCK, `false`=Lock (inverted relative to field name).

### thermostat

| Field | Type | Notes |
|---|---|---|
| `deviceIds` | List\<Integer\> | Required |
| `mode` | String | Thermostat mode |
| `fanMode` | String | Fan setting |
| `heatingSetpoint` | Number | |
| `coolingSetpoint` | Number | |
| `adjustHeating` | Number | |
| `adjustCooling` | Number | |
| `delay` | Map | |
| `rawSettings` | Map | |

### shade
Actions: `open`, `close`, `stop`, `setPosition`

| Field | Type | Notes |
|---|---|---|
| `action` | enum | Required |
| `deviceIds` | List\<Integer\> | Required |
| `position` | Integer | 0-100, required for setPosition |
| `delay` | Map | |
| `rawSettings` | Map | |

Note: `shadeRL.<N>` field -- `true`=CLOSE, `false`=Open (inverted relative to field name).

### fan
Actions: `setSpeed`, `cycle`

| Field | Type | Notes |
|---|---|---|
| `action` | enum | Required |
| `deviceIds` | List\<Integer\> | Required |
| `speed` | String | low/med/high/auto/etc., required for setSpeed |
| `delay` | Map | |
| `rawSettings` | Map | |

### button
Actions: `push`, `pushPerMode`, `choosePerMode`

| Field | Type | Notes |
|---|---|---|
| `action` | enum | Required |
| `deviceIds` | List\<Integer\> | Required |
| `buttonNumber` | Integer | Required for push/pushPerMode/choosePerMode |
| `perMode` | Map | |
| `rawSettings` | Map | |

### runCommand
Calls any device-driver command not exposed by higher-level capability mappings (e.g. `flashOff`, custom verbs).

| Field | Type | Notes |
|---|---|---|
| `command` | String | Required. Driver command name (e.g. 'flashOff', 'refresh', 'setLevel') |
| `deviceIds` | List\<Integer\> | Required |
| `capabilityFilter` | String | Default 'Switch' |
| `parameters` | List | Literal: `[{type: 'number', value: 75}]`. Variable-sourced: `[{type: 'number', variable: 'myVar'}]`. Mix literal and variable entries across slots. Fails loud (success=false with descriptive error) if the hub does not reveal the xVar enum after enabling variable mode for a slot. |
| `useLastEventDevice` | Boolean | |
| `delay` | Map | |
| `rawSettings` | Map | |

---

## Hub capabilities

### mode
Exactly one of `modeId` or `modeName` is required at call time. When `modeName` is supplied, it is resolved to the numeric mode ID via `location.modes` before the write -- unknown names fail fast with the valid mode list. Degenerate case: if `location.modes` returns an empty list (hub in a degraded state), the error message reads "Available modes: (none -- hub returned no modes; verify hub state via get_modes)". Use `get_modes` to confirm the hub's mode list before calling.

| Field | Type | Notes |
|---|---|---|
| `modeId` | Integer | Provide this OR modeName |
| `modeName` | String | Provide this OR modeId. Case-insensitive. Resolved to ID automatically. |

Note: `addAction` mode uses the `modeName` field for name-based resolution. `addTrigger` mode uses a `state` field for the mode name instead -- triggers share a generic `state` field across multiple device-state capability types, because triggers represent a superset of device-state events where a single field covers mode, switch, presence, and similar state values, while `addAction` uses the explicit `modeName` field.

### setVariable
Also accepted as `capability='variable'`. Sets a hub variable to a constant or copies it from another variable.

| Field | Type | Notes |
|---|---|---|
| `variable` | String | Required. Target hub variable name. Must be an existing hub variable -- an unknown name is rejected before the hub write to prevent silent broken-action state. |
| `value` | Number | Numeric constant to assign -- provide this OR `sourceVariable`. String, boolean, and datetime hub-variable targets are not supported via `value`; use `sourceVariable`, or set those types via `rawSettings`. |
| `sourceVariable` | String | Source variable name to copy from -- provide this OR `value`. Must be an existing hub variable -- an unknown name is rejected before the hub write to prevent silent broken-action state. Schema-gated: the source-variable field is only revealed by RM after the numOp=variable write; fails loud if the hub does not reveal it. See `docs/rm_wire_format.md` for the wire sequence. |
| `delay` | Map | |
| `rawSettings` | Map | |

---

## Messaging capabilities

### log

| Field | Type | Notes |
|---|---|---|
| `message` | String | Required |
| `delay` | Map | |
| `rawSettings` | Map | |

### notification

| Field | Type | Notes |
|---|---|---|
| `deviceIds` | List\<Integer\> | Required. Notification device IDs |
| `message` | String | Required |
| `delay` | Map | |
| `rawSettings` | Map | |

### httpGet

| Field | Type | Notes |
|---|---|---|
| `url` | String | Required |
| `delay` | Map | |
| `rawSettings` | Map | |

### httpPost

| Field | Type | Notes |
|---|---|---|
| `url` | String | Required |
| `body` | String | Required |
| `contentType` | String | |
| `delay` | Map | |
| `rawSettings` | Map | |

### ping

| Field | Type | Notes |
|---|---|---|
| `ip` | String | Required |
| `rawSettings` | Map | |

---

## Media capabilities

### volume

| Field | Type | Notes |
|---|---|---|
| `deviceIds` | List\<Integer\> | Required |
| `level` | Integer | Required. Volume level 0-100 |
| `delay` | Map | |
| `rawSettings` | Map | |

### mute
Actions: `mute`, `unmute`

| Field | Type | Notes |
|---|---|---|
| `action` | enum | Required |
| `deviceIds` | List\<Integer\> | Required |
| `delay` | Map | |
| `rawSettings` | Map | |

### chime

| Field | Type | Notes |
|---|---|---|
| `deviceIds` | List\<Integer\> | Required |
| `playStop` | String | |
| `soundNumber` | Integer | |
| `rawSettings` | Map | |

### siren

| Field | Type | Notes |
|---|---|---|
| `deviceIds` | List\<Integer\> | Required |
| `sirenAction` | String | |
| `rawSettings` | Map | |

---

## Rules capabilities

### privateBoolean
Note: `pvTF.<N>` field -- `true`=FALSE, `false`=True (inverted relative to field name).

| Field | Type | Notes |
|---|---|---|
| `ruleIds` | List\<Integer\> | Required |
| `value` | Boolean | Required |
| `rawSettings` | Map | |

### runRule

| Field | Type | Notes |
|---|---|---|
| `ruleIds` | List\<Integer\> | Required |
| `rawSettings` | Map | |

### cancelTimers

| Field | Type | Notes |
|---|---|---|
| `ruleIds` | List\<Integer\> | Required |
| `rawSettings` | Map | |

### pauseRule
Actions: `pause`, `resume`
Note: `pR.<N>` field -- `true`=RESUME, `false`=Pause (inverted relative to field name).

| Field | Type | Notes |
|---|---|---|
| `action` | enum | Required |
| `ruleIds` | List\<Integer\> | Required |
| `rawSettings` | Map | |

---

## Device-control capabilities

### capture

| Field | Type | Notes |
|---|---|---|
| `deviceIds` | List\<Integer\> | Required |
| `rawSettings` | Map | |

### restore
No fields required -- restores previously captured device state.

| Field | Type | Notes |
|---|---|---|
| `rawSettings` | Map | |

### refresh

| Field | Type | Notes |
|---|---|---|
| `deviceIds` | List\<Integer\> | Required |
| `rawSettings` | Map | |

### poll

| Field | Type | Notes |
|---|---|---|
| `deviceIds` | List\<Integer\> | Required |
| `rawSettings` | Map | |

### disableDevice
Actions: `disable`, `enable`
Note: `disEn.<N>` field -- `true`=ENABLE, `false`=Disable (inverted relative to field name).

| Field | Type | Notes |
|---|---|---|
| `action` | enum | Required |
| `deviceIds` | List\<Integer\> | Required |
| `rawSettings` | Map | |

### zwavePoll
Actions: `start`, `stop`

| Field | Type | Notes |
|---|---|---|
| `action` | enum | Required |
| `deviceIds` | List\<Integer\> | Optional. Z-Wave switches/dimmers to poll -- omit to poll all eligible devices |
| `target` | enum | `switches` or `dimmers`. Defaults to 'switches' |
| `rawSettings` | Map | |

---

## Flow capabilities

### delay
Pause execution for a fixed or variable duration.

| Field | Type | Notes |
|---|---|---|
| `hours` | Integer | |
| `minutes` | Integer | |
| `seconds` | Integer | |
| `cancelable` | Boolean | |
| `random` | Boolean | |
| `variable` | String | Hub variable name -- use instead of hours/minutes/seconds |

### delayPerMode

| Field | Type | Notes |
|---|---|---|
| `perMode` | Map | Required. `{modeIdOrName: {hours?, minutes?, seconds?}, ...}` |
| `rawSettings` | Map | |

### cancelDelay
No fields required.

### exitRule
No fields required.

### comment

| Field | Type | Notes |
|---|---|---|
| `text` | String | Required |

### repeat

| Field | Type | Notes |
|---|---|---|
| `hours` | Integer | At least one of hours/minutes/seconds required |
| `minutes` | Integer | |
| `seconds` | Integer | |
| `times` | Integer | |
| `stoppable` | Boolean | |

### stopRepeat
No fields required.

### repeatWhile

| Field | Type | Notes |
|---|---|---|
| `expression` | Map | Required. `{conditions: [...], operator?: 'AND'|'OR'|'XOR', operators?: [...]}`. `expression.conditions[]` follow the addRequiredExpression per-condition spec -- see "Per-condition spec" section below. |
| `hours` | Integer | |
| `minutes` | Integer | |
| `seconds` | Integer | |
| `times` | Integer | |
| `stoppable` | Boolean | |

### waitExpression

| Field | Type | Notes |
|---|---|---|
| `expression` | Map | Required. `{conditions: [...], operator?, operators?}`. `expression.conditions[]` follow the addRequiredExpression per-condition spec -- see "Per-condition spec" section below. |
| `delay` | Map | `{hours?, minutes?, seconds?}` |
| `useDuration` | Boolean | |

### waitEvents
Note: only ONE `waitEvents` action is supported per rule (RM 5.1 platform limitation -- wait-event config is stored in global per-rule scratch settings, not per-action).

| Field | Type | Notes |
|---|---|---|
| `events` | List\<Map\> | Required. Each: `{capability, deviceIds, state, andStays?}` |
| `rawSettings` | Map | |

### ifThen
Opens an IF block. Close with `capability='endIf'`. Use `elseIf`/`else` for branches.

| Field | Type | Notes |
|---|---|---|
| `expression` | Map | Required. `{conditions: [...], operator?, operators?}` |
| `rawSettings` | Map | |

`expression.conditions[]` follow the addRequiredExpression per-condition spec shape -- see "Per-condition spec" section below. The same per-capability extended fields apply: Mode `modeIds`, Between two times `start`/`end`, Variable `variable`+`comparator`, Custom Attribute `attribute`+`comparator`, compareToDevice Map. The shared walker `_rmWalkConditionReveal` handles all per-capability reveal sequences for ifThen / elseIf / repeatWhile / waitExpression (doActPage) and addRequiredExpression (STPage).

### elseIf
Continues an IF block. Needs a preceding `ifThen`.

| Field | Type | Notes |
|---|---|---|
| `expression` | Map | Required. Same `{conditions, operator?, operators?}` shape as ifThen. `expression.conditions[]` follow the addRequiredExpression per-condition spec. |
| `rawSettings` | Map | |

### else
No fields required. Needs a preceding `ifThen` or `elseIf`.

### endIf
No fields required. Closes the IF block.

---

## File capabilities

### fileWrite

| Field | Type | Notes |
|---|---|---|
| `fileName` | String | Required |
| `content` | String | Required |
| `rawSettings` | Map | |

### fileAppend
File must already exist.

| Field | Type | Notes |
|---|---|---|
| `fileName` | String | Required |
| `content` | String | Required |
| `rawSettings` | Map | |

### fileDelete

| Field | Type | Notes |
|---|---|---|
| `fileName` | String | Required |
| `rawSettings` | Map | |

---

## addRequiredExpression spec shape

### Top-level spec

| Field | Type | Notes |
|---|---|---|
| `conditions` | List\<Map\> | Required. Non-empty list of per-condition Maps (see below). |
| `operator` | String | `'AND'` / `'OR'` / `'XOR'`. Applied to every gap between conditions. |
| `operators` | List\<String\> | Per-gap operators; length = `conditions.size()-1`. Use for mixed-operator expressions like "P1 AND P2 OR P3". |

Exactly one of `operator` or `operators` must be supplied when `conditions.size() > 1`.

### Per-condition spec

| Field | Type | Notes |
|---|---|---|
| `capability` | String | Required. See STPage capability list in TOOL_GUIDE.md. |
| `deviceIds` | List\<Integer\> | Required for device-backed capabilities (Switch, Motion, Temperature, etc.). Omit for Mode, Private Boolean, Last Event Device, time-based capabilities. Convenience: pass singular `deviceId: N` (integer) instead -- the dispatcher normalizes to `deviceIds: [N]`. If both `deviceId` and `deviceIds` are provided, `deviceIds` wins. Applies recursively inside nested `subExpression.conditions[]`. |
| `state` | String | Enum value for the capability (e.g. `'on'`/`'off'` for Switch, `'active'`/`'inactive'` for Motion, `'open'`/`'closed'` for Contact, `'locked'`/`'unlocked'` for Lock, `'present'`/`'not present'` for Presence). Omit for numeric comparator path. |
| `comparator` | String | For numeric, Variable, and Custom Attribute capabilities: `'='`, `'<'`, `'>'`, `'<='`, `'>='`, `'!='`. Required together with `attribute` for Custom Attribute conditions; required together with `variable` and `value` for Variable conditions. |
| `value` | Number | Numeric threshold paired with `comparator`. |
| `attribute` | String | For `capability='Custom Attribute'`: the attribute name (e.g. `'humidity'`). Required together with `comparator`. |
| `variable` | String | For `capability='Variable'`: the hub variable name. Required together with `comparator` and `value`. The walker validates against the live schema's enum options. |
| `modeIds` | List\<String\> | For `capability='Mode'`: list of mode IDs. Alternative to `state` (mode name). |
| `start` | Map | For `capability='Between two times'`: `{type:'clock'\|'sunrise'\|'sunset', time?:'HH:mm', offset?:<minutes>}`. `time` is required when `type='clock'`; pass hub-local wall-clock (e.g. `'08:00'`), the walker converts to ISO datetime internally. `offset` (minutes) is required when `type='sunrise'` or `'sunset'`. |
| `end` | Map | For `capability='Between two times'`: same shape as `start`. |
| `compareToDevice` | Map | For numeric caps: `{deviceId:<N>, attribute:'<attr>', offset?:<N>}`. Compares against another device's attribute. |
| `subExpression` | Map | Nested paren group: `{conditions:[...], operator?:'AND'|'OR'|'XOR', operators?:[...]}`. |
| `not` | Boolean | `true` to invert this condition (NOT). Default false. |
| `rawSettings` | Map | Escape hatch: `{fieldName: value}` for fields not yet mapped above. |

### Sensor capabilities with discrete event states

Some sensor capabilities report discrete events rather than a continuous enum state.
Use the capability-specific state names below rather than expecting a numeric or
comparator-based condition:

| Capability | State values |
|---|---|
| `Water sensor` | `'wet'`, `'dry'` |
| `Smoke detector` | `'detected'`, `'clear'`, `'tested'` |
| `Carbon monoxide detector` | `'detected'`, `'clear'`, `'tested'` |
| `Carbon dioxide sensor` | `'detected'`, `'clear'` |
| `Tamper alert` | `'detected'`, `'clear'` |
| `Acceleration` | `'active'`, `'inactive'` |

For these capabilities, pass `state: 'wet'` (not `comparator: '=' / value: ...`).
