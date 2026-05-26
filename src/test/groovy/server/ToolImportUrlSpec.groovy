package server

import spock.lang.Shared
import spock.lang.Unroll
import support.TestChildApp
import support.ToolSpecBase

/**
 * Spec for the importUrl + installAsUserApp + triggerUpdated additions to the
 * install/update tools. Mirrors the pattern in ToolAppDriverCodeSpec --
 * httpGet is stubbed via script.metaClass; the deploy POST + verify GET
 * paths re-use the same stubs the existing source/sourceFile tests use.
 *
 * Coverage:
 *   - _fetchSourceFromUrl helper: happy + 4 failure paths
 *   - importUrl on update_app_code, install_app, install_library (one happy path each)
 *   - importUrl mutual exclusion + scheme validation
 *   - installAsUserApp: happy + 404 + mutual exclusion
 *   - triggerUpdated: happy + lifecycle-fire-failure + negative pin
 */
class ToolImportUrlSpec extends ToolSpecBase {

    @Shared private TestChildApp sharedAppStub = new TestChildApp(id: 1L, label: 'MCP')

    // Mutable per-test response state set by stubHttpGet / stubHttpGetThrows.
    // The setupSpec-level httpGet stub reads these; specs swap them between tests.
    @Shared private int nextHttpGetStatus = 200
    @Shared private String nextHttpGetBody = ''
    @Shared private Throwable nextHttpGetThrow = null
    @Shared private Map nextHttpGetCaptured = [:]

    def setupSpec() {
        appExecutor.getApp() >> sharedAppStub
        // Install ONE global httpGet stub at spec-class scope -- mirrors the
        // rule harness pattern (RuleHarnessSpec.groovy:209). Per-test state is
        // mutated via the stubHttpGet / stubHttpGetThrows helpers below.
        appExecutor.httpGet(*_) >> { args ->
            if (nextHttpGetThrow) throw nextHttpGetThrow
            Map params = args[0] as Map
            Closure handler = args[1] as Closure
            nextHttpGetCaptured.uri = params?.uri
            handler.call([status: nextHttpGetStatus, data: [text: nextHttpGetBody]])
        }
    }

    def setup() {
        // Reset per-test state so prior-test stub config doesn't leak.
        nextHttpGetStatus = 200
        nextHttpGetBody = ''
        nextHttpGetThrow = null
        nextHttpGetCaptured.clear()
    }

    private void enableHubAdminWrite() {
        settingsMap.enableHubAdminWrite = true
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
        ex.message.contains('http://')
        ex.message.contains('https://')
        nextHttpGetCaptured.uri == null  // scheme check fires before any network attempt
    }

    def "_fetchSourceFromUrl: throws IAE on null url"() {
        when:
        script._fetchSourceFromUrl(null)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('http://')
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

    // -------- importUrl integration: update_app_code --------

    def "update_app_code with importUrl fetches via httpGet and POSTs the fetched source"() {
        given:
        enableHubAdminWrite()
        stubHttpGet(200, 'fetched-source-here')
        // app code endpoint returns the current version so the optimistic-lock path is satisfied
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "source": "old-source", "version": 7}'
        }
        def captured = [:]
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            captured.path = path
            captured.body = body
            [status: 200, location: null, data: '{"status":"success"}']
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
        result.note?.contains('importUrl')
        captured.path == '/app/ajax/update'
        captured.body.source == 'fetched-source-here'
    }

    // -------- importUrl integration: install_app --------

    def "install_app with importUrl fetches via httpGet and POSTs to /app/save"() {
        given:
        enableHubAdminWrite()
        stubHttpGet(200, 'definition(name: "FromUrl")')
        def captured = [:]
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            captured.path = path
            captured.body = body
            [status: 302, location: 'http://127.0.0.1:8080/app/editor/7777', data: '']
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
        captured.path == '/app/save'
        captured.body.source == 'definition(name: "FromUrl")'
        result.note?.contains('importUrl')
    }

    // -------- importUrl integration: install_library --------

    def "install_library with importUrl fetches the URL and posts to library/saveOrUpdateJson"() {
        given:
        enableHubAdminWrite()
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
    // For install_*_code, modesSet counts the present modes and throws if > 1.
    // For update_*_code, the existing if-else chain naturally takes the first
    // present mode in the order resave > sourceFile > importUrl > source --
    // tool schema documents these as mutually exclusive; explicit mutex
    // enforcement at the API boundary is a follow-up if it proves needed.

    def "install_app rejects multiple source modes together"() {
        given:
        enableHubAdminWrite()

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

    def "install_library rejects multiple source modes together"() {
        given:
        enableHubAdminWrite()

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

    def "install_app with installAsUserApp creates instance via /installedapp/create/<codeId> and parses Location"() {
        given:
        enableHubAdminWrite()
        def capturedPath = null
        script.metaClass.hubInternalGetRaw = { String path, Map query = null, int timeout = 30, boolean isRetry = false ->
            capturedPath = path
            [
                status: 302,
                location: '/installedapp/configure/9999/mainPage',
                data: ''
            ]
        }

        when:
        def result = script.toolInstallApp([installAsUserApp: 310, confirm: true])

        then:
        capturedPath == '/installedapp/create/310'
        result.success == true
        result.mode == 'installAsUserApp'
        result.codeAppId == 310
        result.instanceAppId == 9999
    }

    def "install_app with installAsUserApp returns success=false when hub returns non-302"() {
        given:
        enableHubAdminWrite()
        script.metaClass.hubInternalGetRaw = { String path, Map query = null, int timeout = 30, boolean isRetry = false ->
            [status: 404, location: null, data: 'not found']
        }

        when:
        def result = script.toolInstallApp([installAsUserApp: 99999, confirm: true])

        then:
        result.success == false
        result.codeAppId == 99999
        result.error.contains('404')
    }

    def "install_app rejects installAsUserApp combined with importUrl"() {
        given:
        enableHubAdminWrite()

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

    def "update_app_code with triggerUpdated fires updated() POST after save and reports updatedFired=true"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "source": "old", "version": 5}'
        }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            posts << [path: path, body: body]
            // first POST: code update; second POST: trigger updated
            [status: 200, location: null, data: '{"status":"success"}']
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
        posts[0].path == '/app/ajax/update'
        posts[1].path == '/installedapp/configure/194/mainPage'
    }

    def "update_app_code with triggerUpdated reports updatedFired=false + repairHints when lifecycle POST throws"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "source": "old", "version": 5}'
        }
        def postCount = 0
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            postCount++
            if (path.startsWith('/installedapp/configure/')) {
                throw new RuntimeException('hub rejected lifecycle POST')
            }
            [status: 200, location: null, data: '{"status":"success"}']
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
    }

    def "update_app_code without triggerUpdated does NOT fire updated() (negative pin matches UI behavior)"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/app/ajax/code') { params ->
            '{"status": "ok", "source": "old", "version": 5}'
        }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body ->
            posts << [path: path]
            [status: 200, location: null, data: '{"status":"success"}']
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
        posts.size() == 1
        posts[0].path == '/app/ajax/update'  // only the code-save POST, no lifecycle POST
    }
}
