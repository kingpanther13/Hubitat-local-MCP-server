/**
 * E2E Dead-Man Watchdog v2 (issue #209 e2e safety net -- package-aware + deploy controller)
 *
 * v2 of the standalone e2e recovery app. The original "E2E Dead-Man Watchdog" is left
 * installed but ORPHANED: e2e only arms THIS app (its own Apps Code class + its own flag
 * file e2e-deadman-v2.json), so v1 sees no flag of its own and sits idle/harmless.
 *
 * What v2 does: it restores the WHOLE main package -- every #include'd library FIRST
 * (POST /library/saveOrUpdateJson), then the app(s) (POST /app/ajax/update) -- from LOCAL
 * File Manager cache files that CI populated from main at the healthy START of the run
 * (importUrl + hub_get_source). It restores on BOTH a clean disarm (intent=disarm) and a
 * missed-deadline fire, and writes restoreResult/restoreFor back into the flag so CI can
 * confirm the reinstall landed.
 *
 * SECOND DRIVER (this revision): v2 is now also a SMALL second MCP server -- a deploy
 * controller. It exposes a token-gated cloud /mcp endpoint (Hubitat OAuth) carrying ONLY
 * the admin/deploy tools CI needs (update_app, get_source, create/update/delete library,
 * install_bundle, read/list/jobs/file/backup/variables). The tool NAMES are identical to
 * the main MCP server so CI scripts work by URL swap. Every admin tool is implemented over
 * the SAME hub-loopback plumbing (hubGet/hubPostForm/hubPostJson to 127.0.0.1:8080) that
 * the dead-man restore uses -- so the MCP endpoint and the dead-man timer are two drivers
 * of one brick-proof restore floor. A PR that bricks the MAIN server cannot block recovery:
 * this app compiles and deploys independently and keeps its own OAuth transport.
 *
 * The admin-tool logic is copied from hubitat-mcp-server.groovy (cited per method) and
 * adapted from its hubInternalGet/PostForm/PostJson to this app's hubGet/hubPostForm/
 * hubPostJson loopback helpers.
 *
 * !! SECURITY: this is a DELIBERATELY UNGUARDED full deploy controller for the e2e TEST HUB
 * ONLY. Unlike the main server it DROPS the Developer-Mode self-update guard, the Read/Write
 * masters, the per-tool overrides, and the 24h-backup requirement -- the sole boundary is the
 * OAuth access_token (whoever holds WATCHDOG_MCP_URL can deploy arbitrary code to ANY app class
 * on this hub) plus a confirm=true flag on writes. NEVER install this on a production/user hub,
 * and never expose its token. It is intended for the donated level99 e2e hub and nothing else.
 */
definition(
    name: "E2E Dead-Man Watchdog v2",
    namespace: "mcp",
    author: "kingpanther13",
    description: "Package-aware autonomous on-hub watchdog + small deploy-controller MCP server: restores main (libraries first, app last) from a local cache on a missed/clean disarm, and exposes a token-gated /mcp admin endpoint over hub loopback.",
    category: "Utility",
    iconUrl: "https://raw.githubusercontent.com/hubitat/HubitatPublic/master/app-dev/icon.png",
    iconX2Url: "https://raw.githubusercontent.com/hubitat/HubitatPublic/master/app-dev/icon.png",
    // Copied from hubitat-mcp-server.groovy line 28 (definition oauth).
    oauth: [displayName: "E2E Dead-Man Watchdog v2", displayLink: ""],
    singleInstance: true
)

preferences {
    page(name: "mainPage", title: "E2E Dead-Man Watchdog v2", install: true, uninstall: true) {
        section("Watchdog") {
            input "flagFileName", "text", title: "Flag file name (File Manager)", defaultValue: "e2e-deadman-v2.json", required: false
            input "debugLogging", "bool", title: "Debug logging", defaultValue: true, required: false
            paragraph statusText()
        }
        section("MCP Deploy Endpoint") {
            // Surface the cloud /mcp endpoint URL + access token, mirroring the main server's
            // mainPage (hubitat-mcp-server.groovy lines 54-56). createAccessToken() runs in
            // initialize() before the endpoint can serve; if it hasn't run yet, prompt to save.
            if (state.accessToken) {
                paragraph "<b>Cloud /mcp endpoint (token-in-query):</b><br><code>${getFullApiServerUrl()}/mcp?access_token=${state.accessToken}</code>"
            } else {
                paragraph "Save the app once (Done) to create the OAuth access token and surface the /mcp endpoint URL."
            }
        }
        section("Hub Security (only if enabled on this hub)") {
            input "hubSecurityEnabled", "bool", title: "Hub Security enabled?", defaultValue: false, required: false
            input "hubSecurityUser", "text", title: "Hub Security username", required: false
            input "hubSecurityPassword", "password", title: "Hub Security password", required: false
        }
    }
}

// ==================== MCP CLOUD TRANSPORT (deploy controller) ====================
//
// Copied from hubitat-mcp-server.groovy lines 659-673 (top-level mappings{} for /mcp).
// Hubitat OAuth validates ?access_token= BEFORE routing here, so there is no per-call token
// check in the handlers below (matches the main server's transport contract).
mappings {
    path("/mcp") {
        action: [
            GET: "handleMcpGet",
            POST: "handleMcpRequest"
        ]
    }
}

def installed() { initialize() }
def updated()   { unschedule(); initialize() }

def initialize() {
    // Copied from hubitat-mcp-server.groovy lines 404-408: create the OAuth access token
    // (idempotent) BEFORE scheduling, so the /mcp endpoint can serve immediately.
    if (!state.accessToken) {
        // createAccessToken() THROWS until OAuth is enabled in the Apps Code editor UI (level99's
        // one-time step). Never let that kill initialize() -- the dead-man TIMER below must still
        // register (the brick-proof floor needs no token or endpoint). A re-save after enabling
        // OAuth mints the token.
        try {
            createAccessToken()
            log.info "E2E Dead-Man Watchdog v2: created MCP access token"
        } catch (Exception e) {
            log.warn "E2E Dead-Man Watchdog v2: createAccessToken() failed -- enable OAuth in the app's code editor (${e.message}). The /mcp endpoint is unavailable until then; the dead-man timer is still scheduled."
        }
    }
    runEvery1Minute("checkDeadman")
    logInfo "E2E Dead-Man Watchdog v2 initialized; checking '${flagFileName ?: 'e2e-deadman-v2.json'}' every minute. /mcp endpoint armed."
}

// ---- the dead-man check (runs every minute) ----
def checkDeadman() {
    def flag = readFlag()
    if (flag == null) { logDebug "no flag file -- idle"; return }

    // Clean-finish restore: CI wrote intent=disarm for a run that finished. Restore main once
    // per runId (idempotency stamp restoreFor) even though armed is already false.
    if (flag.intent == "disarm" && (flag.restoreFor?.toString() != flag.runId?.toString())) {
        logInfo "disarm received for run ${flag.runId} -- restoring main package from cache."
        actAndRecord(flag, "disarm")
        return
    }

    if (flag.armed != true) { logDebug "flag present but disarmed/idle"; return }

    def deadline = (flag.deadline != null) ? (flag.deadline as long) : 0L
    if (now() < deadline) {
        logDebug "armed; ${((deadline - now()) / 1000) as long}s until deadline"
        return
    }

    // Armed AND past deadline -> the session never disarmed -> FIRE (crash/brick recovery).
    log.warn "E2E Dead-Man Watchdog v2 FIRING: armed flag expired ${((now() - deadline) / 1000) as long}s ago. Restoring main package for run ${flag.runId}."
    actAndRecord(flag, "fire")
}

// Run the package restore, then record result + idempotency stamp into the flag (the signal CI
// polls). A clean disarm records pass/fail once; a fire retries up to a cap (recovery matters most).
private void actAndRecord(Map flag, String trigger) {
    Map res = restorePackage(flag)
    if (res.ok) {
        flag.armed = false
        flag.restoreFor = flag.runId
        flag.restoreResult = "restored"
        flag.restoreTrigger = trigger
        flag.restoredAt = now()
        flag.restoreDetail = res.detail
        flag.fireAttempts = 0
    } else {
        int attempts = ((flag.fireAttempts ?: 0) as int) + 1
        flag.fireAttempts = attempts
        flag.restoreDetail = res.detail
        // Retry BOTH triggers up to the cap -- a transient miss must not latch the restore "failed".
        // (fire: armed+deadline re-fires next tick; disarm: intent=disarm with restoreFor still
        // unstamped re-runs next tick.)
        if (attempts < 5) {
            flag.lastAttemptAt = now()
            log.warn "E2E Dead-Man Watchdog v2 restore attempt ${attempts} (trigger=${trigger}) FAILED; will retry next check. ${res.detail}"
            if (!writeFlag(flag)) {
                log.error "E2E Dead-Man Watchdog v2: could not persist retry state (attempt ${attempts}); will retry next check regardless."
            }
            return
        }
        flag.armed = false
        flag.restoreFor = flag.runId
        flag.restoreResult = "failed"
        flag.restoreTrigger = trigger
        flag.restoredAt = now()
        log.error "E2E Dead-Man Watchdog v2: restore FAILED (trigger=${trigger}, attempts=${attempts}); latching. ${res.detail}"
    }
    if (!writeFlag(flag)) {
        log.error "E2E Dead-Man Watchdog v2: FAILED to persist flag after ${trigger} (restoreResult=${flag.restoreResult}); the flag will be re-evaluated next check."
    }
    log.warn "E2E Dead-Man Watchdog v2 ${trigger} complete: ${flag.restoreResult}."
}

