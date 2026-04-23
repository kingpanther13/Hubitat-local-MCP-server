package rules

import support.TestDevice

/**
 * Error-path coverage for the rule engine (#75). The engine is designed to
 * fail closed and keep going — a malformed action or condition should not
 * tear the whole rule down. This spec pins that behaviour for the cases
 * primitive specs don't cover.
 *
 * Complements: {@link EvaluateComparisonSpec} (null/type-coercion branches)
 * and {@link EvaluateConditionsSpec} (caught-exception → condition-miss).
 */
class ErrorPathsSpec extends RuleHarnessSpec {

    def "action targeting a missing device logs a warning but does not stop execution"() {
        given:
        def present = Spy(TestDevice) { getId() >> 2 }
        parent = new ErrorParent(devices: [2L: present])

        and: 'first action targets a non-existent device; second hits a real one'
        atomicStateMap.actions = [
            [type: 'device_command', deviceId: 999L, command: 'on'],
            [type: 'device_command', deviceId: 2L, command: 'off']
        ]

        when:
        script.executeActions()

        then: 'the missing-device branch warns + skips; the next action still fires'
        1 * present.off()
    }

    def "a thrown exception inside a device command is caught and does not halt the loop"() {
        given:
        def flaky = Spy(TestDevice) {
            getId() >> 1
            on() >> { throw new RuntimeException("simulated device failure") }
        }
        def healthy = Spy(TestDevice) { getId() >> 2 }
        parent = new ErrorParent(devices: [1L: flaky, 2L: healthy])

        atomicStateMap.actions = [
            [type: 'device_command', deviceId: 1L, command: 'on'],
            [type: 'device_command', deviceId: 2L, command: 'on']
        ]

        when:
        script.executeActions()

        then: 'executeAction catches the throw; subsequent action runs'
        1 * healthy.on()
    }

    def "set_thermostat catches per-setter failures without skipping remaining setters"() {
        given: 'setHeatingSetpoint throws, but setThermostatMode is still called first'
        def tstat = Spy(TestDevice) {
            getId() >> 5
            setHeatingSetpoint(_) >> { throw new RuntimeException("probe fail") }
        }
        parent = new ErrorParent(devices: [5L: tstat])

        when:
        script.executeAction([
            type: 'set_thermostat', deviceId: 5L,
            thermostatMode: 'cool', heatingSetpoint: 65, coolingSetpoint: 72
        ])

        then: 'mode was set before the throw; the outer catch swallows the exception'
        1 * tstat.setThermostatMode('cool')
        noExceptionThrown()
    }

    def "missing actions list in atomicState is treated as an empty list"() {
        given: 'atomicState.actions is absent (not set on the map)'
        assert !atomicStateMap.containsKey('actions')

        when:
        script.executeActions()

        then: 'the `?: []` default short-circuits the loop body cleanly'
        noExceptionThrown()
    }

    def "unknown condition type returns false (fail closed)"() {
        expect:
        script.evaluateCondition([type: 'absolutely_not_a_real_type']) == false
    }

    def "unknown action type logs a warn and returns true (continue)"() {
        when:
        def result = script.executeAction([type: 'absolutely_not_a_real_action'])

        then: 'executeAction returns true so the outer loop continues'
        result == true
    }

    def "set_mode missing the 'mode' field returns false (stops the chain)"() {
        given:
        atomicStateMap.actions = [
            [type: 'set_mode'],  // missing mode field → returns false → break
            [type: 'device_command', deviceId: 1L, command: 'on']
        ]
        def target = Spy(TestDevice) { getId() >> 1 }
        parent = new ErrorParent(devices: [1L: target])

        when:
        script.executeActions()

        then: 'the second action is NOT reached because set_mode returned false'
        0 * target.on()
    }

    def "set_hsm missing the 'status' field returns false (stops the chain)"() {
        given:
        atomicStateMap.actions = [
            [type: 'set_hsm'],
            [type: 'device_command', deviceId: 1L, command: 'on']
        ]
        def target = Spy(TestDevice) { getId() >> 1 }
        parent = new ErrorParent(devices: [1L: target])

        when:
        script.executeActions()

        then:
        0 * target.on()
    }

    static class ErrorParent {
        Map<Long, TestDevice> devices = [:]
        Object findDevice(id) { devices[(id as Long)] }
    }
}
