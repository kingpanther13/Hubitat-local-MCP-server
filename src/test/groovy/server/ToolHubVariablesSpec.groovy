package server

import spock.lang.Shared
import support.TestLocation
import support.ToolSpecBase

/**
 * Spec for the hub-variable tools in libraries/mcp-variables-lib.groovy:
 * hub_list_variables, hub_get_variable, hub_set_variable, hub_create_variable,
 * hub_delete_variable, hub_create_connector and hub_delete_connector, plus the
 * main app's _resolveDirectAppId helper.
 *
 * getAllGlobalVars / getGlobalVar / setGlobalVar are dynamic (not on
 * AppExecutor), so they're stubbed per-test via script.metaClass. The hub's
 * Hub Variables page is stubbed by stubHubVarsPage(): GET /hub2/variables
 * through the harness hubGet mock (rows shaped like the live capture in
 * resources/hub2-source/README.md) and the write POSTs recorded in varPosts.
 * NativeHubVariablesSpec covers the same page with a stateful fake.
 *
 * The write tools are gated centrally on the Write master (executeTool) and by
 * requireDestructiveConfirm (args.confirm + 24h backup window). Helper
 * enableWrite() seeds the Write master + a recent backup.
 */
class ToolHubVariablesSpec extends ToolSpecBase {

    @Shared private TestLocation sharedLocation = new TestLocation()

    def setupSpec() {
        appExecutor.getLocation() >> sharedLocation
    }

    def cleanup() {
        sharedLocation.timeZone = TimeZone.getTimeZone('UTC')
    }

    private void enableWrite() {
        settingsMap.enableWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L  // matches HarnessSpec's fixed now()
    }

    // The Hub Variables page as these cases see it: its rows by name, and every write it receives.
    private Map modelRows = [:]
    private List varPosts = []

    private static Map modelRow(String name, Map extra = [:]) {
        [name: name, type: 'integer', value: '1', linked: false, meshEnabled: false, sourceName: 'Local', sourceIp: null,
         connectorId: null, connectorLabel: null, connectorType: null, connectorOptions: ['Variable', 'Dimmer'],
         usedBy: [], connectorUsedBy: []] + extra
    }

    private void stubHubVarsPage(Closure onPost = null) {
        hubGet.register('/hub2/variables') { params ->
            groovy.json.JsonOutput.toJson([timeZone: 'UTC', variables: modelRows.values() as List])
        }
        script.metaClass.hubInternalPostJson = { String path, String json, int timeout = 420 ->
            Map body = new groovy.json.JsonSlurper().parseText(json) as Map
            varPosts << [path: path, body: body]
            onPost?.call(path, body)
            return null   // the hub answers a write it took with HTTP 204 and an empty body
        }
    }

    // The hub's 400 body for a name it will not take.
    static final String NAME_REFUSAL = '{"message":"Use a name of 1-255 characters without leading/trailing spaces, control characters, or quotes, backslashes, ~, [, ], :, <, >."}'

    // An HTTP error answer as the hub client throws it: the status and body ride on `response`.
    static class HubAnswer extends RuntimeException {
        Map response
        HubAnswer(int status, data) {
            super("HTTP ${status}".toString())
            response = [status: status, data: data]
        }
    }

    // -------- initialize() lifecycle (issue #105 PR2a #6) --------

    def "initialize drops the variable subscriptions older versions made"() {
        given: 'a clean initialize with the unrelated side-effects neutralised'
        UNSUBSCRIBE_CALL_COUNT.set(0)
        stateMap.accessToken = 'tok'                  // skip createAccessToken
        script.metaClass.checkForUpdate = { -> }      // skip the GitHub HTTP check
        script.metaClass.getAllGlobalVars = { -> [porch: [name: 'porch']] }

        when:
        script.initialize()

        then: 'SetupRefreshLifecycleSpec proves no subscription is made'
        UNSUBSCRIBE_CALL_COUNT.get() == 1
    }

    def "the retired variable callbacks write no app state"() {
        when: 'a variable event from an older subscription arrives, and the hub reports a rename'
        script.handleHubVariableEvent([name: 'variable:porch', value: 'on'])
        script.renameVariable('porch', 'deck')

        then: 'only the log machinery touched state'
        !atomicStateMap.containsKey('variableHistory')
        atomicStateMap.keySet() - ['debugLogGeneration'] == [] as Set
    }

    // -------- toolListVariables --------

    def "hub_list_variables returns both hub and rule-engine variables with correct total"() {
        given: 'hub exposes two variables — one connector-backed, one not'
        script.metaClass.getAllGlobalVars = { ->
            [
                'temp_setpoint': [name: 'temp_setpoint', type: 'Number',  value: 72,    deviceId: 305, attribute: 'variable'],
                'vacation_mode': [name: 'vacation_mode', type: 'Boolean', value: false, deviceId: null, attribute: null]
            ]
        }

        and: 'the rule engine has one variable stored in state'
        stateMap.ruleVariables = [last_motion: '2026-04-21T10:00:00']

        and: 'the Hub Variables page describes the connector and Hub Mesh state'
        modelRows.temp_setpoint = modelRow('temp_setpoint', [value: '72', connectorId: 305, connectorLabel: 'Setpoint', connectorType: 'Dimmer'])
        modelRows.vacation_mode = modelRow('vacation_mode', [type: 'boolean', value: 'false', meshEnabled: true])
        stubHubVarsPage()

        when:
        def result = script.toolListVariables()

        then: 'hub variables come back with the modern shape including connector linkage'
        result.hubVariables.size() == 2
        def temp = result.hubVariables.find { it.name == 'temp_setpoint' }
        temp.value == 72
        temp.type == 'Number'
        temp.source == 'hub'
        temp.deviceId == 305
        temp.attribute == 'variable'
        def vac = result.hubVariables.find { it.name == 'vacation_mode' }
        vac.deviceId == null
        vac.attribute == null

        and: 'one page read adds the connector and Hub Mesh fields'
        temp.connectorType == 'Dimmer'
        temp.connectorLabel == 'Setpoint'
        temp.meshShared == false
        temp.linked == false
        vac.meshShared == true
        !vac.containsKey('connectorType')
        !result.containsKey('detailsError')
        hubGet.calls*.path == ['/hub2/variables']

        and: 'rule-engine variables come back unchanged'
        result.ruleVariables.size() == 1
        result.ruleVariables[0].name == 'last_motion'
        result.ruleVariables[0].source == 'rule_engine'
        result.total == 3
    }

    def "hub_list_variables falls back to rule variables when the hub API throws"() {
        given:
        script.metaClass.getAllGlobalVars = { ->
            throw new RuntimeException('Hub variable API not available')
        }
        stateMap.ruleVariables = [counter: 5]

        when:
        def result = script.toolListVariables()

        then:
        result.hubVariables == []
        result.ruleVariables.size() == 1
        result.total == 1
    }

    def "hub_list_variables returns empty collections when no variables are defined"() {
        given:
        script.metaClass.getAllGlobalVars = { -> [:] }

        when:
        def result = script.toolListVariables()

        then:
        result.hubVariables == []
        result.ruleVariables == []
        result.total == 0
        // unconditional per-list totals so a caller can distinguish "no hub vars"
        // from "no rule vars" without cursor context
        result.totalHubVariables == 0
        result.totalRuleVariables == 0
    }

    def "hub_list_variables keeps every row and reports detailsError when the Hub Variables page cannot be read"() {
        given:
        script.metaClass.getAllGlobalVars = { -> ['n': [name: 'n', type: 'integer', value: 3, deviceId: null, attribute: null]] }
        hubGet.register('/hub2/variables') { params -> throw new RuntimeException('page down') }

        when:
        def result = script.toolListVariables()

        then:
        result.hubVariables == [[name: 'n', value: 3, type: 'integer', deviceId: null, attribute: null, source: 'hub']]
        result.detailsError == 'Could not read the Hub Variables model: page down'
    }

    def "hub_list_variables reads no page when there are no hub variables"() {
        given:
        script.metaClass.getAllGlobalVars = { -> [:] }
        stateMap.ruleVariables = [counter: 5]

        when:
        def result = script.toolListVariables()

        then:
        result.total == 1
        hubGet.calls.isEmpty()
    }

    def "hub_list_variables cursor pages hubVariables while ruleVariables stays in full (#174)"() {
        given: '150 hub vars + 5 rule vars'
        def allVars = (0..<150).collectEntries { i -> ["var_${String.format('%03d', i)}".toString(), [name: "var_${i}".toString(), type: 'Number', value: i, deviceId: null, attribute: null]] }
        script.metaClass.getAllGlobalVars = { -> allVars }
        stateMap.ruleVariables = [a: 1, b: 2, c: 3, d: 4, e: 5]

        when: 'first page (cursor="")'
        def page1 = script.toolListVariables([cursor: ''])

        then:
        page1.hubVariables.size() == 100
        page1.ruleVariables.size() == 5   // ruleVariables stays in full
        page1.totalHubVariables == 150
        page1.totalRuleVariables == 5
        page1.total == 155                 // legacy combined total
        page1.nextCursor == '100'

        when: 'last page'
        def page2 = script.toolListVariables([cursor: '100'])

        then:
        page2.hubVariables.size() == 50
        page2.ruleVariables.size() == 5
        !page2.containsKey('nextCursor')
    }

    def "hub_list_variables surfaces a hubVariablesError field when getAllGlobalVars throws (not silent)"() {
        // Pre-fix this exception was only logged at debug level; a caller couldn't
        // distinguish "no hub variables" from "hub API broke".
        given:
        script.metaClass.getAllGlobalVars = { -> throw new RuntimeException('sandbox tightened: getAllGlobalVars not available') }

        when:
        def result = script.toolListVariables()

        then:
        result.hubVariables == []
        result.hubVariablesError != null
        result.hubVariablesError.contains('sandbox tightened')
        result.totalHubVariables == 0
    }

    // -------- toolGetVariable --------

    def "a hub variable lookup that throws is recorded in the MCP log at debug level"() {
        given:
        settingsMap.mcpLogLevel = 'debug'
        script.log.messages.clear()
        script.metaClass.getGlobalVar = { String name -> throw new RuntimeException('platform lookup failed') }

        when:
        script.toolGetVariable('outdoor_temp')

        then: 'the lookup failure falls through to not-found'
        thrown(IllegalArgumentException)

        and: 'its diagnostic reaches the MCP history under hub-vars'
        script.log.messages.any {
            it.contains('[MCP1] ') && it.contains('"component":"hub-vars"') &&
                it.contains("Hub variable 'outdoor_temp' lookup threw RuntimeException: platform lookup failed")
        }
    }

    def "hub_get_variable returns the modern shape with type and connector linkage"() {
        given:
        script.metaClass.getGlobalVar = { String name ->
            if (name != 'outdoor_temp') return null
            [name: 'outdoor_temp', type: 'Decimal', value: 68.4, deviceId: 312, attribute: 'variable']
        }

        when:
        def result = script.toolGetVariable('outdoor_temp')

        then:
        result.name == 'outdoor_temp'
        result.value == 68.4
        result.type == 'Decimal'
        result.deviceId == 312
        result.attribute == 'variable'
        result.source == 'hub'
    }

    def "hub_get_variable returns hub var without connector linkage when no connector exists"() {
        given: 'a Boolean var without a connector — deviceId/attribute null'
        script.metaClass.getGlobalVar = { String name ->
            [name: 'vacation_mode', type: 'Boolean', value: true, deviceId: null, attribute: null]
        }

        when:
        def result = script.toolGetVariable('vacation_mode')

        then:
        result.name == 'vacation_mode'
        result.value == true
        result.type == 'Boolean'
        result.deviceId == null
        result.attribute == null
        result.source == 'hub'
    }

    def "hub_get_variable falls back to rule-engine value when hub API throws"() {
        given:
        script.metaClass.getGlobalVar = { String name ->
            throw new RuntimeException('Not supported')
        }
        stateMap.ruleVariables = [porch_light_pref: 'bright']

        when:
        def result = script.toolGetVariable('porch_light_pref')

        then:
        result.name == 'porch_light_pref'
        result.value == 'bright'
        result.source == 'rule_engine'
    }

    def "hub_get_variable throws when variable exists in neither source"() {
        given:
        script.metaClass.getGlobalVar = { String name -> null }
        stateMap.ruleVariables = [:]

        when:
        script.toolGetVariable('nonexistent')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Variable not found')
        ex.message.contains('nonexistent')
    }

    // -------- toolSetVariable --------

    def "rule-engine variables preserve data keys and false zero null values on both write paths"() {
        given:
        script.metaClass.setGlobalVar = { String name, Object value -> false }
        script.metaClass.getGlobalVar = { String name -> null }
        stateMap.ruleVariables = [untouched: 'keep']
        def values = [fields: false, class: 0, metaClass: null, Fields: [false, 0, null], getClass: 'data']

        expect:
        values.every { key, value ->
            def result = script.toolSetVariable([name: key, value: value])
            result.success && result.source == 'rule_engine' &&
                stateMap.ruleVariables.containsKey(key) && script.getVariableValue(key) == value
        }
        values.every { key, value ->
            script.setRuleVariable(key, 'old') == 'old' &&
                script.setRuleVariable(key, value) == value &&
                stateMap.ruleVariables.containsKey(key) && script.getVariableValue(key) == value
        }
        stateMap.ruleVariables.untouched == 'keep'
    }

