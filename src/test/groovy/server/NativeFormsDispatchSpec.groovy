package server

import spock.lang.Shared
import support.TestChildApp
import support.ToolSpecBase

class NativeFormsDispatchSpec extends ToolSpecBase {
    @Shared private TestChildApp sharedAppStub = new TestChildApp(id: 7L, label: 'MCP')

    void setupSpec() { appExecutor.getApp() >> sharedAppStub }
    private static Map openArgs() { [app_id: 42, steps: [[kind: 'open']], confirm: true] }

    void setup() {
        settingsMap.enableMandatoryBPS = false
        script.callerClient = null
    }

    void cleanup() { script.callerClient = null }

    def 'native form is a mutating nonidempotent centrally registered continuation tool'() {
        expect:
        script.getAllToolDefinitions().find { it.name == 'hub_call_app_form' }.inputSchema.required == ['app_id', 'steps', 'confirm']
        !script.getReadOnlyToolNames().contains('hub_call_app_form')
        !script.getIdempotentWriteToolNames().contains('hub_call_app_form')
        script._mrtrWriteTools().contains('hub_call_app_form')
        script._mrtrDetachedWorkerTools().contains('hub_call_app_form')
        script.getGatewayConfig().hub_manage_native_rules_and_apps.tools.contains('hub_call_app_form')
        !script.getGatewayConfig().hub_read_rules.tools.contains('hub_call_app_form')
        script._guideSectionForTool('hub_call_app_form') == 'native_app_forms'
        script.getToolGuideSections().native_app_forms.contains('Even open renders execute app code')
    }

    def 'flat and gateway forms calls dispatch the same closed arguments'() {
        given:
        List calls = []
        script.metaClass.toolCallAppForm = { Map args -> calls << args; [success: true, app_id: args.app_id] }
        when:
        def direct = script.executeTool('hub_call_app_form', openArgs())
        def gateway = script.handleGateway('hub_manage_native_rules_and_apps', 'hub_call_app_form', openArgs())
        then:
        direct.success
        gateway.success
        calls == [openArgs(), openArgs()]
    }

    def 'write master and guide acknowledgment block open before native dispatch'() {
        given:
        int calls = 0
        script.metaClass.toolCallAppForm = { Map args -> calls++; [success: true] }
        settingsMap.enableWrite = false
        when:
        script.executeTool('hub_call_app_form', openArgs())
        then:
        thrown(IllegalArgumentException)
        calls == 0
        when:
        settingsMap.enableWrite = true
        settingsMap.enableMandatoryBPS = true
        script.executeTool('hub_call_app_form', openArgs())
        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('native_app_forms')
        calls == 0
        when:
        def result = script.executeTool('hub_call_app_form', openArgs() + [bestPracticeKey: script.hubBpsGuideKey('native_app_forms')])
        then:
        result.success
        calls == 1
    }

    def 'open requires confirmation and a recent backup before form access'() {
        when:
        script.executeTool('hub_call_app_form', openArgs() + [confirm: false])
        then:
        thrown(IllegalArgumentException)
        when:
        stateMap.remove('lastBackupTimestamp')
        script.executeTool('hub_call_app_form', openArgs())
        then:
        thrown(IllegalArgumentException)
    }

    def 'client grant and self target checks include native app_id before dispatch'() {
        given:
        stateMap.clientTokens = ['1': [label: 'Scoped', token: 'client']]
        script.callerClient = [id: '1', label: 'Scoped']
        settingsMap.clientTools_1 = []
        expect:
        script._clientToolDenial('hub_call_app_form', openArgs())
        when:
        settingsMap.clientTools_1 = ['hub_call_app_form']
        then:
        !script._clientToolDenial('hub_call_app_form', openArgs())
        script._clientToolDenial('hub_call_app_form', openArgs() + [app_id: 7]).contains('cannot target the MCP Rule Server app itself')
    }
}
