package server

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import support.ToolSpecBase

/**
 * _rmBackupRuleSnapshot writes configure/json to File Manager. The hub's app.appType record
 * carries the type's OAuth client credentials and passwords (live-verified on Rule-5.1). The
 * snapshot drops exactly those four fields and keeps the rest of the record.
 */
class RmBackupSnapshotCredentialsSpec extends ToolSpecBase {

    List uploads = []

    def "rule backups drop the four appType credential fields and keep every other field"() {
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
        snap.configJson.app.appType == [name: 'Rule-5.1', namespace: 'hubitat', author: 'Hubitat']
        snap.configJson.settings == [ruleEnable: true]
        ['client-id-value', 'client-secret-value', 'enc-value', 'src-value'].every { !written.contains(it) }
    }
}
