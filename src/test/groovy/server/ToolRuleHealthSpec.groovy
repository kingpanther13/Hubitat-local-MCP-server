package server

import support.ToolSpecBase
import groovy.json.JsonOutput

/**
 * The rule health report (_rmCheckRuleHealth), returned as `health` by hub_get_rule and by
 * hub_set_rule(operation:'read'). An app with a classic page has it rendered once, then a rule's
 * compiled state (GET /app/ruleBuilderJson, the rule's whole app state) is read once, then one App
 * Status read (/installedapp/statusJson, appState as a LIST of {name, value}) supplies paused,
 * stopped, privateBoolean and the live subscription and job counts. Visual Rules report their
 * engine's own verdict; other apps report their page.
 *
 * hub_get_rule on a Rule Machine 5.1 rule returns the document as well; that path is covered in
 * NativeDocumentsSpec, which carries the native handle fixtures.
 *
 * Mocking: hubInternalGet is routed to hubGet.register(path) by HarnessSpec, and the closure
 * returns a JSON STRING. Unstubbed paths throw inside the helper's try/catch.
 */
class ToolRuleHealthSpec extends ToolSpecBase {

    def setup() {
        settingsMap.enableRead = true
        appType('Rule-5.1')
    }

    // ---------- fixtures ----------

    private void appType(String name, String namespace = 'hubitat') {
        script.metaClass.getAppByAppId = { Long id -> [id: id, label: 'BAT-RH-test', appType: [namespace: namespace, name: name]] }
    }

    /** The live /app/ruleBuilderJson body of a new Rule-5.1 rule, trimmed. */
    private static Map ruleState(Map overrides = [:]) {
        [installed: true, broken: false, private: 'true', condOper: 'cond',
         capabstrue: [:], capabsfalse: [:], eval: [:], actionList: [], actions: [:],
         allLocalVars: [:], disabledActions: []] + overrides
    }

    /** configure/json shape of a rendered page. */
    private String configJson(int id, Map opts = [:]) {
        JsonOutput.toJson([
            app: [id: id, label: opts.label ?: 'BAT-RH-test', name: opts.typeName ?: 'Rule-5.1', installed: true,
                  disabled: opts.disabled ?: false, appType: [name: opts.typeName ?: 'Rule-5.1', namespace: opts.namespace ?: 'hubitat']],
            configPage: [name: 'mainPage', title: 'Edit Rule', install: true, error: opts.error,
                         sections: [[title: '', input: [], body: opts.body ?: []]]],
            settings: opts.settings ?: [:], childApps: []
        ])
    }

    /** statusJson as the hub serves it: appState is a list of {name, value}, one entry per state key. */
    private static String statusJson(int id, Map state, Map extra = [:]) {
        JsonOutput.toJson([installedApp: [id: id], appSettings: [],
                           eventSubscriptions: [[name: 'switch']], scheduledJobs: [],
                           appState: state.collect { k, v -> [name: k, value: v] }] + extra)
    }

    /** One rule whose compiled state and App Status page carry the same state, as on a hub. */
    private void seed(int id, Map stateOverrides = [:], Map page = [:]) {
        Map state = ruleState(stateOverrides)
        hubGet.register("/app/ruleBuilderJson/${id}".toString()) { JsonOutput.toJson(state) }
        hubGet.register("/installedapp/configure/json/${id}".toString()) { configJson(id, page) }
        hubGet.register("/installedapp/statusJson/${id}".toString()) { statusJson(id, state) }
    }

    private int count(String path) { hubGet.calls.count { it.path == path } }

    // ---------- Rule Machine ----------

    def "a healthy rule reports its flags from one page render, one compiled read and one status read"() {
        given:
        seed(100)

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.ok == true
        h.unreadable == false
        h.ruleFormat == 'rm'
        h.broken == false
        h.brokenRows == []
        h.paused == false
        h.stopped == false
        h.privateBoolean == true
        h.disabledActions == []
        h.disabledTriggers == []
        h.disabled == false
        h.label == 'BAT-RH-test'
        h.structuralIssues == []
        h.issues == []
        h.checkErrors == []
        h.eventSubscriptionCount == 1
        h.scheduledJobCount == 0

        and:
        count('/installedapp/configure/json/100') == 1
        count('/app/ruleBuilderJson/100') == 1
        count('/installedapp/statusJson/100') == 1
        hubGet.calls.size() == 3
    }

