package server

import groovy.json.JsonOutput
import support.ToolSpecBase

class BulkStateStorageSpec extends ToolSpecBase {
    Map files = [:]

    def setup() {
        script.metaClass.uploadHubFile = { String name, byte[] bytes -> files[name] = bytes.clone() }
        script.metaClass.downloadHubFile = { String name -> files[name] }
    }

    def "reading legacy captures moves device payload off app state without losing restore data"() {
        given:
        def devices = [[deviceId: '42', attributes: [switch: 'on', level: 75]]]
        atomicStateMap.capturedDeviceStates = [evening: [devices: devices, timestamp: 123L, deviceCount: 1]]

        when:
        def restored = script.getCapturedState('evening')

        then:
        restored == devices
        !atomicStateMap.containsKey('capturedDeviceStates')
        files.values().any { new String(it, 'UTF-8').contains('evening') }
    }

    def "backup listing migrates the manifest without dropping legacy records"() {
        given:
        atomicStateMap.itemBackupManifest = [app_42: [type: 'app', id: '42', fileName: 'backup.groovy', timestamp: 123L]]

        when:
        def result = script.toolListItemBackups([:])

        then:
        result.backups*.backupKey == ['app_42']
        !atomicStateMap.containsKey('itemBackupManifest')
        files.values().any { new String(it, 'UTF-8').contains('backup.groovy') }
    }

    def "failed capture migration retains its durable legacy copy"() {
        given:
        atomicStateMap.capturedDeviceStates = [saved: [devices: [[deviceId: '42']], timestamp: 1L]]
        script.metaClass.uploadHubFile = { String name, byte[] bytes -> throw new IOException('disk full') }

        when:
        script.getCapturedState('saved')

        then:
        thrown(Exception)
        atomicStateMap.capturedDeviceStates.saved.devices == [[deviceId: '42']]
    }
}
