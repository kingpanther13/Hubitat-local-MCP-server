package server

import support.RMUtilsMock
import support.ToolSpecBase

/**
 * Spec for toolSetRmRuleBoolean (hubitat-mcp-server.groovy approx line 7837).
 * Gateway: manage_native_rules_and_apps -> set_rm_rule_boolean.
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

    def "throws when Built-in App Read is disabled"() {
        given:
        settingsMap.enableBuiltinApp = false

        when:
        script.toolSetRmRuleBoolean([ruleId: 1, value: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Built-in App')
    }

    def "throws when ruleId is missing"() {
        given:
        settingsMap.enableBuiltinApp = true

        when:
        script.toolSetRmRuleBoolean([value: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('ruleid is required')
    }

    def "throws when value is missing"() {
        given:
        settingsMap.enableBuiltinApp = true

        when:
        script.toolSetRmRuleBoolean([ruleId: 1])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('value')
    }

    def "Boolean true dispatches setRuleBooleanTrue"() {
        given:
        settingsMap.enableBuiltinApp = true

        when:
        def result = script.toolSetRmRuleBoolean([ruleId: 800, value: true])

        then:
        result.success == true
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'setRuleBooleanTrue' }
    }

    def "Boolean false dispatches setRuleBooleanFalse"() {
        given:
        settingsMap.enableBuiltinApp = true

        when:
        def result = script.toolSetRmRuleBoolean([ruleId: 801, value: false])

        then:
        result.success == true
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'setRuleBooleanFalse' }
    }

    def "String 'true' is accepted and dispatches setRuleBooleanTrue"() {
        given:
        settingsMap.enableBuiltinApp = true

        when:
        def result = script.toolSetRmRuleBoolean([ruleId: 802, value: 'true'])

        then:
        result.success == true
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'setRuleBooleanTrue' }
    }

    def "String 'false' is accepted and dispatches setRuleBooleanFalse"() {
        given:
        settingsMap.enableBuiltinApp = true

        when:
        def result = script.toolSetRmRuleBoolean([ruleId: 803, value: 'false'])

        then:
        result.success == true
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'setRuleBooleanFalse' }
    }

    def "reject matrix: capitalized and uppercase boolean strings are rejected"(Object value) {
        given:
        settingsMap.enableBuiltinApp = true

        when:
        script.toolSetRmRuleBoolean([ruleId: 999, value: value])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('value must be boolean')

        where:
        value << ['True', 'False', 'TRUE', 'FALSE']
    }

    def "reject matrix: truthy-looking non-boolean strings are rejected"(Object value) {
        given:
        settingsMap.enableBuiltinApp = true

        when:
        script.toolSetRmRuleBoolean([ruleId: 999, value: value])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('value must be boolean')

        where:
        value << ['yes', 'no', 'on', 'off']
    }

    def "reject matrix: integer values are rejected"(Object value) {
        given:
        settingsMap.enableBuiltinApp = true

        when:
        script.toolSetRmRuleBoolean([ruleId: 999, value: value])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('value must be boolean')

        where:
        value << [1, 0]
    }

    def "gateway dispatch via handleGateway routes to set_rm_rule_boolean"() {
        given:
        settingsMap.enableBuiltinApp = true

        when:
        def result = script.handleGateway('manage_native_rules_and_apps', 'set_rm_rule_boolean', [ruleId: 900, value: true])

        then:
        result.success == true
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'setRuleBooleanTrue' }
    }
}
