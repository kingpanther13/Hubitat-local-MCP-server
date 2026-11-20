package server

import support.RMUtilsMock
import support.ToolSpecBase

/**
 * Spec for toolListRmRules (libraries/mcp-native-rules-lib.groovy).
 * Gateway: hub_manage_native_rules_and_apps -> hub_list_rules.
 *
 * Covers: gate-throw, the single-entry Map shape with Integer key coercion, the
 * "RM not installed" quiet path (count=0 note without success=false), a hard lookup
 * failure, the narrow-scope classifier (Cannot-get-property only with the 'helper'
 * substring), ghost filtering and the status read from name decoration and state.
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

    @spock.lang.Unroll
    def "filtered rule dispatch reports native type from its existing inventory read (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        rmUtils.stubRuleList5 = [[413: 'Living Room Relax'], [832: 'Kitchen']]
        registerRmAppsList([
            [data: [id: 413, name: 'Living Room Relax', type: 'Rule-5.1', disabled: false], children: []],
            [data: [id: 832, name: 'Kitchen', type: 'Rule-5.1', disabled: false], children: []]
        ])
        hubGet.calls.clear()

        when:
        def response = mcpDriver.callTool('hub_list_rules', [labelFilter: 'ROOM reLAX'])

        then:
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.rules*.id == [413]
        inner.count == 1
        inner.rules[0].type == 'Rule-5.1'
        inner.rules[0].status == 'active'
        hubGet.calls*.path == ['/hub2/appsList']
        script._getAllToolDefinitions_partNativeRM().find { it.name == 'hub_list_rules' }
            .inputSchema.properties.labelFilter.type == 'string'

        where:
        useGateways << [true, false]
    }

    def "label filtering precedes pagination and an empty filter preserves the full list"() {
        given:
        rmUtils.stubRuleList5 = (1..110).collect { id ->
            [(id): id <= 50 ? "Alpha ${id}" : "Target ${id.toString().padLeft(3, '0')}"]
        }

        when:
        def first = script.toolListRmRules([labelFilter: 'tArGeT', cursor: ''])
        def second = script.toolListRmRules([labelFilter: 'tArGeT', cursor: first.nextCursor])
        def missing = script.toolListRmRules([labelFilter: 'No matching rule', cursor: ''])
        def all = script.toolListRmRules([labelFilter: ''])

        then:
        first.count == 50
        first.total == 60
        first.rules*.id == (51..100).toList()
        second.count == 10
        second.total == 60
        second.rules*.id == (101..110).toList()
        !second.nextCursor
        missing.count == 0
        missing.total == 0
        !missing.nextCursor
        all.count == 110
    }

    def "absent native type is left null and never guessed"() {
        given:
        rmUtils.stubRuleList5 = [[200: 'Newer']]
        registerRmAppsList([
            [data: [id: 200, name: 'Newer', disabled: false], children: []]
        ])

        when:
        def result = script.toolListRmRules([:])

        then:
        result.rules.find { it.id == 200 }.type == null
        result.rules.every { it.status == 'active' }
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
    def "hub_list_rules via dispatch returns an isError validation result when Read master is disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableRead = false

        when:
        def response = mcpDriver.callTool('hub_list_rules', [:])

        then:
        response.error == null
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('Read tools are disabled')

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

    @spock.lang.Unroll
    def "#label: quiet path with count 0 and an informational note, success NOT false (useGateways=#useGateways)"() {
        given: 'Rule Machine is not installed, so RMUtils cannot resolve'
        settingsMap.useGateways = useGateways
        rmUtils.throwOnGetRuleList5 = failure

        when:
        def response = mcpDriver.callTool('hub_list_rules', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.count == 0
        inner.note == 'Rule Machine not detected on this hub.'
        inner.success != false
        inner.warning == null

        where:
        label                                    | failure                                                              | useGateways
        'NoClassDefFoundError'                   | new NoClassDefFoundError("hubitat.helper.RMUtils")                   | true
        'NoClassDefFoundError'                   | new NoClassDefFoundError("hubitat.helper.RMUtils")                   | false
        // The real sandbox resolves an unknown `hubitat.helper.X` lookup to null, and the
        // dereference throws this shape (live probe during PR #79 review).
        "sandbox 'Cannot get property helper'"   | new RuntimeException("Cannot get property 'helper' on null object")  | true
        "sandbox 'Cannot get property helper'"   | new RuntimeException("Cannot get property 'helper' on null object")  | false
    }

    @spock.lang.Unroll
    def "#label surfaces as success:false with the RMUtils error (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        rmUtils.throwOnGetRuleList5 = failure

        when:
        def response = mcpDriver.callTool('hub_list_rules', [:])

        then:
        response.error == null
        response.result.isError == true
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error.startsWith('RMUtils.getRuleList failed: ')
        inner.error.contains(fragment)
        inner.note.contains('Retry')

        where:
        label                                     | failure                                                              | fragment        | useGateways
        'a timeout'                               | new RuntimeException("timeout connecting to RM")                     | 'timeout'       | true
        'a timeout'                               | new RuntimeException("timeout connecting to RM")                     | 'timeout'       | false
        'Cannot-get-property without helper'      | new RuntimeException("Cannot get property 'label' on null object")   | "'label'"       | true
        'a MissingMethodException'                | new RuntimeException("No signature of method getRuleList() is applicable") | 'getRuleList' | false
    }

    def "only the current Rule Machine list is read"() {
        given:
        rmUtils.stubRuleList5 = [[500: 'My Rule']]

        when:
        def result = script.toolListRmRules([:])

        then:
        result.rules*.id == [500]
        rmUtils.calls.findAll { it.method == 'getRuleList' }*.version == ['5.0']
        !result.rules[0].containsKey('rmVersion')
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

    // ==================== status enrichment (issue #359) ====================

    /** Register /hub2/appsList with the RM parent (id 21) and the given rule children. */
    private void registerRmAppsList(List children) {
        hubGet.register('/hub2/appsList') { params ->
            groovy.json.JsonOutput.toJson([apps: [
                [data: [id: 21, name: 'Rule Machine', type: 'Rule Machine', user: false, hidden: false],
                 children: children]
            ]])
        }
    }

    private static Map ruleNode(int id, String name, boolean disabled = false, String type = 'Rule-5.1') {
        [data: [id: id, name: name, type: type, user: true, hidden: false, disabled: disabled], children: []]
    }

    def "flags a paused rule from the appsList (Paused) decoration the RMUtils label lacks (plain + HTML-span, any rule type)"() {
        given: 'clean RMUtils labels; appsList names carry the (Paused) suffix in plain and red-span form'
        rmUtils.stubRuleList5 = [[401: 'Play room toggle'], [402: 'Hall motion'], [403: 'Garden lights']]
        registerRmAppsList([
            ruleNode(401, 'Play room toggle (Paused)'),
            ruleNode(402, 'Hall motion <span style="color:red">(Paused)</span>'),
            ruleNode(403, 'Garden lights (Paused)', false, 'Button Rule-5.1')
        ])
        [401, 402, 403].each { registerRuleState(it, [paused: true]) }

        when:
        def result = script.toolListRmRules([:])

        then:
        def r401 = result.rules.find { it.id == 401 }
        def r402 = result.rules.find { it.id == 402 }
        def r403 = result.rules.find { it.id == 403 }
        r401.paused == true
        r401.disabled == false
        r401.status == 'paused'
        r402.paused == true
        r402.status == 'paused'

        and: 'a Button Rule is annotated the same way, by the same generic path'
        r403.type == 'Button Rule-5.1'
        r403.paused == true
        r403.status == 'paused'
    }

    def "disabled wins the status summary but the disabled/paused booleans stay independent"() {
        given: 'rule 411 was paused first (keeps its "(Paused)" decoration) and disabled afterward'
        rmUtils.stubRuleList5 = [[410: 'Kitchen night'], [411: 'Porch timer']]
        registerRmAppsList([
            ruleNode(410, 'Kitchen night', true),
            ruleNode(411, 'Porch timer (Paused)', true)
        ])

        when:
        def result = script.toolListRmRules([:])

        then:
        def r410 = result.rules.find { it.id == 410 }
        def r411 = result.rules.find { it.id == 411 }
        r410.disabled == true
        r410.paused == false
        r410.status == 'disabled'

        and: 'precedence only summarizes status; the booleans report independent facts, so a paused-then-disabled rule reads BOTH true'
        r411.disabled == true
        r411.paused == true
        r411.status == 'disabled'
    }

    def "flags requiredExpressionFalse from its decoration and omits the field otherwise"() {
        given:
        rmUtils.stubRuleList5 = [[420: 'Away lights'], [421: 'Normal rule']]
        registerRmAppsList([
            ruleNode(420, 'Away lights (Required Expression false)'),
            ruleNode(421, 'Normal rule')
        ])

        when:
        def result = script.toolListRmRules([:])

        then: 'the decorated rule is still active (enabled, not paused) but flags requiredExpressionFalse'
        def r420 = result.rules.find { it.id == 420 }
        def r421 = result.rules.find { it.id == 421 }
        r420.requiredExpressionFalse == true
        r420.status == 'active'
        r420.paused == false

        and: 'the un-decorated rule omits the field entirely'
        !r421.containsKey('requiredExpressionFalse')
    }

    def "does not false-flag an entity-escaped RMUtils label against the decoded appsList name"() {
        given: 'RMUtils label is HTML-entity-escaped ("&lt;67"); appsList name is the decoded equivalent, no decoration'
        rmUtils.stubRuleList5 = [[430: 'Heat On &lt;67']]
        registerRmAppsList([ruleNode(430, 'Heat On <67')])

        when:
        def result = script.toolListRmRules([:])

        then:
        def r430 = result.rules.find { it.id == 430 }
        r430.paused == false
        r430.status == 'active'
        !r430.containsKey('requiredExpressionFalse')

        and: 'the label is listed as the user typed it, not HTML-escaped'
        r430.label == 'Heat On <67'
    }

    def "does not false-flag a trailing-whitespace RMUtils label"() {
        given: 'RMUtils label carries a trailing space the trimmed appsList name lacks'
        rmUtils.stubRuleList5 = [[440: 'Door close switch ']]
        registerRmAppsList([ruleNode(440, 'Door close switch')])

        when:
        def result = script.toolListRmRules([:])

        then:
        def r440 = result.rules.find { it.id == 440 }
        r440.paused == false
        r440.status == 'active'
    }

    def "does not false-flag a rule the user literally named ending in (Paused)"() {
        given: 'both the RMUtils label and the appsList name carry the literal suffix -- stripped, they are equal'
        rmUtils.stubRuleList5 = [[450: 'Movie mode (Paused)']]
        registerRmAppsList([ruleNode(450, 'Movie mode (Paused)')])

        when:
        def result = script.toolListRmRules([:])

        then: 'equal stripped strings mean NO decoration -- the rule is active despite the literal name'
        def r450 = result.rules.find { it.id == 450 }
        r450.paused == false
        r450.status == 'active'
    }

    def "flags a genuinely stopped rule from the RMUtils-label channel"() {
        given: 'the "(Stopped)" suffix sits on the RMUtils LABEL while the appsList name stays clean'
        rmUtils.stubRuleList5 = [[470: 'Porch timer (Stopped)']]
        registerRmAppsList([ruleNode(470, 'Porch timer')])

        when:
        def result = script.toolListRmRules([:])

        then: 'channel A fires and the decoration is stripped from the reported name'
        def r470 = result.rules.find { it.id == 470 }
        r470.status == 'stopped'
        r470.label == 'Porch timer'
    }

    def "hub_list_rules over DISPATCH serializes the stopped status and the stripped name"() {
        given: 'the same channel-A shape, driven through the wire path a client actually uses'
        settingsMap.enableRead = true
        rmUtils.stubRuleList5 = [[470: 'Porch timer (Stopped)']]
        registerRmAppsList([ruleNode(470, 'Porch timer')])

        when:
        def response = mcpDriver.callTool('hub_list_rules', [:])
        def inner = mcpDriver.parseInner(response)

        then: 'status survives serialization, and BOTH name fields lose the decoration'
        // Stripping only `label` would leave `name` carrying "(Stopped)", so a caller
        // resolving a rule by name could no longer find it.
        response.error == null
        def r470 = inner.rules.find { it.id == 470 }
        r470.status == 'stopped'
        r470.label == 'Porch timer'
        r470.name == 'Porch timer'
    }

    private void registerRuleState(int id, Map flags) {
        hubGet.register("/installedapp/statusJson/${id}".toString()) { params ->
            groovy.json.JsonOutput.toJson([installedApp: [id: id], appSettings: [],
                appState: flags.collect { k, v -> [name: k, value: v, type: 'Boolean'] }])
        }
    }

    def "a rule both paused and stopped reads both flags from its state, though its name shows only (Stopped)"() {
        given:
        rmUtils.stubRuleList5 = [[480: 'Garage loop']]
        registerRmAppsList([ruleNode(480, 'Garage loop <span style="color:red">(Stopped)</span>')])
        registerRuleState(480, [paused: true, stopped: true])

        when:
        def r480 = script.toolListRmRules([:]).rules.find { it.id == 480 }

        then:
        r480.paused == true
        r480.status == 'stopped'
        r480.label == 'Garage loop'
    }

    def "a decorated row whose state is no longer stopped reads active, with the stale decoration still stripped"() {
        given:
        rmUtils.stubRuleList5 = [[481: 'Garage loop']]
        registerRmAppsList([ruleNode(481, 'Garage loop (Stopped)')])
        registerRuleState(481, [paused: false])

        when:
        def r481 = script.toolListRmRules([:]).rules.find { it.id == 481 }

        then:
        r481.paused == false
        r481.status == 'active'
    }

    @spock.lang.Unroll
    def "a (Stopped) row whose state #what reads #expected"() {
        given:
        rmUtils.stubRuleList5 = [[483: 'Garage loop']]
        registerRmAppsList([ruleNode(483, 'Garage loop (Stopped)')])
        hubGet.register('/installedapp/statusJson/483') { params ->
            groovy.json.JsonOutput.toJson([installedApp: [id: 483], appSettings: []] + (appState == 'absent' ? [:] : [appState: appState]))
        }

        when:
        def result = script.toolListRmRules([:])
        def r483 = result.rules.find { it.id == 483 }

        then:
        r483.status == expected
        r483.statusSource == source
        (result.statusSourceNote != null) == (source != null)

        where:
        what                                  | appState                                    | expected  | source
        'has no stopped entry'                | [[name: 'paused', value: false]]            | 'active'  | null
        'has no appState section (a gone app answers {})' | 'absent'                        | 'stopped' | 'name'
        'arrives as a map'                    | [stopped: false]                            | 'active'  | null
        'has a malformed stopped entry'       | [[name: 'stopped', value: 'maybe']]         | 'stopped' | 'name'
        'is in an unexpected shape'           | 'garbage'                                   | 'stopped' | 'name'
    }

    def "a failed state read keeps the name-derived status for that row and says so"() {
        given:
        rmUtils.stubRuleList5 = [[482: 'Garage loop'], [486: 'Porch']]
        registerRmAppsList([ruleNode(482, 'Garage loop (Paused)'), ruleNode(486, 'Porch (Paused)')])
        hubGet.register('/installedapp/statusJson/482') { params -> throw new RuntimeException('hub busy') }
        registerRuleState(486, [paused: true])

        when:
        def result = script.toolListRmRules([:])
        def r482 = result.rules.find { it.id == 482 }
        def r486 = result.rules.find { it.id == 486 }

        then: 'the failed row is marked and listed; the row whose state was read is not'
        r482.paused == true
        r482.status == 'paused'
        r482.statusSource == 'name'
        !r486.containsKey('statusSource')
        result.statusSourceNote.contains('rule(s) 482 could not be read')
        !result.statusSourceNote.contains('486')
    }

    def "a red-X disabled, decorated row whose state read succeeds keeps disabled first and its state's flags"() {
        given:
        rmUtils.stubRuleList5 = [[487: 'Den lights']]
        registerRmAppsList([ruleNode(487, 'Den lights <span style="color:red">(Paused)</span>', true)])
        registerRuleState(487, [paused: true, stopped: false])

        when:
        def result = script.toolListRmRules([:])
        def r487 = result.rules.find { it.id == 487 }

        then:
        r487.disabled == true
        r487.paused == true
        r487.status == 'disabled'
        !r487.containsKey('statusSource')
        result.statusSourceNote == null
    }

    def "a (Paused) row whose state is also stopped reads stopped, with paused kept"() {
        given: 'the name shows one decoration; the state carries both flags'
        rmUtils.stubRuleList5 = [[488: 'Hall loop']]
        registerRmAppsList([ruleNode(488, 'Hall loop (Paused)')])
        registerRuleState(488, [paused: true, stopped: true])

        when:
        def r488 = script.toolListRmRules([:]).rules.find { it.id == 488 }

        then:
        r488.paused == true
        r488.status == 'stopped'
        r488.label == 'Hall loop'
    }

    @spock.lang.Unroll
    def "hub_list_rules via dispatch reads state only for decorated rows (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        rmUtils.stubRuleList5 = [[483: 'Hall'], [484: 'Porch'], [485: 'Kitchen']]
        registerRmAppsList([ruleNode(483, 'Hall'), ruleNode(484, 'Porch (Stopped)'), ruleNode(485, 'Kitchen')])
        registerRuleState(484, [paused: true, stopped: true])
        hubGet.calls.clear()

        when:
        def response = mcpDriver.callTool('hub_list_rules', [:])
        def inner = mcpDriver.parseInner(response)

        then:
        !response.result.isError
        hubGet.calls*.path == ['/hub2/appsList', '/installedapp/statusJson/484']
        inner.rules.find { it.id == 484 }.paused == true
        inner.rules.find { it.id == 484 }.status == 'stopped'
        inner.rules.findAll { it.id != 484 }.every { it.status == 'active' && it.paused == false }

        where:
        useGateways << [true, false]
    }

    def "state reads run only for decorated rules on the returned page"() {
        given: 'decorated rules 10 and 55 land on different pages; 30 is filtered out by label'
        def label = { int id -> id == 30 ? "Other ${id}".toString() : "Rule ${id.toString().padLeft(3, '0')}".toString() }
        rmUtils.stubRuleList5 = (1..60).collect { id -> [(id): label(id)] }
        registerRmAppsList((1..60).collect { id -> ruleNode(id, id in [10, 30, 55] ? "${label(id)} (Paused)".toString() : label(id)) })
        [10, 30, 55].each { registerRuleState(it, [paused: false]) }
        def statusReads = { hubGet.calls*.path.findAll { it.startsWith('/installedapp/statusJson/') } }

        when:
        hubGet.calls.clear()
        def first = script.toolListRmRules([labelFilter: 'rule', cursor: ''])
        def firstReads = statusReads()
        hubGet.calls.clear()
        def second = script.toolListRmRules([labelFilter: 'rule', cursor: first.nextCursor])
        def secondReads = statusReads()
        hubGet.calls.clear()
        def unpaged = script.toolListRmRules([:])
        def unpagedReads = statusReads()

        then: 'the filtered-out and off-page decorated rules are never read'
        first.count == 50
        first.total == 59
        firstReads == ['/installedapp/statusJson/10']
        second.count == 9
        secondReads == ['/installedapp/statusJson/55']
        unpagedReads.sort() == ['/installedapp/statusJson/10', '/installedapp/statusJson/30', '/installedapp/statusJson/55']

        and: 'each read settles its row: the state says not paused'
        first.rules.find { it.id == 10 }.status == 'active'
        first.rules.find { it.id == 10 }.paused == false
        second.rules.find { it.id == 55 }.status == 'active'
        unpaged.rules.every { it.status == 'active' && it.paused == false }
    }

    def "does not false-flag a rule the user literally named ending in (Stopped)"() {
        given: 'both strings carry the literal suffix, so nothing distinguishes it as decoration'
        rmUtils.stubRuleList5 = [[471: 'Movie mode (Stopped)']]
        registerRmAppsList([ruleNode(471, 'Movie mode (Stopped)')])

        when:
        def result = script.toolListRmRules([:])

        then:
        def r471 = result.rules.find { it.id == 471 }
        r471.status == 'active'
        r471.label == 'Movie mode (Stopped)'
    }

    def "does not false-flag a literally-named (Stopped) rule that is ALSO paused"() {
        // The case the old channel-A test got wrong. It asked only whether the appsList name
        // ENDS with " (Stopped)"; here it ends with "(Paused)", so the suffix in the rule's
        // REAL name read as a runtime decoration. The rule was reported stopped and had
        // "(Stopped)" stripped from both label and name, so a name-to-id lookup lost it.
        given:
        rmUtils.stubRuleList5 = [[472: 'Movie mode (Stopped)']]
        registerRmAppsList([ruleNode(472, 'Movie mode (Stopped) (Paused)')])

        when:
        def result = script.toolListRmRules([:])

        then: 'the appsList name still STARTS WITH the whole RMUtils label -- so the suffix is name, not decoration'
        def r472 = result.rules.find { it.id == 472 }
        r472.paused == true
        r472.status == 'paused'

        and: 'and the real name survives intact for a name lookup'
        r472.label == 'Movie mode (Stopped)'
    }

    def "returns status unknown and a statusNote when /hub2/appsList is unreadable"() {
        given: '/hub2/appsList deliberately left unstubbed so _collectLiveApps returns null'
        rmUtils.stubRuleList5 = [[460: 'Some rule'], [461: 'Another rule']]

        when:
        def result = script.toolListRmRules([:])

        then: 'rules are returned unfiltered, each status unknown with no status booleans'
        result.rules.size() == 2
        result.rules.every { it.status == 'unknown' }
        result.rules.every { !it.containsKey('disabled') }
        result.rules.every { !it.containsKey('paused') }

        and: 'a single result-level statusNote explains the unreadable app list'
        result.statusNote?.contains('unreadable')
    }

    def "a rule whose appsList node has a null name is per-entry unknown while other rules stay determinate"() {
        given:
        rmUtils.stubRuleList5 = [[480: 'Good rule'], [481: 'Nameless node']]
        registerRmAppsList([
            ruleNode(480, 'Good rule'),
            // node present but its name is null -> paused undeterminable for THIS rule only
            [data: [id: 481, name: null, type: 'Rule Machine', disabled: false], children: []]
        ])

        when:
        def result = script.toolListRmRules([:])

        then: 'the nameless rule is individually unknown; the other keeps a real status'
        def good = result.rules.find { it.id == 480 }
        def bad = result.rules.find { it.id == 481 }
        good.status == 'active'
        good.paused == false
        bad.status == 'unknown'
        !bad.containsKey('disabled')
        !bad.containsKey('paused')

        and: 'the per-list statusNote is NOT set -- the tree read fine, only one entry was thin'
        result.statusNote == null
    }

    def "a rule whose appsList node lacks the disabled key is per-entry unknown"() {
        given:
        rmUtils.stubRuleList5 = [[490: 'No disabled key']]
        // node with NO disabled key at all -> disabled undeterminable
        registerRmAppsList([[data: [id: 490, name: 'No disabled key', type: 'Rule Machine'], children: []]])

        when:
        def result = script.toolListRmRules([:])

        then:
        def r = result.rules.find { it.id == 490 }
        r.status == 'unknown'
        !r.containsKey('disabled')
        !r.containsKey('paused')
    }

    def "a rule whose RMUtils entry has a null label (raw-id fallback) is per-entry unknown"() {
        given: 'a bare integer rule id (registerRmRule raw-id fallback) leaves label null -- nothing to diff against'
        rmUtils.stubRuleList5 = [500]
        registerRmAppsList([ruleNode(500, 'Some rule')])

        when:
        def result = script.toolListRmRules([:])

        then:
        def r = result.rules.find { it.id == 500 }
        r != null
        r.label == null
        r.status == 'unknown'
        !r.containsKey('disabled')
        !r.containsKey('paused')
    }

    @spock.lang.Unroll
    def "hub_list_rules via dispatch preserves status/disabled/paused through JSON (disabled precedence) (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        rmUtils.stubRuleList5 = [[410: 'Kitchen night'], [411: 'Porch timer']]
        registerRmAppsList([
            ruleNode(410, 'Kitchen night', true),
            ruleNode(411, 'Porch timer (Paused)', true)
        ])

        when:
        def response = mcpDriver.callTool('hub_list_rules', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        def r410 = inner.rules.find { it.id == 410 }
        def r411 = inner.rules.find { it.id == 411 }
        r410.status == 'disabled'
        r410.disabled == true
        r410.paused == false
        r411.status == 'disabled'
        r411.disabled == true

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_list_rules via dispatch reports per-rule status unknown + statusNote when the tree is unreadable (useGateways=#useGateways)"() {
        given: '/hub2/appsList left unstubbed so the whole tree is unreadable'
        settingsMap.useGateways = useGateways
        rmUtils.stubRuleList5 = [[460: 'Some rule'], [461: 'Another rule']]

        when:
        def response = mcpDriver.callTool('hub_list_rules', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.rules.size() == 2
        inner.rules.every { it.status == 'unknown' }
        inner.rules.every { !it.containsKey('disabled') && !it.containsKey('paused') }
        inner.statusNote?.contains('unreadable')

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

    def "labelFilter narrows to rules whose label contains the text, case-insensitively"() {
        given:
        rmUtils.stubRuleList5 = [
            ['413': 'Living Room Relax'],
            [832: 'Auto - Living Room TV Lights'],
            [900: 'Porch Light']
        ]

        when:
        def result = script.toolListRmRules([labelFilter: 'living ROOM'])

        then:
        result.rules*.id.sort() == [413, 832]
        result.count == 2
    }

    def "RM 5.x entries take their type from the hub's app list"() {
        given:
        rmUtils.stubRuleList5 = [['413': 'Living Room Relax']]
        registerRmAppsList([ruleNode(413, 'Living Room Relax')])

        when:
        def result = script.toolListRmRules([:])

        then:
        result.rules[0].type == 'Rule-5.1'
    }
}
