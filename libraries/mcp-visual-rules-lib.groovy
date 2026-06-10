library(name: "McpVisualRulesLib", namespace: "mcp", author: "kingpanther13", description: "Visual Rules Builder tool implementations for the MCP Rule Server (hub_get_visual_rule/hub_set_visual_rule/hub_delete_visual_rule); included by the main app. Gateway entries and dispatch stay in the app; tool definitions live here alongside the impl.")

private Map _vrbAppExistence(Integer appId) {
    // GET /installedapp/json/<id> -> {id, name, type, disabled, user} for any installed app.
    // Returns [state: "found", info: <map>] | [state: "absent"] | [state: "unknown", error: <msg>].
    // The three-way split matters: "absent" backs definitive claims ("no such app" errors,
    // delete verification), while a network error or an unparseable 200 (e.g. a login page)
    // must surface as "unknown" -- never fabricated certainty either way.
    def text
    try {
        text = hubInternalGet("/installedapp/json/${appId}")
    } catch (Exception e) {
        def status = null
        try { status = e.response?.status } catch (Exception ignored) { }
        if (status == 404) return [state: "absent"]
        return [state: "unknown", error: e.message]
    }
    if (!text) return [state: "absent"]
    try {
        def parsed = new groovy.json.JsonSlurper().parseText(text)
        if (parsed instanceof Map && parsed.id != null) return [state: "found", info: parsed]
        return [state: "absent"]
    } catch (Exception e) {
        return [state: "unknown", error: "unparseable response from /installedapp/json: ${text?.take(120)}"]
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
    // A null resp (empty / non-JSON 200 body) is treated as accepted, mirroring the UI's
    // success-unless-false check -- every caller confirms via a read-back comparison, which
    // is the real write verification for both save endpoints.
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
        if (parsed instanceof Map && parsed.success == false) {
            return [success: false, error: parsed.message ? "pause endpoint reported: ${parsed.message}" : "pause endpoint returned success=false"]
        }
        return [success: true]
    } catch (Exception e) {
        return [success: false, error: "pause endpoint returned a non-JSON response: ${text?.take(200)}"]
    }
}

private void _vrbForceDelete(Integer appId) {
    // Standard force-delete path -- the same one the builder UIs use.
    hubInternalGetRaw("/installedapp/forcedelete/${appId}/quiet")
}

