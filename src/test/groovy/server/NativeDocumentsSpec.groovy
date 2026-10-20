package server

import groovy.json.JsonOutput
import support.ToolSpecBase

class NativeDocumentsSpec extends ToolSpecBase {
    List events
    Map nativeState
    boolean failAction
    boolean broken
    Map lifecycle
    Map typedSettings

    void setup() {
        events = []
        failAction = false
        broken = false
        typedSettings = [:]
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
            new NativeDocumentChild(events, lifecycle, nativeState.allLocalVars, typedSettings)
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

    def 'native password settings are redacted without rendering or altering false and zero'() {
        given:
        String secret = 'native-password-secret'
        hubGet.register('/installedapp/settings/42') { q -> JsonOutput.toJson([settings: [
            [name: 'credential', type: 'password', value: secret],
            [name: 'passwordHint', type: 'text', value: 'visible hint'],
            [name: 'flag', type: 'bool', value: false],
            [name: 'count', type: 'number', value: 0]
        ]]) }
        when:
        def result = script.toolGetRuleDocument([rule_id: 42, sections: ['settings']])
        then:
        def credential = result.settings.find { it.name == 'credential' }
        credential.value == null
        credential.redacted == true
        !JsonOutput.toJson(result).contains(secret)
        result.settings.find { it.name == 'passwordHint' }.value == 'visible hint'
        result.settings.findAll { it.type != 'password' }.every { !it.containsKey('redacted') }
        result.settings.find { it.name == 'flag' }.value == false
        result.settings.find { it.name == 'count' }.value == 0
        events.isEmpty()
    }

    def 'disabled native required expression ignores stale predicate flags without rendering'() {
        given:
        typedSettings.useST = false
        nativeState.hasPredicate = true
        nativeState.isPredicate = false
        nativeState.predCapabs = [4]
        nativeState.eval = [:]
        when:
        def document = script.toolGetRuleDocument([rule_id: 42, sections: ['expressions']])
        then:
        document.validation.structuralIssues.isEmpty()
        nativeState.hasPredicate == true
        nativeState.eval.isEmpty()
        events.isEmpty()
        when:
        def result = script.toolUpdateRuleDocument([rule_id: 42, changes: [[part: 'variable', value: [operation: 'set', name: 'counter', value: 0]]], confirm: true])
        then:
        result.success
        events.any { it instanceof Map && it.path == '/installedapp/update/json' }
    }

    def 'enabled native required expression detects missing and invalid operands despite stale flags'() {
        given:
        typedSettings.useST = true
        nativeState.hasPredicate = false
        nativeState.eval = expression
        when:
        def document = script.toolGetRuleDocument([rule_id: 42, sections: ['expressions']])
        then:
        document.validation.structuralIssues.any { it.contains(expected) }
        events.isEmpty()
        when:
        def result = script.toolUpdateRuleDocument([rule_id: 42, changes: [[part: 'variable', value: [operation: 'set', name: 'counter', value: 0]]], confirm: true])
        then:
        !result.success
        result.error.contains(expected)
        !events.any { it instanceof Map }
        where:
        expression           | expected
        [:]                  | 'Missing native expression'
        ['0': ['999999']]    | 'unknown condition'
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

    def 'fresh Button Rule opens its native action editor before allocating and preserves its button binding'() {
        given:
        script.metaClass.getAppByAppId = { Long id -> [id: 42L, parentAppId: 28L, appType: [namespace: 'hubitat', name: 'Button Rule-5.1'], installed: true] }
        nativeState = [buttonRule: true, isButtonRule: true, trigDevs: ['7'], capabstrue: ['1': 'Button 1 pushed'], keep: [unknown: true]]
        typedSettings.putAll([tDev1: ['7'], tCapab1: 'Button', tstate1: 'pushed', ButtontDev1: 1])
        Map bindingBefore = [:] + typedSettings
        script.metaClass._nrHandle = { Long id -> new NativeDocumentButtonChild(events, lifecycle, nativeState, typedSettings) }
        script.metaClass._nmUpsertAction = { Long id, Map value ->
            assert nativeState.actNdx == 1
            events << 'action'
            nativeState.actions = ['1': [method: 'getLogMsg']]
            nativeState.actionList = ['1']
            [success: true, id: '1']
        }

        when:
        def result = script.toolUpdateRuleDocument([rule_id: 42, changes: [[part: 'action', value: [type: 'messageActs', subtype: 'getLogMsg', fields: []]]], confirm: true])

        then:
        result.success
        result.rule_id == 42L
        events[0..4] == ['selectActionsX', 'action', 'selectTriggers', 'selectActionsX', 'selectActions']
        events[5].path == '/installedapp/update/json'
        !events.contains('mainPage')
        !events.contains('initState')
        !events.contains('selectActionsWithParams')
        typedSettings == bindingBefore
        nativeState.buttonRule && nativeState.isButtonRule
        nativeState.trigDevs == ['7']
        nativeState.capabstrue == ['1': 'Button 1 pushed']
        nativeState.keep == [unknown: true]
        result.document.actions*.id == ['1']
    }

    def 'existing Button Rule validates its action editor and primary page without resetting native state'() {
        given:
        script.metaClass.getAppByAppId = { Long id -> [id: 42L, parentAppId: 28L, appType: [namespace: 'hubitat', name: 'Button Rule-5.1'], installed: true] }
        nativeState.actNdx = 8
        Map before = [:] + nativeState
        script.metaClass._nrHandle = { Long id -> new NativeDocumentButtonChild(events, lifecycle, nativeState, typedSettings, brokenEditor) }

        when:
        def result = script.toolUpdateRuleDocument([rule_id: 42, changes: [[part: 'action', value: [id: '3', type: 'messageActs', subtype: 'getLogMsg', fields: []]]], confirm: true])

        then:
        result.success == !brokenEditor
        result.rule_id == 42L
        events.take(3) == ['action', 'selectTriggers', 'selectActionsX']
        events.contains('selectActions') == !brokenEditor
        events.any { it instanceof Map && it.path == '/installedapp/update/json' } == !brokenEditor
        !events.contains('mainPage')
        !events.contains('initState')
        !events.contains('selectActionsWithParams')
        nativeState == before

        where:
        brokenEditor << [false, true]
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

    def 'native time triggers use scheduled jobs as activation evidence'() {
        given:
        nativeState.capabstrue = ['5': 'When time is 11:59 PM']
        List jobs = scheduled ? [[handler: 'allHandlerT', status: 'PENDING',
                                 nextRunTime: '2026-10-21T03:59:00+0000']] : []
        hubGet.register('/installedapp/statusJson/42') { q ->
            JsonOutput.toJson([installedApp: [id: 42, installed: true],
                              eventSubscriptions: null, scheduledJobs: jobs])
        }
        when:
        def result = script.toolUpdateRuleDocument([rule_id: 42, confirm: true,
            changes: [[part: 'variable', value: [operation: 'set', name: 'counter', value: 0]]]])
        then:
        result.success == scheduled
        if (scheduled) {
            assert result.eventSubscriptions == []
            assert result.scheduledJobs == jobs
        } else {
            assert result.partial
            assert result.error.contains('subscriptions or scheduled jobs')
        }
        where:
        scheduled << [true, false]
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

    def 'unknown simple action condition refuses the complete batch before rendering'() {
        when:
        def result = script.toolUpdateRuleDocument([rule_id: 42, changes: [
            [part: 'variable', value: [operation: 'set', name: 'counter', value: 7]],
            [part: 'action', value: [type: 'switchActs', subtype: 'getOnOffSwitch', condition: '999999', fields: []]]
        ], confirm: true])
        then:
        !result.success
        !result.partial
        result.error.contains('unknown condition')
        events.empty
    }

    def 'new simple conditional action prevents deleting its condition later in the same batch'() {
        when:
        def result = script.toolUpdateRuleDocument([rule_id: 42, changes: [
            [part: 'action', value: [type: 'switchActs', subtype: 'getOnOffSwitch', condition: '4', fields: []]],
            [part: 'delete', value: [part: 'condition', id: '4']]
        ], confirm: true])
        then:
        !result.success
        !result.partial
        result.error.contains('Referenced')
        events.empty
    }

    def 'wait expression and simple condition changes retain their native semantic fields'() {
        given:
        List received = []
        script.metaClass._nmUpsertAction = { Long id, Map value -> received << value; [success: true] }
        when:
        def result = script.toolUpdateRuleDocument([rule_id: 42, changes: [
            [part: 'action', value: [type: 'delayActs', subtype: 'getWaitRule', expression: ['4'], fields: []]],
            [part: 'action', value: [type: 'switchActs', subtype: 'getOnOffSwitch', condition: '4', fields: []]]
        ], confirm: true])
        then:
        result.success
        received*.subtype == ['getWaitRule', 'getOnOffSwitch']
        received[0].expression == ['4']
        received[1].condition == '4'
    }

    def 'new wait expression without tokens refuses before any mutation'() {
        when:
        script.toolUpdateRuleDocument([rule_id: 42, changes: [[part: 'action', value: [type: 'delayActs', subtype: 'getWaitRule', fields: []]]], confirm: true])
        then:
        thrown(IllegalArgumentException)
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

    def 'explicit null activation collections are empty only for the verified installed rule'() {
        given:
        nativeState.capabstrue = [:]
        hubGet.register('/installedapp/statusJson/42') { q -> JsonOutput.toJson([installedApp: [id: 42, installed: true], eventSubscriptions: null, scheduledJobs: null]) }
        when:
        def result = script.toolUpdateRuleDocument([rule_id: 42, changes: [[part: 'terminator', value: [subtype: 'endIf']]], confirm: true])
        then:
        result.success
        result.eventSubscriptions == []
    }

    def 'null activation collections cannot stand in for missing or mismatched rule identity'() {
        given:
        hubGet.register('/installedapp/statusJson/42') { q -> JsonOutput.toJson([installedApp: [id: 99, installed: true], eventSubscriptions: null, scheduledJobs: null]) }
        when:
        def result = script.toolUpdateRuleDocument([rule_id: 42, changes: [[part: 'terminator', value: [subtype: 'endIf']]], confirm: true])
        then:
        !result.success
        result.partial
        result.error.contains('cannot verify')
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
        !result.created
        result.rule_id == null
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
    Map typedSettings
    NativeDocumentChild(List events, Map lifecycle, Map locals = [:], Map typedSettings = [:]) { this.events = events; this.lifecycle = lifecycle; this.locals = locals; this.typedSettings = typedSettings }
    Object getSetting(String name) { typedSettings.get(name) }
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

class NativeDocumentButtonChild extends NativeDocumentChild {
    Map nativeState
    boolean brokenEditor
    NativeDocumentButtonChild(List events, Map lifecycle, Map nativeState, Map typedSettings, boolean brokenEditor = false) {
        super(events, lifecycle, nativeState.allLocalVars ?: [:], typedSettings)
        this.nativeState = nativeState
        this.brokenEditor = brokenEditor
    }
    Object mainPage() { events << 'mainPage'; null }
    Object selectActions(Map params) { events << 'selectActionsWithParams'; null }
    Object selectActions() { events << 'selectActions'; [name: 'selectActions', sections: [[body: []]]] }
    Object selectActionsX() {
        events << 'selectActionsX'
        if (nativeState.actNdx == null && !nativeState.actions && !nativeState.actionList) nativeState.actNdx = 1
        [name: 'selectActionsX', sections: [[body: brokenEditor ? [[type: 'paragraph', description: '<span style="color:red">**Broken Action**</span>']] : []]]]
    }
}
