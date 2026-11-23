package server

import groovy.json.JsonOutput
import support.ToolSpecBase
import support.TestChildApp
import spock.lang.Shared

class NativeDocumentsSpec extends ToolSpecBase {
    @Shared TestChildApp serverApp = new TestChildApp(id: 7L, label: 'MCP')

    void setupSpec() { appExecutor.getApp() >> serverApp }

    List events
    List renders
    Map pageBody
    Map nativeState
    boolean failAction
    Boolean broken
    Map lifecycle
    Map typedSettings
    List statusSettings
    Map labels

    void setup() {
        statusSettings = []
        labels = [:]
        events = []
        renders = []
        pageBody = null
        failAction = false
        broken = false
        typedSettings = [:]
        lifecycle = [stopped: false, paused: false]
        nativeState = [actionList: ['3', '1'], actions: ['3': [method: 'getLogMsg'], '1': [method: 'getLogMsg']],
                       capabsfalse: ['4': 'Switch is on'], capabstrue: ['5': 'Switch changed'], eval: ['0': ['4']], ndx: 6, actNdx: 4,
                       allLocalVars: [counter: [type: 'integer', value: 0]]]
        settingsMap.enableMandatoryBPS = false
        // Snapshot behavior has dedicated NativeRuleEditBackupSpec coverage.
        settingsMap.backupBeforeRuleEdits = false
        stateMap.lastBackupTimestamp = 1234567890000L
        script.metaClass.getAppByAppId = { Long id -> id == 28L ? [id: 28L, appType: [namespace: 'hubitat', name: 'Rule Machine']] : [id: 42L, parentAppId: 28L, appType: [namespace: 'hubitat', name: 'Rule-5.1'], installed: true] }
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, int t = 30, boolean retry = false -> events << 'create'; [status: 302, location: '/installedapp/configure/42'] }
        def spec = this
        script.metaClass._nrHandle = { Long id ->
            if (id != 42L) throw new IllegalArgumentException('missing rule')
            def child = new NativeDocumentChild(events, lifecycle, nativeState.allLocalVars, typedSettings)
            // The rule's own broken() check, which health reads in place of the compiled flag.
            child.brokenCheck = { -> spec.broken }
            child.labelText = { String kind -> def text = spec.labels.get(kind); (text instanceof Closure ? text() : text) ?: '' }
            child.stateOf = { -> spec.nativeState }
            child
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
        // /app/ruleBuilderJson serializes the rule's whole state, and statusJson lists the same state as appState rows.
        hubGet.register('/app/ruleBuilderJson/42') { q -> JsonOutput.toJson(nativeState + [broken: broken] + lifecycle) }
        hubGet.register('/installedapp/statusJson/42') { q -> JsonOutput.toJson([eventSubscriptions: lifecycle.stopped ? [] : [[name: 'switch']], scheduledJobs: [],
            installedApp: [id: 42, label: 'Doc rule', disabled: false, installed: true, appType: [name: 'Rule-5.1', namespace: 'hubitat']],
            appSettings: statusSettings + (typedSettings.useST == true ? [[name: 'useST', type: 'bool', value: 'true', deviceIdsForDeviceList: [], deviceList: null]] : []), appState: (nativeState + lifecycle).collect { k, v -> [name: k, value: v] }]) }
        hubGet.register('/installedapp/configure/json/42') { q -> renders << 'mainPage'; pageJson() }
        script.metaClass.hubInternalPostFormRaw = { String path, String body, int t = 420, boolean retry = false -> events << [path: path, body: body]; lifecycle.stopped = false; lifecycle.paused = false; [status: 200] }
    }

