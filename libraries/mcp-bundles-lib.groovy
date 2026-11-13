library(name: "McpBundlesLib", namespace: "mcp", author: "kingpanther13", description: "Bundle tools for the MCP Rule Server (hub_install_bundle, hub_list_bundles, hub_export_bundle, hub_delete_bundle); included by the main app. Gateway entries and dispatch stay in the app; tool definitions live here alongside the impl.")

private Map _parseBundleContent(String content) {
    // Parse the hub's bundle content summary ("apps [A, B], drivers [], libraries [L]") into
    // [apps:[...], drivers:[...], libraries:[...]]. Returns null if the shape is unrecognized so
    // the caller surfaces the raw string instead of a wrong parse.
    if (!content) return null
    def out = [apps: [], drivers: [], libraries: []]
    boolean matched = false
    ["apps", "drivers", "libraries"].each { key ->
        def token = "${key} ["
        int s = content.indexOf(token)
        if (s >= 0) {
            int open = s + token.length() - 1
            int close = content.indexOf("]", open)
            if (close > open) {
                matched = true
                def inner = content.substring(open + 1, close).trim()
                out[key] = inner ? inner.split(/\s*,\s*/).collect { it.trim() }.findAll { it } : []
            }
        }
    }
    return matched ? out : null
}

def toolListBundles(args) {
    // List the code bundles installed on the hub (the Bundles page -- distinct from Libraries Code).
    // GET /hub2/userBundles -> [{id, name, namespace, private, content}]; mirrors toolListLibraries'
    // source/degraded-shape handling. Read-only.
    def cursor = args?.cursor
    def result = [:]
    try {
        def responseText = hubInternalGet("/hub2/userBundles")
        if (responseText) {
            try {
                def parsed = new groovy.json.JsonSlurper().parseText(responseText)
                if (parsed instanceof List) {
                    // Defensive: skip any null / non-map element the hub API might return so a
                    // single malformed row can't NPE the whole listing.
                    result.bundles = parsed.findAll { it instanceof Map }.collect { b ->
                        def entry = [
                            id: b.id?.toString(),
                            name: b.name,
                            namespace: b.namespace,
                            "private": (b["private"] == true)
                        ]
                        def contains = _parseBundleContent(b.content?.toString())
                        if (contains != null) entry.contains = contains
                        else if (b.content != null) entry.content = b.content?.toString()
                        entry
                    }
                    result.count = result.bundles.size()
                    result.source = "hub_api"
                    def noId = result.bundles.count { it.id == null }
                    if (noId > 0) {
                        result.note = "${noId} bundle${noId == 1 ? '' : 's'} returned by the hub had no id and cannot be targeted by hub_delete_bundle / hub_export_bundle (possible firmware shape change)."
                    }
                } else {
                    result.bundles = []
                    result.count = 0
                    result.rawResponse = responseText?.take(2000)
                    result.source = "hub_api_raw"
                    result.note = "Response was not a JSON array. This endpoint may return a different shape on your firmware version."
                }
            } catch (Exception parseErr) {
                result.bundles = []
                result.count = 0
                result.rawResponse = responseText?.take(2000)
                result.source = "hub_api_raw"
                result.note = "Response was not JSON. This endpoint may return HTML on your firmware version."
            }
        } else {
            result.bundles = []
            result.count = 0
            result.source = "unavailable"
            result.note = "Empty response from hub API"
        }
    } catch (Exception e) {
        mcpLog("warn", "hub-admin", "hub_list_bundles API call failed: ${e.message}")
        result.bundles = []
        result.count = 0
        result.source = "unavailable"
        result.note = "Hub internal API unavailable (${e.message}). This may require Hub Security credentials or a firmware update."
    }

    if (cursor != null && result.bundles instanceof List) {
        def paged = _paginateList(result.bundles, cursor, 50, "hub_list_bundles")
        result.total = result.bundles.size()
        result.bundles = paged.page
        result.count = paged.page.size()
        if (paged.nextCursor != null) result.nextCursor = paged.nextCursor
    }

    mcpLog("info", "hub-admin", "Listed hub bundles (source: ${result.source})")
    return result
}

