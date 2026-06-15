package server

import support.RMUtilsMock
import support.ToolSpecBase

/**
 * Spec for toolListRmRules (libraries/mcp-native-rules-lib.groovy).
 * Gateway: hub_manage_native_rules_and_apps -> hub_list_rules.
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

    def "throws when Read master is disabled"() {
        given:
        settingsMap.enableRead = false

        when: 'the central executeTool gate blocks the read tool (tool body no longer self-gates)'
        script.executeTool('hub_list_rules', [:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Read tools are disabled')
    }

    @spock.lang.Unroll
    def "hub_list_rules via dispatch returns -32602 envelope when Read master is disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = false

        when:
        def response = mcpDriver.callTool('hub_list_rules', [:])

        then:
        response.error.code == -32602
        response.error.message.contains('Read tools are disabled')

        where:
        useGateways << [true, false]
    }

    def "RM 5.x single-entry Map shape with mixed String/Integer keys coerces all ids to Integer"() {
        given: 'one entry has a String key, the other an Integer key'
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

    @spock.lang.Unroll
    def "hub_list_rules via dispatch coerces mixed String/Integer keys to Integer (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        rmUtils.stubRuleList5 = [
            ['413': 'Living Room Relax'],
            [832: 'Auto - Living Room TV Lights']
        ]

        when:
        def response = mcpDriver.callTool('hub_list_rules', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.rules.size() == 2
        inner.rules.every { it.id instanceof Integer }
        inner.rules*.id.sort() == [413, 832]
        inner.count == 2

        where:
        useGateways << [true, false]
    }

    def "RM 4.x explicit-fields shape passes all fields through"() {
        given:
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

    @spock.lang.Unroll
    def "hub_list_rules via dispatch passes RM 4.x explicit fields through (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        rmUtils.stubRuleList4 = [
            [id: 100, label: 'Alpha', name: 'A', type: 'Rule-4.1']
        ]

        when:
        def response = mcpDriver.callTool('hub_list_rules', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.rules.size() == 1
        def rule = inner.rules[0]
        rule.id == 100
        rule.label == 'Alpha'
        rule.name == 'A'
        rule.type == 'Rule-4.1'

        where:
        useGateways << [true, false]
    }

    def "same rule id in both v4 and v5 appears exactly once (first-seen wins)"() {
        given:
        rmUtils.stubRuleList4 = [[id: 200, label: 'From v4', name: 'From v4', type: 'Rule-4.1']]
        rmUtils.stubRuleList5 = [[200: 'From v5']]

        when:
        def result = script.toolListRmRules([:])

        then: 'combined map deduplicates; v4 was registered first'
        result.rules.size() == 1
        result.rules[0].id == 200
        result.rules[0].rmVersion == '4.x'
    }

    @spock.lang.Unroll
    def "hub_list_rules via dispatch dedups same id across v4 and v5 (first-seen wins) (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        rmUtils.stubRuleList4 = [[id: 200, label: 'From v4', name: 'From v4', type: 'Rule-4.1']]
        rmUtils.stubRuleList5 = [[200: 'From v5']]

        when:
        def response = mcpDriver.callTool('hub_list_rules', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.rules.size() == 1
        inner.rules[0].id == 200
        inner.rules[0].rmVersion == '4.x'

        where:
        useGateways << [true, false]
    }

    def "both-absent quiet path: count=0, informational note, success NOT false"() {
        given: 'both versions throw NoClassDefFoundError (RM not installed)'
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

    @spock.lang.Unroll
    def "hub_list_rules via dispatch both-absent NoClassDefFound quiet path (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        rmUtils.throwOnGetRuleList4 = new NoClassDefFoundError("hubitat.helper.RMUtils")
        rmUtils.throwOnGetRuleList5 = new NoClassDefFoundError("hubitat.helper.RMUtils")

        when:
        def response = mcpDriver.callTool('hub_list_rules', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.count == 0
        inner.note?.toLowerCase()?.contains('rule machine')
        inner.success != false
        inner.warning == null

        where:
        useGateways << [true, false]
    }

    def "both-absent via sandbox-wrapped 'Cannot get property helper' shape: quiet path"() {
        given:
        // The real Hubitat Groovy sandbox resolves unknown `hubitat.helper.X`
        // namespace lookups to null, and the subsequent property dereference
        // throws this shape -- verified via live probe during PR #79 review.
        // Classifier in libraries/mcp-native-rules-lib.groovy scopes the match to
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

    @spock.lang.Unroll
    def "hub_list_rules via dispatch both-absent 'Cannot get property helper' quiet path (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        rmUtils.throwOnGetRuleList4 = new RuntimeException("Cannot get property 'helper' on null object")
        rmUtils.throwOnGetRuleList5 = new RuntimeException("Cannot get property 'helper' on null object")

        when:
        def response = mcpDriver.callTool('hub_list_rules', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.count == 0
        inner.note?.toLowerCase()?.contains('rule machine')
        inner.success != false
        inner.warning == null

        where:
        useGateways << [true, false]
    }

    def "both failed with mixed shapes returns success=false error mentioning both versions"() {
        given: 'v4 is class-missing but v5 is a hard RuntimeException -- not a quiet path'
        rmUtils.throwOnGetRuleList4 = new NoClassDefFoundError("hubitat.helper.RMUtils")
        rmUtils.throwOnGetRuleList5 = new RuntimeException("timeout connecting to RM")

        when:
        def result = script.toolListRmRules([:])

        then:
        result.success == false
        result.error?.contains('v4=') == true
        result.error?.contains('v5=') == true
    }

    @spock.lang.Unroll
    def "hub_list_rules via dispatch both-failed mixed shapes returns success=false (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        rmUtils.throwOnGetRuleList4 = new NoClassDefFoundError("hubitat.helper.RMUtils")
        rmUtils.throwOnGetRuleList5 = new RuntimeException("timeout connecting to RM")

        when:
        def response = mcpDriver.callTool('hub_list_rules', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error?.contains('v4=') == true
        inner.error?.contains('v5=') == true

        where:
        useGateways << [true, false]
    }

    def "MissingMethodException mentioning getRuleList on one side is quiet (old firmware shape)"() {
        given: 'v4 throws an MME scoped to getRuleList (old firmware, no getRuleList overload)'
        rmUtils.throwOnGetRuleList4 = new RuntimeException("No signature of method getRuleList() is applicable")
        rmUtils.stubRuleList5 = [[500: 'My v5 Rule']]

        when:
        def result = script.toolListRmRules([:])

        then: 'v5 rules are returned without a warning -- the MME was scoped to getRuleList'
        result.rules.size() == 1
        result.rules[0].id == 500
        result.warning == null
    }

    @spock.lang.Unroll
    def "hub_list_rules via dispatch MissingMethodException on getRuleList is quiet (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        rmUtils.throwOnGetRuleList4 = new RuntimeException("No signature of method getRuleList() is applicable")
        rmUtils.stubRuleList5 = [[500: 'My v5 Rule']]

        when:
        def response = mcpDriver.callTool('hub_list_rules', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.rules.size() == 1
        inner.rules[0].id == 500
        inner.warning == null

        where:
        useGateways << [true, false]
    }

    def "MissingMethodException NOT scoped to getRuleList surfaces as a warning"() {
        given: 'v4 throws an MME for an unrelated method name (not getRuleList)'
        rmUtils.throwOnGetRuleList4 = new RuntimeException("No signature of method setXYZ() is applicable")
        rmUtils.stubRuleList5 = [[600: 'My Rule']]

        when:
        def result = script.toolListRmRules([:])

        then: 'warning surfaced because the MME was not scoped to getRuleList'
        result.rules.size() == 1
        result.warning != null
        result.warning.contains('setXYZ')
    }

    @spock.lang.Unroll
    def "hub_list_rules via dispatch MME not scoped to getRuleList surfaces warning (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        rmUtils.throwOnGetRuleList4 = new RuntimeException("No signature of method setXYZ() is applicable")
        rmUtils.stubRuleList5 = [[600: 'My Rule']]

        when:
        def response = mcpDriver.callTool('hub_list_rules', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.rules.size() == 1
        inner.warning != null
        inner.warning.contains('setXYZ')

        where:
        useGateways << [true, false]
    }

    def "Cannot-get-property with 'helper' substring is quiet (hubitat.helper path)"() {
        given: "v4 throws the sandbox null-resolution error for hubitat.helper.RMUtils"
        rmUtils.throwOnGetRuleList4 = new RuntimeException("Cannot get property 'helper' on null object")
        rmUtils.stubRuleList5 = [[700: 'Only v5 Rule']]

        when:
        def result = script.toolListRmRules([:])

        then: 'quiet: the helper-path null-resolution is treated as class-missing'
        result.rules.size() == 1
        result.warning == null
    }

    @spock.lang.Unroll
    def "hub_list_rules via dispatch Cannot-get-property helper substring is quiet (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        rmUtils.throwOnGetRuleList4 = new RuntimeException("Cannot get property 'helper' on null object")
        rmUtils.stubRuleList5 = [[700: 'Only v5 Rule']]

        when:
        def response = mcpDriver.callTool('hub_list_rules', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.rules.size() == 1
        inner.warning == null

        where:
        useGateways << [true, false]
    }

    def "Cannot-get-property WITHOUT 'helper' substring surfaces as a warning"() {
        given: 'v4 throws a Cannot-get-property error unrelated to the helper path'
        rmUtils.throwOnGetRuleList4 = new RuntimeException("Cannot get property 'label' on null object")
        rmUtils.stubRuleList5 = [[800: 'Rule']]

        when:
        def result = script.toolListRmRules([:])

        then: 'warning surfaced -- not the helper-path pattern'
        result.rules.size() == 1
        result.warning != null
    }

    @spock.lang.Unroll
    def "hub_list_rules via dispatch Cannot-get-property WITHOUT helper surfaces warning (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        rmUtils.throwOnGetRuleList4 = new RuntimeException("Cannot get property 'label' on null object")
        rmUtils.stubRuleList5 = [[800: 'Rule']]

        when:
        def response = mcpDriver.callTool('hub_list_rules', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.rules.size() == 1
        inner.warning != null

        where:
        useGateways << [true, false]
    }

    def "hard RuntimeException on one side with other succeeding surfaces warning"() {
        given:
        rmUtils.throwOnGetRuleList4 = new RuntimeException("timeout")
        rmUtils.stubRuleList5 = [[900: 'My Rule']]

        when:
        def result = script.toolListRmRules([:])

        then: 'results from v5 present, warning mentions the v4 failure'
        result.rules.size() == 1
        result.warning != null
        result.warning.contains('v4=')
    }

    @spock.lang.Unroll
    def "hub_list_rules via dispatch hard RuntimeException on one side surfaces warning (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        rmUtils.throwOnGetRuleList4 = new RuntimeException("timeout")
        rmUtils.stubRuleList5 = [[900: 'My Rule']]

        when:
        def response = mcpDriver.callTool('hub_list_rules', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.rules.size() == 1
        inner.warning != null
        inner.warning.contains('v4=')

        where:
        useGateways << [true, false]
    }

    def "registerRmRule skips entries with non-Integer-coercible keys and emits a sandbox-safe warn log"() {
        given: 'collect mcpLog calls via per-test metaClass override'
        // mcpLog is a script-local method (not inherited from AppExecutor /
        // HubitatAppScript), so per-instance metaClass override works here —
        // see docs/testing.md "Which interception point to use".
        def mcpLogCalls = []
        script.metaClass.mcpLog = { String level, String component, String msg ->
            mcpLogCalls << [level: level, component: component, msg: msg]
        }

        and: 'v5 list contains one bad String key and one valid numeric key'
        rmUtils.stubRuleList5 = [
            ['not-a-number': 'Bad rule'],    // String key that fails toInteger()
            [123: 'Good rule']               // numeric key, should pass
        ]

        when:
        def result = script.toolListRmRules([:])

        then: 'only the valid rule is returned'
        result.rules*.id == [123]
        result.count == 1
        result.success != false

        and: 'warn log fires with the instanceof-ladder type classification'
        // Catches: deleting the mcpLog call, dropping the NumberFormatException
        // catch, removing (type=${keyType}) from the template, or collapsing
        // the instanceof ladder so a String key no longer yields 'String'.
        //
        // Does NOT catch a getClass() revert on its own: under HarnessSpec's
        // Flags.DontRestrictGroovy, rawKey.getClass().simpleName returns
        // "String" at runtime -- byte-identical to the instanceof-ladder
        // output for this fixture, so the message text is unchanged. The
        // getClass()-revert guard lives in sandbox_lint.py's SANDBOX-001
        // (GString-aware after #103); this assertion is orthogonal to it.
        mcpLogCalls.any {
            it.level == 'warn' &&
            it.component == 'rm-interop' &&
            it.msg.contains("'not-a-number'") &&
            it.msg.contains('type=String')
        }
    }

    @spock.lang.Unroll
    def "hub_list_rules via dispatch skips non-Integer-coercible keys and emits warn log (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        and: 'collect mcpLog calls via per-test metaClass override'
        def mcpLogCalls = []
        script.metaClass.mcpLog = { String level, String component, String msg ->
            mcpLogCalls << [level: level, component: component, msg: msg]
        }

        and: 'v5 list contains one bad String key and one valid numeric key'
        rmUtils.stubRuleList5 = [
            ['not-a-number': 'Bad rule'],
            [123: 'Good rule']
        ]

        when:
        def response = mcpDriver.callTool('hub_list_rules', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.rules*.id == [123]
        inner.count == 1
        inner.success != false

        and: 'warn log fires with the instanceof-ladder type classification'
        mcpLogCalls.any {
            it.level == 'warn' &&
            it.component == 'rm-interop' &&
            it.msg.contains("'not-a-number'") &&
            it.msg.contains('type=String')
        }

        where:
        useGateways << [true, false]
    }

    def "hub_list_rules filters out RMUtils ghosts and reports them in ghostsFiltered"() {
        given: 'two rules in RMUtils; only one is live in /hub2/appsList'
        // RMUtils reports rule 300 and rule 301
        rmUtils.stubRuleList5 = [
            [300: 'Live Rule'],
            [301: 'Ghost Rule']
        ]
        // /hub2/appsList only contains the RM parent (id=21) and rule 300 as a child
        hubGet.register('/hub2/appsList') { params ->
            groovy.json.JsonOutput.toJson([apps: [
                [data: [id: 21, name: "Rule Machine", type: "Rule Machine", user: false, hidden: false],
                 children: [
                    [data: [id: 300, name: "Live Rule", type: "Rule Machine", user: true, hidden: false], children: []]
                    // rule 301 deliberately absent -- simulates a ghost entry in RMUtils cache
                ]]
            ]])
        }

        when:
        def result = script.toolListRmRules([:])

        then: 'only the live rule is returned; ghost is removed from the rules array'
        result.rules.size() == 1
        result.rules[0].id == 300

        and: 'the ghost id is recorded in ghostsFiltered with an explanatory note'
        result.ghostsFiltered == [301]
        result.ghostNote?.contains("no longer exist")
    }

    @spock.lang.Unroll
    def "hub_list_rules via dispatch filters out RMUtils ghosts and reports them in ghostsFiltered (useGateways=#useGateways)"() {
        given: 'two rules in RMUtils; only one is live in /hub2/appsList'
        settingsMap.useGateways = useGateways
        rmUtils.stubRuleList5 = [
            [300: 'Live Rule'],
            [301: 'Ghost Rule']
        ]
        hubGet.register('/hub2/appsList') { params ->
            groovy.json.JsonOutput.toJson([apps: [
                [data: [id: 21, name: "Rule Machine", type: "Rule Machine", user: false, hidden: false],
                 children: [
                    [data: [id: 300, name: "Live Rule", type: "Rule Machine", user: true, hidden: false], children: []]
                ]]
            ]])
        }

        when:
        def response = mcpDriver.callTool('hub_list_rules', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.rules.size() == 1
        inner.rules[0].id == 300

        and: 'the ghost id is recorded in ghostsFiltered with an explanatory note'
        inner.ghostsFiltered == [301]
        inner.ghostNote?.contains("no longer exist")

        where:
        useGateways << [true, false]
    }

    def "gateway dispatch via handleGateway routes to hub_list_rules"() {
        given:
        rmUtils.stubRuleList5 = [[10: 'Test Rule']]

        when:
        def result = script.handleGateway('hub_manage_native_rules_and_apps', 'hub_list_rules', [:])

        then:
        result.rules instanceof List
        result.count instanceof Integer
    }
}
