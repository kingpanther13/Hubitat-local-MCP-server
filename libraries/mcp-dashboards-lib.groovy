library(name: "McpDashboardsLib", namespace: "mcp", author: "kingpanther13", description: "Easy Dashboard CRUD tools (list/get/create/update/delete/clone) for the MCP Rule Server.")

def toolListDashboards(args = null) {
    args = args ?: [:]
    // /dashboard/all returns [] unless given the page-global pinToken from /dashboard/select. Use a
    // caller token if present, else scrape it; if still empty, fall back to child-app enumeration.
    def token = (args.pinToken != null) ? args.pinToken.toString() : _fetchDashboardPinToken()
    def q = [:]
    if (token) q.pinToken = token
    def parsed = null
    boolean allErrored = false   // true iff /dashboard/all threw (hub error), not merely empty
    try {
        def raw = hubInternalGet("/dashboard/all", q)
        parsed = raw ? new groovy.json.JsonSlurper().parseText(raw) : []
    } catch (Exception e) {
        mcpLogError("dashboard", "list dashboards via /dashboard/all failed", e)
        parsed = null
        allErrored = true
    }
    // Keep only Easy (2.x) dashboards; a null/absent version (create/update echo) is kept.
    def list = (parsed instanceof List) ? parsed.findAll { it != null && (it.version == null || it.version.toString().startsWith("2")) } : []
    if (!list.isEmpty()) {
        def dashboards = list.collect { _summarizeDashboard(it) }
        return [dashboards: dashboards, count: dashboards.size(), source: "dashboard-all"]
    }
    def viaApps = _listDashboardsViaChildApps()
    if (viaApps != null && !viaApps.isEmpty()) {
        // Don't blame a missing pinToken for a hub ERROR: only mention pinToken when /dashboard/all was empty.
        def note = allErrored
            ? "/dashboard/all errored (logged); listed via the Easy Dashboard Parent's child apps instead. Each entry carries id + name only; call hub_get_dashboard for full config."
            : "Listed from the Easy Dashboard Parent's child apps (/dashboard/all returned nothing -- a pinToken may be required). Each entry carries id + name only; call hub_get_dashboard for full config."
        return [dashboards: viaApps, count: viaApps.size(), source: "child-apps", note: note]
    }
    // The child-app fallback READ FAILED (null), vs read-OK-but-empty ([]). Combined with /dashboard/all
    // yielding nothing, we genuinely can't tell -- DON'T claim a confident "zero dashboards"/"no child
    // apps present". Only viaApps == [] means the read succeeded and there truly are none.
    if (viaApps == null) {
        return [success: false,
                error: allErrored
                    ? "Could not list dashboards: both /dashboard/all and the child-app fallback failed to read."
                    : "Could not confirm the dashboard list: /dashboard/all returned nothing and the child-app fallback read failed.",
                note: "Transient hub error (details logged). Retry; if it persists, check hub connectivity."]
    }
    // viaApps == [] : the child-app read SUCCEEDED and found none. Branch the note on whether
    // /dashboard/all errored vs returned empty -- don't blame a missing pinToken for a hub error.
    return [dashboards: [], count: 0,
            note: allErrored
                ? "No Easy Dashboards found: /dashboard/all errored (logged) and the Easy Dashboard Parent has no child apps. Retry; if it persists, check hub connectivity."
                : "No Easy Dashboards found: /dashboard/all returned empty (a pinToken may be required) and no Easy Dashboard Parent child apps were present. If you expected dashboards, pass pinToken and retry."]
}

// Scrape globalDashboardPinToken from the /dashboard/select page so callers needn't supply pinToken.
// Returns null if unreadable (caller then degrades to child-app enumeration). Server-side only.
private String _fetchDashboardPinToken() {
    try {
        // hubInternalGetRaw returns a struct [status, location, data]; the body is .data.
        def html = hubInternalGetRaw("/dashboard/select")?.data?.toString()
        if (!html) return null
        def matcher = (html =~ /globalDashboardPinToken\s*=\s*['"]([^'"]+)['"]/)
        return matcher.find() ? matcher.group(1) : null
    } catch (Exception e) {
        mcpLogError("dashboard", "fetch dashboard pinToken failed", e)
        return null
    }
}

