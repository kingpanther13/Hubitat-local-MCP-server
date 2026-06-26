library(name: "McpDashboardsLib", namespace: "mcp", author: "kingpanther13", description: "Canary5-v2: the McpDashboardsLib NAME plus the FULL dashboards body, marker-probed via hub_get_info (no aggregator wiring). Isolates name+content combination vs aggregator-wiring. Diagnostic.")

String mcpDashNameProbe() { return "dashname-ok-v2" }


def toolListDashboards(args = null) {
    args = args ?: [:]
    // GET /dashboard/all?pinToken=<token> -> array of dashboards. The pinToken's server-side
    // auth is UNVERIFIED (the live UI fetches this with a token from the page); pass it through
    // when supplied. Tolerate an empty/non-array body gracefully -- an empty array may mean a
    // pinToken is required rather than that no dashboards exist.
    def q = [:]
    if (args.pinToken != null) q.pinToken = args.pinToken.toString()
    def raw
    try {
        raw = hubInternalGet("/dashboard/all", q)
    } catch (Exception e) {
        mcpLogError("dashboard", "list dashboards failed", e)
        return [success: false, error: "Failed to list dashboards: ${e.message}",
                note: "The Easy Dashboard endpoint /dashboard/all may require a pinToken, or the Easy Dashboard app may not be installed. See hub_get_tool_guide(section='dashboards') if available."]
    }
    def parsed
    try {
        parsed = raw ? new groovy.json.JsonSlurper().parseText(raw) : []
    } catch (Exception e) {
        mcpLogError("dashboard", "list dashboards: unparseable response", e)
        return [success: false, error: "The hub returned an unreadable response from /dashboard/all.",
                note: "A pinToken may be required. Retry with pinToken set from the Easy Dashboard UI."]
    }
    def list = (parsed instanceof List) ? parsed : []
    def dashboards = list.findAll { it != null }.collect { _summarizeDashboard(it) }
    def result = [dashboards: dashboards, count: dashboards.size()]
    if (dashboards.isEmpty()) {
        result.note = "No Easy Dashboards returned. If you expected dashboards, this endpoint may require a pinToken -- pass pinToken (from the Easy Dashboard UI) and retry."
    }
    return result
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
    return match
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
        if (parsed instanceof Map && parsed.success == true) {
            return [success: true, id: dashId, message: parsed.message ?: "Dashboard ${dashId} deleted."]
        }
        return [success: false, id: dashId,
                error: (parsed instanceof Map) ? (parsed.message ?: parsed.error ?: "hub reported failure") : "unexpected response",
                note: "Nothing was deleted. Verify the id with hub_list_dashboards."]
    } catch (Exception e) {
        mcpLogError("dashboard", "delete dashboard failed", e)
        return [success: false, id: dashId, error: e.message, note: "Nothing was deleted."]
    }
}

