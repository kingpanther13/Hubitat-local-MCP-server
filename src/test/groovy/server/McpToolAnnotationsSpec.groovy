package server

import spock.lang.Unroll
import support.ToolSpecBase

/**
 * Pins the four-hint MCP annotations contract emitted by getToolDefinitions()
 * (issue #238): readOnlyHint + idempotentHint + openWorldHint on every entry,
 * destructiveHint on every write. Claude.ai's connector UI groups a server's
 * catalog into Read vs Write blocks from readOnlyHint; entries missing the
 * annotation fall into a generic "Other tools" bucket. This spec ensures every
 * entry (base tools, gateway entries, flat-mode and gateway-mode) carries the
 * hints, that the positive classification sets (getIdempotentWriteToolNames,
 * getOpenWorldToolNames) resolve to real tools, and that gateway aggregation
 * rolls leaf hints up correctly (any write sub-tool flips the gateway to
 * write+destructive; any non-idempotent sub-tool flips it non-idempotent; any
 * open-world sub-tool flips it open-world).
 */
class McpToolAnnotationsSpec extends ToolSpecBase {

    def "every gateway-mode entry carries an MCP annotations.readOnlyHint boolean"() {
        given:
        settingsMap.remove('useGateways')
        settingsMap.enableCustomRuleEngine = true

        when:
        def tools = script.getToolDefinitions()

        then:
        // Asserting non-empty defends against `every {}` returning true vacuously.
        !tools.isEmpty()
        tools.every {
            it.annotations instanceof Map &&
            it.annotations.readOnlyHint instanceof Boolean &&
            it.annotations.idempotentHint instanceof Boolean &&
            it.annotations.openWorldHint instanceof Boolean
        }
    }

    def "every flat-mode entry carries an MCP annotations.readOnlyHint boolean"() {
        given:
        settingsMap.useGateways = false
        settingsMap.enableCustomRuleEngine = true

        when:
        def tools = script.getToolDefinitions()

        then:
        !tools.isEmpty()
        tools.every {
            it.annotations instanceof Map &&
            it.annotations.readOnlyHint instanceof Boolean &&
            it.annotations.idempotentHint instanceof Boolean &&
            it.annotations.openWorldHint instanceof Boolean
        }
    }

    @Unroll
    def "flat mode: read-only tool '#name' has readOnlyHint=true and omits destructiveHint"() {
        given:
        settingsMap.useGateways = false
        settingsMap.enableCustomRuleEngine = true

        when:
        def tools = script.getToolDefinitions()
        def tool = tools.find { it.name == name }

        then:
        tool != null
        tool.annotations.readOnlyHint == true
        // destructiveHint is only meaningful when readOnlyHint=false -- read-only
        // tools omit the key entirely, not just set it to false.
        tool.annotations.containsKey('destructiveHint') == false

        where:
        // Sample from each category in getReadOnlyToolNames() -- full set lives in source.
        name << [
            'hub_list_devices', 'hub_get_device', 'hub_get_device_attribute',
            'hub_get_custom_rule', 'hub_test_custom_rule',
            'hub_get_info', 'hub_list_modes', 'hub_get_hsm_status',
            'hub_list_variables', 'hub_get_variable', 'hub_list_variable_changes',
            'hub_list_captured_states',
            'hub_get_debug_logs', 'hub_report_issue',
            'hub_get_logs', 'hub_list_device_events', 'hub_get_performance_stats',
            'hub_get_radio_details', 'hub_get_device_health', 'hub_get_metrics',
            'hub_list_apps', 'hub_get_source', 'hub_list_backups', 'hub_get_backup',
            'hub_list_rooms', 'hub_get_room',
            'hub_list_files', 'hub_read_file',
            'hub_get_app_config',
            'hub_list_hpm_packages',
            'hub_list_rules', 'hub_get_rule_health',
            'hub_get_tool_guide'
        ]
    }

