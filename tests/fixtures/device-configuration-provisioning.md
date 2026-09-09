# Persistent device configuration fixtures

The configuration matrix and its separate LAN dispatch scenario only discover and
exercise existing fixtures. They never install a driver, create a device, modify
the MCP allowlist, or delete infrastructure. Missing, duplicate, wrongly selected,
stale, or unrestored fixtures fail with provisioning guidance.

Provision once through the direct test-hub admin endpoints after confirming no
E2E run is active. Direct endpoints have not yet been supplied or inspected for
this revision. Do not use a personal hub or the E2E watchdog to provision these
devices. Further integration or radio setup requires the user's scope decision.

## Standing core profiles

Install `device-configuration.groovy` as `mcptest.E2E_PERM_Configuration`, then
install a second copy with only the definition name changed to
`E2E_PERM_Configuration_Alternate`. Both must expose fixture version 2 and the
same preferences and commands. The alternate is used for reversible driver
switching; it is never created or removed inside E2E.

Provision the labels from `device-configuration-manifest.json` exactly:

| Label suffix after `BAT_E2E_KEEP_Configuration_` | Native ownership | MCP exposure | Evidence from the scenario |
|---|---|---|---|
| `Child` | Non-component child of the MCP app, created by its child-device SDK path | Authorized as a child | Child DeviceWrapper dispatch |
| `StandaloneSDK` | Standalone, native `parentAppId=null` | Explicitly selected in the MCP app | Ordinary selected DeviceWrapper dispatch |
| `StandaloneBypass` | Standalone, native `parentAppId=null` | Unselected; existing E2E bypass toggle permits access | Native ID-endpoint dispatch |

The native `/device/createVirtual` endpoint accepts a user-driver type and makes
a standalone instance. That endpoint name, an MCP `virtual` projection, and a
label do not establish equivalence to a paired physical device. Preserve native
`parentAppId`, `controllerType`, `virtual` (when supplied), and `isComponent` in
the observer record; the E2E log identifies each dispatch profile separately.
Require editable native name, DNI, and driver controls on all three profiles.
The matrix changes and restores DNI and driver by stable device ID, including
the child; an unavailable control fails provisioning instead of skipping a path.

Set the primary driver on all three devices and initialize these saved values
once, using direct native preference writes with complete preserved pane
controls. Verify both settings and inputValues through each observer before
running E2E. Do not rely only on declaration defaults or `installed()` firing.

| Preference | Saved value |
|---|---|
| `probeBool` | Boolean `false` (declaration default is `true`) |
| `probeNumber` | Integer `3` |
| `probeText` | `original saved text` |
| `probeEnum` | `eco` |
| `probeMultiple` | Runtime List `["red"]`, with the native multiple declaration retained |

Set `showOnHome=true`, `defaultCurrentState="switch"`, and leave the room empty.
Keep each device enabled and save the owned Data key `configurationProbe=original`
on all three devices during provisioning. The SDK profiles change and restore
that saved key; bypass explicitly refuses the unsupported Data writer.
Use restorable history limits (for example `maxEvents=40`, `maxStates=30`,
`spammyThreshold=300`) and confirm those values are within the public edit bounds.
Preserve the native retry setting. Clear any leftover `largeReadProbe` or pending
LAN probe before approving the fixture baseline. Ensure the existing permanent
`BAT_E2E_KEEP_Room` is present. Device labels keep the `KEEP_` prefix even while
temporarily changed, so ordinary artifact sweeps cannot delete them.

The test applies independent native fields and preferences in a grouped patch,
checks one nonce-stamped native snapshot plus one configuration read per phase,
then restores the saved native fields and preferences in one patch. Driver restoration precedes the
grouped preference restoration because changing a driver and preferences in one
patch is prohibited. The final enabled-state probe disables the target and uses
another standing profile to read its native page; re-enabling precedes driver and
preference restoration so observation never depends on a disabled driver running.
Large-state cleanup is independently verified. Every
restoration failure is recorded in `_fixture_reset_failures` and fails the run.

## Native prerequisite decisions

