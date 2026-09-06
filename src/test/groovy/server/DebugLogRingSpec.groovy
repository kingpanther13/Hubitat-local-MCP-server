package server

import groovy.json.JsonOutput
import spock.lang.Shared
import support.TestChildApp
import support.ToolSpecBase

class DebugLogRingSpec extends ToolSpecBase {
    @Shared private TestChildApp loggingApp = new TestChildApp(id: 402L)

    def setupSpec() {
        appExecutor.getApp() >> loggingApp
    }

    def setup() {
        assert (scriptStaticField('DEBUG_LOG_BUFFERS') as Map).isEmpty()
        assert script.app.id == 402L
        script.log.messages.clear()
    }

    private void reload() {
        (scriptStaticField('DEBUG_LOG_BUFFERS') as Map).clear()
    }

    private List nativeRows() {
        script.log.messages.findAll { it.contains('[MCP1]') }.collect { line ->
            def colon = line.indexOf(':')
            "2026-09-06 12:00:00.000\t${line.substring(0, colon).toUpperCase()}\tapp|402|MCP|${line.substring(colon + 1)}".toString()
        }
    }

    def "first suppressed log migrates every legacy level and preserves structured history after reload"() {
        given:
        stateMap.debugLogs = [config: [logLevel: 'warn', maxEntries: 100], entries: [
            [timestamp: 1L, level: 'info', component: 'server', message: 'history'],
            [timestamp: 2L, level: 'warn', component: 'server', message: 'failure', details: [tool: 'hub_get_info']]
        ]]

        when:
        script.mcpLog('debug', 'server', 'suppressed')

        then:
        !stateMap.debugLogs.containsKey('entries')
        script.getConfiguredLogLevel() == 'warn'
        script.getDebugLogEntries()*.message == ['history', 'failure']

        when:
        def rows = nativeRows()
        hubGet.register('/logs/past/json') { params ->
            assert params == [type: 'app', id: '402']
            JsonOutput.toJson(rows)
        }
        reload()

        then:
        script.getDebugLogEntries()*.message == ['history', 'failure']
        script.getDebugLogEntries()[1].details.tool == 'hub_get_info'
    }

    def "warm debug and info writes use native logs without reading or writing app state or files"() {
        given:
        settingsMap.mcpLogLevel = 'debug'
        script.initDebugLogs()
        def peer = newCompiledScriptInstance(app: loggingApp,
            state: { -> throw new AssertionError('warm state access') },
            atomicState: { -> throw new AssertionError('warm atomicState access') })
        peer.metaClass.uploadHubFile = { String name, byte[] data -> throw new AssertionError('file write') }

        when:
        (1..150).each { peer.mcpLog(it % 2 ? 'debug' : 'info', 'server', "line ${it}") }

        then:
        script.getDebugLogEntries().size() == 100
        script.getDebugLogEntries().first().message == 'line 51'
        stateMap.debugLogs.keySet() == ['config'] as Set
    }

    def "all admitted levels survive reload and clear watermark excludes old native rows"() {
        given:
        settingsMap.mcpLogLevel = 'debug'
        ['debug', 'info', 'warn', 'error'].each { script.mcpLog(it, 'server', it) }
        def rows = nativeRows()
        hubGet.register('/logs/past/json') { params -> JsonOutput.toJson(rows) }

        when:
        reload()

        then:
        script.getDebugLogEntries()*.level == ['debug', 'info', 'warn', 'error']

        when:
        def result = script.toolClearDebugLogs([:])
        reload()

        then:
        result.clearedCount == 4
        script.getDebugLogEntries() == []
    }

    def "structured fields preserve existing character limits and complete nested metadata"() {
        given:
        settingsMap.mcpLogLevel = 'debug'
        def message = '\u754c' * 700
        def trace = '\u754c' * 1200
        def component = 'component' * 20
        def ruleId = '42' * 80
        def ruleName = 'name' * 100
        def details = (1..12).collectEntries { ["key${it}".toString(), "value${it}".toString()] }
        details.nested = [one: [two: [three: [list: (1..15).collect { [payload: 'x' * 1500] }]]]]

        when:
        script.mcpLog('error', component, message, ruleId,
            [ruleName: ruleName, stackTrace: trace, duration: 123.5, details: details])

        then:
        def entry = script.getDebugLogEntries()[0]
        entry.message == '\u754c' * 500
        entry.stackTrace == '\u754c' * 1000
        entry.component == component
        entry.ruleId == ruleId
        entry.ruleName == ruleName
        entry.duration == 123.5
        entry.details == details

        when:
        def rows = nativeRows()
        hubGet.register('/logs/past/json') { params -> JsonOutput.toJson(rows) }
        reload()

        then:
        script.getDebugLogEntries()[0] == entry
    }

