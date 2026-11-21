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
            result.operation == op && result.inputSchema.properties.bestPracticeKey.type == 'string' && result.confirmRequired == true
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
        def refused = thrown(IllegalArgumentException)
        refused.message == 'App 42 is a Motion Lighting app; only Rule Machine 5.1 rules and Button Rules can be edited here.'
    }

    def 'form results spell their keys in camelCase while values and input names stay as the hub sends them'() {
        given:
        script.metaClass.getAppByAppId = { Long id -> [id: id, appType: [namespace: 'hubitat', name: 'Rule-5.1']] }
        script.metaClass.toolCallAppForm = { Map args, String toolName ->
            [success: false, ruleId: 42, reopen_required: true, remaining_steps: 1, navigated_app_id: 43, navigated_page: 'mainPage',
             created_app_id: 44, defaults_posted: ['use_st'], label_restored: true, fields_not_landed: ['label'],
             completed: [[kind: 'submit', navigation_ended: true]],
             fields: [[name: 'act_type.1', submit_on_change: true, value_source: 'stored', has_value: true, options_available: true, value: [some_key: 'x']]],
             controls: [[control_id: 'N||doAct', kind: 'create_child', submit_on_change: false]]]
        }
        when:
        def result = script.toolSetRule([operation: 'form', ruleId: 42, args: [steps: [[kind: 'open']]], confirm: true])
        then:
        result.keySet() == ['success', 'ruleId', 'reopenRequired', 'remainingSteps', 'navigatedAppId', 'navigatedPage', 'createdAppId',
                            'defaultsPosted', 'labelRestored', 'fieldsNotLanded', 'completed', 'fields', 'controls'] as Set
        result.defaultsPosted == ['use_st']
        result.completed == [[kind: 'submit', navigationEnded: true]]
        result.fields == [[name: 'act_type.1', submitOnChange: true, valueSource: 'stored', hasValue: true, optionsAvailable: true, value: [some_key: 'x']]]
        result.controls == [[controlId: 'N||doAct', kind: 'create_child', submitOnChange: false]]
    }

    def 'form names the control id key of the tool that was called'() {
        given:
        Map context = [envelope: [:], fields: [:], controls: [:]]
        when:
        script._nfPrepare(42L, context, [:], 'missing', toolName)
        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == "Choose one exact returned native ${key}".toString()
        where:
        toolName             | key
        'hub_set_rule'       | 'controlId'
        'hub_set_native_app' | 'control_id'
    }

    def 'form opens the Rule Machine parent app'() {
        given:
        script.metaClass.getAppByAppId = { Long id -> [id: id, appType: [namespace: 'hubitat', name: 'Rule Machine']] }
        script.metaClass.toolCallAppForm = { Map args, String toolName -> [opened: args.ruleId] }
        expect:
        script.toolSetRule([operation: 'form', ruleId: 28, args: [steps: [[kind: 'open']]], confirm: true]) == [opened: 28]
    }

    def 'every operation words its refusals as hub_get_rule does: #label'() {
        given:
        script.metaClass.getAppByAppId = { Long id -> app == null ? null : [id: id, parentAppId: 28L, appType: [namespace: namespace, name: app]] }
        script.metaClass.toolCallAppForm = { Map args, String toolName -> throw new AssertionError('unexpected native form') }
        when:
        script.toolSetRule(request + [ruleId: 42])
        then:
        def refused = thrown(IllegalArgumentException)
        refused.message.startsWith(expected)
        where:
        label                       | request                                                                         | app                        | namespace || expected
        'read, no app'              | [operation: 'read']                                                             | null                       | 'hubitat' || "No app with id 42 is installed. Find rule ids with hub_list_rules (Rule Machine), hub_list_apps (Button Rules and Basic Rules) or hub_get_visual_rule (Visual Rules)"
        'form, no app'              | [operation: 'form', confirm: true, args: [steps: [[kind: 'open']]]]             | null                       | 'hubitat' || "No app with id 42 is installed"
        'read, a non-rule app'      | [operation: 'read']                                                             | 'Motion Lighting'          | 'hubitat' || 'App 42 is a Motion Lighting app; only Rule Machine 5.1 rules and Button Rules can be edited here'
        'read, a Visual Rule'       | [operation: 'read']                                                             | 'Visual Rule Builder 2.0'  | 'hubitat' || 'App 42 is a Visual Rule Builder 2.0 rule: Visual Rules are read with hub_get_visual_rule, but only Rule Machine 5.1 rules and Button Rules can be edited here'
        'form, another rule type'   | [operation: 'form', confirm: true, args: [steps: [[kind: 'open']]]]             | 'Rule-6.0'                 | 'hubitat' || 'App 42 is a Rule-6.0 rule: rules of that type are read with hub_get_rule, but only Rule Machine 5.1 rules and Button Rules can be edited here'
        'read, a community Rule-5.1'| [operation: 'read']                                                             | 'Rule-5.1'                 | 'acme'    || 'App 42 is a Rule-5.1 app'+"; only Rule Machine 5.1 rules and Button Rules can be edited here"
    }

    def 'hub_set_rule refuses ruleId #ruleId (#operation) before any lookup, as hub_call_rule does'() {
        given:
        int lookups = 0
        script.metaClass.getAppByAppId = { Long id -> lookups++; null }
        when:
        script.toolSetRule([operation: operation, ruleId: ruleId, confirm: true, args: args])
        then:
        def refused = thrown(IllegalArgumentException)
        refused.message.startsWith('A positive native application ID is required, got: ')
        lookups == 0
        where:
        operation | ruleId        | args
        'read'    | 4294967338L   | [:]
        'read'    | '4294967338'  | [:]
        'read'    | 42.5          | [:]
        'read'    | 0             | [:]
        'update'  | 4294967338L   | [changes: [[delete: [part: 'action', id: '1']]]]
        'form'    | 4294967338L   | [steps: [[kind: 'open']]]
    }

    def 'hub_set_rule takes ruleId #ruleId as the id it names'() {
        given:
        List looked = []
        script.metaClass.getAppByAppId = { Long id -> looked << id; null }
        when:
        script.toolSetRule([operation: 'read', ruleId: ruleId])
        then: 'the lookup is for rule 42, which has no app here'
        def refused = thrown(IllegalArgumentException)
        refused.message.startsWith('No app with id 42 is installed')
        looked == [42L]
        where:
        ruleId << [42, '42', 42.0]
    }

    def '_rmIsEditableRuleType names the Rule Machine 5.1 rule and Button Rule types in the hubitat namespace'() {
        expect:
        script._rmIsEditableRuleType(namespace, name) == expected
        where:
        name              | namespace || expected
        'Rule-5.1'        | 'hubitat' || true
        'Button Rule-5.1' | 'hubitat' || true
        'Rule Machine'    | 'hubitat' || false
        'Rule-5.1'        | 'acme'    || false
        null              | 'hubitat' || false
        'Rule-5.1'        | null      || false
    }

    def 'the rule-type predicates need the hubitat namespace: a community type named like a rule is no rule'() {
        expect:
        script._rmIsRuleAppType(namespace, name) == rule
        script._rmIsVisualRuleType(namespace, name) == visual
        where:
        name                      | namespace || rule  | visual
        'Rule-5.1'                | 'hubitat' || true  | false
        'Rule-5.1'                | 'acme'    || false | false
        'Rule-5.1'                | null      || false | false
        'Visual Rule Builder 2.0' | 'hubitat' || false | true
        'Visual Rule Builder 2.0' | 'acme'    || false | false
    }
}
