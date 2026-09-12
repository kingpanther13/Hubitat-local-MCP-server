package server

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import support.TestChildApp
import support.ToolSpecBase

class RetainedStateCloseoutSpec extends ToolSpecBase {
    private static class FailingManifest extends LinkedHashMap {
        boolean fail
        String failedKey = "itemBackupManifest"
        int failAt
        int writes
        Object put(Object key, Object value) {
            if (key == failedKey && (++writes == failAt || fail)) throw new IllegalStateException('manifest unavailable')
            super.put(key, value)
        }
    }

    private void enableWrite() {
        settingsMap.enableWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L
    }

    private Map entry(String id, long timestamp = 1L) {
        [type: 'app', id: id, fileName: "mcp-backup-app-${id}.groovy".toString(),
         timestamp: timestamp, version: 1, sourceLength: 3]
    }

    private FailingManifest failingManifest(Map seed = [app_99: entry('99')]) {
        def backing = new FailingManifest()
        backing.put('itemBackupManifest', seed)
        backing.@writes = 0 // The seeding put counts; failAt must start from the first real write.
        return backing
    }

    private Object peerFor(FailingManifest backing) {
        newCompiledScriptInstance([app: new TestChildApp(id: 1L), state: stateMap, atomicState: backing])
    }

    def 'source backup repairs oversized retention and unlinks before deleting old files'() {
        given:
        atomicStateMap.itemBackupManifest = (1..23).collectEntries { [("app_${it}".toString()): entry("${it}", it)] }
        hubGet.register('/app/ajax/code') { params -> '{"source":"new source","version":2}' }
        List deleted = []
        script.metaClass.uploadHubFile = { String name, byte[] bytes -> }
        script.metaClass.deleteHubFile = { String name ->
            assert !atomicStateMap.itemBackupManifest.values().any { it.fileName == name }
            deleted << name
        }

        when:
        script.backupItemSource('app', '99')

        then:
        atomicStateMap.itemBackupManifest.size() == 20
        atomicStateMap.itemBackupManifest.containsKey('app_99')
        deleted == (1..4).collect { "mcp-backup-app-${it}.groovy" }
    }

    def 'manifest publication failure preserves the old rollback file and entry'() {
        given:
        def backing = failingManifest()
        backing.@fail = true
        def peer = peerFor(backing)
        Map files = ['mcp-backup-app-99.groovy': 'old']
        peer.metaClass.hubInternalGet = { String path, Map params = null -> '{"source":"new source","version":2}' }
        peer.metaClass.uploadHubFile = { String name, byte[] bytes -> files[name] = new String(bytes, 'UTF-8') }
        peer.metaClass.deleteHubFile = { String name -> files.remove(name) }

        when:
        peer.backupItemSource('app', '99')

        then:
        thrown(IllegalStateException)
        files == ['mcp-backup-app-99.groovy': 'old']
        backing.itemBackupManifest.app_99 == entry('99')
    }

    def 'publication failure surfaces even when reclaiming the uploaded file also fails'() {
        given:
        def backing = failingManifest()
        backing.@fail = true
        def peer = peerFor(backing)
        peer.metaClass.hubInternalGet = { String path, Map params = null -> '{"source":"new source","version":2}' }
        peer.metaClass.uploadHubFile = { String name, byte[] bytes -> }
        peer.metaClass.deleteHubFile = { String name -> throw new IllegalStateException('file manager busy') }

        when:
        peer.backupItemSource('app', '99')

        then:
        def error = thrown(IllegalStateException)
        error.message == 'manifest unavailable'
        backing.itemBackupManifest.app_99 == entry('99')
    }

    def 'retention repair failure on baseline reuse keeps the reused backup'() {
        given:
        def backing = failingManifest((1..23).collectEntries { [("app_${it}".toString()): entry("${it}", 1234567890000L)] })
        backing.@fail = true
        def peer = peerFor(backing)
        List uploads = []
        peer.metaClass.uploadHubFile = { String name, byte[] bytes -> uploads << name }

        when:
        def result = peer.backupItemSource('app', '5')

        then:
        result == entry('5', 1234567890000L)
        uploads.isEmpty()
        backing.itemBackupManifest.size() == 23
    }

