package server

import groovy.json.JsonOutput
import support.ToolSpecBase

class NativeDocumentsSpec extends ToolSpecBase {
    List events
    Map nativeState
    boolean failAction
    boolean broken
    Map lifecycle

    void setup() {
        events = []
        failAction = false
        broken = false
        lifecycle = [stopped: false, paused: false]
        nativeState = [actionList: ['3', '1'], actions: ['3': [method: 'getLogMsg'], '1': [method: 'getLogMsg']],
                       capabsfalse: ['4': 'Switch is on'], capabstrue: ['5': 'Switch changed'], eval: ['0': ['4']],
                       allLocalVars: [counter: [type: 'integer', value: 0]]]
        settingsMap.enableMandatoryBPS = false
        stateMap.lastBackupTimestamp = 1234567890000L
        script.metaClass.getAppByAppId = { Long id -> id == 28L ? [id: 28L, appType: [namespace: 'hubitat', name: 'Rule Machine']] : [id: 42L, parentAppId: 28L, appType: [namespace: 'hubitat', name: 'Rule-5.1'], installed: true] }
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, int t = 30, boolean retry = false -> events << 'create'; [status: 302, location: '/installedapp/configure/42'] }
        script.metaClass._nrHandle = { Long id ->
            if (id != 42L) throw new IllegalArgumentException('missing rule')
            new NativeDocumentChild(events, lifecycle, nativeState.allLocalVars)
        }
        script.metaClass._nrState = { Long id -> [:] + nativeState }
        script.metaClass._nmUpsertAction = { Long id, Map value -> events << 'action'; [success: !failAction, partial: failAction, id: '6'] }
        script.metaClass._nmUpsertCondition = { Long id, boolean trigger, Map value -> events << (trigger ? 'trigger' : 'condition'); [success: true, id: '7'] }
        script.metaClass._nmSetExpression = { Long id, Object expressionId, List tokens -> events << 'expression'; [success: true] }
        script.metaClass._nmLocal = { Long id, String op, String name = null, Object value = null, String type = null -> events << 'variable'; [success: true] }
        script.metaClass._nmDeleteRow = { Long id, String part, Object row -> events << ('delete:' + part + ':' + row); [success: true] }
        script.metaClass._nmOrderActions = { Long id, List ids -> events << 'order'; [success: true] }
        script.metaClass._nmAddTerminator = { Long id, String subtype -> events << 'terminator'; [success: true] }
        hubGet.register('/installedapp/settings/42') { q -> JsonOutput.toJson([settings: [[name: 'flag', type: 'bool', multiple: false, value: false], [name: 'count', type: 'number', value: 0]]]) }
        hubGet.register('/app/ruleBuilderJson/42') { q -> JsonOutput.toJson([broken: broken] + lifecycle) }
        hubGet.register('/installedapp/statusJson/42') { q -> JsonOutput.toJson([eventSubscriptions: lifecycle.stopped ? [] : [[name: 'switch']], scheduledJobs: []]) }
        script.metaClass.hubInternalPostFormRaw = { String path, String body, int t = 420, boolean retry = false -> events << [path: path, body: body]; lifecycle.stopped = false; lifecycle.paused = false; [status: 200] }
    }

    def 'read document returns native stable order typed values and locals without rendering'() {
        when:
        def result = script.executeTool('hub_get_rule_document', [rule_id: 42])
        then:
        result.rule_id == 42L
        result.actions*.id == ['3', '1']
        result.settings.find { it.name == 'flag' }.value == false
        result.settings.find { it.name == 'count' }.value == 0
        result.variables.counter.value == 0
        events.empty
    }

    def 'projection suppresses unrequested native sections'() {
        when:
        def result = script.toolGetRuleDocument([rule_id: 42, sections: ['actions']])
        then:
        result.actions*.id == ['3', '1']
        !result.containsKey('settings')
        !result.containsKey('variables')
    }

    def 'unknown change fails before every mutation'() {
        when:
        script.toolUpdateRuleDocument([rule_id: 42, changes: [[part: 'action', value: [type: 'messageActs', subtype: 'getLogMsg', fields: []]], [part: 'state', value: [unsafe: true]]], confirm: true])
        then:
        thrown(IllegalArgumentException)
        events.empty
    }

    def 'failed change stops and returns truthful partial outcomes'() {
        given:
        failAction = true
        when:
        def result = script.toolUpdateRuleDocument([rule_id: 42, changes: [[part: 'action', value: [type: 'messageActs', subtype: 'getLogMsg', fields: []]], [part: 'terminator', value: [subtype: 'endIf']]], confirm: true])
        then:
        !result.success
        result.partial
        result.results.size() == 1
        events == ['action']
    }

    def 'successful update keeps ID then checks native health and subscriptions after Done'() {
        when:
        def result = script.toolUpdateRuleDocument([rule_id: 42, changes: [[part: 'action', value: [type: 'messageActs', subtype: 'getLogMsg', fields: []]]], confirm: true])
        then:
        result.success
        result.rule_id == 42L
        result.eventSubscriptions.size() == 1
        events[0..3] == ['action', 'selectTriggers', 'selectActions', 'mainPage']
        events[4].path == '/installedapp/update/json'
        events[4].body.contains('_action_update=Done')
    }

    def 'broken native health prevents finalization'() {
        given:
        broken = true
        when:
        def result = script.toolUpdateRuleDocument([rule_id: 42, changes: [[part: 'terminator', value: [subtype: 'endIf']]], confirm: true])
        then:
        !result.success
        result.partial
        result.broken
        !events.any { it instanceof Map }
    }

    def 'delete and order parts dispatch only fixed model operations'() {
        when:
        def result = script.toolUpdateRuleDocument([rule_id: 42, changes: [[part: 'delete', value: [part: 'action', id: '1']], [part: 'order', value: [ids: ['3']]]], confirm: true])
        then:
        result.success
        events[0..1] == ['delete:action:1', 'order']
    }

    def 'invalid action order is refused before any change or render'() {
        when:
        def result = script.toolUpdateRuleDocument([rule_id: 42, changes: [[part: 'order', value: [ids: ['3']]]], confirm: true])
        then:
        !result.success
        !result.partial
        events.empty
        nativeState.actionList == ['3', '1']
    }

    def 'unknown expression operands are refused before any change or render'() {
        when:
        def result = script.toolUpdateRuleDocument([rule_id: 42, changes: [[part: 'required', value: [tokens: ['999999']]]], confirm: true])
        then:
        !result.success
        !result.partial
        result.error.contains('unknown condition')
        events.empty
    }

    def 'referenced condition deletion is refused before any change or render'() {
        when:
        def result = script.toolUpdateRuleDocument([rule_id: 42, changes: [[part: 'delete', value: [part: 'condition', id: '4']]], confirm: true])
        then:
        !result.success
        !result.partial
        events.empty
        nativeState.capabsfalse.'4' == 'Switch is on'
    }

    def 'native expression with missing condition cannot pass a false broken flag'() {
        given:
        nativeState.actions.'3' = [method: 'getIfThen', rule: '1']
        nativeState.eval.'1' = ['999999']
        when:
        def result = script.toolUpdateRuleDocument([rule_id: 42, changes: [[part: 'terminator', value: [subtype: 'endIf']]], confirm: true])
        then:
        !result.success
        result.error.contains('unknown condition')
        !events.any { it instanceof Map }
    }

    def 'native unfinished IF cannot finalize despite a false broken flag'() {
        given:
        nativeState.actions.'3' = [method: 'getIfThen', rule: '0']
        nativeState.inIf = true
        when:
        def result = script.toolUpdateRuleDocument([rule_id: 42, changes: [[part: 'variable', value: [operation: 'set', name: 'counter', value: 0]]], confirm: true])
        then:
        !result.success
        result.error.contains('unfinished IF')
        !events.any { it instanceof Map }
    }

    def 'native red broken diagnostic blocks Done but a user text marker does not'() {
        given:
        script.metaClass._nrHandle = { Long id -> new NativeDocumentDiagnosticChild(events, lifecycle, true) }
        when:
        def failed = script.toolUpdateRuleDocument([rule_id: 42, changes: [[part: 'terminator', value: [subtype: 'endIf']]], confirm: true])
        then:
        !failed.success
        failed.error.contains('broken condition')
        !events.any { it instanceof Map }
        when:
        events.clear()
        script.metaClass._nrHandle = { Long id -> new NativeDocumentDiagnosticChild(events, lifecycle, false) }
        def passed = script.toolUpdateRuleDocument([rule_id: 42, changes: [[part: 'terminator', value: [subtype: 'endIf']]], confirm: true])
        then:
        passed.success
    }

    def 'Done preserves an existing stopped and paused native rule using verified toggles'() {
        given:
        lifecycle.stopped = true
        lifecycle.paused = true
        when:
        def result = script.toolUpdateRuleDocument([rule_id: 42, changes: [[part: 'terminator', value: [subtype: 'endIf']]], confirm: true])
        then:
        result.success
        result.stopped
        result.paused
        result.eventSubscriptions == []
        events.count { it == 'stopRule' } == 1
        events.count { it == 'pausRule' } == 1
    }

    def 'missing native health is unavailable and cannot finalize'() {
        given:
        hubGet.register('/app/ruleBuilderJson/42') { q -> '{}' }
        when:
        def result = script.toolUpdateRuleDocument([rule_id: 42, changes: [[part: 'terminator', value: [subtype: 'endIf']]], confirm: true])
        then:
        !result.success
        !result.partial
        result.error.contains('unavailable')
        !events.any { it instanceof Map }
    }

    def 'create uses native child identity label and actual Done installation'() {
        when:
        def result = script.executeTool('hub_create_rule_document', [parent_id: 28, name: 'New document', changes: [[part: 'terminator', value: [subtype: 'endIf']]], confirm: true])
        then:
        result.success
        result.created
        result.rule_id == 42L
        events[0..3] == ['create', 'initState', 'origLabel:New document', 'label:New document']
        events.find { it instanceof Map }.body.contains('_action_update=Done')
    }

    def 'create accepts the native Location header prefix and absolute loopback origin'() {
        given:
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, int t = 30, boolean retry = false ->
            events << 'create'
            [status: 302, location: 'Location: http://127.0.0.1:8080/installedapp/configure/42']
        }
        when:
        def result = script.toolCreateRuleDocument([parent_id: 28, name: 'Header child', changes: [[part: 'terminator', value: [subtype: 'endIf']]], confirm: true])
        then:
        result.success
        result.rule_id == 42L
    }

    def 'create refuses an unexpected child parent before changing its state or label'() {
        given:
        script.metaClass.getAppByAppId = { Long id -> id == 28L ? [id: 28L, appType: [namespace: 'hubitat', name: 'Rule Machine']] : [id: 42L, parentAppId: 99L, appType: [namespace: 'hubitat', name: 'Rule-5.1']] }
        when:
        def result = script.toolCreateRuleDocument([parent_id: 28, name: 'Wrong child', changes: [[part: 'terminator', value: [subtype: 'endIf']]], confirm: true])
        then:
        !result.success
        result.partial
        events == ['create']
    }

    def 'invalid parent prevents native child creation'() {
        when:
        script.toolCreateRuleDocument([parent_id: 42, name: 'Bad parent', changes: [[part: 'terminator', value: [subtype: 'endIf']]], confirm: true])
        then:
        thrown(IllegalArgumentException)
        events.empty
    }

    def 'read and write masters and confirmation apply through real dispatcher'() {
        when:
        settingsMap.enableRead = false
        script.executeTool('hub_get_rule_document', [rule_id: 42])
        then:
        thrown(IllegalArgumentException)
        when:
        settingsMap.enableRead = true
        settingsMap.enableWrite = false
        script.executeTool('hub_update_rule_document', [rule_id: 42, changes: [], confirm: true])
        then:
        thrown(IllegalArgumentException)
        when:
        settingsMap.enableWrite = true
        script.executeTool('hub_update_rule_document', [rule_id: 42, changes: [[part: 'terminator', value: [subtype: 'endIf']]], confirm: false])
        then:
        thrown(IllegalArgumentException)
        events.empty
    }
}