    def "hub_set_variable writes via setGlobalVar and reports source 'hub' on success"() {
        given:
        script.metaClass.getGlobalVar = { String n -> [name: n, type: 'boolean', value: false] }
        def captured = [:]
        script.metaClass.setGlobalVar = { String name, Object value ->
            captured[name] = value
            return true
        }

        when:
        def result = script.toolSetVariable([name: 'vacation_mode', value: true])

        then:
        captured == [vacation_mode: true]
        result.success == true
        result.name == 'vacation_mode'
        result.value == true
        result.source == 'hub'
    }

    def "hub_set_variable falls back to rule engine when setGlobalVar returns false (var not found)"() {
        given: 'the name is not a hub variable, and setGlobalVar returns false for it'
        script.metaClass.getGlobalVar = { String n -> null }
        script.metaClass.setGlobalVar = { String name, Object value -> false }

        when:
        def result = script.toolSetVariable([name: 'new_rule_var', value: 42])

        then: 'rule-engine state was updated'
        stateMap.ruleVariables == [new_rule_var: 42]

        and: 'result reports rule_engine source'
        result.success == true
        result.source == 'rule_engine'
        result.value == 42
    }

    def "hub_set_variable falls back to rule engine when setGlobalVar throws for a name that is not a hub variable"() {
        given:
        script.metaClass.getGlobalVar = { String n -> null }
        script.metaClass.setGlobalVar = { String name, Object value ->
            throw new RuntimeException('Hub variable API unavailable')
        }

        when:
        def result = script.toolSetVariable([name: 'weather_state', value: 'sunny'])

        then:
        stateMap.ruleVariables == [weather_state: 'sunny']
        result.success == true
        result.value == 'sunny'
        result.source == 'rule_engine'
    }

    def "hub_set_variable passes string values through to setGlobalVar unchanged"() {
        given:
        script.metaClass.getGlobalVar = { String n -> [name: n, type: 'string', value: 'rain'] }
        def captured = [:]
        script.metaClass.setGlobalVar = { String name, Object value ->
            captured[name] = value
            return true
        }

        when:
        def result = script.toolSetVariable([name: 'weather_state', value: 'sunny'])

        then:
        captured == [weather_state: 'sunny']
        result.value == 'sunny'
        result.source == 'hub'
    }

    // -------- toolSetVariable Hub Mesh sharing --------

    def "hub_set_variable shares a hub variable into the mesh (mesh_shared=true) and confirms via read-back"() {
        given: 'the name is a hub variable, and the mesh share + read-back succeed'
        script.metaClass.getGlobalVar = { String n -> [type: 'Boolean', value: true] }
        hubGet.register('/hub2/addVarToMesh/vacationMode') { params -> '' }
        hubGet.register('/hub2/hubMeshJson') { params ->
            '{"sharedHubVariables":[{"name":"vacationMode","type":"Boolean"}]}'
        }

        when:
        def result = script.toolSetVariable([name: 'vacationMode', mesh_shared: true])

        then: 'the share endpoint was hit and the read-back confirmed it'
        hubGet.calls.any { it.key == '/hub2/addVarToMesh/vacationMode' }
        result.success == true
        result.meshShared == true
        result.meshShareConfirmed == true
        !result.containsKey('value')   // mesh-only path did not touch the value leg
    }

    def "hub_set_variable unshares a hub variable (mesh_shared=false)"() {
        given:
        script.metaClass.getGlobalVar = { String n -> [type: 'Boolean', value: true] }
        hubGet.register('/hub2/removeVarFromMesh/vacationMode') { params -> '' }
        hubGet.register('/hub2/hubMeshJson') { params -> '{"sharedHubVariables":[]}' }

        when:
        def result = script.toolSetVariable([name: 'vacationMode', mesh_shared: false])

        then:
        hubGet.calls.any { it.key == '/hub2/removeVarFromMesh/vacationMode' }
        result.success == true
        result.meshShared == false
        result.meshShareConfirmed == true   // absent from sharedHubVariables == unshared

        and: 'unshare carries the stale-copy / unlink-first caution'
        result.note != null
        result.note.contains('unlink the copy on those hubs first')
        result.note.contains('STALE local copy')
    }

    def "hub_set_variable share (mesh_shared=true) does NOT carry the unshare caution; unshare does"() {
        given: 'the mesh read-back reflects the applied state (so both legs confirm rather than fail)'
        script.metaClass.getGlobalVar = { String n -> [type: 'Boolean', value: true] }
        boolean shared = false
        hubGet.register('/hub2/addVarToMesh/vacationMode') { params -> shared = true; '' }
        hubGet.register('/hub2/removeVarFromMesh/vacationMode') { params -> shared = false; '' }
        hubGet.register('/hub2/hubMeshJson') { params ->
            groovy.json.JsonOutput.toJson([sharedHubVariables: shared ? [[name: 'vacationMode']] : []])
        }

        when: 'share'
        def sharedResult = script.toolSetVariable([name: 'vacationMode', mesh_shared: true])

        then: 'no unshare caution on the share path'
        sharedResult.meshShareConfirmed == true
        !(sharedResult.note?.contains('unlink the copy on those hubs first'))

        when: 'unshare (read-back now confirms absent)'
        def unshared = script.toolSetVariable([name: 'vacationMode', mesh_shared: false])

        then: 'the unshare succeeds and appends the stale-copy caution'
        unshared.success == true
        unshared.meshShareConfirmed == true
        unshared.note.contains('unlink the copy on those hubs first')
    }

    // Item: a readable sharedHubVariables that DISAGREES with the request is this hub's OWN state, so a
    // confirmed mismatch is a real FAILURE (not a propagation-lag soft success).
    def "hub_set_variable share FAILS when the read-back confirms the var is still not shared"() {
        given: 'the share endpoint returns 200 but the read-back never shows the var shared'
        script.metaClass.getGlobalVar = { String n -> [type: 'Boolean', value: true] }
        hubGet.register('/hub2/addVarToMesh/vacationMode') { params -> '' }
        hubGet.register('/hub2/hubMeshJson') { params -> '{"sharedHubVariables":[]}' }

        when:
        def result = script.toolSetVariable([name: 'vacationMode', mesh_shared: true])

        then:
        result.success == false
        result.isError == true
        result.meshShareConfirmed == false
        result.error.contains('did not take')
        result.note.contains("this hub's own state")
    }

    def "hub_set_variable unshare FAILS when the read-back confirms the var is still shared"() {
        given: 'the unshare endpoint returns 200 but the read-back still shows the var shared'
        script.metaClass.getGlobalVar = { String n -> [type: 'Boolean', value: true] }
        hubGet.register('/hub2/removeVarFromMesh/vacationMode') { params -> '' }
        hubGet.register('/hub2/hubMeshJson') { params -> '{"sharedHubVariables":[{"name":"vacationMode"}]}' }

        when:
        def result = script.toolSetVariable([name: 'vacationMode', mesh_shared: false])

        then:
        result.success == false
        result.isError == true
        result.meshShareConfirmed == false
        result.error.contains('did not take')
    }

    def "hub_set_variable applies BOTH a value write and a mesh share in one call"() {
        given:
        def captured = [:]
        script.metaClass.setGlobalVar = { String n, Object v -> captured[n] = v; return true }
        script.metaClass.getGlobalVar = { String n -> [type: 'Boolean', value: false] }
        hubGet.register('/hub2/addVarToMesh/vacationMode') { params -> '' }
        hubGet.register('/hub2/hubMeshJson') { params -> '{"sharedHubVariables":[{"name":"vacationMode"}]}' }

        when:
        def result = script.toolSetVariable([name: 'vacationMode', value: true, mesh_shared: true])

        then:
        captured == [vacationMode: true]
        result.success == true
        result.value == true
        result.source == 'hub'
        result.meshShared == true
        result.meshShareConfirmed == true
    }

    def "hub_set_variable value+mesh: a hub variable the hub does not set fails before the mesh share"() {
        given:
        script.metaClass.getGlobalVar = { String n -> [type: 'Boolean', value: false] }
        script.metaClass.setGlobalVar = { String n, Object v -> false }   // hub write does not persist

        when:
        def result = script.toolSetVariable([name: 'vacationMode', value: true, mesh_shared: true])

        then: 'an honest failure, no rule_engine copy, and the mesh share never sent'
        result.success == false
        result.isError == true
        result.error.contains("did not apply the new value to hub variable 'vacationMode'")
        result.note.contains("hub_get_tool_guide(section='variables')")
        stateMap.ruleVariables == null
        !hubGet.calls.any { it.path.startsWith('/hub2/addVarToMesh/') }
    }

    // -------- toolSetVariable typed hub-variable writes --------

    @spock.lang.Unroll
    def "hub_set_variable stores #input on a #type hub variable as #expected"() {
        given:
        script.metaClass.getGlobalVar = { String n -> [name: n, type: type, value: null] }
        def captured = []
        script.metaClass.setGlobalVar = { String n, Object v -> captured << v; true }

        when:
        def result = script.toolSetVariable([name: 'v', value: input])

        then:
        captured == [expected]
        captured[0].getClass() == expected.getClass()
        result == [success: true, name: 'v', value: expected, source: 'hub']

        where:
        type         | input    | expected
        'integer'    | '42'     | 42
        'integer'    | -7       | -7
        'integer'    | 5.0      | 5
        'bigdecimal' | '1.50'   | new BigDecimal('1.50')
        'bigdecimal' | 3        | new BigDecimal('3')
        'boolean'    | 'TRUE'   | true
        'boolean'    | false    | false
        'string'     | 5        | '5'
    }

    @spock.lang.Unroll
    def "hub_set_variable refuses #input for a #type hub variable before any write"() {
        given:
        script.metaClass.getGlobalVar = { String n -> [name: n, type: type, value: null] }
        def writes = []
        script.metaClass.setGlobalVar = { String n, Object v -> writes << v; true }

        when:
        script.toolSetVariable([name: 'v', value: input])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains(phrase)
        writes.isEmpty()
        stateMap.ruleVariables == null

        where:
        type         | input                 | phrase
        'integer'    | 'abc'                 | 'is a Number (integer): value must be a whole number'
        'integer'    | '1.5'                 | 'whole number'
        'integer'    | '99999999999'         | 'whole number'
        'bigdecimal' | 'x'                   | 'is a Decimal (bigdecimal): value must be a number'
        'boolean'    | 'yes'                 | 'is a Boolean: value must be true or false'
        'string'     | null                  | 'is a String: value must be text'
        'datetime'   | '2026-13-45T12:30'    | 'is a DateTime with date and time: value must be a date and time'
        'datetime'   | null                  | 'is a DateTime'
    }

    @spock.lang.Unroll
    def "hub_set_variable converts DateTime input #input to the hub-local stored form"() {
        given:
        sharedLocation.timeZone = TimeZone.getTimeZone('America/New_York')
        script.metaClass.getGlobalVar = { String n -> [name: n, type: 'datetime', value: '2026-01-01T00:00:00.000-0500'] }
        def captured = []
        script.metaClass.setGlobalVar = { String n, Object v -> captured << v; true }

        when:
        def result = script.toolSetVariable([name: 'when', value: input])

        then:
        captured == ['2026-02-04T14:00:00.000-0500']
        result.value == '2026-02-04T14:00:00.000-0500'

        where:
        input << ['2026-02-04T14:00', '2026-02-04 14:00:59', [date: '2026-02-04', time: '14:00']]
    }

    @spock.lang.Unroll
    def "hub_set_variable keeps a #kind DateTime variable's kind for #input"() {
        given:
        sharedLocation.timeZone = TimeZone.getTimeZone('America/New_York')
        script.metaClass.getGlobalVar = { String n -> [name: n, type: 'datetime', value: current] }
        def captured = []
        script.metaClass.setGlobalVar = { String n, Object v -> captured << v; true }

        when:
        def result = script.toolSetVariable([name: 'when', value: input])

        then:
        captured == [expected]
        result.value == expected

        where:
        kind       | current                         | input                            | expected
        'date'     | '2026-03-05T99:99:99.999-9999'  | '2026-04-01'                     | '2026-04-01T99:99:99.999-9999'
        'date'     | '2026-03-05T99:99:99.999-9999'  | [date: '2026-04-01']             | '2026-04-01T99:99:99.999-9999'
        'date'     | '2026-03-05T99:99:99.999-9999'  | '2026-03-05T99:99:99.999-9999'   | '2026-03-05T99:99:99.999-9999'
        'time'     | '9999-99-99T08:15:00.000-0500'  | '10:30'                          | '9999-99-99T10:30:00.000' + offsetNow()
        'time'     | '9999-99-99T08:15:00.000-0500'  | [time: '10:30:45']               | '9999-99-99T10:30:00.000' + offsetNow()
        'time'     | '9999-99-99T08:15:00.000-0500'  | '9999-99-99T08:15:00.000-0500'   | '9999-99-99T08:15:00.000' + offsetNow()
        'datetime' | '2026-03-05T08:15:00.000-0500'  | '2026-03-05T08:15:00.000-0500'   | '2026-03-05T08:15:00.000-0500'
    }

    private static String offsetNow() { new Date().format('Z', TimeZone.getTimeZone('America/New_York')) }

