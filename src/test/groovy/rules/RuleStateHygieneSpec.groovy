package rules

/**
 * State-hygiene coverage for the legacy rule engine's variable + in-place-edit
 * paths in hubitat-mcp-rule.groovy:
 *  - localVariables size guard: a passive warn-once when the map crosses the
 *    threshold, with NO eviction (user-named variables are preserved), re-armed
 *    on rule save (updated()).
 *  - stale cancelledDelayIds / durationFired cleanup when a rule is edited in
 *    place via updateRuleFromParent (parity with initialize()'s re-init hygiene).
 */
class RuleStateHygieneSpec extends RuleHarnessSpec {

    // ---- localVariables size guard (passive warn-once, no eviction) ----

    def "set_local_variable warns once when localVariables crosses the threshold and never evicts"() {
        given:
        settingsMap.ruleName = 'Var Namer'
        parent = new HygieneParent()

        when: '100 distinct local variables are written'
        (0..99).each { i ->
            script.executeAction([type: 'set_local_variable', variableName: 'var' + i, value: 'v' + i])
        }

        then: 'all 100 are retained (no eviction) and the warn fired exactly once'
        atomicStateMap.localVariables.size() == 100
        atomicStateMap.localVarsWarned == true
        parent.warnCount() == 1

        when: 'one more is written past the threshold'
        script.executeAction([type: 'set_local_variable', variableName: 'var100', value: 'x'])

        then: 'still exactly one warning (warn-once), and the new var is retained'
        atomicStateMap.localVariables.size() == 101
        parent.warnCount() == 1
    }

    def "variable_math local scope also triggers the size guard at the threshold"() {
        given: '99 keys already present so one more local write crosses 100'
        settingsMap.ruleName = 'Math Namer'
        parent = new HygieneParent()
        atomicStateMap.localVariables = (0..98).collectEntries { ['k' + it, it] }

        when:
        script.executeAction([type: 'variable_math', variableName: 'k99',
                              scope: 'local', operation: 'set', operand: 1])

        then:
        atomicStateMap.localVariables.size() == 100
        atomicStateMap.localVarsWarned == true
        parent.warnCount() == 1
    }

    def "updated re-arms the local-variable size warning without clearing the variables"() {
        given:
        settingsMap.ruleName = 'Rearm'
        settingsMap.ruleEnabled = false
        atomicStateMap.localVarsWarned = true
        atomicStateMap.localVariables = (0..99).collectEntries { ['k' + it, it] }

        when:
        script.updated()

        then: 'the warn flag is re-armed but the user variables are preserved'
        atomicStateMap.localVarsWarned == false
        atomicStateMap.localVariables.size() == 100
    }

    // ---- stale-key cleanup on the in-place edit path ----

    def "updateRuleFromParent clears stale cancelledDelayIds, durationFired, and the loop-guard window on edit"() {
        given: 'leftover suppression markers + duration + loop-guard state from a pre-edit action set'
        parent = new HygieneParent()
        atomicStateMap.cancelledDelayIds = [stale: true, gone: true]
        atomicStateMap.durationFired = [duration_1_motion: true]
        atomicStateMap.durationTimers = [duration_1_motion: [startTime: 1]]
        atomicStateMap.recentExecutions = [1L, 2L, 3L]

        when: 'the parent pushes a new action set; rule left disabled'
        script.updateRuleFromParent([triggers: [], conditions: [], actions: [], enabled: false])

        then: 'all stale in-place-edit state is cleared together'
        atomicStateMap.cancelledDelayIds == [:]
        atomicStateMap.durationFired == null
        atomicStateMap.recentExecutions == []
    }

    def "updateRuleFromParent re-arms the local-variable size warning on an MCP edit"() {
        given: 'a latched warn flag + a full variable map (MCP edits do not fire updated())'
        parent = new HygieneParent()
        atomicStateMap.localVarsWarned = true
        atomicStateMap.localVariables = (0..99).collectEntries { ['k' + it, it] }

        when:
        script.updateRuleFromParent([triggers: [], conditions: [], actions: [], enabled: false])

        then: 'the flag is re-armed (parity with updated()) and the user variables are preserved'
        atomicStateMap.localVarsWarned == false
        atomicStateMap.localVariables.size() == 100
    }

    /**
     * Parent that records mcpLog pushes so the passive size-guard warning is
     * observable (ruleLog routes warn/info/etc. through parent.mcpLog).
     */
    static class HygieneParent {
        List<List> logCalls = []
        Object findDevice(id) { null }
        void mcpLog(String level, String category, String message, String ruleId, Map extraData) {
            logCalls << [level, category, message, ruleId, extraData]
        }
        int warnCount() { logCalls.count { it[0] == 'warn' } }
    }
}
