package server

import support.ToolSpecBase

/**
 * Drives {@code handleMcpRequest()} through its in-process dispatch pipeline:
 * {@code request.JSON} in, {@code render(...)} out — instead of invoking the
 * JSON-RPC dispatch layer directly as {@code HandleToolsCallSpec} does.
 *
 * This is the tier-2 integration dispatch seam (one JVM, Spock Mocks, no
 * real HTTP boundary) described in docs/testing.md. Coverage goals here are
 * distinct from the unit-layer {@code Tool*Spec} files:
 *
 *   - {@code request.JSON} parse path: null body (-32700), getter-throws
 *     (-32700 from the try/catch), batch array, single object
 *   - {@code render(Map)} envelope (status, contentType, data)
 *   - JSON-RPC -32600 branches: empty batch, missing jsonrpc field,
 *     missing method
 *   - JSON-RPC -32601 (method-not-found) and -32603 (response-too-large)
 *   - Notification short-circuit (id-less request → 204 no-content)
 *   - Batch per-item error isolation (a failing item must not poison a
 *     later success)
 *   - tools/call error-envelope at the HTTP shell (isError: true wrapped
 *     in a 200 render, not a naked hub 500)
 *   - /health and GET /mcp handlers (small but distinct render envelopes)
 *
 * Part of #77 (tier-2). The tier-3 fake-hub work remains tracked there.
 */
