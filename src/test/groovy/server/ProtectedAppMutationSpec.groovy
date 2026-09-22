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
        hubGet.register('/installedapp/statusJson/42') {
            '{"installedApp":{"id":42,"name":"Dashboard","systemAppType":true}}'
        }

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
            ['toolSetRule', [appId: ' 42 ', settings: [enableDeveloperMode: true]]],
            ['toolSetNativeApp', [appId: '00042', button: 'updateRule']],
            ['toolSetAppDisabled', [appId: '00042', disabled: true]],
            ['toolSetAppDisabled', [appId: 42, disabled: false]],
            ['toolDeleteNativeApp', [appId: 42]],
            ['toolDeleteNativeApp', [appId: ' 42 ', force: true]],
            ['toolSetVisualRule', [appId: 42, paused: true]],
            ['toolDeleteVisualRule', [appId: 42]],
            ['toolUpdateDashboard', [dashboardId: '42', name: 'Changed']],
            ['toolDeleteDashboard', [dashboardId: '42']],
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
        if (operation == 'clone') script._mrtrCloneNativeAppSlice(rec, [:])
        else script._mrtrImportNativeAppSlice(rec, [:])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.toLowerCase().contains('protected')
        writes.every { it == '/installedapp/forcedelete/900/quiet' }

        where:
        operation << ['clone', 'import']
    }
    @Unroll
    def 'generic protection is enforced through #gateway dispatch with Developer Mode #developerMode'() {
        given:
        settingsMap.enableDeveloperMode = developerMode
        settingsMap.enableMandatoryBPS = false
        settingsMap.useGateways = gateway
        def args = [appId: 42, settings: [protectedAppIds: []], confirm: true]

        when:
        script.executeTool(gateway ? 'hub_manage_native_rules_and_apps' : 'hub_set_native_app',
            gateway ? [tool: 'hub_set_native_app', args: args] : args)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.toLowerCase().contains('protected')
        writes.empty

        where:
        [gateway, developerMode] << [[false, true], [false, true]].combinations()
    }

    @Unroll
    def 'rule batch #method refuses all dispatch when one target is protected'() {
        given:
        rmUtils = new RMUtilsMock(stubRuleList: [[id: 43, name: 'Allowed'], [id: 42, name: 'Protected']])
        rmUtils.install()
        hubGet.register('/hub2/appsList') {
            '{"apps":[{"data":{"id":43}},{"data":{"id":42}}]}'
        }

        when:
        script."$method"([ruleId: [43, 42]] + args)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.toLowerCase().contains('protected')
        !rmUtils.calls.any { it.method == 'sendAction' }
        writes.empty

        where:
        method                  | args
        'toolRunRmRule'          | [action: 'actions']
        'toolSetRulePaused'      | [paused: true]
        'toolSetRmRuleBoolean'   | [value: true]
    }

    def 'Easy Dashboard with a protected installed-app numeric ID reaches its own update endpoint'() {
        given:
        hubGet.register('/installedapp/statusJson/42') { '{"installedApp":{"id":42,"name":"Other app","systemAppType":false}}' }
        hubGet.register('/dashboard/update') { writes << '/dashboard/update'; '{"success":true,"id":42}' }
        settingsMap.bypassDeviceAllowlist = true

        when:
        def result = script.toolUpdateDashboard([dashboardId: '42', name: 'Easy', deviceIds: ['1'],
            options: [dashboardPin: '', hsmPin: '']])

        then:
        noExceptionThrown()
        writes.contains('/dashboard/update')
    }

}
