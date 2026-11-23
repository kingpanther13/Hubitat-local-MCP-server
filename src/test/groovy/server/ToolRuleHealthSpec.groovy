package server

import support.ToolSpecBase
import groovy.json.JsonOutput

/**
 * The rule health report (_rmCheckRuleHealth), returned as `health` by hub_get_rule and by
 * hub_set_rule(operation:'read'). No page is ever rendered: one App Status read (/installedapp/statusJson,
 * appState and appSettings as LISTS) supplies the label, red-X flag, paused, stopped, privateBoolean, the
 * device settings brokenRows come from and the live subscription and job counts; a rule's compiled state
 * (GET /app/ruleBuilderJson) is read once; a Rule Machine 5.1 rule or Button Rule answers broken from its
 * own broken() method through _nrHandle. Visual Rules report their engine's own verdict.
 *
 * hub_get_rule on a Rule Machine 5.1 rule returns the document as well; that path is covered in
 * NativeDocumentsSpec, which carries the native handle fixtures.
 *
 * Mocking: hubInternalGet is routed to hubGet.register(path) by HarnessSpec, and the closure
 * returns a JSON STRING. Unstubbed paths throw inside the helper's try/catch.
 */
class ToolRuleHealthSpec extends ToolSpecBase {

    Object ruleBroken
    List handles
    // The text Rule Machine's label methods return per kind, markers included, and the rule's live state.
    Map labels
    Map liveState

    def setup() {
        settingsMap.enableRead = true
        appType('Rule-5.1')
        ruleBroken = false
        handles = []
        labels = [:]
        liveState = [:]
        script.metaClass._nrHandle = { Long id ->
            handles << id
            new Expando(broken: { -> ruleBroken instanceof Closure ? ruleBroken() : ruleBroken },
                        triggerLabel: { -> label('trigger') }, conditionLabel: { -> label('condition') }, actionLabel: { -> label('action') },
                        getState: { -> liveState })
        }
    }

    private Object label(String kind) {
        def text = labels.get(kind)
        text instanceof Closure ? text() : text
    }

    private static String marker(String kind) { "<span class='text-hubitat-danger'>**Broken ${kind}**</span>".toString() }

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

    /**
     * statusJson as the hub serves it (live shape, firmware 2.5.3.123): appState is a list of {name, value};
     * appSettings rows carry a device picker's devices in deviceIdsForDeviceList and deviceList, its value null.
     */
    private static String statusJson(int id, Map state, Map app = [:], List settings = []) {
        JsonOutput.toJson([installedApp: [id: id, label: app.label ?: 'BAT-RH-test', disabled: app.disabled ?: false, installed: true,
                                          appType: [name: app.typeName ?: 'Rule-5.1', namespace: app.namespace ?: 'hubitat', oauthClientSecret: 'secret-x']],
                           appSettings: settings, eventSubscriptions: [[name: 'switch']], scheduledJobs: [],
                           appState: state.collect { k, v -> [name: k, value: v] }])
    }

    /** One appSettings row; a device picker lists its device ids, or none once its device is deleted. */
    private static Map setting(String name, String type, List deviceIds = null, Object value = null) {
        [id: 70, installedAppId: 100, version: 1, name: name, type: type, multiple: deviceIds != null, value: value,
         deviceIdsForDeviceList: deviceIds ?: [], deviceList: deviceIds ? deviceIds.collectEntries { [(it.toString()): "Device ${it}".toString()] } : null]
    }

    /** One rule whose compiled state and App Status page carry the same state, as on a hub. */
    private void seed(int id, Map stateOverrides = [:], Map app = [:], List settings = []) {
        Map state = ruleState(stateOverrides)
        liveState = state
        hubGet.register("/app/ruleBuilderJson/${id}".toString()) { JsonOutput.toJson(state) }
        hubGet.register("/installedapp/statusJson/${id}".toString()) { statusJson(id, state, app, settings) }
    }

    private int count(String path) { hubGet.calls.count { it.path == path } }

    // ---------- Rule Machine ----------

    def "a healthy rule reports its flags from one status read, one compiled read and its own broken check, rendering nothing"() {
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
        count('/installedapp/configure/json/100') == 0
        count('/app/ruleBuilderJson/100') == 1
        count('/installedapp/statusJson/100') == 1
        hubGet.calls.size() == 2
        handles == [100L]
    }

    def "every report field is present from the start, null until read"() {
        given: "nothing can be read"
        ruleBroken = { -> throw new IllegalStateException('down') }
        hubGet.register('/app/ruleBuilderJson/100') { throw new IOException('down') }
        hubGet.register('/app/ruleBuilder20Json/100') { throw new IOException('down') }
        hubGet.register('/installedapp/statusJson/100') { throw new IOException('down') }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        ['privateBoolean', 'disabledActions', 'disabledTriggers', 'paused', 'stopped', 'broken',
         'eventSubscriptionCount', 'scheduledJobCount', 'brokenRows'].every { h.containsKey(it) && h.get(it) == null }
    }

