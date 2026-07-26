package server

import spock.lang.Shared
import support.TestDevice
import support.TestHub
import support.TestLocation
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
 *   - Notification short-circuit (id-less request → 202 Accepted, empty body)
 *   - Batch per-item error isolation (a failing item must not poison a
 *     later success)
 *   - tools/call error-envelope at the HTTP shell (isError: true wrapped
 *     in a 200 render, not a naked hub 500)
 *   - /health and GET /mcp handlers (small but distinct render envelopes)
 *   - the 2026-07-28 era split: {@code request.headers} in, the modern
 *     MCP-Protocol-Version / Mcp-Method / Mcp-Name validation (400 + -32020 /
 *     -32022, 404 + -32601), and the headerless legacy path staying byte-for-byte
 *     as it was
 *   - Origin validation (403) on both eras
 *
 * Part of #77 (tier-2). The tier-3 fake-hub work remains tracked there.
 */
class HandleMcpRequestDispatchSpec extends ToolSpecBase {

    /**
     * The Origin check compares an inbound Origin against the identities the SERVER
     * knows for itself, one of which is {@code location.hub.localIP}. That read goes
     * through {@code appExecutor.getLocation()} (a class-2 seam per the docs/testing.md
     * cheat sheet — metaClass hooks are not consulted), so it is stubbed here and the
     * hub is re-seeded per test.
     */
    @Shared private TestLocation sharedLocation = new TestLocation()

    def setupSpec() {
        appExecutor.getLocation() >> sharedLocation
    }

    def setup() {
        sharedLocation.hub = new TestHub(localIP: '192.168.1.133')
    }

    def "initialize returns protocolVersion and serverInfo via render(Map)"() {
        given: 'gateway mode explicit -- the instructions prose is mode-branched'
        settingsMap.useGateways = true
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

        and: 'MCP initialize response shape — no requested version, so the newest supported one'
        response.result.protocolVersion == '2025-11-25'
        response.result.capabilities.tools == [:]
        response.result.serverInfo.name == 'hubitat-mcp-rule-server'
        // Semver pattern — a regression that returned '' or 'unknown' would
        // satisfy a naked truthy check, so pin the actual shape currentVersion()
        // produces.
        response.result.serverInfo.version ==~ /\d+\.\d+\.\d+.*/

        and: 'instructions field is present, non-empty, and survives serialization through the dispatch envelope'
        // Reading it off parseResponseJson() proves it round-trips
        // jsonRpcResult -> JsonOutput.toJson -> render -> parse (PR2 owns the
        // field; PR3 refines the prose, so pin stable keywords, not the text).
        response.result.instructions instanceof String
        !response.result.instructions.isEmpty()
        response.result.instructions.toLowerCase().contains('gateway')
        response.result.instructions.toLowerCase().contains('pagination')
    }

    def "initialize instructions in flat mode do not tell the client to call gateways"() {
        // useGateways=false blocks gateway-name calls ("useGateways is OFF"), so the
        // instructions must not steer a flat client into that error -- the flat prose
        // keeps the pagination guidance and drops the call-a-gateway recipe.
        given:
        settingsMap.useGateways = false
        mcpDriver.pushBody([jsonrpc: '2.0', id: 1, method: 'initialize', params: [:]])

        when:
        script.handleMcpRequest()

        then:
        def response = mcpDriver.parseResponseJson()
        response.result.instructions instanceof String
        !response.result.instructions.toLowerCase().contains('call a gateway')
        response.result.instructions.toLowerCase().contains('flat catalog')
        response.result.instructions.toLowerCase().contains('pagination')
    }

