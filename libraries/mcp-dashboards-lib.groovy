library(name: "McpDashboardsLib", namespace: "mcp", author: "kingpanther13", description: "Dashboard tools (list, get, create/copy, update, delete) covering Easy Dashboards and legacy Hubitat® Dashboards for the MCP Rule Server.")

def toolListDashboards(args = null) {
    return _readDashboards().out
}

def toolGetDashboard(args) {
    args = args ?: [:]
    String wantId = _requireDashboardId(args.dashboardId, "dashboardId", " to read")
    def read = _readDashboards()
    return read.out.success == false ? read.out : _getDashboard(wantId, read)
}

// One read of the hub's dashboard list: [out: <the public list result>, records: <the raw rows>].
// /dashboard/all returns [] unless given the page-global pinToken from /dashboard/select, and the hub
// honours that token only in the session (HUBSESSION cookie) that served the page, so scrape it and
// list in its session; if still empty, fall back to the dashboard parents' child apps. The raw rows
// carry the legacy accessToken, so they never leave this library.
private Map _readDashboards() {
    def page = _fetchDashboardPinToken()
    def q = page?.token ? [pinToken: page.token] : [:]
    def parsed = null
    boolean allErrored = false   // true iff /dashboard/all threw (hub error), not merely empty
    try {
        def raw = page?.session ? hubInternalGetInSession("/dashboard/all", q, page.session as String) : hubInternalGet("/dashboard/all", q)
        parsed = raw ? new groovy.json.JsonSlurper().parseText(raw) : []
    } catch (Exception e) {
        mcpLogError("dashboard", "list dashboards via /dashboard/all failed", e)
        allErrored = true
    }
    // Keep Easy (2.x) AND legacy Hubitat® (1.x) dashboards; an absent version is classified easy.
    def records = (parsed instanceof List) ? parsed.findAll { r ->
        def v = (r instanceof Map) ? r.version?.toString() : "x"
        v == null || v.startsWith("2") || v.startsWith("1")
    } : []
    if (records) {
        def dashboards = records.collect { _summarizeDashboard(it) }
        return [out: [dashboards: dashboards, count: dashboards.size(), source: "dashboard-all"], records: records]
    }
    def viaApps = null   // null = the child-app read FAILED; [] = it read and found none
    boolean incomplete = false   // the app tree had nodes of an unexpected shape, so some may be missing
    try {
        def tree = _dashboardAppTree()
        incomplete = tree.malformed
        viaApps = tree.rows.findAll { row ->
            row.id != null && (row.parentType == "Easy Dashboard Parent" ||
                (_isLegacyDashboardParentType(row.parentType) && row.type?.toString() == "Dashboard"))
        }.collect { row ->
            [id: row.id.toString(), name: row.name, type: row.parentType == "Easy Dashboard Parent" ? "easy" : "legacy"]
        }
    } catch (Exception e) {
        mcpLogError("dashboard", "enumerate dashboards via child apps failed", e)
    }
    String partialNote = incomplete ? " Part of the app inventory had an unexpected shape, so this list may be missing dashboards." : ""
    if (viaApps) {
        def note = (allErrored
            ? "/dashboard/all errored (logged); listed via the dashboard parents' child apps instead. Each entry carries id + name + type only; call hub_get_dashboard for the full config."
            : "Listed from the dashboard parents' child apps (/dashboard/all returned nothing${page?.session ? '' : ': the dashboard page token or its session could not be read'}). Each entry carries id + name + type only; call hub_get_dashboard for the full config.") + partialNote
        def out = [dashboards: viaApps, count: viaApps.size(), source: "child-apps", note: note]
        if (incomplete) out.partial = true
        return [out: out, records: viaApps]
    }
    if (viaApps == null) {
        // Neither source could be read: never claim a confident "zero dashboards".
        return [out: [success: false,
                error: allErrored
                    ? "Could not list dashboards: both /dashboard/all and the child-app fallback failed to read."
                    : "Could not confirm the dashboard list: /dashboard/all returned nothing and the child-app fallback read failed.",
                note: "Transient hub error (details logged). Retry; if it persists, check hub connectivity."], records: []]
    }
    def empty = [dashboards: [], count: 0,
            note: (allErrored
                ? "No dashboards found: /dashboard/all errored (logged) and the dashboard parents have no child apps. Retry; if it persists, check hub connectivity."
                : "No dashboards found: /dashboard/all returned empty and no dashboard-parent child apps were present.") + partialNote]
    if (incomplete) empty.partial = true
    return [out: empty, records: []]
}

// Scrape globalDashboardPinToken from the /dashboard/select page, with the session cookie that page
// opened ([token, session]). Null if unreadable (the list then degrades to child-app enumeration).
private Map _fetchDashboardPinToken() {
    try {
        // hubInternalGetRaw returns a struct [status, location, data, setCookie]; the body is .data.
        def pageResp = hubInternalGetRaw("/dashboard/select")
        def html = pageResp?.data?.toString()
        def matcher = html ? (html =~ /globalDashboardPinToken\s*=\s*['"]([^'"]+)['"]/) : null
        if (!matcher?.find()) {
            mcpLog("warn", "dashboard", "No dashboard pin token on /dashboard/select (HTTP ${pageResp?.status}); listing without it.")
            return null
        }
        if (!pageResp?.setCookie) mcpLog("warn", "dashboard", "/dashboard/select set no session cookie; its pin token cannot be used.")
        return [token: matcher.group(1), session: pageResp?.setCookie]
    } catch (Exception e) {
        mcpLogError("dashboard", "fetch dashboard pinToken failed", e)
        return null
    }
}

// One /hub2/appsList read flattened to [id, type, parentType, name, installed, hidden, hasData] rows,
// so every parent/child lookup filters a single read. malformed is set when a node is not the
// expected shape, since a skipped node may be a dashboard. Throws when unreadable.
private Map _dashboardAppTree() {
    def raw = hubInternalGet("/hub2/appsList")
    def parsed = raw ? new groovy.json.JsonSlurper().parseText(raw) : null
    if (!(parsed instanceof Map) || !(parsed.apps instanceof List)) throw new IllegalStateException("the app inventory is unavailable")
    def rows = []
    boolean malformed = false
    def walk
    walk = { node, String parentType ->
        if (!(node instanceof Map) || (node.data != null && !(node.data instanceof Map)) ||
                (node.children != null && !(node.children instanceof List))) {
            malformed = true
            return
        }
        def d = node.data ?: [:]
        def type = d.type != null ? d.type : node.type
        rows << [id: (d.id != null ? d.id : node.id), type: type, parentType: parentType, name: d.name,
                 installed: d.installed, hidden: d.hidden, hasData: node.data != null]
        (node.children ?: []).each { walk(it, type?.toString()) }
    }
    parsed.apps.each { walk(it, null) }
    return [rows: rows, malformed: malformed]
}

