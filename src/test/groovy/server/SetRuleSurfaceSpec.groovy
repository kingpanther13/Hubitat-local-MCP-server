package server

import support.ToolSpecBase

class SetRuleSurfaceSpec extends ToolSpecBase {
    def 'only one native rule document and form tool is advertised'() {
        expect:
        script._getAllToolDefinitions_partNativeDocuments()*.name == ['hub_set_rule']
        script._getAllToolDefinitions_partNativeDocuments()[0].inputSchema.properties.bestPracticeKey.type == 'string'
        !script.getAllToolDefinitions().any { it.name.startsWith('hub_call_app_form') }
    }

    def 'help and unconfirmed writes return compact schemas without accessing native apps'() {
        given:
        script.metaClass.getAppByAppId = { Long id -> throw new AssertionError('unexpected native access') }
        expect:
        script.toolSetRule([:]).operations == ['read', 'create', 'update', 'form']
        ['create', 'update', 'form'].every { op ->
            def result = script.toolSetRule([operation: op])
            result.operation == op && result.inputSchema.properties.bestPracticeKey.type == 'string' && result.confirm_required == true
        }
    }

    def 'read and form dispatch map top level identity into private helpers'() {
        given:
        script.metaClass.toolGetRuleDocument = { Map args -> args }
        script.metaClass.toolCallAppForm = { Map args -> args }
        script.metaClass.getAppByAppId = { Long id -> [id: id, appType: [namespace: 'hubitat', name: ruleType]] }
        expect:
        script.toolSetRule([operation: 'read', rule_id: 42, args: [sections: ['actions']]]) == [rule_id: 42, sections: ['actions']]
        script.toolSetRule([operation: 'form', rule_id: 42, args: [steps: [[kind: 'open']]], confirm: true]) == [app_id: 42, steps: [[kind: 'open']], confirm: true]
        where:
        ruleType << ['Rule-5.1', 'Button Rule-5.1']
    }

    def 'create and update retain their private operation arguments'() {
        given:
        script.metaClass.toolCreateRuleDocument = { Map args -> args }
        script.metaClass.toolUpdateRuleDocument = { Map args -> args }
        def changes = [[part: 'variable', value: [operation: 'set', name: 'counter', value: 0]]]
        expect:
        script.toolSetRule([operation: 'create', args: [parent_id: 28, name: 'Example', changes: []], confirm: true]) == [parent_id: 28, name: 'Example', changes: [], confirm: true]
        script.toolSetRule([operation: 'update', rule_id: 42, args: [changes: changes], confirm: true]) == [rule_id: 42, changes: changes, confirm: true]
    }

    def 'unknown operations and nested authority overrides are rejected'() {
        when:
        script.toolSetRule(request)
        then:
        thrown(IllegalArgumentException)
        where:
        request << [[operation: 'delete'], [operation: 'read', rule_id: 42, args: [rule_id: 99]],
                    [operation: 'update', args: [confirm: true]], [operation: 'form', args: [app_id: 99]],
                    [operation: 'read', extra: true], [operation: 'form', args: [unknown: true]],
                    [operation: 'update', confirm: 'true'], [operation: 'read', rule_id: 0],
                    [operation: 'read', rule_id: true], [operation: 'read', rule_id: 1.5],
                    [operation: 'create', args: [parent_id: false]],
                    [operation: 'create', args: [parent_id: 2.5]],
                    [operation: 'update', args: [changes: 'not an array']],
                    [operation: 'form', args: [steps: [[kind: 'unknown']]]]]
    }

    def 'read shaped classification includes discovery but excludes confirmed writes'() {
        expect:
        script._isRuleReadCall('hub_set_rule', [:])
        script._isRuleReadCall('hub_set_rule', [operation: 'read', confirm: true])
        script._isRuleReadCall('hub_set_rule', [operation: 'form', confirm: false])
        !script._isRuleReadCall('hub_set_rule', [operation: 'form', confirm: true])
        !script._isRuleReadCall('hub_set_rule_paused', [:])
        !script._isRuleReadCall('hub_set_rule', 'invalid arguments')
        !script._isRuleReadCall('hub_set_rule', null)

        and: 'hub_set_native_app is a write tool in every call shape; its help lives in the read-only guide'
        !script._isRuleReadCall('hub_set_native_app', [:])
        !script._isRuleReadCall('hub_set_native_app', [operation: 'update', app_id: 5])
    }

    def 'form rejects unrelated native apps before executing helper'() {
        given:
        script.metaClass.getAppByAppId = { Long id -> [id: id, appType: [namespace: 'hubitat', name: 'Motion Lighting']] }
        script.metaClass.toolCallAppForm = { Map args -> throw new AssertionError('unexpected native form') }
        when:
        script.toolSetRule([operation: 'form', rule_id: 42, args: [steps: [[kind: 'open']]], confirm: true])
        then:
        thrown(IllegalArgumentException)
    }
}
