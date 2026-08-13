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
        def failure = new AtomicReference()
        def virtualNow = new AtomicLong(1234567890000L)
        NOW_OVERRIDE.set({ -> virtualNow.get() })
        PAUSE_EXECUTION_OVERRIDE.set({ Long delayMs -> virtualNow.addAndGet(delayMs) })
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
        runInCalls.size() == 1
        runInCalls[0][0..1] == [1, 'runMrtrSlice']
        runInCalls[0][2].overwrite == false
        runInCalls[0][2].data.stateId == stateId
        runInCalls[0][2].data.claimId instanceof String
        !runInCalls[0][2].data.containsKey('arguments')

        when: 'another write arrives while the claimed worker is only queued'
        def capped = modernCall('hub_call_rule', [ruleId: [401, 402], action: 'stop'])
        def cappedInner = mcpDriver.parseInner(capped)

        then: 'queued work still occupies the all-write concurrency slot'
        capped.result.isError == true
        cappedInner.status == 'too_many_writes_in_flight'
        cappedInner.active == [[tool: 'hub_set_rule', startedAt: 1234567890000L, transport: 'mrtr']]

        when: 'Hubitat invokes the scheduled worker and its real leaf remains blocked'
        Map workerData = new LinkedHashMap(runInCalls[0][2].data as Map)
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

    def "fresh duplicate is refused while an MRTR operation is active without disclosing its state"() {
        given:
        settingsMap.enableWrite = true
        def ran = 0
        script.metaClass.toolRunRmRule = { Map a -> ran++; [success: true, ruleIds: a.ruleId, results: []] }
        def original = [ruleId: [21, 22], action: 'stop']

        when:
        def first = modernCall('hub_call_rule', original)
        def duplicate = modernCall('hub_call_rule', original)
        def inner = mcpDriver.parseInner(duplicate)

        then:
        first.result.resultType == 'input_required'
        duplicate.result.resultType == 'complete'
        duplicate.result.isError == true
        inner.status == 'duplicate_in_flight'
        inner.tool == 'hub_call_rule'
        !inner.containsKey('requestState')
        !inner.containsKey('opToken')
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
        attempts.count { it.result?.resultType == 'input_required' } == 1
        attempts.count { it.result?.isError == true &&
            mcpDriver.parseInner(it).status == 'duplicate_in_flight' } == 23
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
        virtualNow.get() >= 1234567890000L + 3L * 6000L

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

        and: 'synchronous slices retain half their request budget for actual leaf work'
        script._mrtrContentionWaitMs('hub_call_rule') == 4000L

        when: 'an operator configures a smaller cloud leg budget'
        settingsMap.relayBudgetMs = 5000

        then: 'the worker path keeps 2000ms for parsing, scheduling, and rendering'
        script._mrtrContentionWaitMs('hub_set_rule') == 3000L
        script._mrtrContentionWaitMs('hub_call_rule') == 2500L

        when: 'the same explicit budget is applied to a LAN request'
        script.metaClass._isCloudRequest = { -> false }
        settingsMap.lanBudgetMs = 5000

        then: 'LAN retains its prior 1500ms reserve because the measured defect is cloud-only'
        script._mrtrContentionWaitMs('hub_set_rule') == 3500L
    }

    def "gateway native-app resume schedules the resolved leaf and its worker returns the terminal result"() {
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

        when:
        def scheduled = modernCall(gateway, args, stateId)
        assert runInCalls.size() == 1
        Map workerData = new LinkedHashMap(runInCalls[0][2].data as Map)
        script.runMrtrSlice(workerData)
        def complete = modernCall(gateway, args, stateId)
        def inner = mcpDriver.parseInner(complete)

        then:
        scheduled.result.resultType == 'input_required'
        seen == [[appId: 654, confirm: true, settings: [description: 'gateway worker']]]
        complete.result.resultType == 'complete'
        complete.result.isError != true
        inner.appId == 654
        inner.success == true
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

    def "an active write lease refuses a parallel device command before dispatch"() {
        given:
        settingsMap.enableWrite = true
        settingsMap.enableMandatoryBPS = false
        settingsMap.maxConcurrentWrites = 1
        atomicStateMap.writeRequestLeases = [busy: [
            tool: 'hub_set_variable', transport: 'modern',
            startedAt: 1234567889000L, expiresAt: 1234567990000L
        ]]
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

    def "an aged package marker with unrelated terminal evidence keeps its write slot"() {
        given:
        settingsMap.maxConcurrentWrites = 1
        atomicStateMap.packageDeployInFlight = [
            requestId: 'pkg-live', ref: 'feat/live', startedAt: 1234567200000L, args: [ref: 'feat/live']
        ]
        atomicStateMap.lastSelfDeploy = [
            success: true, sourceMode: 'importUrl', at: 1234567889000L
        ]

        when:
        Map reservation = script._writeReserveRequest('hub_call_device_command', 'legacy') as Map

        then:
        reservation.accepted == false
        reservation.refusal.status == 'too_many_writes_in_flight'
        reservation.refusal.active == [[
            tool: 'hub_update_package', startedAt: 1234567200000L, transport: 'background'
        ]]
    }

    def "matching package terminal evidence releases an orphaned write slot"() {
        given:
        settingsMap.maxConcurrentWrites = 1
        atomicStateMap.packageDeployInFlight = [
            requestId: 'pkg-finished', ref: 'feat/finished',
            startedAt: 1234567880000L, args: [ref: 'feat/finished']
        ]
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
