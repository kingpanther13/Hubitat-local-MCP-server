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
 *   <li>v0.7.7 — short-circuit condition evaluation + fail-closed on
 *       evaluator throw: {@link EvaluateConditionsSpec} pins both — the
 *       {@code all}/{@code any} short-circuit via CountingParent, and the
 *       throw-caught-as-miss path via ThrowingParent.</li>
 *   <li>v0.7.7 — {@code variable_math} double atomicState read:
 *       {@link ActionTypesSpec} pins the read-once/write-once shape through
 *       its {@code variable_math} tests.</li>
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

    // --- substituteVariables built-in-token precedence -----------------------
    //
    // Not a named-release regression — an ordering invariant worth pinning
    // because its correctness is easy to regress. {@code substituteVariables}
    // at hubitat-mcp-rule.groovy:3229-3274 replaces built-in tokens
    // ({@code %now%}, {@code %mode%}, etc.) BEFORE iterating
    // {@code atomicState.localVariables}. If a future refactor swaps the
    // order, a user whose rule defines a local variable called
    // {@code now} would see its value take over the built-in {@code %now%}
    // token — surprising and hard to debug. (The v0.7.6 "variable shadowing
    // of now()" fix applied to a different code path; that's guarded by
    // sandbox_lint.py and code review, not by this test.)

    def "built-in %now% token is resolved before the localVariables loop runs"() {
        given: 'a local variable named "now" whose value would collide with the built-in'
        atomicStateMap.localVariables = [now: 'LOCAL_VAR_SHOULD_NOT_WIN']

        when:
        def result = script.substituteVariables('time is %now%')

        then: 'the built-in timestamp wins because its replace() runs first'
        result != 'time is LOCAL_VAR_SHOULD_NOT_WIN'
        // Hubitat's timestamp format is "yyyy-MM-dd HH:mm:ss" — pin the shape,
        // not the exact value (new Date() means the seconds drift between runs).
        result ==~ /time is \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}/
    }

    // Note on v0.7.7 short-circuit + fail-closed aggregator:
    // {@link EvaluateConditionsSpec} already pins the `all`/`any`
    // short-circuit via CountingParent and the throw-caught-as-miss path
    // via ThrowingParent. Not duplicated here — see the class-Javadoc
    // cross-reference above.

    static class RepeatParent {
        Map<Long, TestDevice> devices = [:]
        Object findDevice(id) { devices[(id as Long)] }
    }
}
