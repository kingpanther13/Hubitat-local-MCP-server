package server

import spock.lang.Shared
import support.TestChildApp
import support.ToolSpecBase

/**
 * Spec for the importUrl + installAsUserApp + triggerUpdated additions to the
 * install/update tools.
 */
class ToolImportUrlSpec extends ToolSpecBase {

    @Shared private TestChildApp sharedAppStub = new TestChildApp(id: 1L, label: 'MCP')

    // Mutable per-test response state set by stubHttpGet / stubHttpGetThrows.
    // The setupSpec-level httpGet stub reads these; specs swap them between tests.
    @Shared private int nextHttpGetStatus = 200
    @Shared private String nextHttpGetBody = ''
    @Shared private String nextHttpGetContentType = null
    @Shared private Throwable nextHttpGetThrow = null
    @Shared private Map nextHttpGetCaptured = [:]

    def setupSpec() {
        appExecutor.getApp() >> sharedAppStub
        // Install ONE global httpGet stub at spec-class scope. Per-test state is
        // mutated via the stubHttpGet / stubHttpGetThrows helpers below. (Spock
        // dispatches httpGet from the script through the appExecutor mock; the
        // script.metaClass route doesn't intercept this path, hence the
        // appExecutor stub.)
        appExecutor.httpGet(*_) >> { args ->
            if (nextHttpGetThrow) throw nextHttpGetThrow
            Map params = args[0] as Map
            Closure handler = args[1] as Closure
            nextHttpGetCaptured.uri = params?.uri
            def headers = nextHttpGetContentType ? ['Content-Type': nextHttpGetContentType] : [:]
            handler.call([status: nextHttpGetStatus, data: [text: nextHttpGetBody], headers: headers])
        }
    }

    def setup() {
        // Reset per-test state so prior-test stub config doesn't leak.
        nextHttpGetStatus = 200
        nextHttpGetBody = ''
        nextHttpGetContentType = null
        nextHttpGetThrow = null
        nextHttpGetCaptured.clear()
    }

    private void enableWrite() {
        settingsMap.enableWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L
    }

    /**
     * Configure the next httpGet response. Returns the captured map (the URL the
     * helper requested lands in captured.uri after the call).
     */
    private Map stubHttpGet(int status, String body) {
        nextHttpGetStatus = status
        nextHttpGetBody = body
        nextHttpGetCaptured
    }

    private void stubHttpGetThrows(String message) {
        nextHttpGetThrow = new RuntimeException(message)
    }

    /**
     * Stub the install-commit path (_commitUserAppInstall) so installAsUserApp
     * success tests run end-to-end: the configure/json + statusJson reads and the
     * Done POST to /installedapp/update/json. Returns a capture map (donePath /
     * doneBody / configPaths / statusPaths). opts: scheduledJobs, eventSubscriptions,
     * doneStatus, installedFlag (post-commit app.installed read-back, default true),
     * statusThrows (make the post-commit statusJson read throw).
     */
    private Map stubInstallCommit(Map opts = [:]) {
        def cap = [donePath: null, doneBody: null, configPaths: [], statusPaths: []]
        def schedJobs = opts.containsKey('scheduledJobs') ? opts.scheduledJobs : [[name: 'checkDeadman']]
        def subs = opts.containsKey('eventSubscriptions') ? opts.eventSubscriptions : []
        int doneStatus = opts.containsKey('doneStatus') ? (opts.doneStatus as int) : 200
        def sections = opts.containsKey('sections') ? opts.sections : []
        def installedFlag = opts.containsKey('installedFlag') ? opts.installedFlag : true
        boolean statusThrows = opts.containsKey('statusThrows') ? (opts.statusThrows as boolean) : false
        script.metaClass.hubInternalGet = { String path, Map q = null, int t = 30, boolean r = false ->
            if (path.contains('/installedapp/configure/json/')) {
                cap.configPaths << path
                return groovy.json.JsonOutput.toJson([app: [version: 1, installed: installedFlag], configPage: [name: 'mainPage', sections: sections]])
            }
            if (statusThrows && path.contains('/installedapp/statusJson/')) {
                cap.statusPaths << path
                throw new RuntimeException('statusJson read failed (transient)')
            }
            if (path.contains('/installedapp/statusJson/')) {
                cap.statusPaths << path
                return groovy.json.JsonOutput.toJson([appSettings: [], scheduledJobs: schedJobs, eventSubscriptions: subs])
            }
            return '{}'
        }
        script.metaClass.hubInternalPostForm = { String path, Map body, int t = 420, boolean r = false ->
            cap.donePath = path
            cap.doneBody = body
            return [status: doneStatus, data: '', location: '/installedapp/list?section=apps']
        }
        return cap
    }

    // -------- _fetchSourceFromUrl helper --------

