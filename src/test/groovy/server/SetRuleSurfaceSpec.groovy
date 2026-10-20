package server

import support.ToolSpecBase

class SetRuleSurfaceSpec extends ToolSpecBase {
    def 'only one native rule document and form tool is advertised'() {
        expect:
        script._getAllToolDefinitions_partNativeDocuments()*.name == ['hub_set_rule']
        script._getAllToolDefinitions_partNativeForms().empty
        script._toolDisplayMeta_partNativeForms().empty
    }

    def 'help and unconfirmed writes return compact schemas without accessing native apps'() {
        given:
        script.metaClass.getAppByAppId = { Long id -> throw new AssertionError('unexpected native access') }
        expect:
        script.toolSetRule([:]).operations == ['read', 'create', 'update', 'form']
        ['create', 'update', 'form'].every { op ->
            def result = script.toolSetRule([operation: op])
            result.operation == op && result.inputSchema.properties && result.confirm_required == true
        }
    }

    def 'read and form dispatch map top level identity into private helpers'() {
        given:
        script.metaClass.toolGetRuleDocument = { Map args -> args }
        script.metaClass.toolCallAppForm = { Map args -> args }
        script.metaClass.getAppByAppId = { Long id -> [id: id, appType: [namespace: 'hubitat', name: 'Rule-5.1']] }
        expect:
        script.toolSetRule([operation: 'read', rule_id: 42, args: [sections: ['actions']]]) == [rule_id: 42, sections: ['actions']]
        script.toolSetRule([operation: 'form', rule_id: 42, args: [steps: [[kind: 'open']]], confirm: true]) == [app_id: 42, steps: [[kind: 'open']], confirm: true]
    }

    def 'unknown operations and nested authority overrides are rejected'() {
        when:
        script.toolSetRule(request)
        then:
        thrown(IllegalArgumentException)
        where:
        request << [[operation: 'delete'], [operation: 'read', rule_id: 42, args: [rule_id: 99]],
                    [operation: 'update', args: [confirm: true]], [operation: 'form', args: [app_id: 99]],
                    [operation: 'read', extra: true], [operation: 'form', args: [unknown: true]]]
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