    def "hubResponseCapBytes() is the single 131072-byte source and the two derived guards stay ordered inner < outer"() {
        // Pins the size-cap single-source invariant: the helper is the 128 KiB hub cap,
        // the outer handleMcpRequest guard (=124000) and inner handleToolsCall guard
        // (=120000) derive from it, and inner must stay strictly below outer.
        expect:
        script.hubResponseCapBytes() == 131072
        (script.hubResponseCapBytes() - 7072) == 124000   // outer
        (script.hubResponseCapBytes() - 11072) == 120000  // inner
        (script.hubResponseCapBytes() - 11072) < (script.hubResponseCapBytes() - 7072)
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
        settingsMap.enableRead = true
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

    def "validation-error mcpLog blames the SUB-TOOL, not the gateway, on a gateway-routed call (#319)"() {
        given: 'hub_get_room reaches its handler (room supplied) then throws IAE (no rooms), via its gateway'
        settingsMap.enableRead = true
        settingsMap.useGateways = true
        stateMap.debugLogs = [entries: [], config: [logLevel: 'debug', maxEntries: 1000]]
        settingsMap.mcpLogLevel = 'debug'
        // getRooms is a dynamic SDK method (metaClass-stubbable); [] makes toolGetRoom
        // throw its own "No rooms configured" IAE -> the validation branch.
        script.metaClass.getRooms = { -> [] }
        mcpDriver.pushBody([
            jsonrpc: '2.0', id: 210, method: 'tools/call',
            params: [name: 'hub_read_rooms', arguments: [tool: 'hub_get_room', args: [room: 'X']]]
        ])

        when:
        script.handleMcpRequest()

        then: 'the -32602 fires and the debug-log entry names the sub-tool with the gateway as context'
        def response = mcpDriver.parseResponseJson()
        response.error.code == -32602
        def warn = (stateMap.debugLogs?.entries ?: []).find { it.message?.startsWith('Validation error in') }
        warn != null
        warn.message.contains('hub_get_room')
        warn.details.tool == 'hub_get_room'
        warn.details.gateway == 'hub_read_rooms'
    }

    def "execution-error mcpLog blames the SUB-TOOL, not the gateway, on a gateway-routed call (#319)"() {
        given: 'hub_get_room reaches its handler then a non-IAE bubbles up (generic execution error), via its gateway'
        settingsMap.enableRead = true
        settingsMap.useGateways = true
        stateMap.debugLogs = [entries: [], config: [logLevel: 'debug', maxEntries: 1000]]
        settingsMap.mcpLogLevel = 'debug'
        script.metaClass.getRooms = { -> throw new RuntimeException('boom-exec') }
        mcpDriver.pushBody([
            jsonrpc: '2.0', id: 211, method: 'tools/call',
            params: [name: 'hub_read_rooms', arguments: [tool: 'hub_get_room', args: [room: 'X']]]
        ])

        when:
        script.handleMcpRequest()

        then: 'the isError envelope fires and the error-level entry names the sub-tool with the gateway as context'
        def response = mcpDriver.parseResponseJson()
        response.result.isError == true
        def err = (stateMap.debugLogs?.entries ?: []).find { it.message?.startsWith('Tool execution error in') }
        err != null
        err.message.contains('hub_get_room')
        err.details.tool == 'hub_get_room'
        err.details.gateway == 'hub_read_rooms'
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

    def "single tools/call renders correctly via the preserialized fast path with no sentinel leak"() {
        given:
        script.metaClass.getRooms = { -> [[id: 1L, name: 'Living Room'], [id: 2L, name: 'Kitchen']] }
        mcpDriver.pushBody([jsonrpc: '2.0', id: 7, method: 'tools/call', params: [name: 'hub_list_rooms', arguments: [:]]])

        when:
        script.handleMcpRequest()

        then: 'a well-formed JSON-RPC success envelope wrapping the real rooms payload (toolListRooms sorts by name)'
        mcpDriver.lastRenderArgs.contentType == 'application/json'
        def response = mcpDriver.parseResponseJson()
        response.jsonrpc == '2.0'
        response.id == 7
        response.error == null
        mcpDriver.parseInner(response).rooms*.name == ['Kitchen', 'Living Room']

        and: 'the rendered body is well-formed JSON-RPC and the internal sentinel never leaks onto the wire'
        def raw = mcpDriver.lastRenderArgs.data as String
        !raw.contains('__preserialized')
        new groovy.json.JsonSlurper().parseText(raw).result.content[0].type == 'text'
    }

    def "batch of two tools/call renders a valid array with no preserialized sentinel leaking"() {
        given:
        script.metaClass.getRooms = { -> [[id: 1L, name: 'Den']] }
        mcpDriver.pushBody([
            [jsonrpc: '2.0', id: 81, method: 'tools/call', params: [name: 'hub_list_rooms', arguments: [:]]],
            [jsonrpc: '2.0', id: 82, method: 'tools/call', params: [name: 'hub_list_rooms', arguments: [:]]]
        ])

        when:
        script.handleMcpRequest()

        then: 'no sentinel key in the raw rendered body'
        def raw = mcpDriver.lastRenderArgs.data as String
        !raw.contains('__preserialized')

        and: 'a 2-element array of well-formed JSON-RPC success envelopes, each carrying the rooms payload'
        def response = mcpDriver.parseResponseJson()
        response instanceof List
        response.size() == 2
        response.every { it.jsonrpc == '2.0' && it.error == null }
        response.each { el ->
            assert el.id in [81, 82]
            assert !el.containsKey('__preserialized')
            assert new groovy.json.JsonSlurper().parseText(el.result.content[0].text).rooms*.name == ['Den']
        }
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
        // still dispatches it directly by tool name in hubitat-mcp-server.groovy — the
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

    def "notification (no id) returns 202 Accepted no-content"() {
        given: 'a notification-shaped request — id field is absent per JSON-RPC 2.0'
        mcpDriver.pushBody([jsonrpc: '2.0', method: 'initialized', params: [:]])

        when:
        script.handleMcpRequest()

        then: 'render was called with 202 Accepted and empty body — no JSON envelope (MCP Streamable HTTP)'
        mcpDriver.lastRenderArgs.status == 202
        mcpDriver.lastRenderArgs.data == ''
    }

    @spock.lang.Unroll
    def "initialize echoes supported requested protocolVersion=#requested as #expected (echo-allowlist)"() {
        when:
        def result = script.handleInitialize([id: 1, params: [protocolVersion: requested]])

        then:
        result.result.protocolVersion == expected

        where:
        requested      || expected
        '2025-11-25'   || '2025-11-25'
        '2025-06-18'   || '2025-06-18'
        '2025-03-26'   || '2025-03-26'
        '2024-11-05'   || '2024-11-05'
        // Unknown / omitted both negotiate DOWN to the newest LEGACY version this
        // server speaks rather than erroring -- pre-2026 clients depend on that.
        'garbage-9999' || '2025-11-25'
        null           || '2025-11-25'
        // 2026-07-28 is supported on the transport but is NOT negotiable through
        // initialize: that revision deleted the handshake, so a client reaching this
        // method is legacy-era by construction and must not be handed a modern
        // version to cache. It negotiates down like any non-legacy value.
        '2026-07-28'   || '2025-11-25'
    }

    def "supportedProtocolVersions leads with 2026-07-28; initializeProtocolVersions is the legacy subset and defaultProtocolVersion is its head"() {
        // supportedProtocolVersions() is the single source for server/discover's
        // supportedVersions, the modern MCP-Protocol-Version header allowlist, and the
        // `supported` list a -32022 rejection hands back. initializeProtocolVersions()
        // is the derived legacy subset the initialize echo-allowlist uses -- derived,
        // so a future revision cannot drift the two apart.
        expect:
        script.modernProtocolVersion() == '2026-07-28'
        script.supportedProtocolVersions() == ['2026-07-28', '2025-11-25', '2025-06-18', '2025-03-26', '2024-11-05']
        script.initializeProtocolVersions() == ['2025-11-25', '2025-06-18', '2025-03-26', '2024-11-05']

        and: 'the modern revision heads the supported list and is the only non-legacy entry'
        script.supportedProtocolVersions()[0] == script.modernProtocolVersion()

        and: 'the initialize allowlist is exactly the supported list minus the modern revision'
        script.initializeProtocolVersions() == script.supportedProtocolVersions() - [script.modernProtocolVersion()]
        !script.initializeProtocolVersions().contains(script.modernProtocolVersion())

        and: 'the initialize fallback is the newest LEGACY revision, not the newest supported one'
        script.defaultProtocolVersion() == script.initializeProtocolVersions()[0]
        script.defaultProtocolVersion() == '2025-11-25'
        script.defaultProtocolVersion() != script.supportedProtocolVersions()[0]
    }

    def "a MODERN-era result carries resultType 'complete' plus the io.modelcontextprotocol/serverInfo _meta key"() {
        // SEP-2575: servers on the modern revision MUST send resultType and SHOULD
        // identify themselves in each result's _meta. jsonRpcResult stamps both
        // centrally, so this asserts through the render envelope (proving they survive
        // serialization); the legacy counterpart is the very next feature.
        given:
        mcpDriver.pushHeaders(['MCP-Protocol-Version': '2026-07-28', 'Mcp-Method': 'initialize'])
        mcpDriver.pushBody([jsonrpc: '2.0', id: 1, method: 'initialize', params: [:]])

        when:
        script.handleMcpRequest()

        then:
        def response = mcpDriver.parseResponseJson()
        response.result.resultType == 'complete'
        response.result._meta['io.modelcontextprotocol/serverInfo'].name == 'hubitat-mcp-rule-server'
        // Semver shape, not a literal — avoids churn on every release bump.
        response.result._meta['io.modelcontextprotocol/serverInfo'].version ==~ /\d+\.\d+\.\d+.*/
    }

    @spock.lang.Unroll
    def "a LEGACY-era result carries the serverInfo _meta key but NOT resultType (#scenario)"() {
        // resultType is a 2026-07-28 field, and legacy clients parse an empty result with
        // a STRICT schema -- the MCP TypeScript SDK's EmptyResultSchema is
        // ResultSchema.strict(), which REJECTS unknown keys. Stamping resultType on a
        // legacy reply therefore turns a `ping` keepalive into a client-side protocol
        // error. `_meta` is a modeled key in every revision's Result schema, so it
        // survives that same strict parse and stays unconditional.
        given:
        if (headers != null) mcpDriver.pushHeaders(headers)
        mcpDriver.pushBody([jsonrpc: '2.0', id: 600, method: method, params: [:]])

        when:
        script.handleMcpRequest()

        then:
        def response = mcpDriver.parseResponseJson()
        response.error == null
        !response.result.containsKey('resultType')
        response.result._meta['io.modelcontextprotocol/serverInfo'].name == 'hubitat-mcp-rule-server'

        where:
        scenario                        | headers                                   | method
        'headerless ping'               | null                                      | 'ping'
        'headerless initialize'         | null                                      | 'initialize'
        'headerless tools/list'         | null                                      | 'tools/list'
        'legacy-versioned ping'         | ['MCP-Protocol-Version': '2025-06-18']    | 'ping'
        'legacy-versioned tools/list'   | ['MCP-Protocol-Version': '2025-11-25']    | 'tools/list'
    }

    def "a legacy ping result is exactly the _meta envelope -- nothing a strict EmptyResult parse would reject"() {
        // The concrete shape a legacy client's EmptyResultSchema.strict() sees. Pinned as
        // an exact keySet so any future unconditional decoration fails here rather than on
        // a user's keepalive.
        given:
        mcpDriver.pushBody([jsonrpc: '2.0', id: 601, method: 'ping', params: [:]])

        when:
        script.handleMcpRequest()

        then:
        mcpDriver.parseResponseJson().result.keySet() == ['_meta'] as Set
    }

    def "jsonRpcResult does not clobber a resultType or _meta the caller already set"() {
        // The MRTR pattern returns resultType 'input_required', and a future handler may
        // set its own _meta keys, so the central decoration must defer to the caller.
        // Staged as modern so the resultType stamp is live and genuinely has to yield.
        given:
        mcpDriver.pushHeaders(['MCP-Protocol-Version': '2026-07-28', 'Mcp-Method': 'tools/call'])

        when:
        def out = script.jsonRpcResult(5, [resultType: 'input_required', _meta: [somekey: 'kept']])

        then: 'the caller-set resultType survives'
        out.result.resultType == 'input_required'

        and: 'the caller _meta keys survive AND serverInfo is merged in beside them'
        out.result._meta.somekey == 'kept'
        out.result._meta['io.modelcontextprotocol/serverInfo'].name == 'hubitat-mcp-rule-server'
    }

    def "jsonRpcResult copies the result map instead of mutating the caller's"() {
        // Decoration must not write into a map the caller may reuse or hold elsewhere.
        // Modern-staged so BOTH stamps run against the copy, not just the _meta one.
        given:
        mcpDriver.pushHeaders(['MCP-Protocol-Version': '2026-07-28', 'Mcp-Method': 'tools/list'])
        def original = [tools: []]

        when:
        def out = script.jsonRpcResult(6, original)

        then:
        original == [tools: []]

        and: 'the copy really was decorated -- otherwise the assertion above proves nothing'
        out.result.resultType == 'complete'
        out.result._meta['io.modelcontextprotocol/serverInfo'].name == 'hubitat-mcp-rule-server'
    }

    def "a modern tools/call carries resultType + serverInfo _meta through the preserialized fast path"() {
        // tools/call serializes its envelope inside handleToolsCall and hands
        // handleMcpRequest a preserialized string, so the central decoration has to
        // already be baked into that string -- a decoration applied later would miss
        // the single-message fast path entirely (the hottest path on the server).
        given:
        script.metaClass.getRooms = { -> [[id: 1L, name: 'Den']] }
        mcpDriver.pushHeaders([
            'MCP-Protocol-Version': '2026-07-28',
            'Mcp-Method': 'tools/call',
            'Mcp-Name': 'hub_list_rooms',
        ])
        mcpDriver.pushBody([jsonrpc: '2.0', id: 8, method: 'tools/call', params: [name: 'hub_list_rooms', arguments: [:]]])

        when:
        script.handleMcpRequest()

        then: 'read off the RAW rendered body — that is the preserialized string itself'
        def raw = mcpDriver.lastRenderArgs.data as String
        !raw.contains('__preserialized')
        def response = new groovy.json.JsonSlurper().parseText(raw)
        response.result.resultType == 'complete'
        response.result._meta['io.modelcontextprotocol/serverInfo'].name == 'hubitat-mcp-rule-server'

        and: 'the tool payload is untouched alongside the new envelope keys'
        new groovy.json.JsonSlurper().parseText(response.result.content[0].text).rooms*.name == ['Den']
    }

    def "tools/list result carries the CacheableResult ttlMs + cacheScope hints in BOTH eras"() {
        // SEP-2549 requires both on tools/list. cacheScope is 'private' because the
        // endpoint is per-install token-authed and the catalog is shaped by that
        // install's settings, so a shared proxy must not serve it across contexts.
        // Unlike resultType these stay unconditional: the legacy ListToolsResult schema
        // is passthrough, so extra keys are safe there.
        given:
        mcpDriver.pushBody([jsonrpc: '2.0', id: 9, method: 'tools/list', params: [:]])

        when:
        script.handleMcpRequest()

        then: 'headerless (legacy) still gets the hints'
        def legacy = mcpDriver.parseResponseJson()
        legacy.result.ttlMs == 300000
        legacy.result.cacheScope == 'private'
        legacy.result._meta['io.modelcontextprotocol/serverInfo'].name == 'hubitat-mcp-rule-server'
        !legacy.result.containsKey('resultType')

        when: 'the same call on the modern era'
        mcpDriver.pushHeaders(['MCP-Protocol-Version': '2026-07-28', 'Mcp-Method': 'tools/list'])
        mcpDriver.pushBody([jsonrpc: '2.0', id: 10, method: 'tools/list', params: [:]])
        script.handleMcpRequest()

        then: 'same hints, plus resultType'
        def modern = mcpDriver.parseResponseJson()
        modern.result.ttlMs == 300000
        modern.result.cacheScope == 'private'
        modern.result.resultType == 'complete'

        and: 'the ttl comes from the single cacheHintTtlMs() source'
        script.cacheHintTtlMs() == 300000
    }

    def "server/discover returns supportedVersions, capabilities, serverInfo and the required cache hints"() {
        // SEP-2575: servers MUST implement server/discover so a stateless client can
        // pick a mutually supported version before sending anything else.
        // DiscoverResult is a CacheableResult — ttlMs + cacheScope are REQUIRED
        // fields of it, not optional extras.
        given:
        settingsMap.useGateways = true  // instructions prose is mode-branched
        mcpDriver.pushBody([jsonrpc: '2.0', id: 12, method: 'server/discover', params: [:]])

        when:
        script.handleMcpRequest()

        then: 'a well-formed JSON-RPC success envelope'
        def response = mcpDriver.parseResponseJson()
        response.jsonrpc == '2.0'
        response.id == 12
        response.error == null

        and: 'the DiscoverResult shape -- the modern revision leads the advertised list'
        // The FULL list is advertised, legacy entries included: a client that picks a
        // legacy version off it and sends that as its MCP-Protocol-Version header is
        // served correctly and statelessly, because nothing here requires initialize.
        response.result.supportedVersions == ['2026-07-28', '2025-11-25', '2025-06-18', '2025-03-26', '2024-11-05']
        response.result.capabilities.tools == [:]
        response.result.serverInfo.name == 'hubitat-mcp-rule-server'
        response.result.serverInfo.version ==~ /\d+\.\d+\.\d+.*/
        response.result.ttlMs == 300000
        response.result.cacheScope == 'private'
        response.result.resultType == 'complete'

        and: 'instructions ride discover too — a stateless client never calls initialize'
        response.result.instructions instanceof String
        response.result.instructions.toLowerCase().contains('gateway')
    }


    @spock.lang.Unroll
    def "no non-200 status leaks onto a HEADERLESS #scenario"() {
        // Guards the enumeration in handleMcpRequest's transport-contract comment for
        // the LEGACY era: with no MCP-Protocol-Version header the only explicit
        // statuses are 405 (GET) and 202 (all-notifications POST), and every
        // application-level JSON-RPC error rides the bare default-200 render. The
        // 400/404 mappings are scoped to header-bearing (modern) requests -- pinned in
        // the modern-era features below.
        given:
        mcpDriver.pushBody(body)

        when:
        script.handleMcpRequest()

        then:
        mcpDriver.lastRenderArgs.status == null

        where:
        scenario                | body
        'method-not-found'      | [jsonrpc: '2.0', id: 40, method: 'does/not/exist']
        'missing method'        | [jsonrpc: '2.0', id: 41]
        'wrong jsonrpc version' | [jsonrpc: '1.0', id: 42, method: 'ping']
        'a plain success'       | [jsonrpc: '2.0', id: 43, method: 'ping', params: [:]]
    }

    @spock.lang.Unroll
    def "a HEADERLESS request whose _meta protocolVersion is #scenario dispatches normally"() {
        // On the headerless (legacy) path EVERY per-request _meta version is tolerated,
        // including unknown ones. A headerless POST never claimed the modern transport,
        // so answering it with -32022 would tell a dual-era client "modern server -- do
        // not fall back to initialize" and wedge it out of the handshake it still
        // needs. The -32022 rejection lives on the header path only (below).
        given:
        mcpDriver.pushBody([jsonrpc: '2.0', id: 14, method: 'tools/list', params: requestParams])

        when:
        script.handleMcpRequest()

        then:
        def response = mcpDriver.parseResponseJson()
        response.error == null
        response.result.tools instanceof List

        and: 'and it stays on the JSON-RPC-native 200 -- no modern status mapping'
        mcpDriver.lastRenderArgs.status == null

        where:
        scenario                     | requestParams
        'supported'                  | ['_meta': ['io.modelcontextprotocol/protocolVersion': '2025-11-25']]
        'an older supported one'     | ['_meta': ['io.modelcontextprotocol/protocolVersion': '2024-11-05']]
        'unknown (tolerated)'        | ['_meta': ['io.modelcontextprotocol/protocolVersion': '2099-01-01']]
        'the modern one (tolerated)' | ['_meta': ['io.modelcontextprotocol/protocolVersion': '2026-07-28']]
        'absent'                     | [:]
        // A malformed _meta must fall through to the legacy path, not throw: the
        // check reads it defensively because it runs outside the dispatch try/catch.
        'a non-Map _meta'            | ['_meta': 'not-an-object']
    }

    // ---- Era switch: the header's VALUE, never its presence ----
    //
    // MCP-Protocol-Version has been REQUIRED on every POST since 2025-06-18, so a
    // client that negotiated 2025-06-18 or 2025-11-25 through initialize sends it on
    // every request -- with NO Mcp-Method / Mcp-Name, because those headers do not
    // exist before 2026-07-28. Reading presence as "modern" would 400 every one of
    // those requests, i.e. every current production client. These features are the
    // regression pin for that; the modern-era block follows.

    @spock.lang.Unroll
    def "a request whose MCP-Protocol-Version names the LEGACY revision #version is served as legacy with NO mirrored-header requirement"() {
        // THE critical compatibility case: header present, naming a supported legacy
        // revision, no Mcp-Method at all. Must be an ordinary 200, never a -32020.
        given:
        mcpDriver.pushHeaders(['MCP-Protocol-Version': version])
        mcpDriver.pushBody([jsonrpc: '2.0', id: 500, method: 'tools/list', params: [:]])

        when:
        script.handleMcpRequest()

        then:
        mcpDriver.lastRenderArgs.status == null
        def response = mcpDriver.parseResponseJson()
        response.id == 500
        response.error == null
        response.result.tools instanceof List

        and: 'and it is not decorated as a modern result either'
        !response.result.containsKey('resultType')

        where:
        version << ['2025-11-25', '2025-06-18', '2025-03-26', '2024-11-05']
    }

    def "a legacy-versioned tools/call needs no Mcp-Name (that header does not exist before the modern revision)"() {
        given:
        script.metaClass.getRooms = { -> [[id: 1L, name: 'Den']] }
        mcpDriver.pushHeaders(['MCP-Protocol-Version': '2025-06-18'])
        mcpDriver.pushBody([jsonrpc: '2.0', id: 501, method: 'tools/call',
                            params: [name: 'hub_list_rooms', arguments: [:]]])

        when:
        script.handleMcpRequest()

        then:
        mcpDriver.lastRenderArgs.status == null
        def response = mcpDriver.parseResponseJson()
        response.error == null
        mcpDriver.parseInner(response).rooms*.name == ['Den']
    }

    def "a legacy-versioned unknown method stays on 200 + -32601 (the 404 mapping is modern-only)"() {
        given:
        mcpDriver.pushHeaders(['MCP-Protocol-Version': '2025-11-25'])
        mcpDriver.pushBody([jsonrpc: '2.0', id: 502, method: 'does/not/exist'])

        when:
        script.handleMcpRequest()

        then:
        mcpDriver.lastRenderArgs.status == null
        mcpDriver.parseResponseJson().error.code == -32601
    }

    def "a legacy-versioned BATCH still returns a 200 array (the single-message rule is modern-only)"() {
        given:
        mcpDriver.pushHeaders(['MCP-Protocol-Version': '2025-11-25'])
        mcpDriver.pushBody([
            [jsonrpc: '2.0', id: 503, method: 'ping', params: [:]],
            [jsonrpc: '2.0', id: 504, method: 'ping', params: [:]],
        ])

        when:
        script.handleMcpRequest()

        then:
        mcpDriver.lastRenderArgs.status == null
        def response = mcpDriver.parseResponseJson()
        response instanceof List
        response.size() == 2
        response.every { it.error == null }
    }

    def "a legacy-versioned request whose _meta version disagrees with the header is NOT cross-checked"() {
        // The per-request _meta protocol version is a 2026-07-28 construct. On a legacy
        // revision there is nothing to reconcile, so the disagreement is ignored rather
        // than answered with -32020.
        given:
        mcpDriver.pushHeaders(['MCP-Protocol-Version': '2025-06-18'])
        mcpDriver.pushBody([jsonrpc: '2.0', id: 505, method: 'tools/list',
                            params: ['_meta': ['io.modelcontextprotocol/protocolVersion': '2099-01-01']]])

        when:
        script.handleMcpRequest()

        then:
        mcpDriver.lastRenderArgs.status == null
        mcpDriver.parseResponseJson().result.tools instanceof List
    }

    def "an unsupported MCP-Protocol-Version is rejected even with NO Mcp-Method -- the -32022 spans both eras"() {
        // 2025-06-18 already required 400 for an unsupported MCP-Protocol-Version, so
        // this rejection is not gated on the modern era. A legacy client that sends a
        // version this server dropped gets the supported list to retry from.
        given:
        mcpDriver.pushHeaders(['MCP-Protocol-Version': '2019-01-01'])
        mcpDriver.pushBody([jsonrpc: '2.0', id: 506, method: 'tools/list', params: [:]])

        when:
        script.handleMcpRequest()

        then:
        mcpDriver.lastRenderArgs.status == 400
        def response = mcpDriver.parseResponseJson()
        response.id == 506
        response.error.code == -32022
        response.error.data.requested == '2019-01-01'
        response.error.data.supported == script.supportedProtocolVersions()
    }

    // ---- 2026-07-28 modern transport: request-metadata header validation ----
    //
    // Every feature below stages headers through mcpDriver.pushHeaders(), which
    // reproduces the hub's own wire shape (names case-normalized to first-char-upper,
    // values List-wrapped) as confirmed by a live probe over LAN and the cloud relay --
    // so the production case-insensitive lookup and List-unwrap are what is under test.

    def "a modern request with matching Mcp-Method dispatches normally at HTTP 200"() {
        given: 'the full modern header set for a tools/list, matching the body'
        mcpDriver.pushHeaders(['MCP-Protocol-Version': '2026-07-28', 'Mcp-Method': 'tools/list'])
        mcpDriver.pushBody([jsonrpc: '2.0', id: 300, method: 'tools/list', params: [:]])

        when:
        script.handleMcpRequest()

        then: 'no status override -- validation passed, so this is an ordinary success'
        mcpDriver.lastRenderArgs.status == null
        def response = mcpDriver.parseResponseJson()
        response.id == 300
        response.error == null
        response.result.tools instanceof List
    }

    def "the header lookup is case-insensitive independent of the hub's own normalization"() {
        // RFC 9110: field names are case-insensitive. The hub happens to normalize to
        // first-char-upper, and pushHeaders reproduces that — so staging the map
        // DIRECTLY with a third casing is the only way to prove the lookup isn't
        // quietly coupled to one particular spelling. Values stay List-wrapped, as
        // the hub sends them.
        given:
        mcpDriver.headers['MCP-PROTOCOL-VERSION'] = ['2026-07-28']
        mcpDriver.headers['mcp-method'] = ['ping']
        mcpDriver.pushBody([jsonrpc: '2.0', id: 330, method: 'ping', params: [:]])

        when:
        script.handleMcpRequest()

        then: 'both headers were found, so validation passed rather than 400ing on a "missing" Mcp-Method'
        mcpDriver.lastRenderArgs.status == null
        def response = mcpDriver.parseResponseJson()
        response.id == 330
        response.error == null
        response.result.resultType == 'complete'
    }

    def "a header staged as a bare (non-List) value is still read -- the unwrap must not require a List"() {
        // The hub List-wraps every value, but _requestHeader unwraps defensively rather
        // than assuming: a firmware that handed back a bare String must still work.
        given:
        mcpDriver.headers['Mcp-protocol-version'] = '2026-07-28'
        mcpDriver.headers['Mcp-method'] = 'ping'
        mcpDriver.pushBody([jsonrpc: '2.0', id: 331, method: 'ping', params: [:]])

        when:
        script.handleMcpRequest()

        then:
        mcpDriver.lastRenderArgs.status == null
        mcpDriver.parseResponseJson().error == null
    }

    def "a header staged as an EMPTY List reads as absent, not as an empty-string mismatch"() {
        // An empty List must not unwrap to "" -- that would turn a header the hub could
        // not populate into a bogus value comparison. Absent is the correct reading, so
        // this lands on the headerless legacy path.
        given:
        mcpDriver.headers['Mcp-protocol-version'] = []
        mcpDriver.pushBody([jsonrpc: '2.0', id: 332, method: 'tools/list', params: [:]])

        when:
        script.handleMcpRequest()

        then: 'legacy path -- no 400 for a "missing Mcp-Method" it was never modern enough to need'
        mcpDriver.lastRenderArgs.status == null
        mcpDriver.parseResponseJson().result.tools instanceof List
    }

    def "an unsupported MCP-Protocol-Version header returns 400 with -32022 and the schema-shaped requested/supported data"() {
        // UnsupportedProtocolVersionError: `data.requested` and `data.supported` are
        // both REQUIRED by the draft schema -- the client picks a mutually supported
        // version out of `supported` and retries, so an empty or absent list would
        // leave a dual-era client with nowhere to go.
        given:
        mcpDriver.pushHeaders(['MCP-Protocol-Version': '2099-01-01', 'Mcp-Method': 'tools/list'])
        mcpDriver.pushBody([jsonrpc: '2.0', id: 302, method: 'tools/list', params: [:]])

        when:
        script.handleMcpRequest()

        then: 'the spec pins this rejection to 400 Bad Request'
        mcpDriver.lastRenderArgs.status == 400
        mcpDriver.lastRenderArgs.contentType == 'application/json'

        and: 'a JSON-RPC error envelope echoing the id'
        def response = mcpDriver.parseResponseJson()
        response.jsonrpc == '2.0'
        response.id == 302
        response.error.code == -32022
        response.error.message.contains('2099-01-01')

        and: 'data carries both required fields, and supported is the live single-source list'
        response.error.data.requested == '2099-01-01'
        response.error.data.supported == script.supportedProtocolVersions()
        response.error.data.supported.contains('2026-07-28')
    }

    def "a modern request whose Mcp-Method disagrees with the body method returns 400 with -32020 naming the header"() {
        given:
        mcpDriver.pushHeaders(['MCP-Protocol-Version': '2026-07-28', 'Mcp-Method': 'tools/list'])
        mcpDriver.pushBody([jsonrpc: '2.0', id: 303, method: 'ping', params: [:]])

        when:
        script.handleMcpRequest()

        then:
        mcpDriver.lastRenderArgs.status == 400
        def response = mcpDriver.parseResponseJson()
        response.id == 303
        response.error.code == -32020
        response.error.message.contains('Mcp-Method')
        response.error.message.contains('tools/list')
        response.error.message.contains('ping')
    }

    def "a modern request with NO Mcp-Method header returns 400 with -32020 (a missing required header is a mismatch)"() {
        given: 'the version header marks this modern, but the required method mirror is absent'
        mcpDriver.pushHeaders(['MCP-Protocol-Version': '2026-07-28'])
        mcpDriver.pushBody([jsonrpc: '2.0', id: 304, method: 'tools/list', params: [:]])

        when:
        script.handleMcpRequest()

        then:
        mcpDriver.lastRenderArgs.status == 400
        def response = mcpDriver.parseResponseJson()
        response.error.code == -32020
        response.error.message.contains('Mcp-Method')
        response.error.message.toLowerCase().contains('required')
    }

    def "a modern tools/call with matching Mcp-Name dispatches to the tool"() {
        given:
        script.metaClass.getRooms = { -> [[id: 1L, name: 'Den']] }
        mcpDriver.pushHeaders([
            'MCP-Protocol-Version': '2026-07-28',
            'Mcp-Method': 'tools/call',
            'Mcp-Name': 'hub_list_rooms',
        ])
        mcpDriver.pushBody([jsonrpc: '2.0', id: 305, method: 'tools/call',
                            params: [name: 'hub_list_rooms', arguments: [:]]])

        when:
        script.handleMcpRequest()

        then:
        mcpDriver.lastRenderArgs.status == null
        def response = mcpDriver.parseResponseJson()
        response.error == null
        mcpDriver.parseInner(response).rooms*.name == ['Den']
    }

    def "a modern tools/call with NO Mcp-Name header returns 400 with -32020"() {
        // Mcp-Name mirrors params.name and is REQUIRED for tools/call. This server
        // implements no resources/read or prompts/get, so tools/call is the whole set.
        given:
        mcpDriver.pushHeaders(['MCP-Protocol-Version': '2026-07-28', 'Mcp-Method': 'tools/call'])
        mcpDriver.pushBody([jsonrpc: '2.0', id: 306, method: 'tools/call',
                            params: [name: 'hub_list_rooms', arguments: [:]]])

        when:
        script.handleMcpRequest()

        then:
        mcpDriver.lastRenderArgs.status == 400
        def response = mcpDriver.parseResponseJson()
        response.error.code == -32020
        response.error.message.contains('Mcp-Name')
        response.error.message.toLowerCase().contains('required')
    }

    def "a modern tools/call whose Mcp-Name disagrees with params.name returns 400 with -32020"() {
        given:
        mcpDriver.pushHeaders([
            'MCP-Protocol-Version': '2026-07-28',
            'Mcp-Method': 'tools/call',
            'Mcp-Name': 'hub_delete_room',
        ])
        mcpDriver.pushBody([jsonrpc: '2.0', id: 307, method: 'tools/call',
                            params: [name: 'hub_list_rooms', arguments: [:]]])

        when:
        script.handleMcpRequest()

        then: 'rejected BEFORE dispatch -- a header/body split is exactly the confusion this blocks'
        mcpDriver.lastRenderArgs.status == 400
        def response = mcpDriver.parseResponseJson()
        response.error.code == -32020
        response.error.message.contains('Mcp-Name')
        response.error.message.contains('hub_delete_room')
        response.error.message.contains('hub_list_rooms')
    }

    def "a base64-sentinel Mcp-Name is decoded before the body comparison and passes"() {
        // "=?base64?<data>?=" is the spec's escape for a value that cannot ride as
        // plain visible ASCII (and clients MUST also wrap any literal value that
        // happens to match the sentinel). Servers MUST decode before comparing.
        given:
        script.metaClass.getRooms = { -> [[id: 1L, name: 'Den']] }
        def encoded = '=?base64?' + 'hub_list_rooms'.bytes.encodeBase64().toString() + '?='
        // Pin the literal wire form so a fixture that stopped producing a real sentinel
        // (and therefore proved nothing) fails here rather than passing as a plain value.
        assert encoded == '=?base64?aHViX2xpc3Rfcm9vbXM=?='
        mcpDriver.pushHeaders([
            'MCP-Protocol-Version': '2026-07-28',
            'Mcp-Method': 'tools/call',
            'Mcp-Name': encoded,
        ])
        mcpDriver.pushBody([jsonrpc: '2.0', id: 308, method: 'tools/call',
                            params: [name: 'hub_list_rooms', arguments: [:]]])

        when:
        script.handleMcpRequest()

        then: 'the encoded header matched, so the call ran'
        mcpDriver.lastRenderArgs.status == null
        def response = mcpDriver.parseResponseJson()
        response.error == null
        mcpDriver.parseInner(response).rooms*.name == ['Den']
    }

    def "a base64-sentinel Mcp-Name that decodes to the WRONG name is still a -32020 mismatch"() {
        given:
        def encoded = '=?base64?' + 'hub_delete_room'.bytes.encodeBase64().toString() + '?='
        mcpDriver.pushHeaders([
            'MCP-Protocol-Version': '2026-07-28',
            'Mcp-Method': 'tools/call',
            'Mcp-Name': encoded,
        ])
        mcpDriver.pushBody([jsonrpc: '2.0', id: 309, method: 'tools/call',
                            params: [name: 'hub_list_rooms', arguments: [:]]])

        when:
        script.handleMcpRequest()

        then: 'the DECODED value is what appears in the message, not the wrapper'
        mcpDriver.lastRenderArgs.status == 400
        def response = mcpDriver.parseResponseJson()
        response.error.code == -32020
        response.error.message.contains('hub_delete_room')
        !response.error.message.contains('=?base64?')
    }

    def "a modern request whose _meta protocolVersion disagrees with the header returns 400 with -32020"() {
        // The header value MUST match the body's
        // params._meta["io.modelcontextprotocol/protocolVersion"] -- the whole point of
        // mirroring is that an intermediary routing on the header and the server
        // executing on the body cannot disagree.
        given:
        mcpDriver.pushHeaders(['MCP-Protocol-Version': '2026-07-28', 'Mcp-Method': 'tools/list'])
        mcpDriver.pushBody([jsonrpc: '2.0', id: 310, method: 'tools/list',
                            params: ['_meta': ['io.modelcontextprotocol/protocolVersion': '2025-11-25']]])

        when:
        script.handleMcpRequest()

        then:
        mcpDriver.lastRenderArgs.status == 400
        def response = mcpDriver.parseResponseJson()
        response.error.code == -32020
        response.error.message.contains('2026-07-28')
        response.error.message.contains('2025-11-25')
    }

    def "a modern request whose _meta protocolVersion AGREES with the header dispatches normally"() {
        given:
        mcpDriver.pushHeaders(['MCP-Protocol-Version': '2026-07-28', 'Mcp-Method': 'tools/list'])
        mcpDriver.pushBody([jsonrpc: '2.0', id: 311, method: 'tools/list',
                            params: ['_meta': ['io.modelcontextprotocol/protocolVersion': '2026-07-28']]])

        when:
        script.handleMcpRequest()

        then:
        mcpDriver.lastRenderArgs.status == null
        def response = mcpDriver.parseResponseJson()
        response.error == null
        response.result.tools instanceof List
    }

    def "a modern request with no _meta at all dispatches normally (the header is the only REQUIRED copy)"() {
        given:
        mcpDriver.pushHeaders(['MCP-Protocol-Version': '2026-07-28', 'Mcp-Method': 'tools/list'])
        mcpDriver.pushBody([jsonrpc: '2.0', id: 312, method: 'tools/list', params: [:]])

        when:
        script.handleMcpRequest()

        then:
        mcpDriver.lastRenderArgs.status == null
        mcpDriver.parseResponseJson().error == null
    }

    def "a modern BATCH is rejected with 400 + -32600 -- the modern transport forbids a multi-message body"() {
        // -32600 Invalid Request, NOT -32020: the modern transport requires the POST body
        // to be a single JSON-RPC message, so an array is a malformed BODY. -32020 is
        // defined for header/body disagreement and missing/malformed headers, a different
        // fault. Legacy batches (headerless or legacy-versioned) still render a 200 array
        // -- see the era-switch and batch features above.
        given:
        mcpDriver.pushHeaders(['MCP-Protocol-Version': '2026-07-28', 'Mcp-Method': 'tools/list'])
        mcpDriver.pushBody([
            [jsonrpc: '2.0', id: 313, method: 'tools/list', params: [:]],
            [jsonrpc: '2.0', id: 314, method: 'ping', params: [:]],
        ])

        when:
        script.handleMcpRequest()

        then: 'a single error object with a null id, not an array of per-element results'
        mcpDriver.lastRenderArgs.status == 400
        def response = mcpDriver.parseResponseJson()
        !(response instanceof List)
        response.id == null
        response.error.code == -32600
        response.error.message.contains('batch')
    }

    def "an unknown method on a modern request returns HTTP 404 with -32601 still in the body"() {
        // 2026-07-28 pins an unknown method to 404. The JSON-RPC body is load-bearing:
        // it is what lets a dual-era client tell this apart from the 404 a legacy
        // HTTP+SSE server returns for a path it does not host.
        given:
        mcpDriver.pushHeaders(['MCP-Protocol-Version': '2026-07-28', 'Mcp-Method': 'does/not/exist'])
        mcpDriver.pushBody([jsonrpc: '2.0', id: 315, method: 'does/not/exist'])

        when:
        script.handleMcpRequest()

        then:
        mcpDriver.lastRenderArgs.status == 404
        mcpDriver.lastRenderArgs.contentType == 'application/json'
        def response = mcpDriver.parseResponseJson()
        response.jsonrpc == '2.0'
        response.id == 315
        response.error.code == -32601
        response.error.message == 'Method not found: does/not/exist'
    }

    def "a modern request answered with -32600 keeps the JSON-RPC-native 200 (only -32601 maps to 404)"() {
        // Guards the narrowness of the mapping: the header validation passed, so the
        // request reached dispatch, and every application-level error OTHER than
        // method-not-found still rides a 200 body exactly as it does on the legacy path.
        given: 'valid modern headers, but a body that fails the JSON-RPC 2.0 marker check'
        mcpDriver.pushHeaders(['MCP-Protocol-Version': '2026-07-28', 'Mcp-Method': 'ping'])
        mcpDriver.pushBody([jsonrpc: '1.0', id: 316, method: 'ping'])

        when:
        script.handleMcpRequest()

        then:
        mcpDriver.lastRenderArgs.status == null
        def response = mcpDriver.parseResponseJson()
        response.id == 316
        response.error.code == -32600
    }

    def "a modern tools/call answered with -32602 keeps the JSON-RPC-native 200"() {
        // The -32602 companion to the case above, through a real gateway dispatch:
        // hub_get_room with no rooms configured throws IllegalArgumentException, which
        // handleToolsCall maps to -32602. Mcp-Name mirrors the OUTER params.name (the
        // gateway), which is what the spec says the header carries.
        given:
        settingsMap.enableRead = true
        settingsMap.useGateways = true
        script.metaClass.getRooms = { -> [] }
        mcpDriver.pushHeaders([
            'MCP-Protocol-Version': '2026-07-28',
            'Mcp-Method': 'tools/call',
            'Mcp-Name': 'hub_read_rooms',
        ])
        mcpDriver.pushBody([jsonrpc: '2.0', id: 317, method: 'tools/call',
                            params: [name: 'hub_read_rooms', arguments: [tool: 'hub_get_room', args: [room: 'X']]]])

        when:
        script.handleMcpRequest()

        then:
        mcpDriver.lastRenderArgs.status == null
        def response = mcpDriver.parseResponseJson()
        response.id == 317
        response.error.code == -32602
    }

    def "a modern NOTIFICATION keeps 202 with no header validation at all"() {
        // The spec explicitly leaves header requirements for a notification POST
        // undefined, so a notification is never rejected for its headers -- even ones
        // that would fail every check on a request (wrong method mirror, unsupported
        // version).
        given:
        mcpDriver.pushHeaders(['MCP-Protocol-Version': '2099-01-01', 'Mcp-Method': 'totally/wrong'])
        mcpDriver.pushBody([jsonrpc: '2.0', method: 'notifications/initialized', params: [:]])

        when:
        script.handleMcpRequest()

        then:
        mcpDriver.lastRenderArgs.status == 202
        mcpDriver.lastRenderArgs.data == ''
    }

    def "a malformed base64-sentinel Mcp-Name is rejected with 400 + -32020 rather than crashing"() {
        // A recognized header carrying invalid characters is a rejection per the spec.
        // The payload is screened against the base64 alphabet before decoding, so this
        // verdict does not depend on how leniently the decoder treats stray characters.
        given:
        mcpDriver.pushHeaders([
            'MCP-Protocol-Version': '2026-07-28',
            'Mcp-Method': 'tools/call',
            'Mcp-Name': '=?base64?not base64!?=',
        ])
        mcpDriver.pushBody([jsonrpc: '2.0', id: 318, method: 'tools/call',
                            params: [name: 'hub_list_rooms', arguments: [:]]])

        when:
        script.handleMcpRequest()

        then:
        mcpDriver.lastRenderArgs.status == 400
        def response = mcpDriver.parseResponseJson()
        response.error.code == -32020
        response.error.message.toLowerCase().contains('malformed')
    }

    @spock.lang.Unroll
    def "_decodeHeaderValue passes a plain value through, decodes a sentinel, and nulls a malformed one: #label"() {
        expect:
        script._decodeHeaderValue(raw) == decoded

        where:
        label                     | raw                                | decoded
        'plain ASCII'             | 'hub_get_info'                     | 'hub_get_info'
        'valid sentinel'          | '=?base64?aHViX2dldF9pbmZv?='      | 'hub_get_info'
        'sentinel with UTF-8'     | '=?base64?SGVsbG8sIOS4lueVjA==?='  | 'Hello, 世界'
        // A literal value that happens to LOOK like the sentinel must itself be
        // encoded by a conforming client, so decoding one is the correct reading.
        'round-tripped sentinel'  | '=?base64?PT9iYXNlNjQ/bGl0ZXJhbD89?=' | '=?base64?literal?='
        'non-base64 payload'      | '=?base64?not base64!?='           | null
        // Too short to hold the wrapper -- not a sentinel, so it passes through
        // verbatim and simply fails the body comparison.
        'wrapper-shaped but short' | '=?base64?='                      | '=?base64?='
        'no wrapper at all'       | '=?other?abc?='                    | '=?other?abc?='
        'null'                    | null                               | null
    }

    def "request.headers reading back null degrades to the legacy path"() {
        // Older/unknown firmware may not expose the header map. _requestHeader must
        // answer null rather than throw, which lands the request on the legacy path --
        // never a 400 and never a hub 500.
        given:
        mcpDriver.pushBody([jsonrpc: '2.0', id: 320, method: 'tools/list', params: [:]])
        mcpDriver.nullHeaders = true

        when:
        script.handleMcpRequest()

        then:
        mcpDriver.lastRenderArgs.status == null
        def response = mcpDriver.parseResponseJson()
        response.error == null
        response.result.tools instanceof List
    }

    def "request.headers throwing degrades to the legacy path"() {
        given: 'firmware that does not expose the property at all'
        mcpDriver.pushBody([jsonrpc: '2.0', id: 321, method: 'tools/list', params: [:]])
        mcpDriver.throwingHeaders = new MissingPropertyException('headers', Object)

        when:
        script.handleMcpRequest()

        then:
        mcpDriver.lastRenderArgs.status == null
        def response = mcpDriver.parseResponseJson()
        response.error == null
        response.result.tools instanceof List
    }

    def "GET on the endpoint is 405 regardless of Origin -- the POST-only rule is the outer boundary"() {
        // Documents the real boundary: handleMcpGet answers before any Origin logic, so a browser
        // GET from a foreign origin gets the POST-only 405, not the 403. Neither leaks anything;
        // pinning it stops a future reader "fixing" handleMcpGet to 403 and changing the contract.
        given:
        mcpDriver.pushHeaders(['Origin': 'http://evil.example'])

        when:
        script.handleMcpGet()

        then:
        mcpDriver.lastRenderArgs.status == 405
        mcpDriver.parseResponseJson().error.code == -32600
    }

    def "a modern-header all-notifications batch still answers 202"() {
        // Header requirements for a notification POST are undefined by the spec, and that exemption
        // must survive a BATCH of them -- the batch rejection is scoped to POSTs carrying a request.
        given:
        mcpDriver.pushHeaders(['MCP-Protocol-Version': '2026-07-28', 'Mcp-Method': 'tools/list'])
        mcpDriver.pushBody([
            [jsonrpc: '2.0', method: 'notifications/initialized', params: [:]],
            [jsonrpc: '2.0', method: 'notifications/initialized', params: [:]],
        ])

        when:
        script.handleMcpRequest()

        then:
        mcpDriver.lastRenderArgs.status == 202
        mcpDriver.lastRenderArgs.data == ''
    }

    def "a modern request with an Mcp-Method header but NO body method is a -32020 mismatch"() {
        // The mirrored header must match the body, and "the body has no method at all" is a
        // mismatch, not a pass -- otherwise a malformed body could smuggle past the check.
        given:
        mcpDriver.pushHeaders(['MCP-Protocol-Version': '2026-07-28', 'Mcp-Method': 'tools/list'])
        mcpDriver.pushBody([jsonrpc: '2.0', id: 450])

        when:
        script.handleMcpRequest()

        then:
        mcpDriver.lastRenderArgs.status == 400
        def response = mcpDriver.parseResponseJson()
        response.error.code == -32020
        response.error.message.contains('Mcp-Method')
    }

    @spock.lang.Unroll
    def "a padded MCP-Protocol-Version is trimmed per RFC 9110, so #version lands on its proper era"() {
        // RFC 9110 excludes leading/trailing optional whitespace from a field VALUE, so
        // "2026-07-28 " IS "2026-07-28". Trimming in _requestHeader is what makes that true; without
        // it a padded value matches nothing in supportedProtocolVersions() and 400s as unsupported,
        // which is neither era and is wrong for both. Each padded version must reach exactly the
        // path its trimmed form would.
        given:
        mcpDriver.pushHeaders(['MCP-Protocol-Version': version] + extraHeaders)
        mcpDriver.pushBody([jsonrpc: '2.0', id: 451, method: 'tools/list', params: [:]])

        when:
        script.handleMcpRequest()

        then: 'served normally -- never the -32022 an untrimmed value would produce'
        mcpDriver.lastRenderArgs.status == null
        def response = mcpDriver.parseResponseJson()
        response.error == null
        response.result.tools instanceof List

        and: 'and it landed on the expected era, which resultType reveals'
        response.result.containsKey('resultType') == modernExpected

        where:
        // A padded MODERN version is modern, so it also has to satisfy the mirrored-header contract
        // -- Mcp-Method is supplied here for exactly that reason, which is itself the proof that the
        // trimmed value took the modern path.
        version         | extraHeaders                | modernExpected
        '2026-07-28 '   | ['Mcp-Method': 'tools/list'] | true
        ' 2026-07-28'   | ['Mcp-Method': 'tools/list'] | true
        '2025-06-18 '   | [:]                          | false
        ' 2025-11-25 '  | [:]                          | false
    }

    def "a padded MODERN version still enforces the mirrored-header contract"() {
        // The other half of the trim: once " 2026-07-28 " IS the modern era, a missing Mcp-Method
        // must fail it. Otherwise trimming would have quietly created a header-validation bypass.
        given:
        mcpDriver.pushHeaders(['MCP-Protocol-Version': '2026-07-28 '])
        mcpDriver.pushBody([jsonrpc: '2.0', id: 452, method: 'tools/list', params: [:]])

        when:
        script.handleMcpRequest()

        then:
        mcpDriver.lastRenderArgs.status == 400
        mcpDriver.parseResponseJson().error.code == -32020
    }

    def "server/discover works on the modern era too, with its headers validated"() {
        // discover is reachable in BOTH eras. Headerless is pinned above; this is the modern side,
        // where the mirrored Mcp-Method must match or the call never reaches the handler.
        given:
        settingsMap.useGateways = true
        mcpDriver.pushHeaders(['MCP-Protocol-Version': '2026-07-28', 'Mcp-Method': 'server/discover'])
        mcpDriver.pushBody([jsonrpc: '2.0', id: 452, method: 'server/discover', params: [:]])

        when:
        script.handleMcpRequest()

        then:
        mcpDriver.lastRenderArgs.status == null
        def response = mcpDriver.parseResponseJson()
        response.error == null
        response.result.supportedVersions[0] == '2026-07-28'
        response.result.resultType == 'complete'

        when: 'the same call with a MISMATCHED Mcp-Method never reaches the handler'
        mcpDriver.pushHeaders(['MCP-Protocol-Version': '2026-07-28', 'Mcp-Method': 'tools/list'])
        mcpDriver.pushBody([jsonrpc: '2.0', id: 453, method: 'server/discover', params: [:]])
        script.handleMcpRequest()

        then:
        mcpDriver.lastRenderArgs.status == 400
        mcpDriver.parseResponseJson().error.code == -32020
    }

    // ---- Origin validation (DNS-rebinding defence, both eras) ----
    //
    // The comparison is against identities the SERVER knows for itself
    // (location.hub.localIP, the cloud relay, loopback) -- NOT against the request's own
    // Host header. A Host comparison would be self-referential and useless: in a real
    // rebinding attack the browser sends Origin AND Host both naming the attacker's
    // domain, so they agree. The Host-match rows below are therefore localIP-match rows,
    // and the harness seeds localIP in setup().
    //
    // This matrix is the ONLY coverage of Origin handling: the e2e suite deliberately
    // carries no Origin scenarios, so connectivity to the live test hub can never depend
    // on Origin behavior. Anything provable about Origin has to be proven here.

    def "a present Origin naming none of the server's known identities is rejected with 403"() {
        // Streamable HTTP: "servers MUST validate the Origin header on all incoming
        // connections to prevent DNS rebinding attacks... respond with HTTP 403
        // Forbidden. The body MAY comprise a JSON-RPC error response that has no id."
        given: 'enforcement ON (opt-in; the default is log-only) and the attacker sends a matching Host too -- exactly what a rebinding browser does'
        settingsMap.enforceOriginValidation = true
        mcpDriver.pushHeaders(['Origin': 'http://evil.example', 'Host': 'evil.example'])
        mcpDriver.pushBody([jsonrpc: '2.0', id: 400, method: 'tools/list', params: [:]])

        when:
        script.handleMcpRequest()

        then: 'rejected despite Origin and Host agreeing -- the agreement is attacker-controlled'
        mcpDriver.lastRenderArgs.status == 403
        mcpDriver.lastRenderArgs.contentType == 'application/json'
        def response = mcpDriver.parseResponseJson()
        response.jsonrpc == '2.0'
        response.id == null
        response.error.message.contains('Origin')
    }

    @spock.lang.Unroll
    def "Origin #origin gives allowed=#allowed when the hub's localIP is 192.168.1.133"() {
        given: 'enforcement ON so a mismatch is observable as a status, plus a Host that always AGREES with the Origin to prove Host plays no part'
        settingsMap.enforceOriginValidation = true
        mcpDriver.pushHeaders(['Origin': origin, 'Host': 'whatever.example'])
        mcpDriver.pushBody([jsonrpc: '2.0', id: 401, method: 'ping', params: [:]])

        when:
        script.handleMcpRequest()

        then:
        mcpDriver.lastRenderArgs.status == (allowed ? null : 403)

        where:
        origin                              || allowed
        // The hub's own LAN address, port and scheme irrelevant, case-insensitive.
        'http://192.168.1.133'              || true
        'http://192.168.1.133:8080'         || true
        'https://192.168.1.133'             || true
        // The Hubitat cloud relay fronts this endpoint, so it is always a valid origin.
        'https://cloud.hubitat.com'         || true
        'https://CLOUD.Hubitat.Com'         || true
        // Loopback identities.
        'http://localhost:8080'             || true
        'http://127.0.0.1'                  || true
        'http://[::1]:8080'                 || true
        // Anything else -- including the rebinding case and a suffix-confusion attempt.
        'http://evil.example'               || false
        'http://192.168.1.133.evil.example' || false
        'http://cloud.hubitat.com.evil.example' || false
        'http://192.168.1.134'              || false
        // Malformed reads as invalid, never as an exception.
        'null'                              || false
        'not-a-url'                         || false
        'http://'                           || false
    }

    def "an unreadable hub localIP narrows the allowed set to the static identities instead of failing open"() {
        given: 'enforcement ON, and firmware with no localIP -- location.hub present but the property is null'
        settingsMap.enforceOriginValidation = true
        sharedLocation.hub = new TestHub()
        mcpDriver.pushHeaders(['Origin': 'https://cloud.hubitat.com'])
        mcpDriver.pushBody([jsonrpc: '2.0', id: 403, method: 'ping', params: [:]])

        when:
        script.handleMcpRequest()

        then: 'the static identities still work'
        mcpDriver.lastRenderArgs.status == null

        when: 'and an origin that would only have matched the LAN IP is now rejected'
        mcpDriver.pushHeaders(['Origin': 'http://192.168.1.133'])
        mcpDriver.pushBody([jsonrpc: '2.0', id: 404, method: 'ping', params: [:]])
        script.handleMcpRequest()

        then: 'not failed open'
        mcpDriver.lastRenderArgs.status == 403
    }

    def "an absent location never throws -- the Origin check degrades to the static identities"() {
        given:
        sharedLocation.hub = null
        mcpDriver.pushHeaders(['Origin': 'http://localhost'])
        mcpDriver.pushBody([jsonrpc: '2.0', id: 405, method: 'ping', params: [:]])

        when:
        script.handleMcpRequest()

        then:
        mcpDriver.lastRenderArgs.status == null
        mcpDriver.parseResponseJson().error == null
    }

    @spock.lang.Unroll
    def "additionalAllowedOrigins admits #origin when configured as #setting"() {
        // Additive escape hatch for reverse-proxy / remote-access fronting, where a browser
        // client's Origin is neither the hub's LAN address nor cloud.hubitat.com. Deny-by-default
        // is unchanged: only names listed here are added.
        given: 'enforcement ON, so an allow verdict is observable as "not 403"'
        settingsMap.enforceOriginValidation = true
        settingsMap.additionalAllowedOrigins = setting
        mcpDriver.pushHeaders(['Origin': origin])
        mcpDriver.pushBody([jsonrpc: '2.0', id: 410, method: 'ping', params: [:]])

        when:
        script.handleMcpRequest()

        then:
        mcpDriver.lastRenderArgs.status == null

        where:
        setting                                    | origin
        'mcp.example.com'                          | 'https://mcp.example.com'
        // whitespace and case are tolerated, and a port on the request side is ignored
        '  mcp.example.com  '                      | 'https://MCP.Example.COM:8443'
        // multi-entry, with a blank the split must drop
        'a.example.com, ,mcp.example.com'          | 'https://mcp.example.com'
        // a pasted URL still reduces to its hostname
        'https://mcp.example.com:8443/mcp'         | 'https://mcp.example.com'
    }

    def "an origin NOT in additionalAllowedOrigins is still rejected (the hatch is additive, not a bypass)"() {
        given:
        settingsMap.enforceOriginValidation = true
        settingsMap.additionalAllowedOrigins = 'mcp.example.com'
        mcpDriver.pushHeaders(['Origin': 'http://evil.example'])
        mcpDriver.pushBody([jsonrpc: '2.0', id: 411, method: 'ping', params: [:]])

        when:
        script.handleMcpRequest()

        then:
        mcpDriver.lastRenderArgs.status == 403
    }

    def "an unset additionalAllowedOrigins changes nothing"() {
        given: 'enforcement ON and the setting absent entirely -- the default allowlist still governs'
        settingsMap.enforceOriginValidation = true
        settingsMap.remove('additionalAllowedOrigins')
        mcpDriver.pushHeaders(['Origin': 'https://mcp.example.com'])
        mcpDriver.pushBody([jsonrpc: '2.0', id: 412, method: 'ping', params: [:]])

        when:
        script.handleMcpRequest()

        then:
        mcpDriver.lastRenderArgs.status == 403

        and: 'and the hub LAN address is still admitted'
        mcpDriver.pushHeaders(['Origin': 'http://192.168.1.133'])
        mcpDriver.pushBody([jsonrpc: '2.0', id: 413, method: 'ping', params: [:]])
        script.handleMcpRequest()
        mcpDriver.lastRenderArgs.status == null
    }

    def "by DEFAULT a mismatched Origin is SERVED, not rejected (validation is log-only)"() {
        // Deliberate deviation from the spec MUST, recorded on _originAllowed: the token lives in
        // the request URL, so a rebound page cannot authenticate and a tokenless request never
        // reaches this handler -- while enforcing by default would newly 403 working reverse-proxy
        // and browser-client setups that no shipped version ever rejected.
        given: 'enforceOriginValidation unset -- the shipped default'
        settingsMap.remove('enforceOriginValidation')
        mcpDriver.pushHeaders(['Origin': 'http://evil.example', 'Host': 'evil.example'])
        mcpDriver.pushBody([jsonrpc: '2.0', id: 420, method: 'tools/list', params: [:]])

        when:
        script.handleMcpRequest()

        then: 'served normally -- no 403, and the request actually dispatched'
        mcpDriver.lastRenderArgs.status == null
        def response = mcpDriver.parseResponseJson()
        response.id == 420
        response.error == null
        response.result.tools instanceof List
    }

    def "the log-only mismatch is still reported at error level with both sides named"() {
        // Log-only must not mean silent: the mismatch is the only trace, so it carries the offending
        // origin, the allowed set, and the fact that enforcement is off.
        given:
        settingsMap.enforceOriginValidation = false
        stateMap.debugLogs = [entries: [], config: [logLevel: 'debug', maxEntries: 1000]]
        settingsMap.mcpLogLevel = 'debug'
        mcpDriver.pushHeaders(['Origin': 'http://evil.example'])
        mcpDriver.pushBody([jsonrpc: '2.0', id: 421, method: 'ping', params: [:]])

        when:
        script.handleMcpRequest()

        then: 'served, and an error-level entry explains why it was not rejected'
        mcpDriver.lastRenderArgs.status == null
        // Match the MISMATCH line specifically. 'Origin' alone also matches the header-readability
        // entry, which is how this originally picked up an info-level record and failed.
        def entry = (stateMap.debugLogs?.entries ?: []).find { it.message?.contains('Origin MISMATCH') }
        entry != null
        entry.level == 'error'
        entry.message.contains('evil.example')
        entry.message.contains('enforceOriginValidation is off')
    }

    def "explicitly enabling enforcement restores the 403"() {
        given:
        settingsMap.enforceOriginValidation = true
        mcpDriver.pushHeaders(['Origin': 'http://evil.example'])
        mcpDriver.pushBody([jsonrpc: '2.0', id: 422, method: 'ping', params: [:]])

        when:
        script.handleMcpRequest()

        then:
        mcpDriver.lastRenderArgs.status == 403
    }

    def "an absent Origin always passes -- server-to-server MCP clients never send one"() {
        given: 'a Host but no Origin at all'
        mcpDriver.pushHeaders(['Host': '192.168.1.133'])
        mcpDriver.pushBody([jsonrpc: '2.0', id: 402, method: 'tools/list', params: [:]])

        when:
        script.handleMcpRequest()

        then:
        mcpDriver.lastRenderArgs.status == null
        mcpDriver.parseResponseJson().result.tools instanceof List
    }

    def "the Origin check runs before the body is parsed -- a rebinding POST cannot reach dispatch"() {
        given: 'enforcement ON, a bad Origin, AND a body whose read would throw'
        settingsMap.enforceOriginValidation = true
        mcpDriver.pushHeaders(['Origin': 'http://evil.example'])
        mcpDriver.pushBodyThrowing(new RuntimeException('body must never be read'))

        when:
        script.handleMcpRequest()

        then: '403, not the -32700 parse error -- the body was never touched'
        mcpDriver.lastRenderArgs.status == 403
        mcpDriver.parseResponseJson().error.code == -32600
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
        init.result.protocolVersion == '2025-11-25'
        init.result.serverInfo.name == 'hubitat-mcp-rule-server'

        and: 'ping response (id=11) carries no payload beyond the universal envelope key'
        // ping still returns an empty result body. This batch is headerless (legacy), so
        // jsonRpcResult stamps ONLY _meta -- resultType would break a legacy client's
        // strict EmptyResult parse. Pinned as an exact keySet.
        def ping = response.find { it.id == 11 }
        ping.result.keySet() == ['_meta'] as Set

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
        first.result.protocolVersion == '2025-11-25'
        first.error == null

        and: 'item 21 is a method-not-found error — isolated, does not affect neighbours'
        def middle = response.find { it.id == 21 }
        middle.error.code == -32601

        and: 'item 22 is a success — not contaminated by item 21'
        def last = response.find { it.id == 22 }
        last.result.keySet() == ['_meta'] as Set
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

    def "batch over the 50-element cap returns a single -32600 'batch too large' envelope (no per-element dispatch)"() {
        given: 'a 51-element batch — one over the inbound cap'
        // Spy so we can prove the per-element dispatcher was never entered.
        int perElementCalls = 0
        script.metaClass.processJsonRpcMessage = { m -> perElementCalls++; null }
        mcpDriver.pushBody((1..51).collect { i -> [jsonrpc: '2.0', id: i, method: 'ping', params: [:]] })

        when:
        script.handleMcpRequest()

        then: 'a single error Map (not an array) is rendered'
        def response = mcpDriver.parseResponseJson()
        !(response instanceof List)
        response.jsonrpc == '2.0'
        response.id == null
        response.error.code == -32600
        response.error.message == 'Invalid Request: batch too large (51 elements, max 50)'

        and: 'the cap short-circuited before any per-element dispatch'
        perElementCalls == 0
        // No cleanup needed: HarnessSpec.setup() dual-wipes the per-instance
        // metaClass before each feature, so this override does not leak.
    }

    def "batch at exactly the 50-element cap dispatches normally and returns an array"() {
        given: 'exactly 50 ping requests — the boundary is inclusive'
        mcpDriver.pushBody((1..50).collect { i -> [jsonrpc: '2.0', id: i, method: 'ping', params: [:]] })

        when:
        script.handleMcpRequest()

        then: 'a 50-element response array, each a payload-free ping success'
        def response = mcpDriver.parseResponseJson()
        response instanceof List
        response.size() == 50
        response.every { it.jsonrpc == '2.0' && it.error == null && it.result.keySet() == ['_meta'] as Set }
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

        then: 'production catch in hubitat-mcp-server.groovy turned it into -32700'
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

    def "handleMcpGet returns 405 with a JSON-RPC -32600 POST-only envelope"() {
        when:
        script.handleMcpGet()

        then:
        mcpDriver.lastRenderArgs.status == 405
        mcpDriver.lastRenderArgs.contentType == 'application/json'
        def body = mcpDriver.parseResponseJson()
        body.jsonrpc == '2.0'
        body.id == null
        body.error.code == -32600
        body.error.message.contains('POST')
        body.error.message.contains('SSE')
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
        settingsMap.enableCustomRuleEngine = true

        when: 'sized as the REAL wire response — that is what the outer guard measures'
        // Measuring handleToolsList's own envelope (not a bare [tools: ...] wrap) keeps
        // the tripwire honest: the JSON-RPC frame, the resultType/_meta decoration and
        // the ttlMs/cacheScope cache hints all spend budget on a user's hub too.
        int flatBytes = groovy.json.JsonOutput.toJson(script.handleToolsList([id: 1])).getBytes("UTF-8").length
        int fullBytes = groovy.json.JsonOutput.toJson([tools: script.getAllToolDefinitions()]).getBytes("UTF-8").length

        then: 'the flat catalog fits under the cap'
        assert flatBytes < 124000 : "flat tools/list wire response is ${flatBytes} bytes, over the 124,000 cap"

        and: 'the strip + [[FLAT_TRIM]] is load-bearing: the un-stripped defs are materially larger'
        fullBytes > flatBytes
    }

    def "outputSchema survives JSON serialization in gateway mode; gateway entries carry none"() {
        // Mirrors the annotations-survive-serialization guard above: outputSchema is
        // a nested Map of Maps, the kind of payload a JsonOutput/transform regression
        // would silently drop. With publishOutputSchemas ON (issue #290), assert it lands
        // on the wire for a base tool, and that gateway entries (which proxy many tools)
        // carry no single outputSchema. (The default-OFF case is the companion test below.)
        given:
        settingsMap.useGateways = true
        settingsMap.enableCustomRuleEngine = true
        settingsMap.publishOutputSchemas = true
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

    def "outputSchema is NOT emitted on tools/list by default (issue #290 regression guard)"() {
        // Default (publishOutputSchemas OFF): strict clients (e.g. Claude Desktop via the
        // MCP TypeScript SDK) throw JSON-RPC -32600 when a tool advertises outputSchema but
        // the result carries no structuredContent. This server returns text only, so by
        // default NO tools/list entry may carry outputSchema. This pins the #290 fix at the
        // full handleMcpRequest wire layer (a real client's exact path).
        given:
        settingsMap.useGateways = true
        settingsMap.enableCustomRuleEngine = true
        // publishOutputSchemas unset => OFF (default)
        mcpDriver.pushBody([jsonrpc: '2.0', id: 72, method: 'tools/list', params: [:]])

        when:
        script.handleMcpRequest()
        def response = mcpDriver.parseResponseJson()

        then: 'no tool entry advertises outputSchema'
        response.result.tools.every { !it.containsKey('outputSchema') }

        and: 'hub_get_info is present, simply without an outputSchema key'
        def info = response.result.tools.find { it.name == 'hub_get_info' }
        info != null
        info.containsKey('outputSchema') == false
    }

    def "every gateway catalog disclosure stays under the 120,000-byte tools/call cap"() {
        // The gateway catalog (handleGateway with no toolName) is the canonical home for
        // the heavy outputSchemas WHEN publishOutputSchemas is on (issue #290), and it is
        // bounded by the 120,000-byte tools/call cap. Over it, the caller gets a
        // response_too_large envelope instead of the catalog and can no longer discover
        // any tool in that gateway. The largest today (hub_manage_native_rules_and_apps)
        // is ~76KB; pin all 19 with the toggle ON so the worst case stays guarded.
        given:
        settingsMap.enableCustomRuleEngine = true
        settingsMap.publishOutputSchemas = true

        when:
        def oversize = script.getGatewayConfig().keySet().findAll { gw ->
            groovy.json.JsonOutput.toJson(script.handleGateway(gw, null, null)).getBytes("UTF-8").length >= 120000
        }

        then:
        assert oversize.isEmpty() : "gateway catalog(s) over the 120,000-byte tools/call cap: ${oversize}"
    }
}
