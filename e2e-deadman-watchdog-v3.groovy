/**
 * Manual administration and package deployment for the E2E hub. Never restores a package on its own;
 * its only automatic action is rebooting a hub whose web stack is wedged.
 * Callers must hold the exclusive hub lease before deploying.
 */
definition(
    name: "E2E Dead-Man Watchdog v3",
    namespace: "mcp",
    author: "kingpanther13",
    description: "Manual hub administration and MCP package deployment with live progress. E2E test hub only.",
    category: "Utility",
    iconUrl: "https://raw.githubusercontent.com/hubitat/HubitatPublic/master/app-dev/icon.png",
    iconX2Url: "https://raw.githubusercontent.com/hubitat/HubitatPublic/master/app-dev/icon.png",
    oauth: [displayName: "E2E Dead-Man Watchdog v3", displayLink: ""],
    singleInstance: true
)

preferences {
    page(name: "mainPage", title: "E2E Dead-Man Watchdog v3", install: true, uninstall: true) {
        section("Watchdog") {
            input "debugLogging", "bool", title: "Debug logging", defaultValue: false, required: false
            input "autoRebootOnWedge", "bool", title: "Auto-reboot when the hub's loopback HTTP stays dead for 4+ minutes (the web stack is wedged)", defaultValue: true, required: false
        }
        section("MCP Deploy Endpoint") {
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

mappings {
    path("/mcp") {
        action: [
            GET: "handleMcpGet",
            POST: "handleMcpRequest"
        ]
    }
}

@groovy.transform.Field static final Object PACKAGE_DEPLOY_LOCK = new Object()
@groovy.transform.Field static final Map MANUAL_WRITE = [:]
@groovy.transform.Field static final Object PURGE_CLAIM_LOCK = new Object()
@groovy.transform.Field static final Object LOOPBACK_LOCK = new Object()
// Wedge counters live in memory, not atomicState: they are touched on every loopback call and
// every health tick, and a reboot or code load should start them from zero anyway.
@groovy.transform.Field static final Map LOOPBACK = [failStreak: 0, lastOkAt: null, streakStartedAt: null]
@groovy.transform.Field static final int WEDGE_STREAK_MIN = 8
@groovy.transform.Field static final Object REBOOT_LOCK = new Object()
// Longer than any single blocking call the worker makes (the 420s app save). A worker silent this
// long is treated as dead; if it is only slow, packageRequireHold stops it before its next write.
@groovy.transform.Field static final long WORKER_STALE_MS = 900000L
// Live deployment progress, kept in memory the way HPM keeps its status message: only the hold is
// persisted. A hub restart or code load empties it, which is how a dead deployment is recognised.
@groovy.transform.Field static final Map PACKAGE_PROGRESS = [:]
@groovy.transform.Field static final Map HEALTH_TICK = [:]

def installed() { initialize() }
// No unschedule(): it would drop a pending deployment verification poll and strand the hold.
def updated() { initialize() }
def initialize() {
    if (!state.accessToken) {
        try { createAccessToken() }
        catch (Exception e) { log.warn "createAccessToken() failed (${e.message}). Enable OAuth for the v3 code class, then save this app to create its endpoint." }
    }
    ensureHealthTick()
}

// A code update does not run initialize(), so the first request after each class load arms the tick too.
void ensureHealthTick() {
    if (HEALTH_TICK.armed) return
    try {
        runEvery1Minute("checkHubHealth")
        HEALTH_TICK.armed = true
    } catch (Exception e) { log.error "Could not schedule the hub health tick: ${e.message}" }
}

// A wedged web stack cannot serve this app's own /mcp endpoint, so no remote caller can request the
// reboot; only this on-hub tick can. The probe keeps the wedge counters live on an idle hub.
def checkHubHealth() {
    probeLoopbackAlive()
    maybeAutoRebootWedgedHub()
}

// Rate-limited to one attempt per 30 minutes so a reboot that does not clear the wedge cannot
// become a boot loop.
private boolean maybeAutoRebootWedgedHub() {
    if (settings?.autoRebootOnWedge == false) return false
    if (!hubLooksWedged()) return false
    Long downUntil = null
    try { downUntil = atomicState.expectedDownUntil as Long } catch (Exception ignore) { downUntil = null }
    if (downUntil != null && now() < downUntil) {
        log.warn "E2E Dead-Man Watchdog v3: loopback is down but a deliberate reboot/platform update is in progress for another ${((downUntil - now()) / 1000) as long}s -- not rebooting into it."
        return false
    }
    // Never act on accumulated counters alone: a live probe must also fail right now. Outside the
    // monitor on purpose -- a 10s probe under it would hold every queued tick.
    if (probeLoopbackAlive()) {
        log.warn "E2E Dead-Man Watchdog v3: the wedge counters were stale -- a live probe answered, so the hub is healthy. NOT rebooting."
        return false
    }
    long nowMs = now()
    synchronized (REBOOT_LOCK) {
        Long lastReboot = null
        try { lastReboot = atomicState.lastAutoRebootAt as Long } catch (Exception ignore) { lastReboot = null }
        if (lastReboot != null && (nowMs - lastReboot) < 1800000L) {
            log.warn "E2E Dead-Man Watchdog v3: hub still looks wedged but an auto-reboot fired ${((nowMs - lastReboot) / 1000) as long}s ago -- not rebooting again within 30 minutes. The hub may need a physical power cycle."
            return false
        }
        // Re-validate under the lock: another tick's probe may have answered, or an operator may
        // have started a reboot or platform update, since the checks above.
        if (!hubLooksWedged()) {
            log.warn "E2E Dead-Man Watchdog v3: the hub recovered while the reboot slot was being claimed -- NOT rebooting."
            return false
        }
        try { downUntil = atomicState.expectedDownUntil as Long } catch (Exception ignore) { downUntil = null }
        if (downUntil != null && nowMs < downUntil) {
            log.warn "E2E Dead-Man Watchdog v3: a deliberate reboot/platform update began while the reboot slot was being claimed -- not rebooting into it."
            return false
        }
        // Claim under the lock so an overlapping tick cannot also reach the POST; the POST itself
        // runs outside it so a hung request holds nothing but its own thread.
        atomicState.lastAutoRebootAt = nowMs
        Long stampBack = null
        try { stampBack = atomicState.lastAutoRebootAt as Long } catch (Exception ignore) { stampBack = null }
        if (stampBack != nowMs) {
            log.error "E2E Dead-Man Watchdog v3: the auto-reboot rate-limit stamp did NOT persist (state holds ${stampBack}); rebooting anyway, but a following tick could fire a second reboot."
        }
    }
    // A loopback call already in flight can answer in the gap; never reboot a hub that just came back.
    if (!hubLooksWedged()) {
        log.warn "E2E Dead-Man Watchdog v3: the hub answered while the reboot was being prepared -- NOT rebooting, and the rate-limit slot is given back."
        try { atomicState.lastAutoRebootAt = null } catch (Exception ignore) { }
        return false
    }
    log.error "E2E Dead-Man Watchdog v3: hub loopback HTTP has been dead for at least ${loopbackState().failStreak} consecutive calls with no success in over 4 minutes -- the web stack is wedged and nothing in-process recovers from that. AUTO-REBOOTING."
    def r = adminRebootHub([confirm: true])
    if (!r?.success) {
        log.error "E2E Dead-Man Watchdog v3: auto-reboot POST did not confirm (${r?.error})."
        // An ambiguous POST may have landed, so its stamp keeps the 30-minute limit; an answered
        // rejection proves nothing rebooted, so the slot is freed for a real retry.
        if (r?.ambiguous != true) {
            try { atomicState.lastAutoRebootAt = null } catch (Exception ignore) { }
        }
    }
    return true
}

def handleMcpGet() {
    return render(status: 405, contentType: "application/json",
                  data: groovy.json.JsonOutput.toJson(jsonRpcError(null, -32600,
                      "This MCP endpoint is request-response only (POST). SSE/GET streaming is not supported.")))
}

def handleMcpRequest() {
    ensureHealthTick()
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

    if (response == null || (response instanceof List && response.isEmpty())) {
        return render(status: 202, contentType: "application/json", data: "")
    }

    def jsonResponse = groovy.json.JsonOutput.toJson(response)
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
        serverInfo: [name: "e2e-deadman-watchdog-v3", version: "3"],
        instructions: "Manual administration and package deployment with live progress. Reserve the E2E hub before changes. Restoration requires an explicit request; the only automatic action is a reboot when the hub's web stack has been wedged for 4+ minutes."
    ])
}

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
        return jsonRpcResult(msg.id, [content: [[type: "text", text: jsonText]], isError: result?.success == false])
    } catch (IllegalArgumentException e) {
        return jsonRpcError(msg.id, -32602, "Invalid params: ${e.message}")
    } catch (Exception e) {
        log.error "Tool execution error in ${toolName}: ${e.message}"
        return jsonRpcResult(msg.id, [content: [[type: "text", text: "Tool error: ${e.message}"]], isError: true])
    }
}

def jsonRpcResult(id, result) {
    return [jsonrpc: "2.0", id: id, result: result]
}
def jsonRpcError(id, code, message, data = null) {
    def error = [jsonrpc: "2.0", id: id, error: [code: code, message: message]]
    if (data) error.error.data = data
    return error
}

def executeAdminTool(String toolName, Map args) {
    // Status makes no hub HTTP call, so it stays readable when Hub Security auth is broken.
    if (toolName == "hub_get_package_deployment") return adminGetPackageDeployment(args)
    if (settings?.hubSecurityEnabled == true && secCookie() == null)
        return [success: false, error: "Hub Security authentication failed; check v3 settings"]
    if (toolName == "hub_update_package") return adminUpdatePackage(args)
    if (toolName == "hub_set_package_deployment") return adminSetPackageDeployment(args)
    if (!manualToolWrites(toolName, args)) return executeManualTool(toolName, args)
    // A forced reboot is the only in-band recovery from a wedged hub, so it never waits behind a
    // hold or another write.
    if (toolName == "hub_reboot" && args.force == true) return executeManualTool(toolName, args)
    boolean repeatedPurge = false
    synchronized (PACKAGE_DEPLOY_LOCK) {
        def deployment = packageJob()
        if (deployment?.hold == true) return packageHeldRefusal(deployment, "manual writes are blocked")
        if (MANUAL_WRITE.isEmpty()) {
            MANUAL_WRITE.tool = toolName
        } else if (toolName == "hub_purge_e2e_artifacts" && MANUAL_WRITE.tool == toolName) {
            // The purge's own single-flight latch answers a repeat (in-flight, busy or cached).
            repeatedPurge = true
        } else {
            return manualWriteBusyRefusal("nothing was submitted")
        }
    }
    if (repeatedPurge) return executeManualTool(toolName, args)
    try {
        return executeManualTool(toolName, args)
    } finally {
        synchronized (PACKAGE_DEPLOY_LOCK) { MANUAL_WRITE.clear() }
    }
}

Map packageHeldRefusal(deployment, String consequence) {
    return [success: false, heldRequestId: deployment?.requestId, phase: deployment?.phase,
            error: "Package deployment ${deployment?.requestId} is held (phase ${deployment?.phase}); ${consequence}",
            note: "Poll hub_get_package_deployment with that requestId. Release the hold with hub_set_package_deployment: endpointVerified:true to complete, or abandon:true plus writesSettled:true to give it up."]
}

Map manualWriteBusyRefusal(String consequence) {
    return [success: false, busy: true, activeTool: MANUAL_WRITE.tool,
            error: "A manual write (${MANUAL_WRITE.tool}) is still running; ${consequence}",
            note: "Nothing was changed by this call. Retry after that write returns."]
}

boolean manualToolWrites(String toolName, Map args) {
    if (toolName == "hub_get_source") return args.noSave != true
    if (toolName == "hub_update_platform") return args.statusOnly != true
    if (toolName == "hub_manage_variables") return args.action in ["set", "hub_set_variable"]
    return toolName in ["hub_update_app", "hub_set_mcp_developer_mode", "hub_create_library",
        "hub_update_library", "hub_delete_item", "hub_force_delete_app", "hub_purge_e2e_artifacts",
        "hub_reboot", "hub_set_app_disabled", "hub_install_bundle", "hub_delete_bundle",
        "hub_write_file", "hub_create_backup"]
}

String packageRepository(value) {
    String base = value == null ? "https://raw.githubusercontent.com/kingpanther13/Hubitat-local-MCP-server" : value.toString()
    if (!(base ==~ /https:\/\/raw\.githubusercontent\.com\/[A-Za-z0-9_-]+\/[A-Za-z0-9_.-]+/) ||
        base.endsWith("/.") || base.endsWith("/.."))
        throw new IllegalArgumentException("Package repository must be https://raw.githubusercontent.com/<owner>/<repository>")
    return base
}

def getAdminToolDefinitions() { getManualToolDefinitions() + getPackageToolDefinitions() }

