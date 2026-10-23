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
    String nativeType
    boolean undoFails

    void setupSpec() { appExecutor.getApp() >> serverApp }

    void setup() {
        script.callerClient = null
        stateMap.lastBackupTimestamp = script.now()
        nativeContexts().clear()
        busyContexts().clear()
        events = []
        rebuildDrifts = false
        undoFails = false
        script.metaClass._itemBackupManifest = { -> [:] }
        nativeType = 'Rule-5.1'
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
        script.metaClass.getAppByAppId = { Long id -> [id: id, parentAppId: 28L, appType: [namespace: 'hubitat', name: nativeType]] }
        script.metaClass._nrHandle = { Long id -> child }
        hubGet.register('/installedapp/settings/42') { q -> JsonOutput.toJson([settings: child.typed.collect { name, row -> [name: name] + row }]) }
        script.metaClass._rmBackupRuleSnapshot = { Integer id, String reason, boolean strict, String protectedKey ->
            events << ['undo', strict, protectedKey]
            if (undoFails) throw new IllegalStateException('undo unavailable')
            [backupKey: 'rm-rule_42_undo']
        }
        child.rebuild = {
            events << 'rebuild'
            child.backing.runtimeCursor = 'native rebuilt'
            if (rebuildDrifts) child.backing.actions['1'].method = 'wrong after rebuild'
        }
        hubGet.register('/app/ruleBuilderJson/42') { q -> JsonOutput.toJson([broken: false, stopped: child.backing.stopped == true, paused: child.backing.paused == true]) }
        hubGet.register('/installedapp/statusJson/42') { q -> JsonOutput.toJson([eventSubscriptions: [], scheduledJobs: []]) }
        script.metaClass.hubInternalPostFormRaw = { String path, String body, int timeout = 420, boolean retry = false -> [status: 200] }

    }

    void cleanup() { script.callerClient = null; nativeContexts().clear(); busyContexts().clear() }

    private Map nativeContexts() {
        def field = script.class.getDeclaredField('NATIVE_FORM_CONTEXTS')
        field.accessible = true
        field.get(null) as Map
    }

    private Map busyContexts() {
        def field = script.class.getDeclaredField('NATIVE_FORM_BUSY')
        field.accessible = true
        field.get(null) as Map
    }

    private void cacheForm() {
        nativeContexts()[script._nfCallerKey()] = [app_id: 42L, expires: script.now() + 300000L,
            page: [name: 'mainPage'], envelope: [:], fields: [:], controls: [:]]
    }

    private Map restore() { script._rmRestoreFromBackup([fileName: 'snapshot.json'], snapshot, true, 'rm-rule_42_source') }

    def 'an edited snapshot file restores its existing rule without registering or changing the source file'() {
        given:
        byte[] saved = JsonOutput.toJson(snapshot).getBytes('UTF-8')
        def reads = []
        script.metaClass.downloadHubFile = { String name -> reads << name; saved }
        script.metaClass._itemBackupManifest = { -> [:] }
        when:
        def result = script.toolRestoreItemBackup([sourceFile: 'edited-rule.json', confirm: true])
        then:
        result.success
        result.ruleId == 42L
        result.sourceFile == 'edited-rule.json'
        result.restoredVia == 'nativeStateReplay'
        reads == ['edited-rule.json']
        new String(saved, 'UTF-8') == JsonOutput.toJson(snapshot)
        events[0] == ['undo', true, null]
        child.backing.lv_counter.value == 7
        child.backing.actionList == ['2', '1']
    }

    def 'snapshot file arguments reject #label before reading a file or mutating a rule'() {
        given:
        def reads = []
        script.metaClass.downloadHubFile = { String name -> reads << name; JsonOutput.toJson(snapshot).getBytes('UTF-8') }
        when:
        script.toolRestoreItemBackup(arguments)
        then:
        thrown(IllegalArgumentException)
        reads.empty
        events.empty
        where:
        label                    | arguments
        'unconfirmed restore'    | [sourceFile: 'edited-rule.json']
        'two sources'            | [sourceFile: 'edited-rule.json', backupKey: 'rm-rule_42_source', confirm: true]
        'path traversal'         | [sourceFile: '../edited-rule.json', confirm: true]
        'URL'                    | [sourceFile: 'https://example.com/rule.json', confirm: true]
        'empty file name'        | [sourceFile: '', confirm: true]
        'nontext file name'      | [sourceFile: [name: 'edited-rule.json'], confirm: true]
        'replacement requested'  | [sourceFile: 'edited-rule.json', preserveRuleId: false, confirm: true]
        'whole hub scope'        | [sourceFile: 'edited-rule.json', scope: 'hub_local', fileName: 'database.lzf', confirm: true]
    }

    def 'snapshot files require the complete native format before undo capture'() {
        given:
        snapshot.remove('captureFormat')
        script.metaClass.downloadHubFile = { String name -> JsonOutput.toJson(snapshot).getBytes('UTF-8') }
        when:
        def result = script.toolRestoreItemBackup([sourceFile: 'legacy-rule.json', confirm: true])
        then:
        !result.success
        result.sourceFile == 'legacy-rule.json'
        result.error.contains('native-rule-edit-v1')
        events.empty
    }

    def 'malformed snapshot file contents cannot reach undo capture or rule mutation'() {
        given:
        script.metaClass.downloadHubFile = { String name -> contents.getBytes('UTF-8') }
        when:
        def result = script.toolRestoreItemBackup([sourceFile: 'invalid-rule.json', confirm: true])
        then:
        !result.success
        result.partial == false
        result.sourceFile == 'invalid-rule.json'
        result.error
        events.empty
        where:
        contents << ['{', '[]', '{"captureFormat":"native-rule-edit-v1"}']
    }

    def 'indexed snapshot files are refused before retention can delete the input during undo publication'() {
        given:
        script.metaClass._itemBackupManifest = { -> ['rm-rule_42_saved': [fileName: 'saved.json', deletePending: pending]] }
        def reads = []
        script.metaClass.downloadHubFile = { String name -> reads << name; JsonOutput.toJson(snapshot).getBytes('UTF-8') }
        when:
        def result = script.toolRestoreItemBackup([sourceFile: 'saved.json', confirm: true])
        then:
        !result.success
        result.partial == false
        result.error.contains("backupKey 'rm-rule_42_saved'")
        reads.empty
        events.empty
        where:
        pending << [true, false]
    }

    def 'an edited snapshot file is not read when the backup index cannot be loaded'() {
        given:
        script.metaClass._itemBackupManifest = { -> throw new IllegalStateException("${'Backup index unavailable'}: listing failed") }
        def reads = []
        script.metaClass.downloadHubFile = { String name -> reads << name; JsonOutput.toJson(snapshot).getBytes('UTF-8') }
        when:
        def result = script.toolRestoreItemBackup([sourceFile: 'edited-rule.json', confirm: true])
        then:
        !result.success
        result.sourceFile == 'edited-rule.json'
        result.error.startsWith('Backup index unavailable')
        result.note.startsWith('Nothing was changed')
        reads.empty
        events.empty
    }

    def 'an edited snapshot file refuses missing devices and never captures undo or mutates'() {
        given:
        Map payload = new JsonSlurper().parseText(snapshot.nativeExport) as Map
        payload.deviceReplacements = ['591': [value: '591']]
        snapshot.nativeExport = JsonOutput.toJson(payload)
        script.metaClass.downloadHubFile = { String name -> JsonOutput.toJson(snapshot).getBytes('UTF-8') }
        hubGet.register('/device/fullJson/591') { q -> JsonOutput.toJson([:]) }
        when:
        def result = script.toolRestoreItemBackup([sourceFile: 'edited-rule.json', confirm: true])
        then:
        !result.success
        result.partial == false
        result.failedStep == 'devicePreflight'
        result.missingDeviceIds == ['591']
        events.empty
    }

    def 'an edited snapshot cannot create a missing target or overwrite a different native app'() {
        given:
        script.metaClass.downloadHubFile = { String name -> JsonOutput.toJson(snapshot).getBytes('UTF-8') }
        script.metaClass.getAppByAppId = { Long id -> target }
        when:
        def result = script.toolRestoreItemBackup([sourceFile: 'edited-rule.json', confirm: true])
        then:
        !result.success
        result.partial == false
        events.empty
        where:
        target << [null, [id: 42L, parentAppId: 99L, appType: [namespace: 'hubitat', name: 'Rule-5.1']],
                   [id: 42L, parentAppId: 28L, appType: [namespace: 'hubitat', name: 'Button Rule-5.1']]]
    }

    def 'an edited snapshot cannot write without its pre-restore undo'() {
        given:
        undoFails = true
        script.metaClass.downloadHubFile = { String name -> JsonOutput.toJson(snapshot).getBytes('UTF-8') }
        when:
        def result = script.toolRestoreItemBackup([sourceFile: 'edited-rule.json', confirm: true])
        then:
        !result.success
        result.partial == false
        result.error.contains('undo unavailable')
        events == [['undo', true, null]]
    }

    def 'a snapshot file retains partial outcome and undo handle if native rebuild changes restored data'() {
        given:
        rebuildDrifts = true
        script.metaClass.downloadHubFile = { String name -> JsonOutput.toJson(snapshot).getBytes('UTF-8') }
        when:
        def result = script.toolRestoreItemBackup([sourceFile: 'edited-rule.json', confirm: true])
        then:
        !result.success
        result.partial == true
        result.backup.backupKey == 'rm-rule_42_undo'
        result.sourceFile == 'edited-rule.json'
    }

    def 'same ID restore recovers deleted records full payloads expressions order settings locals and unknown native configuration'() {
        given:
        nativeType = ruleType
        def source = new JsonSlurper().parseText(snapshot.nativeExport)
        source.appReplacements['42'].appTypeName = ruleType
        snapshot.nativeExport = JsonOutput.toJson(source)
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
        events.contains('selectActionsX') == (ruleType == 'Button Rule-5.1')
        events.contains('mainPage') == (ruleType == 'Rule-5.1')
        where:
        ruleType << ['Rule-5.1', 'Button Rule-5.1']
    }

    def 'a stopped rule restores its name while RM re-derives the Stopped label decoration'() {
        given: 'a snapshot whose label carries the decoration HTML-stripped, as captured from a stopped rule'
        stoppedSnapshot('Original (Stopped)')
        when:
        def result = restore()
        then:
        result.success
        result.restoredVia == 'nativeStateReplay'
        child.backing.stopped
        child.label == "Original <span style='color:red'>(Stopped)</span>"
    }

    def 'a restore whose rebuilt rule carries a different name still fails verification'() {
        given:
        stoppedSnapshot('Original (Stopped)')
        child.relabelTo = 'Renamed'
        when:
        def result = restore()
        then:
        !result.success
        result.error.contains('Native label or lifecycle state differs from recovery')
    }

    private void stoppedSnapshot(String capturedLabel) {
        def source = new JsonSlurper().parseText(snapshot.nativeExport)
        source.appReplacements['42'].appLabel = capturedLabel
        source.appData['42'].appSettings << [name: 'origLabel', type: 'text', value: 'Original']
        snapshot.nativeExport = JsonOutput.toJson(source)
        child.decorates = true
    }

    def 'device references use verified native device identity before undo capture'() {
        given:
        Map payload = new JsonSlurper().parseText(snapshot.nativeExport) as Map
        payload.deviceReplacements = ['591': [value: '591']]
        payload.appData['42'].appSettings << [name: 'rDev_1', type: 'capability.switch', multiple: true, deviceList: ['591': 'Switch']]
        snapshot.nativeExport = JsonOutput.toJson(payload)
        hubGet.register('/device/fullJson/591') { q -> JsonOutput.toJson([device: [id: 591]]) }
        when:
        def result = restore()
        then:
        result.success
    }

    def 'missing or mismatched device identities refuse before undo capture'() {
        given:
        Map payload = new JsonSlurper().parseText(snapshot.nativeExport) as Map
        payload.deviceReplacements = ['591': [value: '591']]
        snapshot.nativeExport = JsonOutput.toJson(payload)
        hubGet.register('/device/fullJson/591') { q -> JsonOutput.toJson(response) }
        when:
        restore()
        then:
        thrown(IllegalArgumentException)
        events.empty
        where:
        response << [[device: [id: 595]]]
    }

    def 'a missing recovery device returns structured refusal before undo capture or mutation'() {
        given:
        Map payload = new JsonSlurper().parseText(snapshot.nativeExport) as Map
        payload.deviceReplacements = ['591': [value: '591']]
        snapshot.nativeExport = JsonOutput.toJson(payload)
        cacheForm()
        String beforeState = JsonOutput.toJson(child.backing)
        String beforeSettings = JsonOutput.toJson(child.typed)
        hubGet.register('/device/fullJson/591') { q ->
            if (response == '404') throw new IllegalStateException('status code: 404 Not Found')
            JsonOutput.toJson([:])
        }
        when:
        def result = restore()
        then:
        !result.success && !result.partial
        result.failedStep == 'devicePreflight'
        result.missingDeviceIds == ['591']
        result.error.contains('Nothing was restored')
        !result.containsKey('settingsSkipped')
        events.empty
        JsonOutput.toJson(child.backing) == beforeState
        JsonOutput.toJson(child.typed) == beforeSettings
        nativeContexts().containsKey(script._nfCallerKey())
        busyContexts().isEmpty()
        where:
        response << ['404', 'empty']
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

    def 'a failed restore reports the rule stopped and how to start it'() {
        given:
        rebuildDrifts = true
        when:
        def result = restore()
        then:
        !result.success
        result.stopped == true
        result.paused == false
        !result.containsKey('lifecycleUnknown')
        result.note.contains("hub_call_rule(ruleId, action:'start')")
    }

    def 'a failed restore whose lifecycle state cannot be read reports it unknown and keeps the original error'() {
        given:
        rebuildDrifts = true
        boolean failed = false
        script.metaClass.mcpLogError = { String component, String message, Exception e = null, String ruleId = null -> failed = true }
        script.metaClass._nrState = { Long id ->
            if (failed) throw new IllegalStateException('state unreadable')
            [:] + child.backing
        }
        when:
        def result = restore()
        then:
        !result.success
        result.stopped == null
        result.paused == null
        result.lifecycleUnknown == true
        result.error.contains('differ from recovery')
        result.note.contains("stopped/paused state could not be read")
    }

    def 'failed undo capture leaves original live configuration untouched'() {
        given:
        undoFails = true
        cacheForm()
        def before = JsonOutput.toJson(child.backing)
        when:
        restore()
        then:
        thrown(IllegalStateException)
        JsonOutput.toJson(child.backing) == before
        events == [['undo', true, 'rm-rule_42_source']]
        nativeContexts().containsKey(script._nfCallerKey())
        busyContexts().isEmpty()
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

    def 'recovery refuses a different supported native rule type before undo or writes'() {
        given:
        nativeType = 'Button Rule-5.1'
        when:
        restore()
        then:
        thrown(IllegalArgumentException)
        events.empty
    }

    def 'wrong native parent fails before undo capture or writes'() {
        given:
        cacheForm()
        script.metaClass.getAppByAppId = { Long id -> [id: id, parentAppId: 99L, appType: [namespace: 'hubitat', name: 'Rule-5.1']] }
        when:
        restore()
        then:
        thrown(IllegalArgumentException)
        events.empty
        nativeContexts().containsKey(script._nfCallerKey())
    }

    def 'native copy verification also rejects same ID rows with altered compiled payloads'() {
        given:
        child.backing = new JsonSlurper().parseText(snapshot.nativeExport).appData['42'].state as Map
        child.backing.actions['1'].method = 'different'
        script.metaClass._rmReadRuleState = { Integer id -> child.backing }
        expect:
        script._rmNativeCopyMismatch(42, snapshot).contains('configuration differs')
    }

    def 'native imported copy verifies stored locals without executing its disabled app'() {
        given:
        Map source = new JsonSlurper().parseText(snapshot.nativeExport).appData['42'].state as Map
        script.metaClass._rmReadRuleState = { Integer id -> source }
        script.metaClass._nrHandle = { Long id -> throw new AssertionError('Imported disabled app must not execute') }
        hubGet.register('/installedapp/settings/42') { q -> JsonOutput.toJson([settings: [
            [name: 'count', type: 'number', value: '7'], [name: 'enabled', type: 'bool', value: false]]]) }
        expect:
        script._rmNativeCopyMismatch(42, snapshot) == null
        when:
        source.lv_counter.value = 999
        then:
        script._rmNativeCopyMismatch(42, snapshot).contains('local variables differ')
    }

    def 'matching rendered local cache cannot hide a missing authoritative imported local'() {
        given:
        Map source = new JsonSlurper().parseText(snapshot.nativeExport).appData['42'].state as Map
        source.allLocalVars.counter.value = 7
        source.remove('lv_counter')
        script.metaClass._rmReadRuleState = { Integer id -> source }
        script.metaClass._nrHandle = { Long id -> throw new AssertionError('Imported disabled app must not execute') }
        hubGet.register('/installedapp/settings/42') { q -> JsonOutput.toJson([settings: [
            [name: 'count', type: 'number', value: '7'], [name: 'enabled', type: 'bool', value: false]]]) }
        expect:
        script._rmNativeCopyMismatch(42, snapshot).contains('local variables differ')
        script._rmNativeLocalSignature([allLocalVars: [counter: [type: 'integer', value: 7]]]).isEmpty()
    }

    def 'restoring invalidates old form descriptors while preserving other rule workflows'() {
        given:
        cacheForm()
        nativeContexts()['other caller'] = [app_id: 99L, expires: script.now() + 300000L]
        when:
        def restored = restore()
        def staleSubmit = script.toolCallAppForm([app_id: 42, confirm: true, steps: [[kind: 'submit', expect_page: 'mainPage']]])
        then:
        restored.success
        !staleSubmit.success && !staleSubmit.partial
        staleSubmit.error.contains('Open the native form first')
        nativeContexts().containsKey('other caller')
        busyContexts().isEmpty()
    }

    def 'restore refuses an active form without dropping its lock or cached descriptors'() {
        given:
        cacheForm()
        busyContexts()[script._nfKey(42L)] = true
        when:
        restore()
        then:
        def failure = thrown(IllegalStateException)
        failure.message.contains('active native form operation')
        events.empty
        nativeContexts().containsKey(script._nfCallerKey())
        busyContexts().containsKey(script._nfKey(42L))
    }

    def 'the restore lock also refuses a form started during undo capture'() {
        given:
        script.metaClass._rmBackupRuleSnapshot = { Integer id, String reason, boolean strict, String protectedKey ->
            boolean refused = false
            try { script.toolCallAppForm([app_id: 42, steps: [[kind: 'open']], confirm: true]) }
            catch (IllegalStateException failure) { refused = failure.message.contains('active form operation') }
            assert refused
            [backupKey: 'rm-rule_42_undo']
        }
        when:
        def result = restore()
        then:
        result.success
        busyContexts().isEmpty()
    }
}

class RestoreNativeChild {
    Map backing
    Map typed
    Map pending
    Closure rebuild
    String label = 'Edited'
    boolean decorates
    String relabelTo
    List events

    RestoreNativeChild(List events) { this.events = events }
    Object getSetting(String name) { typed.get(name)?.value }
    Map selectTriggers() { rebuild.call(); [name: 'selectTriggers', sections: [[body: []]]] }
    Map selectActions(Map params) { events << 'selectActions'; [name: 'selectActions', sections: [[body: []]]] }
    Map selectActions() { events << 'selectActions'; [name: 'selectActions', sections: [[body: []]]] }
    Map selectActionsX() { events << 'selectActionsX'; [name: 'selectActionsX', sections: [[body: []]]] }
    Map mainPage() { events << 'mainPage'; [name: 'mainPage', sections: [[body: []]]] }
    Map getState() { backing }
    void setState(Map value) { pending = value }
    void saveState() { backing = new JsonSlurper().parseText(JsonOutput.toJson(pending)) as Map }
    void removeSetting(String name) { typed.remove(name); events << ['removeSetting', name] }
    void updateSetting(String name, Map value) { typed[name] = [type: value.type, value: value.value, multiple: value.value instanceof List]; if (value.type.startsWith('capability.')) typed[name].deviceList = (value.value instanceof List ? value.value : [value.value]).collectEntries { [(it.toString()): 'Switch'] }; events << ['updateSetting', name] }
    void updateLabel(String value) { label = value }
    void appButtonHandler(String name) {
        events << name
        if (name == 'stopRule') backing.stopped = backing.stopped != true
        if (name == 'pausRule') backing.paused = backing.paused != true
        if (name == 'updateRule') { backing.stopped = false; backing.runtimeCursor = 'reset by native update' }
        // RM's fixAppLabel: the name, plus a status span derived from the stopped state.
        if (decorates) label = (relabelTo ?: typed.origLabel?.value ?: label) + (backing.stopped ? " <span style='color:red'>(Stopped)</span>" : '')
    }
    Map getAllLocalVars() { backing.findAll { key, value -> key.startsWith('lv_') }.collectEntries { key, value -> [(key.substring(3)): value] } }
}
