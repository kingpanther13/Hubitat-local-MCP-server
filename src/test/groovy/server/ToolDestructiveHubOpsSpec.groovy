package server

import support.ToolSpecBase

/**
 * Spec for the hub_manage_destructive_ops gateway tools in hubitat-mcp-server.groovy:
 *
 * - toolRebootHub    -> hub_reboot
 * - toolShutdownHub  -> hub_shutdown
 * - toolDeleteDevice -> hub_delete_device
 *
 * All three tools run through requireDestructiveConfirm (confirm + 24h backup)
 * and are gated centrally on the Write master in executeTool, so every
 * golden-path test must seed:
 *   settingsMap.enableWrite      = true
 *   stateMap.lastBackupTimestamp = 1234567890000L  (matches HarnessSpec's fixed now())
 *   args.confirm                 = true
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

    // asynchttpGet/pauseExecution are AppExecutor API methods: metaClass stubbing silently
    // no-ops for those, and the shared per-spec-class mock only honors interactions declared
    // in setupSpec (the additive-stub pattern; see HarnessSpec's buildAppExecutorMock note).
    @spock.lang.Shared
    List asyncCalls = []

    def setupSpec() {
        appExecutor.asynchttpGet(*_) >> { args -> asyncCalls << [cb: args[0], params: args[1]] }
        appExecutor.pauseExecution(_) >> null
    }

    private void enableWrite() {
        settingsMap.enableWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L  // matches fixed now()
    }

    // -------- toolRebootHub --------

    def "hub_reboot throws when confirm is not provided"() {
        given:
        enableWrite()

        when:
        script.toolRebootHub([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
        ex.message.contains('confirm=true')
    }

    def "hub_reboot throws when Write tools are disabled"() {
        given:
        settingsMap.enableWrite = false

        when:
        script.executeTool('hub_reboot', [confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Write tools are disabled')
    }

    def "hub_reboot throws when no recent backup is available"() {
        given:
        settingsMap.enableWrite = true
        // No stateMap.lastBackupTimestamp — requireDestructiveConfirm's 24h window fails
        // (and /hub2/localBackups is unstubbed, so the list fallback degrades to null)

        when:
        script.toolRebootHub([confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('BACKUP REQUIRED')
        ex.message.contains('hub_create_backup')
    }

    // -------- requireDestructiveConfirm backup-list fallback (issue #361) --------
    // The gate's private stamp goes stale whenever a backup exists that this app never
    // confirmed (scheduled/UI backups, or a create whose completion check missed). The
    // fallback reads the hub's own local backup list -- ground truth for recovery points.

    def "gate accepts a fresh backup from the hub's local backup list when the app stamp is missing (issue #361)"() {
        given:
        settingsMap.enableWrite = true
        // No stateMap.lastBackupTimestamp. Fixed harness now() = 1234567890000 (2009-02-13T23:31:30Z);
        // this entry is ~3.5h old -- within the 24h window.
        hubGet.register('/hub2/localBackups') { params -> '[{"name":"2009-02-13~2.5.0.159.lzf","createTimeOrig":"2009-02-13T20:00:00+0000"}]' }
        def postedPath = null
        script.metaClass.hubInternalPost = { String path, Map body = null -> postedPath = path; 'ok' }

        when:
        def result = script.toolRebootHub([confirm: true])

        then:
        postedPath == '/hub/reboot'
        result.success == true
        stateMap.lastBackupTimestamp == 1234555200000L   // the list's epoch is cached into the stamp
    }

    def "gate caches the list find, so a second gated call skips the list read"() {
        given:
        settingsMap.enableWrite = true
        hubGet.register('/hub2/localBackups') { params -> '[{"name":"a.lzf","createTimeOrig":"2009-02-13T20:00:00+0000"}]' }
        script.metaClass.hubInternalPost = { String path, Map body = null -> 'ok' }

        when:
        script.toolRebootHub([confirm: true])
        script.toolRebootHub([confirm: true])

        then:
        hubGet.calls.count { it.path == '/hub2/localBackups' } == 1
    }

    def "the backup-list fallback ignores backups older than 24h"() {
        given:
        settingsMap.enableWrite = true
        // ~3.98 days before the fixed harness now() -- outside the 24h window.
        hubGet.register('/hub2/localBackups') { params -> '[{"name":"old.lzf","createTimeOrig":"2009-02-10T00:00:00+0000"}]' }

        when:
        script.toolRebootHub([confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('BACKUP REQUIRED')
        ex.message.contains('backup list')
        stateMap.lastBackupTimestamp == null      // a stale list entry must NOT stamp the gate
    }

    def "the fallback parses an offset-less createTimeOrig as UTC and skips entries without one"() {
        given:
        settingsMap.enableWrite = true
        // First entry has no createTimeOrig (skipped); the second is suffix-less -- the
        // SimpleDateFormat UTC fallback must resolve it to the same epoch as the +0000 form.
        hubGet.register('/hub2/localBackups') { params -> '[{"name":"junk.lzf"},{"name":"a.lzf","createTimeOrig":"2009-02-13T20:00:00"}]' }
        script.metaClass.hubInternalPost = { String path, Map body = null -> 'ok' }

        when:
        def result = script.toolRebootHub([confirm: true])

        then:
        result.success == true
        stateMap.lastBackupTimestamp == 1234555200000L   // == the +0000 parse of the same instant
    }

    def "a malformed backup list leaves the gate closed"() {
        given:
        settingsMap.enableWrite = true
        hubGet.register('/hub2/localBackups') { params -> '<html>login required</html>' }

        when:
        script.toolRebootHub([confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('BACKUP REQUIRED')
    }

    // -------- toolCreateHubBackup (async trigger; never slurps the .lzf) --------

    def "hub_create_backup triggers /hub/backupDB asynchronously and confirms via statusJson"() {
        given:
        settingsMap.enableWrite = true
        asyncCalls.clear()
        hubGet.register('/hub/backup/statusJson') { params -> '{"backupInProgress":false,"cloudBackupInProgress":false}' }

        when:
        def result = script.toolCreateHubBackup([confirm: true])

        then:
        asyncCalls.size() == 1
        asyncCalls[0].params.path == '/hub/backupDB'    // the hub creates the backup...
        asyncCalls[0].params.query == [fileName: 'latest']
        asyncCalls[0].cb == 'backupResponseSink'        // ...and the .lzf body is discarded, never read here
        result.success == true
        result.confirmed == true
        stateMap.lastBackupTimestamp != null
    }

    def "hub_create_backup does NOT satisfy the destructive-confirm gate when completion is unconfirmed"() {
        given:
        settingsMap.enableWrite = true
        asyncCalls.clear()
        stateMap.remove('lastBackupTimestamp')
        // No statusJson registration -> hubInternalGet returns null -> never confirmed. An unverified
        // backup must report failure and leave the 24h gate UNSTAMPED (else destructive ops could run
        // believing a recovery point exists when it may not -- a silent failure).

        when:
        def result = script.toolCreateHubBackup([confirm: true])

        then:
        result.success == false
        result.confirmed == false
        stateMap.lastBackupTimestamp == null
        result.error.toLowerCase().contains('could not be confirmed')
    }

    def "hub_create_backup mock=true stamps the gate record without any backup work (developer mode)"() {
        given:
        settingsMap.enableWrite = true
        settingsMap.enableDeveloperMode = true
        asyncCalls.clear()

        when:
        def result = script.toolCreateHubBackup([confirm: true, mock: true])

        then:
        asyncCalls.isEmpty()                       // no /hub/backupDB trigger at all
        result.success == true
        result.mocked == true
        result.message.contains('MOCK')
        stateMap.lastBackupTimestamp != null
    }

    def "hub_create_backup mock=true is refused without Developer Mode"() {
        given:
        settingsMap.enableWrite = true
        settingsMap.enableDeveloperMode = false

        when:
        script.toolCreateHubBackup([confirm: true, mock: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Developer Mode')
    }

    def "hub_create_backup still requires confirm"() {
        given:
        settingsMap.enableWrite = true

        when:
        script.toolCreateHubBackup([:])

        then:
        thrown(IllegalArgumentException)
    }

    def "hub_reboot posts to /hub/reboot and reports success"() {
        given:
        enableWrite()
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

    def "hub_update_firmware fires checkForUpdate then updatePlatform (no plain-reboot POST)"() {
        given:
        enableWrite()
        def gets = []
        hubGet.register('/hub/cloud/checkForUpdate') { params -> gets << '/hub/cloud/checkForUpdate'; '{"version":"2.5.0.157","upgrade":true,"status":"UPDATE_AVAILABLE","accountEmails":["secret@example.com"]}' }
        hubGet.register('/hub/cloud/updatePlatform') { params -> gets << '/hub/cloud/updatePlatform'; '{"success":"true"}' }
        def postedPath = null
        script.metaClass.hubInternalPost = { String path, Map body = null -> postedPath = path; 'ok' }

        when:
        def result = script.toolUpdateFirmware([confirm: true])

        then:
        gets == ['/hub/cloud/checkForUpdate', '/hub/cloud/updatePlatform']
        postedPath == null                       // firmware install must NOT plain-reboot
        result.success == true
        result.message.contains('Firmware update')
        // available is the checkForUpdate payload returned VERBATIM (transparent -- the owner's own
        // account email is intentionally surfaced, not redacted).
        result.available.version == '2.5.0.157'
        result.available.upgrade == true
        result.available.status == 'UPDATE_AVAILABLE'
        result.available.accountEmails == ['secret@example.com']
        result.warning.contains('hub_get_info')
    }

    def "hub_update_firmware available falls back to raw text when checkForUpdate is not JSON"() {
        given:
        enableWrite()
        hubGet.register('/hub/cloud/checkForUpdate') { params -> '<html>login</html>' }   // non-JSON
        hubGet.register('/hub/cloud/updatePlatform') { params -> 'ok' }

        when:
        def result = script.toolUpdateFirmware([confirm: true])

        then:
        result.success == true                      // a malformed check never fails the apply
        result.available.raw == '<html>login</html>'
    }

    def "hub_update_firmware requires the destructive confirm gate to apply"() {
        given:
        enableWrite()

        when:
        script.toolUpdateFirmware([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
    }

    def "hub_update_firmware statusOnly polls checkUpdateStatus without applying or a confirm gate"() {
        given:
        def gets = []
        hubGet.register('/hub/cloud/checkUpdateStatus') { params -> gets << '/hub/cloud/checkUpdateStatus'; '{"status":"IDLE"}' }
        def postedPath = null
        script.metaClass.hubInternalPost = { String path, Map body = null -> postedPath = path; 'ok' }

        when:
        def result = script.toolUpdateFirmware([statusOnly: true])   // no confirm needed

        then:
        gets == ['/hub/cloud/checkUpdateStatus']
        postedPath == null
        result.success == true
        result.statusOnly == true
        result.status.status == 'IDLE'
    }

    def "hub_update_firmware statusOnly returns the raw string when checkUpdateStatus is not JSON"() {
        given:
        // The hub returns a non-JSON body mid-reboot; the poll must degrade to the truncated string,
        // not throw. (status is documented as Map-or-String.)
        hubGet.register('/hub/cloud/checkUpdateStatus') { params -> 'UPDATING' }

        when:
        def result = script.toolUpdateFirmware([statusOnly: true])

        then:
        result.success == true
        result.statusOnly == true
        result.status == 'UPDATING'
    }

    def "hub_update_firmware statusOnly surfaces a poll failure instead of false-greening"() {
        given:
        hubGet.register('/hub/cloud/checkUpdateStatus') { params -> throw new RuntimeException('boom') }

        when:
        def result = script.toolUpdateFirmware([statusOnly: true])

        then:
        result.success == false
        result.error.contains('Update status poll failed')
    }

    def "hub_update_firmware surfaces an install failure instead of false-greening"() {
        given:
        enableWrite()
        hubGet.register('/hub/cloud/checkForUpdate') { params -> '{"upgrade":true}' }
        hubGet.register('/hub/cloud/updatePlatform') { params -> throw new RuntimeException('boom') }

        when:
        def result = script.toolUpdateFirmware([confirm: true])

        then:
        result.success == false
        result.error.contains('Firmware update failed')
    }

    @spock.lang.Unroll
    def "hub_update_firmware via dispatch applies the platform update (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        hubGet.register('/hub/cloud/checkForUpdate') { params -> '{"upgrade":true}' }
        hubGet.register('/hub/cloud/updatePlatform') { params -> '{"success":"true"}' }

        when:
        def response = mcpDriver.callTool('hub_update_firmware', [confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.message.contains('Firmware update')

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_reboot via dispatch posts and reports success (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        def postedPath = null
        script.metaClass.hubInternalPost = { String path, Map body = null ->
            postedPath = path
            return 'ok'
        }

        when:
        def response = mcpDriver.callTool('hub_reboot', [confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        postedPath == '/hub/reboot'
        inner.success == true
        inner.message.contains('reboot')
        inner.warning.contains('automations')
        inner.response == 'ok'

        where:
        useGateways << [true, false]
    }

    def "hub_reboot reports failure when the hub POST throws"() {
        given:
        enableWrite()
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

    @spock.lang.Unroll
    def "hub_reboot via dispatch maps missing-confirm IAE to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_reboot', [:])

        then:
        response.error != null
        response.error.code == -32602
        response.error.message.contains('SAFETY CHECK FAILED')
        response.error.message.contains('confirm=true')

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_reboot via dispatch returns success=false envelope when hub POST throws (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        script.metaClass.hubInternalPost = { String path, Map body = null ->
            throw new RuntimeException('Connection refused')
        }

        when:
        def response = mcpDriver.callTool('hub_reboot', [confirm: true])

        then: 'tool catches the exception internally and reports success=false via the success envelope'
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error.contains('Reboot failed')
        inner.error.contains('Connection refused')

        where:
        useGateways << [true, false]
    }

    // -------- toolShutdownHub --------

    def "hub_shutdown throws when confirm is not provided"() {
        given:
        enableWrite()

        when:
        script.toolShutdownHub([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
        ex.message.contains('confirm=true')
    }

    def "hub_shutdown throws when Write tools are disabled"() {
        given:
        settingsMap.enableWrite = false

        when:
        script.executeTool('hub_shutdown', [confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Write tools are disabled')
    }

    def "hub_shutdown posts to /hub/shutdown and warns about manual restart"() {
        given:
        enableWrite()
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

    @spock.lang.Unroll
    def "hub_shutdown via dispatch posts and reports success (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        def postedPath = null
        script.metaClass.hubInternalPost = { String path, Map body = null ->
            postedPath = path
            return 'shutdown initiated'
        }

        when:
        def response = mcpDriver.callTool('hub_shutdown', [confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        postedPath == '/hub/shutdown'
        inner.success == true
        inner.message.contains('shutdown initiated')
        inner.warning.contains('unplug')

        where:
        useGateways << [true, false]
    }

    def "hub_shutdown reports failure when the hub POST throws"() {
        given:
        enableWrite()
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

    @spock.lang.Unroll
    def "hub_shutdown via dispatch maps Write-disabled IAE to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableWrite = false

        when:
        def response = mcpDriver.callTool('hub_shutdown', [confirm: true])

        then:
        response.error != null
        response.error.code == -32602
        response.error.message.contains('Write tools are disabled')

        where:
        useGateways << [true, false]
    }

    // -------- toolDeleteDevice --------

    def "hub_delete_device throws when confirm is not provided"() {
        given:
        enableWrite()

        when:
        script.toolDeleteDevice([deviceId: '42'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
        ex.message.contains('confirm=true')
    }

    def "hub_delete_device throws when Write tools are disabled"() {
        given:
        settingsMap.enableWrite = false

        when:
        script.executeTool('hub_delete_device', [deviceId: '42', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Write tools are disabled')
    }

    def "hub_delete_device throws when deviceId is missing"() {
        given:
        enableWrite()

        when:
        script.toolDeleteDevice([confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('deviceId is required')
    }

    def "hub_delete_device throws when the device is not found on the hub"() {
        given:
        enableWrite()
        hubGet.register('/device/fullJson/999') { params -> null }

        when:
        script.toolDeleteDevice([deviceId: '999', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('not found on hub')
        ex.message.contains('999')
    }

    def "hub_delete_device force-deletes the device and verifies the deletion"() {
        given:
        enableWrite()

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

    @spock.lang.Unroll
    def "hub_delete_device via dispatch force-deletes and verifies (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        def lookupCalls = 0
        hubGet.register('/device/fullJson/42') { params ->
            lookupCalls++
            lookupCalls == 1
                ? '{"id": 42, "label": "Old Switch", "name": "Generic Switch", "typeName": "Virtual Switch", "deviceNetworkId": "mcp-virtual-123"}'
                : null
        }
        hubGet.register('/device/forceDelete/42/yes') { params -> 'ok' }

        when:
        def response = mcpDriver.callTool('hub_delete_device', [deviceId: '42', confirm: true])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        lookupCalls == 2
        inner.success == true
        inner.deviceId == '42'
        inner.deviceName == 'Old Switch'
        inner.message.contains('permanently deleted')
        inner.auditInfo.deviceType == 'Virtual Switch'

        where:
        useGateways << [true, false]
    }

    def "hub_delete_device reports force-delete failure without throwing"() {
        given:
        enableWrite()
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

    @spock.lang.Unroll
    def "hub_delete_device via dispatch maps deviceId-missing IAE to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_delete_device', [confirm: true])

        then:
        response.error != null
        response.error.code == -32602
        response.error.message.contains('deviceId is required')

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_delete_device via dispatch maps device-not-found IAE to -32602 (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()
        hubGet.register('/device/fullJson/999') { params -> null }

        when:
        def response = mcpDriver.callTool('hub_delete_device', [deviceId: '999', confirm: true])

        then:
        response.error != null
        response.error.code == -32602
        response.error.message.contains('not found on hub')
        response.error.message.contains('999')

        where:
        useGateways << [true, false]
    }
}
