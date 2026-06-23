package server

import support.TestLocation
import support.ToolSpecBase
import spock.lang.Shared

/**
 * Spec for the mode surface in libraries/mcp-system-lib.groovy:
 *   toolGetModes        -> hub_list_modes (extended: per-mode icon + Mode Manager state)
 *   toolManageMode      -> hub_manage_mode (action: create | rename | delete | activate)
 *   toolSetModeManager  -> hub_set_mode_manager
 *
 * location.modes / location.setMode are served by a shared TestLocation; the /modes/* HTTP
 * surface is stubbed via hubGet.register (GETs) and script.metaClass.hubInternalPostJson (POSTs).
 */
class ToolModeSpec extends ToolSpecBase {

    @Shared private TestLocation sharedLocation = new TestLocation()

    def setupSpec() {
        appExecutor.getLocation() >> sharedLocation
    }

    def cleanup() {
        sharedLocation.modes = []
        sharedLocation.setMode('Home')
        sharedLocation.modeSetCalls.clear()
    }

    private void enableWrite() {
        settingsMap.enableWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L   // matches the harness fixed now()
    }

    private void seedModes() {
        sharedLocation.modes = [[id: 1, name: 'Day'], [id: 2, name: 'Night'], [id: 3, name: 'Away']]
    }

    // ---------- hub_list_modes (extended) ----------

    def "hub_list_modes returns per-mode icon + Mode Manager state from /modes/json"() {
        given:
        seedModes()
        hubGet.register('/modes/json') { params ->
            '{"modes":[{"id":1,"name":"Day","icon":"fa-sun"},{"id":2,"name":"Night","icon":"fa-moon"}],"currentModeId":1,"selectedModeManager":"builtIn","modeManagerAppId":1814,"easyModeManagerAppId":65}'
        }

        when:
        def result = script.toolGetModes()

        then:
        result.modes.find { it.name == 'Day' }?.icon == 'fa-sun'
        result.modeManager.selected == 'builtIn'
        result.modeManager.appId == '1814'
    }

    def "hub_list_modes reads the Integrated Mode Manager conditions regardless of which manager is selected"() {
        given:
        hubGet.register('/modes/json') { params -> '{"modes":[{"id":1,"name":"Day"}],"selectedModeManager":"builtIn","modeManagerAppId":1814,"easyModeManagerAppId":65}' }
        hubGet.register('/modes/easyModeManager/json') { params -> '{"1":[{"type":"time"}]}' }

        when:
        def result = script.toolGetModes()

        then:
        result.modeManager.selected == 'builtIn'
        result.modeManager.easyConditions == ['1': [[type: 'time']]]
    }

    def "hub_list_modes falls back to the SDK list when /modes/json is unreadable"() {
        given:
        seedModes()   // /modes/json unregistered -> hubInternalGet throws -> caught -> SDK fallback

        when:
        def result = script.toolGetModes()

        then:
        result.modes*.name == ['Day', 'Night', 'Away']
        !result.containsKey('modeManager')
    }

    // ---------- hub_manage_mode ----------

    def "hub_manage_mode create posts /modes/jsonCreate"() {
        given:
        enableWrite()
        def sent = [:]
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false -> sent.path = path; sent.body = body; return [success: true] }
        hubGet.register('/modes/json') { params -> '{"modes":[{"id":1,"name":"Day"}]}' }

        when:
        def result = script.toolManageMode([action: 'create', name: 'Vacation'])

