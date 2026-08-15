package server

import spock.lang.Shared
import spock.lang.Unroll
import support.McpSchemaValidator
import support.TestHub
import support.TestLocation
import support.ToolSpecBase

/**
 * Validates REAL rendered wire responses against the OFFICIAL MCP JSON Schemas vendored under
 * {@code src/test/resources/mcp-schema/}. See docs/testing.md § Conformance harness.
 *
 * The two eras are validated against DIFFERENT schemas on purpose, because the shapes genuinely
 * differ: the modern {@code ListToolsResult} REQUIRES {@code resultType}, which a legacy result
 * must not carry. Cross-validating an era against the other schema is a bug in the test rather
 * than a finding.
 *
 * The last three features are NEGATIVE CONTROLS — one per schema plus the strict view. A
 * validator wired up wrongly (bad path, empty definitions map, swallowed messages) reports "no
 * violations" for everything and green-washes the whole spec. Keep them.
 */
class McpWireSchemaConformanceSpec extends ToolSpecBase {

    /** Stubbed once because the Origin check reads {@code location.hub.localIP} on every request. */
    @Shared private TestLocation sharedLocation = new TestLocation()

    def setupSpec() {
        appExecutor.getLocation() >> sharedLocation
    }

    def setup() {
        sharedLocation.hub = new TestHub(localIP: '192.168.1.133')
    }

    /** Drive one request through the dispatch pipeline and return the parsed response. */
    private Map dispatch(Map body, Map headers = null) {
        if (headers != null) mcpDriver.pushHeaders(headers)
        mcpDriver.pushBody(body)
        script.handleMcpRequest()
        return mcpDriver.parseResponseJson() as Map
    }

    // ---------------------------------------------------------------------
    // Legacy era — the published 2025-06-18 schema
    // ---------------------------------------------------------------------

    def "a legacy initialize response conforms to InitializeResult and the JSONRPCResponse envelope"() {
        given: 'gateway mode explicit -- the instructions prose is mode-branched'
        settingsMap.useGateways = true

        when:
        def response = dispatch([jsonrpc: '2.0', id: 1, method: 'initialize', params: [:]])

        then: 'the result satisfies InitializeResult (protocolVersion + capabilities + serverInfo all required)'
        McpSchemaValidator.legacyErrors('InitializeResult', response.result) == []

        and: 'and the whole rendered envelope satisfies JSONRPCResponse'
        McpSchemaValidator.legacyErrors('JSONRPCResponse', response) == []
    }

    @Unroll
    def "a legacy tools/list catalog conforms to ListToolsResult in #mode mode"() {
        // Validates every advertised Tool entry, not just the wrapper: name, the inputSchema
        // `type: "object"` const, each properties value being an object, and the
        // ToolAnnotations hint types. Both catalog SHAPES are checked because different code
        // paths build them -- gateway envelopes vs. the flat leaf catalog.
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = dispatch([jsonrpc: '2.0', id: 2, method: 'tools/list', params: [:]])

        then: 'a non-trivial catalog really was rendered -- an empty list would validate vacuously'
        response.result.tools.size() > 5

        and:
        McpSchemaValidator.legacyErrors('ListToolsResult', response.result) == []

        where:
        mode      | useGateways
        'gateway' | true
        'flat'    | false
    }

    def "a legacy tools/list catalog still conforms with publishOutputSchemas ON"() {
        // Issues #290/#342: with the advanced toggle on, base-tool entries carry the WIRE
        // form of their outputSchema (required arrays stripped by _wireOutputSchema). The
        // legacy Tool schema constrains outputSchema too -- `type` is required and const
        // "object" -- so the emitted wire form has to satisfy it, not just the definition.
        given:
        settingsMap.useGateways = true
        settingsMap.publishOutputSchemas = true

        when:
        def response = dispatch([jsonrpc: '2.0', id: 3, method: 'tools/list', params: [:]])

        then: 'at least one entry really did carry an outputSchema -- otherwise this proves nothing'
        response.result.tools.any { it.outputSchema != null }

        and:
        McpSchemaValidator.legacyErrors('ListToolsResult', response.result) == []
    }

    def "a legacy tools/call result conforms to CallToolResult"() {
        given:
        script.metaClass.getRooms = { -> [[id: 1L, name: 'Den']] }

        when:
        def response = dispatch([jsonrpc: '2.0', id: 4, method: 'tools/call',
                                 params: [name: 'hub_list_rooms', arguments: [:]]])

        then: 'the tool really ran -- so the content block under test is a tool payload'
        response.result.isError != true
        response.result.content[0].type == 'text'

        and:
        McpSchemaValidator.legacyErrors('CallToolResult', response.result) == []
    }

