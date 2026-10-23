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

    def 'generic native authoring retains its own catalog routing and continuation classification'() {
        expect:
        script.getAllToolDefinitions().count { it.name == 'hub_set_native_app' } == 1
        script.getAllToolDefinitions().count { it.name == 'hub_set_rule' } == 1
        script.getGatewayConfig().hub_manage_native_rules_and_apps.tools.contains('hub_set_native_app')
        script._guideSectionForTool('hub_set_native_app') == 'builtin_app_tools_crud'
        script._mrtrWriteTools().contains('hub_set_native_app')
        script._mrtrDetachedWorkerTools().contains('hub_set_native_app')
        !script.getReadOnlyToolNames().contains('hub_set_native_app')
        !script.getIdempotentWriteToolNames().contains('hub_set_native_app')
        script._isActualWriteCall('hub_set_native_app', 'hub_set_native_app', [appId: 42, settings: [comments: 'updated'], confirm: true])
        !script._isActualWriteCall('hub_set_native_app', 'hub_set_native_app', [appId: 42, guide: true])
    }

    def 'generic guide remains read shaped and does not replace the consolidated RM guide'() {
        given:
        settingsMap.enableRead = false
        settingsMap.enableWrite = false
        settingsMap.enableMandatoryBPS = true
        script.metaClass.hubInternalGet = { Object... args -> throw new AssertionError('Static guide must not read the hub') }
        when:
        def result = script.executeTool('hub_set_native_app', [appId: 42, guide: true])
        then:
        result.section == 'builtin_app_tools_crud'
        result.content.contains('hub_set_native_app')
        result.content.contains('walkStep')
        script.getToolGuideSections().set_rule_reference.contains('operation')
        !script.getToolGuideSections().containsKey('native_app_reference')
    }
    def 'removed RM shortcut and internal creation aliases refuse before any hub work'() {
        given:
        script.metaClass.hubInternalGet = { Object... args -> throw new AssertionError('Removed arguments must not read native state') }
        script.metaClass.hubInternalGetRaw = { Object... args -> throw new AssertionError('Removed arguments must not create a child') }
        script.metaClass._rmBackupRuleSnapshot = { Object... args -> throw new AssertionError('Removed arguments must not take a backup') }
        when:
        script.toolSetNativeApp(request)
        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('hub_set_rule')
        where:
        request << ['addTrigger', 'addTriggers', 'addAction', 'addActions', 'addRequiredExpression',
            'replaceRequiredExpression', 'addLocalVariable', 'removeLocalVariable', 'patches',
            'replaceActions', 'removeAction', 'clearActions', 'moveAction', 'removeTrigger',
            'modifyTrigger', 'modifyAction', 'triggers', 'actions', 'requiredExpression'].collectMany { key ->
            [[name: 'Rejected shell', confirm: true, (key): [:]], [appId: 42, confirm: true, (key): [:]]]
        }
    }

    def 'generic guide cannot silently ignore a bundled edit or controller creation'() {
        given:
        script.metaClass.hubInternalGet = { Object... args -> throw new AssertionError('Mixed guide must not read native state') }
        when:
        script.toolSetNativeApp([appId: 42, guide: true, confirm: true] + extra)
        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('guide:true must be requested alone')
        where:
        extra << [[settings: [comments: 'ignored']], [button: 'update'],
            [walkStep: [page: 'mainPage', operation: 'write', write: [comments: 'ignored']]],
            [buttonRule: [controllerId: 43, buttonNumber: 1, event: 'pushed']]]
    }

    def 'generic page validation refuses unsafe names before snapshot or native reads'() {
        given:
        stateMap.lastBackupTimestamp = script.now()
        script.metaClass.hubInternalGet = { Object... args -> throw new AssertionError('Invalid page must not read native state') }
        when:
        script.toolSetNativeApp([appId: 42, settings: [comments: 'test'], pageName: '../invalid', confirm: true])
        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('pageName must')
    }

    def 'generic walker continuation preserves its native walkStep shape'() {
        given:
        def remaining = [[page: 'offMeansPage', operation: 'done']]
        def args = [tool: 'hub_set_native_app', args: [appId: 42, confirm: true,
            walkStep: [operation: 'drive', steps: [[page: 'onMeansPage', operation: 'introspect']] + remaining]]]
        def rec = [outerTool: 'hub_manage_native_rules_and_apps', leafTool: 'hub_set_native_app']
        when:
        def continuation = script._mrtrContinuation('hub_set_native_app', args,
            [status: 'in_progress', stepsRemaining: remaining, page: 'offMeansPage'], rec)
        then:
        continuation.kind == 'walk_steps'
        continuation.nextArguments.args.appId == 42
        continuation.nextArguments.args.walkStep.steps == remaining
        continuation.nextArguments.args.walkStep.page == 'offMeansPage'
        !continuation.nextArguments.args.containsKey('operation')
        script._mrtrContinuation('hub_set_native_app', args,
            [status: 'in_progress', addActionsRemaining: [[capability: 'log']]], rec) == null
    }

}
