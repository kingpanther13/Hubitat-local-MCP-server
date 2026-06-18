# Hubitat admin-UI source bundles (`/ui2/js/`)

This folder vendors Hubitat's first-party browser JavaScript — the code that
powers the hub's `/ui2/` admin UI — as a reference for interoperability work
with the hub's HTTP surface. Every file is downloaded verbatim from a hub at
`http://<hub-ip>/ui2/js/<file>`.

**Two distinct UIs ship in `/ui2/`, and both matter here:**

| File(s) | What it is |
|---|---|
| `vue-hub2.min.js` (~3.3 MB) | The modern **Vue 3 SPA** — Basic Rules, Visual Rules Builder, devices, dashboards, hub admin |
| `appUI.js`, `main.js`, `helpers.js`, `hub2utils.js`, `hubitat.min.js`, `success-compiled.js` | The **classic server-rendered `dynamicPage` engine** — the client side of the legacy app-config flow that Rule Machine and every other classic app still use |

Classic-engine files + `success-compiled.js` captured **2026-06-08** from a
Hubitat **C-8** on firmware **2.5.0.143**; `vue-hub2.min.js` was first captured
2026-05-26. Hubitat ships no source maps and no unminified siblings, so these
minified bundles are the only form available — but **string literals survive
minification** (capability names, field keys, endpoint paths), so plain `grep`
is the fastest way to find a feature's data shape.

## Why these live in the repo

Several issues need the MCP server wired to hub features whose contract is the
JSON/form payload the browser sends, not something the server documents. The
two UIs split the work:

- **Vue SPA (`vue-hub2.min.js`)** — the contract for apps rewritten in Vue
  (Basic Rules, Visual Rules Builder, …) is the JSON the Vue components POST.
  (Hub variables and device swap are NOT on this list: their Vue components
  are stubs/iframes and the classic wizard is the real contract — see the
  `direct/*` endpoint rows below.)
- **Classic engine (`appUI.js` + `main.js`)** — the contract for Rule Machine
  and the other classic `dynamicPage` apps is the form/settings POST this
  jQuery engine performs on `submitOnChange`, button clicks, and page
  transitions. **This is the genuine wire-format reference for the MCP
  server's native-RM tools.**

## Classic-page engine — the RM wizard contract

The classic config page (`/installedapp/configure/<id>`) loads this set. The
two load-bearing files:

- **`appUI.js`** (48 KB) — the `dynamicPage` driver. Implements `submitOnChange`
  (the per-field re-POST that re-renders the wizard) and `stateAttribute`
  button encoding. Endpoints it calls: `/installedapp/update/json` (settings
  POST), `/installedapp/btn` (button click), `/installedapp/ssr/` (server-side
  page render), `/installedapp/collapseCallback/`, `/installedapp/configure/`.
- **`main.js`** (145 KB) — the broader classic app-list / app-config UI logic:
  `submitOnChange`, `formAction`, `nextPage` / `btnNext` page navigation,
  `AppButtons`, plus `/installedapp/status/`, `/installedapp/createchild/`,
  `/installedapp/disable`, `/installedapp/configure/`.

Supporting files:

- **`helpers.js`** (16 KB) — shared UI helpers (`/installedapp/list`).
- **`hub2utils.js`** (3.7 KB) — small shared utility shims.
- **`hubitat.min.js`** (33 KB) — **not** the dynamicPage engine: the Handlebars
  template runtime (`registerPartial`/`registerHelper`/`unregisterDecorator`),
  modal / z-index helpers (`showModal`, `updateZIndex`), and the hub-control
  toolbar (`reboot`/`shutdown`/`zwaveRepair` via jQuery `.ajax`). Vendored for
  completeness of the classic `/ui2/js` set.
- **`success-compiled.js`** (842 B) — tiny precompiled Handlebars template bundle.

### On the "Rule Machine is a black box" note

The Vue bundle treats RM (and every classic app) as a black box — it iframes
`/installedapp/configure/<id>?embed` and lets the server-side `dynamicPage`
HTML do the work. That is true **of the Vue layer only**. The classic engine
above (`appUI.js` / `main.js`) is the client that drives that `dynamicPage`
flow, so the RM wizard's submit / button / page-transition protocol **is**
documented here — on the classic side, not the Vue side.

## Vue SPA (`vue-hub2.min.js`)

