package server

import spock.lang.Unroll
import support.ToolSpecBase

/**
 * Spec for the `useGateways` setting on getToolDefinitions() + flat-mode dispatch.
 *
 * Default/null/true: 22 core + 11 gateway entries on tools/list. Explicit false:
 * every tool advertised individually, search_tools dropped, gateway-name calls
 * rejected with a friendly isError.
 */
class GatewayToggleSpec extends ToolSpecBase {

    def "default (no setting saved): tools/list contains gateway entries and hides proxied sub-tools"() {
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
        // Two settings code paths (null falls through, true also falls through) — pin
        // they actually emit the same catalog. A future refactor that splits these
        // would break update behavior for existing installs.
        given:
        def defaultNames = script.getToolDefinitions()*.name as Set

        when:
        settingsMap.useGateways = true
        def explicitTrueNames = script.getToolDefinitions()*.name as Set

        then:
        explicitTrueNames == defaultNames
    }

    def "useGateways=false: every tool advertised individually, gateway entries gone, search_tools hidden"() {
        given:
        settingsMap.useGateways = false

        when:
        def tools = script.getToolDefinitions()
        def names = tools*.name as Set

        then: 'no gateway entries on tools/list'
        !names.contains('manage_rooms')
        !names.contains('manage_files')
        !names.contains('manage_logs')
        !names.contains('manage_diagnostics')
        !names.contains('manage_rules_admin')
        !names.contains('manage_rule_machine')

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
        names.contains('create_rule')

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
        given:
        settingsMap.useGateways = false

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
            'delete_rule', 'test_rule', 'export_rule', 'import_rule', 'clone_rule',
            'list_variables', 'get_variable', 'set_variable',
            'list_rooms', 'get_room', 'create_room', 'delete_room', 'rename_room',
            'reboot_hub', 'shutdown_hub', 'delete_device',
            'list_hub_apps', 'list_hub_drivers', 'get_app_source', 'get_driver_source',
            'list_item_backups', 'get_item_backup',
            'install_app', 'install_driver', 'update_app_code', 'update_driver_code',
            'delete_app', 'delete_driver', 'restore_item_backup',
            'get_hub_logs', 'get_device_history', 'get_performance_stats', 'get_hub_jobs',
            'get_debug_logs', 'clear_debug_logs', 'set_log_level', 'get_logging_status',
            'get_set_hub_metrics', 'get_memory_history', 'force_garbage_collection',
            'device_health_check', 'get_rule_diagnostics',
            'get_zwave_details', 'get_zigbee_details', 'zwave_repair',
            'list_captured_states', 'delete_captured_state', 'clear_captured_states',
            'list_files', 'read_file', 'write_file', 'delete_file',
            'list_installed_apps', 'get_device_in_use_by', 'get_app_config', 'list_app_pages',
            'list_rm_rules', 'run_rm_rule', 'pause_rm_rule', 'resume_rm_rule', 'set_rm_rule_boolean'
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
        // Stale clients that cached the old gateway-mode catalog will still call
        // 'manage_rooms' even after the user flipped to flat mode. Silently servicing
        // those calls with a gateway-shaped catalog response would contradict the
        // user's intent — instead return a soft isError that names the real sub-tools.
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
        // The flat-mode guard must NOT fire when the toggle is on (the default). This
        // pins that the guard is gated by the setting, not always-on.
        given:
        script.metaClass.getRooms = { ->
            [[id: 1L, name: 'Living Room']]
        }

        when: 'gateway-mode call to manage_rooms with a real sub-tool'
        def result = script.executeTool('manage_rooms', [tool: 'list_rooms', args: [:]])

        then: 'normal gateway dispatch — sub-tool result returned, no isError'
        result.isError != true
        result.rooms*.name == ['Living Room']
    }
}
