package server

import support.RMUtilsMock
import support.ToolSpecBase

/**
 * Spec for toolListRmRules (hubitat-mcp-server.groovy approx line 7644).
 * Gateway: manage_rule_machine -> list_rm_rules.
 *
 * Covers: gate-throw, RM 5.x Map shape with Integer key coercion, RM 4.x
 * explicit-fields shape, dedup when same id in both, the "both-absent quiet
 * path" (returns count=0 note without success=false), mixed failure shapes,
 * narrow-scope classifier (MissingMethodException only for getRuleList,
 * Cannot-get-property only with 'helper' substring).
 */
class ToolListRmRulesSpec extends ToolSpecBase {

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
        script.toolListRmRules([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Built-in App')
    }

    def "RM 5.x single-entry Map shape with mixed String/Integer keys coerces all ids to Integer"() {
        given:
        settingsMap.enableBuiltinAppRead = true

        and: 'one entry has a String key, the other an Integer key'
        rmUtils.stubRuleList5 = [
            ['413': 'Living Room Relax'],
            [832: 'Auto - Living Room TV Lights']
        ]

        when:
        def result = script.toolListRmRules([:])

        then:
        result.rules.size() == 2
        result.rules.every { it.id instanceof Integer }
        result.rules*.id.sort() == [413, 832]
        result.count == 2
    }

    def "RM 4.x explicit-fields shape passes all fields through"() {
        given:
        settingsMap.enableBuiltinAppRead = true

        rmUtils.stubRuleList4 = [
            [id: 100, label: 'Alpha', name: 'A', type: 'Rule-4.1']
        ]

        when:
        def result = script.toolListRmRules([:])

        then:
        result.rules.size() == 1
        def rule = result.rules[0]
        rule.id == 100
        rule.label == 'Alpha'
        rule.name == 'A'
        rule.type == 'Rule-4.1'
    }

    def "same rule id in both v4 and v5 appears exactly once (first-seen wins)"() {
        given:
        settingsMap.enableBuiltinAppRead = true

        rmUtils.stubRuleList4 = [[id: 200, label: 'From v4', name: 'From v4', type: 'Rule-4.1']]
        rmUtils.stubRuleList5 = [[200: 'From v5']]

        when:
        def result = script.toolListRmRules([:])

        then: 'combined map deduplicates; v4 was registered first'
        result.rules.size() == 1
        result.rules[0].id == 200
        result.rules[0].rmVersion == '4.x'
    }

    def "both-absent quiet path: count=0, informational note, success NOT false"() {
        given:
        settingsMap.enableBuiltinAppRead = true

        and: 'both versions throw NoClassDefFoundError (RM not installed)'
        rmUtils.throwOnGetRuleList4 = new NoClassDefFoundError("hubitat.helper.RMUtils")
        rmUtils.throwOnGetRuleList5 = new NoClassDefFoundError("hubitat.helper.RMUtils")

        when:
        def result = script.toolListRmRules([:])

        then: 'quiet: count 0, note mentioning rule machine, but NO success=false'
        result.count == 0
        result.note?.toLowerCase()?.contains('rule machine')
        result.success != false  // unset or truthy
        result.warning == null
    }

    def "both-absent via sandbox-wrapped 'Cannot get property helper' shape: quiet path"() {
        given:
        settingsMap.enableBuiltinAppRead = true
        // The real Hubitat Groovy sandbox resolves unknown `hubitat.helper.X`
        // namespace lookups to null, and the subsequent property dereference
        // throws this shape -- verified via live probe during PR #79 review.
        // Classifier at hubitat-mcp-server.groovy:7692 scopes the match to
        // the 'helper' substring.
        rmUtils.throwOnGetRuleList4 = new RuntimeException("Cannot get property 'helper' on null object")
        rmUtils.throwOnGetRuleList5 = new RuntimeException("Cannot get property 'helper' on null object")

        when:
        def result = script.toolListRmRules([:])

        then:
        result.count == 0
        result.note?.toLowerCase()?.contains('rule machine')
        result.success != false  // unset or truthy
        result.warning == null
    }

    def "both failed with mixed shapes returns success=false error mentioning both versions"() {
        given:
        settingsMap.enableBuiltinAppRead = true

        and: 'v4 is class-missing but v5 is a hard RuntimeException -- not a quiet path'
        rmUtils.throwOnGetRuleList4 = new NoClassDefFoundError("hubitat.helper.RMUtils")
        rmUtils.throwOnGetRuleList5 = new RuntimeException("timeout connecting to RM")

        when:
        def result = script.toolListRmRules([:])

        then:
        result.success == false
        result.error?.contains('v4=') == true
        result.error?.contains('v5=') == true
    }

    def "MissingMethodException mentioning getRuleList on one side is quiet (old firmware shape)"() {
        given:
        settingsMap.enableBuiltinAppRead = true

        and: 'v4 throws an MME scoped to getRuleList (old firmware, no getRuleList overload)'
        rmUtils.throwOnGetRuleList4 = new RuntimeException("No signature of method getRuleList() is applicable")
        rmUtils.stubRuleList5 = [[500: 'My v5 Rule']]

        when:
        def result = script.toolListRmRules([:])

        then: 'v5 rules are returned without a warning -- the MME was scoped to getRuleList'
        result.rules.size() == 1
        result.rules[0].id == 500
        result.warning == null
    }

    def "MissingMethodException NOT scoped to getRuleList surfaces as a warning"() {
        given:
        settingsMap.enableBuiltinAppRead = true

        and: 'v4 throws an MME for an unrelated method name (not getRuleList)'
        rmUtils.throwOnGetRuleList4 = new RuntimeException("No signature of method setXYZ() is applicable")
        rmUtils.stubRuleList5 = [[600: 'My Rule']]

        when:
        def result = script.toolListRmRules([:])

        then: 'warning surfaced because the MME was not scoped to getRuleList'
        result.rules.size() == 1
        result.warning != null
        result.warning.contains('setXYZ')
    }

    def "Cannot-get-property with 'helper' substring is quiet (hubitat.helper path)"() {
        given:
        settingsMap.enableBuiltinAppRead = true

        and: "v4 throws the sandbox null-resolution error for hubitat.helper.RMUtils"
        rmUtils.throwOnGetRuleList4 = new RuntimeException("Cannot get property 'helper' on null object")
        rmUtils.stubRuleList5 = [[700: 'Only v5 Rule']]

        when:
        def result = script.toolListRmRules([:])

        then: 'quiet: the helper-path null-resolution is treated as class-missing'
        result.rules.size() == 1
        result.warning == null
    }

    def "Cannot-get-property WITHOUT 'helper' substring surfaces as a warning"() {
        given:
        settingsMap.enableBuiltinAppRead = true

        and: 'v4 throws a Cannot-get-property error unrelated to the helper path'
        rmUtils.throwOnGetRuleList4 = new RuntimeException("Cannot get property 'label' on null object")
        rmUtils.stubRuleList5 = [[800: 'Rule']]

        when:
        def result = script.toolListRmRules([:])

        then: 'warning surfaced -- not the helper-path pattern'
        result.rules.size() == 1
        result.warning != null
    }

    def "hard RuntimeException on one side with other succeeding surfaces warning"() {
        given:
        settingsMap.enableBuiltinAppRead = true

        rmUtils.throwOnGetRuleList4 = new RuntimeException("timeout")
        rmUtils.stubRuleList5 = [[900: 'My Rule']]

        when:
        def result = script.toolListRmRules([:])

        then: 'results from v5 present, warning mentions the v4 failure'
        result.rules.size() == 1
        result.warning != null
        result.warning.contains('v4=')
    }

    def "gateway dispatch via handleGateway routes to list_rm_rules"() {
        given:
        settingsMap.enableBuiltinAppRead = true
        rmUtils.stubRuleList5 = [[10: 'Test Rule']]

        when:
        def result = script.handleGateway('manage_rule_machine', 'list_rm_rules', [:])

        then:
        result.rules instanceof List
        result.count instanceof Integer
    }
}
