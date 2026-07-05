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

    // ---------- structuredContent on schema-advertised tools (issue #342) ----------
    // MCP spec (2025-06-18): a server that publishes an outputSchema MUST return
    // structured results. With publishOutputSchemas ON, the gateway-mode base tools
    // advertise outputSchema, and spec-validating clients (Claude Desktop, mcp-proxy's
    // Python SDK) reject a text-only result of an advertised tool — every successful
    // call read as a generic client failure while the hub logged success.

    def "publishOutputSchemas ON: base-tool result carries structuredContent alongside the text block"() {
        given: 'gateway mode pinned -- the schema-advertised surface exists only there (flat CI matrix presets useGateways=false)'
        settingsMap.useGateways = true
        settingsMap.publishOutputSchemas = true
        script.metaClass.toolGetHubInfo = { a -> [model: 'C-8', ok: true] }

        when:
        def response = mcpDriver.callTool('hub_get_info', [:])

        then: 'structuredContent is the result object; the serialized text block stays (spec SHOULD)'
        response.result.structuredContent == [model: 'C-8', ok: true]
        mcpDriver.parseInner(response) == [model: 'C-8', ok: true]
    }

    def "publishOutputSchemas OFF (default): no structuredContent"() {
        given:
        script.metaClass.toolGetHubInfo = { a -> [model: 'C-8'] }

        when:
        def response = mcpDriver.callTool('hub_get_info', [:])

        then:
        !response.result.containsKey('structuredContent')
    }

    def "publishOutputSchemas ON in flat mode: no structuredContent (flat never advertises outputSchema)"() {
        given:
        settingsMap.publishOutputSchemas = true
        settingsMap.useGateways = false
        script.metaClass.toolGetHubInfo = { a -> [model: 'C-8'] }

        when:
        def response = mcpDriver.callTool('hub_get_info', [:])

        then:
        !response.result.containsKey('structuredContent')
    }

    def "publishOutputSchemas ON: gateway-routed sub-tool result carries no structuredContent (gateways advertise no schema)"() {
        given:
        settingsMap.publishOutputSchemas = true
        settingsMap.useGateways = true
        script.metaClass.toolListRooms = { a -> [rooms: []] }

        when:
        def response = mcpDriver.callTool('hub_manage_rooms', [tool: 'hub_list_rooms', args: [:]])

        then:
        !response.result.containsKey('structuredContent')
    }

    def "publishOutputSchemas ON: an isError result carries the isError flag, not structuredContent"() {
        given:
        settingsMap.publishOutputSchemas = true
        script.metaClass.toolGetHubInfo = { a -> [isError: true, error: 'boom'] }

        when:
        def response = mcpDriver.callTool('hub_get_info', [:])

        then:
        response.result.isError == true
        !response.result.containsKey('structuredContent')
    }

    def "publishOutputSchemas ON: an oversized schema-advertised result returns the too-large envelope AS AN ERROR"() {
        given: 'gateway mode + schemas published, and a base tool returning >120KB'
        settingsMap.useGateways = true
        settingsMap.publishOutputSchemas = true
        script.metaClass.toolGetHubInfo = { a -> [blob: 'x' * 130000] }

        when:
        def response = mcpDriver.callTool('hub_get_info', [:])

        then: 'isError=true -- a text-only NON-error result of a schema-advertised tool violates the spec MUST (structuredContent), so spec-validating clients would reject it with the same generic failure #342 was filed about; error results are exempt from validation'
        response.result.isError == true
        response.result.content[0].text.contains('response_too_large')
        !response.result.containsKey('structuredContent')
    }

    def "publishOutputSchemas OFF: an oversized result keeps the long-standing non-error too-large envelope"() {
        given:
        settingsMap.useGateways = true
        script.metaClass.toolGetHubInfo = { a -> [blob: 'x' * 130000] }

        when:
        def response = mcpDriver.callTool('hub_get_info', [:])

        then: 'no schema is advertised, so the #174 non-error shape (model reads the suggestion and retries) is unchanged'
        !response.result.containsKey('isError')
        response.result.content[0].text.contains('response_too_large')
    }

    def "_wireOutputSchema strips required arrays recursively but keeps a property literally named 'required'"() {
        given:
        def schema = [
            type: 'object',
            properties: [
                success : [type: 'boolean'],
                required: [type: 'string', description: 'a property that happens to be named required'],
                nested  : [type: 'object', properties: [x: [type: 'string']], required: ['x']],
                list    : [type: 'array', items: [type: 'object', properties: [y: [type: 'number']], required: ['y']]]
            ],
            required: ['success']
        ]

        when:
        def wire = script._wireOutputSchema(schema)

        then: 'every schema-keyword required array is gone; the property named required survives'
        !wire.containsKey('required')
        !wire.properties.nested.containsKey('required')
        !wire.properties.list.items.containsKey('required')
        wire.properties.required == [type: 'string', description: 'a property that happens to be named required']

        and: 'the original definition map is untouched (wire form is a copy)'
        schema.required == ['success']
        schema.properties.nested.required == ['x']
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
