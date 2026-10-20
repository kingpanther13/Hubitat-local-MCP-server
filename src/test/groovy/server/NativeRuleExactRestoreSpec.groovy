package server

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Shared
import support.TestChildApp
import support.ToolSpecBase

class NativeRuleExactRestoreSpec extends ToolSpecBase {
    @Shared TestChildApp serverApp = new TestChildApp(id: 7L, label: 'MCP')
    RestoreNativeChild child
    Map snapshot
    List events
    boolean rebuildDrifts
    boolean undoFails

    void setupSpec() { appExecutor.getApp() >> serverApp }

    void setup() {
        events = []
        rebuildDrifts = false
        undoFails = false
        child = new RestoreNativeChild(events)
        Map original = [actions: ['1': [method: 'getLogMsg', detail: 'original'], '2': [method: 'getWaitEvents', wait: ['1']]],
            actionList: ['2', '1'], capabstrue: ['3': 'Original trigger'], capabsfalse: ['4': 'Original condition'],
            eval: ['0': ['4'], '8': ['4']], actNdx: 3, ndx: 5, ruleNdx: 9, stopped: true, paused: false,
            lv_counter: [type: 'integer', value: 7], allLocalVars: [counter: [type: 'integer', value: 2]],
            arbitraryNativeConfiguration: [nested: ['preserve', 12]], runtimeCursor: 'old runtime']
        Map payload = [appReplacements: ['42': [appTypeNamespace: 'hubitat', appTypeName: 'Rule-5.1', parentAppInstalledAppId: '28', appLabel: 'Original']],
            deviceReplacements: [:], appData: ['42': [state: original,
                appSettings: [[name: 'count', type: 'number', value: '7'], [name: 'enabled', type: 'bool', value: false]], subscriptions: []]]]
        snapshot = [schemaVersion: 1, captureFormat: 'native-rule-edit-v1', ruleId: 42, nativeExport: JsonOutput.toJson(payload)]
        child.backing = [actions: ['1': [method: 'different', detail: 'edited'], '9': [method: 'extra']], actionList: ['9', '1'],
            capabstrue: ['9': 'extra'], capabsfalse: [:], eval: [:], stopped: false, paused: true,
            lv_counter: [type: 'integer', value: 99], lv_added: [type: 'string', value: 'extra'], onlyAfterBackup: true]
        child.typed = [count: [type: 'text', value: 'edited', multiple: false], extra: [type: 'text', value: 'remove', multiple: false]]
        script.metaClass.getAppByAppId = { Long id -> [id: id, parentAppId: 28L, appType: [namespace: 'hubitat', name: 'Rule-5.1']] }
        script.metaClass._nrHandle = { Long id -> child }
        script.metaClass._nmSettingsInventory = { Long id -> [:] + child.typed }
        script.metaClass._rmBackupRuleSnapshot = { Integer id, String reason, boolean strict, String protectedKey ->
            events << ['undo', strict, protectedKey]
            if (undoFails) throw new IllegalStateException('undo unavailable')
            [backupKey: 'rm-rule_42_undo']
        }
        script.metaClass._ndApply = { Long id, List changes, boolean created ->
            events << 'rebuild'
            assert changes.empty && !created
            child.backing.runtimeCursor = 'native rebuilt'
            if (rebuildDrifts) child.backing.actions['1'].method = 'wrong after rebuild'
            [success: true, broken: false, stopped: child.backing.stopped, paused: child.backing.paused, eventSubscriptions: [], scheduledJobs: []]
        }
    }

    private Map restore() { script._rmRestoreFromBackup([fileName: 'snapshot.json'], snapshot, true, 'rm-rule_42_source') }