// ---- restore the whole package from the cache manifest: LIBRARIES first, APP(s) last ----
private Map restorePackage(Map flag) {
    def m = flag.manifest
    if (!(m instanceof Map)) return [ok: false, detail: "no manifest in flag"]
    if (settings?.hubSecurityEnabled == true && secCookie() == null) {
        return [ok: false, detail: "hub security enabled but auth failed (no cookie)"]
    }
    def detail = []
    // Libraries first so the app's #include directives resolve when it recompiles.
    def libs = (m.libraries instanceof List) ? m.libraries : []
    for (lib in libs) {
        boolean ok = restoreLibrary(lib?.id?.toString(), lib?.file?.toString())
        detail << "lib ${lib?.id}=${ok}"
        if (!ok) return [ok: false, detail: "library ${lib?.id} restore failed [${detail.join('; ')}]"]
    }
    // Then the app(s). manifest.app is a single {classId,file}; manifest.apps an optional list.
    def apps = (m.apps instanceof List) ? m.apps : (m.app ? [m.app] : [])
    for (a in apps) {
        boolean ok = restoreApp(a?.classId?.toString(), a?.file?.toString())
        detail << "app ${a?.classId}=${ok}"
        if (!ok) return [ok: false, detail: "app ${a?.classId} restore failed [${detail.join('; ')}]"]
    }
    if (apps.isEmpty()) return [ok: false, detail: "manifest has no app to restore [${detail.join('; ')}]"]
    return [ok: true, detail: detail.join('; ')]
}

// ---- restore one app class: read cached source, POST it to the LOCAL /app/ajax/update ----
boolean restoreApp(String classId, String restoreFileName) {
    if (!classId || !restoreFileName) {
        log.error "restoreApp: missing classId (${classId}) or restoreFileName (${restoreFileName})"
        return false
    }
    String source = readHubFileText(restoreFileName)
    if (source == null || source.length() < 50) {
        log.error "restoreApp: cache '${restoreFileName}' missing/empty/too-small -- refusing to push it."
        return false
    }
    def oldVersion = appCodeVersion(classId)
    if (oldVersion == null) {
        log.error "restoreApp: could not read current version of app class ${classId}"
        return false
    }
    def resp = hubPostForm("/app/ajax/update", [id: classId, version: oldVersion, source: source])
    if (resp?.status != 200) {
        log.error "restoreApp: /app/ajax/update returned status ${resp?.status}; body=${resp?.data?.toString()?.take(300)}"
        return false
    }
    boolean reported = false
    try { reported = (new groovy.json.JsonSlurper().parseText(resp.data ?: "{}")).status == "success" }
    catch (Exception e) { log.error "restoreApp: could not parse update response (${e.message}); body=${resp?.data?.toString()?.take(300)}" }
    if (!reported) {
        log.error "restoreApp: hub did not report success; body=${resp?.data?.toString()?.take(300)}"
        return false
    }
    // A real code-class save advances the version -- but Hubitat may NOT bump it on a byte-identical
    // save, which is a legitimate no-op (the live app already equals what we pushed; common when a PR
    // triggers e2e WITHOUT changing hubitat-mcp-server.groovy). So: version-advanced -> restored; else
    // re-read the live source and treat a length match as a no-op success; only a genuine mismatch
    // (the push silently dropped) fails. Mirrors mcp_watchdog_deploy.sh's no-op handling.
    def newVersion = appCodeVersion(classId)
    String oldStr = oldVersion?.toString()?.trim()
    String newStr = newVersion?.toString()?.trim()
    if (newStr?.isLong() && oldStr?.isLong() && newStr.toLong() > oldStr.toLong()) {
        logInfo "restoreApp: pushed ${source.length()} chars to class ${classId}; version ${oldVersion}->${newVersion}, confirmed."
        return true
    }
    String live = readAppSource(classId)
    if (live != null && live.length() == source.length()) {
        logInfo "restoreApp: class ${classId} version unchanged (${oldVersion}) but live source length matches the pushed source -- legitimate no-op, treating as restored."
        return true
    }
    log.error "restoreApp: reported success but version did not advance (old=${oldVersion}, new=${newVersion}) and the live source does not match the pushed source -- the restore did not land for class ${classId}."
    return false
}

// ---- restore one library: read cached source, POST it to the LOCAL /library/saveOrUpdateJson ----
boolean restoreLibrary(String libId, String fileName) {
    if (!libId || !fileName) {
        log.error "restoreLibrary: missing libId (${libId}) or fileName (${fileName})"
        return false
    }
    String source = readHubFileText(fileName)
    if (source == null || source.length() < 20) {
        log.error "restoreLibrary: cache '${fileName}' missing/empty/too-small -- refusing to push it."
        return false
    }
    Integer curVer = libraryVersion(libId)
    if (curVer == null) {
        log.error "restoreLibrary: could not read current version of library ${libId}"
        return false
    }
    String body = groovy.json.JsonOutput.toJson([id: libId.toInteger(), source: source, version: curVer])
    Map resp = hubPostJson("/library/saveOrUpdateJson", body)
    if (resp?.status != 200) {
        log.error "restoreLibrary: /library/saveOrUpdateJson returned status ${resp?.status}; body=${resp?.data?.toString()?.take(300)}"
        return false
    }
    boolean ok = false
    try {
        def parsed = new groovy.json.JsonSlurper().parseText(resp.data ?: "{}")
        // Confirm the restore actually landed (don't trust success+id alone): the version advanced, OR
        // -- on a byte-identical no-op the hub may not bump it -- the live library source length matches
        // what we pushed.
        if (parsed?.success == false || parsed?.id == null) {
            log.error "restoreLibrary ${libId}: hub reported failure or no id (${resp?.data?.toString()?.take(200)})"
        } else {
            def newVer = parsed?.version
            if (newVer != null && curVer != null && (newVer as Integer) > curVer) {
                ok = true
            } else {
                String live = readLibrarySource(libId)
                if (live != null && live.length() == source.length()) {
                    ok = true
                    logInfo "restoreLibrary ${libId}: version unchanged but live source length matches the pushed source -- legitimate no-op."
                } else {
                    log.error "restoreLibrary ${libId}: success reported but version did not advance and live source != pushed -- did not land."
                }
            }
        }
    } catch (Exception e) {
        log.error "restoreLibrary: could not parse update response (${e.message}); body=${resp?.data?.toString()?.take(300)}"
    }
    logInfo "restoreLibrary ${libId}: pushed ${source.length()} chars (version was ${curVer}); ok=${ok}"
    return ok
}

// Current version of an app class (optimistic-lock field on /app/ajax/update).
def appCodeVersion(String classId) {
    String body = hubGet("/app/ajax/code", [id: classId])
    if (body == null) return null
    try { return (new groovy.json.JsonSlurper().parseText(body)).version }
    catch (Exception e) { log.error "appCodeVersion: parse failed: ${e.message}"; return null }
}

// Current version of a library (optimistic-lock field on /library/saveOrUpdateJson).
Integer libraryVersion(String libId) {
    String body = hubGet("/library/list/single/data/${libId}", [:])
    if (body == null) return null
    try {
        def parsed = new groovy.json.JsonSlurper().parseText(body)
        if (parsed instanceof List && !parsed.isEmpty()) return (parsed[0]?.version) as Integer
    } catch (Exception e) { log.error "libraryVersion: parse failed: ${e.message}" }
    return null
}

// Full current source of an app class / library -- for the restore no-op check (a reported success
// that doesn't bump the version is legitimate when the live source already equals what we pushed).
String readAppSource(String classId) {
    String body = hubGet("/app/ajax/code", [id: classId])
    if (body == null) return null
    try { return (new groovy.json.JsonSlurper().parseText(body)).source }
    catch (Exception e) { log.error "readAppSource: parse failed: ${e.message}"; return null }
}
String readLibrarySource(String libId) {
    String body = hubGet("/library/list/single/data/${libId}", [:])
    if (body == null) return null
    try {
        def parsed = new groovy.json.JsonSlurper().parseText(body)
        if (parsed instanceof List && !parsed.isEmpty()) return parsed[0]?.source
    } catch (Exception e) { log.error "readLibrarySource: parse failed: ${e.message}" }
    return null
}

