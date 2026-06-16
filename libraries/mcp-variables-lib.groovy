library(name: "McpVariablesLib", namespace: "mcp", author: "kingpanther13", description: "Hub variable + connector tool implementations (list/get/set/create/delete variables, connectors, change history) plus the variable event-subscription handlers for the MCP Rule Server; #include'd by the main app. Gateway entries and dispatch cases stay in the app; tool definitions, implementations, domain helpers, and per-tool metadata live here.")

private void _refreshHubVarInUseRegistrations() {
    Set<String> currentVars = [] as Set
    Set<String> hubVarNames
    try {
        hubVarNames = (getAllGlobalVars()?.keySet() ?: []) as Set<String>
    } catch (Exception e) {
        // Surface as ERROR — when this fails the in-use safety net is stale,
        // and hub_delete_variable's pre-deletion warning won't fire. Users need
        // to know the safety registrations weren't refreshed.
        mcpLog("error", "hub-vars",
            "_refreshHubVarInUseRegistrations: getAllGlobalVars failed (${e.class.simpleName}: ${e.message}) -- " +
            "in-use safety registrations are STALE. hub_delete_variable's hub-side warning may not fire until " +
            "this resolves and updated() runs again.")
        return
    }
    if (!hubVarNames) {
        // No hub vars at all — clear any stale registrations and bail.
        def previous = (atomicState.inUseHubVars ?: []) as List<String>
        previous.each { name ->
            try { removeInUseGlobalVar(name) } catch (Exception e) { /* idempotent */ }
        }
        atomicState.inUseHubVars = []
        return
    }
    try {
        getChildApps()?.each { child ->
            def ruleData = null
            try { ruleData = child.getRuleData() } catch (Exception e) { /* not an MCP rule child */ }
            if (ruleData) {
                def serialized = groovy.json.JsonOutput.toJson(ruleData)
                hubVarNames.each { varName ->
                    // Check for the JSON-quoted form so `temp` doesn't match
                    // `temperature` / `attempt`. Names live as JSON values
                    // (and sometimes keys) in the serialized blob, so the
                    // `"<name>"` form is a reliable word-boundary proxy.
                    def needle = "\"${varName}\""
                    if (serialized?.contains(needle)) {
                        currentVars << varName
                    }
                }
            }
        }
    } catch (Exception e) {
        logDebug("_refreshHubVarInUseRegistrations: getChildApps() scan failed: ${e.class.simpleName}: ${e.message}")
        return
    }

    def previous = ((atomicState.inUseHubVars ?: []) as List<String>) as Set<String>
    def toAdd = currentVars - previous
    def toRemove = previous - currentVars

    toAdd.each { name ->
        // ERROR level: failure here means Hubitat won't warn the user before
        // they delete a variable a rule depends on. Warn-level would be
        // dropped at the default mcpLogLevel="error" config.
        try { addInUseGlobalVar(name) }
        catch (Exception e) { mcpLogError("hub-vars", "addInUseGlobalVar('${name}') failed -- in-use safety warning will not surface for this var", e) }
    }
    toRemove.each { name ->
        try { removeInUseGlobalVar(name) }
        catch (Exception e) { mcpLogError("hub-vars", "removeInUseGlobalVar('${name}') failed -- stale in-use registration will linger", e) }
    }

    atomicState.inUseHubVars = (currentVars as List).sort()
    if (toAdd || toRemove) {
        mcpLog("info", "hub-vars", "in-use registrations refreshed: added=${toAdd.sort()}, removed=${toRemove.sort()}, total=${currentVars.size()}")
    }
}

private void _subscribeToAllHubVariables() {
    def vars
    try { vars = getAllGlobalVars() }
    catch (Exception e) {
        logDebug("_subscribeToAllHubVariables: getAllGlobalVars threw ${e.class.simpleName}: ${e.message}")
        return
    }
    if (location == null) return  // unit-test environment safety
    vars?.keySet()?.each { varName ->
        try {
            subscribe(location, "variable:${varName}", "handleHubVariableEvent")
        } catch (Throwable e) {
            // Don't let one bad subscribe break the whole loop. The hub
            // sometimes rejects names with characters that getAllGlobalVars
            // returned but subscribe() refuses; log and continue. Catch
            // Throwable because hubitat_ci's validator throws AssertionError.
            // ERROR level: a persistent failure here means hub_list_variable_changes
            // silently misses changes for this variable; warn-level would be
            // dropped at default mcpLogLevel="error".
            mcpLog("error", "hub-vars", "subscribe to variable:${varName} failed: ${e.message} -- hub_list_variable_changes will not capture changes for this var")
        }
    }
}

def renameVariable(String oldName, String newName) {
    mcpLog("info", "hub-vars", "renameVariable callback: '${oldName}' -> '${newName}'")
    def history = atomicState.variableHistory ?: []
    def rewrote = false
    history = history.collect { entry ->
        if (entry?.name == oldName) {
            rewrote = true
            // Groovy Map +: rightmost map's keys override; produces a new
            // map with `name` updated and other fields preserved.
            return entry + [name: newName]
        }
        return entry
    }
    if (rewrote) atomicState.variableHistory = history
    // Re-subscribe to the new name. The old "variable:OLD" event will
    // never fire again since the variable is gone, but Hubitat's
    // unsubscribe semantics mean it's harmless to leave it in place.
    // Catch Throwable because the hubitat_ci validator throws AssertionError
    // (not Exception) when location is null in unit-test environments;
    // history rewrite is the important part and shouldn't be aborted by a
    // subscription registration failure.
    if (location != null) {
        try { subscribe(location, "variable:${newName}", "handleHubVariableEvent") }
        catch (Throwable e) {
            mcpLog("error", "hub-vars", "post-rename subscribe to variable:${newName} failed: ${e.message} -- history capture for renamed var will lag until next initialize()")
        }
    }
}

def handleHubVariableEvent(evt) {
    if (evt == null) return
    def varName = evt.name?.toString()
    if (varName?.startsWith("variable:")) {
        varName = varName.substring("variable:".length())
    }
    if (!varName) return

    def entry = [
        name: varName,
        value: evt.value,
        timestamp: now(),
        descriptionText: evt.descriptionText?.toString()
    ]
    // Best-effort, non-transactional read-append-cap-write. atomicState gives
    // per-write durability, not read-then-write atomicity, and the sandbox has
    // no CAS primitive: concurrent variable: events may read the same snapshot
    // and drop an append. Acceptable for a best-effort history buffer.
    def history = atomicState.variableHistory ?: []
    history << entry
    // Cap the buffer. 200 entries is enough to survive an MCP-tool
    // round-trip plus a few minutes of bursty change activity without
    // blowing up state size on hubs with many vars.
    def cap = 200
    if (history.size() > cap) {
        history = history[(history.size() - cap)..-1]
    }
    atomicState.variableHistory = history
}

