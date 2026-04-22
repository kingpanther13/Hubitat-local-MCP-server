package server

import support.ToolSpecBase

/**
 * Spec for the manage_destructive_hub_ops gateway tools in hubitat-mcp-server.groovy:
 *
 * - toolRebootHub    -> reboot_hub
 * - toolShutdownHub  -> shutdown_hub
 * - toolDeleteDevice -> delete_device
 *
 * All three tools run through requireHubAdminWrite, so every golden-path test
 * must seed:
 *   settingsMap.enableHubAdminWrite = true
 *   stateMap.lastBackupTimestamp    = 1234567890000L  (matches HarnessSpec's fixed now())
 *   args.confirm                    = true
 *
 * Mocking strategy (see docs/testing.md "Which interception point to use"):
 *   - hubInternalGet       — routed by HarnessSpec via hubGet.register(path) closures.
 *   - hubInternalPost      — script-defined helper, stubbed per-test on script.metaClass.
 *                            (reboot + shutdown don't go through a wrapper; they call
 *                            hubInternalPost directly.)
 *   - location.hub / findDevice / selectedDevice.events() branches in toolDeleteDevice
 *     are best-effort in try/catch blocks — tests don't drive them.
 */
class ToolDestructiveHubOpsSpec extends ToolSpecBase {

    private void enableHubAdminWrite() {
        settingsMap.enableHubAdminWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L  // matches fixed now()
    }

    // -------- toolRebootHub --------

    def "reboot_hub throws when confirm is not provided"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolRebootHub([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
        ex.message.contains('confirm=true')
    }

    def "reboot_hub throws when Hub Admin Write is disabled"() {
        when:
        script.toolRebootHub([confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Hub Admin Write')
    }

    def "reboot_hub throws when no recent backup is available"() {
        given:
        settingsMap.enableHubAdminWrite = true
        // No stateMap.lastBackupTimestamp — requireHubAdminWrite's 24h window fails

        when:
        script.toolRebootHub([confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('BACKUP REQUIRED')
        ex.message.contains('create_hub_backup')
    }

    def "reboot_hub posts to /hub/reboot and reports success"() {
        given:
        enableHubAdminWrite()
        def postedPath = null
        script.metaClass.hubInternalPost = { String path, Map body = null ->
            postedPath = path
            return 'ok'
        }

        when:
        def result = script.toolRebootHub([confirm: true])

        then:
        postedPath == '/hub/reboot'
        result.success == true
        result.message.contains('reboot')
        result.warning.contains('automations')
        result.response == 'ok'
    }

    def "reboot_hub reports failure when the hub POST throws"() {
        given:
        enableHubAdminWrite()
        script.metaClass.hubInternalPost = { String path, Map body = null ->
            throw new RuntimeException('Connection refused')
        }

        when:
        def result = script.toolRebootHub([confirm: true])

        then:
        result.success == false
        result.error.contains('Reboot failed')
        result.error.contains('Connection refused')
        result.note.contains('manually')
    }

    // -------- toolShutdownHub --------

    def "shutdown_hub throws when confirm is not provided"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolShutdownHub([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
        ex.message.contains('confirm=true')
    }

    def "shutdown_hub throws when Hub Admin Write is disabled"() {
        when:
        script.toolShutdownHub([confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Hub Admin Write')
    }

    def "shutdown_hub posts to /hub/shutdown and warns about manual restart"() {
        given:
        enableHubAdminWrite()
        def postedPath = null
        script.metaClass.hubInternalPost = { String path, Map body = null ->
            postedPath = path
            return 'shutdown initiated'
        }

        when:
        def result = script.toolShutdownHub([confirm: true])

        then:
        postedPath == '/hub/shutdown'
        result.success == true
        result.message.contains('shutdown initiated')
        result.warning.contains('unplug')
        result.response == 'shutdown initiated'
    }

    def "shutdown_hub reports failure when the hub POST throws"() {
        given:
        enableHubAdminWrite()
        script.metaClass.hubInternalPost = { String path, Map body = null ->
            throw new RuntimeException('Timeout')
        }

        when:
        def result = script.toolShutdownHub([confirm: true])

        then:
        result.success == false
        result.error.contains('Shutdown failed')
        result.error.contains('Timeout')
    }

    // -------- toolDeleteDevice --------

    def "delete_device throws when confirm is not provided"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolDeleteDevice([deviceId: '42'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
        ex.message.contains('confirm=true')
    }

    def "delete_device throws when Hub Admin Write is disabled"() {
        when:
        script.toolDeleteDevice([deviceId: '42', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Hub Admin Write')
    }

    def "delete_device throws when deviceId is missing"() {
        given:
        enableHubAdminWrite()

        when:
        script.toolDeleteDevice([confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('deviceId is required')
    }

    def "delete_device throws when the device is not found on the hub"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/device/fullJson/999') { params -> null }

        when:
        script.toolDeleteDevice([deviceId: '999', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('not found on hub')
        ex.message.contains('999')
    }

    def "delete_device force-deletes the device and verifies the deletion"() {
        given:
        enableHubAdminWrite()

        and: 'lookup returns the device on the first call, null on verify (device is gone)'
        def lookupCalls = 0
        hubGet.register('/device/fullJson/42') { params ->
            lookupCalls++
            // Non-radio DNI (not 2 hex digits) + no zigbeeId so isRadioDevice=false,
            // skipping the Z-Wave/Zigbee endpoint probes entirely.
            lookupCalls == 1
                ? '{"id": 42, "label": "Old Switch", "name": "Generic Switch", "typeName": "Virtual Switch", "deviceNetworkId": "mcp-virtual-123"}'
                : null
        }
        hubGet.register('/device/forceDelete/42/yes') { params -> 'ok' }

        when:
        def result = script.toolDeleteDevice([deviceId: '42', confirm: true])

        then: 'reports success with audit info, post-delete verify saw the device gone'
        lookupCalls == 2
        result.success == true
        result.deviceId == '42'
        result.deviceName == 'Old Switch'
        result.message.contains('permanently deleted')
        result.auditInfo.deviceType == 'Virtual Switch'
        result.auditInfo.deviceNetworkId == 'mcp-virtual-123'
    }

    def "delete_device reports force-delete failure without throwing"() {
        given:
        enableHubAdminWrite()
        hubGet.register('/device/fullJson/77') { params ->
            '{"id": 77, "label": "Unlucky Device", "name": "Bulb", "typeName": "Virtual Bulb", "deviceNetworkId": "mcp-virtual-77"}'
        }
        hubGet.register('/device/forceDelete/77/yes') { params ->
            throw new RuntimeException('Hub API returned 500')
        }

        when:
        def result = script.toolDeleteDevice([deviceId: '77', confirm: true])

        then:
        result.success == false
        result.error.contains('Force delete failed')
        result.deviceId == '77'
        result.deviceName == 'Unlucky Device'
    }
}
