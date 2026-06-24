package server

import support.ToolSpecBase
import groovy.json.JsonSlurper

/**
 * Spec for the hub-DB (whole-hub database) backup surface added in issue #259 item #1
 * (McpItemBackupsLib): hub_create_backup (+ folded schedule), the hub-DB scopes of
 * hub_list_backups / hub_restore_backup, and hub_delete_backup. The source-code backup
 * behavior of list/get/restore is unchanged and covered by the existing item-backup specs.
 *
 * GET endpoints are stubbed via hubGet.register (the mock keys on path; the query Map is
 * recorded, not matched). The schedule POST is captured via a hubInternalPostJson override.
 * The upload path's httpGet/httpPost are overridden on the metaClass (no real I/O). enableWrite()
 * + lastBackupTimestamp satisfies the destructive-confirm gate (24h backup).
 */
class ToolBackupSpec extends ToolSpecBase {

    private Map posted

    def setup() {
        posted = [:]
        hubGet.register('/hub2/localBackups') { params -> '[{"name":"local-1.lzf","createTime":"2026-06-01","createTimeOrig":"2026-06-01T00:00:00","size":1024}]' }
        hubGet.register('/hub2/cloudBackups') { params -> '{"backups":[{"path":"cloud/abc.lzf","createTime":"2026-06-02","hubVersion":"2.5.0","hubName":"CrameHub"}]}' }
        ['/hub2/restoreLocalBackup', '/hub2/restoreCloudBackup', '/hub2/restoreUploadedBackup',
         '/hub2/deleteLocalBackup', '/hub2/deleteCloudBackup'].each { p ->
            hubGet.register(p) { params -> '{"success":true}' }
        }
        hubGet.register('/hub/backup/statusJson') { params -> '{"backupInProgress":false,"cloudBackupInProgress":false}' }
        // current schedule (read-merge source); cloud DISABLED by default so schedule tests need no password
        hubGet.register('/hub2/backup/json') { params -> '{"localBackupFrequency":1,"cloudBackupFrequency":0,"databaseCleanupTimeHour":3,"databaseCleanupJobMinute":0,"backupPassword":null}' }
        // schedule POST capture
        script.metaClass.hubInternalPostJson = { String path, String body, int t = 420, boolean r = false ->
            posted.path = path; posted.body = new JsonSlurper().parseText(body); return [success: true]
        }
        // create-backup plumbing: no real async trigger, no real sleeps, no hub-security lookup
        script.metaClass.asynchttpGet = { String handler, Map params -> null }
        script.metaClass.pauseExecution = { long ms -> null }
        script.metaClass.getHubSecurityCookie = { -> null }
    }

    private void enableWrite() {
        settingsMap.enableWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L
    }

    // ---------- hub_list_backups scope ----------

    def "scope=hub_local fetches /hub2/localBackups into hubLocalBackups"() {
        when:
        def r = script.toolListItemBackups([scope: 'hub_local'])

        then:
        r.scope == 'hub_local'
        r.hubLocalBackups.size() == 1
        r.hubLocalBackups[0].name == 'local-1.lzf'
        r.hubCloudBackups == null
    }

    def "scope=hub_cloud fetches /hub2/cloudBackups into hubCloudBackups"() {
        when:
        def r = script.toolListItemBackups([scope: 'hub_cloud'])

        then:
        r.hubCloudBackups.size() == 1
        r.hubCloudBackups[0].path == 'cloud/abc.lzf'
    }

    def "scope=all returns the source section AND both hub-DB sections"() {
        when:
        def r = script.toolListItemBackups([scope: 'all'])

        then:
        r.containsKey('backups')          // source-code section preserved
        r.hubLocalBackups != null
        r.hubCloudBackups != null
    }

