package server

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.util.concurrent.atomic.AtomicLong
import spock.lang.Unroll
import support.ToolSpecBase

class MrtrDriverBudgetSpec extends ToolSpecBase {
    private final AtomicLong clock = new AtomicLong(1234567890000L)
    private final List<Map> saves = []
    private final Map<String, Integer> versions = ['55': 1]
    private Closure itemElapsed = { int index -> index == 1 ? 120001L : 10L }

    def setup() {
        NOW_OVERRIDE.set({ -> clock.get() })
        settingsMap.enableWrite = true
        settingsMap.enableRead = true
        settingsMap.maxConcurrentWrites = 1
        settingsMap.relayBudgetMs = 0
        settingsMap.lanBudgetMs = 0
        stateMap.lastBackupTimestamp = clock.get()
        script.metaClass.backupItemSource = { String type, String id ->
            [version: versions[id], filename: "driver-${id}.groovy".toString()]
        }
        hubGet.register('/driver/ajax/code') { params ->
            JsonOutput.toJson([status: 'ok', source: 'metadata { }',
                               version: versions[params.id.toString()] ?: 1])
        }
        script.metaClass.hubInternalPostJson = { String path, String body ->
            assert path == '/driver/saveOrUpdateJson'
            Map payload = new JsonSlurper().parseText(body) as Map
            saves << payload
            clock.addAndGet(itemElapsed.call(saves.size()) as Long)
            if (payload.source == 'reject') return [success: false, message: 'compile refused']
            String id = payload.id == null ? (9000 + saves.size()).toString() : payload.id.toString()
            if (payload.id != null) {
                assert payload.version == versions[id]
                versions[id] = versions[id] + 1
            }
            [success: true, id: id, version: versions[id] ?: 1]
        }
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

    private List<Map> specs(String field, int count = 3) {
        (1..count).collect { int index ->
            def item = [source: "metadata { /* ${index} */ }".toString()]
            if (field == 'updates') item += [driverId: '55', expectedVersion: index]
            item
        }
    }

    @Unroll
    def "driver #field keeps exact suffix and ordered results through #route"() {
        given:
        settingsMap.useGateways = gateway
        String leaf = field == 'installs' ? 'hub_create_driver' : 'hub_update_driver'
        String outer = gateway ? 'hub_manage_code' : leaf
        def items = specs(field)
        def edit = [confirm: true, (field): items]
        def args = gateway ? [tool: leaf, args: edit] : edit
        String stateId = modernCall(outer, args).result.requestState

        expect:
        saves.isEmpty()
        runInMillisCalls.isEmpty()

        when:
        def paused = modernCall(outer, args, stateId)
        Map record = atomicStateMap.mrtrRequests[stateId] as Map
        Map remainingArgs = gateway ? record.nextArguments?.args : record.nextArguments

        then:
        paused.result.findAll { key, value -> key != '_meta' } ==
            [resultType: 'input_required', requestState: stateId]
        saves*.source == items.take(1)*.source
        remainingArgs[field] == items.drop(1)
        record.aggregate[field].size() == 1
        record.rounds == 1
        record.expiresAt == clock.get() + 180000L
        script._activeWrites()*.tool == [leaf]

        when: 'the old callback cannot repeat a saved item, and the next slice gets a fresh clock'
        script.runMrtrSlice(new LinkedHashMap(runInMillisCalls[0][2].data as Map))
        clock.addAndGet(60000L)
        def complete = modernCall(outer, args, stateId)
        def terminal = mcpDriver.parseInner(complete)
        def replay = modernCall(outer, args, stateId)

        then:
        complete.result.resultType == 'complete'
        terminal.success == true
        terminal[field]*.success == [true, true, true]
        terminal[field]*.driverId == (field == 'installs' ? ['9001', '9002', '9003'] : ['55', '55', '55'])
        terminal.message == "All 3 driver(s) ${field == 'installs' ? 'installed' : 'updated'} successfully."
        terminal.mrtr.rounds == 2
        saves*.source == items*.source
        field != 'updates' || saves*.version == [1, 2, 3]
        runInMillisCalls.size() == 2
        mcpDriver.parseInner(replay) == terminal
        script._activeWrites().isEmpty()
        !JsonOutput.toJson([record.nextArguments, terminal, saves]).contains('__reqT0')

        where:
        field      | gateway | route
        'installs' | false   | 'flat'
        'installs' | true    | 'gateway'
        'updates'  | false   | 'flat'
        'updates'  | true    | 'gateway'
    }

    @Unroll
    def "a failed first driver #field item remains failed after a clean resumed slice"() {
        given:
        settingsMap.useGateways = false
        String leaf = field == 'installs' ? 'hub_create_driver' : 'hub_update_driver'
        def items = specs(field)
        items[0].source = 'reject'
        if (field == 'updates') {
            items[1].expectedVersion = 1
            items[2].expectedVersion = 2
        }
        def args = [confirm: true, (field): items]
        String stateId = modernCall(leaf, args).result.requestState

        when:
        def paused = modernCall(leaf, args, stateId)

        then: 'failure does not disable the safe boundary or retry the failed item'
        paused.result.resultType == 'input_required'
        saves.size() == 1
        atomicStateMap.mrtrRequests[stateId].nextArguments[field] == items.drop(1)

        when:
        def complete = modernCall(leaf, args, stateId)
        def terminal = mcpDriver.parseInner(complete)

        then:
        complete.result.resultType == 'complete'
        terminal.success == false
        terminal[field]*.success == [false, true, true]
        terminal[field][0].error.contains('compile refused')
        terminal.message == "2 of 3 driver(s) ${field == 'installs' ? 'installed' : 'updated'} successfully."
        saves*.source == items*.source
        runInMillisCalls.size() == 2

        where:
        field << ['installs', 'updates']
    }

    def "resumed repeated driver IDs retain each expectedVersion including a conflict"() {
        given:
        settingsMap.useGateways = false
        def items = specs('updates')
        items[1].expectedVersion = 999
        items[2].expectedVersion = 2
        def args = [confirm: true, updates: items]
        String stateId = modernCall('hub_update_driver', args).result.requestState

        when:
        def paused = modernCall('hub_update_driver', args, stateId)

        then:
        paused.result.resultType == 'input_required'
        atomicStateMap.mrtrRequests[stateId].nextArguments.updates == items.drop(1)

        when:
        def complete = modernCall('hub_update_driver', args, stateId)
        def terminal = mcpDriver.parseInner(complete)

        then:
        terminal.success == false
        terminal.updates*.driverId == ['55', '55', '55']
        terminal.updates*.success == [true, false, true]
        terminal.updates[1].conflict == true
        terminal.updates[1].expectedVersion == 999
        terminal.updates[1].currentVersion == 2
        saves*.source == [items[0].source, items[2].source]
        saves*.version == [1, 2]
    }

    @Unroll
    def "the driver #field slice cap keeps the exact suffix for an explicit follow-up"() {
        given:
        settingsMap.useGateways = false
        itemElapsed = { int index -> 120001L }
        String leaf = field == 'installs' ? 'hub_create_driver' : 'hub_update_driver'
        String remainingField = field + 'Remaining'
        def items = specs(field, 10)
        def args = [confirm: true, (field): items]
        String stateId = modernCall(leaf, args).result.requestState

        when:
        Map response
        for (int slice = 1; slice <= 8; slice++) {
            response = modernCall(leaf, args, stateId)
            if (slice < 8) assert response.result.resultType == 'input_required'
        }
        def terminal = mcpDriver.parseInner(response)

        then:
        response.result.resultType == 'complete'
        response.result.isError == true
        terminal.status == 'continuation_limit'
        terminal[remainingField] == items.drop(8)
        terminal.aggregate[field].size() == 8
        terminal.mrtr.rounds == 8
        saves*.source == items.take(8)*.source
        mcpDriver.parseInner(modernCall(leaf, args, stateId)) == terminal
        script._activeWrites().isEmpty()

        when:
        def followup = [confirm: true, (field): terminal[remainingField]]
        String nextId = modernCall(leaf, followup).result.requestState
        modernCall(leaf, followup, nextId)
        def finished = modernCall(leaf, followup, nextId)

        then:
        finished.result.resultType == 'complete'
        mcpDriver.parseInner(finished).success == true
        saves*.source == items*.source
        runInMillisCalls.size() == 10

        where:
        field << ['installs', 'updates']
    }
}
