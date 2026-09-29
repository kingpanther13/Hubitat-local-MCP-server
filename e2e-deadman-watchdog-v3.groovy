/**
 * Experimental package deploy controller for the E2E hub, installed alongside v2.
 * V2 remains the recovery controller. V3 never schedules restore or reboot.
 * Requires an exclusive hub lease and a fresh backup before manual deployment.
 */
definition(
    name: "E2E Dead-Man Watchdog v3",
    namespace: "mcp",
    author: "kingpanther13",
    description: "Experimental MCP package deployment with persisted progress. Install alongside watchdog v2 on the E2E test hub only.",
    category: "Utility",
    iconUrl: "https://raw.githubusercontent.com/hubitat/HubitatPublic/master/app-dev/icon.png",
    iconX2Url: "https://raw.githubusercontent.com/hubitat/HubitatPublic/master/app-dev/icon.png",
    oauth: [displayName: "E2E Dead-Man Watchdog v3", displayLink: ""],
    singleInstance: true
)

preferences {
    page(name: "mainPage", title: "E2E Dead-Man Watchdog v3", install: true, uninstall: true) {
        section("Package deployment") {
            input "debugLogging", "bool", title: "Debug logging", defaultValue: false, required: false
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

def installed() { initialize() }
def updated() { unschedule(); initialize() }
def initialize() {
    if (!state.accessToken) {
        try { createAccessToken() }
        catch (Exception ignored) { log.warn "Enable OAuth for the v3 code class, then save this app to create its endpoint." }
    }
}

def handleMcpGet() {
    return render(status: 405, contentType: "application/json",
                  data: groovy.json.JsonOutput.toJson(jsonRpcError(null, -32600,
                      "This MCP endpoint is request-response only (POST). SSE/GET streaming is not supported.")))
}

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
        instructions: "Experimental package deployment and progress. Reserve the E2E hub; keep watchdog v2 available for recovery."
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
    if (toolName == "hub_get_package_deployment") return adminGetPackageDeployment(args)
    if (!(toolName in ["hub_update_package", "hub_set_package_deployment", "hub_list_app_instances"]))
        throw new IllegalArgumentException("Unknown tool: ${toolName}")
    if (settings?.hubSecurityEnabled == true && secCookie() == null)
        return [success: false, error: "Hub Security authentication failed; check v3 settings"]
    switch (toolName) {
        case "hub_update_package": return adminUpdatePackage(args)
        case "hub_set_package_deployment": return adminSetPackageDeployment(args)
        case "hub_list_app_instances": return adminListAppInstances(args)
    }
}

private void requireConfirm(args) {
    if (args?.confirm != true) {
        throw new IllegalArgumentException("SAFETY CHECK FAILED: set confirm=true to use this write tool.")
    }
}


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
    String binding = packageSourceHash(groovy.json.JsonOutput.toJson([ref: ref, libraries: libraries]))
    def prior = atomicState.packageDeployment
    if (prior?.requestId == requestId) {
        if (prior.binding != binding) return [success: false, error: "requestId is already bound to different inputs"]
        return adminGetPackageDeployment([requestId: requestId])
    }
    if (!packageWatchdogIdle()) return [success: false, error: "Manual package probe requires an idle, disarmed v2 recovery flag with completed recovery"]
    synchronized (PACKAGE_DEPLOY_LOCK) {
        def current = atomicState.packageDeployment
        if (current?.requestId == requestId) {
            if (current.binding != binding) return [success: false, error: "requestId is already bound to different inputs"]
            return adminGetPackageDeployment([requestId: requestId])
        }
        if (current?.hold == true || current?.workerActive == true)
            return [success: false, error: "A v3 deployment is still held; nothing was scheduled"]
        atomicState.packageDeployment = [requestId: requestId, ref: ref, binding: binding,
            libraries: libraries, hold: true, phase: "queued", status: "queued", startedAt: now(),
            stageStartedAt: now(), updatedAt: now(), history: [], workerActive: false]
        def back = atomicState.packageDeployment
        if (back?.requestId != requestId || back.hold != true)
            return [success: false, error: "Could not persist deployment safety hold; nothing was scheduled"]
    }
    try {
        runIn(1, "runWatchdogPackageDeploy", [data: [requestId: requestId]])
    } catch (Exception ignored) {
        def job = [:] + atomicState.packageDeployment
        packageStage(job, "stopped", "", "Could not schedule deployment. Safety hold retained.")
    }
    return adminGetPackageDeployment([requestId: requestId])
}

def adminGetPackageDeployment(Map args) {
    def job = atomicState.packageDeployment
    if (!job || job.requestId != args.requestId?.toString())
        return [success: false, error: "No deployment with this requestId"]
    return [success: !(job.phase in ["stopped", "abandoned"]), requestId: job.requestId, ref: job.ref,
        phase: job.phase, status: job.phase, component: job.component, hold: job.hold,
        startedAt: job.startedAt, updatedAt: job.updatedAt, elapsedMs: now() - (job.startedAt as long),
        stageElapsedMs: now() - (job.stageStartedAt as long), workerActive: job.workerActive == true,
        error: job.error, history: job.history ?: []]
}

def adminSetPackageDeployment(Map args) {
    requireConfirm(args)
    if (!args.requestId) throw new IllegalArgumentException("requestId is required to release a package deployment")
    synchronized (PACKAGE_DEPLOY_LOCK) {
        def stored = atomicState.packageDeployment
        if (stored?.requestId != args.requestId?.toString() || stored.workerActive == true || args.endpointVerified != true)
            return [success: false, error: "Matching idle operation and verification of MCP, v2, and v3 endpoints are required"]
        if (!(args.abandon == true ? stored.phase == "stopped" : stored.phase == "awaiting_verification"))
            return [success: false, error: "The operation is not ready for release"]
        def job = [:] + stored
        job.workerActive = true
        atomicState.packageDeployment = job
    }
    def job = [:] + atomicState.packageDeployment
    boolean release = false
    try {
        if (args.abandon != true && (!packageLibrariesMatch(job) || !job.apps.every { packageAppMatches(it) }))
            return [success: false, error: "Source verification changed; safety hold retained"]
        release = true
    } catch (Exception ignored) {
        return [success: false, error: "Could not recheck installed sources; safety hold retained"]
    } finally {
        synchronized (PACKAGE_DEPLOY_LOCK) {
            if (atomicState.packageDeployment?.requestId == job.requestId) {
                job.workerActive = false
                job.hold = !release
                packageStage(job, release ? (args.abandon == true ? "abandoned" : "complete") : "stopped", "",
                             release ? null : "Completion verification failed; safety hold retained")
            }
        }
    }
    return adminGetPackageDeployment(args)
}

def runWatchdogPackageDeploy(Map data) {
    Map job
    synchronized (PACKAGE_DEPLOY_LOCK) {
        def stored = atomicState.packageDeployment
        if (stored?.requestId != data.requestId?.toString() || stored.hold != true || stored.workerActive == true ||
            stored.phase in ["complete", "abandoned", "stopped", "awaiting_verification"]) return
        job = [:] + stored
        job.workerActive = true
        atomicState.packageDeployment = job
    }
    try {
        if (job.phase == "queued") {
            packageStage(job, "preflight")
            prepareWatchdogPackage(job)
            def baseline = packageLibrarySnapshot(job)
            if (!baseline.readable) throw new IllegalStateException("Cannot read the complete library baseline; bundle was not submitted")
            if (!baseline.matches) {
                packageStage(job, "installing_bundle", "MCP libraries")
                job.verifyUntil = now() + 600000L
                atomicState.packageDeployment = job
                adminInstallBundle([importUrl: job.bundleUrl, confirm: true])
            }
            job.verifyUntil = now() + 600000L
            packageStage(job, "verifying_libraries", "MCP libraries")
        }
        if (job.phase in ["installing_bundle", "verifying_libraries"]) {
            if (!packageLibrariesMatch(job)) {
                packageWait(job, "verifying_libraries", "MCP libraries")
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
                atomicState.packageDeployment = job
                def response = hubPostForm("/app/ajax/update", [id: target.id, version: before.version, source: source])
                def result = _parseJsonBody(response?.data)
                if (result instanceof Map && result.status == "error")
                    throw new IllegalStateException("App compile/save rejected: ${result.errorMessage ?: 'no diagnostic'}")
                job.verifyUntil = now() + 600000L
                packageStage(job, "verifying_app", target.name)
            }
            if (!(job.phase in ["updating_app", "verifying_app"]))
                throw new IllegalStateException("Interrupted before save; inspect operation before retrying")
            if (!packageAppMatches(target)) {
                packageWait(job, "verifying_app", target.name)
                return
            }
            job.appIndex = (job.appIndex as int) + 1
            packageStage(job, "next_app")
        }
        packageStage(job, "awaiting_verification", "Original MCP and watchdog endpoints")
    } catch (Exception e) {
        packageStage(job, "stopped", job.component?.toString() ?: "", e.message?.take(1000) ?: "Deployment failed")
    } finally {
        job.workerActive = false
        atomicState.packageDeployment = job
    }
}

def prepareWatchdogPackage(Map job) {
    if (!packageWatchdogIdle())
        throw new IllegalStateException("Manual package probe requires an idle, disarmed v2 recovery flag with completed recovery")
    String base = "https://raw.githubusercontent.com/kingpanther13/Hubitat-local-MCP-server"
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
        if (matches.size() != 1) throw new IllegalStateException("Library ${expected.name} must already exist uniquely")
        return expected + [id: matches[0].id.toString()]
    }
    job.bundleUrl = "${base}/bundle-artifacts/shas/${job.ref}/mcp-libraries.zip".toString()
}

