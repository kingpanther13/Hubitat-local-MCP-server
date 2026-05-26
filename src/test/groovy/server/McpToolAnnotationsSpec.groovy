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
        // Removed by tool-surface reduction (folded into other reads):
        //   poll_until_attribute → get_attribute(expectedValue=...)
        //   custom_list_rules → custom_get_rule (no-id overload)
        //   get_debug_logs + get_logging_status → get_debug_log_state(mode=logs|status)
        //   get_set_hub_metrics → get_hub_info (recordSnapshot/trendPoints opt-in)
        name << [
            'list_devices', 'get_device', 'get_attribute',
            'custom_get_rule', 'custom_test_rule', 'custom_export_rule',
            'get_hub_info', 'get_modes', 'get_hsm_status',
            'list_variables', 'get_variable', 'get_variable_history',
            'list_captured_states',
            'get_debug_log_state', 'generate_bug_report',
            'get_hub_logs', 'get_device_history', 'get_performance_stats',
            'get_zwave_details', 'get_zigbee_details', 'device_health_check',
            'force_garbage_collection',
            'list_hub_apps', 'get_app_source', 'list_item_backups',
            'list_virtual_devices',
            'list_rooms', 'get_room',
            'list_files', 'read_file',
            'list_installed_apps', 'get_app_config',
            'list_hpm_packages', 'get_hpm_drift',
            'list_rm_rules', 'export_native_app', 'check_rule_health',
            'get_tool_guide'
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
        // Removed by tool-surface reduction (folded into other writes):
        //   set_log_level + clear_debug_logs → update_debug_logs(action=setLevel|clear)
        //   install_app + update_app_code → save_app
        //   install_driver + update_driver_code → save_driver
        //   install_library + update_library_code → save_library
        //   get_set_hub_metrics → get_hub_info (recordSnapshot=true), which is read-only
        //   force_garbage_collection moved to read-only set (mutates JVM but not persisted state)
        name << [
            'send_command',
            'custom_create_rule', 'custom_update_rule', 'custom_delete_rule',
            'custom_import_rule', 'custom_clone_rule',
            'set_mode',
            'set_variable', 'create_variable', 'delete_variable',
            'create_connector', 'remove_connector',
            'set_hsm',
            'update_mcp_settings',
            'delete_captured_state', 'clear_captured_states',
            'update_debug_logs',
            'create_hub_backup',
            'reboot_hub', 'shutdown_hub', 'zwave_repair', 'delete_device',
            'manage_virtual_device', 'update_device',
            'create_room', 'delete_room', 'rename_room',
            'save_app', 'save_driver',
            'delete_app', 'delete_driver',
            'save_library', 'delete_library',
            'restore_item_backup',
            'write_file', 'delete_file',
            'run_rm_rule', 'pause_rm_rule', 'resume_rm_rule', 'set_rm_rule_boolean',
            'create_native_app', 'update_native_app', 'clone_native_app',
            'import_native_app', 'delete_native_app'
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
        ['manage_apps_drivers', 'manage_installed_apps', 'manage_hpm'].each { gwName ->
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
            'manage_rules_admin',        // custom_delete_rule + others
            'manage_hub_variables',      // set/create/delete + others
            'manage_rooms',              // create/delete/rename + others
            'manage_destructive_hub_ops',// reboot/shutdown/delete_device
            'manage_app_driver_code',    // install/update/delete code
            'manage_logs',               // clear_debug_logs, set_log_level
            'manage_diagnostics',        // zwave_repair, clear_captured_states
            'manage_files',              // write_file, delete_file
            'manage_native_rules_and_apps', // create/update/delete/run native rules
            'manage_mcp_self'            // update_mcp_settings
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
        def readOnly = ['list_rooms', 'get_room'] as Set
        def ann = script.annotationsForLeaf('list_rooms', readOnly)

        then:
        ann.readOnlyHint == true
        ann.containsKey('destructiveHint') == false
    }

    def "annotationsForLeaf: write name returns readOnlyHint=false and destructiveHint=true"() {
        when:
        def readOnly = ['list_rooms'] as Set
        def ann = script.annotationsForLeaf('delete_room', readOnly)

        then:
        ann.readOnlyHint == false
        ann.destructiveHint == true
    }

    def "annotationsForGateway: all-read sub-tools roll up to read-only, no destructiveHint"() {
        when:
        def readOnly = ['list_rooms', 'get_room', 'list_devices'] as Set
        def ann = script.annotationsForGateway(['list_rooms', 'get_room'], readOnly)

        then:
        ann.readOnlyHint == true
        ann.containsKey('destructiveHint') == false
    }

    def "annotationsForGateway: any write sub-tool flips gateway to write+destructive"() {
        when:
        def readOnly = ['list_rooms', 'get_room'] as Set
        def ann = script.annotationsForGateway(['list_rooms', 'delete_room'], readOnly)

        then:
        ann.readOnlyHint == false
        ann.destructiveHint == true
    }

    def "annotationsForGateway: empty visibleSubTools throws (precondition guard)"() {
        when:
        script.annotationsForGateway([], ['list_rooms'] as Set)

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
        def readOnly = ['list_rooms', 'get_room', 'list_devices'] as Set
        def withWrites = script.annotationsForGateway(['list_rooms', 'delete_room'], readOnly)
        def writesHidden = script.annotationsForGateway(['list_rooms', 'get_room'], readOnly)

        then:
        withWrites.readOnlyHint == false
        withWrites.destructiveHint == true
        writesHidden.readOnlyHint == true
        writesHidden.containsKey('destructiveHint') == false
    }

    def "gateway aggregation survives toggle-driven sub-tool hiding"() {
        // Smoke-tests the end-to-end path: customEngineMode=off adds
        // custom_get_rule_diagnostics to hideByName, which now also filters
        // gateway sub-tools, so manage_diagnostics's visibleSubTools is one
        // shorter than its config. The remaining sub-tools still include
        // writes (zwave_repair etc.), so the label stays write+destructive.
        given:
        settingsMap.remove('useGateways')
        settingsMap.enableBuiltinApp = false
        settingsMap.enableCustomRuleEngine = false  // customEngineMode = off

        when:
        def tools = script.getToolDefinitions()
        def diag = tools.find { it.name == 'manage_diagnostics' }

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
            'list_devices', 'get_device', 'get_attribute', 'get_device_events',
            // poll_until_attribute folded into get_attribute(expectedValue=...)
            // custom_list_rules folded into custom_get_rule (no-id overload)
            'custom_get_rule', 'custom_test_rule',
            'custom_get_rule_diagnostics', 'custom_export_rule',
            'get_hub_info', 'get_modes', 'get_hsm_status', 'check_for_update',
            'list_variables', 'get_variable', 'get_variable_history',
            'list_captured_states',
            // get_debug_logs + get_logging_status folded into get_debug_log_state(mode=...)
            'get_debug_log_state', 'generate_bug_report',
            'get_hub_logs', 'get_device_history', 'get_performance_stats',
            'get_hub_jobs', 'get_memory_history',
            'get_zwave_details', 'get_zigbee_details', 'device_health_check',
            // force_garbage_collection moved here per maintainer rule that JVM-only
            // mutations (no persisted hub data change) classify as read.
            'force_garbage_collection',
            'list_hub_apps', 'list_hub_drivers',
            'get_app_source', 'get_driver_source', 'get_library_source',
            'list_item_backups', 'get_item_backup',
            'list_virtual_devices',
            'list_rooms', 'get_room',
            'list_files', 'read_file',
            'list_installed_apps', 'get_device_in_use_by', 'get_app_config',
            'list_app_pages',
            'list_hpm_packages', 'get_hpm_drift',
            'list_rm_rules', 'export_native_app', 'check_rule_health',
            'get_tool_guide'
            // search_tools is in getReadOnlyToolNames() but suppressed in flat mode.
        ] as Set

        def expectedWrites = [
            'send_command',
            'custom_create_rule', 'custom_update_rule', 'custom_delete_rule',
            'custom_import_rule', 'custom_clone_rule',
            'set_mode',
            'set_variable', 'create_variable', 'delete_variable',
            'create_connector', 'remove_connector',
            'update_mcp_settings',
            'set_hsm',
            'delete_captured_state', 'clear_captured_states',
            // clear_debug_logs + set_log_level folded into update_debug_logs(action=...)
            'update_debug_logs',
            'create_hub_backup',
            'reboot_hub', 'shutdown_hub', 'zwave_repair', 'delete_device',
            // get_set_hub_metrics folded into get_hub_info(recordSnapshot/trendPoints opt-in, read-only by default)
            'manage_virtual_device', 'update_device',
            'create_room', 'delete_room', 'rename_room',
            // install_app + update_app_code → save_app; install_driver + update_driver_code → save_driver;
            // install_library + update_library_code → save_library
            'save_app', 'save_driver',
            'delete_app', 'delete_driver',
            'save_library', 'delete_library',
            'restore_item_backup',
            'write_file', 'delete_file',
            'run_rm_rule', 'pause_rm_rule', 'resume_rm_rule', 'set_rm_rule_boolean',
            'create_native_app', 'update_native_app', 'clone_native_app',
            'import_native_app', 'delete_native_app'
        ] as Set

        then:
        // Symmetric snapshots: a regression in either direction surfaces with a clear
        // failure message ("expected reads matches actual reads" vs "expected writes
        // matches actual writes") instead of forcing the author to reverse-engineer
        // which side moved.
        classifiedAsRead == expectedReadOnly
        classifiedAsWrite == expectedWrites
    }
}