    def "an isError tools/call envelope also conforms to CallToolResult"() {
        // The error path is a DIFFERENT construction site in handleToolsCall (content plus
        // isError: true) and, per the spec, still rides a JSON-RPC success envelope -- so it
        // has to satisfy the same result schema as a happy path.
        given: 'a tool whose implementation throws'
        script.metaClass.getRooms = { -> throw new RuntimeException('simulated hub failure') }

        when:
        def response = dispatch([jsonrpc: '2.0', id: 5, method: 'tools/call',
                                 params: [name: 'hub_list_rooms', arguments: [:]]])

        then: 'this really is the isError shape, not a JSON-RPC error'
        response.error == null
        response.result.isError == true

        and:
        McpSchemaValidator.legacyErrors('CallToolResult', response.result) == []
    }

    // ---------------------------------------------------------------------
    // Modern era — the published 2026-07-28 schema
    // ---------------------------------------------------------------------

    def "a modern tools/list result conforms to the 2026-07-28 ListToolsResult"() {
        // The modern ListToolsResult REQUIRES resultType, ttlMs, cacheScope and tools -- which
        // makes this the schema-side proof that the CacheableResult hints and the era-gated
        // resultType stamp are all present together on the modern path.
        given:
        settingsMap.useGateways = true

        when:
        def response = dispatch(
            [jsonrpc: '2.0', id: 6, method: 'tools/list', params: [:]],
            ['MCP-Protocol-Version': '2026-07-28', 'Mcp-Method': 'tools/list'])

        then: 'served, not rejected'
        mcpDriver.lastRenderArgs.status == null

        and: 'a non-trivial catalog really was rendered -- the modern schema permits tools: []'
        response.result.tools.size() > 5

        and:
        McpSchemaValidator.modernErrors('ListToolsResult', response.result) == []
    }

    def "a modern tools/call result conforms to the 2026-07-28 CallToolResult"() {
        // Modern CallToolResult requires content AND resultType. tools/call answers from the
        // preserialized fast path, so this also proves the central decoration was baked into
        // that string rather than applied to a map that never reached the wire.
        given:
        script.metaClass.getRooms = { -> [[id: 1L, name: 'Den']] }

        when:
        def response = dispatch(
            [jsonrpc: '2.0', id: 7, method: 'tools/call', params: [name: 'hub_list_rooms', arguments: [:]]],
            ['MCP-Protocol-Version': '2026-07-28', 'Mcp-Method': 'tools/call', 'Mcp-Name': 'hub_list_rooms'])

        then:
        response.result.resultType == 'complete'

        and: 'the tool really ran -- the modern schema permits content: []'
        response.result.isError != true
        response.result.content[0].type == 'text'

        and:
        McpSchemaValidator.modernErrors('CallToolResult', response.result) == []
    }

    def "a state-only modern tools/call continuation conforms to InputRequiredResult"() {
        given:
        settingsMap.enableWrite = true
        def ran = 0
        script.metaClass.toolRunRmRule = { Map a -> ran++; [success: true] }

        when:
        def response = dispatch(
            [jsonrpc: '2.0', id: 71, method: 'tools/call',
             params: [name: 'hub_call_rule', arguments: [ruleId: [71, 72], action: 'stop']]],
            ['MCP-Protocol-Version': '2026-07-28', 'Mcp-Method': 'tools/call',
             'Mcp-Name': 'hub_call_rule'])

        then: 'the preflight performs no write and asks only for automatic state echo'
        ran == 0
        response.result.resultType == 'input_required'
        response.result.requestState instanceof String
        !response.result.containsKey('inputRequests')

        and:
        McpSchemaValidator.modernErrors('InputRequiredResult', response.result) == []
    }