// The legacy parent's app-type name carries the (R) glyph between the words ("Hubitat® Dashboard"),
// so match the stable ASCII prefix/suffix -- an encoding difference anywhere in the chain would
// otherwise hide every legacy dashboard.
private boolean _isLegacyDashboardParentType(String t) {
    return t && t.startsWith("Hubitat") && t.endsWith("Dashboard")
}

// Dashboard ids are numeric; reject anything else so a bad value can't be spliced into a path.
private String _requireDashboardId(rawId, String param, String verbNote) {
    def s = rawId?.toString()?.trim()
    if (!s) {
        throw new IllegalArgumentException("${param} is required (the dashboard id${verbNote}). Call hub_list_dashboards to find it.")
    }
    if (!(s ==~ /\d+/)) {
        throw new IllegalArgumentException("${param} must be a numeric dashboard id; got '${rawId}'. Call hub_list_dashboards to find it.")
    }
    return s
}

// The full read of one dashboard from an already-read list (no single-dashboard endpoint exists).
private Map _getDashboard(String id, Map read) {
    def rec = (read.records ?: []).find { r -> (r.installedAppId ?: r.id)?.toString() == id }
    if (rec == null) {
        def available = (read.out.dashboards ?: []).collect { it.id }.findAll { it != null }
        if (read.out.partial) {
            return [success: false, error: "Dashboard '${id}' was not among the dashboards read, but the dashboard list was incomplete, so it may still exist.",
                    availableIds: available, partial: true,
                    note: "Part of the hub's app inventory had an unexpected shape. Retry, or check the hub's Dashboards page."]
        }
        return [success: false, error: "No dashboard found with id '${id}'.",
                availableIds: available,
                note: available.isEmpty()
                    ? "The list returned no dashboards; check the hub's Dashboards page."
                    : "Call hub_list_dashboards to see valid ids."]
    }
    boolean fromAll = read.out.source == "dashboard-all"
    Map summary = fromAll ? _summarizeDashboard(rec) : rec
    if (summary.type == "legacy") return _getLegacyDashboard(id, summary, fromAll ? rec : null)
    // A /dashboard/all row already carries the full Easy config; a child-app row has only id + name.
    if (summary.containsKey("showClockTile")) return summary
    def full = _dashboardConfigFromApp(id)
    if (full != null) {
        if (!full.name) full.name = summary.name
        return full
    }
    // The enrichment read FAILED: flag it, so "config unreadable" isn't mistaken for an all-default config.
    return summary + [partial: true, note: "Only id + name could be read (the dashboard's config page was unreadable); tile/device/pin fields are unavailable, not defaulted. Retry hub_get_dashboard."]
}

// ---- legacy Hubitat® Dashboard helpers ----
// A legacy dashboard is a classic child app of the "Hubitat® Dashboard" parent. Its design lives in
// the app's own state.layout, served by /apps/api/<id>/dashboard/<id>/layout -- authenticated with
// the app's OAuth access_token PLUS a per-page-load requestToken minted by rendering the dashboard
// page. GET returns the layout JSON (tiles + grid + colors); POST replaces it and echoes it back.

// Read /installedapp/statusJson/<id>. TRI-STATE: null when the read FAILED (the dashboard's kind is
// then unknown, and an Easy write at a legacy id would misfire); else [present, legacy] plus, for a
// legacy dashboard, label and, when readable, accessToken / deviceIds / localAccess. statusJson
// answers {} (not 404) for a removed or never-existing id, so present:false is a definitive "gone".
private Map _dashboardStatus(String id) {
    def parsed
    try {
        def raw = hubInternalGet("/installedapp/statusJson/${id}")
        parsed = raw ? new groovy.json.JsonSlurper().parseText(raw) : null
    } catch (Exception e) {
        mcpLogError("dashboard", "dashboard status read failed for ${id}", e)
        return null
    }
    if (!(parsed instanceof Map)) return null
    def ia = parsed.installedApp
    boolean present = ia instanceof Map && !ia.isEmpty()
    if (!present || ia.name?.toString() != "Dashboard" || ia.systemAppType != true) return [present: present, legacy: false]
    def out = [present: true, legacy: true, label: ia.label]
    def token = (parsed.appState ?: []).find { it?.name?.toString() == "accessToken" }
    if (token?.value != null) out.accessToken = token.value.toString()
    def devices = (parsed.appSettings ?: []).find { it?.name?.toString() == "devicesPicked" }
    if (devices?.deviceIdsForDeviceList != null) out.deviceIds = _normalizeDeviceIdList(devices.deviceIdsForDeviceList)
    def la = (parsed.appSettings ?: []).find { it?.name?.toString() == "localAccess" }
    if (la != null) out.localAccess = la.value?.toString() != "false"
    return out
}

// The layout endpoint can be blocked by a disabled 'Allow LAN access'; say so as precisely as known.
private String _lanAccessHint(Map status) {
    if (status?.localAccess == true) return ""
    if (status?.localAccess == false) return " Note: this dashboard has 'Allow LAN access' disabled, which can block its layout endpoint."
    return " If this dashboard has 'Allow LAN access' disabled, that can block its layout endpoint."
}

// Render the dashboard page to mint the requestToken the layout endpoint demands. Every page load
// mints a fresh one and stale ones are rejected, so each read/write mints its own.
private String _legacyMintRequestToken(String id, String accessToken) {
    def html = hubInternalGet("/apps/api/${id}/dashboard/${id}", [access_token: accessToken])
    if (!html) return null
    def m = (html =~ /javascriptRequestToken\s*=\s*["']([^"']+)["']/)
    return m.find() ? m.group(1) : null
}

