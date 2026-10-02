package server

import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.app.HubitatAppScript
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification
import spock.lang.Unroll
import support.PassThroughAppValidator
import support.PermissiveLog

// Coverage of the manual tools v3 retains from v2.
class WatchdogV3ManualSpec extends Specification {
    HubitatAppScript script
    List<List<Object>> runInCalls = []     // captures (delaySeconds, handler[, opts]) of every runIn
    Map atomicStateMap = [:]               // backs the script's atomicState (the single-flight latch)

    def setup() {
        File appFile = new File('e2e-deadman-watchdog-v3.groovy')
        def sandbox = new HubitatAppSandbox(appFile.getText('UTF-8'))
        script = sandbox.run(
            api: Mock(AppExecutor) {
                _ * getLog() >> new PermissiveLog()
                _ * getSettings() >> [hubSecurityEnabled: false, debugLogging: false]
                _ * getAtomicState() >> { atomicStateMap }
                // Real wall-clock: the single-flight latch computes (now() - purgeInFlightAt);
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

    /** An atomicState whose writes of ONE key vanish -- a hub dropping state writes under load. */
    private static class DroppingMap extends HashMap {
        String dropKey
        DroppingMap(String dropKey) { super(); this.dropKey = dropKey }
        @Override Object put(Object k, Object v) { k?.toString() == dropKey ? null : super.put(k, v) }
    }

    /** An atomicState that counts every read and write. */
    private static class CountingMap extends HashMap {
        int accesses = 0
        @Override Object get(Object k) { accesses++; super.get(k) }
        @Override Object put(Object k, Object v) { accesses++; super.put(k, v) }
        // A method, not a property: property access on a Map is itself a get().
        int total() { accesses }
    }

    /** A second script instance whose user settings differ from the shared one built in setup(). */
    private HubitatAppScript scriptWithSettings(Map extraSettings) {
        Map settings = [hubSecurityEnabled: false, debugLogging: false] + extraSettings
        def sandbox = new HubitatAppSandbox(new File('e2e-deadman-watchdog-v3.groovy').getText('UTF-8'))
        return sandbox.run(
            api: Mock(AppExecutor) {
                _ * getLog() >> new PermissiveLog()
                _ * getSettings() >> settings
                _ * getAtomicState() >> { atomicStateMap }
                _ * now() >> { System.currentTimeMillis() }
                _ * runIn(*_) >> { args -> runInCalls << (args as List) }
            },
            userSettingValues: settings,
            validator: new PassThroughAppValidator([
                Flags.DontValidatePreferences,
                Flags.DontValidateDefinition,
                Flags.DontRestrictGroovy,
                Flags.DontRunScript
            ])
        )
    }

    // ---- wedge auto-reboot: the one automatic action v3 keeps -----------------------------------

    private void wedge(long sinceOkMs = 300_000L, int streak = 12) {
        script.LOOPBACK.failStreak = streak
        script.LOOPBACK.lastOkAt = System.currentTimeMillis() - sinceOkMs
    }

    def "a wedged hub is auto-rebooted by the health tick"() {
        given: 'loopback dead: 8+ consecutive failures and no success for over four minutes'
        String posted = null
        script.metaClass.hubPostForm = { String p, Map b -> posted = p; [status: 200, data: 'ok'] }
        script.metaClass.probeLoopbackAlive = { -> false }
        wedge()

        when:
        script.checkHubHealth()

        then:
        posted == '/hub/reboot'
        atomicStateMap.lastAutoRebootAt != null
    }

    @Unroll
    def "a healthy hub is never auto-rebooted (#scenario)"() {
        given:
        String posted = null
        script.metaClass.hubPostForm = { String p, Map b -> posted = p; [status: 200, data: 'ok'] }
        wedge(sinceOkMs, streak)

        when:
        script.checkHubHealth()

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
        script.metaClass.hubPostForm = { String p, Map b -> posted = p; [status: 200, data: 'ok'] }
        script.metaClass.probeLoopbackAlive = { -> false }
        wedge()
        atomicStateMap.lastAutoRebootAt = System.currentTimeMillis() - 60_000L

        when:
        script.checkHubHealth()

        then:
        posted == null
    }

    def "a live probe that answers stands the auto-reboot down, even with a wedged-looking streak"() {
        given: 'a wedged-looking streak left over from before the hub recovered'
        String posted = null
        script.metaClass.hubPostForm = { String p, Map b -> posted = p; [status: 200, data: 'ok'] }
        script.metaClass.probeLoopbackAlive = { -> true }
        wedge(600_000L)

        when:
        script.checkHubHealth()

        then:
        posted == null
    }

    @Unroll
    def "a deliberate reboot/platform-update window suppresses the auto-reboot (#reason)"() {
        given:
        String posted = null
        script.metaClass.hubPostForm = { String p, Map b -> posted = p; [status: 200, data: 'ok'] }
        script.metaClass.probeLoopbackAlive = { -> false }
        wedge(900_000L, 20)
        atomicStateMap.expectedDownUntil = System.currentTimeMillis() + remainingMs

        when:
        script.checkHubHealth()

        then:
        posted == null

        where:
        reason                         | remainingMs
        'platform update, 20 min left' | 1_200_000L
        'operator reboot, 2 min left'  | 120_000L
    }

    def "once the expected-downtime window expires a genuine wedge is still caught"() {
        given:
        String posted = null
        script.metaClass.hubPostForm = { String p, Map b -> posted = p; [status: 200, data: 'ok'] }
        script.metaClass.probeLoopbackAlive = { -> false }
        wedge(900_000L, 20)
        atomicStateMap.expectedDownUntil = System.currentTimeMillis() - 1_000L

        when:
        script.checkHubHealth()

        then:
        posted == '/hub/reboot'
    }

    def "autoRebootOnWedge=false stops the auto-reboot before any POST, however wedged the hub looks"() {
        given:
        def optedOut = scriptWithSettings([autoRebootOnWedge: false])
        optedOut.LOOPBACK.failStreak = 99
        optedOut.LOOPBACK.lastOkAt = System.currentTimeMillis() - 3_600_000L
        def posts = []
        optedOut.metaClass.hubPostForm = { String path, Map body -> posts << path; [status: 200, data: ''] }
        optedOut.metaClass.probeLoopbackAlive = { -> false }

        when:
        optedOut.checkHubHealth()

        then:
        posts == []
        atomicStateMap.lastAutoRebootAt == null
    }

    def "two overlapping health ticks issue exactly one reboot POST"() {
        given: "a wedged hub whose reboot POST is slow enough for the second tick to overlap"
        def posts = new java.util.concurrent.atomic.AtomicInteger(0)
        def pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        script.metaClass.probeLoopbackAlive = { -> false }
        script.metaClass.hubPostForm = { String p, Map b -> posts.incrementAndGet(); Thread.sleep(300); [status: 200, data: 'ok'] }
        wedge(900_000L, 20)

        when:
        def futures = (1..2).collect { pool.submit({ script.checkHubHealth() } as Runnable) }
        futures.each { it.get(5, java.util.concurrent.TimeUnit.SECONDS) }

        then:
        posts.get() == 1
        atomicStateMap.lastAutoRebootAt != null

        cleanup:
        pool.shutdownNow()
        pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
    }

    def "a watchdog that has never had a successful loopback call can still detect a wedge"() {
        given: "no lastOkAt at all -- the hub was wedged before this code loaded"
        script.LOOPBACK.failStreak = 12
        script.LOOPBACK.lastOkAt = null
        script.LOOPBACK.streakStartedAt = System.currentTimeMillis() - 300_000L
        String posted = null
        script.metaClass.hubPostForm = { String p, Map b -> posted = p; [status: 200, data: 'ok'] }
        script.metaClass.probeLoopbackAlive = { -> false }

        when:
        script.checkHubHealth()

        then: "the streak-start timestamp is the baseline, so the escape still fires"
        posted == '/hub/reboot'
    }

    def "every tick probes the loopback before the wedge decision, wedged or not"() {
        given: "healthy counters -- nothing below the probe would run on its own"
        int probes = 0
        script.metaClass.probeLoopbackAlive = { -> probes++; true }
        script.LOOPBACK.failStreak = 0

        when:
        script.checkHubHealth()

        then: "the probe ran once on an idle tick, so a latched streak can clear without watchdog traffic"
        probes == 1
    }

    def "an auto-reboot is vetoed under the lock when a concurrent probe cleared the streak"() {
        given:
        wedge(300_000L, 8)
        int probes = 0
        script.metaClass.probeLoopbackAlive = { ->
            probes++
            // The 2nd call is the reboot path's own probe; a concurrent success lands with it.
            if (probes == 2) script.LOOPBACK.failStreak = 0
            false
        }
        def posts = []
        script.metaClass.hubPostForm = { String path, Map body -> posts << path; [status: 200, data: ''] }

        when:
        script.checkHubHealth()

        then:
        probes == 2
        posts == []
        atomicStateMap.lastAutoRebootAt == null
    }

    def "an auto-reboot is vetoed under the lock when a platform update was accepted meanwhile"() {
        given:
        wedge(300_000L, 8)
        int probes = 0
        script.metaClass.probeLoopbackAlive = { ->
            probes++
            if (probes == 2) atomicStateMap.expectedDownUntil = System.currentTimeMillis() + 1500000L
            false
        }
        def posts = []
        script.metaClass.hubPostForm = { String path, Map body -> posts << path; [status: 200, data: ''] }

        when:
        script.checkHubHealth()

        then:
        posts == []
        atomicStateMap.lastAutoRebootAt == null
    }

    def "an ambiguous auto-reboot POST keeps the rate limit, an answered rejection frees it"() {
        given:
        script.metaClass.probeLoopbackAlive = { -> false }
        script.metaClass.hubPostForm = { String p, Map b -> [status: status, data: null] }
        wedge()

        when:
        script.checkHubHealth()

        then:
        (atomicStateMap.lastAutoRebootAt != null) == held

        where:
        status | held
        null   | true
        500    | false
    }

    def "the watchdog refuses to disable or force-delete its own instance"() {
        given:
        script.metaClass.isOwnInstance = { id -> id.toString() == '48028' }
        def calls = []
        script.metaClass.hubPostJson = { String p, String b -> calls << p; [status: 200, data: ''] }
        script.metaClass.hubGetStatus = { String p, Map q -> calls << p; [status: 302] }

        expect:
        !script.adminSetAppDisabled([appId: '48028', disable: true, confirm: true]).success
        !script.adminForceDeleteInstalledApp([id: '48028', confirm: true]).success
        calls == []
    }

    // ---- self-update guard: the MCP server is the watchdog's only repair path -------------------

    private static final String V3_SOURCE = 'definition(\n    name: "E2E Dead-Man Watchdog v3",\n    namespace: "mcp")\n'

    @Unroll
    def "the watchdog's own code is replaced only while the MCP endpoint answers (available=#available)"() {
        given:
        def posts = []
        script.metaClass.hubGet = { String p, Map q -> groovy.json.JsonOutput.toJson([source: V3_SOURCE, version: 4]) }
        script.metaClass.hubPostForm = { String p, Map b -> posts << p; [status: 200, data: '{"status":"success"}'] }
        script.metaClass.peerEndpointStatus = { ->
            available ? [available: true, appId: '38'] : [available: false, reason: 'the MCP server endpoint did not answer tools/list']
        }

        when:
        def res = script.adminUpdateApp([appId: '1925', source: 'new source', confirm: true])

        then:
        res.success == available
        posts == (available ? ['/app/ajax/update'] : [])
        available || res.error.contains('did not answer tools/list')
        available || atomicStateMap.lastSelfDeploy == null

        where:
        available << [true, false]
    }

    def "another app's code is updated without consulting the MCP endpoint"() {
        given:
        def posts = []
        script.metaClass.hubGet = { String p, Map q ->
            groovy.json.JsonOutput.toJson([source: 'definition(name: "MCP Rule Server", namespace: "mcp")', version: 4])
        }
        script.metaClass.hubPostForm = { String p, Map b -> posts << p; [status: 200, data: '{"status":"success"}'] }
        script.metaClass.peerEndpointStatus = { -> throw new AssertionError('the peer check is only for the watchdog code') }

        expect:
        script.adminUpdateApp([appId: '178', source: 'new source', confirm: true]).success
        posts == ['/app/ajax/update']
    }

    @Unroll
    def "the peer check reports #scenario"() {
        given:
        script.metaClass.hubGet = { String p, Map q ->
            if (p == '/hub2/appsList') return groovy.json.JsonOutput.toJson([apps: instances.collect { [data: it, children: []] }])
            if (p.startsWith('/installedapp/statusJson/')) return groovy.json.JsonOutput.toJson([appState: appState])
            null
        }
        script.metaClass.peerPost = { String id, String token, String json -> answer }

        when:
        def peer = script.peerEndpointStatus()

        then:
        peer.available == available
        available || peer.reason.contains(reason)

        where:
        scenario                               | instances                                                                   | appState                             | answer                                                 | available | reason
        'a serving MCP server, gateway catalog' | [[id: 38, type: 'MCP Rule Server']]                                         | [[name: 'accessToken', value: 't']] | '{"result":{"tools":[{"name":"hub_manage_code"}]}}'   | true      | ''
        'a serving MCP server, flat catalog'    | [[id: 38, type: 'MCP Rule Server']]                                         | [[name: 'accessToken', value: 't']] | '{"result":{"tools":[{"name":"hub_update_app"}]}}'    | true      | ''
        'no MCP server instance'                | [[id: 9, type: 'Rule Machine']]                                             | []                                   | null                                                   | false     | 'found 0'
        'two MCP server instances'              | [[id: 38, type: 'MCP Rule Server'], [id: 39, type: 'MCP Rule Server']]      | [[name: 'accessToken', value: 't']] | null                                                   | false     | 'found 2'
        'a disabled MCP server'                 | [[id: 38, type: 'MCP Rule Server', disabled: true]]                         | [[name: 'accessToken', value: 't']] | null                                                   | false     | 'disabled'
        'an unreadable access token'            | [[id: 38, type: 'MCP Rule Server']]                                         | []                                   | null                                                   | false     | 'access token'
        'an endpoint that does not answer'      | [[id: 38, type: 'MCP Rule Server']]                                         | [[name: 'accessToken', value: 't']] | null                                                   | false     | 'did not answer'
        'an endpoint returning an error'        | [[id: 38, type: 'MCP Rule Server']]                                         | [[name: 'accessToken', value: 't']] | '{"error":{"code":-32603}}'                            | false     | 'did not answer'
        'a catalog with no code-update tool'    | [[id: 38, type: 'MCP Rule Server']]                                         | [[name: 'accessToken', value: 't']] | '{"result":{"tools":[{"name":"hub_list_devices"}]}}'  | false     | 'no code-update tool'
    }

    def "the watchdog deletes no app whose code it cannot read"() {
        given: 'the read that would rule out the watchdog\'s own code fails'
        def deletes = []
        script.metaClass.hubGet = { String p, Map q ->
            if (p == '/app/ajax/code') return null
            deletes << p
            '{"status":"true"}'
        }

        when:
        def res = script.adminDeleteItem([type: 'app', id: '77', confirm: true])

        then:
        res.success == false
        res.error.contains('nothing was deleted')
        deletes == []
    }

    def "the watchdog refuses to delete its own code class but deletes another"() {
        given:
        def deletes = []
        script.metaClass.hubGet = { String p, Map q ->
            if (p == '/app/ajax/code') return groovy.json.JsonOutput.toJson([source: q.id == '1925' ? V3_SOURCE : 'definition(name: "Other")', version: 4])
            deletes << p
            '{"status":"true"}'
        }

        expect:
        !script.adminDeleteItem([type: 'app', id: '1925', confirm: true]).success
        deletes == []
        script.adminDeleteItem([type: 'app', id: '77', confirm: true]).success
        deletes == ['/app/edit/deleteJsonSafe/77']
    }

    def "hub_get_info runs the peer check only when asked"() {
        given:
        int checks = 0
        script.metaClass.hubGet = { String p, Map q -> null }
        script.metaClass.peerEndpointStatus = { -> checks++; [available: true, appId: '38'] }

        when:
        def plain = script.adminGetInfo([:])
        def asked = script.adminGetInfo([peer: true])

        then:
        plain.peerEndpoint == null
        asked.peerEndpoint.available
        checks == 1
    }

    def "the reboot POST gets a SHORT timeout, every other form POST the long one"() {
        // The wedge escape's whole value is acting while the web stack is dying: a reboot POST
        // that inherits the 420s form timeout holds the tick for seven minutes and the escape
        // never gets to report. Stubbing hubPostForm in the reboot specs hides this entirely.
        expect:
        script.hubPostTimeoutSec('/hub/reboot') == 20
        script.hubPostTimeoutSec('/installedapp/btn') == 420
        script.hubPostTimeoutSec('/hub/cloud/updatePlatform') == 420
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

    // ---- force-delete an installed-app instance (RM rule), as the fixture purge does ----

    def "adminForceDeleteInstalledApp GETs /installedapp/forcedelete/<id>/quiet then verifies gone via /installedapp/json"() {
        given:
        // The forcedelete endpoint answers SUCCESS with a 302 redirect to the apps list; hubGetStatus
        // captures that status off the thrown response (followRedirects:false), so the tool sees a 3xx.
        // The 302 alone is not trusted: a follow-up /installedapp/json existence read (404 = gone)
        // must confirm, because the fixture purge can fire these mid-recompile, where commits strand.
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
        // hub -- the tool must NOT report success then, so the fixture purge reports the failure.
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

    private static Map mcpConfig(boolean enabled) {
        [app: [id: 194, version: 3, appType: [namespace: 'mcp', name: 'MCP Rule Server']],
         configPage: [name: 'mainPage'],
         settings: [enableDeveloperMode: enabled.toString(), enableCustomRuleEngine: 'false']]
    }

    @Unroll
    def "Developer Mode bootstrap verifies the setting after POST (#scenario)"() {
        given:
        def reads = []
        def posts = []
        script.metaClass.hubGet = { String path, Map query ->
            reads << path
            groovy.json.JsonOutput.toJson(mcpConfig(reads.size() > 1 && landed))
        }
        script.metaClass.hubPostForm = { String path, Map body ->
            posts << [path, body]
            [status: postStatus, data: '']
        }

        when:
        def result = script.executeAdminTool('hub_set_mcp_developer_mode',
            [appId: '0194', enabled: true, confirm: true])

        then:
        result.success == landed
        reads == ['/installedapp/configure/json/194', '/installedapp/configure/json/194']
        posts == [['/installedapp/update/json', [id: '194', version: '3',
            'settings[enableDeveloperMode]': 'true', 'enableDeveloperMode.type': 'bool',
            currentPage: 'mainPage', pageBreadcrumbs: '[]', formAction: 'update']]]

        where:
        scenario                       | postStatus | landed
        'normal write'                 | 200        | true
        'lost response but committed'  | null       | true
        'HTTP success without change'  | 200        | false
        'failed write'                 | 500        | false
    }

    def "Developer Mode bootstrap is a no-op when already enabled"() {
        given:
        def posts = []
        script.metaClass.hubGet = { String path, Map query -> groovy.json.JsonOutput.toJson(mcpConfig(true)) }
        script.metaClass.hubPostForm = { String path, Map body -> posts << body; [:] }

        when:
        def result = script.adminSetMcpDeveloperMode([appId: '194', enabled: true, confirm: true])

        then:
        result.success && !result.changed
        posts.empty
    }

    @Unroll
    def "Developer Mode bootstrap refuses unverified app identity (#scenario)"() {
        given:
        def config = mcpConfig(false)
        mutate(config)
        def posts = []
        script.metaClass.hubGet = { String path, Map query -> groovy.json.JsonOutput.toJson(config) }
        script.metaClass.hubPostForm = { String path, Map body -> posts << body; [:] }

        expect:
        !script.adminSetMcpDeveloperMode([appId: '194', enabled: true, confirm: true]).success
        posts.empty

        where:
        scenario             | mutate
        'different instance' | { c -> c.app.id = 195 }
        'different app'      | { c -> c.app.appType.name = 'Unrelated app' }
        'wrong namespace'    | { c -> c.app.appType.namespace = 'other' }
        'missing version'    | { c -> c.app.remove('version') }
        'wrong page'         | { c -> c.configPage.name = 'otherPage' }
        'missing identity'   | { c -> c.remove('app') }
    }

    @Unroll
    def "Developer Mode bootstrap requires a confirmed enable and valid instance (#args)"() {
        given:
        def reads = []
        script.metaClass.hubGet = { String path, Map query -> reads << path; null }

        when:
        script.adminSetMcpDeveloperMode(args)

        then:
        thrown(IllegalArgumentException)
        reads.empty

        where:
        args << [[appId: '194', enabled: true],
                 [appId: '194', enabled: false, confirm: true],
                 [appId: '0', enabled: true, confirm: true],
                 [appId: 'abc', enabled: true, confirm: true]]
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

    // ---- bundle tools mirrored into the watchdog ----

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

    def "adminGetJobs reads /logs/json and maps a scheduled job by its method name"() {
        given:
        // hub_get_jobs reads the verified /logs/json shape (jobs / runningJobs / hubCommands, keyed by
        // methodName) -- NOT the non-existent /hub/scheduledJobs/json, which 404s.
        String requestedPath = null
        script.metaClass.hubGet = { String p, Map q ->
            requestedPath = p
            '{"uptime":"1d","jobs":[{"name":"checkHubHealth","methodName":"checkHubHealth","recurring":true}],"runningJobs":[],"hubCommands":[]}'
        }

        when:
        def r = script.adminGetJobs([:])

        then: 'the schedule read goes to /logs/json, never /hub/scheduledJobs/json'
        requestedPath == '/logs/json'

        and: 'the health tick is surfaced via job.methodName, so a caller can confirm it is scheduled'
        r.scheduledJobs.count == 1
        r.scheduledJobs.jobs[0].method == 'checkHubHealth'
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
        // A sweep that is genuinely running owns all three keys -- the claim is what says someone
        // is still working, and a bare timestamp is the trailing edge of a sweep that has finished.
        atomicStateMap.purgeInFlightAt = System.currentTimeMillis() - 30_000L
        atomicStateMap.purgeClaim = 'purge-running'
        atomicStateMap.purgeClaimPrefix = 'BAT_E2E_'

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
        atomicStateMap.purgeClaim = 'purge-killed'
        atomicStateMap.purgeClaimPrefix = 'BAT_E2E_'

        when:
        def res = script.adminPurgeE2eArtifacts([confirm: true])

        then: 'the stale latch is overridden and the sweep runs'
        enumerations == 1
        res.inFlight != true
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

    def "a successful loopback call clears the wedge streak"() {
        given:
        script.LOOPBACK.failStreak = 12

        when:
        script.noteLoopback(true)

        then:
        script.LOOPBACK.failStreak == 0
        script.LOOPBACK.lastOkAt != null
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
        res.error?.contains(errFragment)
        res.note?.contains(noteFragment)

        and: "only an unanswered POST is ambiguous -- a status the hub returned proves it did not reboot"
        (res.ambiguous == true) == (st == null)

        where:
        scenario                        | st   | errFragment  | noteFragment
        'transport failure, no status'  | null | 'no response' | 'physical power cycle'
        'hub refused the reboot'        | 500  | '500'         | 'The hub answered'
        'not found'                     | 404  | '404'         | 'The hub answered'
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

    def "the tools that reach the open internet are the ones that fetch by URL"() {
        given: "openWorldHint is an accuracy statement: the hub is the closed-world system"
        def defs = script.getAdminToolDefinitions()

        expect: "only the importUrl/zip-fetch/platform-download tools leave the hub"
        (defs.findAll { it.annotations?.openWorldHint == true }*.name as Set) ==
            ['hub_update_package', 'hub_update_app', 'hub_create_library', 'hub_update_library',
             'hub_update_platform', 'hub_install_bundle'] as Set
    }

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
        script.httpStatusOf(new V3FakeHttpException(code)) == code

        where:
        code << [404, 500, 302]
    }

    def "a real transport failure carries no status"() {
        expect: "only this shape is a dead web stack"
        script.httpStatusOf(new RuntimeException('Read timed out')) == null
    }

    def "the wedge streak advances only on a status-less failure"() {
        given:
        script.LOOPBACK.failStreak = 3

        when: 'the caller passes the answered/unanswered decision noteLoopback is given'
        script.noteLoopback(answered)

        then:
        script.LOOPBACK.failStreak == expected

        where:
        answered | expected
        true     | 0
        false    | 4
    }

    def "a fresh failure streak stamps its own baseline, and a success clears it"() {
        given: 'explicitly no prior streak -- the baseline is only stamped on the FIRST failure'
        script.LOOPBACK.failStreak = 0
        script.LOOPBACK.streakStartedAt = null
        script.LOOPBACK.lastOkAt = null

        when:
        script.noteLoopback(false)

        then: 'this is what gives hubLooksWedged a baseline when there has never been a success'
        script.LOOPBACK.failStreak == 1
        script.LOOPBACK.streakStartedAt != null

        when: 'the hub answers again'
        script.noteLoopback(true)

        then: 'the streak and its baseline are both released'
        script.LOOPBACK.failStreak == 0
        script.LOOPBACK.streakStartedAt == null
        script.LOOPBACK.lastOkAt != null
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

    def "loopback bookkeeping and a healthy health tick never touch atomicState"() {
        given: "every atomicState access is a hub DB round trip, and these run on every call and every minute"
        def counting = new CountingMap()
        atomicStateMap = counting
        script.metaClass.hubGetStatus = { String p, Map q, int t -> script.noteLoopback(true); [status: 200] }

        when:
        3.times { script.noteLoopback(false) }
        script.noteLoopback(true)
        script.checkHubHealth()

        then:
        counting.total() == 0
        script.LOOPBACK.failStreak == 0
        script.LOOPBACK.lastOkAt != null
    }


    // ---- contracts and latch edge cases -------------------------------------------------------

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

        and: "hub_reboot advertises the force override -- without it the platform-update refusal has no escape a client can find"
        defs.find { it.name == 'hub_reboot' }.inputSchema.properties.containsKey('force')
        defs.find { it.name == 'hub_reboot' }.annotations.idempotentHint == false
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
        script.metaClass.purgeE2eArtifactsLocked = { String prefix, String claim = null ->
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

    def "hub_update_platform with no response reports UNKNOWN and holds the escape down"() {
        given: "hubGet returns null for a transport failure, a dropped response AND an empty 200 body alike"
        script.metaClass.hubGet = { String p, Map q -> p.endsWith('checkForUpdate') ? '{"ok":true}' : null }

        when:
        def res = script.adminUpdatePlatform([confirm: true])

        then: "whether the hub accepted it is unknowable here, and a reboot into a firmware install is the worse error"
        res.success == false
        res.updateMayHaveStarted == true
        res.error?.contains('UNKNOWN')
        (atomicStateMap.expectedDownUntil as Long) > System.currentTimeMillis()
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

    def "a REJECTED reboot POST clears the downtime window it had stamped"() {
        given: "the hub answered, so it certainly did not reboot"
        script.metaClass.hubPostForm = { String p, Map b -> [status: 500, data: null] }

        when:
        def res = script.adminRebootHub([confirm: true])

        then: "otherwise the escape would stand down for 10 min on a reboot that never happened"
        res.success == false
        res.ambiguous == null
        atomicStateMap.expectedDownUntil == null
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

    def "a rejected reboot POST restores the downtime window it found, rather than clearing it"() {
        given: "a platform update already has a window open; the hub then answers the reboot"
        long existing = System.currentTimeMillis() + 900_000L
        atomicStateMap.expectedDownUntil = existing
        script.metaClass.hubPostForm = { String p, Map b -> [status: 500, data: null] }

        when:
        def res = script.adminRebootHub([confirm: true])

        then: "the platform update's suppression survives a reboot attempt that did not land"
        res.success == false
        atomicStateMap.expectedDownUntil == existing
    }

    def "the failure streak saturates at the wedge threshold (streak #streak)"() {
        given:
        script.LOOPBACK.failStreak = streak
        script.LOOPBACK.streakStartedAt = System.currentTimeMillis() - 1_000L

        when:
        script.noteLoopback(false)

        then: "below the threshold it still counts; at or past it the count is left alone"
        script.LOOPBACK.failStreak == expected

        where:
        streak | expected
        7      | 8
        8      | 8
        40     | 40
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

    def "a purge sweep renews its claim under the lock before every delete and stops once the claim is lost"() {
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

    def "a platform update claims the downtime window BEFORE its request"() {
        given:
        script.metaClass.hubGet = { String path, Map q = [:], Integer t = null ->
            path == "/hub/cloud/checkForUpdate" ? '{"status":"ok"}' : (path == "/hub/cloud/updatePlatform" ? '{"ok":true}' : null)
        }

        when:
        def r = script.adminUpdatePlatform([confirm: true])

        then:
        r.success == true
        (atomicStateMap.expectedDownUntil as Long) > System.currentTimeMillis()
        atomicStateMap.expectedDownReason == 'hub_update_platform'
    }

    def "a platform update is NOT requested when its downtime window could not be persisted"() {
        given: "the hub drops writes of the window key, so the stamp cannot be made durable"
        atomicStateMap = new DroppingMap('expectedDownUntil')
        def paths = []
        script.metaClass.hubGet = { String path, Map q = [:], Integer t = null ->
            paths << path
            path == "/hub/cloud/checkForUpdate" ? '{"status":"ok"}' : '{"ok":true}'
        }

        when:
        def r = script.adminUpdatePlatform([confirm: true])

        then: "the request never went out because the manual reboot guard could not be persisted"
        r.success == false
        r.error.contains("was not requested")
        !paths.contains("/hub/cloud/updatePlatform")
    }

    def "a REJECTED reboot POST hands back the window it borrowed WITH its reason, so a platform update still refuses later reboots"() {
        given: "a platform-update window is open; a forced reboot POST is then rejected by the hub"
        long updateUntil = System.currentTimeMillis() + 1400000L
        atomicStateMap.expectedDownUntil = updateUntil
        atomicStateMap.expectedDownReason = 'hub_update_platform'
        script.metaClass.hubPostForm = { String path, Map body -> [status: 500, data: 'nope'] }

        when:
        def failed = script.adminRebootHub([confirm: true, force: true])
        def afterwards = script.adminRebootHub([confirm: true])

        then: "the hub answered, so nothing rebooted: the update's window AND its reason are back"
        failed.success == false
        failed.ambiguous == null
        (atomicStateMap.expectedDownUntil as Long) == updateUntil
        atomicStateMap.expectedDownReason == 'hub_update_platform'
        afterwards.refused == true
    }

    def "an UNANSWERED reboot POST keeps its window -- a hub that accepted the reboot goes down before answering"() {
        given:
        script.metaClass.hubPostForm = { String path, Map body -> [status: null, data: null] }

        when:
        def r = script.adminRebootHub([confirm: true])

        then: "reported ambiguous, and the window stands so the wedge escape does not reboot a hub that is already rebooting"
        r.success == false
        r.ambiguous == true
        (atomicStateMap.expectedDownUntil as Long) > System.currentTimeMillis()
        atomicStateMap.expectedDownReason == 'hub_reboot'
    }

    def "a fresh purge stamp that no claim owns is not treated as cover for this sweep"() {
        given: "the timestamp of a sweep that has finished and cleared its claim"
        atomicStateMap.purgeInFlightAt = System.currentTimeMillis()
        atomicStateMap.purgeClaim = null
        atomicStateMap.purgeClaimPrefix = null
        def swept = false
        script.metaClass.purgeE2eArtifactsLocked = { String prefix, String claim = null -> swept = true; [success: true, prefix: prefix, deleted: [], failed: []] }

        when:
        def r = script.adminPurgeE2eArtifacts([confirm: true, prefix: 'BAT_E2E_'])

        then: "an unowned stamp covers nothing -- this call sweeps instead of reporting itself done"
        swept == true
        r.inFlight != true
    }

    def "hub_reboot is refused while an accepted platform update is installing, unless forced"() {
        given: "the update window claimed by hub_update_platform"
        atomicStateMap.expectedDownUntil = System.currentTimeMillis() + 1400000L
        atomicStateMap.expectedDownReason = 'hub_update_platform'
        def posts = []
        script.metaClass.hubPostForm = { String path, Map body -> posts << path; [status: 200, data: ''] }

        when:
        def refused = script.adminRebootHub([confirm: true])
        def forced = script.adminRebootHub([confirm: true, force: true])

        then:
        refused.success == false
        refused.refused == true
        refused.error.contains('platform update')
        forced.success == true
        posts == ['/hub/reboot']
    }

    def "hub_get_info exposes the wedge detector's state, so an auto-reboot can be attributed after the log buffer has rolled"() {
        given: "a stamped auto-reboot and a short streak, on a hub whose loopback answers"
        script.metaClass.hubGet = { String path, Map q = [:], Integer t = null -> path == "/hub/advanced/freeOSMemory" ? "123456" : null }
        atomicStateMap.lastAutoRebootAt = 1000L
        script.LOOPBACK.failStreak = 3
        script.LOOPBACK.lastOkAt = 2000L

        when:
        def info = script.adminGetInfo([:])

        then: "the stamp, its age, the streak and the wedge verdict ride hub_get_info"
        info.wedge.lastAutoRebootAt == 1000L
        (info.wedge.lastAutoRebootAgeMs as Long) > 0L
        info.wedge.loopbackFailStreak == 3
        info.wedge.loopbackLastOkAt == 2000L
        info.wedge.looksWedged == false
        info.watchdogEndpoint == true
    }

    def "a variable delete renews the claim before every wizard click and stops mid-variable once the claim is lost"() {
        given: "no apps to purge, one variable; the first wizard click hands the claim to a newer sweep"
        script.metaClass.hubGet = { String path, Map q = [:], Integer t = null ->
            path == "/hub2/appsList" ? groovy.json.JsonOutput.toJson([apps: []]) : null
        }
        script.metaClass.getAllGlobalVars = { -> [BAT_E2E_v1: [type: 'string', value: 'x']] }
        script.metaClass.getGlobalVar = { String n -> [type: 'string', value: 'x'] }   // never deleted
        // The Hub Variables app id resolves through the public HTTP seam (the resolver itself is private).
        script.metaClass.hubGetStatus = { String path, Map q ->
            path == "/installedapp/direct/hubVariables" ? [status: 302, location: "/installedapp/configure/42", data: null]
                                                        : [status: 200, location: null, data: null]
        }
        def clicks = []
        script.metaClass.hubPostForm = { String path, Map body ->
            clicks << body.name
            atomicStateMap.purgeClaim = 'purge-newer'
            [status: 200, data: '']
        }
        atomicStateMap.purgeClaim = 'purge-mine'
        atomicStateMap.purgeInFlightAt = 1L

        when:
        def res = script.purgeE2eArtifactsLocked("BAT_E2E_", 'purge-mine')

        then: "only the deleteGV click ran -- the delConfirm click and the second attempt did not, and the loss is reported"
        clicks == ['BAT_E2E_v1']
        (atomicStateMap.purgeInFlightAt as Long) > 1L
        res.variablesFailed.any { it.name == 'BAT_E2E_v1' && it.error.contains('claim lost') && it.error.contains('delConfirm') }
        res.variablesDeleted == []
    }

}

class V3FakeHttpException extends RuntimeException {
    // Hubitat's httpGet/httpPost throw an exception carrying the response on a 4xx/5xx; the
    // watchdog reads e.response.status off it. A metaClass-patched RuntimeException does not
    // present the property to the script, so model it as a real type.
    def response
    V3FakeHttpException(Integer status) {
        super("HTTP ${status}")
        this.response = [status: status]
    }
}
