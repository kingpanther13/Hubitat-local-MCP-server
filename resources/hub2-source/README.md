# Hubitat admin-UI source bundles (`/ui2/js/`)

This folder vendors Hubitat's first-party browser JavaScript — the code that
powers the hub's `/ui2/` admin UI — as a reference for interoperability work
with the hub's HTTP surface. Every file is downloaded verbatim from a hub at
`http://<hub-ip>/ui2/js/<file>`.

**Two distinct UIs ship in `/ui2/`, and both matter here:**

| File(s) | What it is |
|---|---|
| `vue-hub2.min.js` (~3.3 MB, platform 2.5.0.143) | The modern **Vue 3 SPA** as one MONOLITH — every component body inline, so this is the file whose string literals carry the **whole endpoint corpus** (`/hub2/appsList`, `/device/runmethod`, `/app/saveOrUpdateJson`, …). Kept deliberately: the 2.5.1 build is code-split and its shell no longer contains those literals |
| `vue-hub2-shell-2.5.1.min.js` (~573 KB, platform 2.5.1.181) | The 2.5.1 **shell** — routes, the chunk-filename map (`.u=function(e)`) for every lazily-loaded chunk, and the components that were not split out. Grep it for routing; grep the monolith above for endpoints |
| `vue-hub2-visual-rule-builder-20.min.js` (~583 KB, platform 2.5.1.181) | The **Visual Rule Builder 2.0** code-split chunk — the entire 2.0 editor: graph compose/decompose, dialogs, and its own endpoints |
| `appUI.js`, `main.js`, `helpers.js`, `hub2utils.js`, `hubitat.min.js`, `success-compiled.js` | The **classic server-rendered `dynamicPage` engine** — the client side of the legacy app-config flow that Rule Machine and every other classic app still use |

## Capture state

`vue-hub2-shell-2.5.1.min.js`, `main.js` and `vue-hub2-visual-rule-builder-20.min.js`
were captured **2026-09-03** from a Hubitat **C-8** on platform **2.5.1.181**.
`vue-hub2.min.js` is still the **2.5.0.143 monolith** (captured 2026-05-26 / 2026-06-08),
on purpose: the 2.5.1 SPA is code-split, so the shell that replaced it holds only a
fraction of the endpoint literals; an earlier revision of this PR swapped it out and
five of five randomly checked endpoints disappeared from the folder. The monolith
stays as the greppable corpus until every chunk that carries an endpoint is vendored.
`hubitat.min.js` was re-fetched the same day and is byte-identical to the earlier
capture, so it carries both dates.

`appUI.js`, `helpers.js`, `hub2utils.js` and `success-compiled.js` are still the
**2026-06-08 / firmware 2.5.0.143** captures, and that is deliberate: **2.5.1.181
serves those four MINIFIED**, so re-capturing would replace the only readable
copy of the classic engine that exists anywhere with a mangled one. Their
contract has barely moved in the interim — the *only* endpoint-literal deltas
between the two captures are `/installedapp/grouping/` (new in the 2.5.1.181
`appUI.js`) and `/installedapp/configure/` + `/hub/edit` (no longer present as
whole literals). If you do re-capture them, keep an unminified copy alongside.

Hubitat ships no source maps, so for everything else these minified bundles are
the only form available — but **string literals survive minification**
(capability names, field keys, endpoint paths), so plain `grep` is the fastest
way to find a feature's data shape.

## Why these live in the repo

Several issues need the MCP server wired to hub features whose contract is the
JSON/form payload the browser sends, not something the server documents. The
two UIs split the work:

- **Vue SPA (`vue-hub2.min.js` + its chunks)** — the contract for apps rewritten
  in Vue (Basic Rules, Visual Rules Builder, …) is the JSON the Vue components
  POST. The bundle is code-split, so a feature's body usually lives in a chunk,
  not the shell — Visual Rule Builder 2.0 is in
  `vue-hub2-visual-rule-builder-20.min.js`.
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

### RM 5.1 action / condition field wire formats (live-probed)

Field names live-discovered by driving the `doActPage` wizard on a test hub
(the picker option values survive minification but the field-reveal ORDER does
not — each is `submitOnChange`-gated on the field before it). All indices are
per-action; `<N>` is the action index unless noted.

