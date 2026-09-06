package server

import groovy.json.JsonOutput
import support.TestChildApp
import support.ToolSpecBase

class DebugLogRingSpec extends ToolSpecBase {
    def setup() {
        script.metaClass.getApp = { -> new TestChildApp(id: 402L) }
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
        def peer = newCompiledScriptInstance()
        peer.metaClass.getApp = { -> new TestChildApp(id: 402L) }
        peer.metaClass.getState = { -> throw new AssertionError('warm state access') }
        peer.metaClass.getAtomicState = { -> throw new AssertionError('warm atomicState access') }
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

    def "all fields and nested details have serialized byte bounds"() {
        given:
        settingsMap.mcpLogLevel = 'debug'
        def huge = '\u754c' * 20000
        def details = [tool: 'hub_set_rule', nested: [payload: huge, list: (1..50).collect { [huge: huge] }]]

        when:
        (1..25).each { script.mcpLog('error', huge, huge, huge,
            [ruleName: huge, stackTrace: huge, duration: huge, details: details]) }

        then:
        script.getDebugLogEntries().every { JsonOutput.toJson(it).getBytes('UTF-8').length <= 4096 }
        JsonOutput.toJson(script.getDebugLogEntries()).getBytes('UTF-8').length <= 65536
        script.getDebugLogEntries().every { it.details instanceof Map }
    }

    def "app instances cannot see or clear another app's ring"() {
        given:
        settingsMap.mcpLogLevel = 'debug'
        def first = newCompiledScriptInstance()
        def second = newCompiledScriptInstance()
        first.metaClass.getApp = { -> new TestChildApp(id: 401L) }
        second.metaClass.getApp = { -> new TestChildApp(id: 402L) }
        def firstState = [:]
        def secondState = [:]
        def firstAtomic = [:]
        def secondAtomic = [:]
        first.metaClass.getState = { -> firstState }
        second.metaClass.getState = { -> secondState }
        first.metaClass.getAtomicState = { -> firstAtomic }
        second.metaClass.getAtomicState = { -> secondAtomic }

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
}
