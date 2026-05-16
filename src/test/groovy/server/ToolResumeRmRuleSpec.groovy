package server

import support.RMUtilsMock
import support.ToolSpecBase

/**
 * Spec for toolResumeRmRule (hubitat-mcp-server.groovy approx line 7819).
 * Gateway: manage_native_rules_and_apps -> resume_rm_rule.
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

    def "throws when Built-in App Read is disabled"() {
        given:
        settingsMap.enableBuiltinApp = false

        when:
        script.toolResumeRmRule([ruleId: 1])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Built-in App')
    }

    @spock.lang.Unroll
    def "resume_rm_rule via dispatch returns -32602 envelope when Built-in App Read is disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableBuiltinApp = false

        when:
        def response = mcpDriver.callTool('resume_rm_rule', [ruleId: 1])

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
        script.toolResumeRmRule([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('ruleid is required')
    }

    @spock.lang.Unroll
    def "resume_rm_rule via dispatch returns -32602 envelope when ruleId is missing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableBuiltinApp = true

        when:
        def response = mcpDriver.callTool('resume_rm_rule', [:])

        then:
        response.error.code == -32602
        response.error.message.toLowerCase().contains('ruleid is required')

        where:
        useGateways << [true, false]
    }

    def "golden path: dispatches resumeRule sendAction for the given ruleId"() {
        given:
        settingsMap.enableBuiltinApp = true

        when:
        def result = script.toolResumeRmRule([ruleId: 600])

        then:
        result.success == true
        result.ruleId == 600
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'resumeRule' }
    }

    @spock.lang.Unroll
    def "resume_rm_rule via dispatch dispatches resumeRule sendAction for the given ruleId (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableBuiltinApp = true

        when:
        def response = mcpDriver.callTool('resume_rm_rule', [ruleId: 600])

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
        given:
        settingsMap.enableBuiltinApp = true

        when:
        def result = script.toolResumeRmRule([ruleId: '601'])

        then:
        result.success == true
        result.ruleId == 601
        result.ruleId instanceof Integer
    }

    @spock.lang.Unroll
    def "resume_rm_rule via dispatch coerces String ruleId to Integer (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableBuiltinApp = true

        when:
        def response = mcpDriver.callTool('resume_rm_rule', [ruleId: '601'])

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
        given:
        settingsMap.enableBuiltinApp = true

        when:
        script.toolResumeRmRule([ruleId: 'xyz'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('integer')
    }

    @spock.lang.Unroll
    def "resume_rm_rule via dispatch returns -32602 envelope on non-numeric ruleId (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableBuiltinApp = true

        when:
        def response = mcpDriver.callTool('resume_rm_rule', [ruleId: 'xyz'])

        then:
        response.error.code == -32602
        response.error.message.toLowerCase().contains('integer')

        where:
        useGateways << [true, false]
    }

    def "gateway dispatch via handleGateway routes to resume_rm_rule"() {
        given:
        settingsMap.enableBuiltinApp = true

        when:
        def result = script.handleGateway('manage_native_rules_and_apps', 'resume_rm_rule', [ruleId: 700])

        then:
        result.success == true
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'resumeRule' }
    }
}