- **HSM as an action** — actType `lockActs` ("Control HSM, Garage Doors,
  Locks, Valves or Thermostats"), actSubType `getSetHSM` ("Control Hubitat
  Safety Monitor"). Command field `alarm.<N>` (enum, dotted index). Option
  values are BARE tokens (not the Vue `hsmArm*` tokens): `armAway`, `armHome`,
  `armNight`, `disarm`, `rearm`, `disarmAll`, `armRules`, `cancelAlerts`. There
  is no `armAll` value. `getSetHSM` appears in the subtype list only when HSM is
  installed on the hub. The only other revealed field is the generic
  `delayAct.<N>` ("Delay?").
- **Switch "only switches that are on/off"** — the on/off subtype
  (`switchActs`/`getOnOffSwitch`) reveals `optSwitch.<N>` (bool, "Command only
  switches that are on?") ONLY AFTER a device is selected in `onOffSwitch.<N>`
  — so it must be written after the device picker. Sibling bool that also
  reveals: `trackSwitch.<N>` ("Track event switch?").
- **Wait for Events per-event "and stays for" duration** — the per-event
  `stays-<N>` toggle (bool) reveals THREE DASH-indexed duration fields
  `SHours-<N>`, `SMins-<N>`, `SSecs-<N>` (number). Note the DASH index differs
  from the trigger's no-dash `SHours<N>`. `<N>` is the 1-based per-event index
  (same dash-index as `tCapab-<N>`/`tDev-<N>`/`tstate-<N>`). waitEvents does not
  NPE on a partial duration the way the trigger does, but all three are written
  (default 0) for a clean total-wait computation.
- **String `*contains*` comparator** — for a STRING-typed variable or a
  free-valued Custom Attribute, the comparator field (`RelrDev_<N>` on the
  condition wizard, `ReltDev<N>` on the trigger row) offers `=`, `≠` (the
  U+2260 glyph, NOT ASCII `!=`), `*contains*`, `*changed*`. The
  contains value is the literal asterisk-wrapped `*contains*` (written verbatim,
  not bare `contains`). There is NO "does not contain"/"starts with"/"ends
  with" — negation is the `not<N>` toggle + `*contains*`. A numeric variable
  gets numeric comparators instead.

## Vue SPA (`vue-hub2.min.js` monolith, plus the 2.5.1 shell and chunks)

The production Vue 3 SPA. Notable groups: app/driver/library editor, devices,
**Visual Rules Builder** (`VisualRuleBuilder`, `VisualRuleBuilder20`,
`VRB*Dialog`, `ConditionsController`, …), Basic Rules (`BasicRulesApp`),
dashboards, Z-Wave/Zigbee admin, hub/system (backup, hub mesh, variables, modes,
HSM, onboarding).

**On 2.5.1 the SPA is code-split.** The file the hub serves as `vue-hub2.min.js`
shrank from ~3.3 MB (2.5.0.143) to ~573 KB (2.5.1.181) because component bodies
moved into lazily-loaded chunks; that shell keeps only the routes and the
chunk-id → filename map (the `.u=function(e)` map, `"js/vue-hub2-" + {…}[e] +
".min.js"`) and is vendored here as `vue-hub2-shell-2.5.1.min.js`. The
`vue-hub2.min.js` in this folder is deliberately the 2.5.0.143 MONOLITH, because
it still carries every endpoint literal inline. So: **grep the monolith for
endpoints, the VRB 2.0 chunk for anything 2.0, the shell for routing** — none of
the three `ruleBuilder20*` endpoints appear in the shell at all. Only the VRB 2.0
chunk is vendored; fetch another from `/ui2/js/<name>` when you need it.

**There are TWO Vue builder components with different wire formats** behind one
user-facing app type ("Visual Rules Builder" parent; children are hidden type
109, type string "Visual Rule Builder"):

- **`VisualRuleBuilder`** (v1.1) — the when/then/else node-list editor that
  current firmware ships. Wire format: `{whenNodes, thenNodes, elseNodes}` via
  `GET/POST /app/ruleBuilderJson/<id>`, including the AI-generate flow
  (`/app/ruleBuilderGenerateRule`, Gemini cloud).
- **`VisualRuleBuilder20`** — a graph editor (`nodes`/`edges`) backed by
  `/app/ruleBuilder20Json/<id>`. **Live and OUT OF BETA on the 2.5.1 stable
  line** — it shipped 2.5.1.138 as "Visual Rule Builder 2.0 (beta)" and is now
  the format newly-created VRB rules speak on such a hub. Its component body is
  **not in the shell**: `vue-hub2-shell-2.5.1.min.js` registers only the route, so grep the
  vendored chunk `vue-hub2-visual-rule-builder-20.min.js` for anything 2.0.
  A graph-format rule answers on `ruleBuilder20Json` and a classic rule does
  not, which is how the MCP VRB tools detect a rule's serialization; on 2.5.1
  the hub additionally labels each child by version in `/hub2/appsList`
  `data.type` — `"Visual Rule Builder 1.0"` / `"Visual Rule Builder 2.0"`.

### VRB 2.0 graph schema (platform 2.5.1.181, read from the chunk + validator-confirmed)

**Hubitat publishes its own authoring guide for this format, and it is vendored
here as [`vrb2-authoring-guide.md`](vrb2-authoring-guide.md).** That file is the
authoritative catalog — every trigger / condition / action type with its exact
enum strings, required `config` keys, value formats, and the storage/activation
lifecycle. Read it before deriving any of that by hand. The hub serves the same
text over its built-in MCP server as the resource `hubitat://vrb2/schema`. What
follows is the shape of the document plus what the *builder* and the *validator*
do with it — the parts the guide does not cover.

#### The graph document

Every node is `{id, kind, type, config}` — `kind` is the CATEGORY, `type` the
variety within it, and all per-node fields live inside `config`. Config keys are
per-type and plural for device lists (`config.motionSensors`, `config.switches`),
never a generic `deviceIds`. Store device and mode **ids**, not names. The
document also carries a top-level `version`, which must be `1`.

- `kind: "trigger"` — `type` is the trigger variety (e.g. `"switch"`).
- `kind: "merge"`, `type: "triggerMerge"` — REQUIRED, exactly one. All triggers
  edge into it on port `next`.
- `kind: "decision"`, `type: "all"|"any"` (null defaults to `all`; `any` is OR and
  must carry at least one condition) — REQUIRED, exactly one.
- `kind: "merge"`, `type: "branchMerge"` — present only when actions rejoin after
  the if/else (the builder's `commonActions`).
- `kind: "action"` — `type` is the action variety (e.g. `"turnOff"`).

**Conditions are not nodes.** They nest inside the decision as
`decision.config.conditions`, an array of `{id, type, config}` — note there is
**no `kind`** on a condition. An empty array means unconditional.

Edges: `triggers -> triggerMerge (next)`, `triggerMerge -> decision (next)`,
`decision -> then-chain (true)`, `decision -> else-chain (false)`, and each chain
links onward on `next`, optionally terminating at the `branchMerge`. A
`branchMerge`, when present, chains onward on `next` through the common actions.

#### The builder's editor model + compose/decompose

The Vue builder does not edit the graph directly. It holds a flat **editor
state** and converts in both directions with two pure functions in the chunk.
Anchor them by content — the file is one minified line, so line numbers are
meaningless; `grep -o` for the strings below:

| Function | Direction | Grep anchor |
|---|---|---|
| `W(graph)` | decompose → editor | `The rule must contain a trigger merge and an AND/OR decision.` |
| `U(editor)` | compose → graph | `function U(e){const t=H(e.triggers` |

The editor state is
`{triggers, conditions, decisionType, thenActions, elseActions, commonActions, structureIds:{triggerMerge, decision, branchMerge}}`.
`U` emits nodes in the order `triggers`, `triggerMerge`, `decision` (carrying
`config.conditions`), `thenActions`, `elseActions`, then — only when
`commonActions` is non-empty — `branchMerge` and the common actions. Default
structure ids are `trigger-merge`, `decision`, `branch-merge`, suffixed `-1`,
`-2` on collision. A chain with an EMPTY list still emits its edge when there is
a terminal, so an empty else-branch yields `decision -> branchMerge` on port
`false`. `U` throws on an unsupported `decisionType`, and on `any` with zero
conditions; `W` additionally throws on a missing merge/decision, a non-action
node in an action chain, and an action cycle.

**Dialog → config mapping.** A dialog node and a graph node are the same object
minus five keys: `config` is the dialog node with `triggerType`/`actionType`
(which becomes the node's `type`), `description`, `deviceIds` and
`predefinedColor` removed. That is why a 1.0 dialog node maps 1:1 onto a 2.0
node.

#### What the validator rejects

Every POST is answered `success:true` and the document is **stored** even when it
fails validation — see *Save semantics* below. The errors come back in
`validationErrors`, which makes the endpoint a usable oracle when the schema
shifts again. Messages observed on 2.5.1.181 (scratch rule, deleted after):

- Wrong enum value — *"Node 't1' config.triggerCondition must be one of
  beforeSunrise, sunrise, afterSunrise, beforeSunset, sunset, afterSunset"*,
  *"Node 't1' config.switchEvent must be one of Turns on, Turns off, …"*
- Unknown type — *"Condition 'c1' has unsupported type 'bogusCondition'"*,
  *"Action node 'a1' has unsupported type 'bogusAction'"*
- A 1.0-shaped node inside a graph — *"Node 't1' config must be an object"*,
  *"Node 't1' has unsupported kind 'null'"*
- Topology — *"Rule must contain at least one trigger node"*, *"Rule must contain
  exactly one triggerMerge node"*, *"Rule must contain exactly one decision
  node"*
- A wholly non-conforming document — *"The rule is not a Visual Rule Builder 2.0
  schema version 1 document."*

**Gotcha: `sunriseSunset` (trigger) and `timeIsBetween` (condition) key their
choice on `triggerCondition`, not `condition`** — the dialog's key survived into
the schema. Accepted `timeIsBetween` configs are
`{triggerCondition:"specificTimes", startTime:"0700", endTime:"2200"}` and
`{triggerCondition:"sunriseToSunset"|"sunsetToSunrise"}`.

#### Save semantics — stored vs activated

Storage and activation are separate. The hub stores the submitted text in the
app's `state.ruleJson`, then parses and validates it; subscriptions, schedules
and the runtime graph are activated **only if validation succeeds**. So an
invalid document is kept as an **inactive draft** — `storedSuccessfully:true`,
`activatedSuccessfully:false` — and the rule stops running rather than
continuing on an older graph. Device references are registered with Hubitat's
"in use by" tracking even when another error blocks activation. The serialized
document is capped at 100,000 UTF-8 bytes; an oversized one is rejected before
any state changes.

Treat `storedSuccessfully`, `activatedSuccessfully`, `validationIssues`
(`[{nodeId, field, message}]`), `referencedDeviceIds`, `graphDocument` and
`runtimeGraph` as **optional on the wire** — read them when present, never
require them.

Paused rules: the builder appends the literal constant
`" <span class='text-red'>(Paused)</span>"` to the rule NAME (39 characters — the
chunk strips it with `.slice(0, -39)`), so any read-back name comparison must
strip HTML and tolerate that suffix.

#### AI generate (2.0)

The 2.0 dialog has its own AI path, distinct from the classic 1.0 one:
`POST /app/ruleBuilder20GenerateRule` with a JSON body `{appId, prompt}` →
`{success, graphDocument | ruleJson, message}`. The builder prefers
`graphDocument` and falls back to `JSON.parse(ruleJson)`, then feeds the result
straight through `W()` into the editor without saving. A failure surfaces as
`success:false` plus `message`. Prompt suggestions come from
`GET /app/ruleBuilder20Suggestions`.

#### The hub's own MCP server — VRB 2.0 surface

Hubitat's built-in **AI Connector Integration** app ships an MCP server at
`/mcp` that authors VRB 2.0 by handing the LLM the raw graph document plus the
authoring guide. Its surface (live-read on 2.5.1.181):

| Tool | Arguments |
|---|---|
| `hubitat_vrb2_list_rules` | none — ids, state, and an optimistic revision token per rule |
| `hubitat_vrb2_get_rule` | `ruleId` — full graph document, validation status, run-rule targets, revision token |
| `hubitat_vrb2_validate_rule` | `ruleId`, `document` — validates without storing or activating |
| `hubitat_vrb2_create_rule` | `name`, `document`, `allowSensitive` (all required) |
| `hubitat_vrb2_update_rule` | `ruleId`, `document`, `expectedRevision`, `allowSensitive` required; `name` optional |

Resource: **`hubitat://vrb2/schema`** (`text/markdown`) — the authoring guide,
vendored here as `vrb2-authoring-guide.md`.

Two behaviours worth copying. Writes are gated on an explicit
`allowSensitive: true` rather than a bare call, and updates take an
`expectedRevision` from the most recent `get_rule` for optimistic concurrency.
Its own tool text tells the model that **invalid documents remain stored as
inactive drafts** — the same draft semantics described above, surfaced to the
caller rather than hidden.

## Endpoints discovered

| Endpoint | Notes |
|---|---|
| `POST /app/saveOrUpdateJson` | `{id, source, version}` (app); same shape for `/driver/` and `/library/` |
| `GET/POST /app/ruleBuilderJson/<id>` | Serializes the raw state of ANY installed app (classic RM rules return their compiled state: `broken` flag, `eval`/`parens`/`predCapabs`, rendered condition text) and returns `{}` for nonexistent ids. ALSO the **classic Visual Rule read+WRITE endpoint**: for a classic-format VRB rule, `GET` returns `{name, rulePaused, whenNodes, thenNodes, elseNodes, promptHistory}`; the builder's `POST` body is `{name, rulePaused, whenNodes, thenNodes, elseNodes}` (the UI never posts `promptHistory` back — the hub retains it). Only the `whenNodes`+`thenNodes` shape identifies a classic VRB rule. Used by `hub_get_visual_rule`/`hub_set_visual_rule`, and the classic-RM `broken` boolean is the preferred source for `hub_get_rule_health` (shape-check: a non-empty map that is not a VRB node shape and carries `broken`). **`broken` lag (verified fw 2.5.0.143):** deleting a rule's trigger device sets the rendered `*BROKEN*` label immediately, but the compiled `broken` boolean stays `false` until the rule re-validates (e.g. its config page is rendered), then flips `true` — so a consumer should cross-check `broken` against the HTML `*BROKEN*` markers rather than trust either alone. |
| `GET/POST /app/ruleBuilder20Json/<id>` | Visual Rule Builder 2.0 (graph) rule — read+write. `ruleJson` is a **JSON STRING** (double-encoded graph); the builder posts it pretty-printed. `GET` → `{name, rulePaused, ruleJson, validationErrors}` plus, on 2.5.1, `ruleApps` (`[{id,label}]` — the `runRule` action's eligible targets) and `promptHistory` (last 5 AI prompts). `POST {name, ruleJson}` responds `{success?, name, ruleJson, validationErrors, errorMessage}` — treat the save as accepted unless `success === false`. **On 2.5.1 the POST response additionally carries `storedSuccessfully`, `activatedSuccessfully`, `storageError`, `activationError`, `validationIssues` (`[{nodeId, field, message}]`), `referencedDeviceIds`, `deviceTrackingError`, `graphDocument` and `revision`; the GET carries `revision`, `validationIssues`, `referencedDeviceIds`, `ruleApps`, `promptHistory`, `graphDocument` and `runtimeGraph` (null when nothing is active) — but NOT `storedSuccessfully`/`activatedSuccessfully` (live key list, 2.5.1.181). `revision` is the SHA-256 of the stored `ruleJson` (an empty rule reads back `e3b0c442…`, the empty-string digest) — the same optimistic token the hub's own MCP server takes as `expectedRevision`. All OPTIONAL on the wire; read them when present, never require them.** A POST answers `success:true` and STORES the document even when validation fails: that is the **inactive-draft** contract (`storedSuccessfully:true` + `activatedSuccessfully:false`), so a save succeeding does NOT mean the rule is running — see the VRB 2.0 schema section. Answers `{success:false, message:"Rule builder instance not found"}` (HTTP 200) for ANY non-graph id (nonexistent, RM, classic VRB alike). `validationErrors` is also the graph Visual Rule's health signal — `hub_get_rule_health` reports `broken=true` when it is non-empty (the VRB equivalent of RM's `broken` boolean). |
| `GET  /app/createVisualRuleBuilderRule` | Navigation create: server-creates a new VRB child and returns (or redirects to) the builder page. The new appId travels ONLY as an injected window global in the HTML — `HubitatRuleBuilder20AppId` (graph editor) or `HubitatRuleBuilderAppId` (classic editor); which global is injected reveals the firmware's native format for new rules. |
| `GET  /app/ruleBuilderPause/<id>/<true\|false>` | Pause/resume a Visual Rule — boolean rides in the path → `{success}`. Shared by BOTH builders: the 2.0 chunk calls the same path |
| `GET  /app/ruleBuilderRun/<id>` | Run a Visual Rule 2.0 now → `{success, message?}`. The builder's "run rule" button; conditions still select the action branch |
| `GET  /app/ruleBuilderGenerateRule?appId=&prompt=` | VRB AI generate (Gemini cloud) → `{success, whenNodes, thenNodes, elseNodes}`. Query params `appId` + `prompt` (Vue `handleGeminiRule`: `URLSearchParams({appId, prompt})`). Returns `{success:false, message:null}` (HTTP 200) when cloud Gemini is unconfigured. NOT folded into an MCP tool (issue #257): the success body is the SAME classic node structure `hub_set_visual_rule` already accepts, so an MCP LLM authors those nodes directly — folding it would only add a fragile cloud-Gemini dependency for no new capability |
| `GET  /app/ruleBuilderSuggestions` | Prompt suggestions for the classic (1.0) VRB AI-generate dialog |
| `POST /app/ruleBuilder20GenerateRule` | VRB **2.0** AI generate — a JSON body `{appId, prompt}` (NOT the 1.0 query-string form) → `{success, graphDocument\|ruleJson, message}`. The builder prefers `graphDocument` and falls back to `JSON.parse(ruleJson)`, loads it into the editor, and does NOT save it; `success:false` carries `message` |
| `GET  /app/ruleBuilder20Suggestions` | Prompt suggestions for the VRB **2.0** AI-generate dialog (the dialog's `suggestions-endpoint` prop) — a JSON array; live-probed `[]` on 2.5.1.181, as is the 1.0 endpoint |
| `GET  /hub2/vrb/devices` | The VRB 2.0 device picker feed and the **successor to the removed `/device/listWithCapabilities/json`** — a flat JSON array of `{id, label, capabilities[], temperature, lightEffects, supportedFanSpeeds, buttonCount}` (the last four null unless the device has them; `lightEffects` is a JSON STRING of an id→name map). Live-probed on 2.5.1.181. Read-only and capability-bearing, and it returns the FULL flat inventory — 127 entries against 127 nodes in the `/hub2/devicesList` tree on the probed hub — so unlike the Groovy device model it is not authorization-scoped. **`hub_list_devices scope='all'` and the `hub_update_mcp_settings` `selectedDevices` validation now use it as their second tier**, projecting each record to the `{id, label, capabilities}` triple the removed endpoint returned |
| `GET  /device/listWithCapabilities/json` | **Removed in platform 2.5.1.173 and later (404; confirmed on .173, .174 and .181).** Before that: all-hub device list with capabilities (`id`, `label`, `capabilities`) — fed the VRB device pickers AND `hub_list_devices` `scope='all'`. Still the first tier `hub_list_devices` tries, for hubs that predate the removal; on newer firmware it falls to `/hub2/vrb/devices` (above), which carries the same `id`/`label`/`capabilities` triple, and only then to the capability-less `/hub2/devicesList` below |
| `GET  /hub2/devicesList` | All-hub device INVENTORY as a parent/child tree: `{devices: [{key, data: {id, name, secondaryName, ...}, children: [...]}]}` — `data.name` is the user-facing label, `secondaryName` the driver name; child devices nest under their parent's `children`. Carries NO capabilities. The last resort for `hub_list_devices scope='all'`, reached only when NEITHER capability-bearing endpoint above answers: it flattens the tree and fills capabilities for authorized devices only, reporting `capabilitiesPartial` |
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
| **DESTRUCTIVE (radio)** → `hub_call_destructive_ops` (target=zwave\|zigbee\|matter, `hub_manage_destructive_ops`, `confirm`-gated): `GET /hub/zwave/resetJson` · `/hub/zigbee/reset` · `/hub/matter/reset` (network/fabric wipe — unpairs everything); `POST /hub/zwave/deviceFirmware/{start,abort}` (JSON `{nodeId,target,fileName}` / `{nodeId}`; the tool's `target_index` arg maps to the JSON `target`) · `GET /hub/zwave/startUpdateHubFirmware` · `/hub/zigbee/updateFirmware/latest` (firmware flash — device OTA, Z-Wave chip, Zigbee) | All radio operations that wipe state or can brick hardware, isolated in one confirm-gated destructive tool |
| **DESTRUCTIVE (network/cloud)** → `hub_call_destructive_ops` (`hub_manage_destructive_ops`, `confirm`-gated): `GET /hub/advanced/disconnectWiFi` · `/hub/advanced/disconnectEthernet` (target=network; drop a link — the hub may become unreachable over it); `GET /hub/advanced/disableCloudController` · `/hub/advanced/enableCloudController` (target=cloud; disabling severs Alexa/Google, cloud dashboards, cloud firmware updates, and Hub Protect/subscriptions). Endpoint paths RE'd from `vue-hub2.min.js`. | Connectivity/cloud kill-switches folded into the same confirm-gated destructive tool |
| `GET  /hub/cloud/{checkForUpdate,updatePlatform,checkUpdateStatus}` | Hub **platform/firmware** update — token-free cloud path. `checkForUpdate` → `{version, upgrade, status:"UPDATE_AVAILABLE"\|..., releaseNotesUrl, beta, accountEmails[...]}` (live-verified more current than `/hub2/hubData.alerts.platformUpdateAvailable`; `accountEmails` is the hub owner's own account email, returned verbatim); `updatePlatform` applies (downloads, installs, self-reboots); `checkUpdateStatus` → `{status:"IDLE"\|...}`. Feeds `hub_update_firmware` (confirm-gated; `statusOnly=true` polls). The `/management/firmwareUpdate?token=` family also exists (503 without `/hub/advanced/getManagementToken`) but is not used. |
| `GET  /hub/details/json` | Read the hub's location/identity settings: `{hubName, timeZone, latitude, longitude, zipCode, tempScale, dateFormat, timeFormat, ttsCurrent, mdnsName, platformVersion, hardwareVersion, sunrise, sunset, ...}` (live-verified fw 2.5.0.159). Read-only. The read-merge source for `hub_set_system_settings`. |
| `POST /location/update` | The Settings → Location **save** (the live path). Wholesale JSON body `{name, timeZone, latitude, longitude, clock, dateFormat, zipCode, temperatureScale, voice, mdnsName}` — `name` is the hub name, `clock`=`timeFormat`, `voice`=`ttsCurrent`. Because it is wholesale, **omitted fields are blanked**, so a caller MUST read-merge from `/hub/details/json` and override only the changed fields. ⚠️ **A timeZone change REBOOTS the hub** (1-3 min). Returns `{success, ...}`. Backs `hub_set_system_settings` (one atomic POST; timeZone leg is confirm-gated). NOTE: `GET /hub/updateLatLongTimezone?...` and `GET /hub/updateName?name=` are the **onboarding-wizard** endpoints (they 404 on a configured hub) — do NOT use them for a live settings change. |
| `GET  /hub/applyDarkMode/<true\|false>` | HTTP 200 **empty body**; sets the admin-UI dark/light theme. Setter-only — there is **no read-back** (`/hub/details/json` has no dark/theme key), the same shape as `GET /device/setShowOnHome`. Live-verified on fw 2.5.0.159. MAY be firmware-gated (404 on older firmware, like `/device/setShowOnHome` on 2.5.0.157). Backs `hub_set_system_settings(darkMode)` (an independent leg — NOT part of the `/location/update` POST). |
| `GET  /hub/advanced/switchToStaticIp?address=&netmask=&gateway=&nameserver=` | Settings → Network **static IP** save. Query param names RE'd from `vue-hub2.min.js` (`switchToStatic()` builds `URLSearchParams({address, netmask, gateway, nameserver})`). ⚠️ A network change can **disconnect the hub**. Backs `hub_set_system_settings(network:{ipMode:"static", address, netmask, gateway, nameserver})` (an independent, confirm-gated leg — NOT part of `/location/update`). |
| `GET  /hub/advanced/switchToDhcp?nameserver=&useDNSFallover=<true\|false>` | Settings → Network **DHCP** save (also used to override/clear DNS while on DHCP). Param names RE'd from `vue-hub2.min.js` (`switchToDHCP()` builds `URLSearchParams({nameserver, useDNSFallover})`). ⚠️ Can disconnect the hub. Backs `hub_set_system_settings(network:{ipMode:"dhcp", nameserver, useDNSFallover})` (confirm-gated leg). |
| `GET  /hub/advanced/network/ethernetMode/<true\|false>` | Settings → Network **Ethernet autonegotiation** toggle (`updateLanAutoneg()` in `vue-hub2.min.js`). ⚠️ Can disconnect the hub. Backs `hub_set_system_settings(network:{ethernetAutoneg})` (confirm-gated leg). |
| `GET  /hub/advanced/setWiFiNetworkInfo?ssid=&psk=` | Settings → Network **join WiFi** (Ethernet-only hubs). Param names RE'd from `vue-hub2.min.js` (`joinWiFiNetwork()` builds `URLSearchParams({ssid, psk})`; an async variant `setWiFiNetworkInfoAsync` + `getWiFiNetworkInfoAsyncStatus` exists for WiFi-capable hubs but is not used). ⚠️ Can disconnect the hub. Backs `hub_set_system_settings(network:{wifiSsid, wifiPassword})` — `wifiPassword` maps to `psk` (confirm-gated leg). |
| `GET  /hub2/localBackups` · `/hub2/cloudBackups?force=` | Whole-hub DB backup lists — local = array of `{name,createTime,createTimeOrig,size}`; cloud = `{backups:[{path,createTime,hubVersion,hubName}]}`. Read-only. Feed `hub_list_backups(scope=hub_local\|hub_cloud)` (issue #259 item #1). Distinct from the source-code item backups (`hub_list_backups` default scope=source). |
| `GET  /hub2/restoreLocalBackup?fileName=` · `/hub2/restoreCloudBackup?fileName=<path>&restorePassword=<pwd>&restoreZb=&restoreZw=&restoreFiles=&deleteExistingFiles=&t=<ms>` · `/hub2/restoreUploadedBackup` | Whole-hub DB restore — local by fileName; cloud by the backup's `path` (sent as `fileName`) + `restorePassword` + per-subsystem restore flags (DB always; Zigbee/Z-Wave/files opt-in); or a previously-uploaded backup. **ALL reboot the hub** → `{success}`. Feed `hub_restore_backup(scope=hub_local\|hub_cloud\|hub_uploaded)`. (`GET /hub/restoreWithReboot?localOnly=&onboarding=` is the ONBOARDING-only variant — 404s on a configured hub; not used.) |
| `GET  /hub2/deleteLocalBackup?fileName=` · `/hub2/deleteCloudBackup?path=` | Delete a whole-hub DB backup (local by name, cloud by path) → `{success}`. Feed `hub_delete_backup(location=local\|cloud)`. |
| `POST /hub2/uploadBackup` (multipart `uploadFile`=.lzf) | Upload an external `.lzf` to stage it for `restoreUploadedBackup`. Browser multipart; the MCP path (`hub_restore_backup scope=hub_uploaded`) fetches the `.lzf` from `backupUrl` and hand-rolls the multipart POST itself (OPEN-WORLD). Use case: migrate a backup from another hub / restore an off-hub archive. |
| `POST /hub2/updateBackupSchedule` | Set the automatic-backup schedule — body `{localBackupFrequency,cloudBackupFrequency,hour,minute,cloudBackupPassword}` → `{success}`. Folded into `hub_create_backup` (`schedule`/`scheduleOnly`). |
| `GET  /hub/backupDB?fileName=latest` · `/hub/backup/statusJson` | Create a hub-DB backup (async trigger; the `.lzf` is streamed but never read into the app) + completion poll (`{backupInProgress,cloudBackupInProgress}`). Back `hub_create_backup`. |
| `GET  /modes/list/json` | Location modes list — feeds the VRB mode trigger/condition/action dialogs |
| `GET  /modes/json` | Full modes payload: `{modes:[{id,name,icon,conditions}], currentModeId, selectedModeManager (builtIn\|legacy\|app), modeManagerAppId, easyModeManagerAppId}` (live-probed fw 2.5.0.157; `selectedModeManager` echoes the selected option id — observed `app` on the e2e hub). Read-only. Feeds `hub_list_modes` (per-mode icon + Mode Manager state) |
| `POST /modes/jsonCreate` · `POST /modes/jsonUpdate` · `GET /modes/jsonDelete/{id}` | Mode CRUD (Vue Modes UI). Create/update POST the mode object `{name, icon?}` (+ `id` for update); delete is id-in-path. Returns `{success, message?}`. Back `hub_manage_mode` (create/rename/delete; activate uses the SDK `location.setMode`) |
| `GET  /modes/setModeManager/{builtIn\|legacy\|app}` · `GET\|POST /modes/easyModeManager/json` | Mode Manager: select which manager runs (valid ids from the Vue `modeManagerOptions` — `builtIn` always, `legacy` when a legacy app exists, `app` only when `modeManagerAppId` is set; **`easy` is rejected "Invalid mode manager"**, live-confirmed on the e2e hub). `easyModeManager/json` is the built-in/**Integrated** Mode Manager's per-mode conditions (GET reads, POST replaces, keyed by mode id; independent of the selected manager, returns `{}` when none set). Back `hub_set_mode_manager` + `hub_list_modes.modeManager` |
| `GET  /appui/createBasicRulesChild` | Server-creates a new Basic Rules child → `{success, appId}` |
| `GET  /appui/clearEmptyBasicRules` | Sweeps empty (never-saved) Basic Rules children |
| `GET  /installedapp/configure/json/<id>` | Full live config page (sections, inputs, settings) — the RM **read** path the MCP server uses |
| `POST /installedapp/update/json` | Classic settings POST (`dynamicPage` submit) — the RM **write** path |
| `*    /installedapp/btn` | Classic page-button click. **RM rule-local-variable delete is a two-step `btn` flow** (NOT present in the Vue/`appUI` bundles — DevTools-confirmed on a live hub): click 1 = button name `<varName>` with `stateAttribute=deleteGV` (opens the inline confirm), click 2 = button name `delConfirm` with `stateAttribute=deleteConfirm` (commits the removal). The `stateAttribute` value distinguishes the two clicks even though both target the `selectActions` page. Verify the removal via `statusJson` `appState.allLocalVars`. Used by `hub_set_rule` `removeLocalVariable`. |
| `*    /installedapp/ssr/<…>` | Classic server-side page render |
| `*    /installedapp/collapseCallback/` | Section collapse state |
| `GET  /installedapp/json/<id>` | Thin app summary (id/name/type/disabled/user) |
| `GET  /installedapp/statusJson/<id>` | App status JSON. For an RM rule, `appState.allLocalVars` carries the rule's local-variable map (`{<name>: {type, value}}`) — the read/verify source for `addLocalVariable` / `setLocalVariable` / `removeLocalVariable` and `hub_list_rule_local_variables` (NOT `appSettings`). Note `appState` is a **LIST** of `{name, value}` entries, so read it as `appState.find { it.name == "allLocalVars" }.value`; the entry is absent when the rule has no locals. The `setLocalVariable` action validates its target against this map (rule-local namespace), distinct from `setVariable`'s hub-global namespace. |
| `GET  /installedapp/eventsJson/<id>` | Events history JSON |
| `POST /installedapp/forcedelete/<id>/quiet` | Force-delete, no prompts |
| `GET  /installedapp/createchild/<namespace>/<appName>/parent/<parentId>` | Server-creates a child app instance under a parent — a raw GET that 302-redirects to the new child's `configure/<id>` page. Used by the MCP server (`_rmCreateChildApp`) to instantiate classic child apps (Basic Rule, RM child, etc.) AND, on 2.5.1, the two Visual Rules Builder children by version: `appName` = `Visual Rule Builder 1.0` / `Visual Rule Builder 2.0` (the parent's own "Build New Visual Rule 1.0 / 2.0" links; live-verified on 2.5.1.181 — a fresh 2.0 child answers `ruleBuilder20Json` immediately with an empty `ruleJson`, a fresh 1.0 child answers `ruleBuilderJson` with `{}` until its first save). CAVEAT: the platform HTTP client may auto-follow an ABSOLUTE `Location` and hand back 200 with no header — the child then exists but its id was lost, which is why `_vrbCreateChild` reconciles the parent's child list before ever creating again |
| `POST /installedapp/disable` | Enable/disable an installed app — body `{id, disable:<bool>}` (`true` disables, `false` enables). Posted by `main.js` `enableApp()`/`disableApp()`. Used by `hub_set_app_disabled` (read-back verified via `/installedapp/json/<id>`) |
| `GET  /installedapp/direct/<alias>` | NOT a Vue CRUD endpoint — a name-addressed 302 redirect chain: `direct/<alias>` → `create/<typeId>` → `configure/<instanceId>` (type ids vary per hub; the alias is the stable key). Get-or-create, so it doubles as a stable name→id resolver (fw 2.5.0.143) |
| `GET  /installedapp/direct/hubVariables` | Singleton: the chain lands on the SAME instance every visit. The Vue `HubVariables` component is a non-functional stub — the classic `hubVar` wizard is the real variable-CRUD contract |
| `GET  /installedapp/direct/swapDevice` | Transient: every visit CREATES a fresh instance (1802, then 1803 observed) — callers own cleanup of instances they don't drive to completion. The swap flow itself is the classic `mainPage` wizard; its pickers offer only free-standing devices (app-owned child/component devices are excluded from both `oldDev` and `newDev`); `oldDev` additionally lists only devices referenced by at least one app, while `newDev` offers any compatible free-standing device (fw 2.5.0.143) |
| `GET  /device/getReplacementOptions/{deviceId}` | The Vue device "Replace" flow's candidate read. Returns a JSON array of compatible replacement devices: `[{id, name, deviceTypes:[...]}, ...]` (live-probed fw 2.5.0.157). Read-only. Feeds `hub_call_device_replace(list_options=true)` |
| `GET  /device/replace?oldId={X}&newId={Y}` | The Vue device "Replace" commit (re-point device X onto Y's hardware). Returns `{success, message?}`; on success the Vue sets `replacedDeviceId = oldId` — i.e. the OLD device id and ALL its app/rule references are PRESERVED (distinct from `swapDevice`, which migrates references onto the NEW device's id). Backs `hub_call_device_replace` |
| `GET  /device/drivers` | Full driver-type catalog → `{drivers:[{id, version, name, namespace, author, type(`sys`\|`usr`\|`dep`), category, ...}]}` (935 on the test hub: `sys`=built-in incl. the `Virtual *` drivers, `usr`=user). The superset of `/hub2/userDeviceTypes` (which lists only `usr`). Feeds `hub_list_drivers(include='all')`; each `id` is the deviceTypeId for `hub_create_device`. The projection buckets `usr`→user, `Virtual *`→virtual, else→system, and skips `type=='dep'` / `category=='Hidden'`. Read-only. |
| `GET  /device/setShowOnHome?deviceId=&show=<bool>` | Per-device Home-page / status-bar flag → HTTP 200 (empty body). Sets whether the device appears on the hub Home page and counts toward its quick status summaries. **Availability varies by hub: live-found 404 on a 2.5.0.157 hub, 200 on a 2.5.0.159 hub — cause not established (NOT tied to any documented release-notes change).** Feeds `hub_update_device(showOnHome)`; when this GET is absent (404), `hub_update_device` falls back to the portable `POST /device/preference/save` (below). |
| `GET  /device/setDefaultCurrentState?id=&currentState=<attr\|"">` | Sets which Current-States attribute shows in the Status column on the Devices/Rooms pages (`""`=None) → `true`; a 200 body that is NOT `true` (e.g. `false` for an unknown attribute) is a value rejection (the endpoint exists). **Availability varies by hub: live-found 404 on a 2.5.0.157 hub, 200 on a 2.5.0.159 hub — cause not established (NOT tied to any documented release-notes change).** Feeds `hub_update_device(defaultCurrentState)`; when this GET is absent (404), `hub_update_device` falls back to the portable `POST /device/preference/save` (below) — but a 200 rejection does NOT fall back. |
| `POST /device/preference/save` (JSON) | The long-standing Preferences-pane save — JSON body `{deviceId, showOnHome, defaultCurrentState, commandRetry, preferences:[]}`; a PARTIAL body (e.g. just `{deviceId, showOnHome}`) sets ONLY the named field (live-verified fw 2.5.0.159 — flips `showOnHome`, preserves label/other fields). `deviceId` is numeric. Present on both hubs tested (the 2.5.0.157 e2e hub via the fallback path and a 2.5.0.159 hub directly), so it serves as the portable fallback. Backs `hub_update_device` showOnHome/defaultCurrentState as the FALLBACK when `/device/setShowOnHome` / `/device/setDefaultCurrentState` are absent (404). |
| `GET  /device/availableTags` | JSON array of tag strings — the hub's GLOBAL tag pool (`[]` when none). Autocomplete only; tags are ASSIGNED only via the wholesale `POST /device/update` form (no dedicated setter — `setTags`/`updateTags` 404). |
| `POST /device/update` (x-www-form-urlencoded) | Wholesale device-edit **save** — OMITTED fields are BLANKED, so a caller MUST read-merge from `/device/fullJson/<id>` and re-post the COMPLETE form. deviceModel keys: `name, label, zigbeeId, maxEvents, maxStates, spammyThreshold, deviceNetworkId, deviceTypeId, deviceTypeReadableType, roomId, meshEnabled, retryEnabled, meshFullSync, homeKitEnabled, locationId, hubId, groupId, dashboardIds, tags, defaultIcon, notes` (+ `id, version, controllerType` when `id` present); booleans emit `true`→`"on"` else `"false"`. Backs `hub_update_device(tags)` (read-merge then full re-POST; identity fields restored via SDK setters if the form blanked them). |
| `GET  /device/sysDriverByIdJson/<deviceTypeId>` | Instantiate a device from a driver type (the "add device by driver" path) → `{success, deviceId, errorMessage}`. Creates a non-radio software/real instance; radio drivers become orphan shells (no paired node). Backs `hub_create_device`. |
| `GET  /device/updateLabel?deviceId=&label=` · `/device/updateRoom?deviceId=&room=<roomName>` | Dedicated label / room setters → `true`. `updateRoom` is keyed on the room NAME (not the id), and SILENTLY CREATES a spurious room for a name it doesn't match exactly (so callers must validate the name exists first AND send the canonical casing). `hub_create_device` tries `updateLabel` first for the optional `label`. **`updateLabel` availability varies by hub: live-found 404 on a 2.5.0.157 hub** (same sometimes-absent dedicated-setter class as `/device/setShowOnHome` / `/device/setDefaultCurrentState` above), so BOTH the device-allowlist bypass branch of `hub_update_device` AND the `hub_create_device` post-create label step fall back to the portable wholesale `POST /device/update` form (which carries a `label` field) when this endpoint fails; only if BOTH paths fail does `hub_create_device` warn (non-fatal, pointing the caller at `hub_update_device`). |
| `GET  /hub/compatibleDevices` | Hubitat's static compatible-device catalog — a JSON array (~1083 entries, ~1MB) of `{brand, name, deviceType, productNumber, protocol, driverName, deviceTypeId, appTypeId, integrationAppName, supportedHubs, joinInstructions, excludeInstructions, factoryResetInstructions, notes, additionalHardware, affiliateLink, zwaveAllianceId, zwaveAllianceXml, id}` with HTML pairing/exclude/factory-reset instructions. This is what the Vue "instructionSearch" page renders. Read-only. Backs `hub_get_compatible_devices` (filtered + paginated). |

## Working with the files

String literals survive minification, so `grep` is the fastest way to find a
data shape. To read control flow:

- `prettier --parser babel <file> > <file>.pretty.js`
- `npx webcrack <file> -o <out>/` — splits a webpack/Vite bundle into modules
- `npx humanify` — LLM-renames mangled identifiers (slow; only for deep dives)

## Refresh procedure

When Hubitat ships new firmware that updates the UI:

```bash
for f in main.js hubitat.min.js vue-hub2-visual-rule-builder-20.min.js; do
  curl -s "http://<hub-ip>/ui2/js/$f" -o "$f"
done
curl -s "http://<hub-ip>/ui2/js/vue-hub2.min.js" -o vue-hub2-shell-<platform>.min.js   # the code-split SHELL -- never over the monolith
```

**Do NOT blindly re-capture `appUI.js`, `helpers.js`, `hub2utils.js` or
`success-compiled.js`** — platform 2.5.1.181 serves those four minified, so a
plain overwrite destroys the unminified classic-engine source vendored here and
available nowhere else. See *Capture state* at the top. If you need the current
bytes, fetch them to a scratch path and diff the string literals rather than
replacing the readable copies.

To pick up another code-split chunk, read the chunk-id → filename map out of the
shell (`grep -o '\.u=function(e)...' vue-hub2-shell-2.5.1.min.js`) and fetch
`/ui2/js/vue-hub2-<name>.min.js` the same way.

Record the platform version + capture date in *Capture state* at the top of this
README, and update the per-file sizes in the table.

## License note

These bundles are Hubitat's proprietary distributed code, included here under
the same terms as anyone accessing them from a Hubitat hub they own — as a
reference for interoperability with the published admin HTTP surface. Do not
redistribute outside this repo or the contexts that already legitimately serve
them.
