package server

import spock.lang.Shared
import support.TestChildApp
import support.ToolSpecBase

class NativeFormsDispatchSpec extends ToolSpecBase {
    @Shared private TestChildApp sharedAppStub = new TestChildApp(id: 7L, label: 'MCP')

    void setupSpec() { appExecutor.getApp() >> sharedAppStub }
    private static Map openArgs() { [operation: 'form', rule_id: 42, args: [steps: [[kind: 'open']]], confirm: true] }

    void setup() {
        settingsMap.enableMandatoryBPS = false
        script.callerClient = null
        script.metaClass.getAppByAppId = { Long id -> [id: id, appType: [namespace: 'hubitat', name: 'Rule-5.1']] }
    }

    void cleanup() { script.callerClient = null }

    def 'native form is a mutating nonidempotent centrally registered continuation tool'() {
        expect:
        script.getAllToolDefinitions().find { it.name == 'hub_set_rule' }.inputSchema.properties.operation.enum == ['read', 'create', 'update', 'form']
        script._ndOperationSchema('form').properties.steps.items.properties.activate.type == 'string'
        !script.getReadOnlyToolNames().contains('hub_set_rule')
        !script.getIdempotentWriteToolNames().contains('hub_set_rule')
        script._mrtrWriteTools().contains('hub_set_rule')
        script._mrtrDetachedWorkerTools().contains('hub_set_rule')
        script.getGatewayConfig().hub_manage_native_rules_and_apps.tools.contains('hub_set_rule')
        !script.getGatewayConfig().hub_read_rules.tools.contains('hub_set_rule')
        script._guideSectionForTool('hub_set_rule') == 'set_rule_reference'
        script.getToolGuideSections().set_rule_reference.contains('Even open renders execute app code')
    }

    def 'flat and gateway forms calls dispatch the same closed arguments'() {
        given:
        List calls = []
        script.metaClass.toolCallAppForm = { Map args -> calls << args; [success: true, app_id: args.app_id] }
        when:
        def direct = script.executeTool('hub_set_rule', openArgs())
        def gateway = script.handleGateway('hub_manage_native_rules_and_apps', 'hub_set_rule', openArgs())
        then:
        direct.success
        gateway.success
        calls == [[app_id: 42, steps: [[kind: 'open']], confirm: true], [app_id: 42, steps: [[kind: 'open']], confirm: true]]
    }

    def 'write master and guide acknowledgment block open before native dispatch'() {
        given:
        int calls = 0
        script.metaClass.toolCallAppForm = { Map args -> calls++; [success: true] }
        settingsMap.enableWrite = false
        when:
        script.executeTool('hub_set_rule', openArgs())
        then:
        thrown(IllegalArgumentException)
        calls == 0
        when:
        settingsMap.enableWrite = true
        settingsMap.enableMandatoryBPS = true
        script.executeTool('hub_set_rule', openArgs())
        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('set_rule_reference')
        calls == 0
        when:
        def result = script.executeTool('hub_set_rule', openArgs() + [bestPracticeKey: script.hubBpsGuideKey('set_rule_reference')])
        then:
        result.success
        calls == 1
    }

    def 'open schema does not require a backup but confirmed access does'() {
        when:
        def probe = script.executeTool('hub_set_rule', openArgs() + [confirm: false])
        then:
        probe.operation == 'form'
        probe.confirm_required
        when:
        stateMap.remove('lastBackupTimestamp')
        script.executeTool('hub_set_rule', openArgs())
        then:
        thrown(IllegalArgumentException)
    }

    def 'client grant and self target checks include rule_id before dispatch'() {
        given:
        stateMap.clientTokens = ['1': [label: 'Scoped', token: 'client']]
        script.callerClient = [id: '1', label: 'Scoped']
        settingsMap.clientTools_1 = []
        expect:
        script._clientToolDenial('hub_set_rule', openArgs())
        when:
        settingsMap.clientTools_1 = ['hub_set_rule']
        then:
        !script._clientToolDenial('hub_set_rule', openArgs())
        script._clientToolDenial('hub_set_rule', openArgs() + [rule_id: 7]).contains('cannot target the MCP Rule Server app itself')
    }
}
