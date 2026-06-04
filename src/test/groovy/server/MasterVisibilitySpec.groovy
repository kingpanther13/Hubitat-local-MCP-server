package server

import support.ToolSpecBase

/**
 * The two universal masters (issue #113) feed getHiddenToolNames(), which drives
 * the gateway catalog: a master turned OFF removes its whole class of tools, drops
 * pure-class gateways entirely, and flips a mixed gateway's read-only annotation.
 */
class MasterVisibilitySpec extends ToolSpecBase {

    def "Read master OFF hides every read tool from the gateway catalog and collapses pure-read gateways"() {
        given:
        settingsMap.enableRead = false
        settingsMap.enableWrite = true
        settingsMap.useGateways = true

        when:
        def tools = script.getToolDefinitions()
        def names = tools*.name

        then: "pure-read gateways drop entirely; a read base tool is gone"
        !names.contains("hub_read_devices")
        !names.contains("hub_list_modes")
        and: "a write-bearing gateway remains"
        names.contains("hub_manage_destructive_ops")
    }

    def "Write master OFF hides every write tool and flips a mixed gateway to read-only annotation"() {
        given:
        settingsMap.enableRead = true
        settingsMap.enableWrite = false
        settingsMap.useGateways = true

        when:
        def tools = script.getToolDefinitions()
        def rooms = tools.find { it.name == "hub_manage_rooms" }

        then: "the mixed rooms gateway still shows (it has reads) and is now read-only"
        rooms != null
        rooms.annotations.readOnlyHint == true
        and: "a write-only gateway drops"
        !(tools*.name.contains("hub_manage_destructive_ops"))
    }

    def "both masters ON (default) -- full catalog of 30 in the default config"() {
        given:
        settingsMap.remove('enableRead')
        settingsMap.remove('enableWrite')
        settingsMap.enableCustomRuleEngine = true
        settingsMap.useGateways = true

        expect:
        script.getToolDefinitions().size() == 30
    }
}
