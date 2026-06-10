library(name: "McpVisualRulesLib", namespace: "mcp", author: "kingpanther13", description: "Visual Rules Builder tool implementations for the MCP Rule Server (hub_get_visual_rule/hub_set_visual_rule/hub_delete_visual_rule); included by the main app. Gateway entries and dispatch stay in the app; tool definitions live here alongside the impl.")

private Map _vrbAppInfo(Integer appId) {
    // GET /installedapp/json/<id> -> {id, name, type, disabled, user} for any installed app.
    // Returns null when the app doesn't exist or the response isn't the expected shape, so
    // callers can use it both as an existence check and a type check.
    try {
        def text = hubInternalGet("/installedapp/json/${appId}")
        if (!text) return null
        def parsed = new groovy.json.JsonSlurper().parseText(text)
        return (parsed instanceof Map && parsed.id != null) ? parsed : null
    } catch (Exception e) {
        return null
    }
}

private Map _vrbFetchGraph(Integer appId) {
    // GET /app/ruleBuilder20Json/<id> -> {name, rulePaused, ruleJson, validationErrors} for a
    // graph-format (VRB 2.0 editor) rule. The endpoint answers {success:false, message:...} for
    // EVERY other id -- nonexistent, RM rule, classic-format VRB rule -- with no distinction, so
    // a null return only means "not a graph rule", not "no such app".
    def text = hubInternalGet("/app/ruleBuilder20Json/${appId}")
    if (!text) return null
    def parsed
    try {
        parsed = new groovy.json.JsonSlurper().parseText(text)
    } catch (Exception e) {
        return null
    }
    if (!(parsed instanceof Map) || parsed.success == false) return null
    def out = [name: parsed.name, rulePaused: parsed.rulePaused == true,
               validationErrors: parsed.validationErrors ?: [], ruleJson: parsed.ruleJson]
    // ruleJson is a STRING on the wire (double-encoded graph). Parse it for the tool response;
    // blank means a freshly-created empty rule.
    def raw = parsed.ruleJson?.toString()
    if (raw?.trim()) {
        try {
            out.definition = new groovy.json.JsonSlurper().parseText(raw)
        } catch (Exception e) {
            out.definitionParseError = "ruleJson did not parse as JSON: ${e.message}"
        }
    }
    return out
}

private Map _vrbFetchClassic(Integer appId) {
    // GET /app/ruleBuilderJson/<id>. CAUTION: this endpoint serializes the raw state of ANY
    // installed app (and returns {} for nonexistent ids) -- only the whenNodes+thenNodes shape
    // proves the app is a classic-format Visual Rule. Never surface a non-matching body.
    def text = hubInternalGet("/app/ruleBuilderJson/${appId}")
    if (!text) return null
    def parsed
    try {
        parsed = new groovy.json.JsonSlurper().parseText(text)
    } catch (Exception e) {
        return null
    }
    if (!(parsed instanceof Map)) return null
    if (!parsed.containsKey("whenNodes") || !parsed.containsKey("thenNodes")) return null
    return [name: parsed.name, rulePaused: parsed.rulePaused == true,
            whenNodes: parsed.whenNodes ?: [], thenNodes: parsed.thenNodes ?: [],
            elseNodes: parsed.elseNodes ?: [], promptHistory: parsed.promptHistory ?: []]
}

private Map _vrbDetect(Integer appId) {
    // Resolve which serialization a VRB rule speaks: graph (2.0 editor, /app/ruleBuilder20Json)
    // or classic (when/then/else editor, /app/ruleBuilderJson). Null = neither (not a VRB rule).
    def graph = _vrbFetchGraph(appId)
    if (graph != null) return [format: "graph", data: graph]
    def classic = _vrbFetchClassic(appId)
    if (classic != null) return [format: "classic", data: classic]
    return null
}

