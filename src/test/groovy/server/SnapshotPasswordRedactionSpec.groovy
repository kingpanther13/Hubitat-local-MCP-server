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

    def "a snapshot drops a credential-named text setting the way hub_get_app_config redacts it"() {
        given:
        List uploads = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> uploads << new String(b, 'UTF-8') }
        hubGet.register('/installedapp/configure/json/78') { params ->
            JsonOutput.toJson([
                app: [id: 78, name: 'Bridge', label: 'Bridge', trueLabel: 'Bridge', installed: true, appType: [name: 'Bridge', namespace: 'someDeveloper']],
                configPage: [name: 'mainPage', sections: [[input: [[name: 'apiKey', type: 'text', value: 'ak-live-1'], [name: 'disarmPin', type: 'text', value: '4321'], [name: 'zipCode', type: 'text', value: '02134']]]]],
                settings: [apiKey: 'ak-live-1', disarmPin: '4321', zipCode: '02134'],
                childApps: []])
        }
        hubGet.register('/installedapp/statusJson/78') { params ->
            JsonOutput.toJson([installedApp: [id: 78], appState: [],
                appSettings: [[name: 'apiKey', type: 'text', value: 'ak-live-1'], [name: 'disarmPin', type: 'text', value: '4321'], [name: 'zipCode', type: 'text', value: '02134']]])
        }

        when:
        script._rmBackupRuleSnapshot(78, 'pre-update')
        def snap = new JsonSlurper().parseText(uploads[0])

        then:
        !uploads[0].contains('ak-live-1')
        !uploads[0].contains('4321')
        snap.redactedSettings == ['apiKey', 'disarmPin']
        snap.configJson.settings == [zipCode: '02134']
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

    def "a snapshot whose statusJson cannot be read fails closed instead of storing unclassified settings"() {
        given:
        List uploads = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> uploads << fn }
        hubGet.register('/installedapp/configure/json/78') { params ->
            JsonOutput.toJson([app: [id: 78, name: 'Notifier', label: 'A', installed: true, appType: [name: 'Notifier', namespace: 'hubitat']],
                configPage: [name: 'mainPage', sections: []], settings: [subPagePassword: 'live-secret'], childApps: []])
        }
        hubGet.register('/installedapp/statusJson/78') { params -> throw new RuntimeException('statusJson timed out') }

        when:
        script._rmBackupRuleSnapshot(78, 'pre-update')

        then:
        def failure = thrown(IllegalStateException)
        failure.message == "App 78: its settings could not be read (statusJson timed out), so no snapshot was taken; nothing was changed. " +
            "Retry; or delete it from the hub's Apps page; or turn off pre-edit backups for edits."
        uploads.isEmpty()
    }

    def "a snapshot whose status read returns no settings list says so instead of naming a failure"() {
        given:
        List uploads = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> uploads << fn }
        hubGet.register('/installedapp/configure/json/78') { params ->
            JsonOutput.toJson([app: [id: 78, name: 'Notifier', label: 'A', installed: true, appType: [name: 'Notifier', namespace: 'hubitat']],
                configPage: [name: 'mainPage', sections: []], settings: [subPagePassword: 'live-secret'], childApps: []])
        }
        hubGet.register('/installedapp/statusJson/78') { params -> JsonOutput.toJson([installedApp: [id: 78]]) }

        when:
        script._rmBackupRuleSnapshot(78, 'pre-update')

        then:
        def failure = thrown(IllegalStateException)
        failure.message == "App 78: its settings could not be read (its status read returned no settings list), so no snapshot was taken; nothing was changed. " +
            "Retry; or delete it from the hub's Apps page; or turn off pre-edit backups for edits."
        uploads.isEmpty()
    }

    def "a user or community app is snapshotted under its own type, not as a Rule Machine rule"() {
        given:
        List uploads = []
        script.metaClass.uploadHubFile = { String fn, byte[] b -> uploads << new String(b, 'UTF-8') }
        hubGet.register('/installedapp/configure/json/79') { params ->
            JsonOutput.toJson([app: [id: 79, name: 'BAT555 Stateful Wizard', label: 'W', installed: true, appType: [name: 'BAT555 Stateful Wizard', namespace: 'someDeveloper']],
                configPage: [name: 'mainPage', sections: []], settings: [count: '1'], childApps: []])
        }
        hubGet.register('/installedapp/statusJson/79') { params -> JsonOutput.toJson([installedApp: [id: 79], appState: [], appSettings: [[name: 'count', type: 'number', value: '1']]]) }

        when:
        script._rmBackupRuleSnapshot(79, 'pre-update')
        def snap = new JsonSlurper().parseText(uploads[0])

        then:
        snap.appType == 'app'
        snap.appTypeName == 'BAT555 Stateful Wizard'
        !snap.containsKey('nativeExport')
    }

    def "a snapshot taken for a page renders that page, not the app's main page"() {
        given:
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        hubGet.register('/installedapp/configure/json/80/subPage') { params ->
            JsonOutput.toJson([app: [id: 80, name: 'Wizard', label: 'W', installed: true, appType: [name: 'Wizard', namespace: 'someDeveloper']],
                configPage: [name: 'subPage', sections: []], settings: [step: '3'], childApps: []])
        }
        hubGet.register('/installedapp/statusJson/80') { params -> JsonOutput.toJson([installedApp: [id: 80], appState: [], appSettings: [[name: 'step', type: 'number', value: '3']]]) }

        when:
        def backup = script._rmBackupBeforeEdit(80, 'pre-native-form', 'subPage')

        then:
        backup.backupKey
        hubGet.calls*.path.contains('/installedapp/configure/json/80/subPage')
        !hubGet.calls*.path.contains('/installedapp/configure/json/80')
    }
}