All eight rows below start as `pending` in the manifest. This is pending positive
coverage awaiting the user's disposition, not approved exclusion or proof of
coverage. The matrix prints native applicability/writability and fails before
configuration mutations if that profile has any pending row. A run with approved
unavailable rows must not be reported as positive radio/integration coverage.
Final delivery remains blocked until the
user disposes of every pending positive prerequisite; a green core run alone
does not settle those decisions.

After direct inspection and user approval, a profile can override `nativeFields`:

```json
"nativeFields": {
  "retryEnabled": {"expectation": "unavailable", "target": true},
  "dashboardIds": {"expectation": "positive", "target": [123]}
}
```

`123` is illustrative only: never copy it onto a hub. Positive targets must refer
to owned fixture resources and change the current value. `unavailable` checks
native applicability and asserts that an attempted write is refused; it is
negative-path coverage, not a substitute for a positive integration test. If a
previously unavailable field becomes applicable, the test requires the manifest
to be reviewed before proceeding.

| Field | Native prerequisite for positive coverage | Current evidence limit |
|---|---|---|
| `zigbeeId` | An owned software fixture with a preapproved synthetic, locally administered nonempty identifier and a restorable baseline | A synthetic identifier persisted on personal software mocks without a radio node. That proves property storage, not Zigbee commands or pairing. Never target a paired device's identity. |
| `dashboardIds` | Native dashboard availability plus a preprovisioned owned test dashboard ID, named with `E2E_PERM_` | Mock software does not create a Dashboard app or justify changing unrelated assignments. The dashboard sweep does not exempt `BAT_E2E_KEEP_`. |
| `meshEnabled` | Hub Mesh enabled and the device's native mesh-selection flag | A driver alone cannot enable native Mesh service. This toggle does not prove linked-hub propagation. |
| `meshFullSync` | An actual owned linked-device fixture and native refresh support, generally requiring a second configured hub | The three local core fixtures cannot manufacture linked-device state. A separate approved profile is required; none is provisioned by this change. |
| `homeKitEnabled` | Installed native HomeKit support and a compatible native device with an editable selection | Personal software mocks did not expose this control. Copying a Boolean into a mock is not positive native coverage. |
| `amazonAlexaEnabled` | Installed/supported Alexa integration and an owned fixture assignment | Native assignment persistence and external assistant operation are distinct claims. |
| `googleHomeEnabled` | Installed/supported Google Home integration and an owned fixture assignment | Native assignment persistence and external assistant operation are distinct claims. |
| `retryEnabled` | Native retry availability/selection support | Adding a synthetic Zigbee ID did not enable retry on personal mocks. Driver declarations cannot force this platform capability. |

The initial manifest supports the three independent core profiles. Do not append
a linked Mesh device to that core list: linked devices deliberately cannot run
the ordinary writable-preference/identity lifecycle. Once such infrastructure is
approved, give its field-specific scenario separate preconditions and evidence.

## Separate asynchronous LAN proof

Upload `device-configuration-response.json` once to the test hub File Manager as
`E2E_PERM_Configuration_Response.json`. File sweeps do not exempt `BAT_E2E_KEEP_`,
so the response deliberately uses `E2E_PERM_`. The fixture's `sendLanProbe` explicitly
calls `sendHubCommand` for an HTTP GET to this owned loopback file and emits a
nonce acknowledgment only from a valid response callback. The separate E2E
scenario verifies that callback and resets the observer for each dispatch
profile. It changes no household switch, sends no radio traffic, and never
recreates the response file during a test.

The fixture serializes entry points and forbids overlapping pending probes.
`clearLanProbe` refuses to erase an unconsumed pending request: a timeout or invalid
reply records a cleanup failure and prevents a new request from borrowing its
late callback. Only a valid callback consumes the pending request. Inspect a
failed pending probe before manually repairing its state; automatic E2E cannot
reset it. This does not test a driver's returned-HubAction convention,
external LAN device behavior, or virtual/physical equivalence. Personal probes
found returned-only HubAction did not execute on either native bypass or SDK
paths, so this scenario deliberately proves the separately observed explicit
send contract.
