library(name: "McpDashboardsLib", namespace: "mcp", author: "kingpanther13", description: "Easy Dashboard CRUD tool implementations for the MCP Rule Server (hub_list_dashboards/hub_get_dashboard/hub_create_dashboard/hub_update_dashboard/hub_delete_dashboard/hub_clone_dashboard). Easy Dashboards are classic child apps of the Easy Dashboard Parent, driven by GET /dashboard endpoints.")

def toolListDashboards(args = null) {
    args = args ?: [:]
    // Easy Dashboards are listed via GET /dashboard/all, but that endpoint returns an EMPTY array
    // unless it is given the dashboard pinToken the admin UI passes -- the page-global
    // globalDashboardPinToken, rendered as a literal into the /dashboard/select page. So: use a
    // caller-supplied pinToken if present, else scrape that page token server-side, then call
    // /dashboard/all. If that STILL yields nothing (token unavailable, or a locked-down hub), fall
    // back to enumerating the Easy Dashboard Parent's child apps -- needs no token (id + name only).
    def token = (args.pinToken != null) ? args.pinToken.toString() : _fetchDashboardPinToken()
    def q = [:]
    if (token) q.pinToken = token
    def parsed = null
    try {
        def raw = hubInternalGet("/dashboard/all", q)
        parsed = raw ? new groovy.json.JsonSlurper().parseText(raw) : []
    } catch (Exception e) {
        mcpLogError("dashboard", "list dashboards via /dashboard/all failed", e)
        parsed = null
    }
    // /dashboard/all aggregates BOTH Easy (version 2.x) and legacy (version 1.x) dashboards; this is
    // the Easy Dashboard surface, so keep only 2.x. A null/absent version (e.g. a create/update echo)
    // is kept. Matches the child-app fallback's Easy-only scope below.
    def list = (parsed instanceof List) ? parsed.findAll { it != null && (it.version == null || it.version.toString().startsWith("2")) } : []
    if (!list.isEmpty()) {
        def dashboards = list.collect { _summarizeDashboard(it) }
        return [dashboards: dashboards, count: dashboards.size(), source: "dashboard-all"]
    }
    // Fallback: enumerate Easy Dashboard child apps (no pinToken needed; id + name only).
    def viaApps = _listDashboardsViaChildApps()
    if (viaApps != null && !viaApps.isEmpty()) {
        return [dashboards: viaApps, count: viaApps.size(), source: "child-apps",
                note: "Listed from the Easy Dashboard Parent's child apps (/dashboard/all returned nothing -- pinToken unavailable). Each entry carries id + name only; call hub_get_dashboard for one dashboard's full config."]
    }
    return [dashboards: [], count: 0,
            note: "No Easy Dashboards found: /dashboard/all returned empty (a pinToken may be required) and no Easy Dashboard Parent child apps were present. If you expected dashboards, pass pinToken (from the Easy Dashboard UI) and retry."]
}

// Scrape the dashboard pinToken the admin UI passes to /dashboard/all. The hub renders it as a
// literal (globalDashboardPinToken = '...') into the /dashboard/select page, so fetch that page
// server-side and extract it -- a caller then never has to supply pinToken on a normal hub.
// Returns null if the page or the token can't be read; the caller then degrades to the child-app
// enumeration. The token is used only server-side (never returned to the caller).
private String _fetchDashboardPinToken() {
    try {
        def page = hubInternalGetRaw("/dashboard/select")
        if (!page) return null
        def matcher = (page =~ /globalDashboardPinToken\s*=\s*['"]([^'"]+)['"]/)
        return matcher.find() ? matcher.group(1) : null
    } catch (Exception e) {
        mcpLogError("dashboard", "fetch dashboard pinToken failed", e)
        return null
    }
}

// Enumerate Easy Dashboards as the child apps of the "Easy Dashboard Parent" via /hub2/appsList
// (no pinToken needed). Each child app IS one Easy Dashboard: its app id is the dashboard's
// installedAppId and its label is the dashboard name. Returns [[id, name], ...], or null if the
// apps list can't be read. Used only as a fallback when /dashboard/all yields nothing.
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