The production Vue 3 SPA — ~548 named components. Notable groups: app/driver/
library editor, devices, **Visual Rules Builder** (`VisualRuleBuilder`,
`VisualRuleBuilder20`, `VRB*Dialog`, `ConditionsController`, …), Basic Rules
(`BasicRulesApp`), dashboards, Z-Wave/Zigbee admin, hub/system (backup, hub
mesh, variables, modes, HSM, onboarding).

**There are TWO Vue builder components with different wire formats** behind one
user-facing app type ("Visual Rules Builder" parent; children are hidden type
109, type string "Visual Rule Builder"):

- **`VisualRuleBuilder`** (v1.1) — the when/then/else node-list editor that
  current firmware ships. Wire format: `{whenNodes, thenNodes, elseNodes}` via
  `GET/POST /app/ruleBuilderJson/<id>`, including the AI-generate flow
  (`/app/ruleBuilderGenerateRule`, Gemini cloud).
- **`VisualRuleBuilder20`** — a graph editor (`nodes`/`edges`) backed by
  `/app/ruleBuilder20Json/<id>`. **Dormant in fw 2.5.0.143**: the component is
  registered in the bundle but has no PageSwitcher route, so the UI never
  reaches it. Its endpoint is live, though — a graph-format rule answers on
  `ruleBuilder20Json` and a classic rule does not, which is how the MCP VRB
  tools detect a rule's serialization.

## Endpoints discovered

