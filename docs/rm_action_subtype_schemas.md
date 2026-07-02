# RM 5.1 Action Capability Reference

Generated from `_rmActionSchemaForDiscover()` in `libraries/mcp-native-rules-lib.groovy`.
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

Note: fan `setSpeed` takes a fixed enum speed only (low / medium-low / medium / medium-high / high / on / off / auto); RM has no variable-sourced fan speed because the classic wizard exposes a variable toggle only for numeric/text value fields, not enum pickers. For a variable-driven speed, use `runCommand` with `command='setSpeed'` and `parameters=[{type:'string', variable:'<varName>'}]` (per-parameter variable sourcing).

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
| `parameters` | List | Each slot is `{type, value}` (literal) or `{type, variable}` (variable-sourced). `type` is one of `'number'`, `'decimal'`, or `'string'` (lowercase) -- it maps to RM's per-parameter `cpType` selector. Literal: `[{type: 'number', value: 75}]` or `[{type: 'string', value: 'auto'}]`. Variable-sourced: `[{type: 'number', variable: 'myVar'}]`. Mix literal and variable entries across slots. Fails loud (success=false with descriptive error) if the hub does not reveal the xVar enum after enabling variable mode for a slot. |
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
Also accepted as `capability='variable'`. Sets a hub variable from one of four mutually-exclusive source modes: a constant, a copy of another variable, a device attribute, or structured variable math. Exactly ONE of `value` / `sourceVariable` / `fromDevice` / `math` per action. The `value`, `fromDevice`, and `math` source modes all require a Number or Decimal target variable -- RM renders the `numOp`/`valNumber` source fields only for numeric targets, so requesting any of them into a String/Boolean/DateTime variable is rejected with `success=false` before the write. To assign a String/Boolean/DateTime target use `sourceVariable` (copy from another variable), or `rawSettings`.

| Field | Type | Notes |
|---|---|---|
| `variable` | String | Required. Target hub variable name. Must be an existing hub variable -- an unknown name is rejected before the hub write to prevent silent broken-action state. |
| `value` | Number | Numeric constant to assign -- provide exactly one source mode. Requires a Number or Decimal target variable (rejected with `success=false` before the write otherwise). String, boolean, and datetime hub-variable targets are not supported via `value`; use `sourceVariable`, or set those types via `rawSettings`. Wire: `numOp=number` + `valNumber.<N>`. |
| `sourceVariable` | String | Source variable name to copy from -- provide exactly one source mode. Must be an existing hub variable -- an unknown name is rejected before the hub write. Schema-gated: the source-variable field is only revealed by RM after the `numOp=variable` write; fails loud if the hub does not reveal it. Wire: `numOp=variable` + `xVar3.<N>`. |
| `fromDevice` | Map | `{deviceId: <Integer>, attribute: '<name>'}` -- read the value from a device attribute. Provide exactly one source mode. Requires a Number or Decimal target variable (rejected with `success=false` before the write otherwise). Maps to `numOp='device attribute'`. The device picker (`customDev.<N>`) and the attribute enum (`tCustomAttr.<N>`, FILTERED to that device's live attributes) are schema-gated and revealed in sequence; the implementation fails loud if the device picker is not revealed. `deviceId` may be ANY hub device (RM's picker spans all devices, not just MCP-selected); it is validated only as a positive-integer id, not against the MCP device set. An attribute not offered by the device's filtered enum is rejected with the available list. See `docs/rm_wire_format.md` for the wire sequence. |
| `math` | Map | `{left: <varName\|Number>, op: '<operator>', right: <varName\|Number>}` -- structured variable math. Provide exactly one source mode. Requires a Number or Decimal target variable (rejected with `success=false` before the write otherwise). Maps to `numOp='variable math'`. A Number operand becomes a `(constant)` (`valConst.<N>` / `valConst2.<N>`); a String operand is a hub variable name (`xVar3.<N>` / `xVar4.<N>`), validated against the hub variable list. Binary operators `+ - * / %` require `right`; unary operators `negate absolute round random sqrt sin cos tan asin acos atan log toRadians toDegrees` reject `right`. Operand fields are schema-gated and revealed in sequence; fails loud if a required field is not revealed. See `docs/rm_wire_format.md` for the wire sequence. |
| `delay` | Map | |
| `rawSettings` | Map | |

### setLocalVariable
Sets a rule-LOCAL variable (the per-rule variables created via `hub_set_rule` `addLocalVariable`), as opposed to `setVariable`, which targets hub globals. Same `getSetVariable` action and same four mutually-exclusive source modes (`value` / `sourceVariable` / `fromDevice` / `math`) as `setVariable` -- the only difference is that the `variable` target is validated against the rule's local variables (`state.allLocalVars`) rather than the hub global list. Use this when a local and a hub variable share a name and you mean the local. The `value`, `fromDevice`, and `math` source modes still require a Number or Decimal target; for a String/Boolean/DateTime local use `sourceVariable`.