def toolExportBundle(args) {
    // Export a bundle's .zip to the hub File Manager. GET /bundle/export/<id> returns application/zip;
    // fetched as BINARY (textParser:false -- the text hubInternal* helpers would corrupt the zip) and
    // saved via uploadHubFile. A write (adds a file) but not destructive -- matches
    // hub_export_custom_rule gating (Write master, no confirm). Downloadable at /local/<fileName>.
    String bundleId = _bundleIdArg(args)

    // The list only supplies the default filename; with saveAs given, the HTTP status and PK
    // checks below already catch an id the hub does not have.
    Map target = null
    String baseName = args?.saveAs?.toString()?.trim()
    if (!baseName) {
        def listed = toolListBundles([:])
        for (b in (listed.bundles ?: [])) {
            if (b.id == bundleId) { target = b; break }
        }
        if (listed.source == "hub_api" && !target) {
            return [success: false, error: "No bundle with id ${bundleId} found on the hub. Use hub_list_bundles to see installed bundles.", bundleId: bundleId]
        }
        baseName = target?.name?.toString()?.trim() ?: "bundle-${bundleId}"
    }
    def fileName = baseName.replaceAll(/[^A-Za-z0-9._-]/, "_")
    if (!fileName.toLowerCase().endsWith(".zip")) fileName += ".zip"

    def zipBytes = null
    def httpStatus = null
    def unexpectedBodyDesc = null
    try {
        def cookie = getHubSecurityCookie()
        def params = [
            uri: hubBaseUri(),
            path: "/bundle/export/${bundleId}",
            textParser: false,
            ignoreSSLIssues: true,
            timeout: 120
        ]
        if (cookie) params.headers = [Cookie: cookie]
        httpGet(params) { resp ->
            httpStatus = resp?.status
            def d = resp?.data
            if (d instanceof byte[]) {
                zipBytes = d
            } else if (d instanceof CharSequence) {
                // A text body (e.g. an HTML error/login page) -- not a zip.
                unexpectedBodyDesc = "likely an HTML error page"
            } else if (d != null) {
                // Binary body delivered as a stream (httpGet textParser:false). Read its bytes via
                // the .bytes property WITHOUT naming java.io.InputStream -- referencing that class is
                // blocked by the Hubitat sandbox (ClassExpression not allowed). Guard the result: a
                // non-stream object (e.g. a parsed Map) resolves `.bytes` to a Groovy key lookup -> null
                // (no exception), so only accept an actual non-empty byte[]; anything else is unknown.
                def streamBytes = null
                try {
                    streamBytes = d.bytes
                } catch (Exception streamErr) {
                    streamBytes = null
                }
                if (streamBytes instanceof byte[] && streamBytes.length > 0) {
                    zipBytes = streamBytes
                } else {
                    unexpectedBodyDesc = "an unrecognized type"
                }
            }
        }
    } catch (Exception e) {
        mcpLog("warn", "hub-admin", "hub_export_bundle fetch threw: ${e.toString()}")
        return [success: false, error: "Failed to fetch bundle ${bundleId} export zip: ${e.message ?: e.toString()}", bundleId: bundleId]
    }
    // A non-2xx response (e.g. a 404/500 error PAGE) or a body that doesn't start with the ZIP
    // signature ('PK') means the hub did not return a real bundle zip. Fail WITHOUT saving rather
    // than writing a corrupt .zip and reporting success.
    if (httpStatus != null && !(httpStatus >= 200 && httpStatus < 300)) {
        return [success: false, error: "Bundle ${bundleId} export returned HTTP ${httpStatus} -- not a bundle zip. Nothing saved.", bundleId: bundleId, status: httpStatus]
    }
    if (unexpectedBodyDesc) {
        return [success: false, error: "Bundle ${bundleId} export returned a non-binary body (${unexpectedBodyDesc}); nothing saved -- a binary zip was expected.", bundleId: bundleId]
    }
    if (!zipBytes || zipBytes.length == 0) {
        return [success: false, error: "Bundle ${bundleId} export returned no data (the bundle id may not exist on the hub).", bundleId: bundleId]
    }
    if (!(zipBytes.length >= 2 && zipBytes[0] == (byte) 0x50 && zipBytes[1] == (byte) 0x4B)) {
        return [success: false, error: "Bundle ${bundleId} export did not return a ZIP (no PK signature) -- the hub likely returned an error page. Nothing saved.", bundleId: bundleId]
    }
    try {
        uploadHubFile(fileName, zipBytes)
    } catch (Exception e) {
        return [success: false, error: "Fetched bundle ${bundleId} (${zipBytes.length} bytes) but saving to File Manager as ${fileName} failed: ${e.message ?: e.toString()}", bundleId: bundleId]
    }

    mcpLog("info", "hub-admin", "Exported bundle ${bundleId} to File Manager as ${fileName} (${zipBytes.length} bytes)")
    return [
        success: true,
        message: "Bundle ${bundleId}${target?.name ? " ('${target.name}')" : ''} exported to the File Manager as ${fileName} (${zipBytes.length} bytes). Download at /local/${fileName}.",
        bundleId: bundleId,
        fileName: fileName,
        bytes: zipBytes.length,
        directDownload: "/local/${fileName}"
    ]
}

