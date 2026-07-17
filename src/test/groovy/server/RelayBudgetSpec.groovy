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
 * Loops that carry the checkpoint (libraries/mcp-native-rules-lib.groovy):
 *   - _rmDriveWalkSteps  -- between ordered walkStep drive steps (clean-partial pause)
 *   - _applyNativeAppEdit -- between patches[] ops AND mid-op inside a patch op's bulk
 *     addTriggers/addActions inner list (the deferred trailing updateRule must NOT fire
 *     on a pause; it fires when the remaining patches complete)
 *   - _applyNativeAppEdit -- between plain bulk addTriggers[]/addActions[] items (same
 *     deferred-updateRule contract; the trigger-loop pause hands back the unrun triggers
 *     AND every action). The bulk and patches pauses stop as soon as the budget is spent
 *     REGARDLESS of whether an earlier item failed -- continuing un-budgeted risks the
 *     relay dropping the whole response -- and surface any failed/degraded item in the
 *     pause envelope's success/partial rather than masking it as a clean success.
 *
 * The generic machinery (_isCloudRequest / _relayBudgetMs / _lanBudgetMs /
 * _timeBudgetExceeded) lives in the main file. The loop tests stub
 * _timeBudgetExceeded so the pause is deterministic; the composition of that
 * predicate is unit-tested separately with _isCloudRequest stubbed and the
 * harness's fixed clock (now()==1234567890000L). The budget is per-source (issue
 * #351): a cloud request reads relayBudgetMs (default 8000), a LAN request reads
 * lanBudgetMs (default 0 = off) -- so with the LAN knob unset, LAN loops keep
 * their pre-#348 shape byte-for-byte, covered by the not-exceeded cases below.
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

    def "_lanBudgetMs defaults to 0 (off) when the setting is unset"() {
        expect:
        script._lanBudgetMs() == 0L
    }

    def "_lanBudgetMs honours settings.lanBudgetMs"() {
        given:
        settingsMap.lanBudgetMs = 45000

        expect:
        script._lanBudgetMs() == 45000L
    }

    @Unroll
    def "_timeBudgetExceeded fires only past the live budget for the request's source (#desc)"() {
        given:
        settingsMap.relayBudgetMs = relayMs
        if (lanMs != null) settingsMap.lanBudgetMs = lanMs
        script.metaClass._isCloudRequest = { -> cloud }

        expect:
        script._timeBudgetExceeded(t0) == expected

        where:
        desc                          | cloud | relayMs | lanMs | t0                | expected
        'cloud, over budget'          | true  | 100     | null  | FIXED_NOW - 200L  | true
        'cloud, under budget'         | true  | 100     | null  | FIXED_NOW - 50L   | false
        'cloud, null t0'              | true  | 100     | null  | null              | false
        'cloud, budget disabled 0'    | true  | 0       | null  | FIXED_NOW - 200L  | false
        'LAN, lan budget unset (off)' | false | 100     | null  | FIXED_NOW - 999L  | false
        'LAN, lan budget live, over'  | false | 100     | 200   | FIXED_NOW - 300L  | true
        'LAN, lan budget live, under' | false | 100     | 200   | FIXED_NOW - 50L   | false
        'LAN, lan budget disabled 0'  | false | 100     | 0     | FIXED_NOW - 300L  | false
        'cloud ignores the LAN knob'  | true  | 0       | 50    | FIXED_NOW - 200L  | false
    }

    // ---------------- _rmDriveWalkSteps checkpoint ----------------

    private List installWalkerStubs(List walkCalls, boolean pause) {
        script.metaClass._rmWalkStep = { Integer id, Map step ->
            walkCalls << new LinkedHashMap(step); [page: step.page, success: true]
        }
        script.metaClass._rmCheckRuleHealth = { Integer id -> [ok: true] }
        script.metaClass._timeBudgetExceeded = { Long t0 -> pause }
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
        script.metaClass._rmAddAction = { Integer id, Map spec, boolean batch = false, Set validRuleIds = null ->
            addActionCalls << spec; [success: true]
        }
        script.metaClass._rmClickAppButton = { Integer aId, String name, String stateAttr = null,
                                               String pageName = null, Map cache = null -> clicks << name }
        script.metaClass._timeBudgetExceeded = { Long t0 -> pause }
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

    def "a patch op with a large inner addActions list pauses MID-op, rewriting the op into patchesRemaining"() {
        given:
        def addActionCalls = []
        def clicks = []
        installPatchStubs(addActionCalls, clicks, true)

        when: 'ONE patch op carrying three inner addActions (each with a stray internal clock); the budget is already blown'
        def result = script._applyNativeAppEdit([appId: 1, confirm: true, __reqT0: 2000L, patches: [
            [addActions: [
                [capability: 'switch', action: 'on', deviceIds: [8], __reqT0: 999L],
                [capability: 'switch', action: 'off', deviceIds: [9], __reqT0: 999L],
                [capability: 'switch', action: 'on', deviceIds: [10], __reqT0: 999L],
            ]],
        ]])

        then: 'only the first inner action ran (never pause before the first inner item)'
        addActionCalls.size() == 1
        result.status == 'in_progress'

        and: 'patchesRemaining leads with the SAME op rewritten to its two unprocessed inner actions'
        result.patchesRemaining instanceof List
        result.patchesRemaining.size() == 1
        result.patchesRemaining[0].addActions instanceof List
        result.patchesRemaining[0].addActions.size() == 2
        result.patchesRemaining[0].addActions[0].deviceIds == [9]

        and: 'the internal clock is stripped from the NESTED inner specs, not just the patch op'
        !result.patchesRemaining[0].addActions.any { it.containsKey('__reqT0') }

        and: 'the current op is recorded partial in patchResults and no updateRule fired'
        result.patchResults.find { it.op == 'addActions' }?.partial == true
        !clicks.contains('updateRule')
        !JsonOutput.toJson(result).contains('__reqT0')
    }

    def "a patch op with a large inner addTriggers list pauses MID-op, rewriting the op into patchesRemaining"() {
        given:
        def addActionCalls = []
        def clicks = []
        installPatchStubs(addActionCalls, clicks, true)

        when: 'ONE patch op carrying three inner addTriggers (first is a non-Map, recorded inline -- _rmAddTrigger is private/unstubbable); budget already blown'
        def result = script._applyNativeAppEdit([appId: 1, confirm: true, __reqT0: 2000L, patches: [
            [addTriggers: [
                'not-a-map',
                [capability: 'Switch', state: 'off', deviceIds: [9]],
                [capability: 'Motion', state: 'active', deviceIds: [10]],
            ]],
        ]])

        then: 'the budget paused after the first inner trigger, before the second'
        result.status == 'in_progress'

        and: 'patchesRemaining leads with the SAME op rewritten to its two unprocessed inner triggers'
        result.patchesRemaining instanceof List
        result.patchesRemaining.size() == 1
        result.patchesRemaining[0].addTriggers instanceof List
        result.patchesRemaining[0].addTriggers.size() == 2
        result.patchesRemaining[0].addTriggers[0].state == 'off'

        and: 'the op is recorded partial in patchResults and no updateRule fired'
        result.patchResults.find { it.op == 'addTriggers' }?.partial == true
        !clicks.contains('updateRule')
        !JsonOutput.toJson(result).contains('__reqT0')
    }

    def "_patchesPauseResult surfaces a failed op as outer success:false + partial, not masked"() {
        when: 'a pause built from patch results where one op failed'
        def result = script._patchesPauseResult(5, [key: 'snap'],
            [[success: true, op: 'addAction'], [success: false, op: 'addAction', error: 'boom']],
            [[addAction: [a: 9]]])

        then: 'the outer envelope reports the failure, not a clean in_progress success'
        result.status == 'in_progress'
        result.success == false
        result.partial == true
        result.appId == 5
        result.patchesRemaining.size() == 1

        and: 'an all-clean pause instead reports success:true / partial:false'
        def clean = script._patchesPauseResult(5, [key: 'snap'],
            [[success: true, op: 'addAction'], [success: true, op: 'addAction']],
            [[addAction: [a: 9]]])
        clean.success == true
        clean.partial == false
    }

    // ---------------- _applyNativeAppEdit bulk addTriggers[]/addActions[] checkpoint ----------------

    private void installBulkStubs(List triggerCalls, List actionCalls, List clicks, boolean pause) {
        stateMap.lastBackupTimestamp = FIXED_NOW   // requireDestructiveConfirm: backup within 24h
        settingsMap.enableWrite = true
        script.metaClass._rmBackupRuleSnapshot = { Integer id, String reason -> [key: 'snap'] }
        script.metaClass._rmCheckRuleHealth = { Integer id -> [ok: true] }
        script.metaClass._rmAddTrigger = { Integer id, Map spec -> triggerCalls << spec; [success: true] }
        script.metaClass._rmAddAction = { Integer id, Map spec, boolean batch = false, Set validRuleIds = null ->
            actionCalls << spec; [success: true]
        }
        script.metaClass._rmClickAppButton = { Integer aId, String name, String stateAttr = null,
                                               String pageName = null, Map cache = null -> clicks << name }
        script.metaClass._timeBudgetExceeded = { Long t0 -> pause }
    }

    def "a bulk addActions batch over a tiny cloud budget commits the first action then pauses, deferring updateRule"() {
        given:
        def triggerCalls = []
        def actionCalls = []
        def clicks = []
        installBulkStubs(triggerCalls, actionCalls, clicks, true)

        when: 'three addActions items; the budget is already blown'
        def result = script._applyNativeAppEdit([appId: 1, confirm: true, __reqT0: 2000L, addActions: [
            [capability: 'switch', action: 'on', deviceIds: [8]],
            [capability: 'switch', action: 'off', deviceIds: [9]],
            [capability: 'switch', action: 'on', deviceIds: [10]],
        ]])

        then: 'only the first action committed (never pause before the first item); the rest are handed back'
        actionCalls.size() == 1
        result.status == 'in_progress'
        result.success == true
        result.actionsCommitted == 1
        (result.actions as List).size() == 1

        and: 'the remaining two actions ride back verbatim; no triggers were in play'
        result.addActionsRemaining instanceof List
        result.addActionsRemaining.size() == 2
        result.addActionsRemaining[0].deviceIds == [9]
        result.addActionsRemaining[1].deviceIds == [10]
        (result.addTriggersRemaining as List).isEmpty()

        and: 'the deferred trailing updateRule did NOT fire on the pause'
        !clicks.contains('updateRule')

        and: 'resume guidance is present and the internal budget marker is never echoed back'
        result.resume?.note instanceof String && !result.resume.note.trim().isEmpty()
        !JsonOutput.toJson(result).contains('__reqT0')
    }

    // A RUNNING trigger-loop pause: the trigger-loop checkpoint hands back a DISTINCT shape
    // from the action-loop pause -- the unrun triggers PLUS every action (the action loop has
    // not started). A SUCCEEDING-trigger running pause cannot be stubbed here: _rmAddTrigger is
    // a PRIVATE method, so the intra-script call resolves invokespecial and a per-instance
    // metaClass stub does not intercept it (unlike the non-private _rmAddAction). So exercise
    // the real running loop via a non-Map trigger[0]: the loop records that failure INLINE (no
    // _rmAddTrigger call needed) and the budget then pauses before trigger[1], driving the real
    // checkpoint + trigList.subList(ti, ...) + actList carry-forward. The clean-partial return
    // shape is pinned separately on _bulkPauseResult below.
    def "the trigger-loop checkpoint hands back the unrun triggers AND all actions on a pause"() {
        given:
        def triggerCalls = []
        def actionCalls = []
        def clicks = []
        installBulkStubs(triggerCalls, actionCalls, clicks, true)

        when: 'trigger[0] is a non-Map (recorded inline, no _rmAddTrigger call); the budget then pauses before trigger[1]'
        def result = script._applyNativeAppEdit([appId: 1, confirm: true, __reqT0: 2000L,
            addTriggers: [
                'not-a-map',
                [capability: 'Switch', state: 'off', deviceIds: [9]],
                [capability: 'Motion', state: 'active', deviceIds: [10]],
            ],
            addActions: [
                [capability: 'switch', action: 'on', deviceIds: [11]],
                [capability: 'switch', action: 'off', deviceIds: [12]],
            ]])

        then: 'the loop paused after item 0 (never before the first item); the action loop never started'
        actionCalls.size() == 0
        result.status == 'in_progress'

        and: 'the trigger-loop pause hands back the two unrun triggers AND every action'
        result.addTriggersRemaining instanceof List
        result.addTriggersRemaining.size() == 2
        result.addTriggersRemaining[0].state == 'off'
        result.addActionsRemaining instanceof List
        result.addActionsRemaining.size() == 2

        and: 'item 0 failed, so the pause surfaces success:false + partial rather than masking it'
        result.success == false
        result.partial == true

        and: 'the deferred trailing updateRule did NOT fire on the pause'
        !clicks.contains('updateRule')
        !JsonOutput.toJson(result).contains('__reqT0')
    }

    // A pause fired AFTER a committed item failed: the batch still stops on the budget (to
    // protect the response from a relay drop) but the failure is surfaced in the outer
    // success/partial, not masked as a clean in_progress success.
    def "a bulk pause after a failed item surfaces success:false + partial, not a clean success"() {
        given:
        def actionCalls = []
        def clicks = []
        stateMap.lastBackupTimestamp = FIXED_NOW
        settingsMap.enableWrite = true
        script.metaClass._rmBackupRuleSnapshot = { Integer id, String reason -> [key: 'snap'] }
        script.metaClass._rmCheckRuleHealth = { Integer id -> [ok: true] }
        script.metaClass._rmAddAction = { Integer id, Map spec, boolean batch = false, Set validRuleIds = null ->
            actionCalls << spec
            (actionCalls.size() == 1) ? [success: false, error: 'bad cap'] : [success: true]
        }
        script.metaClass._rmClickAppButton = { Integer aId, String name, String stateAttr = null,
                                               String pageName = null, Map cache = null -> clicks << name }
        script.metaClass._timeBudgetExceeded = { Long t0 -> true }

        when: 'the first action fails, then the budget pauses before the second'
        def result = script._applyNativeAppEdit([appId: 1, confirm: true, __reqT0: 2000L, addActions: [
            [capability: 'switch', action: 'on', deviceIds: [8]],
            [capability: 'switch', action: 'off', deviceIds: [9]],
        ]])

        then: 'the batch STILL paused (protecting the relay) even though item 0 failed'
        actionCalls.size() == 1
        result.status == 'in_progress'
        result.addActionsRemaining.size() == 1

        and: 'the failure is bubbled up, NOT masked as a clean success'
        result.success == false
        result.partial == true
        result.repairHints instanceof List && !result.repairHints.isEmpty()

        and: 'the deferred updateRule did not fire'
        !clicks.contains('updateRule')
    }

    // Pin the trigger-pause return shape directly on _bulkPauseResult too -- a
    // deterministic unit on the shape the running loop above relies on.
    def "_bulkPauseResult builds the trigger-loop carry-forward shape (remaining triggers + all actions)"() {
        when: 'a trigger-loop pause: one trigger committed, two remain, no action has run yet'
        def result = script._bulkPauseResult(7, [key: 'snap'],
            [[success: true, capability: 'Switch']],                                        // triggerResults so far
            [],                                                                             // actionResults so far
            [[capability: 'Switch', state: 'off', __reqT0: 9L], [capability: 'Motion']],   // remaining triggers
            [[capability: 'switch', action: 'on'], [capability: 'switch', action: 'off']]) // ALL actions carry forward

        then: 'success-shaped in_progress envelope with the committed counts'
        result.success == true
        result.status == 'in_progress'
        result.appId == 7
        result.backup == [key: 'snap']
        result.triggersCommitted == 1
        result.actionsCommitted == 0

        and: 'the remaining triggers AND all actions ride back, with the internal __reqT0 stripped'
        result.addTriggersRemaining.size() == 2
        result.addTriggersRemaining[0].state == 'off'
        !result.addTriggersRemaining[0].containsKey('__reqT0')
        result.addActionsRemaining.size() == 2

        and: 'resume guidance is present and no internal clock leaks into the JSON'
        result.resume?.note instanceof String && !result.resume.note.trim().isEmpty()
        !JsonOutput.toJson(result).contains('__reqT0')
    }

    def "a bulk batch within budget applies every item and fires the trailing updateRule once"() {
        given:
        def triggerCalls = []
        def actionCalls = []
        def clicks = []
        installBulkStubs(triggerCalls, actionCalls, clicks, false)

        when:
        def result = script._applyNativeAppEdit([appId: 1, confirm: true, addActions: [
            [capability: 'switch', action: 'on', deviceIds: [8]],
            [capability: 'switch', action: 'off', deviceIds: [9]],
            [capability: 'switch', action: 'on', deviceIds: [10]],
        ]])

        then: 'all three actions ran, the trailing updateRule fired, and no in_progress shape appears'
        actionCalls.size() == 3
        clicks.contains('updateRule')
        result.status != 'in_progress'
        result.addActionsRemaining == null
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
        given: 'gateway mode pinned -- this test exercises the gateway re-injection hop'
        settingsMap.useGateways = true
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
        given: 'gateway mode pinned -- the envelope rides the same gateway hop'
        settingsMap.useGateways = true
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

    // ---------------- health-probe shedding + unreadable tolerance (issue #351) ----------------
    // The trailing health probe is advisory freight on an already-committed op: it
    // must shed once the budget is spent (returning under the transport ceiling
    // beats 504ing on diagnostics), and a probe that could not be READ (transient
    // fetch failure -- no evidence of breakage) must never fail committed work.

    def "_rmWalkStepHealth sheds the probe once the budget is spent"() {
        given:
        def probes = 0
        script.metaClass._rmCheckRuleHealth = { Integer id -> probes++; [ok: true] }
        script.metaClass._timeBudgetExceeded = { Long t0 -> true }

        when:
        def h = script._rmWalkStepHealth(7, 1000L)

        then: 'no probe fetch; an ok-shaped skipped verdict pointing at hub_get_rule_health'
        probes == 0
        h.ok == true
        h.skipped == true
        h.note.contains('hub_get_rule_health')
    }

    def "_rmWalkStepHealth probes normally within budget"() {
        given:
        def probes = 0
        script.metaClass._rmCheckRuleHealth = { Integer id -> probes++; [ok: true] }
        script.metaClass._timeBudgetExceeded = { Long t0 -> false }

        expect:
        script._rmWalkStepHealth(7, 1000L).ok == true
        probes == 1
    }

    def "an unreadable final health probe does not fail a drive whose every step committed"() {
        given:
        def walkCalls = []
        script.metaClass._rmWalkStep = { Integer id, Map step ->
            walkCalls << new LinkedHashMap(step); [page: step.page, success: true]
        }
        script.metaClass._rmCheckRuleHealth = { Integer id ->
            [ok: false, unreadable: true, source: 'none', broken: null,
             issues: ['health check failed: status code: 404, reason phrase: Not Found']]
        }
        script.metaClass._timeBudgetExceeded = { Long t0 -> false }

        when:
        def result = script._rmDriveWalkSteps(1, [operation: 'drive', steps: [
            [page: 'p1', operation: 'introspect'],
        ]])

        then: 'committed work stands; the caller is told to re-verify, not to restore'
        result.success == true
        result.error == null
        result.health.unreadable == true
        result.repairHints.any { it.contains('hub_get_rule_health') }
    }

    def "a checked-and-broken final health verdict still fails the drive (unreadable tolerance is not a bypass)"() {
        given:
        def walkCalls = []
        installWalkerStubs(walkCalls, false)
        script.metaClass._rmCheckRuleHealth = { Integer id ->
            [ok: false, unreadable: false, broken: true, issues: ['ruleBuilderJson reports broken:true']]
        }

        when:
        def result = script._rmDriveWalkSteps(1, [operation: 'drive', steps: [
            [page: 'p1', operation: 'introspect'],
        ]])

        then:
        result.success == false
        result.error.contains('unhealthy')
    }

    // Real single-op _rmWalkStep, no walker stubs: the gate and clock wiring inside the
    // op itself (the exact shape of the live-hub 504/poison failure). introspect's only
    // hub touches are the page configure/json and statusJson reads.
    private void seedWalkStepPage(int id) {
        hubGet.register("/installedapp/configure/json/${id}/mainPage".toString()) {
            JsonOutput.toJson([
                app: [id: id, label: 'BAT-WS', name: 'Rule-5.1', installed: true,
                      appType: [name: 'Rule-5.1', namespace: 'hubitat']],
                configPage: [name: 'mainPage', title: 'Edit Rule', install: true, error: null,
                             sections: [[title: '', input: [], paragraphs: []]]],
                settings: [:], childApps: []
            ])
        }
        hubGet.register("/installedapp/statusJson/${id}".toString()) {
            JsonOutput.toJson([installedApp: [id: id], appSettings: [],
                               eventSubscriptions: [], scheduledJobs: [], appState: [:]])
        }
    }

    def "a REAL single walkStep op tolerates an unreadable health probe -- committed work stands"() {
        given:
        settingsMap.enableRead = true
        seedWalkStepPage(100)
        script.metaClass._rmCheckRuleHealth = { Integer id ->
            [ok: false, unreadable: true, source: 'none', broken: null,
             issues: ['health check failed: status code: 404, reason phrase: Not Found']]
        }

        when:
        def result = script._rmWalkStep(100, [page: 'mainPage', operation: 'introspect'])

        then:
        result.success == true
        result.health.unreadable == true
        result.repairHints.any { it.contains('hub_get_rule_health') }
    }

    def "a REAL single walkStep op still fails on a checked-and-broken health verdict"() {
        given:
        settingsMap.enableRead = true
        seedWalkStepPage(100)
        script.metaClass._rmCheckRuleHealth = { Integer id ->
            [ok: false, unreadable: false, broken: true, issues: ['ruleBuilderJson reports broken:true']]
        }

        when:
        def result = script._rmWalkStep(100, [page: 'mainPage', operation: 'introspect'])

        then:
        result.success == false
    }

    def "a REAL single walkStep op sheds its trailing health probe once the budget is spent (clock read from the spec)"() {
        given:
        settingsMap.enableRead = true
        seedWalkStepPage(100)
        def probes = 0
        script.metaClass._rmCheckRuleHealth = { Integer id -> probes++; [ok: true] }
        script.metaClass._timeBudgetExceeded = { Long t0 -> t0 != null }

        when: 'clock present -- probe shed'
        def shed = script._rmWalkStep(100, [page: 'mainPage', operation: 'introspect', __reqT0: 1000L])

        and: 'no clock -- probe runs'
        def probed = script._rmWalkStep(100, [page: 'mainPage', operation: 'introspect'])

        then:
        shed.success == true
        shed.health.skipped == true
        probes == 1        // only the un-clocked call probed
        probed.health.skipped == null
    }

    def "the budget clock is threaded into a SINGLE walkStep op (not just drive)"() {
        given:
        stateMap.lastBackupTimestamp = FIXED_NOW
        settingsMap.enableWrite = true
        script.metaClass._rmBackupRuleSnapshot = { Integer id, String reason -> [key: 'snap'] }
        def captured = [:]
        script.metaClass._rmWalkStep = { Integer id, Map spec -> captured.spec = spec; [page: spec.page, success: true, operation: spec.operation] }

        when:
        script._applyNativeAppEdit([appId: 1, confirm: true, __reqT0: 1000L,
                                    walkStep: [operation: 'introspect', page: 'mainPage']])

        then:
        captured.spec.__reqT0 == 1000L
    }

    def "the drive threads the budget clock into each per-step spec"() {
        given:
        def walkCalls = []
        script.metaClass._rmWalkStep = { Integer id, Map step ->
            walkCalls << new LinkedHashMap(step); [page: step.page, success: true]
        }
        script.metaClass._rmCheckRuleHealth = { Integer id -> [ok: true] }
        script.metaClass._timeBudgetExceeded = { Long t0 -> false }

        when:
        def result = script._rmDriveWalkSteps(1, [operation: 'drive', __reqT0: 1000L, steps: [
            [page: 'p1', operation: 'introspect'],
            [page: 'p2', operation: 'introspect'],
        ]])

        then: 'every step carries the clock; the result echoes none of it'
        walkCalls.every { it.__reqT0 == 1000L }
        !JsonOutput.toJson(result).contains('__reqT0')
    }

    def "a drive paused right after an intermediate done step does NOT fire the trailing mainPage Done"() {
        given: 'a walker that pauses after step 1 (a done op), plus a recorder on the finalizer'
        stateMap.lastBackupTimestamp = FIXED_NOW
        settingsMap.enableWrite = true
        script.metaClass._rmBackupRuleSnapshot = { Integer id, String reason -> [key: 'snap'] }
        script.metaClass._rmCheckRuleHealth = { Integer id -> [ok: true] }
        script.metaClass._rmWalkStep = { Integer id, Map spec ->
            spec.operation == 'drive' ? script._rmDriveWalkSteps(id, spec) : [page: spec.page, success: true]
        }
        script.metaClass._timeBudgetExceeded = { Long t0 -> t0 != null }
        def doneClicks = 0
        script.metaClass._rmSubmitMainPageDone = { Integer id -> doneClicks++; [done: true] }

        when: 'the drive pauses at step 2, with lastStepOperation == done from step 1'
        def result = script._applyNativeAppEdit([appId: 1, confirm: true, __reqT0: 1000L, walkStep: [
            operation: 'drive', steps: [
                [page: 'p1', operation: 'done'],
                [page: 'p2', operation: 'introspect'],
            ]]])

        then: 'paused mid-flow: the update lifecycle must NOT run on the half-configured app'
        result.status == 'in_progress'
        result.lastStepOperation == 'done'
        result.stepsRemaining.size() == 1
        doneClicks == 0

        and: 'no self-contradictory failure markers ride the pause envelope'
        result.success == true
        result.mainPageDoneFailed == null
    }

    def "the budget clock reaches _applyNativeAppEdit on a FLAT (no-gateway) dispatch"() {
        given: 'flat mode pinned -- covers the direct handleToolsCall injection with no gateway hop'
        settingsMap.useGateways = false
        settingsMap.enableWrite = true
        def captured = [:]
        script.metaClass._applyNativeAppEdit = { Map a -> captured.edit = a; [success: true, appId: 1] }

        when:
        def response = mcpDriver.callTool('hub_set_rule', [
            appId: 1, confirm: true,
            patches: [[addAction: [a: 1]], [addAction: [a: 2]]]])

        then:
        response.error == null
        captured.edit.__reqT0 instanceof Long
        captured.edit.patches.size() == 2
    }
}
