library(name: "McpCodeManagementLib", namespace: "mcp", author: "kingpanther13", description: "App/driver/library code management tool implementations (list/source/install/update/delete, app config + pages, device dependents) for the MCP Rule Server; #include'd by the main app. Gateway entries and dispatch cases stay in the app; tool definitions, implementations, domain helpers, and per-tool metadata live here.")

def toolListHubApps(args) {

    def cursor = args?.cursor
    def result = [:]
    try {
        // /hub2/userAppTypes is the Apps Code registry: every user code DEFINITION with its
        // class id + usedBy, including child-app templates that have zero running instances.
        // Its id semantics and completeness are relied on elsewhere (self-admin class-id
        // resolution, HPM orphan detection), so it stays the primary source here. It omits
        // `menu` and the built-in types entirely, so both are layered on from /hub2/appsList
        // (the payload the Apps admin page itself loads) via _appTypeEnrichment().
        def responseText = hubInternalGet("/hub2/userAppTypes")
        if (responseText) {
            def parsed = null
            boolean notJson = false
            // Narrow the catch to the parse itself -- a failure in enrichment/projection below is a
            // real bug and must not masquerade as "Response was not JSON".
            try {
                parsed = new groovy.json.JsonSlurper().parseText(responseText)
            } catch (Exception parseErr) {
                // Response was not JSON - return what we can. apps=[] keeps the cursor
                // block + downstream shape consistent so a paginating caller doesn't
                // silently lose the pagination contract on a firmware shape drift.
                result.apps = []
                result.rawResponse = responseText?.take(2000)
                result.source = "hub_api_raw"
                result.note = "Response was not JSON. This endpoint may return HTML on your firmware version."
                notJson = true
            }
            if (!notJson) {
                if (parsed instanceof List) {
                    def enrich = _appTypeEnrichment()
                    def types = []
                    // Community (user-installed) code definitions: system/isBuiltIn=false,
                    // menu looked up from the appsList catalog (null when undeclared/unlisted).
                    parsed.each { t -> if (t instanceof Map) types << _projectAppType(t, false, enrich) }
                    // Built-in types never appear in the code registry; append them so a caller
                    // can answer "community vs built-in" over ALL app types in one call.
                    enrich.builtIn.each { t -> if (t instanceof Map) types << _projectAppType(t, true, enrich) }
                    result.apps = types
                    result.count = types.size()
                    result.source = "hub_api"
                    if (enrich.note) result.note = enrich.note
                } else {
                    // Unexpected non-array JSON shape: pass it through rather than guess.
                    result.apps = parsed
                    result.count = 0
                    result.source = "hub_api"
                    result.note = "/hub2/userAppTypes returned an unexpected (non-array) shape; passing it through unprojected. Hubitat firmware may have changed the endpoint format."
                }
            }
        } else {
            result.apps = []
            result.note = "Empty response from hub API"
        }
    } catch (Exception e) {
        mcpLog("warn", "hub-admin", "hub_list_apps API call failed: ${e.message}")
        // Fallback: return MCP child apps as the only apps we can enumerate
        def childApps = getChildApps()
        result.apps = childApps?.collect { ca ->
            [id: ca.id.toString(), name: ca.getSetting("ruleName") ?: ca.label ?: "Unknown", type: "MCP Rule"]
        } ?: []
        result.count = result.apps.size()
        result.source = "mcp_only"
        result.note = "Hub internal API unavailable (${e.message}). Showing only MCP Rule Server apps. This may require Hub Security credentials or a firmware update."
    }

    if (args?.nameFilter && result.apps instanceof List) {
        result.apps = _nameFiltered(result.apps, args.nameFilter)
        result.count = result.apps.size()
    }
    if (cursor != null && result.apps instanceof List) {
        def paged = _paginateList(result.apps, cursor, 50, "hub_list_apps")
        result.total = result.apps.size()
        result.apps = paged.page
        result.count = paged.page.size()
        if (paged.nextCursor != null) result.nextCursor = paged.nextCursor
    }

    mcpLog("info", "hub-admin", "Listed hub apps (source: ${result.source})")
    return result
}

def _appTypeEnrichment() {
    // Read /hub2/appsList once to supply what the Apps Code registry cannot: the `menu` tab
    // (Apps/Automations/Integrations) each app type declares and the catalog of built-in
    // (systemAppTypes) types. Returns menu lookups keyed by both class id and namespace+name
    // (the appsList catalog and the code registry share the app-type class id; ns+name is a
    // fallback), the built-in type list, and a `note` when the enrichment could not be read
    // (best-effort: a failure just leaves community menus null and omits built-in types).
    def out = [menuById: [:], menuByNsName: [:], builtIn: [], note: null]
    try {
        def txt = hubInternalGet("/hub2/appsList")
        def parsed = txt ? new groovy.json.JsonSlurper().parseText(txt) : null
        if (parsed instanceof Map && (parsed.userAppTypes != null || parsed.systemAppTypes != null)) {
            def catalog = []
            def notes = []
            // Enrich from whichever list is valid. appsList supplies only the `menu` tab and the
            // built-in catalog; community types themselves come from /hub2/userAppTypes, so a missing
            // appsList.userAppTypes only nulls their menu -- it does NOT drop them from the listing.
            if (parsed.userAppTypes instanceof List) catalog += parsed.userAppTypes
            else notes << "community app types are still listed (from the code registry) but without their menu tab"
            if (parsed.systemAppTypes instanceof List) catalog += parsed.systemAppTypes
            else notes << "built-in (system) app types are omitted this call"
            if (notes) out.note = "/hub2/appsList did not return a usable list this call; " + notes.join("; ") + "."
            catalog.each { e ->
                if (e instanceof Map) {
                    if (e.id != null) out.menuById[e.id.toString()] = e.menu
                    if (e.namespace != null && e.name != null) {
                        def ns = e.namespace.toString()
                        if (!out.menuByNsName.containsKey(ns)) out.menuByNsName[ns] = [:]
                        out.menuByNsName[ns][e.name.toString()] = e.menu
                    }
                }
            }
            if (parsed.systemAppTypes instanceof List) out.builtIn = parsed.systemAppTypes.findAll { it instanceof Map }
        } else {
            out.note = "Built-in vs community split and menu tab unavailable this call: /hub2/appsList returned an unexpected shape. Community app types are listed without a menu tab."
        }
    } catch (Exception e) {
        out.note = "Built-in vs community split and menu tab unavailable this call (${e.message}). Community app types are listed without a menu tab."
        mcpLog("warn", "hub-admin", "hub_list_apps could not enrich app types from /hub2/appsList: ${e.message}")
    }
    return out
}

def _projectAppType(Map t, boolean builtIn, Map enrich) {
    // Project one app-type entry: preserve every native field the hub returned, add the
    // built-in-vs-community flags (system / isBuiltIn), and guarantee a `menu` key. `menu` is
    // the Apps/Automations/Integrations tab an app declares in its definition() block: taken
    // inline when the entry already carries it (built-in appsList entries do), else resolved
    // from the enrichment maps by class id then namespace+name. A type that declares none — or
    // that the appsList catalog does not list (e.g. a child-app template) — is surfaced as null
    // (no invented default).
    def out = [:]
    out.putAll(t)
    out.system = builtIn
    out.isBuiltIn = builtIn
    if (!out.containsKey("menu")) {
        def m = null
        if (enrich != null) {
            m = enrich.menuById?.get(t?.id?.toString())
            if (m == null && t?.namespace != null && t?.name != null) {
                m = enrich.menuByNsName?.get(t.namespace.toString())?.get(t.name.toString())
            }
        }
        out.menu = m
    }
    return out
}

private List _nameFiltered(List rows, filter) {
    // Rows whose name contains the filter, ignoring case and the hub's HTML (a list's own names).
    String f = filter?.toString()?.trim()?.toLowerCase()
    if (!f) return rows
    return rows.findAll { it instanceof Map && (stripAppConfigHtml(it.name) ?: "").toLowerCase().contains(f) }
}

def toolListHubDrivers(args) {
    def cursor = args?.cursor
    def include = (args?.include ?: "user").toString().toLowerCase()
    if (!(include in ["user", "all"])) throw new IllegalArgumentException("include must be 'user' or 'all' (got '${args.include}').")
    def result = [:]
    if (include == "all") {
        // Full instantiable driver-type catalog -- built-in "system" + "virtual" + user
        // drivers (the superset the device add/edit picker uses), vs the default "user"
        // path which lists only user-installed driver types. Each id is the deviceTypeId
        // consumed by hub_create_device; hub_manage_virtual_device(customDriver) instead
        // takes the entry's namespace + name (both are in the include='all' projection).
        try {
            def responseText = hubInternalGet("/device/drivers")
            def parsed = responseText ? new groovy.json.JsonSlurper().parseText(responseText) : null
            if (parsed?.drivers instanceof List) {
                def projected = []
                parsed.drivers.each { d ->
                    if (d?.type == "dep" || d?.category == "Hidden") return
                    def nm = d?.name?.toString() ?: ""
                    def bucket = (d?.type == "usr") ? "user" : (nm.startsWith("Virtual") ? "virtual" : "system")
                    projected << [id: d?.id?.toString(), name: nm, namespace: d?.namespace, bucket: bucket]
                }
                result.drivers = projected
                result.count = projected.size()
                result.source = "hub_api"
            } else {
                result.drivers = []
                result.count = 0
                result.rawResponse = responseText?.take(2000)
                result.source = "hub_api_raw"
                result.note = "Response was not the expected {drivers:[...]} shape; /device/drivers may differ on your firmware version."
            }
        } catch (Exception e) {
            mcpLog("warn", "hub-admin", "hub_list_drivers include=all API call failed: ${e.message}")
            result.drivers = []
            result.count = 0
            result.source = "unavailable"
            result.note = "Hub internal API unavailable (${e.message}). This may require Hub Security credentials or a firmware update."
        }
        result.include = "all"
    } else {
        try {
            def responseText = hubInternalGet("/hub2/userDeviceTypes")
            if (responseText) {
                try {
                    def parsed = new groovy.json.JsonSlurper().parseText(responseText)
                    result.drivers = parsed
                    result.count = parsed instanceof List ? parsed.size() : 0
                    result.source = "hub_api"
                } catch (Exception parseErr) {
                    // Response was not JSON - return what we can. drivers=[] keeps the
                    // cursor block + downstream shape consistent.
                    result.drivers = []
                    result.rawResponse = responseText?.take(2000)
                    result.source = "hub_api_raw"
                    result.note = "Response was not JSON. This endpoint may return HTML on your firmware version."
                }
            } else {
                result.drivers = []
                result.note = "Empty response from hub API"
            }
        } catch (Exception e) {
            mcpLog("warn", "hub-admin", "hub_list_drivers API call failed: ${e.message}")
            result.drivers = []
            result.count = 0
            result.source = "unavailable"
            result.note = "Hub internal API unavailable (${e.message}). This may require Hub Security credentials or a firmware update."
        }
        result.include = "user"
    }

    if (args?.nameFilter && result.drivers instanceof List) {
        result.drivers = _nameFiltered(result.drivers, args.nameFilter)
        result.count = result.drivers.size()
    }
    if (cursor != null && result.drivers instanceof List) {
        def paged = _paginateList(result.drivers, cursor, 50, "hub_list_drivers")
        result.total = result.drivers.size()
        result.drivers = paged.page
        result.count = paged.page.size()
        if (paged.nextCursor != null) result.nextCursor = paged.nextCursor
    }

    mcpLog("info", "hub-admin", "Listed hub drivers (source: ${result.source})")
    return result
}

def toolListLibraries(args) {

    def cursor = args?.cursor
    def result = [:]
    try {
        def responseText = hubInternalGet("/hub2/userLibraries")
        if (responseText) {
            try {
                def parsed = new groovy.json.JsonSlurper().parseText(responseText)
                if (parsed instanceof List) {
                    // Project to summaries -- omit each library's `source` (full Groovy) to keep
                    // the list lean; read source via hub_get_source(type='library', id=N).
                    result.libraries = parsed.collect { lib ->
                        [id: lib.id?.toString(), name: lib.name, namespace: lib.namespace, version: lib.version]
                    }
                    result.count = result.libraries.size()
                    result.source = "hub_api"
                    // A library with no id can't be chained into hub_get_source -- surface it in a
                    // note instead of silently shipping an unusable row under the hub_api all-clear.
                    def noId = result.libraries.count { it.id == null }
                    if (noId > 0) {
                        result.note = "${noId} librar${noId == 1 ? 'y' : 'ies'} returned by the hub had no id and cannot be read via hub_get_source (possible firmware shape change)."
                    }
                } else {
                    result.libraries = []
                    result.count = 0
                    result.rawResponse = responseText?.take(2000)
                    result.source = "hub_api_raw"
                    result.note = "Response was not a JSON array. This endpoint may return a different shape on your firmware version."
                }
            } catch (Exception parseErr) {
                result.libraries = []
                result.count = 0
                result.rawResponse = responseText?.take(2000)
                result.source = "hub_api_raw"
                result.note = "Response was not JSON. This endpoint may return HTML on your firmware version."
            }
        } else {
            result.libraries = []
            result.count = 0
            result.source = "unavailable"
            result.note = "Empty response from hub API"
        }
    } catch (Exception e) {
        mcpLog("warn", "hub-admin", "hub_list_libraries API call failed: ${e.message}")
        result.libraries = []
        result.count = 0
        result.source = "unavailable"
        result.note = "Hub internal API unavailable (${e.message}). This may require Hub Security credentials or a firmware update."
    }

    if (cursor != null && result.libraries instanceof List) {
        def paged = _paginateList(result.libraries, cursor, 50, "hub_list_libraries")
        result.total = result.libraries.size()
        result.libraries = paged.page
        result.count = paged.page.size()
        if (paged.nextCursor != null) result.nextCursor = paged.nextCursor
    }

    mcpLog("info", "hub-admin", "Listed hub libraries (source: ${result.source})")
    return result
}

def toolGetItemSource(String type, String idParam, args) {
    def itemId = args[idParam]
    if (!itemId) throw new IllegalArgumentException("${idParam} is required")

    def maxChunkSize = 64000
    def requestedOffset = args.offset ? args.offset as int : 0
    def requestedLength = args.length ? Math.min(args.length as int, maxChunkSize) : defaultReadChunkChars()

    def ajaxPath = (type == "app") ? "/app/ajax/code" : "/driver/ajax/code"

    try {
        def responseText = hubInternalGet(ajaxPath, [id: itemId])
        if (!responseText) return [success: false, error: "Empty response from hub"]

        def parsed = new groovy.json.JsonSlurper().parseText(responseText)
        if (parsed.status == "error") {
            return [success: false, error: parsed.errorMessage ?: "Failed to get ${type} source"]
        }

        def fullSource = parsed.source ?: ""
        def totalLength = fullSource.length()

        // For large sources, save full copy to File Manager so update can use sourceFile
        def savedToFile = null
        if (totalLength > maxChunkSize) {
            def sourceFileName = "mcp-source-${type}-${itemId}.groovy"
            try {
                uploadHubFile(sourceFileName, fullSource.getBytes("UTF-8"))
                savedToFile = sourceFileName
                mcpLog("info", "hub-admin", "Saved full ${type} ID ${itemId} source to File Manager: ${sourceFileName} (${totalLength} chars)")
            } catch (Exception saveErr) {
                mcpLog("warn", "hub-admin", "Could not save ${type} source to File Manager: ${saveErr.message}")
            }
        }

        // Extract the requested chunk
        def endIndex = Math.min(requestedOffset + requestedLength, totalLength)
        def chunk = (requestedOffset < totalLength) ? fullSource.substring(requestedOffset, endIndex) : ""
        def hasMore = endIndex < totalLength

        mcpLog("info", "hub-admin", "Retrieved ${type} ID ${itemId} source: ${totalLength} chars total, returning offset ${requestedOffset}..${endIndex}${hasMore ? ' (more available)' : ''}")

        def result = [
            success: true,
            (idParam): itemId,
            source: chunk,
            version: parsed.version,
            status: parsed.status,
            totalLength: totalLength,
            offset: requestedOffset,
            chunkLength: chunk.length(),
            hasMore: hasMore
        ]
        if (hasMore) {
            result.nextOffset = endIndex
            result.remainingChars = totalLength - endIndex
            result.hint = "Call again with offset: ${endIndex} to get the next chunk."
        }
        if (savedToFile) {
            result.sourceFile = savedToFile
            result.sourceFileHint = "Full source saved to File Manager. Use hub_update_${type} with sourceFile: '${savedToFile}' to update without cloud size limits."
        }
        return result
    } catch (Exception e) {
        mcpLogError("hub-admin", "Failed to get ${type} source", e)
        return [success: false, error: "Failed to get ${type} source: ${e.message}"]
    }
}

// NOT private: list filters and response builders call it from inside closures, and a closure
// resolves owner calls through the metaClass, which does not expose private methods.
String stripAppConfigHtml(value) {
    if (value == null) return null
    def s = value.toString()
    // Strip HTML tags, then any leftover CSS-rule / inline-script bodies that
    // Hubitat embeds via <style>/<script>: the tags strip above but the
    // "selector{...}" / "fn(){...}" bodies remain mashed into the text (e.g. the
    // Local Variables `lvTable` page). Only blocks containing ; or : inside the
    // braces are removed, so prose like "{x}" is preserved. A `<` opens a tag only when a
    // tag name, `/` or `!` follows: RM renders its less-than comparators as a raw `<`
    // ("is < 100<span ...>"), which a bare `<[^>]+>` would swallow up to the next tag.
    if (s.contains("<")) {
        s = s.replaceAll(/<\/?[A-Za-z!][^>]*>/, "").replaceAll(/[^{}]*\{[^{}]*[;:][^{}]*\}/, "")
    }
    // Decode the common HTML entities Hubitat escapes user-typed names with: a
    // rule the user named "Heat On <67" is stored (and listed) as "Heat On &lt;67".
    // Decode &amp; LAST so a double-encoded entity doesn't collapse (e.g. "&amp;lt;"
    // must stay "&lt;", not become "<").
    if (s.contains("&")) {
        s = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", '"')
             .replace("&#39;", "'").replace("&apos;", "'").replace("&nbsp;", " ")
             .replace("&amp;", "&")
    }
    return s.trim()
}

private stripOptionsHtml(options) {
    if (options instanceof List) {
        def out = []
        for (entry in options) {
            if (entry instanceof Map) {
                def cleaned = [:]
                entry.each { k, v -> cleaned.put(k, (v instanceof String) ? stripAppConfigHtml(v) : v) }
                out << cleaned
            } else {
                out << entry
            }
        }
        return out
    }
    if (options instanceof Map) {
        def cleaned = [:]
        options.each { k, v -> cleaned.put(k, (v instanceof String) ? stripAppConfigHtml(v) : v) }
        return cleaned
    }
    return options
}

// The Rule Machine parent app is not a rule; older hubs still run Rule-4.x children.
private boolean _rmIsRuleAppType(String name) {
    return name != null && name ==~ /(Button )?Rule-\d.*/
}

// A 404/410 from a configure or summary read means the app is gone or is an install shell whose
// page cannot render; any other failure returns null. HttpResponseException is duck-typed off the
// exception because it fails to load on the test classpath.
private Map _appConfigNotFound(Exception e, String path, String appIdStr) {
    Integer st = null
    try { st = e.response?.status as Integer } catch (Exception ignored) { st = null }
    if (st != 404 && st != 410) return null
    mcpLog("warn", "hub-admin", "hub_get_app_config app ${appIdStr} not found (HTTP ${st} from ${path})")
    return [success: false, error: "App ${appIdStr} not found (HTTP ${st} from ${path}).", note: _appConfigNote("Check the id with hub_get_app_config(summary=true) or hub_list_apps(scope='instances')"), appId: appIdStr as Integer, fingerprint: 'app not found (404)', status: st]
}

