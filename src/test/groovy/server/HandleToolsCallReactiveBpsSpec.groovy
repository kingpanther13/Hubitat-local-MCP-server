package server

import support.ToolSpecBase

/**
 * Reactive best-practice hints on write errors (issue #299, ALWAYS ON — no toggle) at the
 * handleToolsCall envelope chokepoint. On a write-tool error, the response gains a one-line pointer
 * to the FAILING tool's OWN guide section (via _guideSectionForTool), on both the thrown-IAE ->
 * -32602 path (message augmented) and the returned [success:false]/isError Map path (bp_warning
 * field). It stays quiet for: tools with no dedicated section (no generic fallback), permission/
 * config refusals, the gate's own missing-key message, success responses, and errors that already
 * point at the guide. A hint failure must never mask the genuine error.
 */
class HandleToolsCallReactiveBpsSpec extends ToolSpecBase {

    // ---- THROWN-IAE path (-32602): pointer to the failing tool's section --------

    def "a thrown device-command error points at device_authorization (the tool's section)"() {
        given:
        script.metaClass.toolSendCommand = { d, c, p = null, w = null ->
            throw new IllegalArgumentException("Device not found: ${d}")
        }

        when:
        def response = mcpDriver.callTool('hub_call_device_command', [deviceId: '999', command: 'on'])

        then:
        response.error.code == -32602
        response.error.message.contains('Device not found: 999')
        response.error.message.contains('hub_get_tool_guide(section="device_authorization")')
        response.error.message.contains('hub_call_device_command')
        and: "NOT the generic best-practice page"
        !response.error.message.contains('best_practice_reference')
    }

    def "a thrown hub_set_rule error points at set_rule_reference (a DIFFERENT tool -> different section)"() {
        given:
        script.metaClass.toolSetRule = { a -> throw new IllegalArgumentException("trigger is required") }

        when:
        def response = mcpDriver.callTool('hub_set_rule', [appId: 1, addTrigger: [:]])

        then:
        response.error.code == -32602
        response.error.message.contains('hub_get_tool_guide(section="set_rule_reference")')
    }

    def "reactive hint resolves the SUB-TOOL section for a gateway-routed error (not the gateway name)"() {
        given: "a sub-tool error routed through its gateway -- handleToolsCall sees the gateway name"
        settingsMap.useGateways = true
        script.metaClass.requiredParamsByTool = { -> [:] }
        script.metaClass.toolSetAppDisabled = { a -> throw new IllegalArgumentException("appId must be a positive integer") }

        when:
        def response = mcpDriver.callTool('hub_manage_native_rules_and_apps',
            [tool: 'hub_set_app_disabled', args: [appId: 'x']])

        then: "the hint maps to the SUB-TOOL's section (builtin_app_tools), since the gateway has none"
        response.error.code == -32602
        response.error.message.contains('hub_get_tool_guide(section="builtin_app_tools")')
        response.error.message.contains('hub_set_app_disabled')
    }

    def "a gateway-ENVELOPE error (unknown sub-tool) gets NO hint -- the sub-tool never ran"() {
        given: "a sub-tool that is not a member of the called gateway -> handleGateway rejects it"
        settingsMap.useGateways = true

        when:
        def response = mcpDriver.callTool('hub_manage_devices', [tool: 'hub_delete_room', args: [room: 'x']])

        then: "the gateway 'Unknown tool' refusal stands alone -- no resolved-sub-tool guide pointer"
        response.error.code == -32602
        response.error.message.contains('Unknown tool')
        !response.error.message.contains('get_tool_guide')
    }

    def "malformed gateway arguments (a non-Map) do not crash the reactive-name resolution"() {
        given: "a gateway call whose 'arguments' is a String, not an object"
        settingsMap.useGateways = true

        when: "the args.tool probe runs BEFORE handleToolsCall's try -- it must not throw on a String"
        def response = script.handleToolsCall([id: 1, params: [name: 'hub_manage_devices', arguments: 'not-an-object']])

        then: "the malformed call is reported through the handled JSON-RPC path, not an unhandled crash"
        noExceptionThrown()
        response != null
        (response.error != null) || (response.result != null)
    }

    def "a thrown error from a tool with NO dedicated section gets no hint"() {
        given: "hub_set_hsm is not in the tool->section map"
        script.metaClass.toolSetHsm = { m -> throw new IllegalArgumentException("HSM not configured") }

        when:
        def response = mcpDriver.callTool('hub_set_hsm', [mode: 'armHome'])

        then:
        response.error.code == -32602
        response.error.message.contains('HSM not configured')
        !response.error.message.contains('get_tool_guide')
    }

    def "a permission refusal (Write master OFF) is NOT augmented"() {
        given:
        settingsMap.enableWrite = false

        when: "a sectioned write tool blocked by the master gate"
        def response = mcpDriver.callTool('hub_call_device_command', [deviceId: '1', command: 'on'])

        then: "the message is the master refusal, with no reactive pointer appended"
        response.error.code == -32602
        response.error.message.contains('Write tools are disabled')
        !response.error.message.contains('get_tool_guide')
    }

    def "the gate's OWN missing-key refusal is NOT double-coached"() {
        given: "gate ON, no key -> the gate throws its own guide-pointer message"
        settingsMap.enableMandatoryBPS = true

        when:
        def response = mcpDriver.callTool('hub_call_device_command', [deviceId: '1', command: 'on'])

        then:
        response.error.code == -32602
        response.error.message.contains('Mandatory best-practice')
        !response.error.message.contains('reference and best practices')   // the reactive suffix is absent
    }

