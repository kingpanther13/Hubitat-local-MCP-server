library(name: "McpCodeManagementLib", namespace: "mcp", author: "kingpanther13", description: "App/driver/library code management tool implementations (list/source/install/update/delete, app config + pages, device dependents) for the MCP Rule Server; #include'd by the main app. Gateway entries and dispatch cases stay in the app; tool definitions, implementations, domain helpers, and per-tool metadata live here.")

def toolListHubApps(args) {

    def cursor = args?.cursor
    def result = [:]
    try {
        def responseText = hubInternalGet("/hub2/userAppTypes")
        if (responseText) {
            try {
                def parsed = new groovy.json.JsonSlurper().parseText(responseText)
                result.apps = parsed
                result.count = parsed instanceof List ? parsed.size() : 0
                result.source = "hub_api"
            } catch (Exception parseErr) {
                // Response was not JSON - return what we can. apps=[] keeps the cursor
                // block + downstream shape consistent so a paginating caller doesn't
                // silently lose the pagination contract on a firmware shape drift.
                result.apps = []
                result.rawResponse = responseText?.take(2000)
                result.source = "hub_api_raw"
                result.note = "Response was not JSON. This endpoint may return HTML on your firmware version."
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

def toolListHubDrivers(args) {

    def cursor = args?.cursor
    def result = [:]
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
    def requestedLength = args.length ? Math.min(args.length as int, maxChunkSize) : maxChunkSize

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
            result.sourceFileHint = "Full source saved to File Manager. Use update_${type}_code with sourceFile: '${savedToFile}' to update without cloud size limits."
        }
        return result
    } catch (Exception e) {
        mcpLogError("hub-admin", "Failed to get ${type} source", e)
        return [success: false, error: "Failed to get ${type} source: ${e.message}"]
    }
}

private String stripAppConfigHtml(value) {
    if (value == null) return null
    def s = value.toString()
    // Strip HTML tags, then any leftover CSS-rule / inline-script bodies that
    // Hubitat embeds via <style>/<script>: the tags strip above but the
    // "selector{...}" / "fn(){...}" bodies remain mashed into the text (e.g. the
    // Local Variables `lvTable` page). Only blocks containing ; or : inside the
    // braces are removed, so prose like "{x}" is preserved.
    if (s.contains("<")) {
        s = s.replaceAll(/<[^>]+>/, "").replaceAll(/[^{}]*\{[^{}]*[;:][^{}]*\}/, "")
    }
    // Decode the common HTML entities Hubitat escapes user-typed names with: a
    // rule the user named "Heat On <67" is stored (and listed) as "Heat On &lt;67".
    // Decode &amp; LAST so a single-encoded "&lt;" resolves correctly.
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
                entry.each { k, v -> cleaned[k] = (v instanceof String) ? stripAppConfigHtml(v) : v }
                out << cleaned
            } else {
                out << entry
            }
        }
        return out
    }
    if (options instanceof Map) {
        def cleaned = [:]
        options.each { k, v -> cleaned[k] = (v instanceof String) ? stripAppConfigHtml(v) : v }
        return cleaned
    }
    return options
}

