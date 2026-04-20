package server

import support.TestChildApp
import support.ToolSpecBase

/**
 * Spec for toolUpdateRule (hubitat-mcp-server.groovy line 2263).
 *
 * Covers: golden-path update via a TestChildApp Spy + the "Rule not found"
 * error path.
 */
class ToolUpdateRuleSpec extends ToolSpecBase {

    def "updates rule via child app and returns success"() {
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

    def "throws when rule is not found"() {
        given:
        childAppsList.clear()

        when:
        script.toolUpdateRule('999', [name: 'x'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message == 'Rule not found: 999'
    }
}