def toolGetVariableHistory(args) {
    def history = atomicState.variableHistory ?: []
    def filtered = history
    if (args?.name) {
        def n = args.name.toString()
        filtered = filtered.findAll { it?.name == n }
    }
    if (args?.sinceMs != null) {
        def since = args.sinceMs as Long
        filtered = filtered.findAll { (it?.timestamp ?: 0L) >= since }
    }
    def limit = (args?.limit != null) ? (args.limit as Integer) : 50
    if (limit < 1) limit = 1
    // Most-recent first; cap at limit.
    def recent = filtered.reverse().take(limit)
    return [
        entries: recent,
        total: recent.size(),
        bufferSize: history.size(),
        bufferCap: 200
    ]
}

def toolListVariables(args = null) {
    // Modern Hub Variable API (issue #92): getAllGlobalVars() sees every hub
    // variable, not just the connector-exposed subset that the legacy
    // getAllGlobalConnectorVariables() returned. Each entry is shaped like
    // [name, type, value, deviceId, attribute] — deviceId/attribute populated
    // when the variable has a Connector device, null otherwise.
    def hubVariables = []
    String hubVarsError = null
    try {
        def allVars = getAllGlobalVars()
        if (allVars) {
            hubVariables = allVars.collect { name, var ->
                [
                    name: name,
                    value: var?.value,
                    type: var?.type,
                    deviceId: var?.deviceId,
                    attribute: var?.attribute,
                    source: "hub"
                ]
            }
        }
    } catch (Exception e) {
        // Surface to mcpLog (not just logDebug) so a "no hub variables" response can be
        // distinguished from "hub variable API broke" via hub_get_debug_logs.
        hubVarsError = e.message ?: e.toString()
        mcpLog("warn", "hub-vars", "hub_list_variables: getAllGlobalVars() failed: ${hubVarsError} -- returning empty hubVariables", null, [details: [error: hubVarsError]])
    }

    def ruleVariables = state.ruleVariables?.collect { name, value ->
        [name: name, value: value, source: "rule_engine"]
    } ?: []

    def cursor = args?.cursor
    def paged = _paginateList(hubVariables, cursor, 100, "hub_list_variables")
    // Both per-list totals are always emitted so a caller can distinguish "1000 hub
    // vars + 0 rule vars" from "0 hub vars + 1000 rule vars" without needing cursor
    // context. `total` is the legacy summed field; the per-list fields are the
    // primary contract going forward.
    def result = [
        hubVariables: paged.page,
        ruleVariables: ruleVariables,
        totalHubVariables: hubVariables.size(),
        totalRuleVariables: ruleVariables.size(),
        total: hubVariables.size() + ruleVariables.size()
    ]
    if (hubVarsError) result.hubVariablesError = hubVarsError
    if (cursor != null && paged.nextCursor != null) result.nextCursor = paged.nextCursor
    return result
}

def toolGetVariable(name) {
    try {
        def hubVar = getGlobalVar(name)
        if (hubVar != null) {
            return [
                name: name,
                value: hubVar.value,
                type: hubVar.type,
                deviceId: hubVar.deviceId,
                attribute: hubVar.attribute,
                source: "hub"
            ]
        }
    } catch (Exception e) {
        logDebug("Hub variable '${name}' lookup threw ${e.class.simpleName}: ${e.message}")
    }

    def ruleVar = state.ruleVariables?.get(name)
    if (ruleVar != null) {
        return [name: name, value: ruleVar, source: "rule_engine"]
    }

    throw new IllegalArgumentException("Variable not found: ${name}")
}

// Hub variable name validation. Hubitat's UI rejects ' " \ ~ [ : ] < > and
// blank names. Reproduced here so the create tool fails fast with a clean
// error before we touch the wizard. Returned via getters because the
// Hubitat sandbox rejects `private static final` at script scope.
private List getHubVarForbiddenChars() {
    return ["'", '"', '\\', '~', '[', ':', ']', '<', '>']
}

// Sentinel appended to the IllegalStateException message thrown by
// _rmClearActions on the retry-window-expired path. The action-mutation
// dispatcher catches the throw, detects this token, and routes to the
// structured eventual-consistency response shape (asyncCommitLikely:true).
// Centralized so the throw site and every strip site share a single source
// of truth -- a typo or drift would silently fall back to the legacy flat
// error shape and lose the data-loss-protection contract. Leading space is
// intentional: stripping " [asyncCommitLikely]" removes the separator too.
private String getAsyncCommitMarker() {
    return " [asyncCommitLikely]"
}

private List getHubVarTypes() {
    return ["Number", "Decimal", "String", "Boolean", "DateTime"]
}

private void _validateHubVarName(String name) {
    if (!name?.trim()) {
        throw new IllegalArgumentException("Variable name is required")
    }
    def bad = getHubVarForbiddenChars().findAll { c -> name.contains(c) }
    if (bad) {
        throw new IllegalArgumentException(
            "Variable name '${name}' contains forbidden character(s): ${bad.join(' ')}. " +
            "Hubitat rejects: ' \" \\ ~ [ : ] < >")
    }
}

private String _validateHubVarType(String type) {
    def types = getHubVarTypes()
    def match = types.find { it.equalsIgnoreCase(type) }
    if (!match) {
        throw new IllegalArgumentException(
            "Variable type '${type}' is invalid. Must be one of: ${types.join(', ')}")
    }
    return match  // canonical casing
}

// Wizard-priming for the Hub Variables system app. Verified on firmware
// 2.5.0.126: clicks via _rmClickAppButton silently no-op unless the app's
// wizard state is "warmed" first. The configure-json + statusJson GET pair
// reliably primes it; a single bare GET does not.
//
// Non-private so test specs can override via script.metaClass.
def _primeHubVarsWizard(Integer appId, String context) {
    try {
        hubInternalGet("/installedapp/configure/json/${appId}")
        hubInternalGet("/installedapp/statusJson/${appId}")
    } catch (Exception e) {
        logDebug("${context}: _primeHubVarsWizard for ${appId} threw ${e.class.simpleName}: ${e.message}")
    }
}

