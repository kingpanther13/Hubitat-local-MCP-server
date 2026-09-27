package server

import support.ToolSpecBase

/**
 * Guards for the RM gateway-description trim + the Boy Scout fixes:
 *  - the two RM gateway descriptions stay lean but keep the cross-gateway routing
 *    pointer (the selection-critical signal) and drop the verbose deep-reference prose;
 *  - hub_set_native_app's appType param documents the create-vs-edit limitation;
 *  - hub_set_app_disabled's param is appId (the app_id -> appId rename).
 */
class RmGatewayDescTrimSpec extends ToolSpecBase {

    private Map gw(String name) { script.getGatewayConfig()[name] }
    private Map toolDef(String name) { script.getAllToolDefinitions().find { it.name == name } }

    def "hub_manage_native_rules_and_apps description is lean but keeps the routing pointer; drops the verbose prose"() {
        when:
        def desc = gw('hub_manage_native_rules_and_apps').description as String

        then: 'lean (was ~1880 chars)'
        desc.length() < 900

        and: 'keeps the selection-critical routing pointer to the RM rule-authoring gateway'
        desc.contains('hub_manage_rule_machine')
        desc.contains('hub_set_rule')

        and: 'still names what it does + the not-custom disambiguation + the snapshot/confirm + async-verify nugget'
        desc.toLowerCase().contains('classic')
        desc.contains('custom_*')
        desc.contains('confirm=true')
        desc.contains('hub_get_app_config')

        and: 'the verbose deep-reference prose was dropped'
        !desc.contains('Verification protocol')
        !desc.contains('Reads require the Read master')
        !desc.contains('Two surfaces')
    }

    def "hub_manage_rule_machine description keeps its routing pointer to the native-apps gateway"() {
        when:
        def desc = gw('hub_manage_rule_machine').description as String

        then:
        desc.length() < 900
        desc.contains('hub_manage_native_rules_and_apps')
        desc.contains('hub_set_rule')
        desc.toLowerCase().contains('visual rules builder')
    }

    def "hub_set_native_app appType lists the create-capable types and points other apps at their parent page"() {
        when:
        def appType = toolDef('hub_set_native_app').inputSchema.properties.appType

        then: 'apps without an appType are created through their parent app page (Room Lighting newScene)'
        (appType.description as String).contains("parent app's own page")
        (appType.description as String).contains('newScene')
        !(appType.description as String).contains('EDIT/DELETE-only')

        and: 'enum is exactly the create-capable classic types, room_lighting included (issue #461)'
        appType.enum == ['rule_machine', 'button_controller', 'groups_scenes', 'notifier', 'basic_rule', 'room_lighting']
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