    def "same-generation contention stays a schema-valid state-only continuation"() {
        given:
        settingsMap.enableWrite = true
        def args = [ruleId: [73, 74], action: 'stop']
        def preflight = dispatch(
            [jsonrpc: '2.0', id: 72, method: 'tools/call',
             params: [name: 'hub_call_rule', arguments: args]],
            ['MCP-Protocol-Version': '2026-07-28', 'Mcp-Method': 'tools/call',
             'Mcp-Name': 'hub_call_rule'])
        String stateId = preflight.result.requestState
        Map binding = script._mrtrBinding('hub_call_rule', 'hub_call_rule', args) as Map
        Map claimed = script._mrtrClaim(stateId, 'hub_call_rule', 'hub_call_rule', binding) as Map
        assert claimed.outcome == 'claimed'

        when:
        def response = dispatch(
            [jsonrpc: '2.0', id: 73, method: 'tools/call',
             params: [name: 'hub_call_rule', arguments: args, requestState: stateId]],
            ['MCP-Protocol-Version': '2026-07-28', 'Mcp-Method': 'tools/call',
             'Mcp-Name': 'hub_call_rule'])

        then:
        response.error == null
        response.result == response.result.findAll { it.key in ['resultType', 'requestState', '_meta'] }
        response.result.resultType == 'input_required'
        response.result.requestState == stateId
        McpSchemaValidator.modernErrors('InputRequiredResult', response.result) == []

        cleanup:
        if (claimed != null) {
            script._mrtrAbandon(stateId, claimed.record as Map, claimed, 'test_cleanup')
        }
    }

    def "a server/discover result conforms to the 2026-07-28 DiscoverResult"() {
        // DiscoverResult requires supportedVersions, capabilities, ttlMs, cacheScope AND
        // resultType. Driven HEADERLESS on purpose: discover is the compat probe a stateless
        // client sends before it knows what to put in the header, which is exactly why
        // handleServerDiscover sets resultType itself instead of relying on the era gate.
        given:
        settingsMap.useGateways = true

        when:
        def response = dispatch([jsonrpc: '2.0', id: 8, method: 'server/discover', params: [:]])

        then:
        McpSchemaValidator.modernErrors('DiscoverResult', response.result) == []
    }

    def "an unsupported protocol version rejection conforms to the 2026-07-28 UnsupportedProtocolVersionError"() {
        // The modern schema models this as a whole RESPONSE envelope, and both data.requested and
        // data.supported are REQUIRED -- they are what a client retries from, so a rejection
        // omitting either would wedge it. Schema-checking the envelope is how that stays true.
        when:
        def response = dispatch(
            [jsonrpc: '2.0', id: 9, method: 'tools/list', params: [:]],
            ['MCP-Protocol-Version': '2099-01-01'])

        then: 'the spec mandates 400 for this one'
        mcpDriver.lastRenderArgs.status == 400
        response.error.code == -32022

        and:
        McpSchemaValidator.modernErrors('UnsupportedProtocolVersionError', response) == []
    }

    def "a header mismatch rejection conforms to the 2026-07-28 HeaderMismatchError"() {
        when: 'a modern request with no Mcp-Method header -- a missing required header is a mismatch'
        def response = dispatch(
            [jsonrpc: '2.0', id: 10, method: 'tools/list', params: [:]],
            ['MCP-Protocol-Version': '2026-07-28'])

        then:
        mcpDriver.lastRenderArgs.status == 400
        response.error.code == -32020

        and:
        McpSchemaValidator.modernErrors('HeaderMismatchError', response) == []
    }

    def "a modern unknown method rejection conforms to the 2026-07-28 error envelope and MethodNotFoundError"() {
        // A modern unknown method answers 404, and the -32601 BODY is what distinguishes it
        // from a legacy HTTP+SSE server's 404. The modern schema models MethodNotFoundError as the
        // error OBJECT (code const -32601) and JSONRPCErrorResponse as the envelope, so both
        // halves are checked.
        when:
        def response = dispatch(
            [jsonrpc: '2.0', id: 11, method: 'nope/nope', params: [:]],
            ['MCP-Protocol-Version': '2026-07-28', 'Mcp-Method': 'nope/nope'])

        then:
        mcpDriver.lastRenderArgs.status == 404

        and:
        McpSchemaValidator.modernErrors('JSONRPCErrorResponse', response) == []

        and:
        McpSchemaValidator.modernErrors('MethodNotFoundError', response.error) == []
    }

    // ---------------------------------------------------------------------
    // Resources (issue #366) — both eras
    // ---------------------------------------------------------------------

    def "a legacy resources/list result conforms to ListResourcesResult and each Resource entry"() {
        when:
        def response = dispatch([jsonrpc: '2.0', id: 20, method: 'resources/list', params: [:]])

        then: 'a non-trivial catalog really was rendered -- an empty list would validate vacuously'
        response.result.resources.size() > 2

        and:
        McpSchemaValidator.legacyErrors('ListResourcesResult', response.result) == []
    }

