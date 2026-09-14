package server

import spock.lang.Unroll
import support.ToolSpecBase

class LibraryMapDataKeysSpec extends ToolSpecBase {
    @Unroll
    def "unknown backup key #backupKey returns the missing-backup result for #operation"() {
        given:
        atomicStateMap.itemBackupManifest = [app_123: [type: 'app', id: '123']]
        stateMap.lastBackupTimestamp = 1234567890000L
        script.metaClass.downloadHubFile = { String name ->
            throw new AssertionError('Missing backup must not read a file')
        }

        when:
        def result = operation == 'get'
            ? script.toolGetItemBackup([backupKey: backupKey])
            : script.toolRestoreItemBackup([backupKey: backupKey, confirm: true])

        then:
        result.error == "No backup found for key '${backupKey}'"
        result.availableBackups == 'app_123'
        if (operation == 'restore') assert result.success == false
        hubGet.calls.empty
        atomicStateMap.itemBackupManifest == [app_123: [type: 'app', id: '123']]

        where:
        [operation, backupKey] << [['get', 'restore'], ['fields', 'class', 'metaClass', 'properties']].combinations()
    }
}
