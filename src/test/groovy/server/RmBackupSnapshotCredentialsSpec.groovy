package server

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import support.ToolSpecBase

/**
 * _rmBackupRuleSnapshot writes configure/json to File Manager. The hub's app.appType record
 * carries the type's OAuth client credentials and passwords (live-verified on Rule-5.1), so the
 * snapshot keeps only the type's name and namespace.
 */
class RmBackupSnapshotCredentialsSpec extends ToolSpecBase {

    List uploads = []

    def "rule backups drop the appType credentials and keep the type identity"() {
        given:
        def captured = uploads
        script.metaClass.uploadHubFile = { String fn, byte[] b -> captured << b }
        hubGet.register('/installedapp/configure/json/75') { params ->
            JsonOutput.toJson([
                app: [id: 75, name: 'Rule-5.1', label: 'Hall', trueLabel: 'Hall', installed: true,
                      appType: [name: 'Rule-5.1', namespace: 'hubitat', oauthClientId: 'client-id-value',
                                oauthClientSecret: 'client-secret-value', encryptedPassword: 'enc-value',
                                sourcePassword: 'src-value', author: 'Hubitat']],
                configPage: [name: 'mainPage', title: '', install: true, sections: []],
                settings: [ruleEnable: true],
                childApps: []
            ])
        }
        hubGet.register('/installedapp/statusJson/75') { params ->
            JsonOutput.toJson([installedApp: [id: 75], appSettings: [], appState: []])
        }

        when:
        script._rmBackupRuleSnapshot(75, 'pre-delete')
        String written = new String(uploads[0] as byte[], 'UTF-8')
        def snap = new JsonSlurper().parseText(written)

        then:
        uploads.size() == 1
        snap.appType == 'rule_machine'
        snap.configJson.app.appType == [name: 'Rule-5.1', namespace: 'hubitat']
        snap.configJson.settings == [ruleEnable: true]
        ['client-id-value', 'client-secret-value', 'enc-value', 'src-value'].every { !written.contains(it) }
    }
}
