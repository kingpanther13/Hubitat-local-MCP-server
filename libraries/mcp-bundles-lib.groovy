library(name: "McpBundlesLib", namespace: "mcp", author: "kingpanther13", description: "Bundle tools for the MCP Rule Server (hub_install_bundle, hub_list_bundles, hub_export_bundle, hub_delete_bundle); included by the main app. Gateway entries and dispatch stay in the app; tool definitions live here alongside the impl.")

// ---- The hub's bundle records ----
// GET /bundle/list/json answers every bundle record; GET /bundle/json/<id> answers one under
// `bundle`, or {"success":false} for an id the hub does not have. A record carries id, name,
// namespace, appTypeIds, deviceTypeIds, libraryIds, sourceUrl and contentReadable, plus the zip
// password fields, which only _bundleView reads past and which never reach a result or a log.
// _readBundleList and _readBundle are the one place to correct if the firmware's shapes differ.

private String _withBundlesGuide(String note) {
    return note + " See hub_get_tool_guide(section='bundles')."
}

// Parse the hub's display summary ("apps [A, B], drivers [], libraries [L]") into
// [apps:[...], drivers:[...], libraries:[...]]; null unless all three categories are found.
private Map _parseBundleContent(String content) {
    if (!content) return null
    Map out = [:]
    ["apps", "drivers", "libraries"].each { String key ->
        int open = content.indexOf(key + " [")
        int close = open < 0 ? -1 : content.indexOf("]", open)
        if (close > 0) out.put(key, content.substring(open + key.length() + 2, close).split(",")*.trim().findAll())
    }
    return out.size() == 3 ? out : null
}

// What a client sees of a bundle record: the password fields are never copied. codeIds are the
// ids the hub stored for the bundle (what a delete with code acts on). contains splits the display
// summary into names, kept only when its counts match codeIds: a name with "]" in it, or code
// deleted since the install, makes the summary unreliable, and the text is given as content.
Map _bundleView(Map rec) {
    Map codeIds = [apps: _bundleIds(rec.appTypeIds), drivers: _bundleIds(rec.deviceTypeIds), libraries: _bundleIds(rec.libraryIds)]
    def entry = [id: rec.id?.toString(), name: rec.name, namespace: rec.namespace, "private": (rec["private"] == true)]
    if (rec.sourceUrl) entry.sourceUrl = rec.sourceUrl.toString()
    entry.codeIds = codeIds
    String text = rec.contentReadable?.toString()
    Map contains = _parseBundleContent(text)
    if (contains != null && codeIds.every { k, ids -> contains.get(k).size() == ids.size() }) entry.contains = contains
    else if (text != null) entry.content = text
    return entry
}

private List _bundleIds(ids) {
    return (ids instanceof List) ? ids.findAll()*.toString() : []
}

// GET /bundle/list/json -> [records: List] or [records: null, source, note].
private Map _readBundleList() {
    String text
    try {
        text = hubInternalGet("/bundle/list/json")
    } catch (Exception e) {
        mcpLog("warn", "hub-admin", "Bundle list read failed: ${e.message}")
        return [records: null, source: "unavailable",
                note: "Hub internal API unavailable (${e.message}). This may require Hub Security credentials or a firmware update."]
    }
    if (!text) return [records: null, source: "unavailable", note: "Empty response from hub API"]
    def parsed = null
    try { parsed = new groovy.json.JsonSlurper().parseText(text) } catch (Exception ignored) { }
    // The reply is a JSON array, or a map holding it under `bundles`.
    def rows = (parsed instanceof Map) ? parsed.bundles : parsed
    if (rows instanceof List) return [records: rows.findAll { it instanceof Map }]
    // The raw reply is not returned: it can carry the bundles' zip passwords.
    return [records: null, source: "hub_api_raw",
            note: _withBundlesGuide("The hub's bundle list (/bundle/list/json) is not in a recognized shape on this firmware, so no bundle is listed.")]
}