// Enumerate Easy Dashboards as child apps of the "Easy Dashboard Parent" via /hub2/appsList (no token).
// Returns [id:..., name:...] maps, or null if unreadable. Callers: the list fallback AND _dashboardPresent.
private List _listDashboardsViaChildApps() {
    try {
        def raw = hubInternalGet("/hub2/appsList")
        if (!raw) return null
        def parsed = new groovy.json.JsonSlurper().parseText(raw)
        def apps = parsed?.apps ?: []
        def out = []
        def walk
        walk = { node ->
            def d = node?.data ?: [:]
            if (d.type == "Easy Dashboard Parent") {
                (node?.children ?: []).each { c ->
                    def cd = c?.data ?: [:]
                    if (cd.id != null) out << [id: cd.id?.toString(), name: cd.name]
                }
            }
            (node?.children ?: []).each { walk(it) }
        }
        apps.each { walk(it) }
        return out
    } catch (Exception e) {
        mcpLogError("dashboard", "enumerate dashboards via child apps failed", e)
        return null
    }
}

// Dashboard ids are numeric; reject anything else so a bad value can't be spliced into /dashboard/<id>.
private String _requireDashboardId(rawId, String verbNote) {
    def s = rawId?.toString()?.trim()
    if (!s) {
        throw new IllegalArgumentException("id is required (the dashboard installedAppId${verbNote}). Call hub_list_dashboards to find it.")
    }
    if (!(s ==~ /\d+/)) {
        throw new IllegalArgumentException("id must be a numeric dashboard installedAppId; got '${rawId}'. Call hub_list_dashboards to find it.")
    }
    return s
}

def toolGetDashboard(args) {
    args = args ?: [:]
    def wantId = _requireDashboardId(args.id, "")
    // No single-dashboard read endpoint; derive from the list and filter by id.
    def listed = toolListDashboards(args)
    if (listed instanceof Map && listed.success == false) {
        return listed
    }
    def match = (listed.dashboards ?: []).find { it.id?.toString() == wantId }
    if (!match) {
        def available = (listed.dashboards ?: []).collect { it.id }.findAll { it != null }
        return [success: false, error: "No dashboard found with id '${wantId}'.",
                availableIds: available,
                note: available.isEmpty()
                    ? "The list returned no dashboards -- a pinToken may be required (pass pinToken and retry)."
                    : "Use hub_list_dashboards to see valid ids."]
    }
    // Child-app list has only id+name; enrich from the app-config page for the full shape (no theme there).
    if (!match.containsKey("showClockTile")) {
        def full = _dashboardConfigFromApp(wantId)
        if (full != null) {
            full.id = wantId
            if (!full.name) full.name = match.name
            return full
        }
        // Enrichment read FAILED: return id + name but flag it as partial, so a caller doesn't mistake
        // "config unreadable" for "a dashboard whose tile/device/pin fields are all empty/default".
        return match + [partial: true, note: "Only id + name could be read (the dashboard's config page was unreadable); tile/device/pin fields are unavailable, not defaulted. Retry hub_get_dashboard."]
    }
    return match
}

// Read one dashboard's full config from /installedapp/configure/json/<id> (stateless, no token): tile
// toggles (as "true"/"false"), navigationSelection, dashboardPin/hsmPin, devicesPicked ({id:name}). No theme.
private Map _dashboardConfigFromApp(String id) {
    try {
        def raw = hubInternalGet("/installedapp/configure/json/${id}")
        if (!raw) return null
        def parsed = new groovy.json.JsonSlurper().parseText(raw)
        def s = parsed?.settings
        if (!(s instanceof Map)) return null
        def out = [id: id, name: parsed?.app?.label]
        ["showModeTile", "showClockTile", "showCalendarTile", "showHSMTile", "showEdit",
         "showNavigation", "showTutorial"].each { k ->
            if (s.containsKey(k)) out[k] = (s[k]?.toString() == "true")
        }
        if (s.containsKey("navigationSelection")) out.navigationSelection = _dashboardNavSelectionCsv(s.navigationSelection)
        if (s.devicesPicked instanceof Map) out.deviceIds = s.devicesPicked.keySet().collect { it.toString() }
        // app-config returns "" for an unset pin and the literal "null" for an unset hsmPin.
        if (s.dashboardPin && s.dashboardPin.toString() != "null") out.dashboardPin = s.dashboardPin
        if (s.hsmPin && s.hsmPin.toString() != "null") out.hsmPin = s.hsmPin
        return out
    } catch (Exception e) {
        mcpLogError("dashboard", "read dashboard config from app failed", e)
        return null
    }
}