def toolCloneDashboard(args) {
    args = args ?: [:]
    def dashId = _requireDashboardId(args.id, " to clone")
    try {
        // cloneAsEasy takes the source id as a PATH parameter, not a query param.
        def raw = hubInternalGet("/dashboard/cloneAsEasy/${dashId}")
        def parsed
        try {
            parsed = raw ? new groovy.json.JsonSlurper().parseText(raw) : null
        } catch (Exception e) {
            mcpLogError("dashboard", "clone dashboard ${dashId} response unparseable", e)
            parsed = null
        }
        // Strict success: honor an explicit `success` flag; only infer from an echoed id when
        // `success` is absent (the hub may return the new record under installedAppId OR a bare id --
        // Codex P2). A list echo is NOT proof the clone was created.
        boolean ok = (parsed instanceof Map) && (parsed.containsKey("success") ? (parsed.success == true) : ((parsed.installedAppId ?: parsed.id) != null))
        if (ok) {
            def out = [success: true, sourceId: dashId, message: parsed.message ?: "Dashboard ${dashId} cloned."]
            def newId = parsed.installedAppId ?: parsed.id
            if (newId != null) out.newId = newId.toString()
            return out
        }
        if (parsed instanceof List) {
            return [success: false, sourceId: dashId,
                    error: "Clone returned the dashboard list instead of a status object; outcome unconfirmed.",
                    note: "The clone may or may not have been created; verify with hub_list_dashboards.",
                    dashboards: parsed.findAll { it != null }.collect { _summarizeDashboard(it) }]
        }
        return [success: false, sourceId: dashId,
                error: (parsed instanceof Map) ? (parsed.message ?: parsed.error ?: "hub reported failure") : "unexpected response from /dashboard/cloneAsEasy",
                note: "The clone may or may not have been created; verify with hub_list_dashboards."]
    } catch (Exception e) {
        mcpLogError("dashboard", "clone dashboard failed", e)
        return [success: false, sourceId: dashId, error: e.message, note: "Nothing was cloned."]
    }
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
    if (raw.containsKey("deviceIds")) out.deviceIds = raw.deviceIds
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

// Build the shared /dashboard/create|update query map from args. Booleans serialize as the literal
// strings "true"/"false" (the verified wire); theme defaults to "legacy" (empty == legacy). The id
// param (update only) is added by the caller. deviceCsv is precomputed by the caller.
//
// The tile toggles / nav / pins are read from an `options` sub-object FIRST, falling back to the
// top-level arg of the same name. The schema advertises the compact `options` object (it keeps the
// flat tools/list catalog under the hub's size cap); the top-level fallback keeps direct/programmatic
// callers (and a caller that flattens the options) working too.
private Map _buildDashboardConfigQuery(Map args, String deviceCsv) {
    def boolStr = { v -> (v == true) ? "true" : "false" }
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
    if (navigationSelection instanceof List) {
        return navigationSelection.collect { it?.toString()?.trim() }.findAll { it }.join(",")
    }
    return navigationSelection.toString()
}

// theme: legacy|light|dark|auto; empty/unset == legacy (the verified default). A value outside the
// known set is rejected so a typo isn't silently sent to the hub.
private String _dashboardTheme(theme) {
    if (theme == null || !theme.toString().trim()) return "legacy"
    def t = theme.toString().trim()
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
def _c5InertDefs() {
    return [
        [
            name: "hub_list_dashboards",
            description: """List the hub's Easy Dashboards.
[[FLAT_TRIM]]
Read-only. Each entry has id, name, and tile/theme config. Use to discover dashboards or resolve a name to its id before the other dashboard tools. Some hubs gate this behind a pinToken: if an expected list comes back empty, pass pinToken and retry.
[[/FLAT_TRIM]]""",
            inputSchema: [
                type: "object",
                properties: [
                    pinToken: [type: "string", description: "Optional pin token.[[FLAT_TRIM]] Only if the hub requires it (empty result = the tell).[[/FLAT_TRIM]]"]
                ]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    dashboards: [type: "array", description: "Easy Dashboards on the hub", items: [type: "object", properties: [
                        id: [type: "string", description: "Dashboard installedAppId — pass as id to the other dashboard tools"],
                        name: [type: "string", description: "Dashboard name"],
                        showModeTile: [description: "Whether the Mode tile is shown"],
                        showClockTile: [description: "Whether the Clock tile is shown"],
                        showCalendarTile: [description: "Whether the Calendar tile is shown"],
                        showHSMTile: [description: "Whether the HSM tile is shown"],
                        showEdit: [description: "Whether the Edit control is shown"],
                        showNavigation: [description: "Whether navigation is shown"],
                        showTutorial: [description: "Whether the tutorial is shown"],
                        navigationSelection: [description: "Dashboard ids in the navigation menu (CSV)"],
                        theme: [description: "Theme (legacy/light/dark/auto)"],
                        deviceIds: [description: "Devices on the dashboard"],
                        dashboardPin: [description: "Dashboard PIN (only when the hub exposes it)"],
                        hsmPin: [description: "HSM PIN (only when the hub exposes it)"]
                    ]]],
                    count: [type: "integer", description: "Dashboards returned"],
                    note: [type: "string", description: "Guidance, e.g. when the list is empty (pinToken may be required)"],
                    success: [type: "boolean", description: "false only on a fetch/parse failure"],
                    error: [type: "string", description: "Failure detail (present on success:false)"]
                ],
                required: ["dashboards", "count"]
            ]
        ],
        [
            name: "hub_get_dashboard",
            description: """Get one Easy Dashboard's full config by id.
[[FLAT_TRIM]]
Read-only. Returns name, tile toggles, navigation (as a CSV), theme, devices, and PINs WHEN the hub exposes them. Lists then filters by id (no single-dashboard endpoint). Read this before hub_update_dashboard (which replaces the config wholesale) and pass its output straight back. NOTE: the hub list often omits dashboardPin/hsmPin; if they're absent here, re-supply them on update or the wholesale replace clears them.
[[/FLAT_TRIM]]""",
            inputSchema: [
                type: "object",
                properties: [
                    id: [type: "string", description: "Dashboard installedAppId.[[FLAT_TRIM]] From hub_list_dashboards.[[/FLAT_TRIM]]"],
                    pinToken: [type: "string", description: "Optional pin token.[[FLAT_TRIM]] Only if the hub requires it.[[/FLAT_TRIM]]"]
                ],
                required: ["id"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    id: [type: "string", description: "Dashboard installedAppId"],
                    name: [type: "string", description: "Dashboard name"],
                    showModeTile: [description: "Whether the Mode tile is shown"],
                    showClockTile: [description: "Whether the Clock tile is shown"],
                    showCalendarTile: [description: "Whether the Calendar tile is shown"],
                    showHSMTile: [description: "Whether the HSM tile is shown"],
                    showEdit: [description: "Whether the Edit control is shown"],
                    showNavigation: [description: "Whether navigation is shown"],
                    showTutorial: [description: "Whether the tutorial is shown"],
                    navigationSelection: [description: "Dashboard ids in the navigation menu (CSV)"],
                    theme: [description: "Theme (legacy/light/dark/auto)"],
                    deviceIds: [description: "Devices on the dashboard"],
                    dashboardPin: [description: "Dashboard PIN (only when the hub exposes it)"],
                    hsmPin: [description: "HSM PIN (only when the hub exposes it)"],
                    success: [type: "boolean", description: "false only when the id was not found or the fetch failed"],
                    error: [type: "string", description: "Failure detail (present on success:false)"],
                    availableIds: [type: "array", description: "Valid dashboard ids (present when the id was not found)"],
                    note: [type: "string", description: "Actionable guidance"]
                ]
            ]
        ],
        [
            name: "hub_create_dashboard",
            description: """Create an Easy Dashboard.
[[FLAT_TRIM]]
Write op (Write master). Requires a name and >=1 device. Tile toggles default false; theme defaults to legacy. Tell the user before creating UI surfaces on their hub.
[[/FLAT_TRIM]]""",
            inputSchema: [
                type: "object",
                properties: [
                    name: [type: "string", description: "Dashboard display name."],
                    deviceIds: [type: "array", description: "Device ids, REQUIRED >=1.", items: [type: "string"]],
                    options: [type: "object", description: "Optional config.[[FLAT_TRIM]] Bool keys show{Mode,Clock,Calendar,HSM}Tile/showEdit/showNavigation/showTutorial; navigationSelection (dashboard-id array); dashboardPin; hsmPin; theme (legacy|light|dark|auto). All default off/empty.[[/FLAT_TRIM]]"]
                ],
                required: ["name", "deviceIds"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether creation succeeded"],
                    id: [type: "string", description: "New dashboard installedAppId (when the hub returns it)"],
                    dashboard: [type: "object", description: "Created dashboard summary (when echoed by the hub)"],
                    dashboards: [type: "array", description: "Dashboard list (present when the hub echoes the full list)"],
                    message: [type: "string", description: "Human-readable result"],
                    error: [type: "string", description: "Failure detail"],
                    note: [type: "string", description: "Actionable guidance"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_update_dashboard",
            description: """Update an Easy Dashboard by id. REPLACES config wholesale.
[[FLAT_TRIM]]
Write op (Write master). Pass the FULL desired config every call: there is no cheap server-side read-merge, so any field you omit reverts to its default -- including dashboardPin/hsmPin, which an omitted value CLEARS. Read hub_get_dashboard first and re-send its config; if it didn't return the PINs (the hub list often omits them), re-supply them here or they're cleared.
[[/FLAT_TRIM]]""",
            inputSchema: [
                type: "object",
                properties: [
                    id: [type: "string", description: "Dashboard installedAppId.[[FLAT_TRIM]] From hub_list_dashboards.[[/FLAT_TRIM]]"],
                    name: [type: "string", description: "Display name, REQUIRED.[[FLAT_TRIM]] Wholesale replace.[[/FLAT_TRIM]]"],
                    deviceIds: [type: "array", description: "Full device id set, REQUIRED >=1.[[FLAT_TRIM]] Omitting one removes it.[[/FLAT_TRIM]]", items: [type: "string"]],
                    options: [type: "object", description: "Optional config.[[FLAT_TRIM]] SAME keys as hub_create_dashboard.options. Wholesale: any omitted key reverts to default.[[/FLAT_TRIM]]"]
                ],
                required: ["id", "name", "deviceIds"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the update succeeded"],
                    id: [type: "string", description: "Updated dashboard installedAppId"],
                    dashboard: [type: "object", description: "Updated dashboard summary (when echoed by the hub)"],
                    dashboards: [type: "array", description: "Dashboard list (present when the hub echoes the full list)"],
                    message: [type: "string", description: "Human-readable result"],
                    error: [type: "string", description: "Failure detail"],
                    note: [type: "string", description: "Actionable guidance"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_delete_dashboard",
            description: """⚠️ Permanently delete an Easy Dashboard by id (DESTRUCTIVE, irreversible; devices NOT deleted). Tell the user first.[[FLAT_TRIM]] Write master + confirm=true + recent backup.[[/FLAT_TRIM]]""",
            inputSchema: [
                type: "object",
                properties: [
                    id: [type: "string", description: "Dashboard installedAppId to delete.[[FLAT_TRIM]] From hub_list_dashboards.[[/FLAT_TRIM]]"],
                    confirm: [type: "boolean", description: "REQUIRED true.[[FLAT_TRIM]] Confirms a recent backup + user approval.[[/FLAT_TRIM]]"]
                ],
                required: ["id", "confirm"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the delete succeeded"],
                    id: [type: "string", description: "Deleted dashboard installedAppId"],
                    message: [type: "string", description: "Human-readable result"],
                    error: [type: "string", description: "Failure detail"],
                    note: [type: "string", description: "Actionable guidance"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_clone_dashboard",
            description: """Clone an Easy Dashboard into a new one.
[[FLAT_TRIM]]
Write op (Write master). Hubitat's cloneAsEasy: duplicates the source's tiles and config; cheaper than rebuilding via hub_create_dashboard. Returns the new id when the hub provides it.
[[/FLAT_TRIM]]""",
            inputSchema: [
                type: "object",
                properties: [
                    id: [type: "string", description: "Source dashboard installedAppId.[[FLAT_TRIM]] From hub_list_dashboards.[[/FLAT_TRIM]]"]
                ],
                required: ["id"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the clone succeeded"],
                    sourceId: [type: "string", description: "The cloned source dashboard id"],
                    newId: [type: "string", description: "The new clone's installedAppId (when the hub returns it)"],
                    dashboards: [type: "array", description: "Dashboard list (present when the hub echoes the full list)"],
                    message: [type: "string", description: "Human-readable result"],
                    error: [type: "string", description: "Failure detail"],
                    note: [type: "string", description: "Actionable guidance"]
                ],
                required: ["success"]
            ]
        ]
    ]
}

def _c5InertReadOnly() {
    // Read-only classification membership for this library's tools, contributed to the
    // app's getReadOnlyToolNames() aggregator (issue #209: per-tool metadata lives with
    // the tool). A tool absent from every part list is write+destructive by default.
    return [
        "hub_list_dashboards", "hub_get_dashboard"
    ]
}

def _c5InertIdem() {
    // Retry-safe writes (MCP idempotentHint) -- contributed to the app's
    // getIdempotentWriteToolNames() aggregator; see the classification rules there.
    //   * hub_update_dashboard: wholesale replace -> same args, same end state (idempotent).
    //   * hub_delete_dashboard: a repeat is a no-op once it's gone (idempotent).
    //   * hub_create_dashboard / hub_clone_dashboard: each call makes ANOTHER dashboard (NOT idempotent).
    return [
        "hub_update_dashboard", "hub_delete_dashboard"
    ]
}

def _c5InertDisplay() {
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