    def 'next publication purges pending-deletion markers and removes their files best-effort'() {
        given:
        atomicStateMap.itemBackupManifest = [
            app_1: entry('1') + [deletePending: true],
            'rm-rule_5_x': [type: 'rm-rule', id: 5, ruleId: 5, fileName: 'mcp-rm-backup-5-x.json', timestamp: 2L, deletePending: true],
            app_3: entry('3', 3L)]
        List deleted = []
        script.metaClass.deleteHubFile = { String name -> deleted << name; if (name.endsWith('.json')) throw new IllegalStateException('already gone') }

        when:
        script._publishItemBackup('app_4', entry('4', 4L))

        then:
        atomicStateMap.itemBackupManifest.keySet() == ['app_3', 'app_4'] as Set
        deleted as Set == ['mcp-backup-app-1.groovy', 'mcp-rm-backup-5-x.json'] as Set
    }

    def 'later backup publication includes earlier writes despite a stale execution snapshot'() {
        given:
        hubGet.register('/app/ajax/code') { params -> '{"source":"source","version":2}' }
        script.metaClass.uploadHubFile = { String name, byte[] bytes -> }

        when:
        script.backupItemSource('app', '1')
        atomicStateMap.itemBackupManifest = [:]
        script.backupItemSource('app', '2')

        then:
        atomicStateMap.itemBackupManifest.keySet() == ['app_1', 'app_2'] as Set
    }

    def 'complete app inventory reconciles deleted recovery flags but preserves hidden apps and new flags'() {
        given:
        atomicStateMap.predClearPending = ['10': true, '11': true, '12': true]
        hubGet.register('/hub2/appsList') { params ->
            script._rmMarkPredClearPending(12)
            '{"apps":[{"data":{"id":10,"hidden":true},"children":[]}]}'
        }

        when:
        script.toolListInstalledApps([:])

        then:
        atomicStateMap.predClearPending.keySet() == ['10', '12'] as Set
    }

    def 'overlapping backups of one item serialize upload and reuse the same baseline'() {
        given:
        def entered = new CountDownLatch(1)
        def release = new CountDownLatch(1)
        def secondStarted = new CountDownLatch(1)
        def secondThread = new java.util.concurrent.atomic.AtomicReference<Thread>()
        def workers = Executors.newFixedThreadPool(2)
        List files = Collections.synchronizedList([])
        hubGet.register('/app/ajax/code') { params -> '{"source":"original","version":2}' }
        script.metaClass.uploadHubFile = { String name, byte[] bytes ->
            files << name
            entered.countDown()
            assert release.await(10, TimeUnit.SECONDS)
        }

        when:
        def first = workers.submit({ -> script.backupItemSource('app', '99') } as java.util.concurrent.Callable)
        assert entered.await(10, TimeUnit.SECONDS)
        def second = workers.submit({ ->
            secondThread.set(Thread.currentThread())
            secondStarted.countDown()
            script.backupItemSource('app', '99')
        } as java.util.concurrent.Callable)
        assert secondStarted.await(10, TimeUnit.SECONDS)
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (secondThread.get().state != Thread.State.BLOCKED && !second.isDone() && System.nanoTime() < deadline) {
            Thread.yield()
        }
        if (second.isDone()) second.get() // Surface the worker's own exception instead of a thread-state mismatch.
        assert secondThread.get().state == Thread.State.BLOCKED
        assert files.size() == 1
        release.countDown()
        def firstResult = first.get(10, TimeUnit.SECONDS)
        def secondResult = second.get(10, TimeUnit.SECONDS)

        then:
        firstResult == secondResult
        files.size() == 1

        cleanup:
        release.countDown()
        workers.shutdownNow()
    }

