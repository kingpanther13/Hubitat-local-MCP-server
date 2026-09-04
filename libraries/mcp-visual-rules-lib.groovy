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
    // VRB2 read extras (platform 2.5.1.138+). All OPTIONAL on the wire -- an older firmware
    // answers the same endpoint without them -- so each is passed through only when present.
    if (parsed.revision != null) out.revision = parsed.revision
    if (parsed.validationIssues != null) out.validationIssues = parsed.validationIssues
    if (parsed.referencedDeviceIds != null) out.referencedDeviceIds = parsed.referencedDeviceIds
    if (parsed.ruleApps instanceof List) {
        // The hub decorates these labels with the same HTML it uses on the apps list
        // ("<span style='color:red'>*BROKEN*</span>"); strip it like every other label read.
        out.ruleApps = parsed.ruleApps.collect { app ->
            (app instanceof Map && app.label != null) ? (app + [label: stripAppConfigHtml(app.label)]) : app
        }
    } else if (parsed.ruleApps != null) {
        out.ruleApps = parsed.ruleApps
    }
    // `runtimeGraph` is null whenever nothing is active -- a stored-but-failed activation has an
    // EMPTY validationErrors list and a null runtime, so activation must not be inferred from the
    // error list alone. Absent key = older firmware = unknown (null); present = the hub's verdict.
    if (parsed.containsKey("runtimeGraph")) out.runtimeActive = (parsed.runtimeGraph != null)
    if (parsed.runtimeGraph != null) out.runtimeGraph = parsed.runtimeGraph
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
    if (graph != null) return [format: "graph", data: _vrbWithBareName(graph)]
    def classic = _vrbFetchClassic(appId)
    if (classic != null) return [format: "classic", data: _vrbWithBareName(classic)]
    return null
}

// The hub decorates a paused rule's name ("Name <span class='text-red'>(Paused)</span>"). Every
// consumer wants the rule's OWN name -- a save that echoed the decoration renamed the rule to it
// and then failed its own read-back -- so strip it once at the single reader and keep the raw form
// alongside for anything that needs to show what the hub said.
private Map _vrbWithBareName(Map data) {
    if (data?.name != null) {
        data.rawName = data.name
        data.name = _vrbBareName(data.name, data.rulePaused == true)
    }
    return data
}

private Map _vrbParentNode() {
    // The "Visual Rules Builder" parent node in the /hub2/appsList installed-app tree. Its
    // children are the rules; its id is the parent every child-create route needs. Throws
    // IllegalStateException when the parent app is not installed so the caller can return an
    // actionable note.
    def text = hubInternalGet("/hub2/appsList")
    if (!text) throw new IllegalStateException("Empty response from /hub2/appsList")
    def parsed = new groovy.json.JsonSlurper().parseText(text)
    def parent = (parsed?.apps ?: []).find { it?.data?.type == "Visual Rules Builder" }
    if (parent == null) {
        throw new IllegalStateException("The Visual Rules Builder parent app is not installed on this hub. Install it via Apps -> Add Built-In App -> Visual Rules Builder, then retry.")
    }
    return parent
}

private List _vrbListRules() {
    def parent = _vrbParentNode()
    // A paused VRB rule decorates its appsList name with a "(Paused)" suffix (often HTML-
    // wrapped in a red span); strip tags/entities so the name is clean AND the suffix is
    // detectable. Unlike hub_list_rules there is no RMUtils label to cross-check against, so
    // this is suffix-only: a rule the user literally named "... (Paused)" reads as paused here.
    // hub_get_visual_rule(appId) returns the authoritative rulePaused.
    //
    // paused/disabled are OMITTED (not asserted false) when the node data can't support them:
    // a null stripped name means paused is undeterminable, and an absent data.disabled key
    // means disabled is undeterminable. Present keys behave exactly as before.
    return (parent.children ?: []).findAll { it?.data?.id != null }.collect {
        def cleanName = stripAppConfigHtml(it.data.name)
        def disabledRaw = it.data.disabled
        def entry = [appId: it.data.id, name: cleanName]
        // The hub types each child "Visual Rule Builder 1.0" / "... 2.0" -- the only place the
        // rule's serialization is visible without a per-rule read. Omitted when unparseable
        // (older firmware reported the bare family name).
        def versionMatch = (it.data.type?.toString() ?: "") =~ /(\d+\.\d+)\s*$/
        if (versionMatch.find()) entry.version = versionMatch.group(1)
        if (disabledRaw != null) entry.disabled = (disabledRaw == true)
        if (cleanName != null) {
            entry.paused = cleanName.endsWith("(Paused)")
        } else {
            mcpLog("warn", "vrb", "_vrbListRules: rule ${it.data.id} has no readable name in /hub2/appsList; name null, paused undeterminable")
        }
        entry
    }
}

private Map _vrbCreateChild(String version) {
    // The VRB parent offers a per-VERSION child-create route, so the DEFINITION picks which
    // builder the new rule runs instead of the firmware picking for us:
    //   /installedapp/createchild/hubitat/Visual Rule Builder <version>/parent/<parentId>
    //     -> 302 /installedapp/configure/<newId>
    // A freshly created 2.0 child answers /app/ruleBuilder20Json straight away (empty ruleJson);
    // a 1.0 child answers /app/ruleBuilderJson with {} until its first classic save, which is why
    // the format is taken from the route we asked for rather than from a probe.
    //
    // Firmware without the versioned child types throws here. The fallback is the parent's own
    // create link, which picks the version ITSELF -- the caller reconciles what it gets back.
    def wantedFormat = (version == "1.0") ? "classic" : "graph"
    def before = [] as Set
    def parentSeen = false
    try {
        def parent = _vrbParentNode()
        parentSeen = true
        before = ((parent.children ?: []).collect { it?.data?.id?.toString() }.findAll { it }) as Set
        def newId = _rmCreateChildApp(parent.data.id as Integer, "hubitat", "Visual Rule Builder ${version}".toString())
        return [appId: newId, format: wantedFormat, version: version, route: "createchild"]
    } catch (Exception e) {
        // The raw GET may auto-follow an ABSOLUTE redirect and answer 200 with no Location (see
        // hubInternalGetRaw): the child then EXISTS and only its id was lost. Reconcile against the
        // parent's children before any second non-idempotent create -- exactly one new child is
        // adopted; none means the versioned route is genuinely unsupported; more than one is
        // refused rather than guessed.
        // "The read showed no new child" and "the read failed" must not collapse into the same
        // branch: the second one leaves the child's existence UNKNOWN, and creating again on unknown
        // is exactly the duplicate this block exists to prevent.
        def appeared = []
        def reconciled = false
        if (parentSeen) {
            try {
                appeared = (_vrbParentNode().children ?: []).collect { it?.data?.id?.toString() }.findAll { it && !before.contains(it) }
                reconciled = true
            } catch (Exception readError) {
                mcpLog("warn", "vrb", "Could not re-read the Visual Rules Builder parent after a failed versioned create: ${readError.message}")
            }
        }
        if (parentSeen && !reconciled) {
            throw new IllegalStateException("Versioned create of a Visual Rule Builder ${version} child failed (${e.message}) and the parent could not be re-read to tell whether a child was created; refusing to create again. List rules with hub_get_visual_rule, delete any empty unnamed shell, and retry.")
        }
        if (appeared.size() == 1) {
            // A list delta alone does not prove ownership -- another client could have created a
            // rule in the same window. Adopt only a child that looks exactly like what this
            // request would have made: the requested builder version, no name, never saved.
            def candidate = appeared[0] as Integer
            if (_vrbIsFreshShell(candidate, version)) {
                mcpLog("warn", "vrb", "Versioned create of a Visual Rule Builder ${version} child answered without a usable Location (${e.message}); adopted the fresh shell that appeared, app ${candidate}")
                return [appId: candidate, format: wantedFormat, version: version, route: "createchild"]
            }
            throw new IllegalStateException("Versioned create of a Visual Rule Builder ${version} child answered without a usable Location (${e.message}); app ${candidate} appeared meanwhile but is not an empty ${version} shell, so it is not provably this request's -- inspect it with hub_get_visual_rule(appId=${candidate}) and retry.")
        }
        if (appeared.size() > 1) {
            throw new IllegalStateException("Versioned create of a Visual Rule Builder ${version} child answered without a usable Location (${e.message}) and ${appeared.size()} new children appeared (${appeared.join(', ')}); refusing to guess which is ours -- delete the strays with hub_delete_visual_rule and retry.")
        }
        mcpLog("warn", "vrb", "Versioned create of a Visual Rule Builder ${version} child failed (${e.message}) and no child appeared; falling back to /app/createVisualRuleBuilderRule")
    }
    def legacy = _vrbCreateChildLegacy()
    legacy.version = (legacy.format == "classic") ? "1.0" : "2.0"
    legacy.route = "createVisualRuleBuilderRule"
    return legacy
}

