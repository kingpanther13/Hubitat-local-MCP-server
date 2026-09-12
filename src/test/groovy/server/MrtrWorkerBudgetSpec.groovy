package server

import groovy.json.JsonOutput
import java.util.concurrent.atomic.AtomicLong
import spock.lang.Unroll
import support.ToolSpecBase

class MrtrWorkerBudgetSpec extends ToolSpecBase {
    private final AtomicLong clock = new AtomicLong(1234567890000L)
    private final List<Map> actions = []
    private final List<String> clicks = []
    private Closure itemElapsed = { int index -> index == 1 ? 120001L : 10L }

    def setup() {
        NOW_OVERRIDE.set({ -> clock.get() })
        settingsMap.enableWrite = true
        settingsMap.enableRead = true
        settingsMap.maxConcurrentWrites = 1
        settingsMap.relayBudgetMs = 0
        settingsMap.lanBudgetMs = 0
        stateMap.lastBackupTimestamp = clock.get()
        script.metaClass._rmBackupRuleSnapshot = { Integer id, String reason -> [key: 'baseline'] }
        script.metaClass._rmCheckRuleHealth = { Integer id -> [ok: true] }
        script.metaClass._rmAddAction = { Integer id, Map spec, boolean batch = false, Set validIds = null ->
            actions << new LinkedHashMap(spec)
            clock.addAndGet(itemElapsed.call(actions.size()) as Long)
            [success: true, deviceIds: spec.deviceIds]
        }
        script.metaClass._rmClickAppButton = { Integer id, String name, String attr = null,
                                               String page = null, Map cache = null -> clicks << name }
        RUN_IN_MILLIS_OVERRIDE.set({ List call ->
            runInMillisCalls << call
            script.runMrtrSlice(new LinkedHashMap(call[2].data as Map))
        })
    }

    private Map modernCall(String tool, Map args, String stateId = null) {
        def params = [name: tool, arguments: args]
        if (stateId != null) params.requestState = stateId
        mcpDriver.pushHeaders(['MCP-Protocol-Version': '2026-07-28',
                              'Mcp-Method': 'tools/call', 'Mcp-Name': tool])
        mcpDriver.pushBody([jsonrpc: '2.0', id: ++mcpDriver.lastSentId,
                            method: 'tools/call', params: params])
        script.handleMcpRequest()
        return mcpDriver.parseResponseJson() as Map
    }

    private List<Map> actionSpecs(int count = 3) {
        (1..count).collect { [capability: 'switch', action: 'on', deviceIds: [it + 10]] }
    }

    private Map leafArgs(Map args, boolean gateway) { gateway ? args.args as Map : args }

    private Object workerClock() {
        def field = script.getClass().declaredFields.find { it.name == 'mrtrWorkerSliceStartedAt' }
        if (field == null) return null
        field.accessible = true
        return field.get(script)
    }

    private void assertPause(Map response, String stateId, String leaf) {
        assert response.error == null
        assert response.result.findAll { key, value -> key != '_meta' } ==
            [resultType: 'input_required', requestState: stateId]
        assert atomicStateMap.mrtrRequests[stateId].status == 'active'
        assert script._activeWrites()*.tool == [leaf]
        assert !clicks.contains('updateRule')
    }

