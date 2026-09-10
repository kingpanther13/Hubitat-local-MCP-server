package server

import groovy.json.JsonOutput
import java.util.concurrent.atomic.AtomicLong
import support.TestDevice
import support.ToolSpecBase

class HealthMrtrSpec extends ToolSpecBase {
    AtomicLong clock

    def setup() {
        clock = new AtomicLong(1234567890000L)
        NOW_OVERRIDE.set({ clock.get() })
        PAUSE_EXECUTION_OVERRIDE.set({ Long ms -> clock.addAndGet(ms) })
        settingsMap.enableRead = true
        settingsMap.enableWrite = false
        settingsMap.enableMandatoryBPS = true
        settingsMap.useGateways = true
        settingsMap.relayBudgetMs = 6000
        script.metaClass._isCloudRequest = { true }
    }

    private Map call(String outer, Map leafArgs, String token = null) {
        Map args = outer == 'hub_get_device_health' ? leafArgs :
            [tool: 'hub_get_device_health', args: leafArgs]
        Map params = [name: outer, arguments: args]
        if (token != null) params.requestState = token
        mcpDriver.pushHeaders(['MCP-Protocol-Version': '2026-07-28',
            'Mcp-Method': 'tools/call', 'Mcp-Name': outer])
        mcpDriver.pushBody([jsonrpc: '2.0', id: ++mcpDriver.lastSentId,
            method: 'tools/call', params: params])
        script.handleMcpRequest()
        mcpDriver.parseResponseJson() as Map
    }

    private void finish(List scheduled) {
        script."${scheduled[1]}"(new LinkedHashMap(scheduled[2].data as Map))
    }

    def 'health #outer runs probes once outside HTTP and replays the original result'() {
        given:
        settingsMap.selectedDevices = [new TestDevice(id: 88)]
        hubGet.register('/hub/networkTest/traceroute/8.8.8.8') {
            clock.addAndGet(30000L)
            'original route'
        }
        hubGet.register('/hub/advanced/blinkLED') { 'ok' }
        hubGet.register('/hub2/devicesList') {
            JsonOutput.toJson([devices: [[data: [id: 88, name: 'Original', lastActivity: null]]]])
        }
        Map args = [tracerouteHost: '8.8.8.8', identifyHub: true]
        long started = clock.get()

        when:
        def first = call(outer, args)
        String token = first.result.requestState

        then:
        first.result.resultType == 'input_required'
        token != null
        clock.get() - started < 10000L
        hubGet.calls.empty
        script._activeWrites().empty

        when:
        finish(runInMillisCalls[0])
        finish(runInMillisCalls[0])
        def complete = call(outer, args, token)
        def replay = call(outer, args, token)

        then:
        complete.result.resultType == 'complete'
        complete.result.isError != true
        def payload = mcpDriver.parseInner(complete)
        payload.traceroute.output == 'original route'
        payload.identifyHubTriggered == true
        payload.summary.totalDevices == 1
        mcpDriver.parseInner(replay) == payload
        hubGet.calls*.path == ['/hub/networkTest/traceroute/8.8.8.8',
            '/hub/advanced/blinkLED', '/hub2/devicesList']
        !JsonOutput.toJson(atomicStateMap.mrtrRequests).contains('original route')
        !JsonOutput.toJson(payload).contains('readSnapshotFetchedAt')
        script._activeWrites().empty

        where:
        outer << ['hub_get_device_health', 'hub_read_diagnostics', 'hub_manage_diagnostics']
    }

    def 'a fast health worker answers once and a new call fetches again'() {
        given:
        int probes = 0
        hubGet.register('/hub/networkTest/traceroute/8.8.8.8') { "route ${++probes}".toString() }
        RUN_IN_MILLIS_OVERRIDE.set({ List scheduled ->
            runInMillisCalls << scheduled
            finish(scheduled)
        })

        when:
        def first = call('hub_get_device_health', [tracerouteHost: '8.8.8.8'])
        def second = call('hub_get_device_health', [tracerouteHost: '8.8.8.8'])

        then:
        first.result.resultType == 'complete'
        second.result.resultType == 'complete'
        mcpDriver.parseInner(first).traceroute.output == 'route 1'
        mcpDriver.parseInner(second).traceroute.output == 'route 2'
        probes == 2
    }

    def 'queued health worker honors revoked #gate before any probe'() {
        given:
        settingsMap.selectedDevices = [new TestDevice(id: 88)]
        Map args = [tracerouteHost: '8.8.8.8']
        String token = call('hub_read_diagnostics', args).result.requestState
        assert token != null

        when:
        if (gate == 'gateway') settingsMap.disabled_gateways = ['hub_read_diagnostics']
        if (gate == 'read') settingsMap.enableRead = false
        if (gate == 'scope') settingsMap.selectedDevices = []
        finish(runInMillisCalls[0])
        def denied = call('hub_read_diagnostics', args, token)

        then:
        denied.error != null || denied.result.isError == true
        hubGet.calls.empty

        where:
        gate << ['gateway', 'read', 'scope']
    }