private String _vrbTryCleanupShell(Integer appId) {
    // Best-effort removal of a just-created empty shell after a failed create. Never throws --
    // a cleanup failure must not mask the original error -- and always names the appId so a
    // surviving orphan can be deleted manually.
    try {
        _vrbForceDelete(appId)
        def existence = _vrbAppExistence(appId)
        if (existence.state == "absent") {
            return "The empty child app created during this attempt (appId ${appId}) was cleaned up."
        }
        if (existence.state == "found") {
            return "The empty child app created during this attempt (appId ${appId}) may still exist -- delete it with hub_delete_visual_rule(appId=${appId}, confirm=true)."
        }
        return "The empty child app created during this attempt (appId ${appId}) was delete-requested but could not be verified gone (${existence.error}) -- check with hub_get_visual_rule(appId=${appId})."
    } catch (Exception e) {
        return "The empty child app created during this attempt (appId ${appId}) could NOT be cleaned up (${e.message}) -- delete it with hub_delete_visual_rule(appId=${appId}, confirm=true)."
    }
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
    def existence = _vrbAppExistence(appId)
    if (existence.state == "unknown") {
        return [success: false, appId: appId,
                error: "App ${appId} did not answer as a Visual Rule, and whether it exists at all could not be determined (${existence.error}).",
                note: "Likely a transient hub error -- retry, or list rules with hub_get_visual_rule (no appId)."]
    }
    if (existence.state == "absent") {
        return [success: false, appId: appId,
                error: "No installed app with appId ${appId} was found.",
                note: "Call hub_get_visual_rule with no appId to list Visual Rules Builder rules, or hub_list_rules for Rule Machine rules."]
    }
    def info = existence.info
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
            def cleanupNote = _vrbTryCleanupShell(created.appId)
            return [success: false, hubNativeFormat: created.format,
                    error: "This hub's Visual Rules Builder creates ${created.format}-format rules, but the definition is ${normalized.format}-format.",
                    note: "Re-call hub_set_visual_rule with a ${created.format} definition -- see hub_get_tool_guide(section='visual_rule_reference'). ${cleanupNote}"]
        }
        try {
            return _vrbApplySave(created.appId, created.format, name, normalized.map, hasPaused ? paused : null, false, true)
        } catch (Exception e) {
            // Log the ORIGINAL failure before attempting cleanup -- the cleanup helper never
            // throws, so the save error can't be masked by a second failure.
            mcpLog("error", "vrb", "hub_set_visual_rule save-after-create failed for new appId ${created.appId}: ${e.message}")
            return [success: false, error: "Saving the new Visual Rule failed: ${e.message}",
                    note: _vrbTryCleanupShell(created.appId)]
        }
    }

    // EDIT / PAUSE: appId given. At least one mutation must be requested.
    if (!hasDefinition && !name && !hasPaused) {
        throw new IllegalArgumentException("Nothing to change: provide definition (full replacement), name (rename), and/or paused (pause/resume) alongside appId.")
    }
    def appId = normalizeRuleId(args.appId)
    def detected
    try {
        detected = _vrbDetect(appId)
    } catch (Exception e) {
        mcpLog("warn", "vrb", "hub_set_visual_rule could not read rule ${appId}: ${e.message}")
        return [success: false, appId: appId, error: "Could not read Visual Rule ${appId}: ${e.message}",
                note: "Likely a transient hub error -- retry, or list rules with hub_get_visual_rule (no appId)."]
    }
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
        def requestedName = (name ?: detected.data.name)?.toString()
        def classicBodyCarriedPause = false
        if (name && name != detected.data.name?.toString()) {
            // A never-saved graph rule reads back a blank ruleJson; the builder UI would save
            // the default empty template in that case, so mirror it rather than POSTing "".
            def emptyTemplate = '{"version":1,"nodes":[{"id":"trigger-1","type":"trigger","triggerCondition":"trigger","triggerType":"sampleTrigger","deviceIds":[]},{"id":"action-1","type":"action","actionType":"sample","deviceIds":[]}],"edges":[{"from":"trigger-1","to":"action-1","port":"next"}]}'
            if (detected.format == "graph") {
                def existing = detected.data.ruleJson?.toString()?.trim() ?: emptyTemplate
                def saved = _vrbSaveGraph(appId, name, existing)
                if (saved.success == false) return [success: false, appId: appId, error: "Rename failed: ${saved.errorMessage}", validationErrors: saved.validationErrors]
            } else {
                // The classic save body always carries rulePaused, so a combined rename+pause
                // commits the pause here -- calling the pause endpoint again would be redundant.
                def existing = [whenNodes: detected.data.whenNodes, thenNodes: detected.data.thenNodes, elseNodes: detected.data.elseNodes]
                _vrbSaveClassic(appId, name, hasPaused ? paused : detected.data.rulePaused == true, existing)
                classicBodyCarriedPause = hasPaused
            }
        }
        if (hasPaused && !classicBodyCarriedPause) {
            def pauseResult = _vrbSetPaused(appId, paused)
            if (pauseResult.success == false) {
                return [success: false, appId: appId, error: "Pause/resume failed", note: pauseResult.error]
            }
        }
        // Neither save endpoint returns a usable body, so the read-back comparison is the
        // only write confirmation -- success must not be claimed without it.
        def after = _vrbDetect(appId)
        def nameOk = after != null && after.data.name?.toString() == requestedName
        def pauseOk = !hasPaused || ((after?.data?.rulePaused == true) == paused)
        def verified = nameOk && pauseOk
        def out = [success: verified, appId: appId, format: detected.format, verified: verified,
                   name: after?.data?.name, rulePaused: after?.data?.rulePaused == true]
        if (!verified) {
            out.error = "The ${name ? 'rename' : 'pause'} request was sent but the read-back did not confirm it (read back name: ${after?.data?.name}, rulePaused: ${after?.data?.rulePaused})."
            out.note = "Re-read with hub_get_visual_rule(appId=${appId}) to inspect what the hub persisted."
            mcpLog("warn", "vrb", "Rename/pause read-back verification failed for ${appId} (nameOk=${nameOk}, pauseOk=${pauseOk})")
        }
        return out
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
    def pauseResult = null
    if (format == "graph") {
        def definitionJson = groovy.json.JsonOutput.toJson(definition)
        def saved = _vrbSaveGraph(appId, name, definitionJson)
        if (saved.success == false) {
            mcpLog("warn", "vrb", "Graph save rejected for ${appId}: ${saved.errorMessage} ${saved.validationErrors ?: ''}")
            return [success: false, error: "Hub rejected the graph save: ${saved.errorMessage}",
                    validationErrors: saved.validationErrors,
                    note: created ? _vrbTryCleanupShell(appId) : "The rule's previous definition is untouched."]
        }
        validationErrors = saved.validationErrors ?: []
        if (pausedRequested != null) {
            // The graph POST carries no rulePaused field; pause state has its own endpoint.
            // A pause failure is surfaced through the read-back check below (verified covers
            // the requested pause state, not just the name).
            pauseResult = _vrbSetPaused(appId, pausedRequested)
        }
    } else {
        _vrbSaveClassic(appId, name, pausedRequested != null ? pausedRequested : (currentPaused == true), definition)
    }
    // Neither save endpoint returns a trustworthy body, so the read-back comparison is the
    // real write verification: name, requested pause state, and node-list sizes (the hub may
    // normalize node CONTENTS on save, so deep equality would false-negative).
    def after = _vrbDetect(appId)
    def nameOk = after != null && after.data.name?.toString() == name
    def pauseOk = pausedRequested == null || ((after?.data?.rulePaused == true) == pausedRequested)
    def countsOk = after != null && _vrbDefinitionCountsMatch(format, definition, after.data)
    def verified = nameOk && pauseOk && countsOk
    def out = [success: verified, appId: appId, format: format, created: created,
               name: after?.data?.name, rulePaused: after?.data?.rulePaused == true, verified: verified]
    if (validationErrors) {
        out.validationErrors = validationErrors
        out.note = "Saved, but the hub reported validation errors -- the rule may not run until they are fixed."
    }
    if (!verified) {
        out.error = "Save POST was sent but the read-back did not confirm the new state (name ok: ${nameOk}, pause ok: ${pauseOk}, definition counts ok: ${countsOk}; read back name: ${after?.data?.name}, rulePaused: ${after?.data?.rulePaused})."
        def hints = []
        if (pauseResult?.success == false) hints << "The pause endpoint reported failure${pauseResult.error ? " (${pauseResult.error})" : ""}."
        hints << "Re-read with hub_get_visual_rule(appId=${appId}) to inspect what the hub persisted."
        out.note = hints.join(" ")
        mcpLog("warn", "vrb", "Read-back verification failed for ${appId} (nameOk=${nameOk}, pauseOk=${pauseOk}, countsOk=${countsOk})")
    } else if (after != null) {
        out.definition = format == "graph" ? after.data.definition :
                [whenNodes: after.data.whenNodes, thenNodes: after.data.thenNodes, elseNodes: after.data.elseNodes]
    }
    return out
}

