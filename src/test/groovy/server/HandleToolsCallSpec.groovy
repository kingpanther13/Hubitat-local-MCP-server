package server

import groovy.json.JsonOutput
import support.ToolSpecBase

/**
 * Spec for hubitat-mcp-server.groovy::handleToolsCall.
 *
 * Covers the JSON-RPC 2.0 envelope, IAE → -32602 error mapping for
 * validation errors, and the generic-Exception path that returns an
 * isError success envelope per the MCP spec.
 *
 * Features drive through the full {@code handleMcpRequest} envelope path
 * (request.JSON parse + tools/call dispatch + render envelope) via
 * {@code mcpDriver.callTool}. The malformed-name feature keeps the
 * manual pushBody construction since callTool assumes a well-formed name.
 */
class HandleToolsCallSpec extends ToolSpecBase {

    def "missing tool name returns -32602"() {
        given: 'deliberately malformed envelope (no name in params) — bypass callTool helper'
        mcpDriver.pushBody([jsonrpc: '2.0', id: 42, method: 'tools/call', params: [:]])

        when:
        script.handleMcpRequest()

        then:
        def response = mcpDriver.parseResponseJson()
        response.jsonrpc == '2.0'
        response.id == 42
        response.error.code == -32602
        response.error.message.contains('tool name required')
    }

    def "IllegalArgumentException from a tool is mapped to -32602 with wrapping"() {
        given: 'Read tools are disabled — the central Read master gate will throw IAE'
        settingsMap.enableRead = false

        when:
        def response = mcpDriver.callTool('hub_get_logs', [:])

        then:
        response.jsonrpc == '2.0'
        response.id == mcpDriver.lastSentId
        response.error.code == -32602
        response.error.message.startsWith('Invalid params:')
        response.error.message.contains('Read tools are disabled')
    }

    def "a call still carrying the removed opToken is refused loudly with the requestState pointer"() {
        given: 'a client running the removed idempotent-replay protocol'
        settingsMap.enableWrite = true
        def ran = 0
        script.metaClass.toolCreateVariable = { Map a -> ran++; [success: true] }

        when:
        def response = mcpDriver.callTool('hub_create_variable',
            [name: 'legacyTokenVar', type: 'String', value: 'x', confirm: true, opToken: 'tok-12345678'])

        then: 'silence would cost the client its duplicate-commit protection unnoticed'
        response.error.code == -32602
        response.error.message.contains('opToken was removed')
        response.error.message.contains('requestState')

        and: 'the refusal fired before any side effect -- the write never ran'
        ran == 0
    }

    def "generic Exception from a tool returns isError success envelope (MCP spec)"() {
        given: 'getRooms() throws a non-IAE so hub_list_rooms hits the generic catch'
        script.metaClass.getRooms = { throw new RuntimeException('boom') }

        when:
        def response = mcpDriver.callTool('hub_list_rooms', [:])

        then: 'MCP spec: tool execution errors return a success envelope with isError flag'
        response.jsonrpc == '2.0'
        response.id == mcpDriver.lastSentId
        response.error == null
        response.result.isError == true
        response.result.content instanceof List
        response.result.content[0].type == 'text'
        response.result.content[0].text.startsWith('Tool error:')
        response.result.content[0].text.contains('boom')
    }

    def "tool results stay text-only with a saved publication toggle in either catalog mode"() {
        given:
        settingsMap.publishOutputSchemas = true
        settingsMap.useGateways = gatewayMode
        script.metaClass.toolGetHubInfo = { a -> [model: 'C-8'] }

        when:
        def response = mcpDriver.callTool('hub_get_info', [:])

        then:
        !response.result.containsKey('structuredContent')
        mcpDriver.parseInner(response) == [model: 'C-8']

        where:
        gatewayMode << [true, false]
    }

    def "an isError result carries the isError flag and text content"() {
        given:
        script.metaClass.toolGetHubInfo = { a -> [isError: true, error: 'boom'] }

        when:
        def response = mcpDriver.callTool('hub_get_info', [:])

        then:
        response.result.isError == true
        !response.result.containsKey('structuredContent')
    }

    def "an oversized result keeps the non-error too-large envelope with a saved publication toggle"() {
        given:
        settingsMap.useGateways = true
        settingsMap.publishOutputSchemas = true
        script.metaClass.toolGetHubInfo = { a -> [blob: 'x' * 130000] }

        when:
        def response = mcpDriver.callTool('hub_get_info', [:])

        then: 'the model reads the suggestion and retries'
        !response.result.containsKey('isError')
        response.result.content[0].text.contains('response_too_large')
    }

