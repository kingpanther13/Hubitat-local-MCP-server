package server

import support.RMUtilsMock
import support.ToolSpecBase
import groovy.json.JsonOutput

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

    def "array ruleId pauses the whole set in ONE sendAction dispatch"() {
        when:
        def result = script.toolSetRulePaused([ruleId: [400, 401, 402], paused: true])

        then:
        result.success == true
        result.ruleIds == [400, 401, 402]
        result.ruleId == null
        result.paused == true
        def sends = rmUtils.calls.findAll { it.method == 'sendAction' && it.action == 'pauseRule' }
        sends.size() == 1
        sends[0].ruleIds == [400, 401, 402]
    }

    def "array ruleId coerces String ids and dedupes preserving order"() {
        when:
        def result = script.toolSetRulePaused([ruleId: ['402', 400, '400'], paused: false])

        then:
        result.success == true
        result.ruleIds == [402, 400]
        result.paused == false
        rmUtils.calls.find { it.method == 'sendAction' }.ruleIds == [402, 400]
    }

    def "single-element array keeps the single-id response shape (ruleId present)"() {
        when:
        def result = script.toolSetRulePaused([ruleId: [400], paused: true])

        then:
        result.success == true
        result.ruleId == 400
        result.ruleIds == [400]
    }

    def "empty ruleId array throws IllegalArgumentException"() {
        when:
        script.toolSetRulePaused([ruleId: [], paused: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('must not be empty')
    }

    def "a null element inside the ruleId array is rejected as a validation error, not an NPE"() {
        when:
        script.toolSetRulePaused([ruleId: [400, null], paused: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('integer')
        rmUtils.calls.isEmpty()
    }

    @spock.lang.Unroll
    def "a NON-INTEGER-VALUED id in the array is rejected instead of truncating to a different rule (#kind)"() {
        // The list arg coerces through _rmCoerceRuleId, not normalizeRuleId: a JSON number
        // 400.7 would silently truncate to rule 400 via toInteger() -- and then PASS the
        // existence check, pausing the wrong rule with full confidence.
        when:
        script.toolSetRulePaused([ruleId: [badId], paused: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("is not an integer-valued rule id")
        ex.message.contains("hub_list_rules")

        and: "nothing was dispatched -- the coercion refusal is pre-write"
        !rmUtils.calls.any { it.method == 'sendAction' }

        where:
        kind                   | badId
        "JSON number 400.7"    | 400.7
        "decimal string 400.7" | "400.7"
    }

    def "a single-id call carries NO idsVerified key at all"() {
        when:
        def result = script.toolSetRulePaused([ruleId: 400, paused: true])

        then: "the existence check is a multi-id-batch contract; a single id never claims verification"
        result.success == true
        !result.containsKey('idsVerified')
    }

    def "a multi-id batch whose rule list is UNVERIFIABLE still dispatches, flagged idsVerified false"() {
        when: "no seedValidRuleIds -- /hub2/appsList is unstubbed, so the app-tree cross-check cannot run"
        def result = script.toolSetRulePaused([ruleId: [400, 401], paused: true])

        then: "fire-and-forget dispatch still happens, but the caller is TOLD the check was skipped"
        result.success == true
        result.ruleIds == [400, 401]
        result.idsVerified == false
        rmUtils.calls.findAll { it.method == 'sendAction' }.size() == 1
    }

    // Live app-id tree for the batch existence-check specs. _rmValidRuleIds intersects the
    // RMUtils rule list with this tree, so a rule must appear in BOTH to count as existing.
    private String appsListWithRules(List ruleIds) {
        JsonOutput.toJson([apps: [
            [data: [id: 21, name: "Rule Machine", type: "Rule Machine"],
             children: ruleIds.collect { [data: [id: it, name: "Rule ${it}".toString(), type: "Rule-5.1"], children: []] }]
        ]])
    }

    // Make _rmValidRuleIds resolve to exactly ruleIds. Driven through its own transport --
    // RMUtils plus /hub2/appsList -- because the helper is private and an intra-script call
    // to a private method resolves invokespecial, which a per-instance metaClass stub on the
    // script does not intercept.
    private void seedValidRuleIds(List ruleIds) {
        rmUtils.stubRuleList5 = ruleIds.collect { [(it): "Rule ${it}".toString()] }
        hubGet.register('/hub2/appsList') { params -> appsListWithRules(ruleIds) }
    }

    def "a multi-id batch existence-checks every id and refuses unknown ids BEFORE dispatch"() {
        given: "a verifiable rule-id set that lacks 999"
        seedValidRuleIds([400, 401])

        when:
        script.toolSetRulePaused([ruleId: [400, 999], paused: true])

        then: "the batch is refused naming the unknown id, and no dispatch reached RMUtils"
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('999')
        ex.message.contains('hub_list_rules')
        !rmUtils.calls.any { it.method == 'sendAction' }
        rmUtils.calls.any { it.method == 'getRuleList' }
    }

    def "a multi-id batch whose ids all exist passes the existence check and dispatches once"() {
        given:
        seedValidRuleIds([400, 401])

        when:
        def result = script.toolSetRulePaused([ruleId: [400, 401], paused: true])

        then:
        result.success == true
        result.ruleIds == [400, 401]
        result.idsVerified == true
        rmUtils.calls.findAll { it.method == 'sendAction' }.size() == 1

        and: "the check ran against a VERIFIABLE set, so the pass is not the cannot-verify skip"
        // idsVerified above is the caller-visible half of the same claim; this is the
        // transport-level proof that it was earned rather than defaulted.
        hubGet.calls.any { it.path == '/hub2/appsList' }
        script._rmValidRuleIds() == ([400, 401] as Set)
    }

    @spock.lang.Unroll
    def "hub_set_rule_paused via dispatch pauses an array of ruleIds in one call (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_set_rule_paused', [ruleId: [400, 401], paused: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.ruleIds == [400, 401]
        rmUtils.calls.findAll { it.method == 'sendAction' && it.action == 'pauseRule' }.size() == 1

        where:
        useGateways << [true, false]
    }
}
