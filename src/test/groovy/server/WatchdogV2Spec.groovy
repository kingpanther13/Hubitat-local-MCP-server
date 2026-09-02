package server

import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.app.HubitatAppScript
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification
import spock.lang.Unroll
import support.PassThroughAppValidator
import support.PermissiveLog

/**
 * Hub-less coverage of the v2 dead-man watchdog (e2e-deadman-watchdog-v2.groovy) -- the second MCP
 * server that drives the e2e install + restore. Locks the review-hardened failure contracts a green
 * Spock matrix must keep, so a future edit can't silently regress them:
 *   - restorePackage installs main's bundle via adminInstallBundle(importUrl) + each app via
 *     adminUpdateApp(importUrl) from the manifest's canonical https URLs, with a mainChars landing assert;
 *   - adminUpdateLibrary fails CLOSED on a dropped/invalid POST (a null response is NOT success);
 *   - adminGetSource's noSave gate (the deploy probes pass noSave so they don't auto-save the live PR
 *     source over the dead-man restore cache -- the critical cache-poisoning fix);
 *   - checkDeadman parses a non-numeric deadline defensively (fires, never throws out of the tick).
 */
class WatchdogV2Spec extends Specification {
    HubitatAppScript script
    List<List<Object>> runInCalls = []     // captures (delaySeconds, handler[, opts]) of every runIn
    Map atomicStateMap = [:]               // backs the script's atomicState (the single-flight latch)

    def setup() {
        File appFile = new File('e2e-deadman-watchdog-v2.groovy')
        def sandbox = new HubitatAppSandbox(appFile.getText('UTF-8'))
        script = sandbox.run(
            api: Mock(AppExecutor) {
                _ * getLog() >> new PermissiveLog()
                _ * getSettings() >> [hubSecurityEnabled: false, debugLogging: false]
                _ * getAtomicState() >> { atomicStateMap }
                // Real wall-clock: the single-flight latch computes (now() - restoreInFlightAt);
                // an unstubbed mock now() returns 0, making every latch age hugely negative.
                _ * now() >> { System.currentTimeMillis() }
                _ * runIn(*_) >> { args -> runInCalls << (args as List) }
            },
            userSettingValues: [hubSecurityEnabled: false, debugLogging: false],
            validator: new PassThroughAppValidator([
                Flags.DontValidatePreferences,
                Flags.DontValidateDefinition,
                Flags.DontRestrictGroovy,
                Flags.DontRunScript
            ])
        )
    }

    @Unroll
    def "adminUpdateLibrary fails CLOSED on a dropped/invalid POST (#scenario)"() {
        given:
        script.metaClass.hubGet = { String p, Map q -> '[{"version":5}]' }   // freshVersion fetch for source mode
        script.metaClass.hubPostJson = { String p, String b -> [status: status, data: data] }

        when:
        def r = script.adminUpdateLibrary([libraryId: '119', source: 'a' * 50, confirm: true])

        then:
        r.success == expected

        where:
        scenario                      | status | data                                    || expected
        'dropped POST (data null)'    | null   | null                                    || false
        'HTTP non-200'                | 500    | null                                    || false
        'success:false envelope'      | 200    | '{"success":false,"message":"bad"}'     || false
        'no id in response'           | 200    | '{"success":true}'                      || false
        'valid update'                | 200    | '{"success":true,"id":119,"version":6}' || true
    }

    @Unroll
    def "adminGetSource auto-saves to the cache only when noSave is not set (noSave=#noSave -> saved=#saved)"() {
        given:
        boolean uploaded = false
        String big = 'x' * 70000     // > 64KB -> the auto-save path
        script.metaClass.hubGet = { String p, Map q -> '{"status":"ok","source":"' + big + '","version":3}' }
        script.metaClass.uploadHubFile = { String fn, byte[] bytes -> uploaded = true }

        when:
        def args = [type: 'app', id: '178']
        if (noSave) args.noSave = true
        def r = script.adminGetSource(args)

        then:
        uploaded == saved
        (r.sourceFile != null) == saved
        r.totalLength == 70000

        where:
        noSave || saved
        true   || false
        false  || true
    }

    def "checkDeadman tolerates a non-numeric deadline (fires the restore, never throws)"() {
        given:
        // restorePackage is private (metaClass can't intercept it), so stub the PUBLIC leaf adminUpdateApp
        // the real restorePackage calls (this flag's manifest has no bundles, so adminInstallBundle is not
        // reached), and capture the flag actAndRecord writes back.
        boolean appRestored = false
        Map written = null
        script.metaClass.adminUpdateApp = { Map a -> appRestored = true; [success: true] }
        script.metaClass.readFlag = { -> [armed: true, deadline: 'not-a-number', runId: '1',
                                          manifest: [app: [classId: '178', url: 'https://raw.example/main/app.groovy'], libraries: []]] }
        script.metaClass.writeFlag = { Map fl -> written = fl; true }

        when:
        script.checkDeadman()

        then:
        noExceptionThrown()
        appRestored                              // unparseable deadline -> treated as expired -> FIRE
        written?.restoreResult == 'restored'
    }

    def "a successful restore stamps the canonical-main marker from the flag (CI's disarm no longer polls)"() {
        given:
        Map uploaded = [:]
        script.metaClass.adminUpdateApp = { Map a -> [success: true] }
        script.metaClass.uploadHubFile = { String name, byte[] bytes -> uploaded[name] = new String(bytes, 'UTF-8') }
        script.metaClass.readFlag = { -> [armed: false, intent: 'disarm', runId: '7', canonicalMainSha: 'abc123',
                                          manifest: [app: [classId: '178', url: 'https://raw.example/main/app.groovy'], libraries: []]] }
        script.metaClass.writeFlag = { Map fl -> true }

        when:
        script.checkDeadman()

        then: 'the marker carries the flag-stamped canonical main SHA'
        uploaded['mcp-main-deployed-sha.txt'] == 'abc123'
    }

    def "a tick landing during an in-flight restore is skipped (single-flight latch)"() {
        given:
        // A restore takes 3-4 minutes and checkDeadman fires every minute, so without the latch
        // each tick starts ANOTHER full concurrent restore (seen live: three overlapping
        // "disarm complete" per teardown -- a load spike that trips the hub's per-app limiter).
        int restores = 0
        script.metaClass.adminUpdateApp = { Map a -> restores++; [success: true] }
        script.metaClass.readFlag = { -> [armed: false, intent: 'disarm', runId: '7',
                                          manifest: [app: [classId: '178', url: 'https://raw.example/main/app.groovy'], libraries: []]] }
        script.metaClass.writeFlag = { Map fl -> true }
        atomicStateMap.restoreInFlightFor = '7'
        atomicStateMap.restoreInFlightAt = System.currentTimeMillis() - 30_000L

        when:
        script.checkDeadman()

        then: 'the duplicate tick does not start a second restore'
        restores == 0
    }

    def "a STALE in-flight latch (crashed restore) does not block the next restore"() {
        given:
        int restores = 0
        script.metaClass.adminUpdateApp = { Map a -> restores++; [success: true] }
        script.metaClass.readFlag = { -> [armed: false, intent: 'disarm', runId: '7',
                                          manifest: [app: [classId: '178', url: 'https://raw.example/main/app.groovy'], libraries: []]] }
        script.metaClass.writeFlag = { Map fl -> true }
        atomicStateMap.restoreInFlightFor = '7'
        atomicStateMap.restoreInFlightAt = System.currentTimeMillis() - 700_000L   // > 10-min escape

        when:
        script.checkDeadman()

        then: 'the stale latch is overridden and the restore runs'
        restores == 1
    }

    def "the in-flight latch is cleared after the restore completes"() {
        given:
        script.metaClass.adminUpdateApp = { Map a -> [success: true] }
        script.metaClass.readFlag = { -> [armed: false, intent: 'disarm', runId: '8',
                                          manifest: [app: [classId: '178', url: 'https://raw.example/main/app.groovy'], libraries: []]] }
        script.metaClass.writeFlag = { Map fl -> true }

        when:
        script.checkDeadman()

        then: 'a finished restore releases the latch for the next run'
        atomicStateMap.restoreInFlightFor == null
    }

    def "no marker is stamped when the flag has no canonicalMainSha or the restore fails (#scenario)"() {
        given:
        Map uploaded = [:]
        script.metaClass.adminUpdateApp = { Map a -> [success: restoreOk] }
        script.metaClass.uploadHubFile = { String name, byte[] bytes -> uploaded[name] = new String(bytes, 'UTF-8') }
        script.metaClass.readFlag = { -> [armed: false, intent: 'disarm', runId: '7', fireAttempts: 4,
                                          manifest: [app: [classId: '178', url: 'https://raw.example/main/app.groovy'], libraries: []]] + extraFlag }
        script.metaClass.writeFlag = { Map fl -> true }

        when:
        script.checkDeadman()

        then: 'a cleared marker stays cleared, so the next run refreshes main'
        !uploaded.containsKey('mcp-main-deployed-sha.txt')

        where:
        scenario              | restoreOk | extraFlag
        'no sha in the flag'  | true      | [:]
        'restore failed'      | false     | [canonicalMainSha: 'abc123']
    }

    // ---- disarm acceleration: adminWriteFile kicks a one-shot checkDeadman past the ~60s tick ----

    def "deadmanKick delegates to checkDeadman (so the periodic check stays the single decision point)"() {
        given:
        boolean checked = false
        script.metaClass.checkDeadman = { -> checked = true }

        when:
        script.deadmanKick()

        then:
        checked
    }

    @Unroll
    def "adminWriteFile schedules a one-shot deadmanKick ONLY when the armed-flag file is written (#scenario)"() {
        given:
        // A flag write must accelerate the restore (runIn 2s -> deadmanKick); a write to any OTHER file
        // must NOT schedule anything. The handler MUST be the dedicated 'deadmanKick', never
        // 'checkDeadman' (scheduling that name would overwrite the runEvery1Minute periodic dead-man).
        script.metaClass.uploadHubFile = { String fn, byte[] bytes -> }

        when:
        def r = script.adminWriteFile([fileName: fileName, content: '{"armed":false,"intent":"disarm"}', confirm: true])

        then:
        r.success == true
        runInCalls.findAll { it[1] == 'deadmanKick' }.size() == expectedKicks
        runInCalls.findAll { it[1] == 'deadmanKick' && it[0] == 2 }.size() == expectedKicks   // 2s delay
        runInCalls.findAll { it[1] == 'checkDeadman' }.isEmpty()                              // never the periodic name

        where:
        scenario                  | fileName                || expectedKicks
        'flag file -> kick'       | 'e2e-deadman-v2.json'   || 1
        'other file -> no kick'   | 'mcp-source-app.groovy' || 0
    }

    // ---- defer-native-deletes: force-delete an installed-app instance (RM rule) for the disarm sweep ----

