# Device editable field inventory

Source: captured Hubitat 2.5.1.181 Vue `DeviceInfoTab`, `DevicePreferencesTab`,
`DeviceAssistantsTab` in the shared device-details chunk. Captures were inspected
locally without requesting hub access. This is the implementation contract, not
a statement that every field has passed live validation. No private device JSON
is included. The closeout plan tracks implementation and remote evidence.

`device.*` and unprefixed root fields below refer to `/device/fullJson/{id}`.
Configuration mode exposes the matching `editableFields` property; preferences
use the separately typed preference entries. All writes retain existing device
scope, Write-master, advanced overrides and BPS enforcement.

| UI field | Write parameter/type | Native read source | Native endpoint and encoding | Applicability/safety | Unit/E2E mapping |
|---|---|---|---|---|---|
| Device name | `name`: string | `device.name` | Listed device setter; bypass merged form | Native linked/component restrictions | Existing edit/bypass specs; disposable name restore |
| Device label | `label`: string | `device.label` | Listed device setter; bypass merged form | Disabled on linked-and-disabled device | Existing edit/bypass specs; disposable label restore |
| Device network ID / linked device target | `deviceNetworkId`: string | `device.deviceNetworkId` | Listed nonlinked device setter; linked/bypass merged form | Identity change: confirm and backup; nonlinked components disabled; linked targets discovered from `/device/accessibleLinkedDevices` | Configuration write confirmation; disposable identity restore and dedicated mesh retarget fixture |
| Zigbee ID | `zigbeeId`: string | `device.zigbeeId` | Merged form | Existing Zigbee ID; not component/linked; confirm and backup | Configuration form/confirmation; suitable dedicated radio fixture required |
| Driver Type | `deviceTypeId`: integer | `device.deviceTypeId` | Merged form | Driver ID discovery below; not component/linked; confirm and backup | Configuration form/invalid ID/confirmation; disposable driver switch and restore |
| Room | `room`: string | `device.roomName`, `device.roomId` | Existing room assignment; bypass `/device/updateRoom` by canonical name or merged `roomId=0` | Existing room; empty clears; no implicit room creation | Existing room/bypass specs; disposable room assignment restore |
| Dashboards | `dashboardIds`: integer array | Root `dashboards` selected IDs | Merged form, comma-separated IDs; empty array becomes empty string | `hasDashboards`; known IDs; confirm and backup | Configuration form/invalid ID/confirmation; run-owned dashboard fixture required |
| Event history size | `maxEvents`: integer 1..2000 | `device.maxEvents` | Merged form | Native numeric bounds | Configuration form/invalid bounds; disposable retention restore |
| State history size | `maxStates`: integer 1..2000 | `device.maxStates` | Merged form | Native numeric bounds | Configuration form/invalid bounds; disposable retention restore |
| Too many events alert | `spammyThreshold`: integer 100..2000 | `device.spammyThreshold` | Merged form | Native numeric bounds | Configuration form/invalid bounds; disposable threshold restore |
| Hub Mesh enabled | `meshEnabled`: boolean | `device.meshEnabled` | Merged form | `device.meshSelectionEnabled`; confirm and backup | Configuration form/confirmation; dedicated mesh fixture required |
| Command retry | `retryEnabled`: boolean | `device.retryEnabled` | Merged form (`retryEnabled`); Preferences pane uses JSON `commandRetry` | `commandRetrySelectionEnabled` in information pane or `device.retryAvailable` in Preferences | Configuration form; dedicated retry-capable fixture required |
| Regularly sync source | `meshFullSync`: boolean | `device.meshFullSync` | Merged form | Linked device and `hubMeshRefreshEnabled`; confirm and backup | Configuration form; dedicated mesh fixture required |
| Tags | `tags`: string array | `device.tags` | Merged form, comma-separated string | Replacement; empty array clears | Existing edit/bypass and configuration specs; disposable tags restore |
| Custom icon | `defaultIcon`: string | `device.defaultIcon` with displayed fallback `device.icon` | Merged form | Empty string explicitly removes override | Configuration form; disposable icon restore |
| Device note | `notes`: string | `device.notes` | Merged form | Empty string clears | Configuration form including Unicode/form-sensitive text; disposable note restore |
| Show on Home | `showOnHome`: boolean | `device.showOnHome` | Existing setter or `/device/preference/save` JSON | Preserve other Preferences-pane fields | Existing edit/bypass specs; disposable homepage restore |
| Default current state | `defaultCurrentState`: string | `device.defaultCurrentState` | Existing setter or `/device/preference/save` JSON | Attribute discovery; empty string selects None | Existing edit/bypass specs; disposable status restore |
| Driver preferences | `preferences`: object keyed by declared name | Root `settings` definitions/saved rows and root `inputValues` | Listed `updateSetting`; bypass `/device/preference/save` JSON `{deviceId,preferences:[{name,type,value}]}` | Complete prevalidation; type/options/range; no invented names; distinguish unknown/unset/storage failure | Configuration root readback/clear/invalid preference plus preference specs; independent saved-value restore |
| Apple HomeKit | `homeKitEnabled`: boolean | Root `homeKitEnabled` | `/device/updateAssistants` merged JSON | `homeKitSelectionEnabled`; confirm and backup | Configuration assistants/partial failure/confirmation; dedicated integration fixture required |
| Amazon Alexa | `amazonAlexaEnabled`: boolean | Root `amazonAlexaEnabled` | `/device/updateAssistants` merged JSON | `amazonAlexaInstalled && amazonAlexaSupported`; confirm and backup | Configuration assistants/partial failure/confirmation; dedicated integration fixture required |
| Google Home | `googleHomeEnabled`: boolean | Root `googleHomeEnabled` | `/device/updateAssistants` merged JSON | `googleHomeInstalled && googleHomeSupported`; confirm and backup | Configuration assistants/partial failure/confirmation; dedicated integration fixture required |