    def "default scope=source does not touch the hub-DB endpoints"() {
        when:
        def r = script.toolListItemBackups([:])

        then:
        r.containsKey('backups')
        r.hubLocalBackups == null
        hubGet.calls.every { !(it.path in ['/hub2/localBackups', '/hub2/cloudBackups']) }
    }

    def "an invalid scope is rejected"() {
        when:
        script.toolListItemBackups([scope: 'bogus'])

        then:
        thrown(IllegalArgumentException)
    }

    // ---------- hub_create_backup + folded schedule ----------

    def "create with a schedule POSTs /hub2/updateBackupSchedule and reports scheduleUpdated"() {
        given:
        enableWrite()

        when:
        def r = script.toolCreateHubBackup([confirm: true, schedule: [hour: 3, minute: 30, localBackupFrequency: 1, cloudBackupFrequency: 0, cloudBackupPassword: 'pw']])

        then:
        r.success == true
        r.scheduleUpdated == true
        posted.path == '/hub2/updateBackupSchedule'
        posted.body.hour == 3
        posted.body.minute == 30
        posted.body.cloudBackupPassword == 'pw'
    }

    def "scheduleOnly sets the schedule WITHOUT creating a backup and needs no confirm"() {
        when:
        def r = script.toolCreateHubBackup([schedule: [hour: 1, minute: 0], scheduleOnly: true])

        then:
        r.success == true
        r.scheduleUpdated == true
        r.message.toLowerCase().contains('schedule')
        posted.path == '/hub2/updateBackupSchedule'
    }

    def "create without confirm throws"() {
        when:
        script.toolCreateHubBackup([:])

        then:
        thrown(IllegalArgumentException)
    }

    def "a schedule with an out-of-range hour fails as a structured error (no throw, no backup)"() {
        given:
        enableWrite()

        when:
        def r = script.toolCreateHubBackup([confirm: true, schedule: [hour: 99, minute: 0]])

        then:
        r.success == false
        r.error.toLowerCase().contains('schedule')
    }

    // ---------- hub_restore_backup hub-DB scopes ----------

    def "scope=hub_local restores by fileName (confirm-gated; warns about the reboot)"() {
        given:
        enableWrite()

        when:
        def r = script.toolRestoreItemBackup([scope: 'hub_local', fileName: 'local-1.lzf', confirm: true])

        then:
        r.success == true
        r.type == 'hub-db'
        r.location == 'hub_local'
        r.message.toLowerCase().contains('reboot')
    }

    def "scope=hub_local without confirm throws the destructive gate"() {
        given:
        settingsMap.enableWrite = true   // Write master on, but NO recent backup -> confirm gate fails

        when:
        script.toolRestoreItemBackup([scope: 'hub_local', fileName: 'x.lzf'])

        then:
        thrown(IllegalArgumentException)
    }

    def "scope=hub_local without fileName throws"() {
        given:
        enableWrite()

        when:
        script.toolRestoreItemBackup([scope: 'hub_local', confirm: true])

        then:
        thrown(IllegalArgumentException)
    }

    def "scope=hub_cloud requires cloudBackupPassword"() {
        given:
        enableWrite()

        when:
        script.toolRestoreItemBackup([scope: 'hub_cloud', confirm: true])

        then:
        thrown(IllegalArgumentException)
    }

    def "scope=hub_cloud with a password succeeds"() {
        given:
        enableWrite()

        when:
        def r = script.toolRestoreItemBackup([scope: 'hub_cloud', cloudBackupPassword: 'pw', confirm: true])

        then:
        r.success == true
        r.location == 'hub_cloud'
    }

    def "an invalid restore scope throws"() {
        given:
        enableWrite()

        when:
        script.toolRestoreItemBackup([scope: 'bogus', confirm: true])

        then:
        thrown(IllegalArgumentException)
    }

    // ---------- hub_restore_backup scope=hub_uploaded (upload-from-URL) ----------

