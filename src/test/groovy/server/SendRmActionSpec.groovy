package server

import support.RMUtilsMock
import support.ToolSpecBase

/**
 * sendRmAction (libraries/mcp-native-rules-lib.groovy), behind hub_set_rule_private_boolean,
 * makes exactly one 4-arg RMUtils.sendAction call and turns any Throwable into a
 * {success:false} result: a retry could double-fire an action the first call already dispatched.
 */
class SendRmActionSpec extends ToolSpecBase {

    RMUtilsMock rmUtils

    def setup() {
        rmUtils = new RMUtilsMock()
        rmUtils.install()
        script.metaClass.getAppByAppId = { Long id -> [id: id, appType: [namespace: 'hubitat', name: 'Rule-5.1']] }
    }

    def cleanup() {
        rmUtils?.uninstall()
    }

    def "a successful dispatch is the single 4-arg call"() {
        when:
        def result = script.toolSetRmRuleBoolean([ruleId: 1, value: true])

        then:
        result.success == true
        result.ruleId == 1
        result.rmAction == 'setRuleBooleanTrue'
        !result.containsKey('fallback')
        def sendCalls = rmUtils.calls.findAll { it.method == 'sendAction' }
        sendCalls.size() == 1
        sendCalls[0].version == '5.0'
    }

    def "a multi-id dispatch carries the whole list and no single-id echo"() {
        when:
        def result = script.toolSetRmRuleBoolean([ruleId: [2, 3], value: false])

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
        def result = script.toolSetRmRuleBoolean([ruleId: 4, value: true])

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

    @spock.lang.Unroll
    def "a load-limiter refusal says nothing changed and that a reset then a retry clears it (#label)"() {
        given:
        hubitat.helper.RMUtils.metaClass.static.sendAction = { List targets, String action, String appLabel, String version ->
            throw new RuntimeException('App generates excessive hub load')
        }

        when:
        def result = script.toolSetRmRuleBoolean([ruleId: ids, value: true])

        then:
        result.success == false
        result.note.contains('load limiter refused the dispatch, so nothing was changed')
        result.note.contains("hub_delete_logs(mode='appRuntimeStats', confirm=true)")
        result.note.contains('retry')
        !result.note.contains('Verify the ruleId')
        !result.note.contains('LIST dispatch')

        where:
        label         | ids
        'a single id' | 5
        'a batch'     | [5, 6]
    }

    def "any other failure of a batch dispatch warns that part of the set may already be set"() {
        given:
        hubitat.helper.RMUtils.metaClass.static.sendAction = { List targets, String action, String appLabel, String version ->
            throw new RuntimeException('timeout')
        }

        when:
        def result = script.toolSetRmRuleBoolean([ruleId: [7, 8], value: true])

        then:
        result.success == false
        result.note.contains('Verify the ruleId')
        result.note.contains('LIST dispatch (7, 8)')
        result.note.contains('hub_get_rule')
    }
}