// The hub call behind a bundle install, as HPM's installBundle() makes it: the hub fetches the zip
// and unpacks it into Apps/Drivers/Libraries Code. Firmware >= 2.3.8.108 -> GET
// /bundle2/uploadZipFromUrl, older -> POST /bundle/uploadZipFromUrl. Never throws: returns
// [endpoint, ok, raw, error]. It makes no confirm check of its own.
Map _installBundleFromUrl(String url, boolean installer) {
    String fw = null
    try { fw = location?.hub?.firmwareVersionString?.toString() } catch (Exception ignored) { }
    if (!fw) mcpLog("warn", "hub-admin", "Bundle install: could not read firmware version; defaulting bundle endpoint to modern (/bundle2/uploadZipFromUrl)")
    // Numeric segment compare: a string compare would sort "2.3.10.0" below "2.3.8.0".
    boolean modern = _firmwareAtLeast(fw, "2.3.8.108")
    String endpoint = modern ? "/bundle2/uploadZipFromUrl" : "/bundle/uploadZipFromUrl"

    mcpLog("info", "hub-admin", "Installing bundle (endpoint: ${endpoint}, fw: ${fw}, installer: ${installer}, url: ${url})")
    def resp
    try {
        if (modern) {
            // `private` is quoted because it is a Groovy keyword. 300s matches HPM's bundle install.
            resp = hubInternalGet("/bundle2/uploadZipFromUrl", [url: url, pwd: "", "private": installer.toString()], 300)
        } else {
            resp = hubInternalPostJson("/bundle/uploadZipFromUrl", groovy.json.JsonOutput.toJson([url: url, installer: installer, pwd: ""]))
        }
    } catch (Exception e) {
        mcpLog("warn", "hub-admin", "Bundle install threw: ${e.toString()}")
        return [endpoint: endpoint, ok: false, raw: null, error: "Bundle install failed: ${e.message ?: e.toString()}".toString()]
    }
    if (!_bundleResponseSucceeded(resp)) {
        mcpLog("warn", "hub-admin", "Bundle install did not report success (endpoint ${endpoint})")
        String hubMessage = _bundleResponseMessage(resp)
        return [endpoint: endpoint, ok: false, raw: resp,
                error: hubMessage ? "Bundle install failed: ${hubMessage}".toString()
                        : "Bundle install failed: the hub returned no success signal. The zip may be malformed or unreachable, or the firmware endpoint unavailable."]
    }
    mcpLog("info", "hub-admin", "Bundle installed from ${url}")
    return [endpoint: endpoint, ok: true, raw: resp, error: null]
}