// Confirms a delete by effect (since /dashboard/delete's success flag is unreliable). TRI-STATE:
// true=present, false=confirmed absent, null=could not verify (so delete never false-claims "gone").
private Boolean _dashboardPresent(String id) {
    def viaApps = _listDashboardsViaChildApps()
    if (viaApps != null) return viaApps.any { it.id?.toString() == id }
    def listed = toolListDashboards([:])
    if (!(listed instanceof Map) || listed.success == false) return null   // couldn't read either source
    return (listed.dashboards ?: []).any { it.id?.toString() == id }
}

def toolCreateDashboard(args) {
    args = args ?: [:]
    // Create requires name + >=1 deviceId; tile toggles, theme, navigation, and pins are optional.
    if (!args.name?.toString()?.trim()) {
        throw new IllegalArgumentException("name is required (the dashboard's display name).")
    }
    def deviceCsv = _dashboardDeviceCsv(args.deviceIds, true)
    def q = _buildDashboardConfigQuery(args, deviceCsv)
    // CREATE-ONLY: the Vue editor sends version=2.0 on create (stripped on update) for an Easy Dashboard.
    q.version = (args.version ?: "2.0").toString()
    try {
        def raw = hubInternalGet("/dashboard/create", q)
        return _dashboardWriteResult(raw, "create", null)
    } catch (IllegalArgumentException iae) {
        throw iae
    } catch (Exception e) {
        mcpLogError("dashboard", "create dashboard failed", e)
        return [success: false, error: "Failed to create dashboard: ${e.message}", note: "Nothing was created."]
    }
}

def toolUpdateDashboard(args) {
    args = args ?: [:]
    def updateId = _requireDashboardId(args.id, " to update")
    if (!args.name?.toString()?.trim()) {
        throw new IllegalArgumentException("name is required. Update REPLACES the dashboard's config wholesale (there is no server-side read-merge), so pass the full desired config every time.")
    }
    // Update is wholesale + requires the full device set, so a caller can't silently blank devices.
    def deviceCsv = _dashboardDeviceCsv(args.deviceIds, true)
    // Wholesale replace would CLEAR an omitted dashboardPin/hsmPin: preserve an existing PIN the caller
    // didn't pass by reading + re-injecting it. (theme isn't recoverable from app-config.)
    def argsForQuery = args
    boolean pinPreserveFailed = false
    def hasField = { String k -> (args.options instanceof Map && args.options.containsKey(k)) || args.containsKey(k) }
    if (!hasField("dashboardPin") || !hasField("hsmPin")) {
        def cur = _dashboardConfigFromApp(updateId)
        if (cur != null) {
            def merged = [:] + (args.options instanceof Map ? args.options : [:])
            if (!hasField("dashboardPin") && cur.containsKey("dashboardPin")) merged.dashboardPin = cur.dashboardPin
            if (!hasField("hsmPin") && cur.containsKey("hsmPin")) merged.hsmPin = cur.hsmPin
            argsForQuery = [:] + args
            argsForQuery.options = merged
        } else {
            // Couldn't read the current config to preserve an omitted PIN -- the wholesale update will
            // CLEAR it. Flag it so the clear isn't silent (the caller can re-set the PIN if needed).
            pinPreserveFailed = true
        }
    }
    def q = _buildDashboardConfigQuery(argsForQuery, deviceCsv)
    q.id = updateId
    try {
        def raw = hubInternalGet("/dashboard/update", q)
        def result = _dashboardWriteResult(raw, "update", updateId)
        if (pinPreserveFailed && result instanceof Map && result.success == true) {
            result.pinPreserveFailed = true
            result.note = "Update applied, but the current config couldn't be read to preserve an omitted PIN -- if this dashboard had a dashboardPin/hsmPin it was cleared; re-set it with another update if needed."
        }
        return result
    } catch (IllegalArgumentException iae) {
        throw iae
    } catch (Exception e) {
        mcpLogError("dashboard", "update dashboard failed", e)
        return [success: false, error: "Failed to update dashboard: ${e.message}", note: "The dashboard may be unchanged; verify with hub_get_dashboard."]
    }
}