    @Unroll
    def "flat mode: write tool '#name' is annotated readOnlyHint=false AND destructiveHint=true"() {
        // Project policy: every write is destructive. The explicit destructiveHint=true
        // matches the MCP spec default (destructiveHint defaults to true on writes) and
        // makes the wire payload unambiguous for clients that don't fall back to the
        // spec default.
        given:
        settingsMap.useGateways = false
        settingsMap.enableCustomRuleEngine = true

        when:
        def tools = script.getToolDefinitions()
        def tool = tools.find { it.name == name }

        then:
        tool != null
        tool.annotations.readOnlyHint == false
        tool.annotations.destructiveHint == true

        where:
        name << [
            'hub_call_device_command', 'hub_call_device_swap',
            'hub_create_custom_rule', 'hub_update_custom_rule', 'hub_delete_custom_rule',
            'hub_import_custom_rule', 'hub_clone_custom_rule', 'hub_export_custom_rule',
            'hub_set_mode',
            'hub_set_variable', 'hub_create_variable', 'hub_delete_variable',
            'hub_create_connector', 'hub_delete_connector',
            'hub_set_hsm', 'hub_set_log_level',
            'hub_update_mcp_settings',
            'hub_delete_captured_state',
            'hub_delete_debug_logs',
            'hub_create_backup', 'hub_call_gc',
            'hub_reboot', 'hub_shutdown', 'hub_call_zwave_repair', 'hub_delete_device',
            'hub_manage_virtual_device', 'hub_update_device',
            'hub_create_room', 'hub_delete_room', 'hub_update_room',
            'hub_create_app', 'hub_create_driver', 'hub_update_app', 'hub_update_driver',
            'hub_delete_item',
            'hub_create_library', 'hub_update_library', 'hub_install_bundle',
            'hub_delete_bundle', 'hub_export_bundle',
            'hub_restore_backup',
            'hub_write_file', 'hub_delete_file',
            'hub_call_rule', 'hub_set_rule_paused', 'hub_set_rule_private_boolean',
            'hub_set_rule', 'hub_set_native_app', 'hub_clone_native_app',
            'hub_import_native_app', 'hub_delete_native_app', 'hub_export_native_app'
        ]
    }

    def "gateway entries: all-read-only sub-tools roll up to readOnlyHint=true and omit destructiveHint"() {
        given:
        settingsMap.remove('useGateways')
        settingsMap.enableCustomRuleEngine = true

        when:
        def tools = script.getToolDefinitions()

        then: 'gateways whose every sub-tool is in getReadOnlyToolNames()'
        ['hub_read_apps_code', 'hub_read_devices', 'hub_read_diagnostics', 'hub_read_files',
         'hub_read_rooms', 'hub_read_rules', 'hub_read_variables'].each { gwName ->
            def gw = tools.find { it.name == gwName }
            assert gw != null : "${gwName} missing from gateway-mode catalog"
            assert gw.annotations.readOnlyHint == true : "${gwName} should be read-only"
            assert gw.annotations.containsKey('destructiveHint') == false : "${gwName} should omit destructiveHint"
        }
    }

    def "gateway entries: any write sub-tool flips the whole gateway to write+destructive"() {
        given:
        settingsMap.remove('useGateways')
        settingsMap.enableCustomRuleEngine = true

        when:
        def tools = script.getToolDefinitions()

        then: 'every gateway with at least one write sub-tool is write+destructive'
        def writeGateways = [
            'hub_manage_custom_rules', // hub_delete_custom_rule + others
            'hub_manage_variables',      // set/create/delete + others
            'hub_manage_rooms',              // create/delete/rename + others
            'hub_manage_destructive_ops',// reboot/shutdown/hub_delete_device
            'hub_manage_code',          // install/update/delete code
            'hub_manage_devices',            // hub_call_device_command, hub_call_device_swap, hub_update_device
            'hub_manage_logs',               // hub_delete_debug_logs, hub_set_log_level
            'hub_manage_diagnostics',        // hub_call_zwave_repair, hub_delete_captured_state
            'hub_manage_files',              // hub_write_file, hub_delete_file
            'hub_manage_native_rules_and_apps', // create/update/delete/run native rules
            'hub_manage_rule_machine',       // hub_call_rule, set_rule_paused, set_rule_private_boolean
            'hub_manage_mcp'            // hub_update_mcp_settings
        ]
        writeGateways.each { gwName ->
            def gw = tools.find { it.name == gwName }
            assert gw != null : "${gwName} missing from gateway-mode catalog"
            assert gw.annotations.readOnlyHint == false : "${gwName} should be write"
            assert gw.annotations.destructiveHint == true : "${gwName} should be destructive (any write sub-tool flips destructive)"
        }
    }