// Non-private so test specs can override via script.metaClass — internal calls
// from this script's other methods would bypass the metaClass dispatch on a
// private method and hit the real implementation, which makes mocking the
// hub-side discovery painful in unit tests.
def _findHubVariablesAppId() {
    // Stage 1: atomicState cache.
    def cached = atomicState.hubVarsAppId
    if (cached != null) {
        try { return cached.toString().toInteger() } catch (NumberFormatException e) {
            mcpLog("warn", "hub-vars", "Invalid cached hubVarsAppId '${cached}' -- rediscovering")
            atomicState.remove("hubVarsAppId")
        }
    }

    // Stage 2: name-addressed direct alias -- a single redirect chain, no
    // payload walking. Safe to probe: hubVariables is a singleton, so the
    // chain's create hop is get-or-create and never strands a transient
    // instance.
    def directId = _resolveDirectAppId("hubVariables")
    if (directId != null) {
        atomicState.hubVarsAppId = directId
        mcpLog("info", "hub-vars", "Discovered Hub Variables app id: ${directId} (via /installedapp/direct/hubVariables)")
        return directId
    }

    // Stage 3: /hub2/appsList walk.
    try {
        def responseText = hubInternalGet("/hub2/appsList")
        if (responseText) {
            def parsed = new groovy.json.JsonSlurper().parseText(responseText)
            def found = null
            def recurse
            recurse = { node ->
                def d = node?.data
                if (found == null && d?.type == "Hub Variables" && d?.id != null) {
                    found = d
                }
                node?.children?.each { c -> recurse(c) }
            }
            (parsed?.apps ?: []).each { a -> recurse(a) }
            if (found?.id != null) {
                def id = found.id.toString().toInteger()
                // The feed's type label is the only evidence on this path, so
                // confirm via configure/json before caching -- a wrong cached id
                // would break every wizard-driving tool until manually cleared.
                // Best-effort: a definitive name mismatch rejects; a fetch error
                // must NOT discard an otherwise name-keyed match (transient HTTP
                // trouble would lock the tools out entirely).
                def verified = true
                try {
                    def cfgText = hubInternalGet("/installedapp/configure/json/${id}")
                    if (cfgText) {
                        def cfg = new groovy.json.JsonSlurper().parseText(cfgText)
                        if (cfg?.app?.appType?.name != "Hub Variables") verified = false
                    }
                } catch (Exception e) {
                    logDebug("_findHubVariablesAppId: verify fetch for ${id} threw ${e.class.simpleName}: ${e.message}")
                }
                if (verified) {
                    atomicState.hubVarsAppId = id
                    mcpLog("info", "hub-vars", "Discovered Hub Variables app id: ${id} (via /hub2/appsList)")
                    return id
                }
                mcpLog("warn", "hub-vars", "appsList candidate ${id} failed configure/json verification -- not caching")
            }
        }
    } catch (Exception e) {
        logDebug("_findHubVariablesAppId: /hub2/appsList walk threw ${e.class.simpleName}: ${e.message}")
    }

    throw new IllegalStateException(
        "Hub Variables system app not found via the /installedapp/direct/hubVariables redirect chain " +
        "OR the /hub2/appsList walk. This should not happen on a normally-functioning hub -- the app " +
        "is system-installed. Check that Hub Variables is enabled (Settings > Hub Variables).")
}

def toolCreateVariable(args) {
    requireDestructiveConfirm(args.confirm)

    // Bulk form: variables=[{name,type,value}, ...]. Mutually exclusive with
    // the single name/type/value form. Each item is created SEQUENTIALLY
    // through the shared Hub Variables system app -- the create wizard has a
    // known post-write race that makes rapid/parallel creates spuriously fail,
    // so the loop never parallelizes.
    if (args.variables != null) {
        if (args.name != null || args.type != null || args.value != null) {
            throw new IllegalArgumentException(
                "Provide EITHER the single name/type/value form OR the bulk variables array -- not both.")
        }
        return _createVariablesBulk(args.variables)
    }

    def name = args.name?.toString()?.trim()
    _validateHubVarName(name)
    def type = _validateHubVarType(args.type?.toString())
    def value = args.value
    _validateInitialValue(name, type, value)

    // Refuse to silently overwrite an existing variable — the UI's "Create"
    // path can't either; the wizard only progresses when the name is novel.
    try {
        def existing = getGlobalVar(name)
        if (existing != null) {
            throw new IllegalArgumentException(
                "Hub variable '${name}' already exists (type=${existing.type}, value=${existing.value}). " +
                "Use hub_set_variable to change the value, or pick a different name.")
        }
    } catch (IllegalArgumentException reraise) { throw reraise }
    catch (Exception e) {
        logDebug("hub_create_variable: getGlobalVar('${name}') threw ${e.class.simpleName}: ${e.message}")
    }

    def appId = _findHubVariablesAppId()
    return _createOneVariable(appId, name, type, value)
}

// Bulk create driver: validates the array shape, resolves the Hub Variables
// app id ONCE, then creates each item sequentially. Per-item try/catch so one
// failure never aborts the rest -- a failed item carries its own `error`.
private Map _createVariablesBulk(variables) {
    if (!(variables instanceof List) || variables.isEmpty()) {
        throw new IllegalArgumentException("variables must be a non-empty array of {name, type, value} objects.")
    }
    variables.eachWithIndex { item, idx ->
        if (!(item instanceof Map)) {
            throw new IllegalArgumentException("variables[${idx}] must be an object with name, type, and value.")
        }
    }

    def appId = _findHubVariablesAppId()
    def results = []
    int createdCount = 0
    int failedCount = 0

    variables.eachWithIndex { item, idx ->
        def itemName = item.name?.toString()?.trim()
        try {
            _validateHubVarName(itemName)
            def itemType = _validateHubVarType(item.type?.toString())
            _validateInitialValue(itemName, itemType, item.value)

            // Pre-flight existence check (matches the single-create refusal).
            def existing = null
            try { existing = getGlobalVar(itemName) } catch (Exception e) {
                logDebug("hub_create_variable bulk: getGlobalVar('${itemName}') threw ${e.class.simpleName}: ${e.message}")
            }
            if (existing != null) {
                throw new IllegalArgumentException(
                    "Hub variable '${itemName}' already exists (type=${existing.type}). Use hub_set_variable to change the value, or pick a different name.")
            }

            def created = _createOneVariable(appId, itemName, itemType, item.value)
            results << [name: itemName, success: true, type: created.type, value: created.value]
            createdCount++
        } catch (Exception e) {
            // Fall back to the array index when the item carried no usable name, so
            // every failed entry is traceable to its request position.
            results << [name: (itemName ?: item?.name ?: "variables[${idx}]"), success: false, error: e.message ?: e.toString()]
            failedCount++
        }
    }

    return [
        success: failedCount == 0,
        results: results,
        createdCount: createdCount,
        failedCount: failedCount
    ]
}

