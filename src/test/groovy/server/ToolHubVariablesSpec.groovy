package server

import support.ToolSpecBase

/**
 * Spec for the manage_hub_variables gateway tools in hubitat-mcp-server.groovy:
 *
 * - toolListVariables    -> list_variables
 * - toolGetVariable      -> get_variable
 * - toolSetVariable      -> set_variable
 * - toolDeleteHubVariable -> delete_variable
 *
 * Hub-variable APIs (getAllGlobalConnectorVariables / getGlobalConnectorVariable /
 * setGlobalConnectorVariable) are purely dynamic — not on AppExecutor — so
 * they're stubbed per-test via script.metaClass in given: blocks. See
 * docs/testing.md "Which interception point to use" for the general pattern.
 *
 * delete_variable is gated by requireHubAdminWrite (3-layer: setting flag,
 * args.confirm, 24h backup window). Helper enableHubAdminWrite() seeds those.
 */
class ToolHubVariablesSpec extends ToolSpecBase {

    private void enableHubAdminWrite() {
        settingsMap.enableHubAdminWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L  // matches HarnessSpec's fixed now()
    }

    // -------- toolListVariables --------

    def "list_variables returns both hub and rule-engine variables with correct total"() {
        given: 'hub exposes two connector variables'
        script.metaClass.getAllGlobalConnectorVariables = { ->
            [
                'temp_setpoint': [value: 72, type: 'number'],
                'vacation_mode': [value: false, type: 'boolean']
            ]
        }

        and: 'the rule engine has one variable stored in state'
        stateMap.ruleVariables = [last_motion: '2026-04-21T10:00:00']

        when:
        def result = script.toolListVariables()

        then:
        result.hubVariables.size() == 2
        result.hubVariables.find { it.name == 'temp_setpoint' }.value == 72
        result.hubVariables.find { it.name == 'temp_setpoint' }.type == 'number'
        result.hubVariables.find { it.name == 'temp_setpoint' }.source == 'hub'
        result.ruleVariables.size() == 1
        result.ruleVariables[0].name == 'last_motion'
        result.ruleVariables[0].source == 'rule_engine'
        result.total == 3
    }

    def "list_variables falls back to rule variables when hub connector API throws"() {
        given: 'the hub connector API is unavailable'
        script.metaClass.getAllGlobalConnectorVariables = { ->
            throw new RuntimeException('Hub connector not available')
        }

        and: 'a rule-engine variable exists'
        stateMap.ruleVariables = [counter: 5]

        when:
        def result = script.toolListVariables()

        then: 'hub list is empty but the call does not throw'
        result.hubVariables == []
        result.ruleVariables.size() == 1
        result.total == 1
    }

    def "list_variables returns empty collections when no variables are defined"() {
        given:
        script.metaClass.getAllGlobalConnectorVariables = { -> [:] }

        when:
        def result = script.toolListVariables()

        then:
        result.hubVariables == []
        result.ruleVariables == []
        result.total == 0
    }

    // -------- toolGetVariable --------

    def "get_variable returns hub connector value with source 'hub' when found"() {
        given:
        script.metaClass.getGlobalConnectorVariable = { String name ->
            name == 'outdoor_temp' ? 68 : null
        }

        when:
        def result = script.toolGetVariable('outdoor_temp')

        then:
        result.name == 'outdoor_temp'
        result.value == 68
        result.source == 'hub'
    }

    def "get_variable falls back to rule-engine value when hub connector throws"() {
        given: 'hub connector API unavailable'
        script.metaClass.getGlobalConnectorVariable = { String name ->
            throw new RuntimeException('Not supported')
        }

        and: 'rule engine has the variable'
        stateMap.ruleVariables = [porch_light_pref: 'bright']

        when:
        def result = script.toolGetVariable('porch_light_pref')

        then:
        result.name == 'porch_light_pref'
        result.value == 'bright'
        result.source == 'rule_engine'
    }

    def "get_variable throws when variable exists in neither source"() {
        given:
        script.metaClass.getGlobalConnectorVariable = { String name -> null }
        stateMap.ruleVariables = [:]

        when:
        script.toolGetVariable('nonexistent')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Variable not found')
        ex.message.contains('nonexistent')
    }

    // -------- toolSetVariable --------