    def "annotationsForLeaf: read-only name returns readOnlyHint=true and no destructiveHint key"() {
        // Direct unit test of the helper -- catches regressions independently of
        // the larger getToolDefinitions() integration path.
        when:
        def readOnly = ['hub_list_rooms', 'hub_get_room'] as Set
        // displayMeta is a required param; null deliberately skips title emission
        // (the hint contract under test here is independent of titles).
        def ann = script.annotationsForLeaf('hub_list_rooms', readOnly, null, [] as Set, [] as Set)

        then:
        ann.readOnlyHint == true
        ann.containsKey('destructiveHint') == false
    }

    def "annotationsForLeaf: write name returns readOnlyHint=false and destructiveHint=true"() {
        when:
        def readOnly = ['hub_list_rooms'] as Set
        def ann = script.annotationsForLeaf('hub_delete_room', readOnly, null, [] as Set, [] as Set)

        then:
        ann.readOnlyHint == false
        ann.destructiveHint == true
    }

    def "annotationsForGateway: all-read sub-tools roll up to read-only, no destructiveHint"() {
        when:
        def readOnly = ['hub_list_rooms', 'hub_get_room', 'hub_list_devices'] as Set
        def ann = script.annotationsForGateway(['hub_list_rooms', 'hub_get_room'], readOnly, [] as Set, [] as Set)

        then:
        ann.readOnlyHint == true
        ann.containsKey('destructiveHint') == false
    }

    def "annotationsForGateway: any write sub-tool flips gateway to write+destructive"() {
        when:
        def readOnly = ['hub_list_rooms', 'hub_get_room'] as Set
        def ann = script.annotationsForGateway(['hub_list_rooms', 'hub_delete_room'], readOnly, [] as Set, [] as Set)

        then:
        ann.readOnlyHint == false
        ann.destructiveHint == true
    }

    def "annotationsForGateway: empty visibleSubTools throws (precondition guard)"() {
        when:
        script.annotationsForGateway([], ['hub_list_rooms'] as Set, [] as Set, [] as Set)

        then:
        thrown(IllegalArgumentException)
    }

    def "annotationsForGateway: hidden sub-tool drops out of the aggregation, flipping a mixed gateway to read-only"() {
        // Pins the load-bearing claim in the header comment: visibleSubTools drives
        // the label, so if a feature toggle hides every write sub-tool, the remaining
        // read-only sub-tools relabel the gateway to read-only. No current toggle
        // exercises this end-to-end (it requires a gateway whose writes are all
        // toggle-hidden while at least one read sub-tool stays visible), so this
        // unit-level test pins the helper contract directly.
        when:
        def readOnly = ['hub_list_rooms', 'hub_get_room', 'hub_list_devices'] as Set
        def withWrites = script.annotationsForGateway(['hub_list_rooms', 'hub_delete_room'], readOnly, [] as Set, [] as Set)
        def writesHidden = script.annotationsForGateway(['hub_list_rooms', 'hub_get_room'], readOnly, [] as Set, [] as Set)

        then:
        withWrites.readOnlyHint == false
        withWrites.destructiveHint == true
        writesHidden.readOnlyHint == true
        writesHidden.containsKey('destructiveHint') == false
    }

    def "gateway aggregation survives toggle-driven sub-tool hiding"() {
        // Smoke-tests the end-to-end path: the Write master OFF feeds getHiddenToolNames(),
        // which also filters gateway sub-tools. hub_manage_diagnostics is a mixed gateway --
        // with every write sub-tool (hub_call_gc, hub_call_zwave_repair, hub_delete_captured_state)
        // hidden, its surviving read sub-tools relabel the gateway to read-only. The gateway
        // still appears (it keeps reads), proving the aggregation re-derives the label from the
        // visible sub-tools rather than the static config.
        given:
        settingsMap.remove('useGateways')
        settingsMap.enableWrite = false  // Write master OFF -> all write sub-tools hidden

        when:
        def tools = script.getToolDefinitions()
        def diag = tools.find { it.name == 'hub_manage_diagnostics' }

        then:
        diag != null
        diag.annotations.readOnlyHint == true
        diag.annotations.containsKey('destructiveHint') == false
    }