    @spock.lang.Unroll
    def "hub_set_variable refuses #input for a #kind DateTime variable before any write, keeping its kind"() {
        given:
        sharedLocation.timeZone = TimeZone.getTimeZone('America/New_York')
        script.metaClass.getGlobalVar = { String n -> [name: n, type: 'datetime', value: current] }
        def writes = []
        script.metaClass.setGlobalVar = { String n, Object v -> writes << v; true }

        when:
        script.toolSetVariable([name: 'when', value: input])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains(phrase)
        writes.isEmpty()

        where:
        kind       | current                         | input                  | phrase
        'date'     | '2026-03-05T99:99:99.999-9999'  | '2026-04-01T10:00'     | 'is a date-only DateTime: value must be a date such as 2026-03-05'
        'date'     | '2026-03-05T99:99:99.999-9999'  | '10:00'                | 'is a date-only DateTime'
        'time'     | '9999-99-99T08:15:00.000-0500'  | '2026-04-01'           | 'is a time-only DateTime: value must be a time such as 08:15'
        'time'     | '9999-99-99T08:15:00.000-0500'  | '25:00'                | 'is a time-only DateTime'
        'datetime' | '2026-03-05T08:15:00.000-0500'  | '2026-04-01'           | 'is a DateTime with date and time'
        'datetime' | '2026-03-05T08:15:00.000-0500'  | '9999-99-99T08:15:00.000-0500' | 'is a DateTime with date and time'
    }

    def "hub_set_variable reports a throwing setGlobalVar on a hub variable as a failure, not a rule_engine write"() {
        given:
        script.metaClass.getGlobalVar = { String n -> [name: n, type: 'integer', value: 1] }
        script.metaClass.setGlobalVar = { String n, Object v -> throw new RuntimeException('excessive hub load') }

        when:
        def result = script.toolSetVariable([name: 'n', value: 2])

        then:
        result.success == false
        result.isError == true
        result.error.contains('excessive hub load')
        result.note.contains('hub_get_variable')
        stateMap.ruleVariables == null
    }

    def "hub_set_variable writes nothing when it cannot tell whether the name is a hub variable"() {
        given:
        script.metaClass.getGlobalVar = { String n -> throw new RuntimeException('lookup failed') }
        def writes = []
        script.metaClass.setGlobalVar = { String n, Object v -> writes << v; true }

        when:
        def result = script.toolSetVariable([name: 'n', value: 2])

        then:
        result.success == false
        result.isError == true
        result.error.contains("could not read whether 'n' is a hub variable")
        result.note.startsWith('Nothing was changed')
        writes.isEmpty()
        stateMap.ruleVariables == null
    }

