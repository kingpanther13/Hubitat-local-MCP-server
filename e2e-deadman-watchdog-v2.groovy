/**
 * E2E Dead-Man Watchdog v2 (issue #209 e2e safety net -- package-aware)
 *
 * v2 of the standalone e2e recovery app. The original "E2E Dead-Man Watchdog" is left
 * installed but ORPHANED: e2e only arms THIS app (its own Apps Code class + its own flag
 * file e2e-deadman-v2.json), so v1 sees no flag of its own and sits idle/harmless.
 *
 * What v2 adds: it restores the WHOLE main package -- every #include'd library FIRST
 * (POST /library/saveOrUpdateJson), then the app(s) (POST /app/ajax/update) -- from LOCAL
 * File Manager cache files that CI populated from main at the healthy START of the run
 * (importUrl + hub_get_source). It restores on BOTH a clean disarm (intent=disarm) and a
 * missed-deadline fire, and writes restoreResult/restoreFor back into the flag so CI can
 * confirm the reinstall landed.
 *
 * Still standalone: own Apps Code class, no #include, no oauth{}/mappings{}. Acts only via a
 * 1-minute local timer + a File Manager flag + hub-loopback admin POSTs. No cloud, no token,
 * no dependency on the MCP server being compilable -- so a PR that bricks the server cannot
 * block recovery.
 */
definition(
    name: "E2E Dead-Man Watchdog v2",
    namespace: "mcp",
    author: "kingpanther13",
    description: "Package-aware autonomous on-hub watchdog: restores main (libraries first, app last) from a local cache on a missed disarm OR a clean disarm. No cloud endpoint, no OAuth.",
    category: "Utility",
    iconUrl: "https://raw.githubusercontent.com/hubitat/HubitatPublic/master/app-dev/icon.png",
    iconX2Url: "https://raw.githubusercontent.com/hubitat/HubitatPublic/master/app-dev/icon.png",
    singleInstance: true
)
// NOTE: deliberately NO oauth{} and NO mappings{} -- this app must never expose a cloud
// endpoint. Driven only by a local timer + a File Manager flag.

preferences {
    page(name: "mainPage", title: "E2E Dead-Man Watchdog v2", install: true, uninstall: true) {
        section("Watchdog") {
            input "flagFileName", "text", title: "Flag file name (File Manager)", defaultValue: "e2e-deadman-v2.json", required: false
            input "debugLogging", "bool", title: "Debug logging", defaultValue: true, required: false
            paragraph statusText()
        }
        section("Hub Security (only if enabled on this hub)") {
            input "hubSecurityEnabled", "bool", title: "Hub Security enabled?", defaultValue: false, required: false
            input "hubSecurityUser", "text", title: "Hub Security username", required: false
            input "hubSecurityPassword", "password", title: "Hub Security password", required: false
        }
    }
}

def installed() { initialize() }
def updated()   { unschedule(); initialize() }

def initialize() {
    runEvery1Minute("checkDeadman")
    logInfo "E2E Dead-Man Watchdog v2 initialized; checking '${flagFileName ?: 'e2e-deadman-v2.json'}' every minute."
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
        if (trigger == "fire" && attempts < 5) {
            flag.lastAttemptAt = now()
            log.warn "E2E Dead-Man Watchdog v2 restore attempt ${attempts} FAILED; staying armed to retry next check. ${res.detail}"
            if (!writeFlag(flag)) {
                log.error "E2E Dead-Man Watchdog v2: could not persist retry state (attempt ${attempts}); the still-armed flag retries next check regardless."
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
    // A real code-class save advances the version; a reported success that doesn't bump it means
    // the push didn't land (don't claim 'restored' on a silent no-op).
    def newVersion = appCodeVersion(classId)
    boolean confirmed = false
    String oldStr = oldVersion?.toString()?.trim()
    String newStr = newVersion?.toString()?.trim()
    if (newStr?.isLong() && oldStr?.isLong()) {
        confirmed = newStr.toLong() > oldStr.toLong()
    } else {
        log.error "restoreApp: version not numeric (old=${oldVersion}, new=${newVersion}); cannot confirm the advance."
    }
    logInfo "restoreApp: pushed ${source.length()} chars to class ${classId}; reported=${reported}, version ${oldVersion}->${newVersion}, confirmed=${confirmed}"
    return confirmed
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
        // saveOrUpdateJson returns {success, id, version}; success is absent-or-true on the happy path.
        ok = (parsed?.success != false) && (parsed?.id != null)
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

// ---- flag file IO (File Manager, hub-local, no cloud) ----
Map readFlag() {
    String txt = readHubFileText(flagFileName ?: "e2e-deadman-v2.json")
    if (txt == null) return null
    try { return (Map) new groovy.json.JsonSlurper().parseText(txt) }
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
String hubGet(String path, Map query) {
    def params = [uri: "http://127.0.0.1:8080", path: path, query: query,
                  textParser: true, ignoreSSLIssues: true, timeout: 30]
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
            cookie = resp?.headers?.'Set-Cookie'?.split(';')?.getAt(0)
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

void logInfo(String m)  { log.info  "[deadman-v2] ${m}" }
void logDebug(String m) { if (settings?.debugLogging != false) log.debug "[deadman-v2] ${m}" }