// hub_install_bundle. The install response carries no bundle id, so the id is the one that appears
// between a list read before and after. A list that cannot be read only costs the id: it never
// turns an install the hub reported as done into a failure.
def toolInstallBundle(args) {
    requireDestructiveConfirm(args.confirm)
    String url = (args.importUrl instanceof String) ? args.importUrl.trim() : ""
    if (!url) {
        throw new IllegalArgumentException("importUrl is required: the URL of the bundle .zip the hub fetches and installs.")
    }
    String lower = url.toLowerCase()
    if (!(lower.startsWith("http://") || lower.startsWith("https://"))) {
        throw new IllegalArgumentException("importUrl scheme must be http or https (got '${url.take(40)}')")
    }
    if (args.installer != null && !(args.installer instanceof Boolean)) {
        throw new IllegalArgumentException("installer must be true or false (got '${args.installer.toString().take(40)}').")
    }
    boolean installer = (args.installer == true)

    def before = toolListBundles([:])
    Set beforeIds = (before.source == "hub_api") ? ((before.bundles*.id) as Set) : null

    Map r = _installBundleFromUrl(url, installer)
    if (!r.ok) {
        def failure = [success: false, error: r.error, endpoint: r.endpoint, lastBackup: formatTimestamp(state.lastBackupTimestamp)]
        if (r.raw != null) failure.rawResponse = r.raw.toString().take(500)
        else failure.note = "Whether the bundle was installed is unknown. Check hub_list_bundles before retrying."
        return failure
    }

    def out = [success: true, bundleId: null, endpoint: r.endpoint, installer: installer,
               lastBackup: formatTimestamp(state.lastBackupTimestamp)]
    String note = null
    if (beforeIds == null) {
        note = "The bundle list could not be read before the install, so the new bundle's id is unknown. hub_list_bundles shows it."
    } else {
        def after = toolListBundles([:])
        List fresh = []
        if (after.source == "hub_api") {
            for (b in after.bundles) {
                if (b.id != null && !beforeIds.contains(b.id)) fresh << b
            }
        }
        if (fresh.size() == 1) {
            out.bundleId = fresh[0].id
            out.bundle = fresh[0]
        } else if (after.source != "hub_api") {
            note = "The bundle list could not be read after the install, so the new bundle's id is unknown. hub_list_bundles shows it."
        } else {
            note = "No single new bundle id appeared (reinstalling a bundle keeps its id). hub_list_bundles shows it."
        }
    }
    out.message = "Bundle installed from ${url}. Its apps, drivers and libraries are in Code.".toString()
    if (note) out.note = note
    return out
}

private String _bundleCodeType(String containsKey) {
    return [apps: "app", drivers: "driver", libraries: "library"][containsKey]
}

// The rows behind hub_list_apps(scope='types') / hub_list_drivers / hub_list_libraries, or null
// when the list cannot be read.
private List _bundleCodeRows(String type) {
    try {
        def text = hubInternalGet([app: "/hub2/userAppTypes", driver: "/hub2/userDeviceTypes", library: "/hub2/userLibraries"][type])
        def parsed = text ? new groovy.json.JsonSlurper().parseText(text) : null
        return (parsed instanceof List) ? parsed : null
    } catch (Exception e) {
        mcpLog("warn", "hub-admin", "Bundle delete: could not read the ${type} code list: ${e.message}")
        return null
    }
}

// Each name in a bundle's contains summary, matched to one code id: name plus the bundle's
// namespace first, then the name alone when only one item has it.
private Map _resolveBundleCode(Map bundle) {
    List items = []
    List problems = []
    Map rowsByType = [:]
    String ns = bundle.namespace?.toString()
    for (String key in ["apps", "drivers", "libraries"]) {
        List names = (bundle["contains"][key] ?: []) as List
        if (names.isEmpty()) continue
        String type = _bundleCodeType(key)
        List rows = _bundleCodeRows(type)
        rowsByType.put(type, rows)
        for (name in names) {
            if (rows == null) {
                problems << [type: type, name: name, reason: "the ${type} code list could not be read".toString()]
                continue
            }
            List byName = []
            List byNs = []
            for (row in rows) {
                if (row instanceof Map && row.name?.toString() == name) {
                    byName << row
                    if (row.namespace?.toString() == ns) byNs << row
                }
            }
            List pick = byNs ?: byName
            if (pick.size() == 1 && pick[0].id != null) {
                items << [type: type, id: pick[0].id.toString(), name: name]
            } else {
                problems << [type: type, name: name, reason: (pick ? "${pick.size()} ${type}s have this name" : "no ${type} has this name").toString()]
            }
        }
    }
    return [items: items, problems: problems, types: rowsByType.keySet() as List]
}

// The bundleId argument as the hub's own integer form, so " 4", "04" and "4" all reach /bundle/.../4.
String _bundleIdArg(args) {
    String raw = args?.bundleId?.toString()?.trim()
    if (!raw) {
        throw new IllegalArgumentException("bundleId is required (the numeric bundle id from hub_list_bundles).")
    }
    if (!(raw ==~ /\d{1,10}/) || (raw as Long) <= 0) {
        throw new IllegalArgumentException("bundleId must be a positive integer (got '${raw.take(40)}'). Use hub_list_bundles to find it.")
    }
    return (raw as Long).toString()
}

