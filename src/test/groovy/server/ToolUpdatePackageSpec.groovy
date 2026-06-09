package server

import spock.lang.Shared
import support.ToolSpecBase

/**
 * Spec for hub_update_package (toolUpdatePackage) -- the Developer Mode package
 * deploy tool (issue #250). It deploys a git ref the way Hubitat Package Manager's
 * performRepair does -- OVERRIDE whatever is installed -- but anchored to the
 * manifest AT the target ref (so it can install unmerged PRs):
 *
 *   fetch app source at ref (parse #includes for the coverage guard) -> fetch
 *   packageManifest.json at ref -> re-anchor each bundle/app location to the ref ->
 *   install every manifest bundle FIRST (override) -> deploy every manifest app,
 *   the SELF app (mcp / "MCP Rule Server") LAST so its recompile (which drops the
 *   in-flight response, #237) is the final act.
 *
 * The load-bearing guarantee under test: a package failure must never cost the main
 * app its ability to update. These specs pin (a) bundles are installed before any
 * app, (b) the self app is deployed last, (c) ANY pre-self-app failure aborts
 * without deploying the self app, and (d) the Developer-Mode gate.
 *
 * Mocking strategy:
 *   - The app-source AND manifest fetches both run through the real
 *     _fetchSourceFromUrl -> appExecutor.httpGet -- stubbed at spec scope and routed
 *     by URL (a packageManifest.json URL gets the manifest body; everything else the
 *     app-source body).
 *   - /hub2/userAppTypes (app-class lookup, parent + child) is stubbed via
 *     hubGet.register.
 *   - The inner write tools (toolInstallBundle / toolUpdateAppCode) are stubbed
 *     per-test on script.metaClass so the orchestration -- ordering and abort
 *     behaviour -- is what is exercised, not the (separately specced) bundle/app
 *     write internals.
 */
class ToolUpdatePackageSpec extends ToolSpecBase {

    private static final String APP_NO_INCLUDE =
        'definition(name: "MCP Rule Server", namespace: "mcp")\n\ndef foo() { return 1 }\n'
    private static final String APP_WITH_SMOKE =
        APP_NO_INCLUDE + '\n#include mcp.McpSmokeTestLib\n'
    private static final String APP_WITH_DUPE =
        APP_NO_INCLUDE + '\n#include mcp.McpSmokeTestLib\n#include   mcp.McpSmokeTestLib\n'

    private static final String RAW = 'https://raw.githubusercontent.com/kingpanther13/Hubitat-local-MCP-server'

    // Full manifest: 1 library bundle + parent app (self) + child app. Locations are
    // pinned to /main (as committed); the tool re-anchors them to the deploy ref.
    private static String manifest(List bundles, List apps) {
        groovy.json.JsonOutput.toJson([packageName: 'MCP Rule Server', bundles: bundles, apps: apps])
    }
    private static final List BUNDLE_LIBS =
        [[name: 'MCP Rule Server Libraries', namespace: 'mcp', location: "${RAW}/main/bundles/mcp-libraries.zip".toString(), required: true]]
    private static final List APPS_BOTH = [
        [name: 'MCP Rule Server', namespace: 'mcp', location: "${RAW}/main/hubitat-mcp-server.groovy".toString(), required: true, primary: true],
        [name: 'MCP Rule', namespace: 'mcp', location: "${RAW}/main/hubitat-mcp-rule.groovy".toString(), required: true, primary: false]
    ]
    private static final String MANIFEST_FULL = manifest(BUNDLE_LIBS, APPS_BOTH)
    private static final String MANIFEST_NO_BUNDLE = manifest([], APPS_BOTH)

    @Shared int nextHttpStatus = 200
    @Shared String nextHttpBody = ''
    @Shared Throwable nextHttpThrow = null
    @Shared int nextManifestStatus = 200
    @Shared String nextManifestBody = ''
    @Shared Throwable nextManifestThrow = null