// Validate the initial value for a hub variable create. A null value is always
// rejected; additionally a String variable created with an empty/blank value
// reports success but never actually persists (getGlobalVar stays null --
// deterministic), so reject that up front with an actionable error.
private void _validateInitialValue(String name, String type, value) {
    if (value == null) {
        throw new IllegalArgumentException("Initial value is required")
    }
    if (type == "String" && !value.toString().trim()) {
        throw new IllegalArgumentException(
            "String variable '${name}' requires a non-empty initial value. " +
            "Hubitat reports success for an empty String value but the variable never persists; supply any non-empty initial value.")
    }
}

// Shared per-variable create core: drives the Hub Variables system app wizard
// (prime -> moreVar -> write name/type/value or DateTime date/time + Done),
// verifies the variable landed with a verify-backoff, and subscribes to its
// change event. Returns the single-create success map; throws on validation
// or verification failure. Both the single and bulk paths call this; `appId`
// is resolved once by the caller and reused.
private Map _createOneVariable(Integer appId, String name, String type, value) {
    def applied = []
    def skipped = []

    _primeHubVarsWizard(appId, "hub_create_variable pre-moreVar")
    _rmClickAppButton(appId, "moreVar", "moreVar", "hubVar")
    _rmWriteSettingOnPage(appId, "hubVar", "hbVar",   name, applied, "text", skipped)
    _rmWriteSettingOnPage(appId, "hubVar", "varType", type, applied, "enum", skipped)
    if (type == "DateTime") {
        // DateTime renders varDate + varTime instead of varValue. Accept ISO
        // "yyyy-MM-ddTHH:mm[:ss]", "yyyy-MM-dd HH:mm[:ss]", or a Map
        // {date:"yyyy-MM-dd", time:"HH:mm"}.
        def dateStr, timeStr
        if (value instanceof Map) {
            dateStr = value.date?.toString()
            timeStr = value.time?.toString()
        } else {
            def s = value.toString().trim()
            def parts = s.contains("T") ? s.split("T", 2) : (s.contains(" ") ? s.split(" ", 2) : [s, null])
            dateStr = parts[0]
            timeStr = parts.size() > 1 ? parts[1]?.take(5) : null  // "HH:mm" only
        }
        if (!dateStr || !timeStr) {
            throw new IllegalArgumentException(
                "DateTime variable '${name}' requires both date and time. " +
                "Pass an ISO string like '2026-05-06T12:00:00' or a {date,time} map. Got: ${value}")
        }
        _rmWriteSettingOnPage(appId, "hubVar", "varDate", dateStr, applied, "date", skipped)
        _rmWriteSettingOnPage(appId, "hubVar", "varTime", timeStr, applied, "time", skipped)
        // DateTime requires an explicit Done click to commit (the other
        // types auto-commit when varValue is written). Prime first — same
        // wizard quirk as delete/hub_create_connector clicks.
        _primeHubVarsWizard(appId, "hub_create_variable DateTime pre-Done")
        _rmClickAppButton(appId, "dateTimeDone", null, "hubVar")
    } else {
        _rmWriteSettingOnPage(appId, "hubVar", "varValue", value, applied, "textarea", skipped)
    }

    // Verify the variable landed. The wizard auto-commits on varValue write
    // (or dateTimeDone for DateTime); the var becomes visible to getGlobalVar
    // shortly after. Retry with backoff up to ~3s before giving up.
    def created = null
    for (int attempt = 0; attempt < 4; attempt++) {
        try { pauseExecution(500) } catch (Exception e) { logDebug("pauseExecution interrupted: ${e.class.simpleName}: ${e.message}") }
        try { created = getGlobalVar(name) } catch (Exception e) {
            logDebug("hub_create_variable: post-write getGlobalVar('${name}') threw ${e.class.simpleName}: ${e.message}")
        }
        if (created != null) break
    }
    if (created == null) {
        throw new IllegalStateException(
            "hub_create_variable: wizard completed but '${name}' is not visible via getGlobalVar. " +
            "Settings applied: ${applied.join(', ')}. Skipped: ${skipped}")
    }

    // Subscribe to the new variable's location event so changes show up in
    // hub_list_variable_changes without waiting for the next updated() pass.
    // Null-check + Throwable catch mirrors renameVariable: hubitat_ci's
    // validator throws AssertionError (not Exception) when location is null
    // in unit-test environments, and the create itself shouldn't be aborted
    // by a subscription-registration failure.
    if (location != null) {
        try { subscribe(location, "variable:${name}", "handleHubVariableEvent") }
        catch (Throwable e) {
            mcpLog("error", "hub-vars", "post-create subscribe to variable:${name} failed: ${e.message} -- hub_list_variable_changes won't capture this var's changes until next initialize()")
        }
    }

    mcpLog("info", "hub-vars", "Created hub variable '${name}' type=${type} value=${value}")
    return [
        success: true,
        name: name,
        type: type,
        value: created.value,
        source: "hub",
        message: "Variable '${name}' (${type}) created with initial value ${created.value}."
    ]
}

def toolCreateConnector(args) {
    requireDestructiveConfirm(args.confirm)
    def name = args.name?.toString()?.trim()
    if (!name) throw new IllegalArgumentException("Variable name is required")
    def requestedType = args.connectorType?.toString()?.trim()

    def existing
    try { existing = getGlobalVar(name) }
    catch (Exception e) { existing = null }
    if (existing == null) {
        throw new IllegalArgumentException("Hub variable '${name}' does not exist. Create it first with hub_create_variable.")
    }
    if (existing.deviceId != null) {
        return [
            success: true,
            name: name,
            deviceId: existing.deviceId,
            attribute: existing.attribute,
            alreadyExists: true,
            message: "Connector for '${name}' already exists (deviceId=${existing.deviceId})."
        ]
    }

    def appId = _findHubVariablesAppId()
    _primeHubVarsWizard(appId, "hub_create_connector pre-click")
    _rmClickAppButton(appId, name, "createCon", "hubVar")

    // For Number/Decimal vars Hubitat opens a chooser sub-wizard requiring
    // a 'capab' enum pick (Dimmer / Variable / etc.); for String/Boolean/
    // DateTime the click commits the connector immediately and the capab
    // input isn't in the schema, so the write is a schema-skip no-op.
    // Prime the wizard fresh (the chooser sub-page is its own wizard step
    // and benefits from the same priming as the parent click).
    def chosenType = requestedType?.trim() ?: "Variable"
    def applied = []
    def skipped = []
    _primeHubVarsWizard(appId, "hub_create_connector pre-capab")
    _rmWriteSettingOnPage(appId, "hubVar", "capab", chosenType, applied, "enum", skipped)

    def after
    for (int v = 0; v < 4; v++) {
        try { pauseExecution(500) } catch (Exception e) { logDebug("pauseExecution interrupted: ${e.class.simpleName}: ${e.message}") }
        try { after = getGlobalVar(name) } catch (Exception e) { after = null }
        if (after?.deviceId != null) break
    }
    if (after?.deviceId == null) {
        throw new IllegalStateException(
            "hub_create_connector: wizard completed but '${name}' still has no deviceId. " +
            "Tried capab='${chosenType}' (only relevant for Number/Decimal). The connector did not bake.")
    }

    mcpLog("info", "hub-vars", "Created connector for '${name}' (deviceId=${after.deviceId}, attribute=${after.attribute}, capab=${chosenType})")
    return [
        success: true,
        name: name,
        deviceId: after.deviceId,
        attribute: after.attribute,
        connectorType: chosenType,
        message: "Connector created for '${name}' (deviceId=${after.deviceId})."
    ]
}

