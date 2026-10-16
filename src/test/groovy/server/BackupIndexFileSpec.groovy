package server

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import support.ToolSpecBase

/**
 * The backup index's real File Manager calls (issues #506 / #517): every other spec swaps the
 * index file for the harness store, so this one runs the production read/write through
 * downloadHubFile / uploadHubFile.
 */
class BackupIndexFileSpec extends ToolSpecBase {
    @Override
    protected boolean stubBackupIndexStore() { false }

    private Map files = [:]

    def setup() {
        files = [:]
        script.metaClass.uploadHubFile = { String name, byte[] bytes -> files[name] = bytes }
        script.metaClass.downloadHubFile = { String name ->
            if (!files.containsKey(name)) throw new IOException("File not found: ${name}")
            files[name]
        }
        script.metaClass.deleteHubFile = { String name -> files.remove(name) }
    }

    private Map indexFile() {
        new JsonSlurper().parseText(new String(files['mcp-backup-index.json'] as byte[], 'UTF-8')) as Map
    }

    def 'a publication writes the index to mcp-backup-index.json'() {
        given:
        hubGet.register('/hub/fileManager/json') { params -> JsonOutput.toJson([files: []]) }

        when:
        script._publishItemBackup('app_1', [type: 'app', id: '1', fileName: 'mcp-backup-app-1.groovy',
                                            timestamp: 5L, sourceLength: 3, sizeBytes: 3, retention: 'pinned'])

        then:
        indexFile().version == 1
        indexFile().entries.keySet() == ['app_1'] as Set
        indexFile().entries.app_1.fileName == 'mcp-backup-app-1.groovy'
        !atomicStateMap.containsKey('itemBackupManifest')
    }

    def 'a new class load reads the index file back'() {
        given:
        files['mcp-backup-index.json'] = JsonOutput.toJson([version: 1, entries: [
            app_7: [type: 'app', id: '7', fileName: 'mcp-backup-app-7.groovy', timestamp: 9L, retention: 'pinned']]]).getBytes('UTF-8')

        when:
        def view = script._itemBackupManifest()

        then:
        view.keySet() == ['app_7'] as Set
        hubGet.calls.empty
    }

    def 'a missing index file is rebuilt from the listing and saved'() {
        given:
        files['mcp-rm-backup-8-20260102-030405-678.json'] = '{}'.getBytes('UTF-8')
        hubGet.register('/hub/fileManager/json') { params ->
            JsonOutput.toJson([files: files.keySet().collect { [name: it, type: 'file', size: '2', date: '100'] }])
        }

        when:
        def view = script._itemBackupManifest()

        then:
        view.keySet() == ['rm-rule_8_20260102-030405-678'] as Set
        indexFile().entries.keySet() == view.keySet()
    }
}