    def "every report field is present from the start, null until read"() {
        given: "nothing can be read"
        hubGet.register('/installedapp/configure/json/100') { throw new IOException('down') }
        hubGet.register('/app/ruleBuilderJson/100') { throw new IOException('down') }
        hubGet.register('/app/ruleBuilder20Json/100') { throw new IOException('down') }
        hubGet.register('/installedapp/statusJson/100') { throw new IOException('down') }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        ['privateBoolean', 'disabledActions', 'disabledTriggers', 'paused', 'stopped', 'broken',
         'eventSubscriptionCount', 'scheduledJobCount'].every { h.containsKey(it) && h.get(it) == null }
        h.brokenRows == []
    }

    def "the rule's page is rendered before its compiled state is read, so a render-time broken flag is reported"() {
        given: "Rule Machine sets broken only when the page renders"
        boolean rendered = false
        hubGet.register('/installedapp/configure/json/100') {
            rendered = true
            configJson(100, [body: [[element: 'href', title: 'Select Trigger Events', description: 'BAT_E2E_SW turns on **Broken Trigger**']]])
        }
        hubGet.register('/app/ruleBuilderJson/100') { JsonOutput.toJson(ruleState([broken: rendered])) }
        hubGet.register('/installedapp/statusJson/100') { statusJson(100, ruleState([broken: rendered])) }

        when:
        def h = script._rmCheckRuleHealth(100)
        def paths = hubGet.calls*.path

        then:
        paths.indexOf('/installedapp/configure/json/100') < paths.indexOf('/app/ruleBuilderJson/100')
        h.broken == true
        h.ok == false
        h.brokenRows == [[kind: 'trigger', text: 'BAT_E2E_SW turns on']]
        h.issues.any { it.contains('compiled broken:true') && it.contains('brokenRows') && !it.contains('hub_set_rule') }
    }

    def "brokenRows names each row the page marks, from page links and paragraphs"() {
        given:
        seed(100, [broken: true], [body: [
            [element: 'href', title: 'Select Trigger Events',
             description: 'Mode becomes Day<br>Switch <b>Porch</b> turns on <span style="color:red">**Broken Trigger**</span>'],
            [element: 'paragraph', description: 'IF (Contact &amp; Door open) THEN<br>**Broken Condition**<br>Log &lt;done&gt; **Broken Action**'],
            [element: 'input', name: 'x', description: '**Broken Action** in an input is not a row']]])

        when:
        def rows = script._rmCheckRuleHealth(100).brokenRows

        then: "a marker on its own line belongs to the row above it"
        rows == [[kind: 'trigger', text: 'Switch Porch turns on'],
                 [kind: 'condition', text: 'IF (Contact & Door open) THEN'],
                 [kind: 'action', text: 'Log <done>']]
    }

    @spock.lang.Unroll
    def "privateBoolean reads the stored private flag #stored as #expected"() {
        given:
        seed(100, [private: stored])

        expect:
        script._rmCheckRuleHealth(100).privateBoolean == expected

        where:
        stored  | expected
        'true'  | true
        'false' | false
        true    | true
        false   | false
        null    | null
        'maybe' | null
    }

    def "privateBoolean comes from the App Status read, not a second state read"() {
        given: "the status page and the compiled state disagree; only the status page is the flag's source"
        hubGet.register('/app/ruleBuilderJson/100') { JsonOutput.toJson(ruleState([private: true])) }
        hubGet.register('/installedapp/configure/json/100') { configJson(100) }
        hubGet.register('/installedapp/statusJson/100') { statusJson(100, ruleState([private: false])) }

        expect:
        script._rmCheckRuleHealth(100).privateBoolean == false
    }

    @spock.lang.Unroll
    def "disabledActions and disabledTriggers are normalised alike (#stored)"() {
        given:
        seed(100, stored)

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.disabledActions == actions
        h.disabledTriggers == triggers
        h.ok == (errors == 0)
        h.checkErrors.size() == errors

        where:
        stored                                             | actions     | triggers | errors
        [disabledActions: ['3', 5], disabledTriggers: [2]] | ['3', '5']  | ['2']    | 0
        [disabledActions: null]                            | []          | []       | 0
        [disabledActions: 'oops']                          | null        | []       | 1
        [disabledTriggers: 7]                              | []          | null     | 1
        [disabledActions: [:], disabledTriggers: 'x']      | null        | null     | 2
    }