    def "every name in getIdempotentWriteToolNames() resolves to a real WRITE tool"() {
        // Guards against typos/stale entries AND against accidentally listing a
        // read-only tool (reads are implicitly idempotent and must not be here).
        when:
        def allNames = script.getAllToolDefinitions()*.name as Set
        def idempotent = script.getIdempotentWriteToolNames()
        def readOnly = script.getReadOnlyToolNames()

        then:
        (idempotent - allNames) == [] as Set
        idempotent.intersect(readOnly) == [] as Set
    }

    def "every name in getOpenWorldToolNames() resolves to a real tool"() {
        when:
        def allNames = script.getAllToolDefinitions()*.name as Set

        then:
        (script.getOpenWorldToolNames() - allNames) == [] as Set
    }

    def "every write tool is explicitly classified for idempotency (symmetric snapshot)"() {
        // Mirror of the read/write completeness snapshot: the positive set means an
        // unlisted write silently defaults to non-idempotent, so this snapshot forces
        // the per-tool retry-safety decision into code review. set/update/delete
        // writes converge on retry (idempotent); create/clone/import make another
        // one and invoke-style writes re-fire the action (non-idempotent).
        when:
        def allWrites = (script.getAllToolDefinitions()*.name as Set) - script.getReadOnlyToolNames()
        def idempotent = script.getIdempotentWriteToolNames()
        def nonIdempotent = allWrites - idempotent

        def expectedIdempotent = [
            'hub_update_custom_rule', 'hub_delete_custom_rule',
            'hub_set_mode', 'hub_set_hsm',
            'hub_set_variable', 'hub_delete_variable', 'hub_create_connector', 'hub_delete_connector',
            'hub_update_mcp_settings', 'hub_set_log_level', 'hub_delete_debug_logs',
            'hub_delete_captured_state',
            'hub_update_device', 'hub_delete_device',
            'hub_update_room', 'hub_delete_room',
            'hub_update_app', 'hub_update_driver', 'hub_update_library',
            'hub_delete_item', 'hub_restore_backup',
            'hub_install_bundle', 'hub_delete_bundle', 'hub_export_bundle',
            'hub_write_file', 'hub_delete_file',
            'hub_set_rule_paused', 'hub_set_rule_private_boolean',
            'hub_export_native_app',
            'hub_delete_visual_rule',
            'hub_update_package'
        ] as Set

        def expectedNonIdempotent = [
            'hub_call_device_command', 'hub_call_device_swap',
            'hub_create_custom_rule', 'hub_import_custom_rule', 'hub_clone_custom_rule',
            'hub_export_custom_rule',
            'hub_create_variable',
            'hub_create_backup',
            'hub_reboot', 'hub_shutdown', 'hub_call_zwave_repair', 'hub_call_gc',
            'hub_manage_virtual_device',
            'hub_create_room',
            'hub_create_app', 'hub_create_driver', 'hub_create_library',
            'hub_call_rule', 'hub_set_rule', 'hub_set_native_app',
            'hub_clone_native_app', 'hub_import_native_app', 'hub_delete_native_app',
            'hub_set_visual_rule'
        ] as Set

        then:
        idempotent == expectedIdempotent
        nonIdempotent == expectedNonIdempotent
    }

    def "the open-world set is exactly the internet-reaching tools"() {
        // The hub, its devices, and its radios are the closed-world system; only
        // GitHub fetches, bundle-URL downloads, and importUrl source modes leave it.
        expect:
        script.getOpenWorldToolNames() == [
            'hub_get_update_status', 'hub_update_package', 'hub_install_bundle',
            'hub_create_app', 'hub_create_driver', 'hub_create_library',
            'hub_update_app', 'hub_update_driver', 'hub_update_library',
            'hub_get_device_health'
        ] as Set
    }