    def "a rule whose own broken check says broken is reported broken while its compiled flag still reads false"() {
        given: "a trigger device was deleted and no one opened the rule, so only broken() knows; its trigger label carries the marker"
        ruleBroken = true
        labels.trigger = marker('Trigger')
        seed(100, [broken: false, capabstrue: ['1': 'Porch turns on']], [:], [setting('tCapab1', 'enum', null, 'Switch'), setting('tDev1', 'capability.switch', [])])

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.broken == true
        h.ok == false
        h.brokenRows == [[kind: 'trigger', id: '1', setting: 'tDev1', text: 'Porch turns on']]
        h.issues == ["Rule Machine marks this rule broken: a trigger, condition or action refers to something that is gone; brokenRows lists the rows its page marks."]
        count('/installedapp/configure/json/100') == 0
    }

    def "brokenRows name each row Rule Machine's labels mark by native id, found from its emptied picker; the OR between triggers takes no row"() {
        given: "the live rule: a condition (id 1), two Switch triggers (ids 2 and 3, the page joins them with OR), a conditional switch action and a log action, all devices deleted"
        ruleBroken = true
        labels.putAll(trigger: marker('Trigger') + ' OR ' + marker('Trigger'), condition: marker('Condition'), action: 'IF (' + marker('Condition') + ') ' + marker('Action'))
        seed(100, [capabstrue: ['2': 'A turns on', '3': 'B turns on'], capabsfalse: ['1': 'C is on'], inUseConds: ['1'],
                   actionList: ['1', '2'], actions: ['1': [quick: false, method: 'getOnOffSwitch', indent: '', cond: '1'], '2': [quick: true, method: 'getLogMsg', indent: '']]],
             [:], [setting('tCapab2', 'enum', null, 'Switch'), setting('tDev2', 'capability.switch', []), setting('tstate2', 'enum', null, 'on'),
                   setting('tCapab3', 'enum', null, 'Switch'), setting('tDev3', 'capability.switch', []), setting('tstate3', 'enum', null, 'on'),
                   setting('rCapab_1', 'enum', null, 'Switch'), setting('rDev_1', 'capability.switch', []), setting('state_1', 'enum', null, 'on'),
                   setting('actType.1', 'enum', null, 'switchActs'), setting('onOffSwitch.1', 'capability.switch', []), setting('onOff.1', 'bool', null, 'true'),
                   setting('logmsg.2', 'textarea', null, 'plain')])

        when:
        def rows = script._rmCheckRuleHealth(100).brokenRows

        then: "triggers, then conditions, then actions, each by its own id; an action's stored row is a map, so it carries no text"
        rows == [[kind: 'trigger', id: '2', setting: 'tDev2', text: 'A turns on'],
                 [kind: 'trigger', id: '3', setting: 'tDev3', text: 'B turns on'],
                 [kind: 'condition', id: '1', setting: 'rDev_1', text: 'C is on'],
                 [kind: 'action', id: '1', setting: 'onOffSwitch.1']]
    }

    def "a device setting that still names a device, or names a row the rule does not hold, is not a marked row"() {
        given:
        ruleBroken = true
        labels.trigger = marker('Trigger')
        seed(100, [capabstrue: ['1': 'A turns on', '2': 'B turns on']], [:],
             [setting('tDev1', 'capability.switch', [9]), setting('tDev2', 'capability.switch', []), setting('tDev7', 'capability.switch', []),
              setting('relDevice_4', 'capability.switch', []), setting('notify', 'capability.notification', [])])

        expect: "only the dead picker of a row the rule holds, in a kind its labels mark, counts"
        script._rmCheckRuleHealth(100).brokenRows == [[kind: 'trigger', id: '2', setting: 'tDev2', text: 'B turns on']]
    }

    def "an action setting whose base name holds digits or underscores is an action, and an unknown name is no trigger"() {
        given: "action ids 3 and 4 and trigger 2 are held, condition 2 is in use; notify2 and switches_2 name no known trigger or condition prefix"
        labels.putAll(condition: marker('Condition'), action: marker('Action'))
        seed(100, [capabstrue: ['2': 'B turns on'], capabsfalse: ['2': 'C is on'], actionList: ['3', '4'], inUseConds: [2],
                   actions: ['3': [method: 'getOnOffSwitch', cond: '2'], '4': [method: 'getOnOffSwitch']]], [:],
             [setting('foo2.3', 'capability.switch', []), setting('a_b.4', 'capability.switch', []),
              setting('notify2', 'capability.notification', []), setting('switches_2', 'capability.switch', []),
              setting('relDevice_2', 'capability.switch', [])])

        expect:
        script._rmCheckRuleHealth(100).brokenRows == [[kind: 'condition', id: '2', setting: 'relDevice_2', text: 'C is on'],
                                                      [kind: 'action', id: '3', setting: 'foo2.3'],
                                                      [kind: 'action', id: '4', setting: 'a_b.4']]
    }

    def "an emptied picker in a kind Rule Machine's labels do not mark is not listed: a row being built, or a spare condition"() {
        given: "a trigger picker left empty while its row is built, and a condition whose device is gone that no label marks"
        seed(100, [capabstrue: ['1': 'A turns on'], capabsfalse: ['2': 'C is on'], inUseConds: ['2']], [:],
             [setting('tDev1', 'capability.switch', []), setting('rDev_2', 'capability.switch', [])])

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.brokenRows == []
        h.ok == true
        h.issues == []
    }