boolean packageWatchdogIdle() {
    try {
        def flag = readFlag()
        return flag instanceof Map && flag.armed == false &&
            (flag.intent != "disarm" || (flag.runId != null && flag.restoreFor?.toString() == flag.runId.toString() && flag.restoreResult == "restored"))
    } catch (Exception ignored) { return false }
}

def packageLibrariesMatch(Map job) {
    def snapshot = packageLibrarySnapshot(job)
    return snapshot.readable && snapshot.matches
}

Map packageLibrarySnapshot(Map job) {
    try {
        def installed = _parseJsonBody(hubGet("/hub2/userLibraries", [:]))
        if (!(installed instanceof List)) return [readable: false, matches: false]
        boolean allMatch = true
        for (def expected : job.libraries) {
            def matches = installed.findAll { it.namespace == "mcp" && it.name == expected.name }
            if (matches.size() != 1 || matches[0].id.toString() != expected.id) return [readable: false, matches: false]
            def data = _parseJsonBody(hubGet("/library/list/single/data/${expected.id}".toString(), [:]))
            if (!(data instanceof List) || data.size() != 1 || !(data[0].source instanceof String))
                return [readable: false, matches: false]
            if (packageSourceHash(data[0].source) != expected.sha256) allMatch = false
        }
        return [readable: true, matches: allMatch]
    } catch (Exception ignored) { return [readable: false, matches: false] }
}