Existing MCP `enabled` and `dataValues` remain supported with discoverable native
readback, although Enable/Disable is a device action and Device Data is displayed
read-only in this Vue tab. Data editing must not invent a wholesale form field;
the existing listed-device API is separately evidenced.

The Vue driver selector reads `/device/drivers`, takes `.drivers`, and excludes
`type='dep'` or `category='Hidden'` unless the row is the current driver. Its value
is the numeric `id`; display labels and `deviceTypeReadableType` are not IDs.

The linked-device DNI selector reads `/device/accessibleLinkedDevices`, takes
`.devices`, and offers `${hubId}-${deviceId}` with `linkedLocally` entries disabled.
The special value `0` keeps the current device link; it must not be mistaken for
an actual new identity. Preferences use native booleans/numbers/arrays in their
JSON values. Multiple-enum storage can use JSON-array strings or comma-separated
strings; the first live configuration E2E observed JSON-array strings. All
three captured device models put settings at the root; nested `device.settings`
is not an evidenced compatibility source.

The wholesale endpoint is `POST /device/update` with URL-encoded form fields.
Vue maps true to `on`, false to `false`, null/undefined to empty string, and arrays
to comma-separated strings through `URLSearchParams`. The merged form preserves
`name,label,zigbeeId,maxEvents,maxStates,spammyThreshold,deviceNetworkId,
deviceTypeId,deviceTypeReadableType,roomId,meshEnabled,retryEnabled,meshFullSync,
homeKitEnabled,locationId,hubId,groupId,dashboardIds,tags,defaultIcon,notes` plus
existing `id,version,controllerType`. `homeKitEnabled` comes from the root;
`dashboardIds` comes from selected dashboard rows at the root. False, zero, empty string
and version zero must survive merge and encoding. Other native device data stays
outside the form and must survive independent readback.

Assistant saves POST JSON containing `deviceId` plus all three enabled flags.
Omitted flags must preserve their native state. The response includes aggregate
`success` and `assistants.{homeKit,amazonAlexa,googleHome}` entries with
`success`, `enabled`, and optional `reason`. A native partial failure must retain
confirmed per-property successes and report each failed assignment. Neither HTTP
success nor the aggregate flag replaces fresh independent readback.

Computed status, IDs/versions, controller type, driver namespace/source, pairing,
command buttons, deletion, reset and state-clearing controls are not additional
editable configuration rows. Actual pairing/command/lifecycle operations retain
their separate tool contracts.

Each E2E description above is a required scenario, not recorded execution.
Unavailable radio/mesh/assistant infrastructure remains an explicit coverage gap;
no existing personal-hub device may substitute for a disposable fixture.
