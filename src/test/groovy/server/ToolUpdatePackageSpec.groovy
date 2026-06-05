package server

import spock.lang.Shared
import support.ToolSpecBase

/**
 * Spec for hub_update_package (toolUpdatePackage) -- the Developer Mode package
 * deploy tool (issue #209). It fetches the app source at a git ref, parses its
 * #include directives, installs/updates each referenced library, then updates the
 * app -- libraries FIRST, app LAST, aborting before the app save on any failure.
 *
 * The load-bearing guarantee under test: a package failure must never cost the main
 * app its ability to update. These specs pin (a) libraries are written before the
 * app, (b) ANY pre-app failure aborts without calling the app leg, and (c) the
 * Developer-Mode gate + idempotent create-vs-update behaviour.
 *
 * Mocking strategy:
 *   - The app-source fetch runs through the real _fetchSourceFromUrl, which calls
 *     appExecutor.httpGet -- stubbed at spec scope (script.metaClass does not
 *     intercept that path; see ToolImportUrlSpec).
 *   - /hub2/userAppTypes (self app-class lookup) and /hub2/userLibraries (existing
 *     library snapshot) are stubbed via hubGet.register.
 *   - The inner write tools (toolInstallLibrary / toolUpdateLibraryCode /
 *     toolUpdateAppCode) are stubbed per-test on script.metaClass so the
 *     orchestration -- ordering and abort behaviour -- is what is exercised, not
 *     the (separately specced) library/app write internals.
 */
class ToolUpdatePackageSpec extends ToolSpecBase {

    private static final String APP_NO_INCLUDE =
        'definition(name: "MCP Rule Server", namespace: "mcp")\n\ndef foo() { return 1 }\n'
    private static final String APP_WITH_SMOKE =
        APP_NO_INCLUDE + '\n#include mcp.McpSmokeTestLib\n'
    private static final String APP_WITH_UNKNOWN =
        APP_NO_INCLUDE + '\n#include mcp.NotARealLib\n'
    private static final String APP_WITH_DUPE =
        APP_NO_INCLUDE + '\n#include mcp.McpSmokeTestLib\n#include   mcp.McpSmokeTestLib\n'

    @Shared int nextHttpStatus = 200
    @Shared String nextHttpBody = ''
    @Shared Throwable nextHttpThrow = null

    def setupSpec() {
        // Single global httpGet stub (per-test state mutated via the fields above).
        // _fetchSourceFromUrl -> _httpFetchUrl -> appExecutor.httpGet.
        appExecutor.httpGet(*_) >> { callArgs ->
            if (nextHttpThrow) throw nextHttpThrow
            Closure handler = callArgs[1] as Closure
            handler.call([status: nextHttpStatus, data: [text: nextHttpBody], headers: [:]])
        }
    }

    def setup() {
        nextHttpStatus = 200
        nextHttpBody = ''
        nextHttpThrow = null
    }

    private void enableDev() {
        settingsMap.enableDeveloperMode = true
        settingsMap.enableWrite = true
        // Matches the fixed now() == 1234567890000L so requireDestructiveConfirm's
        // <24h backup gate passes for real (non-dry-run) deploys.
        stateMap.lastBackupTimestamp = 1234567890000L
    }

    private void registerAppTypes(String classId) {
        hubGet.register('/hub2/userAppTypes') { params ->
            groovy.json.JsonOutput.toJson([
                [id: classId, name: 'MCP Rule Server', namespace: 'mcp'],
                [id: '999', name: 'Some Other App', namespace: 'other']
            ])
        }
    }

    private void registerLibs(List libs) {
        hubGet.register('/hub2/userLibraries') { params -> groovy.json.JsonOutput.toJson(libs) }
    }

    // -------- Developer-Mode gate --------

