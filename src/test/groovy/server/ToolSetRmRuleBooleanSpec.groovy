package server

import support.RMUtilsMock
import support.ToolSpecBase

/**
 * Spec for toolSetRmRuleBoolean (libraries/mcp-native-rules-lib.groovy).
 * Gateway: hub_manage_native_rules_and_apps -> hub_set_rule_private_boolean.
 *
 * Load-bearing: strict coercion policy. Accepts ONLY Boolean true/false OR
 * the lowercase strings "true"/"false". All other truthy-looking values
 * (capitalized strings, integers, yes/no, on/off) throw IllegalArgumentException.
 *
 * Covers: gate-throw, missing ruleId, missing value, the full accept matrix,
 * the full reject matrix, rmAction mapping for each resolved Boolean, and the target guard
 * every id passes before the one RMUtils dispatch.
 */
class ToolSetRmRuleBooleanSpec extends ToolSpecBase {

    RMUtilsMock rmUtils
    // App type per id for the getAppByAppId stub; an id mapped to null has no app, an absent id is a Rule-5.1 rule.
    Map<Integer, String> appTypes = [:]

    def setup() {
        rmUtils = new RMUtilsMock()
        rmUtils.install()
        script.metaClass.getAppByAppId = { Long id ->
            Integer key = id as Integer
            if (appTypes.containsKey(key) && appTypes[key] == null) return null
            [id: id, appType: [namespace: 'hubitat', name: appTypes.containsKey(key) ? appTypes[key] : 'Rule-5.1']]
        }
    }

    def cleanup() {
        rmUtils?.uninstall()
    }

    def "throws when Write master is disabled"() {
        given:
        settingsMap.enableWrite = false

        when: 'the central executeTool gate blocks the write tool (tool body no longer self-gates)'
        script.executeTool('hub_set_rule_private_boolean', [ruleId: 1, value: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Write tools are disabled')
    }

    @spock.lang.Unroll
    def "hub_set_rule_private_boolean via dispatch returns an isError validation result when Write master is disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableWrite = false

        when:
        def response = mcpDriver.callTool('hub_set_rule_private_boolean', [ruleId: 1, value: true])

        then:
        response.error == null
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('Write tools are disabled')

        where:
        useGateways << [true, false]
    }

    def "throws when ruleId is missing"() {
        when:
        script.toolSetRmRuleBoolean([value: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('ruleid is required')
    }

    @spock.lang.Unroll
    def "hub_set_rule_private_boolean via dispatch returns isError validation result envelope when ruleId is missing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_set_rule_private_boolean', [value: true])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.toLowerCase().contains('ruleid is required')

        where:
        useGateways << [true, false]
    }

    def "throws when value is missing"() {
        when:
        script.toolSetRmRuleBoolean([ruleId: 1])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('value')
    }

    @spock.lang.Unroll
    def "hub_set_rule_private_boolean via dispatch returns isError validation result envelope when value is missing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_set_rule_private_boolean', [ruleId: 1])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.toLowerCase().contains('value')

        where:
        useGateways << [true, false]
    }

    def "Boolean true dispatches setRuleBooleanTrue"() {
        when:
        def result = script.toolSetRmRuleBoolean([ruleId: 800, value: true])

        then:
        result.success == true
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'setRuleBooleanTrue' }
    }

    @spock.lang.Unroll
    def "hub_set_rule_private_boolean via dispatch Boolean true dispatches setRuleBooleanTrue (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_set_rule_private_boolean', [ruleId: 800, value: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'setRuleBooleanTrue' }

