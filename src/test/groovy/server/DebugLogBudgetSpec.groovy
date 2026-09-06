package server

import groovy.json.JsonOutput
import spock.lang.Shared
import support.TestChildApp
import support.ToolSpecBase

class DebugLogBudgetSpec extends ToolSpecBase {
    @Shared private TestChildApp loggingApp = new TestChildApp(id: 402L)

    def setupSpec() {
        appExecutor.getApp() >> loggingApp
    }

    def setup() {
        settingsMap.mcpLogLevel = 'error'
        settingsMap.relayBudgetMs = 6000
        script.metaClass._isCloudRequest = { -> true }
        script.log.messages.clear()
    }

    private List coldHistory() {
        script.mcpLog('error', 'server', 'retained error', '42', [details: [tool: 'hub_set_rule', nested: [id: 7]]])
        def line = script.log.messages.last()
        def rows = ["2026-09-06 12:00:00.000\tERROR\tapp|402|MCP|${line.substring(line.indexOf(':') + 1)}".toString()]
        (scriptStaticField('DEBUG_LOG_BUFFERS') as Map).clear()
        return rows
    }

    def "elapsed request budget schedules full timeout recovery without foreground HTTP and retry gets complete history"() {
        given:
        def rows = coldHistory()
        def pauses = []
        PAUSE_EXECUTION_OVERRIDE.set({ Long ms -> pauses << ms })

        when:
        def pending = script.getDebugLogReadResult([__reqT0: 1234567880000L])

        then:
        pending.entries == null
        pending.status == 'in_progress'
        !pending.error
        pending.retryable
        hubGet.calls.empty
        pauses.empty
        runInMillisCalls.size() == 1
        runInMillisCalls[0][1] == 'runDebugLogHistoryFetch'

        when:
        script.metaClass.hubInternalGet = { String path, Map query, Integer timeout ->
            assert path == '/logs/past/json'
            assert query == [type: 'app', id: '402']
            assert timeout == 30
            JsonOutput.toJson(rows)
        }
        script.runDebugLogHistoryFetch(runInMillisCalls[0][2].data as Map)
        def recovered = script.getDebugLogEntries([__reqT0: 1234567880000L])

        then:
        recovered*.message == ['retained error']
        recovered[0].ruleId == '42'
        recovered[0].details == [tool: 'hub_set_rule', nested: [id: 7]]
        runInMillisCalls.size() == 1
    }

    def "clear waits for recovery then fences a replayed worker after mutation"() {
        given:
        def rows = coldHistory()
        script.getDebugLogReadResult([__reqT0: 1234567880000L])
        def job = runInMillisCalls[0][2].data as Map
        hubGet.register('/logs/past/json') { params -> JsonOutput.toJson(rows) }
        def generation = atomicStateMap.debugLogGeneration

        when:
        def pending = script.toolClearDebugLogs([__reqT0: 1234567880000L])

        then:
        pending.status == 'in_progress'
        atomicStateMap.debugLogGeneration == generation
        hubGet.calls.empty

        when:
        script.runDebugLogHistoryFetch(job)
        def cleared = script.toolClearDebugLogs([__reqT0: 1234567880000L])
        script.runDebugLogHistoryFetch(job)

        then:
        cleared.success
        cleared.clearedCount == 1
        atomicStateMap.debugLogGeneration != generation
        script.getDebugLogEntries([__reqT0: 1234567880000L]) == []
    }

    def "stale scheduled recovery cannot overwrite the replacement worker"() {
        given:
        def rows = coldHistory()
        def clock = new java.util.concurrent.atomic.AtomicLong(1234567890000L)
        NOW_OVERRIDE.set({ -> clock.get() })
        script.getDebugLogReadResult([__reqT0: clock.get() - 10000L])
        def oldJob = runInMillisCalls[0][2].data as Map
        clock.addAndGet(120000L)
        script.getDebugLogReadResult([__reqT0: clock.get() - 10000L])
        def newJob = runInMillisCalls[1][2].data as Map
        hubGet.register('/logs/past/json') { params -> JsonOutput.toJson(rows) }

        when:
        script.runDebugLogHistoryFetch(oldJob)

        then:
        hubGet.calls.empty

        when:
        script.runDebugLogHistoryFetch(newJob)

        then:
        script.getDebugLogEntries([__reqT0: clock.get() - 10000L])*.message == ['retained error']
    }

    def "failed recovery reports the failure while scheduling a retry that can complete"() {
        given:
        def rows = coldHistory()
        script.getDebugLogReadResult([__reqT0: 1234567880000L])
        hubGet.register('/logs/past/json') { params -> throw new IllegalStateException('503 native history unavailable') }

        when:
        script.runDebugLogHistoryFetch(runInMillisCalls[0][2].data as Map)
        def failed = script.getDebugLogReadResult([__reqT0: 1234567880000L])

        then:
        failed.error.contains('503 native history unavailable')
        failed.retryable
        runInMillisCalls.size() == 2

        when:
        hubGet.register('/logs/past/json') { params -> JsonOutput.toJson(rows) }
        script.runDebugLogHistoryFetch(runInMillisCalls[1][2].data as Map)

        then:
        script.getDebugLogEntries([__reqT0: 1234567880000L])*.message == ['retained error']
    }
}
