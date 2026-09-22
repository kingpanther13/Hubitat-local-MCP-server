package server

import groovy.json.JsonOutput
import support.ToolSpecBase
import support.RMUtilsMock
import spock.lang.Unroll

class ProtectedAppMutationSpec extends ToolSpecBase {
    private List writes = []
    private RMUtilsMock rmUtils

    def setup() {
        settingsMap.enableWrite = true
        settingsMap.enableRead = true
        settingsMap.protectedAppIds = ['42']
        atomicStateMap.protectedAppsInitialized = true
        stateMap.lastBackupTimestamp = 1234567890000L
        script.metaClass.hubInternalPostJson = { String path, String body, Integer timeout = 420 ->
            writes << path; '{}'
        }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer timeout = 420 ->
            writes << path; [status: 200]
        }
        script.metaClass.hubInternalGetRaw = { String path, Map params = null, Integer timeout = 30 ->
            writes << path; [status: 302]
        }
        script.metaClass.uploadHubFile = { String name, byte[] content -> writes << name }
    }

    def cleanup() { rmUtils?.uninstall() }

    @Unroll
    def '#method rejects protected target before mutation with Developer Mode #developerMode'() {
        given:
        settingsMap.enableDeveloperMode = developerMode

        when:
        script."$method"(arguments + [confirm: true])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.toLowerCase().contains('protected')
        e.message.contains('42')
        writes.empty

        where:
        [developerMode, row] << [[false, true], [
            ['toolSetNativeApp', [appId: 42, settings: [protectedAppIds: []]]],
            ['toolSetRule', [appId: 'rm-42', settings: [enableDeveloperMode: true]]],
            ['toolSetNativeApp', [appId: 'rule-42', button: 'updateRule']],
            ['toolSetAppDisabled', [appId: '00042', disabled: true]],
            ['toolSetAppDisabled', [appId: 42, disabled: false]],
            ['toolDeleteNativeApp', [appId: 42]],
            ['toolDeleteNativeApp', [appId: 'rm-42', force: true]],
            ['toolSetVisualRule', [appId: 42, paused: true]],
            ['toolDeleteVisualRule', [appId: 42]],
            ['toolSetNativeApp', [buttonRule: [controllerId: 42, buttonNumber: 1, event: 'pushed']]],
            ['toolRunRmRule', [ruleId: 42, action: 'stop']],
            ['toolSetRulePaused', [ruleId: 42, paused: true]],
            ['toolSetRmRuleBoolean', [ruleId: 42, value: true]]
        ]].combinations()
        method = row[0]
        arguments = row[1]
    }

    def 'unprotected app disable still posts and verifies the requested flag'() {
        given:
        hubGet.register('/installedapp/json/43') { '{"id":43,"disabled":true}' }

        when:
        def result = script.toolSetAppDisabled([appId: 43, disabled: true])

        then:
        result.success
        result.appId == 43
        writes == ['/installedapp/disable']
    }

    def 'read-only authoring discovery remains available for a protected target'() {
        when:
        def result = script.toolSetRule([appId: 42, addTrigger: [discover: true]])

        then:
        result != null
        writes.empty
    }

    @Unroll
    def 'delete refuses a protected descendant before taking a backup (force #force)'() {
        given:
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([apps: [[data: [id: 21], children: [[data: [id: 30], children: [[data: [id: 42]]]]]]]])
        }

        when:
        script.toolDeleteNativeApp([appId: 21, force: force, confirm: true])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.toLowerCase().contains('protected')
        e.message.contains('42')
        writes.empty

        where:
        force << [false, true]
    }

    def 'delete fails closed if descendant protection cannot be verified'() {
        given:
        hubGet.register('/hub2/appsList') { throw new IOException('offline') }

        when:
        script.toolDeleteNativeApp([appId: 21, force: true, confirm: true])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.toLowerCase().contains('protection')
        writes.empty
    }

    def 'backup restore checks the embedded target before applying settings'() {
        when:
        script._rmRestoreFromBackup([fileName: 'backup.json'], [ruleId: 42, appType: 'rule_machine', configJson: [settings: [:]]])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.toLowerCase().contains('protected')
        writes.empty
    }

    @Unroll
    def 'resumed #operation refuses a newly protected parent before clone or import commits'() {
        given:
        def tool = "hub_${operation}_native_app".toString()
        def rec = [outerTool: tool, leafTool: tool, checkpoint: [
            phase: "${operation}_commit".toString(), parentAppId: 42, clonerAppId: 900,
            sourceAppId: 100, originalSourceId: 100, preIds: ['100']
        ]]

        when:
        def result = operation == 'clone' ? script._mrtrCloneNativeAppSlice(rec, [:]) : script._mrtrImportNativeAppSlice(rec, [:])

        then:
        result.success == false
        result.error.toLowerCase().contains('protected')
        writes.every { it == '/installedapp/forcedelete/900/quiet' }

        where:
        operation << ['clone', 'import']
    }
}
