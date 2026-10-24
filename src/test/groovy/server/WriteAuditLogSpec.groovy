package server

import spock.lang.Unroll
import support.ToolSpecBase

/**
 * Write audit records (issue #530): every lease-backed (non-continuation) write leaves
 * started/finished records in the MCP log under component "write", at info level and
 * regardless of the configured MCP log level. Continuation writes stay covered by
 * hub_get_info.recentWrites and leave none. The recovery hint served to clients names
 * recentWrites only for a modern-era caller.
 */
class WriteAuditLogSpec extends ToolSpecBase {

    def setup() {
        settingsMap.enableRead = true
        settingsMap.enableWrite = true
        script.initDebugLogs().entries.clear()
        script.log.messages.clear()
    }

    private List<Map> writeRecords() {
        def logs = script.toolGetHubLogs([mode: 'mcp', component: 'write', limit: 100])
        return (logs.entries as List<Map>).findAll { it.component == 'write' }
    }

    private Map modernCall(String toolName, Map args) {
        int id = ++mcpDriver.lastSentId
        mcpDriver.pushHeaders([
            'MCP-Protocol-Version': '2026-07-28',
            'Mcp-Method': 'tools/call',
            'Mcp-Name': toolName
        ])
        mcpDriver.pushBody([jsonrpc: '2.0', id: id, method: 'tools/call',
                            params: [name: toolName, arguments: args]])
        script.handleMcpRequest()
        return mcpDriver.parseResponseJson() as Map
    }

    private Map rpc(String method, Map params, Map headers) {
        mcpDriver.pushHeaders(headers)
        mcpDriver.pushBody([jsonrpc: '2.0', id: 77, method: method, params: params])
        script.handleMcpRequest()
        return mcpDriver.parseResponseJson() as Map
    }

    def "a legacy write leaves started and finished records at the default error log level"() {
        given:
        settingsMap.useGateways = false
        settingsMap.remove('mcpLogLevel')
        script.metaClass.toolCreateVariable = { Map a -> [success: true, name: a.name, type: a.type] }

        when:
        def response = mcpDriver.callTool('hub_create_variable',
            [name: 'AuditVar', type: 'string', value: 'secret-value'])
        def records = writeRecords()

        then: 'the threshold is still error, yet both info records exist'
        script.getConfiguredLogLevel() == 'error'
        !response.result.isError
        records*.details*.phase == ['started', 'finished']
        records.every { it.level == 'info' && it.details.tool == 'hub_create_variable' }
        records.every { it.details.rpcId == mcpDriver.lastSentId.toString() && it.details.transport == 'legacy' }
        records.every { it.details.target == [name: 'AuditVar', type: 'string'] }
        records[0].details.writeId.startsWith('write-')
        records[0].details.writeId == records[1].details.writeId
        records[1].details.outcome == 'completed'
        records[1].details.durationMs instanceof Number
        !records[1].details.containsKey('error')
        records[0].message == "write started: hub_create_variable target=name=AuditVar,type=string write=${records[0].details.writeId} rpc=${mcpDriver.lastSentId} via=legacy".toString()
        records[1].message.startsWith("write finished: hub_create_variable outcome=completed target=name=AuditVar,type=string write=${records[0].details.writeId} rpc=${mcpDriver.lastSentId} ms=".toString())

        and: 'the value never reaches either log, and the native hub log carries the same records'
        !records.toString().contains('secret-value')
        script.log.messages.findAll { it.startsWith('info:[MCP1] ') && it.contains('"component":"write"') }.size() == 2
        !script.log.messages.any { it.contains('secret-value') }
    }

