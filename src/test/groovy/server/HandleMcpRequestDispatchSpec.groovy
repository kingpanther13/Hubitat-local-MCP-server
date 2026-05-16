package server

import support.TestDevice
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
 *   - JSON-RPC -32601 (method-not-found); -32603 (response-too-large) is now
 *     superseded for tools/call by the universal fail-soft size guard at
 *     handleToolsCall (#174) -- tools/call returns a structured
 *     response_too_large envelope on success, not a JSON-RPC error. The outer
 *     handleMcpRequest guard remains as a backstop for other RPC methods.
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

    def "tools/call returns response_too_large envelope when wire-encoded response exceeds the universal-guard threshold"() {
        given: 'a tool whose wire-encoded response will exceed the 120KB universal-guard threshold'
        // 2000 padded rooms ~> >120KB once wire-serialized.
        def padding = 'x' * 80
        def bigRooms = (0..<2000).collect { i ->
            [id: i as Long, name: "Room-${i}-${padding}"]
        }
        // Defensive: if anyone retunes the threshold or trims the room shape, fail loud
        // here instead of letting the test silently slide into the pass-through branch.
        def roomList = bigRooms.collect { [id: it.id?.toString(), name: it.name, deviceCount: 0, deviceIds: []] }
        assert groovy.json.JsonOutput.toJson([rooms: roomList, count: roomList.size()]).getBytes("UTF-8").length > 120000
        script.metaClass.getRooms = { -> bigRooms }
        mcpDriver.pushBody([
            jsonrpc: '2.0', id: 100, method: 'tools/call',
            params: [name: 'list_rooms', arguments: [:]]
        ])

        when:
        script.handleMcpRequest()

        then: 'JSON-RPC success envelope -- fail-soft is not a JSON-RPC error'
        def response = mcpDriver.parseResponseJson()
        response.jsonrpc == '2.0'
        response.id == 100
        response.error == null

        and: 'inner content carries the structured envelope (no isError -- fail-soft is not a tool error)'
        response.result.isError != true
        def inner = new groovy.json.JsonSlurper().parseText(response.result.content[0].text)
        inner.response_too_large == true
        inner.truncated == true
        inner.tool == 'list_rooms'
        inner.sizeLimitBytes == 120000
        inner.estimatedBytes instanceof Number
        inner.estimatedBytes > inner.sizeLimitBytes
        inner.suggestion instanceof String
        !inner.suggestion.isEmpty()

        and: 'no rooms leak through the envelope (the whole point of fail-soft)'
        inner.rooms == null
    }

    def "size-guard mcpLog entry carries the warn level + structured details map a debug-log consumer can read"() {
        given: 'oversize result so the guard fires + debug-log scaffolding wired (mcpLog otherwise no-ops at default log level)'
        stateMap.debugLogs = [
            entries: [],
            config: [logLevel: 'debug', maxEntries: 1000]
        ]
        settingsMap.mcpLogLevel = 'debug'
        def padding = 'x' * 80
        script.metaClass.getRooms = { -> (0..<2000).collect { i -> [id: i as Long, name: "Room-${i}-${padding}"] } }
        mcpDriver.pushBody([jsonrpc: '2.0', id: 110, method: 'tools/call', params: [name: 'list_rooms', arguments: [:]]])

        when:
        script.handleMcpRequest()

        then:
        def warn = (stateMap.debugLogs?.entries ?: []).find { it.message?.contains('response too large') }
        warn != null
        warn.level == 'warn'
        warn.details.tool == 'list_rooms'
        warn.details.gateway == null  // direct call, not gateway-routed
        warn.details.bytes > 120000
        warn.details.limit == 120000
    }

    def "size guard surfaces the inner sub-tool name + gateway hint when called through a manage_* gateway (#174)"() {
        given: 'a stubbed sub-tool (get_app_config) that returns a huge config'
        settingsMap.enableBuiltinApp = true
        settingsMap.enableHubAdminRead = true
        // Build a get_app_config response large enough to trip the wire-byte guard once
        // wrapped + escaped + envelope-encoded.
        def bigSettings = (0..<3000).collectEntries { i -> ["k${i}".toString(), ("v" * 50)] }
        script.metaClass.toolGetAppConfig = { Map args -> [success: true, app: [id: 99, label: 'X'], settings: bigSettings] }
        stateMap.debugLogs = [
            entries: [],
            config: [logLevel: 'debug', maxEntries: 1000]
        ]
        settingsMap.mcpLogLevel = 'debug'
        // Gateway-routed call: name=manage_installed_apps, args carries tool+args.
        mcpDriver.pushBody([
            jsonrpc: '2.0', id: 200, method: 'tools/call',
            params: [name: 'manage_installed_apps', arguments: [tool: 'get_app_config', args: [appId: '99', includeSettings: true]]]
        ])

        when:
        script.handleMcpRequest()

        then: 'envelope reports the SUB-tool, not the gateway, so the LLMs retry hint matches the call it issued'
        def response = mcpDriver.parseResponseJson()
        response.error == null
        def inner = new groovy.json.JsonSlurper().parseText(response.result.content[0].text)
        inner.response_too_large == true
        inner.tool == 'get_app_config'
        inner.suggestion.contains('includeSettings')

        and: 'debug-log details surface the gateway/sub-tool split for an operator running get_debug_logs'
        def warn = (stateMap.debugLogs?.entries ?: []).find { it.message?.contains('response too large') }
        warn.details.tool == 'get_app_config'
        warn.details.gateway == 'manage_installed_apps'
    }

    def "non-gateway caller passing a stray `tool` arg does NOT route the suggestion to the wrong tool"() {
        // Defends against the would-be bug where a direct (non-gateway) caller with a
        // stray args.tool='export_native_app' on list_devices would get an export_native_app
        // suggestion ("use saveAs=..."), nonsensical for list_devices.
        given:
        // Force list_devices to blow the cap by stubbing a giant selected-device list.
        def padding = 'x' * 80
        def bigDevices = (0..<2000).collect { i ->
            def d = new TestDevice(id: i, name: "D${i}", label: "Device-${i}-${padding}")
            d.metaClass.getLastActivity = { -> null }
            d
        }
        settingsMap.selectedDevices = bigDevices
        mcpDriver.pushBody([
            jsonrpc: '2.0', id: 201, method: 'tools/call',
            params: [name: 'list_devices', arguments: [tool: 'export_native_app']]
        ])

        when:
        script.handleMcpRequest()

        then:
        def inner = new groovy.json.JsonSlurper().parseText(mcpDriver.parseResponseJson().result.content[0].text)
        inner.response_too_large == true
        inner.tool == 'list_devices'                       // NOT export_native_app
        inner.suggestion.contains('filter')                // list_devices guidance
        !inner.suggestion.contains('saveAs')               // not export_native_app guidance
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

    def "tools/call surfaces a structured isError envelope when the tool implementation returns null"() {
        // Defends against a future tool whose last expression evaluates to null -- without
        // the explicit null guard, the wire payload becomes text: "null" which looks like
        // a normal tool result to an LLM.
        // Dispatch always calls toolListRooms(args) so a 1-arg metaClass override
        // intercepts cleanly.
        given:
        script.metaClass.toolListRooms = { ignored -> null }
        mcpDriver.pushBody([jsonrpc: '2.0', id: 102, method: 'tools/call', params: [name: 'list_rooms', arguments: [:]]])

        when:
        script.handleMcpRequest()

        then:
        def response = mcpDriver.parseResponseJson()
        response.error == null
        response.result.isError == true
        def inner = new groovy.json.JsonSlurper().parseText(response.result.content[0].text)
        inner.isError == true
        inner.error.contains('list_rooms')
        inner.tool == 'list_rooms'
    }

    // The non-serializable-result branch in handleToolsCall is defensive: groovy.json.JsonOutput
    // silently coerces most "weird" types (Closure -> {}, Pattern -> {pattern, flags}, etc.) so
    // there is no portable way to deterministically trigger the catch from a Spock spec.
    // The branch is exercised by code review + the production path it protects (a future tool
    // returning something genuinely unserializable). Kept here as documentation of the gap.

    @spock.lang.Unroll
    def "_responseTooLargeSuggestion returns tool-specific guidance for #toolName"() {
        expect:
        def suggestion = script._responseTooLargeSuggestion(toolName)
        suggestion instanceof String
        !suggestion.isEmpty()
        suggestion.toLowerCase().contains(expectedFragment.toLowerCase())

        where:
        toolName              | expectedFragment
        'list_devices'        | 'filter'
        'list_installed_apps' | 'cursor'
        'get_app_config'      | 'includeSettings'
        'device_health_check' | 'includeHealthy'
        'get_memory_history'  | 'limit'
        'get_hub_logs'        | 'pattern'
        'export_native_app'   | 'saveAs'
        'get_hub_info'        | 'subsection'
        'get_app_source'      | 'File Manager'
        'unknown_tool_xyz'    | 'narrow your query'
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
