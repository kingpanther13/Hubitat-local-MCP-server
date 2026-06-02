package server

import support.RMUtilsMock
import support.ToolSpecBase

/**
 * Spec for toolSetRulePaused with value=true (the pause half of the merged
 * verb-pair tool; former pause_rm_rule).
 * Gateway: hub_manage_native_rules_and_apps -> hub_set_rule_paused.
 *
 * Covers: gate-throw, missing ruleId, golden-path pauseRule dispatch,
 * and String ruleId coercion.
 */
class ToolPauseRmRuleSpec extends ToolSpecBase {

    RMUtilsMock rmUtils

    def setup() {
        rmUtils = new RMUtilsMock()
        rmUtils.install()
    }

    def cleanup() {
        rmUtils?.uninstall()
    }

    def "throws when Built-in App Read is disabled"() {
        given:
        settingsMap.enableBuiltinApp = false

        when:
        script.toolSetRulePaused([ruleId: 1, value: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Built-in App')
    }

    @spock.lang.Unroll
    def "hub_set_rule_paused via dispatch returns -32602 envelope when Built-in App Read is disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableBuiltinApp = false

        when:
        def response = mcpDriver.callTool('hub_set_rule_paused', [ruleId: 1, value: true])

        then:
        response.error.code == -32602
        response.error.message.contains('Built-in App')

        where:
        useGateways << [true, false]
    }

    def "throws when ruleId is missing"() {
        given:
        settingsMap.enableBuiltinApp = true

        when:
        script.toolSetRulePaused([value: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('ruleid is required')
    }

    @spock.lang.Unroll
    def "hub_set_rule_paused via dispatch returns -32602 envelope when ruleId is missing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableBuiltinApp = true

        when:
        def response = mcpDriver.callTool('hub_set_rule_paused', [value: true])

        then:
        response.error.code == -32602
        response.error.message.toLowerCase().contains('ruleid is required')

        where:
        useGateways << [true, false]
    }

    def "golden path: dispatches pauseRule sendAction for the given ruleId"() {
        given:
        settingsMap.enableBuiltinApp = true

        when:
        def result = script.toolSetRulePaused([ruleId: 400, value: true])

        then:
        result.success == true
        result.ruleId == 400
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'pauseRule' }
    }

    @spock.lang.Unroll
    def "hub_set_rule_paused via dispatch dispatches pauseRule sendAction for the given ruleId (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableBuiltinApp = true

        when:
        def response = mcpDriver.callTool('hub_set_rule_paused', [ruleId: 400, value: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.ruleId == 400
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'pauseRule' }

        where:
        useGateways << [true, false]
    }

    def "String ruleId is coerced to Integer"() {
        given:
        settingsMap.enableBuiltinApp = true

        when:
        def result = script.toolSetRulePaused([ruleId: '401', value: true])

        then:
        result.success == true
        result.ruleId == 401
        result.ruleId instanceof Integer
    }

    @spock.lang.Unroll
    def "hub_set_rule_paused via dispatch coerces String ruleId to Integer (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableBuiltinApp = true

        when:
        def response = mcpDriver.callTool('hub_set_rule_paused', [ruleId: '401', value: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.ruleId == 401
        inner.ruleId instanceof Integer

        where:
        useGateways << [true, false]
    }

    def "non-numeric ruleId throws IllegalArgumentException"() {
        given:
        settingsMap.enableBuiltinApp = true

        when:
        script.toolSetRulePaused([ruleId: 'abc', value: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('integer')
    }

    @spock.lang.Unroll
    def "hub_set_rule_paused via dispatch returns -32602 envelope on non-numeric ruleId (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableBuiltinApp = true

        when:
        def response = mcpDriver.callTool('hub_set_rule_paused', [ruleId: 'abc', value: true])

        then:
        response.error.code == -32602
        response.error.message.toLowerCase().contains('integer')

        where:
        useGateways << [true, false]
    }

    def "gateway dispatch via handleGateway routes to hub_set_rule_paused"() {
        given:
        settingsMap.enableBuiltinApp = true

        when:
        def result = script.handleGateway('hub_manage_native_rules_and_apps', 'hub_set_rule_paused', [ruleId: 500, value: true])

        then:
        result.success == true
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'pauseRule' }
    }
}