// ==================== MCP JSON-RPC HANDLERS (deploy controller) ====================
//
// handleMcpGet: copied from hubitat-mcp-server.groovy line 693 -- this endpoint is
// request-response only (POST); GET/SSE is not supported.
def handleMcpGet() {
    return render(status: 405, contentType: "application/json",
                  data: groovy.json.JsonOutput.toJson(jsonRpcError(null, -32600,
                      "This MCP endpoint is request-response only (POST). SSE/GET streaming is not supported.")))
}

// handleMcpRequest: envelope handling copied/condensed from hubitat-mcp-server.groovy
// lines 705-784 + processJsonRpcMessage 786-825. Parses request.JSON, dispatches
// initialize / tools/list / tools/call / ping, and renders a JSON-RPC envelope.
// NO per-call token check: Hubitat OAuth validates ?access_token first (server 705-784).
def handleMcpRequest() {
    def requestBody
    try {
        requestBody = request.JSON
    } catch (Exception e) {
        def errResp = jsonRpcError(null, -32700, "Parse error: invalid JSON")
        return render(contentType: "application/json", data: groovy.json.JsonOutput.toJson(errResp))
    }
    if (requestBody == null) {
        def errResp = jsonRpcError(null, -32700, "Parse error: empty or invalid JSON body")
        return render(contentType: "application/json", data: groovy.json.JsonOutput.toJson(errResp))
    }
    logDebug "MCP Request: ${requestBody.toString().take(500)}"

    def response
    if (requestBody instanceof List) {
        // Batch: empty array is invalid (JSON-RPC 2.0); otherwise process each, dropping notifications.
        if (requestBody.isEmpty()) {
            response = jsonRpcError(null, -32600, "Invalid Request: empty batch array")
        } else if (requestBody.size() > 50) {
            return render(contentType: "application/json", data: groovy.json.JsonOutput.toJson(
                jsonRpcError(null, -32600, "Invalid Request: batch too large (${requestBody.size()} elements, max 50)")))
        } else {
            response = requestBody.collect { msg -> processJsonRpcMessage(msg) }.findAll { it != null }
        }
    } else {
        response = processJsonRpcMessage(requestBody)
    }

    // All-notifications batch / notification: no response objects -> 202 Accepted.
    if (response == null || (response instanceof List && response.isEmpty())) {
        return render(status: 202, contentType: "application/json", data: "")
    }

    def jsonResponse = groovy.json.JsonOutput.toJson(response)
    // Hub enforces a 128KB response limit; guard with byte-accurate sizing.
    def maxResponseSize = 124000
    def responseBytes = jsonResponse.getBytes("UTF-8").length
    if (responseBytes > maxResponseSize) {
        def echoId = (response instanceof Map) ? response.id : null
        def errResp = jsonRpcError(echoId, -32603,
            "Response too large (${responseBytes} bytes exceeds hub's 128KB limit). Request less data or a more specific query.")
        jsonResponse = groovy.json.JsonOutput.toJson(errResp)
    }
    logDebug "MCP Response: ${jsonResponse.take(500)}"
    return render(contentType: "application/json", data: jsonResponse)
}

// processJsonRpcMessage: copied from hubitat-mcp-server.groovy lines 786-825.
def processJsonRpcMessage(msg) {
    if (!msg) return jsonRpcError(null, -32600, "Invalid Request: empty message")
    if (msg.jsonrpc != "2.0") return jsonRpcError(msg?.id, -32600, "Invalid Request: must use JSON-RPC 2.0")
    if (!msg.method) {
        if (msg.id == null) return null
        return jsonRpcError(msg.id, -32600, "Invalid Request: missing method field")
    }
    if (msg.id == null) { logDebug "MCP Notification: ${msg.method}"; return null }

    try {
        switch (msg.method) {
            case "initialize":
                return handleInitialize(msg)
            case "tools/list":
                return jsonRpcResult(msg.id, [tools: getAdminToolDefinitions()])
            case "tools/call":
                return handleToolsCall(msg)
            case "ping":
                return jsonRpcResult(msg.id, [:])
            default:
                return jsonRpcError(msg.id, -32601, "Method not found: ${msg.method}")
        }
    } catch (Exception e) {
        // Hubitat's LogWrapper.error() does NOT accept (String, Throwable); use string-only.
        log.error "MCP Error: ${e.message}"
        return jsonRpcError(msg.id, -32603, "Internal error: ${e.message}")
    }
}

def handleInitialize(msg) {
    def requested = msg.params?.protocolVersion
    def supported = ["2025-06-18", "2025-03-26", "2024-11-05"]
    def negotiated = supported.contains(requested) ? requested : "2024-11-05"
    return jsonRpcResult(msg.id, [
        protocolVersion: negotiated,
        capabilities: [tools: [:]],
        serverInfo: [name: "e2e-deadman-watchdog-v2", version: "2"],
        instructions: "Deploy-controller MCP server: admin/deploy tools over hub loopback. Tool names match the main hubitat-mcp server so CI scripts work by URL swap."
    ])
}

// handleToolsCall: dispatch params.name -> tool impl, wrap result in the MCP
// {jsonrpc,id,result:{content:[{type:text,text:<json>}]}} envelope.
// Copied/condensed from hubitat-mcp-server.groovy lines 896-988 (handleToolsCall).
def handleToolsCall(msg) {
    def toolName = msg.params?.name
    def args = msg.params?.arguments ?: [:]
    if (!toolName) return jsonRpcError(msg.id, -32602, "Invalid params: tool name required")

    try {
        def result = executeAdminTool(toolName, args)
        if (result == null) {
            return jsonRpcResult(msg.id, [
                content: [[type: "text", text: groovy.json.JsonOutput.toJson([
                    isError: true, error: "Tool ${toolName} returned no result", tool: toolName])]],
                isError: true
            ])
        }
        def jsonText = groovy.json.JsonOutput.toJson(result)
        return jsonRpcResult(msg.id, [content: [[type: "text", text: jsonText]]])
    } catch (IllegalArgumentException e) {
        // Validation errors map to -32602 (server 974-979).
        return jsonRpcError(msg.id, -32602, "Invalid params: ${e.message}")
    } catch (Exception e) {
        // Tool-execution errors return a successful result with isError (server 980-989).
        log.error "Tool execution error in ${toolName}: ${e.message}"
        return jsonRpcResult(msg.id, [content: [[type: "text", text: "Tool error: ${e.message}"]], isError: true])
    }
}

// jsonRpcResult / jsonRpcError: copied verbatim from hubitat-mcp-server.groovy lines 10012-10020.
def jsonRpcResult(id, result) {
    return [jsonrpc: "2.0", id: id, result: result]
}
def jsonRpcError(id, code, message, data = null) {
    def error = [jsonrpc: "2.0", id: id, error: [code: code, message: message]]
    if (data) error.error.data = data
    return error
}

// ==================== ADMIN TOOL DISPATCH ====================
//
// Tool names IDENTICAL to hubitat-mcp-server.groovy so CI scripts work by URL swap.
// Every impl runs over the loopback helpers (hubGet/hubPostForm/hubPostJson).
def executeAdminTool(String toolName, Map args) {
    if (settings?.hubSecurityEnabled == true && secCookie() == null) {
        return [success: false, error: "Hub Security is enabled but loopback auth failed (no cookie). Set the Hub Security username/password in the watchdog app settings."]
    }
    switch (toolName) {
        case "hub_update_app":      return adminUpdateApp(args)
        case "hub_get_source":      return adminGetSource(args)
        case "hub_create_library":  return adminCreateLibrary(args)
        case "hub_update_library":  return adminUpdateLibrary(args)
        case "hub_delete_item":     return adminDeleteItem(args)
        case "hub_install_bundle":  return adminInstallBundle(args)
        case "hub_get_info":        return adminGetInfo(args)
        case "hub_list_apps":       return adminListApps(args)
        case "hub_list_libraries":  return adminListLibraries(args)
        case "hub_get_jobs":        return adminGetJobs(args)
        case "hub_read_file":       return adminReadFile(args)
        case "hub_write_file":      return adminWriteFile(args)
        case "hub_create_backup":   return adminCreateBackup(args)
        case "hub_manage_variables": return adminManageVariables(args)
        default:
            throw new IllegalArgumentException("Unknown tool: ${toolName}. This deploy-controller exposes only the admin/deploy subset.")
    }
}

