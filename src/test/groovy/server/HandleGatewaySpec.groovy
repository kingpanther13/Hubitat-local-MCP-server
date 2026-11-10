package server

import support.ToolSpecBase

/**
 * Spec for hubitat-mcp-server.groovy::handleGateway.
 *
 * Covers catalog mode (no toolName), unknown-gateway and unknown-tool
 * errors, the effective rejection of gateway-as-tool, the Option D
 * required-param pre-check (throws -> isError validation result with the full param list, the
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
        result.tools.every { !it.containsKey('outputSchema') }
    }

    def "a saved publication toggle cannot restore output schemas in any gateway catalog"() {
        given:
        settingsMap.publishOutputSchemas = true
        settingsMap.enableCustomRuleEngine = true
        settingsMap.enableDeveloperMode = true

        when:
        def catalogs = script.getGatewayConfig().keySet().collect {
            script.handleGateway(it, null, null)
        }

        then:
        !catalogs.isEmpty()
        catalogs.every { it.tools && it.tools.every { tool ->
            tool.inputSchema && !tool.containsKey('outputSchema')
        } }
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

    def "missing required param throws isError validation result with the full param list (same shape as flat; #319)"() {
        when:
        // hub_get_room requires `room`; omit it. The pre-check throws (not a soft return)
        // so a missing-arg error has the SAME shape gateway and flat (both -> isError validation result).
        script.handleGateway('hub_manage_rooms', 'hub_get_room', [:])

        then: 'thrown IllegalArgumentException -> handleToolsCall maps it to isError validation result'
        def e = thrown(IllegalArgumentException)
        // Singular form (W-missingRequired-singular): a regression dropping the count-aware
        // ternary would emit "parameters" for 1 missing arg.
        e.message.contains('Missing required parameter for hub_get_room:')
        !e.message.contains('Missing required parameters')
        e.message.contains('room')
        // The full param list rides in the message (no content lost vs the old field).
        e.message.contains('All parameters:')
    }

    def "missing-param error is the SAME SHAPE gateway and flat (both isError validation result; #319)"() {
        // The user-facing #319 fix: the same mistake yields the same error CATEGORY both
        // ways. flat's handler validation throws (-> isError validation result); the gateway pre-check now
        // throws too (was a soft isError envelope). The gateway TEXT is additionally
        // richer (lists every param), never poorer -- no information is dropped.
        when: 'flat: executeTool by leaf name -> the handler throws its own message'
        def flatEx = null
        try { script.executeTool('hub_get_room', [:]) } catch (Exception ex) { flatEx = ex }

        and: 'gateway: handleGateway to the same sub-tool -> the pre-check throws (all params)'
        def gwEx = null
        try { script.handleGateway('hub_manage_rooms', 'hub_get_room', [:]) } catch (Exception ex) { gwEx = ex }

        then: 'both throw IllegalArgumentException (same JSON-RPC isError validation result category)'
        flatEx instanceof IllegalArgumentException
        gwEx instanceof IllegalArgumentException

        and: 'the gateway still names the missing param -- no information lost, plus the full list'
        gwEx.message.contains('room')
        gwEx.message.contains('All parameters:')
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

    def "hub_get_device dispatch forwards mode sections fields and cursor to the reader"() {
        given:
        def captured = [:]
        script.metaClass.toolGetDevice = { deviceId, mode = 'summary', sections = null, fields = null, cursor = null ->
            captured.put('deviceId', deviceId)
            captured.put('mode', mode)
            captured.put('sections', sections)
            captured.put('fields', fields)
            captured.put('cursor', cursor)
            [success: true]
        }

        when:
        def result = script.executeTool('hub_get_device', [
            deviceId: '42', mode: 'configuration', sections: ['preferences', 'driver'],
            fields: ['probeBool', 'maxEvents'], cursor: 'preference:probeBool:500'
        ])

        then:
        result.success == true
        captured == [deviceId: '42', mode: 'configuration', sections: ['preferences', 'driver'],
                     fields: ['probeBool', 'maxEvents'], cursor: 'preference:probeBool:500']
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

    // The class cache must preserve required-parameter validation without durable catalog metadata.

    def "memo stays outside persisted state after the first gateway call; a repeat call throws the identical missing-param error"() {
        when: 'first call with a missing required param (hub_get_room requires room)'
        def firstEx = null
        try { script.handleGateway('hub_manage_rooms', 'hub_get_room', [:]) } catch (Exception e) { firstEx = e }

        then: 'thrown missing-param error AND the memo remains outside persisted state'
        firstEx instanceof IllegalArgumentException
        firstEx.message.contains('Missing required parameter for hub_get_room:')
        firstEx.message.contains('room')
        script.requiredParamsByTool() instanceof Map
        !atomicStateMap.containsKey('requiredParamsByTool')
        script.requiredParamsByTool()['hub_get_room'] == ['room']
        // omission contract: a no-required-param tool is absent from the map
        !script.requiredParamsByTool().containsKey('hub_list_rooms')

        when: 'a second identical call now served from the cached memo'
        def secondEx = null
        try { script.handleGateway('hub_manage_rooms', 'hub_get_room', [:]) } catch (Exception e) { secondEx = e }

        then: 'identical error message -- the memo read is behavior-preserving'
        secondEx.message == firstEx.message
    }

    def "the missing-param message stays intact (no FLAT_TRIM leak) even after a flat-mode tools/list strip"() {
        given: 'flat mode drives the in-place [[FLAT_TRIM]] strip path'
        settingsMap.useGateways = false

        when: 'run the flat strip path first (mutates fresh def copies in place), then a gateway missing-param call'
        script.getToolDefinitions()
        script.handleGateway('hub_manage_rooms', 'hub_get_room', [:])

        then: 'the thrown param list is fully intact -- no marker leakage'
        def e = thrown(IllegalArgumentException)
        e.message.contains('room')
        !e.message.contains('FLAT_TRIM')
    }

    def "two-missing-required path rebuilds the full catalog for the hint and caches the two-element required list"() {
        when: 'hub_create_room requires both name and confirm; omit both'
        Exception ex = null
        try { script.handleGateway('hub_manage_rooms', 'hub_create_room', [:]) } catch (Exception e) { ex = e }

        then: 'plural form + both names; memo cached the two-element required list (order-independent)'
        ex instanceof IllegalArgumentException
        ex.message.contains('Missing required parameters for hub_create_room:')
        ex.message.contains('name')
        ex.message.contains('confirm')
        (script.requiredParamsByTool()['hub_create_room'] as Set) == (['name', 'confirm'] as Set)
    }

    // Retired persisted indexes must never override the current compiled tool definitions.

    def "compiled required parameters supersede a same-version persisted memo"() {
        given: 'an old build persisted a different required list for hub_get_room'
        // Retired metadata is ignored even when currentVersion() has not changed.
        atomicStateMap.requiredParamsByTool = ['hub_get_room': ['legacy_param']]
        atomicStateMap.requiredParamsByToolFingerprint = 'stale-fingerprint-from-an-older-same-version-build'

        when: 'a gateway call omits everything'
        Exception ex = null
        try { script.handleGateway('hub_manage_rooms', 'hub_get_room', [:]) } catch (Exception e) { ex = e }

        then: 'the compiled catalog supersedes the persisted memo -- rejection names the LIVE required param, never the stale one'
        ex instanceof IllegalArgumentException
        ex.message.contains('room')
        !ex.message.contains('legacy_param')

        and: 'the live required parameters are used'
        script.requiredParamsByTool()['hub_get_room'] == ['room']
    }

    def "retired persisted requirements cannot reject a now-optional parameter"() {
        given: 'a stale memo lists an extra requirement removed from the compiled catalog'
        // Supplying the current requirements must reach dispatch without extra_param.
        script.metaClass.getRooms = { [[id: 7, name: 'Kitchen', deviceIds: []]] }
        atomicStateMap.requiredParamsByTool = ['hub_get_room': ['room', 'extra_param']]
        atomicStateMap.requiredParamsByToolFingerprint = 'stale-fingerprint'

        when: 'supply only the live-required param, omitting the stale extra'
        def result = script.handleGateway('hub_manage_rooms', 'hub_get_room', [room: 'Kitchen'])

        then: 'the pre-check passes (no missing-param throw) -- dispatch reached the impl and returned the room'
        notThrown(IllegalArgumentException)
        result.name == 'Kitchen'

        and: 'the in-memory memo has the live shape'
        script.requiredParamsByTool()['hub_get_room'] == ['room']
    }

    def "retired persisted metadata is ignored even if it claims a matching fingerprint"() {
        given:
        atomicStateMap.requiredParamsByTool = ['hub_get_room': ['sentinel_param']]
        atomicStateMap.requiredParamsByToolFingerprint = 'claimed-current'

        when:
        script.handleGateway('hub_manage_rooms', 'hub_get_room', [:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('room')
        !ex.message.contains('sentinel_param')
    }

    // -------- strict arguments: every tool's schema properties, enforced centrally in executeTool --------

    def "the catalog index records every tool's property names, except the frozen custom-rule tools"() {
        when:
        Map indexes = script._toolCatalogIndexes()
        Map argNames = indexes.argNames
        def frozen = { String n -> n.endsWith('_custom_rule') || n in ['hub_list_captured_states', 'hub_delete_captured_state'] }

        then:
        argNames.hub_update_library.containsAll(['libraryId', 'source', 'expectedVersion', 'confirm'])
        argNames.hub_list_modes == []
        argNames.keySet() == script.getAllToolDefinitions()*.name.findAll { !frozen(it) } as Set

        and: "the bulk entry keys come from the schema's updates.items.properties and installs.items.properties"
        indexes.updateEntryArgs.hub_update_driver == ['driverId', 'sourceFile', 'source', 'importUrl', 'resave', 'backup', 'expectedVersion']
        indexes.installEntryArgs.hub_create_item == ['sourceFile', 'source', 'importUrl']
    }

    def "_rejectUnknownArgs names the unknown keys, lists the valid ones, and exempts server keys and bestPracticeKey"() {
        when:
        script._rejectUnknownArgs('hub_update_library', [libraryId: '42', source: 's', zeta: 1, alpha: 2, confirm: true])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.startsWith('Unknown hub_update_library arguments: alpha, zeta. Nothing was changed. Valid arguments: libraryId, ')
        e.message.contains('expectedVersion')

        when: 'the budget clock, any double-underscore key and the guide key ride along'
        script._rejectUnknownArgs('hub_update_library', [libraryId: '42', __reqT0: 1L, __future: true, bestPracticeKey: 'k'])

        then:
        notThrown(IllegalArgumentException)

        when: 'a tool without additionalProperties:false is checked the same way'
        script._rejectUnknownArgs('hub_get_room', [room: '1', whatever: true])

        then:
        def lenient = thrown(IllegalArgumentException)
        lenient.message.contains('Unknown hub_get_room argument: whatever')

        when: 'a tool with no properties says so'
        script._rejectUnknownArgs('hub_list_modes', [whatever: true])

        then:
        def none = thrown(IllegalArgumentException)
        none.message.contains('This tool takes no arguments.')

        when: "a direct write tool's opaque args object carries the guide key"
        script._rejectUnknownArgs('hub_create_backup', [confirm: true, args: [bestPracticeKey: 'k']])

        then:
        notThrown(IllegalArgumentException)

        when: 'a gateway name is not checked: its sub-tool is, on re-entry'
        script._rejectUnknownArgs('hub_manage_code', [tool: 'hub_update_library', args: [:], __reqT0: 1L])

        then:
        notThrown(IllegalArgumentException)
    }

    @spock.lang.Unroll
    def "the frozen #name keeps undeclared arguments"() {
        when:
        script._rejectUnknownArgs(name, [localVariables: [:], whatever: true])

        then:
        notThrown(IllegalArgumentException)

        where:
        name << ['hub_create_custom_rule', 'hub_update_custom_rule', 'hub_get_custom_rule', 'hub_delete_custom_rule',
                 'hub_test_custom_rule', 'hub_export_custom_rule', 'hub_import_custom_rule', 'hub_clone_custom_rule',
                 'hub_manage_custom_rules', 'hub_list_captured_states', 'hub_delete_captured_state']
    }

    def "hub_call_device_command passes includeState through dispatch, default on"() {
        given:
        settingsMap.enableWrite = true
        def seen = []
        script.metaClass.toolSendCommand = { a, b, c, d, e, f, g -> seen << g; [success: true] }

        when:
        script.executeTool('hub_call_device_command', [deviceId: '5', command: 'on', includeState: false])
        script.executeTool('hub_call_device_command', [deviceId: '5', command: 'on', includeState: true])
        script.executeTool('hub_call_device_command', [deviceId: '5', command: 'on'])

        then:
        seen == [false, true, true]

        when: 'a misspelled waitFor is refused instead of skipping the wait'
        script.executeTool('hub_call_device_command', [deviceId: '5', command: 'on', waitFr: [attribute: 'switch', value: 'on']])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('Unknown hub_call_device_command argument: waitFr')
    }

    def "a gateway call to a strict tool with an unknown argument throws before the handler runs"() {
        given:
        settingsMap.enableWrite = true
        boolean reached = false
        script.metaClass.toolUpdateLibraryCode = { Map a -> reached = true; [success: true] }

        when:
        script.handleGateway('hub_manage_code', 'hub_update_library', [libraryId: '42', source: 's', expectedVerison: 3, confirm: true])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('Unknown hub_update_library argument: expectedVerison')
        !reached
    }

    def "a gateway refuses anything beside tool and args, so a misplaced lock never saves unlocked"() {
        given:
        settingsMap.useGateways = true
        settingsMap.enableWrite = true
        def posts = []
        script.metaClass.hubInternalPostJson = { String path, String body, int timeout = 420 -> posts << path; [success: true] }

        when:
        script.executeTool('hub_manage_code', [tool: 'hub_update_driver', args: [driverId: '5', source: 's', confirm: true], expectedVersion: 3])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('Unknown hub_manage_code argument: expectedVersion')
        e.message.contains('Valid arguments: tool, args')
        posts.isEmpty()
    }

    def "the MRTR access check refuses unknown arguments on the leaf and the gateway before anything is reserved"() {
        given:
        settingsMap.enableWrite = true
        String gateway = script.getGatewayConfig().find { it.value.tools.contains('hub_clone_native_app') }.key

        when: 'a misspelled clone argument through the flat tool'
        script._mrtrValidateAccess('hub_clone_native_app', 'hub_clone_native_app', [appId: 5, stageDisbled: true])

        then:
        def flat = thrown(IllegalArgumentException)
        flat.message.contains('Unknown hub_clone_native_app argument: stageDisbled')

        when: 'the same inside a gateway'
        script._mrtrValidateAccess(gateway, 'hub_clone_native_app', [tool: 'hub_clone_native_app', args: [appId: 5, stageDisbled: true]])

        then:
        def inner = thrown(IllegalArgumentException)
        inner.message.contains('Unknown hub_clone_native_app argument: stageDisbled')

        when: 'a key beside the gateway envelope'
        script._mrtrValidateAccess(gateway, 'hub_clone_native_app', [tool: 'hub_clone_native_app', args: [appId: 5], stageDisabled: true])

        then:
        def beside = thrown(IllegalArgumentException)
        beside.message.contains('argument: stageDisabled')
    }

    def "hub_update_device's refusal of a top-level field says where device fields go"() {
        when:
        script._rejectUnknownArgs('hub_update_device', [deviceId: '5', room: 'Den'])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('Unknown hub_update_device argument: room')
        e.message.contains('device fields go in fields and driver preferences in preferences')
    }

    def "a gateway's own arguments and the exempt keys pass the envelope check; the frozen custom-rule gateway is left alone"() {
        when:
        script._rejectUnknownArgs('hub_manage_code', [tool: 'hub_update_driver', args: [:], bestPracticeKey: 'k', __reqT0: 1L])
        script._rejectUnknownArgs('hub_manage_code', [:])
        script._rejectUnknownArgs('hub_manage_custom_rules', [tool: 'hub_list_custom_rules', args: [:], stray: 1])

        then:
        noExceptionThrown()
    }

    @spock.lang.Unroll
    def "dispatch: every tool refuses an unknown argument (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableWrite = true
        boolean reached = false
        script.metaClass.toolUpdateLibraryCode = { Map a -> reached = true; [success: true, received: a.keySet().sort()] }

        when:
        def strict = mcpDriver.callTool('hub_update_library', [libraryId: '42', source: 's', bogus: 1, confirm: true])

        then:
        strict.result.isError == true
        mcpDriver.parseInner(strict).error.contains('Unknown hub_update_library argument: bogus')
        !reached

        when: 'a tool without additionalProperties:false refuses it too'
        def lenient = mcpDriver.callTool('hub_install_bundle', [importUrl: 'https://example.com/b.zip', bogus: 1, confirm: true])

        then:
        lenient.result.isError == true
        mcpDriver.parseInner(lenient).error.contains('Unknown hub_install_bundle argument: bogus')

        when: 'the mandatory guide key is accepted on a strict tool'
        settingsMap.enableMandatoryBPS = true
        def keyed = mcpDriver.callTool('hub_update_library', [libraryId: '42', source: 's', confirm: true,
            bestPracticeKey: script.hubBpsGuideKey(script._bpsSectionForTool('hub_update_library'))])

        then:
        !keyed.result.isError
        reached
        mcpDriver.parseInner(keyed).received.contains('bestPracticeKey')

        where:
        useGateways << [true, false]
    }
}
