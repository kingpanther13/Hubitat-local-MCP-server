package server

import groovy.json.JsonOutput
import support.ToolSpecBase
import support.TestChildApp
import spock.lang.Shared

class NativeDocumentsSpec extends ToolSpecBase {
    @Shared TestChildApp serverApp = new TestChildApp(id: 7L, label: 'MCP')

    void setupSpec() { appExecutor.getApp() >> serverApp }

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
        // Snapshot behavior has dedicated NativeRuleEditBackupSpec coverage.
        settingsMap.backupBeforeRuleEdits = false
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

    private Map logChange() {
        [part: 'action', value: [type: 'messageActs', subtype: 'getLogMsg',
            fields: [[name: 'logmsg.#', type: 'textarea', value: 'Document fixture']]]]
    }

    def 'paged native reads concatenate complete ordered actions maps and typed settings without persistence'() {
        given:
        nativeState.capabsfalse = ['9': 'nine', '2': 'two', '10': 'ten']
        // The mapped transport probes header support before dispatching a tool.
        script.jsonRpcResult(0, [:])
        def beforeAtomic = [:] + atomicStateMap
        def beforeState = [:] + stateMap
        when:
        def actionPages = (0..1).collect { offset -> script.toolSetRule([operation: 'read', rule_id: 42,
            args: [sections: ['actions'], cursor: offset.toString(), limit: 1]]) }
        def conditionPages = (0..2).collect { offset -> script.toolSetRule([operation: 'read', rule_id: 42,
            args: [sections: ['conditions'], cursor: offset.toString(), limit: 1]]) }
        def settingPages = (0..1).collect { offset -> script.toolSetRule([operation: 'read', rule_id: 42,
            args: [sections: ['settings'], cursor: offset.toString(), limit: 1]]) }
        then:
        actionPages.collectMany { it.actions }*.id == ['3', '1']
        conditionPages.collectMany { it.conditions.keySet().toList() } == ['10', '2', '9']
        conditionPages.collectEntries { it.conditions } == nativeState.capabsfalse
        settingPages.collectMany { it.settings }*.name == ['count', 'flag']
        settingPages[0].settings[0].value == 0
        settingPages[1].settings[0].value == false
        [actionPages, conditionPages, settingPages].every { pages ->
            pages.collect { it.page.revision }.unique().size() == 1 && (pages[0].page.revision ==~ /[0-9a-f]{64}/) &&
                pages[0].page.total == pages.size() && pages[0].nextCursor == '1' && pages.last().nextCursor == null
        }
        atomicStateMap == beforeAtomic
        stateMap == beforeState
        events.empty
    }

    def 'paged read revisions cover data outside the current page and canonicalize map insertion order'() {
        given:
        nativeState.capabsfalse = ['2': 'two', '1': 'one']
        when:
        def first = script.toolGetRuleDocument([rule_id: 42, sections: ['conditions'], limit: 1])
        nativeState.capabsfalse = ['1': 'one', '2': 'two']
        def reordered = script.toolGetRuleDocument([rule_id: 42, sections: ['conditions'], limit: 1])
        nativeState.capabsfalse['2'] = 'changed outside page'
        def changed = script.toolGetRuleDocument([rule_id: 42, sections: ['conditions'], limit: 1])
        then:
        first.conditions == reordered.conditions && first.conditions == changed.conditions
        first.page.revision == reordered.page.revision
        first.page.revision != changed.page.revision
        first.page.offset == 0 && first.page.limit == 1
        !script.toolGetRuleDocument([rule_id: 42, sections: ['conditions']]).containsKey('page')
    }

    def 'paging defaults return an explicit final cursor including an empty section'() {
        given:
        nativeState.actions = [:]
        nativeState.actionList = []
        when:
        def result = script.toolGetRuleDocument([rule_id: 42, sections: ['actions'], cursor: ''])
        then:
        result.actions == []
        result.page.offset == 0
        result.page.limit == 50
        result.page.total == 0
        result.containsKey('nextCursor') && result.nextCursor == null
        !result.page.containsKey('next_offset')
    }

