# RM 5.1 Wire Format Notes

Internal reference for the RM 5.1 HTTP API helpers in `hubitat-mcp-server.groovy`.
Moved from in-source Groovydocs to avoid multi-paragraph docblock sprawl.

---

## _rmAddAction

High-level orchestrated action creation. Replaces the 6-7 manual wizard calls
(init selectActions → click N with stateAttribute=doActN → set actType → set
actSubType → set type-specific fields → wait for actionDone → click actionDone).

### Wire-format quirks (firmware 2.5.0.123, verified live via Chrome DevTools + curl)

1. **stateAttribute=doActN** — the "Create New Action" button (name=N) requires
   the literal concatenation of "doAct" and the button name "N". Sending
   `stateAttribute=doAct` alone sets `state.doAct='N'` but NOT `state.doActN`,
   and doActPage then errors with "Cannot invoke method startsWith() on null object".

2. **Incremental schema** — doActPage's schema is incremental: `actionDone` only
   appears AFTER all required type-specific fields are set. `_rmWriteSettingOnPage`
   re-fetches the schema before each write, so calling it for every field
   guarantees `actionDone` is present by the final click.

3. **state.actNdx initialization** — selectActions' page hook initializes
   `state.actNdx`. On a freshly created rule with zero actions, `state.actNdx`
   is null and doActPage renders with `actType.null` (broken). Fire an empty
   POST to selectActions FIRST to initialize actNdx -- `_rmInitSelectActionsPage`
   handles this idempotently.

4. **Navigation bake** — the helper navigates `doActPage→selectActions` at the
   end so the action is fully baked into `actions[]` and `state.actNdx` is
   advanced before the next `addAction` can land. The navigation marker drives
   the action bake; firing `updateRule` per-action is not needed (and would add
   latency in batch scenarios).

### Capability families and spec fields

Use `addAction({discover: true})` to get the live schema from the code. Key
families: `switch`, `dimmer`, `color`, `colorTemp`, `lock`, `thermostat`,
`shade`, `fan`, `button`, `runCommand`, `mode`, `setVariable` (alias: `variable`),
`log`, `notification`, `httpGet`, `httpPost`, `ping`, `volume`, `mute`, `chime`,
`siren`, `privateBoolean`, `runRule`, `cancelTimers`, `pauseRule`, `capture`,
`restore`, `refresh`, `poll`, `disableDevice`, `delay`, `delayPerMode`,
`cancelDelay`, `exitRule`, `comment`, `repeat`, `stopRepeat`, `repeatWhile`,
`waitExpression`, `waitEvents`, `ifThen`, `elseIf`, `else`, `endIf`, `fileWrite`,
`fileAppend`, `fileDelete`, `zwavePoll`.

### Optional modifiers (every action)

- `delay { hours, minutes, seconds, cancelable }` — sets `delayAct.<N>` +
  duration sub-fields
- `rawSettings { fieldName: value }` — escape hatch; use `@N` in field name as
  a placeholder for the action index

---

## _rmAddRequiredExpression

High-level orchestrated Required Expression creation. Replaces the 7+ manual
wizard calls (useST=true → navigate STPage → cond=a → rCapab/rDev/state per
condition → hasAll → oper → hasRule → done).

### Spec shape

```json
{
  "conditions": [
    {"capability": "Switch", "deviceIds": [282], "state": "on"},
    {"capability": "Motion", "deviceIds": [284], "state": "active"}
  ],
  "operator": "AND"
}
```

### Per-condition fields

| Field | Notes |
|---|---|
| `capability` | "Switch" / "Motion" / "Contact" / "Lock" / "Presence" / "Temperature" / "Humidity" / "Custom Attribute" / "Mode" / "Private Boolean" / etc. |
| `deviceIds` | Required for device-backed capabilities. Omit for Mode / Private Boolean / time-based. |
| `state` | Enum value ("on", "active", "open", "locked", "present", "true"/"false"). Omit for numeric comparator path. |
| `comparator` | For numeric capabilities: "<=", "=", ">", "<", ">=", etc. |
| `value` | Numeric threshold (paired with comparator). |
| `attribute` | For Custom Attribute capability: the attribute name. Required together with `comparator`. |
| `not` | `true` to invert this condition (NOT). |
| `rawSettings` | Escape hatch `{fieldName: value}` for unmapped fields. |

### Post-commit appSettings

- `useST=true`
- `rCapab_<N>`, `rDev_<N>`, `state_<N>` per condition
- `oper=<AND|OR|XOR>` when multi-condition (ephemeral -- may not persist after commit)

### STPage wizard internals (firmware 2.5.0.123)

Phase 1 -- condition building (one pass per condition):

1. `mainPage.useST=true` exposes the "Define Required Expression" href
2. STPage's `hrefParams` is `{unUsed: null}` -- placeholder only
3. STPage shows enum `cond` with options `a` (new condition) / `b` (sub-expression) / `c` (NOT)
4. After `cond=a`, schema reveals `rCapab_<N>` where N is the live cond counter
   (NOT necessarily 1 -- RM increments globally across the parent app)
