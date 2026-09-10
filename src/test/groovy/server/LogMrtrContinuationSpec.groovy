package server

import groovy.json.JsonOutput
import java.util.concurrent.atomic.AtomicLong
import spock.lang.Shared
import support.TestChildApp
import support.ToolSpecBase

class LogMrtrContinuationSpec extends ToolSpecBase {
    @Shared private TestChildApp loggingApp = new TestChildApp(id: 402L)

    def setupSpec() { appExecutor.getApp() >> loggingApp }

    def setup() {
        settingsMap.enableRead = true
        settingsMap.enableWrite = true
        settingsMap.useGateways = true
        settingsMap.relayBudgetMs = 6000
        settingsMap.mcpLogLevel = 'error'
        script.metaClass._isCloudRequest = { -> true }
        def clock = new AtomicLong(1234567890000L)
        NOW_OVERRIDE.set({ -> clock.get() })
        PAUSE_EXECUTION_OVERRIDE.set({ Long ms -> clock.addAndGet(ms) })
    }

    private Map call(String outer, Map args, String stateId = null) {
        def params = [name: outer, arguments: args]
        if (stateId != null) params.requestState = stateId
        mcpDriver.pushHeaders(['MCP-Protocol-Version': '2026-07-28',
            'Mcp-Method': 'tools/call', 'Mcp-Name': outer])
        mcpDriver.pushBody([jsonrpc: '2.0', id: ++mcpDriver.lastSentId,
            method: 'tools/call', params: params])
        script.handleMcpRequest()
        mcpDriver.parseResponseJson() as Map
    }

    def "native log reads continue without foreground HTTP and preserve scoped full messages"() {
        given:
        settingsMap.enableWrite = false
        def text = 'diagnostic ' + ('x' * 12000)
        hubGet.register('/logs/past/json') { params ->
            JsonOutput.toJson(["2026-09-06 12:00:00.000\tERROR\tapp|42|Example|${text}".toString()])
        }
        def args = [tool: 'hub_get_logs', args: [appId: '42']]

        when:
        def first = call('hub_read_diagnostics', args)
        String stateId = first.result.requestState

        then:
        first.result.resultType == 'input_required'
        stateId
        hubGet.calls.empty
        script._activeWrites().empty
        runInMillisCalls.size() == 1
        runInMillisCalls[0][1] == 'runNativeLogFetch'
        runInMillisCalls[0][2].data.query == [type: 'app', id: '42']

        when:
        script.runNativeLogFetch(runInMillisCalls[0][2].data as Map)
        script.runNativeLogFetch(runInMillisCalls[0][2].data as Map)
        long fetchedAt = script.now()
        NOW_OVERRIDE.set({ -> fetchedAt + 29000L })
        def result = call('hub_read_diagnostics', args, stateId)

        then:
        result.result.resultType == 'complete'
        mcpDriver.parseInner(result).logs[0].message == "app|42|Example|${text}"
        atomicStateMap.mrtrRequests[stateId].terminalResult == [__slowReadReplay: true, tool: 'hub_get_logs']
        atomicStateMap.mrtrRequests[stateId].expiresAt == fetchedAt + 30000L

        when:
        def replay = call('hub_read_diagnostics', args, stateId)

        then:
        mcpDriver.parseInner(replay).logs[0].message == "app|42|Example|${text}"
        hubGet.calls.size() == 1
    }

    def "failed native workers do not repeat and the next call can recover"() {
        given:
        hubGet.register('/logs/past/json') { params -> throw new IllegalStateException('503 unavailable') }
        script._nativeLogSnapshot(null, [__reqT0: 1234567880000L])
        def job = runInMillisCalls[0][2].data as Map

        when:
        script.runNativeLogFetch(job)
        script.runNativeLogFetch(job)

        then:
        hubGet.calls.size() == 1

        when:
        script._nativeLogSnapshot(null, [__reqT0: 1234567880000L])

        then:
        def failure = thrown(IllegalStateException)
        failure.message.contains('503 unavailable')

        when:
        hubGet.register('/logs/past/json') { params -> '[]' }
        script._nativeLogSnapshot(null, [__reqT0: 1234567880000L])
        script.runNativeLogFetch(runInMillisCalls[1][2].data as Map)
        def recovered = script._nativeLogSnapshot(null, [:])

        then:
        recovered.state == 'ready'
        recovered.text == '[]'
        hubGet.calls.size() == 2
    }

