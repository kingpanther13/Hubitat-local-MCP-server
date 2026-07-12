package server

import groovy.json.JsonOutput
import spock.lang.Unroll
import support.ToolSpecBase

/**
 * Spec for the cloud-relay self-budget (issue #348, section C). When a request
 * arrives over the cloud relay, a partial-commit loop checks its own elapsed time
 * between committed sub-steps and, before the relay's fixed ceiling, returns a
 * success-shaped in_progress envelope with resume info -- so the response is never
 * lost mid-loop and the already-committed work is not repeated.
 *
 * Two loops carry the checkpoint (libraries/mcp-native-rules-lib.groovy):
 *   - _rmDriveWalkSteps  -- between ordered walkStep drive steps
 *   - _applyNativeAppEdit -- between patches[] ops (the deferred trailing updateRule
 *     must NOT fire on a pause; it fires when the remaining patches complete)
 *
 * The generic machinery (_isCloudRequest / _relayBudgetMs / _relayBudgetExceeded)
 * lives in the main file. The loop tests stub _relayBudgetExceeded so the pause is
 * deterministic; the composition of that predicate is unit-tested separately with
 * _isCloudRequest stubbed and the harness's fixed clock (now()==1234567890000L).
 * A LAN request or relayBudgetMs=0 short-circuits the guard, so those loops keep
 * their pre-#348 shape byte-for-byte -- covered by the not-exceeded cases below.
 */
class RelayBudgetSpec extends ToolSpecBase {

    private static final long FIXED_NOW = 1234567890000L

    // ---------------- generic budget helpers (main file) ----------------

    def "_relayBudgetMs defaults to 8000 when the setting is unset"() {
        expect:
        script._relayBudgetMs() == 8000L
    }

    def "_relayBudgetMs honours settings.relayBudgetMs"() {
        given:
        settingsMap.relayBudgetMs = 5000

        expect:
        script._relayBudgetMs() == 5000L
    }

    def "_isCloudRequest defaults to false when no cloud request marker is present"() {
        // The harness request has no requestSource, so the try/catch returns the
        // safe LAN default -- LAN behaviour must be identical to today.
        expect:
        script._isCloudRequest() == false
    }

    @Unroll
    def "_relayBudgetExceeded fires only for a cloud request past a live budget (#desc)"() {
        given:
        settingsMap.relayBudgetMs = budgetMs
        script.metaClass._isCloudRequest = { -> cloud }

        expect:
        script._relayBudgetExceeded(t0) == expected

        where:
        desc                        | cloud | budgetMs | t0                | expected
        'cloud, over budget'        | true  | 100      | FIXED_NOW - 200L  | true
        'cloud, under budget'       | true  | 100      | FIXED_NOW - 50L   | false
        'cloud, null t0'            | true  | 100      | null              | false
        'cloud, budget disabled 0'  | true  | 0        | FIXED_NOW - 200L  | false
        'LAN, over budget'          | false | 100      | FIXED_NOW - 200L  | false
    }

    // ---------------- _rmDriveWalkSteps checkpoint ----------------

    private List installWalkerStubs(List walkCalls, boolean pause) {
        script.metaClass._rmWalkStep = { Integer id, Map step ->
            walkCalls << new LinkedHashMap(step); [page: step.page, success: true]
        }
        script.metaClass._rmCheckRuleHealth = { Integer id -> [ok: true] }
        script.metaClass._relayBudgetExceeded = { Long t0 -> pause }
        return walkCalls
    }

    def "a drive over a tiny cloud budget commits the first step then pauses, handing back the rest"() {
        given:
        def walkCalls = []
        installWalkerStubs(walkCalls, true)

        when: 'three ordered steps; the budget is already blown'
        def result = script._rmDriveWalkSteps(1, [operation: 'drive', __reqT0: 1000L, steps: [
            [page: 'p1', operation: 'introspect'],
            [page: 'p2', operation: 'introspect'],
            [page: 'p3', operation: 'introspect'],
        ]])

        then: 'step 1 still ran (never pause before the first step); steps 2-3 did not'
        walkCalls.size() == 1
        walkCalls[0].page == 'p1'

        and: 'a success-shaped in_progress envelope naming the next step and the unrun work'
        result.status == 'in_progress'
        result.success == true
        result.pausedAtStep == 2
        result.stepsRemaining instanceof List
        result.stepsRemaining.size() == 2
        result.stepsRemaining[0].page == 'p2'
        result.stepsRemaining[1].page == 'p3'
        result.resume?.note instanceof String && !result.resume.note.trim().isEmpty()

        and: 'the internal budget marker is never echoed into the handed-back work'
        !JsonOutput.toJson(result).contains('__reqT0')
    }

    def "a drive within budget runs every step and keeps its pre-budget shape"() {
        given:
        def walkCalls = []
        installWalkerStubs(walkCalls, false)

        when:
        def result = script._rmDriveWalkSteps(1, [operation: 'drive', __reqT0: 1000L, steps: [
            [page: 'p1', operation: 'introspect'],
            [page: 'p2', operation: 'introspect'],
            [page: 'p3', operation: 'introspect'],
        ]])

        then: 'all three steps ran and no in_progress/resume shape appears'
        walkCalls.size() == 3
        result.stepsRun == 3
        result.success == true
        result.status != 'in_progress'
        result.stepsRemaining == null
    }

