package server

import support.TestChildApp
import support.ToolSpecBase

/**
 * Lifecycle tools for the legacy custom (MCP-managed) rule engine —
 * toolCreateRule and toolUpdateRule. Both route through the parent app's
 * addChildApp / childApps fixture and exercise TestChildApp Spy
 * interactions; consolidated from ToolCreateRuleSpec + ToolUpdateRuleSpec
 * so the spec class pays one sandbox compile for both surfaces.
 *
 * Note on the create golden path: it uses trigger type "time" and action
 * type "delay" because both validate without a device lookup — seeding
 * real devices into childDevicesList would couple the test to findDevice
 * machinery without adding value here.
 */
class ToolCustomRuleLifecycleSpec extends ToolSpecBase {

    // ---- schema: type enums (issue #258) ------------------------------------

    def "hub_create_custom_rule schema enums stay in lockstep with the create-path validators"() {
        // Issue #258 moved the trigger/condition/action type lists OUT of the description
        // prose and INTO items.properties.type.enum (the structural home per AGENTS.md "use
        // enum for a fixed set"). The create path (toolCreateRule/toolUpdateRule in this lib)
        // gates every type through validateTrigger/validateCondition/validateAction in
        // hubitat-mcp-server.groovy. This guard reads those validators' ACTUAL `case` labels
        // from source and asserts each enum equals the accepted set -- so a strict client can
        // never reject a type the create path accepts (the exact regression caught in review,
        // where the enum was derived from runtime dispatch and missed validator-only cases),
        // and a newly-added validator case can't silently drift from the enum.
        // Exceptions: 'expression' is validate-REJECTED (Eval.me banned in the sandbox) so it
        // is excluded from conditions; sunrise/sunset/sun are normalizeTrigger INPUT aliases
        // (normalized to type:'time' before validateTrigger) added on top of the trigger cases;
        // minutes/hours/days are the nested periodic-UNIT switch inside validateTrigger, not
        // trigger types.
        given:
        settingsMap.enableCustomRuleEngine = true
        def src = new File('hubitat-mcp-server.groovy').text
        def caseLabels = { String fnSig ->
            int start = src.indexOf(fnSig)
            assert start >= 0 : "could not find ${fnSig} in hubitat-mcp-server.groovy -- validator moved/renamed?"
            int end = src.indexOf('\ndef ', start + fnSig.length())
            def body = src.substring(start, end < 0 ? src.length() : end)
            // /[a-z_]+/ matches the lowercase_underscore type labels the validators use. A future
            // label with a digit/uppercase (e.g. "set_hvac2") would be missed here -- but then the
            // guard fails in the SAFE direction (the enum carries it, the parsed set doesn't =>
            // inequality => RED), so it degrades to a loud failure, never a silent miss.
            (body =~ /case\s+"([a-z_]+)"/).collect { it[1] } as Set
        }
        def triggerCases = caseLabels('def validateTrigger(trigger) {') - ['minutes', 'hours', 'days']
        def conditionCases = caseLabels('def validateCondition(condition) {') - ['expression']
        def actionCases = caseLabels('def validateAction(action) {')

        def def_ = script.getAllToolDefinitions().find { it.name == 'hub_create_custom_rule' }
        def triggerEnum = def_.inputSchema.properties.triggers.items.properties.type.enum as Set
        def conditionEnum = def_.inputSchema.properties.conditions.items.properties.type.enum as Set
        def actionEnum = def_.inputSchema.properties.actions.items.properties.type.enum as Set

        expect: 'the validators actually parsed (guard is not vacuous)'
        triggerCases.contains('device_event') && conditionCases.contains('device_state') && actionCases.contains('device_command')

        and: 'trigger enum = validateTrigger cases + the normalizeTrigger sun aliases'
        triggerEnum == (triggerCases + ['sunrise', 'sunset', 'sun'])

        and: 'condition enum = validateCondition cases (minus the rejected expression type)'
        conditionEnum == conditionCases

        and: 'action enum = validateAction cases exactly'
        actionEnum == actionCases
    }

    // ---- toolCreateRule -----------------------------------------------------