private List _extractEmbeddedActions(String html) {
    if (!html) return []
    def buttonNames = []
    def im = (html =~ /<input\s+type=['"]hidden['"]\s+name=['"]([^'"]+?)\.type['"]\s+value=['"]button['"][^>]*>/)
    while (im.find()) {
        buttonNames << im.group(1)
    }
    def divs = []
    def dm = (html =~ /(?s)<div([^>]*)class=['"]submitOnChange['"]([^>]*)>(.*?)<\/div>/)
    while (dm.find()) {
        def attrs = (dm.group(1) ?: "") + (dm.group(2) ?: "")
        def innerRaw = dm.group(3) ?: ""
        def title = null
        def state = null
        def tm = attrs =~ /title=['"]([^'"]+?)['"]/
        if (tm.find()) title = tm.group(1)
        def sm = attrs =~ /data-stateAttribute=['"]([^'"]+?)['"]/
        if (sm.find()) state = sm.group(1)
        def inner = innerRaw.replaceAll(/<[^>]+>/, "").replaceAll(/&nbsp;|&#65291|&#x[0-9a-fA-F]+;|&#\d+;/, "").trim()
        divs << [title: title, stateAttribute: state, description: inner ?: null]
    }
    if (!buttonNames || !divs) return []
    def actions = []
    int n = Math.min(buttonNames.size(), divs.size())
    for (int i = 0; i < n; i++) {
        def a = [name: buttonNames[i]]
        def d = divs[i]
        if (d.title) a.title = d.title
        if (d.stateAttribute) a.stateAttribute = d.stateAttribute
        if (d.description) a.description = d.description
        actions << a
    }
    return actions
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

    // Identity-only fast path: /installedapp/json/<id> is the thin per-app record
    // (no configPage render), so it stays cheap even on apps whose config page is
    // expensive to build (Room Lighting, RM 5.1). Fields pass through as-is so
    // firmware additions surface without a server update.
    if (args?.summary == true) {
        def summaryPath = "/installedapp/json/${appIdStr}"
        mcpLog("info", "hub-admin", "hub_get_app_config appId=${appIdStr} summary mode")
        def summaryText
        try {
            summaryText = hubInternalGet(summaryPath, null, 30)
        } catch (Exception e) {
            // Duck-type the HTTP status off the exception (HttpResponseException NCDFEs at parse
            // time on the test classpath, so read e.response.status defensively). A 404/410 on the
            // thin identity record means the app is gone (deleted/mid-delete) -- degrade to a clean
            // not-found at warn, not an opaque ERROR.
            def resp = null
            try { resp = e.response } catch (Exception ig) { resp = null }
            Integer st = null
            try { st = resp?.status as Integer } catch (Exception ig) { st = null }
            if (st == 404 || st == 410) {
                mcpLog("warn", "hub-admin", "hub_get_app_config app ${appIdStr} not found (HTTP ${st} from ${summaryPath})")
                return [success: false, error: "App ${appIdStr} not found (HTTP ${st} from ${summaryPath}). May be deleted, mid-delete, or a not-yet-committed install shell whose config page cannot render. Verify with hub_get_app_config(summary=true) or hub_list_apps.", appId: appIdStr as Integer, fingerprint: 'app not found (404)', status: st]
            }
            mcpLogError("hub-admin", "hub_get_app_config summary fetch failed", e)
            return [success: false, error: "Failed to fetch app summary [${e.class.simpleName}]: ${e.message}", appId: appIdStr as Integer]
        }
        if (!summaryText) {
            return [success: false, error: "Empty response from ${summaryPath}. App may not exist or hub internal API is unavailable.", appId: appIdStr as Integer]
        }
        def summaryParsed
        try {
            summaryParsed = new groovy.json.JsonSlurper().parseText(summaryText)
        } catch (Exception e) {
            mcpLogError("hub-admin", "hub_get_app_config summary JSON parse failed", e)
            return [success: false, error: "Failed to parse app summary JSON: ${e.message}. Hubitat firmware may have changed the endpoint contract.", appId: appIdStr as Integer]
        }
        if (!(summaryParsed instanceof Map)) {
            return [success: false, error: "Unexpected response shape: expected a JSON object. Firmware may have changed the endpoint contract.", appId: appIdStr as Integer, fingerprint: "top-level not a Map"]
        }
        def summaryResult = [:]
        summaryResult.putAll(summaryParsed)
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
        // Duck-type the HTTP status off the exception (HttpResponseException NCDFEs at parse
        // time on the test classpath, so read e.response.status defensively). A 404/410 here is
        // benign: the configure page can't render for a deleted, mid-delete, or not-yet-committed
        // install shell -- degrade to a clean not-found at warn, not an opaque ERROR.
        def resp = null
        try { resp = e.response } catch (Exception ig) { resp = null }
        Integer st = null
        try { st = resp?.status as Integer } catch (Exception ig) { st = null }
        if (st == 404 || st == 410) {
            mcpLog("warn", "hub-admin", "hub_get_app_config app ${appIdStr} not found (HTTP ${st} from ${path})")
            return [success: false, error: "App ${appIdStr} not found (HTTP ${st} from ${path}). May be deleted, mid-delete, or a not-yet-committed install shell whose config page cannot render. Verify with hub_get_app_config(summary=true) or hub_list_apps.", appId: appIdStr as Integer, fingerprint: 'app not found (404)', status: st]
        }
        mcpLogError("hub-admin", "hub_get_app_config fetch failed", e)
        return [success: false, error: "Failed to fetch app config [${e.class.simpleName}]: ${e.message}", appId: appIdStr as Integer]
    }

    if (!responseText) {
        return [success: false, error: "Empty response from ${path}. App may not exist or hub internal API is unavailable.", appId: appIdStr as Integer]
    }

    def parsed
    try {
        parsed = new groovy.json.JsonSlurper().parseText(responseText)
    } catch (Exception e) {
        mcpLogError("hub-admin", "hub_get_app_config JSON parse failed", e)
        return [success: false, error: "Failed to parse app config JSON: ${e.message}. Hubitat firmware may have changed the endpoint contract.", appId: appIdStr as Integer]
    }

    // Runtime fingerprint: confirm the SDK-level shape this tool depends on.
    // The /installedapp/configure/json/<id> endpoint returns {app, configPage, settings, childApps, ...}
    // consistently across every legacy SmartApp. If any of these top-level invariants are
    // missing, the firmware likely changed the contract — fail explicitly rather than
    // returning malformed data.
    if (!(parsed instanceof Map)) {
        return [success: false, error: "Unexpected response shape: expected a JSON object. Firmware may have changed the endpoint contract.", appId: appIdStr as Integer, fingerprint: "top-level not a Map"]
    }
    if (!(parsed.app instanceof Map)) {
        return [success: false, error: "Unexpected response shape: missing 'app' object. Firmware may have changed the endpoint contract.", appId: appIdStr as Integer, fingerprint: "missing app"]
    }
    if (!(parsed.configPage instanceof Map)) {
        return [success: false, error: "Unexpected response shape: missing 'configPage' object. Firmware may have changed the endpoint contract.", appId: appIdStr as Integer, fingerprint: "missing configPage"]
    }
    if (!(parsed.configPage.sections instanceof List)) {
        return [success: false, error: "Unexpected response shape: configPage.sections is not a list. This page may be a dynamic redirect or action-only page (common in HPM multi-step flows). Try a different pageName -- call hub_list_app_pages for this app, or `hub_get_tool_guide(section='builtin_app_tools')` for common multi-page app names.", appId: appIdStr as Integer, fingerprint: "sections not a list"]
    }

    // Hub returns app.appType as a ~30-key metadata object (author, classLocation,
    // createTime, deprecated, etc.). Extract only the useful fields — the rest is
    // either internal SDK state or duplicates what's already on app itself.
    def appTypeRaw = parsed.app.appType
    def appTypeSummary = null
    if (appTypeRaw instanceof Map) {
        appTypeSummary = [
            name: appTypeRaw.name,
            namespace: appTypeRaw.namespace,
            author: appTypeRaw.author,
            category: appTypeRaw.category,
            classLocation: appTypeRaw.classLocation,
            deprecated: appTypeRaw.deprecated == true,
            system: appTypeRaw.system == true,
            documentationLink: appTypeRaw.documentationLink
        ]
    }

    def appObj = [
        id: parsed.app.id,
        label: stripAppConfigHtml(parsed.app.trueLabel ?: parsed.app.label),
        name: parsed.app.name,
        appType: appTypeSummary,
        disabled: parsed.app.disabled == true,
        parentAppId: parsed.app.parentAppId,
        installed: parsed.app.installed == true
    ]

    // Redact type="password" input values: the Hubitat UI masks these inputs, so this
    // tool must not de-mask the stored secret into the MCP response. Password input names
    // are collected here so the includeSettings settings map below can redact the same
    // keys. Per-page only: a password on a different page of a multi-page app is not
    // visible in this configPage (broadening to a name-pattern heuristic is a deliberate
    // future option, intentionally not done here -- see settingsRedactionNote below).
    def redactedPw = "***redacted (password)***"
    def passwordInputNames = [] as Set
    def sections = []
    for (s in parsed.configPage.sections) {
        if (!(s instanceof Map)) continue
        def section = [
            title: stripAppConfigHtml(s.title),
            inputs: []
        ]
        for (i in (s.input ?: [])) {
            if (!(i instanceof Map)) continue
            def input = [
                name: i.name,
                type: i.type,
                title: stripAppConfigHtml(i.title)
            ]
            if (i.multiple == true) input.multiple = true
            if (i.required == true) input.required = true
            def desc = stripAppConfigHtml(i.description)
            if (desc && desc != "Click to set") input.description = desc
            if (i.options) input.options = stripOptionsHtml(i.options)
            // Current values: 'defaultValue' is the rendered value for most input types
            // (despite the misleading name), 'value' is used on some. Include whichever
            // is non-null and not the boolean 'true' sentinel.
            //
            // The boolean 'true' sentinel: Hubitat's legacy SmartApp SDK populates
            // defaultValue=true on capability.* and device-list input types (e.g.
            // type="capability.*", type="device.switch") to indicate "this input has a
            // configured value" rather than encoding the actual selection as defaultValue.
            // For those types the actual selected device label appears separately in the
            // rendered description. Excluding defaultValue==true prevents emitting a bare
            // true into the value field for every populated device-picker input.
            //
            // Exception for type="bool": boolean (checkbox) inputs use true/false as their
            // actual user-configured state values -- not as sentinel markers. The filter
            // is bypassed when i.type == "bool" so that both true (enabled) and false
            // (disabled) checkbox states are preserved in the output. Without this bypass,
            // enabled checkboxes (value==true) would be silently dropped, making the AI
            // believe the setting is unconfigured when it is actually set to enabled.
            // (observed: firmware 2.3.x-2.4.x; sentinel confirmed on capability.* types)
            if (i.defaultValue != null && (i.defaultValue != true || i.type == "bool")) input.value = i.defaultValue
            else if (i.value != null && (i.value != true || i.type == "bool")) input.value = i.value
            if (i.type == "password") {
                if (i.name) passwordInputNames << i.name.toString()
                if (input.containsKey("value")) input.value = redactedPw
            }
            section.inputs << input
        }
        // Paragraph/body content (informational text in the config page). Keep any
        // non-"Click to set" string — including short labels like "Enabled" / "Warning!"
        // that the SDK emits as standalone body paragraphs.
        //
        // RM 5.1 (and other classic SmartApps that drive multi-step wizards) embed
        // clickable affordances inside paragraph HTML rather than emitting them as
        // structured `input.type=button` entries — e.g. on the selectTriggers page,
        // "Create New Trigger Event" is a `<div class='submitOnChange'>` with a
        // `data-stateAttribute='moreCond'` and a sibling `<input type='hidden'
        // name='true.type' value='button'>` that identifies the button name. Without
        // extraction, a tool-only LLM sees only the stripped text "Create New Trigger
        // Event" with no way to discover the button name or stateAttribute. Extract
        // these into a structured `embeddedActions` field so the wizard buttons are
        // first-class data on every paragraph-bearing page.
        def paragraphs = []
        def embeddedActions = []
        for (b in (s.body ?: [])) {
            if (!(b instanceof Map)) continue
            def rawHtml = (b.description ?: b.title)?.toString()
            def text = stripAppConfigHtml(rawHtml)
            if (text && text != "Click to set") paragraphs << text
            if (rawHtml) {
                def acts = _extractEmbeddedActions(rawHtml)
                if (acts) embeddedActions.addAll(acts)
            }
        }
        if (paragraphs) section.paragraphs = paragraphs
        if (embeddedActions) section.embeddedActions = embeddedActions
        sections << section
    }

    def children = []
    for (c in (parsed.childApps ?: [])) {
        if (!(c instanceof Map)) continue
        children << [id: c.id, label: stripAppConfigHtml(c.label ?: c.name), name: c.name]
    }

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
        endpoint: path
    ]

    int settingsCount = (parsed.settings instanceof Map) ? parsed.settings.size() : 0
    result.settingsKeyCount = settingsCount
    if (includeSettings) {
        def rawSettings = (parsed.settings instanceof Map) ? parsed.settings : [:]
        result.settings = passwordInputNames ? rawSettings.collectEntries { k, v ->
            [(k): (passwordInputNames.contains(k?.toString()) ? redactedPw : v)]
        } : rawSettings
        if (rawSettings) {
            result.settingsRedactionNote = "Password-type values are redacted using this page's inputs. For a multi-page app, password values defined on OTHER pages are not detectable from this fetch and may appear here unredacted -- redaction is page-scoped (see hub_list_app_pages)."
        }
    } else if (settingsCount > 0) {
        result.settingsNote = "Raw settings omitted -- pass includeSettings=true to include. Large apps (Room Lighting, RM 5.1) may have 500-1000 keys with app-specific encoding (e.g. \"dm~<deviceId>~<scene>\" for Room Lighting dim presets) that is non-trivial to decode without app-specific knowledge."
    }

    return result
}

def toolListAppPages(args) {

    if (args?.appId == null || args.appId.toString().trim() == "") {
        throw new IllegalArgumentException("appId is required")
    }
    def appIdStr = args.appId.toString().trim()
    if (!appIdStr.isInteger()) {
        throw new IllegalArgumentException("appId must be numeric: ${appIdStr}")
    }

    mcpLog("info", "hub-admin", "hub_list_app_pages appId=${appIdStr}")

    def path = "/installedapp/configure/json/${appIdStr}"
    def responseText
    try {
        responseText = hubInternalGet(path, null, 30)
    } catch (Exception e) {
        // Duck-type the HTTP status off the exception (HttpResponseException NCDFEs at parse
        // time on the test classpath, so read e.response.status defensively). A 404/410 here is
        // benign: the configure page can't render for a deleted, mid-delete, or not-yet-committed
        // install shell -- degrade to a clean not-found at warn, not an opaque ERROR.
        def resp = null
        try { resp = e.response } catch (Exception ig) { resp = null }
        Integer st = null
        try { st = resp?.status as Integer } catch (Exception ig) { st = null }
        if (st == 404 || st == 410) {
            mcpLog("warn", "hub-admin", "hub_list_app_pages app ${appIdStr} not found (HTTP ${st} from ${path})")
            return [success: false, error: "App ${appIdStr} not found (HTTP ${st} from ${path}). May be deleted, mid-delete, or a not-yet-committed install shell whose config page cannot render. Verify with hub_get_app_config(summary=true) or hub_list_apps.", appId: appIdStr as Integer, fingerprint: 'app not found (404)', status: st]
        }
        mcpLogError("hub-admin", "hub_list_app_pages fetch failed", e)
        return [success: false, error: "Failed to fetch app config [${e.class.simpleName}]: ${e.message}", appId: appIdStr as Integer]
    }

    if (!responseText) {
        return [success: false, error: "Empty response from ${path}. App may not exist or hub internal API is unavailable.", appId: appIdStr as Integer]
    }

    def parsed
    try {
        parsed = new groovy.json.JsonSlurper().parseText(responseText)
    } catch (Exception e) {
        mcpLogError("hub-admin", "hub_list_app_pages JSON parse failed", e)
        return [success: false, error: "Failed to parse app config JSON: ${e.message}. Hubitat firmware may have changed the endpoint contract.", appId: appIdStr as Integer]
    }

    if (!(parsed instanceof Map)) {
        return [success: false, error: "Unexpected response shape: expected a JSON object. Firmware may have changed the endpoint contract.", appId: appIdStr as Integer, fingerprint: "top-level not a Map"]
    }
    if (!(parsed.app instanceof Map)) {
        return [success: false, error: "Unexpected response shape: missing 'app' object. Firmware may have changed the endpoint contract.", appId: appIdStr as Integer, fingerprint: "missing app"]
    }
    if (!(parsed.configPage instanceof Map)) {
        return [success: false, error: "Unexpected response shape: missing 'configPage' object. Firmware may have changed the endpoint contract.", appId: appIdStr as Integer, fingerprint: "missing configPage"]
    }

    def appTypeRaw = parsed.app.appType
    def appTypeName = (appTypeRaw instanceof Map) ? (appTypeRaw.name ?: "") : ""
    def appLabel = stripAppConfigHtml(parsed.app.trueLabel ?: parsed.app.label) ?: ""

    // Primary page: introspected from the hub response (configPage guaranteed Map by fingerprint check above)
    def primaryPageName = parsed.configPage.name ?: "mainPage"
    def primaryPageTitle = stripAppConfigHtml(parsed.configPage.title)
    def primaryPage = [name: primaryPageName, title: primaryPageTitle, role: "primary"]

    def appObj = [
        id: parsed.app.id,
        label: appLabel,
        name: parsed.app.name,
        appTypeName: appTypeName
    ]

    // Curated directory dispatch -- case-insensitive substring matching for robustness.
    def appTypeNameLower = appTypeName.toLowerCase()
    def pages
    def note = null

    if (appTypeNameLower.contains("hubitat package manager")) {
        pages = [
            [name: "prefOptions",    title: "Main Menu",                role: "navigation"],
            [name: "prefPkgUninstall", title: "Uninstall / Full Package List", role: "full_package_list"],
            [name: "prefPkgModify",  title: "Modify Package (optional-components subset)", role: "modifiable_subset"],
            [name: "prefPkgInstall", title: "Install New Package",       role: "install_flow"],
            [name: "prefPkgMatchUp", title: "Match Up Packages",         role: "matching_flow"]
        ]
    } else if (appTypeNameLower.contains("rule-5") || appTypeNameLower.contains("rule machine")) {
        pages = [
            [name: "mainPage", title: appLabel ?: "Rule Settings", role: "primary"]
        ]
        note = "Rule Machine rules are single-page. No sub-pages available."
    } else if (appTypeNameLower.contains("room lights") || appTypeNameLower.contains("room lighting")) {
        pages = [
            [name: "mainPage", title: appLabel ?: "Room Lighting Settings", role: "primary"]
        ]
        note = "Room Lighting instances use a single mainPage. No named sub-pages."
    } else if (appTypeNameLower.contains("mode manager")) {
        pages = [
            [name: "mainPage", title: "Manage Setting of Modes", role: "primary"]
        ]
        note = "Mode Manager uses a single mainPage. No named sub-pages."
    } else {
        pages = [primaryPage]
        note = "App-type-specific page directory not curated; only primary page known. For multi-page apps, consult the app's Groovy source or the Web UI navigation for sub-page names."
    }

    return [
        success: true,
        app: appObj,
        primaryPage: primaryPage,
        pages: pages,
        note: note
    ]
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

private Map _createUserAppInstance(Integer codeAppId) {
    def result
    try {
        result = hubInternalGetRaw("/installedapp/create/${codeAppId}")
    } catch (Exception e) {
        mcpLog("error", "hub-admin", "_createUserAppInstance(${codeAppId}): ${e.toString()}")
        return [
            success: false,
            error: "User app instantiation failed: ${e.toString()}",
            codeAppId: codeAppId
        ]
    }
    if (result?.status != 302 || !result.location) {
        def status = result?.status
        def hint
        switch (status) {
            case 401:
            case 403:
                hint = "Hub Security authentication failed (status ${status}). Verify MCP credentials and Hub Security state."
                break
            case 404:
                hint = "codeAppId ${codeAppId} does not exist in Apps Code."
                break
            case 500:
                hint = "Hub returned a server error (status 500). Code ${codeAppId} may be in a compile-error state -- inspect via hub_get_source(type='app')."
                break
            case 200:
                hint = "Hub returned the configure page without redirect (status 200). codeAppId ${codeAppId} may be a driver/library, or an already-instantiated app."
                break
            default:
                hint = "Hub returned unexpected status ${status}. The codeAppId may not exist or may not be a user app."
        }
        mcpLog("warn", "hub-admin", "_createUserAppInstance(${codeAppId}): non-302 (status=${status})")
        return [
            success: false,
            error: hint,
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

private Map _commitUserAppInstall(Integer instanceId, String pageName) {
    def cfg = _rmFetchConfigJson(instanceId, pageName)
    def page = pageName ?: (cfg?.configPage?.name?.toString()) ?: "mainPage"
    def schema = _rmCollectInputSchema(cfg?.configPage)
    // The UI's Done submits each input's rendered value -- its default on a fresh
    // shell (e.g. a bool's "true"/"false"). Sending "" for a typed input, notably
    // a bool, makes the hub's update handler 500. Read each value straight from the
    // configPage inputs (value, else defaultValue).
    def pageValues = [:]
    for (s in (cfg?.configPage?.sections ?: [])) {
        for (i in (s?.input ?: [])) {
            if (i instanceof Map && i.name) {
                def nm = i.name.toString()
                if (i.value != null) pageValues[nm] = i.value
                else if (i.defaultValue != null) pageValues[nm] = i.defaultValue
            }
        }
    }
    def settingsMap = [:]
    schema.each { name, meta ->
        settingsMap[name] = pageValues.containsKey(name) ? pageValues[name] : ""
    }
    def body = _rmBuildSettingsBody(instanceId, settingsMap, schema)
    body.formAction = "update"
    body.currentPage = page
    body._action_update = "Done"
    body.pageBreadcrumbs = "[]"
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
    // Fields the classic "Done" form also submits. _rmSubmitMainPageDone (RM)
    // omits them and gets away with it because RM commits via updateRule first
    // and tolerates a failing Done; a standalone app's Done is the ONLY commit,
    // and the hub's update handler 500s without these (verified live). The UI's
    // referrer/url fields are navigation hints only and are NOT required.
    body.appTypeId = ""
    body.appTypeName = ""
    body._cancellable = "false"

    def resp = hubInternalPostForm("/installedapp/update/json", body)
    def st = resp?.status
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
    if (installedConfirmed == null) {
        out.note = "Install commit submitted but app.installed could not be independently confirmed (config read failed); scheduledJobCount/eventSubscriptionCount are the lifecycle evidence."
    }
    return out
}

def toolInstallApp(args) {
    if (args.installs != null) {
        throw new IllegalArgumentException("Bulk mode ('installs' array) is not supported for hub_create_app. Apps do not cluster the way drivers do; install each app individually.")
    }
    requireDestructiveConfirm(args.confirm)

    // installAsUserApp mode: create a running instance from already-installed code.
    // Closes the "hub_create_app installs code but not an instance" gap. Mutually
    // exclusive with code-install args -- callers can't combine the two in one
    // call (avoids confusion about which appId the response refers to).
    if (args.installAsUserApp != null) {
        if (args.source || args.sourceFile || args.importUrl) {
            throw new IllegalArgumentException(
                "installAsUserApp is mutually exclusive with source/sourceFile/importUrl. " +
                "Use two calls: (1) hub_create_app(source|sourceFile|importUrl, confirm:true) " +
                "to install the code, then " +
                "(2) hub_create_app(installAsUserApp:<codeAppId>, confirm:true) " +
                "to create the running instance."
            )
        }
        def codeAppId
        try { codeAppId = args.installAsUserApp as Integer }
        catch (Exception e) {
            throw new IllegalArgumentException("installAsUserApp must be a positive integer (got '${args.installAsUserApp}')")
        }
        // Range-check at the caller so _createUserAppInstance can keep a clean
        // "always returns an envelope, never throws" contract.
        if (codeAppId == null || codeAppId < 1) {
            throw new IllegalArgumentException("installAsUserApp must be a positive integer (got '${args.installAsUserApp}')")
        }
        return _createUserAppInstance(codeAppId)
    }

    return toolInstallItemSingle("app", args)
}

def toolInstallDriver(args) {
    return toolInstallItem("driver", args)
}

private Map toolInstallItem(String type, args) {
    requireDestructiveConfirm(args.confirm)

    def idField = (type == "app") ? "appId" : "driverId"

    // Bulk mode: installs array present -- validate then dispatch per-item
    if (args.installs != null) {
        if (args.source != null || args.sourceFile != null || args.importUrl != null) {
            throw new IllegalArgumentException("Cannot supply both 'installs' (bulk mode) and single-item fields (source/sourceFile/importUrl). Use one mode or the other.")
        }
        if (!(args.installs instanceof List)) {
            throw new IllegalArgumentException("'installs' must be an array of objects, each with 'source', 'sourceFile', or 'importUrl'.")
        }
        if (args.installs.isEmpty()) {
            throw new IllegalArgumentException("'installs' array must not be empty.")
        }

        // Bulk mode: apply each install in sequence, continue on per-item errors
        def itemResults = []
        def allSucceeded = true
        args.installs.eachWithIndex { item, idx ->
            if (!(item instanceof Map) || (item.source == null && item.sourceFile == null && item.importUrl == null)) {
                itemResults << [(idField): null, success: false, error: "Each installs entry must have a 'source', 'sourceFile', or 'importUrl' field."]
                allSucceeded = false
                return
            }
            try {
                def singleArgs = [confirm: args.confirm]
                if (item.sourceFile != null) singleArgs.sourceFile = item.sourceFile
                if (item.source != null) singleArgs.source = item.source
                if (item.importUrl != null) singleArgs.importUrl = item.importUrl
                def r = toolInstallItemSingle(type, singleArgs)
                def entry = [(idField): r[idField], success: r.success == true]
                if (r.success) {
                    if (r.sourceMode) entry.sourceMode = r.sourceMode
                    if (r.sourceLength != null) entry.sourceLength = r.sourceLength
                    if (r.verified != null) entry.verified = r.verified
                    if (r.verifyError) entry.verifyError = r.verifyError
                } else {
                    entry.error = r.error ?: "Install failed"
                    if (r.note) entry.note = r.note
                    if (r.lastBackup) entry.lastBackup = r.lastBackup
                    allSucceeded = false
                }
                itemResults << entry
            } catch (Exception e) {
                mcpLogError("hub-admin", "Bulk install_${type} item ${idx} threw", e)
                itemResults << [(idField): null, success: false, error: e.message ?: e.toString(), errorClass: e.class.simpleName]
                allSucceeded = false
            }
        }

        def successCount = itemResults.count { it.success == true }
        mcpLog("info", "hub-admin", "Bulk install_${type}: ${successCount}/${itemResults.size()} succeeded")
        return [
            success: allSucceeded,
            message: allSucceeded ? "All ${itemResults.size()} ${type}(s) installed successfully." : "${successCount} of ${itemResults.size()} ${type}(s) installed successfully.",
            installs: itemResults,
            lastBackup: formatTimestamp(state.lastBackupTimestamp)
        ]
    }

    // Single-item mode: delegate to single-item helper
    return toolInstallItemSingle(type, args)
}

// Caller must have already invoked requireDestructiveConfirm -- gate fires once per call, not per bulk item.
private Map toolInstallItemSingle(String type, args) {
    def idField = (type == "app") ? "appId" : "driverId"

    // Resolve source: exactly one of sourceFile / source / importUrl must be supplied.
    // Mutual exclusion: surface a clear error rather than silently picking one.
    def sourceCode = null
    def sourceMode = null
    def modesSet = [args.sourceFile, args.source, args.importUrl].count { it }
    if (modesSet > 1) {
        throw new IllegalArgumentException("Provide exactly one of 'source' (inline), 'sourceFile' (File Manager filename), or 'importUrl' (URL the hub will fetch).")
    }
    if (args.sourceFile) {
        sourceMode = "sourceFile"
        mcpLog("info", "hub-admin", "Reading ${type} source from File Manager: ${args.sourceFile}")
        def bytes = downloadHubFile(args.sourceFile)
        if (bytes == null) throw new IllegalArgumentException("Source file '${args.sourceFile}' not found in File Manager")
        sourceCode = new String(bytes, "UTF-8")
        mcpLog("info", "hub-admin", "Read ${sourceCode.length()} chars from ${args.sourceFile}")
    } else if (args.importUrl) {
        sourceMode = "importUrl"
        mcpLog("info", "hub-admin", "Reading ${type} source from importUrl: ${args.importUrl}")
        sourceCode = _fetchSourceFromUrl(args.importUrl)
        mcpLog("info", "hub-admin", "Read ${sourceCode.length()} chars from importUrl")
    } else if (args.source) {
        sourceMode = "source"
        sourceCode = args.source
    } else {
        throw new IllegalArgumentException("One of 'source' (inline Groovy code), 'sourceFile' (File Manager filename), or 'importUrl' (URL the hub will fetch) is required")
    }

    def savePath = (type == "app") ? "/app/saveOrUpdateJson" : "/driver/saveOrUpdateJson"
    def ajaxPath = (type == "app") ? "/app/ajax/code" : "/driver/ajax/code"

    mcpLog("info", "hub-admin", "Installing new ${type} (mode: ${sourceMode}, sourceLength: ${sourceCode.length()})...")
    try {
        // Firmware 2.5.x creates app/driver code via POST /app/saveOrUpdateJson (JSON) -- the same
        // endpoint the Vue editor uses and the twin of /library/saveOrUpdateJson. id:null => create;
        // response is {success, id, message}. (The old form POST /app/save returns an HTML page and
        // creates nothing on 2.5.0.143 -- this is the hub_create_app fix.)
        def saveBody = groovy.json.JsonOutput.toJson([id: null, source: sourceCode, version: 1])
        def parsed = hubInternalPostJson(savePath, saveBody)

        if (parsed == null || parsed.success != true) {
            def hubMsg = (parsed instanceof Map) ? (parsed.message ?: parsed.errorMessage) : null
            mcpLog("warn", "hub-admin", "${type.capitalize()} create rejected: ${hubMsg ?: 'no/unparseable response'}")
            return [
                success: false,
                error: "${type.capitalize()} installation failed: ${hubMsg ?: 'the hub rejected the source or returned no parseable response'}",
                (idField): null,
                note: "The hub validates the source on create. Fix the reported issue -- e.g. a Groovy compile error, or a required definition() field such as a non-empty iconUrl/iconX2Url (enforced on create) -- and retry.",
                lastBackup: formatTimestamp(state.lastBackupTimestamp)
            ]
        }

        def newItemId = parsed.id?.toString()
        if (!newItemId) {
            mcpLog("warn", "hub-admin", "${type.capitalize()} create returned success but no id: ${parsed}")
            return [
                success: false,
                error: "${type.capitalize()} install reported success but returned no item id.",
                (idField): null,
                note: "Unexpected /app/saveOrUpdateJson response shape. Check Hubitat > Apps Code (or Drivers Code).",
                lastBackup: formatTimestamp(state.lastBackupTimestamp)
            ]
        }

        // Best-effort verify: hub Location can point at editor while item is in error state.
        def verifyText = null
        def verifyError = null
        def verified = null

        try {
            verifyText = hubInternalGet(ajaxPath, [id: newItemId])
        } catch (Exception verifyErr) {
            verifyError = verifyErr.message ?: verifyErr.toString()
            mcpLog("warn", "hub-admin", "${type.capitalize()} post-install verification fetch failed for ID ${newItemId}: ${verifyError}")
        }

        def bodyPresent = verifyText instanceof String && !verifyText.trim().isEmpty()

        if (verifyError == null && bodyPresent) {
            try {
                verified = new groovy.json.JsonSlurper().parseText(verifyText)
            } catch (Exception parseErr) {
                mcpLog("warn", "hub-admin", "${type.capitalize()} ID ${newItemId}: verify body unparseable: ${parseErr.message}")
                return [
                    success: false,
                    error: "${type.capitalize()} install unverified: hub returned unparseable verify body for ID ${newItemId}",
                    (idField): newItemId,
                    note: "Hub created an item slot but the verify response was not valid JSON (possibly an HTML error/login page). Use get_${type}_source with ID ${newItemId} to confirm whether the item persisted. Do NOT retry the install without checking first -- a duplicate item with a different ID may result.",
                    lastBackup: formatTimestamp(state.lastBackupTimestamp)
                ]
            }

            if (verified.status == "error" || !verified.source) {
                def errMsg = verified.errorMessage ?: "item in error state after install"
                mcpLog("warn", "hub-admin", "${type.capitalize()} ID ${newItemId} installed but failed verification: ${errMsg}")
                return [
                    success: false,
                    error: "${type.capitalize()} installation failed: ${errMsg}",
                    (idField): newItemId,
                    note: "The hub created an item slot (ID: ${newItemId}) but reported an error. Check the Groovy source for compilation issues. You can view the error via hub_get_source(type='app'|'driver') with this ID.",
                    lastBackup: formatTimestamp(state.lastBackupTimestamp)
                ]
            }
        }

        if (verifyError == null && !bodyPresent) {
            mcpLog("warn", "hub-admin", "${type.capitalize()} ID ${newItemId}: verify endpoint returned empty body -- cannot confirm install")
            return [
                success: false,
                error: "${type.capitalize()} install unverified: hub returned empty verify body for ID ${newItemId}",
                (idField): newItemId,
                note: "Hub created an item slot but the verify fetch returned no content. Use get_${type}_source with ID ${newItemId} to confirm whether the item persisted. Do NOT retry the install without checking first -- a duplicate item with a different ID may result.",
                lastBackup: formatTimestamp(state.lastBackupTimestamp)
            ]
        }

        mcpLog("info", "hub-admin", "${type.capitalize()} installed (ID: ${newItemId}, mode: ${sourceMode}, verified: ${verifyError == null})")
        def installResult = [
            success: true,
            message: "${type.capitalize()} installed successfully",
            (idField): newItemId,
            sourceMode: sourceMode,
            sourceLength: sourceCode.length(),
            verified: (verifyError == null),
            lastBackup: formatTimestamp(state.lastBackupTimestamp)
        ]
        if (verifyError != null) installResult.verifyError = "${verifyError} -- use get_${type}_source with ID ${newItemId} to confirm."
        if (sourceMode == "sourceFile") installResult.note = "Source was read from File Manager file '${args.sourceFile}'."
        if (sourceMode == "importUrl") installResult.note = "Source was fetched from importUrl '${args.importUrl}' (hub-side fetch, no agent transcript)."
        return installResult
    } catch (Exception e) {
        mcpLogError("hub-admin", "${type.capitalize()} installation failed", e)
        return [
            success: false,
            error: "${type.capitalize()} installation failed: ${e.message}",
            note: "Check that the Groovy source code is valid and doesn't have syntax errors."
        ]
    }
}

def toolUpdateItemCode(String type, String idParam, args) {
    requireDestructiveConfirm(args.confirm)
    return toolUpdateItemCodeInner(type, idParam, args)
}

// Caller must have already invoked requireDestructiveConfirm -- gate fires once per call, not per bulk item.
private Map toolUpdateItemCodeInner(String type, String idParam, args) {
    def itemId = args[idParam]
    if (!itemId) throw new IllegalArgumentException("${idParam} is required")

    // Validate source mode: exactly one of resave/sourceFile/source/importUrl. The
    // mutex matches toolInstallItemSingle's strict check -- tool schemas advertise
    // these as mutually exclusive, so the API boundary must reject the combination
    // rather than silently picking one via if/else precedence.
    def modesSet = [args.resave, args.sourceFile, args.source, args.importUrl].count { it }
    if (modesSet == 0) {
        throw new IllegalArgumentException("One of 'source', 'sourceFile', 'importUrl', or 'resave' is required")
    }
    if (modesSet > 1) {
        throw new IllegalArgumentException("Provide exactly one of 'source', 'sourceFile', 'importUrl', or 'resave'")
    }

    // Reject explicit-null expectedVersion: a templated null arg would otherwise read as "no lock
    // requested" and silently let the write through -- the footgun the field exists to prevent.
    if (args.containsKey('expectedVersion') && args.expectedVersion == null) {
        throw new IllegalArgumentException("expectedVersion was supplied as null. Omit the field entirely to skip the optimistic-lock check, or pass an integer.")
    }

    // Validate id shape up front: bad args are caller-recoverable and belong on the
    // IllegalArgumentException path, not in a runtime-failure envelope from the POST.
    def itemIdInt
    try {
        itemIdInt = itemId as Integer
    } catch (NumberFormatException | ClassCastException idErr) {
        throw new IllegalArgumentException("${idParam} must be an integer ${type} code id (got '${itemId}')")
    }

    // Self-update guard: blocks overwriting our own app source unless Developer Mode is on.
    // Runs before any I/O so blocked self-updates don't pull source. Fails closed when `app` is
    // unavailable -- can't verify it's not a self-update, so refuse rather than risk a silent brick.
    if (type == "app") {
        def selfAppId = app?.id?.toString()
        if (selfAppId == null) {
            mcpLog("error", "hub-admin", "hub_update_app: self-update guard cannot verify -- app context unavailable (app=${app}); refusing appId=${itemId} to fail closed")
            throw new IllegalArgumentException("hub_update_app cannot verify the self-update guard: app context is unavailable (app=${app}). Refusing to proceed to avoid a silent self-update brick. Retry the call; this is typically a transient lifecycle window.")
        }
        if (itemId.toString() == selfAppId) {
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
    def freshVersion = null  // Track version from fresh fetch (not from backup cache)

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
        freshVersion = parsed.version  // Capture fresh version for optimistic locking
    } else if (args.sourceFile) {
        // Source file mode: read source from File Manager (avoids cloud size limits)
        sourceMode = "sourceFile"
        mcpLog("info", "hub-admin", "Reading ${type} source from File Manager: ${args.sourceFile}")
        def bytes = downloadHubFile(args.sourceFile)
        if (bytes == null) throw new IllegalArgumentException("Source file '${args.sourceFile}' not found in File Manager")
        sourceCode = new String(bytes, "UTF-8")
        mcpLog("info", "hub-admin", "Read ${sourceCode.length()} chars from ${args.sourceFile}")
    } else if (args.importUrl) {
        sourceMode = "importUrl"
        mcpLog("info", "hub-admin", "Reading ${type} source from importUrl: ${args.importUrl}")
        sourceCode = _fetchSourceFromUrl(args.importUrl)
        mcpLog("info", "hub-admin", "Read ${sourceCode.length()} chars from importUrl")
    } else {
        // Direct source mode -- presence already validated above.
        sourceMode = "source"
        sourceCode = args.source
    }

    // Back up current source for safety. The 1h cache inside backupItemSource means parallel-agent
    // conflicts cost nothing on the second call. `currentVersion` below is always re-fetched -- the
    // backup cache's `entry.version` can be stale.
    def itemBackup = backupItemSource(type, itemId.toString())

    // For optimistic locking, use fresh version if available (resave mode already fetched it).
    // Otherwise fetch current version fresh from hub — backup cache may have stale version.
    def currentVersion = freshVersion
    if (currentVersion == null) {
        try {
            def versionResponse = hubInternalGet(ajaxPath, [id: itemId])
            if (versionResponse) {
                def versionParsed = new groovy.json.JsonSlurper().parseText(versionResponse)
                currentVersion = versionParsed.version
            }
        } catch (Exception vErr) {
            mcpLog("warn", "hub-admin", "Could not fetch fresh version for ${type} ID ${itemId}, falling back to backup version: ${vErr.message}")
        }
        // Fall back to backup version if fresh fetch failed
        if (currentVersion == null) currentVersion = itemBackup.version
    }

    if (currentVersion == null) {
        throw new IllegalArgumentException("Could not determine current version for ${type} ID ${itemId}. The ${type} may not exist.")
    }

    // Optimistic-lock check: caller-asserted version catches RMW races against concurrent edits.
    if (args.containsKey('expectedVersion')) {
        def expectedVersionInt
        try {
            expectedVersionInt = args.expectedVersion as Integer
        } catch (NumberFormatException | ClassCastException coerceErr) {
            // Note: don't try to interpolate args.expectedVersion?.class -- the Hubitat sandbox
            // rejects .class on arbitrary inputs (returns null for non-system types).
            // coerceErr.message carries the actual coerce target (e.g. "Cannot coerce a map to class java.lang.Integer").
            throw new IllegalArgumentException("expectedVersion must be an integer (got '${args.expectedVersion}'): ${coerceErr.message}")
        }
        def currentVersionInt
        try {
            currentVersionInt = currentVersion as Integer
        } catch (NumberFormatException | ClassCastException currErr) {
            mcpLog("error", "hub-admin", "Hub returned non-integer version for ${type} ${itemId}: ${currentVersion}; aborting optimistic-lock evaluation to avoid an unguarded write")
            throw new IllegalArgumentException("Cannot evaluate expectedVersion: hub returned a non-integer current version (${currentVersion}). Update aborted to avoid an unguarded write.")
        }
        if (expectedVersionInt != currentVersionInt) {
            mcpLog("warn", "hub-admin", "${type} update ID ${itemId} aborted: expectedVersion=${expectedVersionInt} but currentVersion=${currentVersionInt} (concurrent edit detected)")
            return [
                success: false,
                error: "Optimistic-lock conflict: expected ${type} version ${expectedVersionInt} but hub has ${currentVersionInt}. Re-read the source and retry with the new expectedVersion.",
                (idParam): itemId,
                expectedVersion: expectedVersionInt,
                currentVersion: currentVersionInt,
                conflict: true,
                lastBackup: formatTimestamp(state.lastBackupTimestamp)
            ]
        }
    }

    // Self-update detection: when the MCP server updates its OWN app code, a SUCCESS reloads the app
    // (dropping our in-flight cloud response) and a big-file FAILURE 504s before the hub returns --
    // either way the result can't ride back on this call. Record it to atomicState so a later
    // hub_get_info read recovers the hub's verbatim outcome (incl. compile errors). See issue #237.
    //
    // Match BOTH ids: app.id is the installed-INSTANCE id, but a code deploy targets the Apps Code
    // CLASS id (from /hub2/userAppTypes), which differs -- matching only app.id would miss the real
    // self-deploy. Resolve the class id only when the cheap instance check misses (app-code updates
    // are rare); _resolveSelfAppClassId returns null on any failure (incl. unstubbed in tests), so a
    // miss simply leaves isSelfUpdate=false.
    boolean isSelfUpdate = false
    if (type == "app") {
        def selfInstanceId = app?.id?.toString()
        if (selfInstanceId != null && itemId?.toString() == selfInstanceId) {
            isSelfUpdate = true
        } else {
            def selfClassId = _resolveSelfAppClassId()
            if (selfClassId != null && itemId?.toString() == selfClassId.toString()) isSelfUpdate = true
            else if (selfClassId == null && sourceCode.length() > 500000) {
                // A self-deploy-shaped update (large source) whose class-id lookup flaked: #237 compile-
                // error capture won't arm, so a failed self-deploy would lose the hub's verbatim error.
                // Surface it instead of silently downgrading to a non-self update.
                mcpLog("warn", "hub-admin", "hub_update_app: large app-source update to id ${itemId} but the self app-class lookup returned null -- if this IS the MCP server, #237 self-deploy error capture is disabled for this deploy.")
            }
        }
    }
    mcpLog("info", "hub-admin", "Updating ${type} ID: ${itemId} (version: ${currentVersion}, mode: ${sourceMode}, sourceLength: ${sourceCode.length()})")
    try {
        // Update rides POST /app|driver/saveOrUpdateJson (JSON); a non-null id means in-place
        // update of the existing code class. Response is {success, id, message[, version]}; a
        // compile failure rides verbatim in `message`. (The old form /app|driver/ajax/update
        // returns a different envelope entirely: {status, errorMessage}.)
        def parsed = hubInternalPostJson(savePath, groovy.json.JsonOutput.toJson([
            id: itemIdInt,
            source: sourceCode,
            version: currentVersion
        ]))

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
                errorMsg = parsed.message ?: parsed.errorMessage ?: "hub response lacked success=true: ${parsed.toString().take(200)}"
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
                    sourceLength: sourceCode.length(),
                    at: now()
                ]
                // A dropped (empty) response is the expected self-update signature, but the success
                // is inferred, not hub-confirmed -- mark it so consumers can choose to re-verify.
                if (success && parsed == null) stash.assumed = true
                atomicState.lastSelfDeploy = stash
            } catch (Exception stashErr) {
                // Never break the deploy over bookkeeping, but a lost stash means the self-deploy
                // outcome is unrecoverable -- say so instead of failing silently.
                mcpLog("error", "hub-admin", "lastSelfDeploy stash write failed -- self-deploy outcome record lost: ${stashErr}")
            }
        }

        if (success) {
            mcpLog("info", "hub-admin", "${type} ID ${itemId} updated successfully (mode: ${sourceMode})")
            def successResult = [
                success: true,
                message: "${type.capitalize()} code updated successfully",
                (idParam): itemId,
                previousVersion: currentVersion,
                sourceMode: sourceMode,
                sourceLength: sourceCode.length(),
                lastBackup: formatTimestamp(state.lastBackupTimestamp)
            ]
            if (sourceMode == "resave") successResult.note = "Source was fetched and re-saved entirely on-hub — no cloud round-trip."
            if (sourceMode == "sourceFile") successResult.note = "Source was read from File Manager file '${args.sourceFile}' — no cloud size limits."
            if (sourceMode == "importUrl") successResult.note = "Source was fetched from importUrl '${args.importUrl}' (hub-side fetch, no agent transcript)."

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
                    hubInternalPostForm("/installedapp/configure/${triggerId}/mainPage", [
                        "_action_Done": "Done"
                    ])
                    successResult.updatedFired = true
                    mcpLog("info", "hub-admin", "triggerUpdated: fired updated() on instance ${triggerId} after app code save")
                } catch (Exception updErr) {
                    // Half-failure: the code was deployed (success:true holds) but the
                    // opt-in lifecycle refresh failed. The whole point of triggerUpdated
                    // was the lifecycle refresh -- so flag this as partial so callers
                    // checking `result.partial` see the half-failure without having to
                    // drill into updatedFired. Mirrors the partial:true pattern used
                    // elsewhere (e.g. the RM wizard's partial-success envelope).
                    successResult.updatedFired = false
                    successResult.partial = true
                    successResult.repairHints = ["triggerUpdated requested but lifecycle-fire POST failed: ${updErr.toString()}. The new code is deployed (success:true) but subscriptions/schedules may not have refreshed -- toggle the app off/on, or POST /installedapp/configure/${triggerId}/mainPage manually."]
                    mcpLog("warn", "hub-admin", "triggerUpdated failed on instance ${triggerId}: ${updErr.toString()}")
                }
            }

            return successResult
        } else {
            return [
                success: false,
                error: errorMsg ?: "Update failed - the hub returned an error",
                (idParam): itemId,
                note: "Check the Groovy source code for syntax errors or compilation issues.",
                lastBackup: formatTimestamp(state.lastBackupTimestamp)
            ]
        }
    } catch (Exception e) {
        mcpLogError("hub-admin", "${type} update failed", e)
        if (isSelfUpdate) {
            try {
                atomicState.lastSelfDeploy = [
                    success: false,
                    error: "${type.capitalize()} update failed: ${e.message}",
                    sourceMode: sourceMode,
                    importUrl: (args.importUrl ?: null),
                    sourceLength: (sourceCode != null ? sourceCode.length() : 0),
                    at: now()
                ]
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

def toolUpdateAppCode(args) {
    if (args.updates != null) {
        throw new IllegalArgumentException("Bulk mode ('updates' array) is not supported for hub_update_app. Apps do not cluster the way drivers do; update each app individually.")
    }
    if (args.oauth == null) {
        return toolUpdateItemCode("app", "appId", args)
    }

    // OAuth fold: enable/configure OAuth on the app CODE definition (apps only), optionally alongside
    // a source update. The destructive gate fires once here; the inner source path is then called
    // WITHOUT re-gating. OAuth-only is allowed (no source mode required).
    requireDestructiveConfirm(args.confirm)
    if (!args.appId) throw new IllegalArgumentException("appId is required")

    def hasSourceMode = [args.resave, args.sourceFile, args.source, args.importUrl].count { it } > 0
    def result
    if (hasSourceMode) {
        result = toolUpdateItemCodeInner("app", "appId", args)   // gate already fired above
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
// has empty creds and the hub generates them. Returns the resulting clientId/clientSecret.
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
        if (clientId == null) clientId = cur.clientId ?: ""
        if (clientSecret == null) clientSecret = cur.clientSecret ?: ""
    }

    try {
        def q = ["id=${java.net.URLEncoder.encode(appId.toString(), 'UTF-8')}",
                 "oauthEnabled=${enabled}",
                 "clientId=${java.net.URLEncoder.encode(clientId, 'UTF-8')}",
                 "clientSecret=${java.net.URLEncoder.encode(clientSecret, 'UTF-8')}",
                 "refreshSecret=${refreshSecret}"].join("&")
        def respText = hubInternalGet("/app/updateOAuth?${q}", null, 30)
        def parsed = null
        try { parsed = respText ? new groovy.json.JsonSlurper().parseText(respText) : null } catch (Exception ignore) { }
        if (parsed instanceof Map) {
            if (parsed.success) {
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
        return [success: false, error: "/app/updateOAuth did not report success", response: respText?.take(300),
                note: "The app's source must declare OAuth (an oauth block + mappings) before it can be enabled."]
    } catch (Exception e) {
        mcpLogError("hub-admin", "OAuth update failed", e)
        return [success: false, error: "OAuth update failed: ${e.message}", note: "Check the app id and Hub Security credentials."]
    }
}

// Read an app code definition's current OAuth client id/secret from the app-detail JSON
// (/app/list/single/data/<id> returns [{...oauthClientId, oauthClientSecret...}]). Used to preserve
// existing creds on re-enable. Returns [:] if unreadable -- the caller falls back to empty (generate).
private Map _readAppOAuthCreds(appId) {
    try {
        def txt = hubInternalGet("/app/list/single/data/${java.net.URLEncoder.encode(appId.toString(), 'UTF-8')}", null, 30)
        def d = txt ? new groovy.json.JsonSlurper().parseText(txt) : null
        def o = (d instanceof List && d) ? d[0] : (d instanceof Map ? d : null)
        if (!(o instanceof Map)) return [:]
        return [clientId: o.oauthClientId, clientSecret: o.oauthClientSecret]
    } catch (Exception e) {
        mcpLog("warn", "hub-admin", "Could not read current OAuth creds for app ${appId}: ${e.message}")
        return [:]
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
        def itemResults = []
        def allSucceeded = true
        args.updates.eachWithIndex { item, idx ->
            if (!(item instanceof Map) || !item.driverId) {
                itemResults << [driverId: item?.driverId?.toString() ?: "item[${idx}]", success: false, error: "Each updates entry must have a 'driverId' field."]
                allSucceeded = false
                return
            }
            try {
                def singleArgs = [driverId: item.driverId]
                if (item.sourceFile != null) singleArgs.sourceFile = item.sourceFile
                if (item.source != null) singleArgs.source = item.source
                if (item.importUrl != null) singleArgs.importUrl = item.importUrl
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
                    if (r.lastBackup) entry.lastBackup = r.lastBackup
                    if (r.conflict) {
                        entry.conflict = true
                        entry.expectedVersion = r.expectedVersion
                        entry.currentVersion = r.currentVersion
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

// Unified delete for the hub_delete_item tool: dispatches on type to the shared
// app/driver endpoint helper or the library-specific handler. Public param is
// `id`; mapped to the per-type id the helpers expect.
def toolDeleteItem(args) {
    def type = args.type
    if (!(type in ["app", "driver", "library"])) {
        throw new IllegalArgumentException("type is required and must be one of: app, driver, library")
    }
    def id = (args.id != null) ? args.id : (type == "app" ? args.appId : (type == "driver" ? args.driverId : args.libraryId))
    if (type == "library") {
        return toolDeleteLibrary(args + [libraryId: id])
    }
    def idParam = (type == "app") ? "appId" : "driverId"
    def deletePath = (type == "app") ? "/app/edit/deleteJsonSafe/" : "/driver/editor/deleteJson/"
    return _deleteItemViaEndpoint(type, idParam, deletePath, args + [(idParam): id])
}

private Map _deleteItemViaEndpoint(String type, String idParam, String deletePath, args) {
    requireDestructiveConfirm(args.confirm)
    def itemId = args[idParam]
    if (!itemId) throw new IllegalArgumentException("${idParam} is required")

    def backupSucceeded = true
    try {
        backupItemSource(type, itemId.toString())
    } catch (Exception backupErr) {
        backupSucceeded = false
        mcpLog("warn", "hub-admin", "Pre-delete backup failed for ${type} ${itemId}: ${backupErr.message} -- proceeding with delete")
    }

    mcpLog("warn", "hub-admin", "Deleting ${type} ID: ${itemId}")
    try {
        def responseText = hubInternalGet("${deletePath}${itemId}")
        mcpLog("debug", "hub-admin", "Delete ${type} ${itemId} response: ${responseText?.take(200)}")
        def success = false
        if (responseText) {
            try {
                def parsed = new groovy.json.JsonSlurper().parseText(responseText)
                success = parsed.status?.toString() == "true"
            } catch (Exception parseErr) {
                success = !responseText.toLowerCase().contains("error")
            }
        }

        if (success) {
            mcpLog("info", "hub-admin", "${type.capitalize()} ID ${itemId} deleted successfully")
            // .toString() because the stored key is a String but Map.get(GString) does not coerce (hashCode mismatch → silent null).
            def backupEntry = (atomicState.itemBackupManifest ?: [:])?.get("${type}_${itemId}".toString())
            def installTool = (type == "app") ? "hub_create_app" : "hub_create_driver"
            def result = [
                success: true,
                message: backupSucceeded ? "${type.capitalize()} deleted successfully. Source code backed up to File Manager." : "${type.capitalize()} deleted successfully. WARNING: Pre-delete backup failed -- source code may not be recoverable.",
                (idParam): itemId,
                lastBackup: formatTimestamp(state.lastBackupTimestamp),
                backupFile: backupEntry?.fileName,
                restoreHint: backupEntry ? "To restore: use '${installTool}' with the backup source, or download ${backupEntry.fileName} from Hubitat > Settings > File Manager and re-install manually." : null
            ]
            if (!backupSucceeded) result.backupWarning = "Pre-delete backup could not be created. The source code may be permanently lost."
            return result
        } else {
            return [
                success: false,
                error: "Delete may have failed - check the Hubitat web UI to verify",
                (idParam): itemId,
                response: responseText?.take(500)
            ]
        }
    } catch (Exception e) {
        mcpLogError("hub-admin", "${type.capitalize()} deletion failed", e)
        return [success: false, error: "${type.capitalize()} deletion failed: ${e.message}"]
    }
}

private Map backupLibrarySource(String libraryId) {
    def manifest = atomicState.itemBackupManifest ?: [:]
    def key = "library_${libraryId}"
    def existing = manifest[key]

    if (existing?.timestamp && (now() - existing.timestamp) < 3600000) {
        mcpLog("debug", "hub-admin", "Library backup for ${key} already exists (${formatTimestamp(existing.timestamp)}), skipping")
        return existing
    }

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
    def fileName = "mcp-backup-library-${libraryId}.groovy"
    try {
        uploadHubFile(fileName, backupSource.getBytes("UTF-8"))
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
        sourceLength: backupSource.length()
    ]
    manifest[key] = entry

    if (manifest.size() > 20) {
        def sortedKeys = manifest.entrySet().sort { a, b -> a.value.timestamp <=> b.value.timestamp }*.key
        def toRemove = sortedKeys.take(manifest.size() - 20)
        toRemove.each { k ->
            def e2 = manifest[k]
            if (e2?.fileName) {
                try { deleteHubFile(e2.fileName) } catch (Exception ex) { mcpLog("warn", "hub-admin", "Could not delete pruned library backup file '${e2.fileName}': ${ex.message}") }
            }
            manifest.remove(k)
        }
    }

    atomicState.itemBackupManifest = manifest
    mcpLog("info", "hub-admin", "Backed up library ID ${libraryId} source to File Manager: ${fileName} (version ${libData.version}, ${backupSource.length()} chars)")
    return entry
}

def toolGetLibrarySource(args) {
    def libraryId = args.libraryId
    if (!libraryId) throw new IllegalArgumentException("libraryId is required")
    if (!libraryId.toString().isInteger() || libraryId.toString().toInteger() <= 0) throw new IllegalArgumentException("libraryId must be a positive integer (got: '${libraryId}')")

    def maxChunkSize = 64000
    def requestedOffset = args.offset ? args.offset as int : 0
    def requestedLength = args.length ? Math.min(args.length as int, maxChunkSize) : maxChunkSize

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

def toolInstallLibrary(args) {
    requireDestructiveConfirm(args.confirm)

    // Resolve source: exactly one of sourceFile / source / importUrl
    def sourceCode = null
    def sourceMode = null
    def modesSet = [args.sourceFile, args.source, args.importUrl].count { it }
    if (modesSet > 1) {
        throw new IllegalArgumentException("Provide exactly one of 'source', 'sourceFile', or 'importUrl'")
    }
    if (args.sourceFile) {
        sourceMode = "sourceFile"
        mcpLog("info", "hub-admin", "Reading library source from File Manager: ${args.sourceFile}")
        def bytes = downloadHubFile(args.sourceFile)
        if (bytes == null) throw new IllegalArgumentException("Source file '${args.sourceFile}' not found in File Manager")
        sourceCode = new String(bytes, "UTF-8")
        mcpLog("info", "hub-admin", "Read ${sourceCode.length()} chars from ${args.sourceFile}")
    } else if (args.importUrl) {
        sourceMode = "importUrl"
        mcpLog("info", "hub-admin", "Reading library source from importUrl: ${args.importUrl}")
        sourceCode = _fetchSourceFromUrl(args.importUrl)
        mcpLog("info", "hub-admin", "Read ${sourceCode.length()} chars from importUrl")
    } else if (args.source) {
        sourceMode = "source"
        sourceCode = args.source
    } else {
        throw new IllegalArgumentException("One of 'source', 'sourceFile', or 'importUrl' is required")
    }

    mcpLog("info", "hub-admin", "Installing new library (mode: ${sourceMode}, sourceLength: ${sourceCode.length()})")
    try {
        def body = groovy.json.JsonOutput.toJson([id: null, source: sourceCode, version: null])
        def result = hubInternalPostJson("/library/saveOrUpdateJson", body)

        def newLibraryId = result?.id?.toString()
        def newVersion = result?.version

        if (result?.success == false) {
            def msg = result?.message ?: "Hub returned failure"
            mcpLog("warn", "hub-admin", "Library install failed: ${msg}")
            return [
                success: false,
                error: "Library installation failed: ${msg}",
                note: "Check that the Groovy source includes a valid library() definition block and has no syntax errors.",
                lastBackup: formatTimestamp(state.lastBackupTimestamp)
            ]
        }

        // Fail-closed when hub gives us nothing concrete to confirm the install:
        // either no response at all, or a response without an id. The library
        // may or may not have persisted -- the agent must check the UI rather
        // than retry blindly (which could create a duplicate at a fresh id).
        if (result == null || !newLibraryId) {
            def detail = result == null ? "empty/null response" : "response missing id field"
            mcpLog("warn", "hub-admin", "Library install: hub returned ${detail} -- cannot confirm install")
            return [
                success: false,
                error: "Library install unverified: hub returned ${detail}",
                note: "Check Hubitat web UI (FOR DEVELOPERS > Libraries code) to confirm whether the library was actually persisted. Do NOT retry without checking first -- a duplicate library may result.",
                lastBackup: formatTimestamp(state.lastBackupTimestamp)
            ]
        }

        mcpLog("info", "hub-admin", "Library installed successfully (ID: ${newLibraryId}, version: ${newVersion})")

        // Post-install verification: confirm library persists in the hub list.
        // Mirrors toolInstallItemSingle semantics: empty/unparseable verify response or a hub
        // compile-error signal both return success=false (with libraryId populated so the
        // caller can inspect via hub_get_source(type='library')). Transient fetch failures return
        // success=true with verified=false + verifyError.
        def verifyText = null
        def verifyError = null
        try {
            verifyText = hubInternalGet("/hub2/userLibraries")
        } catch (Exception verifyErr) {
            verifyError = verifyErr.message ?: verifyErr.toString()
            mcpLog("warn", "hub-admin", "Library post-install verification fetch failed for ID ${newLibraryId}: ${verifyError}")
        }

        if (verifyError == null) {
            if (!verifyText) {
                mcpLog("warn", "hub-admin", "Library ID ${newLibraryId}: verify endpoint returned empty body -- cannot confirm install")
                return [
                    success: false,
                    error: "Library install unverified: hub returned empty verify body for ID ${newLibraryId}",
                    libraryId: newLibraryId,
                    note: "Hub created a library slot but the verify fetch returned no content. Use hub_get_source(type='library', id=${newLibraryId}) to confirm whether the library persisted. Do NOT retry without checking first -- a duplicate library at a new ID may result.",
                    lastBackup: formatTimestamp(state.lastBackupTimestamp)
                ]
            }
            def libraries
            try {
                libraries = new groovy.json.JsonSlurper().parseText(verifyText)
            } catch (Exception parseErr) {
                mcpLog("warn", "hub-admin", "Library ID ${newLibraryId}: verify body unparseable: ${parseErr.message}")
                return [
                    success: false,
                    error: "Library install unverified: hub returned unparseable verify body for ID ${newLibraryId}",
                    libraryId: newLibraryId,
                    note: "Hub created a library slot but the verify response was not valid JSON. Use hub_get_source(type='library', id=${newLibraryId}) to confirm whether the library persisted. Do NOT retry without checking first -- a duplicate library at a new ID may result.",
                    lastBackup: formatTimestamp(state.lastBackupTimestamp)
                ]
            }
            def found = (libraries instanceof List) && libraries.any { it.id?.toString() == newLibraryId }
            if (!found) {
                mcpLog("warn", "hub-admin", "Library ID ${newLibraryId} not found in post-install library list")
                return [
                    success: false,
                    error: "Library install unverified: ID ${newLibraryId} not found in post-install library list",
                    libraryId: newLibraryId,
                    note: "Hub created a library slot but the verify list did not include it. Use hub_get_source(type='library', id=${newLibraryId}) to confirm. Do NOT retry without checking first -- a duplicate library at a new ID may result.",
                    lastBackup: formatTimestamp(state.lastBackupTimestamp)
                ]
            }
        }

        mcpLog("info", "hub-admin", "Library installed (ID: ${newLibraryId}, mode: ${sourceMode}, verified: ${verifyError == null})")
        def installResult = [
            success: true,
            message: "Library installed successfully",
            libraryId: newLibraryId,
            version: newVersion,
            sourceMode: sourceMode,
            sourceLength: sourceCode.length(),
            verified: (verifyError == null),
            lastBackup: formatTimestamp(state.lastBackupTimestamp)
        ]
        if (verifyError != null) installResult.verifyError = "${verifyError} -- use hub_get_source(type='library', id=${newLibraryId}) to confirm."
        return installResult
    } catch (Exception e) {
        mcpLogError("hub-admin", "Library installation failed", e)
        return [
            success: false,
            error: "Library installation failed: ${e.message}",
            note: "Check that the Groovy source includes a valid library() definition block and has no syntax errors."
        ]
    }
}

def toolUpdateLibraryCode(args) {
    requireDestructiveConfirm(args.confirm)
    def libraryId = args.libraryId
    if (!libraryId) throw new IllegalArgumentException("libraryId is required")
    if (!libraryId.toString().isInteger() || libraryId.toString().toInteger() <= 0) throw new IllegalArgumentException("libraryId must be a positive integer (got: '${libraryId}')")

    // Resolve source: exactly one of resave/sourceFile/source/importUrl. Matches
    // the mutex enforcement in toolInstallItemSingle / toolUpdateItemCodeInner --
    // tool schema advertises these as mutually exclusive.
    def sourceCode = null
    def sourceMode = null
    def freshVersion = null
    def modesSet = [args.resave, args.sourceFile, args.source, args.importUrl].count { it }
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
    } else if (args.sourceFile) {
        sourceMode = "sourceFile"
        mcpLog("info", "hub-admin", "Reading library source from File Manager: ${args.sourceFile}")
        def bytes = downloadHubFile(args.sourceFile)
        if (bytes == null) throw new IllegalArgumentException("Source file '${args.sourceFile}' not found in File Manager")
        sourceCode = new String(bytes, "UTF-8")
        mcpLog("info", "hub-admin", "Read ${sourceCode.length()} chars from ${args.sourceFile}")
    } else if (args.importUrl) {
        sourceMode = "importUrl"
        mcpLog("info", "hub-admin", "Reading library source from importUrl: ${args.importUrl}")
        sourceCode = _fetchSourceFromUrl(args.importUrl)
        mcpLog("info", "hub-admin", "Read ${sourceCode.length()} chars from importUrl")
    } else if (args.source) {
        sourceMode = "source"
        sourceCode = args.source
    } else {
        throw new IllegalArgumentException("One of 'source', 'sourceFile', 'importUrl', or 'resave' is required")
    }

    // Check 1-hour dedup BEFORE any backup-related fetch -- preserves the original
    // pre-edit baseline through rapid edits AND avoids an unnecessary network round-trip.
    // Fail-closed: backup-fetch failure (when needed) aborts the update, matching
    // toolUpdateItemCodeInner which calls backupItemSource() without try/catch.
    def backupFileName = null
    def existingEntry = (atomicState.itemBackupManifest ?: [:])["library_${libraryId}"]
    def skipBackup = (existingEntry?.timestamp && (now() - existingEntry.timestamp) < 3600000)

    def versionFetchError = null
    if (skipBackup) {
        mcpLog("debug", "hub-admin", "Library backup for library_${libraryId} already exists (${formatTimestamp(existingEntry.timestamp)}), skipping")
        backupFileName = existingEntry.fileName
        // Still need fresh version for optimistic locking when the source-resolution path
        // didn't provide it (i.e., source/sourceFile modes -- resave already set it).
        if (freshVersion == null) {
            try {
                def versionText = hubInternalGet("/library/list/single/data/${libraryId}")
                if (versionText) {
                    def versionParsed = new groovy.json.JsonSlurper().parseText(versionText)
                    if (versionParsed instanceof List && !versionParsed.isEmpty()) {
                        freshVersion = versionParsed[0]?.version
                    }
                }
            } catch (Exception vErr) {
                versionFetchError = vErr.message ?: vErr.toString()
                mcpLog("warn", "hub-admin", "Could not fetch fresh version for library ID ${libraryId}, falling back to backup version: ${versionFetchError}")
            }
            // Fall back to cached backup version (matching toolUpdateItemCodeInner fallback)
            if (freshVersion == null) freshVersion = existingEntry.version
        }
    } else {
        // Backup needed. backupLibrarySource handles fetch, upload, manifest update, and pruning.
        // Fail-closed: any throw propagates up and aborts the update -- same contract as
        // toolUpdateItemCodeInner calling backupItemSource() without try/catch.
        def backupEntry = backupLibrarySource(libraryId.toString())
        backupFileName = backupEntry.fileName
        if (freshVersion == null) freshVersion = backupEntry.version
    }

    if (freshVersion == null) {
        // This path is only reachable when skipBackup=true (dedup window), freshVersion was null
        // after the source-resolution step, AND the version-refetch failed AND existingEntry.version
        // was also null. Surface the actual failure so the agent can diagnose.
        throw new IllegalArgumentException("Could not determine current version for library ID ${libraryId} -- version fetch failed (${versionFetchError ?: 'unknown'}) and cached backup has no version. Check that the library exists.")
    }
    def currentVersion = freshVersion

    mcpLog("info", "hub-admin", "Updating library ID: ${libraryId} (version: ${currentVersion}, mode: ${sourceMode}, sourceLength: ${sourceCode.length()})")
    try {
        def body = groovy.json.JsonOutput.toJson([id: libraryId as Integer, source: sourceCode, version: currentVersion as Integer])
        def result = hubInternalPostJson("/library/saveOrUpdateJson", body)

        if (result?.success == false) {
            def msg = result?.message ?: "Hub returned failure"
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
            previousVersion: currentVersion,
            newVersion: result?.version,
            sourceMode: sourceMode,
            sourceLength: sourceCode.length(),
            lastBackup: formatTimestamp(state.lastBackupTimestamp)
        ]
        if (sourceMode == "resave") successResult.note = "Source was fetched and re-saved entirely on-hub -- no cloud round-trip."
        if (sourceMode == "sourceFile") successResult.note = "Source was read from File Manager file '${args.sourceFile}' -- no cloud size limits."
        return successResult
    } catch (Exception e) {
        mcpLogError("hub-admin", "Library update failed", e)
        return [success: false, error: "Library update failed: ${e.message}"]
    }
}

def toolDeleteLibrary(args) {
    requireDestructiveConfirm(args.confirm)
    def libraryId = args.libraryId
    if (!libraryId) throw new IllegalArgumentException("libraryId is required")
    if (!libraryId.toString().isInteger() || libraryId.toString().toInteger() <= 0) throw new IllegalArgumentException("libraryId must be a positive integer (got: '${libraryId}')")

    // Initialize to false -- assume backup failed; only flip to true on confirmed completion so the success message and backupFile field stay consistent on every exit path.
    def backupSucceeded = false
    def backupFileName = null
    try {
        def backupEntry = backupLibrarySource(libraryId.toString())
        backupFileName = backupEntry.fileName
        backupSucceeded = true
    } catch (Exception backupErr) {
        mcpLog("warn", "hub-admin", "Pre-delete backup failed for library ${libraryId}: ${backupErr.message} -- proceeding with delete")
    }

    mcpLog("warn", "hub-admin", "Deleting library ID: ${libraryId}")
    try {
        def responseText = hubInternalGet("/library/edit/deleteJson/${libraryId}")
        mcpLog("debug", "hub-admin", "Delete library ${libraryId} response: ${responseText?.take(200)}")

        // Fail-closed: only treat the delete as successful when the hub returns parseable
        // JSON with an explicit success==true. Substring fallback is unsafe -- hub rejection
        // bodies like "Library is in use by..." don't contain the word "error" and would
        // produce a false success.
        def success = false
        if (responseText) {
            try {
                def parsed = new groovy.json.JsonSlurper().parseText(responseText)
                // Hub returns {success: true, message: null} on success
                if (parsed?.success == true) {
                    success = true
                } else {
                    def hubMsg = parsed?.message ?: parsed?.error ?: "Hub returned success=false"
                    mcpLog("warn", "hub-admin", "Delete library ${libraryId}: hub indicated failure: ${hubMsg}")
                    return [
                        success: false,
                        error: hubMsg,
                        libraryId: libraryId,
                        note: "Hub rejected the delete request. If the library is in use, remove all #include references from apps and drivers before deleting."
                    ]
                }
            } catch (Exception parseErr) {
                mcpLog("warn", "hub-admin", "Delete library ${libraryId}: response not parseable as JSON -- treating as failure. Response: ${responseText?.take(200)}")
                return [
                    success: false,
                    error: "Delete response was not valid JSON -- cannot confirm deletion. Response: ${responseText?.take(200)}",
                    libraryId: libraryId,
                    note: "Check Hubitat web UI (FOR DEVELOPERS > Libraries code) to verify whether the library was deleted."
                ]
            }
        }

        if (success) {
            mcpLog("info", "hub-admin", "Library ID ${libraryId} deleted successfully")
            def backupEntry = (atomicState.itemBackupManifest ?: [:])?.get("library_${libraryId}".toString())
            def result = [
                success: true,
                message: backupSucceeded ? "Library deleted successfully. Source code backed up to File Manager." : "Library deleted successfully. WARNING: Pre-delete backup failed -- source code may not be recoverable.",
                libraryId: libraryId,
                lastBackup: formatTimestamp(state.lastBackupTimestamp),
                backupFile: backupEntry?.fileName ?: backupFileName,
                restoreHint: backupEntry ? "To restore: use 'hub_create_library' with the backup source, or download ${backupEntry.fileName} from Hubitat > Settings > File Manager and re-install manually." : null
            ]
            if (!backupSucceeded) result.backupWarning = "Pre-delete backup could not be created. The source code may be permanently lost."
            return result
        } else {
            return [
                success: false,
                error: "Delete may have failed - check the Hubitat web UI (FOR DEVELOPERS > Libraries code) to verify",
                libraryId: libraryId,
                response: responseText?.take(500)
            ]
        }
    } catch (Exception e) {
        mcpLogError("hub-admin", "Library deletion failed", e)
        return [success: false, error: "Library deletion failed: ${e.message}"]
    }
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
                    disabled: d.disabled == true,
                    user: d.user == true,
                    hidden: isHidden,
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
        throw e  // let cursor / arg-validation surface as -32602; don't reframe as transport failure
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

def toolGetDeviceInUseBy(args) {
    if (!args?.deviceId) throw new IllegalArgumentException("deviceId is required")
    def deviceId = args.deviceId.toString().trim()
    // /device/fullJson/<id> returns a parseable non-empty body even for unknown ids
    // (empty appsUsing, null fields), which would read as "device has no apps" rather
    // than "device doesn't exist". Validate up front against the hub's device registry
    // so callers get an explicit Device-not-found error. Mirrors toolGetHubLogs.
    if (!findDevice(deviceId)) {
        throw new IllegalArgumentException("Device not found: ${deviceId}")
    }

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

        // Defensive shape check: if firmware drifts and appsUsing arrives as a Map
        // (or anything other than List), the collect{} below would produce all-null
        // entries silently. Surface that loud rather than letting the paginator
        // hand the LLM a bag of nulls. instanceof-based shape labelling because the
        // Hubitat sandbox returns null for .class on parsed-Map values.
        if (parsed?.appsUsing != null && !(parsed.appsUsing instanceof List)) {
            String shape = (parsed.appsUsing instanceof Map) ? "Map" :
                           (parsed.appsUsing instanceof String) ? "String" :
                           (parsed.appsUsing instanceof Number) ? "Number" : "non-list"
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
        def cursor = args?.cursor
        def paged = _paginateList(appsUsingList, cursor, 100, "hub_list_device_dependents")
        def result = [
            deviceId: deviceId,
            // extraBreadcrumb is the canonical UI breadcrumb label; fall back to .name or
            // .label if a future firmware drops that field so callers still get a usable
            // device-name string instead of silent null.
            deviceName: parsed?.extraBreadcrumb ?: parsed?.name ?: parsed?.label,
            appsUsing: paged.page,
            count: count,
            parentApp: parsed?.parentApp
        ]
        // Surface the count/array disparity when firmware reports an appsUsingCount that
        // doesn't match the appsUsing array length (truncation / paging signal). Caller
        // can then decide whether to chase the missing entries via another path.
        if (count != appsUsingList.size()) {
            result.countMismatch = "appsUsingCount=${count} but appsUsing array carries ${appsUsingList.size()} entries -- firmware may be truncating"
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
    return [
        // ==================== HUB ADMIN READ TOOLS ====================
        // get_hub_details merged into hub_get_info (core tool)
        [
            name: "hub_list_apps",
            description: """List apps on the hub. scope selects what kind of "apps" to return.

scope='instances' (default) — running app INSTANCES (built-in + user) with parent/child tree. Requires the Read master.[[FLAT_TRIM]]
  Each app entry returns: id, name, type, disabled, user (true=user-installed Groovy app, false=built-in), hidden, parentId (null for top-level), hasChildren, childCount. Per-app event history: hub_list_device_events with appId.
  Use filter to narrow results: 'all' (default), 'builtin' (Hubitat native apps), 'user' (custom Groovy apps), 'disabled' (paused/disabled), 'parents' (apps with children like Rule Machine, Room Lighting, Groups and Scenes), 'children' (individual rules, scenes, etc.).
  filter, includeHidden, and cursor apply to this mode.[[/FLAT_TRIM]]

scope='types' — installed app CODE LIBRARY / available app TYPES (the app code installed on the hub, not running instances). Requires Read master.[[FLAT_TRIM]]
  filter and includeHidden are ignored in this mode.[[/FLAT_TRIM]]

Pass cursor to page through the list at 50 per page when the full response would exceed the hub's 128KB JSON-RPC cap.""",
            inputSchema: [
                type: "object",
                properties: [
                    scope: [type: "string", enum: ["instances", "types"], description: "What to list. 'instances' (default) = running app instances with parent/child tree (Read master). 'types' = installed app code library / available app types (Read master).", default: "instances"],
                    filter: [type: "string", enum: ["all", "builtin", "user", "disabled", "parents", "children"], description: "scope='instances' only: filter apps by category. Default: all"],
                    includeHidden: [type: "boolean", description: "scope='instances' only: include hidden apps (typically Hubitat internal). Default: false", default: false],
                    cursor: [type: "string", description: "Opt-in pagination cursor. Omit for unbounded (subject to 120KB guard).[[FLAT_TRIM]] Pass the nextCursor value from a prior call to fetch the next page (page size 50). Empty string starts at the first page.[[/FLAT_TRIM]]"]
                ]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    apps: [type: "array", description: "App entries (shape depends on scope)", items: [type: "object", properties: [
                        id: [description: "App ID"],
                        name: [type: "string", description: "App name"],
                        type: [type: "string", description: "App type (scope='instances'); 'MCP Rule' on the fallback path"],
                        disabled: [type: "boolean", description: "scope='instances': app is paused/disabled"],
                        user: [type: "boolean", description: "scope='instances': true=user Groovy app, false=built-in"],
                        hidden: [type: "boolean", description: "scope='instances': app is hidden"],
                        parentId: [description: "scope='instances': parent app ID, null at top level"],
                        hasChildren: [type: "boolean", description: "scope='instances': app has child apps"],
                        childCount: [type: "integer", description: "scope='instances': number of child apps"]
                    ]]],
                    count: [type: "integer", description: "Apps returned"],
                    filter: [type: "string", description: "scope='instances': filter applied"],
                    totalOnHub: [type: "integer", description: "scope='instances': total apps before filtering"],
                    source: [type: "string", description: "scope='types': hub_api / hub_api_raw / mcp_only"],
                    note: [type: "string", description: "Status note when the hub API was unavailable or returned a non-JSON shape"],
                    rawResponse: [type: "string", description: "scope='types': raw body when response was not JSON"],
                    total: [type: "integer", description: "Total matched (present when paginating)"],
                    nextCursor: [type: "string", description: "Present when more results remain"]
                ],
                required: ["apps"]
            ]
        ],
        [
            name: "hub_list_drivers",
            description: "List all installed drivers on the hub. Requires Read master.",
            inputSchema: [
                type: "object",
                properties: [
                    cursor: [type: "string", description: "Opt-in pagination cursor. Omit for unbounded; pass \"\" for the first page, iterate nextCursor (page size 50)."]
                ]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    drivers: [type: "array", description: "Installed driver entries as returned by the hub", items: [type: "object"]],
                    count: [type: "integer", description: "Drivers returned"],
                    source: [type: "string", description: "hub_api / hub_api_raw / unavailable"],
                    note: [type: "string", description: "Status note when the hub API was unavailable or returned a non-JSON shape"],
                    rawResponse: [type: "string", description: "Raw body when response was not JSON"],
                    total: [type: "integer", description: "Total matched (present when paginating)"],
                    nextCursor: [type: "string", description: "Present when more results remain"]
                ],
                required: ["drivers"]
            ]
        ],
        [
            name: "hub_list_libraries",
            description: "List all Groovy libraries installed on the hub (id, name, namespace, version). Use this to discover a library's id, then read its source with hub_get_source(type='library', id=N); the source is omitted here to keep the list lean. Requires Read master.",
            inputSchema: [
                type: "object",
                properties: [
                    cursor: [type: "string", description: "Opt-in pagination cursor. Omit for unbounded; pass \"\" for the first page, iterate nextCursor (page size 50)."]
                ]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    libraries: [type: "array", description: "Installed library summaries (id, name, namespace, version)", items: [type: "object"]],
                    count: [type: "integer", description: "Libraries returned"],
                    source: [type: "string", description: "hub_api / hub_api_raw / unavailable"],
                    note: [type: "string", description: "Status note when the hub API was unavailable or returned a non-JSON shape"],
                    rawResponse: [type: "string", description: "Raw body when response was not JSON"],
                    total: [type: "integer", description: "Total matched (present when paginating)"],
                    nextCursor: [type: "string", description: "Present when more results remain"]
                ],
                required: ["libraries"]
            ]
        ],
        // Hub Admin App/Driver Source Read Tools
        [
            name: "hub_get_source",
            description: "Get the Groovy source of an installed app, driver, or library. Pass type and id. Supports chunked reading (offset/length); large files are auto-saved to the File Manager for use with the matching update tool's sourceFile mode. Requires Read master.",
            inputSchema: [
                type: "object",
                properties: [
                    type: [type: "string", enum: ["app", "driver", "library"], description: "What kind of code to read."],
                    id: [type: "string", description: "The app/driver/library ID (positive integer). App IDs from hub_list_apps, driver IDs from hub_list_drivers, library IDs from hub_list_libraries (or a hub_create_library response, or the Hubitat Libraries code page)."],
                    offset: [type: "integer", description: "Character offset to start reading from (for chunked reading of large sources). Default: 0"],
                    length: [type: "integer", description: "Max characters to return in this chunk. Default/max: 64000"]
                ],
                required: ["type", "id"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the source was read"],
                    appId: [description: "App ID (type='app')"],
                    driverId: [description: "Driver ID (type='driver')"],
                    libraryId: [description: "Library ID (type='library')"],
                    source: [type: "string", description: "Source chunk for the requested offset/length"],
                    version: [description: "Item version"],
                    status: [type: "string", description: "Hub status field (app/driver)"],
                    name: [type: "string", description: "Library name (type='library')"],
                    namespace: [type: "string", description: "Library namespace (type='library')"],
                    totalLength: [type: "integer", description: "Total source length in chars"],
                    offset: [type: "integer", description: "Start offset of this chunk"],
                    chunkLength: [type: "integer", description: "Chars returned in this chunk"],
                    hasMore: [type: "boolean", description: "More chunks remain"],
                    nextOffset: [type: "integer", description: "Offset for next chunk; present when hasMore"],
                    remainingChars: [type: "integer", description: "Chars left; present when hasMore"],
                    hint: [type: "string", description: "Next-chunk guidance; present when hasMore"],
                    sourceFile: [type: "string", description: "File Manager filename full source was auto-saved to (large sources)"],
                    sourceFileHint: [type: "string", description: "Guidance for using sourceFile mode; present with sourceFile"],
                    sourceFileError: [type: "string", description: "Present (library) when auto-save to File Manager failed"]
                ],
                required: ["success"]
            ]
        ],
        // Hub Admin App/Driver Management Write Tools
        [
            name: "hub_create_app",
            description: """⚠️ Install new app. Show code to user and get confirmation first.

Three source modes (mutually exclusive):
- source (inline) -- stubs only, fills agent transcript
- sourceFile -- read from File Manager[[FLAT_TRIM]] (upload first via curl -F uploadFile=@./X.groovy -F folder=/ http://<hub>/hub/fileManager/upload; Hub Security: prefix with curl -c cookies.txt -d username=USER&password=PASS http://<hub>/login then add -b cookies.txt)[[/FLAT_TRIM]]
- importUrl -- hub fetches the URL directly[[FLAT_TRIM]] (mirrors UI's "Import Code from Website" then Save flow)[[/FLAT_TRIM]]

After installing the code, create a running instance with a SECOND call: hub_create_app(installAsUserApp: <newAppId>, confirm: true). Mutually exclusive with code-install args.

Verifies install succeeded: if the hub accepted the request but the app failed to compile, hub_create_app returns success=false with the error. Requires Write master + confirm + backup <24h. Returns new app ID.""",
            inputSchema: [
                type: "object",
                properties: [
                    source: [type: "string", description: "Inline Groovy source. Stubs only -- fills agent transcript. For non-trivial apps prefer sourceFile or importUrl."],
                    sourceFile: [type: "string", description: "File Manager filename (upload first via curl per tool description; bypasses agent transcript)."],
                    importUrl: [type: "string", description: "URL the hub fetches directly. Mirrors the editor's Import Code from Website + Save. http:// or https://. Mutually exclusive with source/sourceFile."],
                    installAsUserApp: [type: "integer", description: "Second-step mode: create a running instance from already-installed code (the codeAppId returned by a prior hub_create_app call) AND commit the install. Mutually exclusive with code-install args.[[FLAT_TRIM]] Submits the config page's Done, firing installed()/initialize() so schedules/subscriptions actually register. Targets apps whose first page installs with defaults; a required first-page input with no default blocks the auto-Done (same as the UI).[[/FLAT_TRIM]]"],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms backup was created and user approved."]
                ],
                required: ["confirm"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the install/instantiation succeeded"],
                    message: [type: "string", description: "Human-readable result"],
                    appId: [description: "New app code ID (code-install mode)"],
                    sourceMode: [type: "string", description: "Source mode used: source / sourceFile / importUrl"],
                    sourceLength: [type: "integer", description: "Chars of source installed"],
                    verified: [type: "boolean", description: "Whether post-install compile verification passed"],
                    verifyError: [type: "string", description: "Verification fetch error; present when verify could not run"],
                    codeAppId: [description: "installAsUserApp mode: source code app ID"],
                    instanceAppId: [description: "installAsUserApp mode: new running instance ID"],
                    committed: [type: "boolean", description: "installAsUserApp mode: whether the install was committed (Done submitted AND the instance reads app.installed=true). false means an inert shell was left behind -- delete it and retry"],
                    installedConfirmed: [type: "boolean", description: "installAsUserApp mode: the committed instance's app.installed flag was independently re-read as true (false here with committed=true means the confirmation read failed -- see note)"],
                    scheduledJobCount: [type: "integer", description: "installAsUserApp mode: scheduled jobs registered by initialize() (evidence the install committed)"],
                    eventSubscriptionCount: [type: "integer", description: "installAsUserApp mode: event subscriptions registered by initialize()"],
                    mode: [type: "string", description: "installAsUserApp mode marker"],
                    note: [type: "string", description: "Recovery/source-mode guidance"],
                    lastBackup: [type: "string", description: "Timestamp of most recent backup"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_create_driver",
            description: """⚠️ Install new driver. Show code to user and get confirmation first.

Three source modes (mutually exclusive per item):
- source (inline) -- stubs only
- sourceFile -- File Manager filename[[FLAT_TRIM]] (upload first via curl -F uploadFile=@./X.groovy -F folder=/ http://<hub>/hub/fileManager/upload)[[/FLAT_TRIM]]
- importUrl -- hub fetches the URL directly

For >1 driver: USE BULK mode (single round-trip, not N separate calls): installs=[{source|sourceFile|importUrl}, ...].[[FLAT_TRIM]] Cannot mix bulk and single-driver fields.[[/FLAT_TRIM]]

Verifies install succeeded: if the hub accepted the request but the driver failed to compile, hub_create_driver returns success=false with the error. Requires Write master + confirm + backup <24h. Returns new driver ID(s).""",
            inputSchema: [
                type: "object",
                properties: [
                    source: [type: "string", description: "Inline Groovy source (single-driver mode). Stubs only -- fills agent transcript."],
                    sourceFile: [type: "string", description: "File Manager filename (single-driver mode). Upload first via curl per tool description."],
                    importUrl: [type: "string", description: "URL the hub fetches directly (single-driver mode). http:// or https://. Mutually exclusive with source/sourceFile."],
                    installs: [
                        type: "array",
                        description: "BULK MODE -- one round-trip for many drivers. Each entry: {source|sourceFile|importUrl}. Cannot mix with single-driver fields. Continue-on-error.",
                        items: [
                            type: "object",
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
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the install (or all bulk installs) succeeded"],
                    message: [type: "string", description: "Human-readable result"],
                    driverId: [description: "New driver code ID (single-driver mode)"],
                    sourceMode: [type: "string", description: "Source mode used (single-driver mode)"],
                    sourceLength: [type: "integer", description: "Chars installed (single-driver mode)"],
                    verified: [type: "boolean", description: "Post-install verification passed (single-driver mode)"],
                    verifyError: [type: "string", description: "Verification fetch error (single-driver mode)"],
                    note: [type: "string", description: "Recovery/source-mode guidance"],
                    installs: [type: "array", description: "Per-driver results (bulk mode)", items: [type: "object", properties: [
                        driverId: [description: "Driver code ID, null if it failed"],
                        success: [type: "boolean", description: "Whether this driver installed"],
                        sourceMode: [type: "string", description: "Source mode used"],
                        sourceLength: [type: "integer", description: "Chars installed"],
                        verified: [type: "boolean", description: "Verification passed"],
                        verifyError: [type: "string", description: "Verification fetch error"],
                        error: [type: "string", description: "Failure reason; present when success=false"],
                        note: [type: "string", description: "Per-item guidance"]
                    ]]],
                    lastBackup: [type: "string", description: "Timestamp of most recent backup"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_update_app",
            description: """⚠️ CRITICAL: Modify existing app code. Read current source first, explain changes, get confirmation.

Four source modes (mutually exclusive):
- source (inline) -- stubs only, fills agent transcript
- sourceFile -- File Manager filename[[FLAT_TRIM]] (upload first via curl -F uploadFile=@./X.groovy -F folder=/ http://<hub>/hub/fileManager/upload)[[/FLAT_TRIM]]
- importUrl -- hub fetches the URL directly[[FLAT_TRIM]] (mirrors UI's "Import Code from Website" then Save flow)[[/FLAT_TRIM]]
- resave -- recompile without changes (on-hub only, no source touched)

Auto-backs up before modifying. Requires Write master + confirm + backup <24h.

Self-update guard: refuses to overwrite the MCP server's own app source unless Developer Mode is on.[[FLAT_TRIM]] Optional expectedVersion arg enables optimistic locking. Optional triggerUpdated arg fires updated() on a named instance after save. A bad self-update bricks the MCP loop. UI Save does NOT fire updated() -- triggerUpdated is opt-in only.

OAuth: pass oauth={enabled:true} to enable OAuth on the app code definition (apps only) -- the programmatic version of the UI's "Enable OAuth in App" -- and get the generated clientId/clientSecret back under result.oauth. Use it to ship apps with OAuth already on instead of the manual UI step. Runs alone (no source change) or alongside a source update.[[/FLAT_TRIM]]""",
            inputSchema: [
                type: "object",
                properties: [
                    appId: [type: "string", description: "The app ID (Apps Code id) to update"],
                    source: [type: "string", description: "Inline Groovy source. Stubs only -- fills agent transcript."],
                    sourceFile: [type: "string", description: "File Manager filename. Upload first via curl per tool description."],
                    importUrl: [type: "string", description: "URL the hub fetches directly (http:// or https://). Mirrors UI's Import Code from Website + Save."],
                    resave: [type: "boolean", description: "Re-save the current source code without changes. Runs entirely on-hub."],
                    expectedVersion: [type: "integer", description: "OPTIONAL optimistic-lock guard. Aborts with conflict:true on version mismatch.[[FLAT_TRIM]] Stringified integers coerced; explicit null rejected.[[/FLAT_TRIM]]"],
                    triggerUpdated: [type: "integer", description: "OPTIONAL post-save lifecycle refresh. Set to the running instance appId; fires updated() so subscriptions/schedules re-initialize.[[FLAT_TRIM]] Default: omitted (matches UI behavior; UI does NOT fire updated() on save).[[/FLAT_TRIM]]"],
                    oauth: [type: "object", description: "OPTIONAL: enable/configure OAuth on this app (apps only).[[FLAT_TRIM]] Returns the generated clientId/secret. {enabled (bool, default true), client_id?, client_secret?, refresh_secret? (bool, regenerate the secret)}. Omit client_id/client_secret to preserve the app's current values (the hub generates them on first enable). The resulting clientId/clientSecret come back under result.oauth. Can be the only change (no source mode required) or run with a source update. Refuses to alter the MCP server's own app OAuth unless Developer Mode is on (it backs the live /mcp token).[[/FLAT_TRIM]]"],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms backup was created and user approved."]
                ],
                required: ["appId", "confirm"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the update succeeded"],
                    message: [type: "string", description: "Human-readable result"],
                    appId: [description: "App ID updated"],
                    previousVersion: [description: "Version prior to the update"],
                    sourceMode: [type: "string", description: "Source mode used: source / sourceFile / importUrl / resave"],
                    sourceLength: [type: "integer", description: "Chars written"],
                    note: [type: "string", description: "Source-mode / recovery guidance"],
                    triggerUpdated: [description: "Instance appId updated() was fired on; present when requested"],
                    updatedFired: [type: "boolean", description: "Whether updated() fired on the instance"],
                    partial: [type: "boolean", description: "Code saved but a follow-on leg failed -- the OAuth update, or the opt-in lifecycle refresh"],
                    repairHints: [type: "array", description: "Recovery steps; present on partial/lifecycle failure", items: [type: "string"]],
                    expectedVersion: [type: "integer", description: "Optimistic-lock expected version; present on conflict"],
                    currentVersion: [type: "integer", description: "Hub's actual version; present on conflict"],
                    conflict: [type: "boolean", description: "True when an optimistic-lock conflict aborted the update"],
                    oauth: [type: "object", description: "OAuth update result; present when the oauth arg was supplied. {success, enabled, clientId, clientSecret}. clientId/clientSecret are the resulting (possibly hub-generated) credentials."],
                    lastBackup: [type: "string", description: "Timestamp of most recent backup"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_update_driver",
            description: """⚠️ CRITICAL: Modify existing driver code. Read current source first, explain changes, get confirmation.

Four source modes (mutually exclusive per item):
- source (inline) -- stubs only
- sourceFile -- File Manager filename[[FLAT_TRIM]] (upload first via curl -F uploadFile=@./X.groovy -F folder=/ http://<hub>/hub/fileManager/upload)[[/FLAT_TRIM]]
- importUrl -- hub fetches the URL directly
- resave -- recompile without changes (on-hub only)

For >1 driver: USE BULK mode (single round-trip): updates=[{driverId, source|sourceFile|importUrl|resave, optional expectedVersion}, ...].[[FLAT_TRIM]] Cannot mix bulk and single-driver fields. Continue-on-error.[[/FLAT_TRIM]]

Auto-backs up before modifying. Requires Write master + confirm + backup <24h.""",
            inputSchema: [
                type: "object",
                properties: [
                    driverId: [type: "string", description: "The driver ID to update (single-driver mode). Omit when using 'updates' array."],
                    source: [type: "string", description: "Inline Groovy source. Stubs only -- fills agent transcript."],
                    sourceFile: [type: "string", description: "File Manager filename. Upload first via curl per tool description."],
                    importUrl: [type: "string", description: "URL the hub fetches directly. http:// or https://.[[FLAT_TRIM]] Mutually exclusive with source/sourceFile/resave.[[/FLAT_TRIM]]"],
                    resave: [type: "boolean", description: "Re-save the current source without changes. Runs entirely on-hub."],
                    expectedVersion: [type: "integer", description: "OPTIONAL optimistic-lock guard.[[FLAT_TRIM]] Update aborts with conflict:true on version mismatch.[[/FLAT_TRIM]] Bulk mode: put expectedVersion inside each updates[] entry."],
                    updates: [
                        type: "array",
                        description: "BULK MODE -- one round-trip for many drivers.[[FLAT_TRIM]] Each entry: {driverId, sourceFile|source|importUrl|resave, optional expectedVersion}. Cannot mix with single-driver fields. Continue-on-error.[[/FLAT_TRIM]]",
                        items: [
                            type: "object",
                            properties: [
                                driverId: [type: "string", description: "The driver ID to update"],
                                sourceFile: [type: "string", description: "File Manager filename (preferred)."],
                                source: [type: "string", description: "Inline source (stubs only)."],
                                importUrl: [type: "string", description: "URL the hub fetches directly."],
                                resave: [type: "boolean", description: "Re-save without changes."],
                                expectedVersion: [type: "integer", description: "OPTIONAL optimistic-lock guard for this item only."]
                            ],
                            required: ["driverId"]
                        ]
                    ],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms backup was created and user approved."]
                ],
                required: ["confirm"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the update (or all bulk updates) succeeded"],
                    message: [type: "string", description: "Human-readable result"],
                    driverId: [description: "Driver ID updated (single-driver mode)"],
                    previousVersion: [description: "Version prior to the update (single-driver mode)"],
                    sourceMode: [type: "string", description: "Source mode used (single-driver mode)"],
                    sourceLength: [type: "integer", description: "Chars written (single-driver mode)"],
                    note: [type: "string", description: "Source-mode / recovery guidance"],
                    conflict: [type: "boolean", description: "Optimistic-lock conflict aborted the update (single-driver mode)"],
                    expectedVersion: [type: "integer", description: "Expected version on conflict (single-driver mode)"],
                    currentVersion: [type: "integer", description: "Hub's actual version on conflict (single-driver mode)"],
                    updates: [type: "array", description: "Per-driver results (bulk mode)", items: [type: "object", properties: [
                        driverId: [type: "string", description: "Driver ID"],
                        success: [type: "boolean", description: "Whether this driver updated"],
                        sourceMode: [type: "string", description: "Source mode used"],
                        sourceLength: [type: "integer", description: "Chars written"],
                        error: [type: "string", description: "Failure reason; present when success=false"],
                        note: [type: "string", description: "Per-item guidance"],
                        conflict: [type: "boolean", description: "Optimistic-lock conflict for this item"],
                        expectedVersion: [type: "integer", description: "Expected version on conflict"],
                        currentVersion: [type: "integer", description: "Hub's actual version on conflict"]
                    ]]],
                    lastBackup: [type: "string", description: "Timestamp of most recent backup"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_delete_item",
            description: """⚠️ DESTRUCTIVE: Permanently delete an app, driver, or library. Auto-backs up the source before deletion.

Pre-flight by type: apps -- remove app instances via the Hubitat UI first; drivers -- switch any devices to a different driver first; libraries -- ensure no apps/drivers still #include it (deleting an included library causes compile errors).

Tell the user the item name/ID, warn it's permanent, get confirmation. Requires Write master + confirm + backup <24h.""",
            inputSchema: [
                type: "object",
                properties: [
                    type: [type: "string", enum: ["app", "driver", "library"], description: "What to delete."],
                    id: [type: "string", description: "The app/driver/library ID to delete."],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms backup was created and user approved."]
                ],
                required: ["type", "id", "confirm"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the deletion succeeded"],
                    message: [type: "string", description: "Human-readable result, including backup status"],
                    appId: [description: "Deleted app ID (type='app')"],
                    driverId: [description: "Deleted driver ID (type='driver')"],
                    libraryId: [description: "Deleted library ID (type='library')"],
                    backupFile: [type: "string", description: "Pre-delete backup filename"],
                    restoreHint: [type: "string", description: "How to recover the deleted item"],
                    backupWarning: [type: "string", description: "Present when the pre-delete backup could not be created"],
                    lastBackup: [type: "string", description: "Timestamp of most recent backup"]
                ],
                required: ["success"]
            ]
        ],
        // Hub Admin Library Management Tools
        [
            name: "hub_create_library",
            description: """⚠️ Install new Groovy library code. Libraries are #include'd by drivers/apps. Show code to user and get confirmation first.

Three source modes (mutually exclusive):
- source (inline) -- stubs only
- sourceFile -- File Manager filename (upload first via curl -F uploadFile=@./X.groovy -F folder=/ http://<hub>/hub/fileManager/upload)
- importUrl -- hub fetches the URL directly

Library source must include a library() definition block. Requires Write master + confirm + backup <24h. Returns new libraryId.[[FLAT_TRIM]] Required fields: name, namespace, author, description. The hub does NOT compile-check libraries at install time -- syntax errors only surface later when an app or driver tries to #include the library.[[/FLAT_TRIM]]""",
            inputSchema: [
                type: "object",
                properties: [
                    source: [type: "string", description: "Inline source. Stubs only -- fills agent transcript.[[FLAT_TRIM]] Must include library() block with name/namespace/author/description.[[/FLAT_TRIM]]"],
                    sourceFile: [type: "string", description: "File Manager filename. Upload first via curl per tool description."],
                    importUrl: [type: "string", description: "URL the hub fetches directly. http:// or https://. Mutually exclusive with source/sourceFile."],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms backup was created and user approved."]
                ],
                required: ["confirm"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the library installed"],
                    message: [type: "string", description: "Human-readable result"],
                    libraryId: [description: "New library ID"],
                    version: [description: "Library version"],
                    sourceMode: [type: "string", description: "Source mode used: source / sourceFile / importUrl"],
                    sourceLength: [type: "integer", description: "Chars installed"],
                    verified: [type: "boolean", description: "Post-install verification passed"],
                    verifyError: [type: "string", description: "Verification fetch error; present when verify could not run"],
                    note: [type: "string", description: "Recovery guidance"],
                    lastBackup: [type: "string", description: "Timestamp of most recent backup"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_update_library",
            description: """⚠️ CRITICAL: Modify existing library code. Read current source first, explain changes, get confirmation.

Four source modes (mutually exclusive):
- source (inline) -- stubs only
- sourceFile -- File Manager filename (upload first via curl -F uploadFile=@./X.groovy -F folder=/ http://<hub>/hub/fileManager/upload)
- importUrl -- hub fetches the URL directly
- resave -- recompile without changes (on-hub only)

Auto-backs up before modifying. Requires Write master + confirm + backup <24h.""",
            inputSchema: [
                type: "object",
                properties: [
                    libraryId: [type: "string", description: "The library ID to update"],
                    source: [type: "string", description: "Inline source. Stubs only -- fills agent transcript."],
                    sourceFile: [type: "string", description: "File Manager filename. Upload first via curl per tool description."],
                    importUrl: [type: "string", description: "URL the hub fetches directly. http:// or https://. Mutually exclusive with source/sourceFile/resave."],
                    resave: [type: "boolean", description: "Re-save the current source without changes. Runs entirely on-hub."],
                    confirm: [type: "boolean", description: "REQUIRED: Must be true. Confirms backup was created and user approved."]
                ],
                required: ["libraryId", "confirm"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the update succeeded"],
                    message: [type: "string", description: "Human-readable result"],
                    libraryId: [description: "Library ID updated"],
                    previousVersion: [description: "Version prior to the update"],
                    newVersion: [description: "Version after the update"],
                    sourceMode: [type: "string", description: "Source mode used: source / sourceFile / importUrl / resave"],
                    sourceLength: [type: "integer", description: "Chars written"],
                    note: [type: "string", description: "Source-mode / recovery guidance"],
                    lastBackup: [type: "string", description: "Timestamp of most recent backup"]
                ],
                required: ["success"]
            ]
        ],
        // Hub Admin App Configuration Read (grouped with installed-apps peers)
        [
            name: "hub_get_app_config",
            description: """Read an installed app's configuration — the same structured data the Hubitat Web UI shows on each app's settings page. Works for Rule Machine rules, Room Lighting instances, Basic Rules, Button Controllers, Hubitat Package Manager, Mode Manager, and any other legacy SmartApp.

Returns the app's identity (label, type, parent, disabled state) and its current config page: sections, inputs (name, type, title, description, options, current value), and `embeddedActions` — clickable button affordances embedded in paragraph HTML[[FLAT_TRIM]] (RM 5.1 wizards expose "Create New Trigger", "Edit Trigger", "Delete Trigger" etc. as `<div class='submitOnChange'>` elements rather than schema inputs; this field surfaces them with their button name + stateAttribute so hub_set_rule can drive them)[[/FLAT_TRIM]]. Multi-page apps (e.g. RM 5.1) expose sub-pages by name — pass pageName to navigate into them. Read-only; does not modify anything. summary=true: fast identity-only mode.

Get the appId from hub_list_apps (scope='instances') or hub_list_rules; for multi-page apps pass pageName (hub_list_app_pages discovers available names).[[FLAT_TRIM]] Use hub_list_rules, not hub_get_custom_rule, for RM rules (hub_get_custom_rule only handles MCP-native rules).[[/FLAT_TRIM]]

Requires Read master.""",
            inputSchema: [
                type: "object",
                properties: [
                    appId: [type: "string", description: "Installed-app ID (decimal). From hub_list_apps (scope='instances'), hub_list_rules, or the numeric id in the Hubitat UI URL (/installedapp/configure/<id>)."],
                    pageName: [type: "string", description: "Optional sub-page name for multi-page apps. Main page is used when omitted. Call hub_list_app_pages to discover available pages.[[FLAT_TRIM]] HPM: prefPkgUninstall (full installed-package list), prefPkgModify (modifiable subset), prefOptions (main menu). RM / Room Lighting: mainPage only.[[/FLAT_TRIM]]"],
                    includeSettings: [type: "boolean", description: "Include the raw app-internal settings key-value map. Default false -- large apps can have 500-1000 keys with app-specific encoding (e.g. Room Lighting's dm~<deviceId>~<scene>). Set true only for power-user inspection.", default: false],
                    summary: [type: "boolean", description: "Fast identity-only read (returns the hub's thin app record: id, name, type, disabled, user -- no config page). pageName/includeSettings are ignored.", default: false]
                ],
                required: ["appId"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the config was read"],
                    id: [description: "summary=true: app ID"],
                    name: [type: "string", description: "summary=true: app name/label"],
                    type: [type: "string", description: "summary=true: app type name"],
                    disabled: [type: "boolean", description: "summary=true: app is disabled"],
                    user: [type: "boolean", description: "summary=true: user-installed app (false=built-in)"],
                    app: [type: "object", description: "App identity (full mode; summary mode passes the thin record through at top level)", properties: [
                        id: [description: "App ID"],
                        label: [type: "string", description: "User-visible label"],
                        name: [type: "string", description: "App type name"],
                        appType: [type: "object", description: "App-type metadata (name, namespace, author, etc.)"],
                        disabled: [type: "boolean", description: "App is disabled"],
                        parentAppId: [description: "Parent app ID, when a child"],
                        installed: [type: "boolean", description: "App is installed"]
                    ]],
                    page: [type: "object", description: "The config page (full mode only)", properties: [
                        name: [type: "string", description: "Page name"],
                        title: [type: "string", description: "Page title"],
                        install: [type: "boolean", description: "Page is the install page"],
                        refreshInterval: [description: "Auto-refresh interval"],
                        sections: [type: "array", description: "Page sections", items: [type: "object", properties: [
                            title: [type: "string", description: "Section title"],
                            inputs: [type: "array", description: "Input fields", items: [type: "object", properties: [
                                name: [type: "string", description: "Setting name"],
                                type: [type: "string", description: "Input type"],
                                title: [type: "string", description: "Input title"],
                                multiple: [type: "boolean", description: "Accepts multiple values"],
                                required: [type: "boolean", description: "Input is required"],
                                description: [type: "string", description: "Input description"],
                                options: [description: "Allowed values, when an enum/capability picker"],
                                value: [description: "Current configured value"]
                            ]]],
                            paragraphs: [type: "array", description: "Informational text blocks", items: [type: "string"]],
                            embeddedActions: [type: "array", description: "Clickable wizard affordances embedded in HTML", items: [type: "object"]]
                        ]]]
                    ]],
                    childApps: [type: "array", description: "Child apps", items: [type: "object", properties: [
                        id: [description: "Child app ID"],
                        label: [type: "string", description: "Child label"],
                        name: [type: "string", description: "Child type name"]
                    ]]],
                    endpoint: [type: "string", description: "Internal API endpoint used"],
                    settingsKeyCount: [type: "integer", description: "Number of raw settings keys"],
                    settings: [type: "object", description: "Raw app-internal settings; present when includeSettings=true"],
                    settingsNote: [type: "string", description: "Note when raw settings were omitted"]
                ],
                required: ["success"]
            ]
        ],
        // Hub Admin App Pages Directory
        [
            name: "hub_list_app_pages",
            description: """List known page names for a multi-page installed app. Returns the primary page (introspected live from the hub) plus a curated directory of known sub-pages for well-known app types.

[[FLAT_TRIM]]
Curated directories: HPM (prefOptions main menu, prefPkgUninstall full installed-package list, prefPkgModify modifiable subset, prefPkgInstall install flow, prefPkgMatchUp match-up flow); Rule Machine rules (mainPage only -- rules are single-page); Room Lighting (mainPage); Mode Manager (mainPage).
[[/FLAT_TRIM]]

Unknown app types return the primary page only, plus a note directing you to consult the app's source or Web UI navigation for additional page names.

Use this before hub_get_app_config on multi-page apps to avoid guessing page names.

Requires Read master.""",
            inputSchema: [
                type: "object",
                properties: [
                    appId: [type: "string", description: "Installed-app ID (decimal). From hub_list_apps (scope='instances'), hub_list_rules, or the Hubitat UI URL (/installedapp/configure/<id>)."]
                ],
                required: ["appId"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the page list was built"],
                    app: [type: "object", description: "App identity", properties: [
                        id: [description: "App ID"],
                        label: [type: "string", description: "User-visible label"],
                        name: [type: "string", description: "App type name"],
                        appTypeName: [type: "string", description: "App-type display name"]
                    ]],
                    primaryPage: [type: "object", description: "Live-introspected primary page", properties: [
                        name: [type: "string", description: "Page name"],
                        title: [type: "string", description: "Page title"],
                        role: [type: "string", description: "Page role"]
                    ]],
                    pages: [type: "array", description: "Known page directory", items: [type: "object", properties: [
                        name: [type: "string", description: "Page name"],
                        title: [type: "string", description: "Page title"],
                        role: [type: "string", description: "Page role"]
                    ]]],
                    note: [type: "string", description: "Guidance for uncurated or single-page app types"]
                ],
                required: ["success", "pages"]
            ]
        ],
        // Installed Apps Integration (built-in + user app visibility)
        [
            name: "hub_list_device_dependents",
            description: """List all apps that reference a specific device (Room Lighting instances, Rule Machine rules, Groups and Scenes, Mode Manager, dashboards, Maker API, Echo Skill, etc.). Requires the Read master.

Answers \"which apps would break or change behavior if I disable/delete this device?\" — critical before device cleanup, troubleshooting, or reassignment.
[[FLAT_TRIM]]
Returns: deviceId, deviceName, appsUsing array (each entry: id, name=app type, label=user-visible name, trueLabel=label without HTML decoration, disabled), count, parentApp.
[[/FLAT_TRIM]]""",
            inputSchema: [
                type: "object",
                properties: [
                    deviceId: [type: "string", description: "Device ID from hub_list_devices"],
                    cursor: [type: "string", description: "Opt-in pagination cursor for the appsUsing list. Omit for unbounded; pass \"\" for the first page, iterate nextCursor (page size 100)."]
                ],
                required: ["deviceId"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    deviceId: [type: "string", description: "Device queried"],
                    deviceName: [type: "string", description: "Device display name"],
                    appsUsing: [type: "array", description: "Apps referencing this device", items: [type: "object", properties: [
                        id: [description: "App ID"],
                        name: [type: "string", description: "App type name (e.g. Room Lights, Rule-5.1)"],
                        label: [type: "string", description: "User-visible label (may contain HTML)"],
                        trueLabel: [type: "string", description: "Label stripped of HTML"],
                        disabled: [type: "boolean", description: "App is disabled"]
                    ]]],
                    count: [type: "integer", description: "Number of apps using the device"],
                    parentApp: [description: "Parent app reference, when present"],
                    countMismatch: [type: "string", description: "Present when firmware count disagrees with the array length"],
                    total: [type: "integer", description: "Total matched (present when paginating)"],
                    nextCursor: [type: "string", description: "Present when more results remain"]
                ],
                required: ["deviceId", "appsUsing", "count"]
            ]
        ],
    ]
}

def _readOnlyToolNames_partCodeManagement() {
    // Read-only classification membership for this library's tools, contributed to the
    // app's getReadOnlyToolNames() aggregator (issue #209: per-tool metadata lives with
    // the tool). A tool absent from every part list is write+destructive by default.
    return [
        // Apps/drivers (read)
        "hub_list_apps", "hub_list_drivers", "hub_list_libraries", "hub_get_source",
        // Installed apps (read) -- instances list folded into hub_list_apps(scope='instances').
        "hub_list_device_dependents", "hub_get_app_config", "hub_list_app_pages"
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
        hub_get_app_config: [title: "Get App Configuration", summary: "Read an installed app's configuration page."],
        hub_list_app_pages: [title: "List App Pages", summary: "List known page names for a multi-page app."]
    ]
}