    def "ring retains one hundred entries even when their combined payload exceeds sixty four KiB"() {
        given:
        settingsMap.mcpLogLevel = 'debug'

        when:
        (1..100).each { n -> script.mcpLog('info', 'server', "line ${n}", null, [details: [payload: 'x' * 1500]]) }

        then:
        def entries = script.getDebugLogEntries()
        entries.size() == 100
        entries.first().message == 'line 1'
        entries.last().message == 'line 100'
        JsonOutput.toJson(entries).getBytes('UTF-8').length > 65536

        when:
        script.mcpLog('info', 'server', 'line 101')

        then:
        script.getDebugLogEntries().size() == 100
        script.getDebugLogEntries().first().message == 'line 2'
    }

    def "native envelope keeps the complete original message on a single line"() {
        given:
        settingsMap.mcpLogLevel = 'debug'
        def message = ('full message ' * 200) + '\nlast\tline'

        when:
        script.mcpLog('info', 'server', message)

        then:
        String line = script.log.messages.last()
        def envelope = new groovy.json.JsonSlurper().parseText(line.substring(line.indexOf('[MCP1] ') + 7))
        envelope.entry.message == message
        !line.contains('\n')
        !line.contains('\t')
        script.getDebugLogEntries()[0].message == message.take(500)
    }

    def "app instances cannot see or clear another app's ring"() {
        given:
        settingsMap.mcpLogLevel = 'debug'
        def firstState = [:]
        def secondState = [:]
        def firstAtomic = [:]
        def secondAtomic = [:]
        def first = newCompiledScriptInstance(app: new TestChildApp(id: 401L), state: firstState, atomicState: firstAtomic)
        def second = newCompiledScriptInstance(app: loggingApp, state: secondState, atomicState: secondAtomic)

        when:
        first.mcpLog('error', 'server', 'first app')
        second.mcpLog('error', 'server', 'second app')
        second.toolClearDebugLogs([:])

        then:
        first.getDebugLogEntries()*.message == ['first app']
        !second.getDebugLogEntries().any { it.message == 'first app' }
    }

    def "returned snapshots cannot mutate live history"() {
        given:
        settingsMap.mcpLogLevel = 'debug'
        def details = [tool: 'original', nested: [id: 1]]
        script.mcpLog('warn', 'server', 'original', null, [details: details])

        when:
        details.nested.id = 2
        def snapshot = script.getDebugLogEntries()
        snapshot[0].details.nested.id = 3
        snapshot.clear()

        then:
        script.getDebugLogEntries()[0].details.nested.id == 1
    }

    def "failed cold history fetch is retried without discarding live entries"() {
        given:
        settingsMap.mcpLogLevel = 'debug'
        script.mcpLog('debug', 'server', 'before reload')
        def rows = nativeRows()
        reload()
        script.mcpLog('info', 'server', 'after reload')
        hubGet.register('/logs/past/json') { params -> throw new IllegalStateException('503 unavailable') }

        when:
        script.getDebugLogEntries()

        then:
        thrown(IllegalStateException)

        when:
        hubGet.register('/logs/past/json') { params -> JsonOutput.toJson(rows) }

        then:
        script.getDebugLogEntries()*.message == ['before reload', 'after reload']
    }

    def "cold native recovery deduplicates migrated rows and rejects other app envelopes"() {
        given:
        settingsMap.mcpLogLevel = 'debug'
        script.mcpLog('debug', 'server', 'retained', '42', [duration: 17, details: [tool: 'hub_get_info']])
        def rows = nativeRows()
        def foreign = rows[0].replace('"appId":"402"', '"appId":"999"')
        hubGet.register('/logs/past/json') { params -> JsonOutput.toJson(rows + rows + [foreign]) }

        when:
        reload()
        def result = script.toolGetDebugLogs([ruleId: '42'])

        then:
        result.count == 1
        result.entries[0].message == 'retained'
        result.entries[0].durationMs == 17
        result.entries[0].details.tool == 'hub_get_info'
    }
}
