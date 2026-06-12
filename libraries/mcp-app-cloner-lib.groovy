library(name: "McpAppClonerLib", namespace: "mcp", author: "kingpanther13", description: "Native-app cloner tool implementations (hub_clone_native_app/hub_export_native_app/hub_import_native_app) plus the backup-restore primitive built on them for the MCP Rule Server; #include'd by the main app. Gateway entries and dispatch cases stay in the app; tool definitions, implementations, domain helpers, and per-tool metadata live here.")

private Map _appClonerInit(Integer sourceAppId) {
    def resp
    try {
        resp = hubInternalGetRaw("/installedapp/sysAppApi/appCloner/app/${sourceAppId}")
    } catch (Exception e) {
        throw new IllegalStateException("appCloner entry failed for source ${sourceAppId}: ${e.message}. The source app may not exist or appCloner may be unavailable.")
    }
    def loc = resp?.location
    if (!loc) {
        throw new IllegalStateException("appCloner entry returned no Location header for source ${sourceAppId} (status=${resp?.status})")
    }
    // regex covers two redirect shapes seen across firmwares (apps/api/* current, installedapp/configure/* older)
    def m = (loc =~ /\/(?:apps\/api|installedapp\/configure)\/(\d+)/)
    Integer clonerId = m.find() ? (m[0][1] as Integer) : null
    if (clonerId == null) {
        throw new IllegalStateException("Unexpected appCloner Location: ${loc}")
    }
    // Cloner state machine validates `referrer`/`url` against the session that
    // opened the cloner. Use the running hub's IP — hardcoding fails for any
    // other deployment. Falls back to 127.0.0.1 (the loopback the parent app
    // already uses for hubInternal* calls) if location.hub.localIP is null.
    String hubIp = null
    try { hubIp = location?.hub?.localIP?.toString() } catch (Exception ignored) { /* fall through */ }
    if (!hubIp) hubIp = "127.0.0.1"
    String sourceContextUrl = loc.startsWith("http") ? loc : "http://${hubIp}${loc}"
    String configUrl = "http://${hubIp}/installedapp/configure/${clonerId}/main".toString()
    // CRITICAL: follow the redirect target. The cloner's `app(sourceId)`
    // mapping renders the source-context page and sets internal state
    // (state.cloneSource, etc.). Without this GET, the cloner accepts our
    // form POSTs but its state machine never knows what rule to clone, so
    // cloneRuleButton/importNow clicks register but no new rule appears.
    // The Location is OAuth-token-protected (/apps/api/<clonerId>/app/<sourceId>
    // ?access_token=...) — split path + query so HTTPBuilder doesn't URL-encode
    // the `?` and break the access_token lookup.
    def relPath = loc.replaceFirst(/^https?:\/\/[^\/]+/, '')
    def parts = relPath.split(/\?/, 2)
    def justPath = parts[0]
    def query = [:]
    if (parts.length > 1) {
        parts[1].split('&').each { kv ->
            def eq = kv.indexOf('=')
            if (eq > 0) query[kv.substring(0, eq)] = kv.substring(eq + 1)
        }
    }
    try {
        hubInternalGet(justPath, query)
    } catch (Exception followErr) {
        // Source-context render is load-bearing — clone/import will silently
        // produce no new rule without it. Surface the failure instead of
        // letting the wizard fire and reporting "no new child appeared".
        throw new IllegalStateException("appCloner source-context render failed for source ${sourceAppId} (cloner ${clonerId}): ${followErr.message}. Without this step the cloner state machine never seeds state.cloneSource and subsequent clicks silently produce no rule.")
    }
    pauseExecution(1000)
    // Mimic browser flow: fetch the configPage JSON before any settings POSTs.
    // Without this, file-text widgets (`settings[ruleUpload]`) silently drop
    // their values — the cloner accepts the POST (200, ruleUpload key
    // registered) but stores null. Required for hub_import_native_app.
    try {
        hubInternalGet("/installedapp/configure/json/${clonerId}/main", [:])
    } catch (Exception primeErr) {
        throw new IllegalStateException("appCloner configPage prime failed for cloner ${clonerId}: ${primeErr.message}. Without this step settings[ruleUpload] writes are silently dropped on the import path.")
    }
    return [clonerAppId: clonerId, referrer: sourceContextUrl, configUrl: configUrl]
}

