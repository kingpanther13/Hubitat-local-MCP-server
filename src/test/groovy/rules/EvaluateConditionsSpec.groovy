package rules

/**
 * Spec for hubitat-mcp-rule.groovy::evaluateConditions (plural).
 *
 * Aggregates the per-condition evaluator. Respects settings.conditionLogic
 * ("all" default → AND, anything else → OR) and short-circuits via Groovy
 * Collection.every / .any. Individual-condition exceptions are caught and
 * treated as not-met (fail closed).
 */
class EvaluateConditionsSpec extends RuleHarnessSpec {

    def "empty conditions list with all-logic returns true (vacuously)"() {
        given:
        atomicStateMap.conditions = []
        settingsMap.conditionLogic = 'all'

        expect:
        script.evaluateConditions() == true
    }

    def "empty conditions list with any-logic returns false"() {
        given:
        atomicStateMap.conditions = []
        settingsMap.conditionLogic = 'any'

        expect:
        script.evaluateConditions() == false
    }

    def "all-logic returns true only when every condition matches"() {
        given:
        atomicStateMap.localVariables = [a: 1, b: 2]
        atomicStateMap.conditions = [
            [type: 'variable', variableName: 'a', operator: 'equals', value: 1],
            [type: 'variable', variableName: 'b', operator: 'equals', value: 2]
        ]
        settingsMap.conditionLogic = 'all'

        expect:
        script.evaluateConditions() == true
    }

    def "all-logic returns false when any condition fails"() {
        given:
        atomicStateMap.localVariables = [a: 1, b: 2]
        atomicStateMap.conditions = [
            [type: 'variable', variableName: 'a', operator: 'equals', value: 1],
            [type: 'variable', variableName: 'b', operator: 'equals', value: 99]
        ]
        settingsMap.conditionLogic = 'all'

        expect:
        script.evaluateConditions() == false
    }

    def "any-logic returns true if at least one condition matches"() {
        given:
        atomicStateMap.localVariables = [a: 1, b: 2]
        atomicStateMap.conditions = [
            [type: 'variable', variableName: 'a', operator: 'equals', value: 99],  // miss
            [type: 'variable', variableName: 'b', operator: 'equals', value: 2]    // hit
        ]
        settingsMap.conditionLogic = 'any'

        expect:
        script.evaluateConditions() == true
    }

    def "any-logic returns false when all conditions miss"() {
        given:
        atomicStateMap.localVariables = [a: 1, b: 2]
        atomicStateMap.conditions = [
            [type: 'variable', variableName: 'a', operator: 'equals', value: 99],
            [type: 'variable', variableName: 'b', operator: 'equals', value: 99]
        ]
        settingsMap.conditionLogic = 'any'

        expect:
        script.evaluateConditions() == false
    }

    def "default logic is 'all' when settings.conditionLogic is unset"() {
        given:
        atomicStateMap.localVariables = [a: 1]
        atomicStateMap.conditions = [
            [type: 'variable', variableName: 'a', operator: 'equals', value: 1]
        ]
        // settingsMap.conditionLogic intentionally unset.

        expect:
        script.evaluateConditions() == true
    }

    def "an evaluator exception is treated as a condition miss (fail closed)"() {
        given: 'a parent that throws on findDevice — device_state hits the catch branch'
        parent = new ThrowingParent()
        atomicStateMap.conditions = [
            [type: 'device_state', deviceId: 1L, attribute: 'switch',
             operator: 'equals', value: 'on']
        ]
        settingsMap.conditionLogic = 'all'

        expect: 'the caught exception yields false, so all-logic is false'
        script.evaluateConditions() == false
    }

    def "all-logic short-circuits: a false condition stops subsequent evaluations"() {
        given: 'two device_state conditions — the first will miss'
        def counting = new CountingParent()
        parent = counting
        atomicStateMap.conditions = [
            [type: 'device_state', deviceId: 1L, attribute: 'switch',
             operator: 'equals', value: 'on'],   // miss — device 1 has no entry
            [type: 'device_state', deviceId: 2L, attribute: 'switch',
             operator: 'equals', value: 'on']    // would hit, but should be skipped
        ]
        settingsMap.conditionLogic = 'all'

        when:
        def result = script.evaluateConditions()

        then:
        result == false
        counting.lookups == [1L]  // no lookup for device 2 — proves short-circuit
    }

    def "any-logic short-circuits: a matching condition stops subsequent evaluations"() {
        given:
        def counting = new CountingParent(devices: [
            1L: new support.TestDevice(id: 1, label: 'Match', attributeValues: [switch: 'on'])
        ])
        parent = counting
        atomicStateMap.conditions = [
            [type: 'device_state', deviceId: 1L, attribute: 'switch',
             operator: 'equals', value: 'on'],   // hit
            [type: 'device_state', deviceId: 2L, attribute: 'switch',
             operator: 'equals', value: 'on']    // should be skipped
        ]
        settingsMap.conditionLogic = 'any'

        when:
        def result = script.evaluateConditions()

        then:
        result == true
        counting.lookups == [1L]
    }

    static class ThrowingParent {
        Object findDevice(id) {
            throw new RuntimeException("simulated findDevice failure")
        }
    }

    /**
     * Parent stub that records every findDevice(id) call in order — lets
     * short-circuit tests assert the second condition's lookup never fires.
     */
    static class CountingParent {
        Map<Long, support.TestDevice> devices = [:]
        List<Long> lookups = []

        Object findDevice(id) {
            def key = (id as Long)
            lookups << key
            devices[key]
        }
    }
}
