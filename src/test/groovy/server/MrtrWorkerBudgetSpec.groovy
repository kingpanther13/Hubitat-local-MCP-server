package server

import java.util.concurrent.atomic.AtomicLong
import support.ToolSpecBase

class MrtrWorkerBudgetSpec extends ToolSpecBase {
    private final AtomicLong clock = new AtomicLong(1234567890000L)

    def setup() {
        NOW_OVERRIDE.set({ -> clock.get() })
        settingsMap.enableWrite = true
        settingsMap.enableRead = true
        settingsMap.maxConcurrentWrites = 1
        settingsMap.relayBudgetMs = 0
        settingsMap.lanBudgetMs = 0
        stateMap.lastBackupTimestamp = clock.get()
        RUN_IN_MILLIS_OVERRIDE.set({ List call ->
            runInMillisCalls << call
            script.runMrtrSlice(new LinkedHashMap(call[2].data as Map))
        })
    }

    private Map modernCall(String tool, Map args, String stateId = null) {
        def params = [name: tool, arguments: args]
        if (stateId != null) params.requestState = stateId
        mcpDriver.pushHeaders(['MCP-Protocol-Version': '2026-07-28', 'Mcp-Method': 'tools/call', 'Mcp-Name': tool])
        mcpDriver.pushBody([jsonrpc: '2.0', id: ++mcpDriver.lastSentId, method: 'tools/call', params: params])
        script.handleMcpRequest()
        mcpDriver.parseResponseJson() as Map
    }

    def "a document change batch dispatches once as a whole and replays its completed result"() {
        given:
        def changes = [
            [part: 'variable', value: [operation: 'create', name: 'Counter', type: 'integer', value: 0]],
            [part: 'action', value: [type: 'messageActs', subtype: 'getLogMsg',
                fields: [[name: 'logmsg.#', type: 'textarea', value: 'batch complete']]]]
        ]
        def args = [rule_id: 77, confirm: true, changes: changes]
        List dispatched = []
        script.metaClass.toolUpdateRuleDocument = { Map actual ->
            dispatched << new LinkedHashMap(actual)
            // A document batch completes in one worker; no legacy remainder protocol.
            clock.addAndGet(120001L)
            [success: true, rule_id: actual.rule_id,
                results: actual.changes.collect { [part: it.part, result: [success: true]] }]
        }

        when:
        def completed = modernCall('hub_update_rule_document', args)
        String stateId = atomicStateMap.mrtrRequests.keySet().first()
        def terminal = mcpDriver.parseInner(completed)
        def replay = modernCall('hub_update_rule_document', args, stateId)

        then:
        completed.result.resultType == 'complete'
        stateId instanceof String
        dispatched == [args]
        terminal.success == true
        terminal.rule_id == 77
        terminal.results*.part == ['variable', 'action']
        mcpDriver.parseInner(replay) == terminal
        runInMillisCalls.size() == 1
        atomicStateMap.mrtrRequests[stateId].status == 'terminal'
        !atomicStateMap.mrtrRequests[stateId].containsKey('nextArguments')
        script._activeWrites().isEmpty()
    }

}
