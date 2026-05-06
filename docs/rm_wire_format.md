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
`shade`, `fan`, `button`, `runCommand`, `mode`, `log`, `notification`,
`httpGet`, `httpPost`, `ping`, `volume`, `mute`, `chime`, `siren`,
`privateBoolean`, `runRule`, `cancelTimers`, `pauseRule`, `capture`, `restore`,
`refresh`, `poll`, `disableDevice`, `delay`, `delayPerMode`, `cancelDelay`,
`exitRule`, `comment`, `repeat`, `stopRepeat`, `repeatWhile`, `waitExpression`,
`waitEvents`, `ifThen`, `elseIf`, `else`, `endIf`, `fileWrite`, `fileAppend`,
`fileDelete`, `zwavePoll`.

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
