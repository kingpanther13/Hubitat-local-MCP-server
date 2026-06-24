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

    def "scope=hub_uploaded fetches the URL, multipart-uploads it, then restores"() {
        given:
        enableWrite()
        def uploaded = [:]
        script.metaClass.httpGet = { Map params, Closure cb -> cb.call([data: 'BACKUPBYTES'.getBytes('UTF-8')]) }
        script.metaClass.httpPost = { Map params, Closure cb -> uploaded.path = params.path; cb.call([data: [success: true], status: 200]) }

        when:
        def r = script.toolRestoreItemBackup([scope: 'hub_uploaded', backupUrl: 'https://host/b.lzf', confirm: true])

        then:
        r.success == true
        r.location == 'hub_uploaded'
        uploaded.path == '/hub2/uploadBackup'
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
}