| Field | Type | Notes |
|---|---|---|
| `variable` | String | Required. Target LOCAL variable name. Must be an existing local variable on this rule (create one first via `addLocalVariable`) -- an unknown name is rejected before the hub write. The picker section headers ` --LOCAL VARIABLES--` / ` --HUB VARIABLES--` are rejected (they are not variable names). |
| `value` | Number | Numeric constant to assign -- provide exactly one source mode. Requires a Number or Decimal target local variable (rejected with `success=false` before the write otherwise); for a String/Boolean/DateTime local use `sourceVariable`. Wire: `numOp=number` + `valNumber.<N>`. |
| `sourceVariable` | String | Source variable name to copy from -- provide exactly one source mode. The source may be a LOCAL or a HUB variable, **because RM's source picker (`xVar3`) spans both namespaces -- a local target may legitimately copy from a hub global**; the source is therefore validated against the live revealed enum (which spans both), NOT pre-checked against the locals-only list (fails loud if not revealed). Wire: `numOp=variable` + `xVar3.<N>`. |
| `fromDevice` | Map | `{deviceId: <Integer>, attribute: '<name>'}` -- same wire and validation as `setVariable`'s `fromDevice`. Requires a Number or Decimal target. |
| `math` | Map | `{left, op, right}` -- same operator set and wire as `setVariable`'s `math`. Requires a Number or Decimal target; operand variables may be local or hub (validated against the live revealed enum). |
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

**Activating a Scene / Room Lighting group is NOT a dedicated action here.** RM 5.1 has no activate-scene subtype. Each Scene / Room Lighting instance spawns an activator device that carries the `switch` capability -- activate it with the Switch action: `capability='switch'` + `action='on'` + `deviceIds=[<activatorDeviceId>]` (use `action='off'` to send an off/deactivate command, whose effect is configuration-dependent). The `activate_scene` action exists only on the legacy custom rule engine (the `hub_*_custom_rule` tools), not on this native addAction surface.

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
| `events` | List\<Map\> | Required. Each device event: `{capability, deviceIds, state, andStays?}`. A **Mode** event is the exception: `{capability:'Mode', state:'Night'}` or `{capability:'Mode', state:['Away','Night']}` (mode names, case-insensitive) or `{capability:'Mode', modeIds:['3']}` / `modeIds:['3','5']` (IDs from `hub_list_modes`). A Mode event takes no `deviceIds`; the mode value is written to the discovered mode picker (`modesX-<N>` family, keyed by mode ID), never to `tstate-<N>`. The resolved IDs are validated against the live picker options -- an unknown mode or an ID the picker does not offer fails loud. |
| `rawSettings` | Map | |

### ifThen
Opens an IF block. Close with `capability='endIf'`. Use `elseIf`/`else` for branches.

| Field | Type | Notes |
|---|---|---|
| `expression` | Map | Required. `{conditions: [...], operator?, operators?}` |
| `rawSettings` | Map | |

`expression.conditions[]` follow the addRequiredExpression per-condition spec shape -- see "Per-condition spec" section below. The same per-capability extended fields apply: Mode `modeIds`, Between two times `start`/`end`, Variable `variable`+`comparator`, Custom Attribute `attribute`+`comparator`, compareToDevice Map. The shared walker `_rmWalkConditionReveal` handles all per-capability reveal sequences for ifThen / elseIf / repeatWhile / waitExpression (doActPage) and addRequiredExpression (STPage).

**Note: nested `subExpression` is REQUIRED-EXPRESSION-ONLY today.** `ifThen` (and its siblings `elseIf` / `repeatWhile` / `waitExpression`) rejects nested `subExpression` in `expression.conditions[]` with `"nested subExpression on this row is not yet supported"`. Either flatten the conditions list, or move the nested expression into a Required Expression -- `addRequiredExpression` supports nesting. See the `subExpression` row in the per-condition field table below.

### elseIf
Continues an IF block. Needs a preceding `ifThen`.