    @spock.lang.Unroll
    def "stopped is read from the app's state with the parser hub_list_rules uses (#stored -> #expected)"() {
        given:
        seed(100, stored)

        expect:
        script._rmCheckRuleHealth(100).stopped == expected

        where:
        stored              | expected
        [:]                 | false
        [stopped: true]     | true
        [stopped: 'true']   | true
        [stopped: false]    | false
        [stopped: 'false']  | false
        [stopped: 'x']      | null
    }

    @spock.lang.Unroll
    def "paused reads the app's state, then the compiled default (#stored -> #expected)"() {
        given:
        seed(100, stored)

        expect:
        script._rmCheckRuleHealth(100).paused == expected

        where:
        stored              | expected
        [:]                 | false
        [paused: true]      | true
        [paused: 'true']    | true
        [paused: false]     | false
        [paused: null]      | null
        [paused: 'invalid'] | null
    }

    def "an unclosed IF in the compiled actions is a structural issue pointing at the terminator change and hub_get_rule"() {
        given:
        seed(100, [actionList: ['1', '2'], actions: ['1': [method: 'getIfThen', rule: '1'], '2': [method: 'getLogMsg']],
                   eval: ['1': []]])

        when:
        def h = script._rmCheckRuleHealth(100)
        def text = h.issues.find { it.startsWith('structural imbalance') }

        then:
        h.ok == false
        h.structuralIssues.contains('Native IF block has no END-IF')
        text.contains("hub_set_rule(operation='update')")
        text.contains('terminator')
        text.contains("hub_get_rule(sections:['actions'])")
    }

    def "a balanced IF / ELSE / END-IF and REPEAT / END-REP read clean"() {
        given:
        seed(100, [actionList: ['1', '2', '3', '4', '5', '6'], eval: ['1': ['1']], capabsfalse: ['1': [capab: 'Switch']],
                   actions: ['1': [method: 'getIfThen', rule: '1'], '2': [method: 'getRepeat'], '3': [method: 'getEndRepeat'],
                             '4': [method: 'getElse'], '5': [method: 'getLogMsg'], '6': [method: 'getEndIf']]])

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.structuralIssues == []
        h.ok == true
    }

    def "Stop Repeating inside an IF / Repeat / END-REP / ELSE / END-IF sequence is a leaf, not a closer"() {
        given:
        seed(100, [actionList: ['1', '2', '3', '4', '5', '6', '7'], eval: ['1': ['1']], capabsfalse: ['1': [capab: 'Switch']],
                   actions: ['1': [method: 'getIfThen', rule: '1'], '2': [method: 'getRepeat'], '3': [method: 'getLogMsg'],
                             '4': [method: 'getEndRepeat'], '5': [method: 'getElse'], '6': [method: 'getStopRepeat'],
                             '7': [method: 'getEndIf']]])

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.structuralIssues == []
        h.issues == []
        h.ok == true
    }

    def "a Required Expression switched on with none committed is a structural issue"() {
        given:
        seed(100, [:], [settings: [useST: true]])

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.structuralIssues.any { it.startsWith('Native expression 0') }
        h.ok == false
    }

    @spock.lang.Unroll
    def "a #typeName rule takes the same path as any rule; a value the hub does not return reads null, not a failed check"() {
        given: "an older rule type stores no broken flag, so its state is not recognized as compiled Rule Machine state"
        appType(typeName)
        hubGet.register('/app/ruleBuilderJson/100') { JsonOutput.toJson([lvList: [], private: true]) }
        hubGet.register('/app/ruleBuilder20Json/100') { '{"success":false}' }
        hubGet.register('/installedapp/configure/json/100') { configJson(100, [typeName: typeName]) }
        hubGet.register('/installedapp/statusJson/100') { statusJson(100, [private: true]) }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.ruleFormat == 'rm'
        h.broken == null
        h.disabledActions == null
        h.structuralIssues == []
        h.privateBoolean == true
        h.stopped == false
        h.checkErrors == []
        h.issues == []
        h.ok == true

        where:
        typeName << ['Rule-4.1', 'Rule-5.0', 'Button Rule-4.1']
    }

    def "a rule whose compiled read fails still reports ruleFormat rm"() {
        given:
        seed(100)
        hubGet.register('/app/ruleBuilderJson/100') { throw new IOException('403') }
        hubGet.register('/app/ruleBuilder20Json/100') { throw new IOException('403') }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.ruleFormat == 'rm'
        h.checkErrors.any { it.contains('compiled-state read failed') }
        h.unreadable == false
        h.broken == null
        h.ok == false
    }

