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
        // Issue #290: by default (publishOutputSchemas OFF) the catalog disclosure does
        // NOT forward outputSchema, so strict clients (e.g. Claude Desktop) work.
        result.tools.every { !it.containsKey('outputSchema') }
    }

    def "catalog mode forwards outputSchema for each tool when publishOutputSchemas is on"() {
        given:
        settingsMap.publishOutputSchemas = true

        when:
        def result = script.handleGateway('hub_manage_rooms', null, null)

        then:
        // Non-vacuity guard: the catalog actually lists the room tools, so the every{}
        // below cannot pass on an empty list.
        result.tools*.name == ['hub_list_rooms', 'hub_get_room', 'hub_create_room', 'hub_delete_room', 'hub_update_room']
        // With the opt-in toggle ON, the catalog disclosure forwards each tool's
        // outputSchema (the flat tools/list path still strips it for size).
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

    // ---- content-fingerprint self-heal of the required-param memo ----
    // A code-update deploy (HPM update, hub_update_app, the e2e HPM-repair install)
    // recompiles the class but does NOT fire updated() (the memo's only other
    // invalidation) AND rides the SAME currentVersion() (PRs don't bump version), so
    // neither updated() nor a version stamp catches a same-version required-array change.
    // The memo is keyed on requiredParamsCatalogFingerprint() -- a content signature of
    // the live name->required shape -- so a memo whose fingerprint != the live catalog's
    // is rebuilt from the live definitions. The discriminator is that fingerprint check:
    // reverting it makes the stale memo authoritative, so the load-bearing spec below
    // rejects on the stale `legacy_param` (RED) instead of the live `room` (GREEN). The
    // pre-check rejects before any tool impl runs, so the spec needs no hub-method stubs.

    def "a same-version stale memo is rebuilt from live definitions (live required honored, not the stale entry)"() {
        given: 'an old build memoized a different required list for hub_get_room; version is UNCHANGED but the catalog fingerprint no longer matches'
        // hub_get_room requires ['room'] in the live (current) catalog. The seeded memo
        // simulates an older same-version build that required `legacy_param` instead. The
        // stamped fingerprint is a stale string that cannot match the live fingerprint --
        // the exact same-version code-deploy scenario the e2e caught (a relaxed required
        // array served stale because currentVersion() did not change).
        atomicStateMap.requiredParamsByTool = ['hub_get_room': ['legacy_param']]
        atomicStateMap.requiredParamsByToolFingerprint = 'stale-fingerprint-from-an-older-same-version-build'

        when: 'a gateway call omits everything'
        def result = script.handleGateway('hub_manage_rooms', 'hub_get_room', [:])

        then: 'the fingerprint mismatch forces a rebuild -- rejection names the LIVE required param, never the stale one'
        result.isError == true
        result.tool == 'hub_get_room'
        result.error.contains('room')
        !result.error.contains('legacy_param')

        and: 'the memo + fingerprint were healed to the live catalog (live required restored)'
        atomicStateMap.requiredParamsByToolFingerprint == script.requiredParamsCatalogFingerprint()
        atomicStateMap.requiredParamsByTool['hub_get_room'] == ['room']
    }

    def "a relaxed-required tool with a stale fingerprint is NOT rejected for the now-optional params (the e2e failure, abstracted)"() {
        given: 'a stale memo lists params as required that the live build relaxed away; fingerprint is stale (same-version deploy)'
        // Directly models the failing e2e: a tool whose required array was relaxed (here
        // hub_get_room, whose live required is only ['room']) must not be rejected for a
        // param the OLD build still listed. With the fix, supplying the live-required
        // `room` passes the pre-check and dispatch reaches the impl; reverting the
        // fingerprint guard would reject on the stale `extra_param` before dispatch even
        // though `room` was supplied. Stub getRooms() to return a matching room so the
        // impl resolves cleanly to a real result (bucket-1 per-test metaClass stub;
        // setup() wipes it next test).
        script.metaClass.getRooms = { [[id: 7, name: 'Kitchen', deviceIds: []]] }
        atomicStateMap.requiredParamsByTool = ['hub_get_room': ['room', 'extra_param']]
        atomicStateMap.requiredParamsByToolFingerprint = 'stale-fingerprint'

        when: 'supply only the live-required param, omitting the stale extra'
        def result = script.handleGateway('hub_manage_rooms', 'hub_get_room', [room: 'Kitchen'])

        then: 'the pre-check passes (no missing-param rejection) -- dispatch reached the impl and returned the room'
        !(result instanceof Map && result.isError == true && result.error?.toString()?.startsWith('Missing required'))
        result.name == 'Kitchen'

        and: 'the memo healed to the live shape (the stale extra param is gone)'
        atomicStateMap.requiredParamsByTool['hub_get_room'] == ['room']
    }

    def "a memo whose fingerprint matches the live catalog is served as-is (no rebuild)"() {
        given: 'a memo carrying a deliberately wrong required list but stamped with the LIVE fingerprint'
        // Proves the fingerprint is the gate: a matching fingerprint trusts the cached
        // value verbatim (the build-once/read-many fast path the memo exists for).
        atomicStateMap.requiredParamsByTool = ['hub_get_room': ['sentinel_param']]
        atomicStateMap.requiredParamsByToolFingerprint = script.requiredParamsCatalogFingerprint()

        when: 'omit the sentinel param the cached (matching-fingerprint) memo lists as required'
        def result = script.handleGateway('hub_manage_rooms', 'hub_get_room', [:])

        then: 'the matching-fingerprint cache is honored verbatim -- the sentinel rejection fires (no rebuild)'
        result.isError == true
        result.tool == 'hub_get_room'
        result.error.contains('sentinel_param')
        atomicStateMap.requiredParamsByTool['hub_get_room'] == ['sentinel_param']
    }
}