private List _vrbListRules() {
    // Walk the /hub2/appsList installed-app tree and collect the children of the
    // "Visual Rules Builder" parent. Throws IllegalStateException when the parent app is not
    // installed so the caller can return an actionable note.
    def text = hubInternalGet("/hub2/appsList")
    if (!text) throw new IllegalStateException("Empty response from /hub2/appsList")
    def parsed = new groovy.json.JsonSlurper().parseText(text)
    def parent = (parsed?.apps ?: []).find { it?.data?.type == "Visual Rules Builder" }
    if (parent == null) {
        throw new IllegalStateException("The Visual Rules Builder parent app is not installed on this hub. Install it via Apps -> Add Built-In App -> Visual Rules Builder, then retry.")
    }
    return (parent.children ?: []).findAll { it?.data?.id != null }.collect {
        [appId: it.data.id, name: it.data.name, disabled: it.data.disabled == true]
    }
}

private Map _vrbCreateChild() {
    // GET /app/createVisualRuleBuilderRule server-creates a new VRB child and returns (or
    // redirects to) the builder page; the new appId travels ONLY as an injected window global:
    // HubitatRuleBuilder20AppId (graph editor) or HubitatRuleBuilderAppId (classic editor).
    // Which global the firmware injects tells us the native format of new rules on this hub.
    def resp = hubInternalGetRaw("/app/createVisualRuleBuilderRule")
    def html = resp?.data?.toString()
    if (!html && resp?.location) {
        def loc = resp.location.toString()
        def absolute = loc =~ /^https?:\/\/[^\/]+(\/.*)$/
        if (absolute.find()) loc = absolute.group(1)
        html = hubInternalGet(loc)
    }
    if (!html) {
        throw new IllegalStateException("createVisualRuleBuilderRule returned no page body (status=${resp?.status}). Cannot determine the new rule's appId.")
    }
    def m20 = html =~ /HubitatRuleBuilder20AppId\s*=\s*(\d+)/
    if (m20.find()) return [appId: m20.group(1) as Integer, format: "graph"]
    def m11 = html =~ /HubitatRuleBuilderAppId\s*=\s*(\d+)/
    if (m11.find()) return [appId: m11.group(1) as Integer, format: "classic"]
    throw new IllegalStateException("createVisualRuleBuilderRule page did not contain a HubitatRuleBuilderAppId / HubitatRuleBuilder20AppId global (firmware shape change?). First 300 chars: ${html.take(300)}")
}

private Map _vrbSaveGraph(Integer appId, String name, String definitionJson) {
    // POST /app/ruleBuilder20Json/<id> with {name, ruleJson} where ruleJson is the graph as a
    // JSON STRING (double-encoded -- sending a nested object is the classic wire mistake here).
    // Response: {success?, name, ruleJson, validationErrors, errorMessage}; absent success means
    // saved. A save with non-empty validationErrors still persists (UI parity).
    def body = groovy.json.JsonOutput.toJson([name: name, ruleJson: definitionJson])
    def resp = hubInternalPostJson("/app/ruleBuilder20Json/${appId}", body)
    if (resp instanceof Map && resp.success == false) {
        return [success: false, errorMessage: resp.errorMessage ?: "hub rejected the save",
                validationErrors: resp.validationErrors ?: []]
    }
    return [success: true, validationErrors: (resp instanceof Map ? (resp.validationErrors ?: []) : [])]
}

private void _vrbSaveClassic(Integer appId, String name, Boolean rulePaused, Map definition) {
    // POST /app/ruleBuilderJson/<id> with {name, rulePaused, whenNodes, thenNodes, elseNodes}
    // (real arrays, NOT double-encoded). The hub returns no useful body for this POST -- the
    // builder UI ignores it -- so callers must verify via a read-back.
    def body = groovy.json.JsonOutput.toJson([
        name: name,
        rulePaused: rulePaused == true,
        whenNodes: definition.whenNodes ?: [],
        thenNodes: definition.thenNodes ?: [],
        elseNodes: definition.elseNodes ?: []
    ])
    hubInternalPostJson("/app/ruleBuilderJson/${appId}", body)
}

