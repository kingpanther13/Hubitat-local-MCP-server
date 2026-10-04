package server

import support.ToolSpecBase

/**
 * Mandatory best-practice acknowledgment gate at the executeTool dispatch chokepoint and the
 * modern-path _mrtrValidateAccess chokepoint (issue #299, default ON). Each write tool requires
 * the acknowledgment key of its own guide section (_bpsSectionForTool) as the bestPracticeKey
 * argument; keys rotate hourly and the previous hour's key is still accepted. Only explicit
 * false disables the gate. hub_get_tool_guide (read) and hub_update_mcp_settings
 * (self-disable) are exempt so the caller can never lock itself out; gateway names short-circuit.
 */
class ExecuteToolMandatoryBpsGateSpec extends ToolSpecBase {

    static final long HOUR = 3600000L
    static final long T0 = 1234567890000L
    static final String BLOCK_HEAD = 'Mandatory best-practice acknowledgment is enabled for write tools.'
    static final String ANY_KEY = /I-HAVE-READ-THE-GUIDE-[a-z_]+-[0-9a-f]{8}/
    static final List<String> WHY_STEMS = ['The key format changed', 'has expired', 'belongs to section']

    def setup() {
        // Representative writes; stubbed so a past-the-gate dispatch returns a sentinel instead
        // of touching the hub. The base setup() wiped the metaClass first, so stubs are fresh.
        script.metaClass.toolSetHsm = { m -> [success: true, stubbed: true] }
        script.metaClass.toolSetRule = { m -> [success: true, stubbed: true] }
        settingsMap.enableWrite = true
        settingsMap.enableRead = true
    }

    private String key(String section) { script.hubBpsGuideKey(section) as String }

    private static final Map SET_RULE_ARGS = [appId: 5, addTrigger: [capability: 'Switch'], confirm: true]

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
        !e.message.contains(key('best_practice_reference'))
        !e.message.contains('I-HAVE-READ-THE-GUIDE')
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
        def result = script.executeTool("hub_set_hsm", [armCommand: "armHome", bestPracticeKey: key('best_practice_reference')])

