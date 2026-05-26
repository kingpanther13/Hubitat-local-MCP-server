# Hubitat hub2 Vue UI source (`vue-hub2.min.js`)

This is the production Vue 3 SPA bundle that powers Hubitat's modern `/ui2/`
admin UI. It is downloaded verbatim from the hub at:

    http://<hub-ip>/ui2/js/vue-hub2.min.js

The copy committed here was captured on **2026-05-26** from a Hubitat C-8 Pro
on firmware current as of that date. The file is **~3.3 MB minified**.
Hubitat ships no source map (`vue-hub2.min.js.map` is 404) and no
unminified sibling, so this is the only form available to consumers.

## Why it lives in this repo

Several open issues require us to wire the MCP server to apps whose admin
UI was rewritten in Vue (Basic Rules, Visual Rules Builder, hub variables,
device swap, etc.). For those apps the legacy `dynamicPage` HTML scrape
strategy does not apply — the contract is the JSON payload the Vue UI
POSTs to the server. The data model and endpoint paths for that contract
are encoded as literal strings in this bundle, and the easiest way to
get a stable reference copy is to vendor the file.

## What's in the bundle

~548 named Vue components. The notable groups:

| Area | Components |
|---|---|
| App / driver / library editor | `AppCodeEditor`, `AppsCodeV2`, `AppDetails`, `AppEvents`, `AppEventSubscriptions`, `AppSettings`, `AppSettingsDrawer`, `AppsCodeTable`, `AppScheduledJobs`, `AppState`, `AppSplitWarningDialog` |
| Devices | `AddDevice*` (8 flows), `Devices`, `DeviceDetails`, `DeviceGridView`, `DevicesTableV2`, `DeviceTile`, `DeviceEvents`, `DeviceLogsTab` |
| Visual Rules Builder | `VisualRuleBuilder`, `VisualRuleBuilder20`, `VRBTriggerDialog`, `VRBConditionDialog`, `VRBActionDialog`, `ConditionsController`, `ConditionRow`, `ConditionsDialog`, `DeviceCondition`, `TimeCondition`, `DeviceDoesThis`, `DeviceIs`, `DeviceSensesThat` |
| Basic Rules | `BasicRulesApp` + `createNewBasicRuleApp` factory |
| Dashboards | `Dashboards`, `DashboardsViewer`, `DashboardGridView`, `DashboardTable`, `DashboardToolbar` |
| Z-Wave / Zigbee admin | `ZigbeeGraph/Info/Logs/Scan`, `ZwaveGraph/Info/Logs/NodeState`, `ZWaveFirmwareFileManager`, `ZigbeeMqttImportPage`, `TasmotaMqttImportPage` |
| Hub / system | Backup/Restore (+ Cloud Backups, scheduled), Hub Mesh, Hub Variables, Hub Modes, HSM, onboarding wizards, ToS |

### Not in this bundle: Rule Machine

The Vue layer treats RM (and every other classic system app — Notifier,
Button Controllers, Mode Manager, etc.) as a black box. When you open an
RM rule from hub2 it renders `<iframe src="/installedapp/configure/<id>?embed">`
and lets the server-side `dynamicPage` HTML do everything. So this bundle
is **not** useful for RM wire format. The classic POST flow is still the
contract.

## Endpoints discovered

| Endpoint | Notes |
|---|---|
| `POST /app/saveOrUpdateJson` | Known. `{id, source, version}` |
| `POST /driver/saveOrUpdateJson` | Same shape, drivers. New for us. |
| `POST /library/saveOrUpdateJson` | Same shape, libraries. New for us. |
| `GET  /installedapp/json/<id>` | Installed-app state as JSON |
| `GET  /installedapp/statusJson/<id>` | App status JSON |
| `GET  /installedapp/eventsJson/<id>` | Events history JSON |
| `GET  /installedapp/sysAppByIdJson/<id>` | System-app metadata |
| `POST /installedapp/forcedelete/<id>/quiet` | Force-delete, no prompts |
| `*    /installedapp/direct/swapDevice` | New direct JSON page for global device swap |
| `*    /installedapp/direct/hubVariables` | New direct JSON page for variable CRUD |
| `/installedapp/sysAppApi/appCloner/app/0` | AppCloner restore-apps API |
| `/installedapp/configure/<id>?embed` | iframe URL for classic apps (RM, BC, Notifier, etc.) |

The `direct/*` family is narrow — only the two endpoints above exist.
Hub2 did **not** build a general "save settings as JSON" API; everything
else still goes through the classic `dynamicPage` flow.

## Working with the file

The file is minified (one ~3.3 MB line) but **string literals survive
minification** — capability names, event labels, field keys, endpoint
paths are all greppable as-is. For that reason, plain `grep` against
this file is the fastest way to find a feature's data shape.

When you need to read control flow, run it through one of:

- `prettier --parser babel vue-hub2.min.js > vue-hub2.pretty.js` —
  restores formatting; variable names stay mangled but the file becomes
  navigable.
- `npx webcrack vue-hub2.min.js -o vue-hub2.webcrack/` — splits the
  webpack/Vite bundle back into per-module files.
- `npx humanify` — LLM-renames mangled identifiers semantically. Slow
  and costs tokens; overkill unless you need to fully understand a
  complex flow.

## Refresh procedure

When Hubitat ships a new hub firmware that updates the UI:

```bash
curl -s "http://<hub-ip>/ui2/js/vue-hub2.min.js" -o vue-hub2.min.js
```

Capture the firmware version and capture date at the top of this README
when you do.

## License note

The bundle is Hubitat's proprietary distributed code. It is included
here under the same terms as anyone else accessing it from a Hubitat hub
they own — as a reference for interoperability work with the published
admin HTTP surface. Do not redistribute it outside this repo or the
contexts that already legitimately serve it.