    def "native log reads evict the oldest completed snapshot without disturbing pending workers"() {
        given:
        Map snapshots = scriptStaticField('NATIVE_LOG_SNAPSHOTS') as Map
        long timestamp = script.now()
        (0..<6).each { index ->
            snapshots.put("pending-${index}".toString(), [at: timestamp - 20000L, pending: true])
        }
        snapshots.put('old-ready', [at: timestamp - 10000L, pending: false, text: '[]'])
        snapshots.put('new-ready', [at: timestamp - 1000L, pending: false, text: '[]'])
        hubGet.register('/logs/past/json') { params -> '[]' }

        when:
        def first = script._nativeLogSnapshot([type: 'app', id: '42'], [__reqT0: timestamp - 10000L])

        then:
        first.state == 'pending'
        runInMillisCalls.size() == 1
        !snapshots.containsKey('old-ready')
        snapshots.containsKey('new-ready')
        (0..<6).every { index -> snapshots.get("pending-${index}".toString()).pending == true }
        snapshots.size() == 8
        hubGet.calls.empty

        when:
        script.runNativeLogFetch(runInMillisCalls[0][2].data as Map)
        def ready = script._nativeLogSnapshot([type: 'app', id: '42'], [:])

        then:
        ready.state == 'ready'
        ready.text == '[]'
        hubGet.calls.size() == 1
        runInMillisCalls.size() == 1
    }

    def "a full pool of pending reads rejects a native log call without empty continuation rounds"() {
        given:
        Map snapshots = scriptStaticField('NATIVE_LOG_SNAPSHOTS') as Map
        long timestamp = script.now()
        (0..<8).each { index ->
            snapshots.put("pending-${index}".toString(), [at: timestamp, pending: true])
        }

        when:
        def response = call('hub_read_diagnostics', [tool: 'hub_get_logs', args: [appId: '42']])

        then:
        response.error != null || response.result?.isError == true
        JsonOutput.toJson(response).contains('Background read capacity is full')
        response.result?.resultType != 'input_required'
        runInMillisCalls.empty
        hubGet.calls.empty
        snapshots.size() == 8
        script.now() == timestamp
    }

    def "MCP log history uses requestState and clearing waits before rotating history"() {
        given:
        script.mcpLog('error', 'server', 'retained error')
        def nativeLine = script.log.messages.last()
        def rows = ["2026-09-06 12:00:00.000\tERROR\tapp|402|MCP|${nativeLine.substring(nativeLine.indexOf(':') + 1)}".toString()]
        (scriptStaticField('DEBUG_LOG_BUFFERS') as Map).clear()
        hubGet.register('/logs/past/json') { params -> JsonOutput.toJson(rows) }
        def args = [tool: 'hub_get_logs', args: [mode: 'mcp']]

        when:
        def first = call('hub_read_diagnostics', args)

        then:
        first.result.resultType == 'input_required'
        hubGet.calls.empty

        when:
        def clearFirst = call('hub_delete_debug_logs', [:])
        String clearState = clearFirst.result.requestState
        def generation = atomicStateMap.debugLogGeneration
        def clearPending = call('hub_delete_debug_logs', [:], clearState)

        then:
        clearPending.result.resultType == 'input_required'
        atomicStateMap.debugLogGeneration == generation

        when:
        script.runDebugLogHistoryFetch(runInMillisCalls.find { it[1] == 'runDebugLogHistoryFetch' }[2].data as Map)
        def recovered = call('hub_read_diagnostics', args, first.result.requestState as String)
        def cleared = call('hub_delete_debug_logs', [:], clearState)
        def clearedGeneration = atomicStateMap.debugLogGeneration
        def replay = call('hub_delete_debug_logs', [:], clearState)

        then:
        mcpDriver.parseInner(recovered).entries*.message == ['retained error']
        mcpDriver.parseInner(cleared).clearedCount == 1
        clearedGeneration != generation
        mcpDriver.parseInner(replay).clearedCount == 1
        atomicStateMap.debugLogGeneration == clearedGeneration
    }
}
