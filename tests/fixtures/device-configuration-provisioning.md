# Persistent device configuration fixtures

The configuration matrix and its separate LAN dispatch scenario only discover and
exercise existing fixtures. They never install a driver, create a device, modify
the MCP allowlist, or delete infrastructure. Missing, duplicate, wrongly selected,
stale, or unrestored fixtures fail with provisioning guidance.

Provision once through a direct connection to the test hub after confirming no
E2E run is active. Routine E2E does not provision or remove these resources.
Review the native prerequisites before adding integration or radio infrastructure.

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

Leave the room empty. The matrix reads the actual `showOnHome` and
`defaultCurrentState` values at the start of each run. If needed, it sets them to
`true` and `"switch"` in one grouped update and verifies them independently before
testing preference preservation. Cleanup restores the original values, including
false or empty selections, even if setup fails. Already-prepared panes add no
setup calls; the permanent devices and drivers are still installed only once.
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

The native-bypass profile divides its large edit and restoration into two grouped
requests: device metadata, then preferences/pane controls/room. This keeps the
multiple native endpoint calls within the cloud relay budget. It adds two tool
calls per matrix, preserves the complete final readback, and never retries a
write whose response was lost.

Clearing an enum removes the native storage identity and can lose its selection
cardinality. The read tool reports `multiple:null` rather than a false single-
selection declaration. Restore from the captured declaration using an explicit
`multiple:true` hint for the fixture's multi-select value. Native saved readback
must again contain the actual List; no cached metadata is accepted as proof.

Shared discovery, pagination, invalid-preference and explicit-clear checks run
once on the child profile. Each profile still performs its own grouped native
field/preference edit, driver change, disabled-state observation and independent
restoration. Bypass additionally verifies rejection of SDK-only Data writes.

Initialize the persistent legacy dashboard's device selection once: use
`hub_update_dashboard` to add one owned configuration fixture, then clear its
`deviceIds` back to `[]`. A newly created dashboard with an untouched selection
can appear in the native device picker yet ignore device-page assignment saves.
Verify add/remove through `hub_update_device` during provisioning; routine E2E
must not repeat this initialization.

## Native prerequisite decisions

The manifest records the provisioned test hub's native support. Positive rows
must change an owned value and restore it. Unavailable rows must expose a native
restriction and reject a write. A changed applicability flag fails the scenario
so firmware or integration changes cannot silently remove coverage.

The persistent HomeKit Bridge is the representative assistant integration. All
three profiles exercise the shared `/device/updateAssistants` save path through
HomeKit; Alexa and Google Home remain absent and exercise rejection. Account
pairing and external assistant operation are not prerequisites for this test.
Do not install/remove integrations during E2E.

| Field | Standing coverage | Limit |
|---|---|---|
| `zigbeeId` | Selected and bypass standalones have synthetic baseline IDs `0200000000006859` and `0200000000006860`; the child has none and rejects edits. | Native property storage only; no paired identity or radio traffic. The manifest's positive target IDs deliberately differ from the baselines. |
| `dashboardIds` | Persistent legacy dashboard `E2E_PERM_Configuration_Dashboard`, ID `36461`; all fixtures start unassigned. | Assignment persistence, not dashboard rendering. If provisioned on another hub, use that hub's owned dashboard ID. |
| `meshEnabled` | Native per-device selection control is available on the local mocks. | Test the stored selection only. Hub Mesh service remains disabled; propagation is not tested. |
| `meshFullSync` | Rejected on the three local, unlinked devices. | Positive linked-device sync coverage is explicitly excluded; no second hub is required. |
| `homeKitEnabled` | Enabled persistent HomeKit Bridge, ID `36462`; all fixtures start unassigned and are assignable. | Native assignment and preservation of other assistant flags, not Apple pairing or external control. |
| `amazonAlexaEnabled` | Rejected while the native integration is absent. | Positive Alexa-specific behavior is an accepted gap; HomeKit covers the shared save mechanism. |
| `googleHomeEnabled` | Rejected while the native integration is absent. | Positive Google-specific behavior is an accepted gap; HomeKit covers the shared save mechanism. |
| `retryEnabled` | Rejected because the software fixtures do not have native retry support. | Positive retry coverage is an accepted gap; no paired hardware is required. |

Provisioning uses the native user-driver create route. Live native readback on
firmware 2.5.1.174 reports `virtual=false`, non-component devices, a null parent
for both standalones, and the MCP app as the child's parent. Saving the complete
native device form normalizes absent controller/identity strings to empty
strings; baseline snapshots must reflect the saved native form. A synthetic ID
does not change the software fixture into a radio device. These fixtures exercise
real native device storage and the selected/child/bypass dispatch paths without
claiming equivalence to every physical driver or protocol.

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