    def "a marked kind with no emptied picker is listed once with id null"() {
        given: "an action gated on a marked condition carries the marker in the action labels, with no picker of its own"
        labels.action = 'IF (' + marker('Condition') + ') Log: gated'
        seed(100, [actionList: ['1'], actions: ['1': [method: 'getLogMsg', cond: '1']]], [:], [setting('logmsg.1', 'textarea', null, 'gated')])

        expect:
        script._rmCheckRuleHealth(100).brokenRows == [[kind: 'action', id: null]]
    }

    def "a label method that fails leaves brokenRows null with a checkErrors entry, so an update is refused"() {
        given:
        labels.condition = { -> throw new IllegalStateException('label down') }
        seed(100)

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.broken == false
        h.brokenRows == null
        h.ok == false
        h.checkErrors == ["the rule's label methods failed (label down), so brokenRows was not listed"]
    }

    def "a malformed appSettings reads as no settings: paused, stopped and the counts still come back"() {
        given:
        ruleBroken = brokenNow
        Map state = ruleState(capabstrue: ['1': 'A turns on'])
        hubGet.register('/app/ruleBuilderJson/100') { JsonOutput.toJson(state) }
        hubGet.register('/installedapp/statusJson/100') {
            JsonOutput.toJson([installedApp: [label: 'BAT-RH-test', disabled: false, appType: [name: 'Rule-5.1', namespace: 'hubitat']],
                               appSettings: [tDev1: null], eventSubscriptions: [[name: 'switch']], scheduledJobs: [],
                               appState: [[name: 'paused', value: true]]])
        }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.paused == true
        h.stopped == false
        h.eventSubscriptionCount == 1
        h.scheduledJobCount == 0
        h.brokenRows == null
        h.checkErrors.any { it.contains('brokenRows was not listed') }
        !h.checkErrors.any { it.startsWith('statusJson') }

        where:
        brokenNow << [false, true]
    }

    def "a broken rule whose compiled state went unread still carries the broken issue, and says why brokenRows is null"() {
        given:
        ruleBroken = true
        hubGet.register('/app/ruleBuilderJson/100') { throw new IOException('down') }
        hubGet.register('/installedapp/statusJson/100') { statusJson(100, [:], [:], [setting('tDev1', 'capability.switch', [])]) }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.broken == true
        h.disabledActions == null
        h.brokenRows == null
        h.issues.any { it.contains('marks this rule broken') }
        h.checkErrors.any { it.contains('brokenRows was not listed') }
    }

    def "a status page without an installedApp block takes the red-X flag from the app itself"() {
        given:
        script.metaClass.getAppByAppId = { Long id -> [id: id, label: 'BAT-RH-test', disabled: true, appType: [namespace: 'hubitat', name: 'Rule-5.1']] }
        hubGet.register('/app/ruleBuilderJson/100') { JsonOutput.toJson(ruleState()) }
        hubGet.register('/installedapp/statusJson/100') { JsonOutput.toJson([appSettings: [], eventSubscriptions: [], scheduledJobs: [], appState: []]) }

        expect:
        script._rmCheckRuleHealth(100).disabled == true
    }

    def "a row with two dead pickers is listed once per setting, and the list is deduplicated on the whole row"() {
        given:
        ruleBroken = true
        labels.trigger = marker('Trigger')
        seed(100, [capabstrue: ['1': 'Temp above Other']], [:],
             [setting('tDev1', 'capability.temperatureMeasurement', []), setting('reltDevice1', 'capability.temperatureMeasurement', []),
              setting('tDev1', 'capability.temperatureMeasurement', [])])

        expect:
        script._rmCheckRuleHealth(100).brokenRows == [[kind: 'trigger', id: '1', setting: 'reltDevice1', text: 'Temp above Other'],
                                                      [kind: 'trigger', id: '1', setting: 'tDev1', text: 'Temp above Other']]
    }

    def "a condition whose device was deleted is a marked row, not a broken rule: broken stays Rule Machine's own false"() {
        given: "broken() says false for a deleted condition device; the condition and the action it gates carry the marker"
        labels.putAll(condition: marker('Condition'), action: 'IF (' + marker('Condition') + ") Log: 'gated'")
        seed(100, [capabsfalse: ['1': 'C is on'], inUseConds: ['1'], actionList: ['1'], actions: ['1': [method: 'getLogMsg', cond: '1']]], [:],
             [setting('rCapab_1', 'enum', null, 'Switch'), setting('rDev_1', 'capability.switch', []), setting('state_1', 'enum', null, 'on')])

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.broken == false
        h.ok == false
        h.brokenRows == [[kind: 'condition', id: '1', setting: 'rDev_1', text: 'C is on'], [kind: 'action', id: null]]
        h.issues == ["Rule Machine marks 2 row(s) on the rule's page (brokenRows) but does not call the rule broken; an update that marks no new row is allowed."]
        h.checkErrors == []
    }