    def 'file deletion failure restores its manifest entry and reusable view'() {
        given:
        enableWrite()
        atomicStateMap.itemBackupManifest = [app_99: entry('99')]
        script.metaClass.deleteHubFile = { String name -> throw new IllegalStateException('delete unavailable') }

        when:
        def result = script.toolDeleteFile([fileName: 'mcp-backup-app-99.groovy', confirm: true])

        then:
        result.success == false
        atomicStateMap.itemBackupManifest.app_99 == entry('99')
        script._itemBackupManifest().app_99 == entry('99')
    }

    def 'successful deletion reports partial completion when manifest unlink fails'() {
        given:
        enableWrite()
        def backing = failingManifest()
        backing.@failAt = 2 // Pending marker succeeds; unlink fails.
        def peer = peerFor(backing)
        List deleted = []
        peer.metaClass.deleteHubFile = { String name -> deleted << name }

        when:
        def result = peer.toolDeleteFile([fileName: 'mcp-backup-app-99.groovy', confirm: true])

        then:
        result.success && result.partial && result.fileDeleted
        result.manifestCleanupPending
        result.pendingBackupKeys == ['app_99']
        result.manifestCleanupError == 'manifest unavailable'
        result.note.contains('do not repeat the deletion')
        result.warning.contains('Do not retry the deletion')
        result.message.contains('did not complete')
        deleted == ['mcp-backup-app-99.groovy']
        backing.@writes == 2 // The pending marker and the failed unlink; a new commit on this path must retarget failAt.
        backing.itemBackupManifest.app_99.deletePending
        peer._itemBackupManifest().app_99.deletePending
    }

    def 'pending-deletion publication failure never deletes the rollback file'() {
        given:
        def backing = failingManifest()
        backing.@fail = true
        def peer = peerFor(backing)
        List deleted = []
        peer.metaClass.deleteHubFile = { String name -> deleted << name }

        when:
        peer._deleteHubFileAndUnlinkBackups('mcp-backup-app-99.groovy')

        then:
        thrown(IllegalStateException)
        deleted.isEmpty()
        backing.itemBackupManifest.app_99 == entry('99')
    }

    def 'failed writes keep newer committed backup and predicate views over stale snapshots'() {
        given:
        def backing = new FailingManifest()
        def peer = peerFor(backing)
        peer._publishItemBackup('app_1', entry('1'))
        backing.put('itemBackupManifest', [:])
        backing.@fail = true

        when:
        peer._publishItemBackup('app_2', entry('2'))

        then:
        thrown(IllegalStateException)
        peer._itemBackupManifest().keySet() == ['app_1'] as Set

        when:
        backing.@fail = false
        peer._publishItemBackup('app_3', entry('3'))
        peer._rmMarkPredClearPending(10)
        peer._rmMarkPredClearPending(11)
        backing.put('predClearPending', [:])
        backing.@failedKey = 'predClearPending'
        backing.@fail = true
        peer.metaClass.hubInternalGet = { String path, Map params = null ->
            '{"apps":[{"data":{"id":1},"children":[{"data":{"id":10},"children":[]}]}]}'
        }
        def listing = peer.toolListInstalledApps([:])

        then: 'a failed reconciliation commit keeps every record and the listing still succeeds'
        listing.success != false && listing.error == null
        backing.itemBackupManifest.keySet() == ['app_1', 'app_3'] as Set
        peer._rmPendingPredClearSnapshot().keySet() == ['10', '11'] as Set

        when:
        backing.@fail = false
        peer._rmMarkPredClearPending(11)

        then:
        backing.predClearPending.keySet() == ['10', '11'] as Set
    }