// confirm gate for the WRITE tools (server uses requireDestructiveConfirm; this app's surface is
// already token-gated by OAuth, so the floor is the explicit confirm flag the CI scripts pass).
private void requireConfirm(args) {
    if (args?.confirm != true) {
        throw new IllegalArgumentException("SAFETY CHECK FAILED: set confirm=true to use this write tool.")
    }
}

// ==================== ADMIN TOOL IMPLEMENTATIONS ====================

// hub_update_app: copied from toolUpdateItemCodeInner (hubitat-mcp-server.groovy 12934-13230),
// KEEPING the issue #237 verbatim compile-error capture: read /app/ajax/update's errorMessage
// synchronously AND, for the self-update case, stash a lastSelfDeploy-style record in atomicState.
// Adapted from hubInternalGet/PostForm to hubGet/hubPostForm.
def adminUpdateApp(args) {
    requireConfirm(args)
    def itemId = args.appId
    if (!itemId) throw new IllegalArgumentException("appId is required")

    // Source resolution: exactly one of source / sourceFile / importUrl / resave (server 12942-12950).
    def modesSet = [args.resave, args.sourceFile, args.source, args.importUrl].count { it }
    if (modesSet == 0) throw new IllegalArgumentException("One of 'source', 'sourceFile', 'importUrl', or 'resave' is required")
    if (modesSet > 1) throw new IllegalArgumentException("Provide exactly one of 'source', 'sourceFile', 'importUrl', or 'resave'")

    def sourceCode = null
    def sourceMode = null
    def freshVersion = null

    if (args.resave) {
        sourceMode = "resave"
        def responseText = hubGet("/app/ajax/code", [id: itemId])
        if (!responseText) throw new IllegalArgumentException("Could not fetch current source for app ID ${itemId}")
        def parsed = new groovy.json.JsonSlurper().parseText(responseText)
        if (parsed.status == "error" || !parsed.source) {
            throw new IllegalArgumentException("Cannot read app ID ${itemId}: ${parsed.errorMessage ?: 'no source returned'}")
        }
        sourceCode = parsed.source
        freshVersion = parsed.version
    } else if (args.sourceFile) {
        sourceMode = "sourceFile"
        def bytes = downloadHubFile(args.sourceFile)
        if (bytes == null) throw new IllegalArgumentException("Source file '${args.sourceFile}' not found in File Manager")
        sourceCode = new String(bytes, "UTF-8")
    } else if (args.importUrl) {
        sourceMode = "importUrl"
        sourceCode = fetchExternal(args.importUrl)
    } else {
        sourceMode = "source"
        sourceCode = args.source
    }

    // Resolve current version for the optimistic lock (server 13024-13040, simplified).
    def currentVersion = freshVersion
    if (currentVersion == null) {
        try {
            def vt = hubGet("/app/ajax/code", [id: itemId])
            if (vt) currentVersion = (new groovy.json.JsonSlurper().parseText(vt)).version
        } catch (Exception vErr) {
            logDebug "adminUpdateApp: version fetch failed: ${vErr.message}"
        }
    }
    if (currentVersion == null) throw new IllegalArgumentException("Could not determine current version for app ID ${itemId}. The app may not exist.")

    // Self-update detection (server 13095-13110): the watchdog can be asked to deploy the MAIN
    // server's OWN app-code class. A self-deploy of the MAIN server can't return its outcome on
    // the call (success reloads it; a big-file failure 504s), so we stash the hub's verbatim
    // result -- keyed on a manifest-supplied selfClassId or the args.selfUpdate flag the CI passes.
    boolean isSelfUpdate = (args.selfUpdate == true) ||
        (args.selfClassId != null && itemId?.toString() == args.selfClassId?.toString())

    mcpAdminLog "Updating app ID ${itemId} (version ${currentVersion}, mode ${sourceMode}, sourceLength ${sourceCode.length()})"
    try {
        // Copied error-capture from toolUpdateItemCodeInner (server 13112-13230): read the
        // /app/ajax/update response errorMessage synchronously.
        def result = hubPostForm("/app/ajax/update", [id: itemId, version: currentVersion, source: sourceCode])
        def responseData = result?.data
        def success = false
        def errorMsg = null
        if (responseData) {
            try {
                def parsed = new groovy.json.JsonSlurper().parseText(responseData.toString())
                success = parsed.status == "success"
                errorMsg = parsed.errorMessage
            } catch (Exception parseErr) {
                errorMsg = "Unexpected response format -- update may have succeeded but could not be confirmed. Check the app in the Hubitat web UI."
            }
        } else if (isSelfUpdate) {
            // Self-update ONLY: /app/ajax/update reloads THIS app mid-request, so an empty/dropped
            // response is the expected success signal (issue #237). A normal deploy (the watchdog
            // updating the MAIN server, not itself) does NOT reload the watchdog, so an empty/null
            // response there means the loopback POST FAILED (hubPostForm returns data:null on a thrown
            // POST) -- it must never false-green the deploy.
            success = true
        } else {
            success = false
            errorMsg = "No/empty response from /app/ajax/update (HTTP ${result?.status}) -- the loopback POST failed (not a self-update, so an empty response is a real failure, not a reload)."
        }

        // issue #237 lastSelfDeploy record (server 13125-13135): persists across app reloads.
        if (isSelfUpdate) {
            try {
                atomicState.lastSelfDeploy = [
                    success: success,
                    error: success ? null : (errorMsg ?: "Update failed -- the hub returned an error"),
                    sourceMode: sourceMode,
                    importUrl: (args.importUrl ?: null),
                    sourceLength: sourceCode.length(),
                    at: now()
                ]
            } catch (Exception ignore) { /* bookkeeping must never break the deploy */ }
        }

        if (success) {
            mcpAdminLog "App ID ${itemId} updated successfully (mode ${sourceMode})"
            def successResult = [
                success: true,
                message: "App code updated successfully",
                appId: itemId,
                previousVersion: currentVersion,
                sourceMode: sourceMode,
                sourceLength: sourceCode.length()
            ]
            if (sourceMode == "importUrl") successResult.note = "Source was fetched from importUrl '${args.importUrl}' (hub-side fetch)."
            return successResult
        } else {
            return [
                success: false,
                error: errorMsg ?: "Update failed - the hub returned an error",
                appId: itemId,
                note: "Check the Groovy source code for syntax errors or compilation issues."
            ]
        }
    } catch (Exception e) {
        // Failure-case self-deploy capture (server 13205-13218): record before returning.
        if (isSelfUpdate) {
            try {
                atomicState.lastSelfDeploy = [
                    success: false,
                    error: "App update failed: ${e.message}",
                    sourceMode: sourceMode,
                    importUrl: (args.importUrl ?: null),
                    sourceLength: (sourceCode != null ? sourceCode.length() : 0),
                    at: now()
                ]
            } catch (Exception ignore) { }
        }
        log.error "adminUpdateApp: app update failed: ${e.message}"
        return [success: false, error: "App update failed: ${e.message}"]
    }
}

