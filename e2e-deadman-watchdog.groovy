/**
 * E2E Dead-Man Watchdog (issue #209 e2e safety net)
 *
 * A STANDALONE recovery app for the e2e test hub. Its only job: if an e2e session
 * arms it and then fails to disarm within a window, it autonomously restores the
 * MCP Rule Server app's code from a known-good backup file -- recovering a hub that
 * a bad self-deploy bricked, WITHOUT any human on the LAN and WITHOUT a cloud call.
 *
 * Why standalone (not a child of, or #include'd by, the main app):
 *   - It must survive a brick OR deletion of the main app. A child instance dies with
 *     its parent; shared/#include'd code dies with the main app's compile. This app has
 *     its OWN Apps Code class, no #include, and no dependency on the main app loading.
 *   - It has NO OAuth and NO mappings: it is never called from outside. It runs a local
 *     timer and acts entirely through hub-internal loopback calls. Zero new cloud
 *     endpoint, zero new token -- nothing to attack.
 *
 * Contract (the "dead-man's switch"):
 *   - ARM:    something (CI, via the main app's hub_write_file) writes the flag file
 *             {armed:true, deadline:<epoch ms>, classId:<MCP app class id>, restoreFileName:<File Manager .groovy>}.
 *   - WATCH:  this app checks the flag every minute (its own stable schedule).
 *   - DISARM: the session writes {armed:false} when it has cleanly finished+verified.
 *   - FIRE:   if the flag is still armed past its deadline, restore the target app from
 *             restoreFileName, then disarm (records firedAt/fireResult). Failure to
 *             disarm in time IS the trigger -- a bricked main app can't disarm itself.
 *
 * The window is data-driven (deadline lives in the flag), so a 2-minute test window and
 * a 20-minute production window need no code change.
 *
 * Intended ONLY for the e2e/level99 hub. A normal hub has LAN web-UI recovery and does
 * not want a standing auto-restore.
 */
definition(
    name: "E2E Dead-Man Watchdog",
    namespace: "mcp",
    author: "kingpanther13",
    description: "Autonomous on-hub watchdog that auto-restores the MCP Rule Server app if an e2e session fails to disarm within its window. No cloud endpoint, no OAuth.",
    category: "Utility",
    iconUrl: "https://raw.githubusercontent.com/hubitat/HubitatPublic/master/app-dev/icon.png",
    iconX2Url: "https://raw.githubusercontent.com/hubitat/HubitatPublic/master/app-dev/icon.png",
    singleInstance: true
)
// NOTE: deliberately NO oauth{} and NO mappings{} -- this app must never expose a cloud
// endpoint. It is driven only by a local timer + a File Manager flag.

