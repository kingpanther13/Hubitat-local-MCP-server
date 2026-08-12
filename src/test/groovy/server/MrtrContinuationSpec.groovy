package server

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    def "concurrent cap-one device-write reservations admit exactly one before dispatch"() {
        given:
        settingsMap.maxConcurrentWrites = 1

        when:
        def attempts = race(24) {
            script._writeReserveRequest('hub_call_device_command', 'legacy') as Map
        }

        then:
        attempts.count { it.accepted == true } == 1
        attempts.count { it.refusal?.status == 'too_many_writes_in_flight' } == 23
        (atomicStateMap.writeRequestLeases as Map).values().count { it instanceof Map } == 1
    }

    def "concurrent identical MRTR preflights create one state and refuse every duplicate"() {
        given:
        settingsMap.maxConcurrentWrites = 0
        def args = [ruleId: [901, 902], action: 'stop']
        Map binding = script._mrtrBinding('hub_call_rule', 'hub_call_rule', args) as Map

        when:
        def attempts = race(24) {
            script._mrtrReserve('hub_call_rule', 'hub_call_rule', binding) as Map
        }

        then:
        attempts.count { it.accepted == true } == 1
        attempts.count { it.refusal?.status == 'duplicate_in_flight' } == 23
        (atomicStateMap.mrtrRequests as Map).values().count { it?.status == 'active' } == 1
    }

    def "concurrent repeats claim one MRTR generation and never both own its slice"() {
        given:
        def args = [ruleId: [911, 912], action: 'stop']
        Map binding = script._mrtrBinding('hub_call_rule', 'hub_call_rule', args) as Map
        Map admitted = script._mrtrReserve('hub_call_rule', 'hub_call_rule', binding) as Map

        when:
        def claims = race(2) {
            script._mrtrClaim(admitted.stateId as String, 'hub_call_rule', 'hub_call_rule', binding) as Map
        }

        then:
        claims.count { it.outcome == 'claimed' } == 1
        claims.count { it.outcome == 'in_progress' } == 1
        atomicStateMap.mrtrRequests[admitted.stateId].generation == 0
        atomicStateMap.mrtrRequests[admitted.stateId].claimedGeneration == 0
        atomicStateMap.mrtrRequests[admitted.stateId].claimId == claims.find { it.outcome == 'claimed' }.claimId
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

    def "an unrelated newer self-deploy result does not release a package worker write slot"() {
        given:
        settingsMap.maxConcurrentWrites = 1
        atomicStateMap.packageDeployInFlight = [
            requestId: 'pkg-live', ref: 'feat/live', startedAt: 1234567880000L, args: [ref: 'feat/live']
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
            tool: 'hub_update_package', startedAt: 1234567880000L, transport: 'background'
        ]]
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
                argHash: b.argHash, argReverseHash: b.argReverseHash, argLength: b.argLength,
                argPrefix: b.argPrefix, argSuffix: b.argSuffix,
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
                argHash: index, argReverseHash: -index, argLength: index + 1,
                argPrefix: "p${index}", argSuffix: "s${index}",
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
        def cleaned = []
        script.metaClass._appClonerCleanup = { Integer id -> cleaned << id }
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
        cleaned == [77]
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
