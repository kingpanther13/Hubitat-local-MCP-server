package server

import groovy.json.JsonOutput
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import support.TestChildApp
import support.ToolSpecBase

class RetainedStateCloseoutSpec extends ToolSpecBase {
    private static class FailingManifest extends LinkedHashMap {
        boolean fail
        Object put(Object key, Object value) {
            if (fail && key == 'itemBackupManifest') throw new IllegalStateException('manifest unavailable')
            super.put(key, value)
        }
    }

    private Map entry(String id, long timestamp = 1L) {
        [type: 'app', id: id, fileName: "mcp-backup-app-${id}.groovy".toString(),
         timestamp: timestamp, version: 1, sourceLength: 3]
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
        def backing = new FailingManifest()
        backing.put('itemBackupManifest', [app_99: entry('99')])
        backing.@fail = true
        def peer = newCompiledScriptInstance([app: new TestChildApp(id: 1L), state: stateMap, atomicState: backing])
        Map files = ['mcp-backup-app-99.groovy': 'old']
        peer.metaClass.hubInternalGet = { String path, Map params = null -> '{"source":"new source","version":2}' }
        peer.metaClass.uploadHubFile = { String name, byte[] bytes -> files[name] = new String(bytes, 'UTF-8') }
        peer.metaClass.deleteHubFile = { String name -> files.remove(name) }

        when:
        peer.backupItemSource('app', '99')

        then:
        thrown(IllegalStateException)
        files['mcp-backup-app-99.groovy'] == 'old'
        backing.itemBackupManifest.app_99 == entry('99')
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

    def 'compact variable history preserves full values boundary ordering rename and cold reads'() {
        given:
        String fullValue = 'a' * 4096
        String description = 'description ' * 40
        List old = (1..200).collect { [name: 'old', value: fullValue, timestamp: it, descriptionText: description] }
        atomicStateMap.variableHistory = old

        when:
        script.handleHubVariableEvent([name: 'variable:old', value: fullValue, descriptionText: description])
        script.renameVariable('old', 'new')
        def peer = newCompiledScriptInstance([app: new TestChildApp(id: 1L), state: stateMap, atomicState: atomicStateMap])
        def result = peer.toolGetVariableHistory([name: 'new', sinceMs: 200, limit: 200])

        then:
        result.bufferSize == 200
        result.entries*.timestamp == [1234567890000L, 200]
        result.entries.every { it.value == fullValue && it.descriptionText == description && it.name == 'new' }
        atomicStateMap.variableHistory.collect { it instanceof List }.unique() == [true]
        JsonOutput.toJson(atomicStateMap.variableHistory).length() < JsonOutput.toJson(old).length() - 8000
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
            secondStarted.countDown()
            script.backupItemSource('app', '99')
        } as java.util.concurrent.Callable)
        assert secondStarted.await(10, TimeUnit.SECONDS)
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

    def 'manifest unlink failure never deletes the rollback file'() {
        given:
        def backing = new FailingManifest()
        backing.put('itemBackupManifest', [app_99: entry('99')])
        backing.@fail = true
        def peer = newCompiledScriptInstance([app: new TestChildApp(id: 1L), state: stateMap, atomicState: backing])
        List deleted = []
        peer.metaClass.deleteHubFile = { String name -> deleted << name }

        when:
        peer._deleteItemBackupFile('mcp-backup-app-99.groovy')

        then:
        thrown(IllegalStateException)
        deleted.isEmpty()
        backing.itemBackupManifest.app_99 == entry('99')
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
        response << ['{}', '{"apps":[],"error":"partial"}', '{"apps":[{"data":{}}]}',
                     '{"apps":[{"data":{"id":11},"children":"unreadable"}]}']
    }
}