    def "set_variable writes via hub connector and reports source 'hub' on success"() {
        given:
        def captured = [:]
        script.metaClass.setGlobalConnectorVariable = { String name, Object value ->
            captured[name] = value
            return true
        }

        when:
        def result = script.toolSetVariable('vacation_mode', true)

        then:
        captured == [vacation_mode: true]
        result.success == true
        result.name == 'vacation_mode'
        result.value == true
        result.source == 'hub'
    }

    def "set_variable falls back to rule engine when hub connector throws"() {
        given:
        script.metaClass.setGlobalConnectorVariable = { String name, Object value ->
            throw new RuntimeException('Connector variable not defined on hub')
        }

        when:
        def result = script.toolSetVariable('new_rule_var', 42)

        then: 'rule-engine state was updated'
        stateMap.ruleVariables == [new_rule_var: 42]

        and: 'result reports rule_engine source'
        result.success == true
        result.source == 'rule_engine'
        result.value == 42
    }

    def "set_variable passes string values through to hub connector unchanged"() {
        given:
        def captured = [:]
        script.metaClass.setGlobalConnectorVariable = { String name, Object value ->
            captured[name] = value
        }

        when:
        def result = script.toolSetVariable('weather_state', 'sunny')

        then:
        captured == [weather_state: 'sunny']
        result.value == 'sunny'
        result.source == 'hub'
    }

    def "set_variable accepts null values via rule-engine fallback"() {
        given: 'hub connector rejects null (some connector types require non-null)'
        script.metaClass.setGlobalConnectorVariable = { String name, Object value ->
            throw new IllegalArgumentException('Connector variable must not be null')
        }

        when:
        def result = script.toolSetVariable('last_motion', null)

        then:
        stateMap.ruleVariables == [last_motion: null]
        result.success == true
        result.value == null
        result.source == 'rule_engine'
    }

    // -------- toolDeleteHubVariable --------

    def "delete_variable removes an existing rule_engine variable and reports the previous value"() {
        given:
        enableHubAdminWrite()
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

    def "delete_variable throws when confirm is not provided"() {
        given:
        enableHubAdminWrite()
        stateMap.ruleVariables = [scratch_var: 'value']

        when:
        script.toolDeleteHubVariable([name: 'scratch_var'])

        then:
        thrown(IllegalArgumentException)
        // Variable should NOT be deleted when the gate refuses
        stateMap.ruleVariables == [scratch_var: 'value']
    }

    def "delete_variable throws when no recent backup exists (Hub Admin Write 24h gate)"() {
        given: 'enableHubAdminWrite is true but no lastBackupTimestamp seeded'
        settingsMap.enableHubAdminWrite = true
        stateMap.ruleVariables = [scratch_var: 'value']

        when:
        script.toolDeleteHubVariable([name: 'scratch_var', confirm: true])

        then:
        thrown(IllegalArgumentException)
        stateMap.ruleVariables == [scratch_var: 'value']
    }

    def "delete_variable throws when enableHubAdminWrite setting is off"() {
        given:
        // No settingsMap.enableHubAdminWrite seed — gate refuses on the first layer
        stateMap.ruleVariables = [scratch_var: 'value']

        when:
        script.toolDeleteHubVariable([name: 'scratch_var', confirm: true])

        then:
        thrown(IllegalArgumentException)
        stateMap.ruleVariables == [scratch_var: 'value']
    }

    def "delete_variable throws when name is missing"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolDeleteHubVariable([confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('name is required')
    }

    def "delete_variable throws with helpful hint when variable is not in rule_engine namespace"() {
        given: 'rule_engine namespace is empty'
        enableHubAdminWrite()
        stateMap.ruleVariables = [:]

        when:
        script.toolDeleteHubVariable([name: 'nonexistent', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("'nonexistent'")
        ex.message.contains('rule_engine namespace')
        // Error should redirect users to UI for connector-namespace deletion
        ex.message.contains('Hub Variables UI')
    }

    def "delete_variable throws when ruleVariables map is null (variable also not present)"() {
        given:
        enableHubAdminWrite()
        // stateMap.ruleVariables not seeded at all — null check path

        when:
        script.toolDeleteHubVariable([name: 'never_set', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("'never_set'")
        ex.message.contains('rule_engine namespace')
    }
}
