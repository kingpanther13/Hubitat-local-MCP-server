package server

import me.biocomp.hubitat_ci.api.app_api.AppExecutor
import me.biocomp.hubitat_ci.app.HubitatAppSandbox
import me.biocomp.hubitat_ci.app.HubitatAppScript
import me.biocomp.hubitat_ci.validation.Flags
import spock.lang.Specification
import support.PassThroughAppValidator
import support.PermissiveLog

// The purge's device / room / code / file leg: everything e2e teardown used to sweep through the MCP app.
class WatchdogV3PurgeFixturesSpec extends Specification {
    HubitatAppScript script
    Map atomicStateMap = [:]
    List<String> gets = []
    List<String> deletedFiles = []
    List<String> postedRooms = []
    List rooms = []

    def setup() {
        def sandbox = new HubitatAppSandbox(new File('e2e-deadman-watchdog-v3.groovy').getText('UTF-8'))
        script = sandbox.run(
            api: Mock(AppExecutor) {
                _ * getLog() >> new PermissiveLog()
                _ * getSettings() >> [hubSecurityEnabled: false, debugLogging: false]
                _ * getAtomicState() >> { atomicStateMap }
                _ * now() >> { System.currentTimeMillis() }
            },
            userSettingValues: [hubSecurityEnabled: false, debugLogging: false],
            validator: new PassThroughAppValidator([
                Flags.DontValidatePreferences, Flags.DontValidateDefinition,
                Flags.DontRestrictGroovy, Flags.DontRunScript
            ])
        )
        rooms = [[id: 7, name: 'BAT_E2E_Room'], [id: 8, name: 'BAT_E2E_KEEP_Room'], [id: 9, name: 'Kitchen']]
        script.metaClass.getRooms = { -> rooms }
        script.metaClass.hubPostJson = { String path, String body ->
            postedRooms << path
            rooms = rooms.findAll { "/room/delete/${it.id}".toString() != path }
            [status: 200, data: '{}']
        }
        script.metaClass.deleteHubFile = { String name -> deletedFiles << name; true }
        script.metaClass.adminListBundles = { Map a -> [source: 'hub_api', bundles: [[id: 3, name: 'Throwaway', namespace: 'mcptest'],
                                                                                     [id: 4, name: 'Real', namespace: 'someone']]] }
        script.metaClass.adminDeleteBundle = { Map a -> [success: a.bundleId == '3'] }
        script.metaClass.adminDeleteItem = { Map a -> [success: true] }
    }

    private void hub(Map<String, Object> bodies) {
        script.metaClass.hubGet = { String path, Map q ->
            gets << path
            def body = bodies[path]
            body == null ? (path.startsWith('/device/forceDelete/') ? 'ok' : null) : groovy.json.JsonOutput.toJson(body)
        }
    }

    def "sweeps prefixed devices, rooms, files and the mcptest code, and leaves everything else"() {
        given:
        hub(['/hub2/devicesList': [devices: [
                [data: [id: 11, name: 'BAT_E2E_Switch'], children: [[data: [id: 12, name: 'BAT_E2E_Child']]]],
                [data: [id: 13, name: 'BAT_E2E_KEEP_Switch']], [data: [id: 14, name: 'Porch Light']]]],
             '/hub2/userAppTypes': [[id: 21, name: 'Deadman Test Target', namespace: 'mcptest'],
                                    [id: 22, name: 'Deadman Test Target', namespace: 'mine'],
                                    [id: 23, name: 'Something Else', namespace: 'mcptest']],
             '/hub2/userDeviceTypes': [[id: 31, name: 'Deadman Test Target Driver', namespace: 'mcptest']],
             '/hub/fileManager/json': [[name: 'BAT_E2E_note.txt'], [name: 'e2e-deferred_backup_1.json'],
                                       [name: 'e2e-config.json'], [name: 'mcp-rm-backup-5-x.json'], [name: 'mine.txt'],
                                       [name: 'BAT_E2E_KEEP_baseline.json']]])
        def codeDeletes = []
        script.metaClass.adminDeleteItem = { Map a -> codeDeletes << "${a.type}:${a.id}".toString(); [success: true] }

        when:
        def r = script.purgeOtherFixturesLocked('BAT_E2E_', null)

        then:
        r.failed == []
        gets.findAll { it.startsWith('/device/forceDelete/') } == ['/device/forceDelete/11/yes', '/device/forceDelete/12/yes']
        postedRooms == ['/room/delete/7']
        codeDeletes == ['app:21', 'driver:31']
        deletedFiles == ['BAT_E2E_note.txt', 'e2e-deferred_backup_1.json']

        and: 'the MCP app deletes its own indexed backups, so the watchdog never does'
        !deletedFiles.contains('mcp-rm-backup-5-x.json')
        r.deleted*.kind.countBy { it } == [device: 2, room: 1, 'app code': 1, 'driver code': 1, bundle: 1, file: 2]
    }