def toolDeleteBundle(args) {
    requireDestructiveConfirm(args.confirm)
    String id = _bundleIdArg(args)
    if (args.deleteCode != null && !(args.deleteCode instanceof Boolean)) {
        throw new IllegalArgumentException("deleteCode must be true or false (got '${args.deleteCode.toString().take(40)}').")
    }
    return _deleteBundleItem(id, args.deleteCode == true)
}

// hub_delete_bundle. /bundle/deleteJson/<id> answers success for any id, so the
// bundle must be on a clean list read first. With deleteCode the hub also deletes the code the
// bundle delivered, except code still in use, which it keeps without saying so: the code lists
// are read again to report what went.
Map _deleteBundleItem(String id, boolean deleteCode) {
    def listed = toolListBundles([:])
    if (listed.source != "hub_api") {
        return [success: false, bundleId: id,
                error: "Could not read the bundle list (${listed.source}), so bundle ${id} was not deleted.".toString(),
                note: "The hub reports success for any bundle id, so the delete is sent only once the list shows the bundle. Retry when hub_list_bundles answers."]
    }
    Map target = null
    for (b in listed.bundles) {
        if (b.id == id) { target = b; break }
    }
    if (target == null) {
        return [success: false, bundleId: id, error: "No bundle with id ${id}".toString(),
                note: "Nothing was deleted. hub_list_bundles lists the bundle ids."]
    }
    String label = "Bundle ${id} ('${target.name}')".toString()

    List items = []
    List types = []
    List backupFiles = []
    if (deleteCode) {
        if (!(target["contains"] instanceof Map)) {
            return [success: false, bundleId: id,
                    error: "${label}: the hub's summary of what the bundle contains could not be read, so its code cannot be matched.".toString(),
                    content: target.content, note: "Nothing was deleted. Delete the bundle without deleteCode, then each item with hub_delete_item(type, item_id)."]
        }
        Map plan = _resolveBundleCode(target)
        if (plan.problems) {
            return [success: false, bundleId: id,
                    error: "${label}: not every item it delivered matches exactly one code id.".toString(),
                    unresolved: plan.problems,
                    note: "Nothing was deleted. Delete the bundle without deleteCode, then each item with hub_delete_item(type, item_id) using the ids from the code lists."]
        }
        items = plan.items
        types = plan.types
        for (item in items) {
            try {
                def entry = (item.type == "library") ? backupLibrarySource(item.id) : backupItemSource(item.type, item.id)
                if (entry?.fileName) backupFiles << entry.fileName
            } catch (Exception backupErr) {
                Map unavailable = _itemBackupIndexUnavailableResult(backupErr)
                if (unavailable != null) return unavailable + [bundleId: id]
                mcpLog("warn", "hub-admin", "Bundle ${id}: backup of ${item.type} ${item.id} failed: ${backupErr.message}")
                return [success: false, bundleId: id,
                        error: "Could not back up ${item.type} ${item.id} ('${item.name}'): ${backupErr.message}".toString(),
                        backupFiles: backupFiles, note: "Nothing was deleted."]
            }
        }
    }

    mcpLog("warn", "hub-admin", "Deleting bundle ${id} (${target.name}, deleteCode: ${deleteCode})")
    def responseText
    try {
        responseText = hubInternalGet("/bundle/deleteJson/${id}".toString(), deleteCode ? [full: "true"] : null)
    } catch (Exception e) {
        mcpLogError("hub-admin", "Bundle ${id} delete request failed", e)
        return [success: false, bundleId: id, error: "Bundle delete request failed: ${e.message}".toString(),
                backupFiles: backupFiles, note: "Whether ${label} was deleted is unknown. Check hub_list_bundles before retrying.".toString()]
    }
    def parsed = null
    try { parsed = responseText ? new groovy.json.JsonSlurper().parseText(responseText) : null } catch (Exception ignored) { }
    if (!(parsed instanceof Map) || !parsed.containsKey("success")) {
        return [success: false, bundleId: id, error: "Could not confirm the delete of ${label}: the hub's reply is not a recognized answer.".toString(),
                response: responseText?.toString()?.take(500), backupFiles: backupFiles, note: "Check hub_list_bundles to see whether the bundle is still there."]
    }
    if (parsed.success != true) {
        return [success: false, bundleId: id, error: (parsed.message ?: "The hub refused the delete").toString(),
                response: responseText.toString().take(500), backupFiles: backupFiles]
    }

    def out = [success: true, bundleId: id, name: target.name, deleteCode: deleteCode,
               lastBackup: formatTimestamp(state.lastBackupTimestamp)]
    if (!deleteCode) {
        out.message = "${label} deleted. The apps, drivers and libraries it delivered stay in Code: delete each with hub_delete_item(type, item_id).".toString()
        return out
    }

    Map after = [:]
    for (String t in types) after.put(t, _bundleCodeRows(t))
    List deleted = []
    List kept = []
    List unverified = []
    for (item in items) {
        List rows = after[item.type]
        if (rows == null) { unverified << item; continue }
        boolean present = false
        for (row in rows) {
            if (row instanceof Map && row.id?.toString() == item.id) { present = true; break }
        }
        if (present) kept << item
        else deleted << item
    }
    out.codeDeleted = deleted
    out.codeKept = kept
    out.backupFiles = backupFiles
    if (backupFiles) out.backupRetentionNote = _itemBackupRetentionNote()
    out.message = "${label} deleted with its code: ${deleted.size()} of ${items.size()} delivered item(s) deleted.".toString()
    List notes = []
    if (kept) notes << "The hub kept the code in codeKept because something still uses it (an installed app, a device, or code that #includes a library). Remove those app instances or switch those devices to another driver, then delete each item with hub_delete_item."
    if (unverified) {
        out.codeUnverified = unverified
        notes << "The code lists could not be read after the delete, so whether the items in codeUnverified were deleted is unknown. Check hub_list_apps(scope='types'), hub_list_drivers or hub_list_libraries."
    }
    if (notes) out.note = notes.join(" ")
    return out
}