// hub_get_source: copied from toolGetSource / toolGetItemSource / toolGetLibrarySource
// (hubitat-mcp-server.groovy 12135-12203, 12666-12677, 13458-13540). The File Manager
// auto-save side effect (uploadHubFile of the full source) is how the backup caches main.
def adminGetSource(args) {
    def type = args.type
    if (!(type in ["app", "driver", "library"])) {
        throw new IllegalArgumentException("type is required and must be one of: app, driver, library")
    }
    def id = (args.id != null) ? args.id : (type == "app" ? args.appId : (type == "driver" ? args.driverId : args.libraryId))
    if (!id) throw new IllegalArgumentException("id is required")
    if (!id.toString().isInteger() || id.toString().toInteger() <= 0) throw new IllegalArgumentException("id must be a positive integer (got: '${id}')")

    def maxChunkSize = 64000
    def requestedOffset = args.offset ? args.offset as int : 0
    def requestedLength = args.length ? Math.min(args.length as int, maxChunkSize) : maxChunkSize

    def fullSource = ""
    def version = null
    if (type == "library") {
        def responseText = hubGet("/library/list/single/data/${id}", [:])
        if (!responseText) return [success: false, error: "Empty response from hub for library ${id}"]
        def parsed
        try { parsed = new groovy.json.JsonSlurper().parseText(responseText) }
        catch (Exception e) { return [success: false, error: "Failed to parse library response: ${e.message}"] }
        if (!(parsed instanceof List) || parsed.isEmpty()) return [success: false, error: "Library ${id} not found"]
        fullSource = parsed[0]?.source ?: ""
        version = parsed[0]?.version
    } else {
        def ajaxPath = (type == "app") ? "/app/ajax/code" : "/driver/ajax/code"
        def responseText = hubGet(ajaxPath, [id: id])
        if (!responseText) return [success: false, error: "Empty response from hub"]
        def parsed = new groovy.json.JsonSlurper().parseText(responseText)
        if (parsed.status == "error") return [success: false, error: parsed.errorMessage ?: "Failed to get ${type} source"]
        fullSource = parsed.source ?: ""
        version = parsed.version
    }

    def totalLength = fullSource.length()
    // File Manager auto-save side effect (server 12158-12169): caches the full source so a later
    // restore (or hub_update_app sourceFile) can read it without cloud size limits. This is how
    // the dead-man cache is populated from main at the healthy start of a run.
    def savedToFile = null
    if (totalLength > maxChunkSize) {
        def sourceFileName = "mcp-source-${type}-${id}.groovy"
        try {
            uploadHubFile(sourceFileName, fullSource.getBytes("UTF-8"))
            savedToFile = sourceFileName
            logInfo "adminGetSource: saved full ${type} ID ${id} source to File Manager: ${sourceFileName} (${totalLength} chars)"
        } catch (Exception saveErr) {
            logInfo "adminGetSource: could not save ${type} source to File Manager: ${saveErr.message}"
        }
    }

    def endIndex = Math.min(requestedOffset + requestedLength, totalLength)
    def chunk = (requestedOffset < totalLength) ? fullSource.substring(requestedOffset, endIndex) : ""
    def hasMore = endIndex < totalLength
    def result = [
        success: true,
        id: id,
        type: type,
        source: chunk,
        version: version,
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
    if (savedToFile) result.sourceFile = savedToFile
    return result
}

// hub_create_library: copied from toolInstallLibrary (hubitat-mcp-server.groovy 13651-13730).
// POST /library/saveOrUpdateJson {id:null, source, version:null}. Adapted to hubPostJson.
def adminCreateLibrary(args) {
    requireConfirm(args)
    def modesSet = [args.sourceFile, args.source, args.importUrl].count { it }
    if (modesSet > 1) throw new IllegalArgumentException("Provide exactly one of 'source', 'sourceFile', or 'importUrl'")
    def sourceCode = null
    def sourceMode = null
    if (args.sourceFile) {
        sourceMode = "sourceFile"
        def bytes = downloadHubFile(args.sourceFile)
        if (bytes == null) throw new IllegalArgumentException("Source file '${args.sourceFile}' not found in File Manager")
        sourceCode = new String(bytes, "UTF-8")
    } else if (args.importUrl) {
        sourceMode = "importUrl"
        sourceCode = fetchExternal(args.importUrl)
    } else if (args.source) {
        sourceMode = "source"
        sourceCode = args.source
    } else {
        throw new IllegalArgumentException("One of 'source', 'sourceFile', or 'importUrl' is required")
    }

    mcpAdminLog "Installing new library (mode ${sourceMode}, sourceLength ${sourceCode.length()})"
    try {
        def body = groovy.json.JsonOutput.toJson([id: null, source: sourceCode, version: null])
        def resp = hubPostJson("/library/saveOrUpdateJson", body)
        def parsed = _parseJsonBody(resp?.data)
        def newLibraryId = parsed?.id?.toString()
        if (parsed?.success == false) {
            return [success: false, error: "Library installation failed: ${parsed?.message ?: 'Hub returned failure'}",
                    note: "Check that the source includes a valid library() definition block and has no syntax errors."]
        }
        if (parsed == null || !newLibraryId) {
            def detail = parsed == null ? "empty/null response" : "response missing id field"
            return [success: false, error: "Library install unverified: hub returned ${detail}",
                    note: "Check the Libraries code UI to confirm. Do NOT retry without checking -- a duplicate may result."]
        }
        mcpAdminLog "Library installed successfully (ID ${newLibraryId}, version ${parsed?.version})"
        return [success: true, message: "Library installed successfully", libraryId: newLibraryId,
                version: parsed?.version, sourceMode: sourceMode, sourceLength: sourceCode.length()]
    } catch (Exception e) {
        log.error "adminCreateLibrary: ${e.message}"
        return [success: false, error: "Library installation failed: ${e.message}"]
    }
}

// hub_update_library: copied from toolUpdateLibraryCode (hubitat-mcp-server.groovy 13795-13930).
// POST /library/saveOrUpdateJson {id, source, version}. Adapted to hubPostJson.
def adminUpdateLibrary(args) {
    requireConfirm(args)
    def libraryId = args.libraryId
    if (!libraryId) throw new IllegalArgumentException("libraryId is required")
    if (!libraryId.toString().isInteger() || libraryId.toString().toInteger() <= 0) {
        throw new IllegalArgumentException("libraryId must be a positive integer (got: '${libraryId}')")
    }
    def modesSet = [args.resave, args.sourceFile, args.source, args.importUrl].count { it }
    if (modesSet > 1) throw new IllegalArgumentException("Provide exactly one of 'source', 'sourceFile', 'importUrl', or 'resave'")

    def sourceCode = null
    def sourceMode = null
    def freshVersion = null
    if (args.resave) {
        sourceMode = "resave"
        def responseText = hubGet("/library/list/single/data/${libraryId}", [:])
        if (!responseText) throw new IllegalArgumentException("Could not fetch current source for library ID ${libraryId}")
        def parsed = new groovy.json.JsonSlurper().parseText(responseText)
        if (!(parsed instanceof List) || parsed.isEmpty()) throw new IllegalArgumentException("Library ID ${libraryId} not found")
        sourceCode = parsed[0].source
        freshVersion = parsed[0].version
        if (!sourceCode) throw new IllegalArgumentException("Library ID ${libraryId} has no source to resave")
    } else if (args.sourceFile) {
        sourceMode = "sourceFile"
        def bytes = downloadHubFile(args.sourceFile)
        if (bytes == null) throw new IllegalArgumentException("Source file '${args.sourceFile}' not found in File Manager")
        sourceCode = new String(bytes, "UTF-8")
    } else if (args.importUrl) {
        sourceMode = "importUrl"
        sourceCode = fetchExternal(args.importUrl)
    } else if (args.source) {
        sourceMode = "source"
        sourceCode = args.source
    } else {
        throw new IllegalArgumentException("One of 'source', 'sourceFile', 'importUrl', or 'resave' is required")
    }

    // Fetch fresh version for the optimistic lock when the source path didn't provide it.
    if (freshVersion == null) {
        def vt = hubGet("/library/list/single/data/${libraryId}", [:])
        if (vt) {
            try {
                def vp = new groovy.json.JsonSlurper().parseText(vt)
                if (vp instanceof List && !vp.isEmpty()) freshVersion = vp[0]?.version
            } catch (Exception vErr) { logDebug "adminUpdateLibrary: version fetch parse failed: ${vErr.message}" }
        }
    }
    if (freshVersion == null) throw new IllegalArgumentException("Could not determine current version for library ID ${libraryId}. Check that the library exists.")

    mcpAdminLog "Updating library ID ${libraryId} (version ${freshVersion}, mode ${sourceMode}, sourceLength ${sourceCode.length()})"
    try {
        def body = groovy.json.JsonOutput.toJson([id: libraryId as Integer, source: sourceCode, version: freshVersion as Integer])
        def resp = hubPostJson("/library/saveOrUpdateJson", body)
        def parsed = _parseJsonBody(resp?.data)
        if (parsed?.success == false) {
            return [success: false, error: "Library update failed: ${parsed?.message ?: 'Hub returned failure'}",
                    libraryId: libraryId, note: "Check the Groovy source code for syntax errors or compilation issues."]
        }
        mcpAdminLog "Library ID ${libraryId} updated successfully (mode ${sourceMode})"
        return [success: true, message: "Library code updated successfully", libraryId: libraryId,
                previousVersion: freshVersion, newVersion: parsed?.version, sourceMode: sourceMode, sourceLength: sourceCode.length()]
    } catch (Exception e) {
        log.error "adminUpdateLibrary: ${e.message}"
        return [success: false, error: "Library update failed: ${e.message}"]
    }
}

// hub_delete_item: copied from toolDeleteItem / _deleteItemViaEndpoint / toolDeleteLibrary
// (hubitat-mcp-server.groovy 13307-13380, 14189-14272). GET delete endpoints; library uses JSON success.
def adminDeleteItem(args) {
    requireConfirm(args)
    def type = args.type
    if (!(type in ["app", "driver", "library"])) {
        throw new IllegalArgumentException("type is required and must be one of: app, driver, library")
    }
    def id = (args.id != null) ? args.id : (type == "app" ? args.appId : (type == "driver" ? args.driverId : args.libraryId))
    if (!id) throw new IllegalArgumentException("id is required")
    if (!id.toString().isInteger() || id.toString().toInteger() <= 0) throw new IllegalArgumentException("id must be a positive integer (got: '${id}')")

    if (type == "library") {
        mcpAdminLog "Deleting library ID ${id}"
        try {
            def responseText = hubGet("/library/edit/deleteJson/${id}", [:])
            def parsed = responseText ? new groovy.json.JsonSlurper().parseText(responseText) : null
            if (parsed?.success == true) {
                return [success: true, message: "Library deleted successfully", libraryId: id]
            }
            return [success: false, error: parsed?.message ?: parsed?.error ?: "Delete may have failed -- check the Libraries code UI",
                    libraryId: id, response: responseText?.take(500)]
        } catch (Exception e) {
            log.error "adminDeleteItem(library): ${e.message}"
            return [success: false, error: "Library deletion failed: ${e.message}"]
        }
    }

    def deletePath = (type == "app") ? "/app/edit/deleteJsonSafe/" : "/driver/editor/deleteJson/"
    mcpAdminLog "Deleting ${type} ID ${id}"
    try {
        def responseText = hubGet("${deletePath}${id}", [:])
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
            return [success: true, message: "${type.capitalize()} deleted successfully", id: id]
        }
        return [success: false, error: "Delete may have failed -- check the Hubitat web UI to verify",
                id: id, response: responseText?.take(500)]
    } catch (Exception e) {
        log.error "adminDeleteItem(${type}): ${e.message}"
        return [success: false, error: "${type.capitalize()} deletion failed: ${e.message}"]
    }
}