def packageAppMatches(Map target) {
    try {
        def types = _parseJsonBody(hubGet("/hub2/userAppTypes", [:]))
        if (!(types instanceof List)) return false
        def matches = types.findAll { it.namespace == "mcp" && it.name == target.name }
        if (matches.size() != 1 || matches[0].id.toString() != target.id) return false
        def data = _parseJsonBody(hubGet("/app/ajax/code", [id: target.id]))
        return data instanceof Map && data.source instanceof String && data.version?.toString()?.isLong() &&
            data.version.toString().toLong() > (target.beforeVersion as long) && packageSourceHash(data.source) == target.sha256
    } catch (Exception ignored) { return false }
}

def packageWait(Map job, String phase, String component) {
    if (now() >= (job.verifyUntil as long)) {
        packageStage(job, "stopped", component, "Timed out verifying ${component}; no write was retried. Safety hold retained.")
    } else {
        packageStage(job, phase, component)
        runIn(10, "runWatchdogPackageDeploy", [data: [requestId: job.requestId]])
    }
}

def packageStage(Map job, String phase, String component = "", String error = null) {
    long stamp = now()
    if (job.phase != phase || job.component != component) {
        job.stageStartedAt = stamp
        job.history = ((job.history ?: []) + [[phase: phase, component: component, at: stamp]]).takeRight(40)
    }
    job.phase = phase
    job.component = component
    job.updatedAt = stamp
    job.error = error
    atomicState.packageDeployment = job
    def back = atomicState.packageDeployment
    if (back?.requestId != job.requestId || back.phase != phase || back.hold != job.hold)
        throw new IllegalStateException("Could not persist deployment stage; no further writes are allowed")
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

def adminListAppInstances(args) {
    String raw = hubGet("/hub2/appsList", [:])
    String noList = "Nothing was changed: the hub did not answer /hub2/appsList. Check hub health through v2 before retrying this read."
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

def getAdminToolDefinitions() {
    return [
        [name: "hub_update_package", annotations: [title: "Deploy MCP Package", readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: true],
         description: "Start one background repair of the existing MCP package at an immutable commit. Manual E2E-hub probe only: reserve the hub, verify MCP, v2, and v3 endpoints, back up, and disarm recovery first. Holds further v3 deployments until explicit endpoint verification. V2 remains independent. Never updates the watchdog or OAuth. Submit once, then poll hub_get_package_deployment with the same requestId; confirm:true required.",
         inputSchema: [type: "object", properties: [requestId: [type: "string"], ref: [type: "string", description: "Full 40-character commit SHA."],
             libraries: [type: "array", items: [type: "object", properties: [name: [type: "string"], sha256: [type: "string"]], required: ["name", "sha256"]]],
             confirm: [type: "boolean"]], required: ["requestId", "ref", "libraries", "confirm"]]],
        [name: "hub_get_package_deployment", annotations: [title: "Get Package Deployment", readOnlyHint: true, idempotentHint: true, openWorldHint: false],
         description: "Read persisted package stages, elapsed time, errors, and safety hold without contacting hub HTTP. A stopped or missing operation never authorizes replaying the install.",
         inputSchema: [type: "object", properties: [requestId: [type: "string"]], required: ["requestId"]]],
        [name: "hub_set_package_deployment", annotations: [title: "Release Package Deployment", readOnlyHint: false, destructiveHint: true, idempotentHint: false, openWorldHint: false],
         description: "Release a package safety hold after verifying original MCP, v2, and v3 endpoints/tokens and unchanged app instances. Rechecks code hashes before completing. Explicit abandon:true may release a stopped job after operator recovery; use only after every endpoint is healthy. confirm:true and endpointVerified:true required.",
         inputSchema: [type: "object", properties: [requestId: [type: "string"], endpointVerified: [type: "boolean"], abandon: [type: "boolean"],
             confirm: [type: "boolean"]], required: ["requestId", "endpointVerified", "confirm"]]],
        [name: "hub_list_app_instances", annotations: [title: "List App Instances", readOnlyHint: true, idempotentHint: true, openWorldHint: false], inputSchema: [type: "object", properties: [:]], description: "Every running app INSTANCE (flattened /hub2/appsList with parentId) -- the full app inventory. DISTINCT from hub_list_apps (Apps Code CLASSES, which resolve_class_id depends on). Read-only."],
    ]
}

def _parseJsonBody(data) {
    if (data == null) return null
    if (data instanceof Map || data instanceof List) return data
    try { return new groovy.json.JsonSlurper().parseText(data.toString()) }
    catch (Exception e) { log.error "_parseJsonBody: response not JSON: ${data.toString()?.take(200)}"; return null }
}

Map readFlag() {
    String txt = readHubFileText("e2e-deadman-v2.json")
    if (txt == null) return null
    try {
        def parsed = new groovy.json.JsonSlurper().parseText(txt)
        if (!(parsed instanceof Map)) { log.error "readFlag: flag JSON is not a JSON object -- ignoring."; return null }
        return (Map) parsed
    }
    catch (Exception e) { log.error "readFlag: flag is not valid JSON: ${e.message}"; return null }
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

String hubGet(String path, Map query, int timeoutSec = 30) {
    def params = [uri: "http://127.0.0.1:8080", path: path, query: query,
                  textParser: true, ignoreSSLIssues: true, timeout: timeoutSec]
    def cookie = secCookie()
    if (cookie) params.headers = [Cookie: cookie]
    String out = null
    try { httpGet(params) { resp -> out = respText(resp) } }
    catch (Exception e) {
        log.error "hubGet ${path}: ${e.message}"
    }
    return out
}

Integer httpStatusOf(Exception e) {
    try {
        def resp = e.response
        return resp?.status as Integer
    } catch (Exception ignore) { return null }
}

Map hubPostForm(String path, Map body) {
    def params = [uri: "http://127.0.0.1:8080", path: path, body: body,
                  requestContentType: "application/x-www-form-urlencoded",
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
        log.error "hubPostForm ${path}: ${e.message}"
    }
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
    catch (Exception e) {
        Integer st = httpStatusOf(e)
        if (st != null) out = [status: st, data: null]
        log.error "hubPostJson ${path}: ${e.message}"
    }
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
            def sc = resp?.headers?.'Set-Cookie'
            if (sc instanceof List) { sc = sc ? sc[0] : null }
            cookie = sc?.toString()?.split(';')?.getAt(0)
        }
    } catch (Exception e) { log.error "secCookie: hub security auth failed: ${e.message}" }
    if (cookie) { atomicState.secCookie = cookie; atomicState.secCookieExp = now() + (30 * 60 * 1000) }
    return cookie
}

void mcpAdminLog(String m) { logInfo m }
void logInfo(String m) { log.info "[watchdog-v3] ${m}" }
void logDebug(String m) { if (settings?.debugLogging == true) log.debug "[watchdog-v3] ${m}" }
