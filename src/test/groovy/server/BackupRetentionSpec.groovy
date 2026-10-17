package server

import groovy.json.JsonOutput
import support.ToolSpecBase

/**
 * Backup index storage and retention (issues #506 / #517): the index lives in a File Manager
 * file instead of atomicState, an older atomicState index migrates into it, a missing index is
 * rebuilt from the File Manager listing, and backups are evicted only past the configurable byte budget.
 * The index file itself is the harness store here; BackupIndexFileSpec drives the real file calls.
 */
class BackupRetentionSpec extends ToolSpecBase {
    private static final long MIB = 1024L * 1024L

    private Map app(String id, long timestamp, long sizeBytes, Map extra = [:]) {
        [type: 'app', id: id, fileName: "mcp-backup-app-${id}.groovy".toString(), timestamp: timestamp,
         version: 1, sourceLength: sizeBytes, sizeBytes: sizeBytes, retention: 'pinned'] + extra
    }

    private Map rule(Integer ruleId, String suffix, long timestamp, long sizeBytes, String retention) {
        [type: 'rm-rule', id: ruleId, ruleId: ruleId, fileName: "mcp-rm-backup-${ruleId}-${suffix}.json".toString(),
         timestamp: timestamp, sourceLength: sizeBytes, sizeBytes: sizeBytes, retention: retention]
    }

    private List deletedFiles() {
        List deleted = []
        script.metaClass.deleteHubFile = { String name -> deleted << name }
        return deleted
    }

    // ---------- migration out of atomicState ----------

    def 'an atomicState index moves into the index file and the app-state key is removed'() {
        given:
        atomicStateMap.itemBackupManifest = [app_1: app('1', 5L, 10L)]
        backupIndex = null

        when:
        def view = script._itemBackupManifest()

        then:
        view.keySet() == ['app_1'] as Set
        !atomicStateMap.containsKey('itemBackupManifest')
        backupIndex == [app_1: app('1', 5L, 10L)]
        hubGet.calls.empty // no listing rebuild when an older index exists
    }

    def 'a failed index-file write keeps the app-state index until the next write succeeds'() {
        given:
        atomicStateMap.itemBackupManifest = [app_1: app('1', 5L, 10L)]
        backupIndex = null
        boolean failWrites = true
        def storeRef = backupIndexStore
        script.metaClass._writeItemBackupIndexStore = { String json ->
            if (failWrites) throw new IllegalStateException('file manager full')
            storeRef.json = json
        }

        when:
        def view = script._itemBackupManifest()

        then: 'the legacy index still serves and stays durable'
        view.keySet() == ['app_1'] as Set
        atomicStateMap.containsKey('itemBackupManifest')
        backupIndex == null

        when:
        failWrites = false
        script._publishItemBackup('app_2', app('2', 6L, 10L))

        then: 'the first successful write finishes the move'
        backupIndex.keySet() == ['app_1', 'app_2'] as Set
        !atomicStateMap.containsKey('itemBackupManifest')
    }

    def 'publishing writes the index file and never the retired app-state key'() {
        given:
        backupIndex = [app_1: app('1', 5L, 10L)]

        when:
        script._publishItemBackup('app_2', app('2', 6L, 10L))

        then:
        !atomicStateMap.containsKey('itemBackupManifest')
        backupIndex.keySet() == ['app_1', 'app_2'] as Set
    }

    // ---------- rebuild from the File Manager listing ----------

    private void listFiles(List files) {
        hubGet.register('/hub/fileManager/json') { params ->
            JsonOutput.toJson([files: files, freeSpace: 1000])
        }
    }

