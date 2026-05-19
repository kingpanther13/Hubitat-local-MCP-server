package server

import spock.lang.Unroll
import support.ToolSpecBase

/**
 * Pins the MCP `annotations.readOnlyHint` / `destructiveHint` contract emitted
 * by getToolDefinitions(). Claude.ai's connector UI groups a server's catalog
 * into Read tools vs Write tools blocks based on readOnlyHint; tools missing
 * the annotation fall into a generic "Other tools" bucket. Pre-2026 this
 * server emitted no annotations, so every entry on tools/list — over 100
 * tools — was lumped into "Other tools". This spec ensures every entry
 * (base tools, gateway entries, and flat-mode entries) carries a
 * readOnlyHint, that the value matches the source-of-truth set in
 * getReadOnlyToolNames(), and that gateway aggregation rolls the leaf
 * labels up correctly (any write sub-tool → gateway is write; any
 * destructive sub-tool → gateway is destructive).
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
        tools.every {
            it.annotations instanceof Map &&
            it.annotations.readOnlyHint instanceof Boolean
        }
    }

    @Unroll
    def "flat mode: read-only tool '#name' is annotated readOnlyHint=true"() {
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
        tool.annotations.destructiveHint != true  // destructiveHint is only meaningful on writes

        where:
        // Sample from each category in getReadOnlyToolNames() — full set lives in source.
        name << [
            'list_devices', 'get_device', 'get_attribute', 'poll_until_attribute',
            'custom_list_rules', 'custom_test_rule', 'custom_export_rule',
            'get_hub_info', 'get_modes', 'get_hsm_status',
            'list_variables', 'get_variable', 'get_variable_history',
            'list_captured_states',
            'get_debug_logs', 'get_logging_status', 'generate_bug_report',
            'get_hub_logs', 'get_device_history', 'get_performance_stats',
            'get_zwave_details', 'get_zigbee_details', 'device_health_check',
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
    def "flat mode: write tool '#name' is annotated readOnlyHint=false"() {
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

        where:
        name << [
            'send_command',
            'custom_create_rule', 'custom_update_rule', 'custom_import_rule', 'custom_clone_rule',
            'set_mode',
            'set_variable', 'create_variable', 'create_connector',
            'set_hsm', 'set_log_level',
            'update_mcp_settings',
            'create_hub_backup', 'force_garbage_collection', 'get_set_hub_metrics',
            'update_device',
            'create_room', 'rename_room',
            'install_app', 'install_driver', 'update_app_code', 'update_driver_code',
            'install_library', 'update_library_code', 'restore_item_backup',
            'write_file',
            'run_rm_rule', 'pause_rm_rule', 'resume_rm_rule', 'set_rm_rule_boolean',
            'create_native_app', 'update_native_app', 'clone_native_app', 'import_native_app'
        ]
    }

    @Unroll
    def "flat mode: destructive tool '#name' is annotated destructiveHint=true and readOnlyHint=false"() {
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
            'custom_delete_rule',
            'delete_variable', 'remove_connector',
            'delete_captured_state', 'clear_captured_states',
            'clear_debug_logs',
            'reboot_hub', 'shutdown_hub', 'zwave_repair', 'delete_device',
            'delete_room',
            'delete_app', 'delete_driver', 'delete_library',
            'delete_file',
            'delete_native_app',
            'manage_virtual_device'
        ]
    }

    def "gateway entries: all-read-only sub-tools roll up to readOnlyHint=true"() {
        given:
        settingsMap.remove('useGateways')
        settingsMap.enableBuiltinApp = true
        settingsMap.enableCustomRuleEngine = true

        when:
        def tools = script.getToolDefinitions()

        then: 'gateways whose every sub-tool is in getReadOnlyToolNames()'
        // manage_apps_drivers, manage_installed_apps, and manage_hpm are pure-read
        // gateways per their getGatewayConfig() entries; surface them as such.
        tools.find { it.name == 'manage_apps_drivers' }.annotations.readOnlyHint == true
        tools.find { it.name == 'manage_installed_apps' }.annotations.readOnlyHint == true
        tools.find { it.name == 'manage_hpm' }.annotations.readOnlyHint == true
    }

    def "gateway entries: any write sub-tool flips the whole gateway to write"() {
        given:
        settingsMap.remove('useGateways')
        settingsMap.enableBuiltinApp = true
        settingsMap.enableCustomRuleEngine = true

        when:
        def tools = script.getToolDefinitions()

        then: 'gateways with mixed read/write sub-tools surface as writes'
        // manage_hub_variables mixes list_variables (read) with set_variable / delete_variable (write+destructive).
        // manage_rooms mixes list_rooms (read) with create_room / delete_room (write+destructive).
        // manage_logs mixes get_hub_logs (read) with clear_debug_logs / set_log_level (write).
        // manage_files mixes list_files / read_file (read) with write_file / delete_file (write+destructive).
        // manage_native_rules_and_apps mixes list_rm_rules / check_rule_health (read) with create_native_app etc (write).
        // manage_rules_admin mixes custom_test_rule / custom_export_rule (read) with custom_delete_rule etc (write).
        tools.find { it.name == 'manage_hub_variables' }.annotations.readOnlyHint == false
        tools.find { it.name == 'manage_rooms' }.annotations.readOnlyHint == false
        tools.find { it.name == 'manage_logs' }.annotations.readOnlyHint == false
        tools.find { it.name == 'manage_files' }.annotations.readOnlyHint == false
        tools.find { it.name == 'manage_native_rules_and_apps' }.annotations.readOnlyHint == false
        tools.find { it.name == 'manage_rules_admin' }.annotations.readOnlyHint == false

        and: 'all-write gateways are also writes'
        tools.find { it.name == 'manage_destructive_hub_ops' }.annotations.readOnlyHint == false
        tools.find { it.name == 'manage_app_driver_code' }.annotations.readOnlyHint == false
        tools.find { it.name == 'manage_mcp_self' }.annotations.readOnlyHint == false
    }

    def "gateway entries: any destructive sub-tool surfaces destructiveHint=true"() {
        given:
        settingsMap.remove('useGateways')
        settingsMap.enableBuiltinApp = true
        settingsMap.enableCustomRuleEngine = true

        when:
        def tools = script.getToolDefinitions()

        then:
        tools.find { it.name == 'manage_destructive_hub_ops' }.annotations.destructiveHint == true
        tools.find { it.name == 'manage_hub_variables' }.annotations.destructiveHint == true
        tools.find { it.name == 'manage_rooms' }.annotations.destructiveHint == true
        tools.find { it.name == 'manage_files' }.annotations.destructiveHint == true
        tools.find { it.name == 'manage_app_driver_code' }.annotations.destructiveHint == true
        tools.find { it.name == 'manage_native_rules_and_apps' }.annotations.destructiveHint == true
        tools.find { it.name == 'manage_rules_admin' }.annotations.destructiveHint == true
        tools.find { it.name == 'manage_diagnostics' }.annotations.destructiveHint == true

        and: 'pure-read gateways do not carry destructiveHint'
        tools.find { it.name == 'manage_apps_drivers' }.annotations.containsKey('destructiveHint') == false
        tools.find { it.name == 'manage_installed_apps' }.annotations.containsKey('destructiveHint') == false
        tools.find { it.name == 'manage_hpm' }.annotations.containsKey('destructiveHint') == false

        and: 'write-but-not-destructive gateways do not carry destructiveHint'
        // manage_logs has clear_debug_logs (destructive) -- so this gateway IS destructive.
        // manage_mcp_self contains only update_mcp_settings (write, non-destructive).
        tools.find { it.name == 'manage_mcp_self' }.annotations.containsKey('destructiveHint') == false
    }

    def "gateway aggregation survives sub-tool hiding without crashing or mis-classifying"() {
        // customEngineMode=off ANDs both toggles to false; that path adds
        // custom_get_rule_diagnostics to hideGatewaySubTools["manage_diagnostics"],
        // so the aggregator runs over a strictly smaller visibleSubTools list than
        // the static gateway config. Smoke-tests that the aggregator survives the
        // narrower list -- manage_diagnostics still contains zwave_repair /
        // clear_captured_states so the label stays write+destructive.
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

    def "readOnlyToolNames and destructiveToolNames are disjoint -- a tool cannot be both"() {
        when:
        def readOnly = script.getReadOnlyToolNames()
        def destructive = script.getDestructiveToolNames()

        then:
        (readOnly.intersect(destructive)) == [] as Set
    }

    def "every name in readOnlyToolNames and destructiveToolNames exists in getAllToolDefinitions"() {
        // Guards against typos that silently classify a non-existent tool — and against
        // stale entries that linger after a tool is renamed or removed.
        when:
        def allNames = script.getAllToolDefinitions()*.name as Set
        def readOnly = script.getReadOnlyToolNames()
        def destructive = script.getDestructiveToolNames()

        then:
        (readOnly - allNames) == [] as Set
        (destructive - allNames) == [] as Set
    }

    def "every leaf tool (flat mode) is classified — none fall through to default-write-without-listing"() {
        // The annotation layer treats unlisted tools as write+non-destructive (safe default),
        // but a tool that genuinely should be read-only would silently land in the write
        // group. This spec forces explicit classification: every tool in the flat catalog
        // must appear in EITHER getReadOnlyToolNames() OR the implicit-write set (which we
        // derive here as `allNames - readOnly`). The intent: catch new tools added without
        // an explicit annotation decision.
        given:
        settingsMap.useGateways = false
        settingsMap.enableBuiltinApp = true
        settingsMap.enableCustomRuleEngine = true

        when:
        def allNames = script.getToolDefinitions()*.name as Set
        def readOnly = script.getReadOnlyToolNames()
        def destructive = script.getDestructiveToolNames()
        def classifiedAsWrite = (allNames - readOnly)

        then: 'every classified-as-write entry is intentional (i.e., appears here as a sanity check on the count).'
        // Adjust this list when intentionally adding/removing write tools. The point is
        // to make any change to the write surface visible in code review.
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
            'clear_debug_logs', 'set_log_level',
            'create_hub_backup',
            'reboot_hub', 'shutdown_hub', 'zwave_repair', 'delete_device',
            'force_garbage_collection', 'get_set_hub_metrics',
            'manage_virtual_device', 'update_device',
            'create_room', 'delete_room', 'rename_room',
            'install_app', 'install_driver', 'update_app_code', 'update_driver_code',
            'delete_app', 'delete_driver',
            'install_library', 'update_library_code', 'delete_library',
            'restore_item_backup',
            'write_file', 'delete_file',
            'run_rm_rule', 'pause_rm_rule', 'resume_rm_rule', 'set_rm_rule_boolean',
            'create_native_app', 'update_native_app', 'clone_native_app',
            'import_native_app', 'delete_native_app'
        ] as Set
        classifiedAsWrite == expectedWrites

        and: 'destructive is a subset of write'
        (destructive - classifiedAsWrite) == [] as Set
    }
}