// hub_install_bundle: copied from toolInstallBundle + _firmwareAtLeast + _bundleResponseSucceeded
// (hubitat-mcp-server.groovy 13548-13643), incl. the NUMERIC firmware gate at 2.3.8.108 and the
// /bundle2 vs /bundle/uploadZipFromUrl split. Adapted to hubGet/hubPostJson.
def adminInstallBundle(args) {
    requireConfirm(args)
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
    boolean modern = _firmwareAtLeast(fw, "2.3.8.108")
    String endpoint = modern ? "/bundle2/uploadZipFromUrl" : "/bundle/uploadZipFromUrl"

    mcpAdminLog "Installing bundle (endpoint ${endpoint}, fw ${fw}, primary ${primary}, url ${importUrl})"
    try {
        def respBody
        if (modern) {
            // bundle2 is a GET with the url/pwd/private query (server 13577). `private` quoted (keyword).
            respBody = hubGet("/bundle2/uploadZipFromUrl", [url: importUrl, pwd: "", "private": primary.toString()], 300)
        } else {
            def body = groovy.json.JsonOutput.toJson([url: importUrl, installer: primary, pwd: ""])
            def resp = hubPostJson("/bundle/uploadZipFromUrl", body)
            respBody = resp?.data
        }
        boolean ok = _bundleResponseSucceeded(respBody)
        if (!ok) {
            return [success: false,
                    error: "Bundle install failed: the hub returned no success signal. The zip may be malformed/unreachable, or the firmware endpoint unavailable.",
                    endpoint: endpoint, rawResponse: respBody?.toString()?.take(500)]
        }
        mcpAdminLog "Bundle installed successfully from ${importUrl}"
        return [success: true, message: "Bundle installed from ${importUrl}. Its libraries/apps/drivers are now in Code.",
                endpoint: endpoint, primary: primary]
    } catch (Exception e) {
        log.error "adminInstallBundle: ${e.message}"
        return [success: false, error: "Bundle install failed: ${e.message ?: e.toString()}", endpoint: endpoint]
    }
}

// _firmwareAtLeast: copied VERBATIM from hubitat-mcp-server.groovy lines 13630-13644 (numeric
// segment compare; missing/blank/unparseable fw -> true / assume modern).
def _firmwareAtLeast(fw, String target) {
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
    return true
}