    def "the page's *BROKEN* label, broken markers and its error are issues"() {
        given:
        seed(100, [:], [label: 'Porch *BROKEN*', error: 'For input string',
                        body: [[element: 'paragraph', description: 'Log x **Broken Action**'],
                               [element: 'href', description: 'Switch on **Broken Condition**']]])

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.ok == false
        h.issues.any { it.contains('*BROKEN*') }
        h.issues.any { it.contains('2 row(s) broken') }
        h.brokenRows*.kind == ['action', 'condition']
        h.issues.any { it.contains('For input string') }
    }

    @spock.lang.Unroll
    def "the label drops only real runtime decoration markup (#label)"() {
        given:
        seed(100, [:], [label: label])

        expect:
        script._rmCheckRuleHealth(100).label == expected

        where:
        label                                                     | expected
        'Porch Light <span style="color:green">(Stopped)</span>'   | 'Porch Light'
        'Porch <span>(Stopped)</span> <span>(Paused)</span>'       | 'Porch'
        'Alert When Stopped (Stopped)'                            | 'Alert When Stopped (Stopped)'
        'Living Room (Paused)'                                    | 'Living Room (Paused)'
    }

    def "the red-X disabled flag comes from the rendered page"() {
        given:
        seed(100, [:], [disabled: true])

        expect:
        script._rmCheckRuleHealth(100).disabled == true
    }

    def "a Button Rule is read as a Rule Machine rule"() {
        given:
        appType('Button Rule-5.1')
        seed(100)

        expect:
        script._rmCheckRuleHealth(100).ruleFormat == 'rm'
    }

    // ---------- read failures ----------

    def "a page that fails to render keeps ok false even when the compiled state reads clean"() {
        given:
        seed(100)
        hubGet.register('/installedapp/configure/json/100') { throw new IOException('page timeout') }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.ok == false
        h.unreadable == false
        h.broken == false
        h.issues == []
        h.checkErrors.any { it.contains('configPage read failed') && it.contains('page timeout') }
    }

    def "a failed status read leaves the flags it carries null and names the read"() {
        given:
        seed(100)
        hubGet.register('/installedapp/statusJson/100') { throw new IOException('status timeout') }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.ok == false
        h.unreadable == false
        h.issues == []
        h.eventSubscriptionCount == null
        h.stopped == null
        h.privateBoolean == null
        h.paused == false
        h.checkErrors == ['statusJson: status timeout']
    }

    def "a status page whose appState is not a list of entries is a failed status read"() {
        given:
        seed(100)
        hubGet.register('/installedapp/statusJson/100') { JsonOutput.toJson([appState: 'oops', eventSubscriptions: [], scheduledJobs: []]) }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.checkErrors.any { it.startsWith('statusJson:') && it.contains('appState') }
        h.ok == false
    }

    def "nothing readable is unreadable, not checked-and-broken"() {
        given:
        hubGet.register('/installedapp/configure/json/100') { throw new IOException('down') }
        hubGet.register('/app/ruleBuilderJson/100') { throw new IOException('down') }
        hubGet.register('/app/ruleBuilder20Json/100') { throw new IOException('down') }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.ok == false
        h.unreadable == true
        h.ruleFormat == 'rm'
        h.issues.any { it.contains("neither the app's page nor its compiled state") }
        h.checkErrors.any { it.startsWith('statusJson:') }
    }

    def "a schedule-only rule reports zero live counts, not null"() {
        given:
        seed(100)
        hubGet.register('/installedapp/statusJson/100') { JsonOutput.toJson([installedApp: [id: 100], appSettings: [], appState: []]) }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.eventSubscriptionCount == 0
        h.scheduledJobCount == 0
        !h.checkErrors.any { it.startsWith('statusJson:') }
    }

    // ---------- Visual Rules and classic apps ----------