    def "wire spot checks: idempotent and open-world hints land on leaves, gateways, and the dev-mode tool"() {
        given:
        settingsMap.remove('useGateways')
        settingsMap.enableDeveloperMode = true

        when:
        def tools = script.getToolDefinitions()
        def byName = tools.collectEntries { [(it.name): it.annotations] }

        then: 'read-only core tool: idempotent, closed-world'
        byName.hub_get_info.idempotentHint == true
        byName.hub_get_info.openWorldHint == false

        and: 'pure-read gateway rolls up idempotent + closed-world'
        byName.hub_read_rooms.idempotentHint == true
        byName.hub_read_rooms.openWorldHint == false

        and: 'a creates-bearing gateway is non-idempotent; importUrl tools make it open-world'
        byName.hub_manage_code.idempotentHint == false
        byName.hub_manage_code.openWorldHint == true

        and: 'a mixed gateway whose every write converges on retry stays idempotent'
        byName.hub_manage_logs.idempotentHint == true
        byName.hub_manage_logs.openWorldHint == false

        and: 'the Developer Mode self-deploy is retry-safe and reaches GitHub'
        byName.hub_update_package.idempotentHint == true
        byName.hub_update_package.openWorldHint == true

        and: 'the ping capability rolls up open-world into the diagnostics gateways, which stay read-only and idempotent'
        byName.hub_read_diagnostics.readOnlyHint == true
        byName.hub_read_diagnostics.idempotentHint == true
        byName.hub_read_diagnostics.openWorldHint == true
        byName.hub_manage_diagnostics.openWorldHint == true
    }

    def "getIdempotentToolNames composes the reads plus the retry-safe writes"() {
        expect:
        script.getIdempotentToolNames() ==
            script.getReadOnlyToolNames() + script.getIdempotentWriteToolNames()
    }

    def "annotationsForLeaf and annotationsForGateway emit the idempotent and open-world hints from the classification sets"() {
        when:
        // idempotentNames is the COMPLETE surface (getIdempotentToolNames): a read
        // NOT in it (the carve-out case) ships idempotentHint=false despite being
        // read-only.
        def readOnly = ['hub_list_rooms', 'hub_get_metrics'] as Set
        def idem = ['hub_list_rooms', 'hub_delete_room'] as Set
        def open = ['hub_create_app'] as Set
        def readLeaf = script.annotationsForLeaf('hub_list_rooms', readOnly, null, idem, open)
        def carveOut = script.annotationsForLeaf('hub_get_metrics', readOnly, null, idem, open)
        def idemWrite = script.annotationsForLeaf('hub_delete_room', readOnly, null, idem, open)
        def openCreate = script.annotationsForLeaf('hub_create_app', readOnly, null, idem, open)
        def gwAllIdem = script.annotationsForGateway(['hub_list_rooms', 'hub_delete_room'], readOnly, idem, open)
        def gwMixed = script.annotationsForGateway(['hub_list_rooms', 'hub_create_app'], readOnly, idem, open)
        def gwCarve = script.annotationsForGateway(['hub_list_rooms', 'hub_get_metrics'], readOnly, idem, open)

        then: 'membership in the complete idempotent surface drives the hint'
        readLeaf.idempotentHint == true
        idemWrite.idempotentHint == true
        openCreate.idempotentHint == false

        and: 'a carved-out read stays read-only but loses the idempotent claim'
        carveOut.readOnlyHint == true
        carveOut.idempotentHint == false

        and: 'open-world only from the positive set'
        readLeaf.openWorldHint == false
        openCreate.openWorldHint == true

        and: 'gateway: idempotent iff every sub-tool is; open-world if any is'
        gwAllIdem.idempotentHint == true
        gwAllIdem.openWorldHint == false
        gwMixed.idempotentHint == false
        gwMixed.openWorldHint == true

        and: 'a pure-read gateway containing a carved-out read is read-only yet non-idempotent'
        gwCarve.readOnlyHint == true
        gwCarve.idempotentHint == false
    }

    def "every name in getReadOnlyToolNames() resolves to a real tool in getAllToolDefinitions()"() {
        // Guards against typos and stale entries after a tool is renamed or removed.
        when:
        def allNames = script.getAllToolDefinitions()*.name as Set
        def readOnly = script.getReadOnlyToolNames()

        then:
        (readOnly - allNames) == [] as Set
    }