    def "a condition only a ticked Conditional Trigger on a held trigger uses counts, though inUseConds leaves it out"() {
        given: "inUseConds is empty, as the hub leaves it for Conditional Triggers; trigger 1's box is ticked for condition 1, trigger 2's unticked box keeps condTrig = 3, and condTrig.9 names a trigger the rule no longer holds"
        labels.condition = marker('Condition') + ' ' + marker('Condition')
        seed(100, [capabstrue: ['1': 'A turns on', '2': 'B turns on'], capabsfalse: ['1': 'C is on', '3': 'E is on', '4': 'F is on'], inUseConds: []], [:],
             [setting('isCondTrig.9', 'bool', null, 'true'), setting('condTrig.9', 'enum', null, '4'), setting('rDev_4', 'capability.switch', []),
              setting('isCondTrig.1', 'bool', null, 'true'), setting('condTrig.1', 'enum', null, '1'),
              setting('isCondTrig.2', 'bool', null, 'false'), setting('condTrig.2', 'enum', null, '3'),
              setting('tDev1', 'capability.switch', [7]), setting('tDev2', 'capability.switch', [8]), setting('rDev_1', 'capability.switch', []),
              setting('rDev_3', 'capability.switch', [])])

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.broken == false
        h.brokenRows == [[kind: 'condition', id: '1', setting: 'rDev_1', text: 'C is on']]
        h.checkErrors == []
    }

    @spock.lang.Unroll
    def "a status page #label reads brokenRows #expected"() {
        given:
        Map state = ruleState(capabsfalse: ['1': 'C is on'], actionList: ['1'], actions: ['1': [method: 'getLogMsg', cond: '1']])
        hubGet.register('/app/ruleBuilderJson/100') { JsonOutput.toJson(state) }
        hubGet.register('/installedapp/statusJson/100') {
            JsonOutput.toJson([installedApp: [label: 'BAT-RH-test', disabled: false, appType: [name: 'Rule-5.1', namespace: 'hubitat']],
                               eventSubscriptions: [], scheduledJobs: [], appState: [[name: 'paused', value: false]]] + body)
        }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.brokenRows == expected
        h.paused == false
        h.ok == (expected != null)
        h.checkErrors.any { it == "the rule's settings went unread, so brokenRows was not listed" } == (expected == null)
        h.checkErrors.any { it.contains('expression 0 was not checked') } == (expected == null)

        where:
        label                                  | body                  | expected
        'with no appSettings key'              | [:]                   | null
        'with appSettings null'                | [appSettings: null]   | null
        'with an empty appSettings list'       | [appSettings: []]     | []
    }

    def "a Rule Machine 5.1 rule whose App Status read fails reads brokenRows null, not an empty list, with a checkErrors entry"() {
        given: "broken() says false and the settings cannot be read"
        hubGet.register('/app/ruleBuilderJson/100') { JsonOutput.toJson(ruleState(capabsfalse: ['1': 'C is on'], actionList: ['1'], actions: ['1': [method: 'getLogMsg', cond: '1']])) }
        hubGet.register('/installedapp/statusJson/100') { throw new IOException('timed out') }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.broken == false
        h.brokenRows == null
        h.disabledActions == []
        h.ok == false
        h.checkErrors.any { it == "the rule's settings went unread, so brokenRows was not listed" }
    }

    def "an older Rule Machine type has no label methods here, so its brokenRows is empty whatever its own flag says (broken=#flag)"() {
        given:
        appType('Rule-4.1')
        seed(100, [broken: flag, capabsfalse: ['1': 'C is on']], [typeName: 'Rule-4.1'], [setting('rDev_1', 'capability.switch', [])])

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.broken == flag
        h.brokenRows == []
        handles == []

        where:
        flag << [false, true]
    }

    def "privateBoolean reads the stored private flag #stored as #expected, an entry that is not a flag being a failed check"() {
        given:
        seed(100, [private: stored])

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.privateBoolean == expected
        h.ok == (expected != null)
        h.checkErrors.any { it.contains('private state is stored as neither true nor false') } == (expected == null)

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

        when:
        def h = script._rmCheckRuleHealth(100)

        then: "an entry that is not a flag is unread and names itself, as hub_list_rules and the toggles treat it"
        h.stopped == expected
        h.ok == (expected != null)
        h.checkErrors.any { it.contains('stopped state is stored as neither true nor false') } == (expected == null)

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

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.paused == expected
        h.checkErrors.any { it.contains('paused state is stored as neither true nor false') } == (expected == null)

        where:
        stored              | expected
        [:]                 | false
        [paused: true]      | true
        [paused: 'true']    | true
        [paused: false]     | false
        [paused: null]      | null
        [paused: 'invalid'] | null
    }

    def "an App Status flag that is not a flag reads null, neither the compiled value nor the name's decoration standing in for it"() {
        given: "compiled state says not paused and not stopped; App Status holds a malformed value for each; the name carries both decorations"
        hubGet.register('/app/ruleBuilderJson/100') { JsonOutput.toJson(ruleState(paused: false, stopped: false)) }
        hubGet.register('/installedapp/statusJson/100') {
            statusJson(100, ruleState(paused: 'invalid', stopped: 'x'), [label: 'BAT-RH-test <span style="color:red">(Paused)</span> <span style="color:red">(Stopped)</span>'])
        }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.paused == null
        h.stopped == null
        h.ok == false
        h.checkErrors.any { it.contains('paused state is stored as neither true nor false') }
        h.checkErrors.any { it.contains('stopped state is stored as neither true nor false') }
    }

