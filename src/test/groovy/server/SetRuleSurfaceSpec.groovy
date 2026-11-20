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
        script.metaClass.toolCallAppForm = { Map args, String toolName -> args + [toolName: toolName] }
        script.metaClass.getAppByAppId = { Long id -> [id: id, appType: [namespace: 'hubitat', name: ruleType]] }
        expect:
        script.toolSetRule([operation: 'read', ruleId: 42, args: [sections: ['actions']]]) == [ruleId: 42, sections: ['actions']]
        script.toolSetRule([operation: 'form', ruleId: 42, args: [steps: [[kind: 'open']]], confirm: true]) == [ruleId: 42, steps: [[kind: 'open']], confirm: true, toolName: 'hub_set_rule']
        where:
        ruleType << ['Rule-5.1', 'Button Rule-5.1']
    }

    def 'create and update retain their private operation arguments'() {
        given:
        script.metaClass.toolCreateRuleDocument = { Map args -> args }
        script.metaClass.toolUpdateRuleDocument = { Map args -> args }
        def changes = [[part: 'variable', value: [operation: 'set', name: 'counter', value: 0]]]
        expect:
        script.toolSetRule([operation: 'create', args: [parentId: 28, name: 'Example', changes: []], confirm: true]) == [parentId: 28, name: 'Example', changes: [], confirm: true]
        script.toolSetRule([operation: 'update', ruleId: 42, args: [changes: changes], confirm: true]) == [ruleId: 42, changes: changes, confirm: true]
    }

    def 'unknown operations and nested authority overrides are rejected'() {
        when:
        script.toolSetRule(request)
        then:
        thrown(IllegalArgumentException)
        where:
        request << [[operation: 'delete'], [operation: 'read', ruleId: 42, args: [ruleId: 99]],
                    [operation: 'update', args: [confirm: true]], [operation: 'form', args: [app_id: 99]],
                    [operation: 'read', extra: true], [operation: 'form', args: [unknown: true]],
                    [operation: 'update', confirm: 'true'], [operation: 'read', ruleId: 0],
                    [operation: 'read', ruleId: true], [operation: 'read', ruleId: 1.5],
                    [operation: 'create', args: [parentId: false]],
                    [operation: 'create', args: [parentId: 2.5]],
                    [operation: 'update', args: [changes: 'not an array']],
                    [operation: 'form', args: [steps: [[kind: 'unknown']]]]]
    }

    def 'the old snake_case keys are refused by name before anything runs'() {
        given:
        settingsMap.enableMandatoryBPS = false
        script.metaClass.getAppByAppId = { Long id -> throw new AssertionError('unexpected native access') }
        when:
        script.executeTool('hub_set_rule', request)
        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains(named)
        where:
        request                                                                                          || named
        [operation: 'read', rule_id: 42, args: [:]]                                                      || 'Unknown hub_set_rule argument: rule_id'
        [operation: 'create', args: [parent_id: 28, name: 'Example', changes: []]]                       || 'Unknown args property: parent_id'
        [operation: 'create', confirm: true, args: [parent_id: 28, name: 'Example']]                     || 'Unknown args property: parent_id'
        [operation: 'form', ruleId: 42, args: [steps: [[kind: 'open']], options_for: ['devices']]]       || 'Unknown args property: options_for'
        [operation: 'form', ruleId: 42, args: [steps: [[kind: 'submit', expect_page: 'mainPage']]]]      || 'Unknown args.steps property: expect_page'
    }

    def 'read shaped classification includes discovery but excludes confirmed writes'() {
        expect:
        script._isRuleReadCall('hub_set_rule', [:])
        script._isRuleReadCall('hub_set_rule', [operation: 'read', confirm: true])
        script._isRuleReadCall('hub_set_rule', [operation: 'form', confirm: false])
        !script._isRuleReadCall('hub_set_rule', [operation: 'form', confirm: true])
        !script._isRuleReadCall('hub_call_rule', [:])
        !script._isRuleReadCall('hub_set_rule', 'invalid arguments')
        !script._isRuleReadCall('hub_set_rule', null)

        and: 'hub_set_native_app is a write tool in every call shape; its help lives in the read-only guide'
        !script._isRuleReadCall('hub_set_native_app', [:])
        !script._isRuleReadCall('hub_set_native_app', [operation: 'update', app_id: 5])
    }

    def 'form rejects unrelated native apps before executing helper'() {
        given:
        script.metaClass.getAppByAppId = { Long id -> [id: id, appType: [namespace: 'hubitat', name: 'Motion Lighting']] }
        script.metaClass.toolCallAppForm = { Map args, String toolName -> throw new AssertionError('unexpected native form') }
        when:
        script.toolSetRule([operation: 'form', ruleId: 42, args: [steps: [[kind: 'open']]], confirm: true])
        then:
        thrown(IllegalArgumentException)
    }
}