private Map _appClonerSubmitForm(Integer clonerAppId, String currentPage, String formState, String referrer, String configUrl, Map extras = null) {
    // Build a form-refresh body that mirrors the Hubitat UI's POST shape
    // for the cloner's current rendering state. Each formState corresponds
    // to a distinct view the cloner can render at /main:
    //   "source"        — initial source-context view (3 buttons)
    //   "confirmation"  — after cloneRuleButton: "Clone..." + Cancel
    //   "importRule"    — after navigation: name editor + importNow button
    // Each POST must emit the input triplets matching the rendered view —
    // sending the WRONG view's fields stalls the state machine silently.
    def pageBreadcrumbs = currentPage == "main" ? "[]" : '["main"]'
    def body = [
        formAction: "update",
        id: clonerAppId.toString(),
        version: "1",
        appTypeId: "",
        appTypeName: "",
        currentPage: currentPage,
        pageBreadcrumbs: pageBreadcrumbs,
        // The cloner state machine validates that subsequent form POSTs
        // come from the source-authorized session. The UI's referrer is the
        // OAuth-tokened source-context URL (.../apps/api/<cloner>/app/
        // <source>?access_token=...); without it the cloner silently rejects
        // state transitions. Captured during _appClonerInit.
        referrer: referrer ?: "",
        url: configUrl ?: "",
        _cancellable: "false"
    ]
    switch (formState) {
        case "source":
            body["exportRuleButton.type"] = "button"
            body["exportRuleButton.multiple"] = "false"
            body["settings[exportRuleButton]"] = ""
            body["ruleUpload.type"] = "file-text"
            body["ruleUpload.multiple"] = "false"
            body["settings[ruleUpload]"] = ""
            body["cloneRuleButton.type"] = "button"
            body["cloneRuleButton.multiple"] = "false"
            body["settings[cloneRuleButton]"] = ""
            break
        case "confirmation":
            body["cancelUpload.type"] = "button"
            body["cancelUpload.multiple"] = "false"
            body["settings[cancelUpload]"] = ""
            break
        case "importRule":
            // importRule fields are dynamic per source — caller passes them via extras
            break
        default:
            break
    }
    if (extras) body.putAll(extras)
    // URL-encode manually — HTTPBuilder's Map auto-encoder mangles backslash
    // sequences inside form-urlencoded bodies, so JSON content with embedded
    // `\"` (e.g. canonical exports' multi-select enum encoding) loses its
    // escaping on the wire and the cloner silently rejects the upload.
    StringBuilder sb = new StringBuilder()
    boolean first = true
    body.each { k, v ->
        if (!first) sb.append('&')
        first = false
        sb.append(URLEncoder.encode(k.toString(), "UTF-8"))
        sb.append('=')
        sb.append(v == null ? "" : URLEncoder.encode(v.toString(), "UTF-8"))
    }
    def resp = hubInternalPostFormRaw("/installedapp/update/json", sb.toString())
    if (resp == null) {
        // Closure never ran -> network call produced no response. Don't coerce
        // to [:] (export's "no JSON content" error would point at the wrong
        // root cause). Bubble so callers see the real failure.
        throw new IllegalStateException("appCloner POST /installedapp/update/json returned no response (cloner ${clonerAppId}, currentPage=${currentPage}, formState=${formState}). Network call failed before delivering a status.")
    }
    return resp
}

private Integer _appClonerFindActionHrefIdx(Integer clonerAppId, String pageName, String targetActionName) {
    // Hubitat assigns action_href elements a session-scoped numeric id at
    // render time — the same logical button gets a different number on the
    // post-clone confirmation page (low — usually 0) than on the post-upload
    // restore-or-import page (high — observed 55 live), and the cloner's
    // server-side dispatcher matches on the EXACT `<action>|<idx>` pair.
    // Fetch the page JSON and regex out the current id; without this the
    // navigate POST hits a no-op and the importNow click later fires on
    // the wrong page (silent failure — settings persist but no rule).
    def pn = pageName ?: "main"
    String body = null
    try {
        def resp = hubInternalGet("/installedapp/configure/json/${clonerAppId}/${pn}", [:])
        body = (resp instanceof Map) ? (resp.data?.toString()) : (resp?.toString())
    } catch (Exception e) {
        mcpLog("warn", "rm-native", "appCloner: fetch state for href discovery failed: ${e.message}")
        return null
    }
    if (!body) return null
    def m = (body =~ /_action_href_name\|${java.util.regex.Pattern.quote(targetActionName)}\|(\d+)/)
    return m.find() ? (m[0][1] as Integer) : null
}

private void _appClonerCommitImportRule(Integer clonerAppId, Integer sourceAppId, String newName, String referrer, String configUrl) {
    // Step 3: navigate /main → /main/importRule via _action_href_name. The
    // cloner is in either confirmation (post-clone) or restore-or-import
    // (post-upload) state — both expose an importRule action_href but at
    // different session-scoped indices.
    // The cloner re-renders asynchronously after a state-transition POST;
    // poll the page state a few times to give the action_href button time
    // to appear. Fail loudly if it never shows — the import path silently
    // no-ops when this idx is wrong (the very symptom we're fighting).
    Integer hrefIdx = null
    for (int attempt = 0; attempt < 4 && hrefIdx == null; attempt++) {
        if (attempt > 0) pauseExecution(500)
        hrefIdx = _appClonerFindActionHrefIdx(clonerAppId, "main", "importRule")
    }
    if (hrefIdx == null) {
        throw new IllegalStateException("appCloner importRule action_href not found on cloner ${clonerAppId} main page after 4 polls (~2s). The cloner did not transition to the expected confirmation/restore-or-import state — clicking importNow now would silently no-op.")
    }
    _appClonerSubmitForm(clonerAppId, "main", "confirmation", referrer, configUrl, [
        ("_action_href_name|importRule|${hrefIdx}".toString()): "",
        ("params_for_action_href_name|importRule|${hrefIdx}".toString()): ""
    ])
    pauseExecution(500)

    // Step 4 (optional): override the cloner's default new-app label. The
    // setting name is dynamic per source: settings[newName<sourceId>].
    def newNameField = "settings[newName${sourceAppId}]".toString()
    def newNameTypeKey = "newName${sourceAppId}.type".toString()
    def newNameMultKey = "newName${sourceAppId}.multiple".toString()
    if (newName) {
        _appClonerSubmitForm(clonerAppId, "importRule", "importRule", referrer, configUrl, [
            (newNameField): newName,
            (newNameTypeKey): "text",
            (newNameMultKey): "false"
        ])
    }

    // Step 5: click importNow — fires the actual create. The button is
    // named `importNow` regardless of which path (clone vs import) brought
    // us here. Same double-click pattern: first click drops, second commits.
    def btnBody = [
        id: clonerAppId.toString(),
        name: "importNow",
        ("settings[importNow]".toString()): "clicked",
        ("importNow.type".toString()): "button"
    ]
    def finalExtras = [
        (newNameField): (newName ?: ""),
        (newNameTypeKey): "text",
        (newNameMultKey): "false",
        ("importNow.type".toString()): "button",
        ("importNow.multiple".toString()): "false",
        ("settings[importNow]".toString()): ""
    ]
    for (int attempt = 0; attempt < 2; attempt++) {
        def resp = hubInternalPostForm("/installedapp/btn", btnBody)
        if (resp?.status != null && resp.status >= 400) {
            throw new IllegalStateException("appCloner importNow click failed: status=${resp.status}")
        }
        pauseExecution(500)
        // Form refresh on importRule that the UI fires after the click.
        // Body includes newName and importNow fields. The actual create
        // commits on the hub side during the second pass's form refresh.
        _appClonerSubmitForm(clonerAppId, "importRule", "importRule", referrer, configUrl, finalExtras)
        pauseExecution(500)
    }
}

