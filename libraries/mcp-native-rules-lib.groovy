library(name: "McpNativeRulesLib", namespace: "mcp", author: "kingpanther13", description: "Native Rule Machine + classic-app tool implementations (hub_list_rules/hub_call_rule/hub_set_rule_paused/hub_set_rule_private_boolean/hub_set_rule/hub_set_native_app/hub_delete_native_app/hub_get_rule_health) -- the full RM 5.1 wizard authoring surface plus native-app CRUD -- for the MCP Rule Server; included by the main app. Gateway entries, dispatch cases, and the shared classic-dynamicPage wizard primitives (used by other libraries) stay in the app; the RM-exclusive tool definitions, implementations, domain helpers, and per-tool metadata live here.")

def _getAllToolDefinitions_partNativeRM() {
    return [
        // Rule Machine Integration (read + trigger + pause/resume only — platform blocks CRUD)
        [
            name: "hub_list_rules",
            description: "List all Rule Machine rules (RM 4.x + 5.x, deduplicated by id). Returns rule IDs and labels. Requires the Read master. Call `hub_get_tool_guide(section='builtin_app_tools')` for details and platform limitations on RM rule internals.",
            inputSchema: [
                type: "object",
                properties: [
                    cursor: [type: "string", description: "Opt-in pagination cursor. Omit for unbounded; pass \"\" for the first page, iterate nextCursor (page size 50)."]
                ]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    rules: [type: "array", description: "Rule Machine rules", items: [type: "object", properties: [
                        id: [type: "integer", description: "Rule app ID"],
                        label: [type: "string", description: "Rule label"],
                        name: [type: "string", description: "Rule name"],
                        type: [type: "string", description: "Rule type, or null"],
                        rmVersion: [type: "string", description: "RM version, 4.x or 5.x"]
                    ]]],
                    count: [type: "integer", description: "Rules returned"],
                    total: [type: "integer", description: "Total rules; paginated mode only"],
                    nextCursor: [type: "string", description: "Cursor; present when more remain"],
                    ghostsFiltered: [type: "array", description: "RMUtils cache ghost IDs dropped", items: [type: "integer"]],
                    ghostNote: [type: "string", description: "Present when ghosts were filtered"],
                    note: [type: "string", description: "Present when RM not detected or informational"],
                    warning: [type: "string", description: "Present on partial RMUtils failure"],
                    success: [type: "boolean", description: "Present only on failure/partial paths"],
                    error: [type: "string", description: "Present on hard failure"]
                ]
            ]
        ],
        [
            name: "hub_call_rule",
            description: "Trigger a Rule Machine rule. Not destructive (invokes existing user-configured automation). Requires the Write master. Call `hub_get_tool_guide(section='builtin_app_tools')` for action semantics.",
            inputSchema: [
                type: "object",
                properties: [
                    ruleId: [type: "integer", description: "Rule ID from hub_list_rules"],
                    action: [type: "string", enum: ["rule", "actions", "stop", "start"], description: "Which RM action to invoke. Default: rule.[[FLAT_TRIM]] 'rule'=runRule (re-evaluate the rule's conditions, then run the matching true/false action set); 'actions'=runRuleAct (run the action list directly, skipping condition evaluation); 'stop'=halt the rule's in-progress actions; 'start'=re-enable a stopped rule (also resets its private boolean). stop/start toggle the stopRule UI button, not RMUtils (RMUtils has no startRule verb).[[/FLAT_TRIM]]"]
                ],
                required: ["ruleId"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the action succeeded"],
                    ruleId: [type: "integer", description: "Rule app ID acted on"],
                    rmAction: [type: "string", description: "RM action performed (runRule, runRuleAct, stopRule toggle, noop)"],
                    fallback: [type: "string", description: "Present on old-firmware 3-arg fallback"],
                    note: [type: "string", description: "Present on no-op or informational"],
                    error: [type: "string", description: "Present on failure"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_set_rule_paused",
            description: "Pause or resume a Rule Machine rule (paused rules don't fire on triggers). value=true pauses, value=false resumes (idempotent on the hub). Requires the Write master.",
            inputSchema: [
                type: "object",
                properties: [
                    ruleId: [type: "integer", description: "Rule ID from hub_list_rules"],
                    value: [type: "boolean", description: "true = pause the rule; false = resume it."]
                ],
                required: ["ruleId", "value"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the pause/resume succeeded"],
                    ruleId: [type: "integer", description: "Rule app ID"],
                    paused: [type: "boolean", description: "Applied pause state (true=paused, false=resumed)"],
                    rmAction: [type: "string", description: "pauseRule or resumeRule"],
                    fallback: [type: "string", description: "Present on old-firmware 3-arg fallback"],
                    error: [type: "string", description: "Present on failure"],
                    note: [type: "string", description: "Present on failure"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_set_rule_private_boolean",
            description: "Set a Rule Machine rule's private boolean to true or false (strict: accepts Boolean or lowercase string 'true'/'false' only). Requires the Write master. Call `hub_get_tool_guide(section='builtin_app_tools')` for pattern and coercion policy.",
            inputSchema: [
                type: "object",
                properties: [
                    ruleId: [type: "integer", description: "Rule ID from hub_list_rules"],
                    value: [type: "boolean", description: "true sets the boolean to TRUE, false sets it to FALSE"]
                ],
                required: ["ruleId", "value"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the set succeeded"],
                    ruleId: [type: "integer", description: "Rule app ID"],
                    rmAction: [type: "string", description: "setRuleBooleanTrue or setRuleBooleanFalse"],
                    fallback: [type: "string", description: "Present on old-firmware 3-arg fallback"],
                    error: [type: "string", description: "Present on failure"],
                    note: [type: "string", description: "Present on failure"]
                ],
                required: ["success"]
            ]
        ],
        // Native classic-app CRUD (hub admin-layer, bypasses SmartApp parent-type check).
        // Generic across native automation app types — RM is the first registered type;
        // Room Lighting / Button Controllers / Basic Rules / Notifier / Groups+Scenes work
        // for edit + delete today (any classic-app appId), and join create as their entries
        // get added to _appTypeRegistry().
        [
            name: "hub_set_native_app",
            description: """Create OR edit a classic native automation app on the hub — one generic upsert tool for any classic SmartApp instance (Room Lighting, Button Controller, Notifier, Group, Scene, Visual Rule, etc.), addressed by appId.

Omit appId to CREATE a new app of `appType` (provide name). Provide appId to EDIT an existing app: write its config-page inputs via `settings`, and/or click a page-transition button via `button`. Discover input names and buttons via hub_get_app_config first.

[[FLAT_TRIM]]The shell is created via the hub's admin-layer createchild endpoint, which bypasses the SmartApp parent-type check that blocks third-party addChildApp('hubitat', ...) calls. The new app appears under Apps / Automations exactly as if created via the native UI. appType is enum-driven by _appTypeRegistry().[[/FLAT_TRIM]]

This is the GENERIC tool for any classic SmartApp.[[FLAT_TRIM]] Button Rules can't be created standalone (use `buttonRule`); `walkStep` and the RM authoring shortcuts also work here on EDIT (appId present) for RM-wire-format classic apps — the create arm honors none of them and rejects rather than drops. Rule Machine RULES belong in hub_set_rule. Separate from the MCP custom rule engine (hub_*_custom_rule).[[/FLAT_TRIM]]

[[FLAT_TRIM]]BEFORE EVERY edit-write a full snapshot is saved to File Manager; the response carries backup.backupKey for hub_restore_backup (in hub_manage_code) if a write goes wrong.[[/FLAT_TRIM]]

Requires the Write master + confirm=true + recent hub backup.""",
            inputSchema: [
                type: "object",
                properties: [
                    appId: [type: "integer", description: "Installed-app id of an existing classic app (from hub_list_apps with scope='instances'). OMIT to CREATE a new app of `appType` (then `name` is required); PROVIDE to EDIT an existing app's settings/button."],
                    appType: [type: "string", enum: ["rule_machine", "button_controller", "groups_scenes", "notifier", "basic_rule"], description: "Native app class to CREATE (appId omitted). Default: rule_machine. Visual Rules are NOT created here -- use hub_set_visual_rule.[[FLAT_TRIM]] Enum is driven by _appTypeRegistry(); add types there. For full RM rule authoring use hub_set_rule. Button Rules are NOT an appType -- use the buttonRule param.[[/FLAT_TRIM]]"],
                    name: [type: "string", description: "Label for the new app[[FLAT_TRIM]] (shown in the hub's app list)[[/FLAT_TRIM]]. Required on CREATE (when appId is omitted); ignored when appId is provided."],
                    settings: [type: "object", description: "Map {inputName: value} to write to the app's current config page: scalars for bool/enum/text/number inputs, List of device IDs for capability.* multi-device inputs.[[FLAT_TRIM]] The multiple=true 3-field contract (settings[name]=csv + name.type=capability.X + name.multiple=true) is emitted automatically and post-write verified with one auto-retry.[[/FLAT_TRIM]] Discover input names via hub_get_app_config."],
                    button: [type: "string", description: "Page-transition button name to click (discover via hub_get_app_config)."],
                    pageName: [type: "string", description: "Optional sub-page for schema introspection + settings POST."],
                    stateAttribute: [type: "string", description: "Optional state attribute value for the button click."],
                    buttonRule: [type: "object", description: "Create a Button Rule under an existing Button Controller.[[FLAT_TRIM]] Routes through the controller's add-button flow; returns buttonRuleId with the Button trigger auto-seeded — author its actions via hub_set_rule(appId=buttonRuleId, addAction=...). The controller must already have a button device assigned.[[/FLAT_TRIM]]", properties: [controllerId: [type: "integer", description: "Button Controller-5.1 appId"], buttonNumber: [type: "integer", description: "button number (>=1)"], event: [type: "string", enum: ["pushed", "held", "doubleTapped", "released"]]]],
                    walkStep: [type: "object", description: "Generic classic-dynamicPage walker for stateful multi-page classic apps -- introspect/write/click/navigate/done one step per call, or operation='drive' with steps=[...] to run the whole sequence in one call.[[FLAT_TRIM]] Same shape as hub_set_rule's walkStep (see hub_get_tool_guide(section='set_rule_reference')). For Rule Machine RULES use hub_set_rule.[[/FLAT_TRIM]]"],
                    confirm: [type: "boolean", description: "Must be true. Safety gate for Write master operations."]
                ],
                required: ["confirm"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the create/edit succeeded"],
                    appId: [type: "integer", description: "App ID created or edited"],
                    buttonRuleId: [type: "integer", description: "buttonRule: appId of the created Button Rule (author its actions via hub_set_rule)"],
                    controllerId: [type: "integer", description: "buttonRule: parent Button Controller appId"],
                    appType: [type: "string", description: "create: app type created"],
                    name: [type: "string", description: "create: requested app label"],
                    labelApplied: [type: "boolean", description: "create: true if the requested name became the display label (verified for rule_machine only; false for partial-support appTypes -- see note)"],
                    partialTriggers: [type: "array", description: "create: indices of bundled triggers that failed to fully bake (always empty for this generic tool -- no trigger sugar)", items: [type: "integer"]],
                    partialActions: [type: "array", description: "create: indices of bundled actions that failed to fully bake (always empty for this generic tool -- no action sugar)", items: [type: "integer"]],
                    parentAppId: [type: "integer", description: "create: parent app ID"],
                    statusSummary: [type: "object", description: "create: eventSubscriptions and scheduledJobs counts"],
                    backup: [type: "object", description: "edit: pre-write backup metadata (backupKey, type, fileName, ...)"],
                    settingsApplied: [type: "array", description: "edit: settings applied", items: [type: "string"]],
                    settingsSkipped: [type: "array", description: "edit: settings skipped", items: [type: "string"]],
                    unknownSettingsWarning: [type: "string", description: "edit: present when unknown settings supplied"],
                    buttonClicked: [type: "string", description: "edit: button clicked"],
                    subPageNote: [type: "string", description: "edit: sub-page note"],
                    health: [type: "object", description: "App health summary"],
                    partial: [type: "boolean", description: "Partial-success flag"],
                    repairHints: [type: "array", description: "Suggested fixes", items: [type: "string"]],
                    note: [type: "string", description: "Human-readable result"],
                    error: [type: "string", description: "Present on failure"],
                    orphanCleanup: [type: "string", description: "create: present when a failed create's half-built shell was cleaned up"]
                ]
            ]
        ],
        [
            name: "hub_set_rule",
            description: """Create OR edit a Hubitat Rule Machine rule (RM 5.1) — one upsert tool for the full rule-authoring surface (triggers, actions, conditions, required expressions, local variables). Omit appId to CREATE a new rule (provide name; optionally bundle addTriggers / addActions / addTrigger / addAction / addRequiredExpression to populate it in the same call -- other edit-only ops like replaceActions/walkStep require an existing appId). Provide appId to EDIT an existing rule.

Prefer the high-level structured shortcuts, each of which orchestrates the full RM 5.1 wizard in one call.[[FLAT_TRIM]] The shortcuts: addTrigger / addAction / addRequiredExpression / replaceRequiredExpression; bulk addTriggers / addActions / replaceActions; and removeAction / clearActions / moveAction / removeTrigger / modifyTrigger / addLocalVariable / removeLocalVariable / patches. For a capability the shortcuts don't cover, walkStep drives one wizard page at a time, or write page inputs via settings and click page-transition buttons via button directly (raw mode).[[/FLAT_TRIM]]

BEFORE EVERY edit-write a full snapshot (configure/json + statusJson) is saved to File Manager; the response carries backup.backupKey for hub_restore_backup (in hub_manage_code) if a write goes wrong. Partial-success on CREATE: the tool always returns the new appId even if a bundled trigger/action only partially bakes — inspect partial / partialTriggers / partialActions / repairHints (full create + repair protocol: hub_get_tool_guide(section='set_rule_create_reference')).

For NON-RM classic apps (Room Lighting, Button Controller, Notifier, Groups+Scenes, Visual Rule, etc.) use hub_set_native_app instead — this tool is RM-only. Completely separate from the MCP custom rule engine (hub_*_custom_rule).
[[FLAT_TRIM]]
Full capability reference — trigger/action/expression families, extended condition shapes, the raw settings/button wizard flow, and walkStep — is one call away: pass guide:true to get it back inline (no separate tool call), or see hub_get_tool_guide(section='set_rule_reference'). Pass {discover:true} on addTrigger/addAction for the live machine-readable schema.
[[/FLAT_TRIM]]
Requires the Write master + confirm=true + recent hub backup.""",
            inputSchema: [
                type: "object",
                properties: [
                    appId: [type: "integer", description: "RM rule ID (the rule's installed-app id). OMIT to CREATE a new rule (then `name` is required); PROVIDE to EDIT an existing rule."],
                    name: [type: "string", description: "Label for the new rule (shown in Hubitat's Rule Machine app list). Required on CREATE (when appId is omitted); ignored when appId is provided."],
                    settings: [type: "object", description: "Map {inputName: value}: scalars for bool/enum/text/number inputs, List of device IDs for capability.* multi-device inputs.[[FLAT_TRIM]] The multiple=true 3-field contract (settings[name]=csv + name.type=capability.X + name.multiple=true) is emitted automatically and post-write verified with one auto-retry — you don't manage it.[[/FLAT_TRIM]]"],
                    button: [type: "string", description: "Page-transition button name[[FLAT_TRIM]] (e.g. updateRule, editCond, pausRule, refreshActions for RM; analogous buttons for other app types — discover them via hub_get_app_config)[[/FLAT_TRIM]]."],
                    pageName: [type: "string", description: "Optional sub-page for schema introspection + settings POST."],
                    stateAttribute: [type: "string", description: "Optional state attribute value for the button click (e.g. trigger/action index for RM editCond/editAct)."],
                    addTrigger: [
                        type: "object",
                        description: """Add a Rule Machine TRIGGER via the high-level structured API. DISCRIMINATOR: use `capability` (NOT `type`) -- `{type: 'switch', ...}` is rejected with "addTrigger.capability is required". Pass `addTrigger: {discover: true}` for the live per-capability schema, or `hub_get_tool_guide(section='set_rule_reference')` for the `addTrigger` families reference. The tool orchestrates the full RM 5.1 wizard internally and fires updateRule so subscriptions populate; returns result.triggerIndex. Bulk addTriggers[] fires updateRule once at the end.[[FLAT_TRIM]]

Capability families (NAMES; full per-field specs via discover:true or the guide): Device-state (Switch / Motion / Contact / Lock / Garage / Door / Valve / Window Shade / Presence / Power source), Numeric (Temperature / Humidity / Battery / Illuminance / Power / Energy / CO2 / Dimmer / Thermostat setpoints), Button, Custom Attribute, Time / Sunrise / Sunset, Mode, Periodic Schedule. Modifiers: andStays, allOfThese.

Optional on every spec:
  - conditional — sets isCondTrig.<N>; combine with `condition` to bind the conditional-trigger gate in one call, or set alone to leave it empty.
  - condition — Map {capability, deviceIds?, variable?, compareToVariable?, state?, comparator?, value?, attribute?, not?, rawSettings?} (selectTriggers supports a NARROWER set than addRequiredExpression/addAction — see guide).
  - rawSettings — escape hatch {fieldName: value} using the `@N` token for the auto-assigned index, e.g. {'xVar@N': 'myVar'} writes `xVar1` when the trigger lands at index 1.

PARTIAL-SUCCESS: success:true can come with partial:true — check partial/repairHints; on a rejected trailing updateRule the trigger is written but not subscribed (subscriptionsNotLive:true), retry hub_set_rule(button='updateRule', confirm=true). Full protocol: guide:true or hub_get_tool_guide(section='set_rule_reference').[[/FLAT_TRIM]]"""
                    ],
                    addTriggers: [
                        type: "array",
                        description: "Bulk-add multiple triggers in one call (each item the same shape as addTrigger). updateRule fires ONCE at the end, so subscriptions populate from a fully-loaded rule; pairs with addActions to build a whole rule in one call.[[FLAT_TRIM]] Empty/omitted falls back to single addTrigger. On a rejected post-bulk updateRule: subscriptionsNotLive:true — retry hub_set_rule(button='updateRule', confirm=true).[[/FLAT_TRIM]]",
                        items: [type: "object"]
                    ],
                    addRequiredExpression: [
                        type: "object",
                        description: """Add a Rule Machine 5.1 Required Expression (RM's pre-trigger gate conditioning whether the rule may fire) via the high-level structured API. The tool orchestrates STPage's full wizard internally and fires updateRule; returns result.conditionIndices.

Spec: {conditions:[{capability, deviceIds?, state?, comparator?, value?, attribute?, not?, rawSettings?}, ...], operator:'AND'|'OR'|'XOR' OR operators:[...] (one per gap, for mixed expressions; equal precedence, left-to-right)}. comparator is REQUIRED with attribute for Custom Attribute, and with variable+value for Variable (ASCII !=/<>/== auto-map to RM glyphs). Full reference: hub_get_tool_guide(section='set_rule_reference').[[FLAT_TRIM]] For the full STPage capability list, all extended per-capability shapes (Mode, Between two times, Variable incl. compareToVariable, device-relative compareToDevice, nested subExpression) and the partial-success/failure contract, pass guide:true or hub_get_tool_guide(section='set_rule_reference').

PARTIAL-SUCCESS: partial:true / expressionNotLive / wizardStuck can accompany success — check repairHints; on a rejected trailing updateRule the expression is committed but not live (expressionNotLive:true), retry hub_set_rule(button='updateRule', confirm=true). If wizardStuck:true call hub_set_rule(button='cancelCapab', pageName='STPage', confirm=true) first (restoreHint has the exact command).[[/FLAT_TRIM]]"""
                    ],
                    replaceRequiredExpression: [
                        type: "object",
                        description: """Replace an existing RM 5.1 Required Expression in place (same appId, no clone) -- whole-expression replace. Same spec as addRequiredExpression ({conditions:[...], operator|operators}); use addRequiredExpression to ADD one when the rule has none (this refuses with requiredExpressionMissing if there is nothing to replace). The delete is destructive, but the entire spec is validated BEFORE it (a malformed spec leaves the existing expression intact) and any failure after the delete -- including a rejected trailing updateRule -- auto-restores the pre-op backup (requiredExpressionRestored): a failed replace is always reported, never silent data loss.[[FLAT_TRIM]] Per-condition shapes and the full partial-success/failure contract: guide:true or hub_get_tool_guide(section='set_rule_reference').[[/FLAT_TRIM]]"""
                    ],

                    addActions: [
                        type: "array",
                        description: "Bulk-add multiple actions in one call (each item the same shape as addAction; actions self-bake via doActPage). updateRule fires ONCE at the end. Pairs with addTriggers to add many triggers + actions in one call.[[FLAT_TRIM]] On a rejected post-bulk updateRule: subscriptionsNotLive:true — retry hub_set_rule(button='updateRule', confirm=true).[[/FLAT_TRIM]]",
                        items: [type: "object"]
                    ],
                    addLocalVariable: [
                        type: "object",
                        description: "Add a local variable. Spec: {name, type, value}.[[FLAT_TRIM]] type is one of Number/Decimal/String/Boolean/DateTime (case-insensitive) and value matches the type (DateTime wants an ISO timestamp). Used as %name% in actions/expressions; stored in state.allLocalVars (verify via statusJson appState.allLocalVars, not appSettings). Returns success=false with repair hints if a value/type mismatch makes RM silently reject.[[/FLAT_TRIM]]"
                    ],
                    removeLocalVariable: [
                        type: "object",
                        description: "Remove a local variable. Spec: {name}.[[FLAT_TRIM]] Drives RM's deleteGV/delConfirm wizard, then verifies the variable left state.allLocalVars and re-checks rule health. An unknown name is rejected with the available local-variable list. WARNING: RM does NOT reliably refuse to delete a referenced local -- deleting a local that an action still references usually SUCCEEDS at removing it and leaves the referencing action Broken. In that case this returns success=false (deleted=true) with a specific error + a repairHint to restore the pre-delete backup (backupKey in the response) or remove the references first. A delete that fails to verify at all returns success=false with partial=true + repair hints. To read current locals use hub_list_rule_local_variables (in hub_read_rules).[[/FLAT_TRIM]]"
                    ],
                    patches: [
                        type: "array",
                        description: "Atomic multi-mutation: each item is a sub-spec with ONE operation key (settings, button, addTrigger(s), addAction(s), addRequiredExpression, replaceRequiredExpression, addLocalVariable, removeLocalVariable, removeAction, clearActions, replaceActions, moveAction). Operations run sequentially; updateRule fires ONCE at the end.[[FLAT_TRIM]] Per-op outcome in patches[i]; an individual op failing doesn't abort the rest.[[/FLAT_TRIM]]",
                        items: [type: "object"]
                    ],
                    removeAction: [
                        type: "object",
                        description: "Delete a single action by its index. Pass {index: <N>}.[[FLAT_TRIM]] RM preserves remaining indices (no renumbering on delete). updateRule fires after the deletion.[[/FLAT_TRIM]]"
                    ],
                    clearActions: [
                        type: "boolean",
                        description: "Pass true to delete every action (highest index first) -- the 'wipe and rebuild' pattern. The delete commits synchronously; updateRule fires after.[[FLAT_TRIM]] Rare late-commit recovery (asyncCommitLikely:true): verify via hub_get_app_config(appId), do NOT call cancelTrash (it can commit pending deletes); full protocol: guide:true.[[/FLAT_TRIM]]"
                    ],
                    replaceActions: [
                        type: "array",
                        description: "Atomically replace the rule's entire action list: clears all existing actions, bulk-adds every spec here (same shape as addAction items), then fires updateRule once. Useful for editing or reordering; pass [] to clear all (equivalent to clearActions=true).[[FLAT_TRIM]] Rare late-commit recovery (asyncCommitLikely:true): verify via hub_get_app_config(appId), re-add the echoed pendingActionsToAdd, do NOT call cancelTrash; full protocol: guide:true.[[/FLAT_TRIM]]",
                        items: [type: "object"]
                    ],
                    moveAction: [
                        type: "object",
                        description: "Move a single action up or down by one slot. Pass {index: <N>, direction: 'up'|'down'}.[[FLAT_TRIM]] For arbitrary reorders, prefer replaceActions with the new order — that's a single atomic operation.[[/FLAT_TRIM]]"
                    ],
                    removeTrigger: [
                        type: "object",
                        description: "Delete a single trigger by its index. Pass {index: <N>}.[[FLAT_TRIM]] RM preserves remaining trigger indices (no renumbering on delete). updateRule fires after the deletion. Use addTrigger to add a replacement.[[/FLAT_TRIM]]"
                    ],
                    modifyTrigger: [
                        type: "object",
                        description: "Change the state field of an existing trigger: {index: <N>, mods: {state: '<new-state>'}}.[[FLAT_TRIM]] Opens editCond, writes tstate<N>, commits, fires updateRule. Only the 'state' field is supported, and only device-state triggers (Switch, Motion, Contact, Lock, Presence, ...) expose one -- Time/Periodic/Mode triggers throw with a removeTrigger+addTrigger workaround hint. To change capability or deviceIds, use removeTrigger + addTrigger.[[/FLAT_TRIM]]"
                    ],
                    walkStep: [
                        type: "object",
                        description: """Schema-aware wizard walker -- the escape hatch when the high-level addAction/addTrigger helpers don't cover the capability (Periodic Schedule sub-pages, conditional-trigger binding, IF/THEN/ELSE flow control, later-firmware features). operation='drive' runs a whole steps=[...] loop in one call; the single-step primitives are introspect / write / click / navigate / done.[[FLAT_TRIM]]

PREFERRED -- operation='drive' runs the whole loop in ONE call: pass steps=[ {operation, page?, write?/click?/navigate?/done?, hrefContext?}, ... ] and the tool performs them in order (introspect → navigate into a sub-page → write each field → done → finalize), carrying the page forward across navigate/done and stopping at the first failed step (stopOnError=false to continue). Returns {steps:[per-step diff/valueEcho/health], success, health}. This does the progressive loop for you instead of issuing N separate calls.

Single-step operations (the primitives drive composes; call directly for fine control): introspect (fetch schema, no mutation) | write (one field, exactly one key per call) | click (a regular button: cancelCapab, hasAll, moreCond, ...) | navigate (forward into a sub-page via its href) | done (BACK-navigate to the parent via _action_previous=Done, carrying the sub-page's settings; REQUIRED for sub-pages like Periodic whose parent row otherwise renders "?" -- pass hrefContext={fromPage:<parent>, hrefParams:{n:<idx>}}).

Spec: {page, operation, write?:{<field>:<value>}, click?:{name, stateAttribute?}, navigate?:{targetPage}, validateEnum?, hrefContext?:{fromPage, hrefName, hrefParams?, hrefIndex?}, steps?:[...] (drive)}; page is e.g. selectTriggers/selectActions/doActPage/mainPage/periodic. Always check `silentRejection`, `valueEcho.match`, `health.ok` -- the fail-loud signals. Full reference: guide:true or hub_get_tool_guide(section='set_rule_reference').[[/FLAT_TRIM]]"""
                    ],
                    addAction: [
                        type: "object",
                        description: """Add a Rule Machine ACTION via the high-level structured API. DISCRIMINATOR: use `capability` (NOT `type`) -- `{type: 'log', ...}` is rejected with "addAction.capability is required (e.g. 'switch')". Pass `addAction: {discover: true}` for the live per-field schema (returns immediately, no hub mutation), or see docs/rm_action_subtype_schemas.md / hub_get_tool_guide(section='set_rule_reference'). The tool orchestrates the full RM 5.1 action wizard internally; returns result.actionIndex (no trailing updateRule needed -- doActPage self-bakes the action).

Capability families (NAMES; full per-field specs via discover:true or the guide): switch, dimmer, color, colorTemp, button, runCommand, lock, thermostat, shade, fan, mode, setVariable / setLocalVariable, log / notification / httpGet / httpPost / ping, volume / mute / chime / siren, privateBoolean / runRule / cancelTimers / pauseRule, capture / restore / refresh / poll / disableDevice, fileWrite / fileAppend / fileDelete, zwavePoll, and flow control (delay / delayPerMode / cancelDelay / repeat / stopRepeat / repeatWhile / waitExpression / waitEvents / ifThen / elseIf / else / endIf / exitRule / comment).[[FLAT_TRIM]] The expression-based ones (ifThen / elseIf / repeatWhile / waitExpression) take expression={conditions:[...], operator|operators}. LIMIT: only ONE waitEvents action per rule (RM stores wait events globally, not per-action).

Per-condition shape inside any expression: {capability, deviceIds?, state?, comparator?, value?, attribute?, not?, rawSettings?}. Pass singular deviceId:N or deviceIds:[N] (array form preferred -- a bare integer silently stores {N: null}). Nested subExpression is NOT supported here (use addRequiredExpression). Extended per-capability shapes (Mode, Between two times, Variable, Custom Attribute, compareToDevice) and discrete-event state names: guide:true.

Optional on any spec: delay {hours, minutes, seconds, cancelable}; rawSettings {fieldName: value} escape hatch using the '@N' token for the auto-assigned action index (e.g. {'flashRate.@N': 750}). Variable-sourced values and the not-yet-mapped capability workarounds (HSM/Garage/Valve): guide:true.

PARTIAL-SUCCESS: partial:true is orthogonal to success — the action row exists but needs repair (repairHints names next steps); for unrecoverable rows (hubRenderError=true) use removeAction(index:N) then retry. On failure wizardStuck:true means call hub_set_rule(button='cancelCapab', pageName='doActPage', confirm=true) before retry (restoreHint has the exact command). Full protocol: guide:true.[[/FLAT_TRIM]]"""
                    ],
                    guide: [type: "boolean", description: "Set true to return the full hub_set_rule capability reference inline[[FLAT_TRIM]] (trigger/action/expression families, extended condition shapes, the raw settings/button wizard flow, and walkStep) — same content as hub_get_tool_guide(section='set_rule_reference'), without a separate tool call[[/FLAT_TRIM]]. Makes NO change to any rule."],
                    buttonRule: [type: "object", description: "Create a Button Rule under an existing Button Controller (RM-family).[[FLAT_TRIM]] Routes through the controller's add-button flow; returns buttonRuleId with the Button trigger auto-seeded — then author actions with addAction on that appId. Controller must already have a button device. Same handler as hub_set_native_app.buttonRule.[[/FLAT_TRIM]]", properties: [controllerId: [type: "integer", description: "Button Controller-5.1 appId"], buttonNumber: [type: "integer", description: "button number (>=1)"], event: [type: "string", enum: ["pushed", "held", "doubleTapped", "released"]]]],
                    confirm: [type: "boolean", description: "Must be true."]
                ],
                required: ["confirm"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the update succeeded (absent in discover mode)"],
                    appId: [type: "integer", description: "App ID updated"],
                    buttonRuleId: [type: "integer", description: "buttonRule: appId of the created Button Rule (author its actions via addAction on this id)"],
                    controllerId: [type: "integer", description: "buttonRule: parent Button Controller appId"],
                    backup: [type: "object", description: "Pre-update backup metadata (backupKey, type, fileName, ...)"],
                    settingsApplied: [type: "array", description: "Settings applied (settings mode)", items: [type: "string"]],
                    settingsSkipped: [type: "array", description: "Settings skipped", items: [type: "string"]],
                    unknownSettingsWarning: [type: "string", description: "Present when unknown settings supplied"],
                    subPageNote: [type: "string", description: "Sub-page note"],
                    buttonClicked: [type: "string", description: "Button clicked (button mode)"],
                    health: [type: "object", description: "Rule health summary"],
                    subscriptionSettle: [type: "string", description: "Subscription settle note"],
                    removedIndex: [type: "integer", description: "removeAction/removeTrigger result index"],
                    reclicked: [type: "boolean", description: "removeAction/removeTrigger: true when the first delete click silently no-oped and the in-tool verified re-click landed the removal"],
                    beforeIndices: [type: "array", description: "Indices before edit", items: [type: "integer"]],
                    afterIndices: [type: "array", description: "Indices after edit", items: [type: "integer"]],
                    index: [type: "integer", description: "moveAction index"],
                    direction: [type: "string", description: "moveAction direction"],
                    beforePosition: [type: "integer", description: "moveAction position before"],
                    afterPosition: [type: "integer", description: "moveAction position after"],
                    indicesAfter: [type: "array", description: "Indices after move", items: [type: "integer"]],
                    partial: [type: "boolean", description: "Bulk add partial flag"],
                    triggers: [type: "array", description: "Bulk addTriggers results", items: [type: "object"]],
                    actions: [type: "array", description: "Bulk addActions results", items: [type: "object"]],
                    updateRuleFailed: [type: "boolean", description: "Trailing updateRule click failed"],
                    subscriptionsNotLive: [type: "boolean", description: "Subscriptions not live after update"],
                    updateRuleError: [type: "string", description: "updateRule error detail"],
                    repairHints: [type: "array", description: "Suggested fixes", items: [type: "string"]],
                    note: [type: "string", description: "Human-readable result"],
                    error: [type: "string", description: "Present on failure"],
                    restoreHint: [type: "string", description: "Present on failure"],
                    wizardStuck: [type: "boolean", description: "Present when wizard is stuck"],
                    wizardStuckHint: [type: "string", description: "Present when wizardStuck:true; carries the cancelCapab recovery command to close the half-open wizard before the next write"],
                    removedIndices: [type: "array", description: "replace/clear-all: indices removed", items: [type: "integer"]],
                    addedActions: [type: "array", description: "replace/clear-all: actions added", items: [type: "object"]],
                    modifiedIndex: [type: "integer", description: "modifyTrigger: modified trigger index"],
                    verifiedState: [description: "modifyTrigger: post-edit verified state"],
                    verificationFetchFailed: [type: "boolean", description: "modifyTrigger: verification fetch failed"],
                    triggerIndex: [type: "integer", description: "addTrigger: new trigger index"],
                    configPageError: [description: "Config-page read error, when present"],
                    hubRenderError: [type: "string", description: "addTrigger: hub render error"],
                    actionIndex: [type: "integer", description: "addAction: new action index"],
                    capability: [type: "string", description: "addAction: capability"],
                    action: [type: "string", description: "addAction: action verb"],
                    actType: [type: "string", description: "addAction: action type"],
                    actSubType: [type: "string", description: "addAction: action subtype"],
                    patches: [type: "array", description: "patches: per-patch results", items: [type: "object"]],
                    patchesNotLive: [type: "boolean", description: "patches: not live after update"],
                    variable: [type: "object", description: "addLocalVariable: created variable {name, type, value}. removeLocalVariable: {name, deleted} -- no type/value because a deleted variable has neither (the sub-shape differs by operation; the key is shared)."],
                    variableNotLive: [type: "boolean", description: "addLocalVariable/removeLocalVariable: not live after update (trailing updateRule rejected)"],
                    conditionIndices: [type: "array", description: "addRequiredExpression/replaceRequiredExpression: condition indices", items: [type: "integer"]],
                    expressionNotLive: [type: "boolean", description: "addRequiredExpression/replaceRequiredExpression: not live after update"],
                    requiredExpressionAlreadyExists: [type: "boolean", description: "addRequiredExpression: the rule already has a Required Expression (success:false with actionable error); to change it use replaceRequiredExpression"],
                    requiredExpressionReplaced: [type: "boolean", description: "replaceRequiredExpression: true = a new Required Expression was COMMITTED and finalized live. false when the rebuild failed OR the trailing updateRule was rejected -- a rejected updateRule auto-restores the pre-op backup (see requiredExpressionRestored), it does NOT leave a committed-but-not-live RE"],
                    requiredExpressionMissing: [type: "boolean", description: "replaceRequiredExpression: no committed Required Expression to replace (success:false, RE intact); use addRequiredExpression to add one"],
                    requiredExpressionRestored: [type: "boolean", description: "replaceRequiredExpression: post-delete rebuild failed; true = original Required Expression auto-restored in place from backup, false = NOT recovered in place (check requiredExpressionRestoredAs, else manual hub_restore_backup needed)"],
                    requiredExpressionRestoredAs: [type: "integer", description: "replaceRequiredExpression: present when auto-restore could not reuse the original appId and recreated the rule under this NEW id -- the original appId is dead; use this id and delete the husk"],
                    wizardDoneAutoRetry: [type: "boolean", description: "settings/button: wizard-done auto-retry fired"],
                    warning: [type: "string", description: "Non-fatal warning"],
                    asyncCommitLikely: [type: "boolean", description: "clearActions/replaceActions/moveAction: the operation could not be confirmed within the verify window -- verify before retrying (always paired with success:false)"],
                    httpWriteStatus: [description: "clearActions: HTTP write status"],
                    actionsRequestedForRemoval: [type: "integer", description: "clearActions: actions requested for removal"],
                    actionsStillPresent: [type: "integer", description: "clearActions: actions still present after"],
                    possibleStateEditAct: [description: "clearActions: possible state-edit action"],
                    verifyHint: [type: "string", description: "clearActions/replaceActions/moveAction: human-readable verify-before-retry guidance for an unconfirmed async commit"],
                    safeRecovery: [type: "object", description: "clearActions: safe-recovery guidance"],
                    partialTriggers: [type: "array", description: "create: indices of bundled triggers that failed to fully bake", items: [type: "integer"]],
                    partialActions: [type: "array", description: "create: indices of bundled actions that failed to fully bake", items: [type: "integer"]],
                    requiredExpression: [type: "object", description: "create: outcome of a bundled addRequiredExpression (success/partial/error + conditionIndices/settingsSkipped); present only when addRequiredExpression was passed on CREATE"],
                    appType: [type: "string", description: "create: app type created (rule_machine)"],
                    name: [type: "string", description: "create: rule label"],
                    labelApplied: [type: "boolean", description: "create: true if the requested name became the display label (always true for rule_machine)"],
                    parentAppId: [type: "integer", description: "create: parent (Rule Machine) app ID"],
                    statusSummary: [type: "object", description: "create: eventSubscriptions and scheduledJobs counts"],
                    orphanCleanup: [type: "string", description: "create: present when a failed create's half-built shell was cleaned up"]
                ]
            ]
        ],
        [
            name: "hub_get_rule_health",
            description: """Inspect a rule's current state and return a structured health report -- detect broken rules without curl. Works for Rule Machine, Visual Rules Builder, and other classic apps (Button Controller, Basic Rule). Run after every mutation; hub_set_rule attaches it as `health` on every response. ok=false means at least one issue was found; the issues list explains what.[[FLAT_TRIM]] These apps share RM's configPage protocol. Prefers the rule's compiled state (RM `broken` via /app/ruleBuilderJson, or a graph VRB's validationErrors via /app/ruleBuilder20Json) with the HTML render scan as cross-check + fallback; `ruleFormat` (rm / vrb-graph / vrb-classic / basic-rule / button-controller / classic-app) says which engine answered. The report surfaces the compiled-state broken verdict, validationErrors, config-page render errors, RM *BROKEN* / **Broken Trigger|Action|Condition** markers, multiple-flag corruption, structural IF/Repeat imbalance, and a compiled-vs-HTML cross-check (full key list + brokenMarkerCounts: outputSchema below).[[/FLAT_TRIM]]""",
            inputSchema: [
                type: "object",
                properties: [
                    appId: [type: "integer", description: "Installed-app ID to check (Rule Machine or Visual Rules Builder rule)."],
                    source: [type: "string", enum: ["auto", "ruleBuilderJson", "configPage"], description: "Which source(s) to read.[[FLAT_TRIM]] 'auto' (default): the preferred compiled-state verdict plus the RM HTML render detections + a cross-check. 'ruleBuilderJson': the compiled-state verdict only. 'configPage': the legacy RM HTML render scan only.[[/FLAT_TRIM]]"]
                ],
                required: ["appId"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    ok: [type: "boolean", description: "True when no issues found"],
                    broken: [type: "boolean", description: "Authoritative compiled-state broken verdict (RM `broken`, or graph VRB validationErrors non-empty); null when no boolean applies. Disambiguate via ruleFormat: null+ruleFormat='vrb-classic'/'basic-rule'/'button-controller'/'classic-app' is a healthy rule with no compiled boolean; null+ruleFormat=null means undetermined (source unavailable / read failed)."],
                    source: [type: "string", description: "Which source(s) contributed: 'ruleBuilderJson', 'ruleBuilder20Json', 'configPage', a '+'-join, or 'none'"],
                    ruleFormat: [type: "string", description: "What was inspected: 'rm', 'vrb-graph', 'vrb-classic', 'basic-rule', 'button-controller', 'classic-app' (other classic apps via configPage), or null when unrecognized"],
                    label: [type: "string", description: "Rule label (RM); null when the JSON-only path answered (HTML scan skipped)"],
                    configPageError: [type: "string", description: "Config page error; null when none"],
                    brokenMarkers: [type: "array", description: "Broken Trigger/Action/Condition markers from the HTML render; always present, empty when none", items: [type: "string"]],
                    brokenMarkerCounts: [type: "object", description: "Per-marker occurrence count in the current render -- key is the marker string (e.g. **Broken Condition**), value is how many times it appears. The replace restore gate uses a count increase to detect a genuinely-new broken instance when the same marker already existed in the baseline."],
                    multipleFlagPoison: [type: "array", description: "Poisoned setting names; always present, empty when none", items: [type: "string"]],
                    structuralIssues: [type: "array", description: "Structural issues; always present, empty when none", items: [type: "string"]],
                    validationErrors: [type: "array", description: "Graph Visual Rule validation errors; always present, empty when none", items: [type: "string"]],
                    predicate: [type: "object", description: "Compiled required-expression summary from ruleBuilderJson: {hasPredicate, predCapabs}. Present only when the compiled RM state carried the predicate fields (hasPredicate may be false)."],
                    issues: [type: "array", description: "All issues; ok is false iff non-empty", items: [type: "string"]]
                ],
                required: ["ok"]
            ]
        ],
        [
            name: "hub_list_rule_local_variables",
            description: "List a Rule Machine rule's LOCAL variables (per-rule, distinct from hub globals).[[FLAT_TRIM]] Hub globals are covered by hub_list_variables; locals are created via hub_set_rule addLocalVariable / removeLocalVariable. Reads state.allLocalVars from the rule's statusJson appState; returns each local's name, type, and current value. Pure read -- no wizard, no mutation. Use to confirm a local exists (and its type) before targeting it with the setLocalVariable action or removeLocalVariable shortcut.[[/FLAT_TRIM]] Requires the Read master.",
            inputSchema: [
                type: "object",
                properties: [
                    appId: [type: "integer", description: "The Rule Machine rule's installed-app id (the rule whose local variables to list)."]
                ],
                required: ["appId"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    appId: [type: "integer", description: "The rule id queried"],
                    localVariables: [type: "array", description: "The rule's local variables; empty when the rule has none", items: [type: "object", properties: [
                        name: [type: "string", description: "Local variable name (use as %name% in actions/expressions)"],
                        type: [type: "string", description: "Internal type token as RM stores it (integer/bigdecimal/string/boolean/datetime)"],
                        value: [description: "Current value"]
                    ]]],
                    total: [type: "integer", description: "Number of local variables"]
                ],
                required: ["appId", "localVariables", "total"]
            ]
        ],
        [
            name: "hub_delete_native_app",
            description: """Delete any classic native automation app (RM rule, Room Lighting instance, Button Controller / Button Rule, Basic Rule, Notifier, Group, Scene, etc.). Same endpoint family across all of them. force=false (default) soft-deletes (hub refuses if the app has child apps/devices); force=true hard-deletes with no child safety checks.
[[FLAT_TRIM]]
force=false (default): soft delete via /installedapp/delete. Hub refuses if the app has child apps or devices; response includes hubMessage explaining why.

force=true: hard delete via /installedapp/forcedelete/quiet — the same path the hub UI uses internally for its own \"Delete\" buttons. No child safety checks.
[[/FLAT_TRIM]]
BEFORE DELETE: full snapshot saved to File Manager. Response includes backup.backupKey; call hub_restore_backup (in hub_manage_code) with that key to recreate the app with all its settings re-applied.

Requires Write master + confirm=true + recent hub backup.""",
            inputSchema: [
                type: "object",
                properties: [
                    appId: [type: "integer", description: "Installed-app ID."],
                    force: [type: "boolean", description: "true = bypass child/device safety checks and force-delete. Default false."],
                    confirm: [type: "boolean", description: "Must be true."]
                ],
                required: ["appId", "confirm"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the delete succeeded"],
                    appId: [type: "integer", description: "App ID"],
                    mode: [type: "string", description: "delete or forcedelete"],
                    backup: [type: "object", description: "Pre-delete backup metadata"],
                    hubMessage: [type: "string", description: "Present when hub refused soft delete"],
                    note: [type: "string", description: "Human-readable result"],
                    error: [type: "string", description: "Present on exception"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_set_app_disabled",
            description: "Enable or disable any installed app (the admin UI red-X) without deleting it — reversible and preserves the app's config. For Rule Machine rules use hub_set_rule_paused instead. Write master; the disabled flag is read-back verified.",
            inputSchema: [
                type: "object",
                properties: [
                    app_id: [type: "integer", description: "Installed-app ID to enable/disable (from hub_list_apps)."],
                    disabled: [type: "boolean", description: "true = disable the app (stop it running), false = enable it."]
                ],
                required: ["app_id", "disabled"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the disabled flag now matches the requested value (read-back verified)"],
                    appId: [type: "integer", description: "App ID"],
                    disabled: [type: "boolean", description: "The app's disabled flag after the call"],
                    message: [type: "string", description: "Human-readable result on success"],
                    error: [type: "string", description: "Present on failure"],
                    note: [type: "string", description: "Recovery guidance on failure"]
                ],
                required: ["success"]
            ]
        ],
    ]
}

// Enable/disable any installed app via POST /installedapp/disable {id, disable} -- the documented
// Vue admin wire format (red-X). Reversible (re-enable with disabled=false). A POST that doesn't
// throw is NOT proof; the disabled flag is read back from /installedapp/json/<id>.
def toolSetAppDisabled(args) {
    def id = (args?.app_id != null) ? args.app_id : args?.appId
    if (id == null || !id.toString().isInteger() || id.toString().toInteger() <= 0) {
        throw new IllegalArgumentException("app_id must be a positive integer (got: '${id}')")
    }
    int appId = id.toString().toInteger()
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

// List all Rule Machine rules via the official hubitat.helper.RMUtils API.
// Combines RM 4.x and RM 5.x rules (deduplicated by id).
//
// RMUtils is an optional platform class -- absent on hubs that have never
// installed Rule Machine. Absence manifests as NoClassDefFoundError or
// ClassNotFoundException (both Error subclasses, uncaught by catch Exception).
// Hence the catch Throwable + null-guarded .message across the two try blocks
// below; the classifier further down decides which Throwables to surface vs
// treat as a quiet "RM not installed".
def toolListRmRules(args) {
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
    // output + a warning so callers don't lose visibility on a
    // transient hub error.
    def ghostIds = []
    try {
        def liveIds = _collectLiveAppIds()
        if (liveIds != null) {
            def filtered = [:]
            combined.each { id, entry ->
                def idInt
                try { idInt = (id instanceof Number) ? id.intValue() : id.toString().toInteger() }
                catch (Exception ignored) { idInt = null }
                if (idInt != null && liveIds.contains(idInt)) {
                    filtered[id] = entry
                } else {
                    if (idInt != null) ghostIds << idInt
                }
            }
            combined = filtered
        }
    } catch (Throwable e) {
        mcpLog("warn", "rm-interop", "hub_list_rules: could not cross-check RMUtils output against /hub2/appsList (${e.message}); returning unfiltered")
    }

    // combined.values() returns a Collection view in some Groovy versions; materialize
    // as a concrete List via toList() so subList in _paginateList is safe.
    def rules = combined.values().sort { it.label ?: "" }.toList()

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
        combined[key] = [
            id: id,
            label: label,
            name: name,
            type: type,
            rmVersion: version
        ]
    }
}

// Trigger a Rule Machine rule via RMUtils.sendAction() or the lifecycle
// button for stop/start (RMUtils has no startRule verb — the RM 5.1 UI
// uses the stopRule button as a toggle on state.stopped, and clicking
// it while stopped restarts the rule and resets the private boolean).
//
// Not destructive — invokes existing user-configured automation.
def toolRunRmRule(args) {
    if (args?.ruleId == null) throw new IllegalArgumentException("ruleId is required")
    def ruleId = normalizeRuleId(args.ruleId)
    def action = args?.action ?: "rule"

    // start/stop route through the stopRule button click (a toggle on
    // state.stopped) rather than RMUtils.sendAction — verified live on
    // firmware 2.5.0.123 that clicking stopRule when state.stopped=false
    // sets it true (unsubscribe + cancel delays/repeats/waits) and when
    // state.stopped=true clears it + re-runs initialize() + resets the
    // private boolean. To keep the verb idempotent, we read state before
    // clicking and no-op if the rule is already in the target state.
    if (action == "stop" || action == "start") {
        return _rmToggleStopped(ruleId, action)
    }

    def rmAction
    switch (action) {
        case "rule": rmAction = "runRule"; break
        case "actions": rmAction = "runRuleAct"; break
        default: throw new IllegalArgumentException("Invalid action '${action}'. Must be 'rule', 'actions', 'stop', or 'start'.")
    }

    return sendRmAction(ruleId, rmAction, "hub_call_rule action=${action}")
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
// <p>RM clears {@code state.editAct} automatically after ~60s. The value
// is typically an Integer (the action index) but may be serialized as a
// String by some firmware versions -- returned as-is without coercion.
private Object _rmGetStateEditAct(Integer appId) {
    def status = _rmFetchStatusJson(appId)
    def entry = (status?.appState ?: []).find { it?.name?.toString() == "editAct" }
    return entry?.value
}

// Pause or resume a Rule Machine rule via RMUtils.sendAction(pauseRule|resumeRule).
// value=true pauses, value=false resumes. Idempotent on the hub side. Backs the
// hub_set_rule_paused tool (verb-pair merge of the former pause/resume tools).
def toolSetRulePaused(args) {
    if (args?.ruleId == null) throw new IllegalArgumentException("ruleId is required")
    if (args?.value == null) throw new IllegalArgumentException("value (boolean) is required: true=pause, false=resume")
    Boolean paused
    if (args.value instanceof Boolean) paused = args.value
    else if (args.value == "true") paused = true
    else if (args.value == "false") paused = false
    else throw new IllegalArgumentException("value must be boolean true/false (got: ${args.value})")
    def ruleId = normalizeRuleId(args.ruleId)
    def result = paused ? sendRmAction(ruleId, "pauseRule", "hub_set_rule_paused")
                        : sendRmAction(ruleId, "resumeRule", "hub_set_rule_paused")
    // Echo the applied pause state so callers can confirm the outcome without a
    // follow-up read. RMUtils pause/resume is fire-and-forget, so this is the
    // requested/applied value, not a hub read-back.
    if (result instanceof Map) result.paused = paused
    return result
}

// Set a Rule Machine rule's private boolean (used by its conditions) via
// RMUtils.sendAction(setRuleBooleanTrue|setRuleBooleanFalse).
//
// Strict-coercion policy: accepts ONLY Boolean true/false or the canonical
// lowercase strings "true"/"false". Other truthy-looking values — 1, 0,
// "True", "TRUE", "yes", "no", "on", "off" — are rejected with
// IllegalArgumentException. Reason: silently coercing "yes" to true (or 1
// to true) risks the AI sending the wrong boolean when a rule's behaviour
// depends on the boolean (e.g. arming a security path). The ambiguity is
// worse than the friction of requiring explicit true/false.
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
    return sendRmAction(normalizeRuleId(args.ruleId), rmAction, "hub_set_rule_private_boolean value=${resolved}")
}

// Shared sendAction wrapper. Returns a consistent success/error result map.
//
// Invariant: the 4-arg form `sendAction([ids], action, appLabel, "5.0")` dispatches
// to the RM 5.x handler and is the canonical call for both RM 4.x and RM 5.x rules.
// The 3-arg form `sendAction([ids], action, appLabel)` reaches only RM 4.x and is
// used as a fallback for very old firmware that predates the version-discriminator
// overload (see sendRmActionFallback). Background on the RM API shape:
// https://community.hubitat.com/t/rule-machine-api/7104
private Map sendRmAction(Integer ruleId, String rmAction, String logContext) {
    def appLabel = app?.label ?: "MCP Rule Server"
    try {
        hubitat.helper.RMUtils.sendAction([ruleId], rmAction, appLabel, "5.0")
        mcpLog("info", "rm-interop", "${logContext}: sent ${rmAction} to rule ${ruleId}")
        return [success: true, ruleId: ruleId, rmAction: rmAction]
    } catch (MissingMethodException e) {
        return sendRmActionFallback(ruleId, rmAction, appLabel, logContext, e)
    } catch (NoSuchMethodError e) {
        return sendRmActionFallback(ruleId, rmAction, appLabel, logContext, e)
    } catch (Throwable e) {
        // Everything else — NoClassDefFoundError (RM not installed), timeouts, NPEs
        // inside RM internals, invalid ruleId — propagates through a single error
        // response without a second attempt. Retrying a transient failure would
        // double-fire the action if the first call partially succeeded.
        def m = e.message ?: e.toString()
        mcpLog("error", "rm-interop", "${logContext} failed for rule ${ruleId}: ${m}")
        return [success: false, error: "RMUtils.sendAction failed: ${m}", note: "Verify the ruleId is valid (use hub_list_rules) and Rule Machine is installed."]
    }
}

// 3-arg fallback invoked only when the 4-arg sendAction raised a signature-mismatch
// shape (MissingMethodException / NoSuchMethodError) — very old firmware that
// predates the version-discriminator overload. Any other Throwable from the 4-arg
// call propagates directly in sendRmAction above.
private Map sendRmActionFallback(Integer ruleId, String rmAction, String appLabel, String logContext, Throwable original) {
    try {
        hubitat.helper.RMUtils.sendAction([ruleId], rmAction, appLabel)
        mcpLog("info", "rm-interop", "${logContext}: sent ${rmAction} to rule ${ruleId} (3-arg fallback)")
        return [success: true, ruleId: ruleId, rmAction: rmAction, fallback: "3-arg"]
    } catch (Throwable e2) {
        def m1 = original.message ?: original.toString()
        def m2 = e2.message ?: e2.toString()
        mcpLog("error", "rm-interop", "${logContext} failed for rule ${ruleId}: 4-arg=${m1}, 3-arg=${m2}")
        return [success: false, error: "RMUtils.sendAction failed: ${m2}", note: "4-arg attempt also failed: ${m1}. Verify the ruleId is valid (use hub_list_rules) and Rule Machine is installed."]
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
private String _resolveCommitButton(String configAppName) {
    if (!configAppName) return "updateRule"
    def match = _appTypeRegistry().find { typeKey, reg -> reg.appName == configAppName }
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
// `unsettled` (Boolean) + `triggerCount` (Integer) + `subCount` (Integer)
// so the caller can decide whether to auto-retry / warn.
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
        subCount: subs.size()
    ]
}

// Collect all installed-app ids currently present in the hub's authoritative
// /hub2/appsList tree. Used by hub_list_rules to filter out stale RMUtils-
// cache ghosts after a rule is deleted (the delete itself succeeds at the
// InstalledApp table, but RMUtils' internal rule list lags for seconds-to-
// minutes — hub UI lists never show these because they read /hub2/appsList,
// not RMUtils, so we do the same).
//
// Returns a Set<Integer> of all live ids across the tree (any depth), or
// null if the endpoint is unreachable / malformed. Callers treat null as
// "cannot filter — surface RMUtils output unfiltered" rather than as an
// empty set (which would drop every rule).
private Set _collectLiveAppIds() {
    def responseText
    try {
        responseText = hubInternalGet("/hub2/appsList")
    } catch (Exception e) {
        mcpLog("warn", "rm-interop", "_collectLiveAppIds: /hub2/appsList fetch failed (${e.message})")
        return null
    }
    if (!responseText) return null
    def parsed
    try {
        parsed = new groovy.json.JsonSlurper().parseText(responseText)
    } catch (Exception e) {
        mcpLog("warn", "rm-interop", "_collectLiveAppIds: /hub2/appsList parse failed (${e.message})")
        return null
    }
    def ids = [] as Set
    def walk
    walk = { node ->
        if (node == null) return
        def idVal = node?.data?.id ?: node?.id
        if (idVal != null) {
            try { ids << (idVal as Integer) } catch (Exception ignored) { /* skip non-int ids */ }
        }
        (node?.children ?: []).each { walk(it) }
    }
    (parsed?.apps ?: []).each { walk(it) }
    return ids
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
// package-private for testability -- the _rm prefix is the convention for internal helpers
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
// reason code is added -- there is no lint rule today that mirrors the
// discrete-event-caps-doc-parity check for reason codes. Adding such a lint
// would be the mechanical class-fix (PIPELINE.md Rule 13) that closes the
// remaining gap.
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
    // ('*changed*'/'*became*') was requested on an enum attribute ALONGSIDE an explicit
    // state/value. The value lands and the rule works as an equals-check, so the dropped
    // change-comparator intent is reported informationally, NOT as a partial degradation.
    return ["reveal_fallback_to_existing_field", "useST_idempotent_noop",
            "state_change_comparator_ignored_explicit_value"]
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
        def exists
        try {
            def resp = hubInternalGet("/device/fullJson/${idStr}")
            exists = (resp != null && resp.toString().length() > 0)
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
            throw new IllegalArgumentException("${label} contains device ID '${idStr}' which does not exist on the hub. Verify the device ID via hub_list_devices.")
        }
    }
}

// Map common ASCII comparator aliases to the Unicode glyphs RM 5.1's
// comparator enum accepts. Verified live 2026-05-17 on firmware
// 2.5.0.135: writing "!=" silently rejects because the enum option set
// is the Unicode "≠". Pass-through for unrecognized values so callers
// who already pass the Unicode glyphs continue to work.
// package-private for testability — _rm prefix is the convention for internal helpers
String _rmNormalizeComparator(Object raw) {
    if (raw == null) return null
    def s = raw.toString()
    if (s == "!=" || s == "<>") return "≠"
    if (s == "==") return "="
    return s
}

// package-private for testability — _rm prefix is the convention for internal helpers
// Single source of truth for the RM no-RHS state-change comparator family. Each entry
// is a substring marker that identifies a comparator selecting a change EVENT rather
// than a value test, so it has no right-hand value to place in a value picker. The
// predicate below is a FAMILY gate ("is this a no-RHS comparator at all"); routing an
// option to a specific request must additionally match the exact token (see
// _rmComparatorTokensMatch). If RM adds a new no-RHS token, extend THIS list in
// lockstep -- the enum branches across the four wizard surfaces consult the predicate,
// not a private copy of the markers. Method-constant, not `static final` (script-scope
// rejects the field in the Hubitat sandbox -- see hubResponseCapBytes).
private List<String> _rmRhsOptionalComparatorMarkers() { ["changed", "became"] }

// package-private for testability — _rm prefix is the convention for internal helpers
// FAMILY gate for the no-RHS state-change comparator family. Used both to relax the
// RHS-required guards (a missing value is legitimate here) and to detect when such a
// comparator is being routed to an enum-valued attribute whose value picker cannot
// represent it. This is intentionally a fuzzy substring test: it answers "no-RHS
// comparator?" only. Choosing WHICH picker option to route a request to is the job of
// _rmComparatorTokensMatch (exact-token equality), never this gate.
boolean _rmComparatorIsRhsOptional(Object rawComparator) {
    def c = rawComparator?.toString()?.toLowerCase()
    if (c == null) return false
    return _rmRhsOptionalComparatorMarkers().any { c.contains(it) }
}

// package-private for testability — _rm prefix is the convention for internal helpers
// Strip a comparator token to its comparable core: the '*...*' wizard wrapping and
// surrounding whitespace removed, case-folded. RM renders state-change tokens wrapped
// (e.g. '*became true*') in the picker option list but the request may arrive wrapped
// or bare; normalizing both sides lets an EXACT equality decide a route.
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

// package-private for testability — _rm prefix is the convention for internal helpers
// Exact-token equality between a requested comparator and a candidate picker option,
// after stripping the '*...*' wrapping and case-folding both sides. This is the ROUTE
// gate: a picker offering both 'became true' and 'became false' must route a
// '*became false*' request to the EXACT option, never the first RHS-optional option;
// and a picker option like 'unchanged' must NOT match a '*changed*' request. Use this
// (not the fuzzy family gate) whenever choosing which option to write.
private boolean _rmComparatorTokensMatch(Object requested, Object candidate) {
    return _rmComparatorTokenMatchesOption(_rmComparatorToken(requested), candidate)
}

// package-private for testability — _rm prefix is the convention for internal helpers
// Equality of an ALREADY-NORMALIZED requested token against a candidate picker option.
// Route sites normalize the requested comparator ONCE (hoisted out of the per-candidate
// .find) and call this; _rmComparatorTokensMatch is the un-hoisted convenience form.
private boolean _rmComparatorTokenMatchesOption(String requestedToken, Object candidate) {
    if (requestedToken == null) return false
    def b = _rmComparatorToken(candidate)
    return b != null && requestedToken == b
}

// package-private for testability — _rm prefix is the convention for internal helpers
// Normalize an enum input's `options` to a list of value strings. The hub returns
// enum-picker options in shapes that need flattening before use:
//   - bare strings: ["on", "off"]
//   - a LIST of value-key Maps: [[value:"on", text:"On"], ...] -- one option per Map
//   - a MAP container keyed by value: ["on":"On", "off":"Off"] -- value is the KEY
// A naive toString() on a value-key Map option yields "[value:on, text:On]"; iterating
// a Map CONTAINER with .collect would yield "on=On" Map.Entry strings; a bare-String
// `options` would .collect into per-CHARACTER options. Any of those corrupts the route
// probe AND the shared state_<N> domain validator that consumes this helper. The
// canonical reader for the enum route-or-skip probe and the state_<N> domain validator.
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
    return (opts.collect { o ->
        (o instanceof Map ? o.value?.toString() : o?.toString())
    }).findAll { it }
}

// package-private for testability — _rm prefix is the convention for internal helpers
// Canonical repair-hint for a no-RHS state-change comparator requested on an enum-valued
// Custom Attribute (the comparator_not_representable_for_enum_attribute skip). Single
// source of truth so the trigger / condition / Required-Expression emit sites cannot
// drift. ASCII-only.
private String _rmNotRepresentableEnumComparatorHint(Object attribute, Object comparator) {
    def attrName = attribute?.toString()
    def cmp = comparator?.toString()
    return "Comparator '${cmp}' cannot be represented for the enum-valued Custom Attribute '${attrName}': Rule Machine offers only a value picker (e.g. on/off) for an attribute it recognizes as an enum, with no comparator slot, so a state-change comparator has nowhere to land. Use a non-built-in attribute name, or trigger on the device's native capability instead (e.g. capability:'Switch')."
}

private Map _rmAddTrigger(Integer appId, Map triggerSpec) {
    if (!(triggerSpec instanceof Map)) throw new IllegalArgumentException("addTrigger requires a Map spec")
    // Discover mode -- return static schema without touching the hub.
    // No capability field required; no Write master gate; no backup.
    if (triggerSpec.discover == true) {
        return _rmTriggerSchemaForDiscover()
    }
    def cap = triggerSpec.capability?.toString()?.trim()
    if (!cap) throw new IllegalArgumentException("addTrigger.capability is required. Common values: Switch, Motion, Contact, Time, Periodic Schedule, Mode, Custom Attribute. Pass {discover: true} to get the full structured schema.")

    // Pre-validate device IDs exist — RM 5.1 silently stores
    // {<bogusId>: null} in tDev_<N> if the ID doesn't resolve, and the
    // trigger renders as "Broken Trigger" with no event subscriptions.
    _rmValidateDeviceIdsExist("addTrigger.deviceIds", triggerSpec.deviceIds)
    if (triggerSpec.condition instanceof Map) {
        // Normalize singular deviceId -> deviceIds on the trigger.condition Map BEFORE
        // pre-validation runs **because** _rmBuildCondition's internal normalization runs
        // too late to protect _rmValidateDeviceIdsExist: validator sees the raw .deviceIds
        // list, an absent value early-returns, and the bogus singular ID surfaces only
        // as a "Broken Trigger" render later.
        def cm = triggerSpec.condition as Map
        if (cm.deviceIds == null && cm.deviceId != null) {
            cm.deviceIds = [cm.deviceId]
        }
        _rmValidateDeviceIdsExist("addTrigger.condition.deviceIds", cm.deviceIds)
    }

    // Periodic argument validation, run before the moreCond click opens the
    // wizard so a bad spec surfaces a structured failure without leaving an
    // in-flight trigger editor half-open. NOTE: this half-open guarantee is
    // PER-SPEC -- it protects the single trigger being added here. The bulk
    // addTriggers/patches paths share one pre-batch backup, so a mid-batch reject
    // leaves earlier triggers in the batch already committed; batch callers should
    // verify rule state and restore from the batch backup if needed. The full
    // per-frequency field map lives in the periodic block further down; these are
    // just the arg guards.
    if (cap.equalsIgnoreCase("Periodic Schedule") && triggerSpec.periodic instanceof Map) {
        def perEarly = triggerSpec.periodic as Map
        def freqEarly = perEarly.frequency?.toString()
        // Seconds/Minutes count is a restricted RM enum, not a free integer.
        // Fractional values truncate toward zero (5.5 -> 5) and are then
        // range-checked against the enum. Normalize everyN to the truncated
        // integer in place so the downstream write sends the enum-valid value,
        // not the raw fractional (RM's enum field would silent-reject "5.5").
        if ((freqEarly == "Seconds" || freqEarly == "Minutes") && perEarly.everyN != null) {
            def allowedCounts = [1, 2, 3, 4, 5, 6, 10, 12, 15, 20, 30]
            def reqCount = null
            // Broad catch: everyN may arrive as a non-numeric String
            // (NumberFormatException) or a Map/List (GroovyCastException);
            // both mean "not a usable count" and route to the throw below.
            try { reqCount = perEarly.everyN as Integer } catch (Exception ignored) { reqCount = null }
            if (reqCount == null || !(reqCount in allowedCounts)) {
                throw new IllegalArgumentException("Periodic ${freqEarly} everyN must be one of ${allowedCounts} (RM restricts the count to this enum); got '${perEarly.everyN}'.")
            }
            perEarly.everyN = reqCount
        }
        // Monthly has two mutually-exclusive modes whose field sets hide each
        // other: by-day (dayOfMonth) and nth-weekday (weekOfMonth). Reject a
        // spec that mixes them so the caller gets a clear error instead of an
        // unrenderable half-written sub-page.
        if (freqEarly == "Monthly" && perEarly.dayOfMonth != null && perEarly.weekOfMonth != null) {
            throw new IllegalArgumentException("Periodic Monthly: dayOfMonth and weekOfMonth are mutually exclusive -- dayOfMonth selects a calendar day (e.g. the 15th), weekOfMonth selects the Nth weekday (e.g. the Second Monday). Pass one mode's fields, not both.")
        }
    }

    // Snapshot committed trigger indices (settings) BEFORE clicking moreCond.
    // The actual in-flight trigger idx is discovered from the schema after
    // the click — see "schema-aware index discovery" block below. We do not
    // trust existing+1 because state.moreCond can drift (e.g. if a prior
    // STPage walk or aborted wizard left the counter advanced).
    def existing = _rmCollectTriggerIndices(appId)

    // Open the trigger editor (state.moreCond=true).
    _rmClickAppButton(appId, "true", "moreCond", "selectTriggers")

    // CONDITIONAL TRIGGER PATH. When `condition` is provided, the trigger is
    // bound to a freshly-created condition. RM 5.1's wizard for conditional
    // triggers is two-stage: first stage builds a condition (consumes one
    // trigger index for the condition's setup state), second stage builds
    // the actual trigger that REFERENCES the saved condition by ID.
    // Verified live by walking the UI in Chrome with network capture
    // (firmware 2.5.0.123): clicking "Done with this Condition" advances
    // state.moreCond to the next trigger index, and the trigger that
    // follows is at idx+1, with condTrig.<idx+1>=<conditionId> binding it.
    def conditionSpec = (triggerSpec.condition instanceof Map) ? (triggerSpec.condition as Map) : null
    def conditionId = null
    def applied = []
    def skipped = []
    def idx = null
    if (conditionSpec) {
        // Conditional path: pre-compute idx via existing+1 because
        // _rmBuildCondition needs an explicit slot to write its setup
        // state into. We tolerate drift only in the non-conditional path.
        idx = (existing ? existing.max() + 1 : 1)
        conditionId = _rmBuildCondition(appId, idx, conditionSpec, applied, skipped)
        // The condition wizard advanced the trigger index by one, so the
        // actual conditional trigger now lives at idx+1. The isCondTrig
        // and condTrig writes are deferred to the post-tCapab finalize
        // block below — verified live that schema doesn't
        // expose isCondTrig.<idx+1>/condTrig.<idx+1> until tCapab<idx+1>
        // is set + tDev<idx+1>/tstate<idx+1> populated. Writing them
        // early gets silently dropped (settingsSkipped reveals
        // available=[cancelCapab, isCondTrig.<old>, tCapab<new>]).
        idx = idx + 1
    }

    // Normalize capability to the canonical case Hubitat's enum expects.
    // The tCapab<N> input is an enum and its `options` list contains
    // properly-cased labels like "Switch", "Motion", "Contact". Writing
    // a value that isn't in the list is silently rejected by Hubitat:
    // the field stays unset, the schema doesn't progress, and tDev<N>
    // never appears — leaving us to commit a phantom "**Broken
    // Trigger**" via hasAll. Match case-insensitively against the live
    // options and write back the canonical value. Verified live in
    // Chrome 2026-04-25: tCapab2='switch' rejected, tCapab2='Switch'
    // exposes tDev2 of type capability.switch.
    def capPageConfig = _rmFetchConfigJson(appId, "selectTriggers")
    def allCapInputs = (capPageConfig?.configPage?.sections ?: []).collectMany { it?.input ?: [] }
    if (idx == null) {
        // SCHEMA-AWARE INDEX DISCOVERY (non-conditional path). Find the
        // in-flight tCapab<N> for an uncommitted wizard — any N not in the
        // already-committed `existing` set. state.moreCond can be at any
        // value if a prior STPage walk or aborted wizard left the counter
        // advanced; trust the schema, not existing+1. Pick the largest
        // unmatched N (the most-recent moreCond click). Verified live
        // 2026-04-26: STPage walks left state.moreCond at 3+ on rule 1319,
        // and addTrigger using existing+1=1 errored with "tCapab1 not
        // present" until the schema-aware fix landed.
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
        throw new IllegalArgumentException("addTrigger.capability '${cap}' not in Hubitat's trigger capability list. Valid options: ${capOptions.collect { it.toString() }.sort().join(', ')}. Pass {discover: true} to get the per-capability field schema for each of these.")
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

    // Hub Variable trigger: tCapab<N>=Variable exposes xVar<N> (variable
    // picker, no device IDs). Comparator is the standard ReltDev<N> below.
    // Verified live 2026-05-17: xVar<N> options are populated from the
    // hub variables list; writing it advances the schema to expose
    // ReltDev<N>.
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
        // Trigger-row site of the enum-recognized Custom Attribute comparator
        // invariant (see _rmForceWriteEnumField docstring for the authoritative
        // rule). Fields here: comparator=ReltDev<N>, value picker=tstate<N>. The
        // schema-gated writeIfPresent path means a neither-rendered comparator
        // records not_in_schema (not silent dead-storage); a throwing re-fetch
        // force-writes the comparator best-effort and flags partial.
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
                    // ENUM-recognized attribute: only the value picker tstate<N> is exposed,
                    // the comparator ReltDev<N> is hidden. A value comparator's value lands in
                    // tstate<N> below -- correct, nothing to do here. The no-RHS family is
                    // gated by _rmComparatorIsRhsOptional (single-source-of-truth markers in
                    // _rmRhsOptionalComparatorMarkers -- extend BOTH in lockstep if RM adds a
                    // new no-RHS token, or this branch silently falls through). A state-change
                    // comparator ('*changed*' / '*became*') has NO right-hand value, so it
                    // cannot ride the value picker; the routing here applies ONLY when no
                    // explicit value was supplied. If the picker offers an option matching the
                    // REQUESTED change token exactly, the generic tstate<N> write below will
                    // place it; otherwise the comparator is genuinely unrepresentable through
                    // this path and must NOT be silently dropped as clean success (the bug). When
                    // an explicit value WAS supplied it wins and lands in tstate<N> below -- the
                    // change-comparator intent is moot, not routed here.
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
                            // Family gate passed; now require an EXACT token match between the
                            // requested comparator and a picker option (not just any RHS-optional
                            // option), so 'became false' never routes to a 'became true' slot and
                            // 'unchanged' never satisfies a '*changed*' request. Normalize the
                            // requested token ONCE, not per-candidate.
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
        } else {
            // No attribute (numeric/text comparator path on a standard capability) --
            // the comparator field is already in the schema; write it directly.
            writeIfPresent("ReltDev${idx}", _rmNormalizeComparator(triggerSpec.comparator))
        }
    }

    // Button capability has its own button-number field.
    if (triggerSpec.buttonNumber != null) {
        writeIfPresent("ButtontDev${idx}", triggerSpec.buttonNumber)
    }

    // MODE CAPABILITY -- special-case BEFORE the generic tstate path.
    //
    // RM 5.1 Mode triggers do NOT use tstate<N>. The correct field is
    // modesX<N>, which takes a List of mode IDs (String representations of
    // Long IDs from location.modes). Writing tstate<N> for a Mode trigger
    // causes: tCapab<N>=Mode lands, tstate<N> is silently ignored, and the
    // trigger renders as "**Broken Trigger**" because modesX<N> is absent.
    //
    // Two input shapes are accepted:
    //   state  -- String or List<String> of mode NAMES (e.g. "Night" or
    //             ["Away", "Night"]). Names are resolved to IDs via
    //             location.modes. Case-insensitive. All names must resolve;
    //             unresolved names throw IllegalArgumentException with the
    //             full valid-modes list so the caller can correct the input.
    //   modeIds -- List<Integer or String> of mode IDs. Written directly as
    //              String values inside a List (e.g. ["3"] or ["3", "5"]).
    //
    // Verified live (zero-context validation 2026-05-02): two agents
    // hit this pattern (rules 1319, 1320), each spending 30+ extra tool calls
    // deleting and rebuilding the rule because tstate<N> silently no-ops.
    if (capCanonical == "Mode") {
        def rawModeIds = []
        if (triggerSpec.modeIds instanceof List) {
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
                    throw new IllegalArgumentException("addTrigger Mode: mode name '${mn}' not found. Valid modes: ${validModeNames.sort().join(', ')}")
                }
                rawModeIds << matched.id.toString()
            }
        }
        if (rawModeIds.isEmpty()) {
            throw new IllegalArgumentException("addTrigger Mode: no mode IDs resolved -- pass state with valid mode name(s) or modeIds with mode ID(s)")
        }
        writeIfPresent("modesX${idx}", rawModeIds)
    } else {
        // tstate<N> covers both enum-state ("on"/"active") and numeric value
        // (Temperature reading) paths. value field is the numeric form.
        // NOT used for Mode (see special-case above) -- Mode uses modesX<N>.
        def stateValue = triggerSpec.state != null ? triggerSpec.state : triggerSpec.value
        if (stateValue != null) {
            writeIfPresent("tstate${idx}", stateValue)
        }
    }

    // Time-based capability uses time1, atTime1, atSunriseOffset1 etc.
    // The wizard uses index 1 for the time fields regardless of trigger
    // index because RM models time triggers as singletons within the
    // trigger row -- the index is on the wrapper trigger, not the inner
    // time picker. (Verified live on firmware 2.5.0.123.)
    //
    // BUG A FIX: when caller provides atTime but omits time, infer
    // time='A specific time' automatically. atTime is only meaningful
    // for the wall-clock path; Sunrise/Sunset use offset instead.
    // Without this inference the entire time block was silently skipped
    // (the if-guard required time != null), leaving atTime unwritten and
    // the trigger rendering as "Certain Time **Broken Trigger**".
    def effectiveTime = triggerSpec.time
    if (effectiveTime == null && triggerSpec.atTime != null) {
        effectiveTime = "A specific time"
    }
    if (effectiveTime != null) {
        writeIfPresent("time${idx}", effectiveTime)
        if (triggerSpec.atTime != null) {
            // RM 5.1's selectTriggers page parser requires the full
            // 'YYYY-MM-DDTHH:mm:ss.SSS+HHMM' form. Short ISO forms
            // (no millis, no tz, or Zulu 'Z' suffix) cause
            // ParseException on every page render, blocking all
            // subsequent trigger additions with no recovery path short
            // of deleting the rule. Normalize before writing.
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
        // RM's allHandler computes total wait time as SHours*3600 + SMins*60
        // + SSecs and crashes with NullPointerException on .multiply() if
        // any of the three are null when the trigger fires. Always write
        // all three (default 0) so the math survives even when caller only
        // specified one component (e.g. minutes: 2). Verified live: rule
        // with stays=true and only SMins=2 NPEs on the next contact event;
        // adding SHours=0 + SSecs=0 fixes it.
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

    // Periodic Schedule: navigate the dedicated periodic sub-page, write
    // the frequency-specific fields, and submit Done so the trigger row's
    // description bakes ("Every 1 hour starting at 1:00 PM" etc.).
    // Without this dance, hasAll commits a phantom row with description="?"
    // because the parent's renderer can't reach the sub-page state.
    //
    // Field BASE names are IRREGULAR across frequencies (everyNHC vs everyNDC
    // vs everyNSecs -- no shared stem to extract), so freqFieldMap below carries
    // the exact RM 5.1 base name for each role of each frequency, captured live
    // against fw 5.1.8. The per-trigger SUFFIX, by contrast, is uniform: every
    // field ends in the resolved sub-page index pn (interpolated below).
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
        // Resolve the periodic sub-page navigation from the LIVE selectTriggers
        // schema rather than hardcoding it. RM renders the periodic href once
        // the in-flight trigger's capability is Periodic Schedule; the href
        // carries params={n: <trigger index>} keyed to THIS trigger, and the
        // sub-page's field names are suffixed by that same n (whichPeriod<n>,
        // everyNDoMC<n>, ...). For a single periodic trigger n=1 -- byte-identical
        // to suffix-1. For a 2nd periodic trigger RM renders a periodic<n> sub-page
        // with whichPeriod<n>/everyN*C<n>/etc.; routing to the right n AND writing
        // the n-suffixed field names is what keeps the two triggers from colliding.
        // Mirrors the walkStep navigate href-discovery.
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
        // Href absent (not an exception, but still a discovery failure): warn the
        // same way, because suffix-1 fallback is only safe for the FIRST periodic
        // trigger. RM keys the periodic sub-page by trigger index, so without the
        // discovered href a later periodic trigger would write suffix-1 fields and
        // clobber the first trigger's schedule.
        if (!periodicHrefFound) {
            mcpLog("warn", "rm-native", "_rmAddTrigger: no periodic href found in selectTriggers schema for app ${appId} (in-flight trigger idx ${idx}) -- falling back to periodic1/n=1")
        }
        // The marker form-index suffix uses params.n (matches walkStep navigate).
        // Guard the cast: a malformed params.n (non-integer) would otherwise escape
        // to the outer catch as a raw context-less JVM message; fall back to 1 and
        // flag it so the fallback decision below treats a discovered-but-malformed
        // href the same as an absent one (both leave us unable to target the right
        // sub-page index, which is unsafe for a non-first trigger).
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
        // Normalize params.n to the resolved integer index. Downstream re-casts
        // (_rmSubmitSubPageDone re-reads hrefParams.n as Integer) must operate on a
        // clean Integer so a malformed value the cast guard above already absorbed
        // can't re-throw past this point. Build a fresh map -- never mutate the
        // discovered params.
        def hrefParams = (periodicHrefParams instanceof Map ? (periodicHrefParams as Map) : [:]) + [n: periodicHrefIndex]
        // If we cannot reliably target this trigger's own sub-page index -- the href
        // was NOT discovered OR its params.n was malformed -- AND this is not the
        // first trigger slot, suffix-1 fallback would silently overwrite an earlier
        // periodic trigger. Refuse to write a guaranteed clobber: record a skip so
        // the row surfaces partial=true with an actionable repair hint, and leave
        // the periodic sub-page writes unattempted for this trigger. (idx is provably
        // Integer here -- assigned from the resolved trigger index -- so its cast is
        // unguarded, unlike the hub-supplied params.n above.)
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

        // Field-name maps. Keys are role aliases; values are the RM 5.1 setting
        // names with the per-trigger suffix interpolated.
        //
        // Role keys (not all frequencies have all roles):
        //   everyNToggle  -- bool, "every n <unit>?"; must land BEFORE everyNCount
        //   everyNCount   -- the count; for Hourly/Daily a free integer, for
        //                    Minutes a restricted enum (validated up front). Seconds
        //                    is equally restricted, via secondsCount below.
        //   secondsCount  -- Seconds has NO toggle; the count enum IS the mode
        //                    (restricted enum, same allowed set as Minutes)
        //   selectMulti   -- multi-enum picker (hours / days-of-month / days-of-week / minutes)
        //   weekdayToggle -- Daily-only "weekdays only"
        //   dayOfMonth    -- Monthly by-day "on day number"
        //   everyNMonths  -- Monthly by-day "of every n months"
        //   weekOfMonth   -- Monthly/Yearly nth-weekday "in the week of month" (First..Last)
        //   dayOfWeek     -- Monthly/Yearly nth-weekday SINGLE day-of-week (Sunday..Saturday)
        //   everyNMonthsNth -- Monthly nth-weekday cadence (everyNMCX<n>, the X-suffixed field)
        //   monthEnum     -- Yearly nth-weekday single month (yearlyMonthCX<n>, the X-suffixed field)
        //   time          -- "starting at" time field
        //   offset        -- Hourly-only minute offset
        //   cron          -- Cron String text field
        //
        // Monthly and Yearly are MODE-PROGRESSIVE: writing the week-of-month
        // field swaps the visible field set (mutually exclusive with the by-day
        // fields). The dispatch below routes by whether weekOfMonth is present.
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
        def fields = freqFieldMap[freq] ?: [:]
        // (Periodic arg validation -- Seconds/Minutes restricted-enum count and
        // the Monthly dayOfMonth/weekOfMonth mutual-exclusivity -- already ran
        // up front, before the trigger editor opened.)
        // Skip the entire periodic sub-page dance when the suffix-1 fallback
        // would clobber an earlier periodic trigger (see periodicUnsafeFallback).
        if (!periodicUnsafeFallback) {
        // Forward-nav into the periodic sub-page. Its return is intentionally NOT
        // folded into skipped: _rmNavigateToPage returns null on BOTH a POST failure
        // AND a non-JSON response (the latter is benign -- the caller plain-fetches),
        // so null is ambiguous here. A genuine nav failure surfaces deterministically
        // anyway via the writePeriodic persistence checks below (every field skips).
        //
        // BUG-9 (verified live 2026-06-03, app 1606): RM 5.1's OWN `periodic` page
        // method (ruleApp51, ~line 1576) logs a benign NullPointerException
        // ("Cannot get property 'n' on null object ... method periodic") against the
        // RULE app -- not ours (app 194) -- 2-3x while RM renders the periodic
        // sub-page during these interactions. It is RM-internal (RM doesn't null-
        // guard its own params.n on a render path) and NON-FATAL: the trigger bakes
        // cleanly (settingsApplied populated, health.ok). Our POSTs already carry n
        // via hrefParams; the noise is on RM's render, which we do not control. Do
        // NOT chase it into this fragile sub-page flow to silence RM's own log line.
        _rmNavigateToPage(appId, "selectTriggers", "periodic", periodicHrefIndex, periodicHrefName, hrefParams)
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
            // Multi-enum selection. Each frequency exposes ONE selectMulti
            // field but under a different alias; route the matching one.
            //   Hourly  selectedHours        -> selectHoursC<n>
            //   Daily   selectedDaysOfMonth  -> selectDoMC<n>
            //   Weekly  daysOfWeek           -> selectDoWC<n>
            //   Minutes selectedMinutes      -> selectMinutesC<n> (alt to everyN)
            // CONTRACT for the Elvis chain below: it relies on (a) each frequency
            // exposing AT MOST ONE selectMulti alias, so no two of these are set at
            // once, and (b) empty-list falsiness -- an empty [] for one alias falls
            // through to the next. If a future frequency exposes two multi-pickers,
            // this collapses them to one and must be reworked into per-frequency routing.
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
        // Submit Done -- bakes the description into the trigger row. _rmSubmitSubPageDone
        // lets a Done-POST failure throw (so its other callers surface it as success:false);
        // here we catch it locally and fold it into skipped, because the periodic sub-page is
        // write-after-commit (the trigger row already exists) so a Done failure is a repairable
        // partial -- not the full success:false the abort-style callers want.
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

    // Conditional-trigger binding MUST be written BEFORE hasAll while the
    // trigger-edit form is still open. Verified live: after hasAll,
    // selectTriggers' schema is empty (the wizard
    // returned to the trigger-list view, no edit fields exposed), so
    // condTrig.<idx> writes silently fail with available=[] (a hub-render
    // error pattern). The earlier comment claiming condTrig.<idx> only
    // appears AFTER hasAll was wrong.
    //
    // For conditional triggers, the live UI flow inside the trigger edit
    // form is:
    //   1. Toggle isCondTrig.<idx>=true → reveals condTrig.<idx> dropdown
    //   2. Set condTrig.<idx>=<conditionId> → picks which condition the
    //      trigger references (by condition's allocation id)
    //   3. Click hasAll to commit the trigger
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

    // Post-hasAll finalize: for non-conditional triggers we need to write
    // isCondTrig.<idx>=false to close the residual "Conditional Trigger?"
    // prompt that RM exposes briefly after hasAll. Skip this for
    // conditional triggers — they already had isCondTrig.<idx>=true set
    // pre-hasAll, and writing false here would un-bind the condition.
    if (conditionSpec == null && triggerSpec.conditional != true) {
        try {
            _rmWriteSettingOnPage(appId, "selectTriggers", "isCondTrig.${idx}", false, applied, null, skipped)
        } catch (Exception finalizeExc) {
            mcpLog("debug", "rm-native", "_rmAddTrigger: post-hasAll isCondTrig.${idx}=false finalize failed for app ${appId} (${finalizeExc.message}) -- residual 'Conditional Trigger?' prompt may linger, can leave a phantom Broken Trigger N+1")
            // Best-effort: if the prompt isn't there (clean exit), the
            // schema check inside _rmWriteSettingOnPage skips it.
        }
    }

    // Navigate back to mainPage to commit the in-flight trigger.
    // Mirrors the action-wizard pattern (_rmAddAction navigates
    // doActPage→selectActions for the bake) — the live UI's "Done with
    // Triggers" button submits a form with `_action_href_name|mainPage|0`
    // that signals the page transition. This ensures trigger state is
    // fully baked before the next addTrigger call (or final updateRule).
    _rmNavigateToPage(appId, "selectTriggers", "mainPage")

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

    // Post-commit silent-failure detection. RM 5.1's selectTriggers
    // silently accepts many invalid inputs at the field-write level —
    // bad state values, capability/state mismatches, unknown deviceIds —
    // without erroring. The trigger row may not bake at all, leaving
    // mainPage's "Define Triggers" placeholder. Without detection,
    // addTrigger would return success=true on a rule whose trigger
    // didn't actually commit. The hub isn't doing anything wrong —
    // it's permissive by design — but the LLM needs the signal so it
    // can self-correct. Verified: this check catches the
    // "expression silently didn't bake" class of failures that
    // configPageError + brokenMarkers don't catch.
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

    // Partial-success signal — see _rmAddAction for rationale. The trigger
    // is committed but a caller-requested field didn't land; LLM should
    // retry via hub_set_rule(walkStep) or rebuild the trigger row.
    // health.brokenMarkers non-empty means a PRIOR trigger is already broken;
    // the new trigger committed but the rule is in a known-bad state the
    // caller should address — surface as partial so the LLM sees it immediately.
    // Filter informational/cosmetic skips out of the skip-driven partial computation,
    // mirroring the exemption that _rmAddAction and _rmAddRequiredExpression apply via
    // _rmInformationalSkippedReasons(). Only the shared walker sentinels (e.g.
    // reveal_fallback_to_existing_field) are exempt by reason code -- a field the walker
    // matched after it was already visible is not a degraded write.
    // not_in_schema is NOT exempt as a reason code. Like silent_rejection it is GENUINE
    // degradation: a field the helper tried to write was absent from the live page schema, so
    // the value did not land. A state-change comparator such as '*changed*' is itself written
    // into the comparator field (ReltDev_<N>) as a value -- it is not an absent RHS -- so a
    // clean '*changed*' trigger does NOT skip its comparator. The only way the comparator (or
    // attribute, device, state) skips with not_in_schema is a real schema-timing failure where
    // the value never wrote, which must surface as partial rather than be masked. This matches
    // how the walker pages (STPage/doActPage) and _rmAddAction treat not_in_schema, and how
    // _rmModifyTrigger hard-throws on it.
    // The single narrow exception is the (isCondTrig.<N>, not_in_schema, value=false) tuple:
    // after the trigger commits, the helper writes isCondTrig.<N>=false best-effort to dismiss
    // the residual "Conditional Trigger?" prompt that RM only sometimes exposes post-hasAll. On
    // firmware/timing where the prompt already closed, that field is legitimately gone and the
    // write no-ops with not_in_schema -- a clean exit, not a lost trigger value. The value MUST
    // be false: the construction write isCondTrig.<N>=true (binding a conditional trigger to its
    // condition) is load-bearing, and a skip there means the trigger was not made conditional --
    // that must still flip partial. Exempt only that exact field+reason+value so a non-conditional
    // trigger does not spuriously report partial, while every other not_in_schema skip flips it.
    // triggerNotBaked and brokenMarkers flip partial independently below, so a genuinely
    // needed-but-missing field also surfaces via the not-baked check.
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
        repairHints << "Trigger did not bake — mainPage still shows 'Define Triggers' placeholder. Common causes: state value not in capability's enum (e.g. 'on' is invalid for Motion which uses 'active'/'inactive'), capability/state mismatch, or unknown deviceIds. Verify state value matches the capability's domain (Switch: 'on'/'off'/'*changed*', Motion: 'active'/'inactive', Contact: 'open'/'closed', etc.) and run hub_list_devices to verify deviceIds. Then call removeAction or rebuild the rule."
    }
    if (partial || hasBrokenLabel) {
        // Name only genuinely-degraded fields (exclude the informational walker
        // sentinels) so the hint doesn't tell callers to repair a field that the
        // walker merely matched after it was already visible.
        // comparator_force_written_unverified is NOT a lost value: the comparator WAS
        // POSTed (it is in settingsApplied) but the exposure-probe re-fetch that would
        // confirm it failed transiently. Telling the caller it "didn't land" / to re-write
        // it would be wrong, so split those keys out into a verify-don't-rewrite hint.
        def forceWrittenKeys = genuineSkipped.findAll { it instanceof Map && it.reason == "comparator_force_written_unverified" }*.key.findAll { it != null }
        // Both comparator_force_written_unverified (written-but-unverified) and
        // comparator_not_representable_for_enum_attribute (genuinely unrepresentable)
        // get their OWN specific hint below, so exclude them from the generic
        // "didn't land -- introspect and re-write" hint -- that advice does not apply
        // to either and would mislead.
        def specificHintReasons = ["comparator_force_written_unverified", "comparator_not_representable_for_enum_attribute"]
        def lostSkipped = genuineSkipped.findAll { !(it instanceof Map && it.reason in specificHintReasons) }
        if (!lostSkipped.isEmpty()) {
            def skippedKeys = lostSkipped*.key.findAll { it != null }.join(', ')
            def settingWord = lostSkipped.size() == 1 ? "setting" : "settings"
            repairHints << "Some trigger ${settingWord} didn't land: ${skippedKeys}. Use hub_set_rule(walkStep={page:'selectTriggers', operation:'introspect'}) to see the live schema, then write the missing fields one at a time. CAVEAT: if the introspect call returns an empty schema for the missing field, that field is likely wizard-past-state (write-only during initial trigger construction, no longer in the live input list). Verify via hub_get_app_config(appId) -- if the trigger paragraph renders the value correctly (e.g. 'Certain Time 5:30 PM'), the partial flag is cosmetic and the trigger is fully baked. Skip the repair."
        }
        if (!forceWrittenKeys.isEmpty()) {
            def cmpWord = forceWrittenKeys.size() == 1 ? "Comparator" : "Comparators"
            def cmpVerb = forceWrittenKeys.size() == 1 ? "was" : "were"
            repairHints << "${cmpWord} ${forceWrittenKeys.join(', ')} ${cmpVerb} force-written via a degraded path after a transient re-fetch failure -- the value IS in settingsApplied and success stays true, but it could not be schema-confirmed. Verify via hub_get_app_config(appId): if the trigger paragraph renders the comparator correctly, the partial flag is cosmetic. Do NOT re-write -- only re-add via hub_set_rule(walkStep={...}) if the paragraph shows the comparator missing."
        }
        def notRepresentable = genuineSkipped.findAll { it instanceof Map && it.reason == "comparator_not_representable_for_enum_attribute" }
        notRepresentable.each { sk ->
            repairHints << _rmNotRepresentableEnumComparatorHint(
                (sk instanceof Map ? sk.attribute : null), (sk instanceof Map ? sk.value : null))
        }
        if (hasBrokenLabel) {
            repairHints << "Trigger row has *BROKEN* marker -- capability '${cap}' likely needs a capability-specific field (Mode: pass state='ModeName' or modeIds=['id'], NOT rawSettings.tstate; Periodic: pass periodic={} sub-spec). Re-add the trigger with the correct fields."
        }
        if ((health?.brokenMarkers as List)?.size() > 0 && !hasBrokenLabel) {
            repairHints << "Rule has pre-existing broken markers: ${(health.brokenMarkers as List).unique().join(', ')}. The new trigger committed, but run hub_get_rule_health(${appId}) and repair the existing broken trigger/action rows before this rule fires correctly."
        }
        if (skipped?.any { it?.reason == "periodic_href_unresolved_nonfirst_trigger" }) {
            repairHints << "The periodic sub-page index could not be resolved for this (non-first) trigger -- its href was absent from the trigger schema, or the href's params.n was malformed -- so its schedule was NOT written, because writing it would have overwritten an earlier periodic trigger's schedule. Retry the add: re-fetch with hub_set_rule(walkStep={page:'selectTriggers', operation:'introspect'}) to confirm the periodic href is present with an integer params.n, or rebuild the rule's triggers in order. If the rule has only one periodic trigger, this should not recur."
        }
        if (hubRenderError) {
            repairHints << "WARNING: selectTriggers may have rendered with an error (configPageError=${err}, or skipped items have empty available list). This is a hub-side issue. Consider deleting the trigger and trying with different deviceIds or trigger shape."
        }
    }
    // success=true when: the API call completed without an error AND at least
    // one setting was written to the rule (meaning the trigger skeleton
    // exists). partial=true is an orthogonal caller-actionable signal -- the
    // row exists but needs repair. Using !applied.isEmpty() rather than
    // !partial || !hasBrokenLabel means we no longer conflate "row needs
    // repair" with "the operation failed outright". Callers check success
    // for "did anything happen" then partial for "is more work needed."
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

// Return a structured schema Map describing every supported addTrigger capability.
// Called when addTrigger: {discover: true} is passed -- no hub mutation, no
// confirm/backup required. Content is sourced from the inline capability-family
// reference in the hub_set_rule tool description.
private Map _rmTriggerSchemaForDiscover() {
    return [
        discriminator: "capability",
        note: "Pass the chosen capability name (case-insensitive) as addTrigger.capability in the real call.",
        capabilities: [
            [
                name: "Switch",
                family: "device-state",
                requiredFields: [
                    [name: "deviceIds", type: "List<Integer>", description: "Switch device IDs"],
                    [name: "state", type: "enum", values: ["on", "off"], description: "Trigger condition"]
                ],
                optionalFields: [
                    [name: "allOfThese", type: "Boolean", description: "Require ALL selected devices to match (vs. any one)"],
                    [name: "andStays", type: "Map", description: "{hours?, minutes?, seconds?} -- only fire after state has held this long"],
                    [name: "conditional", type: "Boolean", description: "Make this a conditional trigger (sets isCondTrig)"],
                    [name: "condition", type: "Map", description: "Inline conditional-trigger gate -- same per-condition shape as addRequiredExpression conditions[]"],
                    [name: "rawSettings", type: "Map", description: "Escape hatch for fields not yet mapped"]
                ]
            ],
            [
                name: "Motion",
                family: "device-state",
                requiredFields: [
                    [name: "deviceIds", type: "List<Integer>", description: "Motion sensor device IDs"],
                    [name: "state", type: "enum", values: ["active", "inactive"], description: "Trigger condition"]
                ],
                optionalFields: [
                    [name: "allOfThese", type: "Boolean", description: "Require ALL selected devices to match"],
                    [name: "andStays", type: "Map", description: "{hours?, minutes?, seconds?}"],
                    [name: "conditional", type: "Boolean"],
                    [name: "condition", type: "Map"],
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "Contact",
                family: "device-state",
                requiredFields: [
                    [name: "deviceIds", type: "List<Integer>", description: "Contact sensor device IDs"],
                    [name: "state", type: "enum", values: ["open", "closed"], description: "Trigger condition"]
                ],
                optionalFields: [
                    [name: "allOfThese", type: "Boolean"],
                    [name: "andStays", type: "Map", description: "{hours?, minutes?, seconds?}"],
                    [name: "conditional", type: "Boolean"],
                    [name: "condition", type: "Map"],
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "Lock",
                family: "device-state",
                requiredFields: [
                    [name: "deviceIds", type: "List<Integer>", description: "Lock device IDs"],
                    [name: "state", type: "enum", values: ["locked", "unlocked"], description: "Trigger condition"]
                ],
                optionalFields: [
                    [name: "allOfThese", type: "Boolean"],
                    [name: "andStays", type: "Map", description: "{hours?, minutes?, seconds?}"],
                    [name: "conditional", type: "Boolean"],
                    [name: "condition", type: "Map"],
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "Presence",
                family: "device-state",
                requiredFields: [
                    [name: "deviceIds", type: "List<Integer>", description: "Presence sensor device IDs"],
                    [name: "state", type: "enum", values: ["present", "not present"], description: "Trigger condition"]
                ],
                optionalFields: [
                    [name: "allOfThese", type: "Boolean"],
                    [name: "andStays", type: "Map", description: "{hours?, minutes?, seconds?}"],
                    [name: "conditional", type: "Boolean"],
                    [name: "condition", type: "Map"],
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "Garage",
                family: "device-state",
                aliases: ["Door", "Valve", "Window Shade"],
                notes: "Also covers Door, Valve, Window Shade -- pass any of these names as capability; use appropriate state values for each device type.",
                requiredFields: [
                    [name: "deviceIds", type: "List<Integer>"],
                    [name: "state", type: "enum", values: ["open", "closed"], description: "Trigger condition"]
                ],
                optionalFields: [
                    [name: "allOfThese", type: "Boolean"],
                    [name: "andStays", type: "Map"],
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "Power source",
                family: "device-state",
                requiredFields: [
                    [name: "deviceIds", type: "List<Integer>"],
                    [name: "state", type: "enum", values: ["battery", "dc", "mains", "unknown"], description: "Trigger condition"]
                ],
                optionalFields: [
                    [name: "allOfThese", type: "Boolean"],
                    [name: "andStays", type: "Map"],
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "Temperature",
                family: "numeric",
                requiredFields: [
                    [name: "deviceIds", type: "List<Integer>"],
                    [name: "comparator", type: "enum", values: ["=", "<", ">", "<=", ">=", "*changed*"]],
                    [name: "value", type: "Number", description: "Temperature threshold (omit for *changed*)"]
                ],
                optionalFields: [
                    [name: "andStays", type: "Map"],
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "Humidity",
                family: "numeric",
                requiredFields: [
                    [name: "deviceIds", type: "List<Integer>"],
                    [name: "comparator", type: "enum", values: ["=", "<", ">", "<=", ">=", "*changed*"]],
                    [name: "value", type: "Number"]
                ],
                optionalFields: [
                    [name: "andStays", type: "Map"],
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "Battery",
                family: "numeric",
                requiredFields: [
                    [name: "deviceIds", type: "List<Integer>"],
                    [name: "comparator", type: "enum", values: ["=", "<", ">", "<=", ">=", "*changed*"]],
                    [name: "value", type: "Number"]
                ],
                optionalFields: [
                    [name: "andStays", type: "Map"],
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "Illuminance",
                family: "numeric",
                requiredFields: [
                    [name: "deviceIds", type: "List<Integer>"],
                    [name: "comparator", type: "enum", values: ["=", "<", ">", "<=", ">=", "*changed*"]],
                    [name: "value", type: "Number"]
                ],
                optionalFields: [
                    [name: "andStays", type: "Map"],
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "Power",
                family: "numeric",
                notes: "Also covers Energy, CO2, Dimmer level, Thermostat setpoints.",
                requiredFields: [
                    [name: "deviceIds", type: "List<Integer>"],
                    [name: "comparator", type: "enum", values: ["=", "<", ">", "<=", ">=", "*changed*"]],
                    [name: "value", type: "Number"]
                ],
                optionalFields: [
                    [name: "andStays", type: "Map"],
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "Button",
                family: "button",
                requiredFields: [
                    [name: "deviceIds", type: "List<Integer>", description: "Button-capable device IDs"],
                    [name: "buttonNumber", type: "Integer"],
                    [name: "state", type: "enum", values: ["pushed", "held", "doubleTapped", "released"]]
                ],
                optionalFields: [
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "Custom Attribute",
                family: "custom-attribute",
                requiredFields: [
                    [name: "deviceIds", type: "List<Integer>"],
                    [name: "attribute", type: "String", description: "The attribute name on the device"],
                    [name: "comparator", type: "enum", values: ["=", "<", ">", "<=", ">=", "*changed*"]],
                    [name: "value", type: "String or Number"]
                ],
                optionalFields: [
                    [name: "andStays", type: "Map"],
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "Certain Time (and optional date)",
                family: "time",
                requiredFields: [
                    [name: "time", type: "enum", values: ["A specific time", "Sunrise", "Sunset"]]
                ],
                optionalFields: [
                    [name: "atTime", type: "String", description: "ISO 8601 datetime (e.g. '2026-04-28T08:00:00') -- required when time='A specific time'; omit for Sunrise/Sunset. Simple forms without timezone use hub local tz."],
                    [name: "offset", type: "Integer", description: "Minutes offset for Sunrise/Sunset -- omit when time='A specific time'"]
                ],
                notes: "atTime is conditionally required: only when time='A specific time'. offset is only applicable for Sunrise/Sunset."
            ],
            [
                name: "Periodic Schedule",
                family: "time",
                requiredFields: [
                    [name: "periodic", type: "Map", description: "{frequency, everyN?, startingTime?, weekdaysOnly?, selectedHours?, selectedMinutes?, selectedDaysOfMonth?, daysOfWeek?, dayOfWeek?, dayOfMonth?, everyNMonths?, months?, weekOfMonth?, minutesOffset?, cronString?, rawSettings?}"]
                ],
                notes: "frequency values: 'Seconds', 'Minutes', 'Hourly', 'Daily', 'Weekly', 'Monthly', 'Yearly', 'Cron String'. everyN is REQUIRED even when =1 for Daily AND Hourly -- omitting it renders 'null'. For Seconds and Minutes, everyN is a restricted enum -- one of [1,2,3,4,5,6,10,12,15,20,30] and must be a whole number (firmware-imposed; Hourly/Daily accept any positive integer. Fractional values truncate, e.g. 5.5 -> 5; anything outside the set fails). Seconds exposes ONLY the count enum -- no toggle and no startingTime. For Hourly-everyN, pass startingTime too -- omitting it renders a cosmetic trailing 'starting at ' blank. Monthly has TWO mutually-exclusive modes: by-day (dayOfMonth + everyNMonths) and nth-weekday (weekOfMonth + dayOfWeek + everyNMonths) -- passing both dayOfMonth and weekOfMonth is rejected. Monthly by-day needs BOTH dayOfMonth AND everyNMonths or it renders 'null'. Monthly 'specific months' ('on day N of selected months') is NOT yet supported (an order-sensitive third sub-mode) -- use rawSettings if needed. Yearly is ALWAYS nth-weekday: weekOfMonth + dayOfWeek + months (single month) -- because RM 5.1 exposes no by-day calendar-day field for Yearly, only the nth-weekday picker; the month alone never completes. Without periodic the trigger renders as description='?'. The helper walks the periodic sub-page automatically.",
                periodicShape: [
                    frequency: "Seconds | Minutes | Hourly | Daily | Weekly | Monthly | Yearly | Cron String",
                    everyN: "Integer -- 'every N <unit>' mode (Seconds/Minutes/Hourly/Daily). REQUIRED even when =1 for Daily AND Hourly (omitting renders null). For Seconds/Minutes restricted to a whole number in [1,2,3,4,5,6,10,12,15,20,30] (firmware-imposed; Hourly/Daily accept any positive integer. Fractional values truncate, e.g. 5.5 -> 5).",
                    startingTime: "HH:mm -- start time (Hourly/Daily/Weekly/Monthly/Yearly; Seconds has none). For Hourly-everyN, pass it -- omitting renders a cosmetic trailing 'starting at ' blank.",
                    weekdaysOnly: "Boolean -- Daily only",
                    selectedHours: "List<Integer> -- Hourly only, alternative to everyN",
                    selectedMinutes: "List<Integer> -- Minutes only, 'at specific minutes' mode, alternative to everyN",
                    selectedDaysOfMonth: "List<Integer> -- Daily only, alternative to everyN/weekdays",
                    daysOfWeek: "List<String> -- Weekly only, MULTI day-of-week (e.g. ['Monday','Friday'])",
                    dayOfWeek: "String -- Monthly/Yearly nth-weekday mode, SINGLE day-of-week (e.g. 'Monday'). Distinct from Weekly's daysOfWeek (multi).",
                    dayOfMonth: "Integer -- Monthly by-day 'on day number' (pair with everyNMonths; mutually exclusive with weekOfMonth)",
                    everyNMonths: "Integer -- 'of every N months' (Monthly, both modes; free integer)",
                    months: "String -- Yearly only; the single nth-weekday month (e.g. 'December'). Yearly exposes a single-month picker. (Monthly does NOT take months -- its specific-months sub-mode is unsupported.)",
                    weekOfMonth: "String -- Monthly/Yearly nth-weekday 'in the week of month' (First | Second | Third | Fourth | Last). Its presence selects nth-weekday mode.",
                    minutesOffset: "Integer -- Hourly only, when not using everyN",
                    cronString: "String -- Cron String mode (e.g. '0 * * * *')",
                    rawSettings: "Map -- escape hatch for periodic-page fields not yet mapped"
                ]
            ],
            [
                name: "Mode",
                family: "hub-state",
                requiredFields: [
                    [name: "state", type: "String or List<String>", description: "Mode name(s) that trigger the rule (e.g. 'Night' or ['Away', 'Night']). Names are resolved to IDs via location.modes -- must match existing hub modes (case-insensitive). Use hub_list_modes to list available modes and their IDs. Required unless modeIds is provided."]
                ],
                optionalFields: [
                    [name: "modeIds", type: "List<String or Integer>", description: "Mode IDs directly (alternative to state). Use when you already have the ID from hub_list_modes. E.g. ['3'] or ['3', '5']. When provided, state is not required."]
                ],
                notes: "No deviceIds required. Triggers when hub mode becomes any of the listed modes. IMPORTANT: internally writes modesX<N> (not tstate<N>) -- passing only rawSettings with tstate will produce a broken trigger."
            ],
            [
                name: "Variable",
                family: "variable",
                requiredFields: [
                    [name: "variable", type: "String", description: "Hub variable name. Use hub_list_variables to discover."]
                ],
                optionalFields: [
                    [name: "comparator", type: "enum", values: ["=", "!= (or ≠)", "<", ">", "<=", ">=", "in", "*changed*", "*increased*", "*decreased*", "*increased by over*", "*decreased by over*"], description: "Default: *changed*. ASCII '!=' / '<>' map to Unicode '≠'."],
                    [name: "value", type: "Number or String", description: "Comparison value (omit for *changed* family)"],
                    [name: "conditional", type: "Boolean"],
                    [name: "condition", type: "Map", description: "Inline conditional gate. For 'Variable A changed ONLY IF A != B': {capability: 'Variable', variable: 'A', comparator: '!=', compareToVariable: 'B'}. condition fields: capability, variable, comparator, value | compareToVariable, not?, rawSettings?."],
                    [name: "rawSettings", type: "Map", description: "Escape hatch -- '@N' token in field names is replaced with the trigger index (e.g. {'xVar@N': 'myVar'})."]
                ],
                notes: "No deviceIds. Fires on hub-variable change. Conditional Variable triggers use condition.compareToVariable for 'A vs B' comparisons."
            ]
        ]
    ]
}

// Return a structured schema Map describing every supported addAction capability.
// Called when addAction: {discover: true} is passed -- no hub mutation, no
// confirm/backup required. Content is sourced from the inline capability-family
// reference in the hub_set_rule tool description.
private Map _rmActionSchemaForDiscover() {
    return [
        discriminator: "capability",
        note: "Pass the chosen capability name (case-insensitive) as addAction.capability in the real call. 'action' is required for multi-variant capabilities (switch, dimmer, etc.); optional or absent for single-variant ones (log, mode, delay, etc.).",
        capabilities: [
            [
                name: "switch",
                family: "device",
                actions: ["on", "off", "toggle", "flash", "setPerMode", "choosePerMode"],
                requiredFields: [
                    [name: "action", type: "enum", values: ["on", "off", "toggle", "flash", "setPerMode", "choosePerMode"]],
                    [name: "deviceIds", type: "List<Integer>", description: "Required for on/off/toggle/flash/setPerMode/choosePerMode"]
                ],
                optionalFields: [
                    [name: "perMode", type: "Map", description: "For setPerMode: {modeIdOrName: 'on'|'off', ...}. For choosePerMode: {modeIdOrName: {on: [devIds], off: [devIds]}, ...}"],
                    [name: "delay", type: "Map", description: "{hours?, minutes?, seconds?, cancelable?}"],
                    [name: "rawSettings", type: "Map"]
                ],
                notes: "flash starts a flash schedule; use capability='runCommand' with command='flashOff' to stop it.",
                conditionalRequired: [
                    setPerMode: "perMode",
                    choosePerMode: "perMode"
                ]
            ],
            [
                name: "dimmer",
                family: "device",
                actions: ["setLevel", "toggle", "adjust", "fade", "stopFade", "startRaiseLower", "stopChanging", "setLevelPerMode"],
                requiredFields: [
                    [name: "action", type: "enum", values: ["setLevel", "toggle", "adjust", "fade", "stopFade", "startRaiseLower", "stopChanging", "setLevelPerMode"]],
                    [name: "deviceIds", type: "List<Integer>"]
                ],
                optionalFields: [
                    [name: "level", type: "Integer", description: "0-100, required for setLevel/toggle"],
                    [name: "adjustBy", type: "Integer", description: "-100..100, required for adjust"],
                    [name: "fadeSeconds", type: "Integer", description: "Optional for setLevel/toggle/adjust"],
                    [name: "targetLevel", type: "Integer", description: "Required for fade"],
                    [name: "minutes", type: "Integer", description: "Required for fade"],
                    [name: "direction", type: "enum", values: ["raise", "lower"], description: "Optional for fade and startRaiseLower -- defaults to lower (verified live: the action bakes without it)"],
                    [name: "intervalSeconds", type: "Integer", description: "Optional for fade"],
                    [name: "perMode", type: "Map", description: "For setLevelPerMode: {modeIdOrName: level, ...}"],
                    [name: "levelVariable", type: "String", description: "Hub variable name -- use instead of level for variable-sourced setLevel"],
                    [name: "delay", type: "Map"],
                    [name: "rawSettings", type: "Map"]
                ],
                conditionalRequired: [
                    setLevel: "level (or levelVariable for a variable-sourced level)",
                    toggle: "level (the on-level used when toggling from off)",
                    adjust: "adjustBy",
                    fade: "targetLevel + minutes",
                    setLevelPerMode: "perMode"
                ]
            ],
            [
                name: "color",
                family: "device",
                notes: "RGBW bulbs.",
                actions: ["setColor", "toggleColor", "setColorPerMode"],
                requiredFields: [
                    [name: "action", type: "enum", values: ["setColor", "toggleColor", "setColorPerMode"]],
                    [name: "deviceIds", type: "List<Integer>"]
                ],
                optionalFields: [
                    [name: "colorName", type: "String", description: "Color name (e.g. 'Red') -- required for setColor (or supply a custom HSV color via rawSettings) and for toggleColor"],
                    [name: "level", type: "Integer", description: "0-100, required for toggleColor"],
                    [name: "perMode", type: "Map", description: "For setColorPerMode: {modeIdOrName: {color: 'Red', level: 70}, ...}"],
                    [name: "delay", type: "Map"],
                    [name: "rawSettings", type: "Map"]
                ],
                conditionalRequired: [
                    setColor: "colorName (or a custom HSV color via rawSettings)",
                    toggleColor: "colorName + level",
                    setColorPerMode: "perMode"
                ]
            ],
            [
                name: "colorTemp",
                family: "device",
                actions: ["setColorTemp", "toggleColorTemp", "fadeColorTemp", "stopColorTempFade", "setColorTempPerMode"],
                requiredFields: [
                    [name: "action", type: "enum", values: ["setColorTemp", "toggleColorTemp", "fadeColorTemp", "stopColorTempFade", "setColorTempPerMode"]],
                    [name: "deviceIds", type: "List<Integer>"]
                ],
                optionalFields: [
                    [name: "kelvin", type: "Integer", description: "Color temperature in Kelvin -- required for setColorTemp and toggleColorTemp"],
                    [name: "targetKelvin", type: "Integer", description: "Target color temperature in Kelvin -- required for fadeColorTemp (NOT 'kelvin')"],
                    [name: "level", type: "Integer"],
                    [name: "minutes", type: "Integer", description: "Required for fadeColorTemp"],
                    [name: "direction", type: "enum", values: ["raise", "lower"], description: "Optional for fadeColorTemp -- defaults to lower"],
                    [name: "perMode", type: "Map", description: "For setColorTempPerMode: {modeIdOrName: {kelvin: 2700, level: 70}, ...}"],
                    [name: "delay", type: "Map"],
                    [name: "rawSettings", type: "Map"]
                ],
                conditionalRequired: [
                    setColorTemp: "kelvin",
                    toggleColorTemp: "kelvin",
                    fadeColorTemp: "targetKelvin + minutes",
                    setColorTempPerMode: "perMode"
                ]
            ],
            [
                name: "lock",
                family: "device",
                actions: ["lock", "unlock"],
                requiredFields: [
                    [name: "action", type: "enum", values: ["lock", "unlock"]],
                    [name: "deviceIds", type: "List<Integer>"]
                ],
                optionalFields: [
                    [name: "delay", type: "Map"],
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "thermostat",
                family: "device",
                requiredFields: [
                    [name: "deviceIds", type: "List<Integer>"]
                ],
                optionalFields: [
                    [name: "action", type: "String"],
                    [name: "mode", type: "String"],
                    [name: "fanMode", type: "String"],
                    [name: "heatingSetpoint", type: "Number"],
                    [name: "coolingSetpoint", type: "Number"],
                    [name: "adjustHeating", type: "Number"],
                    [name: "adjustCooling", type: "Number"],
                    [name: "delay", type: "Map"],
                    [name: "rawSettings", type: "Map"]
                ],
                notes: "Requires at least one setting (mode / fanMode / heatingSetpoint / adjustHeating / coolingSetpoint / adjustCooling) -- a thermostat action with deviceIds but no setting does not bake."
            ],
            [
                name: "shade",
                family: "device",
                actions: ["open", "close", "stop", "setPosition"],
                requiredFields: [
                    [name: "action", type: "enum", values: ["open", "close", "stop", "setPosition"]],
                    [name: "deviceIds", type: "List<Integer>"]
                ],
                optionalFields: [
                    [name: "position", type: "Integer", description: "0-100, required for setPosition"],
                    [name: "delay", type: "Map"],
                    [name: "rawSettings", type: "Map"]
                ],
                conditionalRequired: [
                    setPosition: "position"
                ]
            ],
            [
                name: "fan",
                family: "device",
                actions: ["setSpeed", "cycle"],
                requiredFields: [
                    [name: "action", type: "enum", values: ["setSpeed", "cycle"]],
                    [name: "deviceIds", type: "List<Integer>"]
                ],
                optionalFields: [
                    [name: "speed", type: "String", description: "low/med/high/auto/etc., required for setSpeed"],
                    [name: "delay", type: "Map"],
                    [name: "rawSettings", type: "Map"]
                ],
                conditionalRequired: [
                    setSpeed: "speed"
                ]
            ],
            [
                name: "button",
                family: "device",
                actions: ["push", "pushPerMode", "choosePerMode"],
                requiredFields: [
                    [name: "action", type: "enum", values: ["push", "pushPerMode", "choosePerMode"]],
                    [name: "deviceIds", type: "List<Integer>"]
                ],
                optionalFields: [
                    [name: "buttonNumber", type: "Integer", description: "Required for push and choosePerMode (pushPerMode carries the per-mode button numbers inside perMode)"],
                    [name: "perMode", type: "Map"],
                    [name: "rawSettings", type: "Map"]
                ],
                conditionalRequired: [
                    push: "buttonNumber",
                    pushPerMode: "perMode",
                    choosePerMode: "perMode + buttonNumber"
                ]
            ],
            [
                name: "runCommand",
                family: "device",
                notes: "Calls any device-driver command not exposed by higher-level capability mappings (e.g. flashOff, custom verbs).",
                requiredFields: [
                    [name: "command", type: "String", description: "Driver command name (e.g. 'flashOff', 'refresh', 'setLevel')"],
                    [name: "deviceIds", type: "List<Integer>"]
                ],
                optionalFields: [
                    [name: "capabilityFilter", type: "String", description: "Default 'Switch'"],
                    [name: "parameters", type: "List", description: "Parameter list. Each entry is {type, value} for a literal (e.g. {type: 'number', value: 75}) or {type, variable} for a hub-variable-sourced value (e.g. {type: 'number', variable: 'myVar'}). Type is lowercase: 'number', 'decimal', or 'string'. Variable-sourced params wire via uVar<P>/xVar<P> -- see docs/rm_wire_format.md for the wire sequence. Fails loud if the hub does not reveal the xVar<P> enum after enabling variable mode (returns success=false with a descriptive error)."],
                    [name: "useLastEventDevice", type: "Boolean"],
                    [name: "delay", type: "Map"],
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "mode",
                family: "hub",
                requiredFields: [],
                optionalFields: [
                    [name: "modeId", type: "Integer", description: "Mode ID -- provide this OR modeName (not both). When modeName is given, it is resolved to its ID before submission; unknown names fail fast with the valid mode list. Degenerate: if location.modes is empty, the error lists '(none -- hub returned no modes; verify hub state via hub_list_modes)'."],
                    [name: "modeName", type: "String", description: "Mode name (case-insensitive) -- provide this OR modeId (not both). Resolved to mode ID automatically. Use hub_list_modes to confirm available names."]
                ],
                notes: "Exactly one of modeId or modeName is required at call time."
            ],
            [
                name: "setVariable",
                family: "hub",
                notes: "Set a hub variable to a constant, copy it from another hub variable, read it from a device attribute, or compute it with structured variable math. Also accepted as capability='variable'. Exactly ONE source mode (value | sourceVariable | fromDevice | math) per action.",
                requiredFields: [
                    [name: "variable", type: "String", description: "Hub variable name to write (the target). Must be an existing hub variable name -- an unknown name is rejected before the hub write to prevent silent broken-action state."]
                ],
                optionalFields: [
                    [name: "value", type: "Number", description: "Numeric constant to assign -- provide exactly one of value, sourceVariable, fromDevice, or math. Requires a Number or Decimal target variable (rejected with success=false before the write otherwise -- RM renders numOp/valNumber only for numeric targets). String, boolean, and datetime targets are not supported via 'value'; use 'sourceVariable', or set those types via rawSettings."],
                    [name: "sourceVariable", type: "String", description: "Hub variable name to read from (the source) -- provide exactly one of value, sourceVariable, fromDevice, or math. Must be an existing hub variable name -- an unknown name is rejected before the hub write to prevent silent broken-action state. Schema-gated: the source-variable field is only revealed by RM after the numOp=variable write; fails loud (success=false) if the hub does not reveal it. See docs/rm_wire_format.md for the wire sequence."],
                    [name: "fromDevice", type: "Map", description: "Read the value from a device attribute: {deviceId: <Integer>, attribute: '<name>'} -- provide exactly one of value, sourceVariable, fromDevice, or math. Requires a Number or Decimal target variable. Maps to numOp='device attribute'.[[FLAT_TRIM]] RM does not offer the device-attribute source for String/Boolean/DateTime variables (rejected with success=false before the hub write). deviceId may be ANY hub device (RM's device picker spans all hub devices, not just the MCP-selected set); it is validated only as a positive integer id, not against the MCP device set. The device picker and the attribute enum are schema-gated and revealed in sequence (deviceId reveals an attribute enum FILTERED to that device's live attributes); fails loud (success=false) if the device picker is not revealed. An attribute not in the device's filtered enum is rejected with success=false and the device's available-attribute list. See docs/rm_wire_format.md for the wire sequence.[[/FLAT_TRIM]]"],
                    [name: "math", type: "Map", description: "Compute the value with structured variable math: {left: <varName|Number>, op: '<operator>', right: <varName|Number>} -- provide exactly one of value, sourceVariable, fromDevice, or math. Requires a Number or Decimal target variable -- RM does not offer the variable-math source for String/Boolean/DateTime variables (rejected with success=false before the hub write). Maps to numOp='variable math'. A Number operand becomes a constant; a String operand is treated as a hub variable name. Binary operators (+ - * / %) require 'right'; unary operators (negate absolute round random sqrt sin cos tan asin acos atan log toRadians toDegrees) reject 'right'. Operand fields are schema-gated and revealed in sequence; fails loud (success=false) if a required field is not revealed. See docs/rm_wire_format.md for the wire sequence."],
                    [name: "delay", type: "Map"],
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "setLocalVariable",
                family: "hub",
                notes: "Set a rule-LOCAL variable (distinct from setVariable, which targets hub globals). Same source modes and wire path as setVariable; the only difference is that the target name is validated against the rule's local variables (state.allLocalVars). Use this -- not setVariable -- when a local and a hub variable share a name and you mean the local. Exactly ONE source mode (value | sourceVariable | fromDevice | math) per action.",
                requiredFields: [
                    [name: "variable", type: "String", description: "Local variable name to write (the target). Must be an existing local variable on this rule (create one first via hub_set_rule addLocalVariable) -- an unknown name is rejected before the hub write to prevent silent broken-action state."]
                ],
                optionalFields: [
                    [name: "value", type: "Number", description: "Numeric constant to assign -- provide exactly one of value, sourceVariable, fromDevice, or math. String, boolean, and datetime local-variable targets are not supported via 'value'; use 'sourceVariable', or set those types via rawSettings."],
                    [name: "sourceVariable", type: "String", description: "Variable name to read from (the source) -- provide exactly one of value, sourceVariable, fromDevice, or math. RM's source picker spans BOTH local and hub variables, so the source may be either; validated against the live revealed enum (fails loud, success=false, if the hub does not reveal it). See docs/rm_wire_format.md for the wire sequence."],
                    [name: "fromDevice", type: "Map", description: "Read the value from a device attribute: {deviceId: <Integer>, attribute: '<name>'} -- provide exactly one of value, sourceVariable, fromDevice, or math. Requires a Number or Decimal target variable (rejected with success=false before the hub write otherwise). Same wire and validation as setVariable's fromDevice."],
                    [name: "math", type: "Map", description: "Compute the value with structured variable math: {left: <varName|Number>, op: '<operator>', right: <varName|Number>} -- provide exactly one of value, sourceVariable, fromDevice, or math. Requires a Number or Decimal target variable (rejected with success=false before the hub write otherwise). Same operator set and wire as setVariable's math; operand variables may be local or hub (validated against the live revealed enum)."],
                    [name: "delay", type: "Map"],
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "log",
                family: "messaging",
                requiredFields: [
                    [name: "message", type: "String"]
                ],
                optionalFields: [
                    [name: "delay", type: "Map"],
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "notification",
                family: "messaging",
                requiredFields: [
                    [name: "deviceIds", type: "List<Integer>", description: "Notification device IDs"],
                    [name: "message", type: "String"]
                ],
                optionalFields: [
                    [name: "delay", type: "Map"],
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "httpGet",
                family: "messaging",
                requiredFields: [
                    [name: "url", type: "String"]
                ],
                optionalFields: [
                    [name: "delay", type: "Map"],
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "httpPost",
                family: "messaging",
                requiredFields: [
                    [name: "url", type: "String"],
                    [name: "body", type: "String"]
                ],
                optionalFields: [
                    [name: "contentType", type: "String"],
                    [name: "delay", type: "Map"],
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "ping",
                family: "messaging",
                requiredFields: [
                    [name: "ip", type: "String"]
                ],
                optionalFields: [
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "volume",
                family: "media",
                requiredFields: [
                    [name: "deviceIds", type: "List<Integer>"],
                    [name: "level", type: "Integer", description: "Volume level 0-100"]
                ],
                optionalFields: [
                    [name: "delay", type: "Map"],
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "mute",
                family: "media",
                actions: ["mute", "unmute"],
                requiredFields: [
                    [name: "action", type: "enum", values: ["mute", "unmute"]],
                    [name: "deviceIds", type: "List<Integer>"]
                ],
                optionalFields: [
                    [name: "delay", type: "Map"],
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "chime",
                family: "media",
                requiredFields: [
                    [name: "deviceIds", type: "List<Integer>"]
                ],
                optionalFields: [
                    [name: "playStop", type: "String"],
                    [name: "soundNumber", type: "Integer"],
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "siren",
                family: "media",
                requiredFields: [
                    [name: "deviceIds", type: "List<Integer>"]
                ],
                optionalFields: [
                    [name: "sirenAction", type: "String"],
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "privateBoolean",
                family: "rules",
                requiredFields: [
                    [name: "ruleIds", type: "List<Integer>"],
                    [name: "value", type: "Boolean"]
                ],
                optionalFields: [
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "runRule",
                family: "rules",
                requiredFields: [
                    [name: "ruleIds", type: "List<Integer>"]
                ],
                optionalFields: [
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "cancelTimers",
                family: "rules",
                requiredFields: [
                    [name: "ruleIds", type: "List<Integer>"]
                ],
                optionalFields: [
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "pauseRule",
                family: "rules",
                actions: ["pause", "resume"],
                requiredFields: [
                    [name: "action", type: "enum", values: ["pause", "resume"]],
                    [name: "ruleIds", type: "List<Integer>"]
                ],
                optionalFields: [
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "capture",
                family: "device-control",
                requiredFields: [
                    [name: "deviceIds", type: "List<Integer>"]
                ],
                optionalFields: [
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "restore",
                family: "device-control",
                notes: "No fields required -- restores previously captured device state.",
                requiredFields: [],
                optionalFields: [
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "refresh",
                family: "device-control",
                requiredFields: [
                    [name: "deviceIds", type: "List<Integer>"]
                ],
                optionalFields: [
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "poll",
                family: "device-control",
                requiredFields: [
                    [name: "deviceIds", type: "List<Integer>"]
                ],
                optionalFields: [
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "disableDevice",
                family: "device-control",
                actions: ["disable", "enable"],
                requiredFields: [
                    [name: "action", type: "enum", values: ["disable", "enable"]],
                    [name: "deviceIds", type: "List<Integer>"]
                ],
                optionalFields: [
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "delay",
                family: "flow",
                notes: "Pause execution for a fixed or variable duration.",
                requiredFields: [],
                optionalFields: [
                    [name: "hours", type: "Integer"],
                    [name: "minutes", type: "Integer"],
                    [name: "seconds", type: "Integer"],
                    [name: "cancelable", type: "Boolean"],
                    [name: "random", type: "Boolean"],
                    [name: "variable", type: "String", description: "Hub variable name -- use instead of hours/minutes/seconds for variable-sourced delay"]
                ]
            ],
            [
                name: "delayPerMode",
                family: "flow",
                requiredFields: [
                    [name: "perMode", type: "Map", description: "{modeIdOrName: {hours?, minutes?, seconds?}, ...}"]
                ],
                optionalFields: [
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "cancelDelay",
                family: "flow",
                notes: "No fields required.",
                requiredFields: [],
                optionalFields: []
            ],
            [
                name: "exitRule",
                family: "flow",
                notes: "No fields required.",
                requiredFields: [],
                optionalFields: []
            ],
            [
                name: "comment",
                family: "flow",
                requiredFields: [
                    [name: "text", type: "String"]
                ],
                optionalFields: []
            ],
            [
                name: "repeat",
                family: "flow",
                requiredFields: [
                    [name: "hours", type: "Integer", description: "At least one of hours/minutes/seconds required"],
                    [name: "minutes", type: "Integer"],
                    [name: "seconds", type: "Integer"]
                ],
                optionalFields: [
                    [name: "times", type: "Integer"],
                    [name: "stoppable", type: "Boolean"]
                ]
            ],
            [
                name: "stopRepeat",
                family: "flow",
                notes: "No fields required.",
                requiredFields: [],
                optionalFields: []
            ],
            [
                name: "repeatWhile",
                family: "flow",
                requiredFields: [
                    [name: "expression", type: "Map", description: "{conditions: [...], operator?: 'AND'|'OR'|'XOR', operators?: [...]}"]
                ],
                optionalFields: [
                    [name: "hours", type: "Integer"],
                    [name: "minutes", type: "Integer"],
                    [name: "seconds", type: "Integer"],
                    [name: "times", type: "Integer"],
                    [name: "stoppable", type: "Boolean"]
                ]
            ],
            [
                name: "waitExpression",
                family: "flow",
                requiredFields: [
                    [name: "expression", type: "Map", description: "{conditions: [...], operator?, operators?}"]
                ],
                optionalFields: [
                    [name: "delay", type: "Map", description: "{hours?, minutes?, seconds?}"],
                    [name: "useDuration", type: "Boolean"]
                ]
            ],
            [
                name: "waitEvents",
                family: "flow",
                requiredFields: [
                    [name: "events", type: "List<Map>", description: "Each: {capability, deviceIds, state, andStays?}"]
                ],
                optionalFields: [
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "ifThen",
                family: "flow",
                notes: "Opens an IF block. Close with capability='endIf'. Use 'elseIf'/'else' for branches.",
                requiredFields: [
                    [name: "expression", type: "Map", description: "{conditions: [...], operator?, operators?}"]
                ],
                optionalFields: [
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "elseIf",
                family: "flow",
                notes: "Continues an IF block. Needs a preceding ifThen.",
                requiredFields: [
                    [name: "expression", type: "Map"]
                ],
                optionalFields: [
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "else",
                family: "flow",
                notes: "No fields required. Needs a preceding ifThen or elseIf.",
                requiredFields: [],
                optionalFields: []
            ],
            [
                name: "endIf",
                family: "flow",
                notes: "No fields required. Closes the IF block.",
                requiredFields: [],
                optionalFields: []
            ],
            [
                name: "fileWrite",
                family: "file",
                requiredFields: [
                    [name: "fileName", type: "String"],
                    [name: "content", type: "String"]
                ],
                optionalFields: [
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "fileAppend",
                family: "file",
                notes: "File must already exist.",
                requiredFields: [
                    [name: "fileName", type: "String"],
                    [name: "content", type: "String"]
                ],
                optionalFields: [
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "fileDelete",
                family: "file",
                requiredFields: [
                    [name: "fileName", type: "String"]
                ],
                optionalFields: [
                    [name: "rawSettings", type: "Map"]
                ]
            ],
            [
                name: "zwavePoll",
                family: "device-control",
                actions: ["start", "stop"],
                requiredFields: [
                    [name: "action", type: "enum", values: ["start", "stop"]]
                ],
                optionalFields: [
                    [name: "deviceIds", type: "List<Integer>", description: "Z-Wave switches/dimmers to poll -- omit to poll all eligible devices"],
                    [name: "target", type: "enum", values: ["switches", "dimmers"], description: "Defaults to 'switches' when omitted"],
                    [name: "rawSettings", type: "Map"]
                ]
            ]
        ]
    ]
}

// Scan an RM rule's appSettings for tCapab<N> entries and return the
// Set of trigger indices currently in use. Used by _rmAddTrigger to pick
// the next free index without colliding with existing triggers.
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

// Scan an RM rule's appSettings for actType.<N> entries and return the
// list of action indices in DISPLAY ORDER. Used by _rmAddAction to pick
// the next free index and by _rmMoveAction to verify position shifts.
//
// Ordering strategy (two-tier):
// 1. If statusJson.actions is a Map with integer keys, use its key
// iteration order (Hubitat serializes this in display order).
// This is immune to the JSON key lexical-sort bug (actType.1,
// actType.10, actType.2) that appSettings scanning suffers from.
// 2. Fall back to the appSettings actType.<N> scan with lexical sort
// when statusJson.actions is absent. Lexical order matches display
// order at small action counts but may diverge above 10 actions
// (actType.10 sorts before actType.2 lexically); the tier-1
// statusJson.actions path is the authoritative source when available.
//
// Note action keys use a dot-N suffix (actType.1) vs trigger keys
// without dot (tCapab1).
private List _rmCollectActionIndices(Integer appId) {
    def status = _rmFetchStatusJson(appId)

    // Tier 1: statusJson.actions map (integer-keyed, display-ordered).
    def actionsMap = status?.actions
    if (actionsMap instanceof Map && !actionsMap.isEmpty()) {
        def ordered = []
        actionsMap.keySet().each { k ->
            try { ordered << k.toString().toInteger() } catch (NumberFormatException ignored) {}
        }
        return ordered
    }

    // Tier 2: lexical appSettings scan.
    def out = []
    (status?.appSettings ?: []).each { s ->
        def n = s?.name?.toString()
        if (n) {
            def m = (n =~ /^actType\.(\d+)$/)
            if (m.matches()) out << (m[0][1] as Integer)
        }
    }
    return out
}

// Delete a single action at a specific index. Verified live
// by inspecting the live UI HTML on selectActions: the per-row trash
// button has data-stateAttribute='delAct' and the form-input name is
// just the action index as a digit (e.g. name='2' for action 2). RM's
// appButtonHandler dispatches on the stateAttribute and uses the name
// to identify which action to delete.
//
// After delete, RM does NOT renumber remaining actions — indices are
// preserved with gaps. Subsequent addAction picks the next free index
// via _rmCollectActionIndices' max+1 logic.
//
// Pre-flight refuses three things before the delAct click goes out:
// (a) the requested index does not exist on the rule,
// (b) the rule has a stuck state.editAct from a prior interrupted
// edit (RM silently no-ops delAct clicks in that state), and
// (c) the action is a structural opener/closer (IF / ELSE-IF / ELSE /
// END-IF / Repeat / While / End-Repeat) and removing it would
// introduce a new structural-balance issue (compared as a set diff
// so deletions that merely improve an already-broken rule are
// still allowed). All three throw IllegalArgumentException /
// IllegalStateException — no RM mutation is sent.
//
// Post-click verification uses a retry loop to absorb RM 5.1's
// asynchronous button-handler dispatch. The click returns HTTP 200 before
// the deletion has propagated to appSettings, so a single immediate fetch
// can falsely report the action still present. Up to 4 attempts are made
// with increasing delays (1s, 3s, 6s) for a max sleep budget of 10s
// (wall-clock ~10.5s on the failure path including four HTTP fetches) and
// near-zero extra latency on the common success path (first check passes
// immediately). The 10s budget covers typical and slow hub propagation
// observed in live-hub runs; on retry exhaustion the throw directs the
// caller to verify via hub_get_app_config since the deletion may complete
// post-response. See source comment below for the original race description.
private Map _rmDeleteAction(Integer appId, Integer actionIdx) {
    // Single statusJson fetch shared by all three pre-flight checks below;
    // before the refactor each helper (_rmCollectActionIndices,
    // _rmGetStateEditAct, structural pre-flight) called _rmFetchStatusJson
    // independently, tripling the per-delete read load on the hub.
    def status = _rmFetchStatusJson(appId)
    def settingsByName = (status?.appSettings ?: []).collectEntries { [(it?.name?.toString()): it] }
    def beforeIndices = []
    def actionsMap = status?.actions
    if (actionsMap instanceof Map && !actionsMap.isEmpty()) {
        actionsMap.keySet().each { k ->
            try { beforeIndices << k.toString().toInteger() } catch (NumberFormatException ignored) {}
        }
    } else {
        settingsByName.keySet().each { n ->
            def m = (n =~ /^actType\.(\d+)$/)
            if (m.matches()) beforeIndices << (m[0][1] as Integer)
        }
    }
    if (!beforeIndices.contains(actionIdx)) {
        throw new IllegalArgumentException("removeAction.index ${actionIdx} not found in rule ${appId}. Existing indices: ${beforeIndices.sort().join(', ')}")
    }
    // Pre-flight: if state.editAct is set, RM will silently no-op the delAct
    // click. Detect and fail immediately with a descriptive error rather than
    // burning 10s of retries and surfacing a confusing timeout message.
    def editActEntry = (status?.appState ?: []).find { it?.name?.toString() == "editAct" }
    def stuckEditAct = editActEntry?.value
    if (stuckEditAct != null) {
        throw new IllegalStateException("removeAction(${actionIdx}) blocked: rule ${appId} has state.editAct=${stuckEditAct} set from a prior interrupted action edit. RM 5.1 silently no-ops delAct clicks until this clears. Recovery options: (a) wait ~60s for RM's stale-state timeout and retry, (b) try hub_set_rule(button='cancelAct', pageName='doActPage', confirm=true) to abort the in-flight edit (behavior unverified -- attempt at own risk), (c) hub_restore_backup with a recent snapshot.")
    }
    // Structural pre-flight: refuse if the deleted index is a block
    // opener/closer AND removing it would introduce a new imbalance not
    // present in the current rule. Set-diff (projected MINUS current)
    // rather than size-only — size comparison allows damage-shuffling on
    // already-broken rules (e.g. swapping "Repeat never closed" for
    // "END-IF closes a Repeat — mismatched" keeps the count flat). The
    // set diff still allows deletions that strictly improve a broken
    // rule, because every projected issue is also in current.
    def aType = settingsByName["actType.${actionIdx}".toString()]?.value?.toString()
    def sType = settingsByName["actSubType.${actionIdx}".toString()]?.value?.toString()
    def structuralSubTypes = ["getIfThen", "getElseIf", "getElse", "getEndIf", "getRepeat", "getWhile", "getStopRepeat"]
    if (aType in ["condActs", "repeatActs"] && sType in structuralSubTypes) {
        def currentIssues = _rmStructuralIssuesFromSequence(_rmStructuralSequenceFromSettings(settingsByName))
        def projectedIssues = _rmStructuralIssuesFromSequence(_rmStructuralSequenceFromSettings(settingsByName, ([actionIdx] as Set)))
        def newIssues = projectedIssues - currentIssues
        if (newIssues) {
            def humanKind = [
                "getIfThen": "IF",
                "getElseIf": "ELSE-IF",
                "getElse": "ELSE",
                "getEndIf": "END-IF",
                "getRepeat": "Repeat",
                "getWhile": "Repeat-While",
                "getStopRepeat": "End-Repeat"
            ][sType] ?: sType
            throw new IllegalArgumentException("removeAction(${actionIdx}) blocked: action ${actionIdx} is a structural ${humanKind}; removing it would introduce a new structural-balance issue (${newIssues.first()}). Remove the matching opener/closer first, or use replaceActions to rebuild the action list atomically. RM is not touched.")
        }
    }
    _rmClickAppButton(appId, actionIdx.toString(), "delAct", "selectActions")
    // Verify the action actually disappeared. RM 5.1 silently no-ops the
    // delAct click if the button-handler dispatch races with another edit.
    // state.editAct was clear at pre-flight, so the loop below absorbs the
    // two failure modes a clean 200 on the click can hide:
    //   (a) propagation lag -- the click committed but appSettings updates
    //       asynchronously (observed at several seconds in live repros);
    //   (b) a silently DROPPED first click -- RM 5.1 occasionally no-ops the
    //       first delAct entirely (live-measured 2/2 on a quiet hub,
    //       2026-06-12), so nothing ever propagates.
    // The old loop (1s/3s/6s backoff) treated both the same: it burned the
    // whole ~10s budget, pushed the op past the ~10s cloud-relay ceiling
    // (10.4s direct-timed on a QUIET hub, so every cloud caller got a 504),
    // and then threw -- while its own error text prescribed the verified
    // re-click performed below. Now: short early polls absorb (a); if the
    // index is STILL present at ~2.5s, that is the dropped-click signature
    // (b) -- re-click ONCE (the immediately-preceding presence check is the
    // verified-non-commit evidence that makes the second click duplicate-
    // safe) and keep polling. Success lands ~4-7s even on a dropped first
    // click, back under the relay ceiling.
    def afterIndices = null
    def reclicked = false
    def retryDelaysMs = [1000, 1500, 2000, 3000]
    for (int attempt = 0; attempt < 5; attempt++) {
        if (attempt > 0) pauseExecution(retryDelaysMs[attempt - 1] as Integer)
        afterIndices = _rmCollectActionIndices(appId)
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
    throw new IllegalStateException("removeAction(${actionIdx}): after the delAct click, one verified re-click, and ~8s of polling, action ${actionIdx} is still present in rule ${appId} (before: ${beforeIndices.sort().join(', ')}; after: ${afterIndices.sort().join(', ')}). state.editAct was clear at pre-flight and the re-click also failed to land. Recovery (verify-first, do NOT blind-retry): call hub_get_app_config(appId=${appId}) and check action ${actionIdx}. If still present -> safe to call removeAction(index:${actionIdx}) again. If gone -> a click committed late (rare extreme lag); the removal already succeeded, do not retry. Indices shift on deletion, so retrying without this check can delete the wrong action.")
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
private Map _rmRemoveTrigger(Integer appId, Integer triggerIdx) {
    def beforeIndices = _rmCollectTriggerIndices(appId)
    if (!beforeIndices.contains(triggerIdx)) {
        throw new IllegalArgumentException("removeTrigger.index ${triggerIdx} not found in rule ${appId}. Existing indices: ${beforeIndices.sort().join(', ')}")
    }
    // The trigger-row Delete button on selectTriggers requires a TWO-POST
    // sequence to actually delete (verified live via Chrome XHR
    // capture against rule 290 + direct curl reproduction):
    //
    //   POST 1 — /installedapp/btn — minimal body: id, name=<idx>,
    //            stateAttribute=deleteCon, settings[<idx>]=clicked,
    //            <idx>.type=button. NO formAction/version/currentPage.
    //            This registers the deletion intent. Returns 200
    //            {"status":"success"} on its own but does NOT commit.
    //
    //   POST 2 — /installedapp/update/json — page-save with form context:
    //            formAction=update, id, version, currentPage=selectTriggers,
    //            pageBreadcrumbs=["mainPage"]. Triggers the button-handler
    //            chain that processes the registered deletion intent.
    //
    // Including the form-context fields in POST 1 (the path
    // _rmClickAppButton takes when pageName is provided) makes RM treat
    // the click as a settings-save; the deletion intent gets dropped.
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
            pageBreadcrumbs: '["mainPage"]'
        ]
        if (cfgForVersion?.app?.version != null) commitBody.version = cfgForVersion.app.version.toString()
        try { hubInternalPostForm("/installedapp/update/json", commitBody) } catch (Exception postExc) {
            mcpLog("warn", "rm-native", "_rmRemoveTrigger: page-save commit failed for app ${appId} trigger ${triggerIdx} (${postExc.message}) -- the deletion may not have committed; the verify retry loop below will catch it.")
        }
    }
    fireDeleteSequence()
    // Verify the trigger actually disappeared -- same two failure modes and same
    // verified re-click recovery as _rmDeleteAction's delAct (see the comment
    // there): short early polls absorb async propagation lag; an index still
    // present at ~2.5s is the silently-DROPPED-first-click signature, so the
    // full click+commit sequence re-fires ONCE (the immediately-preceding
    // presence check is the verified-non-commit evidence that makes it
    // duplicate-safe), keeping the op under the ~10s cloud-relay ceiling.
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
    throw new IllegalStateException("removeTrigger(${triggerIdx}): after the delete click, one verified re-click, and ~8s of polling, trigger ${triggerIdx} is still present in rule ${appId} (before: ${beforeIndices.sort().join(', ')}; after: ${afterIndices.sort().join(', ')}). Recovery (verify-first, do NOT blind-retry): call hub_get_app_config(appId=${appId}) and check trigger ${triggerIdx}. If still present -> safe to call removeTrigger(index:${triggerIdx}) again. If gone -> a click committed late (rare); the removal already succeeded, do not retry. If a retry also fails, hub_restore_backup to the pre-operation snapshot. Indices shift on deletion, so retrying without this check can delete the wrong trigger.")
}

// Modify a single trigger's state field by opening the editCond wizard,
// writing the new tstate<N> value, and committing via hasAll.
//
// Wire format verified from RM 5.1's selectTriggers page: the per-row edit
// button has data-stateAttribute='editCond' and name=<idx>. After the click,
// the wizard exposes tstate<N> for device-state triggers (among other fields).
// The hasAll button commits the in-flight edit.
//
// Scope: only state-value changes (mods.state) are supported. Capability and
// deviceIds changes require removeTrigger + addTrigger because RM's wizard
// does not expose a capability-change path in an existing trigger slot.
private Map _rmModifyTrigger(Integer appId, Integer triggerIdx, Map mods) {
    def unsupported = mods.keySet() - ["state"]
    if (unsupported) {
        throw new IllegalArgumentException("modifyTrigger currently only supports changing the trigger's state field. Unsupported fields: ${unsupported.sort().join(', ')}. To change capability or deviceIds, use removeTrigger + addTrigger.")
    }
    if (!mods.containsKey("state")) {
        throw new IllegalArgumentException("modifyTrigger.mods must include 'state'. Supported fields: state.")
    }
    def existingIndices = _rmCollectTriggerIndices(appId)
    if (!existingIndices.contains(triggerIdx)) {
        throw new IllegalArgumentException("modifyTrigger.index ${triggerIdx} not found in rule ${appId}. Existing indices: ${existingIndices.sort().join(', ')}")
    }
    // Open the edit-condition wizard for this trigger.
    _rmClickAppButton(appId, triggerIdx.toString(), "editCond", "selectTriggers")
    // Write the modified state field through the schema-aware helper.
    // applied/skipped track what was written vs. silently bypassed.
    def applied = []
    def skipped = []
    _rmWriteSettingOnPage(appId, "selectTriggers", "tstate${triggerIdx}", mods.state, applied, null, skipped)
    // Guard: if tstate<N> was not in the schema (e.g. Time or Periodic triggers
    // have no state field), abort before committing via hasAll. Committing an
    // empty wizard edit on a trigger that never accepted the write could leave
    // the rule in an inconsistent state. The agent gets a descriptive error
    // directing them to use removeTrigger + addTrigger instead.
    def tstateName = "tstate${triggerIdx}".toString()
    def schemaSkipped = skipped.find { it instanceof Map && it.key == tstateName && it.reason == "not_in_schema" }
    if (schemaSkipped) {
        throw new IllegalArgumentException(
            "modifyTrigger: trigger ${triggerIdx} does not expose a 'state' field on selectTriggers schema. " +
            "Time and Periodic triggers do not have a state value (they fire on schedule, not on a state change). " +
            "Workaround: removeTrigger + addTrigger to reconfigure with the new shape."
        )
    }
    // Commit the in-flight edit via hasAll on selectTriggers.
    _rmClickAppButton(appId, "hasAll", null, "selectTriggers")
    // Post-commit verification: read the new state back to confirm the write
    // persisted. RM may silently accept a write but not persist it if the
    // wizard is in an unexpected state, so verificationFetchFailed lets the
    // caller fall back to hub_get_app_config rather than assuming success. The
    // readback source is the persisted configure/json settings (see the next
    // comment) -- NOT the post-commit selectTriggers wizard page.
    def verifiedState = null
    def verificationFetchFailed = false
    try {
        // Read the PERSISTED tstate from the rule's configure/json settings
        // (the same ground-truth surface hub_get_app_config reports), NOT the
        // post-commit selectTriggers wizard page: once hasAll closes the
        // trigger editor, selectTriggers no longer exposes a populated
        // tstate<N> input, so the old schema readback always echoed null even
        // on a successful modify (the change had really landed).
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

// Delete every action on a rule via the trashAll → trashActs flow.
// Verified live:
//
// 1. Click button name='trashAll' stateAttribute='trash'. RM enters
// trash-confirmation mode and exposes a `trashActs` multi-enum
// whose options are the current action indices.
// 2. Commit settings[trashActs]=<indices-as-JSON-array> as a FULL page-form
// submit (the complete form-action envelope + serialized selectActions
// page state, mirroring the native UI). trashActs has submitOnChange=true,
// and the full submit makes RM run that handler synchronously during the
// page re-render, so the selected actions are deleted by the time the POST
// returns. A bare settings-write of trashActs stores the value but never
// runs the handler, stranding the delete.
//
// Per-row delAct buttons get hidden when only one action remains, so
// per-row iteration leaves the final action stuck. The trashActs enum
// path handles any count including deleting down to zero.
//
// Returns the list of indices that were marked for deletion.
private List _rmClearActions(Integer appId) {
    def indices = _rmCollectActionIndices(appId)
    if (!indices) return []
    // The page can be in two states:
    //   normal: trashAll button visible, trashActs NOT in schema yet.
    //   trash-confirmation: trashActs visible, cancelTrash + runAction
    //                       buttons (no trashAll). We can land here if a
    //                       previous clearActions attempt clicked trashAll
    //                       but failed to write trashActs.
    // Inspect the schema first so we don't double-toggle.
    def cfg = _rmFetchConfigJson(appId, "selectActions")
    def schema = _rmCollectInputSchema(cfg?.configPage)
    if (!schema?.containsKey("trashActs")) {
        // Normal state -- click trashAll to enter trash-confirmation mode.
        _rmClickAppButton(appId, "trashAll", "trash", "selectActions")
        cfg = _rmFetchConfigJson(appId, "selectActions")
        schema = _rmCollectInputSchema(cfg?.configPage)
    }
    if (!schema?.containsKey("trashActs")) {
        // RM didn't enter trash mode after the trashAll click. Don't pretend
        // success -- caller (patches handler, replaceActions, etc.) needs to
        // know nothing was deleted so success aggregation is correct.
        def actCountMsg = "${indices.size()} ${indices.size() == 1 ? 'action' : 'actions'}"
        throw new IllegalStateException("clearActions: trashActs not in selectActions schema after trashAll click for app ${appId} -- RM didn't enter trash mode. The rule has ${actCountMsg} at indices ${indices.sort()} that were NOT deleted.")
    }
    // Commit trashActs as a full page-form submit so RM runs its
    // submitOnChange handler synchronously during the re-render. The handler
    // is what actually deletes the selected actions; a bare settings-write
    // stores the value but never runs it, stranding the delete.
    def stringIndices = indices.collect { it.toString() }
    _rmSubmitFullPageForm(appId, "selectActions", cfg, schema, (cfg?.settings ?: [:]),
        ["trashActs": stringIndices])
    // Post-condition check -- verify the actions actually disappeared. With the
    // synchronous full-form submit the deletion has committed by the time the
    // POST returns, so the FIRST check is expected to pass in the common case.
    // The retry loop is a thin defensive net for two residual edge cases:
    //   (a) RM has been observed to no-op the trashActs write entirely when
    //       state.editAct is set, leaving the rule fully unchanged. This is a
    //       hard failure the retries won't fix, but distinguishing it from (b)
    //       requires the verification fetch either way.
    //   (b) a genuinely-rare firmware/timing edge where the handler's write to
    //       appSettings lags the POST return. Empirically uncommon now that the
    //       submitOnChange handler runs in-band; the backoff (1s, 3s, 6s)
    //       absorbs it without adding latency on the common success path.
    // If the first fetch shows clean, return immediately; only after 4 attempts
    // do we throw (real (a)-class failure or the rare residual lag).
    def remaining = null
    def stillThere = null
    def retryDelaysMs = [1000, 3000, 6000]
    for (int attempt = 0; attempt < 4; attempt++) {
        if (attempt > 0) pauseExecution(retryDelaysMs[attempt - 1] as Integer)
        remaining = _rmCollectActionIndices(appId)
        stillThere = remaining.intersect(indices)
        if (!stillThere) return indices
    }
    def stillThereCount = stillThere.size()
    def stillThereWord = stillThereCount == 1 ? "action" : "actions"
    // asyncCommit marker tells the dispatcher catch that this is the
    // verification-window-exhausted case (HTTP 200 + retries exhausted), not a
    // hard wizard / schema failure. With synchronous commit this path should
    // almost never fire -- it stays as a defensive net for the two residual
    // edge cases above. Distinct from the wizardStuck marker pattern but
    // follows the same precedent of encoding a recoverable-shape hint in the
    // exception message. Single source of truth: getAsyncCommitMarker() --
    // throw and strip sites must agree.
    throw new IllegalStateException("clearActions: trashActs submit returned 200 but actions ${stillThere.sort()} still present on rule ${appId} after 10s of retries. Likely either state.editAct is set (use hub_set_rule(button='cancelAct', pageName='doActPage', confirm=true) to clear) or a rare RM commit lag. Verify via hub_get_app_config(appId=${appId}) before retrying -- the deletion may commit post-response. Roll back via hub_restore_backup if the ${stillThereWord} really did get clobbered. Note: do NOT use hub_set_rule(button='cancelTrash') as a recovery -- in trash-confirmation mode that button may commit pending deletes rather than abort, potentially wiping additional actions.${getAsyncCommitMarker()}")
}

// Move an action one slot in the given direction. Verified live
// 2026-04-25: per-row up/down arrows have data-stateAttribute='arrowUp'
// / 'arrowDn' and use the action index as the button name (same
// convention as delAct). Returns the request response status.
//
// Note: RM may renumber actions when moves cross gaps — caller should
// re-collect indices via _rmCollectActionIndices if subsequent moves
// depend on positions.
private Map _rmMoveAction(Integer appId, Integer actionIdx, String direction) {
    def stateAttr = direction == "up" ? "arrowUp" : (direction == "down" ? "arrowDn" : null)
    if (!stateAttr) throw new IllegalArgumentException("moveAction direction must be 'up' or 'down'")
    // Capture pre-move ORDERING (insertion order from appSettings, which RM
    // updates on every shuffle to reflect display order). The unsorted list
    // lets us verify the action's POSITION moved, not just that the index
    // set is unchanged -- a mid-list silent no-op leaves both before- and
    // after-sets identical AND positions identical, but a real move shifts
    // position by exactly one slot.
    def beforeOrderRaw = _rmCollectActionIndices(appId)
    if (!beforeOrderRaw.contains(actionIdx)) {
        throw new IllegalArgumentException("moveAction.index ${actionIdx} not found in rule ${appId}. Existing indices: ${beforeOrderRaw.sort().join(', ')}")
    }
    // Pre-flight: if state.editAct is set, RM will silently no-op the move-arrow
    // click. Detect and fail immediately with a descriptive error rather than
    // burning a slow position-shift check and surfacing a confusing failure.
    // Placed after the index-existence check (mirrors _rmDeleteAction ordering):
    // an invalid index gets the more actionable IllegalArgumentException first.
    def stuckEditAct = _rmGetStateEditAct(appId)
    if (stuckEditAct != null) {
        throw new IllegalStateException("moveAction(${actionIdx}, ${direction}) blocked: rule ${appId} has state.editAct=${stuckEditAct} set from a prior interrupted action edit. RM 5.1 silently no-ops move-arrow clicks until this clears. Recovery options: (a) wait ~60s for RM's stale-state timeout and retry, (b) try hub_set_rule(button='cancelAct', pageName='doActPage', confirm=true) to abort the in-flight edit (behavior unverified -- attempt at own risk), (c) hub_restore_backup with a recent snapshot.")
    }
    def beforePosition = beforeOrderRaw.indexOf(actionIdx)
    def isBoundary = (direction == "up" && beforePosition == 0) ||
                     (direction == "down" && beforePosition == beforeOrderRaw.size() - 1)
    _rmClickAppButton(appId, actionIdx.toString(), stateAttr, "selectActions")
    def afterOrderRaw = _rmCollectActionIndices(appId)
    def afterPosition = afterOrderRaw.indexOf(actionIdx)
    def cfg = null
    try { cfg = _rmFetchConfigJson(appId, "selectActions") }
    catch (Exception verifyExc) {
        mcpLog("warn", "rm-native", "moveAction: post-click selectActions fetch failed for app ${appId} (${verifyExc.message}) -- render-error check skipped, action ordering may have left the page in an inconsistent state")
    }
    def renderError = cfg?.configPage?.error
    if (renderError) {
        throw new IllegalStateException("moveAction(${actionIdx}, ${direction}): click returned 200 but selectActions render now errors: ${renderError}")
    }
    // Verify position actually shifted, except at boundaries (RM correctly
    // no-ops a move-up at position 0 or move-down at the last slot).
    if (!isBoundary) {
        def expectedShift = direction == "up" ? -1 : 1
        def actualShift = afterPosition - beforePosition
        // The move-arrow click can commit slightly AFTER the immediate re-read
        // (observed live on a slow hub: the position flips a couple seconds
        // post-click, so the first check still sees the pre-move order). Do ONE
        // short re-check to catch a fast-late commit, kept well under the cloud
        // relay's ~10s window so the call itself does not 504 (a long in-handler
        // poll would). If it STILL has not shifted, return a SOFT
        // asyncCommitLikely result (mirroring clearActions/replaceActions)
        // rather than a hard throw: a late commit reported as a hard failure is
        // a false-negative that tempts a caller into a second move that
        // double-shifts the action.
        if (actualShift != expectedShift) {
            pauseExecution(1500)
            // The re-check read is the one fetch in this otherwise verify-tolerant
            // path; if it throws (transient relay flake), treat the move as
            // unconfirmed -- fall through to the soft asyncCommitLikely return
            // rather than hard-throwing out of the function.
            try {
                afterOrderRaw = _rmCollectActionIndices(appId)
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
private Map _rmNavigateToPage(Integer appId, String fromPage, String targetPage, Integer hrefIndex = 0, String hrefName = "name", Map hrefParams = null) {
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
    def actionMarker = "_action_href_${hrefName}|${targetPage}|${hrefIndex}".toString()
    def body = [
        id: appId.toString(),
        formAction: "update",
        currentPage: fromPage,
        pageBreadcrumbs: '["mainPage"]',
        (actionMarker): ""
    ]
    if (hrefParams != null && !hrefParams.isEmpty()) {
        def paramsMarker = "params_for_action_href_${hrefName}|${targetPage}|${hrefIndex}".toString()
        body[paramsMarker] = groovy.json.JsonOutput.toJson(hrefParams)
    }
    try {
        def cfg = _rmFetchConfigJson(appId, fromPage)
        def v = cfg?.app?.version
        if (v != null) body.version = v.toString()
    } catch (Exception versionExc) {
        mcpLog("debug", "rm-native", "_rmNavigateToPage: version fetch on ${fromPage} failed for app ${appId} (${versionExc.message}) -- sending POST without version")
    }
    try {
        def resp = hubInternalPostForm("/installedapp/update/json", body)
        if (resp?.data) {
            try {
                return new groovy.json.JsonSlurper().parseText(resp.data) as Map
            } catch (Exception parseExc) {
                mcpLog("debug", "rm-native", "_rmNavigateToPage: ${fromPage}→${targetPage} response wasn't JSON (${parseExc.message}) -- caller will plain-fetch the schema")
            }
        }
    } catch (Exception postExc) {
        mcpLog("warn", "rm-native", "_rmNavigateToPage: ${fromPage}→${targetPage} POST failed for app ${appId}: ${postExc.message} -- downstream 'X not in schema' errors likely point at this")
    }
    return null
}

// Rebuild a name->value map of an app's live settings from statusJson
// appSettings, for re-submitting a full page form. Capability/device
// settings report value=null even when devices ARE assigned -- the live
// ids sit in deviceIdsForDeviceList (with a deviceList id->label map
// alongside). Rebuilding a form from `value` alone re-submits
// settings[<name>]="" which, combined with _action_update=Done, actively
// CLEARS the device assignment (verified live on fw 2.5.0.143: the Button
// Controller buttonDev wipe -- RM rules never hit it on mainPage because
// their device pickers live on sub-pages). Device-backed null values are
// reconstructed as a List of id strings so _rmBuildSettingsBody
// serializes them as the CSV the form expects.
private Map _rmLiveSettingsFromStatus(Map status) {
    return (status?.appSettings ?: []).collectEntries { s ->
        def v = s?.value
        if (v == null) {
            def ids = (s?.deviceIdsForDeviceList instanceof List && s.deviceIdsForDeviceList) ?
                s.deviceIdsForDeviceList :
                ((s?.deviceList instanceof Map && s.deviceList) ? s.deviceList.keySet().toList() : null)
            if (ids) v = ids.collect { it.toString() }
        }
        [(s?.name?.toString()): v]
    }
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
private void _rmSubmitSubPageDone(Integer appId, String page, String parentPage, String hrefName, Map hrefParams) {
    // Sub-pages with route params (periodic.n, etc.) lose state.<paramKey>
    // on a plain GET — `_rmFetchConfigJson(appId, page)` returns the page
    // rendered with state=null, which means the schema is empty/wrong and
    // the version field is unreadable. Round-trip via _rmNavigateToPage to
    // get a fresh response that has the param state in scope.
    def hrefIndex = hrefParams?.n != null ? (hrefParams.n as Integer) : 0
    def navResp = _rmNavigateToPage(appId, parentPage ?: page, page, hrefIndex, hrefName ?: "name", hrefParams)
    def cfg = navResp ? [configPage: navResp.configPage, app: navResp.app] : _rmFetchConfigJson(appId, page)
    def schema = _rmCollectInputSchema(cfg?.configPage)
    def status = _rmFetchStatusJson(appId)
    def liveSettings = _rmLiveSettingsFromStatus(status)
    def settingsMap = [:]
    schema.each { name, meta ->
        def v = liveSettings[name]
        if (v == null) v = ""
        settingsMap[name] = v
    }
    def body = _rmBuildSettingsBody(appId, settingsMap, schema)
    body.formAction = "update"
    body.currentPage = page
    body._action_previous = "Done"
    if (hrefParams != null && !hrefParams.isEmpty()) {
        body.paramsForPage = groovy.json.JsonOutput.toJson(hrefParams)
    }
    body.pageBreadcrumbs = parentPage ?
        groovy.json.JsonOutput.toJson(["mainPage", parentPage]) :
        '["mainPage"]'
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
private Map _rmSubmitMainPageDone(Integer appId) {
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
        def v = liveSettings[name]
        if (v == null) v = ""
        settingsMap[name] = v
    }
    def body = _rmBuildSettingsBody(appId, settingsMap, schema)
    body.formAction = "update"
    body.currentPage = commitPage
    body._action_update = "Done"
    body.pageBreadcrumbs = "[]"
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

private Map _rmWriteSubPageField(Integer appId, String page, String parentPage, String hrefName, Integer hrefIndex, Map hrefParams, String key, Object value) {
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
        def navResp = _rmNavigateToPage(appId, parentPage ?: page, page, hrefIndex, hrefName, hrefParams)
        cfg = navResp ? [configPage: navResp.configPage, app: navResp.app] : _rmFetchConfigJson(appId, page)
    } else {
        cfg = _rmFetchConfigJson(appId, page)
    }
    def schema = _rmCollectInputSchema(cfg?.configPage)
    def beforeKeys = (schema?.keySet() ?: []) as Set
    def beforeValueStr = schema?."${key}"?.value?.toString()
    def beforeRenderHash = _rmSanitizeRenderForHash(cfg?.configPage?.sections).hashCode()
    def body = _rmBuildSettingsBody(appId, [(key): value], schema)
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
    body.pageBreadcrumbs = '[]'
    if (cfg?.app?.version != null) body.version = cfg.app.version.toString()
    hubInternalPostForm("/installedapp/update/json", body)
    // Post-write verification — re-fetch the page and detect whether the
    // write either (a) shifted the schema (wizard advanced; key disappeared
    // or new keys appeared), or (b) landed the new value verbatim. Returns
    // a Map so callers can route into applied vs skipped lists rather than
    // optimistically appending to applied regardless of outcome (RM 5.1
    // returns 200 for many writes that never land; the previous optimistic
    // bookkeeping hid these silent rejections).
    def afterCfg = null
    def verifyFetchErr = null
    try {
        afterCfg = hasRealParams ? _rmNavigateToPage(appId, parentPage ?: page, page, hrefIndex, hrefName, hrefParams) : _rmFetchConfigJson(appId, page)
    } catch (Exception fetchExc) {
        verifyFetchErr = fetchExc.message
        mcpLog("warn", "rm-native", "_rmWriteSubPageField: post-write fetch on ${page} failed for app ${appId} key=${key} (${fetchExc.message}) -- write status is unverified")
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
private List _rmResolveModeIds(Collection keys) {
    def hubModes = location?.modes ?: []
    def nameToId = [:]
    hubModes.each { m -> if (m?.name && m?.id != null) nameToId[m.name.toString()] = m.id.toString() }
    def out = []
    keys.each { k ->
        def s = k?.toString()
        if (!s) return
        if (s.isInteger()) { out << s; return }
        def mapped = nameToId[s]
        if (mapped) { out << mapped; return }
        throw new IllegalArgumentException("Unknown mode '${s}' -- must be an integer mode ID or one of: ${nameToId.keySet().sort().join(', ')}")
    }
    return out
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
            idToName[m.id.toString()] = m.name.toString()
            nameSet << m.name.toString()
        }
    }
    def out = []
    keys.each { k ->
        def s = k?.toString()
        if (!s) return
        if (s.isInteger() && idToName[s]) { out << idToName[s]; return }
        if (nameSet.contains(s)) { out << s; return }
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
        pageBreadcrumbs: '["mainPage"]'
    ]
    def v = cfg?.app?.version
    if (v != null) body.version = v.toString()
    try { hubInternalPostForm("/installedapp/update/json", body) } catch (Exception e) {
        mcpLog("warn", "rm-native", "_rmInitSelectActionsPage: ${editorPage} tickle POST for app ${appId} failed (${e.message}) -- state.actNdx may not initialize; first +N click may throw 'Cannot invoke method startsWith() on null object'")
    }
}

// High-level structured action creation for Rule Machine 5.1.
// Replaces the 6-7 manual wizard calls with one orchestrated call.
// Wire-format quirks and capability families: docs/rm_wire_format.md#_rmAddAction.
//
// Returns: [success, actionIndex, capability, action, settingsApplied,
// configPageError]
private Map _rmAddAction(Integer appId, Map actionSpec, boolean intraBatch = false) {
    if (!(actionSpec instanceof Map)) throw new IllegalArgumentException("addAction requires a Map spec")
    // Discover mode -- return static schema without touching the hub.
    // No capability field required; no Write master gate; no backup.
    if (actionSpec.discover == true) {
        return _rmActionSchemaForDiscover()
    }
    def cap = actionSpec.capability?.toString()?.trim()
    def action = actionSpec.action?.toString()?.trim()
    if (!cap) throw new IllegalArgumentException("addAction.capability is required (e.g. 'switch'). Common values: switch, dimmer, color, log, notification, mode, setVariable, runCommand, delay, repeat, ifThen. Pass {discover: true} to get the full structured schema.")
    // 'action' is required only for capabilities that have multiple action
    // variants (e.g. switch needs on/off/toggle/flash). Single-action
    // capabilities (log, mode, delay, comment, exitRule, capture, restore,
    // refresh, poll, runRule, cancelTimers, etc.) accept a null/missing
    // action — each capability's branch validates as needed.

    // Pre-flight: refuse closers (endIf / stopRepeat) and orphan branch
    // keywords (elseIf / else) that would render as orphaned because they
    // have no matching opener / containing IF block. Asymmetric on purpose:
    // openers (ifThen / repeat / repeatWhile) added alone are allowed —
    // they're a normal multi-step build state and the caller will add the
    // matching closer in a follow-up call. Set-diff (projected MINUS
    // current) catches the case where the new action would introduce a
    // new structural-balance issue without flagging deletions that
    // merely improve an already-broken rule.
    def preflightCap = _rmStructuralPairForCapability(cap)
    def closerOrBranchKeywords = ["endIf", "stopRepeat", "elseIf", "else"]
    if (cap in closerOrBranchKeywords && preflightCap != null) {
        def settingsByName = _rmFetchSettingsByName(appId)
        def currentSeq = _rmStructuralSequenceFromSettings(settingsByName)
        // The projected idx only matters for issue-message construction;
        // any value not in current works for the walker.
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

    // Pre-validate device IDs exist on the hub. RM 5.1 silently stores
    // {<bogusId>: null} for unknown IDs in any device-bearing setting and
    // the action renders as broken with no execution. Validate the top-
    // level deviceIds list (used by switch / dimmer / lock / shade /
    // thermostat / messaging / etc.) and any waitEvents events[].deviceIds.
    _rmValidateDeviceIdsExist("addAction.deviceIds", actionSpec.deviceIds)
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
            // Pre-pass: reject nested subExpression at the top level rather than
            // recursing into a shape the doActPage walker does not yet support. The
            // walker also rejects subExpression with a targeted message at the first
            // condition site, but catching it here is cheaper and produces a clearer
            // error before any wizard write hits the hub (the backup on disk is
            // already taken by the outer dispatcher at this point; fail-fast here
            // means RM's wizard state stays untouched). _rmAddRequiredExpression
            // supports nested subExpression today; _rmAddAction's doActPage walker
            // is flat-only.
            exprConds.eachWithIndex { entry, idx ->
                if (entry instanceof Map && (entry as Map).subExpression != null) {
                    throw new IllegalArgumentException("addAction.expression.conditions[${idx}]: nested subExpression is not yet supported on this action type. Either flatten the condition list, or move the nested expression into a Required Expression (addRequiredExpression supports nesting).")
                }
            }
            // Normalize singular deviceId -> deviceIds before pre-validation **because**
            // _rmBuildCondition's internal normalization runs too late to protect
            // _rmValidateDeviceIdsExist; the validator below sees the raw deviceIds list
            // and would silently skip a singular deviceId.
            // Flat-only normalization; subExpression is rejected at the pre-pass above --
            // if that gate is ever relaxed, restore a recursive walk-in here.
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
                    // compareToDevice reference device: existence-validated up front, before
                    // the walker opens the slot, so a nonexistent reference id fails loud.
                    def cm = c as Map
                    if (cm.compareToDevice instanceof Map && (cm.compareToDevice as Map).deviceId != null) {
                        _rmValidateDeviceIdsExist("addAction.expression.conditions[${cIdx}].compareToDevice.deviceId", [(cm.compareToDevice as Map).deviceId])
                    }
                }
            }
        }
    }

    // Initialize state.actNdx if this is the first action on the rule
    // — avoids the doActPage 'startsWith on null' error on empty rules.
    _rmInitSelectActionsPage(appId)

    // Discover next action index by scanning existing actType.<N> settings.
    def existing = _rmCollectActionIndices(appId)
    def idx = (existing ? existing.max() + 1 : 1)

    // RM 5.1 platform limitation: waitEvents actions share GLOBAL event-row
    // settings (tCapab-N, tDev-N, tstate-N, stays-N) — there is no per-action
    // event storage. Adding a second waitEvents action causes the wizard to
    // inherit action 1's event configuration as defaults, and any field
    // change overwrites action 1's events. The Hubitat web UI exhibits the
    // same bug — verified live via Chrome XHR capture against
    // rule 227 (test hub) plus manual UI walk: the rule rendered "Wait for
    // event: <DeviceA>" twice for what was supposed to be two distinct
    // waits, because setting action 2's device silently overwrote action 1's
    // tDev-1. Settings dump confirmed: actType.1=delayActs, actType.2=delayActs,
    // BUT only one tCapab-1/tDev-1/tstate-1 record shared by both actions.
    //
    // Until Hubitat fixes this at the platform level, fail-loud rather than
    // silently corrupt the rule.
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
                "RM 5.1 platform limitation: only one Wait for Events action is supported per rule " +
                "(an existing waitEvents action is at index ${existingWaitIdx}). RM stores wait-event " +
                "capability/device/state in global per-rule settings (tCapab-N, tDev-N, tstate-N), NOT " +
                "in per-action storage — adding a second waitEvents action would silently overwrite " +
                "the first action's event configuration. Verified live: the Hubitat web UI " +
                "exhibits the same bug. Workarounds: (a) put all wait events into a SINGLE waitEvents " +
                "action via the 'events' array (events=[{...}, {...}]); (b) split into two rules " +
                "chained via Run Actions; (c) wait at the platform level via separate triggers."
            )
        }
    }

    // Map (capability, action) → (actType, actSubType, fields)
    def actType = null
    def actSubType = null
    def fields = [:]  // key: field name with @N placeholder, value: the value
    def deviceIds = actionSpec.deviceIds
    // applied/skipped track what was written vs. silently bypassed.
    // Declared here (before capability dispatch) so capability branches can
    // push sentinel entries before the main write loop initialises them.
    def applied = []
    def skipped = []

    if (cap == "switch") {
        actType = "switchActs"
        switch (action) {
            case "on":
                actSubType = "getOnOffSwitch"
                fields = ["onOffSwitch.@N": deviceIds, "onOff.@N": true]
                break
            case "off":
                actSubType = "getOnOffSwitch"
                fields = ["onOffSwitch.@N": deviceIds, "onOff.@N": false]
                break
            case "toggle":
                actSubType = "getToggleSwitch"
                fields = ["toggleSwitch.@N": deviceIds]
                break
            case "flash":
                actSubType = "getFlashSwitch"
                fields = ["flashSwitch.@N": deviceIds]
                // FOLLOW-UP (verified live): RM 5.1's switchActs
                // category exposes getFlashSwitch (start flashing) but NO
                // matching "stop flashing" subtype. Calling switch.on/.off
                // afterward DOES NOT cancel the flash schedule — the device
                // keeps pulsing on/off until something explicitly calls its
                // .flashOff() command (verified: rule that did Flash → Delay
                // 5s → On left lights flashing for ~90s after the rule
                // completed, until I sent .flashOff() directly via
                // hub_call_device_command).
                //
                // To cancel a running flash from within a rule, use
                // capability='runCommand' (mapped below) with command=
                // 'flashOff' on the same device list — that drives
                // modeActs/getDefinedAction internally.
                break
            case "setPerMode":
                // switchActs/getModeSwitch — same device list, per-mode on/off.
                // Wire format (verified live):
                //   switchM.<N>      = devices (capability.switch multi)
                //   switchModes.<N>  = mode IDs (enum multi, JSON-array)
                //   switch<modeID>.<N> = "on" | "off" for each selected mode
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
                // switchActs/getChooseSwitch — different device lists per mode for ON / OFF.
                // Wire format (verified live):
                //   chooseModes.<N>     = mode IDs
                //   chooseSwOn<modeID>.<N>  = devices to turn ON for that mode
                //   chooseSwOff<modeID>.<N> = devices to turn OFF for that mode
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
            default:
                throw new IllegalArgumentException("Unknown switch action '${action}' -- supported: on, off, toggle, flash, setPerMode, choosePerMode")
        }
    } else if (cap == "dimmer") {
        actType = "dimmerActs"
        switch (action) {
            case "setLevel":
                actSubType = "getSetDimmer"
                fields = ["dimA.@N": deviceIds]
                if (actionSpec.levelVariable != null) {
                    // Variable-sourced level: uVar.<N>=true + xVar.<N>=<varName>
                    fields["uVar.@N"] = true
                    fields["xVar.@N"] = actionSpec.levelVariable
                } else {
                    if (actionSpec.level == null) throw new IllegalArgumentException("dimmer.setLevel requires 'level' (0-100) OR 'levelVariable' (hub variable name). Verified live: actionDone never appears until a level source is set.")
                    fields["dimLA.@N"] = actionSpec.level
                }
                if (actionSpec.fadeSeconds != null) fields["dimRA.@N"] = actionSpec.fadeSeconds
                break
            case "toggle":
                // level IS required by RM 5.1's getToggleDimmer wizard — it
                // is the level the dimmer goes to when toggling FROM off TO on.
                // Verified live in Chrome (2026-04-25): actionDone button
                // doesn't appear in the schema until 'To this level (0..100)*'
                // is filled. The * indicates required.
                if (actionSpec.level == null) throw new IllegalArgumentException("dimmer.toggle requires 'level' (0-100) -- the level to set when toggling from off to on. Verified live: actionDone never appears in the schema until level is set (the wizard input is marked required with *).")
                actSubType = "getToggleDimmer"
                fields = ["dimA.@N": deviceIds, "dimLA.@N": actionSpec.level]
                if (actionSpec.fadeSeconds != null) fields["dimRA.@N"] = actionSpec.fadeSeconds
                break
            case "adjust":
                // adjustBy IS required — the +/- amount (-100..100). Without
                // it, actionDone never appears (same pattern as toggle).
                if (actionSpec.adjustBy == null) throw new IllegalArgumentException("dimmer.adjust requires 'adjustBy' (signed integer -100..100). Without it the wizard's actionDone button never renders.")
                actSubType = "getAdjustDimmer"
                fields = ["dimA.@N": deviceIds, "dimAdj.@N": actionSpec.adjustBy]
                if (actionSpec.fadeSeconds != null) fields["dimAdjR.@N"] = actionSpec.fadeSeconds
                break
            case "fade":
                // Fade dimmer over time (raise OR lower) — getFadeDimmer.
                // dimFadeUp.<N>: true=Raise, false=Lower
                if (actionSpec.targetLevel == null) throw new IllegalArgumentException("dimmer.fade requires 'targetLevel' (0-100). Without it the wizard's actionDone button never renders.")
                if (actionSpec.minutes == null) throw new IllegalArgumentException("dimmer.fade requires 'minutes' (over how long to fade). Without it the wizard's actionDone button never renders.")
                actSubType = "getFadeDimmer"
                fields = ["dimFade.@N": deviceIds, "dimFadeUp.@N": (actionSpec.direction == "raise"), "dimFadeTarget.@N": actionSpec.targetLevel, "dimFadeTime.@N": actionSpec.minutes]
                if (actionSpec.intervalSeconds != null) fields["dimFadeInterval.@N"] = actionSpec.intervalSeconds
                break
            case "stopFade":
                actSubType = "getStopFade"; fields = [:]
                break
            case "startRaiseLower":
                // dimRL.<N>: true=LOWER, false=Raise (verified live
                // sweep — the boolean is inverted relative to the
                // intuition the field name suggests).
                actSubType = "getRLDimmer"
                fields = ["dimRL.@N": (actionSpec.direction != "raise"), "dimRaiseLower.@N": deviceIds]
                break
            case "stopChanging":
                actSubType = "getStopDimmer"; fields = ["dimStop.@N": deviceIds]
                break
            case "setLevelPerMode":
                // dimmerActs/getDimmersPerMode — same dimmer device list, per-mode level.
                // Field naming (verified live):
                //   dimM.<N>           = devices (capability.switchLevel multi)
                //   dimmerModes.<N>    = mode IDs
                //   level<modeID>.<N>  = number 0-100 for each mode
                //   dimMR.<N>          = optional fade seconds
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
            default:
                throw new IllegalArgumentException("Unknown dimmer action '${action}' -- supported: setLevel, toggle, adjust, fade, stopFade, startRaiseLower, stopChanging, setLevelPerMode")
        }
    } else if (cap == "color") {
        // Color (RGBW) family — capability.colorControl.
        actType = "dimmerActs"
        switch (action) {
            case "setColor":
                // Verified live: with no color source the action registers (bulbs.<N>
                // written) but never bakes. Require colorName OR a rawSettings color value.
                if (actionSpec.colorName == null && !(actionSpec.rawSettings instanceof Map)) throw new IllegalArgumentException("color.setColor requires 'colorName' (e.g. 'Red'), OR a custom HSV color supplied via rawSettings (colorH.<N>). Without a color source the action registers but never bakes. See hub_get_tool_guide(section='set_rule_create_reference').")
                actSubType = "getSetColor"
                fields = ["bulbs.@N": deviceIds]
                if (actionSpec.colorName != null) fields["color.@N"] = actionSpec.colorName
                if (actionSpec.level != null) fields["colorLevel.@N"] = actionSpec.level
                // For custom HSV: pass hue/saturation/level via rawSettings (colorH.<N> takes a JSON-encoded color picker value)
                break
            case "toggleColor":
                // Verified live: getToggleColor's actionDone button
                // doesn't render until colorName AND level are set. Without
                // them the action never bakes — the row stays in atomicState
                // but actions[] doesn't advance, so the next addAction's
                // index discovery returns the same idx and overwrites.
                if (actionSpec.colorName == null) throw new IllegalArgumentException("color.toggleColor requires 'colorName' (e.g. 'Red'). Without it, actionDone never appears in the schema and the action silently fails to bake.")
                if (actionSpec.level == null) throw new IllegalArgumentException("color.toggleColor requires 'level' (0-100). Without it, actionDone never appears in the schema and the action silently fails to bake.")
                actSubType = "getToggleColor"
                fields = ["bulbsTog.@N": deviceIds, "colorTog.@N": actionSpec.colorName, "colorTogLevel.@N": actionSpec.level]
                break
            case "setColorPerMode":
                // dimmerActs/getColorPerMode — per-mode color + level (RGBW bulbs).
                // Wire format (verified live):
                //   bulbsM.<N>             = devices (capability.colorControl multi)
                //   colorModes.<N>         = mode IDs
                //   color<modeID>.<N>      = color enum (Red/Green/Blue/etc.) per mode
                //   colorLevel<modeID>.<N> = bulb level (0-100) per mode
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
            default:
                throw new IllegalArgumentException("Unknown color action '${action}' -- supported: setColor, toggleColor, setColorPerMode")
        }
    } else if (cap == "colorTemp") {
        // Color temperature family — capability.colorTemperature.
        actType = "dimmerActs"
        switch (action) {
            case "setColorTemp":
                // Verified live: without kelvin the action registers (ct.<N> written)
                // but never bakes -- mainPage keeps the 'Define Actions' placeholder.
                // Fail fast rather than returning a confusing silent partial.
                if (actionSpec.kelvin == null) throw new IllegalArgumentException("colorTemp.setColorTemp requires 'kelvin' (color temperature in K, e.g. 2700). Without it the action registers but never bakes. See hub_get_tool_guide(section='set_rule_create_reference').")
                actSubType = "getSetColorTemp"
                fields = ["ct.@N": deviceIds, "ctL.@N": actionSpec.kelvin]
                if (actionSpec.level != null) fields["ctLevel.@N"] = actionSpec.level
                break
            case "toggleColorTemp":
                // Verified live: without kelvin the action never bakes (same silent
                // partial as setColorTemp). Fail fast.
                if (actionSpec.kelvin == null) throw new IllegalArgumentException("colorTemp.toggleColorTemp requires 'kelvin' (color temperature in K, e.g. 2700). Without it the action registers but never bakes. See hub_get_tool_guide(section='set_rule_create_reference').")
                actSubType = "getToggleColorTemp"
                fields = ["ctTog.@N": deviceIds, "ctLTog.@N": actionSpec.kelvin]
                if (actionSpec.level != null) fields["ctTogLevel.@N"] = actionSpec.level
                break
            case "fadeColorTemp":
                // Verified live: without targetKelvin + minutes the action never bakes
                // (only ctFade/ctFadeUp get written). Fail fast. direction (raise/lower)
                // defaults to lower when omitted, mirroring dimmer.fade.
                if (actionSpec.targetKelvin == null || actionSpec.minutes == null) throw new IllegalArgumentException("colorTemp.fadeColorTemp requires 'targetKelvin' (target color temp in K) and 'minutes' (fade duration). Without them the action registers but never bakes. Optional 'direction' (raise/lower) defaults to lower. See hub_get_tool_guide(section='set_rule_create_reference').")
                actSubType = "getFadeCT"
                fields = ["ctFade.@N": deviceIds, "ctFadeUp.@N": (actionSpec.direction == "raise"), "ctFadeTarget.@N": actionSpec.targetKelvin, "ctFadeTime.@N": actionSpec.minutes]
                if (actionSpec.intervalSeconds != null) fields["ctFadeInterval.@N"] = actionSpec.intervalSeconds
                if (actionSpec.changeLevel != null) fields["bothCTandL.@N"] = actionSpec.changeLevel
                if (actionSpec.targetLevel != null) fields["ctFadeLevel.@N"] = actionSpec.targetLevel
                break
            case "stopColorTempFade":
                actSubType = "getStopCTFade"; fields = [:]
                break
            case "setColorTempPerMode":
                // dimmerActs/getColorTempPerMode — per-mode kelvin + level.
                // Wire format quirk: the LEVEL field name is ctMode<modeID>.<N>Level
                // (the "Level" suffix appended after the action index).
                //   ctM.<N>                     = devices (capability.colorTemperature multi)
                //   ctModes.<N>                 = mode IDs
                //   ctMode<modeID>.<N>          = kelvin per mode
                //   ctMode<modeID>.<N>Level     = bulb level per mode  (note suffix order)
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
                        // Level suffix goes AFTER the action index — encode the @N substitution
                        // and append "Level" so the final key is ctMode<mid>.<idx>Level.
                        if (cfg.level != null) fields["ctMode${mid}.@NLevel"] = cfg.level
                    }
                }
                break
            default:
                throw new IllegalArgumentException("Unknown colorTemp action '${action}' -- supported: setColorTemp, toggleColorTemp, fadeColorTemp, stopColorTempFade, setColorTempPerMode")
        }
    } else if (cap == "lock") {
        // lockRL.<N>: true=UNLOCK, false=Lock (verified live --
        // boolean is inverted relative to field-name intuition).
        actType = "lockActs"
        actSubType = "getLULock"
        switch (action) {
            case "lock":   fields = ["lockRL.@N": false, "lockLockUnlock.@N": deviceIds]; break
            case "unlock": fields = ["lockRL.@N": true,  "lockLockUnlock.@N": deviceIds]; break
            default: throw new IllegalArgumentException("Unknown lock action '${action}' -- supported: lock, unlock")
        }
    } else if (cap == "thermostat") {
        // Thermostat with optional mode, fan, heating/cooling setpoints.
        // Verified live: a thermostat action with NO setting registers (thermo.<N>
        // written) but never bakes. Require at least one setting. Fail fast.
        if (actionSpec.mode == null && actionSpec.fanMode == null && actionSpec.heatingSetpoint == null &&
            actionSpec.adjustHeating == null && actionSpec.coolingSetpoint == null && actionSpec.adjustCooling == null) {
            throw new IllegalArgumentException("thermostat action requires at least one setting: 'mode', 'fanMode', 'heatingSetpoint', 'adjustHeating', 'coolingSetpoint', or 'adjustCooling'. Without any, the action registers but never bakes. See hub_get_tool_guide(section='set_rule_create_reference').")
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
    } else if (cap == "shade") {
        // shadeRL.<N>: true=CLOSE, false=Open (verified live --
        // boolean is inverted relative to field-name intuition).
        actType = "sceneActs"
        switch (action) {
            case "open":  actSubType = "getRLShade"; fields = ["shadeRL.@N": false, "shadeOpenClose.@N": deviceIds]; break
            case "close": actSubType = "getRLShade"; fields = ["shadeRL.@N": true,  "shadeOpenClose.@N": deviceIds]; break
            case "setPosition":
                // Verified live: without position the action registers (shadePosition.<N>
                // written) but never bakes. Fail fast.
                if (actionSpec.position == null) throw new IllegalArgumentException("shade.setPosition requires 'position' (0-100). Without it the action registers but never bakes. See hub_get_tool_guide(section='set_rule_create_reference').")
                actSubType = "getShadePosition"
                fields = ["shadePosition.@N": deviceIds, "shadeLevel.@N": actionSpec.position]
                break
            case "stop": actSubType = "getStopShade"; fields = ["shadeStop.@N": deviceIds]; break
            default: throw new IllegalArgumentException("Unknown shade action '${action}' -- supported: open, close, setPosition, stop")
        }
    } else if (cap == "fan") {
        actType = "sceneActs"
        switch (action) {
            case "setSpeed":
                // Verified live: without speed the action registers (fanDevice.<N>
                // written) but never bakes. Fail fast.
                if (actionSpec.speed == null) throw new IllegalArgumentException("fan.setSpeed requires 'speed' (e.g. low / medium-low / medium / medium-high / high / on / auto / off). Without it the action registers but never bakes. See hub_get_tool_guide(section='set_rule_create_reference').")
                actSubType = "getFanSpeed"
                fields = ["fanDevice.@N": deviceIds, "fanSpeed.@N": actionSpec.speed]
                break
            case "cycle":
                actSubType = "getAdjustFan"
                fields = ["fanAdjust.@N": deviceIds]
                break
            default: throw new IllegalArgumentException("Unknown fan action '${action}' -- supported: setSpeed, cycle")
        }
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
            // Resolve modeName -> mode ID. Writing the name literal produces Mode: null
            // in the RM render because the mode.<N> input expects an integer ID.
            def name = actionSpec.modeName.toString()
            def hubModes = location?.modes ?: []
            def matched = hubModes.find { it?.name?.toString()?.equalsIgnoreCase(name) }
            if (!matched) {
                def available = hubModes.collect { it?.name }.findAll { it }.sort().join(', ')
                def availableDisplay = available ?: "(none -- hub returned no modes; verify hub state via hub_list_modes)"
                throw new IllegalArgumentException("mode action: modeName '${name}' not found. Available modes: ${availableDisplay}")
            }
            modeValue = matched.id
        }
        fields = ["mode.@N": modeValue]
    } else if (cap == "variable" || cap == "setVariable" || cap == "setLocalVariable") {
        // modeActs/getSetVariable -- Set Hub Variable OR rule-local variable to a value.
        // RM exposes locals and globals through the SAME getSetVariable action and the SAME
        // xVarV picker (locals appear in the picker alongside globals), so the wire path is
        // identical -- the only difference is WHICH variable namespace the target name is
        // validated against. setLocalVariable validates against the rule's local variables
        // (state.allLocalVars) so it cannot silently target a same-named hub global; the
        // value/sourceVariable/fromDevice/math source modes and every post-write reveal block
        // are shared verbatim with the global path.
        // Verified wire format (RM 5.1, actType=modeActs, actSubType=getSetVariable):
        //   xVarV.<N>       = hub variable name (enum from hub variables list). GATES the
        //                     numOp.<N> reveal -- numOp does not render until xVarV is set, so
        //                     xVarV must be written before numOp.
        //   numOp.<N>       = source mode enum. Supported here: "number" (constant),
        //                     "variable" (copy from another variable), "device attribute"
        //                     (read a device's attribute), "variable math" (structured math).
        //                     Default: "number"
        //   valNumber.<N>   = value when numOp=number (constant form)
        //   xVar3.<N>       = source variable name when numOp=variable (copy-from-variable form).
        //                     Schema-gated: RM only reveals xVar3.<N> AFTER numOp.<N>="variable".
        //                     Discovered from the live schema, not hardcoded. Post-write block:
        //                     __setVariableSourceVar.
        //   customDev.<N>   = source device when numOp="device attribute" (capability.* single-
        //                     device picker, multiple=false). Reveals tCustomAttr.<N>.
        //   tCustomAttr.<N> = source attribute (enum FILTERED to the selected device's live
        //                     attributes). Both are schema-gated after numOp; post-write block:
        //                     __setVariableFromDevice.
        //   xVar3.<N>/valConst.<N> + valMathOp.<N> + xVar4.<N>/valConst2.<N> = structured math
        //                     operands when numOp="variable math". xVar3/valMathOp reveal after
        //                     numOp; a binary operator reveals the second operand. Operand value
        //                     "(constant)" reveals the matching valConst slot. Post-write block:
        //                     __setVariableMath.
        // Source mode is exactly one of value | sourceVariable | fromDevice | math.
        actType = "modeActs"
        actSubType = "getSetVariable"
        // setLocalVariable targets the rule's LOCAL variable namespace; setVariable/variable
        // target hub GLOBALS. capLabel keeps the caller-facing error text aligned with the
        // capability they actually called; isLocalVar selects the validation namespace below.
        boolean isLocalVar = (cap == "setLocalVariable")
        String capLabel = isLocalVar ? "setLocalVariable" : "setVariable"
        if (!actionSpec.variable) {
            throw new IllegalArgumentException("${capLabel} action requires 'variable' (${isLocalVar ? "local variable name" : "hub variable name"})")
        }
        // != null (not truthiness): value=0 is a valid numeric constant that a truthy-check would wrongly reject.
        // Count the source modes provided: exactly one is required.
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
        // valNumber.<N> is a numeric field; writing a non-numeric value produces a broken action
        // that RM silently accepts but renders incorrectly. Reject early so the caller gets a
        // clear message rather than a broken rule.
        if (actionSpec.value != null && !(actionSpec.value instanceof Number)) {
            throw new IllegalArgumentException("${capLabel}: 'value' must be a numeric constant (Integer, Long, BigDecimal, etc.); got '${actionSpec.value}' -- use a number literal, not a string")
        }
        // fromDevice shape + math shape/arity validation happen up-front so bad inputs fail
        // before any hub write, mirroring the runCommand parameter pre-validation pattern.
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
            // deviceId must be a positive WHOLE-number id. Not validated against findDevice():
            // RM's customDev.<N> picker spans ALL hub devices, while findDevice() only sees the
            // MCP-exposed selectedDevices set -- gating here would falsely reject a valid hub
            // device. RM's filtered picker is the authoritative check (post-write reveal).
            // Accept any whole number regardless of how it arrives -- a bare Integer/Long, a
            // BigDecimal with no fractional part (72.0), or a digit string -- via _rmAsPositiveDeviceId,
            // which returns null for non-integers / non-positive / non-numeric so only those are rejected.
            if (_rmAsPositiveDeviceId(fdDev) == null) {
                throw new IllegalArgumentException("${capLabel} fromDevice: 'deviceId' must be a positive integer device id; got '${fdDev}'")
            }
        }
        if (actionSpec.math != null) {
            if (!(actionSpec.math instanceof Map)) {
                throw new IllegalArgumentException("${capLabel}: 'math' must be a Map {left, op, right}; got '${actionSpec.math}'")
            }
            // Trim string operands so " temp " resolves to the "temp" variable (mirrors op.trim()).
            // Number operands pass through unchanged (a Number has no surrounding whitespace).
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
            // Each operand is either a number (becomes a constant) or a String (a hub variable
            // name). Reject any other type (e.g. Boolean, Map, List) up-front with a precise
            // message rather than coercing it to a string and blaming a nonexistent variable.
            [[role: "left", operand: mathLeft], [role: "right", operand: mathRight]].each { o ->
                if (o.operand != null && !(o.operand instanceof Number) && !(o.operand instanceof CharSequence)) {
                    throw new IllegalArgumentException("${capLabel} math: ${o.role} operand must be a number or a hub variable name; got '${o.operand}'")
                }
            }
        }
        // Reject the xVarV picker's two sentinel header rows as targets. RM emits the
        // leading-space strings " --LOCAL VARIABLES--" / " --HUB VARIABLES--" as selectable
        // enum values; they are section headers, not real variable names, and writing one
        // produces a broken action. Guard before any namespace lookup so the message is precise.
        def pickerSectionHeaders = [" --LOCAL VARIABLES--", " --HUB VARIABLES--"]
        if (actionSpec.variable?.toString() in pickerSectionHeaders) {
            throw new IllegalArgumentException("${capLabel}: '${actionSpec.variable}' is a picker section header, not a variable name. Pass a real variable name.")
        }
        // Validate the target variable (and sourceVariable / math operands) against the
        // appropriate namespace. setVariable/variable validate against hub GLOBALS via
        // getAllGlobalVars(); setLocalVariable validates against the rule's LOCALS via the
        // statusJson appState.allLocalVars map. Both are normalized to {name -> [type:<token>]}
        // so the shared name + numeric-target checks below read identically.
        // Fail loud: an unknown variable name causes the hub to silently reject the write,
        // producing a broken-looking action with no caller-visible error.
        // Source unavailable -> skip validation and log warn (null sentinel).
        // Source empty -> every name is invalid -> validate and fail.
        def varDisplayKind = isLocalVar ? "local variables" : "hub variables"
        def emptyDisplay = isLocalVar ? "(none -- rule has no local variables defined)" : "(none -- hub has no variables defined)"
        def allVars = null
        if (isLocalVar) {
            // Read state.allLocalVars (the rule-local namespace) from the rule's statusJson
            // appState. Normalize to the same {name -> [type:<token>]} shape getAllGlobalVars
            // returns so the downstream checks are namespace-agnostic. The stored type token
            // ('integer'/'bigdecimal'/'string'/...) matches what _rmIsNumericVarType expects.
            def localsRead = _rmReadLocalVarsMap(appId)
            if (localsRead.ok) {
                // statusJson answered (an empty map means the rule simply has no locals).
                allVars = [:]
                localsRead.vars.each { lvName, lvMeta ->
                    allVars[lvName?.toString()] = [type: (lvMeta instanceof Map ? lvMeta?.type?.toString() : null)]
                }
            } else {
                mcpLog("warn", "rm-native", "setLocalVariable: local-variable read (statusJson appState.allLocalVars) unavailable for app ${appId} (${localsRead.error}) -- variable-name validation skipped; write will proceed unvalidated")
                skipped << [key: "variable-validation", reason: "api_unavailable"]
            }
        } else {
            try { allVars = getAllGlobalVars() } catch (Exception e) {
                mcpLog("warn", "rm-native", "setVariable: getAllGlobalVars() unavailable (${e.class.simpleName}: ${e.message ?: e.toString()}) -- variable-name validation skipped; write will proceed unvalidated")
                // Signal to the caller that validation was bypassed. The partial=true plumbing at
                // result assembly treats any non-empty skipped list as "incomplete"; the sentinel key
                // is distinct from field-write skips so the caller can distinguish the two.
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
            // The value (numeric constant), device-attribute (fromDevice), and variable-math (math)
            // source modes are all NUMERIC-TARGET-ONLY. value writes numOp=number + valNumber.<N>,
            // and RM renders numOp/valNumber ONLY for a Number/Decimal target -- against a
            // String/Boolean/DateTime target those fields never reveal, so the write skips them and
            // bakes a partial:true action with no assigned value. fromDevice/math have the same gate:
            // numOp never reveals their source option for a non-numeric target, so the reveal walk
            // fails deep with a misleading not-in-schema message. Fail loud HERE, before any hub
            // write, with the actual requirement and the ONE supported alternative (sourceVariable,
            // which copies from another variable; RM's source picker spans all types). (Type is read
            // from the same namespace map already fetched for name validation; an unavailable map
            // skips this with everything else.)
            // The INTERNAL type token (NOT the UI label) is what both namespaces store: a Number var is
            // "integer", a Decimal var is "bigdecimal" (the two numeric kinds), and String/Boolean/
            // DateTime report "string"/"boolean"/"datetime" (live-confirmed). _rmIsNumericVarType is
            // the single source of truth. Fail CLOSED: the target var's name is already validated to
            // exist, so a null or non-numeric token means we cannot prove it is numeric -- reject
            // rather than silently allowing an un-typeable target through to a doomed reveal walk.
            if (actionSpec.value != null || actionSpec.fromDevice != null || actionSpec.math != null) {
                def targetMeta = allVars[targetVar]
                def targetType = (targetMeta instanceof Map) ? targetMeta?.type?.toString() : null
                if (!_rmIsNumericVarType(targetType)) {
                    def modeName = actionSpec.value != null ? "numeric-constant (value)"
                        : (actionSpec.fromDevice != null ? "device-attribute (fromDevice)" : "variable-math (math)")
                    // Distinguish a recognized-but-non-numeric type (string/boolean/datetime)
                    // from metadata we could not parse a type out of at all -- the latter is a
                    // shape problem, not a "this variable is the wrong type" problem.
                    def typeDisplay = targetType ? "of type '${targetType}'" : "of an unreadable type (its variable metadata did not carry a recognizable type token)"
                    throw new IllegalArgumentException("${capLabel}: the ${modeName} source mode requires a Number or Decimal target variable; '${targetVar}' is ${typeDisplay}. To assign a String/Boolean/DateTime target use 'sourceVariable' (copy from another variable).")
                }
            }
            // sourceVariable + math-operand names: pre-validate against the global namespace for
            // setVariable only; for setLocalVariable the pre-check is SKIPPED entirely.
            // RM's source/operand pickers (xVar3/xVar4) span BOTH locals and globals, so a local
            // target legitimately copies from a global source -- pre-checking a setLocalVariable
            // source against the locals-only map would falsely reject that. For setLocalVariable
            // the authoritative check is the live revealed-enum validation in the
            // __setVariableSourceVar / __setVariableMath post-write blocks (which read the actual
            // picker spanning both namespaces); skip the pre-check here so it never over-rejects.
            if (!isLocalVar && actionSpec.sourceVariable != null) {
                def srcVar = actionSpec.sourceVariable.toString()
                if (!allVarNames.any { it?.toString() == srcVar }) {
                    def available = allVarNames.isEmpty() ? emptyDisplay : allVarNames.sort().join(', ')
                    throw new IllegalArgumentException("${capLabel}: sourceVariable '${srcVar}' not found. Available ${varDisplayKind}: ${available}")
                }
            }
            // Math operands that are variable names (non-Number operands) must also exist.
            // A Number operand becomes a (constant) and needs no variable-list check.
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
        // Stash the capability label so the schema-gated post-write blocks
        // (__setVariableSourceVar / __setVariableMath, which run after fields.each and
        // are out of capLabel's scope) name the ACTUAL capability the caller invoked --
        // a setLocalVariable caller must not see "setVariable:" in a deferred error.
        actionSpec.__setVariableCapLabel = capLabel
        if (actionSpec.sourceVariable != null) {
            // Write numOp=variable (the full word -- "var" is rejected by RM 5.1 live).
            // The source-variable field (xVar3.<N>) is schema-gated and only revealed
            // after numOp=variable is written. Discovery and write happen in the
            // __setVariableSourceVar post-write block below (after fields.each).
            fields["numOp.@N"] = "variable"
            actionSpec.__setVariableSourceVar = actionSpec.sourceVariable.toString()
        } else if (actionSpec.fromDevice != null) {
            // Write numOp="device attribute". The device picker (customDev.<N>) and the
            // attribute enum (tCustomAttr.<N>) are schema-gated and revealed in sequence after
            // numOp lands. Discovery and writes happen in the __setVariableFromDevice block.
            fields["numOp.@N"] = "device attribute"
            // Normalize to the canonical integer string so customDev gets "72", not "72.0".
            actionSpec.__setVariableFromDevice = [
                deviceId: _rmAsPositiveDeviceId(actionSpec.fromDevice.deviceId).toString(),
                attribute: actionSpec.fromDevice.attribute.toString()
            ]
        } else if (actionSpec.math != null) {
            // Write numOp="variable math". The operand fields (xVar3/valConst, valMathOp,
            // xVar4/valConst2) are schema-gated and revealed in sequence after numOp lands.
            // Discovery and writes happen in the __setVariableMath block. Binary-ness is
            // recomputed in that block from the operator via _rmMathBinaryOps().
            fields["numOp.@N"] = "variable math"
            actionSpec.__setVariableMath = [
                left: mathLeft, op: mathOp, right: mathRight
            ]
        } else {
            fields["numOp.@N"] = "number"
            fields["valNumber.@N"] = actionSpec.value
        }
    } else if (cap == "runCommand") {
        // modeActs/getDefinedAction — Run Custom Action. Multi-step:
        //   useLastDev.<N>  = false (use selected devices, not the trigger device)
        //   myCapab.<N>     = Hubitat capability CLASS NAME (verified live):
        //                     'Switch', 'SwitchLevel' (Dimmer), 'Lock',
        //                     'PushableButton' (Button), 'ColorControl',
        //                     'ColorTemperature', 'WindowShade', 'WindowBlind',
        //                     'FanControl', 'MusicPlayer', 'AudioVolume',
        //                     'GarageDoorControl', 'DoorControl', 'Valve',
        //                     'Lock', 'Thermostat', 'Tone', 'SpeechSynthesis', etc.
        //   devices.<N>     = device list (capability.<key-lowercased>)
        //   cCmd.<N>        = command name (one of the device driver's commands)
        // Per-parameter wire sequence (live-verified, see __runCommandExtraParams block):
        //   moreParams button click  -> reveals cpType<P>.<N> (P = RM-assigned, starts at 2)
        //   cpType<P>.<N>            = type (number/decimal/string) -> reveals uVar<P>+cpVal<P>
        //   literal value:  cpVal<P>.<N> = value
        //   hub variable:   uVar<P>.<N> = "true" -> reveals xVar<P>.<N> (enum) -> write varName
        actType = "modeActs"
        actSubType = "getDefinedAction"
        if (!actionSpec.command) throw new IllegalArgumentException("runCommand requires 'command' (the device driver method name)")
        // Friendly names → Hubitat capability class keys
        def friendlyToKey = [
            "switch": "Switch", "Switch": "Switch",
            "dimmer": "SwitchLevel", "Dimmer": "SwitchLevel", "switchLevel": "SwitchLevel", "SwitchLevel": "SwitchLevel", "Switch Level": "SwitchLevel",
            "color": "ColorControl", "Color": "ColorControl", "ColorControl": "ColorControl",
            "colorTemp": "ColorTemperature", "ColorTemperature": "ColorTemperature", "Color Temperature": "ColorTemperature",
            "lock": "Lock", "Lock": "Lock",
            "button": "PushableButton", "Button": "PushableButton", "PushableButton": "PushableButton",
            "shade": "WindowShade", "WindowShade": "WindowShade", "Window Shade": "WindowShade",
            "blind": "WindowBlind", "WindowBlind": "WindowBlind", "Window Blind": "WindowBlind",
            "fan": "FanControl", "FanControl": "FanControl", "Fan Control": "FanControl",
            "music": "MusicPlayer", "MusicPlayer": "MusicPlayer", "Music Player": "MusicPlayer",
            "garage": "GarageDoorControl", "GarageDoorControl": "GarageDoorControl", "Garage door": "GarageDoorControl",
            "door": "DoorControl", "DoorControl": "DoorControl",
            "valve": "Valve", "Valve": "Valve",
            "thermostat": "Thermostat", "Thermostat": "Thermostat",
            "tone": "Tone", "Tone": "Tone",
            "speech": "SpeechSynthesis", "SpeechSynthesis": "SpeechSynthesis"
        ]
        def capFilterRaw = actionSpec.capabilityFilter ?: "Switch"
        def capFilter = friendlyToKey[capFilterRaw.toString()] ?: capFilterRaw.toString()
        fields = [
            "useLastDev.@N": (actionSpec.useLastEventDevice == true),
            "myCapab.@N": capFilter,
            "devices.@N": deviceIds,
            "cCmd.@N": actionSpec.command
        ]
        // Parameter slot allocation: moreParams click is required for ALL params
        // (P is RM-assigned, starts at 2; P=1 is never used). Full live-verified
        // sequence in the __runCommandExtraParams block below.
        if (actionSpec.parameters instanceof List && !actionSpec.parameters.isEmpty()) {
            // Validate all parameters up-front so bad inputs fail fast before any hub writes.
            // Parameter entries: {type, value} for literal, {type, variable} for hub-variable.
            // Legacy scalar entries (bare String/Number) are passed through unchanged.
            // The actual per-parameter write is driven by the moreParams/P-discovery sequence
            // in the __runCommandExtraParams block after all base fields are written.
            actionSpec.parameters.eachWithIndex { p, paramIdx ->
                if (!(p instanceof Map)) return  // scalar (legacy) entries skip Map-level guards
                def pType = p.type
                def pValue = p.value
                def pVariable = p.variable
                // value/variable mutex: providing both is ambiguous. Mirrors the setVariable guard.
                if (pValue != null && pVariable != null) {
                    throw new IllegalArgumentException("runCommand parameter slot ${paramIdx + 1}: provide 'value' OR 'variable', not both")
                }
                // A Map entry with neither value nor variable writes only cpType<P>; RM bakes a
                // half-formed action with no actual parameter content.
                if (pValue == null && pVariable == null) {
                    throw new IllegalArgumentException("runCommand parameter slot ${paramIdx + 1}: Map entry must include 'value' (literal) or 'variable' (hub variable name)")
                }
                if (pType != null) {
                    def t = pType.toString().toLowerCase()
                    if (!(t in ["string", "number", "decimal"])) {
                        throw new IllegalArgumentException("runCommand parameter type '${pType}' invalid -- must be 'string', 'number', or 'decimal'")
                    }
                    // A non-numeric value for a numeric type produces cpVal<P> written with the
                    // wrong type, which RM silently accepts but renders incorrectly.
                    if (pValue != null && t in ["number", "decimal"] && !(pValue instanceof Number)) {
                        throw new IllegalArgumentException("runCommand parameter slot ${paramIdx + 1}: type '${t}' requires a numeric 'value' (Integer, Long, BigDecimal, etc.); got '${pValue}' -- use a number literal, not a string")
                    }
                }
                // Variable name is NOT pre-checked against getAllGlobalVars() here.
                // Validation happens post-reveal against the live xVar<P>.<N> enum in the
                // __runCommandExtraParams block, which is the authoritative constraint (RM
                // controls the scope). A pre-check would add a full hub API round-trip before
                // the moreParams/P-discovery sequence and still defer to the enum post-reveal,
                // buying only an earlier failure for obviously-unknown names at the cost of
                // additional complexity and an extra hub call per parameter.
            }
            actionSpec.__runCommandExtraParams = actionSpec.parameters
        }
    } else if (cap == "fileWrite") {
        // modeActs/getWriteLocalFile — write content to a local file (overwrite).
        actType = "modeActs"
        actSubType = "getWriteLocalFile"
        fields = ["localFile.@N": (actionSpec.fileName ?: ""), "fileContents.@N": (actionSpec.content ?: "")]
    } else if (cap == "fileAppend") {
        // modeActs/getAppendLocalFile — append content to existing file.
        // localFile.<N> is an enum (existing files only), not a free-text input.
        actType = "modeActs"
        actSubType = "getAppendLocalFile"
        fields = ["localFile.@N": (actionSpec.fileName ?: ""), "fileContents.@N": (actionSpec.content ?: "")]
    } else if (cap == "fileDelete") {
        actType = "modeActs"
        actSubType = "getDeleteLocalFile"
        fields = ["deleteFile.@N": (actionSpec.fileName ?: "")]
    } else if (cap == "zwavePoll") {
        // deviceActs/getStartStopZPoll — start or stop Z-Wave polling on switches/dimmers.
        //   ssZ.<N>     = true (start) | false (stop)
        //   ssSD.<N>    = true (Switches) | false (Dimmers)
        //   ssZPoll.<N> = devices (device.GenericZ-WaveSwitch) — Z-Wave devices only
        actType = "deviceActs"
        actSubType = "getStartStopZPoll"
        fields = [
            "ssZ.@N": (action == "start"),
            "ssSD.@N": (actionSpec.target == "switches" || actionSpec.target == null)
        ]
        if (deviceIds) fields["ssZPoll.@N"] = deviceIds
    } else if (cap == "button") {
        actType = "switchActs"
        switch (action) {
            case "push":
                // switchActs/getPushButton — push a specific button on a button device.
                // Verified live by capturing the working UI's
                // settings after a manual walkthrough:
                //   pushButton.<N>  = devices (capability.pushableButton, multiple=false)
                //   pushButNo.<N>   = button number (e.g. "1")
                //   pushButOp.<N>   = button operation: "push" (default),
                //                     "hold", "doubleTap", "release"
                //   varButNo.<N>    = (optional) toggle to use a hub variable
                //                     for the button number; left empty/false here
                //
                // Earlier code used a guessed "ButtonpushButton<N>" (no dot)
                // that doesn't exist; RM silently dropped it and the page
                // render fell into a broken code path. Don't blame RM.
                actSubType = "getPushButton"
                if (actionSpec.buttonNumber == null) throw new IllegalArgumentException("button.push requires 'buttonNumber' (Integer)")
                fields = [
                    "pushButton.@N": deviceIds,
                    "pushButNo.@N": actionSpec.buttonNumber,
                    "pushButOp.@N": (actionSpec.operation ?: "push")
                ]
                break
            case "pushPerMode":
                // switchActs/getPushButtonPerMode — push different buttons per mode.
                //   pushMBtn.<N>   = devices
                //   buttonModes.<N>= mode IDs
                //   button<modeID>.<N> = button number per mode
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
                // switchActs/getChooseButton — different DEVICES per mode, all push the same button.
                //   chooseButtonModes.<N>     = mode IDs
                //   chooseButton<modeID>.<N>  = devices for that mode
                //   chooseButtonNum.<N>       = button number
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
            default:
                throw new IllegalArgumentException("Unknown button action '${action}' -- supported: push, pushPerMode, choosePerMode")
        }
    } else if (cap == "log") {
        actType = "messageActs"
        actSubType = "getLogMsg"
        fields = ["logmsg.@N": (actionSpec.message ?: "")]
    } else if (cap == "notification") {
        actType = "messageActs"
        actSubType = "getMsg"
        fields = ["msg.@N": (actionSpec.message ?: ""), "note.@N": deviceIds]
    } else if (cap == "httpGet") {
        actType = "messageActs"
        actSubType = "getHTTPGet"
        fields = ["httper.@N": (actionSpec.url ?: "")]
    } else if (cap == "httpPost") {
        actType = "messageActs"
        actSubType = "getHTTPPost"
        fields = [
            "httper.@N": (actionSpec.url ?: ""),
            "httpPostBody.@N": (actionSpec.body ?: ""),
            "httpPostType.@N": (actionSpec.contentType ?: "application/x-www-form-urlencoded")
        ]
    } else if (cap == "ping") {
        actType = "messageActs"
        actSubType = "getPingIP"
        fields = ["pingIP.@N": (actionSpec.ip ?: "")]
    } else if (cap == "volume") {
        actType = "soundActs"
        actSubType = "getSetVolume"
        fields = ["volume.@N": deviceIds]
        if (actionSpec.level != null) fields["volumeVal.@N"] = actionSpec.level
    } else if (cap == "mute") {
        actType = "soundActs"
        actSubType = "getMuteUnmute"
        switch (action) {
            case "mute":   fields = ["mU.@N": true, "muteUnmute.@N": deviceIds]; break
            case "unmute": fields = ["mU.@N": false, "muteUnmute.@N": deviceIds]; break
            default: throw new IllegalArgumentException("Unknown mute action '${action}' -- supported: mute, unmute")
        }
    } else if (cap == "chime") {
        actType = "soundActs"
        actSubType = "getChime"
        fields = ["chime.@N": deviceIds]
        if (actionSpec.playStop != null) fields["chimePlayStop.@N"] = actionSpec.playStop
        if (actionSpec.soundNumber != null) fields["chimePlaySound.@N"] = actionSpec.soundNumber
    } else if (cap == "siren") {
        actType = "soundActs"
        actSubType = "getSiren"
        fields = ["siren.@N": deviceIds]
        if (actionSpec.sirenAction != null) fields["sirenAct.@N"] = actionSpec.sirenAction
    } else if (cap == "privateBoolean") {
        // pvTF.<N>: true=FALSE, false=True (verified live --
        // boolean is inverted relative to its field name).
        actType = "rulesActs"
        actSubType = "getSetPrivateBoolean"
        fields = [
            "pvRuleType.@N": "Rule Machine",
            "pvTF.@N": !(actionSpec.value as Boolean),
            "privateT.@N": (actionSpec.ruleIds ?: deviceIds)
        ]
    } else if (cap == "runRule") {
        actType = "rulesActs"
        actSubType = "getRuleActions"
        fields = [
            "runRuleType.@N": "Rule Machine",
            "ruleAct.@N": (actionSpec.ruleIds ?: deviceIds)
        ]
    } else if (cap == "cancelTimers") {
        actType = "rulesActs"
        actSubType = "getStopActions"
        fields = [
            "stopRuleType.@N": "Rule Machine",
            "stopAct.@N": (actionSpec.ruleIds ?: deviceIds)
        ]
    } else if (cap == "pauseRule") {
        // pR.<N>: true=RESUME, false=Pause (verified live --
        // boolean is inverted relative to its field name).
        actType = "rulesActs"
        actSubType = "getPauseResumeRules"
        switch (action) {
            case "pause":  fields = ["pR.@N": false, "pauseRuleType.@N": "Rule Machine", "pauseRule.@N": (actionSpec.ruleIds ?: deviceIds)]; break
            case "resume": fields = ["pR.@N": true,  "pauseRuleType.@N": "Rule Machine", "pauseRule.@N": (actionSpec.ruleIds ?: deviceIds)]; break
            default: throw new IllegalArgumentException("Unknown pauseRule action '${action}' -- supported: pause, resume")
        }
    } else if (cap == "capture") {
        actType = "deviceActs"
        actSubType = "getCapture"
        fields = ["capture.@N": deviceIds]
    } else if (cap == "restore") {
        actType = "deviceActs"
        actSubType = "getRestore"
        fields = [:]
    } else if (cap == "refresh") {
        actType = "deviceActs"
        actSubType = "getRefreshSwitch"
        fields = ["refresh.@N": deviceIds]
    } else if (cap == "poll") {
        actType = "deviceActs"
        actSubType = "getPollSwitch"
        fields = ["poll.@N": deviceIds]
    } else if (cap == "disableDevice") {
        // disEn.<N>: true=ENABLE, false=Disable (verified live --
        // boolean is inverted relative to its field name).
        actType = "deviceActs"
        actSubType = "getDisable"
        fields = ["disEn.@N": (action != "disable"), "devDisable.@N": deviceIds]
    } else if (cap == "delay") {
        // Verified live: a delay with no duration (no hours/minutes/seconds and no
        // variable source) registers but never bakes. Fail fast.
        if (actionSpec.variable == null && actionSpec.hours == null && actionSpec.minutes == null && actionSpec.seconds == null) {
            throw new IllegalArgumentException("delay action requires a duration: 'hours', 'minutes', and/or 'seconds' (or 'variable' for a variable-sourced delay). Without any, the action registers but never bakes. See hub_get_tool_guide(section='set_rule_create_reference').")
        }
        actType = "delayActs"
        actSubType = "getDelay"
        fields = [:]
        if (actionSpec.variable != null) {
            // Variable-sourced delay: uVar.<N>=true + xVar.<N>=<varName> picks the
            // hub variable that supplies the seconds value at fire time.
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
        // delayActs/getDelayPerMode — different delay durations per mode.
        // Field naming uses mode NAMES (Day/Away/etc), NOT mode IDs:
        //   delayModes.<N>            = mode IDs (the picker still uses IDs)
        //   delayHour<ModeName>.<N>   = hours for that mode
        //   delayMinute<ModeName>.<N> = minutes for that mode
        //   delaySecond<ModeName>.<N> = seconds for that mode (decimal)
        //   uVar<ModeName>.<N>        = bool, optional variable-source toggle
        actType = "delayActs"
        actSubType = "getDelayPerMode"
        if (!(actionSpec.perMode instanceof Map) || actionSpec.perMode.isEmpty()) {
            throw new IllegalArgumentException("delayPerMode requires perMode={modeIdOrName: {hours, minutes, seconds}, ...}")
        }
        def modeIds = _rmResolveModeIds(actionSpec.perMode.keySet())
        def modeNames = _rmResolveModeNames(actionSpec.perMode.keySet())
        fields = ["delayModes.@N": modeIds]
        modeNames.eachWithIndex { mname, i ->
            def cfg = actionSpec.perMode[modeIds[i]] ?: actionSpec.perMode[mname] ?: actionSpec.perMode[(modeIds[i] as Integer)]
            if (cfg instanceof Map) {
                if (cfg.hours != null)   fields["delayHour${mname}.@N"]   = cfg.hours
                if (cfg.minutes != null) fields["delayMinute${mname}.@N"] = cfg.minutes
                if (cfg.seconds != null) fields["delaySecond${mname}.@N"] = cfg.seconds
            }
        }
    } else if (cap == "cancelDelay") {
        actType = "delayActs"
        actSubType = "getCancelDelay"
        fields = [:]
    } else if (cap == "exitRule") {
        actType = "delayActs"
        actSubType = "getExitRule"
        fields = [:]
    } else if (cap == "comment") {
        actType = "delayActs"
        actSubType = "getComment"
        fields = ["comment.@N": (actionSpec.text ?: "")]
    } else if (cap == "repeat") {
        // Verified live: a repeat with no interval (hours/minutes/seconds) registers
        // but never bakes -- 'times' alone is NOT enough. Fail fast.
        if (actionSpec.hours == null && actionSpec.minutes == null && actionSpec.seconds == null) {
            throw new IllegalArgumentException("repeat action requires an interval: 'hours', 'minutes', and/or 'seconds' (how often to repeat). 'times' alone does not bake. Without an interval the action registers but never bakes. See hub_get_tool_guide(section='set_rule_create_reference').")
        }
        actType = "repeatActs"
        actSubType = "getRepeat"
        fields = [:]
        if (actionSpec.hours != null) fields["repeatHour.@N"] = actionSpec.hours
        if (actionSpec.minutes != null) fields["repeatMinute.@N"] = actionSpec.minutes
        if (actionSpec.seconds != null) fields["repeatSecond.@N"] = actionSpec.seconds
        if (actionSpec.times != null) fields["repeatN.@N"] = actionSpec.times
        if (actionSpec.stoppable != null) fields["stopRepeat.@N"] = actionSpec.stoppable
    } else if (cap == "stopRepeat") {
        actType = "repeatActs"
        actSubType = "getStopRepeat"
        fields = [:]
    } else if (cap == "repeatWhile") {
        // Repeat While Expression — repeatActs/getWhile. Embeds an
        // expression (same wizard as ifThen) PLUS interval/Stoppable
        // fields (same as basic repeat). Verified live the
        // doActPage schema after actSubType=getWhile exposes:
        //   cond (expression builder)
        //   uVar.<idx> (Variable repeat interval? bool)
        //   repeatHour/Minute/Second.<idx>
        //   uVar2.<idx> (Repeat variable n times? bool)
        //   repeatN.<idx>
        //   stopRepeat.<idx>
        // RM 5.1 has no "Repeat Until Expression" — use repeatWhile
        // with NOT on the expression for the inverse semantic.
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
        // Wait for Events — delayActs/getWaitEvents. Each event row uses
        // dash-separated index: tCapab-<eventIdx>, tDev-<eventIdx>,
        // tstate-<eventIdx>. After actSubType=getWaitEvents the wizard
        // exposes tCapab-1; writing it reveals tDev-1; writing devices
        // reveals tstate-1; writing state advances to next event slot
        // OR exposes timeout/done. Verified live.
        actType = "delayActs"
        actSubType = "getWaitEvents"
        fields = [:]
        if (!(actionSpec.events instanceof List) || (actionSpec.events as List).isEmpty()) {
            throw new IllegalArgumentException("waitEvents action requires events=[{capability, deviceIds, state}, ...] (non-empty)")
        }
    } else if (cap == "waitExpression") {
        // Wait for Expression — delayActs/getWaitRule. Embeds an
        // expression PLUS optional timeout (uses delayAct.<idx> +
        // delayHor/Min/Sec.<idx> — same fields as the regular Delay
        // modifier on actions) and durChoice.<idx> for Use Duration mode.
        // Verified live.
        actType = "delayActs"
        actSubType = "getWaitRule"
        fields = [:]
        if (!(actionSpec.expression instanceof Map)) {
            throw new IllegalArgumentException("waitExpression action requires expression={conditions:[...], operator?:..., operators?:[...]}")
        }
    } else if (cap == "ifThen" || cap == "elseIf") {
        // IF Expression THEN / ELSE-IF Expression THEN — embeds an
        // expression (same shape as Required Expression) inside the
        // action. After actType=condActs + actSubType=getIfThen/getElseIf,
        // the wizard exposes the cond enum and we walk the expression
        // identically to STPage. The expression spec lives on the
        // actionSpec as `expression: {conditions: [...], operator/operators}`.
        actType = "condActs"
        actSubType = (cap == "ifThen") ? "getIfThen" : "getElseIf"
        fields = [:]  // expression-walking happens AFTER actSubType write
        if (!(actionSpec.expression instanceof Map)) {
            throw new IllegalArgumentException("${cap} action requires expression={conditions:[...], operator?:..., operators?:[...]}")
        }
    } else if (cap == "else") {
        actType = "condActs"
        actSubType = "getElse"
        fields = [:]
    } else if (cap == "endIf") {
        actType = "condActs"
        actSubType = "getEndIf"
        fields = [:]
    } else {
        throw new IllegalArgumentException("Unsupported capability '${cap}' -- supported: switch, dimmer, color, colorTemp, lock, thermostat, shade, fan, mode, setVariable, setLocalVariable, runCommand, log, notification, httpGet, httpPost, ping, volume, mute, chime, siren, privateBoolean, runRule, cancelTimers, pauseRule, capture, restore, refresh, poll, disableDevice, delay, cancelDelay, exitRule, comment, repeat, stopRepeat, repeatWhile, ifThen, elseIf, else, endIf, waitExpression, waitEvents. For not-yet-mapped subtypes (per-mode/per-button/etc.), use rawSettings={fieldName: value, ...} with @N placeholder.")
    }

    // Open the new-action editor.
    // CRITICAL: stateAttribute must be 'doActN' (concatenated), not 'doAct'.
    // Verified live: stateAttribute=doAct sets state.doAct='N' which leaves
    // state.doActN null, and doActPage's renderer NPEs with startsWith on
    // null. The Hubitat UI's buttonClick(this) handler concatenates the
    // data-stateAttribute='doAct' attribute with the button name 'N' before
    // POSTing — so we mirror the post-concatenation form here.
    _rmClickAppButton(appId, "N", "doActN", "selectActions")

    // Re-read the index RM actually allocated. RM keeps a high-water mark
    // (state.actNdx) — even after clearActions deletes all actions, the
    // next "Create New Action" click allocates idx = high_water + 1,
    // not idx = 1. Verified live: a rule that had actions
    // 1/2/3 deleted then opens the wizard with actType.4 (not actType.1).
    // Use the schema's freshly-exposed actType.<N> as ground truth.
    def doActPageCfg = _rmFetchConfigJson(appId, "doActPage")
    def doActSchema = _rmCollectInputSchema(doActPageCfg?.configPage)
    def actTypeField = doActSchema?.keySet()?.find { it.toString() ==~ /^actType\.\d+$/ }
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

    // Set actType + actSubType. Each write re-fetches the schema, so the
    // subsequent fields appear as the wizard expands.
    _rmWriteSettingOnPage(appId, "doActPage", "actType.${idx}", actType, applied, null, skipped)
    _rmWriteSettingOnPage(appId, "doActPage", "actSubType.${idx}", actSubType, applied, null, skipped)

    // Pre-emptive cond=[] write (schema-gated). For expression-type actions
    // (getIfThen/getElseIf/getWhile/getWaitRule), this resets any stale cond
    // accumulator before the expression builder writes cond=a. For non-expression
    // actions, cond is not in doActPage's schema, so this write is silently
    // skipped -- the atomicState.predCapabs context for non-expression actions
    // was already cleared by _rmClearPredCapabsViaGhostIfThen (called from
    // _rmAddRequiredExpression after the expression-builder hasRule click) before
    // this method was called.
    def condContextClear = []
    _rmWriteSettingOnPage(appId, "doActPage", "cond", [], applied, null, condContextClear)
    if (!condContextClear.isEmpty()) {
        mcpLog("debug", "rm-native", "_rmAddAction: cond not in doActPage schema after actType/actSubType for app ${appId} action ${idx} (${condContextClear[0]?.reason}) -- skipped (non-expression action; predCapabs already cleared via ghost ifThen in addRequiredExpression)")
    }

    // Type-specific fields. The @N placeholder in keys is substituted with
    // the action index here.
    fields.each { rawKey, value ->
        if (value != null) {
            def fieldName = rawKey.toString().replace("@N", idx.toString())
            _rmWriteSettingOnPage(appId, "doActPage", fieldName, value, applied, null, skipped)
        }
    }

    // Action subtypes that embed an expression. The cond enum is exposed
    // after actType+actSubType writes, identical to STPage's Required
    // Expression wizard. Walk each condition (cond=a → rCapab_<X> →
    // rDev_<X> → state_<X> → hasAll) with optional joining operators
    // (oper between conditions), then hasRule to commit. Subtypes:
    //   condActs/getIfThen, condActs/getElseIf — full IF/ELSE-IF
    //   repeatActs/getWhile — Repeat While Expression
    //   delayActs/getWaitRule — Wait for Expression
    // Wait for Events: walk each event row using tCapab-<N>/tDev-<N>/
    // tstate-<N> (dash-separated index). After tstate-<N> is written,
    // a hasAll button appears ("Done with this Wait Event"). Click it
    // to commit the event and reveal tCapab-<N+1> for the next event.
    // Without the hasAll click, multi-event rules fail because tCapab-2
    // never appears in schema. Verified live.
    // Optional timeout via the existing delay-modifier path.
    if (actSubType == "getWaitEvents") {
        def events = actionSpec.events as List
        events.eachWithIndex { evRaw, evIdx ->
            if (!(evRaw instanceof Map)) {
                throw new IllegalArgumentException("waitEvents.events[${evIdx}] is not a Map")
            }
            def ev = evRaw as Map
            def evCap = ev.capability?.toString()?.trim()
            if (!evCap) throw new IllegalArgumentException("waitEvents.events[${evIdx}].capability is required")
            def n = evIdx + 1
            // Validate + canonicalize capability against the live enum.
            // Retry briefly: after the prior anotherWait click, RM's state.actNdx
            // advance is observable via plain GET but occasionally the first
            // fetch races with the click's commit (server processes the click
            // before persisting the new schema). Retrying once after a short
            // pause gives the hub a tick to catch up.
            def cfg = null
            def inputs = null
            def capInput = null
            for (int attempt = 0; attempt < 4; attempt++) {
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
                throw new IllegalArgumentException("waitEvents.events[${evIdx}].capability '${evCap}' not in option list. Valid: ${opts.collect { it.toString() }.sort().join(', ')}")
            }
            _rmWriteSettingOnPage(appId, "doActPage", "tCapab-${n}", canon, applied, null, skipped)
            if (ev.deviceIds != null) {
                _rmWriteSettingOnPage(appId, "doActPage", "tDev-${n}", ev.deviceIds, applied, null, skipped)
            }
            if (ev.state != null) {
                _rmWriteSettingOnPage(appId, "doActPage", "tstate-${n}", ev.state, applied, null, skipped)
            }
            // Optional 'and stays for' duration.
            if (ev.andStays == true) {
                _rmWriteSettingOnPage(appId, "doActPage", "stays-${n}", true, applied, "bool", skipped)
            }
            // Click hasAll to commit this event. Verified live:
            // after hasAll, the schema replaces tCapab-<N>/tDev/tstate
            // with two new buttons:
            //   - anotherWait  ("Add another Wait Event")
            //   - doneWaits    ("Done with Wait Events")
            // For non-last events, clicking anotherWait reveals tCapab-<N+1>
            // and the wizard loop continues. The last event leaves the
            // wizard in the 'doneWaits' state — the existing actionDone
            // click below handles the final commit.
            //
            // anotherWait MUST carry stateAttribute=anotherWait — Chrome XHR
            // capture 2026-04-26 (rule 1381 doActPage probe) showed the
            // live UI POSTs `stateAttribute=anotherWait` alongside
            // `settings[anotherWait]=clicked`. Without it the click is
            // accepted (200 OK) but RM doesn't advance state.actNdx for
            // the new event row, so the next tCapab-<N+1> never appears
            // in the schema and event 2/3/N silently drops.
            _rmClickAppButton(appId, "hasAll", null, "doActPage")
            if (evIdx < events.size() - 1) {
                _rmClickAppButton(appId, "anotherWait", "anotherWait", "doActPage")
            }
        }
    }

    def expressionSubtypes = ["getIfThen", "getElseIf", "getWhile", "getWaitRule"]
    if (expressionSubtypes.contains(actSubType)) {
        // Pre-expression timeout/duration writes for getWaitRule. The
        // wizard's schema shows cond + durChoice + delayAct together
        // BEFORE hasRule, but hasRule advances past them. So write
        // timeout fields here (while they're still in schema) — for
        // delayAct/delayHor/Min/Sec the existing actionSpec.delay
        // path runs AFTER the expression block, which is too late.
        // Workaround: peek at actionSpec.delay here and write the
        // delay-modifier fields up-front for getWaitRule.
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
                // Mark the delay-modifier as already-handled so the
                // generic delay-modifier path below skips it.
                actionSpec.__delayHandledForWaitRule = true
            }
        }
        def exprSpec = actionSpec.expression as Map
        def conditions = exprSpec.conditions
        if (!(conditions instanceof List) || conditions.isEmpty()) {
            throw new IllegalArgumentException("${cap} action's expression.conditions must be a non-empty List")
        }
        def operator = exprSpec.operator?.toString()?.toUpperCase()
        def opsList = null
        if (exprSpec.operators instanceof List) {
            opsList = (exprSpec.operators as List).collect { it?.toString()?.toUpperCase() }
            if (opsList.size() != conditions.size() - 1) {
                throw new IllegalArgumentException("${cap}.expression.operators must have length conditions.size()-1")
            }
        }
        if (conditions.size() > 1 && !operator && !opsList) {
            throw new IllegalArgumentException("${cap}.expression with ${conditions.size()} conditions requires operator (AND/OR/XOR) or operators list")
        }
        // Pre-validate device existence per condition (same as STPage).
        conditions.eachWithIndex { c, i ->
            if (!(c instanceof Map)) return
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
        // writeAct: doActPage field-write closure -- same signature as writeST (STPage
        // caller), with hrefParams accepted and ignored since _rmWriteSettingOnPage
        // derives page context from its pageName argument. Routes into the shared
        // applied/skipped accumulators owned by _rmAddAction's outer scope.
        def writeAct = { Map params, String fieldKey, Object fieldValue, String label = null ->
            _rmWriteSettingOnPage(appId, "doActPage", fieldKey, fieldValue, applied, null, skipped)
        }
        // cancelInFlightActCond: cancel the in-flight condition wizard on doActPage.
        // Invoked by _rmWalkConditionReveal before throwing on validation failure so
        // the caller does not leave the wizard half-open. Track cleanup failure so the
        // outer error result can surface wizardStuck for the caller.
        // actCancelledByWalker mirrors STPage's cancelledByWalker flag -- without it,
        // a walker-side throw + outer-catch fallback would issue two cancelCapab clicks
        // back-to-back (the walker's own call inside cancelInFlightActCond, then the
        // outer catch's redundant call). The second always fails (nothing to cancel),
        // setting actWizardCleanupFailed=true and surfacing a false wizardStuck=true.
        // currentActCondIdx is updated by the per-condition loop below so cancel-cleanup
        // warn messages name the in-flight condition index -- without it, N back-to-back
        // failures produce N indistinguishable log entries.
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
        }
        // doActPage condition-wizard walk: write cond=a, discover the firmware-assigned
        // cIdx from the schema (the slot number RM assigns is unpredictable), validate
        // the capability option, then delegate the per-capability reveal sequence to
        // _rmWalkConditionReveal (handles rCapab->rDev->comparator->state->hasAll in
        // the correct progressive-disclosure order, same invariants as STPage).
        // The condition-wizard field names are identical on both pages (RelrDev_<N>,
        // rCustomAttr_<N>, state_<N>, etc.) -- only the page name differs.
        conditions.eachWithIndex { condRaw, i ->
            currentActCondIdx = i
            if (!(condRaw instanceof Map)) {
                throw new IllegalArgumentException("${cap}.expression.conditions[${i}] is not a Map")
            }
            def cond = condRaw as Map
            // Reject subExpression on this path until the recursive walker lands -- the
            // doActPage walker only handles the flat condition shape today (the pre-pass in
            // `_rmAddAction`'s subExpression rejection also catches this before reaching
            // here, but this in-walker check is the defense-in-depth half of the gate).
            // Surface a targeted message rather
            // than the generic "capability is required" that the next check would produce.
            // _rmAddRequiredExpression (STPage) supports nested subExpression today.
            if (cond.subExpression != null) {
                throw new IllegalArgumentException("${cap}.expression.conditions[${i}]: nested subExpression on this row is not yet supported. _rmAddRequiredExpression (addRequiredExpression) supports nested expressions; on ${cap} rows, either flatten the condition list, or capture the sub-expression as a Required Expression instead.")
            }
            def ccap = cond.capability?.toString()?.trim()
            if (!ccap) throw new IllegalArgumentException("${cap}.expression.conditions[${i}].capability is required")
            _rmWriteSettingOnPage(appId, "doActPage", "cond", "a", applied, null, skipped)
            // Discover the live condition index from the schema.
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
                throw new IllegalArgumentException("${cap}.expression.conditions[${i}].capability '${ccap}' not in doActPage option list. Valid: ${capOptions.collect { it.toString() }.sort().join(', ')}")
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
                // Mirror STPage outer-catch symmetry: only cancel if the walker did not
                // already do so before throwing. _rmFetchConfigJson exceptions from inside
                // _rmRevealStep propagate out of the walker WITHOUT calling cancelInFlightCondition,
                // so the outer catch must issue the cancel itself. Without this fallback the
                // wizard stays half-open and the next addAction starts in a broken state.
                if (!actCancelledByWalker) {
                    cancelInFlightActCond()
                }
                if (actWizardCleanupFailed) {
                    // Both the per-condition write AND the cancel cleanup failed --
                    // embed the marker so the dispatcher's catch can surface wizardStuck.
                    throw new IllegalStateException("${perCondExc.message} [wizardStuck -- cancelCapab cleanup also failed: ${actWizardCleanupErr}]")
                }
                throw perCondExc
            }
            // Joining operator for non-last conditions.
            if (i < conditions.size() - 1) {
                def gapOp = opsList ? opsList[i] : operator
                if (gapOp) {
                    _rmWriteSettingOnPage(appId, "doActPage", "oper", gapOp, applied, null, skipped)
                }
            }
        }
        // hasRule SEALS the expression to this action (binds the conds
        // we just walked to actType.<idx>'s expression scope). Without
        // it, RM's paragraph render conflates conds across multiple
        // IF/ELSE-IF actions because there's no per-action expression
        // binding in the settings (verified live: rule with
        // ifThen + elseIf both rendered as "(Sw1 is on Sw2 is on)" —
        // both conds appearing in BOTH branches because hasRule was
        // skipped on single-cond expressions).
        //
        // Always click hasRule. RM tolerates the click even when the
        // button isn't yet visible in the schema (the click endpoint
        // accepts the button name regardless; if RM has nothing to do
        // it's a no-op). For multi-cond expressions hasRule appears
        // immediately after the last hasAll; for single-cond it appears
        // only on the post-hasAll schema after a settle. The
        // try/catch handles the rare case where the wizard isn't in a
        // state where hasRule is a valid click target.
        try {
            _rmClickAppButton(appId, "hasRule", null, "doActPage")
        } catch (Exception ignored) { /* tolerated — wizard may already be sealed */ }

        // (Timeout/durChoice for getWaitRule are written BEFORE the
        // expression walk above — see pre-expression block.)
    }

    // Optional Delay? modifier on an action. Verified live:
    //   delayAct.<N> options: ["none", "hrs:min:sec", "variable"]
    //   After delayAct=hrs:min:sec the schema exposes:
    //     delayHor.<N>  (number)  Hours
    //     delayMin.<N>  (number)  Minutes
    //     delaySec.<N>  (decimal) Seconds
    //     randomAct.<N> (bool)    Random?
    //     cancelAct.<N> (bool)    Cancelable?
    //   After delayAct=variable the schema exposes:
    //     xVarD.<N>     (enum)    Select variable (hub variable name)
    //     randomAct.<N>, cancelAct.<N>
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

    // runCommand parameters. Live-verified wire sequence (RM 5.1):
    //   For each parameter (including the first):
    //   1. Click the "moreParams" button -- this allocates the next param slot.
    //      RM assigns a param-number P starting at 2 for the first param; P is
    //      NOT predictable -- always discover it from the schema (see step 2).
    //   2. Re-introspect doActPage. Find the newly-revealed cpType<P>.<N> field
    //      by scanning for names matching /^cpType(\d+)\.N$/. P is RM-assigned.
    //   3. Write cpType<P>.<N> = type (lowercase: number/decimal/string).
    //      This reveals uVar<P>.<N> (bool toggle) and cpVal<P>.<N> (literal text).
    //   4. Variable-sourced param: write uVar<P>.<N>="true" -> re-introspect ->
    //      xVar<P>.<N> appears (an ENUM of live hub-variable names) and cpVal<P>.<N>
    //      disappears. Validate the target var is among xVar's options; fail loud if absent.
    //      Write xVar<P>.<N> = variableName.
    //      Literal-value param: write cpVal<P>.<N> = value directly.
    //   5. Repeat steps 1-4 for each subsequent parameter.
    //   Persisted result for a variable param: cpType<P>.N=type, uVar<P>.N="true",
    //   xVar<P>.N=varName -- renders "setLevel(<varName>) on <device>".
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

            // Step 1+2: snapshot doActPage schema, click moreParams to allocate the next
            // param slot, re-fetch and identify the newly-revealed cpType<P>.N field.
            // _rmRevealStep encapsulates the pre-snapshot/trigger/post-fetch/diff sequence.
            def cpTypeReveal = _rmRevealStep(appId, "doActPage", "cpType\\d+\\.${idx}".toString(), {
                _rmClickAppButton(appId, "moreParams", null, "doActPage")
            })
            def newCpTypeField = cpTypeReveal.input?.name?.toString()
            if (!newCpTypeField) {
                mcpLog("warn", "rm-native", "runCommand[${actionSpec.command}]: moreParams click did not reveal a new cpType<P> field for action ${idx} param ${paramIdx + 1}; param skipped")
                // Add a sentinel so skipped is non-empty, which drives partial=true at result assembly.
                skipped << [key: "param${paramIdx + 1}", reason: "moreParams_no_reveal"]
                return
            }
            // Extract the cpType<P> base name (e.g. "cpType2" from "cpType2.1").
            def cpTypeBase = newCpTypeField.toString().replaceAll("\\.\\d+\$", "")
            // Extract P (the RM-assigned param number, e.g. 2 from "cpType2").
            def pNumStr = cpTypeBase.replaceAll("^cpType", "")

            // Step 3: write cpType<P>.N = type. This reveals uVar<P>.N and cpVal<P>.N.
            _rmWriteSettingOnPage(appId, "doActPage", "${cpTypeBase}.${idx}".toString(), pType, applied, null, skipped)

            if (pVariable != null) {
                // Step 4 (variable path): write uVar<P>.N="true" to switch the param
                // slot into variable-source mode, then discover and write xVar<P>.N.
                // uVar<P>.N stores as the string "true" (bool fields echo-match OK this way).
                def uVarField = "uVar${pNumStr}.${idx}".toString()
                _rmWriteSettingOnPage(appId, "doActPage", uVarField, "true", applied, "bool", skipped)
                // Re-introspect: xVar<P>.N (an enum of hub variable names) now appears;
                // cpVal<P>.N disappears. Validate the target variable is in the enum options.
                def xVarCfg = _rmFetchConfigJson(appId, "doActPage")
                def xVarField = "xVar${pNumStr}.${idx}".toString()
                def xVarInput = (xVarCfg?.configPage?.sections ?: []).collectMany { sec ->
                    (sec?.input ?: [])
                }.find { it?.name?.toString() == xVarField }
                if (!xVarInput) {
                    throw new IllegalArgumentException("runCommand: xVar field not revealed after enabling variable mode for param slot ${pNumStr} (expected '${xVarField}') -- hub may not support variable-sourced parameters for command '${actionSpec.command}'")
                }
                // Canonical reader handles every option shape (Map container, scalar, List-of-value-Maps),
                // shared with the setVariable source-variable + fromDevice/math modes so all enum reads match.
                def xVarOpts = _rmReadPickerOptionStrings(xVarInput)
                // Fail loud when options are absent or unreadable: writing an unvalidated
                // variable name would produce a silently-broken action. The canonical reader
                // returns a non-null list, so the idiomatic truthiness check covers empty/absent.
                if (!xVarOpts) {
                    throw new IllegalArgumentException("runCommand: xVar${pNumStr}.${idx} revealed but has no enumerable options -- cannot validate variable name '${pVariable}'. Hub may not expose variable list for command '${actionSpec.command}'")
                }
                if (!xVarOpts.any { it == pVariable.toString() }) {
                    throw new IllegalArgumentException("runCommand parameter variable '${pVariable}' is not in the hub variable enum for param slot ${pNumStr}. Available: ${xVarOpts.sort().join(', ')}")
                }
                _rmWriteSettingOnPage(appId, "doActPage", xVarField, pVariable.toString(), applied, null, skipped)
            } else if (pValue != null) {
                // Step 4 (literal path): write cpVal<P>.N directly.
                def cpValField = "cpVal${pNumStr}.${idx}".toString()
                _rmWriteSettingOnPage(appId, "doActPage", cpValField, pValue, applied, null, skipped)
            }
        }
    }

    // setVariable copy-from-variable: source-variable field is schema-gated.
    // RM 5.1 reveals the source-variable enum ONLY after numOp.<N>="variable" is written.
    // Discover the actual field name from the live schema (observed as xVar3.<N>) rather
    // than hardcoding it -- RM's field naming is firmware-version-specific.
    // Fail loud if the reveal does not materialise: a missing field means the write
    // would silently be skipped, leaving an action that bakes without a source variable.
    if (actionSpec.__setVariableSourceVar != null) {
        def srcVar = actionSpec.__setVariableSourceVar.toString()
        // Name the actual capability the caller invoked (setVariable vs setLocalVariable),
        // stashed when the markers were set; defaults to setVariable for safety.
        def capLbl = actionSpec.__setVariableCapLabel ?: "setVariable"
        // numOp=variable must have landed for the schema-gated source-variable field to appear.
        _rmAssertNumOpLanded(idx, applied, skipped, "source-variable")
        def srcCfg = _rmFetchConfigJson(appId, "doActPage")
        def srcInputs = (srcCfg?.configPage?.sections ?: []).collectMany { sec -> (sec?.input ?: []) }
        // Match xVar<digits>.<N> -- the source-variable enum for getSetVariable.
        // xVarV.<N> (target field, already written) and xVarD.<N> (delay-variable) don't match
        // \d+ because 'V' and 'D' are not digits, so the pattern naturally excludes them.
        def xVarMatches = srcInputs.findAll { inp ->
            inp?.name?.toString()?.matches("xVar\\d+\\.${idx}")
        }
        if (!xVarMatches) {
            def visibleNames = srcInputs.collect { it?.name?.toString() }.findAll { it }.join(', ') ?: "(none -- schema returned empty)"
            throw new IllegalArgumentException("${capLbl}: source-variable field was not revealed after writing numOp=variable for action ${idx} -- hub may not support copy-from-variable at this action position. Expected a field matching xVar<digits>.${idx}. Visible fields: ${visibleNames}")
        }
        if (xVarMatches.size() > 1) {
            // More than one numeric xVar at this action slot is unexpected. Surface it loudly
            // so the caller can inspect the schema rather than silently picking the first.
            def allNames = xVarMatches.collect { it?.name?.toString() }.join(', ')
            throw new IllegalArgumentException("${capLbl}: schema contains ${xVarMatches.size()} candidate source-variable fields for action ${idx} (${allNames}); expected exactly one. Use rawSettings to write the correct field explicitly.")
        }
        def xVar3Input = xVarMatches[0]
        def xVar3Field = xVar3Input.name.toString()
        // Canonical reader handles every option shape (Map container, scalar, List-of-value-Maps),
        // shared with the fromDevice/math source modes so all three read enums identically.
        def xVar3Opts = _rmReadPickerOptionStrings(xVar3Input)
        // Fail loud when the revealed enum is empty: an unvalidated write would produce a
        // silently-broken action with no source variable persisted.
        if (xVar3Opts == null || xVar3Opts.isEmpty()) {
            throw new IllegalArgumentException("${capLbl}: revealed field '${xVar3Field}' has no enumerable options -- cannot validate sourceVariable '${srcVar}'. Hub may not expose the variable list at this action position.")
        }
        if (!xVar3Opts.any { it == srcVar }) {
            // The target + numOp=variable already landed before this deferred reveal-enum
            // check, so the throw leaves a partial action row (target set, no source). Warn
            // the caller and point at the auto-snapshot taken before the edit for recovery.
            throw new IllegalArgumentException("${capLbl}: sourceVariable '${srcVar}' is not in the revealed enum for '${xVar3Field}'. Available: ${xVar3Opts.sort().join(', ')}. A partial action row was written (target variable set, no source) -- remove it with hub_set_rule(removeAction:{index:N}) or restore the pre-edit auto-snapshot via hub_restore_backup.")
        }
        _rmWriteSettingOnPage(appId, "doActPage", xVar3Field, srcVar, applied, null, skipped)
    }

    // setVariable from-device: the device picker (customDev.<N>) and the attribute enum
    // (tCustomAttr.<N>) are schema-gated. RM reveals customDev.<N> only after
    // numOp.<N>="device attribute" lands, and tCustomAttr.<N> only after the device is
    // written (the attribute enum is FILTERED to the selected device's live attributes).
    // Field names are validated against the live schema before writing (existence check
    // gates each write). The names are fixed by RM's UI contract for these slots, so they
    // are hardcoded -- unlike __setVariableSourceVar, whose copy-variable slot number can
    // vary by firmware and is therefore regex-discovered.
    if (actionSpec.__setVariableFromDevice != null) {
        def capLbl = actionSpec.__setVariableCapLabel ?: "setVariable"
        def fd = actionSpec.__setVariableFromDevice
        def fdDeviceId = fd.deviceId.toString()
        def fdAttr = fd.attribute.toString()
        // numOp must have landed for the gated fields to appear.
        _rmAssertNumOpLanded(idx, applied, skipped, "device-attribute")
        // Step 1: customDev.<N> (capability.* single-device picker) must be revealed.
        def customDevField = "customDev.${idx}".toString()
        _rmRevealedInputOrThrow(appId, customDevField,
            "device picker '${customDevField}' was not revealed after writing numOp=device attribute for action ${idx} -- hub may not support read-from-device at this action position.")
        // Write the device id. The writer reads multiple=false from the schema and emits the
        // capability.* single-device 3-field contract. RM's picker spans all hub devices, so an
        // unknown id does not land; the next step's tCustomAttr reveal then fails loud
        // (success=false) because the attribute enum never appears without a selected device.
        _rmWriteSettingOnPage(appId, "doActPage", customDevField, [fdDeviceId], applied, null, skipped)
        // Step 2: tCustomAttr.<N> (attribute enum, filtered to the device) appears after the
        // device write. Validate the requested attribute against the revealed enum.
        def tCustomAttrField = "tCustomAttr.${idx}".toString()
        def tCustomAttrInput = _rmRevealedInputOrThrow(appId, tCustomAttrField,
            "attribute enum '${tCustomAttrField}' was not revealed after writing device '${fdDeviceId}' for action ${idx} -- the device id may not be in RM's picker (RM's customDev picker spans all hub devices; confirm the id exists).")
        // Canonical reader handles every option shape (Map container, scalar, List-of-value-Maps).
        def attrOpts = _rmReadPickerOptionStrings(tCustomAttrInput)
        if (attrOpts == null || attrOpts.isEmpty()) {
            throw new IllegalArgumentException("${capLbl}: revealed attribute enum '${tCustomAttrField}' has no enumerable options -- cannot validate attribute '${fdAttr}'. Device '${fdDeviceId}' may expose no readable attributes at this action position.")
        }
        // Match case-insensitively, then write the CANONICAL enum option (the hub's exact casing),
        // not the caller's -- RM stores the option verbatim, so the caller's casing could bake a
        // value the enum does not contain.
        def canonicalAttr = attrOpts.find { it?.equalsIgnoreCase(fdAttr) }
        if (canonicalAttr == null) {
            throw new IllegalArgumentException("${capLbl} fromDevice: attribute '${fdAttr}' is not in the device's attribute enum for action ${idx}. Available: ${attrOpts.sort().join(', ')}")
        }
        _rmWriteSettingOnPage(appId, "doActPage", tCustomAttrField, canonicalAttr, applied, null, skipped)
    }

    // setVariable variable-math: operands are schema-gated. RM reveals the first operand
    // (xVar3.<N>) and the operator (valMathOp.<N>) after numOp.<N>="variable math" lands.
    // A "(constant)" first operand reveals valConst.<N>. A binary operator reveals the second
    // operand (xVar4.<N>); a "(constant)" second operand reveals valConst2.<N>. Unary operators
    // take no second operand. Field names are validated against the live schema before writing
    // (existence check gates each write). The names are fixed by RM's UI contract for these
    // math slots, so they are hardcoded (unlike __setVariableSourceVar's regex-discovered slot).
    if (actionSpec.__setVariableMath != null) {
        def capLbl = actionSpec.__setVariableCapLabel ?: "setVariable"
        def m = actionSpec.__setVariableMath
        _rmAssertNumOpLanded(idx, applied, skipped, "variable-math")
        // The "(constant)" sentinel is RM's enum option that switches an operand to a literal.
        def constSentinel = "(constant)"
        // Canonical reader handles every option shape (Map container, scalar, List-of-value-Maps),
        // returning [] for absent/unreadable options. assertInEnum's empty-list guard covers that.
        def optsOf = { inp -> _rmReadPickerOptionStrings(inp) }
        // Fail loud if the requested operand/operator value is not in the revealed field's enum,
        // mirroring the sibling source-var/attribute validation. An unvalidated write would bake
        // a silently-broken action with the wrong (or no) operand/operator. role describes the slot.
        def assertInEnum = { String field, Object input, String wanted, String role ->
            def opts = optsOf(input)
            if (opts == null || opts.isEmpty()) {
                throw new IllegalArgumentException("${capLbl} math: revealed field '${field}' has no enumerable options -- cannot validate ${role} '${wanted}'. Hub may not expose the ${role} list at this action position.")
            }
            if (!opts.any { it == wanted }) {
                throw new IllegalArgumentException("${capLbl} math: ${role} '${wanted}' is not in the revealed enum for '${field}'. Available: ${opts.sort().join(', ')}")
            }
        }
        // Step 1: first operand (xVar3.<N>) + operator (valMathOp.<N>) appear after numOp.
        def mCfg = _rmFetchConfigJson(appId, "doActPage")
        def mInputs = (mCfg?.configPage?.sections ?: []).collectMany { sec -> (sec?.input ?: []) }
        def xVar3Field = "xVar3.${idx}".toString()
        def xVar3Input = mInputs.find { it?.name?.toString() == xVar3Field }
        def valMathOpField = "valMathOp.${idx}".toString()
        def valMathOpInput = mInputs.find { it?.name?.toString() == valMathOpField }
        if (!xVar3Input || !valMathOpInput) {
            def visibleNames = mInputs.collect { it?.name?.toString() }.findAll { it }.join(', ') ?: "(none -- schema returned empty)"
            throw new IllegalArgumentException("${capLbl}: variable-math operand/operator fields ('${xVar3Field}' + '${valMathOpField}') were not revealed after writing numOp=variable math for action ${idx} -- hub may not support variable math at this action position. Visible fields: ${visibleNames}")
        }
        // Validate the operator against valMathOp's options up-front (the field is already
        // revealed). _rmMathBinaryOps/_rmMathUnaryOps is the project's known partition, but the
        // live enum is the hub's authority -- a firmware that drops an operator is caught here.
        assertInEnum(valMathOpField, valMathOpInput, m.op.toString(), "operator")
        // Write the first operand: a Number becomes (constant)+valConst.<N>, else the var name.
        // _rmWriteMathOperand validates the chosen xVar3 option and writes verbatim constants.
        _rmWriteMathOperand(appId, idx, m.left, xVar3Field, "valConst.${idx}".toString(),
            "first operand", assertInEnum, xVar3Input, applied, skipped)
        // Write the operator. (Validated against the live enum above; arity in the handler.)
        _rmWriteSettingOnPage(appId, "doActPage", valMathOpField, m.op.toString(), applied, null, skipped)
        // Binary operator: write the second operand (xVar4.<N>), revealed after the op write.
        if (_rmMathBinaryOps().contains(m.op.toString())) {
            def xVar4Field = "xVar4.${idx}".toString()
            def xVar4Input = _rmRevealedInputOrThrow(appId, xVar4Field,
                "math: second-operand field '${xVar4Field}' was not revealed after writing binary operator '${m.op}' for action ${idx}.")
            _rmWriteMathOperand(appId, idx, m.right, xVar4Field, "valConst2.${idx}".toString(),
                "second operand", assertInEnum, xVar4Input, applied, skipped)
        }
    }

    // Caller escape hatch.
    if (actionSpec.rawSettings instanceof Map) {
        actionSpec.rawSettings.each { k, v ->
            if (v != null) {
                def fieldName = k.toString().replace("@N", idx.toString())
                _rmWriteSettingOnPage(appId, "doActPage", fieldName, v, applied, null, skipped)
            }
        }
    }

    // The predCapabs condition-context leak from a preceding
    // addRequiredExpression is cleared by _rmClearPredCapabsViaGhostIfThen
    // (called from _rmAddRequiredExpression after the expression-builder hasRule
    // click) before control returns here. By the time _rmAddAction runs,
    // atomicState.predCapabs is already clean regardless of whether this is
    // an expression or plain action type. No per-action
    // clearing is needed here.

    // Click actionDone (with form context) to commit.
    _rmClickAppButton(appId, "actionDone", null, "doActPage")

    // Navigate back to selectActions to commit the in-flight action.
    // Verified live in Chrome (2026-04-25) on a clean rule walkthrough:
    // the UI's "Done with Action" click triggers (1) the actionDone
    // button POST, then (2) full doActPage form submissions that carry
    // a `_action_href_name|selectActions|<idx>` navigation marker. The
    // server bakes the action when transitioning OFF doActPage. After
    // that sequence completes, state.actionList contains the new index,
    // state.actNdx is advanced, and NO updateRule is required.
    //
    // Mirror the navigation by POSTing to selectActions's update/json
    // endpoint with a minimal form body -- the server will commit the
    // action's settings and bake into actions[].
    _rmNavigateToPage(appId, "doActPage", "selectActions")

    // Final config-error check.
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
        // Bundled multi-action build (hub_set_rule create actions[], addActions,
        // replaceActions, patches): an open block (IF before its END-IF, Repeat
        // before its End-Repeat) is legitimately unbalanced until the closer
        // bakes later in the SAME call, so the structural-imbalance signal on
        // this INTERMEDIATE per-action snapshot is a transient false alarm that
        // nudges a needless hub_restore_backup. Drop ONLY structuralIssues from
        // this snapshot (plus the single combined "structural imbalance..." line
        // in issues, and recompute ok) -- brokenMarkers / multipleFlagPoison /
        // configPageError are kept, partial keys off brokenMarkers not this, and
        // the caller's FINAL post-batch _rmCheckRuleHealth still asserts any real,
        // persisting imbalance. Single addAction passes the default
        // intraBatch=false, so a lone unclosed block still surfaces correctly.
        def filteredIssues = ((health.issues as List) ?: []).findAll {
            !(it?.toString()?.startsWith("structural imbalance in action block nesting"))
        }
        // Recompute ok with the SAME predicate _rmCheckRuleHealth uses (issues + broken +
        // validationErrors), not issues alone, so the two ok derivations can't drift if the
        // broken/validationErrors issue strings ever change.
        health = health + [structuralIssues: [], issues: filteredIssues,
                           ok: filteredIssues.isEmpty() && health.broken != true && ((health.validationErrors as List) ?: []).isEmpty()]
    }

    // Post-commit silent-failure detection. Same class of issue as
    // addTrigger / addRequiredExpression: RM 5.1's doActPage silently
    // accepts invalid inputs at the field-write level without erroring,
    // and the action row may not bake — leaving mainPage's "Define
    // Actions" placeholder. The hub itself isn't erroring, so the LLM
    // needs the signal to self-correct. Verified.
    def actionNotBaked = false
    try {
        def mainCfg = _rmFetchConfigJson(appId, "mainPage")
        // Read BOTH paragraph formats the hub emits. Mirrors the dual-read
        // in _rmAddTrigger and _rmCheckRuleHealth -- see trigger site comment.
        def mainParagraphs = (mainCfg?.configPage?.sections ?: []).collectMany { sect ->
            def fromBody = (sect?.body ?: [])
                .findAll { b -> b instanceof Map && (b.element == "paragraph" || b.element == "href") }
                .collect { it.description?.toString() ?: "" }
            def fromParagraphs = (sect?.paragraphs ?: []).collect { it?.toString() ?: "" }
            fromBody + fromParagraphs
        }
        def joinedParagraphs = mainParagraphs.join("\n")
        actionNotBaked = joinedParagraphs.contains("Define Actions")
    } catch (Exception verifyExc) {
        verificationFetchFailed = true
        mcpLog("warn", "rm-native", "_rmAddAction: post-commit mainPage paragraph fetch failed for app ${appId} (${verifyExc.message}) -- action-baked check skipped")
    }

    // Partial-success signal: any skipped settings indicate a field the
    // caller asked for that didn't land. The action is still committed
    // (actType/actSubType set, row in actions[]), but it's incomplete and
    // worth retrying via hub_set_rule(walkStep) or replaceActions.
    // health.brokenMarkers non-empty means a PRIOR action/trigger is already
    // broken; the new action committed but the rule is in a known-bad state.
    // INFORMATIONAL sentinels (reveal_fallback_to_existing_field) are excluded
    // from the partial computation -- they signal a static-schema or already-
    // revealed-field walker path, NOT a genuine field-write failure. Mirrors
    // the exemption in _rmAddRequiredExpression's partial gate; the shared
    // _rmWalkConditionReveal walker emits the same sentinels on both pages,
    // so both call sites need the same exemption to match the documented
    // "INFORMATIONAL -- does NOT flip partial by itself" contract. Set sourced
    // from _rmInformationalSkippedReasons() so a future addition lands in one
    // place rather than requiring lockstep edits in both helpers.
    def informationalReasons = _rmInformationalSkippedReasons()
    def genuineSkipped = (skipped ?: []).findAll {
        !(it instanceof Map) || it.reason == null || !(it.reason in informationalReasons)
    }
    def partial = !genuineSkipped.isEmpty() || actionNotBaked ||
                  ((health?.brokenMarkers as List)?.size() > 0)
    def hubRenderError = err != null || (genuineSkipped.any { it?.available != null && (it.available as List).isEmpty() } as Boolean) || actionNotBaked
    def repairHints = []
    if (actionNotBaked) {
        repairHints << "Action did not bake — mainPage still shows 'Define Actions' placeholder. Common causes: required field for the (capability, action) pair was omitted (e.g. dimmer.setLevel needs 'level'; switch.setPerMode needs 'perMode'; runCommand needs 'command'), invalid deviceIds, or value out of range. Inspect the rule via hub_get_app_config(includeSettings=true) — settings.actType.<idx> set without matching subtype-specific fields means the action was registered but not committed. Use removeAction(${idx}) to clean up, then rebuild."
    }
    if (partial) {
        // Use genuineSkipped (excluding informational sentinels) so the repair
        // hint only names keys that actually failed to land. An informational
        // fallback entry would otherwise be reported as "didn't land" when in
        // fact the field was written via the already-revealed path.
        // comparator_force_written_unverified is written-but-unverified, NOT lost (the
        // walker's exposure-probe-failure fallback force-wrote the comparator and it IS in
        // settingsApplied). comparator_not_representable_for_enum_attribute is genuinely
        // unrepresentable. Both get their own specific hint below, so split them out so the
        // generic "didn't land -- introspect and re-write" hint does not mislead.
        def forceWrittenKeys = genuineSkipped.findAll { it instanceof Map && it.reason == "comparator_force_written_unverified" }*.key.findAll { it != null }
        def specificHintReasons = ["comparator_force_written_unverified", "comparator_not_representable_for_enum_attribute"]
        def lostSkipped = genuineSkipped.findAll { !(it instanceof Map && it.reason in specificHintReasons) }
        if (!lostSkipped.isEmpty()) {
            def skippedKeys = lostSkipped*.key.findAll { it != null }.join(', ')
            def settingWord = lostSkipped.size() == 1 ? "setting" : "settings"
            repairHints << "Some ${settingWord} didn't land: ${skippedKeys}. Use hub_set_rule(walkStep={page:'doActPage', operation:'introspect'}) to see the LIVE schema, then write the missing fields one at a time. The 'available' list on each skipped item shows what fields ARE in the schema right now. CAVEAT: if the introspect call returns an empty schema for the missing field, that field is likely wizard-past-state (write-only during initial action construction, no longer in the live input list). Verify via hub_get_app_config(appId) -- if the action paragraph renders the value correctly, the partial flag is cosmetic and the action is fully baked. Skip the repair."
            if (hubRenderError) {
                repairHints << "WARNING: doActPage may have rendered with an error (configPageError=${err}, or skipped items have empty available list). This is a hub-side issue, not a wire-format problem. The action partially committed; consider removeAction(${idx}) to clear the broken row, then retry with different deviceIds or a different action shape."
            }
            repairHints << "If retries still fail, removeAction(index:${idx}) to clean up, then call addAction again with corrections."
        }
        if (!forceWrittenKeys.isEmpty()) {
            def cmpWord = forceWrittenKeys.size() == 1 ? "Comparator" : "Comparators"
            def cmpVerb = forceWrittenKeys.size() == 1 ? "was" : "were"
            repairHints << "${cmpWord} ${forceWrittenKeys.join(', ')} ${cmpVerb} force-written via a degraded path after a transient re-fetch failure -- the value IS in settingsApplied and success stays true, but it could not be schema-confirmed. Verify via hub_get_app_config(appId): if the action paragraph renders the comparator correctly, the partial flag is cosmetic. Do NOT re-write -- only re-add via hub_set_rule(walkStep={...}) if the paragraph shows the comparator missing."
        }
        genuineSkipped.findAll { it instanceof Map && it.reason == "comparator_not_representable_for_enum_attribute" }.each { sk ->
            repairHints << _rmNotRepresentableEnumComparatorHint(
                (sk instanceof Map ? sk.attribute : null), (sk instanceof Map ? sk.value : null))
        }
        if ((health?.brokenMarkers as List)?.size() > 0) {
            repairHints << "Rule has pre-existing broken markers: ${(health.brokenMarkers as List).unique().join(', ')}. The new action committed, but run hub_get_rule_health(${appId}) and repair the existing broken trigger/action rows before this rule fires correctly."
        }
    }
    // success=true when: the API call completed without an error AND at least
    // one setting was written to the rule (actType.N or actSubType.N landed).
    // partial=true is an orthogonal caller-actionable signal -- the row exists
    // but some fields didn't land. Decoupling success from partial means the
    // caller checks success for "did anything happen" then partial for "is more
    // work needed" -- avoiding the false success=false when the row exists but
    // is incomplete.
    return [
        success: !err && !applied.isEmpty(),
        partial: partial,
        hubRenderError: hubRenderError,
        actionIndex: idx,
        capability: cap,
        action: action,
        actType: actType,
        actSubType: actSubType,
        settingsApplied: applied,
        settingsSkipped: skipped,
        configPageError: err,
        repairHints: repairHints,
        health: health,
        verificationFetchFailed: verificationFetchFailed
    ]
}

// Build a fresh condition for a conditional trigger. Drives the condition
// sub-wizard inside selectTriggers: isCondTrig.<N>=true → condTrig.<N>="a"
// (new condition) → walk rCapab_<N> / rDev_<N> / state_<N> / not<N>
// → click hasAll (Done with this Condition). After hasAll, the wizard
// advances state.moreCond by one index — _rmAddTrigger then re-opens the
// trigger editor at idx+1 to actually build the conditional trigger.
//
// Uses static direct-write order (no _rmRevealStep) **because** the
// selectTriggers condition sub-wizard exposes a narrower capability set than
// the expression-wizard pages (STPage/doActPage) and all supported fields
// are reliably schema-visible without progressive disclosure. Migrate to
// _rmRevealStep if Mode/Between two times/compareToDevice support is added here.
//
// Returns the condition's auto-assigned ID (currently equal to the index
// passed in, since RM allocates condition IDs sequentially starting at 1).
//
// Spec fields (parallel to trigger spec but for the condition):
// capability (required) — RM condition capabilities are a SUPERSET of
// trigger capabilities; extras include: Time of day, Time Since Event,
// Between two times, Between two dates, Days of week, On a Day,
// Window Shade, Fan Speed, Lock codes
// deviceIds (for device-based conditions)
// state (for enum-state conditions: "on", "active", "open", etc.)
// comparator + value (for numeric conditions)
// buttonNumber (for Button conditions)
// attribute (for Custom Attribute conditions)
// not (bool, default false) — sets not<N>=true to negate the condition
// rawSettings (escape hatch for advanced fields)
private Integer _rmBuildCondition(Integer appId, Integer idx, Map condSpec, List applied, List skipped = null) {
    def condCap = condSpec.capability?.toString()?.trim()
    if (!condCap) throw new IllegalArgumentException("condition.capability is required")

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
        // RM 5.1's condition wizard exposes RelrDev_<N> (with underscore,
        // 'Relr') as the comparator field on every condition-wizard page
        // (selectTriggers, doActPage's ifThen, STPage's required-expression)
        // — verified live 2026-05-17 on firmware 2.5.0.135 for Variable, and
        // already used by _rmAddAction (doActPage) and
        // _rmAddRequiredExpression (STPage) for Custom Attribute. The
        // previous compareCond_<N> name silently skipped on all three pages.
        //
        // Conditional-trigger condition site of the enum-recognized Custom
        // Attribute comparator invariant (see _rmForceWriteEnumField docstring for
        // the authoritative rule). Fields here: comparator=RelrDev_<N>, value
        // picker=state_<N>. Schema-gated _rmWriteSettingOnPage means a
        // neither-rendered comparator records not_in_schema (not silent
        // dead-storage); a throwing re-fetch force-writes it best-effort (partial).
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
                    // ENUM-recognized attribute: only state_<N> (value picker) is exposed.
                    // The no-RHS family is gated by _rmComparatorIsRhsOptional (markers in
                    // _rmRhsOptionalComparatorMarkers -- extend BOTH in lockstep if RM adds a
                    // new no-RHS token, or this branch silently falls through). A state-change
                    // comparator ('*changed*' / '*became*') has no RHS to place there; the
                    // routing here applies ONLY when no explicit value was supplied. If the
                    // picker offers an option matching the REQUESTED change token exactly, route
                    // it through the state_<N> write below; otherwise the comparator is
                    // unrepresentable through this path and must surface as a genuine skip
                    // (-> partial) rather than be silently dropped as clean success. An explicit
                    // value, when supplied, wins and lands in state_<N>.
                    if (_rmComparatorIsRhsOptional(condSpec.comparator)) {
                        def cStateVal = condSpec.state != null ? condSpec.state : condSpec.value
                        if (cStateVal != null) {
                            // Contradictory request (state-change comparator + explicit value):
                            // the value lands, the rule works as an equals-check, so the dropped
                            // change intent is reported via an INFORMATIONAL skip (no partial).
                            if (skipped == null) {
                                throw new IllegalStateException("_rmBuildCondition: informational skip for RelrDev_${idx} has nowhere to land -- the 'skipped' accumulator was not supplied. The degradation-surfacing path requires it; pass a non-null List.")
                            }
                            skipped << [key: "RelrDev_${idx}".toString(), reason: "state_change_comparator_ignored_explicit_value",
                                        value: _rmNormalizeComparator(condSpec.comparator), attribute: condSpec.attribute?.toString()]
                        } else {
                            def pickerInput = afterAttrInputs.find { it?.name == "state_${idx}".toString() }
                            // Family gate passed; require an EXACT token match (not just any
                            // RHS-optional option) so 'became false' never routes to 'became true'
                            // and 'unchanged' never satisfies a '*changed*' request. Normalize the
                            // requested token ONCE, not per-candidate.
                            def reqToken = _rmComparatorToken(condSpec.comparator)
                            def changeOption = _rmReadPickerOptionStrings(pickerInput).find { _rmComparatorTokenMatchesOption(reqToken, it) }
                            if (changeOption != null) {
                                condSpec.state = changeOption
                            } else {
                                if (skipped == null) {
                                    throw new IllegalStateException("_rmBuildCondition: not-representable skip for RelrDev_${idx} has nowhere to land -- the 'skipped' accumulator was not supplied. The degradation-surfacing path requires it; pass a non-null List.")
                                }
                                skipped << [key: "RelrDev_${idx}".toString(), reason: "comparator_not_representable_for_enum_attribute",
                                            value: _rmNormalizeComparator(condSpec.comparator), attribute: condSpec.attribute?.toString()]
                            }
                        }
                    }
                }
            }
        } else {
            // No attribute (numeric/text comparator on a standard capability) --
            // the comparator field is already exposed; write it directly.
            _rmWriteSettingOnPage(appId, "selectTriggers", "RelrDev_${idx}", _rmNormalizeComparator(condSpec.comparator), applied, null, skipped)
        }
    }
    if (condSpec.buttonNumber != null) {
        _rmWriteSettingOnPage(appId, "selectTriggers", "ButtontDev_${idx}", condSpec.buttonNumber, applied, null, skipped)
    }
    // Compare-to-variable for Variable conditions: isVar_<N>=true exposes
    // xVarR_<N> (right-hand variable picker). When isVar_<N> is false the
    // comparator's RHS is a numeric value in state_<N>. Verified live
    // 2026-05-17: writing isVar_<N>=true alone exposes xVarR_<N> in the
    // schema; without it, xVarR_<N> is silently dropped.
    //
    // If both writes get routed to `skipped` (e.g. the comparator wasn't
    // recognized, so the schema didn't advance to expose isVar_<N>), the
    // RHS would silently fall through to a numeric state_<N>=0 default
    // and the caller would think they got "A != B" but actually got
    // "A != 0". Detect that and fail loudly instead.
    if (condCap == "Variable" && condSpec.compareToVariable != null) {
        _rmWriteSettingOnPage(appId, "selectTriggers", "isVar_${idx}", true, applied, null, skipped)
        _rmWriteSettingOnPage(appId, "selectTriggers", "xVarR_${idx}", condSpec.compareToVariable, applied, null, skipped)
        // The RHS picker xVarR_<N> is the load-bearing write -- it carries
        // the variable name. If it didn't land, the condition will render
        // as "A <comparator> 0" (state_<N> default) instead of "A vs B".
        // Fail loudly rather than letting the caller think they got A vs B.
        if (!(applied ?: []).contains("xVarR_${idx}".toString())) {
            throw new IllegalStateException("condition.compareToVariable=${condSpec.compareToVariable} could not be written: xVarR_${idx} not in schema after the comparator + isVar_${idx} writes. Likely cause: condSpec.comparator='${condSpec.comparator}' didn't advance the wizard. Verify the comparator is one of: =, ≠ (or !=), <, >, <=, >=, in.")
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

// RM 5.1 setVariable "variable math" operator vocabulary (numOp="variable math").
// Binary operators take a second operand (reveal xVar4.<N>/valConst2.<N>); unary
// operators take only the first operand. Single source of truth: the addAction
// math-arity validator and the __setVariableMath post-write block must agree on
// this partition, so both read it from here rather than restating the list.
// Binary vs unary partition for setVariable variable-math operators. This local partition is
// the ARITY authority: the live valMathOp enum lists the operators but does NOT label their
// arity, so the handler cannot derive "needs a second operand" from the schema alone.
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

// Build the standard error response shape for _applyNativeAppEdit catch
// blocks. Detects pre-flight refusals (signalled by the "RM is not
// touched" sentinel that every pre-flight refusal throw includes) and
// adjusts the restoreHint to make clear that nothing was mutated — so
// the caller doesn't waste a hub_restore_backup call. Always attaches the
// current health (issue #254) so the caller sees the rule's compiled-state
// `broken` verdict straight off the failure — on a pre-flight refusal that is
// the existing damage that motivated the refusal; on a mutation failure it is
// whether the write left the rule broken — without a follow-up
// hub_get_rule_health call.
private Map _rmBuildUpdateErrorResponse(Integer appId, String msg, Map backup, String pageName = "doActPage") {
    def msgStr = msg?.toString() ?: ""
    def isPreflightRefusal = msgStr.contains("RM is not touched")
    // wizardStuck: mid-walk cancelCapab cleanup may have failed leaving the wizard
    // half-open. Independent of preflight refusal (preflight never opens the wizard).
    def wizardStuck = msgStr.contains("wizardStuck") || msgStr.contains("cancelCapab cleanup failed")
    def health = null
    try { health = _rmCheckRuleHealth(appId) } catch (Exception ignored) { /* best effort — never let a health read mask the real error */ }
    def restoreHint
    if (isPreflightRefusal) {
        restoreHint = "Pre-flight refusal -- RM was not touched; the saved backup is identical to the current rule and does not need to be restored."
    } else if (wizardStuck) {
        // pageName tells the caller which wizard page the cancelCapab recovery click belongs on
        // (doActPage for addAction, STPage for addRequiredExpression). The wizardStuck markers
        // themselves carry no page info, so callers thread it in.
        restoreHint = "Backup saved before write -- restore via hub_restore_backup with backupKey='${backup.backupKey}'. Or, before your next write, call hub_set_rule(button='cancelCapab', pageName='${pageName}', confirm=true) to manually close the in-flight wizard."
    } else {
        restoreHint = "Backup saved before write. Call hub_restore_backup with backupKey='${backup.backupKey}' to roll back."
    }
    def result = [
        success: false,
        appId: appId,
        error: msg,
        wizardStuck: wizardStuck,
        backup: backup,
        restoreHint: restoreHint
    ]
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

// Single source of truth for the "a committed Required Expression is showing on
// STPage" tell. On RM 5.1.8 a committed RE lands STPage on the committed-expression
// controls (cancelST "Delete Required Expression" + editST), with the conditions on a
// separate selectConditions sub-page so the inline new-condition selector (cond /
// rCapab_<N>) is withheld. The stable marker is the cancelST+editST control pair with
// NO inline new-condition selector.
//
// `requireStopOnST` selects between the two callers' intentionally-different tells:
// - false (replace path): the two-field cancelST+editST pair. cancelST+editST co-occur
// ONLY in the committed-RE state, and the !newCondSelector guard rules out the
// building state, so two fields uniquely identify a committed RE. stopOnST is
// excluded because its presence varies across firmware revisions.
// - true (add path's existing-RE guard): additionally requires stopOnST. The add path
// wants the stricter three-field tell so a transient render that happens to show
// cancelST+editST without stopOnST does not trip its refusal.
// The difference is deliberate and is preserved here rather than collapsed.
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
                        opts << [value: k.toString(), label: o[k]?.toString()]
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
    return [
        inputs: inputs,
        hrefs: hrefs,
        commitButtons: [
            actionDone: hasActionDone,
            hasAll: hasHasAll,
            cancel: hasCancel
        ]
    ]
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
// validateEnum?: bool,  // if true, reject writes whose value
// // isn't in the input's options list
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
private Map _rmWalkStep(Integer appId, Map spec) {
    // "drive" runs an ordered sequence of single-step operations in ONE call --
    // the progressive flow that replaces the manual introspect -> navigate ->
    // write each field -> done -> finalize loop the caller used to issue as N
    // separate calls. Handled before the single-step page check because a drive
    // carries its page per step, not at the top level.
    if (spec?.operation?.toString()?.trim() == "drive") {
        return _rmDriveWalkSteps(appId, spec)
    }
    def page = spec?.page?.toString()?.trim()
    if (!page) throw new IllegalArgumentException("walkStep.page is required (e.g. 'selectTriggers', 'selectActions', 'doActPage', 'mainPage', 'periodic')")
    if (!page.matches(/[A-Za-z0-9_]+/)) throw new IllegalArgumentException("walkStep.page must be alphanumeric/underscore")
    def operation = spec?.operation?.toString()?.trim() ?: "introspect"
    def validateEnum = spec?.validateEnum == true

    // hrefContext lets the LLM keep state alive across multiple walkStep
    // calls on a sub-page that needs `state.<paramKey>` set every request.
    // Verified live: periodic schedule's `state.n` only exists
    // for the duration of a request that carries the action marker, so
    // every fetch/write to the periodic page must re-send it.
    //
    // Spec shape: { fromPage, hrefName, hrefIndex?, hrefParams: {n: 1} }
    // When provided, the BEFORE schema fetch goes through _rmNavigateToPage
    // (which sets state) and writes/clicks include the same marker.
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
    if (hrefContext) {
        def hcName = hrefContext.hrefName?.toString() ?: "name"
        def hcParams = hrefContext.hrefParams instanceof Map ? hrefContext.hrefParams as Map : null
        def hcIndex = hrefContext.hrefIndex != null ? (hrefContext.hrefIndex as Integer) :
            (hcParams?.n != null ? (hcParams.n as Integer) : 0)
        def fromPage = hrefContext.fromPage?.toString() ?: page
        def navResp = _rmNavigateToPage(appId, fromPage, page, hcIndex, hcName, hcParams)
        beforeCfg = navResp ? [configPage: navResp.configPage] : _rmFetchConfigJson(appId, page)
    } else {
        beforeCfg = _rmFetchConfigJson(appId, page)
    }
    def beforeStatus = _rmFetchStatusJson(appId)
    def beforeSettings = (beforeStatus?.appSettings ?: []).collectEntries { [(it?.name?.toString()): it?.value] }
    def beforeSchema = _rmCollectWalkSchema(beforeCfg?.configPage, beforeSettings)
    def beforeActionCount = _rmCollectActionIndices(appId).size()
    def beforeTriggerCount = _rmCollectTriggerIndices(appId).size()

    def opResult = [:]
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
        writtenValue = writeMap[writtenKey]
        // Validate against schema if asked.
        def schemaInput = beforeSchema.inputs.find { it.name == writtenKey }
        if (!schemaInput) {
            opResult.warning = "Field '${writtenKey}' not in current schema for page '${page}'. Available: ${beforeSchema.inputs.collect { it.name }}. The write will be attempted but the hub may silently drop it."
        }
        if (validateEnum && schemaInput?.options) {
            def validValues = schemaInput.options.collect { it.value?.toString() }
            def writtenStr = writtenValue?.toString()
            // For multi-enum, value may be a list — check each.
            def values = (writtenValue instanceof List) ? writtenValue.collect { it?.toString() } : [writtenStr]
            def invalid = values.findAll { v -> v != null && !validValues.contains(v) }
            if (invalid) {
                def valueWord = (invalid.size() == 1) ? "value" : "values"
                throw new IllegalArgumentException("walkStep.write enum validation failed: ${valueWord} ${invalid} not in options ${validValues} for field '${writtenKey}'. Pass validateEnum=false to bypass, or pick a valid option.")
            }
        }
        // Build the schema map _rmUpdateAppSettings expects.
        def fullSchemaMap = _rmCollectInputSchema(beforeCfg?.configPage)
        if (hrefContextMarkers) {
            // For sub-pages, include the action marker in the same request
            // so RM re-sets state.<paramKey> before applying the write.
            // Without this, the page renders with state.n null and the
            // setting write lands in the wrong scope.
            def body = _rmBuildSettingsBody(appId, [(writtenKey): writtenValue], fullSchemaMap)
            body.formAction = "update"
            body.currentPage = page
            body.pageBreadcrumbs = '["mainPage"]'
            hrefContextMarkers.each { k, v -> body[k] = v }
            try {
                def cfg = _rmFetchConfigJson(appId, hrefContext.fromPage?.toString() ?: page)
                if (cfg?.app?.version != null) body.version = cfg.app.version.toString()
            } catch (Exception verExc) {
                mcpLog("warn", "rm-native", "walkStep: href-context version fetch for app ${appId} on page '${hrefContext.fromPage ?: page}' failed (${verExc.message}) -- POSTing write without version field; hub may reject on concurrent-edit conflict")
            }
            _rmPostSettings(appId, body)
        } else {
            _rmUpdateAppSettings(appId, [(writtenKey): writtenValue], fullSchemaMap)
        }
        opResult.wrote = [(writtenKey): writtenValue]
    } else if (operation == "click") {
        if (!(spec?.click instanceof Map) || !spec.click.name) {
            throw new IllegalArgumentException("walkStep operation='click' requires click={name, stateAttribute?}")
        }
        def btnName = spec.click.name?.toString()
        def stateAttr = spec.click.stateAttribute?.toString()
        _rmClickAppButton(appId, btnName, stateAttr, page)
        opResult.clicked = [name: btnName, stateAttribute: stateAttr]
    } else if (operation == "done") {
        // Submit current page back to its parent via _action_previous=Done,
        // carrying ALL current setting values + sidecar fields. This is the
        // back-nav analog of "navigate" — required for sub-pages whose
        // parent expects the trigger/action to commit fully.
        //
        // Live-captured 2026-04-25: Periodic Schedule's trigger description
        // only renders when the periodic page submits via Done with a
        // valid schedule (e.g. everyNHoursC1=true + everyNHC1=1). The
        // forward-nav `_action_href_*` markers reach the parent page but
        // don't bake the trigger row — RM keeps the row's description as
        // "?" until the form-style Done with full settings arrives.
        //
        // For sub-pages with hrefContext, paramsForPage carries the route
        // param (e.g. {"n":1}) so RM resets state.<paramKey> on the way
        // back. pageBreadcrumbs = ["mainPage", parentPage] tells the hub
        // which page to render in response.
        def parentPage = hrefContext?.fromPage?.toString()
        def hcParams = hrefContext?.hrefParams instanceof Map ? hrefContext.hrefParams as Map : null
        def hcHrefName = hrefContext?.hrefName?.toString() ?: "name"
        _rmSubmitSubPageDone(appId, page, parentPage, hcHrefName, hcParams)
        opResult.done = [from: page, parent: parentPage]
        // After done, schema lives at the parent page.
        page = parentPage ?: page
    } else if (operation == "navigate") {
        def target = spec?.navigate?.targetPage?.toString()
        if (!target) throw new IllegalArgumentException("walkStep operation='navigate' requires navigate={targetPage}")
        // Pull the matching href from the BEFORE schema; we need its
        // name AND params to construct the right action marker pair:
        //   _action_href_<linkName>|<page>|<idx>=clicked
        //   params_for_action_href_<linkName>|<page>|<idx>=<json-params>
        // For plain navigation (no href in schema), fall back to
        // hrefName="name", hrefIndex=0, no params.
        def hrefMatch = beforeSchema.hrefs.find { it.targetPage == target }
        def hrefName = hrefMatch?.name?.toString() ?: "name"
        def hrefIndex = 0
        def hrefParams = null
        if (hrefMatch?.params != null) {
            hrefParams = hrefMatch.params as Map
            // The form-index suffix (the |N at the end of the marker)
            // looks like a render-time form counter, not the params
            // value — the live UI captured |4 for params={n:1}.
            // Don't conflate them. Use the params.n value as fallback
            // index ONLY when no explicit hrefIndex is given.
            if (hrefParams.n != null) hrefIndex = hrefParams.n as Integer
        }
        if (spec.navigate.hrefIndex != null) {
            hrefIndex = spec.navigate.hrefIndex as Integer
        }
        // _rmNavigateToPage returns the nav response — the target page's
        // schema rendered WITH the href params in scope. Stash it as
        // `navResponseConfigPage` so the AFTER block can use it instead
        // of doing a separate GET that would lose the param state.
        opResult.navResponseConfigPage = _rmNavigateToPage(appId, page, target, hrefIndex, hrefName, hrefParams)?.configPage
        opResult.navigated = [from: page, to: target, hrefName: hrefName, hrefIndex: hrefIndex, hrefParams: hrefParams]
        // After navigation the schema lives at the target page, not the source.
        page = target
    } else {
        throw new IllegalArgumentException("walkStep.operation must be 'introspect', 'write', 'click', 'navigate', or 'done'; got '${operation}'")
    }

    // Capture AFTER state. For navigate ops, prefer the schema returned
    // by the nav response itself — sub-pages with href params (e.g.
    // periodic schedule) lose state.<n> on a follow-up GET, so the only
    // place to read the target schema is the nav POST's response body.
    // For non-navigate ops on sub-pages with hrefContext, re-fetch via
    // _rmNavigateToPage so state.<paramKey> is set during render.
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
        def navResp = _rmNavigateToPage(appId, fromPage, page, hcIndex, hcName, hcParams)
        afterCfg = navResp ? [configPage: navResp.configPage] : _rmFetchConfigJson(appId, page)
    } else {
        afterCfg = _rmFetchConfigJson(appId, page)
    }
    def afterStatus = _rmFetchStatusJson(appId)
    def afterSettings = (afterStatus?.appSettings ?: []).collectEntries { [(it?.name?.toString()): it?.value] }
    def afterSchema = _rmCollectWalkSchema(afterCfg?.configPage, afterSettings)
    def afterActionCount = _rmCollectActionIndices(appId).size()
    def afterTriggerCount = _rmCollectTriggerIndices(appId).size()

    // Schema diff.
    def beforeNames = beforeSchema.inputs.collect { it.name } as Set
    def afterNames = afterSchema.inputs.collect { it.name } as Set
    def appeared = (afterNames - beforeNames).toList().sort()
    def disappeared = (beforeNames - afterNames).toList().sort()

    // Value-echo: did the write survive the round-trip with the same value?
    // Catches enum case normalization, multiple-flag coercion, anything
    // hub does silently between accepting and storing the write.
    //
    // For capability.* fields the storage shape is unusual: appSettings'
    // .value comes back as null while the device list lives elsewhere
    // (deviceIdsForDeviceList on the appSettings entry). Pull from that
    // sibling field when comparing — otherwise valueEcho is noise for
    // every device picker write.
    def valueEcho = null
    if (writtenKey != null) {
        def storedValue = afterSettings[writtenKey]
        def schemaInputForKey = beforeSchema.inputs.find { it.name == writtenKey }
        def isCapabilityType = (schemaInputForKey?.type?.toString()?.startsWith("capability.")) == true
        if (isCapabilityType) {
            // Read deviceIdsForDeviceList from the raw appSettings entry —
            // afterSettings only carries .value (null for capability.*).
            def rawEntry = (afterStatus?.appSettings ?: []).find { it?.name?.toString() == writtenKey }
            def storedIds = rawEntry?.deviceIdsForDeviceList
            if (storedIds != null) {
                storedValue = storedIds
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

    def health = _rmCheckRuleHealth(appId)
    def silentRejection = (operation == "write") &&
        appeared.isEmpty() && disappeared.isEmpty() &&
        valueEcho?.match == false

    return [
        success: health.ok && (operation != "write" || (valueEcho?.match != false)),
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
    int idx = 0
    for (def rawStep : steps) {
        idx++
        def step = new LinkedHashMap((Map) rawStep)
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
        if (r?.success == false) {
            allOk = false
            if (stopOnError) break
        }
    }
    def finalHealth = _rmCheckRuleHealth(appId)
    def result = [
        success: allOk && finalHealth.ok,
        operation: "drive",
        page: currentPage,
        stepsRequested: steps.size(),
        stepsRun: stepResults.size(),
        lastStepOperation: lastStepOperation,
        steps: stepResults,
        health: finalHealth
    ]
    // Fail-loud rollup: a success:false drive must ALWAYS carry a top-level reason. A step
    // error caught per-step otherwise lives only in steps[].error -- a weak signal for an
    // LLM caller that sees success:false with no top-level `error`. Surface the first failed
    // step's error (and a repairHint naming it); if every step passed but the rule ended
    // unhealthy, surface the finalHealth.ok gate's reason instead.
    def firstFailed = stepResults.find { it.success == false }
    if (firstFailed != null) {
        result.error = "drive halted at step ${firstFailed.step} (${firstFailed.operation}): ${firstFailed.error ?: 'step reported success:false -- inspect its valueEcho/silentRejection/health'}".toString()
        result.repairHints = (result.repairHints ?: []) + ["Drive stopped at step ${firstFailed.step}. Inspect steps[${firstFailed.step - 1}] for the failure detail, correct it, and re-run the drive from that step.".toString()]
    } else if (!finalHealth.ok) {
        result.error = "drive completed all ${stepResults.size()} step(s) but the rule is unhealthy: ${(finalHealth.issues ?: ['see health']).join('; ')}".toString()
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
            fullMap[name] = currentSettings[name]
        } else if (meta?.type == 'button') {
            // Buttons carry no persisted value; the UI serializes them empty.
            fullMap[name] = ""
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
            fullMap[name] = ""
        }
    }
    extraSettings?.each { k, v -> fullMap[k] = v }

    def body = _rmBuildSettingsBody(appId, fullMap, schema)

    // Form-action envelope -- the part that makes RM run the submitOnChange
    // handler during the page re-render instead of only persisting the value.
    body.formAction = "update"
    body.currentPage = pageName
    body.pageBreadcrumbs = '["mainPage"]'
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
        throw new IllegalStateException("Full-form submit on ${pageName} for app ${appId} failed: status=${resp.status}${bodyPreview ? "; body=" + bodyPreview : ""}. The submit was rejected so nothing was committed (a 4xx is usually a stale version token -- re-fetch via hub_get_app_config(appId=${appId}) and retry). The page may be left in trash-confirmation mode; on this hard-fail path the tool backs it out automatically via cancelTrash. Do NOT treat this as a partial delete.")
    }
    // Surface any non-button inputs the wholesale-replace blanked (absent from
    // currentSettings AND not in extraSettings) so a caller can refuse a
    // silently-blanked write. Empty/absent on the trashActs delete path, where
    // nothing load-bearing is blanked.
    if (blankedInputs && resp instanceof Map) resp.blankedInputs = blankedInputs
    return resp
}

// Snapshot the current state of an RM rule into the hub's File Manager
// as a single JSON file (configure/json + statusJson combined), recorded
// in the unified atomicState.itemBackupManifest alongside app/driver backups.
//
// Entries get type="rm-rule" so hub_list_backups + hub_restore_backup
// (the existing tools) handle them too — no separate RM-only backup
// tools. Backup key pattern: rm-rule_<ruleId>_<yyyyMMdd-HHmmss>.
private Map _rmBackupRuleSnapshot(Integer ruleId, String reason) {
    def config
    def status
    try {
        config = _rmFetchConfigJson(ruleId)
    } catch (Exception e) {
        // Vue-JSON children (Visual Rules) may not serve configure/json. If the id speaks a
        // VRB serialization, the VRB-flavored snapshot below is still a full backup --
        // restore replays the captured definition, not classic settings.
        def vrbFallback = null
        try { vrbFallback = _vrbDetect(ruleId) } catch (Exception ignored) { }
        if (vrbFallback == null) {
            throw new IllegalArgumentException("Cannot back up rule ${ruleId}: configure/json failed -- ${e.message}")
        }
        config = null
    }
    try {
        status = _rmFetchStatusJson(ruleId)
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
        _appTypeRegistry().each { typeKey, reg ->
            if (reg.appName == configAppName) detectedAppType = typeKey
        }
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
                snapshot.vrbRuleJson = vrb.data.ruleJson?.toString()
            }
        }
    }

    def ts = new Date(now()).format("yyyyMMdd-HHmmss")
    def fileName = "mcp-rm-backup-${ruleId}-${ts}.json"

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

    // atomicState read-modify-write: read the full manifest, mutate locally, write back.
    def mfst = atomicState.itemBackupManifest ?: [:]
    def backupKey = "rm-rule_${ruleId}_${ts}"
    def entry = [
        type: "rm-rule",
        id: ruleId,
        ruleId: ruleId,
        fileName: fileName,
        reason: snapshot.reason,
        appLabel: snapshot.appLabel,
        timestamp: snapshot.timestamp,
        sourceLength: jsonBytes.length  // reusing the existing field name for byte size
    ]
    mfst[backupKey] = entry

    // Reuse backupItemSource's prune budget (20 entries total across all
    // backup types). Oldest pruned first -- same policy as app/driver.
    if (mfst.size() > 20) {
        def oldest = mfst.min { it.value.timestamp }
        if (oldest) {
            try { deleteHubFile(oldest.value.fileName) } catch (Exception e) {
                mcpLog("warn", "rm-native", "Could not prune backup ${oldest.value.fileName}: ${e.message}")
            }
            mfst.remove(oldest.key)
        }
    }
    atomicState.itemBackupManifest = mfst

    mcpLog("info", "rm-native", "Backed up rule ${ruleId} (${reason}) to ${fileName} (${jsonBytes.length} bytes)")
    // brokenBefore: the rule's pre-write broken state, derived from the config this snapshot
    // already fetched (no extra hub read). A mutation that needs to know whether IT caused a
    // post-write break (vs the rule being broken beforehand) reads this off the returned backup to
    // steer its error wording, then STRIPS it -- it is a transient internal signal, never surfaced
    // to the caller and deliberately kept off the persisted manifest `entry` (not part of the
    // durable backup record). null when no config was readable (VRB-only snapshot).
    return [backupKey: backupKey, brokenBefore: (config == null ? null : _rmConfigHasBrokenMarkers(config))] + entry
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
def toolSetRule(args) {
    // Button Controllers are an RM-family classic app, and their grandchild
    // Button Rules are RM-wire-format -- so creating a Button Rule is exposed
    // here too (same handler as hub_set_native_app's buttonRule; routes through
    // the parent controller's add-button flow). See _createButtonRuleViaController.
    if (args instanceof Map && args.buttonRule != null) {
        return _createButtonRuleViaController(args)
    }
    // discover/guide are RM static-schema meta-calls handled inside
    // _applyNativeAppEdit before any gate or appId check — route them there.
    boolean isMetaCall = (args?.addTrigger instanceof Map && args.addTrigger.discover == true) ||
                         (args?.addAction instanceof Map && args.addAction.discover == true) ||
                         args?.guide == true
    if (args?.appId == null && !isMetaCall) {
        // CREATE a new RM rule (rule_machine), optionally populating it with any
        // bundled addTriggers/addActions/addTrigger/addAction/addRequiredExpression
        // in the same call.
        //
        // Completeness gate: the create arm bundles a FIXED set of authoring
        // shortcuts. Any other edit-path shortcut (replaceActions, removeAction,
        // walkStep, etc.) plus the raw-mode page-field params (settings, button)
        // makes no sense on a brand-new empty rule -- they require an existing
        // wizard session -- and -- if left unread -- would be silently dropped
        // while the call still returned success:true on an empty shell. The
        // create arm must HONOR or LOUDLY REJECT every shortcut it is handed,
        // never silent-success (hub_set_native_app's create arm enforces the
        // same posture for the shortcuts it does not honor).
        def CREATE_HONORED = ['addTrigger', 'addTriggers', 'addAction', 'addActions', 'addRequiredExpression'] as Set
        def EDIT_ONLY = ['replaceRequiredExpression', 'addLocalVariable', 'removeLocalVariable', 'patches', 'replaceActions', 'removeAction', 'clearActions',
                         'moveAction', 'removeTrigger', 'modifyTrigger', 'walkStep', 'settings', 'button']
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
        def trigs = []
        if (args?.addTriggers instanceof List) trigs.addAll(args.addTriggers)
        if (args?.addTrigger instanceof Map) trigs << args.addTrigger
        def acts = []
        if (args?.addActions instanceof List) acts.addAll(args.addActions)
        if (args?.addAction instanceof Map) acts << args.addAction
        if (trigs) createArgs.triggers = trigs
        if (acts) createArgs.actions = acts
        if (args?.addRequiredExpression instanceof Map) createArgs.requiredExpression = args.addRequiredExpression
        return _createNativeAppShell(createArgs)
    }
    return _applyNativeAppEdit(args)
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
                               'modifyTrigger', 'walkStep', 'settings', 'button'].findAll {
            args instanceof Map && args.containsKey(it)
        }
        if (droppedOnCreate) {
            throw new IllegalArgumentException("hub_set_native_app CREATE (no appId) does not honor ${droppedOnCreate.join(', ')} -- ${droppedOnCreate.size() == 1 ? 'it requires' : 'they require'} an existing app. Create the app first (appType + name), then re-call with the returned appId; for Rule Machine authoring sugar on create, use hub_set_rule.")
        }
        return _createNativeAppShell(args)
    }
    return _applyNativeAppEdit(args)
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
                         'removeAction', 'clearActions', 'moveAction', 'removeTrigger', 'modifyTrigger'].findAll {
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

    def backup = _rmBackupRuleSnapshot(controllerId, "pre-buttonRule")

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
                "Restore the controller via hub_restore_backup(backupKey='${backup.backupKey}') if its config looks damaged.").toString()]
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
        // First page of a fresh classic SmartApp is the label page. The
        // input name is conventionally `origLabel` across all the app
        // types in the registry (RM 5.1, Button Controller-5.1, Basic
        // Rules, Room Lighting, etc. — all derive from the same
        // SmartApp framework). Schema is introspected from configure/json
        // so the 3-field capability contract applies uniformly.
        def firstPage = _rmFetchConfigJson(newId)
        def schema = _rmCollectInputSchema(firstPage?.configPage)
        def body = _rmBuildSettingsBody(newId, [origLabel: name], schema)
        hubInternalPostForm("/installedapp/update/json", body)

        // updateRule is RM's "commit + reinitialize" button, the framework
        // default for most installed apps. The registry's commitButton field
        // (default "updateRule" when absent) lets an appType override it; a
        // null commitButton means the app is submitOnChange and has no commit
        // button (e.g. Basic Rule -- clicking updateRule there poisons the
        // render with "For input string: updateRule").
        def createCommitButton = reg.containsKey("commitButton") ? reg.commitButton : "updateRule"
        if (createCommitButton) _rmClickAppButton(newId, createCommitButton)

        // NOTE: the origLabel write above becomes the installed-app label only
        // for RM-family apps (RM copies origLabel -> label on updateRule). Other
        // classic types (Button Controller, etc.) have no origLabel input and
        // are only partial-support here (rule_machine is the only FULLY-supported
        // appType; the others are registered but their label/config handling is
        // incomplete), so they keep the default type-name label -- a known
        // limitation of the non-rule_machine appTypes. A prior attempt to set
        // the label generically via /installedapp/update did not take effect on
        // a live Button Controller, so it was removed rather than shipped dead.

        // Optional bulk-trigger creation. When `triggers` is passed, walk
        // the list and call _rmAddTrigger for each spec. After all are
        // committed, fire updateRule once at the end so subscriptions
        // populate from a fully-loaded rule (avoids N redundant inits).
        def triggerSpecs = args?.triggers instanceof List ? (args.triggers as List) : []
        def triggerResults = []
        if (triggerSpecs) {
            triggerSpecs.eachWithIndex { spec, i ->
                if (!(spec instanceof Map)) {
                    triggerResults << [success: false, error: "triggers[${i}] is not a Map", spec: spec]
                    return
                }
                try {
                    triggerResults << _rmAddTrigger(newId, spec as Map)
                } catch (Exception te) {
                    triggerResults << [success: false, error: te.message, specCapability: spec.capability]
                    mcpLog("warn", "rm-native", "hub_set_rule: trigger ${i} (capability=${spec.capability}) failed -- ${te.message}")
                }
            }
            // Re-init once after all triggers are committed.
            _rmClickAppButton(newId, "updateRule")
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
        if (reSpec != null) {
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
            if (reResult instanceof Map && reResult.success != false) {
                try {
                    _rmClickAppButton(newId, "updateRule")
                } catch (Exception reUpdExc) {
                    reResult.updateRuleFailed = true
                    reResult.expressionNotLive = true
                    reResult.updateRuleError = reUpdExc.message
                    reResult.partial = true
                    mcpLog("warn", "rm-native", "hub_set_rule: requiredExpression trailing updateRule click failed for app ${newId} -- expression may not be live: ${reUpdExc.message}")
                }
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
            actionSpecs.eachWithIndex { spec, i ->
                if (!(spec instanceof Map)) {
                    actionResults << [success: false, error: "actions[${i}] is not a Map", spec: spec]
                    return
                }
                try {
                    actionResults << _rmAddAction(newId, spec as Map, true)
                } catch (Exception ae) {
                    actionResults << [success: false, error: ae.message, specCapability: spec.capability, specAction: spec.action]
                    mcpLog("warn", "rm-native", "hub_set_rule: action ${i} (${spec.capability}/${spec.action}) failed -- ${ae.message}")
                }
            }
            // After bulk-add, navigate selectActions → mainPage via
            // _action_previous=Done — mirrors the live UI's "Done with
            // Actions" click. _rmAddAction leaves us on selectActions; the
            // updateRule button lives on mainPage. Verified live
            // by capturing the working UI flow's XHR sequence: every action
            // commit ends with a Done navigation up to mainPage before
            // updateRule fires. Without this, state.editAct can linger
            // and updateRule may fire from the wrong page state.
            try {
                _rmSubmitSubPageDone(newId, "selectActions", "mainPage", "name", null)
            } catch (Exception subPageDoneExc) {
                mcpLog("warn", "rm-native", "hub_set_rule: trailing _rmSubmitSubPageDone(selectActions->mainPage) failed for app ${newId} (${subPageDoneExc.message}) -- relying on updateRule below; lingering state.editAct markers may corrupt subsequent edits")
            }
            _rmClickAppButton(newId, "updateRule")
        }

        // Final commit: click the Done button on mainPage. The live UI
        // ALWAYS fires this as the last step of every create/modify session
        // (verified). Without it, the rule's session-end state
        // can be incomplete and subsequent reads/edits may behave oddly.
        def createDone = _rmSubmitMainPageDone(newId)

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
        def result = [
            success: health.ok && !partialTriggers && !partialActions && !reFailed && !rePartial,
            partial: (partialTriggers || partialActions || reFailed || rePartial) as Boolean,
            partialTriggers: partialTriggers,
            partialActions: partialActions,
            repairHints: repairHints,
            appId: newId,
            appType: appType,
            name: name,
            labelApplied: (appType == "rule_machine"),
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
            if (_resolveCommitButton(_appTypeRegistry()[appType]?.appName) == null) {
                result.success = false
            }
            result.repairHints = (result.repairHints ?: []) + ["The session-end mainPage Done click did not commit (${createDone.reason}). Settings are already written, but the app's update lifecycle did not run -- verify via hub_get_app_config(appId=${newId}) and re-commit via hub_set_native_app(appId=${newId}, button='updateRule') for RM-family apps.".toString()]
        }
        if (triggerSpecs) result.triggers = triggerResults
        if (actionSpecs) result.actions = actionResults
        if (reSpec != null) {
            result.requiredExpression = reResult
            // Lift the RE outcome fields top-level. The outputSchema declares
            // requiredExpressionAlreadyExists / expressionNotLive / updateRuleFailed /
            // updateRuleError at the top level and the _applyNativeAppEdit edit arm
            // surfaces them there, so don't bury them under requiredExpression on create.
            if (reResult?.requiredExpressionAlreadyExists) result.requiredExpressionAlreadyExists = true
            if (reResult?.expressionNotLive) result.expressionNotLive = true
            if (reResult?.updateRuleFailed) result.updateRuleFailed = true
            if (reResult?.updateRuleError != null) result.updateRuleError = reResult.updateRuleError
        }
        // Honesty caveat: only rule_machine reliably copies origLabel -> the
        // installed-app display label on commit. For other (partial-support)
        // appTypes the requested name may NOT become the visible label, so flag
        // it via labelApplied + a note rather than letting the {success, name}
        // envelope imply the label landed.
        if (appType != "rule_machine") {
            result.note = ((result.note ?: "") +
                " NOTE: appType '${appType}' is partial-support -- the requested name may NOT have been applied as the app's display label (only rule_machine is verified to copy origLabel->label on commit). Verify via hub_get_app_config(appId=${newId}) and rename in the Hubitat UI if needed.").toString().trim()
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
    return [ok: true, vars: (raw instanceof Map) ? raw : [:]]
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
            error: "Variable '${name}' did not commit -- state.allLocalVars does not contain it. Common cause: value type mismatch (e.g. writing a string for a Number-type variable). Verify the value matches the declared type.",
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
        throw new IllegalArgumentException("removeLocalVariable: variable name '${name}' contains characters that are not valid in a Rule Machine local-variable name (no [, ], &, = or non-ASCII characters). Check the name via hub_list_rule_local_variables.")
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
                throw new IllegalStateException("${baseMsg} [wizardStuck -- cancelCapab cleanup also failed: ${cleanupErr}]")
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
            error = "Local variable '${name}' could not be confirmed deleted -- every verify read of state.allLocalVars failed (last error: ${lastFetchErr}). The delete may have succeeded; the verify read did not complete."
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
            ? "updateRule click was rejected after the local variable committed. The variable is created on the hub but the rule's action map will not pick it up until updateRule fires. Retry hub_set_rule(button='updateRule', confirm=true), or restore via backup if the retry also fails."
            : "updateRule click was rejected after the local variable was removed. The variable is gone but the rule's action map will not re-evaluate until updateRule fires. Retry hub_set_rule(button='updateRule', confirm=true), or restore via backup if the retry also fails.")
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
    boolean deleteBrokeRule = (!isAdd) && (varResult?.deleted == true) && (varResult?.success != false) && !health.ok
    String deleteBrokeError = null
    if (deleteBrokeRule) {
        def markerHint = (health.brokenMarkers instanceof List && !health.brokenMarkers.isEmpty()) ? " (${health.brokenMarkers.join(', ')})" : ""
        if (brokenBefore == true) {
            // Pre-existing breakage -- removing this local did not cause it; restore won't fix it.
            deleteBrokeError = "Local variable '${varResult?.name}' was deleted; the rule is broken${markerHint}, but it was ALREADY broken before this delete (pre-existing -- removing this local did not cause it). Restoring backup (backupKey='${backup?.backupKey}') undoes the delete but will NOT fix the pre-existing breakage; inspect the rule's broken action(s)/expression(s)."
            repairHints << "The rule was already broken before this delete (pre-existing). Restoring backupKey='${backup?.backupKey}' only undoes the delete and will NOT repair the pre-existing breakage -- inspect the rule's broken action(s)/expression(s)."
        } else if (brokenBefore == false) {
            // Clean before -- the delete caused the break; restore undoes it.
            deleteBrokeError = "Local variable '${varResult?.name}' was deleted, but that broke the action(s)/expression(s) that referenced it -- the rule is now broken${markerHint}. Restore the pre-delete backup (backupKey='${backup?.backupKey}') via hub_restore_backup to undo, or remove the references first and then retry the removal."
            repairHints << "The deleted local variable was still referenced; the rule is now broken. Restore via hub_restore_backup with backupKey='${backup?.backupKey}', or remove the referencing action(s)/expression(s) and retry."
        } else {
            // Indeterminate baseline (pre-delete config unreadable) -- do not claim causation.
            deleteBrokeError = "The rule is broken after removing '${varResult?.name}'${markerHint} (could not determine whether it was already broken beforehand). Restore the backup (backupKey='${backup?.backupKey}') via hub_restore_backup to undo this delete, or inspect the rule's action(s)/expression(s)."
            repairHints << "The rule is broken after this delete; whether it was already broken beforehand is unknown. Restore via hub_restore_backup with backupKey='${backup?.backupKey}' to undo the delete, or inspect the rule's action(s)/expression(s)."
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
        success: (varResult?.success != false) && health.ok && !updateRuleFailed,
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
    // Open a new action slot on selectActions.
    _rmClickAppButton(appId, "N", "doActN", "selectActions")

    // Discover the index RM allocated (same pattern as _rmAddAction).
    def ghostCfg = _rmFetchConfigJson(appId, "doActPage")
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
    _rmWriteSettingOnPage(appId, "doActPage", "actType.${ghostIdx}", "condActs", ghostApplied, null, ghostSkipped)
    _rmWriteSettingOnPage(appId, "doActPage", "actSubType.${ghostIdx}", "getIfThen", ghostApplied, null, ghostSkipped)

    // Cancel WITHOUT clicking actionDone. This discards the slot before
    // commit so no action is added to the rule, but the re-initialized
    // predCapabs state (from the getIfThen init) persists.
    // DO NOT replace with actionDone -- that bakes an empty IF() block.
    _rmClickAppButton(appId, "actionCancel", null, "doActPage")

    // Return to selectActions to finalize the cancel, then navigate
    // back to mainPage. Without the mainPage nav, the app's server-side
    // routing context stays at selectActions. When the user later opens
    // the browser and clicks "Manage Conditions" (STPage href on mainPage),
    // RM sees a conflicting editAct context and renders "Required Fields
    // missing or not passing validation" on STPage. Without this nav,
    // STPage shows a validation error after addRequiredExpression completes;
    // with it, STPage opens cleanly.
    _rmNavigateToPage(appId, "doActPage", "selectActions")
    _rmNavigateToPage(appId, "selectActions", "mainPage")

    mcpLog("info", "rm-native", "${caller}: ghost ifThen clear fired for app ${appId} (clears atomicState.predCapabs without adding an action)")
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
// (sourceVariable / fromDevice / math). Each mode writes numOp.<N> then walks a sequence of
// schema-gated fields; these collapse the precondition check, the fetch-and-find-or-throw idiom,
// and the constant-vs-variable operand write that were triplicated across the modes.

// Precondition: numOp.<N> must have landed for the mode's gated fields to appear. If the numOp
// write was skipped, attribute the failure precisely (the numOp gap) rather than letting the
// downstream reveal-miss blame a firmware gap. No-op when numOp landed.
private void _rmAssertNumOpLanded(int idx, List applied, List skipped, String modeLabel) {
    def numOpKey = "numOp.${idx}".toString()
    if (!applied.contains(numOpKey) && skipped.any { it?.key?.toString() == numOpKey }) {
        def numOpSkip = skipped.find { it?.key?.toString() == numOpKey }
        throw new IllegalArgumentException("setVariable: numOp.${idx} write did not land (reason: ${numOpSkip?.reason}) -- ${modeLabel} reveal cannot proceed. Verify the doActPage schema includes numOp.${idx} at this action position.")
    }
}

// Fetch the doActPage schema, find the named input, and return its input Map -- or throw a
// fail-loud reveal-miss error naming the role and the visible field names. roleLabel is the
// human phrase for the error (e.g. "device picker", "second-operand field"); the caller supplies
// the full "<role> '<field>' was not revealed ..." suffix via missSuffix so each mode keeps its
// existing wording. Returns the input Map (never null).
private Map _rmRevealedInputOrThrow(Integer appId, String fieldName, String missSuffix) {
    def cfg = _rmFetchConfigJson(appId, "doActPage")
    def inputs = (cfg?.configPage?.sections ?: []).collectMany { sec -> (sec?.input ?: []) }
    def input = inputs.find { it?.name?.toString() == fieldName }
    if (!input) {
        def visibleNames = inputs.collect { it?.name?.toString() }.findAll { it }.join(', ') ?: "(none -- schema returned empty)"
        throw new IllegalArgumentException("setVariable: ${missSuffix} Visible fields: ${visibleNames}")
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

private Map _rmRevealStep(Integer appId, String page, String pattern, Closure trigger) {
    def preCfg = _rmFetchConfigJson(appId, page)
    def preNames = (preCfg?.configPage?.sections ?: []).collectMany { sec ->
        (sec?.input ?: []).collect { it?.name?.toString() }
    }.findAll { it } as Set
    trigger.call()
    def postCfg = _rmFetchConfigJson(appId, page)
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
    // fallbackToExisting signals "matched only via revealedAny" -- the field was already
    // visible BEFORE the trigger closure ran. Callers that care (e.g. a walker tracking
    // whether a trigger write actually advanced the schema vs returning a stale leftover
    // from a prior slot/run) can surface this as an informational sentinel. Does NOT
    // imply failure: static-schema firmware legitimately exposes always-visible fields
    // and this path is the only way the walker reaches them.
    def fallbackToExisting = (revealedNew == null) && (revealedAny != null)
    // postInputs is the full post-trigger input-object list (not just names) so a caller
    // that already drove a reveal can read a sibling field's options from the SAME fetch
    // rather than re-probing the schema (e.g. the Custom Attribute enum no-RHS route).
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
    def writeST               = ctx.writeST as Closure
    def cancelInFlightCond    = ctx.cancelInFlightCondition as Closure
    def condIdx               = ctx.condIdx as Integer
    def cap                   = ctx.cap?.toString()
    def capCanonical          = ctx.capCanonical?.toString()
    def hrefParams            = ctx.hrefParams as Map
    def skippedAccum          = ctx.skipped as List
    // appliedAccum is the caller's settingsApplied list. Used ONLY by the Custom
    // Attribute exposure-probe-failure force-write fallback (which POSTs the comparator
    // directly and must record the key as applied). Every other walker field write goes
    // through writeST, which owns its own applied/skipped routing; this accumulator is
    // not consumed on the normal paths. CONTRACT: callers MUST pass a non-null List in
    // ctx.applied -- the force-write fallback records the comparator key into it AND emits
    // a comparator_force_written_unverified skip whose hint promises "value IS in
    // settingsApplied". If ctx.applied were absent the force-write would land in a
    // throwaway list and the skip's promise would be a lie, so fail loud at the boundary.
    def appliedAccum          = ctx.applied as List
    if (appliedAccum == null) {
        throw new IllegalArgumentException("_rmWalkConditionReveal requires ctx.applied (the caller's settingsApplied List) -- the Custom Attribute force-write fallback records into it and its skip hint promises the value is in settingsApplied. Pass applied: <list>.")
    }
    // The skipped accumulator carries every degradation signal this walker surfaces
    // (reveal-fallback sentinel, comparator_force_written_unverified, and the
    // comparator_not_representable_for_enum_attribute enum skip). A null accumulator on
    // a path whose whole purpose is surfacing degradation would silently drop the skip --
    // the pre-fix silent bug. Both call sites pass a non-null List; fail loud if one does
    // not rather than no-op the skip.
    if (skippedAccum == null) {
        throw new IllegalArgumentException("_rmWalkConditionReveal requires ctx.skipped (the caller's settingsSkipped List) -- the walker records degradation skips into it. Pass skipped: <list>.")
    }
    def page                  = ctx.page?.toString() ?: "STPage"
    // _rmRevealStep returns fallbackToExisting=true when only revealedAny matched (a
    // matching field was already visible BEFORE the trigger closure ran). On static-schema
    // firmware this is normal; on progressive-disclosure firmware it can mask a silent
    // trigger rejection if a same-named field is left over from a prior slot or run.
    // The walker pushes an informational sentinel so callers can detect the fallback path
    // -- does NOT set partial=true by itself (legitimate static-schema operation).
    // Wrapper deliberately mirrors the _rmRevealStep signature so future maintenance can
    // search-and-replace either direction without arg-list edits.
    //
    // Sentinel scoping: only fire on revealStep() calls whose trigger closure WRITES a
    // setting. Empty-trigger calls (used for "discover already-revealed field" -- they
    // exist purely to fetch the latest schema after a previous write committed the field)
    // would otherwise emit false positives because their preNames == postNames by design.
    // Empty-trigger callers use the discoverField() helper below which routes through
    // _rmRevealStep directly without the sentinel push.
    def revealStep = { Integer aId, String pg, String pattern, Closure trigger ->
        def step = _rmRevealStep(aId, pg, pattern, trigger)
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
        return _rmRevealStep(aId, pg, pattern, {})
    }

    // ---- Pre-walker guard: discrete-event sensor capabilities ----
    // Water sensor / Smoke detector / CO / CO2 / Tamper alert / Acceleration report
    // discrete enum events (wet/dry, detected/clear, active/inactive) -- they do NOT
    // accept the comparator+value path that numeric capabilities do.
    //
    // RM's runtime DOES validate two of the three shapes:
    //   (a) comparator='=', value=1  -- value coerces to state and the state-domain
    //       validator rejects loudly with "state '1' not in capability 'Water sensor'
    //       domain. Valid: dry, wet".
    //   (b) comparator='>', value=5  -- same path, same loud reject.
    //
    // The third shape (comparator with NO state and NO value) slips past RM's
    // validation and silently degrades: success=true, partial=true, settingsSkipped
    // entry [{key:'RelrDev_<N>', reason:'silent_rejection'}], with health.ok=true and
    // no broken markers. The condition is CREATED (capability + device written) but
    // has no comparator and no state value -- functionally meaningless, will evaluate
    // to false on every check. Live-probed on test hub (rule 169, RM 5.1.8).
    //
    // Code-side reject closes the gap RM's runtime does not catch. Caller gets a
    // targeted error pointing at the right state-value enumeration instead of a
    // generic silent_rejection sentinel they might dismiss as minor partial.
    // Capability-name pitfall: `Carbon monoxide detector` (CarbonMonoxideDetector
    // capability -- discrete enum events) is IN the map; `Carbon dioxide sensor`
    // (CarbonDioxideMeasurement capability -- numeric ppm value) is INTENTIONALLY
    // OMITTED. The two capabilities look superficially symmetric but RM's STPage
    // wizard treats CO2 as numeric (comparator + value path) and live-rejects the
    // `state: 'detected'/'clear'` shape -- including CO2 here would over-zealously
    // reject valid numeric usage AND direct callers to a path the wizard refuses.
    // Tamper alert is included per the Hubitat TamperAlert capability docs but
    // is not present in STPage's option list on every firmware (untestable on
    // some hubs); the guard is defensible per docs and harmless when the
    // capability never appears in capCanonical.
    def DISCRETE_EVENT_CAPS = [
        "Water sensor":                ["wet", "dry"],
        "Smoke detector":              ["detected", "clear"],
        "Carbon monoxide detector":    ["detected", "clear"],
        "Tamper alert":                ["detected", "clear"],
        "Acceleration":                ["active", "inactive"]
    ]
    def discreteValid = DISCRETE_EVENT_CAPS[capCanonical]
    if (discreteValid != null && cond.comparator != null && cond.state == null && cond.value == null) {
        cancelInFlightCond()
        def validValues = discreteValid.collect { "'${it}'" }.join(" or ")
        throw new IllegalArgumentException("conditions[${condIdx}]: ${capCanonical} is a discrete-event capability -- pass state: ${validValues} instead of a comparator+value pair. The comparator-without-value shape silently degrades on RM 5.1 (no broken marker, but the condition is functionally meaningless). See rCapab_<N> capability list for the full state-value table.")
    }

    // ---- Pre-walker guard: compareToDevice capability scope ----
    // compareToDevice (device-relative RHS) is wired only on the numeric-device path
    // below (Temperature, Humidity, Illuminance, ...). The capabilities that return
    // before that path -- Mode, Between two times, Variable, Custom Attribute -- never
    // reach the isDev_<N>/relDevice_<N> reveal, so a compareToDevice passed alongside
    // one of them would be silently dropped (the capability block commits a literal
    // condition and returns). Reject up front so the contract the docs promise holds
    // unconditionally. Mirrors the DISCRETE_EVENT_CAPS guard's in-pattern shape.
    def COMPARETODEVICE_UNSUPPORTED_CAPS = ["Mode", "Between two times", "Variable", "Custom Attribute"] as Set
    if (cond.compareToDevice != null && (capCanonical in COMPARETODEVICE_UNSUPPORTED_CAPS)) {
        cancelInFlightCond()
        throw new IllegalArgumentException("conditions[${condIdx}]: compareToDevice (device-relative RHS) is only supported with numeric device capabilities (e.g. Temperature, Humidity, Illuminance); capability '${capCanonical}' does not support it. Capabilities with dedicated handling instead: Mode, Between two times, Variable, Custom Attribute.")
    }

    // ---- Mode capability ----
    // RM reveals a modes<cIdx> picker (e.g. modes6) after rCapab is committed --
    // the exact name is firmware-assigned and must be discovered, not hardcoded.
    // Note: triggers use modesX<N>; STPage conditions use modes<N> (no X prefix).
    // Spec: {capability:'Mode', state:'Night'} (single mode by name) or
    // {capability:'Mode', modeIds:['3']} (by ID).
    if (capCanonical == "Mode") {
        def modeIdsToWrite
        if (cond.modeIds != null) {
            modeIdsToWrite = (cond.modeIds instanceof List) ? (cond.modeIds as List).collect { it?.toString() } : [cond.modeIds.toString()]
        } else if (cond.state != null) {
            // Resolve name(s) to IDs via _rmResolveModeIds.
            def stateVal = cond.state
            def names = (stateVal instanceof List) ? (stateVal as List) : [stateVal]
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
        writeST(hrefParams, modesField, modeIdsToWrite)
        if (cond.not == true) {
            writeST(hrefParams, "not${cIdx}".toString(), true)
        }
        if (cond.rawSettings instanceof Map) {
            (cond.rawSettings as Map).each { rk, rv -> writeST(hrefParams, rk.toString(), rv) }
        }
        _rmClickAppButton(appId, "hasAll", null, page)
        return
    }

    // ---- Between two times capability ----
    // Spec: {capability:'Between two times',
    //        start:{type:'clock'|'sunrise'|'sunset', time:'HH:mm', offset?:N},
    //        end:{type:'clock'|'sunrise'|'sunset', time:'HH:mm', offset?:N}}
    // Firmware field names (verified live on RM 5.1.8):
    //   starting<cIdx>  -- start-type enum ('A specific time'|'Sunrise'|'Sunset')
    //   startingA<cIdx> -- start clock-time (ISO datetime with hub-local TZ offset: 2000-01-01THH:mm:00.000±HHMM)
    //   ending<cIdx>    -- end-type enum ('A specific time'|'Sunrise'|'Sunset')
    //   endingA<cIdx>   -- end clock-time (ISO datetime)
    //   endSunriseOffset<cIdx> -- end sunrise/sunset offset (minutes, number)
    // Chain: rCapab -> starting<N> selector -> startingA<N> -> ending<N> -> endingA<N>/endSunriseOffset<N>
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
        def startTypeWire = typeToWire[startType]
        def endTypeWire   = typeToWire[endType]

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
        // Convert HH:mm clock times to the ISO datetime format RM's wizard expects.
        // The date component is a fixed dummy (2000-01-01); RM only uses the time portion.
        // The TZ offset must be computed for the ANCHOR DATE (2000-01-01), not for today,
        // because DST may differ between now and January. Example: a Denver hub in May is
        // MDT (-0600), but 2000-01-01 in Denver is MST (-0700). Using getOffset(now()) would
        // embed -0600; the hub interprets the datetime with the January offset, shifting the
        // display by 1 hour. anchorMs = 2000-01-01T00:00:00.000Z epoch.
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

        // Reveal 2: write start type as the trigger -> start value field appears.
        // Clock: startingA<cIdx>; Sunrise/Sunset: startSunriseOffset<cIdx>/startSunsetOffset<cIdx>
        // (RM names the sun-event offset field per event, NOT a shared name). The offset is
        // optional, but the field still appears on type selection so writeST can fill it when an
        // offset is given. Matching only startingA here silently broke every Sunrise/Sunset start.
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
        _rmClickAppButton(appId, "hasAll", null, page)
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
        if (!cond.comparator) {
            cancelInFlightCond()
            throw new IllegalArgumentException("conditions[${condIdx}]: Variable condition requires 'comparator' (e.g. '=', '!=', '<', '>'). Got: ${cond}")
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
            // Schema's option list came back empty -- could be a hub with no variables defined,
            // a firmware version that lazily-populates the enum, or a probe-timing race. The
            // walker cannot disambiguate, so it MUST signal degradation rather than silently
            // accept a name that may not resolve. Mirrors the addAction setVariable
            // api_unavailable sentinel pattern emitted by `_rmAddAction`'s setVariable
            // branch when the variable picker returns an empty option list. The write
            // still proceeds because the
            // caller-supplied varName is the only signal we have; partial=true tells them the
            // schema-side validation was skipped so they can verify post-write.
            mcpLog("warn", "rm-native", "conditions[${condIdx}]: Variable: picker '${varPickerField}' returned an empty option list -- variable-name validation skipped; write will proceed unvalidated")
            if (skippedAccum != null) {
                skippedAccum << [key: "variable-validation", reason: "api_unavailable", condIdx: condIdx, varName: varName, pickerField: varPickerField]
            }
        } else if (!varOpts.any { it == varName }) {
            cancelInFlightCond()
            throw new IllegalArgumentException("conditions[${condIdx}]: Variable: hub variable '${varName}' not in the revealed picker for '${varPickerField}'. Available: ${varOpts.sort().join(', ')}")
        }

        // Reveal 2: write variable name as the trigger -> RelrDev_<N> comparator appears
        def normalizedComparator = _rmNormalizeComparator(cond.comparator.toString())
        def relrReveal = revealStep(appId, page, /RelrDev_\d+/, {
            writeST(hrefParams, varPickerField, varName)
        })
        if (!relrReveal.input) {
            cancelInFlightCond()
            def visible = relrReveal.visibleNames?.join(', ') ?: "(none)"
            throw new IllegalStateException("conditions[${condIdx}]: Variable: RelrDev_<N> (comparator) not revealed after variable name write. Visible fields: ${visible}")
        }
        // Use the firmware-assigned field name discovered from the live schema.
        def relrField = relrReveal.input.name.toString()

        // Variable-vs-variable RHS path. When the caller supplies compareToVariable,
        // the RHS is another hub variable rather than a numeric constant. RM exposes a
        // boolean toggle (isVar_<N>) that, once true, reveals a second variable picker
        // for the right-hand side. The static _rmBuildCondition (selectTriggers) writes
        // isVar_<N>=true then xVarR_<N>=<name>; the walker pages (STPage/doActPage) use
        // the same isVar reveal but the RHS-picker field name is firmware-assigned and
        // may differ from xVarR_<N>, so discover it from the live schema after the
        // reveal rather than hardcoding the slot.
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
                throw new IllegalStateException("conditions[${condIdx}]: Variable: right-hand variable picker not revealed after isVar_<N>=true (compareToVariable='${rhsVarName}'). Without it the condition would render '${varName} ${cond.comparator} 0' (numeric default) instead of variable-vs-variable. Visible fields: ${visible}")
            }
            def rhsVarField = rhsVarReveal.input.name.toString()
            // Validate the RHS variable name against the revealed picker's options via the
            // canonical reader (same Map-keyed-by-name contract as the LHS variable picker).
            def rhsOpts = _rmReadPickerOptionStrings(rhsVarReveal.input)
            if (!rhsOpts) {
                // Empty option list -- same ambiguity as the LHS variable picker: a hub with
                // no variables, a lazily-populated enum, or a probe-timing race. The walker
                // cannot validate, so it signals degradation (api_unavailable sentinel ->
                // settingsSkipped -> partial=true) rather than writing unvalidated and
                // silently. The write still proceeds because the caller-supplied name is the
                // only signal we have. Mirrors the LHS variable-validation fallback above.
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
            _rmClickAppButton(appId, "hasAll", null, page)
            return
        }

        // Reveal 3: write RelrDev as the trigger -> state_<N> value appears
        def condStateOrValue = cond.state != null ? cond.state : cond.value
        def stateKey = "state_${cIdx}".toString()
        def stateReveal = revealStep(appId, page, /state_\d+/, {
            writeST(hrefParams, relrField, normalizedComparator)
        })
        if (!stateReveal.input) {
            cancelInFlightCond()
            def visible = stateReveal.visibleNames?.join(', ') ?: "(none)"
            throw new IllegalStateException("conditions[${condIdx}]: Variable: state_<N> (value field) not revealed after RelrDev write. Visible fields: ${visible}")
        }
        // Fail loud when the comparator requires an RHS value but the caller did not
        // supply one. Without this guard the state_<N> write is skipped (null check
        // below) and RM renders the comparator against the field's default (0), producing
        // a condition like "myVar = 0" when the caller intended "myVar = <something>".
        // Mirrors the _rmBuildCondition (selectTriggers) fail-loud which throws when
        // xVarR_<N> did not land for the same conceptual reason. State-change
        // comparators ('*changed*', '*became*' family) legitimately omit RHS, so accept
        // those without a value.
        def comparatorIsRhsOptional = _rmComparatorIsRhsOptional(normalizedComparator)
        if (condStateOrValue == null && !comparatorIsRhsOptional) {
            cancelInFlightCond()
            throw new IllegalArgumentException("conditions[${condIdx}]: Variable: comparator '${cond.comparator}' requires an RHS value, but neither 'state' nor 'value' was provided. Without an RHS, RM renders the comparator against the field default (0). Either supply 'value: <constant>' / 'state: <variableName>' or use a state-change comparator ('*changed*' / '*became*').")
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
        _rmClickAppButton(appId, "hasAll", null, page)
        return
    }

    // ---- Custom Attribute capability ----
    // Write order (free-valued attribute): rCapab -> rDev_<N> -> rCustomAttr_<N>
    //              (as reveal trigger) -> RelrDev_<N> (as reveal trigger) -> state_<N>
    // Each _rmRevealStep trigger writes the field that causes the next field to appear.
    // RelrDev_<N> is only visible after rCustomAttr_<N> commits; writing both
    // back-to-back with no re-fetch silently rejects RelrDev.
    //
    // Enum-recognized attribute (switch/motion/contact/lock/...): the rCustomAttr_<N>
    // re-render reveals the value picker state_<N> DIRECTLY and never exposes
    // RelrDev_<N>. That path skips the comparator and writes the value straight to
    // state_<N> -- it is correct enum behaviour, not degradation, so it neither
    // throws nor flags partial. Only a render exposing NEITHER field is a real failure.
    if (capCanonical == "Custom Attribute") {
        if (cond.attribute != null && cond.comparator == null) {
            cancelInFlightCond()
            throw new IllegalArgumentException("conditions[${condIdx}]: Custom Attribute condition requires both 'attribute' (e.g. 'water') AND 'comparator' (e.g. '=' / '!='). Got attribute='${cond.attribute}' but comparator was not provided. RM 5.1's wizard renders the condition without these values silently if either is missing.")
        }
        if (cond.comparator != null && cond.attribute == null) {
            cancelInFlightCond()
            throw new IllegalArgumentException("conditions[${condIdx}]: Custom Attribute condition requires both 'attribute' (e.g. 'water') AND 'comparator' (e.g. '=' / '!='). Got comparator='${cond.comparator}' but attribute was not provided. RM 5.1's wizard renders the condition without these values silently if either is missing.")
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

            // _rmRevealStep: write rCustomAttr_<N> as the trigger, then branch on what
            // the re-render exposes. RM 5.1 rebuilds the field set from the attribute's
            // resolved type: a free-valued attribute reveals the comparator RelrDev_<N>,
            // while an attribute the hub recognizes as an ENUM (switch, motion, contact,
            // lock, ...) reveals the enum value picker state_<N> DIRECTLY and never exposes
            // RelrDev_<N>. The enum case is correct -- there is no comparator to write, the
            // value lands straight in state_<N> -- so it must NOT throw and must NOT flip
            // partial. Use the discovered field name (not a hardcoded slot) and normalize
            // the comparator token the same way as Variable.
            //
            // The exposure-probe re-fetch inside _rmRevealStep is the only NEW failure
            // surface here. A transient empty/unparseable response throws out of revealStep
            // and would otherwise abort the whole walker (no comparator, no value, the add
            // fails). Mirror the trigger-row / condition-wizard fallback: catch the throw,
            // force-write the comparator best-effort + flag partial, then best-effort write
            // the value. The force-write breadcrumb tracks the page's normal write
            // convention -- STPage's writeST posts pageBreadcrumbs=[] (re-firing the mainPage
            // href would reset the in-flight cond-builder), doActPage posts ["mainPage"].
            def relrReveal = null
            try {
                relrReveal = revealStep(appId, page, /RelrDev_\d+/, {
                    if (attrVal != null) {
                        writeST(hrefParams, customAttrKey, attrVal)
                    }
                })
            } catch (Exception revealEx) {
                mcpLog("warn", "rm-native", "conditions[${condIdx}]: Custom Attribute: exposure-probe re-fetch after rCustomAttr_${cIdx} failed for app ${appId} on page ${page} (${revealEx.message ?: revealEx}); force-writing comparator RelrDev_${cIdx} as fallback (partial)")
                // _rmForceWriteEnumField records the comparator into appliedAccum and a
                // comparator_force_written_unverified skip into skippedAccum (both the
                // walker's caller-owned lists). It POSTs directly -- no further re-fetch --
                // so it cannot re-trip the failure that landed us here. The STPage walk uses
                // pageBreadcrumbs=[] (writeST -> _rmWriteSubPageField convention); doActPage
                // uses ["mainPage"] (writeAct -> _rmWriteSettingOnPage convention).
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
                _rmClickAppButton(appId, "hasAll", null, page)
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
                // Enum-recognized attribute -> the re-render revealed the value picker
                // state_<N> directly and hid the comparator. Skip the comparator entirely
                // and write the value to state_<N>. This is correct enum behaviour for a
                // value comparator, not a degradation: do not throw, do not flag partial.
                // The no-RHS family is gated by _rmComparatorIsRhsOptional (markers in
                // _rmRhsOptionalComparatorMarkers -- extend BOTH in lockstep if RM adds a new
                // no-RHS token, or this branch silently falls through). A state-change
                // comparator ('*changed*' / '*became*') is the exception -- it has no RHS, so
                // when the caller supplied no value we check whether the value picker offers an
                // option matching the REQUESTED change token exactly. If it does, route it; if
                // not, the comparator is unrepresentable through this enum path and must surface
                // as a genuine skip (-> partial), never a silent clean success.
                if (_rmComparatorIsRhsOptional(cond.comparator)) {
                    if (condStateOrValue != null) {
                        // Contradictory request (state-change comparator + explicit value): the
                        // value lands in state_<N> below and the rule works as an equals-check, so
                        // the dropped change intent is reported via an INFORMATIONAL skip (no partial).
                        skippedAccum << [key: "RelrDev_${cIdx}".toString(), reason: "state_change_comparator_ignored_explicit_value",
                                         value: _rmNormalizeComparator(cond.comparator), attribute: cond.attribute?.toString()]
                        writeST(hrefParams, stateKey, condStateOrValue)
                    } else {
                        // No explicit value: read the value picker's options from the schema the
                        // preceding relrReveal ALREADY fetched (relrReveal.postInputs) -- no second
                        // probe re-fetch. (A throw during that reveal fetch is already handled by
                        // the outer force-write fallback, so there is no separate abort surface
                        // here.) Require an EXACT token match (not just any RHS-optional option) so
                        // 'became false' never routes to a 'became true' slot and 'unchanged' never
                        // satisfies a '*changed*' request. Normalize the requested token ONCE.
                        def pickerInput = (relrReveal.postInputs ?: []).find { it?.name?.toString() == stateKey }
                        def reqToken = _rmComparatorToken(cond.comparator)
                        def changeOption = _rmReadPickerOptionStrings(pickerInput).find { _rmComparatorTokenMatchesOption(reqToken, it) }
                        if (changeOption != null) {
                            writeST(hrefParams, stateKey, changeOption)
                        } else {
                            // skippedAccum is non-null (guarded at entry) -- recording the skip is
                            // the whole point on this degradation path, never a no-op.
                            skippedAccum << [key: "RelrDev_${cIdx}".toString(), reason: "comparator_not_representable_for_enum_attribute",
                                             value: _rmNormalizeComparator(cond.comparator), attribute: cond.attribute?.toString()]
                        }
                    }
                } else if (condStateOrValue != null) {
                    writeST(hrefParams, stateKey, condStateOrValue)
                }
            } else {
                // Neither the comparator nor the value picker rendered -- genuine
                // degradation (firmware drift / transient render failure). Fail loud.
                cancelInFlightCond()
                def visible = relrReveal.visibleNames?.join(', ') ?: "(none)"
                throw new IllegalStateException("conditions[${condIdx}]: Custom Attribute: neither RelrDev_<N> (comparator) nor state_<N> (enum value picker) revealed after rCustomAttr_<N> write (app ${appId}, page ${page}). Visible fields: ${visible}")
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
        _rmClickAppButton(appId, "hasAll", null, page)
        return
    }

    // ---- Device-relative numeric comparison ----
    // When cond.compareToDevice is set, the RHS is another device's attribute
    // (optionally with an offset) rather than a literal threshold.
    // Spec: {capability:'Temperature', deviceIds:[N], comparator:'>',
    //        compareToDevice:{deviceId:M, attribute:'temperature', offset?:-2}}
    // Each _rmRevealStep trigger writes the field that reveals the next field.
    //
    // RM 5.1's device-relative RHS is gated by a BOOLEAN toggle, the exact
    // structural sibling of the Variable capability's isVar_<N> right-hand
    // picker. Invariant chain: writing the comparator RelrDev_<N> reveals
    // isDev_<N> ("Relative to a device?"); toggling isDev_<N>=true reveals
    // relDevice_<N> (the reference device picker, capability-locked to the LHS
    // capability and SINGLE-select) and re-titles state_<N> from a literal value
    // into the optional decimal offset. There is no separate reference-attribute
    // picker -- the compared attribute is implied by the shared capability, so
    // compareToDevice.attribute is optional and informational (it has no wire
    // consumer; the walker never validates or writes it). relDevice_<N> is a
    // capability.* DEVICE picker whose empty-option-list handling is documented at
    // the option-list check below.
    if (cond.compareToDevice instanceof Map) {
        def ctd = cond.compareToDevice as Map
        if (ctd.deviceId == null) {
            cancelInFlightCond()
            throw new IllegalArgumentException("conditions[${condIdx}]: compareToDevice requires 'deviceId' (the reference device ID). Got: ${ctd}")
        }
        // compareToDevice.attribute is OPTIONAL and informational: the compared attribute
        // is implied by the shared capability (there is no separate reference-attribute
        // picker), so it has no wire consumer and is neither validated nor written.
        // A device RHS is only meaningful with a comparator -- it is the operator between
        // the LHS device attribute and the reference device's attribute. Without it
        // rCapab/rDev are written but no comparator and no RHS land -- a half-written
        // condition that silently passes through hasAll and renders incomplete. Fail loud
        // before any hub write, mirroring the other pre-write capability validators.
        if (cond.comparator == null) {
            cancelInFlightCond()
            throw new IllegalArgumentException("conditions[${condIdx}]: compareToDevice requires 'comparator' (e.g. '>', '<', '=') -- a device RHS needs an operator. Without it the condition writes the capability and device but no comparison and renders incomplete.")
        }
        // A device RHS is mutually exclusive with every other RHS shape -- RM renders
        // exactly one. The device-relative path writes the offset (if any) to state_<N>;
        // a caller-supplied state/value would silently never land, and a caller-supplied
        // compareToVariable (variable RHS) would silently be dropped. Reject the ambiguous
        // combination up front, mirroring the Variable path's compareToVariable-vs-state/value
        // reject.
        if (cond.state != null || cond.value != null) {
            cancelInFlightCond()
            throw new IllegalArgumentException("conditions[${condIdx}]: compareToDevice (device-relative RHS) cannot be combined with 'state'/'value' (literal RHS) -- mutually exclusive; use compareToDevice.offset for an offset.")
        }
        if (cond.compareToVariable != null) {
            cancelInFlightCond()
            throw new IllegalArgumentException("conditions[${condIdx}]: compareToDevice (device-relative RHS) cannot be combined with 'compareToVariable' (variable RHS) -- mutually exclusive; either remove compareToVariable (keep compareToDevice) or remove compareToDevice (keep compareToVariable).")
        }
        def refDevId = ctd.deviceId.toString()
        // The reference device is existence-validated hub-wide BEFORE any write -- the
        // up-front validateDeviceIdsRecursive sweeps in _rmAddRequiredExpression (STPage)
        // and _rmAddAction (doActPage) include compareToDevice.deviceId, so a
        // typo'd/nonexistent id is rejected before the walker opens this slot (the walker
        // contract requires cancel-before-throw, and every throw in this block already
        // pairs with cancelInFlightCond -- a mid-walk existence throw would not). This is
        // existence-only, not capability: the LHS is not capability-validated either, and a
        // wrong-capability reference device renders broken just like a wrong-capability LHS
        // (acceptable parity). The option-list check further down is a defensive
        // capability-lock that only fires on the rare firmware variant which surfaces
        // device-picker options; on normal firmware a capability.* device picker exposes none.
        // Write rCapab and rDev as plain writes (no progressive-disclosure on these for numeric caps).
        writeST(hrefParams, "rCapab_${cIdx}".toString(), capCanonical)
        if (cond.deviceIds != null) {
            writeST(hrefParams, "rDev_${cIdx}".toString(), cond.deviceIds)
        }
        if (cond.attribute != null) {
            // cond.attribute is the LHS condition's own Custom-Attribute name (the attribute
            // being COMPARED on the left), written to rCustomAttr_<N>. It is NOT
            // ctd.attribute (compareToDevice.attribute) -- that one names the reference
            // device's attribute, is implied by the shared capability, and is informational
            // only (no wire consumer; neither validated nor written). The two are distinct.
            writeST(hrefParams, "rCustomAttr_${cIdx}".toString(), cond.attribute)
        }
        // RelrDev_<cIdx> (the comparator) must be visible after the rCapab/rDev/rCustomAttr
        // writes land. We cannot know which of those writes gated it, so direct-fetch the
        // current schema and fail loud if it is absent. The loose /RelrDev_\d+/ find is the
        // PRESENCE check only -- it confirms the comparator slot rendered. The actual write
        // target is the exact RelrDev_<cIdx> key (below): in a multi-condition expression a
        // lingering sibling slot would let a loose-find write land in another condition's
        // RelrDev_<other> (writeST verifies the wrong-but-valid field as applied, so the
        // mistake is silent). The exact-key write mirrors the offset state_<cIdx> anchor.
        def afterBaseFields = _rmFetchConfigJson(appId, page)
        def afterBaseInputs = (afterBaseFields?.configPage?.sections ?: []).collectMany { it?.input ?: [] }
        def relrKey = "RelrDev_${cIdx}".toString()
        def relrInput = afterBaseInputs.find { it?.name?.toString() ==~ /RelrDev_\d+/ }
        if (!relrInput) {
            cancelInFlightCond()
            def visible = afterBaseInputs.collect { it?.name?.toString() }.findAll { it }.join(', ') ?: "(none)"
            throw new IllegalStateException("conditions[${condIdx}]: compareToDevice: RelrDev_<N> not visible after rCapab/rDev/rCustomAttr writes. Visible fields: ${visible} (compareToDevice is only supported on numeric device capabilities; '${capCanonical}' does not render a comparator).")
        }
        // Normalize the comparator (!= -> the not-equal glyph, == -> =) for parity with
        // every other comparator write -- the RM enum only accepts the Unicode glyphs.
        def normalizedComparator = _rmNormalizeComparator(cond.comparator.toString())
        // Write the comparator, which reveals the isDev_<N> "Relative to a device?"
        // toggle. Sibling of the Variable path's "write RelrDev, then toggle the RHS
        // boolean to reveal the picker" sequence.
        def isDevKey = "isDev_${cIdx}".toString()
        // Both writes live in ONE reveal closure (the Variable path writes the comparator
        // BEFORE its closure, then toggles isVar inside it). The two shapes are functionally
        // equivalent here -- writeST POSTs and re-fetches per call, so the comparator still
        // commits before isDev toggles regardless of which closure boundary it sits in.
        // Anchor the reveal pattern to relDevice_<cIdx> exactly so a lingering sibling
        // relDevice_<other> from another condition cannot satisfy the reveal in this slot.
        // Verified live on firmware 2.5.0.143: relDevice_<N> reveals and the rule compiles
        // broken:false, rendering "Temperature of <dev> is > <refDev>-<offset>".
        def relDeviceReveal = revealStep(appId, page, "relDevice_${cIdx}".toString(), {
            writeST(hrefParams, relrKey, normalizedComparator)
            writeST(hrefParams, isDevKey, true)
        })
        if (!relDeviceReveal.input) {
            cancelInFlightCond()
            def visible = relDeviceReveal.visibleNames?.join(', ') ?: "(none)"
            throw new IllegalStateException("conditions[${condIdx}]: compareToDevice: reference-device picker relDevice_<N> not revealed after isDev_<N>=true. Without it the condition would render against a literal value (numeric default) instead of device-relative. Visible fields: ${visible}")
        }
        def relDeviceField = relDeviceReveal.input.name.toString()
        // relDevice_<N> is a capability.* DEVICE picker, not an ENUM. Real capability.*
        // device pickers expose NO options in the configPage schema -- RM populates the
        // device dropdown client-side, so only ENUM pickers carry an options map. An empty
        // option list is therefore the NORMAL case here (unlike the Variable ENUM picker,
        // where empty options signal a genuine anomaly): proceed silently with the write.
        // Reference-device existence was already validated hub-wide up front (the
        // validateDeviceIdsRecursive sweep includes compareToDevice.deviceId). The reject
        // branch below is a defensive capability-lock that only fires on the rare firmware
        // variant which DOES surface device-picker options -- a wrong-capability reference
        // device is otherwise caught by the rendered/broken state, not a pre-write option check.
        // Option shapes RM uses for a picker: a {id:label} Map, a flat scalar List, or a
        // list of single-key Maps ([{id:label}, ...]). Extract the device-id key from each
        // shape -- a list of single-key Maps would otherwise stringify each entry as
        // "[id:label]" and never match refDevId, falsely rejecting a valid reference device.
        // Mirrors the list-of-single-key-maps idiom used elsewhere in this file.
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
            throw new IllegalArgumentException("conditions[${condIdx}]: compareToDevice: reference device '${refDevId}' is not in the relDevice_<N> picker -- the picker is locked to the LHS capability '${capCanonical}', so the reference device must carry that capability. ${availLabel}: ${relOpts.sort().join(', ')}")
        }
        // relDevice_<N> is multiple:false (SINGLE device); write the bare id.
        writeST(hrefParams, relDeviceField, refDevId)
        // Optional decimal offset, written to state_<N> (which re-titles to the offset
        // field once isDev_<N>=true). Omit -> offset 0. If the offset field is not visible
        // (firmware variance), degrade with partial:true rather than throw -- the offset
        // is optional and the device-relative comparison is otherwise complete.
        if (ctd.offset != null) {
            def stateKey = "state_${cIdx}".toString()
            // Re-confirm the offset field is present after the relDevice write (the
            // schema re-renders on each write) before committing the offset. Anchor the
            // presence check to THIS slot's state_<cIdx> exactly -- in a multi-condition
            // rule a lingering sibling state_<other> would mis-gate a loose /state_\d+/
            // decision. _rmRevealStep uses String.matches (full-string anchor), so passing
            // the exact stateKey as the pattern matches state_<cIdx> ONLY, never a sibling.
            // (The sibling branches' looser \d+ patterns are pre-existing and out of scope.)
            // The write target is already state_<cIdx>-correct.
            def offsetReveal = discoverField(appId, page, stateKey)
            if (offsetReveal.input && offsetReveal.input.name?.toString() == stateKey) {
                writeST(hrefParams, stateKey, ctd.offset)
            } else {
                mcpLog("warn", "rm-native", "conditions[${condIdx}] (slot ${cIdx}): compareToDevice: offset field ${stateKey} not visible after reference-device write (firmware may not expose the offset slot for this capability); offset=${ctd.offset} dropped")
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
        _rmClickAppButton(appId, "hasAll", null, page)
        return
    }

    // ---- Default path: enum / numeric device capabilities ----
    // Covers Switch, Motion, Contact, Lock, Temperature, Humidity, etc.
    // Write order: rCapab -> rDev_<N> -> rCustomAttr_<N> (if attribute set) ->
    //              RelrDev_<N> (if comparator set; NO re-fetch needed here for non-CustomAttr
    //              because state_<N> is already in the schema for numeric caps) ->
    //              state_<N> -> hasAll.
    writeST(hrefParams, "rCapab_${cIdx}".toString(), capCanonical)
    if (cond.deviceIds != null) {
        writeST(hrefParams, "rDev_${cIdx}".toString(), cond.deviceIds)
    }
    // Write order MATTERS: STPage (like doActPage) uses progressive disclosure.
    // state_<N> does not appear in the schema until RelrDev_<N> commits --
    // empirically confirmed live (rule 1377, 2026-04-28): after rCustomAttr_<N>
    // the schema shows RelrDev_<N>; only after RelrDev_<N> commits does
    // state_<N> appear. Writing state_<N> before RelrDev_<N> silently rejects.
    // Order: rCustomAttr_<N> -> RelrDev_<N> -> state_<N>.
    // For enum capabilities (no comparator), state_<N> appears immediately
    // after rDev_<N>, so the comparator block is a no-op for those paths.
    if (cond.comparator != null) {
        if (cond.attribute != null) {
            // SITE B: a standard capability (Temperature, Humidity, ...) carrying a
            // custom-attribute field. NOTE: this site is NOT reachable through the
            // structured tool surface today -- the high-level path routes a Custom
            // Attribute spec to capability='Custom Attribute' (Site A, the dedicated
            // block above), so Site B has no e2e coverage and is exercised only by the
            // Spock walker specs that drive this default block directly.
            //
            // A standard capability carrying a custom-attribute field re-renders
            // the same way the dedicated Custom Attribute block does: an
            // enum-recognized attribute reveals the value picker state_<N> and
            // hides the comparator RelrDev_<N>; a free-valued attribute reveals
            // RelrDev_<N> (and may also surface state_<N>). writeST does NOT check
            // schema containment, so an unconditional RelrDev_<N> write here would
            // land on a hidden field as silent dead-storage (no not_in_schema flag).
            // Re-fetch after rCustomAttr_<N>, then write the comparator whenever it
            // IS exposed, suppressing only the positively-detected enum case (value
            // picker present, comparator hidden) -- the value lands via state_<N> in
            // the block below.
            writeST(hrefParams, "rCustomAttr_${cIdx}".toString(), cond.attribute)
            // The exposure-probe re-fetch can throw on a transient hub failure
            // (empty/unparseable response). Mirror the dedicated Custom Attribute
            // block's degrade-never-abort contract: on a throw, force-write the
            // comparator best-effort (comparator_force_written_unverified -> partial)
            // and skip the exposed/hidden discrimination below. Force-write breadcrumbs
            // follow the page's write convention (STPage=[] / doActPage=["mainPage"]).
            // KEEP the full input objects from this single fetch -- the enum no-RHS
            // routing below reads the value picker's options from them, so there is no
            // second probe re-fetch (which would be both redundant and a fresh abort risk).
            def afterAttrInputs = null
            try {
                def afterCfg = _rmFetchConfigJson(appId, page)
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
                // Comparator-FIRST, matching the dedicated Custom Attribute block (the
                // revealStep path above): a free-valued attribute can render BOTH the
                // comparator RelrDev_<N> AND the value picker state_<N>, so the presence of
                // a value picker alone does not prove enum. Write the comparator whenever it
                // is exposed; suppress it ONLY for the true enum case (comparator hidden,
                // value picker present -- the value lands via state_<N> below). When neither
                // rendered (a lagged render), still attempt the write: writeST POSTs first
                // then verifies (no schema-containment pre-gate), so the value POSTs but the
                // post-write verify sees RelrDev_<N> absent and records a silent_rejection
                // skip that flips partial -- the degradation is surfaced, not masked, and the
                // wizard is not hard-failed. Normalize the token the same way as the
                // revealStep path (!= -> ≠, == -> =).
                if (comparatorExposed || !attrValuePickerExposed) {
                    // Condition-wizard comparator field is RelrDev_<N> ("Relr"),
                    // not ReltDev_<N> ("Relt" = trigger-row comparator).
                    writeST(hrefParams, "RelrDev_${cIdx}".toString(), _rmNormalizeComparator(cond.comparator.toString()))
                } else {
                    // True enum case (value picker present, comparator hidden). A value
                    // comparator's value lands via state_<N> in the block below; routing here
                    // applies ONLY when no explicit value was supplied. The no-RHS family is
                    // gated by _rmComparatorIsRhsOptional (markers in
                    // _rmRhsOptionalComparatorMarkers -- extend BOTH in lockstep if RM adds a
                    // new no-RHS token, or this branch silently falls through). A state-change
                    // comparator ('*changed*' / '*became*') with no RHS has nowhere to land: if
                    // the picker offers an option matching the REQUESTED change token exactly,
                    // the value-write below will place it once cond.state is set; otherwise the
                    // comparator is unrepresentable and must surface as a genuine skip, not
                    // silent success. An explicit value, when supplied, wins.
                    def bStateVal = cond.state != null ? cond.state : cond.value
                    if (_rmComparatorIsRhsOptional(cond.comparator)) {
                        if (bStateVal != null) {
                            // Contradictory request (state-change comparator + explicit value):
                            // the value lands via state_<N> below and the rule works as an
                            // equals-check, so the dropped change intent is reported via an
                            // INFORMATIONAL skip (no partial).
                            skippedAccum << [key: "RelrDev_${cIdx}".toString(), reason: "state_change_comparator_ignored_explicit_value",
                                             value: _rmNormalizeComparator(cond.comparator.toString()), attribute: cond.attribute?.toString()]
                        } else {
                            // Reuse the input objects already fetched above -- no second re-fetch.
                            def pickerInput = afterAttrInputs.find { it?.name?.toString() == "state_${cIdx}".toString() }
                            // Family gate passed; require an EXACT token match (not just any
                            // RHS-optional option) so 'became false' never routes to 'became true'
                            // and 'unchanged' never satisfies a '*changed*' request. Normalize the
                            // requested token ONCE, not per-candidate.
                            def reqToken = _rmComparatorToken(cond.comparator)
                            def changeOption = _rmReadPickerOptionStrings(pickerInput).find { _rmComparatorTokenMatchesOption(reqToken, it) }
                            if (changeOption != null) {
                                cond.state = changeOption
                            } else {
                                // skippedAccum is non-null (guarded at entry).
                                skippedAccum << [key: "RelrDev_${cIdx}".toString(), reason: "comparator_not_representable_for_enum_attribute",
                                                 value: _rmNormalizeComparator(cond.comparator.toString()), attribute: cond.attribute?.toString()]
                            }
                        }
                    }
                }
            }
        } else {
            // No attribute -- numeric/enum comparator field is already exposed.
            // Normalize for parity with the Custom Attribute paths.
            writeST(hrefParams, "RelrDev_${cIdx}".toString(), _rmNormalizeComparator(cond.comparator.toString()))
        }
    }
    // state and value both write to state_${cIdx} -- STPage has no separate
    // value_<N> field. state (enum string) takes priority; value (numeric
    // threshold) is the alias for Custom Attribute and numeric comparator paths.
    def condStateOrValue = cond.state != null ? cond.state : cond.value
    if (condStateOrValue != null) {
        def stateNavResp = _rmFetchConfigJson(appId, page)
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
    _rmClickAppButton(appId, "hasAll", null, page)
}

// Validate a Required Expression spec's pure INPUT SHAPE -- the checks that
// depend only on the caller's Map, not on any hub state. Runs the conditions
// non-empty check, the operator/operators membership + length rules,
// normalizes singular deviceId -> deviceIds (in place, recursively through
// nested subExpressions), and existence-validates every deviceId /
// compareToDevice reference against the hub. Throws IllegalArgumentException
// on any violation.
//
// Single source of truth so both the add path and the in-place replace path
// reject a malformed spec identically. For replace this is load-bearing: it
// MUST run before the destructive cancelST delete so a bad spec fails with the
// existing Required Expression still intact (the delete wipes the committed
// gate the instant it is clicked).
//
// `label` names the tool in thrown messages (e.g. "addRequiredExpression" /
// "replaceRequiredExpression"). Returns [operator, opsList] for the caller's
// walk (both already uppercased; either may be null).
private Map _rmValidateRequiredExpressionSpec(Map exprSpec, String label, boolean skipDeviceExistence = false) {
    if (!(exprSpec instanceof Map)) {
        throw new IllegalArgumentException("${label} requires a Map spec")
    }
    def conditions = exprSpec.conditions
    if (!(conditions instanceof List) || conditions.isEmpty()) {
        throw new IllegalArgumentException("${label}.conditions is required (non-empty List of {capability, deviceIds?, state?, ...})")
    }
    // Accept either:
    //   - operator: "AND" | "OR" | "XOR"  (single, applied between every pair)
    //   - operators: ["AND", "OR", "XOR", ...]  (one per gap, length = conditions.size()-1)
    // Operators-list path supports mixed expressions like
    // "P1 AND P2 OR P3 XOR P4" where each gap has a different operator.
    // RM 5.1's spec: AND/OR/XOR have equal precedence, evaluated left-to-right.
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

    // Normalize singular deviceId -> deviceIds array for every plain condition in the
    // tree (including inner conditions of subExpressions). Agents occasionally pass
    // deviceId: N (singular) when the contract is deviceIds: [N] (array). Normalizing
    // before the pre-validation loop means validation also covers the normalized form.
    // If both deviceId and deviceIds are provided, deviceIds (explicit array) wins.
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

    // Pre-validate every condition's deviceIds exist on the hub. RM 5.1 silently
    // accepts unknown device IDs at the field-write level (stores {<bogusId>: null}
    // in rDev_<N>) but the resulting expression does not bake. Catch this before any
    // wizard write so callers see a clear error instead of a phantom in-flight rule.
    // Recursive so nested subExpression deviceIds are covered too, with a path string
    // naming the exact nesting site for an actionable error.
    //
    // skipDeviceExistence: the in-place replace path validates the WHOLE spec up front
    // (before its destructive delete) and then delegates to _rmAddRequiredExpression,
    // which would otherwise existence-check every deviceId a second time (one hub GET
    // each). The replace delegate passes skip=true so the device existence probe runs
    // exactly once per replace; shape validation + normalization + operator derivation
    // above still run (cheap, no hub call). Standalone add/replace callers leave it
    // false and probe normally.
    // Condition shape guard -- runs regardless of skipDeviceExistence. Each condition
    // (incl. nested subExpression conditions) MUST be a Map; a non-Map entry would later
    // dereference as one and throw a raw cast/null error deep in the walker. preValidated
    // callers skip only the deviceId existence HUB probe, NOT this cheap shape check, so a
    // malformed conditions:[<non-Map>] still gets an actionable error.
    def validateConditionShapes
    validateConditionShapes = { List cl, String pathPrefix ->
        cl.eachWithIndex { condRaw, i ->
            if (!(condRaw instanceof Map)) {
                throw new IllegalArgumentException("${pathPrefix}[${i}] is not a Map")
            }
            def m = condRaw as Map
            if (m.subExpression instanceof Map) {
                def sub = (m.subExpression as Map).conditions
                if (sub instanceof List) {
                    validateConditionShapes.call(sub as List, "${pathPrefix}[${i}].subExpression.conditions")
                }
            }
        }
    }
    validateConditionShapes.call(conditions as List, "${label}.conditions")

    if (skipDeviceExistence) {
        return [operator: operator, opsList: opsList]
    }
    def validateDeviceIdsRecursive
    validateDeviceIdsRecursive = { List cl, String pathPrefix ->
        cl.eachWithIndex { condRaw, i ->
            // Shape already guarded above; here it is the existence probe per condition.
            def m = condRaw as Map
            _rmValidateDeviceIdsExist("${pathPrefix}[${i}].deviceIds", m.deviceIds)
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

// High-level structured Required Expression creation for Rule Machine 5.1.
// Replaces the 7+ manual wizard calls with one orchestrated call.
// STPage wire-format internals and spec shape: docs/rm_wire_format.md#_rmAddRequiredExpression.
//
// Returns: [success, conditionIndices, settingsApplied, settingsSkipped]
private Map _rmAddRequiredExpression(Integer appId, Map exprSpec, boolean preValidated = false, boolean skipExistingRECheck = false) {
    // Pure-input-shape validation (conditions/operator/operators rules, deviceId
    // normalization, deviceId existence -- the last hits the hub once per deviceId).
    // Shared with the in-place replace path so both reject a malformed spec identically.
    // Throws IllegalArgumentException. The replace delegate passes preValidated=true: it
    // already ran the identical validator up front (before its destructive delete), so
    // re-running it here would existence-check every deviceId a second time. Standalone
    // add callers leave preValidated=false and validate normally. The operator/opsList
    // derivation is still needed, so re-run the validator either way but skip its work
    // when already done -- to keep the deviceId existence GETs from firing twice, the
    // validator itself is invoked only when not preValidated; the derived values are
    // recomputed from the (already-normalized) spec without the existence probe.
    def validated = _rmValidateRequiredExpressionSpec(exprSpec, "addRequiredExpression", preValidated)
    def conditions = exprSpec.conditions
    def operator = validated.operator
    def opsList = validated.opsList

    def applied = []
    def skipped = []

    // deviceId normalization + deviceId/operator/operators existence-and-shape
    // validation runs in _rmValidateRequiredExpressionSpec (called at the top of
    // this method), shared with the in-place replace path.

    // Closure that wraps _rmWriteSubPageField with applied/skipped routing
    // based on the helper's persistence verification (Map return). Use this
    // for every STPage wizard field write so silent rejections are caught
    // and surfaced rather than optimistically claimed as applied.
    def writeST = { Map params, String fieldKey, Object fieldValue, String label = null ->
        def wr = _rmWriteSubPageField(appId, "STPage", "mainPage", "name", 0, params, fieldKey, fieldValue)
        if (wr?.persisted) {
            applied << (label ?: fieldKey)
        } else if (wr?.verifyFetchFailed) {
            skipped << [key: fieldKey, label: (label ?: fieldKey), reason: "verification_fetch_failed", value: fieldValue, verifyError: wr?.verifyError]
        } else {
            skipped << [key: fieldKey, label: (label ?: fieldKey), reason: "silent_rejection", value: fieldValue, schemaUnchanged: true, available: wr?.afterKeys]
        }
        return wr
    }

    // Step 1. Set useST=true on mainPage. Idempotent — safe to write even
    // if a prior expression already exists. The toggle exposes the
    // "Define Required Expression" href on mainPage so the navigate that
    // follows can resolve it.
    //
    // Because the write is idempotent, when useST is already set the schema does
    // not advance and _rmWriteSettingOnPage tags it silent_rejection -- a cosmetic
    // no-op, not a lost value. Re-tag that specific skip to an informational reason
    // so it does not flip partial on an otherwise-clean expression (e.g. a clean
    // enum Custom Attribute condition). Other useST failures keep their reason.
    int skippedBeforeUseST = skipped.size()
    _rmWriteSettingOnPage(appId, "mainPage", "useST", true, applied, "bool", skipped)
    if (skipped.size() > skippedBeforeUseST) {
        def useStSkip = skipped[skippedBeforeUseST]
        if (useStSkip instanceof Map && useStSkip.key == "useST" && useStSkip.reason == "silent_rejection") {
            useStSkip.reason = "useST_idempotent_noop"
        }
    }

    // Step 1b. Detect a rule that ALREADY has a Required Expression. Re-driving
    // addRequiredExpression over an existing RE lands STPage in edit-mode: it
    // renders the committed-expression button set (editST / cancelST / stopOnST /
    // evalOnBoot / doneST) and does NOT offer the `cond` new-condition selector the
    // walk needs. Without this guard the walk writes cond=a into a page that ignores
    // it, then fails partway with a raw "rCapab_<N> not in STPage schema ... got
    // cancelST, editST, ..." schema dump. Detect the edit-mode tell up front and
    // return a clear, actionable error instead of the dump. To CHANGE an existing
    // Required Expression in place, the caller uses replaceRequiredExpression
    // (deletes the committed expression then rebuilds via this same walker).
    //
    // skipExistingRECheck: the in-place replace delegate sets this. It has JUST verified
    // post-delete (via its own STPage read) that the committed-RE controls are gone, so
    // this Step-1b STPage fetch is provably redundant -- skipping it saves one full
    // size-sensitive STPage read per replace. Standalone add callers leave it false.
    if (!skipExistingRECheck) {
        try {
            def preNames = _rmCollectPageInputNames(appId, "STPage")
            // Add path uses the stricter three-field tell (cancelST+editST+stopOnST,
            // no inline new-condition selector) via _rmIsCommittedRETell(requireStopOnST=true).
            if (_rmIsCommittedRETell(preNames, true)) {
                return [
                    success: false,
                    error: "A Required Expression already exists on this rule (app ${appId}). To change it, use replaceRequiredExpression (replaces it in place). To remove it, delete the existing RE in the Rule Machine UI (or hub_restore_backup to a pre-RE snapshot) then re-run addRequiredExpression. Inspect the current expression via hub_get_app_config(appId=${appId}, includeSettings=true).",
                    requiredExpressionAlreadyExists: true
                ]
            }
        } catch (Exception preExc) {
            // Pre-check fetch failed -- do not block the operation on a transient read
            // error; fall through to the normal walk (which has its own fail-loud paths).
            mcpLog("warn", "rm-native", "addRequiredExpression: pre-walk existing-RE check fetch failed for app ${appId} (${preExc.message ?: preExc}); proceeding with the walk")
        }
    }

    // Step 2. Walk each condition through STPage's wizard.
    def hrefParams = [unUsed: null]
    def conditionIndices = []
    // Cleanup helper: when a per-condition write fails partway, the
    // wizard is left mid-edit (rCapab/rDev already written, state_<N>
    // pending). Subsequent calls hit "rCapab_<N> not in STPage schema"
    // because the wizard is still showing the half-edited condition's
    // form. Click cancelCapab to abort the in-flight edit before
    // propagating the error so the next caller starts fresh.
    // Track whether the cancel cleanup itself failed — propagated to the
    // caller's error result via the wizardStuck flag so they know to call
    // hub_restore_backup OR hub_set_rule(button='cancelCapab',
    // pageName='STPage') before issuing the next write.
    def wizardCleanupFailed = false
    def wizardCleanupErr = null
    // cancelledByWalker is reset to false before each per-condition try block
    // (see below). _rmWalkConditionReveal calls cancelInFlightCondition before
    // every throw; the outer catch checks this flag so it does not issue a
    // redundant second cancelCapab click.
    // currentCondIdx is updated by the walkConds per-condition loop so cancel-cleanup
    // warn messages name the in-flight condition index -- without it, N back-to-back
    // failures produce N indistinguishable log entries. Mirrors doActPage's
    // currentActCondIdx pattern in `_rmAddAction` (same per-condition log-naming guard).
    def cancelledByWalker = false
    def currentCondIdx = -1
    def cancelInFlightCondition = {
        cancelledByWalker = true
        try { _rmClickAppButton(appId, "cancelCapab", null, "STPage") }
        catch (Exception cancelExc) {
            wizardCleanupFailed = true
            wizardCleanupErr = cancelExc.message
            mcpLog("warn", "rm-native", "cancelCapab cleanup failed for app ${appId} on STPage at conditions[${currentCondIdx}]: ${cancelExc.message} -- wizard may stay open and confuse subsequent edits; result will carry wizardStuck=true")
        }
    }

    // Recursive walker — handles plain conditions AND sub-expressions
    // (parens). Each condition can be:
    //   {capability, deviceIds, state, ...}   — plain
    //   {subExpression: {conditions: [...], operator/operators}}  — paren
    // STPage's `cond` enum uses option VALUES "a" (New Condition) and "b"
    // (sub-expression); send the value, not the label (live-UI capture
    // 2026-04-26 confirmed). The `oper` enum's "end-sub-expression )"
    // option is itself the value (no short key), so we send the literal
    // string for close-paren only.
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
                // Open paren. Verified live (Chrome probe rule 1383,
                // 2026-04-26):
                //   1. cond=b              ← opens paren (use VALUE "b",
                //                            NOT label "--> ( sub-expression")
                //   2. cond=a + walk + hasAll  ← FIRST inner cond
                //   3. oper=<innerOp>          ← gap op between inner conds
                //   4. cond=a + walk + hasAll  ← LAST inner cond
                //   5. oper=end-sub-expression ← closes paren
                // walkConds itself writes the gap-oper at step 3 (when i<
                // condList.size()-1), so do NOT pre-write an oper before
                // recursing — RM has no inner condition to operate on yet
                // and that pre-write corrupts state, leaving the open-paren
                // rendered as "**Broken Condition**" (pre-writing oper
                // before a sub-expression condition corrupts the accumulator). Likewise sending the label "--> ( sub-
                // expression" instead of the value "b" stores the literal
                // label in settings.cond and breaks downstream schema
                // fetches (next GET returns oper-enum, walkConds aborts).
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
                writeST(hrefParams, "cond", "a", "cond")
                cancelledByWalker = false
                try {
                    // Step 1: re-fetch STPage to discover the RM-assigned condition
                    // slot index (cIdx). The cond=a write above causes RM to allocate
                    // a new rCapab_<N>/rDev_<N>/... slot; N is firmware-assigned and
                    // must be read from the schema rather than assumed.
                    def navResp = _rmFetchConfigJson(appId, "STPage")
                    def stInputs = (navResp?.configPage?.sections ?: []).collectMany { it?.input ?: [] }
                    def rCapabInput = stInputs.find { it?.name?.toString()?.startsWith("rCapab_") }
                    if (!rCapabInput) {
                        throw new IllegalStateException("addRequiredExpression: rCapab_<N> not in STPage schema after cond=a; got ${stInputs.collect { it?.name }.findAll { it }.join(', ')}")
                    }
                    def m = rCapabInput.name.toString() =~ /rCapab_(\d+)/
                    def cIdx = m ? ((m[0] as List)[1] as Integer) : null
                    if (cIdx == null) {
                        throw new IllegalStateException("addRequiredExpression: couldn't parse condition index from '${rCapabInput.name}'")
                    }
                    conditionIndices << cIdx
                    // Step 2: validate the capability value against the schema option list.
                    def capOptions = (rCapabInput.options ?: []) as List
                    def capCanonical = capOptions.find { it.toString().equalsIgnoreCase(cap) }
                    if (!capCanonical) {
                        throw new IllegalArgumentException("conditions[${i}].capability '${cap}' not in STPage option list. Valid: ${capOptions.collect { it.toString() }.sort().join(', ')}")
                    }
                    // Steps 3-N: write the capability, devices, comparator chain, and state
                    // in the correct progressive-disclosure order, then click hasAll.
                    // Snapshot the skipped list size so we can stamp condIdx onto every
                    // walker-side skipped entry after the walk returns. _rmRevealStep and
                    // writeST do not carry condIdx themselves; without this stamping,
                    // multi-field-per-condition degradations (e.g. static-schema test
                    // stubs or firmware-version mismatches) inflate the per-condition
                    // count reported in repairHints (e.g. "7 conditions" for 7 skipped
                    // entries that all belonged to the same in-flight condition).
                    def skippedBefore = skipped.size()
                    _rmWalkConditionReveal(appId, [
                        writeST: writeST,
                        cancelInFlightCondition: cancelInFlightCondition,
                        condIdx: i,
                        cap: cap,
                        capCanonical: capCanonical,
                        hrefParams: hrefParams,
                        applied: applied,
                        skipped: skipped
                    ], cond, cIdx)
                    for (int sIdx = skippedBefore; sIdx < skipped.size(); sIdx++) {
                        def sEntry = skipped[sIdx]
                        if (sEntry instanceof Map && sEntry.condIdx == null) {
                            sEntry.condIdx = i
                        }
                    }
                } catch (Exception perCondExc) {
                    // Only cancel if the walker did not already do so before throwing.
                    // _rmWalkConditionReveal calls cancelInFlightCondition() before every
                    // throw it raises; cancelledByWalker is set true inside that closure.
                    // Without this guard a walker-initiated throw would issue two
                    // back-to-back cancelCapab clicks, the second of which always fails
                    // (nothing to cancel), setting wizardCleanupFailed=true and surfacing
                    // a false wizardStuck=true in the result.
                    if (!cancelledByWalker) {
                        cancelInFlightCondition()
                    }
                    if (wizardCleanupFailed) {
                        // Both the per-condition write AND the cancel cleanup
                        // failed — the wizard is still open. Embed the marker
                        // so the dispatcher's catch can surface wizardStuck=true
                        // alongside the original error.
                        throw new IllegalStateException("${perCondExc.message} [wizardStuck -- cancelCapab cleanup also failed: ${wizardCleanupErr}]")
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

    // Step 3. Seal the expression with hasRule.
    //
    // BACKGROUND:
    //   walkConds (Step 2) builds AND assembles the expression formula inline:
    //   - Each `cond=a` starts a new condition slot (rCapab_<N>/rDev_<N>/state_<N>).
    //   - Each `oper=<AND|OR|XOR>` write between conditions advances RM's
    //     expression-builder to include that condition in the formula (the oper
    //     picker shown after hasAll is RM's expression-assembly state machine,
    //     not a separate post-processing step).
    //   - By the time walkConds returns, the formula is fully assembled.
    //     Writing additional cond=<idx>/oper pairs AFTER walkConds DUPLICATES
    //     conditions in the formula -- verified live: a 2-condition
    //     AND rule produced "(2 AND 3 AND 3)" when Phase 2 assembly writes were
    //     added after walkConds.
    //
    //   Sub-expressions (cond=b / oper=end-sub-expression) also assemble inline
    //   during walkConds, so the same hasRule-only seal applies for all shapes.
    //   No separate assembly loop is needed for plain or sub-expression shapes.
    //
    //   hasRule SEALS the assembled formula -- without it, conditions render
    //   as "(unused)" in Manage Conditions and the RE evaluates false forever.
    //
    //   WIRE FORMAT (verified live, appUI.js source inspection):
    //   The DOM "Done with Expression" button uses a two-step flow:
    //     1. POST /installedapp/btn  settings[hasRule]=clicked (notify)
    //     2. POST /installedapp/update/json  hasRule=button  (actual commit,
    //        because the button has class=submitOnChange)
    //   writeST routes through _rmWriteSubPageField -> /installedapp/update/json
    //   with settings[hasRule]=button, which is step 2 -- the actual commit
    //   handler.  The /installedapp/btn notify step (step 1) is not needed
    //   because the commit is driven entirely by /installedapp/update/json.
    //   Verified live (app 1779): writeST alone produced
    //   state="complete" with params.unUsed=null on mainPage.
    //
    //   NOTE: STPage's completion button in the JSON schema is named "doneST"
    //   (not "hasRule"). "doneST" is the back-navigation exit button; "hasRule"
    //   is the formula-commit field that RM reads from the update/json payload.
    //   They are separate: hasRule commits, doneST navigates.
    writeST(hrefParams, "hasRule", "button", "hasRule")

    // Step 3b. Click doneST to seal STPage into "Done mode".
    //
    // BACKGROUND:
    //   writeST(hasRule) commits the formula text (state=complete, unUsed=null
    //   on mainPage) BUT leaves STPage in "build mode":
    //     - cond field still has required=true
    //     - no editST / cancelST / stopOnST / evalOnBoot buttons visible
    //
    //   In build mode, any browser navigation using _action_href_name from
    //   STPage triggers jsonSubmit with validate=true, which fails the
    //   required-field check on cond and shows the "Required Fields missing"
    //   popup. The user reported this exactly: opening the rule post-build
    //   and clicking "Manage Conditions" shows the popup.
    //
    //   Clicking doneST via /installedapp/btn transitions STPage to "Done mode":
    //     - cond field disappears (no required=true)
    //     - editST / cancelST / stopOnST / evalOnBoot appear
    //     - doneST is idempotent once in Done mode
    //
    //   Verified live (app 1782):
    //     Before doneST: s0i0 cond required=true
    //     After doneST:  s0i0 cancelST, s0i1 editST, s0i2 stopOnST, ...
    //     (no required fields anywhere)
    //
    //   doneST is type=button WITHOUT submitOnChange (no changeSubmit needed).
    //   Wire format: POST /installedapp/btn with settings[doneST]=clicked,
    //   stateAttribute=doneST, doneST.type=button, pageBreadcrumbs=["mainPage"].
    try {
        _rmClickAppButton(appId, "doneST", "doneST", "STPage")
    } catch (Exception doneStExc) {
        // doneST click failure is non-fatal for the formula itself -- the RE
        // is fully committed. It only means the browser may show the
        // "Required Fields missing" popup when navigating STPage manually.
        // Log warn so the operator can detect and navigate away with
        // hub_set_rule(button='doneST', pageName='STPage') as repair.
        mcpLog("warn", "rm-native", "addRequiredExpression: doneST click failed for app ${appId} (${doneStExc.message ?: doneStExc.toString()}) -- RE is committed but STPage stays in build mode; browser navigation may show validation popup")
    }

    // Step 4. Submit sub-page Done to return to mainPage. This is the
    // _action_previous=Done back-nav with full settings -- the proper exit.
    // After Step 3b, STPage is in Done mode (cond not required); the schema
    // sent by _rmSubmitSubPageDone now reflects editST/cancelST/stopOnST/
    // evalOnBoot fields, which is correct for the Done-mode back-nav.
    _rmSubmitSubPageDone(appId, "STPage", "mainPage", "name", hrefParams)

    // Step 4b. Clear RM's residual condition-builder atomicState after
    // the expression-builder hasRule click.
    //
    // BACKGROUND:
    //   The walkConds writes + hasRule seal leave atomicState.predCapabs in a
    //   stale state that wraps subsequent addAction calls in
    //   IF(**Broken Condition**).  _rmClearPredCapabsViaGhostIfThen fires the
    //   ghost ifThen workaround (open condActs/getIfThen slot + actionCancel)
    //   that resets predCapabs without leaving a visible action in the rule.
    //
    //   See _rmClearPredCapabsViaGhostIfThen Groovydoc for the full mechanistic
    //   rationale.  DO NOT remove this call without re-running the full probe
    //   matrix (probes in groups A, A-seal, and A-extended are the regression
    //   gates).
    try {
        _rmClearPredCapabsViaGhostIfThen(appId, "addRequiredExpression")
    } catch (Exception ghostExc) {
        // Best-effort: if the ghost ifThen sequence fails, subsequent addAction
        // calls MAY produce IF(**Broken Condition**) wrapping. The RE itself
        // is fully committed -- this only affects the next addAction.
        mcpLog("warn", "rm-native", "addRequiredExpression: ghost ifThen clear failed for app ${appId} (${ghostExc.message ?: ghostExc.toString()}) -- subsequent addAction may produce IF(**Broken Condition**) wrapper (ghost IF/THEN wrap detected after Required Expression commit); verify rule render or restore backup if needed")
    }

    // Step 5. Post-commit validation. RM 5.1's STPage silently accepts
    // many invalid inputs at the field-write level (e.g. unknown device
    // IDs, unmatched state values) — the hub stores the values but
    // doesn't bake the expression. Detect this by inspecting the
    // mainPage paragraph: if it still shows the bare "Define Required
    // Expression" placeholder, the wizard didn't commit. Reporting this
    // as success would leave the LLM thinking the rule is good when
    // it's not — surface it as a hub-render-style failure with hints.
    // When the post-commit fetch itself throws (cookie expiry, hub 503),
    // joinedParagraphs would be empty and !contains(...) would evaluate
    // true, incorrectly returning success=true on an unverifiable result.
    // Set verificationFetchFailed=true and return success=false instead.
    def mainCfg = null
    def verificationFetchFailed = false
    try { mainCfg = _rmFetchConfigJson(appId, "mainPage") }
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
            error: "Post-commit verification fetch failed (hub may be under load or session expired). The Required Expression write commands were sent but the result cannot be confirmed. Retry hub_get_app_config(appId=${appId}) -- if the expression paragraph appears, the operation succeeded; if not, re-run addRequiredExpression or hub_restore_backup."
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
            error: "Required Expression did not bake -- mainPage still shows 'Define Required Expression' placeholder. Common causes: unknown deviceIds (verify via hub_list_devices), state value not in capability's enum (e.g. 'on' is invalid for Motion which uses 'active'/'inactive'), or capability/state mismatch.",
            repairHints: [
                "Verify every deviceIds entry exists via hub_list_devices.",
                "Verify the 'state' value matches the capability's domain (Switch: 'on'/'off', Motion: 'active'/'inactive', Contact: 'open'/'closed', Lock: 'locked'/'unlocked', etc.).",
                "Inspect the rule's actual state via hub_get_app_config(appId, includeSettings=true) — settings.rDev_<N> = {<id>: null} indicates the device wasn't resolved; settings.rCapab_<N> absent means the condition slot wasn't committed. Note: 'cond' is an ephemeral wizard selector (value 'a'/'b') and is NOT present in appSettings after commit -- conditions live in rCapab_N/rDev_N/state_N only."
            ]
        ]
    }

    // `reveal_fallback_to_existing_field` is INFORMATIONAL only -- it signals
    // the walker matched a field that was already visible BEFORE the trigger
    // closure ran (normal on static-schema firmware, AND normal on Between-two-times
    // where multiple stage reveals naturally land on already-revealed fields after
    // the previous stage committed). The doc contract at the inline hub_get_tool_guide
    // content block says these sentinels do NOT flip `partial` by themselves;
    // production must match. Other reason codes (silent_rejection,
    // offset_field_not_revealed, verification_fetch_failed, not_in_schema, etc.)
    // ARE genuine degradation and continue to flip `partial`.
    // Reason-code is the disambiguator -- compareToDevice's genuinely-partial path
    // uses a distinct code (offset_field_not_revealed), so a single-code exemption
    // here is safe and contract-aligned. Set sourced from
    // _rmInformationalSkippedReasons() so the doActPage callsite in _rmAddAction
    // sees the exact same exemption list without lockstep edits.
    def informationalReasons = _rmInformationalSkippedReasons()
    def hasDegradation = skipped.any {
        it instanceof Map && it.reason != null && !(it.reason in informationalReasons)
    }
    if (hasDegradation) {
        // Mirror the _rmAddTrigger / _rmAddAction split: comparator_force_written_unverified
        // is NOT a lost value -- the comparator WAS POSTed (it is in settingsApplied) but the
        // exposure-probe re-fetch that would confirm it failed transiently. Folding it into
        // the generic "fill missing fields / re-run with rawSettings" hint would wrongly tell
        // the caller to re-write a comparator that DID land. Split it into a
        // verify-don't-rewrite hint; emit the generic degraded-path hint only for genuinely
        // lost fields.
        def degEntries = skipped.findAll {
            it instanceof Map && it.reason != null && !(it.reason in informationalReasons)
        }
        def forceWrittenKeys = degEntries.findAll { it.reason == "comparator_force_written_unverified" }*.key.findAll { it != null }
        // Both comparator_force_written_unverified and comparator_not_representable_for_enum_attribute
        // get their own specific hint below, so exclude them from the generic degraded-path hint.
        def specificHintReasons = ["comparator_force_written_unverified", "comparator_not_representable_for_enum_attribute"]
        def lostEntries = degEntries.findAll { !(it.reason in specificHintReasons) }
        def reRepairHints = []
        if (!lostEntries.isEmpty()) {
            // Count UNIQUE conditions that had any genuinely-lost degraded write, not raw
            // entry count -- a single condition can produce many skipped entries (one per
            // walker field that hit silent_rejection / verification_fetch_failed /
            // not_in_schema). Per-condition stamping (condIdx) happens in the walkConds
            // loop above; entries that still lack condIdx came from outside any
            // per-condition walk (e.g. the useST=true mainPage write).
            def uniqueCondIdxs = lostEntries.collect { it.condIdx }.findAll { it != null }.unique().size()
            def deg = uniqueCondIdxs > 0 ? uniqueCondIdxs : lostEntries.size()
            def cw = (deg == 1) ? "condition" : "conditions"
            reRepairHints << "${deg} ${cw} used a degraded write path (e.g. comparator_force_write_failed or offset_field_not_revealed; see settingsSkipped entries with 'reason'). Re-add the affected field(s) via hub_set_rule(walkStep={page:'STPage', operation:'introspect'}) to see the live schema, then write them one at a time -- or rebuild the expression. Verify first via hub_get_app_config(appId, includeSettings=true): if the expression paragraph renders correctly, the partial flag is cosmetic and no repair is needed."
        }
        if (!forceWrittenKeys.isEmpty()) {
            def cmpWord = forceWrittenKeys.size() == 1 ? "Comparator" : "Comparators"
            def cmpVerb = forceWrittenKeys.size() == 1 ? "was" : "were"
            reRepairHints << "${cmpWord} ${forceWrittenKeys.join(', ')} ${cmpVerb} force-written via a degraded path after a transient re-fetch failure -- the value IS in settingsApplied and success stays true, but it could not be schema-confirmed. Verify via hub_get_app_config(appId): if the expression paragraph renders the comparator correctly, the partial flag is cosmetic. Do NOT re-write -- only re-add via hub_set_rule(walkStep={...}) if the paragraph shows the comparator missing."
        }
        degEntries.findAll { it.reason == "comparator_not_representable_for_enum_attribute" }.each { sk ->
            reRepairHints << _rmNotRepresentableEnumComparatorHint(
                (sk instanceof Map ? sk.attribute : null), (sk instanceof Map ? sk.value : null))
        }
        return [
            success: true,
            partial: true,
            conditionIndices: conditionIndices,
            settingsApplied: applied,
            settingsSkipped: skipped,
            repairHints: reRepairHints
        ]
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

// Collect every input field name visible on a config page. Shared by the
// existing-RE edit-mode detection and the replace-RE delete-verification so
// both read the page through the identical lens (a committed RE renders the
// cancelST/editST controls, a deleted one no longer shows them).
private Set _rmCollectPageInputNames(Integer appId, String pageName) {
    def cfg = _rmFetchConfigJson(appId, pageName)
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
    def baselineIssues = (baselineHealth?.issues ?: []) as Set
    def baselineStructural = (baselineHealth?.structuralIssues ?: []) as Set
    def nowIssues = (nowHealth?.issues ?: []) as Set
    def nowStructural = (nowHealth?.structuralIssues ?: []) as Set
    def newIssues = ((nowIssues - baselineIssues) + (nowStructural - baselineStructural)).collect { it.toString() }
    def baselineMarkerCounts = (baselineHealth?.brokenMarkerCounts instanceof Map) ? (baselineHealth.brokenMarkerCounts as Map) : [:]
    def nowMarkerCounts = (nowHealth?.brokenMarkerCounts instanceof Map) ? (nowHealth.brokenMarkerCounts as Map) : [:]
    nowMarkerCounts.each { marker, cnt ->
        def baseCnt = (baselineMarkerCounts[marker] ?: 0) as Integer
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
// only), is the helper's restoreAfterDelete closure: a POST-commit health flip OR a
// rejected trailing updateRule means the delete already happened but the rule is now
// broken/not-live, so the pre-op backup MUST be put back rather than left destroyed.
// It is invoked with the failure message + the trailing-updateRule diagnostic slots so
// the restore envelope carries requiredExpressionRestored honestly. The add path passes
// null (an add deleted nothing -- there is nothing to restore; the trailing-updateRule
// failure is surfaced via the slots only).
//
// `finalizeOpts` (replace path) carries two knobs:
// - baselineHealth: the pre-delete health verdict. The restore decision gates on a DELTA
// against it -- only issues/structuralIssues present NOW but absent in the baseline
// attribute the break to the replace (same `now - baseline` set-diff the action-mutation
// pre-flights use). _rmCheckRuleHealth flags a pre-existing unbalanced IF/Repeat ACTION
// block (a rule still mid-construction) as ok=false, but that imbalance is EXPECTED and
// its own health text says "do NOT restore" -- so a clean replace on such a rule is NOT
// spuriously rolled back; only a NEW break is. A null baseline (defensive -- shouldn't
// happen, _rmCheckRuleHealth never throws) defaults the baseline sets to empty, so every
// post-commit issue counts as new and the restore fires conservatively.
// - deferUpdateRule: in a patches[] batch the trailing updateRule fires ONCE at batch
// end and rule-level health is the batch's concern, so this finalize skips both its
// own updateRule click and the health-regression RESTORE -- only the per-op rebuild-
// failure restore (handled before finalize) applies inside a batch. The health CHECK
// still runs (it populates health:/success: in the per-op envelope); only the restore
// ACTION on a regression is suppressed.
private Map _rmFinalizeRequiredExpressionWrite(Integer appId, Map innerResult, Map backup, String verb, boolean strictSuccess, Closure restoreOnFailure = null, Map finalizeOpts = [:]) {
    // deferUpdateRule (batch mode): inside a patches[] batch the trailing updateRule
    // fires ONCE at the batch end, not per-op, and rule-level health is the batch's
    // concern -- not this single replace op's. So in defer mode this finalize neither
    // fires its own updateRule click NOR performs the health-regression RESTORE action:
    // the per-op destructive-window safety that genuinely belongs to this op (rebuild-
    // failure restore) was already handled by the helper's earlier branches before finalize
    // is reached. The health CHECK below still runs in defer mode -- it populates the
    // health:/success: fields of the per-op envelope; only the restore on a regression is
    // suppressed. The standalone (non-batch) path leaves deferUpdateRule false and finalizes
    // fully, gated by the baseline-health delta below.
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
    def health = _rmCheckRuleHealth(appId)
    def reCondCount = innerResult?.conditionIndices?.size() ?: 0
    def repairHints = (innerResult?.repairHints as List) ?: []
    if (updateRuleFailed) {
        repairHints = repairHints + ["updateRule click was rejected after the expression conditions wrote successfully. The condition slots are baked but the rule will not re-evaluate the gate until updateRule fires. Retry hub_set_rule(button='updateRule', confirm=true), or restore via backup if the retry also fails."]
    }

    // Restore-on-failure window (replace only): the delete already happened, so a rejected
    // trailing updateRule OR a replace-introduced health regression must put the backup back.
    // The health test is a DELTA against the pre-delete baseline (only NEW issues -- string set
    // diff plus a count-aware broken-marker delta -- attribute the break to the replace), so a
    // pre-existing unrelated imbalance does not roll a clean replace back. Shared with the
    // sole-op patches-batch restore via _rmHealthRegressedVsBaseline. Full rationale in this
    // method's docblock.
    def baselineHealth = (finalizeOpts?.baselineHealth instanceof Map) ? (finalizeOpts.baselineHealth as Map) : null
    def newHealthIssues = _rmHealthRegressionNewIssues(baselineHealth, health)
    boolean healthRegressed = !newHealthIssues.isEmpty()

    // The whole-rule-health restore is suppressed in defer mode (batch): a patches batch
    // owns rule-level health at its single batch-end click, so a mid-batch op must not roll
    // the whole rule back on a transient imbalance a sibling op left. A genuine regression
    // still flips success:false (the !healthRegressed envelope gate below) so the batch's
    // opsOk count reflects it; the per-op rebuild-failure restore (handled before finalize)
    // is the only restore that fires inside a batch. The standalone path restores normally.
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

    // !healthRegressed is the success gate, not the absolute health.ok: a clean replace
    // on a rule that already carried a pre-existing (unrelated) imbalance must still
    // report success. For the add path (no baseline) !healthRegressed == health.ok, so
    // add behavior is unchanged. In defer mode (batch) updateRuleFailed is always false
    // here (the batch end owns updateRule), so the gate reflects only the per-op health.
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
    // requiredExpressionAlreadyExists is an ADD-path diagnostic only: the replace delegate
    // runs with skipExistingRECheck=true (it just deleted the RE), so the existing-RE guard
    // can never trip and the key is structurally always-null on the replace verb. Emit it
    // only for the add path so it is not contract noise on a replace envelope.
    if (verb != "replaced") envelope.requiredExpressionAlreadyExists = innerResult?.requiredExpressionAlreadyExists
    if (verb == "replaced") envelope.requiredExpressionReplaced = (innerResult?.success == true)
    // Deferred replace that COMMITTED (defer mode + a new RE is live): the destructive delete
    // already happened, but this op's share of the single batch-end updateRule was deferred to
    // the batch end. Hand the batch loop the per-op snapshot AND this op's pre-delete baseline
    // health so the batch-end finalize can restore it on the batch-end updateRule failure
    // (always attributable) OR, ONLY when this replace was the SOLE op in the batch, on a health
    // regression vs the baseline (with no siblings the regression IS attributable -- the batch-
    // end loop documents why multi-op batches do NOT use the health trigger). Internal key;
    // the batch loop captures + STRIPS it before the response.
    if (deferUpdateRule && verb == "replaced" && envelope.requiredExpressionReplaced == true) {
        envelope._deferredRERestore = [backup: backup,
            baselineHealth: (finalizeOpts?.baselineHealth instanceof Map) ? finalizeOpts.baselineHealth : null]
    }
    return envelope
}

// Restore a deleted committed Required Expression from a pre-op backup and report the
// recovery outcome HONESTLY. Shared by both restore windows that can fire after the
// destructive cancelST delete already happened: the standalone helper's restoreAfterDelete
// closure, and the patches[] batch-end finalize (when a DEFERRED replace op committed but the
// batch-end updateRule failed, or a sole-op batch regressed health). Both need the identical
// read-back-gated restore -- factoring it here keeps them from drifting.
//
// Returns restore-outcome fields merged ON TOP of `carry` (so a trusted requiredExpression-
// Restored / error authored here always wins over forwarded diagnostics). Honesty rules:
// - no usable backup -> restored:false, "DELETED ... no backup".
// - _rmRestoreFromBackup RETURNS success:false (partial replay) -> restored:false.
// - recreate-under-new-id -> restored:false + requiredExpressionRestoredAs.
// - replay succeeded but the committed-RE tell does NOT re-render -> restored:false
// (the rule may be left UNGATED -- never a false restored:true).
// - replay + read-back both confirm -> restored:true.
// - restore threw -> restored:false, "DELETED ... auto-restore ALSO failed".
private Map _rmRestoreCommittedREFromBackup(Integer appId, Map backup, String errMsg, Map carry = [:]) {
    if (backup?.fileName == null) {
        // No usable backup handle -- cannot auto-restore. Say so loudly; the old RE
        // is gone and the caller must recover by hand.
        mcpLog("warn", "rm-native", "replaceRequiredExpression: post-delete failure on app ${appId} and NO pre-op backup was available to auto-restore -- the original Required Expression is DELETED: ${errMsg}")
        return carry + [
            requiredExpressionRestored: false,
            error: "${errMsg} The original Required Expression was DELETED and no backup was available to auto-restore -- rebuild it via hub_set_rule(addRequiredExpression=...) or hub_restore_backup an earlier snapshot."
        ]
    }
    try {
        // _rmRestoreFromBackup does not always THROW on failure: a mid-restore
        // settings-replay error returns a Map with success:false (the rule exists
        // but its settings are incomplete). Treat that returned-failure the same
        // as a thrown one -- a partial restore is NOT a recovered Required
        // Expression, so it must report restored:false, never a false restored:true.
        def restoreResult = _rmRestoreFromBackup(backup)
        if (restoreResult instanceof Map && restoreResult.success == false) {
            def restoreErr = restoreResult.error ?: "restore reported failure without detail"
            mcpLog("error", "rm-native", "replaceRequiredExpression: post-delete failure on app ${appId} AND auto-restore did not complete (${restoreErr}) -- the original Required Expression is DELETED: ${errMsg}")
            return carry + [
                requiredExpressionRestored: false,
                error: "${errMsg} The original Required Expression was DELETED and auto-restore did not complete (${restoreErr}) -- manually restore via hub_restore_backup(backupKey='${backup.backupKey}')."
            ]
        }
        // RECREATE path: _rmRestoreFromBackup's exists-probe can transiently fail and
        // recreate the rule under a NEW appId (recreated:true + a different ruleId).
        // The original appId is then a husk and the restored expression lives on the
        // new id -- reporting a clean in-place requiredExpressionRestored:true would
        // lie to the caller (their appId is dead). Surface the new id and steer them
        // to it instead of claiming an in-place recovery.
        if (restoreResult instanceof Map && restoreResult.recreated == true
                && restoreResult.ruleId != null && restoreResult.ruleId != appId) {
            def newId = restoreResult.ruleId
            mcpLog("warn", "rm-native", "replaceRequiredExpression: post-delete failure on app ${appId}; auto-restore RECREATED the rule under a new id ${newId} (the original app ${appId} could not be reused) -- the original appId is dead: ${errMsg}")
            return carry + [
                requiredExpressionRestored: false,
                requiredExpressionRestoredAs: newId,
                error: "${errMsg} The original app ${appId} could NOT be restored in place -- auto-restore recreated the rule under a new id ${newId} (the original appId is now dead). Use the new rule ${newId}; delete the husk app ${appId} via hub_delete_native_app(appId=${appId})."
            ]
        }
        // READ-BACK before claiming restored:true. _rmRestoreFromBackup reports
        // success purely from its settings-replay + updateRule click NOT throwing -- it
        // never re-reads to confirm the Required Expression gate actually re-rendered.
        // A replay that wrote the RE field values but did not re-activate the gate would
        // otherwise be reported as restored:true while the rule is silently UNGATED. So
        // re-read STPage and require the committed-RE tell (the same tell the pre-delete
        // check used) before trusting the in-place restore. If the tell is absent, the
        // RE did not come back -- report restored:false so the caller knows to recover.
        // A read-back fetch that itself throws leaves the RE unconfirmable, which is
        // treated as restored:false (defensive: never crash the restore report, never
        // over-claim a recovery we could not verify).
        def reConfirmed = false
        try {
            def confirmNames = _rmCollectPageInputNames(appId, "STPage")
            reConfirmed = _rmIsCommittedRETell(confirmNames)
        } catch (Exception confirmExc) {
            mcpLog("warn", "rm-native", "replaceRequiredExpression: post-restore read-back of STPage failed for app ${appId} (${confirmExc.message ?: confirmExc}) -- cannot confirm the Required Expression re-activated; reporting restored:false to be safe")
        }
        if (!reConfirmed) {
            mcpLog("error", "rm-native", "replaceRequiredExpression: auto-restore from backup ${backup.backupKey} replayed for app ${appId} but the committed Required Expression could NOT be confirmed back on STPage -- the rule may be left UNGATED: ${errMsg}")
            return carry + [
                requiredExpressionRestored: false,
                error: "${errMsg} Auto-restore from backup ${backup.backupKey} replayed but the original Required Expression could NOT be confirmed re-activated (the rule may be left UNGATED). Verify via hub_get_app_config(appId=${appId}, includeSettings=true) and manually restore via hub_restore_backup(backupKey='${backup.backupKey}') if the gate is missing."
            ]
        }
        mcpLog("info", "rm-native", "replaceRequiredExpression: post-delete failure on app ${appId}; auto-restored the original Required Expression from backup ${backup.backupKey} (re-activation confirmed on STPage): ${errMsg}")
        return carry + [
            requiredExpressionRestored: true,
            error: "${errMsg} The replace failed during the rebuild; the original Required Expression was restored from backup ${backup.backupKey}."
        ]
    } catch (Exception restoreExc) {
        mcpLog("error", "rm-native", "replaceRequiredExpression: post-delete failure on app ${appId} AND auto-restore failed (${restoreExc.message ?: restoreExc}) -- the original Required Expression is DELETED: ${errMsg}")
        return carry + [
            requiredExpressionRestored: false,
            error: "${errMsg} The original Required Expression was DELETED and auto-restore ALSO failed (${restoreExc.message ?: restoreExc}) -- manually restore via hub_restore_backup(backupKey='${backup.backupKey}')."
        ]
    }
}

// Replace an existing Required Expression on an RM 5.1 rule IN PLACE (same appId).
//
// REFERENCE PATTERN for destructive in-place edits: (1) validate the WHOLE new spec
// UP FRONT, before the first destructive click, so a malformed spec fails with the old
// state intact; (2) snapshot just before the destructive step (lazily, so a pre-delete
// refusal pays nothing); (3) wrap EVERY post-destruction failure path -- the rebuild,
// the post-commit health DELTA, and the trailing finalize click -- in one restore window
// (restoreAfterDelete) that puts the snapshot back and reports the recovery outcome
// honestly (restored true/false, or the recreated-under-new-id case). The read-back-gated
// restore core is factored into _rmRestoreCommittedREFromBackup, shared with the patches[]
// batch-end finalize (a DEFERRED replace whose single batch-end updateRule failed -- its
// destructive window closes at the batch end, not per-op, so the batch owns that restore).
//
// Full-formula replace, matching addRequiredExpression's whole-expression semantics:
// a committed RE lands STPage on the committed-expression controls (cancelST "Delete
// Required Expression", editST, the conditions on a separate selectConditions sub-page).
// cancelST removes the whole Required Expression and returns the rule to the no-RE state.
// The new condition(s) are then built by delegating to _rmAddRequiredExpression, which
// navigates fresh from mainPage and reaches the cond new-condition selector cleanly.
//
// DESTRUCTIVE-WINDOW CONTRACT. cancelST is immediately destructive: the committed gate is
// gone the instant the click lands. Two invariants protect the caller's data:
// - The ENTIRE spec is validated BEFORE the first click (_rmValidateRequiredExpressionSpec),
// so a malformed spec fails with the OLD Required Expression still intact.
// - After cancelST succeeds, ANY failure auto-restores the pre-op `backup` snapshot and
// reports requiredExpressionRestored:true|false so the caller knows whether data was
// recovered. The covered window extends through the TRAILING finalize: the rebuild,
// the trailing updateRule click, AND the post-commit health DELTA are all inside it
// (finalize runs via _rmFinalizeRequiredExpressionWrite with restoreAfterDelete), so a
// rejected trailing updateRule or a health REGRESSION the replace introduced (new
// issues vs the pre-delete baseline -- a ghost-ifThen clear that left an IF(**Broken
// Condition**) wrapper, say) restores the original rather than leaving the OLD
// expression destroyed-and-broken. A pre-existing, unrelated imbalance present BEFORE
// the replace does NOT trigger a restore (the baseline-health delta filters it out).
//
// Precondition: a committed Required Expression MUST already exist. If none does, returns
// success:false + requiredExpressionMissing:true steering the caller to addRequiredExpression
// -- never silently an add.
//
// `backupOrProvider` is the pre-op snapshot used for the auto-restore. It is EITHER a
// materialized manifest entry (Map with fileName/backupKey -- the top-level dispatcher
// already backed up before any edit) OR a zero-arg Closure that takes the snapshot
// lazily. The closure form lets the patches[] loop defer the snapshot (a config+status
// fetch + File-Manager write) until the replace is actually about to delete -- a
// pre-delete refusal (missing-RE / STPage-read-fail) never deletes, so it should not pay
// for a snapshot. The closure is resolved exactly once, right before the destructive
// cancelST click, after the pre-delete gates pass. When absent, a post-delete failure
// reports requiredExpressionRestored:false.
//
// Success returns the finalized envelope (_rmFinalizeRequiredExpressionWrite: the trailing
// updateRule click + health check + the full add-shape envelope) plus
// requiredExpressionReplaced:true (it means a NEW expression is COMMITTED, not merely that
// the old one was deleted -- false on any rebuild failure). The dispatcher only adds
// appId/backup/partial on top.
//
// `deferFinalize` (set by the patches[] batch loop) tells the trailing finalize to defer
// its updateRule click and its whole-rule-health restore to the batch end. The classic
// wizard fires updateRule ONCE per batch (the batch-end click bakes every op's writes from
// a fully-loaded state); a per-op updateRule + a per-op whole-rule-health restore would
// both double-fire updateRule mid-batch AND could roll the rule back mid-batch on a
// sibling-induced transient imbalance. The per-op destructive-window safety that genuinely
// belongs to THIS op (validate-before-delete, plus restore-this-op's-snapshot when the
// REBUILD ITSELF fails) is kept regardless of deferFinalize. The standalone (non-batch)
// path leaves it false and finalizes fully, gated by the baseline-health delta.
//
// WHY destructive (delete + rebuild + restore) rather than an in-place editST edit: the
// in-place RE-edit / delete gesture through the SmartApp settings/button handler does not
// route cleanly on RM 5.1.8 -- editing the committed expression's conditions in place was
// found blocked by handler routing -- so the supported path is to delete the whole
// expression (cancelST), rebuild it fresh via the same validated add walker, and protect
// the destructive window with the validate-before-delete guard + the auto-restore safety
// net below.
private Map _rmReplaceRequiredExpression(Integer appId, Map exprSpec, Object backupOrProvider = null, boolean deferFinalize = false) {
    // Step 0. Validate the ENTIRE new-conditions spec UP FRONT, before any click.
    // The cancelST delete is immediately destructive -- the committed gate is gone the
    // instant it is clicked. So a malformed spec MUST fail here, with the OLD Required
    // Expression still intact, rather than after the delete. Same validator the add
    // path uses (conditions/operator/operators rules, deviceId normalization, deviceId
    // existence). Throws IllegalArgumentException -> -32602.
    _rmValidateRequiredExpressionSpec(exprSpec, "replaceRequiredExpression")

    // The backup is resolved lazily (Step 2, just before the destructive click) when a
    // provider closure is supplied, so a pre-delete refusal does not take a snapshot.
    // An already-materialized Map is used as-is. `backup` is null until resolved; all
    // pre-delete refusals run with no snapshot (they need none -- the RE stays intact).
    def backupProvider = (backupOrProvider instanceof Closure) ? backupOrProvider : null
    def backup = (backupOrProvider instanceof Map) ? (backupOrProvider as Map) : null

    // After the cancelST click succeeds, the rule is ungated and the old expression
    // is gone. Any subsequent failure (the rebuild returns success:false, or throws,
    // or a post-delete re-read fails) leaves the rule WORSE than before unless we put
    // the original back. This restores the pre-op snapshot the dispatcher took and
    // reports honestly whether the data was recovered. errMsg states what failed;
    // restoredFields are merged onto the returned envelope.
    def restoreAfterDelete = { String errMsg, Map extraFields = [:] ->
        // Layering, safety-first: the delegate's diagnostic `extraFields` go UNDERNEATH
        // the safety keys (extraFields + safe), so a future/forwarded extraFields key
        // named success/requiredExpressionReplaced/error can never clobber the failure
        // verdict. The branch-specific restore outcome (requiredExpressionRestored +
        // the final error text) is authored here and layered ON TOP last -- it is
        // trusted and MUST win. partial:true so a deleted-then-restored recovery surfaces
        // partial consistently across every direct-restore branch, matching the finalize-
        // path restore (which wraps partial:true at the dispatcher) -- the dispatcher
        // coalesces partial off this outcome.
        def safe = [success: false, requiredExpressionReplaced: false, partial: true, error: errMsg]
        def base = (extraFields ?: [:]) + safe
        // wizardStuck parity with the add path: when the destructive build left the
        // STPage wizard half-open (a cancelCapab cleanup click failed mid-walk), the
        // caller needs the same wizardStuck:true + cancelCapab restoreHint the
        // addRequiredExpression dispatcher surfaces via _rmBuildUpdateErrorResponse --
        // otherwise the next write trips the stuck wizard. The tell rides in the errMsg
        // (the thrown delegate message) or an explicit extraFields.wizardStuck. Layered
        // UNDER safe so the failure verdict still wins; merged into every return branch.
        def wizardStuck = (errMsg?.contains("wizardStuck") || errMsg?.contains("cancelCapab cleanup failed")
                           || extraFields?.wizardStuck == true)
        def wizardExtras = wizardStuck ? [
            wizardStuck: true,
            wizardStuckHint: "The STPage wizard may have been left half-open by a failed mid-walk cleanup. Before your next write, call hub_set_rule(button='cancelCapab', pageName='STPage', confirm=true) to close it."
        ] : [:]
        // The read-back-gated restore-and-confirm core is shared with the patches[] batch-end
        // restore (a deferred replace whose batch-end updateRule failed). carry = base +
        // wizardExtras: the trusted requiredExpressionRestored/error authored in the helper
        // layers ON TOP, so the failure verdict + wizardStuck hint still ride out.
        return _rmRestoreCommittedREFromBackup(appId, backup, errMsg, base + wizardExtras)
    }

    // Step 1. Require an existing committed Required Expression. On RM 5.1.8 a
    // committed RE lands STPage on the committed-expression controls: cancelST
    // ("Delete Required Expression") and editST are rendered, the conditions live
    // on a separate selectConditions sub-page, and the inline cond new-condition
    // selector is withheld. The reliable tell is cancelST + editST present with NO
    // inline cond/rCapab_ selector. If that tell is absent the rule has no RE to
    // replace -- refuse loudly and steer the caller to addRequiredExpression rather
    // than silently adding one (which would mask the wrong-tool mistake). PRE-delete
    // refusals all leave the existing RE intact (no restore needed).
    def preNames
    try {
        preNames = _rmCollectPageInputNames(appId, "STPage")
    } catch (Exception preExc) {
        return [
            success: false,
            error: "replaceRequiredExpression: could not read STPage to confirm an existing Required Expression for app ${appId} (${preExc.message ?: preExc}). The hub may be under load or the session expired -- retry, or inspect via hub_get_app_config(appId=${appId}, includeSettings=true)."
        ]
    }
    // The replace path uses the two-field tell (cancelST + editST, no inline
    // new-condition selector), intentionally narrower than the add path's three-field
    // stopOnST-requiring variant -- see _rmIsCommittedRETell for why the two differ.
    if (!_rmIsCommittedRETell(preNames)) {
        return [
            success: false,
            requiredExpressionMissing: true,
            error: "replaceRequiredExpression: no committed Required Expression to replace on app ${appId}. Use addRequiredExpression to create one. Inspect the current expression via hub_get_app_config(appId=${appId}, includeSettings=true)."
        ]
    }

    // Resolve a lazy backup provider NOW -- all pre-delete gates have passed and the
    // destructive click is next, so this is the point at which a snapshot is worth its
    // cost. (A materialized Map backup was already taken by the top-level dispatcher; a
    // provider closure is the patches[] loop deferring its per-op snapshot to here.) The
    // restoreAfterDelete closure captures `backup` by reference, so assigning it here is
    // visible to every subsequent failure branch. If the snapshot itself fails REFUSE
    // before the delete -- the RE is still intact (all pre-delete gates passed), so
    // proceeding into cancelST with no backup would convert a recoverable op into
    // guaranteed data loss on the next failure. The RE is unchanged; the caller retries.
    if (backupProvider != null && backup == null) {
        def made
        try {
            made = backupProvider.call()
        } catch (Exception snapExc) {
            mcpLog("warn", "rm-native", "replaceRequiredExpression: pre-delete backup snapshot failed for app ${appId} (${snapExc.message ?: snapExc}) -- refusing before the destructive delete; the Required Expression is unchanged")
            return [
                success: false,
                error: "replaceRequiredExpression: could not take the pre-op backup before the destructive delete for app ${appId} (${snapExc.message ?: snapExc}); the Required Expression is UNCHANGED -- retry, or take a manual hub backup first."
            ]
        }
        backup = (made instanceof Map) ? (made as Map) : null
        if (backup == null) {
            mcpLog("warn", "rm-native", "replaceRequiredExpression: pre-delete backup snapshot returned no usable handle for app ${appId} -- refusing before the destructive delete; the Required Expression is unchanged")
            return [
                success: false,
                error: "replaceRequiredExpression: the pre-op backup did not produce a usable handle before the destructive delete for app ${appId}; the Required Expression is UNCHANGED -- retry, or take a manual hub backup first."
            ]
        }
    }

    // Baseline health, captured WHILE the old expression and the rest of the rule are
    // still intact (last point before the destructive cancelST click). The trailing
    // finalize gates its restore on the DELTA against this baseline, not absolute health:
    // a rule that already carries an unrelated pre-existing imbalance (e.g. a half-built
    // IF/Repeat action block -- ok=false but EXPECTED mid-construction) must NOT have a
    // successful replace rolled back. Only health problems NEW vs this baseline attribute
    // the break to the replace. _rmCheckRuleHealth swallows its own fetch errors and never
    // throws (a failed read returns a degraded ok:false verdict), so a direct call is safe.
    def baselineHealth = _rmCheckRuleHealth(appId)

    // Outer safety net for the WHOLE post-delete region: the explicit branches below
    // route their known failures (click throw, re-read throw, silent reject, rebuild
    // throw/non-Map/structured-fail, finalize) through restoreAfterDelete. This catch
    // covers any OTHER throw that escapes them once the destructive cancelST may have
    // landed -- without it such a throw would propagate to the dispatcher's catch, which
    // assumes every throw is PRE-delete and would wrongly report the RE "intact" while it
    // was actually deleted. Routing through restoreAfterDelete restores the pre-op backup
    // and returns the honest deleted/restored envelope. (Pre-delete throws -- Step 0
    // validation, the STPage read, the missing-RE refusal, the backup resolution -- stay
    // ABOVE this try, so they still propagate and the dispatcher's "intact" message holds.)
    try {
    // Step 2. Click cancelST ("Delete Required Expression") to remove the whole
    // committed expression. THIS IS THE DESTRUCTIVE STEP -- the committed gate is
    // gone the instant the click lands. From here on, EVERY failure path auto-restores
    // the pre-op backup. Deleting clears useST and returns the rule to the no-RE state
    // so the subsequent delegate (which navigates fresh from mainPage) reaches the cond
    // new-condition selector cleanly. The deleted condition's underlying settings (e.g.
    // rCapab_<N>/modes<N>/rDev_<N>/state_<N>) linger in the pool but are not part of any
    // active formula. NOTE: these orphan slots are deliberately NOT cleared. After the
    // rebuild the new formula re-uses condition slot indices, and the orphan slots are
    // not reliably distinguishable from the active ones on the firmware wire format
    // (index re-use varies by firmware; a slot-name match could clear an ACTIVE slot).
    // Clearing them risks corrupting the live formula for a purely cosmetic gain (the
    // orphans render nowhere and do not affect evaluation), so they are left in place.
    // Safe orphan-slot identification is the prerequisite for ever clearing them.
    try {
        _rmClickAppButton(appId, "cancelST", "cancelST", "STPage")
    } catch (Exception deleteExc) {
        // The click itself threw. A thrown click usually means the POST never
        // committed (>=400 or transport error), so the RE is most likely intact --
        // but cancelST's destructiveness makes that uncertain, so restore to be
        // safe rather than report a maybe-deleted RE as a benign no-op.
        return restoreAfterDelete("replaceRequiredExpression: cancelST (Delete Required Expression) click failed for app ${appId} (${deleteExc.message ?: deleteExc}).")
    }
    // Verify the delete took before rebuilding. A committed RE that survives the
    // click (cancelST still rendered) means the delete was silently rejected -- the
    // delegate would then trip the add path's existing-RE guard and refuse. Confirm
    // the committed-RE controls are gone via STPage; a STPage re-read that throws goes
    // straight to restore (the delete already landed, so the RE is most likely gone).
    def afterDelete
    try {
        afterDelete = _rmCollectPageInputNames(appId, "STPage")
    } catch (Exception afterDeleteExc) {
        // cancelST POST returned 200 (no throw above) but the confirming re-read
        // failed. The expression is almost certainly already deleted -- restore.
        return restoreAfterDelete("replaceRequiredExpression: could not re-read STPage after the delete for app ${appId} (${afterDeleteExc.message ?: afterDeleteExc}).")
    }
    // Mirror the pre-check's FULL guard set (the two-field committed-RE tell): a hybrid
    // render that shows both the committed-expression controls AND a cond / rCapab_
    // selector is the deleting-and-rebuilding transition, NOT a survived RE, so it must
    // not be misread as "the delete did not take." _rmIsCommittedRETell's !newCondSelector
    // half rules out that hybrid; without it, such a render would spuriously restore.
    if (_rmIsCommittedRETell(afterDelete)) {
        return restoreAfterDelete("replaceRequiredExpression: cancelST did not clear the committed Required Expression on app ${appId} (STPage still shows the committed-expression controls: ${afterDelete.sort().join(', ')}); the delete may have been silently rejected or the firmware flow may have changed.")
    }

    // Step 3. Delegate the new condition build to the validated add walker. With the
    // old RE deleted, _rmAddRequiredExpression navigates fresh from mainPage, sets
    // useST, reaches the cond new-condition selector, and walks the new condition(s)
    // -- single OR multi-condition (incl. nested subExpressions) -- exactly as a fresh
    // add, sealing via hasRule/doneST and the sub-page Done. The old RE is already
    // deleted here, so a failed rebuild MUST auto-restore.
    def addResult
    try {
        // preValidated=true: Step 0 already ran the identical validator (incl. the
        // deviceId existence probe), so the delegate skips re-probing every deviceId.
        // skipExistingRECheck=true: the post-delete verify just above already confirmed
        // the committed-RE controls are gone, so the delegate's Step-1b STPage re-read is
        // redundant -- skip it to save one size-sensitive STPage fetch.
        addResult = _rmAddRequiredExpression(appId, exprSpec, true, true)
    } catch (Exception buildExc) {
        return restoreAfterDelete("replaceRequiredExpression: the new condition build threw for app ${appId} (${buildExc.message ?: buildExc}).")
    }
    if (!(addResult instanceof Map)) {
        return restoreAfterDelete("replaceRequiredExpression: the new condition build returned no result for app ${appId}.")
    }
    def addMap = addResult as Map
    if (addMap.success == false) {
        // The rebuild ran but did not produce a live new expression (hubRenderError /
        // verificationFetchFailed / etc.). The old RE is deleted -- restore it and
        // forward the delegate's own diagnostic fields so the caller sees WHY the
        // rebuild failed. requiredExpressionAlreadyExists is NOT forwarded: the RE was
        // deleted before delegating, so the delegate's existing-RE guard cannot trip.
        def diag = [:]
        if (addMap.hubRenderError != null) diag.hubRenderError = addMap.hubRenderError
        if (addMap.verificationFetchFailed != null) diag.verificationFetchFailed = addMap.verificationFetchFailed
        if (addMap.settingsApplied != null) diag.settingsApplied = addMap.settingsApplied
        if (addMap.settingsSkipped != null) diag.settingsSkipped = addMap.settingsSkipped
        if (addMap.repairHints != null) diag.repairHints = addMap.repairHints
        // Forward wizardStuck so restoreAfterDelete surfaces the cancelCapab recovery hint
        // even on a structured (non-throwing) delegate failure that left the wizard open.
        if (addMap.wizardStuck == true) diag.wizardStuck = true
        def innerErr = addMap.error ? " Rebuild error: ${addMap.error}" : ""
        return restoreAfterDelete("replaceRequiredExpression: the new condition build did not commit a live expression for app ${appId}.${innerErr}", diag)
    }
    // Success: a new RE was committed. Finalize INSIDE the destructive window -- the
    // trailing updateRule click and the health check are part of the covered window, so
    // a rejected trailing updateRule OR a health REGRESSION (new issues vs the pre-delete
    // baseline) auto-restores the pre-op backup via restoreAfterDelete, exactly like the
    // other post-delete failure branches. A clean finalize stamps
    // requiredExpressionReplaced:true. Done here (not in the dispatcher) so the delete and
    // its trailing finalize share one restore window -- the old expression is never left
    // destroyed-and-broken.
    //
    // baselineHealth gates the restore on a DELTA (a pre-existing unrelated imbalance must
    // not roll a clean replace back). deferFinalize (batch mode) skips the per-op
    // updateRule click and the whole-rule-health restore -- the batch-end click owns
    // updateRule and rule-level health; the per-op rebuild-failure restores above still
    // apply inside a batch.
    return _rmFinalizeRequiredExpressionWrite(appId, addMap, backup, "replaced", true, restoreAfterDelete,
        [baselineHealth: baselineHealth, deferUpdateRule: deferFinalize])
    } catch (Exception postDeleteExc) {
        // An unexpected throw none of the explicit post-delete branches handled. The
        // cancelST delete may already have landed, so route through restoreAfterDelete:
        // it restores the pre-op backup and returns the honest deleted/restored envelope
        // rather than letting the dispatcher mislabel a deleted RE as "intact".
        return restoreAfterDelete("replaceRequiredExpression: unexpected post-delete failure for app ${appId} (${postDeleteExc.message ?: postDeleteExc}).")
    }
}

// _applyNativeAppEdit — the shared edit engine for an EXISTING app (appId
// required). Backend for the edit arm of hub_set_rule (full RM wizard ladder:
// addTrigger/addAction/addRequiredExpression/walkStep/patches/...) and
// hub_set_native_app (the generic settings/button fall-through). Also handles
// the RM discover/guide static-schema meta-calls. Two raw modes, caller picks
// one (settings OR button):
//
// settings: apply a settings map with the multi-device 3-field contract
// enforced automatically. Always backs up first, always
// verifies the multiple flags post-write with one retry on
// divergence, then runs the updateRule button so the change
// takes effect on the running rule instance.
//
// button:   POST to /installedapp/btn for wizard-navigation buttons
// (editCond, editAct, pausRule, etc.). Useful for driving
// the multi-page authoring flow when callers need to set
// state.editCond / state.editAct before the next settings
// write can reach the right dynamic page.
//
// pageName lets callers target a specific sub-page (e.g. ruleActions,
// triggerCondition, ifthenelseActions) — the schema is introspected from
// that page so settings named on that page get correct marshaling.
def _applyNativeAppEdit(args) {
    // Discover mode short-circuit: {addTrigger: {discover: true}} or
    // {addAction: {discover: true}} returns static schema with no hub
    // interaction -- bypass the in-handler requireDestructiveConfirm gate (confirm
    // + backup snapshot). The Write master still gates this write centrally in
    // executeTool. Pure static data; no hub dependency whatsoever.
    if (args?.addTrigger instanceof Map && args.addTrigger.discover == true) {
        return _rmAddTrigger(0, args.addTrigger as Map)
    }
    if (args?.addAction instanceof Map && args.addAction.discover == true) {
        return _rmAddAction(0, args.addAction as Map)
    }
    // Guide short-circuit: {guide: true} returns the hub_set_rule capability
    // reference inline (same content as hub_get_tool_guide), with no hub interaction
    // and no rule change -- bypasses ALL gates exactly like discover mode above.
    if (args?.guide == true) {
        return toolGetToolGuide('set_rule_reference')
    }
    requireDestructiveConfirm(args?.confirm as Boolean)
    if (args?.appId == null) throw new IllegalArgumentException("appId is required")
    def appId = normalizeRuleId(args.appId)
    def settingsMap = args?.settings instanceof Map ? args.settings : null
    // Raw `settings` mode is the unstructured escape hatch; it doesn't go
    // through addTrigger/addAction's deviceId pre-validator. Scan settings
    // keys for known device-list field-name patterns and validate any
    // List values against the hub. Same RM 5.1 `{<bogusId>: null}` silent-
    // storage bug applies here as in the structured paths.
    if (settingsMap) {
        def devKeyPattern = ~/^([tr]Dev[_-]?\d+|switch[A-Z]\w*|onOffSwitch\.\d+|lockLockUnlock\.\d+|shadeOpenClose\.\d+|fanRL\.\d+|tDev-\d+|deviceList|dimmerLevel\.\d+|ButtontDev_?\d+|pushButton\d+)$/
        settingsMap.each { k, v ->
            if (v instanceof List && k?.toString()?.matches(devKeyPattern)) {
                _rmValidateDeviceIdsExist("settings.${k}", v)
            }
        }
    }
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
    // replaceActions:[] is semantically "clear and add nothing" == clearActions.
    // Normalize it to the clearActions path so the downstream branch reports the
    // clear-only stage/note (not replaceActions.clear_committed_late_no_add with
    // an empty pendingActionsToAdd and "add half not attempted" copy for an add
    // half that never existed). Non-empty lists stay on the replace path.
    if (replaceActionsList != null && replaceActionsList.isEmpty()) {
        clearActionsFlag = true
        replaceActionsList = null
    }
    def moveActionSpec = args?.moveAction instanceof Map ? args.moveAction : null
    def walkStepSpec = args?.walkStep instanceof Map ? args.walkStep : null
    def removeTriggerSpec = args?.removeTrigger instanceof Map ? args.removeTrigger : null
    def modifyTriggerSpec = args?.modifyTrigger instanceof Map ? args.modifyTrigger : null
    if (!settingsMap && !button && !addTriggerSpec && !addActionSpec && !addActionsList && !addTriggersList
            && !addRequiredExpressionSpec && !replaceRequiredExpressionSpec && !addLocalVariableSpec && !removeLocalVariableSpec && !patchesList && !removeActionSpec && !clearActionsFlag && replaceActionsList == null && !moveActionSpec && !walkStepSpec && !removeTriggerSpec && !modifyTriggerSpec) {
        throw new IllegalArgumentException("Editing an app requires one of: 'settings' (Map) or 'button' (String) for any classic app; or, for Rule Machine rules via hub_set_rule, a structured shortcut -- 'addTrigger' (Map), 'addTriggers' (List), 'addAction' (Map), 'addActions' (List), 'addRequiredExpression' (Map), 'replaceRequiredExpression' (Map), 'addLocalVariable' (Map), 'removeLocalVariable' ({name}), 'patches' (List of sub-specs), 'removeAction' ({index:N}), 'clearActions' (true), 'replaceActions' (List), 'moveAction' ({index:N, direction:up|down}), 'removeTrigger' ({index:N}), 'modifyTrigger' ({index:N, mods:{state:...}}), or 'walkStep' ({page, operation, write?, click?, navigate?, validateEnum?}) -- none provided.")
    }

    // Always snapshot before writing. No exceptions — this is the
    // restore channel if anything downstream goes wrong.
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
        (walkStepSpec ? "pre-walkStep" : "pre-update")))))))))))))))
    def backup = _rmBackupRuleSnapshot(appId, backupReason)

    // walkStep — schema-aware single-step wizard walker. Lets a caller
    // (typically an LLM) drive any RM wizard page dynamically: introspect
    // the current schema, perform one operation (write/click/navigate),
    // and see the schema diff + value-echo + health check + sub-page
    // links surfaced in the response. The hardcoded addAction/addTrigger
    // helpers stay as fast paths for common shapes; walkStep is the
    // escape hatch for anything they don't handle (Periodic Schedule
    // sub-pages, conditional trigger binding, IF/THEN/ELSE flow control,
    // not-yet-mapped capability handlers, future RM features).
    if (walkStepSpec) {
        try {
            def result = _rmWalkStep(appId, walkStepSpec)
            result.appId = appId
            result.backup = backup
            // Click mainPage Done as the final step — only if the walk's
            // final operation was 'done' (meaning the wizard flow is complete):
            // a single-step 'done', or a 'drive' whose last step was 'done'.
            // For introspect/write/click/navigate ops in the middle of a
            // multi-step walk, skip Done since the caller is mid-flow.
            def wsOp = walkStepSpec?.operation?.toString()
            if (wsOp == "done" || (wsOp == "drive" && result?.lastStepOperation == "done")) {
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
                    result.repairHints = (result.repairHints ?: []) + ["The session-end mainPage Done click did not commit (${walkDone.reason}). Settings are already written, but the app's update lifecycle did not run -- verify via hub_get_app_config(appId=${appId}) and re-commit via hub_set_native_app(appId=${appId}, button='updateRule') for RM-family apps.".toString()]
                }
            }
            return result
        } catch (Exception e) {
            mcpLogError("rm-native", "walkStep failed for app ${appId}", e)
            return [
                success: false,
                appId: appId,
                error: e.message ?: e.toString(),
                backup: backup,
                restoreHint: "Backup saved before write. Call hub_restore_backup with backupKey='${backup.backupKey}' to roll back."
            ]
        }
    }

    // Action mutation paths — single delete, clear-all, replace-all, move.
    // All re-fire updateRule at the end so the rule re-subscribes from a
    // fully-loaded state (actions self-bake via doActPage->selectActions
    // per-item).
    if (removeActionSpec || clearActionsFlag || replaceActionsList != null || moveActionSpec) {
        // Hoisted out of the try block — Groovy `def` is block-scoped, so
        // declaring inside try would leave these undefined for the return
        // statement below.
        def removed = []
        def addedResults = []
        // moveAction rich return ({beforePosition, afterPosition, indicesAfter}).
        // Hoisted for the same block-scope reason as the others above.
        def moveResult = null
        // removeAction rich return ({removedIndex, beforeIndices, afterIndices}).
        // Surfaced on the outer envelope alongside moveAction's rich return so
        // callers see the index list shift without re-fetching via hub_get_app_config.
        def removeResult = null
        // Trailing-updateRule failure propagation (sibling pattern from the
        // bulk addTriggers/addActions branch and the addTrigger single-spec
        // branch). When the post-mutation updateRule click is rejected, the
        // mutations IS in the rule's appSettings but the running rule
        // instance never re-subscribes to its trigger events. Surface this
        // via dedicated slots so callers can detect without log-grep.
        // subscriptionsNotLive is the slot name because every action-mutation
        // path here ultimately affects the trigger->action wiring that the
        // trailing updateRule re-initializes.
        def updateRuleFailed = false
        def subscriptionsNotLive = false
        def updateRuleError = null
        // Pre-clearActions indices snapshot, declared here (block scope) and
        // populated inside the try just before the clear runs. It costs one hub
        // fetch on every clear/replace, but is only CONSUMED on the async-commit
        // failure path, where it lets the catch echo `actionsRequestedForRemoval`
        // without re-deriving the pre-write index set after the rule already
        // changed. Null when no clear/replace is requested.
        def preClearIndicesSnapshot = null
        try {
            // Pre-flight: walk the replaceActions spec list's capability
            // sequence and refuse the call before clearActions runs if the
            // proposed list is itself structurally imbalanced. Catches the
            // LLM-error case where the caller forgot an endIf / stopRepeat
            // (or stacked an extra one) — better to surface the mistake
            // than wipe the rule and then re-add a broken structure.
            // (Walked against an empty current state because clearActions
            // runs first; the rule's existing actions don't factor in.)
            if (replaceActionsList != null) {
                def specIssues = _rmStructuralIssuesFromSequence(_rmStructuralSequenceFromSpecList(replaceActionsList))
                if (specIssues) {
                    throw new IllegalArgumentException("replaceActions blocked: the proposed action list is structurally imbalanced — ${specIssues.join('; ')}. Re-order the list, add the missing closer (capability='endIf' or 'stopRepeat'), or remove the orphan closer. RM is not touched and no actions are cleared.")
                }
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
                    // _rmClearActions threw mid-flow. Two cases:
                    //   (a) asyncCommit case: trashActs submit returned HTTP 200
                    //       but the verification window exhausted -- the rule IS
                    //       still in trash-confirmation mode with the delete
                    //       selected/in-flight (verified live). Firing
                    //       cancelTrash in that state may COMMIT the pending
                    //       delete rather than abort it -- the exact data-loss
                    //       vector this contract exists to prevent. Skip
                    //       cancelTrash and let the outer catch emit the
                    //       structured envelope with safeRecovery.
                    //   (b) hard-fail case: trashAll click landed but the
                    //       trashActs submit didn't take, OR another mid-flow
                    //       failure leaves nothing selected for deletion. Here
                    //       cancelTrash safely backs the page out of
                    //       trash-confirmation mode and IS the correct recovery.
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
                    if (!(spec instanceof Map)) {
                        addedResults << [success: false, error: "replaceActions[${i}] is not a Map", spec: spec]
                        return
                    }
                    try { addedResults << _rmAddAction(appId, spec as Map, true) }
                    catch (Exception ae) {
                        addedResults << [success: false, error: ae.message, specCapability: spec.capability, specAction: spec.action]
                        mcpLog("warn", "rm-native", "hub_set_rule: replaceActions[${i}] (${spec.capability}/${spec.action}) failed -- ${ae.message}")
                    }
                }
            }
        } catch (Exception e) {
            // Strip the asyncCommit sentinel before logging so the internal
            // marker never reaches operator-facing channels. Single sanitize
            // here, then both the error log and downstream branches consume
            // the cleaned text. The structured-envelope branch below
            // re-derives cleanedError for the same reason -- if e.message
            // didn't carry the marker, replace() returns the original string.
            def cleanedError = e.message?.replace(getAsyncCommitMarker(), "")
            // Intentionally plain mcpLog (NOT mcpLogError): this site deliberately
            // sanitizes the asyncCommit marker out of the message, and mcpLogError
            // would re-leak the raw e.message (marker included) into stackTrace.
            mcpLog("error", "rm-native", "action mutation failed for app ${appId}: ${cleanedError}")
            // Defensive eventual-consistency response shape for the rare
            // clearActions verification-window-exhausted residual. The trashActs
            // submit now runs RM's submitOnChange handler synchronously, so the
            // delete normally commits before the POST returns and this path
            // almost never fires. When it does (state.editAct no-op, or a rare
            // firmware commit lag), the write returned HTTP 200 but verification
            // still saw the actions; callers verify via hub_get_app_config and treat
            // absent as success. replaceActions does NOT proceed to add when this
            // fires -- the clear may have committed and we can't tell yet, so
            // adding would risk double-write if the caller retries the whole
            // op after seeing the partial.
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
                    restoreHint: "If hub_get_app_config confirms the operation did NOT commit, roll back via hub_restore_backup(backupKey='${backup.backupKey}').",
                    verifyHint: "Call hub_get_app_config(appId=${appId}) and inspect the actions list -- if the deletion actually committed asynchronously after the response, do NOT call hub_restore_backup.",
                    safeRecovery: [
                        recommended: 'verify-then-decide',
                        verifyVia: "hub_get_app_config(appId: ${appId})",
                        ifActionsAbsent: 'treat as success -- clearActions committed post-response',
                        ifActionsPresent: "wait 15s, then call hub_get_app_config to re-check. If actions still present, retry clearActions, or clear state.editAct via hub_set_rule(button='cancelAct', pageName='doActPage', confirm=true) first.",
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
            def result = _rmBuildUpdateErrorResponse(appId, e.message, backup)
            // The removeAction retry-exhaustion path needs an extra hint that
            // the helper doesn't know about. This branch now only fires for
            // removeAction exhaustion: the clearActions / replaceActions async-
            // commit case is handled by the structured envelope above.
            // removeAction stays on the legacy flat shape because it's single-
            // row and the dropped-click race is rarer in practice. Detected by
            // the distinctive "one verified re-click" phrase in _rmDeleteAction's
            // exhaustion throw (which fires only after the in-helper re-click
            // also failed to land; BUG-13 had rewritten the original wording).
            def isRetryExhaustion = e.message?.contains("one verified re-click")
            if (isRetryExhaustion) {
                result.restoreHint = "If hub_get_app_config confirms the operation did NOT commit, roll back via hub_restore_backup(backupKey='${backup.backupKey}')."
                result.verifyHint = "Call hub_get_app_config(appId=${appId}) and inspect the actions list -- if the operation actually committed despite the false-fail, do NOT call hub_restore_backup."
            }
            return result
        }
        // Trailing updateRule fires AFTER mutation block completes. Hoisted
        // out of the per-item try so a rejection here doesn't get routed
        // through the generic "mutation errored partway" shape -- the mutation
        // state IS committed and callers need the dedicated failure slots to
        // detect the subscriptions-not-live consequence without log-grep.
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
        // Bubble per-item partial:true (silent_rejection / verification_fetch_failed /
        // hubRenderError on inner field writes) up through the outer envelope. addedOk
        // counts success!=false, so a success:true + partial:true row inflates addedOk
        // and the size-equality check alone misses the inner-partial signal.
        // Sibling pattern: patches dispatcher's innerOk uses `every { ... && partial != true }`.
        def itemsPartial = replaceActionsList != null && (addedOk != addedTotal ||
            addedResults.any { it instanceof Map && it.partial == true })
        def repairHints = []
        if (updateRuleFailed) {
            repairHints << "updateRule click was rejected after the action mutation committed. The action rows are baked but the rule will not subscribe to its device events until updateRule fires. Retry hub_set_rule(button='updateRule', confirm=true), or restore via backup if the retry also fails."
        }
        // Inner-only partial (replaceActions list had partial inner items but the
        // trailing updateRule click landed clean). Without this hint the outer
        // envelope returned partial:true + success:false + repairHints:[] and the
        // caller had to drill into addedActions[] to discover why -- the same C2
        // antipattern this PR has been closing on the *NotLive flags.
        if (itemsPartial && !updateRuleFailed) {
            repairHints << "One or more inner replaceActions items reported partial. Drill into addedActions[] for per-item settingsSkipped + repairHints. Wait 5s and retry, or use removeAction/clearActions to clean up and re-add the failing spec."
        }
        // moveAction soft-return: on a slow hub the move-arrow click can commit
        // AFTER the post-click read; _rmMoveAction does one short re-check and,
        // if the position still has not shifted, returns success:false +
        // asyncCommitLikely instead of throwing. Fold that into the outer
        // success/partial (the addedOk==addedTotal==0 success test would
        // otherwise report success:true for an unconfirmed move) and surface the
        // verify-first hint so a caller verifies before retrying.
        def moveSoftFail = moveResult != null && moveResult.success == false
        if (moveSoftFail && moveResult.verifyHint) {
            repairHints << moveResult.verifyHint.toString()
        }
        return [
            success: (addedOk == addedTotal) && health.ok && !updateRuleFailed && !moveSoftFail,
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
    }

    // Trigger mutation paths — single delete or single field-edit.
    // updateRule fires after each so subscriptions bake from the
    // updated trigger state. Trailing-updateRule failure propagation is
    // wired the same way as the action-mutation block above and the bulk
    // addTriggers/addActions branch -- if the trailing click is rejected,
    // the mutation IS committed but the rule never re-subscribes, so the
    // envelope surfaces dedicated slots (updateRuleFailed /
    // subscriptionsNotLive / updateRuleError) and flips success=false +
    // partial=true.
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
            // removeTrigger / modifyTrigger retry-exhaustion stays on the legacy
            // flat error shape (success:false + restoreHint hint). The structured
            // asyncCommitLikely envelope -- with safeRecovery, actionsRequestedForRemoval,
            // pendingActionsToAdd, etc. -- is scoped to the action-mutation branch
            // (clearActions / replaceActions) where the async-GC race is
            // live-verified and the replace-half data-loss case justifies the
            // richer recovery contract. Trigger-mutation exhaustion is rare and
            // single-row, so the flat shape stays sufficient here. Detected by the
            // distinctive "one verified re-click" phrase in removeTrigger's
            // exhaustion throw (which fires only after the in-helper re-click
            // also failed to land; BUG-13 had rewritten the original wording on
            // both the action and trigger paths).
            def isRetryExhaustion = e.message?.contains("one verified re-click")
            def trigResult = [
                success: false,
                appId: appId,
                error: e.message ?: e.toString(),
                backup: backup,
                restoreHint: isRetryExhaustion ?
                    "If hub_get_app_config confirms the operation did NOT commit, roll back via hub_restore_backup(backupKey='${backup.backupKey}')." :
                    "Backup saved before write. Call hub_restore_backup with backupKey='${backup.backupKey}' to roll back."
            ]
            if (isRetryExhaustion) {
                trigResult.verifyHint = "Call hub_get_app_config(appId=${appId}) and inspect the triggers list -- if the operation actually committed despite the false-fail, do NOT call hub_restore_backup."
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
            repairHints << "updateRule click was rejected after the trigger mutation committed. The trigger row is baked but the rule will not subscribe to its device events until updateRule fires. Retry hub_set_rule(button='updateRule', confirm=true), or restore via backup if the retry also fails."
        }
        if (removeTriggerSpec) {
            // Defense-in-depth: if _rmRemoveTrigger ever starts emitting partial
            // (today it returns {success, removedIndex, beforeIndices, afterIndices}
            // only), bubble it the same way modifyTrigger bubbles trigInnerPartial.
            // Keeps the two trigger-mutation dispatch shapes aligned so a future
            // helper-side addition does not silently drop the signal.
            def removedInnerPartial = trigMutResult?.partial == true
            return [
                success: trigMutResult.success != false && health.ok && !updateRuleFailed,
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
        // Bubble inner settingsSkipped + verificationFetchFailed into outer partial.
        // A modifyTrigger where tstate<N> was silent-rejected (settingsSkipped
        // non-empty) OR post-commit verification failed previously returned
        // {success:false, partial:false}; every other dispatcher returns
        // partial:true on either of those conditions. Match the dispatcher-wide
        // contract.
        def trigSkippedSize = (trigMutResult?.settingsSkipped as List)?.size() ?: 0
        def trigInnerPartial = trigSkippedSize > 0 || trigMutResult?.verificationFetchFailed == true
        // Inner-only partial hint (trigger inner skipped/verify-failed BUT the
        // trailing updateRule landed clean). Mirrors the action-mutation
        // dispatcher's inner-only repairHint above; without it the caller has to
        // drill into settingsSkipped[] to discover why partial flipped true.
        if (trigInnerPartial && !updateRuleFailed) {
            repairHints << "modifyTrigger reported inner partial (settingsSkipped or verificationFetchFailed). Inspect settingsSkipped[] for per-field silent_rejection reasons. Re-attempt the modifyTrigger call, or use removeTrigger + addTrigger to rebuild the trigger atomically."
        }
        return [
            success: trigMutResult.success != false && health.ok && !updateRuleFailed,
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
        // High-level structured trigger creation. Replaces the 6-8 wizard
        // calls of the manual flow with one orchestrated call. After the
        // helper commits, updateRule fires automatically so subscriptions
        // populate immediately -- no manual button call needed.
        // Bulk/patch paths fire their own updateRule at the end of the
        // batch; only this single-spec wrapper adds the auto-fire here.
        def trigResult
        try {
            trigResult = _rmAddTrigger(appId, addTriggerSpec)
        } catch (Exception e) {
            mcpLogError("rm-native", "addTrigger failed for app ${appId}", e)
            return _rmBuildUpdateErrorResponse(appId, e.message, backup)
        }
        // Auto-fire updateRule after a successful commit so eventSubscriptions
        // populate without a separate tool call. Skip on hard failure
        // (success==false): nothing committed, so updateRule would be a no-op
        // and the extra hub round-trip isn't worthwhile. On partial success
        // (trigger row exists but some fields didn't land) fire updateRule
        // anyway -- the trigger IS in the rule and subscriptions should bake
        // from whatever committed, matching the addAction pattern.
        //
        // Symmetric with addRequiredExpression: if the trigger row commits but
        // updateRule is rejected, eventSubscriptions never populate -- the trigger
        // is in the rule's appSettings but the running rule instance never
        // re-subscribes to the device events that fire it. Surface this via
        // dedicated response slots (updateRuleFailed / subscriptionsNotLive /
        // updateRuleError) so callers can detect without log-grep, and flip
        // success=false + partial=true so they do not treat the response as
        // fully baked. Mirrors the propagation pattern in the
        // addRequiredExpression dispatcher branch (`_rmAddRequiredExpression`
        // trailing-updateRule catch block).
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
            trigRepairHints = trigRepairHints + ["updateRule click was rejected after the trigger row committed. The trigger settings are baked but the rule will not subscribe to its device events until updateRule fires. Retry hub_set_rule(button='updateRule', confirm=true), or restore via backup if the retry also fails."]
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
        // High-level structured action creation. Mirrors addTrigger:
        // replaces the 6-7 wizard calls of the manual doActPage flow
        // with one orchestrated call. Action committed via
        // doActPage->selectActions navigation; no trailing updateRule
        // click is issued or required for action bake.
        def actResult
        try {
            actResult = _rmAddAction(appId, addActionSpec)
        } catch (Exception e) {
            mcpLogError("rm-native", "addAction failed for app ${appId}", e)
            return _rmBuildUpdateErrorResponse(appId, e.message, backup)
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
            repairHints: actResult?.repairHints,
            health: actResult?.health,
            verificationFetchFailed: actResult?.verificationFetchFailed,
            note: "Action added + committed (baked into actions[] map). No trailing updateRule needed; doActPage->selectActions navigation finalizes."
        ]
    }

    if (patchesList != null) {
        // Multi-mutation atomic patch. Each item in the patches
        // list is a dict with one of the supported sub-operations:
        //   {addRequiredExpression: {...}}
        //   {addTrigger: {...}} | {addTriggers: [...]}
        //   {addAction: {...}} | {addActions: [...]}
        //   {addLocalVariable: {...}}
        //   {settings: {...}}
        //   {removeAction: {index}} | {clearActions: true} | {replaceActions: [...]}
        //   {moveAction: {index, direction}}
        //   {button: <name>, stateAttribute?, pageName?}
        // Operations apply sequentially. updateRule fires ONCE at the end
        // — not after each sub-op — so the rule's actions[] map and
        // subscriptions bake from a fully-loaded state. This mirrors
        // Operations are atomic from the rule's perspective.
        def patchResults = []
        def patchErr = null
        // Trailing-updateRule failure propagation: when the post-patch updateRule
        // click is rejected, the patches landed but the rule never bakes them. The
        // catch in the trailing-updateRule block below sets these so the response
        // surfaces dedicated slots (sibling pattern from F2 addRequiredExpression).
        def updateRuleFailed = false
        def patchesNotLive = false
        def updateRuleError = null
        // Deferred-replace restore contexts (per-op snapshot + pre-delete baseline) for every
        // replaceRequiredExpression op that COMMITTED a new RE in defer mode. The destructive
        // delete already happened for these, so the batch-end finalize restores each op's pre-op
        // snapshot if the single batch-end updateRule fails (always attributable) -- or, when the
        // replace was the SOLE op in the batch, on a health regression vs the baseline. On the
        // updateRule-failure path the entries restore in insertion order; because each op's
        // snapshot was taken just before that op deleted, it already captures every preceding
        // committed op, so restoring one op preserves the preceding ops' work. Ops that ran AFTER
        // the replace in the same batch ARE reverted by that restore (the snapshot predates them);
        // acceptable because a batch-end updateRule failure means the whole batch is not live, and
        // restoring the irreversibly-deleted RE outranks the additive post-replace ops -- the
        // pre-batch backup remains the caller's full handle.
        def deferredReReplaces = []
        // A rule has exactly ONE Required Expression, so only one replaceRequiredExpression is
        // valid per batch; a second would replace the first (and its additive restore would land
        // on the intermediate, not the original). Track it to refuse the second.
        def seenReplaceRE = false
        try {
            patchesList.eachWithIndex { p, pi ->
                if (!(p instanceof Map)) {
                    patchResults << [success: false, error: "patches[${pi}] is not a Map", spec: p]
                    return
                }
                def pm = p as Map
                try {
                    if (pm.containsKey("settings")) {
                        // Apply settings via _rmUpdateAppSettings (no auto-updateRule).
                        def cfg = _rmFetchConfigJson(appId, pm.pageName?.toString())
                        def schema = _rmCollectInputSchema(cfg?.configPage)
                        _rmUpdateAppSettings(appId, pm.settings as Map, schema)
                        patchResults << [success: true, op: "settings", keys: (pm.settings as Map).keySet().toList()]
                    } else if (pm.containsKey("button")) {
                        _rmClickAppButton(appId, pm.button.toString(), pm.stateAttribute?.toString(), pm.pageName?.toString())
                        patchResults << [success: true, op: "button", name: pm.button]
                    } else if (pm.containsKey("addTrigger")) {
                        patchResults << ([op: "addTrigger"] + _rmAddTrigger(appId, pm.addTrigger as Map))
                    } else if (pm.containsKey("addTriggers")) {
                        def innerResults = []
                        (pm.addTriggers as List).each { tspec ->
                            try { innerResults << _rmAddTrigger(appId, tspec as Map) }
                            catch (Exception e) { innerResults << [success: false, error: e.message ?: e.toString()] }
                        }
                        // Outer success rolls up inner success — mark the
                        // outer entry as failed if ANY inner item failed,
                        // so opsOk count + the user-visible success flag
                        // accurately reflect partial-batch failures.
                        def innerOk = innerResults.every { (it instanceof Map) && (it.success != false) && (it.partial != true) }
                        patchResults << [success: innerOk, op: "addTriggers", results: innerResults]
                    } else if (pm.containsKey("addAction")) {
                        patchResults << ([op: "addAction"] + _rmAddAction(appId, pm.addAction as Map, true))
                    } else if (pm.containsKey("addActions")) {
                        def innerResults = []
                        (pm.addActions as List).each { aspec ->
                            try { innerResults << _rmAddAction(appId, aspec as Map, true) }
                            catch (Exception e) { innerResults << [success: false, error: e.message ?: e.toString()] }
                        }
                        def innerOk = innerResults.every { (it instanceof Map) && (it.success != false) && (it.partial != true) }
                        patchResults << [success: innerOk, op: "addActions", results: innerResults]
                    } else if (pm.containsKey("addRequiredExpression")) {
                        patchResults << ([op: "addRequiredExpression"] + _rmAddRequiredExpression(appId, pm.addRequiredExpression as Map))
                    } else if (pm.containsKey("replaceRequiredExpression")) {
                        // A rule has a single Required Expression, so only the FIRST
                        // replaceRequiredExpression in a batch is meaningful. Refuse a second:
                        // it would replace the first one's result, and its additive per-op
                        // snapshot would restore to the INTERMEDIATE expression (not the
                        // original) while the read-back -- which is identity-blind -- still
                        // reports restored:true. Fail the op loudly instead.
                        if (seenReplaceRE) {
                            patchResults << [success: false, op: "replaceRequiredExpression",
                                error: "patches[${pi}]: a rule has a single Required Expression; only one replaceRequiredExpression is valid per patches batch -- the second would replace the first. Remove the duplicate, or issue the second replace as a separate hub_set_rule call."]
                            return
                        }
                        seenReplaceRE = true
                        // Per-op snapshot, NOT the pre-batch `backup`: replaceRequiredExpression
                        // auto-restores its backup on a post-delete failure. Handing it the
                        // pre-batch snapshot would roll the whole rule back to before this batch,
                        // silently reverting earlier ops that already succeeded. A fresh snapshot
                        // captures those earlier ops, so a failed replace restores only to the
                        // just-before-this-op state -- the failed op becomes a no-op and the
                        // preceding siblings are preserved. The dispatcher's pre-batch `backup`
                        // stays the user's full-batch rollback handle. The snapshot is passed as a
                        // LAZY provider so a pre-delete refusal (missing-RE / STPage-read-fail) --
                        // which never deletes -- does not pay for a config+status fetch + File-
                        // Manager write; the helper materializes it only when about to delete.
                        def opBackupProvider = { _rmBackupRuleSnapshot(appId, "pre-patch-replaceRequiredExpression") }
                        // deferFinalize=true: the batch-end updateRule click (below) fires
                        // ONCE for the whole batch, so the replace op must NOT fire its own
                        // trailing updateRule here, and rule-level health is the batch's
                        // concern -- so the replace skips its whole-rule-health restore
                        // mid-batch too (it could otherwise roll the rule back on a transient
                        // imbalance a sibling op left, before the batch completes). The per-op
                        // destructive-window safety that IS per-op (validate-before-delete +
                        // restore-this-op's-snapshot when the REBUILD itself fails) is kept.
                        def replRes = _rmReplaceRequiredExpression(appId, pm.replaceRequiredExpression as Map, opBackupProvider, true)
                        // Capture the deferred-restore context (per-op snapshot + pre-delete
                        // baseline) for any replace op that COMMITTED a new RE, then STRIP it from
                        // the patch entry (internal-only -- never on the wire). The batch-end
                        // finalize restores on the batch-end updateRule failure, or -- for a sole-op
                        // batch -- on a health regression vs the baseline. patchResults index lets
                        // the batch-end fold the restore outcome back onto the right entry.
                        if (replRes instanceof Map && replRes._deferredRERestore instanceof Map) {
                            def ctx = (replRes._deferredRERestore as Map)
                            deferredReReplaces << [resultIndex: patchResults.size(), backup: ctx.backup, baselineHealth: ctx.baselineHealth]
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
                        patchResults << [
                            success: true,
                            op: "removeAction",
                            index: pm.removeAction.index,
                            removedIndex: rmRes?.removedIndex,
                            beforeIndices: rmRes?.beforeIndices,
                            afterIndices: rmRes?.afterIndices
                        ]
                    } else if (pm.containsKey("clearActions")) {
                        def cleared
                        try { cleared = _rmClearActions(appId) ?: [] }
                        catch (Exception clearExc) {
                            // Same asyncCommit-vs-hard-fail split as the top-
                            // level dispatcher: on the asyncCommit path the
                            // trashActs submit returned HTTP 200 and the rule is
                            // still in trash-confirmation mode with the delete
                            // in-flight, so firing cancelTrash here risks
                            // committing the pending delete. Skip on async;
                            // recover normally on hard-fail.
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
                        // replaceActions:[] is "clear and add nothing" == clearActions.
                        // Mirror the top-level dispatcher normalization so an empty
                        // list reports the clear-only op (not replaceActions with an
                        // empty add half). Behavior matches the patches clearActions
                        // branch exactly: clear, no add-half handling.
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
                        (pm.replaceActions as List).each { aspec ->
                            try { innerResults << _rmAddAction(appId, aspec as Map, true) }
                            catch (Exception e) { innerResults << [success: false, error: e.message ?: e.toString()] }
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
                    } else {
                        patchResults << [success: false, error: "patches[${pi}] has no recognized operation key. Supported: settings, button, addTrigger(s), addAction(s), addRequiredExpression, replaceRequiredExpression, addLocalVariable, removeLocalVariable, removeAction, clearActions, replaceActions, moveAction.", spec: p]
                    }
                } catch (Exception subExc) {
                    // Strip the internal asyncCommit sentinel from the user-
                    // visible error string. The patches branch doesn't emit the
                    // structured envelope (scoped to the top-level clearActions
                    // / replaceActions path), but the raw marker shouldn't leak
                    // into per-patch error / warn-log either. Local name distinct
                    // from the outer-closure `patchErr` so the top-level result
                    // envelope still drives off the post-trailing-updateRule
                    // aggregate, not the per-patch exception text.
                    def cleanedPatchErr = subExc.message?.replace(getAsyncCommitMarker(), "") ?: subExc.message
                    patchResults << [success: false, op: pm.keySet().first(), error: cleanedPatchErr, spec: p]
                    mcpLog("warn", "rm-native", "patches[${pi}] (${pm.keySet().first()}) failed: ${cleanedPatchErr}")
                }
            }
            // Fire updateRule once at the end so the rule's actions[]
            // map and event subscriptions bake from the fully-loaded
            // post-patch state. Honour the comment: silent failure here means
            // the patches landed but never bake into the running rule, so
            // surface via dedicated envelope slots (sibling pattern from F2:
            // addRequiredExpression slot propagation in the
            // `addRequiredExpressionSpec` dispatcher branch and F1's
            // counterpart in the `addTriggerSpec` dispatcher branch).
            try { _rmClickAppButton(appId, "updateRule") }
            catch (Exception updateExc) {
                updateRuleFailed = true
                // patchesNotLive is the batch-generic not-live slot: a patches batch may
                // carry any mix of ops, so the trailing-updateRule failure is reported
                // once at the batch level rather than with per-op-specific slots like
                // expressionNotLive/subscriptionsNotLive. The op-specific outcome (e.g. a
                // replaceRequiredExpression's requiredExpressionReplaced) still rides in
                // its own patchResults[] entry.
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
        // Batch-end restore for DEFERRED replaceRequiredExpression ops (re-homed destructive-
        // window contract). Restore on two triggers, by attributability: the batch-end updateRule
        // failing (always -- that one click makes every deferred RE live), or a health regression
        // vs a replace's pre-delete baseline ONLY when that replace was the SOLE op (no siblings ->
        // attributable, parity with standalone -- it uses the SAME _rmHealthRegressedVsBaseline
        // delta, so a single-op batch and the standalone path detect a new break identically
        // (string set-diff plus count-aware broken-marker delta). In a multi-op batch the post-
        // batch health is cumulative, so a later sibling's imbalance must not roll an earlier
        // replace back; the health trigger is suppressed there. A rebuild failure was already
        // restored in the loop.
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
                def restoreOutcome = _rmRestoreCommittedREFromBackup(appId, ctx.backup as Map,
                    "replaceRequiredExpression (in patches batch): ${why}.")
                def idx = ctx.resultIndex as Integer
                if (idx != null && idx >= 0 && idx < patchResults.size() && patchResults[idx] instanceof Map) {
                    // Restore outcome wins: the live RE did not stick, so the op did not succeed.
                    // requiredExpressionReplaced:false (the committed flag is now untrue -- the RE
                    // was rolled back) so the entry does not carry both replaced:true AND
                    // restored:true, and the restore note replaces the stale "deferred" note.
                    patchResults[idx] = (patchResults[idx] as Map) + restoreOutcome + [
                        success: false, partial: true, requiredExpressionReplaced: false,
                        note: "Required Expression replace ROLLED BACK in batch: ${why}; the original was restored."]
                    anyRestored = true
                }
            }
            // Recompute the success rollup after any deferred-restore reclassification above.
            if (anyRestored) opsOk = patchResults.count { it?.success != false }
        }
        def repairHints = []
        if (updateRuleFailed) {
            repairHints << "updateRule click was rejected after the patch ops committed. The patch settings are baked but the rule will not re-evaluate / re-subscribe until updateRule fires. Retry hub_set_rule(button='updateRule', confirm=true), or restore via backup if the retry also fails."
        }
        // Inner-only partial hint (one or more patch ops self-reported partial:true BUT
        // the trailing updateRule click landed clean). Outer partial: already bubbles
        // the inner-partial signal via `patchResults.any { ... partial == true }` in the
        // OR-clause below; without this hint the outer repairHints stayed empty and the
        // caller had to drill into patches[] to discover why partial flipped true.
        // Sibling pattern from the action-mutation and modifyTrigger dispatchers'
        // inner-only branches -- closes the same C2 antipattern at this dispatch site.
        if (patchResults.any { it instanceof Map && it.partial == true } && !updateRuleFailed) {
            repairHints << "One or more patch ops reported partial. Drill into patches[] for per-op settingsSkipped + repairHints. The patch ops landed but some inner fields didn't; address the per-op partials directly or re-issue the patches batch."
        }
        return [
            success: (patchErr == null) && (opsOk == patchResults.size()) && health.ok && !updateRuleFailed,
            // `partial` is ORTHOGONAL to success: a patches call where every op
            // landed but one carried partial:true (e.g. trailing-updateRule
            // failure on an inner addRequiredExpression, or settingsSkipped on
            // an inner addTrigger), or a call where the outer trailing-updateRule
            // failed, or a call where any op returned success=false -- all must
            // surface `partial: true` so callers do not silently treat the
            // response as fully baked. Mirrors the bulk path at the
            // addTriggers/addActions branch and the addRequiredExpression /
            // addTrigger single-spec branches.
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
            return _rmBuildUpdateErrorResponse(appId, e.message, backup)
        }
        // Shared add/remove envelope: fires the trailing updateRule, settles, health-
        // checks, and ORs the inner helper's partial/error/repairHints (incl. the
        // commit-verification {success:false, partial:true, hubRenderError:true, ...}
        // shape) into the outer response, so callers can distinguish "trailing updateRule
        // rejected" from "variable never rendered into appState".
        return _rmApplyLocalVarResult(appId, (varResult ?: [:]) as Map, backup, "add")
    }

    if (removeLocalVariableSpec) {
        // Local variable removal. Drives selectActions' deleteGV/delConfirm two-step
        // delete wizard, then verifies the var left state.allLocalVars. The envelope
        // FOLLOWS the addLocalVariableSpec branch's PLUMBING (capture the helper Map, fire
        // the trailing updateRule, health-check, OR the inner partial into the outer
        // partial), but the variable SUB-SHAPE intentionally differs: add returns
        // {name, type, value} (the created variable), remove returns {name, deleted}
        // (no type/value -- a deleted variable has neither). Same plumbing, different payload.
        def varName = removeLocalVariableSpec.name?.toString()?.trim()
        if (!varName) throw new IllegalArgumentException("removeLocalVariable requires 'name' (the local variable to delete)")
        def varResult
        try {
            varResult = _rmRemoveLocalVariable(appId, varName)
        } catch (Exception e) {
            mcpLogError("rm-native", "removeLocalVariable failed for app ${appId}", e)
            return _rmBuildUpdateErrorResponse(appId, e.message, backup, "selectActions")
        }
        // Shared add/remove envelope (opKind='remove' -> {name, deleted} variable shape,
        // remove-specific repairHint/note wording). A verify miss returns
        // {success:false, partial:true, error, repairHints} from the helper; the shared
        // assembler ORs that into the outer partial so a caller never treats an
        // unconfirmed delete as done.
        return _rmApplyLocalVarResult(appId, (varResult ?: [:]) as Map, backup, "remove")
    }

    if (addRequiredExpressionSpec) {
        // High-level structured Required Expression creation. Replaces
        // the 7+ walkStep wizard calls of the manual flow with one
        // orchestrated call. Caller specifies a list of conditions and
        // (optional) joining operator; the helper navigates STPage,
        // walks each condition's reveal sequence, commits via hasAll +
        // hasRule, and submits the sub-page Done back-nav. After return
        // the rule's mainPage paragraph renders the expression text and
        // settings show useST=true + per-cond
        // rCapab_<idx>/rDev_<idx>/state_<idx>. Note: `cond` is the
        // in-wizard selector (value "a"/"b") and is NOT persisted in
        // appSettings after STPage commit -- conditions are stored in
        // rCapab_N/rDev_N/state_N only. Verified live via diag probes.
        def reResult
        try {
            reResult = _rmAddRequiredExpression(appId, addRequiredExpressionSpec)
        } catch (Exception e) {
            mcpLogError("rm-native", "addRequiredExpression failed for app ${appId}", e)
            // STPage is the wizard page this branch operates on; the helper threads it
            // into the wizardStuck restoreHint and surfaces preflight refusals with the
            // current health block (which a future preflight on this branch would set).
            return _rmBuildUpdateErrorResponse(appId, e.message, backup, "STPage")
        }
        // Fire updateRule (leaf-operation contract: addRequiredExpression does it here)
        // + health check + envelope, via the shared finalize helper. add passes no
        // restore closure -- an add deleted nothing, so a trailing-updateRule failure is
        // surfaced via the dedicated slots, not auto-restored. Keeps the permissive
        // `!= false` success form for behavior compatibility (strictSuccess=false). A
        // structured success:false the helper RETURNED (e.g. requiredExpressionAlreadyExists)
        // stays false through the finalize gate and rides out on the envelope unchanged.
        return _rmFinalizeRequiredExpressionWrite(appId, reResult as Map, backup, "added", false, null)
    }

    if (replaceRequiredExpressionSpec) {
        // In-place Required Expression replace. Deletes the whole committed expression
        // (cancelST), then delegates the new condition build to the same structured
        // walker addRequiredExpression uses, then finalizes (trailing updateRule +
        // health) -- ALL inside the helper's restore window, so a post-commit health
        // flip or rejected updateRule auto-restores the pre-op backup (the old
        // expression is never left destroyed-and-broken). The helper returns its own
        // fully-assembled envelope; the dispatcher only owns appId/backup/partial.
        def replResult
        try {
            replResult = _rmReplaceRequiredExpression(appId, replaceRequiredExpressionSpec, backup)
        } catch (Exception e) {
            // A throw here is pre-delete input validation (-32602-class) -- the helper
            // validates the spec before the destructive cancelST click, so a throw
            // means the OLD Required Expression is still intact (no data lost). Suppress
            // the rollback restoreHint: nothing was deleted, so a "roll back via backup"
            // hint would mislead the caller into restoring an intact rule.
            mcpLogError("rm-native", "replaceRequiredExpression failed for app ${appId}", e)
            def errResp = _rmBuildUpdateErrorResponse(appId, e.message, backup, "STPage")
            // wizardStuck (+ the cancelCapab restoreHint) still rides through
            // _rmBuildUpdateErrorResponse for a mid-walk wizard-cleanup failure -- parity
            // with the addRequiredExpression branch. Only the misleading rollback hint
            // on the pure pre-delete validation throw is replaced.
            if (errResp?.wizardStuck != true) {
                errResp.restoreHint = "No changes were made -- the Required Expression is intact (the spec was validated before the destructive delete). No rollback is needed."
            }
            return errResp
        }
        // The helper owns the full envelope -- success (finalized in-window), pre-delete
        // refusal (requiredExpressionMissing / silent-delete-reject, RE intact), and
        // post-delete failure (deleted-then-auto-restored or could-not-restore, carrying
        // requiredExpressionRestored). Spread-forward so a future helper key is never
        // silently dropped; the dispatcher overrides only the keys it owns (appId/backup)
        // and ORs partial so an inner partial is never masked.
        return (replResult ?: [:]) + [appId: appId, backup: backup, partial: replResult?.partial == true]
    }

    if (addTriggersList || addActionsList) {
        // Bulk path: add many triggers + many actions in one tool call,
        // then fire updateRule once at the end. This is the efficient
        // shape for callers who already know all the triggers/actions
        // they want — collapses N tool calls into 1.
        // Trailing-updateRule failure propagation: when the post-bulk
        // updateRule click is rejected, the per-item adds landed but the
        // rule never re-subscribes. The catch in the trailing-updateRule
        // block below sets these so the response surfaces dedicated slots
        // (sibling pattern from F1 in the `patches` dispatcher branch and F2
        // in the `addLocalVariableSpec` dispatcher branch -- both apply the
        // same trailing-updateRule failure-propagation shape).
        // subscriptionsNotLive captures the trigger-side
        // consequence (actions self-bake via doActPage->selectActions, so
        // the trailing updateRule's only effect is subscription re-init).
        def triggerResults = []
        def actionResults = []
        def updateRuleFailed = false
        def subscriptionsNotLive = false
        def updateRuleError = null
        try {
            (addTriggersList ?: []).eachWithIndex { spec, i ->
                if (!(spec instanceof Map)) {
                    triggerResults << [success: false, error: "addTriggers[${i}] is not a Map", spec: spec]
                    return
                }
                try { triggerResults << _rmAddTrigger(appId, spec as Map) }
                catch (Exception te) {
                    triggerResults << [success: false, error: te.message, specCapability: spec.capability]
                    mcpLog("warn", "rm-native", "hub_set_rule: addTriggers[${i}] (${spec.capability}) failed -- ${te.message}")
                }
            }
            (addActionsList ?: []).eachWithIndex { spec, i ->
                if (!(spec instanceof Map)) {
                    actionResults << [success: false, error: "addActions[${i}] is not a Map", spec: spec]
                    return
                }
                try { actionResults << _rmAddAction(appId, spec as Map, true) }
                catch (Exception ae) {
                    actionResults << [success: false, error: ae.message, specCapability: spec.capability, specAction: spec.action]
                    mcpLog("warn", "rm-native", "hub_set_rule: addActions[${i}] (${spec.capability}/${spec.action}) failed -- ${ae.message}")
                }
            }
        } catch (Exception e) {
            mcpLogError("rm-native", "addTriggers/addActions bulk failed for app ${appId}", e)
            def bulkResult = _rmBuildUpdateErrorResponse(appId, e.message, backup)
            bulkResult.triggerResults = triggerResults
            bulkResult.actionResults = actionResults
            return bulkResult
        }
        // Trailing updateRule fires AFTER per-item adds complete. Hoisted out
        // of the per-item try so a rejection here doesn't get routed through
        // the generic "bulk path errored partway" shape -- per-item state IS
        // committed and callers need the dedicated failure slots to detect
        // the subscriptions-not-live consequence without log-grep. Each
        // _rmAddAction self-bakes its own action via the doActPage->
        // selectActions navigation, so this trailing click is just for the
        // final re-init (mirrors the UI's top-level "Update Rule" / "Done"
        // press, which populates eventSubscriptions from the trigger rows).
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
            repairHints << "updateRule click was rejected after the bulk adds committed. The trigger/action rows are baked but the rule will not subscribe to its device events until updateRule fires. Retry hub_set_rule(button='updateRule', confirm=true), or restore via backup if the retry also fails."
        }
        // Bubble per-item partial:true (silent_rejection / verification_fetch_failed /
        // hubRenderError on inner field writes) up through the outer envelope. trigOk
        // and actOk count success!=false, so a success:true + partial:true row inflates
        // the count and the size-equality check alone misses the inner-partial signal.
        // Sibling pattern: action-mutation dispatcher's itemsPartial and patches
        // dispatcher's partial OR-clause both use `any { ... partial == true }`.
        def itemsPartial = (trigOk != triggerResults.size()) || (actOk != actionResults.size()) ||
            triggerResults.any { it instanceof Map && it.partial == true } ||
            actionResults.any { it instanceof Map && it.partial == true }
        // Inner-only partial hint (bulk inner items reported partial BUT the trailing
        // updateRule click landed clean). Without this hint the outer envelope returned
        // partial:true + repairHints:[] (when updateRuleFailed is false) and the caller
        // had to drill into triggers[]/actions[] to discover why -- same C2 antipattern
        // the rest of this PR has been closing on the *NotLive flags. Sibling pattern
        // from the action-mutation and modifyTrigger dispatchers' inner-only branches.
        if (itemsPartial && !updateRuleFailed) {
            repairHints << "One or more bulk trigger/action items reported partial. Drill into triggers[] and actions[] for per-item settingsSkipped + repairHints. Wait 5s and retry the affected items, or use removeAction/removeTrigger to clean up and re-add."
        }
        return [
            success: trigOk == triggerResults.size() && actOk == actionResults.size() && health.ok && !updateRuleFailed,
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
            // Detect settings whose key isn't in the current page's schema.
            // The hub silently drops writes that lack a `<key>.type` sidecar
            // (we can only emit .type when the schema knows the input type),
            // so callers who include a setting that isn't yet on the page
            // would see success=true but the value never gets saved. This is
            // the top source of "why isn't my trigger working" on the RM
            // wizards: tstate1/AlltDev1 etc. only appear AFTER tCapab1/tDev1
            // are written, so bundling them drops tstate1. Pre-split the map
            // into known vs unknown and write only the known ones; surface
            // the unknown set loudly so the caller sees what was skipped.
            def knownSettings = [:]
            def unknownSettings = []
            settingsMap.each { k, v ->
                if (schema?.containsKey(k.toString())) {
                    knownSettings[k] = v
                } else {
                    unknownSettings << k.toString()
                }
            }
            if (knownSettings) {
                _rmUpdateAppSettings(appId, knownSettings, schema)
            }
            // Auto-fire updateRule only for main-page writes. On sub-pages
            // (selectTriggers, selectActions, trigger/action/condition
            // editors, etc.) the commit pattern is to click the page's
            // own Done button — for RM 5.1 triggers that's `hasAll`; for
            // actions it's `actionDone`; other wizard editors follow
            // the same "Done on sub-page, updateRule at the end" shape.
            // Firing updateRule mid-wizard clears stateAttribute flags
            // (moreCond, editCond, editAct, ...) and resets the editor,
            // so any subsequent sub-page write lands in the wrong state.
            // Callers orchestrating a sub-page flow should: write their
            // page-specific settings with pageName=<sub>, click the
            // sub-page Done button, then call hub_set_rule once
            // more with button='updateRule' to re-initialize the rule.
            def isMainPage = (!pageName || pageName == "mainPage")
            // Resolve the per-app-type commit button (default "updateRule";
            // null for submitOnChange apps like Basic Rule, whose inputs have
            // already committed -- clicking updateRule poisons their render).
            def editCommitButton = _resolveCommitButton(config?.app?.appType?.name)
            if (!button && isMainPage && knownSettings && editCommitButton) {
                _rmClickAppButton(appId, editCommitButton)
                implicitCommitButton = editCommitButton
            }
            result.settingsApplied = knownSettings.keySet().toList()
            if (unknownSettings) {
                result.settingsSkipped = unknownSettings
                def settingWord = (unknownSettings.size() == 1) ? "Setting" : "Settings"
                def beVerb = (unknownSettings.size() == 1) ? "is" : "are"
                result.unknownSettingsWarning = "${settingWord} ${unknownSettings} ${beVerb} not in the current page schema (pageName='${pageName ?: 'mainPage'}') and would have been silently dropped by the hub. Common cause on RM wizards: schema inputs are incremental -- e.g. on selectTriggers, tstate1 only appears AFTER tCapab1+tDev1 are written, so bundling them into one call drops tstate1. Fix: split into sequential hub_set_rule calls, one precondition per call."
            }
            if (!isMainPage) {
                result.subPageNote = "Sub-page write (pageName='${pageName}') — updateRule NOT auto-fired so the editor state survives. Finish the wizard and call hub_set_rule(button='updateRule') to commit."
            }
        }

        if (button) {
            _rmClickAppButton(appId, button, args?.stateAttribute?.toString(), pageName)
            result.buttonClicked = button

            // Wizard-Done finalize. Verified live on firmware 2.5.0.123 that
            // the first hasAll click on the selectTriggers wizard DOES commit
            // the trigger to the rule's summary row, but leaves a single
            // residual `isCondTrig.<N>` input ("Conditional Trigger?") on the
            // page asking the user to decide whether the just-committed
            // trigger is conditional. The earlier auto-retry hack of clicking
            // hasAll again ALSO closed that prompt, but at the cost of
            // inadvertently allocating a phantom trigger N+1 (the second
            // hasAll fires moreCond and creates a "**Broken Trigger**" row
            // for an empty next-trigger slot — visible in subsequent
            // selectTriggers fetches). Correct cleanup is to *write*
            // `isCondTrig.<N>=false` instead, which closes the prompt
            // without consuming a trigger index. After this fix:
            //   - 1 hasAll click commits the trigger
            //   - Residual isCondTrig.<N> gets auto-finalized to false
            //   - Editor closes, no phantom trigger
            // Multi-trigger flows now use sequential trigger
            // indices 1, 2, 3 instead of 1, 3, 5.
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
            result.restoreHint = "Call hub_restore_backup with backupKey='${backup.backupKey}' to roll back."
        }
        result.configPageError = err

        // Post-updateRule subscription-settling check. After clicking the
        // main-page updateRule button (either explicit via button param
        // or implicit after a main-page settings write), RM's initialize()
        // should have repopulated eventSubscriptions for any non-HTTP,
        // non-time trigger the rule carries. Verified live on firmware
        // 2.5.0.123 that this sometimes lags: the first updateRule after
        // a fresh trigger-wizard hasAll returns success but leaves subs=0
        // for ~minute; a second updateRule click (often issued after any
        // other activity) populates them. Surface the lag as a structured
        // warning + autoretry so callers don't silently ship rules that
        // never fire.
        def clickedUpdateRule = (button == "updateRule") || (implicitCommitButton == "updateRule")
        if (clickedUpdateRule) {
            def settleStatus = _rmCheckSubscriptionSettle(appId)
            if (settleStatus?.unsettled) {
                mcpLog("info", "rm-native", "updateRule subscription settle lag on app ${appId} -- retrying")
                _rmClickAppButton(appId, "updateRule")
                settleStatus = _rmCheckSubscriptionSettle(appId)
                def trigCount = settleStatus.triggerCount
                def trigWord = trigCount == 1 ? "trigger" : "triggers"
                // Discriminate on count, not on stringified word: trigWord=="trigger"
                // could in theory drift if the assignment above changed, and at count==0
                // ("triggers", plural by default) the "triggers are" verb is correct anyway.
                def trigVerb = (trigCount == 1) ? "trigger is" : "triggers are"
                result.subscriptionSettle = settleStatus?.unsettled ?
                    "WARN: rule has ${trigCount} ${trigWord} but eventSubscriptions=0 after two updateRule clicks. The ${trigVerb} likely incomplete (missing tstate, attached-condition, or other required field) OR a hub timing race. Inspect statusJson.eventSubscriptions; if still empty, call hub_set_rule(button='updateRule') again or check the wizard for missing fields." :
                    "OK after auto-retry"
            } else if (settleStatus != null) {
                result.subscriptionSettle = "OK"
            }
        }
        // Always attach health to settings/button mutations too — the LLM
        // sees broken state immediately without having to call hub_get_rule_health.
        result.health = _rmCheckRuleHealth(appId)
        if (!result.health.ok) result.success = false
        // Final commit: click the mainPage Done button. Mirrors the live UI's
        // last-step behavior on every modify session. Without it, in-flight
        // state markers (state.editAct, state.editCond, etc.) can linger and
        // cause subsequent edits to misbehave. A miss is surfaced (not just
        // warn-logged): for commitButton:null app types this Done is the
        // session's ONLY lifecycle event.
        def editDone = null
        try { editDone = _rmSubmitMainPageDone(appId) }
        catch (Exception doneExc) { mcpLog("warn", "rm-native", "hub_set_rule: trailing mainPage Done click failed for app ${appId}: ${doneExc.message} -- in-flight state markers may linger and corrupt subsequent edits") }
        if (editDone?.done == false) {
            result.mainPageDoneFailed = true
            result.mainPageDoneError = editDone.reason
            // Flip success ONLY for commitButton:null app types (Basic Rule, Button Controller),
            // where this Done is the edit's ONLY commit channel -- a miss means nothing committed.
            // For RM-family apps the edit already committed through another channel (a button click
            // like pausRule via /installedapp/btn, or a main-page settings write's auto-updateRule),
            // so a missed trailing Done is a state-marker-cleanup caveat (surfaced via the
            // repairHint), NOT a failure -- flipping success there would be a false negative for a
            // completed action. (finalConfig.app.appType.name is live-confirmed populated on the
            // base configure/json endpoint; _resolveCommitButton fails safe to non-null on a
            // degraded/absent name, i.e. toward NOT flipping.)
            if (_resolveCommitButton(finalConfig?.app?.appType?.name?.toString()) == null) {
                result.success = false
            }
            result.repairHints = (result.repairHints ?: []) + ["The session-end mainPage Done click did not commit (${editDone.reason}). Settings are already written, but the app's update lifecycle did not run -- verify via hub_get_app_config(appId=${appId}) and re-commit via hub_set_native_app(appId=${appId}, button='updateRule') for RM-family apps.".toString()]
        }
        return result
    } catch (Exception e) {
        def msg = e.message ?: e.toString()
        mcpLogError("rm-native", "hub_set_rule failed for ${appId}: ${msg}", e)
        return _rmBuildUpdateErrorResponse(appId, msg, backup)
    }
}

// hub_delete_native_app — always snapshots the app first, then deletes. Default
// mode is soft delete (hub refuses if the app has child apps or devices).
// force=true routes to forcedelete/quiet — the same path RM itself uses.
//
// Works on any classic SmartApp instance: RM rules, Room Lighting,
// Button Controllers, Basic Rules, Notifier, etc.
def toolDeleteNativeApp(args) {
    requireDestructiveConfirm(args?.confirm as Boolean)
    if (args?.appId == null) throw new IllegalArgumentException("appId is required")
    def appId = normalizeRuleId(args.appId)
    def force = args?.force == true

    def backup = _rmBackupRuleSnapshot(appId, force ? "pre-forcedelete" : "pre-delete")

    try {
        if (force) {
            _rmForceDeleteApp(appId)
            return [
                success: true,
                appId: appId,
                mode: "forcedelete",
                backup: backup,
                note: "App force-deleted. To restore, call hub_restore_backup with backupKey='${backup.backupKey}' (will recreate an empty app and apply saved settings)."
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
    return _rmCheckRuleHealth(appId, source)
}

// hub_list_rule_local_variables -- read a rule's local-variable namespace
// (state.allLocalVars) from its statusJson appState. Pure read; no wizard.
// Returns {appId, localVariables:[{name, type, value}], total}. The statusJson
// allLocalVars map is {name: {type, value}}; an absent map means the rule has
// no locals (returns an empty list, not an error).
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
        localVariables << [
            name: lvName?.toString(),
            type: (lvMeta instanceof Map ? lvMeta?.type?.toString() : null),
            value: (lvMeta instanceof Map ? lvMeta?.value : null)
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
        // hub_list_rule_local_variables reads state.allLocalVars only.
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
        hub_call_rule: [title: "Trigger Rule Machine Rule", summary: "Trigger a Rule Machine rule, run its actions, or stop it."],
        hub_set_rule_paused: [title: "Pause or Resume Rule", summary: "Pause or resume a Rule Machine rule."],
        hub_set_rule_private_boolean: [title: "Set Rule Private Boolean", summary: "Set a Rule Machine rule's private boolean."],
        hub_set_rule: [title: "Author Rule Machine Rule", summary: "Create or edit a Rule Machine rule."],
        hub_get_rule_health: [title: "Get Rule Health", summary: "Read-only health check on any installed app."],
        hub_list_rule_local_variables: [title: "List Rule Local Variables", summary: "Read-only list of a Rule Machine rule's local variables."],
        hub_set_native_app: [title: "Create or Edit Native App", summary: "Create or edit a classic native app (Room Lighting, Notifier, etc.)."],
        hub_delete_native_app: [title: "Delete Native App", summary: "Delete a classic native app (auto-snapshot first)."],
        hub_set_app_disabled: [title: "Enable or Disable App", summary: "Enable or disable any installed app without deleting it (reversible)."]
    ]
}