    // Item: a THROW from getGlobalVar in the mesh_shared pre-check is transient/unknown, NOT proof the
    // name is not a hub variable -- it must not emit the "is not a hub variable" message.
    def "hub_set_variable mesh_shared: a getGlobalVar throw surfaces an unable-to-verify message, not 'not a hub variable'"() {
        given:
        script.metaClass.getGlobalVar = { String n -> throw new RuntimeException('transient lookup error') }

        when:
        script.toolSetVariable([name: 'vacationMode', mesh_shared: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Unable to verify')
        !ex.message.contains('is not a hub variable')
    }

    def "hub_set_variable rejects mesh_shared on a rule-only variable (validation before any write)"() {
        given: 'the name is not a hub variable'
        script.metaClass.getGlobalVar = { String n -> null }
        def wrote = false
        script.metaClass.setGlobalVar = { String n, Object v -> wrote = true; false }

        when:
        script.toolSetVariable([name: 'ruleOnly', mesh_shared: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('mesh_shared applies only to hub variables')
        !wrote   // rejected before the value/mesh legs
    }

    def "hub_set_variable requires at least one of value or mesh_shared"() {
        when:
        script.toolSetVariable([name: 'vacationMode'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Provide value, mesh_shared, or both')
    }

    def "hub_set_variable rejects a non-boolean mesh_shared"() {
        when:
        script.toolSetVariable([name: 'vacationMode', mesh_shared: 'yes'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('mesh_shared must be a boolean')
    }

    def "hub_set_variable mesh share returns success with meshShareConfirmed null when the read-back is unreadable (warn-not-fail)"() {
        given: 'the share endpoint succeeds but hubMeshJson is unreadable'
        script.metaClass.getGlobalVar = { String n -> [type: 'Boolean', value: true] }
        hubGet.register('/hub2/addVarToMesh/vacationMode') { params -> '' }
        hubGet.register('/hub2/hubMeshJson') { params -> throw new RuntimeException('mesh JSON unreachable') }

        when:
        def result = script.toolSetVariable([name: 'vacationMode', mesh_shared: true])

        then: 'the share leg is not failed just because the read-back could not confirm'
        result.success == true
        result.meshShared == true
        result.meshShareConfirmed == null
    }

    def "hub_set_variable mesh share reports an UNKNOWN outcome (may-have-applied) when the endpoint throws and the read-back is unreadable"() {
        given: 'the share endpoint throws AND the read-back cannot confirm either way'
        script.metaClass.getGlobalVar = { String n -> [type: 'Boolean', value: true] }
        hubGet.register('/hub2/addVarToMesh/vacationMode') { params -> throw new RuntimeException('Hub API 500') }
        hubGet.register('/hub2/hubMeshJson') { params -> throw new RuntimeException('mesh JSON unreachable') }

        when:
        def result = script.toolSetVariable([name: 'vacationMode', mesh_shared: true])

        then: 'a throw is not a definite failure: say it MAY have applied and steer to verification'
        result.success == false
        result.isError == true
        result.meshShareConfirmed == null
        result.error.contains('errored')
        result.note.contains('MAY have applied')
        result.note.contains('hub_get_hub_mesh')
        !result.note.contains('did not happen')
    }

    // Item: value commits, then the mesh call throws with an unreadable read-back -> valueApplied still
    // reflects the committed value and the note is the unknown/verify wording (not "did not happen").
    def "hub_set_variable value+mesh: value commits then the mesh throw is reported as unknown, not failed-to-happen"() {
        given:
        def captured = [:]
        script.metaClass.setGlobalVar = { String n, Object v -> captured[n] = v; return true }
        script.metaClass.getGlobalVar = { String n -> [type: 'Boolean', value: false] }
        hubGet.register('/hub2/addVarToMesh/vacationMode') { params -> throw new RuntimeException('read timeout') }
        hubGet.register('/hub2/hubMeshJson') { params -> throw new RuntimeException('mesh JSON unreachable') }

        when:
        def result = script.toolSetVariable([name: 'vacationMode', value: true, mesh_shared: true])

        then: 'the value leg committed'
        captured == [vacationMode: true]

        and: 'the mesh throw is UNKNOWN, and valueApplied reflects the committed value'
        result.success == false
        result.valueApplied == true
        result.note.contains('committed')
        result.note.contains('MAY have applied')
        !result.note.contains('did not happen')
    }

    def "hub_set_variable mesh share reports a definite failure when the endpoint throws but the read-back proves it did not apply"() {
        given: 'the share endpoint throws AND the read-back confirms the var is still not shared'
        script.metaClass.getGlobalVar = { String n -> [type: 'Boolean', value: true] }
        hubGet.register('/hub2/addVarToMesh/vacationMode') { params -> throw new RuntimeException('Hub API 500') }
        hubGet.register('/hub2/hubMeshJson') { params -> '{"sharedHubVariables":[]}' }

        when:
        def result = script.toolSetVariable([name: 'vacationMode', mesh_shared: true])

        then:
        result.success == false
        result.isError == true
        result.meshShareConfirmed == false
        result.error.contains('Failed to share')
        result.note.contains('did not take')
    }

    @spock.lang.Unroll
    def "via dispatch: hub_set_variable(mesh_shared) passes args through the Map signature (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableWrite = true
        script.metaClass.getGlobalVar = { String n -> [type: 'Boolean', value: true] }
        hubGet.register('/hub2/addVarToMesh/vacationMode') { params -> '' }
        hubGet.register('/hub2/hubMeshJson') { params -> '{"sharedHubVariables":[{"name":"vacationMode"}]}' }

        when:
        def response = mcpDriver.callTool('hub_set_variable', [name: 'vacationMode', mesh_shared: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.meshShared == true

        where:
        useGateways << [true, false]
    }

    // -------- toolDeleteHubVariable --------

    def "hub_delete_variable removes an existing rule_engine variable and reports the previous value"() {
        given:
        enableWrite()
        stateMap.ruleVariables = [scratch_var: 'to-be-removed', keep_me: 42]

        when:
        def result = script.toolDeleteHubVariable([name: 'scratch_var', confirm: true])

        then:
        result.success == true
        result.name == 'scratch_var'
        result.deleted == true
        result.source == 'rule_engine'
        result.previousValue == 'to-be-removed'

        and: 'only the targeted variable is gone — siblings preserved'
        stateMap.ruleVariables == [keep_me: 42]
    }

    def "hub_delete_variable throws when confirm is not provided"() {
        given:
        enableWrite()
        stateMap.ruleVariables = [scratch_var: 'value']

        when:
        script.toolDeleteHubVariable([name: 'scratch_var'])

        then:
        thrown(IllegalArgumentException)
        // Variable should NOT be deleted when the gate refuses
        stateMap.ruleVariables == [scratch_var: 'value']
    }

    def "hub_delete_variable throws when no recent backup exists (requireDestructiveConfirm 24h gate)"() {
        given: 'the Write master is on but no lastBackupTimestamp seeded'
        settingsMap.enableWrite = true
        stateMap.ruleVariables = [scratch_var: 'value']

        when:
        script.toolDeleteHubVariable([name: 'scratch_var', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('BACKUP REQUIRED')
        stateMap.ruleVariables == [scratch_var: 'value']
    }

    def "hub_delete_variable throws when Write tools are disabled"() {
        given:
        settingsMap.enableWrite = false  // central Write master gate refuses on dispatch
        stateMap.ruleVariables = [scratch_var: 'value']

        when:
        script.executeTool('hub_delete_variable', [name: 'scratch_var', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Write tools are disabled')
        stateMap.ruleVariables == [scratch_var: 'value']
    }

    def "hub_delete_variable throws when name is missing"() {
        given:
        enableWrite()

        when:
        script.toolDeleteHubVariable([confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('name is required')
    }

    def "hub_delete_variable throws when variable exists in neither hub nor rule_engine namespace"() {
        given:
        enableWrite()
        script.metaClass.getGlobalVar = { String n -> null }
        stateMap.ruleVariables = [:]

        when:
        script.toolDeleteHubVariable([name: 'nonexistent', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("'nonexistent'")
        ex.message.contains('hub-variables namespace')
        ex.message.contains('rule_engine namespace')
    }

    def "hub_delete_variable throws when ruleVariables map is null and var is not a hub var"() {
        given:
        enableWrite()
        script.metaClass.getGlobalVar = { String n -> null }
        // stateMap.ruleVariables not seeded at all — null check path

        when:
        script.toolDeleteHubVariable([name: 'never_set', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("'never_set'")
    }

    def "hub_delete_variable replaces state.ruleVariables with a filtered copy preserving siblings"() {
        // Pin the filtered-copy implementation; map identity does not establish
        // whether Hubitat persists state at the end of an execution.
        given:
        enableWrite()
        stateMap.ruleVariables = [target_var: 'will-go', sibling: 'stays']
        def originalMapRef = stateMap.ruleVariables

        when:
        script.toolDeleteHubVariable([name: 'target_var', confirm: true])

        then: 'sibling is preserved'
        stateMap.ruleVariables == [sibling: 'stays']

        and: 'state.ruleVariables points to the filtered replacement map'
        !stateMap.ruleVariables.is(originalMapRef)
    }

    // -------- Reference-scan safety (hub_delete_variable refuses to silently break consumers) --------

    def "hub_delete_variable refuses when a child rule app references the variable (no force)"() {
        // A child rule's serialized triggers/conditions/actions contains the variable
        // name. Without force=true, deletion would null-out the consumer's lookup and
        // silently break the rule. The tool must surface the breakage and require opt-in.
        given:
        enableWrite()
        stateMap.ruleVariables = [shared_var: 'in-use']

        and: 'a child rule that references shared_var in a condition'
        def consumer = new support.TestChildApp(id: 99L, label: 'Rule Using Shared Var')
        consumer.ruleData = [
            triggers: [],
            conditions: [[type: 'variable', name: 'shared_var', operator: '=', value: 'on']],
            actions: []
        ]
        childAppsList << consumer

        when:
        script.toolDeleteHubVariable([name: 'shared_var', confirm: true])

        then: 'refused with consumer details so the caller knows what would break'
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("'shared_var'")
        // Count-aware singular/plural: 1 consumer renders as "1 rule" (no parenthetical).
        ex.message.contains('1 rule:')
        ex.message.contains('Rule Using Shared Var')
        ex.message.contains('id=99')
        ex.message.contains('force=true')

        and: 'the variable is still present — refusal must not partially mutate state'
        stateMap.ruleVariables == [shared_var: 'in-use']
    }

    // W-spec-hub_delete_variable-plural: the singular spec above only pins "1 rule:"; the
    // count-aware ternary must also be exercised on the plural side or a regression
    // that drops the discrimination (always emits "rule:") would pass on 1 consumer
    // and silently break the 2+ case.
    // Both-ways pending (orchestrator).
    def "hub_delete_variable refuses when 2 child rule apps reference the variable (plural)"() {
        given:
        enableWrite()
        stateMap.ruleVariables = [shared_var: 'in-use']

        and: 'two child rules each referencing shared_var'
        def c1 = new support.TestChildApp(id: 101L, label: 'Rule One')
        c1.ruleData = [
            triggers: [],
            conditions: [[type: 'variable', name: 'shared_var', operator: '=', value: 'on']],
            actions: []
        ]
        def c2 = new support.TestChildApp(id: 102L, label: 'Rule Two')
        c2.ruleData = [
            triggers: [[type: 'variable_change', variable: 'shared_var']],
            conditions: [],
            actions: []
        ]
        childAppsList << c1
        childAppsList << c2

        when:
        script.toolDeleteHubVariable([name: 'shared_var', confirm: true])

        then: 'refused with the plural form ("2 rules:") and both consumer IDs surfaced'
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("'shared_var'")
        ex.message.contains('2 rules:')
        !ex.message.contains('2 rule:')
        ex.message.contains('id=101')
        ex.message.contains('id=102')
        // And the count-aware "those rules" phrase on the breakage hint too.
        ex.message.contains('those rules')
    }

    def "hub_delete_variable proceeds with force=true and reports brokenConsumers"() {
        given:
        enableWrite()
        stateMap.ruleVariables = [shared_var: 'in-use']
        def consumer = new support.TestChildApp(id: 42L, label: 'Acknowledged Breakage')
        consumer.ruleData = [
            triggers: [[type: 'variable_change', variable: 'shared_var']],
            conditions: [],
            actions: []
        ]
        childAppsList << consumer

        when:
        def result = script.toolDeleteHubVariable([name: 'shared_var', confirm: true, force: true])

        then: 'deletion proceeds'
        result.success == true
        result.deleted == true
        stateMap.ruleVariables == [:]

        and: 'response surfaces the consumers that are now broken'
        result.brokenConsumers?.size() == 1
        result.brokenConsumers[0].id == 42L
        result.brokenConsumers[0].label == 'Acknowledged Breakage'
    }

    def "hub_delete_variable proceeds normally when no child rules reference the variable"() {
        given: 'a child rule that does NOT mention the variable being deleted'
        enableWrite()
        stateMap.ruleVariables = [orphan_var: 'safe-to-delete']
        def unrelated = new support.TestChildApp(id: 7L, label: 'Unrelated Rule')
        unrelated.ruleData = [
            triggers: [[type: 'device_event', deviceId: '1', attribute: 'switch']],
            conditions: [],
            actions: []
        ]
        childAppsList << unrelated

        when:
        def result = script.toolDeleteHubVariable([name: 'orphan_var', confirm: true])

        then: 'no force needed, no consumers reported'
        result.success == true
        result.deleted == true
        result.brokenConsumers == null
    }

    // -------- toolCreateVariable validation (issue #92) --------

    def "hub_create_variable relays the hub's own refusal of a name"() {
        given:
        enableWrite()
        script.metaClass.getGlobalVar = { String n -> null }
        script.metaClass.hubInternalPostJson = { String path, String json, int timeout = 420 ->
            throw new HubAnswer(400, NAME_REFUSAL)
        }

        when:
        def result = script.toolCreateVariable([name: 'has[brackets]', type: 'String', value: 'x', confirm: true])

        then: 'nothing local second-guesses the hub, and its rules reach the caller'
        result.success == false
        result.error == 'hub_create_variable: Hub Variables create failed: Use a name of 1-255 characters without leading/trailing spaces, control characters, or quotes, backslashes, ~, [, ], :, <, >.'
        result.note.startsWith('Nothing was changed')
    }

    def "hub_create_variable refuses a blank name before any request"() {
        given:
        enableWrite()
        stubHubVarsPage()

        when:
        script.toolCreateVariable([name: '   ', type: 'String', value: 'x', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message == 'Variable name is required'
        varPosts.isEmpty()
    }

    def "hub_create_variable with present-but-null mesh keys takes the create path, not the link form"() {
        given: 'null mesh keys alongside a normal single create -- must NOT divert to link nor throw "not both"'
        enableWrite()
        def stored = stubBulkCreate()

        when:
        def result = script.toolCreateVariable([name: 'vacationMode', type: 'String', value: 'off',
            mesh_source_hub_id: null, mesh_source_name: null, confirm: true])

        then: 'it chose the normal create path: one create POST, no link GET'
        result.success == true
        varPosts*.path == ['/hub2/variables/create']
        stored.vacationMode.value == 'off'
        !hubGet.calls.any { it.key?.toString()?.startsWith('/hub2/createLinkedHubVar/') }
    }

    def "hub_create_variable rejects unknown type"() {
        given:
        enableWrite()

        when:
        script.toolCreateVariable([name: 'goodName', type: 'NotAType', value: 'x', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('NotAType')
        ex.message.contains('Number, Decimal, String, Boolean, DateTime')
    }

    def "hub_create_variable rejects null/missing initial value"() {
        given:
        enableWrite()

        when:
        script.toolCreateVariable([name: 'goodName', type: 'String', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Initial value is required')
    }

    def "hub_create_variable rejects an empty-string value for a String variable"() {
        // A String variable created with an empty value reports success but never
        // persists (getGlobalVar stays null -- deterministic). Reject up front.
        given:
        enableWrite()

        when:
        script.toolCreateVariable([name: 'emptyStr', type: 'String', value: '', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('non-empty initial value')
    }

    def "hub_create_variable refuses to overwrite an existing hub variable"() {
        given:
        enableWrite()
        script.metaClass.getGlobalVar = { String n ->
            n == 'taken' ? [name: 'taken', type: 'String', value: 'already here', deviceId: null, attribute: null] : null
        }

        when:
        script.toolCreateVariable([name: 'taken', type: 'String', value: 'new', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("already exists")
        ex.message.contains('set_variable')
    }

    def "hub_create_variable requires confirm"() {
        when:
        script.toolCreateVariable([name: 'goodName', type: 'String', value: 'x'])

        then:
        thrown(IllegalArgumentException)
    }

    // -------- toolCreateConnector / toolRemoveConnector validation --------

    def "hub_create_connector throws when the variable does not exist"() {
        given:
        enableWrite()
        script.metaClass.getGlobalVar = { String n -> null }

        when:
        script.toolCreateConnector([name: 'missing', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('does not exist')
    }

    def "hub_create_connector is a no-op when a connector already exists"() {
        given:
        enableWrite()
        script.metaClass.getGlobalVar = { String n ->
            [name: 'has_conn', type: 'Boolean', value: true, deviceId: 305, attribute: 'switch']
        }

        when:
        def result = script.toolCreateConnector([name: 'has_conn', confirm: true])

        then:
        result.success == true
        result.alreadyExists == true
        result.deviceId == 305
    }

    def "hub_delete_connector throws when the variable does not exist"() {
        given:
        enableWrite()
        script.metaClass.getGlobalVar = { String n -> null }

        when:
        script.toolRemoveConnector([name: 'missing', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('does not exist')
    }

    def "hub_delete_connector is a no-op when no connector exists"() {
        given:
        enableWrite()
        script.metaClass.getGlobalVar = { String n ->
            [name: 'no_conn', type: 'String', value: 'x', deviceId: null, attribute: null]
        }

        when:
        def result = script.toolRemoveConnector([name: 'no_conn', confirm: true])

        then:
        result.success == true
        result.alreadyRemoved == true
    }

    // -------- hub_create_variable through the Hub Variables page --------

    // A DateTime create whose read-back holds what the create POST committed; setGlobalVar records
    // each correction.
    private Map stubDateTimeCreate(Map opts) {
        def rec = [sets: [], stored: null, getCalls: 0]
        script.metaClass.getGlobalVar = { String n ->
            rec.getCalls++
            if (rec.getCalls == 1) return null   // pre-flight: the name is free
            return rec.stored == null ? null : [name: n, type: 'datetime', value: rec.stored]
        }
        script.metaClass.setGlobalVar = { String n, v -> rec.sets << v; if (opts.setterWorks != false) rec.stored = v; true }
        stubHubVarsPage { path, body -> rec.stored = opts.postCommits }
        return rec
    }

    def "hub_create_variable DateTime sends the date and time in its one create POST"() {
        given:
        enableWrite()
        def rec = stubDateTimeCreate(postCommits: '2026-01-01T12:30:00.000-0500')

        when:
        def result = script.toolCreateVariable([name: 'dtVar', type: 'DateTime', value: '2026-01-01T12:30:00.000-0500', confirm: true])

        then:
        varPosts*.path == ['/hub2/variables/create']
        varPosts[0].body.type == 'datetime'
        varPosts[0].body.mode == 'datetime'
        varPosts[0].body.date == '2026-01-01'
        varPosts[0].body.time == '12:30'
        rec.sets.isEmpty()
        rec.getCalls == 2
        result.success == true
        result.value == '2026-01-01T12:30:00.000-0500'
    }

    @spock.lang.Unroll
    def "hub_create_variable DateTime makes a #kind variable from #input"() {
        given:
        enableWrite()
        def rec = stubDateTimeCreate(postCommits: stored)

        when:
        def result = script.toolCreateVariable([name: 'dtVar', type: 'DateTime', value: input, confirm: true])

        then: 'the body the hub page posts for that kind, and a matching read-back'
        varPosts*.body == [body]
        result.success == true
        result.value == stored
        result.dateTimeKind == kind
        rec.sets.isEmpty()

        where:
        kind   | input                  | body                                                                                 | stored
        'date' | '2026-03-05'           | [name: 'dtVar', type: 'datetime', mode: 'date', date: '2026-03-05', value: '2026-03-05'] | '2026-03-05T99:99:99.999-9999'
        'date' | [date: '2026-03-05']   | [name: 'dtVar', type: 'datetime', mode: 'date', date: '2026-03-05', value: '2026-03-05'] | '2026-03-05T99:99:99.999-9999'
        'time' | '08:15'                | [name: 'dtVar', type: 'datetime', mode: 'time', time: '08:15', value: '08:15']          | '9999-99-99T08:15:00.000-0500'
        'time' | [time: '08:15:42']     | [name: 'dtVar', type: 'datetime', mode: 'time', time: '08:15', value: '08:15']          | '9999-99-99T08:15:00.000-0500'
    }

    @spock.lang.Unroll
    def "hub_create_variable DateTime fails when the read-back is not the requested #kind kind"() {
        given:
        enableWrite()
        stubDateTimeCreate(postCommits: stored)

        when:
        def result = script.toolCreateVariable([name: 'dtVar', type: 'DateTime', value: input, confirm: true])

        then:
        result.success == false
        result.error.contains("'dtVar' exists, but reads type=datetime, value=${stored}")

        where:
        kind   | input        | stored
        'date' | '2026-03-05' | '2026-03-05T08:15:00.000-0500'
        'time' | '08:15'      | '9999-99-99T09:00:00.000-0500'
    }

    @spock.lang.Unroll
    def "hub_create_variable DateTime sets the full hub-local value when the commit dropped the time (#input)"() {
        given:
        enableWrite()
        def rec = stubDateTimeCreate(postCommits: '2026-01-01T99:99:99.999-9999')

        when:
        def result = script.toolCreateVariable([name: 'dtVar', type: 'DateTime', value: input, confirm: true])

        then:
        rec.sets.size() == 1
        rec.sets[0] ==~ /2026-01-01T12:30:00\.000[+-]\d{4}/
        result.value == rec.sets[0]

        where:
        input << ['2026-01-01T12:30', [date: '2026-01-01', time: '12:30']]
    }

    @spock.lang.Unroll
    def "hub_create_variable DateTime refuses invalid date or time #input before any POST"() {
        given:
        enableWrite()
        def rec = stubDateTimeCreate([:])

        when:
        script.toolCreateVariable([name: 'dtVar', type: 'DateTime', value: input, confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('requires a valid date and time')
        varPosts.isEmpty()
        rec.sets.isEmpty()

        where:
        input << ['2026-13-45T12:30', '2026-01-01T12:30garbage', '2026-01-01T12:30:99',
                  '2026-01-01T24:30', '2026-01-01T12:60', '2026-01-01T12:30:00+25:00',
                  '2026-01-01T12:30:00.123Zjunk', [date: '2026-01-01', time: '12:30garbage'],
                  '2026-02-30', '2026-1-5', '24:00', '7:05', [date: '2026-02-30'], [time: '12:61'], [:], 'garbage']
    }

    @spock.lang.Unroll
    def "hub_create_variable DateTime correction uses hub zone #zoneId for #input"() {
        given:
        enableWrite()
        sharedLocation.timeZone = TimeZone.getTimeZone(zoneId)
        def originalZone = TimeZone.default
        TimeZone.setDefault(TimeZone.getTimeZone('UTC'))
        def rec = stubDateTimeCreate(postCommits: '2026-01-01T99:99:99.999-9999')

        when:
        def result = script.toolCreateVariable([name: 'dtVar', type: 'DateTime', value: input, confirm: true])

        then:
        result.success == true
        rec.sets == [expected]
        result.value == expected

        cleanup:
        TimeZone.setDefault(originalZone)

        where:
        zoneId             | input                                  | expected
        'America/New_York' | '2026-01-01T12:30:45.123Z'              | '2026-01-01T12:30:00.000-0500'
        'America/New_York' | '2026-07-04 18:05:00+02:00'            | '2026-07-04T18:05:00.000-0400'
        'Asia/Kathmandu'   | [date: '2026-01-01', time: '12:30']     | '2026-01-01T12:30:00.000+0545'
        'America/New_York' | '2026-01-01T12:30:00-0500'             | '2026-01-01T12:30:00.000-0500'
    }

    def "hub_create_variable DateTime reports a created variable whose time could not be set"() {
        given:
        enableWrite()
        stubDateTimeCreate(postCommits: '2026-01-01T99:99:99.999-9999', setterWorks: false)

        when:
        def result = script.toolCreateVariable([name: 'dtVar', type: 'DateTime', value: '2026-01-01T12:30', confirm: true])

        then:
        result.success == false
        result.isError == true
        result.error.contains("'dtVar' was created, but its time could not be set")
        result.note.contains('may have applied')
    }

    @spock.lang.Unroll
    def "hub_create_variable reports the hub's own reason for a refused create (#label)"() {
        given:
        enableWrite()
        script.metaClass.getGlobalVar = { String n -> null }
        script.metaClass.hubInternalPostJson = { String path, String json, int timeout = 420 ->
            throw new HubAnswer(status, data)
        }

        when:
        def result = script.toolCreateVariable([name: 'n1', type: 'Number', value: 'abc', confirm: true])

        then:
        result.success == false
        result.isError == true
        result.error == 'hub_create_variable: Hub Variables create failed: The value does not match the variable type.'
        result.note.startsWith(note)

        where:
        label          | status | data                                                              | note
        'JSON text'    | 400    | '{"message":"The value does not match the variable type."}'       | 'Nothing was changed'
        'parsed map'   | 400    | [message: 'The value does not match the variable type.']          | 'Nothing was changed'
        'server error' | 500    | '{"message":"The value does not match the variable type."}'       | 'The hub may have applied'
    }

    // getGlobalVar answers from `stored`, which each create POST fills with the requested value in its
    // stored type, so every item reads back its own name/type/value.
    private Map stubBulkCreate(Set neverStored = [] as Set) {
        Map stored = [:]
        script.metaClass.getGlobalVar = { String n -> stored[n] }
        stubHubVarsPage { path, body ->
            if (body.name.toString().contains('[')) throw new HubAnswer(400, NAME_REFUSAL)
            if (!(body.name in neverStored)) {
                def v = body.type == 'integer' ? body.value.toInteger() : body.type == 'bigdecimal' ? body.value.toBigDecimal() :
                    body.type == 'boolean' ? body.value.toBoolean() : body.value
                stored[body.name] = [name: body.name, type: body.type, value: v, deviceId: null, attribute: null]
            }
        }
        return stored
    }

    def "hub_create_variable bulk form creates every item and reports per-item success"() {
        given:
        enableWrite()
        stubBulkCreate()

        when:
        def result = script.toolCreateVariable([confirm: true, variables: [
            [name: 'bulkA', type: 'Number', value: 1],
            [name: 'bulkB', type: 'String', value: 'two']
        ]])

        then: 'overall success with both items created'
        result.success == true
        result.createdCount == 2
        result.failedCount == 0

        and: 'each result carries ITS OWN name/type/value -- a regression that wrote item-0 for every entry would fail here'
        result.results == [[name: 'bulkA', success: true, type: 'Number', value: 1],
                           [name: 'bulkB', success: true, type: 'String', value: 'two']]

        and: 'one create POST per item, and no page read: a create needs none'
        varPosts*.body == [[name: 'bulkA', type: 'integer', value: '1'], [name: 'bulkB', type: 'string', value: 'two']]
        !hubGet.calls.any { it.path == '/hub2/variables' }
    }

    def "hub_create_variable bulk form: one bad item fails while the others still create"() {
        given:
        enableWrite()
        stubBulkCreate()

        when: 'the hub refuses the middle item\'s name'
        def result = script.toolCreateVariable([confirm: true, variables: [
            [name: 'goodOne', type: 'Number', value: 1],
            [name: 'bad[name]', type: 'Number', value: 2],
            [name: 'goodTwo', type: 'Number', value: 3]
        ]])

        then: 'overall failure, but the two good items still created'
        result.success == false
        result.createdCount == 2
        result.failedCount == 1
        result.results[0].success == true
        result.results[1].name == 'bad[name]'
        result.results[1].success == false
        result.results[1].error.endsWith('Use a name of 1-255 characters without leading/trailing spaces, control characters, or quotes, backslashes, ~, [, ], :, <, >.')
        result.results[2].success == true
        varPosts*.body.name == ['goodOne', 'bad[name]', 'goodTwo']
    }

    def "hub_create_variable bulk form: a non-Map item fails only itself while the others still create"() {
        given:
        enableWrite()
        stubBulkCreate()

        when: 'the middle item is a bare integer, not a {name,type,value} object'
        def result = script.toolCreateVariable([confirm: true, variables: [
            [name: 'goodOne', type: 'Number', value: 1],
            42,
            [name: 'goodTwo', type: 'Number', value: 3]
        ]])

        then: 'the malformed item is isolated to its own entry -- both good items still create'
        result.success == false
        result.createdCount == 2
        result.failedCount == 1
        result.results[1].success == false
        result.results[1].name == 'variables[1]'
        result.results[1].error.contains('must be an object')
        result.results[2].name == 'goodTwo'
        result.results[2].success == true
    }

    def "hub_create_variable bulk form: an unknown key is named before any missing field"() {
        given:
        enableWrite()
        stubBulkCreate()

        when: 'the second item misspells name, so it also lacks one'
        def result = script.toolCreateVariable([confirm: true, variables: [
            [name: 'goodOne', type: 'Number', value: 1],
            [nmae: 'typo', type: 'Number', value: 2]
        ]])

        then: 'the typo is named, not reported as a missing name, and only the good item was sent'
        result.success == false
        result.createdCount == 1
        result.results[1].name == 'variables[1]'
        result.results[1].error == 'variables[1] has unknown key(s): nmae. Each item takes name, type and value.'
        varPosts*.body.name == ['goodOne']
    }

    def "hub_create_variable bulk form: an empty variables array is a batch-level rejection"() {
        given:
        enableWrite()

        when: 'variables is present but empty -- nothing to create'
        script.toolCreateVariable([confirm: true, variables: []])

        then: 'rejected at the batch level (a non-empty List is a genuine precondition, not a per-item failure)'
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('non-empty')
    }

    def "hub_create_variable bulk form: a per-item read-back failure is isolated, others still create"() {
        given: 'the hub accepts every POST, but timesOut never becomes readable'
        enableWrite()
        stubBulkCreate(['timesOut'] as Set)

        when:
        def result = script.toolCreateVariable([confirm: true, variables: [
            [name: 'createsA', type: 'Number', value: 1],
            [name: 'timesOut', type: 'Number', value: 2],
            [name: 'createsB', type: 'Number', value: 3]
        ]])

        then: 'the failed read-back is isolated to its own entry -- the other two still create'
        result.success == false
        result.createdCount == 2
        result.failedCount == 1
        result.results[1].name == 'timesOut'
        result.results[1].success == false
        result.results[1].error.contains('not visible via getGlobalVar')
        result.results[2].success == true
    }

    def "hub_create_variable rejects supplying both the single form and the bulk array"() {
        given:
        enableWrite()

        when:
        script.toolCreateVariable([name: 'single', type: 'Number', value: 1, confirm: true,
                                   variables: [[name: 'bulk', type: 'Number', value: 2]]])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('not both')
    }

    // -------- toolCreateVariable Hub Mesh linking --------

    def "hub_create_variable links a mesh variable and confirms via the localLinkedHubVariables read-back"() {
        given:
        enableWrite()
        def linked = []
        hubGet.register('/hub2/createLinkedHubVar/HUB-A/porchTemp') { params -> linked << 'porchTemp'; '' }
        // Real localLinkedHubVariables rows carry a DECORATED display name plus the bare sourceVarName
        // and sourceHubId; the read-back must match the latter two, not `name`.
        hubGet.register('/hub2/hubMeshJson') { params ->
            groovy.json.JsonOutput.toJson([localLinkedHubVariables: linked.collect {
                [name: "${it} on Peer", sourceVarName: it, sourceHubId: 'HUB-A', type: 'string'] }])
        }

        when:
        def result = script.toolCreateVariable([mesh_source_hub_id: 'HUB-A', mesh_source_name: 'porchTemp', confirm: true])

        then: 'name is the DECORATED local name (feed-able to hub_get/delete_variable); the bare source name is sourceName'
        hubGet.calls.any { it.key == '/hub2/createLinkedHubVar/HUB-A/porchTemp' }
        result.success == true
        result.name == 'porchTemp on Peer'
        result.sourceName == 'porchTemp'
        result.sourceHubId == 'HUB-A'
        result.linked == true
        result.warnings == null
    }

    def "hub_create_variable mesh link passes the name segment LITERALLY (not pre-encoded -- the platform layer encodes the path)"() {
        given: 'a non-ASCII name, a FRESH link (offered before, mirror appears after): the path segment reaches the HTTP layer literal, since pre-encoding double-encodes'
        enableWrite()
        def done = false
        hubGet.register('/hub2/createLinkedHubVar/HUB-A/café') { params -> done = true; '' }
        hubGet.register('/hub2/hubMeshJson') { params ->
            done ? '{"localLinkedHubVariables":[{"name":"café on Peer","sourceVarName":"café","sourceHubId":"HUB-A"}],"availableLinkedHubVariables":[]}'
                 : '{"localLinkedHubVariables":[],"availableLinkedHubVariables":[{"hubId":"HUB-A","name":"café","linkedLocally":false}]}'
        }

        when:
        def result = script.toolCreateVariable([mesh_source_hub_id: 'HUB-A', mesh_source_name: 'café', confirm: true])

        then:
        hubGet.calls.any { it.key == '/hub2/createLinkedHubVar/HUB-A/café' }
        result.success == true
    }

    // Item: linking an already-linked source must NOT issue a fresh GET nor claim a fresh success --
    // it returns alreadyLinked:true with the existing local mirror name resolved.
    def "hub_create_variable mesh link short-circuits with alreadyLinked when the source is already linked (no GET)"() {
        given: 'the available row is already flagged linkedLocally:true and a local mirror exists'
        enableWrite()
        hubGet.register('/hub2/createLinkedHubVar/HUB-A/porchTemp') { params -> '' }
        hubGet.register('/hub2/hubMeshJson') { params ->
            groovy.json.JsonOutput.toJson([
                availableLinkedHubVariables: [[hubId: 'HUB-A', name: 'porchTemp', hubName: 'Peer', linkedLocally: true]],
                localLinkedHubVariables: [[name: 'porchTemp on Peer', sourceVarName: 'porchTemp', sourceHubId: 'HUB-A']]
            ])
        }

        when:
        def result = script.toolCreateVariable([mesh_source_hub_id: 'HUB-A', mesh_source_name: 'porchTemp', confirm: true])

        then: 'reports already linked, resolves the decorated mirror name, and never re-issues the link GET'
        result.success == true
        result.alreadyLinked == true
        result.name == 'porchTemp on Peer'
        result.sourceName == 'porchTemp'
        result.linked == true
        !hubGet.calls.any { it.key == '/hub2/createLinkedHubVar/HUB-A/porchTemp' }
    }

    def "hub_create_variable mesh link reports alreadyLinked (not 'not offered') when the source left availableLinkedHubVariables but a local mirror exists"() {
        given: 'a linked source LEAVES availableLinkedHubVariables; the local mirror is the only evidence'
        enableWrite()
        hubGet.register('/hub2/createLinkedHubVar/HUB-A/porchTemp') { params -> '' }
        hubGet.register('/hub2/hubMeshJson') { params ->
            groovy.json.JsonOutput.toJson([
                availableLinkedHubVariables: [],
                localLinkedHubVariables: [[name: 'porchTemp on Peer', sourceVarName: 'porchTemp', sourceHubId: 'HUB-A']]
            ])
        }

        when:
        def result = script.toolCreateVariable([mesh_source_hub_id: 'HUB-A', mesh_source_name: 'porchTemp', confirm: true])

        then: 'recognized as already linked via the local mirror, not rejected as unoffered; no fresh GET'
        result.success == true
        result.alreadyLinked == true
        result.name == 'porchTemp on Peer'
        result.sourceName == 'porchTemp'
        !hubGet.calls.any { it.key == '/hub2/createLinkedHubVar/HUB-A/porchTemp' }
    }

    def "hub_create_variable mesh link alreadyLinked warns when the local mirror name cannot be resolved"() {
        given: 'the available row shows linkedLocally:true but no local row surfaces'
        enableWrite()
        hubGet.register('/hub2/createLinkedHubVar/HUB-A/porchTemp') { params -> '' }
        hubGet.register('/hub2/hubMeshJson') { params ->
            groovy.json.JsonOutput.toJson([
                availableLinkedHubVariables: [[hubId: 'HUB-A', name: 'porchTemp', hubName: 'Peer', linkedLocally: true]],
                localLinkedHubVariables: []
            ])
        }

        when:
        def result = script.toolCreateVariable([mesh_source_hub_id: 'HUB-A', mesh_source_name: 'porchTemp', confirm: true])

        then:
        result.success == true
        result.alreadyLinked == true
        result.name == 'porchTemp'
        result.warnings != null
        !hubGet.calls.any { it.key == '/hub2/createLinkedHubVar/HUB-A/porchTemp' }
    }

    // Outcome 1 (linked) via the availableLinkedHubVariables row flag, even when the name has not yet
    // surfaced in localLinkedHubVariables.
    def "hub_create_variable mesh link confirms via the availableLinkedHubVariables linkedLocally flag (outcome: linked)"() {
        given:
        enableWrite()
        def done = false
        hubGet.register('/hub2/createLinkedHubVar/HUB-A/porchTemp') { params -> done = true; '' }
        hubGet.register('/hub2/hubMeshJson') { params ->
            groovy.json.JsonOutput.toJson([
                localLinkedHubVariables: [],
                availableLinkedHubVariables: [[hubId: 'HUB-A', name: 'porchTemp', hubName: 'Peer', linkedLocally: done]]
            ])
        }

        when:
        def result = script.toolCreateVariable([mesh_source_hub_id: 'HUB-A', mesh_source_name: 'porchTemp', confirm: true])

        then: 'linked via the linkedLocally flag, but no local row resolved -> name falls back to the source name WITH a caution that it may not identify the mirror'
        result.success == true
        result.name == 'porchTemp'
        result.sourceName == 'porchTemp'
        result.sourceHubId == 'HUB-A'
        result.linked == true
        result.warnings != null
        result.warnings.any { it.toLowerCase().contains('mirror name') }
    }

    def "hub_create_variable mesh link warns when the source row disappears but no local mirror resolves"() {
        given: 'the source is offered pre-call, then leaves availableLinkedHubVariables after the link, with no local row surfacing'
        enableWrite()
        def done = false
        hubGet.register('/hub2/createLinkedHubVar/HUB-A/porchTemp') { params -> done = true; '' }
        hubGet.register('/hub2/hubMeshJson') { params ->
            groovy.json.JsonOutput.toJson([
                localLinkedHubVariables: [],
                availableLinkedHubVariables: done ? [] : [[hubId: 'HUB-A', name: 'porchTemp', hubName: 'Peer', linkedLocally: false]]
            ])
        }

        when:
        def result = script.toolCreateVariable([mesh_source_hub_id: 'HUB-A', mesh_source_name: 'porchTemp', confirm: true])

        then: 'row-disappeared (offered pre-call) proves the link, but the bare source name comes back with a mirror-name caution'
        result.success == true
        result.linked == true
        result.name == 'porchTemp'
        result.sourceName == 'porchTemp'
        result.warnings != null
        result.warnings.any { it.toLowerCase().contains('mirror name') }
    }

    // Outcome 2 (silent no-op): 200 from the GET but the source stays in availableLinkedHubVariables
    // with linkedLocally:false and the name never surfaces locally -> FAILURE, not a warning.
    def "hub_create_variable mesh link FAILS on a silent no-op (source stays unlinked)"() {
        given:
        enableWrite()
        hubGet.register('/hub2/createLinkedHubVar/HUB-A/porchTemp') { params -> '' }
        hubGet.register('/hub2/hubMeshJson') { params ->
            groovy.json.JsonOutput.toJson([
                localLinkedHubVariables: [],
                availableLinkedHubVariables: [[hubId: 'HUB-A', name: 'porchTemp', hubName: 'Peer', linkedLocally: false]]
            ])
        }

        when:
        def result = script.toolCreateVariable([mesh_source_hub_id: 'HUB-A', mesh_source_name: 'porchTemp', confirm: true])

        then:
        result.success == false
        result.isError == true
        result.error.contains('did not take')
        and: 'the note leads with the transient re-establish/retry guidance, with the peer-token store as the persistent-cause fallback'
        result.note.toLowerCase().contains('retry')
        result.note.toLowerCase().contains('re-establish')
        result.note.contains('mesh token')
        result.note.contains('hub_update_hub_mesh(peer_hub_id, peer_token)')
    }

    // Outcome 3 (unresolvable): the link GET is accepted but the mesh list is unreadable -- we can
    // prove neither linked nor not-linked -> warn, don't fail.
    def "hub_create_variable mesh link warns (not fails) when the mesh list is unreadable"() {
        given:
        enableWrite()
        hubGet.register('/hub2/createLinkedHubVar/HUB-A/porchTemp') { params -> '' }
        hubGet.register('/hub2/hubMeshJson') { params -> throw new RuntimeException('mesh JSON unreachable') }

        when:
        def result = script.toolCreateVariable([mesh_source_hub_id: 'HUB-A', mesh_source_name: 'porchTemp', confirm: true])

        then:
        result.success == true
        result.warnings != null
        result.warnings.any { it.contains('could not confirm') }
    }

    // A throw with an unreadable read-back is an UNKNOWN outcome, not a definite failure: it MAY have
    // applied, so the note steers to verification rather than claiming it did not happen.
    def "hub_create_variable mesh link reports an unknown (may-have-applied) outcome when the GET throws and the mesh list is unreadable"() {
        given:
        enableWrite()
        hubGet.register('/hub2/createLinkedHubVar/HUB-A/porchTemp') { params -> throw new RuntimeException('Hub API 500') }
        hubGet.register('/hub2/hubMeshJson') { params -> throw new RuntimeException('mesh JSON unreachable') }

        when:
        def result = script.toolCreateVariable([mesh_source_hub_id: 'HUB-A', mesh_source_name: 'porchTemp', confirm: true])

        then:
        result.success == false
        result.isError == true
        result.error.contains('linking the shared variable')
        result.note.contains('MAY have applied')
        result.note.contains('hub_get_hub_mesh')
    }

    def "hub_create_variable mesh link FAILS definitively when the GET throws but the read-back proves it did not link"() {
        given:
        enableWrite()
        hubGet.register('/hub2/createLinkedHubVar/HUB-A/porchTemp') { params -> throw new RuntimeException('Hub API 500') }
        hubGet.register('/hub2/hubMeshJson') { params ->
            groovy.json.JsonOutput.toJson([
                localLinkedHubVariables: [],
                availableLinkedHubVariables: [[hubId: 'HUB-A', name: 'porchTemp', hubName: 'Peer', linkedLocally: false]]
            ])
        }

        when:
        def result = script.toolCreateVariable([mesh_source_hub_id: 'HUB-A', mesh_source_name: 'porchTemp', confirm: true])

        then:
        result.success == false
        result.isError == true
        result.error.contains('did not take')
    }

    def "hub_create_variable mesh link rejects mixing with a create field"() {
        given:
        enableWrite()

        when:
        script.toolCreateVariable([mesh_source_hub_id: 'HUB-A', mesh_source_name: 'porchTemp', name: 'x', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('not both')
    }

    def "hub_create_variable mesh link requires BOTH hub id and name"() {
        given:
        enableWrite()

        when:
        script.toolCreateVariable([mesh_source_hub_id: 'HUB-A', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('BOTH mesh_source_hub_id and mesh_source_name')
    }

    // A readable availableLinkedHubVariables list that does NOT offer the pair (typo/stale/peer stopped
    // sharing) must throw BEFORE the createLinkedHubVar GET -- validation-before-side-effect -- rather
    // than let the absent source row read as a false success.
    def "hub_create_variable mesh link throws (no GET) when the pair is not offered in a readable available list"() {
        given: 'the available list is readable but offers a DIFFERENT variable, not HUB-A/porchTemp'
        enableWrite()
        hubGet.register('/hub2/createLinkedHubVar/HUB-A/porchTemp') { params -> '' }
        hubGet.register('/hub2/hubMeshJson') { params ->
            groovy.json.JsonOutput.toJson([
                localLinkedHubVariables: [],
                availableLinkedHubVariables: [[hubId: 'HUB-A', name: 'otherVar', hubName: 'Peer', linkedLocally: false]]
            ])
        }

        when:
        script.toolCreateVariable([mesh_source_hub_id: 'HUB-A', mesh_source_name: 'porchTemp', confirm: true])

        then: 'rejected as a validation error and the createLinkedHubVar GET was never called'
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('availableLinkedHubVariables')
        !hubGet.calls.any { it.key == '/hub2/createLinkedHubVar/HUB-A/porchTemp' }
    }

    @spock.lang.Unroll
    def "via dispatch: hub_create_variable mesh link passes through (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        hubGet.register('/hub2/createLinkedHubVar/HUB-A/porchTemp') { params -> '' }
        hubGet.register('/hub2/hubMeshJson') { params -> '{"localLinkedHubVariables":[{"name":"porchTemp on Peer","sourceVarName":"porchTemp","sourceHubId":"HUB-A"}]}' }

        when:
        def response = mcpDriver.callTool('hub_create_variable',
            [mesh_source_hub_id: 'HUB-A', mesh_source_name: 'porchTemp', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.name == 'porchTemp on Peer'
        inner.sourceName == 'porchTemp'

        where:
        useGateways << [true, false]
    }

    // -------- hub_delete_variable through the Hub Variables page --------

    // A hub variable `name` that the remove POST deletes; its page row carries the in-use registry.
    private void stubDeletableHubVar(String name, Map rowExtra = [:]) {
        boolean removed = false
        script.metaClass.getGlobalVar = { String n ->
            (n == name && !removed) ? [name: name, type: 'integer', value: 5, deviceId: null, attribute: null] : null
        }
        modelRows[name] = modelRow(name, rowExtra)
        stubHubVarsPage { path, body -> if (path == '/hub2/variables/remove') removed = true }
    }

    def "hub_delete_variable sends one remove POST for an unused hub variable"() {
        given:
        enableWrite()
        stubDeletableHubVar('condemned')
        def logs = []
        script.metaClass.mcpLog = { String level, String component, String msg -> logs << msg }

        when:
        def result = script.toolDeleteHubVariable([name: 'condemned', confirm: true])

        then:
        varPosts == [[path: '/hub2/variables/remove', body: [name: 'condemned', acknowledgeUse: false]]]
        result.success == true
        result.source == 'hub'
        result.deleted == true
        result.name == 'condemned'
        result.platformInUse == false
        result.platformInUseError == null
        result.coverageNote.contains('webCoRE')

        and: "an unforced delete of an unused variable carries no forced note"
        logs.any { it.contains("removed hub var 'condemned'") && !it.contains("forced") }
    }

    @spock.lang.Unroll
    def "hub_delete_variable hub-namespace: registry #state without force refuses before any POST"() {
        given:
        enableWrite()
        stubDeletableHubVar('shared', [usedBy: usedBy])

        when:
        script.toolDeleteHubVariable([name: 'shared', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("'shared'")
        ex.message.contains(phrase)
        ex.message.contains('force=true')
        varPosts.isEmpty()

        where:
        state        | usedBy                                  | phrase
        'in use'     | [[id: 21, label: 'Porch Rule']]          | 'is in use by Porch Rule (id=21)'
        'unreadable' | null                                    | 'is unknown'
    }

    @spock.lang.Unroll
    def "hub_delete_variable hub-namespace: force=true deletes when the registry is #state and says so"() {
        given:
        enableWrite()
        stubDeletableHubVar('shared', [usedBy: usedBy])
        def logs = []
        script.metaClass.mcpLog = { String level, String component, String msg -> logs << msg }

        when:
        def result = script.toolDeleteHubVariable([name: 'shared', confirm: true, force: true])

        then:
        result.success == true
        result.deleted == true
        result.platformInUse == inUse
        varPosts[0].body == [name: 'shared', acknowledgeUse: true]

        and: "the audit line says the delete was forced past the registry"
        logs.any { it.contains("removed hub var 'shared'") && it.contains("(forced; hub in-use registry: ${note}") }

        where:
        state        | usedBy                          | inUse | note
        'in use'     | [[id: 21, label: 'Porch Rule']]  | true  | 'Porch Rule (id=21)'
        'unreadable' | null                            | null  | 'unreadable'
    }

    def "hub_delete_variable reports a variable still readable after the remove as a failure"() {
        given:
        enableWrite()
        script.metaClass.getGlobalVar = { String n -> [name: n, type: 'string', value: 'x', deviceId: null, attribute: null] }
        modelRows.stuck = modelRow('stuck')
        stubHubVarsPage()

        when:
        def result = script.toolDeleteHubVariable([name: 'stuck', confirm: true])

        then: 'one POST, one read-back, and an honest failure'
        varPosts.size() == 1
        result.success == false
        result.isError == true
        result.error.contains("the hub accepted the delete, but 'stuck' still exists")
    }

    // -------- toolDeleteHubVariable Hub Mesh linked-mirror unlink --------
    // A linked mirror is a normal local hub var whose DECORATED name ("<src> on <peer>") is what the
    // caller passes. The hub's generic in-use registry ALWAYS marks a mirror in-use (the mesh link
    // itself), so unlink keys on the mesh row's inUseByApps, not the registry. Mesh rows come from
    // /hub2/hubMeshJson localLinkedHubVariables[].

    private void stubMirror(Map meshRow) {
        stubDeletableHubVar('porchTemp on Peer', [linked: true, sourceName: 'Peer', usedBy: [[id: 1, label: 'Hub Mesh']]])
        hubGet.register('/hub2/hubMeshJson') { params ->
            groovy.json.JsonOutput.toJson([localLinkedHubVariables: [
                [name: 'porchTemp on Peer', sourceVarName: 'porchTemp', sourceHubId: 'HUB-A', sourceHubName: 'Peer', type: 'Number'] + meshRow
            ]])
        }
    }

    def "hub_delete_variable unlinks a Hub Mesh mirror (inUseByApps=false) without force, bypassing the generic in-use guard"() {
        given: 'the registry marks the mirror in use (the mesh link), but its mesh row reports no real consumer'
        enableWrite()
        stubMirror([inUseByApps: false])

        when:
        def result = script.toolDeleteHubVariable([name: 'porchTemp on Peer', confirm: true])

        then: 'the delete happened, acknowledging the mesh link'
        varPosts == [[path: '/hub2/variables/remove', body: [name: 'porchTemp on Peer', acknowledgeUse: true]]]
        result.success == true
        result.deleted == true
        result.unlinked == true
        result.source == 'hub'

        and: 'the message frames it as an unlink: local copy removed, peer untouched'
        result.note.contains("Unlinked the Hub Mesh variable 'porchTemp on Peer'")
        result.note.contains("removed this hub's local copy")
        result.note.contains('untouched')
    }

    @spock.lang.Unroll
    def "hub_delete_variable refuses to unlink a Hub Mesh mirror whose use is #state without force"() {
        given:
        enableWrite()
        stubMirror(meshRow)

        when:
        script.toolDeleteHubVariable([name: 'porchTemp on Peer', confirm: true])

        then: 'refused before any POST; the message names the mirror nature and why'
        def ex = thrown(IllegalArgumentException)
        varPosts.isEmpty()
        ex.message.contains('linked mirror')
        ex.message.contains('untouched')
        ex.message.contains(phrase)
        ex.message.contains('force=true')

        where:
        state     | meshRow               | phrase
        'real'    | [inUseByApps: true]   | 'real app'
        'unknown' | [:]                   | 'could not confirm'
    }

    def "hub_delete_variable force=true unlinks a Hub Mesh mirror even when a real app uses it (inUseByApps=true)"() {
        given:
        enableWrite()
        stubMirror([inUseByApps: true])

        when:
        def result = script.toolDeleteHubVariable([name: 'porchTemp on Peer', confirm: true, force: true])

        then:
        result.success == true
        result.deleted == true
        result.unlinked == true
    }

    @spock.lang.Unroll
    def "hub_delete_variable keeps the in-use guard for an ordinary hub var when #state"() {
        given: 'an ordinary hub var the registry marks in use'
        enableWrite()
        stubDeletableHubVar('shared', [usedBy: [[id: 21, label: 'Porch Rule']]])
        hubGet.register('/hub2/hubMeshJson', meshAnswer)

        when:
        script.toolDeleteHubVariable([name: 'shared', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('is in use by Porch Rule (id=21)')
        ex.message.contains('force=true')
        varPosts.isEmpty()

        where:
        state                                | meshAnswer
        'the mesh list has no such mirror'   | { params -> groovy.json.JsonOutput.toJson([localLinkedHubVariables: [[name: 'porchTemp on Peer', inUseByApps: false]]]) }
        'the mesh list cannot be read'       | { params -> throw new RuntimeException('mesh JSON unreachable') }
    }

    // ---- hub_get_variable details and includeDependents ----

    def "a plain hub_get_variable makes no hub request"() {
        given:
        script.metaClass.getGlobalVar = { String n -> [name: n, type: 'integer', value: 9, deviceId: 8, attribute: 'variable'] }
        modelRows.V = modelRow('V', [connectorId: 8, connectorType: 'Variable'])
        stubHubVarsPage()

        when:
        def result = script.toolGetVariable([name: 'V'])

        then:
        result == [name: 'V', value: 9, type: 'integer', deviceId: 8, attribute: 'variable', source: 'hub']
        hubGet.calls.isEmpty()
    }

    def "hub_get_variable includeDependents adds the connector and Hub Mesh details from the same page read"() {
        given:
        script.metaClass.getGlobalVar = { String n -> [name: n, type: 'integer', value: 9, deviceId: 8, attribute: 'variable'] }
        modelRows.V = modelRow('V', [value: '9', meshEnabled: true, connectorId: 8, connectorLabel: 'V', connectorType: 'Variable',
            connectorUsedBy: [[id: 2, label: 'All Devices']]])
        stubHubVarsPage()

        when:
        def result = script.toolGetVariable([name: 'V', includeDependents: true])

        then: 'the typed value still comes from getGlobalVar, not the page text'
        result.value == 9
        result.deviceId == 8
        result.connectorType == 'Variable'
        result.connectorLabel == 'V'
        result.meshShared == true
        result.linked == false
        !result.containsKey('sourceHubName')
        result.appsUsing == [[id: '2', label: 'All Devices']]
        !result.containsKey('detailsError')
        hubGet.calls*.path == ['/hub2/variables']
    }

    @spock.lang.Unroll
    def "hub reads return a DateTime value as stored, with dateTimeKind #kind"() {
        given:
        script.metaClass.getGlobalVar = { String n -> [name: n, type: 'datetime', value: stored, deviceId: null, attribute: null] }
        script.metaClass.getAllGlobalVars = { -> [when: [name: 'when', type: 'datetime', value: stored, deviceId: null, attribute: null],
                                                 n: [name: 'n', type: 'integer', value: 1, deviceId: null, attribute: null]] }
        modelRows.when = modelRow('when', [type: 'datetime', value: stored])
        modelRows.n = modelRow('n')
        stubHubVarsPage()

        when:
        def got = script.toolGetVariable([name: 'when'])
        def listed = script.toolListVariables()

        then:
        got.value == stored
        got.dateTimeKind == kind
        listed.hubVariables.find { it.name == 'when' }.dateTimeKind == kind
        !listed.hubVariables.find { it.name == 'n' }.containsKey('dateTimeKind')

        where:
        kind       | stored
        'datetime' | '2026-03-05T08:15:00.000-0500'
        'date'     | '2026-03-05T99:99:99.999-9999'
        'time'     | '9999-99-99T08:15:00.000-0500'
    }

    def "hub_get_variable names the source hub of a Hub Mesh linked mirror"() {
        given:
        script.metaClass.getGlobalVar = { String n -> [name: n, type: 'integer', value: 4, deviceId: null, attribute: null] }
        modelRows['T on Peer'] = modelRow('T on Peer', [linked: true, sourceName: 'Peer', sourceIp: '192.0.2.7'])
        stubHubVarsPage()

        when:
        def result = script.toolGetVariable([name: 'T on Peer', includeDependents: true])

        then:
        result.linked == true
        result.sourceHubName == 'Peer'
        !result.containsKey('connectorType')
    }

    def "hub_get_variable includeDependents lists direct and connector users from the same page read"() {
        given:
        script.metaClass.getGlobalVar = { String n -> [name: n, type: 'boolean', value: false, deviceId: 8, attribute: 'switch'] }
        modelRows.zzProbe = modelRow('zzProbe', [usedBy: [[id: 21, label: 'zzVarDepProbeRule']], connectorId: 8,
            connectorUsedBy: [[id: 2, label: 'All Devices'], [id: 21, label: 'zzVarDepProbeRule']]])
        stubHubVarsPage()

        when:
        def result = script.toolGetVariable([name: 'zzProbe', includeDependents: true])

        then: 'each app once in appsUsing; the connector users on their own'
        result.appsUsing == [[id: '21', label: 'zzVarDepProbeRule'], [id: '2', label: 'All Devices']]
        result.connectorAppsUsing == [[id: '2', label: 'All Devices'], [id: '21', label: 'zzVarDepProbeRule']]
        result.count == 2
        result.coverageNote.contains('webCoRE')
        hubGet.calls.size() == 1
    }

    def "hub_get_variable includeDependents returns an empty list when no app uses the variable"() {
        given:
        script.metaClass.getGlobalVar = { String n -> [name: n, type: 'boolean', value: false] }
        modelRows.Idle = modelRow('Idle')
        stubHubVarsPage()

        when:
        def result = script.toolGetVariable([name: 'Idle', includeDependents: true])

        then:
        result.appsUsing == []
        result.connectorAppsUsing == []
        result.count == 0
        !result.containsKey('dependentsError')
    }

    def "hub_get_variable includeDependents reports an unreadable page in the response without failing the call"() {
        given:
        script.metaClass.getGlobalVar = { String n -> [name: n, type: 'boolean', value: true] }
        hubGet.register('/hub2/variables') { params -> throw new RuntimeException('page down') }

        when:
        def result = script.toolGetVariable([name: 'zzProbe', includeDependents: true])

        then:
        result.value == true
        result.detailsError == 'Could not read the connector and Hub Mesh details: page down'
        result.dependentsError == "Could not read the hub's in-use registry: page down"
        result.dependentsNote.contains("hub_get_tool_guide(section='variables')")
        !result.containsKey('appsUsing')
    }

    @spock.lang.Unroll
    def "hub_get_variable reports #label as unknown dependents, not none"() {
        given:
        script.metaClass.getGlobalVar = { String n -> [name: n, type: 'boolean', value: true] }
        if (row != null) modelRows.zzProbe = row
        stubHubVarsPage()

        when:
        def result = script.toolGetVariable([name: 'zzProbe', includeDependents: true])

        then:
        !result.containsKey('appsUsing')
        result.dependentsError.contains(why)

        where:
        label                          | row                                     | why
        'a variable the page omits'    | null                                    | "does not list 'zzProbe'"
        'a row without consumer lists' | modelRow('zzProbe', [usedBy: null])     | "could not confirm 'zzProbe' consumers"
    }

    def "hub_get_variable includeDependents still throws for an unknown variable, before any page read"() {
        given:
        script.metaClass.getGlobalVar = { String n -> null }
        stateMap.ruleVariables = [:]

        when:
        script.toolGetVariable([name: 'nope', includeDependents: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Variable not found')
        hubGet.calls.isEmpty()
    }

    def "hub_get_variable includeDependents is ignored for a rule-engine variable (no dependents fields, no page read)"() {
        given:
        script.metaClass.getGlobalVar = { String n -> null }
        stateMap.ruleVariables = [onlyRule: 'x']

        when:
        def result = script.toolGetVariable([name: 'onlyRule', includeDependents: true])

        then: 'the in-use registry is hub-variable-only, so a rule-engine var gets no dependents fields'
        result.source == 'rule_engine'
        !result.containsKey('appsUsing')
        !result.containsKey('coverageNote')
        !result.containsKey('dependentsError')
        !result.containsKey('detailsError')
        hubGet.calls.isEmpty()
    }

    def "hub_get_variable includeDependents via dispatch routes through the read gateway"() {
        given:
        script.metaClass.getGlobalVar = { String n -> [name: n, type: 'boolean', value: false] }
        modelRows.zzProbe = modelRow('zzProbe', [usedBy: [[id: 21, label: 'zzVarDepProbeRule']]])
        stubHubVarsPage()

        when:
        def response = mcpDriver.callTool('hub_get_variable', [name: 'zzProbe', includeDependents: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.name == 'zzProbe'
        inner.count == 1
        inner.appsUsing == [[id: '21', label: 'zzVarDepProbeRule']]
        inner.meshShared == false
    }

    def "hub_delete_variable refuses when a child rule uses the variable only as a %name% substitution"() {
        given:
        enableWrite()
        stateMap.ruleVariables = [Temp: 20]
        def consumer = new support.TestChildApp(id: 7L, label: 'Announces Temp')
        consumer.ruleData = [triggers: [], conditions: [],
                             actions: [[type: 'log', message: 'Temperature is %Temp%']]]
        childAppsList << consumer

        when:
        script.toolDeleteHubVariable([name: 'Temp', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('id=7')
        stateMap.ruleVariables == [Temp: 20]
    }

    // -------- _refreshHubVarInUseRegistrations (issue #96 gap 1) --------

    def "in-use refresh registers vars referenced by child rules"() {
        given: 'two hub vars on the hub'
        script.metaClass.getAllGlobalVars = { ->
            [shared_var: [name: 'shared_var', type: 'String', value: 'x'],
             unused_var: [name: 'unused_var', type: 'String', value: 'y']]
        }
        and: 'a child rule that references shared_var'
        def consumer = new support.TestChildApp(id: 11L, label: 'Uses shared_var')
        consumer.ruleData = [
            triggers: [],
            conditions: [[type: 'variable', name: 'shared_var']],
            actions: []
        ]
        childAppsList << consumer

        and: 'recorders for the in-use API calls'
        def added = []
        def removed = []
        script.metaClass.addInUseGlobalVar    = { String n -> added    << n; true }
        script.metaClass.removeInUseGlobalVar = { String n -> removed  << n; true }

        when:
        script._refreshHubVarInUseRegistrations()

        then: 'only the referenced var was registered; the unreferenced one was not'
        added == ['shared_var']
        removed == []
        atomicStateMap.inUseHubVars == ['shared_var']
    }

    def "in-use refresh removes registrations for vars no longer referenced"() {
        given: 'previous run registered two vars'
        atomicStateMap.inUseHubVars = ['old_a', 'old_b']
        script.metaClass.getAllGlobalVars = { ->
            [old_a: [name: 'old_a'], old_b: [name: 'old_b'], still_used: [name: 'still_used']]
        }
        and: 'a child rule that now only references still_used'
        def consumer = new support.TestChildApp(id: 22L, label: 'Migrated')
        consumer.ruleData = [conditions: [[type: 'variable', name: 'still_used']]]
        childAppsList << consumer

        def added = []
        def removed = []
        script.metaClass.addInUseGlobalVar    = { String n -> added   << n; true }
        script.metaClass.removeInUseGlobalVar = { String n -> removed << n; true }

        when:
        script._refreshHubVarInUseRegistrations()

        then: 'still_used is newly registered; old_a + old_b are unregistered'
        added == ['still_used']
        removed.toSet() == ['old_a', 'old_b'] as Set
        atomicStateMap.inUseHubVars == ['still_used']
    }

    def "in-use refresh clears all registrations when there are no hub vars at all"() {
        given:
        atomicStateMap.inUseHubVars = ['stale']
        script.metaClass.getAllGlobalVars = { -> [:] }

        def removed = []
        script.metaClass.removeInUseGlobalVar = { String n -> removed << n; true }

        when:
        script._refreshHubVarInUseRegistrations()

        then:
        removed == ['stale']
        atomicStateMap.inUseHubVars == []
    }

    def "in-use refresh tolerates getAllGlobalVars throwing — does not mutate state"() {
        given:
        atomicStateMap.inUseHubVars = ['existing']
        script.metaClass.getAllGlobalVars = { -> throw new RuntimeException('hub api down') }

        when:
        script._refreshHubVarInUseRegistrations()

        then: 'state unchanged — better to keep stale registration than wipe it on a transient error'
        atomicStateMap.inUseHubVars == ['existing']
        noExceptionThrown()
    }

    // -------- Dispatch-envelope counterparts (#187, #121) --------
    // Parallel coverage exercising callTool() so the JSON-RPC envelope, gateway
    // routing toggles, and error mapping (IAE -> isError validation result, generic -> isError) are
    // verified end-to-end alongside the direct-call golden paths above. Tools
    // live in the hub_manage_variables gateway; dispatched directly by snake-
    // case name through executeTool() in hubitat-mcp-server.groovy.

    @spock.lang.Unroll
    def "hub_list_variables via dispatch returns hub + rule vars (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        script.metaClass.getAllGlobalVars = { ->
            ['temp_setpoint': [name: 'temp_setpoint', type: 'Number', value: 72, deviceId: 305, attribute: 'variable']]
        }
        stateMap.ruleVariables = [last_motion: '2026-04-21T10:00:00']

        when:
        def response = mcpDriver.callTool('hub_list_variables', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.hubVariables.size() == 1
        inner.hubVariables[0].name == 'temp_setpoint'
        inner.ruleVariables.size() == 1
        inner.total == 2

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_list_variables via dispatch returns empty collections when no vars (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        script.metaClass.getAllGlobalVars = { -> [:] }

        when:
        def response = mcpDriver.callTool('hub_list_variables', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.hubVariables == []
        inner.ruleVariables == []
        inner.total == 0

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_get_variable via dispatch returns hub var (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        script.metaClass.getGlobalVar = { String name ->
            [name: 'outdoor_temp', type: 'Decimal', value: 68.4, deviceId: 312, attribute: 'variable']
        }

        when:
        def response = mcpDriver.callTool('hub_get_variable', [name: 'outdoor_temp'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.name == 'outdoor_temp'
        inner.value == 68.4
        inner.type == 'Decimal'
        inner.source == 'hub'

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_get_variable via dispatch maps unknown to isError validation result (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        script.metaClass.getGlobalVar = { String n -> null }
        stateMap.ruleVariables = [:]

        when:
        def response = mcpDriver.callTool('hub_get_variable', [name: 'nonexistent'])

        then:
        response.result?.isError == true
        mcpDriver.parseInner(response).error.contains('Variable not found')
        mcpDriver.parseInner(response).error.contains('nonexistent')

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_set_variable via dispatch writes hub var (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        script.metaClass.getGlobalVar = { String n -> [name: n, type: 'boolean', value: false] }
        def captured = [:]
        script.metaClass.setGlobalVar = { String name, Object value ->
            captured[name] = value
            return true
        }

        when:
        def response = mcpDriver.callTool('hub_set_variable', [name: 'vacation_mode', value: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.name == 'vacation_mode'
        inner.value == true
        inner.source == 'hub'
        captured == [vacation_mode: true]

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_set_variable via dispatch maps a wrong-type value to an isError validation result (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        script.metaClass.getGlobalVar = { String n -> [name: n, type: 'integer', value: 3] }
        def writes = []
        script.metaClass.setGlobalVar = { String n, Object v -> writes << v; true }

        when:
        def response = mcpDriver.callTool('hub_set_variable', [name: 'counter', value: 'abc'])

        then:
        response.error == null
        response.result.isError
        mcpDriver.parseInner(response).toString().contains('whole number')
        writes.isEmpty()
        stateMap.ruleVariables == null

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_set_variable via dispatch falls back to rule_engine when hub var missing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        script.metaClass.getGlobalVar = { String n -> null }
        script.metaClass.setGlobalVar = { String name, Object value -> false }

        when:
        def response = mcpDriver.callTool('hub_set_variable', [name: 'new_rule_var', value: 42])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.source == 'rule_engine'
        inner.value == 42
        stateMap.ruleVariables == [new_rule_var: 42]

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_delete_variable via dispatch removes rule var (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        stateMap.ruleVariables = [scratch_var: 'to-be-removed', keep_me: 42]

        when:
        def response = mcpDriver.callTool('hub_delete_variable', [name: 'scratch_var', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.deleted == true
        inner.source == 'rule_engine'
        inner.previousValue == 'to-be-removed'
        stateMap.ruleVariables == [keep_me: 42]

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_delete_variable via dispatch maps missing-confirm to isError validation result (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        stateMap.ruleVariables = [scratch_var: 'value']

        when:
        def response = mcpDriver.callTool('hub_delete_variable', [name: 'scratch_var'])

        then:
        response.result?.isError == true
        stateMap.ruleVariables == [scratch_var: 'value']

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_delete_variable via dispatch maps missing-name to isError validation result (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_delete_variable', [confirm: true])

        then:
        response.result?.isError == true
        mcpDriver.parseInner(response).error.contains('name is required')

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_delete_variable via dispatch maps unknown var to isError validation result (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.getGlobalVar = { String n -> null }
        stateMap.ruleVariables = [:]

        when:
        def response = mcpDriver.callTool('hub_delete_variable', [name: 'nonexistent', confirm: true])

        then:
        response.result?.isError == true
        mcpDriver.parseInner(response).error.contains("'nonexistent'")

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_delete_variable via dispatch refuses on consumer ref without force (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        stateMap.ruleVariables = [shared_var: 'in-use']
        def consumer = new support.TestChildApp(id: 99L, label: 'Rule Using Shared Var')
        consumer.ruleData = [
            triggers: [],
            conditions: [[type: 'variable', name: 'shared_var', operator: '=', value: 'on']],
            actions: []
        ]
        childAppsList << consumer

        when:
        def response = mcpDriver.callTool('hub_delete_variable', [name: 'shared_var', confirm: true])

        then:
        response.result?.isError == true
        mcpDriver.parseInner(response).error.contains("'shared_var'")
        mcpDriver.parseInner(response).error.contains('force=true')
        stateMap.ruleVariables == [shared_var: 'in-use']

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_create_variable via dispatch returns the hub's name refusal as an isError result (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.getGlobalVar = { String n -> null }
        script.metaClass.hubInternalPostJson = { String path, String json, int timeout = 420 -> throw new HubAnswer(400, NAME_REFUSAL) }

        when:
        def response = mcpDriver.callTool('hub_create_variable', [name: 'has[brackets]', type: 'String', value: 'x', confirm: true])

        then:
        response.result?.isError == true
        mcpDriver.parseInner(response).error.contains('Use a name of 1-255 characters')

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_create_variable via dispatch maps unknown-type to isError validation result (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_create_variable', [name: 'goodName', type: 'NotAType', value: 'x', confirm: true])

        then:
        response.result?.isError == true
        mcpDriver.parseInner(response).error.contains('NotAType')

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_create_variable via dispatch maps missing-confirm to isError validation result (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_create_variable', [name: 'goodName', type: 'String', value: 'x'])

        then:
        response.result?.isError == true

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_create_variable via dispatch maps existing hub var to isError validation result (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.getGlobalVar = { String n ->
            n == 'taken' ? [name: 'taken', type: 'String', value: 'already here', deviceId: null, attribute: null] : null
        }

        when:
        def response = mcpDriver.callTool('hub_create_variable', [name: 'taken', type: 'String', value: 'new', confirm: true])

        then:
        response.result?.isError == true
        mcpDriver.parseInner(response).error.contains('already exists')

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_create_variable via dispatch creates the bulk array and reports per-item status (useGateways=#useGateways)"() {
        given: 'the gateway must route the variables arg + the relaxed required:["confirm"]'
        settingsMap.useGateways = useGateways
        enableWrite()

        and: 'each create POST makes its variable readable'
        stubBulkCreate()

        when:
        def response = mcpDriver.callTool('hub_create_variable', [confirm: true, variables: [
            [name: 'dispBulkA', type: 'Number', value: 1],
            [name: 'dispBulkB', type: 'Number', value: 2]
        ]])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.createdCount == 2
        inner.failedCount == 0
        inner.results.size() == 2
        inner.results[0].name == 'dispBulkA'
        inner.results[0].success == true
        inner.results[1].name == 'dispBulkB'
        inner.results[1].success == true

        where:
        useGateways << [true, false]
    }

    // -------- _resolveDirectAppId (name-addressed app-id resolution) --------
    //
    // The /installedapp/direct/<alias> chain is two 302 hops:
    //   direct/<alias> -> create/<typeId> -> configure/<instanceId>.
    // hubInternalGetRaw is non-private, so a per-test metaClass stub
    // intercepts the helper's internal calls (class 1 on the dispatch cheat
    // sheet); the helper itself is invoked directly — Groovy doesn't enforce
    // `private` for direct calls from specs.

    def "_resolveDirectAppId follows the two-hop redirect chain to the configure instance id"() {
        given: 'the hub answers both hops with relative 302 Locations'
        def gets = []
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, int t = 30, boolean r = false ->
            gets << path
            switch (path) {
                case '/installedapp/direct/hubVariables': return [status: 302, location: '/installedapp/create/32', data: null]
                case '/installedapp/create/32':           return [status: 302, location: '/installedapp/configure/1424', data: null]
                default: throw new IllegalStateException("unexpected GET ${path}")
            }
        }

        when:
        def id = script._resolveDirectAppId('hubVariables')

        then: 'the configure instance id comes back'
        id == 1424

        and: 'exactly two hops — the configure page itself is never fetched'
        gets == ['/installedapp/direct/hubVariables', '/installedapp/create/32']
    }

    def "_resolveDirectAppId tolerates an absolute Location on the final hop"() {
        given: 'hop 2 redirects with a scheme://host-qualified Location'
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, int t = 30, boolean r = false ->
            path == '/installedapp/direct/swapDevice' ?
                [status: 302, location: '/installedapp/create/38', data: null] :
                [status: 302, location: 'http://127.0.0.1:8080/installedapp/configure/1802', data: null]
        }

        expect:
        script._resolveDirectAppId('swapDevice') == 1802
    }

    def "_resolveDirectAppId returns null on a non-redirect response"() {
        given: 'the platform client auto-followed an absolute Location and handed back 200 HTML with no Location (hubInternalGetRaw caveat)'
        def gets = []
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, int t = 30, boolean r = false ->
            gets << path
            [status: 200, location: null, data: '<html>configure page</html>']
        }
        def logged = []
        script.metaClass.mcpLog = { String level, String component, String msg ->
            logged << [level: level, component: component, msg: msg]
        }

        when:
        def id = script._resolveDirectAppId('hubVariables')

        then:
        id == null

        and: 'it gave up on the first hop instead of guessing from the HTML body'
        gets.size() == 1

        and: 'a hub-admin warn summarizes the cause (incl. the absolute-Location auto-follow caveat) without needing debug logging'
        logged.any { it.level == 'warn' && it.component == 'hub-admin' && it.msg.contains('hubVariables') && it.msg.contains('auto-followed') }
    }

    def "_resolveDirectAppId returns null on a garbage Location"() {
        given: 'a 302 pointing somewhere outside the direct chain'
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, int t = 30, boolean r = false ->
            [status: 302, location: '/hub/error', data: null]
        }

        expect:
        script._resolveDirectAppId('hubVariables') == null
    }

    def "_resolveDirectAppId returns null when hop 2 redirects to create/ again"() {
        given: 'the create hop bounces to ANOTHER create/ instead of configure/ — a create match is only legal on hop 1'
        def logged = []
        script.metaClass.mcpLog = { String level, String component, String msg ->
            logged << [level: level, component: component, msg: msg]
        }
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, int t = 30, boolean r = false ->
            path == '/installedapp/direct/hubVariables' ?
                [status: 302, location: '/installedapp/create/32', data: null] :
                [status: 302, location: '/installedapp/create/99', data: null]
        }

        expect:
        script._resolveDirectAppId('hubVariables') == null

        and: 'the unexpected-Location warn fired at hub-admin'
        logged.any { it.level == 'warn' && it.component == 'hub-admin' && it.msg.contains('unexpected Location') }
    }

    def "_resolveDirectAppId returns null when the request throws"() {
        given:
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, int t = 30, boolean r = false ->
            throw new RuntimeException('connection refused')
        }

        expect:
        script._resolveDirectAppId('hubVariables') == null
    }
}