    def "adminForceDeleteInstalledApp GETs /installedapp/forcedelete/<id>/quiet then verifies gone via /installedapp/json"() {
        given:
        // The forcedelete endpoint answers SUCCESS with a 302 redirect to the apps list; hubGetStatus
        // captures that status off the thrown response (followRedirects:false), so the tool sees a 3xx.
        // The 302 alone is not trusted: a follow-up /installedapp/json existence read (404 = gone)
        // must confirm, because the disarm sweep fires these mid-recompile where commits strand.
        def paths = []
        script.metaClass.hubGetStatus = { String path, Map q ->
            paths << path
            path.startsWith("/installedapp/forcedelete/") ? [status: 302, location: "/installedapp/list", data: null]
                                                          : [status: 404, location: null, data: null]
        }

        when:
        def r = script.adminForceDeleteInstalledApp([id: "123", confirm: true])

        then:
        r.success == true
        r.id == "123"
        paths == ["/installedapp/forcedelete/123/quiet",        // NOT /app/edit/deleteJsonSafe (code class)
                  "/installedapp/json/123"]                     // the gone-check
    }

    @Unroll
    def "adminForceDeleteInstalledApp treats forcedelete status #status as #expected (302 redirect + 2xx = success)"() {
        given:
        // The gone-check answers "absent" (404) so the table isolates the FIRST call's status handling.
        script.metaClass.hubGetStatus = { String path, Map q ->
            path.startsWith("/installedapp/json/") ? [status: 404, location: null, data: null]
                                                   : [status: status, location: null, data: null]
        }

        expect:
        script.adminForceDeleteInstalledApp([id: "123", confirm: true]).success == expected

        where:
        status || expected
        302    || true       // forcedelete success redirect
        200    || true       // plain OK (some firmwares)
        404    || false      // instance already gone / bad id -> real failure, sweep keeps its list
        500    || false      // hub error
    }

    @Unroll
    def "adminForceDeleteInstalledApp gone-check: #scenario"() {
        given:
        // forcedelete 302s, but only a verified-absent app may report success -- a late/stranded
        // commit (app still readable) or an unreadable check keeps the id on the sweep's recovery
        // list, where the idempotent re-delete is a harmless no-op.
        script.metaClass.hubGetStatus = { String path, Map q ->
            path.startsWith("/installedapp/json/") ? checkResp
                                                   : [status: 302, location: "/installedapp/list", data: null]
        }

        when:
        def r = script.adminForceDeleteInstalledApp([id: "123", confirm: true])

        then:
        r.success == expected
        expected || r.error?.contains("keep the id")

        where:
        scenario                                  | checkResp                                                          || expected
        'app still exists -> stranded commit'     | [status: 200, location: null, data: '{"id":123,"name":"BAT_X"}']   || false
        'empty 200 body -> gone'                  | [status: 200, location: null, data: '']                            || true
        'parseable non-app body -> gone'          | [status: 200, location: null, data: '{"success":false}']           || true
        'unparseable 200 body -> cannot prove'    | [status: 200, location: null, data: '<html>login</html>']          || false
        'check unreachable -> cannot prove gone'  | [status: null, location: null, data: null]                         || false
    }

    def "adminForceDeleteInstalledApp reports success:false when the request never reaches the hub (status null)"() {
        given:
        // hubGetStatus leaves status null on an auth/cookie failure or a request that never reached the
        // hub -- the tool must NOT report success then, so the disarm sweep can warn + keep its id list.
        script.metaClass.hubGetStatus = { String path, Map q -> [status: null, location: null, data: null] }

        when:
        def r = script.adminForceDeleteInstalledApp([id: "123", confirm: true])

        then:
        r.success == false
        r.error?.contains("did not confirm")
    }

    def "adminPurgeE2eArtifacts force-deletes only BAT_E2E_ apps + removes only BAT_E2E_ vars (one local sweep)"() {
        given:
        // /hub2/appsList returns a mix; the purge must touch ONLY the BAT_E2E_-prefixed entries
        // (incl. a nested child) and leave real apps alone -- the prefix is the only safety scope.
        def appsJson = groovy.json.JsonOutput.toJson([apps: [
            [data: [id: 100, name: "BAT_E2E_Rule1", type: "rule"], children: []],
            [data: [id: 200, name: "Real Rule", type: "rule"], children: [
                [data: [id: 201, name: "BAT_E2E_Child", type: "x"], children: []]]],
        ]])
        def forced = []
        script.metaClass.hubGet = { String path, Map q -> path == "/hub2/appsList" ? appsJson : "" }
        script.metaClass.hubGetStatus = { String path, Map q ->
            if (path.startsWith("/installedapp/forcedelete/")) { forced << path; [status: 302, location: "/installedapp/list", data: null] }
            else if (path == "/installedapp/direct/hubVariables") { [status: 302, location: "/installedapp/configure/9001", data: null] }
            else { [status: 404, location: null, data: null] }   // gone-check: absent
        }
        // Variables are deleted by driving the classic hubVar wizard -- there is no app-facing
        // global-variable delete API. This test previously stubbed removeGlobalVariable(), a method
        // that does not exist on the app class, so it passed against a mock of nothing while the
        // real sweep failed on every run. Assert the wizard clicks instead.
        def deleteClicks = []
        script.metaClass.hubPostForm = { String path, Map b ->
            if (path == "/installedapp/btn" && b.stateAttribute == "deleteGV") { deleteClicks << b.name }
            [status: 200, data: 'ok']
        }
        script.metaClass.getAllGlobalVars = { -> [BAT_E2E_v1: [type: "string"], RealVar: [type: "string"], BAT_E2E_v2: [type: "integer"]] }
        script.metaClass.getGlobalVar = { String n -> null }   // gone after the wizard commits

        when:
        def r = script.adminPurgeE2eArtifacts([confirm: true])

        then:
        r.success == true
        r.deletedCount == 2
        (r.deleted*.id).collect { it as Integer }.sort() == [100, 201]
        forced.sort() == ["/installedapp/forcedelete/100/quiet", "/installedapp/forcedelete/201/quiet"]
        r.variablesDeletedCount == 2
        (r.variablesDeleted as List).sort() == ["BAT_E2E_v1", "BAT_E2E_v2"]

        and: "the real hub variable is never clicked -- the prefix is the only safety scope"
        deleteClicks.sort() == ["BAT_E2E_v1", "BAT_E2E_v2"]
    }

    def "adminPurgeE2eArtifacts requires confirm (never deletes without it)"() {
        when:
        script.adminPurgeE2eArtifacts([:])

        then:
        thrown(Exception)
    }

    @Unroll
    def "adminSetAppDisabled posts the Vue wire format and trusts only the read-back (#scenario)"() {
        given:
        // POST /installedapp/disable {id, disable} (vue-hub2.min.js wire format); a 200 alone is
        // not proof -- only the /installedapp/json read-back showing the flipped flag is success.
        String postedPath = null
        String postedBody = null
        script.metaClass.hubPostJson = { String path, String body ->
            postedPath = path; postedBody = body; [status: postStatus, data: '']
        }
        script.metaClass.hubGetStatus = { String path, Map q -> [status: 200, location: null, data: readBack] }

        when:
        def r = script.adminSetAppDisabled([appId: "5506", disable: true, confirm: true])

        then:
        r.success == expected
        postedPath == "/installedapp/disable"
        postedBody.contains('"id":5506') && postedBody.contains('"disable":true')

        where:
        scenario                          | postStatus | readBack                       || expected
        'flip verified'                   | 200        | '{"id":5506,"disabled":true}'  || true
        'POST ok but flag did not flip'   | 200        | '{"id":5506,"disabled":false}' || false
        'unreadable read-back'            | 200        | '<html>login</html>'           || false
    }

    def "adminSetAppDisabled reports failure when the POST itself fails"() {
        given:
        script.metaClass.hubPostJson = { String path, String body -> [status: 500, data: ''] }

        expect:
        script.adminSetAppDisabled([appId: "5506", disable: true, confirm: true]).success == false
    }

    @Unroll
    def "adminForceDeleteInstalledApp rejects a bad instance id (#scenario)"() {
        when:
        script.adminForceDeleteInstalledApp([id: badId, confirm: true])

        then:
        thrown(IllegalArgumentException)

        where:
        scenario      | badId
        'non-integer' | 'abc'
        'zero'        | '0'
        'missing'     | null
    }

    // ---- PR #247: bundle tools mirrored into the watchdog + the no-stale restore cleanup ----

    @Unroll
    def "adminListBundles parses the hub bundle list (#scenario)"() {
        given:
        script.metaClass.hubGet = { String p, Map q -> body }

        expect:
        def r = script.adminListBundles([:])
        r.source == src
        r.bundles*.name == names

        where:
        scenario    | body                                                    || src           | names
        'json list' | '[{"id":1,"name":"mcp_libraries","namespace":"mcp"}]'   || 'hub_api'     | ['mcp_libraries']
        'not array' | '{"oops":true}'                                         || 'hub_api_raw' | []
        'not json'  | '<html>error</html>'                                    || 'hub_api_raw' | []
    }

    @Unroll
    def "adminDeleteBundle confirms removal by re-list (#scenario)"() {
        given:
        int calls = 0
        script.metaClass.adminListBundles = { Map a ->
            calls++
            (calls == 1)
                ? [source: 'hub_api', bundles: [[id: '5', name: 'mcp_libraries', namespace: 'mcp']]]
                : [source: 'hub_api', bundles: afterList]
        }
        script.metaClass.hubGet = { String p, Map q -> "" }   // the /bundle/delete GET

        expect:
        script.adminDeleteBundle([bundleId: '5', confirm: true]).success == expected

        where:
        scenario               | afterList                                              || expected
        'gone after delete'    | []                                                     || true
        'still present -> fail' | [[id: '5', name: 'mcp_libraries', namespace: 'mcp']]   || false
    }

    def "adminDeleteBundle refuses a missing id without sending a delete"() {
        given:
        script.metaClass.adminListBundles = { Map a -> [source: 'hub_api', bundles: [[id: '5', name: 'x', namespace: 'mcp']]] }

        expect:
        script.adminDeleteBundle([bundleId: '99', confirm: true]).success == false
    }

    def "restorePackage installs the bundle from the manifest CANONICAL https URL"() {
        given:
        // The bundle-driven path: ONE adminInstallBundle from the manifest's canonical https URL delivers
        // every library (the HPM way). No per-library source POSTs (the load profile that tripped the
        // platform's per-app limiter); the bundle install is the only library-side operation.
        def bundleUrls = []
        script.metaClass.adminInstallBundle = { Map a -> bundleUrls << a.importUrl; [success: true] }
        script.metaClass.adminUpdateApp = { Map a -> [success: true] }
        script.metaClass.readFlag = { -> [armed: false, intent: 'disarm', runId: '9',
                                          manifest: [app: [classId: '178', url: 'https://raw.example/main/app.groovy'],
                                                     libraries: [[id: '119', namespace: 'mcp', name: 'McpRoomsLib']],
                                                     bundles: [[namespace: 'mcp', name: 'mcp_libraries', url: 'https://raw.example/main/bundles/mcp-libraries.zip']]]] }
        script.metaClass.writeFlag = { Map fl -> true }
        // reconcile steps list bundles/libraries -- give them benign hub state
        script.metaClass.adminListBundles = { Map a -> [source: "hub_api", bundles: []] }
        script.metaClass.adminListLibraries = { Map a -> [source: "hub_api", libraries: []] }

        when:
        script.checkDeadman()

        then:
        bundleUrls == ['https://raw.example/main/bundles/mcp-libraries.zip']
    }

