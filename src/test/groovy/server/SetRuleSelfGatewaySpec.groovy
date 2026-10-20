package server

import support.ToolSpecBase

class SetRuleSelfGatewaySpec extends ToolSpecBase {
    def "one native rule tool replaces the retired public document and form tools"() {
        given:
        settingsMap.enableWrite = true
        settingsMap.useGateways = false
        def retired = ['hub_get_rule_document', 'hub_create_rule_document', 'hub_update_rule_document', 'hub_call_app_form']
        expect:
        script.getAllToolDefinitions().count { it.name == 'hub_set_rule' } == 1
        script.getToolDefinitions().count { it.name == 'hub_set_rule' } == 1
        script.handleGateway('hub_manage_rule_machine', null, [:]).tools.count { it.name == 'hub_set_rule' } == 1
        script.getToolDisplayMeta().containsKey('hub_set_rule')
        !script.getAllToolDefinitions().any { it.name in retired }
    }

    def "unsupported operation never invokes a document writer"() {
        given:
        boolean invoked = false
        script.metaClass.toolUpdateRuleDocument = { Map args -> invoked = true; [success: true] }
        when:
        script.executeTool('hub_set_rule', [operation: 'addAction'])
        then:
        thrown(IllegalArgumentException)
        !invoked
    }

    def "omitting operation returns help without invoking native code"() {
        when:
        def help = script.executeTool('hub_set_rule', [:])
        then:
        help.operations == ['read', 'create', 'update', 'form']
    }
}
