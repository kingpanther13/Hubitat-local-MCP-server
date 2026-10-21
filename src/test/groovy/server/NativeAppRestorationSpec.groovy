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
        result.section == 'native_app_reference'
        result.content.contains('hub_set_native_app')
        result.content.contains('walkStep')
        script.getToolGuideSections().set_rule_reference.contains('operation')
        script.getToolGuideSubSections().native_app_reference.containsKey('native_app_reference_walkstep')
    }
}
