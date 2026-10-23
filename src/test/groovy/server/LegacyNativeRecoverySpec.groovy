package server

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Shared
import support.TestChildApp
import support.ToolSpecBase

class LegacyNativeRecoverySpec extends ToolSpecBase {
    @Shared TestChildApp serverApp = new TestChildApp(id: 7L, label: 'MCP')
    Map snapshot
    void setupSpec() { appExecutor.getApp() >> serverApp }
    void setup() {
        snapshot = [ruleId: 42, appId: 42, appType: 'rule_machine', appLabel: 'Original',
            configJson: [app: [id: 42, parentAppId: 28, appType: [namespace: 'hubitat', name: 'Rule-5.1']], settings: [origLabel: 'Original']],
            statusJson: [installedApp: [id: 42], appState: [[name: 'actions', value: [:]],
                [name: 'capabstrue', value: ['2': 'Original trigger']],
                [name: 'capabsfalse', value: ['1': 'Original condition']], [name: 'eval', value: ['0': ['1']]],
                [name: 'lv_counter', value: [type: 'integer', value: 7]]],
                appSettings: [[name: 'enabled', type: 'bool', value: false],
                    [name: 'rDev_1', type: 'capability.switch', deviceList: ['591': 'Switch'], multiple: true]],
                eventSubscriptions: []]]
    }

    def 'complete legacy state and typed settings become the strict recovery format without reading current state'() {
        given:
        script.metaClass.hubInternalGet = { Object... ignored -> throw new AssertionError('adapter must use only saved data') }
        when:
        def adapted = script._rmLegacyNativeRecovery(snapshot)
        def payload = new JsonSlurper().parseText(adapted.nativeExport)
        then:
        adapted.captureFormat == 'native-rule-edit-v1'
        payload.appData['42'].state.capabsfalse == ['1': 'Original condition']
        payload.appData['42'].state.eval == ['0': ['1']]
        payload.appData['42'].state.lv_counter.value == 7
        payload.appData['42'].appSettings == snapshot.statusJson.appSettings
        payload.appReplacements['42'].parentAppInstalledAppId == '28'
        !snapshot.containsKey('captureFormat')
    }

    def 'legacy recovery uses strict same ID state replacement settings removal undo and native rebuild'() {
        given:
        script.callerClient = null
        stateMap.lastBackupTimestamp = script.now()
        List events = []
        def child = new RestoreNativeChild(events)
        child.backing = [actions: ['9': [method: 'getLogMsg']], actionList: ['9'], capabstrue: ['9': 'added trigger'],
            capabsfalse: ['9': 'added condition'], eval: ['0': ['9']], stopped: false, paused: false, lv_added: [type: 'string', value: 'extra']]
        child.typed = [comments: [type: 'text', value: 'added comments', multiple: false]]
        child.rebuild = { events << 'rebuild'; child.label = installedLabel }
        snapshot.configJson.app.label = installedLabel
        snapshot.statusJson.appSettings = [[name: 'enabled', type: 'bool', value: false], [name: 'origLabel', type: 'text', value: 'Original']]
        snapshot.appLabel = 'Original (Required Expression false)'
        script.metaClass.getAppByAppId = { Long id -> [id: id, parentAppId: 28L, appType: [namespace: 'hubitat', name: 'Rule-5.1']] }
        script.metaClass._nrHandle = { Long id -> child }
        script.metaClass._rmBackupRuleSnapshot = { Integer id, String reason, boolean strict, String protectedKey ->
            events << ['undo', strict, protectedKey]
            [backupKey: 'undo']
        }
        hubGet.register('/installedapp/settings/42') { q -> JsonOutput.toJson([settings: child.typed.collect { name, row -> [name: name] + row }]) }
        hubGet.register('/app/ruleBuilderJson/42') { q -> JsonOutput.toJson([broken: false, stopped: child.backing.stopped == true, paused: false]) }
        hubGet.register('/installedapp/statusJson/42') { q -> JsonOutput.toJson([eventSubscriptions: [[name: 'switch.on', typeId: 591]], scheduledJobs: []]) }
        script.metaClass.hubInternalPostFormRaw = { String path, String body, int timeout = 420, boolean retry = false -> [status: 200] }
        when:
        def result = script._rmRestoreFromBackup([fileName: 'old.json'], snapshot, true, 'source')
        then:
        result.success
        result.restoredVia == 'nativeStateReplay'
        result.ruleId == 42L
        result.stateVerified && result.settingsVerified && result.localVariablesVerified
        events[0] == ['undo', true, 'source']
        child.backing.capabstrue == ['2': 'Original trigger']
        child.backing.capabsfalse == ['1': 'Original condition']
        child.backing.eval == ['0': ['1']]
        child.backing.lv_added == null
        child.backing.lv_counter.value == 7
        !child.typed.containsKey('comments')
        child.label == installedLabel
        child.typed.origLabel.value == 'Original'
        where:
        installedLabel << ['Original', "Original<span style='color:red'> (Required Expression false)</span>"]
    }

    def 'a legacy snapshot of a rule with no subscriptions adapts with an empty subscription list'() {
        given:
        snapshot.statusJson.eventSubscriptions = null
        when:
        def adapted = script._rmLegacyNativeRecovery(snapshot)
        then:
        adapted.captureFormat == 'native-rule-edit-v1'
        new JsonSlurper().parseText(adapted.nativeExport).appData['42'].subscriptions == []
    }

    def 'a device row without a deviceList map plans its devices from deviceIdsForDeviceList'() {
        when:
        def plan = script._rmNativeSettingPlan([[name: 'tDev1', type: 'capability.switch', deviceList: null, deviceIdsForDeviceList: [16], multiple: true]])
        then:
        plan == [[name: 'tDev1', type: 'capability.switch', multiple: true, value: ['16']]]
    }

    def 'incomplete historical status keeps the legacy compatibility path'() {
        given:
        snapshot.statusJson.remove('appState')
        expect:
        script._rmLegacyNativeRecovery(snapshot) == null
    }

    def 'claimed complete malformed recovery refuses before native reads or writes'() {
        given:
        corrupt.call(snapshot)
        script.metaClass.getAppByAppId = { Long id -> throw new AssertionError('invalid snapshot must refuse before target access') }
        when:
        script._rmRestoreFromBackup([fileName: 'old.json'], snapshot, true, 'source')
        then:
        thrown(Exception)
        where:
        corrupt << [
            { s -> s.configJson.app.id = 43 },
            { s -> s.statusJson.installedApp.id = 43 },
            { s -> s.statusJson.appState << [name: 'eval', value: [:]] },
            { s -> s.statusJson.appState = 'not state' },
            { s -> s.statusJson.appSettings << [name: 'enabled', type: 'bool', value: true] },
            { s -> s.statusJson.eventSubscriptions = 'not a list' },
            { s -> s.nativeExport = '{}' }
        ]
    }

    def 'legacy adapter preserves strict device preflight and does not capture undo for missing devices'() {
        given:
        script.metaClass.getAppByAppId = { Long id -> [id: id, parentAppId: 28L, appType: [namespace: 'hubitat', name: 'Rule-5.1']] }
        hubGet.register('/device/fullJson/591') { q -> JsonOutput.toJson([:]) }
        script.metaClass._rmBackupRuleSnapshot = { Object... ignored -> throw new AssertionError('no undo before device preflight') }
        when:
        def result = script._rmRestoreFromBackup([fileName: 'old.json'], snapshot, true, 'source')
        then:
        !result.success
        !result.partial
        result.failedStep == 'devicePreflight'
        result.missingDeviceIds == ['591']
    }
}