private String _appConfigNote(String step) {
    return "${step}; see hub_get_tool_guide(section='builtin_app_tools_apps').".toString()
}

def toolGetAppConfig(args) {

    if (args?.appId == null || args.appId.toString().trim() == "") {
        throw new IllegalArgumentException("appId is required")
    }
    def appIdStr = args.appId.toString().trim()
    if (!appIdStr.isInteger()) {
        throw new IllegalArgumentException("appId must be numeric: ${appIdStr}")
    }

    def pageName = args?.pageName?.toString()?.trim()
    if (pageName && !pageName.matches(/[A-Za-z0-9_]+/)) {
        throw new IllegalArgumentException("pageName must be alphanumeric/underscore only: ${pageName}")
    }

    boolean includeSettings = args?.includeSettings == true

    // The thin per-app record renders no page, so it stays cheap on apps with expensive pages.
    if (args?.summary == true) {
        def summaryPath = "/installedapp/json/${appIdStr}"
        mcpLog("info", "hub-admin", "hub_get_app_config appId=${appIdStr} summary mode")
        def summaryText
        try {
            summaryText = hubInternalGet(summaryPath, null, 30)
        } catch (Exception e) {
            Map gone = _appConfigNotFound(e, summaryPath, appIdStr)
            if (gone) return gone
            mcpLogError("hub-admin", "hub_get_app_config summary fetch failed", e)
            return [success: false, error: "Failed to fetch app summary [${e.class.simpleName}]: ${e.message}", note: _appConfigNote('Retry once; if it fails again, check that the hub answers with hub_get_info'), appId: appIdStr as Integer]
        }
        if (!summaryText) {
            return [success: false, error: "Empty response from ${summaryPath}.", note: _appConfigNote('Retry once; if it fails again, read the app without summary=true'), appId: appIdStr as Integer]
        }
        def summaryParsed
        try {
            summaryParsed = new groovy.json.JsonSlurper().parseText(summaryText)
        } catch (Exception e) {
            mcpLogError("hub-admin", "hub_get_app_config summary JSON parse failed", e)
            return [success: false, error: "Failed to parse app summary JSON: ${e.message}", note: _appConfigNote('Retry once; if it fails again, read the app without summary=true'), appId: appIdStr as Integer]
        }
        if (!(summaryParsed instanceof Map)) {
            return [success: false, error: "Unexpected response shape: the app summary is not a JSON object.", note: _appConfigNote('Retry once; if it fails again, read the app without summary=true'), appId: appIdStr as Integer, fingerprint: "top-level not a Map"]
        }
        def summaryResult = [:]
        summaryResult.putAll(summaryParsed)
        // The record names the app's type but not its namespace; the installed app itself carries both.
        try { summaryResult.namespace = getAppByAppId(appIdStr as Long)?.appType?.namespace?.toString() }
        catch (Exception lookup) { mcpLog("warn", "hub-admin", "hub_get_app_config app ${appIdStr}: type namespace unreadable: ${lookup.message}") }
        summaryResult.success = true
        summaryResult.endpoint = summaryPath
        return summaryResult
    }

    def path = "/installedapp/configure/json/${appIdStr}"
    if (pageName) path += "/${pageName}"

    mcpLog("info", "hub-admin", "hub_get_app_config appId=${appIdStr} page=${pageName ?: 'main'} includeSettings=${includeSettings}")

    def responseText
    try {
        responseText = hubInternalGet(path, null, 30)
    } catch (Exception e) {
        Map gone = _appConfigNotFound(e, path, appIdStr)
        if (gone) return gone
        mcpLogError("hub-admin", "hub_get_app_config fetch failed", e)
        return [success: false, error: "Failed to fetch app config [${e.class.simpleName}]: ${e.message}", note: _appConfigNote('Retry once; if it fails again, check that the hub answers with hub_get_info'), appId: appIdStr as Integer]
    }

    if (!responseText) {
        return [success: false, error: "Empty response from ${path}.", note: _appConfigNote('Retry once; if it fails again, check that the app still exists with hub_get_app_config(summary=true)'), appId: appIdStr as Integer]
    }

    def parsed
    try {
        parsed = new groovy.json.JsonSlurper().parseText(responseText)
    } catch (Exception e) {
        mcpLogError("hub-admin", "hub_get_app_config JSON parse failed", e)
        return [success: false, error: "Failed to parse app config JSON: ${e.message}", note: _appConfigNote('Retry once; if it fails again, check that the app still exists with hub_get_app_config(summary=true)'), appId: appIdStr as Integer]
    }

    if (!(parsed instanceof Map)) {
        return [success: false, error: "Unexpected response shape: the page is not a JSON object.", note: _appConfigNote('Retry once; if it fails again, check that the app still exists with hub_get_app_config(summary=true)'), appId: appIdStr as Integer, fingerprint: "top-level not a Map"]
    }
    if (!(parsed.app instanceof Map)) {
        return [success: false, error: "Unexpected response shape: the page names no app.", note: _appConfigNote("Check the id with hub_list_apps(scope='instances')"), appId: appIdStr as Integer, fingerprint: "missing app"]
    }
    if (!(parsed.configPage instanceof Map)) {
        return [success: false, error: "Unexpected response shape: the page has no configPage.", note: _appConfigNote('Retry once; if it fails again, check that the app still exists with hub_get_app_config(summary=true)'), appId: appIdStr as Integer, fingerprint: "missing configPage"]
    }
    if (!(parsed.configPage.sections instanceof List)) {
        return [success: false, error: "This page has no sections to read.", note: _appConfigNote("Read the app's default page (omit pageName) and read another page from its page.hrefs"), appId: appIdStr as Integer, fingerprint: "sections not a list"] +
            (parsed.configPage.error ? [configPageError: parsed.configPage.error] : [:])
    }

    def appTypeRaw = parsed.app.appType
    def appObj = [
        id: parsed.app.id,
        label: stripAppConfigHtml(parsed.app.trueLabel ?: parsed.app.label),
        name: parsed.app.name,
        appType: appTypeRaw instanceof Map ? [namespace: appTypeRaw.namespace, name: appTypeRaw.name, system: appTypeRaw.system == true, menu: appTypeRaw.menu] : null,
        disabled: parsed.app.disabled == true,
        parentAppId: parsed.app.parentAppId,
        installed: parsed.app.installed == true
    ]

    boolean isRmRule = _rmIsRuleAppType((appTypeRaw instanceof Map ? appTypeRaw.name : null)?.toString()) ||
        _rmIsRuleAppType(parsed.app.name?.toString())
    // RM keeps which rows are disabled in its state; the page only italicises them. Other rule versions, or unreadable
    // state, leave the page as the only marker.
    boolean rmHasDisabled = isRmRule
    if (isRmRule && [appTypeRaw instanceof Map ? appTypeRaw.name : null, parsed.app.name].any { it?.toString() in ['Rule-5.1', 'Button Rule-5.1'] }) {
        try {
            Map rmState = _nrState(appIdStr as Long)
            rmHasDisabled = [rmState.disabledActions, rmState.disabledTriggers].any { it instanceof Collection && !it.isEmpty() }
        } catch (Exception unreadable) {
            mcpLog("warn", "hub-admin", "hub_get_app_config app ${appIdStr}: rule state unreadable (${unreadable.class.simpleName}: ${unreadable.message}); disabled rows read from the page")
        }
    }
    // Inputs and controls read as hub_set_native_app / hub_set_rule(operation:'form') return them, with the
    // same redaction: a page input is redacted by its own type or by a credential-shaped name.
    Map formFields = null
    Map form = null
    String formError = null
    try {
        form = _nfPublic(_nfContext(appIdStr as Long, parsed as Map), [])
        formFields = form.get('fields').collectEntries { [(it.name): it] }
    } catch (IllegalStateException unmodelled) {
        mcpLog("warn", "hub-admin", "hub_get_app_config app ${appIdStr}: page form unreadable: ${unmodelled.message}")
        formError = unmodelled.message
    } catch (Exception unexpected) {
        mcpLogError("hub-admin", "hub_get_app_config app ${appIdStr}: page form read failed", unexpected)
        formError = "${unexpected.class.simpleName}: ${unexpected.message}".toString()
    }
    def passwordInputNames = [] as Set
    def sections = []
    def pageHrefs = []
    for (s in parsed.configPage.sections) {
        if (!(s instanceof Map)) continue
        def section = [title: stripAppConfigHtml(s.title), inputs: []]
        def paragraphs = []
        def components = []
        // The page renders a section from its body. Each input also appears in the section's input list,
        // which is read too, once per name, for a page whose body leaves an input out.
        List elements = (s.body instanceof List ? s.body : []) + (s.input instanceof List ? s.input.findAll { it instanceof Map && (it.element ?: 'input') == 'input' } : [])
        Set seen = [] as Set
        for (b in elements) {
            if (!(b instanceof Map)) continue
            String element = b.element?.toString() ?: 'input'
            if (element == 'input' && b.name && b.type != 'button') {
                if (!seen.add(b.name.toString())) continue
                if (b.type == 'password') passwordInputNames << b.name.toString()
                section.inputs << _appConfigInput(b, formFields?.get(b.name.toString()), parsed.settings)
            } else if (element in ['label', 'mode']) {
                Map appField = formFields?.get(element == 'mode' ? 'mode[mode]' : 'label')
                if (appField) section.inputs << appField
            } else if (element == 'component' && b.name) {
                components << [name: b.name, title: stripAppConfigHtml(b.title), description: stripAppConfigHtml(b.description)].findAll { k, v -> v }
            } else if (element == 'href' && (b.page || b.url)) {
                // A link to an external page or a sub-page, with the summary the page prints under it. The page
                // renders the external link whenever it has a url.
                Map link = b.url ? [url: b.url.toString()] : [page: b.page.toString()]
                link.title = _uiPlainTitle(b.title)
                def description = _appConfigShownText(b.description)
                if (description) link.description = description
                List disabled = rmHasDisabled ? _appConfigDisabledRows(b.description) : []
                if (disabled) link.disabled = disabled
                pageHrefs << link
            } else if (element == 'paragraph') {
                def rawHtml = (b.description ?: b.title)?.toString()
                def text = _appConfigShownText(rawHtml)
                if (!text) continue
                List disabled = rmHasDisabled ? _appConfigDisabledRows(rawHtml) : []
                paragraphs << (disabled ? [text: text, disabled: disabled] : text)
            }
        }
        if (paragraphs) section.paragraphs = paragraphs
        if (components) section.components = components
        sections << section
    }

    def children = (parsed.childApps instanceof List ? parsed.childApps : []).findAll { it instanceof Map }.collect { c ->
        [id: c.id, label: stripAppConfigHtml(c.label ?: c.name), name: c.name]
    }
    def childDevices = (parsed.childDevices instanceof List ? parsed.childDevices : []).findAll { it instanceof Map }.collect { d ->
        [id: d.id, name: stripAppConfigHtml(d.label ?: d.name)]
    }
    Map modeNames = parsed.modeMap instanceof Map ? parsed.modeMap : [:]
    appObj.restrictedModes = _nfAppValue(parsed as Map, true).collect { id -> [value: id, label: modeNames.get(id)?.toString()] }

    def result = [
        success: true,
        app: appObj,
        page: [
            name: parsed.configPage.name,
            title: stripAppConfigHtml(parsed.configPage.title),
            install: parsed.configPage.install == true,
            refreshInterval: parsed.configPage.refreshInterval,
            sections: sections
        ],
        childApps: children,
        childDevices: childDevices,
        endpoint: path
    ]
    if (parsed.configPage.error) result.configPageError = parsed.configPage.error
    if (pageHrefs) result.page.hrefs = pageHrefs
    if (pageName && parsed.configPage.name != null && parsed.configPage.name.toString() != pageName) {
        String formTool = isRmRule ? "hub_set_rule" : "hub_set_native_app"
        result.pageNote = "The hub rendered '${parsed.configPage.name}' instead of '${pageName}'; this app reaches that page only through its own link: open it with ${formTool}(operation:'form') and activate the href.".toString()
    }
    if (form != null) {
        result.controls = form.get('controls')
        // The page parts a form workflow cannot drive, such as another app's create button.
        if (form.get('unsupported')) result.unsupported = form.get('unsupported')
    } else {
        result.formError = formError
        result.note = _appConfigNote("Input values and controls are withheld: ${formError}. Read the stored values with includeSettings=true")
    }

    int settingsCount = (parsed.settings instanceof Map) ? parsed.settings.size() : 0
    result.settingsKeyCount = settingsCount
    if (includeSettings) {
        def rawSettings = (parsed.settings instanceof Map) ? parsed.settings : [:]
        if (!rawSettings || !_redactsCredentials()) {
            result.settings = rawSettings
        } else {
            // configure/json carries no types for settings declared on other pages. statusJson lists
            // every setting with its type, passwords included; only the App Status page hides those
            // rows, so one extra GET covers all pages. Without it a
            // password named outside the credential-name pattern cannot be told apart, so fail
            // closed: withhold the map rather than return it partly redacted.
            Map settingTypes = _appConfigSettingTypes(appIdStr as Integer)
            // A value the types do not cover and no safe rule classifies is the same gap: fail closed.
            List untyped = settingTypes == null ? [] : rawSettings.findAll { k, v ->
                String key = k?.toString()
                !passwordInputNames.contains(key) && !settingTypes.containsKey(key) && !_appConfigEmptyValue(v) && !(v instanceof Boolean) && !_appConfigSecretName(key)
            }.keySet().collect { it.toString() }
            if (settingTypes == null) {
                result.settingsNote = "Raw settings withheld: the app's setting types could not be read from /installedapp/statusJson/${appIdStr}, so password values could not be identified for redaction. Retry; the config page above is unaffected.".toString()
            } else if (untyped) {
                mcpLog("warn", "hub-admin", "hub_get_app_config: statusJson for app ${appIdStr} types none of ${untyped}; raw settings withheld")
                result.settingsNote = "Raw settings withheld: /installedapp/statusJson/${appIdStr} carries no type for ${untyped.take(5).join(', ')}${untyped.size() > 5 ? " and ${untyped.size() - 5} more" : ''}, so a password among them could not be told apart. Retry; the config page above is unaffected.".toString()
            } else {
                def redactedPw = _appConfigRedactedMarker()
                def redacted = [:]
                rawSettings.each { k, v ->
                    String key = k?.toString()
                    String type = passwordInputNames.contains(key) ? "password" : settingTypes.get(key)
                    redacted.put(k, _appConfigIsSecretSetting(key, type, v) ? redactedPw : v)
                }
                result.settings = redacted
                result.settingsRedactionNote = "Secret values read ${redactedPw}: every setting the hub types as a password, on any page of the app, plus free-text settings whose names look like credentials (password, passwd, pwd, pass, passphrase, passcode, secret, token, API/access/private key, credential, psk, a PIN, or a code named for a lock, user, alarm, keypad, installer or similar). OAuth accessToken settings follow the Hide access tokens setting instead. Empty values are shown as-is. Turn off Hide passwords and credentials in the MCP app's settings to read them.".toString()
            }
        }
    } else if (settingsCount > 0) {
        result.settingsNote = "Raw settings omitted; pass includeSettings=true to include them."
    }

    return result
}

