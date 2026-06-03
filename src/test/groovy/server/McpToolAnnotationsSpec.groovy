package server

import spock.lang.Unroll
import support.ToolSpecBase

/**
 * Pins the MCP `annotations.readOnlyHint` / `destructiveHint` contract emitted
 * by getToolDefinitions(). Claude.ai's connector UI groups a server's catalog
 * into Read vs Write blocks from readOnlyHint; entries missing the annotation
 * fall into a generic "Other tools" bucket. This spec ensures every entry
 * (base tools, gateway entries, flat-mode and gateway-mode) carries
 * readOnlyHint, that writes also carry destructiveHint=true (matching the
 * MCP spec default and the project's "writes are destructive" policy), and
 * that gateway aggregation rolls leaf labels up correctly (any write sub-tool
 * flips the gateway to write+destructive).
 */
class McpToolAnnotationsSpec extends ToolSpecBase {

    def "every gateway-mode entry carries an MCP annotations.readOnlyHint boolean"() {
        given:
        settingsMap.remove('useGateways')
        settingsMap.enableBuiltinApp = true
        settingsMap.enableCustomRuleEngine = true

        when:
        def tools = script.getToolDefinitions()

        then:
        // Asserting non-empty defends against `every {}` returning true vacuously.
        !tools.isEmpty()
        tools.every {
            it.annotations instanceof Map &&
            it.annotations.readOnlyHint instanceof Boolean
        }
    }

    def "every flat-mode entry carries an MCP annotations.readOnlyHint boolean"() {
        given:
        settingsMap.useGateways = false
        settingsMap.enableBuiltinApp = true
        settingsMap.enableCustomRuleEngine = true

        when:
        def tools = script.getToolDefinitions()

        then:
        !tools.isEmpty()
        tools.every {
            it.annotations instanceof Map &&
            it.annotations.readOnlyHint instanceof Boolean
        }
    }

