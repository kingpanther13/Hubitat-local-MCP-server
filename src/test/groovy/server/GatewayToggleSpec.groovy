package server

import spock.lang.Unroll
import support.ToolSpecBase

// Spec for `useGateways`. Other feature toggles are enabled per-test so the
// gateway filter is the only narrowing.
class GatewayToggleSpec extends ToolSpecBase {

    def "default (no setting saved): tools/list contains gateway entries and hides proxied sub-tools"() {
        given: 'no useGateways setting — clear any harness flat-mode pre-seed to honor the test name'
        settingsMap.remove('useGateways')

        when:
        def tools = script.getToolDefinitions()
        def names = tools*.name as Set

        then: 'gateway entries appear'
        names.contains('manage_rooms')
        names.contains('manage_files')
        names.contains('manage_logs')

        and: 'sub-tools that live behind a gateway are NOT top-level'
        !names.contains('list_rooms')
        !names.contains('list_files')
        !names.contains('get_hub_logs')

        and: 'core tools still appear'
        names.contains('list_devices')
        names.contains('get_device')
        names.contains('search_tools')
    }

    def "default (null) and explicit useGateways=true produce identical tool lists"() {
        given:
        settingsMap.remove('useGateways')  // override harness flat-mode pre-seed
        def defaultNames = script.getToolDefinitions()*.name as Set

        when:
        settingsMap.useGateways = true
        def explicitTrueNames = script.getToolDefinitions()*.name as Set

        then:
        explicitTrueNames == defaultNames
    }

    def "useGateways=false: every tool advertised individually, gateway entries gone, search_tools hidden"() {
        given: 'gateways off; the feature toggles whose tools we expect to see are on'
        settingsMap.useGateways = false
        settingsMap.enableBuiltinApp = true
        settingsMap.enableCustomRuleEngine = true

        when:
        def tools = script.getToolDefinitions()
        def names = tools*.name as Set

        then: 'no gateway entries on tools/list'
        !names.contains('manage_rooms')
        !names.contains('manage_files')
        !names.contains('manage_logs')
        !names.contains('manage_diagnostics')
        !names.contains('manage_rules_admin')
        !names.contains('manage_native_rules_and_apps')
        !names.contains('manage_mcp_self')

        and: 'every previously-proxied sub-tool is now top-level'
        names.contains('list_rooms')
        names.contains('get_room')
        names.contains('list_files')
        names.contains('get_hub_logs')
        names.contains('get_zwave_details')
        names.contains('list_rm_rules')

        and: 'core tools still appear'
        names.contains('list_devices')
        names.contains('get_device')
        names.contains('custom_create_rule')

        and: 'search_tools is suppressed in flat mode (its purpose is finding tools hidden behind gateways)'
        !names.contains('search_tools')

        and: 'every entry has the MCP tool shape'
        tools.every {
            it.name instanceof String && !it.name.isEmpty() &&
            it.description instanceof String && !it.description.isEmpty() &&
            it.inputSchema instanceof Map
        }
    }

    def "useGateways=false: catalog equals all tools minus search_tools (no leaks, no drops)"() {
        given: 'all feature toggles on so the gateway-filter is the only narrowing'
        settingsMap.useGateways = false
        settingsMap.enableBuiltinApp = true
        settingsMap.enableCustomRuleEngine = true

        when:
        def flatNames = script.getToolDefinitions()*.name as Set
        def allNames = script.getAllToolDefinitions()*.name as Set

        then:
        flatNames == (allNames - 'search_tools')
    }

    @Unroll
    def "useGateways=false: gateway sub-tool '#subTool' is dispatchable by its real name"() {
        // Pins the load-bearing claim that every gateway sub-tool already has a top-level
        // case in executeTool(). Adding a new sub-tool to getGatewayConfig() without a
        // matching case here would silently break flat mode for that whole gateway.
        given:
        settingsMap.useGateways = false
        settingsMap.enableBuiltinApp = true
        settingsMap.enableCustomRuleEngine = true

        when:
        try {
            script.executeTool(subTool, [:])
        } catch (IllegalArgumentException e) {
            // Tool-side validation IAE (missing required args, bad ID, etc.) is fine — it
            // means dispatch reached the handler. Only a default-case fallthrough is fatal.
            assert !e.message.startsWith("Unknown tool: ${subTool}"),
                   "executeTool fell through to default for sub-tool '${subTool}' — missing case in switch"
        } catch (Exception ignored) {
            // Hub-side failures (NPE on null devices etc.) also indicate dispatch reached
            // the handler. Pass.
        }

        then:
        notThrown(AssertionError)

        where:
        subTool << [
            'custom_delete_rule', 'custom_test_rule', 'custom_export_rule', 'custom_import_rule', 'custom_clone_rule',
            'list_variables', 'get_variable', 'set_variable', 'delete_variable',
            'list_rooms', 'get_room', 'create_room', 'delete_room', 'rename_room',
            'reboot_hub', 'shutdown_hub', 'delete_device',
            'list_hub_apps', 'list_hub_drivers', 'get_app_source', 'get_driver_source',
            'list_item_backups', 'get_item_backup',
            'install_app', 'install_driver', 'update_app_code', 'update_driver_code',
            'delete_app', 'delete_driver', 'restore_item_backup',
            'get_hub_logs', 'get_device_history', 'get_performance_stats', 'get_hub_jobs',
            'get_debug_logs', 'clear_debug_logs', 'set_log_level', 'get_logging_status',
            'get_set_hub_metrics', 'get_memory_history', 'force_garbage_collection',
            'device_health_check', 'custom_get_rule_diagnostics',
            'get_zwave_details', 'get_zigbee_details', 'zwave_repair',
            'list_captured_states', 'delete_captured_state', 'clear_captured_states',
            'list_files', 'read_file', 'write_file', 'delete_file',
            'list_installed_apps', 'get_device_in_use_by', 'get_app_config', 'list_app_pages',
            'list_rm_rules', 'run_rm_rule', 'pause_rm_rule', 'resume_rm_rule', 'set_rm_rule_boolean',
            'create_native_app', 'update_native_app', 'delete_native_app', 'check_rule_health',
            'update_mcp_settings'
        ]
    }

