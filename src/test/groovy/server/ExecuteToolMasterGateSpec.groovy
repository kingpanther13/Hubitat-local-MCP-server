package server

import support.ToolSpecBase

/**
 * Central Read/Write master gate at the executeTool dispatch chokepoint (issue #113).
 * Masters default ON: only an explicit `== false` blocks. Read tools (in
 * getReadOnlyToolNames()) are gated by the Read master; every other (write) tool by
 * the Write master. Gateway NAMES are skipped here (gated per sub-tool on re-entry).
 */
class ExecuteToolMasterGateSpec extends ToolSpecBase {

    def "read tool is blocked when Read master is OFF"() {
        given:
        settingsMap.enableRead = false
        settingsMap.enableWrite = true

        when:
        script.executeTool("hub_list_modes", [:])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("Read tools are disabled")
    }

    def "write tool is blocked when Write master is OFF"() {
        given:
        settingsMap.enableRead = true
        settingsMap.enableWrite = false

        when:
        script.executeTool("hub_manage_mode", [action: "activate", mode: "Day"])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("Write tools are disabled")
    }

    def "both masters default ON (unset) -- neither read nor write is hidden/blocked by the master gate"() {
        given:
        settingsMap.remove('enableRead')
        settingsMap.remove('enableWrite')

        expect: "the master gate hides neither reads nor writes by default"
        def hidden = script.getHiddenToolNames()
        !hidden.contains("hub_list_modes")   // a read
        !hidden.contains("hub_manage_mode")     // a write

        and: "a stub-free read tool dispatches past the master gate (no 'disabled' error)"
        script.executeTool("hub_get_tool_guide", [:]) != null
    }

    def "gateway name is NOT classified as a write -- read gateway dispatches with Write OFF"() {
        given:
        settingsMap.enableRead = true
        settingsMap.enableWrite = false

        when: "calling a pure-read gateway with no sub-tool returns its catalog, not a write-block"
        def result = script.executeTool("hub_read_devices", [:])

        then:
        noExceptionThrown()
        result != null
    }

    def "hub_export_native_app (write, no longer Built-in-App-gated) is blocked by the Write master"() {
        // It lost its requireBuiltinApp() gate and has no requireDestructiveConfirm,
        // so the central Write master is now its only protection.
        given:
        settingsMap.enableWrite = false

        when:
        script.executeTool("hub_export_native_app", [appId: 1])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("Write tools are disabled")
    }

    def "hub_list_rules (read, formerly Built-in-App-gated) is blocked by the Read master"() {
        given:
        settingsMap.enableRead = false

        when:
        script.executeTool("hub_list_rules", [:])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("Read tools are disabled")
    }
}
