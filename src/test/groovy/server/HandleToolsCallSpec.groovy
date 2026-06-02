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
        given: 'Hub Admin Read is disabled — requireHubAdminRead() will throw IAE'
        settingsMap.enableHubAdminRead = false

        when:
        def response = mcpDriver.callTool('hub_get_logs', [:])

        then:
        response.jsonrpc == '2.0'
        response.id == mcpDriver.lastSentId
        response.error.code == -32602
        response.error.message.startsWith('Invalid params:')
        response.error.message.contains('Hub Admin Read')
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

    def "successful tool call returns wrapped content as JSON text"() {
        given: 'Hub Admin Read enabled + a stubbed /logs/past/json returning empty logs'
        settingsMap.enableHubAdminRead = true
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
}
