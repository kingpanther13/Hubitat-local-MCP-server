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
         version: 1, sourceLength: sizeBytes, sizeBytes: sizeBytes] + extra
    }

    private Map rule(Integer ruleId, String suffix, long timestamp, long sizeBytes) {
        [type: 'rm-rule', id: ruleId, ruleId: ruleId, fileName: "mcp-rm-backup-${ruleId}-${suffix}.json".toString(),
         timestamp: timestamp, sourceLength: sizeBytes, sizeBytes: sizeBytes]
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

        and: 'adopted entries carry no retention kind and the rebuilt index is saved'
        view.values().every { !it.containsKey('retention') }
        backupIndex.keySet() == view.keySet()
    }

    def 'an index file that is not valid JSON is copied aside before the rebuild'() {
        given:
        backupIndexStore.json = '{not json'
        listFiles([[name: 'mcp-backup-driver-4.groovy', type: 'file', size: '40', date: '400']])
        Map uploads = [:]
        script.metaClass.uploadHubFile = { String name, byte[] bytes -> uploads[name] = new String(bytes, 'UTF-8') }

        when:
        def view = script._itemBackupManifest()

        then:
        uploads.size() == 1
        uploads.keySet().first() ==~ /mcp-backup-index\.corrupt-\d{8}-\d{6}\.json/
        uploads.values().first() == '{not json'
        view.keySet() == ['driver_4'] as Set
        backupIndex.keySet() == ['driver_4'] as Set
    }

    def 'an index file without an entries map is copied aside the same way'() {
        given:
        backupIndexStore.json = '{"version":1}'
        listFiles([])
        List uploads = []
        script.metaClass.uploadHubFile = { String name, byte[] bytes -> uploads << name }

        when:
        script._itemBackupManifest()

        then:
        uploads.size() == 1
        uploads[0].startsWith('mcp-backup-index.corrupt-')
        backupIndex == [:]
    }

    def 'an invalid index is still rebuilt when it cannot be copied aside'() {
        given:
        backupIndexStore.json = '{not json'
        listFiles([[name: 'mcp-backup-driver-4.groovy', type: 'file', size: '40', date: '400']])
        script.metaClass.uploadHubFile = { String name, byte[] bytes -> throw new IllegalStateException('disk full') }

        when:
        def view = script._itemBackupManifest()

        then: 'the failed aside copy does not hold every backup operation hostage'
        view.keySet() == ['driver_4'] as Set
        backupIndex.keySet() == ['driver_4'] as Set
    }

    def 'a listed index that cannot be read is never rebuilt over, and loads once it reads again'() {
        given:
        backupIndex = [app_1: app('1', 5L, 10L), app_2: app('2', 6L, 10L)]
        String good = backupIndexStore.json
        backupIndexStore.readError = new IllegalStateException("'mcp-backup-index.json' is listed in File Manager but could not be read after 3 attempts (timeout)")
        listFiles([])

        when:
        script._itemBackupManifest()

        then: 'the load throws, nothing is written over the index and nothing is cached'
        def e = thrown(IllegalStateException)
        e.message.startsWith('Backup index unavailable')
        e.message.contains('could not be read')
        backupIndexStore.json == good
        (scriptStaticField('ITEM_BACKUP_MANIFESTS') as Map).isEmpty()

        when: 'the read recovers'
        backupIndexStore.readError = null
        script._publishItemBackup('app_3', app('3', 7L, 10L))

        then: 'the publish sees the full index'
        backupIndex.keySet() == ['app_1', 'app_2', 'app_3'] as Set
    }

    def 'a failed listing while rebuilding throws, caches nothing, and a later load works'() {
        given:
        backupIndex = null
        boolean listingDown = true
        hubGet.register('/hub/fileManager/json') { params ->
            if (listingDown) throw new RuntimeException('listing unavailable')
            JsonOutput.toJson([files: [[name: 'mcp-backup-app-7.groovy', type: 'file', size: '9', date: '70']]])
        }
        List deleted = deletedFiles()

        when:
        script._itemBackupManifest()

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('listing unavailable')
        backupIndex == null
        deleted.isEmpty()
        (scriptStaticField('ITEM_BACKUP_MANIFESTS') as Map).isEmpty()

        when:
        listingDown = false
        def view = script._itemBackupManifest()

        then:
        view.keySet() == ['app_7'] as Set
    }

    def 'with the index unavailable, readers and backup-taking writes return a recoverable refusal'() {
        given:
        settingsMap.enableWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L
        backupIndexStore.readError = new IllegalStateException('listed but unreadable')

        when:
        def listing = script.toolListItemBackups([:])
        def fetched = script.toolGetItemBackup([backupKey: 'app_1'])
        def deleted = script.toolDeleteItem([type: 'app', item_id: '33', confirm: true])

        then:
        [listing, fetched, deleted].every {
            it.success == false && it.error.startsWith('Backup index unavailable') && it.note.contains('Nothing was changed')
        }
        listing.backups == []
        deleted.appId == '33'
        !hubGet.calls.any { it.path.contains('delete') }
    }

    // ---------- migration merge ----------

    def 'a legacy index and an index file merge, the file winning on shared keys'() {
        given:
        backupIndex = [app_1: app('1', 9L, 10L, [version: 2]), app_3: app('3', 9L, 10L)]
        atomicStateMap.itemBackupManifest = [app_1: app('1', 5L, 10L, [version: 1]), app_2: app('2', 5L, 10L)]

        when:
        def view = script._itemBackupManifest()

        then:
        view.keySet() == ['app_1', 'app_2', 'app_3'] as Set
        view.app_1.version == 2
        backupIndex.keySet() == ['app_1', 'app_2', 'app_3'] as Set
        !atomicStateMap.containsKey('itemBackupManifest')
    }

    // atomicState whose removal of the legacy index key fails.
    private static class StickyLegacyState extends LinkedHashMap {
        Object remove(Object key) {
            if (key == 'itemBackupManifest') throw new IllegalStateException('state store busy')
            super.remove(key)
        }
    }

    def 'a stale legacy index left by a failed removal cannot overwrite newer file entries after a reload'() {
        given:
        def state = new StickyLegacyState()
        state.put('itemBackupManifest', [app_1: app('1', 5L, 10L, [version: 1])])
        backupIndex = null
        def peer = newCompiledScriptInstance([app: new support.TestChildApp(id: 1L), state: stateMap, atomicState: state])
        peer.metaClass.deleteHubFile = { String name -> }

        when: 'the move succeeds but the key stays, then newer backups are published'
        peer._itemBackupManifest()
        peer._publishItemBackup('app_1', app('1', 8L, 10L, [version: 2, fileName: 'mcp-backup-app-1-new.groovy']))
        peer._publishItemBackup('app_3', app('3', 9L, 10L))

        and: 'a reload loses the in-memory record that the key was left behind'
        (scriptStaticField('ITEM_BACKUP_MANIFESTS') as Map).clear()
        (scriptStaticField('ITEM_BACKUP_LEGACY_PENDING') as Set).clear()
        def view = peer._itemBackupManifest()

        then:
        state.containsKey('itemBackupManifest')
        view.keySet() == ['app_1', 'app_3'] as Set
        view.app_1.version == 2
        backupIndex.app_1.fileName == 'mcp-backup-app-1-new.groovy'
        backupIndex.containsKey('app_3')
    }

    // ---------- byte budget ----------

    def 'over the byte budget the oldest backups go first, whatever their kind'() {
        given:
        backupIndex = [
            'rm-rule_5_a': rule(5, 'a', 1L, 4 * MIB),
            'rm-rule_5_b': rule(5, 'b', 6L, MIB),
            'rm-rule_6_a': rule(6, 'a', 5L, 3 * MIB),
            'rm-rule_6_b': rule(6, 'b', 7L, MIB),
            app_1: app('1', 2L, 3 * MIB)
        ]
        List deleted = deletedFiles()

        when:
        script._publishItemBackup('app_9', app('9', 10L, MIB))

        then: 'the oldest backup goes and the total is back under the budget'
        backupIndex.keySet() == ['rm-rule_5_b', 'rm-rule_6_a', 'rm-rule_6_b', 'app_1', 'app_9'] as Set
        deleted == ['mcp-rm-backup-5-a.json']
    }

    def 'eviction skips the published entry, the restore target and each item newest backup'() {
        given:
        backupIndex = [
            'rm-rule_5_a': rule(5, 'a', 0L, MIB),
            'rm-rule_5_b': rule(5, 'b', 1L, 4 * MIB),
            'rm-rule_5_c': rule(5, 'c', 3L, MIB),
            app_1: app('1', 2L, 4 * MIB)
        ]
        List deleted = deletedFiles()

        when: 'rm-rule_5_a is the active restore target'
        script._publishItemBackup('app_9', app('9', 10L, 3 * MIB), 'rm-rule_5_a')

        then:
        backupIndex.keySet() == ['rm-rule_5_a', 'rm-rule_5_c', 'app_1', 'app_9'] as Set
        deleted == ['mcp-rm-backup-5-b.json']
    }

    def 'the newest backup of an item survives eviction while older backups of other items go'() {
        given: 'the only backup of app 1 is the oldest in the index'
        backupIndex = [
            app_1: app('1', 0L, 4 * MIB),
            'rm-rule_6_a': rule(6, 'a', 1L, 3 * MIB),
            'rm-rule_6_b': rule(6, 'b', 2L, 2 * MIB)
        ]
        List deleted = deletedFiles()

        when:
        script._publishItemBackup('rm-rule_6_c', rule(6, 'c', 3L, 2 * MIB))

        then:
        backupIndex.keySet() == ['app_1', 'rm-rule_6_b', 'rm-rule_6_c'] as Set
        deleted == ['mcp-rm-backup-6-a.json']
    }

    def 'a full index of one item backups evicts all but its newest'() {
        given:
        settingsMap.backupBudgetMb = 1
        backupIndex = (1..4).collectEntries { [("rm-rule_5_s${it}".toString()): rule(5, "s${it}", (long) it, 600 * 1024L)] }
        List deleted = deletedFiles()

        when:
        script._publishItemBackup('rm-rule_5_s5', rule(5, 's5', 5L, 600 * 1024L))

        then:
        backupIndex.keySet() == ['rm-rule_5_s5'] as Set
        deleted as Set == (1..4).collect { "mcp-rm-backup-5-s${it}.json".toString() } as Set
    }

    def 'a pre-restore undo counts as a backup of its item'() {
        given: 'the undo is newer than the item baseline it was taken beside'
        backupIndex = [
            app_1: app('1', 1L, 6 * MIB),
            prerestore_app_1: app('1', 2L, 3 * MIB, [fileName: 'mcp-prerestore-app-1.groovy', undoForBackupKey: 'app_1'])
        ]
        List deleted = deletedFiles()

        when:
        script._publishItemBackup('app_9', app('9', 3L, 2 * MIB))

        then:
        backupIndex.keySet() == ['prerestore_app_1', 'app_9'] as Set
        deleted == ['mcp-backup-app-1.groovy']
    }

    def 'a single backup larger than the budget is kept and every evictable backup goes'() {
        given:
        backupIndex = ['rm-rule_5_a': rule(5, 'a', 1L, MIB), 'rm-rule_5_b': rule(5, 'b', 2L, MIB)]
        deletedFiles()

        when:
        script._publishItemBackup('rm-rule_6_a', rule(6, 'a', 10L, 11 * MIB))

        then:
        backupIndex.keySet() == ['rm-rule_5_b', 'rm-rule_6_a'] as Set
    }

    def 'pending-deletion entries do not count toward the reported budget usage'() {
        expect:
        script._itemBackupBudgetUsage([a: [sizeBytes: 10], b: [sizeBytes: 20, deletePending: true]]).usedBytes == 10L
    }

    def 'reuse does not republish an index that is within its budget'() {
        given:
        backupIndex = [app_1: app('1', 5L, 10L)]
        Map view = script._itemBackupManifest()
        int writes = 0
        def storeRef = backupIndexStore
        script.metaClass._writeItemBackupIndexStore = { String json -> writes++; storeRef.json = json }

        when:
        script._repairItemBackupRetention(view, 'app_1', view.app_1)

        then:
        writes == 0
    }

    def 'a code backup records its size in bytes, not characters'() {
        given:
        hubGet.register('/app/ajax/code') { params -> JsonOutput.toJson([source: 'déjà vu', version: 2]) }
        script.metaClass.uploadHubFile = { String name, byte[] bytes -> }

        when:
        def entry = script.backupItemSource('app', '41')

        then:
        entry.sourceLength == 7
        entry.sizeBytes == 'déjà vu'.getBytes('UTF-8').length
        entry.sizeBytes == 9
    }

    // ---------- File Manager listing shapes ----------

    def 'the File Manager listing is read from every shape hub_list_files accepts'() {
        given:
        hubGet.register('/hub/fileManager/json') { params -> JsonOutput.toJson(body) }

        expect:
        script._fileManagerRootFiles() == expected

        where:
        body                                                                  | expected
        [[name: 'a.txt', size: '3', date: '4']]                               | [[name: 'a.txt', size: 3L, date: 4L]]
        ['a.txt', 'b.txt']                                                    | [[name: 'a.txt', size: null, date: null], [name: 'b.txt', size: null, date: null]]
        [files: [[name: 'a.txt', size: 'big', date: 'today'], [name: 'd', type: 'dir']]] | [[name: 'a.txt', size: null, date: null]]
        [rootFiles: [[name: 'a.txt', size: 5]]]                               | [[name: 'a.txt', size: 5L, date: null]]
    }

    def 'adopted files with no listed date are the oldest, so the budget evicts them first'() {
        given:
        settingsMap.backupBudgetMb = 1
        backupIndex = null
        String uuid = '0f8fad5b-d9cb-469f-a165-70867728950e'
        listFiles([[name: 'mcp-backup-app-1.groovy', type: 'file', size: String.valueOf(600 * 1024)],
                   [name: "mcp-backup-app-1-${uuid}.groovy".toString(), type: 'file', size: String.valueOf(300 * 1024), date: '5000']])
        List deleted = deletedFiles()

        when:
        script._publishItemBackup('app_9', app('9', 10000L, 300 * 1024L))

        then: 'the undated copy goes; the dated one is the item newest backup'
        deleted == ['mcp-backup-app-1.groovy']
        backupIndex.keySet() == ['app_1', 'app_9'] as Set
    }

    def 'entries without sizeBytes count their sourceLength against the budget'() {
        expect:
        script._itemBackupEntryBytes([sourceLength: 1234]) == 1234L
        script._itemBackupEntryBytes([sizeBytes: 10, sourceLength: 1234]) == 10L
        script._itemBackupEntryBytes([:]) == 0L
    }

    def 'under the budget no backup is evicted, however many snapshots or items there are'() {
        given: 'forty snapshots of one rule, plus another rule and an app'
        Map seeded = (1..40).collectEntries { [("rm-rule_5_s${it}".toString()): rule(5, "s${it}", (long) it, 10L)] }
        seeded['rm-rule_6_s1'] = rule(6, 's1', 0L, 10L)
        seeded['app_1'] = app('1', 0L, 10L)
        backupIndex = seeded
        List deleted = deletedFiles()

        when:
        script._publishItemBackup('rm-rule_5_s41', rule(5, 's41', 41L, 10L))

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
        5000    | 5000
        1000000 | 1000000
        1000001 | 1000000         // above the 1 TB ceiling
        99999999999999 | 1000000  // would overflow the byte count unclamped
        '12.9'  | 12    // fractions truncate
    }

    def 'an unreadable budget setting is remembered so it warns once'() {
        given:
        settingsMap.backupBudgetMb = 'lots'

        when:
        3.times { script._itemBackupByteBudget() }

        then:
        scriptStaticField('ITEM_BACKUP_BUDGET_WARNED') as Set == ['lots'] as Set
    }

    private Map budgetIndex() {
        [
            app_1_1: app('1', 1L, 300 * 1024L, [fileName: 'mcp-backup-app-1-old.groovy']),
            'rm-rule_5_a': rule(5, 'a', 2L, 600 * 1024L),
            app_1: app('1', 4L, 100 * 1024L),
            'rm-rule_5_b': rule(5, 'b', 5L, 300 * 1024L)
        ]
    }

    def 'a 1 MB budget evicts what the default budget keeps'() {
        given:
        settingsMap.backupBudgetMb = 1
        backupIndex = budgetIndex()
        List deleted = deletedFiles()

        when: 'the new backup takes the total to 1.5 MB'
        script._publishItemBackup('app_9', app('9', 10L, 200 * 1024L))

        then: 'the two oldest backups go; each item keeps its newest'
        deleted as Set == ['mcp-backup-app-1-old.groovy', 'mcp-rm-backup-5-a.json'] as Set
        backupIndex.keySet() == ['app_1', 'rm-rule_5_b', 'app_9'] as Set

        when:
        def listing = script.toolListItemBackups([:])

        then: 'the listing and the notes report the configured budget'
        listing.storageBudget == [usedBytes: 600 * 1024L, limitBytes: MIB]
        listing.retentionPolicy.contains('1 MB storage budget')
        script._itemBackupRetentionNote().contains('the 1 MB storage budget')
    }

    def 'the same index stays untouched under the default budget'() {
        given:
        backupIndex = budgetIndex()
        List deleted = deletedFiles()

        when:
        script._publishItemBackup('app_9', app('9', 10L, 200 * 1024L))

        then:
        deleted.isEmpty()
        backupIndex.size() == 5
    }

    // ---------- what callers are told ----------

    def 'hub_list_backups reports the budget and the policy, and no retention kind per entry'() {
        given: 'one entry still carries the retention key an older version wrote'
        backupIndex = [
            app_1: app('1', 1L, 2 * MIB, [retention: 'pinned']),
            'rm-rule_5_a': rule(5, 'a', 2L, MIB),
            app_legacy: [type: 'app', id: 'legacy', fileName: 'mcp-backup-app-legacy.groovy', timestamp: 3L, sourceLength: 500]
        ]

        when:
        def result = script.toolListItemBackups([:])

        then:
        result.storageBudget == [usedBytes: 3 * MIB + 500, limitBytes: 10 * MIB]
        result.retentionPolicy == script._itemBackupRetentionNote()
        result.retentionPolicy.contains('10 MB')
        !result.containsKey('maxBackups')
        result.backups*.backupKey as Set == ['app_1', 'rm-rule_5_a', 'app_legacy'] as Set
        result.backups.every { !it.containsKey('retention') }
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

    def 'the served guide states the file-copy cap the code keeps'() {
        expect: 'the guide is a fixed literal, so this pins it to FILE_BACKUP_COPIES'
        script.getToolGuideSections().file_manager.contains("the newest ${scriptStaticField('FILE_BACKUP_COPIES')} copies of each file name are kept")
    }

    def 'the retention note states the one eviction rule'() {
        expect:
        script._itemBackupRetentionNote() ==
            "Backups are removed only when all MCP backups together exceed the 10 MB storage budget, oldest first; an item's newest backup is never removed."
    }
}
