package server

import support.ToolSpecBase

class SetRuleSelfGatewaySpec extends ToolSpecBase {
    def "retired writer is absent from flat catalog and native gateway disclosure"() {
        given:
        settingsMap.enableWrite = true
        settingsMap.useGateways = false

        expect:
        !script.getAllToolDefinitions().any { it.name == 'hub_set_rule' }
        !script.getToolDefinitions().any { it.name == 'hub_set_rule' }
        !script.handleGateway('hub_manage_rule_machine', null, [:]).tools.any { it.name == 'hub_set_rule' }
        !script.getToolDisplayMeta().containsKey('hub_set_rule')
    }

    def "retired writer has no public execution or schema probe alias"() {
        given:
        settingsMap.enableWrite = true
        boolean invoked = false
        script.metaClass.toolUpdateRuleDocument = { Map args -> invoked = true; [success: true] }

        when:
        script.executeTool('hub_set_rule', [operation: 'addAction'])

        then:
        thrown(IllegalArgumentException)
        !invoked
    }

    def "native authoring is disclosed through documents and form calls"() {
        when:
        def names = script.getAllToolDefinitions()*.name

        then:
        names.containsAll(['hub_get_rule_document', 'hub_create_rule_document', 'hub_update_rule_document', 'hub_call_app_form'])
        !names.contains('hub_set_rule')
    }
}