def _bundleResponseSucceeded(resp) {
    // The bundle endpoints signal success as {"success":true} (bundle2 returns JSON; the older
    // endpoint is parsed by hubInternalPostJson). hubInternalGet hands back the raw body String,
    // so normalize both a parsed Map and a raw String/JSON; success only on an explicit truthy signal.
    if (resp == null) return false
    if (resp instanceof Map) return resp.success == true || resp.success?.toString() == "true"
    String text = resp.toString().trim()
    if (!text) return false
    try {
        def parsed = new groovy.json.JsonSlurper().parseText(text)
        if (parsed instanceof Map) return parsed.success == true || parsed.success?.toString() == "true"
    } catch (Exception ignored) { }
    return text.equalsIgnoreCase("true")
}

// The hub's own reason on a refused bundle call ({"success":false,"message":"Cannot retrieve zip file from ..."}).
String _bundleResponseMessage(resp) {
    def parsed = resp
    if (!(resp instanceof Map)) {
        try { parsed = new groovy.json.JsonSlurper().parseText(resp?.toString() ?: "") } catch (Exception ignored) { return null }
    }
    String msg = (parsed instanceof Map) ? parsed.message?.toString()?.trim() : null
    return msg ?: null
}

// Tool DEFINITIONS for the bundle tools (issue #209: schema lives with the impl). Concatenated
// into getAllToolDefinitions() in the main app; gateway membership + dispatch stay in main.
def _getAllToolDefinitions_partBundles() {
    return [
        [
            name: "hub_install_bundle",
            description: "Install a Hubitat code bundle (.zip) from a URL the way Hubitat Package Manager does -- the hub fetches the zip and unpacks it into Libraries/Apps/Drivers Code. Requires Write master + confirm=true + a recent backup; the hub does not deep-validate the zip. Returns the new bundle's id and contents.[[FLAT_TRIM]] The hub's reply has no id, so the bundle list is read before and after; when no single new id appears (a reinstall keeps its id) the install still succeeds with a note -- find it with hub_list_bundles. Uses /bundle2/uploadZipFromUrl on firmware >= 2.3.8.108, else legacy /bundle/uploadZipFromUrl.[[/FLAT_TRIM]][[FLAT_TRIM]] A transport drop can lose the response while the hub still commits this install; verify current hub state before retrying. See hub_get_tool_guide(section='slow_ops').[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    importUrl: [type: "string", description: "URL of the bundle .zip the hub fetches and installs (http:// or https://)."],
                    installer: [type: "boolean", description: "OPTIONAL. Mark the bundle's contents as installed-by-this-package (HPM's installer/private flag). Default false."],
                    confirm: [type: "boolean", description: "REQUIRED: must be true. Confirms a recent backup exists and the user approved installing this bundle."],
                ],
                required: ["importUrl", "confirm"]
            ]
        ],
        [
            name: "hub_list_bundles",
            description: "List installed code bundles -- the Bundle-Manager containers HPM delivers code in, distinct from Libraries Code.[[FLAT_TRIM]] Each entry: id, name, namespace, private flag, and a 'contains' summary of the apps/drivers/libraries it delivered.[[/FLAT_TRIM]] Use to find a bundle's id for hub_delete_bundle/hub_export_bundle, or to verify a bundle installed. Read-only.",
            inputSchema: [
                type: "object",
                properties: [
                    cursor: [type: "string", description: "Opt-in pagination cursor. Omit for unbounded; pass \"\" for the first page, iterate nextCursor (page size 50)."]
                ]
            ]
        ],
        [
            name: "hub_delete_bundle",
            description: "Delete an installed code bundle by id (find the id with hub_list_bundles). DESTRUCTIVE: requires Write master + confirm=true + a recent backup. The id must be on the bundle list, else nothing is deleted. The apps/drivers/libraries it delivered stay in Code unless deleteCode=true.[[FLAT_TRIM]] deleteCode=true backs up each delivered item first and deletes it with the bundle; the hub keeps code still in use (listed in codeKept). Deleting a library may break apps/drivers that #include it. See hub_get_tool_guide(section='bundles').[[/FLAT_TRIM]]",
            inputSchema: [
                type: "object",
                properties: [
                    bundleId: [type: "string", description: "The numeric bundle id from hub_list_bundles (e.g. \"4\")."],
                    deleteCode: [type: "boolean", description: "OPTIONAL. Also delete the apps/drivers/libraries the bundle delivered, each backed up first. Default false."],
                    confirm: [type: "boolean", description: "REQUIRED: must be true. Confirms a recent backup exists and the user approved deleting this bundle."]
                ],
                required: ["bundleId", "confirm"]
            ]
        ],
        [
            name: "hub_export_bundle",
            description: "Export an installed bundle's .zip to the hub File Manager (downloadable at /local/<fileName>; find the id with hub_list_bundles). A write (creates a File Manager file), not destructive -- needs Write master, no confirm.",
            inputSchema: [
                type: "object",
                properties: [
                    bundleId: [type: "string", description: "The numeric bundle id from hub_list_bundles (e.g. \"4\")."],
                    saveAs: [type: "string", description: "OPTIONAL File Manager filename for the exported .zip. Defaults to the bundle's name.[[FLAT_TRIM]] '.zip' is appended if missing; non-filename characters are replaced with '_'.[[/FLAT_TRIM]]"],
                ],
                required: ["bundleId"]
            ]
        ]
    ]
}