    def "null tool result on a gateway-routed call blames the failing sub-tool, not the gateway"() {
        given: 'a leaf handler that returns null, reached through its gateway'
        settingsMap.useGateways = true
        script.metaClass.toolListRooms = { a -> null }

        when:
        def response = mcpDriver.callTool('hub_manage_rooms', [tool: 'hub_list_rooms', args: [:]])

        then: 'isError envelope whose error/tool fields name the SUB-TOOL (issue #299 pattern)'
        response.result.isError == true
        def inner = mcpDriver.parseInner(response)
        inner.isError == true
        inner.tool == 'hub_list_rooms'
        inner.error.contains('hub_list_rooms')
        !inner.error.contains('hub_manage_rooms')
    }

    def "non-serializable tool result on a gateway-routed call blames the failing sub-tool, not the gateway"() {
        given: 'a leaf handler returning Double.NaN (JsonOutput throws "Number value is Not-a-Number"), reached through its gateway'
        settingsMap.useGateways = true
        script.metaClass.toolListRooms = { a -> [rooms: [], bad: Double.NaN] }

        when:
        def response = mcpDriver.callTool('hub_manage_rooms', [tool: 'hub_list_rooms', args: [:]])

        then: 'isError envelope whose error names the SUB-TOOL (same reactiveToolName resolution as the null branch)'
        response.result.isError == true
        def inner = mcpDriver.parseInner(response)
        inner.isError == true
        inner.error.contains('hub_list_rooms')
        !inner.error.contains('hub_manage_rooms')
    }

    def "successful tool call returns wrapped content as JSON text"() {
        given: 'Read tools enabled + a stubbed /logs/past/json returning empty logs'
        settingsMap.enableRead = true
        hubGet.register('/logs/past/json') { params ->
            JsonOutput.toJson([])
        }

        when:
        def response = mcpDriver.callTool('hub_get_logs', [:])

        then: 'JSON-RPC 2.0 success envelope shape'
        response.jsonrpc == '2.0'
        response.id == mcpDriver.lastSentId
        response.result.content instanceof List
        response.result.content[0].type == 'text'

        and: 'the text payload parses back to the tool result shape'
        def inner = mcpDriver.parseInner(response)
        inner.logs == []
        inner.count == 0
    }

    def "tool result rendering keeps nested brokenBefore metadata internal without mutating cached results"() {
        given:
        def canonicalBackup = [backupKey: 'rm-rule_7_baseline', brokenBefore: true,
                               baselineReused: true]
        def nestedBackup = [backupKey: 'rm-rule_7_required', brokenBefore: false]
        def canonicalResult = [success: true, backup: canonicalBackup,
                               patches: [[success: true, backup: nestedBackup]]]
        settingsMap.useGateways = true
        script.metaClass.toolGetHubInfo = { a -> canonicalResult }

        when:
        def response = mcpDriver.callTool('hub_get_info', [:])
        def inner = mcpDriver.parseInner(response)

        then: 'the internal diagnostic is absent from the public text payload'
        inner.backup == [backupKey: 'rm-rule_7_baseline', baselineReused: true]
        inner.patches[0].backup == [backupKey: 'rm-rule_7_required']
        !response.result.containsKey('structuredContent')

        and: 'recursive path copies leave internal consumers and terminal replay untouched'
        canonicalBackup.brokenBefore == true
        nestedBackup.brokenBefore == false
        canonicalResult.backup.is(canonicalBackup)
        canonicalResult.patches[0].backup.is(nestedBackup)
    }

    def "handleToolsCall returns a __preserialized sentinel on the under-cap success path (serialize-once)"() {
        // The sentinel is the mechanism that lets handleMcpRequest render verbatim without a
        // second JsonOutput.toJson. Pinning the sentinel shape here guards the serialize-once
        // contract -- a regression that went back to returning the plain jsonRpcResult object
        // would re-introduce the double encode. (Dispatch tests above prove the wire form is
        // still correct because handleMcpRequest renders __preserialized verbatim.)
        given:
        script.metaClass.getRooms = { -> [[id: 1L, name: 'Den']] }
        def msg = [jsonrpc: '2.0', id: 5, method: 'tools/call', params: [name: 'hub_list_rooms', arguments: [:]]]

        when:
        def result = script.handleToolsCall(msg)

        then: 'the success path returns the preserialized sentinel, not a bare jsonRpcResult Map'
        result instanceof Map
        result.containsKey('__preserialized')
        result.__preserialized instanceof String

        and: 'the sentinel string is itself the complete, well-formed JSON-RPC wire result'
        def decoded = new groovy.json.JsonSlurper().parseText(result.__preserialized)
        decoded.jsonrpc == '2.0'
        decoded.id == 5
        decoded.result.content[0].type == 'text'
        new groovy.json.JsonSlurper().parseText(decoded.result.content[0].text).rooms*.name == ['Den']
    }
}