// GET /bundle/json/<id> -> [bundle: record] (null for an id the hub does not have) or [error].
private Map _readBundle(String id) {
    String text
    try {
        text = hubInternalGet("/bundle/json/${id}")
    } catch (Exception e) {
        mcpLog("warn", "hub-admin", "Bundle ${id} read failed: ${e.message}")
        return [error: e.message ?: e.toString()]
    }
    def parsed = null
    try { parsed = text ? new groovy.json.JsonSlurper().parseText(text) : null } catch (Exception ignored) { }
    if (parsed instanceof Map && parsed.bundle instanceof Map) return [bundle: parsed.bundle]
    if (parsed instanceof Map && parsed.success == false) return [bundle: null]
    return [error: "the hub's reply to /bundle/json/${id} is not a recognized answer".toString()]
}

def toolListBundles(args) {
    // List the code bundles installed on the hub (the Bundles page -- distinct from Libraries Code).
    def cursor = args?.cursor
    def result = [:]
    Map read = _readBundleList()
    if (read.records != null) {
        List bundles = read.records.collect { _bundleView(it) }
        result.bundles = bundles
        result.count = bundles.size()
        result.source = "hub_api"
        int noId = bundles*.id.count(null)
        if (noId > 0) {
            result.note = "${noId} bundle${noId == 1 ? '' : 's'} returned by the hub had no id and cannot be targeted by hub_delete_bundle / hub_export_bundle (possible firmware shape change)."
        }
    } else {
        result.bundles = []
        result.count = 0
        result.source = read.source
        result.note = read.note
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
    return _exportBundle(_bundleIdArg(args), args?.saveAs?.toString()?.trim())
}

// Export a bundle's .zip to the hub File Manager. GET /bundle/export/<id> returns application/zip;
// fetched as BINARY (textParser:false -- the text hubInternal* helpers would corrupt the zip) and
// saved via uploadHubFile. A write (adds a file) but not destructive -- matches
// hub_export_custom_rule gating (Write master, no confirm). Downloadable at /local/<fileName>.
private Map _exportBundle(String bundleId, String saveAs) {
    // The bundle's record only supplies the default filename; with saveAs given, the HTTP status
    // and PK checks below catch an id the hub does not have.
    Map target = null
    String baseName = saveAs
    String mayNotExist = "The bundle id may not exist on the hub: hub_list_bundles lists the bundle ids."
    if (!baseName) {
        Map read = _readBundle(bundleId)
        if (!read.error && read.bundle == null) {
            return _bundleRefusal(bundleId, "No bundle with id ${bundleId} found on the hub.", "Nothing was saved. hub_list_bundles lists the bundle ids.")
        }
        target = read.bundle
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
        return _bundleRefusal(bundleId, "Failed to fetch bundle ${bundleId} export zip: ${e.message ?: e.toString()}", "Nothing was saved. The bundle id may not exist on the hub: hub_list_bundles lists the bundle ids.")
    }
    // A non-2xx response (e.g. a 404/500 error PAGE) or a body that doesn't start with the ZIP
    // signature ('PK') means the hub did not return a real bundle zip. Fail WITHOUT saving rather
    // than writing a corrupt .zip and reporting success.
    if (httpStatus != null && !(httpStatus >= 200 && httpStatus < 300)) {
        return _bundleRefusal(bundleId, "Bundle ${bundleId} export returned HTTP ${httpStatus} -- not a bundle zip. Nothing saved.", mayNotExist, [status: httpStatus])
    }
    if (unexpectedBodyDesc) {
        return _bundleRefusal(bundleId, "Bundle ${bundleId} export returned a non-binary body (${unexpectedBodyDesc}); nothing saved -- a binary zip was expected.", mayNotExist)
    }
    if (!zipBytes || zipBytes.length == 0) {
        return _bundleRefusal(bundleId, "Bundle ${bundleId} export returned no data. Nothing saved.", mayNotExist)
    }
    if (!(zipBytes.length >= 2 && zipBytes[0] == (byte) 0x50 && zipBytes[1] == (byte) 0x4B)) {
        return _bundleRefusal(bundleId, "Bundle ${bundleId} export did not return a ZIP (no PK signature) -- the hub likely returned an error page. Nothing saved.", mayNotExist)
    }
    try {
        uploadHubFile(fileName, zipBytes)
    } catch (Exception e) {
        return _bundleRefusal(bundleId, "Fetched bundle ${bundleId} (${zipBytes.length} bytes) but saving to File Manager as ${fileName} failed: ${e.message ?: e.toString()}", "Retry once File Manager accepts writes.")
    }

    mcpLog("info", "hub-admin", "Exported bundle ${bundleId} to File Manager as ${fileName} (${zipBytes.length} bytes)")
    return [
        success: true,
        message: "Bundle ${bundleId}${target?.name ? " ('${target.name}')" : ''} exported to the File Manager as ${fileName} (${zipBytes.length} bytes). Download at /local/${fileName}.".toString(),
        bundleId: bundleId,
        fileName: fileName,
        bytes: zipBytes.length,
        directDownload: "/local/${fileName}".toString()
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

def toolInstallBundle(args) {
    requireDestructiveConfirm(args.confirm)
    def rawUrl = args.importUrl
    String url = (rawUrl instanceof String) ? rawUrl.trim() : ""
    if (!url) {
        throw new IllegalArgumentException("importUrl is required: the URL of the bundle .zip the hub fetches and installs.")
    }
    String lower = url.toLowerCase()
    if (!(lower.startsWith("http://") || lower.startsWith("https://"))) {
        throw new IllegalArgumentException("importUrl scheme must be http or https (got '${url.take(40)}')")
    }
    return _installBundle(url, _bundleFlagArg("installer", args.installer))
}

// An optional boolean argument: false when omitted, refused when it is not a boolean.
private boolean _bundleFlagArg(String name, value) {
    if (value != null && !(value instanceof Boolean)) {
        throw new IllegalArgumentException("${name} must be true or false (got '${value.toString().take(40)}').")
    }
    return value == true
}

// hub_install_bundle. The install reply carries no bundle id, so the id is the one that appears
// between a bundle list read before and after, the bundle whose sourceUrl is this URL breaking a
// tie. A list that cannot be read only costs the id: it never turns an install the hub reported
// as done into a failure.
private Map _installBundle(String url, boolean installer) {
    List beforeRecords = _readBundleList().records
    // The hub can install part of a bundle and then refuse it, leaving code in Code that no bundle
    // lists; only these code lists, compared with the ones read after a failure, show what that was.
    Map codeBefore = _bundleCodeSnapshot(["app", "driver", "library"])

    Map r = _installBundleFromUrl(url, installer)
    String lastBackup = formatTimestamp(state.lastBackupTimestamp)
    if (!r.ok) {
        def failure = [success: false, error: r.error, endpoint: r.endpoint, lastBackup: lastBackup]
        List notes = []
        if (r.raw != null) failure.rawResponse = r.raw.toString().take(500)
        else {
            // No reply does not mean the hub stopped: it may have finished, so the list is read again.
            Map found = _installedBundle(beforeRecords, url)
            if (found.fresh) {
                failure.bundle = found.bundle
                failure.bundleId = found.bundle.id
                notes << "No reply came, but the bundle list read again shows the new bundle from this install (in bundle), so the install appears to have completed."
            } else {
                notes << "No reply came, and ${found.read ? 'the bundle list read again shows no new bundle it can tie to this install' : 'the bundle list could not be read'}, so whether the bundle was installed is unknown. Check hub_list_bundles before retrying or deleting anything with hub_delete_item."
            }
        }
        Map added = _bundleCodeAdded(codeBefore)
        if (added.items) {
            failure.codeAdded = added.items
            notes << (r.raw != null ? "The hub added the code in codeAdded to Code before the install failed, and no bundle lists it. Check it before retrying; delete what you do not want with hub_delete_item(type, item_id)."
                : "The code in codeAdded was added to Code during the install.")
        }
        if (added.unverified) {
            String unread = "The ${added.unverified.join(' and ')} code list could not be read, so code the install added there is not listed; check hub_list_apps(scope='types'), hub_list_drivers or hub_list_libraries."
            notes << unread
        }
        failure.note = _withBundlesGuide(notes.join(" "))
        return failure
    }

    String message = "Bundle installed from ${url}. Its apps, drivers and libraries are in Code."
    def out = [success: true, bundleId: null, endpoint: r.endpoint, installer: installer, lastBackup: lastBackup, message: message]
    Map found = _installedBundle(beforeRecords, url)
    if (found.bundle) {
        out.bundle = found.bundle
        out.bundleId = found.bundle.id
    }
    if (found.note) out.note = found.note
    return out
}

// The bundle an install added, from the bundle list read before it and again now: [read, bundle,
// fresh (a new id, not a reinstall), note].
private Map _installedBundle(List beforeRecords, String url) {
    List after = beforeRecords == null ? null : _readBundleList().records
    if (after == null) {
        return [read: false, note: "The bundle list could not be read ${beforeRecords == null ? 'before' : 'after'} the install, so the new bundle's id is unknown. hub_list_bundles shows it."]
    }
    List beforeIds = beforeRecords*.id*.toString()
    List fromUrl = after.findAll { it.id != null && it.sourceUrl?.toString() == url }
    List fresh = after.findAll { it.id != null && !beforeIds.contains(it.id.toString()) }
    // Reinstalling a bundle keeps its id: with nothing new, the bundle installed from this URL is the one.
    List pick = fresh.size() == 1 ? fresh : (fresh ? fresh.intersect(fromUrl) : fromUrl)
    if (pick.size() != 1) return [read: true, note: "No single new bundle appeared, and no single bundle was installed from this URL. hub_list_bundles shows it."]
    return [read: true, bundle: _bundleView(pick[0]), fresh: !fresh.isEmpty(),
            note: fresh ? null : "The bundle installed from this URL was already on the hub; the install updated it and it kept its id."]
}

// type -> [id: name] from the lists behind hub_list_apps(scope='types') / hub_list_drivers /
// hub_list_libraries, or type -> null when that list cannot be read.
private Map _bundleCodeSnapshot(List types) {
    return types.collectEntries { String type ->
        def parsed = null
        try {
            String text = hubInternalGet([app: "/hub2/userAppTypes", driver: "/hub2/userDeviceTypes", library: "/hub2/userLibraries"][type])
            parsed = text ? new groovy.json.JsonSlurper().parseText(text) : null
        } catch (Exception e) {
            mcpLog("warn", "hub-admin", "Bundle: could not read the ${type} code list: ${e.message}")
        }
        [(type): (parsed instanceof List) ? parsed.findAll { it instanceof Map && it.id != null }.collectEntries { [(it.id.toString()): it.name] } : null]
    }
}

// The code that is in the code lists now and was not in `before`: [items, unverified types].
private Map _bundleCodeAdded(Map before) {
    Map after = _bundleCodeSnapshot(before.findAll { k, v -> v != null }.keySet() as List)
    List items = []
    List unverified = before.keySet().findAll { before.get(it) == null || after.get(it) == null } as List
    after.each { String type, Map current ->
        current?.each { id, name -> if (!before.get(type).containsKey(id)) items << [type: type, id: id, name: name] }
    }
    return [items: items, unverified: unverified]
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
    return _deleteBundleItem(id, _bundleFlagArg("deleteCode", args.deleteCode))
}

// hub_delete_bundle. /bundle/deleteJson/<id> answers success for any id, so the bundle's record is
// read first. With deleteCode the hub also deletes the code ids stored on that record, except code
// still in use, which it keeps without saying so, and it fails part-way on an id with no code
// behind it. So those ids are checked against the code lists, backed up, and looked up again
// afterwards to report what went.
Map _deleteBundleItem(String id, boolean deleteCode) {
    Map read = _readBundle(id)
    if (read.error) {
        return _bundleRefusal(id, "Could not read bundle ${id} (${read.error}), so it was not deleted.",
                "The hub reports success for any bundle id, so the delete is sent only once the hub's record of the bundle has been read. Retry when hub_list_bundles answers.")
    }
    if (read.bundle == null) {
        return _bundleRefusal(id, "No bundle with id ${id}", "Nothing was deleted. hub_list_bundles lists the bundle ids.")
    }
    Map view = _bundleView(read.bundle)
    String name = view.name
    String label = "Bundle ${id} ('${name}')"

    if (!deleteCode) return _sendBundleDelete(id, name, label, [], null)
    Map plan = _bundleDeletePlan(id, label, view)
    if (plan.refusal) return plan.refusal
    // Held from the first backup through the delete: released in between, another tool's backup
    // could publish between this set's backups and be evicted by this set's protected publish.
    return _withBackupLock("delete bundle ${id} with its code") {
        Map backup = _backupBundleCode(id, label, plan.items)
        backup.refusal ?: _sendBundleDelete(id, name, label, plan.items, backup.files)
    }
}

// The delete request itself, with deleteCode when backupFiles is not null.
Map _sendBundleDelete(String id, String name, String label, List items, List backupFiles) {
    boolean deleteCode = backupFiles != null
    mcpLog("warn", "hub-admin", "Deleting bundle ${id} (${name}, deleteCode: ${deleteCode})")
    String failure = null
    String responseText = null
    try {
        responseText = hubInternalGet("/bundle/deleteJson/${id}", deleteCode ? [full: "true"] : null)
        def parsed = null
        try { parsed = responseText ? new groovy.json.JsonSlurper().parseText(responseText) : null } catch (Exception ignored) { }
        if (!(parsed instanceof Map) || !parsed.containsKey("success")) {
            failure = "Could not confirm the delete of ${label}: the hub's reply is not a recognized answer."
        } else if (parsed.success != true) {
            failure = parsed.message ?: "The hub refused the delete of ${label}"
        }
    } catch (Exception e) {
        mcpLogError("hub-admin", "Bundle ${id} delete request failed", e)
        failure = "Bundle delete request failed: ${e.message}"
    }
    if (failure != null) {
        Map out = [success: false, bundleId: id, error: failure]
        if (responseText != null) out.response = responseText.take(500)
        if (backupFiles) out.backupFiles = backupFiles
        return out + _bundleDeleteAftermath(id, label, items, deleteCode)
    }

    Map code = deleteCode ? _bundleCodeOutcome(items) : null
    String message = code ? "${label} deleted with its code: ${code.codeDeleted.size()} of ${items.size()} delivered item(s) deleted."
        : "${label} deleted. The apps, drivers and libraries it delivered stay in Code: delete each with hub_delete_item(type, item_id)."
    def out = [success: true, bundleId: id, name: name, deleteCode: deleteCode,
               lastBackup: formatTimestamp(state.lastBackupTimestamp), message: message]
    if (!code) return out
    out.putAll(code)
    out.backupFiles = backupFiles
    if (backupFiles) out.backupRetentionNote = _itemBackupRetentionNote()
    List notes = []
    if (code.codeKept) notes << "The hub kept the code in codeKept because something still uses it (an installed app, a device, or code that #includes a library). Remove those app instances or switch those devices to another driver, then delete each item with hub_delete_item."
    if (code.codeUnverified) notes << "The code lists could not be read after the delete, so whether the items in codeUnverified were deleted is unknown. Check hub_list_apps(scope='types'), hub_list_drivers or hub_list_libraries."
    if (notes) out.note = _withBundlesGuide(notes.join(" "))
    return out
}

// The bundle's code as [type, id, name] items, each found in its code list, or [refusal] when a
// list cannot be read or an id the bundle stores has no code behind it.
private Map _bundleDeletePlan(String id, String label, Map view) {
    List items = []
    List missing = []
    Map keys = [app: "apps", driver: "drivers", library: "libraries"]
    List types = keys.keySet().findAll { view.codeIds.get(keys.get(it)) } as List
    Map lists = _bundleCodeSnapshot(types)
    List unreadable = types.findAll { lists.get(it) == null }
    if (unreadable) {
        return [refusal: _bundleRefusal(id, "${label}: the ${unreadable.join(' and ')} code list could not be read, so the bundle's code cannot be checked.",
                "Nothing was deleted. Retry when the code lists answer, or delete the bundle without deleteCode.")]
    }
    types.each { String type ->
        Map names = lists.get(type)
        view.codeIds.get(keys.get(type)).each { String codeId ->
            if (names.containsKey(codeId)) items << [type: type, id: codeId, name: names.get(codeId)]
            else missing << [type: type, id: codeId]
        }
    }
    if (missing) {
        return [refusal: _bundleRefusal(id, "${label} lists code that is no longer in Code: ${missing.collect { "${it.type} ${it.id}" }.join(', ')}.",
                "Nothing was deleted. The hub's delete of the bundle with its code fails part-way on such an id. Delete the bundle without deleteCode, then delete each remaining item with hub_delete_item(type, item_id).",
                [missingCode: missing])]
    }
    return [items: items]
}

// A refused or failed bundle call: success:false with the note pointing at the bundles guide.
private Map _bundleRefusal(String id, error, String note, Map extra = null) {
    Map out = [success: false, bundleId: id, error: error.toString()]
    if (extra) out.putAll(extra)
    out.note = _withBundlesGuide(note)
    return out
}

// Back up every item before anything is deleted. Each backup is protected from retention until
// the delete has run, and the set is refused up front when it alone would exceed the backup
// budget, since retention could then keep it only by going over budget.
Map _backupBundleCode(String id, String label, List items) {
    if (!items) return [files: []]
    Map manifest
    try {
        manifest = _itemBackupManifest()
    } catch (Exception e) {
        Map unavailable = _itemBackupIndexUnavailableResult(e)
        if (unavailable != null) return [refusal: _bundleRefusal(id, unavailable.error, unavailable.note)]
        throw e
    }
    List keys = items.collect { "${it.type}_${it.id}".toString() }
    Map fetched = [:]
    long bytes = 0L
    for (item in items) {
        String type = item.type
        String codeId = item.id
        String key = "${type}_${codeId}"
        def existing = manifest.get(key)
        if (_isReusableItemBackup(existing)) {
            bytes += _itemBackupEntryBytes(existing)
            continue
        }
        try {
            Map src = _readItemBackupSource(type, codeId)
            fetched.put(key, src)
            bytes += src.source.getBytes("UTF-8").length
        } catch (Exception readErr) {
            String why = "Could not read the source of ${type} ${codeId} ('${item.name}') for its backup: ${readErr.message}"
            mcpLog("warn", "hub-admin", "Bundle ${id}: ${why}")
            return [refusal: _bundleRefusal(id, why, "Nothing was backed up or deleted.")]
        }
    }
    if (bytes > _itemBackupByteBudget()) {
        return [refusal: _bundleRefusal(id, "${label}: the backups of its code take ${bytes} bytes, more than the ${_itemBackupBudgetMb()} MB backup storage budget, so they cannot all be kept.",
                "Nothing was backed up or deleted. Raise the backup storage budget (backupBudgetMb), or delete the bundle without deleteCode and each item with hub_delete_item(type, item_id).")]
    }
    List files = []
    for (item in items) {
        String type = item.type
        String codeId = item.id
        try {
            Map entry = _backupCodeItem(type, codeId, keys, fetched.get("${type}_${codeId}".toString()))
            if (entry.fileName) files << entry.fileName
        } catch (Exception backupErr) {
            Map unavailable = _itemBackupIndexUnavailableResult(backupErr)
            if (unavailable != null) return [refusal: _bundleRefusal(id, unavailable.error, unavailable.note, [backupFiles: files])]
            String why = "Could not back up ${type} ${codeId} ('${item.name}'): ${backupErr.message}"
            mcpLog("warn", "hub-admin", "Bundle ${id}: ${why}")
            return [refusal: _bundleRefusal(id, why, "Nothing was deleted. backupFiles lists the backups already written.", [backupFiles: files])]
        }
    }
    return [files: files]
}

// Each item looked up in its code list again: [codeDeleted, codeKept] plus codeUnverified for
// the items whose list cannot be read.
private Map _bundleCodeOutcome(List items) {
    Map after = _bundleCodeSnapshot(items*.type.unique())
    Map found = items.groupBy { Map ids = after.get(it.type); ids == null ? "codeUnverified" : ids.containsKey(it.id) ? "codeKept" : "codeDeleted" }
    Map out = [codeDeleted: found.codeDeleted ?: [], codeKept: found.codeKept ?: []]
    if (found.codeUnverified) out.codeUnverified = found.codeUnverified
    return out
}

// What a delete the hub did not confirm left behind: whether the bundle is still there, and with
// deleteCode where each item stands, since the hub can delete part of the code and then report
// failure.
private Map _bundleDeleteAftermath(String id, String label, List items, boolean deleteCode) {
    Map again = _readBundle(id)
    Boolean exists = again.error ? null : (again.bundle != null)
    String where = exists == null ? "Whether ${label} is still on the hub is unknown (${again.error})."
        : exists ? "${label} is still on the hub." : "${label} is gone although the hub did not confirm the delete."
    Map out = [bundleExists: exists]
    List notes = [where]
    if (deleteCode) {
        Map code = _bundleCodeOutcome(items)
        out.putAll(code)
        if (code.codeDeleted) notes << "The code in codeDeleted was deleted; backupFiles holds its backups."
        if (code.codeKept) notes << "The code in codeKept is still in Code."
        if (code.codeUnverified) notes << "Whether the code in codeUnverified was deleted is unknown: its code list could not be read."
    }
    notes << "Check hub_list_bundles before retrying."
    out.note = _withBundlesGuide(notes.join(" "))
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
            description: "Install a Hubitat code bundle (.zip) from a URL the way Hubitat Package Manager does -- the hub fetches the zip and unpacks it into Libraries/Apps/Drivers Code. Requires Write master + confirm=true + a recent backup; the hub does not deep-validate the zip. Returns the new bundle's id and contents.[[FLAT_TRIM]] The hub's reply has no id, so the bundle list is read before and after (a bundle's sourceUrl breaks a tie, and finds a reinstall, which keeps its id); when no single bundle matches the install still succeeds with a note -- find it with hub_list_bundles. A failed install lists any code it still added (codeAdded). Uses /bundle2/uploadZipFromUrl on firmware >= 2.3.8.108, else legacy /bundle/uploadZipFromUrl.[[/FLAT_TRIM]][[FLAT_TRIM]] A transport drop can lose the response while the hub still commits this install; verify current hub state before retrying. See hub_get_tool_guide(section='slow_ops').[[/FLAT_TRIM]]",
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
            description: "List installed code bundles -- the Bundle-Manager containers HPM delivers code in, distinct from Libraries Code.[[FLAT_TRIM]] Each entry: id, name, namespace, sourceUrl, the code ids the hub stored for it (codeIds), and a 'contains' summary of the app, driver and library names it delivered.[[/FLAT_TRIM]] Use to find a bundle's id for hub_delete_bundle/hub_export_bundle, or to verify a bundle installed. Read-only.",
            inputSchema: [
                type: "object",
                properties: [
                    cursor: [type: "string", description: "Opt-in pagination cursor. Omit for unbounded; pass \"\" for the first page, iterate nextCursor (page size 50)."]
                ]
            ]
        ],
        [
            name: "hub_delete_bundle",
            description: "Delete an installed code bundle by id (find the id with hub_list_bundles). DESTRUCTIVE: requires Write master + confirm=true + a recent backup. An id the hub does not have deletes nothing. The apps/drivers/libraries it delivered stay in Code unless deleteCode=true.[[FLAT_TRIM]] deleteCode=true backs up each item the hub stored for the bundle first and deletes it with the bundle; the hub keeps code still in use (listed in codeKept). See hub_get_tool_guide(section='bundles').[[/FLAT_TRIM]]",
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