private Integer _appClonerDiscoverNewChild(Integer parentAppId, Set<String> preCloneIds, String sourceLabel, String hint) {
    if (parentAppId == null) return null
    def afterCfg
    try {
        afterCfg = _rmFetchConfigJson(parentAppId)
    } catch (Exception e) {
        mcpLog("warn", "rm-native", "appCloner: parent ${parentAppId} fetch after commit failed: ${e.message}")
        return null
    }
    def afterIds = ((afterCfg?.childApps ?: []) as List).collect { it?.id?.toString() }.findAll { it }
    def added = afterIds.findAll { !preCloneIds.contains(it) }
    if (added.isEmpty()) return null
    if (added.size() == 1) return added[0] as Integer
    // Multiple new children: prefer hint match, then "<label> clone/import" prefix, else max id.
    def candidates = (afterCfg.childApps as List).findAll { added.contains(it?.id?.toString()) }
    def hintMatch = hint ? candidates.find { (it.label?.toString() ?: "") == hint } : null
    if (hintMatch) return hintMatch.id as Integer
    def labelMatch = sourceLabel ? candidates.find {
        def lbl = it.label?.toString() ?: ""
        lbl.startsWith("${sourceLabel} clone") || lbl.startsWith("${sourceLabel} import") || lbl.startsWith("Clone of ${sourceLabel}")
    } : null
    if (labelMatch) return labelMatch.id as Integer
    return (candidates.collect { it.id as Integer }.max()) as Integer
}

private boolean _appClonerCleanup(Integer clonerAppId) {
    if (clonerAppId == null) return false
    try {
        _rmForceDeleteApp(clonerAppId)
        mcpLog("debug", "rm-native", "appCloner: cleaned up temporary cloner ${clonerAppId}")
        return true
    } catch (Exception e) {
        mcpLog("warn", "rm-native", "appCloner: cleanup of temporary cloner ${clonerAppId} failed: ${e.message} -- a hidden 'Export/Import/Clone' app may remain; delete via hub_delete_native_app(appId=${clonerAppId}, force=true)")
        return false
    }
}