    def setupSpec() {
        // Single global httpGet stub, routed by URL: the manifest URL gets the
        // manifest body/throw; everything else (the app source) gets the app body.
        // _fetchSourceFromUrl -> _httpFetchUrl -> appExecutor.httpGet([uri:...]) { }.
        appExecutor.httpGet(*_) >> { callArgs ->
            def uri = (callArgs[0] instanceof Map) ? callArgs[0].uri?.toString() : ''
            Closure handler = callArgs[1] as Closure
            if (uri?.contains('packageManifest.json')) {
                if (nextManifestThrow) throw nextManifestThrow
                handler.call([status: nextManifestStatus, data: [text: nextManifestBody], headers: [:]])
            } else {
                if (nextHttpThrow) throw nextHttpThrow
                handler.call([status: nextHttpStatus, data: [text: nextHttpBody], headers: [:]])
            }
        }
    }

    def setup() {
        nextHttpStatus = 200
        nextHttpBody = APP_WITH_SMOKE
        nextHttpThrow = null
        nextManifestStatus = 200
        nextManifestBody = MANIFEST_FULL
        nextManifestThrow = null
    }

    private void enableDev() {
        settingsMap.enableDeveloperMode = true
        settingsMap.enableWrite = true
        // Matches the fixed now() == 1234567890000L so requireDestructiveConfirm's
        // <24h backup gate passes for real (non-dry-run) deploys.
        stateMap.lastBackupTimestamp = 1234567890000L
    }

    private void registerAppTypes() {
        hubGet.register('/hub2/userAppTypes') { params ->
            groovy.json.JsonOutput.toJson([
                [id: '228', name: 'MCP Rule Server', namespace: 'mcp'],
                [id: '230', name: 'MCP Rule', namespace: 'mcp'],
                [id: '999', name: 'Some Other App', namespace: 'other']
            ])
        }
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
        script.metaClass.toolInstallBundle = { a -> calls << 'bundle'; [success: true] }
        script.metaClass.toolUpdateAppCode = { a -> calls << 'app'; [success: true] }
        registerAppTypes()

        when:
        script.toolUpdatePackage([ref: 'main'])  // confirm omitted

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')

        and: 'nothing was written'
        calls == []
    }

    // -------- dryRun: parse + resolve, ZERO writes --------

    def "dryRun reports the full-repair plan (bundles + apps, self last) and performs no writes"() {
        given:
        enableDev()
        registerAppTypes()
        def calls = []
        script.metaClass.toolInstallBundle = { a -> calls << 'bundle'; [success: true] }
        script.metaClass.toolUpdateAppCode = { a -> calls << 'app'; [success: true] }

        when:
        def result = script.toolUpdatePackage([ref: 'feat/x', dryRun: true])

        then:
        result.success == true
        result.dryRun == true
        result.ref == 'feat/x'
        result.includes == ['mcp.McpSmokeTestLib']

        and: 'one bundle, re-anchored to the deploy ref'
        result.plannedBundles.size() == 1
        result.plannedBundles[0].url.endsWith('/feat/x/bundles/mcp-libraries.zip')

        and: 'both apps planned, class ids resolved, self flagged, urls re-anchored to the ref'
        result.plannedApps.size() == 2
        def self = result.plannedApps.find { it.isSelf }
        def child = result.plannedApps.find { !it.isSelf }
        self.name == 'MCP Rule Server'
        self.classId == '228'
        self.url.endsWith('/feat/x/hubitat-mcp-server.groovy')
        child.name == 'MCP Rule'
        child.classId == '230'
        child.url.endsWith('/feat/x/hubitat-mcp-rule.groovy')

        and: 'no confirm/backup required and nothing written'
        calls == []
    }