// Rule Machine wraps each disabled action or trigger row, and its "Disabled Action" / "Disabled Trigger" legend,
// in an italic span that is red inline (2.5.2) or danger-classed (2.5.3); only those openers match, so italic HTML in
// a user's own action text is not a disabled row. The page never strikes a disabled action through, so do not key
// on <strike>. One paragraph, or the summary under a rule's action link, can hold the whole block.
private List _appConfigDisabledRows(Object html) {
    List disabled = []
    if (!html) return disabled
    def rows = html.toString() =~ /(?is)<span\s+(?:style=['"]\s*color:\s*red;?\s*['"]|class=['"]text-hubitat-danger['"])\s*>\s*<i>(.*?)<\/i>\s*<\/span>/
    while (rows.find()) {
        String row = stripAppConfigHtml(rows.group(1))?.trim()
        if (row && !(row ==~ /(?i)Disabled (?:Action|Trigger)s?/)) disabled << row
    }
    return disabled
}

// The page's text without HTML, or null for the placeholder it prints under an input or link that holds nothing.
private String _appConfigShownText(Object html) {
    String text = stripAppConfigHtml(html)
    return (text && !(text in ["Click to set", "Click to show"])) ? text : null
}

// One page input: the form engine's field, with a device picker's selected labels and HTML-free
// description. When the form engine has no field for it (the page could not be modelled), only the
// input's identity, never its value.
private Map _appConfigInput(Map element, Map field, Object storedSettings) {
    if (field == null) return [name: element.name, type: element.type, title: stripAppConfigHtml(element.title)]
    Map input = [:] + field
    def description = _appConfigShownText(field.description)
    if (description) input.description = description
    else input.remove('description')
    def stored = storedSettings instanceof Map ? storedSettings.get(field.name) : null
    if (_nrIsDeviceType(field.type?.toString()) && stored instanceof Map) input.selected = stored.collect { id, label -> [value: id.toString(), label: stripAppConfigHtml(label)] }
    return input
}

// name -> input type for every setting of an installed app, from statusJson appSettings.
// Null when the read fails or the shape is not the expected list, so the caller can fail closed.
private Map _appConfigSettingTypes(Integer appId) {
    def status
    try {
        status = _rmFetchStatusJson(appId)
    } catch (Exception e) {
        mcpLog("warn", "hub-admin", "hub_get_app_config: statusJson read for app ${appId} failed (${e.message}); raw settings withheld")
        return null
    }
    if (!(status?.appSettings instanceof List)) {
        mcpLog("warn", "hub-admin", "hub_get_app_config: statusJson for app ${appId} carried no appSettings list; raw settings withheld")
        return null
    }
    Map types = [:]
    for (rec in (status.appSettings as List)) {
        // A row with no type is left out, so the caller's untyped check withholds the map.
        if (rec instanceof Map && rec.name != null && rec.type != null) types.put(rec.name.toString(), rec.type.toString())
    }
    return types
}

// Unified source reader for the hub_get_source tool: dispatches on type to the
// shared app/driver helper (toolGetItemSource) or the library helper. The
// public param is `id`; we map it to the per-type id the helpers expect.
def toolGetSource(args) {
    def type = args.type
    if (!(type in ["app", "driver", "library"])) {
        throw new IllegalArgumentException("type is required and must be one of: app, driver, library")
    }
    def id = (args.id != null) ? args.id : (type == "app" ? args.appId : (type == "driver" ? args.driverId : args.libraryId))
    if (type == "library") {
        return toolGetLibrarySource(args + [libraryId: id])
    }
    def idParam = (type == "app") ? "appId" : "driverId"
    return toolGetItemSource(type, idParam, args + [(idParam): id])
}

private String _appInstanceCreateHint(Integer codeAppId, status) {
    switch (status) {
        case 401:
        case 403:
            return "Hub Security authentication failed (status ${status}). Verify MCP credentials and Hub Security state.".toString()
        case 404:
            return "codeAppId ${codeAppId} does not exist in Apps Code.".toString()
        case 500:
            return "Hub returned a server error (status 500). codeAppId ${codeAppId} may not exist in Apps Code (check hub_list_apps(scope='types')), or its code may be in a compile-error state (inspect via hub_get_source(type='app')).".toString()
        case 200:
            return "Hub returned the configure page without redirect (status 200). codeAppId ${codeAppId} may be a driver/library, or an already-instantiated app.".toString()
        default:
            return "Hub returned unexpected status ${status}. The codeAppId may not exist or may not be a user app.".toString()
    }
}

private Map _createUserAppInstance(Integer codeAppId) {
    def result
    try {
        result = hubInternalGetRaw("/installedapp/create/${codeAppId}")
    } catch (Exception e) {
        mcpLog("error", "hub-admin", "_createUserAppInstance(${codeAppId}): ${e.toString()}")
        // The hub answers an unknown code id with a 500, which the client raises as an exception.
        def statusMatch = (e.toString() =~ /status code:?\s*(\d{3})/)
        Integer thrownStatus = statusMatch.find() ? (statusMatch.group(1) as Integer) : null
        return [
            success: false,
            error: thrownStatus != null ? _appInstanceCreateHint(codeAppId, thrownStatus) : "User app instantiation failed: ${e.message ?: e.toString()}".toString(),
            codeAppId: codeAppId,
            status: thrownStatus
        ]
    }
    if (result?.status != 302 || !result.location) {
        def status = result?.status
        mcpLog("warn", "hub-admin", "_createUserAppInstance(${codeAppId}): non-302 (status=${status})")
        return [
            success: false,
            error: _appInstanceCreateHint(codeAppId, status),
            codeAppId: codeAppId,
            status: status,
            hubResponse: result?.data?.toString()?.take(500)
        ]
    }
    def m = (result.location =~ /\/installedapp\/configure\/(\d+)(?:\/([^\/?#]+))?/)
    if (!m) {
        mcpLog("warn", "hub-admin", "_createUserAppInstance(${codeAppId}): could not parse instance id from Location ${result.location}")
        return [
            success: false,
            error: "Could not parse new instance id from Location header: ${result.location}",
            codeAppId: codeAppId
        ]
    }
    def newId = m[0][1] as Integer
    def firstPage = m[0][2]
    mcpLog("info", "hub-admin", "_createUserAppInstance: created shell instance ${newId} from codeAppId ${codeAppId} (page=${firstPage ?: 'default'}); committing install")

    // Step 2: commit the install ("Done"). The GET above only made a pending
    // shell -- this is what fires installed()/initialize().
    def commit
    try {
        commit = _commitUserAppInstall(newId, firstPage)
    } catch (Exception ce) {
        mcpLog("warn", "hub-admin", "_createUserAppInstance: install-commit for instance ${newId} threw: ${ce.toString()}")
        return [
            success: false,
            error: "Instance ${newId} was created from codeAppId ${codeAppId} but the install-commit (Done) failed: ${ce.toString()}. The instance is an uninstalled shell -- delete it via hub_delete_native_app(appId:${newId}, confirm:true) and retry.",
            codeAppId: codeAppId,
            instanceAppId: newId,
            committed: false
        ]
    }
    if (commit?.uiBlocked) {
        // Deleting and retrying would meet the same refusal: keep the shell and fill it in.
        return [
            success: false,
            error: "Instance ${newId} was created but not installed: ${commit.error} Keep it -- write the listed settings with hub_set_native_app(operation:'update', app_id:${newId}, args:{fields:{...}}, confirm:true), which presses the first page's Done and so installs it; a page that needs more than one step is finished with operation:'form'.".toString(),
            codeAppId: codeAppId,
            instanceAppId: newId,
            committed: false
        ]
    }
    if (!commit?.success) {
        return [
            success: false,
            error: "Instance ${newId} was created but the install did not commit: ${commit?.error}. It is an uninstalled shell -- delete via hub_delete_native_app(appId:${newId}, confirm:true) and retry.",
            codeAppId: codeAppId,
            instanceAppId: newId,
            committed: false
        ]
    }
    mcpLog("info", "hub-admin", "_createUserAppInstance: committed install of instance ${newId} (scheduledJobs=${commit.scheduledJobCount}, subscriptions=${commit.eventSubscriptionCount}, installedConfirmed=${commit.installedConfirmed})")
    def out = [
        success: true,
        message: "User app instance created and install committed (Done submitted; installed()/initialize() fired). scheduledJobCount/eventSubscriptionCount reflect what initialize() registered.",
        codeAppId: codeAppId,
        instanceAppId: newId,
        committed: true,
        installedConfirmed: (commit.installedConfirmed == true),
        scheduledJobCount: commit.scheduledJobCount,
        eventSubscriptionCount: commit.eventSubscriptionCount,
        mode: "installAsUserApp"
    ]
    if (commit.note) out.note = commit.note
    return out
}

private Map _submitAppDoneForm(Integer instanceId, String pageName, boolean requireLiveSettings = false, boolean readLiveSettings = true) {
    // The ONE Done-submit implementation for an installed-app instance. Both callers ARE the same
    // UI action -- Done on a pending shell fires installed()/initialize(), on an installed instance
    // it fires updated() (the triggerUpdated refresh); the hub picks the callback, the request is
    // identical. Firmware fact (live, 2.5.0.159): /installedapp/configure/<id>/<page> is the HTML
    // page and answers POST with 405; the submit target is /installedapp/update/json.
    // Returns [status, page, submitted, liveSettingsUnavailable, shapeRejections].
    // `requireLiveSettings` = "a wrong value here OVERWRITES a configured instance, so refuse rather
    // than guess". The triggerUpdated refresh passes true: its whole job is a no-op re-submit, and if
    // the live settings cannot be read there is nothing to gain and a device wipe to lose. The install
    // commit passes false -- a fresh shell HAS no configured values, so the page defaults are correct
    // and refusing would block the install for no benefit. It also passes readLiveSettings=false: a
    // shell created a moment ago has no stored settings, so the statusJson read would return nothing.
    def cfg = _rmFetchConfigJson(instanceId, pageName)
    def page = pageName ?: (cfg?.configPage?.name?.toString()) ?: "mainPage"
    def schema = _rmCollectInputSchema(cfg?.configPage)
    // Done re-submits EVERY input, so a wrong value here is a value it overwrites. Precedence:
    // statusJson appSettings -> configure/json settings -> configPage. configPage-as-primary was
    // DISPROVEN LIVE (a configured bool renders its DEFAULT, so Done blanked the instance -- the
    // e2e settings-preservation assertion is the gate). statusJson is primary because it is the
    // only source that survives a DEVICE input: configure/json renders those unencodable with
    // value=null, while _rmLiveSettingsFromStatus rebuilds ids from deviceIdsForDeviceList into
    // the List _rmBuildSettingsBody CSV-encodes -- same helper the native-RM sub-page Done uses,
    // whose comment records the identical wipe on fw 2.5.0.143 (Button Controller buttonDev).
    def pageValues = [:]
    for (s in (cfg?.configPage?.sections ?: [])) {
        for (i in (s?.input ?: [])) {
            if (i instanceof Map && i.name) {
                def nm = i.name.toString()
                if (i.value != null) pageValues.put(nm, i.value)
                // A menu and a device picker show only their stored value; the page never fills them from defaultValue.
                else if (i.defaultValue != null && i.type != "menu" && !_isDevicePickerType(i.type)) pageValues.put(nm, i.defaultValue)
            }
        }
    }
    def liveSettings = [:]
    boolean liveSettingsUnavailable = false
    if (readLiveSettings) try {
        liveSettings = _rmLiveSettingsFromStatus(_rmFetchStatusJson(instanceId)) ?: [:]
    } catch (Exception statusErr) {
        // A dropped statusJson read must not silently degrade into "re-apply defaults".
        liveSettingsUnavailable = true
        mcpLog("error", "hub-admin", "_submitAppDoneForm: statusJson read for ${instanceId} failed (${statusErr.message}) -- the Done body cannot be built from live settings, so a DEVICE input would be re-submitted empty and CLEARED")
    }
    if (requireLiveSettings && liveSettingsUnavailable) {
        // Refuse rather than submit a body that would blank the instance. No POST is issued, so
        // nothing is committed and the caller reports the refusal.
        return [status: null, page: page, submitted: false, liveSettingsUnavailable: true, shapeRejections: 0]
    }
    def cfgSettings = (cfg?.settings instanceof Map) ? cfg.settings : [:]
    def settingsMap = [:]
    int shapeRejections = 0
    schema.each { name, meta ->
        // Each tier is consulted only for a value the previous one does not have, and only when
        // its SHAPE round-trips through _rmBuildSettingsBody (see _isSimpleSettingValue) -- an
        // unencodable value would be POSTed back as junk.
        //
        // A null OR an empty string defers to the next tier. Null means unset; "" is what a
        // never-configured typed input reports, and re-submitting "" for a typed input (a bool
        // especially) makes the hub's update handler 500 -- deferring to the page default is the
        // pre-existing behaviour that avoided exactly that. A stored `false` or `0` is a REAL
        // value and must win, which is why the test is emptiness rather than Groovy truthiness.
        def chosen = null
        for (candidate in [liveSettings.get(name), cfgSettings.get(name)]) {
            if (candidate == null) continue
            if (candidate instanceof CharSequence && candidate.toString().isEmpty()) continue
            if (!_isSimpleSettingValue(candidate)) {
                // Name + shape only, NEVER the value: a setting can be a password. Error level
                // because a rejection here means the Done body carries a page default over a
                // configured value, i.e. this submit may change state the caller did not ask to.
                shapeRejections++
                mcpLog("error", "hub-admin", "_submitAppDoneForm: setting '${name}' on app ${instanceId} has an unencodable shape (${_settingShapeName(candidate)}); the Done body falls back to the page default for it, which may OVERWRITE the configured value")
                continue
            }
            chosen = candidate
            break
        }
        if (chosen != null) {
            settingsMap.put(name, chosen)
        } else {
            settingsMap.put(name, pageValues.containsKey(name) ? pageValues.get(name) : "")
        }
    }
    // The UI will not submit this Done while the page fails its own checks (required fields,
    // number bounds, patterns); refuse the same way. No POST is issued, so nothing is committed.
    def uiProblems = _uiNavigationViolations(schema, settingsMap)
    if (uiProblems) {
        def detail = uiProblems.collect { "${it.name} (${it.title}): ${it.problem}" }.join("; ")
        return [status: null, page: page, submitted: false, uiBlocked: true,
                uiReason: "Hubitat's app page would refuse Done on page '${page}': ${detail}. Set these values first (required fields are the ones marked *), then retry.".toString(),
                liveSettingsUnavailable: liveSettingsUnavailable, shapeRejections: shapeRejections]
    }
    def body = _rmBuildSettingsBody(instanceId, settingsMap, schema)
    body.formAction = "update"
    body.currentPage = page
    body._action_update = "Done"
    body.pageBreadcrumbs = _rmPageBreadcrumbs(instanceId, page, "[]")
    // Per-type sidecars the form-encoded UI emits (matches _rmSubmitMainPageDone).
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
    // Fields the classic "Done" form also submits (_rmSubmitMainPageDone sends them too); the
    // hub's update handler 500s without these (verified live). The UI's referrer/url fields are
    // navigation hints only and are NOT required.
    body.appTypeId = ""
    body.appTypeName = ""
    body._cancellable = "false"

    def resp = hubInternalPostForm("/installedapp/update/json", body)
    return [status: resp?.status, page: page, submitted: true,
            liveSettingsUnavailable: liveSettingsUnavailable, shapeRejections: shapeRejections]
}

// Whether a stored settings value can go straight to _rmBuildSettingsBody, which CSV-encodes a
// List (capability), JSON-encodes an enum List, and toString()s everything else. Scalars and lists
// of scalars round-trip exactly; a Map -- or a List carrying one, which is how a device input can
// render -- would toString() into junk and POST that junk back, so those defer to the page value.
// NOT private: _submitAppDoneForm calls this from inside a schema.each {} closure, and a closure
// resolves owner calls through the metaClass, which does not expose private methods.
boolean _isSimpleSettingValue(v) {
    if (v instanceof Map) return false
    if (v instanceof List) {
        return v.every { it == null || it instanceof CharSequence || it instanceof Number || it instanceof Boolean }
    }
    return (v instanceof CharSequence || v instanceof Number || v instanceof Boolean)
}

// Coarse shape label for a log line: never getClass() (reflection is sandbox-blocked) and never the
// value, which can be a password. Closure-callable for the same reason as above.
def _settingShapeName(v) {
    if (v == null) return "null"
    if (v instanceof Map) return "Map"
    if (v instanceof List) return "List(with a non-scalar element)"
    return "non-scalar"
}

private Map _commitUserAppInstall(Integer instanceId, String pageName) {
    // A shell created a moment ago has no configured values to protect or read, so the Done body
    // is built from the page alone.
    def submit = _submitAppDoneForm(instanceId, pageName, false, false)
    if (submit.uiBlocked) {
        return [
            success: false,
            uiBlocked: true,
            error: submit.uiReason,
            note: "No Done was submitted, so the instance is not installed yet. Write the listed settings with hub_set_native_app(operation:'update', app_id:${instanceId}, args:{fields:{...}}, confirm:true), which presses the first page's Done and so installs it; a page that needs more than one step is finished with operation:'form'.".toString(),
            scheduledJobCount: 0,
            eventSubscriptionCount: 0
        ]
    }
    def st = submit.status
    def page = submit.page
    if (st != null && st >= 400) {
        return [
            success: false,
            error: "Done POST to /installedapp/update/json returned status ${st}",
            scheduledJobCount: 0,
            eventSubscriptionCount: 0
        ]
    }
    // Independent commit confirmation: a committed install reads app.installed==true; an inert shell
    // reads false. Gate success on THIS -- the hub can return HTTP 200 on a rejected/re-rendered Done,
    // so "the POST didn't 4xx" is not proof the commit took.
    def installedConfirmed = null   // null = read failed -> committed-but-unconfirmed
    try {
        def postCfg = _rmFetchConfigJson(instanceId, page)
        installedConfirmed = (postCfg?.app?.installed == true)
    } catch (Exception ce) {
        mcpLog("warn", "hub-admin", "_commitUserAppInstall: post-commit installed-confirmation read for ${instanceId} failed (${ce.message}) -- reporting committed but unconfirmed")
    }
    if (installedConfirmed == false) {
        return [
            success: false,
            error: "Done POST returned ${st} but the instance still reads app.installed=false -- the commit did not take (inert shell). Delete via hub_delete_native_app(appId:${instanceId}, confirm:true) and retry.",
            scheduledJobCount: 0,
            eventSubscriptionCount: 0
        ]
    }
    // Read back runtime state as evidence installed()/initialize() ran.
    def post = null
    try { post = _rmFetchStatusJson(instanceId) } catch (Exception se) {
        mcpLog("debug", "hub-admin", "_commitUserAppInstall: post-commit statusJson read for ${instanceId} failed (${se.message}) -- reporting zero counts")
    }
    def schedCount = (post?.scheduledJobs instanceof List) ? post.scheduledJobs.size() : 0
    def subCount = (post?.eventSubscriptions instanceof List) ? post.eventSubscriptions.size() : 0
    def out = [success: true, scheduledJobCount: schedCount, eventSubscriptionCount: subCount,
               installedConfirmed: (installedConfirmed == true)]
    // Every degradation on this path gets a sentence -- an unexplained zero count or a silent
    // settings fallback is indistinguishable from a healthy install otherwise.
    def notes = []
    if (installedConfirmed == null) {
        notes << "app.installed could not be independently confirmed (the config read failed); scheduledJobCount/eventSubscriptionCount are the lifecycle evidence."
    }
    if (post == null) {
        notes << "The post-commit statusJson read failed, so scheduledJobCount/eventSubscriptionCount are reported as 0 rather than measured -- they are not evidence that initialize() registered nothing."
    }
    if (submit?.shapeRejections) {
        notes << "${submit.shapeRejections} setting(s) had a shape that cannot be form-encoded and fell back to the page default; see the hub log for their names."
    }
    if (notes) out.note = notes.join(" ")
    return out
}

private Map _resolveCodeSource(args) {
    // Exactly one of source / sourceFile / importUrl. A missing file or a failed fetch throws here,
    // before anything is written to the hub.
    def modesSet = [args.sourceFile, args.source, args.importUrl].count { it }
    if (modesSet > 1) {
        throw new IllegalArgumentException("Provide exactly one of 'source' (inline), 'sourceFile' (File Manager filename), or 'importUrl' (URL the hub will fetch).")
    }
    if (args.sourceFile) {
        mcpLog("info", "hub-admin", "Reading code source from File Manager: ${args.sourceFile}")
        def bytes = null
        // The hub throws for a missing file rather than returning null.
        String readError = null
        try { bytes = downloadHubFile(args.sourceFile) }
        catch (Exception e) {
            readError = e.message ?: e.class.simpleName
            // A missing file throws with just its name, which adds nothing to the message below.
            if (readError == args.sourceFile.toString()) readError = null
            mcpLog("warn", "hub-admin", "Reading ${args.sourceFile} from File Manager failed: ${readError}")
        }
        if (bytes == null) {
            throw new IllegalArgumentException("Source file '${args.sourceFile}' not found in File Manager${readError ? " (the hub's read failed: ${readError})" : ""}".toString())
        }
        String code = new String(bytes, "UTF-8")
        mcpLog("info", "hub-admin", "Read ${code.length()} chars from ${args.sourceFile}")
        return [code: code, mode: "sourceFile"]
    }
    if (args.importUrl) {
        mcpLog("info", "hub-admin", "Reading code source from importUrl: ${args.importUrl}")
        def code = _fetchSourceFromUrl(args.importUrl)
        mcpLog("info", "hub-admin", "Read ${code.length()} chars from importUrl")
        return [code: code, mode: "importUrl"]
    }
    if (args.source) return [code: args.source, mode: "source"]
    throw new IllegalArgumentException("One of 'source' (inline Groovy code), 'sourceFile' (File Manager filename), or 'importUrl' (URL the hub will fetch) is required")
}

private String _codeListCall(String type) {
    return [app: "hub_list_apps(scope='types')", driver: "hub_list_drivers", library: "hub_list_libraries"][type]
}

private Map _codeCreateFailure(String type, String error, String note) {
    return [success: false, error: error, ("${type}Id".toString()): null, note: note, lastBackup: formatTimestamp(state.lastBackupTimestamp)]
}

private String _codeCreateUnknownOutcomeNote(String type) {
    return "Whether the ${type} was saved is unknown. Check ${_codeListCall(type)} before retrying: a retry can create a duplicate at a new id.".toString()
}

private String _hubMessageText(msg) {
    // The hub marks up the line number in a compile error (boom on line <span id='errorLine'>1</span>).
    String text = msg?.toString()
    if (text == null) return null
    return text.replaceAll(/<\/?[A-Za-z!][^>]*>/, "").trim()
}

private Map _createCodeItem(String type, args) {
    // The save response is the hub's verdict: compile errors and other refusals come back on it, so no
    // read follows.
    Map resolved = _resolveCodeSource(args)
    String sourceCode = resolved.code
    String sourceMode = resolved.mode
    String label = type.capitalize()

    mcpLog("info", "hub-admin", "Installing new ${type} (mode: ${sourceMode}, sourceLength: ${sourceCode.length()})")
    def parsed
    try {
        parsed = hubInternalPostJson("/${type}/saveOrUpdateJson".toString(), groovy.json.JsonOutput.toJson([id: null, source: sourceCode, version: 1]))
    } catch (Exception e) {
        mcpLogError("hub-admin", "${label} save request failed", e)
        return _codeCreateFailure(type, "${label} installation failed: ${e.message}".toString(), _codeCreateUnknownOutcomeNote(type))
    }

    if (parsed instanceof Map && parsed.success == false) {
        String hubMsg = _hubMessageText(parsed.message ?: parsed.errorMessage) ?: "the hub refused the source"
        mcpLog("warn", "hub-admin", "${label} create refused: ${hubMsg}")
        String note = type == "library" ?
            "The source needs a library() block with name, namespace, author and description. Fix the source and retry." :
            "Fix the source and retry."
        return _codeCreateFailure(type, "${label} installation failed: ${hubMsg}".toString(), note)
    }

    def newItemId = (parsed instanceof Map && parsed.success == true) ? parsed.id?.toString() : null
    if (!newItemId) {
        String detail = "an empty response"
        if (parsed instanceof Map && parsed._unparseable) detail = parsed.message?.toString()
        else if (parsed instanceof Map && parsed.success == true) detail = "success with no id"
        else if (parsed != null) detail = "an unrecognized response"
        mcpLog("warn", "hub-admin", "${label} create returned ${detail}")
        return _codeCreateFailure(type, "${label} install unverified: hub returned ${detail}".toString(), _codeCreateUnknownOutcomeNote(type))
    }

    mcpLog("info", "hub-admin", "${label} installed (ID: ${newItemId}, version: ${parsed.version}, mode: ${sourceMode})")
    def out = [
        success: true,
        message: "${label} installed successfully".toString(),
        ("${type}Id".toString()): newItemId,
        version: parsed.version,
        sourceMode: sourceMode,
        sourceLength: sourceCode.length(),
        lastBackup: formatTimestamp(state.lastBackupTimestamp)
    ]
    if (sourceMode == "sourceFile") out.note = "Source was read from File Manager file '${args.sourceFile}'.".toString()
    if (sourceMode == "importUrl") out.note = "Source was fetched from importUrl '${args.importUrl}' (hub-side fetch, no agent transcript).".toString()
    return out
}

private Map _createDriversBulk(args) {
    if (args.source != null || args.sourceFile != null || args.importUrl != null) {
        throw new IllegalArgumentException("Cannot supply both 'installs' (bulk mode) and single-item fields (source/sourceFile/importUrl). Use one mode or the other.")
    }
    if (!(args.installs instanceof List)) {
        throw new IllegalArgumentException("'installs' must be an array of objects, each with 'source', 'sourceFile', or 'importUrl'.")
    }
    if (args.installs.isEmpty()) {
        throw new IllegalArgumentException("'installs' array must not be empty.")
    }

    List bulkEntryKeys = _toolCatalogIndexes().installEntryArgs.hub_create_driver
    def itemResults = []
    def allSucceeded = true
    for (int idx = 0; idx < args.installs.size(); idx++) {
        // Each item owns its save; pause only after that outcome is known.
        if (idx > 0 && _resumableBudgetExceeded(null)) {
            return [success: allSucceeded, status: "in_progress", installs: itemResults,
                    installsRemaining: args.installs.subList(idx, args.installs.size()),
                    lastBackup: formatTimestamp(state.lastBackupTimestamp),
                    resume: [note: "Completed driver installs are retained. Continue with only installsRemaining; do not repeat completed installs."]]
        }
        def item = args.installs[idx]
        // Unknown keys first, so a misspelled source key is named rather than reported as missing.
        def unknownKeys = item instanceof Map ? item.keySet().collect { it as String }.findAll { !(it in bulkEntryKeys) } : []
        if (unknownKeys) {
            itemResults << [driverId: null, success: false, error: "Unknown installs[] argument${unknownKeys.size() == 1 ? '' : 's'}: ${unknownKeys.sort().join(', ')}. Nothing was installed for this entry. Valid arguments: ${bulkEntryKeys.join(', ')}.".toString(), note: "Correct or remove the key and send this entry again. See ${_codeGuidePointer()}.".toString()]
            allSucceeded = false
            continue
        }
        if (!(item instanceof Map) || (item.source == null && item.sourceFile == null && item.importUrl == null)) {
            itemResults << [driverId: null, success: false, error: "Each installs entry must have a 'source', 'sourceFile', or 'importUrl' field."]
            allSucceeded = false
            continue
        }
        try {
            def r = _createCodeItem("driver", item)
            def entry = [driverId: r.driverId, success: r.success == true]
            if (r.success) {
                entry.version = r.version
                entry.sourceMode = r.sourceMode
                entry.sourceLength = r.sourceLength
            } else {
                entry.error = r.error ?: "Install failed"
                if (r.note) entry.note = r.note
                if (r.lastBackup) entry.lastBackup = r.lastBackup
                allSucceeded = false
            }
            itemResults << entry
        } catch (Exception e) {
            mcpLogError("hub-admin", "Bulk driver install item ${idx} threw", e)
            itemResults << [driverId: null, success: false, error: e.message ?: e.toString(), errorClass: e.class.simpleName]
            allSucceeded = false
        }
    }

    def successCount = itemResults.count { it.success == true }
    mcpLog("info", "hub-admin", "Bulk driver install: ${successCount}/${itemResults.size()} succeeded")
    return [
        success: allSucceeded,
        message: allSucceeded ? "All ${itemResults.size()} driver(s) installed successfully." : "${successCount} of ${itemResults.size()} driver(s) installed successfully.",
        installs: itemResults,
        lastBackup: formatTimestamp(state.lastBackupTimestamp)
    ]
}

def toolCreateApp(args) {
    requireDestructiveConfirm(args.confirm)
    if (args.codeAppId != null) {
        if (args.source || args.sourceFile || args.importUrl) {
            throw new IllegalArgumentException(
                "codeAppId is mutually exclusive with source/sourceFile/importUrl. " +
                "Use two calls: (1) hub_create_app(source|sourceFile|importUrl, confirm:true) " +
                "to install the code, then " +
                "(2) hub_create_app(codeAppId:<codeAppId>, confirm:true) " +
                "to create the running instance."
            )
        }
        def codeAppId
        try { codeAppId = args.codeAppId as Integer }
        catch (Exception e) {
            throw new IllegalArgumentException("codeAppId must be a positive integer (got '${args.codeAppId}')")
        }
        // Range-check here so _createUserAppInstance keeps its "always returns an envelope, never
        // throws" contract.
        if (codeAppId == null || codeAppId < 1) {
            throw new IllegalArgumentException("codeAppId must be a positive integer (got '${args.codeAppId}')")
        }
        return _createUserAppInstance(codeAppId)
    }
    return _createCodeItem("app", args)
}

def toolCreateDriver(args) {
    requireDestructiveConfirm(args.confirm)
    if (args.installs != null) return _createDriversBulk(args)
    return _createCodeItem("driver", args)
}

def toolCreateLibrary(args) {
    requireDestructiveConfirm(args.confirm)
    return _createCodeItem("library", args)
}

def toolUpdateItemCode(String type, String idParam, args) {
    requireDestructiveConfirm(args.confirm)
    return toolUpdateItemCodeInner(type, idParam, args)
}

// Version of an app/driver from the hub's code list (/app/list/all/data, /driver/list/data). The
// single-item read (/<type>/list/single/data/<id>) carries it too, but the hub compiles the whole
// item to answer it and keeps the memory, so a version check never uses it. Null when the list is
// unreadable, so the caller falls back to the full code fetch.
private _itemRowVersion(String type, itemId) {
    try {
        def txt = type == "driver" ? hubInternalGet("/driver/list/data", null, 30) : hubInternalGet("/app/list/all/data", null, 30)
        def rows = txt ? new groovy.json.JsonSlurper().parseText(txt) : null
        def row = (rows instanceof List) ? rows.find { it instanceof Map && it.id?.toString() == itemId?.toString() } : null
        return (row instanceof Map) ? row.version : null
    } catch (Exception e) {
        mcpLog("warn", "hub-admin", "Could not read ${type} ${itemId} version: ${e.message}")
        return null
    }
}

// Self-save tuning (issue #522). The POST wait is short on purpose: the hub finishes the save on its
// own, and the App Code row's version is the confirmation. A direct call answers its client quickly;
// the detached package worker has nobody waiting and polls until the compile is done.
def _selfSaveRequestTimeoutSec() { 20 }
def _selfSaveWaitMs() { 20000L }
def _packageSelfSaveWaitMs() { 900000L }
def _selfSavePollMs() { 5000L }

// Poll the item's row until its version passes startedVersion. [version: n] when it did, null when
// the budget ran out first (the save may still land; hub_get_info settles the record later).
private Map _awaitSelfSave(String type, itemId, startedVersion, long waitMs) {
    Integer started = null
    try { started = startedVersion as Integer } catch (Exception ignored) { return null }
    long deadline = now() + waitMs
    while (true) {
        Integer cur = null
        try { cur = _itemRowVersion(type, itemId) as Integer } catch (Exception ignored) { cur = null }
        if (cur != null && cur > started) return [version: cur]
        long left = deadline - now()
        if (left <= 0L) return null
        pauseExecution(Math.min(_selfSavePollMs(), left))
    }
}

// Mark the pending self-deploy record landed at the given App Code version.
private void _settleSelfDeploy(confirmedVersion) {
    try {
        def done = [:] + (atomicState.lastSelfDeploy ?: [:])
        done.remove("assumed"); done.remove("status")
        done.confirmedVersion = confirmedVersion
        atomicState.lastSelfDeploy = done
    } catch (Exception e) {
        mcpLog("error", "hub-admin", "lastSelfDeploy settle write failed: ${e}")
    }
}

// A self-deploy record still "saving" is settled by the App Code row: the version passing
// startedVersion is the hub's own confirmation; nothing advancing within 20 minutes is a failure.
// Called by hub_get_info, so a save whose request thread the recompile killed still resolves.
Map _resolveSavingSelfDeploy(Map lsd) {
    if (lsd?.status != "saving" || lsd.appId == null) return lsd
    Integer cur = null
    Integer started = null
    try { cur = _itemRowVersion("app", lsd.appId) as Integer } catch (Exception ignored) { cur = null }
    try { started = lsd.startedVersion as Integer } catch (Exception ignored) { started = null }
    def done = null
    if (cur != null && started != null && cur > started) {
        done = [:] + lsd
        done.remove("assumed"); done.remove("status")
        done.confirmedVersion = cur
    } else if (cur != null && started != null && lsd.at instanceof Number && now() - (lsd.at as long) > 20L * 60L * 1000L) {
        // Only a readable row that has not moved proves the save did not land; an unreadable
        // row (hub still busy, auth hiccup) leaves the record saving for the next read.
        done = [:] + lsd
        done.remove("assumed"); done.remove("status")
        done.success = false
        done.error = "Self save did not land: the App Code version is still ${cur} twenty minutes after the save was accepted at version ${started}"
    }
    if (done == null) return lsd
    try { atomicState.lastSelfDeploy = done } catch (Exception e) { mcpLog("error", "hub-admin", "lastSelfDeploy settle write failed: ${e}") }
    return done
}

// Caller must have already invoked requireDestructiveConfirm -- gate fires once per call, not per bulk item.
// packageWorkerContext is supplied only by the scheduled package worker; public tool args never reach it.
private Map toolUpdateItemCodeInner(String type, String idParam, args, Map packageWorkerContext = null) {
    def itemId = args[idParam]
    if (!itemId) throw new IllegalArgumentException("${idParam} is required")

    // Validate source mode: exactly one of resave/sourceFile/source/importUrl. Tool schemas
    // advertise these as mutually exclusive, so the API boundary must reject the combination
    // rather than silently picking one via if/else precedence.
    def modesSet = [args.resave, args.sourceFile, args.source, args.importUrl].count { it }
    if (modesSet == 0) {
        throw new IllegalArgumentException("One of 'source', 'sourceFile', 'importUrl', or 'resave' is required")
    }
    if (modesSet > 1) {
        throw new IllegalArgumentException("Provide exactly one of 'source', 'sourceFile', 'importUrl', or 'resave'")
    }

    Integer expectedVersion = _expectedVersionArg(args)

    // Validate id shape up front: bad args are caller-recoverable and belong on the
    // IllegalArgumentException path, not in a runtime-failure envelope from the POST.
    def itemIdInt
    try {
        itemIdInt = itemId as Integer
    } catch (NumberFormatException | ClassCastException idErr) {
        throw new IllegalArgumentException("${idParam} must be an integer ${type} code id (got '${itemId}')")
    }

    // Self-update guard: blocks overwriting our own app source unless Developer Mode is on.
    // Resolve identity before fetching source. Use the same integer identity as the save POST.
    boolean isSelfUpdate = false
    boolean selfClassIdUnavailable = false
    if (type == "app") {
        def selfAppId = app?.id?.toString()
        if (selfAppId == null) {
            mcpLog("error", "hub-admin", "hub_update_app: self-update guard cannot verify -- app context unavailable (app=${app}); refusing appId=${itemId} to fail closed")
            throw new IllegalArgumentException("hub_update_app cannot verify the self-update guard: app context is unavailable (app=${app}). Refusing to proceed to avoid a silent self-update brick. Retry the call; this is typically a transient lifecycle window.")
        }
        // appId is an Apps Code CLASS id, so match it as well as the running instance id (as the
        // OAuth guard does); an instance-only check never matched a real self-update.
        isSelfUpdate = itemIdInt == (selfAppId as Integer)
        if (!isSelfUpdate) {
            def selfClassId = _resolveSelfAppClassId()
            selfClassIdUnavailable = selfClassId == null
            if (selfClassIdUnavailable && !settings.enableDeveloperMode) {
                throw new IllegalArgumentException("hub_update_app cannot verify the self-update guard: the MCP server's Apps Code class id could not be resolved. No code was changed. Retry when the app-code list is available.")
            }
            isSelfUpdate = selfClassId != null && itemIdInt == (selfClassId as Integer)
        }
        if (isSelfUpdate) {
            if (!settings.enableDeveloperMode) {
                mcpLog("warn", "hub-admin", "hub_update_app: self-update of MCP server app (id=${itemId}) BLOCKED -- Developer Mode is off")
                throw new IllegalArgumentException("hub_update_app refuses to overwrite the MCP server's own app source (appId=${itemId}) while Developer Mode is off. A bad self-update can brick the MCP loop -- enable 'Developer Mode Tools' in the MCP Rule Server app settings to permit self-updates.")
            }
            mcpLog("warn", "hub-admin", "hub_update_app: self-update of MCP server app (id=${itemId}) ALLOWED -- Developer Mode is on")
        }
    }

    def ajaxPath = (type == "app") ? "/app/ajax/code" : "/driver/ajax/code"
    def savePath = (type == "app") ? "/app/saveOrUpdateJson" : "/driver/saveOrUpdateJson"

    // Resolve source from one of three modes: source, sourceFile, or resave
    def sourceCode = null
    def sourceMode = null
    def freshVersion = null  // the version resave read with the source

    if (args.resave) {
        // Resave mode: fetch current source locally and re-save it (no cloud round-trip)
        sourceMode = "resave"
        mcpLog("info", "hub-admin", "Resave mode: fetching current ${type} ID ${itemId} source locally")
        def responseText = hubInternalGet(ajaxPath, [id: itemId])
        if (!responseText) throw new IllegalArgumentException("Could not fetch current source for ${type} ID ${itemId}")
        def parsed = new groovy.json.JsonSlurper().parseText(responseText)
        if (parsed.status == "error" || !parsed.source) {
            throw new IllegalArgumentException("Cannot read ${type} ID ${itemId}: ${parsed.errorMessage ?: 'no source returned'}")
        }
        sourceCode = parsed.source
        freshVersion = parsed.version
    } else {
        Map resolved = _resolveCodeSource(args)
        sourceCode = resolved.code
        sourceMode = resolved.mode
    }

    // Back up the current source unless the new one comes from a URL: whoever publishes at that
    // URL keeps the item's history (owner's call, issue #522), and hub_update_package passes
    // sourceOrigin "published" with the built source it already fetched. The backup would add one
    // more full copy of the old item to this request's heap while the hub compiles. Inline source
    // and File Manager sources keep it. The 1h cache inside backupItemSource means parallel-agent
    // conflicts cost nothing on the second call.
    def noBackupReason = null
    if (sourceMode == "importUrl") noBackupReason = "the source came from a URL, so its history lives where that URL is published"
    else if (args.sourceOrigin == "published") noBackupReason = "the package deploy's source is the published build"
    else if (args.backup == false) noBackupReason = "backup=false was passed"
    def itemBackup = null
    if (!noBackupReason) {
        try { itemBackup = backupItemSource(type, itemId.toString()) }
        catch (Exception backupErr) {
            Map unavailable = _itemBackupIndexUnavailableResult(backupErr)
            if (unavailable == null) throw backupErr
            return unavailable + [(idParam): itemId]
        }
    }
    if (noBackupReason) mcpLog("info", "hub-admin", "Pre-update source backup skipped for ${type} ID ${itemId}: ${noBackupReason}")

    // The current version: the one this call already read (resave's fetch, or a backup taken just
    // now -- a reused backup's may be up to an hour old), else the item's list row, else the full
    // ajax/code fetch.
    def sentVersion = freshVersion
    if (sentVersion == null && itemBackup?.fresh == true) sentVersion = itemBackup.version
    if (sentVersion == null) sentVersion = _itemRowVersion(type, itemId)
    if (sentVersion == null) {
        try {
            def versionResponse = hubInternalGet(ajaxPath, [id: itemId])
            if (versionResponse) sentVersion = new groovy.json.JsonSlurper().parseText(versionResponse).version
        } catch (Exception vErr) {
            mcpLog("warn", "hub-admin", "Could not read the current version of ${type} ID ${itemId}: ${vErr.message}")
        }
        // Nothing readable: a reused backup's version still serves a plain save, which the hub refuses
        // if it is stale. Not a lock, which needs a current read, nor a self save, whose confirmation
        // waits for the version to pass this one and would take a stale one as already landed.
        if (sentVersion == null && expectedVersion == null && !isSelfUpdate) sentVersion = itemBackup?.version
    }
    if (sentVersion == null) {
        throw new IllegalArgumentException("Could not determine current version for ${type} ID ${itemId}. The ${type} may not exist.")
    }
    // A stale lock is refused here, before the hub spends a compile on a save it would refuse.
    if (expectedVersion != null && sentVersion.toString() != expectedVersion.toString()) {
        return _versionConflictResult(type, idParam, itemId, expectedVersion, sentVersion)
    }

    // Self-update detection: when the MCP server updates its OWN app code, a SUCCESS reloads the app
    // (dropping our in-flight cloud response) and a big-file FAILURE 504s before the hub returns --
    // either way the result can't ride back on this call. Record it to atomicState so a later
    // hub_get_info read recovers the hub's verbatim outcome (incl. compile errors). See issue #237.
    //
    // Reuse the identity resolved by the guard so another lookup cannot disagree with it.
    // Developer Mode explicitly allows self-updates even when class lookup is unavailable.
    if (type == "app" && selfClassIdUnavailable && sourceCode.length() > 500000) {
        mcpLog("warn", "hub-admin", "hub_update_app: large app-source update to id ${itemId} but the self app-class lookup returned null -- if this IS the MCP server, #237 self-deploy error capture is disabled for this deploy.")
    }
    mcpLog("info", "hub-admin", "Updating ${type} ID: ${itemId} (version: ${sentVersion}, mode: ${sourceMode}, sourceLength: ${sourceCode.length()})")
    int sourceLen = sourceCode.length()
    try {
        def parsed = _postItemSave(type, itemIdInt, sourceCode, sentVersion, isSelfUpdate, itemId)
        def stale = _staleVersionRejection(parsed)
        sourceCode = null
        if (stale != null) return _versionConflictResult(type, idParam, itemId, sentVersion, stale.currentVersion, expectedVersion != null)

        def success = false
        def errorMsg = null

        if (parsed instanceof Map) {
            success = parsed.success == true
            if (success && parsed.id != null && parsed.id.toString() != itemIdInt.toString()) {
                // saveOrUpdateJson is an upsert: success echoing a DIFFERENT id means the hub
                // saved somewhere other than the target -- likely a duplicate code entry.
                success = false
                errorMsg = "Hub reported success but saved to ${type} id ${parsed.id} instead of the targeted id ${itemIdInt} -- a duplicate code entry may have been created. Check Apps Code / Drivers Code before retrying."
            } else if (!success) {
                errorMsg = _hubMessageText(parsed.message ?: parsed.errorMessage) ?: "hub response lacked success=true: ${parsed.toString().take(200)}"
            }
        } else if (parsed == null) {
            // null is strictly an EMPTY body (a non-JSON body comes back as the _unparseable
            // sentinel Map and fails above). A SELF-update legitimately drops the response --
            // the recompile kills the in-flight request, and the lastSelfDeploy stash below
            // covers recovery; for anything else, fail closed rather than assume the write landed.
            success = isSelfUpdate
            if (!success) errorMsg = "Empty response from ${savePath} -- the update may or may not have applied. Re-read the ${type} source to verify before retrying."
        } else {
            errorMsg = "Unexpected response shape from ${savePath}: ${parsed.toString().take(200)}"
        }

        // Persist the self-deploy outcome (incl. the hub's verbatim errorMessage) so it survives the
        // post-success app reload AND the failure-case cloud 504 -- a later hub_get_info read recovers it.
        if (isSelfUpdate) {
            try {
                def stash = [
                    success: success,
                    error: success ? null : (errorMsg ?: "Update failed -- the hub returned an error"),
                    sourceMode: sourceMode,
                    importUrl: (args.importUrl ?: null),
                    sourceLength: sourceLen,
                    at: now()
                ]
                def packageCorrelation = _validatedPackageWorkerContext(packageWorkerContext)
                if (packageCorrelation != null) {
                    stash.requestId = packageCorrelation.requestId
                    stash.packageRef = packageCorrelation.packageRef
                }
                // A dropped (empty) response is the expected self-update signature, but the success
                // is inferred, not hub-confirmed -- mark it so consumers can choose to re-verify, and
                // record what the App Code row must pass for the save to count as landed.
                if (success && parsed == null) {
                    stash.assumed = true
                    stash.status = "saving"
                    stash.startedVersion = sentVersion
                    stash.appId = itemId.toString()
                }
                atomicState.lastSelfDeploy = stash
            } catch (Exception stashErr) {
                // Never break the deploy over bookkeeping, but a lost stash means the self-deploy
                // outcome is unrecoverable -- say so instead of failing silently.
                mcpLog("error", "hub-admin", "lastSelfDeploy stash write failed -- self-deploy outcome record lost: ${stashErr}")
            }
        }
        Map landed = null
        if (success && isSelfUpdate && parsed == null) {
            landed = _awaitSelfSave(type, itemId, sentVersion,
                packageWorkerContext != null ? _packageSelfSaveWaitMs() : _selfSaveWaitMs())
            if (landed != null) _settleSelfDeploy(landed.version)
        }

        if (success) {
            mcpLog("info", "hub-admin", "${type} ID ${itemId} updated successfully (mode: ${sourceMode})")
            def successResult = [
                success: true,
                message: "${type.capitalize()} code updated successfully",
                (idParam): itemId,
                previousVersion: sentVersion,
                sourceMode: sourceMode,
                sourceLength: sourceLen,
                lastBackup: formatTimestamp(state.lastBackupTimestamp)
            ]
            if (isSelfUpdate && parsed == null) {
                if (landed != null) {
                    successResult.version = landed.version
                    successResult.verified = true
                } else {
                    successResult.assumed = true
                    successResult.status = "saving"
                    successResult.note = "The hub accepted the save and is still compiling it. Poll hub_get_info.lastSelfDeploy: status disappears and confirmedVersion appears once the App Code version passes ${sentVersion}; a record still saving after 20 minutes turns into a failure."
                }
            }
            def modeNote = null
            if (sourceMode == "resave") modeNote = "Source was fetched and re-saved entirely on-hub — no cloud round-trip."
            if (sourceMode == "sourceFile") modeNote = "Source was read from File Manager file '${args.sourceFile}' — no cloud size limits."
            if (sourceMode == "importUrl") modeNote = "Source was fetched from importUrl '${args.importUrl}' (hub-side fetch, no agent transcript)."
            if (modeNote) successResult.note = successResult.note ? "${successResult.note} ${modeNote}".toString() : modeNote
            if (noBackupReason) successResult.sourceBackup = "skipped: ${noBackupReason}".toString()

            // Optional triggerUpdated: fire updated() on the named running instance so
            // subscriptions/schedules/atomicState re-initialize against the new code.
            // The UI Save flow does NOT do this (empirically verified via JS bundle +
            // Chrome network capture); this is deliberately extra behavior, opt-in only.
            // Apps only -- drivers refresh per-device, libraries are #include'd at compile.
            if (type == "app" && args.containsKey('triggerUpdated') && args.triggerUpdated != null) {
                def triggerId = null
                try { triggerId = args.triggerUpdated as Integer }
                catch (Exception coerceErr) {
                    successResult.triggerUpdated = args.triggerUpdated
                    successResult.updatedFired = false
                    successResult.repairHints = ["triggerUpdated must be a positive integer (instance appId); got '${args.triggerUpdated}'. The code save succeeded; lifecycle was NOT refreshed."]
                    return successResult
                }
                if (triggerId == null || triggerId < 1) {
                    successResult.triggerUpdated = args.triggerUpdated
                    successResult.updatedFired = false
                    successResult.repairHints = ["triggerUpdated must be a positive integer (instance appId). The code save succeeded; lifecycle was NOT refreshed."]
                    return successResult
                }
                successResult.triggerUpdated = triggerId
                try {
                    if (!settings.enableDeveloperMode && _protectedAppIds().contains(triggerId.toString())) {
                        successResult.updatedFired = false
                        successResult.partial = true
                        successResult.repairHints = ["App ${triggerId} is protected: triggerUpdated requires Developer Mode. No Done submit was sent. The new code is deployed (success:true); enable Developer Mode or refresh the app manually through its Hubitat UI."]
                        mcpLog("warn", "hub-admin", "triggerUpdated refused for protected instance ${triggerId}: Developer Mode is required; no Done submit was sent")
                        return successResult
                    }
                    // Submitting Done on an already-installed instance is what fires
                    // updated(). Goes through the SAME _submitAppDoneForm the install-commit
                    // uses -- one Done-submit implementation, one endpoint. The status check
                    // matters: hubInternalPostForm hands back a status for a response the
                    // platform client does not throw on, and without this a rejected Done
                    // would report updatedFired:true.
                    // requireLiveSettings=true: this instance is CONFIGURED, and a Done built from
                    // page defaults would overwrite it. Refusing loses only the refresh.
                    def submit = _submitAppDoneForm(triggerId, null, true)
                    if (submit?.liveSettingsUnavailable) {
                        throw new IllegalStateException("the instance's live settings (statusJson) could not be read, so the Done was NOT submitted -- submitting it would have re-sent this page's defaults and cleared the instance's configured settings, device selections included")
                    }
                    if (submit?.uiBlocked) {
                        throw new IllegalStateException("the Done was NOT submitted: ${submit.uiReason}".toString())
                    }
                    def submitStatus = submit?.status
                    if (submitStatus != null && submitStatus >= 400) {
                        throw new RuntimeException("Done POST to /installedapp/update/json returned status ${submitStatus}")
                    }
                    successResult.updatedFired = true
                    if (submit?.shapeRejections) {
                        // The refresh DID fire, so this is not a failure -- but a setting whose shape
                        // could not be encoded was re-sent as its page default, which may have changed
                        // state the caller never asked to change.
                        successResult.repairHints = ((successResult.repairHints ?: []) + [
                            "${submit.shapeRejections} setting(s) on instance ${triggerId} could not be form-encoded and were re-submitted as this page's defaults, which may have overwritten their configured values -- see the hub log for the setting names, then re-check them via hub_get_app_config(appId:${triggerId}, includeSettings:true)."
                        ])
                    }
                    mcpLog("info", "hub-admin", "triggerUpdated: fired updated() on instance ${triggerId} via /installedapp/update/json after app code save")
                } catch (Exception updErr) {
                    // Half-failure: the code was deployed (success:true holds) but the
                    // opt-in lifecycle refresh failed. The whole point of triggerUpdated
                    // was the lifecycle refresh -- so flag this as partial so callers
                    // checking `result.partial` see the half-failure without having to
                    // drill into updatedFired. Mirrors the partial:true pattern used
                    // elsewhere (e.g. the RM wizard's partial-success envelope).
                    successResult.updatedFired = false
                    successResult.partial = true
                    successResult.repairHints = ["triggerUpdated requested but the Done submit to /installedapp/update/json for instance ${triggerId} failed: ${updErr.toString()}. The new code is deployed (success:true) but subscriptions/schedules may not have refreshed -- open the app in the hub UI and click Done, or toggle it off/on."]
                    mcpLog("warn", "hub-admin", "triggerUpdated failed on instance ${triggerId}: ${updErr.toString()}")
                }
            }

            return successResult
        } else {
            def out = [
                success: false,
                error: errorMsg ?: "Update failed - the hub returned an error",
                (idParam): itemId,
                note: "Check the Groovy source code for syntax errors or compilation issues.",
                lastBackup: formatTimestamp(state.lastBackupTimestamp)
            ]
            if (_isConcurrentSaveRefusal(type, errorMsg)) {
                out.note = _concurrentSaveNote(type)
                out.retryable = true
            }
            return out
        }
    } catch (Exception e) {
        mcpLogError("hub-admin", "${type} update failed", e)
        if (isSelfUpdate) {
            try {
                def stash = [
                    success: false,
                    error: "${type.capitalize()} update failed: ${e.message}",
                    sourceMode: sourceMode,
                    importUrl: (args.importUrl ?: null),
                    sourceLength: sourceLen,
                    at: now()
                ]
                def packageCorrelation = _validatedPackageWorkerContext(packageWorkerContext)
                if (packageCorrelation != null) {
                    stash.requestId = packageCorrelation.requestId
                    stash.packageRef = packageCorrelation.packageRef
                }
                atomicState.lastSelfDeploy = stash
            } catch (Exception stashErr) {
                mcpLog("error", "hub-admin", "lastSelfDeploy stash write failed -- self-deploy outcome record lost: ${stashErr}")
            }
        }
        return [
            success: false,
            error: "${type.capitalize()} update failed: ${e.message}",
            lastBackup: formatTimestamp(state.lastBackupTimestamp)
        ]
    }
}

// An explicit expectedVersion as an Integer, or null when the caller sent none. Rejected before
// any side effect: an explicit null (a templated arg would otherwise read as "no lock" and let the
// write through) and anything but a whole number (2.9 must not truncate into a matching lock).
private Integer _expectedVersionArg(args) {
    if (!(args instanceof Map) || !args.containsKey('expectedVersion')) return null
    def version = args.expectedVersion
    if (version == null) {
        throw new IllegalArgumentException("expectedVersion was supplied as null. Omit the field entirely to skip the optimistic-lock check, or pass an integer.")
    }
    if (!(version instanceof Integer || version instanceof Long) || version < 0 || version > 2147483647L) {
        throw new IllegalArgumentException("expectedVersion must be an integer (got '${version}'): pass the version number hub_get_source returned, unchanged.")
    }
    return version as Integer
}

// POST /app|driver/saveOrUpdateJson: a non-null id updates that code class in place, and the hub
// refuses a version that is no longer current. The answer is {success, id, message[, version]}; a
// compile failure rides verbatim in message. A self save waits only briefly: the hub keeps
// compiling after the caller stops waiting, and a 2.6 MB self save outruns the platform's 300 s
// request cap (issue #522), so a timeout returns null (the dropped response a self save produces
// anyway) and the App Code row's version confirms the save instead.
private _postItemSave(String type, Integer itemIdInt, String sourceCode, version, boolean isSelfUpdate, itemId) {
    String savePath = type == "app" ? "/app/saveOrUpdateJson" : "/driver/saveOrUpdateJson"
    String saveBody = groovy.json.JsonOutput.toJson([id: itemIdInt, source: sourceCode, version: version])
    if (!isSelfUpdate) return hubInternalPostJson(savePath, saveBody)
    try {
        return hubInternalPostJson(savePath, saveBody, _selfSaveRequestTimeoutSec())
    } catch (Exception postErr) {
        if (!(postErr.toString() =~ /(?i)time.?out/)) throw postErr
        mcpLog("info", "hub-admin", "Self save POST for ${type} ID ${itemId} gave no answer within ${_selfSaveRequestTimeoutSec()}s; the hub keeps compiling -- confirming by version")
        return null
    }
}

// The hub's refusal of a stale save version ("Version does not match, attempting to update version
// 1, current version is 2"): [currentVersion: Integer or null], or null for any other answer.
private Map _staleVersionRejection(parsed) {
    if (!(parsed instanceof Map) || parsed.success == true) return null
    String msg = (parsed.message ?: parsed.errorMessage)?.toString()
    if (!msg || !(msg =~ /(?i)version does not match/)) return null
    def m = msg =~ /(?i)current version is (\d+)/
    return [currentVersion: m.find() ? (m.group(1) as Integer) : null]
}

// Overlapping saves of one item get exactly one of these answers, not a verdict on the source, so
// they are retryable: a driver's is this database error, an app's is nothing but its compile path.
// Anything else, such as a compile error naming SQLException, keeps the normal note.
private boolean _isConcurrentSaveRefusal(String type, msg) {
    String text = msg?.toString()?.trim()
    if (!text) return false
    if (type == "driver") return text == "java.sql.SQLException: Failed to update device type"
    return type == "app" && text ==~ /\.\/appTargetDir\/user_app_.+_\d+\.groovy/
}

private String _concurrentSaveNote(String type) {
    return "This is the hub's answer when another save of this ${type} runs at the same moment; the source itself was not rejected. Wait a moment, re-read the ${type} (its version may have changed), then retry. Send saves of the same ${type} one at a time. See ${_codeGuidePointer()}.".toString()
}

private String _codeGuidePointer() { "hub_get_tool_guide(section='hub_admin_write_code')" }

// callerLocked: the caller sent expectedVersion. Without it the version is the one this call read,
// so the answer says another save landed first rather than echoing a lock the caller never sent.
private Map _versionConflictResult(String type, String idParam, itemId, sentVersion, currentVersion, boolean callerLocked = true) {
    String hubSide = currentVersion != null ? "hub has ${currentVersion}" : "the hub reports a different version"
    mcpLog("warn", "hub-admin", "${type} update ID ${itemId} refused: version ${sentVersion} is stale (${hubSide}); nothing was written")
    String error = callerLocked ?
        "Optimistic-lock conflict: expected ${type} version ${sentVersion} but ${hubSide}. Nothing was written. Re-read the source and retry with the new expectedVersion." :
        "Another save of this ${type} landed while this one was being sent, so the hub refused it (${hubSide}). Nothing was written. Re-read the source and send your change again."
    def out = [
        success: false,
        error: error.toString(),
        (idParam): itemId,
        conflict: true,
        note: "Another save changed this ${type}. Read it again with hub_get_source, reapply your change to that source, and pass its version as expectedVersion. See ${_codeGuidePointer()}.".toString(),
        lastBackup: formatTimestamp(state.lastBackupTimestamp)
    ]
    if (callerLocked) out.expectedVersion = sentVersion
    if (currentVersion != null) out.currentVersion = currentVersion
    return out
}

// Validate the scheduled worker's private correlation against the live marker at
// the moment lastSelfDeploy is written. A stale worker or mismatched context is
// treated as an ordinary direct self-update and cannot bind another deploy.
private Map _validatedPackageWorkerContext(packageWorkerContext) {
    if (!(packageWorkerContext instanceof Map) || packageWorkerContext.requestId == null ||
            packageWorkerContext.packageRef == null) return null
    synchronized (WRITE_RESERVATION_LOCK) {
        def marker = _writeStateValueLocked("packageDeployInFlight")
        if (marker instanceof Map && marker.requestId != null && marker.ref != null &&
                marker.requestId.toString() == packageWorkerContext.requestId.toString() &&
                marker.ref.toString() == packageWorkerContext.packageRef.toString()) {
            return [requestId: marker.requestId.toString(), packageRef: marker.ref.toString()]
        }
    }
    return null
}

def toolUpdateAppCode(args) {
    // sourceOrigin is set only by hub_update_package's worker (the source it verified against the
    // published build); from the public argument map it would skip the pre-update backup for
    // arbitrary source, so it is dropped here like the package correlation fields.
    if (args instanceof Map && args.containsKey("sourceOrigin")) {
        args = [:] + args
        args.remove("sourceOrigin")
    }
    return _toolUpdateAppCode(args, null)
}

// Worker-only overload: package identity is an out-of-band execution context,
// never a field accepted from hub_update_app's public argument map.
def _toolUpdateAppCode(args, Map packageWorkerContext) {
    if (args.updates != null) {
        throw new IllegalArgumentException("Bulk mode ('updates' array) is not supported for hub_update_app. Apps do not cluster the way drivers do; update each app individually.")
    }
    if (args.oauth == null) {
        requireDestructiveConfirm(args.confirm)
        return toolUpdateItemCodeInner("app", "appId", args, packageWorkerContext)
    }

    // OAuth fold: enable/configure OAuth on the app CODE definition (apps only), optionally alongside
    // a source update. The destructive gate fires once here; the inner source path is then called
    // WITHOUT re-gating. OAuth-only is allowed (no source mode required).
    requireDestructiveConfirm(args.confirm)
    if (!args.appId) throw new IllegalArgumentException("appId is required")

    def hasSourceMode = [args.resave, args.sourceFile, args.source, args.importUrl].count { it } > 0
    def result
    if (hasSourceMode) {
        result = toolUpdateItemCodeInner("app", "appId", args, packageWorkerContext)   // gate already fired above
        if (result?.success != true) return result               // source update failed -- leave OAuth untouched
    } else {
        result = [success: true, appId: args.appId.toString(), message: "OAuth settings updated (no source change)."]
    }

    def oauthRes = _updateAppOAuth(args.appId, args.oauth)
    result.oauth = oauthRes
    if (oauthRes.success != true) {
        result.success = false
        if (hasSourceMode) result.partial = true   // source saved, OAuth leg failed
    }
    return result
}

// Enable/configure OAuth on an app code definition via /app/updateOAuth (the UI's "Enable OAuth in
// App" step). Mirrors the admin UI: pre-fills clientId/clientSecret from the app's current values
// unless the caller overrides them, so re-running never clobbers an already-enabled app; a fresh app
// has empty creds and the hub generates them. If the current-creds read FAILS, it refuses (returns
// success:false) rather than submit empty creds and risk blanking a live client. Returns the
// resulting clientId/clientSecret.
private Map _updateAppOAuth(appId, oauth) {
    if (!(oauth instanceof Map)) {
        throw new IllegalArgumentException("oauth must be an object, e.g. {enabled: true} (optionally client_id, client_secret, refresh_secret).")
    }
    // Self-protection: never alter the MCP server's OWN app OAuth -- the clientId/secret back the
    // live /mcp access token, so changing them would break this very connection. Gated like the
    // source self-update guard (Developer Mode required to override). Match BOTH the installed-INSTANCE
    // id (app.id) AND the Apps Code CLASS id -- OAuth targets the class id, which differs from app.id,
    // so an instance-id-only check would miss the real self-OAuth attempt.
    boolean isSelf = false
    def selfInstanceId = app?.id?.toString()
    if (selfInstanceId != null && appId?.toString() == selfInstanceId) {
        isSelf = true
    } else {
        def selfClassId = _resolveSelfAppClassId()
        if (selfClassId != null && appId?.toString() == selfClassId.toString()) isSelf = true
    }
    if (isSelf && !settings.enableDeveloperMode) {
        mcpLog("warn", "hub-admin", "hub_update_app: OAuth change to the MCP server's own app (id=${appId}) BLOCKED -- Developer Mode is off")
        throw new IllegalArgumentException("hub_update_app refuses to change the MCP server's own OAuth (appId=${appId}) while Developer Mode is off -- its client id/secret back the live /mcp access token and changing them would break this connection. Enable Developer Mode to override.")
    }

    boolean enabled = (oauth.enabled != null) ? (oauth.enabled == true) : true
    boolean refreshSecret = (oauth.refresh_secret == true)
    String clientId = (oauth.client_id != null) ? oauth.client_id.toString() : null
    String clientSecret = (oauth.client_secret != null) ? oauth.client_secret.toString() : null
    if (clientId == null || clientSecret == null) {
        def cur = _readAppOAuthCreds(appId)
        if (cur == null) {
            // Read FAILED -- can't tell whether the app already has creds. Submitting empty would
            // clobber existing ones, so refuse rather than risk blanking a live OAuth client.
            return [success: false, error: "Could not read the app's current OAuth credentials to preserve them.",
                    note: "Refusing to submit empty credentials (would clobber an already-enabled app's live creds). Pass client_id + client_secret explicitly, or retry."]
        }
        if (clientId == null) clientId = cur.clientId?.toString() ?: ""
        if (clientSecret == null) clientSecret = cur.clientSecret?.toString() ?: ""
    }

    try {
        // Query MAP, not an embedded querystring: the platform client escapes an embedded '?'
        // into the literal path and /app/updateOAuth is an EXACT route, so the embedded form
        // 404s (see the _hubRequest guard). No manual URLEncoder here either -- the query map
        // encodes the values, and pre-encoding them would double-encode.
        def respText = hubInternalGet("/app/updateOAuth", [
            id: appId.toString(),
            oauthEnabled: enabled,
            clientId: clientId,
            clientSecret: clientSecret,
            refreshSecret: refreshSecret
        ], 30)
        // Distinguish "the hub answered, and said no" from "that was not a hub answer at all",
        // mirroring hubInternalPostJson's _unparseable split. An HTML body here is almost always a
        // Hub Security login page, and telling the caller to add an oauth block for that is a
        // wrong-diagnosis dead end.
        def parsed = null
        boolean unparseable = false
        if (respText) {
            try { parsed = new groovy.json.JsonSlurper().parseText(respText) }
            catch (Exception parseErr) { unparseable = true }
        }
        if (parsed instanceof Map) {
            if (parsed.success == true) {
                return [success: true, enabled: enabled,
                        clientId: parsed.clientId ?: (clientId ?: null),
                        clientSecret: parsed.clientSecret ?: (clientSecret ?: null)]
            }
            // Surface the hub's own error text from a structured failure rather than a generic message.
            if (parsed.error || parsed.message) {
                return [success: false, error: (parsed.error ?: parsed.message)?.toString(), response: respText?.take(300),
                        note: "The app's source must declare OAuth (an oauth block + mappings) before it can be enabled."]
            }
        }
        if (unparseable) {
            return [success: false, error: "/app/updateOAuth returned a non-JSON body", response: respText?.take(300),
                    note: "That is not a hub OAuth answer -- an HTML body here is typically the Hub Security login page, so check the MCP app's Hub Security credentials (and that the hub is not mid-reboot). This says nothing about whether the app's source declares OAuth."]
        }
        if (!respText) {
            return [success: false, error: "/app/updateOAuth returned an empty body",
                    note: "The request reached the hub but produced no answer -- retry; if it persists, check Hub Security credentials."]
        }
        return [success: false, error: "/app/updateOAuth did not report success", response: respText?.take(300),
                note: "The app's source must declare OAuth (an oauth block + mappings) before it can be enabled."]
    } catch (Exception e) {
        mcpLogError("hub-admin", "OAuth update failed", e)
        return [success: false, error: "OAuth update failed: ${e.message}", note: "Check the app id and Hub Security credentials."]
    }
}

// Read an app code definition's current OAuth client id/secret from the app-detail JSON
// (/app/list/single/data/<id> returns [{...oauthClientId, oauthClientSecret...}]). Used to preserve
// existing creds on re-enable. Returns a Map {clientId, clientSecret} (values may be null for a fresh
// app) on a SUCCESSFUL read, or NULL if the detail could not be read. The caller MUST distinguish
// these: "fresh app, no creds yet" (send empty -> hub generates) vs "read failed, don't know" (refuse
// to submit empty, which would clobber an already-enabled app's live creds). No other read returns
// the creds; the hub compiles the whole app to answer this one, so only an OAuth change makes it.
private _readAppOAuthCreds(appId) {
    try {
        def txt = hubInternalGet("/app/list/single/data/${java.net.URLEncoder.encode(appId.toString(), 'UTF-8')}", null, 30)
        def d = txt ? new groovy.json.JsonSlurper().parseText(txt) : null
        def o = (d instanceof List && d) ? d[0] : (d instanceof Map ? d : null)
        if (!(o instanceof Map)) return null   // unreadable / unrecognized shape -- NOT "no creds"
        return [clientId: o.oauthClientId, clientSecret: o.oauthClientSecret]
    } catch (Exception e) {
        mcpLogError("hub-admin", "Could not read current OAuth creds for app ${appId}", e)
        return null
    }
}

def toolUpdateDriverCode(args) {
    requireDestructiveConfirm(args.confirm)

    // Bulk mode validation: updates array and single-driver fields are mutually exclusive
    if (args.updates != null) {
        if (args.driverId != null || args.source != null || args.sourceFile != null || args.importUrl != null || args.resave != null || args.expectedVersion != null) {
            throw new IllegalArgumentException("Cannot supply both 'updates' (bulk mode) and single-driver fields (driverId/source/sourceFile/importUrl/resave/expectedVersion). Use one mode or the other -- expectedVersion belongs on each entry inside updates[] for bulk.")
        }
        if (!(args.updates instanceof List)) {
            throw new IllegalArgumentException("'updates' must be an array of objects, each with 'driverId' and 'sourceFile' (or 'source', 'importUrl', or 'resave').")
        }
        if (args.updates.isEmpty()) {
            throw new IllegalArgumentException("'updates' array must not be empty.")
        }

        // Bulk mode: apply each update in sequence, continue on per-item errors
        List bulkEntryKeys = _toolCatalogIndexes().updateEntryArgs.hub_update_driver
        def itemResults = []
        def allSucceeded = true
        for (int idx = 0; idx < args.updates.size(); idx++) {
            // Retain the untouched suffix, including repeated IDs and each expectedVersion.
            if (idx > 0 && _resumableBudgetExceeded(null)) {
                return [success: allSucceeded, status: "in_progress", updates: itemResults,
                        updatesRemaining: args.updates.subList(idx, args.updates.size()),
                        lastBackup: formatTimestamp(state.lastBackupTimestamp),
                        resume: [note: "Completed driver updates are retained. Continue with only updatesRemaining, preserving their order and expectedVersion values."]]
            }
            def item = args.updates[idx]
            // A misspelled lock must not fall through to a lock-free save of this entry, and a
            // misspelled driverId is named rather than reported as missing.
            def unknownKeys = item instanceof Map ? item.keySet().collect { it as String }.findAll { !(it in bulkEntryKeys) } : []
            if (unknownKeys) {
                itemResults << [driverId: item.driverId?.toString() ?: "item[${idx}]", success: false, error: "Unknown updates[] argument${unknownKeys.size() == 1 ? '' : 's'}: ${unknownKeys.sort().join(', ')}. Nothing was changed for this driver. Valid arguments: ${bulkEntryKeys.join(', ')}.", note: "Correct or remove the key and send this entry again. See ${_codeGuidePointer()}.".toString()]
                allSucceeded = false
                continue
            }
            if (!(item instanceof Map) || !item.driverId) {
                itemResults << [driverId: (item instanceof Map ? item.driverId?.toString() : null) ?: "item[${idx}]", success: false, error: "Each updates entry must have a 'driverId' field."]
                allSucceeded = false
                continue
            }
            try {
                def singleArgs = [driverId: item.driverId]
                if (item.sourceFile != null) singleArgs.sourceFile = item.sourceFile
                if (item.source != null) singleArgs.source = item.source
                if (item.importUrl != null) singleArgs.importUrl = item.importUrl
                if (item.backup != null) singleArgs.backup = item.backup
                if (item.resave != null) singleArgs.resave = item.resave
                // Use containsKey so explicit `expectedVersion: null` propagates and hits Inner's
                // null-rejector; the resulting IAE is caught below and recorded per-item.
                if (item.containsKey('expectedVersion')) singleArgs.expectedVersion = item.expectedVersion
                def r = toolUpdateItemCodeInner("driver", "driverId", singleArgs)
                def entry = [driverId: item.driverId.toString(), success: r.success == true]
                if (r.success) {
                    entry.sourceMode = r.sourceMode
                    entry.sourceLength = r.sourceLength
                } else {
                    entry.error = r.error ?: "Update failed"
                    if (r.note) entry.note = r.note
                    if (r.retryable) entry.retryable = true
                    if (r.lastBackup) entry.lastBackup = r.lastBackup
                    if (r.conflict) {
                        entry.conflict = true
                        if (r.containsKey('expectedVersion')) entry.expectedVersion = r.expectedVersion
                        if (r.currentVersion != null) entry.currentVersion = r.currentVersion
                    }
                    allSucceeded = false
                }
                itemResults << entry
            } catch (Exception e) {
                mcpLogError("hub-admin", "Bulk hub_update_driver item ${idx} (driverId ${item.driverId}) threw", e)
                itemResults << [driverId: item.driverId.toString(), success: false, error: e.message ?: e.toString(), errorClass: e.class.simpleName]
                allSucceeded = false
            }
        }

        def successCount = itemResults.count { it.success == true }
        mcpLog("info", "hub-admin", "Bulk hub_update_driver: ${successCount}/${itemResults.size()} succeeded")
        return [
            success: allSucceeded,
            message: allSucceeded ? "All ${itemResults.size()} driver(s) updated successfully." : "${successCount} of ${itemResults.size()} driver(s) updated successfully.",
            updates: itemResults,
            lastBackup: formatTimestamp(state.lastBackupTimestamp)
        ]
    }

    // Single-driver mode: gate already fired above, dispatch to Inner directly.
    return toolUpdateItemCodeInner("driver", "driverId", args)
}

private boolean _isMissingItemBackupFailure(String type, Exception e) {
    // The backup read is the first request a delete makes, so it is where a missing app or library
    // shows: no source for an app (whose delete answers status:true for any id), an empty list for a
    // library. A missing driver's read fails with a server error that does not say why.
    if (!(e instanceof IllegalArgumentException)) return false
    if (type == "app") return e.message?.contains("no source code returned")
    if (type == "library") return e.message?.contains("library not found")
    return false
}

private String _deleteInUseNote(String type) {
    if (type == "library") return "Remove the #include references from apps and drivers first."
    if (type == "driver") return "Switch its devices to another driver first."
    return "Remove its app instances first."
}

private Map _deleteRefusal(String type, String id, hubMsg, String responseText) {
    // The hub refuses a delete of an item in use with a message saying so, and a delete of a driver
    // that does not exist with a bare status:false; only the first gets the in-use advice.
    String listCall = _codeListCall(type)
    Map out = [success: false, id: id, type: type, response: responseText?.take(500)]
    String msg = hubMsg?.toString()?.trim()
    if (!msg) {
        out.error = "The hub refused the delete of ${type} ${id} without giving a reason. The ${type} may not exist.".toString()
        out.note = "Check ${listCall} for the ${type} id.".toString()
    } else if (msg =~ /(?i)\bin use\b/) {
        out.error = msg
        out.note = _deleteInUseNote(type)
    } else {
        out.error = msg
        out.note = "Check ${listCall} for the ${type} id.".toString()
    }
    return out
}

private Integer _deleteIdArg(rawId) {
    // A positive Integer written as plain digits: no sign, no spaces, nothing a hub path would carry
    // verbatim.
    String raw = rawId?.toString()
    if (raw != null && raw ==~ /\d{1,10}/) {
        Long value = raw as Long
        if (value > 0 && value <= Integer.MAX_VALUE) return value as Integer
    }
    throw new IllegalArgumentException("item_id is required and must be a positive integer (got: '${rawId}')")
}

def toolDeleteItem(args) {
    String type = args.type?.toString()
    if (!(type in ["app", "driver", "library"])) {
        throw new IllegalArgumentException("type is required and must be one of: app, driver, library")
    }
    requireDestructiveConfirm(args.confirm)
    Integer idInt = _deleteIdArg(args.item_id)
    // The result carries the canonical digits as a String, the schema type of item_id.
    String id = idInt.toString()
    String label = type.capitalize()

    def backupTaken = null
    try {
        backupTaken = (type == "library") ? backupLibrarySource(id) : backupItemSource(type, id)
    } catch (Exception backupErr) {
        // An unreadable backup index refuses the delete: the backup's fate is unknown.
        Map unavailable = _itemBackupIndexUnavailableResult(backupErr)
        if (unavailable != null) return unavailable + [id: id, type: type]
        // No delete without a backup: the delete is what makes the source unrecoverable.
        mcpLog("warn", "hub-admin", "Pre-delete backup failed for ${type} ${id}: ${backupErr.message} -- delete not sent")
        String listCall = _codeListCall(type)
        if (_isMissingItemBackupFailure(type, backupErr)) {
            return [success: false, error: "${label} ${id} does not exist".toString(), id: id, type: type,
                    note: "Nothing was deleted. ${listCall} lists the ${type} ids.".toString()]
        }
        if (backupErr.message?.contains("file upload failed")) {
            return [success: false, error: "Pre-delete backup failed for ${type} ${id}: ${backupErr.message}".toString(), id: id, type: type,
                    note: "Nothing was deleted. Retry once File Manager accepts writes.".toString()]
        }
        return [success: false, error: "Could not read the source of ${type} ${id} for the pre-delete backup: ${backupErr.message}. The ${type} may not exist.".toString(),
                id: id, type: type, note: "Nothing was deleted. Check ${listCall} for the ${type} id.".toString()]
    }

    String deletePath = [app: "/app/edit/deleteJsonSafe/", driver: "/driver/editor/deleteJson/", library: "/library/edit/deleteJson/"][type]
    mcpLog("warn", "hub-admin", "Deleting ${type} ID: ${id}")
    try {
        def responseText = hubInternalGet("${deletePath}${idInt}".toString())
        def parsed = null
        if (responseText) {
            try { parsed = new groovy.json.JsonSlurper().parseText(responseText) }
            catch (Exception parseErr) { mcpLog("warn", "hub-admin", "Delete ${type} ${id}: response is not JSON") }
        }
        if (!(parsed instanceof Map) || !(parsed.containsKey("success") || parsed.containsKey("status"))) {
            return [success: false, error: "Could not confirm the delete of ${type} ${id}: the hub's reply is not a recognized answer.".toString(),
                    id: id, type: type, response: responseText?.take(500),
                    note: "Check ${_codeListCall(type)} to see whether the ${type} is still there.".toString()]
        }
        if (parsed.success != true && parsed.status?.toString() != "true") {
            def hubMsg = parsed.message ?: parsed.error ?: parsed.errorMessage
            mcpLog("warn", "hub-admin", "Delete ${type} ${id} refused: ${hubMsg ?: 'no reason given'}")
            return _deleteRefusal(type, id, hubMsg, responseText)
        }

        mcpLog("info", "hub-admin", "${label} ID ${id} deleted successfully")
        // .toString() because the stored key is a String but Map.get(GString) does not coerce (hashCode mismatch → silent null).
        def backupEntry = (_itemBackupManifest())?.get("${type}_${id}".toString()) ?: backupTaken
        def result = [
            success: true,
            message: "${label} deleted successfully. Source code backed up to File Manager.".toString(),
            id: id,
            type: type,
            lastBackup: formatTimestamp(state.lastBackupTimestamp),
            backupFile: backupEntry?.fileName,
            backupRetentionNote: backupEntry ? _itemBackupRetentionNote() : null,
            restoreHint: backupEntry ? "To restore: use '${[app: 'hub_create_app', driver: 'hub_create_driver', library: 'hub_create_library'][type]}' with the backup source, or download ${backupEntry.fileName} from Hubitat > Settings > File Manager and re-install manually. ${_itemBackupRetentionNote()}".toString() : null
        ]
        return result
    } catch (Exception e) {
        mcpLogError("hub-admin", "${label} deletion failed", e)
        return [success: false, error: "${label} deletion failed: ${e.message}".toString(), id: id, type: type]
    }
}

private Map backupLibrarySource(String libraryId) {
    return _withBackupLock("backup library ${libraryId}") { _backupLibrarySourceLocked(libraryId) }
}

private Map _backupLibrarySourceLocked(String libraryId) {
    def manifest = _itemBackupManifest()
    String key = "library_${libraryId}"
    def existing = _reusableItemBackup(manifest, key, "Library")
    if (existing != null) return existing

    def responseText = hubInternalGet("/library/list/single/data/${libraryId}")
    if (!responseText) {
        throw new IllegalArgumentException("Cannot back up library ID ${libraryId}: empty response from hub")
    }

    def parsed
    try {
        parsed = new groovy.json.JsonSlurper().parseText(responseText)
    } catch (Exception parseEx) {
        throw new IllegalArgumentException("Cannot back up library ID ${libraryId}: failed to parse hub response: ${parseEx.message}")
    }

    if (!(parsed instanceof List) || parsed.isEmpty()) {
        throw new IllegalArgumentException("Cannot back up library ID ${libraryId}: library not found")
    }

    def libData = parsed[0]
    def backupSource = libData.source ?: ""
    def fileName = _itemBackupFileName("mcp-backup-library-${libraryId}.groovy")
    byte[] backupBytes = backupSource.getBytes("UTF-8")
    try {
        uploadHubFile(fileName, backupBytes)
    } catch (Exception e) {
        mcpLogError("hub-admin", "Failed to save library backup file '${fileName}'", e)
        throw new IllegalArgumentException("Cannot back up library ID ${libraryId}: file upload failed -- ${e.message}")
    }

    def entry = [
        type: "library",
        id: libraryId.toString(),
        fileName: fileName,
        version: libData.version,
        timestamp: now(),
        sourceLength: backupSource.length(),
        sizeBytes: backupBytes.length
    ]
    _publishUploadedItemBackup(key, entry)
    mcpLog("info", "hub-admin", "Backed up library ID ${libraryId} source to File Manager: ${fileName} (version ${libData.version}, ${backupSource.length()} chars)")
    return entry + [fresh: true]
}

def toolGetLibrarySource(args) {
    def libraryId = args.libraryId
    if (!libraryId) throw new IllegalArgumentException("libraryId is required")
    if (!libraryId.toString().isInteger() || libraryId.toString().toInteger() <= 0) throw new IllegalArgumentException("libraryId must be a positive integer (got: '${libraryId}')")

    def maxChunkSize = 64000
    def requestedOffset = args.offset ? args.offset as int : 0
    def requestedLength = args.length ? Math.min(args.length as int, maxChunkSize) : defaultReadChunkChars()

    try {
        def responseText = hubInternalGet("/library/list/single/data/${libraryId}")
        if (!responseText) return [success: false, error: "Empty response from hub for library ${libraryId}"]

        def parsed
        try {
            parsed = new groovy.json.JsonSlurper().parseText(responseText)
        } catch (Exception parseErr) {
            return [success: false, error: "Failed to parse library response: ${parseErr.message}"]
        }

        if (!(parsed instanceof List) || parsed.isEmpty()) {
            return [success: false, error: "Library ${libraryId} not found"]
        }

        def libraryData = parsed[0]
        def fullSource = libraryData?.source ?: ""
        def totalLength = fullSource.length()

        // For large sources, save full copy to File Manager so update can use sourceFile
        def savedToFile = null
        def savedToFileError = null
        if (totalLength > maxChunkSize) {
            def sourceFileName = "mcp-source-library-${libraryId}.groovy"
            try {
                uploadHubFile(sourceFileName, fullSource.getBytes("UTF-8"))
                savedToFile = sourceFileName
                mcpLog("info", "hub-admin", "Saved full library ID ${libraryId} source to File Manager: ${sourceFileName} (${totalLength} chars)")
            } catch (Exception saveErr) {
                savedToFileError = saveErr.message ?: saveErr.toString()
                mcpLog("warn", "hub-admin", "Could not save library source to File Manager: ${savedToFileError}")
            }
        }

        // Extract the requested chunk
        def endIndex = Math.min(requestedOffset + requestedLength, totalLength)
        def chunk = (requestedOffset < totalLength) ? fullSource.substring(requestedOffset, endIndex) : ""
        def hasMore = endIndex < totalLength

        mcpLog("info", "hub-admin", "Retrieved library ID ${libraryId} source: ${totalLength} chars total, returning offset ${requestedOffset}..${endIndex}${hasMore ? ' (more available)' : ''}")

        def result = [
            success: true,
            libraryId: libraryId,
            source: chunk,
            version: libraryData?.version,
            name: libraryData?.name,
            namespace: libraryData?.namespace,
            totalLength: totalLength,
            offset: requestedOffset,
            chunkLength: chunk.length(),
            hasMore: hasMore
        ]
        if (hasMore) {
            result.nextOffset = endIndex
            result.remainingChars = totalLength - endIndex
            result.hint = "Call again with offset: ${endIndex} to get the next chunk."
        }
        if (savedToFile) {
            result.sourceFile = savedToFile
            result.sourceFileHint = "Full source saved to File Manager. Use hub_update_library with sourceFile: '${savedToFile}' to update without cloud size limits."
        }
        if (savedToFileError) {
            result.sourceFileError = "Failed to auto-save full source to File Manager: ${savedToFileError}. Use chunked reads (offset/length) to retrieve the full source."
        }
        return result
    } catch (Exception e) {
        mcpLogError("hub-admin", "Failed to get library source", e)
        return [success: false, error: "Failed to get library source: ${e.message}"]
    }
}

def toolUpdateLibraryCode(args) {
    requireDestructiveConfirm(args.confirm)
    def libraryId = args.libraryId
    if (!libraryId) throw new IllegalArgumentException("libraryId is required")
    if (!libraryId.toString().isInteger() || libraryId.toString().toInteger() <= 0) throw new IllegalArgumentException("libraryId must be a positive integer (got: '${libraryId}')")
    Integer expectedVersion = _expectedVersionArg(args)

    // Resolve source: exactly one of resave/sourceFile/source/importUrl. Matches the mutex
    // enforcement in toolUpdateItemCodeInner -- tool schema advertises these as mutually exclusive.
    def sourceCode = null
    def sourceMode = null
    def freshVersion = null
    def modesSet = [args.resave, args.sourceFile, args.source, args.importUrl].count { it }
    if (modesSet == 0) {
        throw new IllegalArgumentException("One of 'source', 'sourceFile', 'importUrl', or 'resave' is required")
    }
    if (modesSet > 1) {
        throw new IllegalArgumentException("Provide exactly one of 'source', 'sourceFile', 'importUrl', or 'resave'")
    }

    if (args.resave) {
        sourceMode = "resave"
        mcpLog("info", "hub-admin", "Resave mode: fetching current library ID ${libraryId} source locally")
        def responseText = hubInternalGet("/library/list/single/data/${libraryId}")
        if (!responseText) throw new IllegalArgumentException("Could not fetch current source for library ID ${libraryId}")
        def parsed
        try {
            parsed = new groovy.json.JsonSlurper().parseText(responseText)
        } catch (Exception parseEx) {
            throw new IllegalArgumentException("Could not parse library source response for ID ${libraryId}: ${parseEx.message}")
        }
        if (!(parsed instanceof List) || parsed.isEmpty()) {
            throw new IllegalArgumentException("Library ID ${libraryId} not found")
        }
        def lib = parsed[0]
        sourceCode = lib.source
        freshVersion = lib.version
        if (!sourceCode) throw new IllegalArgumentException("Library ID ${libraryId} has no source to resave")
    } else {
        Map resolved = _resolveCodeSource(args)
        sourceCode = resolved.code
        sourceMode = resolved.mode
    }

    // Fail-closed: a backup that cannot be taken aborts the update, as for app and driver code.
    // backupLibrarySource reuses a backup from the last hour, keeping the pre-edit original.
    def backupEntry = null
    // No backup for a source that came from a URL (its history lives where that URL is published)
    // or when the caller passes backup=false; same rule as hub_update_app.
    def noBackupReason = null
    if (sourceMode == "importUrl") noBackupReason = "the source came from a URL, so its history lives where that URL is published"
    else if (args.backup == false) noBackupReason = "backup=false was passed"
    if (noBackupReason) {
        mcpLog("info", "hub-admin", "Pre-update source backup skipped for library ${libraryId}: ${noBackupReason}")
    } else {
        try {
            backupEntry = backupLibrarySource(libraryId.toString())
        } catch (Exception backupErr) {
            Map unavailable = _itemBackupIndexUnavailableResult(backupErr)
            if (unavailable == null) throw backupErr
            return unavailable + [libraryId: libraryId]
        }
    }
    // Same version rule as app and driver code: the version this call read (resave's fetch or a
    // backup taken just now), else read it; a reused backup's version may be up to an hour old.
    def sentVersion = freshVersion
    if (sentVersion == null && backupEntry?.fresh == true) sentVersion = backupEntry.version
    def versionFetchError = null
    if (sentVersion == null) {
        try {
            def versionText = hubInternalGet("/library/list/single/data/${libraryId}")
            def versionParsed = versionText ? new groovy.json.JsonSlurper().parseText(versionText) : null
            if (versionParsed instanceof List && !versionParsed.isEmpty()) sentVersion = versionParsed[0]?.version
        } catch (Exception vErr) {
            versionFetchError = vErr.message ?: vErr.toString()
            mcpLog("warn", "hub-admin", "Could not read the current version of library ID ${libraryId}: ${versionFetchError}")
        }
        // Nothing readable: a reused backup's version still serves a plain save, which the hub refuses
        // if it is stale; a lock needs a current read.
        if (sentVersion == null && expectedVersion == null) sentVersion = backupEntry?.version
    }
    if (sentVersion == null) {
        throw new IllegalArgumentException("Could not determine current version for library ID ${libraryId} -- version fetch failed (${versionFetchError ?: 'unknown'}) and no usable backup has a version. Check that the library exists.")
    }
    if (expectedVersion != null && sentVersion.toString() != expectedVersion.toString()) {
        return _versionConflictResult("library", "libraryId", libraryId, expectedVersion, sentVersion)
    }

    mcpLog("info", "hub-admin", "Updating library ID: ${libraryId} (version: ${sentVersion}, mode: ${sourceMode}, sourceLength: ${sourceCode.length()})")
    try {
        def result = _postLibrarySave(libraryId, sourceCode, sentVersion)
        def stale = _staleVersionRejection(result)
        if (stale != null) return _versionConflictResult("library", "libraryId", libraryId, sentVersion, stale.currentVersion, expectedVersion != null)

        if (result?.success == false) {
            def msg = _hubMessageText(result?.message) ?: "Hub returned failure"
            mcpLog("warn", "hub-admin", "Library update failed: ${msg}")
            return [
                success: false,
                error: "Library update failed: ${msg}",
                libraryId: libraryId,
                note: "Check the Groovy source code for syntax errors or compilation issues."
            ]
        }

        mcpLog("info", "hub-admin", "Library ID ${libraryId} updated successfully (mode: ${sourceMode})")
        def successResult = [
            success: true,
            message: "Library code updated successfully",
            libraryId: libraryId,
            previousVersion: sentVersion,
            newVersion: result?.version,
            sourceMode: sourceMode,
            sourceLength: sourceCode.length(),
            lastBackup: formatTimestamp(state.lastBackupTimestamp)
        ]
        if (sourceMode == "resave") successResult.note = "Source was fetched and re-saved entirely on-hub -- no cloud round-trip."
        if (sourceMode == "sourceFile") successResult.note = "Source was read from File Manager file '${args.sourceFile}' -- no cloud size limits."
        if (noBackupReason) successResult.sourceBackup = "skipped: ${noBackupReason}".toString()
        return successResult
    } catch (Exception e) {
        mcpLogError("hub-admin", "Library update failed", e)
        return [success: false, error: "Library update failed: ${e.message}"]
    }
}

private _postLibrarySave(libraryId, String sourceCode, version) {
    def body = groovy.json.JsonOutput.toJson([id: libraryId as Integer, source: sourceCode, version: version as Integer])
    return hubInternalPostJson("/library/saveOrUpdateJson", body)
}

def toolListInstalledApps(args) {
    def filter = args?.filter ?: "all"
    def includeHidden = args?.includeHidden == true
    def cursor = args?.cursor

    def validFilters = ["all", "builtin", "user", "disabled", "parents", "children"]
    if (!validFilters.contains(filter)) {
        throw new IllegalArgumentException("Invalid filter '${filter}'. Must be one of: ${validFilters.join(', ')}")
    }

    try {
        def responseText = hubInternalGet("/hub2/appsList")
        if (!responseText) {
            return [success: false, error: "Empty response from /hub2/appsList", note: "Hub internal API may be transiently unavailable."]
        }
        def parsed
        try {
            parsed = new groovy.json.JsonSlurper().parseText(responseText)
        } catch (Exception parseErr) {
            return [success: false, error: "Failed to parse /hub2/appsList response: ${parseErr.message}", note: "Hubitat firmware may have changed the endpoint format."]
        }
        def apps = parsed?.apps ?: []

        // Flatten tree to list with parentId.
        // If a parent is hidden and excluded from the output, its children are promoted to the
        // nearest visible ancestor (or null at root) so their parentId always references an
        // app actually present in the results — no orphan references.
        // NOTE: closure parameter is 'node' (not 'app') to avoid shadowing the Hubitat SDK's
        // `app` reference. Hubitat's `app` is used elsewhere for app.label, app.id, etc.
        def flat = []
        def recurse
        recurse = { Map node, parentId ->
            def d = node?.data ?: [:]
            def isHidden = d.hidden == true
            def included = includeHidden || !isHidden
            if (included) {
                flat << [
                    id: d.id,
                    // Strip embedded HTML some apps put in their list name (e.g.
                    // strikethrough/color spans), mirroring hub_get_app_config.
                    name: stripAppConfigHtml(d.name),
                    type: d.type,
                    // The app type's code-class id: the same id scope='types' lists and
                    // hub_get_source(type='app') / the hub's /app/editor/<id> page take, so an
                    // instance resolves to its code without matching on the (ambiguous) type name.
                    appTypeId: d.appTypeId,
                    disabled: d.disabled == true,
                    user: d.user == true,
                    hidden: isHidden,
                    // The hub flags an app whose type is retired (e.g. the legacy Sonos integration).
                    deprecated: d.deprecated == true,
                    parentId: parentId,
                    hasChildren: (node?.children?.size() ?: 0) > 0,
                    childCount: node?.children?.size() ?: 0
                ]
            }
            def childParentId = included ? d.id : parentId
            node?.children?.each { c -> recurse(c, childParentId) }
        }
        apps.each { a -> recurse(a, null) }

        def filtered = flat.findAll { entry ->
            switch (filter) {
                case "all": return true
                case "builtin": return !entry.user
                case "user": return entry.user
                case "disabled": return entry.disabled
                case "parents": return entry.hasChildren
                case "children": return entry.parentId != null
                default:
                    // Whitelist-validated above; reaching here means validFilters and this
                    // switch have drifted. Throw rather than return an over-broad result,
                    // so the mismatch is visible instead of silently shipping stale data.
                    throw new IllegalStateException("filter branch missing for '${filter}' -- validFilters and switch have drifted")
            }
        }

        if (args?.nameFilter) filtered = _nameFiltered(filtered, args.nameFilter)
        def paged = _paginateList(filtered, cursor, 50, "hub_list_apps")
        def result = [
            apps: paged.page,
            count: paged.page.size(),
            filter: filter,
            totalOnHub: flat.size()
        ]
        if (cursor != null) {
            result.total = filtered.size()
            if (paged.nextCursor != null) result.nextCursor = paged.nextCursor
        }
        return result
    } catch (IllegalArgumentException e) {
        throw e  // let cursor / arg-validation surface as a validation result; don't reframe as transport failure
    } catch (IllegalStateException e) {
        // Programmer error -- e.g. validFilters / switch drift on the filter whitelist.
        // Don't reframe as a transport failure; surface so the drift gets fixed instead
        // of swallowed under a misleading "hub blip" note.
        mcpLogError("installed-apps", "hub_list_apps internal invariant violated", e)
        throw e
    } catch (Exception e) {
        // The Built-in-App-Tools gate has already passed by the time we reach here, so
        // do not blame it in the note. Failures at this point come from the hub transport
        // (network blip, firmware change to /hub2/appsList) or the flatten/filter pipeline
        // (unexpected shape in a child entry). The error message itself will usually
        // distinguish the two.
        mcpLogError("installed-apps", "hub_list_apps failed", e)
        return [success: false, error: "Failed to list installed apps: ${e.message}", note: "Hub transport error or unexpected /hub2/appsList response shape -- retry; if persistent, inspect the raw response for firmware-side drift."]
    }
}

// getAppsUsingDevice(Long) from the app API, projected like the appsUsing rows. Null when the
// platform lacks it (firmware before 2.5.2) or the call fails.
def _platformAppsUsingDevice(String deviceId) {
    if (!(deviceId ==~ /\d+/)) return null
    try {
        def apps = getAppsUsingDevice(deviceId as Long)
        if (!(apps instanceof List)) return null
        return apps.findAll { it != null }.collect { a ->
            def disabled = null
            try { disabled = a.disabled } catch (Exception ignored) { }
            def trueLabel = null
            try { trueLabel = a.trueLabel } catch (Exception ignored) { }
            [id: a.id, name: a.name, label: a.label, trueLabel: trueLabel, disabled: disabled == true]
        }
    } catch (Exception e) {
        mcpLog(_hubFirmwareBefore("2.5.2") ? "debug" : "warn", "installed-apps", "getAppsUsingDevice(${deviceId}) unavailable: ${e.message}")
        return null
    }
}

def toolGetDeviceInUseBy(args) {
    if (!args?.deviceId) throw new IllegalArgumentException("deviceId is required")
    def deviceId = args.deviceId.toString().trim()
    boolean listed = _requireDeviceToolAccess(deviceId)

    try {
        def responseText = hubInternalGet("/device/fullJson/${deviceId}")
        if (!responseText) {
            return [success: false, error: "Empty response from /device/fullJson/${deviceId}", note: "Device ID may not exist."]
        }
        def parsed
        try {
            parsed = new groovy.json.JsonSlurper().parseText(responseText)
        } catch (Exception parseErr) {
            return [success: false, error: "Failed to parse device JSON: ${parseErr.message}", note: "Device ID '${deviceId}' may not exist, or firmware changed the endpoint format."]
        }

        // Unknown native ids can return parseable JSON with empty appsUsing. Require
        // matching identity for bypass-only access before reporting no dependents.
        if (!(parsed instanceof Map) || (!listed &&
            (!(parsed.device instanceof Map) || parsed.device.id?.toString() != deviceId))) {
            return [success: false, deviceId: deviceId,
                    error: "Device metadata could not be verified for ${deviceId}.",
                    note: "Verify the device exists in the native Devices page and retry."]
        }

        // Defensive shape check: if firmware drifts and appsUsing arrives as a Map
        // (or anything other than List), the collect{} below would produce all-null
        // entries silently. Surface that loud rather than letting the paginator
        // hand the LLM a bag of nulls. _typeName labels the shape (the sandbox returns
        // null for .class on parsed-Map values).
        if (parsed?.appsUsing != null && !(parsed.appsUsing instanceof List)) {
            String shape = _typeName(parsed.appsUsing)
            mcpLog("warn", "installed-apps", "hub_list_device_dependents: appsUsing is ${shape} not List for device ${deviceId} -- treating as empty")
            return [success: false, deviceId: deviceId, error: "Firmware returned unexpected appsUsing shape: ${shape}", note: "Hub firmware may have changed the /device/fullJson endpoint contract."]
        }
        def appsUsing = parsed?.appsUsing ?: []
        def count
        try {
            count = (parsed?.appsUsingCount != null) ? (parsed.appsUsingCount as Integer) : appsUsing.size()
        } catch (NumberFormatException ne) {
            // A non-numeric appsUsingCount is a firmware/paging signal worth recording
            // (e.g. hub returns "42+" for truncated counts); fall back to the list size
            // so the caller still gets a usable count.
            mcpLog("warn", "installed-apps", "hub_list_device_dependents: non-numeric appsUsingCount '${parsed?.appsUsingCount}' for device ${deviceId}; using appsUsing.size()=${appsUsing.size()}")
            count = appsUsing.size()
        }

        def appsUsingList = appsUsing.collect { a ->
            [
                id: a?.id,
                name: a?.name,         // app type name (e.g. "Room Lights", "Rule-5.1")
                label: a?.label,       // user-visible label, may include HTML decoration
                trueLabel: a?.trueLabel,  // label stripped of HTML (null if same as label)
                disabled: a?.disabled == true
            ]
        }
        // The platform's getAppsUsingDevice() (firmware 2.5.2) also reports apps the device page
        // leaves out of appsUsing, such as Easy Mobile Dashboards; add those it alone returns.
        boolean countMismatch = (count != appsUsingList.size())
        def seen = appsUsingList.collect { it.id?.toString() } as Set
        def platformApps = _platformAppsUsingDevice(deviceId)
        def extra = (platformApps ?: []).findAll { !seen.contains(it.id?.toString()) }
        if (extra) {
            appsUsingList.addAll(extra)
            count = Math.max(count as int, appsUsingList.size())
        }
        def cursor = args?.cursor
        def paged = _paginateList(appsUsingList, cursor, 100, "hub_list_device_dependents")
        def result = [
            deviceId: deviceId,
            // extraBreadcrumb is the canonical UI breadcrumb label; fall back to .name or
            // .label if a future firmware drops that field so callers still get a usable
            // device-name string instead of silent null.
            deviceName: parsed?.extraBreadcrumb ?: parsed?.name ?: parsed?.label,
            appsUsing: paged.page,
            count: count
        ]
        // The device page's raw parentApp carries the app type's OAuth secret; keep its identity only.
        def pa = parsed?.parentApp
        if (pa instanceof Map) result.parentApp = [id: pa.id, name: pa.name, label: pa.label, parentAppId: pa.parentAppId]
        if (platformApps == null && !_hubFirmwareBefore("2.5.2")) result.platformLookup = "unavailable"
        // Surface the count/array disparity when firmware reports an appsUsingCount that
        // doesn't match the appsUsing array length (truncation / paging signal), measured
        // before the platform lookup above added its entries.
        if (countMismatch) {
            result.countMismatch = "appsUsingCount=${parsed?.appsUsingCount} but appsUsing array carries ${appsUsing.size()} entries -- firmware may be truncating"
        }
        if (cursor != null) {
            result.total = appsUsingList.size()
            if (paged.nextCursor != null) result.nextCursor = paged.nextCursor
        }
        return result
    } catch (Exception e) {
        mcpLogError("installed-apps", "hub_list_device_dependents failed for device ${deviceId}", e)
        return [success: false, error: "Failed: ${e.message}", note: "Verify the device ID is valid and the Read master is enabled."]
    }
}

def _getAllToolDefinitions_partCodeManagement() {
    // The list builds in a closure so its strings live in the closure's own class: the app class is
    // near the JVM 65,535 constant-pool limit.
    def defs = { [
        // ==================== HUB ADMIN READ TOOLS ====================
        // get_hub_details merged into hub_get_info (core tool)
        [
            name: "hub_list_apps",
            description: """List apps on the hub — running instances or installed app code/types (see scope).[[FLAT_TRIM]] scope='types' returns both community and built-in app types, each tagged system/isBuiltIn and with its menu tab (Apps/Automations/Integrations). Use system=false/isBuiltIn=false to isolate community (user-installed) types, or menu to group types by the admin UI's Apps/Automations/Integrations tabs. menu is null for a type that declares none. See hub_get_tool_guide(section='builtin_app_tools_apps').[[/FLAT_TRIM]] Per-app event history: hub_list_device_events with appId.[[FLAT_TRIM]] Each instance row carries appTypeId, the app type's code-class id shared with scope='types' and hub_get_source(type='app'), so an instance resolves to its code without matching on the type name. Requires the Read master.[[/FLAT_TRIM]]""",
            inputSchema: [
                type: "object",
                properties: [
                    scope: [type: "string", enum: ["instances", "types"], description: "What to list. 'instances' (default) = running app instances with parent/child tree; 'types' = installed app-type catalog (community + built-in), each with system/isBuiltIn and menu (Apps/Automations/Integrations tab, null if undeclared).", default: "instances"],
                    filter: [type: "string", enum: ["all", "builtin", "user", "disabled", "parents", "children"], description: "scope='instances' only: category filter."],
                    nameFilter: [type: "string", description: "Name contains (case-insensitive)."],
                    includeHidden: [type: "boolean", description: "scope='instances' only: include hidden apps (typically Hubitat internal). Default: false", default: false],
                    cursor: [type: "string", description: "Opt-in pagination cursor. Omit for unbounded (subject to 120KB guard); pass \"\" for the first page, iterate nextCursor (page size 50)."]
                ]
            ]
        ],
        [
            name: "hub_list_drivers",
            description: "List device driver types on the hub.[[FLAT_TRIM]] Requires Read master.[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    include: [type: "string", enum: ["user", "all"], description: "Scope: 'user' (default) = user-installed drivers only; 'all' = full catalog (system + virtual + user)."],
                    nameFilter: [type: "string", description: "Name contains (case-insensitive)."],
                    cursor: [type: "string", description: "Opt-in pagination cursor. Omit for unbounded; pass \"\" for the first page, iterate nextCursor (page size 50)."]
                ]
            ]
        ],
        [
            name: "hub_list_libraries",
            description: "List all Groovy libraries installed on the hub (id, name, namespace, version). Use this to discover a library's id, then read its source with hub_get_source(type='library', id=N)[[FLAT_TRIM]]; the source is omitted here to keep the list lean[[/FLAT_TRIM]].[[FLAT_TRIM]] Requires Read master.[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    cursor: [type: "string", description: "Opt-in pagination cursor. Omit for unbounded; pass \"\" for the first page, iterate nextCursor (page size 50)."]
                ]
            ]
        ],
        // Hub Admin App/Driver Source Read Tools
        [
            name: "hub_get_source",
            description: "Get the Groovy source of an installed app, driver, or library. Supports chunked reading (offset/length); large sources are auto-saved to the File Manager for use with the matching update tool's sourceFile mode.[[FLAT_TRIM]] Requires Read master.[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    type: [type: "string", enum: ["app", "driver", "library"], description: "What kind of code to read."],
                    id: [type: "string", description: "App/driver/library ID (positive integer), from hub_list_apps(scope='types') / hub_list_drivers / hub_list_libraries respectively (library id is also in a hub_create_library response or the Hubitat Libraries code page)."],
                    offset: [type: "integer", description: "Char offset to start reading from. Default: 0"],
                    length: [type: "integer", description: "Max chars (default 30000, max 64000)."]
                ],
                required: ["type", "id"]
            ]
        ],
        // Hub Admin App/Driver Management Write Tools
        [
            name: "hub_create_app",
            description: """⚠️ Install new app. Show code to user and get confirmation first.

Supply the code via exactly one of source / sourceFile / importUrl (mutually exclusive; see each param).

[[FLAT_TRIM]]
After the code installs, create a running instance with a SECOND call: hub_create_app(codeAppId: <newAppId>, confirm: true).
[[/FLAT_TRIM]]

The hub compiles on save and returns success=false with its error. Requires Write master + confirm + backup <24h. Returns the new appId and version (codeAppId: the instanceAppId).
[[FLAT_TRIM]]
A transport drop can lose the response while the hub still commits this write; verify current hub state before retrying. See hub_get_tool_guide(section='slow_ops').
[[/FLAT_TRIM]]
""",
            inputSchema: [
                type: "object",
                properties: [
                    source: [type: "string", description: "Inline Groovy source (stubs only)."],
                    sourceFile: [type: "string", description: "File Manager filename (write it first via hub_write_file), e.g. my-app.groovy."],
                    importUrl: [type: "string", description: "URL the hub fetches directly (http/https)."],
                    codeAppId: [type: "integer", description: "Second-step mode: instantiate already-installed code (the appId from a prior call) and commit the install; not combinable with source/sourceFile/importUrl."],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms backup was created and user approved."]
                ],
                required: ["confirm"]
            ]
        ],
        [
            name: "hub_create_driver",
            description: """⚠️ Install new driver. Show code to user and get confirmation first.

Supply the code via exactly one of source / sourceFile / importUrl (mutually exclusive; see each param). Send driver creates one at a time: wait for one to finish before sending the next. For >1 driver use BULK mode (the installs param), which runs the entries in order.

The hub compiles on save and returns success=false with its error. Requires Write master + confirm + backup <24h. Returns the new driverId and version (bulk: one result per entry).
[[FLAT_TRIM]]
MCP 2026-07-28 clients automatically continue this slow write and can replay its retained terminal response. Older clients can still lose the response on a transport drop; verify current hub state before retrying. See hub_get_tool_guide(section='slow_ops').
[[/FLAT_TRIM]]
""",
            inputSchema: [
                type: "object",
                properties: [
                    source: [type: "string", description: "Inline Groovy source (stubs only)."],
                    sourceFile: [type: "string", description: "File Manager filename (write it first via hub_write_file), e.g. my-code.groovy."],
                    importUrl: [type: "string", description: "URL the hub fetches directly (http/https)."],
                    installs: [
                        type: "array",
                        description: "Bulk mode: one round-trip for many drivers. Each entry {source|sourceFile|importUrl}; cannot mix with single-driver fields; continue-on-error.",
                        items: [
                            type: "object",
                            additionalProperties: false,
                            properties: [
                                sourceFile: [type: "string", description: "File Manager filename (preferred)."],
                                source: [type: "string", description: "Inline source (stubs only)."],
                                importUrl: [type: "string", description: "URL the hub fetches directly."]
                            ]
                        ]
                    ],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms backup was created and user approved."]
                ],
                required: ["confirm"]
            ]
        ],
        [
            name: "hub_update_app",
            description: """⚠️ CRITICAL: Modify existing app code. Read current source first, explain changes, get confirmation.

Supply the new code via exactly one of source / sourceFile / importUrl, or resave to recompile in place (see each param). Optionally enable OAuth via the oauth param (apps only), alone or alongside a source change.

Auto-backs up before modifying. Requires Write master + confirm + backup <24h.[[FLAT_TRIM]]

Self-update guard: refuses to overwrite the MCP server's own app source or OAuth unless Developer Mode is on.[[/FLAT_TRIM]]
[[FLAT_TRIM]]
A transport drop can lose the response while the hub still commits this write; verify current hub state before retrying. See hub_get_tool_guide(section='slow_ops').
[[/FLAT_TRIM]]
""",
            inputSchema: [
                type: "object",
                additionalProperties: false,
                properties: [
                    appId: [type: "string", description: "The app ID (Apps Code id) to update"],
                    source: [type: "string", description: "Inline Groovy source (stubs only)."],
                    sourceFile: [type: "string", description: "File Manager filename (write it first via hub_write_file), e.g. my-code.groovy."],
                    importUrl: [type: "string", description: "URL the hub fetches directly (http/https)."],
                    resave: [type: "boolean", description: "Re-save the current source without changes.[[FLAT_TRIM]] Runs entirely on-hub.[[/FLAT_TRIM]]"],
                    backup: [type: "boolean", description: "false: no pre-save backup.[[FLAT_TRIM]] Default true: the current source goes to File Manager first (hub_list_backups scope=source). Never taken in importUrl mode: the URL's publisher keeps the history.[[/FLAT_TRIM]]"],
                    expectedVersion: [type: "integer", description: "OPTIONAL lock: saves only if the code is still at this version, else conflict:true, no write."],
                    triggerUpdated: [type: "integer", description: "OPTIONAL: running instance appId to refresh via updated() after saving, by re-submitting its mainPage Done form from live settings.[[FLAT_TRIM]] Protected targets require Developer Mode; unreadable live settings refuse the submit; on refusal/failure the code stays deployed (success:true, partial:true, updatedFired:false, repairHints). Omit for the editor's plain Save.[[/FLAT_TRIM]]"],
                    oauth: [type: "object", description: "OPTIONAL: enable/configure OAuth on this app (apps only); e.g. {enabled:true}. Full shape: hub_get_tool_guide(section='hub_admin_write_code')."],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms backup was created and user approved."],
                    bestPracticeKey: [type: "string"]
                ],
                required: ["appId", "confirm"]
            ]
        ],
        [
            name: "hub_update_driver",
            description: """⚠️ CRITICAL: Modify existing driver code. Read current source first, explain changes, get confirmation.

Supply the new code via exactly one of source / sourceFile / importUrl, or resave to recompile in place (see each param). For >1 driver use BULK mode (the updates param).

Auto-backs up before modifying. Requires Write master + confirm + backup <24h.
[[FLAT_TRIM]]
MCP 2026-07-28 clients automatically continue this slow write and can replay its retained terminal response. Older clients can still lose the response on a transport drop; verify current hub state before retrying. See hub_get_tool_guide(section='slow_ops').
[[/FLAT_TRIM]]
""",
            inputSchema: [
                type: "object",
                additionalProperties: false,
                properties: [
                    driverId: [type: "string", description: "The driver ID to update (single-driver mode). Omit when using 'updates' array."],
                    source: [type: "string", description: "Inline Groovy source (stubs only)."],
                    sourceFile: [type: "string", description: "File Manager filename (write it first via hub_write_file), e.g. my-code.groovy."],
                    importUrl: [type: "string", description: "URL the hub fetches directly (http/https)."],
                    resave: [type: "boolean", description: "Re-save the current source without changes.[[FLAT_TRIM]] Runs entirely on-hub.[[/FLAT_TRIM]]"],
                    backup: [type: "boolean", description: "false: no pre-save backup.[[FLAT_TRIM]] Default true: the current source goes to File Manager first (hub_list_backups scope=source). Never taken in importUrl mode: the URL's publisher keeps the history.[[/FLAT_TRIM]]"],
                    expectedVersion: [type: "integer", description: "OPTIONAL lock: saves only if the code is still at this version, else conflict:true, no write.[[FLAT_TRIM]] Bulk: inside each updates[] entry.[[/FLAT_TRIM]]"],
                    updates: [
                        type: "array",
                        description: "Bulk mode: one round-trip for many drivers. Each entry {driverId, sourceFile|source|importUrl|resave, optional expectedVersion}; cannot mix with single-driver fields; continue-on-error.",
                        items: [
                            type: "object",
                            additionalProperties: false,
                            properties: [
                                driverId: [type: "string", description: "The driver ID to update"],
                                sourceFile: [type: "string", description: "File Manager filename (preferred)."],
                                source: [type: "string", description: "Inline source (stubs only)."],
                                importUrl: [type: "string", description: "URL the hub fetches directly."],
                                resave: [type: "boolean", description: "Re-save without changes."],
                                backup: [type: "boolean"],
                                expectedVersion: [type: "integer", description: "OPTIONAL lock for this item only."]
                            ],
                            required: ["driverId"]
                        ]
                    ],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms backup was created and user approved."],
                    bestPracticeKey: [type: "string"]
                ],
                required: ["confirm"]
            ]
        ],
        [
            name: "hub_delete_item",
            description: """⚠️ DESTRUCTIVE: Permanently delete an app, driver, or library. Auto-backs up the source before deletion.

[[FLAT_TRIM]]
Pre-flight by type: apps -- remove app instances via the Hubitat UI first; drivers -- switch any devices to a different driver first; libraries -- ensure no apps/drivers still #include it (else compile errors).
[[/FLAT_TRIM]]

Tell the user the item name/ID, warn it's permanent, get confirmation. Requires Write master + confirm + backup <24h.
[[FLAT_TRIM]]
For type=driver, MCP 2026-07-28 clients automatically continue this slow write and can replay its retained terminal response. Other item types and older clients can still lose the response on a transport drop; verify current hub state before retrying. See hub_get_tool_guide(section='slow_ops').
[[/FLAT_TRIM]]
""",
            inputSchema: [
                type: "object",
                properties: [
                    type: [type: "string", enum: ["app", "driver", "library"], description: "What to delete."],
                    item_id: [type: "string", description: "App/driver/library code ID (positive integer), from hub_list_apps(scope='types') / hub_list_drivers / hub_list_libraries respectively."],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms backup was created and user approved."]
                ],
                required: ["type", "item_id", "confirm"]
            ]
        ],
        // Hub Admin Library Management Tools
        [
            name: "hub_create_library",
            description: """⚠️ Install new Groovy library code. Libraries are #include'd by drivers/apps. Show code to user and get confirmation first.

Provide exactly one of source / sourceFile / importUrl (see each param).

Source must include a library() block with name/namespace/author/description (all required). The hub does NOT compile-check libraries at install -- syntax errors surface only when an app/driver #includes it. Requires Write master + confirm + backup <24h. Returns the new libraryId and version.
[[FLAT_TRIM]]
A transport drop can lose the response while the hub still commits this write; verify current hub state before retrying. See hub_get_tool_guide(section='slow_ops').
[[/FLAT_TRIM]]
""",
            inputSchema: [
                type: "object",
                properties: [
                    source: [type: "string", description: "Inline source (stubs only)."],
                    sourceFile: [type: "string", description: "File Manager filename (write it first via hub_write_file), e.g. my-code.groovy."],
                    importUrl: [type: "string", description: "URL the hub fetches directly (http/https)."],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms backup was created and user approved."]
                ],
                required: ["confirm"]
            ]
        ],
        [
            name: "hub_update_library",
            description: """⚠️ CRITICAL: Modify existing library code. Read current source first, explain changes, get confirmation.

Supply the new code via exactly one of source / sourceFile / importUrl, or resave to recompile in place (see each param). Not compile-checked at save (see hub_create_library).

Auto-backs up before modifying. Requires Write master + confirm + backup <24h.
[[FLAT_TRIM]]
A transport drop can lose the response while the hub still commits this write; verify current hub state before retrying. See hub_get_tool_guide(section='slow_ops').
[[/FLAT_TRIM]]
""",
            inputSchema: [
                type: "object",
                additionalProperties: false,
                properties: [
                    libraryId: [type: "string", description: "The library ID to update"],
                    source: [type: "string", description: "Inline source (stubs only)."],
                    sourceFile: [type: "string", description: "File Manager filename (write it first via hub_write_file), e.g. my-code.groovy."],
                    importUrl: [type: "string", description: "URL the hub fetches directly (http/https)."],
                    resave: [type: "boolean", description: "Re-save the current source without changes.[[FLAT_TRIM]] Runs entirely on-hub.[[/FLAT_TRIM]]"],
                    backup: [type: "boolean", description: "false: no pre-save backup.[[FLAT_TRIM]] Default true: the current source goes to File Manager first (hub_list_backups scope=source). Never taken in importUrl mode: the URL's publisher keeps the history.[[/FLAT_TRIM]]"],
                    expectedVersion: [type: "integer", description: "OPTIONAL lock: saves only if the code is still at this version, else conflict:true, no write."],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms backup was created and user approved."],
                    bestPracticeKey: [type: "string"]
                ],
                required: ["libraryId", "confirm"]
            ]
        ],
        // Hub Admin App Configuration Read (grouped with installed-apps peers)
        [
            name: "hub_get_app_config",
            description: """Read an installed classic app's page as the Hubitat UI renders it: the app's identity, the page's sections with each input as hub_set_native_app(operation:'form') returns it, and its child apps and devices. The pages it links to are listed in page.hrefs. controls lists the page's buttons and links with the control_id that hub_set_native_app or hub_set_rule(operation:'form') activates, and pageNote says when the hub rendered another page than pageName. Secret values are redacted unless Hide passwords and credentials is off (always for a per-client token).""",
            inputSchema: [
                type: "object",
                properties: [
                    appId: [type: "string", description: "Installed-app ID (decimal). From hub_list_apps (scope='instances'), hub_list_rules, or the numeric id in the Hubitat UI URL (/installedapp/configure/<id>)."],
                    pageName: [type: "string", description: "The page to read instead of the default page: a page.hrefs[].page name from a page of this app already read."],
                    includeSettings: [type: "boolean", description: "Include the raw app-internal settings key-value map (default false). Set true only for power-user inspection.", default: false],
                    summary: [type: "boolean", description: "Fast identity-only read: returns the thin app record (id, name, type, disabled, user) and the type's namespace, no config page; pageName/includeSettings ignored.", default: false]
                ],
                required: ["appId"]
            ]
        ],
        // Installed Apps Integration (built-in + user app visibility)
        [
            name: "hub_list_device_dependents",
            description: """List all apps that reference a specific device[[FLAT_TRIM]] (Room Lighting instances, Rule Machine rules, Groups and Scenes, Mode Manager, dashboards, Maker API, Echo Skill, etc.)[[/FLAT_TRIM]] — critical before device cleanup, troubleshooting, or reassignment.[[FLAT_TRIM]] Requires the Read master.[[/FLAT_TRIM]]""",
            inputSchema: [
                type: "object",
                properties: [
                    deviceId: [type: "string", description: "Device ID from hub_list_devices"],
                    cursor: [type: "string", description: "Opt-in pagination cursor for the appsUsing list. Omit for unbounded; pass \"\" for the first page, iterate nextCursor (page size 100)."]
                ],
                required: ["deviceId"]
            ]
        ],
    ] }
    return defs()
}

def _readOnlyToolNames_partCodeManagement() {
    // Read-only classification membership for this library's tools, contributed to the
    // app's getReadOnlyToolNames() aggregator (issue #209: per-tool metadata lives with
    // the tool). A tool absent from every part list is write+destructive by default.
    return [
        // Apps/drivers (read)
        "hub_list_apps", "hub_list_drivers", "hub_list_libraries", "hub_get_source",
        // Installed apps (read) -- instances list folded into hub_list_apps(scope='instances').
        "hub_list_device_dependents", "hub_get_app_config"
    ]
}

def _idempotentWriteToolNames_partCodeManagement() {
    // Retry-safe writes (MCP idempotentHint) for this library's tools -- contributed to the
    // app's getIdempotentWriteToolNames() aggregator; see the classification rules there.
    return [
        // Code (apps/drivers/libraries/bundles/backups)
        "hub_update_app", "hub_update_driver", "hub_update_library", "hub_delete_item"
    ]
}

def _openWorldToolNames_partCodeManagement() {
    // Tools in this library that reach BEYOND the hub to the open internet (MCP
    // openWorldHint) -- contributed to the app's getOpenWorldToolNames() aggregator.
    return [
        "hub_create_app", "hub_create_driver", "hub_create_library", "hub_update_app", "hub_update_driver", "hub_update_library"
    ]
}

def _toolDisplayMeta_partCodeManagement() {
    // Human-facing title/summary per tool (MCP annotations.title + the Advanced per-tool
    // overrides menu) -- merged into the app's getToolDisplayMeta() aggregator (issue #209).
    return [
        // Apps / drivers / libraries / backups / HPM
        hub_list_apps: [title: "List Apps", summary: "List installed app types or enumerate app instances."],
        hub_list_drivers: [title: "List Drivers", summary: "List all installed drivers."],
        hub_list_libraries: [title: "List Libraries", summary: "List installed Groovy libraries."],
        hub_get_source: [title: "Get Source Code", summary: "Read Groovy source for an app, driver, or library."],
        hub_create_app: [title: "Install App", summary: "Install a new app from source, a File Manager file, or a URL, or instantiate installed app code."],
        hub_create_driver: [title: "Install Driver", summary: "Install one or more drivers from source, File Manager files, or a URL."],
        hub_update_app: [title: "Update App Code", summary: "Modify an existing app's source code, or enable/configure its OAuth (client id/secret)."],
        hub_update_driver: [title: "Update Driver Code", summary: "Modify an existing driver's source code."],
        hub_create_library: [title: "Install Library", summary: "Install a new Groovy library."],
        hub_update_library: [title: "Update Library Code", summary: "Modify an existing library's source code."],
        hub_delete_item: [title: "Delete Code Item", summary: "Permanently delete an app, driver, or library (auto-backs up first)."],
        hub_list_device_dependents: [title: "List Device Dependents", summary: "Find all apps that reference a device."],
        hub_get_app_config: [title: "Get App Configuration", summary: "Read an installed app's configuration page."]
    ]
}