    def 'same ID restore recovers deleted records full payloads expressions order settings locals and unknown native configuration'() {
        when:
        def result = restore()
        then:
        result.success
        result.ruleId == 42L
        result.restoredVia == 'nativeStateReplay'
        result.backup.backupKey == 'rm-rule_42_undo'
        child.backing.actions == ['1': [method: 'getLogMsg', detail: 'original'], '2': [method: 'getWaitEvents', wait: ['1']]]
        child.backing.actionList == ['2', '1']
        child.backing.capabstrue == ['3': 'Original trigger']
        child.backing.capabsfalse == ['4': 'Original condition']
        child.backing.eval == ['0': ['4'], '8': ['4']]
        child.backing.arbitraryNativeConfiguration == [nested: ['preserve', 12]]
        !child.backing.containsKey('onlyAfterBackup')
        !child.backing.containsKey('lv_added')
        child.backing.lv_counter.value == 7
        child.backing.runtimeCursor == 'native rebuilt'
        child.typed.keySet() == ['count', 'enabled'] as Set
        child.typed.count.type == 'number'
        child.typed.enabled.value == false
        child.backing.stopped && !child.backing.paused
        child.label == 'Original'
        events[0] == ['undo', true, 'rm-rule_42_source']
    }

    def 'same IDs with changed compiled payload after rebuild cannot be reported restored'() {
        given:
        rebuildDrifts = true
        when:
        def result = restore()
        then:
        !result.success && result.partial
        result.error.contains('differ from recovery')
        result.backup.backupKey == 'rm-rule_42_undo'
    }

    def 'failed undo capture leaves original live configuration untouched'() {
        given:
        undoFails = true
        def before = JsonOutput.toJson(child.backing)
        when:
        restore()
        then:
        thrown(IllegalStateException)
        JsonOutput.toJson(child.backing) == before
        events == [['undo', true, 'rm-rule_42_source']]
    }

    def 'malformed strict recovery never falls through to legacy settings replay'() {
        given:
        snapshot.nativeExport = '{}'
        script.metaClass._rmFetchConfigJson = { Object... ignored -> throw new AssertionError('unexpected legacy editor access') }
        when:
        restore()
        then:
        thrown(IllegalStateException)
        events.empty
    }

    def 'wrong native parent fails before undo capture or writes'() {
        given:
        script.metaClass.getAppByAppId = { Long id -> [id: id, parentAppId: 99L, appType: [namespace: 'hubitat', name: 'Rule-5.1']] }
        when:
        restore()
        then:
        thrown(IllegalArgumentException)
        events.empty
    }

    def 'native copy verification also rejects same ID rows with altered compiled payloads'() {
        given:
        child.backing = new JsonSlurper().parseText(snapshot.nativeExport).appData['42'].state as Map
        child.backing.actions['1'].method = 'different'
        script.metaClass._rmReadRuleState = { Integer id -> child.backing }
        expect:
        script._rmNativeCopyMismatch(42, snapshot).contains('configuration differs')
    }
}

class RestoreNativeChild {
    Map backing
    Map typed
    Map pending
    String label = 'Edited'
    List events

    RestoreNativeChild(List events) { this.events = events }
    Map getState() { backing }
    void setState(Map value) { pending = value }
    void saveState() { backing = new JsonSlurper().parseText(JsonOutput.toJson(pending)) as Map }
    void removeSetting(String name) { typed.remove(name); events << ['removeSetting', name] }
    void updateSetting(String name, Map value) { typed[name] = [type: value.type, value: value.value, multiple: value.value instanceof List]; events << ['updateSetting', name] }
    void updateLabel(String value) { label = value }
    void appButtonHandler(String name) {
        events << name
        if (name == 'stopRule') backing.stopped = backing.stopped != true
        if (name == 'pausRule') backing.paused = backing.paused != true
        if (name == 'updateRule') { backing.stopped = false; backing.runtimeCursor = 'reset by native update' }
    }
    Map getAllLocalVars() { backing.findAll { key, value -> key.startsWith('lv_') }.collectEntries { key, value -> [(key.substring(3)): value] } }
}
