package server

import support.ToolSpecBase

/**
 * Mandatory best-practice acknowledgment gate at the executeTool dispatch chokepoint
 * (issue #299, opt-in). When enableMandatoryBPS is ON every write tool requires the caller
 * to pass the acknowledgment key published by hub_get_tool_guide(section='best_practice_reference')
 * as the bestPracticeKey argument. Default OFF (null/unset/false = inactive, matching the #113
 * master-gate convention). hub_get_tool_guide (read) and hub_update_mcp_settings (self-disable)
 * are exempt so the caller can never lock itself out; gateway names short-circuit.
 */
class ExecuteToolMandatoryBpsGateSpec extends ToolSpecBase {

    def setup() {
        // hub_set_hsm is the representative write tool; stub its impl so a past-the-gate dispatch
        // returns a sentinel instead of touching the hub. The base setup() wiped the metaClass
        // first (superclass fixtures run before subclass), so this stub is fresh each feature.
        script.metaClass.toolSetHsm = { m -> [success: true, stubbed: true] }
        settingsMap.enableWrite = true
        settingsMap.enableRead = true
    }

    def "gate ON + missing key blocks a write tool with a guide-pointer message and never leaks the key"() {
        given:
        settingsMap.enableMandatoryBPS = true

        when:
        script.executeTool("hub_set_hsm", [armCommand: "armHome"])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("best-practice")
        e.message.contains("best_practice_reference")
        e.message.contains("bestPracticeKey")

        and: "the block message tells the LLM how to get the key but never contains the key itself"
        !e.message.contains(script.hubBpsGuideKey())
    }

    def "gate ON + wrong key blocks the write"() {
        given:
        settingsMap.enableMandatoryBPS = true

        when:
        script.executeTool("hub_set_hsm", [armCommand: "armHome", bestPracticeKey: "not-the-key"])

        then:
        thrown(IllegalArgumentException)
    }

    def "gate ON + correct key dispatches past the gate"() {
        given:
        settingsMap.enableMandatoryBPS = true

        when:
        def result = script.executeTool("hub_set_hsm", [armCommand: "armHome", bestPracticeKey: script.hubBpsGuideKey()])

        then:
        noExceptionThrown()
        result.stubbed == true
    }

    def "gate is ON by default (null/unset) -- a keyless write is blocked"() {
        given: "no explicit setting -- the gate ships ON (settings.enableMandatoryBPS != false)"
        settingsMap.remove('enableMandatoryBPS')

        when:
        script.executeTool("hub_set_hsm", [armCommand: "armHome"])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.startsWith("Mandatory best-practice")
    }

    def "gate is ON by default (null/unset) -- the correct key dispatches"() {
        given:
        settingsMap.remove('enableMandatoryBPS')

        expect:
        script.executeTool("hub_set_hsm", [armCommand: "armHome", bestPracticeKey: script.hubBpsGuideKey()]).stubbed == true
    }

    def "gate OFF (explicit false) leaves writes reachable without a key"() {
        given:
        settingsMap.enableMandatoryBPS = false

        expect:
        script.executeTool("hub_set_hsm", [armCommand: "armHome"]).stubbed == true
    }

    def "hub_get_tool_guide (read) is exempt -- always reachable to discover the key"() {
        given:
        settingsMap.enableMandatoryBPS = true

        when:
        def guide = script.executeTool("hub_get_tool_guide", [section: "best_practice_reference"])

        then:
        noExceptionThrown()
        guide != null
    }

    def "hub_update_mcp_settings is exempt -- the self-disable escape hatch is reachable"() {
        given: "gate ON, Developer Mode OFF (default)"
        settingsMap.enableMandatoryBPS = true

        when: "called WITHOUT the key -- the BPS gate must NOT fire; it reaches the tool's own dev-mode gate"
        script.executeTool("hub_update_mcp_settings", [settings: [enableMandatoryBPS: false], confirm: true])

        then: "the refusal is the dev-mode gate, NOT the BPS gate -- proves the exemption"
        def e = thrown(IllegalArgumentException)
        e.message.contains("Developer Mode")
        !e.message.startsWith("Mandatory best-practice")
    }