    @Unroll
    def "a write result #label is recorded as #expected"() {
        given:
        settingsMap.useGateways = false
        script.metaClass.toolSetVariable = stub

        when:
        mcpDriver.callTool('hub_set_variable', [name: 'AuditVar', value: '1'])
        def records = writeRecords()
        def finished = records.find { it.details.phase == 'finished' }

        then:
        records*.details*.phase == ['started', 'finished']
        finished.details.outcome == expected
        errorCheck.call(finished.details.error)
        script._activeWrites().isEmpty()

        where:
        label                  | expected    | stub                                                                   | errorCheck
        'success'              | 'completed' | { Map a -> [success: true] }                                           | { it == null }
        'background requestId' | 'accepted'  | { Map a -> [success: true, status: 'in_progress', requestId: 'r1'] }   | { it == null }
        'paused remainder'     | 'partial'   | { Map a -> [success: true, status: 'in_progress', stepsRemaining: [1]] } | { it?.contains('remaining work') }
        'partial flag'         | 'partial'   | { Map a -> [success: false, partial: true] }                           | { it != null }
        'isError'              | 'failed'    | { Map a -> [isError: true, error: 'token=abc leaked text'] }           | { it == 'tool returned a failure result' }
        'success false'        | 'failed'    | { Map a -> [success: false, status: 'not_found', error: 'x'] }         | { it == 'not_found' }
        'validation throw'     | 'failed'    | { Map a -> throw new IllegalArgumentException('name is required') }    | { it == 'validation refused the call' }
        'runtime throw'        | 'failed'    | { Map a -> throw new IllegalStateException('hub said no') }            | { it == 'tool threw IllegalStateException' }
        'state readback error' | 'unknown'   | { Map a -> [success: true, stateError: 'readback timed out'] }         | { it?.contains('resulting state failed') }
        'null result'          | 'unknown'   | { Map a -> null }                                                      | { it == 'tool returned no result' }
    }

    def "an Error that escapes the tool records an unknown outcome and still releases the lease"() {
        given:
        settingsMap.useGateways = false
        script.metaClass.toolSetVariable = { Map a -> throw new AssertionError('boom') }

        when:
        script.handleToolsCallLegacy([jsonrpc: '2.0', id: 'abc', method: 'tools/call',
                                      params: [name: 'hub_set_variable', arguments: [name: 'AuditVar', value: '1']]])

        then:
        thrown(AssertionError)

        when:
        def records = writeRecords()

        then:
        records*.details*.phase == ['started', 'finished']
        records[1].details.outcome == 'unknown'
        records[1].details.error == 'request ended before a result was produced'
        records[1].details.rpcId == 'abc'
        script._activeWrites().isEmpty()
    }

    def "a long JSON-RPC id is recorded whole so it equals the id the client sent"() {
        given:
        settingsMap.useGateways = false
        script.metaClass.toolSetVariable = { Map a -> [success: true] }
        String longId = 'req-' + ('0123456789abcdef' * 5)

        when:
        script.handleToolsCallLegacy([jsonrpc: '2.0', id: longId, method: 'tools/call',
                                      params: [name: 'hub_set_variable', arguments: [name: 'AuditVar', value: '1']]])
        def records = writeRecords()

        then:
        longId.length() > 64
        records*.details*.phase == ['started', 'finished']
        records.every { it.details.rpcId == longId }
    }

    def "the target summary keeps only identifiers and truncates long strings and lists"() {
        when:
        Map leaf = script._writeTargetSummary('hub_set_rule', 'hub_set_rule',
            [appId: 12, ruleId: (1..8).toList(), name: 'x' * 200, value: 'v', code: 'c', args: [a: 1],
             bestPracticeKey: 'k', settings: [s: 1], token: 't', password: 'p', type: ['ok', [nested: 1]]])
        Map viaGateway = script._writeTargetSummary('hub_create_variable', 'hub_manage_variables',
            [tool: 'hub_create_variable', args: [name: 'G', value: 'secret']])
        Map broken = script._writeTargetSummary('hub_set_rule', 'hub_manage_rule_machine', [args: 'not json'])

        then:
        leaf.keySet() == ['appId', 'ruleId', 'name'] as Set
        leaf.appId == '12'
        leaf.ruleId == '1,2,3,4,5,+3 more'
        leaf.name == 'x' * 80
        viaGateway == [name: 'G']
        broken == [:]
    }

    def "a write refused at capacity leaves one refused record and no started or finished"() {
        given:
        settingsMap.useGateways = false
        settingsMap.maxConcurrentWrites = 1
        def held = script._writeReserveRequest('hub_set_variable', 'legacy')
        def ran = 0
        script.metaClass.toolSetVariable = { Map a -> ran++; [success: true] }

        when:
        def response = mcpDriver.callTool('hub_set_variable', [name: 'AuditVar', value: '1'])
        def records = writeRecords()

        then:
        mcpDriver.parseInner(response).status == 'too_many_writes_in_flight'
        ran == 0
        records.size() == 1
        records[0].details.phase == 'refused'
        records[0].details.reason == 'too_many_writes_in_flight'
        !records[0].details.containsKey('writeId')
        records[0].message.startsWith('write refused: hub_set_variable reason=too_many_writes_in_flight')

        cleanup:
        script._writeReleaseRequest(held.leaseId as String)
    }

