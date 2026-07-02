package server

import support.ToolSpecBase

/**
 * Spec for hubitat-mcp-server.groovy::handleGateway.
 *
 * Covers catalog mode (no toolName), unknown-gateway and unknown-tool
 * errors, the effective rejection of gateway-as-tool, the Option D
 * required-param pre-check (throws -> -32602 with the full param list, the
 * SAME shape flat dispatch gives -- issue #319), valid dispatch delegating
 * to executeTool, and the defensive JSON-string parse for inner {@code args}
 * (some MCP clients, e.g. Sonnet subagents, serialize {@code args} as a
 * JSON-encoded string rather than a Map object).
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

    def "missing required param throws -32602 with the full param list (same shape as flat; #319)"() {
        when:
        // hub_get_room requires `room`; omit it. The pre-check throws (not a soft return)
        // so a missing-arg error has the SAME shape gateway and flat (both -> -32602).
        script.handleGateway('hub_manage_rooms', 'hub_get_room', [:])

        then: 'thrown IllegalArgumentException -> handleToolsCall maps it to -32602'
        def e = thrown(IllegalArgumentException)
        // Singular form (W-missingRequired-singular): a regression dropping the count-aware
        // ternary would emit "parameters" for 1 missing arg.
        e.message.contains('Missing required parameter for hub_get_room:')
        !e.message.contains('Missing required parameters')
        e.message.contains('room')
        // The full param list rides in the message (no content lost vs the old field).
        e.message.contains('All parameters:')
    }

    def "missing-param error is the SAME SHAPE gateway and flat (both -32602; #319)"() {
        // The user-facing #319 fix: the same mistake yields the same error CATEGORY both
        // ways. flat's handler validation throws (-> -32602); the gateway pre-check now
        // throws too (was a soft isError envelope). The gateway TEXT is additionally
        // richer (lists every param), never poorer -- no information is dropped.
        when: 'flat: executeTool by leaf name -> the handler throws its own message'
        def flatEx = null
        try { script.executeTool('hub_get_room', [:]) } catch (Exception ex) { flatEx = ex }

        and: 'gateway: handleGateway to the same sub-tool -> the pre-check throws (all params)'
        def gwEx = null
        try { script.handleGateway('hub_manage_rooms', 'hub_get_room', [:]) } catch (Exception ex) { gwEx = ex }

        then: 'both throw IllegalArgumentException (same JSON-RPC -32602 category)'
        flatEx instanceof IllegalArgumentException
        gwEx instanceof IllegalArgumentException

        and: 'the gateway still names the missing param -- no information lost, plus the full list'
        gwEx.message.contains('room')
        gwEx.message.contains('All parameters:')
    }

    def "hub_set_native_app schema-only meta-call bypasses the required-param pre-check (mirrors hub_set_rule)"() {
        given:
        script.metaClass.toolGetToolGuide = { s -> [section: s, stubbed: true] }

        when: "guide meta-call routed through the gateway WITHOUT confirm (its only required param)"
        def result = script.handleGateway('hub_manage_native_rules_and_apps', 'hub_set_native_app', [appId: 123, guide: true])

        then: 'the pre-check does not reject for missing confirm; the guide short-circuit answers'
        notThrown(IllegalArgumentException)
        result.stubbed == true
    }

    def "missing-param pre-check falls through to the canonical refusal for a master-hidden sub-tool"() {
        given: 'Write master OFF hides hub_delete_room'
        settingsMap.enableWrite = false

        when: 'missing-params call to the hidden write sub-tool through its gateway'
        script.handleGateway('hub_manage_rooms', 'hub_delete_room', [:])

        then: "executeTool's canonical refusal fires instead of the parameter-schema dump"
        def e = thrown(IllegalArgumentException)
        e.message.contains('Write tools are disabled')
    }

    def "missing-param pre-check falls through to the canonical refusal for a #114-disabled sub-tool"() {
        given:
        settingsMap.disabled_tools = ['hub_delete_room']

        when:
        script.handleGateway('hub_manage_rooms', 'hub_delete_room', [:])

        then: 'the Advanced-overrides refusal, not a schema dump implying the tool is callable'
        def e = thrown(IllegalArgumentException)
        e.message.contains('disabled in Advanced settings')
    }

    def "missing-param pre-check falls through to the canonical refusal for a custom-engine-hidden sub-tool"() {
        // The third hiding source getHiddenToolNames() folds in (readonly mode hides the
        // write-side custom_rule tools). The fourth, dev-mode-only, has no gateway member
        // today (hub_update_package is top-level), so it has no gateway surface to pin.
        given: 'engine OFF + Read master ON = readonly mode; hub_delete_custom_rule is hidden'
        settingsMap.enableCustomRuleEngine = false

        when:
        script.handleGateway('hub_manage_custom_rules', 'hub_delete_custom_rule', [:])

        then: "the custom-engine refusal, not hub_delete_custom_rule's parameter schema"
        def e = thrown(IllegalArgumentException)
        e.message.contains('not available in read-only mode')
    }

    def "missing two required parameters reports 'parameters' plural (count-aware)"() {
        when:
        // hub_create_room requires both `name` and `confirm`; omit both.
        script.handleGateway('hub_manage_rooms', 'hub_create_room', [:])

        then: 'plural form fired by the count-aware ternary'
        def e = thrown(IllegalArgumentException)
        e.message.contains('Missing required parameters for hub_create_room:')
        !e.message.contains('Missing required parameter for')
        e.message.contains('name')
        e.message.contains('confirm')
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

    def "memo is populated in atomicState after the first gateway call; a repeat call throws the identical missing-param error"() {
        given: 'a clean atomicState so the memo builds fresh on the first call'
        atomicStateMap.remove('requiredParamsByTool')

        when: 'first call with a missing required param (hub_get_room requires room)'
        def firstEx = null
        try { script.handleGateway('hub_manage_rooms', 'hub_get_room', [:]) } catch (Exception e) { firstEx = e }

        then: 'thrown missing-param error AND the memo is now cached in atomicState'
        firstEx instanceof IllegalArgumentException
        firstEx.message.contains('Missing required parameter for hub_get_room:')
        firstEx.message.contains('room')
        atomicStateMap.requiredParamsByTool instanceof Map
        atomicStateMap.requiredParamsByTool['hub_get_room'] == ['room']
        // omission contract: a no-required-param tool is absent from the map
        !atomicStateMap.requiredParamsByTool.containsKey('hub_list_rooms')

        when: 'a second identical call now served from the cached memo'
        def secondEx = null
        try { script.handleGateway('hub_manage_rooms', 'hub_get_room', [:]) } catch (Exception e) { secondEx = e }

        then: 'identical error message -- the memo read is behavior-preserving'
        secondEx.message == firstEx.message
    }

    def "the missing-param message stays intact (no FLAT_TRIM leak) even after a flat-mode tools/list strip"() {
        given: 'flat mode drives the in-place [[FLAT_TRIM]] strip path; clean memo'
        settingsMap.useGateways = false
        atomicStateMap.remove('requiredParamsByTool')

        when: 'run the flat strip path first (mutates fresh def copies in place), then a gateway missing-param call'
        script.getToolDefinitions()
        script.handleGateway('hub_manage_rooms', 'hub_get_room', [:])

        then: 'the thrown param list is fully intact -- no marker leakage'
        def e = thrown(IllegalArgumentException)
        e.message.contains('room')
        !e.message.contains('FLAT_TRIM')
    }

    def "two-missing-required path rebuilds the full catalog for the hint and caches the two-element required list"() {
        given:
        atomicStateMap.remove('requiredParamsByTool')

        when: 'hub_create_room requires both name and confirm; omit both'
        Exception ex = null
        try { script.handleGateway('hub_manage_rooms', 'hub_create_room', [:]) } catch (Exception e) { ex = e }

        then: 'plural form + both names; memo cached the two-element required list (order-independent)'
        ex instanceof IllegalArgumentException
        ex.message.contains('Missing required parameters for hub_create_room:')
        ex.message.contains('name')
        ex.message.contains('confirm')
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
        Exception ex = null
        try { script.handleGateway('hub_manage_rooms', 'hub_get_room', [:]) } catch (Exception e) { ex = e }

        then: 'the fingerprint mismatch forces a rebuild -- rejection names the LIVE required param, never the stale one'
        ex instanceof IllegalArgumentException
        ex.message.contains('room')
        !ex.message.contains('legacy_param')

        and: 'the memo + fingerprint were healed to the live catalog (live required restored)'
        atomicStateMap.requiredParamsByToolFingerprint == script.requiredParamsCatalogFingerprint(script.getAllToolDefinitions())
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

        then: 'the pre-check passes (no missing-param throw) -- dispatch reached the impl and returned the room'
        notThrown(IllegalArgumentException)
        result.name == 'Kitchen'

        and: 'the memo healed to the live shape (the stale extra param is gone)'
        atomicStateMap.requiredParamsByTool['hub_get_room'] == ['room']
    }

    def "the fingerprint discriminates: two catalogs with different required shapes produce different fingerprints"() {
        given: 'two catalogs identical except for one tool required array -- the only property the self-heal rests on'
        // If requiredParamsCatalogFingerprint() returned a constant, the three tests
        // above would all still pass (each seeds a deliberately-mismatched literal). This
        // proves the fingerprint is actually a function of the required shape, so a
        // same-version required-array change yields a fresh key and forces a rebuild.
        def catalogA = [[name: 'hub_get_room', inputSchema: [required: ['room']]]]
        def catalogB = [[name: 'hub_get_room', inputSchema: [required: ['room', 'extra_param']]]]

        when:
        def fpA = script.requiredParamsCatalogFingerprint(catalogA)
        def fpB = script.requiredParamsCatalogFingerprint(catalogB)

        then: 'a changed required array yields a different fingerprint (a constant return would make these equal)'
        fpA != fpB

        and: 'the fingerprint is stable for an unchanged shape'
        fpA == script.requiredParamsCatalogFingerprint([[name: 'hub_get_room', inputSchema: [required: ['room']]]])
    }

    def "a memo whose fingerprint matches the live catalog is served as-is (no rebuild)"() {
        given: 'a memo carrying a deliberately wrong required list but stamped with the LIVE fingerprint'
        // Proves the fingerprint is the gate: a matching fingerprint trusts the cached
        // value verbatim (the build-once/read-many fast path the memo exists for).
        atomicStateMap.requiredParamsByTool = ['hub_get_room': ['sentinel_param']]
        atomicStateMap.requiredParamsByToolFingerprint = script.requiredParamsCatalogFingerprint(script.getAllToolDefinitions())

        when: 'omit the sentinel param the cached (matching-fingerprint) memo lists as required'
        Exception ex = null
        try { script.handleGateway('hub_manage_rooms', 'hub_get_room', [:]) } catch (Exception e) { ex = e }

        then: 'the matching-fingerprint cache is honored verbatim -- the sentinel rejection fires (no rebuild)'
        ex instanceof IllegalArgumentException
        ex.message.contains('sentinel_param')
        atomicStateMap.requiredParamsByTool['hub_get_room'] == ['sentinel_param']
    }
}
