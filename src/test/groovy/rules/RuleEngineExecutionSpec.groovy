package rules

import support.TestDevice

/**
 * Rule-engine action-dispatch surface — covers both executeAction (single
 * action via parent.findDevice) and executeActions (multi-action queue
 * read from atomicState.actions, with stop-action short-circuit).
 *
 * Consolidated from RuleEngineSmokeSpec + ExecuteActionsSpec (#183);
 * same RuleHarnessSpec base + TestDevice fixture, so one sandbox compile
 * covers all four features instead of two.
 *
 * Source: hubitat-mcp-rule.groovy
 *   executeAction line 3311 (dispatches a single action via parent.findDevice)
 *   executeActions       (iterates atomicState.actions; returns early when
 *                         executeAction returns false (`stop`) or the
 *                         string "delayed")
 *
 * Broader rule-engine coverage (time triggers, boolean composition,
 * nested rules, variable scoping, error paths) is tracked in issue #75.
 */
class RuleEngineExecutionSpec extends RuleHarnessSpec {

    // ---- executeAction single-action dispatch -------------------------------

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

    // ---- executeActions multi-action queue ----------------------------------

    def "executeActions empty actions list is a no-op"() {
        given:
        atomicStateMap.actions = []

        when:
        script.executeActions()

        then:
        noExceptionThrown()
    }

    def "executeActions executes multiple device_command actions in declared order"() {
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

    def "executeActions stop action breaks the loop — subsequent actions do not run"() {
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

    /** Minimal parent stub — findDevice(id) returns the device iff id matches. */
    static class SmokeParent {
        TestDevice device
        Integer matchId

        Object findDevice(id) {
            (id as Integer) == matchId ? device : null
        }
    }

    /** Minimal parent — findDevice by numeric id, coerced to Long. */
    static class OrderingParent {
        Map<Long, TestDevice> devices = [:]

        Object findDevice(id) {
            devices[(id as Long)]
        }
    }
}