    def "restorePackage fails loudly on an old-format flag whose bundles carry no url"() {
        given:
        // No local cache exists anymore; bundle entries without a url cannot be restored -- the
        // operator must re-arm. The watchdog endpoint itself stays reachable (the safety floor).
        // A urlless bundle must fail BEFORE any install, so adminInstallBundle must never be called.
        // restorePackage wraps adminInstallBundle in a try/catch (a throw would be swallowed into a
        // failed restore that this test's restoreResult assertion can't distinguish from the no-url
        // path), so a thrown guard alone proves nothing -- count the calls and assert it stayed 0.
        int bundleInstalls = 0
        script.metaClass.adminInstallBundle = { Map a -> bundleInstalls++; [success: true] }
        script.metaClass.adminUpdateApp = { Map a -> [success: true] }
        script.metaClass.adminListBundles = { Map a -> [source: "hub_api", bundles: []] }
        script.metaClass.adminListLibraries = { Map a -> [source: "hub_api", libraries: []] }
        Map written = null
        script.metaClass.readFlag = { -> [armed: false, intent: 'disarm', runId: '12',
                                          manifest: [app: [classId: '178', url: 'https://raw.example/main/app.groovy'],
                                                     libraries: [],
                                                     bundles: [[namespace: 'mcp', name: 'mcp_libraries']]]] }
        script.metaClass.writeFlag = { Map fl -> written = fl; true }

        when:
        script.checkDeadman()

        then:
        written?.restoreResult != 'restored'
        written?.restoreDetail?.contains('no url')
        bundleInstalls == 0     // a urlless bundle must fail loudly, never reach an install
    }

    @Unroll
    def "restorePackage honors the run-scoped bundle-state marker (#scenario)"() {
        given:
        // The deploy stamps "<runId>:unchanged" when the PR's bundle is byte-identical to main's --
        // the libraries never left main's bytes, so reinstalling the cached bundle is a redundant
        // recompile wave. ONLY a verified this-run marker may skip; a stale runId (crashed prior run)
        // or "changed" must install: fail-safe toward restoring.
        int bundleInstalls = 0
        script.metaClass.adminInstallBundle = { Map a -> bundleInstalls++; [success: true] }
        script.metaClass.adminUpdateApp = { Map a -> [success: true] }
        script.metaClass.readHubFileText = { String fn -> marker }
        script.metaClass.readFlag = { -> [armed: false, intent: 'disarm', runId: '11',
                                          manifest: [app: [classId: '178', url: 'https://raw.example/main/app.groovy'], libraries: [],
                                                     bundles: [[namespace: 'mcp', name: 'mcp_libraries', url: 'https://raw.example/main/bundles/mcp-libraries.zip']]]] }
        script.metaClass.writeFlag = { Map fl -> true }
        script.metaClass.adminListBundles = { Map a -> [source: "hub_api", bundles: []] }
        script.metaClass.adminListLibraries = { Map a -> [source: "hub_api", libraries: []] }

        when:
        script.checkDeadman()

        then:
        bundleInstalls == expectedInstalls

        where:
        scenario                            | marker            || expectedInstalls
        'this-run unchanged -> skip'        | '11:unchanged'    || 0
        'stale runId -> install'            | '999:unchanged'   || 1
        'changed -> install'                | '11:changed'      || 1
        'missing marker -> install'         | null              || 1
    }

    @Unroll
    def "restorePackage app source-length cross-check: #scenario"() {
        given:
        // After adminUpdateApp the restore re-reads the live source (/app/ajax/code) and compares its
        // length to the manifest's mainChars. A mismatch must FAIL the restore loudly (a truncated/wrong
        // install landing silently green is the dangerous case); a match must let it succeed. Lock the
        // comparison direction so a future inversion or wrong-field parse can't pass.
        Map written = null
        script.metaClass.adminUpdateApp = { Map a -> [success: true] }
        script.metaClass.hubGet = { String p, Map q -> '{"source":"' + ('x' * liveLen) + '"}' }
        script.metaClass.adminListBundles = { Map a -> [source: 'hub_api', bundles: []] }
        script.metaClass.adminListLibraries = { Map a -> [source: 'hub_api', libraries: []] }
        script.metaClass.readFlag = { -> [armed: false, intent: 'disarm', runId: '21',
                                          manifest: [app: [classId: '178', url: 'https://raw.example/main/app.groovy', mainChars: '500'],
                                                     libraries: []]] }
        script.metaClass.writeFlag = { Map fl -> written = fl; true }

        when:
        script.checkDeadman()

        then:
        (written?.restoreResult == 'restored') == expectRestored
        expectRestored || written?.restoreDetail?.contains('landed source length')

        where:
        scenario                        | liveLen || expectRestored
        'length matches mainChars'      | 500     || true
        'length differs from mainChars' | 499     || false
    }

    def "restorePackage aborts loudly when a bundle install fails mid-restore (does not continue to the app)"() {
        given:
        // Every other restore test stubs adminInstallBundle as success. Drive the FAILURE: a bundle
        // install returning success:false must abort restorePackage BEFORE the app step, so the restore
        // reports a non-restored result naming the bundle and the app is never touched.
        boolean appTouched = false
        Map written = null
        script.metaClass.adminInstallBundle = { Map a -> [success: false, error: 'hub refused'] }
        script.metaClass.adminUpdateApp = { Map a -> appTouched = true; [success: true] }
        script.metaClass.adminListBundles = { Map a -> [source: 'hub_api', bundles: []] }
        script.metaClass.adminListLibraries = { Map a -> [source: 'hub_api', libraries: []] }
        script.metaClass.readFlag = { -> [armed: false, intent: 'disarm', runId: '22',
                                          manifest: [app: [classId: '178', url: 'https://raw.example/main/app.groovy'],
                                                     libraries: [],
                                                     bundles: [[namespace: 'mcp', name: 'mcp_libraries', url: 'https://raw.example/main/bundles/mcp-libraries.zip']]]] }
        script.metaClass.writeFlag = { Map fl -> written = fl; true }

        when:
        script.checkDeadman()

        then:
        written?.restoreResult != 'restored'
        written?.restoreDetail?.contains('mcp_libraries')
        !appTouched     // a bundle abort must NOT fall through to the app install
    }

    @Unroll
    def "restore retry-cap escalation: #scenario"() {
        given:
        // A failed restore increments fireAttempts and retries while attempts < 5 WITHOUT latching, then
        // terminally latches restoreResult='failed' + armed=false at the 5th attempt (fireAttempts 4 in).
        // Below the cap the restore must stay unlatched (still retrying); at the cap it must latch failed.
        Map written = null
        script.metaClass.adminUpdateApp = { Map a -> [success: false, error: 'hub refused'] }
        script.metaClass.adminListBundles = { Map a -> [source: 'hub_api', bundles: []] }
        script.metaClass.adminListLibraries = { Map a -> [source: 'hub_api', libraries: []] }
        script.metaClass.readFlag = { -> [armed: false, intent: 'disarm', runId: '23', fireAttempts: priorAttempts,
                                          manifest: [app: [classId: '178', url: 'https://raw.example/main/app.groovy'], libraries: []]] }
        script.metaClass.writeFlag = { Map fl -> written = fl; true }

        when:
        script.checkDeadman()

        then:
        written?.fireAttempts == priorAttempts + 1
        written?.restoreResult == expectedResult     // null below the cap (still retrying), 'failed' at the cap
        written?.armed == expectedArmed

        where:
        scenario                          | priorAttempts || expectedResult | expectedArmed
        'below cap -> retry, no latch'    | 2             || null           | false
        'at cap (5th) -> latch failed'    | 4             || 'failed'       | false
    }

    def "a STALE in-flight latch for a DIFFERENT run does not block the current run's restore"() {
        given:
        // The latch is PER-RUN: it skips only when restoreInFlightFor == flag.runId (a same-run duplicate
        // tick). A prior run's leftover latch (different runId), even with a RECENT timestamp the 10-min
        // staleness escape would not yet clear, must NOT block THIS run -- the runId-inequality branch
        // runs the restore regardless of the time check.
        int restores = 0
        script.metaClass.adminUpdateApp = { Map a -> restores++; [success: true] }
        script.metaClass.readFlag = { -> [armed: false, intent: 'disarm', runId: '7',
                                          manifest: [app: [classId: '178', url: 'https://raw.example/main/app.groovy'], libraries: []]] }
        script.metaClass.writeFlag = { Map fl -> true }
        atomicStateMap.restoreInFlightFor = '6'                                    // a DIFFERENT run holds the latch
        atomicStateMap.restoreInFlightAt = System.currentTimeMillis() - 30_000L    // recent -> staleness escape does NOT apply

        when:
        script.checkDeadman()

        then: 'a different run\'s latch is ignored and the restore still runs'
        restores == 1
    }

    def "adminGetHubLogs parses tab-delimited rows newest-first with a level filter"() {
        given:
        script.metaClass.hubGet = { String p, Map q, int t = 30 ->
            '["app|1|x\\tWARN\\told warn\\t10:00\\t","app|2|y\\tERROR\\tboom\\t10:01\\t","app|3|z\\tINFO\\tnoise\\t10:02\\t"]'
        }

        when:
        def r = script.adminGetHubLogs([level: 'error', limit: 10])

        then:
        r.count == 1
        r.logs[0].message == 'boom'
        r.totalParsed == 3
    }

    def "adminGetJobs reads /logs/json and maps checkDeadman (the watchdog schedule check)"() {
        given:
        // hub_get_jobs reads the verified /logs/json shape (jobs / runningJobs / hubCommands, keyed by
        // methodName) -- NOT the old non-existent /hub/scheduledJobs/json that 404'd and blinded the
        // pre-arm checkDeadman schedule check in mcp_arm_watchdog.sh.
        String requestedPath = null
        script.metaClass.hubGet = { String p, Map q ->
            requestedPath = p
            '{"uptime":"1d","jobs":[{"name":"checkDeadman","methodName":"checkDeadman","recurring":true}],"runningJobs":[],"hubCommands":[]}'
        }

        when:
        def r = script.adminGetJobs([:])

        then: 'the schedule check reads /logs/json, never /hub/scheduledJobs/json'
        requestedPath == '/logs/json'

        and: 'checkDeadman is surfaced via job.methodName so the arm-time jq can confirm it is scheduled'
        r.scheduledJobs.count == 1
        r.scheduledJobs.jobs[0].method == 'checkDeadman'
        r.hubActions.count == 0
    }