private boolean _vrbIsFreshShell(Integer appId, String version) {
    // The signature of a child the versioned create just made and nobody has touched: typed as
    // that builder version, nameless, and never saved (a 2.0 shell answers the graph endpoint
    // with a blank ruleJson; a 1.0 shell does not yet answer the classic endpoint at all).
    def existence = _vrbAppExistence(appId)
    if (existence.state != "found") return false
    def info = existence.info
    if (info.type?.toString() != "Visual Rule Builder ${version}".toString()) return false
    if (stripAppConfigHtml(info.name)?.toString()?.trim()) return false
    if (version == "2.0") {
        def graph = _vrbFetchGraph(appId)
        return graph != null && !(graph.ruleJson?.toString()?.trim())
    }
    return _vrbFetchClassic(appId) == null
}

private Map _vrbCreateChildLegacy() {
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
    // saved. A save with non-empty validationErrors still persists as an INACTIVE DRAFT: VRB2
    // stores the document and skips activation, which is why storedSuccessfully and
    // activatedSuccessfully are separate fields and only the latter proves the rule runs.
    def body = groovy.json.JsonOutput.toJson([name: name, ruleJson: definitionJson])
    def resp = hubInternalPostJson("/app/ruleBuilder20Json/${appId}", body)
    if (resp instanceof Map && resp.success == false) {
        return _vrbSaveGraphMeta(resp, [success: false, errorMessage: resp.errorMessage ?: resp.storageError ?: "hub rejected the save",
                                        validationErrors: resp.validationErrors ?: []])
    }
    // A null resp (empty / non-JSON 200 body) is treated as accepted, mirroring the UI's
    // success-unless-false check -- every caller confirms via a read-back comparison, which
    // is the real write verification for both save endpoints.
    return _vrbSaveGraphMeta(resp, [success: true,
                                    validationErrors: (resp instanceof Map ? (resp.validationErrors ?: []) : [])])
}

