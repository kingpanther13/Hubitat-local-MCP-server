package server

import support.TestChildApp
import support.ToolSpecBase

/**
 * Spec for toolCreateRule (hubitat-mcp-server.groovy line 2163).
 *
 * Golden path uses trigger type "time" and action type "delay" — both
 * validate without needing a device lookup (validateTrigger / validateAction
 * require a findable device for device_event / device_command / toggle_device
 * etc., and seeding real devices into childDevicesList would coupling the
 * test to the wider findDevice machinery without adding value here).
 */
class ToolCreateRuleSpec extends ToolSpecBase {

    def "creates rule via addChildApp and returns the child app id"() {
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

    def "rejects missing rule name"() {
        when:
        script.toolCreateRule([
            triggers: [[type: 'time', time: '08:30']],
            actions: [[type: 'delay', seconds: 5]]
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule name is required')
    }

    def "rejects empty triggers list"() {
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

    def "rejects empty actions list"() {
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
}
