package server

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import support.ToolSpecBase

/**
 * Spec for handleToolsCall() — hubitat-mcp-server.groovy line 394.
 *
 * Covers the JSON-RPC 2.0 envelope and IAE → -32602 error mapping that
 * wraps executeTool (and, transitively, handleGateway).
 */
class HandleToolsCallSpec extends ToolSpecBase {

    def "missing tool name returns -32602"() {
        given:
        def msg = [jsonrpc: '2.0', id: 42, method: 'tools/call', params: [:]]

        when:
        def response = script.handleToolsCall(msg)

        then:
        response.jsonrpc == '2.0'
        response.id == 42
        response.error.code == -32602
        response.error.message.contains('tool name required')
    }

    def "IllegalArgumentException from a tool is mapped to -32602 with wrapping"() {
        given: 'Hub Admin Read is disabled — requireHubAdminRead() will throw IAE'
        settingsMap.enableHubAdminRead = false
        def msg = [jsonrpc: '2.0', id: 7, method: 'tools/call', params: [name: 'get_hub_logs', arguments: [:]]]

        when:
        def response = script.handleToolsCall(msg)

        then:
        response.jsonrpc == '2.0'
        response.id == 7
        response.error.code == -32602
        response.error.message.startsWith('Invalid params:')
        response.error.message.contains('Hub Admin Read')
    }

    def "successful tool call returns wrapped content as JSON text"() {
        given: 'Hub Admin Read enabled + a stubbed /logs/past/json returning empty logs'
        settingsMap.enableHubAdminRead = true
        hubGet.register('/logs/past/json') { params ->
            JsonOutput.toJson([])
        }
        def msg = [jsonrpc: '2.0', id: 99, method: 'tools/call', params: [name: 'get_hub_logs', arguments: [:]]]

        when:
        def response = script.handleToolsCall(msg)

        then: 'JSON-RPC 2.0 success envelope shape'
        response.jsonrpc == '2.0'
        response.id == 99
        response.result.content instanceof List
        response.result.content[0].type == 'text'

        and: 'the text payload parses back to the tool result shape'
        def inner = new JsonSlurper().parseText(response.result.content[0].text)
        inner.logs == []
        inner.count == 0
    }
}