    @Unroll
    def "#leaf bulk checkpoints through #route with #transport request budget #requestBudget"() {
        given:
        settingsMap.useGateways = gateway
        settingsMap.relayBudgetMs = requestBudget
        settingsMap.lanBudgetMs = requestBudget
        script.metaClass._isCloudRequest = { -> cloud }
        String outer = gateway ? (leaf == 'hub_set_rule' ? 'hub_manage_rule_machine' :
            'hub_manage_native_rules_and_apps') : leaf
        def specs = actionSpecs()
        def edit = [appId: 1, confirm: true, addActions: specs]
        def args = gateway ? [tool: leaf, args: edit] : edit

        when: 'the first request runs the first action and pauses at the worker target'
        def paused = modernCall(outer, args)
        String stateId = paused.result.requestState
        Map record = atomicStateMap.mrtrRequests[stateId] as Map

        then: 'the first completed action crosses the worker target, independent of transport'
        actions == specs.take(1)
        assertPause(paused, stateId, leaf)
        runInMillisCalls.size() == 1
        record.rounds == 1
        record.generation == 1
        !record.containsKey('claimId')
        record.expiresAt == clock.get() + 180000L
        leafArgs(record.nextArguments as Map, gateway).addActions == specs.drop(1)
        leafArgs(record.nextArguments as Map, gateway).addTriggers == []
        record.aggregate.actions*.deviceIds == [[11]]
        workerClock() == null

        when: 'a duplicate callback is harmless; a later client request starts a fresh worker clock'
        script.runMrtrSlice(new LinkedHashMap(runInMillisCalls[0][2].data as Map))
        clock.addAndGet(60000L)
        def complete = modernCall(outer, args, stateId)
        def terminal = mcpDriver.parseInner(complete)
        def replay = modernCall(outer, args, stateId)

        then: 'both short remaining items fit in the new slice, with one finalization and ledger'
        complete.result.resultType == 'complete'
        terminal.success == true
        terminal.actions*.deviceIds == [[11], [12], [13]]
        terminal.mrtr.rounds == 2
        actions == specs
        clicks.count('updateRule') == 1
        runInMillisCalls.size() == 2
        mcpDriver.parseInner(replay) == terminal
        script._activeWrites().isEmpty()
        workerClock() == null
        !JsonOutput.toJson([actions, record.nextArguments, terminal]).contains('__reqT0')

        where:
        leaf                 | gateway | cloud | requestBudget | route     | transport
        'hub_set_rule'       | false   | false | 0             | 'flat'    | 'LAN'
        'hub_set_rule'       | true    | true  | 1             | 'gateway' | 'cloud'
        'hub_set_native_app' | false   | true  | 0             | 'flat'    | 'cloud'
        'hub_set_native_app' | true    | false | 1             | 'gateway' | 'LAN'
    }

    @Unroll
    def "nested #operation preserves its unprocessed inner items and following patch"() {
        given:
        settingsMap.useGateways = true
        def triggers = []
        if (operation == 'addTriggers') installTriggerWizard(triggers)
        def specs = operation == 'addActions' ? actionSpecs() :
            (1..3).collect { [capability: 'Switch', state: it == 2 ? 'off' : 'on'] }
        def tail = [addAction: [capability: 'switch', action: 'off', deviceIds: [99]]]
        def patches = [[(operation): specs], tail]
        def args = [tool: 'hub_set_rule', args: [appId: 1, confirm: true, patches: patches]]

        when:
        def paused = modernCall('hub_manage_rule_machine', args)
        String stateId = paused.result.requestState
        Map record = atomicStateMap.mrtrRequests[stateId] as Map

        then:
        (operation == 'addActions' ? actions.size() : triggers.size()) == 1
        assertPause(paused, stateId, 'hub_set_rule')
        record.nextArguments.args.patches == [[(operation): specs.drop(1)], tail]
        record.aggregate.patchResults.size() == 1
        record.aggregate.patchResults[0].results.size() == 1
        record.aggregate.patchResults[0].results[0].success == true
        runInMillisCalls.size() == 1

        when:
        clock.addAndGet(60000L)
        def complete = modernCall('hub_manage_rule_machine', args, stateId)
        def terminal = mcpDriver.parseInner(complete)
        def replay = modernCall('hub_manage_rule_machine', args, stateId)

        then:
        complete.result.resultType == 'complete'
        terminal.success == true
        terminal.partial != true
        terminal.patchResults.every { it.partial != true }
        terminal.patchResults.findAll { it.op == operation }.collectMany { it.results }.size() == 3
        terminal.patchResults.count { it.op == 'addAction' } == 1
        actions*.deviceIds == (operation == 'addActions' ? [[11], [12], [13], [99]] : [[99]])
        triggers.size() == (operation == 'addTriggers' ? 3 : 0)
        clicks.count('updateRule') == 1
        runInMillisCalls.size() == 2
        mcpDriver.parseInner(replay) == terminal
        !JsonOutput.toJson([actions, record.nextArguments, terminal]).contains('__reqT0')

        where:
        operation << ['addActions', 'addTriggers']
    }