| Endpoint | Notes |
|---|---|
| `POST /app/saveOrUpdateJson` | `{id, source, version}` (app); same shape for `/driver/` and `/library/` |
| `GET/POST /app/ruleBuilderJson/<id>` | Serializes the raw state of ANY installed app (classic RM rules return their compiled state: `broken` flag, `eval`/`parens`/`predCapabs`, rendered condition text) and returns `{}` for nonexistent ids. ALSO the **classic Visual Rule read+WRITE endpoint**: for a classic-format VRB rule, `GET` returns `{name, rulePaused, whenNodes, thenNodes, elseNodes, promptHistory}`; the builder's `POST` body is `{name, rulePaused, whenNodes, thenNodes, elseNodes}` (the UI never posts `promptHistory` back — the hub retains it). Only the `whenNodes`+`thenNodes` shape identifies a classic VRB rule. Used by `hub_get_visual_rule`/`hub_set_visual_rule`, and the classic-RM `broken` boolean is the preferred source for `hub_get_rule_health` (shape-check: a non-empty map that is not a VRB node shape and carries `broken`). **`broken` lag (verified fw 2.5.0.143):** deleting a rule's trigger device sets the rendered `*BROKEN*` label immediately, but the compiled `broken` boolean stays `false` until the rule re-validates (e.g. its config page is rendered), then flips `true` — so a consumer should cross-check `broken` against the HTML `*BROKEN*` markers rather than trust either alone. |
| `GET/POST /app/ruleBuilder20Json/<id>` | Visual Rule Builder 2.0 (graph) rule — read+write. `ruleJson` is a **JSON STRING** (double-encoded graph). `GET` → `{name, rulePaused, ruleJson, validationErrors}`; `POST {name, ruleJson}` responds `{success?, name, ruleJson, validationErrors, errorMessage}` — treat the save as accepted unless `success === false`. Answers `{success:false, message:"Rule builder instance not found"}` (HTTP 200) for ANY non-graph id (nonexistent, RM, classic VRB alike). `validationErrors` is also the graph Visual Rule's health signal — `hub_get_rule_health` reports `broken=true` when it is non-empty (the VRB equivalent of RM's `broken` boolean). |
| `GET  /app/createVisualRuleBuilderRule` | Navigation create: server-creates a new VRB child and returns (or redirects to) the builder page. The new appId travels ONLY as an injected window global in the HTML — `HubitatRuleBuilder20AppId` (graph editor) or `HubitatRuleBuilderAppId` (classic editor); which global is injected reveals the firmware's native format for new rules. |
| `GET  /app/ruleBuilderPause/<id>/<true\|false>` | Pause/resume a Visual Rule — boolean rides in the path → `{success}` |
| `GET  /app/ruleBuilderGenerateRule?appId=&prompt=` | VRB AI generate (Gemini cloud) → `{success, whenNodes, thenNodes, elseNodes}`. Query params `appId` + `prompt` (Vue `handleGeminiRule`: `URLSearchParams({appId, prompt})`). Returns `{success:false, message:null}` (HTTP 200) when cloud Gemini is unconfigured. NOT folded into an MCP tool (issue #257): the success body is the SAME classic node structure `hub_set_visual_rule` already accepts, so an MCP LLM authors those nodes directly — folding it would only add a fragile cloud-Gemini dependency for no new capability |
| `GET  /app/ruleBuilderSuggestions` | Prompt suggestions for the VRB AI-generate dialog |
| `GET  /device/listWithCapabilities/json` | All-hub device list with capabilities (`id`, `label`, `capabilities`) — feeds the VRB device pickers AND `hub_list_devices` `scope='all'`, which tags each device `mcpAuthorized` true/false. This is the only way the app sees devices it isn't granted (the Groovy device model is authorization-scoped) |
| `GET  /device/listJson?capability=<cap>` | Classic `dynamicPage` device-input picker feed (`appUI.js` line 209 `$.getJSON('/device/listJson?capability='…)`, `main.js`) — a capability-filtered device list. The MCP server reaches the same data via `/device/fullJson` + `hub_list_devices`; this is the older classic-engine path, distinct from the Vue `listWithCapabilities/json` above |
| `GET  /hub/zwave/getChildAndRouteInfoJson` | Z-Wave mesh route map — `{nodes, connectors}` (per-node route/neighbor graph). Read-only. Feeds `hub_get_radio_details(include_topology=true)` |
| `GET  /hub/zigbee/getChildAndRouteInfoJson` | Zigbee mesh route map — `{children, neighbors, routes}` (routes carry `status`/`age`/`nextHopId`). Read-only. Feeds `hub_get_radio_details(include_topology=true)` |
| `GET  /hub/zwaveTopology` | Z-Wave raw route table (plain text). Read-only companion to the JSON route map above |
| `GET  /hub/matterDetails/json` | Matter fabric + commissioned-device details — `{enabled, installed, networkState, ipAddresses, fabricId, devices[]}` (each device: `nodeId`, `online`, `dni`, `id`, `uniqueId`, `manufacturer`, `model`, `name`, `ipAddress`). Read-only. Feeds `hub_get_radio_details(radio='matter')`. Only present on Matter-capable hubs (C-8 / C-8 Pro on supported firmware); absent → `hub_get_radio_details` reports `source='sdk_only'` |
| `GET  /hub/networkTest/traceroute/<ipv4>` | Hub-side traceroute — returns the **plain-text** route table (synchronous; sub-second to a reachable host, up to ~30s when intermediate hops are unreachable). Host rides in the path; the endpoint itself accepts free-text, so `hub_get_device_health` validates the arg as a dotted-quad IPv4 literal at the tool layer (hostnames rejected there). Read-only. Feeds `hub_get_device_health(traceroute=<ipv4>)` |
| `GET  /hub/networkTest/speedtest` | Hub-side WAN download speed test — returns the **plain-text** `wget` log incl. the measured download speed (synchronous; a few seconds on a fast link, longer on slow ones — the tool allows up to 90s). The log itself reveals the source: a fixed 10 MB blob (`Length: 10485760`) from `hubitat-public-files.s3.us-east-2.amazonaws.com/speedtest.bin`, no caller input. Read-only. Feeds `hub_get_device_health(speedtest=true)` |
| `GET  /hub/zwaveDetails/json` · `/hub/zigbeeDetails/json` | Z-Wave / Zigbee radio details (`enabled`, region/`longRangeChannel`, `panId`, device map, `healthy`). Read-only. Feed `hub_get_radio_details(radio='zwave'\|'zigbee')` |
| `GET  /hub/zwave2/getNodeState?node=<id>` · `/hub/matterPairDeviceStatus?nodeId=<id>` | Per-node status — Z-Wave node state (plain text; `"Done"` idle) or, with `radio='matter'`, Matter commissioning status (`{initMap,deviceMap}`). Read-only. Feed `hub_get_radio_details(node_id=<id>)` (radio-aware: matter→pair-status, else→nodeState) |
| `GET  /hub/zwaveRepair2Status` · `/hub/checkZwaveRepairRunning` · `/hub/zwaveExclude/status` · `/hub/searchZwaveDevices` · `/hub/zwave2/antennaTestProgress` · `/hub/zwave/nodeReplace/{status,info}` · `/hub/zigbeeInfo/status` | Lifecycle status pollers — repair stage (`{stage,html}`), heal-running flag, exclusion status, join discovery, antenna-test results (RSSI), node-replace progress + replacement info, Zigbee network status (`{panId,extendedPanId,networkState}`). Read-only. Feed `hub_get_radio_details(include_status=true)` |
| `GET  /hub/matterLogs/json` | Matter chip-tool logs (`{text}`, ANSI). Read-only. Feeds `hub_get_radio_details(radio='matter', include_logs=true)` |
| `GET  /hub/zigbeeChannelScanJson` | Zigbee channel energy-scan results. Read-only. Feeds `hub_get_radio_details(include_channel_scan=true)` |
| `GET  /mobileapi/zwave/smartstart/list` · `POST /mobileapi/zwave/smartstart/delete` (`{nodeDSK}`) | SmartStart provisioning entries — list (read → `hub_get_radio_details(include_smartstart=true)`) + delete (write → `hub_call_zwave`) |
| `GET  /hub/zwave/deviceFirmware/{devices,files}` | Firmware-eligible Z-Wave devices + available files (`{success, devices:[{nodeId,label}], available}`). Read-only. Feed `hub_get_radio_details(include_firmware=true)` |
| `GET  /hub/zwave/enable/<bool>` · `/hub/zwave2/{enable,disable}` · `/hub/zwaveDetails/update?region=&longRangeChannel=` | Z-Wave radio enable/disable + region/long-range-channel config (idempotent). Feed `hub_set_zwave` |
| `GET  /hub/zigbee/enable/<bool>` · `/hub/zigbee/updateChannelAndPower?channel=&powerLevel=` · `/hub/zigbee/updateSettings?rebuildNetworkOnReboot=&inactiveDevicePingEnabled=` · `/hub/zigbee/updatePingDevice/<id>/<bool>` | Zigbee enable/disable, channel/power, radio settings (rebuild-on-reboot / inactive-device ping; merged over current), and per-device keep-alive ping (all idempotent). Feed `hub_set_zigbee` |
| Z-Wave node/mesh lifecycle (non-idempotent) → `hub_call_zwave`: `GET /hub/zwaveRepair2?resetStats=false` · `/hub/zwaveCancelRepair` · `/hub/zwaveNodeRepair2?` (repair); `GET /hub/startZwaveJoin` · `/hub/stopJoin` + `POST /hub/zwave/securityKeys` · `/hub/zwave/securityCode` (inclusion + S2 grant/DSK); `GET /hub/zwaveExclude` · `/hub/stopZWaveExclude` (exclusion ⚠️); `POST /hub/zwave/{refreshNodeStatus,discoverDevice,nodeReinitialize}` (`zwaveNodeId`) · `GET /hub/zwaveNodeDetailGet` (maintenance); `POST /hub2/zwave/nodeReplace` (`{zwaveNodeId}`) + `GET /hub/zwave/nodeReplace/{info,status}` + `POST /hub/zwave/nodeReplace/stop`; `POST /hub/zwave/nodeRemove` (`zwaveNodeId`) ⚠️; `GET /hub/zwave2/{startAntennaTest?node=,antennaTestContinue}` | The full Z-Wave lifecycle surface. Modern `zwaveRepair2` supersedes the legacy `zwaveRepair` that the old `hub_call_zwave_repair` used (now absorbed into `hub_call_zwave`) |
| `GET  /hub/rebootZigbeeRadio` · `/hub/rebuildZigbeeNetwork` · `/hub/zigbeeChannelScan` | Zigbee radio reboot, network rebuild, channel-scan trigger (non-idempotent). Feed `hub_call_zigbee` |
| `GET  /hub/matter/enable/<bool>` · `/hub/matter/pair?setupCode=` · `/hub/matter/openPairingWindow?node=` | Matter enable/disable (needs a hub reboot to take effect), commission a device by setup code, open a share/pairing window (→ `{success,setupCode}`). Feed `hub_call_matter` |
| **DESTRUCTIVE** → `hub_call_destructive_radio` (`hub_manage_destructive_ops`, `confirm`-gated): `GET /hub/zwave/resetJson` · `/hub/zigbee/reset` · `/hub/matter/reset` (network/fabric wipe — unpairs everything); `POST /hub/zwave/deviceFirmware/{start,abort}` (JSON `{nodeId,target,fileName}` / `{nodeId}`) · `GET /hub/zwave/startUpdateHubFirmware` · `/hub/zigbee/updateFirmware/latest` (firmware flash — device OTA, Z-Wave chip, Zigbee) | All radio operations that wipe state or can brick hardware, isolated in one confirm-gated destructive tool |
| `GET  /hub/cloud/{checkForUpdate,updatePlatform,checkUpdateStatus}` | Hub **platform/firmware** update — token-free cloud path. `checkForUpdate` → `{version, upgrade, status:"UPDATE_AVAILABLE"\|..., releaseNotesUrl, beta, accountEmails[...]}` (live-verified more current than `/hub2/hubData.alerts.platformUpdateAvailable`; `accountEmails` leaks the owner email — drop it); `updatePlatform` applies (downloads, installs, self-reboots); `checkUpdateStatus` → `{status:"IDLE"\|...}`. Feeds `hub_update_firmware` (confirm-gated; `statusOnly=true` polls). The `/management/firmwareUpdate?token=` family also exists (503 without `/hub/advanced/getManagementToken`) but is not used. |
| `GET  /modes/list/json` | Location modes list — feeds the VRB mode trigger/condition/action dialogs |
| `GET  /appui/createBasicRulesChild` | Server-creates a new Basic Rules child → `{success, appId}` |
| `GET  /appui/clearEmptyBasicRules` | Sweeps empty (never-saved) Basic Rules children |
| `GET  /installedapp/configure/json/<id>` | Full live config page (sections, inputs, settings) — the RM **read** path the MCP server uses |
| `POST /installedapp/update/json` | Classic settings POST (`dynamicPage` submit) — the RM **write** path |
| `*    /installedapp/btn` | Classic page-button click |
| `*    /installedapp/ssr/<…>` | Classic server-side page render |
| `*    /installedapp/collapseCallback/` | Section collapse state |
| `GET  /installedapp/json/<id>` | Thin app summary (id/name/type/disabled/user) |
| `GET  /installedapp/statusJson/<id>` | App status JSON |
| `GET  /installedapp/eventsJson/<id>` | Events history JSON |
| `POST /installedapp/forcedelete/<id>/quiet` | Force-delete, no prompts |
| `GET  /installedapp/createchild/<namespace>/<appName>/parent/<parentId>` | Server-creates a child app instance under a parent — a raw GET that 302-redirects to the new child's `configure/<id>` page. Used by the MCP server (`_rmCreateChildApp`) to instantiate classic child apps (Basic Rule, RM child, etc.) |
| `POST /installedapp/disable` | Enable/disable an installed app — body `{id, disable:<bool>}` (`true` disables, `false` enables). Posted by `main.js` `enableApp()`/`disableApp()`. Used by `hub_set_app_disabled` (read-back verified via `/installedapp/json/<id>`) |
| `GET  /installedapp/direct/<alias>` | NOT a Vue CRUD endpoint — a name-addressed 302 redirect chain: `direct/<alias>` → `create/<typeId>` → `configure/<instanceId>` (type ids vary per hub; the alias is the stable key). Get-or-create, so it doubles as a stable name→id resolver (fw 2.5.0.143) |
| `GET  /installedapp/direct/hubVariables` | Singleton: the chain lands on the SAME instance every visit. The Vue `HubVariables` component is a non-functional stub — the classic `hubVar` wizard is the real variable-CRUD contract |
| `GET  /installedapp/direct/swapDevice` | Transient: every visit CREATES a fresh instance (1802, then 1803 observed) — callers own cleanup of instances they don't drive to completion. The swap flow itself is the classic `mainPage` wizard; its pickers offer only free-standing devices (app-owned child/component devices are excluded from both `oldDev` and `newDev`); `oldDev` additionally lists only devices referenced by at least one app, while `newDev` offers any compatible free-standing device (fw 2.5.0.143) |

## Working with the files

String literals survive minification, so `grep` is the fastest way to find a
data shape. To read control flow:

- `prettier --parser babel <file> > <file>.pretty.js`
- `npx webcrack <file> -o <out>/` — splits a webpack/Vite bundle into modules
- `npx humanify` — LLM-renames mangled identifiers (slow; only for deep dives)

## Refresh procedure

When Hubitat ships new firmware that updates the UI:

```bash
for f in vue-hub2.min.js appUI.js main.js helpers.js hub2utils.js hubitat.min.js success-compiled.js; do
  curl -s "http://<hub-ip>/ui2/js/$f" -o "$f"
done
```

Record the firmware version + capture date at the top of this README.

## License note

These bundles are Hubitat's proprietary distributed code, included here under
the same terms as anyone accessing them from a Hubitat hub they own — as a
reference for interoperability with the published admin HTTP surface. Do not
redistribute outside this repo or the contexts that already legitimately serve
them.