    def 'invalid document paging refuses before native rendering or reads'() {
        given:
        script.metaClass._nrHandle = { Long id -> throw new AssertionError('invalid paging must not access native app') }
        when:
        script.toolSetRule([operation: 'read', rule_id: 42, args: args])
        then:
        thrown(IllegalArgumentException)
        where:
        args << [[limit: 1], [sections: [], limit: 1], [sections: ['actions', 'settings'], cursor: ''],
            [sections: ['actions'], cursor: '-1'], [sections: ['actions'], cursor: 'bad'],
            [sections: ['actions'], cursor: true], [sections: ['actions'], cursor: 0.5],
            [sections: ['actions'], offset: 0],
            [sections: ['actions'], limit: 0], [sections: ['actions'], limit: 101],
            [sections: ['actions'], limit: false], [sections: ['actions'], limit: 1.5]]
    }

    def 'cursor at or beyond section end refuses instead of returning a misleading empty page'() {
        when:
        script.toolSetRule([operation: 'read', rule_id: 42, args: [sections: ['actions'], cursor: cursor]])
        then:
        thrown(IllegalArgumentException)
        where:
        cursor << ['2', '99', '2147483648']
    }

    def 'byte bounded pages account for unicode and JSON text escaping without dropping rows'() {
        given:
        String text = ('\u263a"\\\n' * 90)
        nativeState.capabsfalse = ['1': text, '2': text, '3': text]
        def unpaged = script.toolGetRuleDocument([rule_id: 42, sections: ['conditions']])
        script.metaClass._responseSizeLimitBytes = { -> 5000 }
        List pages = []
        String cursor = ''
        when:
        while (cursor != null) {
            def result = script.toolSetRule([operation: 'read', rule_id: 42,
                args: [sections: ['conditions'], cursor: cursor, limit: 3]])
            pages << result
            cursor = result.nextCursor
        }
        then:
        JsonOutput.toJson(unpaged).getBytes('UTF-8').length < 5000 - 1024
        JsonOutput.toJson(script.jsonRpcResult(0, [content: [[type: 'text', text: JsonOutput.toJson(unpaged)]]])).getBytes('UTF-8').length > 5000 - 1024
        pages.size() > 1
        pages.collectEntries { it.conditions } == nativeState.capabsfalse
        pages.collectMany { it.conditions.keySet().toList() } == ['1', '2', '3']
        pages.every { result ->
            JsonOutput.toJson(script.jsonRpcResult(0, [content: [[type: 'text', text: JsonOutput.toJson(result)]]])).getBytes('UTF-8').length <= 5000 - 1024
        }
        pages[0].conditions.size() < pages[0].page.limit
        pages.collect { it.page.revision }.unique().size() == 1
    }

    def 'one oversized native row refuses explicitly without mutation or truncation'() {
        given:
        nativeState.capabsfalse = ['1': '\u263a"\\' * 2000]
        script.metaClass._responseSizeLimitBytes = { -> 5000 }
        def before = JsonOutput.toJson(nativeState)
        when:
        script.toolSetRule([operation: 'read', rule_id: 42, args: [sections: ['conditions'], cursor: '', limit: 1]])
        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('exceeds the response size limit')
        JsonOutput.toJson(nativeState) == before
        events.empty
    }