private boolean _vrbDefinitionCountsMatch(String format, Map submitted, Map readBack) {
    if (format == "graph") {
        def persisted = readBack.definition
        if (!(persisted instanceof Map)) return false
        return (submitted.nodes ?: []).size() == (persisted.nodes ?: []).size() &&
               (submitted.edges ?: []).size() == (persisted.edges ?: []).size()
    }
    return (submitted.whenNodes ?: []).size() == (readBack.whenNodes ?: []).size() &&
           (submitted.thenNodes ?: []).size() == (readBack.thenNodes ?: []).size() &&
           (submitted.elseNodes ?: []).size() == (readBack.elseNodes ?: []).size()
}

def toolDeleteVisualRule(args) {
    requireDestructiveConfirm(args?.confirm as Boolean)
    if (args?.appId == null) throw new IllegalArgumentException("appId is required (find it with hub_get_visual_rule).")
    def appId = normalizeRuleId(args.appId)
    // Type-gate before deleting: forcedelete removes ANY installed app, so only proceed once
    // the id provably speaks a VRB serialization.
    def detected
    try {
        detected = _vrbDetect(appId)
    } catch (Exception e) {
        mcpLog("warn", "vrb", "hub_delete_visual_rule could not read rule ${appId}: ${e.message}")
        return [success: false, appId: appId, error: "Could not read Visual Rule ${appId}: ${e.message}",
                note: "Likely a transient hub error -- nothing was deleted. Retry, or list rules with hub_get_visual_rule (no appId)."]
    }
    if (detected == null) return _vrbNotVisualRuleError(appId)
    def predelete = detected.format == "graph" ? detected.data.definition :
            [whenNodes: detected.data.whenNodes, thenNodes: detected.data.thenNodes, elseNodes: detected.data.elseNodes]
    try {
        _vrbForceDelete(appId)
    } catch (Exception e) {
        return [success: false, appId: appId, error: "Delete request failed: ${e.message}"]
    }
    // verified must come from a definitive absence read-back -- a failed verification read
    // (state "unknown") must not be reported as a confirmed delete.
    def existence = _vrbAppExistence(appId)
    def verified = existence.state == "absent"
    def note
    if (existence.state == "found") {
        note = "The hub still reports app ${appId} after the delete request -- re-check with hub_get_visual_rule."
    } else if (existence.state == "unknown") {
        note = "The delete request was accepted but the verification read-back failed (${existence.error}) -- re-check with hub_get_visual_rule(appId=${appId})."
    } else {
        note = predelete != null ? "To recreate this rule, call hub_set_visual_rule with the predeleteDefinition." :
                                   "This rule had no readable definition (never saved), so there is nothing to recreate."
    }
    mcpLog("info", "vrb", "Deleted Visual Rule ${appId} ('${detected.data.name}') verified=${verified}")
    return [success: verified, appId: appId, name: detected.data.name, format: detected.format,
            verified: verified, predeleteDefinition: predelete, note: note]
}