    def 'health terminal replay honors revoked #gate without repeating a probe'() {
        given:
        settingsMap.selectedDevices = [new TestDevice(id: 88)]
        hubGet.register('/hub/networkTest/traceroute/8.8.8.8') { 'private-route' }
        hubGet.register('/hub2/devicesList') { JsonOutput.toJson([devices: []]) }
        Map args = [tracerouteHost: '8.8.8.8']
        String token = call('hub_read_diagnostics', args).result.requestState
        finish(runInMillisCalls[0])
        assert call('hub_read_diagnostics', args, token).result.isError != true
        int callsBefore = hubGet.calls.size()

        when:
        if (gate == 'gateway') settingsMap.disabled_gateways = ['hub_read_diagnostics']
        if (gate == 'read') settingsMap.enableRead = false
        if (gate == 'scope') settingsMap.selectedDevices = []
        def denied = call('hub_read_diagnostics', args, token)

        then:
        denied.error != null || denied.result.isError == true
        !JsonOutput.toJson(denied).contains('private-route')
        hubGet.calls.size() == callsBefore

        where:
        gate << ['gateway', 'read', 'scope']
    }

    def 'unfinished health returns a bounded timeout and never promises an automatic retry'() {
        given:
        Map args = [speedtest: true, identifyHub: true]
        String token = call('hub_get_device_health', args).result.requestState
        assert token != null

        when:
        def legs = (1..8).collect { call('hub_get_device_health', args, token) }
        def result = mcpDriver.parseInner(legs.last())

        then:
        legs.last().result.resultType == 'complete'
        legs.last().result.isError == true
        result.status == 'slow_read_timeout'
        result.note.contains('may still be running')
        result.note.contains('Do not automatically retry')
        result.note.contains('LED')
        !result.note.contains('No hub state was changed')
        result.mrtr.rounds == 8
        mcpDriver.parseInner(call('hub_get_device_health', args, token)) == result
        hubGet.calls.empty
    }

    def 'health pending snapshot survives combined traceroute and speedtest deadlines'() {
        given:
        Map args = [tracerouteHost: '8.8.8.8', speedtest: true]
        String token = call('hub_get_device_health', args).result.requestState
        assert token != null
        List queued = runInMillisCalls[0]
        Map snapshots = scriptStaticField('NATIVE_LOG_SNAPSHOTS') as Map
        long queuedAt = snapshots[queued[2].data.key].at
        hubGet.register('/hub/networkTest/traceroute/8.8.8.8') { 'route' }
        hubGet.register('/hub/networkTest/speedtest') { 'speed' }

        when: 'a later continuation sweeps stale snapshots while the health worker is queued'
        clock.set(queuedAt + 120000L)
        def pending = call('hub_get_device_health', args, token)
        finish(queued)
        def complete = call('hub_get_device_health', args, token)

        then:
        pending.result.resultType == 'input_required'
        complete.result.resultType == 'complete'
        mcpDriver.parseInner(complete).speedtest.output == 'speed'
        hubGet.calls*.path == ['/hub/networkTest/traceroute/8.8.8.8', '/hub/networkTest/speedtest']
    }

    def 'late health terminal expires with its snapshot without refetching'() {
        given:
        hubGet.register('/hub/networkTest/traceroute/8.8.8.8') { 'route' }
        Map args = [tracerouteHost: '8.8.8.8']
        String token = call('hub_get_device_health', args).result.requestState
        finish(runInMillisCalls[0])
        long fetchedAt = clock.get()
        clock.addAndGet(20000L)

        when:
        def complete = call('hub_get_device_health', args, token)

        then:
        complete.result.resultType == 'complete'
        atomicStateMap.mrtrRequests[token].expiresAt == fetchedAt + 30000L

        when:
        clock.set(fetchedAt + 30001L)
        def expired = call('hub_get_device_health', args, token)

        then:
        expired.error.code == -32602
        hubGet.calls.size() == 1
    }

    def 'health continuation rejects changed arguments and missing snapshots without new work'() {
        given:
        Map args = [tracerouteHost: '8.8.8.8']
        String token = call('hub_get_device_health', args).result.requestState
        assert token != null

        when:
        def changed = call('hub_get_device_health', [tracerouteHost: '1.1.1.1'], token)
        (scriptStaticField('NATIVE_LOG_SNAPSHOTS') as Map).clear()
        def lost = call('hub_get_device_health', args, token)
        finish(runInMillisCalls[0])

        then:
        changed.error.code == -32602
        lost.error.code == -32602
        hubGet.calls.empty
    }
}

