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
 *   - restoreApp / restoreLibrary no-op requires BYTE-IDENTICAL live source (not a length match);
 *   - adminUpdateLibrary fails CLOSED on a dropped/invalid POST (a null response is NOT success);
 *   - adminGetSource's noSave gate (the deploy probes pass noSave so they don't auto-save the live PR
 *     source over the dead-man restore cache -- the critical cache-poisoning fix);
 *   - resolveLibraryId re-resolves a library id by namespace.name (deploy-time delete+recreate);
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
    def "restoreApp no-op requires BYTE-IDENTICAL live source, not just a length match (#scenario)"() {
        given:
        String cached = 'a' * 100
        script.metaClass.readHubFileText = { String fn -> cached }
        script.metaClass.appCodeVersion = { String id -> 5 }      // version does NOT advance -> no-op path
        script.metaClass.hubPostForm = { String p, Map b, int t = 420 -> [status: 200, data: '{"status":"success"}'] }
        script.metaClass.readAppSource = { String id -> live }

        expect:
        script.restoreApp('178', 'mcp-source-app-178.groovy') == expected

        where:
        scenario                          | live      || expected
        'byte-identical -> restored'      | 'a' * 100 || true
        'same length, different -> NOT'   | 'b' * 100 || false
        'different length -> NOT'         | 'a' * 90  || false
    }

    @Unroll
    def "restoreLibrary no-op requires BYTE-IDENTICAL live source (#scenario)"() {
        given:
        String cached = 'a' * 100
        script.metaClass.readHubFileText = { String fn -> cached }
        script.metaClass.libraryVersion = { String id -> 5 }      // no advance -> no-op path
        script.metaClass.hubPostJson = { String p, String b -> [status: 200, data: '{"success":true,"id":119,"version":5}'] }
        script.metaClass.readLibrarySource = { String id -> live }
        script.metaClass.hubGet = { String p, Map q -> "" }       // self-heal delete GET (no-op here)

        expect:
        script.restoreLibrary('119', 'mcp-source-library-119.groovy') == expected

        where:
        scenario                          | live      || expected
        'byte-identical -> restored'      | 'a' * 100 || true
        'same length, different -> NOT'   | 'b' * 100 || false
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

    @Unroll
    def "resolveLibraryId re-resolves by namespace.name, else falls back to the manifest id (#scenario)"() {
        given:
        script.metaClass.hubGet = { String p, Map q ->
            '[{"namespace":"mcp","name":"Foo","id":200},{"namespace":"other","name":"Bar","id":201}]'
        }

        expect:
        script.resolveLibraryId(ns, name, '119') == expected

        where:
        scenario               | ns    | name   || expected
        'match -> current id'  | 'mcp' | 'Foo'  || '200'
        'no match -> fallback' | 'mcp' | 'Nope' || '119'
        'null name -> fallback'| 'mcp' | null   || '119'
    }

    def "checkDeadman tolerates a non-numeric deadline (fires the restore, never throws)"() {
        given:
        // restorePackage is private (metaClass can't intercept it), so stub the PUBLIC leaf restoreApp/
        // restoreLibrary the real restorePackage calls, and capture the flag actAndRecord writes back.
        boolean appRestored = false
        Map written = null
        script.metaClass.restoreApp = { String c, String f -> appRestored = true; true }
        script.metaClass.restoreLibrary = { String i, String f -> true }
        script.metaClass.readFlag = { -> [armed: true, deadline: 'not-a-number', runId: '1',
                                          manifest: [app: [classId: '178', file: 'f'], libraries: []]] }
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
        script.metaClass.restoreApp = { String c, String f -> true }
        script.metaClass.restoreLibrary = { String i, String f -> true }
        script.metaClass.uploadHubFile = { String name, byte[] bytes -> uploaded[name] = new String(bytes, 'UTF-8') }
        script.metaClass.readFlag = { -> [armed: false, intent: 'disarm', runId: '7', canonicalMainSha: 'abc123',
                                          manifest: [app: [classId: '178', file: 'f'], libraries: []]] }
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
        script.metaClass.restoreApp = { String c, String f -> restores++; true }
        script.metaClass.restoreLibrary = { String i, String f -> true }
        script.metaClass.readFlag = { -> [armed: false, intent: 'disarm', runId: '7',
                                          manifest: [app: [classId: '178', file: 'f'], libraries: []]] }
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
        script.metaClass.restoreApp = { String c, String f -> restores++; true }
        script.metaClass.restoreLibrary = { String i, String f -> true }
        script.metaClass.readFlag = { -> [armed: false, intent: 'disarm', runId: '7',
                                          manifest: [app: [classId: '178', file: 'f'], libraries: []]] }
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
        script.metaClass.restoreApp = { String c, String f -> true }
        script.metaClass.restoreLibrary = { String i, String f -> true }
        script.metaClass.readFlag = { -> [armed: false, intent: 'disarm', runId: '8',
                                          manifest: [app: [classId: '178', file: 'f'], libraries: []]] }
        script.metaClass.writeFlag = { Map fl -> true }

        when:
        script.checkDeadman()

        then: 'a finished restore releases the latch for the next run'
        atomicStateMap.restoreInFlightFor == null
    }

    def "no marker is stamped when the flag has no canonicalMainSha or the restore fails (#scenario)"() {
        given:
        Map uploaded = [:]
        script.metaClass.restoreApp = { String c, String f -> restoreOk }
        script.metaClass.restoreLibrary = { String i, String f -> restoreOk }
        script.metaClass.uploadHubFile = { String name, byte[] bytes -> uploaded[name] = new String(bytes, 'UTF-8') }
        script.metaClass.readFlag = { -> [armed: false, intent: 'disarm', runId: '7', fireAttempts: 4,
                                          manifest: [app: [classId: '178', file: 'f'], libraries: []]] + extraFlag }
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

    def "restoreLibrary self-heals a rejected in-place update via delete+recreate"() {
        given:
        String cached = 'library mcp.Foo source body aaaa'
        script.metaClass.readHubFileText = { String fn -> cached }
        script.metaClass.libraryVersion = { String id -> 7 }       // exists -> in-place attempted first
        int posts = 0
        script.metaClass.hubPostJson = { String p, String b ->
            posts++
            (posts == 1)
                ? [status: 200, data: '{"success":false,"message":"bundle-managed; update via the bundle"}']  // in-place rejected
                : [status: 200, data: '{"success":true,"id":222,"version":1}']                                 // recreate OK
        }
        script.metaClass.hubGet = { String p, Map q -> "" }        // delete GET
        script.metaClass.readLibrarySource = { String id -> cached }  // post-create byte-confirm matches

        expect:
        script.restoreLibrary('119', 'mcp-source-library-119.groovy') == true
    }

    def "restoreLibrary creates the library when it is absent (cascade-removed)"() {
        given:
        String cached = 'library mcp.Foo source body bbbb'
        script.metaClass.readHubFileText = { String fn -> cached }
        script.metaClass.libraryVersion = { String id -> null }     // not present -> straight to create
        script.metaClass.hubPostJson = { String p, String b -> [status: 200, data: '{"success":true,"id":333,"version":1}'] }
        script.metaClass.readLibrarySource = { String id -> cached }

        expect:
        script.restoreLibrary('119', 'mcp-source-library-119.groovy') == true
    }

    def "restorePackage drops the PR's stale bundle + library, keeps main's and untouched namespaces"() {
        given:
        def deletedBundles = []
        def deletedLibs = []
        // Hub holds main's mcp_smoke_test bundle + the PR's mcp_libraries bundle; main's McpSmokeTestLib +
        // the PR's McpRoomsLib + an unrelated 'other'-namespace library. The manifest's main sets list
        // only mcp_smoke_test + McpSmokeTestLib, so the PR's bundle/library are the stale ones to drop.
        script.metaClass.hubGet = { String p, Map q -> null }       // resolveLibraryId -> falls back to manifest id
        script.metaClass.adminListBundles = { Map a -> [source: 'hub_api', bundles: [
            [id: '1', name: 'mcp_smoke_test', namespace: 'mcp'],
            [id: '2', name: 'mcp_libraries', namespace: 'mcp']]] }
        script.metaClass.adminDeleteBundle = { Map a -> deletedBundles << a.bundleId; [success: true, verified: true] }
        script.metaClass.adminListLibraries = { Map a -> [source: 'hub_api', libraries: [
            [id: '10', name: 'McpSmokeTestLib', namespace: 'mcp'],
            [id: '11', name: 'McpRoomsLib', namespace: 'mcp'],
            [id: '12', name: 'Unrelated', namespace: 'other']]] }
        script.metaClass.adminDeleteItem = { Map a -> deletedLibs << a.id; [success: true] }
        script.metaClass.restoreLibrary = { String i, String f -> true }
        script.metaClass.restoreApp = { String c, String f -> true }
        Map written = null
        script.metaClass.readFlag = { -> [armed: true, deadline: '0', runId: '9', manifest: [
            app: [classId: '178', file: 'mcp-source-app-178.groovy'],
            libraries: [[namespace: 'mcp', name: 'McpSmokeTestLib', id: '10', file: 'mcp-source-library-10.groovy']],
            bundles: [[namespace: 'mcp', name: 'mcp_smoke_test']]]] }
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
        script.metaClass.adminListBundles = { Map a -> [source: 'hub_api', bundles: [[id: '2', name: 'mcp_libraries', namespace: 'mcp']]] }
        script.metaClass.adminDeleteBundle = { Map a -> deletedBundles << a.bundleId; [success: true] }
        script.metaClass.adminListLibraries = { Map a -> [source: 'hub_api', libraries: []] }
        script.metaClass.restoreLibrary = { String i, String f -> true }
        script.metaClass.restoreApp = { String c, String f -> true }
        Map written = null
        // No 'bundles' / no 'libraries' key in the manifest -> both cleanups must SKIP (don't blind-delete).
        script.metaClass.readFlag = { -> [armed: true, deadline: '0', runId: '9',
            manifest: [app: [classId: '178', file: 'mcp-source-app-178.groovy']]] }
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
        script.metaClass.adminListBundles = { Map a -> [source: 'hub_api', bundles: [
            [id: '1', name: 'mcp_smoke_test', namespace: 'mcp'],
            [id: '2', name: 'mcp_libraries', namespace: 'mcp']]] }
        script.metaClass.adminDeleteBundle = { Map a -> [success: false, error: 'hub refused'] }   // delete FAILS
        script.metaClass.adminListLibraries = { Map a -> [source: 'hub_api', libraries: []] }
        script.metaClass.restoreLibrary = { String i, String f -> true }
        script.metaClass.restoreApp = { String c, String f -> true }
        Map written = null
        script.metaClass.readFlag = { -> [armed: true, deadline: '0', runId: '9', manifest: [
            app: [classId: '178', file: 'app.groovy'],
            libraries: [[namespace: 'mcp', name: 'McpSmokeTestLib', id: '10', file: 'lib.groovy']],
            bundles: [[namespace: 'mcp', name: 'mcp_smoke_test']]]] }
        script.metaClass.writeFlag = { Map fl -> written = fl; true }

        when:
        script.checkDeadman()

        then:
        written?.restoreResult == 'restored'   // restoring main's libs+app is the guarantee; a failed cleanup delete must not abort it
    }

    def "restorePackage cleanup is best-effort: a THROWING stale-library delete does not abort the restore"() {
        given:
        script.metaClass.hubGet = { String p, Map q -> null }
        script.metaClass.adminListBundles = { Map a -> [source: 'hub_api', bundles: []] }
        script.metaClass.adminListLibraries = { Map a -> [source: 'hub_api', libraries: [
            [id: '10', name: 'McpSmokeTestLib', namespace: 'mcp'],
            [id: '11', name: 'McpRoomsLib', namespace: 'mcp']]] }
        script.metaClass.adminDeleteItem = { Map a -> throw new RuntimeException('boom') }   // delete THROWS
        script.metaClass.restoreLibrary = { String i, String f -> true }
        script.metaClass.restoreApp = { String c, String f -> true }
        Map written = null
        script.metaClass.readFlag = { -> [armed: true, deadline: '0', runId: '9', manifest: [
            app: [classId: '178', file: 'app.groovy'],
            libraries: [[namespace: 'mcp', name: 'McpSmokeTestLib', id: '10', file: 'lib.groovy']],
            bundles: [[namespace: 'mcp', name: 'mcp_smoke_test']]]] }
        script.metaClass.writeFlag = { Map fl -> written = fl; true }

        when:
        script.checkDeadman()

        then:
        written?.restoreResult == 'restored'   // a throwing cleanup delete is caught; restore still succeeds
    }

    @Unroll
    def "restoreLibrary(create) requires a readable byte-identical live source (#scenario)"() {
        given:
        String cached = 'library mcp.Foo source body cccc'
        script.metaClass.readHubFileText = { String fn -> cached }
        script.metaClass.libraryVersion = { String id -> null }     // not present -> straight to create
        script.metaClass.hubPostJson = { String p, String b -> [status: 200, data: '{"success":true,"id":444,"version":1}'] }
        script.metaClass.readLibrarySource = { String id -> live }   // post-create confirm read

        expect:
        script.restoreLibrary('119', 'mcp-source-library-119.groovy') == expected

        where:
        scenario                         | live                               || expected
        'unreadable -> NOT confirmed'    | null                               || false
        'byte-identical -> restored'     | 'library mcp.Foo source body cccc' || true
        'different live -> did not land' | 'a DIFFERENT source'               || false
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
        script.metaClass.adminListBundles = { Map a -> [source: 'hub_api_raw', bundles: []] }   // degraded -> skip
        script.metaClass.adminDeleteBundle = { Map a -> deletedBundles << a.bundleId; [success: true] }
        script.metaClass.adminListLibraries = { Map a -> [source: 'hub_api', libraries: []] }
        script.metaClass.restoreLibrary = { String i, String f -> true }
        script.metaClass.restoreApp = { String c, String f -> true }
        Map written = null
        script.metaClass.readFlag = { -> [armed: true, deadline: '0', runId: '9', manifest: [
            app: [classId: '178', file: 'app.groovy'],
            libraries: [],
            bundles: [[namespace: 'mcp', name: 'mcp_smoke_test']]]] }
        script.metaClass.writeFlag = { Map fl -> written = fl; true }

        when:
        script.checkDeadman()

        then:
        written?.restoreResult == 'restored'
        deletedBundles == []        // degraded list -> skip cleanup, never blind-delete
    }
}
