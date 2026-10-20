package server

import groovy.json.JsonOutput
import support.ToolSpecBase

class NativeRuleRecoveryExportSpec extends ToolSpecBase {
    private static Map payload(int id = 42) {
        [appReplacements: [(id.toString()): [appTypeNamespace: 'hubitat', appTypeName: 'Rule-5.1', parentAppInstalledAppId: '28']],
         deviceReplacements: ['7': [value: '7']],
         appData: [(id.toString()): [state: [actions: [:], actionList: [], capabstrue: [:], capabsfalse: [:], deviceIdAlias: ['7': '7']],
             appSettings: [[name: 'useST', type: 'bool', value: 'true']], subscriptions: []]]]
    }

    def 'strict edit snapshots capture complete native exports before any editor render'() {
        given:
        List calls = []
        String exported = JsonOutput.toJson(payload())
        script.metaClass._nrHandle = { Long id -> [exportAppData: { List ids -> calls << ['export', ids]; exported }] }
        script.metaClass._rmBuildNativeEditSnapshot = { Integer id, String reason, String captured ->
            calls << ['snapshot', captured]
            [snapshot: [nativeExport: captured], config: [:]]
        }
        script.metaClass._withBackupLock = { String label, Closure work -> work.call() }
        script.metaClass._rmWriteRuleSnapshotLocked = { Integer id, String reason, Map snapshot, Map config, String protectedKey ->
            calls << ['upload', snapshot.nativeExport]
            [backupKey: 'rm-rule_42_fixture']
        }
        when:
        def result = script._rmBackupRuleSnapshot(42, 'pre-document-update', true)
        then:
        result.backupKey == 'rm-rule_42_fixture'
        calls == [['export', [42L]], ['snapshot', exported], ['upload', exported]]
    }

    def 'native capture rejects unavailable mismatched and incomplete recovery payloads'() {
        given:
        script.metaClass._nrHandle = { Long id -> [exportAppData: { List ids -> exported }] }
        when:
        script._rmCaptureRuleExport(42)
        then:
        thrown(IllegalStateException)
        where:
        exported << [null, '', '{}', JsonOutput.toJson(payload(99)),
                     JsonOutput.toJson([appReplacements: payload().appReplacements, appData: ['42': [state: [:]]]])]
    }

    def 'an empty native rule may export no initialized model collections'() {
        given:
        def source = payload()
        source.appData['42'].state = [:]
        script.metaClass._nrHandle = { Long id -> [exportAppData: { List ids -> JsonOutput.toJson(source) }] }
        expect:
        script._rmCaptureRuleExport(42) == JsonOutput.toJson(source)
    }

    def 'strict snapshot metadata comes from the export without rendering or dropping false zero and aliases'() {
        given:
        def source = payload()
        source.appReplacements['42'].appLabel = 'Recovery fixture'
        source.appData['42'].appSettings = [[name: 'flag', type: 'bool', value: false], [name: 'count', type: 'number', value: 0]]
        String exported = JsonOutput.toJson(source)
        script.metaClass.hubInternalGet = { Object... ignored -> throw new AssertionError('unexpected editor render') }
        when:
        def built = script._rmBuildNativeEditSnapshot(42, 'pre-document-update', exported)
        then:
        built.snapshot.nativeExport == exported
        built.snapshot.configJson.settings == [flag: false, count: 0]
        built.snapshot.appLabel == 'Recovery fixture'
        built.snapshot.statusJson.appState.find { it.name == 'deviceIdAlias' }.value == ['7': '7']
    }

    def 'an invalid native export fails before configuration rendering or uploading'() {
        given:
        script.metaClass._nrHandle = { Long id -> [exportAppData: { List ids -> '{}' }] }
        script.metaClass._rmBuildNativeEditSnapshot = { Integer id, String reason, String captured -> throw new AssertionError('unexpected snapshot construction') }
        script.metaClass._rmWriteRuleSnapshotLocked = { Integer id, String reason, Map snapshot, Map config, String protectedKey -> throw new AssertionError('unexpected upload') }
        when:
        script._rmBackupRuleSnapshot(42, 'pre-document-update', true)
        then:
        thrown(IllegalStateException)
    }

    def 'strict uploads are read back before publication and corrupt uploads are reclaimed'() {
        given:
        List events = []
        byte[] stored
        boolean corrupt = false
        def built = script._rmBuildNativeEditSnapshot(42, 'pre-document-update', JsonOutput.toJson(payload()))
        script.metaClass._itemBackupFileName = { String name -> name }
        script.metaClass.uploadHubFile = { String name, byte[] bytes -> stored = bytes; events << 'upload' }
        script.metaClass.downloadHubFile = { String name ->
            events << 'read'
            byte[] readback = stored.clone()
            if (corrupt) readback[0] = (byte) (readback[0] + 1)
            readback
        }
        script.metaClass.deleteHubFile = { String name -> events << 'delete' }
        script.metaClass._publishUploadedItemBackup = { String key, Map entry, String protectedKey -> events << ['publish', protectedKey] }
        when:
        def result = script._rmWriteRuleSnapshotLocked(42, 'pre-document-update', built.snapshot, built.config, 'source-key')
        then:
        result.backupKey
        events == ['upload', 'read', ['publish', 'source-key']]
        when:
        corrupt = true
        script._rmWriteRuleSnapshotLocked(42, 'pre-document-update', built.snapshot, built.config)
        then:
        thrown(IllegalArgumentException)
        events[-3..-1] == ['upload', 'read', 'delete']
    }

    def 'recovery keeps false and zero while rejecting mismatched list cardinality before edits'() {
        expect:
        script._rmNativeSettingPlan([[name: 'flag', type: 'bool', value: false], [name: 'count', type: 'number', value: 0]])*.value == [false, 0]
        when:
        script._rmNativeSettingPlan([[name: 'choice', type: 'enum', multiple: false, value: ['a']]])
        then:
        thrown(IllegalArgumentException)
    }
}
