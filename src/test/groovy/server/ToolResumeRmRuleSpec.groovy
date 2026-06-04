package server

import support.RMUtilsMock
import support.ToolSpecBase

/**
 * Spec for toolSetRulePaused resume path (value:false).
 * Gateway: hub_manage_native_rules_and_apps -> hub_set_rule_paused.
 *
 * Covers: gate-throw, missing ruleId, golden-path resumeRule dispatch,
 * and String ruleId coercion.
 */
class ToolResumeRmRuleSpec extends ToolSpecBase {

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
        script.executeTool('hub_set_rule_paused', [ruleId: 1, value: false])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Write tools are disabled')
    }

    @spock.lang.Unroll
    def "hub_set_rule_paused (resume) via dispatch returns -32602 envelope when Write master is disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableWrite = false

        when:
        def response = mcpDriver.callTool('hub_set_rule_paused', [ruleId: 1, value: false])

        then:
        response.error.code == -32602
        response.error.message.contains('Write tools are disabled')

        where:
        useGateways << [true, false]
    }

    def "throws when ruleId is missing"() {
        when:
        script.toolSetRulePaused([value: false])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('ruleid is required')
    }

    @spock.lang.Unroll
    def "hub_set_rule_paused (resume) via dispatch returns -32602 envelope when ruleId is missing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_set_rule_paused', [value: false])

        then:
        response.error.code == -32602
        response.error.message.toLowerCase().contains('ruleid is required')

        where:
        useGateways << [true, false]
    }

    def "golden path: dispatches resumeRule sendAction for the given ruleId"() {
        when:
        def result = script.toolSetRulePaused([ruleId: 600, value: false])

        then:
        result.success == true
        result.ruleId == 600
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'resumeRule' }
    }

    @spock.lang.Unroll
    def "hub_set_rule_paused (resume) via dispatch dispatches resumeRule sendAction for the given ruleId (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_set_rule_paused', [ruleId: 600, value: false])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.ruleId == 600
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'resumeRule' }

        where:
        useGateways << [true, false]
    }

    def "String ruleId is coerced to Integer"() {
        when:
        def result = script.toolSetRulePaused([ruleId: '601', value: false])

        then:
        result.success == true
        result.ruleId == 601
        result.ruleId instanceof Integer
    }

    @spock.lang.Unroll
    def "hub_set_rule_paused (resume) via dispatch coerces String ruleId to Integer (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_set_rule_paused', [ruleId: '601', value: false])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.ruleId == 601
        inner.ruleId instanceof Integer

        where:
        useGateways << [true, false]
    }

    def "non-numeric ruleId throws IllegalArgumentException"() {
        when:
        script.toolSetRulePaused([ruleId: 'xyz', value: false])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('integer')
    }

    @spock.lang.Unroll
    def "hub_set_rule_paused (resume) via dispatch returns -32602 envelope on non-numeric ruleId (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_set_rule_paused', [ruleId: 'xyz', value: false])

        then:
        response.error.code == -32602
        response.error.message.toLowerCase().contains('integer')

        where:
        useGateways << [true, false]
    }

    def "gateway dispatch via handleGateway routes to hub_set_rule_paused (resume)"() {
        when:
        def result = script.handleGateway('hub_manage_native_rules_and_apps', 'hub_set_rule_paused', [ruleId: 700, value: false])

        then:
        result.success == true
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'resumeRule' }
    }
}