private Map _vrbSetPaused(Integer appId, boolean paused) {
    // GET /app/ruleBuilderPause/<id>/<true|false> -> {success}. The boolean rides in the path.
    def text = hubInternalGet("/app/ruleBuilderPause/${appId}/${paused}")
    try {
        def parsed = text ? new groovy.json.JsonSlurper().parseText(text) : null
        return [success: !(parsed instanceof Map) || parsed.success != false]
    } catch (Exception e) {
        return [success: false, error: "pause endpoint returned a non-JSON response: ${text?.take(200)}"]
    }
}

private void _vrbForceDelete(Integer appId) {
    // Standard force-delete path -- the same one the builder UIs use.
    hubInternalGetRaw("/installedapp/forcedelete/${appId}/quiet")
}

private String _vrbDetectDefinitionFormat(Map definition) {
    def looksGraph = definition.containsKey("nodes") || definition.containsKey("edges")
    def looksClassic = definition.containsKey("whenNodes") || definition.containsKey("thenNodes") || definition.containsKey("elseNodes")
    if (looksGraph && looksClassic) {
        throw new IllegalArgumentException("definition mixes graph keys (nodes/edges) with classic keys (whenNodes/thenNodes/elseNodes) -- supply exactly one format. See hub_get_tool_guide(section='visual_rule_reference').")
    }
    if (looksGraph) return "graph"
    if (looksClassic) return "classic"
    throw new IllegalArgumentException("definition must be either a graph ({version, nodes, edges}) or a classic node-list ({whenNodes, thenNodes, elseNodes}). See hub_get_tool_guide(section='visual_rule_reference') for both schemas.")
}

private Map _vrbNormalizeDefinition(def rawDefinition) {
    // Accept the definition as a Map (the normal MCP argument shape) or a JSON string.
    // Returns [map: Map, format: "graph"|"classic"].
    def map
    if (rawDefinition instanceof Map) {
        map = rawDefinition
    } else if (rawDefinition instanceof CharSequence) {
        try {
            def parsed = new groovy.json.JsonSlurper().parseText(rawDefinition.toString())
            if (!(parsed instanceof Map)) throw new IllegalArgumentException("definition string must encode a JSON object")
            map = parsed
        } catch (IllegalArgumentException iae) {
            throw iae
        } catch (Exception e) {
            throw new IllegalArgumentException("definition is not valid JSON: ${e.message}")
        }
    } else {
        throw new IllegalArgumentException("definition must be a JSON object (or a JSON-encoded string of one)")
    }
    return [map: map, format: _vrbDetectDefinitionFormat(map)]
}

private Map _vrbNotVisualRuleError(Integer appId) {
    // Shared error envelope for "this appId isn't a Visual Rules Builder rule", enriched with
    // the app's real type when it exists so the model can route to the right tool.
    def info = _vrbAppInfo(appId)
    if (info == null) {
        return [success: false, appId: appId,
                error: "No installed app with appId ${appId} was found.",
                note: "Call hub_get_visual_rule with no appId to list Visual Rules Builder rules, or hub_list_rules for Rule Machine rules."]
    }
    return [success: false, appId: appId, appName: info.name, appType: info.type,
            error: "App ${appId} ('${info.name}') is type '${info.type}', not a Visual Rules Builder rule.",
            note: info.type?.toString()?.startsWith("Rule") ?
                "For Rule Machine rules use hub_set_rule / hub_delete_native_app instead." :
                "Use hub_set_native_app / hub_delete_native_app for classic apps."]
}