def toolCloneNativeApp(args) {
    requireDestructiveConfirm(args?.confirm as Boolean)
    def _srcRaw = (args?.sourceAppId != null) ? args.sourceAppId : args?.appId
    if (_srcRaw == null) throw new IllegalArgumentException("sourceAppId (or appId) is required")
    def sourceAppId = normalizeRuleId(_srcRaw)
    def newName = args?.newName?.toString()?.trim()

    def sourceCfg
    try {
        sourceCfg = _rmFetchConfigJson(sourceAppId)
    } catch (Exception sourceErr) {
        mcpLog("warn", "rm-native", "hub_clone_native_app: source ${sourceAppId} config fetch failed: ${sourceErr.message}")
        sourceCfg = null
    }
    if (!sourceCfg?.app) {
        throw new IllegalArgumentException("Source app ${sourceAppId} not found or unreadable")
    }
    def sourceLabel = sourceCfg.app.label?.toString()
    Integer parentAppId = null
    try {
        parentAppId = (sourceCfg.app.parentAppId != null) ? (sourceCfg.app.parentAppId.toString() as Integer) : null
    } catch (NumberFormatException ignored) {
        mcpLog("warn", "rm-native", "hub_clone_native_app: source ${sourceAppId} parentAppId not numeric: ${sourceCfg.app.parentAppId}")
    }

    def preIds = [] as Set
    if (parentAppId != null) {
        try {
            def pc = _rmFetchConfigJson(parentAppId)
            preIds = ((pc?.childApps ?: []) as List).collect { it?.id?.toString() }.findAll { it } as Set
        } catch (Exception preErr) {
            mcpLog("warn", "rm-native", "hub_clone_native_app: pre-clone parent ${parentAppId} fetch failed: ${preErr.message}; new-child discovery may misidentify a pre-existing app")
        }
    }

    def initRes = _appClonerInit(sourceAppId)
    Integer clonerAppId = initRes.clonerAppId
    String referrer = initRes.referrer
    String configUrl = initRes.configUrl

    try {
        // Step 2: click cloneRuleButton + form refresh — TWICE. Hubitat's
        // appCloner state machine drops the first click event silently
        // (verified live via Chrome XHR sniffing — same race the
        // hub_delete_variable wizard works around with a retry-once loop).
        // The second click+refresh commits the state transition to the
        // confirmation page.
        def btnBody = [
            id: clonerAppId.toString(),
            name: "cloneRuleButton",
            ("settings[cloneRuleButton]".toString()): "clicked",
            ("cloneRuleButton.type".toString()): "button"
        ]
        for (int attempt = 0; attempt < 2; attempt++) {
            hubInternalPostForm("/installedapp/btn", btnBody)
            pauseExecution(500)
            _appClonerSubmitForm(clonerAppId, "main", "source", referrer, configUrl, null)
            pauseExecution(500)
        }

        // Steps 3-5: navigate importRule, optional rename, click importNow.
        _appClonerCommitImportRule(clonerAppId, sourceAppId, newName, referrer, configUrl)

        Integer newAppId = _appClonerDiscoverNewChild(parentAppId, preIds, sourceLabel, newName)

        String note = newAppId
            ? "Cloned source ${sourceAppId} -> new app ${newAppId}${newName ? " (renamed to '${newName}')" : ""}. Use hub_set_native_app (or hub_set_rule for RM rules) to further customize."
            : "Clone fired but no new child appeared under parent ${parentAppId}. Re-check via hub_list_apps (scope='instances') shortly."
        def result = [
            success: newAppId != null,
            sourceAppId: sourceAppId,
            clonerAppId: clonerAppId,
            newAppId: newAppId,
            note: note
        ]
        if (newAppId == null) {
            // Cloner fired but child discovery returned no match. Surface the
            // structured isError/error fields callers branching on the
            // file-wide error contract expect (handleToolsCall flags isError
            // on the JSON-RPC envelope; LLM clients use it to route retries).
            result.isError = true
            result.error = note
        }
        return result
    } finally {
        // Reap the transient cloner on every path. The cloned rule is already a
        // child of the RM parent (discovered above), not of the cloner, so this
        // is safe. Prevents accumulating hidden 'Export/Import/Clone' apps (BUG-8).
        _appClonerCleanup(clonerAppId)
    }
}

def toolExportNativeApp(args) {
    def _srcRaw = (args?.sourceAppId != null) ? args.sourceAppId : args?.appId
    if (_srcRaw == null) throw new IllegalArgumentException("sourceAppId (or appId) is required")
    def sourceAppId = normalizeRuleId(_srcRaw)
    def saveAs = args?.saveAs?.toString()?.trim()

    def sourceCfg
    try {
        sourceCfg = _rmFetchConfigJson(sourceAppId)
    } catch (Exception sourceErr) {
        mcpLog("warn", "rm-native", "hub_export_native_app: source ${sourceAppId} config fetch failed: ${sourceErr.message}")
        sourceCfg = null
    }
    if (!sourceCfg?.app) {
        throw new IllegalArgumentException("Source app ${sourceAppId} not found or unreadable")
    }
    def sourceLabel = sourceCfg.app.label?.toString() ?: "app-${sourceAppId}"

    def initRes = _appClonerInit(sourceAppId)
    Integer clonerAppId = initRes.clonerAppId
    String referrer = initRes.referrer
    String configUrl = initRes.configUrl

    try {
        // Step 2: click exportRuleButton, then capture the form-refresh response.
        // Unlike clone (which writes persistent state and needs a double-click
        // to commit the state transition), export's serialized JSON is rendered
        // INTO the form-refresh POST response itself as
        // configPage.sections[].input[].filecontent — session-keyed and not
        // persisted to the cloner's settings. So we must capture the response
        // body of the same POST that fires the click rather than fetching the
        // cloner's state in a subsequent request (different session = bare view,
        // no JSON). One click is sufficient here.
        def btnBody = [
            id: clonerAppId.toString(),
            name: "exportRuleButton",
            ("settings[exportRuleButton]".toString()): "clicked",
            ("exportRuleButton.type".toString()): "button"
        ]
        hubInternalPostForm("/installedapp/btn", btnBody)
        pauseExecution(500)
        def refreshResp = _appClonerSubmitForm(clonerAppId, "main", "source", referrer, configUrl, null)
        String jsonContent = _appClonerExtractJsonFromResponse(refreshResp?.data)
        if (!jsonContent) {
            // Distinguish an unreadable response body (the struct read path returns
            // data:null on a 2xx when the body read fails mid-stream) from a genuine
            // format change, so the operator isn't sent chasing the wrong root cause.
            def reason = (refreshResp?.data == null)
                ? "the cloner response body was empty/unreadable (status ${refreshResp?.status})"
                : "no JSON content could be extracted (looked for configPage.sections[].input[].filecontent) — wire format may have changed"
            throw new IllegalStateException("appCloner export fired but ${reason} for cloner ${clonerAppId}")
        }

        def result = [
            success: true,
            sourceAppId: sourceAppId,
            sourceLabel: sourceLabel,
            clonerAppId: clonerAppId,
            contentLength: jsonContent.length(),
            jsonContent: jsonContent,
            note: "Exported source ${sourceAppId} via appCloner. Pass jsonContent to hub_import_native_app to re-create the rule."
        ]
        if (saveAs) {
            try {
                uploadHubFile(saveAs, jsonContent.getBytes("UTF-8"))
                result.savedAs = saveAs
                String hubIp = null
                try { hubIp = location?.hub?.localIP?.toString() } catch (Exception ignored) { /* fall through */ }
                if (hubIp) {
                    result.savedUrl = "http://${hubIp}/local/${saveAs}"
                } else {
                    // Don't emit a literally-broken http://<HUB_IP>/... URL — flag
                    // the lookup failure instead.
                    mcpLog("warn", "rm-native", "hub_export_native_app: location.hub.localIP unavailable; savedUrl omitted from result")
                }
            } catch (Exception fileErr) {
                result.saveError = fileErr.message
                mcpLog("warn", "rm-native", "hub_export_native_app: saveAs '${saveAs}' upload failed: ${fileErr.message}")
            }
        }
        return result
    } finally {
        // Reap the transient cloner on every path (success or throw) so repeated
        // exports don't accumulate hidden 'Export/Import/Clone' apps (BUG-8).
        _appClonerCleanup(clonerAppId)
    }
}