def toolDeleteDashboard(args) {
    args = args ?: [:]
    requireDestructiveConfirm(args.confirm)
    def dashId = _requireDashboardId(args.id, " to delete")
    try {
        def raw = hubInternalGet("/dashboard/delete", [id: dashId])
        def parsed = raw ? new groovy.json.JsonSlurper().parseText(raw) : null
        // /dashboard/delete returns success:false even on a real delete, so confirm by effect.
        def present = _dashboardPresent(dashId)
        if ((parsed instanceof Map && parsed.success == true) || present == false) {
            return [success: true, id: dashId, message: "Dashboard ${dashId} deleted."]
        }
        if (present == null) {
            // Couldn't read the hub to confirm removal -- don't claim success on a destructive op.
            return [success: false, id: dashId,
                    error: "Delete request was sent, but removal could not be confirmed (the verification read failed).",
                    note: "Verify with hub_list_dashboards before retrying."]
        }
        return [success: false, id: dashId,
                error: (parsed instanceof Map) ? (parsed.message ?: parsed.error ?: "the dashboard is still present after the delete call") : "the dashboard is still present after the delete call",
                note: "Nothing was deleted. Verify the id with hub_list_dashboards."]
    } catch (Exception e) {
        mcpLogError("dashboard", "delete dashboard failed", e)
        return [success: false, id: dashId, error: e.message, note: "Nothing was deleted."]
    }
}

def toolCloneDashboard(args) {
    args = args ?: [:]
    def sourceId = _requireDashboardId(args.id, " to clone")
    // /dashboard/cloneAsEasy is session-bound and fails from the server, so clone BY VALUE: read the
    // source config and create a copy. (theme isn't in the app config, so the copy may use the default.)
    def src = toolGetDashboard([id: sourceId])
    if (!(src instanceof Map) || src.success == false) {
        return [success: false, sourceId: sourceId,
                error: "Could not read the source dashboard to clone it" + ((src instanceof Map && src.error) ? ": ${src.error}" : "."),
                note: "Verify the id with hub_list_dashboards."]
    }
    // Empty deviceIds here means the source read came up short (not bad caller input) -- a real Easy
    // Dashboard always has >=1 device. Report a runtime failure, not a caller-blaming throw from create.
    if (!(src.deviceIds)) {
        return [success: false, sourceId: sourceId,
                error: "Could read the source dashboard's id/name but not its device list, so it can't be cloned.",
                note: "Retry; if it persists, read it with hub_get_dashboard and recreate via hub_create_dashboard."]
    }
    def opts = [:]
    ["showModeTile", "showClockTile", "showCalendarTile", "showHSMTile", "showEdit", "showNavigation",
     "showTutorial", "theme", "navigationSelection", "dashboardPin", "hsmPin"].each { k ->
        if (src.containsKey(k)) opts[k] = src[k]
    }
    def created = toolCreateDashboard([name: "${src.name ?: 'Dashboard'} (copy)", deviceIds: (src.deviceIds ?: []), options: opts])
    if (created instanceof Map && created.success == true) {
        return [success: true, sourceId: sourceId, newId: created.id,
                message: "Cloned dashboard ${sourceId} by copying its config into a new dashboard${created.id ? ' (' + created.id + ')' : ''}."]
    }
    return [success: false, sourceId: sourceId,
            error: (created instanceof Map) ? (created.error ?: "clone-by-copy failed") : "unexpected response",
            note: "The copy may not have been created; verify with hub_list_dashboards."]
}

// ---- domain helpers ----

// Normalize one /dashboard/all record into a stable summary (id from installedAppId or a bare id echo).
private Map _summarizeDashboard(raw) {
    if (!(raw instanceof Map)) return [raw: raw]
    def out = [
        id: (raw.installedAppId ?: raw.id)?.toString(),
        name: raw.name
    ]
    // Pass through config fields (when present) so a read can round-trip back through hub_update_dashboard.
    ["showModeTile", "showClockTile", "showCalendarTile", "showHSMTile", "showEdit",
     "showNavigation", "showTutorial", "theme"].each { k ->
        if (raw.containsKey(k)) out[k] = raw[k]
    }
    if (raw.containsKey("navigationSelection")) out.navigationSelection = _dashboardNavSelectionCsv(raw.navigationSelection)
    // /dashboard/all gives deviceIds as a JSON-array string ("[8,1,9]"); normalize to a list of id strings.
    if (raw.containsKey("deviceIds")) out.deviceIds = _normalizeDeviceIdList(raw.deviceIds)
    if (raw.containsKey("dashboardPin")) out.dashboardPin = raw.dashboardPin
    if (raw.containsKey("hsmPin")) out.hsmPin = raw.hsmPin
    return out
}