    def "adminListAppInstances flattens the /hub2/appsList tree with parentId"() {
        given:
        script.metaClass.hubGet = { String p, Map q ->
            '{"apps":[{"data":{"id":5,"name":"Parent","type":"T","disabled":false,"user":true},"children":[{"data":{"id":7,"name":"Child","type":"C","disabled":true,"user":false},"children":[]}]}]}'
        }

        when:
        def r = script.adminListAppInstances([:])

        then:
        r.count == 2
        r.apps[0].id == 5 && r.apps[0].parentId == null && r.apps[0].childCount == 1
        r.apps[1].id == 7 && r.apps[1].parentId == 5 && r.apps[1].disabled == true
    }

    def "adminGetMemoryHistory parses rows, skips headers, applies the tail limit"() {
        given:
        script.metaClass.hubGet = { String p, Map q ->
            "Date,Free OS,5m CPU\n01-01 00:00,100,0.5\n01-01 00:05,200,0.6,331392,1000,50\n01-01 00:10,300,0.7"
        }

        when:
        def r = script.adminGetMemoryHistory([limit: 2])

        then:
        r.entries.size() == 2
        r.entries[0].freeMemoryKB == 200
        r.entries[0].totalJavaKB == 331392
        r.entries[1].freeMemoryKB == 300
        r.summary.totalEntries == 3
        r.summary.minMemoryKB == 200      // min over the RETURNED window
        r.summary.currentMemoryKB == 300
    }

    def "restorePackage drops the PR's stale bundle + library, keeps main's and untouched namespaces"() {
        given:
        def deletedBundles = []
        def deletedLibs = []
        // Hub holds main's mcp_libraries bundle + the PR's mcp_pr_extra bundle; main's McpRoomsLib +
        // the PR's McpExtraLib + an unrelated 'other'-namespace library. The manifest's main sets list
        // only mcp_libraries + McpRoomsLib, so the PR's bundle/library are the stale ones to drop.
        script.metaClass.hubGet = { String p, Map q -> null }
        script.metaClass.adminInstallBundle = { Map a -> [success: true] }
        script.metaClass.adminListBundles = { Map a -> [source: 'hub_api', bundles: [
            [id: '1', name: 'mcp_libraries', namespace: 'mcp'],
            [id: '2', name: 'mcp_pr_extra', namespace: 'mcp']]] }
        script.metaClass.adminDeleteBundle = { Map a -> deletedBundles << a.bundleId; [success: true, verified: true] }
        script.metaClass.adminListLibraries = { Map a -> [source: 'hub_api', libraries: [
            [id: '10', name: 'McpRoomsLib', namespace: 'mcp'],
            [id: '11', name: 'McpExtraLib', namespace: 'mcp'],
            [id: '12', name: 'Unrelated', namespace: 'other']]] }
        script.metaClass.adminDeleteItem = { Map a -> deletedLibs << a.id; [success: true] }
        script.metaClass.adminUpdateApp = { Map a -> [success: true] }
        Map written = null
        script.metaClass.readFlag = { -> [armed: true, deadline: '0', runId: '9', manifest: [
            app: [classId: '178', url: 'https://raw.example/main/app.groovy'],
            libraries: [[namespace: 'mcp', name: 'McpRoomsLib', id: '10']],
            bundles: [[namespace: 'mcp', name: 'mcp_libraries', url: 'https://raw.example/main/bundle.zip']]]] }
        script.metaClass.writeFlag = { Map fl -> written = fl; true }

        when:
        script.checkDeadman()

        then:
        written?.restoreResult == 'restored'
        deletedBundles == ['2']     // PR bundle dropped; main's mcp_libraries kept
        deletedLibs == ['11']       // PR library dropped; main's McpRoomsLib kept; 'other' namespace untouched
    }

    def "restorePackage skips bundle cleanup when the manifest has no main bundle set (older flag)"() {
        given:
        def deletedBundles = []
        script.metaClass.hubGet = { String p, Map q -> null }
        script.metaClass.adminInstallBundle = { Map a -> [success: true] }
        script.metaClass.adminListBundles = { Map a -> [source: 'hub_api', bundles: [[id: '2', name: 'mcp_libraries', namespace: 'mcp']]] }
        script.metaClass.adminDeleteBundle = { Map a -> deletedBundles << a.bundleId; [success: true] }
        script.metaClass.adminListLibraries = { Map a -> [source: 'hub_api', libraries: []] }
        script.metaClass.adminUpdateApp = { Map a -> [success: true] }
        Map written = null
        // No 'bundles' / no 'libraries' key in the manifest -> both cleanups must SKIP (don't blind-delete).
        script.metaClass.readFlag = { -> [armed: true, deadline: '0', runId: '9',
            manifest: [app: [classId: '178', url: 'https://raw.example/main/app.groovy']]] }
        script.metaClass.writeFlag = { Map fl -> written = fl; true }

        when:
        script.checkDeadman()

        then:
        written?.restoreResult == 'restored'
        deletedBundles == []        // skipped -- no main bundle set to compare against
    }

    def "restorePackage cleanup is best-effort: a FAILED stale-bundle delete does not abort the restore"() {
        given:
        script.metaClass.hubGet = { String p, Map q -> null }
        script.metaClass.adminInstallBundle = { Map a -> [success: true] }
        script.metaClass.adminListBundles = { Map a -> [source: 'hub_api', bundles: [
            [id: '1', name: 'mcp_libraries', namespace: 'mcp'],
            [id: '2', name: 'mcp_pr_extra', namespace: 'mcp']]] }
        script.metaClass.adminDeleteBundle = { Map a -> [success: false, error: 'hub refused'] }   // delete FAILS
        script.metaClass.adminListLibraries = { Map a -> [source: 'hub_api', libraries: []] }
        script.metaClass.adminUpdateApp = { Map a -> [success: true] }
        Map written = null
        script.metaClass.readFlag = { -> [armed: true, deadline: '0', runId: '9', manifest: [
            app: [classId: '178', url: 'https://raw.example/main/app.groovy'],
            libraries: [[namespace: 'mcp', name: 'McpRoomsLib', id: '10']],
            bundles: [[namespace: 'mcp', name: 'mcp_libraries', url: 'https://raw.example/main/bundle.zip']]]] }
        script.metaClass.writeFlag = { Map fl -> written = fl; true }

        when:
        script.checkDeadman()

        then:
        written?.restoreResult == 'restored'   // restoring main's libs+app is the guarantee; a failed cleanup delete must not abort it
    }

    def "restorePackage cleanup is best-effort: a THROWING stale-library delete does not abort the restore"() {
        given:
        script.metaClass.hubGet = { String p, Map q -> null }
        script.metaClass.adminInstallBundle = { Map a -> [success: true] }
        script.metaClass.adminListBundles = { Map a -> [source: 'hub_api', bundles: []] }
        script.metaClass.adminListLibraries = { Map a -> [source: 'hub_api', libraries: [
            [id: '10', name: 'McpBundlesLib', namespace: 'mcp'],
            [id: '11', name: 'McpRoomsLib', namespace: 'mcp']]] }
        script.metaClass.adminDeleteItem = { Map a -> throw new RuntimeException('boom') }   // delete THROWS
        script.metaClass.adminUpdateApp = { Map a -> [success: true] }
        Map written = null
        script.metaClass.readFlag = { -> [armed: true, deadline: '0', runId: '9', manifest: [
            app: [classId: '178', url: 'https://raw.example/main/app.groovy'],
            libraries: [[namespace: 'mcp', name: 'McpBundlesLib', id: '10']],
            bundles: [[namespace: 'mcp', name: 'mcp_libraries', url: 'https://raw.example/main/bundle.zip']]]] }
        script.metaClass.writeFlag = { Map fl -> written = fl; true }

        when:
        script.checkDeadman()

        then:
        written?.restoreResult == 'restored'   // a throwing cleanup delete is caught; restore still succeeds
    }

    def "adminDeleteBundle reports verified=false when the post-delete re-list is degraded"() {
        given:
        int calls = 0
        script.metaClass.adminListBundles = { Map a ->
            calls++
            (calls == 1)
                ? [source: 'hub_api', bundles: [[id: '5', name: 'mcp_libraries', namespace: 'mcp']]]   // before: present
                : [source: 'hub_api_raw', bundles: []]                                                   // after: degraded shape
        }
        script.metaClass.hubGet = { String p, Map q -> "" }

        when:
        def r = script.adminDeleteBundle([bundleId: '5', confirm: true])

        then:
        r.success == false      // a destructive op must NOT claim success it couldn't verify
        r.verified == false
    }

    def "restorePackage skips bundle cleanup when the live bundle list is degraded (no blind delete)"() {
        given:
        def deletedBundles = []
        script.metaClass.hubGet = { String p, Map q -> null }
        script.metaClass.adminInstallBundle = { Map a -> [success: true] }
        script.metaClass.adminListBundles = { Map a -> [source: 'hub_api_raw', bundles: []] }   // degraded -> skip
        script.metaClass.adminDeleteBundle = { Map a -> deletedBundles << a.bundleId; [success: true] }
        script.metaClass.adminListLibraries = { Map a -> [source: 'hub_api', libraries: []] }
        script.metaClass.adminUpdateApp = { Map a -> [success: true] }
        Map written = null
        script.metaClass.readFlag = { -> [armed: true, deadline: '0', runId: '9', manifest: [
            app: [classId: '178', url: 'https://raw.example/main/app.groovy'],
            libraries: [],
            bundles: [[namespace: 'mcp', name: 'mcp_libraries', url: 'https://raw.example/main/bundle.zip']]]] }
        script.metaClass.writeFlag = { Map fl -> written = fl; true }

        when:
        script.checkDeadman()

        then:
        written?.restoreResult == 'restored'
        deletedBundles == []        // degraded list -> skip cleanup, never blind-delete
    }

    // ---- hub_update_platform: apply the pending platform update (test-hub maintenance) ----

    def "adminUpdatePlatform refuses to apply without confirm"() {
        when:
        script.adminUpdatePlatform([:])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("confirm=true")
    }

    def "adminUpdatePlatform statusOnly polls checkUpdateStatus without confirm"() {
        given:
        def paths = []
        script.metaClass.hubGet = { String p, Map q -> paths << p; '{"status":"IDLE"}' }

        when:
        def r = script.adminUpdatePlatform([statusOnly: true])

        then:
        r.success == true
        paths == ["/hub/cloud/checkUpdateStatus"]
    }

    def "adminUpdatePlatform confirm=true fires checkForUpdate then updatePlatform"() {
        given:
        def paths = []
        script.metaClass.hubGet = { String p, Map q -> paths << p; '{"ok":true}' }

        when:
        def r = script.adminUpdatePlatform([confirm: true])

        then:
        r.success == true
        paths == ["/hub/cloud/checkForUpdate", "/hub/cloud/updatePlatform"]
    }