private String _appClonerExtractJsonFromResponse(String responseBody) {
    if (!responseBody || !(responseBody instanceof String)) return null
    def parsed
    try { parsed = new groovy.json.JsonSlurper().parseText(responseBody) }
    catch (Exception e) {
        mcpLog("debug", "rm-native", "appCloner: response JSON parse failed: ${e.message}")
        return null
    }
    def sections = (parsed instanceof Map) ? parsed?.configPage?.sections : null
    if (!(sections instanceof List)) return null
    for (section in sections) {
        // The filecontent lives on input[] entries (download-type inputs)
        // and is mirrored in body[] entries. Either works.
        for (key in ["input", "inputs", "body"]) {
            def items = section?."${key}"
            if (!(items instanceof List)) continue
            for (item in items) {
                def fc = (item instanceof Map) ? item.filecontent : null
                if (fc instanceof String && fc.contains("appReplacements") && fc.startsWith("{")) {
                    // Hubitat appCloner over-escapes multi-select enum values
                    // by one extra level when generating the download payload —
                    // its `filecontent` has `\\"` (2 backslashes + quote) where
                    // canonical JSON requires `\"` (1 backslash + quote). The
                    // result is malformed JSON that won't round-trip back into
                    // import. The pattern `\\"` is unreachable in valid JSON
                    // (a literal `\` + `"` would encode as `\\\"`), so we can
                    // safely collapse it.
                    return fc.replace('\\\\"', '\\"')
                }
            }
        }
    }
    return null
}