def toolRemoveConnector(args) {
    requireDestructiveConfirm(args?.confirm as Boolean)
    def name = args?.name?.toString()?.trim()
    if (!name) throw new IllegalArgumentException("Variable name is required")

    def existing
    try { existing = getGlobalVar(name) }
    catch (Exception e) { existing = null }
    if (existing == null) {
        throw new IllegalArgumentException("Hub variable '${name}' does not exist.")
    }
    if (existing.deviceId == null) {
        return [
            success: true,
            name: name,
            alreadyRemoved: true,
            message: "Variable '${name}' has no connector to remove."
        ]
    }

    def deviceId = existing.deviceId
    // Known Hubitat platform bug (confirmed 2026-06-02, fw 2.5.0.143, via Hubitat's
    // own UI "Remove Device"): removing a variable-connector child makes Hubitat's
    // BUILT-IN "Variable Connectors" parent driver recurse in childRemoved() and log
    // a java.lang.StackOverflowError (the child's uninstalled() fires ~10x first).
    // NOT ours -- the official Remove Device button triggers the identical crash, and
    // the device is removed either way. We surface it in the response `note` so the
    // logged error isn't mistaken for a hub_delete_connector failure.
    def res = toolDeleteDevice([deviceId: deviceId.toString(), confirm: true])
    if (res?.success != true) {
        throw new IllegalStateException(
            "hub_delete_connector: toolDeleteDevice failed for deviceId=${deviceId}: ${res?.error ?: 'unknown error'}")
    }

    // Verify the variable's deviceId linkage is cleared. Without this, a
    // hub_delete_device that returned success but didn't actually remove (or the
    // hub didn't propagate the unlinking) would silently report success here.
    def after = null
    for (int v = 0; v < 4; v++) {
        try { pauseExecution(500) } catch (Exception e) { logDebug("pauseExecution interrupted: ${e.class.simpleName}: ${e.message}") }
        try { after = getGlobalVar(name) } catch (Exception e) { after = null }
        if (after?.deviceId == null) break
    }
    if (after?.deviceId != null) {
        throw new IllegalStateException(
            "hub_delete_connector: device ${deviceId} delete returned success but variable '${name}' still has deviceId=${after.deviceId}")
    }

    mcpLog("info", "hub-vars", "Removed connector for '${name}' (deviceId=${deviceId})")
    return [
        success: true,
        name: name,
        deviceId: deviceId,
        deviceDeleted: true,
        message: "Connector for '${name}' (deviceId=${deviceId}) removed. Variable itself is unchanged.",
        note: "A java.lang.StackOverflowError from Hubitat's built-in 'Variable Connectors' driver (childRemoved) may appear in the hub log during this removal. It is a known Hubitat platform bug -- the same crash occurs via the UI's Remove Device button -- and is harmless: the connector is removed regardless."
    ]
}

def toolSetVariable(name, value) {
    // setGlobalVar returns true on success, false when the variable doesn't
    // exist (Hubitat will not auto-create vars from setGlobalVar — creation
    // requires the Hub Variables UI or our toolCreateVariable tool). Falling
    // back to rule_engine namespace on false OR exception preserves the
    // legacy behavior callers depend on.
    try {
        if (setGlobalVar(name, value)) {
            return [success: true, name: name, value: value, source: "hub"]
        }
    } catch (Exception e) {
        logDebug("setGlobalVar('${name}') threw ${e.class.simpleName}: ${e.message}")
    }
    if (!state.ruleVariables) state.ruleVariables = [:]
    state.ruleVariables[name] = value
    return [success: true, name: name, value: value, source: "rule_engine"]
}