    def "adminUpdatePlatform surfaces an updatePlatform failure instead of false-greening"() {
        given:
        script.metaClass.hubGet = { String p, Map q ->
            if (p == "/hub/cloud/updatePlatform") throw new RuntimeException("boom")
            '{"ok":true}'
        }

        when:
        def r = script.adminUpdatePlatform([confirm: true])

        then:
        r.success == false
        r.error.contains("updatePlatform failed")
    }

    // ---- purge single-flight latch (the 2026-09-01 hub wedge) --------------------------------
    // Five overlapping hub_purge_e2e_artifacts sweeps -- CI retrying one dropped relay response --
    // each re-enumerated the same app list and raced on the same ids for 11+ minutes, exhausting
    // the hub's web thread pool and leaving it unresponsive for 3h32m until a manual power cycle.

    def "a purge landing during an in-flight purge is a no-op, not a second sweep"() {
        given:
        int enumerations = 0
        script.metaClass.hubGet = { String p, Map q -> enumerations++; '{"apps":[]}' }
        atomicStateMap.purgeInFlightAt = System.currentTimeMillis() - 30_000L

        when:
        def res = script.adminPurgeE2eArtifacts([confirm: true])

        then: 'the duplicate call never enumerates, so it cannot race the running sweep'
        enumerations == 0
        res.inFlight == true
        res.success == true
        res.note?.contains('Do NOT retry')
    }

    def "a STALE purge latch (sweep killed mid-flight) does not block the next purge"() {
        given:
        int enumerations = 0
        script.metaClass.hubGet = { String p, Map q -> enumerations++; '{"apps":[]}' }
        atomicStateMap.purgeInFlightAt = System.currentTimeMillis() - 1_000_000L

        when:
        script.adminPurgeE2eArtifacts([confirm: true])

        then: 'the stale latch is overridden and the sweep runs'
        enumerations == 1
    }

    def "the purge latch is released once the sweep completes"() {
        given:
        script.metaClass.hubGet = { String p, Map q -> '{"apps":[]}' }

        when:
        script.adminPurgeE2eArtifacts([confirm: true])

        then:
        atomicStateMap.purgeInFlightAt == null
    }

    def "a purge arriving just after one finished is served from cache, not re-run"() {
        given:
        int enumerations = 0
        script.metaClass.hubGet = { String p, Map q -> enumerations++; '{"apps":[]}' }
        atomicStateMap.purgeResult = [success: true, prefix: 'BAT_E2E_', deletedCount: 12, failedCount: 0]
        atomicStateMap.purgeResultAt = System.currentTimeMillis() - 10_000L

        when:
        def res = script.adminPurgeE2eArtifacts([confirm: true])

        then: 'CI gets the real outcome of the sweep it lost the response to'
        enumerations == 0
        res.cached == true
        res.deletedCount == 12
    }

    def "a purge cache older than the window re-runs instead of serving a stale result"() {
        given:
        int enumerations = 0
        script.metaClass.hubGet = { String p, Map q -> enumerations++; '{"apps":[]}' }
        atomicStateMap.purgeResult = [success: true, prefix: 'BAT_E2E_', deletedCount: 12, failedCount: 0]
        atomicStateMap.purgeResultAt = System.currentTimeMillis() - 400_000L

        when:
        script.adminPurgeE2eArtifacts([confirm: true])

        then:
        enumerations == 1
    }

    // ---- wedge detection + auto-reboot escape -------------------------------------------------

    def "a wedged hub is auto-rebooted, and the escape runs BEFORE readFlag"() {
        given: 'loopback dead: 8+ consecutive failures and no success for over four minutes'
        boolean flagRead = false
        String posted = null
        script.metaClass.readFlag = { -> flagRead = true; null }
        script.metaClass.hubPostForm = { String p, Map b -> posted = p; [status: 200, data: 'ok'] }
        script.metaClass.probeLoopbackAlive = { -> false }   // the live gate agrees: still dead
        atomicStateMap.loopbackFailStreak = 12
        atomicStateMap.loopbackLastOkAt = System.currentTimeMillis() - 300_000L

        when:
        script.checkDeadman()

        then: 'the reboot lands, and the tick never reaches the flag read (itself a loopback call)'
        posted == '/hub/reboot'
        !flagRead
    }

    def "a healthy hub is never auto-rebooted (#scenario)"() {
        given:
        String posted = null
        script.metaClass.readFlag = { -> null }
        script.metaClass.hubPostForm = { String p, Map b -> posted = p; [status: 200, data: 'ok'] }
        atomicStateMap.loopbackFailStreak = streak
        atomicStateMap.loopbackLastOkAt = System.currentTimeMillis() - sinceOkMs

        when:
        script.checkDeadman()

        then:
        posted == null

        where:
        scenario                                   | streak | sinceOkMs
        'no failures at all'                       | 0      | 1_000L
        'a burst of failures but a recent success' | 20     | 30_000L
        'stale silence but too few failures'       | 3      | 600_000L
    }

    def "auto-reboot is rate-limited so it cannot become a boot loop"() {
        given:
        String posted = null
        script.metaClass.readFlag = { -> null }
        script.metaClass.hubPostForm = { String p, Map b -> posted = p; [status: 200, data: 'ok'] }
        atomicStateMap.loopbackFailStreak = 12
        atomicStateMap.loopbackLastOkAt = System.currentTimeMillis() - 300_000L
        atomicStateMap.lastAutoRebootAt = System.currentTimeMillis() - 60_000L
        script.metaClass.probeLoopbackAlive = { -> false }

        when:
        script.checkDeadman()

        then: 'a hub that did not come back is left for a human, not rebooted again'
        posted == null
    }

    def "a successful loopback call clears the wedge streak"() {
        given:
        atomicStateMap.loopbackFailStreak = 12

        when:
        script.noteLoopback(true)

        then:
        atomicStateMap.loopbackFailStreak == 0
        atomicStateMap.loopbackLastOkAt != null
    }

    // ---- restore retry backoff ----------------------------------------------------------------
    // Each restore attempt is a 660KB source fetch + a bundle install + two app recompiles. The old
    // code retried on every 1-minute tick, piling that load onto the condition that made the
    // restore fail in the first place.

    def "restore retries back off instead of firing on every tick (#scenario)"() {
        expect:
        script.retryBackoffPending([fireAttempts: attempts, lastAttemptAt: System.currentTimeMillis() - agoMs],
                                   'disarm') == pending

        where: "the schedule is 1, 2, 4 and 8 minutes -- five attempts over ~15 min before the latch"
        scenario                                   | attempts | agoMs      | pending
        'first attempt is never delayed'           | 0        | 0L         | false
        'after 1 failure, attempt 2 waits 1 min'   | 1        | 30_000L    | true
        'attempt 2 proceeds once 1 min has passed' | 1        | 90_000L    | false
        'after 3 failures, attempt 4 waits 4 min'  | 3        | 200_000L   | true
        'attempt 4 proceeds once 4 min has passed' | 3        | 300_000L   | false
        'after 4 failures, attempt 5 waits 8 min'  | 4        | 400_000L   | true
        'attempt 5 proceeds once 8 min has passed' | 4        | 500_000L   | false
    }

    def "a wedged hub skips the restore attempt entirely rather than adding load"() {
        given:
        atomicStateMap.loopbackFailStreak = 12
        atomicStateMap.loopbackLastOkAt = System.currentTimeMillis() - 300_000L

        expect: 'the restore cannot land while loopback is dead; trying costs load the hub lacks'
        script.retryBackoffPending([fireAttempts: 1, lastAttemptAt: System.currentTimeMillis() - 600_000L],
                                   'disarm')
    }

    // ---- hub_reboot admin tool ---------------------------------------------------------------

    def "hub_reboot posts to /hub/reboot and is reachable through the tool dispatch"() {
        given:
        String posted = null
        script.metaClass.hubPostForm = { String p, Map b -> posted = p; [status: 200, data: 'ok'] }

        when:
        def res = script.executeAdminTool('hub_reboot', [confirm: true])

        then:
        posted == '/hub/reboot'
        res.success == true
    }

    def "hub_reboot reports a failure rather than claiming a reboot that did not happen (#scenario)"() {
        given: "hubPostForm SWALLOWS transport errors and returns status null -- it never throws,"
        // so the status is the only success signal. An earlier revision wrapped the call in a
        // try/catch and returned success:true unconditionally: the catch could never fire, and the
        // auto-reboot escape would have logged a successful reboot for a POST that never landed --
        // exactly the wedged-web-stack case the escape exists for.
        script.metaClass.hubPostForm = { String p, Map b -> [status: st, data: null] }

        when:
        def res = script.adminRebootHub([confirm: true])

        then:
        res.success == false
        res.note?.contains('physical power cycle')
        res.error?.contains(errFragment)

        where:
        scenario                        | st   | errFragment
        'transport failure, no status'  | null | 'no response'
        'hub refused the reboot'        | 500  | '500'
        'not found'                     | 404  | '404'
    }

    // ---- hub-variable purge drives the classic hubVar wizard --------------------------------
    // There is no removeGlobalVar/removeGlobalVariable on the app class. An earlier revision called
    // one, so every purge failed with "No signature of method" and the variables leg never worked.

    def "purging a hub variable drives the deleteGV then delConfirm wizard clicks"() {
        given:
        List<Map> posts = []
        int getGlobalCalls = 0
        script.metaClass.hubGet = { String p, Map q -> '{"apps":[]}' }
        script.metaClass.hubGetStatus = { String p, Map q ->
            [status: 302, location: '/installedapp/configure/9001', data: null]
        }
        script.metaClass.hubPostForm = { String p, Map b -> posts << [path: p, body: b]; [status: 200, data: 'ok'] }
        script.metaClass.getAllGlobalVars = { -> [BAT_E2E_leftover: [value: 1]] }
        // Gone after the wizard commits, so the verify loop succeeds on its first check.
        script.metaClass.getGlobalVar = { String n -> getGlobalCalls++; null }

        when:
        def res = script.adminPurgeE2eArtifacts([confirm: true])

        then: "both clicks land on /installedapp/btn, keyed the way the hubVar page expects"
        posts.size() == 2
        posts.every { it.path == '/installedapp/btn' }
        posts[0].body.name == 'BAT_E2E_leftover'
        posts[0].body.stateAttribute == 'deleteGV'
        posts[0].body.currentPage == 'hubVar'
        posts[1].body.name == 'delConfirm'

        and: "and it is reported as deleted, not as a failure"
        res.variablesDeletedCount == 1
        res.variablesFailedCount == 0
        res.variablesDeleted == ['BAT_E2E_leftover']
    }