class HandleMcpRequestDispatchSpec extends ToolSpecBase {

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
        // Semver pattern — a regression that returned '' or 'unknown' would
        // satisfy a naked truthy check, so pin the actual shape currentVersion()
        // produces.
        response.result.serverInfo.version ==~ /\d+\.\d+\.\d+.*/
    }

    def "tools/list returns the tool catalog with known tools present"() {
        given:
        settingsMap.useGateways = true  // assertion expects gateway entries; pin against harness flat-mode pre-seed
        mcpDriver.pushBody([jsonrpc: '2.0', id: 2, method: 'tools/list', params: [:]])

        when:
        script.handleMcpRequest()

        then:
        def response = mcpDriver.parseResponseJson()
        response.jsonrpc == '2.0'
        response.id == 2
        response.result.tools instanceof List

        and: 'at least these well-known entries are in the catalog by name (non-gatewayed base tools + a gateway)'
        def names = response.result.tools*.name as Set
        // list_devices + get_device are base tools (not behind a gateway) — getToolDefinitions
        // at hubitat-mcp-server.groovy:717-744 folds gatewayed tools under a gateway entry
        // instead of listing them individually.
        names.contains('list_devices')
        names.contains('get_device')
        // manage_rooms is a gateway, so it appears by its gateway name, not its sub-tool names.
        names.contains('manage_rooms')

        and: 'every entry has the MCP tool shape with non-blank name + description'
        response.result.tools.every {
            it.name instanceof String && !it.name.isEmpty() &&
            it.description instanceof String && !it.description.isEmpty() &&
            it.inputSchema instanceof Map
        }
    }

    def "tools/list with useGateways=false returns the flat catalog through the JSON-RPC envelope (iterating cursors)"() {
        given: 'feature toggles on so the JSON-RPC envelope returns the full flat catalog'
        settingsMap.useGateways = false
        settingsMap.enableBuiltinApp = true
        settingsMap.enableCustomRuleEngine = true

        when: 'iterate nextCursor until exhausted -- flat-mode catalog exceeds one page'
        def allNamesList = []      // List (not Set) so an off-by-one cursor that duplicates a tool surfaces as a size mismatch.
        def cursor = null
        def pageCount = 0
        for (;;) {
            pageCount++
            mcpDriver.pushBody([jsonrpc: '2.0', id: 50 + pageCount, method: 'tools/list', params: (cursor == null ? [:] : [cursor: cursor])])
            script.handleMcpRequest()
            def response = mcpDriver.parseResponseJson()
            assert response.jsonrpc == '2.0'
            assert response.id == 50 + pageCount
            // nextCursor must be a String on the wire per MCP spec (cursor is opaque-string-typed).
            // A regression to an Integer or other type would still round-trip via (cursor as String) on the request side,
            // but is a spec violation -- pin it here.
            if (response.result.nextCursor != null) {
                assert response.result.nextCursor instanceof String : "nextCursor must be String, got ${response.result.nextCursor.class.simpleName}"
            }
            // Page-size invariant: each page MUST be <= pageSize entries. Pins the size
            // constant against silent regression -- e.g., bumping to pageSize=200 would
            // re-introduce the >128KB hub-limit failure but would still pass `pageCount > 1`
            // and `tools.size() > 0` as long as the catalog fits in one page.
            assert response.result.tools.size() <= 50 : "page exceeded pageSize=50: got ${response.result.tools.size()}"
            allNamesList.addAll(response.result.tools*.name)
            cursor = response.result.nextCursor
            if (cursor == null) break
            assert pageCount < 20 : "tools/list pagination iterated more than 20 pages -- runaway"
        }
        def allNames = allNamesList as Set

        then: 'no duplicate tool surfaced across pages (off-by-one cursor arithmetic regression guard)'
        allNamesList.size() == allNames.size()

        and: 'gateway entries gone, sub-tools surface, search_tools suppressed'
        !allNames.contains('manage_rooms')
        !allNames.contains('manage_files')
        !allNames.contains('search_tools')
        allNames.contains('list_rooms')
        allNames.contains('list_files')
        allNames.contains('list_devices')

        and: 'feature-toggle-gated tools also surface (proves the envelope returns the full flat catalog, not just the cores)'
        allNames.contains('list_rm_rules')
        allNames.contains('list_installed_apps')
        allNames.contains('custom_create_rule')

        and: 'flat-mode catalog was big enough to require pagination'
        pageCount > 1
    }

    def "tools/list gateway-mode catalog fits on a single page (no nextCursor)"() {
        given: 'useGateways=true; catalog is 36 entries, under page size of 50'
        settingsMap.useGateways = true  // pin against harness flat-mode pre-seed
        mcpDriver.pushBody([jsonrpc: '2.0', id: 60, method: 'tools/list', params: [:]])

        when:
        script.handleMcpRequest()

        then:
        def response = mcpDriver.parseResponseJson()
        response.result.tools instanceof List
        response.result.tools.size() > 0

        and: 'no nextCursor because the gateway-mode catalog fits in one page'
        !response.result.containsKey('nextCursor')
    }

    def "tools/list rejects a non-numeric cursor with -32602"() {
        given:
        mcpDriver.pushBody([jsonrpc: '2.0', id: 70, method: 'tools/list', params: [cursor: 'not-a-number']])

        when:
        script.handleMcpRequest()

        then:
        def response = mcpDriver.parseResponseJson()
        response.error != null
        response.error.code == -32602
        response.error.message.toLowerCase().contains('cursor')
    }

    def "tools/list rejects an out-of-range cursor with -32602"() {
        given:
        mcpDriver.pushBody([jsonrpc: '2.0', id: 71, method: 'tools/list', params: [cursor: '999999']])

        when:
        script.handleMcpRequest()

        then:
        def response = mcpDriver.parseResponseJson()
        response.error != null
        response.error.code == -32602
        response.error.message.toLowerCase().contains('out of range')
    }

    def "tools/list rejects a negative cursor with -32602 (out-of-range, not -32603 IOOBE)"() {
        // "-5".toInteger() returns -5 without throwing NumberFormatException, so it bypasses
        // the parse-catch and lands in the startIdx < 0 disjunct of the range check. If that
        // disjunct were ever dropped, subList(-5, end) would throw IndexOutOfBoundsException,
        // propagating as -32603 ("Internal error") instead of -32602. Pin the -32602 contract.
        given:
        mcpDriver.pushBody([jsonrpc: '2.0', id: 73, method: 'tools/list', params: [cursor: '-5']])

        when:
        script.handleMcpRequest()

        then:
        def response = mcpDriver.parseResponseJson()
        response.error != null
        response.error.code == -32602
        response.error.message.toLowerCase().contains('out of range')
    }

    def "tools/list with empty cursor string starts from the first page"() {
        given: 'empty-string cursor should behave like a missing cursor (start from beginning)'
        mcpDriver.pushBody([jsonrpc: '2.0', id: 72, method: 'tools/list', params: [cursor: '']])

        when:
        script.handleMcpRequest()

        then:
        def response = mcpDriver.parseResponseJson()
        response.error == null
        response.result.tools instanceof List
        response.result.tools.size() > 0
    }

    def "tools/call returns response_too_large envelope when serialized result exceeds the 110KB cap (#174)"() {
        given: 'a tool whose serialized result will exceed the 110KB universal-guard threshold'
        // Each room serializes to roughly 80-100 bytes once name + deviceCount + deviceIds
        // overhead is counted; 2000 such rooms with a padded name push the toolListRooms
        // result well past 110KB so the guard kicks in.
        def padding = 'x' * 80
        def bigRooms = (0..<2000).collect { i ->
            [id: i as Long, name: "Room-${i}-${padding}"]
        }
        script.metaClass.getRooms = { -> bigRooms }
        mcpDriver.pushBody([
            jsonrpc: '2.0', id: 100, method: 'tools/call',
            params: [name: 'list_rooms', arguments: [:]]
        ])

        when:
        script.handleMcpRequest()

        then: 'JSON-RPC success envelope — fail-soft is NOT a JSON-RPC error'
        def response = mcpDriver.parseResponseJson()
        response.jsonrpc == '2.0'
        response.id == 100
        response.error == null

        and: 'inner content carries the structured response_too_large envelope (not isError)'
        // isError is reserved for tool execution failure per the MCP spec; response-too-large
        // is a soft outcome (the tool ran, the result just doesnt fit), and clients should
        // be able to react to it programmatically rather than treating it as a hard failure.
        response.result.isError != true
        def inner = new groovy.json.JsonSlurper().parseText(response.result.content[0].text)
        inner.response_too_large == true
        inner.truncated == true
        inner.tool == 'list_rooms'
        inner.sizeLimitBytes == 110000
        inner.estimatedBytes instanceof Number
        inner.estimatedBytes > inner.sizeLimitBytes
        inner.suggestion instanceof String
        !inner.suggestion.isEmpty()

        and: 'no rooms leak through the envelope (the whole point of fail-soft)'
        inner.rooms == null
    }

    def "tools/call passes small results through unchanged (size guard does not perturb normal traffic)"() {
        given: 'a tiny rooms list well under the cap'
        script.metaClass.getRooms = { -> [[id: 1L, name: 'Den']] }
        mcpDriver.pushBody([
            jsonrpc: '2.0', id: 101, method: 'tools/call',
            params: [name: 'list_rooms', arguments: [:]]
        ])

        when:
        script.handleMcpRequest()

        then: 'the inner content is the real tool result, not the fail-soft envelope'
        def response = mcpDriver.parseResponseJson()
        response.error == null
        def inner = new groovy.json.JsonSlurper().parseText(response.result.content[0].text)
        inner.response_too_large == null
        inner.rooms*.name == ['Den']
    }

    def "tools/call list_rooms flows through render with an MCP content envelope"() {
        given: 'a stubbed getRooms returning a deterministic list'
        script.metaClass.getRooms = { ->
            [[id: 1L, name: 'Living Room'], [id: 2L, name: 'Kitchen']]
        }
        // list_rooms lives behind the manage_rooms gateway in tools/list, but executeTool
        // still dispatches it directly by tool name at hubitat-mcp-server.groovy:1853 — the
        // gateway is a tools/list folding convention, not a dispatch barrier.
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
        def inner = mcpDriver.parseInner(response)
        inner.rooms*.name.containsAll(['Living Room', 'Kitchen'])
    }

    def "tools/call wraps thrown tool exceptions as isError at the HTTP shell"() {
        given: 'a tool that throws — production must still render a 200 with isError in body, never a hub 500'
        script.metaClass.getRooms = { ->
            throw new RuntimeException('simulated hub failure')
        }
        mcpDriver.pushBody([
            jsonrpc: '2.0', id: 30, method: 'tools/call',
            params: [name: 'list_rooms', arguments: [:]]
        ])

        when:
        script.handleMcpRequest()

        then: 'render was called — the exception did not escape the handler'
        mcpDriver.lastRenderArgs.contentType == 'application/json'
        // JSON-RPC envelope present (tools/call reports tool errors per the MCP spec as
        // successful jsonRpcResult with an isError flag on the content envelope, not as a
        // JSON-RPC error object)
        def response = mcpDriver.parseResponseJson()
        response.jsonrpc == '2.0'
        response.id == 30
        response.error == null

        and: 'inner content carries isError:true with the failure message in the text payload'
        response.result.isError == true
        response.result.content[0].type == 'text'
        response.result.content[0].text.contains('Tool error')
        response.result.content[0].text.contains('simulated hub failure')
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

    def "batch request returns an array of responses with matching shapes, skipping notifications"() {
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

        and: 'initialize response (id=10) carries the expected MCP shape'
        def init = response.find { it.id == 10 }
        init.result.protocolVersion == '2024-11-05'
        init.result.serverInfo.name == 'hubitat-mcp-rule-server'

        and: 'ping response (id=11) is the empty-map success shape'
        def ping = response.find { it.id == 11 }
        ping.result == [:]

        and: 'both entries are well-formed success envelopes'
        response.every { it.jsonrpc == '2.0' && it.error == null }
    }

    def "batch isolates per-item errors — a failing item does not poison later successes"() {
        given: 'a three-item batch: success, failure, success'
        mcpDriver.pushBody([
            [jsonrpc: '2.0', id: 20, method: 'initialize', params: [:]],
            [jsonrpc: '2.0', id: 21, method: 'does/not/exist'],
            [jsonrpc: '2.0', id: 22, method: 'ping', params: [:]]
        ])

        when:
        script.handleMcpRequest()

        then: 'all three responses present, keyed by id'
        def response = mcpDriver.parseResponseJson()
        response instanceof List
        response.size() == 3

        and: 'item 20 is a success'
        def first = response.find { it.id == 20 }
        first.result.protocolVersion == '2024-11-05'
        first.error == null

        and: 'item 21 is a method-not-found error — isolated, does not affect neighbours'
        def middle = response.find { it.id == 21 }
        middle.error.code == -32601

        and: 'item 22 is a success — not contaminated by item 21'
        def last = response.find { it.id == 22 }
        last.result == [:]
        last.error == null
    }

    def "empty batch array returns -32600 with the exact invalid-request message"() {
        given:
        mcpDriver.pushBody([])

        when:
        script.handleMcpRequest()

        then:
        def response = mcpDriver.parseResponseJson()
        response.jsonrpc == '2.0'
        response.id == null
        response.error.code == -32600
        response.error.message == 'Invalid Request: empty batch array'
    }

    def "null body returns parse error -32700 (requestBody == null branch)"() {
        given: 'request.JSON is null — production treats this as an unparseable body'
        mcpDriver.pushBody(null)

        when:
        script.handleMcpRequest()

        then:
        def response = mcpDriver.parseResponseJson()
        response.jsonrpc == '2.0'
        response.id == null
        response.error.code == -32700
        response.error.message == 'Parse error: empty or invalid JSON body'
    }

    def "request.JSON throwing returns parse error -32700 (try/catch branch)"() {
        given: 'hub-side JSON parser choked — request.JSON access itself throws'
        mcpDriver.pushBodyThrowing(new RuntimeException('simulated hub-side JSON parse failure'))

        when:
        script.handleMcpRequest()

        then: 'production catch at hubitat-mcp-server.groovy:286-292 turned it into -32700'
        def response = mcpDriver.parseResponseJson()
        response.jsonrpc == '2.0'
        response.id == null
        response.error.code == -32700
        response.error.message == 'Parse error: invalid JSON'
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
        response.error.message == 'Method not found: does/not/exist'
    }

    def "missing jsonrpc field returns -32600 and echoes the client's id per JSON-RPC 2.0 §5.1"() {
        given: 'body shaped like a JSON-RPC call but without the required jsonrpc marker'
        mcpDriver.pushBody([id: 5, method: 'initialize'])

        when:
        script.handleMcpRequest()

        then: 'error code + message'
        def response = mcpDriver.parseResponseJson()
        response.error.code == -32600
        response.error.message == 'Invalid Request: must use JSON-RPC 2.0'

        and: 'id was echoed back — contract-locking per §5.1'
        response.id == 5
    }

    def "oversize tool response is replaced with -32603 Response-too-large"() {
        given: 'a stubbed tool that returns more than the 124KB cap'
        // Build a big label that after JSON-encoding + envelope wrapping
        // blows past the 124000-byte threshold at hubitat-mcp-server.groovy:321.
        String bigLabel = 'x' * 150_000
        script.metaClass.getRooms = { ->
            [[id: 1L, name: bigLabel]]
        }
        mcpDriver.pushBody([
            jsonrpc: '2.0', id: 40, method: 'tools/call',
            params: [name: 'list_rooms', arguments: [:]]
        ])

        when:
        script.handleMcpRequest()

        then: 'response is the -32603 error, not the oversize payload'
        def response = mcpDriver.parseResponseJson()
        response.jsonrpc == '2.0'
        response.id == 40
        response.error.code == -32603
        response.error.message.contains('Response too large')
        response.error.message.contains("exceeds hub's 128KB limit")
    }

    def "handleMcpGet returns 405 with the use-POST hint"() {
        when:
        script.handleMcpGet()

        then:
        mcpDriver.lastRenderArgs.status == 405
        mcpDriver.lastRenderArgs.contentType == 'application/json'
        def body = mcpDriver.parseResponseJson()
        body.error == 'GET not supported, use POST'
    }

    def "handleHealth returns status/server/version from currentVersion()"() {
        when:
        script.handleHealth()

        then:
        mcpDriver.lastRenderArgs.contentType == 'application/json'
        def body = mcpDriver.parseResponseJson()
        body.status == 'ok'
        body.server == 'hubitat-mcp-rule-server'
        // Pin the shape, not a specific version literal (avoids churn on
        // every release bump).
        body.version ==~ /\d+\.\d+\.\d+.*/
    }
}