// Serialize deviceIds to CSV for /dashboard/create|update. Coerce shape first (a String "12" is not a
// char collection): Collection as-is, String split on commas, else wrap; validate each token is numeric.
private String _dashboardDeviceCsv(deviceIds, boolean required) {
    def tokens
    if (deviceIds == null) {
        tokens = []
    } else if (deviceIds instanceof Collection) {
        tokens = deviceIds as List
    } else if (deviceIds instanceof String) {
        tokens = deviceIds.split(",") as List
    } else {
        tokens = [deviceIds]
    }
    def ids = tokens.collect { it?.toString()?.trim() }.findAll { it }
    ids.each {
        if (!(it ==~ /\d+/)) {
            throw new IllegalArgumentException("deviceIds must be numeric device ids; got '${it}'. Use hub_list_devices to find device ids.")
        }
    }
    if (required && ids.isEmpty()) {
        throw new IllegalArgumentException("deviceIds must contain at least one device id -- an Easy Dashboard requires >=1 device.")
    }
    return ids.join(",")
}

// Normalize a deviceIds value to a list of id strings: a JSON-array string ("[8,1,9]") or a real collection.
private List _normalizeDeviceIdList(raw) {
    if (raw instanceof Collection) return raw.collect { it?.toString() }.findAll { it }
    if (raw instanceof String) {
        try {
            def p = new groovy.json.JsonSlurper().parseText(raw)
            if (p instanceof List) return p.collect { it?.toString() }.findAll { it }
        } catch (Exception ignored) { }
        return (raw.replaceAll(/[\[\]\s]/, "").split(",") as List).findAll { it }
    }
    return (raw != null) ? [raw.toString()] : []
}

// Build the /dashboard/create|update query. Booleans serialize as "true"/"false"; theme empty == legacy.
// Tile/nav/pin values are read from an `options` sub-object first, else the top-level arg (keeps the flat
// tools/list catalog small while supporting direct callers). The id param (update) is added by the caller.
private Map _buildDashboardConfigQuery(Map args, String deviceCsv) {
    def boolStr = { v -> (v == true || v?.toString()?.toLowerCase() == "true") ? "true" : "false" }
    def opts = (args.options instanceof Map) ? args.options : [:]
    def opt = { String k -> opts.containsKey(k) ? opts[k] : args[k] }
    return [
        name: args.name.toString(),
        deviceIds: deviceCsv,
        showModeTile: boolStr(opt("showModeTile")),
        showClockTile: boolStr(opt("showClockTile")),
        showCalendarTile: boolStr(opt("showCalendarTile")),
        showHSMTile: boolStr(opt("showHSMTile")),
        showEdit: boolStr(opt("showEdit")),
        showNavigation: boolStr(opt("showNavigation")),
        showTutorial: boolStr(opt("showTutorial")),
        navigationSelection: _dashboardNavSelectionCsv(opt("navigationSelection")),
        dashboardPin: (opt("dashboardPin") != null) ? opt("dashboardPin").toString() : "",
        hsmPin: (opt("hsmPin") != null) ? opt("hsmPin").toString() : "",
        theme: _dashboardTheme(opt("theme"))
    ]
}

// navigationSelection: a CSV of dashboard navigation indices. The hub PERSISTS + returns it as a
// JSON-array STRING ("[1,2]", "[]"), but /dashboard/create+update parse it as a bare CSV of indices.
// So feeding a read value back VERBATIM misparses (the hub reads "[1]" as 0), corrupting nav on a
// get->update round-trip -- the exact workflow this tool advertises. Normalize every form to CSV:
// an array, a bracketed hub string ("[1,2]"), or an already-CSV string all return "1,2".
private String _dashboardNavSelectionCsv(navigationSelection) {
    if (navigationSelection == null) return ""
    def items
    if (navigationSelection instanceof Collection) {
        items = navigationSelection
    } else {
        def s = navigationSelection.toString().trim()
        if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length() - 1)
        items = s.split(",")
    }
    return items.collect { it?.toString()?.trim() }.findAll { it }.join(",")
}