def adminUpdatePackage(Map args) {
    requireConfirm(args)
    String requestId = args.requestId?.toString()
    String ref = args.ref?.toString()
    if (!(requestId ==~ /[A-Za-z0-9_-]{1,100}/) || !(ref ==~ /[0-9a-f]{40}/))
        throw new IllegalArgumentException("requestId and an immutable 40-character commit SHA in ref are required")
    if (!(args.libraries instanceof List) || args.libraries.isEmpty() || args.libraries.size() > 100 ||
        args.libraries.any { !(it instanceof Map) || !(it.name ==~ /[A-Za-z0-9_]+/) || !(it.sha256 ==~ /[0-9a-f]{64}/) })
        throw new IllegalArgumentException("libraries must contain the expected name and SHA-256 for every package library")
    def libraries = args.libraries.collect { [name: it.name.toString(), sha256: it.sha256.toString()] }.sort { it.name }
    if (libraries*.name.unique().size() != libraries.size())
        throw new IllegalArgumentException("Duplicate library names are not allowed")
    String baseUrl = packageRepository(args.baseUrl)
    String bundleBaseUrl = packageRepository(args.bundleBaseUrl)
    String binding = packageSourceHash(groovy.json.JsonOutput.toJson([ref: ref, libraries: libraries,
        baseUrl: baseUrl, bundleBaseUrl: bundleBaseUrl]))
    def prior = atomicState.packageDeployment
    if (prior?.requestId == requestId) {
        if (prior.binding != binding) return [success: false, error: "requestId is already bound to different inputs"]
        return adminGetPackageDeployment([requestId: requestId])
    }
    Map job
    synchronized (PACKAGE_DEPLOY_LOCK) {
        if (!MANUAL_WRITE.isEmpty()) return manualWriteBusyRefusal("no package deployment was scheduled")
        def current = packageJob()
        if (current?.requestId == requestId) {
            if (current.binding != binding) return [success: false, error: "requestId is already bound to different inputs"]
            return packageStatus(current)
        }
        if (current?.hold == true) return packageHeldRefusal(current, "nothing was scheduled")
        job = [requestId: requestId, ref: ref, binding: binding, baseUrl: baseUrl, bundleBaseUrl: bundleBaseUrl,
               libraries: libraries, hold: true, phase: "queued", startedAt: now()]
        atomicState.packageDeployment = job
        def back = atomicState.packageDeployment
        if (back?.requestId != requestId || back.hold != true)
            return [success: false, error: "Could not persist deployment safety hold; nothing was scheduled"]
        job = job + [stageStartedAt: now(), updatedAt: now(), history: [], workerActive: false]
        PACKAGE_PROGRESS.job = [:] + job
    }
    try {
        runIn(1, "runWatchdogPackageDeploy", [data: [requestId: requestId]])
    } catch (Exception e) {
        log.error "hub_update_package ${requestId}: could not schedule the worker: ${e.message}"
        packageStage(job, "stopped", "", "Could not schedule deployment (${e.message}). Safety hold retained.")
    }
    return adminGetPackageDeployment([requestId: requestId])
}

def adminGetPackageDeployment(Map args) {
    def job = packageJob()
    if (!job || job.requestId != args.requestId?.toString())
        return [success: false, error: "No deployment with this requestId",
                note: "hub_get_info.packageDeployment reports this watchdog's latest operation."]
    return packageStatus(job)
}

// The persisted record overlaid with live progress. A held record with no live progress and no
// resting phase means the watchdog restarted mid-deployment: nothing is running any more.
Map packageJob() {
    def stored = atomicState.packageDeployment
    if (!(stored instanceof Map)) return null
    Map live = null
    synchronized (PACKAGE_DEPLOY_LOCK) {
        def current = PACKAGE_PROGRESS.job
        if (current != null && current.requestId == stored.requestId) live = [:] + current
    }
    if (live != null) return ([:] + stored) + live
    if (stored.hold == true && !(stored.phase in ["stopped", "awaiting_verification"]))
        return ([:] + stored) + [phase: "interrupted", workerActive: false,
            error: "The watchdog restarted during this deployment (last recorded phase ${stored.phase}); nothing is running and nothing was resumed. Safety hold retained.".toString()]
    // Liveness is memory-only, whatever an older record stored.
    return ([:] + stored) + [workerActive: false]
}

void packagePublish(Map job) {
    synchronized (PACKAGE_DEPLOY_LOCK) { PACKAGE_PROGRESS.job = [:] + job }
}

Map packageStatus(Map job) {
    long started = (job.startedAt ?: now()) as long
    return [success: !(job.phase in ["stopped", "abandoned", "interrupted"]), requestId: job.requestId, ref: job.ref,
        phase: job.phase, status: job.phase, component: job.component, hold: job.hold,
        startedAt: job.startedAt, updatedAt: job.updatedAt, elapsedMs: now() - started,
        stageElapsedMs: now() - ((job.stageStartedAt ?: started) as long), workerActive: job.workerActive == true,
        workerStale: packageWorkerStale(job), detail: job.detail, error: job.error, history: job.history ?: []]
}

// A worker that hangs without a restart keeps its in-memory claim; only silence tells that apart
// from a slow worker.
boolean packageWorkerStale(Map job) {
    return job.workerActive == true && job.updatedAt != null && (now() - (job.updatedAt as long)) > WORKER_STALE_MS
}

def adminSetPackageDeployment(Map args) {
    requireConfirm(args)
    if (!args.requestId) throw new IllegalArgumentException("requestId is required to release a package deployment")
    boolean abandon = args.abandon == true
    Map job
    synchronized (PACKAGE_DEPLOY_LOCK) {
        job = packageJob()
        if (job?.requestId != args.requestId?.toString())
            return [success: false, error: "No deployment with this requestId"]
        if (job.hold != true) return packageStatus(job)
        if (job.workerActive == true && !(abandon && packageWorkerStale(job)))
            return [success: false, requestId: job.requestId, phase: job.phase,
                    error: "A worker or another release is still active for this operation",
                    note: "Poll hub_get_package_deployment. A worker silent for ${(WORKER_STALE_MS / 60000) as long} minutes reports workerStale:true and can then be abandoned."]
        if (abandon ? args.writesSettled != true : args.endpointVerified != true)
            return [success: false, error: "Verify original endpoints for completion, or explicitly confirm writesSettled before abandoning a deployment for repair"]
        if (!abandon && job.phase != "awaiting_verification")
            return [success: false, requestId: job.requestId, phase: job.phase,
                    error: "The operation is not ready for completion; only abandon:true with writesSettled:true can release it in this phase"]
        // The in-memory claim keeps the worker and a second release out while the recheck runs.
        job.workerActive = true
        job.updatedAt = now()
        PACKAGE_PROGRESS.job = [:] + job
    }
    String failure = "Release was interrupted"
    try {
        failure = abandon ? null : packageInstallMismatch(job)
    } catch (Exception e) {
        log.error "hub_set_package_deployment ${job.requestId}: recheck failed: ${e.message}"
        failure = "Could not recheck installed sources (${e.message})"
    } finally {
        synchronized (PACKAGE_DEPLOY_LOCK) {
            String restPhase = job.phase
            String restComponent = job.component ?: ""
            job.workerActive = false
            job.hold = failure != null
            try {
                // A failed recheck keeps the phase, so completion can be retried once the hub reads cleanly.
                if (failure != null) packageStage(job, restPhase, restComponent, "${failure}; safety hold retained")
                else packageStage(job, abandon ? "abandoned" : "complete")
            } catch (Exception e) {
                // The record still holds: put memory back to match it, without this release's claim.
                job.phase = restPhase
                job.component = restComponent
                job.hold = true
                job.error = "Could not persist the release (${e.message}); safety hold retained".toString()
                packagePublish(job)
                throw e
            }
        }
    }
    def status = adminGetPackageDeployment(args)
    return failure == null ? status : status + [success: false]
}

// Null when every expected library and app is installed as deployed, else the first difference.
String packageInstallMismatch(Map job) {
    def snapshot = packageLibrarySnapshot(job)
    if (!snapshot.readable || !snapshot.matches) return snapshot.reason
    for (def target : (job.apps ?: [])) {
        String reason = packageAppMismatch(target)
        if (reason != null) return reason
    }
    return null
}

def runWatchdogPackageDeploy(Map data) {
    Map job
    synchronized (PACKAGE_DEPLOY_LOCK) {
        // No live progress means this class never admitted the operation (a restart): never resume.
        def live = PACKAGE_PROGRESS.job
        if (live?.requestId != data.requestId?.toString() || live.hold != true || live.workerActive == true ||
            live.phase in ["complete", "abandoned", "stopped", "awaiting_verification"]) return
        job = [:] + live
        job.workerActive = true
        PACKAGE_PROGRESS.job = [:] + job
    }
    try {
        if (job.phase == "queued") {
            packageStage(job, "preflight")
            prepareWatchdogPackage(job)
            def baseline = packageLibrarySnapshot(job)
            if (!baseline.readable) throw new IllegalStateException("Cannot read the complete library baseline (${baseline.reason}); bundle was not submitted")
            if (!baseline.matches) {
                packageStage(job, "installing_bundle", "MCP libraries")
                job.verifyUntil = now() + 600000L
                packageRequireHold(job)
                def bundle = adminInstallBundle([importUrl: job.bundleUrl, confirm: true])
                // Only an explicit rejection stops here. A lost or unreadable response may still have
                // installed, so it goes to hash verification rather than a replay.
                def rejection = _parseJsonBody(bundle?.rawResponse)
                if (bundle?.success != true &&
                    (rejection == false || (rejection instanceof Map && rejection.success == false)))
                    throw new IllegalStateException("Bundle install rejected: ${bundle.rawResponse}")
                if (bundle?.success != true) job.bundleNote = "bundle install was not confirmed (${bundle?.error ?: 'no response'})".toString()
            }
            job.verifyUntil = now() + 600000L
            packageStage(job, "verifying_libraries", "MCP libraries")
        }
        if (job.phase in ["installing_bundle", "verifying_libraries"]) {
            def snapshot = packageLibrarySnapshot(job)
            if (!snapshot.readable || !snapshot.matches) {
                packageWait(job, "verifying_libraries", "MCP libraries", [snapshot.reason, job.bundleNote].findAll().join("; "))
                return
            }
            job.appIndex = 0
            packageStage(job, "next_app")
        }
        while (job.appIndex < job.apps.size()) {
            def target = job.apps[job.appIndex as int]
            if (job.phase == "next_app") {
                packageStage(job, "downloading_app", target.name)
                String source = fetchExternal(target.url)
                if (packageSourceHash(source) != target.sha256) throw new IllegalStateException("Pinned app source changed")
                def before = _parseJsonBody(hubGet("/app/ajax/code", [id: target.id]))
                if (!(before instanceof Map) || !before.version?.toString()?.isLong()) throw new IllegalStateException("Could not read app code version")
                target.beforeVersion = before.version.toString().toLong()
                packageStage(job, "updating_app", target.name)
                job.verifyUntil = now() + 600000L
                packageRequireHold(job)
                def response = hubPostForm("/app/ajax/update", [id: target.id, version: before.version, source: source])
                def result = _parseJsonBody(response?.data)
                if (result instanceof Map && result.status == "error")
                    throw new IllegalStateException("App compile/save rejected: ${result.errorMessage ?: 'no diagnostic'}")
                // Any other response is uncertain: the save may still land, so it is verified by
                // reading the source back, never replayed.
                target.saveStatus = response?.status
                job.verifyUntil = now() + 600000L
                packageStage(job, "verifying_app", target.name)
            }
            if (!(job.phase in ["updating_app", "verifying_app"]))
                throw new IllegalStateException("Interrupted before save; inspect operation before retrying")
            String mismatch = packageAppMismatch(target)
            if (mismatch != null) {
                packageWait(job, "verifying_app", target.name, "${mismatch}; save returned HTTP ${target.saveStatus ?: 'no status'}")
                return
            }
            job.appIndex = (job.appIndex as int) + 1
            packageStage(job, "next_app")
        }
        packageStage(job, "awaiting_verification", "Original MCP and watchdog endpoints")
    } catch (Exception e) {
        log.error "Package deployment ${job.requestId} stopped at ${job.phase}: ${e.message}"
        if (!packageSuperseded(job) && !packageHoldLost(job))
            packageStage(job, "stopped", job.component?.toString() ?: "", e.message?.take(1000) ?: "Deployment failed")
    } finally {
        if (!packageSuperseded(job)) {
            job.workerActive = false
            packagePublish(job)
        }
    }
}

// A worker silent past WORKER_STALE_MS can be abandoned by the operator. If it was only slow, it
// must not write again or revive the released hold.
boolean packageSuperseded(Map job) {
    synchronized (PACKAGE_DEPLOY_LOCK) {
        def live = PACKAGE_PROGRESS.job
        return live == null || live.requestId != job.requestId || live.phase == "abandoned"
    }
}

// The same question against the persisted hold, for a worker that outlived a code load and so
// sees only its own class's memory.
boolean packageHoldLost(Map job) {
    def stored = atomicState.packageDeployment
    return stored?.requestId != job.requestId || stored.hold != true
}

// Checked immediately before each hub write.
void packageRequireHold(Map job) {
    if (packageSuperseded(job) || packageHoldLost(job))
        throw new IllegalStateException("Deployment hold was released or replaced; nothing further was written")
    packagePublish(job)
}

