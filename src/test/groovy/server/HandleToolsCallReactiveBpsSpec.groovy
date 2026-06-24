package server

import support.ToolSpecBase

/**
 * Reactive best-practice hints on write errors (issue #299, opt-in) at the handleToolsCall
 * envelope chokepoint. When enableReactiveBPS is ON, a failed write surfaces ONE domain-specific
 * nudge pointing at the best_practice_reference guide. The Hubitat error contract is twofold, so
 * both surfaces are covered: a THROWN IllegalArgumentException -> -32602 (the message is augmented)
 * and a RETURNED [success:false, ...] / isError Map (a bp_warning field is attached). Success
 * responses are never scanned; the gate's own missing-key refusal is never double-coached.
 */
class HandleToolsCallReactiveBpsSpec extends ToolSpecBase {

    // ---- THROWN-IAE path (-32602) -----------------------------------------

    def "reactive ON: a thrown device-not-found IAE gets a guide pointer on the -32602 message"() {
        given:
        settingsMap.enableReactiveBPS = true
        script.metaClass.toolSendCommand = { d, c, p = null, w = null ->
            throw new IllegalArgumentException("Device not found: ${d}")
        }

        when:
        def response = mcpDriver.callTool('hub_call_device_command', [deviceId: '999', command: 'on'])

        then:
        response.error.code == -32602
        response.error.message.contains('Device not found: 999')
        response.error.message.contains('hub_list_devices')
        response.error.message.contains('best_practice_reference')
    }

    def "reactive OFF: a thrown IAE keeps the bare -32602 message (no hint)"() {
        given:
        settingsMap.enableReactiveBPS = false
        script.metaClass.toolSendCommand = { d, c, p = null, w = null ->
            throw new IllegalArgumentException("Device not found: ${d}")
        }

        when:
        def response = mcpDriver.callTool('hub_call_device_command', [deviceId: '999', command: 'on'])

        then:
        response.error.code == -32602
        response.error.message.contains('Device not found: 999')
        !response.error.message.contains('best_practice_reference')
    }

    def "reactive ON: a thrown IAE with a null message does not crash and is not augmented"() {
        given:
        settingsMap.enableReactiveBPS = true
        script.metaClass.toolSetHsm = { m -> throw new IllegalArgumentException() }   // null message

        when:
        def response = mcpDriver.callTool('hub_set_hsm', [mode: 'armHome'])

        then: "maps to -32602 cleanly; the null-message guard skips augmentation (no suffix)"
        response.error.code == -32602
        !(response.error.message?.contains('for details.'))
    }

    def "reactive ON: the gate's OWN missing-key refusal is NOT double-coached"() {
        given: "both toggles ON, no key supplied -> the gate throws its own guide-pointer message"
        settingsMap.enableMandatoryBPS = true
        settingsMap.enableReactiveBPS = true

        when:
        def response = mcpDriver.callTool('hub_set_hsm', [mode: 'armHome'])

        then: "the gate message stands alone -- the reactive suffix is not appended"
        response.error.code == -32602
        response.error.message.contains('Mandatory best-practice')
        !response.error.message.contains('for details.')
    }

    // ---- RETURNED-error Map path ------------------------------------------

    def "reactive ON: a returned success:false error gets a bp_warning field"() {
        given:
        settingsMap.enableReactiveBPS = true
        script.metaClass.toolSetHsm = { m -> [success: false, error: "Device not found: 42"] }

        when:
        def response = mcpDriver.callTool('hub_set_hsm', [mode: 'armHome'])
        def inner = mcpDriver.parseInner(response)

        then:
        inner.success == false
        inner.bp_warning != null
        inner.bp_warning.contains('hub_list_devices')
        inner.bp_warning.contains('best_practice_reference')
    }

    def "reactive ON: a successful result is never scanned (no bp_warning)"() {
        given:
        settingsMap.enableReactiveBPS = true
        script.metaClass.toolSetHsm = { m -> [success: true, hsmStatus: 'armedHome'] }

        when:
        def inner = mcpDriver.parseInner(mcpDriver.callTool('hub_set_hsm', [mode: 'armHome']))

        then:
        inner.success == true
        !(inner instanceof Map && inner.containsKey('bp_warning'))
    }