    def 'a missing index is rebuilt from the listing and adopts backup files from older versions'() {
        given:
        backupIndex = null
        String uuid = '0f8fad5b-d9cb-469f-a165-70867728950e'
        listFiles([
            [name: 'mcp-backup-app-12.groovy', type: 'file', size: '50', date: '1000'],
            [name: "mcp-backup-app-12-${uuid}.groovy".toString(), type: 'file', size: '60', date: '2000'],
            [name: 'mcp-backup-library-3.groovy', type: 'file', size: '70', date: '1500'],
            [name: 'mcp-prerestore-driver-7.groovy', type: 'file', size: '80', date: '1700'],
            [name: 'mcp-rm-backup-5-20260101-120000-123.json', type: 'file', size: '90', date: '1800'],
            [name: "mcp-rm-backup-5-20260101-130000-000-${uuid}.json".toString(), type: 'file', size: '95', date: '1900'],
            [name: 'mcp-backup-index.json', type: 'file', size: '10', date: '1'],
            [name: 'mcp-source-app-12.groovy', type: 'file', size: '999', date: '1'],
            [name: 'notes_backup_20260101-000000.txt', type: 'file', size: '5', date: '1'],
            [name: 'mcp-backup-app-12', type: 'dir', size: '0']
        ])

        when:
        def view = script._itemBackupManifest()

        then: 'the newest copy of an item keeps the restore key, older copies stay indexed'
        view.keySet() == ['app_12', 'app_12_1', 'library_3', 'prerestore_driver_7',
                          'rm-rule_5_20260101-120000-123', "rm-rule_5_20260101-130000-000-${uuid}".toString()] as Set
        view.app_12.fileName == "mcp-backup-app-12-${uuid}.groovy".toString()
        view.app_12.timestamp == 2000L
        view.app_12_1.fileName == 'mcp-backup-app-12.groovy'
        view.prerestore_driver_7.type == 'driver'
        view.prerestore_driver_7.id == '7'
        view.library_3.sizeBytes == 70L

        and: 'rule snapshots take their timestamp from the file name'
        def ruleEntry = view['rm-rule_5_20260101-120000-123']
        ruleEntry.ruleId == 5
        ruleEntry.timestamp == Date.parse('yyyyMMdd-HHmmss-SSS', '20260101-120000-123').time
        ruleEntry.sizeBytes == 90L

        and: 'adopted files are pinned and the rebuilt index is saved'
        view.values().every { it.retention == 'pinned' }
        backupIndex.keySet() == view.keySet()
    }

    def 'an unreadable index file is rebuilt from the listing'() {
        given:
        backupIndexStore.json = '{not json'
        listFiles([[name: 'mcp-backup-driver-4.groovy', type: 'file', size: '40', date: '400']])

        when:
        def view = script._itemBackupManifest()

        then:
        view.keySet() == ['driver_4'] as Set
        backupIndex.keySet() == ['driver_4'] as Set
    }

    def 'a failed listing leaves the index empty without writing or deleting anything'() {
        given:
        backupIndex = null
        hubGet.register('/hub/fileManager/json') { params -> throw new RuntimeException('listing unavailable') }
        List deleted = deletedFiles()

        when:
        def view = script._itemBackupManifest()

        then:
        view.isEmpty()
        backupIndex == null
        deleted.isEmpty()
    }

    // ---------- byte budget ----------

    def 'over the byte budget, rolling steps go before older pinned baselines of other items'() {
        given:
        backupIndex = [
            'rm-rule_5_a': rule(5, 'a', 1L, 4 * MIB, 'pinned'),
            'rm-rule_6_a': rule(6, 'a', 5L, 3 * MIB, 'rolling'),
            app_1: app('1', 2L, 3 * MIB)
        ]
        List deleted = deletedFiles()

        when:
        script._publishItemBackup('app_9', app('9', 10L, MIB))

        then:
        backupIndex.keySet() == ['rm-rule_5_a', 'app_1', 'app_9'] as Set
        deleted == ['mcp-rm-backup-6-a.json']
    }

    def 'pinned baselines go oldest first, never the published entry or the restore target'() {
        given:
        backupIndex = [
            app_2: app('2', 0L, MIB),
            'rm-rule_5_a': rule(5, 'a', 1L, 4 * MIB, 'pinned'),
            app_1: app('1', 2L, 4 * MIB)
        ]
        List deleted = deletedFiles()

        when:
        script._publishItemBackup('app_9', app('9', 10L, 3 * MIB), 'app_2')

        then:
        backupIndex.keySet() == ['app_2', 'app_1', 'app_9'] as Set
        deleted == ['mcp-rm-backup-5-a.json']
    }

    def 'a single backup larger than the budget is kept and everything evictable goes'() {
        given:
        backupIndex = [app_1: app('1', 1L, MIB), app_2: app('2', 2L, MIB)]
        deletedFiles()

        when:
        script._publishItemBackup('app_9', app('9', 10L, 11 * MIB))

        then:
        backupIndex.keySet() == ['app_9'] as Set
    }

    def 'entries without sizeBytes count their sourceLength against the budget'() {
        expect:
        script._itemBackupEntryBytes([sourceLength: 1234]) == 1234L
        script._itemBackupEntryBytes([sizeBytes: 10, sourceLength: 1234]) == 10L
        script._itemBackupEntryBytes([:]) == 0L
    }

    def 'under the budget no backup is evicted, however many rolling steps or items there are'() {
        given: 'forty rolling steps of one rule, plus another rule and an app'
        Map seeded = (1..40).collectEntries { [("rm-rule_5_s${it}".toString()): rule(5, "s${it}", (long) it, 10L, 'rolling')] }
        seeded['rm-rule_6_s1'] = rule(6, 's1', 0L, 10L, 'rolling')
        seeded['app_1'] = app('1', 0L, 10L)
        backupIndex = seeded
        List deleted = deletedFiles()

        when:
        script._publishItemBackup('rm-rule_5_s41', rule(5, 's41', 41L, 10L, 'rolling'))

        then:
        deleted.isEmpty()
        backupIndex.size() == 43
    }

