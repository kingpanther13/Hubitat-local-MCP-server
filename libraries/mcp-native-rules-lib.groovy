library(name: "McpNativeRulesLib", namespace: "mcp", author: "kingpanther13", description: "Native Rule Machine + classic-app tool implementations (hub_list_rules/hub_call_rule/hub_set_rule_paused/hub_set_rule_private_boolean/hub_set_rule/hub_set_native_app/hub_delete_native_app/hub_get_rule_health) -- the full RM 5.1 wizard authoring surface plus native-app CRUD -- for the MCP Rule Server; included by the main app. Gateway entries, dispatch cases, and the shared classic-dynamicPage wizard primitives (used by other libraries) stay in the app; the RM-exclusive tool definitions, implementations, domain helpers, and per-tool metadata live here.")

def _getAllToolDefinitions_partNativeRM() {
    return [
        // Rule Machine Integration (read + trigger + pause/resume only — platform blocks CRUD)
        [
            name: "hub_list_rules",
            description: "List all Rule Machine rules (RM 4.x + 5.x, deduplicated by id) with each rule's id, label, and live `status` (`active`/`paused`/`stopped`/`disabled`).[[FLAT_TRIM]] Requires the Read master.[[/FLAT_TRIM]][[FLAT_TRIM]] `status` is `unknown` when the hub's app list is momentarily unreadable. CAVEAT: the runtime-STOPPED state is only visible here when the hub's list source decorates the label (many firmwares don't) — the authoritative stopped check is hub_get_rule_health's `stopped` field. For ONE rule's state, call hub_get_rule_health(appId) instead of listing every rule — it returns that rule's paused/disabled/stopped/broken directly. For the enabled/disabled state of NON-RM classic apps (Room Lighting, Notifier, Basic Rules, Button Controllers) use `hub_list_apps` (scope='instances'). Call `hub_get_tool_guide(section='builtin_app_tools_rules')` for the status-detection semantics and platform limitations on RM rule internals.[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    labelFilter: [type: "string", description: "Only rules whose label contains this text (any case)."],
                    cursor: [type: "string", description: "Opt-in pagination cursor. Omit for unbounded; pass \"\" for the first page, iterate nextCursor (page size 50)."]
                ]
            ]
        ],
        [
            name: "hub_call_rule",
            description: "Trigger one or more Rule Machine rules. Not destructive (invokes existing user-configured automation).[[FLAT_TRIM]] Requires the Write master.[[/FLAT_TRIM]] Call `hub_get_tool_guide(section='builtin_app_tools_rules')` for action semantics.",
            inputSchema: [
                type: "object",
                properties: [
                    ruleId: [type: ["integer", "array"], items: [type: "integer"], description: "Rule ID from hub_list_rules, or an array of rule IDs to act on in one call"],
                    action: [type: "string", enum: ["rule", "actions", "stop", "start"], description: "Which RM action to invoke. Default: rule."],
                ],
                required: ["ruleId"]
            ]
        ],
        [
            name: "hub_set_rule_paused",
            description: "Pause or resume one or more Rule Machine rules in one call (paused rules don't fire on triggers). paused=true pauses, paused=false resumes (idempotent on the hub). Pass an array of ruleIds to pause/resume a whole set at once — one RMUtils dispatch, e.g. a staged-migration cutover.[[FLAT_TRIM]] Requires the Write master.[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    ruleId: [type: ["integer", "array"], items: [type: "integer"], description: "Rule ID from hub_list_rules, or an array of rule IDs to pause/resume together"],
                    paused: [type: "boolean", description: "true = pause the rule(s); false = resume."]
                ],
                required: ["ruleId", "paused"]
            ]
        ],
        [
            name: "hub_set_rule_private_boolean",
            description: "Set the private boolean of one or more Rule Machine rules to true or false (strict: accepts Boolean or lowercase string 'true'/'false' only).[[FLAT_TRIM]] Requires the Write master.[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    ruleId: [type: ["integer", "array"], items: [type: "integer"], description: "Rule ID from hub_list_rules, or an array of rule IDs to set together"],
                    value: [type: "boolean", description: "Target value for the rule's Private Boolean."]
                ],
                required: ["ruleId", "value"]
            ]
        ],
        // Native classic-app CRUD (hub admin-layer, bypasses SmartApp parent-type check); create
        // covers the types in _appTypeRegistry(), edit and delete any classic-app appId.
        [
            name: "hub_set_native_app",
            description: """Create OR edit a classic native app (Room Lighting, Button Controller, Notifier, Group, Scene, Thermostat Scheduler/Controller, Visual Rule): omit appId to CREATE (appType + name), appId to EDIT.[[FLAT_TRIM]] One generic upsert for any classic SmartApp instance via settings/button.[[/FLAT_TRIM]] For Rule Machine RULES use hub_set_rule[[FLAT_TRIM]] (in the hub_manage_rule_machine gateway)[[/FLAT_TRIM]] instead.[[FLAT_TRIM]] Its one-call structured shortcuts are the preferred RM path, NOT this tool's raw settings/walkStep.[[/FLAT_TRIM]]

Requires the Write master + confirm=true + recent hub backup.[[FLAT_TRIM]]

On MCP 2026-07-28, eligible slow writes continue automatically across bounded Streamable HTTP requests and eventually return one final result; no custom token or client extension is needed. Older clients retain the existing status:'in_progress' remainder envelope. See hub_get_tool_guide(section='slow_ops').[[/FLAT_TRIM]]""",
            inputSchema: [
                type: "object",
                properties: [
                    appId: [type: "integer", description: "Installed-app id of an existing classic app (from hub_list_apps with scope='instances'). OMIT to CREATE a new app of `appType` (then `name` is required); PROVIDE to EDIT an existing app's settings/button."],
                    appType: [type: "string", enum: ["rule_machine", "button_controller", "groups_scenes", "scene", "notifier", "basic_rule", "room_lighting", "thermostat_scheduler", "thermostat_controller"], description: "Native app class to CREATE (appId omitted). Default: rule_machine. Visual Rules: use hub_set_visual_rule (not this enum).[[FLAT_TRIM]] Other classic apps are edited by appId and can usually be created through their parent app's own page (e.g. the Room Lighting parent's newScene input creates Room Lights from a group or scene) -- see hub_get_tool_guide(section='builtin_app_tools_crud').[[/FLAT_TRIM]]"],
                    name: [type: "string", description: "Label for the new app. Required on CREATE (when appId is omitted); ignored when appId is provided."],
                    settings: [type: "object", description: "Map {inputName: value} to write to the app's current config page: scalars for bool/enum/text/number inputs; for device inputs (capability.* or driver-specific device.*) a List of device IDs or the {id: label} map hub_get_app_config returns. Discover input names via hub_get_app_config; inputs on a sub-page need pageName or walkStep."],
                    button: [type: "string", description: "Page-transition button name to click (discover via hub_get_app_config)."],
                    pageName: [type: "string", description: "Optional sub-page for schema introspection + settings POST."],
                    stateAttribute: [type: "string", description: "Optional state attribute value for the button click."],
                    buttonRule: [type: "object", description: "Create a Button Rule under an existing Button Controller.", properties: [controllerId: [type: "integer", description: "Button Controller-5.1 appId"], buttonNumber: [type: "integer", description: "button number (>=1)"], event: [type: "string", enum: ["pushed", "held", "doubleTapped", "released"]]]],
                    walkStep: [type: "object", description: "Classic-app wizard walker, EDIT-only: inputs on a sub-page (hub_list_app_pages), inputs that appear after an earlier choice, anything settings/button cannot express. Same spec as hub_set_rule's walkStep: hub_get_tool_guide(section='set_rule_reference_walkstep')."],
                    confirm: [type: "boolean", description: "Must be true. Safety gate for Write master operations."]
                ],
                required: ["confirm"]
            ]
        ],
        [
            name: "hub_set_rule",
            description: """Create OR edit a Hubitat Rule Machine rule (RM 5.1) — one upsert tool. Omit appId to CREATE (name required; optionally bundle addTriggers/addActions/addRequiredExpression to populate in the same call). Provide appId to EDIT. Fields per capability: operation:'discover'. RM-only — NON-RM classic apps use hub_set_native_app[[FLAT_TRIM]] (Room Lighting, Button Controller, Notifier, Groups+Scenes, Visual Rule); not the legacy custom engine (hub_*_custom_rule)[[/FLAT_TRIM]]. Requires the Write master + confirm=true + recent backup; each edit keeps a File Manager baseline[[FLAT_TRIM]] (backup.backupKey; reused for an hour)[[/FLAT_TRIM]].

ALWAYS prefer the one-call shortcuts (addTrigger, addAction, addRequiredExpression/replaceRequiredExpression, bulk addTriggers/addActions/replaceActions, remove/modify/move/clear, addLocalVariable/removeLocalVariable, patches); walkStep and raw settings+button are for what they cannot express.[[FLAT_TRIM]] Each shortcut orchestrates the full RM 5.1 wizard in one call.

Partial-success (every shortcut): success:true can pair with partial:true — inspect partial/repairHints. A rejected trailing updateRule leaves the change written-but-not-live (subscriptionsNotLive / expressionNotLive / variableNotLive / patchesNotLive); retry hub_set_rule(button='updateRule', confirm=true). If wizardStuck:true, first run the close-editor click restoreHint names (cancelCapab for a condition sub-wizard or the trigger editor, actionCancel for a new action's editor, actionDone for an editor modifyAction opened on a committed action). On CREATE the new appId is returned even if a bundled item only partially bakes (partialTriggers/partialActions).[[/FLAT_TRIM]]

Deep reference[[FLAT_TRIM]] + worked examples: guide:true inline, or[[/FLAT_TRIM]]: hub_get_tool_guide(section='set_rule_reference').[[FLAT_TRIM]] Full create + repair protocol: hub_get_tool_guide(section='set_rule_create_reference'). Pass {discover:true} on addTrigger/addAction for the live machine-readable schema.

On MCP 2026-07-28, eligible slow writes continue automatically across bounded Streamable HTTP requests and return one final result. Older clients retain the status:'in_progress' remainder envelope. See hub_get_tool_guide(section='slow_ops').[[/FLAT_TRIM]]""",
            inputSchema: [
                type: "object",
                properties: [
                    appId: [type: "integer", description: "RM rule ID (the rule's installed-app id). OMIT to CREATE a new rule (then `name` is required); PROVIDE to EDIT an existing rule."],
                    name: [type: "string", description: "Label for the new rule. Required on CREATE; ignored on edit. To RENAME a rule, pass settings:{origLabel:'New Name'} instead (the mainPage write runs updateRule, which copies it to the label)."],
                    settings: [type: "object", description: "Map {inputName: value}: scalars for bool/enum/text/number inputs, list of device IDs for multi-device inputs, capability.* or driver-specific device.* (the multiple=true 3-field contract is emitted and verified automatically — you don't manage it)."],
                    button: [type: "string", description: "Page-transition button name (e.g. updateRule, editCond, pausRule for RM; discover others via hub_get_app_config)."],
                    pageName: [type: "string", description: "Optional sub-page for schema introspection + settings POST."],
                    stateAttribute: [type: "string", description: "Optional state attribute value for the button click (e.g. trigger/action index for RM editCond/editAct)."],
                    addTrigger: [
                        type: "object",
                        description: """Add an RM trigger; returns triggerIndex. `capability` is Rule Machine's own name (any case); its fields: hub_set_rule(operation:'discover', args:{kind:'trigger', capability}). Optional: condition (a kind:'condition' spec that gates the trigger), rawSettings {field: value} with @N = the trigger index."""
                    ],
                    addTriggers: [
                        type: "array",
                        description: "Add several triggers (addTrigger shape each) in order, then updateRule once. The first failed or partial item stops the batch (later items notAttempted).",
                        items: [type: "object"]
                    ],
                    addRequiredExpression: [
                        type: "object",
                        description: """Add the rule's Required Expression (the gate a trigger must pass); returns conditionIndices. {conditions:[condition, ...], operator:'AND'|'OR'|'XOR' or operators:[one per gap]}; RM evaluates left to right, so group mixed AND/OR with subExpression. Condition fields: hub_set_rule(operation:'discover', args:{kind:'condition', capability}). Sub-expressions and operators: hub_get_tool_guide(section='set_rule_reference_conditions')."""
                    ],
                    replaceRequiredExpression: [
                        type: "object",
                        description: """Replace the rule's existing Required Expression (same spec as addRequiredExpression; refuses with requiredExpressionMissing when the rule has none). The new expression is built before the old one is removed, so a failed build leaves the original in place (originalPreserved) and a failure after the switch restores it (requiredExpressionRestored)."""
                    ],

                    addActions: [
                        type: "array",
                        description: "Add several actions (addAction shape each) in order, then updateRule once. The first failed or partial item stops the batch (later items notAttempted).",
                        items: [type: "object"]
                    ],
                    addLocalVariable: [
                        type: "object",
                        description: "Add a local variable. Spec: {name, type, value} -- ALL THREE are REQUIRED (value is not optional: RM auto-commits the variable only when the value is written, so a missing value is rejected up front). type is one of Number/Decimal/String/Boolean/DateTime (case-insensitive; DateTime wants an ISO timestamp, e.g. 2026-05-06T12:00:00) and value must match the type. This differs from the setLocalVariable ACTION, which takes exactly one of value/sourceVariable/fromDevice/math. Use as %name% in actions/expressions."
                    ],
                    removeLocalVariable: [
                        type: "object",
                        description: "Remove a local variable. Spec: {name}. WARNING: RM does NOT refuse to delete a local an action still references — the delete usually succeeds and leaves the referencing action Broken; that returns success=false (deleted=true) with a repairHint to restore the pre-delete backup (backupKey in the response) or remove the references first. List current locals via hub_list_rule_local_variables (in hub_read_rules)."
                    ],
                    patches: [
                        type: "array",
                        description: "Several edits in one call, each item one operation key (any edit operation except walkStep), run in order with one updateRule at the end; per-op outcome in patches[i]. Not a transaction: the first failed or partial op stops the batch (later ops notAttempted, no updateRule) and earlier ops stay written.",
                        items: [type: "object"]
                    ],
                    removeAction: [
                        type: "object",
                        description: "Delete one action by index: {index:<N>}. RM preserves remaining indices (no renumbering)."
                    ],
                    clearActions: [
                        type: "boolean",
                        description: "Pass true to delete every action. On an unconfirmed late commit (asyncCommitLikely:true) verify via hub_get_app_config(appId) before retrying; never click cancelTrash (it can commit pending deletes)."
                    ],
                    replaceActions: [
                        type: "array",
                        description: "Clear every action, then add these specs (addAction shape) in order, then updateRule once; [] clears only. Not a transaction: the first failed or partial item stops the rest with the old list already cleared.",
                        items: [type: "object"]
                    ],
                    moveAction: [
                        type: "object",
                        description: "Move one action up or down a slot: {index:<N>, direction:'up'|'down'}. For arbitrary reorders prefer replaceActions (one call)."
                    ],
                    removeTrigger: [
                        type: "object",
                        description: "Delete one trigger by index: {index:<N>}. RM preserves remaining indices. Use addTrigger to add a replacement."
                    ],
                    modifyTrigger: [
                        type: "object",
                        description: "Change a trigger's state field: {index:<N>, mods:{state:'<new-state>'}}. Only the state field is supported, and only on device-state triggers (Switch, Motion, Contact, Lock, Presence, ...) — Time/Periodic/Mode triggers are rejected with a removeTrigger+addTrigger hint. To change capability or deviceIds, use removeTrigger + addTrigger."
                    ],
                    modifyAction: [
                        type: "object",
                        description: "Edit a committed action in place through RM's own row editor: {index:<N>, mods:{<field>: <value>, ...}}. mods take the action's fields as discover lists them (e.g. ruleIds, deviceIds, level, value) plus action for a variant that shares the committed RM action (on -> off, pause -> resume). The index, position and untouched settings are kept, and every value is read back. Expression-bearing actions (ifThen, elseIf, repeatWhile, waitExpression, waitEvents) are rebuilt with removeAction + addAction instead."
                    ],
                    walkStep: [
                        type: "object",
                        description: """Wizard walker for what the shortcuts cannot express. {page, operation: introspect|write|click|navigate|done|drive, write?:{field: value}, click?:{name, stateAttribute?}, navigate?:{targetPage}, hrefContext?:{fromPage, hrefName, hrefParams?, hrefIndex?}, validateEnum?, steps?:[...]}; drive runs steps in one call. Reference: hub_get_tool_guide(section='set_rule_reference_walkstep')."""
                    ],
                    addAction: [
                        type: "object",
                        description: """Add an RM action; returns actionIndex (the action editor finalizes it, no updateRule). `capability` and `action` are Rule Machine's; their fields: hub_set_rule(operation:'discover', args:{kind:'action', capability, action?}). ifThen/elseIf/repeatWhile/waitExpression take expression={conditions:[...], operator|operators}, each condition a kind:'condition' spec; nested subExpression only in addRequiredExpression. One waitEvents per rule. ruleIds of runRule/cancelTimers/pauseRule/privateBoolean must be existing rules; privateBoolean also takes '*' (this rule). Optional: delay {hours, minutes, seconds, cancelable}, rawSettings {field: value} with @N = the action index. A spec RM cannot bake is refused or rolled back: success:false with rolledBack:true means nothing was added; applied:null means a row or its fields may remain, so read the rule before retrying."""
                    ],
                    guide: [type: "boolean", description: "Set true to return the hub_set_rule reference inline (hub_get_tool_guide section 'set_rule_reference'). Changes no rule."],
                    buttonRule: [type: "object", description: "Create a Button Rule under an existing Button Controller: {controllerId, buttonNumber, event}. Returns buttonRuleId with the Button trigger auto-seeded — then author actions via addAction on that appId. The controller must already have a button device.", properties: [controllerId: [type: "integer", description: "Button Controller-5.1 appId"], buttonNumber: [type: "integer", description: "button number (>=1)"], event: [type: "string", enum: ["pushed", "held", "doubleTapped", "released"]]]],
                    confirm: [type: "boolean", description: "Must be true."]
                ],
                required: ["confirm"]
            ]
        ],
        [
            name: "hub_get_rule_health",
            description: """Inspect a rule's current state and return a structured health report.[[FLAT_TRIM]] Works for Rule Machine, Visual Rules Builder, and other classic apps (Button Controller, Basic Rule). This standalone read adds live eventSubscriptionCount/scheduledJobCount, runtime stopped, and status/markup fallbacks for paused. paused is true/false/null (unknown); explicit stopped state outranks display markup. Embedded health from write tools carries the structural verdict and compiled paused only, without these extra reads. With auto or ruleBuilderJson, Visual Rule labels preserve the raw own name; forcing configPage uses the rendered label.[[/FLAT_TRIM]] Run after every mutation. ok=false with unreadable=false means at least one issue was found (the issues list explains what; `warnings` lists states the rule runs with as asked, such as Required Expression on with no expression, and never lowers ok); ok=false with unreadable=true means NEITHER source could be read (a transient fetch failure, or the app does not exist) -- a couldn't-check verdict, not evidence of breakage.""",
            inputSchema: [
                type: "object",
                properties: [
                    appId: [type: "integer", description: "Installed-app ID to check (Rule Machine or Visual Rules Builder rule)."],
                    source: [type: "string", enum: ["auto", "ruleBuilderJson", "configPage"], description: "Which source(s) to read; default auto."]
                ],
                required: ["appId"]
            ]
        ],
        [
            name: "hub_list_rule_local_variables",
            description: "List a Rule Machine rule's LOCAL variables (per-rule, distinct from hub globals).[[FLAT_TRIM]] Requires the Read master.[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    appId: [type: "integer", description: "The Rule Machine rule's installed-app id (the rule whose local variables to list)."]
                ],
                required: ["appId"]
            ]
        ],
        [
            name: "hub_delete_native_app",
            description: """Delete any classic native automation app (RM rule, Room Lighting instance, Button Controller / Button Rule, Basic Rule, Notifier, Group, Scene, etc.). Same endpoint family across all of them.[[FLAT_TRIM]] force=false (default) soft-deletes (hub refuses if the app has child apps/devices); force=true hard-deletes with no child safety checks.[[/FLAT_TRIM]]

BEFORE DELETE: full snapshot saved to File Manager (backup.backupKey).[[FLAT_TRIM]] Call hub_restore_backup (in hub_manage_backup) with that key to recreate the app with all its settings re-applied.[[/FLAT_TRIM]]

Requires Write master + confirm=true + recent hub backup.""",
            inputSchema: [
                type: "object",
                properties: [
                    appId: [type: "integer", description: "Installed-app ID."],
                    force: [type: "boolean", description: "true = bypass child/device safety checks and force-delete. Default false."],
                    confirm: [type: "boolean", description: "Must be true."]
                ],
                required: ["appId", "confirm"]
            ]
        ],
        [
            name: "hub_set_app_disabled",
            description: "Enable or disable any installed app (the admin UI red-X) without deleting it — reversible and preserves the app's config. For Rule Machine rules use hub_set_rule_paused instead. Write master only — no confirm/backup needed[[FLAT_TRIM]]; the disabled flag is read-back verified[[/FLAT_TRIM]].",
            inputSchema: [
                type: "object",
                properties: [
                    appId: [type: "integer", description: "Installed-app ID to enable/disable (from hub_list_apps)."],
                    disabled: [type: "boolean", description: "true = disable the app (stop it running), false = enable it."]
                ],
                required: ["appId", "disabled"]
            ]
        ],
    ]
}

// Enable/disable any installed app via POST /installedapp/disable {id, disable} -- the documented
// Vue admin wire format (red-X). Reversible (re-enable with disabled=false). A POST that doesn't
// throw is NOT proof; the disabled flag is read back from /installedapp/json/<id>.
def toolSetAppDisabled(args) {
    def id = args?.appId
    if (id == null || !id.toString().isInteger() || id.toString().toInteger() <= 0) {
        throw new IllegalArgumentException("appId must be a positive integer (got: '${id}')")
    }
    int appId = id.toString().toInteger()
    _requireUnprotectedAppMutation(appId, "enable or disable")
    if (args?.disabled == null) {
        throw new IllegalArgumentException("disabled is required (true to disable the app, false to enable it)")
    }
    boolean disable = (args.disabled == true || args.disabled?.toString() == "true")
    try {
        hubInternalPostJson("/installedapp/disable", groovy.json.JsonOutput.toJson([id: appId, disable: disable]))
    } catch (Exception e) {
        return [success: false, appId: appId, error: "POST /installedapp/disable failed: ${e.message}", note: "Verify the app id with hub_list_apps and retry."]
    }
    // Read the flag back. Distinguish empty body / GET exception / missing-key so a read-back
    // miss can't masquerade as a clean false-RED, and a missing 'disabled' key can't be read as
    // false (a silent false-GREEN). A bare GET exception means the WRITE may have committed.
    def observed = null
    def readErr = null
    try {
        def txt = hubInternalGet("/installedapp/json/${appId}")
        if (txt) {
            def parsed = new groovy.json.JsonSlurper().parseText(txt)
            if (parsed instanceof Map && parsed.containsKey("disabled")) {
                observed = (parsed.disabled == true)
            } else {
                readErr = "read-back response carried no 'disabled' field"
            }
        } else {
            readErr = "read-back returned an empty body"
        }
    } catch (Exception e) {
        readErr = e.message
        mcpLog("warn", "hub-admin", "hub_set_app_disabled ${appId}: read-back of /installedapp/json/${appId} failed: ${e.message}")
    }
    if (observed == disable) {
        mcpLog("info", "hub-admin", "Set installed app ${appId} disabled=${disable}")
        return [success: true, appId: appId, disabled: disable, message: "App ${appId} disabled flag is now ${disable}."]
    }
    if (observed == null) {
        return [success: false, appId: appId, error: "POST /installedapp/disable was accepted but the disabled state could not be confirmed (${readErr ?: 'unparseable read-back'}).", note: "The change may already be applied -- re-check with hub_list_apps before retrying; do NOT blindly re-POST."]
    }
    return [success: false, appId: appId, disabled: observed, error: "POST accepted but read-back shows disabled=${observed} (wanted ${disable}).", note: "The hub may not have committed the flag; retry, or check the app in the hub UI."]
}

// Fetch a guide section in full for an inline meta-call on a tool that has no cursor of its own.
// Returns the same envelope hub_get_tool_guide does, with every page concatenated and the
// pagination bookkeeping stripped -- the caller asked for the reference, not a page of it.
private Map _rmWholeGuideSection(String sectionKey) {
    def result = toolGetToolGuide(sectionKey)
    if (!(result instanceof Map) || result.nextCursor == null) return result as Map
    def parts = [result.content as String]
    def cursor = result.nextCursor
    // Bounded: a page is guidePageChars() and the whole guide is well under 20 pages, so the
    // guard can only fire if the pager itself regressed.
    int guard = 0
    while (cursor != null && guard++ < 50) {
        def page = toolGetToolGuide(sectionKey, cursor)
        parts << (page.content as String)
        cursor = page.nextCursor
    }
    def whole = [:] + (result as Map)
    whole.content = parts.join('')
    whole.remove('nextCursor')
    whole.remove('offset')
    whole.remove('totalChars')
    return whole
}


// Rule Machine rules via hubitat.helper.RMUtils (RM 4.x + 5.x, deduplicated by id), ghost-filtered
// against the live /hub2/appsList tree. RMUtils is an optional platform class: absent on a hub that
// never installed Rule Machine, which surfaces as NoClassDefFoundError or ClassNotFoundException
// (Error subclasses), hence the catch Throwable and the null-guarded .message.
// Returns [combined, ghostIds, v4Error, v5Error, treeReadable, liveApps]:
//   combined     -- id(String) -> entry map, ghost-filtered when the tree read
//   ghostIds     -- ids RMUtils reported that are absent from /hub2/appsList
//   v4Error/v5Error -- RM 4.x / 5.x lookup error strings (null on success)
//   treeReadable -- false when /hub2/appsList could not be read; combined is
//                   then UNfiltered (may still carry post-delete cache ghosts)
//   liveApps     -- the /hub2/appsList map (id -> [name, disabled, type]) used for the
//                   ghost filter AND hub_list_rules status enrichment; null when
//                   the tree was unreadable (treeReadable=false)
private Map _rmCollectFilteredRmRules() {
    def combined = [:]
    def v4Error = null
    def v5Error = null

    try {
        def rules4 = hubitat.helper.RMUtils.getRuleList() ?: []
        rules4.each { r -> registerRmRule(combined, r, "4.x") }
    } catch (Throwable e) {
        v4Error = e.toString()
    }

    try {
        def rules5 = hubitat.helper.RMUtils.getRuleList("5.0") ?: []
        rules5.each { r -> registerRmRule(combined, r, "5.x") }
    } catch (Throwable e) {
        v5Error = e.toString()
    }

    // Filter RMUtils ghosts. RMUtils.getRuleList() reads a cached child
    // list inside the RM parent app and that cache keeps returning an id
    // for some time (seconds to minutes, non-deterministic) after the
    // rule's InstalledApp row is deleted — verified live on firmware
    // 2.5.0.123 for both /installedapp/delete/<id> and
    // /installedapp/forcedelete/<id>/quiet. Hub UI never shows these
    // because it reads /hub2/appsList, which is authoritative. Cross-
    // check against that tree and drop any rule whose id isn't in it.
    // If /hub2/appsList itself fails, fall through with unfiltered
    // output (treeReadable=false) so hub_list_rules keeps visibility on a
    // transient hub error and _rmValidRuleIds can treat it as cannot-verify.
    def ghostIds = []
    def treeReadable = false
    def liveApps = null
    try {
        liveApps = _collectLiveApps()
        if (liveApps != null) {
            treeReadable = true
            def filtered = [:]
            combined.each { id, entry ->
                def idInt
                try { idInt = (id instanceof Number) ? id.intValue() : id.toString().toInteger() }
                catch (Exception ignored) { idInt = null }
                if (idInt != null && liveApps.containsKey(idInt)) {
                    filtered.put(id, entry)
                } else {
                    if (idInt != null) ghostIds << idInt
                }
            }
            combined = filtered
        }
    } catch (Throwable e) {
        mcpLog("warn", "rm-interop", "_rmCollectFilteredRmRules: could not cross-check RMUtils output against /hub2/appsList (${e.message}); returning unfiltered")
    }
    return [combined: combined, ghostIds: ghostIds, v4Error: v4Error, v5Error: v5Error, treeReadable: treeReadable, liveApps: liveApps]
}

// Existence-safe set of Rule Machine rule ids (Integer) for pre-write target
// validation: the RMUtils rule list intersected with the live app tree. Returns
// null when the set cannot be trusted -- any RMUtils lookup error (Rule Machine never
// installed, or a transient one-sided v4/v5 failure) or the app-tree cross-check could
// not run -- so a caller treats "cannot verify" as skip-validation rather than a
// false-empty reject.
private Set _rmValidRuleIds() {
    def collected = _rmCollectFilteredRmRules()
    // Any RMUtils lookup error -- including a one-sided v4 or v5 failure -- makes the
    // enumeration incomplete, so the set cannot be trusted. On a 5.x-only hub the v4
    // getRuleList() legitimately returns [] with no error; if the v5 getRuleList("5.0")
    // then throws transiently, combined is empty but the rules are NOT verified-zero.
    // Requiring BOTH errors (the old && gate) would return that empty set as authoritative
    // and false-reject every existing target on the blip. RM-never-installed still errors
    // on both versions, so this keeps skipping there too.
    if (collected.v4Error || collected.v5Error) return null
    // App tree unreadable: membership is unverifiable (combined is unfiltered).
    if (!collected.treeReadable) return null
    def ids = [] as Set
    collected.combined.each { id, entry ->
        def idInt
        try { idInt = (id instanceof Number) ? id.intValue() : id.toString().toInteger() }
        catch (Exception ignored) { idInt = null }
        if (idInt != null) ids << idInt
    }
    return ids
}

// List all Rule Machine rules (RM 4.x + 5.x, deduped by id) via RMUtils, with
// post-delete cache ghosts filtered against /hub2/appsList. The RMUtils lookup and
// ghost filter live in _rmCollectFilteredRmRules (shared with the rule-target
// existence guard); this tool formats, paginates, and classifies the result.
def toolListRmRules(args) {
    def collected = _rmCollectFilteredRmRules()
    def combined = collected.combined
    def v4Error = collected.v4Error
    def v5Error = collected.v5Error
    def ghostIds = collected.ghostIds
    def treeReadable = collected.treeReadable
    def liveApps = collected.liveApps

    // Enrich each rule with its live enabled/paused/stopped/disabled status from the
    // /hub2/appsList tree (issue #359). When the tree was unreadable the rules are
    // returned unfiltered and carry no node data, so status is "unknown".
    combined.values().each { entry -> _rmAnnotateRuleStatus(entry, treeReadable, liveApps) }

    // combined.values() returns a Collection view in some Groovy versions; materialize
    // as a concrete List via toList() so subList in _paginateList is safe.
    def rules = combined.values().sort { it.label ?: "" }.toList()
    String labelFilter = args?.labelFilter?.toString()?.trim()?.toLowerCase()
    if (labelFilter) rules = rules.findAll { (stripAppConfigHtml(it.label) ?: "").toLowerCase().contains(labelFilter) }

    def cursor = args?.cursor
    def paged = _paginateList(rules, cursor, 50, "hub_list_rules")
    def result = [
        rules: paged.page,
        count: paged.page.size()
    ]
    if (cursor != null) {
        result.total = rules.size()
        if (paged.nextCursor != null) result.nextCursor = paged.nextCursor
    }
    if (ghostIds) {
        result.ghostsFiltered = ghostIds.sort()
        result.ghostNote = "RMUtils reported ${ghostIds.size()} rule id(s) that no longer exist in /hub2/appsList — these are post-delete RMUtils-cache ghosts (rule is already gone, the cache just hasn't caught up). Filtered out of the rules list."
    }
    if (!treeReadable && !rules.isEmpty()) {
        result.statusNote = "/hub2/appsList was unreadable, so each rule's status is \"unknown\" (enabled/paused/stopped/disabled could not be determined). Retry; if it persists the hub's internal API may need Hub Security credentials."
    }
    // Classify the failures. A "missing class" error (RM not installed) is quiet whether
    // the other version succeeded OR both versions failed the same way (both-absent path).
    // A non-missing-class error (e.g. timeout, internal platform issue) is always surfaced
    // so operators can investigate rather than get silent empty results.
    // Narrow to class-resolution failures only. Earlier versions treated any
    // MissingMethodException / "No signature of method" / unqualified "Cannot get property"
    // as quiet-missing, but those substrings appear in unrelated Groovy runtime errors
    // (e.g. a shape change in RMUtils' return value would surface as MME against
    // registerRmRule and get swept under the rug). Scope each substring tightly:
    //   - NoClassDefFoundError / ClassNotFoundException / "unable to resolve class":
    //     JVM-level class-resolution errors; unambiguous "RM not installed" signal.
    //   - "Cannot get property 'helper'": the Hubitat sandbox returns null for
    //     unresolved `hubitat.helper.X` namespace lookups; scoping to 'helper'
    //     limits the match to the hubitat.helper.RMUtils path specifically.
    //   - MissingMethodException together with getRuleList: covers the old-firmware
    //     case where RMUtils exists but lacks the getRuleList("5.0") overload —
    //     unrelated MMEs no longer get swallowed.
    def classMissingHint = { String msg ->
        if (!msg) return false
        if (msg.contains("NoClassDefFoundError") ||
            msg.contains("ClassNotFoundException") ||
            msg.contains("unable to resolve class")) return true
        if (msg.contains("Cannot get property") && msg.contains("'helper'")) return true
        if ((msg.contains("MissingMethodException") || msg.contains("No signature of method"))
            && msg.contains("getRuleList")) return true
        return false
    }
    def hardErrors = []
    if (v4Error && !classMissingHint(v4Error)) hardErrors << "v4=${v4Error}"
    if (v5Error && !classMissingHint(v5Error)) hardErrors << "v5=${v5Error}"

    def bothClassMissing = v4Error && v5Error &&
                           classMissingHint(v4Error) && classMissingHint(v5Error)

    if (rules.isEmpty() && bothClassMissing) {
        // Both RMUtils lookups failed with class-resolution errors. That's the
        // expected shape on a hub that has never installed Rule Machine; it's
        // not an error the caller needs to react to. Return an empty list with
        // an informational note and leave success unset so the result still
        // reads as a normal empty response.
        result.note = "Rule Machine not detected on this hub."
    } else if (rules.isEmpty() && v4Error && v5Error) {
        // Both calls failed but at least one with a non-missing-class shape --
        // unusual enough that operators should see the raw error.
        result.success = false
        result.error = "RMUtils calls failed: v4=${v4Error} v5=${v5Error}"
        result.note = "Rule Machine may not be installed on this hub."
    } else if (hardErrors) {
        // One version succeeded but the other had a non-missing-class error — surface it
        // as a warning without blocking the successful results.
        result.warning = "Partial RMUtils failure (results from the other version shown): ${hardErrors.join('; ')}"
    }
    return result
}

// Normalize a single RMUtils rule entry into a consistent map and register under combined[id].
//
// RMUtils.getRuleList() returns list entries in shapes that vary by RM version:
// - RM 5.x: single-entry Map `[<id>: "<label>"]` -- the key is the rule ID, the value is the label.
// The key may arrive as String or Integer depending on how the hub built the Map.
// - RM 4.x: Map with explicit fields `[id: <id>, label: "<label>", name: "...", type: "..."]`.
// - Raw primitive (int or String ID): defensive fallback for shapes not covered above.
private void registerRmRule(Map combined, def r, String version) {
    def id
    def label
    def name
    def type
    if (r instanceof Map) {
        if (r.containsKey("id")) {
            // RM 4.x explicit-fields shape
            id = r.id
            label = r.label ?: r.name
            name = r.name ?: r.label
            type = r.type
        } else if (r.size() == 1) {
            // RM 5.x single-entry shape: [<id>: "<label>"]
            def entry = r.entrySet().iterator().next()
            // entry.key may come back as String or Integer depending on how Hubitat built
            // the Map; coerce to Integer (via string) so downstream consumers get the
            // Integer id advertised in hub_list_rules' response schema.
            def rawKey = entry.key
            try {
                id = (rawKey instanceof Number) ? rawKey.toInteger() : rawKey.toString().toInteger()
            } catch (NumberFormatException coerceErr) {
                // Returning the raw key would silently violate the tool's
                // ruleId: integer schema (callers would see a String id in the
                // response and fail to dispatch hub_call_rule against it). Skip
                // the entry and surface the anomaly via logs instead.
                def keyType = rawKey instanceof Number ? 'Number' : rawKey instanceof String ? 'String' : 'other'
                mcpLog("warn", "rm-interop", "registerRmRule: non-integer ruleId '${rawKey}' (type=${keyType}) in RM ${version} list -- skipping entry")
                return
            }
            label = entry.value?.toString()
            name = label
        }
    } else if (r != null) {
        // Raw ID fallback
        id = r
    }
    if (id == null) return
    def key = id.toString()
    if (!combined.containsKey(key)) {
        def entry = [id: id, label: label, name: name, rmVersion: version]
        if (type != null) entry.type = type
        combined.put(key, entry)
    }
}

// Annotate an RM rule entry (in place) with disabled/paused booleans, a status summary,
// and requiredExpressionFalse. WHY decoration-detection (full algorithm in the
// builtin_app_tools guide): RMUtils exposes no paused or stopped boolean, so both are found
// only by diffing the app's /hub2/appsList name against its RMUtils label. A stopped rule
// reaches this function through EITHER of two channels and both are read here: the
// "(Stopped)" suffix can sit on the RMUtils LABEL while the appsList name stays clean, or it
// can sit in the appsList-name REMAINDER the "(Paused)" diff already computes. The diff is
// what proves a suffix is a runtime decoration rather than the rule's real name — a rule
// literally NAMED "... (Paused)"/"... (Stopped)" carries the suffix in BOTH strings, so
// neither channel fires. The suffix is stripped from the returned label/name in the SAME
// encoding the undecorated path returns (RMUtils entity-escaping preserved), never swapped
// for the HTML-stripped/decoded form the comparison uses. status is a precedence SUMMARY
// (disabled > stopped > paused > active); the booleans stay
// independent — a rule paused first then disabled keeps its decoration, so
// {disabled:true, paused:true, status:"disabled"} is truthful. "unknown" (booleans then
// omitted, never asserted false) covers both a null liveApps (whole tree unreadable) and
// this one rule's node lacking data.disabled / name / RMUtils label.
private void _rmAnnotateRuleStatus(Map entry, boolean treeReadable, Map liveApps) {
    def idInt
    try { idInt = (entry.id instanceof Number) ? entry.id.intValue() : entry.id?.toString()?.toInteger() }
    catch (Exception ignored) { idInt = null }
    def node = (treeReadable && idInt != null && liveApps != null) ? liveApps[idInt] : null
    if (node == null) {
        entry.status = "unknown"
        return
    }
    if (entry.type == null && node.type) entry.type = node.type.toString()
    def disabledRaw = node.disabled
    def cleanApps = stripAppConfigHtml(node.name)
    def cleanRm = stripAppConfigHtml(entry.label)
    if (disabledRaw == null || cleanApps == null || cleanRm == null) {
        def missing = []
        if (disabledRaw == null) missing << "data.disabled"
        if (cleanApps == null) missing << "appsList name"
        if (cleanRm == null) missing << "RMUtils label"
        mcpLog("warn", "rm-interop", "_rmAnnotateRuleStatus: rule ${entry.id} has incomplete node data (missing ${missing.join(', ')}); status unknown")
        entry.status = "unknown"
        return
    }
    def disabled = disabledRaw == true
    def paused = false
    // The literal-name test is "appsList still starts with the WHOLE RMUtils label" -- the
    // same evidence channel B uses to tell decoration from name. Testing only that appsList
    // does not END with " (Stopped)" got case three wrong: a rule literally named
    // "X (Stopped)" that is ALSO paused has appsList "X (Stopped) (Paused)", which ends
    // with "(Paused)", so the suffix in its real name read as a runtime decoration. It was
    // then reported status:"stopped" AND had "(Stopped)" stripped from both label and name,
    // so a name-to-id lookup could no longer find the rule.
    boolean stopped = (cleanRm.endsWith(" (Stopped)") && !cleanApps.startsWith(cleanRm))
    if (cleanApps != cleanRm && cleanApps.startsWith(cleanRm)) {
        def remainder = cleanApps.substring(cleanRm.length())
        if (remainder.contains("(Paused)")) paused = true
        if (remainder.contains("(Stopped)")) stopped = true
        if (remainder.contains("(Required Expression false)")) entry.requiredExpressionFalse = true
    }
    if (stopped) {
        entry.label = _rmStripTrailingDecoration(entry.label, "(Stopped)")
        entry.name = _rmStripTrailingDecoration(entry.name, "(Stopped)")
    }
    entry.disabled = disabled
    entry.paused = paused
    entry.status = disabled ? "disabled" : (stopped ? "stopped" : (paused ? "paused" : "active"))
}

// Drop a trailing runtime decoration from a label WITHOUT re-encoding it: the caller's
// string keeps whatever escaping its source used, so a decorated and an undecorated rule
// come back in the same encoding. Returns the input unchanged when the suffix is absent.
private String _rmStripTrailingDecoration(Object raw, String suffix) {
    def s = raw?.toString()
    if (s == null) return null
    // RMUtils labels can carry trailing spaces, so match past them.
    def trimmed = s.replaceAll(/\s+$/, "")
    if (!trimmed.endsWith(suffix)) return s
    return trimmed.substring(0, trimmed.length() - suffix.length()).replaceAll(/\s+$/, "")
}

// Normalize a ruleId argument that may be a single id or a list of ids into a
// deduped List<Integer>. RMUtils.sendAction natively takes a LIST of rule ids,
// so the RMUtils-backed tools accept either shape and dispatch once.
//
// Multi-id batches also existence-check every id against the live RM rule list
// BEFORE any dispatch: the array form is sold as the staged-migration cutover,
// where one typo'd id silently left un-actioned (RMUtils is fire-and-forget and
// reports nothing per-rule) is the exact failure the batch exists to prevent.
// Single-id calls keep their long-standing no-validation behavior. The check
// skips when the rule list is unverifiable (_rmValidRuleIds returns null).
private Map _rmRuleIdListArg(Object raw) {
    if (raw == null) throw new IllegalArgumentException("ruleId is required")
    def rawList = (raw instanceof List) ? raw : [raw]
    if (rawList.isEmpty()) throw new IllegalArgumentException("ruleId array must not be empty")
    List<Integer> ids = []
    rawList.each { id ->
        // _rmCoerceRuleId, not normalizeRuleId: a JSON-number 400.7 would silently
        // TRUNCATE to rule 400 via toInteger() -- and then PASS the existence
        // check below, pausing the wrong rule with full confidence.
        def n = _rmCoerceRuleId(id)
        if (n == null) {
            throw new IllegalArgumentException("ruleId '${id}' is not an integer-valued rule id. Use hub_list_rules to find valid ids.")
        }
        if (!ids.contains(n)) ids << n
    }
    Boolean verified = null
    if (ids.size() > 1) {
        def valid = _rmValidRuleIds()
        if (valid != null) {
            verified = true
            def unknown = ids.findAll { !valid.contains(it) }
            if (unknown) {
                throw new IllegalArgumentException("ruleId array contains id(s) that are not existing RM rules: ${unknown.join(', ')}. Nothing was dispatched. Verify ids via hub_list_rules and re-issue the batch.")
            }
        } else {
            // Cannot verify (RMUtils error / app tree unreadable): the batch still
            // dispatches, but the caller is TOLD -- the tool description promises
            // an up-front existence check, and a silent skip would let a typo'd id
            // report success on a fire-and-forget dispatch.
            verified = false
            mcpLog("warn", "rm-interop", "_rmRuleIdListArg: multi-id existence check SKIPPED (rule list unverifiable) for ids ${ids.join(', ')}")
        }
    }
    return [ids: ids, idsVerified: verified]
}

// Trigger a Rule Machine rule via RMUtils.sendAction() (rule) or the RM page
// buttons for actions/stop/start. actions clicks runAction, the UI's "Run Actions"
// button; stop/start toggle stopRule (RMUtils has no startRule verb — the RM 5.1 UI
// uses the stopRule button as a toggle on state.stopped, and clicking it while
// stopped restarts the rule and resets the private boolean). The button route is
// the one the hub UI itself takes, so unlike RMUtils.sendAction it is not refused by
// the platform's per-app load limiter.
//
// Not destructive — invokes existing user-configured automation.
def toolRunRmRule(args) {
    if (args?.ruleId == null) throw new IllegalArgumentException("ruleId is required")
    def idArg = _rmRuleIdListArg(args.ruleId)
    List<Integer> ruleIds = idArg.ids
    ruleIds.each { _requireUnprotectedAppMutation(it, "change rule runtime") }
    def action = args?.action ?: "rule"

    // start/stop route through the stopRule button click (a toggle on
    // state.stopped) rather than RMUtils.sendAction — verified live on
    // firmware 2.5.0.123 that clicking stopRule when state.stopped=false
    // sets it true (unsubscribe + cancel delays/repeats/waits) and when
    // state.stopped=true clears it + re-runs initialize() + resets the
    // private boolean. To keep the verb idempotent, we read state before
    // clicking and no-op if the rule is already in the target state.
    if (action == "stop" || action == "start" || action == "actions") {
        boolean runActions = (action == "actions")
        def perRule = { Integer id -> runActions ? _rmRunActionsButton(id) : _rmToggleStopped(id, action) }
        String verb = runActions ? "runAction button" : "stopRule toggle"
        if (ruleIds.size() == 1) {
            def single = perRule(ruleIds[0])
            // Every call echoes ruleIds -- scalar and array alike -- matching the
            // sendRmAction paths, so callers can read one key on every shape.
            if (single instanceof Map) single.ruleIds = ruleIds
            return single
        }
        // Per-rule toggle loop with a relay-budget checkpoint: each toggle is a
        // statusJson read + (maybe) a button click, so a long list can outlive the
        // response budget. _rmToggleStopped no-ops rules already in the target
        // state, so re-issuing with the remainder is safe.
        def results = []
        List remaining = []
        boolean budgetPaused = false
        for (int i = 0; i < ruleIds.size(); i++) {
            if (i > 0 && _timeBudgetExceeded(args?.__reqT0 as Long)) {
                budgetPaused = true
                remaining = ruleIds.subList(i, ruleIds.size()).collect { it }
                break
            }
            results << perRule(ruleIds[i])
        }
        List failed = []
        results.eachWithIndex { r, i -> if (r?.success != true) failed << ruleIds[i] }
        def out = [
            success: failed.isEmpty() && !budgetPaused,
            partial: budgetPaused || (!failed.isEmpty() && failed.size() < results.size()),
            ruleIds: ruleIds,
            rmAction: "${verb} x${results.size()}".toString(),
            results: results
        ]
        if (idArg.idsVerified != null) out.idsVerified = idArg.idsVerified
        if (failed) {
            out.failedRuleIds = failed
            def actionedCount = results.size() - failed.size()
            out.error = (actionedCount == 0)
                ? "${verb} failed for EVERY rule in the batch (${failed.join(', ')}); per-rule detail in results[].".toString()
                : "${verb} failed for rule(s) ${failed.join(', ')} -- the other ${actionedCount} rule(s) in the batch WERE actioned; per-rule detail in results[].".toString()
            out.note = runActions
                ? "Re-issue for the failed ids only: a repeat of a succeeded id runs that rule's actions again."
                : "Re-issue for the failed ids only. A repeat of a succeeded id is safe: the toggle no-ops rules already in the target state."
        }
        if (budgetPaused) {
            out.remainingRuleIds = remaining
            if (!out.error) out.error = "Response-budget checkpoint: rule(s) ${remaining.join(', ')} were not yet actioned."
            // Failed ids and un-reached ids are DIFFERENT sets -- the re-issue
            // guidance must carry both or a caller following it strands the failures.
            def reissueIds = (failed + remaining).unique()
            out.note = "Budget checkpoint -- re-issue with ruleId=[${reissueIds.join(', ')}] to finish${failed ? " (includes the ${failed.size()} failed id(s), not just the un-reached ones)" : ""}; already-actioned rules no-op safely."
        }
        return out
    }

    if (action != "rule") throw new IllegalArgumentException("Invalid action '${action}'. Must be 'rule', 'actions', 'stop', or 'start'.")
    def result = sendRmAction(ruleIds, "runRule", "hub_call_rule action=${action}")
    if (result instanceof Map && idArg.idsVerified != null) result.idsVerified = idArg.idsVerified
    return result
}

// Click the rule's Run Actions button (runAction on the main page): runs the action list
// directly, skipping condition evaluation, exactly as the hub UI does.
private Map _rmRunActionsButton(Integer ruleId) {
    try {
        _rmClickAppButton(ruleId, "runAction")
    } catch (Exception e) {
        return [success: false, ruleId: ruleId, error: "hub_call_rule action=actions: runAction button click failed (${e.message})"]
    }
    return [success: true, ruleId: ruleId, rmAction: "runAction button"]
}

// Drive the RM 5.1 stopRule button — the same toggle the hub UI exposes
// for Stop/Start. `action` is one of "stop" or "start". Idempotent:
// clicking stopRule when state.stopped is already the target value would
// toggle it the wrong way (running -> stopped, stopped -> running), so
// we read state.stopped first and no-op if we're already there.
private Map _rmToggleStopped(Integer ruleId, String action) {
    def status
    try {
        status = _rmFetchStatusJson(ruleId)
    } catch (Exception e) {
        return [success: false, ruleId: ruleId, error: "hub_call_rule action=${action}: cannot read rule state before toggle (${e.message})"]
    }
    def stoppedNow = _readAppStateBoolean(status, "stopped", false)
    def targetStopped = (action == "stop")
    if (stoppedNow == targetStopped) {
        return [
            success: true,
            ruleId: ruleId,
            rmAction: "noop",
            note: "Rule was already ${action == 'stop' ? 'stopped' : 'running (not stopped)'} -- stopRule button not clicked."
        ]
    }
    try {
        _rmClickAppButton(ruleId, "stopRule")
    } catch (Exception e) {
        return [success: false, ruleId: ruleId, error: "hub_call_rule action=${action}: stopRule button click failed (${e.message})"]
    }
    return [
        success: true,
        ruleId: ruleId,
        rmAction: (action == "stop") ? "stopRule button -> state.stopped=true" : "stopRule button -> state.stopped=false (initialize + private boolean reset)"
    ]
}

// Pull a named Boolean out of statusJson.appState[]. Hubitat serializes
// Booleans in appState entries as the real `true`/`false` value, but
// occasionally as the string "true"/"false" -- treat both equivalently.
private Boolean _readAppStateBoolean(Map status, String name, Boolean fallback) {
    def entry = (status?.appState ?: []).find { it?.name == name }
    if (entry == null) return fallback
    def v = entry.value
    if (v instanceof Boolean) return v
    if (v == "true") return true
    if (v == "false") return false
    return fallback
}

// Fetch statusJson for {@code appId} and return the raw value of
// {@code state.editAct}, or {@code null} if the entry is absent.
//
// <p>RM 5.1 sets {@code state.editAct} to the action index whenever an
// action edit is in flight. While set, {@code delAct} and move-arrow
// clicks are silently no-oped by RM's {@code appButtonHandler}. This
// helper is used as a pre-flight guard in {@code _rmDeleteAction} and
// {@code _rmMoveAction} so callers get a descriptive error immediately
// rather than retrying for 10s before hitting the generic timeout.
//
// <p>RM is BELIEVED to clear a genuinely in-flight {@code state.editAct}
// after ~60s, but this is UNVERIFIED for a stale marker: one artificially set
// via a raw stateAttribute click was observed live (fw 2.5.1.135) NOT to clear
// after 5+ minutes. Treat the timeout as best-effort, not guaranteed. The value
// is typically an Integer (the action index) but may be serialized as a
// String by some firmware versions -- returned as-is without coercion.
private Object _rmGetStateEditAct(Integer appId) {
    def status = _rmFetchStatusJson(appId)
    def entry = (status?.appState ?: []).find { it?.name?.toString() == "editAct" }
    return entry?.value
}

// Pause or resume a Rule Machine rule via RMUtils.sendAction(pauseRule|resumeRule).
// paused=true pauses, paused=false resumes. Idempotent on the hub side. Backs the
// hub_set_rule_paused tool (verb-pair merge of the former pause/resume tools).
def toolSetRulePaused(args) {
    if (args?.ruleId == null) throw new IllegalArgumentException("ruleId is required")
    if (args?.paused == null) throw new IllegalArgumentException("paused (boolean) is required: true=pause, false=resume")
    Boolean paused
    if (args.paused instanceof Boolean) paused = args.paused
    else if (args.paused == "true") paused = true
    else if (args.paused == "false") paused = false
    else throw new IllegalArgumentException("paused must be boolean true/false (got: ${args.paused})")
    def idArg = _rmRuleIdListArg(args.ruleId)
    List<Integer> ruleIds = idArg.ids
    ruleIds.each { _requireUnprotectedAppMutation(it, "change rule runtime") }
    def result = paused ? sendRmAction(ruleIds, "pauseRule", "hub_set_rule_paused")
                        : sendRmAction(ruleIds, "resumeRule", "hub_set_rule_paused")
    // idsVerified: true = every id existence-checked before dispatch; false = the
    // check was SKIPPED (rule list unverifiable) and this fire-and-forget batch
    // may include ids that do not exist. Absent on single-id calls.
    if (result instanceof Map && idArg.idsVerified != null) result.idsVerified = idArg.idsVerified
    // Echo the applied pause state so callers can confirm the outcome without a
    // follow-up read. RMUtils pause/resume is fire-and-forget, so this is the
    // requested/applied value, not a hub read-back.
    if (result instanceof Map) result.paused = paused
    return result
}

// Set a rule's private boolean through RMUtils. Only true/false (or "true"/"false") are accepted:
// guessing a boolean from "yes" or 1 could arm the wrong path.
def toolSetRmRuleBoolean(args) {
    if (args?.ruleId == null) throw new IllegalArgumentException("ruleId is required")
    if (args?.value == null) throw new IllegalArgumentException("value (boolean) is required")
    // Accept Boolean or the canonical lowercase strings 'true'/'false' only. Reject other
    // truthy-looking values (1, 'yes', 'True') to avoid silently setting the wrong boolean.
    Boolean resolved = null
    if (args.value instanceof Boolean) {
        resolved = args.value
    } else if (args.value == "true") {
        resolved = true
    } else if (args.value == "false") {
        resolved = false
    } else {
        throw new IllegalArgumentException("value must be boolean true/false (or the string 'true'/'false'), got: ${args.value}")
    }
    def rmAction = resolved ? "setRuleBooleanTrue" : "setRuleBooleanFalse"
    def idArg = _rmRuleIdListArg(args.ruleId)
    idArg.ids.each { _requireUnprotectedAppMutation(it, "set rule private boolean") }
    def result = sendRmAction(idArg.ids as List, rmAction, "hub_set_rule_private_boolean value=${resolved}")
    if (result instanceof Map && idArg.idsVerified != null) result.idsVerified = idArg.idsVerified
    return result
}

// Shared sendAction wrapper. Returns a consistent success/error result map.
//
// Invariant: the 4-arg form `sendAction([ids], action, appLabel, "5.0")` dispatches
// to the RM 5.x handler and is the canonical call for both RM 4.x and RM 5.x rules.
// The 3-arg form `sendAction([ids], action, appLabel)` reaches only RM 4.x and is
// used as a fallback for very old firmware that predates the version-discriminator
// overload (see sendRmActionFallback). Background on the RM API shape:
// https://community.hubitat.com/t/rule-machine-api/7104
private Map sendRmAction(List<Integer> ruleIds, String rmAction, String logContext) {
    def appLabel = app?.label ?: "MCP Rule Server"
    try {
        hubitat.helper.RMUtils.sendAction(ruleIds, rmAction, appLabel, "5.0")
        mcpLog("info", "rm-interop", "${logContext}: sent ${rmAction} to rule(s) ${ruleIds.join(', ')}")
        def result = [success: true, ruleIds: ruleIds, rmAction: rmAction]
        if (ruleIds.size() == 1) result.ruleId = ruleIds[0]
        return result
    } catch (MissingMethodException e) {
        return sendRmActionFallback(ruleIds, rmAction, appLabel, logContext, e)
    } catch (NoSuchMethodError e) {
        return sendRmActionFallback(ruleIds, rmAction, appLabel, logContext, e)
    } catch (Throwable e) {
        // Everything else — NoClassDefFoundError (RM not installed), timeouts, NPEs
        // inside RM internals, invalid ruleId — propagates through a single error
        // response without a second attempt. Retrying a transient failure would
        // double-fire the action if the first call partially succeeded.
        def m = e.message ?: e.toString()
        mcpLog("error", "rm-interop", "${logContext} failed for rule(s) ${ruleIds.join(', ')}: ${m}")
        return [success: false, error: "RMUtils.sendAction failed: ${m}", note: _rmSendActionErrorNote(rmAction, ruleIds, m)]
    }
}

// Single source of truth for the RMUtils load-limiter marker substring (matched
// case-insensitively). RMUtils surfaces this text when the hub's per-app load limiter
// has tripped; centralized so a wording change is one edit, not a scatter of literals.
private String _rmExcessiveLoadMarker() { "excessive hub load" }

// Build the actionable note for a failed RMUtils.sendAction. When RMUtils reports the hub's
// per-app load limiter, steer recovery correctly: that limiter is STICKY -- it does NOT lift when
// hub load drains, so an immediate retry of the same call just fails again. An app disable/enable
// only clears the block on the app INSTANCE and the platform's load counters survive it, so the
// retry after a bounce can re-trip immediately (observed live); only a hub REBOOT resets those
// counters. On a pause/resume call the direct page-button escape
// bypasses RMUtils and is load-immune, so it IS the real recovery. Pause and resume share a
// SINGLE toggle button, pausRule, whose title flips between "Pause" and "Resume" with the rule's
// current run state (the same one-button toggle pattern as stopRule); clicking pausRule on a
// paused rule resumes it, so both the pause and resume cases steer to the same pausRule button.
// Substring is scoped tightly to RMUtils' load-message text so unrelated failures keep the plain note.
private String _rmSendActionErrorNote(String rmAction, List<Integer> ruleIds, String message) {
    def base = "Verify the ruleId is valid (use hub_list_rules) and Rule Machine is installed."
    if (ruleIds.size() > 1) {
        // A LIST dispatch that threw may have actioned part of the set before
        // failing -- a reflex retry double-fires runRule/runRuleAct actions.
        base = "${base} NOTE: this was a LIST dispatch (${ruleIds.join(', ')}) -- part of the set may already have been actioned; verify per-rule state via hub_list_rules before retrying (a blind retry re-fires already-actioned rules)."
    }
    if (message && message.toLowerCase().contains(_rmExcessiveLoadMarker())) {
        if (rmAction == "pauseRule" || rmAction == "resumeRule") {
            def verb = (rmAction == "resumeRule") ? "resume" : "pause"
            def perRule = (ruleIds.size() == 1) ? "hub_set_rule(appId=${ruleIds[0]}, button:'pausRule', confirm:true)"
                                                : "hub_set_rule(appId=<id>, button:'pausRule', confirm:true) per rule (${ruleIds.join(', ')}) -- CHECK EACH RULE'S CURRENT STATE FIRST via hub_list_rules: the batch went out in one RMUtils dispatch and part of it may already have been actioned, and pausRule is a toggle, so clicking it on a rule the batch already ${verb}d UNDOES that rule"
            return "RMUtils hit the hub's per-app load limiter (sticky -- retrying does not clear it; an app disable/enable clears only the block on the app instance and the platform's load counters can re-trip it at once, so only a hub reboot fully resets it). To ${verb} the rule now, drive the page button directly with ${perRule}, which bypasses RMUtils and is load-immune (pausRule is a single toggle -- clicking it on a paused rule resumes it). ${base}"
        }
        return "RMUtils hit the hub's per-app load limiter. It is sticky -- it does NOT lift when load drains and an immediate retry fails the same way. An app disable/enable clears the block on the app instance but not the platform's load counters, so it can re-trip immediately; a hub reboot is what resets them. Clear it, then reissue. ${base}"
    }
    return base
}

// 3-arg fallback invoked only when the 4-arg sendAction raised a signature-mismatch
// shape (MissingMethodException / NoSuchMethodError) — very old firmware that
// predates the version-discriminator overload. Any other Throwable from the 4-arg
// call propagates directly in sendRmAction above.
private Map sendRmActionFallback(List<Integer> ruleIds, String rmAction, String appLabel, String logContext, Throwable original) {
    try {
        hubitat.helper.RMUtils.sendAction(ruleIds, rmAction, appLabel)
        mcpLog("info", "rm-interop", "${logContext}: sent ${rmAction} to rule(s) ${ruleIds.join(', ')} (3-arg fallback)")
        def result = [success: true, ruleIds: ruleIds, rmAction: rmAction, fallback: "3-arg"]
        if (ruleIds.size() == 1) result.ruleId = ruleIds[0]
        return result
    } catch (Throwable e2) {
        def m1 = original.message ?: original.toString()
        def m2 = e2.message ?: e2.toString()
        mcpLog("error", "rm-interop", "${logContext} failed for rule(s) ${ruleIds.join(', ')}: 4-arg=${m1}, 3-arg=${m2}")
        return [success: false, error: "RMUtils.sendAction failed: ${m2}", note: "4-arg attempt also failed: ${m1}. ${_rmSendActionErrorNote(rmAction, ruleIds, m2)}"]
    }
}

// Resolve the page-transition button to click after a main-page settings
// write commits, for the app type whose live config reports `configAppName`
// (config.app.appType.name -- the child appName the registry keys on).
// Returns "updateRule" (the RM framework default) for unrecognized or
// RM-family types, and the registry's explicit `commitButton` (which may be
// null) for types that override it. A null return means "no commit click" --
// the app's inputs are submitOnChange and clicking updateRule would poison
// the render (e.g. Basic Rule's "For input string: updateRule").
// Repair hint for a session-end mainPage Done that did not commit. A Done the page itself refused
// (uiBlocked) posted nothing: the fix is the named fields, not a commit click.
private String _rmMainPageDoneRepairHint(Integer appId, Map done) {
    if (done?.uiBlocked == true) {
        return "The closing mainPage Done was refused, as Hubitat's app page would refuse it (${done.reason}). Settings are already written; set the listed fields with hub_set_native_app(appId=${appId}, settings={...}), which re-runs the Done.".toString()
    }
    return "The session-end mainPage Done click did not commit (${done?.reason}). Settings are already written, but the app's update lifecycle did not run -- verify via hub_get_app_config(appId=${appId}) and re-commit via hub_set_native_app(appId=${appId}, button='updateRule') for RM-family apps.".toString()
}

// True when configAppName is a registry type (matching as _resolveCommitButton does) or the Room
// Lighting parent, i.e. its commit button is known rather than defaulted.
private boolean _appTypeRegistryKnows(String configAppName) {
    if (!configAppName) return false
    if (configAppName == "Room Lighting") return true
    def versioned = (configAppName =~ /^(.+) [0-9]+(?:\.[0-9]+)*$/)
    def bare = versioned.matches() ? (versioned[0] as List)[1].toString() : null
    return _appTypeRegistry().any { typeKey, reg -> reg.appName == configAppName || (bare != null && reg.appName == bare) }
}

private String _resolveCommitButton(String configAppName) {
    if (!configAppName) return "updateRule"
    // The Room Lighting parent has no updateRule button and no create entry of its own. A settings write on
    // the Room Lighting parent (e.g. newScene) already does its work; clicking updateRule there
    // throws MissingMethodException in its appButtonHandler (verified live, fw 2.5.1.181).
    if (configAppName == "Room Lighting") return null
    // The hub reports Visual Rule children versioned ("Visual Rule Builder 2.0"); the registry holds
    // the bare family name. Exact match first, then the version-stripped name.
    def versioned = (configAppName.toString() =~ /^(.+) [0-9]+(?:\.[0-9]+)*$/)
    def bare = versioned.matches() ? (versioned[0] as List)[1].toString() : null
    def match = _appTypeRegistry().find { typeKey, reg -> reg.appName == configAppName } ?:
                (bare != null ? _appTypeRegistry().find { typeKey, reg -> reg.appName == bare } : null)
    if (match != null && match.value.containsKey("commitButton")) return match.value.commitButton
    return "updateRule"
}

// Detect whether the given configPage still carries an open RM-style
// wizard editor scaffold (any input whose name matches the well-known
// trigger/condition/action edit-scaffold patterns). Used by
// hub_set_rule to decide whether a wizard-Done button (`hasAll`,
// `actionDone`, ...) actually closed the editor or whether it needs a
// second click to commit. Verified live on firmware 2.5.0.123: the
// first wizard-Done click frequently leaves the scaffold in place and
// a second click of the same button finalizes the commit.
//
// Patterns recognized:
// isCondTrig.<N>   trigger-N "is conditional?" toggle (always present
// while a trigger row is open for editing)
// tCapab<N>        trigger-N capability picker
// tDev<N>          trigger-N device picker
// tstate<N>        trigger-N state value
// AlltDev<N>       trigger-N "all of these?" toggle
// stays<N>         trigger-N "and stays?" toggle
// editAct<N>       action-N edit scaffold
// editCond<N>      condition-N edit scaffold
private boolean _rmHasWizardScaffold(Map configPage) {
    if (!(configPage?.sections instanceof List)) return false
    def scaffoldPattern = ~/^(isCondTrig\.\d+|tCapab\d+|tDev\d+|tstate\d+|AlltDev\d+|stays\d+|editAct\d+|editCond\d+)$/
    for (sec in configPage.sections) {
        for (inp in (sec?.input ?: [])) {
            def n = inp?.name?.toString()
            if (n && scaffoldPattern.matcher(n).matches()) return true
        }
    }
    return false
}

// After a wizard-Done button click, the trigger summary row appears
// but the page sometimes leaves a single `isCondTrig.<N>` input on its
// own — RM 5.1's "Conditional Trigger?" follow-up prompt. Returns that
// input name (e.g. "isCondTrig.3") if it's the ONLY trigger-edit input
// remaining on the page, or null otherwise. Callers use this to auto-
// finalize by writing `<name>=false` (the canonical "no, not
// conditional" answer that closes the prompt without consuming an extra
// trigger index, unlike a second hasAll click).
//
// Returns null if either:
// - No isCondTrig.<N> input is present (nothing to finalize)
// - Other editor-scaffold inputs are also present (caller hasn't
// finished filling the trigger; finalizing now would commit an
// incomplete trigger). The caller's higher-level scaffold check
// handles that case separately.
private String _rmFindResidualCondTrig(Map configPage) {
    if (!(configPage?.sections instanceof List)) return null
    def condTrigPattern = ~/^isCondTrig\.\d+$/
    def otherScaffoldPattern = ~/^(tCapab\d+|tDev\d+|tstate\d+|AlltDev\d+|stays\d+|editAct\d+|editCond\d+)$/
    String foundCondTrig = null
    for (sec in configPage.sections) {
        for (inp in (sec?.input ?: [])) {
            def n = inp?.name?.toString()
            if (!n) continue
            if (condTrigPattern.matcher(n).matches()) {
                if (foundCondTrig != null) return null  // Multiple condTrigs — not a clean finalize case
                foundCondTrig = n
            } else if (otherScaffoldPattern.matcher(n).matches()) {
                return null  // Wider scaffold present — let the WARN path handle
            }
        }
    }
    return foundCondTrig
}

// Decide whether a just-clicked updateRule left an RM rule with pending
// subscription work. Returns null if the rule has no trigger-bearing
// settings at all (no lag to detect), otherwise returns a small map with
// `unsettled` (Boolean) + `triggerCount` (Integer) + `subCount` (Integer) +
// `suppressedBy` (why RM is withholding subscriptions, or null) so the caller
// can decide whether to auto-retry / warn. Also null when statusJson is unreadable.
//
// Unsettled = rule has at least one tDev<N> multi-device capability
// setting (trigger is device-backed) but eventSubscriptions is empty.
// HTTP-only, time-only, and triggerless rules legitimately have empty
// eventSubscriptions, so we scope the "unsettled" flag to the specific
// trigger shape that MUST produce subscriptions when initialized.
//
// Verified live on firmware 2.5.0.123: after a fresh trigger-wizard
// hasAll commit followed by updateRule, eventSubscriptions can stay
// at 0 for up to ~minute before populating — a second updateRule click
// typically settles them immediately. This function detects the case
// so hub_set_rule can auto-retry once + surface a clean warning
// if the retry still doesn't help.
private Map _rmCheckSubscriptionSettle(Integer appId) {
    def status
    try {
        status = _rmFetchStatusJson(appId)
    } catch (Exception e) {
        return null
    }
    def settings = status?.appSettings ?: []
    def triggerDevs = settings.findAll { s ->
        def n = s?.name?.toString()
        n?.matches(/^tDev\d+$/) && (s?.deviceIdsForDeviceList instanceof List) && s.deviceIdsForDeviceList
    }
    if (!triggerDevs) return null
    def subs = status?.eventSubscriptions ?: []
    return [
        unsettled: subs.isEmpty(),
        triggerCount: triggerDevs.size(),
        subCount: subs.size(),
        suppressedBy: _rmTriggerSubscriptionGate(status)
    ]
}

private String _rmSuppressedSettleText(Object gate) {
    def why = [requiredExpressionFalse: "its Required Expression is false (RM removes trigger subscriptions until it is true)",
               paused: "the rule is paused", stopped: "the rule is stopped"][gate?.toString()] ?: "RM reports the rule as inactive (${gate})"
    return "SUPPRESSED: eventSubscriptions=0 because ${why}. This is expected and says nothing about whether the trigger is complete; check the trigger again with updateRule once the rule is active.".toString()
}

// Why RM would hold no trigger subscriptions on purpose, or null. RM removes them while
// the Required Expression is false, and a paused or stopped rule has none either.
private String _rmTriggerSubscriptionGate(Map status) {
    def appState = status?.appState
    if (appState instanceof List) {
        if (_readAppStateBoolean(status, "stopped", false)) return "stopped"
        if (_readAppStateBoolean(status, "paused", false)) return "paused"
    }
    // RM's own decoration is a styled span; a rule merely named "... (Paused)" is plain text.
    def label = status?.installedApp?.label?.toString() ?: ""
    if (label.contains(">(Required Expression false)</span>")) return "requiredExpressionFalse"
    if (label.contains(">(Paused)</span>")) return "paused"
    if (label.contains(">(Stopped)</span>")) return "stopped"
    return null
}

// The hub's /hub2/appsList tree by app id: it drops RMUtils' post-delete ghosts and feeds the status
// fields. Name and disabled stay raw so a missing field reads as unknown; null means unreadable.
private Map _collectLiveApps() {
    def responseText
    try {
        responseText = hubInternalGet("/hub2/appsList")
    } catch (Exception e) {
        mcpLog("warn", "rm-interop", "_collectLiveApps: /hub2/appsList fetch failed (${e.message})")
        return null
    }
    if (!responseText) return null
    def parsed
    try {
        parsed = new groovy.json.JsonSlurper().parseText(responseText)
    } catch (Exception e) {
        mcpLog("warn", "rm-interop", "_collectLiveApps: /hub2/appsList parse failed (${e.message})")
        return null
    }
    if (!(parsed instanceof Map) || !(parsed.apps instanceof List)) {
        mcpLog("warn", "rm-interop", "_collectLiveApps: /hub2/appsList is missing its apps list")
        return null
    }
    def apps = [:]
    boolean complete = true
    def walk
    walk = { node ->
        // A leaf may carry "children": null; only a present non-List value is malformed.
        if (!(node instanceof Map) || (node.data != null && !(node.data instanceof Map))
                || (node.children != null && !(node.children instanceof List))) {
            complete = false
            return
        }
        def idVal = node.data?.id != null ? node.data.id : node.id
        if (idVal != null) {
            try {
                // Store the RAW disabled value (may be null when the key is absent) —
                // coercing to == true here would erase the "field missing" signal that
                // _rmAnnotateRuleStatus needs to classify the rule as per-entry unknown.
                if (!(idVal.toString() ==~ /[1-9][0-9]*/)) throw new IllegalArgumentException("Invalid app ID")
                apps.put(idVal as Integer, [name: node.data?.name, disabled: node.data?.disabled, type: node.data?.type])
            } catch (Exception ignored) { complete = false }
        } else if (node.data) {
            // An idless structural container is safe only when all its children can be read;
            // absent children are an empty container, not a malformed one.
            complete = false
        }
        (node?.children ?: []).each { walk(it) }
    }
    parsed.apps.each { walk(it) }
    if (!complete) mcpLog("warn", "rm-interop", "_collectLiveApps: /hub2/appsList contains malformed app nodes; absence cannot be established")
    return complete ? apps : null
}

// Normalize an atTime string to the form RM 5.1 expects:
// {@code YYYY-MM-DDTHH:mm:ss.SSS+HHMM} (millis + numeric zone offset,
// no colon in the offset, e.g. {@code 2026-04-28T03:00:00.000-0500}).
//
// <p>RM 5.1's {@code selectTriggers} page render throws
// {@code java.text.ParseException: Unparseable date: ...} and enters a
// broken state if the stored {@code atTime<N>} value lacks the millis
// fraction or the timezone offset. Any subsequent trigger-add then also
// fails because the page can't render. Normalizing here prevents the
// cascade.
//
// <p><b>Format choice has SEMANTIC consequences for trigger recurrence:</b>
// <ul>
// <li>HH:mm form -- trigger fires DAILY at that wall-clock time (every day).</li>
// <li>Any ISO datetime form -- trigger fires ONCE on the specific date.</li>
// </ul>
//
// <p>Accepted input forms:
// <ol>
// <li>{@code 17:00} or {@code 5:00} -- HH:mm form; passes through unchanged.
// RM 5.1 stores this verbatim and renders the trigger as
// "When time is HH:MM AM/PM" with daily recurrence. Verified live on
// firmware 2.4.4.156: rule with {@code atTime1="22:00"} fires every day
// at 10 PM (existing rule id 825 used as reference).</li>
// <li>{@code 2026-04-28T03:00:00.000-0500} -- explicit numeric offset; normalized
// to UTC equivalent (same instant: {@code 2026-04-28T08:00:00.000+0000}). One-shot dated.</li>
// <li>{@code 2026-04-28T03:00:00} -- no millis, no tz; hub local tz applied. One-shot dated.</li>
// <li>{@code 2026-04-28T03:00:00.000} -- millis present, no tz; hub local tz applied. One-shot dated.</li>
// <li>{@code 2026-04-28T03:00:00-0500} -- no millis, explicit offset; normalized to UTC. One-shot dated.</li>
// <li>{@code 2026-04-28T03:00:00Z} -- Zulu shorthand; normalized to +0000. One-shot dated.</li>
// <li>{@code 2026-04-28T03:00:00.000Z} -- Zulu with millis; normalized to +0000. One-shot dated.</li>
// </ol>
//
// <p>Throws {@code IllegalArgumentException} for input that does not
// match any of the above forms (ASCII-only message, echoes the bad value).
// Internal _rm helper -- not part of the tool surface.
String _rmNormalizeAtTime(String raw) {
    if (!raw) throw new IllegalArgumentException("addTrigger.atTime is required when time='A specific time'")

    // HH:mm form (no date, no seconds) -- RM 5.1 accepts this directly and treats
    // it as a DAILY-RECURRING trigger that fires every day at this wall-clock time.
    // Pass through unchanged so callers can opt into daily-recurring behavior.
    // Without this branch the daily-recurring path is unreachable: the parser
    // chain below requires a YYYY-MM-DD prefix, which forces all callers into
    // ONE-SHOT dated-trigger mode. Verified live on firmware 2.4.4.156: existing
    // rule id 825 stores atTime3="22:00" and fires daily at 10 PM.
    if (raw.matches(/\d{1,2}:\d{2}/)) {
        return raw
    }

    // Canonical RM form: yyyy-MM-dd'T'HH:mm:ss.SSSZ (Z = numeric offset like -0500)
    def canonicalFormat = "yyyy-MM-dd'T'HH:mm:ss.SSSZ"

    // Attempt each known input form. The output format is always canonical.
    // Order: most-specific first so the correct parser wins without ambiguity.
    def UTC = TimeZone.getTimeZone("UTC")
    def hubTz = location.timeZone
    if (!hubTz) {
        throw new IllegalStateException("_rmNormalizeAtTime: location.timeZone is null -- hub timezone is not configured. Set the hub timezone in Settings > Location and Modes before using time-based triggers.")
    }
    def parsers = [
        // Millis + explicit numeric offset (e.g. -0500 / +0000) -- normalize to UTC
        [fmt: "yyyy-MM-dd'T'HH:mm:ss.SSSZ",    tz: UTC],
        // Millis + Z suffix
        [fmt: "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",   tz: UTC],
        // No millis + explicit numeric offset -- normalize to UTC
        [fmt: "yyyy-MM-dd'T'HH:mm:ssZ",          tz: UTC],
        // No millis + Z suffix
        [fmt: "yyyy-MM-dd'T'HH:mm:ss'Z'",        tz: UTC],
        // Millis, no timezone -- use hub local tz
        [fmt: "yyyy-MM-dd'T'HH:mm:ss.SSS",       tz: hubTz],
        // No millis, no timezone -- use hub local tz
        [fmt: "yyyy-MM-dd'T'HH:mm:ss",           tz: hubTz],
    ]

    for (parser in parsers) {
        try {
            def sdf = new java.text.SimpleDateFormat(parser.fmt)
            sdf.setLenient(false)
            sdf.timeZone = parser.tz
            def parsed = sdf.parse(raw)
            // Reformat to canonical form in the same timezone used for parsing.
            // Explicit-offset inputs are normalized to UTC (same instant, +0000
            // representation). Hub-local inputs preserve the wall-clock time
            // with the hub's configured offset.
            def outSdf = new java.text.SimpleDateFormat(canonicalFormat)
            outSdf.timeZone = parser.tz
            return outSdf.format(parsed)
        } catch (java.text.ParseException ignored) {
            // try next parser
        }
    }

    throw new IllegalArgumentException(
        "addTrigger.atTime '${raw}' is not a recognized format. " +
        "Accepted forms: 'HH:mm' (e.g. '17:00' -- creates a DAILY-recurring trigger), " +
        "'YYYY-MM-DDTHH:mm:ss' (hub tz assumed -- creates a ONE-SHOT dated trigger), " +
        "'YYYY-MM-DDTHH:mm:ss.SSS' (hub tz assumed), " +
        "'YYYY-MM-DDTHH:mm:ssZ', 'YYYY-MM-DDTHH:mm:ss.SSSZ' (Zulu), " +
        "'YYYY-MM-DDTHH:mm:ss+HHMM' or 'YYYY-MM-DDTHH:mm:ss.SSS+HHMM' (explicit offset).")
}

// Single source of truth for the PRODUCTION-CODE set of
// `settingsSkipped[].reason` codes that are INFORMATIONAL (do NOT signal a
// genuine field-write failure). Consumed by both `_rmAddRequiredExpression`
// and `_rmAddAction` partial gates -- the shared `_rmWalkConditionReveal`
// walker emits the same sentinels on STPage and doActPage, so both callsites
// need an identical exemption list. Centralising it here means a future
// addition (a second informational reason code) lands in one place inside
// production code rather than requiring lockstep edits in two helpers.
//
// SCOPE CAVEAT: this list is the source of truth for production behavior
// ONLY. Doc surfaces that name reason codes literally (inline schema
// descriptions in this file's tool registrations, the inline hub_get_tool_guide
// content block, TOOL_GUIDE.md, etc.) still require manual sync when a new
// reason code is added -- no lint checks the reason codes named in doc surfaces.
//
// Genuinely-partial reason codes (`silent_rejection`, `offset_field_not_revealed`,
// `verification_fetch_failed`, `not_in_schema`, `api_unavailable`, etc.) are NOT
// in this list and continue to flip `partial`.
// Reason-code is the disambiguator -- doc contract at the inline hub_get_tool_guide
// content block calls this list out by name.
private List _rmInformationalSkippedReasons() {
    // useST_idempotent_noop: the Step-1 useST=true mainPage toggle is idempotent
    // (safe to re-write when an expression already exists). When the toggle is
    // already set the write does not advance the schema and _rmWriteSettingOnPage
    // tags it silent_rejection; that is cosmetic, not a lost-value degradation, so
    // it is re-tagged to this informational code and must NOT flip partial.
    //
    // state_change_comparator_ignored_explicit_value: a no-RHS state-change comparator
    // ('*changed*') was requested on an enum attribute ALONGSIDE an explicit
    // state/value. The value lands and the rule works as an equals-check, so the dropped
    // change-comparator intent is reported informationally, NOT as a partial degradation.
    //
    // device_list_committed_schema_unchanged: a device-picker field (its committed value is
    // a List of device IDs stored in the hub's deviceIdsForDeviceList side-structure, not in
    // settings[<key>]) is the LAST write of an action and reveals no further schema, so none
    // of _rmWriteSettingOnPage's four landing detectors fire and it tags silent_rejection --
    // even though the IDs committed. The re-tag is value-echo-gated (see
    // _rmReclassifyDeviceListSkips): it only applies when the requested IDs are actually
    // present in the committed echo, so a genuine device-write FAILURE (IDs absent) keeps the
    // silent_rejection reason and still flips partial.
    return ["reveal_fallback_to_existing_field", "useST_idempotent_noop",
            "state_change_comparator_ignored_explicit_value",
            "device_list_committed_schema_unchanged"]
}

// Authoritative predicate for the "device-list field" class: a classic-app setting whose
// committed value is a List of device IDs the hub stores in a deviceIdsForDeviceList (or
// deviceList id->label) side-structure, leaving settings[<key>] null. This is the echo
// STRUCTURE, not a field-name allow-list -- it recognises every device picker (switch, lock,
// shade, thermostat, fan, ...) without enumerating each field name, and it is the single
// source of truth _rmReclassifyDeviceListSkips uses to reclassify a cosmetic device-write
// silent_rejection. Takes a status appSettings entry (the {name, value, deviceIdsForDeviceList,
// deviceList} shape from statusJson), not a config input descriptor.
private boolean _rmStatusEntryIsDeviceList(Object entry) {
    if (!(entry instanceof Map)) return false
    def e = entry as Map
    return (e.deviceIdsForDeviceList instanceof List) || (e.deviceList instanceof Map)
}

// The committed device IDs for a device-list status entry, as a Set of Strings. Reads
// a non-empty deviceIdsForDeviceList (the authoritative id list); when that is empty or absent
// it falls back to the deviceList map's keys (id->label) -- the two shapes are treated
// interchangeably, matching _rmStatusEntryIsDeviceList. Empty Set when no IDs are committed --
// which, for a requested non-empty write, means the write did NOT land (keeps the skip partial).
private Set _rmStatusEntryDeviceIds(Object entry) {
    if (!(entry instanceof Map)) return [] as Set
    def e = entry as Map
    def direct = (e.deviceIdsForDeviceList instanceof List) ? (((e.deviceIdsForDeviceList as List).collect { it?.toString() }.findAll { it }) as Set) : ([] as Set)
    if (direct) return direct
    if (e.deviceList instanceof Map) {
        return ((e.deviceList as Map).keySet().collect { it?.toString() }.findAll { it }) as Set
    }
    return direct
}

// Reclassify cosmetic device-list-write silent_rejections in `skipped` to the informational
// device_list_committed_schema_unchanged reason so they do not flip partial -- but ONLY when
// the requested IDs actually committed (value-echo-gated against statusJson). A device-picker
// field is often an action's LAST write and reveals no further schema, so _rmWriteSettingOnPage
// cannot see it land and tags silent_rejection even on success. This mutates the entries of
// `skipped` in place. A skip is left untouched (stays partial) when: it is not a
// silent_rejection, its value is not a device-id List, its status entry is absent or is not a
// device-list entry, or the requested IDs are not all present in the committed echo (a genuine
// partial/failed device write). One statusJson fetch, made only when a candidate skip exists;
// a fetch failure leaves every skip untouched (conservative -- unverifiable stays partial).
// Only a device-list picker that is an action's LAST write (revealing no further schema) earns
// the cosmetic silent_rejection this re-tags; a condition/trigger device picker advances the
// schema (a later field reveals after the pick), so it never gets the cosmetic skip.
private void _rmReclassifyDeviceListSkips(Integer appId, List skipped) {
    if (!skipped) return
    def candidates = skipped.findAll {
        it instanceof Map && it.reason == "silent_rejection" && it.value instanceof List
    }
    if (candidates.isEmpty()) return
    def status
    try {
        status = _rmFetchStatusJson(appId)
    } catch (Exception statusExc) {
        // Null-guard the identity: an exception with no message renders "(null)" from a bare
        // ${e.message}; ?: falls back to toString(), which carries the class name.
        mcpLog("warn", "rm-native", "_rmReclassifyDeviceListSkips: statusJson fetch for app ${appId} failed (${statusExc.message ?: statusExc}) -- device-list silent_rejections left as-is (stay partial)")
        return
    }
    // Defensive: a malformed appSettings shape (a scalar where a List/Map is expected, etc.)
    // must never bubble an exception up through _rmAddAction's partial computation. On any
    // post-fetch anomaly, log and leave every skip as-is (stays partial -- the conservative side).
    try {
        def byName = (status?.appSettings ?: []).findAll { it instanceof Map && it.name != null }
            .collectEntries { [(it.name.toString()): it] }
        candidates.each { sk ->
            def entry = byName[(sk.key?.toString())]
            if (!_rmStatusEntryIsDeviceList(entry)) return
            def requested = ((sk.value as List).collect { it?.toString() }.findAll { it }) as Set
            if (requested.isEmpty()) return
            def committed = _rmStatusEntryDeviceIds(entry)
            if (committed.containsAll(requested)) {
                sk.reason = "device_list_committed_schema_unchanged"
                sk.committedDeviceIds = committed.toList().sort()
            }
        }
    } catch (Exception reclassExc) {
        mcpLog("warn", "rm-native", "_rmReclassifyDeviceListSkips: post-fetch reclassification for app ${appId} failed (${reclassExc.message ?: reclassExc}) -- device-list silent_rejections left as-is (stay partial)")
    }
}

// Validate that every device ID in `ids` resolves on the hub. RM 5.1
// silently stores `{<bogusId>: null}` for unknown IDs in any device
// setting (rDev_<N>, tDev_<N>, switch.<N>, etc.) -- the write returns
// 200 but the rule renders with placeholder text and never fires.
// Catch this before writing so callers see a clear error instead of a
// phantom in-flight rule. Throws IllegalArgumentException on the
// first missing id with a message keyed by `label` (e.g.
// "addTrigger.deviceIds[2]"). Idempotent for List<null>/empty.
private void _rmValidateDeviceIdsExist(String label, Object ids) {
    if (!(ids instanceof List)) return
    ids.each { id ->
        def idStr = id?.toString()
        if (!idStr) return
        // One read per device per execution; the capability check's device list already proves the
        // ids it holds.
        if (_memoDeviceExists(idStr)) return
        def listed = _memoDeviceList()
        if (listed instanceof Map && listed.get(idStr) != null) {
            _memoNoteDeviceExists(idStr)
            return
        }
        def exists
        boolean read = false
        try {
            def resp = hubInternalGet("/device/fullJson/${idStr}")
            exists = (resp != null && resp.toString().length() > 0)
            read = true
        } catch (Exception fetchExc) {
            // Distinguish "device truly missing" (404 / empty body) from
            // "validator unavailable" (network blip, auth-cookie expiry,
            // transient hub error). For non-404 failures, log and SKIP
            // validation rather than synthesizing a misleading
            // "device does not exist" error that steers the user toward
            // the wrong root cause.
            def msg = fetchExc.message?.toString() ?: ""
            if (msg.contains("404") || msg.toLowerCase().contains("not found")) {
                exists = false
            } else {
                mcpLog("warn", "rm-native", "_rmValidateDeviceIdsExist: ${label} validation fetch for id=${idStr} failed transiently (${msg}) -- skipping validation; the underlying hub call may have errored independent of whether the device exists")
                exists = true  // assume valid on transient failure; the actual write will fail loudly if the ID is genuinely bogus
            }
        }
        if (!exists) {
            // The not-touched sentinel is correct only where this validator runs before the
            // operation's first hub write; it is called pre-write on every current path, so the
            // refusal keeps the edit-path restoreHint accurate. (Batch callers catch per item and
            // never consult the sentinel for restoreHint.)
            throw new IllegalArgumentException("${label} contains device ID '${idStr}' which does not exist on the hub. Verify the device ID via hub_list_devices. RM is not touched.")
        }
        if (read) _memoNoteDeviceExists(idStr)
    }
}

// Action capabilities whose primary target is another RM rule -- their ruleIds
// must resolve to an existing Rule Machine rule. Single source of truth for the
// pre-write existence guard's capability gate and the bulk-path "does this batch
// target a rule?" scan; keep it aligned with the capability branches in
// _rmAddAction that write a rule field (ruleAct / stopAct / pauseRule / privateT).
private List _rmRuleTargetCaps() { ["privateBoolean", "runRule", "cancelTimers", "pauseRule"] }

// True when an action spec's capability targets another RM rule (see _rmRuleTargetCaps).
private boolean _rmSpecTargetsRule(Object spec) {
    if (!(spec instanceof Map)) return false
    return ((spec.capability?.toString()?.trim()) in _rmRuleTargetCaps())
}

// True when any spec in a bulk action list targets another RM rule. Lets a bulk
// entry point resolve the valid-rule-id set once (and only when it is actually
// needed) and thread it to every _rmAddAction in the batch.
private boolean _rmSpecListTargetsRule(List specs) {
    return (specs ?: []).any { _rmSpecTargetsRule(it) }
}

// Normalize the rule id(s) written into a rule-targeting action field to the
// integer-canonical form; with allowThisRule, RM's "this rule" target "*" passes through as-is. A decimal-form Number (22624.0) would otherwise bake
// literally into the rule field and RM then mishandles it -- the same reason the
// trigger paths canonicalize a device id to "72" rather than "72.0". Accepts a
// scalar or a list; returns a list (RM's rule pickers are multi-select).
//
// Uses the shared _rmCoerceRuleId so a decimal-form id ("555.0") the guard accepted
// normalizes here too. A non-integer-valued id (fractional 555.9, or non-numeric "abc")
// is rejected fail-loud rather than passed on: only the cannot-verify skip path (RM not
// installed or app-tree unreadable) reaches here without the guard having validated the
// id, and there normalizeRuleId would truncate a fractional Number to a DIFFERENT existing
// rule via toInteger() -- the exact silent corruption _rmCoerceRuleId exists to prevent.
// Throwing keeps the guard and the write on the same integer-valued contract on every path.
private List _rmNormalizeRuleIdsForWrite(Object ids, boolean allowThisRule = false) {
    def idList = (ids instanceof List) ? ids : (ids != null ? [ids] : [])
    return idList.collect { id ->
        if (_rmIsThisRuleTarget(id)) {
            if (allowThisRule) return "*"
            throw new IllegalArgumentException(_rmThisRuleUnsupportedMessage("rule"))
        }
        def c = _rmCoerceRuleId(id)
        if (c == null) {
            throw new IllegalArgumentException("rule target id '${id}' is not an integer-valued rule id. Use hub_list_rules to find valid rule ids. RM is not touched.")
        }
        return c
    }
}

// RM stores a rule-targeting action aimed at its own rule as "*", alone or mixed with
// real ids (privateT.<N> = ["*","1809"]). Seen live only on Set Private Boolean.
private boolean _rmIsThisRuleTarget(Object id) {
    return id?.toString()?.trim() == "*"
}

private boolean _rmTargetAllowsThisRule(Object capability) {
    return capability?.toString() == "privateBoolean"
}

private String _rmThisRuleUnsupportedMessage(Object label) {
    return "${label} target '*' is Rule Machine's \"this rule\" target. It is supported only for privateBoolean actions; for this action use the rule's own numeric id (hub_list_rules) or edit it in the RM wizard. RM is not touched.".toString()
}

// Coerce a rule-target id to an integer-valued Integer, or null when it is not
// integer-valued. Accepts 555, 555.0, "555", "555.0" (a decimal-form string can arrive
// from JSON coercion) and rejects a fractional id (555.9 / "555.9") rather than silently
// truncating it to a different existing rule, plus non-numeric input ("abc"). The Number
// branch compares doubleValue against longValue (avoids BigDecimal-from-double rounding on
// an already-numeric id); the String branch parses via BigDecimal (integer-valued when
// stripTrailingZeros leaves scale <= 0), since toInteger() throws on any decimal point.
private Integer _rmCoerceRuleId(Object id) {
    try {
        if (id instanceof Number) {
            return (id.doubleValue() == id.longValue()) ? id.intValue() : null
        }
        def s = id?.toString()?.trim()
        if (!s) return null
        def bd = new BigDecimal(s)   // throws on non-numeric -> caught -> null
        return (bd.stripTrailingZeros().scale() <= 0) ? bd.intValue() : null
    } catch (Exception ignored) { return null }
}

// Fail loud, pre-write, when a rule-targeting action names a rule id that does not
// resolve to an existing Rule Machine rule. RM 5.1 stores an unknown id verbatim
// into the action's rule field and bakes a dangling reference that never fires and
// renders broken, with no error at write time. Resolving each id against the
// existence-safe rule-id set (the RMUtils rule list confirmed against /hub2/appsList)
// up front keeps the refusal pre-write, matching the fail-loud style of the other
// pre-write guards.
//
// validRuleIds lets a bulk caller resolve the set once and thread it to every item
// (avoids one fetch per action); a single-action caller passes null and the set is
// resolved lazily here. When the set cannot be determined -- RMUtils absent or the
// app-tree cross-check failed, both surfaced as null -- treat it as "cannot verify"
// and let the write proceed (a genuinely bogus id still bakes broken) rather than
// synthesize a misleading "rule does not exist" on a hub blip; mirrors the transient-
// skip stance of _rmValidateDeviceIdsExist. An EMPTY (non-null) set is NOT a blip --
// the rule list was read successfully and has zero valid rules -- so every rule target
// is genuinely invalid and is rejected fail-loud (only null, cannot-verify, skips).
//
// This guard wraps a bare scalar into a list (a caller may pass ruleIds as a single
// integer), BECAUSE ruleIds is not contractually required to be an array here -- the
// sibling _rmValidateDeviceIdsExist instead SKIPS a non-List input, because a bare
// integer deviceId is the very bug it exists to catch (RM stores {N:null} for it), so
// silently ignoring the scalar is the safe stance there. For a rule target the scalar
// is a legitimate single-rule reference, so it is validated rather than skipped.
private void _rmValidateRuleTargetExists(String label, Object ids, Set validRuleIds = null) {
    def idList = (ids instanceof List) ? ids : (ids != null ? [ids] : [])
    if (!idList) return
    // Shape checks need no rule list, so they run before the cannot-verify skip below.
    idList.each { id ->
        if (_rmIsThisRuleTarget(id)) {
            if (!_rmTargetAllowsThisRule(label)) throw new IllegalArgumentException(_rmThisRuleUnsupportedMessage(label))
        } else if (_rmCoerceRuleId(id) == null) {
            throw new IllegalArgumentException("${label} target rule id '${id}' is not a valid numeric rule id. Use hub_list_rules to find valid rule ids. RM is not touched.")
        }
    }
    if (idList.every { _rmIsThisRuleTarget(it) }) return
    def liveIds = (validRuleIds != null) ? validRuleIds : _rmValidRuleIds()
    if (liveIds == null) {
        // Cannot verify (RMUtils absent / tree unreadable) -> skip; an empty set (verified
        // zero rules) falls through and rejects every target. Log the skip like the sibling
        // device guard so a target that later bakes broken has a diagnostic trail.
        mcpLog("warn", "rm-native", "_rmValidateRuleTargetExists: ${label} rule-target existence check skipped -- the RM rule list was unverifiable (RMUtils lookup or /hub2/appsList read failed); a bogus rule id could bake a broken reference")
        return
    }
    idList.each { id ->
        if (_rmIsThisRuleTarget(id)) return
        def idInt = _rmCoerceRuleId(id)
        if (!liveIds.contains(idInt)) {
            throw new IllegalArgumentException("${label} target rule id '${id}' does not exist on the hub. Use hub_list_rules to find valid rule ids. RM is not touched.")
        }
    }
}

// Map common ASCII comparator aliases to the Unicode glyphs RM 5.1's
// comparator enum accepts. Verified live 2026-05-17 on firmware
// 2.5.0.135: writing "!=" silently rejects because the enum option set
// is the Unicode "≠". Pass-through for unrecognized values so callers
// who already pass the Unicode glyphs continue to work.
// Internal _rm helper -- not part of the tool surface.
String _rmNormalizeComparator(Object raw) {
    if (raw == null) return null
    def s = raw.toString()
    if (s == "!=" || s == "<>") return "≠"
    if (s == "==") return "="
    // Bare 'contains' is never a valid RM comparator; the wizard enum offers the
    // asterisk-wrapped '*contains*' (substring match). Accept the intuitive bare
    // form (case-insensitive) so a caller who guesses it does not silently build a
    // broken rule -- an already-wrapped '*contains*' falls through unchanged.
    if (s.equalsIgnoreCase("contains")) return "*contains*"
    return s
}

// Single source of truth for the Hubitat Safety Monitor action command set (actType
// lockActs, actSubType getSetHSM, field alarm.<N>). Bare tokens exactly as RM's alarm
// enum offers them -- NOT the vue hsmArm* tokens. The handler, the validation error, and
// the discover schema must all agree on this list. No 'armAll': use 'armRules'.
private List _rmHsmCommands() {
    ["armAway", "armHome", "armNight", "disarm", "rearm", "disarmAll", "armRules", "cancelAlerts"]
}

// Internal _rm helper -- not part of the tool surface.
// Single source of truth for the RM no-RHS state-change comparator family. Each entry
// is a substring marker that identifies a comparator selecting a change EVENT rather
// than a value test, so it has no right-hand value to place in a value picker. The
// predicate below is a FAMILY gate ("is this a no-RHS comparator at all"); routing an
// option to a specific request must additionally match the exact token (see
// _rmComparatorTokensMatch). If RM adds a new no-RHS token, extend THIS list in
// lockstep -- the enum branches across the four wizard surfaces consult the predicate,
// not a private copy of the markers. Method-constant, not `static final` (script-scope
// rejects the field in the Hubitat sandbox -- see hubResponseCapBytes).
private List<String> _rmRhsOptionalComparatorMarkers() { ["changed"] }

// A comparator with no right-hand value ('*changed*'). Token-anchored, so 'unchanged' is not one.
boolean _rmComparatorIsRhsOptional(Object rawComparator) {
    def token = _rmComparatorToken(rawComparator)
    if (token == null) return false
    return _rmRhsOptionalComparatorMarkers().any { m -> token == m || token.startsWith(m + " ") }
}

// A comparator reduced to its core: '*...*' wrapping and outer spaces removed, case-folded.
private String _rmComparatorToken(Object raw) {
    def s = raw?.toString()?.trim()?.toLowerCase()
    if (!s) return null
    while (s.startsWith("*")) s = s.substring(1)
    while (s.endsWith("*")) s = s.substring(0, s.length() - 1)
    s = s.trim()
    // Re-null an emptied result: an all-asterisk token ('*' / '**') strips to "",
    // which must NOT equality-match another emptied token (empty-vs-empty false match).
    return s ?: null
}

// Exact-token match between a requested comparator and a picker option; 'unchanged' does not match
// '*changed*'.
private boolean _rmComparatorTokensMatch(Object requested, Object candidate) {
    return _rmComparatorTokenMatchesOption(_rmComparatorToken(requested), candidate)
}

// Internal _rm helper -- not part of the tool surface.
// Equality of an ALREADY-NORMALIZED requested token against a candidate picker option.
// Route sites normalize the requested comparator ONCE (hoisted out of the per-candidate
// .find) and call this; _rmComparatorTokensMatch is the un-hoisted convenience form.
private boolean _rmComparatorTokenMatchesOption(String requestedToken, Object candidate) {
    if (requestedToken == null) return false
    def b = _rmComparatorToken(candidate)
    return b != null && requestedToken == b
}

// A picker's options as value strings, from any of the hub's shapes: bare strings, {value, text}
// Maps, single-entry {value: label} Maps, or a {value: label} container.
List _rmReadPickerOptionStrings(Object pickerInput) {
    def opts = pickerInput?.options
    if (opts == null) return []
    if (opts instanceof Map) {
        // Map CONTAINER: the option VALUE is the key (RM's enum value), the entry value
        // is the display label. Read the keys.
        return opts.keySet().collect { it?.toString() }.findAll { it }
    }
    if (opts instanceof CharSequence) {
        // Scalar options (a lone String): treat as a single option, never char-iterate.
        def s = opts.toString()
        return s ? [s] : []
    }
    // List items are {value, text} Maps, single-entry {value: label} Maps (the hub's usual enum
    // shape), or bare strings.
    return (opts.collect { o ->
        if (!(o instanceof Map)) return o?.toString()
        Map m = o as Map
        if (m.containsKey("value")) return m.get("value")?.toString()
        def only = m.size() == 1 ? m.keySet().iterator().next()?.toString() : null
        only == "text" ? null : only
    }).findAll { it }
}

private List<String> _rmUnrepresentedComparatorReasons() {
    ["comparator_not_representable_for_enum_attribute", "comparator_not_offered"]
}

// The repair hint for a comparator RM cannot take: one the condition's picker does not offer, or
// a change comparator on an enum-valued Custom Attribute. Shared by the trigger, action and
// Required Expression result paths. ASCII-only.
private String _rmUnrepresentedComparatorHint(Map sk) {
    def cmp = sk?.value?.toString()
    if (sk?.reason == "comparator_not_offered") {
        return "Comparator '${cmp}' is not offered for a ${sk.capability ?: 'device-state'} condition; its fields are ${(sk.available as List)?.join(', ')}. Match a value with state; not:true negates it.".toString()
    }
    def attrName = sk?.attribute?.toString()
    return "Comparator '${cmp}' has no slot on the enum attribute '${attrName}': Rule Machine shows only a value picker for it. Use state, or the device's own capability (e.g. capability:'Switch')."
}

// The keys an addTrigger spec may carry; a leftover key is named in the refusal.
private List<String> _rmRecognizedTriggerKeys() {
    ["discover", "capability", "deviceIds", "condition", "conditional",
     "periodic", "rawSettings", "variable", "comparator", "attribute", "buttonNumber",
     "modeIds", "state", "value", "time", "atTime", "offset", "allOfThese", "andStays"]
}

// Stems of a change comparator, used to catch one passed as a state.
private List<String> _rmStateChangeTokenStems() { _rmRhsOptionalComparatorMarkers() + ["increased", "decreased"] }

// True when a state or value looks like a change comparator ('changed', 'increased', ...).
private boolean _rmLooksLikeStateChangeToken(Object raw) {
    def s = raw?.toString()?.toLowerCase()
    if (!s) return false
    // Fold BOTH sides: the stems come from _rmRhsOptionalComparatorMarkers(), so a
    // future marker added in mixed case must not silently defeat the case-folded match.
    // Lower-case the stems once, not per element of the any-scan.
    def stems = _rmStateChangeTokenStems().collect { it.toLowerCase() }
    return stems.any { s.contains(it) }
}


// "Did you mean" for an unknown capability, drawn from the list the caller was refused against.
// Short names (under 4 chars) never match as substrings.
private String _rmSuggestTriggerCapability(String cap, Collection liveOptions = null) {
    def want = cap?.trim()?.toLowerCase()
    if (!want) return null
    def live = (liveOptions ?: []).collect { it?.toString() }.findAll { it }
    def schemaNames = (_rmCondTables().trigger as List) + (_rmCondTables().condition as List)
    def candidates = (live ? live : schemaNames).findAll { it }.unique()
    def exact = candidates.find { it.toLowerCase() == want }
    if (exact) return exact
    return candidates.find { def n = it.toLowerCase(); (want.length() >= 4 && n.contains(want)) || (n.length() >= 4 && want.contains(n)) }
}

// Whether a change token passed as state is refused: every family except those whose value can
// legitimately hold one (the hub-state, variable, custom-attribute, button and time families).
private boolean _rmStateChangeGuardApplies(String cap) {
    !(_rmCondFamily(cap) in ["hub-state", "variable", "custom-attribute", "time", "button"])
}

// What a periodic spec is missing for its frequency (else the row renders "null"), or null.
// A rawSettings map skips the check.
private String _rmPeriodicShapeError(String freq, Map per) {
    if (per?.rawSettings instanceof Map && !(per.rawSettings as Map).isEmpty()) return null
    switch (freq) {
        case "Seconds":
            if (per.everyN == null) return "Periodic Seconds requires everyN (the count enum)."
            break
        case "Minutes":
            if (per.everyN == null && per.selectedMinutes == null) return "Periodic Minutes requires everyN ('every N minutes') or selectedMinutes ('at these minutes')."
            break
        case "Hourly":
            if (per.everyN == null && per.selectedHours == null) return "Periodic Hourly requires everyN ('every N hours') or selectedHours ('at these hours')."
            break
        case "Daily":
            if (per.everyN == null && per.weekdaysOnly != true && per.selectedDaysOfMonth == null) return "Periodic Daily requires everyN ('every N days'), weekdaysOnly:true, or selectedDaysOfMonth."
            break
        case "Weekly":
            if (per.daysOfWeek == null) return "Periodic Weekly requires daysOfWeek (list of weekday names, e.g. ['Monday','Friday'])."
            break
        case "Monthly":
            // Mutual-exclusion of dayOfMonth+weekOfMonth is rejected earlier, so here each mode's
            // full field set is required: nth-weekday needs dayOfWeek + everyNMonths; by-day needs
            // everyNMonths; neither present means no mode was selected at all.
            if (per.weekOfMonth != null) {
                def missing = []
                if (per.dayOfWeek == null) missing << "dayOfWeek"
                if (per.everyNMonths == null) missing << "everyNMonths"
                if (missing) return "Periodic Monthly nth-weekday (weekOfMonth set) also requires ${missing.join(' and ')}."
            } else if (per.dayOfMonth != null) {
                if (per.everyNMonths == null) return "Periodic Monthly by-day (dayOfMonth set) also requires everyNMonths."
            } else {
                return "Periodic Monthly requires either dayOfMonth+everyNMonths (by-day) or weekOfMonth+dayOfWeek+everyNMonths (nth-weekday)."
            }
            break
        case "Yearly":
            def missing = []
            if (per.weekOfMonth == null) missing << "weekOfMonth"
            if (per.dayOfWeek == null) missing << "dayOfWeek"
            if (per.months == null) missing << "months"
            if (missing) return "Periodic Yearly (always nth-weekday) requires ${missing.join(', ')} (e.g. weekOfMonth:'Second', dayOfWeek:'Monday', months:'December')."
            break
        case "Cron String":
            if (per.cronString == null) return "Periodic Cron String requires cronString (the cron expression, e.g. '0 0 12 * * ?')."
            break
        default:
            // Unrecognized frequency -- the periodic write block's frequency check handles it.
            return null
    }
    return null
}

// Argument-only refusal seam used before MRTR allocates requestState. Keep this
// allowlist narrower than the full leaf validation: every check here is decided
// solely from the supplied trigger map and must not read or mutate hub state.
private void _rmValidateRoundZeroTriggerSpec(Map triggerSpec) {
    if (!(triggerSpec instanceof Map) || _rmIsDiscoverSpec(triggerSpec)) return
    _rmCheckCondSpec(triggerSpec, "trigger", "addTrigger")
    if (triggerSpec.condition instanceof Map) _rmCheckCondSpec(triggerSpec.condition as Map, "condition", "addTrigger.condition")
    def cap = triggerSpec.capability?.toString()?.trim()
    if (!cap) return

    def effState = triggerSpec.state != null ? triggerSpec.state : triggerSpec.value
    def offendingField = triggerSpec.state != null ? "state" : "value"
    if (triggerSpec.comparator == null && _rmLooksLikeStateChangeToken(effState) &&
            _rmStateChangeGuardApplies(cap)) {
        throw new IllegalArgumentException("${offendingField}:'${effState}' is a change, not a state: use comparator:'*changed*'. RM is not touched.")
    }

    // time is the trigger's mode picker; the UI offers only these options, the clock rides in atTime.
    if (cap.equalsIgnoreCase("Certain Time (and optional date)") && triggerSpec.time != null &&
            !(triggerSpec.time?.toString() in ["A specific time", "Sunrise", "Sunset"])) {
        throw new IllegalArgumentException("addTrigger.time must be 'A specific time', 'Sunrise' or 'Sunset' (got '${triggerSpec.time}'); the clock time goes in atTime. RM is not touched.")
    }

    if (cap.equalsIgnoreCase("Periodic Schedule") && !(triggerSpec.periodic instanceof Map)) {
        def strayKeys = triggerSpec.keySet().findAll {
            !(it?.toString() in _rmRecognizedTriggerKeys())
        }
        def detail
        if (triggerSpec.containsKey("periodic")) {
            detail = " periodic was supplied but is not a Map of schedule fields."
        } else if (strayKeys) {
            detail = " Received unrecognized key${strayKeys.size() == 1 ? '' : 's'}: ${strayKeys}."
        } else {
            detail = ""
        }
        throw new IllegalArgumentException("A Periodic Schedule trigger needs periodic:{frequency, ...}.${detail} See discover kind:'trigger', capability:'Periodic Schedule'. RM is not touched.")
    }
}

// Pure periodic fields that historically run after device-ID prevalidation.
// Round zero invokes these only when no earlier dynamic validator can apply.
private void _rmValidateRoundZeroPeriodicSpec(Map triggerSpec) {
    def cap = triggerSpec?.capability?.toString()?.trim()
    if (!cap) return
    if (cap.equalsIgnoreCase("Periodic Schedule") && triggerSpec.periodic instanceof Map) {
        def periodic = triggerSpec.periodic as Map
        def frequency = periodic.frequency?.toString()
        if (!frequency) {
            throw new IllegalArgumentException("periodic.frequency is required: Seconds, Minutes, Hourly, Daily, Weekly, Monthly, Yearly or 'Cron String'. RM is not touched.")
        }
        // A key the walker never reads would be dropped with no trace (e.g. time for startingTime).
        def periodicKeys = ["frequency", "everyN", "startingTime", "weekdaysOnly", "selectedHours", "selectedMinutes",
                            "selectedDaysOfMonth", "daysOfWeek", "dayOfWeek", "dayOfMonth", "everyNMonths", "months",
                            "weekOfMonth", "minutesOffset", "cronString", "rawSettings"]
        def unknownKeys = periodic.keySet().collect { it?.toString() }.findAll { !(it in periodicKeys) }
        if (unknownKeys) {
            throw new IllegalArgumentException("Periodic Schedule: unknown periodic key(s) ${unknownKeys}; accepted keys are ${periodicKeys}. Pass {discover:true} for the full periodic field schema. RM is not touched.")
        }
        if ((frequency == "Seconds" || frequency == "Minutes") && periodic.everyN != null) {
            def allowedCounts = [1, 2, 3, 4, 5, 6, 10, 12, 15, 20, 30]
            def requestedCount = null
            try { requestedCount = periodic.everyN as Integer }
            catch (Exception ignored) { requestedCount = null }
            if (requestedCount == null || !(requestedCount in allowedCounts)) {
                throw new IllegalArgumentException("Periodic ${frequency} everyN must be one of ${allowedCounts} (RM restricts the count to this enum); got '${periodic.everyN}'. RM is not touched.")
            }
        }
        if (frequency == "Monthly" && periodic.dayOfMonth != null &&
                periodic.weekOfMonth != null) {
            throw new IllegalArgumentException("Periodic Monthly takes dayOfMonth or weekOfMonth, not both. RM is not touched.")
        }
        def shapeErr = _rmPeriodicShapeError(frequency, periodic)
        if (shapeErr) {
            throw new IllegalArgumentException("${shapeErr} Pass {discover:true} for the full periodic field schema. RM is not touched.")
        }
    }
}

// Pure action checks in their established order. Structural checks and
// picker/existence checks need live rule state and remain inside the worker.
private void _rmValidateRoundZeroActionSpec(Map actionSpec) {
    if (!(actionSpec instanceof Map) || _rmIsDiscoverSpec(actionSpec)) return
    def cap = _rmCanonicalActionCapability(actionSpec)
    if (!cap) return
    _rmRejectUnwalkableExpressionConditions(actionSpec)

    def capLc = cap.toLowerCase()
    def expressionBearingCaps = ["ifthen", "elseif", "repeatwhile", "waitexpression"]
    if (capLc in expressionBearingCaps && actionSpec.conditions != null &&
            !(actionSpec.expression instanceof Map)) {
        throw new IllegalArgumentException("${cap} action takes expression:{conditions:[...], operator|operators}, not a top-level conditions array. Wrap them: expression:{conditions:[...], operator:'AND'}. Pass {discover:true} for the expression shape. RM is not touched.")
    }

    def action = actionSpec.action?.toString()?.trim()
    if (action == null && actionSpec.state != null &&
            _rmActionCapUsesActionVerb(cap)) {
        throw new IllegalArgumentException("${cap} action uses action: (not state:) to select the operation -- e.g. addAction(capability:'${cap}', action:'${actionSpec.state}', ...). Pass {discover:true} for this capability's action list. RM is not touched.")
    }
}

private Map _rmAddTrigger(Integer appId, Map triggerSpec) {
    // The body runs as a closure so its call sites live in the closure's own class: the app class is
    // near the JVM 65,535 constant-pool limit. Behaviour is unchanged.
    def editorOpen = [false]
    def builtCond = [null]
    def rhsOverride = null   // a compareToDevice offset: the trigger's right-hand value, kept out of the caller's spec
    def _impl = {
    if (!(triggerSpec instanceof Map)) throw new IllegalArgumentException("addTrigger requires a Map spec. RM is not touched.")
    // Discover mode -- return static schema without touching the hub.
    // No capability field required; no Write master gate; no backup.
    if (_rmIsDiscoverSpec(triggerSpec)) {
        def sel = triggerSpec.discover instanceof Map ? triggerSpec.discover as Map : [:]
        return _rmCondDiscover(sel.kind?.toString() == "condition" ? "condition" : "trigger", sel.capability)
    }
    _rmValidateRoundZeroTriggerSpec(triggerSpec)
    def cap = triggerSpec.capability?.toString()?.trim()
    if (!cap) throw new IllegalArgumentException("addTrigger.capability is required. Common values: Switch, Motion, Contact, Time, Periodic Schedule, Mode, Custom Attribute. Pass {discover: true} to get the full structured schema. RM is not touched.")

    // Pre-validate device IDs exist — RM 5.1 silently stores
    // {<bogusId>: null} in tDev_<N> if the ID doesn't resolve, and the
    // trigger renders as "Broken Trigger" with no event subscriptions.
    _rmValidateDeviceIdsExist("addTrigger.deviceIds", triggerSpec.deviceIds)
    _rmRequireConditionDeviceCapability("addTrigger.deviceIds", triggerSpec)
    if (triggerSpec.condition instanceof Map) {
        // Lift a singular condition deviceId into deviceIds before the device check reads it.
        def cm = triggerSpec.condition as Map
        if (cm.deviceIds == null && cm.deviceId != null) {
            cm.deviceIds = [cm.deviceId]
        }
        _rmValidateDeviceIdsExist("addTrigger.condition.deviceIds", cm.deviceIds)
        _rmRequireConditionDeviceCapability("addTrigger.condition.deviceIds", cm)
        if (cm.compareToDevice instanceof Map) _rmValidateDeviceIdsExist("addTrigger.condition.compareToDevice.deviceId", [(cm.compareToDevice as Map).deviceId])
    }
    if (triggerSpec.compareToDevice instanceof Map) {
        _rmValidateDeviceIdsExist("addTrigger.compareToDevice.deviceId", [(triggerSpec.compareToDevice as Map).deviceId])
    }

    _rmValidateRoundZeroPeriodicSpec(triggerSpec)
    // Preserve the legacy valid-path normalization without making the pure
    // round-zero validator mutate caller arguments.
    if (cap.equalsIgnoreCase("Periodic Schedule") && triggerSpec.periodic instanceof Map) {
        def periodic = triggerSpec.periodic as Map
        def frequency = periodic.frequency?.toString()
        if ((frequency == "Seconds" || frequency == "Minutes") && periodic.everyN != null) {
            periodic.everyN = periodic.everyN as Integer
        }
    }

    // Committed trigger indices before the editor opens; the new row's index comes from the page,
    // since state.moreCond can drift.
    def existing = _rmCollectTriggerIndices(appId)

    // Open the trigger editor (state.moreCond=true).
    _rmClickAppButton(appId, "true", "moreCond", "selectTriggers")
    editorOpen[0] = true

    // A conditional trigger builds its condition first (one index), then the trigger at the next
    // index, bound with condTrig.<n>.
    def conditionSpec = (triggerSpec.condition instanceof Map) ? (triggerSpec.condition as Map) : null
    def conditionId = null
    def applied = []
    def skipped = []
    def idx = null
    if (conditionSpec) {
        // The in-flight trigger's slot is RM's counter, which a cancelled edit leaves advanced. The
        // condition is built on that slot and RM moves the trigger to the next one.
        idx = _rmInFlightTriggerIdx(appId, existing)
        conditionId = _rmBuildCondition(appId, idx, conditionSpec, applied, skipped)
        builtCond[0] = conditionId
        // isCondTrig/condTrig for the trigger appear only after its tCapab and fields are written, so they
        // are written at the end.
        idx = idx + 1
    }

    // Match the capability to RM's picker spelling; a value outside its options is dropped silently
    // and the row would commit broken.
    def capPageConfig = _rmFetchConfigJson(appId, "selectTriggers")
    def allCapInputs = (capPageConfig?.configPage?.sections ?: []).collectMany { it?.input ?: [] }
    if (idx == null) {
        // The in-flight trigger is the largest tCapab<n> not already committed (moreCond can drift).
        def candidateIdxs = []
        allCapInputs.each { inp ->
            def n = inp?.name?.toString()
            if (n) {
                def m = (n =~ /^tCapab(\d+)$/)
                if (m.matches()) {
                    def cn = (m[0][1] as Integer)
                    if (!existing.contains(cn)) candidateIdxs << cn
                }
            }
        }
        if (!candidateIdxs) {
            throw new IllegalStateException("addTrigger: no in-flight tCapab<N> in selectTriggers schema after moreCond click. Existing committed: ${existing}; schema names: ${allCapInputs.collect { it?.name }.findAll { it }.join(', ')}")
        }
        idx = candidateIdxs.max()
    }
    def capInput = allCapInputs.find { it?.name == "tCapab${idx}".toString() }
    if (!capInput) throw new IllegalStateException("addTrigger: tCapab${idx} not present in selectTriggers schema -- wizard didn't open. Was the moreCond click consumed?")
    def capOptions = (capInput.options ?: []) as List
    def capCanonical = capOptions.find { it.toString().equalsIgnoreCase(cap) }
    if (!capCanonical) {
        def suggestion = _rmSuggestTriggerCapability(cap, capOptions)
        def didYouMean = suggestion ? " Did you mean '${suggestion}'?" : ""
        throw new IllegalArgumentException("addTrigger.capability '${cap}' is not in Rule Machine's trigger list on this hub (RM lists a capability only when the hub has a device or app for it).${didYouMean} RM offers: ${capOptions.collect { it.toString() }.sort().join(', ')}.")
    }
    _rmWriteSettingOnPage(appId, "selectTriggers", "tCapab${idx}", capCanonical, applied, null, skipped)

    // Helper closures (sandbox-friendly: no closure recursion on private methods)
    def writeIfPresent = { String name, Object value, String typeHint = null ->
        if (value != null) _rmWriteSettingOnPage(appId, "selectTriggers", name, value, applied, typeHint, skipped)
    }

    // Device-based capabilities use tDev<N>. Time and other non-device
    // capabilities skip this block.
    if (triggerSpec.deviceIds != null) {
        // Defense-in-depth: if the caller passed deviceIds, tDev<N> MUST
        // be in the schema after the tCapab<N> write. If it isn't, the
        // capability normalization above missed something — abort
        // before clicking hasAll on a broken half-baked trigger.
        def afterCap = _rmFetchConfigJson(appId, "selectTriggers")
        def afterInputs = (afterCap?.configPage?.sections ?: []).collectMany { it?.input ?: [] }
        if (!afterInputs.find { it?.name == "tDev${idx}".toString() }) {
            throw new IllegalStateException("addTrigger: tDev${idx} did not appear after writing tCapab${idx}=${capCanonical}; refusing to commit a broken trigger. Schema names: ${afterInputs.collect { it?.name }.findAll{it}.join(', ')}")
        }
        writeIfPresent("tDev${idx}", triggerSpec.deviceIds)
    }

    // A Variable trigger picks the variable (xVar<n>), which reveals its comparator ReltDev<n>.
    if (capCanonical == "Variable") {
        if (triggerSpec.rawSettings != null && !(triggerSpec.rawSettings instanceof Map)) {
            throw new IllegalArgumentException("triggers[].rawSettings must be a Map, got ${triggerSpec.rawSettings.class?.name}.")
        }
        def hasRawXVar = triggerSpec.rawSettings instanceof Map &&
            ((triggerSpec.rawSettings as Map).any { k, _v -> k.toString().replace("@N", idx.toString()) == "xVar${idx}".toString() })
        if (triggerSpec.variable == null && !hasRawXVar) {
            throw new IllegalArgumentException("triggers[].variable is required when capability='Variable' (hub variable name). Use hub_list_variables to discover available names.")
        }
        if (triggerSpec.variable != null) {
            writeIfPresent("xVar${idx}", triggerSpec.variable)
        }
    }

    // Numeric / text comparator path (Temperature, Humidity, Battery,
    // Custom Attribute, Variable, etc.) Some comparators on the live
    // wizard are Unicode (e.g. '≠' for not-equal). Normalize common
    // ASCII aliases so callers can pass "!=" / "==" / "<>".
    if (triggerSpec.comparator != null) {
        // Custom Attribute: comparator ReltDev<n> and value picker tstate<n>; a failed re-read force-writes
        // the comparator and reports the trigger partial.
        if (triggerSpec.attribute != null) {
            writeIfPresent("tCustomAttr${idx}", triggerSpec.attribute)
            def afterAttr = null
            try {
                // The writeIfPresent helper does not return the post-write schema, so
                // this GET is the only way to see which fields the rCustomAttr re-render
                // exposed (comparator vs value picker). Necessary, not redundant.
                afterAttr = _rmFetchConfigJson(appId, "selectTriggers")
            } catch (Exception fetchEx) {
                mcpLog("warn", "rm-native", "addTrigger Custom Attribute: re-fetch after tCustomAttr${idx}='${triggerSpec.attribute}' failed for app ${appId} (${fetchEx.message ?: fetchEx}); force-writing comparator ReltDev${idx} as fallback (partial)")
            }
            if (afterAttr == null) {
                _rmForceWriteEnumField(appId, "selectTriggers", "ReltDev${idx}".toString(), _rmNormalizeComparator(triggerSpec.comparator), applied, skipped)
            } else {
                def afterAttrInputs = (afterAttr?.configPage?.sections ?: []).collectMany { it?.input ?: [] }
                def comparatorExposed = afterAttrInputs.any { it?.name == "ReltDev${idx}".toString() }
                def valuePickerExposed = afterAttrInputs.any { it?.name == "tstate${idx}".toString() }
                // Comparator-first: write whenever exposed; suppress only the true enum
                // case; neither-exposed still attempts (schema-gated -> not_in_schema).
                if (comparatorExposed) {
                    writeIfPresent("ReltDev${idx}", _rmNormalizeComparator(triggerSpec.comparator))
                } else if (!valuePickerExposed) {
                    writeIfPresent("ReltDev${idx}", _rmNormalizeComparator(triggerSpec.comparator))
                } else {
                    // An enum attribute shows only its value picker tstate<n>; a supplied value lands there below. A
                    // change comparator with no value must match a picker option exactly, or it is reported, not dropped.
                    if (_rmComparatorIsRhsOptional(triggerSpec.comparator)) {
                        def stateVal = triggerSpec.state != null ? triggerSpec.state : triggerSpec.value
                        if (stateVal != null) {
                            // Contradictory request: a state-change comparator AND an explicit
                            // value. The value lands in tstate<N> below and the rule works as an
                            // equals-check, so the change-comparator intent is dropped -- but
                            // honestly, via an INFORMATIONAL skip that does NOT flip partial.
                            skipped << [key: "ReltDev${idx}".toString(), reason: "state_change_comparator_ignored_explicit_value",
                                        value: _rmNormalizeComparator(triggerSpec.comparator), attribute: triggerSpec.attribute?.toString()]
                        } else {
                            def pickerInput = afterAttrInputs.find { it?.name == "tstate${idx}".toString() }
                            // Exact token match, so 'unchanged' never satisfies '*changed*'.
                            def reqToken = _rmComparatorToken(triggerSpec.comparator)
                            def changeOption = _rmReadPickerOptionStrings(pickerInput).find { _rmComparatorTokenMatchesOption(reqToken, it) }
                            if (changeOption != null) {
                                // The picker offers the exact requested change option -- route it
                                // through the generic tstate<N> write so the trigger actually fires.
                                triggerSpec.state = changeOption
                            } else {
                                // The accumulator is the local `skipped` list -- always present on
                                // this path; recording the skip is the whole point here, never a no-op.
                                skipped << [key: "ReltDev${idx}".toString(), reason: "comparator_not_representable_for_enum_attribute",
                                            value: _rmNormalizeComparator(triggerSpec.comparator), attribute: triggerSpec.attribute?.toString()]
                            }
                        }
                    }
                }
            }
        } else if (_rmComparatorIsRhsOptional(triggerSpec.comparator)) {
            // A change comparator on another capability goes where the page renders it: ReltDev<n> when the
            // capability has a comparator (numeric), else the tstate<n> value picker (device state).
            def explicitVal = triggerSpec.state != null ? triggerSpec.state : triggerSpec.value
            def dsCfg = null
            try {
                dsCfg = _rmFetchConfigJson(appId, "selectTriggers")
            } catch (Exception dsEx) {
                // Never abort on a transient re-fetch (the F9 contract) -- degrade below.
                mcpLog("warn", "rm-native", "addTrigger: re-fetch of selectTriggers after device write failed for app ${appId} (${dsEx.message ?: dsEx}); state-change comparator ${_rmNormalizeComparator(triggerSpec.comparator)} routed unverified (partial)")
            }
            def dsInputs = dsCfg == null ? null : (dsCfg?.configPage?.sections ?: []).collectMany { it?.input ?: [] }
            def hasComparatorField = dsInputs != null && dsInputs.any { it?.name == "ReltDev${idx}".toString() }
            def dsPicker = dsInputs == null ? null : dsInputs.find { it?.name == "tstate${idx}".toString() }
            if (hasComparatorField) {
                // Numeric/text comparator cap: '*changed*' is a real ReltDev<N> option -- write it.
                writeIfPresent("ReltDev${idx}", _rmNormalizeComparator(triggerSpec.comparator))
            } else if (dsPicker != null) {
                // Device-state enum cap: the tstate<N> value picker carries the change option.
                if (explicitVal != null) {
                    // A state and a change comparator together: the state wins, and the dropped comparator is reported
                    // as an informational skip on a synthetic key so tstate<n> is never both applied and skipped.
                    skipped << [key: "comparator@tstate${idx}".toString(), reason: "state_change_comparator_ignored_explicit_value",
                                value: _rmNormalizeComparator(triggerSpec.comparator), capability: capCanonical?.toString()]
                } else {
                    // Match the requested token against the picker's actual options rather than
                    // hardcoding a literal (RM may store it wrapped, e.g. '*changed*'). Read the
                    // option strings ONCE. Route the EXACT match into tstate<N> via the generic
                    // write below.
                    def reqToken = _rmComparatorToken(triggerSpec.comparator)
                    def pickerOptions = _rmReadPickerOptionStrings(dsPicker)
                    def changeOption = pickerOptions.find { _rmComparatorTokenMatchesOption(reqToken, it) }
                    if (changeOption != null) {
                        triggerSpec.state = changeOption
                    } else {
                        // The value picker offers no matching change option -- surface a GENUINE
                        // skip (-> partial) keyed on the real tstate<N> field (which received no
                        // value, so no applied/skipped collision) rather than silently landing
                        // ReltDev<N> not_in_schema. The skip carries its own precise walkStep hint.
                        skipped << [key: "tstate${idx}".toString(), reason: "change_comparator_not_representable_for_device_state",
                                    value: _rmNormalizeComparator(triggerSpec.comparator), capability: capCanonical?.toString(),
                                    pickerOptions: pickerOptions,
                                    hint: "The '${capCanonical}' value picker (tstate${idx}) has no option for '${_rmNormalizeComparator(triggerSpec.comparator)}' (options: ${pickerOptions})."]
                    }
                }
            } else {
                // Neither the comparator field (ReltDev<N>, numeric caps) nor the value picker
                // (tstate<N>, device-state enum caps) rendered for this row. Route by curated
                // family and fetch outcome.
                def capFamily = _rmCondFamily(capCanonical?.toString())
                if (dsInputs == null) {
                    // Re-read failed: a numeric capability's '*changed*' is a real ReltDev<n> option, so force-write it;
                    // for a value picker the option string is unknown, so report partial with a repair hint.
                    if (capFamily == "numeric") {
                        _rmForceWriteEnumField(appId, "selectTriggers", "ReltDev${idx}".toString(), _rmNormalizeComparator(triggerSpec.comparator), applied, skipped)
                    } else if (capFamily == "hub-state") {
                        // Mode carries its change through modesX<n> below, so nothing is recorded here.
                    } else {
                        // Keyed on a synthetic comparator@tstate<n> because the real field is unknown; the hint names the fix.
                        skipped << [key: "comparator@tstate${idx}".toString(), reason: "state_change_route_unverified_fetch_failed",
                                    value: _rmNormalizeComparator(triggerSpec.comparator), capability: capCanonical?.toString(),
                                    hint: "selectTriggers could not be re-read to place '${_rmNormalizeComparator(triggerSpec.comparator)}'; check the trigger with hub_get_app_config(appId)."]
                    }
                } else if (capFamily == "hub-state") {
                    // Mode (hub-state) carries its change semantics through modesX<N>, written by
                    // the Mode path below -- a phantom ReltDev<N> here would record not_in_schema
                    // (partial) before that clean commit. Leave it for the Mode path.
                } else {
                    // Page rendered neither field: the change cannot be placed, so report it (synthetic key) rather
                    // than claim the write landed.
                    skipped << [key: "comparator@tstate${idx}".toString(), reason: "change_comparator_not_representable_for_device_state",
                                value: _rmNormalizeComparator(triggerSpec.comparator), capability: capCanonical?.toString(),
                                hint: "The '${capCanonical}' trigger showed no comparator (ReltDev${idx}) or value picker (tstate${idx}) for '${_rmNormalizeComparator(triggerSpec.comparator)}'."]
                }
            }
        } else {
            // Non-RHS-optional numeric/text comparator (>, <, =, ...) on a standard capability --
            // the comparator field is already in the schema; write it directly.
            writeIfPresent("ReltDev${idx}", _rmNormalizeComparator(triggerSpec.comparator))
        }
    }

    // compareToDevice: istDev<n>=true makes the right side a reference device (reltDevice<n>, one
    // device of the same capability) and tstate<n> an optional offset.
    if (triggerSpec.compareToDevice instanceof Map) {
        def ctd = triggerSpec.compareToDevice as Map
        writeIfPresent("istDev${idx}", true)
        def relPage = _rmFetchConfigJson(appId, "selectTriggers")
        def relInput = (relPage?.configPage?.sections ?: []).collectMany { it?.input ?: [] }.find { it?.name?.toString() == "reltDevice${idx}".toString() }
        if (relInput == null) {
            throw new IllegalStateException("addTrigger: compareToDevice: the reference-device picker reltDevice${idx} did not appear after istDev${idx}=true.".toString())
        }
        writeIfPresent("reltDevice${idx}", ctd.deviceId.toString())
        if (ctd.offset != null) rhsOverride = ctd.offset
    }

    // Button and Button LED each have their own button-number field.
    if (triggerSpec.buttonNumber != null) {
        writeIfPresent((capCanonical == "Button LED" ? "ButtonLedtDev" : "ButtontDev") + idx, triggerSpec.buttonNumber)
    }

    // Mode triggers use modesX<n> (a list of mode id strings), never tstate<n>. state takes mode names
    // (case-insensitive, all must resolve); modeIds takes ids directly.
    if (capCanonical == "Mode") {
        def rawModeIds = []
        // RM's mode picker carries any-change as option 0.
        boolean anyModeChange = triggerSpec.modeIds == null && triggerSpec.state == null && _rmComparatorIsRhsOptional(triggerSpec.comparator)
        if (anyModeChange) {
            rawModeIds = ["0"]
        } else if (triggerSpec.modeIds instanceof List) {
            // Caller supplied IDs directly -- convert each to String.
            rawModeIds = (triggerSpec.modeIds as List).collect { it?.toString() }.findAll { it }
        } else {
            // Resolve mode name(s) from location.modes. Support single
            // String or List<String>.
            def nameInput = triggerSpec.state
            def modeNames = (nameInput instanceof List) ? (nameInput as List) : (nameInput != null ? [nameInput] : [])
            if (modeNames.isEmpty()) {
                throw new IllegalArgumentException("addTrigger Mode requires state (mode name or list of mode names) or modeIds (list of mode IDs)")
            }
            def allModes = location.modes ?: []
            def validModeNames = allModes.collect { it?.name?.toString() }.findAll { it }
            modeNames.each { mn ->
                def matched = allModes.find { it?.name?.toString()?.equalsIgnoreCase(mn?.toString()) }
                if (!matched) {
                    def commaHint = _rmCommaJoinedModeHint(mn, validModeNames, "addTrigger Mode")
                    if (commaHint) throw new IllegalArgumentException(commaHint)
                    throw new IllegalArgumentException("addTrigger Mode: mode name '${mn}' not found. Valid modes: ${validModeNames.sort().join(', ')}")
                }
                rawModeIds << matched.id.toString()
            }
        }
        if (rawModeIds.isEmpty()) {
            throw new IllegalArgumentException("addTrigger Mode: no mode IDs resolved -- pass state with valid mode name(s) or modeIds with mode ID(s)")
        }
        // A directly-passed integer ID skips the name-resolution check above, so
        // reject any ID no hub mode carries before it commits a mode-less row.
        def modeCheck = anyModeChange ? [bad: []] : _rmBadModeIds(rawModeIds, null)
        if (modeCheck.bad) {
            throw new IllegalArgumentException("addTrigger Mode: mode ID(s) ${modeCheck.bad.join(', ')} not offered by ${modeCheck.source}. Valid IDs: ${modeCheck.valid.sort().join(', ')}")
        }
        writeIfPresent("modesX${idx}", rawModeIds)
    } else {
        // tstate<N> covers both enum-state ("on"/"active") and numeric value
        // (Temperature reading) paths. value field is the numeric form.
        // NOT used for Mode (see special-case above) -- Mode uses modesX<N>.
        def stateValue = triggerSpec.state != null ? triggerSpec.state : (rhsOverride != null ? rhsOverride : triggerSpec.value)
        if (stateValue instanceof CharSequence) {
            // RM's state picker is a fixed enum per capability (Switch: on/off); a value outside it is
            // dropped silently, so check it against the live options and write RM's spelling.
            def stPage = _rmFetchConfigJson(appId, "selectTriggers")
            def stInput = (stPage?.configPage?.sections ?: []).collectMany { it?.input ?: [] }.find { it?.name == "tstate${idx}".toString() }
            List stOpts = stInput?.type?.toString() == "enum" ? _rmReadPickerOptionStrings(stInput) : []
            if (stOpts) {
                def match = stOpts.find { it.equalsIgnoreCase(stateValue.toString().trim()) }
                if (match == null) {
                    throw new IllegalArgumentException("addTrigger.state '${stateValue}' is not a state Rule Machine offers for ${capCanonical}: ${stOpts.join(', ')}.".toString())
                }
                stateValue = match
            }
        }
        if (stateValue != null) {
            writeIfPresent("tstate${idx}", stateValue)
        }
    }

    // Time triggers write time1/atTime1/atSunriseOffset1 at index 1 whatever the trigger index. atTime
    // without time implies 'A specific time'.
    def effectiveTime = triggerSpec.time
    if (effectiveTime == null && triggerSpec.atTime != null) {
        effectiveTime = "A specific time"
    }
    if (effectiveTime != null) {
        writeIfPresent("time${idx}", effectiveTime)
        if (triggerSpec.atTime != null) {
            // RM's selectTriggers page parses only 'YYYY-MM-DDTHH:mm:ss.SSS+HHMM'; a shorter form throws on
            // every render and wedges the rule, so normalize it.
            writeIfPresent("atTime${idx}", _rmNormalizeAtTime(triggerSpec.atTime.toString()))
        }
        if (triggerSpec.offset != null) {
            def t = effectiveTime?.toString()
            if (t == "Sunrise") writeIfPresent("atSunriseOffset${idx}", triggerSpec.offset)
            else if (t == "Sunset") writeIfPresent("atSunsetOffset${idx}", triggerSpec.offset)
        }
    }

    // Optional modifier fields.
    if (triggerSpec.allOfThese == true) writeIfPresent("AlltDev${idx}", true)
    if (triggerSpec.andStays instanceof Map) {
        writeIfPresent("stays${idx}", true)
        def s = triggerSpec.andStays as Map
        // RM's allHandler multiplies SHours/SMins/SSecs and throws on a null when the trigger fires, so all
        // three are written (default 0).
        writeIfPresent("SHours${idx}", s.hours != null ? s.hours : 0)
        writeIfPresent("SMins${idx}", s.minutes != null ? s.minutes : 0)
        writeIfPresent("SSecs${idx}", s.seconds != null ? s.seconds : 0)
    }

    // Caller escape hatch. Supports the '@N' token in field names —
    // replaced with the auto-assigned trigger index. Matches addAction's
    // rawSettings expansion (verified live 2026-05-17): users pass
    // `xVar@N` and the helper writes `xVar1` (or whatever idx landed on).
    if (triggerSpec.rawSettings instanceof Map) {
        triggerSpec.rawSettings.each { k, v ->
            def fieldName = k.toString().replace("@N", idx.toString())
            writeIfPresent(fieldName, v)
        }
    }

    // Periodic Schedule: open its sub-page, write the frequency's fields and press Done so the row's
    // description bakes. Field base names differ per frequency (freqFieldMap); the suffix is the
    // sub-page index pn.
    if (capCanonical == "Periodic Schedule" && triggerSpec.periodic instanceof Map) {
        def per = triggerSpec.periodic as Map
        def freq = per.frequency?.toString()
        if (!freq) {
            throw new IllegalArgumentException("Periodic Schedule trigger requires periodic.frequency (one of: Seconds, Minutes, Hourly, Daily, Weekly, Monthly, Yearly, 'Cron String')")
        }
        // Monthly mode detection: weekOfMonth present selects nth-weekday mode,
        // which hides the by-day fields. (A by-day+weekOfMonth mix was already
        // rejected up front, so here weekOfMonth presence unambiguously means
        // nth-weekday.)
        def monthlyNthWeekday = (freq == "Monthly" && per.weekOfMonth != null)
        // The periodic href comes from the live page; its params.n is this trigger's index and suffixes the
        // sub-page fields, which keeps two periodic triggers apart.
        def periodicHrefName = "periodic1"
        def periodicHrefParams = [n: 1]
        def periodicHrefFound = false
        def periodicHrefDiscoveryError = null
        try {
            def stCfg = _rmFetchConfigJson(appId, "selectTriggers")
            def periodicHref = (stCfg?.configPage?.sections ?: []).collectMany { sect ->
                (sect?.body ?: []).findAll { b -> b instanceof Map && b.element == "href" && b.page?.toString() == "periodic" }
            }.find { it != null }
            if (periodicHref != null) {
                periodicHrefFound = true
                if (periodicHref.name != null) periodicHrefName = periodicHref.name.toString()
                if (periodicHref.params instanceof Map) periodicHrefParams = periodicHref.params as Map
            }
        } catch (Exception hrefExc) {
            periodicHrefDiscoveryError = "${hrefExc.class.simpleName}: ${hrefExc.message}".toString()
            mcpLog("warn", "rm-native", "_rmAddTrigger: periodic href discovery on selectTriggers failed for app ${appId} (in-flight trigger idx ${idx}) (${periodicHrefDiscoveryError}) -- falling back to periodic1/n=1; a non-first periodic trigger may collide with the first")
        }
        // No href: suffix 1 is safe only for the first periodic trigger, so warn.
        if (!periodicHrefFound) {
            mcpLog("warn", "rm-native", "_rmAddTrigger: no periodic href found in selectTriggers schema for app ${appId} (in-flight trigger idx ${idx}) -- falling back to periodic1/n=1")
        }
        // A non-integer params.n falls back to 1 and is flagged like a missing href.
        def periodicHrefIndex = 1
        def periodicNMalformed = false
        if (periodicHrefParams?.n != null) {
            try {
                periodicHrefIndex = periodicHrefParams.n as Integer
            } catch (Exception nCastExc) {
                periodicNMalformed = true
                mcpLog("warn", "rm-native", "_rmAddTrigger: periodic href params.n='${periodicHrefParams.n}' is not an integer for app ${appId} (in-flight trigger idx ${idx}) (${nCastExc.class.simpleName}: ${nCastExc.message}) -- falling back to n=1")
                periodicHrefIndex = 1
            }
        }
        // Fresh params with n as an Integer for the later Done submit.
        def hrefParams = (periodicHrefParams instanceof Map ? (periodicHrefParams as Map) : [:]) + [n: periodicHrefIndex]
        // Without the right sub-page index a non-first trigger would overwrite an earlier periodic trigger's
        // fields, so skip the writes and report partial.
        def periodicUnsafeFallback = ((!periodicHrefFound || periodicNMalformed) && (idx as Integer) > 1)
        if (periodicUnsafeFallback) {
            def unsafeReason = periodicHrefDiscoveryError != null ? "periodic href discovery failed (${periodicHrefDiscoveryError})"
                             : !periodicHrefFound ? "periodic href absent from selectTriggers schema"
                             : "periodic href params.n malformed"
            skipped << [key: "periodic", reason: "periodic_href_unresolved_nonfirst_trigger", value: freq,
                        note: "Cannot resolve the periodic sub-page index for trigger idx ${idx} (${unsafeReason}); writing suffix-1 fields would overwrite an earlier periodic trigger. Periodic sub-page not written."]
            mcpLog("warn", "rm-native", "_rmAddTrigger: skipping periodic sub-page writes for app ${appId} trigger idx ${idx} -- ${unsafeReason} and idx>1, suffix-1 fallback would clobber the first periodic trigger")
        } else if (periodicNMalformed || periodicHrefDiscoveryError != null) {
            // Safe path (idx==1, suffix-1 is correct) but a discovery anomaly still
            // occurred. Surface a breadcrumb in the response so a hub systematically
            // emitting bad periodic hrefs shows up as more than scattered hub logs.
            def anomaly = periodicNMalformed ? "periodic href params.n malformed (resolved to 1)" : "periodic href discovery threw (${periodicHrefDiscoveryError})"
            skipped << [key: "periodic", reason: "periodic_href_anomaly_firsttrigger", value: freq,
                        note: "${anomaly}; suffix-1 was used and is correct for the first periodic trigger, but verify the schedule rendered via hub_get_app_config."]
        }
        // Periodic field names are suffixed by the per-trigger sub-page index
        // (pn). For pn=1 the field names end in ...C1; for a later periodic
        // trigger pn follows the discovered sub-page index, so the names become
        // ...C<pn> and target that trigger's own sub-page slots.
        def pn = periodicHrefIndex

        // Role -> RM field name per frequency (suffix interpolated). everyNToggle must land before
        // everyNCount; Seconds has no toggle. Monthly and Yearly switch field sets when weekOfMonth is
        // written, so the dispatch routes on it.
        def freqFieldMap = [
            "Seconds": [secondsCount: "everyNSecs${pn}"],
            "Minutes": [everyNToggle: "everyNMinutesC${pn}", everyNCount: "everyNC${pn}", selectMulti: "selectMinutesC${pn}"],
            "Hourly":  [everyNToggle: "everyNHoursC${pn}", everyNCount: "everyNHC${pn}", time: "startingHC${pn}", selectMulti: "selectHoursC${pn}", offset: "startingHCX${pn}"],
            "Daily":   [everyNToggle: "everyNDoMC${pn}",   everyNCount: "everyNDC${pn}", time: "startingDC${pn}", weekdayToggle: "everyWeekDay${pn}", selectMulti: "selectDoMC${pn}"],
            "Weekly":  [selectMulti: "selectDoWC${pn}", time: "startingWC${pn}"],
            // Monthly carries BOTH mode field sets; dispatch picks one by weekOfMonth presence.
            // (Specific-months "on day N of selected months" is a THIRD, order-sensitive
            // sub-mode that the flat routing can't satisfy -- not supported here.)
            "Monthly": [dayOfMonth: "dayMC${pn}", everyNMonths: "everyNMC${pn}",
                        weekOfMonth: "weeklyMC${pn}", dayOfWeek: "dailyMC${pn}", everyNMonthsNth: "everyNMCX${pn}",
                        time: "startingMC${pn}"],
            // Yearly is ALWAYS nth-weekday; yearlyMonthC<n> alone never completes,
            // so the month lives in yearlyMonthCX<n> (the X-suffixed reveal field).
            "Yearly":  [weekOfMonth: "weeklyYC${pn}", dayOfWeek: "dailyYC${pn}", monthEnum: "yearlyMonthCX${pn}", time: "startingYC${pn}"],
            "Cron String": [cron: "cronStr${pn}"]
        ]
        def fields = freqFieldMap.get(freq) ?: [:]
        // Arguments were validated before the editor opened; skip the sub-page if it would overwrite another
        // periodic trigger.
        if (!periodicUnsafeFallback) {
        // Navigation's null result is ambiguous (failure or non-JSON page); a real failure shows up as skipped
        // fields below. RM's own periodic page logs a harmless NullPointerException about params.n while it
        // renders; the trigger still bakes.
        _rmNavigateToPage(appId, "selectTriggers", "periodic", periodicHrefIndex, periodicHrefName, hrefParams, null, null, false, true)
        // Closure that wraps _rmWriteSubPageField with applied/skipped routing
        // based on the helper's persistence verification (Map return). Use this
        // for every periodic-sub-page field write so silent rejections are
        // caught and surfaced rather than optimistically claimed as applied.
        def writePeriodic = { String fieldKey, Object fieldValue ->
            def wr = _rmWriteSubPageField(appId, "periodic", "selectTriggers", periodicHrefName, periodicHrefIndex, hrefParams, fieldKey, fieldValue)
            if (wr?.persisted) {
                applied << fieldKey
            } else if (wr?.verifyFetchFailed) {
                skipped << [key: fieldKey, reason: "verification_fetch_failed", value: fieldValue, verifyError: wr?.verifyError]
            } else {
                skipped << [key: fieldKey, reason: "silent_rejection", value: fieldValue, schemaUnchanged: true, available: wr?.afterKeys]
            }
        }
        // Write frequency first -- schema-progressive, so subsequent fields
        // only appear after this lands. whichPeriod<pn> is the per-trigger
        // mode selector (whichPeriod1 for the 1st periodic trigger).
        writePeriodic("whichPeriod${pn}".toString(), freq)
        if (freq == "Cron String") {
            // Cron String mode: just one text field.
            if (per.cronString != null && fields.cron) {
                writePeriodic(fields.cron, per.cronString.toString())
            }
        } else {
            // Seconds: single count enum, no toggle.
            if (per.everyN != null && fields.secondsCount) {
                writePeriodic(fields.secondsCount, per.everyN)
            }
            // everyN toggle + count (Minutes / Hourly / Daily). Order matters:
            // the toggle must land first because the count field only appears
            // (and only accepts a value) after the toggle is true. Writing the
            // count into the toggle field is the original "rendered null" bug.
            if (per.everyN != null && fields.everyNToggle) {
                writePeriodic(fields.everyNToggle, true)
                if (fields.everyNCount) {
                    writePeriodic(fields.everyNCount, per.everyN)
                }
            }
            // Daily-only: weekdaysOnly toggle.
            if (per.weekdaysOnly == true && fields.weekdayToggle) {
                writePeriodic(fields.weekdayToggle, true)
            }
            // nth-weekday mode (Monthly with weekOfMonth, OR Yearly which is
            // always nth-weekday). Writing the week-of-month field HIDES the
            // by-day fields and reveals the day-of-week + X-suffixed cadence/
            // month fields, so the by-day branch below must NOT also run.
            def nthWeekday = (freq == "Yearly") || monthlyNthWeekday
            if (nthWeekday) {
                if (per.weekOfMonth != null && fields.weekOfMonth) {
                    writePeriodic(fields.weekOfMonth, per.weekOfMonth)
                }
                // Single day-of-week enum (dailyMC<n> / dailyYC<n>). The dayOfWeek
                // alias is SINGULAR -- distinct from Weekly's daysOfWeek multi.
                if (per.dayOfWeek != null && fields.dayOfWeek) {
                    writePeriodic(fields.dayOfWeek, per.dayOfWeek)
                }
                // Monthly nth-weekday cadence is a FREE number in everyNMCX<n>
                // (NOT the by-day everyNMC<n>, and NOT the restricted enum).
                if (per.everyNMonths != null && fields.everyNMonthsNth) {
                    writePeriodic(fields.everyNMonthsNth, per.everyNMonths)
                }
                // Yearly nth-weekday month is a single enum in yearlyMonthCX<n>.
                if (per.months != null && fields.monthEnum) {
                    writePeriodic(fields.monthEnum, per.months)
                }
            } else {
                // by-day mode (Monthly without weekOfMonth). dayMC<n> + everyNMC<n>;
                // an incomplete combo (e.g. dayOfMonth without everyNMonths)
                // renders "null" -- documented in the arg shape.
                if (per.dayOfMonth != null && fields.dayOfMonth) {
                    writePeriodic(fields.dayOfMonth, per.dayOfMonth)
                }
                if (per.everyNMonths != null && fields.everyNMonths) {
                    writePeriodic(fields.everyNMonths, per.everyNMonths)
                }
            }
            // Each frequency has one multi-select field under its own alias (hours, days of month, days of
            // week, minutes); the first non-empty alias is used.
            def selectVals = per.selectedHours ?: per.selectedDaysOfMonth ?: per.daysOfWeek ?:
                             per.selectedMinutes
            if (selectVals != null && fields.selectMulti) {
                writePeriodic(fields.selectMulti, selectVals)
            }
            // Time field (startingHC<n> / startingDC<n> / startingWC<n> / ...).
            if (per.startingTime != null && fields.time) {
                writePeriodic(fields.time, per.startingTime.toString())
            }
            // Hourly-only: minute offset (when not using everyN).
            if (per.minutesOffset != null && fields.offset) {
                writePeriodic(fields.offset, per.minutesOffset)
            }
        }
        // Caller escape hatch for periodic-page fields not yet mapped above.
        if (per.rawSettings instanceof Map) {
            (per.rawSettings as Map).each { rk, rv ->
                writePeriodic(rk.toString(), rv)
            }
        }
        // The row already exists, so a failed Done is a repairable partial, not success:false.
        try {
            _rmSubmitSubPageDone(appId, "periodic", "selectTriggers", periodicHrefName, hrefParams)
        } catch (Exception periodicDoneExc) {
            def doneErr = "${periodicDoneExc.class.simpleName}: ${periodicDoneExc.message}".toString()
            mcpLog("warn", "rm-native", "_rmAddTrigger: periodic sub-page Done submit failed for app ${appId} (in-flight trigger idx ${idx}) (${doneErr})")
            skipped << [key: "periodic", reason: "subpage_done_failed", value: freq,
                        note: "Periodic sub-page Done submit failed (${doneErr}); the trigger row description may not have baked. Verify via hub_get_app_config and re-add if the row shows '?'."]
        }
        } // end if (!periodicUnsafeFallback)
    }

    // The conditional binding (isCondTrig.<n>=true, then condTrig.<n>=<condition id>) must be written
    // before hasAll; after hasAll the edit form is gone and the writes drop.
    if (conditionSpec != null || triggerSpec.conditional == true) {
        // Pre-hasAll: bind the trigger to its condition while the edit
        // form is open. _rmWriteSettingOnPage no-ops if isCondTrig.<idx>
        // isn't yet in the schema, but in practice it's exposed as soon
        // as tCapab/tDev/tstate are written.
        _rmWriteSettingOnPage(appId, "selectTriggers", "isCondTrig.${idx}", true, applied, null, skipped)
        if (conditionId != null) {
            _rmWriteSettingOnPage(appId, "selectTriggers", "condTrig.${idx}", conditionId.toString(), applied, null, skipped)
        }
    }

    // Click hasAll (with form context) to commit.
    _rmClickAppButton(appId, "hasAll", null, "selectTriggers")
    editorOpen[0] = false

    // After hasAll a plain trigger answers RM's leftover "Conditional Trigger?" with false; a conditional
    // one keeps its true.
    if (conditionSpec == null && triggerSpec.conditional != true) {
        try {
            _rmWriteSettingOnPage(appId, "selectTriggers", "isCondTrig.${idx}", false, applied, null, skipped)
        } catch (Exception finalizeExc) {
            mcpLog("debug", "rm-native", "_rmAddTrigger: post-hasAll isCondTrig.${idx}=false finalize failed for app ${appId} (${finalizeExc.message}) -- residual 'Conditional Trigger?' prompt may linger, can leave a phantom Broken Trigger N+1")
            // Best-effort: if the prompt isn't there (clean exit), the
            // schema check inside _rmWriteSettingOnPage skips it.
        }
    }

    // Return to mainPage (the "Done with Triggers" href) so the trigger bakes before the next edit.
    _rmNavigateToPage(appId, "selectTriggers", "mainPage", 0, "name", null, null, null, false, true)

    // Final config-error check.
    def finalConfig
    def verificationFetchFailed = false
    try { finalConfig = _rmFetchConfigJson(appId, "selectTriggers") }
    catch (Exception verifyExc) {
        finalConfig = null
        verificationFetchFailed = true
        mcpLog("warn", "rm-native", "_rmAddTrigger: post-commit selectTriggers fetch failed for app ${appId} (${verifyExc.message}) -- caller cannot verify the trigger baked, will mark response as verificationFetchFailed=true")
    }
    def err = finalConfig?.configPage?.error

    def health = _rmCheckRuleHealth(appId)

    // selectTriggers accepts bad values without an error and the row may never bake; check mainPage so a
    // missing trigger is not reported as success.
    def triggerNotBaked = false
    try {
        def mainCfg = _rmFetchConfigJson(appId, "mainPage")
        // Read BOTH paragraph formats the hub emits. body-element format is used
        // by the live Hubitat UI renderer; paragraphs-array format appears in some
        // direct /configure/json responses. _rmCheckRuleHealth does the same dual-read
        // for its broken-marker scan, so detection works across both hub paths.
        def mainParagraphs = (mainCfg?.configPage?.sections ?: []).collectMany { sect ->
            def fromBody = (sect?.body ?: [])
                .findAll { b -> b instanceof Map && (b.element == "paragraph" || b.element == "href") }
                .collect { it.description?.toString() ?: "" }
            def fromParagraphs = (sect?.paragraphs ?: []).collect { it?.toString() ?: "" }
            fromBody + fromParagraphs
        }
        def joinedParagraphs = mainParagraphs.join("\n")
        // Placeholder-only state: paragraph says "Define Triggers" but no
        // trigger row exists. If a trigger row IS present, the placeholder
        // is replaced by the rendered trigger text.
        triggerNotBaked = joinedParagraphs.contains("Define Triggers")
    } catch (Exception verifyExc) {
        verificationFetchFailed = true
        mcpLog("warn", "rm-native", "_rmAddTrigger: post-commit mainPage paragraph fetch failed for app ${appId} (${verifyExc.message}) -- trigger-baked check skipped")
    }

    // partial: a requested field did not land, or an earlier trigger is already broken. Walker
    // sentinels (_rmInformationalSkippedReasons) don't count; not_in_schema does, since the value never
    // wrote. The one exception is the best-effort isCondTrig.<n>=false that closes RM's leftover prompt:
    // when the prompt is already gone that write no-ops. isCondTrig.<n>=true is load-bearing and still counts.
    def triggerInformationalReasons = _rmInformationalSkippedReasons()
    def genuineSkipped = (skipped ?: []).findAll {
        if (!(it instanceof Map) || it.reason == null) return true
        if (it.reason in triggerInformationalReasons) return false
        // The post-commit finalize toggle's absence (isCondTrig.<N>=false) is a clean exit, not
        // a degraded write. The =true construction write is load-bearing and is NOT exempt.
        if (it.reason == "not_in_schema" && it.key?.toString()?.matches(/isCondTrig\.\d+/) && it.value?.toString() == "false") return false
        return true
    }
    def partial = !genuineSkipped.isEmpty() || triggerNotBaked ||
                  ((health?.brokenMarkers as List)?.size() > 0)
    def hubRenderError = err != null || (genuineSkipped.any { it?.available != null && (it.available as List).isEmpty() } as Boolean) || triggerNotBaked
    def repairHints = []
    def hasBrokenLabel = health?.label?.toString()?.contains("*BROKEN*") as Boolean
    if (triggerNotBaked) {
        repairHints << "Trigger did not bake: the rule still shows no triggers. Check its fields with discover (kind:'trigger') and the deviceIds with hub_list_devices, remove the row with removeTrigger, then add it again."
    }
    if (partial || hasBrokenLabel) {
        // Name only the fields that really degraded. comparator_force_written_unverified was posted but
        // not confirmed, so it gets a verify hint, not a rewrite hint.
        def forceWrittenKeys = genuineSkipped.findAll { it instanceof Map && it.reason == "comparator_force_written_unverified" }*.key.findAll { it != null }
        // These reasons have their own hints below; the generic rewrite advice would send the caller to
        // write a token the picker rejects.
        def specificHintReasons = ["comparator_force_written_unverified", "comparator_not_representable_for_enum_attribute", "comparator_not_offered",
                                   "change_comparator_not_representable_for_device_state", "state_change_route_unverified_fetch_failed"]
        def lostSkipped = genuineSkipped.findAll { !(it instanceof Map && it.reason in specificHintReasons) }
        if (!lostSkipped.isEmpty()) {
            def skippedKeys = lostSkipped*.key.findAll { it != null }.join(', ')
            def settingWord = lostSkipped.size() == 1 ? "setting" : "settings"
            repairHints << "Some trigger ${settingWord} did not land: ${skippedKeys}. If the trigger renders right in hub_get_app_config(appId), nothing is missing; otherwise write them with walkStep (page:'selectTriggers')."
        }
        if (!forceWrittenKeys.isEmpty()) {
            def cmpWord = forceWrittenKeys.size() == 1 ? "Comparator" : "Comparators"
            def cmpVerb = forceWrittenKeys.size() == 1 ? "was" : "were"
            repairHints << "${cmpWord} ${forceWrittenKeys.join(', ')} ${cmpVerb} written but could not be read back; check the trigger in hub_get_app_config(appId) before writing again."
        }
        def notRepresentable = genuineSkipped.findAll { it instanceof Map && it.reason in _rmUnrepresentedComparatorReasons() }
        notRepresentable.each { sk ->
            repairHints << _rmUnrepresentedComparatorHint(sk as Map)
        }
        // Device-state change-comparator skips (no matching picker option, or an unverifiable
        // re-fetch) carry their own precise walkStep hint on the skip -- surface it directly so
        // the caller gets the accurate device-state repair path, not the generic one above.
        genuineSkipped.findAll { it instanceof Map &&
                (it.reason == "change_comparator_not_representable_for_device_state" || it.reason == "state_change_route_unverified_fetch_failed") }.each { sk ->
            if (sk.hint) repairHints << sk.hint.toString()
        }
        if (hasBrokenLabel) {
            repairHints << "The trigger renders *BROKEN*: '${cap}' needs its own fields (discover kind:'trigger', capability:'${cap}'). Remove it and add it again."
        }
        if ((health?.brokenMarkers as List)?.size() > 0 && !hasBrokenLabel) {
            repairHints << "The rule already had broken rows: ${(health.brokenMarkers as List).unique().join(', ')}. The trigger was added; repair those rows (hub_get_rule_health(${appId}))."
        }
        if (skipped?.any { it?.reason == "periodic_href_unresolved_nonfirst_trigger" }) {
            repairHints << "This periodic trigger's sub-page could not be resolved, so its schedule was not written (writing it could overwrite another periodic trigger). Remove it and add the rule's periodic triggers again in order."
        }
        if (hubRenderError) {
            repairHints << "selectTriggers may have rendered with an error (${err}); remove the trigger and retry with other devices or fields."
        }
    }
    // success: the call finished and wrote something (the row exists). partial: the row needs repair.
    def result = [
        success: !err && !applied.isEmpty(),
        partial: partial || hasBrokenLabel,
        hubRenderError: hubRenderError,
        triggerIndex: idx,
        capability: cap,
        settingsApplied: applied,
        settingsSkipped: skipped,
        configPageError: err,
        repairHints: repairHints,
        health: health,
        verificationFetchFailed: verificationFetchFailed
    ]
    if (conditionId != null) result.conditionId = conditionId
    return result
    }
    try {
        return _impl()
    } catch (Exception failed) {
        // A failure after the trigger editor opened leaves it open. RM's cancel drops the in-flight
        // trigger on the next render.
        String stuck = null
        if (editorOpen[0]) {
            try {
                _rmClickAppButton(appId, "cancelCapab", null, "selectTriggers")
                _rmFetchConfigJson(appId, "selectTriggers")
            } catch (Exception cancelExc) {
                stuck = "cancelCapab cleanup failed: ${cancelExc.message ?: cancelExc}".toString()
            }
        }
        String leftover = builtCond[0] != null ? " The condition created for this trigger (id ${builtCond[0]}) stays in the rule's conditions as unused; Manage Conditions in the Rule Machine UI can delete it.".toString() : ""
        if (stuck) throw _rmFail(new IllegalStateException("${failed.message ?: failed}${leftover} [wizardStuck -- ${stuck}; close the trigger editor with hub_set_rule(button='cancelCapab', pageName='selectTriggers', confirm=true) and check the rule with hub_get_app_config(appId=${appId})]".toString(), failed), [wizardStuck: true])
        // The condition RM created for the cancelled trigger stays in its condition list as unused
        // (harmless, per RM); the next conditional trigger takes a new slot. A refusal after it
        // exists is no longer a validation error: a corrected retry would create another.
        if (leftover) throw _rmFail(new IllegalStateException("${(failed.message ?: failed.toString()).replace(' RM is not touched.', '')}${leftover}".toString(), failed), [fieldsWritten: true])
        throw failed
    }
}

// Rule Machine's trigger and condition capability tables: capabilities, numeric capabilities
// (comparator, value, device comparison), attribute, default state, device picker, and those without
// "and stays". The live tCapab/rCapab pickers still decide what a hub offers: RM drops a capability
// no hub device has, HSM without Safety Monitor, Button LED without Lutron, Rule paused with no rules.
private Map _rmCondTables() {
    def table = {
    [trigger: ["Acceleration", "Battery", "Button", "Carbon monoxide detector", "Carbon dioxide sensor", "Certain Time (and optional date)", "Periodic Schedule", "Contact", "Dimmer", "Energy meter", "Garage door", "Humidity", "Illuminance", "Lock", "Physical Lock", "Valve", "Mode", "Motion", "Digital Switch", "Variable", "Physical dimmer level", "Physical Switch", "Power meter", "Presence", "Rule paused", "Smoke detector", "Switch", "Fan Speed", "Sound", "Shock sensor", "Temperature", "Door", "HSM Status", "HSM Alert", "Thermostat mode", "Thermostat state", "Thermostat heat setpoint", "Thermostat cool setpoint", "Gas detector", "Water sensor", "Private Boolean", "Music player", "Location Event", "Cloud End Point", "Local End Point", "Security keypad", "Tamper alert", "Power source", "Keypad codes", "Lock codes", "Button LED", "Thermostat fan mode", "Custom Attribute", "Days of Week Schedule", "Last Event Device", "Window Shade", "Window Blind"],
     condition: ["Acceleration", "Battery", "Carbon monoxide detector", "Carbon dioxide sensor", "Contact", "Days of week", "Between two dates", "Dimmer", "Energy meter", "Garage door", "Humidity", "Illuminance", "Lock", "Valve", "Fan Speed", "Mode", "Motion", "Power meter", "Presence", "Rule paused", "Smoke detector", "Sound", "Shock sensor", "Gas detector", "Switch", "Temperature", "Private Boolean", "Door", "HSM Status", "Thermostat mode", "Thermostat state", "Thermostat heat setpoint", "Variable", "Power source", "On a Day", "Thermostat cool setpoint", "Time of day", "Between two times", "Water sensor", "Music player", "Security keypad", "Tamper alert", "Keypad codes", "Lock codes", "Thermostat fan mode", "Custom Attribute", "Last Event Device", "Time Since Event", "Window Shade", "Window Blind"],
     numeric: ["Battery", "Carbon dioxide sensor", "Dimmer", "Energy meter", "Humidity", "Illuminance", "Physical dimmer level", "Power meter", "Temperature", "Thermostat heat setpoint", "Thermostat cool setpoint"],
     attribute: ["Physical dimmer level": "level", "Physical Switch": "switch", "Fan Speed": "speed", "Smoke detector": "smoke", "Carbon monoxide detector": "carbonMonoxide", "Carbon dioxide sensor": "carbonDioxide", "Digital Switch": "switch", "Water sensor": "water", "Security keypad": "securityKeypad", "Tamper alert": "tamper", "Garage door": "door", "Door": "door", "Dimmer": "level", "Shock sensor": "shock", "Energy meter": "energy", "Power meter": "power", "Power source": "powerSource", "Temperature": "temperature", "Thermostat mode": "thermostatMode", "Thermostat state": "thermostatOperatingState", "Thermostat fan mode": "thermostatFanMode", "Music player": "status", "Humidity": "humidity", "Battery": "battery", "Illuminance": "illuminance", "Window Shade": "windowShade", "Window Blind": "windowBlind", "Acceleration": "acceleration", "Contact": "contact", "Lock": "lock", "Physical Lock": "lock", "Lock codes": "lockCode", "Valve": "valve", "Motion": "motion", "Presence": "presence", "Switch": "switch", "Sound": "sound", "Thermostat heat setpoint": "heatingSetpoint", "Thermostat cool setpoint": "coolingSetpoint", "Gas detector": "naturalGas"],
     defaultState: ["Switch": "on", "Physical Switch": "on", "Button LED": "on", "Motion": "active", "Acceleration": "active", "Contact": "open", "Presence": "present", "Garage door": "open", "Gas detector": "detected", "Door": "open", "Lock": "locked", "Physical Lock": "locked", "Valve": "open", "Fan Speed": "off", "Power source": "mains", "Thermostat mode": "heat", "Thermostat state": "heating", "Shock sensor": "detected", "Thermostat fan mode": "auto", "Location Event": "mode", "Location": "mode", "Carbon monoxide detector": "detected", "Smoke detector": "detected", "Sound": "detected", "Water sensor": "wet", "Security keypad": "disarmed", "Tamper alert": "detected", "Rule paused": "true", "Music player": "playing", "Private Boolean": "true", "HSM Status": "disarmed", "Digital Switch": "on", "Window Shade": "open", "Window Blind": "open"],
     deviceCapability: ["Switch": "switch", "Physical dimmer level": "switchLevel", "Physical Switch": "switch", "Digital Switch": "switch", "Motion": "motionSensor", "Acceleration": "accelerationSensor", "Contact": "contactSensor", "Presence": "presenceSensor", "Garage door": "garageDoorControl", "Door": "doorControl", "Actuator": "actuator", "Button": "pushableButton", "Music player": "musicPlayer", "Lock": "lock", "Physical Lock": "lock", "Valve": "valve", "Fan Speed": "fanControl", "Fan Control": "fanControl", "Power source": "powerSource", "Dimmer": "switchLevel", "Temperature": "temperatureMeasurement", "Thermostat mode": "thermostat", "Thermostat state": "thermostat", "Thermostat fan mode": "thermostat", "Thermostat": "thermostat", "Carbon dioxide sensor": "carbonDioxideMeasurement", "Humidity": "relativeHumidityMeasurement", "Illuminance": "illuminanceMeasurement", "Energy meter": "energyMeter", "Power meter": "powerMeter", "Carbon monoxide detector": "carbonMonoxideDetector", "Smoke detector": "smokeDetector", "Gas detector": "gasDetector", "Shock sensor": "shockSensor", "Water sensor": "waterSensor", "Sound": "soundSensor", "Security keypad": "securityKeypad", "Tamper alert": "tamperAlert", "Time Since Event": "*", "Tone": "tone", "Speech device": "speechSynthesis", "Window Shade": "windowShade", "Window Blind": "windowBlind"],
     noDuration: ["Button", "Certain Time", "Periodic Schedule", "Mode", "Cloud End Point", "Local End Point", "Security keypad", "Keypad codes", "Lock codes", "Button LED", "Days of Week Schedule", "Last Event Device", "Location Event", "Location", "Certain Time (and optional date)"],
     locationEvents: ["cloudBackup", "lowMemory", "sunrise", "sunset", "sunriseTime", "sunsetTime", "systemStart", "severeLoad", "zigbeeOff", "zigbeeOn", "zwaveCrashed", "schedulerFailed", "manualReboot", "manualShutdown"],
     hsmAlerts: ["intrusion", "intrusion-delay", "intrusion-home", "intrusion-home-delay", "intrusion-night", "intrusion-night-delay", "smoke", "water", "arming", "armingHome", "armingNight", "cancel", "rule", "cancelRuleAlerts"],
     numericOps: ["=", "\u2260", "<", ">", "<=", ">=", "in"],
     changeOps: ["*changed*", "*increased*", "*decreased*"],
     overOps: ["*increased by over*", "*decreased by over*"],
     stringOps: ["=", "\u2260", "*contains*", "*changed*", "*is empty*"],
     variableOps: ["=", "\u2260", "<", ">", "<=", ">=", "in", "*changed*", "*increased*", "*decreased*", "*increased by over*", "*decreased by over*"],
     buttonStates: ["pushed", "held", "doubleTapped", "released"],
     buttonLedStates: ["on", "off"],
     // Device-state triggers whose state picker has no *changed* option.
     noChangeState: ["Rule paused"],
     timeOpts: ["A specific time", "Sunrise", "Sunset"]]
    }
    return table()
}

// RM's spelling of a trigger/condition capability, matched regardless of case; null when RM has none.
private String _rmCondCanonical(Object cap) {
    String want = cap?.toString()?.trim()
    if (!want) return null
    Map t = _rmCondTables()
    return (((t.trigger as List) + (t.condition as List)).find { it.toString().equalsIgnoreCase(want) })?.toString()
}

// How the tool takes a capability's arguments. The RM facts come from _rmCondTables.
private String _rmCondFamily(Object cap) {
    String c = _rmCondCanonical(cap)
    if (c == null) return null
    if (c in (_rmCondTables().numeric as List)) return "numeric"
    switch (c) {
        case "Variable": return "variable"
        case ["Custom Attribute", "Last Event Device"]: return "custom-attribute"
        case "Button": return "button"
        // One pushableButton device, its button number and the LED state on/off.
        case "Button LED": return "button-led"
        case ["Certain Time (and optional date)", "Periodic Schedule", "Days of Week Schedule", "Time of day",
              "Between two times", "Between two dates", "On a Day", "Days of week", "Time Since Event"]: return "time"
        case ["Mode", "Location Event", "HSM Alert", "Cloud End Point", "Local End Point"]: return "hub-state"
    }
    return "device-state"
}

// Argument checks from RM's capability tables, run before any write; writes RM's spelling back.
private void _rmCheckCondSpec(Map spec, String kind, String where) {
    String raw = spec?.capability?.toString()?.trim()
    if (!raw) return
    Map t = _rmCondTables()
    List names = (kind == "condition" ? t.condition : t.trigger) as List
    String cap = names.find { it.toString().equalsIgnoreCase(raw) }
    if (cap == null) {
        def near = _rmSuggestTriggerCapability(raw, names)
        throw new IllegalArgumentException("${where}.capability '${raw}' is not a Rule Machine ${kind} capability.${near ? " Did you mean '" + near + "'?" : ''} Valid: ${names.join(', ')}. RM is not touched.".toString())
    }
    spec.capability = cap
    String fam = _rmCondFamily(cap)
    String hint = "See discover kind:'${kind}', capability:'${cap}'. RM is not touched.".toString()
    if (cap in (kind == "condition" ? _rmCondNotModelled() : _rmTrigNotModelled())) {
        throw new IllegalArgumentException("${where}: '${cap}' is a Rule Machine ${kind} this tool does not model; set it with rawSettings or walkStep. RM is not touched.".toString())
    }
    if (cap in ["Location Event", "HSM Alert"]) {
        List listed = (cap == "Location Event" ? t.locationEvents : t.hsmAlerts) as List
        if (spec.state == null) throw new IllegalArgumentException("${where}: ${cap} needs state (one of ${listed.join(', ')}). ${hint}".toString())
        def canonical = listed.find { it.toString().equalsIgnoreCase(spec.state.toString().trim()) }
        if (canonical == null) throw new IllegalArgumentException("${where}: ${cap} state '${spec.state}' is not one Rule Machine lists (${listed.join(', ')}). ${hint}".toString())
        spec.state = canonical
    }
    if (spec.compareToDevice != null && fam != "numeric") {
        throw new IllegalArgumentException("${where}: compareToDevice applies only to Rule Machine's numeric capabilities (${(t.numeric as List).join(', ')}). ${hint}".toString())
    }
    if (spec.compareToDevice != null) {
        if (!(spec.compareToDevice instanceof Map) || (spec.compareToDevice as Map).deviceId == null) {
            throw new IllegalArgumentException("${where}: compareToDevice requires 'deviceId' (the reference device ID). ${hint}".toString())
        }
        if (spec.comparator == null || _rmComparatorIsRhsOptional(spec.comparator)) {
            throw new IllegalArgumentException("${where}: compareToDevice needs a comparator (e.g. '>', '<', '='). ${hint}".toString())
        }
        if (spec.state != null || spec.value != null) {
            throw new IllegalArgumentException("${where}: compareToDevice (device-relative RHS) cannot be combined with 'state'/'value' (literal RHS) -- mutually exclusive; use compareToDevice.offset for an offset. ${hint}".toString())
        }
    }
    if (spec.comparator != null) {
        String cmp = _rmNormalizeComparator(spec.comparator)?.toString()
        boolean change = _rmComparatorIsRhsOptional(spec.comparator)
        List allowed = null
        // RM offers the change comparators only on a trigger, and not on an "All of these" one.
        boolean trigChange = kind == "trigger" && spec.allOfThese != true
        if (fam == "numeric") allowed = (t.numericOps as List) + (trigChange ? (t.changeOps as List) + (t.overOps as List) : [])
        // A variable condition drops the change comparators except *changed*, which a String variable
        // keeps; the variable's type is not known here.
        else if (fam == "variable") allowed = (((t.variableOps as List) - (kind == "condition" ? (t.changeOps as List) + (t.overOps as List) : [])) + (t.stringOps as List)).unique()
        else if (fam == "custom-attribute") allowed = ((t.numericOps as List) + (t.stringOps as List)).unique() - (kind == "condition" ? ["*changed*"] : [])
        else if ((fam in ["device-state", "button-led"] || cap == "Mode") && kind == "trigger" && change && !(cap in (t.noChangeState as List))) allowed = null
        else throw new IllegalArgumentException("${where}: Rule Machine has no comparator for a ${cap} ${kind}${kind == 'trigger' && (fam in ['device-state', 'button-led'] || cap == 'Mode') && !(cap in (t.noChangeState as List)) ? " other than '*changed*'" : ''}; pass state. ${hint}".toString())
        if (allowed != null && !(cmp in allowed) && !(fam == "numeric" && trigChange && change)) {
            throw new IllegalArgumentException("${where}: comparator '${spec.comparator}' is not one Rule Machine offers for a ${cap} ${kind}: ${allowed.join(' ')}. ${hint}".toString())
        }
    }
    if (cap == "Mode" && spec.state == null && spec.modeIds == null && !(kind == "trigger" && spec.comparator != null)) {
        throw new IllegalArgumentException("${where}: a Mode ${kind} needs state (mode names) or modeIds${kind == 'trigger' ? ", or comparator:'*changed*' for any change" : ''}. ${hint}".toString())
    }
    if (spec.andStays != null && (kind != "trigger" || cap in (t.noDuration as List))) {
        throw new IllegalArgumentException("${where}: Rule Machine has no 'and stays' duration for a ${cap} ${kind}. ${hint}".toString())
    }
}

// Condition and trigger capabilities RM offers that the tool's writers do not model.
private List _rmCondNotModelled() { ["Between two dates", "Days of week", "On a Day", "Lock codes", "Last Event Device", "Time of day", "Time Since Event"] }
private List _rmTrigNotModelled() { ["Days of Week Schedule"] }

// kind 'trigger' or 'condition'. Without a capability: every capability RM has for that kind, by
// family; with one: its fields.
private Map _rmCondDiscover(String kind, Object capability = null) {
    Map t = _rmCondTables()
    List names = (kind == "condition" ? t.condition : t.trigger) as List
    Map base = [discriminator: "capability", kind: kind,
                note: "Rule Machine offers a capability only when the hub has something for it (a device with that capability, Safety Monitor, rules); the write checks RM's live list.",
                conditionFields: _rmActionConditionFields()]
    if (capability == null || !capability.toString().trim()) {
        return base + [capabilities: names.collect { [name: it, family: _rmCondFamily(it)] }]
    }
    String cap = names.find { it.toString().equalsIgnoreCase(capability.toString().trim()) }
    if (cap == null) throw new IllegalArgumentException("discover: '${capability}' is not a Rule Machine ${kind} capability. Valid: ${names.join(', ')}.".toString())
    return base + [capability: _rmCondEntry(cap, kind)]
}

private Map _rmCondEntry(String cap, String kind) {
    Map t = _rmCondTables()
    boolean trig = kind == "trigger"
    String fam = _rmCondFamily(cap)
    String dev = (t.deviceCapability as Map).get(cap)
    def req = []
    def opt = []
    def f = { String n, String type, Map extra = [:] -> [name: n, type: type] + extra }
    switch (fam) {
        case "numeric":
            req << f("deviceIds", "List<Integer>")
            req << f("comparator", "enum", [values: (t.numericOps as List) + (trig ? (t.changeOps as List) + (t.overOps as List) : [])])
            opt << f("value", "Number", [description: "required unless the comparator is a change"])
            opt << f("compareToDevice", "Map", [description: "{deviceId, offset?}: another device's reading instead of value"])
            break
        case "device-state":
            if (dev) req << f("deviceIds", "List<Integer>")
            req << f("state", "enum", ["default": (t.defaultState as Map).get(cap), description: "a value RM's picker lists for the chosen devices"])
            if (dev) opt << f("allOfThese", "Boolean")
            if (trig && !(cap in (t.noChangeState as List))) opt << f("comparator", "enum", [values: ["*changed*"], description: "instead of state: any change"])
            break
        case "variable":
            req << f("variable", "String", [description: "hub variable name"])
            opt << f("comparator", "enum", [values: (((t.variableOps as List) - (trig ? [] : (t.changeOps as List) + (t.overOps as List))) + (t.stringOps as List)).unique(),
                                            description: "a String variable takes ${(t.stringOps as List).join(' ')}".toString()])
            opt << f("value", "Number or String")
            if (!trig) opt << f("compareToVariable", "String", [description: "another hub variable instead of value"])
            break
        case "custom-attribute":
            req << f("deviceIds", "List<Integer>")
            req << f("attribute", "String")
            opt << f("comparator", "enum", [values: ((t.numericOps as List) + (t.stringOps as List)).unique() - (trig ? [] : ["*changed*"])])
            opt << f("value", "Number or String")
            break
        case "button":
            req << f("deviceIds", "List<Integer>")
            req << f("state", "enum", [values: t.buttonStates])
            opt << f("buttonNumber", "Integer", ["default": 1])
            break
        case "button-led":
            req << f("deviceIds", "List<Integer>", [description: "one device"])
            req << f("state", "enum", [values: t.buttonLedStates, "default": "on"])
            opt << f("buttonNumber", "Integer", ["default": 1])
            if (trig) opt << f("comparator", "enum", [values: ["*changed*"], description: "instead of state: any change"])
            break
        case "hub-state":
            if (cap == "Mode") {
                opt << f("state", "String or List<String>", [description: "mode name(s); this or modeIds"])
                opt << f("modeIds", "List")
                if (trig) opt << f("comparator", "enum", [values: ["*changed*"], description: "instead of a mode: any mode change"])
            } else if (cap in ["Location Event", "HSM Alert"]) {
                req << f("state", "enum", [values: (cap == "Location Event" ? t.locationEvents : t.hsmAlerts) as List])
            }
            break
        case "time":
            if (cap == "Certain Time (and optional date)") {
                req << f("time", "enum", [values: t.timeOpts])
                opt << f("atTime", "String", [description: "ISO date-time, for 'A specific time'"])
                opt << f("offset", "Integer", [description: "minutes, for Sunrise/Sunset"])
            } else if (cap == "Periodic Schedule") {
                req << f("periodic", "Map", [properties: [
                    f("frequency", "enum", [values: ["Seconds", "Minutes", "Hourly", "Daily", "Weekly", "Monthly", "Yearly", "Cron String"]]),
                    f("everyN", "Integer", [description: "every N units; Seconds/Minutes take 1, 2, 3, 4, 5, 6, 10, 12, 15, 20 or 30; needed for Daily and Hourly every-N"]),
                    f("startingTime", "String", [description: "'HH:mm'; not for Seconds"]),
                    f("weekdaysOnly", "Boolean", [description: "Daily"]),
                    f("selectedHours", "List<Integer>", [description: "Hourly, instead of everyN"]),
                    f("minutesOffset", "Integer", [description: "Hourly without everyN"]),
                    f("selectedMinutes", "List<Integer>", [description: "Minutes, instead of everyN"]),
                    f("selectedDaysOfMonth", "List<Integer>", [description: "Daily, instead of everyN"]),
                    f("daysOfWeek", "List<String>", [description: "Weekly"]),
                    f("dayOfMonth", "Integer", [description: "Monthly by day, with everyNMonths; not with weekOfMonth"]),
                    f("weekOfMonth", "enum", [values: ["First", "Second", "Third", "Fourth", "Last"], description: "Monthly/Yearly nth weekday, with dayOfWeek"]),
                    f("dayOfWeek", "String", [description: "Monthly/Yearly nth weekday"]),
                    f("everyNMonths", "Integer", [description: "Monthly"]),
                    f("months", "String", [description: "Yearly: one month"]),
                    f("cronString", "String", [description: "Cron String"]),
                    f("rawSettings", "Map")]])
            } else if (cap == "Between two times" && !trig) {
                req << f("start", "Map", [description: "{type: clock|sunrise|sunset, time?: 'HH:mm', offset?}"])
                req << f("end", "Map", [description: "same shape as start"])
            }
            break
    }
    if (trig && !((t.noDuration as List).contains(cap)) && fam != "time") opt << f("andStays", "Map", [description: "{hours?, minutes?, seconds?}"])
    if (trig) opt << f("condition", "Map", [description: "a condition (discover kind:'condition') gating this trigger"])
    else opt << f("not", "Boolean")
    opt << f("rawSettings", "Map", [description: "RM settings for fields not listed; '@N' becomes the row index"])
    def out = [name: cap, family: fam, requiredFields: req, optionalFields: opt]
    if (dev) out.deviceCapability = dev
    def attr = (t.attribute as Map).get(cap)
    if (attr) out.attribute = attr
    if (cap in (trig ? _rmTrigNotModelled() : _rmCondNotModelled())) out.authoring = "not modelled by this tool; use rawSettings or walkStep"
    def example = [capability: cap]
    req.each { Map r ->
        example.put(r.name, r.values instanceof List ? (r.values as List)[0] : (r.get("default") != null ? r.get("default") : _rmExampleValue(r)))
    }
    if (fam == "numeric") example.put("value", 50)
    out.example = example
    return out
}

// Picker options as stored value -> shown label, for the enum shapes _rmReadPickerOptionStrings
// flattens (Map container, List of value/text Maps, bare strings).
private Map _rmReadPickerOptionLabels(Object pickerInput) {
    def opts = pickerInput?.options
    if (opts instanceof Map) return opts.collectEntries { k, v -> [(k.toString()): v?.toString() ?: k.toString()] }
    Map out = [:]
    _rmReadPickerOptionStrings(pickerInput).each { out.put(it, it) }
    if (opts instanceof List) opts.each { o ->
        if (!(o instanceof Map)) return
        Map m = o as Map
        if (m.get("value") != null && m.get("text") != null) out.put(m.get("value").toString(), m.get("text").toString())
        else if (m.size() == 1 && !m.containsKey("value") && !m.containsKey("text")) m.each { k, v -> out.put(k.toString(), v?.toString() ?: k.toString()) }
    }
    return out
}

// Resolves a runCommand capabilityFilter against the options RM's Run Custom Action capability
// picker shows for this row: the stored key (any case, spaces ignored) or the picker label. RM
// stores an unknown value as-is and then renders no device picker for it, leaving a half-built
// row that crashes the action editor, so a miss throws before the value is stored.
private String _rmResolveRunCommandCapability(Object raw, Map options) {
    if (raw == null || !raw.toString().trim()) return "Switch"
    String wanted = raw.toString().trim()
    String key = options.keySet().find { it.toString() == wanted }
    if (key == null) {
        String folded = wanted.replaceAll(/\s+/, "").toLowerCase()
        key = options.find { k, v ->
            k.toString().toLowerCase() == folded || v.toString().replaceAll(/\s+/, "").toLowerCase() == folded
        }?.key
    }
    if (key == null) {
        throw new IllegalArgumentException("runCommand: capabilityFilter '${wanted}' is not a capability Rule Machine's Run Custom Action offers. RM offers: ${options.keySet().sort().join(', ')} (their picker labels, e.g. 'Dimmer', are accepted too).")
    }
    return key.toString()
}

// The discover response: the full action schema, or one capability's entry when a selector
// {capability, action?} is given.
// Fields a custom mapper needs that its slots do not mark required: the served example carries them.
private Map _rmActionExampleFields() {
    def cond = [capability: "Switch", deviceIds: [1], state: "on"]
    [thermostat: [mode: "heat"], mode: [modeName: "Day"], setVariable: [variable: "myVar", value: 1], setLocalVariable: [variable: "myVar", value: 1],
     privateBoolean: [ruleIds: ["*"], value: true], runRule: [ruleIds: [1]], cancelTimers: [ruleIds: [1]], pauseRule: [ruleIds: [1]],
     delay: [seconds: 30], delayPerMode: [perMode: [Day: [seconds: 30]]], repeat: [minutes: 5, times: 3],
     repeatWhile: [expression: [conditions: [cond]]], waitExpression: [expression: [conditions: [cond]]],
     ifThen: [expression: [conditions: [cond]]], elseIf: [expression: [conditions: [cond]]],
     waitEvents: [events: [cond]], returnValue: [value: "done"]]
}

private Map _rmActionDiscover(Object selector) {
    Map full = _rmActionSchemaForDiscover()
    if (!(selector instanceof Map) || !(selector as Map).capability) return full
    String wanted = (selector as Map).capability.toString().trim()
    Map entry = (full.capabilities as List).find { (it as Map).name?.toString()?.equalsIgnoreCase(wanted) } as Map
    if (entry == null) {
        throw new IllegalArgumentException("discover: '${wanted}' is not an addAction capability. Valid: ${(full.capabilities as List).collect { (it as Map).name }.join(', ')}.")
    }
    def action = (selector as Map).action?.toString()?.trim()
    if (action && entry.actions instanceof List && !(entry.actions as List).any { it.toString() == action }) {
        throw new IllegalArgumentException("discover: '${action}' is not an action of '${entry.name}'. Valid: ${(entry.actions as List).join(', ')}.")
    }
    Map out = [discriminator: full.discriminator, capability: entry]
    if (action) out.action = action
    return out
}

// The addAction catalog: for each capability (and action), the Rule Machine action group and
// sub-type it drives and the settings RM's editor takes, in write order (@N = the action index).
// It is the one source for the mapping (_rmMapActionSpec), the discover schema and the argument
// checks. Field entries (an entry's `slots`): name (spec field), key (RM setting), type, values
// (the enum a field takes), required, desc; fixed = the value always written (no spec field); dflt = default;
// appChoice = a picker of hub data that can be empty (app instances, their modes or periods, hub
// variables), checked against RM's live options; whenTrue =
// written only when true; whenSet = written as true when the field is given (the toggle RM shows
// before the field's own picker); trueWhen = written as (value == trueWhen); direction = written
// as (direction == 'raise') or, when false, (direction != 'raise'); nonBlank = must have text.
// Entry keys: notes (served with discover), requires (field names, an inner list meaning one of
// them), exclusive (lists of fields of which at most one is given), common (fields every action
// of the entry takes); requires and exclusive apply per entry and per action.
// custom = handled by the dedicated code in _rmMapActionSpec (the catalog still describes it).
private Map _rmActionCatalog() {
    def table = {
    def dev = { String key, String desc = null -> def f = [name: "deviceIds", key: key, type: "List<Integer>", required: true]; if (desc) f.desc = desc; f }
    def delay = [name: "delay", type: "Map", desc: "{hours?, minutes?, seconds?, cancelable?, random?} or {variable}"]
    def raw = [name: "rawSettings", type: "Map"]
    def tsCmd = { String cmd -> [key: "thermSched.@N", fixed: cmd] }
    return [
        "switch": [actType: "switchActs", family: "device", actions: [
            on:     [sub: "getOnOffSwitch", slots: [dev("onOffSwitch.@N"), [key: "onOff.@N", fixed: true],
                     [name: "onlyIfChanged", key: "optSwitch.@N", type: "Boolean", whenTrue: true, desc: "Command only switches that are off (RM's optSwitch)"]]],
            off:    [sub: "getOnOffSwitch", slots: [dev("onOffSwitch.@N"), [key: "onOff.@N", fixed: false],
                     [name: "onlyIfChanged", key: "optSwitch.@N", type: "Boolean", whenTrue: true, desc: "Command only switches that are on (RM's optSwitch)"]]],
            toggle: [sub: "getToggleSwitch", slots: [dev("toggleSwitch.@N")]],
            flash:  [sub: "getFlashSwitch", slots: [dev("flashSwitch.@N")], notes: "RM has no stop-flash action; stop it with runCommand command 'flashOff'"],
            setPerMode:    [sub: "getModeSwitch", custom: true, slots: [dev("switchM.@N"), [name: "perMode", type: "Map", required: true, desc: "{modeIdOrName: 'on'|'off', ...}"]]],
            choosePerMode: [sub: "getChooseSwitch", custom: true, slots: [[name: "perMode", type: "Map", required: true, desc: "{modeIdOrName: {on: [deviceIds], off: [deviceIds]}, ...}"]]]
        ], common: [delay, raw]],
        dimmer: [actType: "dimmerActs", family: "device", actions: [
            setLevel: [sub: "getSetDimmer", custom: true, requires: [["level", "levelVariable"]], exclusive: [["level", "levelVariable"]], slots: [dev("dimA.@N"), [name: "level", key: "dimLA.@N", type: "Integer", desc: "0-100; or levelVariable"],
                       [name: "levelVariable", key: "uVar.@N", whenSet: true, type: "String"], [name: "levelVariable", key: "xVar.@N", type: "String", desc: "hub variable holding the level"], [name: "fadeSeconds", key: "dimRA.@N", type: "Integer"]]],
            toggle:   [sub: "getToggleDimmer", slots: [dev("dimA.@N"), [name: "level", key: "dimLA.@N", type: "Integer", required: true, desc: "0-100, the level when toggling on"], [name: "fadeSeconds", key: "dimRA.@N", type: "Integer"]]],
            adjust:   [sub: "getAdjustDimmer", slots: [dev("dimA.@N"), [name: "adjustBy", key: "dimAdj.@N", type: "Integer", required: true, desc: "-100..100"], [name: "fadeSeconds", key: "dimAdjR.@N", type: "Integer"]]],
            fade:     [sub: "getFadeDimmer", slots: [dev("dimFade.@N"), [name: "direction", key: "dimFadeUp.@N", type: "enum", values: ["raise", "lower"], direction: true, desc: "default lower"],
                       [name: "targetLevel", key: "dimFadeTarget.@N", type: "Integer", required: true, desc: "0-100"], [name: "minutes", key: "dimFadeTime.@N", type: "Integer", required: true, desc: "how long the fade takes"],
                       [name: "intervalSeconds", key: "dimFadeInterval.@N", type: "Integer"]]],
            stopFade: [sub: "getStopFade", slots: []],
            startRaiseLower: [sub: "getRLDimmer", slots: [[name: "direction", key: "dimRL.@N", type: "enum", values: ["raise", "lower"], direction: false, desc: "default lower"], dev("dimRaiseLower.@N")]],
            stopChanging: [sub: "getStopDimmer", slots: [dev("dimStop.@N")]],
            setLevelPerMode: [sub: "getDimmersPerMode", custom: true, slots: [dev("dimM.@N"), [name: "perMode", type: "Map", required: true, desc: "{modeIdOrName: level, ...}"], [name: "fadeSeconds", key: "dimMR.@N", type: "Integer"]]]
        ], common: [delay, raw]],
        color: [actType: "dimmerActs", family: "device", actions: [
            setColor:    [sub: "getSetColor", custom: true, requires: [["colorName", "rawSettings"]], slots: [dev("bulbs.@N"), [name: "colorName", key: "color.@N", type: "String", desc: "e.g. 'Red'; or a custom HSV color via rawSettings colorH.@N"], [name: "level", key: "colorLevel.@N", type: "Integer"]]],
            toggleColor: [sub: "getToggleColor", slots: [dev("bulbsTog.@N"), [name: "colorName", key: "colorTog.@N", type: "String", required: true, desc: "e.g. 'Red'"], [name: "level", key: "colorTogLevel.@N", type: "Integer", required: true, desc: "0-100"]]],
            setColorPerMode: [sub: "getColorPerMode", custom: true, slots: [dev("bulbsM.@N"), [name: "perMode", type: "Map", required: true, desc: "{modeIdOrName: {color: 'Red', level: 70}, ...}"]]]
        ], common: [delay, raw]],
        colorTemp: [actType: "dimmerActs", family: "device", actions: [
            setColorTemp:    [sub: "getSetColorTemp", slots: [dev("ct.@N"), [name: "kelvin", key: "ctL.@N", type: "Integer", required: true, desc: "e.g. 2700"], [name: "level", key: "ctLevel.@N", type: "Integer"]]],
            toggleColorTemp: [sub: "getToggleColorTemp", slots: [dev("ctTog.@N"), [name: "kelvin", key: "ctLTog.@N", type: "Integer", required: true, desc: "e.g. 2700"], [name: "level", key: "ctTogLevel.@N", type: "Integer"]]],
            fadeColorTemp:   [sub: "getFadeCT", slots: [dev("ctFade.@N"), [name: "direction", key: "ctFadeUp.@N", type: "enum", values: ["raise", "lower"], direction: true, desc: "default lower"],
                              [name: "targetKelvin", key: "ctFadeTarget.@N", type: "Integer", required: true], [name: "minutes", key: "ctFadeTime.@N", type: "Integer", required: true],
                              [name: "intervalSeconds", key: "ctFadeInterval.@N", type: "Integer"], [name: "changeLevel", key: "bothCTandL.@N", type: "Boolean"], [name: "targetLevel", key: "ctFadeLevel.@N", type: "Integer"]]],
            stopColorTempFade: [sub: "getStopCTFade", slots: []],
            setColorTempPerMode: [sub: "getColorTempPerMode", custom: true, slots: [dev("ctM.@N"), [name: "perMode", type: "Map", required: true, desc: "{modeIdOrName: {kelvin: 2700, level: 70}, ...}"]]]
        ], common: [delay, raw]],
        lock: [actType: "lockActs", family: "device", actions: [
            lock:   [sub: "getLULock", slots: [[key: "lockRL.@N", fixed: false], dev("lockLockUnlock.@N")]],
            unlock: [sub: "getLULock", slots: [[key: "lockRL.@N", fixed: true], dev("lockLockUnlock.@N")]]
        ], common: [delay, raw]],
        garageDoor: [actType: "lockActs", family: "device", actions: [
            open:  [sub: "getOCGarage", slots: [[key: "garageRL.@N", fixed: false], dev("garageOpenClose.@N")]],
            close: [sub: "getOCGarage", slots: [[key: "garageRL.@N", fixed: true], dev("garageOpenClose.@N")]]
        ], common: [delay, raw]],
        valve: [actType: "lockActs", family: "device", actions: [
            open:  [sub: "getOCValve", slots: [[key: "valveRL.@N", fixed: false], dev("valveOpenClose.@N")]],
            close: [sub: "getOCValve", slots: [[key: "valveRL.@N", fixed: true], dev("valveOpenClose.@N")]]
        ], common: [delay, raw]],
        thermostatController: [actType: "lockActs", sub: "getSetThermController", family: "device", notes: "Shown by RM only when Thermostat Controller 2.0 is installed; deviceIds are the temperature sensors the controller is set to use",
              slots: [[name: "controllerId", key: "thisApp.@N", type: "Integer", required: true, appChoice: true, desc: "a Thermostat Controller 2.0 instance id (hub_list_apps)"], dev("tcSensors.@N", "temperature sensors")], common: [delay, raw]],
        thermostatScheduler: [actType: "lockActs", sub: "getSetThermostatScheduler", family: "device", requires: [["deviceIds", "schedulerId"]], exclusive: [["deviceIds", "schedulerId"]],
              notes: "Shown by RM only when Thermostat Scheduler 2.0 or Legacy is installed. The scheduler is picked by its thermostats (deviceIds) or by schedulerId. A by-mode scheduler takes setSched4Mode, a by-time one setTimeAway and setSchedDay. RM's Set Mode Setpoints and Set Days/Times Setpoints commands are built in its own setpoint table editor, which this tool does not drive",
              slots: [[name: "schedulerId", key: "tMethod.@N", whenSet: true, type: "Integer"], [name: "schedulerId", key: "thisApp.@N", type: "Integer", appChoice: true, desc: "a Thermostat Scheduler 2.0 instance id; RM then fills the thermostats from it"],
                      [name: "deviceIds", key: "thisTherm.@N", type: "List<Integer>", desc: "the scheduler's thermostats"]],
              actions: [
            setEcoMode:    [slots: [tsCmd("setEcoMode"), [name: "ecoMode", key: "ecoMode.@N", type: "enum", values: ["on", "off"], required: true]]],
            setEcoOffset:  [requires: [["ecoOffset", "ecoOffsetVariable"]], exclusive: [["ecoOffset", "ecoOffsetVariable"]], slots: [tsCmd("setEcoOffset"),
                            [name: "ecoOffsetVariable", key: "uVar.@N", whenSet: true, type: "String"], [name: "ecoOffsetVariable", key: "xVar.@N", type: "String", desc: "a hub variable holding the offset"],
                            [name: "ecoOffset", key: "ecoOffset.@N", type: "Number"]]],
            setHold:       [slots: [tsCmd("setHold"), [name: "hold", key: "setHold.@N", type: "enum", values: ["on", "off"], required: true]]],
            setSched4Mode: [slots: [tsCmd("setSched4Mode"), [name: "modeId", key: "modN.@N", type: "Integer", required: true, appChoice: true, desc: "a mode the scheduler has setpoints for"]]],
            setTimeAway:   [requires: [["awayHeat", "awayHeatVariable", "awayCool", "awayCoolVariable"]], exclusive: [["awayHeat", "awayHeatVariable"], ["awayCool", "awayCoolVariable"]], slots: [tsCmd("setTimeAway"),
                            [name: "awayHeatVariable", key: "uVar2.@N", whenSet: true, type: "String"], [name: "awayHeatVariable", key: "xVar2.@N", type: "String", desc: "a hub variable holding the heating target"],
                            [name: "awayHeat", key: "timeAwayHeat.@N", type: "Number", desc: "heating target"],
                            [name: "awayCoolVariable", key: "uVar3.@N", whenSet: true, type: "String"], [name: "awayCoolVariable", key: "xVar3.@N", type: "String", desc: "a hub variable holding the cooling target"],
                            [name: "awayCool", key: "timeAwayCool.@N", type: "Number", desc: "cooling target"]]],
            setThermSched: [slots: [tsCmd("setThermSched")], notes: "activates the scheduled setpoints"],
            setSchedDay:   [slots: [tsCmd("setSchedDay"), [name: "day", key: "dayN.@N", type: "Integer", required: true, desc: "0 Monday .. 6 Sunday"]]]
        ], common: [delay, raw]],
        hsm: [actType: "lockActs", sub: "getSetHSM", family: "hub", custom: true, notes: "Shown by RM only when Hubitat Safety Monitor is installed",
              slots: [[name: "command", key: "alarm.@N", type: "enum", values: _rmHsmCommands(), required: true, desc: "Arm Away / Arm Home / Arm Night / Disarm / Re-Arm Water-Smoke-Gas-CO / Disarm All / Arm All HSM Rules / Cancel All Alerts; no armAll (use armRules)"]], common: [delay, raw]],
        thermostat: [actType: "lockActs", sub: "getSetThermostat", family: "device", custom: true, notes: "At least one setting is required",
              slots: [dev("thermo.@N"), [name: "mode", key: "thermoMode.@N", type: "String"], [name: "fanMode", key: "thermoFan.@N", type: "String"],
                       [name: "heatingSetpoint", key: "thermoSetHeat.@N", type: "Number"], [name: "adjustHeating", key: "thermoAdjHeat.@N", type: "Number"],
                       [name: "coolingSetpoint", key: "thermoSetCool.@N", type: "Number"], [name: "adjustCooling", key: "thermoAdjCool.@N", type: "Number"]], common: [delay, raw]],
        shade: [actType: "sceneActs", family: "device", actions: [
            open:  [sub: "getRLShade", slots: [[key: "shadeRL.@N", fixed: false], dev("shadeOpenClose.@N")]],
            close: [sub: "getRLShade", slots: [[key: "shadeRL.@N", fixed: true], dev("shadeOpenClose.@N")]],
            setPosition: [sub: "getShadePosition", slots: [dev("shadePosition.@N"), [name: "position", key: "shadeLevel.@N", type: "Integer", required: true, desc: "0-100"]]],
            stop:  [sub: "getStopShade", slots: [dev("shadeStop.@N")]]
        ], common: [delay, raw]],
        blind: [actType: "sceneActs", family: "device", actions: [
            setTilt: [sub: "getBlindTilt", requires: [["tilt", "tiltVariable"]], exclusive: [["tilt", "tiltVariable"]], slots: [dev("blindTilt.@N"),
                      [name: "tiltVariable", key: "uVar.@N", whenSet: true, type: "String"], [name: "tiltVariable", key: "xVar.@N", type: "String", desc: "hub variable holding the tilt"],
                      [name: "tilt", key: "tiltLevel.@N", type: "Integer", desc: "0-100; or tiltVariable"]]]
        ], common: [delay, raw]],
        scene: [actType: "sceneActs", family: "hub", notes: "Shown by RM only when a Groups and Scenes scene exists", actions: [
            activate: [sub: "getActivateScene", slots: [[name: "sceneIds", key: "sceneAct.@N", type: "List<Integer>", required: true, desc: "the scenes' activator device ids (the device each scene creates)"]]],
            activatePerMode: [sub: "getActivateScenePerMode", custom: true, slots: [[name: "perMode", type: "Map", required: true, desc: "{modeIdOrName: [scene activator device ids], ...}"]]]
        ], common: [delay, raw]],
        fan: [actType: "sceneActs", family: "device", actions: [
            setSpeed: [sub: "getFanSpeed", slots: [dev("fanDevice.@N"), [name: "speed", key: "fanSpeed.@N", type: "enum", values: ["low", "medium-low", "medium", "medium-high", "high", "on", "off", "auto"], required: true]]],
            cycle:    [sub: "getAdjustFan", slots: [dev("fanAdjust.@N")]]
        ], common: [delay, raw]],
        mode: [actType: "modeActs", sub: "getSetMode", family: "hub", custom: true, notes: "Exactly one of modeId or modeName",
               slots: [[name: "modeId", key: "mode.@N", type: "Integer"], [name: "modeName", type: "String", desc: "resolved to its id; unknown names are refused with the hub's list"]], common: [delay, raw]],
        setVariable: [actType: "modeActs", sub: "getSetVariable", family: "hub", custom: true,
               notes: "One source: value, sourceVariable, fromDevice or math. value, fromDevice and math need a Number or Decimal target; sourceVariable also copies into a String target. Also accepted as capability 'variable'.",
               slots: [[name: "variable", key: "xVarV.@N", type: "String", required: true, desc: "target hub variable; must exist"],
                        [name: "value", key: "valNumber.@N", type: "Number"], [name: "numOp", key: "numOp.@N", type: "enum", values: ["number", "add number"], desc: "with value only; 'add number' adds to the variable"],
                        [name: "sourceVariable", type: "String", desc: "hub variable to copy from"],
                        [name: "fromDevice", type: "Map", desc: "{deviceId, attribute}; the attribute must be one RM lists for the device"],
                        [name: "math", type: "Map", desc: "{left, op, right}; operands are a Number or a variable name; binary + - * / % need right, unary negate absolute round random sqrt sin cos tan asin acos atan log toRadians toDegrees take none"]],
               common: [delay, raw]],
        setLocalVariable: [actType: "modeActs", sub: "getSetVariable", family: "hub", custom: true,
               notes: "As setVariable, for one of this rule's local variables (create it with addLocalVariable first); sources may be local or hub variables",
               slots: [[name: "variable", key: "xVarV.@N", type: "String", required: true], [name: "value", type: "Number"], [name: "numOp", type: "enum", values: ["number", "add number"], desc: "with value only; 'add number' adds to the variable"],
                        [name: "sourceVariable", type: "String"], [name: "fromDevice", type: "Map"], [name: "math", type: "Map"]], common: [delay, raw]],
        runCommand: [actType: "modeActs", sub: "getDefinedAction", family: "device", custom: true, notes: "Any command of the selected devices, e.g. flashOff or a driver's custom command",
               requires: [["deviceIds", "useLastEventDevice"]],
               slots: [[name: "command", key: "cCmd.@N", type: "String", required: true, desc: "checked against the commands RM lists for the devices"], [name: "deviceIds", key: "devices.@N", type: "List<Integer>", desc: "required unless useLastEventDevice"],
                        [name: "capabilityFilter", key: "myCapab.@N", type: "String", desc: "a capability RM's picker offers, by key or label (e.g. SwitchLevel or Dimmer); default Switch"],
                        [name: "parameters", type: "List", desc: "each {type, value} or {type, variable}; type number, decimal or string"],
                        [name: "useLastEventDevice", key: "useLastDev.@N", type: "Boolean"]], common: [delay, raw]],
        fileWrite:  [actType: "modeActs", sub: "getWriteLocalFile", family: "hub", slots: [[name: "fileName", key: "localFile.@N", type: "String", required: true, dflt: ""], [name: "content", key: "fileContents.@N", type: "String", dflt: ""]], common: [delay, raw]],
        fileAppend: [actType: "modeActs", sub: "getAppendLocalFile", family: "hub", notes: "The file must exist", slots: [[name: "fileName", key: "localFile.@N", type: "String", required: true, dflt: ""], [name: "content", key: "fileContents.@N", type: "String", dflt: ""]], common: [delay, raw]],
        fileDelete: [actType: "modeActs", sub: "getDeleteLocalFile", family: "hub", notes: "The file must exist", slots: [[name: "fileName", key: "deleteFile.@N", type: "String", required: true, dflt: ""]], common: [delay, raw]],
        zwavePoll: [actType: "deviceActs", sub: "getStartStopZPoll", family: "device",
               actions: [start: [slots: [[key: "ssZ.@N", fixed: true]]], stop: [slots: [[key: "ssZ.@N", fixed: false]]]],
               slots: [[name: "target", key: "ssSD.@N", type: "enum", values: ["switches", "dimmers"], trueWhen: "switches", dflt: "switches", desc: "default switches"],
                        [name: "deviceIds", key: "ssZPoll.@N", type: "List<Integer>", desc: "Z-Wave devices"]], common: [raw]],
        button: [actType: "switchActs", family: "device", actions: [
            push: [sub: "getPushButton", slots: [dev("pushButton.@N"), [name: "buttonNumber", key: "pushButNo.@N", type: "Integer", required: true], [name: "operation", key: "pushButOp.@N", type: "enum", values: ["push", "hold", "doubleTap", "release"], dflt: "push"]]],
            pushPerMode:   [sub: "getPushButtonPerMode", custom: true, slots: [dev("pushMBtn.@N"), [name: "perMode", type: "Map", required: true, desc: "{modeIdOrName: buttonNumber, ...}"]]],
            choosePerMode: [sub: "getChooseButton", custom: true, slots: [[name: "perMode", type: "Map", required: true, desc: "{modeIdOrName: [deviceIds], ...}"], [name: "buttonNumber", key: "chooseButtonNum.@N", type: "Integer", required: true]]]
        ], common: [raw]],
        log:          [actType: "messageActs", sub: "getLogMsg", family: "messaging", slots: [[name: "message", key: "logmsg.@N", type: "String", required: true, dflt: ""]], common: [delay, raw]],
        notification: [actType: "messageActs", sub: "getMsg", family: "messaging", slots: [[name: "message", key: "msg.@N", type: "String", required: true, dflt: ""], dev("note.@N", "notification devices")], common: [delay, raw]],
        httpGet:  [actType: "messageActs", sub: "getHTTPGet", family: "messaging", slots: [[name: "url", key: "httper.@N", type: "String", required: true, dflt: ""]], common: [delay, raw]],
        httpPost: [actType: "messageActs", sub: "getHTTPPost", family: "messaging", slots: [[name: "url", key: "httper.@N", type: "String", required: true, dflt: ""], [name: "body", key: "httpPostBody.@N", type: "String", required: true, dflt: ""],
                   [name: "contentType", key: "httpPostType.@N", type: "String", dflt: "application/x-www-form-urlencoded"]], common: [delay, raw]],
        ping:     [actType: "messageActs", sub: "getPingIP", family: "messaging", slots: [[name: "ip", key: "pingIP.@N", type: "String", required: true, dflt: ""]], common: [raw]],
        volume: [actType: "soundActs", sub: "getSetVolume", family: "media", slots: [dev("volume.@N"), [name: "level", key: "volumeVal.@N", type: "Integer", required: true, desc: "0-100"]], common: [delay, raw]],
        mute: [actType: "soundActs", family: "media", actions: [
            mute:   [sub: "getMuteUnmute", slots: [[key: "mU.@N", fixed: true], dev("muteUnmute.@N")]],
            unmute: [sub: "getMuteUnmute", slots: [[key: "mU.@N", fixed: false], dev("muteUnmute.@N")]]
        ], common: [delay, raw]],
        chime: [actType: "soundActs", sub: "getChime", family: "media", slots: [dev("chime.@N"), [name: "playStop", key: "chimePlayStop.@N", type: "String"], [name: "soundNumber", key: "chimePlaySound.@N", type: "Integer"]], common: [raw]],
        siren: [actType: "soundActs", sub: "getSiren", family: "media", slots: [dev("siren.@N"), [name: "sirenAction", key: "sirenAct.@N", type: "String"]], common: [raw]],
        tone: [actType: "soundActs", sub: "getTone", family: "media", slots: [dev("toner.@N")], common: [delay, raw]],
        musicPlayer: [actType: "soundActs", sub: "getSetMusicPlayer", family: "media", exclusive: [["volume", "volumeVariable"]], notes: "RM offers play track and restore / play text and restore when the first selected player supports them; track, text and volume apply to the commands that take them",
              slots: [dev("player.@N"), [name: "command", key: "playerCommand.@N", type: "enum", required: true,
                      values: ["play", "pause", "toggle play/pause", "stop", "mute", "unmute", "toggle mute", "raise volume", "lower volume", "set volume", "next track", "previous track", "play track", "restore track", "resume track", "set track", "play text", "play track and restore", "play text and restore"]],
                      [name: "track", key: "playerTrack.@N", type: "String", desc: "play track, restore track, resume track, set track, play track and restore"],
                      [name: "text", key: "playerText.@N", type: "String", desc: "play text, play text and restore"],
                      [name: "volumeVariable", key: "uVar.@N", whenSet: true, type: "String"], [name: "volumeVariable", key: "xVar.@N", type: "String", desc: "hub variable holding the volume"],
                      [name: "volume", key: "playerLevel.@N", type: "Integer", desc: "0-100: set volume, play track and restore, play text and restore; or volumeVariable"]], common: [delay, raw]],
        privateBoolean: [actType: "rulesActs", sub: "getSetPrivateBoolean", family: "rules", custom: true,
               notes: "The raw setting pvTF.<N> stores the inverse of value; read the rendered action, not the raw field",
               slots: [[name: "ruleIds", key: "privateT.@N", type: "List<Integer | \"*\">", required: true, desc: "\"*\" is this rule"], [name: "value", key: "pvTF.@N", type: "Boolean", required: true]], common: [raw]],
        runRule:      [actType: "rulesActs", sub: "getRuleActions", family: "rules", custom: true, slots: [[name: "ruleIds", key: "ruleAct.@N", type: "List<Integer>", required: true]], common: [raw]],
        cancelTimers: [actType: "rulesActs", sub: "getStopActions", family: "rules", custom: true, slots: [[name: "ruleIds", key: "stopAct.@N", type: "List<Integer>", required: true]], common: [raw]],
        pauseRule: [actType: "rulesActs", sub: "getPauseResumeRules", family: "rules", custom: true, notes: "The raw setting pR.<N> is true for resume",
               actions: [pause: [slots: [[key: "pR.@N", fixed: false]]], resume: [slots: [[key: "pR.@N", fixed: true]]]],
               slots: [[name: "ruleIds", key: "pauseRule.@N", type: "List<Integer>", required: true]], common: [raw]],
        capture: [actType: "deviceActs", sub: "getCapture", family: "device-control", slots: [dev("capture.@N")], common: [raw]],
        restore: [actType: "deviceActs", sub: "getRestore", family: "device-control", notes: "Restores the captured state", slots: [], common: [raw]],
        refresh: [actType: "deviceActs", sub: "getRefreshSwitch", family: "device-control", slots: [dev("refresh.@N")], common: [raw]],
        poll:    [actType: "deviceActs", sub: "getPollSwitch", family: "device-control", slots: [dev("poll.@N")], common: [raw]],
        disableDevice: [actType: "deviceActs", family: "device-control", actions: [
            disable: [sub: "getDisable", slots: [[key: "disEn.@N", fixed: false], dev("devDisable.@N")]],
            enable:  [sub: "getDisable", slots: [[key: "disEn.@N", fixed: true], dev("devDisable.@N")]]
        ], common: [raw]],
        delay: [actType: "delayActs", sub: "getDelay", family: "flow", custom: true, notes: "hours, minutes and/or seconds, or variable",
               slots: [[name: "hours", key: "delayHour.@N", type: "Integer"], [name: "minutes", key: "delayMinute.@N", type: "Integer"], [name: "seconds", key: "delaySecond.@N", type: "Integer"],
                        [name: "variable", key: "xVar.@N", type: "String", desc: "hub variable holding the seconds"], [name: "cancelable", key: "cancelAct.@N", type: "Boolean"], [name: "random", key: "randomAct.@N", type: "Boolean"]]],
        delayPerMode: [actType: "delayActs", sub: "getDelayPerMode", family: "flow", custom: true, slots: [[name: "perMode", type: "Map", required: true, desc: "{modeIdOrName: {hours?, minutes?, seconds?}, ...}"]], common: [raw]],
        cancelDelay: [actType: "delayActs", sub: "getCancelDelay", family: "flow", slots: []],
        exitRule:    [actType: "delayActs", sub: "getExitRule", family: "flow", slots: []],
        comment:     [actType: "delayActs", sub: "getComment", family: "flow", slots: [[name: "text", key: "comment.@N", type: "String", required: true, nonBlank: true, desc: "the comment itself"]]],
        repeat: [actType: "repeatActs", sub: "getRepeat", family: "flow", custom: true, notes: "hours, minutes and/or seconds are required; times alone is not enough",
               slots: [[name: "hours", key: "repeatHour.@N", type: "Integer"], [name: "minutes", key: "repeatMinute.@N", type: "Integer"], [name: "seconds", key: "repeatSecond.@N", type: "Integer"],
                        [name: "times", key: "repeatN.@N", type: "Integer"], [name: "stoppable", key: "stopRepeat.@N", type: "Boolean"]]],
        stopRepeat:  [actType: "repeatActs", sub: "getStopRepeat", family: "flow", slots: []],
        repeatWhile: [actType: "repeatActs", sub: "getWhile", family: "flow", custom: true,
               slots: [[name: "expression", type: "Map", required: true, desc: "{conditions: [...], operator?: 'AND'|'OR'|'XOR', operators?: [...]}"],
                        [name: "hours", key: "repeatHour.@N", type: "Integer"], [name: "minutes", key: "repeatMinute.@N", type: "Integer"], [name: "seconds", key: "repeatSecond.@N", type: "Integer"],
                        [name: "times", key: "repeatN.@N", type: "Integer"], [name: "stoppable", key: "stopRepeat.@N", type: "Boolean"]]],
        waitEvents: [actType: "delayActs", sub: "getWaitEvents", family: "flow", custom: true, notes: "One per rule: RM keeps the event rows per rule",
               slots: [[name: "events", type: "List", required: true, desc: "[{capability, deviceIds?, state?, modeIds?, andStays?: true|{hours,minutes,seconds}}, ...]"], delay]],
        waitExpression: [actType: "delayActs", sub: "getWaitRule", family: "flow", custom: true,
               slots: [[name: "expression", type: "Map", required: true, desc: "{conditions: [...], operator?, operators?}"], [name: "useDuration", key: "durChoice.@N", type: "Boolean"], [name: "delay", type: "Map", desc: "timeout {hours?, minutes?, seconds?} or {variable}"]]],
        returnValue: [actType: "modeActs", sub: "getReturnValue", family: "flow", custom: true, requires: [["value", "variable"]], exclusive: [["value", "variable"]],
              notes: "Shown by RM only in a rule that returns a value (a rule function). A zero or empty literal is refused: RM shows no row for it",
              slots: [[name: "type", key: "valType.@N", type: "enum", values: ["String", "Integer", "Decimal", "Boolean"], required: true], [name: "value", type: "String", desc: "a literal of that type; Boolean takes true or false"],
                      [name: "variable", key: "valVar.@N", type: "String", appChoice: true, desc: "a hub variable of that type; not for String"]], common: [delay, raw]],
        roomLights: [actType: "rulesActs", family: "rules", notes: "Shown by RM only when a Room Lights instance exists", actions: [
            activate: [sub: "getRLActivate", slots: [[name: "roomLightsId", key: "actRL.@N", type: "Integer", required: true, appChoice: true, desc: "a Room Lights instance id (hub_list_apps)"],
                       [name: "modeOrPeriod", key: "modeRL.@N", type: "String", dflt: "X", appChoice: true, desc: "'X' (default) for the current mode or period, else a mode id or period name the instance defines"],
                       [name: "useConditions", key: "forceRL.@N", type: "Boolean", desc: "true applies the instance's own conditions; default false forces activation"]]],
            turnOff:  [sub: "getRLTurnOff", slots: [[name: "roomLightsIds", key: "actOffRL.@N", type: "List<Integer>", required: true]]]
        ], common: [delay, raw]],
        ifThen: [actType: "condActs", sub: "getIfThen", family: "flow", custom: true, slots: [[name: "expression", type: "Map", required: true, desc: "{conditions: [...], operator?, operators?}"]]],
        elseIf: [actType: "condActs", sub: "getElseIf", family: "flow", custom: true, slots: [[name: "expression", type: "Map", required: true, desc: "{conditions: [...], operator?, operators?}"]]],
        "else": [actType: "condActs", sub: "getElse", family: "flow", slots: []],
        endIf:  [actType: "condActs", sub: "getEndIf", family: "flow", slots: []]
    ]
    }
    return table()
}

// The per-condition fields an expression's conditions[] takes; shared with the trigger schema.
private List _rmActionConditionFields() {
    [[name: "not", type: "Boolean", description: "Applies inside an expression condition list (ifThen/elseIf/repeatWhile/waitExpression conditions[], and addRequiredExpression.conditions[]). not:true negates the condition."]]
}

// Discover: the catalog as a machine-readable schema. One entry per capability, with its actions,
// the fields every action takes (requiredFields), the rest (optionalFields) with conditionalRequired
// naming the fields each action needs, the sub-type RM stores for each action, and an example.
private Map _rmActionSchemaForDiscover() {
    def entries = []
    _rmActionCatalog().each { String cap, Map entry ->
        def perAction = (entry.actions instanceof Map) ? (entry.actions as Map) : [(null): entry]
        def actions = perAction.keySet().findAll { it != null }.collect { it.toString() }
        def fieldSpecs = [:]
        def requiredBy = [:]
        perAction.each { act, Map ae ->
            def fieldList = (act == null) ? ((entry.slots ?: []) as List) : (((entry.slots ?: []) as List) + ((ae.slots ?: []) as List))
            fieldList.each { Map f ->
                if (f.name == null) return
                def spec = fieldSpecs.get(f.name)
                if (spec == null) {
                    spec = [name: f.name, type: f.type]
                    if (f.values instanceof List) spec.values = f.values
                    if (f.desc) spec.description = f.desc
                    fieldSpecs.put(f.name, spec)
                } else if (f.desc && !spec.description) spec.description = f.desc
                if (f.required) requiredBy.put(f.name, (requiredBy.get(f.name) ?: []) + [act])
            }
        }
        def required = []
        def optional = []
        def conditional = [:]
        if (actions) required << [name: "action", type: "enum", values: actions]
        fieldSpecs.each { name, Map spec ->
            def by = requiredBy.get(name) ?: []
            if (by.size() == perAction.size()) required << spec
            else {
                by.each { act -> conditional.put(act.toString(), ((conditional.get(act.toString()) ?: []) as List) + [name.toString()]) }
                optional << spec
            }
        }
        perAction.each { act, Map ae ->
            if (act == null || !ae.requires) return
            conditional.put(act.toString(), ((conditional.get(act.toString()) ?: []) as List) + (ae.requires as List))
        }
        (entry.common ?: []).each { Map c -> optional << [name: c.name, type: c.type] + (c.desc ? [description: c.desc] : [:]) }
        if (!_rmNoConditionRow(entry.actType?.toString(), entry.sub?.toString())) optional << [name: "condition", type: "Map", description: "makes the row a Simple Conditional Action: a condition spec as in expressions, or {definedIndex} for a condition the rule already defines; the action runs only if it is true when reached"]
        def subTypes = (entry.actions instanceof Map) ? perAction.collectEntries { act, Map ae -> [(act.toString()): ae.sub ?: entry.sub] } : entry.sub
        def example = [capability: cap]
        if (actions) example.action = actions[0]
        required.each { Map r -> if (r.name != "action") example.put(r.name, _rmExampleValue(r)) }
        if (actions) fieldSpecs.each { name, Map spec -> if ((requiredBy.get(name) ?: []).contains(actions[0])) example.put(name, _rmExampleValue(spec)) }
        // A one-of requirement is shown with its first field.
        (((entry.requires ?: []) as List) + ((actions ? (perAction[actions[0]]?.requires ?: []) : []) as List)).each { r ->
            def n = (r instanceof List) ? (r as List)[0] : r
            if (fieldSpecs.containsKey(n) && !example.containsKey(n)) example.put(n, _rmExampleValue(fieldSpecs.get(n) as Map))
        }
        // A custom mapper's own requirements, so every served example maps through its action.
        def ownExample = _rmActionExampleFields().get(cap)
        if (ownExample instanceof Map) example.putAll(ownExample as Map)
        def out = [name: cap, family: entry.family, actType: entry.actType, actSubType: subTypes, requiredFields: required, optionalFields: optional, example: example]
        if (actions) out.actions = actions
        if (conditional) out.conditionalRequired = conditional
        if (entry.requires) out.requires = entry.requires
        def exclusive = (((entry.exclusive ?: []) as List) + perAction.collectMany { act, Map ae -> act == null ? [] : ((ae.exclusive ?: []) as List) }).unique()
        if (exclusive) out.exclusive = exclusive
        def notes = [entry.notes] + perAction.collect { act, Map ae -> ae.notes ? "${act}: ${ae.notes}".toString() : null }
        notes = notes.findAll { it }
        if (notes) out.notes = notes.join(". ")
        entries << out
    }
    return [discriminator: "capability", note: "Pass capability (case-insensitive) and, for a capability with actions, action; the other fields are per entry. A spec RM cannot bake is refused before any write, or rolled back when only RM's editor can tell.",
            conditionFields: _rmActionConditionFields(), capabilities: entries]
}

private Object _rmExampleValue(Map field) {
    if (field.values instanceof List) return field.values[0]
    switch (field.type?.toString()) {
        case "List<Integer>": return [123]
        case ~/List.*/: return ["*"]
        case "Integer": case "Number": return 50
        case "Boolean": return true
        case "Map": return [:]
        default: return "..."
    }
}

// Rule Machine's action sub-type -> the device capability its picker lists (its subActCapab).
private Map _rmSubActCapab() {
    [getOnOffSwitch: "Switch", getToggleSwitch: "Switch", getFlashSwitch: "Switch", getModeSwitch: "Switch", getChooseSwitch: "Switch",
     getPushButton: "PushableButton", getPushButtonPerMode: "PushableButton", getStartStopZPoll: "Switch", getChooseButton: "PushableButton",
     getSetDimmer: "SwitchLevel", getToggleDimmer: "SwitchLevel", getAdjustDimmer: "SwitchLevel", getDimmersPerMode: "SwitchLevel",
     getFadeDimmer: "SwitchLevel", getRLDimmer: "SwitchLevel", getStopDimmer: "SwitchLevel", getBlindTilt: "WindowBlind",
     getSetColor: "ColorControl", getToggleColor: "ColorControl", getStopFade: "SwitchLevel", getColorPerMode: "ColorControl",
     getSetColorTemp: "ColorTemperature", getToggleColorTemp: "ColorTemperature", getColorTempPerMode: "ColorTemperature",
     getFadeCT: "ColorTemperature", getStopCTFade: "ColorTemperature", getFanSpeed: "FanControl", getAdjustFan: "FanControl",
     getOCGarage: "GarageDoorControl", getLULock: "Lock", getOCValve: "Valve", getSetThermostat: "Thermostat",
     getSetMusicPlayer: "MusicPlayer", getTone: "Tone", getChime: "Chime", getSetVolume: "AudioVolume", getMuteUnmute: "AudioVolume",
     getSiren: "Alarm"]
}

// Simple Conditional Action (getCondAct): the row first takes a condition, one the rule already
// defines (useCond / useCondNdx) or a new one, then "Done with this condition" (hasAll) clears the
// row's type pickers so the action itself is chosen next on the same row. The caller's catch
// rolls the row back on a throw. Returns the index of a condition it defined, else null.
private Integer _rmWriteSimpleConditionalPreamble(Integer appId, Integer idx, String cap, Map cond, List applied, List skipped, List condIdxOut = null) {
    _rmWriteSettingOnPage(appId, "doActPage", "actType.${idx}", "condActs", applied, null, skipped)
    _rmWriteSettingOnPage(appId, "doActPage", "actSubType.${idx}", "getCondAct", applied, null, skipped)
    _rmRequireOfferedActionSubType("${cap}.condition".toString(), "getCondAct", idx, skipped)
    if (cond.definedIndex != null) {
        _rmWriteSettingOnPage(appId, "doActPage", "useCond.${idx}", true, applied, "bool", skipped)
        _rmWriteSettingOnPage(appId, "doActPage", "useCondNdx.${idx}", cond.definedIndex, applied, null, skipped)
        def miss = skipped.find { it instanceof Map && it.key?.toString() == "useCondNdx.${idx}".toString() && it.reason in ["not_offered", "not_in_schema"] }
        if (miss) throw new IllegalArgumentException("${cap}.condition.definedIndex ${cond.definedIndex} is not a condition this rule defines${miss.offered ? ' (defined: ' + (miss.offered as List).join(', ') + ')' : ''}.".toString())
        _rmClickAppButton(appId, "hasAll", null, "doActPage")
        return null
    } else {
        String ccap = cond.capability.toString().trim()
        def cInputs = (_rmFetchConfigJson(appId, "doActPage")?.configPage?.sections ?: []).collectMany { it?.input ?: [] }
        def rCapabInput = cInputs.find { it?.name?.toString()?.startsWith("rCapab_") }
        if (!rCapabInput) {
            throw new IllegalStateException("${cap}.condition: rCapab_<N> not in doActPage schema after actSubType=getCondAct; got ${cInputs.collect { it?.name }.findAll { it }.join(', ')}".toString())
        }
        def m = rCapabInput.name.toString() =~ /rCapab_(\d+)/
        Integer cIdx = m ? ((m[0] as List)[1] as Integer) : null
        if (cIdx == null) throw new IllegalStateException("${cap}.condition: couldn't parse the condition index from '${rCapabInput.name}'".toString())
        def capOptions = (rCapabInput.options ?: []) as List
        def capCanonical = capOptions.find { it.toString().equalsIgnoreCase(ccap) }
        if (!capCanonical) {
            throw new IllegalArgumentException("${cap}.condition.capability '${ccap}' is not in Rule Machine's condition list on this hub. RM lists: ${capOptions.join(', ')}.".toString())
        }
        def writeAct = { Map params, String fieldKey, Object fieldValue, String label = null ->
            _rmWriteSettingOnPage(appId, "doActPage", fieldKey, fieldValue, applied, null, skipped)
        }
        if (condIdxOut != null) condIdxOut[0] = cIdx
        boolean cancelFailed = false
        def cancel = {
            try { _rmClickAppButton(appId, "cancelCapab", null, "doActPage") } catch (Exception cancelExc) {
                cancelFailed = true
                mcpLog("warn", "rm-native", "cancelCapab cleanup failed for app ${appId} on doActPage (simple conditional action ${idx}): ${cancelExc.message}")
            }
        }
        try {
            _rmWalkConditionReveal(appId, [page: "doActPage", writeST: writeAct, cancelInFlightCondition: cancel, condIdx: 0, cap: "${cap}.condition".toString(),
                                           capCanonical: capCanonical, hrefParams: [unUsed: null], applied: applied, skipped: skipped], cond, cIdx)
        } catch (Exception walkExc) {
            if (cancelFailed) throw _rmFail(new IllegalStateException("${walkExc.message} [wizardStuck -- cancelCapab cleanup also failed on doActPage; close the condition editor with hub_set_rule(button='cancelCapab', pageName='doActPage', confirm=true)]".toString(), walkExc), [wizardStuck: true])
            throw walkExc
        }
        // The render that handles Done clears the type pickers; only the next one shows them again.
        _rmFetchConfigJson(appId, "doActPage")
        return cIdx
    }
}

// A refused Simple Conditional Action leaves the condition it defined in the rule's pool, listed by
// the actions page's Manage Conditions (pred:false), where its bin button removes it on the next render.
// Returns true when the condition is confirmed gone; a condition left behind is named in the response.
private boolean _rmDropSimpleCondition(Integer appId, Integer condIdx) {
    if (condIdx == null) return true
    try {
        _rmNavigateToPage(appId, "selectActions", "selectConditions", 0, "name", [pred: false])
        _rmClickAppButton(appId, condIdx.toString(), "deleteCon", "selectConditions", null)
        _rmFetchConfigJson(appId, "selectConditions")
        if (_rmFetchSettingsByName(appId)?.containsKey("rCapab_${condIdx}".toString())) {
            mcpLog("warn", "rm-native", "condition ${condIdx} of a refused simple conditional action is still defined in app ${appId}")
            return false
        }
        return true
    } catch (Exception e) {
        mcpLog("warn", "rm-native", "removing condition ${condIdx} of a refused simple conditional action from app ${appId} failed (${e.message})")
        return false
    }
}

// The leftover-condition sentence for a refusal whose condition could not be removed.
private String _rmSimpleConditionLeftNote(Integer condIdx) {
    " The condition created for this action (id ${condIdx}) stays in the rule's conditions as unused; Manage Conditions in the Rule Machine UI can delete it.".toString()
}

// Re-raise a refusal after the row cleanup, naming a condition the cleanup could not remove.
private void _rmRethrowWithConditionNote(Exception refusal, Integer condIdx, boolean dropped) {
    if (dropped) throw refusal
    Map flags = _rmOutcomeFlags(refusal) + [fieldsWritten: true]
    throw _rmFail(new IllegalStateException("${(refusal.message ?: refusal.toString()).replace(' RM is not touched.', '')}${_rmSimpleConditionLeftNote(condIdx)}".toString(), refusal), flags)
}

// RM prunes its action sub-type picker to what the hub has (pruneSubTypes: an installed app such as
// Hubitat Safety Monitor, Scenes, Room Lights or a thermostat app, or a device with the capability).
// A sub-type the picker does not list is refused with what it does; the caller rolls the row back.
private void _rmRequireOfferedActionSubType(String cap, String actSubType, Integer idx, List skipped) {
    def miss = skipped.find { it instanceof Map && it.key?.toString() == "actSubType.${idx}".toString() && it.reason == "not_offered" }
    if (miss == null) return
    if (actSubType == "getCondAct") {
        throw new IllegalArgumentException("${cap}: Rule Machine does not offer a Simple Conditional Action on this row. This row's picker offers: ${(miss.offered as List).join(', ')}.".toString())
    }
    throw new IllegalArgumentException("addAction: Rule Machine's action picker on this hub does not offer '${cap}' (${actSubType}). RM lists an action only when the hub has what it needs: the built-in app it drives (Hubitat Safety Monitor, Scenes, Room Lights, Thermostat Scheduler or Controller), a device with the capability, or, for returnValue, a rule that returns a value. This hub's picker offers: ${(miss.offered as List).join(', ')}.".toString())
}

// A choice RM's picker does not list (a player command the player lacks, a scene or app instance
// that is not there) leaves a row RM does not render; the caller's catch rolls it back. The writer
// checks single values; list values and every appChoice value are checked here against the live picker.
private void _rmRequireOfferedCatalogChoice(Integer appId, String cap, String action, Integer idx, Map fields, List skipped) {
    Map entry = _rmActionCatalog()[cap == "variable" ? "setVariable" : cap] as Map
    Map ae = (entry?.actions instanceof Map) ? ((entry.actions as Map)[action] as Map) : null
    List slots = ((entry?.slots ?: []) as List) + ((ae?.slots ?: []) as List)
    def keyOf = { Object k -> k.toString().replace("@N", idx.toString()) }
    def nameOf = { String key -> slots.find { it.key && keyOf(it.key) == key }?.name ?: key }
    def refuse = { String key, Object value, List offered ->
        boolean fixed = slots.find { it.key && keyOf(it.key) == key }?.containsKey("fixed")
        String what = fixed ? action : "${value} for ${nameOf(key)}"
        throw new IllegalArgumentException("${cap}${action ? '.' + action : ''}: Rule Machine does not offer ${what} on this hub; it offers: ${offered ? offered.join(', ') : '(nothing)'}.".toString())
    }
    def miss = skipped.find { it instanceof Map && it.reason == "not_offered" && fields.keySet().any { k -> keyOf(k) == it.key?.toString() } }
    if (miss != null) refuse(miss.key.toString(), "'${miss.value}'", (miss.offered ?: []) as List)
    // appChoice: a picker of hub data that can be empty (app instances, their modes or periods, hub variables).
    Map checked = fields.findAll { k, v ->
        (v instanceof List && !(nameOf(keyOf(k)) in ["deviceIds", "ruleIds"])) || slots.find { it.key?.toString() == k.toString() }?.appChoice
    }
    if (checked.isEmpty()) return
    def schema = _rmCollectInputSchema(_rmFetchConfigJson(appId, "doActPage")?.configPage)
    checked.each { k, v ->
        def field = schema?.get(keyOf(k))
        if (field?.type != "enum" || field.options == null) return
        List offered = _rmReadPickerOptionStrings(field)
        def bad = ((v instanceof List) ? (v as List) : [v]).findAll { !(it?.toString() in offered) }
        if (bad) refuse(keyOf(k), bad.join(', '), offered)
    }
}

// Every device of an action must have the capability RM's picker for that action lists; RM stores
// any id and renders the row, but the command then has no implementation to run. Skipped (named in
// checksSkipped) when the device list cannot be read; a device absent from it is left to the existence
// check, and one listed with no capabilities passes (the feed cannot see into it).
private void _rmRequireDeviceCapability(String label, Object ids, String subType) {
    _rmRequireDevicesWithCapability(label, ids, subType ? _rmSubActCapab()[subType] : null)
}

// A trigger's or condition's devices must have the capability RM's picker for that capability
// lists (the deviceCapability table), the same way an action's devices must.
private void _rmRequireConditionDeviceCapability(String label, Map spec) {
    String rmCap = spec?.capability?.toString()?.trim()
    if (!rmCap) return
    Map table = _rmCondTables().deviceCapability as Map
    def hit = table.find { k, v -> k.toString().equalsIgnoreCase(rmCap) }
    if (hit == null) return
    _rmRequireDevicesWithCapability(label, spec.deviceIds ?: (spec.deviceId != null ? [spec.deviceId] : null), hit.value?.toString())
}

private void _rmRequireDevicesWithCapability(String label, Object ids, String cap) {
    if (cap == null || !(ids instanceof List) || (ids as List).isEmpty()) return
    def byId = _memoDeviceList()
    if (byId == null) {
        byId = [:]
        def parsed
        try { parsed = new groovy.json.JsonSlurper().parseText(hubInternalGet("/hub2/vrb/devices") ?: "[]") } catch (Exception e) {
            mcpLog("warn", "rm-native", "${label}: device capabilities could not be read (${e.message ?: e}); the ${cap} check is skipped for this call")
            _memoKeepDeviceList([:])
            _memoNoteSkippedCheck("device capabilities (the device list could not be read)")
            return
        }
        if (!(parsed instanceof List)) {
            mcpLog("warn", "rm-native", "${label}: the device list had an unexpected shape (${_typeName(parsed)}); the ${cap} check is skipped for this call")
            _memoKeepDeviceList([:])
            _memoNoteSkippedCheck("device capabilities (the device list had an unexpected shape)")
            return
        }
        parsed.each { d ->
            if (d instanceof Map && d.id != null) byId.put(d.id.toString(), [label: d.label, caps: ((d.capabilities ?: []) as List).collect { it?.toString()?.toLowerCase() }])
        }
        _memoKeepDeviceList(byId)
    }
    if (byId.isEmpty()) return
    // An empty capability list is a device the feed cannot see into (as the devices library reads it): unknown, not lacking.
    def lacking = (ids as List).findAll { id -> def d = byId.get(id?.toString()); d != null && d.caps && !(cap.toLowerCase() in d.caps) }
    if (lacking) {
        throw new IllegalArgumentException("${label}: ${lacking.collect { id -> "device ${id} (${byId.get(id.toString()).label})" }.join(', ')} ${lacking.size() == 1 ? 'does' : 'do'} not have the ${cap} capability Rule Machine's device picker here lists. RM is not touched.".toString())
    }
}

// Refuses a value outside the field's enum (case and outer spaces ignored).
private void _rmRequireCatalogValue(String label, Map f, Object value) {
    if (value == null || !(f.values instanceof List)) return
    if (_rmCatalogValueCanonical(f, value) == null) {
        throw new IllegalArgumentException("${label}: '${f.name}' must be one of ${(f.values as List).join(', ')} (got '${value}').".toString())
    }
}

// The enum value in the catalog's spelling (case and outer spaces ignored), or null when it is not one.
private Object _rmCatalogValueCanonical(Map f, Object value) {
    if (!(f.values instanceof List)) return value
    String wanted = value?.toString()?.trim()
    return (f.values as List).find { it.toString().equalsIgnoreCase(wanted) }
}

// Maps a catalog-described action to RM's settings. The custom entries are handled by the code
// that follows in _rmMapActionSpec; this covers every entry the catalog fully describes.
private Map _rmMapCatalogAction(String cap, String action, Map actionSpec, Map entry, Map actionEntry) {
    def fields = [:]
    String label = action ? "${cap}.${action}" : "${cap} action"
    def fieldList = (actionEntry.is(entry)) ? ((entry.slots ?: []) as List) : (((entry.slots ?: []) as List) + ((actionEntry.slots ?: []) as List))
    fieldList.each { Map f ->
        def key = f.key?.toString()
        if (key == null) return
        def value
        if (f.containsKey("fixed")) value = f.fixed
        else {
            value = actionSpec.get(f.name)
            _rmRequireCatalogValue(label, f, value)
            if (value != null && f.values instanceof List) value = _rmCatalogValueCanonical(f, value)
            if (f.direction != null) value = (f.direction == true) ? (value == "raise") : (value != "raise")
            else if (value == null && f.containsKey("dflt")) value = f.dflt
            if (f.trueWhen != null && value != null) value = value.toString() == f.trueWhen.toString()
            if (f.whenSet) value = (value != null) ? true : null
        }
        if (f.required && (value == null || (f.nonBlank && !value.toString().trim()))) {
            throw new IllegalArgumentException("${label} requires '${f.name}'${f.desc ? ' (' + f.desc + ')' : ''}.".toString())
        }
        if (f.whenTrue && value != true) return
        if (value != null) fields.put(key, value)
    }
    _rmRequireDeviceCapability("${label}.deviceIds".toString(), actionSpec.deviceIds, (actionEntry.sub ?: entry.sub)?.toString())
    return [actType: entry.actType, actSubType: actionEntry.sub ?: entry.sub, fields: fields, skipped: []]
}

// The slot RM opened for the in-flight trigger: the larger of its counter (state.ndx), which a
// cancelled edit leaves advanced, and the next index after the committed triggers.
private Integer _rmInFlightTriggerIdx(Integer appId, List existing) {
    Integer next = (existing ? (existing.max() as Integer) + 1 : 1)
    def ndx = null
    try {
        def raw = (_rmFetchStatusJson(appId)?.appState ?: []).find { it?.name?.toString() == "ndx" }?.value
        if (raw != null && raw.toString().isInteger()) ndx = raw.toString() as Integer
    } catch (Exception e) {
        throw new IllegalStateException("addTrigger: the rule's trigger counter could not be read for app ${appId} (${e.message ?: e}), so the conditional trigger's slot is unknown. Nothing was written.".toString(), e)
    }
    return (ndx != null && ndx > next) ? ndx : next
}

private List _rmCollectTriggerIndices(Integer appId) {
    def status = _rmFetchStatusJson(appId)
    def out = []
    (status?.appSettings ?: []).each { s ->
        def n = s?.name?.toString()
        if (n) {
            def m = (n =~ /^tCapab(\d+)$/)
            if (m.matches()) out << (m[0][1] as Integer)
        }
    }
    return out
}

// Trigger index -> committed capability, from statusJson, like the index scan (tCapab is an
// enum, so its value is the capability). A missing value maps to null, which the guards treat as
// unresolved.
private Map _rmCollectTriggerCapabilities(Integer appId) {
    def status = _rmFetchStatusJson(appId)
    def out = [:]
    (status?.appSettings ?: []).each { s ->
        def n = s?.name?.toString()
        if (n) {
            def m = (n =~ /^tCapab(\d+)$/)
            if (m.matches()) out[(m[0][1] as Integer)] = s?.value?.toString()
        }
    }
    return out
}

// Action indices from appSettings, in the hub's arbitrary key order, read from actType.<n> or
// actSubType.<n> (an ELSE / END-IF carries only the subtype). Use _rmCollectActionIndices for order.
// This also sees rows written but never baked, which is why allocation and orphan rollback read it.
private List _rmActionIndicesFromSettings(Map status) {
    def out = []
    def seen = [] as Set
    (status?.appSettings ?: []).each { s ->
        def n = s?.name?.toString()
        if (n) {
            def m = (n =~ /^act(?:Type|SubType)\.(\d+)$/)
            if (m.matches()) {
                def idx = (m[0][1] as Integer)
                if (seen.add(idx)) out << idx
            }
        }
    }
    return out
}

// Rows that hold an action or a structural keyword -- actType.<N> or actSubType.<N> with a value.
// Excludes rows whose actType/actSubType are both blank, which RM leaves behind after a delete or
// a cancel. This answers "what is on this rule at the settings level": a row the compiled
// actionList never listed still occupies its index, and ignoring it leaves a permanent orphan.
private List _rmLiveActionIndicesFromSettings(Map status) {
    def live = [:]
    (status?.appSettings ?: []).each { s ->
        def n = s?.name?.toString()
        if (!n) return
        def m = (n =~ /^act(?:Type|SubType)\.(\d+)$/)
        if (!m.matches()) return
        def idx = (m[0][1] as Integer)
        if (s?.value?.toString()?.trim()) live.put(idx, true)
    }
    return live.keySet().sort()
}

// Action indices in RM's DISPLAY ORDER, read positionally by _rmMoveAction (to verify a position
// shift) and _rmModifyAction (to walk a rebuilt action back to its original slot). Primary source
// is /app/ruleBuilderJson's `actionList`, the only display-ordered source the hub exposes, read
// via _ruleCompiledState so this shares the health check's fetch. When that is absent or
// unreadable (a non-RM classic app, older firmware, a failed read) the appSettings scan answers
// WHICH rows exist but NOT the order they display in.
//
// Note action keys use a dot-N suffix (actType.1) vs trigger keys without dot (tCapab1).
private List _rmCollectActionIndices(Integer appId) {
    def ordered = _rmOrderedActionIndices(appId)
    // null = unreadable (scan settings); [] = the rule genuinely has no actions, honoured as such.
    if (ordered != null) return ordered
    return _rmActionIndicesFromSettings(_rmFetchStatusJson(appId))
}

// Tri-state: true = positively disabled, false = positively enabled, null = the read established
// neither (empty body, unparseable, key absent, exception). null is NOT folded into false -- the
// ungated paths return success:true on a disabled app having done nothing, so a transient read
// failure that silently opened the gate would restore exactly that. Callers proceed on null (a
// blip must not block edits on a healthy hub) but it is logged at WARN, the same separation
// hub_set_app_disabled makes on this endpoint.
Boolean _rmIsAppDisabled(Integer appId) {
    String why
    // Two attempts. A caller that gets null PROCEEDS (a blip must not block edits on a healthy
    // hub), so an unreadable first attempt is the one case where the gate silently stops
    // protecting: on a rule that really is disabled, RM then no-ops the write and the caller
    // reports success having changed nothing. One cheap retry closes the transient-blip window
    // without turning a hiccup into a refusal.
    for (int attempt = 0; attempt < 2; attempt++) {
        if (attempt > 0) { try { pauseExecution(250) } catch (Exception ignore) { } }
        why = null
        try {
            def txt = hubInternalGet("/installedapp/json/${appId}")
            if (!txt) {
                why = "empty /installedapp/json body"
            } else {
                def parsed = new groovy.json.JsonSlurper().parseText(txt)
                if (parsed instanceof Map && parsed.containsKey("disabled")) return parsed.disabled == true
                why = "/installedapp/json carried no 'disabled' key"
            }
        } catch (Exception e) {
            why = "read failed (${e.message})"
        }
    }
    mcpLog("warn", "rm-native", "_rmIsAppDisabled(${appId}): ${why} on two attempts -- disabled state UNKNOWN, proceeding")
    return null
}

// Name the operation for the disabled-app refusal so the message says which edit was refused
// rather than a generic "edit". Same precedence as _applyNativeAppEdit's backupReason chain, so
// a call carrying more than one key is labelled the way its own backup reason names it; the
// keys that chain folds into "pre-update" (patches, settings) come last for the same reason.
private String _rmEditOpLabel(Map args) {
    if (args == null) return "edit"
    def first = ["button", "addTrigger", "addAction", "addActions", "addTriggers",
                 "addRequiredExpression", "replaceRequiredExpression", "addLocalVariable",
                 "removeLocalVariable", "removeAction", "clearActions", "replaceActions",
                 "moveAction", "removeTrigger", "modifyTrigger", "modifyAction", "walkStep",
                 "patches", "settings"].find { args[it] != null }
    return first ?: "edit"
}

// Shared disabled-app refusal, so the wording cannot drift between the sites that need it.
// IllegalArgumentException: this is caller-recoverable -- re-enable, edit, re-disable.
void _rmRejectDisabledAppEdit(Integer appId, String opName) {
    if (_rmIsAppDisabled(appId) != true) return
    throw new IllegalArgumentException("${opName} blocked: rule ${appId} is DISABLED. Hubitat does not allow editing a disabled app -- its configuration page renders only \"App is disabled\", so there is no wizard to drive. This is intended platform behavior, not an error in the rule. To edit it: hub_set_app_disabled(appId=${appId}, disabled=false), make the change, then disable it again if you want it left parked. RM is not touched.")
}

// Delete one action row (delAct click). RM keeps the remaining indices as they are, with gaps.
// skipStructuralCheck skips the balance pre-flight for a row known to be uncommitted, or one of a
// balanced set being removed.
Map _rmDeleteAction(Integer appId, Integer actionIdx, boolean skipStructuralCheck = false) {
    def status = _rmFetchStatusJson(appId)
    def settingsByName = (status?.appSettings ?: []).collectEntries { [(it?.name?.toString()): it] }
    // Settings, not the compiled list: a never-baked row exists only here.
    def beforeIndices = _rmActionIndicesFromSettings(status)
    if (!beforeIndices.contains(actionIdx)) {
        throw new IllegalArgumentException("removeAction.index ${actionIdx} not found in rule ${appId}. Existing indices: ${beforeIndices.sort().join(', ')}")
    }
    // RM silently ignores delAct while an action editor is open (state.editAct).
    def editActEntry = (status?.appState ?: []).find { it?.name?.toString() == "editAct" }
    def stuckEditAct = editActEntry?.value
    if (stuckEditAct != null) {
        throw new IllegalStateException(_rmStuckEditActMessage("removeAction(${actionIdx})", appId, stuckEditAct))
    }
    // Keyed on actSubType alone: the UI persists no actType on ELSE / ELSE-IF / END-IF rows.
    // Only NEW imbalance is refused, so a deletion that improves a broken rule is allowed.
    def sType = settingsByName["actSubType.${actionIdx}".toString()]?.value?.toString()
    def structuralSubTypes = ["getIfThen", "getElseIf", "getElse", "getEndIf", "getRepeat", "getWhile", "getStopRepeat"]
    if (sType in structuralSubTypes && !skipStructuralCheck) {
        def humanKind = [
            "getIfThen": "IF",
            "getElseIf": "ELSE-IF",
            "getElse": "ELSE",
            "getEndIf": "END-IF",
            "getRepeat": "Repeat",
            "getWhile": "Repeat-While",
            "getStopRepeat": "End-Repeat"
        ][sType] ?: sType
        def orderedIndices = _rmOrderedActionIndices(appId)
        if (orderedIndices == null) {
            // A leftover closer in the whole-settings scan can mask the imbalance.
            throw new IllegalStateException("removeAction(${actionIdx}) blocked: action ${actionIdx} is a structural ${humanKind} row and the rule's compiled action order (ruleBuilderJson actionList) is unavailable, so the deletion's effect on block balance cannot be verified -- the settings scan can be masked by leftover rows. Retry once ruleBuilderJson answers (hub_get_rule_health lists the read failure under checkErrors), or use replaceActions to rebuild the action list atomically. RM is not touched.")
        }
        def currentIssues = _rmStructuralIssuesFromSequence(
            _rmStructuralSequenceFromSettings(settingsByName, ([] as Set), orderedIndices))
        def projectedIssues = _rmStructuralIssuesFromSequence(
            _rmStructuralSequenceFromSettings(settingsByName, ([actionIdx] as Set), orderedIndices))
        def newIssues = projectedIssues - currentIssues
        if (newIssues) {
            throw new IllegalArgumentException("removeAction(${actionIdx}) blocked: action ${actionIdx} is a structural ${humanKind}; removing it would introduce a new structural-balance issue (${newIssues.first()}). Remove the matching opener/closer first, or use replaceActions to rebuild the action list atomically. RM is not touched.")
        }
    }
    _rmClickAppButton(appId, actionIdx.toString(), "delAct", "selectActions")
    // The click returns 200 before the delete propagates, and RM sometimes drops the first click
    // entirely: short polls absorb the lag, and a row still present at ~2.5s gets ONE re-click
    // (the presence check just before it is the evidence that makes the re-click safe). Total
    // stays under the ~10s cloud-relay ceiling. The live view (rows whose actType still carries
    // a value) is polled because the UI leaves emptied keys behind on some firmware.
    def afterIndices = null
    def reclicked = false
    def retryDelaysMs = [1000, 1500, 2000, 3000]
    for (int attempt = 0; attempt < 5; attempt++) {
        if (attempt > 0) pauseExecution(retryDelaysMs[attempt - 1] as Integer)
        afterIndices = _rmLiveActionIndicesFromSettings(_rmFetchStatusJson(appId))
        if (!afterIndices.contains(actionIdx)) {
            def out = [success: true, removedIndex: actionIdx, beforeIndices: beforeIndices.sort(), afterIndices: afterIndices.sort()]
            if (reclicked) out.reclicked = true
            return out
        }
        if (!reclicked && attempt == 2) {
            reclicked = true
            _rmClickAppButton(appId, actionIdx.toString(), "delAct", "selectActions")
        }
    }
    throw new IllegalStateException("removeAction(${actionIdx}): after the delAct click, one re-click and ~8s of polling, action ${actionIdx} is still present in rule ${appId} (before: ${beforeIndices.sort().join(', ')}; after: ${afterIndices.sort().join(', ')}). Verify first: hub_get_app_config(appId=${appId}). If the action is still there, removeAction(index:${actionIdx}) is safe to repeat; if it is gone, a click committed late and the removal succeeded.")
}

// RM ignores row buttons (delAct, the move arrows) while an action editor is open.
private String _rmStuckEditActMessage(String op, Integer appId, Object editAct) {
    return "${op} blocked: rule ${appId} has state.editAct=${editAct} from an interrupted action edit, and Rule Machine ignores row buttons until that editor is closed. Open the rule in the Hubitat UI and finish or cancel the open action editor, then retry. Button clicks, updateRule and an in-place restore do not clear it; as a last resort delete the rule and hub_restore_backup(scope='source', backupKey='<a recent rm-rule snapshot>', confirm=true) recreates it with fresh state.".toString()
}

// Delete a single trigger at a specific index. The per-row delete button on
// selectTriggers has data-stateAttribute='deleteCon' (truncated, NOT 'deleteCond'
// — verified live via embeddedActions introspection on rule 289)
// and the form-input name is the trigger index as a digit (e.g. name='1'
// for trigger 1). RM's
// appButtonHandler dispatches on the stateAttribute and uses the name to
// identify which trigger to delete.
//
// After delete, RM does NOT renumber remaining triggers -- indices are
// preserved with gaps. Subsequent addTrigger picks the next free index
// via _rmCollectTriggerIndices.
//
// Post-click verification uses a retry loop identical to _rmDeleteAction's
// to absorb RM 5.1's asynchronous button-handler dispatch. The click returns
// HTTP 200 before the deletion has propagated to appSettings. Up to 4
// attempts are made with increasing delays (1s, 3s, 6s) for a max sleep
// budget of 10s. The 10s budget covers typical and slow hub propagation;
// on retry exhaustion the throw directs the caller to verify via
// hub_get_app_config since the deletion may complete post-response.
Map _rmRemoveTrigger(Integer appId, Integer triggerIdx) {
    def beforeIndices = _rmCollectTriggerIndices(appId)
    if (!beforeIndices.contains(triggerIdx)) {
        throw new IllegalArgumentException("removeTrigger.index ${triggerIdx} not found in rule ${appId}. Existing indices: ${beforeIndices.sort().join(', ')}. RM is not touched.")
    }
    // Deleting a trigger takes two POSTs: /installedapp/btn with only id, name=<idx>,
    // stateAttribute=deleteCon, settings[<idx>]=clicked, <idx>.type=button (no form context, or RM treats
    // it as a save), then the selectTriggers page save that runs the deletion.
    def fireDeleteSequence = {
        _rmClickAppButton(appId, triggerIdx.toString(), "deleteCon")
        // POST 2: page-save commit. Pull version from the just-fetched config
        // (cheap; we already have it locally if we want to reuse, but a fresh
        // fetch keeps the helper self-contained).
        def cfgForVersion = null
        try { cfgForVersion = _rmFetchConfigJson(appId, "selectTriggers") } catch (Exception verExc) {
            mcpLog("warn", "rm-native", "_rmRemoveTrigger: version fetch for app ${appId} failed (${verExc.message}) -- POSTing commit without version field; hub may reject with a version-conflict error on concurrent edits")
        }
        def commitBody = [
            id: appId.toString(),
            formAction: "update",
            currentPage: "selectTriggers",
            pageBreadcrumbs: _rmPageBreadcrumbs(appId, "selectTriggers", '["mainPage"]')
        ]
        if (cfgForVersion?.app?.version != null) commitBody.version = cfgForVersion.app.version.toString()
        try { hubInternalPostForm("/installedapp/update/json", commitBody) } catch (Exception postExc) {
            mcpLog("warn", "rm-native", "_rmRemoveTrigger: page-save commit failed for app ${appId} trigger ${triggerIdx} (${postExc.message}) -- the deletion may not have committed; the verify retry loop below will catch it.")
        }
    }
    fireDeleteSequence()
    // Verify the trigger is gone. Short polls absorb lag; still present at ~2.5s means the first click
    // dropped, so the sequence re-fires once (safe: presence was just checked), within the relay's ~10s.
    def afterIndices = null
    def reclicked = false
    def retryDelaysMs = [1000, 1500, 2000, 3000]
    for (int attempt = 0; attempt < 5; attempt++) {
        if (attempt > 0) pauseExecution(retryDelaysMs[attempt - 1] as Integer)
        afterIndices = _rmCollectTriggerIndices(appId)
        if (!afterIndices.contains(triggerIdx)) {
            def out = [success: true, removedIndex: triggerIdx, beforeIndices: beforeIndices.sort(), afterIndices: afterIndices.sort()]
            if (reclicked) out.reclicked = true
            return out
        }
        if (!reclicked && attempt == 2) {
            reclicked = true
            fireDeleteSequence()
        }
    }
    throw new IllegalStateException("removeTrigger(${triggerIdx}): trigger ${triggerIdx} is still in rule ${appId} after a re-click and ~8s (before: ${beforeIndices.sort().join(', ')}; after: ${afterIndices.sort().join(', ')}). Read the rule with hub_get_app_config(appId=${appId}) before retrying: indices shift on delete, so a late commit plus a retry removes the wrong trigger.")
}

// Change a trigger's state: editCond button name=<idx> reopens the editor, write tstate<n>, commit with
// hasAll. Capability or device changes need removeTrigger + addTrigger.
private String _rmModifyTriggerNoStateMessage(Integer triggerIdx) {
    return "modifyTrigger: trigger ${triggerIdx} has no 'state' value to change -- this trigger does not expose a plain state field. Use removeTrigger + addTrigger to reconfigure it.".toString()
}

private String _rmModifyTriggerWizardRecoveryHint(Integer appId) {
    return "A trigger editor may still be open. Check hub_get_app_config(appId=${appId}, pageName='selectTriggers'); only if it is open, close it once with hub_set_rule(appId=${appId}, button='hasAll', pageName='selectTriggers', confirm=true).".toString()
}

private Map _rmModifyTrigger(Integer appId, Integer triggerIdx, Map mods) {
    def unsupported = mods.keySet() - ["state"]
    if (unsupported) {
        throw new IllegalArgumentException("modifyTrigger changes only a trigger's state (not ${unsupported.sort().join(', ')}); for other fields use removeTrigger + addTrigger. RM is not touched.")
    }
    if (!mods.containsKey("state")) {
        throw new IllegalArgumentException("modifyTrigger.mods must include 'state'. Supported fields: state. RM is not touched.")
    }
    // One statusJson read serves both the index-existence check and the family guard below
    // (index -> committed capability) -- no second fetch.
    def triggerCaps = _rmCollectTriggerCapabilities(appId)
    if (!triggerCaps.containsKey(triggerIdx)) {
        throw new IllegalArgumentException("modifyTrigger.index ${triggerIdx} not found in rule ${appId}. Existing indices: ${triggerCaps.keySet().sort().join(', ')}. RM is not touched.")
    }
    // modifyTrigger has no comparator, so a change token in mods.state would commit as a state that never
    // matches; refuse it for device-state and numeric triggers, before any hub call.
    if (_rmLooksLikeStateChangeToken(mods.state)) {
        def committedCap = triggerCaps.get(triggerIdx)
        // An unreadable capability (null) skips the guard, which would otherwise refuse a valid edit.
        if (committedCap != null && _rmStateChangeGuardApplies(committedCap)) {
            throw new IllegalArgumentException("modifyTrigger.mods.state:'${mods.state}' is a change, not a state; for a change trigger use removeTrigger + addTrigger with comparator:'*changed*'. RM is not touched.")
        }
    }
    // Time-family triggers have no state value. Refuse before opening the editor, which would
    // otherwise stay open on the trigger after the refusal.
    if (_rmCondFamily(triggerCaps.get(triggerIdx)) == "time") {
        throw new IllegalArgumentException(_rmModifyTriggerNoStateMessage(triggerIdx) + " RM is not touched.")
    }
    // Open the edit-condition wizard for this trigger.
    _rmClickAppButton(appId, triggerIdx.toString(), "editCond", "selectTriggers")
    // Write the modified state field through the schema-aware helper.
    // applied/skipped track what was written vs. silently bypassed.
    def applied = []
    def skipped = []
    _rmWriteSettingOnPage(appId, "selectTriggers", "tstate${triggerIdx}", mods.state, applied, null, skipped)
    // No tstate<n> on the page (time and periodic triggers): refuse before hasAll.
    def tstateName = "tstate${triggerIdx}".toString()
    def schemaSkipped = skipped.find { it instanceof Map && it.key == tstateName && it.reason == "not_in_schema" }
    if (schemaSkipped) {
        // Nothing was written, so Done re-commits the trigger unchanged and closes its editor.
        try { _rmClickAppButton(appId, "hasAll", null, "selectTriggers") } catch (Exception closeExc) {
            mcpLog("warn", "rm-native", "_rmModifyTrigger: closing trigger ${triggerIdx}'s editor on app ${appId} failed (${closeExc.message}) -- it may still be open")
            throw _rmFail(new IllegalStateException(_rmModifyTriggerNoStateMessage(triggerIdx) + " [wizardStuck -- hasAll cleanup failed: ${closeExc.message ?: closeExc.toString()}]", closeExc), [wizardStuck: true])
        }
        throw new IllegalArgumentException(_rmModifyTriggerNoStateMessage(triggerIdx))
    }
    // Commit the in-flight edit via hasAll on selectTriggers.
    _rmClickAppButton(appId, "hasAll", null, "selectTriggers")
    // Read the state back from the persisted settings to confirm it landed.
    def verifiedState = null
    def verificationFetchFailed = false
    try {
        // configure/json, not selectTriggers: once hasAll closes the editor that page no longer shows tstate<n>.
        def verifyCfg = _rmFetchConfigJson(appId)
        verifiedState = verifyCfg?.settings?."tstate${triggerIdx}"?.toString()
    } catch (Exception verifyExc) {
        verificationFetchFailed = true
        mcpLog("warn", "rm-native", "_rmModifyTrigger: post-commit configure/json fetch failed for app ${appId} (${verifyExc.message}) -- cannot echo-verify new state; returning verificationFetchFailed=true")
    }
    def success = verificationFetchFailed ? false : (verifiedState != null ? verifiedState == mods.state?.toString() : !applied.isEmpty())
    return [
        success: success,
        modifiedIndex: triggerIdx,
        verifiedState: verifiedState,
        verificationFetchFailed: verificationFetchFailed,
        settingsApplied: applied,
        settingsSkipped: skipped
    ]
}

// Edit a committed action in place through RM's row editor: the row's doAct button value reopens
// doActPage on that row with its settings, keeping its index, position and untouched settings.
// Expression and wait-for-events actions are rebuilt with removeAction + addAction. `plan` is the
// caller's _rmPlanModifyAction result when it already made one.
private Map _rmModifyAction(Integer appId, Integer actionIdx, Map mods, Long reqT0 = null, Map plan = null) {
    if (plan == null) plan = _rmPlanModifyAction(appId, actionIdx, mods)
    String cap = plan.cap
    String action = plan.action
    Map writes = plan.writes as Map
    List fieldDefs = plan.fieldDefs as List
    def before = _rmOrderedActionIndices(appId)
    // The row's edit button value goes straight into doActN, the slot doActPage opens (the add
    // path's "N" does the same); doAct is only moved there by a selectActions render.
    Map pageCache = [:]
    _rmClickAppButton(appId, _rmActionRowButton(appId, actionIdx, plan.cond as Integer, pageCache), "doActN", "selectActions", pageCache)
    def applied = []
    def skipped = []
    // RM's editor on an existing row shows that row's fields (no type pickers); a new row shows
    // actType.<new>. Anything else is not this row, and its cancel would delete that row's
    // settings, so nothing is clicked.
    def openNames = []
    try {
        openNames = (_rmFetchConfigJson(appId, "doActPage")?.configPage?.sections ?: []).collectMany { (it?.input ?: []) }.collect { it?.name?.toString() }.findAll { it }
    } catch (Exception readExc) {
        throw _rmFail(new IllegalStateException("modifyAction: the editor page could not be read after action ${actionIdx}'s edit button (${readExc.message ?: readExc}); RM's editor may be open on that row [wizardStuck -- finish it with hub_set_rule(button='actionDone', pageName='doActPage', confirm=true); actionCancel would delete the action]".toString(), readExc), [wizardStuck: true])
    }
    String rowSuffix = ".${actionIdx}".toString()
    boolean onRow = openNames.any { it.endsWith(rowSuffix) } && !openNames.any { it.startsWith("actType.") && it != "actType${rowSuffix}" }
    if (!onRow) {
        throw _rmFail(new IllegalStateException("modifyAction: RM did not open its editor on action ${actionIdx}. Visible fields: ${openNames.join(', ') ?: '(none)'}. Check the rule with hub_get_rule_health and restore the pre-edit backup if one was taken and the rule is not as before [wizardStuck -- RM's action editor may be open on another row: finish it with hub_set_rule(button='actionDone', pageName='doActPage', confirm=true); actionCancel would delete the action].".toString()), [wizardStuck: true])
    }
    try {
        writes.each { String key, value -> _rmWriteSettingOnPage(appId, "doActPage", key, value, applied, null, skipped) }
        // Written into `writes` as each key lands, so a stop after the capability or devices names them.
        if (plan.runCommand instanceof Map) _rmWriteRunCommandFields(appId, actionIdx, plan.runCommand as Map, applied, skipped, writes)
        def missed = skipped.findAll { it instanceof Map && writes.containsKey(it.key?.toString()) && it.reason == "not_in_schema" }*.key
        if (missed) throw new IllegalArgumentException("modifyAction: RM's editor for action ${actionIdx} has no field ${missed.join(', ')}, so that value does not apply to this action as committed.")
    } catch (Exception editExc) {
        // On an existing row RM's "Cancel editing Action" is actionDone (actionCancel removes a
        // row). Settings already written stay written, so the message names them.
        String stuck = ""
        try { _rmClickAppButton(appId, "actionDone", null, "doActPage") } catch (Exception closeExc) {
            mcpLog("warn", "rm-native", "modifyAction: actionDone after a failed write on app ${appId} action ${actionIdx} failed (${closeExc.message ?: closeExc})")
            stuck = " [wizardStuck -- RM's editor on action ${actionIdx} is still open: finish it with hub_set_rule(button='actionDone', pageName='doActPage', confirm=true); actionCancel would delete the action]"
        }
        def landed = applied.findAll { writes.containsKey(it?.toString()) }
        throw _rmFail(new IllegalStateException("${editExc.message ?: editExc}${landed ? ' Fields already written before the stop: ' + landed.join(', ') + ' (check the action with hub_get_app_config).' : ' Nothing was written.'}${stuck}".toString(), editExc), _rmOutcomeFlags(editExc) + [fieldsWritten: !landed.isEmpty(), wizardStuck: !stuck.isEmpty()])
    }
    try {
        _rmClickAppButton(appId, "actionDone", null, "doActPage")
        _rmNavigateToPage(appId, "doActPage", "selectActions", 0, "name", null, null, null, false, true)
    } catch (Exception commitExc) {
        throw _rmFail(new IllegalStateException("modifyAction: the editor's Done on action ${actionIdx} failed (${commitExc.message ?: commitExc}). Fields already written before the stop: ${applied.findAll { writes.containsKey(it?.toString()) }.join(', ') ?: '(none)'} (check the action with hub_get_app_config). [wizardStuck -- RM's editor on action ${actionIdx} may still be open: finish it with hub_set_rule(button='actionDone', pageName='doActPage', confirm=true); actionCancel would delete the action]".toString(), commitExc), [fieldsWritten: true, wizardStuck: true])
    }
    // Readback: every value echoes from the committed settings, and the action list kept its
    // shape (RM's editor re-registers the index it edited).
    _rmReclassifyDeviceListSkips(appId, skipped)
    def genuineSkipped = skipped.findAll { !(it instanceof Map && it.reason in _rmInformationalSkippedReasons()) }
    def after = null
    boolean verificationFetchFailed = false
    try { after = _rmFetchConfigJson(appId)?.settings } catch (Exception verifyExc) {
        verificationFetchFailed = true
        mcpLog("warn", "rm-native", "modifyAction: readback failed for app ${appId} (${verifyExc.message})")
    }
    if (!(after instanceof Map)) verificationFetchFailed = true
    // RM stores a device picker as {id: label}; its readback is the id set statusJson carries.
    def deviceKeys = fieldDefs.findAll { it.name == "deviceIds" }.collect { it.key.toString().replace("@N", actionIdx.toString()) }.unique()
    def mismatches = (after instanceof Map) ? writes.findAll { k, v -> !(k in deviceKeys) && !_rmSettingEchoes((after as Map)[k], v) }.keySet().toList() : []
    if (after instanceof Map && deviceKeys.any { writes.containsKey(it) }) {
        def status = null
        try { status = _rmFetchStatusJson(appId) } catch (Exception statusExc) {
            verificationFetchFailed = true
            mcpLog("warn", "rm-native", "modifyAction: statusJson readback failed for app ${appId} (${statusExc.message})")
        }
        def byName = (status?.appSettings ?: []).collectEntries { [(it?.name?.toString()): it] }
        if (status != null) deviceKeys.findAll { writes.containsKey(it) }.each { String k ->
            def wanted = ((writes.get(k) instanceof List) ? (writes.get(k) as List) : [writes.get(k)]).collect { it?.toString() } as Set
            if (_rmStatusEntryDeviceIds(byName.get(k)) != wanted) mismatches << k
        }
    }
    def orderAfter = _rmOrderedActionIndices(appId)
    def duplicates = (orderAfter == null) ? [] : orderAfter.countBy { it }.findAll { k, n -> n > 1 }.keySet().toList()
    boolean verified = !verificationFetchFailed && orderAfter != null && orderAfter == before && mismatches.isEmpty() && duplicates.isEmpty() && genuineSkipped.isEmpty()
    def out = [success: verified, partial: !verified, modifiedIndex: actionIdx, capability: cap, action: action,
               settingsApplied: applied, settingsSkipped: skipped, writes: writes,
               readbackMismatches: mismatches ?: null, actionOrder: orderAfter, duplicateIndices: duplicates ?: null,
               verificationFetchFailed: verificationFetchFailed ?: null]
    if (!verified) {
        out.error = (verificationFetchFailed || orderAfter == null) ? "modifyAction: the edit committed but the readback failed (${verificationFetchFailed ? 'the settings' : 'the action list'} could not be read); verify action ${actionIdx} with hub_get_app_config(appId=${appId})."
            : (duplicates ? "modifyAction: after the edit the action list carries index ${duplicates.join(', ')} more than once; inspect the rule with hub_get_app_config and restore the pre-edit backup if one was taken (restoreHint) and it renders wrong."
            : (orderAfter != before ? "modifyAction: the action order changed from ${before} to ${orderAfter}; inspect the rule and restore the pre-edit backup if one was taken (restoreHint) and it renders wrong."
            : "modifyAction: ${mismatches ? mismatches.join(', ') + ' did not read back as written' : 'a field was skipped: ' + genuineSkipped*.key.join(', ')}; verify action ${actionIdx} with hub_get_app_config(appId=${appId}) and restore the pre-edit backup if one was taken (restoreHint).")).toString()
    }
    return out.findAll { k, v -> v != null }
}

// The edit button selectActions renders for an action row, "<index>.<condition>.false". A Simple
// Conditional Action's button carries its condition's index, and RM's editor keeps the row bound to
// that condition only when the clicked value names it. The committed state.actions is the fallback.
private String _rmActionRowButton(Integer appId, Integer actionIdx, Integer committedCond, Map cache) {
    String rowPattern = "${actionIdx}\\.\\d+\\.\\w+".toString()
    String rendered = null
    try {
        rendered = _rmRenderedButtons(_rmFetchConfigJson(appId, "selectActions", cache)?.configPage as Map)
            .collect { it?.name?.toString() }.find { it && it ==~ rowPattern }
    } catch (Exception readExc) {
        mcpLog("warn", "rm-native", "modifyAction: selectActions could not be read for action ${actionIdx}'s edit button (${readExc.message ?: readExc}); the committed condition index is used")
    }
    def cond = rendered ? rendered.tokenize(".")[1] : (committedCond ?: 0)
    return "${actionIdx}.${cond}.false".toString()
}

// A setVariable target is one of the rule's local variables or a hub variable (RM's xVarV picker
// lists both). Nothing is refused when neither list can be read.
private void _rmRequireVariableName(Integer appId, Object name) {
    String target = name?.toString()
    List hub = null
    try { hub = (getAllGlobalVars()?.keySet() ?: []).collect { it?.toString() } } catch (Exception e) {
        mcpLog("warn", "rm-native", "modifyAction: the hub variables could not be read (${e.message ?: e})")
    }
    def localsRead = null
    try { localsRead = _rmReadLocalVarsMap(appId) } catch (Exception e) {
        mcpLog("warn", "rm-native", "modifyAction: rule ${appId}'s local variables could not be read (${e.message ?: e})")
    }
    if (localsRead != null && localsRead.ok != true) mcpLog("warn", "rm-native", "modifyAction: rule ${appId}'s local variables could not be read (${localsRead.error ?: 'no map'})")
    List locals = localsRead?.ok ? ((localsRead.vars ?: [:]) as Map).keySet().collect { it?.toString() } : null
    if ((hub ?: []).contains(target) || (locals ?: []).contains(target)) return
    // A name absent from one list may be in the other; without both, nothing is refused.
    if (hub == null || locals == null) {
        mcpLog("warn", "rm-native", "modifyAction: variable '${target}' was not checked: ${hub == null ? 'hub' : 'local'} variables unreadable")
        _memoNoteSkippedCheck("variable name (the ${hub == null ? 'hub' : 'local'} variable list could not be read)")
        return
    }
    throw new IllegalArgumentException("modifyAction: variable '${target}' is not a hub variable or one of this rule's local variables. Hub variables: ${hub.sort().join(', ') ?: '(none)'}; local variables: ${locals.sort().join(', ') ?: '(none)'}. RM is not touched.".toString())
}

// The argument checks and the settings an in-place edit will write, decided from the committed
// rule and read-only lookups only, so a refusal happens before any backup or write -- except
// runCommand's capabilityFilter and command, resolved against RM's live pickers once the editor is open.
private Map _rmPlanModifyAction(Integer appId, Integer actionIdx, Map mods) {
    if (!(mods instanceof Map) || mods.isEmpty()) {
        throw new IllegalArgumentException("modifyAction.mods must set at least one of the action's fields. RM is not touched.")
    }
    def status = _rmFetchStatusJson(appId)
    def settingsByName = (status?.appSettings ?: []).collectEntries { [(it?.name?.toString()): it] }
    def committed = { String key -> settingsByName["${key}.${actionIdx}".toString()]?.value }
    String subType = committed("actSubType")?.toString()
    if (!subType) {
        def indices = _rmActionIndicesFromSettings(status)
        throw new IllegalArgumentException("modifyAction.index ${actionIdx} not found in rule ${appId}. Existing indices: ${indices.sort().join(', ')}. RM is not touched.")
    }
    def editAct = (status?.appState ?: []).find { it?.name?.toString() == "editAct" }?.value
    if (editAct != null) throw new IllegalStateException(_rmStuckEditActMessage("modifyAction(${actionIdx})", appId, editAct))
    def stateActions = (status?.appState ?: []).find { it?.name?.toString() == "actions" }?.value
    def stateRow = (stateActions instanceof Map) ? (stateActions as Map).get(actionIdx.toString()) : null
    String condText = (stateRow instanceof Map) ? stateRow.cond?.toString() : null
    Integer cond = condText?.isInteger() ? (condText as Integer) : null
    Map target = _rmCatalogEntryForSubType(subType, committed)
    // returnValue's type, literal and variable are coupled by its own mapper (the literal slot
    // follows the type; no variable for a String), which an in-place edit cannot honour.
    if (target?.cap == "returnValue") target = null
    if (target == null) {
        throw new IllegalArgumentException("modifyAction: action ${actionIdx} (${subType}) cannot be edited in place; rebuild it with removeAction + addAction. RM is not touched.")
    }
    String cap = target.cap
    Map entry = target.entry
    Map actionEntry = target.actionEntry
    String action = target.action
    Map actions = (entry.actions instanceof Map) ? (entry.actions as Map) : null
    def writes = [:]
    if (mods.containsKey("action")) {
        String newAction = mods.action?.toString()
        Map newEntry = (actions != null) ? (actions[newAction] as Map) : null
        if (newEntry == null) {
            throw new IllegalArgumentException("Unknown ${cap} action '${newAction}' -- supported: ${actions != null ? actions.keySet().join(', ') : '(none: ' + cap + ' has no action variants)'}. RM is not touched.")
        }
        if ((newEntry.sub ?: entry.sub) != subType) {
            throw new IllegalArgumentException("modifyAction: ${cap}.${newAction} is a different Rule Machine action (${newEntry.sub}) from the committed one (${subType}); rebuild it with removeAction + addAction. RM is not touched.")
        }
        actionEntry = newEntry
        action = newAction
        ((actionEntry.slots ?: []) as List).each { Map f -> if (f.containsKey("fixed")) writes[f.key.toString().replace("@N", actionIdx.toString())] = f.fixed }
    }
    List fieldDefs = (((entry.slots ?: []) as List) + ((actionEntry.is(entry) ? [] : (actionEntry.slots ?: [])) as List)).findAll { it.key && it.name }
    def editable = (fieldDefs*.name).unique() + (actions != null ? ["action"] : [])
    // At most one of an exclusive group may be given, as on an add.
    (((entry.exclusive ?: []) as List) + ((actionEntry.is(entry) ? [] : (actionEntry.exclusive ?: [])) as List)).each { List g ->
        def given = g.findAll { mods.containsKey(it?.toString()) && mods.get(it?.toString()) != null }
        if (given.size() > 1) throw new IllegalArgumentException("modifyAction on ${cap}${action ? '.' + action : ''}: takes one of ${g.join(', ')}, not ${given.join(' and ')}. RM is not touched.".toString())
    }
    mods.each { k, v ->
        String name = k.toString()
        if (name == "action") return
        List named = fieldDefs.findAll { it.name == name }
        if (!named) {
            throw new IllegalArgumentException("modifyAction on ${cap}${action ? '.' + action : ''}: '${name}' is not a field that can be edited in place. Editable: ${editable.join(', ')}. RM is not touched.")
        }
        named.findAll { it.whenSet && v != null }.each { Map t -> writes[t.key.toString().replace("@N", actionIdx.toString())] = true }
        // A literal given for a field whose exclusive partner is a variable clears that partner's
        // toggle first, so RM reveals the literal's picker again (uVar false, then tiltLevel).
        (((entry.exclusive ?: []) as List) + ((actionEntry.is(entry) ? [] : (actionEntry.exclusive ?: [])) as List)).findAll { List g -> name in g }.each { List g ->
            g.findAll { it != name }.each { other ->
                fieldDefs.findAll { it.name == other && it.whenSet }.each { Map t -> writes[t.key.toString().replace("@N", actionIdx.toString())] = false }
            }
        }
        Map f = named.find { !it.whenSet } as Map
        if (f == null) return
        _rmRequireCatalogValue("modifyAction on ${cap}".toString(), f, v)
        def value = (v != null && f.values instanceof List) ? _rmCatalogValueCanonical(f, v) : v
        if (f.direction != null) value = (f.direction == true) ? (value == "raise") : (value != "raise")
        else if (f.trueWhen != null) value = value?.toString() == f.trueWhen.toString()
        else if (name == "ruleIds") value = _rmNormalizeRuleIdsForWrite(v, subType == "getSetPrivateBoolean")
        else if (name == "value" && subType == "getSetPrivateBoolean") value = !(v as Boolean)
        if (name == "deviceIds") {
            _rmValidateDeviceIdsExist("modifyAction.deviceIds", v)
            _rmRequireDeviceCapability("modifyAction.deviceIds", v, subType)
        }
        if (name == "ruleIds") _rmValidateRuleTargetExists(cap, v, null)
        if (name == "modeId") {
            def modeCheck = _rmBadModeIds([v?.toString()], null)
            if (modeCheck.bad) throw new IllegalArgumentException("modifyAction: mode ID ${v} is not offered by ${modeCheck.source}. Valid IDs: ${modeCheck.valid.sort().join(', ')}. RM is not touched.".toString())
        }
        if (name == "variable" && subType == "getSetVariable") _rmRequireVariableName(appId, v)
        if (value == null || (f.nonBlank && !value.toString().trim())) {
            throw new IllegalArgumentException("modifyAction: '${name}' needs a value. RM is not touched.")
        }
        writes[f.key.toString().replace("@N", actionIdx.toString())] = value
    }
    // runCommand's capability, devices and command are resolved against RM's live pickers once the
    // editor is open, the way the add does; the committed values fill in what mods leave out.
    Map runCommand = null
    if (cap == "runCommand" && ["capabilityFilter", "command", "deviceIds", "useLastEventDevice"].any { mods.containsKey(it) }) {
        def committedIds = _rmStatusEntryDeviceIds(settingsByName["devices.${actionIdx}".toString()]).collect { it as Integer }
        boolean useLast = mods.containsKey("useLastEventDevice") ? (mods.useLastEventDevice == true) : (committed("useLastDev")?.toString() == "true")
        // RM hides the device picker while useLastDev is on, so no device list is written then.
        if (useLast && mods.deviceIds != null) throw new IllegalArgumentException("modifyAction on runCommand: deviceIds cannot be set while useLastEventDevice is true, because Rule Machine hides the device picker then. RM is not touched.")
        def deviceIds = useLast ? null : (mods.containsKey("deviceIds") ? mods.deviceIds : (committedIds ?: null))
        if (!useLast && !deviceIds) throw new IllegalArgumentException("modifyAction on runCommand: 'deviceIds' is needed when useLastEventDevice is false. RM is not touched.")
        runCommand = [capabilityFilter: mods.containsKey("capabilityFilter") ? mods.capabilityFilter : committed("myCapab"),
                      command: mods.containsKey("command") ? mods.command : committed("cCmd"),
                      deviceIds: deviceIds, useLastEventDevice: useLast]
        if (runCommand.command == null) throw new IllegalArgumentException("modifyAction on runCommand: 'command' is needed. RM is not touched.")
        // useLastDev stays in writes: the editor writes it before the runCommand fields, as the add does.
        ["myCapab", "cCmd", "devices"].each { writes.remove("${it}.${actionIdx}".toString()) }
    }
    if (writes.isEmpty() && runCommand == null) throw new IllegalArgumentException("modifyAction.mods changes nothing on action ${actionIdx}. RM is not touched.")
    return [cap: cap, action: action, subType: subType, writes: writes, fieldDefs: fieldDefs, runCommand: runCommand, cond: cond]
}

// The catalog entry (and action variant) a committed sub-type belongs to. Variants that share a
// sub-type are told apart by their fixed settings. Null when the catalog names no settings for it
// or the action embeds an expression or waits for events (their editors need more than the row's fields).
private Map _rmCatalogEntryForSubType(String subType, Closure committed) {
    if (subType in ["getIfThen", "getElseIf", "getWhile", "getUntil", "getWaitRule", "getWaitEvents"]) return null
    Map found = null
    _rmActionCatalog().each { String cap, Map entry ->
        if (found != null) return
        if (entry.actions instanceof Map) {
            (entry.actions as Map).each { act, Map ae ->
                if (found != null || (ae.sub ?: entry.sub) != subType) return
                def fixed = ((ae.slots ?: []) as List).findAll { it.containsKey("fixed") }
                if (fixed.every { Map f -> committed(f.key.toString().replace(".@N", ""))?.toString() == f.fixed.toString() }) {
                    found = [cap: cap, action: act.toString(), entry: entry, actionEntry: ae]
                }
            }
        } else if (entry.sub == subType) {
            found = [cap: cap, action: null, entry: entry, actionEntry: entry]
        }
    }
    if (found == null) return null
    def keyed = (((found.entry.slots ?: []) as List) + ((found.actionEntry.slots ?: []) as List)).any { it.key && it.name }
    return keyed ? found : null
}

// Whether a committed setting carries the value that was written (settings echo as strings).
private boolean _rmSettingEchoes(Object stored, Object wanted) {
    if (wanted instanceof List && stored instanceof CharSequence && stored.toString().trim().startsWith("[")) {
        try { stored = new groovy.json.JsonSlurper().parseText(stored.toString()) } catch (Exception ignored) { }
    }
    if (wanted instanceof List) {
        def want = (wanted as List).collect { it?.toString() }.sort(false)
        def have = (stored instanceof List) ? (stored as List).collect { it?.toString() }.sort(false)
            : (stored instanceof Map ? (stored as Map).keySet().collect { it?.toString() }.sort(false) : (stored != null ? [stored.toString()] : []))
        return want == have
    }
    return stored != null && stored.toString().trim() == wanted.toString().trim()
}

private List _rmPatchModifyOpKeys() { ["removeTrigger", "modifyTrigger", "modifyAction"] }

// Every operation key a patches[] item can carry. An item names exactly one: the dispatch chain
// runs only the first key it matches, so a second key would be dropped while the item reports
// success.
private List _rmPatchOpKeys() {
    ["settings", "button", "addTrigger", "addTriggers", "addAction", "addActions", "addRequiredExpression",
     "replaceRequiredExpression", "addLocalVariable", "removeLocalVariable", "removeAction", "clearActions",
     "replaceActions", "moveAction"] + _rmPatchModifyOpKeys()
}

// Refuse the whole batch, before any op runs, when an item carries more than one operation.
private void _rmRejectMultiOpPatchItems(List patchesList) {
    def opKeys = _rmPatchOpKeys()
    patchesList.eachWithIndex { p, i ->
        if (!(p instanceof Map)) return
        def ops = opKeys.findAll { (p as Map).containsKey(it) }
        if (ops.size() > 1) {
            throw new IllegalArgumentException("patches[${i}] carries ${ops.size()} operations (${ops.join(', ')}); each patches item takes exactly one -- split them into separate items, in the order they should run. RM is not touched.")
        }
    }
}

private Integer _rmPatchOpIndex(String op, Object raw) {
    if (raw instanceof Number) return (raw as Number).intValue()
    String text = raw?.toString()?.trim()
    if (text?.isInteger()) return text.toInteger()
    throw new IllegalArgumentException("${op}.index must be an integer index (from hub_get_app_config), got '${raw}'")
}

private boolean _rmModifyTriggerInnerPartial(Map res) {
    ((res?.settingsSkipped as List)?.size() ?: 0) > 0 || res?.verificationFetchFailed == true
}

// One patches[] removeTrigger / modifyTrigger / modifyAction op, via the same helpers as the
// top-level ops. No updateRule here: the batch fires it once at the end.
private Map _rmPatchModifyOp(Integer appId, Map pm, Long reqT0) {
    String op = _rmPatchModifyOpKeys().find { pm.containsKey(it) }
    def spec = pm[op]
    if (!(spec instanceof Map) || spec.index == null) throw new IllegalArgumentException("${op}.index required")
    Integer index = _rmPatchOpIndex(op, spec.index)
    if (op == "removeTrigger") {
        def rtRes = _rmRemoveTrigger(appId, index)
        return [success: rtRes?.success != false, op: op, index: spec.index, removedIndex: rtRes?.removedIndex,
                beforeIndices: rtRes?.beforeIndices, afterIndices: rtRes?.afterIndices, partial: rtRes?.partial == true]
    }
    if (!(spec.mods instanceof Map)) {
        String example = (op == "modifyTrigger") ? "{state: 'on'}" : "{ruleIds: [123]}"
        throw new IllegalArgumentException("${op}.mods is required and must be a Map (e.g. ${example})")
    }
    if (op == "modifyTrigger") {
        def mtRes = _rmModifyTrigger(appId, index, spec.mods as Map) ?: [:]
        return [op: op] + mtRes + [partial: _rmModifyTriggerInnerPartial(mtRes)]
    }
    return [op: op] + (_rmModifyAction(appId, index, spec.mods as Map, reqT0) ?: [:])
}

// Delete every action: the trashAll click enters trash-confirmation mode, which reveals the
// trashActs multi-enum; that is committed as a full page-form submit so RM runs its
// submitOnChange handler in-band (a bare settings write stores the value and deletes nothing).
// Per-row delAct is hidden when one action remains, so this path is also how the last one goes.
private List _rmClearActions(Integer appId) {
    // Every row that exists, not only the compiled view: an un-baked row still occupies its index.
    def liveBefore = _rmLiveActionIndicesFromSettings(_rmFetchStatusJson(appId))
    def indices = ((_rmOrderedActionIndices(appId) ?: []) + liveBefore).collect { it as Integer }.unique().sort()
    if (!indices) return []
    // A previous attempt may have left the page in trash-confirmation mode already.
    def cfg = _rmFetchConfigJson(appId, "selectActions")
    def schema = _rmCollectInputSchema(cfg?.configPage)
    if (!schema?.containsKey("trashActs")) {
        _rmClickAppButton(appId, "trashAll", "trash", "selectActions")
        cfg = _rmFetchConfigJson(appId, "selectActions")
        schema = _rmCollectInputSchema(cfg?.configPage)
    }
    if (!schema?.containsKey("trashActs")) {
        def actCountMsg = "${indices.size()} ${indices.size() == 1 ? 'action' : 'actions'}"
        throw new IllegalStateException("clearActions: trashActs not in selectActions schema after trashAll click for app ${appId} -- RM didn't enter trash mode. The rule has ${actCountMsg} at indices ${indices.sort()} that were NOT deleted.")
    }
    def stringIndices = indices.collect { it.toString() }
    _rmSubmitFullPageForm(appId, "selectActions", cfg, schema, (cfg?.settings ?: [:]),
        ["trashActs": stringIndices])
    // The first check normally passes; the retries cover a rare commit lag (a stuck editAct no-ops
    // the write and never passes).
    def remaining = null
    def stillThere = null
    def retryDelaysMs = [1000, 3000, 6000]
    for (int attempt = 0; attempt < 4; attempt++) {
        if (attempt > 0) pauseExecution(retryDelaysMs[attempt - 1] as Integer)
        remaining = _rmLiveActionIndicesFromSettings(_rmFetchStatusJson(appId))
        stillThere = remaining.intersect(indices)
        if (!stillThere) return indices
    }
    def stillThereCount = stillThere.size()
    def stillThereWord = stillThereCount == 1 ? "action" : "actions"
    // The asyncCommit marker tells the dispatcher this is an exhausted verify window, not a wizard
    // failure (getAsyncCommitMarker is the one spelling the strip sites match).
    throw new IllegalStateException("clearActions: trashActs submit returned 200 but actions ${stillThere.sort()} still present on rule ${appId} after 10s of retries: either an open action editor (state.editAct) is blocking the delete (finish or cancel it in the Hubitat UI, or delete the rule and hub_restore_backup(scope='source') to recreate it) or RM committed late. Verify via hub_get_app_config(appId=${appId}) before retrying; hub_restore_backup if the ${stillThereWord} really did get clobbered. Do not click cancelTrash as a recovery: in trash-confirmation mode it may commit the pending deletes.${getAsyncCommitMarker()}")
}

// Move an action one slot (arrowUp / arrowDn click). Positions come from the compiled action
// order; appSettings key order is arbitrary and cannot verify a move.
Map _rmMoveAction(Integer appId, Integer actionIdx, String direction) {
    def stateAttr = direction == "up" ? "arrowUp" : (direction == "down" ? "arrowDn" : null)
    if (!stateAttr) throw new IllegalArgumentException("moveAction direction must be 'up' or 'down'")
    def beforeOrderRaw = _rmOrderedActionIndices(appId)
    if (beforeOrderRaw == null) {
        throw new IllegalStateException("moveAction(${actionIdx}) refused: the compiled action order (ruleBuilderJson actionList) is unavailable for rule ${appId}, and positions computed from the settings scan are not display order -- a move verified against them could silently reorder the rule. Retry once ruleBuilderJson reads, or rebuild with removeAction + addAction. RM is not touched.")
    }
    if (!beforeOrderRaw.contains(actionIdx)) {
        throw new IllegalArgumentException("moveAction.index ${actionIdx} not found in rule ${appId}. Existing indices: ${beforeOrderRaw.sort().join(', ')}")
    }
    def stuckEditAct = _rmGetStateEditAct(appId)
    if (stuckEditAct != null) {
        throw new IllegalStateException(_rmStuckEditActMessage("moveAction(${actionIdx}, ${direction})", appId, stuckEditAct))
    }
    def beforePosition = beforeOrderRaw.indexOf(actionIdx)
    def isBoundary = (direction == "up" && beforePosition == 0) ||
                     (direction == "down" && beforePosition == beforeOrderRaw.size() - 1)
    _rmClickAppButton(appId, actionIdx.toString(), stateAttr, "selectActions")
    // An unreadable order is unconfirmed, distinct from an empty rule.
    def afterOrderFetched = _rmOrderedActionIndices(appId)
    def afterOrderRaw = afterOrderFetched ?: []
    def afterPosition = afterOrderRaw.indexOf(actionIdx)
    if (afterOrderFetched == null) {
        return [
            success: false,
            asyncCommitLikely: true,
            partial: true,
            index: actionIdx,
            direction: direction,
            beforePosition: beforePosition,
            indicesAfter: null,
            verifyHint: "moveAction(${actionIdx}, ${direction}) could not confirm the position shifted: the click was sent but the compiled action order (ruleBuilderJson actionList) could not be re-read afterwards. VERIFY before retrying -- read the order with hub_get_app_config(appId=${appId}); a blind retry double-shifts the action."
        ]
    }
    def cfg = null
    try { cfg = _rmFetchConfigJson(appId, "selectActions") }
    catch (Exception verifyExc) {
        mcpLog("warn", "rm-native", "moveAction: post-click selectActions fetch failed for app ${appId} (${verifyExc.message}) -- render-error check skipped, action ordering may have left the page in an inconsistent state")
    }
    def renderError = cfg?.configPage?.error
    if (renderError) {
        throw new IllegalStateException("moveAction(${actionIdx}, ${direction}): click returned 200 but selectActions render now errors: ${renderError}")
    }
    // RM correctly no-ops a move at the boundary. Elsewhere the click can commit a couple of
    // seconds late, so one short re-check; still unshifted is reported soft (asyncCommitLikely)
    // because a hard failure would tempt a second move that double-shifts.
    if (!isBoundary) {
        def expectedShift = direction == "up" ? -1 : 1
        def actualShift = afterPosition - beforePosition
        if (actualShift != expectedShift) {
            pauseExecution(1500)
            try {
                afterOrderRaw = _rmOrderedActionIndices(appId) ?: []
                afterPosition = afterOrderRaw.indexOf(actionIdx)
                actualShift = afterPosition - beforePosition
            } catch (Exception recheckExc) {
                mcpLog("warn", "rm-native", "moveAction: post-pause re-check fetch failed for app ${appId} (${recheckExc.message}) -- treating as unconfirmed (soft asyncCommitLikely return)")
            }
        }
        if (actualShift != expectedShift) {
            return [
                success: false,
                asyncCommitLikely: true,
                partial: true,
                index: actionIdx,
                direction: direction,
                beforePosition: beforePosition,
                afterPosition: afterPosition,
                indicesAfter: afterOrderRaw.sort(),
                verifyHint: "moveAction(${actionIdx}, ${direction}) could not confirm the position shifted within the verify window -- the move-arrow click may have committed late, or it may have dropped. VERIFY before retrying: call hub_get_app_config(appId=${appId}) and inspect the action order. If it already changed, the move committed (do NOT retry). If unchanged, the click dropped (safe to retry). Do not blind-retry: a second move on an already-moved action shifts it the other way."
            ]
        }
    }
    return [success: true, index: actionIdx, direction: direction, beforePosition: beforePosition, afterPosition: afterPosition, indicesAfter: afterOrderRaw.sort()]
}

// Navigate to a page by firing the form-submit equivalent of the live
// UI's page transition. Hubitat's UI links (e.g. "Select Actions to Run"
// on mainPage, or the implicit return-to-parent after a sub-page wizard
// commits) submit the current page form WITH a special
// `_action_href_name|<targetPage>|<idx>` marker that tells the server
// to navigate. Verified live in Chrome network capture (2026-04-25).
//
// Used by _rmAddAction to navigate from doActPage back to selectActions
// after the actionDone click — the navigation TRIGGERS the bake of the
// in-flight action into actions[]. Without it, the action's settings
// stay in appSettings but never land in the actions[] map, and
// state.actNdx never advances.
//
// The body is intentionally minimal — the server only needs the
// navigation marker to perform the transition. We don't need to mirror
// every hidden button input on the source page.
private Map _rmNavigateToPage(Integer appId, String fromPage, String targetPage, Integer hrefIndex = 0, String hrefName = "name", Map hrefParams = null, Map cache = null, Long reqT0 = null, boolean recoverEmptyRender = false, boolean uiValidate = false) {
    // For plain page navigation use hrefName="name" + hrefIndex=0. The
    // server treats the marker `_action_href_name|<page>|0` as a generic
    // navigation request.
    //
    // For sub-page hrefs that carry params (e.g. periodic schedule has
    // params={n: 1}), the LIVE UI sends a TWO-PART marker:
    //
    //   _action_href_<linkName>|<page>|<formIndex>=clicked
    //   params_for_action_href_<linkName>|<page>|<formIndex>=<json-of-params>
    //
    // The hub reads `params_for_action_href_*` to set state.<paramKey>
    // before rendering the target page. Without it, the target page
    // renders with `Cannot get property '<paramKey>' on null object`.
    // Verified live by capturing the periodic1 button's
    // XHR body via Chrome devtools (body field count = 20, including
    // params_for_action_href_periodic1|periodic|4 = {"n":1}).
    //
    // Returns the parsed nav response. The response body is the schema
    // of the target page rendered with the param state in scope — the
    // ONLY place that schema lives, since a follow-up GET on the target
    // page won't carry the state. Callers that need the target schema
    // should consume the returned configPage rather than re-fetch.
    // hrefName "__next__" submits the page's own Next button (dynamicPage nextPage), the way the
    // classic UI does: _action_next plus the nextPage hidden field instead of an href marker.
    boolean asNext = (hrefName == "__next__")
    def actionMarker = asNext ? "_action_next" : "_action_href_${hrefName}|${targetPage}|${hrefIndex}".toString()
    def body = [
        id: appId.toString(),
        formAction: "update",
        currentPage: fromPage,
        pageBreadcrumbs: _rmPageBreadcrumbs(appId, fromPage, '["mainPage"]'),
        (actionMarker): asNext ? "Next" : ""
    ]
    if (asNext) body.nextPage = targetPage
    if (hrefParams != null && !hrefParams.isEmpty()) {
        def paramsMarker = "params_for_action_href_${hrefName}|${targetPage}|${hrefIndex}".toString()
        body.put(paramsMarker, groovy.json.JsonOutput.toJson(hrefParams))
    }
    def fromCfg = null
    try {
        fromCfg = _rmFetchConfigJson(appId, fromPage, cache)
        def v = fromCfg?.app?.version
        if (v != null) body.version = v.toString()
    } catch (Exception versionExc) {
        mcpLog("debug", "rm-native", "_rmNavigateToPage: version fetch on ${fromPage} failed for app ${appId} (${versionExc.message}) -- sending POST without version")
    }
    // uiValidate: this navigation stands for a UI page exit or link click, which the UI refuses while
    // the page being left fails its checks. Mapped live in the RM UI (fw 2.5.1.181): leaving
    // selectTriggers after hasAll is the UI's "Done with Trigger Events" (_action_previous), leaving
    // doActPage after actionDone is the UI's own automatic _action_previous, and entering a sub-page
    // is a link click -- all validated by the UI. Internal re-renders of a page the caller is already
    // on pass false.
    if (uiValidate && fromCfg?.configPage instanceof Map) {
        def fromValues = (fromCfg.settings instanceof Map) ? (Map) fromCfg.settings : [:]
        def fromSchema = _rmCollectInputSchema(fromCfg.configPage as Map)
        _requireUiNavigationValid(appId, "leaving page '${fromPage}' for '${targetPage}'".toString(), fromSchema, fromValues)
        // The page submits an unset input's defaultValue with every navigation, so the defaults the
        // check above accepted are saved here too, as the browser would.
        def defaults = fromSchema.findAll { k, meta ->
            meta?.disabled != true && meta?.type != "button" && meta?.defaultValue != null && _uiValueIsEmpty(fromValues.get(k), meta as Map)
        }.collectEntries { k, meta -> [(k): meta.defaultValue] }
        if (defaults) {
            def defaultBody = _rmBuildSettingsBody(appId, defaults, fromSchema)
            defaultBody.remove("id")
            body.putAll(defaultBody)
        }
    }
    try {
        def resp = hubInternalPostForm("/installedapp/update/json", body)
        // This nav POST re-renders the target page + bumps app.version -> drop cached pages for
        // this app. We do NOT store the returned render: the caller consumes navResp.configPage
        // directly, and it carries state.<paramKey> context a later plain GET would not.
        _rmCacheInvalidate(cache, appId)
        if (resp?.data) {
            try {
                def navResp = new groovy.json.JsonSlurper().parseText(resp.data) as Map
                // Opt-in (the walkStep sites only): every other caller navigates to COMMIT state and
                // never reads the render, so a re-read would cost them a pause and a GET for nothing.
                return recoverEmptyRender ? _rmRecoverEmptyNavRender(appId, targetPage, hrefParams, navResp, reqT0) : navResp
            } catch (Exception parseExc) {
                mcpLog("debug", "rm-native", "_rmNavigateToPage: ${fromPage}→${targetPage} response wasn't JSON (${parseExc.message}) -- caller will plain-fetch the schema")
            }
        }
    } catch (Exception postExc) {
        mcpLog("warn", "rm-native", "_rmNavigateToPage: ${fromPage}→${targetPage} POST failed for app ${appId}: ${postExc.message} -- downstream 'X not in schema' errors likely point at this")
    }
    return null
}

private int _rmEmptyRenderPauseMs() {
    // One tuning value for one problem -- RM's transient empty render -- shared by the walker's
    // nav recovery and addAction's doActPage re-read. A method, not a field: the Groovy sandbox
    // rejects static field initializers.
    return 750
}

// RM occasionally answers a navigate with an EMPTY render -- a target page carrying no inputs and
// no hrefs -- that the very next render fills in (observed on a 2.5.1.174 hub: a doActPage with
// nothing on it, no hub error logged, the same op green on every other run). Reported as-is it
// reads as "wizard broke", and at the BEFORE site it makes the following navigate click the
// default link. The nav POST itself is state-committing (it bakes the in-flight action) and is
// not known to be idempotent, so it is never re-sent: the target page is re-READ once instead.
// Three cases are reported as-is: a page reached with href params (its schema lives only in the
// nav response, a GET cannot stand in), a spent time budget, and a re-read that is empty too.
// A null or non-JSON POST never reaches here -- that is precisely the case where the first
// request may have landed, and a retry would double-submit.
private Map _rmRecoverEmptyNavRender(Integer appId, String targetPage, Map hrefParams, Map navResp, Long reqT0) {
    if (!(navResp?.configPage instanceof Map)) return navResp
    if (hrefParams != null && !hrefParams.isEmpty()) {
        // A param-bearing sub-page's schema lives ONLY in this nav response, so a re-read cannot
        // stand in for it and the empty render is reported as-is. Say so, or it leaves no trace.
        def paramSchema = _rmCollectWalkSchema(navResp.configPage as Map, null)
        if (paramSchema.inputs.isEmpty() && paramSchema.hrefs.isEmpty()) {
            mcpLog("debug", "rm-native", "navigate -> ${targetPage} for app ${appId} rendered an empty page, but it was entered with href params whose state a plain GET cannot reproduce; reporting it as-is")
        }
        return navResp
    }
    // A page that RENDERED AN ERROR is not a transient empty render: RM answered, and said why
    // the page has nothing on it (live: a doActPage entered without the wizard state RM expects
    // answers "Cannot invoke method startsWith() on null object" on the POST and on a GET alike).
    // Re-reading it yields the same error; the walker surfaces the error text instead.
    if (navResp.configPage.error != null) return navResp
    def schema = _rmCollectWalkSchema(navResp.configPage as Map, null)
    if (!schema.inputs.isEmpty() || !schema.hrefs.isEmpty()) return navResp
    if (_timeBudgetExceeded(reqT0)) {
        mcpLog("warn", "rm-native", "navigate -> ${targetPage} for app ${appId} rendered an empty page; time budget spent, reporting it as-is")
        return navResp
    }
    mcpLog("warn", "rm-native", "navigate -> ${targetPage} for app ${appId} rendered an empty page; re-reading it once")
    pauseExecution(_rmEmptyRenderPauseMs())
    try {
        def reread = _rmFetchConfigJson(appId, targetPage)
        if (reread?.configPage instanceof Map) {
            def again = _rmCollectWalkSchema(reread.configPage as Map, null)
            if (!again.inputs.isEmpty() || !again.hrefs.isEmpty()) {
                // The re-read's `app` token travels with its configPage: leaving the POST's stale
                // one beside a fresh page hands the next form post an out-of-date app.version.
                def grafted = [configPage: reread.configPage, navRetried: true]
                if (reread.app != null) grafted.app = reread.app
                return navResp + grafted
            }
        }
    } catch (Exception rereadExc) {
        mcpLog("debug", "rm-native", "navigate -> ${targetPage} re-read failed for app ${appId}: ${rereadExc.message}")
    }
    return navResp
}

// Submit a sub-page back to its parent via _action_previous=Done, carrying
// ALL the page's current setting values + sidecar fields. The form-encoded
// Done is what bakes the trigger/action description into the parent's row;
// forward-nav markers (_action_href_*) reach the parent visually but leave
// the row unrendered ("?").
//
// Live-captured 2026-04-25 from Hubitat's Periodic Schedule "Done" XHR
// (firmware 2.5.0.123): the body carries settings[X] values for every
// input on the page + per-type sidecars (X.type, X.multiple,
// checkbox[X]=on for bools, hours/minutes/amPm[X] for times) +
// paramsForPage:{n:1} + pageBreadcrumbs:[mainPage,parent].
//
// Used by both walkStep's "done" op and the high-level addTrigger flow
// for sub-page-driven capabilities (Periodic Schedule, Cron String,
// etc.). Caller passes the current page name + parent page + the href
// params (so paramsForPage routes correctly).
private void _rmSubmitSubPageDone(Integer appId, String page, String parentPage, String hrefName, Map hrefParams, Map cache = null, Map renderedCfg = null) {
    // Sub-pages with route params (periodic.n, etc.) lose state.<paramKey>
    // on a plain GET — `_rmFetchConfigJson(appId, page)` returns the page
    // rendered with state=null, which means the schema is empty/wrong and
    // the version field is unreadable. Round-trip via _rmNavigateToPage to
    // get a fresh response that has the param state in scope, unless the
    // caller already holds that render (renderedCfg, with its app version).
    def cfg = renderedCfg
    if (!(cfg?.configPage instanceof Map) || cfg?.app?.version == null) {
        def hrefIndex = hrefParams?.n != null ? (hrefParams.n as Integer) : 0
        def navResp = _rmNavigateToPage(appId, parentPage ?: page, page, hrefIndex, hrefName ?: "name", hrefParams, cache)
        cfg = navResp ? [configPage: navResp.configPage, app: navResp.app] : _rmFetchConfigJson(appId, page, cache)
    }
    def schema = _rmCollectInputSchema(cfg?.configPage)
    def status = _rmFetchStatusJson(appId)
    def liveSettings = _rmLiveSettingsFromStatus(status)
    def settingsMap = [:]
    schema.each { name, meta ->
        def v = _uiValueOrDefault(liveSettings.get(name), meta)
        if (v == null) v = ""
        settingsMap.put(name, v)
    }
    // The UI refuses this Done until the page passes its own checks; so does the tool.
    _requireUiNavigationValid(appId, "Done on page '${page}'".toString(), schema, settingsMap)
    def body = _rmBuildSettingsBody(appId, settingsMap, schema)
    body.formAction = "update"
    body.currentPage = page
    body._action_previous = "Done"
    if (hrefParams != null && !hrefParams.isEmpty()) {
        body.paramsForPage = groovy.json.JsonOutput.toJson(hrefParams)
    }
    body.pageBreadcrumbs = _rmPageBreadcrumbs(appId, page, parentPage ?
        groovy.json.JsonOutput.toJson(["mainPage", parentPage]) :
        '["mainPage"]')
    // Per-type sidecars the form-encoded UI emits. _rmBuildSettingsBody
    // already handles settings[X], X.type, and X.multiple (only when
    // multi=true). For Done we also need:
    //   - X.multiple=false for non-multi fields
    //   - bool: checkbox[X] = "on" (HTML checkbox marker, always sent)
    //   - time: hours[X], minutes[X], amPm[X] (empty defaults; the time
    //     value rides in settings[X] as "HH:mm")
    schema.each { name, meta ->
        def t = meta?.type?.toString()
        if (meta?.multiple != true) {
            body["${name}.multiple".toString()] = "false"
        }
        if (t == "bool") {
            body["checkbox[${name}]".toString()] = "on"
        } else if (t == "time") {
            body["hours[${name}]".toString()] = ""
            body["minutes[${name}]".toString()] = ""
            body["amPm[${name}]".toString()] = "AM"
        }
    }
    if (cfg?.app?.version != null) body.version = cfg.app.version.toString()
    // Let a Done-POST failure PROPAGATE (hubInternalPostForm throws on a real failure).
    // Callers handle that throw three ways, by intent -- this is why the catch is scoped at
    // each call site, not here:
    //   - addRequiredExpression STPage Done, walkStep done: UNGUARDED -- the throw bubbles to
    //     the dispatcher's backup-and-catch and surfaces as success:false (abort the op).
    //   - addTrigger periodic Done: wraps THIS call and folds the failure into skipped as a
    //     repairable partial (the trigger row already committed).
    //   - hub_set_rule trailing Done: wraps THIS call but discards the exception
    //     (log-only) -- best-effort cleanup, the following updateRule handles the commit.
    // Catching inside the helper would erase all three distinctions, so it does not.
    hubInternalPostForm("/installedapp/update/json", body)
    // The Done POST commits the sub-page + bumps app.version -> drop cached pages for this app.
    _rmCacheInvalidate(cache, appId)
}

// Submit mainPage with `_action_update: Done` — mirrors clicking the
// "Done" button at the bottom of a classic SmartApp's mainPage. This is
// the final commit-and-exit step the live UI fires after every rule
// create/modify session. Without it the rule's session-end state can
// be incomplete (state.<...> markers from in-flight edits not cleaned
// up, subscriptions/scheduledJobs may not fully re-init).
//
// Verified live by capturing the working UI's Done click on
// a rule's mainPage: body carries _action_update=Done plus all mainPage
// input fields echoed with their type + multiple sidecars + (where
// applicable) checkbox/hours/minutes/amPm markers, plus the
// appTypeId/appTypeName/_cancellable fields the classic Done form always
// submits — the hub's update handler 500s without them on standalone
// classic apps (Button Controller-5.1, verified live on fw 2.5.0.143;
// same trio as _commitUserAppInstall). After Done, the server redirects
// to /installedapp/list?section=automations.
//
// Best-effort — even if Done fails the underlying app data is usually
// already committed by prior writes. Returns [done: true] or
// [done: false, reason: <why>] so callers can surface the miss: for
// commitButton:null app types (Basic Rule, Button Controller) the Done
// is the session's ONLY lifecycle event, so a silent miss matters there.
Map _rmSubmitMainPageDone(Integer appId) {
    // Most app types commit on mainPage -- fetch it directly so the common path stays a SINGLE config
    // render (no per-edit regression). Button Rule-5.1's commit page is 'selectActions' instead, so a
    // mainPage fetch fails there ("Cannot find page 'mainPage'"); ONLY on that miss do we probe the app
    // type from the root config and retry on the resolved page (mirrors _rmInitSelectActionsPage).
    def commitPage = "mainPage"
    def cfg
    try {
        cfg = _rmFetchConfigJson(appId, commitPage)
    } catch (Exception mainExc) {
        String resolved = null
        try {
            def rootCfg = _rmFetchConfigJson(appId)
            if (rootCfg?.app?.appType?.name == "Button Rule-5.1") resolved = "selectActions"
        } catch (Exception probeExc) {
            mcpLog("warn", "rm-native", "_rmSubmitMainPageDone: page-graph probe failed for app ${appId} after a mainPage miss (${probeExc.message})")
        }
        if (resolved == null) {
            mcpLog("warn", "rm-native", "_rmSubmitMainPageDone: mainPage fetch failed for app ${appId} (${mainExc.message}) -- skipping Done click; lingering state markers (state.editAct/state.editCond) may corrupt subsequent edits")
            return [done: false, reason: "mainPage fetch failed: ${mainExc.message}".toString()]
        }
        commitPage = resolved
        try { cfg = _rmFetchConfigJson(appId, commitPage) }
        catch (Exception reExc) {
            mcpLog("warn", "rm-native", "_rmSubmitMainPageDone: ${commitPage} fetch failed for app ${appId} (${reExc.message}) -- skipping Done click")
            return [done: false, reason: "${commitPage} fetch failed: ${reExc.message}".toString()]
        }
    }
    def schema = _rmCollectInputSchema(cfg?.configPage)
    def status
    try { status = _rmFetchStatusJson(appId) }
    catch (Exception statusExc) {
        mcpLog("warn", "rm-native", "_rmSubmitMainPageDone: statusJson fetch failed for app ${appId} (${statusExc.message}) -- skipping Done click rather than re-submitting the page blind")
        return [done: false, reason: "statusJson fetch failed: ${statusExc.message}".toString()]
    }
    // Anomalous-shape guard: if statusJson parses but carries no appSettings
    // LIST, rebuilding the form would submit EVERY input as "" -- the
    // all-fields variant of the capability wipe _rmLiveSettingsFromStatus
    // exists to prevent. Skip the Done instead. (A legitimately EMPTY list
    // on a fresh shell still proceeds -- there is nothing to wipe.)
    if (!(status?.appSettings instanceof List)) {
        mcpLog("warn", "rm-native", "_rmSubmitMainPageDone: statusJson for app ${appId} has no appSettings list -- skipping Done click rather than blanking ${schema.size()} inputs")
        return [done: false, reason: "statusJson carried no appSettings list"]
    }
    def liveSettings = _rmLiveSettingsFromStatus(status)
    def settingsMap = [:]
    schema.each { name, meta ->
        def v = _uiValueOrDefault(liveSettings.get(name), meta)
        if (v == null) v = ""
        settingsMap.put(name, v)
    }
    try {
        _requireUiNavigationValid(appId, "Done on page '${commitPage}'".toString(), schema, settingsMap)
    } catch (IllegalStateException uiBlocked) {
        mcpLog("warn", "rm-native", "_rmSubmitMainPageDone: ${uiBlocked.message}")
        return [done: false, reason: uiBlocked.message, uiBlocked: true]
    }
    def body = _rmBuildSettingsBody(appId, settingsMap, schema)
    body.formAction = "update"
    body.currentPage = commitPage
    body._action_update = "Done"
    body.pageBreadcrumbs = _rmPageBreadcrumbs(appId, commitPage, "[]")
    schema.each { name, meta ->
        def t = meta?.type?.toString()
        if (meta?.multiple != true) {
            body["${name}.multiple".toString()] = "false"
        }
        if (t == "bool") {
            body["checkbox[${name}]".toString()] = "on"
        } else if (t == "time") {
            body["hours[${name}]".toString()] = ""
            body["minutes[${name}]".toString()] = ""
            body["amPm[${name}]".toString()] = "AM"
        }
    }
    if (cfg?.app?.version != null) body.version = cfg.app.version.toString()
    // The classic Done form always submits these; without them the hub's
    // update handler 500s on standalone classic apps (verified live on
    // Button Controller-5.1 -- the 500 was previously swallowed here).
    body.appTypeId = ""
    body.appTypeName = ""
    body._cancellable = "false"
    def resp
    try { resp = hubInternalPostForm("/installedapp/update/json", body) } catch (Exception e) {
        mcpLog("warn", "rm-native", "_rmSubmitMainPageDone: final Done POST for app ${appId} failed (${e.message}) -- state.editAct / state.editCond markers from in-flight edits may not have been cleared; next edit session should verify via hub_get_app_config")
        return [done: false, reason: "Done POST failed: ${e.message}".toString()]
    }
    if (resp?.status != null && resp.status >= 400) {
        mcpLog("warn", "rm-native", "_rmSubmitMainPageDone: Done POST for app ${appId} returned status ${resp.status}")
        return [done: false, reason: "Done POST returned status ${resp.status}".toString()]
    }
    return [done: true]
}

private Map _rmWriteSubPageField(Integer appId, String page, String parentPage, String hrefName, Integer hrefIndex, Map hrefParams, String key, Object value, Map cache = null) {
    // For pages with meaningful state.<paramKey> (e.g. periodic schedule's
    // state.n), schema requires the navigate response to set state in scope.
    // For pages with placeholder hrefParams (STPage uses [unUsed: null]),
    // re-firing the action_href on each write RESETS RM's in-flight wizard
    // accumulator (cond-builder, etc.) and breaks multi-step flows that
    // use sub-expressions. Detect "no real params" via all-null values
    // and use a plain GET in that case.
    def hasRealParams = (hrefParams != null) && hrefParams.values().any { it != null }
    def cfg
    if (hasRealParams) {
        def navResp = _rmNavigateToPage(appId, parentPage ?: page, page, hrefIndex, hrefName, hrefParams, cache)
        cfg = navResp ? [configPage: navResp.configPage, app: navResp.app] : _rmFetchConfigJson(appId, page, cache)
    } else {
        cfg = _rmFetchConfigJson(appId, page, cache)
    }
    def schema = _rmCollectInputSchema(cfg?.configPage)
    def beforeKeys = (schema?.keySet() ?: []) as Set
    def beforeValueStr = schema?."${key}"?.value?.toString()
    def beforeRenderHash = _rmSanitizeRenderForHash(cfg?.configPage?.sections).hashCode()
    def body = _rmBuildSettingsBody(appId, [(key): value], schema)
    // `cond` and `oper` are the condition-builder's enum pickers, and their type is known a priori.
    // The request-scoped page cache consumes each write's inline echo, but a submitOnChange echo for
    // these pickers lags one render behind -- a gap-operator (and the close-sub-expression operator)
    // echoes a page that omits the very field the NEXT write needs. If the next write reads that
    // cached schema, _rmBuildSettingsBody finds no `cond`/`oper` entry and DROPS the `<key>.type`
    // sidecar, which RM silently no-ops -- the multi-condition / sub-expression RE failure
    // ("rCapab_<N> not in STPage schema after cond=a"). Emit the picker's enum type explicitly so the
    // write lands against the hub's real (settled) page regardless of the cached schema -- no
    // invalidation, no extra re-fetch (keeps the round-trip win). Live-root-caused 2026-06-21 via the
    // native RM wizard + claude-in-chrome.
    if (key == "cond" || key == "oper") {
        body["${key}.type".toString()] = "enum"
        body["${key}.multiple".toString()] = "false"
    }
    body.formAction = "update"
    body.currentPage = page
    // Live-UI capture (Chrome 2026-04-26, rule 1380 STPage cond=b probe):
    // when writing a setting on a sub-page that's already navigated to,
    // pageBreadcrumbs is `[]` (empty) and the body carries NO action_href
    // markers — those are exclusively for the navigation POST. Including
    // `_action_href_*` in the write re-fires the navigation handler, which
    // resets in-flight wizard state (state.<paramKey>, condition-builder
    // accumulators) and corrupts subsequent renders. Periodic schedule writes keep state.n
    // alive via paramsForPage on the Done back-nav (see _rmSubmitSubPageDone),
    // not via re-firing the action_href on every write.
    body.pageBreadcrumbs = _rmPageBreadcrumbs(appId, page, '[]')
    if (cfg?.app?.version != null) body.version = cfg.app.version.toString()
    def postResp = hubInternalPostForm("/installedapp/update/json", body)
    // Post-write verification. The classic dynamicPage submit RETURNS the re-rendered page model
    // inline (appUI.js jsonSubmit consumes data.configPage with NO follow-up GET), so on the
    // plain-params path (STPage/doActPage condition writes — the hot path) we consume that
    // response as afterCfg, eliminating the separate verify-GET. The hasRealParams path keeps its
    // re-navigate so the state.<paramKey> render context (periodic schedule's state.n) is
    // preserved exactly. Either way afterCfg drives the same schema/value/render diff below
    // (applied vs silently-rejected). RM 5.1 returns 200 for many writes that never land, so the
    // diff — not the POST status — is what routes applied vs skipped.
    def afterCfg = null
    def verifyFetchErr = null
    def parsedPost = hasRealParams ? null : _rmPagePostResponse(postResp)
    if (parsedPost != null) {
        // Consume the inline echo as both the post-write state (routing below) AND the cached page
        // for the next write -- no verify-GET, no invalidation. The condition-builder's `cond`/`oper`
        // picker echoes can lag a render behind (omitting the next field), but the explicit
        // `<key>.type` sidecar above makes the next picker write land against the hub's real page
        // regardless, so caching the lagged echo is safe and keeps the round-trip count minimal.
        _rmCacheStore(cache, appId, page, parsedPost)
        afterCfg = parsedPost
    } else {
        _rmCacheInvalidate(cache, appId)
        try {
            afterCfg = hasRealParams ? _rmNavigateToPage(appId, parentPage ?: page, page, hrefIndex, hrefName, hrefParams, cache) : _rmFetchConfigJson(appId, page, cache)
        } catch (Exception fetchExc) {
            verifyFetchErr = fetchExc.message
            mcpLog("warn", "rm-native", "_rmWriteSubPageField: post-write fetch on ${page} failed for app ${appId} key=${key} (${fetchExc.message}) -- write status is unverified")
        }
    }
    if (afterCfg == null) {
        // Verification fetch failed OR returned null. We CANNOT confirm
        // persistence — return persisted=false with verifyFetchFailed=true
        // so the caller can route this into a distinct skipped bucket
        // ("we don't know if the write landed") rather than falsely
        // declaring it applied (the comparison-against-empty-schema
        // would otherwise produce schemaShifted=true and route to
        // applied without verification).
        return [persisted: false, schemaShifted: false, valueLanded: false, renderShifted: false, verifyFetchFailed: true, verifyError: verifyFetchErr, afterKeys: []]
    }
    def afterSchema = _rmCollectInputSchema(afterCfg?.configPage)
    def afterKeys = (afterSchema?.keySet() ?: []) as Set
    def afterValueStr = afterSchema?."${key}"?.value?.toString()
    def afterRenderHash = _rmSanitizeRenderForHash(afterCfg?.configPage?.sections).hashCode()
    // Stringify list values deterministically so idempotent List writes
    // (already-set multi-enum re-applied) match cleanly via valueLanded.
    // Without this, value instanceof List ⇒ newValueStr=null ⇒ valueLanded=false,
    // and a no-op re-write of a stable list would wrongly route to skipped.
    def newValueStr
    if (value instanceof List) {
        newValueStr = ((value as List).collect { it?.toString() }.findAll { it != null } as List).sort().join(",")
    } else {
        newValueStr = value?.toString()
    }
    def afterValueNorm = afterValueStr
    if (afterValueStr && value instanceof List && afterValueStr.contains(",")) {
        afterValueNorm = afterValueStr.split(",").collect { it.trim() }.findAll { it }.sort().join(",")
    }
    def schemaShifted = (beforeKeys != afterKeys) || (beforeValueStr != afterValueStr)
    def valueLanded = (newValueStr != null) && (afterValueNorm == newValueStr)
    // Wizard-consumed: many sub-page enum pickers (STPage cond, doActPage cond,
    // STPage oper) have the field reset to empty after the wizard advances —
    // before/after schema and field value look identical (both empty), but
    // RM's rendered paragraph text DID shift to reflect the new wizard state.
    // renderShifted catches that case.
    //
    // MASKING CAVEAT: renderShifted is a coarse signal -- it credits `persisted`
    // on ANY post-sanitization paragraph delta, not just one caused by THIS field.
    // For a genuinely-rejected write into a hidden field (e.g. Site B's
    // neither-rendered comparator), an UNRELATED paragraph shift from a sibling
    // field can flip renderShifted true and mask the rejection as `applied` instead
    // of `silent_rejection`. renderShifted stays in `persisted` because it is the
    // only signal for wizard-consumed enum pickers (cond/oper) where valueLanded
    // and settingsLanded cannot fire; the trade-off is a known false-positive on
    // the hidden-field edge. Callers that need a true value-landed guarantee should
    // inspect the discrete valueLanded / settingsLanded flags in the return Map.
    def renderShifted = (beforeRenderHash != afterRenderHash)
    // settingsLanded: catches wizard-consumed fields (RelrDev_N, useLastDev.N,
    // time1, etc.) that disappear from configPage schema after the wizard
    // advances but persist in settings. Applies only on STPage path where
    // afterCfg is a full /installedapp/configure/json response (has settings).
    // On the periodic schedule path, afterCfg is the navigate response shape
    // [configPage:, app:] with no settings key, so settingsValue is null and
    // settingsLanded stays false -- no over-promotion risk.
    //
    // Same normalization strategy as valueLanded: List values are sorted and
    // comma-joined; scalar strings are compared directly. Mirrors the identical
    // fix applied to _rmWriteSettingOnPage for the same field class.
    def settingsValue = afterCfg?.settings?."${key}"
    def settingsValueNorm
    if (settingsValue instanceof List) {
        settingsValueNorm = ((settingsValue as List).collect { it?.toString() }.findAll { it != null } as List).sort().join(",")
    } else if (settingsValue != null) {
        def settingsValueStr = settingsValue.toString()
        if (value instanceof List && settingsValueStr.contains(",")) {
            settingsValueNorm = settingsValueStr.split(",").collect { it.trim() }.findAll { it }.sort().join(",")
        } else {
            settingsValueNorm = settingsValueStr
        }
    }
    def settingsLanded = (newValueStr != null) && (settingsValueNorm != null) && (settingsValueNorm == newValueStr)
    def persisted = schemaShifted || valueLanded || renderShifted || settingsLanded
    return [persisted: persisted, schemaShifted: schemaShifted, valueLanded: valueLanded, renderShifted: renderShifted, settingsLanded: settingsLanded, verifyFetchFailed: false, afterKeys: afterKeys.toList().sort()]
}

// Resolve a collection of mode keys (Integer IDs OR String names like "Day"/
// "Away") into a List of mode IDs as Strings, in insertion order. Used by
// per-mode action subtypes to write the modes list field. The hub's mode
// IDs come from location.modes.
// A mode multi-select is a LIST: RM stores one mode ID per element, so a single
// comma-joined string ("Day,Evening") is looked up as one nonexistent mode. When a
// name fails to resolve AND contains a comma, steer to the list shape rather than the
// opaque "unknown mode" so the caller stops comma-joining. Returns the hint message,
// or null when the value is not a comma-joined string (caller falls through to its
// normal unknown-mode error). Only consulted on the not-found path, so a real
// comma-bearing mode name that DID resolve is never second-guessed. The body carries NO
// surface word -- each caller passes its own `context` (e.g. "addTrigger Mode",
// "conditions[2]") so the message is prefixed once, never doubled. ASCII-only.
private String _rmCommaJoinedModeHint(Object rawName, Collection validNames, String context = "") {
    def s = rawName?.toString()
    if (s == null || !s.contains(",")) return null
    def valid = (validNames ?: []).collect { it?.toString() }.findAll { it }.sort().join(', ')
    def prefix = context ? "${context}: " : ""
    return "${prefix}'${s}' looks like a comma-joined list -- pass a list instead, one entry per mode (e.g. state:['Day','Evening']), not a single comma-separated string. Valid modes: ${valid}"
}

private List _rmResolveModeIds(Collection keys) {
    def hubModes = location?.modes ?: []
    def nameToId = [:]
    hubModes.each { m -> if (m?.name && m?.id != null) nameToId.put(m.name.toString(), m.id.toString()) }
    def out = []
    keys.each { k ->
        def s = k?.toString()
        if (!s) return
        if (s.isInteger()) { out << s; return }
        def mapped = nameToId.get(s)
        if (mapped) { out << mapped; return }
        def commaHint = _rmCommaJoinedModeHint(s, nameToId.keySet(), "Mode")
        if (commaHint) throw new IllegalArgumentException(commaHint)
        throw new IllegalArgumentException("Unknown mode '${s}' -- must be an integer mode ID or one of: ${nameToId.keySet().sort().join(', ')}")
    }
    return out
}

// Cross-check already-resolved mode IDs against the valid set so a raw integer
// ID that no hub mode carries fails loud instead of committing a Mode row with
// no mode selected. Prefer the picker's option keys when the caller discovered a
// non-empty set; otherwise fall back to the hub's location.modes IDs. Returns
// the offending IDs, the valid set, and a human-readable source for the message.
private Map _rmBadModeIds(List resolvedIds, Object pickerOptions) {
    def pickerIds = []
    if (pickerOptions instanceof Map) {
        pickerIds = (pickerOptions as Map).keySet().collect { it?.toString() }
    } else if (pickerOptions instanceof List) {
        (pickerOptions as List).each { o ->
            if (o instanceof Map) { if (o.id != null) pickerIds << o.id.toString() }
            else if (o != null) pickerIds << o.toString()
        }
    }
    def hasPicker = !pickerIds.isEmpty()
    def validIds = hasPicker ? pickerIds : (location?.modes ?: []).collect { it?.id?.toString() }.findAll { it }
    def source = hasPicker ? "the mode picker" : "hub modes"
    def bad = resolvedIds.findAll { !validIds.contains(it?.toString()) }
    return [valid: validIds, bad: bad, source: source]
}

// Resolve a collection of mode keys (IDs or names) into a List of mode NAMES
// in insertion order. Used by subtypes whose field-name suffix is the mode
// name rather than the ID (e.g. delayActs/getDelayPerMode uses
// delayHourDay.<N> / delayHourAway.<N> rather than delayHour1.<N> /
// delayHour4.<N>).
private List _rmResolveModeNames(Collection keys) {
    def hubModes = location?.modes ?: []
    def idToName = [:]
    def nameSet = [] as Set
    hubModes.each { m ->
        if (m?.id != null && m?.name) {
            idToName.put(m.id.toString(), m.name.toString())
            nameSet << m.name.toString()
        }
    }
    def out = []
    keys.each { k ->
        def s = k?.toString()
        if (!s) return
        if (s.isInteger() && idToName.get(s)) { out << idToName.get(s); return }
        if (nameSet.contains(s)) { out << s; return }
        def commaHint = _rmCommaJoinedModeHint(s, nameSet, "Mode")
        if (commaHint) throw new IllegalArgumentException(commaHint)
        throw new IllegalArgumentException("Unknown mode '${s}' -- must be an integer mode ID or one of: ${nameSet.sort().join(', ')}")
    }
    return out
}

// True if `key` (Integer or String mode-name) refers to the same hub mode as
// the numeric `mid` (String form). Used by per-mode action subtypes to look
// up per-mode values keyed by either ID or name.
private boolean _rmModeIdMatches(Object key, String mid) {
    def s = key?.toString()
    if (!s) return false
    if (s == mid) return true
    def hubModes = location?.modes ?: []
    return hubModes.any { m -> m?.name?.toString() == s && m?.id?.toString() == mid }
}

private void _rmInitSelectActionsPage(Integer appId) {
    // Button Rule-5.1's page graph shifts one level vs RM 5.1: its ROOT page
    // is NAMED selectActions (playing mainPage's role -- origLabel/logging
    // inputs plus a "Define Actions" href) and the actual actions editor is
    // selectActionsX. Rendering the EDITOR page is what initializes
    // state.actNdx; tickling the root leaves it null and the doActPage
    // editor input renders as `actType.null`, so every actType.<N> write
    // lands not_in_schema (verified live on fw 2.5.0.143). Once actNdx is
    // initialized the rest of the action wizard (doActN click, doActPage
    // walk, selectActions navigation + verification) works unchanged on
    // Button Rules.
    def editorPage = "selectActions"
    try {
        def rootCfg = _rmFetchConfigJson(appId)
        if (rootCfg?.app?.appType?.name == "Button Rule-5.1") editorPage = "selectActionsX"
    } catch (Exception typeExc) {
        mcpLog("warn", "rm-native", "_rmInitSelectActionsPage: root config fetch failed for app ${appId} (${typeExc.message}) -- assuming the RM 5.1 page graph")
    }
    def cfg
    try { cfg = _rmFetchConfigJson(appId, editorPage) }
    catch (Exception fetchExc) {
        mcpLog("warn", "rm-native", "_rmInitSelectActionsPage: ${editorPage} fetch failed for app ${appId} (${fetchExc.message}) -- state.actNdx may not initialize, first +N click may NPE")
        return
    }
    def body = [
        id: appId.toString(),
        formAction: "update",
        currentPage: editorPage,
        // Kept "mainPage" for BOTH page graphs: on Button Rules the hub accepts
        // it for this transition (verified live -- the e2e authors an action on
        // a fresh button rule through exactly this path).
        pageBreadcrumbs: _rmPageBreadcrumbs(appId, editorPage, '["mainPage"]')
    ]
    def v = cfg?.app?.version
    if (v != null) body.version = v.toString()
    try { hubInternalPostForm("/installedapp/update/json", body) } catch (Exception e) {
        mcpLog("warn", "rm-native", "_rmInitSelectActionsPage: ${editorPage} tickle POST for app ${appId} failed (${e.message}) -- state.actNdx may not initialize; first +N click may throw 'Cannot invoke method startsWith() on null object'")
    }
}

// High-level structured action creation for Rule Machine 5.1.
// Internal _rm helper -- not part of the tool surface.
// True when an action capability selects its operation via an action verb (on/off/open/close/
// lock/setSpeed/...) rather than a trigger-style state value. Sourced from the action discover
// schema: any capability entry declaring an `action` enum requiredField is action-driven, so the
// set covers every such capability (switch, dimmer, color, colorTemp, lock, shade, fan, button,
// ...) rather than a hardcoded few. The Window Shade display name resolves to the shade
// operation picker, so it is recognized too. Case-insensitive. Advisory-only -- used to steer a
// caller who passed state: where action: belongs.
private boolean _rmActionCapUsesActionVerb(String capRaw) {
    def want = capRaw?.trim()?.toLowerCase()
    if (!want) return false
    def actionEnumCaps = (_rmActionSchemaForDiscover()?.capabilities ?: []).findAll { c ->
        (c?.requiredFields instanceof List) && (c.requiredFields as List).any { it?.name == "action" && it?.type == "enum" }
    }.collect { it.name.toString().toLowerCase() }
    // Actuator display name that names the same operation picker as the shade schema entry.
    def resolved = (want == "window shade" || want == "windowshade") ? "shade" : want
    return resolved in actionEnumCaps
}

// Internal _rm helper -- not part of the tool surface.
// Roll back a just-opened action row whose build threw -- an expression opener (ifThen / elseIf /
// repeatWhile / waitExpression) or any plain action refused mid-edit -- so the reject is ATOMIC:
// no orphan row remains (an orphan opener would leave a "block opened but never closed" structural
// imbalance -- a success:false call that mutated the rule). RM's cancel or delete consumes the
// row's index and may leave blank actType/actSubType (and tCustomAttr) keys behind; "gone" means
// no live actType/actSubType value. Returns true when the row is confirmed gone, false when it may
// persist (the caller then surfaces a stuck marker so the envelope points at recovery). Every step
// is tolerant -- a rollback must never mask the original error, and "nothing to cancel/delete" is
// a benign no-op.
private boolean _rmRollbackInFlightAction(Integer appId, Integer idx, boolean condWizardOpen = false) {
    // 1. Close the in-flight condition wizard ONLY when one is genuinely open and not already cancelled.
    //    On the walker-thrown path the walker's cancelInFlightActCond already issued the single allowed
    //    cancelCapab (condWizardOpen is false by then), and on the early-guard path the reject throws
    //    before cond=a so no wizard exists -- in both cases a cancelCapab here would be a forbidden second
    //    fire / a spurious no-op click. delAct is a silent no-op while an in-flight capability edit is open,
    //    so this only runs on paths that opened a slot and threw before it was cancelled (e.g. the schema
    //    did not render rCapab_<N>).
    if (condWizardOpen) {
        try { _rmClickAppButton(appId, "cancelCapab", null, "doActPage") } catch (Exception ignored) { /* nothing to cancel */ }
    }
    // 2. Abort the action editor via doActPage's Cancel button (actionCancel; "cancelAct" is only the
    //    delay "Cancelable?" toggle). This must precede step 3: RM silently no-ops delAct while
    //    state.editAct is set. On success RM drops the uncommitted row's values and consumes its
    //    index; the click is best-effort, so step 3 verifies rather than assumes.
    try { _rmClickAppButton(appId, "actionCancel", null, "doActPage") } catch (Exception cancelExc) {
        // Editor may already be closed; step 3 verifies. Log the cause so a failing cancel is traceable.
        mcpLog("debug", "rm-native", "_rmRollbackInFlightAction: actionCancel click failed for app ${appId} action ${idx} (${cancelExc.message ?: cancelExc.toString()})")
    }
    // 3. If the row still persists in settings (actType.<idx> written by this call), delete it.
    try {
        // Settings, not the compiled list: an un-baked orphan row never reaches
        // ruleBuilderJson.actionList, so a compiled read would call it already gone.
        // Live rows only: the key-count view would report a blank row as present (a false
        // wizardStuck) and delete a row that is already empty.
        if (!_rmLiveActionIndicesFromSettings(_rmFetchStatusJson(appId)).contains(idx)) return true
        _rmDeleteAction(appId, idx, true)   // our own uncommitted row: skip the structural pre-flight
        return !_rmLiveActionIndicesFromSettings(_rmFetchStatusJson(appId)).contains(idx)
    } catch (Exception delExc) {
        mcpLog("warn", "rm-native", "_rmRollbackInFlightAction: rollback of action ${idx} failed for app ${appId} (${delExc.message ?: delExc.toString()}) -- the action row may persist; caller surfaces a stuck-orphan marker so the response points at recovery")
        return false
    }
}

// Maps an action spec to its RM wizard actType / actSubType and the @N field writes. It never writes
// to the hub; it reads hub modes, hub variables and the rule's local variables, and stashes the
// derived source-mode details on the spec it is given. Every argument check lives here, so running
// it on a copy is a complete argument pre-flight.
private Map _rmMapActionSpec(Integer appId, Map actionSpec, String cap, String action) {
    // The body runs as a closure so its call sites live in the closure's own class: the app class is
    // near the JVM 65,535 constant-pool limit. Behaviour is unchanged.
    def _impl = {
    def actType = null
    def actSubType = null
    def fields = [:]  // key: field name with @N placeholder, value: the value
    def deviceIds = actionSpec.deviceIds
    def skipped = []

    if (cap == "switch" && actionSpec.containsKey("onlyOn")) {
        throw new IllegalArgumentException("switch: 'onlyOn' is now 'onlyIfChanged' -- RM's \"Command only switches that are off?\" (for on) / \"... that are on?\" (for off) option sends the command only to switches not already in the requested state. RM is not touched.")
    }
    Map catalog = _rmActionCatalog()
    Map catEntry = catalog[cap == "variable" ? "setVariable" : cap] as Map
    if (catEntry == null) {
        throw new IllegalArgumentException("Unsupported capability '${cap}' -- supported: ${catalog.keySet().join(', ')}. For an action RM offers that none of these maps, use rawSettings={fieldName: value, ...} with the @N index placeholder.")
    }
    Map catActions = (catEntry.actions instanceof Map) ? (catEntry.actions as Map) : null
    Map catAction = (catActions != null) ? (catActions[action] as Map) : catEntry
    if (catActions != null && catAction == null) {
        if (action == null && actionSpec.command != null) {
            throw new IllegalArgumentException("${cap} actions use the 'action' field, not 'command' (got command='${actionSpec.command}'). Supported: ${catActions.keySet().join(', ')}")
        }
        throw new IllegalArgumentException("Unknown ${cap} action '${action}' -- supported: ${catActions.keySet().join(', ')}")
    }
    String specLabel = action ? "${cap}.${action}".toString() : "${cap} action".toString()
    (((catEntry.slots ?: []) as List) + ((catAction.is(catEntry) ? [] : (catAction.slots ?: [])) as List)).each { Map f ->
        if (f.name) _rmRequireCatalogValue(specLabel, f, actionSpec.get(f.name))
    }
    // requires: a name is a required field; an inner list, fields of which one is needed.
    def perLevel = { String k -> ((catEntry.get(k) ?: []) as List) + ((catAction.is(catEntry) ? [] : (catAction.get(k) ?: [])) as List) }
    def slotsAll = ((catEntry.slots ?: []) as List) + ((catAction.is(catEntry) ? [] : (catAction.slots ?: [])) as List)
    def boolField = { String n -> slotsAll.find { Map f -> f.name == n }?.type == "Boolean" }
    perLevel("requires").each { r ->
        // A false Boolean field is "not set" for a one-of: useLastEventDevice:false names no device.
        if (r instanceof List && !r.any { def got = actionSpec.get(it?.toString()); got != null && !(got == false && boolField(it.toString())) }) {
            throw new IllegalArgumentException("${specLabel} requires one of ${r.join(', ')}.".toString())
        }
        if (r instanceof String && actionSpec.get(r) == null) throw new IllegalArgumentException("${specLabel} requires '${r}'.".toString())
    }
    perLevel("exclusive").each { List g ->
        def given = g.findAll { def got = actionSpec.get(it?.toString()); got != null && !(got == false && boolField(it.toString())) }
        if (given.size() > 1) throw new IllegalArgumentException("${specLabel} takes one of ${g.join(', ')}, not ${given.join(' and ')}.".toString())
    }
    if (!(catEntry.custom == true || catAction.custom == true)) {
        return _rmMapCatalogAction(cap, action, actionSpec, catEntry, catAction)
    }

    if (cap == "switch") {
        actType = "switchActs"
        switch (action) {
            case "setPerMode":
                actSubType = "getModeSwitch"
                if (!(actionSpec.perMode instanceof Map) || actionSpec.perMode.isEmpty()) {
                    throw new IllegalArgumentException("switch.setPerMode requires perMode={modeIdOrName: 'on'|'off', ...}")
                }
                def modeIds = _rmResolveModeIds(actionSpec.perMode.keySet())
                fields = ["switchM.@N": deviceIds, "switchModes.@N": modeIds]
                modeIds.each { mid ->
                    def val = actionSpec.perMode.find { _rmModeIdMatches(it.key, mid) }?.value
                    if (val != null) fields["switch${mid}.@N"] = val.toString()
                }
                break
            case "choosePerMode":
                actSubType = "getChooseSwitch"
                if (!(actionSpec.perMode instanceof Map) || actionSpec.perMode.isEmpty()) {
                    throw new IllegalArgumentException("switch.choosePerMode requires perMode={modeIdOrName: {on: [devIds], off: [devIds]}, ...}")
                }
                def modeIds = _rmResolveModeIds(actionSpec.perMode.keySet())
                fields = ["chooseModes.@N": modeIds]
                modeIds.each { mid ->
                    def cfg = actionSpec.perMode.find { _rmModeIdMatches(it.key, mid) }?.value
                    if (cfg instanceof Map) {
                        if (cfg.on != null) fields["chooseSwOn${mid}.@N"] = cfg.on
                        if (cfg.off != null) fields["chooseSwOff${mid}.@N"] = cfg.off
                    }
                }
                break
        }
    } else if (cap == "dimmer") {
        actType = "dimmerActs"
        switch (action) {
            case "setLevel":
                actSubType = "getSetDimmer"
                fields = ["dimA.@N": deviceIds]
                if (actionSpec.levelVariable != null) {
                    fields["uVar.@N"] = true
                    fields["xVar.@N"] = actionSpec.levelVariable
                } else {
                    if (actionSpec.level == null) throw new IllegalArgumentException("dimmer.setLevel requires 'level' (0-100) or 'levelVariable' (hub variable name).")
                    fields["dimLA.@N"] = actionSpec.level
                }
                if (actionSpec.fadeSeconds != null) fields["dimRA.@N"] = actionSpec.fadeSeconds
                break
            case "setLevelPerMode":
                actSubType = "getDimmersPerMode"
                if (!(actionSpec.perMode instanceof Map) || actionSpec.perMode.isEmpty()) {
                    throw new IllegalArgumentException("dimmer.setLevelPerMode requires perMode={modeIdOrName: level (0-100), ...}")
                }
                def modeIds = _rmResolveModeIds(actionSpec.perMode.keySet())
                fields = ["dimM.@N": deviceIds, "dimmerModes.@N": modeIds]
                modeIds.each { mid ->
                    def val = actionSpec.perMode.find { _rmModeIdMatches(it.key, mid) }?.value
                    if (val != null) fields["level${mid}.@N"] = val
                }
                if (actionSpec.fadeSeconds != null) fields["dimMR.@N"] = actionSpec.fadeSeconds
                break
        }
    } else if (cap == "color") {
        actType = "dimmerActs"
        switch (action) {
            case "setColor":
                if (actionSpec.colorName == null && !(actionSpec.rawSettings instanceof Map)) throw new IllegalArgumentException("color.setColor requires 'colorName' (e.g. 'Red') or a custom HSV color via rawSettings (colorH.@N).")
                actSubType = "getSetColor"
                fields = ["bulbs.@N": deviceIds]
                if (actionSpec.colorName != null) fields["color.@N"] = actionSpec.colorName
                if (actionSpec.level != null) fields["colorLevel.@N"] = actionSpec.level
                break
            case "setColorPerMode":
                actSubType = "getColorPerMode"
                if (!(actionSpec.perMode instanceof Map) || actionSpec.perMode.isEmpty()) {
                    throw new IllegalArgumentException("color.setColorPerMode requires perMode={modeIdOrName: {color: 'Red', level: 70}, ...}")
                }
                def modeIds = _rmResolveModeIds(actionSpec.perMode.keySet())
                fields = ["bulbsM.@N": deviceIds, "colorModes.@N": modeIds]
                modeIds.each { mid ->
                    def cfg = actionSpec.perMode.find { _rmModeIdMatches(it.key, mid) }?.value
                    if (cfg instanceof Map) {
                        if (cfg.color != null) fields["color${mid}.@N"] = cfg.color
                        if (cfg.level != null) fields["colorLevel${mid}.@N"] = cfg.level
                    }
                }
                break
        }
    } else if (cap == "colorTemp") {
        actType = "dimmerActs"
        switch (action) {
            case "setColorTempPerMode":
                actSubType = "getColorTempPerMode"
                if (!(actionSpec.perMode instanceof Map) || actionSpec.perMode.isEmpty()) {
                    throw new IllegalArgumentException("colorTemp.setColorTempPerMode requires perMode={modeIdOrName: {kelvin: 2700, level: 70}, ...}")
                }
                def modeIds = _rmResolveModeIds(actionSpec.perMode.keySet())
                fields = ["ctM.@N": deviceIds, "ctModes.@N": modeIds]
                modeIds.each { mid ->
                    def cfg = actionSpec.perMode.find { _rmModeIdMatches(it.key, mid) }?.value
                    if (cfg instanceof Map) {
                        if (cfg.kelvin != null) fields["ctMode${mid}.@N"] = cfg.kelvin
                        // RM's level key really is ctMode<mode>.<idx>Level, suffix after the index.
                        if (cfg.level != null) fields["ctMode${mid}.@NLevel"] = cfg.level
                    }
                }
                break
        }
    } else if (cap == "thermostat") {
        if (actionSpec.mode == null && actionSpec.fanMode == null && actionSpec.heatingSetpoint == null &&
            actionSpec.adjustHeating == null && actionSpec.coolingSetpoint == null && actionSpec.adjustCooling == null) {
            throw new IllegalArgumentException("thermostat action requires at least one setting: 'mode', 'fanMode', 'heatingSetpoint', 'adjustHeating', 'coolingSetpoint' or 'adjustCooling'.")
        }
        actType = "lockActs"
        actSubType = "getSetThermostat"
        fields = ["thermo.@N": deviceIds]
        if (actionSpec.mode != null)              fields["thermoMode.@N"] = actionSpec.mode
        if (actionSpec.fanMode != null)           fields["thermoFan.@N"] = actionSpec.fanMode
        if (actionSpec.heatingSetpoint != null)   fields["thermoSetHeat.@N"] = actionSpec.heatingSetpoint
        if (actionSpec.adjustHeating != null)     fields["thermoAdjHeat.@N"] = actionSpec.adjustHeating
        if (actionSpec.coolingSetpoint != null)   fields["thermoSetCool.@N"] = actionSpec.coolingSetpoint
        if (actionSpec.adjustCooling != null)     fields["thermoAdjCool.@N"] = actionSpec.adjustCooling
    } else if (cap == "hsm") {
        // alarm.<N> takes RM's bare HSM tokens, not the hsmArm* tokens of the Vue UI.
        actType = "lockActs"
        actSubType = "getSetHSM"
        def hsmCmd = _rmCatalogValueCanonical([values: _rmHsmCommands()], actionSpec.command)?.toString()
        if (!(hsmCmd in _rmHsmCommands())) {
            throw new IllegalArgumentException("hsm action requires 'command' as one of: ${_rmHsmCommands().join(', ')}. Got '${hsmCmd}'. (No 'armAll' -- use 'armRules'. getSetHSM appears only when HSM is installed on the hub.)")
        }
        fields = ["alarm.@N": hsmCmd]
    } else if (cap == "mode") {
        actType = "modeActs"
        actSubType = "getSetMode"
        if (actionSpec.modeId == null && actionSpec.modeName == null) {
            throw new IllegalArgumentException("mode action requires modeId (Integer) or modeName (String)")
        }
        def modeValue
        if (actionSpec.modeId != null) {
            modeValue = actionSpec.modeId
        } else {
            def name = actionSpec.modeName.toString()
            def hubModes = location?.modes ?: []
            def matched = hubModes.find { it?.name?.toString()?.equalsIgnoreCase(name) }
            if (!matched) {
                def available = hubModes.collect { it?.name }.findAll { it }.sort().join(', ')
                def availableDisplay = available ?: "(none -- hub returned no modes; verify hub state via hub_list_modes)"
                def commaHint = _rmCommaJoinedModeHint(name, hubModes.collect { it?.name }, "mode action")
                if (commaHint) throw new IllegalArgumentException(commaHint)
                throw new IllegalArgumentException("mode action: modeName '${name}' not found. Available modes: ${availableDisplay}")
            }
            modeValue = matched.id
        }
        fields = ["mode.@N": modeValue]
    } else if (cap == "variable" || cap == "setVariable" || cap == "setLocalVariable") {
        // Locals and globals share RM's getSetVariable action and xVarV picker; only the
        // namespace the target name is validated against differs. xVarV gates numOp, numOp gates
        // the source-mode fields (xVar3, customDev/tCustomAttr, the math operands), which the
        // __setVariable* post-write blocks discover from the live schema.
        actType = "modeActs"
        actSubType = "getSetVariable"
        boolean isLocalVar = (cap == "setLocalVariable")
        String capLabel = isLocalVar ? "setLocalVariable" : "setVariable"
        if (!actionSpec.variable) {
            throw new IllegalArgumentException("${capLabel} action requires 'variable' (${isLocalVar ? "local variable name" : "hub variable name"})")
        }
        // != null: value 0 is a valid constant.
        def srcModes = []
        if (actionSpec.value != null) srcModes << "value"
        if (actionSpec.sourceVariable != null) srcModes << "sourceVariable"
        if (actionSpec.fromDevice != null) srcModes << "fromDevice"
        if (actionSpec.math != null) srcModes << "math"
        if (srcModes.size() > 1) {
            throw new IllegalArgumentException("${capLabel} action: provide exactly one source mode (value, sourceVariable, fromDevice, or math); got ${srcModes.size()} (${srcModes.join(', ')})")
        }
        if (srcModes.isEmpty()) {
            throw new IllegalArgumentException("${capLabel} action requires exactly one source mode: 'value' (numeric constant), 'sourceVariable' (variable name to copy from), 'fromDevice' ({deviceId, attribute}), or 'math' ({left, op, right})")
        }
        if (actionSpec.value != null && !(actionSpec.value instanceof Number)) {
            throw new IllegalArgumentException("${capLabel}: 'value' must be a numeric constant (Integer, Long, BigDecimal, etc.); got '${actionSpec.value}' -- use a number literal, not a string")
        }
        def mathLeft = null, mathOp = null, mathRight = null
        boolean mathRightProvided = false
        if (actionSpec.fromDevice != null) {
            if (!(actionSpec.fromDevice instanceof Map)) {
                throw new IllegalArgumentException("${capLabel}: 'fromDevice' must be a Map {deviceId, attribute}; got '${actionSpec.fromDevice}'")
            }
            def fdDev = actionSpec.fromDevice.deviceId
            def fdAttr = actionSpec.fromDevice.attribute
            if (fdDev == null) throw new IllegalArgumentException("${capLabel} fromDevice requires 'deviceId' (the source device id)")
            if (!fdAttr?.toString()?.trim()) throw new IllegalArgumentException("${capLabel} fromDevice requires 'attribute' (the device attribute name to read)")
            // Not checked against findDevice(): RM's customDev picker spans ALL hub devices, not
            // just the MCP-exposed ones; RM's revealed picker is the authoritative check.
            if (_rmAsPositiveDeviceId(fdDev) == null) {
                throw new IllegalArgumentException("${capLabel} fromDevice: 'deviceId' must be a positive integer device id; got '${fdDev}'")
            }
        }
        if (actionSpec.math != null) {
            if (!(actionSpec.math instanceof Map)) {
                throw new IllegalArgumentException("${capLabel}: 'math' must be a Map {left, op, right}; got '${actionSpec.math}'")
            }
            def _trimOperand = { o -> (o instanceof CharSequence) ? o.toString().trim() : o }
            mathLeft = _trimOperand(actionSpec.math.left)
            mathOp = actionSpec.math.op?.toString()?.trim()
            mathRightProvided = actionSpec.math.containsKey("right") && actionSpec.math.right != null
            mathRight = _trimOperand(actionSpec.math.right)
            if (mathLeft == null) throw new IllegalArgumentException("${capLabel} math requires 'left' (a hub variable name or a number)")
            if (!mathOp) throw new IllegalArgumentException("${capLabel} math requires 'op' (an operator). Binary: ${_rmMathBinaryOps().join(' ')}; Unary: ${_rmMathUnaryOps().join(' ')}")
            boolean isBinary = _rmMathBinaryOps().contains(mathOp)
            boolean isUnary = _rmMathUnaryOps().contains(mathOp)
            if (!isBinary && !isUnary) {
                throw new IllegalArgumentException("${capLabel} math: operator '${mathOp}' is not recognized. Binary: ${_rmMathBinaryOps().join(' ')}; Unary: ${_rmMathUnaryOps().join(' ')}")
            }
            if (isBinary && !mathRightProvided) {
                throw new IllegalArgumentException("${capLabel} math: binary operator '${mathOp}' requires a second operand (right)")
            }
            if (isUnary && mathRightProvided) {
                throw new IllegalArgumentException("${capLabel} math: unary operator '${mathOp}' takes no second operand -- remove 'right'")
            }
            [[role: "left", operand: mathLeft], [role: "right", operand: mathRight]].each { o ->
                if (o.operand != null && !(o.operand instanceof Number) && !(o.operand instanceof CharSequence)) {
                    throw new IllegalArgumentException("${capLabel} math: ${o.role} operand must be a number or a hub variable name; got '${o.operand}'")
                }
            }
        }
        // RM's xVarV picker lists its section headers as selectable values.
        def pickerSectionHeaders = [" --LOCAL VARIABLES--", " --HUB VARIABLES--"]
        if (actionSpec.variable?.toString() in pickerSectionHeaders) {
            throw new IllegalArgumentException("${capLabel}: '${actionSpec.variable}' is a picker section header, not a variable name. Pass a real variable name.")
        }
        // Both namespaces are normalized to {name -> [type: token]}; null = source unavailable
        // (validation skipped, flagged in skipped), empty = every name invalid.
        def varDisplayKind = isLocalVar ? "local variables" : "hub variables"
        def emptyDisplay = isLocalVar ? "(none -- rule has no local variables defined)" : "(none -- hub has no variables defined)"
        def allVars = null
        // A String target has no numOp; its copy selector is valStringOp="Copy variable".
        String copyOpField = "numOp"
        if (isLocalVar) {
            // A create's pre-flight has no rule yet, and a new rule has no locals.
            def localsRead = appId != null ? _rmReadLocalVarsMap(appId) : [ok: true, vars: [:]]
            if (localsRead?.ok) {
                allVars = [:]
                localsRead.vars.each { lvName, lvMeta ->
                    allVars.put(lvName?.toString(), [type: (lvMeta instanceof Map ? lvMeta?.type?.toString() : null)])
                }
            } else {
                mcpLog("warn", "rm-native", "setLocalVariable: local-variable read (statusJson appState.allLocalVars) unavailable for app ${appId} (${localsRead.error}) -- variable-name validation skipped; write will proceed unvalidated")
                skipped << [key: "variable-validation", reason: "api_unavailable"]
            }
        } else {
            try { allVars = getAllGlobalVars() } catch (Exception e) {
                mcpLog("warn", "rm-native", "setVariable: getAllGlobalVars() unavailable (${e.class.simpleName}: ${e.message ?: e.toString()}) -- variable-name validation skipped; write will proceed unvalidated")
                skipped << [key: "variable-validation", reason: "api_unavailable"]
            }
        }
        if (allVars != null) {
            def allVarNames = (allVars.keySet() ?: []) as List<String>
            def targetVar = actionSpec.variable.toString()
            if (!allVarNames.any { it?.toString() == targetVar }) {
                def available = allVarNames.isEmpty() ? emptyDisplay : allVarNames.sort().join(', ')
                throw new IllegalArgumentException("${capLabel}: variable '${targetVar}' not found. Available ${varDisplayKind}: ${available}")
            }
            // RM renders numOp (and so value / fromDevice / math) only for a Number or Decimal
            // target; against any other target those fields never reveal. Fail closed on an
            // unreadable type token.
            if (actionSpec.value != null || actionSpec.fromDevice != null || actionSpec.math != null) {
                def targetMeta = allVars.get(targetVar)
                def targetType = (targetMeta instanceof Map) ? targetMeta?.type?.toString() : null
                if (!_rmIsNumericVarType(targetType)) {
                    def modeName = actionSpec.value != null ? "numeric-constant (value)"
                        : (actionSpec.fromDevice != null ? "device-attribute (fromDevice)" : "variable-math (math)")
                    def typeDisplay = targetType ? "of type '${targetType}'" : "of an unreadable type (its variable metadata did not carry a recognizable type token)"
                    throw new IllegalArgumentException("${capLabel}: the ${modeName} source mode requires a Number or Decimal target variable; '${targetVar}' is ${typeDisplay}. To assign a String target use 'sourceVariable' (copy from another variable); a Boolean/DateTime target is not supported by these source modes, so build it in the RM UI.")
                }
            }
            if (actionSpec.sourceVariable != null) {
                def copyTargetMeta = allVars.get(targetVar)
                def copyTargetType = (copyTargetMeta instanceof Map) ? copyTargetMeta?.type?.toString()?.toLowerCase() : null
                if (copyTargetType == "string") {
                    copyOpField = "valStringOp"
                } else if (copyTargetType in ["boolean", "datetime"]) {
                    throw new IllegalArgumentException("${capLabel}: sourceVariable copy into a ${copyTargetType} target ('${targetVar}') is not supported yet -- its copy picker has not been mapped yet. Copy into a Number, Decimal or String variable, or build this action in the RM UI.")
                } else if (!_rmIsNumericVarType(copyTargetType)) {
                    throw new IllegalArgumentException("${capLabel}: cannot read the type of target variable '${targetVar}' (its variable metadata did not carry a recognizable type token), so the copy selector cannot be chosen: numOp for a Number/Decimal target, valStringOp for a String target.")
                }
            }
            // RM's source/operand pickers span both namespaces, so a local target may copy from a
            // global: for setLocalVariable only the revealed picker (post-write) checks sources.
            if (!isLocalVar && actionSpec.sourceVariable != null) {
                def srcVar = actionSpec.sourceVariable.toString()
                if (!allVarNames.any { it?.toString() == srcVar }) {
                    def available = allVarNames.isEmpty() ? emptyDisplay : allVarNames.sort().join(', ')
                    throw new IllegalArgumentException("${capLabel}: sourceVariable '${srcVar}' not found. Available ${varDisplayKind}: ${available}")
                }
            }
            if (!isLocalVar && actionSpec.math != null) {
                [[role: "left", operand: mathLeft], [role: "right", operand: mathRight]].each { o ->
                    if (o.operand != null && !(o.operand instanceof Number)) {
                        def opVar = o.operand.toString()
                        if (!allVarNames.any { it?.toString() == opVar }) {
                            def available = allVarNames.isEmpty() ? emptyDisplay : allVarNames.sort().join(', ')
                            throw new IllegalArgumentException("${capLabel} math: ${o.role} operand variable '${opVar}' not found. Available ${varDisplayKind}: ${available}")
                        }
                    }
                }
            }
        }
        fields = ["xVarV.@N": actionSpec.variable.toString()]
        // The post-write blocks run outside capLabel's scope.
        actionSpec.__setVariableCapLabel = capLabel
        if (actionSpec.sourceVariable != null) {
            // numOp takes the full word "variable"; "var" is rejected.
            if (copyOpField == "valStringOp") fields["valStringOp.@N"] = "Copy variable"
            else fields["numOp.@N"] = "variable"
            actionSpec.__setVariableSourceOp = copyOpField
            actionSpec.__setVariableSourceTypeUnread = (allVars == null)
            actionSpec.__setVariableSourceVar = actionSpec.sourceVariable.toString()
        } else if (actionSpec.fromDevice != null) {
            fields["numOp.@N"] = "device attribute"
            actionSpec.__setVariableFromDevice = [
                deviceId: _rmAsPositiveDeviceId(actionSpec.fromDevice.deviceId).toString(),
                attribute: actionSpec.fromDevice.attribute.toString()
            ]
        } else if (actionSpec.math != null) {
            fields["numOp.@N"] = "variable math"
            actionSpec.__setVariableMath = [
                left: mathLeft, op: mathOp, right: mathRight
            ]
        } else {
            fields["numOp.@N"] = _rmSetVariableValueNumOp(actionSpec)
            fields["valNumber.@N"] = actionSpec.value
        }
    } else if (cap == "runCommand") {
        // myCapab, devices and cCmd each reveal the next and are validated against RM's live
        // pickers in _rmWriteRunCommandFields; parameters in _rmWriteRunCommandParams.
        actType = "modeActs"
        actSubType = "getDefinedAction"
        if (!actionSpec.command) throw new IllegalArgumentException("runCommand requires 'command' (the device driver method name)")
        fields = ["useLastDev.@N": (actionSpec.useLastEventDevice == true)]
        if (actionSpec.parameters instanceof List && !actionSpec.parameters.isEmpty()) {
            actionSpec.parameters.eachWithIndex { p, paramIdx ->
                if (!(p instanceof Map)) return  // bare scalar entries pass through
                def pType = p.type
                def pValue = p.value
                def pVariable = p.variable
                if (pValue != null && pVariable != null) {
                    throw new IllegalArgumentException("runCommand parameter slot ${paramIdx + 1}: provide 'value' OR 'variable', not both")
                }
                if (pValue == null && pVariable == null) {
                    throw new IllegalArgumentException("runCommand parameter slot ${paramIdx + 1}: Map entry must include 'value' (literal) or 'variable' (hub variable name)")
                }
                if (pType != null) {
                    def t = pType.toString().toLowerCase()
                    if (!(t in ["string", "number", "decimal"])) {
                        throw new IllegalArgumentException("runCommand parameter type '${pType}' invalid -- must be 'string', 'number', or 'decimal'")
                    }
                    if (pValue != null && t in ["number", "decimal"] && !(pValue instanceof Number)) {
                        throw new IllegalArgumentException("runCommand parameter slot ${paramIdx + 1}: type '${t}' requires a numeric 'value' (Integer, Long, BigDecimal, etc.); got '${pValue}' -- use a number literal, not a string")
                    }
                }
                // A variable name is checked against the revealed xVar<P> enum, RM's own scope.
            }
            actionSpec.__runCommandExtraParams = actionSpec.parameters
        }
    } else if (cap == "button") {
        actType = "switchActs"
        switch (action) {
            case "pushPerMode":
                actSubType = "getPushButtonPerMode"
                if (!(actionSpec.perMode instanceof Map) || actionSpec.perMode.isEmpty()) {
                    throw new IllegalArgumentException("button.pushPerMode requires perMode={modeIdOrName: buttonNumber, ...}")
                }
                def modeIds = _rmResolveModeIds(actionSpec.perMode.keySet())
                fields = ["pushMBtn.@N": deviceIds, "buttonModes.@N": modeIds]
                modeIds.each { mid ->
                    def n = actionSpec.perMode.find { _rmModeIdMatches(it.key, mid) }?.value
                    if (n != null) fields["button${mid}.@N"] = n
                }
                break
            case "choosePerMode":
                actSubType = "getChooseButton"
                if (!(actionSpec.perMode instanceof Map) || actionSpec.perMode.isEmpty()) {
                    throw new IllegalArgumentException("button.choosePerMode requires perMode={modeIdOrName: [deviceIds], ...}")
                }
                if (actionSpec.buttonNumber == null) throw new IllegalArgumentException("button.choosePerMode requires 'buttonNumber'")
                def modeIds = _rmResolveModeIds(actionSpec.perMode.keySet())
                fields = ["chooseButtonModes.@N": modeIds, "chooseButtonNum.@N": actionSpec.buttonNumber]
                modeIds.each { mid ->
                    def devs = actionSpec.perMode.find { _rmModeIdMatches(it.key, mid) }?.value
                    if (devs != null) fields["chooseButton${mid}.@N"] = devs
                }
                break
        }
    } else if (cap == "privateBoolean") {
        // pvTF true = set False.
        actType = "rulesActs"
        actSubType = "getSetPrivateBoolean"
        fields = [
            "pvRuleType.@N": "Rule Machine",
            "pvTF.@N": !(actionSpec.value as Boolean),
            "privateT.@N": _rmNormalizeRuleIdsForWrite(actionSpec.ruleIds ?: deviceIds, true)
        ]
    } else if (cap == "runRule") {
        actType = "rulesActs"
        actSubType = "getRuleActions"
        fields = [
            "runRuleType.@N": "Rule Machine",
            "ruleAct.@N": _rmNormalizeRuleIdsForWrite(actionSpec.ruleIds ?: deviceIds)
        ]
    } else if (cap == "cancelTimers") {
        actType = "rulesActs"
        actSubType = "getStopActions"
        fields = [
            "stopRuleType.@N": "Rule Machine",
            "stopAct.@N": _rmNormalizeRuleIdsForWrite(actionSpec.ruleIds ?: deviceIds)
        ]
    } else if (cap == "pauseRule") {
        // pR true = resume.
        actType = "rulesActs"
        actSubType = "getPauseResumeRules"
        def pauseRuleIds = _rmNormalizeRuleIdsForWrite(actionSpec.ruleIds ?: deviceIds)
        switch (action) {
            case "pause":  fields = ["pR.@N": false, "pauseRuleType.@N": "Rule Machine", "pauseRule.@N": pauseRuleIds]; break
            case "resume": fields = ["pR.@N": true,  "pauseRuleType.@N": "Rule Machine", "pauseRule.@N": pauseRuleIds]; break
        }
    } else if (cap == "delay") {
        if (actionSpec.variable == null && actionSpec.hours == null && actionSpec.minutes == null && actionSpec.seconds == null) {
            throw new IllegalArgumentException("delay action requires a duration: 'hours', 'minutes' and/or 'seconds', or 'variable' (a hub variable holding the seconds).")
        }
        actType = "delayActs"
        actSubType = "getDelay"
        fields = [:]
        if (actionSpec.variable != null) {
            fields["uVar.@N"] = true
            fields["xVar.@N"] = actionSpec.variable
        } else {
            if (actionSpec.hours != null) fields["delayHour.@N"] = actionSpec.hours
            if (actionSpec.minutes != null) fields["delayMinute.@N"] = actionSpec.minutes
            if (actionSpec.seconds != null) fields["delaySecond.@N"] = actionSpec.seconds
        }
        if (actionSpec.cancelable != null) fields["cancelAct.@N"] = actionSpec.cancelable
        if (actionSpec.random != null) fields["randomAct.@N"] = actionSpec.random
    } else if (cap == "delayPerMode") {
        // The per-mode fields are keyed by mode NAME; only delayModes takes ids.
        actType = "delayActs"
        actSubType = "getDelayPerMode"
        if (!(actionSpec.perMode instanceof Map) || actionSpec.perMode.isEmpty()) {
            throw new IllegalArgumentException("delayPerMode requires perMode={modeIdOrName: {hours, minutes, seconds}, ...}")
        }
        def modeIds = _rmResolveModeIds(actionSpec.perMode.keySet())
        def modeNames = _rmResolveModeNames(actionSpec.perMode.keySet())
        fields = ["delayModes.@N": modeIds]
        modeNames.eachWithIndex { mname, i ->
            def cfg = actionSpec.perMode.find { _rmModeIdMatches(it.key, modeIds[i]) }?.value
            if (cfg instanceof Map) {
                if (cfg.hours != null)   fields["delayHour${mname}.@N"]   = cfg.hours
                if (cfg.minutes != null) fields["delayMinute${mname}.@N"] = cfg.minutes
                if (cfg.seconds != null) fields["delaySecond${mname}.@N"] = cfg.seconds
            }
        }
    } else if (cap == "repeat") {
        if (actionSpec.hours == null && actionSpec.minutes == null && actionSpec.seconds == null) {
            throw new IllegalArgumentException("repeat action requires an interval: 'hours', 'minutes' and/or 'seconds'; 'times' alone is not enough.")
        }
        actType = "repeatActs"
        actSubType = "getRepeat"
        fields = [:]
        if (actionSpec.hours != null) fields["repeatHour.@N"] = actionSpec.hours
        if (actionSpec.minutes != null) fields["repeatMinute.@N"] = actionSpec.minutes
        if (actionSpec.seconds != null) fields["repeatSecond.@N"] = actionSpec.seconds
        if (actionSpec.times != null) fields["repeatN.@N"] = actionSpec.times
        if (actionSpec.stoppable != null) fields["stopRepeat.@N"] = actionSpec.stoppable
    } else if (cap == "repeatWhile") {
        actType = "repeatActs"
        actSubType = "getWhile"
        fields = [:]
        if (actionSpec.hours != null) fields["repeatHour.@N"] = actionSpec.hours
        if (actionSpec.minutes != null) fields["repeatMinute.@N"] = actionSpec.minutes
        if (actionSpec.seconds != null) fields["repeatSecond.@N"] = actionSpec.seconds
        if (actionSpec.times != null) fields["repeatN.@N"] = actionSpec.times
        if (actionSpec.stoppable != null) fields["stopRepeat.@N"] = actionSpec.stoppable
        if (!(actionSpec.expression instanceof Map)) {
            throw new IllegalArgumentException("repeatWhile action requires expression={conditions:[...], operator?:..., operators?:[...]}")
        }
    } else if (cap == "waitEvents") {
        actType = "delayActs"
        actSubType = "getWaitEvents"
        fields = [:]
        if (!(actionSpec.events instanceof List) || (actionSpec.events as List).isEmpty()) {
            throw new IllegalArgumentException("waitEvents action requires events=[{capability, deviceIds, state}, ...] (non-empty)")
        }
    } else if (cap == "waitExpression") {
        actType = "delayActs"
        actSubType = "getWaitRule"
        fields = [:]
        if (!(actionSpec.expression instanceof Map)) {
            throw new IllegalArgumentException("waitExpression action requires expression={conditions:[...], operator?:..., operators?:[...]}")
        }
    } else if (cap == "scene") {
        actType = "sceneActs"
        actSubType = "getActivateScenePerMode"
        if (!(actionSpec.perMode instanceof Map) || actionSpec.perMode.isEmpty()) {
            throw new IllegalArgumentException("scene.activatePerMode requires perMode={modeIdOrName: [sceneIds], ...}")
        }
        def modeIds = _rmResolveModeIds(actionSpec.perMode.keySet())
        fields = ["sceneModes.@N": modeIds]
        modeIds.each { mid ->
            def val = actionSpec.perMode.find { _rmModeIdMatches(it.key, mid) }?.value
            if (!(val instanceof List) || val.isEmpty()) throw new IllegalArgumentException("scene.activatePerMode: perMode[${mid}] must be a non-empty list of scene ids.")
            fields["scene${mid}.@N".toString()] = val
        }
    } else if (cap == "returnValue") {
        // RM offers a variable source (useVar) for Integer, Decimal and Boolean only.
        actType = "modeActs"
        actSubType = "getReturnValue"
        def valueTypes = ["String", "Integer", "Decimal", "Boolean"]
        def vt = _rmCatalogValueCanonical([values: valueTypes], actionSpec.type)?.toString()
        if (!vt) throw new IllegalArgumentException("returnValue requires type: ${valueTypes.join(', ')}.")
        fields = ["valType.@N": vt]
        if (actionSpec.variable != null) {
            if (vt == "String") throw new IllegalArgumentException("returnValue: Rule Machine returns a String only as a literal value; a variable source is offered for Integer, Decimal and Boolean.")
            fields["useVar.@N"] = true
            fields["valVar.@N"] = actionSpec.variable
        } else {
            String text = actionSpec.value.toString().trim()
            def value
            if (vt == "Boolean") {
                if (!(text.toLowerCase() in ["true", "false"])) throw new IllegalArgumentException("returnValue: a Boolean value is true or false (got '${actionSpec.value}').")
                value = text.toLowerCase()
            } else if (vt == "Integer") {
                if (!text.isLong()) throw new IllegalArgumentException("returnValue: an Integer value must be a whole number (got '${actionSpec.value}').")
                value = text.toLong()
            } else if (vt == "Decimal") {
                if (!text.isBigDecimal()) throw new IllegalArgumentException("returnValue: a Decimal value must be a number (got '${actionSpec.value}').")
                value = text.toBigDecimal()
            } else value = actionSpec.value.toString()
            // RM renders the row from the value's truth, so a zero or empty literal never bakes.
            if (vt != "Boolean" && !value) throw new IllegalArgumentException("returnValue: Rule Machine shows no row for a zero or empty ${vt} value; return it from a variable instead${vt == 'String' ? ' (not offered for String)' : ''}.")
            fields[[String: "valString.@N", Integer: "valInt.@N", Decimal: "valDec.@N", Boolean: "valBool.@N"].get(vt)] = value
        }
    } else if (cap == "ifThen" || cap == "elseIf") {
        actType = "condActs"
        actSubType = (cap == "ifThen") ? "getIfThen" : "getElseIf"
        fields = [:]
        if (!(actionSpec.expression instanceof Map)) {
            throw new IllegalArgumentException("${cap} action requires expression={conditions:[...], operator?:..., operators?:[...]}")
        }
    } else {
        throw new IllegalStateException("addAction: the catalog marks '${cap}' custom but no mapping handles it")
    }
    if (actSubType in ["getIfThen", "getElseIf", "getWhile", "getWaitRule"]) _rmValidateActionExpressionShape(cap, actionSpec.expression as Map)
    _rmRequireDeviceCapability("${action ? cap + '.' + action : cap + ' action'}.deviceIds".toString(), actionSpec.deviceIds, actSubType?.toString())
    return [actType: actType, actSubType: actSubType, fields: fields, skipped: skipped]
    }
    Map mapped = _impl() as Map
    if (actionSpec.condition != null) _rmCheckSimpleConditionSpec(cap, mapped.actType?.toString(), mapped.actSubType?.toString(), actionSpec.condition)
    return mapped
}

// The condition capabilities RM leaves out of a new Simple Conditional Action's picker.
private List _rmSimpleCondExcluded() {
    ["Time of day", "Between two times", "Days of week", "Between two dates", "Mode", "Private Boolean", "HSM Status", "HSM Alert",
     "Keypad codes", "Lock codes", "Variable", "Custom Attribute", "Last Event Device", "On a Day", "Time Since Event"]
}

// Inside a Simple Conditional Action RM's type picker drops the conditional group and its sub-type
// pickers drop Repeat While and Wait for Expression: those rows cannot carry a condition.
private boolean _rmNoConditionRow(String actType, String subType) { actType == "condActs" || subType in ["getWhile", "getWaitRule"] }

// A condition makes the row a Simple Conditional Action.
private void _rmCheckSimpleConditionSpec(String cap, String actType, String subType, Object cond) {
    if (!(cond instanceof Map)) throw new IllegalArgumentException("${cap}.condition must be a condition spec, or {definedIndex} for a condition the rule already defines.".toString())
    if (_rmNoConditionRow(actType, subType)) {
        throw new IllegalArgumentException("${cap} cannot carry a condition: Rule Machine does not offer it inside a Simple Conditional Action. Use ifThen ... endIf instead.".toString())
    }
    Map c = cond as Map
    if (c.definedIndex != null) return
    if (!c.capability?.toString()?.trim()) throw new IllegalArgumentException("${cap}.condition needs capability (or definedIndex for a condition the rule already defines).".toString())
    // RM's Simple Conditional picker leaves these out: refused before the condition's own fields are asked for.
    String rawCondCap = c.capability.toString().trim()
    String condCap = ((_rmCondTables().condition as List).find { it.toString().equalsIgnoreCase(rawCondCap) } ?: rawCondCap).toString()
    if (condCap in _rmSimpleCondExcluded()) {
        throw new IllegalArgumentException("${cap}.condition: Rule Machine does not offer a ${condCap} condition in a Simple Conditional Action (it leaves out ${_rmSimpleCondExcluded().join(', ')}). Define the condition first and pass {definedIndex}, or use ifThen ... endIf. RM is not touched.".toString())
    }
    _rmCheckCondSpec(c, "condition", "${cap}.condition".toString())
    _rmValidateDeviceIdsExist("${cap}.condition.deviceIds".toString(), c.deviceIds ?: (c.deviceId != null ? [c.deviceId] : null))
    _rmRequireConditionDeviceCapability("${cap}.condition.deviceIds".toString(), c)
    if (c.compareToDevice instanceof Map) _rmValidateDeviceIdsExist("${cap}.condition.compareToDevice.deviceId".toString(), [(c.compareToDevice as Map).deviceId])
    _rmValidateDeviceIdsExist("${cap}.condition.deviceIds", c.deviceIds ?: (c.deviceId != null ? [c.deviceId] : null))
}

// An expression-bearing action's argument checks, run with the mapping so a malformed expression is
// refused before its opener row is written (the walk would otherwise open the row, then roll it back).
private void _rmValidateActionExpressionShape(String cap, Map exprSpec) {
    def conditions = exprSpec?.conditions
    if (!(conditions instanceof List) || conditions.isEmpty()) {
        throw new IllegalArgumentException("${cap} action's expression.conditions must be a non-empty List")
    }
    if (exprSpec.operators instanceof List && (exprSpec.operators as List).size() != conditions.size() - 1) {
        throw new IllegalArgumentException("${cap}.expression.operators must have length conditions.size()-1")
    }
    if (conditions.size() > 1 && !exprSpec.operator?.toString() && !(exprSpec.operators instanceof List)) {
        throw new IllegalArgumentException("${cap}.expression with ${conditions.size()} conditions requires operator (AND/OR/XOR) or operators list")
    }
    conditions.eachWithIndex { c, i ->
        if (!(c instanceof Map)) throw new IllegalArgumentException("${cap}.expression.conditions[${i}] is not a Map")
        // A nested subExpression is refused by _rmPrevalidateActionSpec with its own steer.
        if ((c as Map).subExpression == null && !(c as Map).capability?.toString()?.trim()) {
            throw new IllegalArgumentException("${cap}.expression.conditions[${i}].capability is required")
        }
    }
}

// The gateway stamps __reqT0 on the TOP-LEVEL argument map only, and every _rmAddAction caller
// hands it a child action-spec map; this carries the request clock down on that map (a copy --
// the caller's spec is not mutated) so the add's own budget checks see the real clock. The
// signature of _rmAddAction stays as it is: the specs stub it by arity.
private Map _rmWithClock(Map spec, Long reqT0) {
    if (reqT0 == null || spec == null) return spec
    // Only a positive Number is a clock. A client-supplied nested spec can carry anything under
    // this internal key, and "x" or 0 would either blow up the cast downstream or read as a
    // budget already spent -- so anything else is ignored and replaced by the real clock.
    if (spec.__reqT0 instanceof Number && ((Number) spec.__reqT0).longValue() > 0) return spec
    return spec + [__reqT0: reqT0]
}

// Extracted from _rmAddAction to keep that method under the JVM's 64KB per-method bytecode
// limit: every check here refuses a bad spec BEFORE any wizard write, so RM is genuinely
// untouched on a throw.
private void _rmPrevalidateActionSpec(Map actionSpec, String cap, Set validRuleIds) {
    // RM stores an unknown device id as {id: null} and renders the action broken.
    _rmValidateDeviceIdsExist("addAction.deviceIds", actionSpec.deviceIds)
    if (cap in ["variable", "setVariable", "setLocalVariable"]) _rmSetVariableValueNumOp(actionSpec)
    // Locals are re-checked in the mapping, where the rule's own types are readable.
    if ((cap == "variable" || cap == "setVariable") && actionSpec.sourceVariable != null && actionSpec.variable != null) {
        def targetType = null
        try {
            def meta = getAllGlobalVars()?.get(actionSpec.variable.toString())
            targetType = (meta instanceof Map) ? meta?.type?.toString()?.toLowerCase() : null
        } catch (Exception e) {
            mcpLog("debug", "rm-native", "setVariable pre-check: target type unreadable (${e.class.simpleName}: ${e.message}), deferred to the builder")
        }
        if (targetType in ["boolean", "datetime"]) {
            throw new IllegalArgumentException("setVariable: sourceVariable copy into a ${targetType} target ('${actionSpec.variable}') is not supported yet -- its copy picker has not been mapped yet. Copy into a Number, Decimal or String variable, or build this action in the RM UI.")
        }
    }
    if (_rmSpecTargetsRule(actionSpec)) {
        _rmValidateRuleTargetExists(cap, actionSpec.ruleIds ?: actionSpec.deviceIds, validRuleIds)
    }
    if (actionSpec.events instanceof List) {
        (actionSpec.events as List).eachWithIndex { ev, evIdx ->
            if (ev instanceof Map) {
                _rmValidateDeviceIdsExist("addAction.events[${evIdx}].deviceIds", (ev as Map).deviceIds)
            }
        }
    }
    if (actionSpec.expression instanceof Map) {
        def exprConds = (actionSpec.expression as Map).conditions
        if (exprConds instanceof List) {
            // The doActPage condition walker is flat-only.
            exprConds.eachWithIndex { entry, idx ->
                if (entry instanceof Map && (entry as Map).subExpression != null) {
                    throw new IllegalArgumentException("addAction.expression.conditions[${idx}]: nested subExpression is not yet supported on this action type. Either flatten the condition list, or move the nested expression into a Required Expression (addRequiredExpression supports nesting).")
                }
            }
            // Singular deviceId is normalized here so the id check below sees it.
            exprConds.each { entry ->
                if (!(entry instanceof Map)) return
                def em = entry as Map
                if (em.deviceIds == null && em.deviceId != null) {
                    em.deviceIds = [em.deviceId]
                }
            }
            exprConds.eachWithIndex { c, cIdx ->
                if (c instanceof Map) {
                    _rmValidateDeviceIdsExist("addAction.expression.conditions[${cIdx}].deviceIds", (c as Map).deviceIds)
                    def cm = c as Map
                    if (cm.compareToDevice instanceof Map && (cm.compareToDevice as Map).deviceId != null) {
                        _rmValidateDeviceIdsExist("addAction.expression.conditions[${cIdx}].compareToDevice.deviceId", [(cm.compareToDevice as Map).deviceId])
                    }
                }
            }
        }
    }
}

// The argument checks _rmAddTrigger runs before it opens the trigger editor, applied to a whole
// list so a create can refuse a bad trigger before the rule exists. Device ids are read-only lookups.
private void _rmPrevalidateTriggerSpecList(List specs, String label) {
    specs.eachWithIndex { spec, i ->
        if (!(spec instanceof Map)) {
            throw new IllegalArgumentException("${label}[${i}] must be a trigger spec object, got '${spec}'. RM is not touched.")
        }
        def sm = spec as Map
        if (_rmIsDiscoverSpec(sm)) return
        try {
            _rmValidateRoundZeroTriggerSpec(sm)
            if (!sm.capability?.toString()?.trim()) {
                throw new IllegalArgumentException("addTrigger.capability is required. Pass {discover: true} to get the full structured schema. RM is not touched.")
            }
            _rmValidateDeviceIdsExist("addTrigger.deviceIds", sm.deviceIds)
            _rmRequireConditionDeviceCapability("addTrigger.deviceIds", sm)
            if (sm.condition instanceof Map) {
                def cm = sm.condition as Map
                _rmValidateDeviceIdsExist("addTrigger.condition.deviceIds", cm.deviceIds ?: (cm.deviceId != null ? [cm.deviceId] : null))
                _rmRequireConditionDeviceCapability("addTrigger.condition.deviceIds", cm)
                if (cm.compareToDevice instanceof Map) _rmValidateDeviceIdsExist("addTrigger.condition.compareToDevice.deviceId", [(cm.compareToDevice as Map).deviceId])
            }
            if (sm.compareToDevice instanceof Map) {
                _rmValidateDeviceIdsExist("addTrigger.compareToDevice.deviceId", [(sm.compareToDevice as Map).deviceId])
            }
            _rmValidateRoundZeroPeriodicSpec(sm)
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("${label}[${i}]: ${e.message}".toString(), e)
        }
    }
}

// The argument checks a single add runs, applied to a whole list, so a list refused partway is
// refused before any of it is written. Everything here only reads (appId null: no rule yet).
// Structural balance is checked separately; checks that need the rule's own state (one waitEvents
// action per rule, a fileDelete/fileAppend file list, the disabled-app gate) stay in the add.
private void _rmPrevalidateActionSpecList(List specs, String label, Set validRuleIds, Integer appId = null) {
    specs.eachWithIndex { spec, i ->
        if (!(spec instanceof Map)) {
            throw new IllegalArgumentException("${label}[${i}] must be an action spec object, got '${spec}'. RM is not touched.")
        }
        def sm = spec as Map
        if (_rmIsDiscoverSpec(sm)) return
        def cap = _rmCanonicalActionCapability(sm)
        if (!cap) throw new IllegalArgumentException("${label}[${i}].capability is required. RM is not touched.")
        try {
            _rmValidateRoundZeroActionSpec(sm)
            _rmPrevalidateActionSpec(sm, cap, validRuleIds)
            _rmMapActionSpec(appId, new LinkedHashMap(sm), cap, sm.action?.toString()?.trim())
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(_rmNotTouched("${label}[${i}]: ${e.message}".toString()), e)
        }
    }
}

// An argument refusal raised before the rule's first write, marked with the not-touched sentinel
// the error envelope keys its "nothing to restore" hint on.
private String _rmNotTouched(String msg) {
    return (msg ?: "").contains("RM is not touched") ? msg : "${msg} RM is not touched.".toString()
}

// The numOp a setVariable/setLocalVariable 'value' action writes. The other source modes
// select their own source picker, so a caller numOp there (or with no value) could only be
// silently dropped -- refuse it instead.
private String _rmSetVariableValueNumOp(Map actionSpec) {
    if (actionSpec.numOp == null) return "number"
    String capLabel = (actionSpec.capability?.toString()?.trim() == "setLocalVariable") ? "setLocalVariable" : "setVariable"
    if (actionSpec.value == null) {
        throw new IllegalArgumentException("${capLabel}: numOp goes only with value ('number' sets, 'add number' adds); sourceVariable, fromDevice and math set their own. RM is not touched.")
    }
    String requested = actionSpec.numOp.toString().trim().toLowerCase()
    if (!(requested in ["number", "add number"])) {
        throw new IllegalArgumentException("${capLabel}: numOp '${actionSpec.numOp}' does not go with value; use 'number' or 'add number', or sourceVariable, fromDevice or math instead. RM is not touched.")
    }
    return requested
}

// Extracted from _rmAddAction for the same bytecode budget. Returns the action index RM
// actually allocated (its high-water mark), falling back to the caller's computed idx.
private Integer _rmResolveAllocatedActionIdx(Integer appId, Integer idx, Long reqT0) {
    // RM allocates from its high-water mark (state.actNdx), so the opened slot is read back.
    def doActPageCfg = _rmFetchConfigJson(appId, "doActPage")
    def doActSchema = _rmCollectInputSchema(doActPageCfg?.configPage)
    def actTypeField = doActSchema?.keySet()?.find { it.toString() ==~ /^actType\.\d+$/ }
    if (!actTypeField && doActPageCfg?.configPage?.error == null && !_timeBudgetExceeded(reqT0)) {
        // Under load RM sometimes renders doActPage empty right after the click (no error
        // page); one re-read after a pause, and a failed re-read keeps the original read.
        mcpLog("warn", "rm-native", "addAction: doActPage rendered with no actType field for app ${appId} after the Create New Action click; re-reading it once")
        pauseExecution(_rmEmptyRenderPauseMs())
        try {
            doActPageCfg = _rmFetchConfigJson(appId, "doActPage")
            doActSchema = _rmCollectInputSchema(doActPageCfg?.configPage)
            actTypeField = doActSchema?.keySet()?.find { it.toString() ==~ /^actType\.\d+$/ }
        } catch (Exception rereadExc) {
            mcpLog("debug", "rm-native", "addAction: doActPage re-read failed for app ${appId} (${rereadExc.message}); keeping the original read")
        }
    }
    if (actTypeField) {
        def m = (actTypeField.toString() =~ /^actType\.(\d+)$/)
        if (m.matches()) {
            def actualIdx = m[0][1] as Integer
            if (actualIdx != idx) {
                mcpLog("info", "rm-native", "addAction: RM allocated idx ${actualIdx} (computed ${idx} from existing settings) -- using ${actualIdx}")
                idx = actualIdx
            }
        }
    }
    return idx
}

// One sentence that matches what _rmAddAction's result says happened.
private String _rmAddActionNote(Map r) {
    if (r?.success == false) {
        return r.rolledBack == true ? "Action not added: the half-built row was removed; see repairHints." :
            "Action not added and its row could not be removed; see repairHints."
    }
    if (r?.partial == true) return "Action ${r.actionIndex} added and baked, but some fields did not land; see settingsSkipped / repairHints."
    return "Action ${r?.actionIndex} added and baked (no trailing updateRule needed; the action editor finalizes it)."
}

// One orchestrated add through RM's new-action editor (doActPage).
Map _rmAddAction(Integer appId, Map actionSpec, boolean intraBatch = false, Set validRuleIds = null) {
    if (!(actionSpec instanceof Map)) throw new IllegalArgumentException("addAction requires a Map spec")
    // __reqT0 rides a client-supplied map: only a positive Number is a clock.
    Long reqT0 = (actionSpec.__reqT0 instanceof Number && ((Number) actionSpec.__reqT0).longValue() > 0) ?
            ((Number) actionSpec.__reqT0).longValue() : null
    if (_rmIsDiscoverSpec(actionSpec)) {
        return _rmActionDiscover(actionSpec.discover)
    }
    def cap = _rmCanonicalActionCapability(actionSpec)
    def action = actionSpec.action?.toString()?.trim()
    if (!cap) throw new IllegalArgumentException("addAction.capability is required (e.g. 'switch'). Common values: switch, dimmer, color, log, notification, mode, setVariable, runCommand, delay, repeat, ifThen. Pass {discover: true} to get the full structured schema.")

    _rmValidateRoundZeroActionSpec(actionSpec)

    // A disabled app renders an empty page; inside a batch the gate already ran once.
    if (!intraBatch) _rmRejectDisabledAppEdit(appId, "addAction")

    // Closers and branch keywords are refused when they would be orphaned; openers added alone
    // are a normal build state. Only NEW issues count, so a rule already broken is not blocked.
    def preflightCap = _rmStructuralPairForCapability(cap)
    def closerOrBranchKeywords = ["endIf", "stopRepeat", "elseIf", "else"]
    if (cap in closerOrBranchKeywords && preflightCap != null) {
        def settingsByName = _rmFetchSettingsByName(appId)
        def currentSeq = _rmStructuralSequenceFromSettings(settingsByName, ([] as Set), _rmOrderedActionIndices(appId))
        def projectedSeq = currentSeq + [[idx: -1, actType: preflightCap[0], actSubType: preflightCap[1]]]
        def currentIssues = _rmStructuralIssuesFromSequence(currentSeq)
        def projectedIssues = _rmStructuralIssuesFromSequence(projectedSeq)
        def newIssues = projectedIssues - currentIssues
        if (newIssues) {
            def hint = (cap == "endIf") ? "Add an addAction(capability='ifThen', ...) first (and its body), then this closer." :
                       (cap == "stopRepeat") ? "Add an addAction(capability='repeat', ...) first (and its body), then this closer." :
                       "Open an IF block with addAction(capability='ifThen', ...) before adding ${cap}."
            throw new IllegalArgumentException("addAction(${cap}) blocked: would introduce a new structural-balance issue (${newIssues.first()}). ${hint} RM is not touched.")
        }
    }

    _rmPrevalidateActionSpec(actionSpec, cap, validRuleIds)

    // From settings, not the compiled list: an un-baked row still occupies its index.
    def existing = _rmActionIndicesFromSettings(_rmFetchStatusJson(appId))
    def idx = (existing ? existing.max() + 1 : 1)

    // RM keeps wait-event rows (tCapab-N, tDev-N, tstate-N) per rule, not per action, so a
    // second Wait for Events action overwrites the first one's events.
    if (cap == "waitEvents" && existing) {
        def status = _rmFetchStatusJson(appId)
        def existingWaitIdx = (status?.appSettings ?: []).findResult { s ->
            def n = s?.name?.toString()
            if (!n) return null
            def m = (n =~ /^actSubType\.(\d+)$/)
            if (!m.matches()) return null
            if (s?.value?.toString() != "getWaitEvents") return null
            return (m[0][1] as Integer)
        }
        if (existingWaitIdx != null) {
            throw new IllegalArgumentException(
                "Rule Machine supports one Wait for Events action per rule (an existing one is at index ${existingWaitIdx}): " +
                "a second would overwrite the first one's events. Put every event into that one action's 'events' list, " +
                "or split the rule in two and chain them with Run Actions."
            )
        }
    }

    // Inside a batch an earlier item may already be written, so only a standalone add claims
    // "not touched".
    def mapped
    try { mapped = _rmMapActionSpec(appId, actionSpec, cap, action) }
    catch (IllegalArgumentException argExc) {
        if (intraBatch) throw argExc
        throw new IllegalArgumentException(_rmNotTouched(argExc.message), argExc)
    }
    def actType = mapped.actType
    def actSubType = mapped.actSubType
    def fields = mapped.get("fields")
    def deviceIds = actionSpec.deviceIds
    def applied = []
    def skipped = mapped.skipped

    // First writes: a pending predCapabs clear from a preceding addRequiredExpression (else the
    // action is wrapped in IF(**Broken Condition**)), then state.actNdx initialization.
    _rmRunPendingPredCapabsClear(appId)
    _rmInitSelectActionsPage(appId)

    // The UI's button handler concatenates stateAttribute 'doAct' + button 'N'; 'doAct' alone
    // leaves state.doActN null and doActPage NPEs.
    _rmClickAppButton(appId, "N", "doActN", "selectActions")

    idx = _rmResolveAllocatedActionIdx(appId, idx, reqT0)

    // From here to the rawSettings block the editor is open on this row: any refusal must
    // cancel it, or RM reopens the row with its stale fields on the next add.
    // The slot is known as soon as the condition is built, so a throw after it still names it.
    def simpleCond = [null]
    try {
        if (actionSpec.condition instanceof Map) _rmWriteSimpleConditionalPreamble(appId, idx, cap, actionSpec.condition as Map, applied, skipped, simpleCond)
        _rmWriteSettingOnPage(appId, "doActPage", "actType.${idx}", actType, applied, null, skipped)
        _rmWriteSettingOnPage(appId, "doActPage", "actSubType.${idx}", actSubType, applied, null, skipped)
        _rmRequireOfferedActionSubType(cap, actSubType, idx, skipped)

        // Expression actions: reset a stale cond accumulator before cond=a. Not in the schema
        // for other actions, so silently skipped there.
        def condContextClear = []
        _rmWriteSettingOnPage(appId, "doActPage", "cond", [], applied, null, condContextClear)
        if (!condContextClear.isEmpty()) {
            mcpLog("debug", "rm-native", "_rmAddAction: cond not in doActPage schema after actType/actSubType for app ${appId} action ${idx} (${condContextClear[0]?.reason}) -- skipped (non-expression action)")
        }

        if (cap == "fileAppend" || cap == "fileDelete") {
            _rmRequireExistingHubFile(appId, idx, cap == "fileAppend" ? "localFile" : "deleteFile", actionSpec.fileName, cap.toString())
        }
        fields.each { rawKey, value ->
            if (value != null) {
                def fieldName = rawKey.toString().replace("@N", idx.toString())
                _rmWriteSettingOnPage(appId, "doActPage", fieldName, value, applied, null, skipped)
            }
        }
        _rmRequireOfferedCatalogChoice(appId, cap, action, idx, fields as Map, skipped)
        if (cap == "runCommand") _rmWriteRunCommandFields(appId, idx, actionSpec, applied, skipped)
        _rmWriteWaitEventRows(appId, idx, actionSpec, actSubType, applied, skipped)
    } catch (Exception preExprExc) {
        _rmCancelRefusedActionEditorThenDropCondition(appId, idx, preExprExc, simpleCond[0] as Integer)
    }

    // Expression actions walk each condition (cond=a -> rCapab_<x> -> rDev_<x> -> state_<x> -> hasAll),
    // with oper between, then hasRule. A throw rolls the opener back; actCondWizardOpen says whether a
    // condition slot still needs cancelCapab.
    def expressionSubtypes = ["getIfThen", "getElseIf", "getWhile", "getWaitRule"]
    def actCondWizardOpen = false
    try {
    if (expressionSubtypes.contains(actSubType)) {
        // getWaitRule's timeout fields are in the schema only before hasRule, so they are
        // written here rather than by the delay-modifier pass after the expression.
        if (actSubType == "getWaitRule") {
            if (actionSpec.useDuration != null) {
                _rmWriteSettingOnPage(appId, "doActPage", "durChoice.${idx}", actionSpec.useDuration, applied, "bool", skipped)
            }
            if (actionSpec.delay instanceof Map) {
                def d = actionSpec.delay as Map
                if (d.variable != null) {
                    _rmWriteSettingOnPage(appId, "doActPage", "delayAct.${idx}", "variable", applied, null, skipped)
                    _rmWriteSettingOnPage(appId, "doActPage", "xVarD.${idx}", d.variable, applied, null, skipped)
                } else if (d.hours != null || d.minutes != null || d.seconds != null) {
                    _rmWriteSettingOnPage(appId, "doActPage", "delayAct.${idx}", "hrs:min:sec", applied, null, skipped)
                    if (d.hours != null)   _rmWriteSettingOnPage(appId, "doActPage", "delayHor.${idx}", d.hours, applied, null, skipped)
                    if (d.minutes != null) _rmWriteSettingOnPage(appId, "doActPage", "delayMin.${idx}", d.minutes, applied, null, skipped)
                    if (d.seconds != null) _rmWriteSettingOnPage(appId, "doActPage", "delaySec.${idx}", d.seconds, applied, null, skipped)
                }
                actionSpec.__delayHandledForWaitRule = true
            }
        }
        def exprSpec = actionSpec.expression as Map
        def conditions = exprSpec.conditions
        def operator = exprSpec.operator?.toString()?.toUpperCase()
        def opsList = (exprSpec.operators instanceof List) ? (exprSpec.operators as List).collect { it?.toString()?.toUpperCase() } : null
        conditions.eachWithIndex { c, i ->
            if (!(c instanceof Map)) return
            _rmRequireConditionDeviceCapability("${cap}.expression.conditions[${i}].deviceIds".toString(), c as Map)
            if ((c as Map).compareToDevice instanceof Map) _rmValidateDeviceIdsExist("${cap}.expression.conditions[${i}].compareToDevice.deviceId".toString(), [((c as Map).compareToDevice as Map).deviceId])
            def ids = (c as Map).deviceIds
            if (!(ids instanceof List)) return
            ids.each { id ->
                def idStr = id?.toString()
                if (!idStr) return
                def exists = false
                try {
                    def resp = hubInternalGet("/device/fullJson/${idStr}")
                    exists = (resp != null && resp.toString().length() > 0)
                } catch (Exception ignored) { exists = false }
                if (!exists) {
                    throw new IllegalArgumentException("${cap}.expression.conditions[${i}].deviceIds contains '${idStr}' which does not exist on the hub.")
                }
            }
        }
        // Same signature as the STPage writer; hrefParams is unused on doActPage.
        def writeAct = { Map params, String fieldKey, Object fieldValue, String label = null ->
            _rmWriteSettingOnPage(appId, "doActPage", fieldKey, fieldValue, applied, null, skipped)
        }
        // The walker cancels the open condition slot before it throws; actCancelledByWalker keeps
        // the outer catch from issuing a second cancelCapab (which fails and would read as stuck).
        def actWizardCleanupFailed = false
        def actWizardCleanupErr = null
        def actCancelledByWalker = false
        def currentActCondIdx = -1
        def cancelInFlightActCond = {
            actCancelledByWalker = true
            try { _rmClickAppButton(appId, "cancelCapab", null, "doActPage") }
            catch (Exception cancelExc) {
                actWizardCleanupFailed = true
                actWizardCleanupErr = cancelExc.message
                mcpLog("warn", "rm-native", "cancelCapab cleanup failed for app ${appId} on doActPage at conditions[${currentActCondIdx}]: ${cancelExc.message} -- wizard may stay open and confuse subsequent edits; result will carry wizardStuck=true")
            }
            // Cleared even on a failed click: a retry is the double fire; the failure is
            // surfaced as wizardStuck instead.
            actCondWizardOpen = false
        }
        // The condition-wizard fields are the same on doActPage and STPage; the slot number RM
        // assigns is read from the schema after cond=a.
        conditions.eachWithIndex { condRaw, i ->
            currentActCondIdx = i
            if (!(condRaw instanceof Map)) {
                throw new IllegalArgumentException("${cap}.expression.conditions[${i}] is not a Map")
            }
            def cond = condRaw as Map
            if (cond.subExpression != null) {
                throw new IllegalArgumentException("${cap}.expression.conditions[${i}]: nested subExpression on this row is not yet supported. _rmAddRequiredExpression (addRequiredExpression) supports nested expressions; on ${cap} rows, either flatten the condition list, or capture the sub-expression as a Required Expression instead.")
            }
            def ccap = cond.capability?.toString()?.trim()
            if (!ccap) throw new IllegalArgumentException("${cap}.expression.conditions[${i}].capability is required")
            _rmCheckCondSpec(cond, "condition", "${cap}.expression.conditions[${i}]".toString())
            _rmWriteSettingOnPage(appId, "doActPage", "cond", "a", applied, null, skipped)
            actCondWizardOpen = true
            def cfg = _rmFetchConfigJson(appId, "doActPage")
            def cInputs = (cfg?.configPage?.sections ?: []).collectMany { it?.input ?: [] }
            def rCapabInput = cInputs.find { it?.name?.toString()?.startsWith("rCapab_") }
            if (!rCapabInput) {
                throw new IllegalStateException("${cap}: rCapab_<N> not in doActPage schema after writing cond=a; got ${cInputs.collect{it?.name}.findAll{it}.join(', ')}")
            }
            def m = rCapabInput.name.toString() =~ /rCapab_(\d+)/
            def cIdx = m ? ((m[0] as List)[1] as Integer) : null
            if (cIdx == null) throw new IllegalStateException("${cap}: couldn't parse condition index from '${rCapabInput.name}'")
            def capOptions = (rCapabInput.options ?: []) as List
            def capCanonical = capOptions.find { it.toString().equalsIgnoreCase(ccap) }
            if (!capCanonical) {
                cancelInFlightActCond()
                def suggestion = _rmSuggestTriggerCapability(ccap, capOptions)
                def didYouMean = suggestion ? " Did you mean '${suggestion}'?" : ""
                throw new IllegalArgumentException("${cap}.expression.conditions[${i}].capability '${ccap}' is not in Rule Machine's condition list on this hub (RM lists a capability only when the hub has a device or app for it).${didYouMean} RM offers: ${capOptions.collect { it.toString() }.sort().join(', ')}")
            }
            actCancelledByWalker = false
            try {
                _rmWalkConditionReveal(appId, [
                    page: "doActPage",
                    writeST: writeAct,
                    cancelInFlightCondition: cancelInFlightActCond,
                    condIdx: i,
                    cap: cap,
                    capCanonical: capCanonical,
                    hrefParams: [unUsed: null],
                    applied: applied,
                    skipped: skipped
                ], cond, cIdx)
            } catch (Exception perCondExc) {
                // A fetch failure inside the walker propagates without its cancel.
                if (!actCancelledByWalker) {
                    cancelInFlightActCond()
                }
                if (actWizardCleanupFailed) {
                    throw _rmFail(new IllegalStateException("${perCondExc.message} [wizardStuck -- cancelCapab cleanup also failed: ${actWizardCleanupErr}]", perCondExc), _rmOutcomeFlags(perCondExc) + [wizardStuck: true])
                }
                throw perCondExc
            }
            actCondWizardOpen = false
            if (i < conditions.size() - 1) {
                def gapOp = opsList ? opsList[i] : operator
                if (gapOp) {
                    _rmWriteSettingOnPage(appId, "doActPage", "oper", gapOp, applied, null, skipped)
                }
            }
        }
        // hasRule binds the walked conditions to this action; skipping it on a single-condition
        // expression makes RM render the conditions in every IF/ELSE-IF branch. The click is a
        // no-op when the button is not yet rendered.
        try {
            _rmClickAppButton(appId, "hasRule", null, "doActPage")
        } catch (Exception hasRuleExc) {
            mcpLog("warn", "rm-native", "_rmAddAction: hasRule click failed for app ${appId} action ${idx} (${hasRuleExc.message ?: hasRuleExc})")
            skipped << [key: "hasRule", reason: "hasrule_click_failed", value: true,
                        hint: "RM's hasRule click failed, so the expression's conditions may render in every IF/ELSE-IF branch; check action ${idx} with hub_get_app_config(appId=${appId}).".toString()]
        }
    }
    } catch (Exception exprExc) {
        _rmCancelRefusedActionEditor(appId, idx, exprExc, actCondWizardOpen,
            "orphan action ${idx} (the expression block opener) could not be automatically rolled back and may persist".toString())
    }

    // The editor is still open on this row; _rmCancelRefusedActionEditor never returns.
    try {
        _rmWriteActionDelayModifier(appId, idx, actionSpec, applied, skipped)
        _rmWriteRunCommandParams(appId, idx, actionSpec, applied, skipped)
        _rmWriteSetVariableSourceModes(appId, idx, actionSpec, applied, skipped)
        if (actionSpec.rawSettings instanceof Map) {
            actionSpec.rawSettings.each { k, v ->
                if (v != null) {
                    def fieldName = k.toString().replace("@N", idx.toString())
                    _rmWriteSettingOnPage(appId, "doActPage", fieldName, v, applied, null, skipped)
                }
            }
        }
    } catch (Exception postOpenExc) {
        _rmCancelRefusedActionEditorThenDropCondition(appId, idx, postOpenExc, simpleCond[0] as Integer)
    }

    // RM bakes the action on the navigation off doActPage that follows the actionDone click;
    // no updateRule is needed.
    _rmClickAppButton(appId, "actionDone", null, "doActPage")
    _rmNavigateToPage(appId, "doActPage", "selectActions", 0, "name", null, null, null, false, true)

    def finalConfig
    def verificationFetchFailed = false
    try { finalConfig = _rmFetchConfigJson(appId, "selectActions") }
    catch (Exception verifyExc) {
        finalConfig = null
        verificationFetchFailed = true
        mcpLog("warn", "rm-native", "_rmAddAction: post-commit selectActions fetch failed for app ${appId} (${verifyExc.message}) -- caller cannot verify the action baked, will mark response as verificationFetchFailed=true")
    }
    def err = finalConfig?.configPage?.error

    def health = _rmCheckRuleHealth(appId)
    if (intraBatch && health instanceof Map && (health.structuralIssues as List)) {
        // Inside a batch an open block is unbalanced until its closer bakes later in the same
        // call, so only the structural signal is dropped from this intermediate snapshot; the
        // final health check of the whole edit still reports a real imbalance. ok is recomputed with
        // _rmCheckRuleHealth's own predicate.
        def filteredIssues = ((health.issues as List) ?: []).findAll {
            !(it?.toString()?.startsWith("structural imbalance in action block nesting"))
        }
        health = health + [structuralIssues: [], issues: filteredIssues,
                           ok: filteredIssues.isEmpty() && health.broken != true && ((health.validationErrors as List) ?: []).isEmpty()]
    }

    // RM accepts invalid field writes without error and then does not bake the row: mainPage
    // keeps its "Define Actions" placeholder.
    def actionNotBaked = false
    try {
        def mainCfg = _rmFetchConfigJson(appId, "mainPage")
        def mainParagraphs = (mainCfg?.configPage?.sections ?: []).collectMany { sect ->
            def fromBody = (sect?.body ?: [])
                .findAll { b -> b instanceof Map && (b.element == "paragraph" || b.element == "href") }
                .collect { it.description?.toString() ?: "" }
            def fromParagraphs = (sect?.paragraphs ?: []).collect { it?.toString() ?: "" }
            fromBody + fromParagraphs
        }
        def joinedParagraphs = mainParagraphs.join("\n")
        actionNotBaked = joinedParagraphs.contains("Define Actions")
        // RM appends a baked row to state.actionList. A row whose label renders empty (an app with
        // no data for it, a choice the picker lacks) is left out, and the next add reopens its index.
        if (!actionNotBaked && err == null) {
            def appState = _rmFetchStatusJson(appId)?.appState
            def listed = (appState instanceof List) ? (appState as List).find { it instanceof Map && it.name?.toString() == "actionList" }?.value : null
            List baked = (listed instanceof List) ? _rmCoerceActionIndices(listed as List) : null
            if (baked == null) _memoNoteSkippedCheck("action baked (the rule's state carries no actionList)")
            else if (!baked.contains(idx)) actionNotBaked = true
        }
    } catch (Exception verifyExc) {
        verificationFetchFailed = true
        mcpLog("warn", "rm-native", "_rmAddAction: post-commit mainPage paragraph fetch failed for app ${appId} (${verifyExc.message}) -- action-baked check skipped")
    }

    // A device picker written last reveals no further schema and is tagged silent_rejection even
    // when its ids committed; re-tag those (value-echo-gated) before the partial computation.
    // Informational sentinels never flip partial; brokenMarkers (a prior broken row) do.
    _rmReclassifyDeviceListSkips(appId, skipped)
    def informationalReasons = _rmInformationalSkippedReasons()
    def genuineSkipped = (skipped ?: []).findAll {
        !(it instanceof Map) || it.reason == null || !(it.reason in informationalReasons)
    }
    def partial = !genuineSkipped.isEmpty() || actionNotBaked ||
                  ((health?.brokenMarkers as List)?.size() > 0)
    def hubRenderError = err != null || (genuineSkipped.any { it?.available != null && (it.available as List).isEmpty() } as Boolean) || actionNotBaked
    def repairHints = []
    // A row RM's editor errored on or did not bake is the half-built row that crashes the rule's
    // action editor until it is removed: remove it and report the add as failed.
    def rolledBack = false
    if (actionNotBaked || err != null) {
        rolledBack = _rmRollbackInFlightAction(appId, idx, false)
        // A row that could not be removed keeps its condition bound to it; only a removed row's is dropped.
        if (rolledBack && simpleCond[0] != null && !_rmDropSimpleCondition(appId, simpleCond[0] as Integer)) {
            repairHints << _rmSimpleConditionLeftNote(simpleCond[0] as Integer).trim()
        }
        if (rolledBack) {
            try { health = _rmCheckRuleHealth(appId) } catch (Exception healthExc) {
                mcpLog("warn", "rm-native", "_rmAddAction: post-rollback health re-read failed for app ${appId} (${healthExc.message})")
                health = (health instanceof Map ? (health as Map) : [ok: false, unreadable: true, checkErrors: [healthExc.message ?: healthExc.toString()]]) +
                    [stale: true, note: "read before the rollback; the re-read after it failed"]
            }
        }
        String why = err != null ? "RM's action editor reported an error (${err})".toString() : "RM did not bake the action (it is not in the rule's action list: RM showed nothing for these settings)"
        def lost = genuineSkipped.findAll { it instanceof Map && it.key != null }*.key.join(', ')
        if (lost) why += "; fields that did not land: ${lost}".toString()
        repairHints << (rolledBack
            ? "Action ${idx} was not added: ${why}. The row was removed, so nothing is left to repair. Check the spec (required fields for the capability/action pair, deviceIds, value ranges) via hub_set_rule(operation:'discover', args:{kind:'action', capability:'${cap}'}) and retry."
            : "Action ${idx} was not added: ${why}, and its row could not be removed. Remove it with hub_set_rule(removeAction:{index:${idx}}, confirm:true), verify with hub_get_rule_health(appId=${appId}), then retry.").toString()
    }
    if (partial && !(actionNotBaked || err != null)) {
        // A force-written comparator is written but unverified, not lost; it gets its own hint.
        def forceWrittenKeys = genuineSkipped.findAll { it instanceof Map && it.reason == "comparator_force_written_unverified" }*.key.findAll { it != null }
        def specificHintReasons = ["comparator_force_written_unverified", "comparator_not_representable_for_enum_attribute", "comparator_not_offered"]
        def lostSkipped = genuineSkipped.findAll { !(it instanceof Map && it.reason in specificHintReasons) }
        if (!lostSkipped.isEmpty()) {
            def skippedKeys = lostSkipped*.key.findAll { it != null }.join(', ')
            def settingWord = lostSkipped.size() == 1 ? "setting" : "settings"
            repairHints << "Some ${settingWord} didn't land: ${skippedKeys}. Check the action's paragraph via hub_get_app_config(appId): if it renders the value, the field was write-only during construction and the partial flag is cosmetic. Otherwise hub_set_rule(walkStep={page:'doActPage', operation:'introspect'}) shows the live schema (each skipped item's 'available' list is what was in it at the time) for writing the missing fields one at a time."
            if (hubRenderError) {
                repairHints << "A skipped field had an empty available list, so doActPage may have rendered without its inputs (a hub-side issue). If the action renders wrong, hub_set_rule(removeAction:{index:${idx}}, confirm:true) clears it; then retry with different deviceIds or a different action shape."
            }
            repairHints << "If retries still fail, hub_set_rule(removeAction:{index:${idx}}, confirm:true) to clean up, then call addAction again with corrections."
        }
        if (cap == "hsm" && genuineSkipped.any { it instanceof Map && it.key?.toString()?.startsWith("alarm.") }) {
            repairHints << "The HSM command field (alarm.${idx}) never appeared. HSM may not be installed: Rule Machine shows that field only when Hubitat Safety Monitor is installed, so this row has no command. Install Hubitat Safety Monitor, then hub_set_rule(removeAction:{index:${idx}}, confirm:true) and re-add the hsm action; or run that removeAction if HSM is not wanted."
        }
        if (!forceWrittenKeys.isEmpty()) {
            def cmpWord = forceWrittenKeys.size() == 1 ? "Comparator" : "Comparators"
            def cmpVerb = forceWrittenKeys.size() == 1 ? "was" : "were"
            repairHints << "${cmpWord} ${forceWrittenKeys.join(', ')} ${cmpVerb} force-written after a transient re-fetch failure and could not be schema-confirmed; the value IS in settingsApplied. Check the action paragraph via hub_get_app_config(appId). Do NOT re-write; re-add via hub_set_rule(walkStep={...}) only if the comparator is missing there."
        }
        genuineSkipped.findAll { it instanceof Map && it.reason in _rmUnrepresentedComparatorReasons() }.each { sk ->
            repairHints << _rmUnrepresentedComparatorHint(sk as Map)
        }
        if ((health?.brokenMarkers as List)?.size() > 0) {
            repairHints << "Rule has pre-existing broken markers: ${(health.brokenMarkers as List).unique().join(', ')}. The new action committed, but run hub_get_rule_health(${appId}) and repair the existing broken trigger/action rows before this rule fires correctly."
        }
    }
    // success: no editor error, the row baked and something was written. partial: a field did not
    // land, the row did not bake, or the rule already carried a broken row.
    def uniqueApplied = []
    applied.each { key -> if (!uniqueApplied.contains(key)) uniqueApplied << key }
    return [
        success: !err && !actionNotBaked && !applied.isEmpty(),
        partial: partial,
        rolledBack: rolledBack,
        fieldsWritten: ((actionNotBaked || err != null) && !rolledBack) ? true : null,
        hubRenderError: hubRenderError,
        actionIndex: idx,
        capability: cap,
        action: action,
        actType: actType,
        actSubType: actSubType,
        settingsApplied: uniqueApplied,
        settingsSkipped: skipped,
        configPageError: err,
        repairHints: repairHints,
        health: health,
        verificationFetchFailed: verificationFetchFailed
    ]
}

private void _rmRejectUnwalkableExpressionConditions(Map actionSpec) {
    if (!(actionSpec?.expression instanceof Map)) return
    def conds = (actionSpec.expression as Map).conditions
    if (!(conds instanceof List)) return
    (conds as List).each { c ->
        if (c instanceof Map && !((c as Map).subExpression instanceof Map)) _rmCheckCondSpec(c as Map, "condition", "addAction.expression.conditions")
    }
}

// The refusals a Required Expression's conditions would hit mid-walk that are decidable from the
// arguments and read-only lookups, run before the pre-edit backup: _rmValidateRequiredExpressionSpec's
// shape checks, _rmCheckCondSpec on each condition and the device-id lookups.
private void _rmPrevalidateConditionList(Map exprSpec, String label) {
    _rmValidateRequiredExpressionSpec(new groovy.json.JsonSlurper().parseText(groovy.json.JsonOutput.toJson(exprSpec)) as Map, label)
    def walk
    walk = { List conds, String path ->
        conds.eachWithIndex { c, i ->
            if (!(c instanceof Map)) return
            Map m = c as Map
            String where = "${path}[${i}]"
            if (m.subExpression instanceof Map) {
                def sub = (m.subExpression as Map).conditions
                if (sub instanceof List) walk(sub as List, "${where}.subExpression.conditions")
                return
            }
            String cap = m.capability?.toString()?.trim()
            if (!cap) throw new IllegalArgumentException("${where}.capability is required. RM is not touched.")
            _rmCheckCondSpec(m, "condition", where)
            cap = m.capability.toString()
            if (m.compareToDevice instanceof Map) {
                Map ctd = m.compareToDevice as Map
                _rmValidateDeviceIdsExist("${where}.compareToDevice.deviceId", [ctd.deviceId])
            }
            def ids = m.deviceIds ?: (m.deviceId != null ? [m.deviceId] : null)
            if (ids != null) _rmValidateDeviceIdsExist("${where}.deviceIds", ids)
            _rmRequireConditionDeviceCapability("${where}.deviceIds".toString(), m)
        }
    }
    if (exprSpec?.conditions instanceof List) walk(exprSpec.conditions as List, "${label}.conditions")
}

private Integer _rmBuildCondition(Integer appId, Integer idx, Map condSpec, List applied, List skipped = null) {
    def condCap = condSpec.capability?.toString()?.trim()
    if (!condCap) throw new IllegalArgumentException("condition.capability is required")

    // Capabilities whose wizard needs progressive reveal-walking (the start/end time-picker
    // chain) are implemented only on the reveal-walker condition surfaces. On this static
    // selectTriggers condition path they would write rCapab_<N> and then have no field
    // handling, committing a broken condition; fail loud pointing at the supported surfaces.
    if (condCap.equalsIgnoreCase("Between two times")) {
        throw new IllegalArgumentException("'Between two times' is not modelled as a trigger's condition; use it in addRequiredExpression or an ifThen action. RM is not touched.")
    }

    _rmCheckCondSpec(condSpec, "condition", "addTrigger.condition")
    condCap = condSpec.capability?.toString() ?: condCap

    // Toggle conditional + open the new-condition picker.
    _rmWriteSettingOnPage(appId, "selectTriggers", "isCondTrig.${idx}", true, applied, null, skipped)
    _rmWriteSettingOnPage(appId, "selectTriggers", "condTrig.${idx}", "a", applied, null, skipped)

    // Walk the condition wizard. Field names use rCapab_<N> / rDev_<N> /
    // state_<N> / not<N> with an underscore (vs trigger's tCapab<N> /
    // tDev<N> / tstate<N> without).
    _rmWriteSettingOnPage(appId, "selectTriggers", "rCapab_${idx}", condCap, applied, null, skipped)

    // Normalize singular deviceId -> deviceIds so callers that pass
    // deviceId: N (integer) get the same behaviour as deviceIds: [N].
    def condDevIds = condSpec.deviceIds
    if (condDevIds == null && condSpec.deviceId != null) {
        condDevIds = [condSpec.deviceId]
    }
    if (condDevIds != null) {
        _rmWriteSettingOnPage(appId, "selectTriggers", "rDev_${idx}", condDevIds, applied, null, skipped)
    }
    // Hub Variable condition: rCapab_<N>=Variable exposes xVar_<N>
    // (variable picker, no device IDs). Verified live 2026-05-17.
    if (condCap == "Variable") {
        if (condSpec.rawSettings != null && !(condSpec.rawSettings instanceof Map)) {
            throw new IllegalArgumentException("condition.rawSettings must be a Map, got ${condSpec.rawSettings.class?.name}.")
        }
        // compareToVariable (variable RHS) and value/state (constant RHS) are mutually
        // exclusive -- RM renders one OR the other. Reject the ambiguous combination before
        // any write so a doc-invalid input fails loud instead of writing both xVar_<N> and
        // state_<N>. Matches the walker's guard on the STPage/doActPage paths.
        if (condSpec.compareToVariable != null && (condSpec.value != null || condSpec.state != null)) {
            throw new IllegalArgumentException("condition.capability='Variable' cannot combine 'compareToVariable' (variable RHS) with 'value'/'state' (constant RHS) -- they are mutually exclusive. Supply exactly one.")
        }
        def hasRawXVar = condSpec.rawSettings instanceof Map &&
            ((condSpec.rawSettings as Map).any { k, _v -> k.toString().replace("@N", idx.toString()) == "xVar_${idx}".toString() })
        if (condSpec.variable == null && !hasRawXVar) {
            throw new IllegalArgumentException("condition.variable is required when condition.capability='Variable' (hub variable name). Use hub_list_variables to discover available names.")
        }
        if (condSpec.variable != null) {
            _rmWriteSettingOnPage(appId, "selectTriggers", "xVar_${idx}", condSpec.variable, applied, null, skipped)
        }
    }
    if (condSpec.comparator != null) {
        // Condition comparator is RelrDev_<n> on every condition page. Custom Attribute: comparator
        // RelrDev_<n>, value picker state_<n>; a failed re-read force-writes the comparator (partial).
        if (condSpec.attribute != null) {
            _rmWriteSettingOnPage(appId, "selectTriggers", "rCustomAttr_${idx}", condSpec.attribute, applied, null, skipped)
            def afterAttr = null
            try {
                // _rmWriteSettingOnPage does not surface the post-write schema to its
                // caller, so this GET is the only way to see which fields the
                // rCustomAttr re-render exposed (comparator vs value picker). Necessary.
                afterAttr = _rmFetchConfigJson(appId, "selectTriggers")
            } catch (Exception fetchEx) {
                mcpLog("warn", "rm-native", "condition Custom Attribute: re-fetch after rCustomAttr_${idx}='${condSpec.attribute}' failed for app ${appId} (${fetchEx.message ?: fetchEx}); force-writing comparator RelrDev_${idx} as fallback (partial)")
            }
            if (afterAttr == null) {
                _rmForceWriteEnumField(appId, "selectTriggers", "RelrDev_${idx}".toString(), _rmNormalizeComparator(condSpec.comparator), applied, skipped)
            } else {
                def afterAttrInputs = (afterAttr?.configPage?.sections ?: []).collectMany { it?.input ?: [] }
                def comparatorExposed = afterAttrInputs.any { it?.name == "RelrDev_${idx}".toString() }
                def valuePickerExposed = afterAttrInputs.any { it?.name == "state_${idx}".toString() }
                // Comparator-first: write whenever exposed; suppress only the true enum
                // case; neither-exposed still attempts (schema-gated -> not_in_schema).
                if (comparatorExposed) {
                    _rmWriteSettingOnPage(appId, "selectTriggers", "RelrDev_${idx}", _rmNormalizeComparator(condSpec.comparator), applied, null, skipped)
                } else if (!valuePickerExposed) {
                    _rmWriteSettingOnPage(appId, "selectTriggers", "RelrDev_${idx}", _rmNormalizeComparator(condSpec.comparator), applied, null, skipped)
                } else {
                }
            }
        } else {
            // No attribute (numeric/text comparator on a standard capability) --
            // the comparator field is already exposed; write it directly.
            int skippedBefore = skipped?.size() ?: 0
            _rmWriteSettingOnPage(appId, "selectTriggers", "RelrDev_${idx}", _rmNormalizeComparator(condSpec.comparator), applied, null, skipped)
            // A device-state condition (Motion, Switch, ...) has no comparator field at all.
            def miss = skipped ? skipped.drop(skippedBefore).find { it instanceof Map && it.key == "RelrDev_${idx}".toString() } : null
            if (miss && miss.available instanceof List && !(miss.available as List).contains(miss.key)) {
                miss.reason = "comparator_not_offered"
                miss.capability = condSpec.capability?.toString()
            }
        }
    }
    if (condSpec.buttonNumber != null) {
        _rmWriteSettingOnPage(appId, "selectTriggers", "ButtontDev_${idx}", condSpec.buttonNumber, applied, null, skipped)
    }
    // isVar_<n>=true reveals the right-hand variable xVarR_<n>; otherwise the right side is state_<n>. If
    // both writes skipped, the condition would silently compare with 0, so fail.
    if (condCap == "Variable" && condSpec.compareToVariable != null) {
        _rmWriteSettingOnPage(appId, "selectTriggers", "isVar_${idx}", true, applied, null, skipped)
        _rmWriteSettingOnPage(appId, "selectTriggers", "xVarR_${idx}", condSpec.compareToVariable, applied, null, skipped)
        // The RHS picker xVarR_<N> is the load-bearing write -- it carries
        // the variable name. If it didn't land, the condition will render
        // as "A <comparator> 0" (state_<N> default) instead of "A vs B".
        // Fail loudly rather than letting the caller think they got A vs B.
        if (!(applied ?: []).contains("xVarR_${idx}".toString())) {
            throw new IllegalStateException("condition.compareToVariable=${condSpec.compareToVariable} could not be written: xVarR_${idx} did not appear after comparator '${condSpec.comparator}'; use one of = ≠ < > <= >= in.")
        }
    }
    def stateValue = condSpec.state != null ? condSpec.state : condSpec.value
    if (stateValue != null) {
        _rmWriteSettingOnPage(appId, "selectTriggers", "state_${idx}", stateValue, applied, null, skipped)
    }
    if (condSpec.not == true) {
        _rmWriteSettingOnPage(appId, "selectTriggers", "not${idx}", true, applied, null, skipped)
    }
    // Caller escape hatch. Supports the '@N' token in field names —
    // replaced with the condition index. Mirrors trigger-side @N
    // expansion so {xVar_@N: 'foo'} writes xVar_<idx>=foo.
    if (condSpec.rawSettings instanceof Map) {
        condSpec.rawSettings.each { k, v ->
            if (v != null) {
                def fieldName = k.toString().replace("@N", idx.toString())
                _rmWriteSettingOnPage(appId, "selectTriggers", fieldName, v, applied, null, skipped)
            }
        }
    }

    // Done with this Condition. Click hasAll on the condition page —
    // saves the condition with auto-assigned ID = idx (RM allocates
    // sequentially), and advances the wizard's trigger index.
    _rmClickAppButton(appId, "hasAll", null, "selectTriggers")

    // Condition ID equals the trigger index that "owned" its setup.
    return idx
}

// Variable math operators and their arity (the valMathOp picker does not say which take a second
// operand). The addAction validator and the setVariable writer both read this.
def _rmMathBinaryOps() { ["+", "-", "*", "/", "%"] }

def _rmMathUnaryOps() {
    ["negate", "absolute", "round", "random", "sqrt", "sin", "cos", "tan",
     "asin", "acos", "atan", "log", "toRadians", "toDegrees"]
}

// The INTERNAL hub-variable type tokens that getAllGlobalVars() reports for the numeric kinds:
// a Number var is "integer" and a Decimal var is "bigdecimal" (live-confirmed -- these are the
// internal tokens, NOT the UI labels "Number"/"Decimal"). String/Boolean/DateTime report "string"/
// "boolean"/"datetime". The setVariable device-attribute (fromDevice) and variable-math (math)
// source modes are NUMERIC-TARGET-ONLY, so this is the single source of truth for "is this target
// numeric". Single-list so the guard and any future caller cannot drift. Case-insensitive at use.
def _rmNumericVarTypeTokens() { ["integer", "bigdecimal"] }

boolean _rmIsNumericVarType(String token) {
    token != null && _rmNumericVarTypeTokens().any { it.equalsIgnoreCase(token) }
}

// Coerce a caller-supplied device-id value to its canonical positive-integer string,
// or null if it is not a positive whole number. Accepts a bare Integer/Long, a
// BigDecimal/BigInteger with no fractional part (72.0 -> "72"), or a digit string;
// rejects fractional numbers, non-positive values, and non-numeric input. Used to gate
// fromDevice.deviceId without false-rejecting a valid whole-number id that arrives as a
// BigDecimal or exceeds Integer range. ASCII-only; no reflection (sandbox-safe).
def _rmAsPositiveDeviceId(Object raw) {
    if (raw == null) return null
    if (raw instanceof Number) {
        // Reject a fractional value (72.5); accept a whole-valued BigDecimal/Long/Integer.
        BigDecimal bd
        try { bd = raw as BigDecimal } catch (Exception ignored) { return null }
        if (bd.stripTrailingZeros().scale() > 0) return null
        BigInteger bi = bd.toBigInteger()
        return bi > 0 ? bi.toString() : null
    }
    def s = raw.toString().trim()
    // A plain run of digits (no sign, no decimal point) is a positive whole number.
    if (!s.matches(/\d+/)) return null
    BigInteger bi
    try { bi = new BigInteger(s) } catch (Exception ignored) { return null }
    return bi > 0 ? bi.toString() : null
}

// AUTHORITATIVE enum-recognized Custom Attribute comparator invariant (all four
// wizard surfaces: trigger row, conditional-trigger condition, STPage + doActPage
// walkers). Picking a Custom Attribute re-renders: a free-valued attribute reveals
// the comparator; an ENUM-recognized attribute (switch/motion/contact/lock/...)
// reveals the value picker INSTEAD and hides the comparator. Decision is
// evidence-based, comparator-first: write the comparator whenever exposed; suppress
// ONLY when the value picker is positively revealed; neither-rendered still attempts.
// The exposure-probe re-fetch is the one new failure surface -- this helper is the
// transient-throw fallback: it force-writes the comparator WITHOUT the schema gate
// (the probe throw leaves the schema empty, so a gated write would falsely reject),
// marks it applied + records a comparator_force_written_unverified skip (partial),
// and never aborts. pageBreadcrumbs follows each page's happy-path write convention:
// default ["mainPage"] (selectTriggers + doActPage via _rmWriteSettingOnPage); STPage
// walker callers pass '[]' (_rmWriteSubPageField -- re-firing the mainPage href would
// reset the in-flight condition-builder accumulator).
private void _rmForceWriteEnumField(Integer appId, String pageName, String key, Object value, List applied, List skipped, String pageBreadcrumbs = '["mainPage"]') {
    // The comparator field is a single-value enum; pre-normalize defensively so a
    // future caller cannot write a raw '!='/'==' the hub does not recognize (idempotent
    // -- an already-normalized token passes through unchanged, and a null/non-comparator
    // value is returned untouched).
    value = _rmNormalizeComparator(value)
    // type:"enum", multiple:false is correct because the only callers force-write a
    // single-value comparator enum field (ReltDev<N>/RelrDev_<N>); no non-enum or
    // multi-select field reaches this helper.
    def synthSchema = [(key.toString()): [type: "enum", multiple: false]]
    def body = _rmBuildSettingsBody(appId, [(key): value], synthSchema)
    // Wizard sub-pages need formAction=update + currentPage + pageBreadcrumbs or the
    // wizard silently rejects the field. The breadcrumb shape mirrors the page's normal
    // write convention (see method docstring) -- ["mainPage"] for _rmWriteSettingOnPage
    // pages, [] for the href-based _rmWriteSubPageField (STPage) walk.
    if (pageName && pageName != "mainPage") {
        body.formAction = "update"
        body.currentPage = pageName
        body.pageBreadcrumbs = pageBreadcrumbs
    }
    // The whole point of this helper is to NOT abort -- it exists because the
    // exposure-probe re-fetch already threw. _rmPostSettings throws on a 4xx
    // (often a stale version token, which we cannot supply here -- the re-fetch
    // that would carry app.version is the one that failed). Catch the throw so a
    // rejected force-write degrades the add (comparator did not land -> partial)
    // instead of propagating and aborting it -- the F9 never-abort contract.
    try {
        _rmPostSettings(appId, body)
    } catch (Exception postEx) {
        mcpLog("warn", "rm-native", "_rmForceWriteEnumField: fallback POST of ${key} on page '${pageName}' for app ${appId} was rejected (${postEx.message ?: postEx}); comparator did not land -- add degrades to partial")
        if (skipped != null) {
            skipped << [key: key, reason: "comparator_force_write_failed", value: value]
        }
        return
    }
    // Dual-record is intentional and NOT double-counting: the POST succeeded, so the
    // key belongs in settingsApplied (the value DID leave for the hub); but it could
    // not be schema-confirmed (the probe re-fetch that would verify it is the one that
    // threw), so it ALSO carries a partial-flagging skip. The downstream repair hint
    // says only "used a degraded write path -- verify via hub_get_app_config", never
    // "not written", so a key in both lists reads accurately: written-but-unverified.
    applied << key
    if (skipped != null) {
        skipped << [key: key, reason: "comparator_force_written_unverified", value: value]
    }
}

// Walk a list of caller-supplied addAction / replaceActions specs and
// project them as a structural sequence the walker can consume.
// Leaf-action specs (those whose capability has no block role) are
// skipped — they don't affect block balance. The pre-flight in
// _applyNativeAppEdit's replaceActions handler and the patches[]
// replaceActions branch both call this to validate the proposed list
// before any clearActions click.
private List _rmStructuralSequenceFromSpecList(List specList) {
    def out = []
    specList.eachWithIndex { spec, i ->
        if (!(spec instanceof Map)) return
        def pair = _rmStructuralPairForCapability(((spec as Map).capability)?.toString())
        if (pair) out << [idx: (i + 1), actType: pair[0], actSubType: pair[1]]
    }
    out
}

// A restoreHint that points at a backup also says when that backup can be evicted.
private String _rmRetentionSuffix(Map backup) {
    return backup?.retentionNote ? " ${backup.retentionNote}".toString() : ""
}

// The roll-back hint for a write that may have changed the rule; without a key, why there is none.
private String _rmRestoreBaselineHint(Map backup) { _rmBackupHint(backup) }

// The roll-back hint for a write whose commit is unconfirmed.
private String _rmRollbackIfNotCommittedHint(Map backup) {
    return backup?.backupKey
        ? "If hub_get_app_config confirms the operation did NOT commit, roll back via hub_restore_backup(backupKey='${backup.backupKey}').${_rmRetentionSuffix(backup)}".toString()
        : "If hub_get_app_config confirms the operation did NOT commit, repair the rule in place: ${_rmNoBackupReason(backup, false)}.".toString()
}

// Single source of truth for the restoreHint wording emitted when a
// pre-flight refusal (the "RM is not touched" sentinel) is detected in a
// caught error message. Both the structured _rmBuildUpdateErrorResponse
// path and the legacy-flat trigger-mutation catch reuse this so the two
// surfaces stay word-identical.
private String _rmPreflightRestoreHint(String reason = null, Map backup = null, String lead = null) {
    def why = reason ? " Reason: ${reason}" : ""
    // Whether a snapshot exists depends on WHICH pre-flight fired -- the ones hoisted ahead of
    // _applyNativeAppEdit's snapshot (unwalkable conditions, disabled app) refuse with backup
    // null, the ones inside the per-operation helpers refuse after it with a fresh backupKey -- so
    // read it off the envelope rather than asserting either case.
    // A reused baseline (same rule edited again inside the reuse window) was taken for an EARLIER
    // edit, so describing it as a snapshot taken before this refusal misstates what it restores.
    def snapshot = !backup?.backupKey ? (_rmPreEditBackupsOff() ? " No backup was taken: pre-edit backups are off in Advanced settings." : " No backup was taken.") :
        (backup.baselineReused == true
            ? " The backupKey on this response is the baseline reused from an earlier edit; restoring it reverts every edit since it was taken, not just this one."
            : " The backupKey on this response is an unused snapshot taken before the refusal.")
    "${lead ?: 'Pre-flight refusal -- the app was not touched, so nothing needs to be restored.'}${snapshot}${why}"
}

// The restore sentence after a committed edit went wrong: the baseline when one exists, else why there is none.
private String _rmBackupHint(Map backup, String withBackup = null) {
    if (backup?.backupKey) {
        return ((withBackup ?: "Backup baseline available. Call hub_restore_backup with backupKey='${backup.backupKey}' to return to that snapshot; a reused baseline undoes every later edit in its one-hour chain.") + _rmRetentionSuffix(backup)).toString()
    }
    return "${_rmNoBackupReason(backup)}, so there is no snapshot to restore; read the rule with hub_get_app_config and repair it in place.".toString()
}

// Why a backup map carries no key: the setting that skipped it, or an unexpected gap.
private String _rmNoBackupReason(Map backup, boolean sentenceStart = true) {
    String s = backup?.skipped ? "no pre-edit backup was taken ('Back up native apps before edits' is off in Advanced settings)" : "no backup key was returned for this edit"
    return sentenceStart ? s.capitalize() : s
}

// The clause a repair hint ends with: the baseline to restore, or why there is none.
private String _rmRestoreClause(Map backup) {
    return backup?.backupKey ? "restore via hub_restore_backup with backupKey='${backup.backupKey}'".toString()
        : "${_rmNoBackupReason(backup, false)}, so repair the rule in place".toString()
}


// Error envelope for an edit's failure. A refusal before the backup decision (backup null), or one
// carrying "RM is not touched", says nothing needs restoring; the outcome flags (wizardStuck,
// fieldsWritten, rolledBack) come from `cause`; the rule's current health is attached.
private Map _rmBuildUpdateErrorResponse(Integer appId, String msg, Map backup, String pageName = "doActPage", Throwable cause = null) {
    def msgStr = msg?.toString() ?: ""
    // No backup means the refusal fired before the baseline snapshot, so nothing was written: a
    // pre-flight refusal whatever its wording (a non-RM app's message carries no RM sentinel).
    // The flags the throw recorded (_rmFail): a wizard left open, fields written before the stop,
    // a row rolled back, nothing sent. Independent of preflight refusal (preflight never opens the wizard).
    Map flags = _rmOutcomeFlags(cause)
    def isPreflightRefusal = msgStr.contains("RM is not touched") || backup == null || flags.notSent == true
    def wizardStuck = flags.wizardStuck == true
    def health = null
    // Best effort -- a health read must never mask the real error, but a failed one is reported.
    try { health = _rmCheckRuleHealth(appId) } catch (Exception healthExc) {
        health = [ok: false, unreadable: true, checkErrors: [healthExc.message ?: healthExc.toString()]]
        mcpLog("warn", "rm-native", "_rmBuildUpdateErrorResponse: health probe failed for app ${appId}: ${healthExc.message}")
    }
    def restoreHint
    if (isPreflightRefusal) {
        // Echo the disabled-rule cause specifically: it is the one refusal whose remedy is a
        // hub setting rather than a change to the caller's arguments, so a caller that reads
        // only the hint would otherwise never learn a disabled rule cannot be edited at all.
        restoreHint = _rmPreflightRestoreHint(
            msgStr.contains("is DISABLED") ? "the rule is disabled, and Hubitat does not allow editing a disabled app -- re-enable it, make the change, then disable it again" : null,
            backup)
    } else if (wizardStuck) {
        // pageName tells the caller which wizard page the cancelCapab recovery click belongs on
        // (doActPage for addAction, STPage for addRequiredExpression). The wizardStuck markers
        // themselves carry no page info, so callers thread it in.
        restoreHint = _rmBackupHint(backup, "Backup baseline available -- restore via hub_restore_backup with backupKey='${backup?.backupKey}'. Restoring returns the app to that snapshot and may undo every later edit in the baseline's one-hour chain.") + " Or, before your next write, close the in-flight wizard manually: hub_set_rule(button='cancelCapab', pageName='${pageName}', confirm=true) for an open condition sub-wizard, hub_set_rule(button='actionCancel', pageName='doActPage', confirm=true) for a stuck editor on a new action row, or button='actionDone' for an editor modifyAction opened on a committed action (actionCancel would delete that action)."
    } else {
        restoreHint = _rmBackupHint(backup)
    }
    def result = [
        success: false,
        appId: appId,
        error: msg,
        wizardStuck: wizardStuck,
        backup: backup,
        restoreHint: restoreHint
    ]
    if (flags.fieldsWritten == true) result.fieldsWritten = true
    if (flags.rolledBack == true) result.rolledBack = true
    if (health != null) result.health = health
    result
}

// Map a high-level addAction / replaceActions capability spec string to
// its [actType, actSubType] pair for the block-aware capabilities only.
// Returns null for leaf actions (switch, lock, log, …) — they don't
// affect structural balance and the pre-flight walker can ignore them.
//
// CASE-SENSITIVE on purpose: must match _rmAddAction's dispatch
// (`cap == "ifThen"` etc.). A case-insensitive match here would let
// mis-cased structural caps like "endIF" pass the replaceActions
// pre-flight as "balanced" (walker treats them as endIf) and then fail
// at the per-item dispatch step after clearActions has already wiped
// the rule — exactly the #178 damage class the pre-flight is meant to
// prevent.
private List _rmStructuralPairForCapability(String cap) {
    if (cap == null) return null
    switch (cap.toString()) {
        case "ifThen":      return ["condActs", "getIfThen"]
        case "elseIf":      return ["condActs", "getElseIf"]
        case "else":        return ["condActs", "getElse"]
        case "endIf":       return ["condActs", "getEndIf"]
        case "repeat":      return ["repeatActs", "getRepeat"]
        case "repeatWhile": return ["repeatActs", "getWhile"]
        case "stopRepeat":  return ["repeatActs", "getStopRepeat"]
        default: return null
    }
}

// A committed Required Expression shows on STPage (RM 5.1.8) as the cancelST + editST pair with no
// inline new-condition selector. requireStopOnST (the add path's existing-RE refusal) also needs
// stopOnST, so a transient render showing the pair without it does not trip the refusal; elsewhere
// stopOnST is left out because its presence varies across firmware.
private boolean _rmIsCommittedRETell(Set names, boolean requireStopOnST = false) {
    if (names == null) return false
    def hasNewCondSelector = names.contains("cond") || names.any { it.startsWith("rCapab_") }
    def base = names.contains("cancelST") && names.contains("editST")
    if (requireStopOnST) base = base && names.contains("stopOnST")
    return base && !hasNewCondSelector
}

// Strip a configPage's schema down to the fields an LLM caller actually
// needs to drive the wizard. Drops sandbox-blocked HTML, layout hints,
// and other noise that bloats responses without adding decision-making
// value.
//
// Returns a list of {name, type, title, multiple, required, options,
// currentValue} for each input the page exposes, plus {hrefs} for any
// navigation links pointing at sub-pages.
private Map _rmCollectWalkSchema(Map configPage, Map liveSettings = null) {
    def inputs = []
    def hrefs = []
    def hasActionDone = false
    def hasHasAll = false
    def hasCancel = false
    for (s in (configPage?.sections ?: [])) {
        for (i in (s?.input ?: [])) {
            if (!(i instanceof Map) || !i.name) continue
            def name = i.name.toString()
            def entry = [
                name: name,
                type: i.type?.toString(),
                title: (i.title?.toString() ?: "").take(80),
                multiple: i.multiple == true,
                required: i.required == true
            ]
            if (i.options != null) {
                // Enum options can be list of strings or list of single-key maps.
                // Surface as a flat list of {value, label} pairs for clarity.
                def opts = []
                (i.options as List).each { o ->
                    if (o instanceof Map && !o.isEmpty()) {
                        def k = o.keySet().iterator().next()
                        opts << [value: k.toString(), label: o.get(k)?.toString()]
                    } else {
                        opts << [value: o?.toString(), label: o?.toString()]
                    }
                }
                entry.options = opts
            }
            if (liveSettings != null && liveSettings.containsKey(name)) {
                entry.currentValue = liveSettings[name]
            }
            inputs << entry
            if (i.type == "button") {
                if (name == "actionDone") hasActionDone = true
                if (name == "hasAll") hasHasAll = true
                if (name?.toLowerCase()?.contains("cancel")) hasCancel = true
            }
        }
        for (b in (s?.body ?: [])) {
            if (b instanceof Map && b.element == "href" && b.page) {
                hrefs << [
                    name: b.name?.toString(),
                    title: b.title?.toString(),
                    targetPage: b.page?.toString(),
                    params: b.params
                ]
            }
        }
    }
    def out = [
        inputs: inputs,
        hrefs: hrefs,
        commitButtons: [
            actionDone: hasActionDone,
            hasAll: hasHasAll,
            cancel: hasCancel
        ]
    ]
    // A wizard page's Next button (dynamicPage nextPage); navigate to it to press Next.
    if (configPage?.nextPage) out.nextPage = configPage.nextPage.toString()
    return out
}

// What renders each RM wizard page or slot, the state RM keeps for it, and button effects: the table
// walkStep's refusals and hints read; the walkStep guide restates it (pinned by WalkStepSourceSpec).
Map _rmWizardPrerequisites() {
    return [
        pages: [
            doActPage: [appState: "doActN", slotState: "actNdx",
                opener: "on selectActions click N with stateAttribute doActN for a new action (slot state.actNdx), or <n>.0.false with stateAttribute doAct to edit action n"],
            selectTriggers: [slotState: "ndx", slotKey: /^(tCapab|tDev|tstate|isCondTrig\.|condTrig\.)/, openerButton: "true",
                opener: "on selectTriggers click true with stateAttribute moreCond for a new trigger (slot state.ndx), or <n> with stateAttribute editCond to edit trigger n"],
            selectConditions: [slotState: "ndx", slotKey: /^(rCapab_|rDev_|state_)/, openerButton: "true",
                opener: "on selectConditions click true with stateAttribute moreCond for a new condition (slot state.ndx), or <n> with stateAttribute editCond to edit condition n"],
            STPage: [editorButton: "editST",
                opener: "on STPage click editST (stateAttribute editST) to open the saved Required Expression in its editor"],
            periodic: [hrefParam: "n",
                opener: "navigate the periodic<n> href from selectTriggers, or pass hrefContext {fromPage: 'selectTriggers', hrefName: 'periodic<n>', hrefParams: {n: <n>}}"]
        ],
        buttons: [
            cancelCapab: "RM drops the open condition sub-wizard on the page's next render"
        ]
    ]
}

private Object _rmAppStateValue(Map status, String name) {
    def appState = status?.appState
    return appState instanceof List ? appState.find { it instanceof Map && it.name?.toString() == name }?.value : null
}

// doActPage builds its editor from state.doActN, so entering it before any slot was opened fails to
// render. A pending predCapabs clear opens one first; an unreadable appState is left to RM's render.
private String _rmWizardPrerequisiteRefusal(List pages, Map status, boolean pendingClear) {
    if (!("doActPage" in pages) || pendingClear || !(status?.appState instanceof List)) return null
    if (_rmAppStateValue(status, "doActN") != null) return null
    def p = _rmWizardPrerequisites().pages.doActPage
    return "walkStep: doActPage renders only inside an open action editor, built from RM's state.${p.appState}, which this rule has not set. Nothing was rendered or written. Open the editor first: ${p.opener}.".toString()
}

// Why a key the page does not show may be missing, from the page and RM's slot state; null if unknown.
private String _rmWizardPrerequisiteHint(String page, String key, Map configPage, Map status) {
    def p = _rmWizardPrerequisites().pages[page]
    if (!p) return null
    def rendered = _rmRenderedButtons(configPage)*.name
    if (p.slotKey && (key =~ p.slotKey).find() && p.openerButton in rendered) {
        return "'${key}' renders only in an open slot: ${p.opener}. RM's state.${p.slotState} is ${_rmAppStateValue(status, p.slotState as String)}.".toString()
    }
    if (p.editorButton && p.editorButton in rendered) return "${page} shows the saved expression, not its editor: ${p.opener}.".toString()
    if (page == "doActPage") {
        def doActN = _rmAppStateValue(status, "doActN")?.toString()
        if (!doActN) return null
        def slot = (doActN == "N" || doActN.startsWith("I")) ? _rmAppStateValue(status, "actNdx") : doActN.tokenize(".")[0]
        return "The open action editor is slot ${slot} (RM's state.doActN is ${doActN}); its keys end in .${slot}, e.g. actType.${slot}.".toString()
    }
    if (p.hrefParam) return "${page} reads its href param ${p.hrefParam} only from the request: ${p.opener}.".toString()
    return null
}

// walkStep — single-step wizard introspection + write/click/navigate.
//
// Designed for LLM-driven dynamic wizard walking. Each call performs at
// most ONE operation and returns a structured snapshot of how the page
// changed: schema diff (inputs that appeared/disappeared/changed), the
// stored-value echo for any write (catches enum case normalization +
// coercion that the schema wouldn't reveal), sub-page hrefs with their
// target page, list-count change for actions/triggers (disambiguates
// "wizard committed cleanly" from "schema went empty because the rule
// is broken"), and a full health check.
//
// Spec shape:
// { page: <pageName>,
// operation: "introspect" | "write" | "click" | "navigate" | "done",
// write?: {<key>: <value>},
// click?: {name, stateAttribute?},
// navigate?: {targetPage},
// validateEnum?: bool,  // if true, also check each value of a LIST write against the
// // input's options (a single value the picker does not list is always refused)
// hrefContext?: {fromPage, hrefName, hrefParams?, hrefIndex?}
// // for sub-page ops + back-nav (done)
// }
//
// "done" submits the current sub-page back to its parent via
// _action_previous=Done, carrying ALL current setting values. Required
// for sub-pages whose parent's row description only renders after a
// full Done submit (e.g. Periodic Schedule renders "?" without it).
// Pass hrefContext={fromPage: <parent>, hrefParams: {n: <idx>}} so RM
// routes the back-nav with the correct paramsForPage.
//
// For "introspect" the call only fetches the schema — no mutation. The
// `before` snapshot is the same as `after` and `diff` is empty.
Map _rmWalkStep(Integer appId, Map spec) {
    // The body runs as a closure so its call sites live in the closure's own class: the app class is
    // near the JVM 65,535 constant-pool limit. Behaviour is unchanged.
    def _impl = {
    // drive runs a sequence of single steps in one call; each step names its page.
    if (spec?.operation?.toString()?.trim() == "drive") {
        return _rmDriveWalkSteps(appId, spec)
    }
    def page = spec?.page?.toString()?.trim()
    if (!page) throw new IllegalArgumentException("walkStep.page is required (e.g. 'selectTriggers', 'selectActions', 'doActPage', 'mainPage', 'periodic')")
    if (!page.matches(/[A-Za-z0-9_]+/)) throw new IllegalArgumentException("walkStep.page must be alphanumeric/underscore")
    def operation = spec?.operation?.toString()?.trim() ?: "introspect"
    def validateEnum = spec?.validateEnum == true
    // Within a drive, carry the page RM rendered in response to the previous write.
    // Transient wizard state (e.g. the condition opened by cond=a) exists only in that response; a
    // fresh GET re-renders the pre-write page. Any non-write operation invalidates the carry.
    def walkCache = spec?.__pageCache instanceof Map ? (Map) spec.__pageCache : null
    if (walkCache != null && operation != "write") walkCache.clear()

    def navTarget = (operation == "navigate" && spec?.navigate instanceof Map) ? ((Map) spec.navigate).targetPage?.toString() : null
    // Read before any render: a page entered without the state RM builds it from is refused here.
    def beforeStatus = _rmFetchStatusJson(appId)
    boolean actionPage = [page, navTarget].any { it in ["selectActions", "doActPage"] }
    boolean pendingClear = actionPage && _rmPendingPredClearSnapshot().get(appId.toString()) != null
    boolean runsClear = operation in ["write", "click", "navigate"] && actionPage
    def prereqRefusal = _rmWizardPrerequisiteRefusal([page, navTarget], beforeStatus, pendingClear && runsClear)
    if (prereqRefusal) throw _rmFail(new IllegalArgumentException(prereqRefusal), [notSent: true])

    // walkStep can author actions, so run any deferred predCapabs clear first (from an earlier
    // addRequiredExpression), or the action is wrapped in IF(**Broken Condition**).
    if (runsClear) {
        _rmRunPendingPredCapabsClear(appId)
        // The clear opens and cancels an action slot, so the status read above is stale.
        if (pendingClear) beforeStatus = _rmFetchStatusJson(appId)
    }

    // hrefContext re-sends a sub-page's href marker on every request, since state like the periodic
    // page's state.n exists only while the request carries it. Shape: {fromPage, hrefName, hrefIndex?,
    // hrefParams: {n: 1}}.
    def hrefContext = spec?.hrefContext instanceof Map ? spec.hrefContext as Map : null
    def hrefContextMarkers = null
    if (hrefContext) {
        def hcName = hrefContext.hrefName?.toString() ?: "name"
        def hcParams = hrefContext.hrefParams instanceof Map ? hrefContext.hrefParams as Map : null
        def hcIndex = hrefContext.hrefIndex != null ? (hrefContext.hrefIndex as Integer) :
            (hcParams?.n != null ? (hcParams.n as Integer) : 0)
        hrefContextMarkers = [
            ("_action_href_${hcName}|${page}|${hcIndex}".toString()): ""
        ]
        if (hcParams) {
            hrefContextMarkers["params_for_action_href_${hcName}|${page}|${hcIndex}".toString()] = groovy.json.JsonOutput.toJson(hcParams)
        }
    }

    // Capture BEFORE state. For sub-pages with hrefContext, route the
    // fetch through _rmNavigateToPage so state.<paramKey> is set; that
    // call's response IS the page rendered with state in scope.
    def beforeCfg
    boolean navRetriedBefore = false
    // A sub-page's Done may reuse this render only when it is the navigation's own response; a
    // plain-GET fallback renders a param sub-page without its route state.
    boolean beforeFromNav = false
    if (hrefContext) {
        def hcName = hrefContext.hrefName?.toString() ?: "name"
        def hcParams = hrefContext.hrefParams instanceof Map ? hrefContext.hrefParams as Map : null
        def hcIndex = hrefContext.hrefIndex != null ? (hrefContext.hrefIndex as Integer) :
            (hcParams?.n != null ? (hcParams.n as Integer) : 0)
        def fromPage = hrefContext.fromPage?.toString() ?: page
        def navResp = _rmNavigateToPage(appId, fromPage, page, hcIndex, hcName, hcParams, null, spec?.__reqT0 as Long, true)
        if (navResp?.navRetried == true) navRetriedBefore = true
        beforeCfg = navResp ? [configPage: navResp.configPage, app: navResp.app] : _rmFetchConfigJson(appId, page)
        beforeFromNav = navResp != null
    } else {
        beforeCfg = _rmFetchConfigJson(appId, page, walkCache)
        beforeFromNav = true
    }
    def beforeSettings = (beforeStatus?.appSettings ?: []).collectEntries { [(it?.name?.toString()): it?.value] }
    def beforeSchema = _rmCollectWalkSchema(beforeCfg?.configPage, beforeSettings)
    // Count only, off the status already in hand -- no order needed and no second
    // fetch. _rmCollectActionIndices would read the compiled rule for an ordering
    // this never looks at.
    def beforeActionCount = _rmActionIndicesFromSettings(beforeStatus).size()
    def beforeTriggerCount = _rmCollectTriggerIndices(appId).size()

    def opResult = [:]
    if (navRetriedBefore) opResult.navRetried = true
    def writtenKey = null
    def writtenValue = null

    if (operation == "introspect") {
        // No mutation — just return the schema snapshot.
    } else if (operation == "write") {
        if (!(spec?.write instanceof Map) || ((Map) spec.write).isEmpty()) {
            throw new IllegalArgumentException("walkStep operation='write' requires write={<key>: <value>}")
        }
        def writeMap = spec.write as Map
        if (writeMap.size() != 1) throw new IllegalArgumentException("walkStep.write should contain exactly one key -- call once per field for clean schema-diff signals")
        writtenKey = writeMap.keySet().iterator().next().toString()
        writtenValue = writeMap.get(writtenKey)
        // doActPage can advance its slot after rendering an indexed field. Rebind a missing key only on
        // doActPage, same stem and delimiter, and exactly one live candidate.
        def requestedWriteKey = writtenKey
        def resolveWriteKey = { Map schema ->
            def exact = schema.inputs.find { it.name == requestedWriteKey }
            if (exact || page != "doActPage") return [input: exact, key: requestedWriteKey]
            def requestedMatcher = (requestedWriteKey =~ /^(.+[._-])(\d+)$/)
            if (!requestedMatcher.matches()) return [input: null, key: requestedWriteKey]
            def requestedStem = requestedMatcher.group(1)
            def liveCandidates = schema.inputs.findAll { input ->
                def candidateName = input?.name?.toString() ?: ""
                def candidateMatcher = (candidateName =~ /^(.+[._-])(\d+)$/)
                candidateMatcher.matches() && candidateMatcher.group(1) == requestedStem
            }
            return liveCandidates.size() == 1 ? [input: liveCandidates[0], key: liveCandidates[0].name.toString(), rebound: true] : [input: null, key: requestedWriteKey]
        }
        def resolved = resolveWriteKey(beforeSchema)
        if (resolved.input == null && walkCache != null && !walkCache.isEmpty()) {
            // Carry miss recovery: a carried POST echo can lag one render and omit the field the
            // previous write revealed. Drop the carry and resolve once against a live page before
            // concluding the key is absent. The previous write is never re-posted.
            walkCache.clear()
            beforeCfg = _rmFetchConfigJson(appId, page, walkCache)
            beforeSchema = _rmCollectWalkSchema(beforeCfg?.configPage, beforeSettings)
            resolved = resolveWriteKey(beforeSchema)
            opResult.carryRefetched = true
        }
        def schemaInput = resolved.input
        writtenKey = resolved.key
        if (resolved.rebound) opResult.rebound = [requestedKey: requestedWriteKey, resolvedKey: writtenKey]
        // A device input given the {id: label} map hub_get_app_config returns is written (and echoed)
        // as its id list, as the settings path sends it.
        if (writtenValue instanceof Map && _isDevicePickerType(schemaInput?.type)) {
            writtenValue = (writtenValue as Map).keySet().collect { it?.toString() }
        }
        if (validateEnum && schemaInput?.options) {
            def validValues = schemaInput.options.collect { it.value?.toString() }
            def writtenStr = writtenValue?.toString()
            // For multi-enum, value may be a list — check each.
            def values = (writtenValue instanceof List) ? writtenValue.collect { it?.toString() } : [writtenStr]
            def invalid = values.findAll { v -> v != null && !validValues.contains(v) }
            if (invalid) {
                def valueWord = (invalid.size() == 1) ? "value" : "values"
                throw new IllegalArgumentException("walkStep.write enum validation failed: ${valueWord} ${invalid} not in options ${validValues} for field '${writtenKey}'. Pick values from the options list (a single value the picker does not list is always refused; validateEnum=false lets a list through unchecked).")
            }
        }
        def fullSchemaMap = _rmCollectInputSchema(beforeCfg?.configPage)
        def pageSkipped = []
        if (page == "mainPage" && !hrefContextMarkers) {
            // The main page keeps its settings write; only the refusals are shared with sub-pages.
            def mainRefusal = schemaInput ? _rmNotOfferedSkip(fullSchemaMap.get(writtenKey) as Map, writtenKey, writtenValue) :
                [key: writtenKey, reason: "not_in_schema", value: writtenValue, available: fullSchemaMap.keySet().toList().sort()]
            if (mainRefusal) pageSkipped << mainRefusal
            else _rmUpdateAppSettings(appId, [(writtenKey): writtenValue], fullSchemaMap, walkCache)
        } else {
            // Sub-pages write through the schema writer against the page already read (seeded into
            // the cache), with page context; an href sub-page adds its markers so RM re-sets the
            // route state (e.g. periodic's state.n) before applying the write.
            Map writeCache = walkCache != null ? walkCache : [:]
            _rmCacheStore(writeCache, appId, page, beforeCfg as Map)
            Map extras = null
            if (hrefContextMarkers) {
                extras = [:] + hrefContextMarkers
                try {
                    def cfg = _rmFetchConfigJson(appId, hrefContext.fromPage?.toString() ?: page)
                    if (cfg?.app?.version != null) extras.version = cfg.app.version.toString()
                } catch (Exception verExc) {
                    mcpLog("warn", "rm-native", "walkStep: href-context version fetch for app ${appId} on page '${hrefContext.fromPage ?: page}' failed (${verExc.message}) -- POSTing write without version field; hub may reject on concurrent-edit conflict")
                }
            }
            _rmWriteSettingOnPage(appId, page, writtenKey, writtenValue, [], null, pageSkipped, writeCache, extras)
            boolean posted = !pageSkipped.any { it.reason in ["not_in_schema", "not_offered"] }
            if (posted && !hrefContextMarkers) _rmVerifySubPageMultipleFlags(appId, page, [(writtenKey): writtenValue], fullSchemaMap, walkCache)
            // A committed device pick reveals nothing and reads as silent_rejection; re-tag it as addAction does.
            _rmReclassifyDeviceListSkips(appId, pageSkipped)
        }
        if (pageSkipped) opResult.skipped = pageSkipped
        def refusedSkip = pageSkipped.find { it.reason in ["not_in_schema", "not_offered"] }
        if (refusedSkip) opResult.refused = refusedSkip
        else opResult.wrote = [(writtenKey): writtenValue]
    } else if (operation == "click") {
        if (!(spec?.click instanceof Map) || !spec.click.name) {
            throw new IllegalArgumentException("walkStep operation='click' requires click={name, stateAttribute?}")
        }
        def btnName = spec.click.name?.toString()
        def stateAttr = spec.click.stateAttribute?.toString()
        // Checked against the page just read; RM would run the handler of a button it does not show.
        _rmRequireRenderedButton(beforeCfg?.configPage as Map, btnName, page)
        _rmClickAppButton(appId, btnName, stateAttr, page)
        opResult.clicked = [name: btnName, stateAttribute: stateAttr]
        def effect = _rmWizardPrerequisites().buttons[btnName]
        if (effect) opResult.note = effect
    } else if (operation == "done") {
        // Submit the page back to its parent with _action_previous=Done and every current setting: the
        // periodic trigger's description renders only after this. paramsForPage carries the route param;
        // pageBreadcrumbs names the page to render next.
        def parentPage = hrefContext?.fromPage?.toString()
        def hcParams = hrefContext?.hrefParams instanceof Map ? hrefContext.hrefParams as Map : null
        def hcHrefName = hrefContext?.hrefName?.toString() ?: "name"
        // The BEFORE render above already opened this page (with its href params in scope), so
        // the Done reuses it rather than navigating into the page a second time.
        _rmSubmitSubPageDone(appId, page, parentPage, hcHrefName, hcParams, null, beforeFromNav ? beforeCfg : null)
        opResult.done = [from: page, parent: parentPage]
        // After done, schema lives at the parent page.
        page = parentPage ?: page
    } else if (operation == "navigate") {
        def target = spec?.navigate?.targetPage?.toString()
        if (!target) throw new IllegalArgumentException("walkStep operation='navigate' requires navigate={targetPage}")
        // The href's name and params build the marker pair _action_href_<name>|<page>|<idx> and
        // params_for_action_href_<name>|<page>|<idx>; without one, name="name", index 0, no params.
        def hrefMatch = beforeSchema.hrefs.find { it.targetPage == target }
        def hrefName = hrefMatch?.name?.toString() ?: (beforeSchema.nextPage == target ? "__next__" : "name")
        def hrefIndex = 0
        def hrefParams = null
        if (hrefMatch?.params != null) {
            hrefParams = hrefMatch.params as Map
            // The |<n> suffix is a render-time form counter, not params.n; params.n is used only when no
            // hrefIndex is given.
            if (hrefParams.n != null) hrefIndex = hrefParams.n as Integer
        }
        if (spec.navigate.hrefIndex != null) {
            hrefIndex = spec.navigate.hrefIndex as Integer
        }
        // The navigation response is the target page rendered with the href params; the AFTER block uses it
        // (a fresh GET loses them). navRetried marks that the caller sees the one re-read render.
        def navResp = _rmNavigateToPage(appId, page, target, hrefIndex, hrefName, hrefParams, null, spec?.__reqT0 as Long, true, true)
        if (navResp?.navRetried == true) opResult.navRetried = true
        opResult.navResponseConfigPage = navResp?.configPage
        opResult.navigated = [from: page, to: target, hrefName: hrefName, hrefIndex: hrefIndex, hrefParams: hrefParams]
        // After navigation the schema lives at the target page, not the source.
        page = target
    } else {
        throw new IllegalArgumentException("walkStep.operation must be 'introspect', 'write', 'click', 'navigate', or 'done'; got '${operation}'")
    }

    // AFTER state: a navigate reads the navigation response (a follow-up GET loses the href params);
    // other ops on an hrefContext sub-page re-fetch through _rmNavigateToPage.
    def afterCfg
    if (operation == "navigate" && opResult.navResponseConfigPage) {
        afterCfg = [configPage: opResult.navResponseConfigPage]
        opResult.remove("navResponseConfigPage")  // keep it out of the user-facing result
    } else if (operation == "done") {
        // After done, page already switched to parent. Plain fetch is fine —
        // parent pages don't carry the sub-page's paramsForPage state, so
        // routing through _rmNavigateToPage would re-enter the sub-page
        // and undo the done.
        afterCfg = _rmFetchConfigJson(appId, page)
    } else if (hrefContext) {
        def hcName = hrefContext.hrefName?.toString() ?: "name"
        def hcParams = hrefContext.hrefParams instanceof Map ? hrefContext.hrefParams as Map : null
        def hcIndex = hrefContext.hrefIndex != null ? (hrefContext.hrefIndex as Integer) :
            (hcParams?.n != null ? (hcParams.n as Integer) : 0)
        def fromPage = hrefContext.fromPage?.toString() ?: page
        def navResp = _rmNavigateToPage(appId, fromPage, page, hcIndex, hcName, hcParams, null, spec?.__reqT0 as Long, true)
        if (navResp?.navRetried == true) opResult.navRetried = true
        afterCfg = navResp ? [configPage: navResp.configPage] : _rmFetchConfigJson(appId, page)
    } else {
        if (walkCache != null && operation != "write") walkCache.clear()
        afterCfg = _rmFetchConfigJson(appId, page, walkCache)
    }
    def afterStatus = _rmFetchStatusJson(appId)
    def afterSettings = (afterStatus?.appSettings ?: []).collectEntries { [(it?.name?.toString()): it?.value] }
    def afterSchema = _rmCollectWalkSchema(afterCfg?.configPage, afterSettings)
    def afterActionCount = _rmActionIndicesFromSettings(afterStatus).size()
    def afterTriggerCount = _rmCollectTriggerIndices(appId).size()

    // Schema diff.
    def beforeNames = beforeSchema.inputs.collect { it.name } as Set
    def afterNames = afterSchema.inputs.collect { it.name } as Set
    def appeared = (afterNames - beforeNames).toList().sort()
    def disappeared = (beforeNames - afterNames).toList().sort()

    // Value echo: did the stored value match the write (catches case normalization and flag coercion)?
    // A device picker stores null with its ids in deviceIdsForDeviceList, so compare those.
    def valueEcho = null
    if (writtenKey != null && opResult.refused == null) {
        def storedValue = afterSettings[writtenKey]
        def schemaInputForKey = beforeSchema.inputs.find { it.name == writtenKey }
        if (_isDevicePickerType(schemaInputForKey?.type)) {
            // The ids live in the raw appSettings entry (deviceIdsForDeviceList, else the deviceList
            // map); afterSettings only carries .value, which is null or the list's text.
            def rawEntry = (afterStatus?.appSettings ?: []).find { it?.name?.toString() == writtenKey }
            def storedIds = _rmStatusEntryDeviceIds(rawEntry)
            storedValue = storedIds ? storedIds.toList() : _devicePickerIds(storedValue)
        }
        // statusJson stores a multi-select enum as a JSON-array STRING ('["a","b"]'), for Room
        // Lighting and RM alike (verified live, fw 2.5.1.181) -- the same shape the UI posts. When a
        // List was written, compare against the parsed members, not the raw string.
        if (writtenValue instanceof List && storedValue instanceof String && storedValue.trim().startsWith("[")) {
            try {
                def parsedStored = new groovy.json.JsonSlurper().parseText(storedValue as String)
                if (parsedStored instanceof List) storedValue = parsedStored
            } catch (Exception notJson) {
                mcpLog("debug", "rm-native", "walkStep valueEcho: stored value for ${writtenKey} is not a JSON array (${notJson.message}); comparing as text")
            }
        }
        // Normalize comparison — both serialized to strings.
        def writtenStr = writtenValue instanceof List
            ? writtenValue.collect { it?.toString() }.sort().join(",")
            : (writtenValue?.toString() ?: "")
        def storedStr = storedValue instanceof Map
            ? storedValue.keySet().toList().sort().join(",")
            : (storedValue instanceof List ? storedValue.collect { it?.toString() }.sort().join(",") : (storedValue?.toString() ?: ""))
        valueEcho = [
            key: writtenKey,
            written: writtenValue,
            stored: storedValue,
            match: writtenStr == storedStr
        ]
        if (!valueEcho.match) {
            valueEcho.note = "Stored value differs from written value — hub may have normalized case, coerced type, or rejected silently. Inspect 'stored' to see what RM has."
        }
    }

    // Disambiguate "schema empty after click = wizard committed" vs
    // "schema empty = wizard broke and dropped the trigger/action".
    def commitSignal = null
    if (afterSchema.inputs.isEmpty() && (operation == "click" || operation == "navigate")) {
        if (afterActionCount > beforeActionCount) {
            commitSignal = "action_committed"
        } else if (afterTriggerCount > beforeTriggerCount) {
            commitSignal = "trigger_committed"
        } else if (afterActionCount == beforeActionCount && afterTriggerCount == beforeTriggerCount) {
            commitSignal = "schema_empty_no_commit_check_health"
        }
    }

    // Once the relay budget is spent, skip the trailing probe and report mutations unverified. In a
    // drive, mutating steps defer the probe to the final health check: rendering other rule pages resets
    // RM's open editor (doActPage and STPage alike), so the gate is on the operation, which covers done.
    def health = (walkCache != null && operation in ["write", "click", "navigate", "done"]) ?
        _rmEmptyHealthVerdict(ok: true, skipped: true, source: "deferred", note: "health probe deferred to the end of the drive (wizard in progress)") :
        _rmWalkStepHealth(appId, spec?.__reqT0 as Long, spec?.__activePage != true ? null :
            ((operation == "navigate" && spec?.navigate instanceof Map) ? (((Map) spec.navigate).targetPage?.toString() ?: page) : page))
    def silentRejection = (operation == "write") &&
        appeared.isEmpty() && disappeared.isEmpty() &&
        valueEcho?.match == false

    // A render error must stop the drive even when the operation committed: subsequent
    // steps must not write into a page RM could not build.
    def pageError = afterCfg?.configPage?.error
    def result = [
        success: pageError == null && _rmHealthGatePass(health) && (operation != "write" || (valueEcho?.match != false && opResult.refused == null)),
        page: page,
        operation: operation,
        before: beforeSchema,
        after: afterSchema,
        diff: [
            appeared: appeared,
            disappeared: disappeared
        ],
        opResult: opResult,
        valueEcho: valueEcho,
        listCounts: [
            beforeActions: beforeActionCount,
            afterActions: afterActionCount,
            beforeTriggers: beforeTriggerCount,
            afterTriggers: afterTriggerCount
        ],
        commitSignal: commitSignal,
        silentRejection: silentRejection,
        health: health
    ]
    if (pageError != null) {
        result.pageError = pageError.toString()
        // An error page that still returned inputs is a different animal from one that returned
        // nothing: only the second one explains an empty `after` schema.
        def emptyAfter = afterSchema.inputs.isEmpty() && afterSchema.hrefs.isEmpty()
        String renderHint
        if (commitSignal in ["action_committed", "trigger_committed"]) {
            renderHint = "The operation committed (${commitSignal}), but the page '${page}' rendered with an error (${pageError}). Do not repeat the operation; repair the page before continuing."
        } else if (commitSignal == "schema_empty_no_commit_check_health") {
            // Only a click/navigate computes the commit signal, so only there can "nothing new
            // was observed" be said.
            renderHint = "The page '${page}' rendered with an error (${pageError}); its schema is empty because RM could not build it, and no new action or trigger was observed."
        } else if (emptyAfter) {
            renderHint = "The page '${page}' rendered with an error (${pageError}); its schema is empty because RM could not build it."
        } else {
            renderHint = "The page '${page}' rendered with an error (${pageError}) alongside its inputs, so what it shows may not reflect what RM stored."
        }
        result.repairHints = (result.repairHints ?: []) + [renderHint + " Enter the page the way the wizard does (the href or button on its parent page) rather than by name."]
    }
    def refused = opResult.refused as Map
    if (refused != null) {
        result.error = (refused.reason == "not_offered" ?
            "walkStep write refused: the '${refused.key}' picker does not offer ${refused.value} (it offers ${refused.offered}); nothing was written" :
            "walkStep write refused: '${refused.key}' is not an input on page '${page}' (it shows ${refused.available}); nothing was written").toString()
        def hint = refused.reason == "not_in_schema" ? _rmWizardPrerequisiteHint(page, writtenKey, beforeCfg?.configPage as Map, beforeStatus) : null
        if (hint) result.repairHints = (result.repairHints ?: []) + [hint]
    } else if (page == "periodic" && !hrefContext && operation != "navigate") {
        result.repairHints = (result.repairHints ?: []) + [_rmWizardPrerequisiteHint(page, "", null, null)]
    }
    if (health.unreadable == true) {
        result.repairHints = (result.repairHints ?: []) + ["The post-op health probe could not be read -- no evidence of breakage either way (a transient failure, or the rule may since have been removed); the operation itself committed. Verify via hub_get_rule_health(${appId}).".toString()]
    }
    // A standalone mutation has no drive-level check waiting to verify its committed work.
    // Preserve explicit page/echo failures; only replace an otherwise-clean, unverified success.
    if (walkCache == null && operation in ["write", "click", "navigate", "done"] &&
            health.skipped == true && result.success == true) {
        result.success = false
        result.partial = true
        result.healthUnverified = true
        result.error = "walkStep ${operation} committed but its health check was skipped because the time budget ran out, so the rule's state is unverified".toString()
        result.repairHints = (result.repairHints ?: []) + ["The operation committed, so do not re-run it. Check hub_get_rule_health(appId=${appId}) and address any reported issue before continuing or treating the rule as complete.".toString()]
    }
    return result
    }
    return _impl()
}

// Sub-page writes go through _rmWriteSettingOnPage for page context, which skips the sticky multiple-flag check
// _rmUpdateAppSettings performs. Only inputs declared multiple=true are checked; a flipped flag gets one re-POST of
// the same group with page context, and a divergence that survives it throws.
private void _rmVerifySubPageMultipleFlags(Integer appId, String pageName, Map settingsMap, Map schema, Map cache = null) {
    def touched = settingsMap.keySet().collect { it.toString() }
    if (!touched.any { schema?.get(it)?.multiple == true }) return
    try {
        _rmVerifyMultipleFlags(appId, schema, touched)
    } catch (IllegalStateException divergence) {
        mcpLog("warn", "rm-native", "Marshal divergence on app ${appId} page ${pageName} -- retrying with page context: ${divergence.message}")
        def body = _rmBuildSettingsBody(appId, settingsMap, schema)
        body.formAction = "update"
        body.currentPage = pageName
        body.pageBreadcrumbs = _rmPageBreadcrumbs(appId, pageName, '["mainPage"]')
        // The carried POST echo already holds the current version token; a live read is the fallback.
        def cfg = _rmFetchConfigJson(appId, pageName, cache)
        if (cfg?.app?.version != null) body.version = cfg.app.version.toString()
        _rmPostSettings(appId, body, cache)
        try {
            _rmVerifyMultipleFlags(appId, schema, touched, false)
        } catch (IllegalStateException persistent) {
            // The shared verifier's advice is to re-POST the group, which this helper has just done.
            throw new IllegalStateException("${persistent.message} Automatic recovery was already attempted: one full-group re-POST with page context on page '${pageName}' did not restore the flag. The write may already be committed, so do not resend it; check hub_get_rule_health(appId=${appId}) and restore the pre-edit backup if one was taken (restoreHint) and the rule is damaged.".toString())
        }
    }
}

// The trailing health probe for a walkStep op, budget-aware: once the transport
// time budget is spent the probe is SKIPPED (an ok-shaped verdict flagged
// skipped:true, with a pointer to hub_get_rule_health) so the committed op's
// response returns under the transport ceiling instead of burning the remaining
// window on diagnostics. t0 == null (no budget threaded) always probes.
private Map _rmWalkStepHealth(Integer appId, Long t0, String activePage = null) {
    if (_timeBudgetExceeded(t0)) {
        return _rmEmptyHealthVerdict(ok: true, skipped: true, source: "skipped",
                note: "health probe skipped: the transport time budget was reached after the committed operation -- verify via hub_get_rule_health(${appId})".toString())
    }
    return activePage ? _rmCheckRuleHealth(appId, "auto", activePage) : _rmCheckRuleHealth(appId)
}

// Health gate for committed work: fail only on positive evidence of breakage. Skipped (ok:true) and
// unreadable both pass.
private boolean _rmHealthGatePass(Map health) {
    return health?.ok == true || health?.unreadable == true
}

// Structural issues of an RM rule computed the same way the health check does, from the compiled action list and
// statusJson settings only. Null for an app that is not an RM rule; a compiled-state read that failed throws, so the
// caller can say why the baseline is missing rather than mistaking the failure for a non-RM app.
List _rmStructuralBaseline(Integer appId) {
    def cs = _ruleCompiledState(appId)
    if (cs?.readError != null) throw new IllegalStateException("compiled rule state could not be read: ${cs.readError}")
    if (cs == null || cs.ruleFormat != "rm") return null
    def settingsByName = _rmFetchSettingsByName(appId)
    return _rmStructuralIssuesFromSequence(_rmStructuralSequenceFromSettings(settingsByName, ([] as Set), _rmCoerceActionIndices(cs.actionList)))
        .collect { it?.toString() }
}

// Non-empty list when the final verdict fails ONLY on structural issues that were all present in the baseline
// taken before the drive; null otherwise (no baseline, any new issue, or any other kind of breakage still fails).
// orphanedActionRows are left out on purpose: the health contract keeps them diagnostic, outside issues and ok.
private List _rmPreExistingStructuralOnly(List baseline, Map finalHealth) {
    if (baseline == null || !(finalHealth instanceof Map) || finalHealth.ok == true || finalHealth.unreadable == true) return null
    if (finalHealth.broken == true || (finalHealth.brokenMarkers as List) ||
            (finalHealth.validationErrors as List) || (finalHealth.multipleFlagPoison as List) || finalHealth.configPageError != null ||
            finalHealth.label?.toString()?.contains("*BROKEN*")) return null
    def after = ((finalHealth.structuralIssues as List) ?: []).collect { it?.toString() }
    if (!after) return null
    return _rmPreExistingStructuralSubset(baseline, finalHealth).size() == after.size() ? after : null
}

// Final structural issues that were already present in the baseline, matched one by one; empty without a baseline.
private List _rmPreExistingStructuralSubset(List baseline, Map finalHealth) {
    if (baseline == null || !(finalHealth instanceof Map)) return []
    def before = baseline.collect { it?.toString() } as Set
    return ((finalHealth.structuralIssues as List) ?: []).collect { it?.toString() }.findAll { before.contains(it) }
}

// Auto-driver for walkStep (operation='drive'): run an ordered list of
// single-step operations in one call, composing _rmWalkStep per step. Each
// step is a normal walkStep spec ({operation, page?, write?/click?/navigate?/
// done?, hrefContext?, validateEnum?}); a step that omits `page` inherits the
// page the previous step left off on, because navigate moves to the target
// page and done moves back to the parent. The drive stops at the first failed
// step unless stopOnError=false, and returns an aggregate {steps:[...],
// success, health} -- the per-step before/after schemas are omitted to keep
// the envelope small; each step keeps its diff/valueEcho/health fail-loud
// signals. This is the progressive flow that does the loop the LLM used to
// drive by hand across N separate calls.
private Map _rmDriveWalkSteps(Integer appId, Map spec) {
    if (!(spec?.steps instanceof List) || ((List) spec.steps).isEmpty()) {
        throw new IllegalArgumentException("walkStep operation='drive' requires a non-empty steps=[...] list, each item a single-step spec {operation, page?, write?/click?/navigate?/done?, hrefContext?}")
    }
    def steps = (List) spec.steps
    def stopOnError = spec?.stopOnError != false   // default: stop at the first failed step
    def stepResults = []
    def currentPage = spec?.page?.toString()?.trim()
    def allOk = true
    def lastStepOperation = null
    def driveWalkCache = [:]   // per-drive page carry, see _rmWalkStep
    // Time-budget self-pause: set when the budget is reached BETWEEN steps so the
    // post-loop path returns an in_progress envelope with the unrun steps instead of
    // the normal fail-loud rollup. Null whenever no pause fires (within budget, or
    // no budget configured for this transport).
    Integer pausedAtStep = null
    List stepsRemaining = null
    // Pre-flight: validate every step's SHAPE before issuing ANY live POST. A drive is
    // partial-commit by nature (each write/click is a live POST), so a structural mistake
    // -- a non-object step, or a nested drive -- must reject the whole drive up front,
    // never after steps 1..N-1 have already mutated the rule. (Runtime errors that surface
    // only on execution are handled per-step inside the loop, where the partial trace of
    // the steps that already committed is preserved.)
    steps.eachWithIndex { rawStep, i ->
        if (!(rawStep instanceof Map)) {
            throw new IllegalArgumentException("walkStep.drive step ${i + 1} must be an object {operation, ...}")
        }
        if (((Map) rawStep).operation?.toString()?.trim() == "drive") {
            throw new IllegalArgumentException("walkStep.drive steps cannot nest operation='drive' (step ${i + 1})")
        }
    }
    // Structural baseline before any step, from the compiled action list and app settings (no page render, so an
    // in-flight wizard is never reset). The terminal gate then fails only on structural issues this drive introduced.
    List baselineStructural = null
    String baselineUnavailable = null
    // Without a baseline the strict gate stays in force; a failed read is logged and explained if it decides the result.
    try { baselineStructural = _rmStructuralBaseline(appId) }
    catch (Exception baselineExc) {
        baselineUnavailable = baselineExc.message ?: baselineExc.toString()
        mcpLog("warn", "rm-native", "walkStep drive: structural baseline read failed for app ${appId} (${baselineUnavailable}) -- issues already present before the drive cannot be exempted")
    }
    int idx = 0
    for (def rawStep : steps) {
        idx++
        // Time-budget checkpoint -- BETWEEN steps only. Never before the
        // first step (idx > 1) and never after a step already failed (allOk), so a
        // pause always represents a clean partial: every earlier step committed and
        // nothing failed. Each step is a live POST, so stopping here and handing back
        // the unrun steps lets the caller resume before the transport drops the response.
        if (idx > 1 && allOk && _resumableBudgetExceeded(spec?.__reqT0 as Long)) {
            pausedAtStep = idx
            stepsRemaining = steps.subList(idx - 1, steps.size()).collect { _stripInternalClock(it) }
            break
        }
        def step = new LinkedHashMap((Map) rawStep)
        // Thread the budget clock into the (already-copied) step so its trailing
        // health probe sheds once the budget is spent -- the between-step pause
        // above then fires before the next step. stepsRemaining is built from the
        // raw list via _stripInternalClock, so the clock never leaks into an echo.
        if (spec?.__reqT0 != null) step.__reqT0 = spec.__reqT0
        if (spec?.__activePage == true) step.__activePage = true
        step.__pageCache = driveWalkCache
        def stepOp = step.operation?.toString()?.trim() ?: "introspect"
        // Inherit the page the previous step ended on when this step omits one.
        if (!step.page && currentPage) step.page = currentPage
        lastStepOperation = stepOp
        def r
        try {
            r = _rmWalkStep(appId, step)
        } catch (Exception stepExc) {
            // A step that THROWS at runtime (bad input mid-sequence, a hub error) would
            // otherwise unwind the whole drive and discard the per-step trace of the steps
            // that already ran. Capture the throw as a failed step so the partial-run record
            // + a health verdict survive, identical in quality to a success=false step. Record
            // the step's OWN page (step.page is set by the inherit line above), not currentPage
            // -- currentPage only advances on a SUCCESSFUL step, so it would mis-name the page
            // the failing op actually targeted.
            mcpLogError("rm-native", "walkStep drive: step ${idx} (${stepOp}) threw for app ${appId}", stepExc)
            def stepHealth = null
            try { stepHealth = _rmCheckRuleHealth(appId) } catch (Exception ignored) { /* best effort -- never mask the real error */ }
            stepResults << [
                step: idx,
                operation: stepOp,
                page: (step.page ?: currentPage),
                success: false,
                error: stepExc.message ?: stepExc.toString(),
                health: stepHealth
            ]
            allOk = false
            if (stopOnError) break
            else continue
        }
        if (r?.page) currentPage = r.page.toString()
        stepResults << [
            step: idx,
            operation: stepOp,
            page: r?.page,
            success: r?.success,
            diff: r?.diff,
            valueEcho: r?.valueEcho,
            silentRejection: r?.silentRejection,
            commitSignal: r?.commitSignal,
            opResult: r?.opResult,
            health: r?.health
        ]
        if (r?.error != null) stepResults[-1].error = r.error
        if (r?.success == false) {
            allOk = false
            if (stopOnError) break
        }
    }
    // The final probe is the drive's only health check for its mutating steps, whose per-step probes were
    // deferred. It is budget-aware: on a pause (or any budget-spent exit) it is skipped, because the pause exists
    // to beat the transport ceiling; a finished drive whose probe was skipped reports itself unverified below.
    def finalHealth = _rmWalkStepHealth(appId, spec?.__reqT0 as Long, spec?.__activePage == true ? currentPage : null)
    // Paused (budget reached between steps): return an in_progress envelope
    // and skip the fail-loud rollup below -- a pause is NOT an error. success:true holds
    // because the pause guard only trips when nothing has failed; every completed step is
    // committed and its per-step signals ride in steps[].
    if (pausedAtStep != null) {
        return [
            success: true,
            status: "in_progress",
            operation: "drive",
            page: currentPage,
            stepsRequested: steps.size(),
            stepsRun: stepResults.size(),
            pausedAtStep: pausedAtStep,
            stepsRemaining: stepsRemaining,
            lastStepOperation: lastStepOperation,
            steps: stepResults,
            health: finalHealth,
            resume: [
                note: "Time budget reached; all completed steps are committed. Re-issue walkStep operation='drive' with steps = stepsRemaining (page inheritance: set page = this result's page) to continue."
            ]
        ]
    }
    // An unreadable final probe (transient fetch failure, no evidence either way) does not fail the gate; positive
    // evidence of breakage does. A probe the budget skipped also passes the gate here, but a finished drive with
    // mutating steps is then reported healthUnverified below.
    boolean finalHealthGate = _rmHealthGatePass(finalHealth)
    def preExistingStructural = _rmPreExistingStructuralOnly(baselineStructural, finalHealth)
    if (!finalHealthGate && preExistingStructural) finalHealthGate = true
    def result = [
        success: allOk && finalHealthGate,
        operation: "drive",
        page: currentPage,
        stepsRequested: steps.size(),
        stepsRun: stepResults.size(),
        lastStepOperation: lastStepOperation,
        steps: stepResults,
        health: finalHealth
    ]
    // A terminal drive that leaves any structural issue it introduced (an unclosed block included) is incomplete;
    // issues already present before the drive (e.g. building inside an open IF across calls) are reported, not failed.
    if (!finalHealthGate && (finalHealth?.structuralIssues as List)) result.structuralIssues = finalHealth.structuralIssues
    def preExistingSubset = _rmPreExistingStructuralSubset(baselineStructural, finalHealth)
    if (preExistingSubset) result.preExistingStructuralIssues = preExistingSubset
    // Fail-loud rollup: a success:false drive must ALWAYS carry a top-level reason. A step
    // error caught per-step otherwise lives only in steps[].error -- a weak signal for an
    // LLM caller that sees success:false with no top-level `error`. Surface the first failed
    // step's error (and a repairHint naming it); if every step passed but the rule ended
    // unhealthy, surface the finalHealth gate's reason instead.
    def firstFailed = stepResults.find { it.success == false }
    if (firstFailed != null) {
        def failedCount = stepResults.count { it.success == false }
        // With stopOnError=false the drive runs past a failure, so "halted" would misreport it.
        def failurePrefix = stopOnError ?
            "drive halted at step ${firstFailed.step} (${firstFailed.operation}): " :
            "drive ran ${stepResults.size()} step(s) with ${failedCount} failed; first failure at step ${firstFailed.step} (${firstFailed.operation}): "
        result.error = "${failurePrefix}${firstFailed.error ?: 'step reported success:false -- inspect its valueEcho/silentRejection/health'}".toString()
        boolean recoveryExhausted = firstFailed.error?.toString()?.contains("Automatic recovery was already attempted") == true
        result.repairHints = (result.repairHints ?: []) + [(recoveryExhausted ?
            "Drive stopped at step ${firstFailed.step} after its automatic recovery was already attempted. Do not re-run that step: its write may already be committed. Inspect steps[${firstFailed.step - 1}] and hub_get_rule_health(appId=${appId}), and restore the pre-edit backup if one was taken (restoreHint) and the rule is damaged." :
            (stopOnError ?
                "Drive stopped at step ${firstFailed.step}. Inspect steps[${firstFailed.step - 1}] for the failure detail, correct it, and re-run the drive from that step." :
                "Step ${firstFailed.step} failed and the drive continued (stopOnError=false). Inspect each step with success:false before re-running any of them; later steps may have depended on it.")).toString()]
    } else if (!finalHealthGate) {
        result.error = "drive completed all ${stepResults.size()} step(s) but the rule is unhealthy: ${(finalHealth.issues ?: ['see health']).join('; ')}".toString()
        if (baselineUnavailable != null && (finalHealth?.structuralIssues as List)) {
            result.baselineUnavailable = baselineUnavailable
            result.repairHints = (result.repairHints ?: []) + ["The pre-drive structural baseline could not be read (${baselineUnavailable}), so issues that were already present, such as a block left open by an earlier call, could not be told apart from new ones. Compare with hub_get_rule_health(appId=${appId}); if every issue predates this drive, the steps still committed and need no re-run.".toString()]
        }
    } else if (finalHealth.unreadable == true) {
        result.repairHints = (result.repairHints ?: []) + ["The final health probe could not be read -- no evidence of breakage either way (a transient failure, or the rule may since have been removed); every step committed. Verify via hub_get_rule_health(${appId}).".toString()]
    }
    // Only a paused drive may defer the final check. A finished drive whose probe the time budget shed never
    // looked at the rule it changed, so it reports itself unverified instead of complete.
    if (allOk && finalHealth?.skipped == true && stepResults.any { it.operation in ["write", "click", "navigate", "done"] }) {
        result.success = false
        result.partial = true
        result.healthUnverified = true
        result.error = "drive ran all ${stepResults.size()} step(s) but its final health check was skipped because the time budget ran out, so the rule's state is unverified".toString()
        result.repairHints = (result.repairHints ?: []) + ["Every step committed, so do not re-run the drive. Check the rule with hub_get_rule_health(appId=${appId}) and repair anything it reports, such as an unclosed block, before treating the rule as complete.".toString()]
    }
    return result
}

// Commit a value-write as a FULL page-form submit to /installedapp/update/json,
// mirroring exactly what the Web UI sends when a submitOnChange input changes.
//
// A bare settings-write (settings[key] + sidecars only) stores the value but
// does NOT run the page's submitOnChange handler -- the handler only fires when
// the hub receives a complete form-action envelope (formAction=update +
// currentPage + pageBreadcrumbs + version + paramsForPage + appType fields) AND
// the full serialized page state. For inputs whose handler performs the actual
// mutation (e.g. RM's trashActs, which deletes the selected actions inside its
// submitOnChange render), the bare write strands the change. Replaying the
// complete envelope makes the handler run synchronously during the POST, so the
// mutation is committed by the time the POST returns.
//
// Wholesale-replace semantics: a form submit replaces the page's settings as a
// unit. Every input on the page schema is re-emitted, taking its value from
// currentSettings. Inputs present in currentSettings are preserved exactly;
// inputs absent from currentSettings are submitted empty (buttons silently,
// non-button inputs with a warn log AND their names returned in the response
// `blankedInputs` list so a caller can refuse a silently-blanked write).
// Because the submit is wholesale, any
// non-button input the caller wants preserved MUST appear in currentSettings --
// pass the COMPLETE configPage settings map for any page where untouched fields
// must survive, or the submit may blank them. extraSettings carries the inputs
// being changed; they override any same-named current value during the merge.
//
// @param cfg     the configPage response for pageName (carries app.version + app.label).
// @param schema  the collected input schema for the same page.
// @param currentSettings  the page's current input values (configPage `settings` map);
// must be COMPLETE for pages where untouched fields must survive.
// @param extraSettings    the inputs being written this submit (e.g. [trashActs: [...]]).
private Map _rmSubmitFullPageForm(Integer appId, String pageName, Map cfg, Map schema, Map currentSettings, Map extraSettings) {
    // Re-emit every current page input so the wholesale-replace submit does not
    // drop untouched fields, then overlay the inputs being changed. Inputs are
    // enumerated from the page schema; each takes its value from currentSettings.
    def fullMap = [:]
    def blankedInputs = []
    schema?.each { name, meta ->
        if (currentSettings?.containsKey(name)) {
            fullMap.put(name, currentSettings.get(name))
        } else if (meta?.type == 'button') {
            // Buttons carry no persisted value; the UI serializes them empty.
            fullMap.put(name, "")
        } else if (meta?.defaultValue != null) {
            // Nothing stored: the page renders -- and the UI submits -- the input's default.
            fullMap.put(name, meta.defaultValue)
        } else {
            // Non-button input absent from the page settings map. If it is also
            // not among the inputs being written this submit (extraSettings), it
            // would be silently blanked -- surface it so a future caller on a
            // page where preservation matters sees the gap. Inputs supplied via
            // extraSettings are being set deliberately (not blanked), so they do
            // not warn. (Harmless for the trashActs delete path either way.)
            if (!extraSettings?.containsKey(name)) {
                blankedInputs << name
                mcpLog("warn", "rm-native", "_rmSubmitFullPageForm: page input '${name}' (type=${meta?.type}) on ${pageName} for app ${appId} is absent from configPage settings -- submitting empty; if this field needed preserving the full-form submit may blank it")
            }
            fullMap.put(name, "")
        }
    }
    extraSettings?.each { k, v -> fullMap.put(k, v) }

    def body = _rmBuildSettingsBody(appId, fullMap, schema)

    // Form-action envelope -- the part that makes RM run the submitOnChange
    // handler during the page re-render instead of only persisting the value.
    body.formAction = "update"
    body.currentPage = pageName
    body.pageBreadcrumbs = _rmPageBreadcrumbs(appId, pageName, '["mainPage"]')
    // appTypeId / appTypeName are empty for Rule Machine (the native UI sends
    // them blank); emitted explicitly so the body matches the wire capture.
    body.appTypeId = ""
    body.appTypeName = ""
    def label = cfg?.app?.label
    body.paramsForPage = groovy.json.JsonOutput.toJson([label: (label != null ? label.toString() : "")])
    // version is RM's concurrent-edit token; replay the exact one the page render
    // would send. Missing it can make the hub reject the submit as a stale edit.
    def v = cfg?.app?.version
    if (v != null) body.version = v.toString()

    def resp = hubInternalPostForm("/installedapp/update/json", body)
    if (resp?.status != null && resp.status >= 400) {
        // Surface a truncated body preview so operators see WHY RM rejected the
        // submit (stale version token, auth, malformed envelope, etc.) instead
        // of just a bare status code.
        def bodyPreview = resp?.data?.toString()?.take(200)
        throw new IllegalStateException("Full-form submit on ${pageName} for app ${appId} failed: status=${resp.status}${bodyPreview ? "; body=" + bodyPreview : ""}. The outcome has not been verified. Recovery is best-effort; inspect the rule with hub_get_app_config(appId=${appId}) before retrying.")
    }
    // Surface any non-button inputs the wholesale-replace blanked (absent from
    // currentSettings AND not in extraSettings) so a caller can refuse a
    // silently-blanked write. Empty/absent on the trashActs delete path, where
    // nothing load-bearing is blanked.
    if (blankedInputs && resp instanceof Map) resp.blankedInputs = blankedInputs
    return resp
}

// Verify that a manifest handle still points to the same rule snapshot before reuse.
// File Manager files can be deleted outside MCP, so manifest age alone is not proof
// that the advertised rollback handle is usable.
private boolean _rmReusableBackupFileMatches(Map entry, Integer ruleId) {
    def fileName = entry?.fileName?.toString()
    if (!fileName) return false
    try {
        def bytes = null
        // One retry: a transient File Manager read hiccup must not be mistaken for a
        // deleted backup -- the discard path unlinks the manifest handle for good.
        for (int attempt = 0; attempt < 2; attempt++) {
            try { bytes = downloadHubFile(fileName) } catch (Exception readErr) {
                bytes = null
                if (attempt == 1) mcpLog("debug", "rm-native", "Backup file '${fileName}' could not be read on retry: ${readErr.message}")
            }
            if (bytes != null && bytes.length > 0) break
            if (attempt == 0) pauseExecution(300L)
        }
        if (bytes == null || bytes.length == 0) return false
        if (entry.sourceLength != null && (entry.sourceLength as Long) != bytes.length) return false
        def snapshot = new groovy.json.JsonSlurper().parseText(new String(bytes, "UTF-8"))
        if (!(snapshot instanceof Map)) return false
        def snapshotId = snapshot.appId != null ? snapshot.appId : snapshot.ruleId
        if (snapshotId?.toString() != ruleId?.toString()) return false
        if (snapshot.schemaVersion != 1) return false
        def appType = snapshot.appType?.toString() ?: "rule_machine"
        if (appType == "visual_rule") {
            if (snapshot.vrbFormat == "classic") return snapshot.vrbDefinition instanceof Map
            if (snapshot.vrbFormat == "graph" && snapshot.vrbRuleJson) {
                try {
                    return new groovy.json.JsonSlurper().parseText(snapshot.vrbRuleJson.toString()) instanceof Map
                } catch (Exception ignored) {
                    return false
                }
            }
            return false
        }
        // Classic restore replays configJson.settings. Requiring the explicit
        // Map distinguishes a legitimately empty rule from a truncated JSON
        // object that restore would otherwise treat as an empty successful replay.
        return snapshot.configJson instanceof Map && snapshot.configJson.settings instanceof Map
    } catch (Exception ignored) {
        return false
    }
}

// Reuse the newest same-rule edit baseline for one hour unless strict per-write
// backups are enabled. Deletes call _rmBackupRuleSnapshot directly and a standalone
// replaceRequiredExpression is exempted below, so both always get a fresh, pinned image.
// A fresh snapshot is taken after the lock is released, since its App Cloner export is slow.
// activePage: the page a wizard edit is on; its render is read instead of the default page, which
// some apps (HPM) use to reset a wizard's pending input.
// The toggles as the request read them (a detached write carries that view), else as stored;
// a form save keeps a toggle as text, so the text decides.
private boolean _rmPreEditBackupsOff() { _rmBackupToggle("backupBeforeRuleEdits") == "false" }
private boolean _rmStrictBackupsOn() { _rmBackupToggle("backupEveryRuleWrite") == "true" }
// The request's view is the answer whenever it carries the key, even as null (a toggle never saved).
private String _rmBackupToggle(String key) {
    (REQUEST_SETTINGS instanceof Map && (REQUEST_SETTINGS as Map).containsKey(key)) ? _requestSetting(key) : settings?.get(key)?.toString()
}

Map _rmBackupBeforeEdit(Integer ruleId, String reason, String activePage = null) {
    if (_rmPreEditBackupsOff()) {
        _memoDropPreEditStatus(ruleId)
        // No baseline, but the app must exist and its kind is still needed: one config read, of the
        // page a walk is on (some apps reset a wizard when their default page renders).
        def cfg = null
        try { cfg = activePage ? _rmFetchConfigJson(ruleId, activePage) : _rmFetchConfigJson(ruleId) } catch (Exception e) {
            if (e.message?.contains("404")) {
                throw new IllegalArgumentException("No rule/app with id ${ruleId} exists on this hub (configure/json returned 404). Nothing was changed. Use hub_list_rules for valid ids. See hub_get_tool_guide(section='set_rule_reference').")
            }
            throw new IllegalStateException("Rule/app ${ruleId} could not be read before the edit (${e.message ?: e}). Nothing was changed.".toString(), e)
        }
        _rmNoteRuleMachineConfig(ruleId, cfg)
        // A map, not null: null means the edit was refused before its backup decision.
        return [skipped: "pre-edit backups are off in Advanced settings"]
    }
    def reused = _withBackupLock("rule ${ruleId} baseline (${reason})") { _rmBackupBeforeEditLocked(ruleId, reason, activePage) }
    if (reused != null) {
        // The status read before this edit is current only up to its first backup decision.
        _memoDropPreEditStatus(ruleId)
        return reused
    }
    return activePage ? _rmBackupRuleSnapshot(ruleId, reason, activePage) : _rmBackupRuleSnapshot(ruleId, reason)
}

// The newest non-pending snapshot of ruleId taken within the last hour (a map entry), or null.
private def _rmRecentRuleBackup(Map mfst, Integer ruleId, long nowMs) {
    return mfst.findAll { key, value ->
        if (!(value instanceof Map) || value.deletePending || value.type?.toString() != "rm-rule") return false
        def savedRuleId = value.ruleId != null ? value.ruleId : value.id
        if (savedRuleId?.toString() != ruleId?.toString()) return false
        Long savedAt = null
        try { savedAt = value.timestamp as Long } catch (Exception ignored) { }
        if (savedAt == null) return false
        long age = nowMs - savedAt
        return age >= 0L && age < 60L * 60L * 1000L
    }.max { a, b -> (a.value.timestamp as Long) <=> (b.value.timestamp as Long) }
}

private Map _rmBackupBeforeEditLocked(Integer ruleId, String reason, String activePage = null) {
    if (_rmStrictBackupsOn() || reason == "pre-replaceRequiredExpression") return null

    def mfst = _itemBackupManifest()
    def recent = _rmRecentRuleBackup(mfst, ruleId, now())

    if (recent != null && !_rmReusableBackupFileMatches(recent.value as Map, ruleId)) {
        def staleFile = recent.value?.fileName?.toString()
        mcpLog("warn", "rm-native", "Recent backup ${recent.key} for rule ${ruleId} is missing or does not match its manifest; discarding the stale handle and taking a fresh baseline")
        try { unlinkItemBackupManifestFile(staleFile, recent.key?.toString()) }
        catch (Exception unlinkErr) { mcpLog("warn", "rm-native", "Could not unlink the stale backup handle ${recent.key} for rule ${ruleId}: ${unlinkErr.message}; a fresh baseline is taken but the stale entry remains in the manifest") }
        recent = null
    }

    if (recent != null) {
        // Repair only a validated handle: trimming to protect a handle that is then
        // discarded would evict one live rollback point for nothing.
        _repairItemBackupRetention(mfst, recent.key.toString(), recent.value as Map)
        def config = null
        try {
            config = activePage ? _rmFetchConfigJson(ruleId, activePage) : _rmFetchConfigJson(ruleId)
            if (activePage && _rmNoteRuleMachineConfig(ruleId, config)) config = _rmFetchConfigJson(ruleId)
        } catch (Exception e) {
            def vrbFallback = null
            try { vrbFallback = _vrbDetect(ruleId) } catch (Exception ignored) { }
            if (vrbFallback == null) {
                if (e.message?.contains("404")) {
                    throw new IllegalArgumentException("No rule/app with id ${ruleId} exists on this hub (configure/json returned 404). Nothing was changed. Use hub_list_rules for valid ids. See hub_get_tool_guide(section='set_rule_reference').")
                }
                // The persisted snapshot above is already identity-checked and is
                // sufficient for rollback. A transient configure/json failure only
                // makes the brokenBefore wording diagnostic unavailable; it must not
                // turn a valid edit into an invalid-params refusal.
                mcpLog("warn", "rm-native", "Could not read rule ${ruleId} before reusing backup '${recent.key}' (${e.message ?: e}); continuing without the internal brokenBefore diagnostic")
            }
        }
        mcpLog("info", "rm-native", "Reusing recent backup ${recent.key} for rule ${ruleId} (${reason}); restoring it reverts every edit since ${formatTimestamp(recent.value.timestamp as Long)}")
        return [backupKey: recent.key,
                brokenBefore: (config == null ? null : _rmConfigHasBrokenMarkers(config)),
                baselineReused: true,
                rollbackScope: "Restoring this baseline reverts every edit to rule ${ruleId} since ${formatTimestamp(recent.value.timestamp as Long)}."] +
            new LinkedHashMap(recent.value as Map) +
            [retention: (recent.value.retention ?: "pinned"), retentionNote: _itemBackupRetentionNote(recent.value as Map)]
    }

    return null
}

// Whether a configure/json envelope is an RM rule's (every page of an app carries its type);
// recorded for the execution, so a walk step can tell without a read of its own.
private boolean _rmNoteRuleMachineConfig(Integer ruleId, Map config) {
    boolean rm = (config?.app?.appType?.name?.toString() ?: "") ==~ /Rule-[0-9.]+/
    _memoNoteRuleMachine(ruleId, rm)
    return rm
}


// Snapshot the current state of an RM rule into the hub's File Manager
// as a single JSON file (configure/json + statusJson combined), recorded
// in the unified backup index alongside app/driver backups.
//
// Entries get type="rm-rule" so hub_list_backups + hub_restore_backup
// (the existing tools) handle them too — no separate RM-only backup
// tools. Backup key pattern: rm-rule_<ruleId>_<yyyyMMdd-HHmmss-SSS>[-<uuid>].
// The reads and the App Cloner export run outside the backup lock; only the file and manifest
// writes hold it.
Map _rmBackupRuleSnapshot(Integer ruleId, String reason, String activePage = null) {
    def built = _rmBuildRuleSnapshot(ruleId, reason, activePage)
    return _withBackupLock("rule ${ruleId} snapshot (${reason})") { _rmWriteRuleSnapshotLocked(ruleId, reason, built.snapshot as Map, built.config as Map) }
}

private Map _rmBuildRuleSnapshot(Integer ruleId, String reason, String activePage = null) {
    def config
    def status
    try {
        config = activePage ? _rmFetchConfigJson(ruleId, activePage) : _rmFetchConfigJson(ruleId)
        if (activePage && _rmNoteRuleMachineConfig(ruleId, config)) {
            config = _rmFetchConfigJson(ruleId)
            activePage = null
        }
    } catch (Exception e) {
        // Vue-JSON children (Visual Rules) may not serve configure/json. If the id speaks a
        // VRB serialization, the VRB-flavored snapshot below is still a full backup --
        // restore replays the captured definition, not classic settings.
        def vrbFallback = null
        try { vrbFallback = _vrbDetect(ruleId) } catch (Exception ignored) { }
        if (vrbFallback == null) {
            if (e.message?.contains("404")) {
                throw new IllegalArgumentException("No rule/app with id ${ruleId} exists on this hub (configure/json returned 404). Nothing was changed. Use hub_list_rules for valid ids. See hub_get_tool_guide(section='set_rule_reference').")
            }
            throw new IllegalArgumentException("Cannot back up rule ${ruleId}: configure/json failed -- ${e.message}")
        }
        config = null
    }
    try {
        // A status memoised for this execution before any write serves a pre-write snapshot.
        status = _memoTakePreEditStatus(ruleId) ?: _rmFetchStatusJson(ruleId)
    } catch (Exception e) {
        // Status failure is tolerable for a pre-write snapshot — the
        // config JSON alone is enough to restore. Record the failure in
        // the snapshot so post-mortem sees why status was absent.
        mcpLog("warn", "rm-native", "Backup for rule ${ruleId}: statusJson failed -- ${e.message}")
        status = [error: e.message ?: e.toString()]
    }

    // Detect appType from the config's appType.name so the restore path
    // can route to the right registry entry. RM 5.1 = "rule_machine";
    // future appTypes get reverse-mapped from the registry.
    def detectedAppType = config == null ? "visual_rule" : "rule_machine"
    def configAppName = config?.app?.appType?.name
    if (configAppName) {
        // The hub reports Visual Rule children VERSIONED ("Visual Rule Builder 1.0" / "... 2.0")
        // while the registry holds the bare family name, so an exact-only lookup silently
        // classified every Visual Rule as rule_machine -- the snapshot then skipped the definition
        // capture and a restore replayed it as an RM rule. Match the name with a trailing version
        // stripped as well; requiring the suffix to LOOK like a version keeps "Visual Rule
        // Builder" from swallowing the "Visual Rules Builder" parent or any unrelated app.
        def name = configAppName.toString()
        def versioned = (name =~ /^(.+) [0-9]+(?:\.[0-9]+)*$/)
        def bare = versioned.matches() ? (versioned[0] as List)[1].toString() : null
        // Exact match outranks the stripped one, and the first hit wins -- a `.each` that kept
        // assigning would let the last registry entry override an earlier exact match.
        def exactKey = _appTypeRegistry().find { typeKey, reg -> reg.appName == name }?.key
        def bareKey = (exactKey == null && bare != null) ? _appTypeRegistry().find { typeKey, reg -> reg.appName == bare }?.key : null
        detectedAppType = exactKey ?: bareKey ?: detectedAppType
    }
    // The hub's appType record carries the app type's OAuth client credentials and passwords
    // (populated for Rule-5.1); restore never reads them, so they stay out of File Manager.
    if (config?.app?.appType instanceof Map) {
        ["oauthClientId", "oauthClientSecret", "encryptedPassword", "sourcePassword"].each { config.app.appType.remove(it) }
    }

    def snapshot = [
        schemaVersion: 1,
        ruleId: ruleId,           // legacy field; new snapshots also carry appId
        appId: ruleId,
        appType: detectedAppType,
        reason: reason ?: "pre-write",
        timestamp: now(),
        timestampIso: formatTimestamp(now()),
        appLabel: stripAppConfigHtml(config?.app?.trueLabel ?: config?.app?.label),
        configJson: config,
        statusJson: status
    ]

    // Visual Rules keep their definition in app state behind the ruleBuilder endpoints, not
    // in classic settings -- capture it so _vrbRestoreFromSnapshot can replay it. A null
    // detect (unreadable / never-saved shell) is recorded as a husk; restore then fails
    // with an actionable error instead of silently recreating an empty rule.
    if (detectedAppType == "visual_rule") {
        def vrb = null
        try {
            vrb = _vrbDetect(ruleId)
            if (vrb == null) {
                mcpLog("warn", "vrb", "Backup for Visual Rule ${ruleId}: no readable definition (never-saved shell?) -- snapshot is a husk; restore will refuse it")
            }
        } catch (Exception e) {
            mcpLog("warn", "vrb", "Backup for Visual Rule ${ruleId}: definition capture failed -- ${e.message}")
        }
        if (vrb != null) {
            snapshot.vrbFormat = vrb.format
            snapshot.vrbRulePaused = vrb.data.rulePaused == true
            // Prefer the rule's OWN name over the installed-app label: the hub decorates a
            // paused rule's label with " (Paused)" (live-verified), and a restore that
            // replays the decorated label bakes the suffix into the real rule name.
            if (vrb.data.name?.toString()?.trim()) snapshot.appLabel = vrb.data.name
            if (vrb.format == "classic") {
                snapshot.vrbDefinition = [whenNodes: vrb.data.whenNodes ?: [],
                                          thenNodes: vrb.data.thenNodes ?: [],
                                          elseNodes: vrb.data.elseNodes ?: []]
            } else {
                // Capture the document the read RESOLVED (the hub's own loader prefers the parsed
                // graphDocument), not the raw bytes: a blank or {"nodes":[]} ruleJson beside a
                // populated graphDocument would snapshot as unreadable or as an empty graph, and a
                // restore would then replay the empty graph and verify zero against zero. The key
                // keeps its name so existing backups still restore.
                def doc = vrb.data.definition
                // A rollback handle the reader ALREADY KNOWS is unreadable is worse than none: the
                // file uploads, the manifest registers it, the delete envelope advertises it, and
                // the restore that follows the delete answers "not parseable JSON" with the rule
                // already gone. definitionParseError is exactly that knowledge, so refuse here --
                // the caller gates its destructive op on this backup, and refusing the backup
                // refuses the delete.
                if (doc == null && vrb.data.definitionParseError) {
                    throw new IllegalArgumentException(
                            "Cannot back up Visual Rule ${ruleId}: its stored graph document is unreadable " +
                            "(${vrb.data.definitionParseError}), so no restorable snapshot can be taken and " +
                            "nothing was written. Inspect it with hub_get_visual_rule(appId=${ruleId}); a rule " +
                            "whose document cannot be read can only be re-authored, not restored.")
                }
                snapshot.vrbRuleJson = (doc instanceof Map && doc.nodes instanceof List && !doc.nodes.isEmpty()) ?
                        groovy.json.JsonOutput.toJson(doc) : vrb.data.ruleJson?.toString()
                // An empty shell (no nodes, no raw bytes) is a legitimate thing to delete, so this
                // is a marker rather than a refusal -- it lets restore say the rule HAD no graph
                // instead of blaming the snapshot's age.
                if (!snapshot.vrbRuleJson) {
                    snapshot.vrbGraphEmpty = true
                    mcpLog("warn", "vrb", "Backup for Visual Rule ${ruleId}: the rule has no stored graph document, so the snapshot carries no definition to restore")
                }
            }
        }
    }

    // An app the registry does not know also lands on rule_machine above, so the export is gated on
    // the hub's own Rule Machine type name.
    if (detectedAppType == "rule_machine" && config != null && (configAppName?.toString() ==~ /Rule-[0-9.]+/)) {
        // Rule Machine only (restore imports it as a rule). The cloner renders no export for a rule with a
        // committed Required Expression (live, fw 2.5.2.129).
        def evalState = (status?.appState instanceof List) ? (status.appState as List).find { it instanceof Map && it.name == "eval" }?.value : null
        if (evalState instanceof Map && (evalState as Map)["0"] instanceof List && !((evalState as Map)["0"] as List).isEmpty()) {
            snapshot.nativeExportSkipped = "the rule has a Required Expression, which Hubitat's App Cloner does not export"
        } else {
            def nat = _rmNativeExportForBackup(ruleId)
            if (nat?.json) snapshot.nativeExport = nat.json
            else if (nat?.error || nat?.skipped) snapshot.nativeExportSkipped = (nat.error ?: nat.skipped).toString()
        }
    }

    return [snapshot: snapshot, config: config]
}

private Map _rmWriteRuleSnapshotLocked(Integer ruleId, String reason, Map snapshot, Map config) {
    // Under strict per-write backups an edit inside an editing session is a rolling step. The
    // session's first snapshot, and a delete's or Required Expression replacement's recovery
    // point, stay pinned. Decided under the lock, against the committed index.
    boolean editStep = _rmStrictBackupsOn() &&
        !(reason in ["pre-delete", "pre-forcedelete", "pre-replaceRequiredExpression"])
    String retention = editStep && _rmRecentRuleBackup(_itemBackupManifest(), ruleId, now()) != null ? "rolling" : "pinned"
    def ts = new Date(now()).format("yyyyMMdd-HHmmss-SSS")
    String namePrefix = "mcp-rm-backup-${ruleId}-"
    String nameExt = ".json"
    def fileName = _itemBackupFileName(namePrefix + ts + nameExt)

    def jsonBytes
    try {
        jsonBytes = groovy.json.JsonOutput.toJson(snapshot).getBytes("UTF-8")
    } catch (Exception e) {
        throw new IllegalArgumentException("Cannot serialize backup for rule ${ruleId}: ${e.message}")
    }
    try {
        uploadHubFile(fileName, jsonBytes)
    } catch (Exception e) {
        throw new IllegalArgumentException("Cannot save backup file '${fileName}' for rule ${ruleId}: ${e.message}")
    }

    def suffix = fileName.substring(namePrefix.length(), fileName.length() - nameExt.length())
    String backupKey = "rm-rule_${ruleId}_${suffix}"
    def entry = [
        type: "rm-rule",
        id: ruleId,
        ruleId: ruleId,
        fileName: fileName,
        reason: snapshot.reason,
        appLabel: snapshot.appLabel,
        timestamp: snapshot.timestamp,
        sourceLength: jsonBytes.length,  // reusing the existing field name for byte size
        sizeBytes: jsonBytes.length,
        retention: retention
    ]
    _publishUploadedItemBackup(backupKey, entry)

    mcpLog("info", "rm-native", "Backed up rule ${ruleId} (${reason}) to ${fileName} (${jsonBytes.length} bytes)")
    // brokenBefore: the rule's pre-write broken state, derived from the config this snapshot
    // already fetched (no extra hub read). A mutation that needs to know whether IT caused a
    // post-write break (vs the rule being broken beforehand) reads this off the returned backup to
    // steer its error wording, then STRIPS it -- it is a transient internal signal, never surfaced
    // to the caller and deliberately kept off the persisted manifest `entry` (not part of the
    // durable backup record). null when no config was readable (VRB-only snapshot).
    return [backupKey: backupKey,
            brokenBefore: (config == null ? null : _rmConfigHasBrokenMarkers(config)),
            baselineReused: false,
            rollbackScope: "Restoring this baseline returns rule ${ruleId} to its state at ${formatTimestamp(snapshot.timestamp as Long)}."] +
        entry + [retentionNote: _itemBackupRetentionNote(entry)]
}

// Soft delete via /installedapp/delete/<id>. Refuses if the app has
// child devices or child apps (hub-side safety). Returns the hub's JSON
// response verbatim so callers can surface the reason on refusal.
private Map _rmSoftDeleteApp(Integer appId) {
    def responseText = hubInternalGet("/installedapp/delete/${appId}")
    if (!responseText) {
        throw new IllegalArgumentException("Empty response from soft-delete on app ${appId}")
    }
    def parsed
    try {
        parsed = new groovy.json.JsonSlurper().parseText(responseText)
    } catch (Exception e) {
        // Hub sometimes returns redirect HTML instead of JSON on soft-
        // delete success. Treat non-parseable body as success with a
        // note so callers don't see a misleading failure.
        return [success: true, raw: responseText.take(200)]
    }
    return parsed
}

// -------------------- Native RM tools (MCP-exposed) --------------------

// hub_set_rule (RM upsert) and hub_set_native_app (generic upsert) are thin
// create-or-edit dispatchers over the shared backend:
// - no appId -> _createNativeAppShell (createchild + name-set; + RM trigger/
// action bundle for set_rule)
// - appId    -> _applyNativeAppEdit (settings/button + the RM wizard engine)
// Both share the same private helpers; only the schema (FAT RM vs LEAN generic),
// the create appType, and the gateway placement differ.

// ---- hub_set_rule flat self-gateway ----
// In flat mode (useGateways=false) hub_set_rule's 25-param fat inputSchema is the
// single biggest always-on consumer of the 124KB tools/list budget. There it is
// folded to a thin {operation, appId, args, confirm} selector: the agent calls
// once with args OMITTED to get an operation's real schema (no mutation), then
// again with args filled. Gateway mode keeps the fat schema (already lazily
// disclosed by its gateway). The same toolSetRule handler serves BOTH shapes via
// the normalizer at its top, so nothing downstream changes.

// Single source of truth for the operation set: the flat enum, the create-arm gate,
// and the schema-only classifier all read these (a Spock test asserts the enum ==
// these, so the thin schema can't drift from what the handler accepts).
def _setRuleCreateHonored() { ['addTrigger', 'addTriggers', 'addAction', 'addActions', 'addRequiredExpression'] }
def _setRuleEditOnly() { ['replaceRequiredExpression', 'addLocalVariable', 'removeLocalVariable', 'patches', 'replaceActions', 'removeAction', 'clearActions', 'moveAction', 'removeTrigger', 'modifyTrigger', 'modifyAction', 'walkStep', 'settings', 'button'] }
def _setRuleOperations() { (['create'] + _setRuleCreateHonored() + _setRuleEditOnly() + ['buttonRule', 'guide', 'discover']).unique() }

// The thin flat-mode tool (description + inputSchema), substituted for hub_set_rule
// in getToolDefinitions()'s useGateways==false branch.
def _setRuleFlatTool() {
    return [
        description: """Create or edit a Hubitat Rule Machine rule (RM 5.1) — one self-describing tool. Set `operation` and call WITHOUT confirm to get that operation's argument schema back (no change is made); then call again with `args` filled and confirm:true to apply it. operation='create' makes a new rule (omit appId; put name + any bundled triggers/actions/required-expression in args); every other operation EDITs an existing rule (provide appId). RM-only — for non-RM classic apps (Room Lighting, Button Controller, Notifier, Groups+Scenes, Visual Rule) use hub_set_native_app. Any write needs the Write master + confirm=true + a recent backup. Keywords: create edit rule machine RM trigger action condition required expression local variable walkStep authoring automation.""",
        inputSchema: [
            type: "object",
            properties: [
                operation: [type: "string", enum: _setRuleOperations(), description: "What to do. Call WITHOUT confirm to get this operation's argument schema back (argsSchema + usage; no mutation), then call again with args + confirm:true. 'create' = new rule (omit appId); all others edit an existing rule (need appId) except guide/discover/buttonRule, which omit it. A create reports partial bakes in partial/repairHints (hub_get_tool_guide section 'set_rule_create_reference'). 'guide' returns the reference; 'discover' returns Rule Machine's capability list and fields (args={kind:'trigger'|'condition'|'action', capability?, action?})."],
                appId: [type: "integer", description: "RM rule ID. OMIT for create/guide/discover/buttonRule; PROVIDE for every other (edit) operation (appId is the create-vs-edit switch)."],
                args: [type: ["object", "array", "boolean"], description: "Arguments for the chosen operation — the exact shape comes from the schema probe (call without confirm first). Most ops take an object; the list ops (addTriggers/addActions/replaceActions/patches) take a bare array and clearActions takes true. For 'create' args holds name + optional addTriggers/addActions/addRequiredExpression. Pass the bare operation payload (e.g. {capability:'switch',...}), not wrapped under the operation name."],
                confirm: [type: "boolean", description: "Set true to APPLY the operation (any write). Omit (or false) to get the schema back instead (a no-mutation probe)."]
            ],
            required: ["operation"]
        ]
    ]
}

// True when a hub_set_rule call only RETURNS schema/reference and mutates nothing
// (legacy guide:true / addTrigger|addAction discover, or the self-gateway guide/
// discover op, or a probe = any operation call without confirm:true). Lets these
// through the Write master when writes are disabled, and bypasses the gateway
// required-param pre-check. confirm:true (an actual write) is NEVER schema-only.
def _isSetRuleSchemaOnlyCall(args) {
    if (!(args instanceof Map)) return false
    // Envelope (self-gateway) form decides FIRST and mirrors _setRuleFromEnvelope exactly:
    // a guide/discover op or any call without confirm:true is schema-only; a confirm:true
    // envelope EXECUTES and is never schema-only -- a stray top-level guide/discover flag
    // must NOT mask it, or a write could slip past the Write master when writes are off.
    if (args.operation != null) {
        def op = args.operation.toString()
        return (op == 'guide' || op == 'discover' || args.confirm != true)
    }
    // Legacy (no-operation) form: toolSetRule dispatches buttonRule to
    // _createButtonRuleViaController (a REAL write) BEFORE the meta-call shortcuts,
    // and that flow's exclusivity gate does not reject a stray guide flag -- so a
    // guide/discover flag riding a buttonRule call must never read as schema-only
    // (it would bypass the Write master and actually create the button rule).
    if (args.buttonRule != null) return false
    // Remaining meta flags short-circuit inside the handler with NO mutation (the
    // discover/guide early-returns), so they are genuinely schema-only.
    if (args.guide == true) return true
    if (args.addTrigger instanceof Map && _rmIsDiscoverSpec(args.addTrigger)) return true
    if (args.addAction instanceof Map && _rmIsDiscoverSpec(args.addAction)) return true
    return false
}

// hub_set_native_app shares _applyNativeAppEdit's guide/discover short-circuits (static
// schema returns, no mutation, before requireDestructiveConfirm), so those meta-calls are
// schema-only exactly like hub_set_rule's legacy form. Consumed by the same three gates
// that exempt hub_set_rule (handleGateway's required-param pre-check, the Write master,
// the mandatory-BPS gate). UNLIKE toolSetRule (which guards its create arm with an
// isMetaCall check, so a meta flag WITHOUT appId still falls through to the shared edit
// engine and short-circuits there), toolSetNativeApp routes buttonRule and no-appId
// calls to WRITE paths first -- so a call is schema-only ONLY when it is edit-shaped
// (appId present, no buttonRule) AND carries a meta flag. A guide flag on a
// create-shaped call must NOT bypass any gate: the create arm would really execute.
def _isNativeAppSchemaOnlyCall(args) {
    if (!(args instanceof Map)) return false
    if (args.appId == null || args.buttonRule != null) return false
    if (args.guide == true) return true
    if (args.addTrigger instanceof Map && _rmIsDiscoverSpec(args.addTrigger)) return true
    if (args.addAction instanceof Map && _rmIsDiscoverSpec(args.addAction)) return true
    return false
}

// An action spec's capability matched to the catalog's spelling regardless of case ('Switch' ->
// 'switch'), written back to the spec so every later reader sees one spelling. An unknown value
// is left as given for the mapper to refuse with the list.
String _rmCanonicalActionCapability(Map actionSpec) {
    String raw = actionSpec?.capability?.toString()?.trim()
    if (!raw) return raw
    String hit = _rmActionCatalog().keySet().find { it.toString().equalsIgnoreCase(raw) }?.toString()
    if (hit != null && hit != raw) actionSpec.capability = hit
    return hit ?: raw
}

// discover is true or a selector Map ({capability, action} or {kind, capability}).
boolean _rmIsDiscoverSpec(spec) {
    spec instanceof Map && (spec.discover == true || spec.discover instanceof Map)
}

// Re-key a flat self-gateway envelope {operation, appId?, args?, confirm?} into the
// canonical handler shape, OR (for a probe) return the operation's schema slice.
// PROBE vs EXECUTE is decided by confirm (a no-payload op like clearActions has no
// args to gate on, so confirm is the unambiguous "apply it" signal).
Map _setRuleFromEnvelope(Map env) {
    String op = env.operation?.toString()?.trim()
    if (!_setRuleOperations().contains(op)) {
        throw new IllegalArgumentException("hub_set_rule: unknown operation '${op}'. Valid: ${_setRuleOperations().sort().join(', ')}.")
    }
    def payload = env.args
    if (payload instanceof String) {
        // Some MCP clients stringify nested objects. Parse ONLY a string clearly intended as
        // JSON (starts with { or [); on failure throw a clear error rather than swallowing it
        // into a misleading downstream "nothing provided". A bare scalar string (e.g. a button
        // name like 'updateRule') is left as-is for the scalar ops that take one.
        def s = (payload as String).trim()
        if (s.startsWith('{') || s.startsWith('[')) {
            try {
                payload = new groovy.json.JsonSlurper().parseText(s)
            } catch (Exception pe) {
                throw new IllegalArgumentException("hub_set_rule operation='${op}': args looks like JSON but is not valid JSON (${pe.message}). Pass args as an object, not a malformed JSON string.")
            }
        }
    }
    // guide/discover are always schema/meta (no mutation), regardless of confirm.
    if (op == 'guide') return [args: [guide: true]]
    if (op == 'discover') {
        // kind (or operation) names what to describe: action, trigger or condition; capability narrows it.
        def kindRaw = (payload instanceof Map) ? (payload.kind ?: payload.operation) : null
        def kind = kindRaw != null ? kindRaw.toString().toLowerCase().replaceFirst(/^add/, '') : 'action'
        if (!(kind in ['trigger', 'action', 'condition'])) {
            throw new IllegalArgumentException("hub_set_rule operation='discover': args.kind must be 'action', 'trigger' or 'condition' (got '${kindRaw}').")
        }
        def cap = (payload instanceof Map) ? payload.capability : null
        if (kind == 'action') return [args: [addAction: [discover: cap != null ? [capability: cap, action: payload.action] : true]]]
        return [args: [addTrigger: [discover: (cap != null || kind == 'condition') ? [kind: kind, capability: cap] : true]]]
    }
    // No confirm -> probe (return the op's arg schema, no mutation).
    if (env.confirm != true) {
        return [_schema: _setRuleOperationSchema(op)]
    }
    // EXECUTE (confirm == true): re-key into the legacy shape.
    if (op == 'create') {
        // create makes a NEW rule: it must OMIT appId (a stray appId would otherwise route to
        // an EDIT of that rule), and only the create-honored bundle keys are valid -- an
        // edit-only key must reject LOUDLY here, not be silently dropped before the create arm sees it.
        if (env.appId != null) {
            throw new IllegalArgumentException("hub_set_rule operation='create' must OMIT appId (it makes a NEW rule). To edit rule ${env.appId}, call the matching edit operation with that appId.")
        }
        if (!(payload instanceof Map)) {
            throw new IllegalArgumentException("hub_set_rule operation='create': args must be an object of {name, addTriggers, addActions, addRequiredExpression, ...}.")
        }
        def allowed = (['name'] + _setRuleCreateHonored())
        def extraneous = (payload as Map).keySet().findAll { !allowed.contains(it) }
        if (extraneous) {
            throw new IllegalArgumentException("hub_set_rule operation='create' accepts only ${allowed.join(', ')} in args; ${extraneous.sort().join(', ')} require an existing rule -- create first, then call that operation with the returned appId.")
        }
        def legacyCreate = [confirm: true]
        allowed.each { k -> if ((payload as Map).containsKey(k)) legacyCreate.put(k, payload.get(k)) }
        return [args: legacyCreate]
    }
    def legacy = [:]
    if (env.appId != null) legacy.appId = env.appId
    legacy.confirm = true
    if (op == 'buttonRule') {
        legacy.buttonRule = payload
    } else if (op == 'clearActions') {
        legacy.clearActions = true   // boolean op: presence == intent (takes no payload)
    } else {
        // Single edit op. Tolerant: accept the bare value OR an accidental {op: value} wrap --
        // EXCEPT 'settings', whose payload is a raw {inputName: value} map that may legitimately
        // contain a single input literally named 'settings'; unwrapping there would misread it.
        def val = payload
        if (op != 'settings' && val instanceof Map && val.size() == 1 && val.containsKey(op)) val = val.get(op)
        // List-shaped ops take a BARE array. For the spec-list ops a caller who wrapped it under
        // any single key (e.g. {actions:[...]}) is unwrapped here. NOT for patches: its items are
        // {op: spec} maps, so a single-key map like {addActions:[...]} is a malformed payload, not
        // a wrapper -- it must hit the bare-array error, not be silently unwrapped into the inner
        // list (the {patches:[...]} op-name wrap is already handled above). A payload that still
        // isn't a List gets a targeted error instead of the generic "nothing provided".
        if (op in ['addTriggers', 'addActions', 'replaceActions', 'patches']) {
            if (op != 'patches' && val instanceof Map && val.size() == 1 && val.values()[0] instanceof List) val = val.values()[0]
            if (!(val instanceof List)) {
                def got = (val == null) ? 'nothing' : (val instanceof Map ? 'an object' : 'a non-array value')
                throw new IllegalArgumentException("hub_set_rule operation='${op}': args must be a bare array, e.g. args:[{...},...] (got ${got}). Call without confirm to see the schema.")
            }
        }
        legacy.put(op, val)
    }
    return [args: legacy]
}

// Pull the canonical per-operation argument schema from the FAT hub_set_rule def
// (the same def gateway mode serves) so the probe can't drift from what the handler
// accepts. Returns the BARE shape args must match (NOT wrapped under the op name).
// Markers stripped + deep-copied via JSON (never mutate the shared def).
Map _setRuleOperationSchema(String op) {
    def fat = getAllToolDefinitions().find { it.name == 'hub_set_rule' }
    def props = (fat?.inputSchema?.properties ?: [:]) as Map
    def argsSchema
    if (op == 'create') {
        argsSchema = [:]
        (['name'] + _setRuleCreateHonored()).each { k -> if (props.get(k) != null) argsSchema.put(k, props.get(k)) }
    } else {
        argsSchema = props.get(op)   // the bare value/shape args must match
    }
    def clean = new groovy.json.JsonSlurper().parseText(stripFlatTrim(groovy.json.JsonOutput.toJson(argsSchema ?: [:]), false))
    def usage
    if (op == 'create') {
        usage = "To apply: call hub_set_rule again with operation='create', omit appId, confirm:true, and args = {name + any addTriggers/addActions/addRequiredExpression matching argsSchema}."
    } else if (op == 'clearActions') {
        usage = "To apply: call hub_set_rule again with operation='clearActions', appId=<ruleId>, confirm:true (no args needed)."
    } else if (op == 'buttonRule') {
        usage = "To apply: call hub_set_rule again with operation='buttonRule', omit appId, confirm:true, and args = {controllerId, buttonNumber, event} matching argsSchema (the bare object — do NOT wrap it under 'buttonRule')."
    } else {
        usage = "To apply: call hub_set_rule again with operation='${op}', appId=<ruleId>, confirm:true, and args = a value matching argsSchema (the bare object/value — do NOT wrap it under '${op}')."
    }
    return [operation: op, argsSchema: clean, usage: usage, note: "Schema probe only — no rule was changed. Add confirm:true (and args) to apply."]
}

def toolSetRule(args) {
    // Flat self-gateway envelope {operation,...}: re-key to the canonical shape so
    // the rest of this handler and gateway mode stay one code path. A probe (op +
    // empty args) returns that op's schema with NO mutation and returns early.
    if (args instanceof Map && args.operation != null) {
        // _setRuleFromEnvelope rebuilds args from scratch (dropping unknown keys); carry the
        // request-entry clock across so the time-budget machinery (drive/patches pauses,
        // bulk pauses, and every walkStep op's health shed) still sees it
        // for envelope-form calls (the direct canonical form keeps it via straight pass-through).
        def reqT0 = args.__reqT0
        def rekeyed = _setRuleFromEnvelope(args)
        if (rekeyed.containsKey('_schema')) return rekeyed._schema
        args = rekeyed.args
        if (reqT0 != null && args instanceof Map) args.__reqT0 = reqT0
    }
    // Button Controllers are an RM-family classic app, and their grandchild
    // Button Rules are RM-wire-format -- so creating a Button Rule is exposed
    // here too (same handler as hub_set_native_app's buttonRule; routes through
    // the parent controller's add-button flow). See _createButtonRuleViaController.
    if (args instanceof Map && args.buttonRule != null) {
        return _createButtonRuleViaController(args)
    }
    // discover/guide are RM static-schema meta-calls handled inside
    // _applyNativeAppEdit before any gate or appId check — route them there.
    boolean isMetaCall = (args?.addTrigger instanceof Map && _rmIsDiscoverSpec(args.addTrigger)) ||
                         (args?.addAction instanceof Map && _rmIsDiscoverSpec(args.addAction)) ||
                         args?.guide == true
    if (args?.appId == null && !isMetaCall) {
        // CREATE an RM rule, optionally with bundled addTriggers/addActions/addTrigger/addAction/
        // addRequiredExpression. Any other edit shortcut or raw settings/button on a new rule is refused,
        // never silently dropped.
        def CREATE_HONORED = _setRuleCreateHonored() as Set
        def EDIT_ONLY = _setRuleEditOnly()
        def droppedOnCreate = EDIT_ONLY.findAll { args instanceof Map && args.containsKey(it) }
        if (droppedOnCreate) {
            throw new IllegalArgumentException("hub_set_rule CREATE (no appId) only bundles ${CREATE_HONORED.sort().join(', ')}; ${droppedOnCreate.join(', ')} ${droppedOnCreate.size() == 1 ? 'is an' : 'are'} edit-only operation${droppedOnCreate.size() == 1 ? '' : 's'} that require${droppedOnCreate.size() == 1 ? 's' : ''} an existing rule. Create the rule first (with name + any bundled triggers/actions/addRequiredExpression), then re-call hub_set_rule with the returned appId to apply ${droppedOnCreate.join(', ')}.")
        }
        // Type-check the honored keys too. A malformed one (e.g. addRequiredExpression
        // as a List/String instead of a Map) slips past the instanceof guards below
        // and silently creates an empty shell -- the exact silent-success this gate
        // prevents, just for the wrong type.
        if (args instanceof Map) {
            ['addTrigger', 'addAction', 'addRequiredExpression'].each { k ->
                if (args.containsKey(k) && args[k] != null && !(args[k] instanceof Map)) {
                    throw new IllegalArgumentException("hub_set_rule create: '${k}' must be an object (a single spec, e.g. {capability: ...}); it was a non-object that would be silently dropped. Use the plural '${k}s' for a list of specs.")
                }
            }
            ['addTriggers', 'addActions'].each { k ->
                if (args.containsKey(k) && args[k] != null && !(args[k] instanceof List)) {
                    throw new IllegalArgumentException("hub_set_rule create: '${k}' must be an array of spec objects; it was a non-array that would be silently dropped. Use the singular '${k.replaceAll(/s$/, '')}' for a single spec.")
                }
            }
        }
        def createArgs = [appType: "rule_machine", name: args?.name, confirm: args?.confirm] as LinkedHashMap
        // The gateway stamps the request clock on the TOP-LEVEL args only; the bundled
        // triggers/actions below budget-check against it, so it rides along.
        if (args?.__reqT0 != null) createArgs.__reqT0 = args.__reqT0
        def trigs = []
        if (args?.addTriggers instanceof List) trigs.addAll(args.addTriggers)
        if (args?.addTrigger instanceof Map) trigs << args.addTrigger
        def acts = []
        if (args?.addActions instanceof List) acts.addAll(args.addActions)
        if (args?.addAction instanceof Map) acts << args.addAction
        // Refuse a bundled item whose arguments fail before the rule is created, so no empty rule is left
        // behind. Checks needing the live editor still stop the create.
        try {
            _rmPrevalidateTriggerSpecList(trigs, "triggers")
            _rmPrevalidateActionSpecList(acts, "actions", _rmSpecListTargetsRule(acts) ? _rmValidRuleIds() : null)
        } catch (IllegalArgumentException refusal) {
            throw new IllegalArgumentException("hub_set_rule create refused: ${refusal.message} No rule was created.".toString(), refusal)
        }
        if (trigs) createArgs.triggers = trigs
        if (acts) createArgs.actions = acts
        if (args?.addRequiredExpression instanceof Map) createArgs.requiredExpression = args.addRequiredExpression
        return _rmStampOutcome(_createNativeAppShell(createArgs))
    }
    // RM's update after an edit restarts a stopped rule and sets its private boolean to true; say so.
    Integer editId = null
    // Only edits that fire RM's update can restart a stopped rule.
    boolean finalizes = args instanceof Map && (args as Map).keySet().any { it in ["addTrigger", "addTriggers", "removeTrigger", "modifyTrigger",
        "addRequiredExpression", "replaceRequiredExpression", "addLocalVariable", "removeLocalVariable", "settings", "button", "patches", "walkStep",
        "addActions", "replaceActions", "clearActions", "removeAction", "moveAction"] }
    try { editId = (finalizes && args?.appId != null && args?.confirm == true) ? (args.appId as Integer) : null } catch (Exception ignored) { }
    Boolean wasStopped = null
    boolean stoppedUnread = false
    if (editId != null) {
        try {
            def preStatus = _rmFetchStatusJson(editId)
            wasStopped = _readAppStateBoolean(preStatus, "stopped", null)
            // The snapshot taken before the edit's first write reads the same page; it takes this one.
            _memoKeepPreEditStatus(editId, preStatus)
        } catch (Exception readExc) {
            stoppedUnread = true
            mcpLog("warn", "rm-native", "hub_set_rule: the stopped state of app ${editId} could not be read before the edit (${readExc.message ?: readExc})")
        }
    }
    // hub_set_rule edits only RM rules, which the engine then never has to look up.
    def out = _rmStampOutcome(_applyNativeAppEdit(args instanceof Map ? (new LinkedHashMap(args) + [__rmRule: true]) : args))
    if (wasStopped == true && out instanceof Map) {
        Boolean stillStopped = null
        try { stillStopped = _readAppStateBoolean(_rmFetchStatusJson(editId), "stopped", null) } catch (Exception readExc) {
            stoppedUnread = true
            mcpLog("warn", "rm-native", "hub_set_rule: the stopped state of app ${editId} could not be read after the edit (${readExc.message ?: readExc})")
        }
        if (stillStopped == false) {
            out.restartedStoppedRule = true
            out.note = ((out.note ? out.note.toString() + " " : "") + "The rule was stopped; Rule Machine's update restarted it and set its private boolean to true.").toString()
        }
    }
    // A call that applied nothing ran no update, so there is no restart to report.
    if (stoppedUnread && out instanceof Map && out.applied != false) {
        out.stoppedStateUnverified = true
        out.note = ((out.note ? out.note.toString() + " " : "") + (wasStopped == true
            ? "The rule was stopped before this edit and its state could not be read afterwards; Rule Machine's update may have restarted it (check hub_get_rule_health)."
            : "The rule's stopped state could not be read around this edit; if it was stopped, Rule Machine's update may have restarted it (check hub_get_rule_health).")).toString()
    }
    return out
}

// Every write response says the same three things: applied (something was written and stays),
// activated (the rule runs with it: its re-initialization was not skipped or rejected) and
// verified (activated, and the readback and health check confirmed it). applied is null when a
// failure left fields written or an editor open. Schema and probe results are left alone.
Map _rmStampOutcome(Object result) {
    if (!(result instanceof Map) || !(result as Map).containsKey("success")) return result as Map
    Map r = result as Map
    // fieldsWritten / wizardStuck mark a row that is written but not committed, so they outrank
    // partial: the answer is null, not true. checksSkipped names the checks a hub read kept from running.
    boolean unsettled = r.wizardStuck == true || r.fieldsWritten == true
    def skippedChecks = _memoSkippedChecks()
    if (skippedChecks) r.checksSkipped = skippedChecks
    boolean applied = r.rolledBack != true && (r.success == true || (r.partial == true && !unsettled))
    Boolean appliedFlag = applied ? true : (unsettled ? null : false)
    // A time-budget pause keeps what it committed, but the rule's update runs only when the rest is done.
    boolean activated = applied && r.status != "in_progress" && r.updateRuleFailed != true && r.subscriptionsNotLive != true &&
        r.mainPageDoneFailed != true && r.mainPageDoneSkipped != true && r.finalisationNotAttempted != true
    boolean verified = activated && r.success == true && r.verificationFetchFailed != true && r.healthUnverified != true &&
        (r.health == null || ((r.health as Map).ok == true && (r.health as Map).unreadable != true))
    r.applied = appliedFlag
    r.activated = activated
    r.verified = verified
    return r
}

def toolSetNativeApp(args) {
    // Button Rules are grandchildren of a Button Controller and only get their
    // button/event context via the controller's add-button flow -- a bare
    // createchild yields an un-renderable orphan. buttonRule routes through the
    // parent controller (see _createButtonRuleViaController, which also rejects
    // any other operative param bundled alongside buttonRule).
    if (args instanceof Map && args.buttonRule != null) {
        return _createButtonRuleViaController(args)
    }
    // Generic create-or-edit for any classic SmartApp. The authoring shortcuts
    // (walkStep / addTrigger / addAction / addRequiredExpression / patches / ...)
    // are NOT rejected on EDIT (appId present): walkStep is a generic classic-
    // dynamicPage walker, and the RM-wire-format shortcuts genuinely apply to
    // RM-format classic apps (Basic Rules, Button Rules, etc.). They flow
    // through to the shared edit engine below. They stay out of this tool's
    // lean schema to keep the catalog small and to steer callers toward
    // hub_set_rule for actual Rule Machine RULES -- but a hand-crafted call
    // may use them where they apply.
    if (args?.appId == null) {
        // CREATE arm: _createNativeAppShell honors only triggers/actions/
        // requiredExpression (and only on RM-format types). Any edit-path
        // shortcut handed to a create would be silently dropped while the
        // call returned success:true on an empty shell -- same honor-or-
        // loudly-reject posture as hub_set_rule's create gate.
        def droppedOnCreate = ['addTrigger', 'addTriggers', 'addAction', 'addActions',
                               'addRequiredExpression', 'replaceRequiredExpression', 'addLocalVariable', 'removeLocalVariable', 'patches', 'replaceActions',
                               'removeAction', 'clearActions', 'moveAction', 'removeTrigger',
                               'modifyTrigger', 'modifyAction', 'walkStep', 'settings', 'button'].findAll {
            args instanceof Map && args.containsKey(it)
        }
        if (droppedOnCreate) {
            throw new IllegalArgumentException("hub_set_native_app CREATE (no appId) does not honor ${droppedOnCreate.join(', ')} -- ${droppedOnCreate.size() == 1 ? 'it requires' : 'they require'} an existing app. Create the app first (appType + name), then re-call with the returned appId; for Rule Machine authoring sugar on create, use hub_set_rule.")
        }
        return _createNativeAppShell(args)
    }
    def out = _applyNativeAppEdit(args)
    def skippedChecks = _memoSkippedChecks()
    if (skippedChecks && out instanceof Map) out.checksSkipped = skippedChecks
    return out
}

// Create a Button Rule-5.1 under an existing Button Controller-5.1, routing
// through the controller's own add-button flow. These grandchild rules only
// get their button/event context (tCapab1=Button / tDev1 / tstate1 /
// ButtontDev1) when the controller creates them -- a bare createchild yields an
// un-renderable "Cannot set property '1' on null object" orphan.
//
// Replicates the classic UI sequence (verified live, captured from appUI.js'
// buttonClick/jsonSubmit). The wire steps (body comments 2-5) run in ONE
// in-session flow with NO state-resetting GET between the mutations -- the
// add-button mode lives only in the POST-response chain and a fresh GET
// collapses it:
// - btn click name="true" stateAttribute="addBut"   (enter add-button mode)
// - full-form update/json (no newBut)                (re-render -> button picker)
// - full-form update/json + newBut=[buttonNumber]    (define the button)
// - btn click name="<N> <event>" stateAttribute="setBut" (spawn the rule)
//
// Returns the new rule's appId so the caller authors its actions via
// hub_set_rule(appId=<buttonRuleId>, addAction=...). An empty button rule stays
// "(Not Installed)" until it has >=1 action -- expected, not an error.
def _createButtonRuleViaController(args) {
    requireDestructiveConfirm(args?.confirm as Boolean)
    def br = args?.buttonRule
    if (!(br instanceof Map)) {
        throw new IllegalArgumentException("buttonRule must be an object: {controllerId, buttonNumber, event}")
    }
    // buttonRule is exclusive: every other operative param would be silently
    // ignored by this flow (same honor-or-loudly-reject posture as the create
    // gates). confirm rides along; everything else is a caller mistake.
    def bundledExtras = ['appId', 'appType', 'name', 'settings', 'button', 'pageName', 'stateAttribute',
                         'walkStep', 'addTrigger', 'addTriggers', 'addAction', 'addActions',
                         'addRequiredExpression', 'replaceRequiredExpression', 'addLocalVariable', 'removeLocalVariable', 'patches', 'replaceActions',
                         'removeAction', 'clearActions', 'moveAction', 'removeTrigger', 'modifyTrigger', 'modifyAction'].findAll {
        args instanceof Map && args.containsKey(it)
    }
    if (bundledExtras) {
        throw new IllegalArgumentException("buttonRule is exclusive -- ${bundledExtras.join(', ')} would be silently ignored. Create the button rule first, then apply edits in a follow-up call using the returned buttonRuleId as appId.")
    }
    def controllerId = null
    try { controllerId = (br.controllerId as Integer) } catch (Exception ignore) {}
    if (controllerId == null) {
        throw new IllegalArgumentException("buttonRule.controllerId is required -- the appId of a Button Controller-5.1 instance (from hub_list_apps scope='instances'). Create one first via hub_set_native_app(appType='button_controller', name=...) and assign its button device.")
    }
    _requireUnprotectedAppMutation(controllerId, "create a button rule under")
    def buttonNumber = null
    try { buttonNumber = (br.buttonNumber as Integer) } catch (Exception ignore) {}
    if (buttonNumber == null || buttonNumber < 1) {
        throw new IllegalArgumentException("buttonRule.buttonNumber is required and must be >= 1 (the physical button number on the device).")
    }
    def event = br.event?.toString()?.trim()
    def validEvents = ["pushed", "held", "doubleTapped", "released"]
    if (!(event in validEvents)) {
        throw new IllegalArgumentException("buttonRule.event must be one of ${validEvents} (got '${event}').")
    }

    // 1. Validate the parent controller + its button device.
    def cfg = _rmFetchConfigJson(controllerId)
    def ctrlType = cfg?.app?.appType?.name
    if (ctrlType != "Button Controller-5.1") {
        throw new IllegalArgumentException("appId ${controllerId} is '${ctrlType ?: 'unknown'}', not a Button Controller-5.1. buttonRule.controllerId must be a Button Controller-5.1 instance.")
    }
    def buttonDevSetting = cfg?.settings?.buttonDev
    def buttonDevIds = (buttonDevSetting instanceof Map) ? buttonDevSetting.keySet().collect { it.toString() } : []
    if (!buttonDevIds) {
        throw new IllegalArgumentException("Button Controller ${controllerId} has no button device assigned. Set it first: hub_set_native_app(appId=${controllerId}, settings={buttonDev: [<deviceId>]}, confirm=true).")
    }
    def origLabel = cfg?.settings?.origLabel?.toString() ?: ""
    def schema = _rmCollectInputSchema(cfg?.configPage)
    def beforeIds = (cfg?.childApps ?: []).collect { it?.id?.toString() } as Set

    def backup
    try { backup = _rmBackupRuleSnapshot(controllerId, "pre-buttonRule") }
    catch (Exception backupErr) {
        Map unavailable = _itemBackupIndexUnavailableResult(backupErr)
        if (unavailable == null) throw backupErr
        return unavailable + [controllerId: controllerId]
    }

    // Runtime-error envelope: a mid-flow throw after the backup would otherwise
    // surface as a flat "Tool error: <msg>" with no backupKey/controllerId and
    // no hint that the controller may be mid add-button mode -- and a throw on
    // the step-6 discovery fetch would lose the id of a rule that DID spawn.
    // Mirrors _applyNativeAppEdit's _rmBuildUpdateErrorResponse posture.
    try {
        // 2. Enter add-button mode (minimal btn click -- no pageName so no GET).
        _rmClickAppButton(controllerId, "true", "addBut")

        // 3. Full-form re-render (the UI's changeSubmit after addBut) so the button
        //    picker materializes. Re-send the controller's current form unchanged.
        def baseSettings = [buttonDev: buttonDevIds, origLabel: origLabel]
        hubInternalPostForm("/installedapp/update/json", _rmBuildSettingsBody(controllerId, baseSettings, schema))

        // 4. Define the button: full form + newBut as a multi-select enum. Force the
        //    enum/multiple schema -- newBut is a transient input not in the static
        //    page schema, so the JSON-array serialization needs the hint.
        def defineSchema = (schema ?: [:]) + [newBut: [type: "enum", multiple: true]]
        def defineSettings = baseSettings + [newBut: [buttonNumber]]
        hubInternalPostForm("/installedapp/update/json", _rmBuildSettingsBody(controllerId, defineSettings, defineSchema))

        // 5. Spawn the rule for "<N> <event>" (minimal btn click).
        _rmClickAppButton(controllerId, "${buttonNumber} ${event}".toString(), "setBut")

        // 6. Discover the new grandchild by diffing the controller's children.
        def afterCfg = _rmFetchConfigJson(controllerId)
        def afterIds = (afterCfg?.childApps ?: []).collect { it?.id?.toString() } as Set
        def newIds = (afterIds - beforeIds).toList()
        if (!newIds) {
            return [success: false, controllerId: controllerId, buttonNumber: buttonNumber, event: event,
                backup: backup,
                error: "Button rule create did not produce a new child under controller ${controllerId}.",
                note: "Verify the controller's button device is valid and button ${buttonNumber} exists on it. If a rule for this button/event already existed, the controller may have reused it instead of spawning -- inspect childApps via hub_get_app_config(appId=${controllerId})."]
        }
        def newRuleId = (newIds[0] as Integer)
        def ruleHealth = _rmCheckRuleHealth(newRuleId)
        def result = [success: true, buttonRuleId: newRuleId, controllerId: controllerId,
            buttonNumber: buttonNumber, event: event, backup: backup, health: ruleHealth,
            note: ("Empty button rule created (it shows '(Not Installed)' until it has an action). " +
                "Author its actions with hub_set_rule(appId=${newRuleId}, addAction={...}) -- the rule is RM-wire-format. " +
                "Trigger (Button ${buttonNumber} ${event}) is already seeded by the controller.").toString()]
        if (newIds.size() > 1) {
            // More than one new child appeared during the flow (concurrent UI
            // session?) -- expose all of them rather than silently picking one.
            result.newChildIds = newIds
            result.note = (result.note + " NOTE: ${newIds.size()} new children appeared during the flow (${newIds.join(', ')}); buttonRuleId is the first -- verify via hub_get_app_config.").toString()
        }
        return result
    } catch (Exception flowExc) {
        mcpLogError("rm-native", "buttonRule flow failed mid-stream for controller ${controllerId}", flowExc)
        return [success: false, controllerId: controllerId, buttonNumber: buttonNumber, event: event,
            backup: backup, error: flowExc.message ?: flowExc.toString(),
            note: ("The add-button flow failed mid-stream; the controller may be left in add-button mode (a fresh page open in the UI collapses it) and the rule may or may not have spawned -- check childApps via hub_get_app_config(appId=${controllerId}). " +
                (backup?.backupKey ? "Restore the controller via hub_restore_backup(backupKey='${backup.backupKey}') if its config looks damaged." : "No backup key was returned for the controller; repair its config in place if it looks damaged.")).toString()]
    }
}

// _createNativeAppShell — create a new, empty native app (createchild + name-set)
// for args.appType (default rule_machine), then optionally bake bundled RM
// triggers/actions/Required-Expression (args.triggers / args.actions /
// args.requiredExpression). Backend for the create arm of both hub_set_rule and
// hub_set_native_app.
//
// The createchild + name-set is type-agnostic; the triggers/actions/RE bundling is
// RM-specific and only reached when hub_set_rule passes those keys. The RE walk
// runs between triggers and actions so its trailing ghost-ifThen predCapabs clear
// leaves subsequent action adds unwrapped.
//
// Auto-cleanup: if the name-set fails after createchild succeeds, the orphan
// child is force-deleted so the user doesn't accumulate broken shells.
def _createNativeAppShell(args) {
    requireDestructiveConfirm(args?.confirm as Boolean)
    def appType = args?.appType?.toString()?.trim() ?: "rule_machine"
    if (appType == "visual_rule") {
        // Visual Rule children are Vue-JSON apps (/app/ruleBuilderJson family), not classic
        // dynamicPage apps -- the wizard's configure/json + update/json flow can't configure
        // them. The dedicated tool drives the real create + save endpoints.
        throw new IllegalArgumentException("Visual Rules Builder rules are Vue-JSON apps, not classic dynamicPage apps -- use hub_set_visual_rule (hub_manage_rule_machine gateway) instead.")
    }
    def reg = _appTypeRegistry()[appType]
    if (!reg) {
        throw new IllegalArgumentException("Unknown appType '${appType}'. Supported: ${_appTypeRegistry().keySet().join(', ')}")
    }
    def name = args?.name?.toString()?.trim()
    if (!name) throw new IllegalArgumentException("name is required")

    def parentId = _discoverParentAppId(appType)
    _requireUnprotectedAppMutation(parentId, "create a child app under")
    def newId
    try {
        newId = _rmCreateChildApp(parentId, reg.namespace, reg.appName)
    } catch (Exception createExc) {
        // Hub returned an error from /installedapp/createchild — most
        // common cause is that the registry's namespace/appName combo
        // doesn't match an installed app on this hub. Surface a clear
        // hint instead of the generic "unexpected error".
        mcpLogError("rm-native", "createchild failed for appType='${appType}' (ns=${reg.namespace}, appName=${reg.appName}, parent=${parentId})", createExc)
        throw new IllegalArgumentException(
            "Creating a native app failed at the createchild step for appType='${appType}'. " +
            "The hub rejected namespace='${reg.namespace}' + appName='${reg.appName}' " +
            "(parent app id ${parentId}). Most likely the _appTypeRegistry entry has the " +
            "wrong child appName for this hub's installed parent. To verify the actual " +
            "child appName, call hub_list_apps (scope='instances') and inspect the children of " +
            "parentTypeName='${reg.parentTypeName}' — the children's `type` field is the " +
            "child appName the hub expects. Underlying error: ${createExc.message}")
    }

    try {
        // First page of a fresh classic SmartApp is the label page; its label input is `origLabel`
        // unless the registry names another (labelInput). Schema is introspected from
        // configure/json so the 3-field capability contract applies uniformly.
        def firstPage = _rmFetchConfigJson(newId)
        def schema = _rmCollectInputSchema(firstPage?.configPage)
        def labelInput = reg.labelInput ?: "origLabel"
        def body = _rmBuildSettingsBody(newId, [(labelInput): name], schema)
        hubInternalPostForm("/installedapp/update/json", body)

        // updateRule is RM's "commit + reinitialize" button, the framework
        // default for most installed apps. The registry's commitButton field
        // (default "updateRule" when absent) lets an appType override it; a
        // null commitButton means the app is submitOnChange and has no commit
        // button (e.g. Basic Rule -- clicking updateRule there poisons the
        // render with "For input string: updateRule").
        def createCommitButton = reg.containsKey("commitButton") ? reg.commitButton : "updateRule"
        if (createCommitButton) _rmClickAppButton(newId, createCommitButton)

        // The label write uses the app type's own label input (registry `labelInput`, default
        // origLabel): RM copies origLabel -> label on updateRule, Group-2.1 relabels from its
        // `name` input immediately. Types without a label input (Button Controller, ...) keep
        // their default type-name label; the labelApplied read-back below reports that honestly.
        // A prior attempt to set the label generically via /installedapp/update did not take
        // effect on a live Button Controller, so it was removed rather than shipped dead.

        // Optional bulk-trigger creation. When `triggers` is passed, walk
        // the list and call _rmAddTrigger for each spec. After all are
        // committed, fire updateRule once at the end so subscriptions
        // populate from a fully-loaded rule (avoids N redundant inits).
        def triggerSpecs = args?.triggers instanceof List ? (args.triggers as List) : []
        def triggerResults = []
        // Fail closed: the first failed or partial item stops every later mutation and finalisation.
        String createStopAfter = null
        def createStopItem = null
        if (triggerSpecs) {
            triggerSpecs.eachWithIndex { spec, i ->
                if (createStopAfter) { triggerResults << _rmBulkNotAttempted(createStopAfter); return }
                if (!(spec instanceof Map)) {
                    triggerResults << [success: false, error: "triggers[${i}] is not a Map", spec: spec]
                } else {
                    try {
                        triggerResults << _rmAddTrigger(newId, spec as Map)
                    } catch (Exception te) {
                        triggerResults << ([success: false, error: te.message, specCapability: spec.capability] + _rmOutcomeKeys(te))
                        mcpLog("warn", "rm-native", "hub_set_rule: trigger ${i} (capability=${spec.capability}) failed -- ${te.message}")
                    }
                }
                if (_rmBulkItemBlocks(triggerResults.last())) { createStopAfter = "triggers[${i}]".toString(); createStopItem = triggerResults.last() }
            }
            // Re-init once after all triggers are committed.
            if (!createStopAfter) _rmClickAppButton(newId, "updateRule")
        }

        // Optional Required Expression creation. Runs BEFORE actions: the RE
        // walk seals predCapabs and the helper fires a ghost ifThen clear so
        // subsequent addAction calls don't inherit an IF(**Broken Condition**)
        // wrapper. _rmAddRequiredExpression only clicks hasRule+doneST -- it does
        // NOT fire updateRule, so the expression is not yet live on the running
        // rule when the helper returns. The actions path below fires its own
        // trailing updateRule, which reinitializes the RE for free when actions
        // are present. When NO actions follow, that updateRule never fires and
        // the RE would stay dormant, so this block fires a trailing updateRule
        // itself (mirroring the _applyNativeAppEdit edit-arm pattern) and
        // surfaces an updateRuleFailed/expressionNotLive degradation if the
        // re-init click is rejected rather than silently swallowing it.
        def reSpec = args?.requiredExpression instanceof Map ? (args.requiredExpression as Map) : null
        def reResult = null
        if (reSpec != null && createStopAfter) {
            reResult = _rmBulkNotAttempted(createStopAfter)
        } else if (reSpec != null) {
            try {
                reResult = _rmAddRequiredExpression(newId, reSpec)
            } catch (Exception ree) {
                reResult = [success: false, error: ree.message]
                mcpLog("warn", "rm-native", "hub_set_rule: requiredExpression failed -- ${ree.message}")
            }
            // Reinitialize the RE on the running rule. When actions follow,
            // their trailing updateRule (below) also covers this, but firing it
            // here makes the no-actions path correct and an extra updateRule is
            // harmless (triggers->updateRule->actions->updateRule already fires
            // multiple per session). On failure, degrade reResult honestly so a
            // dormant RE is not reported as live.
            if (_rmBulkItemBlocks(reResult)) { createStopAfter = "requiredExpression"; createStopItem = reResult }
            if (reResult instanceof Map && !createStopAfter) {
                try {
                    _rmClickAppButton(newId, "updateRule")
                } catch (Exception reUpdExc) {
                    reResult.updateRuleFailed = true
                    reResult.expressionNotLive = true
                    reResult.updateRuleError = reUpdExc.message
                    reResult.partial = true
                    mcpLog("warn", "rm-native", "hub_set_rule: requiredExpression trailing updateRule click failed for app ${newId} -- expression may not be live: ${reUpdExc.message}")
                }
                if (_rmBulkItemBlocks(reResult)) { createStopAfter = "requiredExpression"; createStopItem = reResult }
            }
        }

        // Optional bulk-action creation. Mirrors the triggers path but
        // for actions. Each spec follows hub_set_rule's addAction
        // shape. After all are committed, fire updateRule so the rule
        // re-subscribes from a fully-loaded state (actions self-bake via
        // doActPage->selectActions per-item).
        def actionSpecs = args?.actions instanceof List ? (args.actions as List) : []
        def actionResults = []
        if (actionSpecs) {
            // Resolve the valid-rule-id set once for the whole batch (only when a
            // rule-targeting action is present) and thread it to each item.
            def actionsValidRuleIds = (!createStopAfter && _rmSpecListTargetsRule(actionSpecs)) ? _rmValidRuleIds() : null
            actionSpecs.eachWithIndex { spec, i ->
                if (createStopAfter) { actionResults << _rmBulkNotAttempted(createStopAfter); return }
                if (!(spec instanceof Map)) {
                    actionResults << [success: false, error: "actions[${i}] is not a Map", spec: spec]
                } else {
                    try {
                        actionResults << _rmAddAction(newId, _rmWithClock(spec as Map, args?.__reqT0 as Long), true, actionsValidRuleIds)
                    } catch (Exception ae) {
                        actionResults << (_rmAddRefusalResult(ae) + [specCapability: spec.capability, specAction: spec.action])
                        mcpLog("warn", "rm-native", "hub_set_rule: action ${i} (${spec.capability}/${spec.action}) failed -- ${ae.message}")
                    }
                }
                if (_rmBulkItemBlocks(actionResults.last())) { createStopAfter = "actions[${i}]".toString(); createStopItem = actionResults.last() }
            }
            // After bulk-add, navigate selectActions → mainPage via
            // _action_previous=Done — mirrors the live UI's "Done with
            // Actions" click. _rmAddAction leaves us on selectActions; the
            // updateRule button lives on mainPage. Verified live
            // by capturing the working UI flow's XHR sequence: every action
            // commit ends with a Done navigation up to mainPage before
            // updateRule fires. Without this, state.editAct can linger
            // and updateRule may fire from the wrong page state.
            if (!createStopAfter) {
                try {
                    _rmSubmitSubPageDone(newId, "selectActions", "mainPage", "name", null)
                } catch (Exception subPageDoneExc) {
                    mcpLog("warn", "rm-native", "hub_set_rule: trailing _rmSubmitSubPageDone(selectActions->mainPage) failed for app ${newId} (${subPageDoneExc.message}) -- relying on updateRule below; lingering state.editAct markers may corrupt subsequent edits")
                }
                _rmClickAppButton(newId, "updateRule")
            }
        }

        // Final commit: click the Done button on mainPage. The live UI
        // ALWAYS fires this as the last step of every create/modify session
        // (verified). Without it, the rule's session-end state
        // can be incomplete and subsequent reads/edits may behave oddly.
        def createDone = createStopAfter ? null : _rmSubmitMainPageDone(newId)

        def status = _rmFetchStatusJson(newId)
        def health = _rmCheckRuleHealth(newId)

        // Roll up partial-success indices so an LLM driver doesn't have to
        // scan each result. Each entry's `partial` flag is set by the
        // _rm{Add,Trigger}Action helper when caller-requested fields were
        // dropped or the trigger/action row carries a *BROKEN* marker.
        def partialTriggers = []
        triggerResults.eachWithIndex { r, i ->
            if (r instanceof Map && (r.partial == true || r.success == false)) partialTriggers << (i + 1)
        }
        def partialActions = []
        actionResults.eachWithIndex { r, i ->
            if (r instanceof Map && (r.partial == true || r.success == false)) partialActions << (i + 1)
        }
        // The Required Expression is a single optional piece, not an indexed list.
        // It contributes to partial/success the same way a partial trigger/action
        // does: a degraded or failed RE write must NOT let the rule report clean.
        def reFailed = (reResult instanceof Map) && (reResult.success == false)
        def rePartial = (reResult instanceof Map) && (reResult.partial == true)
        def repairHints = []
        if (partialTriggers || partialActions || reFailed || rePartial) {
            repairHints << "Rule ${newId} was created BUT some pieces are incomplete -- see partialTriggers / partialActions arrays for indices${reFailed || rePartial ? ", and requiredExpression for the Required Expression outcome" : ""}. Each partial result has its own repairHints list with concrete next steps."
            repairHints << "Repair pattern: 1) hub_get_app_config(${newId}, includeSettings=true) to inspect current state. 2) For each partial trigger/action, follow its repairHints. 3) hub_set_rule(walkStep={...}) for incremental field writes; replaceActions(...) or removeAction(index) + addAction(...) for whole-action retries. 4) hub_set_rule(button='updateRule') after fixes to commit. 5) Re-run hub_get_rule_health to verify. Don't conclude failure until tool-only repair attempts are exhausted."
            repairHints << "Full trigger/action field reference: call hub_set_rule(guide:true), or hub_get_tool_guide(section='set_rule_create_reference')."
        }
        // Verify the label instead of assuming by appType: rule_machine reliably
        // copies origLabel->label on commit; for every other type read the committed
        // label back and compare (startsWith tolerates RM-style live suffixes). An
        // unreadable page keeps the conservative false + the honesty note below.
        def labelApplied = (appType == "rule_machine")
        if (!labelApplied && name) {
            try {
                def committedLabel = _rmFetchConfigJson(newId)?.app?.label?.toString()
                labelApplied = (committedLabel != null && committedLabel.startsWith(name.toString()))
            } catch (Exception labelExc) {
                mcpLog("debug", "rm-native", "create ${appType} ${newId}: label verify fetch failed: ${labelExc.message}")
            }
        }
        def result = [
            success: _rmHealthGatePass(health) && !partialTriggers && !partialActions && !reFailed && !rePartial,
            partial: (partialTriggers || partialActions || reFailed || rePartial) as Boolean,
            partialTriggers: partialTriggers,
            partialActions: partialActions,
            repairHints: repairHints,
            appId: newId,
            appType: appType,
            name: name,
            labelApplied: labelApplied,
            parentAppId: parentId,
            statusSummary: [
                eventSubscriptions: (status?.eventSubscriptions?.size() ?: 0),
                scheduledJobs: (status?.scheduledJobs?.size() ?: 0)
            ],
            health: health,
            note: (triggerSpecs || actionSpecs || reSpec != null) ?
                "Created ${appType} app (id=${newId}) with ${triggerResults.count { it?.success == true }}/${triggerSpecs.size()} triggers + ${actionResults.count { it?.success == true }}/${actionSpecs.size()} actions${reSpec != null ? " + Required Expression (${reFailed ? "failed" : (rePartial ? "partial" : "applied")})" : ""} FULLY committed${partialTriggers || partialActions || reFailed || rePartial ? " (some partial -- see partialTriggers/partialActions/requiredExpression for repair)" : ""}. updateRule fired to commit each section${reSpec != null ? (reResult?.updateRuleFailed ? " but the Required Expression re-init updateRule was REJECTED -- the expression may not be live (see requiredExpression.updateRuleError)" : " (including a trailing updateRule after the Required Expression so it is live)") : ""}." :
                "Empty ${appType} app created (id=${newId}). Use hub_set_rule (RM rules) or hub_set_native_app (other classic apps) to populate, or hub_get_app_config to inspect."
        ]
        // For RM rules the app id IS the rule id -- the ruleId-taking downstream tools (hub_call_rule,
        // hub_set_rule_paused, hub_set_rule_private_boolean) take ruleId, so surface it explicitly under
        // that name too so an agent can chain a create straight into them without re-deriving it. Only
        // rule_machine apps get the real id; a non-RM classic app has none, so it is surfaced as an
        // explicit null to preserve the same response shape across app types.
        if (appType == "rule_machine") {
            result.ruleId = newId
        } else {
            result.ruleId = null
        }
        // Surface a missed session-end Done. For commitButton:null app types
        // (Basic Rule, Button Controller) the Done is the session's ONLY
        // lifecycle event, so a swallowed miss would be a false-clean create.
        if (createDone?.done == false) {
            result.mainPageDoneFailed = true
            result.mainPageDoneError = createDone.reason
            // Flip success ONLY for commitButton:null app types (Basic Rule, Button Controller),
            // where this Done is the session's ONLY lifecycle event -- a miss there means the app
            // never initialized (a false-clean create). RM-family creates already fired updateRule
            // above to commit each section, so a missed trailing Done there is a state-marker
            // cleanup caveat (surfaced via the repairHint), not a creation failure.
            if (createDone.uiBlocked == true || _resolveCommitButton(_appTypeRegistry()[appType]?.appName) == null) {
                result.success = false
            }
            result.repairHints = (result.repairHints ?: []) + [_rmMainPageDoneRepairHint(newId as Integer, createDone)]
        }
        if (createStopAfter) {
            result.success = false
            result.partial = true
            result.bulkStoppedAfter = createStopAfter
            result.finalisationNotAttempted = true
            result.error = _rmBulkStopError(createStopAfter, createStopItem)
            result.note = "Created ${appType} app (id=${newId}) but stopped after ${createStopAfter} failed or was partial: items after the stopping item were not attempted, and the remaining updateRule and mainPage Done were not fired. Sections before the stop may already have been finalised.".toString()
            result.repairHints = (result.repairHints ?: []) + ["Creation stopped fail-closed after ${createStopAfter}. A new rule has no pre-operation backup: inspect with hub_get_app_config(appId=${newId}), then either delete the incomplete rule and re-create it, or repair the failed item and add the notAttempted items, fire hub_set_rule(button='updateRule') and re-run hub_get_rule_health.".toString()]
        }
        if (triggerSpecs) result.triggers = triggerResults
        if (actionSpecs) result.actions = actionResults
        if (reSpec != null) {
            result.requiredExpression = reResult
            // Match the edit arm's top-level RE outcome fields on create.
            if (reResult?.requiredExpressionAlreadyExists) result.requiredExpressionAlreadyExists = true
            if (reResult?.expressionNotLive) result.expressionNotLive = true
            if (reResult?.updateRuleFailed) result.updateRuleFailed = true
            if (reResult?.updateRuleError != null) result.updateRuleError = reResult.updateRuleError
        }
        // Honesty caveat, now only when the label VERIFIABLY did not land (or the
        // verify read failed): labelApplied above is read back from the committed
        // page for non-RM types, so a clean create no longer carries a false alarm.
        if (!labelApplied) {
            result.note = ((result.note ?: "") +
                " NOTE: the requested name could not be verified as the app's display label. Verify via hub_get_app_config(appId=${newId}) and rename in the Hubitat UI if needed.").toString().trim()
        }
        return result
    } catch (Exception e) {
        // Orphan cleanup: caller didn't get a usable app, so remove the
        // half-created shell rather than leaving it under the parent.
        // forcedelete/quiet is idempotent on already-gone ids.
        mcpLogError("rm-native", "native app setup failed after createchild for ${newId} (appType=${appType}) -- cleaning up", e)
        try { _rmForceDeleteApp(newId) } catch (Exception ce) {
            mcpLog("warn", "rm-native", "Orphan cleanup failed for ${newId}: ${ce.message}")
        }
        return [success: false, error: "${appType} create failed: ${e.message ?: e.toString()}", orphanCleanup: "attempted", note: "No partial app left behind."]
    }
}

// Read a rule's local-variable namespace (state.allLocalVars) from its
// statusJson appState. The single source of truth for the allLocalVars shape:
// every site that reads a rule's locals goes through here so the appState
// list-of-entries traversal and the shape guards live in one place.
//
// appState is a LIST of {name, value} entries; the allLocalVars entry's value
// is the {name -> {type, value}} map. Returns:
//   [ok:true,  vars:<Map>]  -- read succeeded; vars is the locals map ([:] when
//                              the rule has no locals, i.e. no allLocalVars entry).
//   [ok:false, vars:[:]]    -- the status read itself threw (channel down).
// A non-List appState (a shape RM is not expected to emit) is treated as
// "no locals" but logged as a shape mismatch so a silent zero-locals read of a
// changed contract is visible rather than mistaken for a genuinely empty rule.
private Map _rmReadLocalVarsMap(Integer appId) {
    def status
    try {
        status = _rmFetchStatusJson(appId)
    } catch (Exception e) {
        return [ok: false, vars: [:], error: "${e.class.simpleName}: ${e.message ?: e.toString()}"]
    }
    def appState = status?.appState
    if (appState != null && !(appState instanceof List)) {
        // appState has always been a List-of-entries; a non-List shape would make the
        // .find below read zero locals silently. An EMPTY non-List (e.g. [:]) carries no
        // data to lose, so treat it as a plain no-locals read. A NON-EMPTY non-List shape
        // carries data we cannot interpret -- returning ok:true would mask a read failure
        // as a genuinely-empty rule, so surface it as ok:false (a read failure) instead, so
        // callers (hub_list_rule_local_variables) report a read error rather than total:0.
        if (appState instanceof Map ? !appState.isEmpty() : true) {
            def shapeLabel = (appState instanceof Map) ? "a Map" : "a non-List value"
            mcpLog("warn", "rm-native", "_rmReadLocalVarsMap: app ${appId} statusJson appState is ${shapeLabel} (expected a List of {name,value} entries) -- treating as a read failure; the allLocalVars contract may have changed.")
            return [ok: false, vars: [:], error: "statusJson appState was ${shapeLabel}, not the expected List of entries -- cannot read local variables (the allLocalVars contract may have changed)"]
        }
        return [ok: true, vars: [:]]
    }
    def raw = (appState ?: []).find { it?.name?.toString() == "allLocalVars" }?.value
    // A running rule updates lv_<name>; allLocalVars only catches up when the rule's page renders.
    def live = [:]
    (appState ?: []).each { e ->
        def n = e?.name?.toString()
        // Local names can be sandbox property names such as fields; use Map.put for arbitrary keys.
        if (n?.startsWith("lv_") && e.value instanceof Map) live.put(n.substring(3), e.value)
    }
    return [ok: true, vars: (raw instanceof Map) ? raw : [:], live: live]
}

// Add a local variable to a Rule Machine 5.1 rule.
//
// RM 5.1's variable wizard lives on selectActions. The flow:
// 1. Click `moreVar` button (state=moreVar) → opens wizard, exposes
// `hbVar` text input ("Name the local variable")
// 2. Write hbVar=<name> → exposes `varType` enum
// 3. Write varType=<Number|Decimal|String|Boolean|DateTime> → exposes
// `varValue` input (typed per varType)
// 4. Write varValue=<initial> → AUTO-COMMITS; the variable is added to
// state.allLocalVars and the wizard fields disappear, ready for
// the next variable. No explicit commit button.
//
// Verified live via probe on rule 1348:
// state.allLocalVars = {myCounter: {type:'integer', value:42}}
//
// Spec:
// {name: <varName>, type: 'Number'|'Decimal'|'String'|'Boolean'|'DateTime',
// value: <initial>}
//
// Returns [success, name, type, value, settingsApplied, settingsSkipped].
private Map _rmAddLocalVariable(Integer appId, Map varSpec) {
    if (!(varSpec instanceof Map)) {
        throw new IllegalArgumentException("addLocalVariable requires a Map spec")
    }
    def name = varSpec.name?.toString()?.trim()
    def type = varSpec.type?.toString()?.trim()
    def value = varSpec.value
    if (!name) throw new IllegalArgumentException("addLocalVariable.name is required")
    if (!type) throw new IllegalArgumentException("addLocalVariable.type is required")
    def validTypes = ["Number", "Decimal", "String", "Boolean", "DateTime"]
    def typeCanonical = validTypes.find { it.equalsIgnoreCase(type) }
    if (!typeCanonical) {
        throw new IllegalArgumentException("addLocalVariable.type '${type}' must be one of: ${validTypes.join(', ')}")
    }
    if (value == null) {
        throw new IllegalArgumentException("addLocalVariable.value is required (RM auto-commits the variable when varValue is written)")
    }

    def applied = []
    def skipped = []

    // Step 1. Click moreVar to open the wizard.
    _rmClickAppButton(appId, "moreVar", "moreVar", "selectActions")

    // Step 2. Write hbVar (name).
    _rmWriteSettingOnPage(appId, "selectActions", "hbVar", name, applied, null, skipped)

    // Step 3. Write varType. Schema reveal: after this, varValue appears.
    _rmWriteSettingOnPage(appId, "selectActions", "varType", typeCanonical, applied, null, skipped)

    // Step 4. Write varValue. RM auto-commits the variable when this lands.
    // Boolean type expects the literal strings "true"/"false" — Groovy's
    // toString() on Boolean produces those, but ensure we don't pass a
    // bare Boolean primitive that gets serialized as something else.
    def serializedValue = value
    if (typeCanonical == "Boolean") {
        // Coerce any boolean-ish input to "true"/"false" string.
        if (value instanceof Boolean) serializedValue = value ? "true" : "false"
        else {
            def s = value.toString().toLowerCase()
            if (s in ["true", "false", "1", "0", "yes", "no"]) {
                serializedValue = (s in ["true", "1", "yes"]) ? "true" : "false"
            } else {
                throw new IllegalArgumentException("addLocalVariable.value for Boolean type must be true/false (got '${value}')")
            }
        }
    }
    _rmWriteSettingOnPage(appId, "selectActions", "varValue", serializedValue, applied, null, skipped)

    // Verify via appState.allLocalVars that the variable committed. RM
    // may take a moment to persist after varValue is written, so retry
    // the read up to 3 times with brief settling between attempts.
    def committed = false
    def attempts = 0
    def lastFetchErr = null
    while (attempts < 3 && !committed) {
        def lvRead = _rmReadLocalVarsMap(appId)
        if (lvRead.ok) {
            if (lvRead.vars.containsKey(name)) committed = true
        } else {
            lastFetchErr = lvRead.error
        }
        attempts++
        if (!committed && attempts < 3) {
            // Tickle the page once to nudge RM's persistence cycle.
            try { _rmFetchConfigJson(appId, "selectActions") }
            catch (Exception tickleExc) {
                lastFetchErr = lastFetchErr ?: "${tickleExc.class.simpleName}: ${tickleExc.message ?: tickleExc.toString()}"
            }
        }
    }
    if (!committed && lastFetchErr) {
        // The verification channel itself failed every attempt — surface
        // that as the root cause rather than silently blaming type mismatch.
        mcpLog("warn", "rm-native", "addLocalVariable: post-write verification fetch failed for app ${appId} (var '${name}'): ${lastFetchErr}. Treating as not-committed; the actual write may still have landed.")
    }
    if (!committed) {
        return [
            success: false,
            partial: true,
            hubRenderError: true,
            name: name,
            type: typeCanonical,
            value: value,
            settingsApplied: applied,
            settingsSkipped: skipped,
            error: "Variable '${name}' did not commit; check that the value matches the variable's type.",
            repairHints: [
                "Check the value's type against varType — Number/Decimal want numeric, String wants text, Boolean wants true/false, DateTime wants an ISO-style timestamp.",
                "Inspect via /installedapp/statusJson/<appId> appState.allLocalVars to see what's currently there."
            ]
        ]
    }

    return [
        success: true,
        name: name,
        type: typeCanonical,
        value: value,
        settingsApplied: applied,
        settingsSkipped: skipped
    ]
}

// Remove a rule-local variable. Drives the same two-step delete wizard the
// Hub Variables system app uses for globals (deleteGV opens the confirm prompt,
// delConfirm commits), but on the rule's own selectActions page and verified
// against state.allLocalVars rather than getGlobalVar.
//
// The first click sequence can be dropped silently by the hub (a state-machine
// race the configure/status prime alone does not always defeat), so the WHOLE
// sequence is retried once and the result verified by re-reading appState
// allLocalVars between attempts.
//
// Returns [success, name, deleted] on commit. On a verify miss after both
// attempts it returns success=false + partial=true + a diagnostic envelope
// (the var name still present, repair hints) rather than throwing, so the outer
// dispatcher can surface it. The legitimate verify-miss case is RM refusing to
// delete a variable still referenced by an action or expression.
//
// future: the hub-global delete path runs a structurally similar deleteGV/delConfirm
// retry+verify loop, but on the hubVar page and verified through getGlobalVar -- a
// shared primitive would have to live outside both libraries (cross-library #include
// is forbidden under #include's textual-paste model), so the two loops stay separate.
private Map _rmRemoveLocalVariable(Integer appId, String varName) {
    def name = varName?.toString()?.trim()
    if (!name) throw new IllegalArgumentException("removeLocalVariable.name is required")

    // The var name is spliced verbatim into the deleteGV click's settings[<name>]
    // POST form key (_rmClickAppButton builds settings[buttonName] from the button
    // name, and deleteGV uses the var name as the button). A name carrying a form-key
    // metacharacter ([ ] & =) or a non-ASCII byte corrupts that key, so the click
    // targets the wrong field and the delete silently no-ops -- the verify loop then
    // misreports it. RM constrains real local-variable names to a safe alphabet, so a
    // name containing one of these can never match an actual variable; reject up front
    // with the rule rather than letting it corrupt the wire.
    if (name =~ /[\[\]&=]/ || !(name ==~ /\p{ASCII}+/)) {
        throw new IllegalArgumentException("removeLocalVariable: '${name}' cannot be a Rule Machine local-variable name ([, ], &, = and non-ASCII are not allowed); see hub_list_rule_local_variables.")
    }

    // Pre-read: confirm the variable exists before attempting the delete, so a
    // typo'd / already-gone name fails with a clear list instead of a silent
    // no-op that the verify loop would then misreport. preReadOk distinguishes a
    // genuine read (the var is authoritatively absent -- incl. a rule with ZERO
    // locals) from a transient read failure (channel down) where we must NOT reject
    // -- a missing-name guard keyed only on a non-empty map would skip the
    // zero-locals case and report a false deleted:true.
    def preRead = _rmReadLocalVarsMap(appId)
    def localVarsBefore = preRead.vars
    if (!preRead.ok) {
        // Read channel down -- skip the existence pre-check and let the delete
        // sequence + verify decide. Log so the operator can see the read failed.
        mcpLog("warn", "rm-native", "removeLocalVariable: pre-read of appState.allLocalVars failed for app ${appId} (${preRead.error}) -- proceeding without existence pre-check")
    }
    if (preRead.ok && !localVarsBefore.containsKey(name)) {
        def available = localVarsBefore.keySet().sort().join(', ') ?: "(none -- rule has no local variables)"
        throw new IllegalArgumentException("removeLocalVariable: local variable '${name}' not found. Available local variables: ${available}")
    }

    // Two attempts of the full prime -> deleteGV -> delConfirm -> verify cycle, both
    // run unconditionally. On a live hub the first deleteGV/delConfirm sequence can
    // transiently miss (an RM state-machine race), and the second attempt is what
    // lands the delete -- so BOTH attempts always run; a fail-fast after attempt 1
    // would kill the retry and falsely report an un-referenced variable as "still
    // referenced".
    def stillThere = true
    def lastFetchErr = null
    def verifyReadOk = false
    for (int attempt = 0; attempt < 2; attempt++) {
        // Prime the wizard state (configure/json + statusJson) so the clicks land --
        // both fetches are required: configure/json is the page the clicks post
        // against, and statusJson settles RM's state machine so the deleteGV button
        // resolves. Dropping either lets the first click sequence silently miss.
        try {
            hubInternalGet("/installedapp/configure/json/${appId}")
            hubInternalGet("/installedapp/statusJson/${appId}")
        } catch (Exception primeExc) {
            mcpLog("debug", "rm-native", "removeLocalVariable: wizard prime attempt-${attempt + 1} for app ${appId} threw ${primeExc.class.simpleName}: ${primeExc.message ?: primeExc.toString()}")
        }
        // deleteGV opens the confirm prompt (name=varName, stateAttribute=deleteGV);
        // delConfirm commits it (name=delConfirm, stateAttribute=deleteConfirm). The
        // confirm button's stateAttribute differs from its name -- both values mirror
        // RM's own delete request. Clicks are scoped to selectActions for page context.
        // A click throw (e.g. a 400 POST) mid-sequence leaves RM's confirm prompt
        // half-open; close it with cancelCapab before propagating so the next edit does
        // not inherit a stuck wizard, and embed the wizardStuck marker so the dispatcher
        // surfaces the cancelCapab recovery hint (matches the addAction/STPage flow).
        try {
            _rmClickAppButton(appId, name, "deleteGV", "selectActions")
            _rmClickAppButton(appId, "delConfirm", "deleteConfirm", "selectActions")
        } catch (Exception clickExc) {
            def cleanupFailed = false
            def cleanupErr = null
            try { _rmClickAppButton(appId, "cancelCapab", null, "selectActions") }
            catch (Exception cancelExc) {
                cleanupFailed = true
                cleanupErr = cancelExc.message ?: cancelExc.toString()
                mcpLog("warn", "rm-native", "removeLocalVariable: cancelCapab cleanup failed for app ${appId} after a deleteGV/delConfirm click threw (var '${name}'): ${cleanupErr} -- the delete wizard may stay open and confuse subsequent edits; result will carry wizardStuck=true")
            }
            def baseMsg = clickExc.message ?: clickExc.toString()
            if (cleanupFailed) {
                throw _rmFail(new IllegalStateException("${baseMsg} [wizardStuck -- cancelCapab cleanup also failed: ${cleanupErr}]", clickExc), _rmOutcomeFlags(clickExc) + [wizardStuck: true])
            }
            throw new IllegalStateException("removeLocalVariable: deleteGV/delConfirm click failed for '${name}' -- ${baseMsg}. The in-flight delete wizard was closed (cancelCapab); retry the removal.")
        }
        // Verify: poll appState.allLocalVars until the var is gone. Settle before each
        // read -- RM persists the delete a moment after delConfirm, so reading first
        // would catch the var still present and waste a poll iteration.
        verifyReadOk = false
        for (int v = 0; v < 4; v++) {
            try { pauseExecution(500) } catch (Exception pe) { mcpLog("debug", "rm-native", "removeLocalVariable: verify-poll pauseExecution interrupted for app ${appId}: ${pe.class.simpleName}: ${pe.message ?: pe.toString()}") }
            def lvRead = _rmReadLocalVarsMap(appId)
            if (lvRead.ok) {
                verifyReadOk = true
                stillThere = lvRead.vars.containsKey(name)
            } else {
                lastFetchErr = lvRead.error
                mcpLog("debug", "rm-native", "removeLocalVariable: verify-read poll v=${v} attempt=${attempt + 1} for app ${appId} (var '${name}') failed: ${lvRead.error}")
                // Leave stillThere as-is and keep polling; a transient read error
                // should not be read as "committed".
            }
            if (!stillThere) break
        }
        if (!stillThere) break
    }

    if (stillThere) {
        def readMayHaveSucceeded = (lastFetchErr != null && !verifyReadOk)
        def error
        def repairHints
        if (readMayHaveSucceeded) {
            // Every verify read failed -- the delete may well have committed; we just
            // could not confirm it. Lead with that so the caller does not chase a
            // "still present" red herring.
            error = "Local variable '${name}' could not be confirmed deleted: every read back failed (${lastFetchErr}). Check hub_list_rule_local_variables before deleting again."
            repairHints = [
                "Re-check whether '${name}' is gone via hub_list_rule_local_variables -- the delete may already have committed.",
                "If it is still present, the delete wizard may have been rejected; remove any expression/condition that references '${name}' and retry."
            ]
        } else {
            // The var remains after both attempts. RM does NOT reliably refuse a referenced-local
            // delete (live, it usually deletes the local and breaks the referencing action -- that
            // case lands as deleted:true with a broken rule, handled by the apply-result envelope).
            // Reaching here means the delete wizard itself did not take, which RM can still do for
            // some expression/condition references on certain firmware; report it as a wizard miss
            // rather than asserting a blanket "RM refuses referenced locals" rule.
            error = "Local variable '${name}' did not delete -- state.allLocalVars still contains it after the deleteGV+delConfirm attempt(s). The delete wizard did not take; on some firmware an expression/condition reference can block it.${lastFetchErr ? " Last verify-read error: ${lastFetchErr}." : ""}"
            repairHints = [
                "Remove any expression or condition that references '${name}', then retry the removal.",
                "Inspect the current locals via hub_list_rule_local_variables."
            ]
        }
        return [
            success: false,
            partial: true,
            name: name,
            deleted: false,
            error: error,
            repairHints: repairHints
        ]
    }

    return [
        success: true,
        name: name,
        deleted: true
    ]
}

// Scan a /installedapp/configure/json config Map for RM's rendered broken-state markers,
// returning true if any **Broken Trigger/Action/Condition** appears. Reads BOTH render
// formats the hub serves -- the body-element format (sect.body[] where element is
// 'paragraph'/'href') AND the paragraphs-array format -- mirroring the live scan in
// _rmCheckRuleHealth. Used to derive a PRE-mutation broken baseline from a backup
// snapshot's captured configJson without a fresh hub read. A null/unparseable config
// returns false (cannot prove the rule was broken).
private boolean _rmConfigHasBrokenMarkers(config) {
    if (!(config instanceof Map)) return false
    def sections = config?.configPage?.sections ?: []
    def texts = (sections as List).collectMany { sect ->
        def fromBody = (sect?.body ?: [])
            .findAll { b -> b instanceof Map && (b.element == "paragraph" || b.element == "href") }
            .collect { it.description?.toString() ?: "" }
        def fromParagraphs = (sect?.paragraphs ?: []).collect { it?.toString() ?: "" }
        fromBody + fromParagraphs
    }
    return texts.any { text ->
        ["**Broken Trigger**", "**Broken Action**", "**Broken Condition**"].any { text.contains(it) }
    }
}

// Shared dispatch-envelope for the local-variable add/remove shortcuts. Both fire
// a trailing updateRule (re-evaluates the rule's action map / subscriptions against
// the new or removed local), brief-settle, health-check, then assemble a response
// that ORs the inner helper's partial into the outer partial. opKind ('add'|'remove')
// drives the only two real differences: the variable SUB-SHAPE (add returns
// {name,type,value} + settings*, remove returns {name,deleted}) and the wording of
// the repairHint / note. varResult is the inner helper's Map (it may itself be a
// structured success:false + partial:true + repairHints when the commit/verify
// missed -- those slots ride out unchanged).
private Map _rmApplyLocalVarResult(Integer appId, Map varResult, Map backup, String opKind) {
    def isAdd = (opKind == "add")
    def updateRuleFailed = false
    def variableNotLive = false
    def updateRuleError = null
    try { _rmClickAppButton(appId, "updateRule") }
    catch (Exception updateExc) {
        updateRuleFailed = true
        variableNotLive = true
        updateRuleError = updateExc.message
        mcpLog("warn", "rm-native", "${isAdd ? 'add' : 'remove'}LocalVariable: trailing updateRule click failed for app ${appId} -- ${isAdd ? 'variable' : 'removal'} may not be live: ${updateExc.message}")
    }
    // Brief settle so the post-updateRule health re-fetch reads the recompiled rule
    // state, not the pre-recompile snapshot (a loaded hub can answer ruleBuilderJson
    // before the recompile lands, yielding a transient false broken:true).
    try { pauseExecution(300) } catch (Exception pe) { mcpLog("debug", "rm-native", "${isAdd ? 'add' : 'remove'}LocalVariable: pre-health-check settle interrupted for app ${appId}: ${pe.class.simpleName}: ${pe.message ?: pe.toString()}") }
    def health = _rmCheckRuleHealth(appId)
    def repairHints = []
    if (updateRuleFailed) {
        repairHints << (isAdd
            ? "updateRule click was rejected after the local variable committed. The variable is created on the hub but the rule's action map will not pick it up until updateRule fires. Retry hub_set_rule(button='updateRule', confirm=true), or ${_rmRestoreClause(backup)} if the retry also fails."
            : "updateRule click was rejected after the local variable was removed. The variable is gone but the rule's action map will not re-evaluate until updateRule fires. Retry hub_set_rule(button='updateRule', confirm=true), or ${_rmRestoreClause(backup)} if the retry also fails.")
    }
    def variableShape = isAdd
        ? [name: varResult?.name, type: varResult?.type, value: varResult?.value]
        : [name: varResult?.name, deleted: varResult?.deleted == true]
    // Broken-after-delete (remove path): RM does NOT reliably refuse to delete a referenced
    // local. Live (RM 5.1 / fw 2.5.0.x), deleting a local that an action still references
    // SUCCEEDS at removing the variable and leaves the referencing action Broken. So a clean
    // delete (deleted:true) can co-exist with health.ok=false -- the variable is gone but the
    // rule is now broken. Detect that here and convert it into a self-consistent failure
    // envelope: the helper's deleted:true rides through (the delete really happened), but the
    // envelope reports success:false + partial:true with a SPECIFIC error + repairHint, so a
    // consumer is never handed deleted:true + error:null + a clean "removed" note while the
    // rule sits broken. The still-referenced -> partial branch in the helper still covers the
    // case where RM DOES refuse (may occur for expression/condition refs on some firmware).
    //
    // deleteBrokeRule fires on EVERY broken-after-successful-delete so the envelope is always
    // coherent (success:false must never ride with error:null). The pre-delete broken state
    // (brokenBefore, kept INTERNAL -- derived by the backup snapshot from the config it fetched
    // BEFORE the delete, no extra hub round-trip) only steers the WORDING: a rule that was
    // ALREADY broken beforehand must NOT be told the delete caused it (and that restoring fixes
    // it -- it would only undo the delete while the pre-existing break remains). brokenBefore:
    // false -> the delete caused it (causation wording); true -> pre-existing (do not blame the
    // delete, restore won't fix it); null -> indeterminate (softened, no causation claim).
    Boolean brokenBefore = (backup?.brokenBefore instanceof Boolean) ? (Boolean) backup.brokenBefore : null
    // brokenBefore is an internal wording signal only -- rule-health state does not belong on the
    // backup metadata object the envelope surfaces. Strip it before it can ride out on envelope.backup.
    if (backup instanceof Map) backup.remove("brokenBefore")
    boolean deleteBrokeRule = (!isAdd) && (varResult?.deleted == true) && (varResult?.success != false) && !_rmHealthGatePass(health)
    String deleteBrokeError = null
    if (deleteBrokeRule) {
        def markerHint = (health.brokenMarkers instanceof List && !health.brokenMarkers.isEmpty()) ? " (${health.brokenMarkers.join(', ')})" : ""
        if (brokenBefore == true) {
            // Pre-existing breakage -- removing this local did not cause it; restore won't fix it.
            deleteBrokeError = "Local variable '${varResult?.name}' was deleted; the rule is broken${markerHint}, but it was ALREADY broken before this delete (pre-existing -- removing this local did not cause it). A restore of the pre-delete baseline (${_rmRestoreClause(backup)}) undoes the delete but will NOT fix the pre-existing breakage; inspect the rule's broken action(s)/expression(s)."
            repairHints << "The rule was already broken before this delete (pre-existing). Undoing the delete (${_rmRestoreClause(backup)}) will NOT repair the pre-existing breakage -- inspect the rule's broken action(s)/expression(s)."
        } else if (brokenBefore == false) {
            // Clean before -- the delete caused the break; restore undoes it.
            deleteBrokeError = "Local variable '${varResult?.name}' was deleted, but that broke the action(s)/expression(s) that referenced it -- the rule is now broken${markerHint}. To undo, ${_rmRestoreClause(backup)}; or remove the references first and then retry the removal."
            repairHints << "The deleted local variable was still referenced; the rule is now broken. ${_rmRestoreClause(backup).capitalize()}, or remove the referencing action(s)/expression(s) and retry."
        } else {
            // Indeterminate baseline (pre-delete config unreadable) -- do not claim causation.
            deleteBrokeError = "The rule is broken after removing '${varResult?.name}'${markerHint} (could not determine whether it was already broken beforehand). To undo this delete, ${_rmRestoreClause(backup)}; or inspect the rule's action(s)/expression(s)."
            repairHints << "The rule is broken after this delete; whether it was already broken beforehand is unknown. To undo the delete, ${_rmRestoreClause(backup)}; or inspect the rule's action(s)/expression(s)."
        }
    }
    // Guard the add-path note the same way the remove-path note guards on deleted:
    // a non-committed add (hubRenderError / success:false) must NOT render a clean
    // "added with value X" -- that would contradict the failure envelope. addCommitted
    // tracks the helper's own commit verdict (success:false or hubRenderError:true means
    // the var never landed in state.allLocalVars).
    boolean addCommitted = isAdd && (varResult?.success != false) && (varResult?.hubRenderError != true)
    def note
    if (isAdd) {
        note = addCommitted
            ? "Local variable '${varResult?.name}' (${varResult?.type}) added with value ${varResult?.value}; updateRule ${updateRuleFailed ? 'FAILED -- variable may not be live' : 'fired'}."
            : "Local variable '${varResult?.name}' (${varResult?.type}) NOT added (commit miss -- see repairHints); updateRule ${updateRuleFailed ? 'FAILED -- variable may not be live' : 'fired (add unconfirmed)'}."
    } else if (deleteBrokeRule) {
        // Baseline-aware: only claim the delete broke the rule when it was clean beforehand.
        def brokeClause = (brokenBefore == false)
            ? "a reference to it broke the rule"
            : (brokenBefore == true
                ? "the rule is broken (it was already broken beforehand -- not caused by this delete)"
                : "the rule is broken (cause indeterminate)")
        note = "Local variable '${varResult?.name}' was deleted but ${brokeClause} (see error/repairHints); updateRule ${updateRuleFailed ? 'FAILED -- removal may not be live' : 'fired'}."
    } else {
        note = "Local variable '${varResult?.name}' ${varResult?.deleted == true ? 'removed' : 'NOT removed (verify miss -- see repairHints)'}; updateRule ${updateRuleFailed ? 'FAILED -- removal may not be live' : (varResult?.deleted == true ? 'fired' : 'fired (removal unconfirmed)')}."
    }
    def envelope = [
        success: (varResult?.success != false) && _rmHealthGatePass(health) && !updateRuleFailed,
        // committed-but-not-clean -> partial:true: on the broken-after-delete path the delete
        // committed (deleted:true) but the rule is not healthy, so the operation is partial.
        partial: (varResult?.partial == true) || updateRuleFailed || deleteBrokeRule,
        appId: appId,
        backup: backup,
        variable: variableShape,
        // On a broken-after-delete the helper's own error is null (the delete succeeded), so
        // synthesize the specific error here; otherwise pass the helper's error through.
        error: deleteBrokeError ?: varResult?.error,
        updateRuleFailed: updateRuleFailed,
        variableNotLive: variableNotLive,
        updateRuleError: updateRuleError,
        repairHints: ((varResult?.repairHints as List) ?: []) + repairHints,
        health: health,
        note: note
    ]
    if (isAdd) {
        // add-only diagnostic slots: the moreVar wizard tracks per-field apply/skip and
        // can report a hubRenderError when varValue persists but never renders into appState.
        envelope.settingsApplied = varResult?.settingsApplied
        envelope.settingsSkipped = varResult?.settingsSkipped
        envelope.hubRenderError = varResult?.hubRenderError
    }
    return envelope
}

// Clear RM's residual atomicState.predCapabs via the "ghost ifThen" workaround.
//
// Shared by _rmAddRequiredExpression (called after walkConds+hasRule commit) and
// any future flow that needs to flush the condition-builder accumulator without
// leaving a visible action in the rule.
//
// Sequence: open a condActs/getIfThen slot on selectActions, immediately cancel
// it (actionCancel -- NOT actionDone), then nav doActPage->selectActions->mainPage.
// actSubType=getIfThen triggers RM's ifThen initializer, which zeroes out
// predCapabs; actionCancel discards the slot so no action lands in the rule.
//
// Caller supplies a short label for log attribution (e.g. "addRequiredExpression").
//
// Mechanistic rationale: writing actSubType=getIfThen invokes RM's condition-builder
// initializer (used by both STPage and doActPage condActs flow), which zeroes out
// predCapabs. actionCancel then exits WITHOUT merging the new slot's state back into
// the rule, so the clean predCapabs persists. Verified live via probe diag variants Y
// and Z (2026-05-01/02). DO NOT remove or simplify without re-running full probe matrix.
private void _rmClearPredCapabsViaGhostIfThen(Integer appId, String caller) {
    // Thread a request-scoped page cache so the repeated doActPage reads inside this fixed
    // sequence reuse the page a prior step already fetched/echoed instead of re-GETting it.
    // This does NOT change the wire POST sequence (same steps, same order, same bodies -- the
    // probe-matrix-verified shape above is untouched); it only drops redundant verify-GETs.
    // Clicks/navs invalidate the cache (they genuinely re-render + bump app.version), so the
    // only HITs are provably-current reads: the actType write reusing the slot page step 2
    // just fetched, the actionCancel version read reusing the actSubType echo, and the
    // mainPage nav's version read reusing the selectActions render the prior nav returned.
    Map cache = [:]

    // Open a new action slot on selectActions.
    _rmClickAppButton(appId, "N", "doActN", "selectActions", cache)

    // Discover the index RM allocated (same pattern as _rmAddAction).
    def ghostCfg = _rmFetchConfigJson(appId, "doActPage", cache)
    def ghostSchema = _rmCollectInputSchema(ghostCfg?.configPage)
    def ghostActTypeField = ghostSchema?.keySet()?.find { it.toString() ==~ /^actType\.\d+$/ }
    def ghostIdx = 1
    if (ghostActTypeField) {
        def m = (ghostActTypeField.toString() =~ /^actType\.(\d+)$/)
        if (m.matches()) ghostIdx = m[0][1] as Integer
    }

    // Write condActs + getIfThen to trigger RM's ifThen initializer,
    // which re-initializes atomicState.predCapabs with a clean state.
    // actSubType=getIfThen is the minimum required step -- actType=condActs
    // alone does not trigger the clear (Variant Z: condActs alone still broken).
    def ghostApplied = []
    def ghostSkipped = []
    _rmWriteSettingOnPage(appId, "doActPage", "actType.${ghostIdx}", "condActs", ghostApplied, null, ghostSkipped, cache)
    // actType=condActs reveals actSubType via submitOnChange, and that picker's POST echo can
    // lag one render (no actSubType in the returned schema yet -- same class as the STPage
    // cond/oper picker bug). A cache HIT is trusted as "provably current", so caching that
    // lagged echo would make the next write skip as not_in_schema (_rmWriteSettingOnPage:4091)
    // and silently leave predCapabs uncleared. Drop the cache so the actSubType write reads the
    // settled page fresh.
    _rmCacheInvalidate(cache, appId)
    _rmWriteSettingOnPage(appId, "doActPage", "actSubType.${ghostIdx}", "getIfThen", ghostApplied, null, ghostSkipped, cache)
    if (ghostSkipped.any { it?.key?.toString()?.startsWith("actSubType") && it?.reason == "not_in_schema" }) {
        mcpLog("warn", "rm-native", "${caller}: ghost ifThen actSubType=getIfThen write was SKIPPED not_in_schema (${ghostSkipped}) for app ${appId} -- the actType->actSubType reveal echo lagged past the fresh re-read; predCapabs may NOT be cleared, so a later non-expression action could render under IF(Broken Condition).")
    }

    // Cancel WITHOUT clicking actionDone. This discards the slot before
    // commit so no action is added to the rule, but the re-initialized
    // predCapabs state (from the getIfThen init) persists.
    // DO NOT replace with actionDone -- that bakes an empty IF() block.
    _rmClickAppButton(appId, "actionCancel", null, "doActPage", cache)

    // Return to selectActions to finalize the cancel, then navigate
    // back to mainPage. Without the mainPage nav, the app's server-side
    // routing context stays at selectActions. When the user later opens
    // the browser and clicks "Manage Conditions" (STPage href on mainPage),
    // RM sees a conflicting editAct context and renders "Required Fields
    // missing or not passing validation" on STPage. Without this nav,
    // STPage shows a validation error after addRequiredExpression completes;
    // with it, STPage opens cleanly.
    def navBack = _rmNavigateToPage(appId, "doActPage", "selectActions", 0, "name", null, cache, null, false, true)
    // A plain name-nav render carries no param-state, so it equals a live selectActions GET;
    // _rmNavigateToPage deliberately doesn't self-store its render, so cache it here for the
    // mainPage nav's version read (drops one more re-GET).
    if (navBack?.configPage != null) _rmCacheStore(cache, appId, "selectActions", navBack)
    _rmNavigateToPage(appId, "selectActions", "mainPage", 0, "name", null, cache, null, false, true)

    mcpLog("info", "rm-native", "${caller}: ghost ifThen clear fired for app ${appId} (clears atomicState.predCapabs without adding an action)")
}

// Just-in-time predCapabs clear. addRequiredExpression Step 4b no longer pays the ~9-round-trip ghost
// ifThen up front (it would make the relay-504-prone RE build heavier for nothing when no action
// follows); instead it flags the rule in atomicState.predClearPending. _rmAddAction calls this once its
// argument checks pass, before its first wizard write: if the rule is flagged, fire the SAME ghost
// ifThen now -- before the action lands -- so the
// action isn't wrapped in IF(**Broken Condition**), then drop the flag so it runs at most once.
// Best-effort + idempotent: a clean predCapabs re-clears harmlessly, and a failed clear degrades to
// the pre-deferral worst case (a possible IF(Broken Condition) wrap, surfaced as a warn).
private void _rmRunPendingPredCapabsClear(Integer appId) {
    def pending = _rmPendingPredClearSnapshot()
    def observedGeneration = pending.get(appId.toString())
    if (!observedGeneration) return
    try {
        _rmClearPredCapabsViaGhostIfThen(appId, "addAction (deferred from addRequiredExpression)")
    } catch (Exception e) {
        mcpLog("warn", "rm-native", "addAction: deferred predCapabs clear (ghost ifThen) failed for app ${appId} (${e.message ?: e.toString()}) -- this action may render under IF(**Broken Condition**); verify rule render or restore the pre-edit backup if one was taken (restoreHint)")
    }
    try { _rmDropPredClearPending(appId, observedGeneration) }
    catch (Exception cleanupError) {
        mcpLog("warn", "rm-native", "addAction: deferred predicate-clear bookkeeping failed for app ${appId}: ${cleanupError.message}; the recovery record is retained for a later attempt")
    }
}

void _rmMarkPredClearPending(Integer appId) {
    synchronized (PRED_CLEAR_STORES) {
        Map pending = _rmPendingPredClearSnapshot()
        // An inventory fetched before this generation cannot discard this intent.
        pending.put(appId.toString(), java.util.UUID.randomUUID().toString())
        _rmCommitPredClearPending(pending)
    }
}

// Drop a deleted rule's deferred predCapabs-clear flag without firing the clear, so it does not
// linger in atomicState after the rule is gone.
private void _rmDropPredClearPending(Integer appId) {
    synchronized (PRED_CLEAR_STORES) {
        Map pending = _rmPendingPredClearSnapshot()
        if (pending.remove(appId.toString()) != null) _rmCommitPredClearPending(pending)
    }
}

// A clear may finish after a concurrent rule edit published fresh recovery intent.
private void _rmDropPredClearPending(Integer appId, Object observedGeneration) {
    if (observedGeneration == null) return
    synchronized (PRED_CLEAR_STORES) {
        Map pending = _rmPendingPredClearSnapshot()
        String key = appId.toString()
        if (pending.get(key) != observedGeneration) return
        pending.remove(key)
        _rmCommitPredClearPending(pending)
    }
}

// Low-level reveal-step primitive for RM 5.1 progressive-disclosure wizard pages.
//
// Snapshot field names on a page, execute a trigger closure (button click or
// setting write), re-fetch the page, then find and return the newly-revealed
// input field whose name matches the given Java regex pattern.
//
// Callers rely on this when RM's next field name is firmware-assigned and must
// be schema-discovered rather than hardcoded -- for example, the cpType<P>.N
// slot allocated by moreParams, the xVar<digits>.N enum revealed after a
// uVar toggle, or any per-capability sub-picker that appears only after the
// capability selector commits. Returns the newly-revealed field if one appears;
// falls back to any matching field in the post-fetch schema (covers static schemas
// and always-visible-field paths). Returns null if no matching field exists at all.
//
// The fallback path (revealedNew ?: revealedAny) exists **because** some firmware
// versions expose fields unconditionally in the schema regardless of preceding writes
// (e.g. firmware that does not use progressive disclosure for a given capability).
// Without the fallback, a hub where the field is always visible would return null and
// fail-loud even though the field is present and writable. The primary path (revealedNew)
// handles the normal progressive-disclosure case; the fallback keeps the walker
// functional on static-schema firmware paths.
//
// @param appId    Rule Machine app ID
// @param page     Wizard page name (e.g. "doActPage", "STPage")
// @param pattern  Java regex the target field name must match
// @param trigger  Closure that causes the field to appear (write or click)
// @return         Map with three keys:
// input: matching input Map (or null if no match)
// visibleNames: List<String> of all input names in the post-trigger schema
// fallbackToExisting: Boolean -- true when the match came from the
// fallback "any matching field" path (revealedAny only, not
// revealedNew). Signals that the field was already visible BEFORE
// the trigger ran; callers tracking progressive-disclosure vs
// static-schema behaviour read this to emit informational
// sentinels. Does NOT imply failure.
// setVariable reveal helpers -- shared across the three schema-gated source modes
// (sourceVariable / fromDevice / math). Each mode writes its selector (numOp.<N>, or
// valStringOp.<N> for a String copy) then walks a sequence of schema-gated fields; these collapse
// the precondition check, the fetch-and-find-or-throw idiom, and the constant-vs-variable operand
// write that were triplicated across the modes.

// Precondition: the mode's selector (opField.<N>) must have landed for its gated fields to appear.
// If that write was skipped, attribute the failure precisely rather than letting the downstream
// reveal-miss blame a firmware gap. No-op when the selector landed.
private void _rmAssertSelectorLanded(int idx, List applied, List skipped, String modeLabel, String opField = "numOp", String capLbl = "setVariable") {
    def selectorKey = "${opField}.${idx}".toString()
    if (!applied.contains(selectorKey) && skipped.any { it?.key?.toString() == selectorKey }) {
        def selectorSkip = skipped.find { it?.key?.toString() == selectorKey }
        throw new IllegalArgumentException("${capLbl}: ${opField}.${idx} write did not land (reason: ${selectorSkip?.reason}) -- ${modeLabel} reveal cannot proceed. Verify the doActPage schema includes ${opField}.${idx} at this action position.")
    }
}

// Fetch the doActPage schema, find the named input, and return its input Map -- or throw a
// fail-loud reveal-miss error naming the role and the visible field names. roleLabel is the
// human phrase for the error (e.g. "device picker", "second-operand field"); the caller supplies
// the full "<role> '<field>' was not revealed ..." suffix via missSuffix so each mode keeps its
// existing wording. Returns the input Map (never null).
private Map _rmRevealedInputOrThrow(Integer appId, String fieldName, String missSuffix, String capLbl = "setVariable") {
    def cfg = _rmFetchConfigJson(appId, "doActPage")
    def inputs = (cfg?.configPage?.sections ?: []).collectMany { sec -> (sec?.input ?: []) }
    def input = inputs.find { it?.name?.toString() == fieldName }
    if (!input) {
        def visibleNames = inputs.collect { it?.name?.toString() }.findAll { it }.join(', ') ?: "(none -- schema returned empty)"
        throw new IllegalArgumentException("${capLbl}: ${missSuffix} Visible fields: ${visibleNames}")
    }
    return input
}

// Write one math operand: a Number becomes the "(constant)" sentinel in the xVar slot plus the
// literal in the revealed valConst slot; a variable name is written to the xVar slot directly.
// The chosen xVar option is validated against the revealed enum first (assertInEnum). roleLabel
// describes the operand ("first operand" / "second operand") for error wording. constField is the
// matching valConst slot name ("valConst.<N>" / "valConst2.<N>"). assertInEnum is passed in so the
// closure shares the math block's enum-validation/error vocabulary.
private void _rmWriteMathOperand(Integer appId, int idx, Object operandValue, String xVarField,
                                 String constField, String roleLabel, Closure assertInEnum,
                                 Object xVarInput, List applied, List skipped) {
    def constSentinel = "(constant)"
    if (operandValue instanceof Number) {
        assertInEnum(xVarField, xVarInput, constSentinel, roleLabel)
        _rmWriteSettingOnPage(appId, "doActPage", xVarField, constSentinel, applied, null, skipped)
        // valConst/valConst2 are numeric VALUE fields -- written verbatim (a decimal like 5.5
        // lands as "5.5"); unlike a device id they must NOT be integer-normalized.
        _rmRevealedInputOrThrow(appId, constField,
            "math: ${roleLabel == 'first operand' ? 'first' : 'second'}-constant field '${constField}' was not revealed after selecting (constant) for action ${idx}.")
        _rmWriteSettingOnPage(appId, "doActPage", constField, operandValue, applied, null, skipped)
    } else {
        assertInEnum(xVarField, xVarInput, operandValue.toString(), roleLabel)
        _rmWriteSettingOnPage(appId, "doActPage", xVarField, operandValue.toString(), applied, null, skipped)
    }
}

// Extracted from _rmAddAction to keep it under the JVM's 64KB per-method bytecode limit.
private void _rmWriteWaitEventRows(Integer appId, Integer idx, Map actionSpec, String actSubType, List applied, List skipped) {
    // Event rows are tCapab-<N>/tDev-<N>/tstate-<N> (a Mode event uses a modesX-<N> picker and
    // no tDev/tstate); hasAll commits a row and anotherWait reveals the next.
    if (actSubType == "getWaitEvents") {
        def events = actionSpec.events as List
        // The first slot number comes from an internal RM counter, not the action index, so it
        // is read from the one unfilled tCapab-<N> the subtype write reveals (after a tick).
        Integer baseN = null
        def baseInputs = null
        def baseCapField = null
        for (int attempt = 0; attempt < 4; attempt++) {
            def baseCfg = _rmFetchConfigJson(appId, "doActPage")
            baseInputs = (baseCfg?.configPage?.sections ?: []).collectMany { it?.input ?: [] }
            baseCapField = baseInputs.find { it?.name?.toString() ==~ /^tCapab-\d+$/ }
            if (baseCapField) {
                baseN = (baseCapField.name.toString().replaceFirst(/^tCapab-/, "")) as Integer
                break
            }
            if (attempt < 3) pauseExecution(250)
        }
        if (baseN == null) {
            throw new IllegalStateException("waitEvents: no tCapab-<N> event-capability slot appeared in doActPage schema after the getWaitEvents subtype write for app ${appId} action ${idx}; the wizard did not expose the first event-capability field.")
        }
        events.eachWithIndex { evRaw, evIdx ->
            if (!(evRaw instanceof Map)) {
                throw new IllegalArgumentException("waitEvents.events[${evIdx}] is not a Map")
            }
            def ev = evRaw as Map
            def evCap = ev.capability?.toString()?.trim()
            if (!evCap) throw new IllegalArgumentException("waitEvents.events[${evIdx}].capability is required")
            def n = baseN + evIdx
            // The first event reuses the base fetch; later ones can race the anotherWait commit.
            def cfg = null
            def inputs = (evIdx == 0) ? baseInputs : null
            def capInput = (evIdx == 0) ? baseCapField : null
            for (int attempt = 0; attempt < 4 && !capInput; attempt++) {
                cfg = _rmFetchConfigJson(appId, "doActPage")
                inputs = (cfg?.configPage?.sections ?: []).collectMany { it?.input ?: [] }
                capInput = inputs.find { it?.name?.toString() == "tCapab-${n}".toString() }
                if (capInput) break
                if (attempt < 3) pauseExecution(250)
            }
            if (!capInput) {
                throw new IllegalStateException("waitEvents: tCapab-${n} not in doActPage schema for event ${evIdx} after retry; previous event's hasAll click may have failed. Schema seen: ${inputs.collect { it?.name }.findAll { it }.join(', ')}")
            }
            def opts = (capInput.options ?: []) as List
            def canon = opts.find { it.toString().equalsIgnoreCase(evCap) }
            if (!canon) {
                def suggestion = _rmSuggestTriggerCapability(evCap, opts)
                def didYouMean = suggestion ? " Did you mean '${suggestion}'?" : ""
                throw new IllegalArgumentException("waitEvents.events[${evIdx}].capability '${evCap}' is not in Rule Machine's list on this hub (RM lists a capability only when the hub has a device or app for it).${didYouMean} RM offers: ${opts.collect { it.toString() }.sort().join(', ')}")
            }
            // Mode input checks run before the tCapab write so a bad mode leaves no row.
            def isMode = canon.toString().equalsIgnoreCase("Mode")
            def modeIds = null
            if (isMode) {
                if (ev.deviceIds != null) {
                    throw new IllegalArgumentException("waitEvents.events[${evIdx}]: a Mode event is hub-state, not device-based -- remove 'deviceIds'.")
                }
                if (ev.modeIds != null) {
                    modeIds = _rmResolveModeIds((ev.modeIds instanceof List) ? (ev.modeIds as List) : [ev.modeIds])
                } else if (ev.state != null) {
                    modeIds = _rmResolveModeIds((ev.state instanceof List) ? (ev.state as List) : [ev.state])
                }
                if (!modeIds) {
                    throw new IllegalArgumentException("waitEvents.events[${evIdx}]: Mode event requires a non-empty 'state' (mode name or list of names) or 'modeIds' (list of mode IDs).")
                }
            }
            _rmWriteSettingOnPage(appId, "doActPage", "tCapab-${n}", canon, applied, null, skipped)
            if (isMode) {
                // The mode picker's exact name is firmware-assigned, so it is discovered.
                def modeField = null
                def modeOptions = null
                def modeInputs = []
                for (int attempt = 0; attempt < 4; attempt++) {
                    def modeCfg = _rmFetchConfigJson(appId, "doActPage")
                    modeInputs = (modeCfg?.configPage?.sections ?: []).collectMany { it?.input ?: [] }
                    def modeInput = modeInputs.find { it?.name?.toString()?.matches("modes[A-Za-z]*-${n}".toString()) }
                    if (modeInput) { modeField = modeInput.name.toString(); modeOptions = modeInput.options; break }
                    if (attempt < 3) pauseExecution(250)
                }
                if (!modeField) {
                    throw new IllegalStateException("waitEvents.events[${evIdx}]: Mode picker (expected a modesX-${n} family field) did not appear after writing tCapab-${n}=Mode. Schema seen: ${modeInputs.empty ? "(none returned)" : modeInputs.collect { it?.name }.findAll { it }.join(', ')}")
                }
                // _rmResolveModeIds passes integer ids through unvalidated; check them against
                // the picker's options, or the hub's modes when the picker reveals empty.
                def pickerIds = []
                if (modeOptions instanceof Map) {
                    pickerIds = (modeOptions as Map).keySet().collect { it?.toString() }
                } else if (modeOptions instanceof List) {
                    (modeOptions as List).each { o ->
                        if (o instanceof Map) { if (o.id != null) pickerIds << o.id.toString() }
                        else if (o != null) pickerIds << o.toString()
                    }
                }
                def validIds
                def validSource
                if (!pickerIds.isEmpty()) {
                    validIds = pickerIds.collect { it?.toString() }
                    validSource = "${modeField} picker"
                } else {
                    validIds = (location?.modes ?: []).collect { it?.id?.toString() }.findAll { it }
                    validSource = "hub modes"
                }
                def badIds = modeIds.findAll { !validIds.contains(it?.toString()) }
                if (!badIds.isEmpty()) {
                    throw new IllegalArgumentException("waitEvents.events[${evIdx}]: mode ID(s) ${badIds.join(', ')} not offered by the ${validSource}. Valid IDs: ${validIds.sort().join(', ')}")
                }
                _rmWriteSettingOnPage(appId, "doActPage", modeField, modeIds, applied, null, skipped)
            } else {
                if (ev.deviceIds != null) {
                    _rmWriteSettingOnPage(appId, "doActPage", "tDev-${n}", ev.deviceIds, applied, null, skipped)
                }
                if (ev.state != null) {
                    _rmWriteSettingOnPage(appId, "doActPage", "tstate-${n}", ev.state, applied, null, skipped)
                }
            }
            // stays-<N>=true reveals dash-indexed SHours-/SMins-/SSecs-<N> (the trigger's are
            // undashed); all three are written so the total is clean.
            def andStays = ev.andStays
            if (andStays != null && andStays != true && !(andStays instanceof Map)) {
                throw new IllegalArgumentException("waitEvents.events[${evIdx}].andStays must be boolean true or a {hours,minutes,seconds} map; got '${andStays}'. A numeric or other value is silently ignored by RM -- pass andStays:true for a zero dwell or andStays:{seconds:5} for a duration.")
            }
            if (andStays == true || andStays instanceof Map) {
                _rmWriteSettingOnPage(appId, "doActPage", "stays-${n}", true, applied, "bool", skipped)
                def dur = (andStays instanceof Map) ? (andStays as Map) : [:]
                _rmWriteSettingOnPage(appId, "doActPage", "SHours-${n}", dur.hours != null ? dur.hours : 0, applied, null, skipped)
                _rmWriteSettingOnPage(appId, "doActPage", "SMins-${n}", dur.minutes != null ? dur.minutes : 0, applied, null, skipped)
                _rmWriteSettingOnPage(appId, "doActPage", "SSecs-${n}", dur.seconds != null ? dur.seconds : 0, applied, null, skipped)
            }
            // anotherWait needs stateAttribute=anotherWait or RM accepts the click and reveals
            // nothing.
            _rmClickAppButton(appId, "hasAll", null, "doActPage")
            if (evIdx < events.size() - 1) {
                _rmClickAppButton(appId, "anotherWait", "anotherWait", "doActPage")
            }
        }
    }
}

// Extracted from _rmAddAction to keep it under the JVM's 64KB per-method bytecode limit.
private void _rmWriteActionDelayModifier(Integer appId, Integer idx, Map actionSpec, List applied, List skipped) {
    if (actionSpec.delay instanceof Map && !actionSpec.__delayHandledForWaitRule) {
        def d = actionSpec.delay as Map
        if (d.variable != null) {
            _rmWriteSettingOnPage(appId, "doActPage", "delayAct.${idx}", "variable", applied, null, skipped)
            _rmWriteSettingOnPage(appId, "doActPage", "xVarD.${idx}", d.variable, applied, null, skipped)
        } else {
            _rmWriteSettingOnPage(appId, "doActPage", "delayAct.${idx}", "hrs:min:sec", applied, null, skipped)
            if (d.hours != null)   _rmWriteSettingOnPage(appId, "doActPage", "delayHor.${idx}", d.hours, applied, null, skipped)
            if (d.minutes != null) _rmWriteSettingOnPage(appId, "doActPage", "delayMin.${idx}", d.minutes, applied, null, skipped)
            if (d.seconds != null) _rmWriteSettingOnPage(appId, "doActPage", "delaySec.${idx}", d.seconds, applied, null, skipped)
        }
        if (d.random != null)     _rmWriteSettingOnPage(appId, "doActPage", "randomAct.${idx}", d.random, applied, null, skipped)
        if (d.cancelable != null) _rmWriteSettingOnPage(appId, "doActPage", "cancelAct.${idx}", d.cancelable, applied, null, skipped)
    }
}

// Extracted from _rmAddAction to keep it under the JVM's 64KB per-method bytecode limit.
// runCommand's three pickers each reveal the next: myCapab.<N> (RM's capability list) reveals
// devices.<N>, and cCmd.<N> is an enum RM builds from the selected devices' commands, so it only
// appears once RM accepted at least one of them for the chosen capability. The capability and
// the command are checked against RM's own lists; a miss throws inside the editor try-block, and
// the caller's editor cleanup decides what happens to the row (an add removes it; modifyAction closes
// the editor with actionDone and keeps the committed row). Fills and returns `written` as each key lands.
private Map _rmWriteRunCommandFields(Integer appId, Integer idx, Map actionSpec, List applied, List skipped, Map written = [:]) {
    String capKey = "myCapab.${idx}".toString()
    def capInput = _rmRevealedInputOrThrow(appId, capKey,
        "the capability picker '${capKey}' was not revealed on action ${idx}.".toString(), "runCommand")
    String capFilter = _rmResolveRunCommandCapability(actionSpec.capabilityFilter, _rmReadPickerOptionLabels(capInput))
    _rmWriteSettingOnPage(appId, "doActPage", capKey, capFilter, applied, null, skipped)
    written.put(capKey, capFilter)
    String cmd = actionSpec.command.toString().trim()
    boolean useLast = actionSpec.useLastEventDevice == true
    String devicesKey = "devices.${idx}".toString()
    if (actionSpec.deviceIds != null) {
        _rmWriteSettingOnPage(appId, "doActPage", devicesKey, actionSpec.deviceIds, applied, null, skipped)
        written.put(devicesKey, actionSpec.deviceIds)
    }
    // A device list often leaves the page unchanged; only a missing field means RM showed no picker.
    if (!useLast && skipped.any { it instanceof Map && it.key?.toString() == devicesKey && it.reason == "not_in_schema" }) {
        throw new IllegalArgumentException("runCommand: RM revealed no device picker for capability '${capFilter}' on action ${idx}, so deviceIds could not be written.")
    }
    String cmdKey = "cCmd.${idx}".toString()
    def ids = (actionSpec.deviceIds instanceof List) ? (actionSpec.deviceIds as List).join(", ") : actionSpec.deviceIds?.toString()
    String wrote = useLast ? "useLastDev=true" : "deviceIds [${ids}]".toString()
    def cmdInput = _rmRevealedInputOrThrow(appId, cmdKey,
        "the command picker '${cmdKey}' was not revealed after writing ${wrote} for capability '${capFilter}' on action ${idx} -- RM lists commands only for devices it accepted into that capability's picker (check that the devices report '${capFilter}').".toString(),
        "runCommand")
    List opts = _rmReadPickerOptionStrings(cmdInput)
    if (!opts) {
        throw new IllegalArgumentException("runCommand: RM found no commands for the selected device(s) on action ${idx} (its picker shows 'No commands found').")
    }
    String canonical = opts.find { it == cmd } ?: opts.find { it?.equalsIgnoreCase(cmd) }
    if (canonical == null) {
        throw new IllegalArgumentException("runCommand: '${cmd}' is not a command of the selected device(s) on action ${idx}. RM offers: ${opts.sort().join(', ')}.")
    }
    _rmWriteSettingOnPage(appId, "doActPage", cmdKey, canonical, applied, null, skipped)
    written.put(cmdKey, canonical)
    return written
}

private void _rmWriteRunCommandParams(Integer appId, Integer idx, Map actionSpec, List applied, List skipped) {
    // Per parameter: a moreParams click allocates slot P (RM-assigned, read from the revealed
    // cpType<P>.<N>); cpType reveals uVar<P>/cpVal<P>; uVar<P>=true swaps cpVal for the
    // xVar<P> variable enum.
    if (actionSpec.__runCommandExtraParams instanceof List && !actionSpec.__runCommandExtraParams.isEmpty()) {
        actionSpec.__runCommandExtraParams.eachWithIndex { p, paramIdx ->
            def pType, pValue, pVariable
            if (p instanceof Map) {
                pType = p.type
                pValue = p.value
                pVariable = p.variable
            } else {
                pType = "string"; pValue = p
            }
            if (pType != null) {
                def t = pType.toString().toLowerCase()
                if (!(t in ["string", "number", "decimal"])) {
                    throw new IllegalArgumentException("runCommand parameter type '${pType}' invalid -- must be 'string', 'number', or 'decimal'")
                }
                pType = t
            } else {
                pType = "string"
            }

            def cpTypeReveal = _rmRevealStep(appId, "doActPage", "cpType\\d+\\.${idx}".toString(), {
                _rmClickAppButton(appId, "moreParams", null, "doActPage")
            })
            def newCpTypeField = cpTypeReveal.input?.name?.toString()
            if (!newCpTypeField) {
                mcpLog("warn", "rm-native", "runCommand[${actionSpec.command}]: moreParams click did not reveal a new cpType<P> field for action ${idx} param ${paramIdx + 1}; param skipped")
                skipped << [key: "param${paramIdx + 1}", reason: "moreParams_no_reveal"]
                return
            }
            def cpTypeBase = newCpTypeField.toString().replaceAll("\\.\\d+\$", "")
            def pNumStr = cpTypeBase.replaceAll("^cpType", "")

            _rmWriteSettingOnPage(appId, "doActPage", "${cpTypeBase}.${idx}".toString(), pType, applied, null, skipped)

            if (pVariable != null) {
                def uVarField = "uVar${pNumStr}.${idx}".toString()
                _rmWriteSettingOnPage(appId, "doActPage", uVarField, "true", applied, "bool", skipped)
                def xVarCfg = _rmFetchConfigJson(appId, "doActPage")
                def xVarField = "xVar${pNumStr}.${idx}".toString()
                def xVarInput = (xVarCfg?.configPage?.sections ?: []).collectMany { sec ->
                    (sec?.input ?: [])
                }.find { it?.name?.toString() == xVarField }
                if (!xVarInput) {
                    throw new IllegalArgumentException("runCommand: xVar field not revealed after enabling variable mode for param slot ${pNumStr} (expected '${xVarField}') -- hub may not support variable-sourced parameters for command '${actionSpec.command}'")
                }
                def xVarOpts = _rmReadPickerOptionStrings(xVarInput)
                if (!xVarOpts) {
                    throw new IllegalArgumentException("runCommand: xVar${pNumStr}.${idx} revealed but has no enumerable options -- cannot validate variable name '${pVariable}'. Hub may not expose variable list for command '${actionSpec.command}'")
                }
                if (!xVarOpts.any { it == pVariable.toString() }) {
                    throw new IllegalArgumentException("runCommand parameter variable '${pVariable}' is not in the hub variable enum for param slot ${pNumStr}. Available: ${xVarOpts.sort().join(', ')}")
                }
                _rmWriteSettingOnPage(appId, "doActPage", xVarField, pVariable.toString(), applied, null, skipped)
            } else if (pValue != null) {
                def cpValField = "cpVal${pNumStr}.${idx}".toString()
                _rmWriteSettingOnPage(appId, "doActPage", cpValField, pValue, applied, null, skipped)
            }
        }
    }
}

// fileAppend/fileDelete pick from RM's list of existing hub files. RM stores any other name but
// shows the picker empty and marks the rule broken once the action is edited (live-verified).
private void _rmRequireExistingHubFile(Integer appId, Integer idx, String field, fileName, String capLabel) {
    def wanted = "${field}.${idx}".toString()
    def input = (_rmFetchConfigJson(appId, "doActPage")?.configPage?.sections ?: [])
        .collectMany { sec -> sec?.input ?: [] }.find { it instanceof Map && it.name?.toString() == wanted }
    if (!(input?.options instanceof List)) return
    def names = (input.options as List).collectMany { o -> o instanceof Map ? (o.keySet() as List) : [o] }*.toString()
    if (!names.contains(fileName?.toString())) {
        throw new IllegalArgumentException("${capLabel}: '${fileName}' is not a file on the hub, and ${capLabel} only works on an existing file. Check the name with hub_list_files, or create the file first (fileWrite action or hub_write_file).")
    }
}

// A thrown add as a bulk item result, carrying the outcome flags the throw recorded (wizardStuck
// outranks rolledBack).
private Map _rmAddRefusalResult(Exception e) {
    def r = [success: false, error: (e.message ?: e.toString())] + _rmOutcomeKeys(e)
    if (r.wizardStuck == true) r.remove("rolledBack")
    return r
}

// A refused row's cleanup, then its condition's: the editor is closed or the row removed first (an
// open editor refuses a condition delete); the condition is dropped only when the row is gone, since
// a row left behind still binds it, and the refusal then says so.
private void _rmCancelRefusedActionEditorThenDropCondition(Integer appId, Integer idx, Exception refusal, Integer condIdx) {
    try {
        _rmCancelRefusedActionEditor(appId, idx, refusal)
    } catch (Exception reraised) {
        if (condIdx == null) throw reraised
        Map flags = _rmOutcomeFlags(reraised)
        boolean dropped = (flags.rolledBack == true && flags.wizardStuck != true) ? _rmDropSimpleCondition(appId, condIdx) : false
        _rmRethrowWithConditionNote(reraised, condIdx, dropped)
    }
}

// Roll back an action row whose build was refused. RM leaves the doActPage editor open on the
// row when a write is refused; without the rollback the next add reopens it pre-filled with its
// stale fields. Cleanup is VERIFIED, not fire-and-forget: the hub answers 200 to a click that does
// nothing, so the settings-checked rollback decides. On confirmed removal the refusal is re-raised
// flagged rolledBack (as a runtime failure when it was not a validation error). Otherwise an
// IllegalStateException flagged wizardStuck carries the refusal text (minus its not-touched claim)
// and a recovery that starts with actionCancel, because removeAction is refused while the open
// editor holds state.editAct.
private void _rmCancelRefusedActionEditor(Integer appId, Integer idx, Exception refusal,
                                          boolean condWizardOpen = false, String stuckWhat = null) {
    boolean cleaned = false
    try {
        cleaned = _rmRollbackInFlightAction(appId, idx, condWizardOpen)
    } catch (Exception rollbackExc) {
        mcpLog("warn", "rm-native", "_rmCancelRefusedActionEditor: rollback threw for app ${appId} action ${idx} (${rollbackExc.message ?: rollbackExc.toString()})")
    }
    if (cleaned) {
        String m = refusal.message ?: refusal.toString()
        if (_rmOutcomeFlags(refusal).rolledBack == true) throw refusal
        Map flags = _rmOutcomeFlags(refusal) + [rolledBack: true]
        if (!(refusal instanceof IllegalArgumentException)) throw _rmFail(new IllegalStateException("${m} The opened action row was rolled back.".toString(), refusal), flags)
        throw _rmFail(new IllegalArgumentException("${m.replace(' RM is not touched.', '')} The opened action row was rolled back.".toString(), refusal), flags)
    }
    String what = stuckWhat ?: "action ${idx}'s editor or row could not be confirmed removed after the refusal, and the next add can reopen it pre-filled".toString()
    mcpLog("warn", "rm-native", "_rmCancelRefusedActionEditor: app ${appId}: ${what}")
    throw _rmFail(new IllegalStateException("${(refusal.message ?: refusal.toString()).replace(" RM is not touched.", "").replace(" The opened action row was rolled back.", "")} [wizardStuck -- ${what}; first close the editor with hub_set_rule(button='actionCancel', pageName='doActPage', confirm=true), verify via hub_get_app_config(appId=${appId}), then remove any surviving row with hub_set_rule(removeAction:{index:${idx}}, confirm:true) or restore the pre-write backup]", refusal), _rmOutcomeFlags(refusal) + [wizardStuck: true])
}

// Extracted from _rmAddAction to keep it under the JVM's 64KB per-method bytecode limit.
private void _rmWriteSetVariableSourceModes(Integer appId, Integer idx, Map actionSpec, List applied, List skipped) {
    // Copy from a variable: the source enum (seen as xVar3.<N>) is revealed by the copy
    // selector and its slot number is discovered; xVarV/xVarD do not match \d+.
    if (actionSpec.__setVariableSourceVar != null) {
        def srcVar = actionSpec.__setVariableSourceVar.toString()
        def capLbl = actionSpec.__setVariableCapLabel ?: "setVariable"
        def copyOp = (actionSpec.__setVariableSourceOp ?: "numOp").toString()
        def copyMode = actionSpec.__setVariableSourceTypeUnread ? "source-variable (the variable list was unreadable, so the target type is unknown and numOp was assumed; a String target needs valStringOp)" : "source-variable"
        _rmAssertSelectorLanded(idx, applied, skipped, copyMode, copyOp, capLbl)
        def srcCfg = _rmFetchConfigJson(appId, "doActPage")
        def srcInputs = (srcCfg?.configPage?.sections ?: []).collectMany { sec -> (sec?.input ?: []) }
        def xVarMatches = srcInputs.findAll { inp ->
            inp?.name?.toString()?.matches("xVar\\d+\\.${idx}")
        }
        if (!xVarMatches) {
            def visibleNames = srcInputs.collect { it?.name?.toString() }.findAll { it }.join(', ') ?: "(none -- schema returned empty)"
            throw new IllegalArgumentException("${capLbl}: the source-variable field (xVar<n>.${idx}) did not appear after ${copyOp == 'valStringOp' ? 'valStringOp=Copy variable' : 'numOp=variable'} on action ${idx}. Visible fields: ${visibleNames}")
        }
        if (xVarMatches.size() > 1) {
            def allNames = xVarMatches.collect { it?.name?.toString() }.join(', ')
            throw new IllegalArgumentException("${capLbl}: schema contains ${xVarMatches.size()} candidate source-variable fields for action ${idx} (${allNames}); expected exactly one. Use rawSettings to write the correct field explicitly.")
        }
        def xVar3Input = xVarMatches[0]
        def xVar3Field = xVar3Input.name.toString()
        def xVar3Opts = _rmReadPickerOptionStrings(xVar3Input)
        if (xVar3Opts == null || xVar3Opts.isEmpty()) {
            throw new IllegalArgumentException("${capLbl}: revealed field '${xVar3Field}' has no enumerable options -- cannot validate sourceVariable '${srcVar}'. Hub may not expose the variable list at this action position.")
        }
        if (!xVar3Opts.any { it == srcVar }) {
            throw new IllegalArgumentException("${capLbl}: sourceVariable '${srcVar}' is not in the revealed enum for '${xVar3Field}'. Available: ${xVar3Opts.sort().join(', ')}. Retry with a listed sourceVariable.")
        }
        _rmWriteSettingOnPage(appId, "doActPage", xVar3Field, srcVar, applied, null, skipped)
        if (copyOp == "numOp") {
            // The UI stores valOffset 0; without it the rule throws on Long#plus at run time.
            _rmWriteSettingOnPage(appId, "doActPage", "valOffset.${idx}".toString(), 0, applied, null, skipped)
        }
    }

    // From a device attribute: numOp reveals customDev.<N>, the device reveals tCustomAttr.<N>
    // (filtered to that device's attributes).
    if (actionSpec.__setVariableFromDevice != null) {
        def capLbl = actionSpec.__setVariableCapLabel ?: "setVariable"
        def fd = actionSpec.__setVariableFromDevice
        def fdDeviceId = fd.deviceId.toString()
        def fdAttr = fd.attribute.toString()
        _rmAssertSelectorLanded(idx, applied, skipped, "device-attribute", "numOp", capLbl)
        def customDevField = "customDev.${idx}".toString()
        _rmRevealedInputOrThrow(appId, customDevField,
            "device picker '${customDevField}' was not revealed after writing numOp=device attribute for action ${idx} -- hub may not support read-from-device at this action position.")
        _rmWriteSettingOnPage(appId, "doActPage", customDevField, [fdDeviceId], applied, null, skipped)
        def tCustomAttrField = "tCustomAttr.${idx}".toString()
        def tCustomAttrInput = _rmRevealedInputOrThrow(appId, tCustomAttrField,
            "The attribute picker '${tCustomAttrField}' did not appear after device '${fdDeviceId}' on action ${idx}; check that the device exists.")
        def attrOpts = _rmReadPickerOptionStrings(tCustomAttrInput)
        if (attrOpts == null || attrOpts.isEmpty()) {
            throw new IllegalArgumentException("${capLbl} fromDevice: Rule Machine's attribute picker '${tCustomAttrField}' offers no attribute of device '${fdDeviceId}' for this variable's type, so '${fdAttr}' cannot be used.")
        }
        // RM stores the option verbatim, so the enum's casing is written.
        def canonicalAttr = attrOpts.find { it?.equalsIgnoreCase(fdAttr) }
        if (canonicalAttr == null) {
            throw new IllegalArgumentException("${capLbl} fromDevice: attribute '${fdAttr}' is not in the device's attribute enum for action ${idx}. Available: ${attrOpts.sort().join(', ')}")
        }
        _rmWriteSettingOnPage(appId, "doActPage", tCustomAttrField, canonicalAttr, applied, null, skipped)
    }

    // Variable math: numOp reveals xVar3.<N> and valMathOp.<N>; "(constant)" reveals valConst;
    // a binary operator reveals xVar4.<N> (and valConst2 for a constant).
    if (actionSpec.__setVariableMath != null) {
        def capLbl = actionSpec.__setVariableCapLabel ?: "setVariable"
        def m = actionSpec.__setVariableMath
        _rmAssertSelectorLanded(idx, applied, skipped, "variable-math", "numOp", capLbl)
        def optsOf = { inp -> _rmReadPickerOptionStrings(inp) }
        def assertInEnum = { String field, Object input, String wanted, String role ->
            def opts = optsOf(input)
            if (opts == null || opts.isEmpty()) {
                throw new IllegalArgumentException("${capLbl} math: revealed field '${field}' has no enumerable options -- cannot validate ${role} '${wanted}'. Hub may not expose the ${role} list at this action position.")
            }
            if (!opts.any { it == wanted }) {
                throw new IllegalArgumentException("${capLbl} math: ${role} '${wanted}' is not in the revealed enum for '${field}'. Available: ${opts.sort().join(', ')}")
            }
        }
        def mCfg = _rmFetchConfigJson(appId, "doActPage")
        def mInputs = (mCfg?.configPage?.sections ?: []).collectMany { sec -> (sec?.input ?: []) }
        def xVar3Field = "xVar3.${idx}".toString()
        def xVar3Input = mInputs.find { it?.name?.toString() == xVar3Field }
        def valMathOpField = "valMathOp.${idx}".toString()
        def valMathOpInput = mInputs.find { it?.name?.toString() == valMathOpField }
        if (!xVar3Input || !valMathOpInput) {
            def visibleNames = mInputs.collect { it?.name?.toString() }.findAll { it }.join(', ') ?: "(none -- schema returned empty)"
            throw new IllegalArgumentException("${capLbl}: the variable-math fields ('${xVar3Field}', '${valMathOpField}') did not appear on action ${idx}. Visible fields: ${visibleNames}")
        }
        assertInEnum(valMathOpField, valMathOpInput, m.op.toString(), "operator")
        _rmWriteMathOperand(appId, idx, m.left, xVar3Field, "valConst.${idx}".toString(),
            "first operand", assertInEnum, xVar3Input, applied, skipped)
        _rmWriteSettingOnPage(appId, "doActPage", valMathOpField, m.op.toString(), applied, null, skipped)
        if (_rmMathBinaryOps().contains(m.op.toString())) {
            def xVar4Field = "xVar4.${idx}".toString()
            def xVar4Input = _rmRevealedInputOrThrow(appId, xVar4Field,
                "math: second-operand field '${xVar4Field}' was not revealed after writing binary operator '${m.op}' for action ${idx}.")
            _rmWriteMathOperand(appId, idx, m.right, xVar4Field, "valConst2.${idx}".toString(),
                "second operand", assertInEnum, xVar4Input, applied, skipped)
        }
    }
}

private Map _rmRevealStep(Integer appId, String page, String pattern, Closure trigger, Map cache = null) {
    def preCfg = _rmFetchConfigJson(appId, page, cache)
    def preNames = (preCfg?.configPage?.sections ?: []).collectMany { sec ->
        (sec?.input ?: []).collect { it?.name?.toString() }
    }.findAll { it } as Set
    trigger.call()
    def postCfg = _rmFetchConfigJson(appId, page, cache)
    def postInputs = (postCfg?.configPage?.sections ?: []).collectMany { sec ->
        (sec?.input ?: [])
    }
    // Prefer newly-revealed (the typical case) but fall back to any matching field
    // in postInputs (covers static schemas and always-visible-field paths).
    def revealedNew = postInputs.find { inp ->
        def n = inp?.name?.toString()
        n && n.matches(pattern) && !preNames.contains(n)
    }
    def revealedAny = postInputs.find { inp ->
        def n = inp?.name?.toString()
        n && n.matches(pattern)
    }
    def revealed = revealedNew ?: revealedAny
    // fallbackToExisting: the field was already visible before the trigger (normal on a static
    // schema; informational for the walker). postInputs lets a caller read a sibling's options
    // from the same fetch.
    def fallbackToExisting = (revealedNew == null) && (revealedAny != null)
    return [input: revealed, visibleNames: postInputs.collect { it?.name?.toString() }.findAll { it },
            postInputs: postInputs, fallbackToExisting: fallbackToExisting]
}

// Condition-field writer for RM 5.1's wizard pages (STPage and doActPage).
//
// Handles the full field-write sequence for a single plain condition after the
// capability index (cIdx) has been discovered from the page schema. Dispatches
// to a per-capability reveal sequence (each modelled as a chain of _rmRevealStep
// calls that expose the next field only after the preceding write commits), then
// writes optional negation and raw-settings overrides, and clicks hasAll to seal
// the slot.
//
// The per-capability sequences (static bounds: Mode=2, Between-two-times=4, Variable=3,
// Custom Attribute=2, Device-relative bounded by firmware-revealed fields):
// Mode              -- rCapab -> re-fetch -> discover modes<N> picker -> write IDs -> hasAll
// Between two times -- rCapab -> re-fetch -> startType (clock|sunrise|sunset) ->
// re-fetch -> start field -> re-fetch -> endType -> re-fetch ->
// end field -> hasAll
// Variable          -- rCapab -> re-fetch -> discover variable picker -> write name ->
// re-fetch -> RelrDev_<N> -> re-fetch -> state_<N> -> hasAll
// (Boolean var) state_<N> true/false directly, no comparator field -> hasAll
// Custom Attribute  -- rCapab -> rDev_<N> -> rCustomAttr_<N> -> re-fetch ->
// (free attr) RelrDev_<N> -> re-fetch -> state_<N> -> hasAll
// (enum attr) state_<N> directly (no comparator) -> hasAll
// (the re-fetch between rCustomAttr and the next write is the
// bug fix: writing RelrDev back-to-back silently rejected it,
// and an enum-recognized attribute hides RelrDev entirely and
// reveals state_<N>, so the comparator is skipped for that case)
// Device-relative   -- rCapab -> rDev_<N> -> rCustomAttr_<N> -> re-fetch ->
// RelrDev_<N> comparator + isDev_<N>=true -> reveal
// relDevice_<N> (capability-locked SINGLE reference-device
// picker) -> write reference id -> optional state_<N> offset
// -> hasAll (no separate reference-attribute picker -- the
// compared attribute is implied by the shared capability)
// Enum/default      -- rCapab -> rDev_<N> -> state_<N> -> hasAll
// (unchanged direct-write path for simple enum/numeric capabilities)
//
// ctx keys:
// writeST                 - Closure(Map params, String key, Object value, String label=null)
// that writes a field and routes into the caller's
// applied/skipped accumulators.
// cancelInFlightCondition - Closure() that clicks cancelCapab on failure.
// condIdx                 - Integer: 0-based condition position for error messages.
// cap                     - String: human-readable capability name for error messages.
// capCanonical            - String: capability value as returned by the page option list.
// hrefParams              - Map passed through to writeST.
// page                    - String: wizard page name (STPage or doActPage); defaults to
// STPage when absent for backwards compatibility.
//
// Throws IllegalArgumentException or IllegalStateException on validation failure;
// the cancel closure is invoked before throwing so the caller's wizard is left
// in a clean state.
private void _rmWalkConditionReveal(Integer appId, Map ctx, Map cond, Integer cIdx) {
    // The body runs as a closure so its call sites live in the closure's own class: the app class is
    // near the JVM 65,535 constant-pool limit. Behaviour is unchanged.
    def _impl = {
    def writeST               = ctx.writeST as Closure
    def cancelInFlightCond    = ctx.cancelInFlightCondition as Closure
    def condIdx               = ctx.condIdx as Integer
    def cap                   = ctx.cap?.toString()
    def capCanonical          = ctx.capCanonical?.toString()
    def hrefParams            = ctx.hrefParams as Map
    def skippedAccum          = ctx.skipped as List
    // ctx.applied is the caller's settingsApplied; only the Custom Attribute force-write records into it,
    // and its skip promises the key is there, so it must be non-null.
    def appliedAccum          = ctx.applied as List
    if (appliedAccum == null) {
        throw new IllegalArgumentException("_rmWalkConditionReveal requires ctx.applied (the caller's settingsApplied List) -- the Custom Attribute force-write fallback records into it and its skip hint promises the value is in settingsApplied. Pass applied: <list>.")
    }
    // The skipped list carries every degradation this walker reports and is required.
    if (skippedAccum == null) {
        throw new IllegalArgumentException("_rmWalkConditionReveal requires ctx.skipped (the caller's settingsSkipped List) -- the walker records degradation skips into it. Pass skipped: <list>.")
    }
    def page                  = ctx.page?.toString() ?: "STPage"
    // Request-scoped page-schema cache threaded from the builder (null when absent). Passed into
    // every reveal/fetch/click below: reveal pre-fetches HIT the prior write's cached POST
    // response, and every click invalidates the app's cached pages.
    def cache                 = ctx.cache as Map
    // fallbackToExisting: the matching field was already visible before the write, which can hide a
    // rejected write when a same-named field lingers. It adds an informational sentinel (no partial),
    // only for reveal steps that write; discoverField() reads without it.
    def revealStep = { Integer aId, String pg, String pattern, Closure trigger ->
        def step = _rmRevealStep(aId, pg, pattern, trigger, cache)
        // skippedAccum is non-null (guarded at entry).
        if (step?.fallbackToExisting == true) {
            skippedAccum << [key: pattern, reason: "reveal_fallback_to_existing_field", condIdx: condIdx]
        }
        return step
    }
    // discoverField: empty-trigger reveal for fields already-revealed by a prior write.
    // Same return shape as revealStep but does NOT push the reveal-fallback sentinel
    // (these calls are by design discovery-only and would emit false positives).
    def discoverField = { Integer aId, String pg, String pattern ->
        return _rmRevealStep(aId, pg, pattern, {}, cache)
    }
    try {
        _rmCheckCondSpec(cond, "condition", "conditions[${condIdx}]".toString())
    } catch (IllegalArgumentException refused) {
        cancelInFlightCond()
        throw refused
    }
    capCanonical = cond.capability?.toString() ?: capCanonical

    // Mode: RM reveals a modes<n> picker (conditions; triggers use modesX<n>), discovered not hardcoded.
    // Spec: state (mode names) or modeIds.
    if (capCanonical == "Mode") {
        def modeIdsToWrite
        if (cond.modeIds != null) {
            modeIdsToWrite = (cond.modeIds instanceof List) ? (cond.modeIds as List).collect { it?.toString() } : [cond.modeIds.toString()]
        } else if (cond.state != null) {
            // Resolve name(s) to IDs via _rmResolveModeIds.
            def stateVal = cond.state
            def names = (stateVal instanceof List) ? (stateVal as List) : [stateVal]
            // A comma-joined single string is a common mistake -- steer to the list shape with
            // this surface's own context before the shared resolver throws its generic form.
            def hubModeNames = (location?.modes ?: []).collect { it?.name }.findAll { it }
            names.each { nm ->
                def commaHint = _rmCommaJoinedModeHint(nm, hubModeNames, "conditions[${condIdx}]")
                if (commaHint) { cancelInFlightCond(); throw new IllegalArgumentException(commaHint) }
            }
            modeIdsToWrite = _rmResolveModeIds(names)
        }
        if (!modeIdsToWrite) {
            cancelInFlightCond()
            throw new IllegalArgumentException("conditions[${condIdx}]: Mode condition requires 'state' (mode name) or 'modeIds' (list of mode IDs). Neither was provided.")
        }
        // _rmRevealStep: snapshot pre-state, then write rCapab (the revealing trigger),
        // then re-fetch and return the newly-appeared modes<cIdx> picker.
        def capKey = "rCapab_${cIdx}".toString()
        def modesReveal = revealStep(appId, page, /modes\d+/, {
            writeST(hrefParams, capKey, capCanonical)
        })
        if (!modesReveal.input) {
            cancelInFlightCond()
            def visible = modesReveal.visibleNames?.join(', ') ?: "(none)"
            throw new IllegalStateException("conditions[${condIdx}]: Mode: expected modes<N> picker after rCapab='Mode' but it did not appear. Visible fields: ${visible}")
        }
        def modesField = modesReveal.input.name.toString()
        // A directly-passed integer ID skips _rmResolveModeIds' name check, so
        // reject any ID the revealed picker (or location.modes) does not offer
        // before it commits a mode-less condition row.
        def modeCheck = _rmBadModeIds(modeIdsToWrite, modesReveal.input.options)
        if (modeCheck.bad) {
            cancelInFlightCond()
            throw new IllegalArgumentException("conditions[${condIdx}]: Mode: mode ID(s) ${modeCheck.bad.join(', ')} not offered by ${modeCheck.source}. Valid IDs: ${modeCheck.valid.sort().join(', ')}")
        }
        writeST(hrefParams, modesField, modeIdsToWrite)
        if (cond.not == true) {
            writeST(hrefParams, "not${cIdx}".toString(), true)
        }
        if (cond.rawSettings instanceof Map) {
            (cond.rawSettings as Map).each { rk, rv -> writeST(hrefParams, rk.toString(), rv) }
        }
        _rmClickAppButton(appId, "hasAll", null, page, cache)
        return
    }

    // Between two times: starting<n>/ending<n> pick 'A specific time', 'Sunrise' or 'Sunset';
    // startingA<n>/endingA<n> take the clock time (2000-01-01THH:mm:00.000+HHMM); sun events take an
    // offset field per event.
    if (capCanonical == "Between two times") {
        def startSpec = cond.start instanceof Map ? (cond.start as Map) : null
        def endSpec   = cond.end   instanceof Map ? (cond.end   as Map) : null
        if (!startSpec || !endSpec) {
            cancelInFlightCond()
            throw new IllegalArgumentException("conditions[${condIdx}]: 'Between two times' requires 'start' and 'end' Maps each with {type:'clock'|'sunrise'|'sunset', time?:'HH:mm', offset?:N}")
        }
        def allowedTypes = ["clock", "sunrise", "sunset"]
        def startType = startSpec.type?.toString()?.toLowerCase()
        def endType   = endSpec.type?.toString()?.toLowerCase()
        if (!startType || !(startType in allowedTypes)) {
            cancelInFlightCond()
            throw new IllegalArgumentException("conditions[${condIdx}]: 'Between two times' start.type must be 'clock', 'sunrise', or 'sunset' (got '${startSpec.type}')")
        }
        if (!endType || !(endType in allowedTypes)) {
            cancelInFlightCond()
            throw new IllegalArgumentException("conditions[${condIdx}]: 'Between two times' end.type must be 'clock', 'sunrise', or 'sunset' (got '${endSpec.type}')")
        }
        // Map caller-facing type names to firmware enum values.
        def typeToWire = [clock: "A specific time", sunrise: "Sunrise", sunset: "Sunset"]
        def startTypeWire = typeToWire.get(startType)
        def endTypeWire   = typeToWire.get(endType)

        // Validate that the required time/offset values are present before any hub writes.
        // Validating here (not after reveals) avoids hub round-trips on a caller error.
        def startValRaw = (startType == "clock") ? startSpec.time : startSpec.offset
        if (startValRaw == null) {
            cancelInFlightCond()
            def fieldHint = (startType == "clock") ? "'time' (HH:mm)" : "'offset' (minutes)"
            throw new IllegalArgumentException("conditions[${condIdx}]: 'Between two times' start.${fieldHint} is required for start.type='${startType}'")
        }
        def endValRaw = (endType == "clock") ? endSpec.time : endSpec.offset
        if (endValRaw == null) {
            cancelInFlightCond()
            def fieldHint = (endType == "clock") ? "'time' (HH:mm)" : "'offset' (minutes)"
            throw new IllegalArgumentException("conditions[${condIdx}]: 'Between two times' end.${fieldHint} is required for end.type='${endType}'")
        }
        // HH:mm -> RM's ISO form on the fixed date 2000-01-01, with that date's zone offset (DST differs from
        // today's).
        def toIsoTime = { String hhmm ->
            long anchorMs = 946684800000L  // 2000-01-01T00:00:00.000Z
            def tz = location.timeZone
            if (!tz) {
                // Every other throw in _rmWalkConditionReveal precedes itself with
                // cancelInFlightCond() so the wizard does not stay half-open. This
                // path inside the toIsoTime closure must do the same.
                cancelInFlightCond()
                throw new IllegalStateException("conditions[${condIdx}]: 'Between two times': location.timeZone is null -- set hub timezone in Settings > Location and Modes before using clock-based conditions.")
            }
            long offsetMs = tz.getOffset(anchorMs)
            long offsetMinutes = offsetMs / 60000L
            String sign = (offsetMinutes >= 0) ? "+" : "-"
            long absMin = Math.abs(offsetMinutes)
            String hh = "${(absMin / 60 as long)}".padLeft(2, '0')
            String mm = "${(absMin % 60 as long)}".padLeft(2, '0')
            "2000-01-01T${hhmm}:00.000${sign}${hh}${mm}".toString()
        }
        def startValToWrite = (startType == "clock") ? toIsoTime(startValRaw.toString()) : startValRaw
        def endValToWrite   = (endType   == "clock") ? toIsoTime(endValRaw.toString())   : endValRaw

        // Reveal 1: write rCapab as the trigger -> starting<cIdx> type selector appears
        def capKey = "rCapab_${cIdx}".toString()
        def startTypeReveal = revealStep(appId, page, /starting\d+/, {
            writeST(hrefParams, capKey, capCanonical)
        })
        if (!startTypeReveal.input) {
            cancelInFlightCond()
            def visible = startTypeReveal.visibleNames?.join(', ') ?: "(none)"
            throw new IllegalStateException("conditions[${condIdx}]: 'Between two times': start-type selector (starting<N>) not revealed after rCapab write. Visible fields: ${visible}")
        }
        def startTypeField = startTypeReveal.input.name.toString()

        // Writing the start type reveals its value field: startingA<n> for a clock time,
        // startSunriseOffset<n>/startSunsetOffset<n> for sun events.
        def startValReveal = revealStep(appId, page, /startingA\d+|startSunriseOffset\d+|startSunsetOffset\d+/, {
            writeST(hrefParams, startTypeField, startTypeWire)
        })
        if (!startValReveal.input) {
            cancelInFlightCond()
            def visible = startValReveal.visibleNames?.join(', ') ?: "(none)"
            def startFieldHint = (startType == "clock") ? "'time' field (startingA<N>)" : "'offset' field (firmware-assigned)"
            throw new IllegalStateException("conditions[${condIdx}]: 'Between two times': start ${startFieldHint} not revealed after start-type='${startType}'. Visible fields: ${visible}")
        }
        def startValField = startValReveal.input.name.toString()

        // Reveal 3: write start value as the trigger -> ending<cIdx> end-type selector appears
        def endTypeReveal = revealStep(appId, page, /ending\d+/, {
            writeST(hrefParams, startValField, startValToWrite)
        })
        if (!endTypeReveal.input) {
            cancelInFlightCond()
            def visible = endTypeReveal.visibleNames?.join(', ') ?: "(none)"
            throw new IllegalStateException("conditions[${condIdx}]: 'Between two times': end-type selector (ending<N>) not revealed after start fields. Visible fields: ${visible}")
        }
        def endTypeField = endTypeReveal.input.name.toString()

        // Reveal 4: write end type as the trigger -> end value field appears.
        // Clock: endingA<cIdx>; Sunrise: endSunriseOffset<cIdx>; Sunset: endSunsetOffset<cIdx>
        // (RM names the sun-event offset field per event; offset minutes, optional). The prior
        // regex matched Sunrise but not Sunset on the end side -- end-type='Sunset' silently failed.
        def endValReveal = revealStep(appId, page, /endingA\d+|endSunriseOffset\d+|endSunsetOffset\d+/, {
            writeST(hrefParams, endTypeField, endTypeWire)
        })
        if (!endValReveal.input) {
            cancelInFlightCond()
            def visible = endValReveal.visibleNames?.join(', ') ?: "(none)"
            // Symmetric phrasing with the start-side hint above. The walker's regex
            // /endingA\d+|endSunriseOffset\d+/ captures both shapes; naming a single
            // candidate would mislead callers when the firmware variant of the field
            // is the other branch. Mirror startFieldHint exactly.
            def endFieldHint = (endType == "clock") ? "'time' field (endingA<N>)" : "'offset' field (firmware-assigned)"
            throw new IllegalStateException("conditions[${condIdx}]: 'Between two times': end ${endFieldHint} not revealed after end-type='${endType}'. Visible fields: ${visible}")
        }
        def endValField = endValReveal.input.name.toString()
        writeST(hrefParams, endValField, endValToWrite)

        if (cond.not == true) {
            writeST(hrefParams, "not${cIdx}".toString(), true)
        }
        if (cond.rawSettings instanceof Map) {
            (cond.rawSettings as Map).each { rk, rv -> writeST(hrefParams, rk.toString(), rv) }
        }
        _rmClickAppButton(appId, "hasAll", null, page, cache)
        return
    }

    // ---- Variable comparison capability ----
    // Spec: {capability:'Variable', variable:'myVar', comparator:'=', value:<v>}
    // Each _rmRevealStep trigger writes the field that causes the next field to appear.
    // Chain: rCapab -> variable-name picker -> RelrDev_<N> comparator -> state_<N> value
    if (capCanonical == "Variable") {
        if (!cond.variable) {
            cancelInFlightCond()
            throw new IllegalArgumentException("conditions[${condIdx}]: Variable condition requires 'variable' (the hub variable name) and 'comparator'. Got: ${cond}")
        }
        // A Boolean variable exposes state_<N> (true/false) directly and has no comparator
        // field, so an omitted comparator is allowed only with a true/false literal; otherwise fail loud.
        if (!cond.comparator) {
            def rhs = cond.state != null ? cond.state : cond.value
            boolean booleanLiteral = rhs instanceof Boolean || rhs?.toString()?.toLowerCase() in ["true", "false"]
            if (!booleanLiteral) {
                cancelInFlightCond()
                throw new IllegalArgumentException("conditions[${condIdx}]: Variable condition requires 'comparator' (e.g. '=', '!=', '<', '>'), or value true/false for a Boolean variable. Got: ${cond}")
            }
        }
        // compareToVariable (variable-vs-variable RHS) and value/state (constant RHS)
        // are mutually exclusive -- RM renders one OR the other, never both. Reject the
        // ambiguous combination up front so the caller picks a single RHS shape.
        if (cond.compareToVariable != null && (cond.value != null || cond.state != null)) {
            cancelInFlightCond()
            throw new IllegalArgumentException("conditions[${condIdx}]: Variable condition cannot combine 'compareToVariable' (variable RHS) with 'value'/'state' (constant RHS) -- they are mutually exclusive. Supply exactly one.")
        }
        def varName = cond.variable.toString()
        def capKey = "rCapab_${cIdx}".toString()

        // Reveal 1: write rCapab as the trigger -> variable-name picker appears
        // (e.g. lVar_<N> or varX_<N>; exact name is firmware-assigned)
        def varPickerReveal = revealStep(appId, page, /[a-zA-Z]+Var[a-zA-Z]*_\d+|varName_\d+|rVar_\d+/, {
            writeST(hrefParams, capKey, capCanonical)
        })
        if (!varPickerReveal.input) {
            cancelInFlightCond()
            def visible = varPickerReveal.visibleNames?.join(', ') ?: "(none)"
            throw new IllegalStateException("conditions[${condIdx}]: Variable: variable-name picker not revealed after rCapab='Variable'. Visible fields: ${visible}")
        }
        def varPickerField = varPickerReveal.input.name.toString()
        // Validate variable name against the schema's option list via the canonical
        // reader. Variable-picker options are Maps keyed by variable name, so the reader's
        // Map branch extracts the keys (the names); it also normalizes the scalar and
        // List-of-{value:...} shapes a hand-rolled Map?keySet:List?collect reader mishandled.
        def varOpts = _rmReadPickerOptionStrings(varPickerReveal.input)
        if (!varOpts) {
            // Empty variable options can't be checked: write the name anyway and report api_unavailable (partial).
            mcpLog("warn", "rm-native", "conditions[${condIdx}]: Variable: picker '${varPickerField}' returned an empty option list -- variable-name validation skipped; write will proceed unvalidated")
            if (skippedAccum != null) {
                skippedAccum << [key: "variable-validation", reason: "api_unavailable", condIdx: condIdx, varName: varName, pickerField: varPickerField]
            }
        } else if (!varOpts.any { it == varName }) {
            cancelInFlightCond()
            throw new IllegalArgumentException("conditions[${condIdx}]: Variable: hub variable '${varName}' not in the revealed picker for '${varPickerField}'. Available: ${varOpts.sort().join(', ')}")
        }

        // Reveal 2: write variable name as the trigger -> RelrDev_<N> comparator appears,
        // or state_<N> directly for a Boolean variable.
        def normalizedComparator = cond.comparator ? _rmNormalizeComparator(cond.comparator.toString()) : null
        def relrReveal = revealStep(appId, page, "RelrDev_${cIdx}|state_${cIdx}".toString(), {
            writeST(hrefParams, varPickerField, varName)
        })
        // Classify on every matching field, not the first in page order: a static schema can list state_<N>
        // ahead of RelrDev_<N>, and a comparator field means a comparison slot whatever else is present.
        def revealedInputs = relrReveal.postInputs ?: []
        def relrInput = revealedInputs.find { it?.name?.toString() == "RelrDev_${cIdx}".toString() }
        def stateInput = revealedInputs.find { it?.name?.toString() == "state_${cIdx}".toString() }
        if (relrInput == null && stateInput != null) {
            def stateOptions = _rmReadPickerOptionStrings(stateInput)
            boolean trueFalseOptions = stateOptions && stateOptions.every { it?.toString()?.toLowerCase() in ["true", "false"] }
            if (stateOptions && !trueFalseOptions) {
                cancelInFlightCond()
                throw new IllegalStateException("conditions[${condIdx}]: Variable '${varName}': state_${cIdx} offers ${stateOptions} and no comparator (RelrDev_${cIdx}) appeared, so it is neither a Boolean nor a comparison. Visible fields: ${relrReveal.visibleNames?.join(', ') ?: '(none)'}")
            }
            if (relrReveal.fallbackToExisting && !trueFalseOptions) {
                cancelInFlightCond()
                throw new IllegalStateException("conditions[${condIdx}]: Variable: state_${cIdx} was present before '${varName}' was selected and lists no true/false options, so its Boolean type cannot be established. Refusing to guess.")
            }
            if (relrReveal.fallbackToExisting) {
                // state_<cIdx> was already on the page before the variable write (static schema or a
                // leftover slot). Accept it only if the variable picker echoes the variable just chosen.
                def varEcho = (relrReveal.postInputs ?: []).find { it?.name?.toString() == varPickerField }
                def echoed = varEcho?.value != null ? varEcho.value : varEcho?.currentValue
                if (echoed?.toString() != varName) {
                    cancelInFlightCond()
                    throw new IllegalStateException("conditions[${condIdx}]: Variable '${varName}': '${varPickerField}' did not echo the selection (got '${echoed}') and state_${cIdx} was already present, so the value field cannot be told apart from a leftover one.")
                }
            }
            // Boolean variable: implicit equality against true/false; RM persists RelrDev_<N>='=' itself
            // (observed on a rule built through the native UI).
            if (cond.compareToVariable != null) {
                cancelInFlightCond()
                throw new IllegalArgumentException("conditions[${condIdx}]: Variable '${varName}' is Boolean; RM compares it only to true/false, not to another variable.")
            }
            if (normalizedComparator != null && normalizedComparator != _rmNormalizeComparator("=")) {
                cancelInFlightCond()
                throw new IllegalArgumentException("conditions[${condIdx}]: Variable '${varName}' is Boolean; only '=' is supported (use not:true to negate). Got comparator '${cond.comparator}'.")
            }
            def boolRaw = cond.state != null ? cond.state : cond.value
            def boolStr = (boolRaw instanceof Boolean) ? boolRaw.toString() : boolRaw?.toString()?.toLowerCase()
            def boolField = stateInput.name.toString()
            def boolOpts = stateOptions
            if (!(boolStr in ["true", "false"]) || (boolOpts && !(boolStr in boolOpts))) {
                cancelInFlightCond()
                throw new IllegalArgumentException("conditions[${condIdx}]: Variable '${varName}' is Boolean; value must be true or false. Got '${boolRaw}'. Options: ${boolOpts}")
            }
            writeST(hrefParams, boolField, boolStr)
            if (cond.not == true) {
                writeST(hrefParams, "not${cIdx}".toString(), true)
            }
            if (cond.rawSettings instanceof Map) {
                (cond.rawSettings as Map).each { rk, rv -> writeST(hrefParams, rk.toString(), rv) }
            }
            _rmClickAppButton(appId, "hasAll", null, page, cache)
            return
        }
        if (relrInput && !cond.comparator) {
            cancelInFlightCond()
            throw new IllegalArgumentException("conditions[${condIdx}]: Variable '${varName}' revealed a comparator field (RelrDev_${cIdx}), so it is not a Boolean variable and requires 'comparator' (e.g. '=', '!=', '<', '>'). Got: ${cond}")
        }
        if (!relrInput) {
            cancelInFlightCond()
            def visible = relrReveal.visibleNames?.join(', ') ?: "(none)"
            throw new IllegalStateException("conditions[${condIdx}]: Variable: RelrDev_<N> (comparator) not revealed after variable name write. Visible fields: ${visible}")
        }
        // Use the firmware-assigned field name discovered from the live schema.
        def relrField = relrInput.name.toString()

        // compareToVariable: isVar_<n>=true reveals the right-hand variable picker, whose name is discovered
        // from the page.
        if (cond.compareToVariable != null) {
            def rhsVarName = cond.compareToVariable.toString()
            def isVarKey = "isVar_${cIdx}".toString()
            // Write RelrDev (comparator) first so the wizard advances, then toggle
            // isVar_<N>=true to reveal the right-hand variable picker.
            writeST(hrefParams, relrField, normalizedComparator)
            def rhsVarReveal = revealStep(appId, page, /xVarR_\d+|[a-zA-Z]+VarR[a-zA-Z]*_\d+|rVarR_\d+/, {
                writeST(hrefParams, isVarKey, true)
            })
            if (!rhsVarReveal.input) {
                cancelInFlightCond()
                def visible = rhsVarReveal.visibleNames?.join(', ') ?: "(none)"
                throw new IllegalStateException("conditions[${condIdx}]: Variable: the right-hand variable picker did not appear after isVar_<N>=true (compareToVariable='${rhsVarName}'). Visible fields: ${visible}")
            }
            def rhsVarField = rhsVarReveal.input.name.toString()
            // Validate the RHS variable name against the revealed picker's options via the
            // canonical reader (same Map-keyed-by-name contract as the LHS variable picker).
            def rhsOpts = _rmReadPickerOptionStrings(rhsVarReveal.input)
            if (!rhsOpts) {
                // Empty right-hand variable options: write anyway and report api_unavailable (partial).
                mcpLog("warn", "rm-native", "conditions[${condIdx}]: Variable: right-hand picker '${rhsVarField}' returned an empty option list -- compareToVariable name validation skipped; write will proceed unvalidated")
                if (skippedAccum != null) {
                    skippedAccum << [key: "compareToVariable-validation", reason: "api_unavailable", condIdx: condIdx, varName: rhsVarName, pickerField: rhsVarField]
                }
            } else if (!rhsOpts.any { it == rhsVarName }) {
                cancelInFlightCond()
                throw new IllegalArgumentException("conditions[${condIdx}]: Variable: compareToVariable '${rhsVarName}' not in the revealed right-hand picker for '${rhsVarField}'. Available: ${rhsOpts.sort().join(', ')}")
            }
            writeST(hrefParams, rhsVarField, rhsVarName)

            if (cond.not == true) {
                writeST(hrefParams, "not${cIdx}".toString(), true)
            }
            if (cond.rawSettings instanceof Map) {
                (cond.rawSettings as Map).each { rk, rv -> writeST(hrefParams, rk.toString(), rv) }
            }
            _rmClickAppButton(appId, "hasAll", null, page, cache)
            return
        }

        // Reveal 3: write RelrDev as the trigger -> state_<N> value appears
        def condStateOrValue = cond.state != null ? cond.state : cond.value
        def stateKey = "state_${cIdx}".toString()
        def stateReveal = revealStep(appId, page, /state_\d+/, {
            writeST(hrefParams, relrField, normalizedComparator)
        })
        // A change comparator has no value, and RM shows no value field for it.
        if (!stateReveal.input && !(condStateOrValue == null && _rmComparatorIsRhsOptional(normalizedComparator))) {
            cancelInFlightCond()
            def visible = stateReveal.visibleNames?.join(', ') ?: "(none)"
            throw new IllegalStateException("conditions[${condIdx}]: Variable: state_<N> (value field) not revealed after RelrDev write. Visible fields: ${visible}")
        }
        // A comparator that needs a value but has none would compare with the default 0, so fail. Change
        // comparators take no value.
        def comparatorIsRhsOptional = _rmComparatorIsRhsOptional(normalizedComparator)
        if (condStateOrValue == null && !comparatorIsRhsOptional) {
            cancelInFlightCond()
            throw new IllegalArgumentException("conditions[${condIdx}]: Variable: comparator '${cond.comparator}' needs a value (or compareToVariable).")
        }
        if (condStateOrValue != null) {
            writeST(hrefParams, stateKey, condStateOrValue)
        }

        if (cond.not == true) {
            writeST(hrefParams, "not${cIdx}".toString(), true)
        }
        if (cond.rawSettings instanceof Map) {
            (cond.rawSettings as Map).each { rk, rv -> writeST(hrefParams, rk.toString(), rv) }
        }
        _rmClickAppButton(appId, "hasAll", null, page, cache)
        return
    }

    // Custom Attribute: rCapab -> rDev_<n> -> rCustomAttr_<n> -> RelrDev_<n> -> state_<n>, each write
    // revealing the next. An attribute RM knows as an enum reveals state_<n> directly and no comparator;
    // only a render with neither field is a failure.
    if (capCanonical == "Custom Attribute") {
        if (cond.attribute != null && cond.comparator == null) {
            cancelInFlightCond()
            throw new IllegalArgumentException("conditions[${condIdx}]: a Custom Attribute condition needs both attribute and comparator (comparator missing).")
        }
        if (cond.comparator != null && cond.attribute == null) {
            cancelInFlightCond()
            throw new IllegalArgumentException("conditions[${condIdx}]: a Custom Attribute condition needs both attribute and comparator (attribute missing).")
        }
        writeST(hrefParams, "rCapab_${cIdx}".toString(), capCanonical)
        if (cond.deviceIds != null) {
            writeST(hrefParams, "rDev_${cIdx}".toString(), cond.deviceIds)
        }
        if (cond.comparator != null) {
            def customAttrKey = "rCustomAttr_${cIdx}".toString()
            def attrVal = cond.attribute
            def condStateOrValue = cond.state != null ? cond.state : cond.value
            def stateKey = "state_${cIdx}".toString()

            // Write rCustomAttr_<n>, then branch on what renders: RelrDev_<n> (free value) or state_<n> (enum).
            // If the re-read throws, force-write the comparator and report partial rather than abort.
            // Breadcrumbs follow the page: STPage [] (re-firing mainPage resets its editor), doActPage ["mainPage"].
            def relrReveal = null
            try {
                relrReveal = revealStep(appId, page, /RelrDev_\d+/, {
                    if (attrVal != null) {
                        writeST(hrefParams, customAttrKey, attrVal)
                    }
                })
            } catch (Exception revealEx) {
                mcpLog("warn", "rm-native", "conditions[${condIdx}]: Custom Attribute: exposure-probe re-fetch after rCustomAttr_${cIdx} failed for app ${appId} on page ${page} (${revealEx.message ?: revealEx}); force-writing comparator RelrDev_${cIdx} as fallback (partial)")
                // The force-write records the comparator as applied plus a comparator_force_written_unverified skip,
                // and posts without re-reading.
                def forceBreadcrumbs = (page == "STPage") ? '[]' : '["mainPage"]'
                _rmForceWriteEnumField(appId, page, "RelrDev_${cIdx}".toString(), _rmNormalizeComparator(cond.comparator),
                                       appliedAccum, skippedAccum, forceBreadcrumbs)
                // Best-effort value + negation + raw-settings, then seal the slot. These go
                // through writeST so their own POST-then-verify accounting still applies; if
                // the hub is still hiccuping they degrade to silent_rejection (partial), never
                // aborting -- matching the never-abort contract of the trigger/condition path.
                if (condStateOrValue != null) {
                    writeST(hrefParams, stateKey, condStateOrValue)
                }
                if (cond.not == true) {
                    writeST(hrefParams, "not${cIdx}".toString(), true)
                }
                if (cond.rawSettings instanceof Map) {
                    (cond.rawSettings as Map).each { rk, rv -> writeST(hrefParams, rk.toString(), rv) }
                }
                _rmClickAppButton(appId, "hasAll", null, page, cache)
                return
            }
            if (relrReveal.input) {
                // Free-valued attribute -> comparator path: write RelrDev_<N>, which reveals state_<N>.
                def relrField = relrReveal.input.name.toString()
                def normalizedComparator = _rmNormalizeComparator(cond.comparator.toString())
                def stateReveal = revealStep(appId, page, /state_\d+/, {
                    writeST(hrefParams, relrField, normalizedComparator)
                })
                if (!stateReveal.input) {
                    cancelInFlightCond()
                    def visible = stateReveal.visibleNames?.join(', ') ?: "(none)"
                    throw new IllegalStateException("conditions[${condIdx}]: Custom Attribute: state_<N> (value) not revealed after RelrDev write (app ${appId}, page ${page}). Visible fields: ${visible}")
                }
                if (condStateOrValue != null) {
                    writeST(hrefParams, stateKey, condStateOrValue)
                }
            } else if (relrReveal.visibleNames?.contains(stateKey)) {
                // An enum attribute shows only its value picker; the value goes there, no comparator.
                if (condStateOrValue != null) writeST(hrefParams, stateKey, condStateOrValue)
            } else {
                // Neither the comparator nor the value picker rendered -- genuine
                // degradation (firmware drift / transient render failure). Fail loud.
                cancelInFlightCond()
                def visible = relrReveal.visibleNames?.join(', ') ?: "(none)"
                throw new IllegalStateException("conditions[${condIdx}]: Custom Attribute: neither a comparator (RelrDev_<N>) nor a value picker (state_<N>) appeared after the attribute (app ${appId}, page ${page}). Visible fields: ${visible}")
            }
        } else if (cond.attribute != null) {
            // No comparator -- just write the attribute (e.g. presence-style custom attr check)
            writeST(hrefParams, "rCustomAttr_${cIdx}".toString(), cond.attribute)
            def condStateOrValue = cond.state != null ? cond.state : cond.value
            if (condStateOrValue != null) {
                writeST(hrefParams, "state_${cIdx}".toString(), condStateOrValue)
            }
        }
        if (cond.not == true) {
            writeST(hrefParams, "not${cIdx}".toString(), true)
        }
        if (cond.rawSettings instanceof Map) {
            (cond.rawSettings as Map).each { rk, rv -> writeST(hrefParams, rk.toString(), rv) }
        }
        _rmClickAppButton(appId, "hasAll", null, page, cache)
        return
    }

    // compareToDevice: the comparator RelrDev_<n> reveals isDev_<n>; isDev_<n>=true reveals relDevice_<n>
    // (single device, same capability) and turns state_<n> into an optional offset. The attribute comes
    // from the capability, so compareToDevice.attribute is never written.
    if (cond.compareToDevice instanceof Map) {
        def ctd = cond.compareToDevice as Map
        if (ctd.deviceId == null) {
            cancelInFlightCond()
            throw new IllegalArgumentException("conditions[${condIdx}]: compareToDevice requires 'deviceId' (the reference device ID). Got: ${ctd}")
        }
        // A device right-hand side needs a comparator, or the condition commits half-written.
        if (cond.comparator == null) {
            cancelInFlightCond()
            throw new IllegalArgumentException("conditions[${condIdx}]: compareToDevice needs a comparator.")
        }
        // A device right-hand side excludes state/value and compareToVariable.
        if (cond.state != null || cond.value != null) {
            cancelInFlightCond()
            throw new IllegalArgumentException("conditions[${condIdx}]: compareToDevice (device-relative RHS) cannot be combined with 'state'/'value' (literal RHS) -- mutually exclusive; use compareToDevice.offset for an offset.")
        }
        if (cond.compareToVariable != null) {
            cancelInFlightCond()
            throw new IllegalArgumentException("conditions[${condIdx}]: compareToDevice and compareToVariable cannot both be set.")
        }
        def refDevId = ctd.deviceId.toString()
        // The reference device was checked to exist before the walk (validateDeviceIdsRecursive includes
        // compareToDevice.deviceId). rCapab and rDev are plain writes for numeric capabilities.
        writeST(hrefParams, "rCapab_${cIdx}".toString(), capCanonical)
        if (cond.deviceIds != null) {
            writeST(hrefParams, "rDev_${cIdx}".toString(), cond.deviceIds)
        }
        if (cond.attribute != null) {
            // cond.attribute is the left side's Custom Attribute (rCustomAttr_<n>), not compareToDevice.attribute.
            writeST(hrefParams, "rCustomAttr_${cIdx}".toString(), cond.attribute)
        }
        // The comparator must render after those writes. Write the exact RelrDev_<cIdx>, since a loose match
        // could hit another condition's slot.
        def afterBaseFields = _rmFetchConfigJson(appId, page, cache)
        def afterBaseInputs = (afterBaseFields?.configPage?.sections ?: []).collectMany { it?.input ?: [] }
        def relrKey = "RelrDev_${cIdx}".toString()
        def relrInput = afterBaseInputs.find { it?.name?.toString() ==~ /RelrDev_\d+/ }
        if (!relrInput) {
            cancelInFlightCond()
            def visible = afterBaseInputs.collect { it?.name?.toString() }.findAll { it }.join(', ') ?: "(none)"
            throw new IllegalStateException("conditions[${condIdx}]: compareToDevice: no comparator (RelrDev_<N>) appeared for '${capCanonical}'. Visible fields: ${visible}")
        }
        // Normalize the comparator (!= -> the not-equal glyph, == -> =) for parity with
        // every other comparator write -- the RM enum only accepts the Unicode glyphs.
        def normalizedComparator = _rmNormalizeComparator(cond.comparator.toString())
        // Write the comparator, which reveals the isDev_<N> "Relative to a device?"
        // toggle. Sibling of the Variable path's "write RelrDev, then toggle the RHS
        // boolean to reveal the picker" sequence.
        def isDevKey = "isDev_${cIdx}".toString()
        // Comparator and isDev toggle in one reveal step; the reveal is anchored on relDevice_<cIdx> exactly.
        def relDeviceReveal = revealStep(appId, page, "relDevice_${cIdx}".toString(), {
            writeST(hrefParams, relrKey, normalizedComparator)
            writeST(hrefParams, isDevKey, true)
        })
        if (!relDeviceReveal.input) {
            cancelInFlightCond()
            def visible = relDeviceReveal.visibleNames?.join(', ') ?: "(none)"
            throw new IllegalStateException("conditions[${condIdx}]: compareToDevice: the reference-device picker (relDevice_<N>) did not appear after isDev_<N>=true. Visible fields: ${visible}")
        }
        def relDeviceField = relDeviceReveal.input.name.toString()
        // A device picker carries no options in the schema (RM fills it in the browser), so an empty list is
        // normal. Options, when present, come as {id: label}, a list, or [{id: label}]; read the id key.
        def relOptsRaw = relDeviceReveal.input.options
        def relOpts
        if (relOptsRaw instanceof Map) {
            relOpts = (relOptsRaw as Map).keySet().collect { it?.toString() }.findAll { it }
        } else {
            relOpts = ((relOptsRaw ?: []) as List).collect { o ->
                (o instanceof Map && !(o as Map).isEmpty()) ? (o as Map).keySet().iterator().next()?.toString() : o?.toString()
            }.findAll { it }
        }
        if (relOpts && !relOpts.any { it == refDevId }) {
            cancelInFlightCond()
            def availLabel = relOpts.size() == 1 ? "Available device id" : "Available device ids"
            throw new IllegalArgumentException("conditions[${condIdx}]: compareToDevice: device '${refDevId}' is not in the reference picker, which lists only '${capCanonical}' devices. ${availLabel}: ${relOpts.sort().join(', ')}")
        }
        // relDevice_<N> is multiple:false (SINGLE device); write the bare id.
        writeST(hrefParams, relDeviceField, refDevId)
        // Optional decimal offset, written to state_<N> (which re-titles to the offset
        // field once isDev_<N>=true). Omit -> offset 0. If the offset field is not visible
        // (firmware variance), degrade with partial:true rather than throw -- the offset
        // is optional and the device-relative comparison is otherwise complete.
        if (ctd.offset != null) {
            def stateKey = "state_${cIdx}".toString()
            // Re-check this slot's exact state_<cIdx> before writing the offset.
            def offsetReveal = discoverField(appId, page, stateKey)
            if (offsetReveal.input && offsetReveal.input.name?.toString() == stateKey) {
                writeST(hrefParams, stateKey, ctd.offset)
            } else {
                mcpLog("warn", "rm-native", "conditions[${condIdx}] (slot ${cIdx}): compareToDevice: no offset field (${stateKey}) after the reference device; offset=${ctd.offset} was not written")
                if (skippedAccum != null) {
                    skippedAccum << [key: "compareToDevice", reason: "offset_field_not_revealed", condIdx: condIdx, offset: ctd.offset]
                }
            }
        }
        if (cond.not == true) {
            writeST(hrefParams, "not${cIdx}".toString(), true)
        }
        if (cond.rawSettings instanceof Map) {
            (cond.rawSettings as Map).each { rk, rv -> writeST(hrefParams, rk.toString(), rv) }
        }
        _rmClickAppButton(appId, "hasAll", null, page, cache)
        return
    }

    // Default (device enum and numeric): rCapab -> rDev_<n> -> rCustomAttr_<n> if attribute ->
    // RelrDev_<n> if comparator -> state_<n> -> hasAll. state_<n> appears only after the comparator
    // commits, so they can't share a POST.
    writeST(hrefParams, "rCapab_${cIdx}".toString(), capCanonical)
    if (cond.deviceIds != null) {
        writeST(hrefParams, "rDev_${cIdx}".toString(), cond.deviceIds)
    }
    // STPage discloses progressively: state_<n> appears only after RelrDev_<n> commits, so the order is
    // rCustomAttr_<n> -> RelrDev_<n> -> state_<n>. Enum capabilities show state_<n> right after rDev_<n>.
    if (cond.comparator != null) {
        if (cond.attribute != null) {
            // A standard capability with a custom attribute (reached only by the walker specs; the tool sends
            // Custom Attribute specs to the block above). Re-read after rCustomAttr_<n> and write the comparator
            // only where it renders: writeST doesn't check the schema, so a hidden RelrDev_<n> would store silently.
            writeST(hrefParams, "rCustomAttr_${cIdx}".toString(), cond.attribute)
            // If the re-read throws, force-write the comparator (partial) and skip the discrimination below.
            def afterAttrInputs = null
            try {
                def afterCfg = _rmFetchConfigJson(appId, page, cache)
                afterAttrInputs = (afterCfg?.configPage?.sections ?: []).collectMany { it?.input ?: [] }
            } catch (Exception revealEx) {
                mcpLog("warn", "rm-native", "conditions[${condIdx}]: Custom Attribute (default block): exposure-probe re-fetch after rCustomAttr_${cIdx} failed for app ${appId} on page ${page} (${revealEx.message ?: revealEx}); force-writing comparator RelrDev_${cIdx} as fallback (partial)")
                def forceBreadcrumbs = (page == "STPage") ? '[]' : '["mainPage"]'
                _rmForceWriteEnumField(appId, page, "RelrDev_${cIdx}".toString(), _rmNormalizeComparator(cond.comparator),
                                       appliedAccum, skippedAccum, forceBreadcrumbs)
                afterAttrInputs = null
            }
            if (afterAttrInputs != null) {
                def attrValuePickerExposed = afterAttrInputs.any { it?.name?.toString() == "state_${cIdx}".toString() }
                def comparatorExposed = afterAttrInputs.any { it?.name?.toString() == "RelrDev_${cIdx}".toString() }
                // Comparator first: a free-valued attribute can render both fields, so a value picker alone isn't
                // enum. Skip the comparator only when it is hidden and the value picker shows; with neither, the write
                // is tried and its verify reports silent_rejection.
                if (comparatorExposed || !attrValuePickerExposed) {
                    // Condition-wizard comparator field is RelrDev_<N> ("Relr"),
                    // not ReltDev_<N> ("Relt" = trigger-row comparator).
                    writeST(hrefParams, "RelrDev_${cIdx}".toString(), _rmNormalizeComparator(cond.comparator.toString()))
                } else {
                    // Enum case: a supplied value lands via state_<n> below. A change comparator with no value must match
                    // a picker option exactly; otherwise it is reported as a skip.
                    def bStateVal = cond.state != null ? cond.state : cond.value
                }
            }
        } else {
            // No attribute: a numeric capability's comparator field is already exposed. A
            // device-state condition (Motion, Switch, ...) has none, which is a comparator RM
            // does not offer rather than a write that failed.
            String relrKey = "RelrDev_${cIdx}".toString()
            def wr = writeST(hrefParams, relrKey, _rmNormalizeComparator(cond.comparator.toString()))
            // The STPage writer returns its write result; the action-editor writer returns nothing useful.
            boolean landed = (wr instanceof Map) && ((wr as Map).persisted || (wr as Map).verifyFetchFailed)
            def miss = landed ? null : (skippedAccum ?: []).reverse().find { it instanceof Map && it.key == relrKey }
            if (miss && miss.available instanceof Collection && !(miss.available as Collection).contains(relrKey)) {
                miss.reason = "comparator_not_offered"
                miss.capability = cond.capability?.toString()
            }
        }
    }
    // state and value both write to state_${cIdx} -- STPage has no separate
    // value_<N> field. state (enum string) takes priority; value (numeric
    // threshold) is the alias for Custom Attribute and numeric comparator paths.
    def condStateOrValue = cond.state != null ? cond.state : cond.value
    if (condStateOrValue != null) {
        def stateNavResp = _rmFetchConfigJson(appId, page, cache)
        def stateInputs = (stateNavResp?.configPage?.sections ?: []).collectMany { it?.input ?: [] }
        def stateInput = stateInputs.find { it?.name?.toString() == "state_${cIdx}".toString() }
        if (stateInput?.options) {
            def stateOptions = _rmReadPickerOptionStrings(stateInput)
            def matched = stateOptions.find { it.equalsIgnoreCase(condStateOrValue.toString()) }
            if (!matched && stateOptions) {
                cancelInFlightCond()
                throw new IllegalArgumentException("conditions[${condIdx}].state '${condStateOrValue}' not in capability '${cap}' domain. Valid: ${stateOptions.sort().join(', ')}")
            }
            if (matched) condStateOrValue = matched
        }
        writeST(hrefParams, "state_${cIdx}".toString(), condStateOrValue)
    }
    if (cond.not == true) {
        writeST(hrefParams, "not${cIdx}".toString(), true)
    }
    if (cond.rawSettings instanceof Map) {
        (cond.rawSettings as Map).each { rk, rv ->
            writeST(hrefParams, rk.toString(), rv)
        }
    }
    _rmClickAppButton(appId, "hasAll", null, page, cache)
    }
    _impl()
}

// The input-only checks of a Required Expression spec: conditions present, operator(s) valid,
// singular deviceId lifted (recursively), and every device id (compareToDevice included) exists.
// Shared by add and replace, so a bad spec clicks nothing. Returns [operator, opsList], uppercased.
private Map _rmValidateRequiredExpressionSpec(Map exprSpec, String label) {
    if (!(exprSpec instanceof Map)) {
        throw new IllegalArgumentException("${label} requires a Map spec")
    }
    def conditions = exprSpec.conditions
    if (!(conditions instanceof List) || conditions.isEmpty()) {
        throw new IllegalArgumentException("${label}.conditions is required (non-empty List of {capability, deviceIds?, state?, ...})")
    }
    // operator applies to every gap; operators gives one per gap (conditions.size()-1). RM evaluates left
    // to right and stops early: a true left side of OR or a false left side of AND ends it.
    def opsList = null
    if (exprSpec.operators instanceof List) {
        opsList = (exprSpec.operators as List).collect { it?.toString()?.toUpperCase() }
        opsList.eachWithIndex { o, i ->
            if (!(o in ["AND", "OR", "XOR"])) {
                throw new IllegalArgumentException("${label}.operators[${i}] must be 'AND', 'OR', or 'XOR' (got '${o}')")
            }
        }
        if (opsList.size() != conditions.size() - 1) {
            throw new IllegalArgumentException("${label}.operators must have length conditions.size()-1 (${conditions.size() - 1}); got ${opsList.size()}")
        }
    }
    def operator = exprSpec.operator?.toString()?.toUpperCase()
    if (operator && !(operator in ["AND", "OR", "XOR"])) {
        throw new IllegalArgumentException("${label}.operator must be 'AND', 'OR', or 'XOR' (got '${operator}')")
    }
    if (conditions.size() > 1 && !operator && !opsList) {
        throw new IllegalArgumentException("${label} with ${conditions.size()} conditions requires operator (AND/OR/XOR) or operators list")
    }

    // Lift singular deviceId into deviceIds throughout the tree before validation; deviceIds wins.
    def normCondList
    normCondList = { List cl ->
        cl.eachWithIndex { entry, idx ->
            if (!(entry instanceof Map)) return
            def m = entry as Map
            if (m.deviceIds == null && m.deviceId != null) {
                m.deviceIds = [m.deviceId]
            }
            if (m.subExpression instanceof Map) {
                def sub = (m.subExpression as Map).conditions
                if (sub instanceof List) normCondList.call(sub as List)
            }
        }
    }
    normCondList.call(conditions as List)

    // Condition shape guard: each condition (incl. nested subExpression conditions) MUST be a Map;
    // a non-Map entry would later dereference as one and throw a raw cast/null error deep in the walker.
    def validateConditionShapes
    validateConditionShapes = { List cl, String pathPrefix ->
        cl.eachWithIndex { condRaw, i ->
            if (!(condRaw instanceof Map)) {
                throw new IllegalArgumentException("${pathPrefix}[${i}] is not a Map")
            }
            def m = condRaw as Map
            // Mode names resolve against the hub's modes without touching the rule, so an unknown
            // name fails here rather than mid-walk.
            if (m.capability?.toString()?.trim()?.equalsIgnoreCase("Mode")) {
                def modeKeys = (m.modeIds != null) ? m.modeIds : m.state
                if (modeKeys != null && !(location?.modes ?: []).isEmpty()) {
                    // Names only: an integer id is checked against the live mode picker during the walk.
                    def names = ((modeKeys instanceof Collection) ? (modeKeys as Collection) : [modeKeys])
                        .findAll { it != null && !it.toString().isInteger() }
                    try {
                        if (names) _rmResolveModeIds(names)
                    } catch (IllegalArgumentException modeExc) {
                        throw new IllegalArgumentException("${pathPrefix}[${i}]: ${modeExc.message}")
                    }
                }
            }
            if (m.subExpression instanceof Map) {
                def sub = (m.subExpression as Map).conditions
                if (sub instanceof List) {
                    validateConditionShapes.call(sub as List, "${pathPrefix}[${i}].subExpression.conditions")
                }
            }
        }
    }
    validateConditionShapes.call(conditions as List, "${label}.conditions")

    // Every condition's deviceIds must exist: RM accepts an unknown id at the field write
    // ({<bogusId>: null} in rDev_<N>) but the expression then does not bake. Recursive, with a
    // path naming the nesting site.
    def validateDeviceIdsRecursive
    validateDeviceIdsRecursive = { List cl, String pathPrefix ->
        cl.eachWithIndex { condRaw, i ->
            // Shape already guarded above; here it is the existence probe per condition.
            def m = condRaw as Map
            _rmValidateDeviceIdsExist("${pathPrefix}[${i}].deviceIds", m.deviceIds)
            _rmRequireConditionDeviceCapability("${pathPrefix}[${i}].deviceIds".toString(), m)
            // compareToDevice's reference device is existence-validated here, up front,
            // before any walker write -- so a nonexistent reference id fails loud and the
            // walker never opens a half-written slot for it.
            if (m.compareToDevice instanceof Map && (m.compareToDevice as Map).deviceId != null) {
                _rmValidateDeviceIdsExist("${pathPrefix}[${i}].compareToDevice.deviceId", [(m.compareToDevice as Map).deviceId])
            }
            if (m.subExpression instanceof Map) {
                def sub = (m.subExpression as Map).conditions
                if (sub instanceof List) {
                    validateDeviceIdsRecursive.call(sub as List, "${pathPrefix}[${i}].subExpression.conditions")
                }
            }
        }
    }
    validateDeviceIdsRecursive.call(conditions as List, "${label}.conditions")

    return [operator: operator, opsList: opsList]
}

// One new condition slot on STPage, after the selector that opens it (`cond=a` in the add walker,
// a `*` token in the token editor): read the slot index RM assigned, validate the capability
// against the live option list, then walk the condition's fields to hasAll. Appends the slot to
// ctx.conditionIndices. The caller owns cancelling an in-flight condition on a throw.
private Integer _rmWalkNewConditionSlot(Integer appId, Map cond, int i, Map ctx) {
    def cap = cond.capability?.toString()?.trim()
    // Step 1: discover the RM-assigned condition slot index (cIdx): the selector allocated a new
    // rCapab_<N> slot (N firmware-assigned, read from the schema). The rCapab guard throws the
    // actionable "got cond, doneST" dump if the selector silently no-ops.
    def navResp = _rmFetchConfigJson(appId, "STPage", ctx.cache as Map)
    def stInputs = (navResp?.configPage?.sections ?: []).collectMany { it?.input ?: [] }
    def rCapabInput = stInputs.find { it?.name?.toString()?.startsWith("rCapab_") }
    if (!rCapabInput) {
        throw new IllegalStateException("${ctx.op ?: 'addRequiredExpression'}: rCapab_<N> not in STPage schema after ${ctx.selector ?: 'cond=a'}; got ${stInputs.collect { it?.name }.findAll { it }.join(', ')}")
    }
    def m = rCapabInput.name.toString() =~ /rCapab_(\d+)/
    def cIdx = m ? ((m[0] as List)[1] as Integer) : null
    if (cIdx == null) {
        throw new IllegalStateException("${ctx.op ?: 'addRequiredExpression'}: couldn't parse condition index from '${rCapabInput.name}'")
    }
    (ctx.conditionIndices as List) << cIdx
    // Step 2: validate the capability value against the schema option list.
    def capOptions = (rCapabInput.options ?: []) as List
    def capCanonical = capOptions.find { it.toString().equalsIgnoreCase(cap) }
    if (!capCanonical) {
        def suggestion = _rmSuggestTriggerCapability(cap, capOptions)
        def didYouMean = suggestion ? " Did you mean '${suggestion}'?" : ""
        throw new IllegalArgumentException("conditions[${i}].capability '${cap}' is not in Rule Machine's condition list on this hub (RM lists a capability only when the hub has a device or app for it).${didYouMean} RM offers: ${capOptions.collect { it.toString() }.sort().join(', ')}")
    }
    // Write capability, devices, comparator and state in disclosure order, then hasAll. condIdx is stamped
    // onto the skips afterward so repair hints count conditions, not entries.
    def skipped = ctx.skipped as List
    def skippedBefore = skipped.size()
    _rmWalkConditionReveal(appId, [
        writeST: ctx.writeST,
        cancelInFlightCondition: ctx.cancelInFlightCondition,
        condIdx: i,
        cap: cap,
        capCanonical: capCanonical,
        hrefParams: ctx.hrefParams,
        applied: ctx.applied,
        skipped: ctx.skipped,
        cache: ctx.cache
    ], cond, cIdx)
    for (int sIdx = skippedBefore; sIdx < skipped.size(); sIdx++) {
        def sEntry = skipped[sIdx]
        if (sEntry instanceof Map && sEntry.condIdx == null) {
            sEntry.condIdx = i
        }
    }
    return cIdx
}

// Structured Required Expression creation for RM 5.1 (STPage wire format: docs/rm_wire_format.md).
// Returns [success, conditionIndices, settingsApplied, settingsSkipped]. An add that fails with nothing
// committed switches "Required Expression" back off when it was the one to switch it on, so a refused
// add never leaves the rule with an empty gate enabled; useSTLeftOn reports when that could not be done.
private Map _rmAddRequiredExpression(Integer appId, Map exprSpec) {
    Boolean useSTBefore = null
    try { useSTBefore = _rmFetchConfigJson(appId)?.settings?.useST?.toString() == "true" } catch (Exception e) {
        mcpLog("warn", "rm-native", "addRequiredExpression: could not read useST on app ${appId} before the add (${e.message}); a failed add will not switch it back off")
    }
    def out
    try {
        out = _rmAddRequiredExpressionWalk(appId, exprSpec)
    } catch (Exception e) {
        if (_rmUndoUseSTAfterFailedAdd(appId, useSTBefore)) {
            mcpLog("error", "rm-native", "addRequiredExpression: app ${appId} may be left with Required Expression switched on and empty (runs ungated)")
            def msg = "${_rmStripNotTouched(e.message ?: e.toString())} ${_rmUseSTLeftOnText()}".toString()
            throw (e instanceof IllegalArgumentException) ? new IllegalArgumentException(msg, e) : new IllegalStateException(msg, e)
        }
        throw e
    }
    if (out?.success != true && _rmUndoUseSTAfterFailedAdd(appId, useSTBefore)) {
        out.useSTLeftOn = true
        out.error = "${_rmStripNotTouched(out.error ?: 'addRequiredExpression failed.')} ${_rmUseSTLeftOnText()}".toString()
    }
    return out
}

// The "RM is not touched" marker classifies an error as a refusal before any change; it is untrue
// once the gate was switched on and could not be switched back off.
private String _rmStripNotTouched(String msg) {
    (msg ?: "").replace(" RM is not touched.", "").replace("RM is not touched", "the rule WAS changed")
}

private String _rmUseSTLeftOnText() {
    "Required Expression could not be switched back off, so the rule may run ungated -- turn useST off or add an expression."
}

// Switches useST back off after a failed add that turned it on. Returns true when it is (or may be)
// left on with nothing committed. With the prior value unknown it only reports, never switches.
private boolean _rmUndoUseSTAfterFailedAdd(Integer appId, Boolean useSTBefore) {
    if (useSTBefore == true) return false
    try {
        if (_rmFetchConfigJson(appId)?.settings?.useST?.toString() != "true") return false
        def toks = _rmReadExpressionTokens(appId)
        if (useSTBefore == null) return toks == null || toks.isEmpty()
        if (toks == null) {
            mcpLog("warn", "rm-native", "addRequiredExpression: the expression of app ${appId} could not be read after the failed add; useST left as is")
            return true
        }
        if (!toks.isEmpty()) return false
        _rmWriteSettingOnPage(appId, "mainPage", "useST", false, [], "bool", [])
        return _rmFetchConfigJson(appId)?.settings?.useST?.toString() == "true"
    } catch (Exception e) {
        mcpLog("warn", "rm-native", "addRequiredExpression: switching Required Expression back off on app ${appId} after the failed add failed (${e.message})")
        return true
    }
}

Map _rmAddRequiredExpressionWalk(Integer appId, Map exprSpec) {
    // Shape validation, deviceId normalization and existence (one hub GET per deviceId); throws
    // IllegalArgumentException before any wizard write.
    def validated = _rmValidateRequiredExpressionSpec(exprSpec, "addRequiredExpression")
    def conditions = exprSpec.conditions
    def operator = validated.operator
    def opsList = validated.opsList

    def applied = []
    def skipped = []

    // deviceId normalization + deviceId/operator/operators existence-and-shape
    // validation runs in _rmValidateRequiredExpressionSpec (called at the top of
    // this method), shared with the in-place replace path.

    // writeST routes each STPage write by its persistence check, so a rejected write is reported. The
    // page-schema cache is filled by each POST response and cleared by every write, for this build only.
    def rmCache = [:]
    def writeST = { Map params, String fieldKey, Object fieldValue, String label = null ->
        def wr = _rmWriteSubPageField(appId, "STPage", "mainPage", "name", 0, params, fieldKey, fieldValue, rmCache)
        if (wr?.persisted) {
            applied << (label ?: fieldKey)
        } else if (wr?.verifyFetchFailed) {
            skipped << [key: fieldKey, label: (label ?: fieldKey), reason: "verification_fetch_failed", value: fieldValue, verifyError: wr?.verifyError]
        } else {
            skipped << [key: fieldKey, label: (label ?: fieldKey), reason: "silent_rejection", value: fieldValue, schemaUnchanged: true, available: wr?.afterKeys]
        }
        return wr
    }

    // Step 1: useST=true on mainPage reveals the Required Expression href. When it is already set the
    // write reads as silent_rejection; that skip is relabelled informational.
    int skippedBeforeUseST = skipped.size()
    _rmWriteSettingOnPage(appId, "mainPage", "useST", true, applied, "bool", skipped, rmCache)
    if (skipped.size() > skippedBeforeUseST) {
        def useStSkip = skipped[skippedBeforeUseST]
        if (useStSkip instanceof Map && useStSkip.key == "useST" && useStSkip.reason == "silent_rejection") {
            useStSkip.reason = "useST_idempotent_noop"
        }
    }

    // Step 1b: a rule that already has a Required Expression opens STPage in edit mode (editST, cancelST,
    // ...) with no cond selector; refuse and point at replaceRequiredExpression.
    try {
        // Thread rmCache so this precheck's STPage GET warms the cache: nothing writes STPage
        // between here and the walk's first writeST(cond,"a"), so that write's pre-fetch is a HIT
        // instead of a second back-to-back GET of the same page (F2).
        def preNames = _rmCollectPageInputNames(appId, "STPage", rmCache)
        // Add path uses the stricter three-field tell (cancelST+editST+stopOnST,
        // no inline new-condition selector) via _rmIsCommittedRETell(requireStopOnST=true).
        if (_rmIsCommittedRETell(preNames, true)) {
            return [
                success: false,
                error: "Rule ${appId} already has a Required Expression; change it with replaceRequiredExpression (see it with hub_get_app_config(appId=${appId}, includeSettings=true)).",
                requiredExpressionAlreadyExists: true
            ]
        }
    } catch (Exception preExc) {
        // Pre-check fetch failed -- do not block the operation on a transient read
        // error; fall through to the normal walk (which has its own fail-loud paths).
        mcpLog("warn", "rm-native", "addRequiredExpression: pre-walk existing-RE check fetch failed for app ${appId} (${preExc.message ?: preExc}); proceeding with the walk")
    }

    // Step 2. Walk each condition through STPage's wizard.
    def hrefParams = [unUsed: null]
    def conditionIndices = []
    // A failed condition leaves STPage mid-edit; cancelCapab closes it so the next call starts clean.
    // If that cancel fails, wizardStuck tells the caller to restore or press cancelCapab on STPage.
    def wizardCleanupFailed = false
    def wizardCleanupErr = null
    // cancelledByWalker stops a second cancelCapab after the walker already cancelled; currentCondIdx
    // names the condition in cancel warnings.
    def cancelledByWalker = false
    def currentCondIdx = -1
    def cancelInFlightCondition = {
        cancelledByWalker = true
        // As the UI does: the button's stateAttribute, then a render that applies it. A bare click left
        // state.cancelCapab pending, and the next add's cond=a consumed it, cancelling the NEW
        // condition (live, fw 2.5.2.129).
        try {
            _rmClickAppButton(appId, "cancelCapab", "cancelCapab", "STPage", rmCache)
            _rmCacheInvalidate(rmCache, appId)
            try { _rmFetchConfigJson(appId, "STPage", rmCache) } catch (Exception renderExc) {
                mcpLog("debug", "rm-native", "cancelCapab render on app ${appId} failed (${renderExc.message}); the next STPage read applies it")
            }
        }
        catch (Exception cancelExc) {
            wizardCleanupFailed = true
            wizardCleanupErr = cancelExc.message
            mcpLog("warn", "rm-native", "cancelCapab cleanup failed for app ${appId} on STPage at conditions[${currentCondIdx}]: ${cancelExc.message} -- wizard may stay open and confuse subsequent edits; result will carry wizardStuck=true")
        }
    }

    // Walks plain conditions and sub-expressions ({subExpression: {conditions, operator(s)}}). STPage's
    // cond takes the option values "a" (new condition) and "b" (sub-expression); oper's close-paren
    // option is its own value.
    def walkConds
    walkConds = { List condList, String outerOp, List outerOpsList ->
        condList.eachWithIndex { condRaw, i ->
            currentCondIdx = i
            if (!(condRaw instanceof Map)) {
                throw new IllegalArgumentException("conditions[${i}] is not a Map")
            }
            def cond = condRaw as Map

            if (cond.subExpression instanceof Map) {
                // Sub-expression branch: open paren, pick inner op, walk
                // inner conditions recursively, close paren.
                def subSpec = cond.subExpression as Map
                def subConds = subSpec.conditions
                if (!(subConds instanceof List) || subConds.isEmpty()) {
                    throw new IllegalArgumentException("conditions[${i}].subExpression.conditions must be a non-empty List")
                }
                def subOp = subSpec.operator?.toString()?.toUpperCase()
                def subOpsList = (subSpec.operators instanceof List) ? (subSpec.operators as List).collect { it?.toString()?.toUpperCase() } : null
                if (subOp && !(subOp in ["AND", "OR", "XOR"])) {
                    throw new IllegalArgumentException("conditions[${i}].subExpression.operator must be AND/OR/XOR (got '${subOp}')")
                }
                if (subConds.size() > 1 && !subOp && !subOpsList) {
                    throw new IllegalArgumentException("conditions[${i}].subExpression with ${subConds.size()} conds requires operator or operators")
                }
                // Sub-expression: cond=b, each inner condition (cond=a ... hasAll) with its gap oper, then
                // oper=end-sub-expression. No oper before the first inner condition: that renders the paren as a
                // Broken Condition. Sending the label instead of "b" stores the label and breaks the walk.
                writeST(hrefParams, "cond", "b", "cond(open-paren)")
                // Recurse into inner conditions. walkConds itself writes
                // the gap-oper between inner conds at index i<size-1.
                walkConds.call(subConds, subOp, subOpsList)
                // Close paren — live UI uses literal label "end-sub-expression )"
                // for this oper option (it's not encoded as a single-letter
                // value, so the label IS the value here).
                writeST(hrefParams, "oper", "end-sub-expression )", "oper(close-paren)")
            } else {
                // Plain condition: cond=a, discover the RM-assigned cIdx, validate
                // the capability option, then delegate all field writes to
                // _rmWalkConditionReveal (handles the rCapab->rDev->comparator->
                // state->hasAll sequence in the correct progressive-disclosure order).
                def cap = cond.capability?.toString()?.trim()
                if (!cap) {
                    throw new IllegalArgumentException("conditions[${i}].capability is required")
                }
                _rmCheckCondSpec(cond as Map, "condition", "conditions[${i}]".toString())
                writeST(hrefParams, "cond", "a", "cond")
                cancelledByWalker = false
                try {
                    _rmWalkNewConditionSlot(appId, cond, i, [
                        writeST: writeST,
                        cancelInFlightCondition: cancelInFlightCondition,
                        hrefParams: hrefParams,
                        applied: applied,
                        skipped: skipped,
                        cache: rmCache,
                        conditionIndices: conditionIndices,
                        selector: "cond=a"
                    ])
                } catch (Exception perCondExc) {
                    // Skip the cancel when the walker already cancelled; a second cancelCapab fails and would report a
                    // false wizardStuck.
                    if (!cancelledByWalker) {
                        cancelInFlightCondition()
                    }
                    if (wizardCleanupFailed) {
                        // Both the per-condition write AND the cancel cleanup
                        // failed — the wizard is still open. Embed the marker
                        // so the dispatcher's catch can surface wizardStuck=true
                        // alongside the original error.
                        throw _rmFail(new IllegalStateException("${perCondExc.message} [wizardStuck -- cancelCapab cleanup also failed: ${wizardCleanupErr}]", perCondExc), _rmOutcomeFlags(perCondExc) + [wizardStuck: true])
                    }
                    throw perCondExc
                }
            }
            // Joining operator for non-last condition at this level.
            if (i < condList.size() - 1) {
                def gapOp = outerOpsList ? outerOpsList[i] : outerOp
                if (gapOp) {
                    writeST(hrefParams, "oper", gapOp)
                }
            }
        }
    }
    walkConds.call(conditions, operator, opsList)

    // Step 3: seal with hasRule. walkConds already assembled the formula (each oper adds the next
    // condition), so writing more cond/oper pairs here duplicates conditions. Without hasRule the
    // conditions show as unused and the expression stays false. writeST posts hasRule=button through
    // /installedapp/update/json, which commits; doneST is the separate exit button.
    writeST(hrefParams, "hasRule", "button", "hasRule")

    // Step 3b: doneST (/installedapp/btn, stateAttribute=doneST) moves STPage from build mode, where cond
    // is still required and the UI shows "Required Fields missing", to done mode (editST, cancelST, ...).
    try {
        _rmClickAppButton(appId, "doneST", "doneST", "STPage", rmCache)
    } catch (Exception doneStExc) {
        // A failed doneST leaves the expression committed; only the UI popup remains, repairable with
        // button='doneST' on STPage.
        mcpLog("warn", "rm-native", "addRequiredExpression: doneST click failed for app ${appId} (${doneStExc.message ?: doneStExc.toString()}) -- RE is committed but STPage stays in build mode; browser navigation may show validation popup")
    }

    // Step 4: _action_previous=Done back to mainPage with the done-mode settings.
    _rmSubmitSubPageDone(appId, "STPage", "mainPage", "name", hrefParams, rmCache)

    // Step 4b: the build leaves atomicState.predCapabs stale, which would wrap the next action in
    // IF(**Broken Condition**). The ghost ifThen clear costs about 9 round trips, so it is deferred to the
    // next addAction (_rmClearPredCapabsViaGhostIfThen). STPage still opens cleanly: done mode leaves no
    // required fields.
    _rmMarkPredClearPending(appId)

    // Step 5: STPage accepts bad input without baking. If mainPage still shows "Define Required
    // Expression", report failure; if that read fails, report verificationFetchFailed, not success.
    def mainCfg = null
    def verificationFetchFailed = false
    try { mainCfg = _rmFetchConfigJson(appId, "mainPage", rmCache) }
    catch (Exception verifyExc) {
        verificationFetchFailed = true
        mcpLog("warn", "rm-native", "addRequiredExpression: post-commit mainPage fetch failed for app ${appId} (${verifyExc.message}) -- cannot verify expression baked; returning verificationFetchFailed=true")
    }
    if (verificationFetchFailed) {
        return [
            success: false,
            verificationFetchFailed: true,
            conditionIndices: conditionIndices,
            settingsApplied: applied,
            settingsSkipped: skipped,
            error: "The Required Expression was written but could not be read back; check it with hub_get_app_config(appId=${appId}) before retrying."
        ]
    }
    def mainParagraphs = (mainCfg?.configPage?.sections ?: []).collectMany { sect ->
        (sect?.body ?: []).findAll { b -> b instanceof Map && (b.element == "paragraph" || b.element == "href") }
                          .collect { it.description?.toString() ?: "" }
    }
    def joinedParagraphs = mainParagraphs.join("\n")
    def expressionRendered = !joinedParagraphs.contains("Define Required Expression")
    if (!expressionRendered) {
        return [
            success: false,
            partial: true,
            hubRenderError: true,
            conditionIndices: conditionIndices,
            settingsApplied: applied,
            settingsSkipped: skipped,
            error: "The Required Expression did not bake: the rule still shows none. Check the condition fields with discover (kind:'condition') and the deviceIds with hub_list_devices.",
            repairHints: [
                "Verify every deviceIds entry exists via hub_list_devices.",
                "Verify the 'state' value matches the capability's domain (Switch: 'on'/'off', Motion: 'active'/'inactive', Contact: 'open'/'closed', Lock: 'locked'/'unlocked', etc.).",
                "Read the rule with hub_get_app_config(appId, includeSettings=true): rDev_<N> = {<id>: null} means the device did not resolve; no rCapab_<N> means the condition was not committed."
            ]
        ]
    }

    // Informational sentinels (_rmInformationalSkippedReasons, shared with _rmAddAction) don't flip
    // partial; every other reason does.
    def informationalReasons = _rmInformationalSkippedReasons()
    def hasDegradation = skipped.any {
        it instanceof Map && it.reason != null && !(it.reason in informationalReasons)
    }
    if (hasDegradation) {
        // As in _rmAddTrigger: comparator_force_written_unverified gets a verify hint, not the rewrite hint.
        def degEntries = skipped.findAll {
            it instanceof Map && it.reason != null && !(it.reason in informationalReasons)
        }
        def forceWrittenKeys = degEntries.findAll { it.reason == "comparator_force_written_unverified" }*.key.findAll { it != null }
        // Both comparator_force_written_unverified and comparator_not_representable_for_enum_attribute
        // get their own specific hint below, so exclude them from the generic degraded-path hint.
        def specificHintReasons = ["comparator_force_written_unverified", "comparator_not_representable_for_enum_attribute", "comparator_not_offered"]
        def lostEntries = degEntries.findAll { !(it.reason in specificHintReasons) }
        def reRepairHints = []
        if (!lostEntries.isEmpty()) {
            // Count distinct conditions with lost writes (by condIdx); entries without one came from outside a
            // condition (the useST write).
            def uniqueCondIdxs = lostEntries.collect { it.condIdx }.findAll { it != null }.unique().size()
            def deg = uniqueCondIdxs > 0 ? uniqueCondIdxs : lostEntries.size()
            def cw = (deg == 1) ? "condition" : "conditions"
            reRepairHints << "${deg} ${cw} did not fully land (see each settingsSkipped reason). If the expression renders right in hub_get_app_config(appId), nothing is missing; otherwise replace it with replaceRequiredExpression."
        }
        if (!forceWrittenKeys.isEmpty()) {
            def cmpWord = forceWrittenKeys.size() == 1 ? "Comparator" : "Comparators"
            def cmpVerb = forceWrittenKeys.size() == 1 ? "was" : "were"
            reRepairHints << "${cmpWord} ${forceWrittenKeys.join(', ')} ${cmpVerb} written but could not be read back; check the expression in hub_get_app_config(appId) before writing again."
        }
        def notRepresentable = degEntries.findAll { it.reason in _rmUnrepresentedComparatorReasons() }
        notRepresentable.each { sk ->
            reRepairHints << _rmUnrepresentedComparatorHint(sk as Map)
        }
        // A condition whose comparator RM cannot express does not mean what was asked, so the
        // expression is reported as not added as requested, with the committed slots named.
        def out = [
            success: notRepresentable.isEmpty(),
            partial: true,
            conditionIndices: conditionIndices,
            settingsApplied: applied,
            settingsSkipped: skipped,
            repairHints: reRepairHints
        ]
        if (notRepresentable) {
            out.error = "addRequiredExpression: condition ${notRepresentable.collect { it.condIdx != null ? it.condIdx + 1 : '?' }.join(', ')} asks for a comparator Rule Machine does not offer there, and was committed without it. Replace the expression (replaceRequiredExpression) or restore the pre-edit backup if one was taken (restoreHint).".toString()
        }
        return out
    }
    return [
        success: true,
        partial: false,
        conditionIndices: conditionIndices,
        settingsApplied: applied,
        settingsSkipped: skipped,
        repairHints: []
    ]
}

// Every input field name visible on a config page (e.g. STPage, read for the committed-RE tell).
Set _rmCollectPageInputNames(Integer appId, String pageName, Map cache = null) {
    def cfg = _rmFetchConfigJson(appId, pageName, cache)
    return (cfg?.configPage?.sections ?: []).collectMany { sec ->
        (sec?.input ?: []).collect { it?.name?.toString() }
    }.findAll { it } as Set
}

// The list of rule-health problems NEW vs `baselineHealth` -- the attributable-regression
// set the replace restore gates on. Two layers: a `now - baseline` STRING set-diff over
// issues + structuralIssues, plus a COUNT-aware broken-marker delta (the "broken markers in
// render" issue string collapses multiplicity, so a baseline that already carries one
// **Broken Condition** would mask a genuinely-NEW broken instance under a string diff -- a
// per-marker count INCREASE over baseline is a new break, named "<marker> (<now> vs <base>)").
// An UNCHANGED count or a pre-existing imbalance present in the baseline is NOT new, so a
// clean replace on an already-imbalanced rule is never spuriously rolled back. The restore
// message names ONLY these new issues, not the full current-issue list.
private List _rmHealthRegressionNewIssues(Map baselineHealth, Map nowHealth) {
    // A couldn't-check post-op probe (unreadable: NEITHER source read) carries no
    // positive evidence of anything: its "health check failed" issue strings are
    // fetch diagnostics, not regressions, and set-diffing them against the baseline
    // would fire the restore (or fail the batch op) on COMMITTED work over a
    // transient probe failure -- the same poison _rmHealthGatePass closes on the
    // plain ok-gates. No verdict, no attributable regression.
    if (nowHealth?.unreadable == true) return []
    def baselineIssues = (baselineHealth?.issues ?: []) as Set
    def baselineStructural = (baselineHealth?.structuralIssues ?: []) as Set
    def nowIssues = (nowHealth?.issues ?: []) as Set
    def nowStructural = (nowHealth?.structuralIssues ?: []) as Set
    def newIssues = ((nowIssues - baselineIssues) + (nowStructural - baselineStructural)).collect { it.toString() }
    def baselineMarkerCounts = (baselineHealth?.brokenMarkerCounts instanceof Map) ? (baselineHealth.brokenMarkerCounts as Map) : [:]
    def nowMarkerCounts = (nowHealth?.brokenMarkerCounts instanceof Map) ? (nowHealth.brokenMarkerCounts as Map) : [:]
    nowMarkerCounts.each { marker, cnt ->
        def baseCnt = (baselineMarkerCounts.get(marker) ?: 0) as Integer
        if ((cnt as Integer) > baseCnt) newIssues << "${marker} (${cnt} vs ${baseCnt})".toString()
    }
    return newIssues
}

// Boolean gate over _rmHealthRegressionNewIssues -- true when the replace introduced any new
// rule-health problem. Shared by the standalone finalize and the sole-op patches-batch
// restore so both use identical semantics.
private boolean _rmHealthRegressedVsBaseline(Map baselineHealth, Map nowHealth) {
    return !_rmHealthRegressionNewIssues(baselineHealth, nowHealth).isEmpty()
}

// Shared trailing-finalize for a committed Required Expression write (add OR replace).
//
// After the inner walker has committed a new expression (innerResult.success==true),
// this fires the leaf-operation updateRule click, runs the health check, and assembles
// the success/failure envelope. add and replace shared ~40 lines of identical
// updateRule try/catch + health + repairHints + the success-shape forwarding before
// this was extracted; the only intended difference is the success derivation (replace
// uses the stricter `== true`, add keeps the permissive `!= false` for compatibility),
// carried by the `strictSuccess` flag.
//
// `verb` is "added" / "replaced" -- it tunes the note and the requiredExpressionReplaced
// stamp (replace stamps it; add omits it). `restoreOnFailure`, when supplied (replace
// only), is the replace's token rollback: a POST-commit health flip OR a rejected trailing
// updateRule puts the original tokens back. It is invoked with the failure message + the
// trailing-updateRule diagnostic slots so the envelope carries requiredExpressionRestored
// honestly. The add path passes null (the trailing-updateRule failure is surfaced via the
// slots only).
//
// `finalizeOpts` (replace path) carries three knobs:
// - baselineHealth: the health before the replace; only issues NEW against it roll back, so a
//   pre-existing imbalance (a rule mid-construction) does not undo a clean replace.
// - deferUpdateRule: in a patches[] batch, skip the updateRule click and the health rollback (the
//   batch end owns both); the health check still fills the per-op envelope.
// - deferredRestoreExtra: merged into the internal _deferredRERestore record for the batch end.
private Map _rmFinalizeRequiredExpressionWrite(Integer appId, Map innerResult, Map backup, String verb, boolean strictSuccess, Closure restoreOnFailure = null, Map finalizeOpts = [:]) {
    // deferUpdateRule: see the docblock.
    def deferUpdateRule = (finalizeOpts?.deferUpdateRule == true)
    def updateRuleFailed = false
    def expressionNotLive = false
    def updateRuleError = null
    if (!deferUpdateRule) {
        try { _rmClickAppButton(appId, "updateRule") }
        catch (Exception updateExc) {
            updateRuleFailed = true
            expressionNotLive = true
            updateRuleError = updateExc.message
            mcpLog("warn", "rm-native", "${verb == 'replaced' ? 'replaceRequiredExpression' : 'addRequiredExpression'}: trailing updateRule click failed for app ${appId} -- expression may not be live: ${updateExc.message}")
        }
    }
    // The expression is already committed, so an unreadable health check is a couldn't-check
    // (never a regression), not a failure that would hide the committed write.
    def health
    try { health = _rmCheckRuleHealth(appId) } catch (Exception healthExc) {
        mcpLog("warn", "rm-native", "${verb == 'replaced' ? 'replaceRequiredExpression' : 'addRequiredExpression'}: health check on app ${appId} failed (${healthExc.message})")
        health = [ok: false, unreadable: true, checkErrors: [healthExc.message ?: healthExc.toString()]]
    }
    def reCondCount = innerResult?.conditionIndices?.size() ?: 0
    def repairHints = (innerResult?.repairHints as List) ?: []
    if (updateRuleFailed) {
        repairHints = repairHints + ["The expression is written but updateRule was rejected, so the rule does not use it yet; retry hub_set_rule(button='updateRule', confirm=true)."]
    }

    // Replace only: a rejected updateRule, or health issues new since the pre-replace baseline, restore
    // the original tokens (_rmHealthRegressedVsBaseline), so an unrelated earlier problem doesn't.
    def baselineHealth = (finalizeOpts?.baselineHealth instanceof Map) ? (finalizeOpts.baselineHealth as Map) : null
    def newHealthIssues = _rmHealthRegressionNewIssues(baselineHealth, health)
    boolean healthRegressed = !newHealthIssues.isEmpty()

    // The health-regression rollback is suppressed in defer mode: the batch owns rule-level health
    // at its single batch-end click. A regression still flips success:false so opsOk reflects it.
    if (!deferUpdateRule && restoreOnFailure != null && (healthRegressed || updateRuleFailed)) {
        def why = healthRegressed ?
            "the replacement introduced new rule-health problems that were not present before (${newHealthIssues.join('; ') ?: 'health check failed'})" :
            "the trailing updateRule click was rejected (${updateRuleError})"
        def restored = restoreOnFailure("replaceRequiredExpression: ${why}.", [
            updateRuleFailed: updateRuleFailed,
            expressionNotLive: expressionNotLive,
            updateRuleError: updateRuleError,
            conditionIndices: innerResult?.conditionIndices,
            settingsApplied: innerResult?.settingsApplied,
            settingsSkipped: innerResult?.settingsSkipped,
            health: health,
            repairHints: repairHints
        ])
        return (restored ?: [:]) + [appId: appId, backup: backup, partial: true]
    }

    // Success gate is !healthRegressed (equal to health.ok on add). In a batch, updateRule runs at the end.
    def envelope = [
        success: (strictSuccess ? (innerResult?.success == true) : (innerResult?.success != false)) && !healthRegressed && !updateRuleFailed,
        partial: (innerResult?.partial == true) || updateRuleFailed,
        appId: appId,
        backup: backup,
        conditionIndices: innerResult?.conditionIndices,
        settingsApplied: innerResult?.settingsApplied,
        settingsSkipped: innerResult?.settingsSkipped,
        error: innerResult?.error,
        verificationFetchFailed: innerResult?.verificationFetchFailed,
        hubRenderError: innerResult?.hubRenderError,
        updateRuleFailed: updateRuleFailed,
        expressionNotLive: expressionNotLive,
        updateRuleError: updateRuleError,
        repairHints: repairHints,
        health: health,
        note: "Required Expression ${verb} with ${reCondCount} ${reCondCount == 1 ? 'condition' : 'conditions'}; updateRule ${deferUpdateRule ? 'deferred to the batch-end click' : (updateRuleFailed ? 'FAILED -- expression may not be live' : 'fired')}."
    ]
    // requiredExpressionAlreadyExists is an ADD-path diagnostic only (a replace never runs the
    // add path's existing-RE guard), so it is emitted only for the add verb.
    if (verb != "replaced") envelope.requiredExpressionAlreadyExists = innerResult?.requiredExpressionAlreadyExists
    if (verb == "replaced") envelope.requiredExpressionReplaced = (innerResult?.success == true)
    // A deferred replace that committed hands the batch its original tokens and baseline health
    // (internal key, stripped from the response). The batch restores them if its updateRule fails, or on
    // a health regression only when this replace was the batch's only op.
    if (deferUpdateRule && verb == "replaced" && envelope.requiredExpressionReplaced == true) {
        envelope._deferredRERestore = [backup: backup,
            baselineHealth: (finalizeOpts?.baselineHealth instanceof Map) ? finalizeOpts.baselineHealth : null] +
            ((finalizeOpts?.deferredRestoreExtra instanceof Map) ? (finalizeOpts.deferredRestoreExtra as Map) : [:])
    }
    return envelope
}

// Token editor: RM keeps the committed expression as the token list state.eval['0'] (condition ids,
// operators, parens). editST + editToken open it; insertTok (index == count appends), deleteToken,
// doneToken/doneST are named by token index. newToken<k> picks the inserted token (`*` opens a new
// condition's rCapab_<n>). A click applies on the next STPage render, so each is followed by a fetch.

// The committed Required Expression tokens (state.eval['0']), or null when ruleBuilderJson cannot
// be read. A rule without an expression reads as an empty list.
private List _rmReadExpressionTokens(Integer appId) {
    def text
    try { text = hubInternalGet("/app/ruleBuilderJson/${appId}") } catch (Exception e) {
        mcpLog("warn", "rm-native", "reading the Required Expression of app ${appId} failed: ${e.message}")
        return null
    }
    if (!text) return null
    def parsed
    try { parsed = new groovy.json.JsonSlurper().parseText(text) } catch (Exception e) { return null }
    if (!(parsed instanceof Map)) return null
    def toks = (parsed.eval instanceof Map) ? parsed.eval["0"] : null
    return (toks instanceof List) ? new ArrayList(toks as List) : []
}

// A token as the newToken picker spells it: condition ids as their number, operators verbatim.
private String _rmTokenValue(Object tok) {
    return tok?.toString()?.trim()
}

private boolean _rmTokensEqual(List a, List b) {
    if (a == null || b == null || a.size() != b.size()) return false
    for (int i = 0; i < a.size(); i++) {
        if (_rmTokenValue(a[i]) != _rmTokenValue(b[i])) return false
    }
    return true
}

// Condition ids in a token list (numeric tokens).
private List _rmTokenConditionIds(List toks) {
    return (toks ?: []).findAll { _rmTokenValue(it)?.isInteger() }.collect { _rmTokenValue(it) as Integer }
}

// Click a token-editor / STPage button and render STPage so the click takes effect. Returns the
// rendered page.
private Map _rmTokClick(Integer appId, String name, String stateAttr, Map cache = null) {
    _rmClickAppButton(appId, name, stateAttr, "STPage", cache)
    return _rmFetchConfigJson(appId, "STPage", cache)
}

private Set _rmPageInputNames(Map cfg) {
    return ((cfg?.configPage?.sections ?: []).collectMany { it?.input ?: [] }
        .collect { it?.name?.toString() }.findAll { it }) as Set
}

// Open the token editor on a rule with a committed Required Expression. Throws when it does not
// open (nothing in the expression has changed at that point).
private void _rmTokEnterEditor(Integer appId, Map cache = null) {
    _rmTokClick(appId, "editST", "editST", cache)
    def names = _rmPageInputNames(_rmTokClick(appId, "editToken", "editToken", cache))
    // An expression just built through the new-expression selector reopens with an empty insert pending.
    if (names.contains("cancelInsert")) names = _rmPageInputNames(_rmTokClick(appId, "cancelInsert", "cancelInsert", cache))
    if (!names.contains("doneToken")) {
        throw new IllegalStateException("the Required Expression token editor did not open (STPage shows ${names.sort().join(', ')})")
    }
}

// Close the token editor and the Required Expression page: doneToken returns to the expression
// view, doneST commits it. The caller fires updateRule.
private void _rmTokLeaveEditor(Integer appId, Map cache = null) {
    def names = _rmPageInputNames(_rmFetchConfigJson(appId, "STPage", cache))
    if (names.contains("doneToken")) _rmTokClick(appId, "doneToken", "doneToken", cache)
    _rmTokClick(appId, "doneST", "doneST", cache)
}

// Insert one token at `position` (an insertTok index; the token count appends). For `*` the
// condition form is left open for the caller to walk.
private void _rmTokInsert(Integer appId, int position, String value, Closure writeST, Map hrefParams, Map cache = null) {
    def cfg = _rmTokClick(appId, position.toString(), "insertTok", cache)
    def field = (cfg?.configPage?.sections ?: []).collectMany { it?.input ?: [] }
        .find { it?.name?.toString()?.startsWith("newToken") }
    if (!field) {
        throw new IllegalStateException("the token editor did not offer a new token at position ${position} (STPage shows ${_rmPageInputNames(cfg).sort().join(', ')})")
    }
    // The picker's option VALUES (an operator / paren, a condition id, or `*`), whatever container
    // the page uses for them.
    def opts = field.options
    def offered = (opts instanceof Map) ? opts.keySet().collect { it?.toString() } :
        ((opts instanceof List) ? opts.collectMany { o ->
            (o instanceof Map) ? (o.containsKey("value") ? [o.value?.toString()] : o.keySet().collect { it?.toString() }) : [o?.toString()]
        } : [])
    offered = offered.findAll { it }
    if (offered && !offered.contains(value)) {
        throw new IllegalStateException("token '${value}' is not offered by the expression editor (offered: ${offered.join(', ')})")
    }
    def wr = writeST(hrefParams, field.name.toString(), value, "token(${value})")
    if (!(wr instanceof Map) || wr.persisted != true) {
        throw new IllegalStateException("the expression editor did not accept token '${value}' at position ${position}")
    }
}

// Inside the token editor: drop every token past target.size() (tokens this edit appended,
// including the blank token a cancelled condition leaves), then report whether the live tokens
// equal `target`.
private boolean _rmTokTrimTo(Integer appId, List target, Map cache = null) {
    for (int guard = 0; guard < 60; guard++) {
        def live = _rmReadExpressionTokens(appId)
        if (live == null) return false
        if (_rmTokensEqual(live, target)) return true
        if (live.size() <= target.size()) return false
        _rmTokClick(appId, target.size().toString(), "deleteToken", cache)
    }
    return false
}

// Remove conditions from the rule's condition pool (Manage Conditions' deleteCon). A condition any
// expression still references (the Required Expression or an action's IF, all eval lists) is never
// removed, and nothing is when the rule's state cannot be read. Returns the ids whose settings are gone.
List _rmDeleteExpressionConditions(Integer appId, Collection ids) {
    if (!ids) return []
    def st = _rmReadRuleState(appId)
    if (st == null) {
        mcpLog("warn", "rm-native", "not removing conditions ${ids} from app ${appId}: its expressions could not be read")
        return []
    }
    def inUse = ((st.eval instanceof Map) ? (st.eval as Map).values() : []).collectMany { v ->
        (v instanceof List) ? _rmTokenConditionIds(v as List).collect { it.toString() } : []
    } as Set
    ids = ids.findAll { !inUse.contains(it.toString()) }
    if (!ids) return []
    // RM ignores deleteCon until Manage Conditions is opened from STPage the way the UI does (pred:true).
    try {
        _rmNavigateToPage(appId, "STPage", "selectConditions", 0, "name", [pred: true])
    } catch (Exception navExc) {
        mcpLog("warn", "rm-native", "Required Expression edit: opening Manage Conditions on app ${appId} failed (${navExc.message})")
    }
    ids.each { id ->
        try {
            _rmClickAppButton(appId, id.toString(), "deleteCon", "selectConditions", null)
            _rmFetchConfigJson(appId, "selectConditions")
        } catch (Exception e) {
            mcpLog("warn", "rm-native", "Required Expression edit: removing condition ${id} from app ${appId} failed (${e.message})")
        }
    }
    def remaining
    try { remaining = _rmFetchSettingsByName(appId)?.keySet() as Set } catch (Exception e) { remaining = null }
    if (remaining == null) return []
    return ids.findAll { !remaining.contains("rCapab_${it}".toString()) }.collect { it as Integer }
}

// Set the committed Required Expression to exactly `target` (condition ids already in the rule's
// pool plus operator / paren tokens), then commit and confirm. Rolls a replace back in place.
// Returns [restored: Boolean, error: String?].
private Map _rmTokSetExpression(Integer appId, List target) {
    // An empty target would delete every token and leave the rule ungated.
    if (!target) return [restored: false, error: "no original tokens were recorded, so nothing was changed"]
    def cache = [:]
    def hrefParams = [unUsed: null]
    def writeST = { Map params, String key, Object value, String label = null ->
        _rmWriteSubPageField(appId, "STPage", "mainPage", "name", 0, params, key, value, cache)
    }
    try {
        def names = _rmPageInputNames(_rmFetchConfigJson(appId, "STPage", cache))
        if (names.contains("cancelCapab")) names = _rmPageInputNames(_rmTokClick(appId, "cancelCapab", "cancelCapab", cache))
        if (names.contains("cancelInsert")) names = _rmPageInputNames(_rmTokClick(appId, "cancelInsert", "cancelInsert", cache))
        if (!names.contains("doneToken")) {
            if (names.contains("editToken")) _rmTokClick(appId, "editToken", "editToken", cache)
            else _rmTokEnterEditor(appId, cache)
        }
        // The target goes in front first and only the tokens after it are deleted, so the expression
        // is never empty if a click fails part way.
        target.eachWithIndex { tok, i -> _rmTokInsert(appId, i, _rmTokenValue(tok), writeST, hrefParams, cache) }
        for (int guard = 0; guard < 60; guard++) {
            def live = _rmReadExpressionTokens(appId)
            if (live == null) throw new IllegalStateException("the rule's expression state could not be read")
            if (live.size() <= target.size()) break
            _rmTokClick(appId, target.size().toString(), "deleteToken", cache)
        }
        _rmTokLeaveEditor(appId, cache)
        _rmClickAppButton(appId, "updateRule")
        def live = _rmReadExpressionTokens(appId)
        def committed = _rmIsCommittedRETell(_rmCollectPageInputNames(appId, "STPage"))
        if (_rmTokensEqual(live, target) && committed) return [restored: true]
        return [restored: false, error: "the rule's expression reads ${live} after the rollback (expected ${target})"]
    } catch (Exception e) {
        mcpLog("error", "rm-native", "Required Expression rollback on app ${appId} failed: ${e.message}")
        return [restored: false, error: e.message ?: e.toString()]
    }
}

// Roll a replaced Required Expression back to `origTokens` and report it in the replace envelope's
// terms: a failure while switching, a post-commit failure, and the patches[] batch end.
private Map _rmRevertRequiredExpression(Integer appId, List origTokens, String errMsg, Map carry = [:]) {
    def r = _rmTokSetExpression(appId, origTokens)
    if (r.restored == true) {
        mcpLog("warn", "rm-native", "replaceRequiredExpression: rolled app ${appId} back to its original Required Expression: ${errMsg}")
        return carry + [requiredExpressionRestored: true, originalPreserved: true,
            error: "${errMsg} The original Required Expression was put back and confirmed."]
    }
    mcpLog("error", "rm-native", "replaceRequiredExpression: rollback of app ${appId} failed (${r.error}): ${errMsg}")
    return carry + [requiredExpressionRestored: false, originalPreserved: false, partial: true,
        error: "${errMsg} Putting the original Required Expression back also failed (${r.error}); check the rule with hub_get_app_config(appId=${appId}) and rebuild it with addRequiredExpression if it is gone."]
}

// Restore from a backup: settings replay can't bring back the expression (it lives in app state), so
// each saved condition is re-walked from its settings and the tokens re-inserted through the token
// editor; the rendered expression confirms it.

// Condition id -> text from capabsfalse (capabstrue holds trigger texts, a separate id space).
// Current-value markup like "Switch(<span>off</span>)" is dropped before comparing; plain parentheses
// and raw `<` comparators stay.
private Map _rmConditionTexts(Map state) {
    def out = [:]
    if (state?.get("capabsfalse") instanceof Map) (state.get("capabsfalse") as Map).each { id, txt ->
        out.put(id?.toString(), txt?.toString()?.replaceAll(/\(\s*<[A-Za-z][^>]*>[^()]*<\/[A-Za-z]+>\s*\)/, "")
            ?.replaceAll(/<\/?[A-Za-z!][^>]*>/, "")?.replaceAll(/\s+/, " ")?.trim())
    }
    return out
}

// Triggers (capabstrue), actions (actionList) and the condition pool (capabsfalse) live in RM app
// state, which a settings replay cannot write. Remove what the live rule has beyond the snapshot
// through RM's own delete paths, and name what the snapshot has that the live rule still lacks.
Map _rmReconcileRuleStructure(Integer appId, Map snapshot) {
    def appState = snapshot?.statusJson?.appState
    if (!(appState instanceof List)) return [:]
    def snap = [:]
    appState.each { if (it instanceof Map && it.name != null) snap.put(it.name.toString(), it.value) }
    if (!["capabstrue", "capabsfalse", "actionList"].any { snap.containsKey(it) }) return [:]
    def trigs = { Map st -> (st?.capabstrue instanceof Map) ? (st.capabstrue as Map).keySet().collect { it.toString() } : [] }
    def conds = { Map st -> (st?.capabsfalse instanceof Map) ? (st.capabsfalse as Map).keySet().collect { it.toString() } : [] }
    def acts = { Map st -> (st?.actionList instanceof List) ? (st.actionList as List).collect { it.toString() } : [] }
    def live = _rmReadRuleState(appId)
    if (live == null) return [structureRestored: false, structureError: "the rule's triggers and actions could not be read after the restore"]

    def removed = [triggers: [], actions: [], conditions: []]
    def failures = []
    (trigs(live) - trigs(snap)).each { idx ->
        try { _rmRemoveTrigger(appId, idx as Integer); removed.triggers << idx }
        catch (Exception e) { failures << "trigger ${idx} (${e.message})".toString() }
    }
    // Rows go one at a time, last first, so a removal never shifts a row still to go. The per-row
    // balance refusal would block each half of an extra IF/END-IF pair, so balance is
    // checked once for the whole set and the per-row check skipped only when the set leaves the
    // rule no less balanced than it is now.
    def extraActs = acts(live) - acts(snap)
    boolean setBalanced = false
    if (extraActs) {
        try {
            def ordered = _rmOrderedActionIndices(appId)
            if (ordered != null) {
                def byName = (_rmFetchStatusJson(appId)?.appSettings ?: []).collectEntries { [(it?.name?.toString()): it] }
                def removing = extraActs.collect { it as Integer } as Set
                def issuesNow = _rmStructuralIssuesFromSequence(_rmStructuralSequenceFromSettings(byName, ([] as Set), ordered))
                def after = _rmStructuralIssuesFromSequence(_rmStructuralSequenceFromSettings(byName, removing, ordered))
                setBalanced = (after - issuesNow).isEmpty()
            }
        } catch (Exception e) {
            mcpLog("warn", "rm-native", "restore: block balance check for app ${appId} failed (${e.message}); extra rows are removed one by one under the per-row check")
        }
    }
    boolean actionDeleteFailed = false
    extraActs.reverse().each { idx ->
        try { _rmDeleteAction(appId, idx as Integer, setBalanced); removed.actions << idx }
        catch (Exception e) { failures << "action ${idx} (${e.message})".toString(); actionDeleteFailed = true }
    }
    // Rows removed as a balanced set without the per-row check: a set left half done can leave an open block.
    def blockIssues = []
    if (setBalanced && actionDeleteFailed) {
        try {
            def ordered = _rmOrderedActionIndices(appId)
            if (ordered != null) {
                def byName = (_rmFetchStatusJson(appId)?.appSettings ?: []).collectEntries { [(it?.name?.toString()): it] }
                blockIssues = _rmStructuralIssuesFromSequence(_rmStructuralSequenceFromSettings(byName, ([] as Set), ordered))
            }
        } catch (Exception e) {
            mcpLog("warn", "rm-native", "restore: re-checking block balance on app ${appId} failed (${e.message})")
        }
    }
    // Conditions any expression uses (the Required Expression or an action's IF) stay.
    def inExpr = ((live.eval instanceof Map) ? (live.eval as Map).values() : []).collectMany { v ->
        (v instanceof List) ? _rmTokenConditionIds(v as List).collect { it.toString() } : []
    }
    def extraConds = conds(live) - conds(snap) - inExpr
    if (extraConds) removed.conditions = _rmDeleteExpressionConditions(appId, extraConds.collect { it as Integer }).collect { it as Integer }
    // Actions the backup also has go back to its order, one move up at a time.
    boolean moved = false
    String orderError = null
    def wantOrder = acts(snap)
    for (int p = 0; wantOrder.size() > 1 && p < wantOrder.size() && !orderError; p++) {
        def current = null
        try { current = _rmOrderedActionIndices(appId)?.collect { it.toString() }?.findAll { wantOrder.contains(it) } } catch (Exception e) {
            mcpLog("warn", "rm-native", "restore: reading the action order of app ${appId} failed (${e.message})")
        }
        if (current == null) { orderError = "the action order could not be read"; break }
        int pos = current.indexOf(wantOrder[p])
        while (pos > p && !orderError) {
            def mv
            try { mv = _rmMoveAction(appId, wantOrder[p] as Integer, "up") } catch (Exception e) { mv = [success: false, error: e.message] }
            if (mv?.success != true) orderError = "moving action ${wantOrder[p]} up failed (${mv?.error ?: mv?.verifyHint ?: 'not confirmed'})".toString()
            else { moved = true; pos-- }
        }
    }
    String updateRuleError = null
    if (removed.triggers || removed.actions || removed.conditions || moved) {
        try { _rmClickAppButton(appId, "updateRule") } catch (Exception e) { updateRuleError = e.message ?: e.toString() }
    }

    def after = _rmReadRuleState(appId)
    if (after == null) return [structureRestored: false, structureError: "the rule's triggers and actions could not be read after the restore"]
    def snapTrigText = (snap.capabstrue instanceof Map) ? snap.capabstrue as Map : [:]
    def out = [structureRestored: true]
    if (removed.triggers) out.removedTriggers = removed.triggers
    if (removed.actions) out.removedActions = removed.actions
    if (removed.conditions) out.removedConditionIds = removed.conditions
    def problems = []
    def missingTrigs = trigs(snap) - trigs(after)
    def missingActs = acts(snap) - acts(after)
    def extraTrigs = trigs(after) - trigs(snap)
    def extraActsLeft = acts(after) - acts(snap)
    def extraLeft = conds(after) - conds(snap) - inExpr
    if (missingTrigs) {
        out.missingTriggers = missingTrigs.collect { [index: it, text: snapTrigText.get(it)?.toString()] }
        problems << "the backup's trigger(s) ${missingTrigs.collect { snapTrigText.get(it) ?: it }.join('; ')} are not on the rule".toString()
    }
    if (missingActs) {
        out.missingActions = missingActs
        problems << "the backup's action row(s) ${missingActs.join(', ')} are not on the rule".toString()
    }
    if (extraTrigs || extraActsLeft || extraLeft) {
        out.extraRemaining = [triggers: extraTrigs, actions: extraActsLeft, conditions: extraLeft].findAll { k, v -> v }
        problems << "items the backup did not have could not be removed (${failures ? failures.join('; ') : out.extraRemaining})".toString()
    }
    def liveOrder = acts(after).findAll { wantOrder.contains(it) }
    if (liveOrder != wantOrder.findAll { liveOrder.contains(it) }) {
        out.actionOrder = [live: acts(after), backup: wantOrder]
        problems << "the actions run in the order ${acts(after)}, not the backup's ${wantOrder}${orderError ? ' (' + orderError + ')' : ''}".toString()
    }
    if (blockIssues) {
        out.structuralIssues = blockIssues
        problems << "removing the extra rows stopped part way, leaving the rule's blocks unbalanced: ${blockIssues.join('; ')} -- fix them with hub_set_rule(removeAction)".toString()
    }
    if (updateRuleError) {
        out.updateRuleFailed = true
        problems << "the closing updateRule failed (${updateRuleError}), so the rule's subscriptions may not match -- click Update Rule".toString()
    }
    if (problems) {
        out.structureRestored = false
        out.structureError = problems.join("; ")
    }
    return out
}

private List _rmRenderExpression(List toks, Map texts) {
    return (toks ?: []).collect { t ->
        def v = _rmTokenValue(t)
        (v?.isInteger()) ? (texts?.get(v) ?: "#${v}".toString()) : v
    }
}

Map _rmReadRuleState(Integer appId) {
    try {
        def text = hubInternalGet("/app/ruleBuilderJson/${appId}")
        def parsed = text ? new groovy.json.JsonSlurper().parseText(text) : null
        if (!(parsed instanceof Map)) mcpLog("warn", "rm-native", "the state of app ${appId} read as ${text ? 'non-JSON' : 'empty'}")
        return (parsed instanceof Map) ? (parsed as Map) : null
    } catch (Exception e) {
        mcpLog("warn", "rm-native", "reading the state of app ${appId} failed: ${e.message}")
        return null
    }
}

// Re-walk one saved condition slot into the slot the wizard just opened: write each revealed field
// of the new slot from the snapshot's value for the same field of slot `srcIdx` (one field per
// render, in page order), then click hasAll. Returns the new slot index.
private Integer _rmReplayConditionSlot(Integer appId, Map srcSettings, int srcIdx, Closure writeST, Map cache) {
    Integer newIdx = null
    def written = [] as Set
    for (int iter = 0; iter < 30; iter++) {
        def inputs = (_rmFetchConfigJson(appId, "STPage", cache)?.configPage?.sections ?: []).collectMany { it?.input ?: [] }
        if (newIdx == null) {
            def rc = inputs.find { it?.name?.toString()?.startsWith("rCapab_") }
            def m = rc ? (rc.name.toString() =~ /^rCapab_(\d+)$/) : null
            if (!m || !m.matches()) throw new IllegalStateException("no new condition slot opened (STPage shows ${inputs.collect { it?.name }.findAll { it }.join(', ')})")
            newIdx = (m[0] as List)[1] as Integer
        }
        def next = null
        def nextValue = null
        for (inp in inputs) {
            def name = inp?.name?.toString()
            if (!name || written.contains(name) || inp?.type?.toString() == "button") continue
            def nm = name =~ /^(.*?[A-Za-z])(_?)(\d+)$/
            if (!nm.matches() || ((nm[0] as List)[3] as Integer) != newIdx) continue
            def srcKey = "${(nm[0] as List)[1]}${(nm[0] as List)[2]}${srcIdx}".toString()
            def v = srcSettings.get(srcKey)
            if (v == null || v == "" || (v instanceof Collection && v.isEmpty()) || (v instanceof Map && v.isEmpty())) continue
            def want = (v instanceof Map) ? (v as Map).keySet().collect { it?.toString() } : v
            // A field that already holds the saved value (a reused slot) needs no write.
            if (inp.value != null && _rmReplayValueMatches(inp.value, want)) { written << name; continue }
            next = name
            nextValue = want
            break
        }
        if (next != null) {
            def wr = writeST([unUsed: null], next, nextValue, next)
            if (!(wr instanceof Map) || wr.persisted != true) throw new IllegalStateException("the wizard did not accept ${next}=${nextValue} for the restored condition")
            written << next
            continue
        }
        if (inputs.any { it?.name?.toString() == "hasAll" }) {
            _rmClickAppButton(appId, "hasAll", null, "STPage", cache)
            _rmFetchConfigJson(appId, "STPage", cache)
            return newIdx
        }
        throw new IllegalStateException("the restored condition (from slot ${srcIdx}) could not be completed; STPage shows ${inputs.collect { it?.name }.findAll { it }.join(', ')}")
    }
    throw new IllegalStateException("the restored condition (from slot ${srcIdx}) did not complete within the wizard step limit")
}

private boolean _rmReplayValueMatches(Object current, Object want) {
    def norm = { o ->
        if (o instanceof Map) return (o as Map).keySet().collect { it?.toString() }.sort()
        if (o instanceof Collection) return (o as Collection).collect { it?.toString() }.sort()
        return [o?.toString()]
    }
    return norm(current) == norm(want)
}

// Device ids the snapshot's condition settings (for condIds) name that no longer exist on the hub.
List _rmExpressionDeadDevices(Map snapshot, List condIds) {
    def ids = (condIds ?: []).collect { it.toString() } as Set
    if (!ids) return []
    def settings = (snapshot?.configJson?.settings ?: [:]) as Map
    def recs = (snapshot?.statusJson?.appSettings ?: []).findAll { it instanceof Map }.collectEntries { [(it.name?.toString()): it] }
    def devices = [] as LinkedHashSet
    ((settings.keySet().collect { it.toString() }) + recs.keySet()).unique().each { String name ->
        // Condition settings end in _<id> (rDev_3); trigger settings (tDev3) share the digits but not the underscore.
        def m = name =~ /^[A-Za-z]+_(\d+)$/
        if (!m.matches() || !ids.contains((m[0] as List)[1].toString())) return
        def rec = recs.get(name)
        def v = settings.get(name)
        // A picker the status read does not list is known by its {id: label} value.
        if (!(rec ? _isDevicePickerType(rec.type?.toString()) : v instanceof Map)) return
        def list = (rec?.deviceIdsForDeviceList instanceof List && rec.deviceIdsForDeviceList) ? rec.deviceIdsForDeviceList : _devicePickerIds(v)
        (list instanceof List ? list : []).each { devices << it.toString() }
    }
    def cache = [:]
    return devices.findAll { _rmDeviceGone(it, cache) }.toList()
}

// Make the rule's Required Expression match a backup snapshot. Returns [:] when the snapshot carries
// no expression state (an older snapshot, a non-RM app) or neither side has an expression; else
// requiredExpressionRestored true|false, plus requiredExpressionRemoved (the snapshot had none),
// requiredExpressionError, preRestoreExpressionKept (a failed rebuild left the earlier expression),
// requiredExpressionPartial (a failed rebuild left part of the snapshot's expression live) and
// leftoverConditionIds.
Map _rmRestoreRequiredExpression(Integer appId, Map snapshot) {
    def appState = snapshot?.statusJson?.appState
    if (!(appState instanceof List)) return [:]
    def snapState = [:]
    appState.each { if (it instanceof Map && it.name != null) snapState.put(it.name.toString(), it.value) }
    def snapSettings = (snapshot?.configJson?.settings ?: [:]) as Map
    if (!snapState.containsKey("eval") && !snapSettings.containsKey("useST")) return [:]
    def snapTokens = (snapState.eval instanceof Map && (snapState.eval as Map)["0"] instanceof List) ? new ArrayList((snapState.eval as Map)["0"] as List) : []
    boolean snapHasRE = snapSettings.useST?.toString() == "true" && !snapTokens.isEmpty()
    def snapRendered = _rmRenderExpression(snapTokens, _rmConditionTexts(snapState))

    if (!snapHasRE) {
        // Nothing to rebuild; only a live expression the snapshot did not have needs removing.
        boolean liveHasRE
        try { liveHasRE = _rmIsCommittedRETell(_rmCollectPageInputNames(appId, "STPage")) } catch (Exception e) {
            return [requiredExpressionRestored: false, requiredExpressionError: "the rule's Required Expression page could not be read after the restore (${e.message})"]
        }
        if (!liveHasRE) return [:]
        try {
            _rmTokClick(appId, "cancelST", "cancelST")
            _rmClickAppButton(appId, "updateRule")
            boolean gone = !_rmIsCommittedRETell(_rmCollectPageInputNames(appId, "STPage"))
            return gone ? [requiredExpressionRestored: true, requiredExpressionRemoved: true] :
                [requiredExpressionRestored: false, requiredExpressionError: "the snapshot has no Required Expression but removing the live one did not take"]
        } catch (Exception e) {
            return [requiredExpressionRestored: false, requiredExpressionError: "removing the live Required Expression failed (${e.message})"]
        }
    }
    def liveState = _rmReadRuleState(appId)
    if (liveState == null) return [requiredExpressionRestored: false, requiredExpressionError: "the rule's expression state could not be read after the restore"]
    def liveTokens = (liveState.eval instanceof Map && (liveState.eval as Map)["0"] instanceof List) ? (liveState.eval as Map)["0"] as List : []
    // A condition naming a deleted device cannot be rebuilt (the wizard takes the dead id and the
    // condition never renders), so nothing is touched.
    def deadDevices = _rmExpressionDeadDevices(snapshot, _rmTokenConditionIds(snapTokens))
    if (deadDevices) {
        def out = [requiredExpressionRestored: false,
                   requiredExpressionError: "the backup's expression names device(s) ${deadDevices.join(', ')} that no longer exist, so it was not rebuilt; the rule's expression was left as it is".toString()]
        if (!liveTokens.isEmpty()) out.preRestoreExpressionKept = true
        return out
    }
    boolean liveCommitted
    try { liveCommitted = _rmIsCommittedRETell(_rmCollectPageInputNames(appId, "STPage")) } catch (Exception e) {
        return [requiredExpressionRestored: false, requiredExpressionError: "the rule's Required Expression page could not be read after the restore (${e.message})"]
    }
    if (liveCommitted && _rmRenderExpression(liveTokens, _rmConditionTexts(liveState)) == snapRendered) {
        return [requiredExpressionRestored: true]
    }

    def cache = [:]
    def writeST = { Map params, String key, Object value, String label = null ->
        _rmWriteSubPageField(appId, "STPage", "mainPage", "name", 0, params, key, value, cache)
    }
    def hrefParams = [unUsed: null]
    // What the rule is committed to before the next step, so a failure trims back to it; the
    // conditions this rebuild added to the pool come out once that is confirmed.
    List baseTokens = liveCommitted ? new ArrayList(liveTokens) : []
    def poolBefore = _rmConditionTexts(liveState).keySet()
    try {
        int builtCount = 0
        int firstCond = snapTokens.findIndexOf { _rmTokenValue(it)?.isInteger() }
        int oldCount
        if (liveCommitted) {
            oldCount = liveTokens.size()
            _rmTokEnterEditor(appId, cache)
        } else {
            // No committed expression to edit: build the first condition with the new-expression
            // selector, commit it, then edit the rest in.
            oldCount = 0
            def w = writeST(hrefParams, "cond", "a", "cond")
            if (!(w instanceof Map) || w.persisted != true) throw new IllegalStateException("the new-expression selector did not open")
            def first = _rmReplayConditionSlot(appId, snapSettings, _rmTokenValue(snapTokens.get(firstCond)) as Integer, writeST, cache)
            writeST(hrefParams, "hasRule", "button", "hasRule")
            _rmTokClick(appId, "doneST", "doneST", cache)
            baseTokens = [first]
            _rmSubmitSubPageDone(appId, "STPage", "mainPage", "name", hrefParams, cache)
            _rmClickAppButton(appId, "updateRule")
            builtCount = 1
            if (snapTokens.size() > 1) _rmTokEnterEditor(appId, cache)
        }
        if (liveCommitted || snapTokens.size() > 1) {
            // Tokens before the first condition (a leading paren or NOT) go in front of it; the rest
            // append. With an old expression present, everything appends after it.
            def prefix = liveCommitted ? [] : (firstCond > 0 ? snapTokens[0..<firstCond] : [])
            def rest = liveCommitted ? snapTokens : snapTokens.subList(firstCond + 1, snapTokens.size())
            prefix.eachWithIndex { tok, i -> _rmTokInsert(appId, i, _rmTokenValue(tok), writeST, hrefParams, cache) }
            int position = oldCount + builtCount + prefix.size()
            rest.each { tok ->
                def v = _rmTokenValue(tok)
                if (v?.isInteger()) {
                    _rmTokInsert(appId, position, "*", writeST, hrefParams, cache)
                    _rmCacheInvalidate(cache, appId)
                    _rmReplayConditionSlot(appId, snapSettings, v as Integer, writeST, cache)
                } else {
                    _rmTokInsert(appId, position, v, writeST, hrefParams, cache)
                }
                position++
            }
            // The appended expression must read as the snapshot's before the old tokens go.
            def appendedState = _rmReadRuleState(appId)
            def appended = (appendedState?.eval instanceof Map && (appendedState.eval as Map)["0"] instanceof List) ? (appendedState.eval as Map)["0"] as List : null
            if (appended == null || appended.size() < oldCount) throw new IllegalStateException("the expression could not be read back after the rebuild")
            def appendedRendered = _rmRenderExpression(appended.drop(oldCount), _rmConditionTexts(appendedState))
            if (appendedRendered != snapRendered) {
                throw new IllegalStateException("the rebuilt expression reads '${appendedRendered.join(' ')}', not the snapshot's '${snapRendered.join(' ')}'")
            }
            oldCount.times { _rmTokClick(appId, "0", "deleteToken", cache) }
            _rmTokLeaveEditor(appId, cache)
            _rmClickAppButton(appId, "updateRule")
        }
        def after = _rmReadRuleState(appId)
        if (after == null) {
            return [requiredExpressionRestored: false, requiredExpressionError: "the rebuilt expression could not be read back to confirm it"]
        }
        def afterTokens = (after.eval instanceof Map && (after.eval as Map)["0"] instanceof List) ? (after.eval as Map)["0"] as List : []
        boolean committed = _rmIsCommittedRETell(_rmCollectPageInputNames(appId, "STPage"))
        if (committed && _rmRenderExpression(afterTokens, _rmConditionTexts(after ?: [:])) == snapRendered) {
            return [requiredExpressionRestored: true]
        }
        throw new IllegalStateException("the rebuilt expression reads '${_rmRenderExpression(afterTokens, _rmConditionTexts(after ?: [:])).join(' ')}', not the snapshot's '${snapRendered.join(' ')}'")
    } catch (Exception e) {
        mcpLog("error", "rm-native", "restore: rebuilding the Required Expression of app ${appId} failed: ${e.message}")
        // A failure after the rebuild finished (a later click or a verify read) leaves the snapshot's
        // expression in place: finish it rather than undo it.
        try {
            def nowState = _rmReadRuleState(appId)
            def nowTokens = (nowState?.eval instanceof Map && (nowState.eval as Map)["0"] instanceof List) ? (nowState.eval as Map)["0"] as List : null
            if (nowTokens && _rmRenderExpression(nowTokens, _rmConditionTexts(nowState)) == snapRendered) {
                _rmTokLeaveEditor(appId)
                _rmClickAppButton(appId, "updateRule")
                if (_rmIsCommittedRETell(_rmCollectPageInputNames(appId, "STPage"))) return [requiredExpressionRestored: true]
            }
        } catch (Exception finishExc) {
            mcpLog("warn", "rm-native", "restore: finishing the rebuilt expression on app ${appId} failed (${finishExc.message})")
        }
        // Otherwise put back what was committed before the failure: the pre-restore expression, or
        // (no expression before) the first condition. The token editor sets any token order back,
        // including prefix tokens inserted in front of the base and old tokens already deleted.
        boolean trimmed = baseTokens.isEmpty()
        def keep = _rmTokenConditionIds(baseTokens)*.toString()
        def created = null
        def removed = []
        try {
            if (baseTokens.isEmpty()) {
                def names = _rmPageInputNames(_rmFetchConfigJson(appId, "STPage"))
                if (names.contains("cancelCapab")) _rmTokClick(appId, "cancelCapab", "cancelCapab")
                def nowTokens = _rmReadExpressionTokens(appId)
                trimmed = nowTokens != null && nowTokens.isEmpty()
            } else {
                trimmed = _rmTokSetExpression(appId, baseTokens).restored == true
            }
            def after = _rmReadRuleState(appId)
            if (after != null) created = (_rmConditionTexts(after).keySet() - poolBefore).findAll { !keep.contains(it) }.collect { it.isInteger() ? (it as Integer) : it }
            if (trimmed && created) removed = _rmDeleteExpressionConditions(appId, created)
        } catch (Exception cleanupExc) {
            mcpLog("error", "rm-native", "restore: backing out of the failed rebuild on app ${appId} failed (${cleanupExc.message})")
            trimmed = false
        }
        def out = [requiredExpressionRestored: false]
        String state
        if (liveCommitted) {
            out.preRestoreExpressionKept = trimmed
            state = trimmed ? "the pre-restore expression was left in place" : "the live expression may be partly rebuilt -- inspect it with hub_get_app_config"
        } else if (!baseTokens.isEmpty()) {
            out.requiredExpressionPartial = true
            state = trimmed ? "only the snapshot's first condition is committed -- finish the expression with replaceRequiredExpression" :
                "part of the snapshot's expression is committed and the editor may be open -- inspect it with hub_get_app_config"
        } else {
            state = "no expression was committed, so the rule runs without its Required Expression (ungated) -- rebuild it with addRequiredExpression"
        }
        def leftover = (created ?: []).findAll { !(removed*.toString()).contains(it.toString()) }
        if (leftover) out.leftoverConditionIds = leftover
        out.requiredExpressionError = "rebuilding the Required Expression failed (${e.message}); ${state}".toString()
        return out
    }
}

// The token sequence a replace spec builds, in order: [cond: Map, condIdx: Integer, path: String]
// for each condition and [tok: String] for every operator and paren. Validates sub-expression operators.
private List _rmRequiredExpressionTokenPlan(List conditions, String operator, List opsList, String path = "conditions") {
    def plan = []
    conditions.eachWithIndex { c, i ->
        if (i > 0) plan << [tok: (opsList ? opsList[i - 1] : operator)?.toString()]
        def cond = c as Map
        if (cond.subExpression instanceof Map) {
            def sub = cond.subExpression as Map
            def subConds = sub.conditions
            if (!(subConds instanceof List) || subConds.isEmpty()) {
                throw new IllegalArgumentException("${path}[${i}].subExpression.conditions must be a non-empty List")
            }
            def subOp = sub.operator?.toString()?.toUpperCase()
            def subOps = (sub.operators instanceof List) ? (sub.operators as List).collect { it?.toString()?.toUpperCase() } : null
            if (subOp && !(subOp in ["AND", "OR", "XOR"])) {
                throw new IllegalArgumentException("${path}[${i}].subExpression.operator must be AND/OR/XOR (got '${subOp}')")
            }
            if (subOps != null && (subOps.size() != subConds.size() - 1 || subOps.any { !(it in ["AND", "OR", "XOR"]) })) {
                throw new IllegalArgumentException("${path}[${i}].subExpression.operators must hold ${subConds.size() - 1} AND/OR/XOR value(s)")
            }
            if (subConds.size() > 1 && !subOp && !subOps) {
                throw new IllegalArgumentException("${path}[${i}].subExpression with ${subConds.size()} conds requires operator or operators")
            }
            plan << [tok: "("]
            plan.addAll(_rmRequiredExpressionTokenPlan(subConds as List, subOp, subOps, "${path}[${i}].subExpression.conditions"))
            plan << [tok: ")"]
        } else {
            plan << [cond: cond, condIdx: i, path: "${path}[${i}]".toString()]
        }
    }
    return plan
}

// Replace a Required Expression in place without leaving the rule ungated: append the new
// expression, remove the old tokens once it verifies, trim back on a failed build, and roll back on a
// rejected updateRule or health regression. deferFinalize (patches) defers that to the batch end.
private Map _rmReplaceRequiredExpression(Integer appId, Map exprSpec, Map backup = null, boolean deferFinalize = false) {
    // Validate the whole spec and derive the token plan before any click.
    def validated = _rmValidateRequiredExpressionSpec(exprSpec, "replaceRequiredExpression")
    def plan = _rmRequiredExpressionTokenPlan(exprSpec.conditions as List, validated.operator as String, validated.opsList as List)
    plan.each { item ->
        if (item.cond != null) {
            def cap = (item.cond as Map).capability?.toString()?.trim()
            if (!cap) throw new IllegalArgumentException("replaceRequiredExpression: every condition needs a capability")
            _rmCheckCondSpec(item.cond as Map, "condition", "replaceRequiredExpression")
        }
    }

    def preNames
    try {
        preNames = _rmCollectPageInputNames(appId, "STPage")
    } catch (Exception preExc) {
        return [
            success: false,
            originalPreserved: true,
            error: "replaceRequiredExpression: STPage could not be read for app ${appId} (${preExc.message ?: preExc}); retry, or check the rule with hub_get_app_config(appId=${appId})."
        ]
    }
    if (!_rmIsCommittedRETell(preNames)) {
        return [
            success: false,
            requiredExpressionMissing: true,
            error: "replaceRequiredExpression: rule ${appId} has no Required Expression; create one with addRequiredExpression."
        ]
    }
    def origTokens = _rmReadExpressionTokens(appId)
    if (!origTokens) {
        return [
            success: false,
            originalPreserved: true,
            error: (origTokens == null ?
                "replaceRequiredExpression: could not read the current Required Expression of app ${appId} (ruleBuilderJson). Nothing was changed -- retry, or inspect via hub_get_app_config(appId=${appId})." :
                "replaceRequiredExpression: app ${appId} shows a committed Required Expression but its token list reads empty. Nothing was changed -- inspect it via hub_get_app_config(appId=${appId}).").toString()
        ]
    }
    // An unreadable baseline counts every later issue as new, so the rollback errs toward the original.
    def baselineHealth = null
    try { baselineHealth = _rmCheckRuleHealth(appId) } catch (Exception baseExc) {
        mcpLog("warn", "rm-native", "replaceRequiredExpression: baseline health of app ${appId} could not be read (${baseExc.message})")
    }

    def rmCache = [:]
    def applied = []
    def skipped = []
    def conditionIndices = []
    def hrefParams = [unUsed: null]
    def writeST = { Map params, String fieldKey, Object fieldValue, String label = null ->
        def wr = _rmWriteSubPageField(appId, "STPage", "mainPage", "name", 0, params, fieldKey, fieldValue, rmCache)
        if (wr?.persisted) {
            applied << (label ?: fieldKey)
        } else if (wr?.verifyFetchFailed) {
            skipped << [key: fieldKey, label: (label ?: fieldKey), reason: "verification_fetch_failed", value: fieldValue, verifyError: wr?.verifyError]
        } else {
            skipped << [key: fieldKey, label: (label ?: fieldKey), reason: "silent_rejection", value: fieldValue, schemaUnchanged: true, available: wr?.afterKeys]
        }
        return wr
    }
    def cancelledByWalker = false
    def cancelInFlightCondition = {
        cancelledByWalker = true
        try { _rmTokClick(appId, "cancelCapab", "cancelCapab", rmCache) }
        catch (Exception cancelExc) {
            mcpLog("warn", "rm-native", "replaceRequiredExpression: cancelCapab failed on app ${appId} (${cancelExc.message})")
        }
    }

    // Undo a failed edit: close any open condition or insert, trim to the original tokens, commit, then
    // remove the conditions this edit created (only once the original is confirmed back).
    def createdConditions = { conditionIndices.findAll { !(_rmTokenConditionIds(origTokens).contains(it)) } }
    def abortEdit = { String why ->
        boolean preserved = false
        def removed = []
        try {
            def names = _rmPageInputNames(_rmFetchConfigJson(appId, "STPage", rmCache))
            if (names.contains("cancelCapab")) names = _rmPageInputNames(_rmTokClick(appId, "cancelCapab", "cancelCapab", rmCache))
            if (names.contains("cancelInsert")) _rmTokClick(appId, "cancelInsert", "cancelInsert", rmCache)
            boolean trimmed = _rmTokTrimTo(appId, origTokens, rmCache)
            _rmTokLeaveEditor(appId, rmCache)
            _rmClickAppButton(appId, "updateRule")
            def live = _rmReadExpressionTokens(appId)
            preserved = trimmed && _rmTokensEqual(live, origTokens) && _rmIsCommittedRETell(_rmCollectPageInputNames(appId, "STPage"))
            if (preserved) removed = _rmDeleteExpressionConditions(appId, createdConditions())
        } catch (Exception cleanupExc) {
            mcpLog("error", "rm-native", "replaceRequiredExpression: backing out of the failed edit on app ${appId} failed (${cleanupExc.message})")
        }
        def out = [success: false, requiredExpressionReplaced: false, originalPreserved: preserved,
                   conditionIndices: conditionIndices, settingsApplied: applied, settingsSkipped: skipped]
        if (removed) out.removedConditionIds = removed
        def leftover = createdConditions().findAll { !(removed*.toString()).contains(it.toString()) }
        if (leftover) out.leftoverConditionIds = leftover
        if (preserved) {
            out.error = "replaceRequiredExpression: ${why} The original Required Expression was left in place (nothing was replaced)."
        } else {
            out.partial = true
            out.error = "replaceRequiredExpression: ${why} Backing out could not be confirmed; check the original expression with hub_get_app_config(appId=${appId}) and rebuild it with addRequiredExpression if it is gone."
        }
        return out
    }

    try {
        _rmTokEnterEditor(appId, rmCache)
    } catch (Exception enterExc) {
        boolean intact = false
        try {
            _rmTokLeaveEditor(appId, rmCache)
            _rmClickAppButton(appId, "updateRule")
            intact = _rmTokensEqual(_rmReadExpressionTokens(appId), origTokens) && _rmIsCommittedRETell(_rmCollectPageInputNames(appId, "STPage"))
        } catch (Exception leaveExc) {
            mcpLog("warn", "rm-native", "replaceRequiredExpression: closing the editor on app ${appId} after it failed to open failed (${leaveExc.message})")
        }
        return [success: false, requiredExpressionReplaced: false, originalPreserved: intact, partial: !intact,
                error: ("replaceRequiredExpression: could not open the Required Expression editor on app ${appId} (${enterExc.message ?: enterExc}). " +
                    (intact ? "Nothing was changed." : "The original expression could not be confirmed afterwards -- inspect it with hub_get_app_config(appId=${appId}).")).toString()]
    }

    def newTokens = []
    int position = origTokens.size()
    try {
        plan.each { item ->
            if (item.tok != null) {
                _rmTokInsert(appId, position, item.tok as String, writeST, hrefParams, rmCache)
                newTokens << item.tok
                position++
                return
            }
            _rmTokInsert(appId, position, "*", writeST, hrefParams, rmCache)
            // Read the opened condition form from a fresh render, not the write's echo.
            _rmCacheInvalidate(rmCache, appId)
            cancelledByWalker = false
            try {
                def cIdx = _rmWalkNewConditionSlot(appId, item.cond as Map, item.condIdx as Integer, [
                    writeST: writeST, cancelInFlightCondition: cancelInFlightCondition, hrefParams: hrefParams,
                    applied: applied, skipped: skipped, cache: rmCache, conditionIndices: conditionIndices,
                    selector: "a new-condition token", op: "replaceRequiredExpression"
                ])
                _rmFetchConfigJson(appId, "STPage", null)
                newTokens << cIdx
                position++
            } catch (Exception condExc) {
                if (!cancelledByWalker) cancelInFlightCondition()
                // A nested condition's own index says nothing about where it sits; name its path.
                if (item.path?.toString()?.contains("subExpression")) {
                    throw new IllegalStateException("at ${item.path}: ${condExc.message ?: condExc}".toString())
                }
                throw condExc
            }
        }
        // Every appended token must be live before the old ones go.
        def appended = _rmReadExpressionTokens(appId)
        if (!_rmTokensEqual(appended, origTokens + newTokens)) {
            throw new IllegalStateException("the expression reads ${appended} after appending (expected ${origTokens + newTokens})")
        }
    } catch (Exception buildExc) {
        return abortEdit("the new expression could not be built for app ${appId} (${buildExc.message ?: buildExc}).")
    }

    // The new expression is complete after the original: remove the original tokens.
    try {
        origTokens.size().times { _rmTokClick(appId, "0", "deleteToken", rmCache) }
        def live = _rmReadExpressionTokens(appId)
        if (!_rmTokensEqual(live, newTokens)) {
            throw new IllegalStateException("the expression reads ${live} after removing the original tokens (expected ${newTokens})")
        }
        _rmTokLeaveEditor(appId, rmCache)
        if (!_rmIsCommittedRETell(_rmCollectPageInputNames(appId, "STPage"))) {
            throw new IllegalStateException("STPage did not return to the committed-expression view")
        }
    } catch (Exception swapExc) {
        def restoreOut = _rmRevertRequiredExpression(appId, origTokens,
            "replaceRequiredExpression: switching app ${appId} to the new expression failed (${swapExc.message ?: swapExc}).",
            [success: false, requiredExpressionReplaced: false,
             conditionIndices: conditionIndices, settingsApplied: applied, settingsSkipped: skipped])
        def removed = []
        if (restoreOut.requiredExpressionRestored == true) {
            try { removed = _rmDeleteExpressionConditions(appId, createdConditions()) } catch (Exception delExc) {
                mcpLog("warn", "rm-native", "replaceRequiredExpression: removing the created conditions on app ${appId} failed (${delExc.message})")
            }
        }
        if (removed) restoreOut.removedConditionIds = removed
        def leftover = createdConditions().findAll { !(removed*.toString()).contains(it.toString()) }
        if (leftover) restoreOut.leftoverConditionIds = leftover
        return restoreOut
    }
    // Parity with the add path: the next addAction clears the stale predCapabs first. The new
    // expression is already committed, so a failure here must not abort the replace.
    try { _rmMarkPredClearPending(appId) } catch (Exception markExc) {
        mcpLog("warn", "rm-native", "replaceRequiredExpression: marking the predCapabs clear on app ${appId} failed (${markExc.message})")
    }

    def revert = { String errMsg, Map extraFields = [:] ->
        def safe = [success: false, requiredExpressionReplaced: false, error: errMsg]
        return _rmRevertRequiredExpression(appId, origTokens, errMsg, (extraFields ?: [:]) + safe)
    }
    def inner = [success: true, partial: !skipped.isEmpty() && skipped.any { it instanceof Map && !(it.reason in _rmInformationalSkippedReasons()) },
                 conditionIndices: conditionIndices, settingsApplied: applied, settingsSkipped: skipped, repairHints: []]
    return _rmFinalizeRequiredExpressionWrite(appId, inner, backup, "replaced", true, revert,
        [baselineHealth: baselineHealth, deferUpdateRule: deferFinalize,
         deferredRestoreExtra: [origTokens: origTokens]])
}

// Remove the internal __reqT0 clock from a spec handed back in an in_progress pause; the resume call
// would otherwise inherit the old elapsed time.
private _stripInternalClock(rem) {
    return (rem instanceof Map) ? ((Map) rem).findAll { k, v -> k?.toString() != "__reqT0" } : rem
}

// Fail-closed bulk contract: a failed or partial item stops every later mutation and finalisation.
private boolean _rmBulkItemBlocks(r) {
    return r instanceof Map && (r.success == false || r.partial == true)
}

private Map _rmBulkNotAttempted(String stoppedAfter) {
    return [success: false, notAttempted: true, error: "not attempted: bulk stopped after ${stoppedAfter} failed or was partial".toString()]
}

// Top-level error for a stopped batch, naming the stopping item and why. The MRTR recentWrites record keeps
// only success, ids and error from a terminal result, so this is all a client that never saw the response gets.
private String _rmBulkStopError(String stoppedAfter, stopItem) {
    def item = (stopItem instanceof Map) ? (Map) stopItem : [:]
    String why = item.success == false ? "failed" : "reported partial"
    String itemError = item.error?.toString()?.trim()
    // A partial item usually has no error of its own; recentWrites keeps only this string, so carry its most specific reason.
    if (!itemError) itemError = item.updateRuleError?.toString()?.trim()
    if (!itemError && item.settingsSkipped instanceof List) {
        def informational = _rmInformationalSkippedReasons()
        // The isCondTrig.<N> finalize toggle is a cosmetic best-effort write, never why an item stopped.
        def skip = (item.settingsSkipped as List).find { it instanceof Map && it.key && it.reason &&
            !(it.reason in informational) && !it.key.toString().startsWith("isCondTrig.") }
        // Not every skip means the value was lost (a force-written comparator is written but unverified), so name the reason.
        if (skip != null) itemError = "field '${skip.key}' reported ${skip.reason}".toString()
    }
    if (!itemError && item.repairHints instanceof List) {
        itemError = (item.repairHints as List).find { it != null && it.toString().trim() }?.toString()?.trim()
    }
    while (itemError?.endsWith(".")) itemError = itemError.substring(0, itemError.length() - 1)
    String detail = itemError ? ": ${itemError}" : ""
    return "Stopped after ${stoppedAfter} ${why}${detail}. Later items were not attempted and finalisation was not fired.".toString()
}

// Edit and replace stop envelope. Skipping finalisation is not a rollback: items before the stop stay written, and
// actions self-bake, so they can already affect an active rule. The pre-operation backup is the rollback handle.
private Map _rmBulkStoppedResult(Integer appId, Map backup, String stoppedAfter, stopItem, Map extra) {
    // partial when anything landed: an applied or partial item at any depth (a patch op's inner
    // `results`), the stop item itself when it was partial, or a replace that already cleared rows.
    def landed
    landed = { Object node ->
        if (node instanceof Map) {
            Map m = node as Map
            if ((m.success == true || m.partial == true) && m.rolledBack != true) return true
            return m.values().any { v -> (v instanceof List || v instanceof Map) && landed(v) }
        }
        if (node instanceof List) return (node as List).any { v -> (v instanceof List || v instanceof Map) && landed(v) }
        return false
    }
    boolean earlierApplied = (extra ?: [:]).any { k, v ->
        (k == "removedIndices" && v instanceof List && !(v as List).isEmpty()) || ((v instanceof List || v instanceof Map) && k != "health" && landed(v))
    } || (stopItem instanceof Map && landed(stopItem))
    def out = [
        success: false,
        partial: earlierApplied,
        appId: appId,
        backup: backup,
        bulkStoppedAfter: stoppedAfter,
        finalisationNotAttempted: true,
        error: _rmBulkStopError(stoppedAfter, stopItem),
        health: (extra?.containsKey("health") ? extra.health : _rmCheckRuleHealth(appId)),
        repairHints: ["Stopped fail-closed after ${stoppedAfter}: later items were not attempted and the trailing updateRule was not fired. Items before it remain written and can already affect the rule (actions self-bake). Inspect with hub_get_app_config(appId=${appId}); repair the failed item and add the notAttempted items, or roll back (${_rmRestoreClause(backup)}). Fire hub_set_rule(button='updateRule') only once the rule is complete.".toString()],
        note: "Stopped after ${stoppedAfter} failed or was partial; finalisation not attempted.".toString()
    ]
    // An item that left an editor open is reported the way the single-op envelope reports it:
    // retrying the failed item first would reopen the stale row pre-filled.
    // The stop item's own outcome flags (an editor left open, a row written but not baked) are the
    // envelope's: the single-op envelope reports them the same way.
    def flagged
    flagged = { Object node, String flag ->
        if (node instanceof Map) {
            if ((node as Map).get(flag) == true) return true
            return (node as Map).values().any { v -> (v instanceof List || v instanceof Map) && flagged(v, flag) }
        }
        if (node instanceof List) return (node as List).any { v -> (v instanceof List || v instanceof Map) && flagged(v, flag) }
        return false
    }
    boolean stuck = flagged(stopItem, "wizardStuck")
    if (flagged(stopItem, "fieldsWritten")) out.fieldsWritten = true
    if (stuck) {
        out.wizardStuck = true
        def recoveryHint = (stopItem instanceof Map && stopItem.op == "modifyTrigger") ?
            _rmModifyTriggerWizardRecoveryHint(appId) :
            "An item left an editor open (wizardStuck). Close it before any retry: hub_set_rule(button='actionCancel', pageName='doActPage', confirm=true) for a new action's editor (button='actionDone' for one modifyAction opened on a committed action), or button='cancelCapab' with the wizard's pageName for a condition or trigger. Verify with hub_get_app_config(includeSettings=true), then retry the failed item.".toString()
        out.repairHints = ([recoveryHint] + (out.repairHints as List))
    }
    out.putAll(extra ?: [:])
    return out
}

// Time-budget pause envelope for the bulk addTriggers/addActions path -- mirrors the in_progress shape the
// walkStep-drive step loop and the patches op loop return. A pause is only reachable while every committed item
// is clean (a failed or partial item stops the batch first), so this hands back a clean prefix plus the
// unprocessed items (minus the internal __reqT0 clock) for the caller to re-issue. The itemsPartial roll-up is a
// defensive guard should that ordering ever change. Health is intentionally NOT gated: the trailing updateRule is
// deferred to the resume call, so subscriptions are legitimately not-yet-live at a pause.
private Map _bulkPauseResult(Integer appId, Map backup, List triggerResults, List actionResults,
                                  List addTriggersRemaining, List addActionsRemaining) {
    def trigOk = triggerResults.count { it?.success != false }
    def actOk = actionResults.count { it?.success != false }
    def itemsPartial = (trigOk != triggerResults.size()) || (actOk != actionResults.size()) ||
        triggerResults.any { it instanceof Map && it.partial == true } ||
        actionResults.any { it instanceof Map && it.partial == true }
    def repairHints = []
    if (itemsPartial) {
        repairHints << "One or more committed bulk items failed or reported partial before the time budget paused the batch. Drill into triggers[]/actions[] for per-item settingsSkipped + repairHints; the remaining (unprocessed) items are in addTriggersRemaining/addActionsRemaining."
    }
    return [
        success: trigOk == triggerResults.size() && actOk == actionResults.size(),
        partial: itemsPartial,
        status: "in_progress",
        appId: appId,
        backup: backup,
        triggers: triggerResults,
        actions: actionResults,
        triggersCommitted: trigOk,
        actionsCommitted: actOk,
        addTriggersRemaining: addTriggersRemaining.collect { _stripInternalClock(it) },
        addActionsRemaining: addActionsRemaining.collect { _stripInternalClock(it) },
        repairHints: repairHints,
        resume: [
            note: "Time budget reached; committed items are written at the settings level (the trailing updateRule is deferred to the resume call). Re-issue the edit (hub_set_rule or hub_set_native_app) with addActions/addTriggers = the remaining items to continue."
        ]
    ]
}

// Time-budget pause envelope for the patches path. Fires at the patch-op boundary AND (for a
// bulk addTriggers/addActions sub-op) mid-op, so a single patch op carrying a large inner list
// can no longer exhaust the time budget un-paused. As with the bulk pause, a failed or partial op
// or inner item stops the batch before any checkpoint, so a pause only ever hands back work after a
// clean prefix. A clean mid-op checkpoint still marks its unfinished patch partial:true;
// this is resumable work, not a failed item. patchesRemaining is the
// un-processed work the caller re-issues -- for a mid-op pause the caller prepends the current op
// rewritten to only its un-processed inner items.
private Map _patchesPauseResult(Integer appId, Map backup, List patchResults, List patchesRemaining) {
    def opPartial = patchResults.any { it instanceof Map && (it.success == false || it.partial == true) }
    return [
        success: patchResults.every { it?.success != false },
        partial: opPartial,
        status: "in_progress",
        appId: appId,
        backup: backup,
        patchResults: patchResults,
        patchesRemaining: patchesRemaining,
        resume: [
            note: "Time budget reached; completed patch ops are committed at the settings level but NOT yet baked into the running rule. Re-issue the edit (hub_set_rule or hub_set_native_app) with patches = patchesRemaining to continue; the rule finalize/updateRule runs when the remaining patches complete."
        ]
    ]
}

// Return a terminal structured refusal only for edit shapes whose rejection is
// fully determined by argument maps. This method must remain free of hub reads,
// writes, state mutation, backup creation, and wizard calls.
private Map _rmRoundZeroNativeEditRefusal(Map args) {
    if (!(args instanceof Map) || args.appId == null || args.operation != null) return null
    // EDIT dispatch accepts numeric-string ids via normalizeRuleId, so the
    // pre-reservation seam must too -- a "123" appId otherwise skips it and only
    // refuses after claiming the write slot. Garbage ids still fall through: the
    // full path owns their richer error.
    Integer refusalAppId
    try {
        refusalAppId = normalizeRuleId(args.appId)
    } catch (IllegalArgumentException ignored) {
        return null
    }

    def candidates = []
    if (args.addTrigger instanceof Map) candidates << "addTrigger"
    if (args.addAction instanceof Map) candidates << "addAction"
    // Keep the seam narrower than EDIT dispatch. In particular, never let an
    // argument refusal outrank the existing multi-operation-family error.
    if (candidates.size() != 1) return null
    String candidate = candidates.first()
    def competingEditKeys = _setRuleOperations() -
        ["create", "buttonRule", "discover", candidate]
    if (competingEditKeys.any { args.containsKey(it) }) return null

    try {
        switch (candidate) {
            case "addTrigger":
                def triggerSpec = args.addTrigger as Map
                _rmValidateRoundZeroTriggerSpec(triggerSpec)
                if (triggerSpec.deviceIds == null &&
                        !(triggerSpec.condition instanceof Map)) {
                    _rmValidateRoundZeroPeriodicSpec(triggerSpec)
                }
                break
            case "addAction":
                _rmValidateRoundZeroActionSpec(args.addAction as Map)
                break
        }
        return null
    } catch (IllegalArgumentException refusal) {
        return [
            success: false,
            appId: refusalAppId,
            error: refusal.message,
            wizardStuck: false,
            restoreHint: _rmPreflightRestoreHint()
        ]
    }
}

// An object value is only meaningful on a device input, where it is the {id: label} map
// hub_get_app_config returns (_rmBuildSettingsBody sends its ids). Anywhere else it would reach
// the hub as Groovy's map text and 500, so refuse it before any backup or write. One read-only GET.
private void _rmRejectNonDeviceMapSettings(Integer appId, String pageName, Map settingsMap) {
    def schema = _rmCollectInputSchema(_rmFetchConfigJson(appId, pageName)?.configPage)
    def bad = settingsMap.findAll { k, v ->
        v instanceof Map && schema.containsKey(k.toString()) && !_isDevicePickerType(schema.get(k.toString())?.type)
    }.collect { k, v -> "${k} (${schema.get(k.toString())?.type})".toString() }
    if (bad) {
        throw new IllegalArgumentException("settings ${bad} were given an object, which only device inputs accept (as the {id: label} map hub_get_app_config returns). Pass a scalar for text/number/bool/enum inputs and a List of options for a multi-select enum.")
    }
}

// _applyNativeAppEdit: the edit engine for an existing app, behind hub_set_rule's edit arm and
// hub_set_native_app. Raw modes: settings (multi-device fields enforced, multiple flags verified with
// one retry, then updateRule) or button (/installedapp/btn for editor buttons). pageName targets a
// sub-page (e.g. ruleActions, triggerCondition, ifthenelseActions): the schema is introspected from
// that page so settings named on it get the right marshaling.
def _applyNativeAppEdit(args) {
    // The body runs as a closure so its call sites live in the closure's own class: the app class is
    // near the JVM 65,535 constant-pool limit. Behaviour is unchanged.
    def _impl = {
    // Discover returns the static catalog with no hub call, so it skips confirm and the backup;
    // executeTool lets it through even with writes disabled (_isNativeAppSchemaOnlyCall).
    if (args?.addTrigger instanceof Map && _rmIsDiscoverSpec(args.addTrigger)) {
        return _rmAddTrigger(0, args.addTrigger as Map)
    }
    if (args?.addAction instanceof Map && _rmIsDiscoverSpec(args.addAction)) {
        return _rmAddAction(0, _rmWithClock(args.addAction as Map, args?.__reqT0 as Long))
    }
    // guide:true returns the hub_set_rule reference inline with no hub call; hub_set_rule has no cursor,
    // so the whole section is returned.
    if (args?.guide == true) {
        return _rmWholeGuideSection('set_rule_reference')
    }
    requireDestructiveConfirm(args?.confirm as Boolean)
    if (args?.appId == null) throw new IllegalArgumentException("appId is required")
    def appId = normalizeRuleId(args.appId)
    _requireUnprotectedAppMutation(appId, "edit")
    def settingsMap = args?.settings instanceof Map ? args.settings : null
    // Raw settings skip the structured device check, so validate any list value under a device-list
    // field name (RM stores an unknown id silently).
    def button = args?.button?.toString()?.trim() ?: null
    def addTriggerSpec = args?.addTrigger instanceof Map ? args.addTrigger : null
    def addActionSpec = args?.addAction instanceof Map ? args.addAction : null
    def addActionsList = args?.addActions instanceof List ? (args.addActions as List) : null
    def addTriggersList = args?.addTriggers instanceof List ? (args.addTriggers as List) : null
    def addRequiredExpressionSpec = args?.addRequiredExpression instanceof Map ? args.addRequiredExpression : null
    def replaceRequiredExpressionSpec = args?.replaceRequiredExpression instanceof Map ? args.replaceRequiredExpression : null
    def addLocalVariableSpec = args?.addLocalVariable instanceof Map ? args.addLocalVariable : null
    def removeLocalVariableSpec = args?.removeLocalVariable instanceof Map ? args.removeLocalVariable : null
    def patchesList = args?.patches instanceof List ? (args.patches as List) : null
    def removeActionSpec = args?.removeAction instanceof Map ? args.removeAction : null
    def clearActionsFlag = args?.clearActions == true
    def replaceActionsList = args?.replaceActions instanceof List ? (args.replaceActions as List) : null
    // replaceActions:[] is clearActions, so it reports the clear, not an add half that never existed.
    if (replaceActionsList != null && replaceActionsList.isEmpty()) {
        clearActionsFlag = true
        replaceActionsList = null
    }
    def moveActionSpec = args?.moveAction instanceof Map ? args.moveAction : null
    def walkStepSpec = args?.walkStep instanceof Map ? args.walkStep : null
    def removeTriggerSpec = args?.removeTrigger instanceof Map ? args.removeTrigger : null
    def modifyTriggerSpec = args?.modifyTrigger instanceof Map ? args.modifyTrigger : null
    def modifyActionSpec = args?.modifyAction instanceof Map ? args.modifyAction : null

    // An edit carries one operation, except the addTriggers+addActions pair; refuse any other mix before
    // the backup. Otherwise the first branch drops the rest, or the action-removal family all runs in an
    // order the caller didn't choose.
    def triggerOpNames = []
    if (addTriggerSpec) triggerOpNames << "addTrigger"
    if (addTriggersList) triggerOpNames << "addTriggers"
    def actionOpNames = []
    if (addActionSpec) actionOpNames << "addAction"
    if (addActionsList) actionOpNames << "addActions"
    def rawOpNames = []
    if (settingsMap) rawOpNames << "settings"
    if (button) rawOpNames << "button"
    if (rawOpNames) {
        if (args?.pageName != null) rawOpNames << "pageName"
        if (args?.stateAttribute != null) rawOpNames << "stateAttribute"
    }
    def editOpGroups = [
        triggerOpNames,
        actionOpNames,
        addRequiredExpressionSpec ? ["addRequiredExpression"] : [],
        replaceRequiredExpressionSpec ? ["replaceRequiredExpression"] : [],
        removeActionSpec ? ["removeAction"] : [],
        clearActionsFlag ? ["clearActions"] : [],
        // replaceActions:[] is already clearActions, so the null test alone prevents a double count; an
        // explicit clearActions with a non-empty replaceActions stays two operations and is refused.
        (replaceActionsList != null) ? ["replaceActions"] : [],
        moveActionSpec ? ["moveAction"] : [],
        removeTriggerSpec ? ["removeTrigger"] : [],
        modifyTriggerSpec ? ["modifyTrigger"] : [],
        modifyActionSpec ? ["modifyAction"] : [],
        addLocalVariableSpec ? ["addLocalVariable"] : [],
        removeLocalVariableSpec ? ["removeLocalVariable"] : [],
        patchesList ? ["patches"] : [],
        walkStepSpec ? ["walkStep"] : [],
        rawOpNames
    ].findAll { !it.isEmpty() }
    boolean allowedBulkPair = (editOpGroups.size() == 2 &&
        triggerOpNames == ["addTriggers"] && actionOpNames == ["addActions"])
    // The singular+plural pair of one family collapses into a single group, so the
    // group count alone would miss addTrigger+addTriggers (and addAction+addActions).
    if ((editOpGroups.size() >= 2 && !allowedBulkPair) || triggerOpNames.size() > 1 || actionOpNames.size() > 1) {
        def opNames = editOpGroups.collectMany { it }
        throw new IllegalArgumentException("hub_set_rule / hub_set_native_app received multiple operations in one call (${opNames.join(', ')}). Multi-op calls are refused: depending on the combination the extras would either be silently dropped or run in a fixed internal order you did not choose. Use patches:[...] for ordered edits (earlier writes remain if a later edit fails), or issue one call per operation. See hub_get_tool_guide(section='set_rule_reference').")
    }

    if (settingsMap) {
        def devKeyPattern = ~/^([tr]Dev[_-]?\d+|switch[A-Z]\w*|onOffSwitch\.\d+|lockLockUnlock\.\d+|shadeOpenClose\.\d+|fanRL\.\d+|tDev-\d+|deviceList|dimmerLevel\.\d+|ButtontDev_?\d+|pushButton\d+)$/
        settingsMap.each { k, v ->
            if (v instanceof List && k?.toString()?.matches(devKeyPattern)) {
                _rmValidateDeviceIdsExist("settings.${k}", v)
            } else if (v instanceof Map && k?.toString()?.matches(devKeyPattern)) {
                _rmValidateDeviceIdsExist("settings.${k}", v.keySet().collect { it?.toString() })
            }
        }
    }
    if (!settingsMap && !button && !addTriggerSpec && !addActionSpec && !addActionsList && !addTriggersList
            && !addRequiredExpressionSpec && !replaceRequiredExpressionSpec && !addLocalVariableSpec && !removeLocalVariableSpec && !patchesList && !removeActionSpec && !clearActionsFlag && replaceActionsList == null && !moveActionSpec && !walkStepSpec && !removeTriggerSpec && !modifyTriggerSpec && !modifyActionSpec) {
        throw new IllegalArgumentException("Editing an app requires one of: 'settings' (Map) or 'button' (String) for any classic app; or, for Rule Machine rules via hub_set_rule, a structured shortcut -- 'addTrigger' (Map), 'addTriggers' (List), 'addAction' (Map), 'addActions' (List), 'addRequiredExpression' (Map), 'replaceRequiredExpression' (Map), 'addLocalVariable' (Map), 'removeLocalVariable' ({name}), 'patches' (List of sub-specs), 'removeAction' ({index:N}), 'clearActions' (true), 'replaceActions' (List), 'moveAction' ({index:N, direction:up|down}), 'removeTrigger' ({index:N}), 'modifyTrigger' ({index:N, mods:{state:...}}), 'modifyAction' ({index:N, mods:{ruleIds:[...]}}), or 'walkStep' ({page, operation, write?, click?, navigate?, validateEnum?}) -- none provided.")
    }

    // An addAction expression with a condition this tool does not model is refused before the backup,
    // from the requested name, with the not-touched envelope (null backup). Patches and createRule are
    // caught inside _rmAddAction.
    Map modifyActionPlan = null
    try {
        if (addActionSpec) _rmRejectUnwalkableExpressionConditions(addActionSpec)
        addActionsList?.each { if (it instanceof Map) _rmRejectUnwalkableExpressionConditions(it as Map) }
        if (patchesList != null) _rmRejectMultiOpPatchItems(patchesList)
        // For the top-level shortcuts, every check decidable from the arguments (and read-only
        // lookups) runs before the backup, so a refused call never takes a snapshot it then tells the
        // caller not to restore; patches items are checked as each op runs.
        def preActs = (addActionSpec ? [addActionSpec] : []) + (addActionsList ?: []) + (replaceActionsList ?: [])
        if (preActs) _rmPrevalidateActionSpecList(preActs, addActionSpec ? "addAction" : (addActionsList ? "addActions" : "replaceActions"),
            _rmSpecListTargetsRule(preActs) ? _rmValidRuleIds() : null, appId)
        def preTrigs = (addTriggerSpec ? [addTriggerSpec] : []) + (addTriggersList ?: [])
        if (preTrigs) _rmPrevalidateTriggerSpecList(preTrigs, addTriggerSpec ? "addTrigger" : "addTriggers")
        if (addRequiredExpressionSpec) _rmPrevalidateConditionList(addRequiredExpressionSpec, "addRequiredExpression")
        if (replaceRequiredExpressionSpec) _rmPrevalidateConditionList(replaceRequiredExpressionSpec, "replaceRequiredExpression")
        if (modifyActionSpec) {
            if (modifyActionSpec.index == null) throw new IllegalArgumentException("modifyAction.index is required. RM is not touched.")
            if (!(modifyActionSpec.mods instanceof Map)) throw new IllegalArgumentException("modifyAction.mods is required: the fields to change, e.g. {ruleIds: [123]}. RM is not touched.")
            modifyActionPlan = _rmPlanModifyAction(appId, _rmPatchOpIndex("modifyAction", modifyActionSpec.index), modifyActionSpec.mods as Map)
        }
        // A disabled app renders no configuration page, and every edit below drives that page: removeAction
        // would burn a backup and say retry is safe; walkStep and settings would report success having changed
        // nothing. One read-only GET.
        _rmRejectDisabledAppEdit(appId, _rmEditOpLabel(args))
        if (settingsMap?.values()?.any { it instanceof Map }) {
            _rmRejectNonDeviceMapSettings(appId, args?.pageName?.toString()?.trim() ?: null, settingsMap)
        }
    } catch (Exception preflightExc) {
        // Nothing is written before the backup, so a failed read or a stuck editor is a refusal too.
        return _rmBuildUpdateErrorResponse(appId, preflightExc.message ?: preflightExc.toString(), null, "doActPage", preflightExc)
    }
    // A standalone replace with nothing to replace is decidable from the committed page.
    if (replaceRequiredExpressionSpec) {
        def stNames = null
        try { stNames = _rmCollectPageInputNames(appId, "STPage") } catch (Exception ignored) { }
        if (stNames != null && !_rmIsCommittedRETell(stNames)) {
            return _rmBuildUpdateErrorResponse(appId, "replaceRequiredExpression: no committed Required Expression to replace on app ${appId}. Use addRequiredExpression to create one. RM is not touched.".toString(), null) +
                [requiredExpressionMissing: true]
        }
    }

    // Ensure a recent baseline before WRITING -- the restore channel if anything downstream goes wrong. The
    // one carve-out is a decidable-from-name pre-flight reject (above): it writes nothing, so it
    // needs no snapshot and has already returned its refusal envelope before this point.
    def backupReason = button ? "pre-button-${button}" :
        (addTriggerSpec ? "pre-addTrigger" :
        (addActionSpec ? "pre-addAction" :
        (addActionsList ? "pre-addActions-bulk" :
        (addTriggersList ? "pre-addTriggers-bulk" :
        (addRequiredExpressionSpec ? "pre-addRequiredExpression" :
        (replaceRequiredExpressionSpec ? "pre-replaceRequiredExpression" :
        (addLocalVariableSpec ? "pre-addLocalVariable" :
        (removeLocalVariableSpec ? "pre-removeLocalVariable" :
        (removeActionSpec ? "pre-removeAction" :
        (clearActionsFlag ? "pre-clearActions" :
        (replaceActionsList != null ? "pre-replaceActions" :
        (moveActionSpec ? "pre-moveAction" :
        (removeTriggerSpec ? "pre-removeTrigger" :
        (modifyTriggerSpec ? "pre-modifyTrigger" :
        (modifyActionSpec ? "pre-modifyAction" :
        (walkStepSpec ? "pre-walkStep" : "pre-update"))))))))))))))))
    // A walkStep on an app that is not an RM rule reads the page it is on (see _rmBackupBeforeEdit);
    // the backup decision's config read records whether the app is an RM rule.
    String wsPage = (walkStepSpec instanceof Map) ? ((walkStepSpec.page ?: ((walkStepSpec.steps instanceof List && walkStepSpec.steps) ? (walkStepSpec.steps[0] instanceof Map ? walkStepSpec.steps[0].page : null) : null))?.toString()) : null
    if (wsPage && args?.__rmRule == true) wsPage = null
    def backup
    try { backup = wsPage ? _rmBackupBeforeEdit(appId, backupReason, wsPage) : _rmBackupBeforeEdit(appId, backupReason) }
    catch (Exception backupErr) {
        Map unavailable = _itemBackupIndexUnavailableResult(backupErr)
        if (unavailable == null) throw backupErr
        return unavailable + [appId: appId]
    }
    // When the backup decision read no config (a transient failure), the kind is read here.
    if (wsPage && !_memoKnowsRuleMachine(appId)) {
        try { _rmNoteRuleMachineConfig(appId, _rmFetchConfigJson(appId, wsPage)) } catch (Exception kindExc) {
            mcpLog("warn", "rm-native", "walkStep: app ${appId}'s kind could not be read on page ${wsPage} (${kindExc.message ?: kindExc}); walked as a non-RM app")
            _memoNoteSkippedCheck("app kind (walked as a non-RM app)")
        }
    }
    if (wsPage && _memoRuleMachine(appId) == true) wsPage = null

    // walkStep: drive any RM page one operation at a time (introspect/write/click/navigate) and get the
    // schema diff, value echo, health and sub-page links back. The fallback for what the shortcuts don't
    // cover.
    if (walkStepSpec) {
        try {
            // Every walkStep op gets the request clock (spec.__reqT0) on a copy, so it sheds the trailing probe
            // once the budget is spent.
            def wsSpec = walkStepSpec
            if (args?.__reqT0 != null || wsPage) {
                wsSpec = new LinkedHashMap(walkStepSpec)
                if (args?.__reqT0 != null) wsSpec.__reqT0 = args.__reqT0
                // A non-RM wizard: health reads the page the walk is on, not the default page.
                if (wsPage) wsSpec.__activePage = true
            }
            def result = _rmWalkStep(appId, wsSpec)
            result.appId = appId
            result.backup = backup
            // Click mainPage Done only when the walk finished with 'done': a single done, or a drive whose every
            // step ran and succeeded.
            def wsOp = walkStepSpec?.operation?.toString()
            def requestedSteps = (walkStepSpec?.steps instanceof List) ? (walkStepSpec.steps as List) : []
            def driveEndsInDone = wsOp == "drive" && !requestedSteps.isEmpty() &&
                (requestedSteps[-1] instanceof Map) && ((Map) requestedSteps[-1]).operation?.toString()?.trim() == "done"
            def driveCompleted = driveEndsInDone && result?.lastStepOperation == "done" &&
                result?.status != "in_progress" && result?.stepsRun == result?.stepsRequested &&
                !((result?.steps ?: []) as List).any { it?.success == false }
            if (wsOp == "done" || driveCompleted) {
                def walkDone = null
                try { walkDone = _rmSubmitMainPageDone(appId) }
                catch (Exception doneExc) { mcpLog("warn", "rm-native", "walkStep: trailing mainPage Done click failed for app ${appId}: ${doneExc.message} -- in-flight state markers may linger and corrupt subsequent edits") }
                if (walkDone?.done == false) {
                    result.mainPageDoneFailed = true
                    result.mainPageDoneError = walkDone.reason
                    // The Done click drives the app's update lifecycle; a miss means subscriptions
                    // / schedules did not re-initialize even though settings are written, so the
                    // result is NOT clean -- flip success so callers branching on it don't treat a
                    // half-committed edit as done.
                    result.success = false
                    result.repairHints = (result.repairHints ?: []) + [_rmMainPageDoneRepairHint(appId, walkDone)]
                }
            } else if (driveEndsInDone) {
                result.mainPageDoneSkipped = true
                result.repairHints = (result.repairHints ?: []) + ["The mainPage Done (the app's update lifecycle) was not run because the drive did not complete. Once the failed or remaining steps are done, finish with walkStep {page:'mainPage', operation:'done'}.".toString()]
            }
            return result
        } catch (Exception e) {
            mcpLogError("rm-native", "walkStep failed for app ${appId}", e)
            // A page-check refusal is decided before any settings or Done are submitted, but after the
            // walker opened the page to read its inputs, which runs the app's page code.
            def uiRefusal = (e instanceof IllegalStateException) && e.message?.startsWith("Hubitat's app page would refuse")
            // An unrendered button or an unopened doActPage is refused before any POST.
            def walkRefusal = _rmOutcomeFlags(e).notSent == true
            return [
                success: false,
                appId: appId,
                error: e.message ?: e.toString(),
                backup: backup,
                restoreHint: uiRefusal ? _rmPreflightRestoreHint(null, backup, "Refused before submitting: no settings were written and no Done was sent, so nothing needs to be restored. The page was still opened to read its inputs, which runs the app's page code.") :
                    walkRefusal ? _rmPreflightRestoreHint(null, backup, "Refused before any write or click was sent, so nothing needs to be restored.") :
                    _rmRestoreBaselineHint(backup)
            ]
        }
    }

    // modifyAction: an in-place edit through RM's row editor. Actions are read when the rule
    // runs, so no updateRule follows (the same as addAction).
    if (modifyActionSpec) {
        def maResult
        try {
            maResult = _rmModifyAction(appId, modifyActionSpec.index as Integer, modifyActionSpec.mods as Map, args?.__reqT0 as Long, modifyActionPlan)
        } catch (Exception e) {
            mcpLogError("rm-native", "modifyAction failed for app ${appId}", e)
            return _rmBuildUpdateErrorResponse(appId, e.message, backup, "doActPage", e)
        }
        def maHealth
        try { maHealth = _rmCheckRuleHealth(appId) }
        catch (Exception healthExc) {
            maHealth = [ok: false, unreadable: true, checkErrors: [healthExc.message ?: healthExc.toString()]]
            mcpLog("warn", "rm-native", "modifyAction: post-edit health probe failed for app ${appId}: ${healthExc.message}")
        }
        boolean maOk = (maResult?.success == true) && _rmHealthGatePass(maHealth)
        // The health verdict outranks the readback's own success, so success and partial go on the right.
        def maOut = [appId: appId, backup: backup] + (maResult ?: [:]) + [
            success: maOk, partial: !maOk,
            health: maHealth,
            error: maOk ? null : (maResult?.error ?: "modifyAction: the edit committed but the health check reports the rule broken -- inspect health.issues and ${_rmRestoreClause(backup)} if the damage is real."),
            restoreHint: maOk ? null : _rmBackupHint(backup),
            note: maOk ? "Action ${modifyActionSpec.index} edited in place; its index and position are unchanged and the new values read back.".toString()
                       : "modifyAction did not verify cleanly -- see error.".toString()
        ]
        return maOut.findAll { k, v -> v != null }
    }


    if (removeActionSpec || clearActionsFlag || replaceActionsList != null || moveActionSpec) {
        // Hoisted out of the try block — Groovy `def` is block-scoped, so
        // declaring inside try would leave these undefined for the return
        // statement below.
        def removed = []
        def addedResults = []
        String replaceStopAfter = null
        def replaceStopItem = null
        // moveAction rich return ({beforePosition, afterPosition, indicesAfter}).
        // Hoisted for the same block-scope reason as the others above.
        def moveResult = null
        // removeAction rich return ({removedIndex, beforeIndices, afterIndices}).
        // Surfaced on the outer envelope alongside moveAction's rich return so
        // callers see the index list shift without re-fetching via hub_get_app_config.
        def removeResult = null
        // A rejected trailing updateRule leaves the change in settings but the running rule unsubscribed;
        // report it as updateRuleFailed / subscriptionsNotLive.
        def updateRuleFailed = false
        def subscriptionsNotLive = false
        def updateRuleError = null
        // Action indices before the clear, used only to echo actionsRequestedForRemoval when the clear
        // commits late.
        def preClearIndicesSnapshot = null
        // Batch-resolved valid-rule-id set for the replaceActions re-add loop,
        // populated by the pre-flight below when the incoming list targets a rule.
        def replaceValidRuleIds = null
        try {
            // Refuse a replaceActions list whose IF/END-IF or repeat structure is unbalanced before clearing.
            if (replaceActionsList != null) {
                def specIssues = _rmStructuralIssuesFromSequence(_rmStructuralSequenceFromSpecList(replaceActionsList))
                if (specIssues) {
                    throw new IllegalArgumentException("replaceActions blocked: the proposed action list is structurally imbalanced — ${specIssues.join('; ')}. Re-order the list, add the missing closer (capability='endIf' or 'stopRepeat'), or remove the orphan closer. RM is not touched and no actions are cleared.")
                }
                // Refuse anything a single add would refuse (a dangling rule target, an unknown
                // device, an unsupported numOp, ...) BEFORE clearActions wipes the rule. The
                // valid-rule-id set is resolved once here and reused by the re-add loop below.
                replaceValidRuleIds = _rmSpecListTargetsRule(replaceActionsList) ? _rmValidRuleIds() : null
                _rmPrevalidateActionSpecList(replaceActionsList, "replaceActions", replaceValidRuleIds, appId)
            }
            if (removeActionSpec) {
                if (removeActionSpec.index == null) throw new IllegalArgumentException("removeAction.index is required")
                def idx = (removeActionSpec.index as Integer)
                // Capture the helper's rich return ({removedIndex, beforeIndices,
                // afterIndices}) so the outer response can surface the index list
                // shift without forcing callers to re-fetch via hub_get_app_config.
                // Mirrors the moveAction rich-envelope propagation pattern above.
                removeResult = _rmDeleteAction(appId, idx)
                removed << idx
            }
            // Capture the helper's rich return ({beforePosition, afterPosition,
            // indicesAfter}) so the outer response can surface it without forcing
            // callers to re-fetch via hub_get_app_config. Mirrors the addLocalVariable
            // rich-envelope propagation pattern.
            if (moveActionSpec) {
                if (moveActionSpec.index == null) throw new IllegalArgumentException("moveAction.index is required")
                def dir = moveActionSpec.direction?.toString()
                if (!(dir in ["up", "down"])) throw new IllegalArgumentException("moveAction.direction must be 'up' or 'down'")
                moveResult = _rmMoveAction(appId, moveActionSpec.index as Integer, dir)
            }
            if (clearActionsFlag || replaceActionsList != null) {
                // Capture pre-write indices so the eventual-consistency catch
                // can echo them back as `actionsRequestedForRemoval`. Best
                // effort -- if the fetch itself fails, the catch falls back to
                // null and still emits the structured shape with what it has.
                try { preClearIndicesSnapshot = _rmCollectActionIndices(appId) }
                catch (Exception snapExc) {
                    mcpLog("debug", "rm-native", "pre-clearActions snapshot fetch failed for app ${appId}: ${snapExc.message} -- actionsRequestedForRemoval will be null on failure path")
                }
                try {
                    def cleared = _rmClearActions(appId) ?: []
                    removed = (removed + cleared).unique()
                } catch (Exception clearExc) {
                    // _rmClearActions threw. Late commit (trashActs returned 200, verify timed out): the delete may
                    // still be pending, and cancelTrash could commit it, so leave it to the envelope. Hard failure
                    // (nothing selected): cancelTrash backs the page out safely.
                    if (clearExc.message?.contains(getAsyncCommitMarker())) {
                        def cleanForLog = clearExc.message?.replace(getAsyncCommitMarker(), "")
                        mcpLog("warn", "rm-native", "clearActions threw on async-commit-likely path for app ${appId} (${cleanForLog}) -- skipping cancelTrash to avoid committing the pending delete")
                    } else {
                        mcpLog("warn", "rm-native", "clearActions threw mid-flow for app ${appId} (${clearExc.message}) -- auto-firing cancelTrash to recover the page")
                        try { _rmClickAppButton(appId, "cancelTrash", null, "selectActions") }
                        catch (Exception cancelExc) {
                            mcpLog("warn", "rm-native", "cancelTrash recovery also failed for app ${appId}: ${cancelExc.message} -- rule may need hub_restore_backup")
                        }
                    }
                    throw clearExc
                }
            }
            if (replaceActionsList != null) {
                replaceActionsList.eachWithIndex { spec, i ->
                    // Fail closed: the first failed or partial item stops every later add and finalisation.
                    if (replaceStopAfter) { addedResults << _rmBulkNotAttempted(replaceStopAfter); return }
                    try { addedResults << _rmAddAction(appId, _rmWithClock(spec as Map, args?.__reqT0 as Long), true, replaceValidRuleIds) }
                    catch (Exception ae) {
                        addedResults << (_rmAddRefusalResult(ae) + [specCapability: spec.capability, specAction: spec.action])
                        mcpLog("warn", "rm-native", "hub_set_rule: replaceActions[${i}] (${spec.capability}/${spec.action}) failed -- ${ae.message}")
                    }
                    if (_rmBulkItemBlocks(addedResults.last())) { replaceStopAfter = "replaceActions[${i}]".toString(); replaceStopItem = addedResults.last() }
                }
            }
        } catch (Exception e) {
            // Strip the internal asyncCommit marker before logging or returning the message.
            def cleanedError = e.message?.replace(getAsyncCommitMarker(), "")
            // Intentionally plain mcpLog (NOT mcpLogError): this site deliberately
            // sanitizes the asyncCommit marker out of the message, and mcpLogError
            // would re-leak the raw e.message (marker included) into stackTrace.
            mcpLog("error", "rm-native", "action mutation failed for app ${appId}: ${cleanedError}")
            // Rare: the clear's verify window ran out though trashActs returned 200. The caller checks
            // hub_get_app_config (absent = done); replaceActions does not add, which could double-write on a retry.
            def isAsyncCommitLikely = e.message?.contains(getAsyncCommitMarker()) &&
                                      (clearActionsFlag || replaceActionsList != null)
            if (isAsyncCommitLikely) {
                def actionsStillPresent = null
                try { actionsStillPresent = _rmCollectActionIndices(appId) }
                catch (Exception fetchExc) {
                    mcpLog("debug", "rm-native", "asyncCommitLikely: actionsStillPresent fetch failed for app ${appId}: ${fetchExc.message}")
                }
                def possibleStateEditAct = false
                try { possibleStateEditAct = (_rmGetStateEditAct(appId) != null) }
                catch (Exception editActExc) {
                    mcpLog("debug", "rm-native", "asyncCommitLikely: possibleStateEditAct fetch failed for app ${appId}: ${editActExc.message} -- defaulting to false")
                }
                def shape = [
                    success: false,
                    partial: true,
                    asyncCommitLikely: true,
                    appId: appId,
                    httpWriteStatus: 200,
                    actionsRequestedForRemoval: preClearIndicesSnapshot,
                    actionsStillPresent: actionsStillPresent,
                    possibleStateEditAct: possibleStateEditAct,
                    wizardStuck: false,
                    backup: backup,
                    restoreHint: _rmRollbackIfNotCommittedHint(backup),
                    verifyHint: "Call hub_get_app_config(appId=${appId}) and inspect the actions list -- if the deletion actually committed asynchronously after the response, do NOT call hub_restore_backup.",
                    safeRecovery: [
                        recommended: 'verify-then-decide',
                        verifyVia: "hub_get_app_config(appId: ${appId})",
                        ifActionsAbsent: 'treat as success -- clearActions committed post-response',
                        ifActionsPresent: "wait 15s, then call hub_get_app_config to re-check. If actions still present, retry clearActions. If state.editAct is set it will block delete-class clicks -- abort the open editor with hub_set_rule(button='actionCancel', pageName='doActPage', confirm=true) (a cancelAct click was verified NOT to clear it), or delete + hub_restore_backup(scope='source') to recreate the rule with fresh state.",
                        avoid: ['cancelTrash']
                    ]
                ]
                if (replaceActionsList != null) {
                    shape.stage = 'replaceActions.clear_committed_late_no_add'
                    shape.pendingActionsToAdd = replaceActionsList
                    shape.clearActionsResult = [
                        stage: 'clearActions.verify_absent',
                        asyncCommitLikely: true,
                        actionsRequestedForRemoval: preClearIndicesSnapshot,
                        actionsStillPresent: actionsStillPresent,
                        error: cleanedError
                    ]
                    shape.error = "Clear half may have committed asynchronously. Add half not attempted to prevent data loss if clear actually succeeded."
                    shape.verifyHint = "Call hub_get_app_config to confirm clear state. If actions absent (clear succeeded), call addAction (or bulk addActions) to complete the replacement. If actions still present (clear genuinely failed), call replaceActions again."
                } else {
                    shape.stage = 'clearActions.verify_absent'
                    shape.error = cleanedError
                }
                return shape
            }
            def result = _rmBuildUpdateErrorResponse(appId, e.message, backup, "doActPage", e)
            // removeAction exhaustion keeps the flat error with a verify hint, detected by the "one re-click"
            // phrase in _rmDeleteAction's throw.
            def isRetryExhaustion = e.message?.contains("one re-click")
            if (isRetryExhaustion) {
                result.restoreHint = _rmRollbackIfNotCommittedHint(backup)
                result.verifyHint = "Call hub_get_app_config(appId=${appId}) and inspect the actions list -- if the operation actually committed despite the false-fail, do NOT call hub_restore_backup."
            }
            return result
        }
        if (replaceStopAfter) {
            return _rmBulkStoppedResult(appId, backup, replaceStopAfter, replaceStopItem, [removedIndices: removed ?: null, addedActions: addedResults])
        }
        // Trailing updateRule fires only when no added item stopped the batch (that case returned above).
        // Hoisted out of the per-item try so a rejection gets the dedicated not-live slots rather than
        // the generic "mutation errored partway" shape: the mutation state IS committed.
        try { _rmClickAppButton(appId, "updateRule") }
        catch (Exception updateExc) {
            updateRuleFailed = true
            subscriptionsNotLive = true
            updateRuleError = updateExc.message
            mcpLog("warn", "rm-native", "action mutation: trailing updateRule click failed for app ${appId} -- mutations committed but subscriptions may not populate: ${updateExc.message}")
        }
        def addedOk = (addedResults ?: []).count { it?.success != false }
        def addedTotal = (replaceActionsList ?: []).size()
        def health = _rmCheckRuleHealth(appId)
        // Lift per-item partial into the envelope; addedOk counts success alone.
        def itemsPartial = replaceActionsList != null && (addedOk != addedTotal ||
            addedResults.any { it instanceof Map && it.partial == true })
        def repairHints = []
        if (updateRuleFailed) {
            repairHints << "updateRule click was rejected after the action mutation committed. The action rows are baked but the rule will not subscribe to its device events until updateRule fires. Retry hub_set_rule(button='updateRule', confirm=true), or ${_rmRestoreClause(backup)} if the retry also fails."
        }
        // A moveAction that may commit late returns success:false + asyncCommitLikely; fold that in and show
        // the verify-first hint.
        def moveSoftFail = moveResult != null && moveResult.success == false
        if (moveSoftFail && moveResult.verifyHint) {
            repairHints << moveResult.verifyHint.toString()
        }
        def out = [
            success: (addedOk == addedTotal) && _rmHealthGatePass(health) && !updateRuleFailed && !moveSoftFail,
            partial: itemsPartial || updateRuleFailed || (moveResult?.partial == true),
            appId: appId,
            backup: backup,
            removedIndices: removed ?: null,
            addedActions: addedResults ?: null,
            // Surface moveAction's rich return so callers can see the new position
            // without a follow-up hub_get_app_config. Null when this dispatch path was
            // not moveAction.
            beforePosition: moveResult?.beforePosition,
            afterPosition: moveResult?.afterPosition,
            indicesAfter: moveResult?.indicesAfter,
            asyncCommitLikely: moveResult?.asyncCommitLikely,
            verifyHint: moveResult?.verifyHint,
            // Surface removeAction's rich return alongside moveAction's; null when
            // this dispatch path was not removeAction. beforeIndices/afterIndices
            // let callers diff the index list without a re-fetch.
            removedIndex: removeResult?.removedIndex,
            beforeIndices: removeResult?.beforeIndices,
            afterIndices: removeResult?.afterIndices,
            // True when the first delAct click silently no-oped and the helper's
            // verified re-click is what landed the removal (see _rmDeleteAction).
            reclicked: removeResult?.reclicked,
            health: health,
            updateRuleFailed: updateRuleFailed,
            subscriptionsNotLive: subscriptionsNotLive,
            updateRuleError: updateRuleError,
            repairHints: repairHints,
            note: replaceActionsList != null
                ? "Replaced actions: removed ${removed?.size() ?: 0}, added ${addedOk}/${addedTotal}; updateRule ${updateRuleFailed ? 'FAILED -- subscriptions may not be live' : 'fired'}."
                : (clearActionsFlag ? "Cleared ${removed?.size() ?: 0} ${(removed?.size() ?: 0) == 1 ? 'action' : 'actions'}; updateRule ${updateRuleFailed ? 'FAILED -- subscriptions may not be live' : 'fired'}."
                : (moveActionSpec ? (moveSoftFail ? "Move of action ${moveActionSpec.index} ${moveActionSpec.direction} could NOT be confirmed within the verify window -- see verifyHint before retrying." : "Moved action ${moveActionSpec.index} ${moveActionSpec.direction}; updateRule ${updateRuleFailed ? 'FAILED -- subscriptions may not be live' : 'fired'}.")
                : "Removed action ${removeActionSpec?.index}; updateRule ${updateRuleFailed ? 'FAILED -- subscriptions may not be live' : 'fired'}."))
        ]
        return out
    }

    // removeTrigger/modifyTrigger, each followed by updateRule; a rejected updateRule is reported as in the
    // action block.
    if (removeTriggerSpec || modifyTriggerSpec) {
        def trigMutResult = null
        def trigIdxOut = null
        try {
            if (removeTriggerSpec) {
                if (removeTriggerSpec.index == null) throw new IllegalArgumentException("removeTrigger.index is required")
                trigIdxOut = (removeTriggerSpec.index as Integer)
                trigMutResult = _rmRemoveTrigger(appId, trigIdxOut)
            } else if (modifyTriggerSpec) {
                if (modifyTriggerSpec.index == null) throw new IllegalArgumentException("modifyTrigger.index is required")
                if (!(modifyTriggerSpec.mods instanceof Map)) throw new IllegalArgumentException("modifyTrigger.mods is required and must be a Map (e.g. {state: 'on'})")
                trigIdxOut = (modifyTriggerSpec.index as Integer)
                trigMutResult = _rmModifyTrigger(appId, trigIdxOut, modifyTriggerSpec.mods as Map)
            }
        } catch (Exception e) {
            mcpLogError("rm-native", "trigger mutation failed for app ${appId}", e)
            // Trigger retry exhaustion keeps the flat error + restoreHint. A pre-flight refusal (the "RM is not
            // touched" sentinel) changed nothing, so it gets the not-touched wording instead; the two can't overlap.
            def isPreflightRefusal = e.message?.contains("RM is not touched")
            def isRetryExhaustion = e.message?.contains("after a re-click")
            def trigResult = [
                success: false,
                appId: appId,
                error: e.message ?: e.toString(),
                backup: backup,
                restoreHint: isPreflightRefusal ?
                    _rmPreflightRestoreHint(null, backup) :
                    (isRetryExhaustion ?
                        _rmRollbackIfNotCommittedHint(backup) :
                        _rmRestoreBaselineHint(backup))
            ]
            if (isRetryExhaustion) {
                trigResult.verifyHint = "Call hub_get_app_config(appId=${appId}) and inspect the triggers list -- if the operation actually committed despite the false-fail, do NOT call hub_restore_backup."
            }
            if (_rmOutcomeFlags(e).wizardStuck == true) {
                trigResult.wizardStuck = true
                trigResult.restoreHint = _rmModifyTriggerWizardRecoveryHint(appId) + " " + trigResult.restoreHint
            }
            return trigResult
        }
        // Trailing updateRule fires AFTER the mutation completes. Hoisted out
        // of the per-mutation try so a rejection here doesn't get routed
        // through the generic "trigger mutation failed" error response.
        def updateRuleFailed = false
        def subscriptionsNotLive = false
        def updateRuleError = null
        try { _rmClickAppButton(appId, "updateRule") }
        catch (Exception updateExc) {
            updateRuleFailed = true
            subscriptionsNotLive = true
            updateRuleError = updateExc.message
            mcpLog("warn", "rm-native", "trigger mutation: trailing updateRule click failed for app ${appId} -- mutation committed but subscriptions may not populate: ${updateExc.message}")
        }
        def health = _rmCheckRuleHealth(appId)
        def repairHints = []
        if (updateRuleFailed) {
            repairHints << "updateRule click was rejected after the trigger mutation committed. The trigger row is baked but the rule will not subscribe to its device events until updateRule fires. Retry hub_set_rule(button='updateRule', confirm=true), or ${_rmRestoreClause(backup)} if the retry also fails."
        }
        if (removeTriggerSpec) {
            // Lift partial from _rmRemoveTrigger if it ever reports one, as modifyTrigger does.
            def removedInnerPartial = trigMutResult?.partial == true
            return [
                success: trigMutResult.success != false && _rmHealthGatePass(health) && !updateRuleFailed,
                partial: updateRuleFailed || removedInnerPartial,
                appId: appId,
                backup: backup,
                removedIndex: trigMutResult.removedIndex,
                beforeIndices: trigMutResult.beforeIndices,
                afterIndices: trigMutResult.afterIndices,
                updateRuleFailed: updateRuleFailed,
                subscriptionsNotLive: subscriptionsNotLive,
                updateRuleError: updateRuleError,
                repairHints: repairHints,
                health: health,
                note: "Removed trigger ${trigIdxOut}; updateRule ${updateRuleFailed ? 'FAILED -- subscriptions may not be live' : 'fired'}."
            ]
        }
        // A skipped tstate<n> or a failed verify read makes modifyTrigger partial, like every other edit.
        def trigInnerPartial = _rmModifyTriggerInnerPartial(trigMutResult)
        // Inner-only partial hint (trigger inner skipped/verify-failed BUT the
        // trailing updateRule landed clean); without it the caller has to
        // drill into settingsSkipped[] to discover why partial flipped true.
        if (trigInnerPartial && !updateRuleFailed) {
            repairHints << "modifyTrigger reported inner partial (settingsSkipped or verificationFetchFailed). Inspect settingsSkipped[] for per-field silent_rejection reasons. Re-attempt the modifyTrigger call, or use removeTrigger + addTrigger to rebuild the trigger atomically."
        }
        return [
            success: trigMutResult.success != false && _rmHealthGatePass(health) && !updateRuleFailed,
            partial: updateRuleFailed || trigInnerPartial,
            appId: appId,
            backup: backup,
            modifiedIndex: trigMutResult.modifiedIndex,
            verifiedState: trigMutResult.verifiedState,
            verificationFetchFailed: trigMutResult.verificationFetchFailed,
            settingsApplied: trigMutResult.settingsApplied,
            settingsSkipped: trigMutResult.settingsSkipped,
            updateRuleFailed: updateRuleFailed,
            subscriptionsNotLive: subscriptionsNotLive,
            updateRuleError: updateRuleError,
            repairHints: repairHints,
            health: health,
            note: "Modified trigger ${trigIdxOut}; updateRule ${updateRuleFailed ? 'FAILED -- subscriptions may not be live' : 'fired'}."
        ]
    }

    if (addTriggerSpec) {
        // One-call trigger creation; updateRule follows (bulk and patches run their own at the end).
        def trigResult
        try {
            trigResult = _rmAddTrigger(appId, addTriggerSpec)
        } catch (Exception e) {
            mcpLogError("rm-native", "addTrigger failed for app ${appId}", e)
            return _rmBuildUpdateErrorResponse(appId, e.message, backup, "selectTriggers", e)
        }
        // updateRule after any committed trigger, partial included; a rejected updateRule is reported
        // (updateRuleFailed / subscriptionsNotLive / updateRuleError) and flips success false + partial, as
        // addRequiredExpression does.
        def updateRuleFailed = false
        def subscriptionsNotLive = false
        def updateRuleError = null
        if (trigResult?.success != false) {
            try { _rmClickAppButton(appId, "updateRule") }
            catch (Exception updateExc) {
                updateRuleFailed = true
                subscriptionsNotLive = true
                updateRuleError = updateExc.message
                mcpLog("warn", "rm-native", "addTrigger: trailing updateRule click failed for app ${appId} -- trigger committed but subscriptions may not populate until the next updateRule: ${updateExc.message}")
            }
        }
        def trigRepairHints = (trigResult?.repairHints as List) ?: []
        if (updateRuleFailed) {
            trigRepairHints = trigRepairHints + ["updateRule click was rejected after the trigger row committed. The trigger settings are baked but the rule will not subscribe to its device events until updateRule fires. Retry hub_set_rule(button='updateRule', confirm=true), or ${_rmRestoreClause(backup)} if the retry also fails."]
        }
        return [
            success: (trigResult?.success != false) && !updateRuleFailed,
            partial: (trigResult?.partial == true) || updateRuleFailed,
            appId: appId,
            backup: backup,
            triggerIndex: trigResult?.triggerIndex,
            settingsApplied: trigResult?.settingsApplied,
            settingsSkipped: trigResult?.settingsSkipped,
            configPageError: trigResult?.configPageError,
            hubRenderError: trigResult?.hubRenderError,
            updateRuleFailed: updateRuleFailed,
            subscriptionsNotLive: subscriptionsNotLive,
            updateRuleError: updateRuleError,
            repairHints: trigRepairHints,
            health: trigResult?.health,
            verificationFetchFailed: trigResult?.verificationFetchFailed,
            note: "Trigger added + updateRule ${updateRuleFailed ? 'FAILED -- subscriptions may not be live' : 'fired (subscriptions populated). Successive addTrigger calls now self-contain their re-init -- no manual updateRule needed'}."
        ]
    }

    if (addActionSpec) {
        // One-call action creation; actions bake through doActPage -> selectActions, no updateRule needed.
        def actResult
        try {
            actResult = _rmAddAction(appId, _rmWithClock(addActionSpec, args?.__reqT0 as Long))
        } catch (Exception e) {
            mcpLogError("rm-native", "addAction failed for app ${appId}", e)
            return _rmBuildUpdateErrorResponse(appId, e.message, backup, "doActPage", e)
        }
        return [
            success: actResult?.success != false,
            partial: actResult?.partial,
            appId: appId,
            backup: backup,
            actionIndex: actResult?.actionIndex,
            capability: actResult?.capability,
            action: actResult?.action,
            actType: actResult?.actType,
            actSubType: actResult?.actSubType,
            settingsApplied: actResult?.settingsApplied,
            settingsSkipped: actResult?.settingsSkipped,
            configPageError: actResult?.configPageError,
            hubRenderError: actResult?.hubRenderError,
            rolledBack: actResult?.rolledBack,
            fieldsWritten: actResult?.fieldsWritten,
            wizardStuck: actResult?.wizardStuck,
            repairHints: actResult?.repairHints,
            health: actResult?.health,
            verificationFetchFailed: actResult?.verificationFetchFailed,
            note: _rmAddActionNote(actResult)
        ]
    }

    if (patchesList != null) {
        // patches: a list of single operations (addRequiredExpression, addTrigger(s), addAction(s),
        // addLocalVariable, settings, removeAction, clearActions, replaceActions, moveAction, modifyAction,
        // removeTrigger, modifyTrigger, button), applied in order with one updateRule at the end. Not a
        // transaction: a failed or partial op stops the batch and earlier ops stay.
        def patchResults = []
        def patchErr = null
        // Trailing-updateRule failure propagation: when the post-patch updateRule
        // click is rejected, the patches landed but the rule never bakes them. The
        // catch in the trailing-updateRule block below sets these so the response
        // surfaces dedicated slots (sibling pattern from F2 addRequiredExpression).
        def updateRuleFailed = false
        def patchesNotLive = false
        def updateRuleError = null
        // Original tokens and baseline health for each replaceRequiredExpression that committed in a batch;
        // the batch end restores only that expression.
        def deferredReReplaces = []
        // A rule has exactly ONE Required Expression, so only one replaceRequiredExpression is
        // valid per batch; a second would replace the first (and its token rollback would land on
        // the intermediate expression, not the original). Track it to refuse the second.
        def seenReplaceRE = false
        // Replacement rollback context and whole-batch attribution cannot cross a checkpoint.
        boolean canPausePatchBatch = mrtrWorkerSliceStartedAt == null || !patchesList.any {
            it instanceof Map && it.containsKey("replaceRequiredExpression")
        }
        // Resolve the valid-rule-id set once for the whole patch batch (only when
        // some action op targets a rule) and thread it to every addAction /
        // addActions / replaceActions op below -- one resolve per batch, not per item.
        def patchBatchTargetsRule = (patchesList ?: []).any { p ->
            if (!(p instanceof Map)) return false
            def pm = p as Map
            _rmSpecTargetsRule(pm.addAction) ||
                (pm.addActions instanceof List && _rmSpecListTargetsRule(pm.addActions as List)) ||
                (pm.replaceActions instanceof List && _rmSpecListTargetsRule(pm.replaceActions as List))
        }
        def patchValidRuleIds = patchBatchTargetsRule ? _rmValidRuleIds() : null
        // Fail closed, as the top-level bulk paths do: the first failed or partial op, or inner item of an
        // addTriggers/addActions/replaceActions op, stops every later op and the batch-end updateRule. Later
        // ops and inner items are reported notAttempted, and the stop is decided before any budget checkpoint,
        // so a pause never hands back a skipped tail.
        String patchStopAfter = null
        def patchStopItem = null
        try {
            // Indexed for-loop (not eachWithIndex) so the time-budget checkpoint can
            // cleanly break/return between ops -- a Groovy closure can't break out of a loop.
            int pi = -1
            for (def p : patchesList) {
                pi++
                if (patchStopAfter) {
                    def skippedOp = (p instanceof Map && !((Map) p).isEmpty()) ? ((Map) p).keySet().first() : null
                    patchResults << ([op: skippedOp] + _rmBulkNotAttempted(patchStopAfter))
                    continue
                }
                String innerStopAfter = null
                def innerStopItem = null
                // Budget checkpoint between ops (never before the first). Return before the batch-end updateRule:
                // the resume call's own updateRule bakes everything once the rest is done.
                if (pi > 0 && canPausePatchBatch && _resumableBudgetExceeded(args?.__reqT0 as Long)) {
                    def patchesRemaining = patchesList.subList(pi, patchesList.size()).collect { _stripInternalClock(it) }
                    return _patchesPauseResult(appId, backup, patchResults, patchesRemaining)
                }
                if (!(p instanceof Map)) {
                    patchResults << [success: false, error: "patches[${pi}] is not a Map", spec: p]
                    patchStopAfter = "patches[${pi}]".toString()
                    patchStopItem = patchResults.last()
                    continue
                }
                def pm = p as Map
                try {
                    if (pm.containsKey("settings")) {
                        // Same object-value refusal as top-level settings; without it the map is saved as its text.
                        if (pm.settings instanceof Map && (pm.settings as Map).values().any { it instanceof Map }) {
                            _rmRejectNonDeviceMapSettings(appId, pm.pageName?.toString()?.trim() ?: null, pm.settings as Map)
                        }
                        // Apply settings via _rmUpdateAppSettings (no auto-updateRule).
                        def cfg = _rmFetchConfigJson(appId, pm.pageName?.toString())
                        def schema = _rmCollectInputSchema(cfg?.configPage)
                        _rmUpdateAppSettings(appId, pm.settings as Map, schema)
                        patchResults << [success: true, op: "settings", keys: (pm.settings as Map).keySet().toList()]
                    } else if (pm.containsKey("button")) {
                        if (pm.pageName) _rmClickRenderedButton(appId, pm.button.toString(), pm.stateAttribute?.toString(), pm.pageName.toString())
                        else _rmClickAppButton(appId, pm.button.toString(), pm.stateAttribute?.toString(), null)
                        patchResults << [success: true, op: "button", name: pm.button]
                    } else if (pm.containsKey("addTrigger")) {
                        patchResults << ([op: "addTrigger"] + _rmAddTrigger(appId, pm.addTrigger as Map))
                    } else if (pm.containsKey("addTriggers")) {
                        // Indexed for-loop (not .each) so the relay-budget checkpoint can break
                        // mid-op: a single patch op carrying a large inner list must not run the
                        // whole list un-paused. On a mid-op pause, hand back this op rewritten to
                        // its un-processed inner triggers, prepended to the remaining patches.
                        def innerList = (pm.addTriggers as List)
                        def innerResults = []
                        int ii = -1
                        boolean innerPaused = false
                        for (def tspec : innerList) {
                            ii++
                            if (innerStopAfter) { innerResults << _rmBulkNotAttempted(innerStopAfter); continue }
                            if (ii > 0 && canPausePatchBatch && _resumableBudgetExceeded(args?.__reqT0 as Long)) {
                                innerPaused = true
                                break
                            }
                            try { innerResults << _rmAddTrigger(appId, tspec as Map) }
                            catch (Exception e) { innerResults << _rmAddRefusalResult(e) }
                            if (_rmBulkItemBlocks(innerResults.last())) {
                                innerStopAfter = "patches[${pi}].addTriggers[${ii}]".toString()
                                innerStopItem = innerResults.last()
                            }
                        }
                        // Outer success rolls up inner success — mark the
                        // outer entry as failed if ANY inner item failed,
                        // so opsOk count + the user-visible success flag
                        // accurately reflect partial-batch failures.
                        def innerOk = innerResults.every { (it instanceof Map) && (it.success != false) && (it.partial != true) }
                        def trigOpEntry = [success: innerOk, op: "addTriggers", results: innerResults]
                        // pausedMidOp lets MRTR restate a later stop in the original request's indices.
                        if (innerPaused) { trigOpEntry.partial = true; trigOpEntry.pausedMidOp = true }
                        patchResults << trigOpEntry
                        if (innerPaused) {
                            // Strip the internal clock from the un-processed inner specs (the
                            // top-level .collect only reaches each patch op's own keys, not the
                            // specs nested inside this rebuilt addTriggers op).
                            def remainingOp = [addTriggers: innerList.subList(ii, innerList.size()).collect { _stripInternalClock(it) }]
                            def patchesRemaining = ([remainingOp] + patchesList.subList(pi + 1, patchesList.size())).collect { _stripInternalClock(it) }
                            return _patchesPauseResult(appId, backup, patchResults, patchesRemaining)
                        }
                    } else if (pm.containsKey("addAction")) {
                        patchResults << ([op: "addAction"] + _rmAddAction(appId, _rmWithClock(pm.addAction as Map, args?.__reqT0 as Long), true, patchValidRuleIds))
                    } else if (pm.containsKey("addActions")) {
                        // Same mid-op relay-budget checkpoint as the addTriggers inner loop above.
                        def innerList = (pm.addActions as List)
                        def innerResults = []
                        int ii = -1
                        boolean innerPaused = false
                        for (def aspec : innerList) {
                            ii++
                            if (innerStopAfter) { innerResults << _rmBulkNotAttempted(innerStopAfter); continue }
                            if (ii > 0 && canPausePatchBatch && _resumableBudgetExceeded(args?.__reqT0 as Long)) {
                                innerPaused = true
                                break
                            }
                            try { innerResults << _rmAddAction(appId, _rmWithClock(aspec as Map, args?.__reqT0 as Long), true, patchValidRuleIds) }
                            catch (Exception e) { innerResults << _rmAddRefusalResult(e) }
                            if (_rmBulkItemBlocks(innerResults.last())) {
                                innerStopAfter = "patches[${pi}].addActions[${ii}]".toString()
                                innerStopItem = innerResults.last()
                            }
                        }
                        def innerOk = innerResults.every { (it instanceof Map) && (it.success != false) && (it.partial != true) }
                        def actOpEntry = [success: innerOk, op: "addActions", results: innerResults]
                        if (innerPaused) { actOpEntry.partial = true; actOpEntry.pausedMidOp = true }
                        patchResults << actOpEntry
                        if (innerPaused) {
                            // Strip the internal clock from the un-processed inner specs (the
                            // top-level .collect only reaches each patch op's own keys, not the
                            // specs nested inside this rebuilt addActions op).
                            def remainingOp = [addActions: innerList.subList(ii, innerList.size()).collect { _stripInternalClock(it) }]
                            def patchesRemaining = ([remainingOp] + patchesList.subList(pi + 1, patchesList.size())).collect { _stripInternalClock(it) }
                            return _patchesPauseResult(appId, backup, patchResults, patchesRemaining)
                        }
                    } else if (pm.containsKey("addRequiredExpression")) {
                        patchResults << ([op: "addRequiredExpression"] + _rmAddRequiredExpression(appId, pm.addRequiredExpression as Map))
                    } else if (pm.containsKey("replaceRequiredExpression")) {
                        // A rule has one Required Expression, so a second replaceRequiredExpression in a batch is refused: it
                        // would restore to the first one's result, not the original.
                        if (seenReplaceRE) {
                            patchResults << [success: false, op: "replaceRequiredExpression",
                                error: "patches[${pi}]: a rule has a single Required Expression; only one replaceRequiredExpression is valid per patches batch -- the second would replace the first. Remove the duplicate, or issue the second replace as a separate hub_set_rule call."]
                            patchStopAfter = "patches[${pi}]".toString()
                            patchStopItem = patchResults.last()
                            continue
                        }
                        seenReplaceRE = true
                        // No per-op snapshot: a failed replace backs out through the token editor and a batch-end rollback
                        // uses its original tokens. deferFinalize: the batch fires one updateRule and owns rule health, so a
                        // sibling's transient imbalance can't roll this replace back mid-batch.
                        def replRes = _rmReplaceRequiredExpression(appId, pm.replaceRequiredExpression as Map, null, true)
                        // Keep a committed replace's restore context for the batch end, then strip it from the entry.
                        if (replRes instanceof Map && replRes._deferredRERestore instanceof Map) {
                            def ctx = (replRes._deferredRERestore as Map)
                            deferredReReplaces << [resultIndex: patchResults.size(), backup: ctx.backup, baselineHealth: ctx.baselineHealth,
                                                   origTokens: ctx.origTokens]
                            replRes = (replRes as Map).findAll { k, v -> k != "_deferredRERestore" }
                        }
                        patchResults << ([op: "replaceRequiredExpression"] + replRes)
                    } else if (pm.containsKey("addLocalVariable")) {
                        patchResults << ([op: "addLocalVariable"] + _rmAddLocalVariable(appId, pm.addLocalVariable as Map))
                    } else if (pm.containsKey("removeLocalVariable")) {
                        def rlvName = (pm.removeLocalVariable instanceof Map) ? pm.removeLocalVariable.name?.toString()?.trim() : null
                        if (!rlvName) throw new IllegalArgumentException("removeLocalVariable requires 'name'")
                        patchResults << ([op: "removeLocalVariable"] + _rmRemoveLocalVariable(appId, rlvName))
                    } else if (pm.containsKey("removeAction")) {
                        if (pm.removeAction.index == null) throw new IllegalArgumentException("removeAction.index required")
                        // Capture the helper's rich return so per-patch entries
                        // surface the index list shift the same way the action-
                        // mutation envelope does. Mirrors the moveAction sibling
                        // below.
                        def rmRes = _rmDeleteAction(appId, pm.removeAction.index as Integer)
                        def rmEntry = [
                            success: true,
                            op: "removeAction",
                            index: pm.removeAction.index,
                            removedIndex: rmRes?.removedIndex,
                            beforeIndices: rmRes?.beforeIndices,
                            afterIndices: rmRes?.afterIndices
                        ]
                        patchResults << rmEntry
                    } else if (pm.containsKey("clearActions")) {
                        def cleared
                        try { cleared = _rmClearActions(appId) ?: [] }
                        catch (Exception clearExc) {
                            // As at the top level: skip cancelTrash when the clear may still commit.
                            if (clearExc.message?.contains(getAsyncCommitMarker())) {
                                def cleanForLog = clearExc.message?.replace(getAsyncCommitMarker(), "")
                                mcpLog("warn", "rm-native", "patches[${pi}].clearActions: async-commit-likely path for app ${appId} (${cleanForLog}) -- skipping cancelTrash to avoid committing the pending delete")
                            } else {
                                try { _rmClickAppButton(appId, "cancelTrash", null, "selectActions") }
                                catch (Exception cancelExc) {
                                    mcpLog("warn", "rm-native", "patches[${pi}].clearActions: cancelTrash recovery also failed for app ${appId}: ${cancelExc.message} -- rule may need hub_restore_backup")
                                }
                            }
                            throw clearExc
                        }
                        patchResults << [success: true, op: "clearActions", removedIndices: cleared]
                    } else if (pm.containsKey("replaceActions") && (pm.replaceActions instanceof List) && (pm.replaceActions as List).isEmpty()) {
                        // replaceActions:[] is clearActions, as at the top level.
                        def cleared
                        try { cleared = _rmClearActions(appId) ?: [] }
                        catch (Exception clearExc) {
                            if (clearExc.message?.contains(getAsyncCommitMarker())) {
                                def cleanForLog = clearExc.message?.replace(getAsyncCommitMarker(), "")
                                mcpLog("warn", "rm-native", "patches[${pi}].clearActions: async-commit-likely path for app ${appId} (${cleanForLog}) -- skipping cancelTrash to avoid committing the pending delete")
                            } else {
                                try { _rmClickAppButton(appId, "cancelTrash", null, "selectActions") }
                                catch (Exception cancelExc) {
                                    mcpLog("warn", "rm-native", "patches[${pi}].clearActions: cancelTrash recovery also failed for app ${appId}: ${cancelExc.message} -- rule may need hub_restore_backup")
                                }
                            }
                            throw clearExc
                        }
                        patchResults << [success: true, op: "clearActions", removedIndices: cleared]
                    } else if (pm.containsKey("replaceActions")) {
                        // Pre-flight: validate the proposed list's structural
                        // balance before clearActions touches RM.
                        def patchSpecIssues = _rmStructuralIssuesFromSequence(_rmStructuralSequenceFromSpecList(pm.replaceActions as List))
                        if (patchSpecIssues) {
                            throw new IllegalArgumentException("patches[${pi}].replaceActions blocked: the proposed action list is structurally imbalanced — ${patchSpecIssues.join('; ')}. Re-order the list, add the missing closer (capability='endIf' or 'stopRepeat'), or remove the orphan closer. RM is not touched and no actions are cleared.")
                        }
                        // Refuse anything a single add would refuse BEFORE clearActions wipes the
                        // rule (same guard as the top-level replaceActions path).
                        _rmPrevalidateActionSpecList(pm.replaceActions as List, "patches[${pi}].replaceActions".toString(), patchValidRuleIds, appId)
                        def cleared
                        try { cleared = _rmClearActions(appId) ?: [] }
                        catch (Exception clearExc) {
                            // Same asyncCommit-vs-hard-fail split as the top-
                            // level dispatcher and patches[clearActions] above.
                            if (clearExc.message?.contains(getAsyncCommitMarker())) {
                                def cleanForLog = clearExc.message?.replace(getAsyncCommitMarker(), "")
                                mcpLog("warn", "rm-native", "patches[${pi}].replaceActions: async-commit-likely path for app ${appId} (${cleanForLog}) -- skipping cancelTrash to avoid committing the pending delete")
                            } else {
                                try { _rmClickAppButton(appId, "cancelTrash", null, "selectActions") }
                                catch (Exception cancelExc) {
                                    mcpLog("warn", "rm-native", "patches[${pi}].replaceActions: cancelTrash recovery also failed for app ${appId}: ${cancelExc.message} -- rule may need hub_restore_backup")
                                }
                            }
                            throw clearExc
                        }
                        def innerResults = []
                        int ri = -1
                        for (def aspec : (pm.replaceActions as List)) {
                            ri++
                            if (innerStopAfter) { innerResults << _rmBulkNotAttempted(innerStopAfter); continue }
                            try { innerResults << _rmAddAction(appId, _rmWithClock(aspec as Map, args?.__reqT0 as Long), true, patchValidRuleIds) }
                            catch (Exception e) { innerResults << _rmAddRefusalResult(e) }
                            if (_rmBulkItemBlocks(innerResults.last())) {
                                innerStopAfter = "patches[${pi}].replaceActions[${ri}]".toString()
                                innerStopItem = innerResults.last()
                            }
                        }
                        def innerOk = innerResults.every { (it instanceof Map) && (it.success != false) && (it.partial != true) }
                        patchResults << [success: innerOk, op: "replaceActions", removedIndices: cleared, addedResults: innerResults]
                    } else if (pm.containsKey("moveAction")) {
                        if (pm.moveAction.index == null) throw new IllegalArgumentException("moveAction.index required")
                        def dir = pm.moveAction.direction?.toString()
                        if (!(dir in ["up", "down"])) throw new IllegalArgumentException("moveAction.direction must be up|down")
                        // Capture the helper's rich return ({beforePosition, afterPosition,
                        // indicesAfter}) so per-patch entries surface the move outcome the
                        // same way the action-mutation envelope does. Without these, callers
                        // would need a separate hub_get_app_config to see where the action moved.
                        def mvRes = _rmMoveAction(appId, pm.moveAction.index as Integer, dir)
                        patchResults << [
                            success: mvRes?.success != false,
                            op: "moveAction",
                            index: pm.moveAction.index,
                            direction: dir,
                            beforePosition: mvRes?.beforePosition,
                            afterPosition: mvRes?.afterPosition,
                            indicesAfter: mvRes?.indicesAfter,
                            asyncCommitLikely: mvRes?.asyncCommitLikely,
                            verifyHint: mvRes?.verifyHint,
                            partial: mvRes?.partial == true
                        ]
                    } else if (_rmPatchModifyOpKeys().any { pm.containsKey(it) }) {
                        patchResults << _rmPatchModifyOp(appId, pm, args?.__reqT0 as Long)
                    } else {
                        patchResults << [success: false, error: "patches[${pi}] has no recognized operation key. Supported: settings, button, addTrigger(s), addAction(s), addRequiredExpression, replaceRequiredExpression, addLocalVariable, removeLocalVariable, removeAction, clearActions, replaceActions, moveAction, removeTrigger, modifyTrigger, modifyAction.", spec: p]
                    }
                } catch (Exception subExc) {
                    // Strip the internal asyncCommit marker from the per-patch error; patchErr stays the batch's.
                    def cleanedPatchErr = subExc.message?.replace(getAsyncCommitMarker(), "") ?: subExc.message
                    patchResults << ([success: false, op: pm.keySet().first(), error: cleanedPatchErr, spec: p] + _rmOutcomeKeys(subExc))
                    mcpLog("warn", "rm-native", "patches[${pi}] (${pm.keySet().first()}) failed: ${cleanedPatchErr}")
                }
                if (!patchResults.isEmpty() && _rmBulkItemBlocks(patchResults.last())) {
                    patchStopAfter = innerStopAfter ?: "patches[${pi}]".toString()
                    patchStopItem = innerStopItem ?: patchResults.last()
                }
            }
            // Fire updateRule once at the end, and only when no op stopped the batch, so the rule's
            // actions[] map and event subscriptions bake from the fully-loaded post-patch state. A
            // rejected click means the ops landed but never bake, so it gets dedicated envelope slots.
            if (!patchStopAfter) try { _rmClickAppButton(appId, "updateRule") }
            catch (Exception updateExc) {
                updateRuleFailed = true
                // patchesNotLive reports a rejected batch-end updateRule once for the whole batch.
                patchesNotLive = true
                updateRuleError = updateExc.message
                mcpLog("warn", "rm-native", "patches: trailing updateRule click failed for app ${appId} -- patches may not be live: ${updateExc.message}")
            }
        } catch (Exception e) {
            patchErr = e.message
            mcpLogError("rm-native", "patches application failed", e)
        }
        def opsOk = patchResults.count { it?.success != false }
        def health = _rmCheckRuleHealth(appId)
        def deferredRestoreHints = []
        // Batch-end rollback for deferred replaces: on a failed batch-end updateRule, or on a health
        // regression only when the replace was the batch's only op (in a larger batch health is cumulative).
        if (!deferredReReplaces.isEmpty()) {
            // A B5-refused second replace op still adds a patchResults entry, so patchResults.size()
            // > 1 and soleOpBatch is false -- the health-regression restore is suppressed. That is
            // intentional: the refused op did no hub work, so there is nothing to attribute or
            // over-restore, and updateRuleFailed still covers the real deferred risk.
            def soleOpBatch = (deferredReReplaces.size() == 1 && patchResults.size() == 1)
            def anyRestored = false
            deferredReReplaces.each { ctx ->
                def healthRegressed = soleOpBatch &&
                    _rmHealthRegressedVsBaseline(ctx.baselineHealth instanceof Map ? (ctx.baselineHealth as Map) : null, health)
                if (!(updateRuleFailed || healthRegressed)) return
                def why = updateRuleFailed ?
                    "the batch-end updateRule click was rejected, so the replaced Required Expression is not live" :
                    "the replacement introduced new rule-health problems that were not present before"
                def restoreOutcome = _rmRevertRequiredExpression(appId, ctx.origTokens as List,
                    "replaceRequiredExpression (in patches batch): ${why}.")
                def idx = ctx.resultIndex as Integer
                if (idx != null && idx >= 0 && idx < patchResults.size() && patchResults[idx] instanceof Map) {
                    // Restore outcome wins: the live RE did not stick, so the op did not succeed.
                    // requiredExpressionReplaced:false (the committed flag is now untrue -- the RE
                    // was rolled back) so the entry does not carry both replaced:true AND
                    // restored:true, and the restore note replaces the stale "deferred" note.
                    def restored = restoreOutcome.requiredExpressionRestored == true
                    patchResults[idx] = (patchResults[idx] as Map) + restoreOutcome + [
                        success: false, partial: !restored, requiredExpressionReplaced: false,
                        note: (restored ?
                            "Required Expression replace ROLLED BACK in batch: ${why}; the original was restored and confirmed." :
                            "Required Expression replace rollback was attempted in batch: ${why}; the original could not be confirmed restored. See error for the recovery outcome.").toString()]
                    anyRestored = true
                    // The rollback lives in patches[idx]; name it at the top level too so callers see the recovery outcome.
                    deferredRestoreHints << (restored ?
                        "patches[${idx}] replaceRequiredExpression was rolled back because ${why}; the original Required Expression was restored and confirmed. See patches[${idx}] for details." :
                        "patches[${idx}] replaceRequiredExpression rollback was attempted because ${why}, but the original Required Expression could not be confirmed restored: ${restoreOutcome.error ?: 'see patches[' + idx + ']'}").toString()
                }
            }
            // Recompute the success rollup after any deferred-restore reclassification above.
            if (anyRestored) opsOk = patchResults.count { it?.success != false }
        }
        if (patchStopAfter && patchErr == null) {
            def stopped = _rmBulkStoppedResult(appId, backup, patchStopAfter, patchStopItem, [patches: patchResults, health: health])
            if (deferredRestoreHints) stopped.repairHints = deferredRestoreHints + (stopped.repairHints ?: [])
            return stopped
        }
        def repairHints = []
        repairHints.addAll(deferredRestoreHints)
        if (updateRuleFailed) {
            repairHints << "updateRule click was rejected after the patch ops committed. The patch settings are baked but the rule will not re-evaluate / re-subscribe until updateRule fires. Retry hub_set_rule(button='updateRule', confirm=true), or ${_rmRestoreClause(backup)} if the retry also fails."
        }
        def out = [
            success: (patchErr == null) && (opsOk == patchResults.size()) && _rmHealthGatePass(health) && !updateRuleFailed,
            // partial whenever any op was partial or failed, or the trailing updateRule failed.
            partial: (patchErr != null) || (opsOk != patchResults.size()) || patchResults.any { it instanceof Map && (it.partial == true) } || updateRuleFailed,
            appId: appId,
            backup: backup,
            patches: patchResults,
            health: health,
            error: patchErr,
            updateRuleFailed: updateRuleFailed,
            patchesNotLive: patchesNotLive,
            updateRuleError: updateRuleError,
            repairHints: repairHints,
            note: "Applied ${opsOk}/${patchResults.size()} patch ops; updateRule ${updateRuleFailed ? 'FAILED -- patches may not be live' : 'fired once'}."
        ]
        return out
    }

    if (addLocalVariableSpec) {
        // Local variable creation. Walks selectActions' moreVar wizard:
        // click moreVar → write hbVar (name) → write varType → write
        // varValue (auto-commits). Verified live.
        def varResult
        try {
            varResult = _rmAddLocalVariable(appId, addLocalVariableSpec)
        } catch (Exception e) {
            mcpLogError("rm-native", "addLocalVariable failed for app ${appId}", e)
            return _rmBuildUpdateErrorResponse(appId, e.message, backup, "doActPage", e)
        }
        // Shared add/remove envelope: trailing updateRule, settle, health, and the helper's partial, error
        // and repair hints merged in.
        return _rmApplyLocalVarResult(appId, (varResult ?: [:]) as Map, backup, "add")
    }

    if (removeLocalVariableSpec) {
        // Remove a local variable via selectActions' deleteGV/delConfirm, then confirm it left
        // state.allLocalVars. Returns {name, deleted}.
        def varName = removeLocalVariableSpec.name?.toString()?.trim()
        if (!varName) throw new IllegalArgumentException("removeLocalVariable requires 'name' (the local variable to delete)")
        def varResult
        try {
            varResult = _rmRemoveLocalVariable(appId, varName)
        } catch (Exception e) {
            mcpLogError("rm-native", "removeLocalVariable failed for app ${appId}", e)
            return _rmBuildUpdateErrorResponse(appId, e.message, backup, "selectActions", e)
        }
        // A verify miss from the helper is partial, so an unconfirmed delete is never reported done.
        return _rmApplyLocalVarResult(appId, (varResult ?: [:]) as Map, backup, "remove")
    }

    if (addRequiredExpressionSpec) {
        // One-call Required Expression: walk STPage's conditions, commit with hasAll + hasRule, and return to
        // mainPage. The cond selector is not persisted; conditions live in rCapab_<n>/rDev_<n>/state_<n>.
        def reResult
        try {
            reResult = _rmAddRequiredExpression(appId, addRequiredExpressionSpec)
        } catch (Exception e) {
            mcpLogError("rm-native", "addRequiredExpression failed for app ${appId}", e)
            // STPage is the wizard page this branch operates on; the helper threads it
            // into the wizardStuck restoreHint and surfaces preflight refusals with the
            // current health block (which a future preflight on this branch would set).
            return _rmBuildUpdateErrorResponse(appId, e.message, backup, "STPage", e)
        }
        // updateRule, health and envelope via the shared finalize; add has no restore. A success:false the
        // helper returned (requiredExpressionAlreadyExists) passes through unchanged.
        return _rmFinalizeRequiredExpressionWrite(appId, reResult as Map, backup, "added", false, null)
    }

    if (replaceRequiredExpressionSpec) {
        // In-place replace via the token editor (see _rmReplaceRequiredExpression); the helper builds the
        // envelope.
        def replResult
        try {
            replResult = _rmReplaceRequiredExpression(appId, replaceRequiredExpressionSpec, backup)
        } catch (Exception e) {
            // An IllegalArgumentException is spec validation raised before any click, so the
            // expression is untouched and the rollback hint is replaced. Any other throw may have
            // touched the rule and keeps the backup hint.
            mcpLogError("rm-native", "replaceRequiredExpression failed for app ${appId}", e)
            def errResp = _rmBuildUpdateErrorResponse(appId, e.message, backup, "STPage", e)
            if (errResp?.wizardStuck != true && e instanceof IllegalArgumentException) {
                errResp.restoreHint = "No changes were made -- the Required Expression is intact (the spec was rejected before the rule was touched). No rollback is needed."
            }
            return errResp
        }
        // Spread the helper's envelope forward; the dispatcher sets appId/backup and ORs partial.
        return (replResult ?: [:]) + [appId: appId, backup: backup, partial: replResult?.partial == true]
    }

    if (addTriggersList || addActionsList) {
        // Bulk triggers and actions in one call, one updateRule at the end. A rejected updateRule leaves the
        // items committed but unsubscribed, reported as subscriptionsNotLive.
        def triggerResults = []
        def actionResults = []
        def updateRuleFailed = false
        def subscriptionsNotLive = false
        def updateRuleError = null
        def trigList = (addTriggersList ?: [])
        def actList = (addActionsList ?: [])
        String bulkStopAfter = null
        def bulkStopItem = null
        try {
            // Indexed loops so the budget checkpoint can return between items (never before the first).
            // The first failed or partial item stops the batch and skips finalisation, so a failed IF opener can
            // never leave its body as unconditional actions. A pause returns the rest before the updateRule.
            int ti = -1
            for (def spec : trigList) {
                ti++
                if (bulkStopAfter) { triggerResults << _rmBulkNotAttempted(bulkStopAfter); continue }
                if ((triggerResults.size() + actionResults.size()) > 0 &&
                        _resumableBudgetExceeded(args?.__reqT0 as Long)) {
                    return _bulkPauseResult(appId, backup, triggerResults, actionResults,
                        trigList.subList(ti, trigList.size()), actList)
                }
                if (!(spec instanceof Map)) {
                    triggerResults << [success: false, error: "addTriggers[${ti}] is not a Map", spec: spec]
                } else {
                    try { triggerResults << _rmAddTrigger(appId, spec as Map) }
                    catch (Exception te) {
                        triggerResults << ([success: false, error: te.message, specCapability: spec.capability] + _rmOutcomeKeys(te))
                        mcpLog("warn", "rm-native", "hub_set_rule: addTriggers[${ti}] (${spec.capability}) failed -- ${te.message}")
                    }
                }
                if (_rmBulkItemBlocks(triggerResults.last())) { bulkStopAfter = "addTriggers[${ti}]".toString(); bulkStopItem = triggerResults.last() }
            }
            // Resolve the valid-rule-id set once for the whole batch (only when a
            // rule-targeting action is present) and thread it to each item.
            def addActionsValidRuleIds = (!bulkStopAfter && _rmSpecListTargetsRule(actList)) ? _rmValidRuleIds() : null
            int ai = -1
            for (def spec : actList) {
                ai++
                if (bulkStopAfter) { actionResults << _rmBulkNotAttempted(bulkStopAfter); continue }
                if ((triggerResults.size() + actionResults.size()) > 0 &&
                        _resumableBudgetExceeded(args?.__reqT0 as Long)) {
                    // Every trigger already processed; only the unprocessed actions remain.
                    return _bulkPauseResult(appId, backup, triggerResults, actionResults,
                        [], actList.subList(ai, actList.size()))
                }
                if (!(spec instanceof Map)) {
                    actionResults << [success: false, error: "addActions[${ai}] is not a Map", spec: spec]
                } else {
                    try { actionResults << _rmAddAction(appId, _rmWithClock(spec as Map, args?.__reqT0 as Long), true, addActionsValidRuleIds) }
                    catch (Exception ae) {
                        actionResults << (_rmAddRefusalResult(ae) + [specCapability: spec.capability, specAction: spec.action])
                        mcpLog("warn", "rm-native", "hub_set_rule: addActions[${ai}] (${spec.capability}/${spec.action}) failed -- ${ae.message}")
                    }
                }
                if (_rmBulkItemBlocks(actionResults.last())) { bulkStopAfter = "addActions[${ai}]".toString(); bulkStopItem = actionResults.last() }
            }
        } catch (Exception e) {
            mcpLogError("rm-native", "addTriggers/addActions bulk failed for app ${appId}", e)
            def bulkResult = _rmBuildUpdateErrorResponse(appId, e.message, backup, "doActPage", e)
            bulkResult.triggerResults = triggerResults
            bulkResult.actionResults = actionResults
            return bulkResult
        }
        if (bulkStopAfter) {
            return _rmBulkStoppedResult(appId, backup, bulkStopAfter, bulkStopItem, [triggers: triggerResults, actions: actionResults])
        }
        // The trailing updateRule runs only when every item completed; a rejection gets the not-live slots.
        try { _rmClickAppButton(appId, "updateRule") }
        catch (Exception updateExc) {
            updateRuleFailed = true
            subscriptionsNotLive = true
            updateRuleError = updateExc.message
            mcpLog("warn", "rm-native", "addTriggers/addActions bulk: trailing updateRule click failed for app ${appId} -- per-item adds committed but subscriptions may not populate: ${updateExc.message}")
        }
        def trigOk = triggerResults.count { it?.success != false }
        def actOk = actionResults.count { it?.success != false }
        def health = _rmCheckRuleHealth(appId)
        def repairHints = []
        if (updateRuleFailed) {
            repairHints << "updateRule click was rejected after the bulk adds committed. The trigger/action rows are baked but the rule will not subscribe to its device events until updateRule fires. Retry hub_set_rule(button='updateRule', confirm=true), or ${_rmRestoreClause(backup)} if the retry also fails."
        }
        // Lift per-item partial into the envelope; the counts use success alone.
        def itemsPartial = (trigOk != triggerResults.size()) || (actOk != actionResults.size()) ||
            triggerResults.any { it instanceof Map && it.partial == true } ||
            actionResults.any { it instanceof Map && it.partial == true }
        return [
            success: trigOk == triggerResults.size() && actOk == actionResults.size() && _rmHealthGatePass(health) && !updateRuleFailed,
            partial: itemsPartial || updateRuleFailed,
            appId: appId,
            backup: backup,
            triggers: triggerResults,
            actions: actionResults,
            health: health,
            updateRuleFailed: updateRuleFailed,
            subscriptionsNotLive: subscriptionsNotLive,
            updateRuleError: updateRuleError,
            repairHints: repairHints,
            note: "Bulk update committed: ${trigOk}/${triggerResults.size()} triggers + ${actOk}/${actionResults.size()} actions; updateRule ${updateRuleFailed ? 'FAILED -- subscriptions may not be live' : 'fired once at the end'}."
        ]
    }

    def pageName = args?.pageName?.toString()?.trim() ?: null
    if (pageName && !pageName.matches(/[A-Za-z0-9_]+/)) {
        throw new IllegalArgumentException("pageName must be alphanumeric/underscore: ${pageName}")
    }

    try {
        def result = [success: true, appId: appId, backup: backup]
        // Records which commit button (if any) a main-page settings write
        // auto-fired, so the subscription-settle check below runs only when
        // updateRule was actually clicked (submitOnChange apps click nothing).
        def implicitCommitButton = null

        if (settingsMap) {
            def config = _rmFetchConfigJson(appId, pageName)
            def schema = _rmCollectInputSchema(config?.configPage)
            // Settings whose key is not on the current page are dropped by the hub (no .type sidecar), e.g.
            // tstate1 before tCapab1/tDev1. Write the known keys and report the rest.
            def knownSettings = [:]
            def unknownSettings = []
            settingsMap.each { k, v ->
                if (schema?.containsKey(k.toString())) {
                    knownSettings.put(k, v)
                } else {
                    unknownSettings << k.toString()
                }
            }
            def isSubPageWrite = (pageName && pageName != "mainPage")
            def subPageApplied = []
            def subPageSkipped = []
            // A page-context click that the page does not render refuses the whole call before any
            // setting is written: a validation refusal comes before every side effect.
            if (button && pageName) _rmRequireRenderedButton(config?.configPage as Map, button, pageName)
            if (knownSettings && isSubPageWrite) {
                // Write sub-page keys one at a time with page context and verify each landed, including
                // the sticky multiple flag; settingsApplied lists only confirmed keys.
                knownSettings.each { k, v ->
                    _rmWriteSettingOnPage(appId, pageName, k.toString(), v, subPageApplied, null, subPageSkipped)
                    _rmVerifySubPageMultipleFlags(appId, pageName, [(k.toString()): v], schema)
                }
            } else if (knownSettings) {
                _rmUpdateAppSettings(appId, knownSettings, schema)
            }
            // updateRule only for mainPage writes: mid-editor it clears moreCond/editCond/editAct and resets the
            // editor. Sub-page flows commit with the page's Done (hasAll, actionDone), then button='updateRule'.
            def isMainPage = (!pageName || pageName == "mainPage")
            // Resolve the per-app-type commit button (default "updateRule";
            // null for submitOnChange apps like Basic Rule, whose inputs have
            // already committed -- clicking updateRule poisons their render).
            def editCommitButton = _resolveCommitButton(config?.app?.appType?.name)
            // An app type the registry does not know gets the RM default, but a page without that
            // button (e.g. a user app) throws MissingMethodException in its appButtonHandler when it
            // is clicked; its settings commit through the closing Done instead.
            if (editCommitButton == "updateRule" && !_appTypeRegistryKnows(config?.app?.appType?.name?.toString()) &&
                    _rmCollectInputSchema(config?.configPage as Map).get("updateRule")?.type != "button") {
                editCommitButton = null
            }
            if (!button && isMainPage && knownSettings && editCommitButton) {
                _rmClickAppButton(appId, editCommitButton)
                implicitCommitButton = editCommitButton
            }
            result.settingsApplied = isSubPageWrite ? subPageApplied : knownSettings.keySet().toList()
            if (isSubPageWrite && subPageSkipped) {
                result.settingsNotLanded = subPageSkipped
                result.partial = true
            }
            if (unknownSettings) {
                result.settingsSkipped = unknownSettings
                def settingWord = (unknownSettings.size() == 1) ? "Setting" : "Settings"
                def beVerb = (unknownSettings.size() == 1) ? "is" : "are"
                def linkedPages = _rmPageHrefs(config?.configPage as Map).collect { it.page }.unique()
                def subPageHint = linkedPages ?
                    " This page links to sub-page(s) ${linkedPages}: an input that lives there needs pageName='<sub-page>' or walkStep (navigate, write, done)." :
                    ""
                result.unknownSettingsWarning = ("${settingWord} ${unknownSettings} ${beVerb} not inputs on page '${pageName ?: 'mainPage'}' and ${beVerb == 'is' ? 'was' : 'were'} not written (the hub would have silently dropped ${unknownSettings.size() == 1 ? 'it' : 'them'})." +
                    subPageHint +
                    " Inputs can also appear only after an earlier input is set: write the prerequisite in its own call first (e.g. on an RM rule's selectTriggers, tstate1 appears only after tCapab1 and tDev1 are written).").toString()
            }
            if (!isMainPage) {
                result.subPageNote = "Sub-page write (pageName='${pageName}') — updateRule NOT auto-fired so the editor state survives. Finish the wizard and call hub_set_rule(button='updateRule') to commit."
            }
        }

        if (button) {
            // A page-context click was checked against the page's buttons before any setting was
            // written (above); without pageName the click is sent as named.
            if (pageName) {
                try { _rmClickRenderedButton(appId, button, args?.stateAttribute?.toString(), pageName) }
                catch (IllegalArgumentException notRendered) {
                    if (settingsMap || _rmOutcomeFlags(notRendered).notSent != true) throw notRendered
                    return [success: false, appId: appId, error: notRendered.message, backup: backup,
                            restoreHint: _rmPreflightRestoreHint(null, backup, "Refused before the click was sent, so nothing needs to be restored.")]
                }
            } else {
                _rmClickAppButton(appId, button, args?.stateAttribute?.toString(), pageName)
            }
            result.buttonClicked = button

            // One hasAll commits the trigger but leaves RM's "Conditional Trigger?" prompt (isCondTrig.<n>).
            // Write it false to close; a second hasAll would allocate a phantom Broken Trigger.
            def buttonIsWizardDone = ["hasAll", "actionDone", "doneCond", "doneAct"].contains(button)
            def isSubPage = pageName && pageName != "mainPage"
            if (buttonIsWizardDone && isSubPage) {
                def afterClickConfig
                try { afterClickConfig = _rmFetchConfigJson(appId, pageName) }
                catch (Exception verifyExc) {
                    afterClickConfig = null
                    mcpLog("warn", "rm-native", "walkStep: post-wizard-Done fetch on ${pageName} failed for app ${appId} (${verifyExc.message}) -- residual condTrig prompt check skipped, may leave phantom trigger N+1")
                }
                def residualCondTrigName = _rmFindResidualCondTrig(afterClickConfig?.configPage)
                if (residualCondTrigName) {
                    mcpLog("info", "rm-native", "Wizard-Done click '${button}' left ${residualCondTrigName} prompt on app ${appId} -- auto-finalizing with =false to avoid phantom trigger")
                    try {
                        def finalizeSchema = _rmCollectInputSchema(afterClickConfig?.configPage)
                        _rmUpdateAppSettings(appId, [(residualCondTrigName): false], finalizeSchema)
                        result.wizardDoneAutoRetry = "OK after finalize (set ${residualCondTrigName}=false to clear residual Conditional? prompt)"
                    } catch (Exception finalizeErr) {
                        result.wizardDoneAutoRetry = "WARN: failed to auto-finalize ${residualCondTrigName} (${finalizeErr.message}) — trigger committed but the residual Conditional? prompt is still open; call hub_set_rule(settings={${residualCondTrigName}: false}, pageName='${pageName}') to clear it manually"
                    }
                } else if (_rmHasWizardScaffold(afterClickConfig?.configPage)) {
                    result.wizardDoneAutoRetry = "WARN: wizard scaffold still present after click — caller probably hasn't filled all required fields (capability, device, state); inspect hub_get_app_config(pageName='${pageName}')"
                } else {
                    result.wizardDoneAutoRetry = "OK"
                }
            }
        }

        // Final verification: the config page's error field is null on
        // healthy apps. If any non-null error appears here, the write
        // poisoned something and the caller should restore from backup.
        def finalConfig = _rmFetchConfigJson(appId)
        def err = finalConfig?.configPage?.error
        if (err) {
            result.warning = "App has a rendering error after update: ${err}"
            result.restoreHint = _rmRestoreBaselineHint(backup)
        }
        result.configPageError = err

        // After updateRule, RM's initialize() should resubscribe; the first one after a new trigger can leave
        // zero subscriptions for about a minute, so warn and retry.
        def clickedUpdateRule = (button == "updateRule") || (implicitCommitButton == "updateRule")
        if (clickedUpdateRule) {
            def settleStatus = _rmCheckSubscriptionSettle(appId)
            if (settleStatus?.unsettled && settleStatus.suppressedBy) {
                // A retry cannot add subscriptions RM is withholding, and the rule is not incomplete.
                result.subscriptionSettle = _rmSuppressedSettleText(settleStatus.suppressedBy)
            } else if (settleStatus?.unsettled) {
                mcpLog("info", "rm-native", "updateRule subscription settle lag on app ${appId} -- retrying")
                _rmClickAppButton(appId, "updateRule")
                def firstStatus = settleStatus
                settleStatus = _rmCheckSubscriptionSettle(appId)
                def trigCount = (settleStatus ?: firstStatus).triggerCount
                def trigWord = trigCount == 1 ? "trigger" : "triggers"
                // Discriminate on count, not on stringified word: trigWord=="trigger"
                // could in theory drift if the assignment above changed, and at count==0
                // ("triggers", plural by default) the "triggers are" verb is correct anyway.
                def trigVerb = (trigCount == 1) ? "trigger is" : "triggers are"
                result.subscriptionSettle = (settleStatus?.unsettled && settleStatus.suppressedBy) ?
                    _rmSuppressedSettleText(settleStatus.suppressedBy) :
                    settleStatus == null ?
                    "UNKNOWN: updateRule was retried but the follow-up statusJson read failed, so whether the ${trigCount == 1 ? 'trigger' : 'triggers'} subscribed is unverified. Inspect statusJson.eventSubscriptions before relying on the rule." :
                    settleStatus.unsettled ?
                    "WARN: rule has ${trigCount} ${trigWord} but eventSubscriptions=0 after two updateRule clicks. The ${trigVerb} likely incomplete (missing tstate, attached-condition, or other required field) OR a hub timing race. Inspect statusJson.eventSubscriptions; if still empty, call hub_set_rule(button='updateRule') again or check the wizard for missing fields." :
                    "OK after auto-retry"
            } else if (settleStatus != null) {
                result.subscriptionSettle = "OK"
            }
        }
        // Always attach health to settings/button mutations too — the LLM
        // sees broken state immediately without having to call hub_get_rule_health.
        result.health = _rmCheckRuleHealth(appId)
        if (!_rmHealthGatePass(result.health)) result.success = false
        // Final mainPage Done clears lingering editor markers. A miss is reported, since for commitButton:null
        // apps this is the only commit.
        def editDone = null
        try { editDone = _rmSubmitMainPageDone(appId) }
        catch (Exception doneExc) { mcpLog("warn", "rm-native", "hub_set_rule: trailing mainPage Done click failed for app ${appId}: ${doneExc.message} -- in-flight state markers may linger and corrupt subsequent edits") }
        if (editDone?.done == false) {
            result.mainPageDoneFailed = true
            result.mainPageDoneError = editDone.reason
            // Fail only for commitButton:null apps (Basic Rule, Button Controller), where Done is the only commit;
            // for RM the edit already committed, so a missed Done is a repair hint. _resolveCommitButton fails safe.
            if (editDone.uiBlocked == true || _resolveCommitButton(finalConfig?.app?.appType?.name?.toString()) == null) {
                result.success = false
            }
            result.repairHints = (result.repairHints ?: []) + [_rmMainPageDoneRepairHint(appId, editDone)]
        }
        return result
    } catch (Exception e) {
        def msg = e.message ?: e.toString()
        mcpLogError("rm-native", "hub_set_rule failed for ${appId}: ${msg}", e)
        return _rmBuildUpdateErrorResponse(appId, msg, backup, "doActPage", e)
    }
    }
    return _impl()
}

// hub_delete_native_app: snapshot, then delete. Soft delete by default (refused with child apps or
// devices); force=true uses forcedelete/quiet, as RM does.
def toolDeleteNativeApp(args) {
    requireDestructiveConfirm(args?.confirm as Boolean)
    if (args?.appId == null) throw new IllegalArgumentException("appId is required")
    def appId = normalizeRuleId(args.appId)
    _requireUnprotectedAppDeletion(appId)
    def force = args?.force == true

    def backup
    try { backup = _rmBackupRuleSnapshot(appId, force ? "pre-forcedelete" : "pre-delete") }
    catch (Exception backupErr) {
        Map unavailable = _itemBackupIndexUnavailableResult(backupErr)
        if (unavailable == null) throw backupErr
        return unavailable + [appId: appId]
    }

    try {
        if (force) {
            _rmForceDeleteApp(appId)
            _rmDropPredClearPending(appId)   // rule gone -> drop any deferred predCapabs-clear flag
            return [
                success: true,
                appId: appId,
                mode: "forcedelete",
                backup: backup,
                note: "App force-deleted. To restore, call hub_restore_backup with backupKey='${backup.backupKey}' (will recreate an empty app and apply saved settings).${_rmRetentionSuffix(backup)}"
            ]
        } else {
            def resp = _rmSoftDeleteApp(appId)
            // Soft delete returns {success: bool, message: ...} when it
            // parses. Surface the hub's own message so the user learns
            // why (e.g. "cannot delete — app has child devices").
            if (resp?.success == false) {
                return [
                    success: false,
                    appId: appId,
                    mode: "delete",
                    hubMessage: resp?.message,
                    backup: backup,
                    note: "Soft delete refused by hub. Pass force=true to override (app's children will also be removed)."
                ]
            }
            _rmDropPredClearPending(appId)   // rule gone -> drop any deferred predCapabs-clear flag
            return [
                success: true,
                appId: appId,
                mode: "delete",
                backup: backup,
                note: "App deleted."
            ]
        }
    } catch (Exception e) {
        return [
            success: false,
            appId: appId,
            error: e.message ?: e.toString(),
            backup: backup
        ]
    }
}

// hub_get_rule_health — public tool wrapper around _rmCheckRuleHealth.
// Read-only; doesn't take a backup. Returns the same {ok, issues, ...}
// shape that hub_set_rule embeds as `health` on every response.
def toolCheckRuleHealth(args) {
    if (args?.appId == null) throw new IllegalArgumentException("appId is required")
    def appId = normalizeRuleId(args.appId)
    def source = (args?.source != null ? args.source.toString() : "auto")
    if (!(source in ["auto", "ruleBuilderJson", "configPage"])) {
        throw new IllegalArgumentException("source must be one of: auto (default), ruleBuilderJson, configPage")
    }
    def result = _rmCheckRuleHealth(appId, source)
    def status = null
    try {
        status = _rmFetchStatusJson(appId)
        // A readable statusJson with an ABSENT section means zero live entries -- a
        // schedule-only rule carries no eventSubscriptions list at all (and a stopped
        // rule drops both). null is reserved for the fetch itself failing.
        result.eventSubscriptionCount = (status?.eventSubscriptions instanceof List) ? status.eventSubscriptions.size() : 0
        result.scheduledJobCount = (status?.scheduledJobs instanceof List) ? status.scheduledJobs.size() : 0
    } catch (Exception statusErr) {
        result.eventSubscriptionCount = null
        result.scheduledJobCount = null
        // Half-checked marker, same contract as the two source reads: null counts with no
        // recorded reason read as "this rule has none", which is a different fact.
        def checkErrors = (result.checkErrors instanceof List) ? result.checkErrors : []
        checkErrors << "statusJson: ${statusErr.message ?: statusErr.toString()}".toString()
        result.checkErrors = checkErrors
    }
    // A plain suffix can be the user's name, even when the rule really is paused.
    // Only real markup proves which text is a runtime decoration; typed '<' is escaped.
    def rawLabel = result.label?.toString()
    Boolean pausedVerdict = result.paused instanceof Boolean ? result.paused :
        (status != null ? _readAppStateBoolean(status, "paused", null) : null)
    Boolean stoppedVerdict = status != null ? _readAppStateBoolean(status, "stopped", null) : null
    boolean visual = result.ruleFormat in ["vrb-classic", "vrb-graph"]
    while (!visual && rawLabel != null) {
        def decoration = rawLabel =~ /<[^>]+>\s*\((Paused|Stopped)\)\s*(?:<\/[^>]+>\s*)*$/
        if (!decoration.find()) break
        if (decoration.group(1) == "Stopped") {
            if (stoppedVerdict == null) stoppedVerdict = true
        } else if (pausedVerdict == null) pausedVerdict = true
        rawLabel = rawLabel.substring(0, decoration.start()).trim()
    }
    // Explicit state outranks older display markup; unreadable state still uses it.
    // A readable status with no stopped flag means never stopped. RM's compiled state
    // settles paused (absent = not paused); without it an absent flag stays unknown.
    if (stoppedVerdict == null && status != null) stoppedVerdict = false
    result.stopped = stoppedVerdict
    result.paused = pausedVerdict
    if (!visual && rawLabel != null) result.label = rawLabel.replaceAll(/<[^>]+>/, "").trim()
    return result
}

// hub_list_rule_local_variables -- read a rule's local variables from its statusJson appState.
// Pure read; no wizard. Returns {appId, localVariables:[{name, type, value}], total}; an absent
// allLocalVars map means the rule has no locals (an empty list, not an error).
def toolListRuleLocalVariables(args) {
    if (args?.appId == null) throw new IllegalArgumentException("appId is required")
    def appId = normalizeRuleId(args.appId)
    // _rmReadLocalVarsMap throws nothing (it wraps the status read); a read failure
    // surfaces as ok:false. Re-raise it here so the pure-read tool reports the read
    // error rather than silently returning an empty list that looks like "no locals".
    def lvRead = _rmReadLocalVarsMap(appId)
    if (!lvRead.ok) {
        throw new IllegalStateException("hub_list_rule_local_variables: could not read rule ${appId} status (${lvRead.error}).")
    }
    def localVariables = []
    lvRead.vars.each { lvName, lvMeta ->
        def live = lvRead.live?.get(lvName?.toString())
        localVariables << [
            name: lvName?.toString(),
            type: (lvMeta instanceof Map ? lvMeta?.type?.toString() : null),
            value: (live instanceof Map && live.containsKey("value")) ? live.value : (lvMeta instanceof Map ? lvMeta?.value : null)
        ]
    }
    localVariables = localVariables.sort { it.name }
    return [appId: appId, localVariables: localVariables, total: localVariables.size()]
}

// ==================== PER-TOOL METADATA (issue #209) ====================

def _readOnlyToolNames_partNativeRM() {
    // Read-only classification for this library's tools (issue #209: per-tool metadata lives
    // with the tool), contributed to the app's getReadOnlyToolNames() aggregator.
    return [
        // Native rules (read) -- hub_get_rule_health inspects only;
        // hub_list_rule_local_variables reads the rule's statusJson only.
        // (hub_export_native_app -- in McpAppClonerLib -- instantiates a cloner app + persists, so it stays write.)
        "hub_list_rules", "hub_get_rule_health", "hub_list_rule_local_variables"
    ]
}

def _idempotentWriteToolNames_partNativeRM() {
    // Retry-safe writes (MCP idempotentHint) for this library's tools -- contributed to the
    // app's getIdempotentWriteToolNames() aggregator; see the classification rules there.
    return [
        // Native rules / classic apps
        "hub_set_rule_paused", "hub_set_rule_private_boolean", "hub_set_app_disabled"
    ]
}

def _toolDisplayMeta_partNativeRM() {
    // Human-facing title/summary per tool -- merged into the app's getToolDisplayMeta() aggregator.
    return [
        // Native Rule Machine + classic apps
        hub_list_rules: [title: "List Rule Machine Rules", summary: "List all Rule Machine rules."],
        hub_call_rule: [title: "Trigger Rule Machine Rule", summary: "Trigger one or more Rule Machine rules, run their actions, or stop them."],
        hub_set_rule_paused: [title: "Pause or Resume Rule", summary: "Pause or resume one or more Rule Machine rules in one call."],
        hub_set_rule_private_boolean: [title: "Set Rule Private Boolean", summary: "Set the private boolean of one or more Rule Machine rules."],
        hub_set_rule: [title: "Author Rule Machine Rule", summary: "Create or edit a Rule Machine rule."],
        hub_get_rule_health: [title: "Get Rule Health", summary: "Read-only health check on any installed app."],
        hub_list_rule_local_variables: [title: "List Rule Local Variables", summary: "Read-only list of a Rule Machine rule's local variables."],
        hub_set_native_app: [title: "Create or Edit Native App", summary: "Create or edit a classic native app (Room Lighting, Notifier, etc.)."],
        hub_delete_native_app: [title: "Delete Native App", summary: "Delete a classic native app (auto-snapshot first)."],
        hub_set_app_disabled: [title: "Enable or Disable App", summary: "Enable or disable any installed app without deleting it (reversible)."]
    ]
}