    def "hub_set_rule schema-only probe is exempt (mirrors the Write master exemption)"() {
        given:
        settingsMap.enableMandatoryBPS = true
        script.metaClass._isSetRuleSchemaOnlyCall = { a -> true }
        script.metaClass.toolSetRule = { a -> [success: true, schemaOnly: true] }

        when:
        def result = script.executeTool("hub_set_rule", [:])

        then:
        noExceptionThrown()
        result.schemaOnly == true
    }

    def "hub_set_native_app schema-only discover probe is exempt (mirrors the hub_set_rule exemption)"() {
        given:
        settingsMap.enableMandatoryBPS = true

        when: "edit-shaped discover meta-call, no key -- static schema return, no mutation"
        def result = script.executeTool("hub_set_native_app", [appId: 123, addTrigger: [discover: true]])

        then: "the gate does not fire; the real static discovery schema comes back"
        noExceptionThrown()
        result.discriminator == 'capability'
        result.capabilities instanceof List
    }

    def "hub_set_native_app CREATE-shaped call with a stray guide flag is still gated (no key -> blocked)"() {
        given:
        settingsMap.enableMandatoryBPS = true

        when: "no appId -> the create arm would really execute; the schema-only exemption must not apply"
        script.executeTool("hub_set_native_app", [guide: true, appType: "rule_machine", name: "X"])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.startsWith("Mandatory best-practice")
    }

    def "gateway name is not gated -- sub-tools gate on re-entry"() {
        given:
        settingsMap.enableMandatoryBPS = true

        when: "a pure-read gateway with no sub-tool returns its catalog, not a gate block"
        def result = script.executeTool("hub_read_devices", [:])

        then:
        noExceptionThrown()
        result != null
    }

    def "hubBpsGuideKey() matches the literal published in the guide body (drift guard)"() {
        expect: "the key the gate validates is the same literal the guide hands the LLM"
        (script.getToolGuideSections()['best_practice_reference'] as String).contains(script.hubBpsGuideKey())
    }

    def "gate ON + non-string key value is rejected (toString coercion does not bypass)"() {
        given:
        settingsMap.enableMandatoryBPS = true

        when: "a numeric bestPracticeKey -> coerced to '12345' != the key -> blocked"
        script.executeTool("hub_set_hsm", [armCommand: "armHome", bestPracticeKey: 12345])

        then:
        thrown(IllegalArgumentException)
    }

    // ---- gateway-routed gate (the production call shape): the sub-tool re-enters
    // executeTool via handleGateway and is gated on re-entry. requiredParamsByTool is
    // stubbed empty so the gateway's required-param pre-check is a no-op and the call
    // reaches the BPS gate. ----

    def "gate applies to a gateway-routed write sub-tool on re-entry (blocked without the key)"() {
        given: "useGateways pinned ON -- a gateway NAME is only dispatched to handleGateway when on"
        settingsMap.enableMandatoryBPS = true
        settingsMap.useGateways = true
        script.metaClass.requiredParamsByTool = { -> [:] }
        script.metaClass.toolCreateVariable = { a -> [success: true, stubbed: true] }

        when: "a write reached through its gateway, no key -> blocked when the sub-tool re-enters"
        script.executeTool("hub_manage_variables", [tool: "hub_create_variable", args: [name: "x"]])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.startsWith("Mandatory best-practice")
    }

    def "gate passes a gateway-routed write when the key is in the inner args"() {
        given:
        settingsMap.enableMandatoryBPS = true
        settingsMap.useGateways = true
        script.metaClass.requiredParamsByTool = { -> [:] }
        script.metaClass.toolCreateVariable = { a -> [success: true, stubbed: true] }

        when:
        def result = script.executeTool("hub_manage_variables",
            [tool: "hub_create_variable", args: [name: "x", bestPracticeKey: script.hubBpsGuideKey()]])

        then:
        noExceptionThrown()
        result.stubbed == true
    }
}
