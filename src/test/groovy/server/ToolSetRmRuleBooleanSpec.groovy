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
 * the full reject matrix, and rmAction mapping for each resolved Boolean.
 */
class ToolSetRmRuleBooleanSpec extends ToolSpecBase {

    RMUtilsMock rmUtils

    def setup() {
        rmUtils = new RMUtilsMock()
        rmUtils.install()
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
    def "hub_set_rule_private_boolean via dispatch returns -32602 envelope when Write master is disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableWrite = false

        when:
        def response = mcpDriver.callTool('hub_set_rule_private_boolean', [ruleId: 1, value: true])

        then:
        response.error.code == -32602
        response.error.message.contains('Write tools are disabled')

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
    def "hub_set_rule_private_boolean via dispatch returns -32602 envelope when ruleId is missing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_set_rule_private_boolean', [value: true])

        then:
        response.error.code == -32602
        response.error.message.toLowerCase().contains('ruleid is required')

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
    def "hub_set_rule_private_boolean via dispatch returns -32602 envelope when value is missing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_set_rule_private_boolean', [ruleId: 1])

        then:
        response.error.code == -32602
        response.error.message.toLowerCase().contains('value')

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
    def "hub_set_rule_private_boolean via dispatch rejects capitalized boolean string '#value' with -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_set_rule_private_boolean', [ruleId: 999, value: value])

        then:
        response.error.code == -32602
        response.error.message.toLowerCase().contains('value must be boolean')

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
    def "hub_set_rule_private_boolean via dispatch rejects truthy-looking string '#value' with -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_set_rule_private_boolean', [ruleId: 999, value: value])

        then:
        response.error.code == -32602
        response.error.message.toLowerCase().contains('value must be boolean')

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
    def "hub_set_rule_private_boolean via dispatch rejects integer value #value with -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_set_rule_private_boolean', [ruleId: 999, value: value])

        then:
        response.error.code == -32602
        response.error.message.toLowerCase().contains('value must be boolean')

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
}