    def "every leaf tool in the flat catalog is explicitly classified read or write"() {
        // The annotation helper treats unlisted names as write+destructive (safe default),
        // but a tool that genuinely should be read-only would silently land in the write
        // group, surfacing surprise permission prompts. This spec forces explicit
        // classification: every flat-catalog entry must appear in either the read-only
        // set or the enumerated write set below. Adjust the snapshots when intentionally
        // adding/removing tools -- the point is to make any change to the read/write
        // surface visible in code review.
        given:
        settingsMap.useGateways = false
        settingsMap.enableCustomRuleEngine = true

        when:
        def allNames = script.getToolDefinitions()*.name as Set
        def readOnly = script.getReadOnlyToolNames()
        def classifiedAsRead = allNames.intersect(readOnly)
        def classifiedAsWrite = (allNames - readOnly)

        def expectedReadOnly = [
            'hub_list_devices', 'hub_get_device', 'hub_get_device_attribute', 'hub_list_device_events',
            'hub_get_custom_rule', 'hub_test_custom_rule',
            'hub_get_info', 'hub_list_modes', 'hub_get_hsm_status', 'hub_get_update_status',
            'hub_list_variables', 'hub_get_variable', 'hub_list_variable_changes',
            'hub_list_captured_states',
            'hub_get_debug_logs', 'hub_report_issue',
            'hub_get_logs', 'hub_get_performance_stats',
            'hub_get_jobs', 'hub_get_memory_history',
            'hub_get_radio_details', 'hub_get_device_health', 'hub_get_metrics',
            'hub_list_apps', 'hub_list_drivers', 'hub_list_libraries', 'hub_list_bundles',
            'hub_get_source',
            'hub_list_backups', 'hub_get_backup',
            'hub_list_rooms', 'hub_get_room',
            'hub_list_files', 'hub_read_file',
            'hub_list_device_dependents', 'hub_get_app_config',
            'hub_list_app_pages',
            'hub_list_hpm_packages',
            'hub_list_rules', 'hub_get_rule_health', 'hub_get_visual_rule',
            'hub_get_tool_guide'
            // hub_search_tools is in getReadOnlyToolNames() but suppressed in flat mode.
        ] as Set

        def expectedWrites = [
            'hub_call_device_command', 'hub_call_device_swap',
            'hub_create_custom_rule', 'hub_update_custom_rule', 'hub_delete_custom_rule',
            'hub_import_custom_rule', 'hub_clone_custom_rule', 'hub_export_custom_rule',
            'hub_set_mode',
            'hub_set_variable', 'hub_create_variable', 'hub_delete_variable',
            'hub_create_connector', 'hub_delete_connector',
            'hub_update_mcp_settings',
            'hub_set_hsm',
            'hub_delete_captured_state',
            'hub_delete_debug_logs', 'hub_set_log_level',
            'hub_create_backup',
            'hub_reboot', 'hub_shutdown', 'hub_call_zwave_repair', 'hub_delete_device',
            'hub_call_gc',
            'hub_manage_virtual_device', 'hub_update_device',
            'hub_create_room', 'hub_delete_room', 'hub_update_room',
            'hub_create_app', 'hub_create_driver', 'hub_update_app', 'hub_update_driver',
            'hub_delete_item',
            'hub_create_library', 'hub_update_library', 'hub_install_bundle',
            'hub_delete_bundle', 'hub_export_bundle',
            'hub_restore_backup',
            'hub_write_file', 'hub_delete_file',
            'hub_call_rule', 'hub_set_rule_paused', 'hub_set_rule_private_boolean',
            'hub_set_rule', 'hub_set_native_app', 'hub_clone_native_app',
            'hub_import_native_app', 'hub_delete_native_app', 'hub_export_native_app',
            'hub_set_visual_rule', 'hub_delete_visual_rule'
        ] as Set

        then:
        // Symmetric snapshots: a regression in either direction surfaces with a clear
        // failure message ("expected reads matches actual reads" vs "expected writes
        // matches actual writes") instead of forcing the author to reverse-engineer
        // which side moved.
        classifiedAsRead == expectedReadOnly
        classifiedAsWrite == expectedWrites
    }