// _bundleResponseSucceeded: copied from hubitat-mcp-server.groovy lines 13615-13627.
def _bundleResponseSucceeded(resp) {
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

// hub_get_info: condensed from toolGetHubInfo (hubitat-mcp-server.groovy 6986-7090), surfacing the
// fields CI needs incl. the issue #237 lastSelfDeploy record with ageMs (server 7086-7090).
def adminGetInfo(args) {
    def hub = location?.hub
    def info = [:]
    try { info.model = hub?.hardwareID } catch (Exception e) { info.model = "unavailable" }
    try { info.firmwareVersion = hub?.firmwareVersionString } catch (Exception e) { info.firmwareVersion = "unavailable" }
    try { info.name = hub?.name } catch (Exception e) { info.name = "unavailable" }
    try { info.localIP = hub?.localIP } catch (Exception e) { info.localIP = "unavailable" }
    try {
        def freeMemory = hubGet("/hub/advanced/freeOSMemory", [:])
        if (freeMemory) info.freeMemoryKB = freeMemory.trim()
    } catch (Exception e) { info.freeMemoryKB = "unavailable" }
    info.watchdogEndpoint = true
    // issue #237 self-deploy outcome (server 7086-7090): persists across reloads; add ageMs.
    if (atomicState.lastSelfDeploy != null) {
        def lsd = [:] + atomicState.lastSelfDeploy
        if (lsd.at instanceof Number) lsd.ageMs = now() - (lsd.at as long)
        info.lastSelfDeploy = lsd
    }
    return info
}

// hub_list_apps: copied from toolListHubApps (hubitat-mcp-server.groovy 10650-10675) for
// scope='types', else installed-apps fallback. Adapted to hubGet.
def adminListApps(args) {
    def endpoint = (args?.scope == "types") ? "/hub2/userAppTypes" : "/hub2/appsList"
    def result = [:]
    try {
        def responseText = hubGet(endpoint, [:])
        if (responseText) {
            try {
                def parsed = new groovy.json.JsonSlurper().parseText(responseText)
                result.apps = parsed
                result.count = parsed instanceof List ? parsed.size() : 0
                result.source = "hub_api"
            } catch (Exception parseErr) {
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
        log.warn "adminListApps: ${e.message}"
        result.apps = []
        result.source = "unavailable"
        result.note = "Hub internal API unavailable (${e.message})."
    }
    return result
}

// hub_list_libraries: copied from toolListLibraries (hubitat-mcp-server.groovy 10743-10792).
// Adapted to hubGet; projects to summaries (omits each library's source).
def adminListLibraries(args) {
    def result = [:]
    try {
        def responseText = hubGet("/hub2/userLibraries", [:])
        if (responseText) {
            try {
                def parsed = new groovy.json.JsonSlurper().parseText(responseText)
                if (parsed instanceof List) {
                    result.libraries = parsed.findAll { it != null }.collect { lib ->
                        [id: lib?.id?.toString(), name: lib?.name, namespace: lib?.namespace, version: lib?.version]
                    }
                    result.count = result.libraries.size()
                    result.source = "hub_api"
                } else {
                    result.libraries = []
                    result.count = 0
                    result.rawResponse = responseText?.take(2000)
                    result.source = "hub_api_raw"
                    result.note = "Response was not a JSON array."
                }
            } catch (Exception parseErr) {
                result.libraries = []
                result.count = 0
                result.rawResponse = responseText?.take(2000)
                result.source = "hub_api_raw"
                result.note = "Response was not JSON."
            }
        } else {
            result.libraries = []
            result.count = 0
            result.source = "unavailable"
            result.note = "Empty response from hub API"
        }
    } catch (Exception e) {
        log.warn "adminListLibraries: ${e.message}"
        result.libraries = []
        result.count = 0
        result.source = "unavailable"
        result.note = "Hub internal API unavailable (${e.message})."
    }
    return result
}

// hub_get_jobs: condensed from toolGetHubJobs (hubitat-mcp-server.groovy 11532-11578). Reads the
// scheduled-jobs JSON over loopback. (The main server's toolGetHubJobs reads jobs a different way;
// this app reads /hub/scheduledJobs/json directly -- verify the path + shape on the test hub firmware.)
def adminGetJobs(args) {
    def responseText = hubGet("/hub/scheduledJobs/json", [:])
    if (!responseText) return [error: "Empty response from hub jobs endpoint"]
    def data
    try { data = new groovy.json.JsonSlurper().parseText(responseText) }
    catch (Exception e) { return [error: "Failed to parse hub jobs: ${e.message}", rawResponse: responseText?.take(500)] }

    def scheduledJobs = (data?.jobs ?: []).findAll { it != null }.collect { job ->
        [id: job?.id, name: job?.name, recurring: job?.recurring, method: job?.methodName, nextRun: job?.nextRun]
    }
    def runningJobs = (data?.runningJobs ?: []).findAll { it != null }.collect { job ->
        [id: job?.id, name: job?.name, method: job?.methodName]
    }
    return [
        uptime: data?.uptime,
        scheduledJobs: [count: scheduledJobs.size(), jobs: scheduledJobs],
        runningJobs: [count: runningJobs.size(), jobs: runningJobs]
    ]
}

// hub_read_file: copied from toolReadFile (hubitat-mcp-server.groovy 9809-9855). downloadHubFile + chunk.
def adminReadFile(args) {
    if (!args.fileName) throw new IllegalArgumentException("fileName is required")
    def maxChunkSize = 60000
    def requestedOffset = args.offset ? args.offset as int : 0
    def requestedLength = args.length ? Math.min(args.length as int, maxChunkSize) : maxChunkSize
    def content
    try {
        def bytes = downloadHubFile(args.fileName)
        if (bytes == null) throw new Exception("File not found in File Manager")
        content = new String(bytes, "UTF-8")
    } catch (Exception e) {
        return [success: false, error: "File '${args.fileName}' could not be read: ${e.message}",
                suggestion: "Check the file name in Hubitat > Settings > File Manager."]
    }
    def totalLength = content.length()
    def endIndex = Math.min(requestedOffset + requestedLength, totalLength)
    def chunk = (requestedOffset < totalLength) ? content.substring(requestedOffset, endIndex) : ""
    def hasMore = endIndex < totalLength
    def result = [success: true, fileName: args.fileName, totalLength: totalLength, offset: requestedOffset,
                  chunkLength: chunk.length(), hasMore: hasMore, content: chunk]
    if (hasMore) {
        result.nextOffset = endIndex
        result.remainingChars = totalLength - endIndex
        result.hint = "Call again with offset: ${endIndex} to get the next chunk."
    }
    return result
}

// hub_write_file: copied from toolWriteFile (hubitat-mcp-server.groovy 9862-9920). uploadHubFile + name check.
def adminWriteFile(args) {
    requireConfirm(args)
    if (!args.fileName) throw new IllegalArgumentException("fileName is required")
    if (args.content == null) throw new IllegalArgumentException("content is required")
    if (!(args.fileName ==~ /^[A-Za-z0-9][A-Za-z0-9._-]*$/)) {
        throw new IllegalArgumentException("Invalid file name '${args.fileName}'. Only letters, numbers, hyphens, underscores, and periods; cannot start with a period.")
    }
    try {
        uploadHubFile(args.fileName, args.content.getBytes("UTF-8"))
        return [success: true, message: "File '${args.fileName}' written.", fileName: args.fileName, contentLength: args.content.length()]
    } catch (Exception e) {
        log.error "adminWriteFile: ${e.message}"
        return [success: false, error: "Failed to write file '${args.fileName}': ${e.message}"]
    }
}

// hub_create_backup: copied from toolCreateHubBackup (hubitat-mcp-server.groovy 12023-12057).
// GET /hub/backupDB?fileName=latest. Records state.lastBackupTimestamp.
def adminCreateBackup(args) {
    if (!args.confirm) throw new IllegalArgumentException("You must set confirm=true to create a backup.")
    mcpAdminLog "Creating hub backup..."
    try {
        hubGet("/hub/backupDB", [fileName: "latest"])
        def backupTime = now()
        state.lastBackupTimestamp = backupTime
        return [success: true, message: "Hub backup created successfully", backupTimestampEpoch: backupTime]
    } catch (Exception e) {
        log.error "adminCreateBackup: ${e.message}"
        return [success: false, error: "Backup failed: ${e.message}"]
    }
}

// hub_manage_variables: thin action-dispatch gateway exposing hub_get_variable / hub_set_variable
// for the lease. Copied from toolGetVariable / toolSetVariable (hubitat-mcp-server.groovy 7197-7220,
// 7639-7656) using the sandbox global-var API (getGlobalVar / setGlobalVar).
def adminManageVariables(args) {
    def action = args?.action
    if (!action) {
        return [tools: [
            [name: "hub_get_variable", description: "Read a hub variable by name."],
            [name: "hub_set_variable", description: "Set a hub variable's value (write)."]
        ]]
    }
    def name = args?.name
    switch (action) {
        case "hub_get_variable":
        case "get":
            if (!name) throw new IllegalArgumentException("name is required")
            def hubVar = null
            try { hubVar = getGlobalVar(name) } catch (Exception e) { logDebug "getGlobalVar('${name}') threw: ${e.message}" }
            if (hubVar != null) {
                return [name: name, value: hubVar.value, type: hubVar.type, source: "hub"]
            }
            throw new IllegalArgumentException("Variable not found: ${name}")
        case "hub_set_variable":
        case "set":
            requireConfirm(args)
            if (!name) throw new IllegalArgumentException("name is required")
            try {
                if (setGlobalVar(name, args.value)) {
                    return [success: true, name: name, value: args.value, source: "hub"]
                }
            } catch (Exception e) { logDebug "setGlobalVar('${name}') threw: ${e.message}" }
            return [success: false, name: name,
                    error: "Variable '${name}' could not be set. Hub variables must exist before setGlobalVar can assign them (create it in the Hub Variables UI)."]
        default:
            throw new IllegalArgumentException("Unknown variable action: ${action}. Use hub_get_variable or hub_set_variable.")
    }
}

// ==================== EXTERNAL FETCH (importUrl) ====================
//
// fetchExternal: copies _fetchSourceFromUrl (hubitat-mcp-server.groovy 8908-8967) +
// _httpFetchUrl (8981-9003) VERBATIM in behaviour. httpGet [uri, textParser:true, timeout:60],
// NO ignoreSSLIssues (external cert validation -- this is a hub-side fetch of executable code,
// so the trusted-CA handshake is the floor; self-signed / MITM-d URLs fail). Validates
// scheme / status / body.
def fetchExternal(urlArg) {
    if (urlArg == null) throw new IllegalArgumentException("importUrl is required")
    if (!(urlArg instanceof String)) throw new IllegalArgumentException("importUrl must be a String")
    String url = (String) urlArg
    def lower = url.toLowerCase()
    if (!(lower.startsWith("http://") || lower.startsWith("https://"))) {
        throw new IllegalArgumentException("importUrl scheme must be http or https (got '${url.take(40)}')")
    }
    def resp
    try {
        resp = _httpFetchUrl(url)
    } catch (Exception e) {
        // e.message can be null on SSL/socket exceptions; toString() always returns something.
        def cause = e.toString()
        log.error "fetchExternal ${url}: ${cause}"
        throw new IllegalArgumentException("importUrl fetch failed: ${cause}")
    }
    def status = resp?.status
    def body = resp?.body
    if (status == null) throw new IllegalArgumentException("importUrl fetch returned no response (status null) for ${url}")
    if (status != 200) throw new IllegalArgumentException("importUrl returned HTTP ${status} for ${url}")
    if (!body) throw new IllegalArgumentException("importUrl returned empty body from ${url}")
    logInfo "fetchExternal ${url}: ${body.length()} bytes"
    return body
}

// _httpFetchUrl: copied VERBATIM from hubitat-mcp-server.groovy lines 8981-9003.
// NO ignoreSSLIssues -- external cert validation. Body read failures re-thrown, not swallowed.
private Map _httpFetchUrl(String url) {
    def status = null
    def body = null
    def contentType = null
    def readError = null
    httpGet([
        uri: url,
        textParser: true,
        timeout: 60
    ]) { resp ->
        status = resp.status
        contentType = resp.headers?.'Content-Type'?.toString()
        try { body = resp.data.text }
        catch (Exception readErr) { readError = readErr }
    }
    if (readError != null) throw readError
    return [status: status, body: body, contentType: contentType]
}

// ==================== ADMIN TOOL DEFINITIONS (tools/list) ====================
//
// Minimal MCP tool list for tools/list. Names IDENTICAL to the main server so CI works by URL swap.
def getAdminToolDefinitions() {
    return [
        [name: "hub_update_app", description: "Update an Apps Code class source (deploy). One of source/sourceFile/importUrl/resave; confirm:true required.",
         inputSchema: [type: "object", properties: [
            appId: [type: "string", description: "Apps Code CLASS id to update."],
            source: [type: "string"], sourceFile: [type: "string"], importUrl: [type: "string"], resave: [type: "boolean"],
            selfUpdate: [type: "boolean", description: "Set true when deploying the MAIN MCP server's own app-code class so the issue #237 lastSelfDeploy outcome is captured."],
            selfClassId: [type: "string", description: "The MAIN server's own Apps Code class id; if it matches appId, the #237 self-deploy capture arms."],
            confirm: [type: "boolean"]], required: ["appId", "confirm"]]],
        [name: "hub_get_source", description: "Read app/driver/library source (chunked); auto-saves the full source to File Manager so a restore can read it.",
         inputSchema: [type: "object", properties: [
            type: [type: "string", enum: ["app", "driver", "library"]], id: [type: "string"],
            offset: [type: "integer"], length: [type: "integer"]], required: ["type", "id"]]],
        [name: "hub_create_library", description: "Install a new library. One of source/sourceFile/importUrl; confirm:true required.",
         inputSchema: [type: "object", properties: [
            source: [type: "string"], sourceFile: [type: "string"], importUrl: [type: "string"], confirm: [type: "boolean"]], required: ["confirm"]]],
        [name: "hub_update_library", description: "Update an existing library. One of source/sourceFile/importUrl/resave; confirm:true required.",
         inputSchema: [type: "object", properties: [
            libraryId: [type: "string"], source: [type: "string"], sourceFile: [type: "string"], importUrl: [type: "string"], resave: [type: "boolean"], confirm: [type: "boolean"]],
            required: ["libraryId", "confirm"]]],
        [name: "hub_delete_item", description: "Delete an app/driver/library by id. confirm:true required.",
         inputSchema: [type: "object", properties: [type: [type: "string", enum: ["app", "driver", "library"]], id: [type: "string"], confirm: [type: "boolean"]], required: ["type", "id", "confirm"]]],
        [name: "hub_install_bundle", description: "Install a code bundle .zip from a URL the hub fetches itself (HPM-style). confirm:true required.",
         inputSchema: [type: "object", properties: [importUrl: [type: "string"], primary: [type: "boolean"], confirm: [type: "boolean"]], required: ["importUrl", "confirm"]]],
        [name: "hub_get_info", description: "Hub model/firmware/memory + the issue #237 lastSelfDeploy record (with ageMs)."],
        [name: "hub_list_apps", description: "List Apps Code types (scope='types') or installed apps.",
         inputSchema: [type: "object", properties: [scope: [type: "string", enum: ["types", "instances"]]]]],
        [name: "hub_list_libraries", description: "List libraries (id/name/namespace/version summaries)."],
        [name: "hub_get_jobs", description: "List scheduled + running hub jobs."],
        [name: "hub_read_file", description: "Read a File Manager file (chunked).",
         inputSchema: [type: "object", properties: [fileName: [type: "string"], offset: [type: "integer"], length: [type: "integer"]], required: ["fileName"]]],
        [name: "hub_write_file", description: "Write a File Manager file. confirm:true required.",
         inputSchema: [type: "object", properties: [fileName: [type: "string"], content: [type: "string"], confirm: [type: "boolean"]], required: ["fileName", "content", "confirm"]]],
        [name: "hub_create_backup", description: "Trigger a hub DB backup. confirm:true required.",
         inputSchema: [type: "object", properties: [confirm: [type: "boolean"]], required: ["confirm"]]],
        [name: "hub_manage_variables", description: "Read/set hub variables for the lease (hub_get_variable / hub_set_variable). Call with no action to list sub-tools.",
         inputSchema: [type: "object", properties: [action: [type: "string", enum: ["hub_get_variable", "hub_set_variable"]], name: [type: "string"], value: [type: "string"], confirm: [type: "boolean"]]]]
    ]
}

// Parse a loopback POST response body (String) into a Map/List. hubPostJson returns [status,data];
// data is the raw body String. Mirrors hubInternalPostJson's parse step (server 9270-9280).
def _parseJsonBody(data) {
    if (data == null) return null
    if (data instanceof Map || data instanceof List) return data
    try { return new groovy.json.JsonSlurper().parseText(data.toString()) }
    catch (Exception e) { log.error "_parseJsonBody: response not JSON: ${data.toString()?.take(200)}"; return null }
}

// ---- flag file IO (File Manager, hub-local) ----
Map readFlag() {
    String txt = readHubFileText(flagFileName ?: "e2e-deadman-v2.json")
    if (txt == null) return null
    try {
        def parsed = new groovy.json.JsonSlurper().parseText(txt)
        if (!(parsed instanceof Map)) { log.error "readFlag: flag JSON is not a JSON object -- ignoring."; return null }
        return (Map) parsed
    }
    catch (Exception e) { log.error "readFlag: flag is not valid JSON: ${e.message}"; return null }
}

boolean writeFlag(Map flag) {
    try {
        uploadHubFile(flagFileName ?: "e2e-deadman-v2.json", groovy.json.JsonOutput.toJson(flag).getBytes("UTF-8"))
        return true
    } catch (Exception e) {
        log.error "writeFlag: failed to write flag: ${e.message}"
        return false
    }
}

String readHubFileText(String name) {
    try {
        def bytes = downloadHubFile(name)
        if (bytes == null) return null
        return new String(bytes, "UTF-8")
    } catch (Exception e) {
        logDebug "readHubFileText('${name}'): ${e.message}"
        return null
    }
}

// ---- minimal hub-internal loopback (mirrors the main app's _hubRequest, trimmed) ----
String hubGet(String path, Map query, int timeoutSec = 30) {
    // timeoutSec defaults to 30 (fast loopback reads); the bundle-install GET passes ~300, since the
    // hub fetches + unpacks the zip server-side and that can exceed 30s (matches the main server's
    // 300s bundle timeout).
    def params = [uri: "http://127.0.0.1:8080", path: path, query: query,
                  textParser: true, ignoreSSLIssues: true, timeout: timeoutSec]
    def cookie = secCookie()
    if (cookie) params.headers = [Cookie: cookie]
    String out = null
    try { httpGet(params) { resp -> out = respText(resp) } }
    catch (Exception e) { log.error "hubGet ${path}: ${e.message}" }
    return out
}

Map hubPostForm(String path, Map body) {
    // 420s: restoring the ~1.6MB MCP server source is a large form POST that can be slow.
    def params = [uri: "http://127.0.0.1:8080", path: path, body: body,
                  requestContentType: "application/x-www-form-urlencoded",
                  textParser: true, ignoreSSLIssues: true, timeout: 420]
    def headers = [Connection: "keep-alive"]
    def cookie = secCookie()
    if (cookie) headers.Cookie = cookie
    params.headers = headers
    Map out = [status: null, data: null]
    try { httpPost(params) { resp -> out = [status: resp.status, data: respText(resp)] } }
    catch (Exception e) { log.error "hubPostForm ${path}: ${e.message}" }
    return out
}

Map hubPostJson(String path, String jsonBody) {
    def params = [uri: "http://127.0.0.1:8080", path: path, body: jsonBody,
                  requestContentType: "application/json",
                  textParser: true, ignoreSSLIssues: true, timeout: 420]
    def headers = [Connection: "keep-alive"]
    def cookie = secCookie()
    if (cookie) headers.Cookie = cookie
    params.headers = headers
    Map out = [status: null, data: null]
    try { httpPost(params) { resp -> out = [status: resp.status, data: respText(resp)] } }
    catch (Exception e) { log.error "hubPostJson ${path}: ${e.message}" }
    return out
}

String respText(resp) {
    try {
        def d = resp?.data
        if (d == null) return null
        if (d instanceof CharSequence) return d.toString()
        return d.text
    } catch (Exception e) { log.error "respText: ${e.message}"; return null }
}

// Hub Security cookie (only when this hub has Hub Security on). Cached in atomicState.
String secCookie() {
    if (settings?.hubSecurityEnabled != true) return null
    if (!settings?.hubSecurityUser || !settings?.hubSecurityPassword) return null
    if (atomicState.secCookie && atomicState.secCookieExp && atomicState.secCookieExp > now()) {
        return atomicState.secCookie
    }
    String cookie = null
    try {
        httpPost([uri: "http://127.0.0.1:8080", path: "/login",
                  body: [username: settings?.hubSecurityUser, password: settings?.hubSecurityPassword],
                  textParser: true, ignoreSSLIssues: true]) { resp ->
            // Set-Cookie may be a String or, when several cookies are set, a List -- normalize first
            // (calling .split on a List throws MissingMethodException).
            def sc = resp?.headers?.'Set-Cookie'
            if (sc instanceof List) { sc = sc ? sc[0] : null }
            cookie = sc?.toString()?.split(';')?.getAt(0)
        }
    } catch (Exception e) { log.error "secCookie: hub security auth failed: ${e.message}" }
    if (cookie) { atomicState.secCookie = cookie; atomicState.secCookieExp = now() + (30 * 60 * 1000) }
    return cookie
}

// ---- helpers ----
String statusText() {
    try {
        def flag = readFlag()
        if (flag == null) return "Status: idle (no flag file)."
        if (flag.armed == true) {
            def secs = (((flag.deadline ?: 0L) as long) - now()) / 1000 as long
            return "Status: ARMED, run ${flag.runId}, ${secs}s to deadline."
        }
        return "Status: disarmed. Last restore: ${flag.restoreResult ?: 'none'}${flag.restoredAt ? ' at ' + flag.restoredAt : ''} (${flag.restoreTrigger ?: '-'})."
    } catch (Exception e) {
        return "Status: (unavailable until installed)."
    }
}

void mcpAdminLog(String m) { logInfo "[mcp-admin] ${m}" }
void logInfo(String m)  { log.info  "[deadman-v2] ${m}" }
void logDebug(String m) { if (settings?.debugLogging != false) log.debug "[deadman-v2] ${m}" }
