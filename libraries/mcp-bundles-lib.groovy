library(name: "McpBundlesLib", namespace: "mcp", author: "kingpanther13", description: "Bundle management tool implementations for the MCP Rule Server (hub_list_bundles/hub_delete_bundle/hub_export_bundle); included by the main app. Gateway entries and dispatch stay in the app; tool definitions live here alongside the impl.")

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

def toolDeleteBundle(args) {
    // Delete a code bundle by id via GET /bundle/delete/<id> (302-redirects on success).
    // DESTRUCTIVE: Write master + confirm + recent backup. Verifies by re-listing. Removing the
    // bundle container may leave the code it delivered in Code -- the caller is told to verify.
    requireDestructiveConfirm(args.confirm)
    def rawId = args?.bundleId
    if (rawId == null || !rawId.toString().trim()) {
        throw new IllegalArgumentException("bundleId is required (the numeric id from hub_list_bundles).")
    }
    def bundleId = rawId.toString().trim()
    if (!(bundleId ==~ /\d+/)) {
        throw new IllegalArgumentException("bundleId must be a positive integer (got '${bundleId.take(40)}'). Use hub_list_bundles to find it.")
    }

    def before = toolListBundles([:])
    def target = (before.bundles ?: []).find { it.id?.toString() == bundleId }
    // Only a clean live list that actually contains the id proves the bundle existed. Without that,
    // a later "absent" can't prove WE deleted it (deleting a nonexistent id is a harmless hub no-op),
    // so the result must NOT claim verified:true.
    def confirmedPresentBefore = (before.source == "hub_api" && target != null)
    if (before.source == "hub_api" && !target) {
        return [success: false, error: "No bundle with id ${bundleId} found on the hub. Use hub_list_bundles to see installed bundles.", bundleId: bundleId]
    }
    if (before.source != "hub_api") {
        mcpLog("warn", "hub-admin", "hub_delete_bundle: could not validate bundle ${bundleId} against the live list (source=${before.source}); proceeding on the supplied id, but the result will be reported unverified")
    }
    def bundleName = target?.name
    mcpLog("info", "hub-admin", "Deleting bundle ${bundleId} (${bundleName ?: 'name unknown'})")

    def resp
    try {
        resp = hubInternalGetRaw("/bundle/delete/${bundleId}")
    } catch (Exception e) {
        mcpLog("warn", "hub-admin", "hub_delete_bundle threw: ${e.toString()}")
        return [success: false, error: "Bundle delete request failed: ${e.message ?: e.toString()}", bundleId: bundleId, lastBackup: formatTimestamp(state.lastBackupTimestamp)]
    }

    // Authoritative check: re-list and confirm the id is gone (the 302 alone isn't proof).
    def after = toolListBundles([:])
    if (after.source != "hub_api") {
        // Can't confirm removal from a degraded list -- do NOT report success for a destructive op.
        return [
            success: false,
            verified: false,
            error: "Delete request was sent for bundle ${bundleId}${bundleName ? " ('${bundleName}')" : ''}, but removal could not be verified -- the bundle list API returned a degraded shape (${after.source}). Re-run hub_list_bundles to check whether it was removed.",
            bundleId: bundleId,
            status: resp?.status,
            lastBackup: formatTimestamp(state.lastBackupTimestamp)
        ]
    }
    def stillThere = (after.bundles ?: []).any { it.id?.toString() == bundleId }
    if (stillThere) {
        return [success: false, error: "Bundle ${bundleId}${bundleName ? " ('${bundleName}')" : ''} is still present after the delete request -- the hub may have refused it (status=${resp?.status}).", bundleId: bundleId, status: resp?.status, lastBackup: formatTimestamp(state.lastBackupTimestamp)]
    }

    if (!confirmedPresentBefore) {
        // Post-delete list is clean and shows no id ${bundleId}, but the PRE-delete list was degraded
        // so we never confirmed the bundle existed. "Absent now" therefore doesn't prove a real
        // deletion (it may have been a no-op on a nonexistent id). Report the end state but do NOT
        // claim verified -- mirrors the degraded post-list refusal above.
        return [
            success: true,
            verified: false,
            message: "Bundle ${bundleId} is not present in the current bundle list. The pre-delete list was degraded, so I could not confirm the bundle existed beforehand -- if you expected a specific bundle removed, re-run hub_list_bundles to confirm.",
            bundleId: bundleId,
            bundleName: bundleName,
            lastBackup: formatTimestamp(state.lastBackupTimestamp)
        ]
    }

    return [
        success: true,
        message: "Bundle ${bundleId}${bundleName ? " ('${bundleName}')" : ''} deleted. This removes the bundle container; libraries/apps/drivers it delivered may remain in Code -- verify with hub_list_libraries / hub_list_apps and delete separately if needed.",
        bundleId: bundleId,
        bundleName: bundleName,
        verified: true,
        lastBackup: formatTimestamp(state.lastBackupTimestamp)
    ]
}

