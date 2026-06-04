package server

import support.ToolSpecBase

/**
 * Spec for hubitat-mcp-server.groovy::handleGateway.
 *
 * Covers catalog mode (no toolName), unknown-gateway and unknown-tool
 * errors, the effective rejection of gateway-as-tool, missing-required
 * soft error (Option D: isError response, not an exception), valid
 * dispatch delegating to executeTool, and the defensive JSON-string parse
 * for inner {@code args} (some MCP clients, e.g. Sonnet subagents, serialize
 * {@code args} as a JSON-encoded string rather than a Map object).
 */
class HandleGatewaySpec extends ToolSpecBase {

    def "catalog mode returns gateway's tool schemas when no toolName is supplied"() {
        when:
        def result = script.handleGateway('hub_manage_rooms', null, null)

        then:
        result.gateway == 'hub_manage_rooms'
        result.mode == 'catalog'
        result.tools instanceof List
        result.tools*.name == ['hub_list_rooms', 'hub_get_room', 'hub_create_room', 'hub_delete_room', 'hub_update_room']
        result.tools.every { it.description && it.inputSchema }
        // PR1C: the catalog disclosure forwards outputSchema when the tool declares one
        // (the flat tools/list path strips it for size; the gateway catalog keeps it).
        result.tools.every { it.outputSchema instanceof Map && it.outputSchema.type == 'object' }
    }