def toolGetVisualRule(args) {
    // Read-only. No appId -> list every Visual Rules Builder rule (id, name, disabled).
    // With appId -> full definition in whichever serialization the rule speaks.
    if (args?.appId == null) {
        try {
            def rules = _vrbListRules()
            return [success: true, count: rules.size(), rules: rules,
                    note: rules ? "Pass appId to hub_get_visual_rule for a rule's full definition." :
                                  "No Visual Rules Builder rules exist yet. Create one with hub_set_visual_rule."]
        } catch (IllegalStateException ise) {
            return [success: false, error: ise.message]
        } catch (Exception e) {
            mcpLog("warn", "vrb", "hub_get_visual_rule list failed: ${e.message}")
            return [success: false, error: "Could not list Visual Rules Builder rules: ${e.message}",
                    note: "Hub internal API unavailable. This may require Hub Security credentials or a firmware update."]
        }
    }
    def appId = normalizeRuleId(args.appId)
    try {
        def detected = _vrbDetect(appId)
        if (detected == null) return _vrbNotVisualRuleError(appId)
        def out = [success: true, appId: appId, format: detected.format] + detected.data
        if (detected.format == "graph" && out.definition == null && !out.definitionParseError) {
            out.note = "This graph rule has an empty definition (freshly created, never saved)."
        }
        return out
    } catch (Exception e) {
        mcpLog("warn", "vrb", "hub_get_visual_rule failed for ${appId}: ${e.message}")
        return [success: false, appId: appId, error: "Could not read Visual Rule ${appId}: ${e.message}"]
    }
}

