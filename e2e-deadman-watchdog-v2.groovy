/**
 * E2E Dead-Man Watchdog v2 (issue #209 e2e safety net -- package-aware + deploy controller)
 *
 * v2 of the standalone e2e recovery app. The original "E2E Dead-Man Watchdog" is left
 * installed but ORPHANED: e2e only arms THIS app (its own Apps Code class + its own flag
 * file e2e-deadman-v2.json), so v1 sees no flag of its own and sits idle/harmless.
 *
 * What v2 does: it restores the WHOLE main package by installing main's BUNDLE (which delivers
 * every #include'd library) and then every app, from the CANONICAL raw.githubusercontent.com
 * URLs the arm recorded in the flag manifest at MAIN_SHA -- the HPM-repair path
 * (adminInstallBundle(importUrl) -> GET /bundle2/uploadZipFromUrl for the bundle,
 * adminUpdateApp(importUrl) -> POST /app/ajax/update for each app). NOTHING is cached locally,
 * so GitHub reachability is required at fire time. It restores on BOTH a clean disarm
 * (intent=disarm) and a missed-deadline fire, and writes restoreResult/restoreFor back into the
 * flag so CI can confirm the reinstall landed.
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
 * With debug logging on (default), it also writes the first 500 chars of each request/response body
 * to the hub log -- so set/variable values and inline-source prefixes are visible there (the token
 * is NOT: it rides the query string, not the body). Acceptable for a test hub; do not ship elsewhere.
 */
definition(
    name: "E2E Dead-Man Watchdog v2",
    namespace: "mcp",
    author: "kingpanther13",
    description: "Package-aware autonomous on-hub watchdog + small deploy-controller MCP server: restores main (libraries first, app last) from a local cache on a missed/clean disarm, and exposes a token-gated /mcp admin endpoint over hub loopback.",
    category: "Utility",
    iconUrl: "https://raw.githubusercontent.com/hubitat/HubitatPublic/master/app-dev/icon.png",
    iconX2Url: "https://raw.githubusercontent.com/hubitat/HubitatPublic/master/app-dev/icon.png",
    // Copied from hubitat-mcp-server.groovy (definition oauth).
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
            // mainPage (hubitat-mcp-server.groovy). createAccessToken() runs in
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
// Copied from hubitat-mcp-server.groovy (top-level mappings{} for /mcp).
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
    // Copied from hubitat-mcp-server.groovy: create the OAuth access token
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

    // Parse the deadline defensively: a non-numeric value must NOT throw out of this tick (that would
    // silently disable the dead-man). Treat an unparseable deadline as already-expired so we FIRE
    // (restore) rather than sit armed forever -- the safe direction.
    long deadline = 0L
    if (flag.deadline != null) {
        try { deadline = flag.deadline as long }
        catch (Exception e) { log.warn "checkDeadman: non-numeric deadline '${flag.deadline}' -- treating as expired (will fire)." }
    }
    if (now() < deadline) {
        logDebug "armed; ${((deadline - now()) / 1000) as long}s until deadline"
        return
    }

    // Armed AND past deadline -> the session never disarmed -> FIRE (crash/brick recovery).
    log.warn "E2E Dead-Man Watchdog v2 FIRING: armed flag expired ${((now() - deadline) / 1000) as long}s ago. Restoring main package for run ${flag.runId}."
    actAndRecord(flag, "fire")
}

// One-shot accelerator scheduled by adminWriteFile right after CI writes the armed flag. A handler
// name DISTINCT from "checkDeadman" is deliberate: it guarantees runIn() never overwrites the
// runEvery1Minute("checkDeadman") periodic dead-man job. Delegates to the same idempotent check, so it
// just makes the disarm restore happen ~2s after the flag write instead of on the next minute tick.
def deadmanKick() { checkDeadman() }

// Run the package restore, then record result + idempotency stamp into the flag (the signal CI
// polls). BOTH triggers retry up to a 5-tick cap before latching "failed" (a transient miss must not
// latch the restore failed); a success stamps restoreFor=runId so it runs at most once per run.
private void actAndRecord(Map flag, String trigger) {
    // SINGLE-FLIGHT LATCH. restoreFor is only stamped at the END of a restore, but a restore takes
    // 3-4 minutes and checkDeadman fires every minute (plus the adminWriteFile kick) -- without this
    // latch each tick starts ANOTHER full restore and they run CONCURRENTLY (seen live: three
    // overlapping "disarm complete" at 193/210/235s). Each restore is a bundle install + every
    // library + two app recompiles, so the overlap is a hub-load spike big enough to trip the
    // platform's per-app load limiter (LimitExceededException), which then blocks device commands
    // hub-wide until a reboot. atomicState narrows the duplicate window from minutes to
    // milliseconds; the 10-minute staleness escape keeps a crashed restore from wedging the latch.
    long nowMs = now()
    Long inFlightAt = null
    try { inFlightAt = atomicState.restoreInFlightAt as Long } catch (Exception ignore) { inFlightAt = null }
    if (atomicState.restoreInFlightFor?.toString() == flag.runId?.toString()
            && inFlightAt != null && (nowMs - inFlightAt) < 600000L) {
        logInfo "restore for run ${flag.runId} already in flight (${((nowMs - inFlightAt) / 1000) as long}s, trigger=${trigger}) -- skipping duplicate tick"
        return
    }
    atomicState.restoreInFlightFor = flag.runId
    atomicState.restoreInFlightAt = nowMs
    try {
        actAndRecordLocked(flag, trigger)
    } finally {
        atomicState.restoreInFlightFor = null
        atomicState.restoreInFlightAt = null
    }
}

