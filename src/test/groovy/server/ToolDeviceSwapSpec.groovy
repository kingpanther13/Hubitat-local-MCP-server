package server

import groovy.json.JsonOutput
import support.ToolSpecBase

/**
 * Spec for toolCallDeviceSwap (hub_call_device_swap), the driver for the
 * hub's built-in Swap Device system app.
 *
 * The wizard primitives (_rmWriteSettingOnPage, _rmClickAppButton) are
 * non-private app methods (dispatch cheat-sheet class 1 — see
 * docs/testing.md), so per-instance script.metaClass overrides intercept
 * them and record the call order. _rmFetchConfigJson and
 * _resolveDirectAppId are private (internal dispatch bypasses metaClass),
 * so they are stubbed one level down: configure/json fetches at
 * hubInternalGet via the hubGet path registry, the resolver's two-hop
 * redirect chain (and the cleanup delete GET) at hubInternalGetRaw.
 *
 * The Swap Device direct alias creates a fresh TRANSIENT instance per
 * resolve. The Cancel button (closeApp) does NOT reap a pending
 * (installed:false) instance, so cleanup is a GET to
 * /installedapp/delete/<appId> — asserted on every exit path unless the
 * swap action already removed the instance itself.
 */
class ToolDeviceSwapSpec extends ToolSpecBase {

    private static final Integer SWAP_APP_ID = 1802
    private static final String CONFIGURE_PATH = "/installedapp/configure/json/${SWAP_APP_ID}/mainPage"

    private void enableWriteWithBackup() {
        settingsMap.enableWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L  // matches HarnessSpec's fixed now()
    }

    private void registerDevices() {
        childDevicesList << [id: '101', label: 'BAT Swap Source', name: 'Virtual Switch']
        childDevicesList << [id: '202', label: 'BAT Swap Target', name: 'Virtual Switch']
    }

    /**
     * configure/json body for the Swap Device mainPage. oldDev options are
     * the hub's swappable-device list — only free-standing devices referenced
     * by at least one app appear there (app-owned child/component devices are
     * excluded); the default seeds BOTH fixture devices so every flow passes
     * the eligibility pre-check unless a test overrides `oldDevOptions`.
     * newDev options are the hub's compatible-replacement list ([{id: label},
     * ...] shape); `buttons` adds the firmware-named swap-action button(s).
     * closeApp (Cancel) is always present, mirroring the live page.
     */
    private static String pageJson(Map opts = [:]) {
        def inputs = [
            [name: 'oldDev', type: 'enum', multiple: false, submitOnChange: true,
             options: opts.oldDevOptions ?: [['101': 'BAT Swap Source'], ['202': 'BAT Swap Target']]],
            [name: 'newDev', type: 'enum', multiple: false, submitOnChange: true,
             options: opts.newDevOptions ?: []]
        ]
        (opts.buttons ?: []).each { inputs << [name: it, type: 'button', title: 'Swap'] }
        inputs << [name: 'closeApp', type: 'button', title: 'Cancel']
        return JsonOutput.toJson([
            app: [id: SWAP_APP_ID, version: 1, name: 'Swap Device'],
            configPage: [name: 'mainPage', sections: [[input: inputs]]]
        ])
    }