// theme: legacy|light|dark|auto; empty == legacy. A value outside the set is rejected (catch a typo).
private String _dashboardTheme(theme) {
    if (theme == null || !theme.toString().trim()) return "legacy"
    def t = theme.toString().trim().toLowerCase()
    if (!(t in ["legacy", "light", "dark", "auto"])) {
        throw new IllegalArgumentException("theme must be one of: legacy, light, dark, auto (got '${theme}').")
    }
    return t
}

// Parse a create/update response into a structured write result. Honor an explicit `success` flag; only
// infer success from an echoed id when `success` is absent (a rejected write can echo an unchanged id).
private Map _dashboardWriteResult(raw, String op, String reqId) {
    def parsed
    try {
        parsed = raw ? new groovy.json.JsonSlurper().parseText(raw) : null
    } catch (Exception ignored) {
        parsed = null
    }
    if (parsed instanceof Map) {
        def ok = parsed.containsKey("success") ? (parsed.success == true) : ((parsed.installedAppId ?: parsed.id) != null)
        if (ok) {
            def out = [success: true, message: parsed.message ?: "Dashboard ${op} succeeded."]
            def dash = _summarizeDashboard(parsed)
            if (dash.id != null) out.id = dash.id
            else if (reqId != null) out.id = reqId
            if (dash.name != null) out.dashboard = dash
            return out
        }
        return [success: false,
                error: parsed.message ?: parsed.error ?: "hub reported failure",
                note: "The dashboard may be unchanged; verify with hub_list_dashboards."]
    }
    if (parsed instanceof List) {
        // A list body is NOT proof the write applied (the hub can re-render the list on a no-op too).
        return [success: false,
                error: "Dashboard ${op} returned the dashboard list instead of a status object; outcome unconfirmed.",
                note: "The ${op} may or may not have applied; verify with hub_list_dashboards.",
                dashboards: parsed.findAll { it != null }.collect { _summarizeDashboard(it) }]
    }
    return [success: false, error: "Unexpected (non-JSON) response from /dashboard/${op}.",
            note: "The ${op} may or may not have applied; verify with hub_list_dashboards."]
}

