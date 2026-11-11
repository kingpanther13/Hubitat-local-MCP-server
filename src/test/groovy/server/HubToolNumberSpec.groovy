package server

import spock.lang.Unroll
import support.ToolSpecBase

// Covers the optional hub_x -> hub<d>_x tool-name numbering feature: the
// _hubToolNumber/_externalToolName/_internalToolName helpers, the tools/list
// rename (including gateway sub-tool enums + "Available tools:" catalog text),
// hub_search_tools result renaming, and the tools/call dispatch rewrite of
// both params.name and a gateway call's arguments.tool.
class HubToolNumberSpec extends ToolSpecBase {

    // ---- _hubToolNumber() ----

    def "hubToolNumber: default settings (unset) yields null"() {
        expect:
        script._hubToolNumber() == null
    }

    @Unroll
    def "hubToolNumber: enabled/number combinations map to the expected digit or null"() {
        given:
        settingsMap.enableHubToolNumber = enabled
        if (number != null) settingsMap.hubToolNumber = number

        expect:
        script._hubToolNumber() == expected

        where:
        enabled | number | expected
        false   | '3'    | null
        true    | null   | null
        true    | '3'    | '3'
        true    | '0'    | '0'
        true    | '9'    | '9'
        true    | '12'   | null
        true    | 'a'    | null
        true    | ''     | null
        true    | '-1'   | null
    }

    // ---- _externalToolName / _internalToolName ----

    def "toolName mapping: disabled settings pass names through unchanged"() {
        expect:
        script._externalToolName('hub_get_info') == 'hub_get_info'
        script._internalToolName('hub_get_info') == 'hub_get_info'
    }

    def "toolName mapping: enabled with 3 renames hub_ names and leaves everything else alone"() {
        given:
        settingsMap.enableHubToolNumber = true
        settingsMap.hubToolNumber = '3'

        expect: 'hub_ prefixed names gain the digit'
        script._externalToolName('hub_get_info') == 'hub3_get_info'
        script._externalToolName('hub_manage_rooms') == 'hub3_manage_rooms'

        and: 'a non-hub_ name is untouched'
        script._externalToolName('mcp_get_info') == 'mcp_get_info'

        and: 'internal mapping reverses it, and a plain hub_ name is still accepted'
        script._internalToolName('hub3_get_info') == 'hub_get_info'
        script._internalToolName('hub_get_info') == 'hub_get_info'

        and: 'a different digit prefix is left alone (not this install\'s number)'
        script._internalToolName('hub4_get_info') == 'hub4_get_info'

        and: 'round-trip'
        script._internalToolName(script._externalToolName('hub_get_info')) == 'hub_get_info'
    }

    // ---- handleToolsList: tool-name rename ----

    @Unroll
    def "handleToolsList: default settings leave every name starting with hub_ in both catalog modes"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableCustomRuleEngine = true

        when:
        def names = script.handleToolsList([id: 1, params: [:]]).result.tools*.name

        then:
        names.every { (it as String).startsWith('hub_') }