    def "throws IllegalArgumentException for unknown gateway"() {
        when:
        script.handleGateway('manage_fake_gateway', 'any_tool', [:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message == 'Unknown gateway: manage_fake_gateway'
    }

    def "throws IllegalArgumentException for a tool that is not in the called gateway"() {
        when:
        // hub_list_files belongs to hub_manage_files, not hub_manage_rooms
        script.handleGateway('hub_manage_rooms', 'hub_list_files', [:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.startsWith("Unknown tool 'hub_list_files' in hub_manage_rooms")
        ex.message.contains('Available:')
        ex.message.contains('hub_list_rooms')
    }

    def "using a gateway name as a tool fails"() {
        // handleGateway's defensive recursive-call guard
        // ("Cannot call a gateway from within a gateway") is unreachable
        // given current configs — gateway names and tool names are
        // disjoint namespaces, so the unknown-tool check fires first.
        // This test pins the effective behaviour: attempting to invoke a
        // gateway by name as a tool is rejected.
        when:
        script.handleGateway('hub_manage_rooms', 'hub_manage_rooms', [:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.startsWith("Unknown tool 'hub_manage_rooms' in hub_manage_rooms")
        ex.message.contains('Available:')
    }

    def "missing required parameters returns isError response (does NOT throw)"() {
        when:
        // hub_get_room requires `room`; omit it.
        def result = script.handleGateway('hub_manage_rooms', 'hub_get_room', [:])

        then: 'Option D behaviour: soft error response, not an exception'
        notThrown(IllegalArgumentException)
        result.isError == true
        result.tool == 'hub_get_room'
        // Pin the singular form (W-missingRequired-singular): a regression that drops
        // the count-aware ternary at L1337 would emit "parameters" for 1 missing arg.
        result.error.contains('Missing required parameter:')
        !result.error.contains('Missing required parameters:')
        result.error.contains('room')
        result.parameters.contains('room')
    }

    // Both-ways pending (orchestrator).
    def "missing two required parameters reports 'parameters' plural (count-aware)"() {
        when:
        // hub_create_room requires both `name` and `confirm`; omit both.
        def result = script.handleGateway('hub_manage_rooms', 'hub_create_room', [:])

        then: 'plural form fired by the count-aware ternary at L1337'
        notThrown(IllegalArgumentException)
        result.isError == true
        result.tool == 'hub_create_room'
        result.error.contains('Missing required parameters:')
        !result.error.contains('Missing required parameter:')
        result.error.contains('name')
        result.error.contains('confirm')
    }

    def "valid dispatch delegates to executeTool"() {
        given: 'hub_list_rooms calls getRooms() on the Hubitat SDK — stub it on the script'
        script.metaClass.getRooms = { ->
            [
                [id: 1, name: 'Kitchen', deviceIds: []],
                [id: 2, name: 'Living Room', deviceIds: []]
            ]
        }

        when:
        def result = script.handleGateway('hub_manage_rooms', 'hub_list_rooms', [:])

        then: 'the sub-tool ran and its return shape came through'
        !result.isError
        result.rooms instanceof List
        result.rooms.size() == 2
        result.rooms*.name.containsAll(['Kitchen', 'Living Room'])
        result.count == 2
    }

    // ---- Defensive JSON-string parse for inner args ----
    // Some MCP clients (Sonnet subagents in particular) serialize the inner
    // `args` value as a JSON-encoded string rather than a Map object. Without
    // the defensive parse, any Map operation on that String (containsKey,
    // property access) throws MissingMethodException / MissingPropertyException
    // deep in the dispatch chain, producing an opaque Groovy stack trace.

    def "JSON-encoded string args containing a valid object is parsed and dispatch proceeds"() {
        given: 'hub_list_rooms needs getRooms() stubbed'
        script.metaClass.getRooms = { ->
            [[id: 1, name: 'Kitchen', deviceIds: []]]
        }

        when: 'args is a JSON string encoding an empty object -- simulates Sonnet subagent serialization'
        def result = script.handleGateway('hub_manage_rooms', 'hub_list_rooms', '{}')

        then: 'the string was transparently parsed; dispatch ran; result is the normal tool response'
        !result.isError
        result.rooms instanceof List
        result.count == 1
    }

    def "JSON-encoded string args containing fields is parsed and fields are accessible to the tool"() {
        given: 'hub_get_room calls getRooms() -- stub it with a known room'
        script.metaClass.getRooms = { ->
            [[id: 42, name: 'Office', deviceIds: []]]
        }

        when: 'args is a JSON string encoding {"room":"Office"}'
        def result = script.handleGateway('hub_manage_rooms', 'hub_get_room', '{"room":"Office"}')

        then: 'string was parsed; the room parameter reached the tool; correct room returned'
        !result.isError
        result.name == 'Office'
    }

    def "JSON-encoded string args containing invalid JSON throws IllegalArgumentException"() {
        when: 'args is a malformed JSON string'
        script.handleGateway('hub_manage_rooms', 'hub_list_rooms', 'not valid json')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("Gateway arg 'args' was a String but not valid JSON")
        ex.message.contains('Parse error:')
    }

    def "JSON-encoded string args containing a JSON array rejects with IllegalArgumentException"() {
        // A JSON array is valid JSON but not a valid args object (must be a JSON object).
        // The type check after parsing rejects it with a clear error rather than letting
        // a List propagate into tool implementations as a Map.
        when:
        script.handleGateway('hub_manage_rooms', 'hub_list_rooms', '[1,2,3]')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("Gateway arg 'args' was a String that parsed to a JSON Array")
        ex.message.contains("not a JSON object")
    }

    def "Map args (existing happy path) is unaffected by the defensive parse"() {
        given:
        script.metaClass.getRooms = { ->
            [[id: 10, name: 'Garage', deviceIds: []]]
        }

        when: 'args is a plain Map -- the normal case'
        def result = script.handleGateway('hub_manage_rooms', 'hub_list_rooms', [:])

        then: 'no change in behaviour -- Map args passes through as before'
        !result.isError
        result.rooms instanceof List
        result.count == 1
    }

    def "null args (no-arg tool) is unaffected by the defensive parse"() {
        given:
        script.metaClass.getRooms = { ->
            [[id: 5, name: 'Bedroom', deviceIds: []]]
        }

        when: 'args is null -- also a normal case for parameter-less tools'
        def result = script.handleGateway('hub_manage_rooms', 'hub_list_rooms', null)

        then: 'safeArgs defaults to [:] and dispatch proceeds normally'
        !result.isError
        result.rooms instanceof List
        result.count == 1
    }

    // ---- pr2c-cat-1: cached required-param memo on the gateway dispatch path ----
    // handleGateway no longer rebuilds the full ~111-tool catalog for the required-param
    // pre-check; it reads from the memoized requiredParamsByTool() map cached in atomicState.

    def "memo is populated in atomicState after the first gateway call; a repeat call returns the identical missing-param error"() {
        given: 'a clean atomicState so the memo builds fresh on the first call'
        atomicStateMap.remove('requiredParamsByTool')

        when: 'first call with a missing required param (hub_get_room requires room)'
        def first = script.handleGateway('hub_manage_rooms', 'hub_get_room', [:])

        then: 'soft missing-param error AND the memo is now cached in atomicState'
        first.isError == true
        first.tool == 'hub_get_room'
        first.error.contains('Missing required parameter:')
        first.error.contains('room')
        atomicStateMap.requiredParamsByTool instanceof Map
        atomicStateMap.requiredParamsByTool['hub_get_room'] == ['room']
        // omission contract: a no-required-param tool is absent from the map
        !atomicStateMap.requiredParamsByTool.containsKey('hub_list_rooms')

        when: 'a second identical call now served from the cached memo'
        def second = script.handleGateway('hub_manage_rooms', 'hub_get_room', [:])

        then: 'byte-identical error response -- the memo read is behavior-preserving'
        second == first
    }

    def "the missing-param hint stays intact (no FLAT_TRIM leak) even after a flat-mode tools/list strip"() {
        given: 'flat mode drives the in-place [[FLAT_TRIM]] strip path; clean memo'
        settingsMap.useGateways = false
        atomicStateMap.remove('requiredParamsByTool')

        when: 'run the flat strip path first (mutates fresh def copies in place), then a gateway missing-param call'
        script.getToolDefinitions()
        def result = script.handleGateway('hub_manage_rooms', 'hub_get_room', [:])

        then: 'the missing-param hint is fully intact -- no marker leakage'
        result.isError == true
        result.error.contains('room')
        result.parameters.contains('room')
        !result.parameters.contains('FLAT_TRIM')
    }

    def "two-missing-required path still rebuilds the full catalog for the hint and caches the two-element required list"() {
        given:
        atomicStateMap.remove('requiredParamsByTool')

        when: 'hub_create_room requires both name and confirm; omit both'
        def result = script.handleGateway('hub_manage_rooms', 'hub_create_room', [:])

        then: 'plural form + both names; memo cached the two-element required list (order-independent)'
        result.isError == true
        result.tool == 'hub_create_room'
        result.error.contains('Missing required parameters:')
        result.error.contains('name')
        result.error.contains('confirm')
        (atomicStateMap.requiredParamsByTool['hub_create_room'] as Set) == (['name', 'confirm'] as Set)
    }
}
