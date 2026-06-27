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
        names.contains('hub_manage_rooms')
        names.contains('hub_manage_files')
        names.contains('hub_manage_logs')

        and: 'sub-tools that live behind a gateway are NOT top-level'
        !names.contains('hub_list_rooms')
        !names.contains('hub_list_files')
        !names.contains('hub_get_logs')

        and: 'device + custom-rule tools are now proxied behind gateways, NOT top-level'
        !names.contains('hub_list_devices')
        !names.contains('hub_get_device')
        !names.contains('hub_get_custom_rule')

        and: 'flat (always top-level) tools still appear'
        names.contains('hub_get_info')
        names.contains('hub_get_hsm_status')
        names.contains('hub_search_tools')
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

    def "useGateways=false: every tool advertised individually, gateway entries gone, hub_search_tools hidden"() {
        given: 'gateways off; the feature toggles whose tools we expect to see are on'
        settingsMap.useGateways = false
        settingsMap.enableCustomRuleEngine = true

        when:
        def tools = script.getToolDefinitions()
        def names = tools*.name as Set

        then: 'no gateway entries on tools/list'
        !names.contains('hub_manage_rooms')
        !names.contains('hub_manage_files')
        !names.contains('hub_manage_logs')
        !names.contains('hub_manage_diagnostics')
        !names.contains('hub_manage_radio')
        !names.contains('hub_manage_custom_rules')
        !names.contains('hub_manage_native_rules_and_apps')
        !names.contains('hub_manage_mcp')

        and: 'every previously-proxied sub-tool is now top-level'
        names.contains('hub_list_rooms')
        names.contains('hub_get_room')
        names.contains('hub_list_files')
        names.contains('hub_get_logs')
        names.contains('hub_get_radio_details')
        names.contains('hub_set_zwave')
        names.contains('hub_call_zwave')
        names.contains('hub_list_rules')

        and: 'core tools still appear'
        names.contains('hub_list_devices')
        names.contains('hub_get_device')
        names.contains('hub_create_custom_rule')

        and: 'hub_search_tools is suppressed in flat mode (its purpose is finding tools hidden behind gateways)'
        !names.contains('hub_search_tools')

        and: 'every entry has the MCP tool shape'
        tools.every {
            it.name instanceof String && !it.name.isEmpty() &&
            it.description instanceof String && !it.description.isEmpty() &&
            it.inputSchema instanceof Map
        }
    }

    def "useGateways=false: catalog equals all tools minus hub_search_tools (no leaks, no drops)"() {
        given: 'all feature toggles on so the gateway-filter is the only narrowing'
        settingsMap.useGateways = false
        settingsMap.enableCustomRuleEngine = true
        // Developer Mode is a feature toggle that narrows the catalog too: dev-mode-only
        // tools (hub_update_package) are hidden when it's off. Turn it on so the ONLY
        // narrowing under test is the flat-mode hub_search_tools suppression.
        settingsMap.enableDeveloperMode = true

        when:
        def flatNames = script.getToolDefinitions()*.name as Set
        def allNames = script.getAllToolDefinitions()*.name as Set

        then:
        flatNames == (allNames - 'hub_search_tools')
    }

    @Unroll
    def "useGateways=false: gateway sub-tool '#subTool' is dispatchable by its real name"() {
        // Pins the load-bearing claim that every gateway sub-tool already has a top-level
        // case in executeTool(). Adding a new sub-tool to getGatewayConfig() without a
        // matching case here would silently break flat mode for that whole gateway.
        given:
        settingsMap.useGateways = false
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
            'hub_delete_custom_rule', 'hub_test_custom_rule', 'hub_export_custom_rule', 'hub_import_custom_rule', 'hub_clone_custom_rule',
            'hub_list_variables', 'hub_get_variable', 'hub_set_variable', 'hub_delete_variable',
            'hub_list_rooms', 'hub_get_room', 'hub_create_room', 'hub_delete_room', 'hub_update_room',
            'hub_reboot', 'hub_shutdown', 'hub_delete_device',
            'hub_list_apps', 'hub_list_drivers', 'hub_get_source',
            'hub_list_backups', 'hub_get_backup',
            'hub_create_app', 'hub_create_driver', 'hub_update_app', 'hub_update_driver',
            'hub_delete_item', 'hub_restore_backup', 'hub_delete_backup',
            'hub_get_logs', 'hub_get_performance_stats', 'hub_get_jobs',
            'hub_get_debug_logs', 'hub_delete_debug_logs', 'hub_set_log_level',
            'hub_get_metrics', 'hub_get_memory_history', 'hub_call_gc',
            'hub_get_device_health',
            'hub_get_radio_details',
            'hub_set_zwave', 'hub_set_zigbee',
            'hub_call_zwave', 'hub_call_zigbee', 'hub_call_matter', 'hub_call_destructive_ops',
            'hub_list_captured_states', 'hub_delete_captured_state',
            'hub_list_files', 'hub_read_file', 'hub_write_file', 'hub_delete_file',
            'hub_list_device_dependents', 'hub_get_app_config', 'hub_list_app_pages',
            'hub_list_rules', 'hub_call_rule', 'hub_set_rule_paused', 'hub_set_rule_private_boolean',
            'hub_set_rule', 'hub_set_native_app', 'hub_delete_native_app', 'hub_get_rule_health',
            'hub_get_visual_rule', 'hub_set_visual_rule', 'hub_delete_visual_rule',
            'hub_list_dashboards', 'hub_get_dashboard', 'hub_create_dashboard',
            'hub_update_dashboard', 'hub_delete_dashboard', 'hub_clone_dashboard',
            'hub_update_mcp_settings',
            // Dev-mode-only: executeTool still routes it (the catch tolerates the
            // "Developer Mode tools are disabled" IAE -- that proves the dispatch case exists).
            'hub_update_package'
        ]
    }

    def "every gateway sub-tool has an executeTool dispatch case (derived from getGatewayConfig -- no silent gaps)"() {
        // Root-cause guard for the @Unroll above: that list is hand-maintained, so a NEW gateway
        // sub-tool added to getGatewayConfig() without a dispatch case (or merely omitted from the
        // curated list) would slip through. This derives the sub-tool set straight from the config,
        // so every gateway sub-tool is dispatch-checked automatically and forever.
        given:
        settingsMap.useGateways = false
        settingsMap.enableCustomRuleEngine = true
        def subTools = script.getGatewayConfig().values()*.tools.flatten().unique()

        expect:
        subTools.every { st ->
            boolean reachedHandler = true
            try {
                script.executeTool(st, [:])
            } catch (IllegalArgumentException e) {
                // Tool-side validation IAE / dev-mode-disabled IAE both prove dispatch reached a
                // handler. Only the executeTool default-case fallthrough is fatal.
                reachedHandler = !e.message.startsWith("Unknown tool: ${st}")
            } catch (Exception ignored) {
                // Hub-side failures (null devices, etc.) also mean dispatch reached the handler.
            }
            assert reachedHandler, "executeTool fell through to default for gateway sub-tool '${st}' -- missing dispatch case"
            reachedHandler
        }
    }

    def "every gateway in getGatewayConfig routes through executeTool's dispatch (no missing gateway case)"() {
        // executeTool routes gateway NAMES to handleGateway via a hand-maintained case list that is
        // SEPARATE from getGatewayConfig(). A gateway added to the config without a matching case falls
        // through to the Unknown-tool default on the REAL tools/call path -- a spec that calls
        // handleGateway() directly misses it (that gap shipped hub_manage_backup with no case and only
        // the full e2e lane caught it). Derive from the config so the two lists can't drift.
        given:
        settingsMap.useGateways = true

        expect:
        script.getGatewayConfig().keySet().every { gw ->
            boolean routed = true
            try {
                script.executeTool(gw, [:])   // no sub-tool -> gateway catalog; must NOT hit the default
            } catch (IllegalArgumentException e) {
                routed = !e.message.startsWith("Unknown tool: ${gw}")
            } catch (Exception ignored) {
            }
            assert routed, "executeTool has no gateway-routing case for '${gw}' -- it's in getGatewayConfig() but missing from the executeTool switch"
            routed
        }
    }

    def "useGateways=false: dispatch still works for a sub-tool called by its real name"() {
        given:
        settingsMap.useGateways = false
        script.metaClass.getRooms = { ->
            [[id: 1L, name: 'Living Room']]
        }

        when:
        def result = script.executeTool('hub_list_rooms', [:])

        then:
        result.rooms*.name == ['Living Room']
    }

    def "useGateways=false: calling a gateway name returns isError pointing at sub-tools"() {
        given:
        settingsMap.useGateways = false

        when:
        def result = script.executeTool('hub_manage_rooms', [tool: 'hub_list_rooms', args: [:]])

        then:
        result.isError == true
        result.error.contains('hub_manage_rooms')
        result.error.contains('disabled')
        result.hint.contains('hub_list_rooms')
        result.hint.contains('hub_update_room')
    }

    def "useGateways=true (default): calling a gateway name still dispatches normally"() {
        given:
        settingsMap.useGateways = true  // override harness flat-mode pre-seed
        script.metaClass.getRooms = { ->
            [[id: 1L, name: 'Living Room']]
        }

        when:
        def result = script.executeTool('hub_manage_rooms', [tool: 'hub_list_rooms', args: [:]])

        then:
        result.isError != true
        result.rooms*.name == ['Living Room']
    }

    def "useGateways=false + enableWrite=false: write native tools hidden in the flat catalog while read native tools remain"() {
        // Pins that the flat-mode branch reuses hideByName — a refactor that splits
        // hide-list construction out of the gateway-mode path would silently leak the
        // write native tools (hub_set_rule / hub_call_rule etc.) into flat mode.
        // With the Built-in App toggle removed (#113), the native RM tools are governed
        // by the universal masters: write natives by the Write master, read natives by Read.
        given:
        settingsMap.useGateways = false
        settingsMap.enableWrite = false
        settingsMap.enableCustomRuleEngine = true

        when:
        def names = script.getToolDefinitions()*.name as Set

        then: 'write native tools are removed from the flat catalog, not just from gateway entries'
        !names.contains('hub_set_rule')
        !names.contains('hub_set_native_app')
        !names.contains('hub_delete_native_app')
        !names.contains('hub_call_rule')
        !names.contains('hub_set_rule_paused')

        and: 'read native tools stay visible (Read master is still ON)'
        names.contains('hub_list_rules')
        names.contains('hub_list_device_dependents')
        names.contains('hub_get_rule_health')

        and: 'read tools outside the native group are still present'
        names.contains('hub_get_app_config')
        names.contains('hub_list_apps')
        names.contains('hub_list_devices')
    }

    def "useGateways=false + enableCustomRuleEngine=false (readonly): write-side custom_* tools hidden"() {
        // Same shape as the Write-master test above, but for the readonly customEngineMode
        // path: read tools stay visible, write/structural tools are removed.
        given:
        settingsMap.useGateways = false
        settingsMap.enableCustomRuleEngine = false

        when:
        def names = script.getToolDefinitions()*.name as Set

        then: 'write/structural custom_* tools are removed'
        !names.contains('hub_create_custom_rule')
        !names.contains('hub_delete_custom_rule')
        !names.contains('hub_export_custom_rule')
        !names.contains('hub_import_custom_rule')
        !names.contains('hub_clone_custom_rule')

        and: 'read-side custom_* tools remain (hub_get_custom_rule now also serves list + diagnostics modes)'
        names.contains('hub_get_custom_rule')
        names.contains('hub_test_custom_rule')
    }

    def "useGateways=true + enableCustomRuleEngine=false (readonly): gateway sub-tool catalogs shrink to read-only tools"() {
        // Pins the unified-hideByName invariant: a name in hideByName must be filtered
        // out of every gateway sub-tool catalog AND the input-schema enum, not just
        // out of flat-mode base-tool placement. Previously two parallel lists
        // (hideByName + hideGatewaySubTools) could drift; now hideByName is the single
        // source of truth. Regression guard against a future refactor splitting them
        // back apart and silently advertising sub-tools that fail at executeTool.
        given:
        settingsMap.useGateways = true
        settingsMap.enableCustomRuleEngine = false  // customEngineMode = readonly

        when:
        def tools = script.getToolDefinitions()
        def rulesAdmin = tools.find { it.name == 'hub_manage_custom_rules' }

        then: 'gateway entry still appears (hub_test_custom_rule remains visible)'
        rulesAdmin != null

        and: 'write/structural sub-tools are removed from the catalog prose'
        !rulesAdmin.description.contains('hub_delete_custom_rule')
        !rulesAdmin.description.contains('hub_export_custom_rule')
        !rulesAdmin.description.contains('hub_import_custom_rule')
        !rulesAdmin.description.contains('hub_clone_custom_rule')

        and: 'and from the input-schema tool enum (clients should not be offered them)'
        def toolEnum = rulesAdmin.inputSchema.properties.tool.enum as Set
        !toolEnum.contains('hub_delete_custom_rule')
        !toolEnum.contains('hub_export_custom_rule')
        !toolEnum.contains('hub_import_custom_rule')
        !toolEnum.contains('hub_clone_custom_rule')

        and: 'the surviving read sub-tool is still offered'
        toolEnum.contains('hub_test_custom_rule')
    }

    def "useGateways=false + both masters off: gateway-name hint omits hidden sub-tools"() {
        // The flat-mode guard's hint must filter through getHiddenToolNames() — telling a
        // stale client to call hub_list_rules when it's also disabled by the Read master
        // would just trade one error for another. hub_manage_native_rules_and_apps mixes
        // read sub-tools (hub_list_rules, hub_get_rule_health) and write sub-tools, so both
        // masters must be OFF to hide the whole gateway.
        given:
        settingsMap.useGateways = false
        settingsMap.enableRead = false
        settingsMap.enableWrite = false

        when: 'every sub-tool of hub_manage_native_rules_and_apps is hidden by the Read+Write masters'
        def result = script.executeTool('hub_manage_native_rules_and_apps', [tool: 'hub_list_rules', args: [:]])

        then: 'guard fires, hint does not name any of the hidden sub-tools'
        result.isError == true
        !result.hint.contains('hub_list_rules')
        !result.hint.contains('hub_set_native_app')

        and: 'hint mentions the responsible masters instead'
        result.hint.contains('Read/Write masters') || result.hint.contains('Custom Rule Engine')
    }
}
