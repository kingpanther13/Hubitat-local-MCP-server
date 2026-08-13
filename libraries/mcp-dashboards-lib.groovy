library(name: "McpDashboardsLib", namespace: "mcp", author: "kingpanther13", description: "Dashboard CRUD tools (list/get/create/update/delete/clone) covering Easy Dashboards and legacy Hubitat® Dashboards for the MCP Rule Server.")

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
    // Keep Easy (2.x) AND legacy Hubitat® (1.x) dashboards; a null/absent version (create/update
    // echo, which only the Easy endpoints produce) is kept and classified easy.
    def list = (parsed instanceof List) ? parsed.findAll { it != null && (it.version == null || it.version.toString().startsWith("2") || it.version.toString().startsWith("1")) } : []
    if (!list.isEmpty()) {
        def dashboards = list.collect { _summarizeDashboard(it) }
        return [dashboards: dashboards, count: dashboards.size(), source: "dashboard-all"]
    }
    def viaApps = _listDashboardsViaChildApps()
    if (viaApps != null && !viaApps.isEmpty()) {
        // Don't blame a missing pinToken for a hub ERROR: only mention pinToken when /dashboard/all was empty.
        def note = allErrored
            ? "/dashboard/all errored (logged); listed via the dashboard parents' child apps instead. Each entry carries id + name + type only; call hub_get_dashboard for full config."
            : "Listed from the dashboard parents' child apps (/dashboard/all returned nothing -- a pinToken may be required). Each entry carries id + name + type only; call hub_get_dashboard for full config."
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
                ? "No dashboards found: /dashboard/all errored (logged) and the dashboard parents have no child apps. Retry; if it persists, check hub connectivity."
                : "No dashboards found: /dashboard/all returned empty (a pinToken may be required) and no dashboard-parent child apps were present. If you expected dashboards, pass pinToken and retry."]
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