    // ---------- the Backup storage budget setting ----------

    def 'the budget defaults to 10 MB and follows the backupBudgetMb setting'() {
        when:
        if (setting != null) settingsMap.backupBudgetMb = setting

        then:
        script._itemBackupByteBudget() == expectedMb * MIB

        where:
        setting | expectedMb
        null    | 10
        25      | 25
        '3'     | 3
        1       | 1
        0       | 1     // below the 1 MB minimum
        -5      | 1
        'lots'  | 10    // unreadable value keeps the default
        5000    | 5000  // no upper bound
    }

    def 'a 1 MB budget evicts what the default budget keeps'() {
        given:
        settingsMap.backupBudgetMb = 1
        backupIndex = [
            app_1: app('1', 1L, 600 * 1024L),
            'rm-rule_5_a': rule(5, 'a', 5L, 300 * 1024L, 'rolling'),
            app_2: app('2', 2L, 300 * 1024L)
        ]
        List deleted = deletedFiles()

        when: 'the new backup takes the total to 1.4 MB'
        script._publishItemBackup('app_9', app('9', 10L, 200 * 1024L))

        then: 'the rolling step goes first, then the oldest pinned baseline'
        deleted as Set == ['mcp-rm-backup-5-a.json', 'mcp-backup-app-1.groovy'] as Set
        backupIndex.keySet() == ['app_2', 'app_9'] as Set

        when:
        def listing = script.toolListItemBackups([:])

        then: 'the listing and the notes report the configured budget'
        listing.storageBudget == [usedBytes: 500 * 1024L, limitBytes: MIB]
        listing.retentionPolicy.contains('1 MB storage budget')
        script._itemBackupRetentionNote([type: 'app']).contains('the 1 MB budget')
    }

    def 'the same index stays untouched under the default budget'() {
        given:
        backupIndex = [
            app_1: app('1', 1L, 600 * 1024L),
            'rm-rule_5_a': rule(5, 'a', 5L, 300 * 1024L, 'rolling'),
            app_2: app('2', 2L, 300 * 1024L)
        ]
        List deleted = deletedFiles()

        when:
        script._publishItemBackup('app_9', app('9', 10L, 200 * 1024L))

        then:
        deleted.isEmpty()
        backupIndex.size() == 4
    }

    // ---------- what callers are told ----------

    def 'hub_list_backups reports the budget and each entry retention'() {
        given:
        backupIndex = [
            app_1: app('1', 1L, 2 * MIB),
            'rm-rule_5_a': rule(5, 'a', 2L, MIB, 'rolling'),
            app_legacy: [type: 'app', id: 'legacy', fileName: 'mcp-backup-app-legacy.groovy', timestamp: 3L, sourceLength: 500]
        ]

        when:
        def result = script.toolListItemBackups([:])

        then:
        result.storageBudget == [usedBytes: 3 * MIB + 500, limitBytes: 10 * MIB]
        result.retentionPolicy.contains('removed only when')
        result.retentionPolicy.contains('10 MB')
        !result.containsKey('maxBackups')
        result.backups.collectEntries { [(it.backupKey): it.retention] } ==
            [app_1: 'pinned', 'rm-rule_5_a': 'rolling', app_legacy: 'pinned']
        result.backups.find { it.backupKey == 'app_legacy' }.sizeBytes == 500L
    }

    def 'an empty index still reports the budget'() {
        when:
        def result = script.toolListItemBackups([:])

        then:
        result.total == 0
        result.storageBudget.usedBytes == 0L
        result.storageBudget.limitBytes == 10 * MIB
    }

    def 'retention notes name the eviction rule for each kind of backup'() {
        expect:
        script._itemBackupRetentionNote([type: 'rm-rule', retention: 'rolling']).startsWith('Rolling step')
        script._itemBackupRetentionNote([type: 'rm-rule', retention: 'rolling']).contains('removed only when the 10 MB budget')
        !script._itemBackupRetentionNote([type: 'rm-rule', retention: 'rolling']).contains('newest')
        script._itemBackupRetentionNote([type: 'rm-rule', retention: 'pinned']).contains('Backups of this or other rules never replace it')
        script._itemBackupRetentionNote([type: 'app', retention: 'pinned']).contains('next backup after the one-hour reuse window')
        script._itemBackupRetentionNote([type: 'app', retention: 'pinned', undoForBackupKey: 'app_1']).contains('next pre-restore undo')
        script._itemBackupRetentionNote([type: 'app']).startsWith('Pinned baseline')
        script._itemBackupRetentionNote([type: 'app']).contains('10 MB budget')
    }
}
