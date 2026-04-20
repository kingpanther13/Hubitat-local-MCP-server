package rules

import support.TestDevice

/**
 * Smoke test: the rule engine's executeAction() dispatches a
 * 'device_command' action to the parent.findDevice() result by invoking
 * the device's named command. Proves the harness can load
 * hubitat-mcp-rule.groovy AND drive its action-execution layer end-to-end.
 *
 * Entry point: executeAction(action, actionIndex = null, evt = null)
 *   hubitat-mcp-rule.groovy line 3311
 *
 * Broader rule-engine coverage (time triggers, boolean composition,
 * nested rules, variable scoping, error paths) is tracked in issue #75.
 */
class RuleEngineSmokeSpec extends RuleHarnessSpec {

    def "executeAction dispatches a device_command action via parent.findDevice"() {
        given: 'a device the parent can resolve by id=1'
        def device = Spy(TestDevice) {
            getId() >> 1
            getLabel() >> 'TestSwitch'
            getName() >> 'TestSwitch'
        }

        and: 'a parent server that returns the device for findDevice(1)'
        parent = new SmokeParent(device: device, matchId: 1)

        and: 'a device_command action targeting device 1 with command "off"'
        def action = [type: 'device_command', deviceId: 1, command: 'off']

        when:
        script.executeAction(action)

        then: 'the device received the off() command exactly once'
        1 * device.off()
    }

    /** Minimal parent stub — findDevice(id) returns the device iff id matches. */
    static class SmokeParent {
        TestDevice device
        Integer matchId

        Object findDevice(id) {
            (id as Integer) == matchId ? device : null
        }
    }
}
