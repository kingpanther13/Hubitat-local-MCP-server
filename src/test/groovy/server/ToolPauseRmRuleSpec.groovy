package server

import support.RMUtilsMock
import support.ToolSpecBase

/**
 * Spec for toolSetRulePaused with paused=true (the pause half of the merged
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

    def "throws when Write master is disabled"() {
        given:
        settingsMap.enableWrite = false

        when: 'the central executeTool gate blocks the write tool (tool body no longer self-gates)'
        script.executeTool('hub_set_rule_paused', [ruleId: 1, paused: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Write tools are disabled')
    }

    @spock.lang.Unroll
    def "hub_set_rule_paused via dispatch returns -32602 envelope when Write master is disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableWrite = false

        when:
        def response = mcpDriver.callTool('hub_set_rule_paused', [ruleId: 1, paused: true])

        then:
        response.error.code == -32602
        response.error.message.contains('Write tools are disabled')

        where:
        useGateways << [true, false]
    }

    def "throws when ruleId is missing"() {
        when:
        script.toolSetRulePaused([paused: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('ruleid is required')
    }

    @spock.lang.Unroll
    def "hub_set_rule_paused via dispatch returns -32602 envelope when ruleId is missing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_set_rule_paused', [paused: true])

        then:
        response.error.code == -32602
        response.error.message.toLowerCase().contains('ruleid is required')

        where:
        useGateways << [true, false]
    }

    def "golden path: dispatches pauseRule sendAction for the given ruleId"() {
        when:
        def result = script.toolSetRulePaused([ruleId: 400, paused: true])

        then:
        result.success == true
        result.ruleId == 400
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'pauseRule' }
    }

    def "result echoes the applied paused state (BUG-12: no paused-state in response)"() {
        expect: "the response confirms the applied state so callers don't need a follow-up read"
        script.toolSetRulePaused([ruleId: 400, paused: true]).paused == true
        script.toolSetRulePaused([ruleId: 400, paused: false]).paused == false
    }

    @spock.lang.Unroll
    def "hub_set_rule_paused via dispatch dispatches pauseRule sendAction for the given ruleId (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_set_rule_paused', [ruleId: 400, paused: true])

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
        when:
        def result = script.toolSetRulePaused([ruleId: '401', paused: true])

        then:
        result.success == true
        result.ruleId == 401
        result.ruleId instanceof Integer
    }

    @spock.lang.Unroll
    def "hub_set_rule_paused via dispatch coerces String ruleId to Integer (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_set_rule_paused', [ruleId: '401', paused: true])

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
        when:
        script.toolSetRulePaused([ruleId: 'abc', paused: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('integer')
    }

    @spock.lang.Unroll
    def "hub_set_rule_paused via dispatch returns -32602 envelope on non-numeric ruleId (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_set_rule_paused', [ruleId: 'abc', paused: true])

        then:
        response.error.code == -32602
        response.error.message.toLowerCase().contains('integer')

        where:
        useGateways << [true, false]
    }

    def "gateway dispatch via handleGateway routes to hub_set_rule_paused"() {
        when:
        def result = script.handleGateway('hub_manage_native_rules_and_apps', 'hub_set_rule_paused', [ruleId: 500, paused: true])

        then:
        result.success == true
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'pauseRule' }
    }
}
