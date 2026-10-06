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
    // A navigation submit (an _action_* marker) is one the UI validates first: refuse it where the
    // cloner's own page would, with the values this POST is about to send overlaid on its current ones.
    if (extras?.keySet()?.any { it.toString().startsWith("_action_") }) {
        def navCfg = null
        try { navCfg = _rmFetchConfigJson(clonerAppId, currentPage) } catch (Exception readExc) {
            // An unreadable page cannot be checked; the cloner's own flow verifies the transition.
            mcpLog("debug", "rm-native", "appCloner: page read for the navigation check on ${currentPage} failed (${readExc.message}); submitting unchecked")
        }
        if (navCfg?.configPage instanceof Map) {
            def navValues = (navCfg.settings instanceof Map) ? new LinkedHashMap(navCfg.settings as Map) : [:]
            extras.each { k, v ->
                def m = (k.toString() =~ /^settings\[(.+)\]$/)
                if (m.find()) navValues.put(m[0][1], v)
            }
            def navSchema = _rmCollectInputSchema(navCfg.configPage as Map)
            _requireUiNavigationValid(clonerAppId, "leaving cloner page '${currentPage}'".toString(), navSchema, navValues)
            // The page submits an unset input's defaultValue with the navigation; send what the check accepted.
            def defaults = navSchema.findAll { k, meta ->
                meta?.disabled != true && meta?.type != "button" && meta?.defaultValue != null && _uiValueIsEmpty(navValues.get(k), meta as Map)
            }.collectEntries { k, meta -> [(k): meta.defaultValue] }
            if (defaults) {
                def defaultBody = _rmBuildSettingsBody(clonerAppId, defaults, navSchema)
                defaultBody.remove("id")
                body.putAll(defaultBody)
            }
        }
    }
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