    def "useGateways=false: dispatch still works for a sub-tool called by its real name"() {
        given:
        settingsMap.useGateways = false
        script.metaClass.getRooms = { ->
            [[id: 1L, name: 'Living Room']]
        }

        when:
        def result = script.executeTool('list_rooms', [:])

        then:
        result.rooms*.name == ['Living Room']
    }

    def "useGateways=false: calling a gateway name returns isError pointing at sub-tools"() {
        given:
        settingsMap.useGateways = false

        when:
        def result = script.executeTool('manage_rooms', [tool: 'list_rooms', args: [:]])

        then:
        result.isError == true
        result.error.contains('manage_rooms')
        result.error.contains('disabled')
        result.hint.contains('list_rooms')
        result.hint.contains('rename_room')
    }

    def "useGateways=true (default): calling a gateway name still dispatches normally"() {
        given:
        settingsMap.useGateways = true  // override harness flat-mode pre-seed
        script.metaClass.getRooms = { ->
            [[id: 1L, name: 'Living Room']]
        }

        when:
        def result = script.executeTool('manage_rooms', [tool: 'list_rooms', args: [:]])

        then:
        result.isError != true
        result.rooms*.name == ['Living Room']
    }

    def "useGateways=false + enableBuiltinApp=false: built-in-app tools still hidden in the flat catalog"() {
        // Pins that the flat-mode branch reuses hideByName — a refactor that splits
        // hide-list construction out of the gateway-mode path would silently leak
        // list_rm_rules / create_native_app etc. into flat mode.
        given:
        settingsMap.useGateways = false
        settingsMap.enableBuiltinApp = false
        settingsMap.enableCustomRuleEngine = true

        when:
        def names = script.getToolDefinitions()*.name as Set

        then: 'built-in-app tools are removed from the flat catalog, not just from gateway entries'
        !names.contains('list_rm_rules')
        !names.contains('create_native_app')
        !names.contains('list_installed_apps')
        !names.contains('check_rule_health')

        and: 'tools that do not depend on enableBuiltinApp are still present'
        names.contains('get_app_config')
        names.contains('list_devices')
    }

    def "useGateways=false + enableCustomRuleEngine=false (readonly): write-side custom_* tools hidden"() {
        // Same shape as the enableBuiltinApp test, but for the readonly customEngineMode
        // path: read tools stay visible, write/structural tools are removed.
        given:
        settingsMap.useGateways = false
        settingsMap.enableBuiltinApp = true
        settingsMap.enableCustomRuleEngine = false

        when:
        def names = script.getToolDefinitions()*.name as Set

        then: 'write/structural custom_* tools are removed'
        !names.contains('custom_create_rule')
        !names.contains('custom_delete_rule')
        !names.contains('custom_export_rule')
        !names.contains('custom_import_rule')
        !names.contains('custom_clone_rule')

        and: 'read-side custom_* tools remain'
        names.contains('custom_list_rules')
        names.contains('custom_get_rule')
        names.contains('custom_test_rule')
        names.contains('custom_get_rule_diagnostics')
    }

    def "useGateways=false + builtin/custom both off: gateway-name hint omits hidden sub-tools"() {
        // The flat-mode guard's hint must filter through hideByName — telling a stale
        // client to call list_rm_rules when it's also disabled by enableBuiltinApp=false
        // would just trade one error for another.
        given:
        settingsMap.useGateways = false
        settingsMap.enableBuiltinApp = false
        settingsMap.enableCustomRuleEngine = false

        when: 'every sub-tool of manage_native_rules_and_apps is hidden by enableBuiltinApp=false'
        def result = script.executeTool('manage_native_rules_and_apps', [tool: 'list_rm_rules', args: [:]])

        then: 'guard fires, hint does not name any of the hidden sub-tools'
        result.isError == true
        !result.hint.contains('list_rm_rules')
        !result.hint.contains('create_native_app')

        and: 'hint mentions the responsible toggles instead'
        result.hint.contains('Built-in App Tools') || result.hint.contains('Custom Rule Engine')
    }
}
