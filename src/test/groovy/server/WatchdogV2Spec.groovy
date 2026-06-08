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

    def setup() {
        File appFile = new File('e2e-deadman-watchdog-v2.groovy')
        def sandbox = new HubitatAppSandbox(appFile.getText('UTF-8'))
        script = sandbox.run(
            api: Mock(AppExecutor) {
                _ * getLog() >> new PermissiveLog()
                _ * getSettings() >> [hubSecurityEnabled: false, debugLogging: false]
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
}