// Dashboard ids (installedAppId) are always numeric; reject anything else so a malformed value can't
// be spliced into the /dashboard/<id> URL (Gemini security review). Shared by get/update/delete/clone.
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
    // No dedicated single-dashboard read endpoint exists; derive from the list and filter by id.
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
    // The child-app fallback list carries only id + name. When that's all we have (no tile config),
    // read the dashboard's own app config for the full tile/device/navigation/pin shape -- stateless,
    // no pinToken -- so the result is rich enough to round-trip back through hub_update_dashboard.
    // (theme is not stored as a child-app setting, so it is the one field not recovered this way.)
    if (!match.containsKey("showClockTile")) {
        def full = _dashboardConfigFromApp(wantId)
        if (full != null) {
            full.id = wantId
            if (!full.name) full.name = match.name
            return full
        }
    }
    return match
}

// Read one Easy Dashboard's full config from its OWN app-config page (stateless, no pinToken):
// /installedapp/configure/json/<id> exposes the child app's settings -- the tile toggles (as
// "true"/"false" strings), navigationSelection, dashboardPin/hsmPin, and devicesPicked (a
// {id:name} map whose keys are the device ids). Used to enrich a child-app-sourced get so the
// shape matches _summarizeDashboard. theme is NOT a setting here (only the pinToken-gated
// /dashboard/all carries it), so it is omitted. Returns null if the page can't be read.
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
        // app-config returns "" for an unset pin and the literal string "null" for an unset hsmPin.
        if (s.dashboardPin && s.dashboardPin.toString() != "null") out.dashboardPin = s.dashboardPin
        if (s.hsmPin && s.hsmPin.toString() != "null") out.hsmPin = s.hsmPin
        return out
    } catch (Exception e) {
        mcpLogError("dashboard", "read dashboard config from app failed", e)
        return null
    }
}

// True if an Easy Dashboard with this id is still installed (child app of the Easy Dashboard
// Parent). Used to confirm a delete by effect, since /dashboard/delete returns an unreliable
// success flag. Falls back to the full list if child enumeration is unavailable.
private boolean _dashboardPresent(String id) {
    def viaApps = _listDashboardsViaChildApps()
    if (viaApps != null) return viaApps.any { it.id?.toString() == id }
    def listed = toolListDashboards([:])
    def dashboards = (listed instanceof Map) ? (listed.dashboards ?: []) : []
    return dashboards.any { it.id?.toString() == id }
}

def toolCreateDashboard(args) {
    args = args ?: [:]
    if (!args.name?.toString()?.trim()) {
        throw new IllegalArgumentException("name is required (the dashboard's display name).")
    }
    def deviceCsv = _dashboardDeviceCsv(args.deviceIds, true)
    def q = _buildDashboardConfigQuery(args, deviceCsv)
    // CREATE-ONLY: the Vue dashboard editor sends version=2.0 on create (and strips it on update) to
    // create an Easy Dashboard rather than the legacy variant. Verified against vue-hub2.min.js.
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
    // deviceIds is required by the create endpoint (>=1 device); update shares the same shape, so a
    // wholesale update must carry the full device set too. Require it so a caller doesn't silently
    // blank the dashboard's devices.
    def deviceCsv = _dashboardDeviceCsv(args.deviceIds, true)
    def q = _buildDashboardConfigQuery(args, deviceCsv)
    q.id = updateId
    try {
        def raw = hubInternalGet("/dashboard/update", q)
        return _dashboardWriteResult(raw, "update", updateId)
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
        // /dashboard/delete returns {success:false,message:null} even when it DID delete the
        // dashboard -- its success flag is unreliable -- so confirm by effect: the delete worked
        // iff the dashboard is no longer present. Honor an explicit success:true as well.
        boolean gone = !_dashboardPresent(dashId)
        if ((parsed instanceof Map && parsed.success == true) || gone) {
            return [success: true, id: dashId, message: "Dashboard ${dashId} deleted."]
        }
        return [success: false, id: dashId,
                error: (parsed instanceof Map) ? (parsed.message ?: parsed.error ?: "the dashboard is still present after the delete call") : "unexpected response",
                note: "Nothing was deleted. Verify the id with hub_list_dashboards."]
    } catch (Exception e) {
        mcpLogError("dashboard", "delete dashboard failed", e)
        return [success: false, id: dashId, error: e.message, note: "Nothing was deleted."]
    }
}