    def "a modern resources/list result conforms to the 2026-07-28 ListResourcesResult"() {
        // The modern schema REQUIRES resultType, ttlMs and cacheScope alongside resources -- the
        // schema-side proof the cache hints and era-gated stamp are present together here too.
        when:
        def response = dispatch(
            [jsonrpc: '2.0', id: 21, method: 'resources/list', params: [:]],
            ['MCP-Protocol-Version': '2026-07-28', 'Mcp-Method': 'resources/list'])

        then: 'served, not rejected'
        mcpDriver.lastRenderArgs.status == null

        and: 'a non-trivial catalog really was rendered -- an empty list would validate vacuously'
        response.result.resources.size() > 2

        and:
        McpSchemaValidator.modernErrors('ListResourcesResult', response.result) == []
    }

    @Unroll
    def "a legacy resources/read of #label conforms to ReadResourceResult"() {
        when:
        def response = dispatch([jsonrpc: '2.0', id: 22, method: 'resources/read', params: [uri: uri]])

        then: 'a real text body was served'
        response.result.contents[0].text

        and:
        McpSchemaValidator.legacyErrors('ReadResourceResult', response.result) == []

        where:
        label             | uri
        'a guide section' | 'hubitat://guide/performance'
        'the context summary' | 'hubitat://context-summary'
        'the context JSON'    | 'hubitat://context'
    }

    def "a modern resources/read result conforms to the 2026-07-28 ReadResourceResult"() {
        // Mcp-Name mirrors params.uri on resources/read, exactly as it mirrors
        // params.name on tools/call -- required on the modern transport.
        when:
        def response = dispatch(
            [jsonrpc: '2.0', id: 23, method: 'resources/read', params: [uri: 'hubitat://guide/performance']],
            ['MCP-Protocol-Version': '2026-07-28', 'Mcp-Method': 'resources/read',
             'Mcp-Name': 'hubitat://guide/performance'])

        then:
        mcpDriver.lastRenderArgs.status == null

        and:
        McpSchemaValidator.modernErrors('ReadResourceResult', response.result) == []
    }

    def "a modern resources/read without Mcp-Name is a HeaderMismatch rejection"() {
        // resources/read carries a body field for Mcp-Name to mirror (params.uri), so a
        // missing header is a -32020 on the modern transport -- same contract as tools/call.
        when:
        def response = dispatch(
            [jsonrpc: '2.0', id: 26, method: 'resources/read', params: [uri: 'hubitat://guide/performance']],
            ['MCP-Protocol-Version': '2026-07-28', 'Mcp-Method': 'resources/read'])

        then:
        mcpDriver.lastRenderArgs.status == 400
        response.error.code == -32020

        and:
        McpSchemaValidator.modernErrors('HeaderMismatchError', response) == []
    }

    def "a modern resources/read whose Mcp-Name disagrees with params.uri is a HeaderMismatch rejection"() {
        // The mismatch branch names the mirrored field (params.uri) -- distinct from the
        // missing-header case above and from the tools/call twin (params.name).
        when:
        def response = dispatch(
            [jsonrpc: '2.0', id: 27, method: 'resources/read', params: [uri: 'hubitat://guide/performance']],
            ['MCP-Protocol-Version': '2026-07-28', 'Mcp-Method': 'resources/read',
             'Mcp-Name': 'hubitat://guide/rooms'])

        then:
        mcpDriver.lastRenderArgs.status == 400
        response.error.code == -32020
        response.error.message.contains('params.uri')

        and:
        McpSchemaValidator.modernErrors('HeaderMismatchError', response) == []
    }

    def "resources/templates/list conforms in both eras"() {
        when:
        def legacy = dispatch([jsonrpc: '2.0', id: 24, method: 'resources/templates/list', params: [:]])
        def modern = dispatch(
            [jsonrpc: '2.0', id: 25, method: 'resources/templates/list', params: [:]],
            ['MCP-Protocol-Version': '2026-07-28', 'Mcp-Method': 'resources/templates/list'])

        then:
        McpSchemaValidator.legacyErrors('ListResourceTemplatesResult', legacy.result) == []
        McpSchemaValidator.modernErrors('ListResourceTemplatesResult', modern.result) == []
    }