// Tool DEFINITIONS (issue #209: schema lives with the impl); gateway membership + dispatch stay in main.
def _getAllToolDefinitions_partDashboards() {
    def dashFields = [
        id: [type: "string"], name: [type: "string"],
        showModeTile: [type: "boolean"], showClockTile: [type: "boolean"],
        showCalendarTile: [type: "boolean"], showHSMTile: [type: "boolean"],
        showEdit: [type: "boolean"], showNavigation: [type: "boolean"], showTutorial: [type: "boolean"],
        navigationSelection: [type: "string"], theme: [type: "string"],
        deviceIds: [type: "array"], dashboardPin: [type: "string"], hsmPin: [type: "string"]
    ]
    return [
        [
            name: "hub_list_dashboards",
            description: "List the hub's Easy Dashboards.[[FLAT_TRIM]] Read-only; each has id, name, and tile/theme config. Resolves the dashboard token automatically, so no pinToken is normally needed.[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    pinToken: [type: "string", description: "Optional override."]
                ]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    dashboards: [type: "array", items: [type: "object", properties: dashFields]],
                    count: [type: "integer"], note: [type: "string"],
                    success: [type: "boolean"], error: [type: "string"]
                ],
                required: ["dashboards", "count"]
            ]
        ],
        [
            name: "hub_get_dashboard",
            description: "Get a dashboard's full config by id.[[FLAT_TRIM]] Read-only; returns tiles, navigation, devices, and PINs. Read before the wholesale hub_update_dashboard and pass its output straight back.[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    id: [type: "string", description: "installedAppId."],
                    pinToken: [type: "string", description: "Optional override."]
                ],
                required: ["id"]
            ],
            outputSchema: [
                type: "object",
                properties: dashFields + [
                    success: [type: "boolean"], error: [type: "string"],
                    availableIds: [type: "array"], note: [type: "string"]
                ]
            ]
        ],
        [
            name: "hub_create_dashboard",
            description: "Create an Easy Dashboard.[[FLAT_TRIM]] Write op; needs >=1 device. Tiles default off, theme legacy.[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    name: [type: "string", description: "Display name."],
                    deviceIds: [type: "array", description: "Device ids, >=1.", items: [type: "string"]],
                    options: [type: "object", description: "Optional config.[[FLAT_TRIM]] show{Mode,Clock,Calendar,HSM}Tile/showEdit/showNavigation/showTutorial (bool); navigationSelection; theme (legacy|light|dark|auto); dashboardPin; hsmPin.[[/FLAT_TRIM]]"]
                ],
                required: ["name", "deviceIds"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean"], id: [type: "string"], dashboard: [type: "object"],
                    dashboards: [type: "array"], message: [type: "string"], error: [type: "string"], note: [type: "string"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_update_dashboard",
            description: "Replace a dashboard's config wholesale by id.[[FLAT_TRIM]] Write op; pass the FULL config (omitted fields, PINs included, revert). Read hub_get_dashboard first.[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    id: [type: "string", description: "installedAppId."],
                    name: [type: "string", description: "Display name (required)."],
                    deviceIds: [type: "array", description: "Full device id set, >=1.", items: [type: "string"]],
                    options: [type: "object", description: "Same keys as hub_create_dashboard.options.[[FLAT_TRIM]] Any omitted key reverts to default.[[/FLAT_TRIM]]"]
                ],
                required: ["id", "name", "deviceIds"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean"], id: [type: "string"], dashboard: [type: "object"],
                    dashboards: [type: "array"], message: [type: "string"], error: [type: "string"], note: [type: "string"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_delete_dashboard",
            description: "⚠️ Permanently delete a dashboard by id (irreversible). Tell the user first.[[FLAT_TRIM]] Devices are NOT deleted. Write op; needs confirm=true + a backup within 24h.[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    id: [type: "string", description: "installedAppId to delete."],
                    confirm: [type: "boolean", description: "Must be true.[[FLAT_TRIM]] Confirms a recent backup + user approval.[[/FLAT_TRIM]]"]
                ],
                required: ["id", "confirm"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean"], id: [type: "string"],
                    message: [type: "string"], error: [type: "string"], note: [type: "string"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_clone_dashboard",
            description: "Clone a dashboard into a copy by id.[[FLAT_TRIM]] Write op; copies the source's config into a new dashboard (theme may default).[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    id: [type: "string", description: "Source installedAppId."]
                ],
                required: ["id"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean"], sourceId: [type: "string"], newId: [type: "string"],
                    message: [type: "string"], error: [type: "string"], note: [type: "string"]
                ],
                required: ["success"]
            ]
        ]
    ]
}

def _readOnlyToolNames_partDashboards() {
    // Read-only tools, contributed to getReadOnlyToolNames(). Absent => write+destructive by default.
    return [
        "hub_list_dashboards", "hub_get_dashboard"
    ]
}

def _idempotentWriteToolNames_partDashboards() {
    // Retry-safe writes (idempotentHint): update = wholesale (same args -> same state); delete = no-op once gone.
    // create/clone each make ANOTHER dashboard, so they are NOT idempotent.
    return [
        "hub_update_dashboard", "hub_delete_dashboard"
    ]
}

def _toolDisplayMeta_partDashboards() {
    // Title + Advanced-menu summary per tool, merged into getToolDisplayMeta().
    return [
        hub_list_dashboards: [title: "List Dashboards", summary: "List Easy Dashboards with ids, names, and tile/theme config."],
        hub_get_dashboard: [title: "Get Dashboard", summary: "Get one Easy Dashboard's full config by id (list-then-filter)."],
        hub_create_dashboard: [title: "Create Dashboard", summary: "Create a new Easy Dashboard from devices and tile options."],
        hub_update_dashboard: [title: "Update Dashboard", summary: "Replace an Easy Dashboard's config wholesale (pass the full config)."],
        hub_delete_dashboard: [title: "Delete Dashboard", summary: "Permanently delete an Easy Dashboard by id."],
        hub_clone_dashboard: [title: "Clone Dashboard", summary: "Clone an existing Easy Dashboard into a new one."]
    ]
}