class NativeDocumentChild {
    List events
    Map lifecycle
    Map locals
    NativeDocumentChild(List events, Map lifecycle, Map locals = [:]) { this.events = events; this.lifecycle = lifecycle; this.locals = locals }
    Map getAllLocalVars() { locals }
    void appButtonHandler(String name) {
        events << name
        if (name == 'stopRule') lifecycle.stopped = !lifecycle.stopped
        if (name == 'pausRule') lifecycle.paused = !lifecycle.paused
    }
    void initState() { events << 'initState' }
    void updateSetting(String name, Map value) { events << (name + ':' + value.value) }
    void updateLabel(String name) { events << ('label:' + name) }
    Object selectTriggers() { events << 'selectTriggers'; [name: 'selectTriggers', sections: [[body: []]]] }
    Object selectActions(Map params) { events << 'selectActions'; [name: 'selectActions', sections: [[body: []]]] }
    Object mainPage() { events << 'mainPage'; [name: 'mainPage', sections: [[body: []]]] }
}

class NativeDocumentDiagnosticChild extends NativeDocumentChild {
    boolean diagnostic
    NativeDocumentDiagnosticChild(List events, Map lifecycle, boolean diagnostic) { super(events, lifecycle); this.diagnostic = diagnostic }
    Object selectActions(Map params) {
        events << 'selectActions'
        [name: 'selectActions', sections: [[input: [[name: 'logmsg.1', value: '**Broken Condition**']], body: [[type: 'paragraph', description: diagnostic ? '<span style=color:red>**Broken Condition**</span>' : 'Log: **Broken Condition**']]]]]
    }
}