    // ---------------- _applyNativeAppEdit patches[] checkpoint ----------------

    private void installPatchStubs(List addActionCalls, List clicks, boolean pause) {
        stateMap.lastBackupTimestamp = FIXED_NOW   // requireDestructiveConfirm: backup within 24h
        settingsMap.enableWrite = true
        script.metaClass._rmBackupRuleSnapshot = { Integer id, String reason -> [key: 'snap'] }
        script.metaClass._rmCheckRuleHealth = { Integer id -> [ok: true] }
        script.metaClass._rmAddAction = { Integer id, Map spec, boolean batch = false ->
            addActionCalls << spec; [success: true]
        }
        script.metaClass._rmClickAppButton = { Integer aId, String name, String stateAttr = null,
                                               String pageName = null, Map cache = null -> clicks << name }
        script.metaClass._relayBudgetExceeded = { Long t0 -> pause }
    }

    def "a patches batch over a tiny cloud budget commits the first op then pauses, deferring updateRule"() {
        given:
        def addActionCalls = []
        def clicks = []
        installPatchStubs(addActionCalls, clicks, true)

        when: 'three addAction patch ops; the budget is already blown'
        def result = script._applyNativeAppEdit([appId: 1, confirm: true, __reqT0: 2000L, patches: [
            [addAction: [a: 1]],
            [addAction: [a: 2]],
            [addAction: [a: 3]],
        ]])

        then: 'only the first op committed; the rest are handed back'
        addActionCalls.size() == 1
        result.status == 'in_progress'
        result.success == true
        result.patchesRemaining instanceof List
        result.patchesRemaining.size() == 2

        and: 'the deferred trailing updateRule did NOT fire on the pause (it fires when the remainder completes)'
        !clicks.contains('updateRule')

        and: 'the internal budget marker is never echoed into the handed-back work'
        !JsonOutput.toJson(result).contains('__reqT0')
    }

    def "a patches batch within budget applies every op and fires the trailing updateRule once"() {
        given:
        def addActionCalls = []
        def clicks = []
        installPatchStubs(addActionCalls, clicks, false)

        when:
        def result = script._applyNativeAppEdit([appId: 1, confirm: true, patches: [
            [addAction: [a: 1]],
            [addAction: [a: 2]],
            [addAction: [a: 3]],
        ]])

        then: 'all three ops ran, the trailing updateRule fired, and no in_progress shape appears'
        addActionCalls.size() == 3
        clicks.contains('updateRule')
        result.success == true
        result.status != 'in_progress'
        result.patchesRemaining == null
    }

    // ---------------- __reqT0 threading: dispatch -> gateway -> handler ----------------
    //
    // The loop tests above plant __reqT0 directly, so they cannot catch a regression in
    // the delivery chain: the _budgetAwareTools allowlist injection in handleToolsCall,
    // the handleGateway re-injection into leaf args, and the _setRuleFromEnvelope
    // re-carry (it rebuilds args and would drop the key). These two capture the args
    // that actually reach _applyNativeAppEdit after a full dispatch ride and assert the
    // clock arrived non-null -- the same stub boundary SetRuleSelfGatewaySpec uses, since
    // the wizard internals below it need a live hub. The real _isCloudRequest()==true
    // branch needs a cloud-relayed request object the harness cannot fabricate; it is
    // verified live (requestSource=='cloud' probe).

    def "the budget clock reaches _applyNativeAppEdit through dispatch and the gateway (canonical form)"() {
        given:
        settingsMap.enableWrite = true
        def captured = [:]
        script.metaClass._applyNativeAppEdit = { Map a -> captured.edit = a; [success: true, appId: 1] }

        when: 'a patches edit arrives through hub_manage_rule_machine'
        def response = mcpDriver.callTool('hub_manage_rule_machine', [tool: 'hub_set_rule', args: [
            appId: 1, confirm: true,
            patches: [[addAction: [a: 1]], [addAction: [a: 2]]]]])

        then: 'the clock arrived non-null through injection + gateway re-injection'
        response.error == null
        captured.edit.__reqT0 instanceof Long
        captured.edit.patches.size() == 2
    }

    def "the budget clock survives the operation-envelope rebuild (envelope form)"() {
        given:
        settingsMap.enableWrite = true
        def captured = [:]
        script.metaClass._applyNativeAppEdit = { Map a -> captured.edit = a; [success: true, appId: 1] }

        when: 'the same edit in envelope form -- _setRuleFromEnvelope rebuilds args from scratch'
        def response = mcpDriver.callTool('hub_manage_rule_machine', [tool: 'hub_set_rule', args: [
            operation: 'patches', appId: 1, confirm: true,
            args: [[addAction: [a: 1]], [addAction: [a: 2]]]]])

        then: 'the re-carry preserved the clock across the rebuild'
        response.error == null
        captured.edit.__reqT0 instanceof Long
        captured.edit.patches.size() == 2
    }
}