    def 'read document returns native stable order typed values and locals without rendering'() {
        when:
        def result = script.executeTool('hub_set_rule', [operation: 'read', rule_id: 42, args: [:]])
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

    def 'a repeated field name in a later change refuses create and update before any write'() {
        given:
        settingsMap.remove('backupBeforeRuleEdits')
        script.metaClass._rmBackupRuleSnapshot = { Integer id, String reason, boolean strict -> events << 'backup'; [backupKey: 'unexpected'] }
        String beforeState = JsonOutput.toJson(nativeState)
        def repeated = [part: 'action', value: [type: 'messageActs', subtype: 'getLogMsg',
            fields: [[name: 'logmsg.#', type: 'textarea', value: 'one'], [name: 'logmsg.#', type: 'textarea', value: 'two']]]]
        when:
        script.toolCreateRuleDocument([parent_id: 28, name: 'Repeated create', changes: [logChange(), repeated], confirm: true])
        then:
        def created = thrown(IllegalArgumentException)
        created.message == 'changes[1]: Native inputs must not be repeated'
        events.empty
        when:
        script.toolUpdateRuleDocument([rule_id: 42, changes: [logChange(), repeated], confirm: true])
        then:
        def updated = thrown(IllegalArgumentException)
        updated.message == 'changes[1]: Native inputs must not be repeated'
        events.empty
        JsonOutput.toJson(nativeState) == beforeState
    }

    def 'an edit naming a missing row after a valid change is refused before any write or backup'() {
        given:
        settingsMap.remove('backupBeforeRuleEdits')
        script.metaClass._rmBackupRuleSnapshot = { Integer id, String reason, boolean strict -> events << 'backup'; [backupKey: 'unexpected'] }
        // A new action allocates its id only when written, so a later edit by id of the same kind is left to
        // the write; a condition edit after a new action is still checked against the rule's conditions.
        def missing = [part: 'condition', value: [id: '99', capability: 'Switch',
            fields: [[name: 'rDev_#', type: 'capability.switch', value: ['7']], [name: 'state_#', type: 'enum', value: 'on']]]]
        when:
        def result = script.toolUpdateRuleDocument([rule_id: 42, changes: [logChange(), missing], confirm: true])
        then:
        !result.success && !result.partial
        result.error == 'changes[1]: Native row does not exist'
        events.empty
    }

    def 'a variable set carrying a type is refused before any write'() {
        when:
        script.toolUpdateRuleDocument([rule_id: 42, confirm: true, changes: [
            [part: 'variable', value: [operation: 'set', name: 'counter', type: 'integer', value: 7]]]])
        then:
        def failure = thrown(IllegalArgumentException)
        failure.message == 'changes[0]: Variable type is accepted only when creating a local variable'
        nativeState.allLocalVars.counter.value == 0
        events.empty
    }

    def 'failed change stops and returns truthful partial outcomes'() {
        given:
        failAction = true
        when:
        def result = script.toolUpdateRuleDocument([rule_id: 42, changes: [[part: 'action', value: [type: 'messageActs', subtype: 'getLogMsg', fields: []]], logChange()], confirm: true])
        then:
        !result.success
        result.partial
        result.results.size() == 1
        events == ['action']
    }

    def 'an unverified first variable write reports partial and stops the document batch'() {
        given:
        script.metaClass._nmLocal = { Long id, String operation, String name = null, Object value = null, String type = null ->
            events << 'variable'
            nativeState.allLocalVars[name].value = value
            [success: false, partial: true, verificationNeeded: true]
        }
        when:
        def result = script.toolUpdateRuleDocument([rule_id: 42, confirm: true, changes: [
            [part: 'variable', value: [operation: 'set', name: 'counter', value: 7]], logChange()]])
        then:
        !result.success && result.partial
        result.results.size() == 1
        result.results[0].part == 'variable'
        result.results[0].result.verificationNeeded
        result.remaining == [logChange()]
        nativeState.allLocalVars.counter.value == 7
        events == ['variable']
    }

    def 'native Done error bodies cannot pass using previously healthy installed rule state'() {
        given:
        script.metaClass.hubInternalPostFormRaw = { String path, String body, int timeout = 420, boolean retry = false ->
            events << [path: path, body: body]
            [status: 200, data: JsonOutput.toJson(response)]
        }
        when:
        def result = script.toolUpdateRuleDocument([rule_id: 42, confirm: true, changes: [logChange()]])
        then:
        !result.success && result.partial
        result.error.contains('Native form')
        events.any { it instanceof Map && it.body.contains('_action_update=Done') }
        where:
        response << [[status: 'error'], [error: 'Save refused'], [errorMessage: 'Save refused'],
            [configPage: [error: 'Save refused']]]
    }

    def 'native Done allows success and redirect responses before health verification'() {
        given:
        script.metaClass.hubInternalPostFormRaw = { String path, String body, int timeout = 420, boolean retry = false ->
            [status: status, data: response]
        }
        when:
        def result = script.toolUpdateRuleDocument([rule_id: 42, confirm: true, changes: [logChange()]])
        then:
        result.success
        where:
        status | response
        200    | [status: 'success']
        200    | '{"redirect":"/installedapp/list"}'
        302    | null
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

    def 'existing Button Rule edits capture verified native recovery with default backups enabled'() {
        given:
        settingsMap.remove('backupBeforeRuleEdits')
        nativeState.actNdx = 8
        script.metaClass.getAppByAppId = { Long id -> [id: 42L, parentAppId: 28L, appType: [namespace: 'hubitat', name: 'Button Rule-5.1'], installed: true] }
        def child = new NativeDocumentBackupButtonChild(events, lifecycle, nativeState, typedSettings)
        script.metaClass._nrHandle = { Long id -> child }
        byte[] saved
        script.metaClass._withBackupLock = { String label, Closure work -> work.call() }
        script.metaClass.uploadHubFile = { String name, byte[] bytes -> events << 'backupUpload'; saved = bytes }
        script.metaClass.downloadHubFile = { String name -> events << 'backupReadback'; saved }
        script.metaClass._publishUploadedItemBackup = { String key, Map entry, String protectedKey -> }
        when:
        def result = script.toolUpdateRuleDocument([rule_id: 42, confirm: true, changes: [logChange()]])
        then:
        result.success && result.backup.backupKey
        events.indexOf('export') < events.indexOf('backupUpload')
        events.indexOf('backupUpload') < events.indexOf('backupReadback')
        events.indexOf('backupReadback') < events.indexOf('action')
        def recovery = new groovy.json.JsonSlurper().parseText(new String(saved, 'UTF-8'))
        recovery.captureFormat == 'native-rule-edit-v1'
        recovery.configJson.app.appType.name == 'Button Rule-5.1'
        recovery.configJson.configPage.name == 'selectActions'
        !events.contains('mainPage')
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
        def result = script.toolUpdateRuleDocument([rule_id: 42, changes: [logChange()], confirm: true])
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

    def 'false required expression reports native suppression with or without tracking subscriptions'() {
        given:
        typedSettings.useST = true
        nativeState.hasPredicate = true
        nativeState.stPrimed = false
        hubGet.register('/installedapp/statusJson/42') { q ->
            JsonOutput.toJson([installedApp: [id: 42, installed: true],
                              eventSubscriptions: subscriptions, scheduledJobs: []])
        }
        when:
        def result = script.toolUpdateRuleDocument([rule_id: 42, confirm: true,
            changes: [[part: 'variable', value: [operation: 'set', name: 'counter', value: 0]]]])
        then:
        result.success
        result.subscriptionSettle == 'SUPPRESSED: Required Expression is false'
        result.eventSubscriptions == subscriptions
        events.any { it instanceof Map && it.path == '/installedapp/update/json' }
        where:
        subscriptions << [[], [[name: 'required-expression-tracking']]]
    }

    def 'missing activation cannot be explained by disabled stale or unknown predicate state'() {
        given:
        typedSettings.useST = enabled
        nativeState.hasPredicate = predicate
        nativeState.stPrimed = primed
        hubGet.register('/installedapp/statusJson/42') { q ->
            JsonOutput.toJson([installedApp: [id: 42, installed: true],
                              eventSubscriptions: [], scheduledJobs: []])
        }
        when:
        def result = script.toolUpdateRuleDocument([rule_id: 42, confirm: true,
            changes: [[part: 'variable', value: [operation: 'set', name: 'counter', value: 0]]]])
        then:
        !result.success
        result.partial
        result.error.contains('subscriptions or scheduled jobs')
        !result.containsKey('subscriptionSettle')
        where:
        enabled | predicate | primed
        false   | true      | false
        true    | false     | false
        true    | true      | true
        true    | true      | null
        true    | true      | 'false'
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

    def 'invalid typed scalar later in a batch cannot delete earlier actions'() {
        when:
        script.toolUpdateRuleDocument([rule_id: 42, confirm: true, changes: [
            [part: 'delete', value: [part: 'action', id: '1']],
            [part: 'action', value: [type: 'messageActs', subtype: 'getLogMsg',
                fields: [[name: 'flag.#', type: type, value: value]]]]
        ]])
        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains('flag.#')
        events.empty
        nativeState.actionList == ['3', '1']
        where:
        type      | value
        'bool'    | 'blink'
        'number'  | 'many'
        'decimal' | true
        'time'    | 12345
    }

    def 'removing one structural boundary is refused before writes but removing the entire block is allowed'() {
        given:
        nativeState.actionList = ['1', '2', '3']
        nativeState.actions = ['1': [method: 'getIfThen'], '2': [method: 'getLogMsg'], '3': [method: 'getEndIf']]
        when:
        def refused = script.toolUpdateRuleDocument([rule_id: 42, confirm: true, changes: [
            [part: 'delete', value: [part: 'action', id: '3']]
        ]])
        then:
        !refused.success
        !refused.partial
        refused.error.contains('END-IF')
        events.empty
        when:
        def accepted = script.toolUpdateRuleDocument([rule_id: 42, confirm: true, changes: [
            [part: 'delete', value: [part: 'action', id: '1']],
            [part: 'delete', value: [part: 'action', id: '3']]
        ]])
        then:
        accepted.success
        events[0..1] == ['delete:action:1', 'delete:action:3']
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

    def 'a required expression moved off a condition lets a later change in the batch delete it'() {
        given:
        nativeState.capabsfalse = ['4': 'Switch is on', '8': 'Switch is off']
        nativeState.predCapabs = ['4']
        when:
        script._ndPreflight(42L, script._ndChanges([
            [part: 'required', value: [tokens: ['8']]],
            [part: 'delete', value: [part: 'condition', id: '4']]
        ]))
        then:
        noExceptionThrown()
    }

    def 'a conditional action deleted earlier in the batch no longer holds its condition'() {
        given:
        nativeState.capabsfalse = ['4': 'Switch is on', '8': 'Switch is off']
        nativeState.actionList = ['3', '1']
        nativeState.actions = ['3': [method: 'getLogMsg'], '1': [method: 'getOnOffSwitch', cond: '8']]
        when:
        script._ndPreflight(42L, script._ndChanges([
            [part: 'delete', value: [part: 'action', id: '1']],
            [part: 'delete', value: [part: 'condition', id: '8']]
        ]))
        then:
        noExceptionThrown()
    }

    def 'an action deleted earlier in the batch cannot be edited later in it'() {
        when:
        def result = script.toolUpdateRuleDocument([rule_id: 42, confirm: true, changes: [
            [part: 'delete', value: [part: 'action', id: '1']],
            [part: 'action', value: logChange().value + [id: '1']]
        ]])
        then:
        !result.success
        !result.partial
        result.error.contains('Native action does not exist')
        events.empty
    }

    def 'an order after a new action in the same batch is refused before any write'() {
        when:
        def result = script.toolUpdateRuleDocument([rule_id: 42, confirm: true, changes: [
            logChange(),
            [part: 'order', value: [ids: ['1', '3']]]
        ]])
        then:
        !result.success
        !result.partial
        result.error.contains('cannot follow new actions')
        events.empty
    }

    def 'an order placing END-REP before its REPEAT is refused before any write'() {
        given:
        nativeState.actionList = ['1', '2', '3', '4']
        nativeState.actions = ['1': [method: 'getLogMsg'], '2': [method: 'getRepeat'], '3': [method: 'getLogMsg'], '4': [method: 'getEndRepeat']]
        when:
        def result = script.toolUpdateRuleDocument([rule_id: 42, confirm: true, changes: [
            [part: 'order', value: [ids: ['1', '4', '3', '2']]]
        ]])
        then:
        !result.success
        !result.partial
        result.error.contains('Native END-REP has no matching REPEAT')
        result.error.contains('Native REPEAT block has no END-REP')
        events.empty
        nativeState.actionList == ['1', '2', '3', '4']
    }

    private void orderedBlock(List methods) {
        List ids = (1..methods.size()).collect { it.toString() }
        nativeState.actionList = ids
        nativeState.actions = [ids, methods].transpose().collectEntries { pair -> [(pair[0]): [method: pair[1]]] }
    }

    private void preflightOrder() {
        script._ndPreflight(42L, script._ndChanges([[part: 'order', value: [ids: nativeState.actionList]]]))
    }

    def 'nested IF and REPEAT blocks pass preflight when each closes in order'() {
        given:
        orderedBlock(methods)
        when:
        preflightOrder()
        then:
        noExceptionThrown()
        where:
        methods << [
            ['getIfThen', 'getRepeat', 'getLogMsg', 'getEndRepeat', 'getEndIf'],
            ['getRepeat', 'getIfThen', 'getLogMsg', 'getElse', 'getLogMsg', 'getEndIf', 'getEndRepeat'],
            ['getRepeat', 'getIfThen', 'getLogMsg', 'getElseIf', 'getLogMsg', 'getEndIf', 'getEndRepeat']
        ]
    }

    def 'interleaved IF and REPEAT blocks are refused by preflight'() {
        given:
        orderedBlock(methods)
        when:
        preflightOrder()
        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains(message)
        where:
        methods                                                                          | message
        ['getIfThen', 'getRepeat', 'getEndIf', 'getEndRepeat']                           | 'Native END-IF has no matching IF'
        ['getIfThen', 'getRepeat', 'getElse', 'getEndRepeat', 'getEndIf']                | 'Native ELSE has no matching IF'
    }

    def 'every Rule Machine repeat opener starts a block that END-REP closes'() {
        when:
        orderedBlock([opener, 'getLogMsg', 'getEndRepeat'])
        preflightOrder()
        then:
        noExceptionThrown()
        when:
        orderedBlock([opener, 'getLogMsg'])
        preflightOrder()
        then:
        def error = thrown(IllegalArgumentException)
        error.message.contains('Native REPEAT block has no END-REP')
        where:
        opener << ['getRepeat', 'getWhile', 'getUntil', 'getRepeatWhile']
    }

    def 'two edits of the same wait in one batch keep its event count'() {
        given:
        nativeState.actionList = ['3', '1']
        nativeState.actions = ['3': [method: 'getLogMsg'], '1': [method: 'getWaitEvents', wait: [7]]]
        Map edit = [part: 'action', value: [id: '1', type: 'delayActs', subtype: 'getWaitEvents', fields: [], events: [[capability: 'Switch', fields: []]]]]
        when:
        script._ndPreflight(42L, script._ndChanges([edit, edit]))
        then:
        noExceptionThrown()
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
        nativeState.actions.'6' = [method: 'getEndIf']
        nativeState.actionList = ['3', '1', '6']
        when:
        def result = script.toolUpdateRuleDocument([rule_id: 42, changes: [logChange()], confirm: true])
        then:
        !result.success
        result.error.contains('unknown condition')
        !events.any { it instanceof Map }
    }

    def 'native unfinished IF cannot finalize despite a false broken flag'() {
        given:
        nativeState.actions.'3' = [method: 'getIfThen', rule: '0']
        nativeState.inIf = true
        nativeState.actions.'6' = [method: 'getEndIf']
        nativeState.actionList = ['3', '1', '6']
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
        def failed = script.toolUpdateRuleDocument([rule_id: 42, changes: [logChange()], confirm: true])
        then:
        !failed.success
        failed.error.contains('broken condition')
        !events.any { it instanceof Map }
        when:
        events.clear()
        script.metaClass._nrHandle = { Long id -> new NativeDocumentDiagnosticChild(events, lifecycle, false) }
        def passed = script.toolUpdateRuleDocument([rule_id: 42, changes: [logChange()], confirm: true])
        then:
        passed.success
    }

    def 'Done preserves an existing stopped and paused native rule using verified toggles'() {
        given:
        lifecycle.stopped = true
        lifecycle.paused = true
        when:
        def result = script.toolUpdateRuleDocument([rule_id: 42, changes: [logChange()], confirm: true])
        then:
        result.success
        result.stopped
        result.paused
        result.eventSubscriptions == []
        events.count { it == 'stopRule' } == 1
        events.count { it == 'pausRule' } == 1
    }

    def 'malformed native lifecycle flags remain unknown on reads and refuse writes before mutation'() {
        given:
        Map health = [broken: false, stopped: false, paused: false]
        health.put(flag, malformed)
        hubGet.register('/app/ruleBuilderJson/42') { q -> JsonOutput.toJson(health) }
        when:
        def document = script.toolGetRuleDocument([rule_id: 42, sections: ['actions']])
        then:
        document.nativeHealth.available
        document.nativeHealth.get(flag) == null
        document.nativeHealth.get(flag == 'stopped' ? 'paused' : 'stopped') == false
        events.empty
        when:
        def result = script.toolUpdateRuleDocument([rule_id: 42, changes: [logChange()], confirm: true])
        then:
        !result.success
        !result.partial
        result.error.contains('lifecycle state is malformed')
        events.empty
        nativeState.actionList == ['3', '1']
        where:
        flag      | malformed
        'stopped' | 'false'
        'stopped' | null
        'stopped' | 0
        'paused'  | 'true'
        'paused'  | [:]
        'paused'  | []
    }

    def 'absent native lifecycle flags retain the platform false default'() {
        given:
        hubGet.register('/app/ruleBuilderJson/42') { q -> JsonOutput.toJson([broken: false]) }
        when:
        def document = script.toolGetRuleDocument([rule_id: 42, sections: ['actions']])
        then:
        document.nativeHealth.stopped == false
        document.nativeHealth.paused == false
        events.empty
        when:
        def result = script.toolUpdateRuleDocument([rule_id: 42, changes: [logChange()], confirm: true])
        then:
        result.success
        !result.stopped
        !result.paused
        events.any { it instanceof Map && it.body.contains('_action_update=Done') }
    }

    def 'missing native health is unavailable and cannot finalize'() {
        given:
        hubGet.register('/app/ruleBuilderJson/42') { q -> '{}' }
        when:
        def result = script.toolUpdateRuleDocument([rule_id: 42, changes: [logChange()], confirm: true])
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
        def result = script.toolUpdateRuleDocument([rule_id: 42, changes: [logChange()], confirm: true])
        then:
        result.success
        result.eventSubscriptions == []
    }

    def 'null activation collections cannot stand in for missing or mismatched rule identity'() {
        given:
        hubGet.register('/installedapp/statusJson/42') { q -> JsonOutput.toJson([installedApp: [id: 99, installed: true], eventSubscriptions: null, scheduledJobs: null]) }
        when:
        def result = script.toolUpdateRuleDocument([rule_id: 42, changes: [logChange()], confirm: true])
        then:
        !result.success
        result.partial
        result.error.contains('cannot verify')
    }

    def 'create rejects deterministic changes before allocating a child'() {
        when:
        def result = script.toolCreateRuleDocument([parent_id: 28, name: 'Rejected create', changes: changes, confirm: true])

        then:
        !result.success && !result.partial
        !result.rule_id
        result.error.contains(message)
        events.empty

        where:
        changes | message
        [[part: 'variable', value: [operation: 'set', name: 'missing', value: 1]]] | 'Local variable does not exist'
        [[part: 'variable', value: [operation: 'delete', name: 'missing']]] | 'Local variable does not exist'
        [[part: 'delete', value: [part: 'action', id: '3']]] | 'Native action does not exist'
        [[part: 'order', value: [ids: ['3']]]] | 'exact permutation'
        [[part: 'terminator', value: [subtype: 'endIf']]] | 'END-IF'
    }

    def 'create uses native child identity label and actual Done installation'() {
        when:
        def result = script.executeTool('hub_set_rule', [operation: 'create', confirm: true, args: [parent_id: 28, name: 'New document', changes: [logChange()]]])
        then:
        result.success
        result.created
        result.rule_id == 42L
        events[0..3] == ['create', 'initState', 'origLabel:New document', 'label:New document']
        events.find { it instanceof Map }.body.contains('_action_update=Done')
    }

    def 'empty create installs a native rule while empty update remains a prewrite refusal'() {
        given:
        nativeState = [actionList: [], actions: [:], capabsfalse: [:], capabstrue: [:],
                       eval: [:], allLocalVars: [:]]
        hubGet.register('/installedapp/statusJson/42') { q ->
            JsonOutput.toJson([installedApp: [id: 42, installed: true],
                              eventSubscriptions: [], scheduledJobs: []])
        }
        when:
        def result = script.executeTool('hub_set_rule',
            [operation: 'create', confirm: true, args: [parent_id: 28, name: 'Empty document', changes: []]])
        then:
        result.success
        result.created
        result.rule_id == 42L
        result.results == []
        result.document.actions == []
        result.document.conditions == [:]
        result.document.triggers == [:]
        events[0..3] == ['create', 'initState', 'origLabel:Empty document', 'label:Empty document']
        events.any { it instanceof Map && it.path == '/installedapp/update/json' }
        when:
        events.clear()
        script.toolUpdateRuleDocument([rule_id: 42, changes: [], confirm: true])
        then:
        thrown(IllegalArgumentException)
        events.empty
        when:
        def definitions = script._getAllToolDefinitions_partNativeDocuments()
        then:
        script._ndOperationSchema('create').properties.changes.minItems == 0
        script._ndOperationSchema('update').properties.changes.minItems == 1
    }

    def 'create accepts the native Location header prefix and absolute loopback origin'() {
        given:
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, int t = 30, boolean retry = false ->
            events << 'create'
            [status: 302, location: 'Location: http://127.0.0.1:8080/installedapp/configure/42']
        }
        when:
        def result = script.toolCreateRuleDocument([parent_id: 28, name: 'Header child', changes: [logChange()], confirm: true])
        then:
        result.success
        result.rule_id == 42L
    }

    def 'create refuses an unexpected child parent before changing its state or label'() {
        given:
        script.metaClass.getAppByAppId = { Long id -> id == 28L ? [id: 28L, appType: [namespace: 'hubitat', name: 'Rule Machine']] : [id: 42L, parentAppId: 99L, appType: [namespace: 'hubitat', name: 'Rule-5.1']] }
        when:
        def result = script.toolCreateRuleDocument([parent_id: 28, name: 'Wrong child', changes: [logChange()], confirm: true])
        then:
        !result.success
        result.partial
        !result.created
        result.rule_id == null
        events == ['create']
    }

    def 'invalid parent prevents native child creation'() {
        when:
        script.toolCreateRuleDocument([parent_id: 42, name: 'Bad parent', changes: [logChange()], confirm: true])
        then:
        thrown(IllegalArgumentException)
        events.empty
    }

    def 'read and write masters and confirmation apply through real dispatcher'() {
        when:
        settingsMap.enableRead = false
        script.executeTool('hub_set_rule', [operation: 'read', rule_id: 42, args: [:]])
        then:
        thrown(IllegalArgumentException)
        when:
        settingsMap.enableRead = true
        settingsMap.enableWrite = false
        script.executeTool('hub_set_rule', [operation: 'update', rule_id: 42, confirm: true, args: [changes: []]])
        then:
        thrown(IllegalArgumentException)
        when:
        settingsMap.enableWrite = true
        def probe = script.executeTool('hub_set_rule', [operation: 'update', rule_id: 42, confirm: false, args: [changes: [logChange()]]])
        then:
        probe.confirm_required
        probe.operation == 'update'
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

class NativeDocumentBackupButtonChild extends NativeDocumentButtonChild {
    NativeDocumentBackupButtonChild(List events, Map lifecycle, Map nativeState, Map typedSettings) {
        super(events, lifecycle, nativeState, typedSettings)
    }
    String exportAppData(List ids) {
        assert ids == [42L]
        events << 'export'
        JsonOutput.toJson([appReplacements: ['42': [appTypeNamespace: 'hubitat', appTypeName: 'Button Rule-5.1',
            parentAppInstalledAppId: '28', appLabel: 'Button fixture']], deviceReplacements: [:],
            appData: ['42': [state: nativeState, appSettings: [], subscriptions: []]]])
    }
}
