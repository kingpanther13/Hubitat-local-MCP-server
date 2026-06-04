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

        and: 'at least these well-known entries are in the catalog by name (flat top-level tools + a gateway)'
        def names = response.result.tools*.name as Set
        // hub_get_info + hub_get_hsm_status are flat (always top-level) tools — getToolDefinitions
        // at hubitat-mcp-server.groovy folds gatewayed tools (device + custom-rule tools included)
        // under a gateway entry instead of listing them individually, so device tools are NOT
        // top-level in gateway mode.
        names.contains('hub_get_info')
        names.contains('hub_get_hsm_status')
        // device tools now live behind the hub_read_devices / hub_manage_devices gateways.
        !names.contains('hub_list_devices')
        // hub_manage_rooms is a gateway, so it appears by its gateway name, not its sub-tool names.
        names.contains('hub_manage_rooms')

        and: 'every entry has the MCP tool shape with non-blank name + description'
        response.result.tools.every {
            it.name instanceof String && !it.name.isEmpty() &&
            it.description instanceof String && !it.description.isEmpty() &&
            it.inputSchema instanceof Map
        }

        and: 'no inputSchema carries a top-level anyOf/oneOf/allOf (issue #204 regression guard — Anthropic input_schema validator HTTP-400s on these; first surfaced via Haiku 4.5)'
        // Iterates the full catalog so this guard catches a new tool added
        // anywhere in getToolDefinitions(), not just the one that originally
        // tripped it (hub_import_native_app). Both modes carry this assertion
        // because the flat catalog is what Anthropic-validator clients
        // actually see (gateway-mode hides sub-tool schemas under the
        // gateway entry's catalog payload, but the catch-all here still
        // pins the gateway entries themselves).
        response.result.tools.every { tool ->
            !tool.inputSchema.containsKey('anyOf') &&
            !tool.inputSchema.containsKey('oneOf') &&
            !tool.inputSchema.containsKey('allOf')
        }

        and: 'MCP annotations survive serialization through jsonRpcResult → render'
        // McpToolAnnotationsSpec pins the in-process map shape; this `and:`
        // pins that the annotation keys actually land in the wire envelope
        // (Claude.ai's catalog grouping only cares about what gets serialized).
        // A regression in applyDescriptionTransform / JsonOutput that stripped
        // the `annotations` key would silently undo the Read/Write split.
        response.result.tools.every { it.annotations?.readOnlyHint instanceof Boolean }
        def getInfo = response.result.tools.find { it.name == 'hub_get_info' }
        getInfo.annotations.readOnlyHint == true
        getInfo.annotations.containsKey('destructiveHint') == false
        def manageDestructive = response.result.tools.find { it.name == 'hub_manage_destructive_ops' }
        manageDestructive.annotations.readOnlyHint == false
        manageDestructive.annotations.destructiveHint == true
    }

    def "tools/list with useGateways=false returns the full flat catalog in a single response (no pagination)"() {
        given: 'feature toggles on so the JSON-RPC envelope returns the full flat catalog'
        settingsMap.useGateways = false
        settingsMap.enableBuiltinApp = true
        settingsMap.enableCustomRuleEngine = true
        mcpDriver.pushBody([jsonrpc: '2.0', id: 51, method: 'tools/list', params: [:]])

        when:
        script.handleMcpRequest()
        def response = mcpDriver.parseResponseJson()

        then: 'JSON-RPC 2.0 success envelope'
        response.jsonrpc == '2.0'
        response.id == 51
        response.error == null

        and: 'no nextCursor on the response -- pagination removed, full catalog returned at once'
        // Pagination on tools/list was removed because MCP clients in the
        // wild (including Claude.ai's connector) frequently do NOT iterate
        // nextCursor automatically, leading to silent catalog truncation at
        // the first page. Returning the full catalog -- backstopped by the
        // universal response-size guard at handleMcpRequest() that emits a
        // loud -32603 envelope if the catalog exceeds the hub's 124,000-byte
        // cap -- avoids that footgun. Any client that DOES iterate nextCursor
        // simply finds none and terminates after one call. Note: cursor
        // pagination on tools/call (hub_list_devices, hub_list_apps, etc.
        // via _paginateList) is unchanged -- that is opt-in and remains.
        !response.result.containsKey('nextCursor')

        and: 'response carries every flat-mode tool (gateway entries gone, sub-tools surface, hub_search_tools suppressed)'
        def names = response.result.tools*.name as Set
        !names.contains('hub_manage_rooms')
        !names.contains('hub_manage_files')
        !names.contains('hub_search_tools')
        names.contains('hub_list_rooms')
        names.contains('hub_list_files')
        names.contains('hub_list_devices')

        and: 'feature-toggle-gated tools also surface (proves the envelope returns the full flat catalog, not just the cores)'
        names.contains('hub_list_rules')
        names.contains('hub_list_apps')
        names.contains('hub_create_custom_rule')

        and: 'no duplicate tool names in the response'
        response.result.tools.size() == names.size()

        and: 'no flat-mode inputSchema carries a top-level anyOf/oneOf/allOf (issue #204 regression guard)'
        // Flat mode is the catalog Anthropic-validator clients (Claude.ai
        // connector, Claude Code haiku subagent) actually walk. Top-level
        // anyOf/oneOf/allOf in any tool here HTTP-400s the entire tools/list
        // dispatch, so this guard catches a regression in any of the
        // ~80 flat-catalog tools, not just the one being patched.
        response.result.tools.every { tool ->
            !tool.inputSchema.containsKey('anyOf') &&
            !tool.inputSchema.containsKey('oneOf') &&
            !tool.inputSchema.containsKey('allOf')
        }

        and: 'flat-mode wire envelope also carries readOnlyHint per leaf tool'
        response.result.tools.every { it.annotations?.readOnlyHint instanceof Boolean }
        def listRooms = response.result.tools.find { it.name == 'hub_list_rooms' }
        listRooms.annotations.readOnlyHint == true
        listRooms.annotations.containsKey('destructiveHint') == false
        def deleteRoom = response.result.tools.find { it.name == 'hub_delete_room' }
        deleteRoom.annotations.readOnlyHint == false
        deleteRoom.annotations.destructiveHint == true
    }

    def "tools/list gateway-mode catalog also returns in a single response with no nextCursor"() {
        given: 'useGateways=true; gateway catalog (~36 entries) was always single-response, regression guard for that'
        settingsMap.useGateways = true  // pin against harness flat-mode pre-seed
        mcpDriver.pushBody([jsonrpc: '2.0', id: 60, method: 'tools/list', params: [:]])

        when:
        script.handleMcpRequest()

        then:
        def response = mcpDriver.parseResponseJson()
        response.result.tools instanceof List
        response.result.tools.size() > 0

        and: 'no nextCursor in either mode now that tools/list pagination is removed'
        !response.result.containsKey('nextCursor')
    }

    def "tools/list ignores any cursor parameter a stale client passes (graceful migration)"() {
        // tools/list cursor handling was removed when the unconditional split was
        // dropped. Stale clients that pass a cursor from a prior version's
        // nextCursor (or any value -- numeric, non-numeric, out-of-range, negative,
        // empty) now receive the full catalog rather than a -32602 error. Their
        // iteration loop terminates on the missing nextCursor in the same response.
        // Opt-in tools/call cursors (hub_list_devices etc.) are not affected.
        given:
        mcpDriver.pushBody([jsonrpc: '2.0', id: 70, method: 'tools/list', params: [cursor: cursorValue]])

        when:
        script.handleMcpRequest()

        then:
        def response = mcpDriver.parseResponseJson()
        response.error == null
        response.result.tools instanceof List
        response.result.tools.size() > 0
        !response.result.containsKey('nextCursor')

        where:
        cursorValue << ['not-a-number', '999999', '-5', '', '50']
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
            params: [name: 'hub_list_rooms', arguments: [:]]
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
        inner.tool == 'hub_list_rooms'
        inner.sizeLimitBytes == 120000
        inner.estimatedBytes instanceof Number
        inner.estimatedBytes > inner.sizeLimitBytes
        inner.suggestion instanceof String
        !inner.suggestion.isEmpty()

        and: 'no rooms leak through the envelope (the whole point of fail-soft)'
        inner.rooms == null
    }

    def "JsonOutput escapes non-ASCII to ASCII, so the outer size guard's char-length sizing equals UTF-8 byte length (refutes the issue #105 multibyte-undercount finding)"() {
        // The #105 backend audit flagged the outer guard at handleMcpRequest (which sizes by
        // jsonResponse.length() for sub-threshold responses) as undercounting multibyte UTF-8.
        // It does not: groovy.json.JsonOutput.toJson escapes every char > 126 to a \\uXXXX ASCII
        // sequence on both Hubitat's Groovy 2.4 runtime and the Groovy 3.0 harness (the
        // disableUnicodeEscaping opt-out only exists on Groovy 4.0.19+). So the wire payload is
        // always pure ASCII and char length always equals UTF-8 byte length -- nothing multibyte
        // can slip past the 124KB backstop. This test pins that invariant and will trip if a
        // future Groovy upgrade ever stops escaping (which WOULD make the undercount real).
        given: 'a response payload laden with multibyte content -- CJK, accented Latin, and an astral-plane emoji'
        def payload = [jsonrpc: '2.0', id: 1, result: [tools: [[name: 'x', description: ('中é😀' * 2000)]]]]

        when: 'serialized through the same JsonOutput.toJson the dispatch layer uses'
        def json = groovy.json.JsonOutput.toJson(payload)

        then: 'non-ASCII is escaped to ASCII \\uXXXX (no raw multibyte survives in the wire form)'
        json.contains('\\u4e2d')   // 中
        !json.contains('中')

        and: 'therefore char length == UTF-8 byte length, so the outer guard cannot undercount'
        json.length() == json.getBytes('UTF-8').length
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
        mcpDriver.pushBody([jsonrpc: '2.0', id: 110, method: 'tools/call', params: [name: 'hub_list_rooms', arguments: [:]]])

        when:
        script.handleMcpRequest()

        then:
        def warn = (stateMap.debugLogs?.entries ?: []).find { it.message?.contains('response too large') }
        warn != null
        warn.level == 'warn'
        warn.details.tool == 'hub_list_rooms'
        warn.details.gateway == null  // direct call, not gateway-routed
        warn.details.bytes > 120000
        warn.details.limit == 120000
    }

    def "size guard surfaces the inner sub-tool name + gateway hint when called through a manage_* gateway (#174)"() {
        given: 'a stubbed sub-tool (hub_get_app_config) that returns a huge config'
        settingsMap.enableBuiltinApp = true
        settingsMap.enableHubAdminRead = true
        // useGateways=true so hub_read_apps_code actually dispatches (PR #187/#191's
        // flat-mode matrix would otherwise short-circuit gateway calls with isError).
        settingsMap.useGateways = true
        // Build a hub_get_app_config response large enough to trip the wire-byte guard once
        // wrapped + escaped + envelope-encoded.
        def bigSettings = (0..<3000).collectEntries { i -> ["k${i}".toString(), ("v" * 50)] }
        script.metaClass.toolGetAppConfig = { Map args -> [success: true, app: [id: 99, label: 'X'], settings: bigSettings] }
        stateMap.debugLogs = [
            entries: [],
            config: [logLevel: 'debug', maxEntries: 1000]
        ]
        settingsMap.mcpLogLevel = 'debug'
        // Gateway-routed call: name=hub_read_apps_code, args carries tool+args.
        mcpDriver.pushBody([
            jsonrpc: '2.0', id: 200, method: 'tools/call',
            params: [name: 'hub_read_apps_code', arguments: [tool: 'hub_get_app_config', args: [appId: '99', includeSettings: true]]]
        ])

        when:
        script.handleMcpRequest()

        then: 'envelope reports the SUB-tool, not the gateway, so the LLMs retry hint matches the call it issued'
        def response = mcpDriver.parseResponseJson()
        response.error == null
        def inner = new groovy.json.JsonSlurper().parseText(response.result.content[0].text)
        inner.response_too_large == true
        inner.tool == 'hub_get_app_config'
        inner.suggestion.contains('includeSettings')

        and: 'debug-log details surface the gateway/sub-tool split for an operator running hub_get_debug_logs'
        def warn = (stateMap.debugLogs?.entries ?: []).find { it.message?.contains('response too large') }
        warn.details.tool == 'hub_get_app_config'
        warn.details.gateway == 'hub_read_apps_code'
    }

    def "guide:true through the full tools/call gateway dispatch returns the reference, not a missing-param error (live-path regression)"() {
        // Regression for a bug found by exercising guide:true on the live hub: the gateway's
        // required-param pre-validation rejected guide:true with "Missing required parameters:
        // appId, confirm" BEFORE the handler's gate-bypassing short-circuit could run. The unit
        // test (calling _applyNativeAppEdit directly) skipped this layer -- so guard the FULL
        // tools/call -> handleMcpRequest -> handleToolsCall -> handleGateway path that real
        // MCP clients take. addTrigger/addAction {discover:true} ride the same exemption.
        given: 'gateway mode + builtin app on so the rule machine gateway dispatches'
        settingsMap.useGateways = true
        settingsMap.enableBuiltinApp = true
        mcpDriver.pushBody([
            jsonrpc: '2.0', id: 210, method: 'tools/call',
            params: [name: 'hub_manage_rule_machine', arguments: [tool: 'hub_set_rule', args: [guide: true]]]
        ])

        when:
        script.handleMcpRequest()
        def response = mcpDriver.parseResponseJson()

        then: 'success envelope (no JSON-RPC error) carrying the reference -- NOT the missing-param hint'
        response.error == null
        def text = response.result.content[0].text as String
        !text.contains('Missing required parameter')
        def inner = new groovy.json.JsonSlurper().parseText(text)
        inner.isError != true
        inner.success == true
        inner.section == 'set_rule_reference'
        (inner.content as String).contains('addTrigger')
        (inner.content as String).contains('walkStep')
    }

    def "non-gateway caller passing a stray `tool` arg does NOT route the suggestion to the wrong tool"() {
        // Defends against the would-be bug where a direct (non-gateway) caller with a
        // stray args.tool='hub_export_native_app' on hub_list_devices would get an hub_export_native_app
        // suggestion ("use saveAs=..."), nonsensical for hub_list_devices.
        given:
        // Force hub_list_devices to blow the cap by stubbing a giant selected-device list.
        def padding = 'x' * 80
        def bigDevices = (0..<2000).collect { i ->
            def d = new TestDevice(id: i, name: "D${i}", label: "Device-${i}-${padding}")
            d.metaClass.getLastActivity = { -> null }
            d
        }
        settingsMap.selectedDevices = bigDevices
        mcpDriver.pushBody([
            jsonrpc: '2.0', id: 201, method: 'tools/call',
            params: [name: 'hub_list_devices', arguments: [tool: 'hub_export_native_app']]
        ])

        when:
        script.handleMcpRequest()

        then:
        def inner = new groovy.json.JsonSlurper().parseText(mcpDriver.parseResponseJson().result.content[0].text)
        inner.response_too_large == true
        inner.tool == 'hub_list_devices'                       // NOT hub_export_native_app
        inner.suggestion.contains('filter')                // hub_list_devices guidance
        !inner.suggestion.contains('saveAs')               // not hub_export_native_app guidance
    }

    def "tools/call passes small results through unchanged (size guard does not perturb normal traffic)"() {
        given: 'a tiny rooms list well under the cap'
        script.metaClass.getRooms = { -> [[id: 1L, name: 'Den']] }
        mcpDriver.pushBody([
            jsonrpc: '2.0', id: 101, method: 'tools/call',
            params: [name: 'hub_list_rooms', arguments: [:]]
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
        mcpDriver.pushBody([jsonrpc: '2.0', id: 102, method: 'tools/call', params: [name: 'hub_list_rooms', arguments: [:]]])

        when:
        script.handleMcpRequest()

        then:
        def response = mcpDriver.parseResponseJson()
        response.error == null
        response.result.isError == true
        def inner = new groovy.json.JsonSlurper().parseText(response.result.content[0].text)
        inner.isError == true
        inner.error.contains('hub_list_rooms')
        inner.tool == 'hub_list_rooms'
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
        'hub_list_devices'        | 'filter'
        'hub_list_apps'           | 'cursor'
        'hub_get_app_config'      | 'includeSettings'
        'hub_get_device_health' | 'includeHealthy'
        'hub_get_memory_history'  | 'limit'
        'hub_get_logs'        | 'pattern'
        'hub_export_native_app'   | 'saveAs'
        'hub_get_info'        | 'subsection'
        'hub_get_source'      | 'File Manager'
        'unknown_tool_xyz'    | 'narrow your query'
    }

    def "tools/call hub_list_rooms flows through render with an MCP content envelope"() {
        given: 'a stubbed getRooms returning a deterministic list'
        script.metaClass.getRooms = { ->
            [[id: 1L, name: 'Living Room'], [id: 2L, name: 'Kitchen']]
        }
        // hub_list_rooms lives behind the hub_manage_rooms gateway in tools/list, but executeTool
        // still dispatches it directly by tool name at hubitat-mcp-server.groovy:1853 — the
        // gateway is a tools/list folding convention, not a dispatch barrier.
        mcpDriver.pushBody([
            jsonrpc: '2.0', id: 3, method: 'tools/call',
            params: [name: 'hub_list_rooms', arguments: [:]]
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
            params: [name: 'hub_list_rooms', arguments: [:]]
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

    def "flat-mode tools/list catalog stays under the hub's 124,000-byte cap (outputSchema stripped)"() {
        // PR1C strips outputSchema from the flat catalog precisely to keep this
        // under the hub's 124,000-byte tools/list cap (over it, handleMcpRequest
        // returns -32603 and useGateways=false clients see ZERO tools). Pin the
        // budget so a future verbose description / un-stripped field fails loudly
        // here instead of silently on a user's hub.
        given:
        settingsMap.useGateways = false
        settingsMap.enableBuiltinApp = true
        settingsMap.enableCustomRuleEngine = true

        when:
        def flat = script.getToolDefinitions()
        int flatBytes = groovy.json.JsonOutput.toJson([tools: flat]).getBytes("UTF-8").length
        int fullBytes = groovy.json.JsonOutput.toJson([tools: script.getAllToolDefinitions()]).getBytes("UTF-8").length

        then: 'the flat catalog fits under the cap'
        assert flatBytes < 124000 : "flat tools/list catalog is ${flatBytes} bytes, over the 124,000 cap"

        and: 'the strip + [[FLAT_TRIM]] is load-bearing: the un-stripped defs are materially larger'
        fullBytes > flatBytes
    }

    def "outputSchema survives JSON serialization in gateway mode; gateway entries carry none"() {
        // Mirrors the annotations-survive-serialization guard above: outputSchema is
        // a nested Map of Maps, the kind of payload a JsonOutput/transform regression
        // would silently drop. Assert it lands on the wire for a base tool, and that
        // gateway entries (which proxy many tools) carry no single outputSchema.
        given:
        settingsMap.useGateways = true
        settingsMap.enableBuiltinApp = true
        settingsMap.enableCustomRuleEngine = true
        mcpDriver.pushBody([jsonrpc: '2.0', id: 71, method: 'tools/list', params: [:]])

        when:
        script.handleMcpRequest()
        def response = mcpDriver.parseResponseJson()

        then: 'a flat/base read tool carries its outputSchema through serialization'
        def info = response.result.tools.find { it.name == 'hub_get_info' }
        info.outputSchema instanceof Map
        info.outputSchema.type == 'object'
        info.outputSchema.properties instanceof Map

        and: 'a gateway entry proxies multiple tools, so it has no single outputSchema'
        def gw = response.result.tools.find { it.name == 'hub_manage_rooms' }
        gw != null
        gw.containsKey('outputSchema') == false
    }

    def "every gateway catalog disclosure stays under the 120,000-byte tools/call cap"() {
        // The gateway catalog (handleGateway with no toolName) is now the canonical
        // home for the heavy outputSchemas, and it is bounded by the 120,000-byte
        // tools/call cap. Over it, the caller gets a response_too_large envelope
        // instead of the catalog and can no longer discover any tool in that gateway.
        // The largest today (hub_manage_native_rules_and_apps) is ~76KB; pin all 19.
        given:
        settingsMap.enableBuiltinApp = true
        settingsMap.enableCustomRuleEngine = true

        when:
        def oversize = script.getGatewayConfig().keySet().findAll { gw ->
            groovy.json.JsonOutput.toJson(script.handleGateway(gw, null, null)).getBytes("UTF-8").length >= 120000
        }

        then:
        assert oversize.isEmpty() : "gateway catalog(s) over the 120,000-byte tools/call cap: ${oversize}"
    }
}
