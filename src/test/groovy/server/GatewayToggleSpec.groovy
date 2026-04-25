package server

import support.ToolSpecBase

/**
 * Spec for the `useGateways` setting on getToolDefinitions().
 *
 * Default + null + true: tools/list returns the consolidated catalog
 * (22 core + 11 gateway entries). Explicit false: tools/list returns
 * every tool individually and search_tools is hidden because its only
 * purpose is mapping a query to a hidden sub-tool's gateway.
 *
 * Dispatch is unchanged in either mode — every gateway sub-tool already
 * has its own case in executeTool(), so a flat-mode client calling
 * `list_rooms` directly hits the same handler as a gateway-mode client
 * calling `manage_rooms` with `tool: "list_rooms"`.
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

    def "useGateways=true: same as default"() {
        given:
        settingsMap.useGateways = true

        when:
        def names = script.getToolDefinitions()*.name as Set

        then:
        names.contains('manage_rooms')
        !names.contains('list_rooms')
        names.contains('search_tools')
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

    def "useGateways=false: count is the full tool inventory minus search_tools"() {
        given:
        settingsMap.useGateways = false

        when:
        def flatNames = script.getToolDefinitions()*.name as Set
        def allNames = script.getAllToolDefinitions()*.name as Set

        then: 'flat-mode list equals all tools minus search_tools (no gateway entries leaked in, no tools dropped)'
        flatNames == (allNames - 'search_tools')
    }

    def "useGateways=false: dispatch still works for a sub-tool called by its real name"() {
        // The whole point of the toggle is that flat-mode clients call sub-tools directly.
        // executeTool routes every sub-tool by name (no gateway prefix needed) — this pins
        // that contract.
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
}