def toolSetVisualRule(args) {
    requireDestructiveConfirm(args?.confirm as Boolean)
    def name = args?.name?.toString()?.trim()
    def hasDefinition = args?.definition != null
    def hasPaused = args?.paused != null
    def paused = args?.paused == true

    if (args?.appId == null) {
        // CREATE: the hub creates the child (and picks the serialization format); we then save
        // the caller's definition into it. Both name and definition are required so no unnamed
        // empty shells are left behind.
        if (!name) throw new IllegalArgumentException("name is required when creating a Visual Rule (appId omitted).")
        if (!hasDefinition) throw new IllegalArgumentException("definition is required when creating a Visual Rule. See hub_get_tool_guide(section='visual_rule_reference') for the schema.")
        def normalized = _vrbNormalizeDefinition(args.definition)
        def created
        try {
            created = _vrbCreateChild()
        } catch (Exception e) {
            mcpLog("error", "vrb", "hub_set_visual_rule create failed: ${e.message}")
            return [success: false, error: "Creating the Visual Rule child app failed: ${e.message}"]
        }
        if (normalized.format != created.format) {
            // The supplied definition doesn't match the serialization this firmware's builder
            // creates. Delete the orphan shell rather than stranding an empty unnamed rule.
            _vrbForceDelete(created.appId)
            return [success: false, hubNativeFormat: created.format,
                    error: "This hub's Visual Rules Builder creates ${created.format}-format rules, but the definition is ${normalized.format}-format.",
                    note: "Re-call hub_set_visual_rule with a ${created.format} definition -- see hub_get_tool_guide(section='visual_rule_reference'). The empty child app created during this attempt was cleaned up."]
        }
        try {
            return _vrbApplySave(created.appId, created.format, name, normalized.map, hasPaused ? paused : null, false, true)
        } catch (Exception e) {
            _vrbForceDelete(created.appId)
            mcpLog("error", "vrb", "hub_set_visual_rule save-after-create failed: ${e.message}")
            return [success: false, error: "Saving the new Visual Rule failed: ${e.message}",
                    note: "The empty child app created during this attempt was cleaned up."]
        }
    }

    // EDIT / PAUSE: appId given. At least one mutation must be requested.
    if (!hasDefinition && !name && !hasPaused) {
        throw new IllegalArgumentException("Nothing to change: provide definition (full replacement), name (rename), and/or paused (pause/resume) alongside appId.")
    }
    def appId = normalizeRuleId(args.appId)
    def detected = _vrbDetect(appId)
    if (detected == null) return _vrbNotVisualRuleError(appId)

    if (hasDefinition) {
        def normalized = _vrbNormalizeDefinition(args.definition)
        if (normalized.format != detected.format) {
            return [success: false, appId: appId, format: detected.format,
                    error: "Rule ${appId} is ${detected.format}-format but the definition is ${normalized.format}-format.",
                    note: "Fetch the current shape with hub_get_visual_rule(appId=${appId}) and supply a ${detected.format} definition, or delete and recreate the rule."]
        }
        try {
            def result = _vrbApplySave(appId, detected.format, name ?: detected.data.name?.toString(), normalized.map, hasPaused ? paused : null, detected.data.rulePaused == true, false)
            result.previousDefinition = detected.format == "graph" ? detected.data.definition :
                    [whenNodes: detected.data.whenNodes, thenNodes: detected.data.thenNodes, elseNodes: detected.data.elseNodes]
            return result
        } catch (Exception e) {
            mcpLog("error", "vrb", "hub_set_visual_rule edit failed for ${appId}: ${e.message}")
            return [success: false, appId: appId, error: "Saving Visual Rule ${appId} failed: ${e.message}"]
        }
    }

    // Rename and/or pause without replacing the definition: re-save the EXISTING nodes under
    // the new name (the save endpoints have no rename-only verb), then apply the pause flag.
    try {
        if (name && name != detected.data.name?.toString()) {
            // A never-saved graph rule reads back a blank ruleJson; the builder UI would save
            // the default empty template in that case, so mirror it rather than POSTing "".
            def emptyTemplate = '{"version":1,"nodes":[{"id":"trigger-1","type":"trigger","triggerCondition":"trigger","triggerType":"sampleTrigger","deviceIds":[]},{"id":"action-1","type":"action","actionType":"sample","deviceIds":[]}],"edges":[{"from":"trigger-1","to":"action-1","port":"next"}]}'
            def existing = detected.format == "graph" ?
                    (detected.data.ruleJson?.toString()?.trim() ?: emptyTemplate) :
                    [whenNodes: detected.data.whenNodes, thenNodes: detected.data.thenNodes, elseNodes: detected.data.elseNodes]
            if (detected.format == "graph") {
                def saved = _vrbSaveGraph(appId, name, existing)
                if (saved.success == false) return [success: false, appId: appId, error: "Rename failed: ${saved.errorMessage}", validationErrors: saved.validationErrors]
            } else {
                _vrbSaveClassic(appId, name, hasPaused ? paused : detected.data.rulePaused == true, existing)
            }
        }
        if (hasPaused) {
            def pauseResult = _vrbSetPaused(appId, paused)
            if (pauseResult.success == false) {
                return [success: false, appId: appId, error: "Pause/resume failed", note: pauseResult.error]
            }
        }
        def after = _vrbDetect(appId)
        return [success: true, appId: appId, format: detected.format,
                name: after?.data?.name, rulePaused: after?.data?.rulePaused == true]
    } catch (Exception e) {
        mcpLog("error", "vrb", "hub_set_visual_rule rename/pause failed for ${appId}: ${e.message}")
        return [success: false, appId: appId, error: "Updating Visual Rule ${appId} failed: ${e.message}"]
    }
}