    @Unroll
    def "continued inner action batch retains its #outcome first-item outcome"() {
        given:
        settingsMap.useGateways = false
        script.metaClass._rmAddAction = { Integer id, Map spec, boolean batch = false, Set validIds = null ->
            actions << new LinkedHashMap(spec)
            clock.addAndGet(actions.size() == 1 ? 120001L : 10L)
            [success: true, deviceIds: spec.deviceIds] + (actions.size() == 1 ? firstResult : [:])
        }
        def specs = actionSpecs()
        def args = [appId: 1, confirm: true, patches: [[addActions: specs]]]

        when:
        def paused = modernCall('hub_set_rule', args)
        String stateId = paused.result.requestState

        then:
        assertPause(paused, stateId, 'hub_set_rule')
        actions == specs.take(1)

        when:
        def complete = modernCall('hub_set_rule', args, stateId)
        def terminal = mcpDriver.parseInner(complete)
        def rows = terminal.patchResults.collectMany { it.results }

        then:
        complete.result.resultType == 'complete'
        terminal.success == false
        terminal.partial == true
        rows*.deviceIds == [[11], [12], [13]]
        firstResult.every { key, value -> rows[0][key] == value }
        rows.drop(1).every { it.success == true && it.partial != true }
        actions == specs
        clicks.count('updateRule') == 1
        runInMillisCalls.size() == 2
        mcpDriver.parseInner(modernCall('hub_set_rule', args, stateId)) == terminal

        where:
        outcome   | firstResult
        'failed'  | [success: false, error: 'action refused']
        'partial' | [success: true, partial: true, repairHints: ['one requested setting did not land']]
    }

    def "worker clears its clock when the native backup throws before an edit"() {
        given:
        settingsMap.useGateways = false
        Long duringBackup = null
        script.metaClass._rmBackupRuleSnapshot = { Integer id, String reason ->
            duringBackup = workerClock() as Long
            throw new IllegalStateException('backup storage unavailable')
        }
        def args = [appId: 1, confirm: true, addActions: actionSpecs()]

        when:
        def failed = modernCall('hub_set_rule', args)

        then:
        failed.result.resultType == 'complete'
        failed.result.isError == true
        mcpDriver.parseInner(failed).error.contains('backup storage unavailable')
        duringBackup == clock.get()
        workerClock() == null
        actions.isEmpty()
        script._activeWrites().isEmpty()
    }

    def "a resumed backup failure retains completed work without claiming the remaining slice is safe to repeat"() {
        given:
        settingsMap.useGateways = false
        settingsMap.backupEveryRuleWrite = true
        int backups = 0
        script.metaClass._rmBackupRuleSnapshot = { Integer id, String reason ->
            backups++
            if (backups > 1) throw new IllegalStateException('backup storage unavailable')
            [key: 'baseline']
        }
        def specs = actionSpecs()
        def args = [appId: 1, confirm: true, addActions: specs]

        when:
        def paused = modernCall('hub_set_rule', args)
        String stateId = paused.result.requestState

        then:
        assertPause(paused, stateId, 'hub_set_rule')
        actions == specs.take(1)
        backups == 1

        when: 'the next worker fails before it can run another item'
        def failed = modernCall('hub_set_rule', args, stateId)
        def terminal = mcpDriver.parseInner(failed)
        def replay = modernCall('hub_set_rule', args, stateId)

        then:
        failed.result.resultType == 'complete'
        failed.result.isError == true
        terminal.success == false
        terminal.error.contains('backup storage unavailable')
        terminal.aggregate.kind == 'bulk_edit'
        terminal.aggregate.actions*.deviceIds == [[11]]
        terminal.aggregate.actions.every { it.success == true }
        terminal.note.toLowerCase().contains('inspect')
        terminal.note.toLowerCase().contains('slice')
        !terminal.containsKey('addActionsRemaining')
        actions == specs.take(1)
        backups == 2
        !clicks.contains('updateRule')
        runInMillisCalls.size() == 2
        script._activeWrites().isEmpty()
        workerClock() == null
        mcpDriver.parseInner(replay) == terminal
    }

