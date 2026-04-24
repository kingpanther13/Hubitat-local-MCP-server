package server

import support.ToolSpecBase

/**
 * Drives {@code handleMcpRequest()} end-to-end through the HTTP pipeline
 * the MCP app exposes on a real hub — {@code request.JSON} in,
 * {@code render(...)} out — instead of invoking the JSON-RPC dispatch
 * layer directly as {@code HandleToolsCallSpec} does.
 *
 * Covers the seams that aren't reachable from the JSON-RPC-layer specs:
 *
 *   - {@code request.JSON} parse path (empty body, batch array, single
 *     object, malformed JSON)
 *   - {@code render(Map)} envelope (status, contentType, data)
 *   - Notification short-circuit (id-less request → 204 no-content)
 *   - Empty-batch -32600 validation
 *   - End-to-end tools/call happy path with the render envelope verified
 *
 * Part of #77 — in-harness E2E drive-through.
 */
class HandleMcpRequestE2ESpec extends ToolSpecBase {

    def "initialize returns protocolVersion and serverInfo via render(Map)"() {
        given:
        mcpDriver.pushBody([jsonrpc: '2.0', id: 1, method: 'initialize', params: [:]])

        when:
        script.handleMcpRequest()

        then: 'render was called with a JSON content-type and a parseable body'
        mcpDriver.lastRenderArgs.contentType == 'application/json'
        def response = mcpDriver.parseResponseJson()

        and: 'JSON-RPC 2.0 success envelope'
        response.jsonrpc == '2.0'
        response.id == 1
        response.error == null

        and: 'MCP initialize response shape'
        response.result.protocolVersion == '2024-11-05'
        response.result.capabilities.tools == [:]
        response.result.serverInfo.name == 'hubitat-mcp-rule-server'
        response.result.serverInfo.version
    }

    def "tools/list returns the tool catalog"() {
        given:
        mcpDriver.pushBody([jsonrpc: '2.0', id: 2, method: 'tools/list', params: [:]])

        when:
        script.handleMcpRequest()

        then:
        def response = mcpDriver.parseResponseJson()
        response.jsonrpc == '2.0'
        response.id == 2
        response.result.tools instanceof List
        response.result.tools.size() > 0

        and: 'each entry has the MCP tool shape (name + description + inputSchema)'
        response.result.tools.every {
            it.name instanceof String &&
            it.description instanceof String &&
            it.inputSchema instanceof Map
        }
    }

    def "tools/call list_rooms flows through render with an MCP content envelope"() {
        given: 'a stubbed getRooms returning a deterministic list'
        script.metaClass.getRooms = { ->
            [[id: 1L, name: 'Living Room'], [id: 2L, name: 'Kitchen']]
        }
        mcpDriver.pushBody([
            jsonrpc: '2.0', id: 3, method: 'tools/call',
            params: [name: 'list_rooms', arguments: [:]]
        ])

        when:
        script.handleMcpRequest()

        then: 'success envelope with MCP content array'
        def response = mcpDriver.parseResponseJson()
        response.jsonrpc == '2.0'
        response.id == 3
        response.error == null
        response.result.content[0].type == 'text'

        and: 'the inner JSON parses back to the tool-result shape'
        def inner = new groovy.json.JsonSlurper().parseText(response.result.content[0].text)
        inner.rooms*.name.containsAll(['Living Room', 'Kitchen'])
    }

    def "notification (no id) returns 204 no-content"() {
        given: 'a notification-shaped request — id field is absent per JSON-RPC 2.0'
        mcpDriver.pushBody([jsonrpc: '2.0', method: 'initialized', params: [:]])

        when:
        script.handleMcpRequest()

        then: 'render was called with 204 and empty body — no JSON envelope'
        mcpDriver.lastRenderArgs.status == 204
        mcpDriver.lastRenderArgs.data == ''
    }

    def "batch request returns an array of responses, skipping notifications"() {
        given:
        mcpDriver.pushBody([
            [jsonrpc: '2.0', id: 10, method: 'initialize', params: [:]],
            [jsonrpc: '2.0', method: 'initialized', params: [:]],  // notification
            [jsonrpc: '2.0', id: 11, method: 'ping', params: [:]]
        ])

        when:
        script.handleMcpRequest()

        then: 'response is a JSON array with exactly the two request-shaped entries'
        def response = mcpDriver.parseResponseJson()
        response instanceof List
        response.size() == 2
        response*.id.toSet() == [10, 11] as Set
        response.every { it.jsonrpc == '2.0' && it.error == null }
    }

    def "empty batch array returns -32600"() {
        given:
        mcpDriver.pushBody([])

        when:
        script.handleMcpRequest()

        then:
        def response = mcpDriver.parseResponseJson()
        response.jsonrpc == '2.0'
        response.id == null
        response.error.code == -32600
        response.error.message.contains('empty batch')
    }

    def "null body returns parse error -32700"() {
        given: 'request.JSON is null — production treats this as an unparseable body'
        mcpDriver.pushBody(null)

        when:
        script.handleMcpRequest()

        then:
        def response = mcpDriver.parseResponseJson()
        response.jsonrpc == '2.0'
        response.id == null
        response.error.code == -32700
        response.error.message.toLowerCase().contains('parse error')
    }

    def "unknown method on a valid envelope returns -32601 method-not-found"() {
        given:
        mcpDriver.pushBody([jsonrpc: '2.0', id: 99, method: 'does/not/exist'])

        when:
        script.handleMcpRequest()

        then:
        def response = mcpDriver.parseResponseJson()
        response.jsonrpc == '2.0'
        response.id == 99
        response.error.code == -32601
        response.error.message.contains('does/not/exist')
    }

    def "missing jsonrpc field returns -32600 invalid-request"() {
        given: 'body shaped like a JSON-RPC call but without the required jsonrpc marker'
        mcpDriver.pushBody([id: 5, method: 'initialize'])

        when:
        script.handleMcpRequest()

        then:
        def response = mcpDriver.parseResponseJson()
        response.error.code == -32600
        response.error.message.contains('JSON-RPC 2.0')
    }
}