        where:
        useGateways << [true, false]
    }

    def "Boolean false dispatches setRuleBooleanFalse"() {
        when:
        def result = script.toolSetRmRuleBoolean([ruleId: 801, value: false])

        then:
        result.success == true
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'setRuleBooleanFalse' }
    }

    @spock.lang.Unroll
    def "hub_set_rule_private_boolean via dispatch Boolean false dispatches setRuleBooleanFalse (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_set_rule_private_boolean', [ruleId: 801, value: false])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'setRuleBooleanFalse' }

        where:
        useGateways << [true, false]
    }

    def "String 'true' is accepted and dispatches setRuleBooleanTrue"() {
        when:
        def result = script.toolSetRmRuleBoolean([ruleId: 802, value: 'true'])

        then:
        result.success == true
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'setRuleBooleanTrue' }
    }

    @spock.lang.Unroll
    def "hub_set_rule_private_boolean via dispatch String 'true' is accepted and dispatches setRuleBooleanTrue (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_set_rule_private_boolean', [ruleId: 802, value: 'true'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'setRuleBooleanTrue' }

        where:
        useGateways << [true, false]
    }

    def "String 'false' is accepted and dispatches setRuleBooleanFalse"() {
        when:
        def result = script.toolSetRmRuleBoolean([ruleId: 803, value: 'false'])

        then:
        result.success == true
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'setRuleBooleanFalse' }
    }

    @spock.lang.Unroll
    def "hub_set_rule_private_boolean via dispatch String 'false' is accepted and dispatches setRuleBooleanFalse (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_set_rule_private_boolean', [ruleId: 803, value: 'false'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'setRuleBooleanFalse' }

        where:
        useGateways << [true, false]
    }

    def "reject matrix: capitalized and uppercase boolean strings are rejected"(Object value) {
        when:
        script.toolSetRmRuleBoolean([ruleId: 999, value: value])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('value must be boolean')

        where:
        value << ['True', 'False', 'TRUE', 'FALSE']
    }

    @spock.lang.Unroll
    def "hub_set_rule_private_boolean via dispatch rejects capitalized boolean string '#value' with isError validation result (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_set_rule_private_boolean', [ruleId: 999, value: value])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.toLowerCase().contains('value must be boolean')

        where:
        useGateways | value
        true        | 'True'
        true        | 'False'
        true        | 'TRUE'
        true        | 'FALSE'
        false       | 'True'
        false       | 'False'
        false       | 'TRUE'
        false       | 'FALSE'
    }

    def "reject matrix: truthy-looking non-boolean strings are rejected"(Object value) {
        when:
        script.toolSetRmRuleBoolean([ruleId: 999, value: value])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('value must be boolean')

        where:
        value << ['yes', 'no', 'on', 'off']
    }

    @spock.lang.Unroll
    def "hub_set_rule_private_boolean via dispatch rejects truthy-looking string '#value' with isError validation result (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_set_rule_private_boolean', [ruleId: 999, value: value])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.toLowerCase().contains('value must be boolean')

        where:
        useGateways | value
        true        | 'yes'
        true        | 'no'
        true        | 'on'
        true        | 'off'
        false       | 'yes'
        false       | 'no'
        false       | 'on'
        false       | 'off'
    }

    def "reject matrix: integer values are rejected"(Object value) {
        when:
        script.toolSetRmRuleBoolean([ruleId: 999, value: value])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('value must be boolean')

        where:
        value << [1, 0]
    }

    @spock.lang.Unroll
    def "hub_set_rule_private_boolean via dispatch rejects integer value #value with isError validation result (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_set_rule_private_boolean', [ruleId: 999, value: value])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.toLowerCase().contains('value must be boolean')

        where:
        useGateways | value
        true        | 1
        true        | 0
        false       | 1
        false       | 0
    }

    def "gateway dispatch via handleGateway routes to hub_set_rule_private_boolean"() {
        when:
        def result = script.handleGateway('hub_manage_native_rules_and_apps', 'hub_set_rule_private_boolean', [ruleId: 900, value: true])

        then:
        result.success == true
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'setRuleBooleanTrue' }
    }

    def "empty ruleId array throws IllegalArgumentException"() {
        when:
        script.toolSetRmRuleBoolean([ruleId: [], value: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('must not be empty')
    }

    def "array ruleId sets the private boolean for the whole set in ONE sendAction dispatch"() {
        when:
        def result = script.toolSetRmRuleBoolean([ruleId: [900, 901], value: false])

        then:
        result.success == true
        result.ruleIds == [900, 901]
        result.ruleId == null
        def sends = rmUtils.calls.findAll { it.method == 'sendAction' && it.action == 'setRuleBooleanFalse' }
        sends.size() == 1
        sends[0].ruleIds == [900, 901]
    }

    @spock.lang.Unroll
    def "hub_set_rule_private_boolean via dispatch sets an ARRAY of ruleIds in one call (useGateways=#useGateways)"() {
        given: 'the union-typed ruleId argument has to survive JSON-RPC round-tripping, not just a direct call'
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_set_rule_private_boolean', [ruleId: [900, 901], value: false])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.ruleIds == [900, 901]

        and: 'one dispatch for the whole set, after every id passed the target guard'
        !inner.containsKey('idsVerified')
        rmUtils.calls.findAll { it.method == 'sendAction' && it.action == 'setRuleBooleanFalse' }.size() == 1

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "#label is refused before the dispatch"() {
        given:
        appTypes.putAll(types)

        when:
        script.toolSetRmRuleBoolean([ruleId: ids, value: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains(message)
        ex.message.contains('hub_set_rule_private_boolean acts only on Rule Machine rules and Button Rules')
        ex.message.contains('hub_list_apps (Button Rules and Basic Rules)')
        ex.message.contains('Nothing was dispatched')
        rmUtils.calls.isEmpty()

        where:
        label                                 | ids          | types                              | message
        'an id with no app'                   | 999999       | [999999: null]                     | 'No app with id 999999 is installed'
        'a batch holding an id with no app'   | [900, 10]    | [10: null]                         | 'No app with id 10 is installed'
        'the Rule Machine parent'             | 20           | [20: 'Rule Machine']               | 'App 20 is a Rule Machine app;'
        'a Visual Rule'                       | 21           | [21: 'Visual Rule Builder 2.0']    | 'App 21 is a Visual Rule Builder 2.0 app;'
    }

    @spock.lang.Unroll
    def "a lookup that fails refuses the dispatch as a runtime failure (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        script.metaClass.getAppByAppId = { Long id ->
            if (id == 901L) throw new RuntimeException('hub busy')
            [id: id, appType: [namespace: 'hubitat', name: 'Rule-5.1']]
        }

        when:
        def response = mcpDriver.callTool('hub_set_rule_private_boolean', [ruleId: [900, 901], value: true])
        def inner = mcpDriver.parseInner(response)

        then:
        response.result.isError == true
        inner.success == false
        inner.ruleIds == [900, 901]
        inner.error.contains('app 901: hub busy')
        inner.note.contains('Nothing was changed')
        rmUtils.calls.isEmpty()

        where:
        useGateways << [true, false]
    }

    def "a Button Rule passes the guard and is dispatched"() {
        given:
        appTypes[902] = 'Button Rule-5.1'

        when:
        def result = script.toolSetRmRuleBoolean([ruleId: 902, value: false])

        then:
        result.success == true
        rmUtils.calls.find { it.method == 'sendAction' }.ruleIds == [902]
    }
}