    def "a hub variable that survives the wizard is reported failed, never as deleted"() {
        given:
        script.metaClass.hubGet = { String p, Map q -> '{"apps":[]}' }
        script.metaClass.hubGetStatus = { String p, Map q ->
            [status: 302, location: '/installedapp/configure/9001', data: null]
        }
        script.metaClass.hubPostForm = { String p, Map b -> [status: 200, data: 'ok'] }
        script.metaClass.pauseExecution = { long ms -> }
        script.metaClass.getAllGlobalVars = { -> [BAT_E2E_inuse: [value: 1]] }
        script.metaClass.getGlobalVar = { String n -> [value: 1] }   // never goes away

        when:
        def res = script.adminPurgeE2eArtifacts([confirm: true])

        then: "an in-use variable the wizard refuses is surfaced, not silently counted as gone"
        res.variablesDeletedCount == 0
        res.variablesFailedCount == 1
        res.variablesFailed[0].name == 'BAT_E2E_inuse'
        res.success == false
    }

    def "an unresolvable Hub Variables app is reported once, not per variable"() {
        given:
        script.metaClass.hubGet = { String p, Map q -> '{"apps":[]}' }
        script.metaClass.hubGetStatus = { String p, Map q -> [status: null, location: null, data: null] }
        script.metaClass.getAllGlobalVars = { -> [BAT_E2E_a: [value: 1], BAT_E2E_b: [value: 2]] }

        when:
        def res = script.adminPurgeE2eArtifacts([confirm: true])

        then:
        res.variablesFailedCount == 1
        res.variablesFailed[0].name == '*'
        res.variablesFailed[0].error.contains('Hub Variables app id')
    }

    def "variables that do not match the prefix are never touched"() {
        given:
        List<Map> posts = []
        script.metaClass.hubGet = { String p, Map q -> '{"apps":[]}' }
        script.metaClass.hubGetStatus = { String p, Map q ->
            [status: 302, location: '/installedapp/configure/9001', data: null]
        }
        script.metaClass.hubPostForm = { String p, Map b -> posts << [path: p, body: b]; [status: 200, data: 'ok'] }
        script.metaClass.getAllGlobalVars = { -> [HomeMode: [value: 'x'], Thermostat_Target: [value: 70]] }

        when:
        def res = script.adminPurgeE2eArtifacts([confirm: true])

        then: "a prefix-scoped sweep must never reach a real hub variable"
        posts.isEmpty()
        res.variablesDeletedCount == 0
        res.variablesFailedCount == 0
    }

    def "a throwing getGlobalVar is never mistaken for a deleted variable"() {
        given: "the read fails rather than reporting absence -- that is unknown, not gone"
        script.metaClass.hubGet = { String p, Map q -> '{"apps":[]}' }
        script.metaClass.hubGetStatus = { String p, Map q ->
            [status: 302, location: '/installedapp/configure/9001', data: null]
        }
        script.metaClass.hubPostForm = { String p, Map b -> [status: 200, data: 'ok'] }
        script.metaClass.pauseExecution = { long ms -> }
        script.metaClass.getAllGlobalVars = { -> [BAT_E2E_unreadable: [value: 1]] }
        script.metaClass.getGlobalVar = { String n -> throw new RuntimeException('read failed') }

        when:
        def res = script.adminPurgeE2eArtifacts([confirm: true])

        then: "reported as a failure, so a variable still on the hub is never claimed purged"
        res.variablesDeletedCount == 0
        res.variablesFailedCount == 1
        res.variablesFailed[0].name == 'BAT_E2E_unreadable'
    }

    // ---- annotation completeness ------------------------------------------------------------

    def "every watchdog tool definition carries explicit annotation hints"() {
        given: "tools/list returns getAdminToolDefinitions() directly, so these reach the wire"
        def defs = script.getAdminToolDefinitions()

        expect: "no tool may fall back to a client's defaults -- openWorldHint defaults to TRUE when omitted"
        defs.every { it.annotations != null }
        defs.every { (it.annotations.title instanceof String) && it.annotations.title.trim() }
        defs.every { it.annotations.containsKey('readOnlyHint') }
        defs.every { it.annotations.containsKey('idempotentHint') }
        defs.every { it.annotations.containsKey('openWorldHint') }

        and: "destructiveHint is emitted on every write and omitted on reads (spec: only meaningful when readOnlyHint is false)"
        defs.findAll { it.annotations.readOnlyHint == false }.every { it.annotations.containsKey('destructiveHint') }
        defs.findAll { it.annotations.readOnlyHint == true }.every { !it.annotations.containsKey('destructiveHint') }
    }

    def "the tools that reach the open internet are the ones that fetch by URL"() {
        given: "openWorldHint is an accuracy statement: the hub is the closed-world system"
        def defs = script.getAdminToolDefinitions()

        expect: "only the importUrl/zip-fetch/platform-download tools leave the hub"
        (defs.findAll { it.annotations?.openWorldHint == true }*.name as Set) ==
            ['hub_update_app', 'hub_create_library', 'hub_update_library',
             'hub_update_platform', 'hub_install_bundle'] as Set
    }

    def "hub_reboot is declared a destructive write"() {
        given:
        def rb = script.getAdminToolDefinitions().find { it.name == 'hub_reboot' }

        expect:
        rb.annotations.readOnlyHint == false
        rb.annotations.destructiveHint == true
        rb.annotations.openWorldHint == false
    }


    // ---- served errors are not a wedge -------------------------------------------------------
    // Hubitat's httpGet/httpPost THROW on 4xx/5xx, so the catch-all counted an ANSWERED error as a
    // loopback failure. That inflated the wedge streak on a healthy hub and could auto-reboot it.

    def "an ANSWERED error carries its status, so it is not read as a dead web stack (#code)"() {
        expect: "httpGet/httpPost THROW on 4xx/5xx, but the hub served us -- the status proves it"
        script.httpStatusOf(new FakeHttpException(code)) == code

        where:
        code << [404, 500, 302]
    }

    def "a real transport failure carries no status"() {
        expect: "only this shape is a dead web stack"
        script.httpStatusOf(new RuntimeException('Read timed out')) == null
    }

    def "the wedge streak advances only on a status-less failure"() {
        given:
        atomicStateMap.loopbackFailStreak = 3

        when: 'the caller passes the answered/unanswered decision noteLoopback is given'
        script.noteLoopback(answered)

        then:
        atomicStateMap.loopbackFailStreak == expected

        where:
        answered | expected
        true     | 0
        false    | 4
    }

    def "a fresh failure streak stamps its own baseline, and a success clears it"() {
        given: 'explicitly no prior streak -- the baseline is only stamped on the FIRST failure'
        atomicStateMap.loopbackFailStreak = 0
        atomicStateMap.loopbackStreakStartedAt = null
        atomicStateMap.loopbackLastOkAt = null

        when:
        script.noteLoopback(false)

        then: 'this is what gives hubLooksWedged a baseline when there has never been a success'
        atomicStateMap.loopbackFailStreak == 1
        atomicStateMap.loopbackStreakStartedAt != null

        when: 'the hub answers again'
        script.noteLoopback(true)

        then: 'the streak and its baseline are both released'
        atomicStateMap.loopbackFailStreak == 0
        atomicStateMap.loopbackStreakStartedAt == null
        atomicStateMap.loopbackLastOkAt != null
    }

    def "a watchdog that has never had a successful loopback call can still detect a wedge"() {
        given: "no loopbackLastOkAt at all -- the hub was wedged before this app ever ran"
        atomicStateMap.loopbackFailStreak = 12
        atomicStateMap.loopbackLastOkAt = null
        atomicStateMap.loopbackStreakStartedAt = System.currentTimeMillis() - 300_000L
        String posted = null
        script.metaClass.readFlag = { -> null }
        script.metaClass.hubPostForm = { String p, Map b -> posted = p; [status: 200, data: 'ok'] }
        script.metaClass.probeLoopbackAlive = { -> false }

        when:
        script.checkDeadman()

        then: "the streak-start timestamp is the baseline, so the escape still fires"
        posted == '/hub/reboot'
    }

    // ---- purge failures carry an aggregate error + recovery note ------------------------------

    def "a purge with failures reports a top-level error and actionable note"() {
        given: "one variable the wizard will not remove"
        script.metaClass.hubGet = { String p, Map q -> '{"apps":[]}' }
        script.metaClass.hubGetStatus = { String p, Map q ->
            [status: 302, location: '/installedapp/configure/9001', data: null]
        }
        script.metaClass.hubPostForm = { String p, Map b -> [status: 200, data: 'ok'] }
        script.metaClass.pauseExecution = { long ms -> }
        script.metaClass.getAllGlobalVars = { -> [BAT_E2E_stuck: [value: 1]] }
        script.metaClass.getGlobalVar = { String n -> [value: 1] }   // never goes away

        when:
        def res = script.adminPurgeE2eArtifacts([confirm: true])

        then: "a caller must not have to diff count fields to notice the sweep failed"
        res.success == false
        res.error?.contains('1 variable(s)')
        res.note?.contains('Do NOT blind-retry')
    }

    def "a clean purge carries no error field"() {
        given:
        script.metaClass.hubGet = { String p, Map q -> '{"apps":[]}' }
        script.metaClass.getAllGlobalVars = { -> [:] }

        when:
        def res = script.adminPurgeE2eArtifacts([confirm: true])

        then:
        res.success == true
        res.error == null
    }


    // ---- the auto-reboot must NOT fire on downtime we caused, or on stale counters -------------
    // These are the misfire modes that matter: rebooting mid platform-install, and boot-looping a
    // hub that has already come back. Both are worse than the wedge the escape exists to clear.

    def "a live probe that answers stands the escape down, even with a wedged-looking streak"() {
        given: "counters survive a hub restart, and this runs BEFORE readFlag -- so the first tick"
        // after recovery would otherwise see a stale streak plus an old lastOk and reboot a
        // healthy hub, then do it again on the next tick.
        String posted = null
        script.metaClass.readFlag = { -> null }
        script.metaClass.hubPostForm = { String p, Map b -> posted = p; [status: 200, data: 'ok'] }
        script.metaClass.probeLoopbackAlive = { -> true }   // the hub is actually back
        atomicStateMap.loopbackFailStreak = 12
        atomicStateMap.loopbackLastOkAt = System.currentTimeMillis() - 600_000L

        when:
        script.checkDeadman()

        then: 'no reboot -- accumulated state alone is never enough to act'
        posted == null
    }

    def "a deliberate reboot/platform-update window suppresses the escape (#reason)"() {
        given: "the hub is dark because WE took it down; rebooting into that is the worst outcome"
        String posted = null
        script.metaClass.readFlag = { -> null }
        script.metaClass.hubPostForm = { String p, Map b -> posted = p; [status: 200, data: 'ok'] }
        script.metaClass.probeLoopbackAlive = { -> false }   // genuinely unreachable, as expected
        atomicStateMap.loopbackFailStreak = 20
        atomicStateMap.loopbackLastOkAt = System.currentTimeMillis() - 900_000L
        atomicStateMap.expectedDownUntil = System.currentTimeMillis() + remainingMs

        when:
        script.checkDeadman()

        then: 'no reboot while the expected-downtime window is open'
        posted == null

        where:
        reason                       | remainingMs
        'platform update, 20 min left' | 1_200_000L
        'operator reboot, 2 min left'  | 120_000L
    }

