package server

import groovy.json.JsonOutput
import spock.lang.Shared
import support.TestChildApp
import support.TestHub
import support.TestLocation
import support.ToolSpecBase
import support.PermissiveLog

class DiagnosticLogRecoverySpec extends ToolSpecBase {
    @Shared private TestChildApp loggingApp = new TestChildApp(id: 402L)
    @Shared private TestLocation loggingLocation = new TestLocation(hub: new TestHub())

    def setupSpec() {
        appExecutor.getApp() >> loggingApp
        appExecutor.getLocation() >> loggingLocation
    }

    def setup() {
        settingsMap.mcpLogLevel = 'error'
    }

    private void failColdHistory() {
        script.initDebugLogs()
        (scriptStaticField('DEBUG_LOG_BUFFERS') as Map).clear()
        hubGet.register('/logs/past/json') { params -> throw new IllegalStateException('503 history unavailable') }
    }

    def "hub info retains independent metadata when cold history is unavailable and retries later"() {
        given:
        failColdHistory()

        when:
        def result = script.toolGetHubInfo([:])

        then:
        result.mcpServerVersion == script.currentVersion()
        result.mcpDeviceCount == 0
        result.mcpLogEntries == null
        result.mcpLogReadError.contains('503 history unavailable')
        result.readEnabled == true

        when:
        hubGet.register('/logs/past/json') { params -> '[]' }
        def recovered = script.toolGetHubInfo([:])

        then:
        recovered.mcpLogEntries == 0
        !recovered.mcpLogReadError
    }

    def "bug report retains environment and issue context while marking unavailable logs"() {
        given:
        failColdHistory()

        when:
        def result = script.toolGenerateBugReport([title: 'Device command failed', expected: 'Light turns on', actual: 'Light stayed off'])

        then:
        result.success
        result.suggestedTitle.contains('Device command failed')
        result.report.contains('## Environment')
        result.report.contains('Light turns on')
        result.report.contains('MCP log history unavailable')
        !result.report.contains('No relevant errors logged')
        result.logs.error.contains('503 history unavailable')
        result.logs.relevantCount == null
    }

    def "malformed native history remains retryable for diagnostic readers"() {
        given:
        failColdHistory()
        hubGet.register('/logs/past/json') { params -> '{"unexpected":"object"}' }

        when:
        def failed = script.getDebugLogReadResult()

        then:
        failed.entries == null
        failed.error.contains('Unexpected native log history response')

        when:
        hubGet.register('/logs/past/json') { params -> '[]' }
        def retried = script.getDebugLogReadResult()

        then:
        retried.entries == []
        !retried.error
    }

    def "custom rule diagnostics retain rule and execution data when native logs fail"() {
        given:
        def child = new TestChildApp(id: 42L, label: 'Test rule')
        child.ruleData = [id: 42L, name: 'Test rule', enabled: true, executionCount: 9,
                          triggers: [[type: 'device']], conditions: [], actions: [[cmd: 'on']]]
        childAppsList << child
        failColdHistory()

        when:
        def result = script.toolGetRuleDiagnostics([ruleId: '42'])

        then:
        result.rule.name == 'Test rule'
        result.execution.count == 9
        result.structure.triggerCount == 1
        result.logReadError.contains('503 history unavailable')
        result.logs.recentCount == null
        result.logs.errorCount == null
    }

    def "clear succeeds despite failed cold recovery and prevents old rows returning later"() {
        given:
        script.log.messages.clear()
        script.mcpLog('error', 'server', 'old error')
        def line = script.log.messages.last()
        def nativeRows = ["2026-09-06 12:00:00.000\tERROR\tapp|402|MCP|${line.substring(line.indexOf(':') + 1)}".toString()]
        failColdHistory()
        def oldGeneration = atomicStateMap.debugLogGeneration

        when:
        def result = script.toolClearDebugLogs([:])

        then:
        result.success
        result.clearedCount == null
        result.countIncomplete == true
        result.logReadError.contains('503 history unavailable')
        atomicStateMap.debugLogGeneration != oldGeneration

        when:
        hubGet.register('/logs/past/json') { params -> JsonOutput.toJson(nativeRows) }
        (scriptStaticField('DEBUG_LOG_BUFFERS') as Map).clear()

        then:
        script.getDebugLogEntries() == []
    }

    def "failed native emission preserves legacy migration payload for retry"() {
        given:
        def entries = [[timestamp: 1L, level: 'error', component: 'server', message: 'legacy failure', details: [tool: 'hub_get_info']]]
        stateMap.debugLogs = [entries: entries, config: [logLevel: 'error', maxEntries: 100]]
        def failingLog = new PermissiveLog() {
            @Override void error(String message) { throw new IllegalStateException('native logging unavailable') }
        }
        def peer = newCompiledScriptInstance(app: loggingApp, state: stateMap, atomicState: atomicStateMap, log: failingLog)

        when:
        peer.initDebugLogs()

        then:
        def error = thrown(IllegalStateException)
        error.message == 'native logging unavailable'
        stateMap.debugLogs.entries == entries
        stateMap.debugLogs.config.logLevel == 'error'
        !(scriptStaticField('DEBUG_LOG_BUFFERS') as Map).containsKey('402')
    }
}