    // The rule's main page as /installedapp/configure/json renders it.
    private String pageJson() {
        JsonOutput.toJson([app: [id: 42, label: 'Doc rule', name: 'Rule-5.1', disabled: false, appType: [namespace: 'hubitat', name: 'Rule-5.1']],
            configPage: [name: 'mainPage', sections: [[title: '', input: [], body: pageBody ? [pageBody] : []]]],
            settings: [useST: typedSettings.useST == true]])
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
        def actionPages = (0..1).collect { offset -> script.toolSetRule([operation: 'read', ruleId: 42,
            args: [sections: ['actions'], cursor: offset.toString(), limit: 1]]) }
        def conditionPages = (0..2).collect { offset -> script.toolSetRule([operation: 'read', ruleId: 42,
            args: [sections: ['conditions'], cursor: offset.toString(), limit: 1]]) }
        def settingPages = (0..1).collect { offset -> script.toolSetRule([operation: 'read', ruleId: 42,
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
        def first = script.toolGetRuleDocument([ruleId: 42, sections: ['conditions'], limit: 1])
        nativeState.capabsfalse = ['1': 'one', '2': 'two']
        def reordered = script.toolGetRuleDocument([ruleId: 42, sections: ['conditions'], limit: 1])
        nativeState.capabsfalse['2'] = 'changed outside page'
        def changed = script.toolGetRuleDocument([ruleId: 42, sections: ['conditions'], limit: 1])
        then:
        first.conditions == reordered.conditions && first.conditions == changed.conditions
        first.page.revision == reordered.page.revision
        first.page.revision != changed.page.revision
        first.page.offset == 0 && first.page.limit == 1
        !script.toolGetRuleDocument([ruleId: 42, sections: ['conditions']]).containsKey('page')
    }

    def 'paging defaults return an explicit final cursor including an empty section'() {
        given:
        nativeState.actions = [:]
        nativeState.actionList = []
        when:
        def result = script.toolGetRuleDocument([ruleId: 42, sections: ['actions'], cursor: ''])
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
        script.toolSetRule([operation: 'read', ruleId: 42, args: args])
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
        script.toolSetRule([operation: 'read', ruleId: 42, args: [sections: ['actions'], cursor: cursor]])
        then:
        thrown(IllegalArgumentException)
        where:
        cursor << ['2', '99', '2147483648']
    }

    def 'byte bounded pages account for unicode and JSON text escaping without dropping rows'() {
        given:
        String text = ('\u263a"\\\n' * 90)
        nativeState.capabsfalse = ['1': text, '2': text, '3': text]
        def unpaged = script.toolGetRuleDocument([ruleId: 42, sections: ['conditions']])
        script.metaClass._responseSizeLimitBytes = { -> 5000 }
        List pages = []
        String cursor = ''
        when:
        while (cursor != null) {
            def result = script.toolSetRule([operation: 'read', ruleId: 42,
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
        script.toolSetRule([operation: 'read', ruleId: 42, args: [sections: ['conditions'], cursor: '', limit: 1]])
        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('exceeds the response size limit')
        JsonOutput.toJson(nativeState) == before
        events.empty
    }

    def 'read document returns native stable order typed values and locals, rendering no page'() {
        when:
        def result = script.executeTool('hub_set_rule', [operation: 'read', ruleId: 42, args: [:]])
        then:
        result.ruleId == 42L
        !result.containsKey('rule_id')
        result.health.ok == true
        result.health.ruleFormat == 'rm'
        !result.containsKey('validation') && !result.containsKey('nativeHealth')
        renders == []
        result.actions*.id == ['3', '1']
        result.settings.find { it.name == 'flag' }.value == false
        result.settings.find { it.name == 'count' }.value == 0
        result.variables.counter.value == 0
        events.empty
    }

    @spock.lang.Unroll
    def 'hub_get_rule returns the same document with health and the local variables as their own list (useGateways=#useGateways)'() {
        given:
        settingsMap.enableRead = true
        settingsMap.useGateways = useGateways
        nativeState.allLocalVars.doorCode = [type: 'string', value: '4321']
        when:
        def response = mcpDriver.callTool('hub_get_rule', [ruleId: 42])
        def inner = mcpDriver.parseInner(response)
        def read = script.toolGetRuleDocument([ruleId: 42])
        then:
        !response.result.isError
        inner.ruleId == 42
        inner.health.ok == true
        inner.health.privateBoolean == null
        inner.localVariables == [[name: 'counter', type: 'integer', value: 0], [name: 'doorCode', type: 'string', value: null, redacted: true]]
        !inner.containsKey('variables')
        inner.actions*.id == read.actions*.id
        inner.conditions == read.conditions
        inner.triggers == read.triggers
        inner.expressions == read.expressions
        inner.settings*.name == read.settings*.name
        !JsonOutput.toJson(inner).contains('4321')
        events.empty
        where:
        useGateways << [true, false]
    }

    def 'hub_get_rule pages one section with its health first, leaving the local variables to a page of their own'() {
        when:
        def first = script.toolGetNativeRule([ruleId: 42, sections: ['actions'], cursor: '', limit: 1])
        def second = script.toolGetNativeRule([ruleId: 42, sections: ['actions'], cursor: first.nextCursor, limit: 1])
        def locals = script.toolGetNativeRule([ruleId: 42, sections: ['localVariables'], cursor: '', limit: 10])
        then:
        first.actions*.id == ['3']
        second.actions*.id == ['1']
        second.nextCursor == null
        [first, second].every { it.keySet().first() == 'health' && !it.containsKey('localVariables') && !it.containsKey('conditions') }
        locals.keySet().first() == 'health'
        locals.localVariables*.name == ['counter']
        locals.page.total == 1 && locals.nextCursor == null
        !locals.containsKey('actions')
    }

    def 'hub_get_rule over the response cap keeps health first, pages the first part that does not fit and returns the rest whole'() {
        given: "twelve 10 KB string locals, more than one response holds"
        nativeState.allLocalVars = (1..12).collectEntries { n -> [(String.format('big%02d', n)): [type: 'string', value: 'x' * 10000]] }
        when:
        def first = script.toolGetNativeRule([ruleId: 42])
        List pages = [first]
        while (pages[-1].nextCursor != null) pages << script.toolGetNativeRule([ruleId: 42, sections: ['localVariables'], cursor: pages[-1].nextCursor])
        then: 'health comes first and the small parts after the locals still come back whole'
        first.keySet().first() == 'health'
        first.health.ok == true
        first.pagedSection == 'localVariables'
        first.omittedSections == []
        first.actions*.id == ['3', '1']
        first.settings*.name == ['flag', 'count']
        first.note.contains('pagedSection') && !first.note.contains('sections:[]')
        first.localVariables.size() < 12
        and: 'every page carries the health and fits the cap, and the pages together hold every local once, in order'
        pages.size() > 1
        pages.every { it.health != null && script._ndEnvelopeBytes(it) <= script._responseSizeLimitBytes() - 1024 }
        pages.collectMany { it.localVariables*.name } == (1..12).collect { n -> String.format('big%02d', n) }
    }

    def 'a part after the paged one that does not fit either is listed in omittedSections, through the dispatcher (useGateways=#useGateways)'() {
        given: 'the locals and the settings are each too large to send with the other'
        settingsMap.enableRead = true
        settingsMap.useGateways = useGateways
        nativeState.allLocalVars = (1..12).collectEntries { n -> [("big${n}".toString()): [type: 'string', value: 'x' * 10000]] }
        hubGet.register('/installedapp/settings/42') { q -> JsonOutput.toJson([settings: (1..12).collect { n -> [name: "text${n}".toString(), type: 'text', multiple: false, value: 'y' * 10000] }]) }
        when:
        def response = useGateways ? mcpDriver.callTool('hub_read_rules', [tool: 'hub_get_rule', args: [ruleId: 42]]) : mcpDriver.callTool('hub_get_rule', [ruleId: 42])
        def first = mcpDriver.parseInner(response)
        then:
        first.keySet().first() == 'health'
        first.pagedSection == 'localVariables'
        first.omittedSections == ['settings']
        !first.containsKey('settings')
        first.actions*.id == ['3', '1']
        !first.containsKey('response_too_large')
        where:
        useGateways << [false, true]
    }

    // A long rule's whole document can exceed the response cap, so its health must stay readable on its own.
    def 'hub_get_rule with no sections returns the health and local variables alone'() {
        given:
        settingsMap.enableRead = true
        when:
        def inner = mcpDriver.parseInner(mcpDriver.callTool('hub_get_rule', [ruleId: 42, sections: []]))
        then:
        inner.keySet() as List == ['health', 'ruleId', 'localVariables']
        inner.health.ok == true
        inner.localVariables*.name == ['counter']
    }

    def 'hub_get_rule reports a failed local-variable read beside the health it read, not as no locals'() {
        given:
        script.metaClass._nrHandle = { Long id -> new NativeDocumentChild(events, lifecycle, null, typedSettings) }
        when:
        def result = script.toolGetNativeRule([ruleId: 42, sections: ['actions']])
        then:
        result.success == null
        result.containsKey('localVariables') && result.localVariables == null
        result.localVariablesError.contains("Rule 42's local variables could not be read")
        result.health.ok == true
        result.actions*.id == ['3', '1']
    }

    def 'hub_get_rule reports an unexpected local-variable shape instead of an empty list'() {
        given:
        script.metaClass._nrHandle = { Long id -> new Expando(getAllLocalVars: { -> 'unexpected' }) }
        when:
        def result = script.toolGetNativeRule([ruleId: 42, sections: ['actions']])
        then:
        result.localVariables == null
        result.localVariablesError.contains('unexpected shape')
        result.health != null
    }

    def 'health does not read a status page with no appState as a rule never paused or stopped'() {
        given:
        hubGet.register('/installedapp/statusJson/42') { q -> '{}' }
        when:
        def health = script.toolGetNativeRule([ruleId: 42, sections: []]).health
        then:
        health.stopped == null
        health.privateBoolean == null
        !health.ok
        health.checkErrors.any { it.contains('carried no appState') }
    }

    def 'hub_get_rule(sections: []) reads health and locals without the native state'() {
        given:
        script.metaClass._nrState = { Long id -> throw new IllegalStateException('Native state is unavailable') }
        when:
        def result = script.toolGetNativeRule([ruleId: 42, sections: []])
        then:
        result.success == null
        result.health.ok == true
        result.localVariables*.name == ['counter']
    }

    def 'hub_get_rule still fails when a section it was asked for needs the native state'() {
        given:
        script.metaClass._nrState = { Long id -> throw new IllegalStateException('Native state is unavailable') }
        when:
        def result = script.toolGetNativeRule([ruleId: 42, sections: ['triggers']])
        then:
        result.success == false
        result.error.contains('Native state is unavailable')
        !result.note.contains('Nothing was changed')
    }

    // Rule Machine's broken-row marker, as its label methods and page draw it.
    private static String marker(String kind) { "<span class='text-hubitat-danger'>**Broken ${kind}**</span>".toString() }

    // Pre-write refusals run with pre-edit backups on, so a check moved below the backup fails here.
    private List backupsOn() {
        settingsMap.backupBeforeRuleEdits = true
        def backups = []
        script.metaClass._rmBackupRuleSnapshot = { Integer id, String reason, boolean force -> backups << id; [backupKey: 'k'] }
        backups
    }

    def 'an update whose broken check fails is refused before its backup and any write, with a note that does not say retry'() {
        given: "the compiled flag says not broken, but the rule's own check, the one health reads, fails"
        def backups = backupsOn()
        broken = null
        when:
        def result = script.toolUpdateRuleDocument([ruleId: 42, confirm: true, changes: [logChange()]])
        then:
        result.success == false
        result.partial == false
        result.broken == null
        result.error.contains('broken flag could not be read before the write')
        result.error.contains('no change was written')
        result.checkErrors == ["the rule's broken() check returned null, not true or false"]
        result.note.contains('hub_get_rule') && result.note.contains("operation:'form'")
        !result.note.toLowerCase().contains('retry')
        backups.isEmpty()
        events.empty
        renders.isEmpty()
    }

    def 'hub_get_rule lists a rule with no locals as an empty list'() {
        given:
        nativeState.allLocalVars.clear()
        expect:
        script.toolGetNativeRule([ruleId: 42, sections: ['actions']]).localVariables == []
    }

    def 'native password and credential-named settings are redacted without rendering or altering false and zero'() {
        given:
        String secret = 'native-password-secret'
        hubGet.register('/installedapp/settings/42') { q -> JsonOutput.toJson([settings: [
            [name: 'credential', type: 'password', value: secret],
            [name: 'apiKey', type: 'text', value: 'text-typed-api-key'],
            [name: 'lockCode', type: 'number', value: 4321],
            [name: 'passwordHint', type: 'text', value: 'visible hint'],
            [name: 'flag', type: 'bool', value: false],
            [name: 'count', type: 'number', value: 0]
        ]]) }
        when:
        def result = script.toolGetRuleDocument([ruleId: 42, sections: ['settings']])
        then:
        def credential = result.settings.find { it.name == 'credential' }
        credential.value == null
        credential.redacted == true
        !JsonOutput.toJson(result).contains(secret)
        ['apiKey', 'lockCode'].every { name -> result.settings.find { it.name == name }.with { it.value == null && it.redacted == true } }
        !JsonOutput.toJson(result).contains('text-typed-api-key') && !JsonOutput.toJson(result).contains('4321')
        result.settings.find { it.name == 'passwordHint' }.value == 'visible hint'
        result.settings.findAll { !(it.name in ['credential', 'apiKey', 'lockCode']) }.every { !it.containsKey('redacted') }
        result.settings.find { it.name == 'flag' }.value == false
        result.settings.find { it.name == 'count' }.value == 0
        events.isEmpty()
    }

    def 'credential-named settings and local variables read as stored with Hide passwords and credentials off, hidden by default'() {
        given:
        hubGet.register('/installedapp/settings/42') { q -> JsonOutput.toJson([settings: [[name: 'apiKey', type: 'text', value: 'key-1']]]) }
        nativeState.allLocalVars.doorCode = [type: 'string', value: '4321']
        when:
        def hidden = script.toolGetRuleDocument([ruleId: 42, sections: ['settings', 'variables']])
        settingsMap.redactCredentials = false
        def shown = script.toolGetRuleDocument([ruleId: 42, sections: ['settings', 'variables']])
        then:
        hidden.settings[0].value == null && hidden.settings[0].redacted == true
        hidden.variables.doorCode.value == null && hidden.variables.doorCode.redacted == true
        hidden.variables.counter.value == 0 && !hidden.variables.counter.containsKey('redacted')
        shown.settings[0].value == 'key-1' && !shown.settings[0].containsKey('redacted')
        shown.variables.doorCode.value == '4321'
    }

    def 'disabled native required expression ignores stale predicate flags without rendering'() {
        given:
        typedSettings.useST = false
        nativeState.hasPredicate = true
        nativeState.isPredicate = false
        nativeState.predCapabs = [4]
        nativeState.eval = [:]
        when:
        def document = script.toolGetRuleDocument([ruleId: 42, sections: ['expressions']])
        then:
        document.health.structuralIssues.isEmpty()
        nativeState.hasPredicate == true
        nativeState.eval.isEmpty()
        events.isEmpty()
        when:
        def result = script.toolUpdateRuleDocument([ruleId: 42, changes: [[part: 'variable', value: [operation: 'set', name: 'counter', value: 0]]], confirm: true])
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
        def document = script.toolGetRuleDocument([ruleId: 42, sections: ['expressions']])
        then:
        document.health.structuralIssues.any { it.contains(expected) }
        events.isEmpty()
        when:
        def result = script.toolUpdateRuleDocument([ruleId: 42, changes: [[part: 'variable', value: [operation: 'set', name: 'counter', value: 0]]], confirm: true])
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
        def result = script.toolGetRuleDocument([ruleId: 42, sections: ['actions']])
        then:
        result.actions*.id == ['3', '1']
        !result.containsKey('settings')
        !result.containsKey('variables')
    }

    def 'unknown change fails before every mutation'() {
        when:
        script.toolUpdateRuleDocument([ruleId: 42, changes: [[part: 'action', value: [type: 'messageActs', subtype: 'getLogMsg', fields: []]], [part: 'state', value: [unsafe: true]]], confirm: true])
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
        script.toolCreateRuleDocument([parentId: 28, name: 'Repeated create', changes: [logChange(), repeated], confirm: true])
        then:
        def created = thrown(IllegalArgumentException)
        created.message == 'changes[1]: Native inputs must not be repeated'
        events.empty
        when:
        script.toolUpdateRuleDocument([ruleId: 42, changes: [logChange(), repeated], confirm: true])
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
        def result = script.toolUpdateRuleDocument([ruleId: 42, changes: [logChange(), missing], confirm: true])
        then:
        !result.success && !result.partial
        result.error == 'changes[1]: Native row does not exist'
        events.empty
    }

    def 'a variable set carrying a type is refused before any write'() {
        when:
        script.toolUpdateRuleDocument([ruleId: 42, confirm: true, changes: [
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
        def result = script.toolUpdateRuleDocument([ruleId: 42, changes: [[part: 'action', value: [type: 'messageActs', subtype: 'getLogMsg', fields: []]], logChange()], confirm: true])
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
        def result = script.toolUpdateRuleDocument([ruleId: 42, confirm: true, changes: [
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
        def result = script.toolUpdateRuleDocument([ruleId: 42, confirm: true, changes: [logChange()]])
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
        def result = script.toolUpdateRuleDocument([ruleId: 42, confirm: true, changes: [logChange()]])
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
        def result = script.toolUpdateRuleDocument([ruleId: 42, changes: [[part: 'action', value: [type: 'messageActs', subtype: 'getLogMsg', fields: []]]], confirm: true])
        then:
        result.success
        result.ruleId == 42L
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
        def result = script.toolUpdateRuleDocument([ruleId: 42, changes: [[part: 'action', value: [type: 'messageActs', subtype: 'getLogMsg', fields: []]]], confirm: true])

        then:
        result.success
        result.ruleId == 42L
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
        def result = script.toolUpdateRuleDocument([ruleId: 42, confirm: true, changes: [logChange()]])
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
        def result = script.toolUpdateRuleDocument([ruleId: 42, changes: [[part: 'action', value: [id: '3', type: 'messageActs', subtype: 'getLogMsg', fields: []]]], confirm: true])

        then:
        result.success == !brokenEditor
        result.ruleId == 42L
        events.take(3) == ['action', 'selectTriggers', 'selectActionsX']
        events.contains('selectActions')
        events.any { it instanceof Map && it.path == '/installedapp/update/json' } == !brokenEditor
        !events.contains('mainPage')
        !events.contains('initState')
        !events.contains('selectActionsWithParams')
        nativeState == before

        where:
        brokenEditor << [false, true]
    }

    def 'a rule that is already broken is refused before its backup and any change, naming each broken row by kind and native id'() {
        given: "two triggers whose devices were deleted; the compiled flag still reads false, the rule's own check true"
        def backups = backupsOn()
        broken = true
        labels.trigger = marker('Trigger')
        nativeState.capabstrue = ['5': 'Porch switch turns on', '6': 'Hall switch turns on']
        statusSettings = [[name: 'tDev5', type: 'capability.switch', value: null, deviceIdsForDeviceList: [], deviceList: null],
                          [name: 'tDev6', type: 'capability.switch', value: null, deviceIdsForDeviceList: [], deviceList: null],
                          [name: 'reltDevice6', type: 'capability.switch', value: null, deviceIdsForDeviceList: [], deviceList: null]]
        hubGet.register('/app/ruleBuilderJson/42') { q -> JsonOutput.toJson(nativeState + [broken: false] + lifecycle) }
        when:
        def result = script.toolUpdateRuleDocument([ruleId: 42, changes: [logChange()], confirm: true])
        then:
        !result.success
        !result.partial
        result.broken
        result.brokenRows == [[kind: 'trigger', id: '5', setting: 'tDev5', text: 'Porch switch turns on'],
                              [kind: 'trigger', id: '6', setting: 'reltDevice6', text: 'Hall switch turns on'],
                              [kind: 'trigger', id: '6', setting: 'tDev6', text: 'Hall switch turns on']]
        result.error == "Rule 42 is already broken: trigger 5 ('Porch switch turns on'); trigger 6 ('Hall switch turns on'); no change was written."
        result.note.contains("operation:'form'")
        backups.isEmpty()
        events.empty
        renders.isEmpty()
        nativeState.actionList == ['3', '1']
    }

    def 'a rule whose page marks a condition (its device deleted) is updated, reporting the rows marked before the write'() {
        given: "Rule Machine's broken() says false; the condition and the action it gates carry the marker, as the Hubitat UI shows them"
        def backups = backupsOn()
        labels.putAll(condition: marker('Condition'), action: 'IF (' + marker('Condition') + ") Log: 'gated'")
        nativeState.inUseConds = ['4']
        nativeState.actions = ['3': [method: 'getLogMsg', cond: '4'], '1': [method: 'getLogMsg']]
        Map picker = [name: 'rDev_4', type: 'capability.switch', value: null, deviceIdsForDeviceList: [], deviceList: null]
        statusSettings = [picker]
        hubGet.register('/installedapp/settings/42') { q -> JsonOutput.toJson([settings: [picker]]) }
        when:
        def result = script.toolUpdateRuleDocument([ruleId: 42, changes: [logChange()], confirm: true])
        then:
        result.success == true
        result.broken == false
        result.brokenRowsBefore == [[kind: 'condition', id: '4', setting: 'rDev_4', text: 'Switch is on'], [kind: 'action', id: null]]
        backups == [42]
        events.contains('action')
    }

    def 'an update that adds a row Rule Machine newly marks fails after the write, naming the kind and its marks'() {
        given: "the condition is already marked; the write gates one more action on it, so the action labels carry one more marker"
        def backups = backupsOn()
        labels.putAll(condition: marker('Condition'), action: { -> events.contains('action') ? marker('Condition') + marker('Condition') : marker('Condition') })
        nativeState.inUseConds = ['4']
        when:
        def result = script.toolUpdateRuleDocument([ruleId: 42, changes: [logChange()], confirm: true])
        then:
        !result.success
        result.partial
        result.error.contains('The write adds rows Rule Machine marks broken: action (2 marked, 1 before)')
        result.brokenRowsBefore == [[kind: 'condition', id: null], [kind: 'action', id: null]]
        backups == [42]
        !events.any { it instanceof Map && it.path == '/installedapp/update/json' }
    }

    def 'a row newly marked within a kind is named by its id even when the kind keeps its marker count'() {
        given: "before the write condition 4's picker is empty; after it, condition 4 is repaired and condition 2's device is gone"
        nativeState.capabsfalse = ['4': 'Switch is on', '2': 'Lamp is on']
        nativeState.inUseConds = ['2', '4']
        labels.condition = marker('Condition')
        Closure picker = { String id -> [name: "rDev_${id}".toString(), type: 'capability.switch', value: null, deviceIdsForDeviceList: [], deviceList: null] }
        hubGet.register('/installedapp/settings/42') { q -> JsonOutput.toJson([settings: [events.contains('action') ? picker('2') : picker('4')]]) }
        when:
        def result = script.toolUpdateRuleDocument([ruleId: 42, changes: [logChange()], confirm: true])
        then:
        !result.success
        result.error.contains('The write adds rows Rule Machine marks broken: condition 2')
    }

    def 'a create whose rule Rule Machine marks fails, since a new rule has no marked rows before it'() {
        given:
        labels.action = marker('Action')
        when:
        def result = script.toolCreateRuleDocument([parentId: 28, name: 'Marked', changes: [logChange()], confirm: true])
        then:
        !result.success
        result.partial
        result.error.contains('The write adds rows Rule Machine marks broken: action (1 marked, 0 before)')
    }

    def 'an update whose status page carries no appSettings is refused before its backup and any write, as settings that went unread'() {
        given: "the status page answers without an appSettings list, so condition 4's picker was never read"
        def backups = backupsOn()
        nativeState.actions = ['3': [method: 'getLogMsg', cond: '4'], '1': [method: 'getLogMsg']]
        hubGet.register('/installedapp/statusJson/42') { q -> JsonOutput.toJson([eventSubscriptions: [[name: 'switch']], scheduledJobs: [],
            installedApp: [id: 42, label: 'Doc rule', disabled: false, installed: true, appType: [name: 'Rule-5.1', namespace: 'hubitat']],
            appState: (nativeState + lifecycle).collect { k, v -> [name: k, value: v] }]) }
        when:
        def result = script.toolUpdateRuleDocument([ruleId: 42, changes: [logChange()], confirm: true])
        then:
        !result.success
        !result.partial
        result.broken == false
        result.error == "Rule 42's marked rows could not be read before the write, so a newly marked row would go unnoticed; no change was written."
        result.checkErrors.any { it == "the rule's settings went unread, so brokenRows was not listed" }
        backups.isEmpty()
        events.empty
    }

    def 'the document read reports a condition Rule Machine marks without calling the rule broken'() {
        given: "condition 4 is in Rule Machine's list of conditions in use, and its labels mark it"
        labels.condition = marker('Condition')
        nativeState.inUseConds = ['4']
        statusSettings = [[name: 'rDev_4', type: 'capability.switch', value: null, deviceIdsForDeviceList: [], deviceList: null]]
        when:
        def health = script.toolGetRuleDocument([ruleId: 42, sections: []]).health
        then:
        health.broken == false
        health.ok == false
        health.brokenRows == [[kind: 'condition', id: '4', setting: 'rDev_4', text: 'Switch is on']]
        health.issues == ["Rule Machine marks 1 row(s) on the rule's page (brokenRows) but does not call the rule broken; an update that marks no new row is allowed."]
        renders.isEmpty()
    }

    @spock.lang.Unroll
    def 'an empty picker on a row that is not in use is not broken (#label)'() {
        given: "the Required Expression is off, so condition 4 is in use only when an action references it"
        nativeState.actions = ['3': [method: 'getLogMsg'], '1': [method: 'getLogMsg'], '8': [method: 'getLogMsg', cond: '4']]
        statusSettings = [[name: setting, type: 'capability.switch', value: null, deviceIdsForDeviceList: [], deviceList: null]]
        when:
        def health = script.toolGetRuleDocument([ruleId: 42, sections: []]).health
        then:
        health.brokenRows == []
        health.broken == false
        health.ok == true
        where:
        label                                              | setting
        'a condition no action or expression uses'         | 'rDev_4'
        'a trigger picker for a row not yet added'         | 'tDev9'
        'an action row outside the action list'            | 'onOffSwitch.8'
    }

    def 'a condition Rule Machine lists as in use is found from its emptied picker, a null device list reading as no devices'() {
        given:
        labels.condition = marker('Condition')
        nativeState.inUseConds = [4]
        statusSettings = [[name: 'rDev_4', type: 'capability.switch', value: null, deviceIdsForDeviceList: null, deviceList: null]]
        when:
        def health = script.toolGetRuleDocument([ruleId: 42, sections: []]).health
        then:
        health.broken == false
        health.brokenRows == [[kind: 'condition', id: '4', setting: 'rDev_4', text: 'Switch is on']]
    }

    def 'an update whose compiled state cannot be read is refused before its backup and any write, though its broken check answers'() {
        given:
        def backups = backupsOn()
        hubGet.register('/app/ruleBuilderJson/42') { q -> throw new RuntimeException('read timed out') }
        when:
        def result = script.toolUpdateRuleDocument([ruleId: 42, changes: [logChange()], confirm: true])
        then:
        !result.success
        !result.partial
        result.broken == false
        result.error.contains('compiled state could not be read before the write')
        result.checkErrors.any { it.contains('compiled-state read failed') && it.contains('read timed out') }
        !result.note.toLowerCase().contains('retry')
        backups.isEmpty()
        events.empty
    }

    def 'an update whose App Status read fails is refused before its backup and any write, since a deleted condition device would go unseen'() {
        given: "broken() and the compiled state both answer, as they do for a deleted condition device; only the settings read fails"
        def backups = backupsOn()
        hubGet.register('/installedapp/statusJson/42') { q -> throw new RuntimeException('read timed out') }
        when:
        def result = script.toolUpdateRuleDocument([ruleId: 42, changes: [logChange()], confirm: true])
        then:
        !result.success
        !result.partial
        result.broken == false
        result.error == "Rule 42's marked rows could not be read before the write, so a newly marked row would go unnoticed; no change was written."
        result.checkErrors.any { it == "the rule's settings went unread, so brokenRows was not listed" }
        result.note.contains('brokenRows')
        backups.isEmpty()
        events.empty
    }

    def 'a broken rule whose compiled state cannot be read is refused as already broken, before its backup and any write'() {
        given:
        def backups = backupsOn()
        broken = true
        hubGet.register('/app/ruleBuilderJson/42') { q -> throw new RuntimeException('read timed out') }
        when:
        def result = script.toolUpdateRuleDocument([ruleId: 42, changes: [logChange()], confirm: true])
        then:
        !result.success
        result.broken == true
        result.error == 'Rule 42 is already broken; no change was written.'
        result.checkErrors.any { it.contains('brokenRows was not listed') }
        result.note.contains("operation:'form'")
        backups.isEmpty()
        events.empty
    }

    def 'the document read reports broken from the rule\'s own check without rendering, while the compiled flag still reads false'() {
        given:
        broken = true
        hubGet.register('/app/ruleBuilderJson/42') { q -> JsonOutput.toJson(nativeState + [broken: false] + lifecycle) }
        when:
        def document = script.toolGetRuleDocument([ruleId: 42, sections: ['actions']])
        then:
        document.health.broken == true
        document.health.ok == false
        renders.isEmpty()
        events.empty
    }

    def 'the hub_set_rule read keeps its health when the local variables cannot be read'() {
        given:
        nativeState.allLocalVars = null
        when:
        def document = script.toolSetRule([operation: 'read', ruleId: 42, args: [sections: ['variables', 'actions']]])
        then:
        document.success == null
        document.health.ok == true
        document.containsKey('variables') && document.variables == null
        document.localVariablesError.contains('could not be read')
        document.actions*.id == ['3', '1']
    }

    def 'a paged hub_set_rule read of the variables section reports a failed locals read instead of paging it'() {
        given:
        nativeState.allLocalVars = null
        when:
        def document = script.toolSetRule([operation: 'read', ruleId: 42, args: [sections: ['variables'], cursor: '']])
        then:
        document.health.ok == true
        document.variables == null
        document.localVariablesError
        !document.containsKey('page')
    }

    def 'a verified write whose document read-back fails says so in its own key, not as a nested document'() {
        given:
        def reads = 0
        def realRead = script.&toolGetRuleDocument
        script.metaClass.toolGetRuleDocument = { Map args -> reads++; [success: false, ruleId: 42L, error: 'Rule 42 could not be read: boom', note: 'retrying once is safe'] }
        when:
        def result = script.toolUpdateRuleDocument([ruleId: 42, changes: [logChange()], confirm: true])
        then:
        result.success == true
        reads == 1
        !result.containsKey('document')
        result.documentReadError == 'Rule 42 could not be read: boom'
        result.note.contains('written and verified') && result.note.contains('Do not send them again')
    }

    def 'a verified write whose document is too large to send keeps its receipt and backup key and leaves the document out'() {
        given:
        def backups = backupsOn()
        script.metaClass.toolGetRuleDocument = { Map args -> [ruleId: 42L, actions: [[id: '1', text: 'x' * 200000]]] }
        when:
        def result = script.toolUpdateRuleDocument([ruleId: 42, changes: [logChange()], confirm: true])
        then:
        result.success == true
        result.ruleId == 42L
        result.backup.backupKey == 'k'
        backups == [42]
        result.documentOmitted == true
        !result.containsKey('document')
        result.note.contains('written and verified') && result.note.contains('Do not send them again') && result.note.contains('nextCursor')
    }

    def 'a verified write whose document fits returns it inline'() {
        given:
        script.metaClass.toolGetRuleDocument = { Map args -> [ruleId: 42L, actions: [[id: '1', text: 'x' * 1000]]] }
        when:
        def result = script.toolUpdateRuleDocument([ruleId: 42, changes: [logChange()], confirm: true])
        then:
        result.success == true
        result.document.actions[0].text.size() == 1000
        !result.containsKey('documentOmitted')
    }

    def 'a verified write whose document read-back throws is still a successful write with the read error beside it'() {
        given: "the read-back's rule lookup throws instead of returning a failed read"
        script.metaClass.toolGetRuleDocument = { Map args -> throw new IllegalStateException('lookup timed out') }
        when:
        def result = script.toolUpdateRuleDocument([ruleId: 42, changes: [logChange()], confirm: true])
        then:
        result.success == true
        !result.containsKey('document')
        result.documentReadError == 'lookup timed out'
        result.note.contains('written and verified') && result.note.contains('Do not send them again')
    }

    def 'a broken health result does not fail the document read'() {
        given:
        broken = true
        when:
        def document = script.toolGetRuleDocument([ruleId: 42, sections: ['actions']])
        then:
        document.health.broken == true
        document.actions*.id == ['3', '1']
        !document.containsKey('success')
    }

    def 'Stop Repeating in an IF / Repeat / END-REP / ELSE / END-IF sequence reads with no structural issue'() {
        given:
        nativeState.actionList = ['1', '2', '3', '4', '5', '6', '7']
        nativeState.actions = ['1': [method: 'getIfThen', rule: '1'], '2': [method: 'getRepeat'], '3': [method: 'getLogMsg'],
                               '4': [method: 'getEndRepeat'], '5': [method: 'getElse'], '6': [method: 'getStopRepeat'], '7': [method: 'getEndIf']]
        nativeState.eval = ['1': ['4']]
        when:
        def document = script.toolGetRuleDocument([ruleId: 42, sections: ['actions']])
        then:
        document.health.structuralIssues == []
        document.health.ok == true
    }

    def 'a runtime failure in the document read returns success false with a recovery note instead of throwing'() {
        given:
        script.metaClass._nrState = { Long id -> throw new IllegalStateException('Native state is unavailable') }
        when:
        def result = script.executeTool('hub_set_rule', [operation: 'read', ruleId: 42, args: [:]])
        then:
        result.success == false
        result.ruleId == 42L
        result.error.contains('Native state is unavailable')
        result.note.contains("hub_get_tool_guide(section='builtin_app_tools_rules')")
    }

    def 'native time triggers use scheduled jobs as activation evidence'() {
        given:
        nativeState.capabstrue = ['5': 'When time is 11:59 PM']
        List jobs = scheduled ? [[handler: 'allHandlerT', status: 'PENDING',
                                 nextRunTime: '2026-10-21T03:59:00+0000']] : []
        hubGet.register('/installedapp/statusJson/42') { q ->
            JsonOutput.toJson([installedApp: [id: 42, installed: true], appSettings: [],
                              eventSubscriptions: null, scheduledJobs: jobs])
        }
        when:
        def result = script.toolUpdateRuleDocument([ruleId: 42, confirm: true,
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
            JsonOutput.toJson([installedApp: [id: 42, installed: true], appSettings: [],
                              eventSubscriptions: subscriptions, scheduledJobs: []])
        }
        when:
        def result = script.toolUpdateRuleDocument([ruleId: 42, confirm: true,
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
            JsonOutput.toJson([installedApp: [id: 42, installed: true], appSettings: [],
                              eventSubscriptions: [], scheduledJobs: []])
        }
        when:
        def result = script.toolUpdateRuleDocument([ruleId: 42, confirm: true,
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
        def result = script.toolUpdateRuleDocument([ruleId: 42, changes: [[part: 'delete', value: [part: 'action', id: '1']], [part: 'order', value: [ids: ['3']]]], confirm: true])
        then:
        result.success
        events[0..1] == ['delete:action:1', 'order']
    }

    def 'invalid action order is refused before any change or render'() {
        when:
        def result = script.toolUpdateRuleDocument([ruleId: 42, changes: [[part: 'order', value: [ids: ['3']]]], confirm: true])
        then:
        !result.success
        !result.partial
        events.empty
        nativeState.actionList == ['3', '1']
    }

    def 'invalid typed scalar later in a batch cannot delete earlier actions'() {
        when:
        script.toolUpdateRuleDocument([ruleId: 42, confirm: true, changes: [
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
        def refused = script.toolUpdateRuleDocument([ruleId: 42, confirm: true, changes: [
            [part: 'delete', value: [part: 'action', id: '3']]
        ]])
        then:
        !refused.success
        !refused.partial
        refused.error.contains('END-IF')
        events.empty
        when:
        def accepted = script.toolUpdateRuleDocument([ruleId: 42, confirm: true, changes: [
            [part: 'delete', value: [part: 'action', id: '1']],
            [part: 'delete', value: [part: 'action', id: '3']]
        ]])
        then:
        accepted.success
        events[0..1] == ['delete:action:1', 'delete:action:3']
    }

    def 'unknown expression operands are refused before any change or render'() {
        when:
        def result = script.toolUpdateRuleDocument([ruleId: 42, changes: [[part: 'required', value: [tokens: ['999999']]]], confirm: true])
        then:
        !result.success
        !result.partial
        result.error.contains('unknown condition')
        events.empty
    }

    def 'unknown simple action condition refuses the complete batch before rendering'() {
        when:
        def result = script.toolUpdateRuleDocument([ruleId: 42, changes: [
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
        def result = script.toolUpdateRuleDocument([ruleId: 42, changes: [
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
        def result = script.toolUpdateRuleDocument([ruleId: 42, confirm: true, changes: [
            [part: 'delete', value: [part: 'action', id: '1']],
            [part: 'action', value: logChange().value + [id: '1']]
        ]])
        then:
        !result.success
        !result.partial
        result.error.contains('Native action does not exist')
        events.empty
    }

    def 'an action deleted earlier in the batch stays refused for edits after an append'() {
        when:
        def result = script.toolUpdateRuleDocument([ruleId: 42, confirm: true, changes: [
            logChange(),
            [part: 'delete', value: [part: 'action', id: '1']],
            [part: 'action', value: logChange().value + [id: '1']]
        ]])
        then:
        !result.success
        !result.partial
        result.error.contains('changes[2]: Native action does not exist')
        events.empty
    }

    def 'rows deleted after an append stay gone: a second delete of the same action or condition is refused before any write'() {
        given:
        nativeState.capabsfalse = ['4': 'Switch is on', '8': 'Switch is off']
        def condition = [part: 'condition', value: [capability: 'Switch', fields: [
            [name: 'rDev_#', type: 'capability.switch', value: ['789']], [name: 'state_#', type: 'enum', value: 'on']]]]
        when:
        def actions = script.toolUpdateRuleDocument([ruleId: 42, confirm: true, changes: [
            logChange(),
            [part: 'delete', value: [part: 'action', id: '1']],
            [part: 'delete', value: [part: 'action', id: '1']]
        ]])
        then:
        !actions.success
        !actions.partial
        actions.error == 'changes[2]: Native action does not exist'
        events.empty
        when:
        script._ndPreflight(42L, script._ndChanges([
            condition,
            [part: 'delete', value: [part: 'condition', id: '8']],
            [part: 'condition', value: condition.value + [id: '8']]
        ]))
        then:
        def conditions = thrown(IllegalArgumentException)
        conditions.message == 'changes[2]: Native row does not exist'
    }

    def 'one appended action leaves room for one unknown action id: a second unknown delete is refused before any write'() {
        when:
        def result = script.toolUpdateRuleDocument([ruleId: 42, confirm: true, changes: [
            logChange(),
            [part: 'delete', value: [part: 'action', id: '4']],
            [part: 'delete', value: [part: 'action', id: '5']]
        ]])
        then:
        !result.success
        !result.partial
        result.error == 'changes[2]: Native action does not exist'
        events.empty
    }

    def 'an appended trigger lets a later change name one unknown trigger id as its placeholder, never two'() {
        given:
        def trigger = [part: 'trigger', value: [capability: 'Switch', fields: [
            [name: 'tDev#', type: 'capability.switch', value: ['789']], [name: 'tstate#', type: 'enum', value: 'on']]]]
        when:
        script._ndPreflight(42L, script._ndChanges([trigger, [part: 'trigger', value: trigger.value + [id: '6']]]))
        then:
        noExceptionThrown()
        when:
        script._ndPreflight(42L, script._ndChanges([
            trigger,
            [part: 'delete', value: [part: 'trigger', id: '6']],
            [part: 'delete', value: [part: 'trigger', id: '7']]
        ]))
        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == 'changes[2]: Native row does not exist'
    }

    def 'an unknown id outside the ids the appends will take is refused before any write, even after an append'() {
        given:
        def condition = [part: 'condition', value: [capability: 'Switch',
            fields: [[name: 'rDev_#', type: 'capability.switch', value: ['7']], [name: 'state_#', type: 'enum', value: 'on']]]]
        when:
        script._ndPreflight(42L, script._ndChanges([condition, [part: 'required', value: [tokens: ['999999']]]]))
        then:
        def refused = thrown(IllegalArgumentException)
        refused.message == 'changes[1]: Expression references an unknown condition'
        when: 'the id the append will take is accepted'
        script._ndPreflight(42L, script._ndChanges([condition, [part: 'required', value: [tokens: ['6']]]]))
        then:
        noExceptionThrown()
        when: 'the id an appended trigger will take is not a condition'
        def trigger = [part: 'trigger', value: [capability: 'Switch', fields: [
            [name: 'tDev#', type: 'capability.switch', value: ['789']], [name: 'tstate#', type: 'enum', value: 'on']]]]
        script._ndPreflight(42L, script._ndChanges([trigger, [part: 'required', value: [tokens: ['6']]]]))
        then:
        def wrongKind = thrown(IllegalArgumentException)
        wrongKind.message == 'changes[1]: Expression references an unknown condition'
    }

    def 'appended rows take the ids Rule Machine counters give them: ndx for triggers and conditions, actNdx for actions'() {
        given: 'the condition counter is ahead of the highest condition, as after its highest row was deleted'
        nativeState.ndx = 9
        nativeState.actNdx = 7
        def condition = [part: 'condition', value: [capability: 'Switch', fields: [
            [name: 'rDev_#', type: 'capability.switch', value: ['789']], [name: 'state_#', type: 'enum', value: 'on']]]]
        when:
        script._ndPreflight(42L, script._ndChanges([condition, [part: 'required', value: [tokens: ['9']]]]))
        then:
        noExceptionThrown()
        when: 'the next id above the highest row is not the one Rule Machine gives'
        script._ndPreflight(42L, script._ndChanges([condition, [part: 'required', value: [tokens: ['6']]]]))
        then:
        def stale = thrown(IllegalArgumentException)
        stale.message == 'changes[1]: Expression references an unknown condition'
        when: 'an appended action takes actNdx, which is not a condition'
        script._ndPreflight(42L, script._ndChanges([logChange(), [part: 'required', value: [tokens: ['7']]]]))
        then:
        def wrongKind = thrown(IllegalArgumentException)
        wrongKind.message == 'changes[1]: Expression references an unknown condition'
        when: 'a later change names the appended action by the actNdx id'
        script._ndPreflight(42L, script._ndChanges([logChange(), [part: 'delete', value: [part: 'action', id: '7']]]))
        then:
        noExceptionThrown()
        when: 'the next id above the highest action is not the one Rule Machine gives'
        script._ndPreflight(42L, script._ndChanges([logChange(), [part: 'delete', value: [part: 'action', id: '4']]]))
        then:
        def staleAction = thrown(IllegalArgumentException)
        staleAction.message.contains('Native action does not exist')
    }

    def 'an append refuses an unreadable counter on a rule with rows and a predicted id already in use'() {
        def condition = [part: 'condition', value: [capability: 'Switch', fields: [
            [name: 'rDev_#', type: 'capability.switch', value: ['789']], [name: 'state_#', type: 'enum', value: 'on']]]]
        when: 'the condition counter is missing on a rule that has conditions'
        nativeState.remove('ndx')
        script._ndPreflight(42L, script._ndChanges([condition]))
        then:
        def missing = thrown(IllegalArgumentException)
        missing.message.contains('row counter is unreadable')
        when: 'the action counter names an action the rule already has'
        nativeState.ndx = 6
        nativeState.actNdx = 3
        script._ndPreflight(42L, script._ndChanges([logChange()]))
        then:
        def taken = thrown(IllegalArgumentException)
        taken.message.contains('next action id 3 is already in use')
    }

    def "a rule's first Required Expression advances the condition counter, so a later append takes the next id"() {
        given:
        nativeState.eval = [:]
        def condition = [part: 'condition', value: [capability: 'Switch', fields: [
            [name: 'rDev_#', type: 'capability.switch', value: ['789']], [name: 'state_#', type: 'enum', value: 'on']]]]
        when:
        script._ndPreflight(42L, script._ndChanges([[part: 'required', value: [tokens: ['4']]], condition, [part: 'required', value: [tokens: ['7']]]]))
        then:
        noExceptionThrown()
        when:
        script._ndPreflight(42L, script._ndChanges([[part: 'required', value: [tokens: ['4']]], condition, [part: 'required', value: [tokens: ['6']]]]))
        then:
        def stale = thrown(IllegalArgumentException)
        stale.message == 'changes[2]: Expression references an unknown condition'
        when: 'a rule that already has one keeps its counter'
        nativeState.eval = ['0': ['4']]
        script._ndPreflight(42L, script._ndChanges([[part: 'required', value: [tokens: ['4']]], condition, [part: 'required', value: [tokens: ['6']]]]))
        then:
        noExceptionThrown()
    }

    def 'an appended END-IF named by its id and deleted in the same batch leaves its IF open, refused before any write'() {
        when:
        def result = script.toolUpdateRuleDocument([ruleId: 42, confirm: true, changes: [
            [part: 'action', value: [type: 'condActs', subtype: 'getIfThen', fields: [], expression: ['4']]],
            [part: 'terminator', value: [subtype: 'endIf']],
            [part: 'delete', value: [part: 'action', id: '5']]
        ]])
        then:
        !result.success
        !result.partial
        result.error.contains('Native IF block has no END-IF')
        events.empty
    }

    def 'a condition deleted after an append cannot be named by a later Required Expression'() {
        given:
        nativeState.capabsfalse = ['4': 'Switch is on', '8': 'Switch is off']
        def condition = [part: 'condition', value: [capability: 'Switch', fields: [
            [name: 'rDev_#', type: 'capability.switch', value: ['789']], [name: 'state_#', type: 'enum', value: 'on']]]]
        when:
        script._ndPreflight(42L, script._ndChanges([
            condition,
            [part: 'delete', value: [part: 'condition', id: '8']],
            [part: 'required', value: [tokens: ['8']]]
        ]))
        then:
        def error = thrown(IllegalArgumentException)
        error.message == 'changes[2]: Expression references an unknown condition'
    }

    def 'an IF retargeted to another condition frees the old condition for deletion in the same batch'() {
        given: 'Rule Machine lists condition 1 in use before the batch; the delete itself checks the list as it stands then'
        nativeState.inUseConds = ['1']
        nativeState.capabsfalse = ['1': 'Switch 789 on', '2': 'Switch 746 on']
        nativeState.actionList = ['1', '2', '3']
        nativeState.actions = ['1': [method: 'getIfThen', rule: 1], '2': [method: 'getLogMsg'], '3': [method: 'getEndIf']]
        nativeState.eval = ['1': ['1']]
        when:
        script._ndPreflight(42L, script._ndChanges([
            [part: 'action', value: [id: '1', type: 'condActs', subtype: 'getIfThen', fields: [], expression: ['2']]],
            [part: 'delete', value: [part: 'condition', id: '1']]
        ]))
        then:
        noExceptionThrown()
        when: 'a conditional action moved off a condition and then deleted no longer holds either condition'
        nativeState.actions['2'] = [method: 'getLogMsg', cond: '2']
        script._ndPreflight(42L, script._ndChanges([
            [part: 'action', value: [id: '2', type: 'messageActs', subtype: 'getLogMsg', condition: '1', fields: []]],
            [part: 'delete', value: [part: 'action', id: '2']],
            [part: 'delete', value: [part: 'condition', id: '2']]
        ]))
        then:
        noExceptionThrown()
    }

    def 'an existing conditional action moved to another condition lets the batch delete the old one'() {
        given:
        nativeState.inUseConds = ['8']
        nativeState.capabsfalse = ['4': 'Switch is on', '8': 'Switch is off']
        nativeState.actions = ['3': [method: 'getLogMsg'], '1': [method: 'getOnOffSwitch', cond: '8']]
        when:
        script._ndPreflight(42L, script._ndChanges([
            [part: 'action', value: [id: '1', type: 'switchActs', subtype: 'getOnOffSwitch', condition: '4', fields: []]],
            [part: 'delete', value: [part: 'condition', id: '8']]
        ]))
        then:
        noExceptionThrown()
    }

    def 'an expression written after an action edit in the same batch is the one a condition delete is checked against'() {
        given:
        nativeState.capabsfalse = ['1': 'Switch is on', '5': 'Switch is off']
        nativeState.actionList = ['1', '2']
        nativeState.actions = ['1': [method: 'getIfThen', rule: 1], '2': [method: 'getEndIf']]
        nativeState.eval = ['0': [], '1': ['1']]
        when:
        script._ndPreflight(42L, script._ndChanges([
            [part: 'action', value: [id: '1', type: 'condActs', subtype: 'getIfThen', fields: [], expression: ['1']]],
            [part: 'expression', value: [id: '1', tokens: ['5']]],
            [part: 'delete', value: [part: 'condition', id: '5']]
        ]))
        then:
        def error = thrown(IllegalArgumentException)
        error.message == 'changes[2]: Referenced native conditions must be detached before deletion'
    }

    def 'a condition freed by deleting its IF earlier in the batch deletes; alone, while Rule Machine lists it in use, it is refused'() {
        given:
        nativeState.inUseConds = ['4', '8']
        nativeState.capabsfalse = ['4': 'Switch is on', '8': 'Switch is off']
        nativeState.actionList = ['1', '2', '3']
        nativeState.actions = ['1': [method: 'getIfThen', rule: 1], '2': [method: 'getLogMsg'], '3': [method: 'getEndIf']]
        // Rule Machine keeps the eval entry of a deleted IF.
        nativeState.eval = ['0': ['4'], '1': ['8']]
        when:
        script._ndPreflight(42L, script._ndChanges([
            [part: 'delete', value: [part: 'action', id: '1']],
            [part: 'delete', value: [part: 'action', id: '3']],
            [part: 'delete', value: [part: 'condition', id: '8']]
        ]))
        then:
        noExceptionThrown()
        when:
        script._ndPreflight(42L, script._ndChanges([
            [part: 'delete', value: [part: 'condition', id: '8']]
        ]))
        then:
        def error = thrown(IllegalArgumentException)
        error.message == 'changes[0]: Referenced native conditions must be detached before deletion'
    }

    def 'an order after a new action in the same batch is refused before any write'() {
        when:
        def result = script.toolUpdateRuleDocument([ruleId: 42, confirm: true, changes: [
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
        def result = script.toolUpdateRuleDocument([ruleId: 42, confirm: true, changes: [
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
        def result = script.toolUpdateRuleDocument([ruleId: 42, changes: [
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
        script.toolUpdateRuleDocument([ruleId: 42, changes: [[part: 'action', value: [type: 'delayActs', subtype: 'getWaitRule', fields: []]]], confirm: true])
        then:
        thrown(IllegalArgumentException)
        events.empty
    }

    def 'a condition Rule Machine does not list as in use passes the batch check, whatever an unticked Conditional Trigger still names'() {
        given: "trigger 5's unticked box keeps condTrig.5 = 8; Rule Machine lists only condition 4"
        nativeState.capabsfalse = ['4': 'Switch is on', '8': 'Switch is off']
        nativeState.inUseConds = ['4']
        hubGet.register('/installedapp/settings/42') { q -> JsonOutput.toJson([settings: [[name: 'isCondTrig.5', type: 'bool', value: false], [name: 'condTrig.5', type: 'enum', value: '8']]]) }
        when:
        script._ndPreflight(42L, script._ndChanges([[part: 'delete', value: [part: 'condition', id: '8']]]))
        then:
        noExceptionThrown()
    }

    def 'a condition only a ticked Conditional Trigger uses is refused by the batch check, though inUseConds leaves it out'() {
        given: "trigger 5 is held and its box is ticked with condTrig.5 = 8; Rule Machine lists only condition 4"
        nativeState.capabsfalse = ['4': 'Switch is on', '8': 'Switch is off']
        nativeState.capabstrue = ['5': 'Switch B on']
        nativeState.inUseConds = ['4']
        hubGet.register('/installedapp/settings/42') { q -> JsonOutput.toJson([settings: [[name: 'isCondTrig.5', type: 'bool', value: true], [name: 'condTrig.5', type: 'enum', value: '8']]]) }
        when:
        script._ndPreflight(42L, script._ndChanges([[part: 'delete', value: [part: 'condition', id: '8']]]))
        then:
        def e = thrown(IllegalArgumentException)
        e.message == 'changes[0]: Referenced trigger conditions must be detached before deletion'
    }

    def 'a condition Rule Machine lists as in use is refused for deletion before any change or render'() {
        given:
        nativeState.inUseConds = ['4']
        when:
        def result = script.toolUpdateRuleDocument([ruleId: 42, changes: [[part: 'delete', value: [part: 'condition', id: '4']]], confirm: true])
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
        def result = script.toolUpdateRuleDocument([ruleId: 42, changes: [logChange()], confirm: true])
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
        def result = script.toolUpdateRuleDocument([ruleId: 42, changes: [[part: 'variable', value: [operation: 'set', name: 'counter', value: 0]]], confirm: true])
        then:
        !result.success
        result.error.contains('unfinished IF')
        !events.any { it instanceof Map }
    }

    def "Rule Machine's broken flag blocks Done, and a user's text that reads like its marker does not"() {
        given:
        script.metaClass._nrHandle = { Long id -> new NativeDocumentDiagnosticChild(events, lifecycle, true) }
        when:
        def failed = script.toolUpdateRuleDocument([ruleId: 42, changes: [logChange()], confirm: true])
        then:
        !failed.success
        failed.partial
        failed.broken == true
        failed.error.contains('health is broken')
        !events.any { it instanceof Map }
        when:
        events.clear()
        lifecycle.broken = false
        script.metaClass._nrHandle = { Long id -> new NativeDocumentDiagnosticChild(events, lifecycle, false) }
        def passed = script.toolUpdateRuleDocument([ruleId: 42, changes: [logChange()], confirm: true])
        then:
        passed.success
    }

    def 'an unrelated edit succeeds while an action Rule Machine marked before the write stays marked, and typed text is no marker (#markup)'() {
        given: 'action 1 is gated by condition 4, whose label Rule Machine can no longer draw'
        nativeState.actions['1'] = [method: 'getLogMsg', cond: 4]
        nativeState.inUseConds = ['4']
        labels.action = "IF (${markup}) Log: 'gated'".toString()
        when:
        def result = script.toolUpdateRuleDocument([ruleId: 42, changes: [logChange()], confirm: true])
        then:
        result.success == true
        result.brokenRowsBefore == (marked ? [[kind: 'action', id: null]] : null)
        where:
        markup                                                        | marked
        "<span class='text-hubitat-danger'>**Broken Condition**</span>" | true
        "<span style='color:red'>**Broken Condition**</span>"           | true
        "Switch is on) Log: '**Broken Condition** typed by the user'"   | false
    }

    def 'a broken marker on the rendered page does not block Done while the rule state reports healthy'() {
        given:
        def child = new NativeDocumentDiagnosticChild(events, lifecycle, true)
        child.raisesFlag = false
        script.metaClass._nrHandle = { Long id -> child }
        when:
        def result = script.toolUpdateRuleDocument([ruleId: 42, changes: [logChange()], confirm: true])
        then:
        result.success
        events.any { it instanceof Map && it.path == '/installedapp/update/json' }
    }

    def 'Done preserves an existing stopped and paused native rule using verified toggles'() {
        given:
        lifecycle.stopped = true
        lifecycle.paused = true
        when:
        def result = script.toolUpdateRuleDocument([ruleId: 42, changes: [logChange()], confirm: true])
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
        hubGet.register('/app/ruleBuilderJson/42') { q -> JsonOutput.toJson(nativeState + health) }
        hubGet.register('/installedapp/statusJson/42') { q -> JsonOutput.toJson([eventSubscriptions: [], scheduledJobs: [], appSettings: [],
            appState: health.collect { k, v -> [name: k, value: v] }]) }
        when:
        def document = script.toolGetRuleDocument([ruleId: 42, sections: ['actions']])
        then:
        document.health.get(flag) == null
        document.health.get(flag == 'stopped' ? 'paused' : 'stopped') == false
        events.empty
        when:
        def result = script.toolUpdateRuleDocument([ruleId: 42, changes: [logChange()], confirm: true])
        then:
        !result.success
        !result.partial
        result.error.contains('lifecycle state is malformed')
        events.empty
        nativeState.actionList == ['3', '1']
        where:
        flag      | malformed
        'stopped' | 'no'
        'stopped' | null
        'stopped' | 0
        'paused'  | 'yes'
        'paused'  | [:]
        'paused'  | []
    }

    def 'lifecycle flags stored as true or false text read as flags, as hub_list_rules reads them'() {
        given:
        lifecycle.stopped = 'true'
        lifecycle.paused = 'false'
        when:
        def document = script.toolGetRuleDocument([ruleId: 42, sections: ['actions']])
        then:
        document.health.stopped == true
        document.health.paused == false
        script._ndNativeFlag([stopped: 'true'], 'stopped') == true
        script._ndNativeFlag([paused: 'false'], 'paused') == false
    }

    def 'absent native lifecycle flags retain the platform false default'() {
        given:
        hubGet.register('/app/ruleBuilderJson/42') { q -> JsonOutput.toJson([broken: false]) }
        hubGet.register('/installedapp/statusJson/42') { q -> JsonOutput.toJson([eventSubscriptions: [[name: 'switch']], scheduledJobs: [], appSettings: [], appState: []]) }
        when:
        def document = script.toolGetRuleDocument([ruleId: 42, sections: ['actions']])
        then:
        document.health.stopped == false
        document.health.paused == false
        events.empty
        when:
        def result = script.toolUpdateRuleDocument([ruleId: 42, changes: [logChange()], confirm: true])
        then:
        result.success
        !result.stopped
        !result.paused
        events.any { it instanceof Map && it.body.contains('_action_update=Done') }
    }

    def 'missing native health refuses the update before anything is written'() {
        given:
        def backups = backupsOn()
        hubGet.register('/app/ruleBuilderJson/42') { q -> '{}' }
        when:
        def result = script.toolUpdateRuleDocument([ruleId: 42, changes: [logChange()], confirm: true])
        then:
        !result.success
        !result.partial
        result.error.contains('compiled state could not be read')
        result.checkErrors.any { it.contains('compiled-state read failed') }
        backups.isEmpty()
        !events.any { it instanceof Map }
    }

    def 'a rule whose stored broken flag is missing and whose broken check fails reads not ok, and the update refuses it the same way'() {
        given: "Rule Machine state without its broken key, and no answer from the rule's own check"
        def backups = backupsOn()
        broken = null
        hubGet.register('/app/ruleBuilderJson/42') { q -> JsonOutput.toJson(nativeState + lifecycle) }
        when:
        def health = script.toolGetRuleDocument([ruleId: 42, sections: []]).health
        def result = script.toolUpdateRuleDocument([ruleId: 42, changes: [logChange()], confirm: true])
        then:
        health.broken == null
        health.ok == false
        health.checkErrors == result.checkErrors
        !result.success
        result.error.contains('broken flag could not be read')
        backups.isEmpty()
        events.empty
    }

    def 'explicit null activation collections are empty only for the verified installed rule'() {
        given:
        nativeState.capabstrue = [:]
        hubGet.register('/installedapp/statusJson/42') { q -> JsonOutput.toJson([installedApp: [id: 42, installed: true], appSettings: [], eventSubscriptions: null, scheduledJobs: null]) }
        when:
        def result = script.toolUpdateRuleDocument([ruleId: 42, changes: [logChange()], confirm: true])
        then:
        result.success
        result.eventSubscriptions == []
    }

    def 'null activation collections cannot stand in for missing or mismatched rule identity'() {
        given:
        hubGet.register('/installedapp/statusJson/42') { q -> JsonOutput.toJson([installedApp: [id: 99, installed: true], appSettings: [], eventSubscriptions: null, scheduledJobs: null]) }
        when:
        def result = script.toolUpdateRuleDocument([ruleId: 42, changes: [logChange()], confirm: true])
        then:
        !result.success
        result.partial
        result.error.contains('cannot verify')
    }

    def 'create rejects deterministic changes before allocating a child'() {
        when:
        def result = script.toolCreateRuleDocument([parentId: 28, name: 'Rejected create', changes: changes, confirm: true])

        then:
        !result.success && !result.partial
        !result.ruleId
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
        def result = script.executeTool('hub_set_rule', [operation: 'create', confirm: true, args: [parentId: 28, name: 'New document', changes: [logChange()]]])
        then:
        result.success
        result.created
        result.ruleId == 42L
        events[0..3] == ['create', 'initState', 'origLabel:New document', 'label:New document']
        events.find { it instanceof Map }.body.contains('_action_update=Done')
    }

    def 'empty create installs a native rule while empty update remains a prewrite refusal'() {
        given:
        nativeState = [actionList: [], actions: [:], capabsfalse: [:], capabstrue: [:],
                       eval: [:], allLocalVars: [:]]
        hubGet.register('/installedapp/statusJson/42') { q ->
            JsonOutput.toJson([installedApp: [id: 42, installed: true], appSettings: [],
                              eventSubscriptions: [], scheduledJobs: []])
        }
        when:
        def result = script.executeTool('hub_set_rule',
            [operation: 'create', confirm: true, args: [parentId: 28, name: 'Empty document', changes: []]])
        then:
        result.success
        result.created
        result.ruleId == 42L
        result.results == []
        result.document.actions == []
        result.document.conditions == [:]
        result.document.triggers == [:]
        events[0..3] == ['create', 'initState', 'origLabel:Empty document', 'label:Empty document']
        events.any { it instanceof Map && it.path == '/installedapp/update/json' }
        when:
        events.clear()
        script.toolUpdateRuleDocument([ruleId: 42, changes: [], confirm: true])
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
        def result = script.toolCreateRuleDocument([parentId: 28, name: 'Header child', changes: [logChange()], confirm: true])
        then:
        result.success
        result.ruleId == 42L
    }

    def 'create refuses an unexpected child parent before changing its state or label'() {
        given:
        script.metaClass.getAppByAppId = { Long id -> id == 28L ? [id: 28L, appType: [namespace: 'hubitat', name: 'Rule Machine']] : [id: 42L, parentAppId: 99L, appType: [namespace: 'hubitat', name: 'Rule-5.1']] }
        when:
        def result = script.toolCreateRuleDocument([parentId: 28, name: 'Wrong child', changes: [logChange()], confirm: true])
        then:
        !result.success
        result.partial
        !result.created
        result.ruleId == null
        events == ['create']
    }

    def 'invalid parent prevents native child creation'() {
        when:
        script.toolCreateRuleDocument([parentId: 42, name: 'Bad parent', changes: [logChange()], confirm: true])
        then:
        thrown(IllegalArgumentException)
        events.empty
    }

    def 'read and write masters and confirmation apply through real dispatcher'() {
        when:
        settingsMap.enableRead = false
        script.executeTool('hub_set_rule', [operation: 'read', ruleId: 42, args: [:]])
        then:
        thrown(IllegalArgumentException)
        when:
        settingsMap.enableRead = true
        settingsMap.enableWrite = false
        script.executeTool('hub_set_rule', [operation: 'update', ruleId: 42, confirm: true, args: [changes: []]])
        then:
        thrown(IllegalArgumentException)
        when:
        settingsMap.enableWrite = true
        def probe = script.executeTool('hub_set_rule', [operation: 'update', ruleId: 42, confirm: false, args: [changes: [logChange()]]])
        then:
        probe.confirmRequired
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
    Closure brokenCheck = { -> false }
    // Rule Machine's own label methods (the text its page shows, markers included) and its state, where its label
    // code keeps inUseConds.
    Closure labelText = { String kind -> '' }
    Closure stateOf = { -> [:] }
    Object triggerLabel() { labelText('trigger') }
    Object conditionLabel() { labelText('condition') }
    Object actionLabel() { labelText('action') }
    Object getState() { stateOf() }
    Object getSetting(String name) { typedSettings.get(name) }
    Map getAllLocalVars() { locals }
    Object broken() { brokenCheck() }
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
    boolean raisesFlag = true
    NativeDocumentDiagnosticChild(List events, Map lifecycle, boolean diagnostic) { super(events, lifecycle); this.diagnostic = diagnostic }
    // Rendering a broken rule sets Rule Machine's broken flag; the page marker's markup varies by firmware.
    Object selectActions(Map params) {
        events << 'selectActions'
        if (diagnostic && raisesFlag) lifecycle.broken = true
        [name: 'selectActions', sections: [[input: [[name: 'logmsg.1', value: '**Broken Condition**']], body: [[type: 'paragraph', description: diagnostic ? "<span class='text-hubitat-danger'>**Broken Condition**</span>" : 'Log: **Broken Condition**']]]]]
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
    Object getState() { nativeState }
    Object selectActionsX() {
        events << 'selectActionsX'
        if (nativeState.actNdx == null && !nativeState.actions && !nativeState.actionList) nativeState.actNdx = 1
        if (brokenEditor) lifecycle.broken = true
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
