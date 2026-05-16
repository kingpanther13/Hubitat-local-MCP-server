package server

import support.RMUtilsMock
import support.ToolSpecBase

/**
 * Spec for toolPauseRmRule (hubitat-mcp-server.groovy approx line 7809).
 * Gateway: manage_native_rules_and_apps -> pause_rm_rule.
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
        script.toolPauseRmRule([ruleId: 1])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Built-in App')
    }

    @spock.lang.Unroll
    def "pause_rm_rule via dispatch returns -32602 envelope when Built-in App Read is disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableBuiltinApp = false

        when:
        def response = mcpDriver.callTool('pause_rm_rule', [ruleId: 1])

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
        script.toolPauseRmRule([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('ruleid is required')
    }

    @spock.lang.Unroll
    def "pause_rm_rule via dispatch returns -32602 envelope when ruleId is missing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableBuiltinApp = true

        when:
        def response = mcpDriver.callTool('pause_rm_rule', [:])

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
        def result = script.toolPauseRmRule([ruleId: 400])

        then:
        result.success == true
        result.ruleId == 400
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'pauseRule' }
    }

    @spock.lang.Unroll
    def "pause_rm_rule via dispatch dispatches pauseRule sendAction for the given ruleId (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableBuiltinApp = true

        when:
        def response = mcpDriver.callTool('pause_rm_rule', [ruleId: 400])

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
        def result = script.toolPauseRmRule([ruleId: '401'])

        then:
        result.success == true
        result.ruleId == 401
        result.ruleId instanceof Integer
    }

    @spock.lang.Unroll
    def "pause_rm_rule via dispatch coerces String ruleId to Integer (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableBuiltinApp = true

        when:
        def response = mcpDriver.callTool('pause_rm_rule', [ruleId: '401'])

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
        script.toolPauseRmRule([ruleId: 'abc'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('integer')
    }

    @spock.lang.Unroll
    def "pause_rm_rule via dispatch returns -32602 envelope on non-numeric ruleId (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableBuiltinApp = true

        when:
        def response = mcpDriver.callTool('pause_rm_rule', [ruleId: 'abc'])

        then:
        response.error.code == -32602
        response.error.message.toLowerCase().contains('integer')

        where:
        useGateways << [true, false]
    }

    def "gateway dispatch via handleGateway routes to pause_rm_rule"() {
        given:
        settingsMap.enableBuiltinApp = true

        when:
        def result = script.handleGateway('manage_native_rules_and_apps', 'pause_rm_rule', [ruleId: 500])

        then:
        result.success == true
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'pauseRule' }
    }
}