    def "toolCreateRule creates rule via addChildApp and returns the child app id"() {
        given: 'a TestChildApp Spy returned by addChildApp'
        def childApp = Spy(TestChildApp) {
            getId() >> 42
        }
        mockChildAppForCreate = childApp

        when:
        def result = script.toolCreateRule([
            name: 'Test Rule',
            description: 'smoke test',
            triggers: [[type: 'time', time: '08:30']],
            actions: [[type: 'delay', seconds: 5]]
        ])

        then: 'the child app was configured and received the rule data'
        1 * childApp.updateSetting('ruleName', 'Test Rule')
        1 * childApp.updateSetting('ruleDescription', 'smoke test')
        1 * childApp.updateRuleFromParent({
            it instanceof Map &&
            it.triggers?.size() == 1 &&
            it.triggers[0].type == 'time' &&
            it.actions?.size() == 1 &&
            it.actions[0].type == 'delay' &&
            it.enabled == true
        })

        and: 'the return shape reflects success and reports the new rule id'
        result.success == true
        result.ruleId == '42'
        result.message.contains('Test Rule')
        result.diagnostics.storedTriggers == 1
        result.diagnostics.storedActions == 1
    }

    def "toolCreateRule rejects missing rule name"() {
        when:
        script.toolCreateRule([
            triggers: [[type: 'time', time: '08:30']],
            actions: [[type: 'delay', seconds: 5]]
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule name is required')
    }

    def "toolCreateRule rejects empty triggers list"() {
        when:
        script.toolCreateRule([
            name: 'Test Rule',
            triggers: [],
            actions: [[type: 'delay', seconds: 5]]
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('At least one trigger is required')
    }

    def "toolCreateRule rejects empty actions list"() {
        when:
        script.toolCreateRule([
            name: 'Test Rule',
            triggers: [[type: 'time', time: '08:30']],
            actions: []
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('At least one action is required')
    }

    // ---- toolUpdateRule -----------------------------------------------------

    def "toolUpdateRule updates rule via child app and returns success"() {
        given:
        def mockChildApp = Spy(TestChildApp) {
            getId() >> 42
        }
        mockChildApp.settingsStore['ruleName'] = 'Updated Name'
        childAppsList << mockChildApp

        when:
        def result = script.toolUpdateRule('42', [name: 'Updated Name'])

        then: 'the child app was told to apply the update'
        1 * mockChildApp.updateSetting('ruleName', 'Updated Name')
        1 * mockChildApp.updateLabel('Updated Name')
        1 * mockChildApp.updateRuleFromParent({ it instanceof Map && it.name == 'Updated Name' })

        and:
        result.success == true
        result.ruleId == '42'
    }

    def "toolUpdateRule throws when rule is not found"() {
        given:
        childAppsList.clear()

        when:
        script.toolUpdateRule('999', [name: 'x'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message == 'Rule not found: 999'
    }

    // ---- Dispatch-envelope counterparts (#187, #121) -------------------------
    // Parallel coverage exercising callTool() so the JSON-RPC envelope, gateway
    // routing toggles, and error mapping (IAE -> -32602, generic -> isError) are
    // verified end-to-end alongside the direct-call golden paths above.

    @spock.lang.Unroll
    def "hub_create_custom_rule via dispatch creates rule (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableCustomRuleEngine = true
        def childApp = Spy(TestChildApp) {
            getId() >> 42
        }
        mockChildAppForCreate = childApp

        when:
        def response = mcpDriver.callTool('hub_create_custom_rule', [
            name: 'Test Rule',
            description: 'smoke test',
            triggers: [[type: 'time', time: '08:30']],
            actions: [[type: 'delay', seconds: 5]]
        ])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.ruleId == '42'

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_create_custom_rule via dispatch maps missing rule name to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableCustomRuleEngine = true

        when:
        def response = mcpDriver.callTool('hub_create_custom_rule', [
            triggers: [[type: 'time', time: '08:30']],
            actions: [[type: 'delay', seconds: 5]]
        ])

        then:
        response.error?.code == -32602
        response.error.message.contains('Rule name is required')

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_create_custom_rule via dispatch maps empty triggers to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableCustomRuleEngine = true

        when:
        def response = mcpDriver.callTool('hub_create_custom_rule', [
            name: 'Test Rule',
            triggers: [],
            actions: [[type: 'delay', seconds: 5]]
        ])

        then:
        response.error?.code == -32602
        response.error.message.contains('At least one trigger is required')

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_create_custom_rule via dispatch maps empty actions to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableCustomRuleEngine = true

        when:
        def response = mcpDriver.callTool('hub_create_custom_rule', [
            name: 'Test Rule',
            triggers: [[type: 'time', time: '08:30']],
            actions: []
        ])

        then:
        response.error?.code == -32602
        response.error.message.contains('At least one action is required')

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_update_custom_rule via dispatch updates rule (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableCustomRuleEngine = true
        def mockChildApp = Spy(TestChildApp) {
            getId() >> 42
        }
        mockChildApp.settingsStore['ruleName'] = 'Updated Name'
        childAppsList << mockChildApp

        when:
        def response = mcpDriver.callTool('hub_update_custom_rule', [
            ruleId: '42', name: 'Updated Name'
        ])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.ruleId == '42'

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_update_custom_rule via dispatch maps unknown ruleId to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableCustomRuleEngine = true
        childAppsList.clear()

        when:
        def response = mcpDriver.callTool('hub_update_custom_rule', [
            ruleId: '999', name: 'x'
        ])

        then:
        response.error?.code == -32602
        response.error.message.contains('Rule not found: 999')

        where:
        useGateways << [true, false]
    }

    // ---- hub_get_custom_rule list / single / detailed-guard dispatch ---------
    // ruleId omitted -> toolListRules (list summary). ruleId present (no detailed)
    // -> toolGetRule (single rule data). detailed=true without ruleId -> IAE
    // ("detailed=true requires a ruleId") mapped to -32602 by handleToolsCall.
    // hub_get_custom_rule isn't gated by the custom_* engine dispatch check (name
    // doesn't startWith "custom_"); enableCustomRuleEngine is set for parity with
    // the other custom-rule dispatch tests.

    @spock.lang.Unroll
    def "hub_get_custom_rule via dispatch lists rules when ruleId is omitted (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableCustomRuleEngine = true
        def rule = new TestChildApp(id: 42L, label: 'My Rule')
        rule.ruleData = [
            id: 42L, name: 'My Rule', description: 'desc', enabled: true,
            triggers: [[type: 'device'], [type: 'time']],
            conditions: [[op: '>']],
            actions: [[cmd: 'on']],
            executionCount: 5, lastTriggered: 3000L
        ]
        childAppsList << rule

        when:
        def response = mcpDriver.callTool('hub_get_custom_rule', [:])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.count == 1
        inner.rules[0].id == 42L
        inner.rules[0].name == 'My Rule'
        inner.rules[0].triggerCount == 2
        inner.rules[0].actionCount == 1
        inner.rules[0].source == 'mcp_custom_engine'

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_get_custom_rule via dispatch returns single rule data when ruleId is supplied (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableCustomRuleEngine = true
        def rule = new TestChildApp(id: 42L, label: 'My Rule')
        rule.ruleData = [
            id: 42L, name: 'My Rule', description: 'desc', enabled: true,
            triggers: [[type: 'device']],
            conditions: [],
            actions: [[cmd: 'on'], [cmd: 'off']]
        ]
        childAppsList << rule

        when:
        def response = mcpDriver.callTool('hub_get_custom_rule', [ruleId: '42'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.id == 42L
        inner.name == 'My Rule'
        inner.actions.size() == 2
        inner.source == 'mcp_custom_engine'

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_get_custom_rule via dispatch maps detailed=true without ruleId to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableCustomRuleEngine = true

        when: 'detailed=true requires a ruleId; the guard rejects rather than silently listing'
        def response = mcpDriver.callTool('hub_get_custom_rule', [detailed: true])

        then:
        response.error?.code == -32602
        response.error.message.contains('detailed=true requires a ruleId')

        where:
        useGateways << [true, false]
    }

    def "BUG-15: readonly mode does not block the hub_manage_custom_rules gateway (substring gate must not trip on the gateway name)"() {
        given: "readonly mode (Custom Rule Engine OFF + Read master ON), gateway mode"
        settingsMap.enableCustomRuleEngine = false
        settingsMap.enableRead = true
        settingsMap.useGateways = true

        when: "the GATEWAY is dispatched -- its name 'hub_manage_custom_rules' contains the substring '_custom_rule'"
        def gatewayErr = null
        try { script.executeTool('hub_manage_custom_rules', [tool: 'hub_get_custom_rule', args: [:]]) }
        catch (Exception e) { gatewayErr = e }

        then: "the gateway reaches handleGateway -- the _custom_rule gate must NOT fire on the gateway name (it ends with _custom_rules, plural)"
        gatewayErr?.message?.contains('not available in read-only mode') != true

        when: "a WRITE leaf is dispatched in the same readonly mode"
        def leafErr = null
        try { script.executeTool('hub_create_custom_rule', [name: 'x', triggers: [], actions: []]) }
        catch (Exception e) { leafErr = e }

        then: "the write leaf IS still gated (endsWith still matches the leaf tools)"
        leafErr instanceof IllegalArgumentException
        leafErr.message.contains('not available in read-only mode')
    }
}