private void actAndRecordLocked(Map flag, String trigger) {
    Map res = restorePackage(flag)
    if (res.ok) {
        flag.armed = false
        flag.restoreFor = flag.runId
        flag.restoreResult = "restored"
        flag.restoreTrigger = trigger
        flag.restoredAt = now()
        flag.restoreDetail = res.detail
        flag.fireAttempts = 0
        // Restore confirmed -> the hub is canonical main again. Stamp the SHA marker the PR
        // install cleared so the next run's arm can skip its main refresh. CI's disarm is
        // fire-and-forget (it no longer polls for this restore), so the stamp must happen
        // HERE, on both the disarm and the dead-man fire paths. Best-effort: a missed stamp
        // just means the next arm re-refreshes main.
        if (flag.canonicalMainSha) {
            try { uploadHubFile("mcp-main-deployed-sha.txt", flag.canonicalMainSha.toString().getBytes("UTF-8")) }
            catch (Exception e) { log.warn "could not stamp the canonical-main marker after restore: ${e.message}" }
        }
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

// ---- restore the whole package from the manifest's canonical install URLs: drop the PR's stale bundle,
// install main's BUNDLE (delivers every library), install the APP(s), then drop the PR's stale libraries.
// Leaves the hub carrying ONLY main's bundles + libraries + app -- no leftover from the PR install (the
// user's "overwrite with main without any stale bundles/libraries"). The bundle + app installs fetch from
// the canonical https URLs the arm recorded, so GitHub MUST be reachable at fire time. Orphan cleanup is
// best-effort (it never fails the restore -- installing main's bundle+app is the guarantee).
private Map restorePackage(Map flag) {
    def m = flag.manifest
    if (!(m instanceof Map)) return [ok: false, detail: "no manifest in flag"]
    if (settings?.hubSecurityEnabled == true && secCookie() == null) {
        return [ok: false, detail: "hub security enabled but auth failed (no cookie)"]
    }
    def detail = []

    // (0) Remove the PR's stale bundle CONTAINER(s) FIRST (best-effort). Doing this before restoring
    // libraries means that if deleting a bundle cascade-removes its managed libraries (incl. a main one),
    // the restore below re-creates them; if it does NOT cascade, step (3) deletes the PR-only libraries.
    detail << reconcileStaleBundles(m)

    // (1) Libraries -- delivered the HPM way: install canonical main's bundle from the CANONICAL
    // https URL the arm recorded in the manifest (one operation; the hub fetches, unpacks and
    // compiles every library inside, exactly like a user's HPM update -- no per-library writes, no
    // per-library dependent recompiles). Requires GitHub reachability at fire time -- acceptable,
    // the whole e2e pipeline depends on GitHub anyway. An old-format flag whose manifest bundles
    // carry no url has no install source (nothing is cached locally) and fails loudly below so the
    // operator re-arms -- there is no per-library fallback path.
    def cachedBundles = (m.bundles instanceof List) ? m.bundles.findAll { it?.url } : []
    // Run-scoped skip: the deploy stamps "<runId>:unchanged" when the PR's bundle was byte-identical
    // to main's (deterministic build), meaning the libraries never left main's bytes this run --
    // reinstalling the cached bundle would only fire a redundant dependent-recompile wave. Anything
    // but a verified THIS-run "unchanged" (stale runId from a crashed run, "changed", unreadable
    // marker) falls through to the install: fail-safe in the direction of restoring.
    boolean bundleUnchangedThisRun = false
    try {
        def marker = readHubFileText("e2e-pr-bundle-state.txt")?.trim()
        bundleUnchangedThisRun = (marker == "${flag.runId}:unchanged".toString())
    } catch (Exception ignore) { bundleUnchangedThisRun = false }
    if (bundleUnchangedThisRun && !cachedBundles.isEmpty()) {
        detail << "bundle-skip:pr-identical-to-main(run ${flag.runId})"
    } else if (!cachedBundles.isEmpty()) {
        for (b in cachedBundles) {
            // Install from the CANONICAL https URL the arm recorded -- the exact HPM path. NEVER a
            // hub-local /local/ URL: registering the hub's own loopback URL as a bundle source is
            // off the platform's tested path (the UI uses uploadZip+processUploadedZip for local
            // files) and coincided with /hub2/userLibraries wedging hub-wide. cachedBundles is
            // pre-filtered to entries WITH a url (the no-url old-format case is handled below).
            String srcUrl = b.url?.toString()
            def r = null
            try { r = adminInstallBundle([importUrl: srcUrl, confirm: true]) }
            catch (Exception e) { r = [success: false, error: e.message] }
            boolean ok = (r?.success == true)
            detail << "bundle ${b?.name ?: '?'}(url)=${ok}"
            if (!ok) return [ok: false, detail: "bundle ${b?.name} restore failed: ${r?.error} [${detail.join('; ')}]"]
        }
    } else if ((m.bundles instanceof List) && !m.bundles.isEmpty()) {
        // Bundle entries exist but carry no url: an old-format flag (pre url-manifest arm). There is
        // no local cache to fall back to anymore -- fail loudly so the operator re-arms; the watchdog
        // endpoint stays reachable for manual recovery (the design's safety floor).
        return [ok: false, detail: "manifest bundles carry no url (old-format flag?) -- re-run the arm [${detail.join('; ')}]"]
    }
    // (no bundles at all = an apps-only package; nothing library-side to restore)
    // (2) App(s) -- installed from their CANONICAL https URLs, the exact HPM repair path (the hub
    // downloads main at the END; nothing was saved locally). adminUpdateApp(importUrl) runs hub-side
    // here (no cloud relay cap) and captures the hub's verbatim compile error (#237). A landing
    // assert cross-checks the live source length against the manifest's expected char count.
    def apps = (m.apps instanceof List) ? m.apps : (m.app ? [m.app] : [])
    for (a in apps) {
        String classId = a?.classId?.toString()
        String srcUrl = a?.url?.toString()
        if (!classId || !srcUrl) {
            return [ok: false, detail: "app entry missing classId/url (old-format flag? re-arm) [${detail.join('; ')}]"]
        }
        def r = null
        try { r = adminUpdateApp([appId: classId, importUrl: srcUrl, confirm: true]) }
        catch (Exception e) { r = [success: false, error: e.message] }
        boolean ok = (r?.success == true)
        if (ok && a?.mainChars) {
            try {
                def live = hubGet("/app/ajax/code", [id: classId])
                def parsed = live ? new groovy.json.JsonSlurper().parseText(live) : null
                def liveLen = (parsed instanceof Map) ? parsed.source?.toString()?.length() : null
                if (liveLen != null && liveLen.toString() != a.mainChars.toString()) {
                    ok = false
                    r = [success: false, error: "landed source length ${liveLen} != expected ${a.mainChars}"]
                }
            } catch (Exception e) { logDebug "restore app ${classId}: length cross-check skipped (${e.message})" }
        }
        detail << "app ${classId}(url)=${ok}"
        if (!ok) return [ok: false, detail: "app ${classId} restore failed: ${r?.error} [${detail.join('; ')}]"]
    }
    if (apps.isEmpty()) return [ok: false, detail: "manifest has no app to restore [${detail.join('; ')}]"]

    // (3) Remove the PR's stale LIBRARIES LAST (best-effort): any mcp-namespace library not in the
    // manifest's main set is a PR-only module. Safe here -- main's app (restored above) does not #include
    // them, so deleting them can't break it.
    detail << reconcileStaleLibraries(m)

    return [ok: true, detail: detail.join('; ')]
}

// Delete any mcp-namespace BUNDLE not in the manifest's main bundle set (recorded by the arm = the
// bundles present once canonical main was established, BEFORE the PR deploy). Best-effort; never throws
// out of the restore. SKIPPED when the manifest carries no main bundle set (an older arm flag): without
// it we can't tell main's bundle from a PR's, and deleting blindly could remove main's. Scoped to
// namespace "mcp" so unrelated bundles on the shared test hub are never touched.
private String reconcileStaleBundles(Map m) {
    def mainBundles = (m?.bundles instanceof List) ? m.bundles : []
    if (mainBundles.isEmpty()) return "bundle-cleanup:skipped(no main bundle set in manifest)"
    def listed = adminListBundles([:])
    if (listed?.source != "hub_api") return "bundle-cleanup:skipped(list=${listed?.source})"
    def removed = []
    def failed = []
    for (b in (listed.bundles ?: [])) {
        if (b?.namespace?.toString() != "mcp") continue
        if (b?.id == null) continue
        boolean keep = mainBundles.any {
            (it?.namespace?.toString() == b.namespace?.toString()) && (it?.name?.toString() == b.name?.toString())
        }
        if (keep) continue
        try {
            def r = adminDeleteBundle([bundleId: b.id.toString(), confirm: true])
            if (r?.success == true) removed << b.name else failed << b.name
        } catch (Exception e) { logDebug "reconcileStaleBundles ${b?.name}: ${e.message}"; failed << b?.name }
    }
    return "bundle-cleanup:removed=${removed}${failed ? " failed=${failed}" : ''}"
}

// Delete any mcp-namespace LIBRARY not in the manifest's main library set -- INCLUDING same-name
// DUPLICATE rows whose id differs from the manifest's (name-only matching kept every duplicate
// forever; 36 accumulated and wedged /hub2/userLibraries hub-wide). A row is kept only when BOTH
// its namespace.name AND its id match a manifest entry. Best-effort. Scoped to namespace "mcp";
// runs AFTER the app restore so a still-#included main library is never removed -- note the hub
// itself also refuses deleting the row an installed app's #include binds ("Library is in use"),
// a second guard under this one.
private String reconcileStaleLibraries(Map m) {
    def mainLibs = (m?.libraries instanceof List) ? m.libraries : []
    if (mainLibs.isEmpty()) return "lib-cleanup:skipped(no main library set in manifest)"
    def listed = adminListLibraries([:])
    if (listed?.source != "hub_api") return "lib-cleanup:skipped(list=${listed?.source})"
    def removed = []
    def failed = []
    for (lib in (listed.libraries ?: [])) {
        if (lib?.namespace?.toString() != "mcp") continue
        if (lib?.id == null) continue
        boolean keep = mainLibs.any {
            (it?.namespace?.toString() == lib.namespace?.toString()) &&
            (it?.name?.toString() == lib.name?.toString()) &&
            (it?.id?.toString() == lib.id?.toString())
        }
        if (keep) continue
        try {
            def r = adminDeleteItem([type: "library", id: lib.id.toString(), confirm: true])
            if (r?.success == true) removed << "${lib.name}(${lib.id})" else failed << "${lib.name}(${lib.id})"
        } catch (Exception e) { logDebug "reconcileStaleLibraries ${lib?.name}: ${e.message}"; failed << "${lib?.name}(${lib?.id})" }
    }
    return "lib-cleanup:removed=${removed}${failed ? " failed=${failed}" : ''}"
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
// handleMcpGet: copied from hubitat-mcp-server.groovy -- this endpoint is
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

// processJsonRpcMessage: copied from hubitat-mcp-server.groovy.
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
// Copied/condensed from hubitat-mcp-server.groovy (handleToolsCall).
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

// jsonRpcResult / jsonRpcError: copied verbatim from hubitat-mcp-server.groovy.
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
        case "hub_force_delete_app": return adminForceDeleteInstalledApp(args)
        case "hub_purge_e2e_artifacts": return adminPurgeE2eArtifacts(args)
        case "hub_set_app_disabled": return adminSetAppDisabled(args)
        case "hub_get_metrics":      return adminGetMetrics(args)
        case "hub_update_platform":  return adminUpdatePlatform(args)
        case "hub_get_memory_history": return adminGetMemoryHistory(args)
        case "hub_get_hub_logs":     return adminGetHubLogs(args)
        case "hub_list_app_instances": return adminListAppInstances(args)
        case "hub_install_bundle":  return adminInstallBundle(args)
        case "hub_list_bundles":    return adminListBundles(args)
        case "hub_delete_bundle":   return adminDeleteBundle(args)
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

// hub_update_app: copied from toolUpdateItemCodeInner (hubitat-mcp-server.groovy),
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

        // Deploy-outcome record (generalized from the issue #237 lastSelfDeploy; persists across reloads).
        // Stashed for EVERY app update (with appId), not just self-update: the ~1.6MB app deploy exceeds
        // the cloud relay's response window, so CI recovers success + the verbatim compile error by polling
        // hub_get_info.lastSelfDeploy. The watchdog is stable (deploying the MAIN server never bricks IT),
        // so this record is always queryable -- the whole point of routing deploys through the watchdog.
        try {
            atomicState.lastSelfDeploy = [
                appId: itemId?.toString(),
                success: success,
                error: success ? null : (errorMsg ?: "Update failed -- the hub returned an error"),
                sourceMode: sourceMode,
                importUrl: (args.importUrl ?: null),
                sourceLength: sourceCode.length(),
                at: now()
            ]
        } catch (Exception ignore) { /* bookkeeping must never break the deploy */ }

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
        // Failure-case deploy-outcome capture (always, with appId -- see the success branch above).
        try {
            atomicState.lastSelfDeploy = [
                appId: itemId?.toString(),
                success: false,
                error: "App update failed: ${e.message}",
                sourceMode: sourceMode,
                importUrl: (args.importUrl ?: null),
                sourceLength: (sourceCode != null ? sourceCode.length() : 0),
                at: now()
            ]
        } catch (Exception ignore) { }
        log.error "adminUpdateApp: app update failed: ${e.message}"
        return [success: false, error: "App update failed: ${e.message}"]
    }
}

// hub_get_source: copied from toolGetSource / toolGetItemSource / toolGetLibrarySource
// (hubitat-mcp-server.groovy). The File Manager
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
    // noSave opt-out: a length/metadata probe (e.g. the CI deploy poll reading totalLength) must NOT
    // auto-save, or it overwrites the dead-man restore cache 'mcp-source-<type>-<id>.groovy' with the
    // CURRENTLY-live source (the just-deployed PR code mid-run), which would make the disarm "restore
    // main" from a cache holding PR code. Arm (which DOES want the cache populated) omits noSave.
    def savedToFile = null
    if (totalLength > maxChunkSize && args.noSave != true) {
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

// hub_create_library: copied from toolInstallLibrary (hubitat-mcp-server.groovy).
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

// hub_update_library: copied from toolUpdateLibraryCode (hubitat-mcp-server.groovy).
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
        // Fail CLOSED: a dropped/empty loopback POST yields resp.data null -> parsed null, and a lone
        // `parsed?.success == false` gate would fall through to success:true (null?.success == false is
        // false). The hub returns the saved library's id + version on success; require status 200, a
        // parsed body, no explicit failure, and an id -- mirrors adminCreateLibrary.
        if (resp?.status != 200 || parsed == null || parsed?.success == false || parsed?.id == null) {
            return [success: false,
                    error: "Library update failed: ${parsed?.message ?: (resp?.status != 200 ? "hub HTTP ${resp?.status}" : 'empty/dropped hub response -- the loopback POST may have failed')}",
                    libraryId: libraryId, note: "Check the Groovy source for syntax/compile errors; a bundle-managed library may need delete+recreate instead of in-place update."]
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
// (hubitat-mcp-server.groovy). GET delete endpoints; library uses JSON success.
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

// hub_force_delete_app: force-delete an INSTALLED-APP INSTANCE (e.g. an RM rule) via
// /installedapp/forcedelete/<id>/quiet -- the same path RM's "Delete Rule" button uses, bypassing
// child/device checks. Loosely based on the server's _rmForceDeleteApp (same endpoint). Status-aware
// via hubGetStatus: the forcedelete endpoint answers SUCCESS with a 302 redirect, so a 2xx/3xx status
// is success while >=400 -- or no status at all, meaning the request never reached the hub (auth/
// transport) -- is reported as success:false so the disarm sweep can warn + keep its recovery list
// (any rule that survives is reaped by the separate post-restore --cleanup-only prefix sweep, not a
// re-list). DISTINCT from hub_delete_item(type:'app'), which hits /app/edit/deleteJsonSafe (an Apps
// Code CLASS, not a running instance). Used by the disarm-time deferred-native-rule sweep.
def adminForceDeleteInstalledApp(args) {
    requireConfirm(args)
    def id = (args.id != null) ? args.id : args.appId
    if (!id) throw new IllegalArgumentException("id (the installed-app instance id) is required")
    if (!id.toString().isInteger() || id.toString().toInteger() <= 0) {
        throw new IllegalArgumentException("id must be a positive integer (got: '${id}')")
    }
    mcpAdminLog "Force-deleting installed app instance ${id} (/installedapp/forcedelete/${id}/quiet)"
    def resp = hubGetStatus("/installedapp/forcedelete/${id}/quiet", [:])
    Integer st = (resp?.status != null) ? (resp.status as Integer) : null
    // The forcedelete endpoint answers SUCCESS with a 302 redirect to the apps list (a plain 2xx is
    // also fine). >=400 -- or no status at all, meaning the request never reached the hub
    // (auth/transport) -- is a real failure: report it so the disarm sweep warns + keeps its id list.
    if (st == null || st >= 400) {
        return [success: false, error: "Force-delete of installed app ${id} did not confirm (status=${st ?: 'none'}) -- endpoint error, auth failure, or the request never reached the hub.", id: id]
    }
    // The 302 alone is NOT proof the delete committed: the disarm sweep fires these while the hub is
    // recompiling the restored main app, a window where admin-endpoint writes are known to commit
    // late or strand on this firmware. Verify gone-ness via /installedapp/json/<id> (the same
    // existence read the server's VRB delete uses: {id,...} while installed, 404/empty once gone).
    // Only a definite "absent" confirms; "still found" or an unreadable check reports success:false
    // so the caller keeps the id on its recovery list -- re-deleting a gone app is a harmless no-op.
    def check = hubGetStatus("/installedapp/json/${id}", [:])
    Integer cst = (check?.status != null) ? (check.status as Integer) : null
    if (cst == 404 || (cst != null && cst < 400 && !(check.data?.toString()?.trim()))) {
        return [success: true, message: "Force-deleted installed app ${id} (HTTP ${st}; verified gone).", id: id]
    }
    if (cst != null && cst < 400) {
        def parsed = null
        try {
            parsed = new groovy.json.JsonSlurper().parseText(check.data.toString())
        } catch (Exception ignore) {
            // An unparseable 200 (e.g. a login page) is NOT proof of absence -- fall through to keep-the-id.
            return [success: false, error: "Force-delete of installed app ${id} returned HTTP ${st} but the gone-check body was unparseable (auth/login page?) -- keep the id and re-delete to be safe.", id: id]
        }
        if ((parsed instanceof Map) && parsed.id != null) {
            return [success: false, error: "Force-delete of installed app ${id} returned HTTP ${st} but the app still exists (late/stranded commit) -- keep the id and re-delete.", id: id]
        }
        return [success: true, message: "Force-deleted installed app ${id} (HTTP ${st}; verified gone).", id: id]
    }
    return [success: false, error: "Force-delete of installed app ${id} returned HTTP ${st} but the gone-check could not read /installedapp/json (status=${cst ?: 'none'}) -- keep the id and re-delete to be safe.", id: id]
}

// hub_purge_e2e_artifacts: ONE-call LOCAL sweep of leftover test fixtures. Enumerates every installed-app
// instance whose name starts with the BAT_E2E_ test prefix (via /hub2/appsList, the same read
// adminListAppInstances uses) and force-deletes each by reusing adminForceDeleteInstalledApp's
// forcedelete+verify-gone path. The whole loop runs loopback-local on the hub, so CI makes ONE cloud
// round-trip instead of N -- replacing the disarm's per-item reap loop and the post-restore
// --cleanup-only RM sweep. Hard-scoped to the prefix (never a real app); confirm:true required.
def adminPurgeE2eArtifacts(args) {
    requireConfirm(args)
    String prefix = (args?.prefix instanceof String && args.prefix.trim()) ? args.prefix.trim() : "BAT_E2E_"
    String raw = hubGet("/hub2/appsList", [:])
    if (!raw) return [success: false, error: "empty response from /hub2/appsList -- cannot enumerate to purge"]
    def parsed
    try { parsed = new groovy.json.JsonSlurper().parseText(raw) }
    catch (Exception e) { return [success: false, error: "unparseable /hub2/appsList: ${e.message}"] }
    def targets = []
    def recurse
    recurse = { Map node ->
        def d = node?.data ?: [:]
        if ((d.name instanceof String) && d.name.startsWith(prefix) && d.id != null) {
            targets << [id: d.id, name: d.name]
        }
        node?.children?.each { c -> recurse(c) }
    }
    (parsed?.apps ?: []).each { a -> recurse(a) }
    mcpAdminLog "Purging ${targets.size()} ${prefix}* installed-app instance(s) locally."
    def deleted = []
    def failed = []
    targets.each { t ->
        def r
        try { r = adminForceDeleteInstalledApp([id: t.id, confirm: true]) }
        catch (Exception e) { r = [success: false, error: e.message] }
        if (r?.success) { deleted << [id: t.id, name: t.name] }
        else { failed << [id: t.id, name: t.name, error: r?.error] }
    }
    // Variables are hub-GLOBAL, so the watchdog (any app) can enumerate + remove them via DSL --
    // local, no relay, no classic-wizard (the main server's wizard exists only for in-use/connector
    // safety, which is moot for a BAT_E2E_ purge that runs AFTER the referencing rules are gone).
    def varsDeleted = []
    def varsFailed = []
    try {
        def allVars = getAllGlobalVars() ?: [:]
        allVars.keySet().findAll { (it instanceof String) && it.startsWith(prefix) }.each { vn ->
            try {
                if (removeGlobalVariable(vn)) { varsDeleted << vn }
                else { varsFailed << [name: vn, error: "removeGlobalVariable returned false (in use or already gone)"] }
            } catch (Exception e) { varsFailed << [name: vn, error: e.message] }
        }
    } catch (Exception e) {
        varsFailed << [name: "*", error: "getAllGlobalVars failed: ${e.message}"]
    }
    mcpAdminLog "Purge complete: ${deleted.size()} app(s), ${varsDeleted.size()} variable(s) deleted; " +
                "${failed.size()} app + ${varsFailed.size()} var failure(s)."
    return [success: failed.isEmpty() && varsFailed.isEmpty(), prefix: prefix,
            deletedCount: deleted.size(), failedCount: failed.size(), deleted: deleted, failed: failed,
            variablesDeletedCount: varsDeleted.size(), variablesFailedCount: varsFailed.size(),
            variablesDeleted: varsDeleted, variablesFailed: varsFailed,
            note: "Virtual DEVICES are NOT purged here (they are child devices of the main app and the hub's admin device-delete endpoint is not yet mirrored into the watchdog); the post-restore --cleanup-only sweep still reaps BAT_E2E_ devices."]
}

// hub_set_app_disabled: toggle an installed app's disabled flag (the admin UI's red-X) via
// POST /installedapp/disable {id, disable} -- the documented Vue wire format (vue-hub2.min.js:
// `const e={id:this.appId,disable:!0};postJsonAndCallback(...)`). Remote-management aid for the
// test hub (e.g. parking the legacy v1 watchdog without deleting it). Verified via the
// /installedapp/json/<id> read-back: only an observed flag flip reports success.
def adminSetAppDisabled(args) {
    requireConfirm(args)
    def id = (args.appId != null) ? args.appId : args.id
    if (id == null || !id.toString().isInteger() || id.toString().toInteger() <= 0) {
        throw new IllegalArgumentException("appId must be a positive integer (got: '${id}')")
    }
    boolean disable = (args.disable == true || args.disable?.toString() == "true")
    mcpAdminLog "Setting installed app ${id} disabled=${disable} (/installedapp/disable)"
    def body = groovy.json.JsonOutput.toJson([id: id.toString().toInteger(), disable: disable])
    Map resp = hubPostJson("/installedapp/disable", body)
    Integer st = (resp?.status != null) ? (resp.status as Integer) : null
    if (st == null || st >= 400) {
        return [success: false, error: "POST /installedapp/disable returned status=${st ?: 'none'} for app ${id}.", appId: id]
    }
    // Read back the flag -- a 200 alone is not proof the flip landed.
    def check = hubGetStatus("/installedapp/json/${id}", [:])
    def observed = null
    try {
        def parsed = new groovy.json.JsonSlurper().parseText(check?.data?.toString() ?: "")
        if (parsed instanceof Map) observed = (parsed.disabled == true)
    } catch (Exception ignore) { observed = null }
    if (observed == disable) {
        return [success: true, appId: id, disabled: disable, message: "App ${id} disabled flag verified ${disable}."]
    }
    return [success: false, appId: id, disabled: observed,
            error: "POST accepted (HTTP ${st}) but the read-back shows disabled=${observed} (wanted ${disable})."]
}


// hub_get_metrics: probe-grade current metrics + the hub's own health alerts. Mirrors the main
// server's toolGetHubPerformance current block (/hub/advanced/* reads) and _healthAlertsFromHub2
// (/hub2/hubData), WITHOUT the CSV trend history (that stays main's). Exists so the e2e status probe
// reads hub health through THIS always-alive endpoint instead of 504ing against a busy main app.
def adminGetMetrics(args) {
    def current = [:]
    try { current.freeMemoryKB = hubGet("/hub/advanced/freeOSMemory", [:])?.trim() } catch (Exception e) { current.freeMemoryKB = "unavailable" }
    try { current.internalTempC = hubGet("/hub/advanced/internalTempCelsius", [:])?.trim() } catch (Exception e) { current.internalTempC = "unavailable" }
    try { current.databaseSizeKB = hubGet("/hub/advanced/databaseSize", [:])?.trim() } catch (Exception e) { current.databaseSizeKB = "unavailable" }
    try { current.uptimeSeconds = location.hub?.uptime } catch (Exception e) { current.uptimeSeconds = "unavailable" }
    def healthAlerts = null
    try {
        def raw = hubGet("/hub2/hubData", [:])
        def parsed = raw ? new groovy.json.JsonSlurper().parseText(raw) : null
        if (parsed instanceof Map) {
            def alerts = (parsed.alerts instanceof Map) ? ([:] + parsed.alerts) : [:]
            alerts.remove("platformUpdateAvailable"); alerts.remove("platformUpdateVersion")
            healthAlerts = [safeMode: parsed.safeMode == true,
                            active: alerts.findAll { k, v -> v == true }.collect { k, v -> k.toString() }.sort(),
                            details: alerts]
        }
    } catch (Exception e) { logDebug "adminGetMetrics hubData: ${e.message}" }
    return [current: current, healthAlerts: healthAlerts]
}

// hub_update_platform: apply the hub's pending platform update via the admin UI's own endpoints
// (/hub/cloud/updatePlatform fires the download+install; the hub reboots itself when the install
// completes). Test-hub maintenance tooling: keeps the test hub current without UI access, and the
// reboot legitimately resets the platform's per-app load counters. statusOnly:true polls
// /hub/cloud/checkUpdateStatus without confirm (read); the apply leg requires confirm=true.
def adminUpdatePlatform(args) {
    if (args?.statusOnly == true) {
        def st = null
        try { st = hubGet("/hub/cloud/checkUpdateStatus", [:]) } catch (Exception e) { return [success: false, error: "checkUpdateStatus failed: ${e.message}"] }
        return [success: true, status: st]
    }
    if (args?.confirm != true) {
        throw new IllegalArgumentException("SAFETY CHECK FAILED: set confirm=true to apply the platform update (downloads + installs + REBOOTS the hub). Use statusOnly:true to poll progress without confirm.")
    }
    def check = null
    try { check = hubGet("/hub/cloud/checkForUpdate", [:]) } catch (Exception e) { return [success: false, error: "checkForUpdate failed: ${e.message}"] }
    def resp = null
    try { resp = hubGet("/hub/cloud/updatePlatform", [:]) } catch (Exception e) { return [success: false, error: "updatePlatform failed: ${e.message}", checkForUpdate: check] }
    return [success: true, checkForUpdate: check, updateResponse: resp,
            note: "The hub downloads, installs, then reboots itself. Poll hub_update_platform(statusOnly:true) for progress; expect the endpoint to go dark during the reboot (~5-10 min total), then verify firmwareVersion via hub_get_info."]
}

// hub_get_memory_history: /hub/advanced/freeOSMemoryHistory parse, mirroring the main server's
// toolGetMemoryHistory row shape (timestamp, freeMemoryKB, cpuLoad5min, Java heap columns).
def adminGetMemoryHistory(args) {
    int limit = (args?.limit != null) ? (args.limit as int) : 60
    String raw = hubGet("/hub/advanced/freeOSMemoryHistory", [:])
    if (!raw) return [entries: [], summary: [message: "No memory history data available"]]
    def entries = []
    for (line in raw.trim().split("\n")) {
        def parts = line?.trim()?.split(",", -1)
        if (parts == null || parts.size() < 3) continue
        Integer memKB = null
        try { memKB = parts[1]?.trim() as Integer } catch (Exception e) { continue }
        def entry = [timestamp: parts[0]?.trim(), freeMemoryKB: memKB, cpuLoad5min: parts[2]?.trim()]
        if (parts.size() >= 6) {
            try { entry.totalJavaKB = parts[3]?.trim() as Integer } catch (Exception ignore) { }
            try { entry.freeJavaKB = parts[4]?.trim() as Integer } catch (Exception ignore) { }
            try { entry.directJavaKB = parts[5]?.trim() as Integer } catch (Exception ignore) { }
        }
        entries << entry
    }
    int total = entries.size()
    if (limit > 0 && total > limit) entries = entries.subList(total - limit, total)
    def mems = entries.collect { it.freeMemoryKB }.findAll { it != null }
    return [entries: entries,
            summary: [totalEntries: total,
                      currentMemoryKB: mems ? mems[-1] : null,
                      minMemoryKB: mems ? mems.min() : null,
                      maxMemoryKB: mems ? mems.max() : null]]
}

// hub_get_hub_logs: most-recent hub system log entries with a level filter. Mirrors the main
// server's /logs/past/json parse (JSON array of tab-delimited strings, oldest-first -> reversed)
// without the since/TZ machinery -- the probe wants "newest N errors/warnings", nothing more.
def adminGetHubLogs(args) {
    String level = args?.level?.toString()?.toLowerCase()
    int limit = (args?.limit != null) ? (args.limit as int) : 50
    String raw = hubGet("/logs/past/json", [:], 30)
    if (!raw) return [logs: [], count: 0, message: "No log data returned from hub"]
    def arr
    try { arr = new groovy.json.JsonSlurper().parseText(raw) } catch (Exception e) { return [logs: [], count: 0, error: "unparseable /logs/past/json: ${e.message}"] }
    if (!(arr instanceof List)) return [logs: [], count: 0, error: "unexpected log format"]
    def out = []
    for (entry in arr.reverse()) {
        def parts = entry?.toString()?.split("\t", -1)
        if (parts == null || parts.size() < 3) continue
        String entLevel = parts[1]?.toString()?.toLowerCase()
        if (level && entLevel != level) continue
        out << [name: parts[0], level: parts[1], message: parts.size() > 2 ? parts[2] : "",
                time: parts.size() > 3 ? parts[3] : "", type: parts.size() > 4 ? parts[4] : ""]
        if (out.size() >= limit) break
    }
    return [logs: out, count: out.size(), totalParsed: arr.size(), appliedFilters: [level: level, limit: limit]]
}

// hub_list_app_instances: every running app instance, flattened from /hub2/appsList with parentId --
// mirrors the main server's instances mapping (id/name/type/disabled/user/parentId). The full
// inventory the probe needs (RM rules, Basic Rules, Button Controllers/Rules, Visual Rules,
// watchdogs -- every app type), readable while the main app is busy.
def adminListAppInstances(args) {
    String raw = hubGet("/hub2/appsList", [:])
    if (!raw) return [success: false, error: "empty response from /hub2/appsList"]
    def parsed
    try { parsed = new groovy.json.JsonSlurper().parseText(raw) } catch (Exception e) { return [success: false, error: "unparseable /hub2/appsList: ${e.message}"] }
    def flat = []
    def recurse
    recurse = { Map node, parentId ->
        def d = node?.data ?: [:]
        flat << [id: d.id, name: d.name, type: d.type, disabled: d.disabled == true,
                 user: d.user == true, parentId: parentId,
                 childCount: node?.children?.size() ?: 0]
        node?.children?.each { c -> recurse(c, d.id) }
    }
    (parsed?.apps ?: []).each { a -> recurse(a, null) }
    return [apps: flat, count: flat.size()]
}

// hub_install_bundle: copied from toolInstallBundle + _firmwareAtLeast + _bundleResponseSucceeded
// (hubitat-mcp-server.groovy), incl. the NUMERIC firmware gate at 2.3.8.108 and the
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

// _firmwareAtLeast: copied VERBATIM from hubitat-mcp-server.groovy (numeric
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

// _bundleResponseSucceeded: copied from hubitat-mcp-server.groovy.
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

// hub_list_bundles: mirror of McpBundlesLib.toolListBundles (PR #247), adapted to hubGet. Lists the
// installed bundle CONTAINERS (id/name/namespace), distinct from Libraries Code. Read-only. No
// pagination -- the watchdog uses this internally (restorePackage cleanup + the disarm no-stale check)
// and for the deploy scripts by URL swap; the test hub never has enough bundles to need paging.
def adminListBundles(args) {
    def result = [:]
    try {
        def responseText = hubGet("/hub2/userBundles", [:])
        if (responseText) {
            try {
                def parsed = new groovy.json.JsonSlurper().parseText(responseText)
                if (parsed instanceof List) {
                    result.bundles = parsed.findAll { it instanceof Map }.collect { b ->
                        [id: b.id?.toString(), name: b.name, namespace: b.namespace, "private": (b["private"] == true)]
                    }
                    result.count = result.bundles.size()
                    result.source = "hub_api"
                } else {
                    result.bundles = []
                    result.count = 0
                    result.rawResponse = responseText?.take(2000)
                    result.source = "hub_api_raw"
                    result.note = "Response was not a JSON array."
                }
            } catch (Exception parseErr) {
                result.bundles = []
                result.count = 0
                result.rawResponse = responseText?.take(2000)
                result.source = "hub_api_raw"
                result.note = "Response was not JSON."
            }
        } else {
            result.bundles = []
            result.count = 0
            result.source = "unavailable"
            result.note = "Empty response from hub API"
        }
    } catch (Exception e) {
        log.warn "adminListBundles: ${e.message}"
        result.bundles = []
        result.count = 0
        result.source = "unavailable"
        result.note = "Hub internal API unavailable (${e.message})."
    }
    return result
}

// hub_delete_bundle: mirror of McpBundlesLib.toolDeleteBundle (PR #247), adapted to hubGet. Deletes a
// bundle CONTAINER by id (GET /bundle/delete/<id>, 302 on success), then re-lists to confirm it is
// gone (the 302 alone is not proof). confirm:true required. restorePackage uses this to remove a PR's
// leftover bundle so the restored hub carries only main's bundle(s).
def adminDeleteBundle(args) {
    requireConfirm(args)
    def rawId = args?.bundleId
    if (rawId == null || !rawId.toString().trim()) {
        throw new IllegalArgumentException("bundleId is required (the numeric id from hub_list_bundles).")
    }
    def bundleId = rawId.toString().trim()
    if (!(bundleId ==~ /\d+/)) {
        throw new IllegalArgumentException("bundleId must be a positive integer (got '${bundleId.take(40)}').")
    }
    def before = adminListBundles([:])
    def target = (before.bundles ?: []).find { it.id?.toString() == bundleId }
    if (before.source == "hub_api" && !target) {
        return [success: false, error: "No bundle with id ${bundleId} found on the hub.", bundleId: bundleId]
    }
    def bundleName = target?.name
    mcpAdminLog "Deleting bundle ${bundleId} (${bundleName ?: 'name unknown'})"
    try {
        hubGet("/bundle/delete/${bundleId}", [:])
    } catch (Exception e) {
        return [success: false, error: "Bundle delete request failed: ${e.message ?: e.toString()}", bundleId: bundleId]
    }
    def after = adminListBundles([:])
    if (after.source != "hub_api") {
        return [success: false, verified: false,
                error: "Delete request sent for bundle ${bundleId}, but removal could not be verified (list source=${after.source}).",
                bundleId: bundleId]
    }
    def stillThere = (after.bundles ?: []).any { it.id?.toString() == bundleId }
    if (stillThere) {
        return [success: false, error: "Bundle ${bundleId} is still present after the delete request.", bundleId: bundleId]
    }
    return [success: true, message: "Bundle ${bundleId}${bundleName ? " ('${bundleName}')" : ''} deleted.",
            bundleId: bundleId, bundleName: bundleName, verified: true]
}

// hub_get_info: condensed from toolGetHubInfo (hubitat-mcp-server.groovy), surfacing the
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

// hub_list_apps: copied from toolListHubApps (hubitat-mcp-server.groovy) for
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

// hub_list_libraries: copied from toolListLibraries (hubitat-mcp-server.groovy).
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

// hub_get_jobs: condensed from toolGetHubJobs (hubitat-mcp-server.groovy). Reads the
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

// hub_read_file: copied from toolReadFile (hubitat-mcp-server.groovy). downloadHubFile + chunk.
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

// hub_write_file: copied from toolWriteFile (hubitat-mcp-server.groovy). uploadHubFile + name check.
def adminWriteFile(args) {
    requireConfirm(args)
    if (!args.fileName) throw new IllegalArgumentException("fileName is required")
    if (args.content == null) throw new IllegalArgumentException("content is required")
    if (!(args.fileName ==~ /^[A-Za-z0-9][A-Za-z0-9._-]*$/)) {
        throw new IllegalArgumentException("Invalid file name '${args.fileName}'. Only letters, numbers, hyphens, underscores, and periods; cannot start with a period.")
    }
    try {
        uploadHubFile(args.fileName, args.content.getBytes("UTF-8"))
        // Accelerate the dead-man: kick a one-shot checkDeadman ~2s after CI writes the armed flag,
        // instead of waiting up to ~60s for the next runEvery1Minute tick (the single biggest avoidable
        // cost in the disarm/restore step). A DEDICATED handler (deadmanKick, NOT "checkDeadman") means
        // scheduling it can never overwrite the periodic dead-man job, so the brick/GitHub-down floor
        // stays intact; checkDeadman is idempotent (restoreFor stamp), so an arm-write kick is a harmless
        // no-op and only a disarm write actually restores -- ~58s sooner.
        if (args.fileName == (flagFileName ?: "e2e-deadman-v2.json")) runIn(2, "deadmanKick")
        return [success: true, message: "File '${args.fileName}' written.", fileName: args.fileName, contentLength: args.content.length()]
    } catch (Exception e) {
        log.error "adminWriteFile: ${e.message}"
        return [success: false, error: "Failed to write file '${args.fileName}': ${e.message}"]
    }
}

// hub_create_backup: copied from toolCreateHubBackup (hubitat-mcp-server.groovy).
// GET /hub/backupDB?fileName=latest. Records state.lastBackupTimestamp.
// light:true = trigger the backup WITHOUT downloading the multi-MB .lzf body through this app:
// fire /hub/backupDB asynchronously (the async client truncates the body; the hub still creates
// the backup) and confirm via /hub/backup/statusJson instead of the binary response. The full
// synchronous download slurps the whole backup file through the calling app's execution -- a
// one-off load spike implicated in tripping the platform's per-app limiter ~13 min later.
def adminCreateBackup(args) {
    if (!args.confirm) throw new IllegalArgumentException("You must set confirm=true to create a backup.")
    if (args.light == true) {
        mcpAdminLog "Triggering hub backup (light mode -- async, body discarded)..."
        try {
            asynchttpGet("backupFired", [uri: "http://127.0.0.1:8080", path: "/hub/backupDB", query: [fileName: "latest"], timeout: 300])
            def backupTime = now()
            state.lastBackupTimestamp = backupTime
            def status = null
            try { status = hubGet("/hub/backup/statusJson", [:]) } catch (Exception ignored) { }
            return [success: true, mode: "light",
                    message: "Hub backup triggered asynchronously (body not downloaded). Poll /hub/backup/statusJson via hub_get_metrics or re-read statusJson for completion.",
                    statusJson: status?.take(300), backupTimestampEpoch: backupTime]
        } catch (Exception e) {
            log.error "adminCreateBackup(light): ${e.message}"
            return [success: false, error: "Light backup trigger failed: ${e.message}"]
        }
    }
    mcpAdminLog "Creating hub backup..."
    try {
        // hubGet swallows its own transport exception (returns null), so this try/catch alone can't see
        // a failed backup; /hub/backupDB also returns no useful body on success, so we can't hard-fail
        // on null without false-failing. Report it honestly as a best-effort snapshot -- this backup is
        // a DEFENSIVE snapshot, NOT a restore prerequisite (the real restore floor is the source cache).
        def resp = hubGet("/hub/backupDB", [fileName: "latest"])
        def backupTime = now()
        state.lastBackupTimestamp = backupTime
        return [success: true, confirmed: (resp != null),
                message: (resp != null) ? "Hub backup created" : "Hub backup triggered (no confirmation body; best-effort snapshot)",
                backupTimestampEpoch: backupTime]
    } catch (Exception e) {
        log.error "adminCreateBackup: ${e.message}"
        return [success: false, error: "Backup failed: ${e.message}"]
    }
}

// asynchttpGet completion sink for the light-mode backup trigger: the response body (the .lzf)
// is deliberately ignored -- the point of light mode is that this app never holds it.
def backupFired(response, data) {
    try { mcpAdminLog "light backup async response: status=${response?.status}" } catch (Exception ignored) { }
}

// hub_manage_variables: thin action-dispatch gateway exposing hub_get_variable / hub_set_variable
// using the sandbox global-var API (getGlobalVar / setGlobalVar). NOTE: this is a convenience
// read/write with a flat {action,name,value} shape; the e2e LEASE runs over $MCP_URL (the main server)
// and uses that server's nested {tool,args} shape -- it does NOT go through this watchdog tool. Copied
// from toolGetVariable / toolSetVariable (hubitat-mcp-server.groovy).
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
// fetchExternal: copies _fetchSourceFromUrl (hubitat-mcp-server.groovy) +
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

// _httpFetchUrl: copied VERBATIM from hubitat-mcp-server.groovy.
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
        [name: "hub_force_delete_app", description: "Force-delete an INSTALLED-APP instance (e.g. an RM rule) via /installedapp/forcedelete/<id>/quiet. confirm:true required.",
         inputSchema: [type: "object", properties: [id: [type: "string"], confirm: [type: "boolean"]], required: ["id", "confirm"]]],
        [name: "hub_purge_e2e_artifacts", description: "One-call LOCAL sweep of leftover test fixtures: force-delete every installed-app instance whose name starts with prefix (default BAT_E2E_) AND removeGlobalVariable every matching hub variable, all loopback-local on the hub so CI pays ONE cloud round-trip instead of N. Virtual devices are NOT covered (child devices of the main app). Returns per-class {deleted/failed} counts. confirm:true required.",
         inputSchema: [type: "object", properties: [prefix: [type: "string", description: "Name prefix to purge; default BAT_E2E_."], confirm: [type: "boolean"]], required: ["confirm"]]],
        [name: "hub_set_app_disabled", description: "Toggle an installed app's disabled flag (the admin UI red-X) via POST /installedapp/disable; verified by read-back. confirm:true required.",
         inputSchema: [type: "object", properties: [appId: [type: "string"], disable: [type: "boolean"], confirm: [type: "boolean"]], required: ["appId", "disable", "confirm"]]],
        [name: "hub_get_metrics", description: "Current hub metrics (free memory, temp, DB size, uptime) + the hub's own health alerts. Read-only."],
        [name: "hub_update_platform", description: "Apply the hub's pending platform update (downloads + installs + REBOOTS the hub; requires confirm=true). statusOnly=true polls update progress without confirm.", inputSchema: [type: "object", properties: [confirm: [type: "boolean", description: "Must be true to apply (the hub reboots itself)."], statusOnly: [type: "boolean", description: "Poll /hub/cloud/checkUpdateStatus only; no confirm needed."]]]],
        [name: "hub_get_memory_history", description: "Free-memory / CPU-load history rows from the hub. Args: limit (default 60). Read-only.",
         inputSchema: [type: "object", properties: [limit: [type: "integer"]]]],
        [name: "hub_get_hub_logs", description: "Most-recent hub system log entries. Args: level (error/warn/info), limit (default 50). Read-only.",
         inputSchema: [type: "object", properties: [level: [type: "string"], limit: [type: "integer"]]]],
        [name: "hub_list_app_instances", description: "Every running app INSTANCE (flattened /hub2/appsList with parentId) -- the full app inventory. DISTINCT from hub_list_apps (Apps Code CLASSES, which resolve_class_id depends on). Read-only."],
        [name: "hub_install_bundle", description: "Install a code bundle .zip from a URL the hub fetches itself (HPM-style). confirm:true required.",
         inputSchema: [type: "object", properties: [importUrl: [type: "string"], primary: [type: "boolean"], confirm: [type: "boolean"]], required: ["importUrl", "confirm"]]],
        [name: "hub_list_bundles", description: "List installed code bundle containers (id/name/namespace). Read-only.",
         inputSchema: [type: "object", properties: [:]]],
        [name: "hub_delete_bundle", description: "Delete a code bundle container by id (verified by re-list). confirm:true required.",
         inputSchema: [type: "object", properties: [bundleId: [type: "string"], confirm: [type: "boolean"]], required: ["bundleId", "confirm"]]],
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

// Status-aware loopback GET. Unlike hubGet (text body, swallows every exception -> null), this
// returns [status, location, data] and treats a 3xx the way the hub editor does: endpoints like
// /installedapp/forcedelete answer SUCCESS with a 302 redirect, which Hubitat's httpGet THROWS on
// when followRedirects:false. Mirrors hubitat-mcp-server.groovy _hubRequest(handle3xx) -- the
// proven sandbox-safe e.response.status capture. status stays null only on a real transport failure.
Map hubGetStatus(String path, Map query, int timeoutSec = 30) {
    def params = [uri: "http://127.0.0.1:8080", path: path, query: query,
                  textParser: true, ignoreSSLIssues: true, followRedirects: false, timeout: timeoutSec]
    def cookie = secCookie()
    if (cookie) params.headers = [Cookie: cookie]
    Map out = [status: null, location: null, data: null]
    try {
        httpGet(params) { resp -> out = [status: resp.status, location: resp.headers?."Location"?.toString(), data: respText(resp)] }
    } catch (Exception e) {
        def resp = null
        try { resp = e.response } catch (Exception ignore) { resp = null }
        Integer st = null
        try { st = resp?.status as Integer } catch (Exception ignore) { st = null }
        if (st != null) {
            def loc = null
            try { loc = resp.headers?."Location"?.toString() } catch (Exception ignore) { loc = null }
            out = [status: st, location: loc, data: null]
        } else {
            log.error "hubGetStatus ${path}: ${e.message}"
        }
    }
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