preferences {
    page(name: "mainPage", title: "E2E Dead-Man Watchdog", install: true, uninstall: true) {
        section("Watchdog") {
            input "flagFileName", "text", title: "Flag file name (File Manager)", defaultValue: "e2e-deadman.json", required: false
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
    // Finest built-in cadence. A 2-min window fires on the 2nd-ish check; a 20-min
    // window fires within a minute of its deadline. Registered here in our OWN
    // initialize() -- which is minimal and never self-modified, so it won't throw and
    // leave us unscheduled (the failure mode that makes an in-MAIN-app watchdog useless).
    runEvery1Minute("checkDeadman")
    logInfo "E2E Dead-Man Watchdog initialized; checking '${flagFileName ?: 'e2e-deadman.json'}' every minute."
}

// ---- the dead-man check (runs every minute) ----
def checkDeadman() {
    def flag = readFlag()
    if (flag == null) { logDebug "no flag file -- idle"; return }
    if (flag.armed != true) { logDebug "flag present but disarmed -- idle"; return }

    def deadline = (flag.deadline != null) ? (flag.deadline as long) : 0L
    if (now() < deadline) {
        logDebug "armed; ${((deadline - now()) / 1000) as long}s until deadline"
        return
    }

    // Armed AND past deadline -> the session never disarmed -> FIRE.
    log.warn "E2E Dead-Man Watchdog FIRING: armed flag expired ${((now() - deadline) / 1000) as long}s ago. Restoring app class ${flag.classId} from '${flag.restoreFileName}'."
    boolean ok = restoreApp(flag.classId?.toString(), flag.restoreFileName?.toString())

    // Disarm unconditionally after a fire attempt so we don't loop-restore every minute.
    flag.armed = false
    flag.firedAt = now()
    flag.fireResult = ok ? "restored" : "failed"
    if (!writeFlag(flag)) {
        log.error "E2E Dead-Man Watchdog: FAILED to persist disarm flag after fire (fireResult=${flag.fireResult}); the still-armed flag will re-fire next check until the write succeeds."
    }
    log.warn "E2E Dead-Man Watchdog fire complete: ${flag.fireResult}."
}

// ---- restore: read the backup from File Manager, push it to the target app's class ----
boolean restoreApp(String classId, String restoreFileName) {
    if (!classId || !restoreFileName) {
        log.error "restoreApp: missing classId (${classId}) or restoreFileName (${restoreFileName})"
        return false
    }
    String source = readHubFileText(restoreFileName)
    if (source == null || source.length() < 50) {
        log.error "restoreApp: backup '${restoreFileName}' missing/empty/too-small -- refusing to push it."
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
    // Independent confirmation: a real code-class save advances the version. A
    // reported success that doesn't bump the version means the push didn't land
    // (the watchdog must not claim 'restored' on a silent no-op).
    def newVersion = appCodeVersion(classId)
    boolean confirmed = false
    try { confirmed = (newVersion != null) && ((newVersion as Long) > (oldVersion as Long)) }
    catch (Exception e) { log.error "restoreApp: version compare failed (${oldVersion} -> ${newVersion}): ${e.message}" }
    logInfo "restoreApp: pushed ${source.length()} chars to class ${classId}; reported=${reported}, version ${oldVersion}->${newVersion}, confirmed=${confirmed}"
    return confirmed
}

// Current code version of an app class (needed as the optimistic-lock field on update).
def appCodeVersion(String classId) {
    String body = hubGet("/app/ajax/code", [id: classId])
    if (body == null) return null
    try { return (new groovy.json.JsonSlurper().parseText(body)).version }
    catch (Exception e) { log.error "appCodeVersion: parse failed: ${e.message}"; return null }
}

// ---- flag file IO (File Manager, hub-local, no cloud) ----
Map readFlag() {
    String txt = readHubFileText(flagFileName ?: "e2e-deadman.json")
    if (txt == null) return null
    try { return (Map) new groovy.json.JsonSlurper().parseText(txt) }
    catch (Exception e) { log.error "readFlag: flag is not valid JSON: ${e.message}"; return null }
}

boolean writeFlag(Map flag) {
    try {
        uploadHubFile(flagFileName ?: "e2e-deadman.json", groovy.json.JsonOutput.toJson(flag).getBytes("UTF-8"))
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
        // Missing file is normal (idle) -- downloadHubFile throws when absent.
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
    // 420s mirrors the main server's hubInternalPostForm: restoring the ~1.6MB MCP
    // server source is a large form POST that can be slow on a loaded hub.
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

String respText(resp) {
    // Mirrors the main app's proven _readRespText: with textParser, resp.data is a Reader
    // (or InputStream); .text reads it. Reader has no getText(charset) overload.
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
    // Defensive: this runs when the hub renders the prefs page, including at CREATE time when there
    // is no installed instance yet (settings/atomicState null, no flag file). Never let it throw.
    try {
        def flag = readFlag()
        if (flag == null) return "Status: idle (no flag file)."
        if (flag.armed == true) {
            def secs = (((flag.deadline ?: 0L) as long) - now()) / 1000 as long
            return "Status: ARMED, target class ${flag.classId}, ${secs}s to deadline."
        }
        return "Status: disarmed. Last fire: ${flag.fireResult ?: 'none'}${flag.firedAt ? ' at ' + flag.firedAt : ''}."
    } catch (Exception e) {
        return "Status: (unavailable until installed)."
    }
}

void logInfo(String m)  { log.info  "[deadman] ${m}" }
void logDebug(String m) { if (settings?.debugLogging != false) log.debug "[deadman] ${m}" }