// Enumerate dashboards as child apps of their parents via /hub2/appsList (no token): the "Easy
// Dashboard Parent" (easy) and the legacy "Hubitat® Dashboard" parent (legacy). Returns
// [id:..., name:..., type:...] maps, or null if unreadable. Callers: the list fallback AND _dashboardPresent.
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
            def t = d.type?.toString() ?: ""
            if (t == "Easy Dashboard Parent") {
                (node?.children ?: []).each { c ->
                    def cd = c?.data ?: [:]
                    if (cd.id != null) out << [id: cd.id?.toString(), name: cd.name, type: "easy"]
                }
            } else if (_isLegacyDashboardParentType(t)) {
                (node?.children ?: []).each { c ->
                    def cd = c?.data ?: [:]
                    if (cd.id != null && cd.type?.toString() == "Dashboard") out << [id: cd.id?.toString(), name: cd.name, type: "legacy"]
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

// The legacy parent's app-type name carries the (R) glyph between the words ("Hubitat® Dashboard"),
// so match the stable ASCII prefix/suffix rather than the exact string -- an encoding difference
// anywhere in the chain would otherwise hide every legacy dashboard.
private boolean _isLegacyDashboardParentType(String t) {
    return t && t.startsWith("Hubitat") && t.endsWith("Dashboard")
}

// Dashboard ids are numeric; reject anything else so a bad value can't be spliced into /dashboard/<id>.
private String _requireDashboardId(rawId, String verbNote) {
    def s = rawId?.toString()?.trim()
    if (!s) {
        throw new IllegalArgumentException("dashboardId is required (the dashboard installedAppId${verbNote}). Call hub_list_dashboards to find it.")
    }
    if (!(s ==~ /\d+/)) {
        throw new IllegalArgumentException("dashboardId must be a numeric dashboard installedAppId; got '${rawId}'. Call hub_list_dashboards to find it.")
    }
    return s
}

def toolGetDashboard(args) {
    args = args ?: [:]
    def wantId = _requireDashboardId(args.dashboardId, "")
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
    if (match.type == "legacy") {
        return _getLegacyDashboard(wantId, match)
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

// ---- legacy Hubitat® Dashboard helpers ----
// A legacy dashboard is a classic child app of the "Hubitat® Dashboard" parent. Its design lives in
// the app's own state.layout, served by the dashboard's first-party REST surface
// /apps/api/<id>/dashboard/<id>/layout -- authenticated with the app's OAuth access_token PLUS a
// per-page-load requestToken minted by rendering the dashboard page. GET returns the layout JSON
// (tiles + grid + colors); POST replaces it wholesale and echoes the saved layout back.

// Probe whether an installed app is a legacy dashboard via /installedapp/statusJson/<id>.
// TRI-STATE: [legacy: true, label, + accessToken/deviceIds/localAccess when readable] |
// [legacy: false] | null (the status read FAILED -- unknown, callers must not blind-fire a
// write at the id).
private Map _legacyDashboardProbe(String id) {
    def raw
    try {
        raw = hubInternalGet("/installedapp/statusJson/${id}")
    } catch (Exception e) {
        mcpLogError("dashboard", "legacy dashboard probe failed for ${id}", e)
        return null
    }
    if (!raw) return null
    def parsed
    try { parsed = new groovy.json.JsonSlurper().parseText(raw) } catch (Exception e) {
        mcpLogError("dashboard", "legacy dashboard probe: statusJson for ${id} was not JSON", e)
        return null
    }
    def ia = (parsed instanceof Map) ? parsed.installedApp : null
    // statusJson answers {} (not 404) for a deleted id -- that is a definitive "not a legacy
    // dashboard", distinct from the read-failure null above.
    if (!(ia instanceof Map) || ia.name?.toString() != "Dashboard" || ia.systemAppType != true) return [legacy: false]
    def out = [legacy: true, label: ia.label]
    (parsed.appState ?: []).each { s ->
        if (s?.name?.toString() == "accessToken") out.accessToken = s.value?.toString()
    }
    def devices = (parsed.appSettings ?: []).find { it?.name?.toString() == "devicesPicked" }
    if (devices?.deviceIdsForDeviceList != null) out.deviceIds = _normalizeDeviceIdList(devices.deviceIdsForDeviceList)
    def la = (parsed.appSettings ?: []).find { it?.name?.toString() == "localAccess" }
    if (la != null) out.localAccess = la.value?.toString() != "false"
    return out
}

// Render the dashboard page to mint the requestToken the layout endpoint demands. Every page load
// mints a fresh one, so each read/write mints its own instead of caching (tokens are timestamped
// server-side and stale ones are rejected).
private String _legacyMintRequestToken(String id, String accessToken) {
    def html = hubInternalGet("/apps/api/${id}/dashboard/${id}", [access_token: accessToken])
    if (!html) return null
    def m = (html =~ /javascriptRequestToken\s*=\s*["']([^"']+)["']/)
    return m.find() ? m.group(1) : null
}

// Read the current layout JSON. Returns the parsed Map, or null when the token/page/layout read
// came up empty (transport errors bubble to the caller for logging).
private Map _legacyLayoutFetch(String id, String accessToken) {
    if (!accessToken) return null
    def rt = _legacyMintRequestToken(id, accessToken)
    if (!rt) return null
    def raw = hubInternalGet("/apps/api/${id}/dashboard/${id}/layout", [access_token: accessToken, requestToken: rt])
    def parsed = raw ? new groovy.json.JsonSlurper().parseText(raw) : null
    return (parsed instanceof Map) ? parsed : null
}

// Save a layout wholesale. The endpoint echoes the saved layout back (with `name` re-stamped from
// the app label server-side) -- callers use that echo as the write confirmation.
private Map _legacyLayoutPost(String id, String accessToken, Map layout) {
    def rt = _legacyMintRequestToken(id, accessToken)
    if (!rt) {
        throw new IllegalStateException("Could not mint a dashboard requestToken (the dashboard page render failed); the layout was not saved.")
    }
    def resp = hubInternalPostJson("/apps/api/${id}/dashboard/${id}/layout",
        groovy.json.JsonOutput.toJson(layout), 420, false, [access_token: accessToken, requestToken: rt])
    return (resp instanceof Map) ? resp : null
}

// Full legacy read shape: the list summary + authorized deviceIds + the layout (tiles/grid/colors).
private Map _getLegacyDashboard(String id, Map match) {
    def probe = _legacyDashboardProbe(id)
    if (probe == null || probe.legacy != true) {
        return match + [partial: true, note: "Listed as a legacy dashboard but its status page was unreadable; layout unavailable (not empty). Retry hub_get_dashboard."]
    }
    def out = [id: id, name: (probe.label ?: match.name), type: "legacy"]
    if (probe.deviceIds != null) out.deviceIds = probe.deviceIds
    if (!probe.accessToken) {
        return out + [partial: true, note: "The dashboard's access token could not be read from its app state, so its layout endpoint is unreachable. Open the dashboard app page once and retry."]
    }
    def layout = null
    try { layout = _legacyLayoutFetch(id, probe.accessToken) } catch (Exception e) {
        mcpLogError("dashboard", "legacy layout read failed for ${id}", e)
    }
    if (layout instanceof Map) {
        out.layout = layout
    } else {
        out.partial = true
        out.note = "The dashboard's layout endpoint was unreadable (details logged); tiles/grid are unavailable, not empty. Retry hub_get_dashboard." +
            (probe.localAccess == false ? " Note: this dashboard has 'Allow LAN access' disabled, which can block its layout endpoint." : "")
    }
    return out
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
        def out = [id: id, name: parsed?.app?.label, type: "easy"]
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
    // Create requires name; Easy additionally needs >=1 deviceId (tile toggles, theme, navigation,
    // and pins optional). Legacy starts as an empty grid; deviceIds is its authorized-device list.
    if (!args.name?.toString()?.trim()) {
        throw new IllegalArgumentException("name is required (the dashboard's display name).")
    }
    if (_dashboardTypeArg(args.type) == "legacy") {
        return _createLegacyDashboard(args)
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

// type arg: easy (default) | legacy. Rejects anything else so a typo can't silently create the wrong kind.
private String _dashboardTypeArg(raw) {
    def t = raw?.toString()?.trim()?.toLowerCase()
    if (!t) return "easy"
    if (!(t in ["easy", "legacy"])) {
        throw new IllegalArgumentException("type must be 'easy' or 'legacy' (got '${raw}').")
    }
    return t
}

// Find the legacy "Hubitat® Dashboard" parent's installed-app id via /hub2/appsList. When the
// built-in parent isn't installed, bootstrap it through the hub's Add Built-In App link (the
// same GET /installedapp/sysApp/<type name> flow the RM-family parent discovery uses). The
// parent is a self-installing singleton (installOnOpen), so the GET can never duplicate it.
private Integer _legacyDashboardParentId() {
    def node = _findLegacyDashboardParentNode()
    if (node == null) {
        // Literal name, no pre-encoding -- the HTTP layer encodes the path; pre-encoding gets
        // double-encoded and the hub then matches no app.
        def created = null
        try {
            created = hubInternalGetRaw("/installedapp/sysApp/Hubitat® Dashboard")
        } catch (Exception e) {
            mcpLogError("dashboard", "legacy dashboard parent bootstrap (Add Built-In App) failed", e)
        }
        node = _findLegacyDashboardParentNode()
        if (node == null && created?.location) {
            // /hub2/appsList can lag right after creation -- fall back to the id in the redirect.
            def lm = (created.location.toString() =~ /\/installedapp\/configure\/(\d+)/)
            if (lm.find()) node = [id: lm.group(1), installed: true]
        }
        if (node != null) {
            if (node.installed != true) {
                // installOnOpen normally self-installs the singleton; commit defensively when a
                // firmware surfaces it install-pending (same belt-and-braces as the RM bootstrap).
                try { _commitUserAppInstall(node.id.toString().toInteger(), null) } catch (Exception e) {
                    mcpLog("warn", "dashboard", "legacy dashboard parent install commit unverified: ${e.message}")
                }
            }
            mcpLog("info", "dashboard", "legacy Hubitat® Dashboard parent bootstrapped via Add Built-In App (id ${node.id})")
        }
    }
    if (node?.id == null) return null
    try { return node.id.toString().toInteger() } catch (NumberFormatException ignored) { return null }
}

private Map _findLegacyDashboardParentNode() {
    try {
        def raw = hubInternalGet("/hub2/appsList")
        if (!raw) return null
        def parsed = new groovy.json.JsonSlurper().parseText(raw)
        Map found = null
        def walk
        walk = { node ->
            if (found != null) return
            def d = node?.data ?: [:]
            if (_isLegacyDashboardParentType(d.type?.toString()) && d.id != null && d.hidden != true) {
                found = [id: d.id, installed: d.installed]
                return
            }
            (node?.children ?: []).each { walk(it) }
        }
        (parsed?.apps ?: []).each { walk(it) }
        return found
    } catch (Exception e) {
        mcpLogError("dashboard", "legacy dashboard parent lookup failed", e)
        return null
    }
}

// Classic-page form POST against a legacy dashboard child (label + settings writes). The legacy
// child's mainPage is submitOnChange with no commit button, so a bare /installedapp/update/json
// POST commits each field directly.
private Map _legacyDashboardFormPost(String appId, Map fields) {
    def body = [formAction: "update", id: appId, version: "1", appTypeId: "", appTypeName: "",
                currentPage: "mainPage", pageBreadcrumbs: "[]", referrer: "", url: "", _cancellable: "false"]
    body.putAll(fields)
    def resp = hubInternalPostForm("/installedapp/update/json", body)
    if (resp?.status != null && resp.status >= 400) {
        throw new IllegalStateException("classic form POST failed for app ${appId}: status=${resp.status}")
    }
    return resp
}

// Create a legacy dashboard: createchild under the legacy parent (the shell self-installs and mints
// its OAuth access token), then set the label and (optionally) the authorized-device list over the
// classic form wire. The layout starts empty; tiles are added via hub_update_dashboard.
private Map _createLegacyDashboard(Map args) {
    def name = args.name.toString().trim()
    if (args.options != null) {
        throw new IllegalArgumentException("options applies only to Easy Dashboards. A legacy dashboard's look lives in its layout -- create it first, then pass setOptions/addTiles to hub_update_dashboard.")
    }
    def deviceCsv = _dashboardDeviceCsv(args.deviceIds, false)
    def parentId = _legacyDashboardParentId()
    if (parentId == null) {
        return [success: false,
                error: "The legacy Hubitat® Dashboard parent app was not found and could not be auto-installed via Add Built-In App (details logged), so no legacy dashboard can be created.",
                note: "Install the built-in 'Hubitat® Dashboard' app via Apps > Add built-in app and retry, or create an Easy Dashboard instead (omit type)."]
    }
    def resp
    try {
        resp = hubInternalGetRaw("/installedapp/createchild/hubitat/Dashboard/parent/${parentId}")
    } catch (Exception e) {
        mcpLogError("dashboard", "legacy dashboard createchild failed", e)
        return [success: false, error: "Failed to create the legacy dashboard: ${e.message}", note: "Nothing was created."]
    }
    def loc = resp?.location?.toString()
    // != null, not truthiness: Groovy coerces a Matcher to boolean via find(), which would consume
    // the match before the explicit find() below.
    def m = loc ? (loc =~ /\/installedapp\/configure\/(\d+)/) : null
    def newId = (m != null && m.find()) ? m.group(1) : null
    if (!newId) {
        return [success: false,
                error: "createchild did not return a new app id (status=${resp?.status}).",
                note: "Nothing may have been created; check hub_list_dashboards for an unnamed 'Dashboard' entry."]
    }
    def warnings = []
    boolean labelApplied = false
    try {
        _legacyDashboardFormPost(newId, [label: name, ("label.type"): "text", ("label.multiple"): "false"])
        labelApplied = true
    } catch (Exception e) {
        warnings << "label write failed (${e.message}) -- the dashboard keeps the default 'Dashboard' label; rename via hub_update_dashboard(name=...)."
    }
    if (deviceCsv) {
        try {
            // capability multi-selects need the type + multiple=true sidecars in the same POST, or
            // the setting's multi flag flips and the app page breaks.
            _legacyDashboardFormPost(newId, [
                ("settings[devicesPicked]"): deviceCsv,
                ("devicesPicked.type"): "capability.*",
                ("devicesPicked.multiple"): "true"
            ])
        } catch (Exception e) {
            warnings << "device authorization failed (${e.message}) -- re-apply via hub_update_dashboard(deviceIds=[...])."
        }
    }
    def out = [success: true, id: newId, type: "legacy",
               note: "Legacy dashboard created with an empty layout. Add tiles with hub_update_dashboard (addTiles / setOptions)."]
    // Echo name only when the label write took -- on failure the hub label is the default
    // 'Dashboard', and a result claiming the requested name would contradict its own warning.
    if (labelApplied) out.name = name
    if (warnings) out.warnings = warnings
    return out
}

def toolUpdateDashboard(args) {
    args = args ?: [:]
    def updateId = _requireDashboardId(args.dashboardId, " to update")
    def probe = _legacyDashboardProbe(updateId)
    if (probe == null) {
        // Unknown target type: the Easy path's wholesale /dashboard/update MUST NOT be blind-fired
        // at a possibly-legacy id, so fail safe instead of guessing.
        return [success: false, id: updateId,
                error: "Could not determine the dashboard's type (the status read failed), so no update was attempted.",
                note: "Transient hub error (details logged); retry."]
    }
    if (probe.legacy == true) {
        return _updateLegacyDashboard(updateId, probe, args)
    }
    def legacyOnlyArgs = ["layout", "setOptions", "addTiles", "updateTiles", "removeTileIds"].findAll { args[it] != null }
    if (legacyOnlyArgs) {
        throw new IllegalArgumentException("${legacyOnlyArgs.join(', ')} appl${legacyOnlyArgs.size() == 1 ? 'ies' : 'y'} only to legacy Hubitat® Dashboards; dashboard ${updateId} is an Easy Dashboard. Easy updates replace the config wholesale: pass name, deviceIds, and options (read hub_get_dashboard first).")
    }
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

// Legacy update surface: name (app label), deviceIds (authorized-device list, wholesale), and the
// layout -- either `layout` (wholesale replace) or the granular ops (removeTileIds -> updateTiles ->
// addTiles -> setOptions, applied in that order in ONE save). At least one of those args is required.
private Map _updateLegacyDashboard(String id, Map probe, Map args) {
    def granular = ["setOptions", "addTiles", "updateTiles", "removeTileIds"].findAll { args[it] != null }
    def hasWholesale = args.layout != null
    def hasName = args.name?.toString()?.trim()
    def hasDevices = args.deviceIds != null
    if (hasWholesale && granular) {
        throw new IllegalArgumentException("Pass either layout (wholesale replace) or the granular ops (${granular.join(', ')}), not both.")
    }
    if (!hasWholesale && !granular && !hasName && !hasDevices) {
        throw new IllegalArgumentException("Dashboard ${id} is a legacy Hubitat® Dashboard: pass name (label), deviceIds (authorized devices), layout (wholesale), or any of setOptions / addTiles / updateTiles / removeTileIds. The Easy-Dashboard options arg does not apply. See hub_get_tool_guide(section='dashboards').")
    }
    if (args.options != null) {
        throw new IllegalArgumentException("options applies only to Easy Dashboards. For a legacy dashboard, grid/color/font settings are layout fields -- pass them via setOptions (e.g. setOptions: [bgColor: '#000000', cols: 6]).")
    }
    def out = [success: true, id: id, type: "legacy"]
    def applied = []
    def warnings = []
    if (hasName) {
        def newName = args.name.toString().trim()
        try {
            _legacyDashboardFormPost(id, [label: newName, ("label.type"): "text", ("label.multiple"): "false"])
            out.name = newName
            applied << "name"
        } catch (Exception e) {
            mcpLogError("dashboard", "legacy dashboard rename failed", e)
            return [success: false, id: id, type: "legacy", applied: applied,
                    error: "Rename failed: ${e.message}", note: "Nothing after the rename was attempted; the layout is unchanged."]
        }
    }
    if (hasDevices) {
        def csv = _dashboardDeviceCsv(args.deviceIds, false)
        try {
            _legacyDashboardFormPost(id, [
                ("settings[devicesPicked]"): csv,
                ("devicesPicked.type"): "capability.*",
                ("devicesPicked.multiple"): "true"
            ])
            probe.deviceIds = csv ? (csv.split(",") as List) : []
            out.deviceIds = probe.deviceIds
            applied << "deviceIds"
        } catch (Exception e) {
            mcpLogError("dashboard", "legacy dashboard device authorization failed", e)
            return [success: false, id: id, type: "legacy", applied: applied,
                    error: "Device authorization write failed: ${e.message}", note: "The layout was not touched; retry deviceIds (and any layout ops) once the hub responds."]
        }
    }
    if (hasWholesale || granular) {
        if (!probe.accessToken) {
            return [success: false, id: id, type: "legacy", applied: applied,
                    error: "The dashboard's access token could not be read from its app state, so its layout endpoint is unreachable.",
                    note: "Open the dashboard app page once (or reset its token) and retry."]
        }
        Map newLayout
        if (hasWholesale) {
            if (!(args.layout instanceof Map)) {
                throw new IllegalArgumentException("layout must be an object -- the full layout JSON, as returned by hub_get_dashboard.")
            }
            newLayout = new LinkedHashMap(args.layout as Map)
        } else {
            Map current = null
            try { current = _legacyLayoutFetch(id, probe.accessToken) } catch (Exception e) {
                mcpLogError("dashboard", "legacy layout read-before-edit failed", e)
            }
            if (!(current instanceof Map)) {
                return [success: false, id: id, type: "legacy", applied: applied,
                        error: "Could not read the current layout to apply the edits.",
                        note: "Retry; if it persists, check hub connectivity." +
                            (probe.localAccess == false ? " Note: this dashboard has 'Allow LAN access' disabled, which can block its layout endpoint." : "")]
            }
            newLayout = _applyLegacyLayoutOps(current, args, probe, warnings)
        }
        Map saved = null
        try {
            saved = _legacyLayoutPost(id, probe.accessToken, newLayout)
        } catch (Exception e) {
            mcpLogError("dashboard", "legacy layout save failed", e)
            return [success: false, id: id, type: "legacy", applied: applied,
                    error: "Layout save failed: ${e.message}", note: "The layout may be unchanged; verify with hub_get_dashboard."]
        }
        if (!(saved instanceof Map) || !(saved.tiles instanceof List)) {
            return [success: false, id: id, type: "legacy", applied: applied,
                    error: "The layout endpoint did not echo a saved layout; outcome unconfirmed.",
                    note: "Verify with hub_get_dashboard."]
        }
        applied << (hasWholesale ? "layout" : granular.join(", "))
        out.tileCount = saved.tiles.size()
        out.layout = saved
    }
    out.applied = applied
    if (warnings) out.warnings = warnings
    return out
}

// Apply the granular ops to a copy of the current layout: removals, then updates, then adds, then
// options. All validation throws BEFORE the single save, so a bad op leaves the layout untouched.
private Map _applyLegacyLayoutOps(Map current, Map args, Map probe, List warnings) {
    def layout = new LinkedHashMap(current)
    def tiles = (layout.tiles instanceof List) ? layout.tiles.findAll { it instanceof Map }.collect { new LinkedHashMap(it as Map) } : []
    if (args.removeTileIds != null) {
        def rawIds = (args.removeTileIds instanceof Collection) ? args.removeTileIds : [args.removeTileIds]
        def ids = rawIds.collect { _requireTileId(it, "removeTileIds") }
        // Delete semantics are retry-safe: an id that is already gone is skipped with a warning, not an error.
        ids.each { tid ->
            if (!tiles.any { _tileIdOf(it) == tid }) warnings << "removeTileIds: no tile with id ${tid} (already removed?); skipped."
        }
        tiles.removeAll { _tileIdOf(it) in ids }
    }
    if (args.updateTiles != null) {
        if (!(args.updateTiles instanceof Collection)) {
            throw new IllegalArgumentException("updateTiles must be an array of {id, ...fields-to-change} objects.")
        }
        args.updateTiles.each { spec ->
            if (!(spec instanceof Map) || spec.id == null) {
                throw new IllegalArgumentException("Each updateTiles entry needs an id plus the fields to change (e.g. [id: 3, col: 2, colSpan: 2]).")
            }
            def tid = _requireTileId(spec.id, "updateTiles")
            def tile = tiles.find { _tileIdOf(it) == tid }
            if (tile == null) {
                throw new IllegalArgumentException("updateTiles: no tile with id ${tid}. Existing tile ids: ${tiles.collect { it.id }}.")
            }
            spec.each { k, v -> if (k?.toString() != "id") tile[k] = v }
            _warnUnauthorizedTileDevice(tile, probe, warnings)
        }
    }
    if (args.addTiles != null) {
        if (!(args.addTiles instanceof Collection)) {
            throw new IllegalArgumentException("addTiles must be an array of tile objects ({template, col, row, device?, colSpan?, rowSpan?, ...}).")
        }
        // isEmpty check, not Elvis: a real max id of 0 is falsy in Groovy, so `.max() ?: -1` would
        // hand the next add id 0 again -- two tiles sharing an id makes later id-addressed ops ambiguous.
        def existingIds = tiles.collect { _tileIdOf(it) }.findAll { it != null }
        int nextId = (existingIds.isEmpty() ? -1 : existingIds.max()) + 1
        args.addTiles.each { spec ->
            if (!(spec instanceof Map)) throw new IllegalArgumentException("Each addTiles entry must be a tile object.")
            def missing = ["template", "col", "row"].findAll { spec[it] == null }
            if (missing) {
                throw new IllegalArgumentException("addTiles: each tile needs template, col, and row (missing ${missing.join(', ')} in ${spec}).")
            }
            // Skip an add identical to an existing tile so a retried call can't stack duplicates
            // (keeps hub_update_dashboard honest about its idempotent annotation).
            def dup = tiles.find { t ->
                ["template", "device", "col", "row", "templateExtra"].every { k -> t[k]?.toString() == spec[k]?.toString() } &&
                    (t.colSpan ?: 1).toString() == (spec.colSpan ?: 1).toString() &&
                    (t.rowSpan ?: 1).toString() == (spec.rowSpan ?: 1).toString()
            }
            if (dup != null) {
                warnings << "addTiles: an identical tile already exists (id ${dup.id}); skipped (retry-safe)."
                return
            }
            def tile = new LinkedHashMap(spec as Map)
            tile.id = nextId++
            if (tile.colSpan == null) tile.colSpan = 1
            if (tile.rowSpan == null) tile.rowSpan = 1
            _warnUnauthorizedTileDevice(tile, probe, warnings)
            tiles << tile
        }
    }
    if (args.setOptions != null) {
        if (!(args.setOptions instanceof Map)) {
            throw new IllegalArgumentException("setOptions must be an object of top-level layout fields (e.g. cols, rows, bgColor, iconSize, fontSize, gridGap, roundedCorners).")
        }
        args.setOptions.each { k, v ->
            def key = k?.toString()
            if (key == "tiles") throw new IllegalArgumentException("setOptions cannot replace tiles; use addTiles / updateTiles / removeTileIds (or a wholesale layout).")
            if (key == "name") throw new IllegalArgumentException("The dashboard name is its app label; pass the top-level name arg, not setOptions.name.")
            layout[key] = v
        }
    }
    layout.tiles = tiles
    return layout
}

// Tile ids are small integers assigned at add time; normalize whatever shape the caller (or a
// JSON round-trip) delivers.
private Integer _tileIdOf(tile) {
    def raw = (tile instanceof Map) ? tile.id : tile
    if (raw == null) return null
    try { return raw.toString().trim() as Integer } catch (Exception ignored) { return null }
}

private Integer _requireTileId(raw, String op) {
    def id = _tileIdOf(raw)
    if (id == null) {
        throw new IllegalArgumentException("${op}: tile ids are integers (got '${raw}'). Read the current layout with hub_get_dashboard to see them.")
    }
    return id
}

// A tile bound to a device the dashboard hasn't authorized renders empty -- warn (don't block; the
// caller may authorize afterward via deviceIds).
private void _warnUnauthorizedTileDevice(Map tile, Map probe, List warnings) {
    def dev = tile.device?.toString()
    if (dev && dev ==~ /\d+/ && probe.deviceIds != null && !probe.deviceIds.contains(dev)) {
        warnings << "Tile ${tile.id}: device ${dev} is not in this dashboard's authorized devices, so the tile will show no data. Authorize it via hub_update_dashboard(deviceIds=[...full list...])."
    }
}

def toolDeleteDashboard(args) {
    args = args ?: [:]
    requireDestructiveConfirm(args.confirm)
    def dashId = _requireDashboardId(args.dashboardId, " to delete")
    def legacyProbe = _legacyDashboardProbe(dashId)
    if (legacyProbe == null) {
        // Unknown target type (status read failed): don't route by guess. The Easy /dashboard/delete
        // is a no-op for a legacy dashboard, so a mis-route can end in a false verdict -- fail safe,
        // same as update.
        return [success: false, id: dashId,
                error: "Could not determine the dashboard's type (the status read failed), so no delete was attempted.",
                note: "Transient hub error (details logged); retry."]
    }
    if (legacyProbe.legacy == true) {
        // /dashboard/delete does NOT remove a legacy child (verified live: success:false and the app
        // stays), so route through the classic force-delete. That endpoint can answer 500 for a
        // delete that actually committed -- confirm by effect, same as the Easy path.
        try {
            _rmForceDeleteApp(dashId as Integer)
        } catch (Exception e) {
            mcpLog("debug", "dashboard", "legacy dashboard forcedelete answered '${e.message}' -- verifying by effect")
        }
        def present = _dashboardPresent(dashId)
        if (present == false) {
            return [success: true, id: dashId, type: "legacy", message: "Legacy dashboard ${dashId} deleted."]
        }
        if (present == null) {
            return [success: false, id: dashId, type: "legacy",
                    error: "Delete request was sent, but removal could not be confirmed (the verification read failed).",
                    note: "Verify with hub_list_dashboards before retrying."]
        }
        return [success: false, id: dashId, type: "legacy",
                error: "the legacy dashboard is still present after the force-delete call",
                note: "Nothing was deleted. hub_delete_native_app(appId=${dashId}, force=true) snapshots first and uses the same delete path."]
    }
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
    def sourceId = _requireDashboardId(args.dashboardId, " to clone")
    // /dashboard/cloneAsEasy is session-bound and fails from the server, so clone BY VALUE: read the
    // source config and create a copy. (theme isn't in the app config, so the copy may use the default.)
    def src = toolGetDashboard([dashboardId: sourceId])
    if (!(src instanceof Map) || src.success == false) {
        return [success: false, sourceId: sourceId,
                error: "Could not read the source dashboard to clone it" + ((src instanceof Map && src.error) ? ": ${src.error}" : "."),
                note: "Verify the id with hub_list_dashboards."]
    }
    if (src.type == "legacy") {
        return _cloneLegacyDashboard(sourceId, src)
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

// Legacy clone-by-value: create a fresh legacy dashboard (same authorized devices), then copy the
// source's layout into it wholesale. The source is never touched.
private Map _cloneLegacyDashboard(String sourceId, Map src) {
    if (!(src.layout instanceof Map)) {
        return [success: false, sourceId: sourceId,
                error: "Could read the legacy dashboard but not its layout, so it can't be cloned.",
                note: "Retry; if it persists, inspect it with hub_get_dashboard."]
    }
    def created = _createLegacyDashboard([name: "${src.name ?: 'Dashboard'} (copy)", deviceIds: (src.deviceIds ?: [])])
    if (!(created instanceof Map) || created.success != true) {
        return [success: false, sourceId: sourceId,
                error: (created instanceof Map ? created.error : null) ?: "legacy clone: creating the copy failed",
                note: "The copy may not have been created; verify with hub_list_dashboards."]
    }
    def newId = created.id?.toString()
    def probe = _legacyDashboardProbe(newId)
    String saveError = null
    if (probe?.legacy == true && probe.accessToken) {
        try {
            def saved = _legacyLayoutPost(newId, probe.accessToken, new LinkedHashMap(src.layout as Map))
            // Same write confirmation as the update path: a dropped/empty POST body returns null
            // (no throw), which must not pass as a written layout.
            if (!(saved instanceof Map) || !(saved.tiles instanceof List)) {
                saveError = "the layout endpoint did not echo a saved layout (outcome unconfirmed)"
            }
        } catch (Exception e) { saveError = e.message }
    } else {
        saveError = "the new dashboard's access token was unreadable"
    }
    if (saveError) {
        return [success: false, sourceId: sourceId, newId: newId, type: "legacy",
                error: "Copy ${newId} was created but its layout could not be written: ${saveError}",
                note: "Re-apply with hub_update_dashboard(dashboardId=${newId}, layout=<the source's layout from hub_get_dashboard>)."]
    }
    def out = [success: true, sourceId: sourceId, newId: newId, type: "legacy",
               message: "Cloned legacy dashboard ${sourceId} by copying its layout into a new dashboard (${newId})."]
    // A copy that landed with caveats (e.g. device authorization didn't take) must say so.
    if (created.warnings) out.warnings = created.warnings
    return out
}

// ---- domain helpers ----

// Normalize one /dashboard/all record into a stable summary (id from installedAppId or a bare id echo).
private Map _summarizeDashboard(raw) {
    if (!(raw instanceof Map)) return [raw: raw]
    def out = [
        id: (raw.installedAppId ?: raw.id)?.toString(),
        name: raw.name,
        // version 1.x = legacy Hubitat® Dashboard; 2.x = Easy. A create/update echo carries no
        // version, and only the Easy endpoints produce those.
        type: raw.version?.toString()?.startsWith("1") ? "legacy" : "easy"
    ]
    // Pass through config fields (when present) so a read can round-trip back through hub_update_dashboard.
    ["showModeTile", "showClockTile", "showCalendarTile", "showHSMTile", "showEdit",
     "showNavigation", "showTutorial", "theme"].each { k ->
        if (raw.containsKey(k)) out[k] = raw[k]
    }
    if (raw.containsKey("navigationSelection")) out.navigationSelection = _dashboardNavSelectionCsv(raw.navigationSelection)
    // /dashboard/all gives deviceIds as a JSON-array string ("[8,1,9]"); normalize to a list of id strings.
    if (raw.containsKey("deviceIds")) out.deviceIds = _normalizeDeviceIdList(raw.deviceIds)
    // Unset pins echo as "", "null", or (on legacy records) "None" -- all mean "no pin"; only a real
    // value is worth surfacing.
    if (_dashboardRealValue(raw.dashboardPin)) out.dashboardPin = raw.dashboardPin
    if (_dashboardRealValue(raw.hsmPin)) out.hsmPin = raw.hsmPin
    return out
}

// The hub renders unset optional fields as the literals "null" or "None" (Groovy toString of null)
// depending on the endpoint; treat both as absent.
private boolean _dashboardRealValue(v) {
    def s = v?.toString()
    return s && s != "null" && s != "None"
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
    if (navigationSelection == null || !_dashboardRealValue(navigationSelection)) return ""
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
        id: [type: "string"], name: [type: "string"], type: [type: "string"],
        showModeTile: [type: "boolean"], showClockTile: [type: "boolean"],
        showCalendarTile: [type: "boolean"], showHSMTile: [type: "boolean"],
        showEdit: [type: "boolean"], showNavigation: [type: "boolean"], showTutorial: [type: "boolean"],
        navigationSelection: [type: "string"], theme: [type: "string"],
        deviceIds: [type: "array"], dashboardPin: [type: "string"], hsmPin: [type: "string"]
    ]
    return [
        [
            name: "hub_list_dashboards",
            description: "List the hub's dashboards -- both Easy Dashboards and legacy Hubitat® Dashboards.[[FLAT_TRIM]] Read-only; each entry carries id, name, and a type ('easy' or 'legacy'). Easy entries also include tile/theme config. Resolves the dashboard token automatically, so no pinToken is normally needed. See hub_get_tool_guide(section='dashboards').[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    pinToken: [type: "string", description: "Optional pin token. Auto-resolved when omitted."]
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
            description: "Get a dashboard's full config by id. Easy Dashboard: tiles/theme/devices/PINs. Legacy Hubitat® Dashboard: its authorized deviceIds plus the nested layout (tiles, grid, colors).[[FLAT_TRIM]] Read this before a wholesale hub_update_dashboard and pass its output straight back. A read that partly fails returns partial:true with a note (values are unavailable, not defaulted). See hub_get_tool_guide(section='dashboards').[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    dashboardId: [type: "string", description: "installedAppId."],
                    pinToken: [type: "string", description: "Optional pin token. Auto-resolved when omitted."]
                ],
                required: ["dashboardId"]
            ],
            outputSchema: [
                type: "object",
                properties: dashFields + [
                    layout: [type: "object"], partial: [type: "boolean"],
                    success: [type: "boolean"], error: [type: "string"],
                    availableIds: [type: "array"], note: [type: "string"]
                ]
            ]
        ],
        [
            name: "hub_create_dashboard",
            description: "Create a dashboard. type='easy' (default) builds an Easy Dashboard from devices + tile options; type='legacy' creates an empty legacy Hubitat® Dashboard whose deviceIds are its authorized-device list.[[FLAT_TRIM]] A legacy dashboard starts with an empty layout -- add tiles afterward with hub_update_dashboard (addTiles / setOptions) -- and rejects the Easy-only options arg. See hub_get_tool_guide(section='dashboards').[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    name: [type: "string", description: "Display name."],
                    type: [type: "string", enum: ["easy", "legacy"], description: "Dashboard kind: 'easy' (default) or 'legacy' Hubitat® Dashboard."],
                    deviceIds: [type: "array", description: "Device ids. Required (>=1, runtime-enforced) for an Easy Dashboard; for a legacy dashboard these are its authorized devices (optional).", items: [type: "string"]],
                    options: [type: "object", description: "Easy-only optional config; see hub_get_tool_guide(section='dashboards'). Rejected for type='legacy'."]
                ],
                required: ["name"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean"], id: [type: "string"], type: [type: "string"],
                    name: [type: "string"], dashboard: [type: "object"], dashboards: [type: "array"],
                    message: [type: "string"], error: [type: "string"], note: [type: "string"],
                    warnings: [type: "array"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_update_dashboard",
            description: "Update a dashboard by id; behavior depends on its kind. Easy Dashboard: wholesale config replace -- read hub_get_dashboard first and pass the FULL config back (name + deviceIds required; omitted fields revert to default). Legacy Hubitat® Dashboard: edit its name (label), deviceIds (authorized devices), and/or layout.[[FLAT_TRIM]] Legacy layout edits are EITHER `layout` (wholesale object) OR the granular ops removeTileIds / updateTiles / addTiles / setOptions (not both); ops apply in that order in one save and are retry-safe (an already-gone removal and an identical add are skipped with a warning). Passing a legacy layout arg at an Easy Dashboard, or options at a legacy one, is rejected. See hub_get_tool_guide(section='dashboards').[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    dashboardId: [type: "string", description: "installedAppId."],
                    name: [type: "string", description: "Display name (Easy) or app label (legacy)."],
                    deviceIds: [type: "array", description: "Full device id set. Required (>=1) for Easy; wholesale-replaces a legacy dashboard's authorized devices.", items: [type: "string"]],
                    options: [type: "object", description: "Easy-only. Same keys as hub_create_dashboard.options."],
                    layout: [type: "object", description: "Legacy-only. Wholesale layout replace (the hub_get_dashboard object)."],
                    setOptions: [type: "object", description: "Legacy-only. Merge top-level layout fields (cols, rows, bgColor, iconSize, fontSize, gridGap)."],
                    addTiles: [type: "array", description: "Legacy-only. Tiles to add; each needs template, col, row (device/colSpan/rowSpan/templateExtra optional).", items: [type: "object"]],
                    updateTiles: [type: "array", description: "Legacy-only. Tile edits; each entry is {id, ...fields-to-change}.", items: [type: "object"]],
                    removeTileIds: [type: "array", description: "Legacy-only. Tile ids to remove; ids already gone are skipped.", items: [type: "integer"]]
                ],
                required: ["dashboardId"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean"], id: [type: "string"], type: [type: "string"],
                    name: [type: "string"], deviceIds: [type: "array"],
                    applied: [type: "array"], tileCount: [type: "integer"], layout: [type: "object"],
                    dashboard: [type: "object"], dashboards: [type: "array"], message: [type: "string"],
                    error: [type: "string"], note: [type: "string"], warnings: [type: "array"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_delete_dashboard",
            description: "⚠️ Permanently delete a dashboard by id (irreversible), Easy or legacy Hubitat®. Tell the user first.[[FLAT_TRIM]] A legacy dashboard is removed through the classic force-delete and confirmed by effect; the result carries its type. See hub_get_tool_guide(section='dashboards').[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    dashboardId: [type: "string", description: "installedAppId to delete."],
                    confirm: [type: "boolean", description: "Must be true (requires a recent backup + user approval)."]
                ],
                required: ["dashboardId", "confirm"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean"], id: [type: "string"], type: [type: "string"],
                    message: [type: "string"], error: [type: "string"], note: [type: "string"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_clone_dashboard",
            description: "Clone a dashboard into a copy by id. Easy: copies its config. Legacy Hubitat®: creates a new legacy dashboard with the same authorized devices and copies the layout wholesale (clone-by-value; the source is never touched).[[FLAT_TRIM]] See hub_get_tool_guide(section='dashboards').[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    dashboardId: [type: "string", description: "Source installedAppId."]
                ],
                required: ["dashboardId"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean"], sourceId: [type: "string"], newId: [type: "string"],
                    type: [type: "string"], message: [type: "string"], error: [type: "string"], note: [type: "string"]
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
        hub_list_dashboards: [title: "List Dashboards", summary: "List Easy and legacy Hubitat® Dashboards with ids, names, and type."],
        hub_get_dashboard: [title: "Get Dashboard", summary: "Get one dashboard's full config by id (Easy config or legacy layout)."],
        hub_create_dashboard: [title: "Create Dashboard", summary: "Create an Easy Dashboard or an empty legacy Hubitat® Dashboard."],
        hub_update_dashboard: [title: "Update Dashboard", summary: "Update a dashboard: Easy wholesale, or legacy name/devices/layout edits."],
        hub_delete_dashboard: [title: "Delete Dashboard", summary: "Permanently delete an Easy or legacy Hubitat® Dashboard by id."],
        hub_clone_dashboard: [title: "Clone Dashboard", summary: "Clone an Easy or legacy Hubitat® Dashboard into a new one."]
    ]
}