def toolImportNativeApp(args) {
    requireDestructiveConfirm(args?.confirm as Boolean)
    if (args?.parentHintAppId == null) {
        throw new IllegalArgumentException("parentHintAppId is required (any existing rule's id under the target parent — used to seed the cloner)")
    }
    def parentHintAppId = normalizeRuleId(args.parentHintAppId)
    def newName = args?.newName?.toString()?.trim()

    String jsonContent = args?.jsonContent?.toString()
    if (!jsonContent && args?.fromFile) {
        try {
            def bytes = downloadHubFile(args.fromFile.toString())
            jsonContent = new String(bytes, "UTF-8")
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot read fromFile '${args.fromFile}': ${e.message}")
        }
    }
    if (!jsonContent) {
        throw new IllegalArgumentException("jsonContent or fromFile is required")
    }

    // Parse to verify shape + extract original source id (for the dynamic
    // newName<id> field on the importRule sub-page).
    def parsed
    try { parsed = new groovy.json.JsonSlurper().parseText(jsonContent) }
    catch (Exception e) { throw new IllegalArgumentException("jsonContent is not valid JSON: ${e.message}") }
    def appReplacements = (parsed instanceof Map) ? parsed.appReplacements : null
    if (!(appReplacements instanceof Map) || appReplacements.isEmpty()) {
        throw new IllegalArgumentException("jsonContent does not contain an appReplacements map — not an appCloner export")
    }
    Integer originalSourceId = null
    try {
        originalSourceId = ((appReplacements.keySet() as List)[0]).toString() as Integer
    } catch (Exception e) {
        throw new IllegalArgumentException("Could not extract original source id from appReplacements: ${e.message}")
    }
    def originalLabel = appReplacements[originalSourceId.toString()]?.appLabel?.toString()

    // Snapshot pre-import children of the target parent.
    def parentHintCfg
    try {
        parentHintCfg = _rmFetchConfigJson(parentHintAppId)
    } catch (Exception hintErr) {
        mcpLog("warn", "rm-native", "hub_import_native_app: parentHintAppId ${parentHintAppId} fetch failed: ${hintErr.message}")
        parentHintCfg = null
    }
    if (!parentHintCfg?.app) {
        throw new IllegalArgumentException("parentHintAppId ${parentHintAppId} not found or unreadable")
    }
    Integer parentAppId = null
    try {
        parentAppId = (parentHintCfg.app.parentAppId != null) ? (parentHintCfg.app.parentAppId.toString() as Integer) : null
    } catch (NumberFormatException nfe) {
        mcpLog("warn", "rm-native", "hub_import_native_app: parentHintAppId ${parentHintAppId} parentAppId not numeric: ${parentHintCfg.app.parentAppId}; new-child discovery will be skipped")
    }
    if (parentAppId == null) {
        // Without a parent we can't diff children to identify the new rule.
        // Refuse rather than firing the wizard and reporting a false failure.
        throw new IllegalArgumentException("parentHintAppId ${parentHintAppId} has no numeric parentAppId — pass a hint that's a child of the target parent app (e.g. an existing RM rule for an RM import).")
    }
    def preIds = [] as Set
    try {
        def pc = _rmFetchConfigJson(parentAppId)
        preIds = ((pc?.childApps ?: []) as List).collect { it?.id?.toString() }.findAll { it } as Set
    } catch (Exception preErr) {
        mcpLog("warn", "rm-native", "hub_import_native_app: pre-import parent ${parentAppId} fetch failed: ${preErr.message}; new-child discovery may misidentify a pre-existing app")
    }

    def initRes = _appClonerInit(parentHintAppId)
    Integer clonerAppId = initRes.clonerAppId
    try {
        // For import the cloner's state machine validates session against the
        // local config URL (the page the user uploaded from). The OAuth source-
        // context URL the init returns is correct for clone (that's where the
        // cloneRuleButton lives) but trips a session check on the import path.
        // Verified live: same wire shape with OAuth referrer → no rule created;
        // with local-URL referrer → rule created. Use configUrl for both.
        String referrer = initRes.configUrl
        String configUrl = initRes.configUrl

        // Step 2: stage the JSON via settings[ruleUpload]= — single POST. The
        // UI fires this exactly once (file picker change → FileReader → one
        // jsonSubmit). A second pass is harmful here: the cloner has already
        // transitioned to restore-or-import state and the source-state form
        // body no longer matches.
        _appClonerSubmitForm(clonerAppId, "main", "source", referrer, configUrl, [
            ("settings[ruleUpload]".toString()): jsonContent
        ])
        // Cloner needs ~2s to JSON-parse large uploads + transition to
        // restore-or-import; <1s races on multi-KB exports.
        pauseExecution(2000)

        // Steps 3-5: navigate importRule, optional rename, click importNow.
        _appClonerCommitImportRule(clonerAppId, originalSourceId, newName, referrer, configUrl)

        Integer newAppId = _appClonerDiscoverNewChild(parentAppId, preIds, originalLabel, newName)

        String note = newAppId
            ? "Imported '${originalLabel ?: 'app'}' as new app ${newAppId}${newName ? " (renamed to '${newName}')" : ""}. Use hub_set_native_app (or hub_set_rule for RM rules) to further customize."
            : "Import fired but no new child appeared under parent ${parentAppId}. Re-check via hub_list_apps (scope='instances') shortly."
        def result = [
            success: newAppId != null,
            clonerAppId: clonerAppId,
            newAppId: newAppId,
            originalSourceId: originalSourceId,
            originalLabel: originalLabel,
            contentLength: jsonContent.length(),
            note: note
        ]
        if (newAppId == null) {
            // Wizard fired but child discovery returned no match. Same shape as
            // toolCloneNativeApp on the soft-failure path — see comment there.
            result.isError = true
            result.error = note
        }
        return result
    } finally {
        // Reap the transient cloner on every path. The imported rule is already a
        // child of the target parent (discovered above), not of the cloner.
        // Prevents accumulating hidden 'Export/Import/Clone' apps (BUG-8).
        _appClonerCleanup(clonerAppId)
    }
}

