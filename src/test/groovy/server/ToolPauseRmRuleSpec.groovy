package server

import support.RMUtilsMock
import support.ToolSpecBase

/**
 * Spec for toolPauseRmRule (hubitat-mcp-server.groovy approx line 7809).
 * Gateway: manage_rule_machine -> pause_rm_rule.
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
        settingsMap.enableBuiltinAppRead = false

        when:
        script.toolPauseRmRule([ruleId: 1])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Built-in App')
    }

    def "throws when ruleId is missing"() {
        given:
        settingsMap.enableBuiltinAppRead = true

        when:
        script.toolPauseRmRule([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('ruleid is required')
    }

    def "golden path: dispatches pauseRule sendAction for the given ruleId"() {
        given:
        settingsMap.enableBuiltinAppRead = true

        when:
        def result = script.toolPauseRmRule([ruleId: 400])

        then:
        result.success == true
        result.ruleId == 400
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'pauseRule' }
    }

    def "String ruleId is coerced to Integer"() {
        given:
        settingsMap.enableBuiltinAppRead = true

        when:
        def result = script.toolPauseRmRule([ruleId: '401'])

        then:
        result.success == true
        result.ruleId == 401
        result.ruleId instanceof Integer
    }

    def "non-numeric ruleId throws IllegalArgumentException"() {
        given:
        settingsMap.enableBuiltinAppRead = true

        when:
        script.toolPauseRmRule([ruleId: 'abc'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('integer')
    }

    def "gateway dispatch via handleGateway routes to pause_rm_rule"() {
        given:
        settingsMap.enableBuiltinAppRead = true

        when:
        def result = script.handleGateway('manage_rule_machine', 'pause_rm_rule', [ruleId: 500])

        then:
        result.success == true
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'pauseRule' }
    }
}
