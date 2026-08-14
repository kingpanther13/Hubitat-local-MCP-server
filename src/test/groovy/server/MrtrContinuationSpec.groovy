package server

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

import support.ToolSpecBase

/**
 * Dispatch-level contract for MCP 2026-07-28 request-to-request continuation.
 *
 * A modern slow write gets a no-mutation preflight InputRequiredResult first.
 * The client echoes requestState with the unchanged original arguments. Each
 * resumed request runs one bounded slice; a terminal response is retained under
 * the same state briefly so a dropped final HTTP response can be replayed without
 * running the write again.
 */
class MrtrContinuationSpec extends ToolSpecBase {

    private List<Map> race(int count, Closure<Map> action) {
        def ready = new CountDownLatch(count)
        def start = new CountDownLatch(1)
        def results = java.util.Collections.synchronizedList([])
        def failures = java.util.Collections.synchronizedList([])
        def threads = (0..<count).collect { int index ->
            Thread.start("mrtr-race-${index}") {
                ready.countDown()
                start.await()
                try { results << action.call(index) }
                catch (Throwable t) { failures << t }
            }
        }
        assert ready.await(5, TimeUnit.SECONDS)
        start.countDown()
        threads*.join(5000)
        assert !threads.any { it.alive }
        assert failures.isEmpty()
        return results as List<Map>
    }

    private Map modernCall(String toolName, Map args, String requestState = null) {
        int id = ++mcpDriver.lastSentId
        def params = [name: toolName, arguments: args]
        if (requestState != null) params.requestState = requestState
        mcpDriver.pushHeaders([
            'MCP-Protocol-Version': '2026-07-28',
            'Mcp-Method': 'tools/call',
            'Mcp-Name': toolName
        ])
        mcpDriver.pushBody([jsonrpc: '2.0', id: id, method: 'tools/call', params: params])
        script.handleMcpRequest()
        return mcpDriver.parseResponseJson() as Map
    }

    private Map directCall(Object target, int id, String toolName, Map args,
                           String requestState = null) {
        def params = [name: toolName, arguments: args]
        if (requestState != null) params.requestState = requestState
        Map response = target.handleToolsCall([jsonrpc: '2.0', id: id,
            method: 'tools/call', params: params]) as Map
        return mcpDriver.decodeToolCallResponse(response)
    }

    private String nativeRuleConfig(int appId, String label, int parentAppId) {
        return groovy.json.JsonOutput.toJson([
            app: [id: appId, name: 'Rule-5.1', label: label, trueLabel: label,
                  installed: true, parentAppId: parentAppId,
                  appType: [name: 'Rule-5.1', namespace: 'hubitat']],
            configPage: [name: 'mainPage', title: 'Edit Rule', install: true,
                         error: null, sections: [[title: '', input: []]]],
            settings: [:], childApps: []
        ])
    }

    private String nativeParentConfig(int parentAppId, List<Map> children) {
        return groovy.json.JsonOutput.toJson([
            app: [id: parentAppId, label: 'Rule Machine'],
            configPage: [name: 'mainPage', title: 'RM', install: true,
                         error: null, sections: []],
            settings: [:], childApps: children
        ])
    }

    private String clonerPageState(String action, int index) {
        return groovy.json.JsonOutput.toJson([configPage: [name: 'main', sections: [
            [input: [], body: [[description:
                "<button name='_action_href_name|${action}|${index}'>Go</button>".toString()]]]
        ]]])
    }

    private Map decodeForm(String encoded) {
        if (!encoded) return [:]
        Map decoded = [:]
        encoded.split('&').each { pair ->
            int equalsAt = pair.indexOf('=')
            String key = equalsAt < 0 ? pair : pair.substring(0, equalsAt)
            String value = equalsAt < 0 ? '' : pair.substring(equalsAt + 1)
            decoded[java.net.URLDecoder.decode(key, 'UTF-8')] =
                java.net.URLDecoder.decode(value, 'UTF-8')
        }
        return decoded
    }

