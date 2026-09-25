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
        atomicStateMap.protectedAppsPolicy = [ids: ['42']]
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
        result.discriminator == 'capability'
        result.capabilities.find { it.name == 'Switch' }?.requiredFields*.name == ['deviceIds', 'state']
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

    def 'cascading delete uses the same protection snapshot before and after the inventory read'() {
        given:
        hubGet.register('/hub2/appsList') {
            atomicStateMap.protectedAppsPolicy = [ids: []]
            '{"apps":[{"data":{"id":21},"children":[{"data":{"id":42}}]}]}'
        }

        when:
        script.toolDeleteNativeApp([appId: 21, force: true, confirm: true])

        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains('App 42 is protected')
        atomicStateMap.protectedAppsPolicy.ids.empty
        writes.empty
    }

    @Unroll
    def '#appType backup restore checks the embedded target before applying settings'() {
        when:
        script._rmRestoreFromBackup([fileName: 'backup.json'], [ruleId: 42, appType: appType, configJson: [settings: [:]]])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.toLowerCase().contains('protected')
        e.message.contains('42')
        writes.empty

        where:
        appType << ['rule_machine', 'visual_rule']
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

    @Unroll
    def 'protected #type dashboard refuses #operation through #gateway dispatch'() {
        given:
        settingsMap.enableMandatoryBPS = false
        settingsMap.useGateways = gateway
        hubGet.register('/installedapp/statusJson/42') {
            JsonOutput.toJson([installedApp: [id: 42, name: type, systemAppType: true]])
        }
        hubGet.register('/dashboard/update') { writes << '/dashboard/update'; '{"success":true}' }
        hubGet.register('/dashboard/delete') { writes << '/dashboard/delete'; '{"success":true}' }
        def leaf = "hub_${operation}_dashboard".toString()
        def args = [dashboardId: '42', name: 'Changed', deviceIds: ['1'], confirm: true]

        when:
        script.executeTool(gateway ? 'hub_manage_dashboards' : leaf,
            gateway ? [tool: leaf, args: args] : args)

        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains('App 42 is protected')
        writes.empty

        where:
        [type, operation, gateway] << [['Easy Dashboard', 'Dashboard'], ['update', 'delete'], [false, true]].combinations()
    }

    @Unroll
    def '#type dashboard delete can be retried after removal through gateway=#gateway'() {
        given:
        settingsMap.enableMandatoryBPS = false
        settingsMap.useGateways = gateway
        boolean present = true
        hubGet.register('/hub2/appsList') {
            JsonOutput.toJson([apps: [[data: [id: 42, type: 'MCP Rule Server']],
                [data: [id: 21, type: parentType], children: present ? [[data: [id: 43, type: type]]] : []]]])
        }
        hubGet.register('/installedapp/statusJson/43') {
            JsonOutput.toJson([installedApp: [id: 43, name: type, systemAppType: true]])
        }
        hubGet.register('/dashboard/delete') {
            writes << '/dashboard/delete'
            present = false
            '{"success":true}'
        }
        script.metaClass.hubInternalGetRaw = { String path, Map params = null, Integer timeout = 30 ->
            writes << path
            present = false
            [status: 302]
        }
        def delete = {
            def args = [dashboardId: '43', confirm: true]
            script.executeTool(gateway ? 'hub_manage_dashboards' : 'hub_delete_dashboard',
                gateway ? [tool: 'hub_delete_dashboard', args: args] : args)
        }

        when: 'the first response could be lost after the delete commits'
        def first = delete()

        then:
        first.success == true
        first.alreadyAbsent != true
        !present
        writes.size() == 1

        when: 'the client retries with the target absent from the complete inventory'
        writes.clear()
        hubGet.calls.clear()
        def retry = delete()

        then:
        retry.success == true
        retry.alreadyAbsent == true
        retry.id == '43'
        retry.message.contains('already absent')
        writes.empty
        !hubGet.calls.any { it.path == '/installedapp/statusJson/43' }

        where:
        [type, gateway] << [['Easy Dashboard', 'Dashboard'], [false, true]].combinations()
        parentType = type == 'Dashboard' ? 'Hubitat® Dashboard' : 'Easy Dashboard Parent'
    }

    def 'dashboard delete marks a never-installed target as already absent without a write'() {
        given:
        hubGet.register('/hub2/appsList') {
            '{"apps":[{"data":{"id":42,"type":"MCP Rule Server"}}]}'
        }

        when:
        def result = script.toolDeleteDashboard([dashboardId: '999', confirm: true])

        then:
        result.success == true
        result.id == '999'
        result.alreadyAbsent == true
        writes.empty
        !hubGet.calls.any { it.path == '/installedapp/statusJson/999' }
    }

    @Unroll
    def 'dashboard delete refuses #problem inventory instead of claiming absence'() {
        given:
        hubGet.register('/hub2/appsList') { inventory }

        when:
        script.toolDeleteDashboard([dashboardId: '43', confirm: true])

        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains(diagnostic)
        writes.empty
        !hubGet.calls.any { it.path == '/installedapp/statusJson/43' }

        where:
        problem              | inventory                                                                   | diagnostic
        'empty app data'     | '{"apps":[{"data":{}}]}'                                                   | 'app tree is incomplete'
        'invalid child ID'   | '{"apps":[{"data":{"id":21},"children":[{"data":{"id":"bad"}}]}]}'       | 'app tree is incomplete'
        'non-list children'  | '{"apps":[{"data":{"id":21},"children":{}}]}'                            | 'app tree is incomplete'
        'unavailable'        | '{}'                                                                        | 'app tree is unavailable'
        'protected child'    | '{"apps":[{"data":{"id":43},"children":[{"data":{"id":42}}]}]}'          | 'App 42 is protected'
    }

    def 'Easy Dashboard creation refuses a protected parent before creating a child'() {
        given:
        hubGet.register('/hub2/appsList') {
            '{"apps":[{"data":{"id":42,"type":"Easy Dashboard Parent"},"children":[]}]}'
        }
        hubGet.register('/dashboard/create') { writes << '/dashboard/create'; '{"success":true}' }
        settingsMap.bypassDeviceAllowlist = true

        when:
        script.toolCreateDashboard([name: 'Easy', deviceIds: ['1']])

        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains('App 42 is protected')
        writes.empty
    }

    @Unroll
    def 'unprotected Easy Dashboard still allows #operation with unrelated protection'() {
        given:
        hubGet.register('/installedapp/statusJson/43') {
            '{"installedApp":{"id":43,"name":"Easy Dashboard","systemAppType":true}}'
        }
        hubGet.register('/hub2/appsList') {
            '{"apps":[{"data":{"id":42,"type":"MCP Rule Server"}},{"data":{"id":43,"type":"Easy Dashboard"}}]}'
        }
        hubGet.register('/dashboard/update') { writes << '/dashboard/update'; '{"success":true,"installedAppId":43}' }
        hubGet.register('/dashboard/delete') { writes << '/dashboard/delete'; '{"success":true}' }
        settingsMap.bypassDeviceAllowlist = true
        def method = operation == 'update' ? 'toolUpdateDashboard' : 'toolDeleteDashboard'

        when:
        def result = script."$method"([dashboardId: '43', name: 'Easy', deviceIds: ['1'], confirm: true,
            options: [dashboardPin: '', hsmPin: '']])

        then:
        result.success == true
        writes == ["/dashboard/${operation}".toString()]

        where:
        operation << ['update', 'delete']
    }

    @Unroll
    def 'Easy Dashboard creation permits #condition with unrelated app protection'() {
        given:
        hubGet.register('/hub2/appsList') { inventory }
        hubGet.register('/dashboard/create') {
            writes << '/dashboard/create'; '{"success":true,"installedAppId":73}'
        }
        settingsMap.bypassDeviceAllowlist = true

        when:
        def result = script.toolCreateDashboard([name: 'Easy', deviceIds: ['1']])

        then:
        result.success == true
        writes == ['/dashboard/create']

        where:
        condition           | inventory
        'unprotected parent'| '{"apps":[{"data":{"id":42,"type":"MCP Rule Server"}},{"data":{"id":21,"type":"Easy Dashboard Parent"}}]}'
        'absent parent'     | '{"apps":[{"data":{"id":42,"type":"MCP Rule Server"}}]}'
        'unrelated typeless app' | '{"apps":[{"data":{"id":42,"type":"MCP Rule Server"}},{"data":{"id":99}},{"data":{"id":21,"type":"Easy Dashboard Parent"}}]}'
        'unrelated blank type' | '{"apps":[{"data":{"id":42,"type":"MCP Rule Server"}},{"data":{"id":99,"type":""}}]}'
    }

    @Unroll
    def 'Easy Dashboard creation refuses #condition inventory before creating a child'() {
        given:
        hubGet.register('/hub2/appsList') {
            if (inventory == null) throw new IOException('offline')
            inventory
        }
        hubGet.register('/dashboard/create') { writes << '/dashboard/create'; '{"success":true}' }
        settingsMap.bypassDeviceAllowlist = true

        when:
        script.toolCreateDashboard([name: 'Easy', deviceIds: ['1']])

        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains('Cannot verify Easy Dashboard parent protection')
        writes.empty

        where:
        condition             | inventory
        'unreadable'           | null
        'missing apps'         | '{}'
        'non-list children'    | '{"apps":[{"data":{"id":42,"type":"Easy Dashboard Parent"},"children":{}}]}'
        'missing app type'     | '{"apps":[{"data":{"id":42}}]}'
        'blank protected type' | '{"apps":[{"data":{"id":42,"type":""}}]}'
        'empty app data'       | '{"apps":[{"data":{}}]}'
        'invalid app ID'       | '{"apps":[{"id":"bad","type":"Easy Dashboard Parent"}]}'
    }

    def 'visual child creation bootstraps a confirmed absent parent with default self protection'() {
        given:
        hubGet.register('/hub2/appsList') {
            '{"apps":[{"data":{"id":42,"type":"MCP Rule Server"}}]}'
        }
        script.metaClass.hubInternalGetRaw = { String path, Map params = null, Integer timeout = 30 ->
            writes << path
            [status: 200, data: '<script>HubitatRuleBuilder20AppId = 73;</script>']
        }

        when:
        def result = script._vrbCreateChild('2.0')

        then:
        result.appId == 73
        result.route == 'createVisualRuleBuilderRule'
        writes == ['/app/createVisualRuleBuilderRule']
        hubGet.calls.count { it.path == '/hub2/appsList' } == 1
    }

    @Unroll
    def 'visual child creation cannot bootstrap through #failure inventory when app protection is enabled'() {
        given:
        hubGet.register('/hub2/appsList') {
            if (failure == 'unreadable') throw new IOException('offline')
            response
        }

        when:
        script._vrbCreateChild('2.0')

        then:
        thrown(Exception)
        writes.empty

        where:
        failure       | response
        'unreadable'  | null
        'empty'       | ''
        'malformed'   | '{"apps":null}'
        'incomplete'  | '{"apps":[{"data":{"id":42}}]}'
    }

    def 'visual child creation refuses a protected parent without fallback'() {
        given:
        hubGet.register('/hub2/appsList') {
            '{"apps":[{"data":{"id":42,"type":"Visual Rules Builder"},"children":[]}]}'
        }

        when:
        script._vrbCreateChild('2.0')

        then:
        def e = thrown(IllegalArgumentException)
        e.message.toLowerCase().contains('protected')
        writes.empty
        hubGet.calls.count { it.path == '/hub2/appsList' } == 1
    }

    def 'visual child creation checks an existing unprotected parent once'() {
        given:
        hubGet.register('/hub2/appsList') {
            '{"apps":[{"data":{"id":70,"type":"Visual Rules Builder"},"children":[]}]}'
        }
        script.metaClass.hubInternalGetRaw = { String path, Map params = null, Integer timeout = 30 ->
            writes << path
            [status: 302, location: '/installedapp/configure/73']
        }

        when:
        def result = script._vrbCreateChild('2.0')

        then:
        result.appId == 73
        result.route == 'createchild'
        writes == ['/installedapp/createchild/hubitat/Visual Rule Builder 2.0/parent/70']
        hubGet.calls.count { it.path == '/hub2/appsList' } == 1
    }

    def 'native creation refuses its discovered protected parent before creating a child'() {
        given:
        hubGet.register('/hub2/appsList') {
            '{"apps":[{"data":{"id":42,"type":"Rule Machine","installed":true},"children":[]}]}'
        }

        when:
        script.toolSetNativeApp([appType: 'rule_machine', name: 'New rule', confirm: true])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.toLowerCase().contains('protected')
        e.message.contains('42')
        hubGet.calls.any { it.path == '/hub2/appsList' }
        writes.empty
    }

    @Unroll
    def 'initial #operation refuses protected destination parent (resumable #resumable)'() {
        given:
        hubGet.register('/installedapp/configure/json/100') {
            '{"app":{"id":100,"parentAppId":42,"label":"Source"},"settings":{},"configPage":{"sections":[]}}'
        }
        def args = operation == 'clone' ? [sourceAppId: 100, confirm: true] :
            [parentHintAppId: 100, jsonContent: '{"appReplacements":{"100":{"appLabel":"Source"}}}', confirm: true]
        def tool = "hub_${operation}_native_app".toString()
        def rec = [outerTool: tool, leafTool: tool]

        when:
        if (resumable) {
            if (operation == 'clone') script._mrtrCloneNativeAppSlice(rec, args)
            else script._mrtrImportNativeAppSlice(rec, args)
        } else {
            if (operation == 'clone') script.toolCloneNativeApp(args)
            else script.toolImportNativeApp(args)
        }

        then:
        def e = thrown(IllegalArgumentException)
        e.message.toLowerCase().contains('protected')
        e.message.contains('42')
        writes.empty

        where:
        [operation, resumable] << [['clone', 'import'], [false, true]].combinations()
    }

    def 'restoring a deleted native rule refuses its protected replacement parent'() {
        given:
        hubGet.register('/installedapp/configure/json/100') { throw new IOException('404 deleted') }
        hubGet.register('/hub2/appsList') {
            '{"apps":[{"data":{"id":42,"type":"Rule Machine","installed":true},"children":[]}]}'
        }

        when:
        script._rmRestoreFromBackup([fileName: 'deleted-rule.json'],
            [ruleId: 100, appType: 'rule_machine', appLabel: 'Deleted', configJson: [settings: [:]]])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.toLowerCase().contains('protected')
        e.message.contains('42')
        writes.empty
    }

    @Unroll
    def 'deletion refuses #problem app inventory before backup or deletion'() {
        given:
        hubGet.register('/hub2/appsList') { inventory }

        when:
        script.toolDeleteNativeApp([appId: 21, force: true, confirm: true])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(diagnostic)
        writes.empty

        where:
        problem              | inventory                                                                           | diagnostic
        'non-list children'  | '{"apps":[{"data":{"id":21},"children":{}}]}'                                      | 'app tree is incomplete'
        'invalid child ID'   | '{"apps":[{"data":{"id":21},"children":[{"data":{"id":"bad"}}]}]}'               | 'app tree is incomplete'
        'empty app data'     | '{"apps":[{"data":{"id":21},"children":[{"data":{},"children":[]}]}]}'            | 'app tree is incomplete'
        'missing target'     | '{"apps":[{"data":{"id":43}}]}'                                                     | 'App 21 is absent'
        'missing apps'       | '{}'                                                                                | 'app tree is unavailable'
        'non-list apps'      | '{"apps":{}}'                                                                       | 'app tree is unavailable'
    }

    def 'unrelated protection still permits backed-up deletion of an unprotected app'() {
        given:
        hubGet.register('/hub2/appsList') {
            '{"apps":[{"data":{"id":42}},{"data":{"id":43},"children":[]}]}'
        }
        hubGet.register('/installedapp/configure/json/43') {
            '{"app":{"id":43,"label":"Allowed","appType":{"name":"Rule-5.1"}},"settings":{},"configPage":{"sections":[]}}'
        }
        hubGet.register('/installedapp/statusJson/43') { '{"appState":[]}' }
        def files = [:]
        script.metaClass.uploadHubFile = { String name, byte[] content -> files[name] = content; writes << name }
        script.metaClass.downloadHubFile = { String name -> files[name] }

        when:
        def result = script.toolDeleteNativeApp([appId: 43, force: true, confirm: true])

        then:
        result.success == true
        result.backup.type == 'rm-rule'
        !files.isEmpty()
        writes.last() == '/installedapp/forcedelete/43/quiet'
        !writes.contains('/installedapp/forcedelete/42/quiet')
    }

}