    def "reads and gateway catalog calls leave no write records; a gateway write names its gateway"() {
        given:
        settingsMap.useGateways = true
        script.metaClass.toolGetVariable = { Map a -> [name: a.name, value: '1'] }
        script.metaClass.toolCreateVariable = { Map a -> [success: true] }

        when:
        mcpDriver.callTool('hub_read_variables', [tool: 'hub_get_variable', args: [name: 'AuditVar']])
        mcpDriver.callTool('hub_manage_variables', [:])

        then:
        writeRecords().isEmpty()

        when:
        mcpDriver.callTool('hub_manage_variables', [tool: 'hub_create_variable', args: [name: 'GwVar', type: 'string', value: 'v']])
        def records = writeRecords()

        then:
        records*.details*.phase == ['started', 'finished']
        records.every { it.details.tool == 'hub_create_variable' && it.details.gateway == 'hub_manage_variables' }
        records.every { it.details.target == [name: 'GwVar', type: 'string'] }
    }

    def "a modern continuation write leaves no write records; a modern ordinary write does"() {
        given:
        settingsMap.useGateways = false
        script.metaClass.toolRunRmRule = { Map a -> [success: true, ruleIds: a.ruleId,
            results: a.ruleId.collect { [success: true, ruleId: it] }] }
        script.metaClass.toolCreateVariable = { Map a -> [success: true] }

        when:
        def continuation = modernCall('hub_call_rule', [ruleId: [21, 22], action: 'stop'])

        then:
        continuation.result.resultType == 'complete'
        !continuation.result.isError
        writeRecords().isEmpty()

        when:
        modernCall('hub_create_variable', [name: 'ModernVar', type: 'string', value: 'v'])
        def records = writeRecords()

        then:
        records*.details*.phase == ['started', 'finished']
        records.every { it.details.transport == 'modern' }
        records[1].message.contains('outcome=completed')
    }

    def "initialize serves the legacy recovery hint and server/discover the modern one"() {
        given:
        settingsMap.useGateways = true

        when:
        def init = rpc('initialize', [protocolVersion: '2025-11-25', capabilities: [:],
                                      clientInfo: [name: 't', version: '1']], [:])
        def discover = rpc('server/discover', [:],
            ['MCP-Protocol-Version': '2026-07-28', 'Mcp-Method': 'server/discover'])
        String legacy = init.result.instructions
        String modern = discover.result.instructions

        then:
        legacy.endsWith(script._writeRecoveryHint(false) as String)
        !legacy.contains('recentWrites')
        legacy.contains("hub_get_logs(mode='mcp', component='write')")
        legacy.contains('A missing record does not prove the write never ran')
        legacy.contains('its outcome is uncertain')
        modern.endsWith(script._writeRecoveryHint(true) as String)
        modern.contains('check recentWrites')
        modern.contains("For every other write, call hub_get_logs(mode='mcp', component='write')")

        when: 'discover under a legacy header, and headerless as a compat probe'
        String legacyHeader = rpc('server/discover', [:], ['MCP-Protocol-Version': '2025-11-25']).result.instructions
        String headerless = rpc('server/discover', [:], [:]).result.instructions

        then: 'a legacy header selects the legacy hint; no header keeps the modern one'
        legacyHeader.endsWith(script._writeRecoveryHint(false) as String)
        !legacyHeader.contains('recentWrites')
        headerless.endsWith(script._writeRecoveryHint(true) as String)
    }

    def "tools/list chooses the recovery hint by the request's era on leaves and gateways"() {
        given:
        String legacyHint = script._writeRecoveryHint(false)
        String modernHint = script._writeRecoveryHint(true)

        when:
        settingsMap.useGateways = true
        def gwLegacy = rpc('tools/list', [:], [:]).result.tools.collectEntries { [(it.name): it] }
        def gwModern = rpc('tools/list', [:], ['MCP-Protocol-Version': '2026-07-28', 'Mcp-Method': 'tools/list'])
            .result.tools.collectEntries { [(it.name): it] }

        then: 'a continuation-eligible top-level leaf and a gateway exposing one'
        ['hub_manage_virtual_device', 'hub_manage_native_rules_and_apps'].every { String n ->
            (gwLegacy[n].description as String).endsWith(legacyHint) &&
                !(gwLegacy[n].description as String).contains('recentWrites') &&
                (gwModern[n].description as String).endsWith(modernHint)
        }
        !(gwModern['hub_read_devices'].description as String).contains(modernHint)
    }
}