def toolExportBundle(args) {
    // Export a bundle's .zip to the hub File Manager. GET /bundle/export/<id> returns application/zip;
    // fetched as BINARY (textParser:false -- the text hubInternal* helpers would corrupt the zip) and
    // saved via uploadHubFile. A write (adds a file) but not destructive -- matches
    // hub_export_custom_rule gating (Write master, no confirm). Downloadable at /local/<fileName>.
    def rawId = args?.bundleId
    if (rawId == null || !rawId.toString().trim()) {
        throw new IllegalArgumentException("bundleId is required (the numeric id from hub_list_bundles).")
    }
    def bundleId = rawId.toString().trim()
    if (!(bundleId ==~ /\d+/)) {
        throw new IllegalArgumentException("bundleId must be a positive integer (got '${bundleId.take(40)}'). Use hub_list_bundles to find it.")
    }

    def listed = toolListBundles([:])
    def target = (listed.bundles ?: []).find { it.id?.toString() == bundleId }
    if (listed.source == "hub_api" && !target) {
        return [success: false, error: "No bundle with id ${bundleId} found on the hub. Use hub_list_bundles to see installed bundles.", bundleId: bundleId]
    }

    def baseName = (args?.saveAs ?: target?.name ?: "bundle-${bundleId}").toString().trim()
    if (!baseName) baseName = "bundle-${bundleId}"
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

def toolInstallBundle(args) {
    // Install a Hubitat code bundle (.zip) from a URL, the way HPM's installBundle() does (issue
    // #209): the hub fetches the zip and unpacks it into Libraries/Apps/Drivers Code. firmware >=
    // 2.3.8.108 -> GET /bundle2/uploadZipFromUrl; older -> POST /bundle/uploadZipFromUrl.
    requireDestructiveConfirm(args.confirm)
    def importUrl = args.importUrl
    if (!(importUrl instanceof String) || !importUrl.trim()) {
        throw new IllegalArgumentException("importUrl is required: the URL of the bundle .zip the hub fetches and installs.")
    }
    importUrl = importUrl.trim()
    def lower = importUrl.toLowerCase()
    if (!(lower.startsWith("http://") || lower.startsWith("https://"))) {
        throw new IllegalArgumentException("importUrl scheme must be http or https (got '${importUrl.take(40)}')")
    }
    boolean primary = (args.primary == true)

    String fw = null
    try { fw = location?.hub?.firmwareVersionString?.toString() } catch (Exception ignored) { }
    if (!fw) mcpLog("warn", "hub-admin", "hub_install_bundle: could not read firmware version; defaulting bundle endpoint to modern (/bundle2/uploadZipFromUrl)")
    // bundle2 endpoint exists on firmware >= 2.3.8.108 (matches HPM's gate). Compare
    // segments numerically, NOT lexically -- string compare breaks once a segment
    // reaches two digits (e.g. "2.3.10.0" sorts BELOW "2.3.8.0" as strings, which would
    // wrongly route a newer hub to the removed legacy endpoint).
    boolean modern = _firmwareAtLeast(fw, "2.3.8.108")
    String endpoint = modern ? "/bundle2/uploadZipFromUrl" : "/bundle/uploadZipFromUrl"

    mcpLog("info", "hub-admin", "Installing bundle (endpoint: ${endpoint}, fw: ${fw}, primary: ${primary}, url: ${importUrl})")
    try {
        def resp
        if (modern) {
            // hubInternalGet passes the query map to httpGet, which URL-encodes the values. `private`
            // is quoted because it is a Groovy keyword. 300s timeout matches HPM's bundle install.
            resp = hubInternalGet("/bundle2/uploadZipFromUrl", [url: importUrl, pwd: "", "private": primary.toString()], 300)
        } else {
            def body = groovy.json.JsonOutput.toJson([url: importUrl, installer: primary, pwd: ""])
            resp = hubInternalPostJson("/bundle/uploadZipFromUrl", body)
        }
        boolean ok = _bundleResponseSucceeded(resp)
        if (!ok) {
            mcpLog("warn", "hub-admin", "Bundle install did not report success (endpoint ${endpoint})")
            return [
                success: false,
                error: "Bundle install failed: the hub returned no success signal. The zip may be malformed/unreachable, or the firmware endpoint unavailable.",
                endpoint: endpoint,
                rawResponse: resp?.toString()?.take(500),
                lastBackup: formatTimestamp(state.lastBackupTimestamp)
            ]
        }
        mcpLog("info", "hub-admin", "Bundle installed successfully from ${importUrl}")
        return [
            success: true,
            message: "Bundle installed from ${importUrl}. Its libraries/apps/drivers are now in Code -- verify with hub_list_libraries / hub_list_apps.",
            endpoint: endpoint,
            primary: primary,
            lastBackup: formatTimestamp(state.lastBackupTimestamp)
        ]
    } catch (Exception e) {
        mcpLog("warn", "hub-admin", "Bundle install threw: ${e.toString()}")
        return [
            success: false,
            error: "Bundle install failed: ${e.message ?: e.toString()}",
            endpoint: endpoint,
            lastBackup: formatTimestamp(state.lastBackupTimestamp)
        ]
    }
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

def _firmwareAtLeast(fw, String target) {
    // Compare dotted firmware versions segment-by-segment, numerically. Returns true when fw >=
    // target. Missing/blank/unparseable fw returns true (assume modern): every hub running this
    // server today is well past the 2.3.8.108 bundle2 cutoff, so the current endpoint is the safe default.
    if (fw == null || !fw.toString().trim()) return true
    def fwParts = fw.toString().trim().split("\\.")
    def tgtParts = target.split("\\.")
    int n = Math.max(fwParts.size(), tgtParts.size())
    for (int i = 0; i < n; i++) {
        String fwSeg = (i < fwParts.size()) ? fwParts[i] : "0"
        String tgtSeg = (i < tgtParts.size()) ? tgtParts[i] : "0"
        int a = fwSeg.isInteger() ? fwSeg.toInteger() : 0
        int b = tgtSeg.isInteger() ? tgtSeg.toInteger() : 0
        if (a != b) return a > b
    }
    return true  // all segments equal -> >= holds
}

// Tool DEFINITIONS for the bundle tools (issue #209: schema lives with the impl). Concatenated
// into getAllToolDefinitions() in the main app; gateway membership + dispatch stay in main.
def _getAllToolDefinitions_partBundles() {
    return [
        [
            name: "hub_install_bundle",
            description: "Install a Hubitat code bundle (.zip) from a URL the way Hubitat Package Manager does -- the hub fetches the zip and unpacks it into Libraries/Apps/Drivers Code (how a package delivers the libraries an app #includes). Use it to prove on the real hub that a package installs the HPM way before users update. Requires Write master + confirm=true + a recent backup; the hub does not deep-validate the zip, so verify with hub_list_libraries / hub_get_source. Uses /bundle2/uploadZipFromUrl on firmware >= 2.3.8.108, else legacy /bundle/uploadZipFromUrl.",
            inputSchema: [
                type: "object",
                properties: [
                    importUrl: [type: "string", description: "URL of the bundle .zip the hub fetches and installs (http:// or https://)."],
                    primary: [type: "boolean", description: "OPTIONAL. Mark the bundle's contents as installed-by-this-package (HPM's installer/private flag). Default false."],
                    confirm: [type: "boolean", description: "REQUIRED: must be true. Confirms a recent backup exists and the user approved installing this bundle."]
                ],
                required: ["importUrl", "confirm"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the bundle installed"],
                    message: [type: "string", description: "Human-readable result"],
                    endpoint: [type: "string", description: "Hub endpoint used (/bundle2/uploadZipFromUrl or /bundle/uploadZipFromUrl)"],
                    primary: [type: "boolean", description: "Whether the bundle was marked primary/installer"],
                    error: [type: "string", description: "Failure detail; present on failure"],
                    rawResponse: [type: "string", description: "Raw hub response (truncated); present on a no-success result"],
                    lastBackup: [type: "string", description: "Timestamp of most recent backup"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_list_bundles",
            description: "List installed code bundles -- the Bundle-Manager containers HPM delivers code in, distinct from Libraries Code. Each entry: id, name, namespace, private flag, and a 'contains' summary of the apps/drivers/libraries it delivered. Use to find a bundle's id for hub_delete_bundle/hub_export_bundle, or to verify a bundle installed. Read-only. Requires Read master.",
            inputSchema: [
                type: "object",
                properties: [
                    cursor: [type: "string", description: "Opt-in pagination cursor. Omit for unbounded; pass \"\" for the first page, iterate nextCursor (page size 50)."]
                ]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    bundles: [type: "array", description: "Installed bundle summaries (id, name, namespace, private, and a parsed 'contains' map or raw 'content' string)", items: [type: "object"]],
                    count: [type: "integer", description: "Bundles returned"],
                    source: [type: "string", description: "hub_api / hub_api_raw / unavailable"],
                    note: [type: "string", description: "Status note when the hub API was unavailable or returned a non-JSON shape"],
                    rawResponse: [type: "string", description: "Raw body when response was not JSON"],
                    total: [type: "integer", description: "Total matched (present when paginating)"],
                    nextCursor: [type: "string", description: "Present when more results remain"]
                ],
                required: ["bundles"]
            ]
        ],
        [
            name: "hub_delete_bundle",
            description: "Delete an installed code bundle by id (the Bundle-Manager container; find the id with hub_list_bundles). DESTRUCTIVE: requires Write master + confirm=true + a recent backup; verifies by re-listing that the id is gone. Removes only the container -- the libraries/apps/drivers it delivered may remain in Code, so delete those separately with hub_delete_item if needed.",
            inputSchema: [
                type: "object",
                properties: [
                    bundleId: [type: "string", description: "The numeric bundle id from hub_list_bundles (e.g. \"4\")."],
                    confirm: [type: "boolean", description: "REQUIRED: must be true. Confirms a recent backup exists and the user approved deleting this bundle."]
                ],
                required: ["bundleId", "confirm"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the bundle was deleted"],
                    message: [type: "string", description: "Human-readable result"],
                    bundleId: [type: "string", description: "The targeted bundle id"],
                    bundleName: [type: "string", description: "The deleted bundle's name (when it was resolvable)"],
                    verified: [type: "boolean", description: "Whether the id was confirmed absent from a post-delete re-list"],
                    error: [type: "string", description: "Failure detail; present on failure"],
                    status: [type: "integer", description: "Hub HTTP status of the delete request; present on a not-verified result"],
                    lastBackup: [type: "string", description: "Timestamp of most recent backup"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_export_bundle",
            description: "Export an installed bundle's .zip to the hub File Manager (downloadable at /local/<fileName>; find the id with hub_list_bundles). A write -- it creates a File Manager file -- but not destructive, so it needs the Write master and no confirm. Use saveAs to set the filename (defaults to the bundle name).",
            inputSchema: [
                type: "object",
                properties: [
                    bundleId: [type: "string", description: "The numeric bundle id from hub_list_bundles (e.g. \"4\")."],
                    saveAs: [type: "string", description: "OPTIONAL File Manager filename for the exported .zip. Defaults to the bundle's name. '.zip' is appended if missing; non-filename characters are replaced with '_'."]
                ],
                required: ["bundleId"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether the bundle zip was saved"],
                    message: [type: "string", description: "Human-readable result"],
                    bundleId: [type: "string", description: "The exported bundle id"],
                    fileName: [type: "string", description: "File Manager filename the zip was saved as"],
                    bytes: [type: "integer", description: "Size of the saved zip in bytes"],
                    directDownload: [type: "string", description: "Local download path (/local/<fileName>)"],
                    status: [type: "integer", description: "Hub HTTP status of the export request; present on a non-2xx failure"],
                    error: [type: "string", description: "Failure detail; present on failure"]
                ],
                required: ["success"]
            ]
        ]
    ]
}
