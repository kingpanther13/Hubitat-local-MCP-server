package server

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import support.ToolSpecBase

/**
 * Pre-write snapshots land in File Manager, so password-typed settings are dropped from them:
 * the value leaves configJson.settings, the typed rows keep their name and type with a null
 * value, and redactedSettings names them for restore.
 */
class SnapshotPasswordRedactionSpec extends ToolSpecBase {

    def "a classic app snapshot stores no password value and names the redacted setting"() {
        given:
        List uploads = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> uploads << new String(b, 'UTF-8') }
        hubGet.register('/installedapp/configure/json/76') { params ->
            JsonOutput.toJson([
                app: [id: 76, name: 'Notifier', label: 'Alerts', trueLabel: 'Alerts', installed: true, appType: [name: 'Notifier', namespace: 'hubitat']],
                configPage: [name: 'mainPage', sections: [[input: [[name: 'apiKey', type: 'password', value: 'live-secret'], [name: 'msg', type: 'text']]]]],
                settings: [apiKey: 'live-secret', msg: 'hello'],
                childApps: []])
        }
        hubGet.register('/installedapp/statusJson/76') { params ->
            JsonOutput.toJson([installedApp: [id: 76], appState: [],
                appSettings: [[name: 'apiKey', type: 'password', multiple: false, value: 'live-secret'], [name: 'msg', type: 'text', value: 'hello']]])
        }

        when:
        script._rmBackupRuleSnapshot(76, 'pre-update')
        def snap = new JsonSlurper().parseText(uploads[0])

        then:
        !uploads[0].contains('live-secret')
        snap.redactedSettings == ['apiKey']
        snap.configJson.settings == [msg: 'hello']
        snap.statusJson.appSettings.find { it.name == 'apiKey' } == [name: 'apiKey', type: 'password', multiple: false, value: null]
        snap.configJson.configPage.sections[0].input[0].value == null
    }

    def "a snapshot without password settings carries no redactedSettings"() {
        given:
        List uploads = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> uploads << new String(b, 'UTF-8') }
        hubGet.register('/installedapp/configure/json/77') { params ->
            JsonOutput.toJson([app: [id: 77, name: 'Notifier', label: 'A', installed: true, appType: [name: 'Notifier', namespace: 'hubitat']],
                configPage: [name: 'mainPage', sections: []], settings: [msg: 'hello'], childApps: []])
        }
        hubGet.register('/installedapp/statusJson/77') { params -> JsonOutput.toJson([installedApp: [id: 77], appState: [], appSettings: [[name: 'msg', type: 'text', value: 'hello']]]) }

        when:
        script._rmBackupRuleSnapshot(77, 'pre-update')

        then:
        !new JsonSlurper().parseText(uploads[0]).containsKey('redactedSettings')
    }

    def "a native rule export is stored with its password rows nulled"() {
        given:
        String export = JsonOutput.toJson([
            appReplacements: ['42': [appTypeNamespace: 'hubitat', appTypeName: 'Rule-5.1', parentAppInstalledAppId: '28', appLabel: 'Hall']],
            deviceReplacements: [:],
            appData: ['42': [state: [actionList: []], subscriptions: [], appSettings: [
                [name: 'origLabel', type: 'text', value: 'Hall'], [name: 'token', type: 'password', value: 'live-secret']]]]])

        when:
        def built = script._rmBuildNativeEditSnapshot(42, 'pre-native-form', export)
        def snap = built.snapshot

        then:
        !snap.nativeExport.contains('live-secret')
        new JsonSlurper().parseText(snap.nativeExport).appData['42'].appSettings.find { it.name == 'token' }.value == null
        snap.redactedSettings == ['token']
        !snap.configJson.settings.containsKey('token')
        snap.configJson.settings.origLabel == 'Hall'

        and: "an export without passwords is stored byte for byte"
        String plain = export.replace('"password"', '"text"')
        script._rmRedactExportPasswords(plain, [] as Set).is(plain)
    }
}