    def "every leaf tool in getAllToolDefinitions() declares a well-formed outputSchema"() {
        // Every tool DEFINITION declares an outputSchema describing its success shape
        // (MCP spec 2025-06-18 /server/tools). Per issue #290 the definitions are ALWAYS
        // present even though wire EMISSION is opt-in (settings.publishOutputSchemas,
        // default OFF) -- this test guards the definitions, NOT the emission. Asserting the
        // shape (object + non-empty properties map) catches a missing, stubbed, or
        // malformed schema on a newly-added tool.
        when:
        def defs = script.getAllToolDefinitions()

        then:
        !defs.isEmpty()  // defend against a vacuous every {}
        defs.every {
            it.outputSchema instanceof Map &&
            it.outputSchema.type == 'object' &&
            it.outputSchema.properties instanceof Map &&
            !it.outputSchema.properties.isEmpty()
        }
    }

    def "getAllToolDefinitions() concatenates its chunk methods with no dropped or duplicated tools"() {
        // getAllToolDefinitions() concatenates one per-domain chunk method
        // (_getAllToolDefinitions_part<Name>(), each contributed by its #include
        // library — e.g. _partNativeRM / _partRooms / _partDevices) rather than
        // building one giant literal (JVM 64KB method-bytecode cap). The
        // sandbox_lint guard parses source TEXT; this pins the actual runtime
        // concatenation — a dropped `+ _partRooms()`, a chunk returning [], or a
        // duplicated chunk would corrupt the list while the source still scans clean.
        when:
        def names = script.getAllToolDefinitions()*.name

        then: 'no chunk duplicated and no name collision'
        names.size() == (names as Set).size()

        and: 'no chunk dropped — the full surface is present (bump on intentional add/remove)'
        names.size() == 98

        and: 'sentinels from the first and last chunks survive the concatenation chain'
        names.contains('hub_list_devices')   // first chunk
        names.contains('hub_search_tools')   // last chunk
    }

    @Unroll
    def "hub_update_package is catalog-hidden when Developer Mode is OFF (#mode mode)"() {
        // The maintainer requirement (#209): a self-deploy tool must only be PRESENT
        // when Developer Mode is on, not merely runtime-refused. getDeveloperModeOnlyToolNames()
        // folds it into the hidden set whenever enableDeveloperMode is falsy.
        given:
        if (mode == 'flat') { settingsMap.useGateways = false } else { settingsMap.remove('useGateways') }
        settingsMap.remove('enableDeveloperMode')   // OFF

        when:
        def tools = script.getToolDefinitions()
        def names = tools*.name as Set
        def mcpGw = tools.find { it.name == 'hub_manage_mcp' }
        def subTools = (mcpGw?.inputSchema?.properties?.tool?.enum ?: []) as Set

        then: 'never advertised as a flat tool'
        !names.contains('hub_update_package')

        and: 'in gateway mode it is absent from the hub_manage_mcp sub-tool list, yet the gateway survives (hub_update_mcp_settings keeps it non-empty)'
        mode == 'flat' || (!subTools.contains('hub_update_package') && mcpGw != null)

        where:
        mode << ['gateway', 'flat']
    }

    @Unroll
    def "hub_update_package appears as a top-level write+destructive tool when Developer Mode is ON (#mode mode)"() {
        // Issue #250: pulled out of the hub_manage_mcp gateway -- it is now its own
        // top-level dev-mode tool in BOTH flat and gateway modes.
        given:
        if (mode == 'flat') { settingsMap.useGateways = false } else { settingsMap.remove('useGateways') }
        settingsMap.enableDeveloperMode = true   // ON

        when:
        def tools = script.getToolDefinitions()
        def t = tools.find { it.name == 'hub_update_package' }

        then: 'present as its own top-level tool, write + destructive'
        t != null
        t.annotations.readOnlyHint == false
        t.annotations.destructiveHint == true

        and: 'in gateway mode it is NOT a hub_manage_mcp sub-tool (top-level instead), but the gateway survives'
        if (mode == 'gateway') {
            def mcpGw = tools.find { it.name == 'hub_manage_mcp' }
            assert mcpGw != null : 'hub_manage_mcp gateway must still be present (hub_update_mcp_settings)'
            def subTools = (mcpGw.inputSchema?.properties?.tool?.enum ?: []) as Set
            assert !subTools.contains('hub_update_package') : 'hub_update_package must NOT be a hub_manage_mcp sub-tool anymore'
        }

        where:
        mode << ['gateway', 'flat']
    }