    def "reactive OFF: a returned error is not augmented"() {
        given:
        settingsMap.enableReactiveBPS = false
        script.metaClass.toolSetHsm = { m -> [success: false, error: "Device not found: 42"] }

        when:
        def inner = mcpDriver.parseInner(mcpDriver.callTool('hub_set_hsm', [mode: 'armHome']))

        then:
        inner.success == false
        !(inner instanceof Map && inner.containsKey('bp_warning'))
    }

    def "reactive ON: a pre-existing bp_warning is not overwritten (idempotent)"() {
        given:
        settingsMap.enableReactiveBPS = true
        script.metaClass.toolSetHsm = { m -> [success: false, error: "Device not found: 42", bp_warning: "PRESET"] }

        when:
        def inner = mcpDriver.parseInner(mcpDriver.callTool('hub_set_hsm', [mode: 'armHome']))

        then:
        inner.bp_warning == "PRESET"
    }

    // ---- detector unit coverage (pure function) ---------------------------

    def "_reactiveBpsWarning nudges toward native Rule Machine for legacy custom-rule errors"() {
        expect:
        def w = script._reactiveBpsWarning('hub_create_custom_rule', [:], 'some failure')
        w?.contains('native Rule Machine')
        w?.contains('best_practice_reference')
    }

    def "_reactiveBpsWarning nudges toward a backup+confirm for a destructive refusal"() {
        expect:
        def w = script._reactiveBpsWarning('hub_delete_device', [:], 'confirm=true required')
        w?.contains('backup')
        w?.contains('confirm=true')
    }

    def "_reactiveBpsWarning returns null when nothing matches (clean errors stay quiet)"() {
        expect:
        script._reactiveBpsWarning('hub_set_hsm', [:], 'some unrelated hub failure') == null
    }

    def "_reactiveBpsWarning returns null when the error already points at the guide (idempotent)"() {
        expect:
        script._reactiveBpsWarning('hub_call_device_command', [:], 'Device not found. See hub_get_tool_guide(...)') == null
    }

    def "_reactiveBpsWarning is most-specific-first: a custom_rule error that also says 'not found' yields the RM nudge"() {
        expect: "ordering is deliberate -- the custom-rule detector wins over the device detector"
        def w = script._reactiveBpsWarning('hub_delete_custom_rule', [:], 'device not found while deleting custom rule')
        w?.contains('native Rule Machine')
        !w?.contains('hub_list_devices')
    }

    // ---- real thrown messages (not synthetic strings): guards against a reworded safety
    // message silently dropping (or wrongly gaining) a reactive hint. ----

    def "reactive detector fires on the REAL 'BACKUP REQUIRED' message from requireDestructiveConfirm"() {
        given: "no recent backup -> requireDestructiveConfirm(true) throws the real BACKUP REQUIRED text"
        stateMap.remove('lastBackupTimestamp')
        String realMsg = null
        try {
            script.requireDestructiveConfirm(true)
        } catch (IllegalArgumentException e) {
            realMsg = e.message
        }

        expect:
        realMsg?.startsWith('BACKUP REQUIRED')
        def w = script._reactiveBpsWarning('hub_delete_device', [:], realMsg)
        w?.contains('backup')
        w?.contains('best_practice_reference')
    }

    def "reactive detector stays quiet on the REAL 'SAFETY CHECK' message (it already points at the guide)"() {
        given: "confirm omitted -> requireDestructiveConfirm(null) throws the real SAFETY CHECK text"
        String realMsg = null
        try {
            script.requireDestructiveConfirm(null)
        } catch (IllegalArgumentException e) {
            realMsg = e.message
        }

        expect: "the message already says call hub_get_tool_guide, so the idempotency guard suppresses a second pointer"
        realMsg?.contains('get_tool_guide')
        script._reactiveBpsWarning('hub_delete_device', [:], realMsg) == null
    }

    def "reactive ON: a hint-attach failure (immutable result Map) does NOT mask the original error"() {
        given: "a tool returns an IMMUTABLE error Map -> result.bp_warning = w throws -> the guard must"
        // log and fall through, leaving the ORIGINAL error intact rather than a generic -32603.
        settingsMap.enableReactiveBPS = true
        script.metaClass.toolSetHsm = { m -> [success: false, error: "Device not found: 7"].asImmutable() }

        when:
        def response = mcpDriver.callTool('hub_set_hsm', [mode: 'armHome'])
        def inner = mcpDriver.parseInner(response)

        then: "the genuine tool error survives; the bottom-catch generic envelope did not replace it"
        inner.success == false
        inner.error == "Device not found: 7"
        response.result?.isError != true
    }
}