        then:
        noExceptionThrown()
        result.stubbed == true
    }

    def "block names the calling tool's section, no key -- #tool"() {
        given:
        settingsMap.enableMandatoryBPS = true

        when:
        script.executeTool(tool, args)

        then:
        def e = thrown(IllegalArgumentException)
        e.message == script._bpsBlockMessage(section, null)
        e.message.startsWith(BLOCK_HEAD)
        e.message.contains("hub_get_tool_guide(section='${section}')")
        !(e.message =~ ANY_KEY)
        WHY_STEMS.findAll { e.message.contains(it) } == []

        where:
        tool                 | args                                     || section
        'hub_set_rule'       | SET_RULE_ARGS                            || 'set_rule_reference'
        'hub_set_native_app' | [appType: 'rule_machine', name: 'X']     || 'builtin_app_tools_crud'
        'hub_set_hsm'        | [armCommand: 'armHome']                  || 'best_practice_reference'
    }

    def "the calling tool's section key passes; another section's key is refused -- #tool"() {
        given:
        settingsMap.enableMandatoryBPS = true
        script.metaClass.toolSetNativeApp = { m -> [success: true, stubbed: true] }

        when: 'the right section key'
        def ok = script.executeTool(tool, args + [bestPracticeKey: key(own)])

        then:
        ok.stubbed == true

        when: 'a real, current key from a different section'
        script.executeTool(tool, args + [bestPracticeKey: key(other)])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("section='${own}'")

        where:
        tool                 | args                                 | own                       | other
        'hub_set_rule'       | SET_RULE_ARGS                        | 'set_rule_reference'      | 'best_practice_reference'
        'hub_set_hsm'        | [armCommand: 'armHome']              | 'best_practice_reference' | 'set_rule_reference'
        'hub_set_native_app' | [appType: 'rule_machine', name: 'X'] | 'builtin_app_tools_crud'  | 'builtin_app_tools_rules'
    }

    /** The value a caller offers for each refusal variant; the clock is the harness default T0. */
    private String offeredFor(String variant) {
        switch (variant) {
            case 'missing': return null
            case 'unknown format': return 'not-the-key'
            case 'cached bps-ack': return 'bps-ack-0123abcd'
            case 'expired': return script.hubBpsGuideKey('set_rule_reference', T0 - 2 * HOUR) as String
            case 'other section': return key('best_practice_reference')
        }
        throw new IllegalStateException(variant)
    }

    private String refusalAt(String chokepoint, String offered) {
        Map leafArgs = SET_RULE_ARGS + (offered == null ? [:] : [bestPracticeKey: offered])
        try {
            if (chokepoint == 'executeTool') script.executeTool('hub_set_rule', leafArgs)
            else script._mrtrValidateAccess('hub_manage_rule_machine', 'hub_set_rule', [tool: 'hub_set_rule', args: leafArgs])
        } catch (IllegalArgumentException e) {
            return e.message
        }
        throw new AssertionError("${chokepoint} did not refuse ${offered}")
    }

    def "the refusal says why the offered key failed -- #variant at #chokepoint"() {
        given:
        settingsMap.enableMandatoryBPS = true
        def offered = offeredFor(variant)

        when:
        def message = refusalAt(chokepoint, offered)

        then: 'same text at both chokepoints, pointing at the section, never carrying a key'
        message == script._bpsBlockMessage('set_rule_reference', offered)
        message.startsWith(BLOCK_HEAD)
        message.contains("hub_get_tool_guide(section='set_rule_reference')")
        !(message =~ ANY_KEY)

        and: 'exactly the distinguishing sentence for this variant'
        WHY_STEMS.findAll { message.contains(it) } == (sentence ? WHY_STEMS.findAll { sentence.contains(it) } : [])
        sentence == null || message.contains(sentence)

        where:
        [chokepoint, variant] << [['executeTool', 'modern path'],
                                  ['missing', 'unknown format', 'cached bps-ack', 'expired', 'other section']].combinations()
        sentence = [
            'missing'       : null,
            'unknown format': null,
            'cached bps-ack': 'The key format changed; a cached bps-ack key no longer works.',
            'expired'       : 'The key you passed has expired (keys rotate hourly); read the section again.',
            'other section' : "The key you passed belongs to section 'best_practice_reference', not this tool's section."
        ][variant]
    }

    def "keys rotate hourly: previous hour accepted, two hours old refused"() {
        given: 'a key read at T0'
        settingsMap.enableMandatoryBPS = true
        NOW_OVERRIDE.set({ -> T0 })
        def readAt = key('set_rule_reference')
        assert readAt != script.hubBpsGuideKey('set_rule_reference', T0 + HOUR)
        assert readAt != key('best_practice_reference')

        when: 'one hour later'
        NOW_OVERRIDE.set({ -> T0 + HOUR })

        then: 'still accepted (grace for a read just before rotation)'
        script.hubBpsKeyAccepted('set_rule_reference', readAt)
        script.executeTool('hub_set_rule', SET_RULE_ARGS + [bestPracticeKey: readAt]).stubbed == true

        when: 'two hours later'
        NOW_OVERRIDE.set({ -> T0 + 2 * HOUR })

        then: 'refused at the predicate and at the gate'
        !script.hubBpsKeyAccepted('set_rule_reference', readAt)

        when:
        script.executeTool('hub_set_rule', SET_RULE_ARGS + [bestPracticeKey: readAt])

        then:
        thrown(IllegalArgumentException)
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
        script.executeTool("hub_set_hsm", [armCommand: "armHome", bestPracticeKey: key('best_practice_reference')]).stubbed == true
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
            [tool: "hub_create_variable", args: [name: "x", bestPracticeKey: key('best_practice_reference')]])

        then:
        noExceptionThrown()
        result.stubbed == true
    }

    def "a gateway-routed sub-tool is gated on re-entry with its own section"() {
        given:
        settingsMap.enableMandatoryBPS = true
        settingsMap.useGateways = true
        script.metaClass.requiredParamsByTool = { -> [:] }
        script.metaClass.toolRunRmRule = { a -> [success: true, stubbed: true] }

        when: 'the generic key on hub_call_rule, whose section is builtin_app_tools_rules'
        script.executeTool("hub_manage_native_rules_and_apps",
            [tool: "hub_call_rule", args: [ruleId: 1, action: 'run', bestPracticeKey: key('best_practice_reference')]])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("section='builtin_app_tools_rules'")

        when: 'its own section key'
        def result = script.executeTool("hub_manage_native_rules_and_apps",
            [tool: "hub_call_rule", args: [ruleId: 1, action: 'run', bestPracticeKey: key('builtin_app_tools_rules')]])

        then:
        result.stubbed == true
    }

    def "the modern-path chokepoint requires the leaf tool's section key too"() {
        given:
        settingsMap.enableMandatoryBPS = true

        when: 'another section key on a gateway-routed hub_set_rule'
        script._mrtrValidateAccess('hub_manage_rule_machine', 'hub_set_rule',
            [tool: 'hub_set_rule', args: [bestPracticeKey: key('best_practice_reference')]])

        then: 'the same block text as executeTool, naming the leaf section'
        def e = thrown(IllegalArgumentException)
        e.message == script._bpsBlockMessage('set_rule_reference', key('best_practice_reference'))

        when: 'the leaf section key'
        script._mrtrValidateAccess('hub_manage_rule_machine', 'hub_set_rule',
            [tool: 'hub_set_rule', args: [bestPracticeKey: key('set_rule_reference')]])

        then:
        noExceptionThrown()
    }
}