    @spock.lang.Unroll
    def "a Visual Rule reports its own verdict, pause and name without rendering a page (#format, paused=#paused)"() {
        given:
        appType('Visual Rule Builder 2.0')
        def payload = [name: 'Visual (Paused)', rulePaused: paused]
        hubGet.register('/app/ruleBuilderJson/100') {
            JsonOutput.toJson(format == 'vrb-classic' ? payload + [whenNodes: [], thenNodes: []] : [:])
        }
        hubGet.register('/app/ruleBuilder20Json/100') { JsonOutput.toJson(payload + [ruleJson: [:], validationErrors: []]) }
        hubGet.register('/installedapp/statusJson/100') { statusJson(100, [:]) }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.ruleFormat == format
        h.paused == paused
        h.label == 'Visual (Paused)'
        h.ok == true
        h.eventSubscriptionCount == 1
        count('/installedapp/configure/json/100') == 0
        h.privateBoolean == null

        where:
        format        | paused
        'vrb-classic' | false
        'vrb-classic' | true
        'vrb-graph'   | false
        'vrb-graph'   | true
    }

    def "a graph Visual Rule with validation errors is broken"() {
        given:
        appType('Visual Rule Builder 2.0')
        hubGet.register('/app/ruleBuilderJson/100') { '{}' }
        hubGet.register('/app/ruleBuilder20Json/100') { JsonOutput.toJson([name: 'V', ruleJson: [:], validationErrors: ['Trigger device missing']]) }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.ruleFormat == 'vrb-graph'
        h.broken == true
        h.ok == false
        h.validationErrors == ['Trigger device missing']
        h.issues.any { it.contains('Trigger device missing') }
    }

    def "a Visual Rule whose definition cannot be read says so, not that a page failed"() {
        given:
        appType('Visual Rule Builder 2.0')
        hubGet.register('/app/ruleBuilderJson/100') { throw new IOException('down') }
        hubGet.register('/app/ruleBuilder20Json/100') { throw new IOException('down') }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.unreadable == true
        h.issues.any { it.contains("the Visual Rule's definition could not be read") }
        !h.issues.any { it.contains('page') }
    }

    @spock.lang.Unroll
    def "a built-in classic app is classified by its type without a compiled read: #typeName -> #expected"() {
        given:
        appType(typeName)
        hubGet.register('/installedapp/configure/json/100') { configJson(100, [typeName: typeName]) }
        hubGet.register('/installedapp/statusJson/100') { statusJson(100, [:]) }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.ruleFormat == expected
        h.broken == null
        h.ok == true
        count('/app/ruleBuilderJson/100') == 0
        h.privateBoolean == null
        h.stopped == null

        where:
        typeName                | expected
        'Basic Rule-1.0'        | 'basic-rule'
        'Button Controller-5.1' | 'button-controller'
        'Notifier-1.0'          | 'classic-app'
    }

    @spock.lang.Unroll
    def "a classic app keeps paused and stopped from its state, with the name decoration as evidence (#label, #state)"() {
        given:
        appType('Room Lighting-1.0')
        hubGet.register('/installedapp/configure/json/100') { configJson(100, [typeName: 'Room Lighting-1.0', label: label]) }
        hubGet.register('/installedapp/statusJson/100') { statusJson(100, state) }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.label == 'Kitchen'
        h.paused == paused
        h.stopped == stopped

        where:
        label                                 | state             | paused | stopped
        'Kitchen <span>(Paused)</span>'        | [:]               | true   | null
        'Kitchen <span>(Paused)</span>'        | [paused: false]   | false  | null
        'Kitchen'                             | [paused: true]    | true   | null
        'Kitchen <span>(Stopped)</span>'       | [:]               | null   | true
        'Kitchen'                             | [stopped: 'true'] | null   | true
    }

    def "a user or community app is read like any app: its page renders and no compiled state is read"() {
        given:
        script.metaClass.getAppByAppId = { Long id -> [id: id, label: 'Package Manager', appType: [name: 'Hubitat Package Manager', namespace: 'dcm.hpm']] }
        hubGet.register('/installedapp/configure/json/100') { configJson(100, [typeName: 'Hubitat Package Manager', namespace: 'dcm.hpm', label: 'Package Manager']) }
        hubGet.register('/installedapp/statusJson/100') { statusJson(100, [:]) }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        count('/installedapp/configure/json/100') == 1
        count('/app/ruleBuilderJson/100') == 0
        h.ruleFormat == 'classic-app'
        h.label == 'Package Manager'
        h.checkErrors == []
        h.ok == true
    }

    def "an app without an app type takes its type from its page"() {
        given:
        script.metaClass.getAppByAppId = { Long id -> [id: id, label: 'Untyped'] }
        hubGet.register('/installedapp/configure/json/100') { configJson(100, [typeName: 'Basic Rule-1.0']) }
        hubGet.register('/installedapp/statusJson/100') { statusJson(100, [:]) }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.ruleFormat == 'basic-rule'
        count('/app/ruleBuilderJson/100') == 0
    }