private Map _appClonerSnapshotChildren(Integer parentAppId) {
    // A missing baseline makes existing siblings look newly created. Never start
    // the wizard unless discovery can exclude every app already under the parent.
    try {
        def cfg = _rmFetchConfigJson(parentAppId)
        if (!(cfg.childApps instanceof List)) {
            throw new IllegalStateException("missing childApps list")
        }
        return [ids: cfg.childApps.collect { it?.id?.toString() }.findAll { it }]
    } catch (Exception e) {
        return [success: false, isError: true,
                error: "Cannot read the child-app snapshot for parent ${parentAppId}: ${e.message}".toString(),
                note: "Nothing was created. Restore access to the parent app and retry the clone/import."]
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
    // Concurrent creations can appear in the same diff. Only an unambiguous
    // requested name identifies our result; a prefix or largest ID is a guess.
    def candidates = (afterCfg.childApps as List).findAll { added.contains(it?.id?.toString()) }
    def hintMatches = hint ? candidates.findAll { (it.label?.toString() ?: "") == hint } : []
    if (hintMatches.size() == 1) return hintMatches[0].id as Integer
    mcpLog("warn", "rm-native", "appCloner: ambiguous new children ${added} for '${hint ?: sourceLabel}'; no app selected")
    return null
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

private void _appClonerClickClone(Integer clonerAppId, String referrer, String configUrl) {
    // Hubitat can swallow the first click; repeat both the click and form refresh.
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

    if (parentAppId == null) {
        throw new IllegalArgumentException("Source app ${sourceAppId} has no numeric parentAppId. MCP cannot safely discover its clone; pass a child of the target parent app.")
    }
    _requireUnprotectedAppMutation(parentAppId, "clone or import a child app under")
    Map snapshot = _appClonerSnapshotChildren(parentAppId)
    if (snapshot.isError == true) return snapshot
    def preIds = snapshot.ids as Set

    def initRes = _appClonerInit(sourceAppId)
    Integer clonerAppId = initRes.clonerAppId
    String referrer = initRes.referrer
    String configUrl = initRes.configUrl

    try {
        _appClonerClickClone(clonerAppId, referrer, configUrl)

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
        if (args?.stageDisabled == true && newAppId != null) {
            _appClonerApplyStageDisabled(result, newAppId, args?.__reqT0 as Long)
        } else if (args?.stageDisabled == true) {
            // Discovery-miss: the clone may well EXIST and be live -- the caller
            // asked for stage-inactive and must hear that it never ran.
            _appClonerStagingDiscoveryMiss(result, "clone")
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

    def exported = _appClonerExportJson(sourceAppId)
    String jsonContent = exported.json
    Integer clonerAppId = exported.clonerAppId
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
}

// Hubitat's App Cloner export of one app: [json, clonerAppId]. Throws when the cloner renders none.
Map _appClonerExportJson(Integer sourceAppId) {
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
            // no-content case, so the operator isn't sent chasing the wrong root cause.
            def reason
            if (refreshResp?.data == null) {
                reason = "the cloner response body was empty/unreadable (status ${refreshResp?.status})"
            } else {
                // A consistent no-content extraction here is a KNOWN failure for a source
                // rule that has a Required Expression: appCloner does not render downloadable
                // export content for such rules, so there is no filecontent to extract. Name
                // that cause and a workaround up front (it is the common trigger), then fall
                // back to the generic wire-format explanation for rules without one.
                reason = "no JSON content could be extracted (looked for configPage.sections[].input[].filecontent). " +
                    "Known cause: if the source rule has a Required Expression, appCloner export currently fails for it -- " +
                    "delete the Required Expression in the Rule Machine UI, export, then re-add it (no MCP tool removes a " +
                    "Required Expression: replaceRequiredExpression only swaps one for another); or edit the rule in place " +
                    "via hub_set_rule instead of export/import. " +
                    "If the rule has NO Required Expression, the cloner wire format may have changed"
            }
            throw new IllegalStateException("appCloner export fired but ${reason} for cloner ${clonerAppId}")
        }
        return [json: jsonContent, clonerAppId: clonerAppId]
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
    def originalLabel = appReplacements.get(originalSourceId.toString())?.appLabel?.toString()

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
    _requireUnprotectedAppMutation(parentAppId, "clone or import a child app under")
    Map snapshot = _appClonerSnapshotChildren(parentAppId)
    if (snapshot.isError == true) return snapshot
    def preIds = snapshot.ids as Set

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
        if (args?.stageDisabled == true && newAppId != null) {
            _appClonerApplyStageDisabled(result, newAppId, args?.__reqT0 as Long)
        } else if (args?.stageDisabled == true) {
            _appClonerStagingDiscoveryMiss(result, "import")
        }
        return result
    } finally {
        // Reap the transient cloner on every path. The imported rule is already a
        // child of the target parent (discovered above), not of the cloner.
        // Prevents accumulating hidden 'Export/Import/Clone' apps (BUG-8).
        _appClonerCleanup(clonerAppId)
    }
}

private Map _appClonerStagePlan(Integer newAppId) {
    // Enumerate every descendant BEFORE disabling anything: a parent's flag can
    // hide deeper levels. The primary traversal is recursive; the fallback is BFS.
    // Child Button Rules can subscribe independently, so staging only the parent
    // would leave live automation behind. A clone/import remains active until disabled.
    List failures = []
    List targets = []
    // Primary enumeration: ONE /hub2/appsList read carries the complete nested
    // app tree -- no per-node configPage render (which for a cloned Button
    // Controller with N children turned one read into N+1 expensive renders on
    // the same per-app load counters the sticky limiter watches).
    try {
        def responseText = hubInternalGet("/hub2/appsList")
        def parsed = new groovy.json.JsonSlurper().parseText(responseText)
        Map root = null
        def findNode
        findNode = { List nodes ->
            for (def n : (nodes ?: [])) {
                if (n?.data?.id?.toString() == newAppId.toString()) { root = n; return }
                findNode(n?.children as List)
                if (root != null) return
            }
        }
        findNode(parsed?.apps as List)
        if (root == null) throw new IllegalStateException("app ${newAppId} not present in /hub2/appsList")
        Set visited = [] as Set
        def collect
        collect = { Map node ->
            def nid = node?.data?.id?.toString()
            if (!nid?.isInteger() || visited.contains(nid)) return
            visited << nid
            targets << nid.toInteger()
            (node?.children as List ?: []).each { c -> if (c instanceof Map) collect(c) }
        }
        collect(root)
    } catch (Exception treeErr) {
        // Fallback: per-node configure/json BFS (the pre-appsList mechanism).
        mcpLog("warn", "rm-native", "stageDisabled: /hub2/appsList enumeration failed (${treeErr.message}) -- falling back to per-node configure/json BFS")
        targets = []
        Set visited = [] as Set
        List queue = [newAppId]
        while (queue) {
            Integer tid = queue.remove(0)
            if (visited.contains(tid)) continue
            visited << tid
            targets << tid
            try {
                def cfg = _rmFetchConfigJson(tid)
                ((cfg?.childApps ?: []) as List).each { c ->
                    def cid = c?.id?.toString()
                    if (cid?.isInteger()) queue << cid.toInteger()
                }
            } catch (Exception childErr) {
                failures << [appId: tid, kind: "childEnumeration", error: "child enumeration failed for app ${tid}: ${childErr.message} -- its DESCENDANTS (if any) were not discovered and were NOT disabled; app ${tid} itself is still attempted below"]
            }
        }
    }
    return [targets: targets.unique(), failures: failures]
}

private Map _appClonerDisableTargets(List targets, Long reqT0, List staged, List failures) {
    // Each disable includes read-back. Retain unattempted IDs when the budget
    // expires so the result/checkpoint keeps the committed app and a safe remedy.
    List stageRemaining = []
    for (int i = 0; i < targets.size(); i++) {
        if (i > 0 && _timeBudgetExceeded(reqT0)) {
            stageRemaining = targets.subList(i, targets.size()).collect { it }
            break
        }
        def tid = targets[i]
        def dres
        try { dres = toolSetAppDisabled([appId: tid, disabled: true]) }
        catch (Exception dErr) { dres = [success: false, error: dErr.message ?: dErr.toString()] }
        if (dres?.success == true) staged << tid
        else failures << [appId: tid, kind: "disable", error: dres?.error ?: "disable read-back mismatch"]
    }
    return [staged: staged.unique(), failures: failures, remaining: stageRemaining]
}

private void _appClonerFinishStaging(Map result, Integer newAppId, Map outcome) {
    // Staging is the requested safety property, but creation already committed.
    // Report partial failure with a disable remedy; retrying creation makes duplicates.
    List staged = outcome.staged
    List failures = outcome.failures
    List stageRemaining = outcome.remaining
    result.stagedDisabled = staged
    if (failures || stageRemaining) {
        if (failures) result.stageFailures = failures
        if (stageRemaining) result.stageRemaining = stageRemaining
        result.partial = true
        result.success = false
        result.isError = true
        def newAppItselfFailed = failures.any { it.kind == "disable" && it.appId == newAppId }
        def remainderText = stageRemaining ? "Response budget ran out before ${stageRemaining.size()} app(s) were disabled (${stageRemaining.join(', ')}). " : ""
        result.error = "stageDisabled did NOT fully land: ${failures.size()} failure(s)${stageRemaining ? " + ${stageRemaining.size()} not yet attempted" : ""} -- see stageFailures/stageRemaining. ${newAppItselfFailed ? "The NEW APP ITSELF (${newAppId}) is still ENABLED and live. " : ""}${remainderText}The clone/import DID commit (newAppId=${newAppId}) -- do NOT re-issue this call (that would create a duplicate app); disable the listed apps via hub_set_app_disabled(appId=<id>, disabled=true) instead."
        result.note = "${result.note} STAGING ${stageRemaining ? 'INCOMPLETE' : 'FAILED'} -- see error/stageFailures."
    } else {
        result.note = "${result.note} Staged inactive: ${staged.size()} app(s) disabled (the new app${staged.size() > 1 ? ' + descendants' : ''}); re-enable with hub_set_app_disabled(disabled=false)."
    }
}

private void _appClonerApplyStageDisabled(Map result, Integer newAppId, Long reqT0 = null) {
    Map plan = _appClonerStagePlan(newAppId)
    Map outcome = _appClonerDisableTargets(plan.targets as List, reqT0, [], plan.failures as List)
    _appClonerFinishStaging(result, newAppId, outcome)
}

private void _appClonerStagingDiscoveryMiss(Map result, String operationLabel) {
    result.stagedDisabled = []
    result.success = false
    result.isError = true
    result.error = "${result.error ?: ''} stageDisabled was requested but could NOT run: no newAppId was discovered. If the ${operationLabel} did land, it is ENABLED and live -- find it via hub_list_apps (scope='instances') and disable it with hub_set_app_disabled. Do NOT re-issue creation: it may already have committed.".toString().trim()
}

private Map _appClonerCappedStaging(Map cp) {
    // The latest returned checkpoint includes the final slice's disables. The
    // generic MRTR aggregate has no cloner ledger, and terminal storage drops cp.
    Map result = (cp.baseResult instanceof Map) ? ([:] + cp.baseResult) : [:]
    _appClonerFinishStaging(result, cp.newAppId as Integer, [
        staged: (cp.stagedDisabled ?: []) as List,
        failures: (cp.stageFailures ?: []) as List,
        remaining: (cp.stageTargets ?: []) as List
    ])
    return result
}

private Map _rmReadBackupSnapshot(Map entry) {
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

    return snapshot as Map
}

// Hubitat's App Cloner export of an app, kept in its backup so a restore can bring back app
// state (triggers, actions, IF conditions) that a settings replay cannot. A rule with a Required
// Expression is never exported (the cloner renders nothing for it).
Map _rmNativeExportForBackup(Integer appId) {
    try {
        return [json: _appClonerExportJson(appId).json]
    } catch (Exception e) {
        mcpLog("warn", "rm-native", "Backup for app ${appId}: App Cloner export failed (${e.message}); this backup restores by settings replay")
        return [error: e.message ?: e.toString()]
    }
}

// Restore through Hubitat's own App Cloner import: an exact copy (settings and app state) as a NEW
// app, then the old app is deleted. Returns [fallback: reason] for the settings replay when the
// export cannot be used: unparseable, no Rule Machine rule left to seed the import, or a device that
// no longer exists (an import would not reuse it). Throws IllegalArgumentException, before anything
// is created, when the old rule cannot be deleted because of app protection.
private Map _rmRestoreViaNativeImport(Map snapshot, Integer savedId, boolean exists, String fileName) {
    def parsed
    try { parsed = new groovy.json.JsonSlurper().parseText(snapshot.nativeExport.toString()) } catch (Exception e) {
        mcpLog("warn", "rm-native", "Restore of app ${savedId}: the backup's App Cloner export does not parse (${e.message}); restoring by settings replay")
        return [fallback: "the backup's App Cloner export could not be parsed"]
    }
    def gone = [:]
    def deadDevices = ((parsed?.deviceReplacements instanceof Map) ? (parsed.deviceReplacements as Map).keySet() : [])
        .collect { it.toString() }.findAll { _rmDeviceGone(it, gone) }
    if (deadDevices) {
        mcpLog("info", "rm-native", "Restore of app ${savedId}: device(s) ${deadDevices} no longer exist, so the App Cloner import is skipped for the settings replay")
        return [fallback: "device(s) ${deadDevices.join(', ')} named by the App Cloner export no longer exist".toString()]
    }
    Integer hint = exists ? savedId : null
    if (hint == null) {
        try {
            def kids = _appClonerSnapshotChildren(_discoverParentAppId(snapshot?.appType ?: "rule_machine"))
            hint = (kids?.ids ?: []).collect { it.toString() }.find { it.isInteger() }?.toInteger()
        } catch (Exception e) {
            mcpLog("warn", "rm-native", "Restore of app ${savedId}: finding a Rule Machine rule to seed the import failed (${e.message})")
            hint = null
        }
        if (hint == null) return [fallback: "no Rule Machine rule was found to seed the App Cloner import"]
    }
    // The old rule is deleted after the import, so its protection is checked before anything is created.
    if (exists) {
        try { _requireUnprotectedAppDeletion(savedId) } catch (IllegalArgumentException protExc) {
            throw new IllegalArgumentException("Cannot restore rule ${savedId} as a new copy, because the old rule could not then be deleted: ${protExc.message} Pass preserveRuleId:true to restore it in place instead.".toString(), protExc)
        }
    }
    def label = snapshot?.appLabel ?: "rule ${savedId}"
    def imp
    try {
        imp = toolImportNativeApp([jsonContent: snapshot.nativeExport, parentHintAppId: hint, stageDisabled: true, confirm: true])
    } catch (Exception impExc) {
        mcpLog("error", "rm-native", "Restore of app ${savedId}: the App Cloner import threw (${impExc.message})")
        imp = [success: false, clonerAppId: -1, error: impExc.message ?: impExc.toString()]
    }
    Integer newId = imp?.newAppId as Integer
    if (newId == null) {
        def out = [success: false, type: "rm-rule", ruleId: savedId, originalRuleId: savedId, restoredVia: "nativeImport", backupFile: fileName]
        if (imp?.clonerAppId == null) {
            // The import never started, so nothing was created.
            out.error = "The App Cloner import of the backup could not start: ${imp?.error ?: imp?.note}".toString()
            out.note = "Nothing was created or deleted. Retry, or pass preserveRuleId:true to restore rule ${savedId} in place by settings replay.".toString()
            return out
        }
        // The import ran, but its copy was not found: it may exist, enabled and running.
        out.partial = true
        out.error = "The App Cloner import of the backup ran, but its new app could not be identified: ${imp?.error ?: imp?.note}".toString()
        out.note = ("A copy labelled '${label}' may now exist under Rule Machine, enabled. Find it with hub_list_apps(scope='instances') and disable or delete it before anything else. " +
            (exists ? "Rule ${savedId} was NOT deleted. " : "") + "Do not retry the restore until you have checked.").toString()
        return out
    }
    if (imp?.success != true) {
        // A copy exists but may not be disabled, so the caller decides what stays.
        def failed = [success: false, partial: true, type: "rm-rule", ruleId: savedId, originalRuleId: savedId, importedAppId: newId,
                      restoredVia: "nativeImport", backupFile: fileName,
                      error: "The backup was imported as app ${newId}, but staging it failed: ${imp?.error ?: imp?.note}".toString(),
                      note: (exists ?
                          "Rule ${savedId} was NOT deleted, and app ${newId} may be running alongside it. Delete app ${newId} (hub_delete_native_app) to go back to rule ${savedId}, or delete rule ${savedId} to keep the copy. Do not retry the restore before one of them is gone." :
                          "App ${newId} is the restored rule, but it may not be disabled as intended. Check it with hub_get_app_config(appId=${newId}); do not retry the restore, which would create another copy.").toString()]
        if (imp?.stageFailures) failed.stageFailures = imp.stageFailures
        return failed
    }
    def out = [success: true, type: "rm-rule", ruleId: newId, originalRuleId: savedId, recreated: true,
               restoredVia: "nativeImport", backupFile: fileName]
    if (exists) {
        def del
        try { del = toolDeleteNativeApp([appId: savedId, confirm: true]) } catch (Exception delExc) {
            del = [success: false, error: delExc.message ?: delExc.toString()]
        }
        if (del?.success != true) {
            out.success = false
            out.partial = true
            out.error = "The backup was imported as app ${newId}, but the old rule ${savedId} could not be deleted: ${del?.hubMessage ?: del?.error}".toString()
            out.note = "App ${newId} is the restored copy and is left DISABLED so the two never run together. Delete rule ${savedId} (hub_delete_native_app, force:true if it has children), then enable ${newId} with hub_set_app_disabled(disabled=false).".toString()
            return out
        }
        out.replacedRuleBackup = del?.backup?.backupKey
    }
    // The import is staged disabled; leave it disabled only when the app was disabled at backup time.
    def reenableFailed = []
    if (snapshot?.configJson?.app?.disabled != true) {
        def failed = (imp.stagedDisabled ?: [newId]).findAll { id ->
            try { toolSetAppDisabled([appId: id, disabled: false])?.success != true } catch (Exception enableExc) {
                mcpLog("warn", "rm-native", "Restore of app ${savedId}: re-enabling app ${id} threw (${enableExc.message})")
                true
            }
        }
        if (failed) {
            reenableFailed = failed
            out.success = false
            out.partial = true
            out.error = "The backup was restored as app ${newId}, but app(s) ${failed} could not be re-enabled.".toString()
        }
    }
    out.note = ("Restored with Hubitat's App Cloner import as a NEW app ${newId} -- an exact copy of the backup, triggers and actions included" +
        (exists ? "; the old rule ${savedId} was deleted" : "") +
        ". Update anything that referenced rule ${savedId} (Run Rule actions, dashboards). Pass preserveRuleId:true to restore in place by settings replay instead." +
        (reenableFailed ? " Re-enable app(s) ${reenableFailed} with hub_set_app_disabled(disabled=false)." : "")).toString()
    return out
}

private Map _rmRestoreFromBackup(Map entry, Map preparedSnapshot = null, boolean preserveRuleId = false) {
    def fileName = entry.fileName
    Map snapshot = preparedSnapshot != null ? preparedSnapshot : _rmReadBackupSnapshot(entry)
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

    _requireUnprotectedAppMutation(savedId, "restore settings for")
    def exists = true
    try {
        _rmFetchConfigJson(savedId)
    } catch (Exception e) {
        def liveApps = _collectLiveApps()
        if (liveApps == null || liveApps.containsKey(savedId)) {
            String detail = liveApps == null ? "the app inventory could not confirm its absence" : "it is still present in the app inventory"
            mcpLog("warn", "rm-native", "Restore target ${savedId} could not be inspected (${e.message}); ${detail}")
            return [success: false, type: "rm-rule", ruleId: savedId, originalRuleId: savedId,
                    error: "Cannot restore rule ${savedId}: its configuration could not be read and ${detail}.",
                    note: "No replacement was created and no settings were changed. Inspect hub_list_apps and hub_get_app_config(appId=${savedId}), then retry when the rule is readable or its deletion is confirmed."]
        }
        exists = false
    }
    def reg = _appTypeRegistry()[savedAppType]
    if (!reg) {
        throw new IllegalArgumentException("Backup references unknown appType '${savedAppType}'. Supported: ${_appTypeRegistry().keySet().join(', ')}")
    }
    String nativeSkipped = null
    if (!preserveRuleId && snapshot?.nativeExport) {
        if (savedAppType != "rule_machine") {
            nativeSkipped = "App Cloner restore is used for Rule Machine rules only"
        } else {
            def nativeOut = _rmRestoreViaNativeImport(snapshot, savedId, exists, fileName?.toString())
            if (nativeOut?.fallback) nativeSkipped = nativeOut.fallback.toString()
            else return nativeOut
        }
    }

    def ruleId
    if (exists) {
        ruleId = savedId
        // A disabled app renders no page, and a settings write against it reports success having
        // changed nothing -- the restore would then claim "restored in place". Same gate as an edit.
        _rmRejectDisabledAppEdit(ruleId, "restore")
    } else {
        def parentId = _discoverParentAppId(savedAppType)
        _requireUnprotectedAppMutation(parentId, "restore a child app under")
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
            savedSchema.put(n, [
                name: n,
                type: s?.type?.toString(),
                multiple: s?.multiple == true
            ])
        }
    }
    // Two steps that fail differently: a rejected replay leaves the rule as it was (nothing is
    // committed on a 4xx/5xx), while a failed updateRule click after a committed replay leaves the
    // restored settings in place with subscriptions not yet rebuilt. Name the step, and log it --
    // a caller reading only the envelope could not tell the two apart (seen live: a 500 on rule 37).
    // A button input has no persisted value: RM keeps its row buttons (edit/insert/cut/disable, and
    // the bare "1"/"2"/"3" condition-row buttons) in settings with an empty value, and replaying
    // those through the update endpoint is at best a no-op and at worst a press. The replay of one
    // such snapshot answered 500 on a live 2.5.1.177 hub; the same shapes replay cleanly without
    // the button rows. Replay only value-bearing inputs.
    def replaySettings = savedSettings.findAll { k, v -> savedSchema.get(k.toString())?.type != "button" }
    def skippedButtons = (savedSettings.keySet() - replaySettings.keySet()).collect { it.toString() }.sort()
    // A device picker is snapshotted as configure/json renders it, an {id: label} map.
    // statusJson's deviceIdsForDeviceList (the id list the hub stores for the picker) is used when
    // the snapshot carries it; otherwise the map's keys are the id list.
    // An EMPTY id list is not authoritative (every sibling reader falls back on empty or absent):
    // trusting it would post an empty picker and report the key applied.
    def liveDeviceIds = [:]
    snapshot?.statusJson?.appSettings?.each { st ->
        def n = st?.name?.toString()
        if (n && st?.deviceIdsForDeviceList instanceof List && st.deviceIdsForDeviceList) liveDeviceIds.put(n, st.deviceIdsForDeviceList)
    }
    def skippedMaps = []
    // A device deleted since the backup must not go back into a picker: RM's pages then dereference
    // a null device and stop rendering (seen live on fw 2.5.2.129).
    def deviceGone = [:]
    def skippedDevices = []
    replaySettings = replaySettings.collectEntries { k, v ->
        String key = k.toString()
        boolean isPicker = _isDevicePickerType(savedSchema.get(key)?.type)
        if (!isPicker) {
            // A Map that is NOT a device picker cannot be replayed through the settings endpoint as
            // its keys; rewriting it silently would be a value change reported as applied.
            if (v instanceof Map) { skippedMaps << key; return [:] }
            return [(key): v]
        }
        def ids = liveDeviceIds.containsKey(key) ? liveDeviceIds.get(key) : _devicePickerIds(v)
        if (ids instanceof List) {
            ids = ids.collect { it?.toString() }
            def gone = ids.findAll { _rmDeviceGone(it, deviceGone) }
            if (gone) {
                skippedDevices << [key: key, ids: gone]
                ids = ids - gone
                if (!ids) return [:]
            }
        }
        return [(key): ids]
    }
    // Finish the way the app type commits: Rule Machine's updateRule, or for an app without that
    // button (Basic Rule, Button Controller) the page's Done -- clicking updateRule there stores it
    // as a pending action delete and the page then fails to load.
    String commitButton = reg.containsKey("commitButton") ? reg.commitButton : _resolveCommitButton(snapshot?.configJson?.app?.appType?.name?.toString())
    boolean isRm = savedAppType == "rule_machine"
    def settingsCleared = []
    def clearMiss = null
    def commitMiss = null
    String step = "settings replay"
    try {
        _rmUpdateAppSettings(ruleId, replaySettings, savedSchema)
        if (exists && !isRm) {
            // A replay writes back only the backup's keys; a key the app gained later would stay and
            // mix with the restored values. Rule Machine's own removal handles its rows (reconcile below).
            try { settingsCleared = _rmClearSettingsNotInBackup(ruleId, savedSettings) } catch (Exception clearExc) {
                mcpLog("warn", "rm-native", "restore of app ${ruleId}: emptying settings the backup did not have failed (${clearExc.message})")
                clearMiss = clearExc.message ?: clearExc.toString()
            }
        }
        if (commitButton) {
            step = "the final ${commitButton} click".toString()
            _rmClickAppButton(ruleId, commitButton)
        } else {
            step = "the closing Done"
            def done = _rmSubmitMainPageDone(ruleId)
            if (done?.done != true) commitMiss = done?.reason ?: "the page's Done did not commit"
        }
    } catch (Exception e) {
        mcpLog("error", "rm-native", "restore of rule ${ruleId} from ${fileName} failed during ${step}: ${e.message}")
        return [
            success: false,
            type: "rm-rule",
            ruleId: ruleId,
            originalRuleId: savedId,
            failedStep: step,
            restoredVia: "settingsReplay",
            nativeImportSkipped: nativeSkipped,
            error: "Restore applied partially; failed during ${step}: ${e.message}",
            note: (step == "settings replay"
                ? "Rule ${ruleId} exists but may have incomplete settings. Inspect with hub_get_app_config(appId=${ruleId}) and compare against hub_get_backup(backupKey) before retrying."
                : (commitButton == "updateRule"
                    ? "The settings were replayed but the rule's updateRule did not fire, so its subscriptions may still reflect the pre-restore state. Open the rule and click Update Rule, or call hub_set_rule(appId=${ruleId}, button='updateRule')."
                    : "The settings were replayed but the app's ${commitButton ?: 'Done'} did not run, so its subscriptions may still reflect the pre-restore state. Open the app and click ${commitButton ?: 'Done'}, or call hub_set_native_app(appId=${ruleId}${commitButton ? ", button='" + commitButton + "'" : ''}).")).toString()
        ]
    }

    // A skipped BUTTON is nothing lost -- a button input holds no state. A skipped MAP is saved
    // state this restore did not put back, so the envelope says partial: a caller that reads
    // success:true and stops would leave the rule short of its snapshot without being told.
    def out = [
        success: true,
        type: "rm-rule",
        ruleId: ruleId,
        originalRuleId: savedId,
        recreated: !exists,
        restoredVia: "settingsReplay",
        backupFile: fileName,
        settingsApplied: replaySettings.keySet().toList(),
        settingsSkipped: skippedButtons.collect { key -> [key: key, reason: "button input excluded from replay"] }
            + skippedMaps.collect { key -> [key: key, reason: "map value without a device-picker schema; not replayable through the settings endpoint"] }
            + skippedDevices.collect { sd -> [key: sd.key, reason: "device(s) ${sd.ids.join(', ')} no longer exist on the hub; left out of the replay".toString()] },
        note: exists ? "Settings restored in place." : "Rule was deleted; recreated with new id ${ruleId} and replayed settings."
    ]
    if (settingsCleared) {
        out.settingsCleared = settingsCleared
        out.note = "${out.note} Settings the app gained after the backup were emptied: ${settingsCleared.join(', ')}.".toString()
    }
    if (clearMiss) {
        out.partial = true
        out.note = "${out.note} Settings the app gained after the backup could not be emptied (${clearMiss}); compare hub_get_app_config(appId=${ruleId}, includeSettings=true) with hub_get_backup.".toString()
    }
    if (commitMiss) {
        out.partial = true
        out.note = "${out.note} The app's closing Done did not commit (${commitMiss}); open the app and click Done.".toString()
    }
    if (nativeSkipped) {
        out.nativeImportSkipped = nativeSkipped
        out.note = "${out.note} The backup's App Cloner copy was not used: ${nativeSkipped}.".toString()
    }
    if (skippedDevices) {
        out.partial = true
        out.note = "${out.note} Device(s) deleted since the backup were left out: ${skippedDevices.collect { "${it.key} (${it.ids.join(', ')})" }.join(', ')}. Pick replacements with hub_set_rule if the rule needs them.".toString()
    }
    if (skippedMaps) {
        out.partial = true
        out.note = "${out.note} ${skippedMaps.size()} saved setting(s) could NOT be replayed (see settingsSkipped): ${skippedMaps.join(', ')}. Inspect with hub_get_app_config(appId=${ruleId}) and set them by hand if the rule needs them.".toString()
    }
    // A Required Expression lives in RM app state, which the settings replay cannot write: bring it
    // back to the snapshot's expression explicitly, and never report success over a missing gate.
    // Both live in Rule Machine's own app state; other app types have neither.
    def reOutcome = [:]
    def structure = [:]
    if (isRm) {
        try {
            reOutcome = _rmRestoreRequiredExpression(ruleId, snapshot)
        } catch (Exception reExc) {
            reOutcome = [requiredExpressionRestored: false, requiredExpressionError: reExc.message ?: reExc.toString()]
        }
        try {
            structure = _rmReconcileRuleStructure(ruleId, snapshot)
        } catch (Exception stExc) {
            structure = [structureRestored: false, structureError: stExc.message ?: stExc.toString()]
        }
    }
    if (reOutcome) out.putAll(reOutcome)
    if (structure) out.putAll(structure)
    if (out.leftoverConditionIds && structure?.removedConditionIds) {
        def gone = (structure.removedConditionIds as List)*.toString()
        out.leftoverConditionIds = (out.leftoverConditionIds as List).findAll { !gone.contains(it.toString()) }
        if (!out.leftoverConditionIds) out.remove("leftoverConditionIds")
    }
    if (structure?.structureRestored == false) {
        out.success = false
        out.partial = true
        out.error = "Settings were restored, but the rule's triggers/actions do not match the backup: ${structure.structureError}".toString()
        out.note = "${out.note} Triggers and actions live in Rule Machine state, which a settings replay cannot rebuild -- add the missing ones with hub_set_rule(addTriggers / addActions), checking hub_get_app_config(appId=${ruleId}) against hub_get_backup.".toString()
    } else if (structure?.removedTriggers || structure?.removedActions || structure?.removedConditionIds) {
        out.note = "${out.note} Removed what the backup did not have (triggers ${structure.removedTriggers ?: []}, actions ${structure.removedActions ?: []}, conditions ${structure.removedConditionIds ?: []}).".toString()
    }
    if (reOutcome?.requiredExpressionRestored == false) {
        out.success = false
        out.partial = true
        out.error = [out.error, "Settings were restored, but the rule's Required Expression does not match the backup: ${reOutcome.requiredExpressionError}"].findAll { it }.join(" ").toString()
        out.note = "${out.note} The Required Expression was NOT restored -- inspect it with hub_get_app_config(appId=${ruleId}) and rebuild it with hub_set_rule(addRequiredExpression or replaceRequiredExpression).".toString()
    } else if (reOutcome?.requiredExpressionRemoved == true) {
        out.note = "${out.note} The backup had no Required Expression, so the rule's expression was removed.".toString()
    } else if (reOutcome?.requiredExpressionRestored == true) {
        out.note = "${out.note} Required Expression confirmed to match the backup.".toString()
    }
    return out
}

// Empty every non-button setting the app has that the backup does not, through the same update
// endpoint as the replay (an emptied setting reads as unset; the hub keeps no way to delete one).
// Returns the keys emptied.
List _rmClearSettingsNotInBackup(Integer appId, Map savedSettings) {
    def status = _rmFetchStatusJson(appId)
    if (!(status?.appSettings instanceof List)) return []
    def saved = savedSettings.keySet().collect { it.toString() } as Set
    def extra = (status.appSettings as List).findAll { rec ->
        def n = rec?.name?.toString()
        n && !saved.contains(n) && rec?.type?.toString() != "button" && rec?.value != null && rec?.value != ""
    }
    if (!extra) return []
    def body = [id: appId.toString()]
    extra.each { rec ->
        def n = rec.name.toString()
        body["settings[${n}]".toString()] = ""
        if (rec.type) body["${n}.type".toString()] = rec.type.toString()
        body["${n}.multiple".toString()] = (rec.multiple == true).toString()
    }
    _rmPostSettings(appId, body)
    return extra.collect { it.name.toString() }.sort()
}

// True only when the hub positively says the device is gone (404 or an empty object). An empty or
// unreadable answer keeps the id: dropping a live device from a restore is worse than keeping it.
private boolean _rmDeviceGone(String id, Map cache) {
    if (!id) return false
    if (cache.containsKey(id)) return cache.get(id) as boolean
    boolean gone = false
    try {
        def txt = hubInternalGet("/device/fullJson/${id}")
        if (txt) {
            def parsed = new groovy.json.JsonSlurper().parseText(txt)
            gone = (parsed instanceof Map) && parsed.isEmpty()
        }
    } catch (Exception e) {
        // Without a status, only the hub's own "status code: 404 ... Not Found" wording counts.
        Integer status = _httpStatusOf(e)
        def msg = e.message ?: ""
        gone = (status != null) ? status == 404 : (msg ==~ /(?s).*status code:? 404\b.*/ && msg.contains("Not Found"))
    }
    cache.put(id, gone)
    return gone
}

def _getAllToolDefinitions_partAppCloner() {
    return [
        [
            name: "hub_clone_native_app",
            description: """Clone any classic native automation app (RM rule, Room Lighting, Button Controller, Basic Rule, Notifier, etc.) using Hubitat's first-party appCloner, then surgically edit fields via hub_set_rule (RM rules) or hub_set_native_app (other classic apps).[[FLAT_TRIM]] A lower-overhead alternative to rebuilding via the wizard: clone an existing rule that has the shape you want. Preserves the full rule shape (conditions, expressions, IF/THEN/ELSE structure). The clone completes in tens of seconds for typical rules.[[/FLAT_TRIM]] Returns newAppId on success. Requires the Write master + confirm=true (+ a recent backup).""",
            inputSchema: [
                type: "object",
                properties: [
                    sourceAppId: [type: "integer", description: "Installed-app ID of the rule/app to clone. (alias: appId) Either sourceAppId or appId is required."],
                    appId: [type: "integer", description: "Alias for sourceAppId."],
                    newName: [type: "string", description: "Label for the new cloned app.[[FLAT_TRIM]] If omitted, the cloner default ('<source-label> clone') is kept.[[/FLAT_TRIM]]"],
                    stageDisabled: [type: "boolean", description: "true = disable the new app AND every DESCENDANT under it right after the clone. Staged-migration safety: a clone of an ACTIVE rule lands ACTIVE, and a cloned Button Controller's child Button Rules react to live button events. Re-enable via hub_set_app_disabled(disabled=false)."],
                    confirm: [type: "boolean", description: "Must be true."]
                ],
                // "sourceAppId OR appId" can't be a schema-level anyOf (Anthropic's
                // input_schema validator rejects top-level anyOf/oneOf/allOf); enforced
                // at runtime in toolCloneNativeApp. sourceAppId's description documents the OR.
                required: ["confirm"]
            ]
        ],
        [
            name: "hub_export_native_app",
            description: """Export any classic native automation app to its canonical JSON shape via Hubitat's first-party appCloner — a self-contained document that round-trips cleanly through hub_import_native_app.[[FLAT_TRIM]] The exported JSON is the same format Hubitat's UI 'Export' button produces. Use for: (1) backup before risky edits, (2) edit-as-text workflows that materialize the rule, mutate the JSON, and re-import as a new rule, (3) hub-to-hub transfer.[[/FLAT_TRIM]] Pass saveAs to also write the JSON to the hub's File Manager[[FLAT_TRIM]] (e.g. for HPM-style distribution)[[/FLAT_TRIM]]. Requires the Write master (no confirm/backup required).[[FLAT_TRIM]] Export instantiates a cloner app and persists, so it counts as a write.[[/FLAT_TRIM]]""",
            inputSchema: [
                type: "object",
                properties: [
                    sourceAppId: [type: "integer", description: "Installed-app ID of the rule/app to export. (alias: appId)"],
                    appId: [type: "integer", description: "Alias for sourceAppId."],
                    saveAs: [type: "string", description: "Optional File Manager filename (.json or .txt). When provided, the export is also written to /local/<saveAs>."],
                ]
            ]
        ],
        [
            name: "hub_import_native_app",
            description: """Create a new rule/app from a previously-exported JSON via Hubitat's first-party appCloner.[[FLAT_TRIM]] Pair with hub_export_native_app for round-trip edits or backup/restore workflows.[[/FLAT_TRIM]] Pass jsonContent (the exported JSON string) OR fromFile (a File Manager filename written by hub_export_native_app). Pass parentHintAppId — any existing rule id under the target parent the imported rule should land under[[FLAT_TRIM]] (e.g. another RM rule for an RM import); the cloner needs it to seed itself[[/FLAT_TRIM]]. Requires the Write master + confirm=true (+ a recent backup).""",
            inputSchema: [
                type: "object",
                properties: [
                    jsonContent: [type: "string", description: "The exported JSON content. Either jsonContent or fromFile is required."],
                    fromFile: [type: "string", description: "File Manager filename to read the JSON from."],
                    parentHintAppId: [type: "integer", description: "Any existing rule's id under the target parent app.[[FLAT_TRIM]] Used purely to seed the cloner instance — has no semantic effect on the imported rule beyond placing it under the same parent.[[/FLAT_TRIM]]"],
                    newName: [type: "string", description: "Label for the imported app.[[FLAT_TRIM]] If omitted, the cloner default ('<original-label> import') is kept.[[/FLAT_TRIM]]"],
                    stageDisabled: [type: "boolean", description: "true = disable the new app AND every DESCENDANT under it right after the import (staged-migration safety: an import lands ACTIVE). Re-enable via hub_set_app_disabled(disabled=false)."],
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

private Map _mrtrCloneNativeAppSlice(Map rec, Map outerArgs) {
    Map args = _mrtrLeafArguments(rec.outerTool?.toString(), rec.leafTool?.toString(), outerArgs) as Map
    // Deep copy: the staging phases append to the checkpoint's own lists, and a
    // shallow copy would land those appends in the shared requestState record
    // before the slice decides what to store.
    Map cp = (rec.checkpoint instanceof Map) ? _mrtrCopyMap(rec.checkpoint as Map) : null
    if (cp == null) {
        requireDestructiveConfirm(args?.confirm as Boolean)
        def rawSource = (args?.sourceAppId != null) ? args.sourceAppId : args?.appId
        if (rawSource == null) throw new IllegalArgumentException("sourceAppId (or appId) is required")
        Integer sourceAppId = normalizeRuleId(rawSource)
        String newName = args?.newName?.toString()?.trim()
        def sourceCfg
        try { sourceCfg = _rmFetchConfigJson(sourceAppId) }
        catch (Exception sourceErr) {
            mcpLog("warn", "rm-native", "hub_clone_native_app: source ${sourceAppId} config fetch failed: ${sourceErr.message}")
            sourceCfg = null
        }
        if (!sourceCfg?.app) throw new IllegalArgumentException("Source app ${sourceAppId} not found or unreadable")
        String sourceLabel = sourceCfg.app.label?.toString()
        Integer parentAppId = null
        try {
            parentAppId = sourceCfg.app.parentAppId != null ? sourceCfg.app.parentAppId.toString() as Integer : null
        } catch (NumberFormatException ignored) {
            mcpLog("warn", "rm-native", "hub_clone_native_app: source ${sourceAppId} parentAppId not numeric: ${sourceCfg.app.parentAppId}")
        }
        if (parentAppId == null) {
            throw new IllegalArgumentException("Source app ${sourceAppId} has no numeric parentAppId. MCP cannot safely discover its clone; pass a child of the target parent app.")
        }
        _requireUnprotectedAppMutation(parentAppId, "clone or import a child app under")
        Map snapshot = _appClonerSnapshotChildren(parentAppId)
        if (snapshot.isError == true) return snapshot
        def preIds = snapshot.ids
        def initRes = _appClonerInit(sourceAppId)
        cp = [phase: "clone_clicks", clonerAppId: initRes.clonerAppId,
              referrer: initRes.referrer, configUrl: initRes.configUrl,
              sourceAppId: sourceAppId, sourceLabel: sourceLabel,
              parentAppId: parentAppId, preIds: preIds, newName: newName,
              stageDisabled: args?.stageDisabled == true]
        return _mrtrControl("clone_native_app", cp)
    }

    Integer clonerAppId = cp.clonerAppId as Integer
    try {
        if (cp.phase != "stage_disable") _requireUnprotectedAppMutation(cp.parentAppId, "clone or import a child app under")
        if (cp.phase == "clone_clicks") {
            _appClonerClickClone(clonerAppId, cp.referrer?.toString(), cp.configUrl?.toString())
            cp.phase = "clone_commit"
            return _mrtrControl("clone_native_app", cp)
        }
        if (cp.phase == "clone_commit") {
            _appClonerCommitImportRule(clonerAppId, cp.sourceAppId as Integer,
                cp.newName?.toString(), cp.referrer?.toString(), cp.configUrl?.toString())
            Integer newAppId = _appClonerDiscoverNewChild(cp.parentAppId as Integer,
                (cp.preIds ?: []) as Set, cp.sourceLabel?.toString(), cp.newName?.toString())
            String note = newAppId
                ? "Cloned source ${cp.sourceAppId} -> new app ${newAppId}${cp.newName ? " (renamed to '${cp.newName}')" : ""}. Use hub_set_native_app (or hub_set_rule for RM rules) to further customize."
                : "Clone fired but no new child appeared under parent ${cp.parentAppId}. Re-check via hub_list_apps (scope='instances') shortly."
            def baseResult = [success: newAppId != null, sourceAppId: cp.sourceAppId,
                              clonerAppId: clonerAppId, newAppId: newAppId, note: note]
            if (newAppId == null) {
                baseResult.isError = true
                baseResult.error = note
                if (cp.stageDisabled == true) _appClonerStagingDiscoveryMiss(baseResult, "clone")
                _appClonerCleanup(clonerAppId)
                return baseResult
            }
            if (cp.stageDisabled != true) {
                _appClonerCleanup(clonerAppId)
                return baseResult
            }
            def stagePlan = _appClonerStagePlan(newAppId)
            cp.phase = "stage_disable"
            cp.newAppId = newAppId
            cp.stageTargets = stagePlan.targets
            cp.stageFailures = stagePlan.failures
            cp.stagedDisabled = []
            cp.baseResult = baseResult
            return _mrtrControl("clone_native_app", cp)
        }
        if (cp.phase == "stage_disable") return _mrtrAppClonerStageSlice(cp, "clone")
        throw new IllegalStateException("Unknown clone continuation phase '${cp.phase}'")
    } catch (Exception e) {
        try { _appClonerCleanup(clonerAppId) } catch (Exception ignored) { }
        throw e
    }
}

private Map _mrtrImportNativeAppSlice(Map rec, Map outerArgs) {
    Map args = _mrtrLeafArguments(rec.outerTool?.toString(), rec.leafTool?.toString(), outerArgs) as Map
    // Deep copy: the staging phases append to the checkpoint's own lists, and a
    // shallow copy would land those appends in the shared requestState record
    // before the slice decides what to store.
    Map cp = (rec.checkpoint instanceof Map) ? _mrtrCopyMap(rec.checkpoint as Map) : null
    if (cp == null) {
        requireDestructiveConfirm(args?.confirm as Boolean)
        if (args?.parentHintAppId == null) {
            throw new IllegalArgumentException("parentHintAppId is required (any existing rule's id under the target parent — used to seed the cloner)")
        }
        Integer parentHintAppId = normalizeRuleId(args.parentHintAppId)
        String newName = args?.newName?.toString()?.trim()
        String jsonContent = args?.jsonContent?.toString()
        if (!jsonContent && args?.fromFile) {
            try { jsonContent = new String(downloadHubFile(args.fromFile.toString()), "UTF-8") }
            catch (Exception e) { throw new IllegalArgumentException("Cannot read fromFile '${args.fromFile}': ${e.message}") }
        }
        if (!jsonContent) throw new IllegalArgumentException("jsonContent or fromFile is required")
        def parsed
        try { parsed = new groovy.json.JsonSlurper().parseText(jsonContent) }
        catch (Exception e) { throw new IllegalArgumentException("jsonContent is not valid JSON: ${e.message}") }
        def replacements = (parsed instanceof Map) ? parsed.appReplacements : null
        if (!(replacements instanceof Map) || replacements.isEmpty()) {
            throw new IllegalArgumentException("jsonContent does not contain an appReplacements map — not an appCloner export")
        }
        Integer originalSourceId
        try { originalSourceId = ((replacements.keySet() as List)[0]).toString() as Integer }
        catch (Exception e) { throw new IllegalArgumentException("Could not extract original source id from appReplacements: ${e.message}") }
        String originalLabel = replacements.get(originalSourceId.toString())?.appLabel?.toString()
        def hintCfg
        try { hintCfg = _rmFetchConfigJson(parentHintAppId) }
        catch (Exception hintErr) {
            mcpLog("warn", "rm-native", "hub_import_native_app: parentHintAppId ${parentHintAppId} config fetch failed: ${hintErr.message}")
            hintCfg = null
        }
        if (!hintCfg?.app) throw new IllegalArgumentException("parentHintAppId ${parentHintAppId} not found or unreadable")
        Integer parentAppId = null
        try { parentAppId = hintCfg.app.parentAppId?.toString() as Integer }
        catch (NumberFormatException ignored) {
            mcpLog("warn", "rm-native", "hub_import_native_app: parentHintAppId ${parentHintAppId} parentAppId not numeric: ${hintCfg.app.parentAppId}")
        }
        if (parentAppId == null) {
            throw new IllegalArgumentException("parentHintAppId ${parentHintAppId} has no numeric parentAppId — pass a child of the target parent app")
        }
        _requireUnprotectedAppMutation(parentAppId, "clone or import a child app under")
        Map snapshot = _appClonerSnapshotChildren(parentAppId)
        if (snapshot.isError == true) return snapshot
        def preIds = snapshot.ids
        def initRes = _appClonerInit(parentHintAppId)
        Integer clonerAppId = initRes.clonerAppId as Integer
        try {
            String configUrl = initRes.configUrl?.toString()
            _appClonerSubmitForm(clonerAppId, "main", "source", configUrl, configUrl,
                [("settings[ruleUpload]".toString()): jsonContent])
            pauseExecution(2000)
            cp = [phase: "import_commit", clonerAppId: clonerAppId,
                  referrer: configUrl, configUrl: configUrl,
                  parentAppId: parentAppId, preIds: preIds,
                  originalSourceId: originalSourceId, originalLabel: originalLabel,
                  contentLength: jsonContent.length(), newName: newName,
                  stageDisabled: args?.stageDisabled == true]
            return _mrtrControl("import_native_app", cp)
        } catch (Exception e) {
            try { _appClonerCleanup(clonerAppId) } catch (Exception ignored) { }
            throw e
        }
    }

    Integer clonerAppId = cp.clonerAppId as Integer
    try {
        if (cp.phase != "stage_disable") _requireUnprotectedAppMutation(cp.parentAppId, "clone or import a child app under")
        if (cp.phase == "import_commit") {
            _appClonerCommitImportRule(clonerAppId, cp.originalSourceId as Integer,
                cp.newName?.toString(), cp.referrer?.toString(), cp.configUrl?.toString())
            Integer newAppId = _appClonerDiscoverNewChild(cp.parentAppId as Integer,
                (cp.preIds ?: []) as Set, cp.originalLabel?.toString(), cp.newName?.toString())
            String note = newAppId
                ? "Imported '${cp.originalLabel ?: 'app'}' as new app ${newAppId}${cp.newName ? " (renamed to '${cp.newName}')" : ""}. Use hub_set_native_app (or hub_set_rule for RM rules) to further customize."
                : "Import fired but no new child appeared under parent ${cp.parentAppId}. Re-check via hub_list_apps (scope='instances') shortly."
            def baseResult = [success: newAppId != null, clonerAppId: clonerAppId,
                              newAppId: newAppId, originalSourceId: cp.originalSourceId,
                              originalLabel: cp.originalLabel, contentLength: cp.contentLength,
                              note: note]
            if (newAppId == null) {
                baseResult.isError = true
                baseResult.error = note
                if (cp.stageDisabled == true) _appClonerStagingDiscoveryMiss(baseResult, "import")
                _appClonerCleanup(clonerAppId)
                return baseResult
            }
            if (cp.stageDisabled != true) {
                _appClonerCleanup(clonerAppId)
                return baseResult
            }
            def stagePlan = _appClonerStagePlan(newAppId)
            cp.phase = "stage_disable"
            cp.newAppId = newAppId
            cp.stageTargets = stagePlan.targets
            cp.stageFailures = stagePlan.failures
            cp.stagedDisabled = []
            cp.baseResult = baseResult
            return _mrtrControl("import_native_app", cp)
        }
        if (cp.phase == "stage_disable") return _mrtrAppClonerStageSlice(cp, "import")
        throw new IllegalStateException("Unknown import continuation phase '${cp.phase}'")
    } catch (Exception e) {
        try { _appClonerCleanup(clonerAppId) } catch (Exception ignored) { }
        throw e
    }
}

private Map _mrtrAppClonerStageSlice(Map cp, String operationLabel) {
    List targets = (cp.stageTargets instanceof List) ? cp.stageTargets as List : []
    List staged = (cp.stagedDisabled instanceof List) ? cp.stagedDisabled as List : []
    List failures = (cp.stageFailures instanceof List) ? cp.stageFailures as List : []
    Map outcome = _appClonerDisableTargets(targets, now(), staged, failures)
    List remaining = outcome.remaining
    if (remaining) {
        cp.stageTargets = remaining
        cp.stagedDisabled = staged
        cp.stageFailures = failures
        return _mrtrControl("${operationLabel}_native_app".toString(), cp)
    }
    def result = (cp.baseResult instanceof Map) ? ([:] + cp.baseResult) : [:]
    _appClonerFinishStaging(result, cp.newAppId as Integer, outcome)
    _appClonerCleanup(cp.clonerAppId as Integer)
    return result
}
