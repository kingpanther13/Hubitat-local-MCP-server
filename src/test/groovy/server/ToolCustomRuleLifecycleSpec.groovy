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
    def "custom_create_rule via dispatch creates rule (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableCustomRuleEngine = true
        def childApp = Spy(TestChildApp) {
            getId() >> 42
        }
        mockChildAppForCreate = childApp

        when:
        def response = mcpDriver.callTool('custom_create_rule', [
            name: 'Test Rule',
            description: 'smoke test',
            triggers: [[type: 'time', time: '08:30']],
            actions: [[type: 'delay', seconds: 5]]
        ])

        then:
        response.error == null
        !response.result.isError
        def inner = new groovy.json.JsonSlurper().parseText(response.result.content[0].text)
        inner.success == true
        inner.ruleId == '42'

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "custom_create_rule via dispatch maps missing rule name to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableCustomRuleEngine = true

        when:
        def response = mcpDriver.callTool('custom_create_rule', [
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
    def "custom_create_rule via dispatch maps empty triggers to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableCustomRuleEngine = true

        when:
        def response = mcpDriver.callTool('custom_create_rule', [
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
    def "custom_create_rule via dispatch maps empty actions to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableCustomRuleEngine = true

        when:
        def response = mcpDriver.callTool('custom_create_rule', [
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
    def "custom_update_rule via dispatch updates rule (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableCustomRuleEngine = true
        def mockChildApp = Spy(TestChildApp) {
            getId() >> 42
        }
        mockChildApp.settingsStore['ruleName'] = 'Updated Name'
        childAppsList << mockChildApp

        when:
        def response = mcpDriver.callTool('custom_update_rule', [
            ruleId: '42', name: 'Updated Name'
        ])

        then:
        response.error == null
        !response.result.isError
        def inner = new groovy.json.JsonSlurper().parseText(response.result.content[0].text)
        inner.success == true
        inner.ruleId == '42'

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "custom_update_rule via dispatch maps unknown ruleId to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableCustomRuleEngine = true
        childAppsList.clear()

        when:
        def response = mcpDriver.callTool('custom_update_rule', [
            ruleId: '999', name: 'x'
        ])

        then:
        response.error?.code == -32602
        response.error.message.contains('Rule not found: 999')

        where:
        useGateways << [true, false]
    }
}