def toolDeleteHubVariable(args) {
    requireDestructiveConfirm(args?.confirm as Boolean)
    def varName = args?.name?.toString()?.trim()
    if (!varName) throw new IllegalArgumentException("name is required")
    def force = args?.force == true

    // Source detection: hub vs rule_engine namespace. Hub vars are deleted
    // through the Hub Variables system app's wizard; rule_engine vars are a
    // map in state we can rewrite directly. Same in-use safety scan applies
    // to both — child rules can reference a hub var by name in their
    // triggers/conditions/actions JSON.
    def hubVar = null
    try { hubVar = getGlobalVar(varName) }
    catch (Exception e) {
        logDebug("hub_delete_variable: getGlobalVar('${varName}') threw ${e.class.simpleName}: ${e.message}")
    }
    def isHubVar = (hubVar != null)
    def isRuleVar = state.ruleVariables?.containsKey(varName) ?: false

    if (!isHubVar && !isRuleVar) {
        throw new IllegalArgumentException(
            "Variable '${varName}' not found in either the hub-variables namespace or the rule_engine namespace.")
    }

    // Pre-deletion safety scan: child rule apps that reference this variable will silently
    // break on next access (null lookup → conditions flip false, %varname% substitution
    // leaves literal text). Block by default — caller must opt in via force=true after
    // acknowledging the breakage. Match heuristic: variable name appears in the rule's
    // serialized triggers/conditions/actions JSON.
    def consumers = []
    try {
        // Word-boundary match: look for "<varName>" (JSON-quoted) so a var
        // named `temp` doesn't match rules referencing `temperature` etc.
        def needle = "\"${varName}\""
        getChildApps()?.each { child ->
            def ruleData = null
            try { ruleData = child.getRuleData() } catch (Exception e) { /* not an MCP rule child */ }
            if (ruleData) {
                def serialized = groovy.json.JsonOutput.toJson(ruleData)
                if (serialized?.contains(needle)) {
                    consumers << [id: child.id, label: child.label]
                }
            }
        }
    } catch (Exception e) {
        // Defensive: if the scan itself errors, fall through to apply normal force gate
        // rather than blocking deletion entirely. Log so investigators know the scan
        // didn't run.
        logDebug("hub_delete_variable: getChildApps() scan failed: ${e.class.simpleName}: ${e.message}")
    }
    if (consumers && !force) {
        def consumerCount = consumers.size()
        def consumerWord = consumerCount == 1 ? "rule" : "rules"
        throw new IllegalArgumentException(
            "Variable '${varName}' is referenced by ${consumerCount} ${consumerWord}: " +
            "${consumers.collect { "${it.label} (id=${it.id})" }.join(', ')}. " +
            "Deleting will silently break ${consumerCount == 1 ? 'that rule' : 'those rules'} (null lookups, false conditions, " +
            "literal %${varName}% in substitutions). Pass force=true to proceed anyway, " +
            "or update/remove the consuming ${consumerWord} first."
        )
    }

    if (isHubVar) {
        // Hub-namespace deletion: drive the Hub Variables system app's
        // wizard. Two clicks: deleteGV opens the confirm prompt, delConfirm
        // commits. Hubitat also deletes the connector device when one
        // exists, surfacing the resulting deviceId in the audit log so the
        // caller knows what else just disappeared.
        def previousValue = hubVar.value
        def previousType  = hubVar.type
        def hadConnector  = hubVar.deviceId != null
        def appId = _findHubVariablesAppId()
        // The wizard's first click sequence after a fresh create/edit can be
        // dropped silently by the hub (state-machine race that priming
        // alone doesn't reliably defeat). Retry the full click
        // sequence once if the verification fails — empirically the second
        // attempt always commits.
        def stillThere = null
        for (int attempt = 0; attempt < 2; attempt++) {
            _primeHubVarsWizard(appId, "hub_delete_variable pre-click attempt-${attempt + 1}")
            _rmClickAppButton(appId, varName, "deleteGV", "hubVar")
            _rmClickAppButton(appId, "delConfirm", null, "hubVar")
            // Brief pause + verification. If the var is gone, we're done.
            for (int v = 0; v < 4; v++) {
                try { pauseExecution(500) } catch (Exception e) { logDebug("pauseExecution interrupted: ${e.class.simpleName}: ${e.message}") }
                try { stillThere = getGlobalVar(varName) } catch (Exception e) { stillThere = null }
                if (stillThere == null) break
            }
            if (stillThere == null) break
        }
        if (stillThere != null) {
            throw new IllegalStateException(
                "hub_delete_variable: wizard completed but '${varName}' still exists in getGlobalVar after 2 click-sequence attempts.")
        }

        def prevStr = previousValue?.toString()
        def auditValue = prevStr == null ? "null" : (prevStr.length() > 80 ? "${prevStr.take(80)} [truncated, original length: ${prevStr.length()}]" : prevStr)
        def connectorNote = hadConnector ? " (connector deviceId=${hubVar.deviceId} also deleted)" : ""
        def consumerNote = ""
        if (consumers) {
            def cc = consumers.size()
            consumerNote = " (forced; ${cc} ${cc == 1 ? 'rule' : 'rules'} now broken: ${consumers.collect { "id=${it.id}" }.join(', ')})"
        }
        mcpLog("warn", "developer-mode", "hub_delete_variable: removed hub var '${varName}' (type=${previousType}, previous value: ${auditValue})${connectorNote}${consumerNote}")
        return [
            success: true,
            name: varName,
            deleted: true,
            source: "hub",
            type: previousType,
            previousValue: previousValue,
            connectorDeleted: hadConnector,
            brokenConsumers: consumers ?: null
        ]
    }

    // rule_engine fallback (legacy MCP-managed rule variables). Same
    // top-level reassignment pattern: nested-map mutations on state silently
    // fail to persist across hub reboot / app restart unless the top-level
    // key is reassigned. Read-modify-write the whole map.
    def previousValue = state.ruleVariables[varName]
    def updated = state.ruleVariables.findAll { k, v -> k != varName }
    state.ruleVariables = updated

    def prevStr = previousValue?.toString()
    def auditValue = prevStr == null ? "null" : (prevStr.length() > 80 ? "${prevStr.take(80)} [truncated, original length: ${prevStr.length()}]" : prevStr)
    def consumerNote = ""
    if (consumers) {
        def cc = consumers.size()
        consumerNote = " (forced; ${cc} ${cc == 1 ? 'rule' : 'rules'} now broken: ${consumers.collect { "id=${it.id}" }.join(', ')})"
    }
    mcpLog("warn", "developer-mode", "hub_delete_variable: removed '${varName}' (previous value: ${auditValue})${consumerNote}")
    return [success: true, name: varName, deleted: true, source: "rule_engine", previousValue: previousValue, brokenConsumers: consumers ?: null]
}

