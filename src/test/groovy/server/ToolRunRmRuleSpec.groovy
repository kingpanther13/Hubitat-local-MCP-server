package server

import support.RMUtilsMock
import support.ToolSpecBase

/**
 * Spec for toolRunRmRule (hubitat-mcp-server.groovy approx line 7788).
 * Gateway: manage_native_rules_and_apps -> run_rm_rule.
 *
 * Covers: gate-throw, missing ruleId, action-to-rmAction mapping (rule/actions/stop),
 * invalid action rejection, String ruleId coercion, non-numeric ruleId rejection.
 */
class ToolRunRmRuleSpec extends ToolSpecBase {

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
        script.toolRunRmRule([ruleId: 1])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Built-in App')
    }

    def "throws when ruleId is missing"() {
        given:
        settingsMap.enableBuiltinAppRead = true

        when:
        script.toolRunRmRule([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('ruleid is required')
    }

    def "action=rule dispatches runRule"() {
        given:
        settingsMap.enableBuiltinAppRead = true

        when:
        def result = script.toolRunRmRule([ruleId: 101, action: 'rule'])

        then:
        result.success == true
        result.ruleId == 101
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'runRule' }
    }

    def "action=actions dispatches runRuleAct"() {
        given:
        settingsMap.enableBuiltinAppRead = true

        when:
        def result = script.toolRunRmRule([ruleId: 102, action: 'actions'])

        then:
        result.success == true
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'runRuleAct' }
    }

    def "action=stop dispatches stopRuleAct"() {
        given:
        settingsMap.enableBuiltinAppRead = true

        when:
        def result = script.toolRunRmRule([ruleId: 103, action: 'stop'])

        then:
        result.success == true
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'stopRuleAct' }
    }

    def "default action (no action arg) dispatches runRule"() {
        given:
        settingsMap.enableBuiltinAppRead = true

        when:
        def result = script.toolRunRmRule([ruleId: 104])

        then:
        result.success == true
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'runRule' }
    }

    def "invalid action throws IllegalArgumentException"() {
        given:
        settingsMap.enableBuiltinAppRead = true

        when:
        script.toolRunRmRule([ruleId: 105, action: 'explode'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('invalid action')
    }

    def "String ruleId is coerced to Integer before dispatch"() {
        given:
        settingsMap.enableBuiltinAppRead = true

        when:
        def result = script.toolRunRmRule([ruleId: '202'])

        then:
        result.success == true
        result.ruleId == 202
        result.ruleId instanceof Integer
    }

    def "non-numeric ruleId throws IllegalArgumentException"() {
        given:
        settingsMap.enableBuiltinAppRead = true

        when:
        script.toolRunRmRule([ruleId: 'not-a-number'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('integer')
    }

    def "gateway dispatch via handleGateway routes to run_rm_rule"() {
        given:
        settingsMap.enableBuiltinAppRead = true

        when:
        def result = script.handleGateway('manage_native_rules_and_apps', 'run_rm_rule', [ruleId: 300, action: 'rule'])

        then:
        result.success == true
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'runRule' }
    }
}