    def "once the expected-downtime window expires a genuine wedge is still caught"() {
        given:
        String posted = null
        script.metaClass.readFlag = { -> null }
        script.metaClass.hubPostForm = { String p, Map b -> posted = p; [status: 200, data: 'ok'] }
        script.metaClass.probeLoopbackAlive = { -> false }
        atomicStateMap.loopbackFailStreak = 20
        atomicStateMap.loopbackLastOkAt = System.currentTimeMillis() - 900_000L
        atomicStateMap.expectedDownUntil = System.currentTimeMillis() - 1_000L   // already elapsed

        when:
        script.checkDeadman()

        then: 'the suppression is a window, not a permanent disable'
        posted == '/hub/reboot'
    }

    def "hub_reboot stamps the downtime window so it cannot trigger its own escape"() {
        given:
        script.metaClass.hubPostForm = { String p, Map b -> [status: 200, data: 'ok'] }

        when:
        script.adminRebootHub([confirm: true])

        then:
        atomicStateMap.expectedDownUntil != null
        atomicStateMap.expectedDownUntil > System.currentTimeMillis()
    }

    def "hub_update_platform stamps a longer window before the hub can go dark"() {
        given: "the update takes the hub down for 5-10 min by design"
        script.metaClass.hubGet = { String p, Map q -> '{"ok":true}' }

        when:
        script.adminUpdatePlatform([confirm: true])

        then: 'stamped generously -- a reboot mid firmware-install is unrecoverable'
        atomicStateMap.expectedDownUntil != null
        (atomicStateMap.expectedDownUntil - System.currentTimeMillis()) > 1_000_000L
    }

    def "statusOnly platform polling does NOT stamp a downtime window"() {
        given: "polling progress takes nothing down, so it must not blind the escape"
        script.metaClass.hubGet = { String p, Map q -> '{"state":"downloading"}' }

        when:
        script.adminUpdatePlatform([statusOnly: true])

        then:
        atomicStateMap.expectedDownUntil == null
    }

    def "the liveness probe treats ANY served status as alive (#code)"() {
        given: "the question is only whether the web stack served us"
        script.metaClass.hubGetStatus = { String p, Map q, int t = 30 -> [status: code, location: null, data: null] }

        expect: "a hub answering 404 on the probe endpoint is alive -- rebooting it would be the misfire"
        script.probeLoopbackAlive()

        where:
        code << [200, 404, 500]
    }

    def "the liveness probe reports dead only when nothing answered"() {
        given:
        script.metaClass.hubGetStatus = { String p, Map q, int t = 30 -> [status: null, location: null, data: null] }

        expect:
        !script.probeLoopbackAlive()
    }

    def "a held purge claim makes a second caller yield without sweeping"() {
        given:
        int enumerations = 0
        script.metaClass.hubGet = { String p, Map q -> enumerations++; '{"apps":[]}' }
        atomicStateMap.purgeInFlightAt = System.currentTimeMillis() - 5_000L
        atomicStateMap.purgeClaim = 'purge-someone-else'

        when:
        def res = script.adminPurgeE2eArtifacts([confirm: true])

        then:
        enumerations == 0
        res.inFlight == true
    }

    def "the claim and its marker are both released once the sweep finishes"() {
        given:
        script.metaClass.hubGet = { String p, Map q -> '{"apps":[]}' }
        script.metaClass.getAllGlobalVars = { -> [:] }

        when:
        script.adminPurgeE2eArtifacts([confirm: true])

        then: 'a stranded claim would lock out every later sweep for 15 minutes'
        atomicStateMap.purgeInFlightAt == null
        atomicStateMap.purgeClaim == null
    }

    def "steady-state successes do not rewrite atomicState on every loopback call"() {
        given: "a healthy hub: streak already 0, lastOk stamped moments ago"
        long recent = System.currentTimeMillis() - 5_000L
        atomicStateMap.loopbackFailStreak = 0
        atomicStateMap.loopbackLastOkAt = recent

        when: "a purge sweep makes 150+ loopback calls in a minute -- each is a DB write if unthrottled"
        script.noteLoopback(true)

        then: "nothing changed, so nothing was written"
        atomicStateMap.loopbackLastOkAt == recent
    }

    def "a success refreshes lastOk once it is older than the throttle window"() {
        given:
        long stale = System.currentTimeMillis() - 60_000L
        atomicStateMap.loopbackFailStreak = 0
        atomicStateMap.loopbackLastOkAt = stale

        when:
        script.noteLoopback(true)

        then: "the wedge detector's baseline still advances, just not on every call"
        atomicStateMap.loopbackLastOkAt > stale
    }


    // ---- CodeRabbit round 4: contracts and latch edge cases -----------------------------------

    def "every tool definition carries an object-root inputSchema (the MCP spec makes it REQUIRED)"() {
        given: "tools/list returns these straight to the wire"
        def defs = script.getAdminToolDefinitions()

        expect: "a spec-validating client rejects the whole list if one tool lacks it"
        defs.every { it.inputSchema instanceof Map }
        defs.every { it.inputSchema.type == 'object' }
        defs.every { it.inputSchema.containsKey('properties') }

        and: "every required parameter is a declared property -- a placeholder schema cannot satisfy this"
        defs.findAll { it.inputSchema.required }.every { d -> d.inputSchema.required.every { d.inputSchema.properties.containsKey(it) } }
        defs.find { it.name == 'hub_update_app' }.inputSchema.properties.containsKey('appId')
        defs.find { it.name == 'hub_reboot' }.inputSchema.required == ['confirm']
    }

    def "hub_reboot is reachable through the JSON-RPC envelope, not only the direct dispatch"() {
        given: "the dispatch-envelope leg the repo requires for every new tool"
        String posted = null
        script.metaClass.hubPostForm = { String p, Map b -> posted = p; [status: 200, data: 'ok'] }

        when:
        def env = script.processJsonRpcMessage([jsonrpc: '2.0', id: 7, method: 'tools/call',
                                                params: [name: 'hub_reboot', arguments: [confirm: true]]])

        then: "a well-formed result envelope whose text payload is the tool's own result"
        posted == '/hub/reboot'
        env.jsonrpc == '2.0'
        env.id == 7
        env.error == null
        def payload = new groovy.json.JsonSlurper().parseText(env.result.content[0].text as String)
        payload.success == true
        payload.status == 200
    }

    def "a finished sweep leaves a SUCCESSOR's claim alone"() {
        given: "this sweep's claim was superseded (it ran past the 15-min staleness escape)"
        script.metaClass.hubGet = { String p, Map q -> '{"apps":[]}' }
        script.metaClass.getAllGlobalVars = { -> [:] }
        // Make the sweep body itself install a successor claim mid-flight.
        script.metaClass.purgeE2eArtifactsLocked = { String prefix ->
            atomicStateMap.purgeInFlightAt = System.currentTimeMillis()
            atomicStateMap.purgeClaim = 'purge-successor'
            atomicStateMap.purgeClaimPrefix = 'BAT_E2E_'
            [success: true, prefix: prefix, deletedCount: 0, failedCount: 0, deleted: [], failed: [],
             variablesDeletedCount: 0, variablesFailedCount: 0, variablesDeleted: [], variablesFailed: []]
        }

        when:
        script.adminPurgeE2eArtifacts([confirm: true])

        then: "the successor's markers survive -- clearing them would let a third request pile on"
        atomicStateMap.purgeClaim == 'purge-successor'
        atomicStateMap.purgeInFlightAt != null
    }

    def "an in-flight sweep for a DIFFERENT prefix reports busy, never covered"() {
        given:
        int enumerations = 0
        script.metaClass.hubGet = { String p, Map q -> enumerations++; '{"apps":[]}' }
        atomicStateMap.purgeInFlightAt = System.currentTimeMillis() - 10_000L
        atomicStateMap.purgeClaim = 'purge-other'
        atomicStateMap.purgeClaimPrefix = 'BAT_E2E_'

        when:
        def res = script.adminPurgeE2eArtifacts([confirm: true, prefix: 'OTHER_'])

        then: "the caller must not be told OTHER_ was swept when only BAT_E2E_ is running"
        enumerations == 0
        res.success == false
        res.busy == true
        res.activePrefix == 'BAT_E2E_'
        res.inFlight == null
    }

    def "an in-flight sweep for the SAME prefix still returns the covered marker"() {
        given:
        script.metaClass.hubGet = { String p, Map q -> '{"apps":[]}' }
        atomicStateMap.purgeInFlightAt = System.currentTimeMillis() - 10_000L
        atomicStateMap.purgeClaim = 'purge-other'
        atomicStateMap.purgeClaimPrefix = 'BAT_E2E_'

        when:
        def res = script.adminPurgeE2eArtifacts([confirm: true])

        then:
        res.success == true
        res.inFlight == true
    }

    def "hub_update_platform with no response is a failure and does NOT blind the escape"() {
        given: "hubGet swallows the transport error and returns null"
        script.metaClass.hubGet = { String p, Map q -> p.endsWith('checkForUpdate') ? '{"ok":true}' : null }

        when:
        def res = script.adminUpdatePlatform([confirm: true])

        then: "the hub never accepted the update, so nothing is going down"
        res.success == false
        res.error?.contains('did not accept')
        atomicStateMap.expectedDownUntil == null
    }

    def "hub_update_platform stamps the downtime window only after the hub accepted it"() {
        given:
        script.metaClass.hubGet = { String p, Map q -> '{"ok":true}' }

        when:
        def res = script.adminUpdatePlatform([confirm: true])

        then:
        res.success == true
        atomicStateMap.expectedDownUntil > System.currentTimeMillis()
    }

    def "hub_update_platform statusOnly reports a failed poll instead of success with no status"() {
        given: "hubGet swallows the transport error into null"
        script.metaClass.hubGet = { String p, Map q -> null }

        when:
        def res = script.adminUpdatePlatform([statusOnly: true])

        then:
        res.success == false
        res.error?.contains('No response')
        res.note?.contains('Retry')
    }

    def "the expected-downtime window vetoes ONLY the reboot -- the tick still reads the flag"() {
        given: "wedged-looking counters inside a deliberate downtime window"
        boolean flagRead = false
        String posted = null
        script.metaClass.readFlag = { -> flagRead = true; null }
        script.metaClass.hubPostForm = { String p, Map b -> posted = p; [status: 200, data: 'ok'] }
        script.metaClass.probeLoopbackAlive = { -> false }
        atomicStateMap.loopbackFailStreak = 20
        atomicStateMap.loopbackLastOkAt = System.currentTimeMillis() - 900_000L
        atomicStateMap.expectedDownUntil = System.currentTimeMillis() + 600_000L

        when:
        script.checkDeadman()

        then: "no reboot, but the flag IS read -- that read is what resets the counters once the hub is back"
        posted == null
        flagRead
    }

    def "a null getAllGlobalVars is reported as an enumeration failure, never as a clean sweep"() {
        given:
        script.metaClass.hubGet = { String p, Map q -> '{"apps":[]}' }
        script.metaClass.getAllGlobalVars = { -> null }

        when:
        def res = script.adminPurgeE2eArtifacts([confirm: true])

        then:
        res.success == false
        res.variablesFailedCount == 1
        res.variablesFailed[0].name == '*'
        res.variablesFailed[0].error.contains('could not enumerate')
    }