    @Unroll
    def "flat mode: read-only tool '#name' has readOnlyHint=true and omits destructiveHint"() {
        given:
        settingsMap.useGateways = false
        settingsMap.enableBuiltinApp = true
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
        settingsMap.enableBuiltinApp = true
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
            'hub_call_device_command',
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
            'hub_create_library', 'hub_update_library',
            'hub_restore_backup',
            'hub_write_file', 'hub_delete_file',
            'hub_call_rule', 'hub_set_rule_paused', 'hub_set_rule_private_boolean',
            'hub_create_native_app', 'hub_update_native_app', 'hub_clone_native_app',
            'hub_import_native_app', 'hub_delete_native_app', 'hub_export_native_app'
        ]
    }

    def "gateway entries: all-read-only sub-tools roll up to readOnlyHint=true and omit destructiveHint"() {
        given:
        settingsMap.remove('useGateways')
        settingsMap.enableBuiltinApp = true
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
        settingsMap.enableBuiltinApp = true
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
            'hub_manage_devices',            // hub_call_device_command, hub_update_device
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
        def ann = script.annotationsForLeaf('hub_list_rooms', readOnly)

        then:
        ann.readOnlyHint == true
        ann.containsKey('destructiveHint') == false
    }

    def "annotationsForLeaf: write name returns readOnlyHint=false and destructiveHint=true"() {
        when:
        def readOnly = ['hub_list_rooms'] as Set
        def ann = script.annotationsForLeaf('hub_delete_room', readOnly)

        then:
        ann.readOnlyHint == false
        ann.destructiveHint == true
    }

    def "annotationsForGateway: all-read sub-tools roll up to read-only, no destructiveHint"() {
        when:
        def readOnly = ['hub_list_rooms', 'hub_get_room', 'hub_list_devices'] as Set
        def ann = script.annotationsForGateway(['hub_list_rooms', 'hub_get_room'], readOnly)

        then:
        ann.readOnlyHint == true
        ann.containsKey('destructiveHint') == false
    }

    def "annotationsForGateway: any write sub-tool flips gateway to write+destructive"() {
        when:
        def readOnly = ['hub_list_rooms', 'hub_get_room'] as Set
        def ann = script.annotationsForGateway(['hub_list_rooms', 'hub_delete_room'], readOnly)

        then:
        ann.readOnlyHint == false
        ann.destructiveHint == true
    }

    def "annotationsForGateway: empty visibleSubTools throws (precondition guard)"() {
        when:
        script.annotationsForGateway([], ['hub_list_rooms'] as Set)

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
        def withWrites = script.annotationsForGateway(['hub_list_rooms', 'hub_delete_room'], readOnly)
        def writesHidden = script.annotationsForGateway(['hub_list_rooms', 'hub_get_room'], readOnly)

        then:
        withWrites.readOnlyHint == false
        withWrites.destructiveHint == true
        writesHidden.readOnlyHint == true
        writesHidden.containsKey('destructiveHint') == false
    }

    def "gateway aggregation survives toggle-driven sub-tool hiding"() {
        // Smoke-tests the end-to-end path: both toggles off (customEngineMode=off,
        // Built-in App Tools off) feed hideByName, which also filters gateway
        // sub-tools. hub_manage_diagnostics carries no toggle-hidden sub-tools, but
        // it keeps writes (hub_call_zwave_repair, hub_delete_captured_state) that are
        // always visible, so the gateway label stays write+destructive regardless.
        given:
        settingsMap.remove('useGateways')
        settingsMap.enableBuiltinApp = false
        settingsMap.enableCustomRuleEngine = false  // customEngineMode = off

        when:
        def tools = script.getToolDefinitions()
        def diag = tools.find { it.name == 'hub_manage_diagnostics' }

        then:
        diag != null
        diag.annotations.readOnlyHint == false
        diag.annotations.destructiveHint == true
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
        settingsMap.enableBuiltinApp = true
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
            'hub_list_apps', 'hub_list_drivers',
            'hub_get_source',
            'hub_list_backups', 'hub_get_backup',
            'hub_list_rooms', 'hub_get_room',
            'hub_list_files', 'hub_read_file',
            'hub_list_device_dependents', 'hub_get_app_config',
            'hub_list_app_pages',
            'hub_list_hpm_packages',
            'hub_list_rules', 'hub_get_rule_health',
            'hub_get_tool_guide'
            // hub_search_tools is in getReadOnlyToolNames() but suppressed in flat mode.
        ] as Set

        def expectedWrites = [
            'hub_call_device_command',
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
            'hub_create_library', 'hub_update_library',
            'hub_restore_backup',
            'hub_write_file', 'hub_delete_file',
            'hub_call_rule', 'hub_set_rule_paused', 'hub_set_rule_private_boolean',
            'hub_create_native_app', 'hub_update_native_app', 'hub_clone_native_app',
            'hub_import_native_app', 'hub_delete_native_app', 'hub_export_native_app'
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
        // PR1C: every tool returns a structured map, so every leaf definition MUST
        // publish an outputSchema describing it (MCP spec 2025-06-18 /server/tools;
        // AGENTS.md Schema design -- "servers MUST conform to a published outputSchema;
        // clients SHOULD validate"). Asserting the shape (object + non-empty properties
        // map) catches a missing, stubbed, or malformed schema on a newly-added tool.
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
        // PR1C split getAllToolDefinitions() into _getAllToolDefinitions_part1..8()
        // that the public method concatenates (JVM 64KB method-bytecode cap). The
        // sandbox_lint guard parses source TEXT; this pins the actual runtime
        // concatenation — a dropped `+ _part5()`, a chunk returning [:], or a
        // duplicated chunk would corrupt the list while the source still scans clean.
        when:
        def names = script.getAllToolDefinitions()*.name

        then: 'no chunk duplicated and no name collision'
        names.size() == (names as Set).size()

        and: 'no chunk dropped — the full surface is present (bump on intentional add/remove)'
        names.size() == 88

        and: 'sentinels from the first and last chunks survive the concatenation chain'
        names.contains('hub_list_devices')   // first chunk
        names.contains('hub_search_tools')   // last chunk
    }

    def "outputSchema is published in gateway mode (base tools) and stripped in flat mode (size)"() {
        // PR1C size strategy: flat-mode tools/list is the all-tools surface bounded by
        // the hub's 124,000-byte cap, so outputSchema is dropped there; gateway-mode
        // base tools (and the gateway catalog disclosure) carry it, where the
        // per-response budget has headroom. hub_get_info is a flat/base read tool in
        // both modes, so it is the clean probe.
        given:
        settingsMap.enableBuiltinApp = true
        settingsMap.enableCustomRuleEngine = true

        when: 'gateway mode'
        settingsMap.remove('useGateways')
        def gwInfo = script.getToolDefinitions().find { it.name == 'hub_get_info' }

        and: 'flat mode'
        settingsMap.useGateways = false
        def flatInfo = script.getToolDefinitions().find { it.name == 'hub_get_info' }

        then:
        gwInfo?.outputSchema instanceof Map
        gwInfo.outputSchema.type == 'object'
        flatInfo != null
        flatInfo.containsKey('outputSchema') == false
    }
}
