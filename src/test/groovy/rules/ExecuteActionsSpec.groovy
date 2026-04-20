package rules

import support.TestDevice

/**
 * Spec for hubitat-mcp-rule.groovy::executeActions (plural).
 *
 * Reads atomicState.actions and dispatches to executeAction() in order.
 * Stops early if executeAction returns false (`stop` action) or the
 * string "delayed" (a scheduled delay). Execution order is observable
 * via device-command side effects on the parent-resolved devices.
 */
class ExecuteActionsSpec extends RuleHarnessSpec {

    def "empty actions list is a no-op"() {
        given:
        atomicStateMap.actions = []

        when:
        script.executeActions()

        then:
        noExceptionThrown()
    }

    def "executes multiple device_command actions in declared order"() {
        given: 'three devices resolvable by integer id'
        def dev1 = Spy(TestDevice) { getId() >> 1 }
        def dev2 = Spy(TestDevice) { getId() >> 2 }
        def dev3 = Spy(TestDevice) { getId() >> 3 }
        parent = new OrderingParent(devices: [1L: dev1, 2L: dev2, 3L: dev3])

        and: 'an action sequence that exercises each device in turn'
        atomicStateMap.actions = [
            [type: 'device_command', deviceId: 1, command: 'on'],
            [type: 'device_command', deviceId: 2, command: 'off'],
            [type: 'device_command', deviceId: 3, command: 'on']
        ]

        when:
        script.executeActions()

        then: 'device 1 gets on() first'
        1 * dev1.on()

        then: 'then device 2 gets off()'
        1 * dev2.off()

        then: 'then device 3 gets on()'
        1 * dev3.on()
    }

    def "a stop action breaks the loop — subsequent actions do not run"() {
        given:
        def dev1 = Spy(TestDevice) { getId() >> 1 }
        def dev2 = Spy(TestDevice) { getId() >> 2 }
        parent = new OrderingParent(devices: [1L: dev1, 2L: dev2])

        and:
        atomicStateMap.actions = [
            [type: 'device_command', deviceId: 1, command: 'on'],
            [type: 'stop'],
            [type: 'device_command', deviceId: 2, command: 'on']
        ]

        when:
        script.executeActions()

        then:
        1 * dev1.on()
        0 * dev2.on()
    }

    /** Minimal parent — findDevice by numeric id, coerced to Long. */
    static class OrderingParent {
        Map<Long, TestDevice> devices = [:]

        Object findDevice(id) {
            devices[(id as Long)]
        }
    }
}