    def "pure native-write preflight refuses #caseName before reserving MRTR state"() {
        given:
        settingsMap.enableWrite = true
        settingsMap.useGateways = true
        stateMap.lastBackupTimestamp = 1234567890000L
        def backupCalls = []
        def posts = []
        script.metaClass._rmBackupRuleSnapshot = { Integer id, String reason ->
            backupCalls << [id: id, reason: reason]
            [backupKey: 'must-not-exist']
        }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer timeout = 420 ->
            posts << [path: path, body: new LinkedHashMap(body)]
            [status: 200, location: null, data: '{}']
        }
        script.metaClass.hubInternalPostFormRaw = { String path, String body, Integer timeout = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '{}']
        }
        def wireArgs = (outer == leaf)
            ? new LinkedHashMap(leafArgs)
            : [tool: leaf, args: new LinkedHashMap(leafArgs)]

        when:
        def response = modernCall(outer, wireArgs)
        def inner = mcpDriver.parseInner(response)

        then: 'the ordinary structured refusal is terminal and no continuation protocol leaks out'
        response.error == null
        response.result.resultType == 'complete'
        response.result.isError != true
        !response.result.containsKey('requestState')
        inner.success == false
        inner.appId == 777
        inner.error.toLowerCase().contains(errorNeedle)
        inner.wizardStuck == false
        !inner.containsKey('backup')
        inner.restoreHint == script._rmPreflightRestoreHint()

        and: 'round zero created no durable work and touched no backup or wizard surface'
        !(atomicStateMap.mrtrRequests instanceof Map) || atomicStateMap.mrtrRequests.isEmpty()
        backupCalls.isEmpty()
        posts.isEmpty()
        hubGet.calls.isEmpty()
        runInMillisCalls.isEmpty()

        where:
        caseName                    | outer                              | leaf                 | leafArgs                                                                                                    | errorNeedle
        'periodic map missing'      | 'hub_manage_rule_machine'          | 'hub_set_rule'       | [appId: 777, addTrigger: [capability: 'Periodic Schedule', minutes: 1], confirm: true]                       | 'periodic'
        'state token in state'      | 'hub_set_rule'                     | 'hub_set_rule'       | [appId: 777, addTrigger: [capability: 'Temperature', state: 'changed'], confirm: true]                      | 'comparator'
        'periodic field missing'    | 'hub_manage_rule_machine'          | 'hub_set_rule'       | [appId: 777, addTrigger: [capability: 'Periodic Schedule', periodic: [frequency: 'Hourly']], confirm: true] | 'everyn'
        'action verb in state'      | 'hub_manage_native_rules_and_apps' | 'hub_set_native_app' | [appId: 777, addAction: [capability: 'switch', state: 'on'], confirm: true]                                 | 'action:'
    }

    def "direct flat-mode native preflight still returns a terminal refusal"() {
        given:
        settingsMap.enableWrite = true
        settingsMap.useGateways = false
        stateMap.lastBackupTimestamp = 1234567890000L
        def args = [appId: 781,
            addAction: [capability: 'switch', state: 'on'], confirm: true]

        when:
        def response = modernCall('hub_set_rule', args)
        def inner = mcpDriver.parseInner(response)

        then:
        response.error == null
        response.result.resultType == 'complete'
        !response.result.containsKey('requestState')
        inner.success == false
        inner.error.toLowerCase().contains('action:')
        !(atomicStateMap.mrtrRequests instanceof Map) || atomicStateMap.mrtrRequests.isEmpty()
        runInMillisCalls.isEmpty()
    }

    def "round-zero preflight defers #caseName to canonical worker dispatch"() {
        given:
        settingsMap.enableWrite = true
        settingsMap.useGateways = useGateways
        stateMap.lastBackupTimestamp = 1234567890000L
        def leafArgs = [appId: 782, confirm: true] + editShape
        def wireArgs = outer == leaf
            ? leafArgs
            : [tool: leaf, args: leafArgs]

        when:
        def response = modernCall(outer, wireArgs)
        String requestState = response.result.requestState

        then: 'round zero does not replace bulk or gateway routing semantics'
        response.error == null
        response.result.resultType == 'input_required'
        requestState instanceof String
        atomicStateMap.mrtrRequests[requestState].leafTool == leaf
        runInMillisCalls.isEmpty()

        where:
        caseName                  | useGateways | outer                     | leaf           | editShape
        'plural trigger envelope' | true        | 'hub_set_rule'            | 'hub_set_rule' | [addTriggers: [[capability: 'Switch', state: 'changed']]]
        'plural action envelope'  | true        | 'hub_set_rule'            | 'hub_set_rule' | [addActions: [[capability: 'switch', state: 'on']]]
        'disabled gateway route'  | false       | 'hub_manage_rule_machine' | 'hub_set_rule' | [addAction: [capability: 'switch', state: 'on']]
        'wrong gateway route'     | true        | 'hub_manage_devices'      | 'hub_set_rule' | [addAction: [capability: 'switch', state: 'on']]
    }

    def "round-zero native validation cannot outrank the #gateName gate"() {
        given:
        settingsMap.enableWrite = enableWrite
        // This feature exercises the owning gateway's access gates explicitly.
        // Pin it on so the flat CI matrix default cannot turn the request into
        // the distinct disabled-gateway routing case covered above.
        settingsMap.useGateways = true
        settingsMap.enableMandatoryBPS = mandatoryBps
        stateMap.lastBackupTimestamp = backupEpoch
        def leafArgs = [appId: 778,
            addTrigger: [capability: 'Periodic Schedule', minutes: 1],
            confirm: confirm]
        if (bestPracticeKey != null) leafArgs.bestPracticeKey = bestPracticeKey
        if (backupEpoch == null) {
            hubGet.register('/hub2/localBackups') { params -> '[]' }
        }
        def args = [tool: 'hub_set_rule', args: leafArgs]

        when:
        def response = modernCall('hub_manage_rule_machine', args)

        then:
        response.result == null
        response.error.code == -32602
        response.error.message.contains(expected)
        !response.error.message.toLowerCase().contains('periodic')
        !(atomicStateMap.mrtrRequests instanceof Map) || atomicStateMap.mrtrRequests.isEmpty()
        runInMillisCalls.isEmpty()

        where:
        gateName        | enableWrite | mandatoryBps | backupEpoch    | confirm | bestPracticeKey || expected
        'Write master'  | false       | false        | 1234567890000L | true    | null            || 'Write tools are disabled'
        'mandatory BPS' | true        | true         | 1234567890000L | true    | 'wrong'         || 'Mandatory best-practice acknowledgment'
        'confirmation'  | true        | false        | 1234567890000L | false   | null            || 'SAFETY CHECK FAILED'
        'backup freshness' | true     | false        | null           | true    | null            || 'BACKUP REQUIRED'
    }

    def "valid #leaf native write still allocates requestState after pure preflight"() {
        given:
        settingsMap.enableWrite = true
        settingsMap.useGateways = true
        stateMap.lastBackupTimestamp = 1234567890000L
        def leafArgs = [appId: 779,
            addAction: [capability: 'log', message: 'valid preflight'],
            confirm: true]
        def args = (outer == leaf) ? leafArgs : [tool: leaf, args: leafArgs]

        when:
        def response = modernCall(outer, args)
        String requestState = response.result.requestState

        then:
        response.error == null
        response.result.resultType == 'input_required'
        requestState instanceof String
        atomicStateMap.mrtrRequests[requestState].leafTool == leaf
        runInMillisCalls.isEmpty()

        where:
        outer                              | leaf
        'hub_set_rule'                     | 'hub_set_rule'
        'hub_manage_native_rules_and_apps' | 'hub_set_native_app'
    }

    def "pure refusal does not outrank existing multi-operation dispatch"() {
        given:
        settingsMap.enableWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L
        def args = [appId: 780,
            addTrigger: [capability: 'Periodic Schedule', minutes: 1],
            addLocalVariable: [name: 'mustNotLand', type: 'String', value: 'x'],
            confirm: true]

        when:
        def response = modernCall('hub_set_rule', args)
        String requestState = response.result.requestState

        then: 'round zero defers to the existing worker-side operation-family guard'
        response.error == null
        response.result.resultType == 'input_required'
        requestState instanceof String
        atomicStateMap.mrtrRequests[requestState].leafTool == 'hub_set_rule'
        runInMillisCalls.isEmpty()
    }

    def "modern slow write preflights, continues across bounded slices, and replays its terminal result"() {
        given:
        settingsMap.enableWrite = true
        def ranWith = []
        script.metaClass.toolRunRmRule = { Map a ->
            ranWith << new LinkedHashMap(a)
            if (ranWith.size() == 1) {
                return [
                    success: false,
                    partial: true,
                    ruleIds: [11, 12],
                    rmAction: 'stopRule toggle x1',
                    results: [[success: true, ruleId: 11]],
                    remainingRuleIds: [12]
                ]
            }
            return [
                success: true,
                partial: false,
                ruleIds: [12],
                rmAction: 'stopRule toggle x1',
                results: [[success: true, ruleId: 12]]
            ]
        }
        def original = [ruleId: [11, 12], action: 'stop']

        when: 'round zero allocates opaque state but performs no write'
        def first = modernCall('hub_call_rule', original)
        String requestState = first.result.requestState

        then:
        first.error == null
        first.result.resultType == 'input_required'
        requestState instanceof String
        requestState.size() >= 24
        !first.result.containsKey('content')
        ranWith.isEmpty()

        when: 'the first resumed request executes one bounded slice'
        def second = modernCall('hub_call_rule', original, requestState)

        then:
        second.result.resultType == 'input_required'
        second.result.requestState == requestState
        ranWith.size() == 1
        ranWith[0].ruleId == [11, 12]
        !ranWith[0].containsKey('__mrtr')

        when: 'the next resumed request executes only the stored remainder'
        def third = modernCall('hub_call_rule', original, requestState)
        def finalInner = mcpDriver.parseInner(third)

        then:
        third.error == null
        third.result.resultType == 'complete'
        third.result.isError != true
        ranWith.size() == 2
        ranWith[1].ruleId == [12]
        finalInner.success == true
        finalInner.ruleIds == [11, 12]
        finalInner.results*.ruleId == [11, 12]
        finalInner.mrtr.rounds == 2

        when: 'a lost terminal response is retried with the same requestState'
        def replay = modernCall('hub_call_rule', original, requestState)

        then: 'the cached terminal result is returned and the write is not run again'
        replay.result.resultType == 'complete'
        mcpDriver.parseInner(replay).results*.ruleId == [11, 12]
        ranWith.size() == 2
    }

    def "a resumed native write returns requestState before its blocked worker leaf finishes"() {
        given:
        settingsMap.enableWrite = true
        settingsMap.maxConcurrentWrites = 1
        def args = [appId: 321, confirm: true, settings: [description: 'slow edit']]
        def preflight = modernCall('hub_set_rule', args)
        String stateId = preflight.result.requestState
        def entered = new CountDownLatch(1)
        def release = new CountDownLatch(1)
        def workerDone = new CountDownLatch(1)
        def leafCalls = new AtomicInteger(0)
        def observedWaitMs = new AtomicLong(0L)
        def failure = new AtomicReference()
        def virtualNow = new AtomicLong(1234567890000L)
        long schedulerElapsedMs = 1000L
        NOW_OVERRIDE.set({ -> virtualNow.get() })
        PAUSE_EXECUTION_OVERRIDE.set({ Long delayMs ->
            observedWaitMs.addAndGet(delayMs)
            virtualNow.addAndGet(delayMs)
        })
        RUN_IN_OVERRIDE.set({ List call ->
            runInMillisCalls << call
            virtualNow.addAndGet(schedulerElapsedMs)
        })
        script.metaClass.toolSetRule = { Map actual ->
            leafCalls.incrementAndGet()
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            [success: true, appId: actual.appId, settingsApplied: true]
        }

        when: 'the first resumed HTTP leg claims and schedules the generation'
        def scheduled = modernCall('hub_set_rule', args, stateId)

        then: 'the mapped request is already free and the Hubitat leaf has not run inline'
        scheduled.error == null
        scheduled.result.resultType == 'input_required'
        scheduled.result.requestState == stateId
        leafCalls.get() == 0
        observedWaitMs.get() ==
            (script._mrtrScheduleObserveWaitMs('hub_set_rule') as Long) - schedulerElapsedMs
        runInMillisCalls.size() == 1
        runInMillisCalls[0][0..1] == [200, 'runMrtrSlice']
        runInMillisCalls[0][2].overwrite == false
        runInMillisCalls[0][2].data.stateId == stateId
        runInMillisCalls[0][2].data.claimId instanceof String
        !runInMillisCalls[0][2].data.containsKey('arguments')

        when: 'another write arrives while the claimed worker is only queued'
        def capped = modernCall('hub_call_rule', [ruleId: [401, 402], action: 'stop'])
        def cappedInner = mcpDriver.parseInner(capped)

        then: 'queued work still occupies the all-write concurrency slot'
        capped.result.isError == true
        cappedInner.status == 'too_many_writes_in_flight'
        cappedInner.active == [[tool: 'hub_set_rule', startedAt: 1234567890000L, transport: 'mrtr']]

        when: 'Hubitat invokes the scheduled worker and its real leaf remains blocked'
        Map workerData = new LinkedHashMap(runInMillisCalls[0][2].data as Map)
        Thread worker = Thread.start {
            try {
                script.runMrtrSlice(workerData)
            } catch (Throwable t) {
                failure.set(t)
            } finally {
                workerDone.countDown()
            }
        }
        assert entered.await(5, TimeUnit.SECONDS)
        def whileRunning = modernCall('hub_set_rule', args, stateId)

        then: 'the same logical request stays automatic and does not dispatch twice'
        whileRunning.error == null
        whileRunning.result.resultType == 'input_required'
        whileRunning.result.requestState == stateId
        leafCalls.get() == 1

        when: 'the worker finishes and the client advances once more'
        release.countDown()
        assert workerDone.await(5, TimeUnit.SECONDS)
        worker.join(5000)
        def complete = modernCall('hub_set_rule', args, stateId)
        def inner = mcpDriver.parseInner(complete)
        script.runMrtrSlice(workerData)

        then: 'the retained terminal result replays and a stale worker cannot repeat the write'
        !worker.alive
        failure.get() == null
        complete.error == null
        complete.result.resultType == 'complete'
        complete.result.isError != true
        inner.success == true
        inner.appId == 321
        leafCalls.get() == 1

        cleanup:
        release.countDown()
        worker?.join(5000)
    }

    def "the scheduling request observes a fast detached terminal result and replays it exactly once"() {
        given:
        settingsMap.enableWrite = true
        def args = [appId: 322, confirm: true, settings: [description: 'fast edit']]
        def preflight = modernCall('hub_set_rule', args)
        String stateId = preflight.result.requestState
        def leafCalls = new AtomicInteger(0)
        script.metaClass.toolSetRule = { Map actual ->
            leafCalls.incrementAndGet()
            [success: true, appId: actual.appId, settingsApplied: true]
        }
        RUN_IN_OVERRIDE.set({ List call ->
            runInMillisCalls << call
            script.runMrtrSlice(new LinkedHashMap(call[2].data as Map))
        })

        when:
        def complete = modernCall('hub_set_rule', args, stateId)
        def replay = modernCall('hub_set_rule', args, stateId)

        then:
        complete.result.resultType == 'complete'
        complete.result.isError != true
        mcpDriver.parseInner(complete).appId == 322
        replay.result.resultType == 'complete'
        mcpDriver.parseInner(replay).settingsApplied == true
        leafCalls.get() == 1
        runInMillisCalls.size() == 1
        runInMillisCalls[0][0..1] == [200, 'runMrtrSlice']
        runInMillisCalls[0][2].overwrite == false
        runInMillisCalls[0][2].data.keySet() == ['stateId', 'claimId', 'generation'] as Set
    }

    def "the scheduling request observes generation advancement without claiming the next generation"() {
        given:
        settingsMap.enableWrite = true
        def args = [appId: 323, confirm: true, settings: [description: 'two slices']]
        def preflight = modernCall('hub_set_rule', args)
        String stateId = preflight.result.requestState
        def leafCalls = new AtomicInteger(0)
        script.metaClass.toolSetRule = { Map actual ->
            leafCalls.incrementAndGet()
            [success: false, partial: true, status: 'in_progress',
             stepsRemaining: [[name: 'finish']], appId: actual.appId]
        }
        RUN_IN_OVERRIDE.set({ List call ->
            runInMillisCalls << call
            script.runMrtrSlice(new LinkedHashMap(call[2].data as Map))
        })

        when:
        def advanced = modernCall('hub_set_rule', args, stateId)
        Map stored = atomicStateMap.mrtrRequests[stateId] as Map

        then:
        advanced.result.resultType == 'input_required'
        advanced.result.requestState == stateId
        leafCalls.get() == 1
        runInMillisCalls.size() == 1
        stored.status == 'active'
        stored.generation == 1
        stored.rounds == 1
        !stored.containsKey('claimId')
        !stored.containsKey('claimedGeneration')
    }

    def "the scheduling request observes and replays a fast detached worker error"() {
        given:
        settingsMap.enableWrite = true
        def args = [appId: 324, confirm: true, settings: [description: 'worker error']]
        def preflight = modernCall('hub_set_rule', args)
        String stateId = preflight.result.requestState
        def leafCalls = new AtomicInteger(0)
        script.metaClass.toolSetRule = { Map actual ->
            leafCalls.incrementAndGet()
            throw new IllegalStateException('detached leaf failed')
        }
        RUN_IN_OVERRIDE.set({ List call ->
            runInMillisCalls << call
            script.runMrtrSlice(new LinkedHashMap(call[2].data as Map))
        })

        when:
        def failed = modernCall('hub_set_rule', args, stateId)
        def replay = modernCall('hub_set_rule', args, stateId)

        then:
        failed.result.resultType == 'complete'
        failed.result.isError == true
        mcpDriver.parseInner(failed).error.contains('detached leaf failed')
        replay.result.resultType == 'complete'
        replay.result.isError == true
        mcpDriver.parseInner(replay).error.contains('detached leaf failed')
        leafCalls.get() == 1
        runInMillisCalls.size() == 1
    }

    def "an exact round-zero replay rejoins the active MRTR operation without running a second write"() {
        given:
        settingsMap.enableWrite = true
        def ran = 0
        script.metaClass.toolRunRmRule = { Map a -> ran++; [success: true, ruleIds: a.ruleId, results: []] }
        def original = [ruleId: [21, 22], action: 'stop']

        when:
        def first = modernCall('hub_call_rule', original)
        def duplicate = modernCall('hub_call_rule', original)

        then:
        first.result.resultType == 'input_required'
        duplicate.result.resultType == 'input_required'
        duplicate.result.requestState == first.result.requestState
        !duplicate.result.containsKey('opToken')
        ran == 0
    }

    def "parallel writes are capped by active requestState records without exposing their ids"() {
        given:
        settingsMap.enableWrite = true
        settingsMap.maxConcurrentWrites = 1

        when:
        def first = modernCall('hub_call_rule', [ruleId: [21, 22], action: 'stop'])
        def second = modernCall('hub_call_rule', [ruleId: [31, 32], action: 'stop'])
        def inner = mcpDriver.parseInner(second)

        then:
        first.result.resultType == 'input_required'
        second.result.resultType == 'complete'
        second.result.isError == true
        inner.status == 'too_many_writes_in_flight'
        inner.limit == 1
        inner.active == [[tool: 'hub_call_rule', startedAt: 1234567890000L, transport: 'mrtr']]
        !inner.toString().contains(first.result.requestState.toString())
    }

    def "two compiled app instances keep a blocked ordinary write counted past lease TTL"() {
        given:
        settingsMap.enableWrite = true
        settingsMap.maxConcurrentWrites = 1
        def peer = newCompiledScriptInstance()
        assert !peer.is(script)
        assert peer.getClass().is(script.getClass())
        def entered = new CountDownLatch(1)
        def release = new CountDownLatch(1)
        def calls = new AtomicInteger(0)
        def virtualNow = new AtomicLong(1234567890000L)
        NOW_OVERRIDE.set({ -> virtualNow.get() })
        Closure leaf = { deviceId, command, parameters, waitFor ->
            calls.incrementAndGet()
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            [success: true, deviceId: deviceId, command: command]
        }
        script.metaClass.toolSendCommand = leaf
        peer.metaClass.toolSendCommand = leaf
        def winner = new AtomicReference()
        def failure = new AtomicReference()
        Thread first = Thread.start {
            try {
                winner.set(directCall(script, 1701, 'hub_call_device_command',
                    [deviceId: '17', command: 'on']))
            } catch (Throwable t) {
                failure.set(t)
            }
        }

        when:
        assert entered.await(5, TimeUnit.SECONDS)
        virtualNow.addAndGet((script._mrtrActiveTtlMs() as Long) + 1L)
        def contender = directCall(peer, 1702, 'hub_call_device_command',
            [deviceId: '18', command: 'off'])
        def contenderInner = mcpDriver.parseInner(contender)
        release.countDown()
        first.join(5000)

        then:
        !first.alive
        failure.get() == null
        calls.get() == 1
        contender.result.isError == true
        contenderInner.status == 'too_many_writes_in_flight'
        winner.get().result.isError != true

        cleanup:
        release.countDown()
        first?.join(5000)
    }

    def "simultaneous cap-one device calls on two compiled instances dispatch one leaf"() {
        given:
        settingsMap.enableWrite = true
        settingsMap.maxConcurrentWrites = 1
        def peer = newCompiledScriptInstance()
        def ready = new CountDownLatch(2)
        def start = new CountDownLatch(1)
        def entered = new CountDownLatch(1)
        def oneFinished = new CountDownLatch(1)
        def release = new CountDownLatch(1)
        def calls = new AtomicInteger(0)
        def results = java.util.Collections.synchronizedList([])
        def failures = java.util.Collections.synchronizedList([])
        Closure leaf = { deviceId, command, parameters, waitFor ->
            calls.incrementAndGet()
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            [success: true, deviceId: deviceId, command: command]
        }
        script.metaClass.toolSendCommand = leaf
        peer.metaClass.toolSendCommand = leaf
        def targets = [script, peer]
        def threads = (0..<2).collect { int index ->
            Thread.start("device-dispatch-race-${index}") {
                ready.countDown()
                start.await()
                try {
                    results << directCall(targets[index], 1750 + index,
                        'hub_call_device_command',
                        [deviceId: "${20 + index}".toString(), command: 'on'])
                } catch (Throwable t) {
                    failures << t
                } finally {
                    oneFinished.countDown()
                }
            }
        }

        when:
        assert ready.await(5, TimeUnit.SECONDS)
        start.countDown()
        assert entered.await(5, TimeUnit.SECONDS)
        assert oneFinished.await(5, TimeUnit.SECONDS)

        then: 'the loser was refused while the winner remains blocked in its leaf'
        calls.get() == 1
        results.size() == 1
        mcpDriver.parseInner(results[0]).status == 'too_many_writes_in_flight'

        when:
        release.countDown()
        threads*.join(5000)

        then:
        !threads.any { it.alive }
        failures.isEmpty()
        calls.get() == 1
        results.size() == 2
        results.count { it.result.isError == true } == 1
        results.count { it.result.isError != true } == 1

        cleanup:
        release.countDown()
        threads*.join(5000)
    }

    def "two compiled app instances serialize complete identical MRTR preflights"() {
        given:
        settingsMap.enableWrite = true
        settingsMap.maxConcurrentWrites = 0
        mcpDriver.pushHeaders([
            'MCP-Protocol-Version': '2026-07-28',
            'Mcp-Method': 'tools/call',
            'Mcp-Name': 'hub_call_rule'
        ])
        def peer = newCompiledScriptInstance()
        def args = [ruleId: [901, 902], action: 'stop']

        when:
        def attempts = race(24) { int index ->
            directCall(index % 2 == 0 ? script : peer, 1800 + index,
                'hub_call_rule', args)
        }

        then:
        attempts.every { it.result?.resultType == 'input_required' }
        attempts.collect { it.result.requestState }.unique().size() == 1
        (atomicStateMap.mrtrRequests as Map).values().count { it?.status == 'active' } == 1
    }

    def "a blocked MRTR generation stays counted past TTL and contention remains a continuation"() {
        given:
        settingsMap.enableWrite = true
        settingsMap.maxConcurrentWrites = 1
        mcpDriver.pushHeaders([
            'MCP-Protocol-Version': '2026-07-28',
            'Mcp-Method': 'tools/call',
            'Mcp-Name': 'hub_call_rule'
        ])
        def peer = newCompiledScriptInstance()
        def args = [ruleId: [911, 912], action: 'stop']
        def preflight = directCall(script, 1900, 'hub_call_rule', args)
        String stateId = preflight.result.requestState
        def entered = new CountDownLatch(1)
        def release = new CountDownLatch(1)
        def calls = new AtomicInteger(0)
        def virtualNow = new AtomicLong(1234567890000L)
        NOW_OVERRIDE.set({ -> virtualNow.get() })
        Closure leaf = { Map a ->
            calls.incrementAndGet()
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            [success: true, partial: false, ruleIds: a.ruleId,
             results: a.ruleId.collect { [success: true, ruleId: it] }]
        }
        script.metaClass.toolRunRmRule = leaf
        peer.metaClass.toolRunRmRule = leaf
        def winner = new AtomicReference()
        def failure = new AtomicReference()
        Thread first = Thread.start {
            try {
                winner.set(directCall(script, 1901, 'hub_call_rule', args, stateId))
            } catch (Throwable t) {
                failure.set(t)
            }
        }

        when:
        assert entered.await(5, TimeUnit.SECONDS)
        virtualNow.addAndGet((script._mrtrActiveTtlMs() as Long) + 1L)
        def sameState = directCall(peer, 1902, 'hub_call_rule', args, stateId)
        def competingFresh = directCall(peer, 1903, 'hub_call_rule',
            [ruleId: [913, 914], action: 'stop'])
        def competingInner = mcpDriver.parseInner(competingFresh)
        release.countDown()
        first.join(5000)

        then:
        !first.alive
        failure.get() == null
        calls.get() == 1
        sameState.error == null
        sameState.result.resultType == 'input_required'
        sameState.result.requestState == stateId
        !sameState.result.containsKey('content')
        competingFresh.result.isError == true
        competingInner.status == 'too_many_writes_in_flight'
        winner.get().result.resultType == 'complete'

        cleanup:
        release.countDown()
        first?.join(5000)
    }

    def "repeated contention waits preserve the logical call until the winner finishes"() {
        given:
        settingsMap.enableWrite = true
        settingsMap.maxConcurrentWrites = 1
        mcpDriver.pushHeaders([
            'MCP-Protocol-Version': '2026-07-28',
            'Mcp-Method': 'tools/call',
            'Mcp-Name': 'hub_call_rule'
        ])
        def peer = newCompiledScriptInstance()
        def args = [ruleId: [921, 922], action: 'stop']
        def preflight = directCall(script, 1950, 'hub_call_rule', args)
        String stateId = preflight.result.requestState
        def entered = new CountDownLatch(1)
        def release = new CountDownLatch(1)
        def ownerDone = new CountDownLatch(1)
        def calls = new AtomicInteger(0)
        def pauses = new AtomicInteger(0)
        def releaseOnPause = new AtomicInteger(0)
        def virtualNow = new AtomicLong(1234567890000L)
        NOW_OVERRIDE.set({ -> virtualNow.get() })
        Closure pause = { Long delayMs ->
            pauses.incrementAndGet()
            virtualNow.addAndGet(delayMs)
            if (releaseOnPause.compareAndSet(1, 0)) {
                release.countDown()
                ownerDone.await(5, TimeUnit.SECONDS)
            }
        }
        PAUSE_EXECUTION_OVERRIDE.set(pause)
        Closure leaf = { Map a ->
            calls.incrementAndGet()
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            [success: true, partial: false, ruleIds: a.ruleId,
             results: a.ruleId.collect { [success: true, ruleId: it] }]
        }
        script.metaClass.toolRunRmRule = leaf
        peer.metaClass.toolRunRmRule = leaf
        def winner = new AtomicReference()
        def failure = new AtomicReference()
        Thread first = Thread.start {
            try {
                winner.set(directCall(script, 1951, 'hub_call_rule', args, stateId))
            } catch (Throwable t) {
                failure.set(t)
            } finally {
                ownerDone.countDown()
            }
        }

        when: 'three complete continuation legs wait while the original leaf remains blocked'
        assert entered.await(5, TimeUnit.SECONDS)
        def contention = (0..<3).collect { int index ->
            directCall(peer, 1952 + index, 'hub_call_rule', args, stateId)
        }

        then:
        contention.every { it.error == null && it.result.resultType == 'input_required' }
        contention.every { it.result.requestState == stateId }
        calls.get() == 1
        pauses.get() >= 3
        virtualNow.get() >= 1234567890000L +
            3L * (script._mrtrContentionWaitMs('hub_call_rule') as Long)

        when: 'the next leg observes owner completion during its bounded wait'
        releaseOnPause.set(1)
        def replay = directCall(peer, 1955, 'hub_call_rule', args, stateId)
        first.join(5000)

        then:
        !first.alive
        failure.get() == null
        winner.get().result.resultType == 'complete'
        replay.error == null
        replay.result.resultType == 'complete'
        calls.get() == 1

        cleanup:
        release.countDown()
        first?.join(5000)
    }

    def "cloud contention wait leaves half the relay budget for reclaimed slice work"() {
        given:
        script.metaClass._isCloudRequest = { -> true }

        expect:
        script._mrtrContentionWaitMs() == 4000L

        when:
        settingsMap.relayBudgetMs = 3000

        then:
        script._mrtrContentionWaitMs() == 1500L
    }

    def "cloud worker contention uses the safe budget headroom instead of exhausting SDK rounds"() {
        given:
        script.metaClass._isCloudRequest = { -> true }

        expect: 'detached writes wait in-leg but preserve measured cloud-relay jitter headroom'
        script._mrtrContentionWaitMs('hub_set_rule') == 4500L
        script._mrtrContentionWaitMs('hub_set_native_app') == 4500L

        and: 'the scheduling observer has one more second of cloud-relay headroom'
        script._mrtrScheduleObserveWaitMs('hub_set_rule') == 3500L
        script._mrtrScheduleObserveWaitMs('hub_set_native_app') == 3500L

        and: 'synchronous slices retain half their request budget for actual leaf work'
        script._mrtrContentionWaitMs('hub_call_rule') == 4000L

        when: 'an operator configures a smaller cloud leg budget'
        settingsMap.relayBudgetMs = 5000

        then: 'the worker path keeps 2000ms for parsing, scheduling, and rendering'
        script._mrtrContentionWaitMs('hub_set_rule') == 3000L
        script._mrtrScheduleObserveWaitMs('hub_set_rule') == 3000L
        script._mrtrContentionWaitMs('hub_call_rule') == 2500L

        when: 'the same explicit budget is applied to a LAN request'
        script.metaClass._isCloudRequest = { -> false }
        settingsMap.lanBudgetMs = 5000

        then: 'LAN retains its prior 1500ms reserve because the measured defect is cloud-only'
        script._mrtrContentionWaitMs('hub_set_rule') == 3500L
        script._mrtrScheduleObserveWaitMs('hub_set_rule') == 3500L
    }

    def "a gateway scheduling request observes the resolved leaf terminal result"() {
        given:
        settingsMap.enableWrite = true
        settingsMap.useGateways = true
        def gateway = 'hub_manage_native_rules_and_apps'
        def args = [tool: 'hub_set_native_app', args: [appId: 654, confirm: true,
            settings: [description: 'gateway worker']]]
        def preflight = modernCall(gateway, args)
        String stateId = preflight.result.requestState
        def seen = []
        script.metaClass.toolSetNativeApp = { Map actual ->
            seen << new LinkedHashMap(actual)
            [success: true, appId: actual.appId, settingsApplied: true]
        }
        RUN_IN_OVERRIDE.set({ List call ->
            runInMillisCalls << call
            script.runMrtrSlice(new LinkedHashMap(call[2].data as Map))
        })

        when:
        def complete = modernCall(gateway, args, stateId)
        def inner = mcpDriver.parseInner(complete)

        then:
        runInMillisCalls.size() == 1
        runInMillisCalls[0][0..1] == [200, 'runMrtrSlice']
        seen == [[appId: 654, confirm: true, settings: [description: 'gateway worker']]]
        complete.result.resultType == 'complete'
        complete.result.isError != true
        inner.appId == 654
        inner.success == true
    }

    def "modern driver-code lifecycle leaf #leaf preflights without dispatching"() {
        given:
        settingsMap.enableWrite = true
        settingsMap.useGateways = true
        def dispatched = []
        script.metaClass.toolInstallDriver = { Map actual ->
            dispatched << [leaf: 'hub_create_driver', arguments: new LinkedHashMap(actual)]
            [success: true]
        }
        script.metaClass.toolUpdateDriverCode = { Map actual ->
            dispatched << [leaf: 'hub_update_driver', arguments: new LinkedHashMap(actual)]
            [success: true]
        }
        script.metaClass.toolDeleteItem = { Map actual ->
            dispatched << [leaf: 'hub_delete_item', arguments: new LinkedHashMap(actual)]
            [success: true]
        }
        def gatewayArgs = [tool: leaf, args: leafArgs]

        when:
        def preflight = modernCall('hub_manage_code', gatewayArgs)

        then: 'round zero allocates requestState but cannot mutate Hubitat code'
        preflight.error == null
        preflight.result.resultType == 'input_required'
        preflight.result.requestState instanceof String
        dispatched.isEmpty()

        where:
        leaf                | leafArgs
        'hub_create_driver' | [source: 'metadata { }', confirm: true]
        'hub_update_driver' | [driverId: '55', source: 'metadata { }', confirm: true]
        'hub_delete_item'   | [type: 'driver', item_id: '55', confirm: true]
    }

    def "modern hub_delete_item keeps unproven #itemType deletion on the synchronous path"() {
        given:
        settingsMap.enableWrite = true
        settingsMap.useGateways = true
        def dispatched = []
        script.metaClass.toolDeleteItem = { Map actual ->
            dispatched << new LinkedHashMap(actual)
            [success: true, itemType: actual.type]
        }
        def args = [tool: 'hub_delete_item', args: [
            type: itemType, item_id: '55', confirm: true
        ]]

        when:
        def response = modernCall('hub_manage_code', args)

        then: 'app/library deletion remains outside the driver-only detached proof'
        response.error == null
        response.result.resultType == 'complete'
        mcpDriver.parseInner(response).success == true
        dispatched == [args.args]
        runInMillisCalls.isEmpty()

        where:
        itemType << ['app', 'library']
    }

    def "gateway driver update runs once in a detached worker and replays its terminal result"() {
        given:
        settingsMap.enableWrite = true
        settingsMap.useGateways = true
        settingsMap.maxConcurrentWrites = 1
        def gateway = 'hub_manage_code'
        def args = [tool: 'hub_update_driver', args: [
            driverId: '55', source: 'metadata { }', confirm: true
        ]]
        def preflight = modernCall(gateway, args)
        String stateId = preflight.result.requestState
        def dispatched = []
        script.metaClass.toolUpdateDriverCode = { Map actual ->
            dispatched << new LinkedHashMap(actual)
            [success: true, driverId: '55', previousVersion: 7]
        }

        when: 'the first resumed HTTP leg only schedules the claimed generation'
        def scheduled = modernCall(gateway, args, stateId)

        then:
        preflight.result.resultType == 'input_required'
        scheduled.error == null
        scheduled.result.resultType == 'input_required'
        scheduled.result.requestState == stateId
        dispatched.isEmpty()
        runInMillisCalls.size() == 1
        runInMillisCalls[0][0..1] == [200, 'runMrtrSlice']

        when: 'another slow code write arrives while the worker is queued'
        def capped = modernCall(gateway, [tool: 'hub_create_driver', args: [
            source: 'metadata { }', confirm: true
        ]])
        def cappedInner = mcpDriver.parseInner(capped)

        then: 'the queued code update owns the global write slot'
        capped.result.resultType == 'complete'
        capped.result.isError == true
        cappedInner.status == 'too_many_writes_in_flight'
        cappedInner.active*.tool == ['hub_update_driver']
        cappedInner.active*.transport == ['mrtr']

        when: 'the worker executes once and later calls observe its retained terminal result'
        Map workerData = new LinkedHashMap(runInMillisCalls[0][2].data as Map)
        script.runMrtrSlice(workerData)
        def complete = modernCall(gateway, args, stateId)
        def replay = modernCall(gateway, args, stateId)

        then:
        dispatched == [args.args]
        complete.result.resultType == 'complete'
        complete.result.isError != true
        mcpDriver.parseInner(complete).success == true
        mcpDriver.parseInner(complete).mrtr.rounds == 1
        replay.result.resultType == 'complete'
        mcpDriver.parseInner(replay).previousVersion == 7
        dispatched.size() == 1
    }

    def "terminal evidence repairs an active MRTR snapshot resurrected by an app bounce"() {
        // Live E2E: a limiter recovery disabled/enabled the MCP app immediately after
        // an identical pausRule fallback had returned its terminal result.  The next
        // execution observed that request's older active atomicState snapshot and
        // refused the legitimate next toggle as duplicate_in_flight.  Reproduce the
        // platform boundary by restoring the exact claimed-active snapshot after the
        // worker has completed.  Only terminal evidence captured by that worker may
        // repair it; absence/age alone is deliberately insufficient.
        given:
        settingsMap.enableWrite = true
        settingsMap.maxConcurrentWrites = 1
        def args = [appId: 654, confirm: true, button: 'pausRule']
        def binding = script._mrtrBinding('hub_set_rule', 'hub_set_rule', args) as Map
        def calls = 0
        script.metaClass.toolSetRule = { Map actual ->
            calls++
            [success: true, appId: actual.appId]
        }
        def preflight = modernCall('hub_set_rule', args)
        String stateId = preflight.result.requestState
        def scheduled = modernCall('hub_set_rule', args, stateId)
        Map activeSnapshot = script._mrtrCopyMap(
            atomicStateMap.mrtrRequests[stateId] as Map) as Map
        Map workerData = new LinkedHashMap(runInMillisCalls[0][2].data as Map)
        script.runMrtrSlice(workerData)
        def complete = modernCall('hub_set_rule', args, stateId)

        expect: 'the original logical write completed exactly once before the simulated bounce'
        scheduled.result.resultType == 'input_required'
        complete.result.resultType == 'complete'
        calls == 1

        when: 'disable/enable exposes the older claimed-active snapshot'
        atomicStateMap.mrtrRequests[stateId] = activeSnapshot
        // Reloading atomicState is what makes the older snapshot the read path again;
        // MRTR_TERMINAL_EVIDENCE deliberately survives, and is the only repair proof.
        script._writeStateCacheInvalidate()
        def recovered = script._mrtrClaim(stateId, 'hub_set_rule', 'hub_set_rule', binding) as Map

        then: 'the exact terminal generation is recovered, never treated as still running'
        recovered.outcome == 'terminal'
        recovered.record.status == 'terminal'
        recovered.record.terminalResult.success == true
        calls == 1

        when: 'a later identical toggle is a new logical operation'
        def next = script._mrtrReserve('hub_set_rule', 'hub_set_rule', binding) as Map

        then: 'the stale snapshot cannot cause duplicate_in_flight or consume the write cap'
        next.accepted == true
        next.stateId != stateId
        calls == 1
    }

    def "terminal evidence does not repair an active snapshot with mismatched #field"() {
        given:
        settingsMap.enableWrite = true
        settingsMap.maxConcurrentWrites = 1
        def args = [appId: 654, confirm: true, button: 'pausRule']
        def binding = script._mrtrBinding('hub_set_rule', 'hub_set_rule', args) as Map
        def calls = 0
        script.metaClass.toolSetRule = { Map actual ->
            calls++
            [success: true, appId: actual.appId]
        }
        def preflight = modernCall('hub_set_rule', args)
        String stateId = preflight.result.requestState
        modernCall('hub_set_rule', args, stateId)
        Map activeSnapshot = script._mrtrCopyMap(
            atomicStateMap.mrtrRequests[stateId] as Map) as Map
        Map workerData = new LinkedHashMap(runInMillisCalls[0][2].data as Map)
        script.runMrtrSlice(workerData)
        def complete = modernCall('hub_set_rule', args, stateId)

        expect: 'terminal evidence exists for the actual claim and generation'
        complete.result.resultType == 'complete'
        calls == 1

        when: 'the exposed active snapshot does not identify that exact execution'
        activeSnapshot[field] = mismatch
        atomicStateMap.mrtrRequests[stateId] = activeSnapshot
        script._writeStateCacheInvalidate()
        script._mrtrSweep()
        Map afterSweep = atomicStateMap.mrtrRequests[stateId] as Map
        def replay = script._mrtrReserve(
            'hub_set_rule', 'hub_set_rule', binding) as Map

        then: 'terminal proof is ignored and the active operation retains the write slot'
        afterSweep.status == 'active'
        afterSweep[field] == mismatch
        replay.accepted == true
        replay.rejoined == true
        replay.stateId == stateId
        calls == 1

        where:
        field               | mismatch
        'claimId'           | 'different-claim'
        'claimedGeneration' | 999
        'generation'        | 999
    }

    def "clone continuation checkpoints clicks then commits exactly once"() {
        given:
        settingsMap.enableWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L
        hubGet.register('/installedapp/configure/json/100') { params ->
            nativeRuleConfig(100, 'Source Rule', 21)
        }
        hubGet.register('/apps/api/4242/app/100') { params -> '<html>source-context</html>' }
        hubGet.register('/installedapp/configure/json/4242/main') { params ->
            clonerPageState('importRule', 0)
        }
        int parentReads = 0
        hubGet.register('/installedapp/configure/json/21') { params ->
            parentReads++
            parentReads == 1
                ? nativeParentConfig(21, [[id: 100, label: 'Source Rule']])
                : nativeParentConfig(21, [[id: 100, label: 'Source Rule'],
                                          [id: 250, label: 'Source Rule clone']])
        }
        script.metaClass.hubInternalGetRaw = { String path, Map query = null, Integer timeout = 30 ->
            [status: 302, location: '/apps/api/4242/app/100', data: '']
        }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer timeout = 420 ->
            posts << [path: path, body: new LinkedHashMap(body)]
            [status: 200, location: null, data: '{"status":"success"}']
        }
        script.metaClass.hubInternalPostFormRaw = { String path, String body, Integer timeout = 420 ->
            posts << [path: path, body: decodeForm(body)]
            [status: 200, location: null, data: '{"status":"success"}']
        }
        def args = [sourceAppId: 100, confirm: true]

        when: 'preflight allocates state without touching the cloner'
        def preflight = modernCall('hub_clone_native_app', args)
        String requestState = preflight.result.requestState

        then:
        preflight.result.resultType == 'input_required'
        posts.isEmpty()
        atomicStateMap.mrtrRequests[requestState].checkpoint == null

        when: 'the first resume initializes and checkpoints the click phase'
        def initialized = modernCall('hub_clone_native_app', args, requestState)

        then:
        initialized.result.resultType == 'input_required'
        initialized.result.requestState == requestState
        atomicStateMap.mrtrRequests[requestState].checkpoint.phase == 'clone_clicks'
        atomicStateMap.mrtrRequests[requestState].checkpoint.clonerAppId == 4242
        posts.isEmpty()

        when: 'the next resume performs the click phase and checkpoints the commit'
        def clicked = modernCall('hub_clone_native_app', args, requestState)
        def cloneClicks = posts.findAll {
            it.path == '/installedapp/btn' && it.body.name == 'cloneRuleButton'
        }

        then:
        clicked.result.resultType == 'input_required'
        clicked.result.requestState == requestState
        atomicStateMap.mrtrRequests[requestState].checkpoint.phase == 'clone_commit'
        cloneClicks.size() == 2
        !posts.any { it.path == '/installedapp/btn' && it.body.name == 'importNow' }

        when: 'the final resume commits and a replay observes the retained terminal result'
        def completed = modernCall('hub_clone_native_app', args, requestState)
        def completedInner = mcpDriver.parseInner(completed)
        int postsAfterCommit = posts.size()
        def replay = modernCall('hub_clone_native_app', args, requestState)
        def importNowClicks = posts.findAll {
            it.path == '/installedapp/btn' && it.body.name == 'importNow'
        }
        def commitNavigations = posts.findAll {
            it.path == '/installedapp/update/json' &&
                it.body.containsKey('_action_href_name|importRule|0')
        }

        then:
        completed.result.resultType == 'complete'
        completed.result.isError != true
        completedInner.success == true
        completedInner.newAppId == 250
        completedInner.mrtr.rounds == 3
        commitNavigations.size() == 1
        importNowClicks.size() == 2
        replay.result.resultType == 'complete'
        mcpDriver.parseInner(replay).newAppId == 250
        posts.size() == postsAfterCommit
    }

    def "import continuation uploads once then commits exactly once"() {
        given:
        settingsMap.enableWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L
        String importJson = '{"deviceReplacements":{},"appReplacements":{"42":{"appLabel":"Source Rule","appTypeName":"Rule-5.1"}}}'
        hubGet.register('/installedapp/configure/json/100') { params ->
            nativeRuleConfig(100, 'Existing Rule', 21)
        }
        hubGet.register('/apps/api/4242/app/100') { params -> '<html>source-context</html>' }
        hubGet.register('/installedapp/configure/json/4242/main') { params ->
            clonerPageState('importRule', 55)
        }
        int parentReads = 0
        hubGet.register('/installedapp/configure/json/21') { params ->
            parentReads++
            parentReads == 1
                ? nativeParentConfig(21, [[id: 100, label: 'Existing Rule']])
                : nativeParentConfig(21, [[id: 100, label: 'Existing Rule'],
                                          [id: 700, label: 'Source Rule import']])
        }
        script.metaClass.hubInternalGetRaw = { String path, Map query = null, Integer timeout = 30 ->
            [status: 302, location: '/apps/api/4242/app/100', data: '']
        }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer timeout = 420 ->
            posts << [path: path, body: new LinkedHashMap(body)]
            [status: 200, location: null, data: '{"status":"success"}']
        }
        script.metaClass.hubInternalPostFormRaw = { String path, String body, Integer timeout = 420 ->
            posts << [path: path, body: decodeForm(body)]
            [status: 200, location: null, data: '{"status":"success"}']
        }
        def args = [jsonContent: importJson, parentHintAppId: 100, confirm: true]

        when: 'preflight allocates state without uploading or committing'
        def preflight = modernCall('hub_import_native_app', args)
        String requestState = preflight.result.requestState

        then:
        preflight.result.resultType == 'input_required'
        posts.isEmpty()
        atomicStateMap.mrtrRequests[requestState].checkpoint == null

        when: 'the first resume uploads once and checkpoints import_commit'
        def uploaded = modernCall('hub_import_native_app', args, requestState)
        def uploads = posts.findAll {
            it.path == '/installedapp/update/json' &&
                it.body['settings[ruleUpload]'] == importJson
        }

        then:
        uploaded.result.resultType == 'input_required'
        uploaded.result.requestState == requestState
        atomicStateMap.mrtrRequests[requestState].checkpoint.phase == 'import_commit'
        atomicStateMap.mrtrRequests[requestState].checkpoint.clonerAppId == 4242
        uploads.size() == 1
        !posts.any { it.path == '/installedapp/btn' && it.body.name == 'importNow' }

        when: 'the next resume commits and a replay cannot upload or commit again'
        def completed = modernCall('hub_import_native_app', args, requestState)
        def completedInner = mcpDriver.parseInner(completed)
        int postsAfterCommit = posts.size()
        def replay = modernCall('hub_import_native_app', args, requestState)
        def importNowClicks = posts.findAll {
            it.path == '/installedapp/btn' && it.body.name == 'importNow'
        }
        def commitNavigations = posts.findAll {
            it.path == '/installedapp/update/json' &&
                it.body.containsKey('_action_href_name|importRule|55')
        }

        then:
        completed.result.resultType == 'complete'
        completed.result.isError != true
        completedInner.success == true
        completedInner.newAppId == 700
        completedInner.originalSourceId == 42
        completedInner.mrtr.rounds == 2
        uploads.size() == 1
        commitNavigations.size() == 1
        importNowClicks.size() == 2
        replay.result.resultType == 'complete'
        mcpDriver.parseInner(replay).newAppId == 700
        posts.size() == postsAfterCommit
    }

    def "detached worker schedule failure becomes a retained terminal error without running the leaf"() {
        given:
        settingsMap.enableWrite = true
        def args = [appId: 777, confirm: true, settings: [description: 'no scheduler']]
        def preflight = modernCall('hub_set_rule', args)
        String stateId = preflight.result.requestState
        def leafCalls = new AtomicInteger(0)
        script.metaClass.toolSetRule = { Map actual ->
            leafCalls.incrementAndGet()
            [success: true, appId: actual.appId]
        }
        RUN_IN_OVERRIDE.set({ List call -> throw new IllegalStateException('scheduler unavailable') })

        when:
        def failed = modernCall('hub_set_rule', args, stateId)
        def failedInner = mcpDriver.parseInner(failed)
        def replay = modernCall('hub_set_rule', args, stateId)
        def replayInner = mcpDriver.parseInner(replay)

        then:
        failed.result.resultType == 'complete'
        failed.result.isError == true
        failedInner.status == 'schedule_failed'
        failedInner.error.contains('scheduler unavailable')
        replay.result.resultType == 'complete'
        replay.result.isError == true
        replayInner.status == 'schedule_failed'
        leafCalls.get() == 0
        script._activeWrites().isEmpty()
    }

    def "maxConcurrentWrites zero disables the write cap"() {
        given:
        settingsMap.enableWrite = true
        settingsMap.maxConcurrentWrites = 0

        expect:
        modernCall('hub_call_rule', [ruleId: [41, 42], action: 'stop']).result.resultType == 'input_required'
        modernCall('hub_call_rule', [ruleId: [51, 52], action: 'stop']).result.resultType == 'input_required'
    }

    def "a terminal MRTR call releases its write slot"() {
        given:
        settingsMap.enableWrite = true
        settingsMap.maxConcurrentWrites = 1
        script.metaClass.toolRunRmRule = { Map a ->
            [success: true, partial: false, ruleIds: a.ruleId,
             rmAction: 'stopRule toggle x1', results: a.ruleId.collect { [success: true, ruleId: it] }]
        }
        def args = [ruleId: [61, 62], action: 'stop']
        def first = modernCall('hub_call_rule', args)

        when:
        def complete = modernCall('hub_call_rule', args, first.result.requestState as String)
        def next = modernCall('hub_call_rule', [ruleId: [71, 72], action: 'stop'])

        then:
        complete.result.resultType == 'complete'
        next.result.resultType == 'input_required'
    }

    def "ordinary write-request leases share the same cap and release in finally"() {
        given:
        settingsMap.enableWrite = true
        settingsMap.maxConcurrentWrites = 1
        def activeDuring = []
        script.metaClass.toolRunRmRule = { Map a ->
            activeDuring.addAll((atomicStateMap.writeRequestLeases ?: [:]).values().findAll { it instanceof Map })
            [success: true, ruleIds: a.ruleId, results: []]
        }

        when:
        def response = mcpDriver.callTool('hub_call_rule', [ruleId: [81, 82], action: 'stop'])

        then:
        response.error == null
        activeDuring*.tool == ['hub_call_rule']
        !(atomicStateMap.writeRequestLeases ?: [:]).values().any { it instanceof Map }
    }

    def "write-reservation state is served from a write-through snapshot of atomicState"() {
        given:
        settingsMap.maxConcurrentWrites = 1

        when: 'a lease is taken through the ordinary reservation path'
        Map reservation = script._writeReserveRequest('hub_call_device_command', 'legacy') as Map

        then: 'atomicState carries it, so nothing in flight is lost to a reload'
        reservation.accepted == true
        (atomicStateMap.writeRequestLeases as Map)[reservation.leaseId as String].tool ==
            'hub_call_device_command'

        when: 'atomicState is rewritten behind the snapshot, as only another JVM could'
        atomicStateMap.writeRequestLeases = [seeded: [
            tool: 'hub_set_variable', transport: 'modern',
            startedAt: 1234567889000L, expiresAt: 1234567990000L
        ]]

        then: 'the loaded class keeps reading its own snapshot'
        script._activeWrites()*.tool == ['hub_call_device_command']

        when: 'a recompile/restart drops the snapshot'
        script._writeStateCacheInvalidate()

        then: 'atomicState is the read path again'
        script._activeWrites()*.tool == ['hub_set_variable']

        cleanup:
        if (reservation?.leaseId) script._writeReleaseRequest(reservation.leaseId as String)
    }

    def "an active write lease refuses a parallel device command before dispatch"() {
        given:
        settingsMap.enableWrite = true
        settingsMap.enableMandatoryBPS = false
        settingsMap.maxConcurrentWrites = 1
        atomicStateMap.writeRequestLeases = [busy: [
            tool: 'hub_set_variable', transport: 'modern',
            startedAt: 1234567889000L, expiresAt: 1234567990000L
        ]]
        script._writeStateCacheInvalidate()
        def ran = 0
        script.metaClass.toolCallDeviceCommand = { Map a -> ran++; [success: true] }

        when:
        def response = mcpDriver.callTool('hub_call_device_command', [deviceId: '1', command: 'on'])
        def inner = mcpDriver.parseInner(response)

        then:
        response.error == null
        response.result.isError == true
        inner.status == 'too_many_writes_in_flight'
        inner.active*.tool == ['hub_set_variable']
        ran == 0
    }

    def "the hub LED identify read ignores the write cap"() {
        given:
        settingsMap.enableRead = true
        settingsMap.maxConcurrentWrites = 1
        atomicStateMap.writeRequestLeases = [busy: [
            tool: 'hub_call_device_command', transport: 'legacy',
            startedAt: 1234567889000L, expiresAt: 1234567990000L
        ]]
        script._writeStateCacheInvalidate()
        def seen = null
        script.metaClass.toolGetHubInfo = { Map a -> seen = new LinkedHashMap(a); [identifyHubTriggered: true] }

        when:
        def response = mcpDriver.callTool('hub_get_info', [identifyHub: true])
        def inner = mcpDriver.parseInner(response)

        then:
        response.error == null
        response.result.isError != true
        inner.identifyHubTriggered == true
        seen == [identifyHub: true]
    }

    def "mixed-mode metrics count only snapshot writes and firmware status polls stay read-shaped"() {
        given:
        settingsMap.enableRead = true
        settingsMap.enableWrite = true
        settingsMap.maxConcurrentWrites = 1
        atomicStateMap.writeRequestLeases = [busy: [
            tool: 'hub_call_device_command', transport: 'legacy',
            startedAt: 1234567889000L, expiresAt: 1234567990000L
        ]]
        script._writeStateCacheInvalidate()
        def metricsArgs = []
        def firmwareArgs = []
        script.metaClass.toolGetHubPerformance = { Map a -> metricsArgs << new LinkedHashMap(a); [success: true] }
        script.metaClass.toolUpdateFirmware = { Map a -> firmwareArgs << new LinkedHashMap(a); [success: true, statusOnly: a.statusOnly == true] }

        when:
        def metricsRead = mcpDriver.callTool('hub_get_metrics', [recordSnapshot: false])
        def metricsWrite = mcpDriver.callTool('hub_get_metrics', [recordSnapshot: true])
        def firmwareRead = mcpDriver.callTool('hub_update_firmware', [statusOnly: true])
        def firmwareWrite = mcpDriver.callTool('hub_update_firmware', [confirm: true])

        then:
        metricsRead.result.isError != true
        metricsArgs == [[recordSnapshot: false]]
        mcpDriver.parseInner(metricsWrite).status == 'too_many_writes_in_flight'
        firmwareRead.result.isError != true
        firmwareArgs == [[statusOnly: true]]
        mcpDriver.parseInner(firmwareWrite).status == 'too_many_writes_in_flight'
    }

    def "a nonexpired package marker with unrelated terminal evidence keeps its write slot"() {
        given:
        settingsMap.maxConcurrentWrites = 1
        atomicStateMap.packageDeployInFlight = [
            requestId: 'pkg-live', ref: 'feat/live', startedAt: 1234567800000L,
            phase: 'queued', expiresAt: 1234567920000L, args: [ref: 'feat/live']
        ]
        script._writeStateCacheInvalidate()
        atomicStateMap.lastSelfDeploy = [
            success: true, sourceMode: 'importUrl', at: 1234567889000L
        ]

        when:
        Map reservation = script._writeReserveRequest('hub_call_device_command', 'legacy') as Map

        then:
        reservation.accepted == false
        reservation.refusal.status == 'too_many_writes_in_flight'
        reservation.refusal.active == [[
            tool: 'hub_update_package', startedAt: 1234567800000L, transport: 'background'
        ]]
    }

    def "an expired inactive package marker is removed before ordinary write admission"() {
        given:
        settingsMap.maxConcurrentWrites = 1
        atomicStateMap.packageDeployInFlight = [
            requestId: 'pkg-dead', ref: 'feat/dead', startedAt: 1234567200000L,
            phase: 'queued', expiresAt: 1234567889999L, args: [ref: 'feat/dead']
        ]
        script._writeStateCacheInvalidate()

        when:
        Map reservation = script._writeReserveRequest('hub_call_device_command', 'legacy') as Map

        then:
        reservation.accepted == true
        atomicStateMap.packageDeployInFlight == null
        script._activeWrites()*.tool == ['hub_call_device_command']

        cleanup:
        if (reservation?.leaseId) script._writeReleaseRequest(reservation.leaseId as String)
    }

    def "matching package terminal evidence releases an orphaned write slot"() {
        given:
        settingsMap.maxConcurrentWrites = 1
        atomicStateMap.packageDeployInFlight = [
            requestId: 'pkg-finished', ref: 'feat/finished',
            startedAt: 1234567880000L, args: [ref: 'feat/finished']
        ]
        script._writeStateCacheInvalidate()
        atomicStateMap.lastSelfDeploy = [
            success: true, sourceMode: 'package', requestId: 'pkg-finished',
            ref: 'feat/finished', at: 1234567889000L
        ]

        when:
        Map reservation = script._writeReserveRequest('hub_call_device_command', 'legacy') as Map

        then:
        reservation.accepted == true
        script._activeWrites()*.tool == ['hub_call_device_command']
    }

    def "active request-state storage saturation refuses admission without evicting live work"() {
        given:
        settingsMap.maxConcurrentWrites = 0
        Map targetBinding = script._mrtrBinding('hub_call_rule', 'hub_call_rule',
            [ruleId: [921, 922], action: 'stop']) as Map
        atomicStateMap.mrtrRequests = (0..<16).collectEntries { int index ->
            Map b = script._mrtrBinding('hub_call_rule', 'hub_call_rule',
                [ruleId: [1000 + index, 2000 + index], action: 'stop']) as Map
            [("mrtr-seeded-active-${index}".toString()): [
                schemaVersion: 1, status: 'active', outerTool: 'hub_call_rule', leafTool: 'hub_call_rule',
                argDigest: b.argDigest,
                startedAt: 1234567880000L + index, updatedAt: 1234567880000L + index,
                expiresAt: 1234567990000L, rounds: 0, generation: 0
            ]]
        }
        script._writeStateCacheInvalidate()
        def before = atomicStateMap.mrtrRequests.keySet() as Set

        when:
        Map result = script._mrtrReserve('hub_call_rule', 'hub_call_rule', targetBinding) as Map

        then:
        result.accepted == false
        result.refusal.status == 'request_state_capacity'
        atomicStateMap.mrtrRequests.size() == 16
        atomicStateMap.mrtrRequests.keySet() as Set == before
        atomicStateMap.mrtrRequests.values().every { it.status == 'active' }
    }

    def "terminal request-state records are evicted before a new active reservation"() {
        given:
        settingsMap.maxConcurrentWrites = 0
        Map targetBinding = script._mrtrBinding('hub_call_rule', 'hub_call_rule',
            [ruleId: [931, 932], action: 'stop']) as Map
        atomicStateMap.mrtrRequests = (0..<15).collectEntries { int index ->
            [("mrtr-live-${index}".toString()): [
                schemaVersion: 1, status: 'active', outerTool: 'hub_call_rule', leafTool: 'hub_call_rule',
                argDigest: "seed-digest-${index}".toString(),
                startedAt: 1234567880000L + index, updatedAt: 1234567880000L + index,
                expiresAt: 1234567990000L, rounds: 0, generation: 0
            ]]
        }
        atomicStateMap.mrtrRequests['mrtr-old-terminal'] = [
            schemaVersion: 1, status: 'terminal', outerTool: 'hub_call_rule', leafTool: 'hub_call_rule',
            startedAt: 1234567800000L, updatedAt: 1234567800000L,
            expiresAt: 1234567990000L, terminalResult: [success: true]
        ]
        script._writeStateCacheInvalidate()

        when:
        Map result = script._mrtrReserve('hub_call_rule', 'hub_call_rule', targetBinding) as Map

        then:
        result.accepted == true
        atomicStateMap.mrtrRequests.size() == 16
        !atomicStateMap.mrtrRequests.containsKey('mrtr-old-terminal')
        atomicStateMap.mrtrRequests[result.stateId].status == 'active'
    }

    def "expired active request-state cleanup runs before its storage slot is reused"() {
        given:
        settingsMap.maxConcurrentWrites = 0
        def cleanupPaths = []
        script.metaClass.hubInternalGetRaw = { String path, Map params = null, Integer timeout = 30 ->
            cleanupPaths << path
            [status: 302, data: '']
        }
        atomicStateMap.mrtrRequests = ['mrtr-expired-with-helper': [
            schemaVersion: 1, status: 'active', outerTool: 'hub_clone_native_app',
            leafTool: 'hub_clone_native_app', startedAt: 1L, updatedAt: 1L, expiresAt: 2L,
            rounds: 0, generation: 0, checkpoint: [clonerAppId: 77]
        ]]
        script._writeStateCacheInvalidate()
        Map binding = script._mrtrBinding('hub_call_rule', 'hub_call_rule',
            [ruleId: [941, 942], action: 'stop']) as Map

        when:
        Map result = script._mrtrReserve('hub_call_rule', 'hub_call_rule', binding) as Map

        then:
        result.accepted == true
        cleanupPaths == ['/installedapp/forcedelete/77/quiet']
        !atomicStateMap.mrtrRequests.containsKey('mrtr-expired-with-helper')
    }

    def "requestState is bound to the exact leaf tool and original arguments"() {
        given:
        settingsMap.enableWrite = true
        def ran = 0
        script.metaClass.toolRunRmRule = { Map a -> ran++; [success: true, ruleIds: a.ruleId, results: []] }
        def first = modernCall('hub_call_rule', [ruleId: [31, 32], action: 'stop'])
        String requestState = first.result.requestState

        when: 'the client changes the original arguments'
        def mismatchedArgs = modernCall('hub_call_rule', [ruleId: [31, 99], action: 'stop'], requestState)

        then:
        mismatchedArgs.error.code == -32602
        mismatchedArgs.error.message.contains('requestState')
        ran == 0

        when: 'the client changes the leaf tool'
        def mismatchedTool = modernCall('hub_set_rule', [appId: 31, confirm: true, settings: [x: 1]], requestState)

        then:
        mismatchedTool.error.code == -32602
        mismatchedTool.error.message.contains('requestState')
        ran == 0
    }

    def "requestState binding includes a changed best-practice acknowledgment"() {
        given:
        settingsMap.enableWrite = true
        def original = [ruleId: [81, 82], action: 'stop', bestPracticeKey: 'original-key']
        def first = modernCall('hub_call_rule', original)

        when:
        def changed = modernCall('hub_call_rule',
            [ruleId: [81, 82], action: 'stop', bestPracticeKey: 'different-key'],
            first.result.requestState as String)

        then:
        changed.error.code == -32602
        changed.error.message.contains('requestState')
    }

    def "SHA-256 request binding separates payloads that collide under the legacy compact tuple"() {
        given: 'two equal-length payloads with identical 48-char edges and both Java hashes equal'
        String payloadA = 'PPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPP' +
            'AaBBAaAaBBBBAaBBBBBBAaAaBBBBBBAaBBAaBBBBAaAaBBBBAaBBAaAaAaBBBBAaBBAaAaBBBBBBBBBBAaAaAaBBBBBBAaAaBBBBBBBBBBBBBBAaBBAaBBBBAaBBAaBB' +
            'SSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSS'
        String payloadB = 'PPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPPP' +
            'BBAaBBAaBBBBBBAaBBBBAaBBBBAaAaBBAaAaBBAaBBBBAaBBBBAaAaBBAaAaBBBBBBAaBBBBBBBBBBBBAaAaBBAaBBAaBBBBAaAaBBBBBBBBAaBBAaBBBBBBAaAaAaBB' +
            'SSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSSS'
        String oldA = groovy.json.JsonOutput.toJson([importJson: payloadA])
        String oldB = groovy.json.JsonOutput.toJson([importJson: payloadB])

        expect:
        oldA != oldB
        oldA.length() == oldB.length()
        oldA.substring(0, 48) == oldB.substring(0, 48)
        oldA.substring(oldA.length() - 48) == oldB.substring(oldB.length() - 48)
        oldA.hashCode() == oldB.hashCode()
        oldA.reverse().hashCode() == oldB.reverse().hashCode()
        script._mrtrSha256('abc') ==
            'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad'
        script._mrtrBinding('hub_import_native_app', 'hub_import_native_app',
            [importJson: payloadA]).argDigest !=
            script._mrtrBinding('hub_import_native_app', 'hub_import_native_app',
                [importJson: payloadB]).argDigest
    }

    def "large import binding persists only a fixed-size digest"() {
        given:
        settingsMap.maxConcurrentWrites = 0
        String importJson = '{"apps":[' + ('x' * 200000) + ']}'
        Map binding = script._mrtrBinding('hub_import_native_app',
            'hub_import_native_app', [importJson: importJson]) as Map

        when:
        Map admitted = script._mrtrReserve('hub_import_native_app',
            'hub_import_native_app', binding) as Map
        Map stored = atomicStateMap.mrtrRequests[admitted.stateId] as Map

        then:
        admitted.accepted == true
        binding.argDigest ==~ /[0-9a-f]{64}/
        stored.argDigest == binding.argDigest
        !stored.toString().contains(importJson.substring(0, 100))
        stored.keySet().intersect(['canonicalArgs', 'originalArgs', 'importJson']).isEmpty()
    }

    def "unknown and expired requestState fail without dispatching a write"() {
        given:
        settingsMap.enableWrite = true
        def ran = 0
        script.metaClass.toolRunRmRule = { Map a -> ran++; [success: true] }
        def original = [ruleId: [41, 42], action: 'stop']

        when:
        def unknown = modernCall('hub_call_rule', original, 'mrtr-does-not-exist-1234567890')

        then:
        unknown.error.code == -32602
        unknown.error.message.contains('Invalid or expired requestState')
        ran == 0

        when:
        def first = modernCall('hub_call_rule', original)
        String stateId = first.result.requestState
        atomicStateMap.mrtrRequests[stateId].expiresAt = 1L
        script._writeStateCacheInvalidate()
        def expired = modernCall('hub_call_rule', original, stateId)

        then:
        expired.error.code == -32602
        expired.error.message.contains('Invalid or expired requestState')
        ran == 0
    }

    def "legacy slow write keeps its ordinary remainder result and creates no MRTR state"() {
        given:
        settingsMap.enableWrite = true
        def ran = 0
        script.metaClass.toolRunRmRule = { Map a ->
            ran++
            [success: false, partial: true, ruleIds: [51, 52], results: [[success: true, ruleId: 51]], remainingRuleIds: [52]]
        }

        when:
        def response = mcpDriver.callTool('hub_call_rule', [ruleId: [51, 52], action: 'stop'])
        def inner = mcpDriver.parseInner(response)

        then:
        response.result.resultType == null
        inner.remainingRuleIds == [52]
        ran == 1
        !(atomicStateMap.mrtrRequests instanceof Map) || atomicStateMap.mrtrRequests.isEmpty()
    }
}
