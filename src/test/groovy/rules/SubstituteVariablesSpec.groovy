package rules

/**
 * Spec for hubitat-mcp-rule.groovy::substituteVariables.
 *
 * Substitutes %name% placeholders in action-text payloads. Sources in
 * resolution order: event fields (displayName / value / name), %now%,
 * %mode% from location, atomicState.localVariables, getGlobalVar(),
 * parent.getVariableValue() fallback. Unknown placeholders are left as-is.
 *
 * The implementation dereferences `location.mode` unconditionally (handled
 * by RuleHarnessSpec's default getLocation stub on AppExecutor) and calls
 * `getGlobalVar()`, which is not on the AppExecutor interface so it's
 * metaClass-stubbed here.
 */
class SubstituteVariablesSpec extends RuleHarnessSpec {

    // A place to record what getGlobalVar is asked for so tests can inspect it
    // without having to re-stub per case.
    Map<String, Object> globalVars = [:]

    @Override
    protected void wireOverrides() {
        // getGlobalVar is a Hubitat hub-variable accessor not declared on
        // the AppExecutor interface, so metaClass override is the right hook.
        // Returns from the spec-controlled map, or null to force the
        // parent.getVariableValue fallback.
        def store = globalVars
        script.metaClass.getGlobalVar = { String name ->
            store.containsKey(name) ? [value: store[name]] : null
        }
    }

    def "empty or null text is returned unchanged"() {
        expect:
        script.substituteVariables('') == ''
        script.substituteVariables(null) == null
    }

    def "text with no placeholders is returned unchanged"() {
        expect:
        script.substituteVariables('hello world') == 'hello world'
    }

    def "event placeholders substitute from the passed event"() {
        given:
        def evt = [displayName: 'Kitchen Light', value: 'on', name: 'switch']

        when:
        def result = script.substituteVariables('%device% turned %value% (%name%)', evt)

        then:
        result == 'Kitchen Light turned on (switch)'
    }

    def "event placeholders become empty strings when event is null"() {
        expect: 'no event context → no substitution → placeholders stay as-is'
        script.substituteVariables('%device% turned %value%') == '%device% turned %value%'
    }

    def "%mode% substitutes from location.mode"() {
        expect:
        script.substituteVariables('Mode is %mode%') == 'Mode is Home'
    }

    def "local variables substitute from atomicState.localVariables"() {
        given:
        atomicStateMap.localVariables = [threshold: 42, tempUnit: 'F']

        expect:
        script.substituteVariables('Threshold: %threshold% deg %tempUnit%') ==
            'Threshold: 42 deg F'
    }

    def "global hub variable substitutes from getGlobalVar"() {
        given:
        globalVars.houseState = 'occupied'

        expect:
        script.substituteVariables('House is %houseState%') == 'House is occupied'
    }

    def "unknown placeholder falls back to parent.getVariableValue"() {
        given:
        globalVars = [:]  // hub global returns null
        parent = [getVariableValue: { String name -> name == 'ruleVar' ? 'fromParent' : null }]

        expect:
        script.substituteVariables('Rule: %ruleVar%') == 'Rule: fromParent'
    }

    def "placeholder with no source anywhere is left untouched"() {
        given:
        globalVars = [:]
        parent = [getVariableValue: { String name -> null }]

        expect:
        script.substituteVariables('Unknown: %neverDefined%') == 'Unknown: %neverDefined%'
    }
}
