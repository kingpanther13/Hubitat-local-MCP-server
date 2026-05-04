# Section 2 Trigger Tests — Progress Notes

Status as of 2026-04-25 ~02:52 EDT. Notes for resuming work.

## Architectural fixes committed (local only, not pushed)

The most important changes captured live via Chrome DevTools network panel:

### 5ace616 — `_rmClickAppButton` body shape
**Discovered via Chrome network capture of the real Hubitat UI's hasAll click.**
The hub's button handler accepts the bare `<btn>=clicked` POST with HTTP 200
but does NOT fully process wizard-Done buttons without:
- `settings[<btn>]=clicked` (bracket form, not bare `<btn>=clicked`)
- `formAction=update`
- `version=<app version>`
- `currentPage=<pageName>`
- `pageBreadcrumbs=["mainPage"]`

With the corrected body, **a single `hasAll` click commits the trigger
cleanly** — editor closes, no scaffold residue, no phantom trigger N+1.
The previous 2x-hasAll workaround was masking this bug.

### 9ee049a — `isCondTrig.<N>=false` finalize
For trigger 2+, even after the bracket-form hasAll, the wizard often leaves
`isCondTrig.<N>` as a residual "Conditional Trigger?" prompt. Auto-write
`isCondTrig.<N>=false` finalizes cleanly. (Cleaner than clicking hasAll
twice, which would allocate a phantom **Broken Trigger** N+1.)

## Section 2 test status

✓ = passes via curl with the corrected wizard flow (will pass through
update_native_app once 5ace616 + 9ee049a are deployed)
⚠ = needs more exploration

| Test | Status | Notes |
|---|---|---|
| T320 Motion baseline | ✓ | Single device |
| T321 Multi-device switch | ✓ | Covered by T314 (multiple=true preserved, 2 subs) |
| T322 Multi-trigger OR | ✓ | Contact + Motion + Temperature numeric. 3 triggers, 3 subs |
| T323 Conditional trigger | ⚠ | `isCondTrig=true` + `condTrig.<N>=a` opens condition sub-wizard with `rCapab_<N>` / `rDev_<N>` / `state_<N>`. The condition's hasAll click incorrectly opens trigger N+1 — needs separate "doneCond" or non-hasAll commit path |
| T324 And-stays | ✓ | Contact open + `stays1=true` + `SMins1=2`. 1 subscription |
| T325 Cond+Stays | ⚠ | Depends on T323 |
| T326 Disable trigger | ✓ | `disableT2` button click toggles disabled state. Disabled trigger row shows red italic + checked checkbox |
| T327 Button capability | ✓ | `tCapab=Button`, `tDev`, `ButtontDev<N>` (button number), `tstate=pushed`. Subscribes after device's button attribute is initialized |
| T328 Physical/Digital | likely ✓ | tCapab options include "Physical Switch", "Digital Switch", "Physical dimmer level" — same pattern as Switch capability |
| T329 Numeric comparators | likely ✓ | Battery/Humidity/etc. use the same `ReltDev<N>` (comparator) + numeric `tstate<N>` pattern as Temperature in T322 |
| T330 Enum sensors | likely ✓ | CO/Smoke/Gas/etc. use the same enum pattern as Contact in T322 |
| T331 Lock/Garage/Door/Valve/Shade | likely ✓ | Same pattern as Contact |
| T332 Presence/Power source | likely ✓ | Same pattern as Motion |
| T333 Fan/Music/Thermostat | likely ✓ | Numeric setpoint = ReltDev pattern; mode/state = enum pattern |
| T334 Custom attribute | not tested | Schema shape unknown — needs probing |
| T335 Keypad/Lock codes | skipped | Hubitat virtual driver may not exist |
| T336a Sunrise + offset | ⚠ | tCapab=Certain Time, time1=Sunrise reveals atSunriseOffset1. Settings save but trigger doesn't materialize in summary table — needs different commit path than device triggers |
| T336b Days of week | not tested | |
| T337 Time of day | ⚠ | Same wizard quirk as T336a — settings save but trigger doesn't show in summary |
| T338 Periodic schedule variants | not tested | |
| T339 Between two times/dates | not tested | These are condition-only capabilities |
| T340 Time since event | not tested | |
| T341 HSM/Mode/PrivateBoolean/Variable | not tested | |
| T342 HSM alert + Security keypad | not tested | |

## Known wizard quirks worth investigating

1. **Time-based triggers (T336a, T336b, T337, T338, T340)** — wizard's
   commit path differs from device triggers. Settings persist via
   `/installedapp/update/json` but the trigger row doesn't materialize.
   Hypothesis: an additional flow step like setting `time1=A specific time`
   THEN setting `atTime1=<ISO>` THEN clicking a different button (not
   hasAll) is needed.

2. **Conditional triggers (T323, T325)** — the condition has its own
   sub-wizard with `rCapab_<N>`, `rDev_<N>`, `state_<N>`. Calling hasAll
   from inside the condition wizard opens trigger N+1 (probably a
   double-commit bug). Need different button to close just the condition.

## Local-only commits queued (not yet pushed)

```
5ace616 fix(rm-native): _rmClickAppButton sends settings[<btn>]=clicked + form context (one-click hasAll)
9ee049a fix(rm-native): replace hasAll-second-click hack with isCondTrig finalize (no phantom triggers)
```

Push these (and earlier d8c3691 / 82743a3 / 74ab17e / 9efd31d / 63988fb / 7ce3ba5 / 4267389 / 7e856ce already pushed) to `feature/rm-native-crud-120` once Section 2 is complete enough for Daisy.

## Build marker for verifying deploy

`get_hub_info.mcpServerVersion` returns `0.10.1+9ee049a-prdev-build-marker` after deploy. File header line 7 has the same suffix for ctrl-f.