        then:
        sent.path == '/modes/jsonCreate'
        sent.body.contains('Vacation')
        result.success == true
        result.action == 'create'
    }

    def "hub_manage_mode rename resolves the target by name and posts /modes/jsonUpdate with its id"() {
        given:
        enableWrite()
        seedModes()
        def sent = [:]
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false -> sent.path = path; sent.body = body; return [success: true] }
        hubGet.register('/modes/json') { params -> '{"modes":[]}' }

        when:
        def result = script.toolManageMode([action: 'rename', mode: 'Night', name: 'Evening'])

        then:
        sent.path == '/modes/jsonUpdate'
        sent.body.contains('"id":2')      // Night -> id 2
        sent.body.contains('Evening')
        result.success == true
    }

    def "hub_manage_mode delete requires confirm and hits /modes/jsonDelete"() {
        given:
        enableWrite()
        seedModes()
        hubGet.register('/modes/jsonDelete/3') { params -> '{"success":true}' }
        hubGet.register('/modes/json') { params -> '{"modes":[]}' }

        when:
        def result = script.toolManageMode([action: 'delete', mode: 'Away', confirm: true])

        then:
        result.success == true
        result.action == 'delete'
        result.deletedModeId == '3'
    }

    def "hub_manage_mode delete without confirm throws (destructive gate)"() {
        given:
        settingsMap.enableWrite = true   // no backup, no confirm
        seedModes()

        when:
        script.toolManageMode([action: 'delete', mode: 'Away'])

        then:
        thrown(IllegalArgumentException)
    }

    def "hub_manage_mode activate sets the location mode"() {
        given:
        seedModes()

        when:
        def result = script.toolManageMode([action: 'activate', mode: 'Night'])

        then:
        result.success == true
        result.newMode == 'Night'
        sharedLocation.modeSetCalls.contains('Night')
    }

    def "hub_manage_mode rejects an unknown target mode"() {
        given:
        enableWrite()
        seedModes()

        when:
        script.toolManageMode([action: 'rename', mode: 'Nonexistent', name: 'X'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('not found')
    }

    def "hub_manage_mode surfaces a create failure without false-greening"() {
        given:
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false -> [success: false, message: 'duplicate mode name'] }

        when:
        def result = script.toolManageMode([action: 'create', name: 'Day'])

        then:
        result.success == false
        result.error.contains('duplicate')
    }

    def "hub_manage_mode rejects an unknown action"() {
        when:
        script.toolManageMode([action: 'frobnicate'])

        then:
        thrown(IllegalArgumentException)
    }

    def "hub_manage_mode via dispatch returns the success envelope (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        seedModes()
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false -> [success: true] }
        hubGet.register('/modes/json') { params -> '{"modes":[]}' }

        when:
        def response = mcpDriver.callTool('hub_manage_mode', [action: 'create', name: 'Vacation'])

        then:
        def inner = mcpDriver.parseInner(response)
        inner.success == true

        where:
        useGateways << [true, false]
    }

    // ---------- hub_set_mode_manager ----------

    def "hub_set_mode_manager selects the manager via /modes/setModeManager"() {
        given:
        enableWrite()
        hubGet.register('/modes/setModeManager/legacy') { params -> '{"success":true}' }

        when:
        def result = script.toolSetModeManager([manager: 'legacy'])

        then:
        result.success == true
        result.manager == 'legacy'
    }

    def "hub_set_mode_manager updates Integrated Mode Manager conditions"() {
        given:
        enableWrite()
        def sent = [:]
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false -> sent.path = path; sent.body = body; return [success: true] }

        when:
        def result = script.toolSetModeManager([conditions: ['1': [[type: 'time']]]])

        then:
        sent.path == '/modes/easyModeManager/json'
        result.conditionsUpdated == true
        result.success == true
    }

    def "hub_set_mode_manager rejects an unknown manager"() {
        given:
        enableWrite()

        when:
        script.toolSetModeManager([manager: 'bogus'])

        then:
        thrown(IllegalArgumentException)
    }

    def "hub_set_mode_manager via dispatch (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        hubGet.register('/modes/setModeManager/builtIn') { params -> '{"success":true}' }

        when:
        def response = mcpDriver.callTool('hub_set_mode_manager', [manager: 'builtIn'])

        then:
        def inner = mcpDriver.parseInner(response)
        inner.success == true

        where:
        useGateways << [true, false]
    }

    // ---------- failure / resolution branches (review follow-ups) ----------

    def "hub_manage_mode delete surfaces a hub refusal without false-greening"() {
        given:
        enableWrite()
        seedModes()
        hubGet.register('/modes/jsonDelete/3') { params -> '{"success":false,"message":"mode is in use"}' }

        when:
        def result = script.toolManageMode([action: 'delete', mode: 'Away', confirm: true])

        then:
        result.success == false
        result.error.contains('in use')
    }

    def "hub_manage_mode rename surfaces a hub failure without false-greening"() {
        given:
        enableWrite()
        seedModes()
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false -> [success: false, message: 'name taken'] }

        when:
        def result = script.toolManageMode([action: 'rename', mode: 'Night', name: 'Day'])

        then:
        result.success == false
        result.error.contains('name taken')
    }

    def "hub_manage_mode resolves the target by numeric id and case-insensitively (#id)"() {
        given:
        enableWrite()
        seedModes()
        def sent = [:]
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false -> sent.body = body; return [success: true] }
        hubGet.register('/modes/json') { params -> '{"modes":[]}' }

        when:
        script.toolManageMode([action: 'rename', mode: id, name: 'Evening'])

        then:
        sent.body.contains('"id":2')   // both '2' (id string) and 'night' (lowercase) resolve to Night = id 2

        where:
        id << ['2', 'night']
    }

    def "hub_set_mode_manager surfaces a manager-select failure and skips the conditions leg"() {
        given:
        enableWrite()
        hubGet.register('/modes/setModeManager/builtIn') { params -> '{"success":false}' }
        def condCalled = false
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false -> condCalled = true; return [success: true] }

        when:
        def result = script.toolSetModeManager([manager: 'builtIn', conditions: ['1': []]])

        then:
        result.success == false
        condCalled == false   // conditions NOT applied after the manager leg failed (no partial apply)
    }

    def "hub_set_mode_manager applies manager + conditions in one call"() {
        given:
        enableWrite()
        hubGet.register('/modes/setModeManager/builtIn') { params -> '{"success":true}' }
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false -> [success: true] }

        when:
        def result = script.toolSetModeManager([manager: 'builtIn', conditions: ['1': [[type: 'time']]]])

        then:
        result.success == true
        result.manager == 'builtIn'
        result.conditionsUpdated == true
    }

    def "hub_set_mode_manager surfaces a conditions-update failure"() {
        given:
        enableWrite()
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false -> [success: false, message: 'bad conditions'] }

        when:
        def result = script.toolSetModeManager([conditions: ['1': []]])

        then:
        result.success == false
        result.conditionsError.contains('bad conditions')
    }

    def "hub_list_modes falls back to the SDK list for a 2xx non-Map body"() {
        given:
        seedModes()
        hubGet.register('/modes/json') { params -> '[]' }   // valid JSON, not a Map

        when:
        def result = script.toolGetModes()

        then:
        result.modes*.name == ['Day', 'Night', 'Away']
        !result.containsKey('modeManager')
    }

    def "hub_list_modes omits modeManager when the payload lacks the manager keys"() {
        given:
        seedModes()
        hubGet.register('/modes/json') { params -> '{"modes":[{"id":1,"name":"Day"}]}' }   // Map, no manager keys

        when:
        def result = script.toolGetModes()

        then:
        !result.containsKey('modeManager')   // no all-null block masking a malformed/error body
    }
}