private Map _rmRestoreFromBackup(Map entry) {
    def fileName = entry.fileName
    def jsonBytes
    try {
        jsonBytes = downloadHubFile(fileName)
    } catch (Exception e) {
        throw new IllegalArgumentException("Cannot read RM backup file '${fileName}': ${e.message}")
    }
    def snapshot
    try {
        snapshot = new groovy.json.JsonSlurper().parseText(new String(jsonBytes, "UTF-8"))
    } catch (Exception e) {
        throw new IllegalArgumentException("Cannot parse RM backup file '${fileName}': ${e.message}")
    }
    if (snapshot?.schemaVersion != 1) {
        throw new IllegalArgumentException("Unsupported RM backup schemaVersion: ${snapshot?.schemaVersion} (expected 1)")
    }

    def savedId = snapshot.ruleId as Integer
    def savedSettings = (snapshot?.configJson?.settings ?: [:]) as Map
    def savedLabel = snapshot?.appLabel

    // Backup snapshots from before the appType-aware code path stored
    // type="rm-rule" without an appType field. Treat those as RM (the
    // only app type the original snapshots covered) for the recreate
    // path. Future snapshots can carry an explicit savedAppType field.
    def savedAppType = snapshot?.appType ?: "rule_machine"
    if (savedAppType == "visual_rule") {
        // Visual Rules don't speak the classic createchild + settings-replay protocol below
        // (incl. the configure/json exists-probe, which Vue children may not serve); their
        // snapshots carry the captured VRB definition and replay through the ruleBuilder
        // endpoints (impl in McpVisualRulesLib).
        return _vrbRestoreFromSnapshot(snapshot, fileName?.toString())
    }

    def exists = true
    try { _rmFetchConfigJson(savedId) } catch (Exception e) { exists = false }
    def reg = _appTypeRegistry()[savedAppType]
    if (!reg) {
        throw new IllegalArgumentException("Backup references unknown appType '${savedAppType}'. Supported: ${_appTypeRegistry().keySet().join(', ')}")
    }

    def ruleId
    if (exists) {
        ruleId = savedId
    } else {
        def parentId = _discoverParentAppId(savedAppType)
        ruleId = _rmCreateChildApp(parentId, reg.namespace, reg.appName)
        try {
            def firstPage = _rmFetchConfigJson(ruleId)
            def firstSchema = _rmCollectInputSchema(firstPage?.configPage)
            def seedBody = _rmBuildSettingsBody(ruleId, [origLabel: savedLabel ?: "restored-app-${savedId}"], firstSchema)
            hubInternalPostForm("/installedapp/update/json", seedBody)
            _rmClickAppButton(ruleId, "updateRule")
        } catch (Exception e) {
            try { _rmForceDeleteApp(ruleId) } catch (Exception ce) {
                mcpLog("warn", "rm-native", "_rmRestoreFromBackup: orphan cleanup of newly-created app ${ruleId} failed after recreate error (${ce.message}) -- app may be left in an empty-label state; clean up manually via hub_delete_native_app(appId=${ruleId})")
            }
            throw new IllegalArgumentException("Restore failed during app recreate (appType=${savedAppType}): ${e.message}")
        }
    }

    // Schema sourcing: configPage covers only the main page, but classic
    // apps (especially RM) keep most device pickers on sub-pages whose
    // schema isn't in the main configPage. Without their type+multiple
    // metadata, _rmBuildSettingsBody can't emit the .multiple=true
    // sidecar — exactly the poisoning we're trying to avoid. Supplement
    // from the snapshotted statusJson.appSettings, which carries the
    // live marshal flags for every setting regardless of which page
    // declared it. Page-derived entries take precedence (they include
    // `required` and other UI-only metadata) so this is additive only.
    def savedSchema = _rmCollectInputSchema(snapshot?.configJson?.configPage) ?: [:]
    snapshot?.statusJson?.appSettings?.each { s ->
        def n = s?.name?.toString()
        if (n && !savedSchema.containsKey(n)) {
            savedSchema[n] = [
                name: n,
                type: s?.type?.toString(),
                multiple: s?.multiple == true
            ]
        }
    }
    try {
        _rmUpdateAppSettings(ruleId, savedSettings, savedSchema)
        _rmClickAppButton(ruleId, "updateRule")
    } catch (Exception e) {
        return [
            success: false,
            type: "rm-rule",
            ruleId: ruleId,
            originalRuleId: savedId,
            error: "Restore applied partially; failed during settings replay: ${e.message}",
            note: "Rule ${ruleId} exists but may have incomplete settings. Inspect with hub_get_app_config."
        ]
    }

    return [
        success: true,
        type: "rm-rule",
        ruleId: ruleId,
        originalRuleId: savedId,
        recreated: !exists,
        backupFile: fileName,
        settingsApplied: savedSettings.keySet().toList(),
        note: exists ? "Settings restored in place." : "Rule was deleted; recreated with new id ${ruleId} and replayed settings."
    ]
}

