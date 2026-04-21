package server

import support.ToolSpecBase

/**
 * Spec for the manage_hub_variables gateway tools (hubitat-mcp-server.groovy
 * around line 2822):
 *
 * - toolListVariables    -> list_variables
 * - toolGetVariable      -> get_variable
 * - toolSetVariable      -> set_variable
 *
 * Mocking strategy: getAllGlobalConnectorVariables / getGlobalConnectorVariable /
 * setGlobalConnectorVariable are Hubitat hub-variable APIs not present on
 * AppExecutor, so they're stubbed per-test via script.metaClass in given:
 * blocks. HarnessSpec.setup() calls removeMetaClass(script.getClass()) before
 * re-running wireScriptOverrides(), so per-test stubs are cleared between
 * features automatically.
 */
class ToolHubVariablesSpec extends ToolSpecBase {

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
}