    // ---- review round 7 ----------------------------------------------------------------------

    def "findHubVariablesAppId anchors on the configure path and follows the create hop (#scenario)"() {
        given: "the redirect shapes the hub actually produces"
        def hops = []
        script.metaClass.hubGetStatus = { String p, Map q, int t = 30 ->
            hops << p
            if (p == '/installedapp/direct/hubVariables') return [status: 302, location: firstLoc, data: null]
            if (p == '/installedapp/create/555') return [status: 302, location: '/installedapp/configure/9001', data: null]
            [status: 404, location: null, data: null]
        }

        expect: "the INSTANCE id -- never 127 from an absolute URL, never the type id from the create hop"
        script.findHubVariablesAppId() == expected

        where:
        scenario                          | firstLoc                                                   | expected
        'relative configure'              | '/installedapp/configure/9001'                             | 9001
        'absolute configure'              | 'http://127.0.0.1:8080/installedapp/configure/9001'        | 9001
        'create hop then configure'       | '/installedapp/create/555'                                 | 9001
        'absolute create hop'             | 'http://127.0.0.1:8080/installedapp/create/555'            | 9001
        'unexpected shape'                | '/installedapp/list'                                       | null
    }

    def "findHubVariablesAppId returns null when the alias does not redirect"() {
        given:
        script.metaClass.hubGetStatus = { String p, Map q, int t = 30 -> [status: 200, location: null, data: '<html>'] }

        expect:
        script.findHubVariablesAppId() == null
    }

    def "a rejected wizard click is reported as such, not as a variable in use"() {
        given:
        script.metaClass.hubGet = { String p, Map q -> '{"apps":[]}' }
        script.metaClass.hubGetStatus = { String p, Map q, int t = 30 -> [status: 302, location: '/installedapp/configure/9001', data: null] }
        script.metaClass.hubPostForm = { String p, Map b -> [status: 500, data: null] }
        script.metaClass.pauseExecution = { long ms -> }
        script.metaClass.getAllGlobalVars = { -> [BAT_E2E_x: [value: 1]] }
        script.metaClass.getGlobalVar = { String n -> [value: 1] }

        when:
        def res = script.adminPurgeE2eArtifacts([confirm: true])

        then: "the operator is not sent hunting for a referencing rule when the hub refused the click"
        res.variablesFailed[0].error.contains('not accepted')
        res.variablesFailed[0].error.contains('deleteGV=500')
        !res.variablesFailed[0].error.contains('referenced by a rule')
    }

    def "hub_reboot without confirm is refused and stamps NO downtime window"() {
        given:
        String posted = null
        script.metaClass.hubPostForm = { String p, Map b -> posted = p; [status: 200, data: 'ok'] }

        when:
        script.adminRebootHub([:])

        then: "the most destructive tool keeps its gate, and a refused call cannot blind the escape"
        thrown(IllegalArgumentException)
        posted == null
        atomicStateMap.expectedDownUntil == null
    }

    def "a reboot POST that did not land clears the downtime window it had stamped"() {
        given:
        script.metaClass.hubPostForm = { String p, Map b -> [status: null, data: null] }

        when:
        def res = script.adminRebootHub([confirm: true])

        then: "otherwise the escape would stand down for 10 min on a reboot that never happened"
        res.success == false
        atomicStateMap.expectedDownUntil == null
    }

    def "a failed auto-reboot does not burn the 30-minute rate limit"() {
        given:
        script.metaClass.readFlag = { -> null }
        script.metaClass.hubPostForm = { String p, Map b -> [status: null, data: null] }
        script.metaClass.probeLoopbackAlive = { -> false }
        atomicStateMap.loopbackFailStreak = 12
        atomicStateMap.loopbackLastOkAt = System.currentTimeMillis() - 300_000L

        when:
        script.checkDeadman()

        then: "the next tick may try again"
        atomicStateMap.lastAutoRebootAt == null
    }

    def "hub_manage_variables sub-tools declare their real parameters"() {
        given:
        def catalog = script.adminManageVariables([:])
        def subs = catalog.tools ?: catalog.subTools ?: []

        expect:
        subs.size() == 2
        subs.every { it.annotations?.title && it.annotations.containsKey('readOnlyHint') && it.annotations.containsKey('openWorldHint') }
        subs.find { it.name == 'hub_get_variable' }.inputSchema.required == ['name']
        subs.find { it.name == 'hub_set_variable' }.inputSchema.required.containsAll(['name', 'value', 'confirm'])
    }

    def "retry bookkeeping survives a failed writeFlag -- the next tick still backs off and the cap still advances"() {
        given: "the restore fails and the flag write fails too (a loaded hub)"
        script.metaClass.adminUpdateApp = { Map a -> [success: false, error: 'nope'] }
        script.metaClass.readFlag = { -> [armed: false, intent: 'disarm', runId: '7', fireAttempts: 0,
                                          manifest: [app: [classId: '178', url: 'https://raw.example/main/app.groovy'], libraries: []]] }
        script.metaClass.writeFlag = { Map fl -> false }

        when:
        script.checkDeadman()

        then: "the attempt is counted where a loopback write cannot lose it"
        atomicStateMap.restoreAttempts == 1
        atomicStateMap.restoreLastAttemptAt != null

        and: "the next tick paces off the mirror even though the stale flag still says zero attempts"
        script.retryBackoffPending([runId: '7', fireAttempts: 0, lastAttemptAt: null], 'disarm')
    }

    def "the retry mirror is cleared when the restore succeeds or latches"() {
        given:
        script.metaClass.adminUpdateApp = { Map a -> [success: true] }
        script.metaClass.readFlag = { -> [armed: false, intent: 'disarm', runId: '9', fireAttempts: 2,
                                          manifest: [app: [classId: '178', url: 'https://raw.example/main/app.groovy'], libraries: []]] }
        script.metaClass.writeFlag = { Map fl -> true }
        atomicStateMap.restoreAttempts = 2
        atomicStateMap.restoreLastAttemptAt = System.currentTimeMillis() - 600_000L

        when:
        script.checkDeadman()

        then: "a stale mirror must not pace the NEXT run's first attempt"
        atomicStateMap.restoreAttempts == null
        atomicStateMap.restoreLastAttemptAt == null
    }

    def "a retry mirror left by a DIFFERENT run does not pace this run"() {
        given: "a stale mirror from run 6; this tick is run 7"
        atomicStateMap.restoreAttempts = 3
        atomicStateMap.restoreLastAttemptAt = System.currentTimeMillis() - 30_000L
        atomicStateMap.restoreAttemptsRun = '6'

        expect: "the new run's first attempt is immediate"
        !script.retryBackoffPending([runId: '7', fireAttempts: 0, lastAttemptAt: null], 'disarm')
    }

    def "a failed reboot POST restores the downtime window it found, rather than clearing it"() {
        given: "a platform update already has a window open"
        long existing = System.currentTimeMillis() + 900_000L
        atomicStateMap.expectedDownUntil = existing
        script.metaClass.hubPostForm = { String p, Map b -> [status: null, data: null] }

        when:
        def res = script.adminRebootHub([confirm: true])

        then: "the platform update's suppression survives a reboot attempt that did not land"
        res.success == false
        atomicStateMap.expectedDownUntil == existing
    }

    def "the persisted failure streak saturates at the wedge threshold (streak #streak)"() {
        given:
        atomicStateMap.loopbackFailStreak = streak
        atomicStateMap.loopbackStreakStartedAt = System.currentTimeMillis() - 1_000L

        when:
        script.noteLoopback(false)

        then: "below the threshold it still counts; at or past it nothing is written"
        atomicStateMap.loopbackFailStreak == expected

        where:
        streak | expected
        7      | 8
        8      | 8
        40     | 40
    }

    def "two overlapping wedge checks issue exactly one reboot POST"() {
        given: "a wedged hub whose reboot POST is slow enough for the second check to overlap"
        def posts = new java.util.concurrent.atomic.AtomicInteger(0)
        script.metaClass.readFlag = { -> null }
        script.metaClass.probeLoopbackAlive = { -> false }
        script.metaClass.hubPostForm = { String p, Map b -> posts.incrementAndGet(); Thread.sleep(300); [status: 200, data: 'ok'] }
        atomicStateMap.loopbackFailStreak = 20
        atomicStateMap.loopbackLastOkAt = System.currentTimeMillis() - 900_000L

        when: "the scheduled tick and a kick arrive together"
        def threads = (1..2).collect { Thread.start { script.checkDeadman() } }
        threads*.join()

        then: "the second decision saw the first's timestamp"
        posts.get() == 1
        atomicStateMap.lastAutoRebootAt != null
    }

    def "a failed reboot leaves a NEWER downtime window alone"() {
        given: "a platform update stamps its window while the reboot POST is in flight"
        long newer = System.currentTimeMillis() + 1_500_000L
        script.metaClass.hubPostForm = { String p, Map b -> atomicStateMap.expectedDownUntil = newer; [status: null, data: null] }

        when:
        def res = script.adminRebootHub([confirm: true])

        then: "the reboot's own stamp is not restored over the update's"
        res.success == false
        atomicStateMap.expectedDownUntil == newer
    }

    def "a purge sweep renews its claim before every delete and stops once the claim is lost"() {
        given: "two apps to purge; the first delete hands the claim to a newer sweep"
        script.metaClass.hubGet = { String path, Map q = [:], Integer t = null ->
            path == "/hub2/appsList" ? groovy.json.JsonOutput.toJson([apps: [[data: [id: 1, name: "BAT_E2E_a"]], [data: [id: 2, name: "BAT_E2E_b"]]]]) : null
        }
        script.metaClass.getAllGlobalVars = { -> [:] }
        def deletes = []
        script.metaClass.adminForceDeleteInstalledApp = { Map a ->
            deletes << a.id
            atomicStateMap.purgeClaim = 'purge-newer'
            [success: true]
        }
        atomicStateMap.purgeClaim = 'purge-mine'
        atomicStateMap.purgeInFlightAt = 1L

        when:
        def res = script.purgeE2eArtifactsLocked("BAT_E2E_", 'purge-mine')

        then: "the claim stamp was renewed for the delete that ran, and the second target was not touched"
        deletes == [1]
        (atomicStateMap.purgeInFlightAt as Long) > 1L
        res.failed.any { it.id == 2 && it.error.contains("claim lost") }
    }

}

class FakeHttpException extends RuntimeException {
    // Hubitat's httpGet/httpPost throw an exception carrying the response on a 4xx/5xx; the
    // watchdog reads e.response.status off it. A metaClass-patched RuntimeException does not
    // present the property to the script, so model it as a real type.
    def response
    FakeHttpException(Integer status) {
        super("HTTP ${status}")
        this.response = [status: status]
    }
}