    def "a valid App Status paused flag overrides the compiled value"() {
        given:
        hubGet.register('/app/ruleBuilderJson/100') { JsonOutput.toJson(ruleState(paused: false)) }
        hubGet.register('/installedapp/statusJson/100') { statusJson(100, ruleState(paused: true)) }

        expect:
        script._rmCheckRuleHealth(100).paused == true
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
        given: "the status page lists useST as the text 'true', as it lists every bool setting"
        seed(100, [:], [:], [setting('useST', 'bool', null, 'true')])

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.structuralIssues.any { it.startsWith('Native expression 0') }
        h.ok == false
    }

    @spock.lang.Unroll
    def "an older rule type whose state has no broken flag (#stored) reads broken null as a failed check, never ok"() {
        given: "a Rule-5.0 rule has no broken() check here, so its stored flag is all there is"
        appType('Rule-5.0')
        Map state = ruleState()
        if (stored == 'absent') state.remove('broken') else state.broken = stored
        hubGet.register('/app/ruleBuilderJson/100') { JsonOutput.toJson(state) }
        hubGet.register('/installedapp/statusJson/100') { statusJson(100, state, [typeName: 'Rule-5.0']) }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.ruleFormat == 'rm'
        h.broken == null
        h.disabledActions == []
        h.checkErrors == ["the rule's broken flag could not be read, so whether it is broken is unknown"]
        h.ok == false
        handles.isEmpty()
        count('/app/ruleBuilder20Json/100') == 0

        where:
        stored << ['absent', 'maybe', null]
    }

    def "an older rule type's stored broken flag stored as text reads as a flag"() {
        given:
        appType('Rule-5.0')
        seed(100, [broken: 'true'], [typeName: 'Rule-5.0'])

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.broken == true
        h.ok == false
        handles.isEmpty()
    }

    @spock.lang.Unroll
    def "a Rule Machine 5.1 rule whose broken check #how reads broken null with that failure named"() {
        given:
        ruleBroken = answer
        seed(100)

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.broken == null
        h.ok == false
        h.checkErrors.size() == 1
        h.checkErrors[0].contains(message)

        where:
        how                 | answer                                              | message
        'throws'            | { -> throw new IllegalStateException('no method') } | "broken() check failed (no method)"
        'answers a non-flag' | 'yes'                                              | "broken() check returned yes"
    }

    def "a Rule Machine 5.1 rule's broken comes from its own check, not its compiled flag"() {
        given:
        ruleBroken = false
        seed(100, [broken: true])

        expect:
        script._rmCheckRuleHealth(100).broken == false
    }

    def "a rule whose compiled read answers with no Rule Machine state is a failed check"() {
        given:
        hubGet.register('/app/ruleBuilderJson/100') { JsonOutput.toJson([lvList: [], private: true]) }
        hubGet.register('/app/ruleBuilder20Json/100') { '{"success":false}' }
        hubGet.register('/installedapp/statusJson/100') { statusJson(100, [private: true]) }

        when:
        def h = script._rmCheckRuleHealth(100)

        then: "broken still comes from the rule's own check"
        h.ruleFormat == 'rm'
        h.unreadable == false
        h.broken == false
        h.disabledActions == null
        h.privateBoolean == true
        h.brokenRows == null
        h.checkErrors.size() == 2
        h.checkErrors[0].contains('is not Rule Machine state')
        h.checkErrors[1] == "the rule's compiled state went unread, so brokenRows was not listed"
        h.issues == []
        h.ok == false
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
        h.broken == false
        h.ok == false
    }

    def "a *BROKEN* label on a rule whose own broken check says false is the user's name, not an issue"() {
        given: "a clean rule whose name happens to contain the marker"
        seed(100, [:], [label: 'Kitchen *BROKEN* test'])

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.broken == false
        h.ok == true
        h.issues == []
        h.label == 'Kitchen *BROKEN* test'
    }

    def "a *BROKEN* label is reported as a hint when the rule's own broken check cannot be read"() {
        given:
        ruleBroken = { -> throw new IllegalStateException('down') }
        seed(100, [:], [label: 'Porch *BROKEN*'])

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.broken == null
        h.ok == false
        h.issues.any { it.contains('*BROKEN*') && it.contains('hint') }
        h.checkErrors.any { it.contains('broken() check failed') }
        h.label == 'Porch *BROKEN*'
    }