def _getAllToolDefinitions_partAppCloner() {
    return [
        [
            name: "hub_clone_native_app",
            description: """Clone any classic native automation app (RM rule, Room Lighting, Button Controller, Basic Rule, Notifier, etc.) using Hubitat's first-party appCloner system app. Lower-overhead alternative to rebuilding via the wizard — clone an existing rule that has the shape you want, then surgically edit fields via hub_set_rule (RM rules) or hub_set_native_app (other classic apps). Preserves the full rule shape (state.actNdx, conditions, expressions, IF/THEN/ELSE positional arrays). Drives the appCloner's 4-step wizard (cloneRuleButton → confirmation → importRule sub-page → importNow); the actual clone fires in tens of seconds for typical rules. Returns newAppId on success. Requires the Write master + confirm=true (+ a recent backup).""",
            inputSchema: [
                type: "object",
                properties: [
                    sourceAppId: [type: "integer", description: "Installed-app ID of the rule/app to clone. (alias: appId)"],
                    appId: [type: "integer", description: "Alias for sourceAppId."],
                    newName: [type: "string", description: "Label for the new cloned app. If omitted, the cloner default ('<source-label> clone') is kept."],
                    confirm: [type: "boolean", description: "Must be true."]
                ],
                required: ["confirm"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "True when a new child app was created"],
                    sourceAppId: [type: "integer", description: "Source app ID"],
                    clonerAppId: [type: "integer", description: "Temporary cloner app ID (auto-deleted after the operation)"],
                    newAppId: [type: "integer", description: "New cloned app ID, or null on soft failure"],
                    isError: [type: "boolean", description: "Present (true) on soft failure"],
                    error: [type: "string", description: "Present on soft failure"],
                    note: [type: "string", description: "Human-readable result"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_export_native_app",
            description: """Export any classic native automation app to its canonical JSON shape via Hubitat's first-party appCloner. The exported JSON is the same format Hubitat's UI 'Export' button produces — a self-contained document with appReplacements + deviceReplacements + the full rule state — that round-trips cleanly through hub_import_native_app. Use for: (1) backup before risky edits, (2) edit-as-text workflows that materialize the rule, mutate the JSON, and re-import as a new rule, (3) hub-to-hub transfer. Pass saveAs to also write the JSON to the hub's File Manager (e.g. for HPM-style distribution). Requires the Write master (it instantiates a cloner app and persists, so it is a write operation; no confirm/backup required).""",
            inputSchema: [
                type: "object",
                properties: [
                    sourceAppId: [type: "integer", description: "Installed-app ID of the rule/app to export. (alias: appId)"],
                    appId: [type: "integer", description: "Alias for sourceAppId."],
                    saveAs: [type: "string", description: "Optional File Manager filename (.json or .txt). When provided, the export is also written to /local/<saveAs>."]
                ]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "Whether export succeeded"],
                    sourceAppId: [type: "integer", description: "Source app ID"],
                    sourceLabel: [type: "string", description: "Source app label"],
                    clonerAppId: [type: "integer", description: "Temporary cloner app ID (auto-deleted after the operation)"],
                    contentLength: [type: "integer", description: "Exported JSON length"],
                    jsonContent: [type: "string", description: "Exported rule JSON"],
                    savedAs: [type: "string", description: "File Manager filename; present with saveAs"],
                    savedUrl: [type: "string", description: "File URL; present when hub IP known"],
                    saveError: [type: "string", description: "Present if File Manager save failed"],
                    note: [type: "string", description: "Human-readable result"]
                ],
                required: ["success"]
            ]
        ],
        [
            name: "hub_import_native_app",
            description: """Create a new rule/app from a previously-exported JSON via Hubitat's first-party appCloner. Pair with hub_export_native_app for round-trip edits or backup/restore workflows. Pass jsonContent (the exported JSON string) OR fromFile (a File Manager filename written by hub_export_native_app). The cloner needs an existing rule under the target parent to seed itself — pass parentHintAppId (any existing rule id under the same parent app you want the imported rule to land under, e.g. another RM rule for an RM import). Requires the Write master + confirm=true (+ a recent backup).""",
            inputSchema: [
                type: "object",
                properties: [
                    jsonContent: [type: "string", description: "The exported JSON content. Either jsonContent or fromFile is required."],
                    fromFile: [type: "string", description: "File Manager filename to read the JSON from. Either jsonContent or fromFile is required."],
                    parentHintAppId: [type: "integer", description: "Any existing rule's id under the target parent app. Used purely to seed the cloner instance — has no semantic effect on the imported rule beyond placing it under the same parent."],
                    newName: [type: "string", description: "Label for the imported app. If omitted, the cloner default ('<original-label> import') is kept."],
                    confirm: [type: "boolean", description: "Must be true."]
                ],
                // "jsonContent OR fromFile" is enforced at runtime in
                // toolImportNativeApp (throws IllegalArgumentException) rather
                // than via a schema-level anyOf: Anthropic's MCP input_schema
                // validator rejects top-level anyOf/oneOf/allOf with HTTP 400
                // (first surfaced via Haiku 4.5; Sonnet/Opus accept the same
                // shape, but the constraint applies to every model going
                // forward). Tool + property descriptions document the OR for
                // LLM tool-selection.
                required: ["parentHintAppId", "confirm"]
            ],
            outputSchema: [
                type: "object",
                properties: [
                    success: [type: "boolean", description: "True when a new child app was created"],
                    clonerAppId: [type: "integer", description: "Temporary cloner app ID (auto-deleted after the operation)"],
                    newAppId: [type: "integer", description: "New imported app ID, or null on soft failure"],
                    originalSourceId: [type: "integer", description: "Original source app ID from the export"],
                    originalLabel: [type: "string", description: "Original app label, or null"],
                    contentLength: [type: "integer", description: "Imported JSON length"],
                    isError: [type: "boolean", description: "Present (true) on soft failure"],
                    error: [type: "string", description: "Present on soft failure"],
                    note: [type: "string", description: "Human-readable result"]
                ],
                required: ["success"]
            ]
        ],
    ]
}

def _idempotentWriteToolNames_partAppCloner() {
    // Retry-safe writes (MCP idempotentHint) for this library's tools -- contributed to the
    // app's getIdempotentWriteToolNames() aggregator; see the classification rules there.
    return [
        // Native rules / classic apps
        "hub_export_native_app"
    ]
}

def _toolDisplayMeta_partAppCloner() {
    // Human-facing title/summary per tool (MCP annotations.title + the Advanced per-tool
    // overrides menu) -- merged into the app's getToolDisplayMeta() aggregator (issue #209).
    return [
        hub_clone_native_app: [title: "Clone Native App", summary: "Clone an existing classic app."],
        hub_export_native_app: [title: "Export Native App", summary: "Export a classic app to JSON, optionally saving it to the File Manager."],
        hub_import_native_app: [title: "Import Native App", summary: "Import previously exported app JSON as a new instance."]
    ]
}