def prepareWatchdogPackage(Map job) {
    String base = packageRepository(job.baseUrl)
    def manifest = _parseJsonBody(fetchExternal("${base}/${job.ref}/packageManifest.json".toString()))
    def expectedPaths = ["MCP Rule": "hubitat-mcp-rule.groovy", "MCP Rule Server": "hubitat-mcp-server.groovy"]
    if (!(manifest instanceof Map) || !(manifest.apps instanceof List) || manifest.apps.size() != 2 ||
        manifest.apps.any { it.namespace != "mcp" || !expectedPaths.containsKey(it.name) } ||
        manifest.apps*.name.unique().size() != 2 || manifest.drivers || manifest.files ||
        !(manifest.bundles instanceof List) || manifest.bundles.size() != 1 ||
        !manifest.bundles[0].location?.toString()?.endsWith("/mcp-libraries.zip"))
        throw new IllegalStateException("Only the existing MCP parent, child, and libraries bundle are supported")
    def types = _parseJsonBody(hubGet("/hub2/userAppTypes", [:]))
    if (!(types instanceof List)) throw new IllegalStateException("Cannot read existing Apps Code identities")
    job.apps = []
    expectedPaths.each { name, path ->
        def entry = manifest.apps.find { it.name == name }
        if (!entry.location?.toString()?.endsWith("/${path}")) throw new IllegalStateException("Unexpected manifest app location")
        def matches = types.findAll { it.namespace == "mcp" && it.name == name }
        if (matches.size() != 1 || !matches[0].id?.toString()?.isInteger())
            throw new IllegalStateException("Expected exactly one existing ${name} code class")
        String url = "${base}/${job.ref}/${path}".toString()
        packageStage(job, "preflight", "Downloading ${name}")
        String source = fetchExternal(url)
        job.apps << [id: matches[0].id.toString(), name: name, url: url, sha256: packageSourceHash(source)]
        if (name == "MCP Rule Server") {
            def includes = (source =~ /(?m)^\s*#include\s+mcp\.([A-Za-z0-9_]+)/).collect { it[1] }.unique()
            if (!includes || !job.libraries*.name.containsAll(includes))
                throw new IllegalStateException("Expected library hashes do not cover every parent include")
        }
    }
    if (job.apps*.id.unique().size() != 2) throw new IllegalStateException("Parent and child code IDs must differ")
    def installed = _parseJsonBody(hubGet("/hub2/userLibraries", [:]))
    if (!(installed instanceof List)) throw new IllegalStateException("Cannot read installed libraries")
    job.libraries = job.libraries.collect { expected ->
        def matches = installed.findAll { it.namespace == "mcp" && it.name == expected.name }
        if (matches.size() > 1) throw new IllegalStateException("Library ${expected.name} is installed ${matches.size()} times; remove the duplicates first")
        // A library the commit adds is absent until the bundle creates it, so it carries no id to pin.
        return matches ? expected + [id: matches[0].id.toString()] : expected
    }
    job.bundleUrl = "${packageRepository(job.bundleBaseUrl)}/bundle-artifacts/shas/${job.ref}/mcp-libraries.zip".toString()
}

// readable:false means the install could not be inspected; matches:false means it was inspected
// and differs. reason names the first library responsible for either.
Map packageLibrarySnapshot(Map job) {
    try {
        def installed = _parseJsonBody(hubGet("/hub2/userLibraries", [:]))
        if (!(installed instanceof List)) return [readable: false, matches: false, reason: "Could not read the installed library list"]
        String differs = null
        for (def expected : job.libraries) {
            def matches = installed.findAll { it.namespace == "mcp" && it.name == expected.name }
            if (matches.size() > 1)
                return [readable: false, matches: false, reason: "Library ${expected.name} is installed ${matches.size()} times".toString()]
            if (!matches) {
                if (expected.id) return [readable: false, matches: false, reason: "Library ${expected.name} (id ${expected.id}) is no longer installed".toString()]
                differs = differs ?: "Library ${expected.name} is not installed yet".toString()
                continue
            }
            String id = matches[0].id.toString()
            if (expected.id && id != expected.id)
                return [readable: false, matches: false, reason: "Library ${expected.name} changed id from ${expected.id} to ${id}".toString()]
            def data = _parseJsonBody(hubGet("/library/list/single/data/${id}".toString(), [:]))
            if (!(data instanceof List) || data.size() != 1 || !(data[0].source instanceof String))
                return [readable: false, matches: false, reason: "Could not read the source of library ${expected.name} (id ${id})".toString()]
            if (packageSourceHash(data[0].source) != expected.sha256)
                differs = differs ?: "Library ${expected.name} (id ${id}) does not match the expected source".toString()
        }
        return [readable: true, matches: differs == null, reason: differs]
    } catch (Exception e) {
        log.error "packageLibrarySnapshot: ${e.message}"
        return [readable: false, matches: false, reason: "Library check failed: ${e.message}".toString()]
    }
}

// Null when the installed app is the deployed source, else why not. The version must advance:
// that is what proves a save landed when the new source equals the old.
String packageAppMismatch(Map target) {
    try {
        def types = _parseJsonBody(hubGet("/hub2/userAppTypes", [:]))
        if (!(types instanceof List)) return "Could not read the Apps Code list"
        def matches = types.findAll { it.namespace == "mcp" && it.name == target.name }
        if (matches.size() != 1 || matches[0].id.toString() != target.id)
            return "${target.name} no longer resolves to the single code class ${target.id}".toString()
        def data = _parseJsonBody(hubGet("/app/ajax/code", [id: target.id]))
        if (!(data instanceof Map) || !(data.source instanceof String) || !data.version?.toString()?.isLong())
            return "Could not read the source of ${target.name}".toString()
        if (data.version.toString().toLong() <= (target.beforeVersion as long))
            return "${target.name} is still at code version ${data.version}; the save has not landed".toString()
        if (packageSourceHash(data.source) != target.sha256)
            return "${target.name} does not match the expected source".toString()
        return null
    } catch (Exception e) {
        log.error "packageAppMismatch ${target.name}: ${e.message}"
        return "Check of ${target.name} failed: ${e.message}".toString()
    }
}

def packageWait(Map job, String phase, String component, String reason = null) {
    if (now() >= (job.verifyUntil as long)) {
        packageStage(job, "stopped", component, "Timed out verifying ${component}${reason ? ' (' + reason + ')' : ''}; no write was retried. Safety hold retained.")
    } else {
        packageStage(job, phase, component)
        // After the stage update, which clears detail on a phase change.
        job.detail = reason
        packagePublish(job)
        runIn(10, "runWatchdogPackageDeploy", [data: [requestId: job.requestId]])
    }
}

def packageStage(Map job, String phase, String component = "", String error = null) {
    // A running worker whose operation was abandoned or replaced stops before its next step.
    if (job.workerActive == true && packageSuperseded(job))
        throw new IllegalStateException("Deployment was abandoned or replaced; no further writes are allowed")
    long stamp = now()
    if (job.phase != phase || job.component != component) {
        job.stageStartedAt = stamp
        job.detail = null
        job.history = ((job.history ?: []) + [[phase: phase, component: component, at: stamp]]).takeRight(40)
    }
    job.phase = phase
    job.component = component
    job.updatedAt = stamp
    job.error = error
    // Persisted only where the hold comes to rest; in between, progress lives in memory.
    if (phase in ["stopped", "awaiting_verification", "complete", "abandoned"]) {
        if (job.workerActive == true && packageHoldLost(job))
            throw new IllegalStateException("Deployment hold was released or replaced; no further writes are allowed")
        // Liveness is memory-only: a persisted workerActive would outlive the worker across a restart.
        Map record = [:] + job
        record.remove("workerActive")
        atomicState.packageDeployment = record
        def back = atomicState.packageDeployment
        if (back?.requestId != job.requestId || back.phase != phase || back.hold != job.hold)
            throw new IllegalStateException("Could not persist deployment stage; no further writes are allowed")
    }
    packagePublish(job)
}

String packageSourceHash(String source) {
    def digest = java.security.MessageDigest.getInstance("SHA-256").digest(source.getBytes("UTF-8"))
    String digits = "0123456789abcdef"
    StringBuilder hex = new StringBuilder(digest.length * 2)
    for (def one : digest) {
        int value = (one as Integer) & 0xff
        hex.append(digits.charAt(value >>> 4))
        hex.append(digits.charAt(value & 0x0f))
    }
    return hex.toString()
}

def getPackageToolDefinitions() {
    return [
        [name: "hub_update_package", annotations: [title: "Deploy MCP Package", readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: true],
         description: "Start one background repair of the existing MCP package at an immutable commit. Reserve the E2E hub and verify the original MCP and v3 endpoints first. Holds further deployments and competing manual writes until hub_set_package_deployment releases the hold. Deploy a known-good ref to request restoration. A library the commit adds is created by its bundle; existing libraries must each be installed exactly once. Never updates the watchdog or OAuth. Submit once, then poll hub_get_package_deployment with the same requestId; confirm:true required.",
         inputSchema: [type: "object", properties: [requestId: [type: "string"], ref: [type: "string", description: "Full 40-character commit SHA."],
             baseUrl: [type: "string", description: "Raw GitHub source repository URL; defaults to upstream."],
             bundleBaseUrl: [type: "string", description: "Raw GitHub repository hosting bundle-artifacts/shas/<ref>/mcp-libraries.zip; defaults to upstream."],
             libraries: [type: "array", items: [type: "object", properties: [name: [type: "string"], sha256: [type: "string"]], required: ["name", "sha256"]]],
             confirm: [type: "boolean"]], required: ["requestId", "ref", "libraries", "confirm"]]],
        [name: "hub_get_package_deployment", annotations: [title: "Get Package Deployment", readOnlyHint: true, idempotentHint: true, openWorldHint: false],
         description: "Read package deployment stages, elapsed time, errors, and safety hold without contacting hub HTTP. detail carries the latest verification finding while a stage waits. Only the hold is persisted; progress is kept in memory, so phase interrupted means the watchdog restarted mid-deployment and nothing is running, and workerStale:true means a worker has been silent for 15 minutes and is presumed dead. A stopped, interrupted or missing operation never authorizes replaying the install.",
         inputSchema: [type: "object", properties: [requestId: [type: "string"]], required: ["requestId"]]],
        [name: "hub_set_package_deployment", annotations: [title: "Release Package Deployment", readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: false],
         description: "Release a package safety hold. Completion needs endpointVerified:true (the original MCP and v3 endpoint URLs and tokens still answer, app instances unchanged) on an operation awaiting verification; installed code hashes are rechecked first, and a failed recheck keeps the hold so completion can be retried. abandon:true with writesSettled:true gives up a held operation in any phase for repair, for example to redeploy a known-good ref when the installed MCP is broken; it is refused while a worker is active unless workerStale is true. Abandonment never marks the deployment successful. confirm:true required.",
         inputSchema: [type: "object", properties: [requestId: [type: "string"], endpointVerified: [type: "boolean"], abandon: [type: "boolean"],
             writesSettled: [type: "boolean", description: "For abandonment only: operator verified every submitted hub write has finished (the code versions and sources no longer change); never assume this after a timeout."],
             confirm: [type: "boolean"]], required: ["requestId", "confirm"]]],
    ]
}

def executeManualTool(String toolName, Map args) {
    if (settings?.hubSecurityEnabled == true && secCookie() == null) {
        return [success: false, error: "Hub Security is enabled but loopback auth failed (no cookie). Set the Hub Security username/password in the watchdog app settings."]
    }
    switch (toolName) {
        case "hub_update_app":      return adminUpdateApp(args)
        case "hub_set_mcp_developer_mode": return adminSetMcpDeveloperMode(args)
        case "hub_get_source":      return adminGetSource(args)
        case "hub_create_library":  return adminCreateLibrary(args)
        case "hub_update_library":  return adminUpdateLibrary(args)
        case "hub_delete_item":     return adminDeleteItem(args)
        case "hub_force_delete_app": return adminForceDeleteInstalledApp(args)
        case "hub_purge_e2e_artifacts": return adminPurgeE2eArtifacts(args)
        case "hub_reboot": return adminRebootHub(args)
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

// NON-PRIVATE so specs can stand in an instance id.
boolean isOwnInstance(id) {
    try { return id != null && id.toString() == app?.id?.toString() } catch (Exception ignore) { return false }
}

boolean isWatchdogSource(source) {
    return source instanceof String && (source =~ /definition\s*\(\s*name:\s*"E2E Dead-Man Watchdog v3"/).find()
}

// Whether the MCP server could repair this watchdog right now: installed once, enabled, and its
// own /mcp endpoint lists a code-update tool. Loopback only, so the cloud relay is not involved.
Map peerEndpointStatus() {
    try {
        def inventory = _parseJsonBody(hubGet("/hub2/appsList", [:]))
        def found = []
        def walk
        walk = { node ->
            def d = node?.data
            if (d?.type == "MCP Rule Server" && d.id != null) found << d
            node?.children?.each { c -> walk(c) }
        }
        (inventory instanceof Map ? (inventory.apps ?: []) : []).each { a -> walk(a) }
        if (found.size() != 1) return [available: false, reason: "expected one installed MCP Rule Server, found ${found.size()}".toString()]
        String id = found[0].id.toString()
        if (found[0].disabled == true) return [available: false, appId: id, reason: "the MCP server instance is disabled"]
        def status = _parseJsonBody(hubGet("/installedapp/statusJson/${id}".toString(), [:]))
        def entry = (status instanceof Map ? (status.appState ?: []) : []).find { it?.name?.toString() == "accessToken" }
        String token = entry?.value?.toString()
        if (!token) return [available: false, appId: id, reason: "could not read the MCP server's access token"]
        def answer = _parseJsonBody(peerPost(id, token, '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'))
        def tools = (answer instanceof Map && answer.result instanceof Map) ? answer.result.tools : null
        if (!(tools instanceof List)) return [available: false, appId: id, reason: "the MCP server endpoint did not answer tools/list"]
        if (!tools.any { it?.name in ["hub_manage_code", "hub_update_app"] })
            return [available: false, appId: id, reason: "the MCP server lists no code-update tool"]
        return [available: true, appId: id]
    } catch (Exception e) {
        return [available: false, reason: "peer check failed: ${e.message}".toString()]
    }
}

// NON-PRIVATE so specs can stand in the peer's answer. Never logs the exception: it can carry the token.
// The timeout stays under the cloud relay's ~10s limit, so a dead peer is reported, not lost.
String peerPost(String appId, String token, String json) {
    String out = null
    try {
        httpPost([uri: "http://127.0.0.1:8080", path: "/apps/api/${appId}/mcp".toString(), query: [access_token: token],
                  body: json, requestContentType: "application/json", textParser: true,
                  ignoreSSLIssues: true, timeout: 7]) { resp -> out = respText(resp) }
    } catch (Exception ignore) { log.warn "The MCP server endpoint did not answer the peer check." }
    return out
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
// synchronously AND stash a lastSelfDeploy record in atomicState for every update.
// Adapted from hubInternalGet/PostForm to hubGet/hubPostForm.
def adminUpdateApp(args) {
    requireConfirm(args)
    def itemId = args.appId
    if (!itemId) throw new IllegalArgumentException("appId is required")

    // Source resolution: exactly one of source / sourceFile / importUrl / resave.
    def modesSet = [args.resave, args.sourceFile, args.source, args.importUrl].count { it }
    if (modesSet == 0) throw new IllegalArgumentException("One of 'source', 'sourceFile', 'importUrl', or 'resave' is required")
    if (modesSet > 1) throw new IllegalArgumentException("Provide exactly one of 'source', 'sourceFile', 'importUrl', or 'resave'")

    def sourceCode = null
    def sourceMode = null
    def freshVersion = null
    def currentSource = null

    if (args.resave) {
        sourceMode = "resave"
        def responseText = hubGet("/app/ajax/code", [id: itemId])
        if (!responseText) throw new IllegalArgumentException("Could not fetch current source for app ID ${itemId}")
        def parsed = new groovy.json.JsonSlurper().parseText(responseText)
        if (parsed.status == "error" || !parsed.source) {
            throw new IllegalArgumentException("Cannot read app ID ${itemId}: ${parsed.errorMessage ?: 'no source returned'}")
        }
        sourceCode = parsed.source
        currentSource = parsed.source
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

    // Resolve current version for the optimistic lock.
    def currentVersion = freshVersion
    if (currentVersion == null) {
        try {
            def vt = hubGet("/app/ajax/code", [id: itemId])
            if (vt) {
                def current = new groovy.json.JsonSlurper().parseText(vt)
                currentVersion = current.version
                currentSource = current.source
            }
        } catch (Exception vErr) {
            logDebug "adminUpdateApp: version fetch failed: ${vErr.message}"
        }
    }
    if (currentVersion == null) throw new IllegalArgumentException("Could not determine current version for app ID ${itemId}. The app may not exist.")

    // The watchdog and the MCP server are each other's only repair path, so the watchdog's own
    // code is replaced only while the MCP server's endpoint answers.
    if (isWatchdogSource(currentSource)) {
        def peer = peerEndpointStatus()
        if (peer.available != true) {
            return [success: false, appId: itemId, peerEndpoint: peer,
                    error: "Refused: app ${itemId} is this watchdog's own code, and the MCP server endpoint that could repair a bad update is not available (${peer.reason}).",
                    note: "Nothing was changed. Deploy a known-good MCP package with hub_update_package, confirm hub_get_info(peer:true) reports the endpoint available, then retry."]
        }
    }

    // Self-update = the watchdog saving its OWN code class, which reloads it mid-request. Keyed on
    // the selfUpdate flag or a selfClassId equal to appId.
    boolean isSelfUpdate = (args.selfUpdate == true) ||
        (args.selfClassId != null && itemId?.toString() == args.selfClassId?.toString())

    mcpAdminLog "Updating app ID ${itemId} (version ${currentVersion}, mode ${sourceMode}, sourceLength ${sourceCode.length()})"
    try {
        // Copied error-capture from toolUpdateItemCodeInner: read the /app/ajax/update response
        // errorMessage synchronously.
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
        // Stashed for EVERY app update (with appId), not just self-update: a large app save outlives
        // the cloud relay's response window, so a caller recovers success + the verbatim compile error
        // by polling hub_get_info.lastSelfDeploy.
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
// (hubitat-mcp-server.groovy), including the File Manager auto-save of a large source.
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

    if (type == "app") {
        def current = _parseJsonBody(hubGet("/app/ajax/code", [id: id]))
        if (!(current instanceof Map))
            return [success: false, appId: id, error: "Could not read app ${id} to rule out this watchdog's own code; nothing was deleted."]
        if (isWatchdogSource(current.source))
            return [success: false, appId: id, error: "Refused: app ${id} is this watchdog's own code, the hub's remote repair path."]
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
// transport) -- is reported as success:false so the caller can warn + keep its recovery list.
// DISTINCT from hub_delete_item(type:'app'), which hits /app/edit/deleteJsonSafe (an Apps Code
// CLASS, not a running instance). Used by the fixture purge.
def adminForceDeleteInstalledApp(args) {
    requireConfirm(args)
    def id = (args.id != null) ? args.id : args.appId
    if (!id) throw new IllegalArgumentException("id (the installed-app instance id) is required")
    if (!id.toString().isInteger() || id.toString().toInteger() <= 0) {
        throw new IllegalArgumentException("id must be a positive integer (got: '${id}')")
    }
    if (isOwnInstance(id))
        return [success: false, id: id, error: "Refused: ${id} is this watchdog's own instance, the hub's only remote repair path."]
    mcpAdminLog "Force-deleting installed app instance ${id} (/installedapp/forcedelete/${id}/quiet)"
    def resp = hubGetStatus("/installedapp/forcedelete/${id}/quiet", [:])
    Integer st = (resp?.status != null) ? (resp.status as Integer) : null
    // The forcedelete endpoint answers SUCCESS with a 302 redirect to the apps list (a plain 2xx is
    // also fine). >=400 -- or no status at all, meaning the request never reached the hub
    // (auth/transport) -- is a real failure: report it so the caller warns + keeps its id list.
    if (st == null || st >= 400) {
        return [success: false, error: "Force-delete of installed app ${id} did not confirm (status=${st ?: 'none'}) -- endpoint error, auth failure, or the request never reached the hub.", id: id]
    }
    // The 302 alone is NOT proof the delete committed: teardown fires these while the hub may be
    // recompiling the MCP app, a window where admin-endpoint writes are known to commit late or
    // strand on this firmware. Verify gone-ness via /installedapp/json/<id> (the same
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

// Hub variables have NO app-facing delete API -- there is no removeGlobalVar/removeGlobalVariable
// on the app class (an earlier revision called removeGlobalVariable, every purge failed with "No
// signature of method", and BAT_E2E_ vars accumulated on the test hub). resources/hub2-source's
// README records the classic hubVar wizard as the real variable-CRUD contract, so drive that.
//
// Resolver ported from _resolveDirectAppId (hubitat-mcp-server.groovy). Two hops max: direct ->
// create -> configure, each ANCHORED on its path shape, because taking the first digits anywhere
// in the Location picks up 127 from an absolute http://127.0.0.1:8080/... URL, or the app TYPE id
// on a create/<typeId> hop -- either of which drives deleteGV/delConfirm at an unrelated app.
private Integer findHubVariablesAppId() {
    String path = "/installedapp/direct/hubVariables"
    for (int hop = 1; hop <= 2; hop++) {
        Map r = null
        try { r = hubGetStatus(path, [:]) } catch (Exception e) { mcpAdminLog "findHubVariablesAppId: hop ${hop} GET threw ${e.message}"; return null }
        Integer st = null
        try { st = r?.status as Integer } catch (Exception ignore) { st = null }
        String loc = r?.location?.toString()
        if (st == null || st < 300 || st >= 400 || !loc) {
            mcpAdminLog "findHubVariablesAppId: hop ${hop} ${path} status=${st} location=${loc} -- not a redirect"
            return null
        }
        def cfg = (loc =~ /\/installedapp\/configure\/(\d+)/)
        if (cfg.find()) return cfg.group(1).toInteger()
        def create = (loc =~ /\/installedapp\/create\/(\d+)/)
        if (create.find() && hop == 1) {
            // Rebuild hop 2 from the captured type id rather than following the Location verbatim:
            // normalises an absolute URL and never follows past the expected chain.
            path = "/installedapp/create/${create.group(1)}"
            continue
        }
        mcpAdminLog "findHubVariablesAppId: hop ${hop} unexpected Location ${loc}"
        return null
    }
    return null
}

// One wizard button click on the hubVar page. Mirrors _rmClickAppButton's body shape from
// hubitat-mcp-server.groovy, minus the RM-only page cache, the `version` concurrent-edit token
// (the hubVar wizard tolerates its absence) and the >=400 throw -- this caller verifies by
// read-back through getGlobalVar instead.
private Map clickHubVarButton(Integer appId, String buttonName, String stateAttribute) {
    def body = [id: appId.toString(), name: buttonName,
                ("settings[${buttonName}]".toString()): "clicked",
                ("${buttonName}.type".toString()): "button",
                formAction: "update", currentPage: "hubVar",
                pageBreadcrumbs: '["mainPage"]']
    if (stateAttribute) body.stateAttribute = stateAttribute
    return hubPostForm("/installedapp/btn", body)
}

// deleteGV opens the confirm prompt keyed on the variable name; delConfirm commits. The first click
// sequence after a fresh create/edit can be dropped by the hub (a state-machine race that priming
// alone does not reliably defeat), so the whole sequence is retried once -- the same two-attempt
// shape hub_delete_variable uses, for the same empirically-observed reason.
// Returns null on success, else a reason. The two click statuses are part of the reason: a 5xx
// (or a wrong app id) used to be reported as "likely in use by a rule", sending the operator after
// a referencing rule that did not exist.
// `claim` is the purge sweep's claim token when this runs inside a sweep: the claim is renewed and
// re-checked before EVERY wizard click, not only on entry. Each click is a form POST with the
// 420-second hub timeout, and there are up to four per variable, so a helper that renewed only on
// entry could outlive the 15-minute staleness escape and keep clicking after a newer sweep had
// taken the claim. A null claim (a caller outside a sweep) skips the check and never touches the
// claim stamp.
private String deleteHubVariable(Integer appId, String varName, String claim = null) {
    String lastClicks = "unknown"
    for (int attempt = 0; attempt < 2; attempt++) {
        try {
            hubGet("/installedapp/configure/json/${appId}", [:])
            hubGet("/installedapp/statusJson/${appId}", [:])
        } catch (Exception ignore) { /* priming is best-effort */ }
        Integer s1 = null, s2 = null
        if (claim != null && !renewPurgeClaim(claim)) return "purge claim lost to a newer sweep -- stopped before the deleteGV click (${lastClicks})"
        try { s1 = clickHubVarButton(appId, varName, "deleteGV")?.status as Integer } catch (Exception ignore) { s1 = null }
        if (claim != null && !renewPurgeClaim(claim)) return "purge claim lost to a newer sweep -- stopped before the delConfirm click (deleteGV=${s1 ?: 'no response'})"
        try { s2 = clickHubVarButton(appId, "delConfirm", null)?.status as Integer } catch (Exception ignore) { s2 = null }
        lastClicks = "deleteGV=${s1 ?: 'no response'}, delConfirm=${s2 ?: 'no response'}"
        boolean clicksOk = s1 != null && s1 < 400 && s2 != null && s2 < 400
        for (int v = 0; v < 8; v++) {
            // A THROW is not evidence of deletion -- only a clean read returning null is. Treating
            // the exception as "gone" (which the enumerate-then-delete flow would let through
            // silently) would report a variable purged that is still on the hub.
            def gone = false
            try { gone = (getGlobalVar(varName) == null) } catch (Exception ignore) { gone = false }
            if (gone) return null
            if (v < 7) {
                try { pauseExecution(300) } catch (Exception ignore) { }
            }
        }
        if (!clicksOk) return "hubVar wizard click(s) were not accepted (${lastClicks}) and the variable still exists"
    }
    return "hubVar wizard clicks were accepted (${lastClicks}) but the variable still exists after two attempts -- likely still referenced by a rule"
}

// A purge call that yielded to a sweep already running. Carries the same zero-count shape a real
// sweep returns so a caller reading the count fields is not tripped by a missing key.
private Map purgeNoOpResult(String prefix, String note) {
    return [success: true, inFlight: true, prefix: prefix,
            deletedCount: 0, failedCount: 0, deleted: [], failed: [],
            variablesDeletedCount: 0, variablesFailedCount: 0, variablesDeleted: [], variablesFailed: [],
            note: note]
}

// hub_purge_e2e_artifacts: ONE-call LOCAL sweep of leftover test fixtures. Enumerates every installed-app
// instance whose name starts with the BAT_E2E_ test prefix (via /hub2/appsList, the same read
// adminListAppInstances uses) and force-deletes each by reusing adminForceDeleteInstalledApp's
// forcedelete+verify-gone path. The whole loop runs loopback-local on the hub, so CI makes ONE cloud
// round-trip instead of N. Hard-scoped to the prefix (never a real app); confirm:true required.
// SINGLE-FLIGHT LATCH + SHORT RESULT CACHE. This sweep takes minutes (N apps x forcedelete + verify-gone), which is far longer than
// the ~10s cloud-relay timeout, so a caller that retries a dropped response does so while the FIRST
// sweep is still running. Observed live 2026-09-01: five overlapping invocations, each re-enumerating the
// same app list and racing on the same ids (three hit forcedelete/30839 in the same millisecond),
// each running 11+ minutes. That pile-on is what exhausted the hub's web thread pool and wedged
// it for 3h32m. A duplicate call now returns the in-flight marker instead of starting a sweep,
// and a call arriving just after one finished gets the real result from the cache -- so a relay
// drop costs CI nothing and costs the hub nothing.
def adminPurgeE2eArtifacts(args) {
    requireConfirm(args)
    String prefix = (args?.prefix instanceof String && args.prefix.trim()) ? args.prefix.trim() : "BAT_E2E_"

    long nowMs = now()
    Long purgeAt = null
    // 15-minute staleness escape: a sweep killed mid-flight (app recompile, hub restart) must not
    // wedge the latch permanently. Matches the observed worst-case sweep of ~11.5 minutes.
    // Read the whole claim as ONE snapshot under the claim lock: the owner clears its three keys
    // together, so reading them separately can pair a fresh timestamp with an already-cleared
    // owner -- and an unowned timestamp is nobody's sweep, so it covers nothing.
    String activeClaim = null
    String activePrefix = null
    synchronized (PURGE_CLAIM_LOCK) {
        try {
            purgeAt = atomicState.purgeInFlightAt as Long
            activeClaim = atomicState.purgeClaim?.toString()
            activePrefix = atomicState.purgeClaimPrefix?.toString()
        } catch (Exception ignore) { purgeAt = null }
    }
    if (purgeAt != null && (nowMs - purgeAt) < 900000L && activeClaim != null) {
        // A sweep for a DIFFERENT prefix is not cover for this one. Say busy, not done.
        if (activePrefix != null && activePrefix != prefix) {
            return [success: false, busy: true, prefix: prefix, activePrefix: activePrefix,
                    error: "A purge for prefix '${activePrefix}' has been running for ${((nowMs - purgeAt) / 1000) as long}s; it does not cover '${prefix}'.",
                    note: "Retry after the running sweep completes (its results are cached for 5 minutes, so the retry will not re-run it)."]
        }
        mcpAdminLog "Purge already in flight (${((nowMs - purgeAt) / 1000) as long}s) -- returning the in-flight marker instead of starting a second sweep."
        return purgeNoOpResult(prefix, "A purge started ${((nowMs - purgeAt) / 1000) as long}s ago is still running; this call was a no-op. Do NOT retry -- the running sweep covers the same prefix.")
    }
    Long cachedAt = null
    try { cachedAt = atomicState.purgeResultAt as Long } catch (Exception ignore) { cachedAt = null }
    if (cachedAt != null && (nowMs - cachedAt) < 300000L && atomicState.purgeResult instanceof Map
            && atomicState.purgeResult?.prefix?.toString() == prefix) {
        def cached = [:] + (atomicState.purgeResult as Map)
        cached.cached = true
        cached.note = "Result of the sweep that completed ${((nowMs - cachedAt) / 1000) as long}s ago (cached; this call did NOT re-run it). ${cached.note ?: ''}"
        mcpAdminLog "Purge result served from cache (${((nowMs - cachedAt) / 1000) as long}s old)."
        return cached
    }
    String claim = "purge-${java.util.UUID.randomUUID()}".toString()
    boolean claimed = false
    synchronized (PURGE_CLAIM_LOCK) {
        Long held = null
        String heldClaim = null
        try { held = atomicState.purgeInFlightAt as Long; heldClaim = atomicState.purgeClaim?.toString() } catch (Exception ignore) { held = null }
        // A stamp nobody owns is not a running sweep: the owner clears its three keys together, so
        // a fresh timestamp with no claim is the trailing edge of a sweep that has finished. Left
        // as a blocker it would stall every purge for 15 minutes after a normal completion.
        if (held == null || heldClaim == null || (nowMs - held) >= 900000L) {
            atomicState.purgeInFlightAt = nowMs
            atomicState.purgeClaim = claim
            atomicState.purgeClaimPrefix = prefix
            claimed = true
        }
    }
    if (!claimed) {
        // Re-read: this is the WINNER's prefix, not the snapshot taken before the race.
        activePrefix = atomicState.purgeClaimPrefix?.toString()
        if (activePrefix != null && activePrefix != prefix) {
            return [success: false, busy: true, prefix: prefix, activePrefix: activePrefix,
                    error: "A concurrent purge for prefix '${activePrefix}' won the claim; it does not cover '${prefix}'.",
                    note: "Retry after the running sweep completes."]
        }
        mcpAdminLog "Purge claim lost to a concurrent request -- yielding rather than sweeping twice."
        return purgeNoOpResult(prefix, "A concurrent purge for the same prefix won the claim; this call was a no-op. Do NOT retry.")
    }
    try {
        return purgeE2eArtifactsLocked(prefix, claim)
    } finally {
        // Release ONLY if the claim is still ours. If this sweep ran past the 15-minute staleness
        // escape and a newer sweep took the claim, clearing purgeInFlightAt here would drop the
        // NEWER sweep's marker and let a third request pile on -- the exact race the latch exists
        // to prevent. The successor releases its own markers when it finishes.
        synchronized (PURGE_CLAIM_LOCK) {
            if (atomicState.purgeClaim?.toString() == claim) {
                atomicState.purgeInFlightAt = null
                atomicState.purgeClaim = null
                atomicState.purgeClaimPrefix = null
            }
        }
    }
}

// The 15-minute stale-claim escape must measure a sweep that STOPPED, not one that is long: a sweep
// is one loopback call per artifact, so its length is unbounded. Renewing the stamp before every
// destructive step keeps a live sweep's claim exclusive; a sweep that did lose its claim to the
// escape stops rather than race the successor. Ownership check and renewal are ONE section under the
// claim lock: split, a successor could take the claim between them and both sweeps would delete.
private boolean renewPurgeClaim(String claim) {
    synchronized (PURGE_CLAIM_LOCK) {
        try {
            // A caller with no claim (the spec seam) holds no lease, so it has none to renew:
            // writing the stamp here would extend whatever lease IS held, including another
            // sweep's, which is precisely what the claim exists to prevent.
            if (claim == null) return true
            if (atomicState.purgeClaim?.toString() != claim) return false
            long stamp = now()
            atomicState.purgeInFlightAt = stamp
            // Read back: an unrenewed stamp goes stale, a successor takes the claim, and this sweep
            // would carry on deleting alongside it believing it still owns the lease.
            Long back = atomicState.purgeInFlightAt as Long
            if (back != stamp) {
                log.error "E2E Dead-Man Watchdog v3: the purge lease renewal did not persist (state holds ${back}) -- stopping this sweep rather than racing a successor."
                return false
            }
            return true
        } catch (Exception ignore) { return false }
    }
}

// NON-PRIVATE deliberately (see probeLoopbackAlive): a private method's internal callers bypass
// metaClass dispatch, so a spec could not stand in a sweep body -- the successor-claim test needs
// to install a competing claim mid-sweep to prove the finally leaves it alone.
Map purgeE2eArtifactsLocked(String prefix, String claim = null) {
    String raw = hubGet("/hub2/appsList", [:])
    String noSweep = "Nothing was deleted: the hub did not answer /hub2/appsList. Confirm hub_get_info answers, then re-run hub_purge_e2e_artifacts; a repeat points at the hub's web stack, not the purge."
    if (!raw) return [success: false, error: "empty response from /hub2/appsList -- cannot enumerate to purge", note: noSweep]
    def parsed
    try { parsed = new groovy.json.JsonSlurper().parseText(raw) }
    catch (Exception e) { return [success: false, error: "unparseable /hub2/appsList: ${e.message}", note: noSweep] }
    def targets = []
    // A Button Controller relabels itself after the device it binds, so a test's controller is
    // recognised by type plus a fixture-device name; the mcptest throwaway apps never carry the prefix.
    List appClasses = throwawayCodeClasses("/hub2/userAppTypes")
    Set throwawayAppTypes = (appClasses ?: [])*.name as Set
    String keep = "${prefix}KEEP_".toString()
    def recurse
    recurse = { Map node, boolean isChild ->
        def d = node?.data ?: [:]
        // The list carries the display label, which can wrap status markup such as "(Paused)".
        String name = d.name instanceof String ? d.name.replaceAll(/<[^>]*>/, "").trim() : ""
        boolean prefixed = name.startsWith(prefix)
        boolean strandedController = isChild && d.type == "Button Controller-5.1" &&
            (name.contains(prefix) || name.contains("E2E_PERM_"))
        boolean throwaway = !prefixed && !strandedController && d.type in throwawayAppTypes
        if (d.id != null && !name.startsWith(keep) && (prefixed || strandedController || throwaway)) {
            targets << [id: d.id, name: name, throwaway: throwaway]
        }
        node?.children?.each { c -> recurse(c, true) }
    }
    (parsed?.apps ?: []).each { a -> recurse(a, false) }
    mcpAdminLog "Purging ${targets.size()} ${prefix}* installed-app instance(s) locally."
    def deleted = []
    def failed = []
    if (appClasses == null) {
        failed << [id: "*", name: "/hub2/userAppTypes", error: "could not read the app code classes, so mcptest throwaway instances were not purged"]
    }
    targets.each { t ->
        if (!renewPurgeClaim(claim)) {
            failed << [id: t.id, name: t.name, error: "purge claim lost to a newer sweep -- stopped before this delete"]
            return
        }
        if (t.throwaway) {
            // The apps list names the type but not its namespace: confirm it is the mcptest class.
            def ns = _parseJsonBody(hubGet("/installedapp/configure/json/${t.id}".toString(), [:]))?.app?.appType?.namespace
            if (ns == null) {
                failed << [id: t.id, name: t.name, error: "could not read the instance's code class, so it was left in place"]
                return
            }
            if (ns != "mcptest") return
        }
        def r
        try { r = adminForceDeleteInstalledApp([id: t.id, confirm: true]) }
        catch (Exception e) { r = [success: false, error: e.message] }
        if (r?.success) { deleted << [id: t.id, name: t.name] }
        else { failed << [id: t.id, name: t.name, error: r?.error] }
    }
    // Variables are hub-GLOBAL, so any app can ENUMERATE them via getAllGlobalVars -- but there is
    // no app-facing DELETE (the belief that there was is what left this leg silently broken), so
    // removal drives the classic hubVar wizard through findHubVariablesAppId / deleteHubVariable
    // above, the same two clicks the main server's hub_delete_variable uses. Loopback-local; no relay.
    def varsDeleted = []
    def varsFailed = []
    try {
        def allVars = getAllGlobalVars()
        if (allVars == null) {
            // null means the enumeration itself failed -- NOT that there are no variables. Folding
            // it into an empty map reported a clean sweep with nothing deleted.
            varsFailed << [name: "*", error: "getAllGlobalVars returned null -- could not enumerate hub variables, so none were purged"]
            allVars = [:]
        }
        def targetVars = allVars.keySet().findAll { (it instanceof String) && it.startsWith(prefix) && !it.startsWith("${prefix}KEEP_") }
        if (targetVars) {
            Integer hvAppId = findHubVariablesAppId()
            if (hvAppId == null) {
                varsFailed << [name: "*", error: "could not resolve the Hub Variables app id via /installedapp/direct/hubVariables"]
            } else {
                targetVars.each { vn ->
                    if (!renewPurgeClaim(claim)) {
                        varsFailed << [name: vn, error: "purge claim lost to a newer sweep -- stopped before this delete"]
                        return
                    }
                    try {
                        String why = deleteHubVariable(hvAppId, vn, claim)
                        if (why == null) { varsDeleted << vn }
                        else { varsFailed << [name: vn, error: why] }
                    } catch (Exception e) { varsFailed << [name: vn, error: e.message] }
                }
            }
        }
    } catch (Exception e) {
        varsFailed << [name: "*", error: "getAllGlobalVars failed: ${e.message}"]
    }
    Map others = purgeOtherFixturesLocked(prefix, claim)
    mcpAdminLog "Purge complete: ${deleted.size()} app(s), ${varsDeleted.size()} variable(s), " +
                "${others.deletedCount} other fixture(s) deleted; ${failed.size()} app + " +
                "${varsFailed.size()} var + ${others.failed.size()} other failure(s)."
    // Runtime-failure contract: a caller must not have to diff two count fields to notice the
    // sweep failed. Aggregate what broke into a top-level error + an actionable note.
    def problems = []
    if (!failed.isEmpty()) problems << "${failed.size()} app(s)"
    if (!varsFailed.isEmpty()) problems << "${varsFailed.size()} variable(s)"
    if (!others.failed.isEmpty()) problems << "${others.failed.size()} other fixture(s)"
    def result = [success: problems.isEmpty(), prefix: prefix,
            deletedCount: deleted.size(), failedCount: failed.size(), deleted: deleted, failed: failed,
            variablesDeletedCount: varsDeleted.size(), variablesFailedCount: varsFailed.size(),
            variablesDeleted: varsDeleted, variablesFailed: varsFailed,
            otherDeleted: others.deleted, otherFailed: others.failed]
    if (problems) {
        result.error = "Purge of ${prefix}* completed with failures: ${problems.join(' and ')} could not be removed."
        result.note = "Inspect the failed / variablesFailed / otherFailed entries for the per-item reason. A variable that will not delete is usually still referenced by a rule -- delete the rule first. Do NOT blind-retry the sweep; re-run it only after addressing the listed items."
    }
    // Cache BEFORE returning so a CI retry that lost the response to a relay drop reads the real
    // outcome rather than re-running the sweep.
    try { atomicState.purgeResult = result; atomicState.purgeResultAt = now() } catch (Exception ignore) { }
    return result
}

// The mcptest throwaway code classes the e2e suite creates ("Deadman Test Target*"), from
// /hub2/userAppTypes or /hub2/userDeviceTypes; null when the list is unreadable. Non-private for specs.
List throwawayCodeClasses(String path) {
    def parsed = _parseJsonBody(hubGet(path, [:]))
    if (!(parsed instanceof List)) return null
    return parsed.findAll { it instanceof Map && it.namespace == "mcptest" && it.id != null &&
        (it.name instanceof String) && it.name.startsWith("Deadman Test Target") }
}

// Everything else a run can leave that is not an installed app or a hub variable: devices, rooms,
// throwaway code classes and bundle, and File Manager litter. Each kind is best effort and reported,
// so one unreadable listing never hides the rest. BAT_E2E_KEEP_ scaffolds are never touched.
// Non-private so specs can stand it in (see purgeE2eArtifactsLocked).
Map purgeOtherFixturesLocked(String prefix, String claim) {
    String keep = "${prefix}KEEP_".toString()
    def deleted = []
    def failed = []
    def attempt = { String kind, Object id, String name, Closure action ->
        if (!renewPurgeClaim(claim)) {
            failed << [kind: kind, id: id, name: name, error: "purge claim lost to a newer sweep -- stopped before this delete"]
            return
        }
        try {
            String why = action()
            if (why == null) deleted << [kind: kind, id: id, name: name]
            else failed << [kind: kind, id: id, name: name, error: why]
        } catch (Exception e) {
            failed << [kind: kind, id: id, name: name, error: e.message]
        }
    }

    // Devices: virtual devices are children of the MCP app, but the admin force delete removes any device.
    def tree = _parseJsonBody(hubGet("/hub2/devicesList", [:]))
    if (!(tree instanceof Map) || !(tree.devices instanceof List)) {
        failed << [kind: "device", id: "*", error: "could not read /hub2/devicesList, so no devices were purged"]
    } else {
        def devices = []
        def walk
        walk = { node ->
            def d = node?.data
            if (d?.id != null && d.name instanceof String && d.name.startsWith(prefix) && !d.name.startsWith(keep)) {
                devices << [id: d.id, name: d.name]
            }
            node?.children?.each { c -> walk(c) }
        }
        tree.devices.each { walk(it) }
        devices.each { dev ->
            attempt("device", dev.id, dev.name) {
                hubGet("/device/forceDelete/${dev.id}/yes".toString(), [:]) != null ? null : "force delete got no response"
            }
        }
    }

    // Rooms.
    try {
        (getRooms() ?: []).findAll { r -> r?.name instanceof String && r.name.startsWith(prefix) && !r.name.startsWith(keep) }
            .each { r ->
                attempt("room", r.id, r.name) {
                    def resp = hubPostJson("/room/delete/${r.id}".toString(), groovy.json.JsonOutput.toJson([roomId: r.id as Integer]))
                    if (resp?.status == null || resp.status >= 300) hubGet("/room/delete/${r.id}".toString(), [:])
                    getRooms()?.any { it?.id?.toString() == r.id?.toString() } ? "room still listed after delete" : null
                }
            }
    } catch (Exception e) {
        failed << [kind: "room", id: "*", error: "could not list rooms: ${e.message}"]
    }

    // Throwaway code classes (their instances went with the app sweep) and the throwaway bundle.
    [["app", "/hub2/userAppTypes"], ["driver", "/hub2/userDeviceTypes"]].each { type, path ->
        List classes = throwawayCodeClasses(path)
        if (classes == null) {
            failed << [kind: "${type} code".toString(), id: "*", error: "could not read ${path}, so no ${type} code was purged".toString()]
            return
        }
        classes.each { c ->
            attempt("${type} code".toString(), c.id, c.name) {
                def r = adminDeleteItem([type: type, id: c.id, confirm: true])
                r?.success == true ? null : (r?.error ?: "${type} code delete failed".toString())
            }
        }
    }
    def bundleList = adminListBundles([:])
    if (bundleList?.source != "hub_api") {
        failed << [kind: "bundle", id: "*", error: "could not read the bundle list, so no bundle was purged"]
    } else {
        (bundleList.bundles ?: []).findAll { it?.namespace == "mcptest" && it.id != null }.each { b ->
            attempt("bundle", b.id, b.name) {
                def r = adminDeleteBundle([bundleId: b.id.toString(), confirm: true])
                r?.success == true ? null : (r?.error ?: "bundle delete failed")
            }
        }
    }

    // File Manager: BAT_E2E_ files and the e2e-*_backup_* snapshots of the harness's control files.
    // mcp-rm-backup-* files are listed in the MCP app's backup index, so the app deletes those itself.
    List files = fileManagerNames()
    if (files == null) {
        failed << [kind: "file", id: "*", error: "could not read /hub/fileManager/json, so no files were purged"]
    } else {
        List doomed = files.findAll { nm ->
            nm instanceof String && !nm.startsWith(keep) &&
                (nm.startsWith(prefix) || (nm.startsWith("e2e-") && nm.contains("_backup_")))
        }
        List tried = []
        doomed.each { nm ->
            if (!renewPurgeClaim(claim)) {
                failed << [kind: "file", id: nm, name: nm, error: "purge claim lost to a newer sweep -- stopped before this delete"]
                return
            }
            try { deleteHubFile(nm); tried << nm }
            catch (Exception e) { failed << [kind: "file", id: nm, name: nm, error: e.message] }
        }
        // deleteHubFile reports false for files it did remove (seen on the test hub), so a re-list decides.
        List left = tried ? fileManagerNames() : []
        if (left == null) {
            tried.each { nm -> failed << [kind: "file", id: nm, name: nm, error: "could not re-read File Manager to confirm the delete"] }
        } else {
            tried.each { nm ->
                if (left.contains(nm)) failed << [kind: "file", id: nm, name: nm, error: "still listed after delete"]
                else deleted << [kind: "file", id: nm, name: nm]
            }
        }
    }
    return [deleted: deleted, failed: failed, deletedCount: deleted.size()]
}

// File Manager file names, or null when the listing is unreadable.
private List fileManagerNames() {
    def listing = _parseJsonBody(hubGet("/hub/fileManager/json", [:]))
    def files = listing instanceof List ? listing : (listing instanceof Map && listing.files instanceof List ? listing.files : null)
    return files == null ? null : files.collect { it instanceof Map ? it.name : it }
}

def adminSetMcpDeveloperMode(args) {
    requireConfirm(args)
    def id = args?.appId?.toString()
    if (!id?.isInteger() || id.toInteger() <= 0 || args?.enabled != true) {
        throw new IllegalArgumentException("appId must be a positive installed-app ID and enabled must be true")
    }
    String path = "/installedapp/configure/json/${id.toInteger()}"
    def cfg = _parseJsonBody(hubGet(path, [:]))
    // Only the standing MCP server may be bootstrapped; labels are user-editable.
    if (!(cfg instanceof Map) || cfg.app?.id?.toString() != id.toInteger().toString() ||
        cfg.app?.appType?.namespace != "mcp" || cfg.app?.appType?.name != "MCP Rule Server" ||
        cfg.app?.version == null || cfg.configPage?.name != "mainPage") {
        return [success: false, error: "Could not verify the MCP server's installed-app identity and settings page."]
    }
    if (cfg.settings?.enableDeveloperMode?.toString() == "true") {
        return [success: true, appId: id.toInteger(), developerModeEnabled: true, changed: false]
    }
    mcpAdminLog "Enabling Developer Mode on MCP server instance ${id} for E2E setup"
    def body = [id: id.toInteger().toString(), version: cfg.app.version.toString(),
                "settings[enableDeveloperMode]": "true", "enableDeveloperMode.type": "bool",
                currentPage: "mainPage", pageBreadcrumbs: "[]", formAction: "update"]
    def response = hubPostForm("/installedapp/update/json", body)
    // A lost POST response is ambiguous: the fresh setting decides whether it landed.
    def observed = _parseJsonBody(hubGet(path, [:]))
    if (observed instanceof Map && observed.app?.id?.toString() == id.toInteger().toString() &&
        observed.settings?.enableDeveloperMode?.toString() == "true") {
        return [success: true, appId: id.toInteger(), developerModeEnabled: true, changed: true]
    }
    return [success: false, appId: id.toInteger(),
            error: "Developer Mode was not verified enabled after the settings POST (HTTP ${response?.status ?: 'no response'})."]
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
    if (disable && isOwnInstance(id))
        return [success: false, appId: id, error: "Refused: ${id} is this watchdog's own instance, and a disabled watchdog cannot be re-enabled remotely."]
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
        // hubGet swallows transport errors into null; that is a failed poll, not a status.
        if (st == null) {
            return [success: false, error: "No response from /hub/cloud/checkUpdateStatus.",
                    note: "Loopback HTTP may be down or the hub is mid-reboot. Retry in a minute; if the hub was updating, expect it to go dark for 5-10 min, then confirm firmwareVersion via hub_get_info."]
        }
        return [success: true, status: st]
    }
    if (args?.confirm != true) {
        throw new IllegalArgumentException("SAFETY CHECK FAILED: set confirm=true to apply the platform update (downloads + installs + REBOOTS the hub). Use statusOnly:true to poll progress without confirm.")
    }
    def check = null
    try { check = hubGet("/hub/cloud/checkForUpdate", [:]) } catch (Exception e) { return [success: false, error: "checkForUpdate failed: ${e.message}"] }
    Long ourStamp = null
    boolean windowHeld = false
    boolean rebootInFlight = false
    Long rebootWindow = null
    synchronized (REBOOT_LOCK) {
        // A reboot claimed the window moments ago and its POST may still be in flight: starting a
        // firmware download into a hub that is going down is the mirror image of the refusal
        // hub_reboot makes, and the two must be decided under the SAME lock or each slips past
        // the other's check. No prior window is remembered: every exit below HOLDS the window (an
        // update the hub may have accepted must keep later manual reboots blocked).
        try {
            if (atomicState.expectedDownReason?.toString() == "hub_reboot") rebootWindow = atomicState.expectedDownUntil as Long
        } catch (Exception ignore) { rebootWindow = null }
        if (rebootWindow != null && now() < rebootWindow) {
            rebootInFlight = true
        } else {
            windowHeld = markExpectedDowntime(1500000L, "hub_update_platform")
        }
        try { ourStamp = atomicState.expectedDownUntil as Long } catch (Exception ignore) { ourStamp = null }
    }
    if (rebootInFlight) {
        return [success: false, refused: true, expectedDownUntil: rebootWindow,
                error: "a hub reboot was initiated ${((now() - (rebootWindow - 600000L)) / 1000) as long}s ago and the hub may be going down -- starting a platform update into a reboot risks a half-written install. Retry once the hub is back (hub_get_info).",
                checkForUpdate: check]
    }
    if (!windowHeld) {
        return [success: false, error: "The expected-downtime window could not be persisted, so the update was not requested. This window prevents a later manual reboot during firmware installation.",
                note: "Retry in a minute; hub state writes fail under load. Nothing was scheduled.",
                checkForUpdate: check]
    }
    def resp = null
    Exception thrown = null
    try { resp = hubGet("/hub/cloud/updatePlatform", [:]) } catch (Exception e) { thrown = e }
    if (resp == null) {
        return [success: false, updateMayHaveStarted: true, expectedDownUntil: ourStamp,
                error: thrown != null ? "updatePlatform failed: ${thrown.message}" : "No response from /hub/cloud/updatePlatform -- whether the hub accepted the update is UNKNOWN.",
                note: "The 25-minute downtime window is held because the update may be installing. Manual reboot requests remain blocked unless explicitly forced. Poll hub_update_platform(statusOnly:true) and hub_get_info.firmwareVersion. If you know the update did not start, hub_reboot(force:true) overrides the window.",
                checkForUpdate: check]
    }
    // 25 minutes: the note below quotes 5-10 for the download+install+reboot, with headroom for a
    // slow mirror.
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
    String noList = "Nothing was changed: the hub did not answer /hub2/appsList. Confirm hub_get_info answers, then retry."
    if (!raw) return [success: false, error: "empty response from /hub2/appsList", note: noList]
    def parsed
    try { parsed = new groovy.json.JsonSlurper().parseText(raw) } catch (Exception e) { return [success: false, error: "unparseable /hub2/appsList: ${e.message}", note: noList] }
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
            // bundle2 is a GET with the url/pwd/private query. `private` quoted (keyword).
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
// pagination -- the test hub never has enough bundles to need paging.
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
// gone (the 302 alone is not proof). confirm:true required.
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
// fields CI needs incl. the issue #237 lastSelfDeploy record with ageMs.
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
    info.watchdogVersion = 3
    info.automaticRecovery = false
    info.autoRebootOnWedge = settings?.autoRebootOnWedge != false
    if (args?.peer == true) info.peerEndpoint = peerEndpointStatus()
    def deployment = packageJob()
    info.packageDeployment = deployment ? packageStatus(deployment) : null
    // issue #237 self-deploy outcome: persists across reloads; add ageMs.
    def lastDeploy = atomicState.lastSelfDeploy
    if (lastDeploy != null) {
        def lsd = [:] + lastDeploy
        if (lsd.at instanceof Number) lsd.ageMs = now() - (lsd.at as long)
        info.lastSelfDeploy = lsd
    }
    def wedge = [:]
    try {
        def loopback = loopbackState()
        wedge.loopbackFailStreak = loopback.failStreak
        wedge.loopbackLastOkAt = loopback.lastOkAt
        wedge.loopbackStreakStartedAt = loopback.streakStartedAt
        wedge.lastAutoRebootAt = atomicState.lastAutoRebootAt
        if (wedge.lastAutoRebootAt instanceof Number) wedge.lastAutoRebootAgeMs = now() - (wedge.lastAutoRebootAt as long)
        wedge.expectedDownUntil = atomicState.expectedDownUntil
        wedge.looksWedged = hubLooksWedged()
    } catch (Exception e) { wedge.error = e.message }
    info.wedge = wedge
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

// hub_get_jobs: condensed from toolGetHubJobs (hubitat-mcp-server.groovy). Reads jobs over loopback
// from the verified /logs/json endpoint (jobs / runningJobs / hubCommands, keyed by methodName) --
// the same shape the main server's toolGetHubJobs consumes via _logsJsonSnapshot().
def adminGetJobs(args) {
    def responseText = hubGet("/logs/json", [:])
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
        runningJobs: [count: runningJobs.size(), jobs: runningJobs],
        hubActions: [count: (data?.hubCommands ?: []).size(), actions: data?.hubCommands ?: []]
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
        // a DEFENSIVE snapshot, NOT a restore prerequisite (restores redeploy from GitHub).
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
            [name: "hub_get_variable", annotations: [title: "Get Variable", readOnlyHint: true, idempotentHint: true, openWorldHint: false], inputSchema: [type: "object", properties: [name: [type: "string", description: "Hub variable name."]], required: ["name"]], description: "Read a hub variable by name."],
            [name: "hub_set_variable", annotations: [title: "Set Variable", readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: false], inputSchema: [type: "object", properties: [name: [type: "string", description: "Hub variable name."], value: [description: "New value; must match the variable's declared type."], confirm: [type: "boolean", description: "Must be true."]], required: ["name", "value", "confirm"]], description: "Set a hub variable's value (write)."]
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
// fetchExternal: copies _fetchSourceFromUrl + _httpFetchUrl (hubitat-mcp-server.groovy)
// VERBATIM in behaviour. httpGet [uri, textParser:true, timeout:60],
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
// Shared deployment tools retain the main server names; watchdog-only maintenance tools are included.
def getManualToolDefinitions() {
    return [
        [name: "hub_set_mcp_developer_mode", annotations: [title: "Enable MCP Developer Mode", readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: false],
         description: "Enable Developer Mode on the test hub's MCP server instance for E2E setup. Verifies the app code identity and reads the setting back. Only enabled:true is accepted; confirm:true required.",
         inputSchema: [type: "object", properties: [appId: [type: "string"], enabled: [type: "boolean", enum: [true]], confirm: [type: "boolean"]], required: ["appId", "enabled", "confirm"]]],
        [name: "hub_update_app", annotations: [title: "Update App", readOnlyHint: false, destructiveHint: true, idempotentHint: true, openWorldHint: true], description: "Update an Apps Code class source (deploy). One of source/sourceFile/importUrl/resave; confirm:true required. Updating this watchdog's own code is refused unless the MCP server's endpoint answers, because that server is the only path that could repair a bad watchdog update.",
         inputSchema: [type: "object", properties: [
            appId: [type: "string", description: "Apps Code CLASS id to update."],
            source: [type: "string"], sourceFile: [type: "string"], importUrl: [type: "string"], resave: [type: "boolean"],
            selfUpdate: [type: "boolean", description: "Set true ONLY when updating this watchdog's own code class: the save reloads the watchdog mid-request, so an empty response is then read as success. Never set it when deploying another app."],
            selfClassId: [type: "string", description: "This watchdog's own Apps Code class id; when it equals appId the call is treated as selfUpdate."],
            confirm: [type: "boolean"]], required: ["appId", "confirm"]]],
        [name: "hub_get_source", annotations: [title: "Get Source", readOnlyHint: true, idempotentHint: true, openWorldHint: false], description: "Read app/driver/library source (chunked). A source over 64,000 characters is also saved whole to File Manager as a convenience copy, which makes the call a write; noSave:true skips that copy and keeps the call read-only (allowed during a held package deployment).",
         inputSchema: [type: "object", properties: [
            type: [type: "string", enum: ["app", "driver", "library"]], id: [type: "string"],
            offset: [type: "integer"], length: [type: "integer"],
            noSave: [type: "boolean", description: "Skip the File Manager copy."]], required: ["type", "id"]]],
        [name: "hub_create_library", annotations: [title: "Create Library", readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: true], description: "Install a new library. One of source/sourceFile/importUrl; confirm:true required.",
         inputSchema: [type: "object", properties: [
            source: [type: "string"], sourceFile: [type: "string"], importUrl: [type: "string"], confirm: [type: "boolean"]], required: ["confirm"]]],
        [name: "hub_update_library", annotations: [title: "Update Library", readOnlyHint: false, destructiveHint: true, idempotentHint: true, openWorldHint: true], description: "Update an existing library. One of source/sourceFile/importUrl/resave; confirm:true required.",
         inputSchema: [type: "object", properties: [
            libraryId: [type: "string"], source: [type: "string"], sourceFile: [type: "string"], importUrl: [type: "string"], resave: [type: "boolean"], confirm: [type: "boolean"]],
            required: ["libraryId", "confirm"]]],
        [name: "hub_delete_item", annotations: [title: "Delete Item", readOnlyHint: false, destructiveHint: true, idempotentHint: true, openWorldHint: false], description: "Delete an app/driver/library by id. Refuses the watchdog's own code class. confirm:true required.",
         inputSchema: [type: "object", properties: [type: [type: "string", enum: ["app", "driver", "library"]], id: [type: "string"], confirm: [type: "boolean"]], required: ["type", "id", "confirm"]]],
        [name: "hub_force_delete_app", annotations: [title: "Force Delete App", readOnlyHint: false, destructiveHint: true, idempotentHint: true, openWorldHint: false], description: "Force-delete an INSTALLED-APP instance (e.g. an RM rule) via /installedapp/forcedelete/<id>/quiet. Refuses the watchdog's own instance. confirm:true required.",
         inputSchema: [type: "object", properties: [id: [type: "string"], confirm: [type: "boolean"]], required: ["id", "confirm"]]],
        [name: "hub_purge_e2e_artifacts", annotations: [title: "Purge E2E Artifacts", readOnlyHint: false, destructiveHint: true, idempotentHint: true, openWorldHint: false], description: "One-call LOCAL sweep of leftover test fixtures: force-delete every installed-app instance whose name starts with prefix (default BAT_E2E_), plus stranded test Button Controllers and mcptest throwaway app instances; delete every matching hub variable by driving the classic hubVar wizard (there is no app-facing global-variable delete API); then devices, rooms, File Manager files and e2e-*_backup_* litter with the prefix, and the mcptest throwaway code classes and bundle. <prefix>KEEP_ scaffolds are never touched. All loopback-local on the hub so CI pays ONE cloud round-trip instead of N. Single-flight: a call arriving while a sweep is running is a no-op, and one arriving just after gets the finished sweep's cached result -- never retry it. Refused with busy:true while a different manual write is running, and with the held requestId during a package deployment hold. Returns per-class {deleted/failed} counts (otherDeleted/otherFailed for devices, rooms, code and files). confirm:true required. Every in-flight/cached guarantee here is PER PREFIX: a call for a DIFFERENT prefix while a sweep runs returns success:false, busy:true and SHOULD be retried once that sweep finishes.",
         inputSchema: [type: "object", properties: [prefix: [type: "string", description: "Name prefix to purge; default BAT_E2E_."], confirm: [type: "boolean"]], required: ["confirm"]]],
        [name: "hub_set_app_disabled", annotations: [title: "Set App Disabled", readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: false], description: "Toggle an installed app's disabled flag (the admin UI red-X) via POST /installedapp/disable; verified by read-back. Refuses to disable the watchdog's own instance. confirm:true required.",
         inputSchema: [type: "object", properties: [appId: [type: "string"], disable: [type: "boolean"], confirm: [type: "boolean"]], required: ["appId", "disable", "confirm"]]],
        [name: "hub_get_metrics", annotations: [title: "Get Metrics", readOnlyHint: true, idempotentHint: true, openWorldHint: false], inputSchema: [type: "object", properties: [:]], description: "Current hub metrics (free memory, temp, DB size, uptime) + the hub's own health alerts. Read-only."],
        [name: "hub_reboot", annotations: [title: "Reboot", readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: false], description: "Reboot the hub (POST /hub/reboot; 1-3 min downtime). The only in-band recovery from a wedged web stack -- if loopback HTTP is already dead this call cannot land either and the hub needs a physical power cycle. The watchdog also reboots on its own when loopback HTTP has been dead for 4+ minutes (at most once per 30 minutes; the autoRebootOnWedge setting). Refused during a package deployment hold or while another manual write runs, unless force:true. Requires confirm=true.", inputSchema: [type: "object", properties: [confirm: [type: "boolean", description: "Must be true."], force: [type: "boolean", description: "Reboot even during a package deployment hold, another manual write, or a platform update the hub accepted that is still installing (all refused otherwise)."]], required: ["confirm"]]],
        [name: "hub_update_platform", annotations: [title: "Update Platform", readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: true], description: "Apply the hub's pending platform update (downloads + installs + REBOOTS the hub; requires confirm=true). statusOnly=true polls update progress without confirm.", inputSchema: [type: "object", properties: [confirm: [type: "boolean", description: "Must be true to apply (the hub reboots itself)."], statusOnly: [type: "boolean", description: "Poll /hub/cloud/checkUpdateStatus only; no confirm needed."]]]],
        [name: "hub_get_memory_history", annotations: [title: "Get Memory History", readOnlyHint: true, idempotentHint: true, openWorldHint: false], description: "Free-memory / CPU-load history rows from the hub. Args: limit (default 60). Read-only.",
         inputSchema: [type: "object", properties: [limit: [type: "integer"]]]],
        [name: "hub_get_hub_logs", annotations: [title: "Get Hub Logs", readOnlyHint: true, idempotentHint: true, openWorldHint: false], description: "Most-recent hub system log entries. Args: level (error/warn/info), limit (default 50). Read-only.",
         inputSchema: [type: "object", properties: [level: [type: "string"], limit: [type: "integer"]]]],
        [name: "hub_list_app_instances", annotations: [title: "List App Instances", readOnlyHint: true, idempotentHint: true, openWorldHint: false], inputSchema: [type: "object", properties: [:]], description: "Every running app INSTANCE (flattened /hub2/appsList with parentId) -- the full app inventory. DISTINCT from hub_list_apps (Apps Code CLASSES, which resolve_class_id depends on). Read-only."],
        [name: "hub_install_bundle", annotations: [title: "Install Bundle", readOnlyHint: false, destructiveHint: true, idempotentHint: true, openWorldHint: true], description: "Install a code bundle .zip from a URL the hub fetches itself (HPM-style). confirm:true required.",
         inputSchema: [type: "object", properties: [importUrl: [type: "string"], primary: [type: "boolean"], confirm: [type: "boolean"]], required: ["importUrl", "confirm"]]],
        [name: "hub_list_bundles", annotations: [title: "List Bundles", readOnlyHint: true, idempotentHint: true, openWorldHint: false], description: "List installed code bundle containers (id/name/namespace). Read-only.",
         inputSchema: [type: "object", properties: [:]]],
        [name: "hub_delete_bundle", annotations: [title: "Delete Bundle", readOnlyHint: false, destructiveHint: true, idempotentHint: true, openWorldHint: false], description: "Delete a code bundle container by id (verified by re-list). confirm:true required.",
         inputSchema: [type: "object", properties: [bundleId: [type: "string"], confirm: [type: "boolean"]], required: ["bundleId", "confirm"]]],
        [name: "hub_get_info", annotations: [title: "Get Info", readOnlyHint: true, idempotentHint: true, openWorldHint: false], inputSchema: [type: "object", properties: [peer: [type: "boolean", description: "Also check that the MCP server's own endpoint answers (peerEndpoint.available); this calls its tools/list over loopback, so do not poll with it."]]], description: "Hub model/firmware/memory, the lastSelfDeploy record of the latest hub_update_app (with ageMs; hub_update_package does not write it), the latest package deployment (check its hold), and loopback wedge health including the last automatic reboot. The package is never restored automatically."],
        [name: "hub_list_apps", annotations: [title: "List Apps", readOnlyHint: true, idempotentHint: true, openWorldHint: false], description: "List Apps Code types (scope='types') or installed apps.",
         inputSchema: [type: "object", properties: [scope: [type: "string", enum: ["types", "instances"]]]]],
        [name: "hub_list_libraries", annotations: [title: "List Libraries", readOnlyHint: true, idempotentHint: true, openWorldHint: false], inputSchema: [type: "object", properties: [:]], description: "List libraries (id/name/namespace/version summaries)."],
        [name: "hub_get_jobs", annotations: [title: "Get Jobs", readOnlyHint: true, idempotentHint: true, openWorldHint: false], inputSchema: [type: "object", properties: [:]], description: "List scheduled + running hub jobs."],
        [name: "hub_read_file", annotations: [title: "Read File", readOnlyHint: true, idempotentHint: true, openWorldHint: false], description: "Read a File Manager file (chunked).",
         inputSchema: [type: "object", properties: [fileName: [type: "string"], offset: [type: "integer"], length: [type: "integer"]], required: ["fileName"]]],
        [name: "hub_write_file", annotations: [title: "Write File", readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: false], description: "Write a File Manager file. confirm:true required.",
         inputSchema: [type: "object", properties: [fileName: [type: "string"], content: [type: "string"], confirm: [type: "boolean"]], required: ["fileName", "content", "confirm"]]],
        [name: "hub_create_backup", annotations: [title: "Create Backup", readOnlyHint: false, destructiveHint: false, idempotentHint: false, openWorldHint: false], description: "Trigger a hub DB backup. confirm:true required.",
         inputSchema: [type: "object", properties: [confirm: [type: "boolean"]], required: ["confirm"]]],
        [name: "hub_manage_variables", annotations: [title: "Manage Variables", readOnlyHint: false, destructiveHint: true, idempotentHint: true, openWorldHint: false], description: "Read/set hub variables (hub_get_variable / hub_set_variable). Call with no action to list sub-tools.",
         inputSchema: [type: "object", properties: [action: [type: "string", enum: ["hub_get_variable", "hub_set_variable"]], name: [type: "string"], value: [type: "string"], confirm: [type: "boolean"]]]]
    ]
}

// Parse a loopback POST response body (String) into a Map/List. hubPostJson returns [status,data];
// data is the raw body String. Mirrors hubInternalPostJson's parse step.
def _parseJsonBody(data) {
    if (data == null) return null
    if (data instanceof Map || data instanceof List) return data
    try { return new groovy.json.JsonSlurper().parseText(data.toString()) }
    catch (Exception e) { log.error "_parseJsonBody: response not JSON: ${data.toString()?.take(200)}"; return null }
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
    try { httpGet(params) { resp -> out = respText(resp) }; noteLoopback(true) }
    catch (Exception e) {
        noteLoopback(httpStatusOf(e) != null)
        log.error "hubGet ${path}: ${e.message}"
    }
    return out
}

// The response status carried on a thrown Hubitat HTTP exception, or null on a real transport
// failure. Mirrors the e.response.status capture hubGetStatus already does. Non-private so specs
// can exercise it directly -- the harness supplies httpGet/httpPost, so the surrounding helpers'
// catch paths are not otherwise reachable from a test.
Integer httpStatusOf(Exception e) {
    try {
        def resp = e.response
        return resp?.status as Integer
    } catch (Exception ignore) { return null }
}

// ---- wedge detection ------------------------------------------------------
// Loopback reads to 127.0.0.1:8080 normally answer in milliseconds. When the platform's web
// thread pool is exhausted -- the failure mode that concurrent purges + deploys produce -- EVERY
// loopback read starts returning "Read timed out" and the hub stops serving its own admin UI. Observed live 2026-09-01: wedged 06:01, still
// dead at 09:33, recovered only by a manual power cycle. Nothing in-process recovers from it, so
// the watchdog has to be able to SEE it. Track consecutive loopback failures plus the last
// success so "one flaky read" is distinguishable from "the hub is gone".
private void noteLoopback(boolean ok) {
    synchronized (LOOPBACK_LOCK) {
        if (!ok) {
            // Stamp when THIS streak began so hubLooksWedged has a baseline even if the
            // watchdog has never seen a successful loopback call.
            if (LOOPBACK.failStreak == 0) LOOPBACK.streakStartedAt = now()
            if (LOOPBACK.failStreak < WEDGE_STREAK_MIN) LOOPBACK.failStreak = LOOPBACK.failStreak + 1
            return
        }
        LOOPBACK.failStreak = 0
        LOOPBACK.streakStartedAt = null
        LOOPBACK.lastOkAt = now()
    }
}

// A copy of the wedge counters, for diagnostics. NON-PRIVATE so specs can read them.
Map loopbackState() {
    synchronized (LOOPBACK_LOCK) { return [:] + LOOPBACK }
}

// Both conditions are required on purpose. The streak alone fires on a burst of slow reads from a
// busy-but-alive hub; the silence window alone fires on an idle watchdog that simply made no
// calls. Together they describe only the observed wedge: many consecutive failures AND no
// successful loopback read for minutes.
private boolean hubLooksWedged() {
    synchronized (LOOPBACK_LOCK) {
        if (LOOPBACK.failStreak < WEDGE_STREAK_MIN) return false
        // Fall back to the FIRST failure of the current streak when there has never been a success:
        // a hub already wedged when the watchdog loaded has no lastOk at all.
        Long baseline = (LOOPBACK.lastOkAt ?: LOOPBACK.streakStartedAt) as Long
        if (baseline == null) return false
        return (now() - baseline) > 240000L
    }
}

// One cheap read per health tick, and the live confirmation before an auto-reboot. Routed
// through hubGetStatus so a success updates the wedge counters as a side effect.
// NON-PRIVATE deliberately: a private method's internal callers bypass metaClass dispatch, so a
// spec could not override it and the reboot tests would silently pass on the real implementation
// instead of the stub -- which is exactly how they first passed by accident.
def probeLoopbackAlive() {
    // hubGetStatus, NOT hubGet: hubGet returns null on ANY exception, and Hubitat THROWS on a
    // served 4xx/5xx -- so a hub answering 404 on this endpoint (a firmware that moved it, say)
    // would read as DEAD and get rebooted. The question here is only "did the web stack serve
    // us", so any status at all is a live hub; only a null status is a dead one.
    try { return hubGetStatus("/hub/advanced/freeOSMemory", [:], 10)?.status != null }
    catch (Exception ignore) { return false }
}

private boolean markExpectedDowntime(long ms, String reason) {
    // Returns whether the window is actually READABLE afterwards. Every atomicState write can fail
    // under hub load, and this one is a safety window: a caller that goes on to reboot or start a
    // platform update believing it is suppressed, when it is not, is the failure this guards.
    Long priorUntil = null
    String priorReason = null
    try { priorUntil = atomicState.expectedDownUntil as Long; priorReason = atomicState.expectedDownReason?.toString() } catch (Exception ignore) { priorUntil = null }
    try {
        long until = now() + ms
        atomicState.expectedDownUntil = until
        atomicState.expectedDownReason = reason
        // The EXACT values, not merely non-null: a stale window left by an earlier action reads back
        // non-null, so a null-check would accept a dropped write and report a suppression that does
        // not exist. The reason matters as much as the deadline -- hub_reboot's refusal keys on it.
        Long readBack = atomicState.expectedDownUntil as Long
        String reasonBack = atomicState.expectedDownReason?.toString()
        if (readBack != until || reasonBack != reason) {
            // Put the pair back the way it was. The reason is written before the deadline is
            // verified, so a half-landed claim can leave OUR reason sitting on the PRIOR caller's
            // still-open window -- and a later hub_reboot would then refuse itself, citing a
            // platform update that was never requested.
            try {
                atomicState.expectedDownUntil = priorUntil
                atomicState.expectedDownReason = priorReason
            } catch (Exception ignore) { }
            log.error "E2E Dead-Man Watchdog v3: the expected-downtime window (${reason}) did not persist -- state holds ${readBack}/${reasonBack}. Later reboot requests cannot rely on this window."
            return false
        }
        mcpAdminLog "Expecting the hub to be unreachable for up to ${(ms / 60000) as long} min (${reason}); manual reboot requests respect this window."
        return true
    } catch (Exception e) {
        try {
            atomicState.expectedDownUntil = priorUntil
            atomicState.expectedDownReason = priorReason
        } catch (Exception ignore) { }
        log.error "E2E Dead-Man Watchdog v3: could not persist the expected-downtime window (${reason}): ${e.message}"
        return false
    }
}

// Give a claimed downtime window back when the request it covered did not land -- only if the
// stamp is still ours, so a window a later caller claimed in the meantime is left alone.
private boolean releaseExpectedDowntime(Long ourStamp, Long priorWindow, String priorReason) {
    // Compare-and-restore under the SAME lock every claim takes, or a successor that stamped its
    // own window between the read and the write here has that window silently erased.
    synchronized (REBOOT_LOCK) {
    try {
        Long current = atomicState.expectedDownUntil as Long
        if (current == ourStamp) {
            // Restore the REASON with the window: leaving ours behind on someone else's window
            // would mislabel it -- a platform-update window relabelled "hub_reboot" would stop
            // refusing the reboots it exists to refuse.
            atomicState.expectedDownUntil = priorWindow
            atomicState.expectedDownReason = priorWindow == null ? null : priorReason
            Long back = atomicState.expectedDownUntil as Long
            return back == priorWindow
        }
        return true      // someone else's window: correctly left alone
    } catch (Exception e) {
        log.error "E2E Dead-Man Watchdog v3: could not release the expected-downtime window: ${e.message}"
        return false
    }
    }
}

def adminRebootHub(args) {
    requireConfirm(args)
    // A platform update the hub accepted is downloading and installing for up to 25 minutes; a
    // reboot in that window can interrupt the install. Refuse unless the caller forces it.
    // Read the window, decide, and claim it in ONE step under the lock every claimant takes:
    // checking outside it and stamping after lets a platform update slip in between, and the
    // reboot then overwrites the window it was supposed to respect and POSTs into the install.
    // The prior window is remembered for the REJECTED path only -- a POST the hub answered proves
    // nothing rebooted, so its window goes back. An unanswered POST keeps ours (see below).
    Long updateWindow = null
    String windowReason = null
    Long priorWindow = null
    String priorReason = null
    Long ourStamp = null
    boolean refuse = false
    boolean windowHeld = false
    synchronized (REBOOT_LOCK) {
        try { updateWindow = atomicState.expectedDownUntil as Long; windowReason = atomicState.expectedDownReason?.toString() } catch (Exception ignore) { updateWindow = null }
        if (windowReason == "hub_update_platform" && updateWindow != null && now() < updateWindow && args?.force != true) {
            refuse = true
        } else {
            priorWindow = updateWindow
            priorReason = windowReason
            // Before the POST: once it lands the hub may go before we get to run again.
            windowHeld = markExpectedDowntime(600000L, "hub_reboot")
            try { ourStamp = atomicState.expectedDownUntil as Long } catch (Exception ignore) { ourStamp = null }
        }
    }
    if (refuse) {
        return [success: false, refused: true, expectedDownUntil: updateWindow,
                error: "a platform update was accepted ${((now() - (updateWindow - 1500000L)) / 1000) as long}s ago and the hub is downloading/installing it -- a reboot now could interrupt the install. Pass force:true to reboot anyway."]
    }
    mcpAdminLog "Rebooting the hub (POST /hub/reboot)."
    def resp = hubPostForm("/hub/reboot", [:])
    Integer st = null
    try { st = resp?.status as Integer } catch (Exception ignore) { st = null }
    if (st != null && st >= 200 && st < 400) {
        def ok = [success: true, status: st,
                  message: "Hub reboot initiated; the hub is unreachable for 1-3 minutes.",
                  response: resp?.data?.toString()?.take(200)]
        if (!windowHeld) {
            ok.windowPersisted = false
            ok.note = "The reboot landed, but its expected-downtime window could not be recorded."
        }
        return ok
    }
    if (st == null) {
        // AMBIGUOUS: a hub that accepted the reboot goes down mid-response, so "no status" is the
        // signature of a reboot that LANDED as much as of one that never did. Keep the window --
        // releasing it can encourage a second reboot while the hub is already restarting, and the
        // caller to retry a reboot that already happened.
        return [success: false, ambiguous: true, status: null, expectedDownUntil: ourStamp,
                error: "reboot POST got no response from the hub -- whether it landed is UNKNOWN (a hub that accepted it goes down before answering).",
                note: "The expected-downtime window remains recorded; do not repeat the reboot. Check hub_get_info in a few minutes: a rising uptime means it rebooted. If loopback HTTP is wedged the POST never landed and the hub needs a physical power cycle."]
    }
    boolean released = releaseExpectedDowntime(ourStamp, priorWindow, priorReason)
    return [success: false, status: st,
            error: "reboot POST returned status ${st}",
            windowReleased: released,
            note: released ? "The hub answered, so it did not reboot; the downtime window was released."
                           : "The hub answered, so it did not reboot, but the downtime window could NOT be cleared -- it remains recorded until it expires."]
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
    // A status of any kind (incl. the 302 the forcedelete endpoints answer with) means the web
    // stack served us; only a null status is the wedge signature.
    noteLoopback(out.status != null)
    return out
}

private int hubPostTimeoutSec(String path) { path == "/hub/reboot" ? 20 : 420 }

Map hubPostForm(String path, Map body) {
    // 420s: saving the ~1.6MB MCP server source is a large form POST that can be slow.
    def params = [uri: "http://127.0.0.1:8080", path: path, body: body,
                  requestContentType: "application/x-www-form-urlencoded",
                  textParser: true, ignoreSSLIssues: true, timeout: hubPostTimeoutSec(path)]
    def headers = [Connection: "keep-alive"]
    def cookie = secCookie()
    if (cookie) headers.Cookie = cookie
    params.headers = headers
    Map out = [status: null, data: null]
    try { httpPost(params) { resp -> out = [status: resp.status, data: respText(resp)] } }
    catch (Exception e) {
        // Same as hubGet: a thrown 4xx/5xx still means the hub served us. Keep the status so the
        // caller sees it AND so the wedge streak is not inflated by an answered error.
        Integer st = httpStatusOf(e)
        if (st != null) out = [status: st, data: null]
        log.error "hubPostForm ${path}: ${e.message}"
    }
    noteLoopback(out.status != null)
    return out
}

// noteLoopback here too: without it a watchdog doing mostly JSON POSTs could accumulate a false
// failure streak from the other helpers and trip the wedge detector while the hub is answering.
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
    catch (Exception e) {
        Integer st = httpStatusOf(e)
        if (st != null) out = [status: st, data: null]
        log.error "hubPostJson ${path}: ${e.message}"
    }
    noteLoopback(out.status != null)
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
void mcpAdminLog(String m) { logInfo "[mcp-admin] ${m}" }
void logInfo(String m)  { log.info  "[watchdog-v3] ${m}" }
void logDebug(String m) { if (settings?.debugLogging != false) log.debug "[watchdog-v3] ${m}" }
