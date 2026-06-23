package server

import support.ToolSpecBase

/**
 * #114 advanced per-tool / per-gateway deny-only overrides. disabled_tools and
 * disabled_gateways feed getHiddenToolNames() (catalog + search) and the executeTool
 * dispatch guard. Disabling is GLOBAL: a tool shared across gateways is hidden
 * everywhere; disabling a gateway hides all its tools (including shared ones).
 */
class AdvancedOverridesFilterSpec extends ToolSpecBase {

    def "disabling a tool hides it from every gateway it appears in (global)"() {
        given: "hub_list_rooms is a member of both hub_read_rooms and hub_manage_rooms"
        settingsMap.disabled_tools = ["hub_list_rooms"]
        settingsMap.useGateways = true

        when:
        def hidden = script.getHiddenToolNames()
        def tools = script.getToolDefinitions()
        def readGw = tools.find { it.name == "hub_read_rooms" }
        def manageGw = tools.find { it.name == "hub_manage_rooms" }

        then:
        hidden.contains("hub_list_rooms")
        readGw != null
        manageGw != null
        !readGw.inputSchema.properties.tool.enum.contains("hub_list_rooms")
        !manageGw.inputSchema.properties.tool.enum.contains("hub_list_rooms")
    }

    def "disabling a gateway expands to all its tools, including shared ones"() {
        given:
        settingsMap.disabled_gateways = ["hub_manage_rooms"]

        when:
        def effective = script.getEffectiveDisabledTools()

        then: "every tool of hub_manage_rooms is in the effective disabled set"
        effective.containsAll(script.getGatewayConfig()["hub_manage_rooms"].tools)
    }

    def "a disabled gateway drops entirely from the catalog"() {
        given:
        settingsMap.disabled_gateways = ["hub_manage_rooms"]
        settingsMap.useGateways = true

        expect:
        !(script.getToolDefinitions()*.name.contains("hub_manage_rooms"))
    }

    def "calling an advanced-disabled tool returns a distinct Advanced-settings error"() {
        given:
        settingsMap.disabled_tools = ["hub_manage_mode"]
        settingsMap.enableWrite = true

        when:
        script.executeTool("hub_manage_mode", [action: "activate", mode: "Day"])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("Advanced settings")
        !e.message.contains("Write tools are disabled")
    }

    def "calling a disabled gateway returns the Advanced-settings error"() {
        given:
        settingsMap.disabled_gateways = ["hub_manage_rooms"]

        when:
        script.executeTool("hub_manage_rooms", [tool: "hub_list_rooms", args: [:]])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("Advanced settings")
    }

    def "no overrides set -- nothing extra is hidden"() {
        given:
        settingsMap.remove('disabled_tools')
        settingsMap.remove('disabled_gateways')

        expect:
        script.getEffectiveDisabledTools().isEmpty()
    }

    def "a stale/unknown gateway name in disabled_gateways is a safe no-op (no NPE)"() {
        given: "a gateway name persisted from before a rename, no longer in getGatewayConfig()"
        settingsMap.disabled_gateways = ["hub_nonexistent_gateway"]

        expect: "the ?. navigation makes expansion safe, and the catalog still builds"
        script.getEffectiveDisabledTools().isEmpty()
        script.getToolDefinitions() != null
    }
}