    def "an unreadable listing is reported as a failure, never as nothing to purge"() {
        given:
        hub(['/hub/fileManager/json': [[name: 'BAT_E2E_note.txt']]])
        script.metaClass.adminListBundles = { Map a -> [source: 'unavailable', bundles: []] }

        when:
        def r = script.purgeOtherFixturesLocked('BAT_E2E_', null)

        then:
        r.failed*.kind == ['device', 'app code', 'driver code', 'bundle']
        r.failed[0].error.contains('/hub2/devicesList')

        and: 'the readable kinds are still swept'
        deletedFiles == ['BAT_E2E_note.txt']
        postedRooms == ['/room/delete/7']
    }

    def "a room still listed after the delete is a failure, not a success"() {
        given:
        hub([:])
        script.metaClass.hubPostJson = { String path, String body -> [status: 200, data: '{}'] }

        when:
        def r = script.purgeOtherFixturesLocked('BAT_E2E_', null)

        then:
        r.failed.find { it.kind == 'room' }?.error == 'room still listed after delete'
    }

    def "the app sweep also reaps stranded Button Controllers and throwaway app instances"() {
        given:
        def forced = []
        hub(['/hub2/appsList': [apps: [
                [data: [id: 40, name: 'Button Controllers', type: 'Button Controllers'], children: [
                    [data: [id: 41, name: 'E2E_PERM_Button', type: 'Button Controller-5.1']],
                    [data: [id: 42, name: 'Hallway Button', type: 'Button Controller-5.1']]]],
                [data: [id: 50, name: 'Throwaway', type: 'Deadman Test Target']],
                [data: [id: 51, name: 'Someone Else', type: 'Deadman Test Target']],
                [data: [id: 52, name: 'Unreadable', type: 'Deadman Test Target']],
                [data: [id: 60, name: '<span>BAT_E2E_Paused</span>', type: 'Rule-5.1']],
                [data: [id: 61, name: 'BAT_E2E_KEEP_App', type: 'Rule-5.1']]]],
             '/hub2/userAppTypes': [[id: 21, name: 'Deadman Test Target', namespace: 'mcptest']],
             '/installedapp/configure/json/50': [app: [appType: [namespace: 'mcptest']]],
             '/installedapp/configure/json/51': [app: [appType: [namespace: 'someone']]]])
        script.metaClass.purgeOtherFixturesLocked = { String p, String c -> [deleted: [], failed: [], deletedCount: 0] }
        script.metaClass.adminForceDeleteInstalledApp = { Map a -> forced << a.id; [success: true] }
        script.metaClass.getAllGlobalVars = { -> [:] }

        when:
        def r = script.purgeE2eArtifactsLocked('BAT_E2E_', null)

        then: 'a same-named app from another namespace is left alone, and an unconfirmed one is reported'
        forced.collect { it as Integer }.sort() == [41, 50, 60]
        r.failed*.id == [52]
        r.success == false
    }

    def "an unreadable app code list is a purge failure"() {
        given:
        hub(['/hub2/appsList': [apps: []]])
        script.metaClass.purgeOtherFixturesLocked = { String p, String c -> [deleted: [], failed: [], deletedCount: 0] }
        script.metaClass.getAllGlobalVars = { -> [:] }

        when:
        def r = script.purgeE2eArtifactsLocked('BAT_E2E_', null)

        then:
        r.success == false
        r.failed*.name == ['/hub2/userAppTypes']
    }
}