    def "outputSchema emission is gated by publishOutputSchemas in gateway mode; flat mode never emits it"() {
        // Issue #290: outputSchema emission is OPT-IN. The DEFINITION is always present
        // (see the 'every leaf tool declares a well-formed outputSchema' spec above), but
        // it reaches the wire only in gateway mode AND only when publishOutputSchemas is on
        // -- OFF by default so strict clients (e.g. Claude Desktop) that reject an
        // outputSchema returned without structuredContent work. The flat tools/list never
        // emits it regardless of the toggle (size-constrained 124,000-byte surface).
        // hub_get_info is a base/flat read tool present in both modes -- the clean probe.
        given:
        settingsMap.enableCustomRuleEngine = true

        when: 'gateway mode, publishOutputSchemas OFF (default)'
        settingsMap.remove('useGateways')
        settingsMap.remove('publishOutputSchemas')
        def gwOff = script.getToolDefinitions().find { it.name == 'hub_get_info' }

        and: 'gateway mode, publishOutputSchemas ON'
        settingsMap.publishOutputSchemas = true
        def gwOn = script.getToolDefinitions().find { it.name == 'hub_get_info' }

        and: 'flat mode, publishOutputSchemas ON (must STILL strip)'
        settingsMap.useGateways = false
        def flatOn = script.getToolDefinitions().find { it.name == 'hub_get_info' }

        and: 'flat mode, publishOutputSchemas OFF'
        settingsMap.remove('publishOutputSchemas')
        def flatOff = script.getToolDefinitions().find { it.name == 'hub_get_info' }

        then: 'gateway mode emits outputSchema only when the toggle is on'
        gwOff != null
        gwOff.containsKey('outputSchema') == false
        gwOn?.outputSchema instanceof Map
        gwOn.outputSchema.type == 'object'

        and: 'flat mode never emits outputSchema, regardless of the toggle'
        flatOn != null
        flatOn.containsKey('outputSchema') == false
        flatOff != null
        flatOff.containsKey('outputSchema') == false
    }

    def "getAllToolDefinitions() returns a FRESH list each call -- mutating one return must not leak to the next"() {
        // Pins the clean-copy invariant: applyDescriptionTransform mutates descriptions
        // in place, so getAllToolDefinitions() MUST hand back fresh Map literals every
        // call. A future naive memo that cached and returned the literal list would let
        // one consumer's in-place edit poison the next caller -- this trips loudly then.
        when: 'first call, then mutate the returned hub_list_devices description'
        def first = script.getAllToolDefinitions()
        def firstDev = first.find { it.name == 'hub_list_devices' }
        firstDev.description = 'MUTATED-BY-TEST'

        and: 'second call'
        def second = script.getAllToolDefinitions()
        def secondDev = second.find { it.name == 'hub_list_devices' }

        then: 'the mutation did not leak into the fresh copy'
        secondDev.description != 'MUTATED-BY-TEST'
        secondDev.description.contains('List all devices')
    }

    def "flat-mode in-place FLAT_TRIM strip does not leak into a subsequent getAllToolDefinitions() call"() {
        // getToolDefinitions(useGateways=false) strips the [[FLAT_TRIM]] block IN PLACE on
        // its fresh maps. Because each getAllToolDefinitions() rebuilds, the next caller
        // must still see the un-stripped FLAT_TRIM content. hub_list_devices carries one.
        given:
        settingsMap.useGateways = false

        when: 'run the flat-mode strip path (mutates its own fresh copy in place)'
        def flat = script.getToolDefinitions()
        def flatDev = flat.find { it.name == 'hub_list_devices' }

        and: 'a fresh full-definition call afterwards'
        def freshDev = script.getAllToolDefinitions().find { it.name == 'hub_list_devices' }

        then: 'flat copy had the FLAT_TRIM markers removed'
        !flatDev.description.contains('[[FLAT_TRIM]]')

        and: 'the fresh copy still carries the original FLAT_TRIM markers + wrapped content'
        freshDev.description.contains('[[FLAT_TRIM]]')
        freshDev.description.contains('Summary mode returns currentStates')
    }
}
