package server

import support.ToolSpecBase
import groovy.json.JsonOutput

/**
 * hub_get_rule_health (toolCheckRuleHealth -> _rmCheckRuleHealth). A Rule Machine rule's page is
 * rendered once, then its compiled state (GET /app/ruleBuilderJson, the rule's whole app state) is
 * read once; the tool adds one statusJson read for the live subscription and job counts. Visual
 * Rules report their engine's own verdict; other built-in classic apps report their page.
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
    private String ruleBuilderJson(Map overrides = [:]) {
        def base = [
            installed: true, broken: false, private: 'true', condOper: 'cond',
            capabstrue: [:], capabsfalse: [:], eval: [:], actionList: [], actions: [:],
            allLocalVars: [:], disabledActions: []
        ]
        JsonOutput.toJson(base + overrides)
    }

    /** configure/json shape of a rendered rule page. */
    private String configJson(int id, Map opts = [:]) {
        JsonOutput.toJson([
            app: [id: id, label: opts.label ?: 'BAT-RH-test', name: 'Rule-5.1', installed: true,
                  disabled: opts.disabled ?: false, appType: [name: opts.typeName ?: 'Rule-5.1', namespace: 'hubitat']],
            configPage: [name: 'mainPage', title: 'Edit Rule', install: true, error: opts.error,
                         sections: [[title: '', input: [], paragraphs: opts.paragraphs ?: [], body: opts.body ?: []]]],
            settings: opts.settings ?: [:], childApps: []
        ])
    }

    private String statusJson(int id) {
        JsonOutput.toJson([installedApp: [id: id], appSettings: [], eventSubscriptions: [[name: 'switch']],
                           scheduledJobs: [], appState: []])
    }

    private void seed(int id, Map rb = [:], Map page = [:]) {
        hubGet.register("/app/ruleBuilderJson/${id}".toString()) { ruleBuilderJson(rb) }
        hubGet.register("/installedapp/configure/json/${id}".toString()) { configJson(id, page) }
        hubGet.register("/installedapp/statusJson/${id}".toString()) { statusJson(id) }
    }

    private int count(String path) { hubGet.calls.count { it.path == path } }

    // ---------- Rule Machine ----------

    def "a healthy rule reports its compiled flags from one page render, one compiled read and one status read"() {
        given:
        seed(100)

        when:
        def h = script.toolCheckRuleHealth([appId: 100])

        then:
        h.ok == true
        h.unreadable == false
        h.ruleFormat == 'rm'
        h.broken == false
        h.paused == false
        h.stopped == false
        h.privateBoolean == true
        h.disabledActions == []
        !h.containsKey('disabledTriggers')
        h.disabled == false
        h.label == 'BAT-RH-test'
        h.structuralIssues == []
        h.issues == []
        h.checkErrors == []
        h.eventSubscriptionCount == 1
        h.scheduledJobCount == 0

        and: "the removed report fields are gone"
        !h.containsKey('source')
        !h.containsKey('brokenMarkers')
        !h.containsKey('brokenMarkerCounts')
        !h.containsKey('multipleFlagPoison')
        !h.containsKey('orphanedActionRows')
        !h.containsKey('configPageError')

        and:
        count('/installedapp/configure/json/100') == 1
        count('/app/ruleBuilderJson/100') == 1
        count('/installedapp/statusJson/100') == 1
        hubGet.calls.size() == 3
    }

    def "the rule's page is rendered before its compiled state is read, so a render-time broken flag is reported"() {
        given: "Rule Machine sets broken only when the page renders"
        boolean rendered = false
        hubGet.register('/installedapp/configure/json/100') { rendered = true; configJson(100, [paragraphs: ['**Broken Trigger**']]) }
        hubGet.register('/app/ruleBuilderJson/100') { ruleBuilderJson([broken: rendered]) }
        hubGet.register('/installedapp/statusJson/100') { statusJson(100) }

        when:
        def h = script.toolCheckRuleHealth([appId: 100])
        def paths = hubGet.calls*.path

        then:
        paths.indexOf('/installedapp/configure/json/100') < paths.indexOf('/app/ruleBuilderJson/100')
        h.broken == true
        h.ok == false
        h.issues.any { it.contains('compiled broken:true') && it.contains("hub_set_rule(operation='read')") }
        h.issues.any { it.contains('**Broken Trigger**') }
    }

    @spock.lang.Unroll
    def "privateBoolean reads the stored private flag #stored as #expected"() {
        given:
        seed(100, [private: stored])

        expect:
        script.toolCheckRuleHealth([appId: 100]).privateBoolean == expected

        where:
        stored  | expected
        'true'  | true
        'false' | false
        true    | true
        false   | false
        null    | null
        'maybe' | null
    }

    def "disabledActions are native action ids as strings, and disabledTriggers appears only when stored"() {
        given:
        seed(100, [disabledActions: ['3', 5], disabledTriggers: [2]])

        when:
        def h = script.toolCheckRuleHealth([appId: 100])

        then:
        h.disabledActions == ['3', '5']
        h.disabledTriggers == ['2']
    }

    @spock.lang.Unroll
    def "stopped is read from the compiled state (#stored -> #expected)"() {
        given:
        seed(100, stored)

        expect:
        script.toolCheckRuleHealth([appId: 100]).stopped == expected

        where:
        stored            | expected
        [:]               | false
        [stopped: true]   | true
        [stopped: false]  | false
        [stopped: 'x']    | null
    }

    @spock.lang.Unroll
    def "native compiled pause defaults only for an absent flag on a recognizable RM state (#stored)"() {
        given:
        seed(100, stored)

        expect:
        script.toolCheckRuleHealth([appId: 100]).paused == expected

        where:
        stored              | expected
        [:]                 | false
        [paused: true]      | true
        [paused: false]     | false
        [paused: null]      | null
        [paused: 'invalid'] | null
        [capabstrue: null]  | null
    }

    def "an unclosed IF in the compiled actions is a structural issue pointing at the terminator change and a read"() {
        given:
        seed(100, [actionList: ['1', '2'], actions: ['1': [method: 'getIfThen', rule: '1'], '2': [method: 'getLogMsg']],
                   eval: ['1': []]])

        when:
        def h = script.toolCheckRuleHealth([appId: 100])
        def text = h.issues.find { it.startsWith('structural imbalance') }

        then:
        h.ok == false
        h.structuralIssues.contains('Native IF block has no END-IF')
        text.contains("hub_set_rule(operation='update')")
        text.contains('terminator')
        text.contains("hub_set_rule(operation='read')")
    }

    def "a balanced IF / ELSE / END-IF and REPEAT / END-REP read clean"() {
        given:
        seed(100, [actionList: ['1', '2', '3', '4', '5', '6'], eval: ['1': ['1']], capabsfalse: ['1': [capab: 'Switch']],
                   actions: ['1': [method: 'getIfThen', rule: '1'], '2': [method: 'getRepeat'], '3': [method: 'getEndRepeat'],
                             '4': [method: 'getElse'], '5': [method: 'getLogMsg'], '6': [method: 'getEndIf']]])

        when:
        def h = script.toolCheckRuleHealth([appId: 100])

        then:
        h.structuralIssues == []
        h.ok == true
    }

    def "a Required Expression switched on with none committed is a structural issue"() {
        given:
        seed(100, [:], [settings: [useST: true]])

        when:
        def h = script.toolCheckRuleHealth([appId: 100])

        then:
        h.structuralIssues.any { it.startsWith('Native expression 0') }
        h.ok == false
    }

    def "the page's *BROKEN* label, broken markers in either format and its error are issues"() {
        given:
        seed(100, [:], [label: 'Porch *BROKEN*', error: 'For input string', paragraphs: ['**Broken Action**'],
                        body: [[element: 'paragraph', description: 'x **Broken Condition**']]])

        when:
        def h = script.toolCheckRuleHealth([appId: 100])

        then:
        h.ok == false
        h.issues.any { it.contains('*BROKEN*') }
        h.issues.any { it.contains('**Broken Action**') && it.contains('**Broken Condition**') }
        h.issues.any { it.contains('For input string') }
    }

    @spock.lang.Unroll
    def "the label drops only real runtime decoration markup (#label)"() {
        given:
        seed(100, [:], [label: label])

        expect:
        script.toolCheckRuleHealth([appId: 100]).label == expected

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
        script.toolCheckRuleHealth([appId: 100]).disabled == true
    }

    def "a Button Rule is read as a Rule Machine rule"() {
        given:
        appType('Button Rule-5.1')
        seed(100)

        expect:
        script.toolCheckRuleHealth([appId: 100]).ruleFormat == 'rm'
    }

    // ---------- read failures ----------

    def "a page that fails to render is a non-gating checkErrors entry when the compiled state reads clean"() {
        given:
        seed(100)
        hubGet.register('/installedapp/configure/json/100') { throw new IOException('page timeout') }

        when:
        def h = script.toolCheckRuleHealth([appId: 100])

        then:
        h.ok == true
        h.unreadable == false
        h.checkErrors.any { it.contains('configPage read failed') && it.contains('page timeout') }
    }

    def "a compiled-state read failure is a checkErrors entry when the page rendered"() {
        given:
        seed(100)
        hubGet.register('/app/ruleBuilderJson/100') { throw new IOException('403') }

        when:
        def h = script.toolCheckRuleHealth([appId: 100])

        then:
        h.checkErrors.any { it.contains('compiled-state read failed') }
        h.unreadable == false
        h.broken == null
    }

    def "a rule whose compiled state is not a rule's says its flags were not checked"() {
        given:
        seed(100)
        hubGet.register('/app/ruleBuilderJson/100') { '{}' }
        hubGet.register('/app/ruleBuilder20Json/100') { '{"success":false}' }

        when:
        def h = script.toolCheckRuleHealth([appId: 100])

        then:
        h.checkErrors.any { it.contains('returned no compiled state') }
        h.broken == null
    }

    def "nothing readable is unreadable, not checked-and-broken"() {
        given:
        hubGet.register('/installedapp/configure/json/100') { throw new IOException('down') }
        hubGet.register('/app/ruleBuilderJson/100') { throw new IOException('down') }
        hubGet.register('/app/ruleBuilder20Json/100') { throw new IOException('down') }

        when:
        def h = script.toolCheckRuleHealth([appId: 100])

        then:
        h.ok == false
        h.unreadable == true
        h.eventSubscriptionCount == null
        h.checkErrors.any { it.startsWith('statusJson:') }
    }

    def "a schedule-only rule reports zero live counts, not null"() {
        given:
        seed(100)
        hubGet.register('/installedapp/statusJson/100') { JsonOutput.toJson([installedApp: [id: 100], appSettings: [], appState: []]) }

        when:
        def h = script.toolCheckRuleHealth([appId: 100])

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
        hubGet.register('/installedapp/statusJson/100') { statusJson(100) }

        when:
        def h = script.toolCheckRuleHealth([appId: 100])

        then:
        h.ruleFormat == format
        h.paused == paused
        h.label == 'Visual (Paused)'
        h.ok == true
        count('/installedapp/configure/json/100') == 0
        !h.containsKey('privateBoolean')

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

    @spock.lang.Unroll
    def "a built-in classic app is classified by its type without a compiled read: #typeName -> #expected"() {
        given:
        appType(typeName)
        hubGet.register('/installedapp/configure/json/100') { configJson(100, [typeName: typeName]) }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.ruleFormat == expected
        h.broken == null
        h.ok == true
        count('/app/ruleBuilderJson/100') == 0
        !h.containsKey('privateBoolean')

        where:
        typeName                | expected
        'Basic Rule-1.0'        | 'basic-rule'
        'Button Controller-5.1' | 'button-controller'
        'Notifier-1.0'          | 'classic-app'
    }

    def "a user or community app is not read at all"() {
        given:
        script.metaClass.getAppByAppId = { Long id -> [id: id, label: 'Package Manager', appType: [name: 'Hubitat Package Manager', namespace: 'dcm.hpm']] }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        hubGet.calls.isEmpty()
        h.ruleFormat == 'classic-app'
        h.label == 'Package Manager'
        h.checkErrors.any { it.contains('was not read: it is a user or community app') }
        h.unreadable == true
        h.ok == false
    }

    def "an app without an app type is not read as a built-in app"() {
        given:
        script.metaClass.getAppByAppId = { Long id -> [id: id, label: 'Untyped'] }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        hubGet.calls.isEmpty()
        h.unreadable == true
    }

    @spock.lang.Unroll
    def "an app whose lookup throws gets its compiled state read but no page, and is never reported healthy (broken=#brokenFlag)"() {
        given:
        script.metaClass.getAppByAppId = { Long id -> throw new IllegalStateException('lookup failed') }
        hubGet.register('/app/ruleBuilderJson/100') { ruleBuilderJson([broken: brokenFlag]) }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        count('/installedapp/configure/json/100') == 0
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

    // ---------- dispatch envelope ----------

    @spock.lang.Unroll
    def "hub_get_rule_health via dispatch returns the report (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        seed(100, [private: false, disabledActions: ['4']])

        when:
        def response = mcpDriver.callTool('hub_get_rule_health', [appId: 100])
        def inner = mcpDriver.parseInner(response)

        then:
        response.error == null
        !response.result.isError
        inner.ok == true
        inner.privateBoolean == false
        inner.disabledActions == ['4']
        inner.eventSubscriptionCount == 1

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_get_rule_health refuses the removed source argument as unknown (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        seed(100)

        when:
        def response = mcpDriver.callTool('hub_get_rule_health', [appId: 100, source: 'configPage'])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('Unknown hub_get_rule_health argument: source')
        hubGet.calls.isEmpty()

        where:
        useGateways << [true, false]
    }
}
