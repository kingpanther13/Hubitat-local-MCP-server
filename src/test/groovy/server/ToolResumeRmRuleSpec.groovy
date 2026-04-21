package server

import support.RMUtilsMock
import support.ToolSpecBase

/**
 * Spec for toolResumeRmRule (hubitat-mcp-server.groovy approx line 7819).
 * Gateway: manage_rule_machine -> resume_rm_rule.
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
        settingsMap.enableBuiltinAppRead = false

        when:
        script.toolResumeRmRule([ruleId: 1])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Built-in App')
    }

    def "throws when ruleId is missing"() {
        given:
        settingsMap.enableBuiltinAppRead = true

        when:
        script.toolResumeRmRule([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('ruleid is required')
    }

    def "golden path: dispatches resumeRule sendAction for the given ruleId"() {
        given:
        settingsMap.enableBuiltinAppRead = true

        when:
        def result = script.toolResumeRmRule([ruleId: 600])

        then:
        result.success == true
        result.ruleId == 600
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'resumeRule' }
    }

    def "String ruleId is coerced to Integer"() {
        given:
        settingsMap.enableBuiltinAppRead = true

        when:
        def result = script.toolResumeRmRule([ruleId: '601'])

        then:
        result.success == true
        result.ruleId == 601
        result.ruleId instanceof Integer
    }

    def "non-numeric ruleId throws IllegalArgumentException"() {
        given:
        settingsMap.enableBuiltinAppRead = true

        when:
        script.toolResumeRmRule([ruleId: 'xyz'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('integer')
    }

    def "gateway dispatch via handleGateway routes to resume_rm_rule"() {
        given:
        settingsMap.enableBuiltinAppRead = true

        when:
        def result = script.handleGateway('manage_rule_machine', 'resume_rm_rule', [ruleId: 700])

        then:
        result.success == true
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'resumeRule' }
    }
}