    def "scope=hub_uploaded fetches the URL, uploads it, then restores"() {
        given:
        enableWrite()
        // The fetch + multipart helpers do real HTTP I/O the harness can't exercise (and the path
        // is unverifiable live anyway); mock them and assert the ORCHESTRATION (fetch -> upload ->
        // restoreUploaded GET -> success).
        def calls = [:]
        script.metaClass._fetchBytesFromUrl = { String url -> calls.fetched = url; 'BACKUPBYTES'.getBytes('UTF-8') }
        script.metaClass._postMultipartBackup = { String path, String field, String fileName, byte[] bytes ->
            calls.uploaded = path; calls.bytes = bytes.length; return [success: true]
        }

        when:
        def r = script.toolRestoreItemBackup([scope: 'hub_uploaded', backupUrl: 'https://host/b.lzf', confirm: true])

        then:
        r.success == true
        r.location == 'hub_uploaded'
        calls.fetched == 'https://host/b.lzf'
        calls.uploaded == '/hub2/uploadBackup'
        calls.bytes == 11
    }

    def "scope=hub_uploaded requires backupUrl"() {
        given:
        enableWrite()

        when:
        script.toolRestoreItemBackup([scope: 'hub_uploaded', confirm: true])

        then:
        thrown(IllegalArgumentException)
    }

    def "scope=hub_uploaded rejects a non-http(s) backupUrl"() {
        given:
        enableWrite()

        when:
        script.toolRestoreItemBackup([scope: 'hub_uploaded', backupUrl: 'ftp://x/y.lzf', confirm: true])

        then:
        thrown(IllegalArgumentException)
    }

    // ---------- hub_delete_backup ----------

    def "delete local removes by fileName"() {
        given:
        enableWrite()

        when:
        def r = script.toolDeleteHubBackup([location: 'local', fileName: 'local-1.lzf', confirm: true])

        then:
        r.success == true
        r.location == 'local'
    }

    def "delete cloud removes by path"() {
        given:
        enableWrite()

        when:
        def r = script.toolDeleteHubBackup([location: 'cloud', path: 'cloud/abc.lzf', confirm: true])

        then:
        r.success == true
        r.location == 'cloud'
    }

    def "delete without confirm throws"() {
        given:
        settingsMap.enableWrite = true   // no recent backup

        when:
        script.toolDeleteHubBackup([location: 'local', fileName: 'x'])

        then:
        thrown(IllegalArgumentException)
    }

    def "delete with an invalid location throws"() {
        given:
        enableWrite()

        when:
        script.toolDeleteHubBackup([location: 'bogus', confirm: true])

        then:
        thrown(IllegalArgumentException)
    }

    // ---------- failure envelopes (structured, no throw) ----------

    def "a failing hub-DB restore returns the structured error envelope (does NOT throw)"() {
        given:
        enableWrite()
        hubGet.register('/hub2/restoreLocalBackup') { params -> '{"success":false,"message":"relay down"}' }

        when:
        def r = script.toolRestoreItemBackup([scope: 'hub_local', fileName: 'x.lzf', confirm: true])

        then:
        r.success == false
        r.error.contains('relay down')
        r.note != null
    }

    def "a failing hub-DB delete returns the structured error envelope"() {
        given:
        enableWrite()
        hubGet.register('/hub2/deleteLocalBackup') { params -> '{"success":false,"message":"not found"}' }

        when:
        def r = script.toolDeleteHubBackup([location: 'local', fileName: 'x.lzf', confirm: true])

        then:
        r.success == false
        r.error.contains('not found')
        r.note != null
    }

    def "a list with a failing hub-DB source surfaces hubBackupErrors + partial (never silently complete)"() {
        given:
        hubGet.register('/hub2/cloudBackups') { params -> throw new RuntimeException('cloud unreachable') }

        when:
        def r = script.toolListItemBackups([scope: 'hub'])

        then:
        r.hubLocalBackups != null            // the local store still returned
        r.hubBackupErrors.any { it.contains('cloud') }
        r.partial == true
    }

