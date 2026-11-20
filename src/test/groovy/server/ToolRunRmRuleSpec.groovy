package server

import groovy.json.JsonOutput
import support.RMUtilsMock
import support.ToolSpecBase

/**
 * Spec for toolRunRmRule (libraries/mcp-native-rules-lib.groovy).
 * Gateway: hub_manage_native_rules_and_apps -> hub_call_rule.
 *
 * Every action drives the rule's own page buttons, never RMUtils: actions clicks runAction;
 * stop/start toggle stopRule and pause/resume toggle pausRule, each read first so a rule already
 * in the target state is left alone, and re-rendered after the click so hub_list_rules is current.
 * A Visual Rule runs through /app/ruleBuilderRun and pauses through /app/ruleBuilderPause, and has
 * no stop. Every id is looked up before anything runs (the target guard): a missing id, a non-rule
 * app or a failed lookup refuses the whole call, a batch included.
 */
class ToolRunRmRuleSpec extends ToolSpecBase {

    RMUtilsMock rmUtils
    // App type per id for the getAppByAppId stub; an id mapped to null has no app, an absent id is a Rule-5.1 rule.
    Map<Integer, String> appTypes = [:]
    List<Map> posts = []

    def setup() {
        rmUtils = new RMUtilsMock()
        rmUtils.install()
        script.metaClass.getAppByAppId = { Long id ->
            Integer key = id as Integer
            if (appTypes.containsKey(key) && appTypes[key] == null) return null
            [id: id, appType: [namespace: 'hubitat', name: appTypes.containsKey(key) ? appTypes[key] : 'Rule-5.1']]
        }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '{"status":"success"}']
        }
    }

    def cleanup() {
        rmUtils?.uninstall()
    }

    // A rule's App Status page as the hub serves it; a null flag is left out, as for a rule never stopped or paused.
    private String statusJson(int ruleId, Boolean stopped, Boolean paused = null) {
        def appState = [[name: "running", value: false, type: "Boolean"]]
        if (stopped != null) appState << [name: "stopped", value: stopped, type: "Boolean"]
        if (paused != null) appState << [name: "paused", value: paused, type: "Boolean"]
        JsonOutput.toJson([installedApp: [id: ruleId], appSettings: [], eventSubscriptions: [], scheduledJobs: [],
                           appState: appState, childAppCount: 0, childDeviceCount: 0])
    }

    private void rule(int id, Boolean stopped, Boolean paused = null) {
        hubGet.register("/installedapp/statusJson/${id}") { params -> statusJson(id, stopped, paused) }
        hubGet.register("/installedapp/configure/json/${id}") { params ->
            JsonOutput.toJson([app: [id: id, version: 3], configPage: [name: 'mainPage', sections: []]])
        }
    }

    private List clicks(String button) {
        posts.findAll { it.path == '/installedapp/btn' && it.body.name == button }
    }

    def "throws when Write master is disabled"() {
        given:
        settingsMap.enableWrite = false

        when: 'the central executeTool gate blocks the write tool (tool body no longer self-gates)'
        script.executeTool('hub_call_rule', [ruleId: 1])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Write tools are disabled')
    }

    @spock.lang.Unroll
    def "hub_call_rule via dispatch returns an isError validation result when Write master is disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableWrite = false

        when:
        def response = mcpDriver.callTool('hub_call_rule', [ruleId: 1])

        then:
        response.error == null
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('Write tools are disabled')

        where:
        useGateways << [true, false]
    }

    def "throws when ruleId is missing"() {
        when:
        script.toolRunRmRule([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('ruleid is required')
    }

    @spock.lang.Unroll
    def "hub_call_rule via dispatch returns isError validation result envelope when ruleId is missing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_call_rule', [:])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.toLowerCase().contains('ruleid is required')

        where:
        useGateways << [true, false]
    }

    def "the default action runs the rule's actions through the runAction button, not RMUtils"() {
        when:
        def result = script.toolRunRmRule([ruleId: 102])

        then: "the Run Actions page button is the route the hub UI takes"
        result.success == true
        result.ruleId == 102
        result.ruleIds == [102]
        result.rmAction == 'runAction button'
        clicks('runAction').size() == 1
        clicks('runAction')[0].body.id == '102'
        rmUtils.calls.isEmpty()
    }

    @spock.lang.Unroll
    def "hub_call_rule via dispatch action=actions clicks the runAction button (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_call_rule', [ruleId: 102, action: 'actions'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.ruleId == 102
        clicks('runAction').size() == 1
        rmUtils.calls.isEmpty()

        where:
        useGateways << [true, false]
    }

    def "action=actions reports a failed runAction click"() {
        given:
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            throw new IllegalStateException('btn POST refused')
        }

        when:
        def result = script.toolRunRmRule([ruleId: 109, action: 'actions'])

        then:
        result.success == false
        result.ruleId == 109
        result.error.contains('runAction button click failed')
        result.error.contains('btn POST refused')
        rmUtils.calls.isEmpty()

        and: 'the note warns that the actions may have run and a retry runs them again'
        result.note.contains('may have reached the hub and run')
        result.note.contains('hub_get_logs')
        result.note.contains('a retry runs the actions again')
        result.note.contains("hub_get_tool_guide(section='builtin_app_tools_rules')")
    }

    @spock.lang.Unroll
    def "a failed #button click on action=#action says re-issuing is safe"() {
        given:
        rule(107, false, false)
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 -> throw new IllegalStateException('btn POST refused') }

        when:
        def result = script.toolRunRmRule([ruleId: 107, action: action])

        then:
        result.success == false
        result.error.contains("${button} button click failed")
        result.note.contains('Re-issuing is safe')
        result.note.contains("already ${state} is left alone")
        result.note.contains('hub_list_rules')
        result.note.contains("hub_get_tool_guide(section='builtin_app_tools_rules')")

        where:
        action  | button     | state
        'stop'  | 'stopRule' | 'stopped'
        'pause' | 'pausRule' | 'paused'
    }

    def "array ruleId with action=actions clicks runAction per rule and aggregates"() {
        given:
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            if (body.id == '121') throw new IllegalStateException('btn POST refused')
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        def result = script.toolRunRmRule([ruleId: [120, 121], action: 'actions'])

        then: "one click per rule; the failed id is named and the survivor is not undone"
        result.success == false
        result.partial == true
        result.ruleIds == [120, 121]
        result.rmAction == 'actions x2'
        result.failedRuleIds == [121]
        result.results*.ruleId == [120, 121]
        result.results[0].success == true
        result.results[1].success == false
        result.error.contains('actions failed for rule(s) 121')
        result.note.contains('runs that rule\'s actions again')
        clicks('runAction').size() == 2
        rmUtils.calls.isEmpty()
    }

    @spock.lang.Unroll
    def "action=#action clicks #button once when the rule is not yet there, then renders its page once"() {
        given:
        rule(106, stopped, paused)
        def events = []
        hubGet.register('/installedapp/configure/json/106') { params ->
            events << 'render'
            JsonOutput.toJson([app: [id: 106, version: 3], configPage: [name: 'mainPage', sections: []]])
        }
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            events << "post ${path} ${body?.name}".toString()
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        def result = script.toolRunRmRule([ruleId: 106, action: action])

        then:
        result.success == true
        result.ruleIds == [106]
        result.rmAction == rmAction
        events.count { it == "post /installedapp/btn ${button}".toString() } == 1
        events.count { it == 'render' } == 1
        rmUtils.calls.isEmpty()

        and: 'the render follows the click, so it shows the decoration the click produced'
        events.indexOf("post /installedapp/btn ${button}".toString()) < events.indexOf('render')
        result.note == null

        where:
        action   | stopped | paused | button     | rmAction
        'stop'   | false   | null   | 'stopRule' | 'stopRule button -> state.stopped=true'
        'stop'   | null    | null   | 'stopRule' | 'stopRule button -> state.stopped=true'
        'start'  | true    | null   | 'stopRule' | 'stopRule button -> state.stopped=false (initialize)'
        'pause'  | false   | false  | 'pausRule' | 'pausRule button -> state.paused=true'
        'pause'  | false   | null   | 'pausRule' | 'pausRule button -> state.paused=true'
        'resume' | false   | true   | 'pausRule' | 'pausRule button -> state.paused=false'
    }

    @spock.lang.Unroll
    def "action=#action leaves a rule already #state alone: no click, no render"() {
        given:
        rule(104, stopped, paused)
        def renders = 0
        hubGet.register('/installedapp/configure/json/104') { params -> renders++; '{}' }

        when:
        def result = script.toolRunRmRule([ruleId: 104, action: action])

        then: "a click would toggle the rule the wrong way"
        result.success == true
        result.rmAction == 'noop'
        result.note.contains('already')
        posts.isEmpty()
        renders == 0

        where:
        action   | stopped | paused | state
        'stop'   | true    | null   | 'stopped'
        'start'  | false   | null   | 'running'
        'start'  | null    | null   | 'running (never stopped)'
        'pause'  | false   | true   | 'paused'
        'resume' | false   | false  | 'not paused'
        'resume' | false   | null   | 'not paused (never paused)'
    }

    @spock.lang.Unroll
    def "hub_call_rule via dispatch pause clicks pausRule and no-ops a rule already paused (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        rule(130, false, false)
        rule(131, false, true)

        when:
        def paused = mcpDriver.parseInner(mcpDriver.callTool('hub_call_rule', [ruleId: 130, action: 'pause']))
        def again = mcpDriver.parseInner(mcpDriver.callTool('hub_call_rule', [ruleId: 131, action: 'pause']))

        then:
        paused.success == true
        paused.rmAction == 'pausRule button -> state.paused=true'
        again.success == true
        again.rmAction == 'noop'
        clicks('pausRule')*.body*.id == ['130']
        rmUtils.calls.isEmpty()

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "action=#action refuses to click when the rule's #flag entry is not a flag"() {
        given:
        hubGet.register('/installedapp/statusJson/140') { params ->
            JsonOutput.toJson([appState: [[name: flag, value: 'sometimes']], eventSubscriptions: [], scheduledJobs: []])
        }

        when:
        def result = script.toolRunRmRule([ruleId: 140, action: action])

        then:
        result.success == false
        result.error.contains("cannot read the rule's ${flag} state")
        result.note.contains('Nothing was clicked')
        posts.isEmpty()

        where:
        action  | flag
        'stop'  | 'stopped'
        'pause' | 'paused'
    }

    @spock.lang.Unroll
    def "hub_call_rule via dispatch keeps a landed #action successful with a note when the page re-render fails (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        rule(107, action == 'start', action == 'resume')
        hubGet.register('/installedapp/configure/json/107') { params -> throw new RuntimeException('render timed out') }

        when:
        def response = mcpDriver.callTool('hub_call_rule', [ruleId: 107, action: action])

        then: "the toggle itself landed, so the call succeeds and names the stale-status risk"
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.note.contains('render timed out')
        inner.note.contains('hub_list_rules')
        inner.note.contains('hub_get_rule ')

        where:
        action   | useGateways
        'stop'   | true
        'stop'   | false
        'start'  | true
        'pause'  | true
        'resume' | false
    }

    def "a re-render failure inside a stop batch keeps every rule's result and names the stale status on that rule"() {
        given:
        rule(150, false)
        rule(151, false)
        hubGet.register('/installedapp/configure/json/151') { params -> throw new RuntimeException('render timed out') }

        when:
        def result = script.toolRunRmRule([ruleId: [150, 151], action: 'stop'])

        then: 'both stops landed, so the batch succeeds; the rule whose page did not render carries the note'
        result.success == true
        result.partial == false
        result.rmAction == 'stop x2'
        clicks('stopRule')*.body*.id == ['150', '151']
        result.results[0].note == null
        result.results[1].success == true
        result.results[1].note.contains('render timed out')
    }

    def "invalid action throws IllegalArgumentException naming the five actions"() {
        when:
        script.toolRunRmRule([ruleId: 108, action: 'explode'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('invalid action')
        ex.message.contains("'pause' or 'resume'")
        posts.isEmpty()
    }

    @spock.lang.Unroll
    def "action 'rule' is not an action and is refused before anything runs (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_call_rule', [ruleId: 108, action: 'rule'])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.toLowerCase().contains('invalid action')
        posts.isEmpty()
        rmUtils.calls.isEmpty()

        where:
        useGateways << [true, false]
    }

    def "String ruleId is coerced to Integer before dispatch"() {
        when:
        def result = script.toolRunRmRule([ruleId: '202'])

        then:
        result.success == true
        result.ruleId == 202
        result.ruleId instanceof Integer
    }

    @spock.lang.Unroll
    def "hub_call_rule via dispatch coerces String ruleId to Integer (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_call_rule', [ruleId: '202'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.ruleId == 202
        inner.ruleId instanceof Integer

        where:
        useGateways << [true, false]
    }

    def "array ruleId coerces String ids and dedupes preserving order"() {
        given:
        rule(402, false, true)
        rule(400, false, true)

        when:
        def result = script.toolRunRmRule([ruleId: ['402', 400, '400'], action: 'resume'])

        then:
        result.success == true
        result.ruleIds == [402, 400]
        clicks('pausRule')*.body*.id == ['402', '400']
    }

    @spock.lang.Unroll
    def "#kind in the ruleId is rejected before anything runs"() {
        // The list arg coerces through _rmCoerceRuleId, not normalizeRuleId: a JSON number
        // 400.7 would silently truncate to rule 400 via toInteger() and act on the wrong rule.
        when:
        script.toolRunRmRule([ruleId: badId, action: 'pause'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains(message)
        posts.isEmpty()
        hubGet.calls.isEmpty()

        where:
        kind                   | badId           | message
        'a non-numeric id'     | 'not-a-number'  | 'is not an integer-valued rule id'
        'a null element'       | [400, null]     | 'is not an integer-valued rule id'
        'JSON number 400.7'    | [400.7]         | 'is not an integer-valued rule id'
        'decimal string 400.7' | ['400.7']       | 'is not an integer-valued rule id'
        'an empty array'       | []              | 'must not be empty'
    }

    def "gateway dispatch via handleGateway routes to hub_call_rule"() {
        when:
        def result = script.handleGateway('hub_manage_native_rules_and_apps', 'hub_call_rule', [ruleId: 300, action: 'actions'])

        then:
        result.success == true
        clicks('runAction').size() == 1
    }

    @spock.lang.Unroll
    def "array ruleId with action=#action toggles each rule and aggregates per-rule results"() {
        given:
        rule(118, stoppedA, pausedA)
        rule(119, stoppedB, pausedB)

        when: 'the first rule needs the click, the second is already there'
        def result = script.toolRunRmRule([ruleId: [118, 119], action: action])

        then:
        result.success == true
        result.partial == false
        result.ruleIds == [118, 119]
        result.rmAction == "${action} x2".toString()
        result.results*.ruleId == [118, 119]
        result.results[1].rmAction == 'noop'
        clicks(button)*.body*.id == ['118']

        where:
        action   | stoppedA | pausedA | stoppedB | pausedB | button
        'stop'   | false    | null    | true     | null    | 'stopRule'
        'start'  | true     | null    | false    | null    | 'stopRule'
        'pause'  | false    | false   | false    | true    | 'pausRule'
        'resume' | false    | true    | false    | false   | 'pausRule'
    }

    def "single-element ARRAY echoes both the single-id shape and ruleIds"() {
        given:
        rule(111, true)

        when:
        def result = script.toolRunRmRule([ruleId: [111], action: 'stop'])

        then:
        result.success == true
        result.ruleId == 111
        result.ruleIds == [111]
        !result.containsKey('idsVerified')
    }

    def "multi-rule pause where EVERY rule fails says so instead of naming survivors that do not exist"() {
        given: "both rules' state reads throw, so neither toggle can run"
        hubGet.register('/installedapp/statusJson/116') { params -> throw new IllegalStateException("statusJson read failed") }
        hubGet.register('/installedapp/statusJson/117') { params -> throw new IllegalStateException("statusJson read failed") }

        when:
        def result = script.toolRunRmRule([ruleId: [116, 117], action: 'pause'])

        then: "the all-failed wording -- the partial-success text would claim 0 rules WERE actioned"
        result.success == false
        result.failedRuleIds == [116, 117]
        result.error.contains("failed for EVERY rule in the batch")
        !result.error.contains("WERE actioned")
        result.partial == false
        result.results.every { it.success == false }
        posts.isEmpty()
    }

    def "multi-rule stop names the failed ids and marks partial when others succeeded"() {
        given: "112 is stoppable; 113's state read throws, so its toggle fails"
        rule(112, false)
        hubGet.register('/installedapp/statusJson/113') { params -> throw new IllegalStateException("statusJson read failed") }

        when:
        def result = script.toolRunRmRule([ruleId: [112, 113], action: 'stop'])

        then:
        result.success == false
        result.partial == true
        result.failedRuleIds == [113]
        result.error.contains("113")
        result.error.contains("WERE actioned")
        result.results[0].success == true
        result.results[1].success == false
    }

    @spock.lang.Unroll
    def "a multi-rule #action pauses at the response-budget checkpoint and hands back the remainder"() {
        given: "the budget reads exhausted after the first rule"
        rule(114, true, true)
        script.metaClass._timeBudgetExceeded = { Long t0 -> true }

        when: '114 is handled; 115 is never reached'
        def result = script.toolRunRmRule([ruleId: [114, 115], action: action, __reqT0: 1L])

        then:
        result.success == false
        result.partial == true
        result.remainingRuleIds == [115]
        result.results.size() == 1
        result.note.contains("ruleId=[115]")
        result.note.contains(tail)

        where:
        action    | tail
        'stop'    | 'already-actioned rules no-op safely'
        'actions' | 'leave out the rules already run'
    }

    // ---- the target guard ----

    @spock.lang.Unroll
    def "action=#action on an app that is not a rule is refused naming the app and its type, before any dispatch"() {
        given:
        appTypes[20] = 'Rule Machine'

        when:
        script.toolRunRmRule([ruleId: 20, action: action])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('App 20 is a Rule Machine app, not a rule')
        ex.message.contains('hub_call_rule only acts on rules')
        ex.message.contains('hub_list_rules')
        ex.message.contains('Nothing was dispatched')
        rmUtils.calls.isEmpty()
        posts.isEmpty()
        hubGet.calls.isEmpty()

        where:
        action << ['actions', 'stop', 'start', 'pause', 'resume']
    }

    @spock.lang.Unroll
    def "#label on an id with no app is refused before any dispatch"() {
        given: "the hub returns no app for 999999, as it does for an id that was never installed"
        appTypes[999999] = null

        when:
        script.toolRunRmRule([ruleId: ruleId, action: 'actions'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('No app with id 999999 is installed')
        ex.message.contains('hub_call_rule only acts on rules')
        ex.message.contains('Nothing was dispatched')
        posts.isEmpty()

        where:
        label            | ruleId
        'a single id'    | 999999
        'a mixed batch'  | [101, 999999]
    }

    @spock.lang.Unroll
    def "hub_call_rule via dispatch refuses a batch holding a non-rule app before running any rule (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        appTypes.putAll([20: 'Rule Machine', 30: 'Basic Rule-1.0'])

        when:
        def response = mcpDriver.callTool('hub_call_rule', [ruleId: [101, 20, 30], action: 'actions'])
        def inner = mcpDriver.parseInner(response)

        then:
        response.result.isError == true
        inner.error.contains('Apps 20 (Rule Machine), 30 (Basic Rule-1.0) are not rules')
        posts.isEmpty()
        rmUtils.calls.isEmpty()

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "a lookup that fails refuses the whole call as a runtime failure and logs it, before any dispatch (#label)"() {
        given:
        script.metaClass.getAppByAppId = { Long id ->
            if (id == 160L) throw new RuntimeException('hub busy')
            if (id == 161L) return [id: id, appType: null]
            [id: id, appType: [namespace: 'hubitat', name: 'Rule-5.1']]
        }
        def logged = []
        script.metaClass.mcpLog = { String level, String component, String msg -> logged << [level: level, msg: msg] }

        when:
        def result = script.toolRunRmRule([ruleId: ids, action: 'actions'])

        then: 'nothing runs: the target was never confirmed as a rule'
        result.success == false
        result.ruleIds == ids
        result.error.contains(detail)
        result.error.contains('nothing was run')
        result.note.contains('Nothing was changed')
        posts.isEmpty()
        logged.any { it.level == 'warn' && it.msg.contains('hub_call_rule') }

        where:
        label                       | ids         | detail
        'a single id that throws'   | [160]       | 'app 160: hub busy'
        'a batch with one throwing' | [101, 160]  | 'app 160: hub busy'
        'an app with no type'       | [161]       | 'app 161: its app type could not be read'
    }

    @spock.lang.Unroll
    def "hub_call_rule via dispatch returns a refused lookup as an isError runtime failure (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        script.metaClass.getAppByAppId = { Long id -> throw new RuntimeException('hub busy') }

        when:
        def response = mcpDriver.callTool('hub_call_rule', [ruleId: [101, 102], action: 'pause'])
        def inner = mcpDriver.parseInner(response)

        then:
        response.result.isError == true
        inner.success == false
        inner.error.contains('could not be looked up')
        posts.isEmpty()
        !hubGet.calls*.path.any { it.contains('statusJson') }

        where:
        useGateways << [true, false]
    }

    // ---- Visual Rules ----

    private void visualRule(int id, String format, boolean paused) {
        appTypes[id] = format == 'graph' ? 'Visual Rule Builder 2.0' : 'Visual Rule Builder 1.0'
        if (format == 'graph') {
            hubGet.register("/app/ruleBuilderJson/${id}") { params -> '{}' }
            hubGet.register("/app/ruleBuilder20Json/${id}") { params ->
                JsonOutput.toJson([name: 'Porch', rulePaused: paused, ruleJson: '', validationErrors: []])
            }
        } else {
            hubGet.register("/app/ruleBuilderJson/${id}") { params ->
                JsonOutput.toJson([name: 'Porch', rulePaused: paused, whenNodes: [], thenNodes: [], elseNodes: []])
            }
        }
    }

    @spock.lang.Unroll
    def "action=actions on a #format Visual Rule runs it through /app/ruleBuilderRun"() {
        given:
        visualRule(170, format, false)
        hubGet.register('/app/ruleBuilderRun/170') { params -> '{"success":true}' }

        when:
        def result = script.toolRunRmRule([ruleId: 170, action: 'actions'])

        then:
        result.success == true
        result.rmAction == 'ruleBuilderRun'
        result.ruleIds == [170]
        hubGet.calls*.path.count { it == '/app/ruleBuilderRun/170' } == 1
        posts.isEmpty()
        rmUtils.calls.isEmpty()

        where:
        format << ['graph', 'classic']
    }

    def "a Visual Rule the hub does not run comes back as a runtime failure with the hub's message"() {
        given:
        visualRule(171, 'classic', false)
        hubGet.register('/app/ruleBuilderRun/171') { params -> '{"success":false,"message":"rule is not a 2.0 rule"}' }

        when:
        def result = script.toolRunRmRule([ruleId: 171, action: 'actions'])

        then: 'nothing else is tried'
        result.success == false
        result.error.contains('rule is not a 2.0 rule')
        result.note.contains('Nothing else was tried')
        hubGet.calls*.path.count { it.startsWith('/app/ruleBuilderRun') } == 1
        posts.isEmpty()
    }

    @spock.lang.Unroll
    def "action=#action on a #format Visual Rule #outcome"() {
        given:
        visualRule(172, format, pausedBefore)
        hubGet.register("/app/ruleBuilderPause/172/${action == 'pause'}") { params -> '{"success":true}' }

        when:
        def result = script.toolRunRmRule([ruleId: 172, action: action])

        then:
        result.success == true
        result.rmAction == rmAction
        hubGet.calls*.path.count { it.startsWith('/app/ruleBuilderPause/') } == pauseCalls
        posts.isEmpty()

        where:
        action   | format    | pausedBefore | rmAction                     | pauseCalls | outcome
        'pause'  | 'graph'   | false        | 'ruleBuilderPause -> true'   | 1          | 'pauses it through its builder endpoint'
        'resume' | 'classic' | true         | 'ruleBuilderPause -> false'  | 1          | 'resumes it through its builder endpoint'
        'pause'  | 'classic' | true         | 'noop'                       | 0          | 'is left alone when already paused'
        'resume' | 'graph'   | false        | 'noop'                       | 0          | 'is left alone when not paused'
    }

    def "a Visual Rule pause the endpoint refuses is a runtime failure"() {
        given:
        visualRule(173, 'graph', false)
        hubGet.register('/app/ruleBuilderPause/173/true') { params -> '{"success":false,"message":"busy"}' }

        when:
        def result = script.toolRunRmRule([ruleId: 173, action: 'pause'])

        then:
        result.success == false
        result.error.contains('busy')
        result.note.contains('Re-issuing is safe')
        result.note.contains('already paused is left alone')
        result.note.contains("hub_get_tool_guide(section='builtin_app_tools_rules')")
    }

    @spock.lang.Unroll
    def "a Visual Rule run whose answer is #label may have run, and the note says so"() {
        given:
        visualRule(175, 'graph', false)
        hubGet.register('/app/ruleBuilderRun/175', answer)

        when:
        def result = script.toolRunRmRule([ruleId: 175, action: 'actions'])

        then:
        result.success == false
        result.error.contains(error)
        result.note.contains('may have reached the hub and run the rule')
        result.note.contains('hub_get_logs')
        result.note.contains("hub_get_tool_guide(section='builtin_app_tools_rules')")

        where:
        label         | answer                                               | error
        'lost'        | { params -> throw new IOException('read timed out') } | '/app/ruleBuilderRun failed (read timed out)'
        'not JSON'    | { params -> '<html>Proxy error</html>' }              | 'non-JSON response'
    }

    @spock.lang.Unroll
    def "action=#action naming a Visual Rule is refused before anything runs, the batch's Rule Machine rules included"() {
        given:
        visualRule(174, 'graph', false)
        rule(101, action == 'start')

        when:
        script.toolRunRmRule([ruleId: [101, 174], action: action])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Visual Rules have no stop or start')
        ex.message.contains('174')
        ex.message.contains('Nothing was dispatched')
        posts.isEmpty()
        hubGet.calls.isEmpty()

        where:
        action << ['stop', 'start']
    }

    def "a mixed batch runs each rule through its own route"() {
        given:
        visualRule(175, 'graph', false)
        hubGet.register('/app/ruleBuilderRun/175') { params -> '{"success":true}' }

        when:
        def result = script.toolRunRmRule([ruleId: [101, 175], action: 'actions'])

        then:
        result.success == true
        result.results*.rmAction == ['runAction button', 'ruleBuilderRun']
        clicks('runAction')*.body*.id == ['101']
    }

    def "the Visual Rules parent app is not a rule"() {
        given:
        appTypes[176] = 'Visual Rules Builder'

        when:
        script.toolRunRmRule([ruleId: 176, action: 'actions'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('App 176 is a Visual Rules Builder app, not a rule')
        hubGet.calls.isEmpty()
    }
}