    def 'double deletion failure retains durable pending metadata and both errors'() {
        given:
        def backing = failingManifest()
        backing.@failAt = 2
        def peer = peerFor(backing)
        peer.metaClass.deleteHubFile = { String name -> throw new IllegalStateException('file busy') }

        when:
        peer._deleteHubFileAndUnlinkBackups('mcp-backup-app-99.groovy')

        then:
        def error = thrown(IllegalStateException)
        error.message.contains('file busy') && error.message.contains('manifest unavailable')
        backing.itemBackupManifest.app_99.deletePending == true
        backing.itemBackupManifest.app_99.fileName == 'mcp-backup-app-99.groovy'
        peer._itemBackupManifest().app_99.deletePending == true
    }

    def 'pre-restore backup failure stops the restore write'() {
        given:
        enableWrite()
        def backing = failingManifest()
        backing.@fail = failure == 'publication'
        def peer = peerFor(backing)
        peer.metaClass.downloadHubFile = { String name -> 'original'.getBytes('UTF-8') }
        peer.metaClass.hubInternalGet = { String path, Map params = null ->
            failure == 'read' ? null : failure == 'missing-source' ? '{"version":2}' : '{"source":"current","version":2}'
        }
        Map files = [:]
        peer.metaClass.uploadHubFile = { String name, byte[] bytes ->
            if (failure == 'upload') throw new IllegalStateException('upload unavailable')
            files[name] = bytes
        }
        peer.metaClass.deleteHubFile = { String name -> files.remove(name) }
        List writes = []
        peer.metaClass.hubInternalPostJson = { String path, String body -> writes << path; '{"status":"success"}' }

        when:
        def result = peer.toolRestoreItemBackup([backupKey: 'app_99', confirm: true])

        then:
        result.success == false
        result.error.contains('pre-restore backup')
        result.note.contains('nothing needs undoing')
        writes.isEmpty()
        files.isEmpty() // A pre-restore upload that could not be published is reclaimed, never orphaned.
        backing.itemBackupManifest.app_99 == entry('99')

        where:
        failure << ['read', 'missing-source', 'upload', 'publication']
    }

    def 'pending-deletion backups cannot be fetched or restored'() {
        given:
        enableWrite()
        atomicStateMap.itemBackupManifest = [app_99: entry('99') + [deletePending: true]]
        List reads = []
        script.metaClass.downloadHubFile = { String name -> reads << name; null }
        List writes = []
        script.metaClass.hubInternalPostJson = { String path, String body -> writes << path; '{"status":"success"}' }

        when:
        def fetched = script.toolGetItemBackup([backupKey: 'app_99'])
        def restored = script.toolRestoreItemBackup([backupKey: 'app_99', confirm: true])

        then:
        fetched.error.contains('pending deletion')
        restored.success == false
        restored.error.contains('pending deletion')
        reads == ['mcp-backup-app-99.groovy', 'mcp-backup-app-99.groovy'] // Both paths probe before refusing.
        writes.isEmpty()
    }

    def 'a pending-deletion marker on a file that still exists is cleared and the backup served'() {
        given:
        atomicStateMap.itemBackupManifest = [app_99: entry('99') + [deletePending: true]]
        script.metaClass.downloadHubFile = { String name -> 'still here'.getBytes('UTF-8') }

        when:
        def fetched = script.toolGetItemBackup([backupKey: 'app_99'])

        then:
        fetched.source == 'still here'
        !atomicStateMap.itemBackupManifest.app_99.deletePending
    }

    def 'backup listing surfaces pending deletion markers'() {
        given:
        atomicStateMap.itemBackupManifest = [app_1: entry('1'), app_2: entry('2') + [deletePending: true]]

        when:
        def result = script.toolListItemBackups([:])

        then:
        result.backups.collectEntries { [(it.backupKey): it.deletePending] } == [app_1: false, app_2: true]
        result.deletePendingNote.contains('cannot be restored')
    }