def toolCloneDashboard(args) {
    args = args ?: [:]
    def sourceId = _requireDashboardId(args.id, " to clone")
    // The hub's /dashboard/cloneAsEasy endpoint does NOT work from the server -- it is session-bound
    // (returns success:false and creates nothing even with a session cookie). So clone BY VALUE: read
    // the source's config and create a copy. (theme is not in the source's app config, so unless the
    // source list carried it, the copy uses the default theme.)
    def src = toolGetDashboard([id: sourceId])
    if (!(src instanceof Map) || src.success == false) {
        return [success: false, sourceId: sourceId,
                error: "Could not read the source dashboard to clone it" + ((src instanceof Map && src.error) ? ": ${src.error}" : "."),
                note: "Verify the id with hub_list_dashboards."]
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

// ---- domain helpers (private to the dashboards library) ----

// Normalize one raw dashboard map from /dashboard/all into a stable summary shape. The hub keys
// the dashboard by installedAppId; expose both id (the chaining key) and name.
private Map _summarizeDashboard(raw) {
    if (!(raw instanceof Map)) return [raw: raw]
    def out = [
        // The hub keys the dashboard by installedAppId, but a create/update response may instead
        // echo the new record under a bare `id` -- accept either so the read shape always carries
        // a chainable id (Codex P2).
        id: (raw.installedAppId ?: raw.id)?.toString(),
        name: raw.name
    ]
    // Pass through the config fields the write tools accept, when present, so a caller can read a
    // dashboard then re-send the same shape to hub_update_dashboard (which replaces wholesale).
    // navigationSelection is normalized to a CSV string below so a round-trip back through update
    // (which expects a CSV) doesn't re-send an array the hub would reject (Codex P1).
    ["showModeTile", "showClockTile", "showCalendarTile", "showHSMTile", "showEdit",
     "showNavigation", "showTutorial", "theme"].each { k ->
        if (raw.containsKey(k)) out[k] = raw[k]
    }
    if (raw.containsKey("navigationSelection")) out.navigationSelection = _dashboardNavSelectionCsv(raw.navigationSelection)
    // /dashboard/all returns deviceIds as a JSON-array-as-STRING ("[8,1,9]"); normalize to a list of
    // id strings so the shape round-trips back through hub_update_dashboard (and so a clone-by-value
    // can re-send it) instead of being mis-split on its brackets.
    if (raw.containsKey("deviceIds")) out.deviceIds = _normalizeDeviceIdList(raw.deviceIds)
    // Include the PINs when the hub returns them so a read-then-update round-trip preserves them
    // (the update sends "" for an absent pin, which would CLEAR the pin -- Codex P1). Many hub list
    // payloads omit pins; in that case they're simply absent here and the caller must re-supply them.
    if (raw.containsKey("dashboardPin")) out.dashboardPin = raw.dashboardPin
    if (raw.containsKey("hsmPin")) out.hsmPin = raw.hsmPin
    return out
}

// Serialize deviceIds to the CSV the /dashboard/create|update endpoints want.
// NORMALIZE SHAPE FIRST: a bare String "12" is NOT a collection of ids -- iterating it with .collect
// would walk its characters ("1","2" -> "1,2"). So coerce to a list of id tokens: a Collection is used
// as-is; a String is split on commas (a caller may pass "12,34"); anything else (a lone number) is
// wrapped. Then validate each token is numeric so a bad value is a clean IllegalArgumentException
// (-32602), not an opaque hub error. When `required` and the list is empty, throws (endpoint needs >=1).
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

// Normalize a deviceIds value into a list of id strings. /dashboard/all hands back a
// JSON-array-as-string ("[8,1,9]"); the child-app/app-config path gives a real collection. Both
// become ["8","1","9"] so the read shape can be re-sent to a write tool unchanged.
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

// Build the shared /dashboard/create|update query map from args. Booleans serialize as the literal
// strings "true"/"false" (the verified wire); theme defaults to "legacy" (empty == legacy). The id
// param (update only) is added by the caller. deviceCsv is precomputed by the caller.
//
// The tile toggles / nav / pins are read from an `options` sub-object FIRST, falling back to the
// top-level arg of the same name. The schema advertises the compact `options` object (it keeps the
// flat tools/list catalog under the hub's size cap); the top-level fallback keeps direct/programmatic
// callers (and a caller that flattens the options) working too.
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

// navigationSelection is a CSV of dashboard ids (may be empty). Accept an array or a pre-joined
// string; normalize to a CSV string.
private String _dashboardNavSelectionCsv(navigationSelection) {
    if (navigationSelection == null) return ""
    if (navigationSelection instanceof Collection) {
        return navigationSelection.collect { it?.toString()?.trim() }.findAll { it }.join(",")
    }
    return navigationSelection.toString()
}

// theme: legacy|light|dark|auto; empty/unset == legacy (the verified default). A value outside the
// known set is rejected so a typo isn't silently sent to the hub.
private String _dashboardTheme(theme) {
    if (theme == null || !theme.toString().trim()) return "legacy"
    def t = theme.toString().trim().toLowerCase()
    if (!(t in ["legacy", "light", "dark", "auto"])) {
        throw new IllegalArgumentException("theme must be one of: legacy, light, dark, auto (got '${theme}').")
    }
    return t
}

// Parse a create/update response into the structured write result. The endpoint returns JSON
// (likely {success, ...} and/or the new/updated dashboard). On success echo the dashboard summary.
private Map _dashboardWriteResult(raw, String op, String reqId) {
    def parsed
    try {
        parsed = raw ? new groovy.json.JsonSlurper().parseText(raw) : null
    } catch (Exception ignored) {
        parsed = null
    }
    if (parsed instanceof Map) {
        // Strict success: honor an explicit `success` flag. Do NOT infer success from an echoed
        // id -- a rejected write can still echo the unchanged record (success:false + an id). Only
        // fall back to an echoed id when `success` is absent entirely; accept installedAppId OR a
        // bare id (the create/clone path may return the new record under either -- Codex P2).
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
        // The endpoint echoed the dashboard list rather than a status object. A list body is NOT proof
        // the write applied (the hub can re-render the list on a no-op/rejection too), so do not assume
        // success -- report inconclusive and tell the caller to verify.
        return [success: false,
                error: "Dashboard ${op} returned the dashboard list instead of a status object; outcome unconfirmed.",
                note: "The ${op} may or may not have applied; verify with hub_list_dashboards.",
                dashboards: parsed.findAll { it != null }.collect { _summarizeDashboard(it) }]
    }
    return [success: false, error: "Unexpected (non-JSON) response from /dashboard/${op}.",
            note: "The ${op} may or may not have applied; verify with hub_list_dashboards."]
}

// Tool DEFINITIONS (issue #209: schema lives with the impl). Concatenated into getAllToolDefinitions()
// in the main app; gateway membership + dispatch cases stay in main.
def _getAllToolDefinitions_partDashboards() {
    // Shared per-dashboard output shape (field names are self-describing; navigationSelection is a CSV).
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
    // Read-only classification membership for this library's tools, contributed to the
    // app's getReadOnlyToolNames() aggregator (issue #209: per-tool metadata lives with
    // the tool). A tool absent from every part list is write+destructive by default.
    return [
        "hub_list_dashboards", "hub_get_dashboard"
    ]
}

def _idempotentWriteToolNames_partDashboards() {
    // Retry-safe writes (MCP idempotentHint) -- contributed to the app's
    // getIdempotentWriteToolNames() aggregator; see the classification rules there.
    //   * hub_update_dashboard: wholesale replace -> same args, same end state (idempotent).
    //   * hub_delete_dashboard: a repeat is a no-op once it's gone (idempotent).
    //   * hub_create_dashboard / hub_clone_dashboard: each call makes ANOTHER dashboard (NOT idempotent).
    return [
        "hub_update_dashboard", "hub_delete_dashboard"
    ]
}

def _toolDisplayMeta_partDashboards() {
    // Human-facing title/summary per tool (MCP annotations.title + the Advanced per-tool
    // overrides menu) -- merged into the app's getToolDisplayMeta() aggregator (issue #209).
    return [
        hub_list_dashboards: [title: "List Dashboards", summary: "List Easy Dashboards with ids, names, and tile/theme config."],
        hub_get_dashboard: [title: "Get Dashboard", summary: "Get one Easy Dashboard's full config by id (list-then-filter)."],
        hub_create_dashboard: [title: "Create Dashboard", summary: "Create a new Easy Dashboard from devices and tile options."],
        hub_update_dashboard: [title: "Update Dashboard", summary: "Replace an Easy Dashboard's config wholesale (pass the full config)."],
        hub_delete_dashboard: [title: "Delete Dashboard", summary: "Permanently delete an Easy Dashboard by id."],
        hub_clone_dashboard: [title: "Clone Dashboard", summary: "Clone an existing Easy Dashboard into a new one."]
    ]
}
