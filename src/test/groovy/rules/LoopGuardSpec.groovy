package rules

/**
 * Spec for hubitat-mcp-rule.groovy::executeRule loop-guard behaviour (see #75).
 *
 * The loop guard prevents a rule from stampeding (e.g. "switch A on" action
 * triggers "switch A on" again). It reads {@code parent.settings.loopGuardMax}
 * (default 30) and {@code loopGuardWindowSec} (default 60) and counts entries
 * in {@code atomicState.recentExecutions} within the sliding window. When the
 * count meets or exceeds the threshold, it auto-disables the rule via
 * {@code app.updateSetting("ruleEnabled", false)}, then calls
 * {@code unsubscribe()} / {@code unschedule()} / {@code notifyLoopGuard()}.
 *
 * {@code now()} is fixed at {@code 1234567890000L} by the harness, so the
 * "within window" math works off that constant. Specs seed
 * {@code recentExecutions} relative to that value.
 *
 * {@code app.updateSetting("ruleEnabled", false)} writes to the shared
 * {@link support.TestChildApp}'s {@code settingsStore} map — NOT to
 * {@code settingsMap}. Specs verify via
 * {@code appExecutor.getApp().settingsStore.ruleEnabled == false}.
 */
class LoopGuardSpec extends RuleHarnessSpec {

    // The harness's fixed mocked now(), used to construct in-window timestamps.
    private static final long FIXED_NOW = 1234567890000L

    def "under threshold: rule executes and the exec timestamp is appended"() {
        given: 'seed ruleEnabled=true on the shared TestChildApp so we can assert it stays true'
        settingsMap.ruleEnabled = true
        settingsMap.ruleName = 'Rule Under Limit'
        appExecutor.getApp().settingsStore.ruleEnabled = true
        parent = new LoopGuardParent(settings: [loopGuardMax: 30, loopGuardWindowSec: 60])
        atomicStateMap.conditions = []
        atomicStateMap.actions = []
        atomicStateMap.recentExecutions = []

        when:
        script.executeRule('test')

        then: 'exec recorded, ruleEnabled untouched (auto-disable would flip it to false)'
        atomicStateMap.recentExecutions == [FIXED_NOW]
        appExecutor.getApp().settingsStore.ruleEnabled == true
    }

    def "at threshold: rule auto-disables, unsubscribes, unschedules, and emits mcpLoopGuard"() {
        given:
        settingsMap.ruleEnabled = true
        settingsMap.ruleName = 'Rule Over Limit'
        parent = new LoopGuardParent(settings: [loopGuardMax: 3, loopGuardWindowSec: 60])
        atomicStateMap.conditions = []
        atomicStateMap.actions = []
        // Three in-window exec timestamps == loopGuardMax
        atomicStateMap.recentExecutions = [
            FIXED_NOW - 10_000L,
            FIXED_NOW - 5_000L,
            FIXED_NOW - 1_000L
        ]

        when:
        script.executeRule('test')

        then: 'auto-disable writes ruleEnabled=false on the app settings'
        appExecutor.getApp().settingsStore.ruleEnabled == false

        and: 'unsubscribe() and the blanket unschedule() both fire'
        unsubscribeCount == 1
        unscheduleAllCount == 1

        and: 'recentExecutions is cleared'
        atomicStateMap.recentExecutions == []

        and: 'a public mcpLoopGuard location event is fired so other rules can subscribe'
        sendLocationEventCalls.any {
            it.name == 'mcpLoopGuard' && it.value == 'Rule Over Limit'
        }
    }

    def "window pruning: out-of-window entries do not count toward the threshold"() {
        given:
        settingsMap.ruleEnabled = true
        settingsMap.ruleName = 'Rule Pruning'
        appExecutor.getApp().settingsStore.ruleEnabled = true
        parent = new LoopGuardParent(settings: [loopGuardMax: 3, loopGuardWindowSec: 60])
        atomicStateMap.conditions = []
        atomicStateMap.actions = []
        // Three old (outside 60s window) + zero in-window → count is 0, not 3
        atomicStateMap.recentExecutions = [
            FIXED_NOW - 120_000L,
            FIXED_NOW - 90_000L,
            FIXED_NOW - 70_000L
        ]

        when:
        script.executeRule('test')

        then: 'rule still fires — old entries were pruned, ruleEnabled stays true'
        appExecutor.getApp().settingsStore.ruleEnabled == true
        // Pruned + one new entry → just [FIXED_NOW]
        atomicStateMap.recentExecutions == [FIXED_NOW]
    }

    def "custom loopGuardMax from parent.settings is honoured"() {
        given: 'a tight loopGuardMax of 2'
        settingsMap.ruleEnabled = true
        settingsMap.ruleName = 'Tight Limit'
        parent = new LoopGuardParent(settings: [loopGuardMax: 2, loopGuardWindowSec: 60])
        atomicStateMap.conditions = []
        atomicStateMap.actions = []
        atomicStateMap.recentExecutions = [
            FIXED_NOW - 5_000L,
            FIXED_NOW - 1_000L
        ]

        when:
        script.executeRule('test')

        then: 'threshold of 2 triggers auto-disable'
        appExecutor.getApp().settingsStore.ruleEnabled == false
    }

    /**
     * Minimal loop-guard parent. {@code settings} map carries loopGuardMax
     * and loopGuardWindowSec; {@code getSelectedDevices()} returns an empty
     * list so {@code notifyLoopGuard} can finish without NPEs when the
     * guard trips.
     */
    static class LoopGuardParent {
        Map<String, Object> settings = [:]
        List getSelectedDevices() { [] }
    }
}