        where:
        useGateways << [true, false]
    }

    @Unroll
    def "handleToolsList: enabled with 3 renames every tool to hub3_ and preserves the underlying set in both catalog modes"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableCustomRuleEngine = true
        def defaultNames = script.handleToolsList([id: 1, params: [:]]).result.tools*.name as Set

        when:
        settingsMap.enableHubToolNumber = true
        settingsMap.hubToolNumber = '3'
        def renamedNames = script.handleToolsList([id: 2, params: [:]]).result.tools*.name as Set

        then:
        renamedNames.every { (it as String).startsWith('hub3_') }
        renamedNames.every { !(it as String).startsWith('hub_') }
        renamedNames.collect { (it as String).replaceFirst(/^hub3_/, 'hub_') } as Set == defaultNames

        where:
        useGateways << [true, false]
    }

    @Unroll
    def "handleToolsList: enableHubToolNumber on with an unset or invalid hubToolNumber does not rename"() {
        given:
        settingsMap.useGateways = true
        settingsMap.enableHubToolNumber = true
        if (hubToolNumber != null) settingsMap.hubToolNumber = hubToolNumber

        when:
        def names = script.handleToolsList([id: 1, params: [:]]).result.tools*.name

        then:
        names.every { (it as String).startsWith('hub_') }

        where:
        hubToolNumber << [null, '12', 'a', '', '-1', '99']
    }

    def "handleToolsList: gateway tool enum and Available tools catalog use hub3_ names when enabled, unrenamed by default"() {
        given:
        settingsMap.useGateways = true
        settingsMap.enableCustomRuleEngine = true

        when: 'default (numbering disabled)'
        def defaultGateways = script.handleToolsList([id: 1, params: [:]]).result.tools.findAll {
            it.inputSchema?.properties?.tool?.enum
        }

        then:
        defaultGateways.size() > 0
        defaultGateways.every { gw ->
            gw.inputSchema.properties.tool.enum.every { (it as String).startsWith('hub_') } &&
            gw.inputSchema.properties.tool.enum.every { gw.description.contains(it as String) }
        }

        when: 'enabled with 3'
        settingsMap.enableHubToolNumber = true
        settingsMap.hubToolNumber = '3'
        def renamedGateways = script.handleToolsList([id: 2, params: [:]]).result.tools.findAll {
            it.inputSchema?.properties?.tool?.enum
        }

        then:
        renamedGateways.size() == defaultGateways.size()
        renamedGateways.every { gw ->
            (gw.name as String).startsWith('hub3_') &&
            gw.inputSchema.properties.tool.enum.every { (it as String).startsWith('hub3_') } &&
            gw.inputSchema.properties.tool.enum.every { gw.description.contains(it as String) }
        }
    }

    // ---- tools/call dispatch: params.name + gateway arguments.tool rewrite ----

    def "tools/call dispatch: hub3_get_info reaches hub_get_info"() {
        given:
        settingsMap.enableHubToolNumber = true
        settingsMap.hubToolNumber = '3'
        script.metaClass.toolGetHubInfo = { a -> [model: 'C-8'] }

        when:
        def response = mcpDriver.callTool('hub3_get_info', [:])

        then:
        response.error == null
        mcpDriver.parseInner(response).model == 'C-8'
    }

    def "tools/call dispatch: plain hub_get_info still works when numbering is enabled"() {
        given:
        settingsMap.enableHubToolNumber = true
        settingsMap.hubToolNumber = '3'
        script.metaClass.toolGetHubInfo = { a -> [model: 'C-8'] }

        when:
        def response = mcpDriver.callTool('hub_get_info', [:])

        then:
        response.error == null
        mcpDriver.parseInner(response).model == 'C-8'
    }

    def "tools/call dispatch: gateway call with hub3_ gateway name and hub3_ sub-tool reaches the internal sub-tool"() {
        given:
        settingsMap.useGateways = true
        settingsMap.enableHubToolNumber = true
        settingsMap.hubToolNumber = '3'
        script.metaClass.getRooms = { -> [[id: 1L, name: 'Living Room']] }

        when:
        def response = mcpDriver.callTool('hub3_read_rooms', [tool: 'hub3_list_rooms', args: [:]])

        then:
        response.error == null
        mcpDriver.parseInner(response).rooms*.name == ['Living Room']
    }

    def "tools/call dispatch: a plain hub_ sub-tool name is also accepted under a hub3_ gateway call"() {
        given:
        settingsMap.useGateways = true
        settingsMap.enableHubToolNumber = true
        settingsMap.hubToolNumber = '3'
        script.metaClass.getRooms = { -> [[id: 1L, name: 'Living Room']] }

        when:
        def response = mcpDriver.callTool('hub3_read_rooms', [tool: 'hub_list_rooms', args: [:]])

        then:
        response.error == null
        mcpDriver.parseInner(response).rooms*.name == ['Living Room']
    }

    def "tools/call dispatch: a fully plain (legacy) gateway call still works when numbering is enabled"() {
        given:
        settingsMap.useGateways = true
        settingsMap.enableHubToolNumber = true
        settingsMap.hubToolNumber = '3'
        script.metaClass.getRooms = { -> [[id: 1L, name: 'Living Room']] }

        when:
        def response = mcpDriver.callTool('hub_read_rooms', [tool: 'hub_list_rooms', args: [:]])

        then:
        response.error == null
        mcpDriver.parseInner(response).rooms*.name == ['Living Room']
    }

    def "tools/call dispatch: a hub3_ gateway called with no sub-tool discloses hub3_ sub-tool names in its catalog"() {
        given:
        settingsMap.useGateways = true
        settingsMap.enableHubToolNumber = true
        settingsMap.hubToolNumber = '3'

        when:
        def response = mcpDriver.callTool('hub3_read_rooms', [:])

        then:
        response.error == null
        def inner = mcpDriver.parseInner(response)
        inner.mode == 'catalog'
        inner.tools*.name.every { (it as String).startsWith('hub3_') }
        (inner.tools*.name as Set).collect { (it as String).replaceFirst(/^hub3_/, 'hub_') } as Set ==
            ['hub_list_rooms', 'hub_get_room'] as Set
    }

    def "tools/call dispatch: hub_search_tools results carry hub3_ tool names when enabled"() {
        given:
        settingsMap.useGateways = true
        settingsMap.enableCustomRuleEngine = true
        settingsMap.enableHubToolNumber = true
        settingsMap.hubToolNumber = '3'

        when:
        def response = mcpDriver.callTool('hub3_search_tools', [query: 'list', maxResults: 20])

        then:
        response.error == null
        def inner = mcpDriver.parseInner(response)
        inner.results.size() > 0
        inner.results.every { (it.tool as String).startsWith('hub3_') }
        inner.results.every { it.gateway == null || (it.gateway as String).startsWith('hub3_') }
    }
}
