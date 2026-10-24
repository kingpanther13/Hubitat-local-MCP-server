package server

import spock.lang.Shared
import support.TestChildApp
import support.ToolSpecBase

class NativeAppRestorationSpec extends ToolSpecBase {
    @Shared TestChildApp serverApp = new TestChildApp(id: 7L, label: 'MCP')

    void setupSpec() { appExecutor.getApp() >> serverApp }

    void setup() {
        script.callerClient = null
        script.metaClass.getAppByAppId = { Long id -> [id: id, appType: [namespace: 'hubitat', name: 'Basic Rule-1.0']] }
    }

    void cleanup() { script.callerClient = null }

    def 'generic native authoring keeps its catalog routing, guide section and continuation classification'() {
        expect:
        script.getAllToolDefinitions().count { it.name == 'hub_set_native_app' } == 1
        script.getAllToolDefinitions().count { it.name == 'hub_set_rule' } == 1
        script.getGatewayConfig().hub_manage_native_rules_and_apps.tools.contains('hub_set_native_app')
        script._guideSectionForTool('hub_set_native_app') == 'set_native_app_reference'
        script._mrtrWriteTools().contains('hub_set_native_app')
        script._mrtrDetachedWorkerTools().contains('hub_set_native_app')
        !script._budgetAwareTools().contains('hub_set_native_app')
        !script.getReadOnlyToolNames().contains('hub_set_native_app')
        !script.getIdempotentWriteToolNames().contains('hub_set_native_app')
        script._isActualWriteCall('hub_set_native_app', 'hub_set_native_app', [operation: 'update', app_id: 42, args: [fields: [comments: 'updated']], confirm: true])
        !script._isActualWriteCall('hub_set_native_app', 'hub_set_native_app', [:])
        !script._isActualWriteCall('hub_set_native_app', 'hub_set_native_app', [operation: 'update', app_id: 42])
    }

    def 'help stays read shaped with writes off and points at the native app guide, which points at the form contract'() {
        given:
        settingsMap.enableRead = true
        settingsMap.enableWrite = false
        settingsMap.enableMandatoryBPS = true
        script.metaClass.hubInternalGet = { Object... args -> throw new AssertionError('Help must not read the hub') }
        when:
        def result = script.executeTool('hub_set_native_app', [:])
        def guide = script.getToolGuideSections().set_native_app_reference
        then:
        result.operations == ['create', 'update', 'form']
        result.usage.contains('set_native_app_reference')
        guide.contains('set_rule_reference')
        guide.contains('button_rule')
        !guide.contains('walkStep')
        script.getToolGuideSections().set_rule_reference.contains('hub_set_native_app(operation:"form"')
    }

    def 'a confirmed write still needs the Write master'() {
        given:
        settingsMap.enableWrite = false
        when:
        script.executeTool('hub_set_native_app', [operation: 'update', app_id: 42, args: [fields: [comments: 'x']], confirm: true])
        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('Write tools are disabled')
    }

    def 'retired RM shortcut and walker arguments refuse before any hub work'() {
        given:
        script.metaClass.hubInternalGet = { Object... args -> throw new AssertionError('Removed arguments must not read native state') }
        script.metaClass.hubInternalGetRaw = { Object... args -> throw new AssertionError('Removed arguments must not create a child') }
        script.metaClass._rmBackupRuleSnapshot = { Object... args -> throw new AssertionError('Removed arguments must not take a backup') }
        when:
        script.toolSetNativeApp(request)
        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('Unknown')
        where:
        request << ['addTrigger', 'addAction', 'patches', 'walkStep', 'buttonRule', 'settings', 'button', 'pageName', 'stateAttribute', 'guide'].collectMany { key ->
            [[operation: 'create', args: [app_type: 'notifier', name: 'Rejected shell', (key): [:]], confirm: true],
             [operation: 'update', app_id: 42, args: [fields: [a: 1], (key): [:]], confirm: true],
             [appId: 42, confirm: true, (key): [:]]]
        }
    }

    def 'update page validation refuses unsafe names before snapshot or native reads'() {
        given:
        stateMap.lastBackupTimestamp = script.now()
        script.metaClass.hubInternalGet = { Object... args -> throw new AssertionError('Invalid page must not read native state') }
        when:
        script.toolSetNativeApp([operation: 'update', app_id: 42, args: [fields: [comments: 'test'], page: '../invalid'], confirm: true])
        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('page must')
    }

    def 'a continuation never resumes a hub_set_native_app remainder'() {
        given:
        def args = [tool: 'hub_set_native_app', args: [operation: 'update', app_id: 42, args: [fields: [a: 1]], confirm: true]]
        def rec = [outerTool: 'hub_manage_native_rules_and_apps', leafTool: 'hub_set_native_app']
        expect:
        script._mrtrContinuation('hub_set_native_app', args, [status: 'in_progress', stepsRemaining: [[page: 'x']], page: 'x'], rec) == null
    }
}