    // _rmAddTrigger is private; exercise its real wizard using schema reads and primitive writes.
    private void installTriggerWizard(List triggers) {
        int editorIndex = 0
        Map written = [:]
        Closure config = { String page ->
            JsonOutput.toJson([app: [id: 1, installed: true, name: 'Rule-5.1'],
                configPage: [name: page, error: null, sections: [[input: [
                    [name: "tCapab${editorIndex}".toString(), type: 'enum', options: ['Switch']],
                    [name: "tstate${editorIndex}".toString(), type: 'enum', options: ['on', 'off']],
                    [name: "isCondTrig.${editorIndex}".toString(), type: 'bool']]]]], settings: [:]])
        }
        hubGet.register('/installedapp/configure/json/1/selectTriggers') { config('selectTriggers') }
        hubGet.register('/installedapp/configure/json/1/mainPage') { config('mainPage') }
        hubGet.register('/installedapp/statusJson/1') {
            JsonOutput.toJson([appSettings: (0..<triggers.size())
                .collect { [name: "tCapab${it + 1}".toString(), value: 'Switch'] }])
        }
        script.metaClass._rmWriteSettingOnPage = { Integer id, String page, String key, Object value,
                                                  List applied, String hint = null, List skipped = null,
                                                  Map cache = null ->
            written[key] = value
            applied << [key: key, value: value]
        }
        script.metaClass._rmClickAppButton = { Integer id, String name, String attr = null,
                                               String page = null, Map cache = null ->
            clicks << name
            if (attr == 'moreCond') editorIndex++
        }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer timeout = 420 ->
            if (body.currentPage == 'selectTriggers' && body.containsKey('_action_href_name|mainPage|0')) {
                triggers << [state: written["tstate${editorIndex}".toString()]]
                clock.addAndGet(triggers.size() == 1 ? 120001L : 10L)
            }
            [status: 200, data: config('mainPage')]
        }
        // Trigger commits consume the long first item; the later action patch is short.
        itemElapsed = { int index -> 10L }
    }

    @Unroll
    def "an over-budget #leadingOp before replacement keeps the original batch and duplicate fence"() {
        given:
        settingsMap.useGateways = false
        def triggers = []
        if (leadingOp == 'addTriggers') installTriggerWizard(triggers)
        hubGet.register('/installedapp/configure/json/1/STPage') {
            JsonOutput.toJson([app: [id: 1],
                configPage: [name: 'STPage', sections: [[input: [[name: 'doneST', type: 'button']]]]]])
        }
        hubGet.register('/device/fullJson/8') { '{"id":"8","name":"Switch"}' }
        def replacement = [replaceRequiredExpression:
            [conditions: [[capability: 'Switch', deviceIds: [8], state: 'on']]]]
        def leading = leadingOp == 'addAction' ? [addAction: actionSpecs(1)[0]] :
            (leadingOp == 'addActions' ? [addActions: actionSpecs()] :
                [addTriggers: (1..3).collect { [capability: 'Switch', state: 'on'] }])
        def args = [appId: 1, confirm: true, patches: [leading, replacement, replacement]]

        when:
        def complete = modernCall('hub_set_rule', args)
        def terminal = mcpDriver.parseInner(complete)
        String stateId = (atomicStateMap.mrtrRequests as Map).keySet().first()
        def replay = modernCall('hub_set_rule', args, stateId)

        then: 'the failed first replacement still owns the one-replacement fence for this batch'
        complete.result.resultType == 'complete'
        def entries = (terminal.patchResults ?: terminal.patches).findAll { it.op == 'replaceRequiredExpression' }
        entries.size() == 2
        entries[0].requiredExpressionMissing == true
        entries[1].success == false
        entries[1].error.contains('only one replaceRequiredExpression is valid')
        terminal.success == false
        terminal.partial == true
        actions.size() == (leadingOp == 'addAction' ? 1 : leadingOp == 'addActions' ? 3 : 0)
        triggers.size() == (leadingOp == 'addTriggers' ? 3 : 0)
        runInMillisCalls.size() == 1
        clicks.count('updateRule') == 1
        !clicks.contains('cancelST')
        script._activeWrites().isEmpty()
        mcpDriver.parseInner(replay) == terminal

        where:
        leadingOp << ['addAction', 'addActions', 'addTriggers']
    }

    @Unroll
    def "walk drive retains original step numbering across slices (second step fails=#failSecondStep)"() {
        given:
        settingsMap.useGateways = false
        def walked = []
        int finalized = 0
        script.metaClass._rmWalkStep = { Integer id, Map spec ->
            if (spec.operation == 'drive') return script._rmDriveWalkSteps(id, spec)
            walked << new LinkedHashMap(spec)
            clock.addAndGet(walked.size() == 1 ? 120001L : 10L)
            if (failSecondStep && walked.size() == 2) {
                return [success: false, page: spec.page, error: 'second requested step refused']
            }
            [success: true, page: spec.page]
        }
        hubGet.register('/installedapp/configure/json/1/mainPage') {
            JsonOutput.toJson([app: [id: 1], configPage: [name: 'mainPage', sections: []]])
        }
        hubGet.register('/installedapp/statusJson/1') { JsonOutput.toJson([appSettings: []]) }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer timeout = 420 ->
            if (path == '/installedapp/update/json' && body._action_update == 'Done') finalized++
            [status: 200, data: '{}']
        }
        def steps = [[page: 'selectActions', operation: 'done'],
                     [operation: 'introspect'], [operation: 'done']]
        def args = [appId: 1, confirm: true, walkStep: [operation: 'drive', steps: steps]]

        when:
        def paused = modernCall('hub_set_native_app', args)
        String stateId = paused.result.requestState
        Map record = atomicStateMap.mrtrRequests[stateId] as Map

        then:
        walked.size() == 1
        assertPause(paused, stateId, 'hub_set_native_app')
        record.nextArguments.walkStep.steps == steps.drop(1)
        record.nextArguments.walkStep.page == 'selectActions'
        finalized == 0

        when:
        clock.addAndGet(60000L)
        def complete = modernCall('hub_set_native_app', args, stateId)
        def terminal = mcpDriver.parseInner(complete)
        def replay = modernCall('hub_set_native_app', args, stateId)

        then:
        complete.result.resultType == 'complete'
        terminal.success == !failSecondStep
        terminal.stepsRequested == 3
        terminal.steps.size() == (failSecondStep ? 2 : 3)
        terminal.stepsRun == (failSecondStep ? 2 : 3)
        terminal.steps*.step == (failSecondStep ? [1, 2] : [1, 2, 3])
        walked*.page == (failSecondStep ? ['selectActions', 'selectActions'] :
            ['selectActions', 'selectActions', 'selectActions'])
        finalized == (failSecondStep ? 0 : 1)
        !failSecondStep || (terminal.steps[1].success == false &&
            terminal.steps[1].error == 'second requested step refused')
        !failSecondStep || terminal.error.contains('step 2')
        !failSecondStep || terminal.repairHints.any { it.contains('steps[1]') }
        runInMillisCalls.size() == 2
        mcpDriver.parseInner(replay) == terminal
        !JsonOutput.toJson(walked).contains('__reqT0')

        where:
        failSecondStep << [false, true]
    }

    @Unroll
    def "the eight-slice #shape cap retains exact remaining work and deferred finalization guidance"() {
        given:
        settingsMap.useGateways = false
        itemElapsed = { int index -> 120001L }
        // Force the existing safe checkpoint too, so this isolates cap data loss on older code.
        script.metaClass._timeBudgetExceeded = { Long requestT0 -> true }
        def specs = actionSpecs(10)
        def edit = shape == 'bulk' ? [addActions: specs] : [patches: specs.collect { [addAction: it] }]
        def args = [appId: 1, confirm: true] + edit

        when: 'the first request is slice one; seven continuations reach the cap'
        Map response = modernCall('hub_set_rule', args)
        String stateId = response.result.requestState
        for (int slice = 2; slice <= 8; slice++) {
            assert response.result.resultType == 'input_required'
            response = modernCall('hub_set_rule', args, stateId)
        }
        def terminal = mcpDriver.parseInner(response)
        def replay = modernCall('hub_set_rule', args, stateId)

        then:
        response.result.resultType == 'complete'
        response.result.isError == true
        terminal.status == 'continuation_limit'
        terminal.mrtr.rounds == 8
        actions == specs.take(8)
        runInMillisCalls.size() == 8
        clicks.count('updateRule') == 0
        terminal[remainingField] == (shape == 'bulk' ? specs.drop(8) : specs.drop(8).collect { [addAction: it] })
        terminal.resume.note.contains('updateRule')
        terminal.resume.note.contains('remaining')
        (shape == 'bulk' ? terminal.aggregate.actions : terminal.aggregate.patchResults).size() == 8
        mcpDriver.parseInner(replay) == terminal
        script._activeWrites().isEmpty()

        when: 'an explicit follow-up submits only the exact unprocessed work'
        def remainingEdit = [(shape == 'bulk' ? 'addActions' : 'patches'): terminal[remainingField]]
        def followup = [appId: 1, confirm: true] + remainingEdit
        String nextId = modernCall('hub_set_rule', followup).result.requestState
        def finished = modernCall('hub_set_rule', followup, nextId)

        then:
        finished.result.resultType == 'complete'
        mcpDriver.parseInner(finished).success == true
        actions == specs
        clicks.count('updateRule') == 1

        where:
        shape     | remainingField
        'bulk'    | 'addActionsRemaining'
        'patches' | 'patchesRemaining'
    }
}