    def "scope=hub lists BOTH DB stores and omits the source section"() {
        when:
        def r = script.toolListItemBackups([scope: 'hub'])

        then:
        r.hubLocalBackups != null
        r.hubCloudBackups != null
        !r.containsKey('backups')
    }

    def "a schedule omitting hour/minute read-merges them from the current schedule"() {
        given:
        enableWrite()

        when:
        def r = script.toolCreateHubBackup([confirm: true, schedule: [localBackupFrequency: 2]])

        then:
        r.success == true
        r.scheduleUpdated == true
        posted.body.hour == 3                  // merged from /hub2/backup/json databaseCleanupTimeHour
        posted.body.minute == 0                // merged from databaseCleanupJobMinute
        posted.body.localBackupFrequency == 2  // caller override
        posted.body.cloudBackupFrequency == 0  // merged from current (cloud disabled)
    }

    def "a schedule with cloud backup enabled but no cloudBackupPassword is refused (won't blank the password)"() {
        given:
        enableWrite()
        hubGet.register('/hub2/backup/json') { params -> '{"localBackupFrequency":1,"cloudBackupFrequency":7,"databaseCleanupTimeHour":3,"databaseCleanupJobMinute":0,"backupPassword":null}' }

        when:
        def r = script.toolCreateHubBackup([confirm: true, schedule: [hour: 4, minute: 0]])

        then:
        r.success == false
        r.error.toLowerCase().contains('cloud')
    }

    def "a schedule with a non-numeric hour fails as a structured error (no NumberFormatException leak)"() {
        given:
        enableWrite()

        when:
        def r = script.toolCreateHubBackup([confirm: true, schedule: [hour: 'abc', minute: 0]])

        then:
        r.success == false
        r.error.toLowerCase().contains('integer') || r.error.toLowerCase().contains('schedule')
    }

    def "a create that never confirms returns success:false and does NOT stamp the destructive-confirm gate"() {
        given:
        settingsMap.enableWrite = true            // Write master on, but NO prior backup stamp
        stateMap.remove('lastBackupTimestamp')
        // statusJson never reports completion -> the backup is unverified
        hubGet.register('/hub/backup/statusJson') { params -> '{"backupInProgress":true,"cloudBackupInProgress":false}' }

        when:
        def r = script.toolCreateHubBackup([confirm: true])

        then:
        r.success == false
        r.confirmed == false
        stateMap.lastBackupTimestamp == null      // the 24h gate is NOT satisfied by an unverified backup
        r.note.toLowerCase().contains('not')
    }

    // ---------- dispatch envelopes ----------

    def "hub_delete_backup via dispatch returns the success envelope"() {
        given:
        enableWrite()

        when:
        def resp = mcpDriver.callTool('hub_delete_backup', [location: 'local', fileName: 'local-1.lzf', confirm: true])

        then:
        mcpDriver.parseInner(resp).success == true
    }

    def "hub_create_backup via dispatch returns the success envelope"() {
        given:
        enableWrite()

        when:
        def resp = mcpDriver.callTool('hub_create_backup', [confirm: true])

        then:
        mcpDriver.parseInner(resp).success == true
    }

    def "hub_delete_backup via dispatch surfaces a validation-error envelope for a bad location"() {
        given:
        enableWrite()

        when:
        def resp = mcpDriver.callTool('hub_delete_backup', [location: 'bogus', confirm: true])

        then: 'an IllegalArgumentException maps to a -32602 (or isError) envelope, not a success'
        resp.error?.code == -32602 || resp.result?.isError == true
    }

    def "scheduleOnly without a schedule object falls through to a normal create (scheduleOnly is a no-op)"() {
        given:
        enableWrite()

        when:
        def r = script.toolCreateHubBackup([confirm: true, scheduleOnly: true])

        then:
        r.success == true
        r.scheduleUpdated == false
    }
}