// Read the current layout JSON. Null when the token/page/layout read came up empty; a transport
// error throws instead, so its message can be logged with the context of the failed operation.
private Map _legacyLayoutFetch(String id, String accessToken) {
    def rt = _legacyMintRequestToken(id, accessToken)
    if (!rt) return null
    def raw = hubInternalGet("/apps/api/${id}/dashboard/${id}/layout", [access_token: accessToken, requestToken: rt])
    def parsed = raw ? new groovy.json.JsonSlurper().parseText(raw) : null
    return (parsed instanceof Map) ? parsed : null
}

// Save a layout wholesale. The endpoint echoes the saved layout back (with `name` re-stamped from
// the app label); only an echo carrying a tiles List confirms the save, so anything else returns null.
private Map _legacyLayoutPost(String id, String accessToken, Map layout) {
    def rt = _legacyMintRequestToken(id, accessToken)
    if (!rt) {
        throw new IllegalStateException("Could not mint a dashboard requestToken (the dashboard page render failed); the layout was not saved.")
    }
    def resp = hubInternalPostJson("/apps/api/${id}/dashboard/${id}/layout",
        groovy.json.JsonOutput.toJson(layout), 420, false, [access_token: accessToken, requestToken: rt])
    return (resp instanceof Map && resp.tiles instanceof List) ? resp : null
}

// Full legacy read: id, name, authorized deviceIds, and the layout. The /dashboard/all row carries
// the access token and devices, so the status page is read only when one of them is missing.
private Map _getLegacyDashboard(String id, Map summary, Map rec) {
    def out = [id: id, name: summary.name, type: "legacy"]
    if (summary.deviceIds != null) out.deviceIds = summary.deviceIds
    String token = _dashboardRealValue(rec?.accessToken) ? rec.accessToken.toString() : null
    Map status = null
    if (!token || out.deviceIds == null) {
        status = _dashboardStatus(id)
        if (status?.legacy != true) {
            return out + [partial: true, note: "Listed as a legacy dashboard but its status page was unreadable; layout unavailable (not empty). Retry hub_get_dashboard."]
        }
        if (status.label) out.name = status.label
        if (out.deviceIds == null && status.deviceIds != null) out.deviceIds = status.deviceIds
        if (!token) token = status.accessToken
    }
    if (!token) {
        return out + [partial: true, note: "The dashboard's access token could not be read, so its layout endpoint is unreachable. Open the dashboard app page once and retry."]
    }
    def layout = null
    try { layout = _legacyLayoutFetch(id, token) } catch (Exception e) {
        mcpLogError("dashboard", "legacy layout read failed for ${id}", e)
    }
    if (layout instanceof Map) return out + [layout: layout]
    return out + [partial: true, note: "The dashboard's layout endpoint was unreadable (details logged); tiles/grid are unavailable, not empty. Retry hub_get_dashboard." + _lanAccessHint(status)]
}

// Read one Easy dashboard's full config from /installedapp/configure/json/<id> (no token): tile
// toggles (as "true"/"false"), navigationSelection, dashboardPin/hsmPin, devicesPicked ({id:name}). No theme.
private Map _dashboardConfigFromApp(String id) {
    try {
        def raw = hubInternalGet("/installedapp/configure/json/${id}")
        if (!raw) return null
        def parsed = new groovy.json.JsonSlurper().parseText(raw)
        def s = parsed?.settings
        if (!(s instanceof Map)) return null
        def out = [id: id, name: parsed?.app?.label, type: "easy"]
        _dashboardTileKeys().each { k ->
            if (s.containsKey(k)) out.put(k, s.get(k)?.toString() == "true")
        }
        if (s.containsKey("navigationSelection")) out.navigationSelection = _dashboardNavSelectionCsv(s.navigationSelection)
        if (s.devicesPicked instanceof Map) out.deviceIds = s.devicesPicked.keySet().collect { it.toString() }
        // app-config returns "" for an unset PIN ("null" and "None" are treated as unset too).
        if (_dashboardRealValue(s.dashboardPin)) out.dashboardPin = s.dashboardPin
        if (_dashboardRealValue(s.hsmPin)) out.hsmPin = s.hsmPin
        return out
    } catch (Exception e) {
        mcpLogError("dashboard", "read dashboard config from app failed", e)
        return null
    }
}

def toolCreateDashboard(args) {
    return _createDashboard(args ?: [:])
}

private Map _createDashboard(Map args) {
    if (args.sourceId != null) return _copyDashboard(args)
    // Create requires name; Easy additionally needs >=1 deviceId. Legacy starts as an empty grid;
    // deviceIds is its authorized-device list.
    if (!args.name?.toString()?.trim()) {
        throw new IllegalArgumentException("name is required (the dashboard's display name), unless sourceId copies an existing dashboard.")
    }
    if (_dashboardTypeArg(args.type) == "legacy") {
        return _createLegacyDashboard(args)
    }
    def q = _buildDashboardConfigQuery(args, _dashboardDeviceCsv(args.deviceIds, true))
    // CREATE-ONLY: the hub's editor sends version=2.0 on create (and drops it on update).
    q.version = "2.0"
    _requireUnprotectedEasyDashboardParent()
    try {
        return _dashboardWriteResult(hubInternalGet("/dashboard/create", q), "create", null)
    } catch (Exception e) {
        mcpLogError("dashboard", "create dashboard failed", e)
        return [success: false, outcomeUnknown: true, error: "Failed to confirm dashboard creation: ${e.message}",
                note: "A dashboard may have been created. Inspect hub_list_dashboards before retrying; retrying blindly may create a duplicate."]
    }
}

