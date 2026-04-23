package rules

import support.TestDevice

/**
 * Backfill regression tests for rule-engine bugs that shipped in releases
 * prior to the Groovy/Spock harness being introduced (#69). Each feature
 * method pins behaviour that a specific historical fix restored — so if a
 * future refactor reintroduces the buggy shape, the relevant assertion
 * trips.
 *
 * Sister spec to {@link server.RegressionsFromHistorySpec}. See that file
 * for the overall scope/source-material notes; this spec only covers
 * regressions whose reproduction lives inside the rule-engine child app.
 *
 * Regressions that were already pinned by the existing breadth specs are
 * noted here but not duplicated:
 * <ul>
 *   <li>v0.8.6 — {@code days_of_week} uses only 1-arg {@code Date.format}:
 *       {@link ConditionTypesSpec} covers day-name match/miss/absent.</li>
 *   <li>v0.7.7 — short-circuit condition evaluation:
 *       {@link EvaluateConditionsSpec} pins {@code all}/{@code any} short-
 *       circuit via the CountingParent fixture.</li>
 *   <li>v0.7.7 — {@code variable_math} double atomicState read:
 *       {@link ActionTypesSpec} pins the read-once/write-once shape through
 *       the six {@code variable_math} tests.</li>
 *   <li>v0.1.5 — {@code capture_state}/{@code restore_state} across rules:
 *       {@link ActionTypesSpec} pins both the write path via
 *       {@code parent.saveCapturedState} and the read path via
 *       {@code parent.getCapturedState}.</li>
 *   <li>v0.1.16 / v0.1.8 / v0.1.7 — duration trigger re-arming + single-
 *       fire semantics: {@link TriggerBreadthSpec} covers scheduling, the
 *       re-arm gate, and {@code checkDurationTrigger} still-met branches.
 *       </li>
 * </ul>
 */
class RegressionsFromHistorySpec extends RuleHarnessSpec {

    // --- repeat action `count` legacy parameter name (v0.1.6) ---------------
    //
    // Pre-v0.1.6 the repeat action was wired to `action.count` while the UI
    // emitted `action.times` (or vice versa) — either way, rules saved under
    // the old name broke after the rename. The fix added a fallback chain
    // `action.times ?: action.count ?: 1` at hubitat-mcp-rule.groovy:3601
    // so legacy rules keep working. Regression: feed a `count`-shaped action
    // and confirm the inner actions run that many times.

    def "repeat action honours the legacy `count` parameter as a fallback for `times`"() {
        given:
        def dev = Spy(TestDevice) { getId() >> 7 }
        parent = new RepeatParent(devices: [7L: dev])

        when: 'use the legacy `count` key, not `times`'
        script.executeAction([
            type: 'repeat', count: 4,
            actions: [[type: 'device_command', deviceId: 7L, command: 'on']]
        ])

        then:
        4 * dev.on()
    }

    // --- action-return semantics (v0.1.22 — "action returns") ---------------
    //
    // v0.1.22's "Major bug fixes: action returns, validation, type coercion"
    // corrected the executeAction return contract: `false` means stop, any
    // other value means continue. The repeat action uses this to bail out
    // when an inner action (e.g. a nested `stop`) returns false, rather
    // than silently grinding through every iteration. Regression: inside a
    // `times=5` repeat, a `stop` in the first iteration must halt all
    // remaining iterations AND prevent subsequent outer actions.

    def "repeat halts when an inner action returns false (stop semantics propagate)"() {
        given:
        def inner = Spy(TestDevice) { getId() >> 1 }
        def outer = Spy(TestDevice) { getId() >> 2 }
        parent = new RepeatParent(devices: [1L: inner, 2L: outer])

        and:
        atomicStateMap.actions = [
            [type: 'repeat', times: 5, actions: [
                [type: 'device_command', deviceId: 1L, command: 'on'],
                [type: 'stop']
            ]],
            [type: 'device_command', deviceId: 2L, command: 'on']
        ]

        when:
        script.executeActions()

        then: 'inner runs once in iteration 1, then stop triggers — no 2nd iteration'
        1 * inner.on()

        and: 'the outer action after the repeat also does not run'
        0 * outer.on()
    }

    // --- substituteVariables %now% resolution (v0.7.6 — now() shadowing) ----
    //
    // v0.7.6 fixed a subtle bug where a local variable named `now` shadowed
    // the `now()` call inside rule conditions. The fix (and subsequent code
    // layout) keeps {@code substituteVariables} resolving {@code %now%} via
    // a fresh {@code new Date()} rather than a captured local. Regression:
    // even when {@code atomicState.localVariables} has a key called
    // {@code now}, the built-in {@code %now%} token still renders as a
    // formatted timestamp (not the local-variable value).

    def "%now% resolves to a formatted timestamp even when a local variable named 'now' exists"() {
        given: 'a local variable named "now" whose value would collide with the built-in'
        atomicStateMap.localVariables = [now: 'LOCAL_VAR_SHOULD_NOT_WIN']

        when:
        def result = script.substituteVariables('time is %now%')

        then: 'the built-in timestamp wins — regression guard for the v0.7.6 shadow fix'
        result != 'time is LOCAL_VAR_SHOULD_NOT_WIN'
        // Hubitat's timestamp format is "yyyy-MM-dd HH:mm:ss" — pin the shape,
        // not the exact value (new Date() means the seconds drift between runs).
        result =~ /time is \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}/
    }

    // --- evaluateConditions fail-closed on evaluator throw (v0.7.7) ---------
    //
    // Already pinned by {@link EvaluateConditionsSpec} via {@code ThrowingParent}.
    // Repeated here as a sanity check + explicit release-note citation so a
    // reader landing on this file via the backfill trail gets the full
    // picture without a cross-file hop.

    def "evaluateConditions treats a per-condition throw as a miss (fail closed)"() {
        given: 'a single device_state condition whose parent.findDevice throws'
        parent = new ThrowingFindParent()
        atomicStateMap.conditions = [
            [type: 'device_state', deviceId: 1L, attribute: 'switch', operator: 'equals', value: 'on']
        ]
        settingsMap.conditionLogic = 'all'

        expect: 'the throw is caught inside evaluateCondition — aggregator returns false'
        script.evaluateConditions() == false
    }

    static class RepeatParent {
        Map<Long, TestDevice> devices = [:]
        Object findDevice(id) { devices[(id as Long)] }
    }

    static class ThrowingFindParent {
        Object findDevice(id) {
            throw new RuntimeException('simulated parent.findDevice failure')
        }
    }
}
