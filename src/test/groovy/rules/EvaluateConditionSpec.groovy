package rules

import support.TestDevice

/**
 * Spec for hubitat-mcp-rule.groovy::evaluateCondition (single condition).
 *
 * Covers the two condition types that flow only through the harness's
 * AppExecutor + parent mocks: `device_state` (device attribute compare
 * via parent.findDevice) and `variable` (local atomicState + global
 * parent.getVariableValue fallback). Other condition types
 * (`mode`, `time_range`, `sun_position`, etc.) lean on `location` /
 * `hsmStatus` / `timeOfDayIsBetween`, which need separate
 * location-mock wiring and are deferred to #75's broader coverage.
 */
class EvaluateConditionSpec extends RuleHarnessSpec {

    def "device_state returns true when the device attribute matches the target"() {
        given:
        def device = new TestDevice(id: 10, label: 'Switch')
        device.attributeValues['switch'] = 'on'
        parent = new ConditionParent(devices: [10L: device])

        when:
        def result = script.evaluateCondition([
            type: 'device_state',
            deviceId: 10L,
            attribute: 'switch',
            operator: 'equals',
            value: 'on'
        ])

        then:
        result == true
    }

    def "device_state returns false when the attribute differs"() {
        given:
        def device = new TestDevice(id: 10, label: 'Switch')
        device.attributeValues['switch'] = 'off'
        parent = new ConditionParent(devices: [10L: device])

        expect:
        script.evaluateCondition([
            type: 'device_state', deviceId: 10L, attribute: 'switch',
            operator: 'equals', value: 'on'
        ]) == false
    }

    def "device_state returns false when the device cannot be found"() {
        given:
        parent = new ConditionParent(devices: [:])

        expect: 'missing devices fail closed — safer than throwing mid-evaluation'
        script.evaluateCondition([
            type: 'device_state', deviceId: 999L, attribute: 'switch',
            operator: 'equals', value: 'on'
        ]) == false
    }

    def "variable reads the local value first"() {
        given:
        atomicStateMap.localVariables = [threshold: 50]
        // parent is intentionally absent — the local value should short-circuit.
        parent = new ConditionParent(devices: [:])

        expect:
        script.evaluateCondition([
            type: 'variable', variableName: 'threshold',
            operator: '>', value: 10
        ]) == true
    }

    def "variable falls back to the parent's global value when no local is set"() {
        given:
        atomicStateMap.localVariables = [:]
        parent = new ConditionParent(devices: [:], globals: [mode: 'night'])

        expect:
        script.evaluateCondition([
            type: 'variable', variableName: 'mode',
            operator: 'equals', value: 'night'
        ]) == true
    }

    def "unknown condition type returns false (fail closed)"() {
        expect:
        script.evaluateCondition([type: 'definitely_not_a_real_type']) == false
    }

    /**
     * Minimal parent stub supporting findDevice(id) + getVariableValue(name).
     * Device lookups accept Long, Integer, or String ids uniformly by coercing
     * to Long on read.
     */
    static class ConditionParent {
        Map<Long, TestDevice> devices = [:]
        Map<String, Object> globals = [:]

        Object findDevice(id) {
            devices[(id as Long)]
        }

        Object getVariableValue(String name) {
            globals[name]
        }
    }
}