    def 'pending library baseline is not reused by hub_update_library'() {
        given:
        enableWrite()
        atomicStateMap.itemBackupManifest = [library_42: entry('42', 1234567890000L) +
            [type: 'library', fileName: 'mcp-backup-library-42.groovy', deletePending: true]]
        hubGet.register('/library/list/single/data/42') { params ->
            groovy.json.JsonOutput.toJson([[id: 42, version: 9, source: 'library(name:"L")', name: 'L', namespace: 'n']])
        }
        List uploads = []
        script.metaClass.uploadHubFile = { String name, byte[] bytes -> uploads << name }
        script.metaClass.deleteHubFile = { String name -> }
        script.metaClass.hubInternalPostJson = { String path, String body -> [success: true, message: '', id: 42, version: 10] }

        when:
        def result = script.toolUpdateLibraryCode([libraryId: '42', source: 'new source', confirm: true])

        then:
        result.success == true
        uploads.size() == 1
        atomicStateMap.itemBackupManifest.library_42.fileName == uploads[0]
        !atomicStateMap.itemBackupManifest.library_42.deletePending
    }

    def 'pre-restore publication protects the requested oldest backup and new undo point'() {
        given:
        enableWrite()
        atomicStateMap.itemBackupManifest = (1..23).collectEntries {
            String id = it == 1 ? '99' : it.toString()
            [("app_${id}".toString()): entry(id, it)]
        }
        List deleted = []
        script.metaClass.downloadHubFile = { String name -> 'original'.getBytes('UTF-8') }
        hubGet.register('/app/ajax/code') { params -> '{"source":"current","version":2}' }
        script.metaClass.uploadHubFile = { String name, byte[] bytes -> }
        script.metaClass.deleteHubFile = { String name -> deleted << name }
        script.metaClass.hubInternalPostJson = { String path, String body -> '{"status":"success"}' }

        when:
        script.toolRestoreItemBackup([backupKey: 'app_99', confirm: true])

        then:
        atomicStateMap.itemBackupManifest.size() == 20
        atomicStateMap.itemBackupManifest.keySet().containsAll(['app_99', 'prerestore_app_99'])
        !deleted.contains('mcp-backup-app-99.groovy')
    }

    def 'pending recent source backups cannot authorize baseline reuse'() {
        given:
        String key = "${type}_99"
        atomicStateMap.itemBackupManifest = [(key): entry('99', 1234567890000L) + [type: type, fileName: "mcp-backup-${type}-99.groovy".toString(), deletePending: true]]
        hubGet.register('/app/ajax/code') { params -> '{"source":"fresh source","version":2}' }
        hubGet.register('/library/list/single/data/99') { params -> '[{"source":"fresh source","version":2}]' }
        List uploads = []
        script.metaClass.uploadHubFile = { String name, byte[] bytes -> uploads << name }
        script.metaClass.deleteHubFile = { String name -> }

        when:
        def result = type == 'app' ? script.backupItemSource('app', '99') : script.backupLibrarySource('99')

        then:
        uploads.size() == 1
        result.sourceLength == 'fresh source'.length()
        !atomicStateMap.itemBackupManifest[key].deletePending

        where:
        type << ['app', 'library']
    }

    def 'reconciliation walks nested children before treating an id as absent'() {
        given:
        atomicStateMap.predClearPending = ['11': true, '12': true]
        hubGet.register('/hub2/appsList') { params ->
            '{"apps":[{"data":{"id":1},"children":[{"data":{"id":11},"children":[{"data":{"id":111},"children":[]}]}]}]}'
        }

        when:
        script.toolListInstalledApps([:])

        then:
        atomicStateMap.predClearPending.keySet() == ['11'] as Set
    }

    def 'unreadable or incomplete app inventory never discards recovery intent'() {
        given:
        atomicStateMap.predClearPending = ['10': true]
        hubGet.register('/hub2/appsList') { params -> response }

        when:
        script.toolListInstalledApps([:])

        then:
        atomicStateMap.predClearPending == ['10': true]

        where:
        response << ['{}', '{"apps":[]}', '{"apps":[],"error":"partial"}', '{"apps":[],"success":false}',
                     '{"apps":[{"data":{}}]}', '{"apps":[{"data":{"id":11},"children":"unreadable"}]}']
    }
}