private void _requireUnprotectedEasyDashboardParent() {
    def protectedIds = _protectedAppIds()
    if (protectedIds.isEmpty()) return
    Map tree
    try {
        tree = _dashboardAppTree()
    } catch (Exception e) {
        throw new IllegalArgumentException("Cannot verify Easy Dashboard parent protection: ${e.message}. No create request was sent.")
    }
    if (tree.malformed) {
        throw new IllegalArgumentException("Cannot verify Easy Dashboard parent protection: the app inventory is malformed. No create request was sent.")
    }
    tree.rows.each { row ->
        def id = _protectedAppId(row.id)
        boolean easyParent = row.type == "Easy Dashboard Parent"
        // Only protected IDs need a known type; unrelated apps cannot be a protected parent.
        if (((row.hasData || row.id != null) && !id) || (protectedIds.contains(id) && !(row.type instanceof String && row.type.trim())) ||
                (easyParent && !id)) {
            throw new IllegalArgumentException("Cannot verify Easy Dashboard parent protection: an app ID or type is missing. No create request was sent.")
        }
        if (easyParent) _requireUnprotectedAppMutation(id, "create a dashboard under", protectedIds)
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

// Find the legacy "Hubitat® Dashboard" parent's installed-app id. When the built-in parent isn't
// installed, bootstrap it through the hub's Add Built-In App link (GET /installedapp/sysApp/<type
// name>, the same flow the RM-family parent discovery uses). The parent is a self-installing
// singleton (installOnOpen), so the GET can never duplicate it.
private Integer _legacyDashboardParentId() {
    def node = _findLegacyDashboardParent()
    if (node == null) {
        // Literal name, no pre-encoding -- the HTTP layer encodes the path; pre-encoding gets
        // double-encoded and the hub then matches no app.
        def created = null
        try {
            created = hubInternalGetRaw("/installedapp/sysApp/Hubitat® Dashboard")
        } catch (Exception e) {
            mcpLogError("dashboard", "legacy dashboard parent bootstrap (Add Built-In App) failed", e)
        }
        node = _findLegacyDashboardParent()
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

private Map _findLegacyDashboardParent() {
    try {
        def row = _dashboardAppTree().rows.find { r ->
            _isLegacyDashboardParentType(r.type?.toString()) && r.id != null && r.hidden != true
        }
        return row ? [id: row.id, installed: row.installed] : null
    } catch (Exception e) {
        mcpLogError("dashboard", "legacy dashboard parent lookup failed", e)
        return null
    }
}

// Classic-page form POST against a legacy dashboard child. Its mainPage is submitOnChange with no
// commit button, so a bare /installedapp/update/json POST commits each field directly.
private void _legacyDashboardFormPost(String appId, Map fields) {
    def body = [formAction: "update", id: appId, version: "1", appTypeId: "", appTypeName: "",
                currentPage: "mainPage", pageBreadcrumbs: "[]", referrer: "", url: "", _cancellable: "false"]
    body.putAll(fields)
    def resp = hubInternalPostForm("/installedapp/update/json", body)
    if (resp?.status != null && resp.status >= 400) {
        throw new IllegalStateException("classic form POST failed for app ${appId}: status=${resp.status}")
    }
}

private void _legacyDashboardSetLabel(String appId, String name) {
    _legacyDashboardFormPost(appId, [label: name, ("label.type"): "text", ("label.multiple"): "false"])
}

// A capability multi-select needs the type + multiple=true sidecars in the same POST, or the
// setting's multi flag flips and the app page breaks.
private void _legacyDashboardSetDevices(String appId, String deviceCsv) {
    _legacyDashboardFormPost(appId, [("settings[devicesPicked]"): deviceCsv,
                                     ("devicesPicked.type"): "capability.*", ("devicesPicked.multiple"): "true"])
}

// Create a legacy dashboard: createchild under the legacy parent (the shell self-installs and mints
// its OAuth access token), then set the label and (optionally) the authorized devices.
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
    _requireUnprotectedAppMutation(parentId, "create a dashboard under")
    def resp
    try {
        resp = hubInternalGetRaw("/installedapp/createchild/hubitat/Dashboard/parent/${parentId}")
    } catch (Exception e) {
        mcpLogError("dashboard", "legacy dashboard createchild outcome unknown under parent ${parentId}", e)
        return [success: false, outcomeUnknown: true, parentAppId: parentId,
                error: "The legacy dashboard create request did not return successfully: ${e.message}",
                note: "A dashboard may have been created under parent app ${parentId}. Inspect hub_list_dashboards and hub_list_apps for an unnamed Dashboard before retrying; retrying blindly may create a duplicate."]
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
        _legacyDashboardSetLabel(newId, name)
        labelApplied = true
    } catch (Exception e) {
        warnings << "label write failed (${e.message}) -- the dashboard keeps the default 'Dashboard' label; rename via hub_update_dashboard(name=...)."
    }
    if (deviceCsv) {
        try { _legacyDashboardSetDevices(newId, deviceCsv) } catch (Exception e) {
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

// Copy by value (the hub's /dashboard/cloneAsEasy is session-bound and fails from the server): read
// the source, then create a new dashboard from it. The source is never touched. Only name may be
// given with sourceId; everything else comes from the source, and the copy is edited afterwards.
private Map _copyDashboard(Map args) {
    String sourceId = _requireDashboardId(args.sourceId, "sourceId", " to copy")
    def extra = ["type", "deviceIds", "options"].findAll { args.get(it) != null }
    if (extra) {
        throw new IllegalArgumentException("${extra.join(', ')} cannot be combined with sourceId: a copy keeps the source's kind, devices and settings. Copy it, then change the copy with hub_update_dashboard.")
    }
    def read = _readDashboards()
    def src = read.out.success == false ? read.out : _getDashboard(sourceId, read)
    if (src.success == false) {
        return [success: false, sourceId: sourceId,
                error: "Could not read the source dashboard to copy it" + (src.error ? ": ${src.error}" : "."),
                note: "Verify the id with hub_list_dashboards."]
    }
    String name = args.name?.toString()?.trim() ?: "${src.name ?: 'Dashboard'} (copy)".toString()
    if (src.type == "legacy") return _copyLegacyDashboard(sourceId, src, name)
    // A real Easy Dashboard always has >=1 device, so an empty list means the source read came up short.
    if (!src.deviceIds) {
        return [success: false, sourceId: sourceId,
                error: "Could read the source dashboard's id/name but not its device list, so it can't be copied.",
                note: "Retry; if it persists, read it with hub_get_dashboard and recreate it with hub_create_dashboard."]
    }
    def opts = [:]
    (_dashboardTileKeys() + ["theme", "navigationSelection", "dashboardPin", "hsmPin"]).each { k ->
        if (src.containsKey(k)) opts.put(k, src.get(k))
    }
    def created = _createDashboard([name: name, deviceIds: src.deviceIds, options: opts])
    if (created.success == true) {
        def ok = [success: true, id: created.id, sourceId: sourceId, type: "easy", name: name,
                  message: "Copied dashboard ${sourceId} into a new dashboard${created.id ? ' (' + created.id + ')' : ''}."]
        // The app-config fallback read carries no theme, so the copy fell back to the default.
        if (!src.containsKey("theme")) {
            ok.warnings = ["The source dashboard's theme could not be read, so the copy uses the default 'legacy' theme. Set it with hub_update_dashboard."]
        }
        return ok
    }
    def out = [success: false, sourceId: sourceId, error: created.error ?: "creating the copy failed",
               note: created.note ?: "The copy may not have been created; verify with hub_list_dashboards."]
    if (created.outcomeUnknown) out.outcomeUnknown = true
    return out
}

// Legacy copy: create a fresh legacy dashboard with the same authorized devices, then save the
// source's layout into it wholesale.
private Map _copyLegacyDashboard(String sourceId, Map src, String name) {
    if (!(src.layout instanceof Map)) {
        return [success: false, sourceId: sourceId,
                error: "Could read the legacy dashboard but not its layout, so it can't be copied.",
                note: "Retry; if it persists, inspect it with hub_get_dashboard(dashboardId=${sourceId})."]
    }
    def created = _createLegacyDashboard([name: name, deviceIds: (src.deviceIds ?: [])])
    if (created.success != true) {
        return created + [sourceId: sourceId]
    }
    def newId = created.id.toString()
    def status = _dashboardStatus(newId)
    String saveError
    if (status?.legacy == true && status.accessToken) {
        try {
            if (_legacyLayoutPost(newId, status.accessToken, new LinkedHashMap(src.layout as Map)) == null) {
                saveError = "the layout endpoint did not echo a saved layout (outcome unconfirmed)"
            }
        } catch (Exception e) { saveError = e.message }
    } else {
        saveError = "the new dashboard's access token was unreadable"
    }
    if (saveError) {
        return [success: false, id: newId, sourceId: sourceId, type: "legacy",
                error: "Copy ${newId} was created but its layout could not be written: ${saveError}",
                note: "Re-apply with hub_update_dashboard(dashboardId=${newId}, layout=<the layout from hub_get_dashboard(dashboardId=${sourceId})>)."]
    }
    def out = [success: true, id: newId, sourceId: sourceId, type: "legacy",
               message: "Copied legacy dashboard ${sourceId} with its devices and layout into a new dashboard (${newId})."]
    if (created.name) out.name = created.name
    // A copy that landed with caveats (e.g. device authorization didn't take) must say so.
    if (created.warnings) out.warnings = created.warnings
    return out
}

def toolUpdateDashboard(args) {
    return _updateDashboard(args ?: [:])
}

private Map _updateDashboard(Map args) {
    def updateId = _requireDashboardId(args.dashboardId, "dashboardId", " to update")
    _requireUnprotectedAppMutation(updateId, "edit dashboard")
    def status = _dashboardStatus(updateId)
    if (status == null) {
        // Unknown target type: the Easy path's wholesale /dashboard/update MUST NOT be blind-fired
        // at a possibly-legacy id, so fail safe instead of guessing.
        return [success: false, id: updateId,
                error: "Could not determine the dashboard's type (the status read failed), so no update was attempted.",
                note: "Transient hub error (details logged); retry."]
    }
    if (status.present != true) {
        // statusJson answers {} for a removed id: don't route a write at it as if it were Easy.
        return [success: false, id: updateId,
                error: "No dashboard found with id '${updateId}', so no update was attempted.",
                note: "Call hub_list_dashboards to see valid ids."]
    }
    if (status.legacy == true) {
        return _updateLegacyDashboard(updateId, status, args)
    }
    def legacyOnlyArgs = ["layout", "setOptions", "addTiles", "updateTiles", "removeTileIds"].findAll { args.get(it) != null }
    if (legacyOnlyArgs) {
        throw new IllegalArgumentException("${legacyOnlyArgs.join(', ')} appl${legacyOnlyArgs.size() == 1 ? 'ies' : 'y'} only to legacy Hubitat® Dashboards; dashboard ${updateId} is an Easy Dashboard. Easy updates replace the config wholesale: pass name, deviceIds, and options (read it with hub_get_dashboard first).")
    }
    if (!args.name?.toString()?.trim()) {
        throw new IllegalArgumentException("name is required. Update REPLACES the dashboard's config wholesale (there is no server-side read-merge), so pass the full desired config every time.")
    }
    // Update is wholesale + requires the full device set, so a caller can't silently blank devices.
    def deviceCsv = _dashboardDeviceCsv(args.deviceIds, true)
    // A wholesale replace would CLEAR an omitted dashboardPin/hsmPin, so re-send the stored one. A
    // read returns PINs masked while Hide passwords and credentials is on; passing that back keeps it.
    String pinMarker = _appConfigRedactedMarker()
    args = [:] + args
    def opts = (args.options instanceof Map) ? args.options.findAll { k, v -> !(k in ["dashboardPin", "hsmPin"] && v?.toString() == pinMarker) } : [:]
    ["dashboardPin", "hsmPin"].each { k -> if (args[k]?.toString() == pinMarker) args.remove(k) }
    def missingPins = ["dashboardPin", "hsmPin"].findAll { k -> !opts.containsKey(k) && !args.containsKey(k) }
    boolean pinPreserveFailed = false
    if (missingPins) {
        def cur = _dashboardConfigFromApp(updateId)
        if (cur != null) {
            missingPins.each { k -> if (cur.containsKey(k)) opts.put(k, cur.get(k)) }
        } else {
            // Couldn't read the current config: the wholesale update will CLEAR an omitted PIN. Flag it.
            pinPreserveFailed = true
        }
    }
    args.options = opts
    def q = _buildDashboardConfigQuery(args, deviceCsv)
    q.id = updateId
    try {
        def result = _dashboardWriteResult(hubInternalGet("/dashboard/update", q), "update", updateId)
        if (pinPreserveFailed && result.success == true) {
            result.pinPreserveFailed = true
            result.note = "Update applied, but the current config couldn't be read to preserve an omitted PIN -- if this dashboard had a dashboardPin/hsmPin it was cleared; re-set it with another update if needed."
        }
        return result
    } catch (Exception e) {
        mcpLogError("dashboard", "update dashboard failed", e)
        return [success: false, error: "Failed to update dashboard: ${e.message}", note: "The dashboard may be unchanged; verify with hub_get_dashboard."]
    }
}

// Legacy update surface: name (app label), deviceIds (authorized-device list, wholesale), and the
// layout -- either `layout` (wholesale replace) or the granular ops (removeTileIds -> updateTiles ->
// addTiles -> setOptions, applied in that order in ONE save). At least one of those args is required.
private Map _updateLegacyDashboard(String id, Map status, Map args) {
    def granular = ["setOptions", "addTiles", "updateTiles", "removeTileIds"].findAll { args.get(it) != null }
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
    if (hasWholesale && !(args.layout instanceof Map)) {
        throw new IllegalArgumentException("layout must be an object -- the full layout JSON, as returned by hub_get_dashboard.")
    }
    def deviceCsv = hasDevices ? _dashboardDeviceCsv(args.deviceIds, false) : null
    def out = [success: true, id: id, type: "legacy"]
    def applied = []
    def warnings = []
    def failed = { String error, String note -> [success: false, id: id, type: "legacy", applied: applied, error: error, note: note] }
    if (hasName) {
        def newName = args.name.toString().trim()
        try {
            _legacyDashboardSetLabel(id, newName)
        } catch (Exception e) {
            mcpLogError("dashboard", "legacy dashboard rename failed", e)
            return failed("Rename failed: ${e.message}", "Nothing after the rename was attempted; the layout is unchanged.")
        }
        out.name = newName
        applied << "name"
    }
    if (hasDevices) {
        try {
            _legacyDashboardSetDevices(id, deviceCsv)
        } catch (Exception e) {
            mcpLogError("dashboard", "legacy dashboard device authorization failed", e)
            return failed("Device authorization write failed: ${e.message}", "The layout was not touched; retry deviceIds (and any layout ops) once the hub responds.")
        }
        status.deviceIds = deviceCsv ? (deviceCsv.split(",") as List) : []
        out.deviceIds = status.deviceIds
        applied << "deviceIds"
    }
    if (hasWholesale || granular) {
        if (!status.accessToken) {
            return failed("The dashboard's access token could not be read from its app state, so its layout endpoint is unreachable.",
                "Open the dashboard app page once (or reset its token) and retry.")
        }
        Map newLayout
        if (hasWholesale) {
            newLayout = new LinkedHashMap(args.layout as Map)
        } else {
            Map current = null
            try { current = _legacyLayoutFetch(id, status.accessToken) } catch (Exception e) {
                mcpLogError("dashboard", "legacy layout read-before-edit failed", e)
            }
            if (current == null) {
                return failed("Could not read the current layout to apply the edits.", "Retry; if it persists, check hub connectivity." + _lanAccessHint(status))
            }
            newLayout = _applyLegacyLayoutOps(current, args, status, warnings)
        }
        Map saved
        try { saved = _legacyLayoutPost(id, status.accessToken, newLayout) } catch (Exception e) {
            mcpLogError("dashboard", "legacy layout save failed", e)
            return failed("Layout save failed: ${e.message}", "The layout may be unchanged; verify with hub_get_dashboard.")
        }
        if (saved == null) return failed("The layout endpoint did not echo a saved layout; outcome unconfirmed.", "Verify with hub_get_dashboard.")
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
private Map _applyLegacyLayoutOps(Map current, Map args, Map status, List warnings) {
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
            if (spec instanceof Map) _requireKnownTileKeys(spec, "updateTiles")
            if (!(spec instanceof Map) || spec.id == null) {
                throw new IllegalArgumentException("Each updateTiles entry needs an id plus the fields to change (e.g. [id: 3, col: 2, colSpan: 2]).")
            }
            def tid = _requireTileId(spec.id, "updateTiles")
            def tile = tiles.find { _tileIdOf(it) == tid }
            if (tile == null) {
                throw new IllegalArgumentException("updateTiles: no tile with id ${tid}. Existing tile ids: ${tiles.collect { it.id }}.")
            }
            spec.each { k, v -> if (k?.toString() != "id") tile.put(k, v) }
            _warnUnauthorizedTileDevice(tile, status, warnings)
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
            _requireKnownTileKeys(spec, "addTiles")
            def missing = ["template", "col", "row"].findAll { spec.get(it) == null }
            if (missing) {
                throw new IllegalArgumentException("addTiles: each tile needs template, col, and row (missing ${missing.join(', ')} in ${spec}).")
            }
            // Skip an add identical to an existing tile so a retried call can't stack duplicates
            // (keeps hub_update_dashboard honest about its idempotent annotation).
            def dup = tiles.find { t ->
                ["template", "device", "col", "row", "templateExtra"].every { k -> t.get(k)?.toString() == spec.get(k)?.toString() } &&
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
            _warnUnauthorizedTileDevice(tile, status, warnings)
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
            layout.put(key, v)
        }
    }
    layout.tiles = tiles
    return layout
}

// A tile key outside this set would be stored verbatim and silently ignored by the dashboard, so a
// typo is refused before anything is saved. An addTiles id is tolerated because it is reassigned.
private void _requireKnownTileKeys(Map spec, String op) {
    def allowed = ["id", "template", "device", "col", "row", "colSpan", "rowSpan", "templateExtra"]
    def unknown = spec.keySet().collect { it?.toString() }.findAll { !(it in allowed) }
    if (unknown) {
        throw new IllegalArgumentException("${op}: unknown tile key${unknown.size() == 1 ? '' : 's'} ${unknown.join(', ')}. Allowed keys: ${allowed.join(', ')}.")
    }
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
private void _warnUnauthorizedTileDevice(Map tile, Map status, List warnings) {
    def dev = tile.device?.toString()
    if (dev && dev ==~ /\d+/ && status.deviceIds != null && !status.deviceIds.contains(dev)) {
        warnings << "Tile ${tile.id}: device ${dev} is not in this dashboard's authorized devices, so the tile will show no data. Authorize it via hub_update_dashboard(deviceIds=[...full list...])."
    }
}

def toolDeleteDashboard(args) {
    return _deleteDashboard(args ?: [:])
}

private Map _deleteDashboard(Map args) {
    requireDestructiveConfirm(args.confirm)
    def dashId = _requireDashboardId(args.dashboardId, "dashboardId", " to delete")
    def absent = [success: true, id: dashId, alreadyAbsent: true, message: "Dashboard ${dashId} is already absent; no delete was needed."]
    if (!_requireUnprotectedAppDeletion(dashId.toInteger(), true)) return absent
    def status = _dashboardStatus(dashId)
    if (status == null) {
        // Unknown target type: the Easy /dashboard/delete is a no-op for a legacy dashboard, so a
        // mis-route could end in a false verdict -- fail safe.
        return [success: false, id: dashId,
                error: "Could not determine the dashboard's type (the status read failed), so no delete was attempted.",
                note: "Transient hub error (details logged); retry."]
    }
    if (!status.present) return absent
    def out = [id: dashId]
    String hubError = null
    if (status.legacy) {
        out.type = "legacy"
        // /dashboard/delete does NOT remove a legacy child (success:false and the app stays), so use
        // the classic force-delete. It can answer 500 for a delete that committed: confirm by effect.
        try { _rmForceDeleteApp(dashId as Integer) } catch (Exception e) {
            mcpLog("debug", "dashboard", "legacy dashboard forcedelete answered '${e.message}' -- verifying by effect")
        }
    } else {
        def parsed = null
        try {
            def raw = hubInternalGet("/dashboard/delete", [id: dashId])
            parsed = raw ? new groovy.json.JsonSlurper().parseText(raw) : null
        } catch (Exception e) {
            // The request may still have committed (e.g. the response body was lost): verify by effect.
            mcpLogError("dashboard", "delete dashboard request failed; verifying by effect", e)
            hubError = "the delete request failed (${e.message})"
        }
        // /dashboard/delete answers success:false even on a real delete, so only statusJson below can
        // prove a removal; a success:true answer is accepted as-is.
        if (parsed instanceof Map && parsed.success == true) return out + [success: true, message: "Dashboard ${dashId} deleted."]
        if (parsed instanceof Map) hubError = parsed.message ?: parsed.error
    }
    // statusJson answers {} for a removed app: one small read confirms the delete by effect.
    def present = _dashboardStatus(dashId)?.present
    if (present == false) {
        return out + [success: true, message: "${status.legacy ? 'Legacy dashboard' : 'Dashboard'} ${dashId} deleted."]
    }
    if (present == null) {
        return out + [success: false, outcomeUnknown: true,
                      error: "Delete request was sent, but removal could not be confirmed (the verification read failed)" + (hubError ? "; ${hubError}." : "."),
                      note: "The dashboard may or may not have been deleted. Verify with hub_list_dashboards before retrying."]
    }
    return out + [success: false, error: hubError ?: "the dashboard is still present after the delete call",
                  note: status.legacy
                      ? "Nothing was deleted. hub_delete_native_app(appId=${dashId}, force=true) snapshots first and uses the same delete path."
                      : "Nothing was deleted. Verify the id with hub_list_dashboards."]
}

// ---- domain helpers ----

private List _dashboardTileKeys() {
    return ["showModeTile", "showClockTile", "showCalendarTile", "showHSMTile", "showEdit", "showNavigation", "showTutorial"]
}

// Normalize one /dashboard/all record (or a create/update echo) into the public shape. Fields are
// copied by name, so the record's accessToken never reaches a result.
private Map _summarizeDashboard(Map raw) {
    def out = [
        id: (raw.installedAppId ?: raw.id)?.toString(),
        name: raw.name,
        // version 1.x = legacy Hubitat® Dashboard; 2.x = Easy. A create/update echo carries no
        // version, and only the Easy endpoints produce those.
        type: raw.version?.toString()?.startsWith("1") ? "legacy" : "easy"
    ]
    // Config fields (when present) pass through so a read round-trips back through hub_update_dashboard.
    (_dashboardTileKeys() + ["theme"]).each { k ->
        if (raw.containsKey(k)) out.put(k, raw.get(k))
    }
    if (raw.containsKey("navigationSelection")) out.navigationSelection = _dashboardNavSelectionCsv(raw.navigationSelection)
    // /dashboard/all gives deviceIds as a JSON array of numbers; normalize to a list of id strings (a
    // JSON-array string such as "[8,1,9]" is accepted too).
    if (raw.containsKey("deviceIds")) out.deviceIds = _normalizeDeviceIdList(raw.deviceIds)
    if (_dashboardRealValue(raw.dashboardPin)) out.dashboardPin = raw.dashboardPin
    if (_dashboardRealValue(raw.hsmPin)) out.hsmPin = raw.hsmPin
    return out
}

// The hub renders unset optional fields as "", "null" or "None" depending on the endpoint; all mean absent.
private boolean _dashboardRealValue(v) {
    def s = v?.toString()
    return s && s != "null" && s != "None"
}

// Serialize deviceIds to CSV for the hub. Coerce shape first (a String "12" is not a char
// collection): Collection as-is, String split on commas, else wrap; validate each token is numeric.
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

// A dashboard's PINs are app settings: Hide passwords and credentials masks them in every dashboard tool
// result, at the tool boundary only, so internal reads keep the real PINs.
private _dashboardPublic(result) {
    if (!_redactsCredentials()) return result
    if (result instanceof Map) {
        return result.collectEntries { k, v ->
            [(k): (k in ["dashboardPin", "hsmPin"] && v != null && v.toString()) ? _appConfigRedactedMarker() : _dashboardPublic(v)]
        }
    }
    if (result instanceof List) return result.collect { _dashboardPublic(it) }
    return result
}

// Build the /dashboard/create|update query. Booleans serialize as "true"/"false"; theme empty == legacy.
// Tile/nav/pin values are read from the `options` object first, else the same-named top-level arg.
// The query carries no id: /dashboard/create takes none, and /dashboard/update's id is set separately.
private Map _buildDashboardConfigQuery(Map args, String deviceCsv) {
    def opts = (args.options instanceof Map) ? args.options : [:]
    def opt = { String k -> opts.containsKey(k) ? opts.get(k) : args.get(k) }
    def q = [name: args.name.toString(), deviceIds: deviceCsv]
    _dashboardTileKeys().each { k ->
        def v = opt(k)
        q.put(k, (v == true || v?.toString()?.toLowerCase() == "true") ? "true" : "false")
    }
    ["dashboardPin", "hsmPin"].each { k ->
        def v = opt(k)
        if (v?.toString() == _appConfigRedactedMarker()) {
            throw new IllegalArgumentException("${k} is the redaction marker, not a PIN. Pass the real PIN, or omit it on hub_update_dashboard to keep the stored one.")
        }
        q.put(k, v != null ? v.toString() : "")
    }
    q.navigationSelection = _dashboardNavSelectionCsv(opt("navigationSelection"))
    q.theme = _dashboardTheme(opt("theme"))
    return q
}

// navigationSelection: a CSV of dashboard navigation indices. The hub PERSISTS + returns it as a
// JSON-array STRING ("[1,2]", "[]"), but /dashboard/create+update parse it as a bare CSV of indices,
// so feeding a read value back verbatim misparses (the hub reads "[1]" as 0) and corrupts nav on a
// read->update round-trip. Normalize every form (array, "[1,2]", "1,2") to "1,2".
private String _dashboardNavSelectionCsv(navigationSelection) {
    if (!_dashboardRealValue(navigationSelection)) return ""
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
                note: "The ${op} may or may not have applied; verify with hub_list_dashboards."]
    }
    return [success: false, error: "Unexpected (non-JSON) response from /dashboard/${op}.",
            note: "The ${op} may or may not have applied; verify with hub_list_dashboards."]
}

// Tool definitions live with the impl; gateway membership + dispatch stay in main.
def _getAllToolDefinitions_partDashboards() {
    def idParam = [type: "string", description: "Dashboard id, from hub_list_dashboards."]
    return [
        [
            name: "hub_list_dashboards",
            description: "List the hub's dashboards -- Easy Dashboards and legacy Hubitat® Dashboards -- with id, name and type ('easy' or 'legacy').[[FLAT_TRIM]] Easy entries also carry their tile/theme config. Read one in full with hub_get_dashboard. See hub_get_tool_guide(section='dashboards').[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [:]
            ]
        ],
        [
            name: "hub_get_dashboard",
            description: "Get one dashboard in full by id: an Easy Dashboard's config (tiles, navigation, theme, devices, PINs), or a legacy Hubitat® Dashboard's authorized deviceIds plus its tile layout.[[FLAT_TRIM]] This is the shape hub_update_dashboard takes back. A read that partly fails returns partial:true with a note. See hub_get_tool_guide(section='dashboards').[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    dashboardId: idParam
                ],
                required: ["dashboardId"]
            ]
        ],
        [
            name: "hub_create_dashboard",
            description: "Create a dashboard, or copy one with sourceId. type='easy' (default) builds an Easy Dashboard from devices + options; type='legacy' creates an empty legacy Hubitat® Dashboard.[[FLAT_TRIM]] A copy duplicates an Easy config, or a legacy dashboard's devices and layout; the source is never changed. A legacy dashboard starts empty -- add tiles with hub_update_dashboard. See hub_get_tool_guide(section='dashboards').[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    name: [type: "string", description: "Display name. Required unless sourceId is given (a copy defaults to '<source name> (copy)')."],
                    type: [type: "string", enum: ["easy", "legacy"], description: "Dashboard kind (default easy)."],
                    deviceIds: [type: "array", description: "Device ids: >=1 for Easy; a legacy dashboard's authorized devices (optional).", items: [type: "string"]],
                    options: [type: "object", description: "Easy-only config; see hub_get_tool_guide(section='dashboards')."],
                    sourceId: [type: "string", description: "Dashboard id to copy. Only name may accompany it."]
                ]
            ]
        ],
        [
            name: "hub_update_dashboard",
            description: "Update a dashboard by id. Easy Dashboard: wholesale config replace -- read it with hub_get_dashboard and pass the FULL config back (name + deviceIds required; omitted fields revert to default). Legacy Hubitat® Dashboard: edit its name, deviceIds (authorized devices) and/or layout.[[FLAT_TRIM]] Legacy layout edits are EITHER `layout` (wholesale) OR the granular ops removeTileIds / updateTiles / addTiles / setOptions, applied in that order in one save; an already-gone removal and an identical add are skipped with a warning. Args for the other kind are rejected. See hub_get_tool_guide(section='dashboards').[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    dashboardId: idParam,
                    name: [type: "string", description: "Display name (Easy) or app label (legacy)."],
                    deviceIds: [type: "array", description: "Full device id set. Required (>=1) for Easy; replaces a legacy dashboard's authorized devices.", items: [type: "string"]],
                    options: [type: "object", description: "Easy-only. Same keys as hub_create_dashboard.options."],
                    layout: [type: "object", description: "Legacy-only. Wholesale layout replace."],
                    setOptions: [type: "object", description: "Legacy-only. Merge top-level layout fields (cols, rows, bgColor, iconSize, fontSize, gridGap)."],
                    addTiles: [type: "array", description: "Legacy-only. Tiles to add; each needs template, col, row.", items: [type: "object"]],
                    updateTiles: [type: "array", description: "Legacy-only. Tile edits, each {id, ...fields-to-change}.", items: [type: "object"]],
                    removeTileIds: [type: "array", description: "Legacy-only. Tile ids to remove.", items: [type: "integer"]]
                ],
                required: ["dashboardId"]
            ]
        ],
        [
            name: "hub_delete_dashboard",
            description: "⚠️ Permanently delete a dashboard by id (irreversible), Easy or legacy Hubitat®. Tell the user first.[[FLAT_TRIM]] Removal is confirmed by effect. See hub_get_tool_guide(section='dashboards').[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    dashboardId: idParam,
                    confirm: [type: "boolean", description: "Must be true (requires a recent backup + user approval)."]
                ],
                required: ["dashboardId", "confirm"]
            ]
        ]
    ]
}

