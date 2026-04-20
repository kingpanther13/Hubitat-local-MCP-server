package server

import support.ToolSpecBase

/**
 * Spec for toolCreateRule (hubitat-mcp-server.groovy line 2163).
 *
 * The golden path requires a functioning addChildApp that returns a mock
 * MCP Rule child app plus the server-internal normalizeTrigger /
 * validateTrigger / validateAction chain accepting the test trigger shape.
 * Covers here: required-field validation (error paths) — the cheapest and
 * most regression-valuable surface. The golden path is deferred to a
 * follow-up spec once the mock-child-app fixture matures.
 */
class ToolCreateRuleSpec extends ToolSpecBase {

    def "rejects missing rule name"() {
        when:
        script.toolCreateRule([
            triggers: [[type: 'device', deviceId: 1, attribute: 'switch', value: 'on']],
            actions: [[type: 'command', deviceId: 1, command: 'off']]
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
            actions: [[type: 'command', deviceId: 1, command: 'off']]
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('At least one trigger is required')
    }

    def "rejects empty actions list"() {
        when:
        script.toolCreateRule([
            name: 'Test Rule',
            triggers: [[type: 'device', deviceId: 1, attribute: 'switch', value: 'on']],
            actions: []
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('At least one action is required')
    }
}