private Map _vrbApplySave(Integer appId, String format, String name, Map definition, Boolean pausedRequested, Boolean currentPaused, boolean created) {
    // Shared save + pause + read-back-verify tail for create and full-replacement edits.
    // pausedRequested is null when the caller didn't ask for a pause change; the classic POST
    // must then carry the rule's CURRENT paused state (the body always includes rulePaused).
    def validationErrors = []
    if (format == "graph") {
        def definitionJson = groovy.json.JsonOutput.toJson(definition)
        def saved = _vrbSaveGraph(appId, name, definitionJson)
        if (saved.success == false) {
            if (created) _vrbForceDelete(appId)
            return [success: false, error: "Hub rejected the graph save: ${saved.errorMessage}",
                    validationErrors: saved.validationErrors,
                    note: created ? "The empty child app created during this attempt was cleaned up." :
                                    "The rule's previous definition is untouched."]
        }
        validationErrors = saved.validationErrors ?: []
        if (pausedRequested != null) {
            // The graph POST carries no rulePaused field; pause state has its own endpoint.
            // A pause failure is surfaced through the read-back check below (verified covers
            // the requested pause state, not just the name).
            _vrbSetPaused(appId, pausedRequested)
        }
    } else {
        _vrbSaveClassic(appId, name, pausedRequested != null ? pausedRequested : (currentPaused == true), definition)
    }
    def after = _vrbDetect(appId)
    def verified = after != null && after.data.name?.toString() == name &&
            (pausedRequested == null || (after.data.rulePaused == true) == pausedRequested)
    def out = [success: verified, appId: appId, format: format, created: created,
               name: after?.data?.name, rulePaused: after?.data?.rulePaused == true, verified: verified]
    if (validationErrors) {
        out.validationErrors = validationErrors
        out.note = "Saved, but the hub reported validation errors -- the rule may not run until they are fixed."
    }
    if (!verified) {
        out.error = "Save POST was sent but the read-back did not confirm the new state (read back name: ${after?.data?.name})."
        out.note = "Re-read with hub_get_visual_rule(appId=${appId}) to inspect what the hub persisted."
    } else if (after != null) {
        out.definition = format == "graph" ? after.data.definition :
                [whenNodes: after.data.whenNodes, thenNodes: after.data.thenNodes, elseNodes: after.data.elseNodes]
    }
    return out
}

def toolDeleteVisualRule(args) {
    requireDestructiveConfirm(args?.confirm as Boolean)
    if (args?.appId == null) throw new IllegalArgumentException("appId is required (find it with hub_get_visual_rule).")
    def appId = normalizeRuleId(args.appId)
    // Type-gate before deleting: forcedelete removes ANY installed app, so only proceed once
    // the id provably speaks a VRB serialization.
    def detected = _vrbDetect(appId)
    if (detected == null) return _vrbNotVisualRuleError(appId)
    def predelete = detected.format == "graph" ? detected.data.definition :
            [whenNodes: detected.data.whenNodes, thenNodes: detected.data.thenNodes, elseNodes: detected.data.elseNodes]
    try {
        _vrbForceDelete(appId)
    } catch (Exception e) {
        return [success: false, appId: appId, error: "Delete request failed: ${e.message}"]
    }
    def stillThere = _vrbAppInfo(appId) != null
    mcpLog("info", "vrb", "Deleted Visual Rule ${appId} ('${detected.data.name}') verified=${!stillThere}")
    return [success: !stillThere, appId: appId, name: detected.data.name, format: detected.format,
            verified: !stillThere, predeleteDefinition: predelete,
            note: stillThere ? "The hub still reports app ${appId} after the delete request -- re-check with hub_get_visual_rule." :
                               "To recreate this rule, call hub_set_visual_rule with the predeleteDefinition."]
}