def _getAllToolDefinitions_partVariables() {
    return [
        [
            name: "hub_list_variables",
            description: "List all hub variables (every type, including ones without connectors) and rule-engine variables. Each hub-variable entry includes type (Number/Decimal/String/Boolean/DateTime), value, and connector linkage (deviceId/attribute) when present.",
            inputSchema: [
                type: "object",
                properties: [
                    cursor: [type: "string", description: "Opt-in pagination cursor for the hubVariables list (ruleVariables stays in full alongside the page). Omit for unbounded; pass \"\" for the first page, iterate nextCursor (page size 100)."]
                ]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    hubVariables: [type: "array", description: "Hub variables (page when paginated)", items: [type: "object", properties: [
                        name: [type: "string", description: "Variable name"],
                        value: [description: "Current value"],
                        type: [type: "string", description: "Number/Decimal/String/Boolean/DateTime"],
                        deviceId: [type: "string", description: "Connector device id when present"],
                        attribute: [type: "string", description: "Connector attribute when present"],
                        source: [type: "string", description: "Always 'hub'"]
                    ]]],
                    ruleVariables: [type: "array", description: "Rule-engine variables", items: [type: "object", properties: [
                        name: [type: "string", description: "Variable name"],
                        value: [description: "Current value"],
                        source: [type: "string", description: "Always 'rule_engine'"]
                    ]]],
                    totalHubVariables: [type: "integer", description: "Total hub variables"],
                    totalRuleVariables: [type: "integer", description: "Total rule-engine variables"],
                    total: [type: "integer", description: "Combined total"],
                    hubVariablesError: [type: "string", description: "Present when the hub variable API failed"],
                    nextCursor: [type: "string", description: "Present when more hub variables remain"]
                ],
                required: ["hubVariables", "ruleVariables", "total"]
            ]
        ],
        [
            name: "hub_get_variable",
            description: "Get one variable's current value by name. Searches the hub-variable namespace first, then falls back to rule-engine variables[[FLAT_TRIM]]; the returned source field says which matched. For hub variables it also returns metadata (type, plus deviceId/attribute when a connector is linked)[[/FLAT_TRIM]]. Use hub_list_variables to enumerate; use this when you already know the name.",
            inputSchema: [
                type: "object",
                properties: [
                    name: [type: "string", description: "Exact variable name to look up, e.g. \"vacationMode\". Case-sensitive."]
                ],
                required: ["name"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    name: [type: "string", description: "Variable name"],
                    value: [description: "Current value"],
                    type: [type: "string", description: "Variable type as Hubitat reports it (hub variables only); its naming/casing may differ from the hub_create_variable type enum (Number/Decimal/String/Boolean/DateTime)."],
                    deviceId: [type: "string", description: "Connector device id (hub variables with connector only)"],
                    attribute: [type: "string", description: "Connector attribute (hub variables with connector only)"],
                    source: [type: "string", description: "'hub' or 'rule_engine'"]
                ],
                required: ["name", "value", "source"]
            ]
        ],
        [
            name: "hub_set_variable",
            description: "Set an existing variable's value. For hub variables, value type must match the variable's declared type (creating new hub variables requires hub_create_variable — Hubitat does not allow setGlobalVar to create). Falls back to the rule_engine namespace when no hub variable matches.",
            inputSchema: [
                type: "object",
                properties: [
                    name: [type: "string", description: "Variable name"],
                    value: [type: "string", description: "Variable value (string, number, or boolean as string)"]
                ],
                required: ["name", "value"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the set succeeded"],
                    name: [type: "string", description: "Variable name"],
                    value: [description: "Value that was set"],
                    source: [type: "string", description: "'hub' or 'rule_engine'"]
                ],
                required: ["success", "name", "value", "source"]
            ]
        ],
        [
            name: "hub_create_variable",
            description: "Create a new hub variable (global variable visible to apps and Rule Machine), one at a time or several in one call. Use this before hub_set_variable for a name that doesn't exist yet — Hubitat's setGlobalVar cannot create, only update.[[FLAT_TRIM]] Drives the Settings → Hub Variables wizard, since creation isn't exposed via the public app API. Name must not contain any of these characters: ' \" \\ ~ [ : ] < >.[[/FLAT_TRIM]] A String variable's initial value must be non-empty (an empty String reports success but never persists). Single form: name + type + value. Bulk form: variables=[{name,type,value}, ...] -- mutually exclusive with the single form.[[FLAT_TRIM]] Bulk items are created sequentially; each succeeds or fails independently and the result reports per-item status. To also expose the variable to device-only apps, follow up with hub_create_connector.[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    name: [type: "string", description: "Single form: new variable name, e.g. \"vacationMode\".[[FLAT_TRIM]] Must not contain: ' \" \\ ~ [ : ] < >.[[/FLAT_TRIM]] Omit when using variables."],
                    type: [type: "string", enum: ["Number", "Decimal", "String", "Boolean", "DateTime"], description: "Single form: variable type.[[FLAT_TRIM]] Omit when using variables.[[/FLAT_TRIM]]"],
                    value: [description: "Single form: initial value, must match the type.[[FLAT_TRIM]] Omit when using variables.[[/FLAT_TRIM]]"],
                    variables: [type: "array", description: "Bulk form: several variables in one call (mutually exclusive with name/type/value).", items: [
                        type: "object",
                        properties: [
                            name: [type: "string", description: "New variable name (same character rules as the single form)"],
                            type: [type: "string", enum: ["Number", "Decimal", "String", "Boolean", "DateTime"], description: "Variable type"],
                            value: [description: "Initial value, must match the type"]
                        ],
                        required: ["name", "type", "value"]
                    ]],
                    confirm: [type: "boolean", description: "REQUIRED: must be true to perform the creation"]
                ],
                required: ["confirm"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Single form: whether creation succeeded. Bulk form: true only when every item was created."],
                    name: [type: "string", description: "Single form: variable name"],
                    type: [type: "string", description: "Single form: variable type"],
                    value: [description: "Single form: initial value after creation"],
                    source: [type: "string", description: "Single form: always 'hub'"],
                    message: [type: "string", description: "Single form: human-readable result"],
                    results: [type: "array", description: "Bulk form: per-item result, one entry per requested variable", items: [type: "object", properties: [
                        name: [type: "string", description: "Variable name"],
                        success: [type: "boolean", description: "Whether this item was created"],
                        type: [type: "string", description: "Variable type (created items)"],
                        value: [description: "Initial value after creation (created items)"],
                        error: [type: "string", description: "Failure reason (failed items)"]
                    ]]],
                    createdCount: [type: "integer", description: "Bulk form: number of items created"],
                    failedCount: [type: "integer", description: "Bulk form: number of items that failed"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_delete_variable",
            description: "Permanently delete a variable (DESTRUCTIVE — no undo). Auto-detects whether the target is a hub variable (drives Settings → Hub Variables wizard; also deletes the connector device if one exists) or a rule_engine variable (rewrites state). Throws if the name resolves to neither.\n\nGated on the Write master + confirm=true + a recent backup. [[FLAT_TRIM]]Useful for sweeping orphaned BAT_E2E_* artifacts after CI runs, removing stale lease variables, or general cleanup.[[/FLAT_TRIM]]\n\n**Reference safety:** the tool scans every child rule app for serialized references to this variable name (in triggers/conditions/actions) and refuses by default if any are found[[FLAT_TRIM]] — deletion would silently break those rules (null lookups → false conditions, literal `%varname%` left in substitutions)[[/FLAT_TRIM]]. To proceed anyway, pass `force=true` after acknowledging the breakage. The response includes a `brokenConsumers` field listing the affected rules when force=true.",
            inputSchema: [
                type: "object",
                properties: [
                    name: [type: "string", description: "Variable name to delete"],
                    confirm: [type: "boolean", description: "REQUIRED: must be true to confirm the deletion"],
                    force: [type: "boolean", description: "OPTIONAL: must be true to proceed when one or more child rule apps reference this variable. Without force, the tool refuses and lists the consumers."]
                ],
                required: ["name", "confirm"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether deletion succeeded"],
                    name: [type: "string", description: "Variable name"],
                    deleted: [type: "boolean", description: "True when the variable was removed"],
                    source: [type: "string", description: "'hub' or 'rule_engine'"],
                    type: [type: "string", description: "Variable type (hub variables only)"],
                    previousValue: [description: "Value before deletion"],
                    connectorDeleted: [type: "boolean", description: "True when a connector device was also deleted (hub only)"],
                    brokenConsumers: [type: "array", description: "Rules referencing this variable (populated when force=true), else null", items: [type: "object", properties: [
                        id: [type: "string", description: "Rule app id"],
                        label: [type: "string", description: "Rule label"]
                    ]]]
                ],
                required: ["success", "name", "deleted", "source"]
            ]
        ],
        [
            name: "hub_create_connector",
            description: "Create a virtual-device connector for an existing hub variable so apps that only consume devices can read/write it.[[FLAT_TRIM]] For Number/Decimal vars, Hubitat shows a connector-type chooser (Dimmer/Variable/etc.); pass connectorType to pick, default 'Variable'. For String/Boolean/DateTime vars, the chooser is skipped.[[/FLAT_TRIM]] No-op if a connector already exists.",
            inputSchema: [
                type: "object",
                properties: [
                    name: [type: "string", description: "Existing hub-variable name"],
                    connectorType: [type: "string", description: "Optional connector type for Number/Decimal vars (e.g. 'Dimmer', 'Variable', 'Volume', 'ColorTemp', 'Humidity', 'Illuminance'). Defaults to 'Variable'. Ignored for vars that don't show a chooser."],
                    confirm: [type: "boolean", description: "REQUIRED: must be true"]
                ],
                required: ["name", "confirm"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the connector exists/was created"],
                    name: [type: "string", description: "Variable name"],
                    deviceId: [type: "string", description: "Connector device id"],
                    attribute: [type: "string", description: "Connector attribute"],
                    connectorType: [type: "string", description: "Connector type chosen (newly created connectors)"],
                    alreadyExists: [type: "boolean", description: "True when a connector already existed (no-op)"],
                    message: [type: "string", description: "Human-readable result"]
                ],
                required: ["success", "name", "deviceId"]
            ]
        ],
        [
            name: "hub_delete_connector",
            description: "Delete the connector device backing a hub variable. DESTRUCTIVE and not undoable — the connector device is removed (apps that read/write the variable through that device lose access), but the hub variable itself and its value are unchanged. Confirm with the caller before running, since confirm=true is required. No-op (returns alreadyRemoved) if the variable has no connector.",
            inputSchema: [
                type: "object",
                properties: [
                    name: [type: "string", description: "Hub-variable name whose connector device to remove, e.g. \"vacationMode\""],
                    confirm: [type: "boolean", description: "REQUIRED: must be true to perform the deletion"]
                ],
                required: ["name", "confirm"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the removal succeeded"],
                    name: [type: "string", description: "Variable name"],
                    deviceId: [type: "string", description: "Removed connector device id (when one existed)"],
                    deviceDeleted: [type: "boolean", description: "True when a connector device was deleted"],
                    alreadyRemoved: [type: "boolean", description: "True when there was no connector to remove (no-op)"],
                    message: [type: "string", description: "Human-readable result"],
                    note: [type: "string", description: "Advisory about the known Hubitat StackOverflowError log noise"]
                ],
                required: ["success", "name"]
            ]
        ],
        [
            name: "hub_list_variable_changes",
            description: "List recent hub-variable change events captured by the MCP app's location-event subscription, most-recent first. Use this to audit or debug what changed a variable and when, without polling hub_get_variable. The buffer holds at most the 200 most recent changes (oldest dropped) and is cleared on app restart, so it is not a complete history — an empty or partial result does NOT mean the variable never changed. For the hub's authoritative, complete change log (survives restarts) call hub_list_device_events with no deviceId (location-event mode). Filter by variable name and/or timestamp.",
            inputSchema: [
                type: "object",
                properties: [
                    name: [type: "string", description: "Optional: filter to changes for this variable name only, e.g. \"vacationMode\". Omit to include all variables."],
                    sinceMs: [type: "integer", description: "Optional: only return changes whose timestamp >= this epoch-millis value, e.g. 1717459200000"],
                    limit: [type: "integer", description: "Optional: max entries to return (default 50)"]
                ]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    entries: [type: "array", description: "Recent variable changes, most-recent first", items: [type: "object", properties: [
                        name: [type: "string", description: "Variable name"],
                        value: [description: "New value at change time"],
                        timestamp: [type: "integer", description: "Change time (epoch millis)"],
                        descriptionText: [type: "string", description: "Event description text"]
                    ]]],
                    total: [type: "integer", description: "Entries returned"],
                    bufferSize: [type: "integer", description: "Total changes currently buffered"],
                    bufferCap: [type: "integer", description: "Max buffer capacity (200)"]
                ],
                required: ["entries", "total", "bufferSize", "bufferCap"]
            ]
        ],
    ]
}