    def "baseUrl override drives URL construction and strips a trailing slash"() {
        given:
        enableDev()
        registerAppTypes()

        when: 'baseUrl has a trailing slash that must be normalized away'
        def result = script.toolUpdatePackage([ref: 'main', dryRun: true, baseUrl: 'https://example.com/raw/'])

        then: 'the manifest is read from the override; bundle + app URLs re-anchor to it'
        result.plannedBundles[0].url == 'https://example.com/raw/main/bundles/mcp-libraries.zip'
        result.plannedApps.find { it.isSelf }.url == 'https://example.com/raw/main/hubitat-mcp-server.groovy'
        result.plannedApps.find { !it.isSelf }.url == 'https://example.com/raw/main/hubitat-mcp-rule.groovy'
    }

    // -------- fail-closed aborts BEFORE any write --------

    def "aborts (no writes) when a manifest app-class id cannot be resolved"() {
        given:
        enableDev()
        hubGet.register('/hub2/userAppTypes') { params ->
            // Parent resolves but the child ("MCP Rule") is missing.
            groovy.json.JsonOutput.toJson([[id: '228', name: 'MCP Rule Server', namespace: 'mcp']])
        }
        def calls = []
        script.metaClass.toolInstallBundle = { a -> calls << 'bundle'; [success: true] }
        script.metaClass.toolUpdateAppCode = { a -> calls << 'app'; [success: true] }

        when:
        def result = script.toolUpdatePackage([ref: 'main', confirm: true])

        then:
        result.success == false
        result.aborted == true
        result.abortReason == 'app_class_unresolved'
        calls == []
    }

    def "aborts (no writes) when the app source fetch fails"() {
        given:
        enableDev()
        registerAppTypes()
        nextHttpThrow = new RuntimeException('connection refused')
        def calls = []
        script.metaClass.toolInstallBundle = { a -> calls << 'bundle'; [success: true] }
        script.metaClass.toolUpdateAppCode = { a -> calls << 'app'; [success: true] }

        when:
        def result = script.toolUpdatePackage([ref: 'main', confirm: true])

        then:
        result.success == false
        result.aborted == true
        result.abortReason == 'app_source_fetch_failed'
        calls == []
    }

    def "aborts (no writes) when the manifest fetch fails"() {
        given:
        enableDev()
        registerAppTypes()
        nextManifestThrow = new RuntimeException('connection refused')
        def calls = []
        script.metaClass.toolInstallBundle = { a -> calls << 'bundle'; [success: true] }
        script.metaClass.toolUpdateAppCode = { a -> calls << 'app'; [success: true] }

        when:
        def result = script.toolUpdatePackage([ref: 'main', confirm: true])

        then:
        result.success == false
        result.aborted == true
        result.abortReason == 'manifest_fetch_failed'
        calls == []
    }

    def "aborts (no writes) when the manifest is not valid JSON object"() {
        given:
        enableDev()
        registerAppTypes()
        nextManifestBody = '[]'  // JSON array, not the expected object
        def calls = []
        script.metaClass.toolInstallBundle = { a -> calls << 'bundle'; [success: true] }
        script.metaClass.toolUpdateAppCode = { a -> calls << 'app'; [success: true] }

        when:
        def result = script.toolUpdatePackage([ref: 'main', confirm: true])

        then:
        result.success == false
        result.aborted == true
        result.abortReason == 'manifest_unparseable'
        calls == []
    }

    def "aborts (no writes) when a manifest bundle location can't be re-anchored to the ref"() {
        given:
        enableDev()
        registerAppTypes()
        // A bundle whose location isn't a scheme://host/owner/repo/ref/<path> raw URL -> _reanchorToRef null.
        nextManifestBody = manifest([[name: 'Bad Bundle', location: 'not-a-real-url']], APPS_BOTH)
        def calls = []
        script.metaClass.toolInstallBundle = { a -> calls << 'bundle'; [success: true] }
        script.metaClass.toolUpdateAppCode = { a -> calls << 'app'; [success: true] }

        when:
        def result = script.toolUpdatePackage([ref: 'main', confirm: true])

        then:
        result.success == false
        result.aborted == true
        result.abortReason == 'manifest_unparseable'
        result.error.contains('unusable location')
        calls == []
    }