    // ---- RETURNED-error Map path: bp_warning with the tool's section ------------

    def "a returned success:false error gets a bp_warning pointing at the tool's section"() {
        given:
        script.metaClass.toolSendCommand = { d, c, p = null, w = null -> [success: false, error: "Device not found: 42"] }

        when:
        def inner = mcpDriver.parseInner(mcpDriver.callTool('hub_call_device_command', [deviceId: '42', command: 'on']))

        then:
        inner.success == false
        inner.bp_warning != null
        inner.bp_warning.contains('hub_get_tool_guide(section="device_authorization")')
    }

    def "a successful result is never scanned (no bp_warning)"() {
        given:
        script.metaClass.toolSendCommand = { d, c, p = null, w = null -> [success: true] }

        when:
        def inner = mcpDriver.parseInner(mcpDriver.callTool('hub_call_device_command', [deviceId: '1', command: 'on']))

        then:
        inner.success == true
        !(inner instanceof Map && inner.containsKey('bp_warning'))
    }

    def "a pre-existing bp_warning is not overwritten (idempotent)"() {
        given:
        script.metaClass.toolSendCommand = { d, c, p = null, w = null -> [success: false, error: "x", bp_warning: "PRESET"] }

        when:
        def inner = mcpDriver.parseInner(mcpDriver.callTool('hub_call_device_command', [deviceId: '1', command: 'on']))

        then:
        inner.bp_warning == "PRESET"
    }

    def "a thrown IAE with a null message does not crash and is not augmented"() {
        given:
        script.metaClass.toolSendCommand = { d, c, p = null, w = null -> throw new IllegalArgumentException() }

        when:
        def response = mcpDriver.callTool('hub_call_device_command', [deviceId: '1', command: 'on'])

        then:
        response.error.code == -32602
        !(response.error.message?.contains('get_tool_guide'))
    }

    def "a hint-attach failure (immutable result Map) does NOT mask the original error"() {
        given:
        script.metaClass.toolSendCommand = { d, c, p = null, w = null -> [success: false, error: "Device not found: 7"].asImmutable() }

        when:
        def response = mcpDriver.callTool('hub_call_device_command', [deviceId: '7', command: 'on'])
        def inner = mcpDriver.parseInner(response)

        then: "the genuine error survives; no generic envelope replaced it"
        inner.success == false
        inner.error == "Device not found: 7"
        response.result?.isError != true
    }

    // ---- _guideSectionForTool: the tool -> section map -------------------------

    def "_guideSectionForTool maps each write-tool family to its own section"() {
        expect:
        script._guideSectionForTool('hub_set_rule') == 'set_rule_reference'
        script._guideSectionForTool('hub_set_visual_rule') == 'visual_rule_reference'
        script._guideSectionForTool('hub_create_custom_rule') == 'rules'
        script._guideSectionForTool('hub_set_native_app') == 'builtin_app_tools'
        script._guideSectionForTool('hub_update_device') == 'update_device'
        script._guideSectionForTool('hub_manage_virtual_device') == 'virtual_devices'
        script._guideSectionForTool('hub_delete_device') == 'hub_admin_write'
        script._guideSectionForTool('hub_call_device_command') == 'device_authorization'

        and: "a tool with no dedicated section maps to null (no generic fallback)"
        script._guideSectionForTool('hub_set_hsm') == null
        script._guideSectionForTool(null) == null
    }

    // ---- _reactiveBpsWarning: pure-function rules -------------------------------

    def "_reactiveBpsWarning points at the tool's section for a genuine tool error"() {
        expect:
        def w = script._reactiveBpsWarning('hub_set_rule', [:], 'some rule failure')
        w?.contains('hub_get_tool_guide(section="set_rule_reference")')
    }

    def "_reactiveBpsWarning returns null for a tool with no section"() {
        expect:
        script._reactiveBpsWarning('hub_set_hsm', [:], 'some failure') == null
    }

    def "_reactiveBpsWarning returns null for permission/config refusals"() {
        expect:
        script._reactiveBpsWarning('hub_call_device_command', [:], 'Write tools are disabled. Enable ...') == null
        script._reactiveBpsWarning('hub_call_device_command', [:], 'Mandatory best-practice acknowledgment is enabled ...') == null
        script._reactiveBpsWarning('hub_delete_device', [:], 'hub_delete_device is disabled in Advanced settings ...') == null
    }

    def "_reactiveBpsWarning returns null when the error already points at the guide (idempotent)"() {
        expect:
        script._reactiveBpsWarning('hub_call_device_command', [:], 'Device not found. See hub_get_tool_guide(...)') == null
    }

    def "_reactiveBpsWarning returns null for gateway-envelope errors (the sub-tool never ran)"() {
        expect:
        script._reactiveBpsWarning('hub_delete_room', [:], "Unknown tool 'hub_delete_room' in hub_manage_devices") == null
        script._reactiveBpsWarning('hub_call_device_command', [:], "Missing required parameter: command") == null
        script._reactiveBpsWarning('hub_set_native_app', [:], "Gateway arg 'args' was a String but not valid JSON.") == null
    }
}