    /**
     * Wires the wizard stubs and the two hub endpoints the tool reads.
     * fixture.fetches: configure/json bodies returned in call order — fetch 1
     * is the oldDev eligibility pre-check (before any setting write), fetch 2
     * the newDev compatibility check, fetch 3 the button discovery, fetch 4
     * the post-click gone-check (the last entry repeats; '' makes
     * _rmFetchConfigJson throw = instance gone). fixture.beforeCount /
     * afterCount: /device/fullJson/101 appsUsingCount on the 1st / subsequent
     * reads. Returns the recorded call sequence (resolve / write / fetch /
     * click / count / delete steps, in order).
     */
    private List wireSwapStubs(Map fixture) {
        def calls = []
        // The real (private) _resolveDirectAppId runs; its two redirect hops are
        // answered here, and _deviceSwapCleanup's /installedapp/delete GET is
        // recorded (cleanup swallows exceptions, so an unrecorded delete would
        // pass silently). fixture.resolveBroken=true makes hop 1 a non-redirect,
        // which the resolver maps to null.
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, int t = 30, boolean r = false ->
            if (path == '/installedapp/direct/swapDevice') {
                calls << [step: 'resolve', path: path]
                if (fixture.resolveBroken) return [status: 200, location: null]
                return [status: 302, location: '/installedapp/create/38']
            }
            if (path == '/installedapp/create/38') {
                return [status: 302, location: "/installedapp/configure/${SWAP_APP_ID}".toString()]
            }
            if (path == "/installedapp/delete/${SWAP_APP_ID}".toString()) {
                calls << [step: 'delete', path: path]
                return [status: 200, location: null]
            }
            throw new IllegalStateException("Unstubbed hubInternalGetRaw: ${path}")
        }
        script.metaClass._rmWriteSettingOnPage = { Integer appId, String pageName, String key, Object value, List applied, String typeHint = null, List skipped = null ->
            calls << [step: 'write', appId: appId, page: pageName, key: key, value: value]
            applied << key
        }
        script.metaClass._rmClickAppButton = { Integer appId, String btn, String stateAttr = null, String pageName = null ->
            calls << [step: 'click', appId: appId, btn: btn, page: pageName]
            [status: 200]
        }
        def fetchCount = 0
        hubGet.register(CONFIGURE_PATH) { params ->
            fetchCount++
            calls << [step: 'fetch', n: fetchCount]
            def bodies = (fixture.fetches ?: []) as List
            bodies[Math.min(fetchCount, bodies.size()) - 1]
        }
        def countReads = 0
        hubGet.register('/device/fullJson/101') { params ->
            countReads++
            calls << [step: 'count', n: countReads]
            JsonOutput.toJson([appsUsing: [], appsUsingCount: (countReads == 1 ? fixture.beforeCount : fixture.afterCount)])
        }
        return calls
    }

    // -------- happy paths --------

    def "happy path: count, resolve, eligibility check, write oldDev, options check, write newDev, button discovery, swap click, gone-check — instance self-removes"() {
        given:
        enableWriteWithBackup()
        registerDevices()
        def compat = [['202': 'BAT Swap Target'], ['303': 'Other Compatible']]
        def calls = wireSwapStubs(
            beforeCount: 3,
            afterCount: 0,
            fetches: [
                pageJson(),                                               // before any write: oldDev eligibility check
                pageJson(newDevOptions: compat),                          // after oldDev: compatibility check
                pageJson(newDevOptions: compat, buttons: ['swapDev']),    // after newDev: button discovery
                ''                                                        // after click: instance gone
            ]
        )

        when:
        def result = script.toolCallDeviceSwap([from_device_id: '101', to_device_id: '202', confirm: true])

        then: 'success envelope reports the swap and the before/after dependent counts'
        result.success == true
        result.swapped == [from: '101', to: '202']
        result.appsRewired == 3
        result.remainingDependents == 0
        result.note.contains('hub_list_device_dependents')

        and: 'the wizard ran in exactly the contract order — eligibility fetch BEFORE the first write'
        calls.collect { it.step } == ['count', 'resolve', 'fetch', 'write', 'fetch', 'write', 'fetch', 'click', 'fetch', 'count']
        calls[1].path == '/installedapp/direct/swapDevice'
        calls[3].key == 'oldDev'
        calls[3].value == '101'
        calls[3].page == 'mainPage'
        calls[5].key == 'newDev'
        calls[5].value == '202'
        calls[7].btn == 'swapDev'
        calls[7].appId == SWAP_APP_ID

        and: 'no delete call — the swap action removed the transient instance itself'
        !calls.any { it.step == 'delete' }
    }

    def "happy path variant: instance survives the swap click, so the delete cleanup fires after success verification"() {
        given:
        enableWriteWithBackup()
        registerDevices()
        def compat = [['202': 'BAT Swap Target']]
        def withButton = pageJson(newDevOptions: compat, buttons: ['swapDev'])
        def calls = wireSwapStubs(
            beforeCount: 2,
            afterCount: 0,
            fetches: [
                pageJson(),                                               // eligibility check
                pageJson(newDevOptions: compat),
                withButton,
                withButton                                                // after click: instance still present
            ]
        )

        when:
        def result = script.toolCallDeviceSwap([from_device_id: '101', to_device_id: '202', confirm: true])

        then:
        result.success == true
        result.appsRewired == 2

        and: 'the swap action is the only click, and the delete GET fires AFTER it, never before'
        calls.findAll { it.step == 'click' }*.btn == ['swapDev']
        def clickIdx = calls.findIndexOf { it.step == 'click' && it.btn == 'swapDev' }
        def deleteIdx = calls.findIndexOf { it.step == 'delete' }
        clickIdx >= 0
        deleteIdx > clickIdx
        calls[deleteIdx].path == "/installedapp/delete/${SWAP_APP_ID}".toString()
    }

    def "hub_call_device_swap via dispatch returns the success envelope"() {
        given:
        enableWriteWithBackup()
        registerDevices()
        def compat = [['202': 'BAT Swap Target']]
        wireSwapStubs(
            beforeCount: 1,
            afterCount: 0,
            fetches: [pageJson(), pageJson(newDevOptions: compat), pageJson(newDevOptions: compat, buttons: ['swapDev']), '']
        )

        when:
        def response = mcpDriver.callTool('hub_call_device_swap', [from_device_id: '101', to_device_id: '202', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.swapped.from == '101'
        inner.swapped.to == '202'
        inner.appsRewired == 1
    }

    // -------- ineligible source (oldDev eligibility pre-check) --------

    def "from_device_id not offered as swappable: eligibility error before any setting write, instance closed"() {
        given: 'the hub offers other devices as oldDev candidates but NOT 101 (e.g. 101 is an app child device)'
        enableWriteWithBackup()
        registerDevices()
        def calls = wireSwapStubs(
            beforeCount: 0,
            afterCount: 0,
            fetches: [pageJson(oldDevOptions: [['202': 'BAT Swap Target'], ['303': 'Other Referenced']])]
        )

        when:
        def result = script.toolCallDeviceSwap([from_device_id: '101', to_device_id: '202', confirm: true])

        then: 'structured failure naming the device and the eligibility rule'
        result.success == false
        result.error == "The hub's Swap Device tool does not offer device 101 as swappable."
        result.oldDevOptionCount == 2
        result.note.contains('referenced by at least one app')
        result.note.contains('child')
        result.note.contains('hub_manage_virtual_device')
        result.note.contains('free-standing')
        result.note.contains('hub_list_device_dependents')

        and: 'cleanup deleted the transient instance and NO oldDev write (or any wizard step) was attempted'
        calls.findAll { it.step == 'delete' }*.path == ["/installedapp/delete/${SWAP_APP_ID}".toString()]
        !calls.any { it.step == 'write' }
        !calls.any { it.step == 'click' }
        calls.count { it.step == 'fetch' } == 1
    }

    // -------- incompatible target --------

    def "incompatible to_device_id: structured error lists the offered options and the transient instance is closed"() {
        given:
        enableWriteWithBackup()
        registerDevices()
        def calls = wireSwapStubs(
            beforeCount: 3,
            afterCount: 3,
            fetches: [
                pageJson(),                                               // eligibility check passes (101 offered)
                pageJson(newDevOptions: [['303': 'Other Compatible'], ['404': 'Another Option']])
            ]
        )

        when:
        def result = script.toolCallDeviceSwap([from_device_id: '101', to_device_id: '202', confirm: true])

        then: 'structured failure with the compatible options the hub offered'
        result.success == false
        result.error.contains('202')
        result.error.contains('not a compatible replacement')
        result.compatibleOptions == [[id: '303', label: 'Other Compatible'], [id: '404', label: 'Another Option']]
        result.compatibleOptionCount == 2
        result.note.contains('compatibleOptions')

        and: 'cleanup deleted the transient instance and the wizard never advanced past the options check'
        calls.findAll { it.step == 'delete' }*.path == ["/installedapp/delete/${SWAP_APP_ID}".toString()]
        !calls.any { it.step == 'click' }
        !calls.any { it.step == 'write' && it.key == 'newDev' }
    }

    // -------- button discovery failures --------

    def "ambiguous swap page (two action buttons): error names both buttons, nothing clicked blindly, instance closed"() {
        given:
        enableWriteWithBackup()
        registerDevices()
        def compat = [['202': 'BAT Swap Target']]
        def calls = wireSwapStubs(
            beforeCount: 3,
            afterCount: 3,
            fetches: [
                pageJson(),                                               // eligibility check
                pageJson(newDevOptions: compat),
                pageJson(newDevOptions: compat, buttons: ['swapDev', 'swapAll'])
            ]
        )

        when:
        def result = script.toolCallDeviceSwap([from_device_id: '101', to_device_id: '202', confirm: true])

        then:
        result.success == false
        result.error.contains('swapDev')
        result.error.contains('swapAll')
        result.buttonsFound == ['swapDev', 'swapAll']

        and: 'nothing was clicked — the transient instance was deleted instead'
        !calls.any { it.step == 'click' }
        calls.findAll { it.step == 'delete' }*.path == ["/installedapp/delete/${SWAP_APP_ID}".toString()]
    }

    def "no swap-action button revealed: structured error and the instance is deleted"() {
        given:
        enableWriteWithBackup()
        registerDevices()
        def compat = [['202': 'BAT Swap Target']]
        def calls = wireSwapStubs(
            beforeCount: 3,
            afterCount: 3,
            fetches: [
                pageJson(),                                               // eligibility check
                pageJson(newDevOptions: compat),
                pageJson(newDevOptions: compat)                           // both pickers set, no button revealed
            ]
        )

        when:
        def result = script.toolCallDeviceSwap([from_device_id: '101', to_device_id: '202', confirm: true])

        then:
        result.success == false
        result.error.contains('No swap-action button')
        result.buttonsFound == []
        !calls.any { it.step == 'click' }
        calls.findAll { it.step == 'delete' }*.path == ["/installedapp/delete/${SWAP_APP_ID}".toString()]
    }

    // -------- resolver failure --------

    def "resolver returning null yields a structured failure before any wizard step"() {
        given:
        enableWriteWithBackup()
        registerDevices()
        def calls = wireSwapStubs(resolveBroken: true, beforeCount: 3, afterCount: 3, fetches: [])

        when:
        def result = script.toolCallDeviceSwap([from_device_id: '101', to_device_id: '202', confirm: true])

        then:
        result.success == false
        result.error.contains('did not resolve')
        result.note.contains('Nothing was swapped')

        and: 'no writes, clicks, configure fetches, or cleanup deletes happened'
        !calls.any { it.step in ['write', 'click', 'fetch', 'delete'] }
    }

    // -------- confirmation + validation gates --------

    def "missing confirm throws SAFETY CHECK before device validation (devices intentionally unregistered)"() {
        when:
        script.toolCallDeviceSwap([from_device_id: '999', to_device_id: '888'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('confirm=true')
    }

    def "confirm without a recent backup throws BACKUP REQUIRED"() {
        given: 'no lastBackupTimestamp seeded'
        settingsMap.enableWrite = true

        when:
        script.toolCallDeviceSwap([from_device_id: '101', to_device_id: '202', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('BACKUP REQUIRED')
    }

    @spock.lang.Unroll
    def "hub_call_device_swap via dispatch maps missing confirm to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_call_device_swap', [from_device_id: '101', to_device_id: '202'])

        then:
        response.error != null
        response.error.code == -32602
        response.error.message.contains('confirm=true')

        where:
        useGateways << [true, false]
    }

    def "identical from and to device IDs are rejected"() {
        given:
        enableWriteWithBackup()
        registerDevices()

        when:
        script.toolCallDeviceSwap([from_device_id: '101', to_device_id: '101', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('must be different')
    }

    def "unknown from_device_id throws Device not found before any wizard step"() {
        given:
        enableWriteWithBackup()
        registerDevices()

        when:
        script.toolCallDeviceSwap([from_device_id: '999', to_device_id: '202', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Device not found')
        ex.message.contains('999')
    }

    // -------- runtime failure inside the wizard --------

    def "exception mid-wizard returns a structured failure and still closes the transient instance"() {
        given:
        enableWriteWithBackup()
        registerDevices()
        def calls = []
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, int t = 30, boolean r = false ->
            if (path == '/installedapp/direct/swapDevice') return [status: 302, location: '/installedapp/create/38']
            if (path == "/installedapp/delete/${SWAP_APP_ID}".toString()) {
                calls << [step: 'delete', path: path]
                return [status: 200, location: null]
            }
            return [status: 302, location: "/installedapp/configure/${SWAP_APP_ID}".toString()]
        }
        script.metaClass._rmWriteSettingOnPage = { Integer appId, String pageName, String key, Object value, List applied, String typeHint = null, List skipped = null ->
            throw new IllegalStateException('hub went away mid-write')
        }
        script.metaClass._rmClickAppButton = { Integer appId, String btn, String stateAttr = null, String pageName = null ->
            calls << [step: 'click', btn: btn]
            [status: 200]
        }
        hubGet.register('/device/fullJson/101') { params -> JsonOutput.toJson([appsUsing: [], appsUsingCount: 1]) }
        // Eligibility pre-check passes (101 offered); the oldDev write then throws.
        hubGet.register(CONFIGURE_PATH) { params -> pageJson() }

        when:
        def result = script.toolCallDeviceSwap([from_device_id: '101', to_device_id: '202', confirm: true])

        then: 'runtime failure is a structured envelope, not a throw'
        result.success == false
        result.error.contains('hub went away mid-write')
        result.note.contains('hub_list_device_dependents')

        and: 'the cleanup contract held — the transient instance was deleted, nothing was clicked'
        !calls.any { it.step == 'click' }
        calls.findAll { it.step == 'delete' }*.path == ["/installedapp/delete/${SWAP_APP_ID}".toString()]
    }
}