def _readOnlyToolNames_partVariables() {
    // Read-only classification membership for this library's tools, contributed to the
    // app's getReadOnlyToolNames() aggregator (issue #209: per-tool metadata lives with
    // the tool). A tool absent from every part list is write+destructive by default.
    return [
        // Variables (reads)
        "hub_list_variables", "hub_get_variable", "hub_list_variable_changes"
    ]
}

def _idempotentWriteToolNames_partVariables() {
    // Retry-safe writes (MCP idempotentHint) for this library's tools -- contributed to the
    // app's getIdempotentWriteToolNames() aggregator; see the classification rules there.
    return [
        // Variables + connectors
        "hub_set_variable", "hub_delete_variable", "hub_create_connector", "hub_delete_connector"
    ]
}

def _toolDisplayMeta_partVariables() {
    // Human-facing title/summary per tool (MCP annotations.title + the Advanced per-tool
    // overrides menu) -- merged into the app's getToolDisplayMeta() aggregator (issue #209).
    return [
        // Variables
        hub_list_variables: [title: "List Variables", summary: "List all hub and rule-engine variables."],
        hub_get_variable: [title: "Get Variable", summary: "Get a variable's value and metadata."],
        hub_set_variable: [title: "Set Variable", summary: "Set an existing variable's value."],
        hub_create_variable: [title: "Create Variable", summary: "Create a new hub variable."],
        hub_delete_variable: [title: "Delete Variable", summary: "Permanently delete a hub variable and any connector it has."],
        hub_create_connector: [title: "Create Variable Connector", summary: "Create a virtual-device connector for a hub variable."],
        hub_delete_connector: [title: "Delete Variable Connector", summary: "Remove a hub variable's connector device."],
        hub_list_variable_changes: [title: "List Variable Changes", summary: "Recent hub-variable changes since the MCP app last started."]
    ]
}