| Field | Type | Notes |
|---|---|---|
| `expression` | Map | Required. Same `{conditions, operator?, operators?}` shape as ifThen. `expression.conditions[]` follow the addRequiredExpression per-condition spec. Nested `subExpression` is REQUIRED-EXPRESSION-ONLY -- see ifThen note above. |
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
| `comparator` | String | For numeric, Variable, and Custom Attribute capabilities: `'='`, `'<'`, `'>'`, `'<='`, `'>='`, `'!='`. ASCII forms `'!='` / `'<>'` / `'=='` are accepted and auto-mapped to RM's Unicode glyphs `'≠'` / `'='`. Required together with `attribute` for Custom Attribute conditions; required together with `variable` and `value` for Variable conditions. State-change comparators (`'*changed*'` / `'*became*'`) are the only exemption from the RHS-value requirement on Variable conditions. |
| `value` | Number | Numeric threshold paired with `comparator`. |
| `attribute` | String | For `capability='Custom Attribute'`: the attribute name (e.g. `'humidity'`). Required together with `comparator`. |
| `variable` | String | For `capability='Variable'`: the hub variable name. Required together with `comparator` and `value`. The walker validates against the live schema's enum options. |
| `modeIds` | List\<String\> | For `capability='Mode'`: list of mode IDs. Alternative to `state` (mode name). |
| `start` | Map | For `capability='Between two times'`: `{type:'clock'\|'sunrise'\|'sunset', time?:'HH:mm', offset?:<minutes>}`. `time` is required when `type='clock'`; pass hub-local wall-clock (e.g. `'08:00'`), the walker converts to ISO datetime internally. `offset` (minutes) is required when `type='sunrise'` or `'sunset'`. |
| `end` | Map | For `capability='Between two times'`: same shape as `start`. |
| `compareToDevice` | Map | For numeric caps: `{deviceId:<N>, attribute?:'<attr>', offset?:<N>}`. Compares against another device's reading on the SAME capability, optionally offset by a decimal amount. The reference device must carry the LHS capability; `attribute` is OPTIONAL and informational (no wire consumer -- neither validated nor written -- because the compared attribute is implied by the shared capability). `comparator` is required. Mutually exclusive with `state`/`value` (literal RHS) AND with `compareToVariable` (variable RHS) -- supplying compareToDevice alongside any of them is rejected; use `offset` for an offset. Passing compareToDevice on a non-numeric capability (Mode / Between two times / Variable / Custom Attribute) is rejected up front with a fail-loud error naming the capability -- it is NOT silently dropped. The reference `deviceId` is existence-validated hub-wide before any write (the same `/device/fullJson` check the LHS `deviceIds` get); a nonexistent id is rejected up front. `relDevice_<N>` is a capability.* device picker; on normal firmware RM populates it client-side, so the schema exposes no options and the walker does not pre-validate the id against an option list -- a capability-mismatched reference device surfaces in the rendered/broken state rather than a pre-write error. On a rare firmware variant that DOES surface device-picker options, the walker additionally defensively rejects a reference id absent from that list. `offset` is optional: when the offset slot (`state_<N>`) is absent on the firmware, the offset is dropped and the call degrades with a `partial:true` + `offset_field_not_revealed` sentinel (the device-relative comparison still commits without it). **NOTE (intentional isDev/isVar asymmetry -- do not "fix"):** an EMPTY option list is NORMAL for `compareToDevice`'s `relDevice_<N>` because it is a capability.* DEVICE picker (no options, no sentinel, no partial). This deliberately differs from `compareToVariable`, whose right-hand picker is an ENUM picker where an empty option list IS an anomaly and emits a `compareToVariable-validation` / `api_unavailable` sentinel with `partial:true`. (That right-hand field name is firmware-assigned -- the static `selectTriggers` path exposes `xVarR_<N>`, but the walker pages can expose a differently-suffixed field, so the walker resolves whatever the live schema reveals rather than hardcoding the slot.) The divergence reflects picker type (device vs enum), not an oversight. |
| `subExpression` | Map | **`addRequiredExpression`-only.** Nested paren group: `{conditions:[...], operator?:'AND'|'OR'|'XOR', operators?:[...]}`. The STPage walker recursively handles nested sub-expressions of arbitrary depth. `addAction` / `ifThen` / `elseIf` / `repeatWhile` / `waitExpression` reject this field with a targeted error -- flatten the conditions list or move the nested expression to a Required Expression. |
| `not` | Boolean | `true` to invert this condition (NOT). Default false. |
| `rawSettings` | Map | Escape hatch: `{fieldName: value}` for fields not yet mapped above. |

### Sensor capabilities with discrete event states

Some sensor capabilities report discrete events rather than a continuous enum state.
Use the capability-specific state names below rather than expecting a numeric or
comparator-based condition:

| Capability | State values |
|---|---|
| `Water sensor` | `'wet'`, `'dry'` |
| `Smoke detector` | `'detected'`, `'clear'` |
| `Carbon monoxide detector` | `'detected'`, `'clear'` |
| `Tamper alert` | `'detected'`, `'clear'` |
| `Acceleration` | `'active'`, `'inactive'` |

For these capabilities, pass `state: 'wet'` (not `comparator: '=' / value: ...`).

**Carbon dioxide sensor is NOT in this table.** The `CarbonDioxideMeasurement` capability is numeric ppm (use `comparator: '>'` + `value: 1000` etc.), not a discrete enum. The name is superficially symmetric to `Carbon monoxide detector` but RM 5.1 treats CO2 as a numeric comparator condition; the state-based shape is rejected at the walker. See the `DISCRETE_EVENT_CAPS` rationale comment in `libraries/mcp-native-rules-lib.groovy`.