    def "aborts (no writes) when a manifest app location can't be re-anchored to the ref"() {
        given:
        enableDev()
        registerAppTypes()
        // The child app's location is malformed -> _reanchorToRef null -> app_class_unresolved abort.
        def apps = [
            [name: 'MCP Rule Server', namespace: 'mcp', location: "${RAW}/main/hubitat-mcp-server.groovy".toString(), required: true],
            [name: 'MCP Rule', namespace: 'mcp', location: 'garbage', required: true]
        ]
        nextManifestBody = manifest(BUNDLE_LIBS, apps)
        def calls = []
        script.metaClass.toolInstallBundle = { a -> calls << 'bundle'; [success: true] }
        script.metaClass.toolUpdateAppCode = { a -> calls << 'app'; [success: true] }

        when:
        def result = script.toolUpdatePackage([ref: 'main', confirm: true])

        then:
        result.success == false
        result.aborted == true
        result.abortReason == 'app_class_unresolved'
        calls == []
    }

    def "aborts (no writes) when the app #includes a library but the manifest declares no bundle"() {
        given:
        enableDev()
        registerAppTypes()
        nextHttpBody = APP_WITH_SMOKE          // has an #include
        nextManifestBody = MANIFEST_NO_BUNDLE  // but no bundle to deliver it
        def calls = []
        script.metaClass.toolInstallBundle = { a -> calls << 'bundle'; [success: true] }
        script.metaClass.toolUpdateAppCode = { a -> calls << 'app'; [success: true] }

        when:
        def result = script.toolUpdatePackage([ref: 'main', confirm: true])

        then:
        result.success == false
        result.aborted == true
        result.abortReason == 'bundle_required_but_undeclared'
        calls == []
    }

    // -------- real deploy: ordering (bundles first, self app last) --------

    def "full deploy installs the bundle FIRST, then the child app, then the self app LAST"() {
        given:
        enableDev()
        registerAppTypes()
        def calls = []
        def bundleArgs = null
        script.metaClass.toolInstallBundle = { a -> bundleArgs = a; calls << 'bundle'; [success: true] }
        script.metaClass.toolUpdateAppCode = { a -> calls << "app:${a.appId}".toString(); [success: true, appId: a.appId] }

        when:
        def result = script.toolUpdatePackage([ref: 'main', confirm: true])

        then: 'bundle -> child (230) -> self (228) last'
        calls == ['bundle', 'app:230', 'app:228']

        and: 'bundle installed by re-anchored URL, with confirm'
        bundleArgs.importUrl.endsWith('/main/bundles/mcp-libraries.zip')
        bundleArgs.confirm == true

        and:
        result.success == true
        result.bundles.size() == 1
        result.bundles[0].success == true
        result.apps.size() == 2
        result.apps.every { it.success == true }
    }

    def "a no-include app with no bundle deploys apps only (self last), no bundle step"() {
        given:
        enableDev()
        registerAppTypes()
        nextHttpBody = APP_NO_INCLUDE
        nextManifestBody = MANIFEST_NO_BUNDLE
        def calls = []
        script.metaClass.toolInstallBundle = { a -> calls << 'bundle'; [success: true] }
        script.metaClass.toolUpdateAppCode = { a -> calls << "app:${a.appId}".toString(); [success: true, appId: a.appId] }

        when:
        def result = script.toolUpdatePackage([ref: 'main', confirm: true])

        then:
        calls == ['app:230', 'app:228']
        result.success == true
        result.bundles == []
        result.apps.size() == 2
    }

    // -------- the load-bearing guarantee: a pre-self-app failure never reaches the self app --------

    def "aborts BEFORE any app when a bundle install reports failure"() {
        given:
        enableDev()
        registerAppTypes()
        def calls = []
        script.metaClass.toolInstallBundle = { a -> calls << 'bundle'; [success: false, error: 'bundle unreachable'] }
        script.metaClass.toolUpdateAppCode = { a -> calls << 'app'; [success: true] }

        when:
        def result = script.toolUpdatePackage([ref: 'main', confirm: true])

        then: 'no app leg is ever called'
        calls == ['bundle']
        result.success == false
        result.aborted == true
        result.abortReason == 'bundle_install_failed'
        result.error.contains('NOT touched')
    }

