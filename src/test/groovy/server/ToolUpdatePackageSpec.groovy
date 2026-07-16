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
    private static final String APP_WITH_INCLUDE =
        APP_NO_INCLUDE + '\n#include mcp.McpRoomsLib\n'
    private static final String APP_WITH_DUPE =
        APP_NO_INCLUDE + '\n#include mcp.McpRoomsLib\n#include   mcp.McpRoomsLib\n'

    private static final String RAW = 'https://raw.githubusercontent.com/kingpanther13/Hubitat-local-MCP-server'

    // Full manifest: 1 library bundle + parent app (self) + child app. Locations are
    // pinned to /main (as committed); the tool re-anchors them to the deploy ref.
    private static String manifest(List bundles, List apps) {
        groovy.json.JsonOutput.toJson([packageName: 'MCP Rule Server', bundles: bundles, apps: apps])
    }
    // Unified-delivery manifest shape: the bundle lives on the bundle-artifacts branch.
    private static final List BUNDLE_LIBS =
        [[name: 'MCP Rule Server Libraries', namespace: 'mcp', location: "${RAW}/bundle-artifacts/branches/main/mcp-libraries.zip".toString(), required: true]]
    // Legacy (pre-unification) manifest shape: in-tree committed zip -- old refs still carry this.
    private static final List BUNDLE_LIBS_LEGACY =
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
        nextHttpBody = APP_WITH_INCLUDE
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
        result.includes == ['mcp.McpRoomsLib']

        and: 'one bundle: no per-ref artifact in this stub world, so the plan falls back to the manifest-current branches/main zip'
        result.plannedBundles.size() == 1
        result.plannedBundles[0].url == "${RAW}/bundle-artifacts/branches/main/mcp-libraries.zip".toString()
        result.plannedBundles[0].source == 'manifest-current'

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

    def "aborts (no writes) on a SHA-shaped ref whose per-ref bundle artifact does not exist"() {
        given: 'an abbreviated/typo SHA (hex) -- its per-ref artifact is absent in this stub world'
        enableDev()
        registerAppTypes()
        def calls = []
        script.metaClass.toolInstallBundle = { a -> calls << 'bundle'; [success: true] }
        script.metaClass.toolUpdateAppCode = { a -> calls << 'app'; [success: true] }

        when:
        def result = script.toolUpdatePackage([ref: '8cb66c6', dryRun: true])

        then: 'it aborts instead of silently delivering MAIN libraries via the manifest-current branches/main zip'
        result.success == false
        result.aborted == true
        result.abortReason == 'no_bundle_artifact_for_ref'
        result.error.contains('8cb66c6')

        and: 'nothing written'
        calls == []
    }

    def "a full SHA WITH a published per-ref artifact uses the shas/<sha>/ zip (the guard's accept path, NOT rejected)"() {
        given: 'a full 40-char SHA whose per-ref artifact exists (the complement of the abbreviated-SHA reject above)'
        enableDev()
        registerAppTypes()
        script.metaClass.toolInstallBundle = { a -> [success: true] }
        script.metaClass.toolUpdateAppCode = { a -> [success: true] }
        script.metaClass._bundleArtifactExists = { String u -> true }
        def fullSha = 'a' * 40   // 40 hex chars -> keyPath shas/<sha>

        when:
        def result = script.toolUpdatePackage([ref: fullSha, confirm: true])

        then: 'the SHA is NOT rejected -- the bundle resolves to its per-SHA artifact, not manifest-current'
        result.success == true
        result.aborted != true
        result.bundles[0].source == 'bundle-artifacts'
        result.bundles[0].url == "${RAW}/bundle-artifacts/shas/${fullSha}/mcp-libraries.zip".toString()
    }

    def "baseUrl override drives URL construction and strips a trailing slash"() {
        given:
        enableDev()
        registerAppTypes()

        when: 'baseUrl has a trailing slash that must be normalized away'
        def result = script.toolUpdatePackage([ref: 'main', dryRun: true, baseUrl: 'https://example.com/raw/'])

        then: 'the manifest is read from the override; APP urls re-anchor to it, while the artifact-shaped bundle keeps the manifest LITERAL location (the authoritative user-facing URL)'
        result.plannedBundles[0].url == "${RAW}/bundle-artifacts/branches/main/mcp-libraries.zip".toString()
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
        result.abortReason == 'bundle_location_unusable'
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
        nextHttpBody = APP_WITH_INCLUDE        // has an #include
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

        and: 'bundle installed from the manifest-current branches/main URL, with confirm'
        bundleArgs.importUrl.endsWith('/bundle-artifacts/branches/main/mcp-libraries.zip')
        bundleArgs.confirm == true

        and:
        result.success == true
        result.bundles.size() == 1
        result.bundles[0].success == true
        result.apps.size() == 2
        result.apps.every { it.success == true }
    }

    def "with TWO non-self apps, both deploy in manifest order, then the self app LAST"() {
        // Pins that the self-app-last ordering preserves the non-self apps' relative manifest order
        // (not just 'self is last'); a single non-self app can't distinguish the two.
        given:
        enableDev()
        hubGet.register('/hub2/userAppTypes') { params ->
            groovy.json.JsonOutput.toJson([
                [id: '228', name: 'MCP Rule Server', namespace: 'mcp'],
                [id: '230', name: 'MCP Rule', namespace: 'mcp'],
                [id: '231', name: 'MCP Extra', namespace: 'mcp']
            ])
        }
        // Manifest lists the SELF app first, then two non-self apps -- the tool must still emit them
        // non-self-first (in their manifest order: 230 then 231) and the self app (228) last.
        def apps = [
            [name: 'MCP Rule Server', namespace: 'mcp', location: "${RAW}/main/hubitat-mcp-server.groovy".toString(), required: true, primary: true],
            [name: 'MCP Rule', namespace: 'mcp', location: "${RAW}/main/hubitat-mcp-rule.groovy".toString(), required: true],
            [name: 'MCP Extra', namespace: 'mcp', location: "${RAW}/main/mcp-extra.groovy".toString(), required: true]
        ]
        nextManifestBody = manifest(BUNDLE_LIBS, apps)
        def calls = []
        script.metaClass.toolInstallBundle = { a -> calls << 'bundle'; [success: true] }
        script.metaClass.toolUpdateAppCode = { a -> calls << "app:${a.appId}".toString(); [success: true, appId: a.appId] }

        when:
        def result = script.toolUpdatePackage([ref: 'main', confirm: true])

        then: 'bundle -> child (230) -> extra (231) -> self (228) LAST'
        calls == ['bundle', 'app:230', 'app:231', 'app:228']
        result.success == true
        result.apps.size() == 3
        result.apps[-1].isSelf == true
        result.apps[-1].classId == '228'
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

    // -------- issue #351: in-flight deploy guard --------
    // A package deploy takes minutes; a client-side timeout tempts the agent into
    // re-running the whole repair while the first run is still committing (the
    // double-deploy the issue documents). The guard refuses a concurrent second
    // deploy outright -- recovery is polling, never a re-run.

    private static final long GUARD_NOW = 1234567890000L

    def "a second real deploy is refused while one is in flight, with zero writes"() {
        given:
        enableDev()
        registerAppTypes()
        def calls = []
        script.metaClass.toolInstallBundle = { a -> calls << 'bundle'; [success: true] }
        script.metaClass.toolUpdateAppCode = { a -> calls << 'app'; [success: true, appId: a.appId] }
        atomicStateMap.packageDeployInFlight = [ref: 'feat/other', startedAt: GUARD_NOW - 60000L]

        when:
        def result = script.toolUpdatePackage([ref: 'main', confirm: true])

        then: 'refused as a runtime error (not thrown), naming the in-flight deploy'
        result.success == false
        result.isError == true
        result.error.toLowerCase().contains('already')
        result.inFlight.ref == 'feat/other'
        result.inFlight.elapsedMs == 60000L

        and: 'recovery guidance points at polling, never re-running'
        result.note.contains('opToken')
        result.note.contains('lastSelfDeploy')

        and: 'nothing was written'
        calls == []
    }

    def "the guard stands down when lastSelfDeploy postdates it -- the deploy finished, only its response was lost"() {
        given:
        enableDev()
        registerAppTypes()
        def calls = []
        script.metaClass.toolInstallBundle = { a -> calls << 'bundle'; [success: true] }
        script.metaClass.toolUpdateAppCode = { a -> calls << 'app'; [success: true, appId: a.appId] }
        atomicStateMap.packageDeployInFlight = [ref: 'feat/other', startedAt: GUARD_NOW - 120000L]
        atomicStateMap.lastSelfDeploy = [success: true, at: GUARD_NOW - 10000L]

        when:
        def result = script.toolUpdatePackage([ref: 'main', confirm: true])

        then:
        result.success == true
        calls.size() == 3   // bundle + child app + self app
    }

    def "the guard expires after its TTL (a wedged marker cannot block deploys forever)"() {
        given:
        enableDev()
        registerAppTypes()
        script.metaClass.toolInstallBundle = { a -> [success: true] }
        script.metaClass.toolUpdateAppCode = { a -> [success: true, appId: a.appId] }
        atomicStateMap.packageDeployInFlight = [ref: 'feat/other', startedAt: GUARD_NOW - (11L * 60L * 1000L)]

        when:
        def result = script.toolUpdatePackage([ref: 'main', confirm: true])

        then:
        result.success == true
    }

    def "the guard is cleared on a clean finish"() {
        given:
        enableDev()
        registerAppTypes()
        script.metaClass.toolInstallBundle = { a -> [success: true] }
        script.metaClass.toolUpdateAppCode = { a -> [success: true, appId: a.appId] }

        when:
        def result = script.toolUpdatePackage([ref: 'main', confirm: true])

        then:
        result.success == true
        atomicStateMap.packageDeployInFlight == null
    }

    def "the guard is cleared when the deploy aborts before the self app"() {
        given:
        enableDev()
        registerAppTypes()
        script.metaClass.toolInstallBundle = { a -> [success: false, error: 'zip fetch failed'] }
        script.metaClass.toolUpdateAppCode = { a -> [success: true, appId: a.appId] }

        when:
        def result = script.toolUpdatePackage([ref: 'main', confirm: true])

        then:
        result.success == false
        result.aborted == true
        atomicStateMap.packageDeployInFlight == null
    }

    def "dryRun neither checks nor sets the guard"() {
        given:
        enableDev()
        registerAppTypes()
        def seeded = [ref: 'feat/other', startedAt: GUARD_NOW - 60000L]
        atomicStateMap.packageDeployInFlight = seeded

        when: 'a plan-only run while a deploy is in flight'
        def result = script.toolUpdatePackage([ref: 'main', dryRun: true])

        then: 'the read-only plan is served and the live marker is untouched'
        result.success == true
        result.dryRun == true
        atomicStateMap.packageDeployInFlight == seeded
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
        result.includes == ['mcp.McpRoomsLib']
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

    // -------- helper unit: re-anchor a manifest location to the ref --------

    @spock.lang.Unroll
    def "_reanchorToRef strips host/owner/repo/ref and rebuilds against base+ref (#desc)"() {
        // The load-bearing transform that makes unmerged-PR installs work: strip exactly the 4-segment
        // host/owner/repo/branch prefix, keep the rest of the path, re-anchor to base/ref. Pins the exact
        // boundary behaviour so a regex change that strips one segment too many/few is caught.
        expect:
        script._reanchorToRef(location, base, ref) == expected

        where:
        desc                      | location                                                              | base                  | ref    || expected
        'canonical bundle'        | 'https://raw.githubusercontent.com/o/r/main/bundles/mcp-libraries.zip' | 'https://example/raw' | 'feat' || 'https://example/raw/feat/bundles/mcp-libraries.zip'
        'deeper relpath survives' | 'https://raw.githubusercontent.com/o/r/main/bundles/sub/x.zip'         | 'https://b'           | 'sha1' || 'https://b/sha1/bundles/sub/x.zip'
        'http scheme'             | 'http://raw.githubusercontent.com/o/r/main/hubitat-mcp-rule.groovy'    | 'https://b'           | 'main' || 'https://b/main/hubitat-mcp-rule.groovy'
        'top-level file'          | 'https://raw.githubusercontent.com/o/r/main/hubitat-mcp-server.groovy' | 'https://b'           | 'r2'   || 'https://b/r2/hubitat-mcp-server.groovy'
    }

    @spock.lang.Unroll
    def "_reanchorToRef returns null (fail-closed) for an unusable location: #desc"() {
        expect:
        script._reanchorToRef(location, 'https://b', 'main') == null

        where:
        desc                | location
        'too few segments'  | 'https://raw.githubusercontent.com/o/r/main'
        'no path after ref' | 'https://raw.githubusercontent.com/o/r/main/'
        'not a url'         | 'not-a-real-url'
        'blank'             | '   '
        'null'              | null
        'non-string'        | 42
    }

    // -------- bundle-artifacts resolution (PRs no longer commit bundles/*.zip) --------

    @spock.lang.Unroll
    def "_bundleArtifactUrlForRef keys by branch or full SHA, keeping the zip basename (#desc)"() {
        // The basename MUST survive into the artifact path: the hub appears to key the bundle
        // entity on the zip filename, so a renamed zip imports as a SECOND entity (duplicate
        // libraries) instead of updating the existing one.
        expect:
        script._bundleArtifactUrlForRef(location, 'https://b', ref) == expected

        where:
        desc                       | location                                                                | ref        || expected
        'branch ref'               | 'https://raw.githubusercontent.com/o/r/main/bundles/mcp-libraries.zip' | 'feat/x'   || 'https://b/bundle-artifacts/branches/feat/x/mcp-libraries.zip'
        'full-sha ref'             | 'https://raw.githubusercontent.com/o/r/main/bundles/mcp-libraries.zip' | ('a' * 40) || "https://b/bundle-artifacts/shas/${'a' * 40}/mcp-libraries.zip".toString()
        'short sha = branch path'  | 'https://raw.githubusercontent.com/o/r/main/bundles/mcp-libraries.zip' | 'deadbee'  || 'https://b/bundle-artifacts/branches/deadbee/mcp-libraries.zip'
        '40 chars but NOT hex = branch path (predicate is hex-aware, not length-only)' \
                                   | 'https://raw.githubusercontent.com/o/r/main/bundles/mcp-libraries.zip' | ('g' * 40) || "https://b/bundle-artifacts/branches/${'g' * 40}/mcp-libraries.zip".toString()
        'unusable location'        | 'not-a-real-url'                                                        | 'feat/x'   || null
    }

    def "_bundleArtifactExists: integer .size marker is the only true; throws and junk bodies are false"() {
        // _httpFetchUrl is PRIVATE (metaClass stubs silently no-op on it -- docs/testing.md
        // dispatch table), so drive the probe through the spec-scope appExecutor.httpGet stub.
        when: 'the marker answers with the zip byte count'
        nextHttpStatus = 200
        nextHttpBody = '866882'
        then:
        script._bundleArtifactExists('https://b/bundle-artifacts/branches/x/mcp-libraries.zip')

        when: 'the fetch throws (raw 404)'
        nextHttpThrow = new IllegalStateException('404: Not Found')
        then:
        !script._bundleArtifactExists('https://b/bundle-artifacts/branches/x/mcp-libraries.zip')

        when: 'a 200 with a non-integer body (HTML error page, mis-routed content)'
        nextHttpThrow = null
        nextHttpBody = '<html>nope</html>'
        then:
        !script._bundleArtifactExists('https://b/bundle-artifacts/branches/x/mcp-libraries.zip')

        when: 'a 200 with an EMPTY body (zero-length/truncated .size marker)'
        nextHttpBody = ''
        then:
        !script._bundleArtifactExists('https://b/bundle-artifacts/branches/x/mcp-libraries.zip')
    }

    def "bundle leg prefers the bundle-artifacts zip when the artifact exists, with no freshness warning"() {
        given:
        enableDev()
        registerAppTypes()
        def installedUrls = []
        script.metaClass.toolInstallBundle = { a -> installedUrls << a.importUrl; [success: true] }
        script.metaClass.toolUpdateAppCode = { a -> [success: true] }
        script.metaClass._bundleArtifactExists = { String u -> true }

        when:
        def result = script.toolUpdatePackage([ref: 'feat/x', confirm: true])

        then:
        result.success == true
        result.bundles[0].source == 'bundle-artifacts'
        result.bundles[0].url == "${RAW}/bundle-artifacts/branches/feat/x/mcp-libraries.zip".toString()
        installedUrls == [result.bundles[0].url]
        result.bundleFreshnessWarning == null
    }

    def "bundle leg falls back to current main's manifest zip and warns on a non-main ref"() {
        given:
        enableDev()
        registerAppTypes()
        script.metaClass.toolInstallBundle = { a -> [success: true] }
        script.metaClass.toolUpdateAppCode = { a -> [success: true] }
        script.metaClass._bundleArtifactExists = { String u -> false }

        when:
        def result = script.toolUpdatePackage([ref: 'feat/x', confirm: true])

        then:
        result.success == true
        result.bundles[0].source == 'manifest-current'
        result.bundles[0].url.endsWith('/bundle-artifacts/branches/main/mcp-libraries.zip')
        result.bundleFreshnessWarning?.contains("CURRENT MAIN's bundle")
    }

    def "a LEGACY-manifest ref falls back to its committed-at-ref zip with the legacy warning"() {
        given:
        enableDev()
        registerAppTypes()
        nextManifestBody = manifest(BUNDLE_LIBS_LEGACY, APPS_BOTH)
        script.metaClass.toolInstallBundle = { a -> [success: true] }
        script.metaClass.toolUpdateAppCode = { a -> [success: true] }
        script.metaClass._bundleArtifactExists = { String u -> false }

        when:
        def result = script.toolUpdatePackage([ref: 'feat/x', confirm: true])

        then: 'old refs keep the old behaviour: zip committed AT the ref, reanchored'
        result.success == true
        result.bundles[0].source == 'committed-at-ref'
        result.bundles[0].url.endsWith('/feat/x/bundles/mcp-libraries.zip')
        result.bundleFreshnessWarning?.contains('COMMITTED at the ref')
    }

    def "a manifest-current fallback at ref=main surfaces the probe-failure note"() {
        given:
        // For ref=main the manifest's branches/main zip IS the correct current bundle; the
        // note exists because the artifact probe failing may indicate a transient raw
        // outage worth knowing about.
        enableDev()
        registerAppTypes()
        script.metaClass.toolInstallBundle = { a -> [success: true] }
        script.metaClass.toolUpdateAppCode = { a -> [success: true] }
        script.metaClass._bundleArtifactExists = { String u -> false }

        when:
        def result = script.toolUpdatePackage([ref: 'main', confirm: true])

        then:
        result.success == true
        result.bundles[0].source == 'manifest-current'
        result.bundleFreshnessWarning?.contains('branches/main zip directly')
        result.bundleFreshnessWarning?.contains('correct, current bundle')
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
