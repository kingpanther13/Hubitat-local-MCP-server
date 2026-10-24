package server

import support.RMUtilsMock
import support.ToolSpecBase

/**
 * sendRmAction (libraries/mcp-native-rules-lib.groovy) makes exactly one 4-arg
 * RMUtils.sendAction call and turns any Throwable into a {success:false} result:
 * a retry could double-fire an action the first call already dispatched.
 */
class SendRmActionSpec extends ToolSpecBase {

    RMUtilsMock rmUtils

    def setup() {
        rmUtils = new RMUtilsMock()
        rmUtils.install()
    }

    def cleanup() {
        rmUtils?.uninstall()
    }

    def "a successful dispatch is the single 4-arg call"() {
        when:
        def result = script.toolRunRmRule([ruleId: 1, action: 'rule'])

        then:
        result.success == true
        result.ruleId == 1
        result.rmAction == 'runRule'
        !result.containsKey('fallback')
        def sendCalls = rmUtils.calls.findAll { it.method == 'sendAction' }
        sendCalls.size() == 1
        sendCalls[0].version == '5.0'
    }

    def "a multi-id dispatch carries the whole list and no single-id echo"() {
        when:
        def result = script.toolRunRmRule([ruleId: [2, 3], action: 'rule'])

        then:
        result.success == true
        result.ruleIds == [2, 3]
        result.ruleId == null
        rmUtils.calls.findAll { it.method == 'sendAction' }*.ruleIds == [[2, 3]]
    }

    def "any failure of the 4-arg call is reported once and never retried with the 3-arg form: #label"() {
        given:
        int threeArgCalls = 0
        hubitat.helper.RMUtils.metaClass.static.sendAction = { List ids, String action, String appLabel, String version -> throw failure }
        hubitat.helper.RMUtils.metaClass.static.sendAction = { List ids, String action, String appLabel -> threeArgCalls++ }

        when:
        def result = script.toolRunRmRule([ruleId: 4, action: 'rule'])

        then:
        result.success == false
        result.error.startsWith('RMUtils.sendAction failed: ')
        result.note.contains('hub_list_rules')
        threeArgCalls == 0

        where:
        label                     | failure
        'MissingMethodException'  | new MissingMethodException('sendAction', hubitat.helper.RMUtils, [] as Object[])
        'NoSuchMethodError'       | new NoSuchMethodError('sendAction')
        'RuntimeException'        | new RuntimeException('timeout')
        'NoClassDefFoundError'    | new NoClassDefFoundError('hubitat/helper/RMUtils')
    }
}