private Map _vrbSaveGraphMeta(def resp, Map out) {
    // Copy the VRB2 save-response contract fields onto the result WHEN THE HUB SENT THEM. Every
    // one is optional on the wire (a pre-2.0 firmware answers the same endpoint without them),
    // so presence is what the callers key on -- never a fabricated default.
    if (!(resp instanceof Map)) return out
    if (resp.containsKey("storedSuccessfully")) out.storedSuccessfully = resp.storedSuccessfully == true
    if (resp.containsKey("activatedSuccessfully")) out.activatedSuccessfully = resp.activatedSuccessfully == true
    if (resp.revision != null) out.revision = resp.revision
    if (resp.validationIssues != null) out.validationIssues = resp.validationIssues
    if (resp.referencedDeviceIds != null) out.referencedDeviceIds = resp.referencedDeviceIds
    // The ONLY diagnostics for a store-succeeded-but-activation-threw save (validationErrors is
    // empty on that path) and for a storage failure -- never drop them.
    if (resp.activationError != null) out.activationError = resp.activationError
    if (resp.storageError != null) out.storageError = resp.storageError
    return out
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

private List _vrb2TriggerTypes() {
    // VRB 2.0 `type` catalogs. The Groovy sandbox rejects static field initializers, so each
    // catalog is a method. Source: the hub's own VRB2 authoring guide plus the builder chunk.
    return ["timeOfDay", "sunriseSunset", "motion", "contact", "presence", "acceleration",
            "water", "smoke", "co", "alarm", "temperature", "humidity", "illuminance",
            "power", "switch", "button", "lock", "shock", "systemMode"]
}

private List _vrb2ConditionTypes() {
    return ["timeIsBetween", "daysOfWeek", "motionCondition", "contactCondition",
            "presenceCondition", "accelerationCondition", "waterCondition", "smokeCondition",
            "coCondition", "temperatureCondition", "humidityCondition", "illuminanceCondition",
            "powerCondition", "switchCondition", "lockCondition", "thermostatModeCondition",
            "systemModeCondition"]
}

private List _vrb2ActionTypes() {
    return ["turnOn", "turnOff", "toggle", "setBrightness", "setColorTemp", "setColor",
            "setLightEffect", "lock", "unlock", "turnOnAlarm", "turnOffAlarm", "openValve",
            "closeValve", "openGarageDoor", "closeGarageDoor", "openWindowShade",
            "closeWindowShade", "pushButton", "sendNotification", "speakNotification",
            "controlPlayer", "controlThermostat", "setFanSpeed", "setMode", "setModeUnlessAway",
            "exitAwayMode", "runRule", "wait", "cancelWait", "sample"]
}

private Map _vrb2NodeFromDialog(Map node, String typeKey) {
    // A 1.0 dialog node maps 1:1 onto a 2.0 node: `type` is the dialog's triggerType/actionType
    // and everything else becomes `config`. The builder's own dialog->config mapping drops
    // description/deviceIds/predefinedColor; the classic serialization adds index/type/result,
    // which are list bookkeeping rather than rule data.
    def config = [:]
    node.each { k, v ->
        def key = k?.toString()
        if (key == null) return
        if (key in [typeKey, "id", "kind", "config", "description", "deviceIds", "predefinedColor", "index", "type", "result"]) return
        config[key] = v
    }
    def out = [type: node[typeKey], config: config]
    if (node.id != null && node.id.toString().trim()) out.id = node.id.toString()
    return out
}

private Map _vrb2EditorItem(def raw, String typeKey, String label) {
    // Normalize one editor item to {id?, type, config}. Accepts the 2.0 shape ({type, config})
    // or the flat 1.0 dialog shape (triggerType/actionType plus the field keys).
    if (!(raw instanceof Map)) throw new IllegalArgumentException("${label} must be a JSON object.")
    Map node = (Map) raw
    Map out
    if (node.containsKey(typeKey)) {
        out = _vrb2NodeFromDialog(node, typeKey)
    } else {
        if (node.config != null && !(node.config instanceof Map)) {
            throw new IllegalArgumentException("${label} config must be a JSON object.")
        }
        out = [type: node.type, config: (node.config instanceof Map ? node.config : [:])]
        if (node.id != null && node.id.toString().trim()) out.id = node.id.toString()
    }
    def t = out.type?.toString()?.trim()
    if (!t) {
        throw new IllegalArgumentException("${label} has no type. Use the 2.0 form {type, config} or the 1.0 dialog form carrying '${typeKey}'.")
    }
    out.type = t
    return out
}

private List _vrb2EditorList(def raw, String typeKey, String label) {
    if (raw == null) return []
    if (!(raw instanceof List)) throw new IllegalArgumentException("${label} must be an array.")
    def out = []
    raw.eachWithIndex { item, i -> out << _vrb2EditorItem(item, typeKey, "${label}[${i}]") }
    return out
}

private String _vrb2UniqueId(String base, Set used) {
    def candidate = base
    int n = 1
    while (used.contains(candidate)) {
        candidate = "${base}-${n}".toString()
        n++
    }
    return candidate
}

private void _vrb2AssignIds(List items, String prefix, Set used) {
    items.eachWithIndex { item, i ->
        if (item.id == null || !item.id.toString().trim()) {
            item.id = _vrb2UniqueId("${prefix}-${i + 1}".toString(), used)
        }
        used << item.id.toString()
    }
}

private void _vrb2ChainEdges(List edges, String from, String port, List chain, String terminal) {
    // The builder's chain(): a non-empty branch is entered on `port` then linked node-to-node on
    // `next`, terminating at the branch merge when there is one; an EMPTY branch with a merge
    // still gets its own edge so the decision port is not left dangling.
    if (!chain.isEmpty()) {
        edges << [from: from, to: chain[0].id, port: port]
        chain.eachWithIndex { item, i ->
            def to = (i + 1 < chain.size()) ? chain[i + 1].id : terminal
            if (to != null) edges << [from: item.id, to: to, port: "next"]
        }
    } else if (terminal != null) {
        edges << [from: from, to: terminal, port: port]
    }
}

private Map _vrb2Compose(Map editor) {
    // Editor form -> 2.0 graph document, with exactly the topology the hub's own Vue builder
    // composes, so a rule authored here is indistinguishable from one drawn in the UI.
    if (editor == null) throw new IllegalArgumentException("The editor definition must be a JSON object.")
    def decisionType = editor.decisionType?.toString()?.trim() ?: "all"
    if (!(decisionType in ["all", "any"])) {
        throw new IllegalArgumentException("Unsupported decision type '${decisionType}'. Use 'all' (AND) or 'any' (OR).")
    }
    def triggers = _vrb2EditorList(editor.triggers, "triggerType", "triggers")
    def conditions = _vrb2EditorList(editor.conditions, "triggerType", "conditions")
    def thenActions = _vrb2EditorList(editor.thenActions, "actionType", "thenActions")
    def elseActions = _vrb2EditorList(editor.elseActions, "actionType", "elseActions")
    def commonActions = _vrb2EditorList(editor.commonActions, "actionType", "commonActions")
    if (decisionType == "any" && conditions.isEmpty()) {
        throw new IllegalArgumentException("An OR decision must contain at least one condition.")
    }

    // Register every caller-supplied id BEFORE generating any, so a generated id can never
    // collide with an explicit one that appears later in the document.
    def used = [] as Set
    [triggers, conditions, thenActions, elseActions, commonActions].each { list ->
        list.each { if (it.id != null) used << it.id.toString() }
    }
    _vrb2AssignIds(triggers, "trigger", used)
    _vrb2AssignIds(conditions, "condition", used)
    _vrb2AssignIds(thenActions, "then", used)
    _vrb2AssignIds(elseActions, "else", used)
    _vrb2AssignIds(commonActions, "common", used)

    def structureIds = (editor.structureIds instanceof Map) ? editor.structureIds : [:]
    def triggerMergeId = structureIds.triggerMerge?.toString()?.trim() ?: _vrb2UniqueId("trigger-merge", used)
    used << triggerMergeId
    def decisionId = structureIds.decision?.toString()?.trim() ?: _vrb2UniqueId("decision", used)
    used << decisionId
    def branchMergeId = null
    if (!commonActions.isEmpty()) {
        branchMergeId = structureIds.branchMerge?.toString()?.trim() ?: _vrb2UniqueId("branch-merge", used)
        used << branchMergeId
    }

    def nodes = []
    triggers.each { nodes << [id: it.id, kind: "trigger", type: it.type, config: it.config] }
    nodes << [id: triggerMergeId, kind: "merge", type: "triggerMerge", config: [:]]
    nodes << [id: decisionId, kind: "decision", type: decisionType,
              config: [conditions: conditions.collect { [id: it.id, type: it.type, config: it.config] }]]
    thenActions.each { nodes << [id: it.id, kind: "action", type: it.type, config: it.config] }
    elseActions.each { nodes << [id: it.id, kind: "action", type: it.type, config: it.config] }

    def edges = []
    triggers.each { edges << [from: it.id, to: triggerMergeId, port: "next"] }
    edges << [from: triggerMergeId, to: decisionId, port: "next"]
    _vrb2ChainEdges(edges, decisionId, "true", thenActions, branchMergeId)
    _vrb2ChainEdges(edges, decisionId, "false", elseActions, branchMergeId)
    if (branchMergeId != null) {
        nodes << [id: branchMergeId, kind: "merge", type: "branchMerge", config: [:]]
        commonActions.each { nodes << [id: it.id, kind: "action", type: it.type, config: it.config] }
        _vrb2ChainEdges(edges, branchMergeId, "next", commonActions, null)
    }
    return [version: 1, nodes: nodes, edges: edges]
}

private boolean _vrb2IsVersionOne(def v) {
    // The hub accepts INTEGER version 1 only; an `as int` coercion would let 1.5 through the
    // pre-flight and store an inactive draft instead of refusing before the write.
    return (v instanceof Integer || v instanceof Long) && (v as long) == 1L
}

private List _vrb2WalkChain(Map byId, Map nextMap, String start, String terminal) {
    def out = []
    def seen = [] as Set
    def cursor = start
    while (cursor != null && cursor != terminal) {
        if (seen.contains(cursor)) throw new IllegalArgumentException("The rule contains an action cycle.")
        seen << cursor
        def node = byId[cursor]
        if (!(node instanceof Map) || node.kind != "action") {
            throw new IllegalArgumentException("Expected action node '${cursor}'.")
        }
        out << node
        cursor = nextMap["${cursor}:next".toString()]
    }
    return out
}

private Map _vrb2Decompose(Map graph) {
    // 2.0 graph -> editor form: the inverse of _vrb2Compose, and of the hub builder's own
    // decomposition, with its error messages -- so a graph the UI cannot open reports the same way.
    if (graph == null || !_vrb2IsVersionOne(graph.version) ||
        !(graph.nodes instanceof List) || !(graph.edges instanceof List)) {
        throw new IllegalArgumentException("The rule is not a Visual Rule Builder 2.0 schema version 1 document.")
    }
    def byId = [:]
    graph.nodes.each { if (it instanceof Map && it.id != null) byId[it.id.toString()] = it }
    def triggerMerge = graph.nodes.find { it instanceof Map && it.kind == "merge" && it.type == "triggerMerge" }
    def decision = graph.nodes.find { it instanceof Map && it.kind == "decision" && (it.type == null || it.type in ["all", "any"]) }
    def branchMerge = graph.nodes.find { it instanceof Map && it.kind == "merge" && it.type == "branchMerge" }
    if (triggerMerge == null || decision == null) {
        throw new IllegalArgumentException("The rule must contain a trigger merge and an AND/OR decision.")
    }
    def conditions = (decision.config instanceof Map && decision.config.conditions instanceof List) ? decision.config.conditions : []
    if (decision.type == "any" && conditions.isEmpty()) {
        throw new IllegalArgumentException("An OR decision must contain at least one condition.")
    }
    def nextMap = [:]
    graph.edges.each {
        if (it instanceof Map && it.from != null && it.port != null) {
            nextMap["${it.from}:${it.port}".toString()] = it.to?.toString()
        }
    }
    def branchMergeId = branchMerge?.id?.toString()
    def structureIds = [triggerMerge: triggerMerge.id, decision: decision.id]
    if (branchMergeId != null) structureIds.branchMerge = branchMergeId
    return [triggers: graph.nodes.findAll { it instanceof Map && it.kind == "trigger" },
            conditions: conditions,
            decisionType: decision.type ?: "all",
            thenActions: _vrb2WalkChain(byId, nextMap, nextMap["${decision.id}:true".toString()], branchMergeId),
            elseActions: _vrb2WalkChain(byId, nextMap, nextMap["${decision.id}:false".toString()], branchMergeId),
            commonActions: branchMergeId != null ? _vrb2WalkChain(byId, nextMap, nextMap["${branchMergeId}:next".toString()], null) : [],
            structureIds: structureIds]
}

private List _vrb2ValidateChain(Map kinds, Map nextMap, String from, String port, String terminal, Set visited) {
    // Walk one linear action chain leaving `from` on `port`. When a branchMerge exists every
    // decision branch MUST reach it (an empty branch is the direct decision -> branchMerge edge
    // the builder emits), so a chain that dead-ends short of its terminal is rejected here rather
    // than by the hub, which would store it as an inactive draft. Every node walked is recorded in
    // `visited` so the caller can flag declared nodes the flow never reaches.
    def errs = []
    def start = nextMap["${from}:${port}".toString()]
    if (start == null) {
        if (terminal != null) errs << "Port '${port}' of node '${from}' must connect to the branchMerge node '${terminal}' (directly, or through a chain of actions)."
        return errs
    }
    def seen = [] as Set
    def cursor = start
    while (cursor != null && cursor != terminal) {
        if (seen.contains(cursor)) { errs << "The rule contains an action cycle."; return errs }
        // A chain may only run through action nodes -- report that before the join check so a
        // branch routed into a structure node names the real mistake.
        if (kinds[cursor] != "action") { errs << "Expected action node '${cursor}'."; return errs }
        if (visited.contains(cursor)) {
            // Arbitrary joins are rejected by the hub: two branches may only rejoin at the branchMerge.
            errs << "Node '${cursor}' is reached by more than one path; branches may only rejoin at the branchMerge node."
            return errs
        }
        seen << cursor
        visited << cursor
        cursor = nextMap["${cursor}:next".toString()]
    }
    if (terminal != null && cursor != terminal) {
        errs << "The action chain leaving port '${port}' of node '${from}' must end at the branchMerge node '${terminal}'."
    }
    return errs
}

private List _vrb2Validate(Map graph) {
    // STRUCTURAL + type-catalog pre-flight for a 2.0 graph. Deliberately does NOT inspect config
    // field CONTENTS (device ids, enum labels, ranges, durations) -- that is the hub validator's
    // job, and its verdict comes back as validationErrors on the save. Empty list = looks sane.
    def errors = []
    if (graph == null) return ["Rule document must be a JSON object."]
    if (!_vrb2IsVersionOne(graph.version)) errors << "Rule 'version' must be 1."
    if (!(graph.nodes instanceof List)) errors << "Rule 'nodes' must be an array."
    if (!(graph.edges instanceof List)) errors << "Rule 'edges' must be an array."
    if (!(graph.nodes instanceof List) || !(graph.edges instanceof List)) {
        return errors.collect { it.toString() }
    }

    def kinds = [:]
    def types = [:]
    def ids = [] as Set
    graph.nodes.eachWithIndex { node, i ->
        if (!(node instanceof Map)) { errors << "Node at index ${i} must be an object."; return }
        def rawId = node.id
        def id = rawId?.toString()
        if (!(rawId instanceof CharSequence) || !id.trim()) {
            errors << "Node at index ${i} must have a nonblank string id."
            return
        }
        if (ids.contains(id)) { errors << "Duplicate node id '${id}'."; return }
        ids << id
        def kind = node.kind?.toString()
        if (!(kind in ["trigger", "merge", "decision", "action"])) {
            errors << "Node '${id}' has unsupported kind '${node.kind}'."
        } else {
            kinds[id] = kind
        }
        if (!(node.config instanceof Map)) errors << "Node '${id}' config must be an object."
        def type = node.type?.toString()
        types[id] = type
        if (kind == "trigger" && !(type in _vrb2TriggerTypes())) {
            errors << "Trigger node '${id}' has unsupported type '${node.type}'."
        } else if (kind == "action" && !(type in _vrb2ActionTypes())) {
            errors << "Action node '${id}' has unsupported type '${node.type}'."
        } else if (kind == "merge" && !(type in ["triggerMerge", "branchMerge"])) {
            errors << "Merge node '${id}' has unsupported type '${node.type}'."
        } else if (kind == "decision" && node.type != null && !(type in ["all", "any"])) {
            errors << "Decision node '${id}' has unsupported type '${node.type}'."
        }
    }

    def idList = ids as List
    def triggerIds = idList.findAll { kinds[it] == "trigger" }
    def triggerMergeIds = idList.findAll { kinds[it] == "merge" && types[it] == "triggerMerge" }
    def branchMergeIds = idList.findAll { kinds[it] == "merge" && types[it] == "branchMerge" }
    def decisionIds = idList.findAll { kinds[it] == "decision" }
    if (triggerIds.isEmpty()) errors << "Rule must contain at least one trigger node."
    if (triggerMergeIds.size() != 1) errors << "Rule must contain exactly one triggerMerge node."
    if (decisionIds.size() != 1) errors << "Rule must contain exactly one decision node."
    if (branchMergeIds.size() > 1) errors << "Rule must contain at most one branchMerge node."

    if (decisionIds.size() == 1) {
        def decisionId = decisionIds[0]
        def decisionNode = graph.nodes.find { it instanceof Map && it.id?.toString() == decisionId }
        def rawConditions = (decisionNode.config instanceof Map) ? decisionNode.config.conditions : null
        if (rawConditions != null && !(rawConditions instanceof List)) {
            errors << "Node '${decisionId}' config.conditions must be an array."
        } else {
            def conditions = (rawConditions instanceof List) ? rawConditions : []
            if (types[decisionId] == "any" && conditions.isEmpty()) {
                errors << "An 'any' decision must contain at least one condition."
            }
            def conditionIds = [] as Set
            conditions.eachWithIndex { cond, i ->
                if (!(cond instanceof Map)) { errors << "Condition at index ${i} must be an object."; return }
                def cid = cond.id?.toString()
                if (!(cond.id instanceof CharSequence) || !cid.trim()) {
                    errors << "Condition at index ${i} must have a nonblank string id."
                    return
                }
                if (conditionIds.contains(cid)) { errors << "Duplicate condition id '${cid}'."; return }
                conditionIds << cid
                if (!(cond.type?.toString() in _vrb2ConditionTypes())) {
                    errors << "Condition '${cid}' has unsupported type '${cond.type}'."
                }
                if (!(cond.config instanceof Map)) errors << "Condition '${cid}' config must be an object."
            }
        }
    }

    def seenEdges = [] as Set
    def usedPorts = [] as Set
    def nextMap = [:]
    graph.edges.eachWithIndex { edge, i ->
        if (!(edge instanceof Map)) { errors << "Edge at index ${i} must be an object."; return }
        // Endpoints and ports are STRINGS on the wire; a numeric `from` that merely prints like a
        // node id would pass a stringified comparison here and then be stored as an inactive draft.
        if (!(edge.from instanceof CharSequence) || !(edge.to instanceof CharSequence) || !(edge.port instanceof CharSequence)) {
            errors << "Edge at index ${i} must have string 'from', 'to' and 'port' values."
            return
        }
        def from = edge.from.toString()
        def to = edge.to.toString()
        def port = edge.port.toString()
        if (!from.trim() || !to.trim() || !port.trim()) {
            errors << "Edge at index ${i} must have nonblank 'from', 'to' and 'port' strings."
            return
        }
        if (!ids.contains(from)) { errors << "Edge at index ${i} references unknown node '${from}'."; return }
        if (!ids.contains(to)) { errors << "Edge at index ${i} references unknown node '${to}'."; return }
        def edgeKey = "${from}|${to}|${port}".toString()
        if (seenEdges.contains(edgeKey)) {
            errors << "Duplicate edge '${from}' -> '${to}' on port '${port}'."
            return
        }
        seenEdges << edgeKey
        def allowed = (kinds[from] == "decision") ? ["true", "false"] : ["next"]
        if (kinds[from] != null && !(port in allowed)) {
            errors << "Edge from '${from}' has invalid port '${port}' (expected ${allowed.join(' or ')})."
            return
        }
        def portKey = "${from}|${port}".toString()
        if (usedPorts.contains(portKey)) {
            errors << "Node '${from}' has more than one outgoing edge on port '${port}'."
            return
        }
        usedPorts << portKey
        nextMap["${from}:${port}".toString()] = to
    }

    if (triggerMergeIds.size() == 1 && decisionIds.size() == 1) {
        def triggerMergeId = triggerMergeIds[0]
        def decisionId = decisionIds[0]
        triggerIds.each { tid ->
            if (nextMap["${tid}:next".toString()] != triggerMergeId) {
                errors << "Trigger node '${tid}' must connect to the triggerMerge node '${triggerMergeId}'."
            }
        }
        if (nextMap["${triggerMergeId}:next".toString()] != decisionId) {
            errors << "The triggerMerge node must connect to the decision node."
        }
        def terminal = branchMergeIds.size() == 1 ? branchMergeIds[0] : null
        def visited = ([triggerMergeId, decisionId] + triggerIds) as Set
        if (terminal != null) visited << terminal
        errors.addAll(_vrb2ValidateChain(kinds, nextMap, decisionId, "true", terminal, visited))
        errors.addAll(_vrb2ValidateChain(kinds, nextMap, decisionId, "false", terminal, visited))
        if (terminal != null) {
            errors.addAll(_vrb2ValidateChain(kinds, nextMap, terminal, "next", null, visited))
        }
        // The hub rejects disconnected nodes; a declared action the flow never reaches is the
        // classic authoring slip (a node added, its edge forgotten).
        idList.findAll { !visited.contains(it) }.each { errors << "Node '${it}' is not connected to the rule's flow." }
    }
    return errors.collect { it.toString() }.unique()
}

private Map _vrbClassicToGraph(Map classic) {
    // Deterministic 1.0 -> 2.0 translation. Native VRB2 does NOT migrate 1.0 documents, so this
    // is ours: a classic whenNode is a real trigger unless its triggerType is in the condition
    // catalog, in which case it becomes a nested decision condition. The hub validator is the
    // oracle for the result -- _vrb2Validate only pre-flights the shape.
    _vrbValidateClassicShape(classic)
    def whenNodes = (classic?.whenNodes ?: []) as List
    def conditionTypes = _vrb2ConditionTypes()
    def isCondition = { node -> node instanceof Map && (node.triggerType?.toString() in conditionTypes) }
    return _vrb2Compose([
        triggers: whenNodes.findAll { !isCondition(it) },
        conditions: whenNodes.findAll { isCondition(it) },
        decisionType: "all",
        thenActions: (classic?.thenNodes ?: []) as List,
        elseActions: (classic?.elseNodes ?: []) as List,
        commonActions: []
    ])
}

private void _vrbValidateClassicShape(Map definition) {
    // A classic node-list carries ARRAYS of node objects. Anything else would either blow up as a
    // cast after a child already exists or be POSTed verbatim for the hub to choke on, so it is
    // refused up front -- before any create -- as a plain argument error.
    if (definition == null) throw new IllegalArgumentException("definition must be a JSON object.")
    ["whenNodes", "thenNodes", "elseNodes"].each { key ->
        if (definition[key] == null) return
        if (!(definition[key] instanceof List)) throw new IllegalArgumentException("definition.${key} must be an array of node objects.")
        definition[key].eachWithIndex { node, i ->
            if (!(node instanceof Map)) throw new IllegalArgumentException("definition.${key}[${i}] must be a node object.")
        }
    }
}

private String _vrbPreflightMessage(List validationErrors) {
    return "Definition failed pre-flight validation; nothing was created or saved. Problems: " + validationErrors.join(" ") +
           " Fix them and retry -- see hub_get_tool_guide(section='visual_rule_reference') for the graph schema, the editor form and the type catalogs."
}

private Map _vrbResolveTargetDefinition(String targetFormat, String definitionFormat, Map definitionMap) {
    // Single resolution point shared by create, edit and restore: turn whatever shape the caller
    // sent into the wire format the TARGET rule speaks.
    //   [ok: true,  definition: <map>, translatedFrom: <String|null>]
    //   [ok: false, validationErrors: [...]]  -- pre-flight refusal
    //   [ok: false, formatMismatch: true]     -- a 2.0 document aimed at a 1.0 rule
    // A compose/translate IllegalArgumentException is folded into validationErrors rather than
    // rethrown: the classic-input path only learns the target format AFTER the child shell
    // exists, and a validation throw must never fire after a side effect.
    if (targetFormat == "graph") {
        Map graph
        String translatedFrom = null
        try {
            if (definitionFormat == "editor") {
                graph = _vrb2Compose(definitionMap)
            } else if (definitionFormat == "classic") {
                graph = _vrbClassicToGraph(definitionMap)
                translatedFrom = "classic"
            } else {
                graph = definitionMap
            }
        } catch (Exception e) {
            // Any compose/translate failure (a bad shape can also surface as a cast error) is a
            // pre-flight finding, never a raw throw -- this runs after the shell exists on the
            // legacy-create fallback.
            return [ok: false, validationErrors: [e.message?.toString() ?: "definition could not be composed into a 2.0 graph"]]
        }
        def errors = _vrb2Validate(graph)
        if (errors) return [ok: false, validationErrors: errors]
        return [ok: true, definition: graph, translatedFrom: translatedFrom]
    }
    if (definitionFormat == "classic") return [ok: true, definition: definitionMap, translatedFrom: null]
    return [ok: false, formatMismatch: true]
}

private Map _vrbPreflightRefusal(Integer appId, List validationErrors, String extraNote = null) {
    def out = [success: false]
    if (appId != null) out.appId = appId
    out.error = "Definition failed pre-flight validation; nothing was created/saved."
    out.validationErrors = validationErrors
    def note = "Fix the listed problems and retry -- see hub_get_tool_guide(section='visual_rule_reference') for the graph schema, the editor form and the type catalogs."
    out.note = extraNote ? "${note} ${extraNote}" : note
    return out
}

private Map _vrbFormatMismatchRefusal(Integer appId, String definitionFormat, String extraNote) {
    def out = [success: false]
    if (appId != null) out.appId = appId
    out.format = "classic"
    out.hubNativeFormat = "classic"
    out.error = "This hub still runs Visual Rule Builder 1.0, which speaks only the classic format; the definition is ${definitionFormat}-format."
    def note = "Re-send the rule as a classic definition ({whenNodes, thenNodes, elseNodes}) -- see hub_get_tool_guide(section='visual_rule_reference'). 2.0-only features (OR decisions, a common action tail) cannot be expressed on a 1.0 hub."
    out.note = extraNote ? "${note} ${extraNote}" : note
    return out
}

private String _vrbDetectDefinitionFormat(Map definition) {
    def looksGraph = definition.containsKey("nodes") || definition.containsKey("edges")
    def looksClassic = definition.containsKey("whenNodes") || definition.containsKey("thenNodes") || definition.containsKey("elseNodes")
    def looksEditor = ["triggers", "conditions", "decisionType", "thenActions", "elseActions", "commonActions"].any { definition.containsKey(it) }
    if ([looksGraph, looksClassic, looksEditor].count { it } > 1) {
        throw new IllegalArgumentException("definition mixes graph keys (nodes/edges), classic keys (whenNodes/thenNodes/elseNodes) and/or editor keys (triggers/conditions/decisionType/thenActions/elseActions/commonActions) -- supply exactly one shape. See hub_get_tool_guide(section='visual_rule_reference').")
    }
    if (looksGraph) return "graph"
    if (looksClassic) return "classic"
    if (looksEditor) return "editor"
    throw new IllegalArgumentException("definition must be the editor form ({triggers, conditions, decisionType, thenActions, elseActions, commonActions} -- recommended), a 2.0 graph ({version, nodes, edges}), or a classic 1.0 node-list ({whenNodes, thenNodes, elseNodes}). See hub_get_tool_guide(section='visual_rule_reference') for all three schemas.")
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

// The rule's name with the hub's paused decoration removed. Only strips when the rule actually
// reads back paused, so an UNPAUSED rule a user genuinely named "... (Paused)" keeps its name; a
// paused one so named is indistinguishable from the decoration and is stripped.
private String _vrbBareName(Object raw, boolean paused) {
    def s = stripAppConfigHtml(raw)?.toString()
    if (s != null && paused && s.endsWith("(Paused)")) {
        return s.substring(0, s.length() - "(Paused)".length()).trim()
    }
    return s
}

private boolean _vrbNameMatches(Map after, String requestedName) {
    // Read-back name comparison for every save path. A PAUSED rule comes back carrying the hub's
    // own "(Paused)" decoration, usually HTML-wrapped ("Name <span
    // class='text-red'>(Paused)</span>"), so a literal compare reported verified:false on a write
    // that had landed -- and on create that left the child installed, inviting a duplicate on
    // retry. Accept the literal name OR the decoration-stripped one; a name that differs beyond
    // the suffix still fails.
    if (after == null) return false
    return stripAppConfigHtml(after.data?.name)?.toString() == requestedName ||
           _vrbBareName(after.data?.name, after.data?.rulePaused == true) == requestedName
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
    // Read-only. No appId -> list every Visual Rules Builder rule (appId, name, version, disabled,
    // paused). With appId -> full definition in whichever serialization the rule speaks, plus the
    // editor decomposition and activation state for a graph rule.
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
        if (detected.format == "graph") {
            // `activated` is what tells the caller the rule actually RUNS: VRB2 stores an invalid
            // document as an inactive draft, so a successful read with validationErrors is a rule
            // that exists but is switched off.
            out.activated = !(out.validationErrors) && out.runtimeActive != false
            out.remove("runtimeActive")
            if (out.definition instanceof Map) {
                // The editor form is the shape to modify and send back. A stored graph the
                // builder cannot open must not fail the READ -- report why and keep the raw graph.
                try {
                    out.editor = _vrb2Decompose(out.definition)
                } catch (Exception e) {
                    out.editorError = e.message
                }
            }
            if (out.definition == null && !out.definitionParseError) {
                out.note = "This graph rule has an empty definition (freshly created, never saved)."
            }
        }
        return out
    } catch (Exception e) {
        mcpLog("warn", "vrb", "hub_get_visual_rule failed for ${appId}: ${e.message}")
        return [success: false, appId: appId, error: "Could not read Visual Rule ${appId}: ${e.message}"]
    }
}

def toolSetVisualRule(args) {
    // Attach the unified rule-health report to every response (success AND failure) that
    // resolves to a concrete rule id, mirroring hub_set_rule (issue #254 follow-up). For a
    // graph Visual Rule _rmCheckRuleHealth reads validationErrors as the broken verdict; the
    // confirm pre-flight throw is left to propagate (it must surface as -32602, not a result).
    // We re-call _rmCheckRuleHealth (one or two extra localhost GETs for a graph rule) rather
    // than synthesizing the report from the data _toolSetVisualRuleImpl already holds: that keeps
    // a SINGLE source of truth for the health shape (no drift), and the cost is a cheap localhost
    // read on an infrequent write.
    def result = _toolSetVisualRuleImpl(args)
    try {
        // Prefer the id the impl resolved; fall back to the caller-supplied appId so an EDIT
        // failure whose error map omits appId (e.g. a graph-save rejection) still carries health
        // for the rule the caller named (codex review). Only an early CREATE failure — no appId
        // given and no id resolved — legitimately gets none.
        def rid = result?.appId ?: args?.appId
        if (rid != null && result instanceof Map && !result.containsKey("health")) {
            result.health = _rmCheckRuleHealth(rid as Integer)
        }
    } catch (Exception ignored) { /* best effort — never let a health read mask the tool result */ }
    return result
}

private Map _toolSetVisualRuleImpl(args) {
    requireDestructiveConfirm(args?.confirm as Boolean)
    def name = args?.name?.toString()?.trim()
    def hasDefinition = args?.definition != null
    def hasPaused = args?.paused != null
    def paused = args?.paused == true

    if (args?.appId == null) {
        // CREATE: the definition's shape selects the builder version, we ask the parent for a
        // child of that version, then save the definition into it. Both name and definition are
        // required so no unnamed empty shells are left behind.
        if (!name) throw new IllegalArgumentException("name is required when creating a Visual Rule (appId omitted).")
        if (!hasDefinition) throw new IllegalArgumentException("definition is required when creating a Visual Rule. See hub_get_tool_guide(section='visual_rule_reference') for the schema.")
        def normalized = _vrbNormalizeDefinition(args.definition)
        // Pre-flight a 2.0 document BEFORE the child exists, so a malformed definition never
        // strands an orphan shell. A CLASSIC definition needs no 2.0 pre-flight: it is saved
        // as-is into the 1.0 child it asked for. It is only translated when the create falls
        // back to firmware that can build nothing but 2.0, and that check runs after the create.
        // Nothing exists yet, so a bad definition is a plain argument error (-32602) here; the
        // structured refusal envelope is reserved for the legacy-create fallback below, where the
        // shell already exists and a throw would follow a side effect.
        def preflight = null
        if (normalized.format == "classic") {
            _vrbValidateClassicShape(normalized.map)
        } else {
            preflight = _vrbResolveTargetDefinition("graph", normalized.format, normalized.map)
            if (preflight.ok == false && preflight.validationErrors != null) {
                throw new IllegalArgumentException(_vrbPreflightMessage(preflight.validationErrors))
            }
        }
        // The definition's shape picks the builder version: a classic node-list gets a VRB 1.0
        // child, an editor or graph document gets a 2.0 one. No translation happens when the
        // requested version can be created -- only the legacy fallback can land on the other one.
        def created
        try {
            created = _vrbCreateChild(normalized.format == "classic" ? "1.0" : "2.0")
        } catch (Exception e) {
            mcpLog("error", "vrb", "hub_set_visual_rule create failed: ${e.message}")
            return [success: false, error: "Creating the Visual Rule child app failed: ${e.message}"]
        }
        def resolved = (created.format == "graph" && preflight?.ok == true) ? preflight :
                _vrbResolveTargetDefinition(created.format, normalized.format, normalized.map)
        if (resolved.ok == false) {
            // Either the definition failed the 2.0 pre-flight, or it is a 2.0 document on a hub
            // whose builder is still 1.0. Delete the orphan shell rather than stranding an empty
            // unnamed rule.
            def cleanupNote = _vrbTryCleanupShell(created.appId)
            return resolved.validationErrors != null ?
                    _vrbPreflightRefusal(null, resolved.validationErrors, cleanupNote) :
                    _vrbFormatMismatchRefusal(null, normalized.format, cleanupNote)
        }
        try {
            def out = _vrbApplySave(created.appId, created.format, name, resolved.definition, hasPaused ? paused : null, false, true)
            if (created.version) out.version = created.version
            if (resolved.translatedFrom) out.translatedFrom = resolved.translatedFrom
            return out
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
        if (normalized.format == "classic") _vrbValidateClassicShape(normalized.map)
        def resolved = _vrbResolveTargetDefinition(detected.format, normalized.format, normalized.map)
        if (resolved.ok == false) {
            if (resolved.validationErrors != null) {
                // Nothing has been written: a pre-flight failure on edit is an argument error.
                throw new IllegalArgumentException(_vrbPreflightMessage(resolved.validationErrors))
            }
            return _vrbFormatMismatchRefusal(appId, normalized.format,
                    "Fetch the current shape with hub_get_visual_rule(appId=${appId}), or delete and recreate the rule.")
        }
        try {
            def result = _vrbApplySave(appId, detected.format, name ?: detected.data.name?.toString(), resolved.definition, hasPaused ? paused : null, detected.data.rulePaused == true, false)
            if (resolved.translatedFrom) result.translatedFrom = resolved.translatedFrom
            def previousDefinition = detected.format == "graph" ? detected.data.definition :
                    [whenNodes: detected.data.whenNodes, thenNodes: detected.data.thenNodes, elseNodes: detected.data.elseNodes]
            // A never-saved graph has no prior definition. Omit the optional recovery aid
            // instead of emitting null against its object-shaped wire contract.
            if (previousDefinition instanceof Map) result.previousDefinition = previousDefinition
            return result
        } catch (Exception e) {
            mcpLog("error", "vrb", "hub_set_visual_rule edit failed for ${appId}: ${e.message}")
            return [success: false, appId: appId, error: "Saving Visual Rule ${appId} failed: ${e.message}"]
        }
    }

    // Rename and/or pause without replacing the definition: re-save the EXISTING nodes under
    // the new name (the save endpoints have no rename-only verb), then apply the pause flag.
    try {
        // Strip the hub's "(Paused)" decoration off the fallback. On a RESUME the caller sends no
        // name, so requestedName falls back to the name read BEFORE the write -- and the rule was
        // paused then, so that string carries the decoration while the post-resume read-back is
        // bare. _vrbNameMatches strips `actual` but not `requestedName`, and its decoration-
        // tolerant branch needs rulePaused==true, which the resume just made false: every resume
        // of a paused rule therefore reported verified:false on a write that had landed.
        def requestedName = (name ?: _vrbBareName(detected.data?.name, detected.data?.rulePaused == true))?.toString()
        def classicBodyCarriedPause = false
        if (name && name != detected.data.name?.toString()) {
            // A never-saved graph rule reads back a blank ruleJson. Mirror what the Rule Builder 2.0
            // UI saves for a rule with nothing in it rather than POSTing "": its graph composer
            // (vue-hub2-visual-rule-builder-20, platform 2.5.1.177) always emits the trigger-merge
            // and decision structure nodes -- with no triggers, conditions or actions that is the
            // whole graph. The older builder's `sampleTrigger` placeholder template is not a saved
            // shape on this platform; the validator rejects its `{id, type, deviceIds}` nodes.
            def emptyTemplate = '{"version":1,"nodes":[{"id":"trigger-merge","kind":"merge","type":"triggerMerge","config":{}},{"id":"decision","kind":"decision","type":"all","config":{"conditions":[]}}],"edges":[{"from":"trigger-merge","to":"decision","port":"next"}]}'
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
        def nameOk = after != null && _vrbNameMatches(after, requestedName)
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
    def savedMeta = null
    if (format == "graph") {
        def definitionJson = groovy.json.JsonOutput.toJson(definition)
        def saved = _vrbSaveGraph(appId, name, definitionJson)
        if (saved.success == false) {
            mcpLog("warn", "vrb", "Graph save rejected for ${appId}: ${saved.errorMessage} ${saved.validationErrors ?: ''}")
            def failed = [success: false, error: "Hub rejected the graph save: ${saved.errorMessage}",
                          validationErrors: saved.validationErrors, activated: false,
                          note: created ? _vrbTryCleanupShell(appId) : "The rule's previous definition is untouched."]
            if (saved.storageError != null) failed.storageError = saved.storageError
            return failed
        }
        savedMeta = saved
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
    if (format == "graph" && !validationErrors && after?.data?.validationErrors instanceof List && after.data.validationErrors) {
        // The POST body can be lost (relay drop, non-JSON 200) while the save landed; the
        // read-back then holds the only copy of the hub's verdict.
        validationErrors = after.data.validationErrors
    }
    def nameOk = after != null && _vrbNameMatches(after, name)
    def pauseOk = pausedRequested == null || ((after?.data?.rulePaused == true) == pausedRequested)
    def countsOk = after != null && _vrbDefinitionCountsMatch(format, definition, after.data)
    def verified = nameOk && pauseOk && countsOk
    def out = [success: verified, appId: appId, format: format, created: created,
               name: after?.data?.name, rulePaused: after?.data?.rulePaused == true, verified: verified]
    if (format == "graph") {
        // VRB2 separates STORAGE from ACTIVATION: an invalid document is stored as an inactive
        // draft. activatedSuccessfully is authoritative when the firmware sends it; otherwise an
        // empty validationErrors list is the only activation evidence available.
        out.activated = savedMeta?.containsKey("activatedSuccessfully") ?
                (savedMeta.activatedSuccessfully == true) :
                (validationErrors.isEmpty() && after?.data?.runtimeActive != false)
        if (savedMeta?.activationError != null) out.activationError = savedMeta.activationError
        if (savedMeta?.storageError != null) out.storageError = savedMeta.storageError
        def issues = savedMeta?.validationIssues ?: after?.data?.validationIssues
        if (issues) out.validationIssues = issues
        def referenced = savedMeta?.containsKey("referencedDeviceIds") ?
                savedMeta.referencedDeviceIds : after?.data?.referencedDeviceIds
        if (referenced != null) out.referencedDeviceIds = referenced
        // The opaque optimistic-concurrency token: prefer the one the save just minted.
        def revision = savedMeta?.revision ?: after?.data?.revision
        if (revision != null) out.revision = revision
        if (after?.data?.runtimeGraph != null) out.runtimeGraph = after.data.runtimeGraph
    }
    if (validationErrors) {
        out.validationErrors = validationErrors
        out.note = "Stored as an INACTIVE DRAFT: the hub reported validation errors, so the rule was saved but NOT activated and will not run until they are fixed. See hub_get_tool_guide(section='visual_rule_reference')."
    } else if (format == "graph" && out.activated == false) {
        // Storage succeeded, validation passed, and the rule STILL is not running: activation
        // threw on the hub (activationError) or no runtime exists. Say so with the cause.
        out.note = "Stored but NOT activated: " + (out.activationError ? "the hub reported an activation error -- ${out.activationError}." : "the hub reports no active runtime for this rule.") +
                " Re-read with hub_get_visual_rule(appId=${appId}); re-saving the definition retries activation."
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
        // Same predicate as the field guard below -- _vrbFetchGraph parses ruleJson without a
        // Map check, so a stored array yields a non-null List that is never attached.
        note = predelete instanceof Map ? "To recreate this rule, call hub_set_visual_rule with the predeleteDefinition." :
                                          "This rule had no readable definition (never saved, or not a rule object), so there is nothing to recreate."
    }
    mcpLog("info", "vrb", "Deleted Visual Rule ${appId} ('${detected.data.name}') verified=${verified}")
    def result = [success: verified, appId: appId, name: detected.data.name, format: detected.format,
                  verified: verified, note: note]
    if (predelete instanceof Map) result.predeleteDefinition = predelete
    return result
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
    // The hub decorates a paused rule's label with an HTML-wrapped "(Paused)"; capture prefers the
    // rule's own undecorated name, but strip the decoration defensively (HTML or plain) so an older
    // decorated-label snapshot can't bake it into the restored rule's name.
    if (snapshot.vrbRulePaused == true) name = _vrbBareName(name, true) ?: name
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
        String targetFormat
        String createdVersion = null
        // A 1.0 snapshot can be replayed onto a 2.0 target by translating it; the reverse cannot
        // (2.0-only structure has no 1.0 expression), so a graph snapshot on a classic target
        // still refuses.
        def translatable = { String target -> target == "graph" && vrbFormat == "classic" }
        if (existing != null) {
            if (existing.format != vrbFormat && !translatable(existing.format)) {
                return [success: false, type: "visual-rule", originalRuleId: savedId, backupFile: fileName,
                        error: "App ${savedId} still exists but is ${existing.format}-format; the snapshot is ${vrbFormat}-format.",
                        note: "Delete the rule first (hub_delete_visual_rule) and re-run the restore, or recreate manually with hub_set_visual_rule."]
            }
            targetId = savedId
            recreated = false
            targetFormat = existing.format
        } else {
            // Recreate at the snapshot's OWN version -- a classic snapshot gets a 1.0 child, a
            // graph snapshot a 2.0 one -- so a replay never silently changes the rule's builder.
            def created = _vrbCreateChild(vrbFormat == "classic" ? "1.0" : "2.0")
            if (created.format != vrbFormat && !translatable(created.format)) {
                def cleanupNote = _vrbTryCleanupShell(created.appId)
                return [success: false, type: "visual-rule", originalRuleId: savedId, backupFile: fileName, hubNativeFormat: created.format,
                        error: "This hub's Visual Rules Builder now creates ${created.format}-format rules; the snapshot is ${vrbFormat}-format and cannot be replayed.",
                        note: "Recreate the rule manually with hub_set_visual_rule using a ${created.format} definition. ${cleanupNote}"]
            }
            targetId = created.appId
            recreated = true
            targetFormat = created.format
            createdVersion = created.version
        }

        String translatedFrom = null
        if (targetFormat != vrbFormat) {
            // Only the translated path is pre-flighted. A same-format graph snapshot is replayed
            // untouched: it validated on the hub once, and our structural check is not the oracle.
            def resolved = _vrbResolveTargetDefinition(targetFormat, vrbFormat, definition)
            if (resolved.ok == false) {
                def cleanupNote = recreated ? _vrbTryCleanupShell(targetId) : "Nothing was written to app ${targetId}."
                return [success: false, type: "visual-rule", originalRuleId: savedId, backupFile: fileName,
                        error: "The snapshot's classic 1.0 definition was translated for this Visual Rule Builder 2.0 target, but the result failed pre-flight validation; nothing was saved.",
                        validationErrors: resolved.validationErrors,
                        note: "Recreate the rule manually with hub_set_visual_rule -- see hub_get_tool_guide(section='visual_rule_reference'). ${cleanupNote}"]
            }
            definition = resolved.definition
            translatedFrom = resolved.translatedFrom
        }

        def saved = _vrbApplySave(targetId, targetFormat, name, definition,
                pausedRequested, existing?.data?.rulePaused == true, recreated)
        def out = [success: saved.success, type: "visual-rule", ruleId: targetId, originalRuleId: savedId,
                   recreated: recreated, backupFile: fileName, format: targetFormat,
                   name: saved.name, rulePaused: saved.rulePaused, verified: saved.verified]
        if (createdVersion) out.version = createdVersion
        if (translatedFrom) out.translatedFrom = translatedFrom
        if (saved.containsKey("activated")) out.activated = saved.activated
        if (saved.activationError != null) out.activationError = saved.activationError
        if (saved.error) out.error = saved.error
        if (saved.validationErrors) out.validationErrors = saved.validationErrors
        out.note = saved.success ?
                (recreated ? "Visual Rule was deleted; recreated with new id ${targetId} and its definition replayed." :
                             "Visual Rule definition restored in place.") :
                (saved.note ?: "Restore did not verify -- inspect with hub_get_visual_rule(appId=${targetId}).")
        // A restored rule that is stored but NOT running must say so -- the replay verified, the
        // automation still is not active.
        if (saved.success && saved.activated == false && saved.note) out.note = "${out.note} ${saved.note}".toString()
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
            description: "List Visual Rules Builder rules (omit appId; each entry: appId, name, version, disabled, paused) or read one rule's full definition.[[FLAT_TRIM]] List-mode `paused` is detected from the rule's name suffix (no cross-check) and `version` ('2.0' / '1.0') comes from the hub's own child type; call with appId for the authoritative `rulePaused`. A single-rule read returns `format`: 'graph' (VRB 2.0, {version, nodes, edges}) or 'classic' (VRB 1.0, {whenNodes, thenNodes, elseNodes}). A graph read ALSO returns `editor` -- the same rule as {triggers, conditions, decisionType, thenActions, elseActions, commonActions}, the shape to modify and hand straight back to hub_set_visual_rule -- plus `activated` (false means the hub stored it as an inactive draft; see validationErrors).[[/FLAT_TRIM]]",
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
                    rules: [type: "array", description: "List mode: [{appId, name, disabled, paused}] (paused is name-suffix detected; appId read gives authoritative rulePaused). An OMITTED paused or disabled means it was undeterminable from the node data (null name / absent disabled key)."],
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
            description: "Create or update a Visual Rules Builder rule.[[FLAT_TRIM]] VRB is the PRIMARY rule engine for new automations; VRB 2.0 adds AND/OR condition gates, then/else branches and a shared action tail. Most automations fit it; use hub_set_rule (Rule Machine) only for complex ones (nested logic, loops, variables, custom device commands). On create the definition's shape picks the builder version (editor/graph -> 2.0, classic -> 1.0). Editor and graph definitions are structurally pre-flight validated before anything is created or saved, so a malformed 2.0 rule never strands an empty shell; a classic definition is saved as-is and validated by the hub.[[/FLAT_TRIM]] Omit appId to create (name + definition required). Pre-flight: backup within 24h + confirm=true. Schemas + worked example: hub_get_tool_guide(section='visual_rule_reference').",
            inputSchema: [
                type: "object",
                properties: [
                    appId: [type: "integer", description: "Existing Visual Rule app id to edit. Omit to create."],
                    name: [type: "string", description: "Rule name. Required on create; renames on edit."],
                    definition: [type: "object", description: "Full rule definition (wholesale replacement). RECOMMENDED shape: the editor form {triggers, conditions, decisionType, thenActions, elseActions, commonActions}.[[FLAT_TRIM]] A raw 2.0 graph ({version, nodes, edges}) and a classic 1.0 node-list ({whenNodes, thenNodes, elseNodes}) are also accepted. On CREATE the shape picks the builder version: classic makes a Visual Rule Builder 1.0 rule, editor/graph a 2.0 one. On EDIT a 2.0 rule takes any of the three (a classic node-list is translated and the response says translatedFrom); a 1.0 rule takes classic only.[[/FLAT_TRIM]] Field schemas: hub_get_tool_guide(section='visual_rule_reference')."],
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
                    health: [type: "object", description: "Rule-health report (same shape as hub_get_rule_health's output) attached to every response that resolves to a rule id — early CREATE failures (format mismatch, save-after-create) carry no appId and omit it. For a graph Visual Rule broken=true means non-empty validationErrors."],
                    error: [type: "string"],
                    note: [type: "string"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_delete_visual_rule",
            description: "Delete a Visual Rules Builder rule by appId.[[FLAT_TRIM]] Type-gated: refuses ids that are not VRB rules (use hub_delete_native_app for RM rules / other classic apps). Returns the pre-delete definition for recovery via hub_set_visual_rule.[[/FLAT_TRIM]] Pre-flight: backup within 24h + confirm=true.",
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

def _readOnlyToolNames_partVisualRules() {
    // Read-only classification membership for this library's tools, contributed to the
    // app's getReadOnlyToolNames() aggregator (issue #209: per-tool metadata lives with
    // the tool). A tool absent from every part list is write+destructive by default.
    return [
        // Visual Rules Builder (read)
        "hub_get_visual_rule"
    ]
}

def _idempotentWriteToolNames_partVisualRules() {
    // Retry-safe writes (MCP idempotentHint) for this library's tools -- contributed to the
    // app's getIdempotentWriteToolNames() aggregator; see the classification rules there.
    return [
        // Visual Rules: delete-style retry finds nothing to do (clean "No
        // installed app" envelope, no snapshot minting). hub_set_visual_rule
        // is an upsert whose no-appId mode CREATES -- non-idempotent.
        "hub_delete_visual_rule"
    ]
}

def _toolDisplayMeta_partVisualRules() {
    // Human-facing title/summary per tool (MCP annotations.title + the Advanced per-tool
    // overrides menu) -- merged into the app's getToolDisplayMeta() aggregator (issue #209).
    return [
        // Visual Rules Builder
        hub_get_visual_rule: [title: "Get Visual Rule", summary: "List Visual Rules Builder rules or read one rule's full definition."],
        hub_set_visual_rule: [title: "Author Visual Rule", summary: "Create or edit a Visual Rules Builder rule, including rename and pause/resume."],
        hub_delete_visual_rule: [title: "Delete Visual Rule", summary: "Delete a Visual Rules Builder rule, returning its definition for recovery."]
    ]
}