    def "_fetchSourceFromUrl: happy path returns body string and uses requested URL"() {
        given:
        def captured = stubHttpGet(200, 'definition(name: "Hello")')

        when:
        def result = script._fetchSourceFromUrl('https://example.com/test.groovy')

        then:
        result == 'definition(name: "Hello")'
        captured.uri == 'https://example.com/test.groovy'
    }

    def "_fetchSourceFromUrl: throws IAE on non-http/https scheme before any I/O"() {
        given:
        // If httpGet ever fires for this test, the stub would set captured.uri.
        stubHttpGet(200, 'should-not-be-reached')

        when:
        script._fetchSourceFromUrl('ftp://example.com/x.groovy')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('http or https')
        nextHttpGetCaptured.uri == null  // scheme check fires before any network attempt
    }

    def "_fetchSourceFromUrl: throws IAE on null url"() {
        when:
        script._fetchSourceFromUrl(null)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('required')
    }

    def "_fetchSourceFromUrl: throws IAE when scheme is uppercase HTTPS (not normalized as a valid scheme today)"() {
        // Lowercase comparison: uppercase schemes are rejected. Pins the toLowerCase
        // normalization step so a future refactor doesn't accidentally bypass it.
        given:
        stubHttpGet(200, 'unused')

        when:
        script._fetchSourceFromUrl('HTTPS://example.com/x.groovy')

        then:
        // The lowercase normalization means uppercase HTTPS IS accepted by the
        // scheme check. This test pins that behavior.
        noExceptionThrown()
    }

    def "_fetchSourceFromUrl: throws IAE on non-String importUrl"() {
        when:
        script._fetchSourceFromUrl(123)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('String')
    }

    def "_fetchSourceFromUrl: throws IAE on non-200 status"() {
        given:
        stubHttpGet(404, 'not found')

        when:
        script._fetchSourceFromUrl('https://example.com/missing.groovy')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('HTTP 404')
    }

    def "_fetchSourceFromUrl: throws IAE when fetch raises"() {
        given:
        stubHttpGetThrows('Connection refused')

        when:
        script._fetchSourceFromUrl('https://example.com/x.groovy')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('fetch failed')
        ex.message.contains('Connection refused')
    }

    def "_fetchSourceFromUrl: throws IAE on empty body"() {
        given:
        stubHttpGet(200, '')

        when:
        script._fetchSourceFromUrl('https://example.com/empty.groovy')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('empty body')
    }

    // -------- importUrl integration: hub_update_app --------