def _readOnlyToolNames_partDashboards() {
    // Read-only tools, contributed to getReadOnlyToolNames(). Absent => write+destructive by default.
    return ["hub_list_dashboards", "hub_get_dashboard"]
}

def _idempotentWriteToolNames_partDashboards() {
    // Retry-safe writes: update = wholesale (same args -> same state); delete = no-op once gone.
    // create (including a copy) makes ANOTHER dashboard, so it is NOT idempotent.
    return ["hub_update_dashboard", "hub_delete_dashboard"]
}

def _toolDisplayMeta_partDashboards() {
    // Title + Advanced-menu summary per tool, merged into getToolDisplayMeta().
    return [
        hub_list_dashboards: [title: "List Dashboards", summary: "List Easy and legacy Hubitat® Dashboards with ids, names, and type."],
        hub_get_dashboard: [title: "Get Dashboard", summary: "Get one dashboard's full config by id (Easy config or legacy layout)."],
        hub_create_dashboard: [title: "Create Dashboard", summary: "Create an Easy or empty legacy Hubitat® Dashboard, or copy an existing one."],
        hub_update_dashboard: [title: "Update Dashboard", summary: "Update a dashboard: Easy wholesale, or legacy name/devices/layout edits."],
        hub_delete_dashboard: [title: "Delete Dashboard", summary: "Permanently delete an Easy or legacy Hubitat® Dashboard by id."]
    ]
}
