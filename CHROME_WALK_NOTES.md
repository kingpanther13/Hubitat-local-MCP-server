# Chrome UI walkthrough notes — RM 5.1 add-action wizard

Captured live on rule 1076 (BAT-RM-CHROME-WALK) firmware 2.5.0.123 (2026-04-25).
This is the EXACT sequence the Hubitat UI sends. Reverse engineering target.

## High-level flow

1. From rule mainPage, click "Select Actions to Run" link
2. On selectActions page, click "Create New Action" (+ button)
3. On doActPage: pick actType from "Select Action Type to add" dropdown
4. Pick actSubType from "Select Which Action" dropdown
5. Fill type-specific fields (devices via "Click to set" picker, on/off toggle, etc.)
6. Click "Done with Action" button (commits action, returns to selectActions)
7. (repeat 2-6 for each additional action)
8. Click "Done with Actions" button (returns to mainPage)
9. Click "Update Rule" button on mainPage (final save / re-init)

## Per-step request capture

### Step 1: Click "Select Actions to Run" (mainPage → selectActions)
- 1 request to `/installedapp/update/json`
- 47 params, including special `_action_href_name|selectActions|5` field
- formAction=update, currentPage=mainPage, version=N, pageBreadcrumbs=["mainPage"]
- All hidden mainPage form buttons (pausRule, stopRule, useST, isFunction, ...)

### Step 2: Click "Create New Action" button (the + on selectActions)
- 6 requests:
  - 2x POST /installedapp/btn (4-5 params each): `name=N stateAttribute=doAct undefined=clicked N.type=button` (likely duplicate from mousedown + click)
  - 4x POST /installedapp/update/json: full selectActions form submission, then doActPage form submission. Triggers transition to doActPage.

### Step 3: Pick actType (Control Switches, Push Buttons)
- This step's network capture was lost (page navigation dropped JS hooks)
- BUT settings show actType.1=switchActs after step 2 (the page form on doActPage's first render)
- Behavior: changing the actType <select> fires `submitOnChange` which submits the FULL doActPage form

### Step 4: Pick actSubType (Turn switches on/off)
- 3 requests, all POST /installedapp/update/json
- 19 params each
- formAction=update, currentPage=doActPage
- settings[actType.1]=switchActs (preserved)
- settings[actSubType.1]=getOnOffSwitch (the change)
- 3 duplicates likely from select event + change event + submit event

### Step 5: Set device list ("Click to set" → check BAT-RM Switch 1 → Update)
- 3 requests, all POST /installedapp/update/json
- 28 params each
- settings[onOffSwitch.1]=282 + onOffSwitch.1.type=capability.switch + onOffSwitch.1.multiple=true

### Step 6: Click "Done with Action"
- 9 requests:
  - 3x POST /installedapp/btn: `id=1076 name=actionDone undefined=clicked actionDone.type=button` (4 params, MINIMAL)
  - 6x POST /installedapp/update/json: full doActPage form submissions (41 params) and selectActions form submissions
- After this sequence: state.actionList=['1'], actions={'1':...}, actNdx=2 — ACTION BAKED
- NO updateRule was clicked yet

### Step 7-8: Click "Done with Actions" (selectActions → mainPage)
- 6 requests, all POST /installedapp/update/json
- 3x with currentPage=selectActions (44 params)
- 3x with currentPage=mainPage (46 params)
- Triggers navigation back to mainPage

### Step 9: Click "Update Rule" button on mainPage (final save)
- 6 requests:
  - 3x POST /installedapp/btn: `id=1076 name=updateRule undefined=clicked updateRule.type=button` (4 params)
  - 3x POST /installedapp/update/json: full mainPage form (47 params)

## Critical findings

1. **Most clicks fire 3x** — the UI's button/event handlers duplicate (mousedown + change + click). Server must be idempotent.

2. **Form submissions are FULL FORMS** — every hidden input on the current page gets sent. The minimal-body click I was using only works if the server is in the right state.

3. **Action bake happens on Done-with-Action sequence** — specifically the actionDone btn click + the trailing full doActPage form submit. NO updateRule needed for the bake.

4. **Navigation happens via 2 mechanisms**:
   - Explicit: `_action_href_name|<targetPage>|<idx>` field in form submit
   - Implicit: form submit with currentPage transitioning (server figures out next page)

5. **state.doActN='N' and state.doAct='N' BOTH get set** by the live UI's "Create New Action" click. My earlier curl with stateAttribute=doActN set only state.doActN; my curl with stateAttribute=doAct set only state.doAct. The UI sends `stateAttribute=doAct` AND `undefined=clicked` AND `N.type=button` — maybe the `undefined=clicked` is what tells server to ALSO set state.doActN.

6. **Update Rule on mainPage** is the final commit/re-init — does NOT bake actions, just re-initializes the rule (subscriptions).