    def "hub_update_app with importUrl fetches via httpGet and POSTs the fetched source"() {
        given:
        enableWrite()
        stubHttpGet(200, 'fetched-source-here')
        // app code endpoint returns the current version so the optimistic-lock path is satisfied
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "source": "old-source", "version": 7}'
        }
        def captured = [:]
        script.metaClass.hubInternalPostJson = { String path, String body ->
            captured.path = path
            captured.body = body
            [success: true, id: 42]
        }
        // backup helper is a no-op stub
        script.metaClass.backupItemSource = { String type, String itemId -> [version: 7, fileName: 'b.json'] }

        when:
        def result = script.toolUpdateAppCode([
            appId: '42',
            importUrl: 'https://raw.example.com/app.groovy',
            confirm: true
        ])

        then:
        result.success == true
        result.sourceMode == 'importUrl'
        result.sourceLength == 'fetched-source-here'.length()
        result.note?.contains('hub-side fetch')  // pins the "no agent transcript" semantic, not just the substring
        captured.path == '/app/saveOrUpdateJson'
        new groovy.json.JsonSlurper().parseText(captured.body).source == 'fetched-source-here'
    }

    // -------- importUrl integration: hub_create_app --------

    def "hub_create_app with importUrl fetches via httpGet and POSTs to /app/saveOrUpdateJson"() {
        given:
        enableWrite()
        stubHttpGet(200, 'definition(name: "FromUrl")')
        def captured = [:]
        script.metaClass.hubInternalPostJson = { String path, String body ->
            captured.path = path
            captured.body = body
            [success: true, id: 7777, version: 1]
        }
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "source": "stub-source", "version": 1}'
        }

        when:
        def result = script.toolInstallApp([
            importUrl: 'https://raw.example.com/new.groovy',
            confirm: true
        ])

        then:
        result.success == true
        result.appId == '7777'
        result.sourceMode == 'importUrl'
        captured.path == '/app/saveOrUpdateJson'
        def sent = new groovy.json.JsonSlurper().parseText(captured.body)
        sent.source == 'definition(name: "FromUrl")'
        sent.id == null
        result.note?.contains('hub-side fetch')  // pins the "no agent transcript" semantic, not just the substring
    }

    // -------- importUrl integration: hub_create_library --------

    def "hub_create_library with importUrl fetches the URL and posts to library/saveOrUpdateJson"() {
        given:
        enableWrite()
        stubHttpGet(200, 'library(name: "Lib")')
        def captured = [:]
        script.metaClass.hubInternalPostJson = { String path, String body ->
            captured.path = path
            captured.body = body
            [success: true, id: 555, version: 1]
        }

        when:
        def result = script.toolInstallLibrary([
            importUrl: 'https://raw.example.com/lib.groovy',
            confirm: true
        ])

        then:
        result.success == true
        result.libraryId == '555'
        result.sourceMode == 'importUrl'
        captured.path == '/library/saveOrUpdateJson'
        captured.body.contains('library(name: \\"Lib\\")')
    }

    // -------- mutual exclusion --------
    // All install/update tools enforce: exactly one of source / sourceFile /
    // importUrl / resave (resave is install-irrelevant). modesSet count > 1 throws.

    def "hub_create_app rejects multiple source modes together"() {
        given:
        enableWrite()

        when:
        script.toolInstallApp([
            source: 'inline',
            importUrl: 'https://x.com/a.groovy',
            confirm: true
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('exactly one')
    }

    def "hub_create_library rejects multiple source modes together"() {
        given:
        enableWrite()

        when:
        script.toolInstallLibrary([
            sourceFile: 'lib.groovy',
            importUrl: 'https://x.com/lib.groovy',
            confirm: true
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('exactly one')
    }

    // -------- installAsUserApp --------

    def "hub_create_app with installAsUserApp creates the instance then commits the install (Done)"() {
        given:
        enableWrite()
        def capturedPath = null
        script.metaClass.hubInternalGetRaw = { String path, Map query = null, int timeout = 30, boolean isRetry = false ->
            capturedPath = path
            [
                status: 302,
                location: '/installedapp/configure/9999/mainPage',
                data: ''
            ]
        }
        def commit = stubInstallCommit()

        when:
        def result = script.toolInstallApp([installAsUserApp: 310, confirm: true])

        then:
        capturedPath == '/installedapp/create/310'
        // Step 2: the shell is committed via a Done POST -- the step the old
        // GET-only path skipped, leaving installed()/initialize() unfired.
        commit.donePath == '/installedapp/update/json'
        commit.doneBody._action_update == 'Done'
        commit.doneBody.currentPage == 'mainPage'
        commit.doneBody.id == '9999'
        result.success == true
        result.committed == true
        result.mode == 'installAsUserApp'
        result.codeAppId == 310
        result.instanceAppId == 9999
        result.scheduledJobCount == 1
    }

    def "hub_create_app installAsUserApp uses the instance's actual first-page name in the Done commit"() {
        given:
        enableWrite()
        script.metaClass.hubInternalGetRaw = { String path, Map query = null, int timeout = 30, boolean isRetry = false ->
            [status: 302, location: '/installedapp/configure/4242/p', data: '']
        }
        def commit = stubInstallCommit()

        when:
        def result = script.toolInstallApp([installAsUserApp: 313, confirm: true])

        then:
        result.success == true
        result.committed == true
        result.instanceAppId == 4242
        // First page is "p" (not "mainPage") -- the commit must target it.
        commit.configPaths.any { it == '/installedapp/configure/json/4242/p' }
        commit.doneBody.currentPage == 'p'
    }

    def "hub_create_app installAsUserApp reports an uncommitted shell when the Done POST fails"() {
        given:
        enableWrite()
        script.metaClass.hubInternalGetRaw = { String path, Map query = null, int timeout = 30, boolean isRetry = false ->
            [status: 302, location: '/installedapp/configure/7777/mainPage', data: '']
        }
        stubInstallCommit(doneStatus: 500)

        when:
        def result = script.toolInstallApp([installAsUserApp: 314, confirm: true])

        then:
        result.success == false
        result.committed == false
        result.instanceAppId == 7777
        result.error.contains('shell')
        result.error.contains('7777')
    }

    def "hub_create_app installAsUserApp rejects a shell that 200s the Done but reads app.installed=false"() {
        given:
        enableWrite()
        script.metaClass.hubInternalGetRaw = { String path, Map query = null, int timeout = 30, boolean isRetry = false ->
            [status: 302, location: '/installedapp/configure/7373/mainPage', data: '']
        }
        // The hub can return HTTP 200 on a rejected/re-rendered Done -- "didn't 4xx" is NOT proof the
        // commit took. The independent app.installed read-back is the real gate.
        stubInstallCommit(installedFlag: false)

        when:
        def result = script.toolInstallApp([installAsUserApp: 314, confirm: true])

        then:
        result.success == false
        result.committed == false
        result.instanceAppId == 7373
        result.error.contains('app.installed=false')
        result.error.contains('shell')
    }

    def "hub_create_app installAsUserApp still reports committed when the post-commit statusJson read fails (zero counts)"() {
        given:
        enableWrite()
        script.metaClass.hubInternalGetRaw = { String path, Map query = null, int timeout = 30, boolean isRetry = false ->
            [status: 302, location: '/installedapp/configure/4646/mainPage', data: '']
        }
        // app.installed confirms true, but the runtime-evidence read (statusJson) flakes -- a committed
        // install must still report committed, just with zero counts (don't fail a real install).
        stubInstallCommit(statusThrows: true)

        when:
        def result = script.toolInstallApp([installAsUserApp: 312, confirm: true])

        then:
        result.success == true
        result.committed == true
        result.installedConfirmed == true
        result.scheduledJobCount == 0
        result.eventSubscriptionCount == 0
    }

    def "hub_create_app installAsUserApp falls back to the config page name when the create redirect omits the page segment"() {
        given:
        enableWrite()
        // Location with NO trailing page segment -> firstPage parses to null -> page resolves from configPage.name.
        script.metaClass.hubInternalGetRaw = { String path, Map query = null, int timeout = 30, boolean isRetry = false ->
            [status: 302, location: '/installedapp/configure/8989', data: '']
        }
        def commit = stubInstallCommit()  // configPage.name == 'mainPage'

        when:
        def result = script.toolInstallApp([installAsUserApp: 312, confirm: true])

        then:
        result.success == true
        result.committed == true
        result.instanceAppId == 8989
        commit.doneBody.currentPage == 'mainPage'
    }

    def "hub_create_app installAsUserApp submits each input's value in the Done body (bool as true/false, not empty)"() {
        given:
        enableWrite()
        script.metaClass.hubInternalGetRaw = { String path, Map query = null, int timeout = 30, boolean isRetry = false ->
            [status: 302, location: '/installedapp/configure/5555/mainPage', data: '']
        }
        // Watchdog-shaped page: a text input with a default + a bool defaulting true
        // + an unset text input. The hub 500s on settings[bool]="" -- the original bug.
        def commit = stubInstallCommit(sections: [[
            input: [
                [name: 'flagFileName', type: 'text', value: 'e2e-deadman.json'],
                [name: 'debugLogging', type: 'bool', value: true],
                [name: 'hubSecurityUser', type: 'text']
            ]
        ]])

        when:
        def result = script.toolInstallApp([installAsUserApp: 312, confirm: true])

        then:
        result.success == true
        result.committed == true
        commit.doneBody['settings[debugLogging]'] == 'true'
        commit.doneBody['checkbox[debugLogging]'] == 'on'
        commit.doneBody['debugLogging.type'] == 'bool'
        commit.doneBody['settings[flagFileName]'] == 'e2e-deadman.json'
        commit.doneBody['settings[hubSecurityUser]'] == ''
        // Classic-form fields the hub's update handler requires for a fresh
        // standalone install (without them it 500s -- verified live).
        commit.doneBody._cancellable == 'false'
        commit.doneBody.appTypeId == ''
        commit.doneBody.appTypeName == ''
    }

    def "hub_create_app with installAsUserApp returns success=false when hub returns non-302"() {
        given:
        enableWrite()
        script.metaClass.hubInternalGetRaw = { String path, Map query = null, int timeout = 30, boolean isRetry = false ->
            [status: 404, location: null, data: 'not found']
        }

        when:
        def result = script.toolInstallApp([installAsUserApp: 99999, confirm: true])

        then:
        result.success == false
        result.codeAppId == 99999
        result.status == 404
        result.error.contains('does not exist')  // status-aware hint for 404
    }

    @spock.lang.Unroll
    def "hub_create_app installAsUserApp returns status-aware error for status #status"() {
        // Pins the status-aware error hints so a 401 auth-expired case doesn't get
        // misread as "codeAppId wrong" the way a generic non-302 message would.
        given:
        enableWrite()
        script.metaClass.hubInternalGetRaw = { String path, Map query = null, int timeout = 30, boolean isRetry = false ->
            [status: status, location: null, data: 'body']
        }

        when:
        def result = script.toolInstallApp([installAsUserApp: 310, confirm: true])

        then:
        result.success == false
        result.status == status
        result.error.contains(hint)

        where:
        status | hint
        401    | 'authentication'
        403    | 'authentication'
        500    | 'server error'
        200    | 'without redirect'
    }

    def "hub_create_app rejects installAsUserApp combined with importUrl"() {
        given:
        enableWrite()

        when:
        script.toolInstallApp([
            installAsUserApp: 310,
            importUrl: 'https://example.com/x.groovy',
            confirm: true
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('mutually exclusive')
        ex.message.contains('two calls')
    }

    // -------- triggerUpdated --------

    def "hub_update_app with triggerUpdated fires updated() POST after save and reports updatedFired=true"() {
        given:
        enableWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "source": "old", "version": 5}'
        }
        // The code save rides hubInternalPostJson; the lifecycle fire rides hubInternalPostForm.
        // Both append to one list so the save-then-fire ordering stays pinned.
        def posts = []
        script.metaClass.hubInternalPostJson = { String path, String body ->
            posts << [helper: 'json', path: path]
            [success: true]
        }
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            posts << [helper: 'form', path: path]
            [status: 200, location: null, data: '']
        }
        script.metaClass.backupItemSource = { String type, String itemId -> [version: 5, fileName: 'b.json'] }

        when:
        def result = script.toolUpdateAppCode([
            appId: '42',
            source: 'new source',
            triggerUpdated: 194,
            confirm: true
        ])

        then:
        result.success == true
        result.triggerUpdated == 194
        result.updatedFired == true
        posts.size() == 2
        posts[0] == [helper: 'json', path: '/app/saveOrUpdateJson']
        posts[1] == [helper: 'form', path: '/installedapp/configure/194/mainPage']
    }

    def "hub_update_app with triggerUpdated reports updatedFired=false + repairHints when lifecycle POST throws"() {
        given:
        enableWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "source": "old", "version": 5}'
        }
        def savePostCount = 0
        script.metaClass.hubInternalPostJson = { String path, String body ->
            savePostCount++
            [success: true]
        }
        def lifecyclePaths = []
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            lifecyclePaths << path
            throw new RuntimeException('hub rejected lifecycle POST')
        }
        script.metaClass.backupItemSource = { String type, String itemId -> [version: 5, fileName: 'b.json'] }

        when:
        def result = script.toolUpdateAppCode([
            appId: '42',
            source: 'new source',
            triggerUpdated: 194,
            confirm: true
        ])

        then:
        // The save itself succeeded; only the optional lifecycle refresh failed.
        result.success == true
        result.triggerUpdated == 194
        result.updatedFired == false
        result.repairHints?.any { it.toString().contains('lifecycle-fire POST failed') }
        // Pins that BOTH posts were attempted -- the save + the lifecycle fire.
        // Without this, a regression that throws before reaching the lifecycle POST
        // could produce the same envelope shape while never trying the second POST.
        savePostCount == 1
        lifecyclePaths == ['/installedapp/configure/194/mainPage']
    }

    def "hub_update_app without triggerUpdated does NOT fire updated() (negative pin matches UI behavior)"() {
        given:
        enableWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "source": "old", "version": 5}'
        }
        def savePaths = []
        script.metaClass.hubInternalPostJson = { String path, String body ->
            savePaths << path
            [success: true]
        }
        def lifecyclePaths = []
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            lifecyclePaths << path
            [status: 200, location: null, data: '']
        }
        script.metaClass.backupItemSource = { String type, String itemId -> [version: 5, fileName: 'b.json'] }

        when:
        def result = script.toolUpdateAppCode([
            appId: '42',
            source: 'new source',
            confirm: true
        ])

        then:
        result.success == true
        result.containsKey('triggerUpdated') == false
        result.containsKey('updatedFired') == false
        savePaths == ['/app/saveOrUpdateJson']  // only the code-save POST...
        lifecyclePaths.isEmpty()                // ...no lifecycle POST
    }

    def "hub_update_app with triggerUpdated lifecycle failure also sets partial:true"() {
        // Pins the half-failure flag added so callers checking result.partial see
        // the lifecycle-fire failure without drilling into updatedFired.
        given:
        enableWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "source": "old", "version": 5}'
        }
        script.metaClass.hubInternalPostJson = { String path, String body -> [success: true] }
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            throw new RuntimeException('hub rejected lifecycle POST')
        }
        script.metaClass.backupItemSource = { String type, String itemId -> [version: 5, fileName: 'b.json'] }

        when:
        def result = script.toolUpdateAppCode([
            appId: '42',
            source: 'new',
            triggerUpdated: 194,
            confirm: true
        ])

        then:
        result.success == true       // code save committed
        result.partial == true       // lifecycle refresh failed
        result.updatedFired == false
    }

    // -------- mutual exclusion on update paths (mirrors install paths) --------

    def "hub_update_app rejects multiple source modes together"() {
        given:
        enableWrite()

        when:
        script.toolUpdateAppCode([
            appId: '42',
            source: 'inline',
            importUrl: 'https://x.com/a.groovy',
            confirm: true
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('exactly one')
    }

    def "hub_update_library rejects multiple source modes together"() {
        given:
        enableWrite()

        when:
        script.toolUpdateLibraryCode([
            libraryId: '7',
            sourceFile: 'lib.groovy',
            importUrl: 'https://x.com/lib.groovy',
            confirm: true
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('exactly one')
    }

    // -------- self-update guard + importUrl (no SSRF via importUrl) --------

    def "hub_update_app self-update guard blocks importUrl mode (no outbound fetch happens)"() {
        // If a refactor reorders mode resolution above the self-update guard, the hub
        // could be tricked into fetching attacker-controlled URLs (SSRF probe) even
        // though the guard would later block the write. Pins guard-before-fetch ordering.
        given:
        enableWrite()
        settingsMap.enableDeveloperMode = false
        stubHttpGet(200, 'pwn')

        when:
        // appId '1' matches the sharedAppStub id, triggering the self-update guard.
        script.toolUpdateAppCode([appId: '1', importUrl: 'http://attacker.example/x.groovy', confirm: true])

        then:
        thrown(IllegalArgumentException)
        nextHttpGetCaptured.uri == null  // guard ran before _fetchSourceFromUrl
    }

    // -------- self-deploy outcome recorded to atomicState.lastSelfDeploy (issue #237) --------
    // appId '1' == sharedAppStub id, so these exercise the self-update path. The result can't ride
    // back on the deploy call live (success reloads the app; a big-file compile failure 504s), so the
    // outcome is persisted to atomicState for a follow-up hub_get_info read to recover.

    def "hub_update_app self-update records atomicState.lastSelfDeploy on success"() {
        given:
        enableWrite()
        settingsMap.enableDeveloperMode = true
        stubHttpGet(200, 'fetched-self-source')
        hubGet.register('/app/ajax/code') { params -> '{"status":"ok","source":"old","version":5}' }
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true]
        }
        script.metaClass.backupItemSource = { String type, String itemId -> [version: 5, fileName: 'b.json'] }

        when:
        def result = script.toolUpdateAppCode([appId: '1', importUrl: 'https://raw.example/self.groovy', confirm: true])

        then:
        result.success == true
        atomicStateMap.lastSelfDeploy?.success == true
        atomicStateMap.lastSelfDeploy.error == null
        atomicStateMap.lastSelfDeploy.importUrl == 'https://raw.example/self.groovy'
        atomicStateMap.lastSelfDeploy.sourceMode == 'importUrl'
    }

    def "hub_update_app self-update records the hub's VERBATIM error to atomicState.lastSelfDeploy on a rejected save"() {
        given:
        enableWrite()
        settingsMap.enableDeveloperMode = true
        stubHttpGet(200, 'broken-self-source')
        hubGet.register('/app/ajax/code') { params -> '{"status":"ok","source":"old","version":5}' }
        // Hub rejects the save with its real validation message (the exact thing CI must recover).
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: false, message: 'name cannot be empty in definition section']
        }
        script.metaClass.backupItemSource = { String type, String itemId -> [version: 5, fileName: 'b.json'] }

        when:
        def result = script.toolUpdateAppCode([appId: '1', importUrl: 'https://raw.example/self.groovy', confirm: true])

        then:
        result.success == false
        result.error == 'name cannot be empty in definition section'
        atomicStateMap.lastSelfDeploy?.success == false
        atomicStateMap.lastSelfDeploy.error == 'name cannot be empty in definition section'
        atomicStateMap.lastSelfDeploy.importUrl == 'https://raw.example/self.groovy'
    }

    def "hub_update_app of a DIFFERENT app (neither instance nor class id) does NOT record lastSelfDeploy"() {
        given:
        enableWrite()
        settingsMap.enableDeveloperMode = true
        atomicStateMap.lastSelfDeploy = null
        stubHttpGet(200, 'other-app-source')
        // app.id is 1; the self CLASS id resolves to 178. appId 42 matches neither -> not a self-update.
        hubGet.register('/hub2/userAppTypes') { params -> '[{"id":178,"namespace":"mcp","name":"MCP Rule Server"}]' }
        hubGet.register('/app/ajax/code') { params -> '{"status":"ok","source":"old","version":5}' }
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true]
        }
        script.metaClass.backupItemSource = { String type, String itemId -> [version: 5, fileName: 'b.json'] }

        when:
        def result = script.toolUpdateAppCode([appId: '42', importUrl: 'https://raw.example/other.groovy', confirm: true])

        then:
        result.success == true
        atomicStateMap.lastSelfDeploy == null
    }

    def "hub_update_app self-update via the Apps Code CLASS id (the real CI deploy path) records the verbatim error"() {
        // The CI deploy targets the CLASS id from /hub2/userAppTypes (e.g. 178), which differs from
        // app.id (the instance). isSelfUpdate must still fire via the class-id branch, else a failed
        // real deploy would record nothing and CI couldn't recover the hub's error.
        given:
        enableWrite()
        settingsMap.enableDeveloperMode = true
        atomicStateMap.lastSelfDeploy = null
        stubHttpGet(200, 'class-id-self-source')
        hubGet.register('/hub2/userAppTypes') { params -> '[{"id":178,"namespace":"mcp","name":"MCP Rule Server"}]' }
        hubGet.register('/app/ajax/code') { params -> '{"status":"ok","source":"old","version":5}' }
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: false, message: 'name cannot be empty in definition section']
        }
        script.metaClass.backupItemSource = { String type, String itemId -> [version: 5, fileName: 'b.json'] }

        when:
        def result = script.toolUpdateAppCode([appId: '178', importUrl: 'https://raw.example/self-class.groovy', confirm: true])

        then:
        result.success == false
        result.error == 'name cannot be empty in definition section'
        atomicStateMap.lastSelfDeploy?.success == false
        atomicStateMap.lastSelfDeploy.error == 'name cannot be empty in definition section'
        atomicStateMap.lastSelfDeploy.importUrl == 'https://raw.example/self-class.groovy'
    }

    def "hub_update_app self-update records lastSelfDeploy when the save THROWS (cloud 504 / exception path)"() {
        // The big-source importUrl deploy can 504 at the cloud relay: hubInternalPostJson throws
        // before any parseable 200 body. The catch block must still persist a failure record so a
        // follow-up hub_get_info can recover it; the message is the exception text (not the hub's
        // verbatim compiler error -- that only exists on the synchronous success=false path).
        given:
        enableWrite()
        settingsMap.enableDeveloperMode = true
        atomicStateMap.lastSelfDeploy = null
        stubHttpGet(200, 'self-source-that-504s')
        hubGet.register('/app/ajax/code') { params -> '{"status":"ok","source":"old","version":5}' }
        script.metaClass.hubInternalPostJson = { String path, String body -> throw new RuntimeException('cloud 504') }
        script.metaClass.backupItemSource = { String type, String itemId -> [version: 5, fileName: 'b.json'] }

        when:
        def result = script.toolUpdateAppCode([appId: '1', importUrl: 'https://raw.example/self-504.groovy', confirm: true])

        then:
        result.success == false
        result.error.startsWith('App update failed:')
        atomicStateMap.lastSelfDeploy?.success == false
        atomicStateMap.lastSelfDeploy.error.startsWith('App update failed:')
        atomicStateMap.lastSelfDeploy.importUrl == 'https://raw.example/self-504.groovy'
    }

    // -------- installAsUserApp validation edge cases --------

    @spock.lang.Unroll
    def "hub_create_app installAsUserApp rejects invalid value (#value)"() {
        given:
        enableWrite()

        when:
        script.toolInstallApp([installAsUserApp: value, confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('positive integer')

        where:
        value << [0, -5, 'not-a-number']
    }

    def "hub_create_app installAsUserApp coerces stringified integer"() {
        given:
        enableWrite()
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, int t = 30, boolean r = false ->
            [status: 302, location: '/installedapp/configure/9999/mainPage', data: '']
        }
        stubInstallCommit()

        when:
        def result = script.toolInstallApp([installAsUserApp: '310', confirm: true])

        then:
        result.success == true
        result.committed == true
        result.codeAppId == 310
        result.instanceAppId == 9999
    }

    // -------- triggerUpdated validation edge cases --------

    @spock.lang.Unroll
    def "hub_update_app triggerUpdated #value returns success+updatedFired:false+repairHints (code save still committed)"() {
        given:
        enableWrite()
        hubGet.register('/app/ajax/code') { params -> '{"status":"ok","source":"old","version":5}' }
        script.metaClass.hubInternalPostJson = { String path, String body -> [success: true] }
        def lifecyclePostCount = 0
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            lifecyclePostCount++
            [status: 200, location: null, data: '']
        }
        script.metaClass.backupItemSource = { String type, String itemId -> [version: 5, fileName: 'b.json'] }

        when:
        def result = script.toolUpdateAppCode([
            appId: '42',
            source: 'new',
            triggerUpdated: value,
            confirm: true
        ])

        then:
        result.success == true
        result.updatedFired == false
        result.repairHints?.any { it.toString().contains('positive integer') }
        lifecyclePostCount == 0  // never reached the lifecycle POST

        where:
        value << [0, -1, 'abc']
    }

    // -------- per-tool importUrl smoke (hub_create_driver / hub_update_driver / hub_update_library) --------

    def "hub_create_driver with importUrl fetches via httpGet and POSTs to /driver/saveOrUpdateJson"() {
        given:
        enableWrite()
        stubHttpGet(200, 'metadata { definition (name: "FromUrl") {} }')
        def captured = [:]
        script.metaClass.hubInternalPostJson = { String path, String body ->
            captured.path = path; captured.body = body
            [success: true, id: 8888, version: 1]
        }
        hubGet.register('/driver/ajax/code') { params -> '{"status":"ok","source":"stub","version":1}' }

        when:
        def result = script.toolInstallDriver([importUrl: 'https://raw.example/d.groovy', confirm: true])

        then:
        result.success == true
        result.driverId == '8888'
        captured.path == '/driver/saveOrUpdateJson'
    }

    def "hub_update_driver with importUrl POSTs the fetched source to /driver/saveOrUpdateJson"() {
        given:
        enableWrite()
        stubHttpGet(200, 'updated-driver-source')
        hubGet.register('/driver/ajax/code') { params -> '{"status":"ok","source":"old","version":3}' }
        def captured = [:]
        script.metaClass.hubInternalPostJson = { String path, String body ->
            captured.path = path; captured.body = body
            [success: true, id: 55]
        }
        script.metaClass.backupItemSource = { String type, String itemId -> [version: 3, fileName: 'b.json'] }

        when:
        def result = script.toolUpdateDriverCode([driverId: '55', importUrl: 'https://raw.example/d.groovy', confirm: true])

        then:
        result.success == true
        result.sourceMode == 'importUrl'
        captured.path == '/driver/saveOrUpdateJson'
        new groovy.json.JsonSlurper().parseText(captured.body).source == 'updated-driver-source'
    }

    def "hub_update_library with importUrl posts to /library/saveOrUpdateJson"() {
        given:
        enableWrite()
        stubHttpGet(200, 'library(name: "Updated")')
        hubGet.register('/library/list/single/data/22') { params -> '[{"version":4,"source":"old"}]' }
        // Stub File Manager so library backup doesn't blow up trying to write.
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        def captured = [:]
        script.metaClass.hubInternalPostJson = { String path, String body ->
            captured.path = path; captured.body = body
            [success: true, id: 22, version: 5]
        }

        when:
        def result = script.toolUpdateLibraryCode([libraryId: '22', importUrl: 'https://raw.example/lib.groovy', confirm: true])

        then:
        result.success == true
        result.sourceMode == 'importUrl'
        captured.path == '/library/saveOrUpdateJson'
        captured.body.contains('library(name: \\"Updated\\")')
    }

    // -------- bulk-mode importUrl per-item forwarding --------

    def "hub_create_driver bulk: per-item importUrl is forwarded and fetched"() {
        given:
        enableWrite()
        stubHttpGet(200, 'driver-from-url')
        def postPaths = []
        script.metaClass.hubInternalPostJson = { String path, String body ->
            postPaths << path
            [success: true, id: 9001, version: 1]
        }
        hubGet.register('/driver/ajax/code') { params -> '{"status":"ok","source":"stub","version":1}' }

        when:
        def result = script.toolInstallDriver([
            installs: [[importUrl: 'https://raw.example/d1.groovy']],
            confirm: true
        ])

        then:
        result.success == true
        result.installs.size() == 1
        result.installs[0].success == true
        result.installs[0].sourceMode == 'importUrl'
        nextHttpGetCaptured.uri == 'https://raw.example/d1.groovy'
        postPaths.contains('/driver/saveOrUpdateJson')
    }

    def "hub_update_driver bulk: per-item importUrl is forwarded and fetched"() {
        given:
        enableWrite()
        stubHttpGet(200, 'updated-bulk-driver')
        hubGet.register('/driver/ajax/code') { params -> '{"status":"ok","source":"old","version":2}' }
        def postPaths = []
        script.metaClass.hubInternalPostJson = { String path, String body ->
            postPaths << path
            [success: true]
        }
        script.metaClass.backupItemSource = { String type, String itemId -> [version: 2, fileName: 'b.json'] }

        when:
        def result = script.toolUpdateDriverCode([
            updates: [[driverId: '77', importUrl: 'https://raw.example/d.groovy']],
            confirm: true
        ])

        then:
        result.success == true
        result.updates.size() == 1
        result.updates[0].success == true
        result.updates[0].sourceMode == 'importUrl'
        nextHttpGetCaptured.uri == 'https://raw.example/d.groovy'
        postPaths.contains('/driver/saveOrUpdateJson')
    }

    // -------- JSON-RPC dispatch envelope (-32602 on mutex, success on happy path) --------

    @spock.lang.Unroll
    def "hub_create_app installAsUserApp via dispatch returns success envelope (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, int t = 30, boolean r = false ->
            [status: 302, location: '/installedapp/configure/8888/mainPage', data: '']
        }
        stubInstallCommit()

        when:
        def response = mcpDriver.callTool('hub_create_app', [installAsUserApp: 310, confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.committed == true
        inner.instanceAppId == 8888

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_create_app installAsUserApp + importUrl via dispatch returns -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_create_app', [
            installAsUserApp: 310,
            importUrl: 'https://example.com/x.groovy',
            confirm: true
        ])

        then:
        response.error.code == -32602
        response.error.message.contains('mutually exclusive')

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_update_app with importUrl via dispatch returns success envelope (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        stubHttpGet(200, 'dispatch-fetched-source')
        hubGet.register('/app/ajax/code') { params -> '{"status":"ok","source":"old","version":9}' }
        script.metaClass.hubInternalPostJson = { String path, String body ->
            [success: true]
        }
        script.metaClass.backupItemSource = { String type, String itemId -> [version: 9, fileName: 'b.json'] }

        when:
        def response = mcpDriver.callTool('hub_update_app', [
            appId: '42',
            importUrl: 'https://example.com/app.groovy',
            confirm: true
        ])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.sourceMode == 'importUrl'

        where:
        useGateways << [true, false]
    }
}