    def "aborts BEFORE any app when a bundle install throws"() {
        given:
        enableDev()
        registerAppTypes()
        def calls = []
        script.metaClass.toolInstallBundle = { a -> calls << 'bundle'; throw new RuntimeException('boom') }
        script.metaClass.toolUpdateAppCode = { a -> calls << 'app'; [success: true] }

        when:
        def result = script.toolUpdatePackage([ref: 'main', confirm: true])

        then:
        calls == ['bundle']
        result.success == false
        result.aborted == true
        result.abortReason == 'bundle_install_threw'
    }

    def "aborts BEFORE the self app when the child app update fails (self never touched)"() {
        given:
        enableDev()
        registerAppTypes()
        def calls = []
        script.metaClass.toolInstallBundle = { a -> calls << 'bundle'; [success: true] }
        script.metaClass.toolUpdateAppCode = { a ->
            calls << "app:${a.appId}".toString()
            (a.appId == '230') ? [success: false, error: 'child compile error'] : [success: true]
        }

        when:
        def result = script.toolUpdatePackage([ref: 'main', confirm: true])

        then: 'child (230) attempted, self (228) NEVER reached'
        calls == ['bundle', 'app:230']
        result.success == false
        result.aborted == true
        result.abortReason == 'app_update_failed'
        result.error.contains('NOT touched')
    }

    // -------- self app leg: failure surfaced, recompile-drop is partial --------

    def "surfaces self-app update failure after bundle + child succeed"() {
        given:
        enableDev()
        registerAppTypes()
        script.metaClass.toolInstallBundle = { a -> [success: true] }
        script.metaClass.toolUpdateAppCode = { a -> (a.appId == '228') ? [success: false, error: 'self compile error'] : [success: true, appId: a.appId] }

        when:
        def result = script.toolUpdatePackage([ref: 'main', confirm: true])

        then:
        result.success == false
        result.partial == true   // same partial state as the throw path: bundle + child landed, self did not
        result.bundles[0].success == true
        result.apps.find { it.isSelf }.success == false
        result.message.contains('hub_update_app remains available')
    }

    def "self-app update THROW (recompile loses the response) returns partial, never propagates"() {
        // The most probable real-world outcome: the self app save lands but the running
        // server recompiles mid-call and the response is lost, so toolUpdateAppCode
        // throws. The tool must catch it and report partial -- bundle + child already in place.
        given:
        enableDev()
        registerAppTypes()
        script.metaClass.toolInstallBundle = { a -> [success: true] }
        script.metaClass.toolUpdateAppCode = { a ->
            if (a.appId == '228') throw new RuntimeException('self-update recompile lost response')
            [success: true, appId: a.appId]
        }

        when:
        def result = script.toolUpdatePackage([ref: 'main', confirm: true])

        then: 'caught and reported, not thrown'
        noExceptionThrown()
        result.success == false
        result.partial == true
        result.abortReason == 'app_update_threw'
        result.error.contains('Verify with hub_get_source')
    }

    // -------- de-dup of include parsing (coverage guard input) --------

    def "duplicate #include of the same library still resolves a single include token"() {
        given:
        enableDev()
        registerAppTypes()
        nextHttpBody = APP_WITH_DUPE
        script.metaClass.toolInstallBundle = { a -> [success: true] }
        script.metaClass.toolUpdateAppCode = { a -> [success: true, appId: a.appId] }

        when:
        def result = script.toolUpdatePackage([ref: 'main', confirm: true])

        then:
        result.includes == ['mcp.McpSmokeTestLib']
        result.success == true
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
        registerAppTypes()

        when:
        def response = mcpDriver.callTool('hub_update_package', [ref: 'main', dryRun: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.dryRun == true
        inner.plannedBundles.size() == 1
        inner.plannedApps.size() == 2

        where:
        useGateways << [true, false]
    }
}