    def "throws when Developer Mode is off"() {
        given:
        settingsMap.remove('enableDeveloperMode')

        when:
        script.toolUpdatePackage([ref: 'main', dryRun: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Developer Mode tools are disabled')
    }

    def "via dispatch (flat) returns -32602 envelope when Developer Mode is off"() {
        given:
        settingsMap.useGateways = false
        settingsMap.remove('enableDeveloperMode')

        when:
        def response = mcpDriver.callTool('hub_update_package', [ref: 'main', dryRun: true])

        then:
        response.error != null
        response.result == null
    }

    // -------- arg validation --------

    def "throws when ref is missing"() {
        given:
        enableDev()

        when:
        script.toolUpdatePackage([dryRun: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('ref is required')
    }

    def "throws when ref is blank"() {
        given:
        enableDev()

        when:
        script.toolUpdatePackage([ref: '   ', dryRun: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('ref is required')
    }

    def "real run (non-dryRun) without confirm fails the backup/confirm gate before any write"() {
        given:
        enableDev()
        settingsMap.remove('enableWrite')  // keep write master default-on; this isolates the confirm gate
        def calls = []
        script.metaClass.toolInstallLibrary = { a -> calls << 'install'; [success: true] }
        script.metaClass.toolUpdateAppCode = { a -> calls << 'app'; [success: true] }
        registerAppTypes('228')

        when:
        script.toolUpdatePackage([ref: 'main'])  // confirm omitted

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')

        and: 'nothing was written'
        calls == []
    }

    // -------- dryRun: parse + resolve, ZERO writes --------

    def "dryRun reports the plan and performs no writes (with one include)"() {
        given:
        enableDev()
        nextHttpBody = APP_WITH_SMOKE
        registerAppTypes('228')
        def calls = []
        script.metaClass.toolInstallLibrary = { a -> calls << 'install'; [success: true] }
        script.metaClass.toolUpdateLibraryCode = { a -> calls << 'update'; [success: true] }
        script.metaClass.toolUpdateAppCode = { a -> calls << 'app'; [success: true] }

        when:
        def result = script.toolUpdatePackage([ref: 'feat/x', dryRun: true])

        then:
        result.success == true
        result.dryRun == true
        result.ref == 'feat/x'
        result.appClassId == '228'
        result.includes == ['mcp.McpSmokeTestLib']
        result.plannedLibraries.size() == 1
        result.plannedLibraries[0].name == 'McpSmokeTestLib'
        result.plannedLibraries[0].url.endsWith('/feat/x/libraries/mcp-smoke-test-lib.groovy')

        and: 'no confirm/backup required and nothing written'
        calls == []
    }

    def "dryRun on an app with no includes plans zero libraries"() {
        given:
        enableDev()
        nextHttpBody = APP_NO_INCLUDE
        registerAppTypes('228')

        when:
        def result = script.toolUpdatePackage([ref: 'main', dryRun: true])

        then:
        result.success == true
        result.dryRun == true
        result.includes == []
        result.plannedLibraries == []
    }

    def "baseUrl override drives URL construction and strips a trailing slash"() {
        given:
        enableDev()
        nextHttpBody = APP_WITH_SMOKE
        registerAppTypes('228')

        when: 'baseUrl has a trailing slash that must be normalized away'
        def result = script.toolUpdatePackage([ref: 'main', dryRun: true, baseUrl: 'https://example.com/raw/'])

        then: 'app + library URLs use the override with exactly one slash between segments'
        result.appUrl == 'https://example.com/raw/main/hubitat-mcp-server.groovy'
        result.plannedLibraries[0].url == 'https://example.com/raw/main/libraries/mcp-smoke-test-lib.groovy'
    }

    // -------- fail-closed aborts BEFORE any write --------

    def "aborts (no writes) when the self app-class id cannot be resolved"() {
        given:
        enableDev()
        nextHttpBody = APP_WITH_SMOKE
        hubGet.register('/hub2/userAppTypes') { params ->
            // Registry present but no entry matches mcp / "MCP Rule Server".
            groovy.json.JsonOutput.toJson([[id: '7', name: 'Other', namespace: 'x']])
        }
        def calls = []
        script.metaClass.toolInstallLibrary = { a -> calls << 'install'; [success: true] }
        script.metaClass.toolUpdateAppCode = { a -> calls << 'app'; [success: true] }

        when:
        def result = script.toolUpdatePackage([ref: 'main', confirm: true])

        then:
        result.success == false
        result.aborted == true
        result.abortReason == 'self_app_unresolved'
        calls == []
    }

    def "aborts (no writes) when the app source fetch fails"() {
        given:
        enableDev()
        registerAppTypes('228')
        nextHttpThrow = new RuntimeException('connection refused')
        def calls = []
        script.metaClass.toolInstallLibrary = { a -> calls << 'install'; [success: true] }
        script.metaClass.toolUpdateAppCode = { a -> calls << 'app'; [success: true] }

        when:
        def result = script.toolUpdatePackage([ref: 'main', confirm: true])

        then:
        result.success == false
        result.aborted == true
        result.abortReason == 'app_source_fetch_failed'
        calls == []
    }

    def "aborts (no writes) when the app source fetch returns a non-200 (bad ref -> 404)"() {
        given:
        enableDev()
        registerAppTypes('228')
        nextHttpStatus = 404  // _fetchSourceFromUrl throws IAE on status != 200
        def calls = []
        script.metaClass.toolInstallLibrary = { a -> calls << 'install'; [success: true] }
        script.metaClass.toolUpdateAppCode = { a -> calls << 'app'; [success: true] }

        when:
        def result = script.toolUpdatePackage([ref: 'no-such-ref', confirm: true])

        then: 'a bad ref (GitHub 404) is a clean abort with no writes'
        result.success == false
        result.aborted == true
        result.abortReason == 'app_source_fetch_failed'
        calls == []
    }

    def "aborts (no writes) when an #include has no registry mapping"() {
        given:
        enableDev()
        nextHttpBody = APP_WITH_UNKNOWN
        registerAppTypes('228')
        def calls = []
        script.metaClass.toolInstallLibrary = { a -> calls << 'install'; [success: true] }
        script.metaClass.toolUpdateAppCode = { a -> calls << 'app'; [success: true] }

        when:
        def result = script.toolUpdatePackage([ref: 'main', confirm: true])

        then:
        result.success == false
        result.aborted == true
        result.abortReason == 'unmapped_include'
        result.error.contains('mcp.NotARealLib')
        calls == []
    }

    // -------- real deploy: ordering + idempotency --------

    def "no-include deploy updates the app only, with the resolved class id and app URL"() {
        given:
        enableDev()
        nextHttpBody = APP_NO_INCLUDE
        registerAppTypes('228')
        registerLibs([])
        def calls = []
        script.metaClass.toolInstallLibrary = { a -> calls << [op: 'install']; [success: true] }
        def appArgs = null
        script.metaClass.toolUpdateAppCode = { a -> appArgs = a; calls << [op: 'app']; [success: true, appId: a.appId, sourceMode: 'importUrl'] }

        when:
        def result = script.toolUpdatePackage([ref: 'main', confirm: true])

        then:
        result.success == true
        result.libraries == []
        calls*.op == ['app']
        appArgs.appId == '228'
        appArgs.importUrl.endsWith('/main/hubitat-mcp-server.groovy')
        appArgs.confirm == true
    }

    def "one-include deploy installs the missing library FIRST, then updates the app"() {
        given:
        enableDev()
        nextHttpBody = APP_WITH_SMOKE
        registerAppTypes('228')
        registerLibs([])  // library not present -> create path
        def calls = []
        def installArgs = null
        script.metaClass.toolInstallLibrary = { a -> installArgs = a; calls << 'install'; [success: true, libraryId: '500', message: 'Library installed successfully'] }
        script.metaClass.toolUpdateLibraryCode = { a -> calls << 'update'; [success: true] }
        script.metaClass.toolUpdateAppCode = { a -> calls << 'app'; [success: true, appId: a.appId] }

        when:
        def result = script.toolUpdatePackage([ref: 'main', confirm: true])

        then: 'library written before app'
        calls == ['install', 'app']

        and: 'create path used the mapped library URL'
        installArgs.importUrl.endsWith('/main/libraries/mcp-smoke-test-lib.groovy')
        installArgs.confirm == true

        and: 'result records the create and overall success'
        result.success == true
        result.libraries.size() == 1
        result.libraries[0].action == 'create'
        result.libraries[0].success == true
        result.libraries[0].libraryId == '500'
    }

    def "one-include deploy UPDATES an already-present library (idempotent), then the app"() {
        given:
        enableDev()
        nextHttpBody = APP_WITH_SMOKE
        registerAppTypes('228')
        registerLibs([[id: 7, name: 'McpSmokeTestLib', namespace: 'mcp', version: 3]])  // present -> update path
        def calls = []
        def updateArgs = null
        script.metaClass.toolInstallLibrary = { a -> calls << 'install'; [success: true] }
        script.metaClass.toolUpdateLibraryCode = { a -> updateArgs = a; calls << 'update'; [success: true, libraryId: '7', newVersion: 4] }
        script.metaClass.toolUpdateAppCode = { a -> calls << 'app'; [success: true] }

        when:
        def result = script.toolUpdatePackage([ref: 'main', confirm: true])

        then: 'existing library updated (not created) before app'
        calls == ['update', 'app']
        updateArgs.libraryId == 7
        updateArgs.importUrl.endsWith('/main/libraries/mcp-smoke-test-lib.groovy')

        and:
        result.success == true
        result.libraries[0].action == 'update'
        result.libraries[0].libraryId == '7'
    }

    def "an UPDATE is not subject to the create-only unverified block (hub_update_library reports no verified flag)"() {
        // Asymmetry by design: the unverified block keys on verified==false, which ONLY
        // hub_create_library produces. hub_update_library returns success without a verified
        // field (r?.verified is null, never false), and the existing library already
        // satisfies the #include -- so an update always proceeds to the app leg.
        given:
        enableDev()
        nextHttpBody = APP_WITH_SMOKE
        registerAppTypes('228')
        registerLibs([[id: 7, name: 'McpSmokeTestLib', namespace: 'mcp', version: 3]])  // present -> update path
        def calls = []
        script.metaClass.toolInstallLibrary = { a -> calls << 'install'; [success: true] }
        // Production hub_update_library shape: no `verified` key.
        script.metaClass.toolUpdateLibraryCode = { a -> calls << 'update'; [success: true, libraryId: '7', newVersion: 4] }
        script.metaClass.toolUpdateAppCode = { a -> calls << 'app'; [success: true] }

        when:
        def result = script.toolUpdatePackage([ref: 'main', confirm: true])

        then: 'app leg reached -- the missing verified field is not treated as verified==false'
        calls == ['update', 'app']
        result.success == true
        result.libraries[0].action == 'update'
        result.libraries[0].verified == null
    }

    def "duplicate #include of the same library is de-duplicated to a single write"() {
        given:
        enableDev()
        nextHttpBody = APP_WITH_DUPE
        registerAppTypes('228')
        registerLibs([])
        def installCount = 0
        script.metaClass.toolInstallLibrary = { a -> installCount++; [success: true, libraryId: '500'] }
        script.metaClass.toolUpdateAppCode = { a -> [success: true] }

        when:
        def result = script.toolUpdatePackage([ref: 'main', confirm: true])

        then:
        result.includes == ['mcp.McpSmokeTestLib']
        installCount == 1
        result.libraries.size() == 1
    }

    def "multi-library deploy installs every distinct include FIRST, then the app once"() {
        given:
        enableDev()
        nextHttpBody = APP_NO_INCLUDE + '\n#include mcp.McpSmokeTestLib\n#include mcp.SecondLib\n'
        registerAppTypes('228')
        registerLibs([])
        // Two-entry registry for this test only.
        script.metaClass.getPackageLibraryRegistry = { ->
            [
                "mcp.McpSmokeTestLib": [namespace: "mcp", name: "McpSmokeTestLib", path: "libraries/mcp-smoke-test-lib.groovy"],
                "mcp.SecondLib": [namespace: "mcp", name: "SecondLib", path: "libraries/second-lib.groovy"]
            ]
        }
        def calls = []
        script.metaClass.toolInstallLibrary = { a -> calls << "install:${a.importUrl}".toString(); [success: true, libraryId: '500'] }
        script.metaClass.toolUpdateAppCode = { a -> calls << 'app'; [success: true] }

        when:
        def result = script.toolUpdatePackage([ref: 'main', confirm: true])

        then: 'both libraries installed before the single app save'
        calls.size() == 3
        calls[2] == 'app'
        calls[0].contains('mcp-smoke-test-lib.groovy')
        calls[1].contains('second-lib.groovy')
        result.success == true
        result.libraries.size() == 2
    }

    def "namespace+name matching picks the right library when names collide across namespaces"() {
        given:
        enableDev()
        nextHttpBody = APP_WITH_SMOKE  // needs mcp.McpSmokeTestLib
        registerAppTypes('228')
        registerLibs([
            [id: 99, name: 'McpSmokeTestLib', namespace: 'other', version: 1],
            [id: 7, name: 'McpSmokeTestLib', namespace: 'mcp', version: 1]
        ])
        def updateArgs = null
        script.metaClass.toolInstallLibrary = { a -> [success: true] }
        script.metaClass.toolUpdateLibraryCode = { a -> updateArgs = a; [success: true, libraryId: '7'] }
        script.metaClass.toolUpdateAppCode = { a -> [success: true] }

        when:
        def result = script.toolUpdatePackage([ref: 'main', confirm: true])

        then: 'updates the mcp-namespace library (id 7), not the same-named other-namespace one (id 99)'
        updateArgs.libraryId == 7
        result.libraries[0].action == 'update'
        result.libraries[0].libraryId == '7'
    }

    def "an existing-library entry with no id falls back to CREATE"() {
        given:
        enableDev()
        nextHttpBody = APP_WITH_SMOKE
        registerAppTypes('228')
        registerLibs([[name: 'McpSmokeTestLib', namespace: 'mcp']])  // matches name+ns but has no id
        def calls = []
        script.metaClass.toolInstallLibrary = { a -> calls << 'install'; [success: true, libraryId: '500'] }
        script.metaClass.toolUpdateLibraryCode = { a -> calls << 'update'; [success: true] }
        script.metaClass.toolUpdateAppCode = { a -> calls << 'app'; [success: true] }

        when:
        def result = script.toolUpdatePackage([ref: 'main', confirm: true])

        then: 'no id -> cannot target an update -> create'
        calls == ['install', 'app']
        result.libraries[0].action == 'create'
    }

    // -------- the load-bearing guarantee: library failure never reaches the app --------

    def "aborts BEFORE the app save when a library install is accepted but unverified"() {
        given:
        enableDev()
        nextHttpBody = APP_WITH_SMOKE
        registerAppTypes('228')
        registerLibs([])
        def calls = []
        // hub_create_library returns success=true but verified=false on a transient
        // post-install verify-fetch failure -- the install was accepted, not confirmed.
        script.metaClass.toolInstallLibrary = { a -> calls << 'install'; [success: true, verified: false, verifyError: 'verify endpoint unavailable', libraryId: '500'] }
        script.metaClass.toolUpdateAppCode = { a -> calls << 'app'; [success: true] }

        when:
        def result = script.toolUpdatePackage([ref: 'main', confirm: true])

        then: 'unverified install is a blocker; the app leg is NEVER called'
        calls == ['install']
        result.success == false
        result.aborted == true
        result.abortReason == 'library_unverified'
        result.error.contains('App NOT touched')
    }

    def "aborts (no writes) when the existing-library list is not a JSON array"() {
        given:
        enableDev()
        nextHttpBody = APP_WITH_SMOKE
        registerAppTypes('228')
        hubGet.register('/hub2/userLibraries') { params -> '{"unexpected":"shape"}' }  // Map, not List
        def calls = []
        script.metaClass.toolInstallLibrary = { a -> calls << 'install'; [success: true] }
        script.metaClass.toolUpdateAppCode = { a -> calls << 'app'; [success: true] }

        when:
        def result = script.toolUpdatePackage([ref: 'main', confirm: true])

        then:
        result.success == false
        result.aborted == true
        result.abortReason == 'library_list_unparseable'
        calls == []
    }

    def "aborts BEFORE the app save when a library install reports failure"() {
        given:
        enableDev()
        nextHttpBody = APP_WITH_SMOKE
        registerAppTypes('228')
        registerLibs([])
        def calls = []
        script.metaClass.toolInstallLibrary = { a -> calls << 'install'; [success: false, error: 'library compile error'] }
        script.metaClass.toolUpdateAppCode = { a -> calls << 'app'; [success: true] }

        when:
        def result = script.toolUpdatePackage([ref: 'main', confirm: true])

        then: 'the app leg is NEVER called'
        calls == ['install']

        and:
        result.success == false
        result.aborted == true
        result.abortReason == 'library_write_failed'
        result.error.contains('App NOT touched')
    }

    def "aborts BEFORE the app save when a library write throws"() {
        given:
        enableDev()
        nextHttpBody = APP_WITH_SMOKE
        registerAppTypes('228')
        registerLibs([])
        def calls = []
        script.metaClass.toolInstallLibrary = { a -> calls << 'install'; throw new RuntimeException('boom') }
        script.metaClass.toolUpdateAppCode = { a -> calls << 'app'; [success: true] }

        when:
        def result = script.toolUpdatePackage([ref: 'main', confirm: true])

        then:
        calls == ['install']
        result.success == false
        result.aborted == true
        result.abortReason == 'library_write_threw'
    }

    def "aborts (no writes) when the existing-library list cannot be fetched"() {
        given:
        enableDev()
        nextHttpBody = APP_WITH_SMOKE
        registerAppTypes('228')
        hubGet.register('/hub2/userLibraries') { params -> throw new RuntimeException('list unavailable') }
        def calls = []
        script.metaClass.toolInstallLibrary = { a -> calls << 'install'; [success: true] }
        script.metaClass.toolUpdateAppCode = { a -> calls << 'app'; [success: true] }

        when:
        def result = script.toolUpdatePackage([ref: 'main', confirm: true])

        then:
        result.success == false
        result.aborted == true
        result.abortReason == 'library_list_failed'
        calls == []
    }

    // -------- app-leg failure is surfaced (libraries already in place) --------

    def "surfaces app-update failure after libraries succeed"() {
        given:
        enableDev()
        nextHttpBody = APP_WITH_SMOKE
        registerAppTypes('228')
        registerLibs([])
        script.metaClass.toolInstallLibrary = { a -> [success: true, libraryId: '500'] }
        script.metaClass.toolUpdateAppCode = { a -> [success: false, error: 'app compile error'] }

        when:
        def result = script.toolUpdatePackage([ref: 'main', confirm: true])

        then:
        result.success == false
        result.libraries.size() == 1
        result.libraries[0].success == true
        result.app.success == false
        result.message.contains('hub_update_app remains available')
    }

    def "app-update THROW (self-update recompile loses the response) returns partial, never propagates"() {
        // The most probable real-world outcome: the app save lands but the running server
        // recompiles mid-call and the response is lost, so toolUpdateAppCode throws. The
        // tool must catch it and report partial -- libraries are already in place.
        given:
        enableDev()
        nextHttpBody = APP_WITH_SMOKE
        registerAppTypes('228')
        registerLibs([])
        script.metaClass.toolInstallLibrary = { a -> [success: true, libraryId: '500'] }
        script.metaClass.toolUpdateAppCode = { a -> throw new RuntimeException('self-update recompile lost response') }

        when:
        def result = script.toolUpdatePackage([ref: 'main', confirm: true])

        then: 'caught and reported, not thrown'
        noExceptionThrown()
        result.success == false
        result.partial == true
        result.abortReason == 'app_update_threw'
        result.libraries.size() == 1
        result.libraries[0].success == true
        result.error.contains('Verify with hub_get_source')
    }

    // -------- helper unit: include parsing --------

    def "_parseIncludeDirectives parses, de-dups, and preserves first-seen order"() {
        when:
        def tokens = script._parseIncludeDirectives(
            'foo\n#include mcp.LibA\nbar\n#include  ns.LibB\n#include mcp.LibA\n// not: #include mcp.Commented inline\n')

        then:
        tokens == ['mcp.LibA', 'ns.LibB']
    }

    def "_parseIncludeDirectives returns empty when there are no includes"() {
        expect:
        script._parseIncludeDirectives(APP_NO_INCLUDE) == []
    }

    // -------- dispatch happy path --------

    @spock.lang.Unroll
    def "dryRun via dispatch returns a success envelope with the plan (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableDev()
        nextHttpBody = APP_WITH_SMOKE
        registerAppTypes('228')

        when:
        def response = mcpDriver.callTool('hub_update_package', [ref: 'main', dryRun: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.dryRun == true
        inner.plannedLibraries.size() == 1

        where:
        useGateways << [true, false]
    }
}