private Map _vrbRestoreFromSnapshot(Map snapshot, String fileName) {
    // Restore arm for visual_rule-type backup snapshots (routed here by
    // _rmRestoreFromBackup). VRB rules don't speak the classic settings-replay protocol --
    // their definition lives in app state behind the ruleBuilder endpoints -- so the restore
    // re-saves the snapshot's captured definition (vrbFormat + vrbDefinition/vrbRuleJson,
    // written by _rmBackupRuleSnapshot) through the same save+verify tail the set tool uses.
    def savedId = (snapshot.appId ?: snapshot.ruleId) as Integer
    def vrbFormat = snapshot.vrbFormat?.toString()
    def definition
    if (vrbFormat == "classic" && snapshot.vrbDefinition instanceof Map) {
        definition = [whenNodes: snapshot.vrbDefinition.whenNodes ?: [],
                      thenNodes: snapshot.vrbDefinition.thenNodes ?: [],
                      elseNodes: snapshot.vrbDefinition.elseNodes ?: []]
    } else if (vrbFormat == "graph" && snapshot.vrbRuleJson) {
        try {
            def parsed = new groovy.json.JsonSlurper().parseText(snapshot.vrbRuleJson.toString())
            if (parsed instanceof Map) {
                definition = parsed
            } else {
                return [success: false, type: "visual-rule", originalRuleId: savedId, backupFile: fileName,
                        error: "This Visual Rule snapshot's captured graph definition is not a JSON object (got ${parsed instanceof List ? 'an array' : 'a scalar'}).",
                        note: "Recreate the rule manually with hub_set_visual_rule."]
            }
        } catch (Exception e) {
            return [success: false, type: "visual-rule", originalRuleId: savedId, backupFile: fileName,
                    error: "This Visual Rule snapshot's captured graph definition is not parseable JSON: ${e.message}",
                    note: "Recreate the rule manually with hub_set_visual_rule."]
        }
    }
    if (definition == null) {
        return [success: false, type: "visual-rule", originalRuleId: savedId, backupFile: fileName,
                error: "This Visual Rule snapshot carries no captured rule definition (the rule was unreadable when the backup was taken, or the backup predates VRB-aware snapshots).",
                note: "Recreate the rule manually with hub_set_visual_rule -- see hub_get_tool_guide(section='visual_rule_reference')."]
    }
    def name = snapshot.appLabel?.toString()?.trim() ?: "restored-visual-rule-${savedId}"
    // Always restore the SNAPSHOT's pause state (a Boolean, never null) -- an in-place
    // restore must not inherit whatever pause state the live rule drifted to.
    Boolean pausedRequested = snapshot.vrbRulePaused == true

    // Escaped exceptions from here on (create route failure, save network error) must come
    // back as a visual-rule envelope -- the caller's generic catch is rm-rule-flavored.
    try {
        // In-place when the original app still exists and speaks VRB; otherwise recreate.
        def existing = null
        try { existing = _vrbDetect(savedId) } catch (Exception ignored) { }
        Integer targetId
        boolean recreated
        if (existing != null) {
            if (existing.format != vrbFormat) {
                return [success: false, type: "visual-rule", originalRuleId: savedId, backupFile: fileName,
                        error: "App ${savedId} still exists but is ${existing.format}-format; the snapshot is ${vrbFormat}-format.",
                        note: "Delete the rule first (hub_delete_visual_rule) and re-run the restore, or recreate manually with hub_set_visual_rule."]
            }
            targetId = savedId
            recreated = false
        } else {
            def created = _vrbCreateChild()
            if (created.format != vrbFormat) {
                def cleanupNote = _vrbTryCleanupShell(created.appId)
                return [success: false, type: "visual-rule", originalRuleId: savedId, backupFile: fileName, hubNativeFormat: created.format,
                        error: "This hub's Visual Rules Builder now creates ${created.format}-format rules; the snapshot is ${vrbFormat}-format and cannot be replayed.",
                        note: "Recreate the rule manually with hub_set_visual_rule using a ${created.format} definition. ${cleanupNote}"]
            }
            targetId = created.appId
            recreated = true
        }

        def saved = _vrbApplySave(targetId, vrbFormat, name, definition,
                pausedRequested, existing?.data?.rulePaused == true, recreated)
        def out = [success: saved.success, type: "visual-rule", ruleId: targetId, originalRuleId: savedId,
                   recreated: recreated, backupFile: fileName, format: vrbFormat,
                   name: saved.name, rulePaused: saved.rulePaused, verified: saved.verified]
        if (saved.error) out.error = saved.error
        if (saved.validationErrors) out.validationErrors = saved.validationErrors
        out.note = saved.success ?
                (recreated ? "Visual Rule was deleted; recreated with new id ${targetId} and its definition replayed." :
                             "Visual Rule definition restored in place.") :
                (saved.note ?: "Restore did not verify -- inspect with hub_get_visual_rule(appId=${targetId}).")
        mcpLog("info", "vrb", "Restored Visual Rule snapshot for ${savedId} -> ${targetId} (recreated=${recreated}, verified=${saved.verified})")
        return out
    } catch (Exception e) {
        mcpLog("error", "vrb", "Visual Rule restore failed for snapshot of ${savedId}: ${e.message}")
        return [success: false, type: "visual-rule", originalRuleId: savedId, backupFile: fileName,
                error: "Visual Rule restore failed: ${e.message}",
                note: "Likely a transient hub error -- retry, or recreate manually with hub_set_visual_rule."]
    }
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
                    promptHistory: [type: "array", description: "classic format: AI-builder prompts recorded by the hub"],
                    definition: [type: "object", description: "graph format: parsed {version, nodes, edges}"],
                    ruleJson: [type: "string", description: "graph format: the raw double-encoded definition string as stored"],
                    definitionParseError: [type: "string", description: "graph format: present when the stored ruleJson is not parseable JSON"],
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
                    verified: [type: "boolean", description: "Whether a read-back confirmed the name, requested pause state, and definition node counts"],
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