// Tool DEFINITIONS for the Visual Rules Builder tools (issue #209 pattern: schema lives with
// the impl). Concatenated into getAllToolDefinitions() in the main app; gateway membership +
// dispatch stay in main.
def _getAllToolDefinitions_partVisualRules() {
    return [
        [
            name: "hub_get_visual_rule",
            description: "List Visual Rules Builder rules (omit appId) or read one rule's full JSON definition. Returns the rule's format: 'classic' ({whenNodes, thenNodes, elseNodes}) or 'graph' ({version, nodes, edges}); pass the same format back to hub_set_visual_rule when editing.[[FLAT_TRIM]] VRB rules are simple visual automations (similar tier to Basic Rules) stored as clean JSON -- much easier to author than Rule Machine. Node schemas: hub_get_tool_guide(section='visual_rule_reference').[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    appId: [type: "integer", description: "Visual Rule app id. Omit to list all VRB rules."]
                ]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean"],
                    rules: [type: "array", description: "List mode: [{appId, name, disabled}]"],
                    count: [type: "integer"],
                    appId: [type: "integer"],
                    format: [type: "string", description: "'classic' (whenNodes/thenNodes/elseNodes) or 'graph' (nodes/edges)"],
                    name: [type: "string"],
                    rulePaused: [type: "boolean"],
                    whenNodes: [type: "array", description: "classic format: trigger nodes"],
                    thenNodes: [type: "array", description: "classic format: action nodes"],
                    elseNodes: [type: "array", description: "classic format: else-branch action nodes"],
                    definition: [type: "object", description: "graph format: parsed {version, nodes, edges}"],
                    validationErrors: [type: "array"],
                    error: [type: "string"],
                    note: [type: "string"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_set_visual_rule",
            description: "Create or update a Visual Rules Builder rule -- the EASIEST native automation to author (one JSON write; no wizard). PREFER this for simple device automations (motion lights, contact alerts, schedules); fall back to hub_set_rule (Rule Machine) for branching logic, loops, variables, or arbitrary device commands. Omit appId to create (name + definition required). Pre-flight: backup within 24h + confirm=true. Schemas + worked example: hub_get_tool_guide(section='visual_rule_reference').[[FLAT_TRIM]] With appId: definition replaces wholesale, name renames, paused pauses/resumes; the definition format must match the rule's existing format (see hub_get_visual_rule).[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    appId: [type: "integer", description: "Existing Visual Rule app id to edit. Omit to create."],
                    name: [type: "string", description: "Rule name. Required on create; renames on edit."],
                    definition: [type: "object", description: "Full rule definition (wholesale replacement). Classic: {whenNodes, thenNodes, elseNodes}; graph: {version, nodes, edges}. Field schemas: hub_get_tool_guide(section='visual_rule_reference')."],
                    paused: [type: "boolean", description: "true=pause, false=resume. May be sent alone with appId."],
                    confirm: [type: "boolean", description: "REQUIRED: must be true (recent backup + user approval)."]
                ],
                required: ["confirm"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean"],
                    appId: [type: "integer", description: "The created or edited rule's app id"],
                    format: [type: "string", description: "'classic' or 'graph' -- the serialization this rule speaks"],
                    created: [type: "boolean"],
                    name: [type: "string"],
                    rulePaused: [type: "boolean"],
                    verified: [type: "boolean", description: "Whether a read-back confirmed the saved state"],
                    definition: [type: "object", description: "Read-back of what the hub persisted"],
                    previousDefinition: [type: "object", description: "The definition before a full replacement (recovery aid)"],
                    validationErrors: [type: "array", description: "Hub-side validation problems; the rule saved but may not run"],
                    hubNativeFormat: [type: "string"],
                    error: [type: "string"],
                    note: [type: "string"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_delete_visual_rule",
            description: "Delete a Visual Rules Builder rule by appId. Type-gated: refuses ids that are not VRB rules (use hub_delete_native_app for RM rules / other classic apps). Returns the pre-delete definition for recovery via hub_set_visual_rule. Pre-flight: backup within 24h + confirm=true.",
            inputSchema: [
                type: "object",
                properties: [
                    appId: [type: "integer", description: "The Visual Rule app id from hub_get_visual_rule."],
                    confirm: [type: "boolean", description: "REQUIRED: must be true (recent backup + user approval)."]
                ],
                required: ["appId", "confirm"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean"],
                    appId: [type: "integer"],
                    name: [type: "string"],
                    format: [type: "string"],
                    verified: [type: "boolean", description: "Whether the app was confirmed gone after the delete"],
                    predeleteDefinition: [type: "object", description: "The rule definition captured before deletion (recovery aid)"],
                    error: [type: "string"],
                    note: [type: "string"]
                ],
                required: ["success"]
            ]
        ]
    ]
}