5. After `rCapab_<N>=Switch`, `rDev_<N>` appears
6. After `rDev_<N>=[id]`, `state_<N>` appears
7. After `state_<N>=on`, `hasAll` button appears -- click to commit this slot
8. Repeat 3-7 for each condition, writing `oper=<AND|OR|XOR>` between conditions

Phase 2 -- sealing:

9. `hasRule` is a `submitOnChange` button (HTML value="button"). Writing it as a
   settings write to `/installedapp/update/json` SEALS the assembled formula.
   Using `/installedapp/btn` does NOT trigger the commit handler -- verified live:
   `/installedapp/btn` left conditions as "(unused)"; the settings-write path
   committed correctly.
10. After `hasRule`, the `doneST` button is a no-op. The proper exit is via the
    back-nav `_action_previous=Done`.

### predCapabs leak and the ghost ifThen workaround

After `hasRule` seals the expression, `atomicState.predCapabs` retains the RE's
condition context. A subsequent `addAction` for a plain (non-expression) action
would be wrapped in `IF(**Broken Condition**)`. `_rmClearPredCapabsViaGhostIfThen`
resets `predCapabs` by opening a `condActs/getIfThen` slot and immediately
canceling it -- the `getIfThen` initializer zeroes predCapabs; `actionCancel`
discards the slot before it bakes.

See `_rmClearPredCapabsViaGhostIfThen` source comment for the full probe-matrix
evidence.

---

## setVariable wire format

Live-verified wire format for `addAction(capability='setVariable')`.
Maps to `actType=modeActs`, `actSubType=getSetVariable`.

**Fields written to doActPage (action index N):**

| Field | Value | Notes |
|---|---|---|
| `xVarV.<N>` | hub variable name | Target variable (enum from hub variables list). Must be an existing hub variable name -- an unknown name is rejected before the hub write to prevent silent broken-action state. |
| `numOp.<N>` | `"number"` or `"variable"` | `"number"` = constant-value form; `"variable"` (full word) = copy-from-variable form. Live-verified: the short form `"var"` is NOT accepted by RM 5.1 and causes the action to bake without a source variable. |
| `valNumber.<N>` | numeric constant | Written when `numOp=number`. Only numeric constants are supported; string/boolean/datetime targets require `sourceVariable` or `rawSettings`. |
| `xVar3.<N>` | source variable name | Written when `numOp=variable` (the `sourceVariable` form). Schema-gated: this field is only revealed by RM after `numOp=variable` is written. The field name `xVar3` is live-verified for RM 5.1; the implementation discovers it from the live schema rather than hardcoding. Must be an existing hub variable name -- an unknown name is rejected before the hub write to prevent silent broken-action state. |

`value` and `sourceVariable` are mutually exclusive; providing both is rejected.
The `value` path always writes `numOp=number` + `valNumber` -- the hub's wire
format does not expose separate type-specific constant slots for string/boolean/datetime
at this subtype. Use `sourceVariable` to copy from a variable of any type, or
`rawSettings` to supply advanced wire fields directly.

---

## runCommand extra parameters -- moreParams / P-discovery wire sequence

Live-verified wire format for `addAction(capability='runCommand', parameters=[...])`.
P is RM-assigned (starts at 2, never computed by the caller).

**Per-parameter sequence (repeat for each parameter):**

1. Click the `moreParams` button (`_rmClickAppButton`). RM allocates the next
   parameter slot and reveals `cpType<P>.<N>` in the doActPage schema.

2. Re-introspect doActPage. Find the newly-revealed `cpType<P>.<N>` field by
   diffing against the pre-click schema snapshot. P is extracted from the field
   name (e.g. `cpType2.1` -> P=2). This P-discovery step is mandatory -- P is
   never 1 and is never derivable from parameter index.

3. Write `cpType<P>.<N> = type` (lowercase: `number`, `decimal`, `string`).
   This reveals `uVar<P>.<N>` (bool toggle) and `cpVal<P>.<N>` (literal text
   input) in the schema.

4a. **Literal value path**: write `cpVal<P>.<N> = value`. Done.

4b. **Variable-sourced path**: write `uVar<P>.<N> = "true"`. Re-introspect.
    `xVar<P>.<N>` (an enum of live hub variable names) appears; `cpVal<P>.<N>`
    disappears. If `xVar<P>.<N>` is NOT revealed after the uVar write (firmware
    gap, unsupported command), fail loud with `IllegalArgumentException` -- a silent
    fall-through would produce a rule that renders broken with no caller-visible
    error. Validate the target variable name is present in the enum options; fail
    loud with the available list if not. Write `xVar<P>.<N> = variableName`.

**Persisted state (live-verified, firmware 2.5.0.123):**

- Literal param: `cpType<P>.N = type`, `cpVal<P>.N = value`
- Variable param: `cpType<P>.N = type`, `uVar<P>.N = "true"`, `xVar<P>.N = varName`

`cpVar` does NOT exist in the RM 5.1 schema. Writing it is silently ignored.

Two parameter shapes exist because RM 5.1 exposes separate literal (`cpVal<P>`) and
variable (`uVar<P>`+`xVar<P>`) reveal paths that cannot be unified into a single write
sequence -- the hub shows or hides `cpVal<P>` vs `xVar<P>` based on the current value
of `uVar<P>`, so they are mutually exclusive at the schema level.