    def "unreadable settings leave the Required Expression unchecked and say so, never reading as switched off"() {
        given: "the status page returns appSettings as a map, so the useST setting cannot be read"
        Map state = ruleState()
        hubGet.register('/app/ruleBuilderJson/100') { JsonOutput.toJson(state) }
        hubGet.register('/installedapp/statusJson/100') {
            def body = new groovy.json.JsonSlurper().parseText(statusJson(100, state))
            body.appSettings = [useST: 'true']
            JsonOutput.toJson(body)
        }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.checkErrors.any { it.contains('Required Expression') && it.contains('expression 0 was not checked') }
        h.ok == false
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

    def "the red-X disabled flag comes from the status page"() {
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
        h.brokenRows == null
        h.checkErrors.size() == 3
        h.checkErrors[0] == 'statusJson: status timeout'
        h.checkErrors[1] == "the rule's settings went unread, so brokenRows was not listed"
        h.checkErrors[2].contains('expression 0 was not checked')
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
        ruleBroken = { -> throw new IllegalStateException('down') }
        hubGet.register('/app/ruleBuilderJson/100') { throw new IOException('down') }
        hubGet.register('/app/ruleBuilder20Json/100') { throw new IOException('down') }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.ok == false
        h.unreadable == true
        h.ruleFormat == 'rm'
        h.issues.any { it.contains("neither the app's status page nor its compiled state") }
        h.checkErrors.any { it.startsWith('statusJson:') }
    }

    def "a schedule-only rule reports zero live counts, not null"() {
        given:
        seed(100)
        hubGet.register('/installedapp/statusJson/100') { JsonOutput.toJson([installedApp: [id: 100, label: 'R', disabled: false], appSettings: [], appState: []]) }

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
    def "a built-in classic app is classified by its type without a compiled read or a render: #typeName -> #expected"() {
        given:
        appType(typeName)
        hubGet.register('/installedapp/statusJson/100') { statusJson(100, [:], [typeName: typeName]) }

        when:
        def h = script._rmCheckRuleHealth(100)

        then: "a Basic Rule with no paused entry was never paused; the other apps have no such default"
        h.ruleFormat == expected
        h.broken == null
        h.ok == true
        count('/app/ruleBuilderJson/100') == 0
        count('/installedapp/configure/json/100') == 0
        handles.isEmpty()
        h.privateBoolean == null
        h.stopped == null
        h.paused == paused

        where:
        typeName                | expected            | paused
        'Basic Rule-1.0'        | 'basic-rule'        | false
        'Button Controller-5.1' | 'button-controller' | null
        'Notifier-1.0'          | 'classic-app'       | null
    }

    @spock.lang.Unroll
    def "a Basic Rule reads paused from its state.paused (#state -> #paused)"() {
        given:
        appType('Basic Rule-1.0')
        hubGet.register('/installedapp/statusJson/100') { statusJson(100, state, [typeName: 'Basic Rule-1.0']) }

        expect:
        script._rmCheckRuleHealth(100).paused == paused

        where:
        state                                        | paused
        [actNo: 1, actionList: []]                   | false
        [paused: true]                               | true
        [paused: 'false']                            | false
    }

    @spock.lang.Unroll
    def "a classic app keeps paused and stopped from its state, with the name decoration as evidence (#label, #state)"() {
        given:
        appType('Room Lighting-1.0')
        hubGet.register('/installedapp/statusJson/100') { statusJson(100, state, [typeName: 'Room Lighting-1.0', label: label]) }

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

    def "a user or community app is read like any app: its page is not rendered, since that runs its own code, and no compiled state is read"() {
        given:
        script.metaClass.getAppByAppId = { Long id -> [id: id, label: 'Package Manager', appType: [name: 'Hubitat Package Manager', namespace: 'dcm.hpm']] }
        hubGet.register('/installedapp/statusJson/100') { statusJson(100, [:], [typeName: 'Hubitat Package Manager', namespace: 'dcm.hpm', label: 'Package Manager']) }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        count('/installedapp/configure/json/100') == 0
        count('/app/ruleBuilderJson/100') == 0
        h.ruleFormat == 'classic-app'
        h.label == 'Package Manager'
        h.checkErrors == []
        h.ok == true
    }

    def "an app whose lookup fails takes its type from its status page"() {
        given:
        script.metaClass.getAppByAppId = { Long id -> throw new IllegalStateException('lookup failed') }
        hubGet.register('/installedapp/statusJson/100') { statusJson(100, [:], [typeName: 'Basic Rule-1.0']) }

        when:
        def h = script._rmCheckRuleHealth(100)

        then:
        h.ruleFormat == 'basic-rule'
        h.ok == false
        h.checkErrors.any { it.contains('so its type was taken from its status page') }
        count('/app/ruleBuilderJson/100') == 0
    }

    @spock.lang.Unroll
    def "an app whose lookup throws and whose status page cannot be read is judged by its compiled state, never reported healthy (broken=#brokenFlag)"() {
        given:
        script.metaClass.getAppByAppId = { Long id -> throw new IllegalStateException('lookup failed') }
        hubGet.register('/app/ruleBuilderJson/100') { JsonOutput.toJson(ruleState([broken: brokenFlag])) }
        hubGet.register('/installedapp/statusJson/100') { throw new IOException('down') }

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
        refused.message.contains('No app with id 100 is installed')
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
    def "hub_get_rule on a #typeName returns its health alone with a note pointing at #pointer (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        appType(typeName)
        hubGet.register('/app/ruleBuilderJson/100') { JsonOutput.toJson([lvList: []]) }
        hubGet.register('/app/ruleBuilder20Json/100') { '{"success":false}' }
        hubGet.register('/installedapp/statusJson/100') { statusJson(100, [:], [typeName: typeName]) }

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
        !inner.containsKey('actions') && !inner.containsKey('settings')
        count('/installedapp/configure/json/100') == 0

        where:
        typeName                | format              | pointer                                   | useGateways
        'Button Controller-5.1' | 'button-controller' | 'hub_get_app_config(includeStatus: true)' | true
        'Notifier-1.0'          | 'classic-app'       | 'hub_get_app_config(includeStatus: true)' | false
    }

    @spock.lang.Unroll
    def "hub_get_rule on a Basic Rule returns its health and settings, with no note (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        appType('Basic Rule-1.0')
        hubGet.register('/installedapp/statusJson/100') { statusJson(100, [actNo: 1], [typeName: 'Basic Rule-1.0']) }
        hubGet.register('/installedapp/settings/100') { JsonOutput.toJson([settings: [[name: 'trigCapab', type: 'enum', multiple: false, value: 'Switch'],
            [name: 'switchDev', type: 'capability.switch', multiple: true, value: null, deviceList: ['9': 'Porch']]]]) }

        when:
        def inner = mcpDriver.parseInner(mcpDriver.callTool('hub_get_rule', [ruleId: 100]))

        then:
        inner.health.ruleFormat == 'basic-rule'
        inner.health.paused == false
        inner.localVariables == null
        inner.settings*.name == ['trigCapab', 'switchDev']
        inner.settings[1].deviceIds == ['9']
        !inner.containsKey('note')
        count('/installedapp/configure/json/100') == 0

        where:
        useGateways << [true, false]
    }

    def "hub_get_rule on a Basic Rule leaves its settings out when sections omits them, and refuses paging them"() {
        given:
        appType('Basic Rule-1.0')
        hubGet.register('/installedapp/statusJson/100') { statusJson(100, [:], [typeName: 'Basic Rule-1.0']) }

        when:
        def health = script.toolGetNativeRule([ruleId: 100, sections: []])

        then:
        health.health.ruleFormat == 'basic-rule'
        !health.containsKey('settings')

        when:
        script.toolGetNativeRule([ruleId: 100, sections: ['settings'], cursor: ''])

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message.contains("A Basic Rule's settings are returned whole")
    }

    def "a Basic Rule whose requested settings cannot be read is a failed read that keeps its health and names the failure"() {
        given: "the rule was deleted, or its settings page failed, between the health read and the settings read"
        appType('Basic Rule-1.0')
        hubGet.register('/installedapp/statusJson/100') { statusJson(100, [:], [typeName: 'Basic Rule-1.0']) }
        hubGet.register('/installedapp/settings/100') { throw new IOException('status code: 404') }

        when:
        def result = script.toolGetNativeRule([ruleId: 100])

        then:
        result.success == false
        result.health.ruleFormat == 'basic-rule'
        result.settings == null
        result.settingsError == 'status code: 404'
        result.error == 'Rule 100 could not be read: status code: 404'
        result.note.contains('retrying once is safe')
    }

    def "a Basic Rule settings failure reaches the client as isError with its health, through the dispatcher (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        appType('Basic Rule-1.0')
        hubGet.register('/installedapp/statusJson/100') { statusJson(100, [:], [typeName: 'Basic Rule-1.0']) }
        hubGet.register('/installedapp/settings/100') { throw new IOException('status code: 404') }

        when:
        def response = useGateways ? mcpDriver.callTool('hub_read_rules', [tool: 'hub_get_rule', args: [ruleId: 100, sections: ['settings']]]) :
            mcpDriver.callTool('hub_get_rule', [ruleId: 100, sections: ['settings']])
        def inner = mcpDriver.parseInner(response)

        then:
        response.result.isError == true
        inner.success == false
        inner.health.ruleFormat == 'basic-rule'
        inner.settingsError == 'status code: 404'

        where:
        useGateways << [false, true]
    }

    def "hub_get_rule names a missing app as a validation error before any read"() {
        given:
        script.metaClass.getAppByAppId = { Long id -> null }

        when:
        def response = mcpDriver.callTool('hub_get_rule', [ruleId: 999])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('No app with id 999 is installed')
        mcpDriver.parseInner(response).error.contains('hub_list_rules')
        hubGet.calls.isEmpty()
    }

    def "a failed app lookup with nothing else readable is a runtime failure with a read's recovery note"() {
        given:
        script.metaClass.getAppByAppId = { Long id -> throw new IllegalStateException('lookup failed') }
        hubGet.register('/installedapp/statusJson/100') { throw new IOException('down') }
        hubGet.register('/app/ruleBuilderJson/100') { throw new IOException('down') }

        when:
        def result = script.toolGetNativeRule([ruleId: 100])

        then: 'a read is told it changed no configuration and may retry, not that nothing was run'
        result.success == false
        result.error == 'Rule 100 could not be read: lookup failed'
        result.note.contains("does not open the rule's page or change its configuration")
        result.note.contains('retrying once is safe')
        !result.note.contains('writes nothing')
        !result.error.contains('nothing was run')
        result.note.contains("hub_get_tool_guide(section='builtin_app_tools_rules')")
        count('/installedapp/configure/json/100') == 0
    }

    def "a failed app lookup still returns the health its status page supplies, with the document marked unread"() {
        given:
        script.metaClass.getAppByAppId = { Long id -> throw new IllegalStateException('lookup failed') }
        seed(100)

        when:
        def result = script.toolGetNativeRule([ruleId: 100, sections: ['actions']])

        then: 'the requested section did not come back, so the read failed, with the health it could read attached'
        result.success == false
        result.error == 'Rule 100 could not be identified (lookup failed), so its document and local variables were not read; its health was read without it.'
        result.health.ruleFormat == 'rm'
        result.health.unreadable == false
        result.health.checkErrors.any { it.contains('could not be identified (lookup failed)') }
        result.localVariables == null
        !result.containsKey('actions')
        !result.containsKey('documentError')
        result.note.contains('retrying once is safe')
        count('/installedapp/configure/json/100') == 0
    }

    def "a failed app lookup on a health-only read succeeds, with the document marked unread"() {
        given:
        script.metaClass.getAppByAppId = { Long id -> throw new IllegalStateException('lookup failed') }
        seed(100)

        when:
        def result = script.toolGetNativeRule([ruleId: 100, sections: []])

        then:
        !result.containsKey('success')
        result.health.ruleFormat == 'rm'
        result.localVariables == null
        result.documentError == 'Rule 100 could not be identified (lookup failed), so its document and local variables were not read; its health was read without it.'
    }

    @spock.lang.Unroll
    def "hub_get_rule via dispatch reports a failed read when a requested section could not come back (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        script.metaClass.getAppByAppId = { Long id -> throw new IllegalStateException('lookup failed') }
        seed(100)

        when:
        def response = mcpDriver.callTool('hub_get_rule', [ruleId: 100])

        then:
        response.result.isError == true
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.health.ruleFormat == 'rm'
        inner.error.contains('could not be identified (lookup failed)')

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_get_rule via dispatch returns health, not an error, when only the app lookup fails (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        script.metaClass.getAppByAppId = { Long id -> throw new IllegalStateException('lookup failed') }
        seed(100)

        when:
        def response = mcpDriver.callTool('hub_get_rule', [ruleId: 100, sections: []])

        then:
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.health.ruleFormat == 'rm'
        inner.documentError.contains('could not be identified (lookup failed)')

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_get_rule takes ruleId #ruleId as hub_call_rule does"() {
        given:
        appType('Basic Rule-1.0')
        hubGet.register('/installedapp/statusJson/100') { statusJson(100, [:], [typeName: 'Basic Rule-1.0']) }

        when:
        def result = script.toolGetNativeRule([ruleId: ruleId, sections: []])

        then:
        result.ruleId == 100L
        result.health.ruleFormat == 'basic-rule'

        where:
        ruleId << [100, '100', ' 100 ', 100.0]
    }

    def "a community app whose type is named like a rule is read as an app, not a rule"() {
        given:
        appType('Rule-5.1', 'acme')
        hubGet.register('/installedapp/statusJson/100') { statusJson(100, [:], [typeName: 'Rule-5.1', namespace: 'acme']) }

        when:
        def result = script.toolGetNativeRule([ruleId: 100, sections: []])

        then: 'no document is read and its own broken() is never called'
        result.health.ruleFormat == 'classic-app'
        result.note.contains('hub_get_app_config')
        handles.isEmpty()
        count('/app/ruleBuilderJson/100') == 0
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
        [ruleId: 0]                                                | 'positive whole number'
        [ruleId: 4294967396L]                                      | 'positive whole number'
        [ruleId: '4294967396']                                     | 'positive whole number'
        [ruleId: 100.5]                                            | 'positive whole number'
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

    @spock.lang.Unroll
    def "a stale rule_id is named as unknown before ruleId is reported missing (#mode)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = useGateways ? mcpDriver.callTool('hub_read_rules', [tool: 'hub_get_rule', args: [rule_id: 2735]])
                                   : mcpDriver.callTool('hub_get_rule', [rule_id: 2735])
        def error = mcpDriver.parseInner(response).error

        then:
        response.result.isError == true
        error.contains('Unknown hub_get_rule argument: rule_id')
        error.contains('Valid arguments: ruleId')
        !error.contains('Missing required parameter')
        hubGet.calls.isEmpty()

        where:
        mode      | useGateways
        'gateway' | true
        'flat'    | false
    }

    def "through a gateway, a hidden tool called with a stale key still gets its own refusal, not its schema"() {
        given:
        settingsMap.useGateways = true
        settingsMap.disabled_tools = ['hub_get_rule']

        when:
        def error = mcpDriver.parseInner(mcpDriver.callTool('hub_read_rules', [tool: 'hub_get_rule', args: [rule_id: 2735]])).error

        then:
        error.contains('disabled in Advanced settings')
        !error.contains('Valid arguments')
        !error.contains('rule_id')
    }

    def "through a gateway, a call with only known keys still lists the missing parameter with every parameter"() {
        given:
        settingsMap.useGateways = true

        when:
        def error = mcpDriver.parseInner(mcpDriver.callTool('hub_read_rules', [tool: 'hub_get_rule', args: [sections: []]])).error

        then:
        error.contains('Missing required parameter for hub_get_rule: ruleId')
        error.contains('sections (array, optional)')
    }
}
