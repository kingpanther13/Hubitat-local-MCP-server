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
            else { [status: 404, location: null, data: null] }   // gone-check: absent
        }
        def removedVars = []
        script.metaClass.getAllGlobalVars = { -> [BAT_E2E_v1: [type: "string"], RealVar: [type: "string"], BAT_E2E_v2: [type: "integer"]] }
        script.metaClass.removeGlobalVariable = { String n -> removedVars << n; true }

        when:
        def r = script.adminPurgeE2eArtifacts([confirm: true])

        then:
        r.success == true
        r.deletedCount == 2
        (r.deleted*.id).collect { it as Integer }.sort() == [100, 201]
        forced.sort() == ["/installedapp/forcedelete/100/quiet", "/installedapp/forcedelete/201/quiet"]
        r.variablesDeletedCount == 2
        removedVars.sort() == ["BAT_E2E_v1", "BAT_E2E_v2"]
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
                                                     libraries: [[id: '119', namespace: 'mcp', name: 'McpSmokeTestLib']],
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
        // Hub holds main's mcp_smoke_test bundle + the PR's mcp_libraries bundle; main's McpSmokeTestLib +
        // the PR's McpRoomsLib + an unrelated 'other'-namespace library. The manifest's main sets list
        // only mcp_smoke_test + McpSmokeTestLib, so the PR's bundle/library are the stale ones to drop.
        script.metaClass.hubGet = { String p, Map q -> null }
        script.metaClass.adminInstallBundle = { Map a -> [success: true] }
        script.metaClass.adminListBundles = { Map a -> [source: 'hub_api', bundles: [
            [id: '1', name: 'mcp_smoke_test', namespace: 'mcp'],
            [id: '2', name: 'mcp_libraries', namespace: 'mcp']]] }
        script.metaClass.adminDeleteBundle = { Map a -> deletedBundles << a.bundleId; [success: true, verified: true] }
        script.metaClass.adminListLibraries = { Map a -> [source: 'hub_api', libraries: [
            [id: '10', name: 'McpSmokeTestLib', namespace: 'mcp'],
            [id: '11', name: 'McpRoomsLib', namespace: 'mcp'],
            [id: '12', name: 'Unrelated', namespace: 'other']]] }
        script.metaClass.adminDeleteItem = { Map a -> deletedLibs << a.id; [success: true] }
        script.metaClass.adminUpdateApp = { Map a -> [success: true] }
        Map written = null
        script.metaClass.readFlag = { -> [armed: true, deadline: '0', runId: '9', manifest: [
            app: [classId: '178', url: 'https://raw.example/main/app.groovy'],
            libraries: [[namespace: 'mcp', name: 'McpSmokeTestLib', id: '10']],
            bundles: [[namespace: 'mcp', name: 'mcp_smoke_test', url: 'https://raw.example/main/bundle.zip']]]] }
        script.metaClass.writeFlag = { Map fl -> written = fl; true }

        when:
        script.checkDeadman()

        then:
        written?.restoreResult == 'restored'
        deletedBundles == ['2']     // PR bundle dropped; main's mcp_smoke_test kept
        deletedLibs == ['11']       // PR library dropped; main's McpSmokeTestLib kept; 'other' namespace untouched
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
            [id: '1', name: 'mcp_smoke_test', namespace: 'mcp'],
            [id: '2', name: 'mcp_libraries', namespace: 'mcp']]] }
        script.metaClass.adminDeleteBundle = { Map a -> [success: false, error: 'hub refused'] }   // delete FAILS
        script.metaClass.adminListLibraries = { Map a -> [source: 'hub_api', libraries: []] }
        script.metaClass.adminUpdateApp = { Map a -> [success: true] }
        Map written = null
        script.metaClass.readFlag = { -> [armed: true, deadline: '0', runId: '9', manifest: [
            app: [classId: '178', url: 'https://raw.example/main/app.groovy'],
            libraries: [[namespace: 'mcp', name: 'McpSmokeTestLib', id: '10']],
            bundles: [[namespace: 'mcp', name: 'mcp_smoke_test', url: 'https://raw.example/main/bundle.zip']]]] }
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
            [id: '10', name: 'McpSmokeTestLib', namespace: 'mcp'],
            [id: '11', name: 'McpRoomsLib', namespace: 'mcp']]] }
        script.metaClass.adminDeleteItem = { Map a -> throw new RuntimeException('boom') }   // delete THROWS
        script.metaClass.adminUpdateApp = { Map a -> [success: true] }
        Map written = null
        script.metaClass.readFlag = { -> [armed: true, deadline: '0', runId: '9', manifest: [
            app: [classId: '178', url: 'https://raw.example/main/app.groovy'],
            libraries: [[namespace: 'mcp', name: 'McpSmokeTestLib', id: '10']],
            bundles: [[namespace: 'mcp', name: 'mcp_smoke_test', url: 'https://raw.example/main/bundle.zip']]]] }
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
            bundles: [[namespace: 'mcp', name: 'mcp_smoke_test', url: 'https://raw.example/main/bundle.zip']]]] }
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
}