def _readOnlyToolNames_partBundles() {
    // Read-only classification membership for this library's tools, contributed to the
    // app's getReadOnlyToolNames() aggregator (issue #209: per-tool metadata lives with
    // the tool). A tool absent from every part list is write+destructive by default.
    return [
        "hub_list_bundles"
    ]
}

def _idempotentWriteToolNames_partBundles() {
    // Retry-safe writes (MCP idempotentHint) for this library's tools -- contributed to the
    // app's getIdempotentWriteToolNames() aggregator; see the classification rules there.
    return [
        // Code (apps/drivers/libraries/bundles/backups)
        "hub_install_bundle", "hub_delete_bundle", "hub_export_bundle"
    ]
}

def _openWorldToolNames_partBundles() {
    // Tools in this library that reach BEYOND the hub to the open internet (MCP
    // openWorldHint) -- contributed to the app's getOpenWorldToolNames() aggregator.
    return [
        "hub_install_bundle"
    ]
}

def _toolDisplayMeta_partBundles() {
    // Human-facing title/summary per tool (MCP annotations.title + the Advanced per-tool
    // overrides menu) -- merged into the app's getToolDisplayMeta() aggregator (issue #209).
    return [
        // Bundles
        hub_list_bundles: [title: "List Bundles", summary: "List installed code bundles and what they contain."],
        hub_install_bundle: [title: "Install Bundle", summary: "Install a code bundle (.zip) from a URL."],
        hub_delete_bundle: [title: "Delete Bundle", summary: "Delete an installed code bundle, optionally with the apps, drivers and libraries it delivered."],
        hub_export_bundle: [title: "Export Bundle", summary: "Export an installed bundle's zip to the File Manager."]
    ]
}