    // ---------------------------------------------------------------------
    // The ping / EmptyResult regression (#365)
    // ---------------------------------------------------------------------

    @Unroll
    def "a legacy ping result conforms to EmptyResult under a STRICT unknown-key parse (#era)"() {
        // THE regression this leg exists for. The published draft-07 Result carries
        // additionalProperties: {} and accepts anything, so plain conformance proves little
        // here -- but the MCP TypeScript SDK parses an empty result with
        // ResultSchema.strict(), which REJECTS unknown keys, so stamping the modern
        // resultType onto a legacy ping breaks every keepalive on a real client.
        // legacyStrictErrors reproduces that parse from the SAME vendored definitions.
        //
        // Both header shapes, because the era gate keys on the header's VALUE: a headerless
        // request is the pre-2025-06-18 client, but the clients #365 actually broke SEND
        // MCP-Protocol-Version on every POST (required since 2025-06-18) -- so the
        // header-bearing case is the deployed one and headerless alone would not cover it.
        when:
        def response = dispatch([jsonrpc: '2.0', id: 12, method: 'ping', params: [:]], headers)

        then: 'permissive conformance -- the weak half, kept so a shape break is attributed correctly'
        McpSchemaValidator.legacyErrors('EmptyResult', response.result) == []

        and: 'and the strict view a real legacy client applies'
        McpSchemaValidator.legacyStrictErrors('EmptyResult', 'Result', response.result) == []

        where:
        era                            | headers
        'headerless'                   | null
        'legacy MCP-Protocol-Version'  | ['MCP-Protocol-Version': '2025-11-25']
    }

    // ---------------------------------------------------------------------
    // Negative controls — prove the referee actually rejects
    // ---------------------------------------------------------------------

    def "the validator rejects an initialize result with a required field removed"() {
        given: 'a REAL rendered result, then one required field deleted'
        def response = dispatch([jsonrpc: '2.0', id: 13, method: 'initialize', params: [:]])
        def doctored = new LinkedHashMap(response.result as Map)
        doctored.remove('protocolVersion')

        expect: 'the same schema that passes above now reports the violation, naming the field'
        McpSchemaValidator.legacyErrors('InitializeResult', doctored)
            .any { it.contains('protocolVersion') }

        and: 'the undoctored original is still clean -- so the rejection is about the edit, not the schema'
        McpSchemaValidator.legacyErrors('InitializeResult', response.result) == []
    }

    def "the modern validator rejects a -32022 rejection with error.data.supported removed"() {
        // The legacy control above cannot speak for the modern leg: that leg reaches a different
        // $defs key, the #/$defs/ ref prefix, the 2020-12 dialect, and -- here -- an allOf whose
        // second branch nests the required data object. Doctoring the REAL rejection is what
        // proves all four resolve, so a modernErrors(...) == [] elsewhere means "conformant"
        // rather than "the modern half never validated anything".
        given: 'the real rendered rejection, then the field a client retries from deleted'
        def response = dispatch(
            [jsonrpc: '2.0', id: 15, method: 'tools/list', params: [:]],
            ['MCP-Protocol-Version': '2099-01-01'])
        def data = new LinkedHashMap(response.error.data as Map)
        data.remove('supported')
        def error = new LinkedHashMap(response.error as Map)
        error.data = data
        def doctored = new LinkedHashMap(response as Map)
        doctored.error = error

        expect: 'the same schema that passes above now reports the violation, naming the field'
        McpSchemaValidator.modernErrors('UnsupportedProtocolVersionError', doctored)
            .any { it.contains('supported') }

        and: 'the undoctored original is still clean -- so the rejection is about the edit, not the schema'
        McpSchemaValidator.modernErrors('UnsupportedProtocolVersionError', response) == []
    }

    def "the strict EmptyResult view rejects a resultType stamped onto a legacy ping"() {
        given: 'the real legacy ping result, with the modern stamp added as the regression would'
        def response = dispatch([jsonrpc: '2.0', id: 14, method: 'ping', params: [:]])
        def regressed = new LinkedHashMap(response.result as Map)
        regressed.resultType = 'complete'

        expect: 'the permissive schema shrugs -- which is exactly why the strict view exists'
        McpSchemaValidator.legacyErrors('EmptyResult', regressed) == []

        and: 'the strict view catches it, naming the offending key'
        McpSchemaValidator.legacyStrictErrors('EmptyResult', 'Result', regressed)
            .any { it.contains('resultType') }
    }
}
