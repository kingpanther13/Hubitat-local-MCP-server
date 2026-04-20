package rules

/**
 * Smoke test: the rule engine's executeAction() dispatches a
 * 'device_command' action to the parent.findDevice() result by invoking
 * the device's named command. Proves the harness can load
 * hubitat-mcp-rule.groovy AND drive its action-execution layer end-to-end.
 *
 * Entry point: executeAction(action, actionIndex = null, evt = null)
 *   line 3311 of hubitat-mcp-rule.groovy
 *
 * Broader rule-engine coverage (time triggers, boolean composition,
 * nested rules, variable scoping, error paths) is tracked in issue #75.
 */
class RuleEngineSmokeSpec extends RuleHarnessSpec {

    def "executeAction dispatches a device_command action via parent.findDevice"() {
        given: 'a parent server that can resolve device 1 to a mock device'
        def device = Mock(Object) {
            _ * getId() >> 1
            _ * getLabel() >> 'TestSwitch'
            _ * getName() >> 'TestSwitch'
        }
        parent = Mock(Object)
        parent.findDevice(_) >> { args -> args[0] == 1 ? device : null }
        wireOverrides()  // re-bind parent after assignment

        and: 'a device_command action targeting device 1 with command "off"'
        def action = [type: 'device_command', deviceId: 1, command: 'off']

        when:
        script.executeAction(action)

        then: 'the mock device received the off() command exactly once'
        1 * device.off()
    }
}
