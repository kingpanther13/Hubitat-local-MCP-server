package server

import support.ToolSpecBase

/**
 * Guards for the RM gateway-description trim + the Boy Scout fixes:
 *  - the two RM gateway descriptions stay lean but keep the rule-operation routing
 *    contract (the selection-critical signal) and drop the verbose deep-reference prose;
 *  - hub_set_app_disabled's param is appId (the app_id -> appId rename).
 */
class RmGatewayDescTrimSpec extends ToolSpecBase {

    private Map gw(String name) { script.getGatewayConfig()[name] }
    private Map toolDef(String name) { script.getAllToolDefinitions().find { it.name == name } }

    def "hub_manage_native_rules_and_apps description keeps unified rule routing and lifecycle guidance"() {
        when:
        def desc = gw('hub_manage_native_rules_and_apps').description as String

        then: 'lean (was ~1880 chars)'
        desc.length() < 900

        and: 'routes native RM operations through the unified rule tool'
        desc.contains('read/create/update/form')
        desc.contains('hub_set_rule')

        and: 'keeps lifecycle, backup, partial-outcome and native-health guidance'
        desc.contains('delete/clone/export/import')
        desc.contains('Confirmed writes')
        desc.contains('recent hub backup')
        desc.contains('partial outcomes')
        desc.contains('native health')

        and: 'the verbose deep-reference prose was dropped'
        !desc.contains('Verification protocol')
        !desc.contains('Reads require the Read master')
        !desc.contains('Two surfaces')
    }

    def "hub_manage_rule_machine description distinguishes operation help schemas and rule diagnostics"() {
        when:
        def desc = gw('hub_manage_rule_machine').description as String

        then:
        desc.length() < 900
        desc.contains('read/create/update/form')
        desc.contains('without operation for help')
        desc.contains('without confirm for a write-operation schema')
        desc.contains('hub_delete_native_app')
        desc.contains('hub_read_rules')
        desc.contains('hub_set_rule')
        desc.toLowerCase().contains('visual rules builder')
    }

    def "hub_set_app_disabled uses appId (app_id -> appId rename)"() {
        when:
        def schema = toolDef('hub_set_app_disabled').inputSchema

        then:
        schema.properties.containsKey('appId')
        !schema.properties.containsKey('app_id')
        schema.required == ['appId', 'disabled']
    }
}