    @spock.lang.Unroll
    def "an app whose lookup throws is read from its page and compiled state, and is never reported healthy (broken=#brokenFlag)"() {
        given:
        script.metaClass.getAppByAppId = { Long id -> throw new IllegalStateException('lookup failed') }
        seed(100, [broken: brokenFlag])

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        count('/installedapp/configure/json/100') == 1
        h.ruleFormat == 'rm'
        h.broken == brokenFlag
        h.ok == false
        h.checkErrors.any { it.contains('could not be identified (lookup failed)') }

        where:
        brokenFlag << [true, false]
    }

    def "an app id the hub does not know is refused before any read"() {
        given:
        script.metaClass.getAppByAppId = { Long id -> null }

        when:
        script._rmCheckRuleHealth(100)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message.contains('App 100 not found')
        hubGet.calls.isEmpty()
    }

    def "a null appId is an unreadable verdict with no reads"() {
        when:
        def h = script._rmCheckRuleHealth(null)

        then:
        h.ok == false
        h.unreadable == true
        h.issues.any { it.contains('appId is null') }
        hubGet.calls.isEmpty()
    }

    // ---------- hub_get_rule on an app without a document ----------

    @spock.lang.Unroll
    def "hub_get_rule on a #typeName returns its health alone with a note (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        appType(typeName)
        hubGet.register('/installedapp/configure/json/100') { configJson(100, [typeName: typeName]) }
        hubGet.register('/app/ruleBuilderJson/100') { JsonOutput.toJson([lvList: []]) }
        hubGet.register('/app/ruleBuilder20Json/100') { '{"success":false}' }
        hubGet.register('/installedapp/statusJson/100') { statusJson(100, [:]) }

        when:
        def response = mcpDriver.callTool('hub_get_rule', [ruleId: 100])
        def inner = mcpDriver.parseInner(response)

        then:
        response.error == null
        !response.result.isError
        inner.ruleId == 100
        inner.health.ruleFormat == format
        inner.health.eventSubscriptionCount == 1
        inner.containsKey('localVariables') && inner.localVariables == null
        inner.note.contains(pointer)
        !inner.containsKey('actions')

        where:
        typeName         | format       | pointer              | useGateways
        'Basic Rule-1.0' | 'basic-rule' | 'hub_get_app_config' | true
        'Basic Rule-1.0' | 'basic-rule' | 'hub_get_app_config' | false
        'Rule-5.0'       | 'rm'         | 'health alone'       | true
    }

    def "hub_get_rule names a missing app as a validation error before any read"() {
        given:
        script.metaClass.getAppByAppId = { Long id -> null }

        when:
        def response = mcpDriver.callTool('hub_get_rule', [ruleId: 999])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('App 999 not found')
        mcpDriver.parseInner(response).error.contains('hub_list_rules')
        hubGet.calls.isEmpty()
    }

    def "a failed app lookup is a runtime failure with a recovery note"() {
        given:
        script.metaClass.getAppByAppId = { Long id -> throw new IllegalStateException('lookup failed') }

        when:
        def result = script.toolGetNativeRule([ruleId: 100])

        then:
        result.success == false
        result.error.contains('lookup failed')
        result.note.contains("hub_get_tool_guide(section='builtin_app_tools_rules')")
        hubGet.calls.isEmpty()
    }

    @spock.lang.Unroll
    def "hub_get_rule refuses #args before anything is read"() {
        when:
        script.toolGetNativeRule(args)

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message.contains(message)
        hubGet.calls.isEmpty()

        where:
        args                                                       | message
        [:]                                                        | 'ruleId is required'
        [ruleId: 0]                                                | 'positive integer'
        [ruleId: '100']                                            | 'positive integer'
        [ruleId: 100, sections: ['variables']]                     | 'Unknown document section'
        [ruleId: 100, sections: ['actions', 'triggers'], limit: 5] | 'exactly one section'
    }

    @spock.lang.Unroll
    def "hub_get_rule refuses the old appId argument as unknown (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_get_rule', [ruleId: 100, appId: 100])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('Unknown hub_get_rule argument: appId')
        hubGet.calls.isEmpty()

        where:
        useGateways << [true, false]
    }
}
