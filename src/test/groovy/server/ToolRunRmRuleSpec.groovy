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
    // A Visual Rule's paused state, read on every builder read, so a test's pause endpoint can change it.
    Map<Integer, Boolean> vrPaused = [:]
    List<Map> posts = []
    // Each rule's stopped/paused flags as its App Status page reads them; a stopRule/pausRule click flips one, as on a hub.
    Map<String, Map> flags = [:]

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
            toggle(path, body)
            [status: 200, location: null, data: '{"status":"success"}']
        }
    }

    private void toggle(String path, Map body) {
        String flag = [stopRule: 'stopped', pausRule: 'paused'][body?.name]
        Map state = flags[body?.id?.toString()]
        if (path == '/installedapp/btn' && flag && state != null) state[flag] = state[flag] != true
    }

    def cleanup() {
        rmUtils?.uninstall()
        formContexts().clear()
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
        flags[id.toString()] = [stopped: stopped, paused: paused]
        hubGet.register("/installedapp/statusJson/${id}") { params -> statusJson(id, flags[id.toString()].stopped, flags[id.toString()].paused) }
        hubGet.register("/installedapp/configure/json/${id}") { params ->
            JsonOutput.toJson([app: [id: id, version: 3], configPage: [name: 'mainPage', sections: []]])
        }
    }

    private List clicks(String button) {
        posts.findAll { it.path == '/installedapp/btn' && it.body.name == button }
    }

    // A hub_set_rule form session left open across calls, as _nfContext stores it per caller.
    private Map formContexts() {
        def field = script.class.getDeclaredField('NATIVE_FORM_CONTEXTS')
        field.accessible = true
        field.get(null) as Map
    }

    private int renders(int id) { hubGet.calls.count { it.path == "/installedapp/configure/json/${id}".toString() } }

    // A render rewrites the rule's state, which would clear the open session's in-progress keys.
    @spock.lang.Unroll
    def "action=#action on a rule with an open form session clicks but leaves the page unrendered, saying so"() {
        given:
        rule(900, action == 'start', action == 'resume')
        formContexts()['caller:main'] = [app_id: 900L, expires: script.now() + 60000L]

        when:
        def result = script.toolRunRmRule([ruleId: 900, action: action])

        then:
        result.success == true
        clicks(button).size() == 1
        renders(900) == 0
        result.note == "The rule's ${flag} state changed. Its page was not rendered because a hub_set_rule form session is open on it, so hub_list_rules may show the old status until that session finishes or the page next renders.".toString()

        where:
        action   | button     | flag
        'stop'   | 'stopRule' | 'stopped'
        'start'  | 'stopRule' | 'stopped'
        'pause'  | 'pausRule' | 'paused'
        'resume' | 'pausRule' | 'paused'
    }

    @spock.lang.Unroll
    def "a #label form context leaves the toggle's render in place"() {
        given:
        rule(901, false, false)
        formContexts()['caller:main'] = context

        when:
        def result = script.toolRunRmRule([ruleId: 901, action: 'pause'])

        then:
        result.success == true
        renders(901) == 1
        !result.containsKey('note')

        where:
        label                  | context
        'expired'              | [app_id: 901L, expires: script.now() - 1000L]
        "another app's"        | [app_id: 902L, expires: script.now() + 60000L]
    }

    @spock.lang.Unroll
    def "hub_call_rule via dispatch pause on a rule with an open form session skips the render (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        rule(903, false, false)
        formContexts()['caller:main'] = [app_id: 903L, expires: script.now() + 60000L]

        when:
        def response = mcpDriver.callTool('hub_call_rule', [ruleId: 903, action: 'pause'])

        then:
        !response.result.isError
        mcpDriver.parseInner(response).note.contains('a hub_set_rule form session is open on it')
        clicks('pausRule').size() == 1
        renders(903) == 0

        where:
        useGateways << [true, false]
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

    // action has no default: a client that leaves it out must not run every action, skipping the rule's conditions.
    @spock.lang.Unroll
    def "action #label is refused before any lookup or click"() {
        given:
        boolean looked = false
        script.metaClass.getAppByAppId = { Long id -> looked = true; [id: id, appType: [namespace: 'hubitat', name: 'Rule-5.1']] }

        when:
        script.toolRunRmRule(args)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(message)
        e.message.contains("'actions' (runs the rule's actions without its triggers or Required Expression)")
        e.message.endsWith('Nothing was dispatched.')
        !looked
        posts.isEmpty()
        rmUtils.calls.isEmpty()

        where:
        label       | args                          | message
        'left out'  | [ruleId: 102]                 | 'action is required'
        'empty'     | [ruleId: 102, action: '']     | "Invalid action ''"
        'false'     | [ruleId: 102, action: false]  | "Invalid action 'false'"
        'zero'      | [ruleId: 102, action: 0]      | "Invalid action '0'"
    }

    @spock.lang.Unroll
    def "hub_call_rule via dispatch without action is an isError refusal that clicks nothing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_call_rule', [ruleId: 102])

        then:
        response.error == null
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('action is required')
        posts.isEmpty()

        where:
        useGateways << [true, false]
    }

    def "action:'actions' runs the rule's actions through the runAction button, not RMUtils"() {
        when:
        def result = script.toolRunRmRule([ruleId: 102, action: 'actions'])

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

    // The hub accepts runAction on a paused rule, but Rule Machine runs nothing (live on a C-7).
    @spock.lang.Unroll
    def "action=actions on a paused #type is not clicked and says to resume it first"() {
        given:
        appTypes[130] = type
        rule(130, false, true)

        when:
        def result = script.toolRunRmRule([ruleId: 130, action: 'actions'])

        then:
        result.success == false
        result.dispatched == false
        result.ruleIds == [130]
        result.error == "hub_call_rule action=actions: rule 130 is paused, and Rule Machine does not run a paused rule's actions, so nothing was run."
        result.note == "Resume it first with action:'resume', then run its actions. See hub_get_tool_guide(section='builtin_app_tools_rules')."
        clicks('runAction').isEmpty()
        rmUtils.calls.isEmpty()

        where:
        type << ['Rule-5.1', 'Button Rule-5.1']
    }

    @spock.lang.Unroll
    def "action=actions clicks runAction when the paused flag reads #why"() {
        given:
        rule(131, false, paused)
        if (statusPage != null) hubGet.register('/installedapp/statusJson/131') { params -> statusPage }

        when:
        def result = script.toolRunRmRule([ruleId: 131, action: 'actions'])

        then:
        result.success == true
        result.rmAction == 'runAction button'
        clicks('runAction').size() == 1
        result.note == note

        where:
        why                    | paused | statusPage
        'false'                | false  | null
        'absent (never paused)'| null   | null
        'unreadable'           | null   | JsonOutput.toJson([installedApp: [id: 131], appSettings: []])
        note << [null, null, "The rule's paused state could not be read (its status page carried no appState), so the click was sent; if the rule is paused, Rule Machine ran nothing. Check with hub_get_rule."]
    }

    def "a batch runs the rule that is not paused and reports the paused one as not run"() {
        given:
        rule(132, false, true)
        rule(133, false, false)

        when:
        def result = script.toolRunRmRule([ruleId: [132, 133], action: 'actions'])

        then:
        result.success == false
        result.partial == true
        result.failedRuleIds == [132]
        result.results[0].dispatched == false
        result.results[0].error.contains('rule 132 is paused')
        result.results[1].success == true
        clicks('runAction')*.body.id == ['133']
        result.note == "Rule(s) 132 were not run because they are paused; resume them with action:'resume' first. A repeat of a succeeded id runs that rule's actions again."
    }

    def "a batch note says a paused rule was not run and keeps the lost-run warning for a click that failed"() {
        given:
        rule(132, false, true)
        rule(133, false, false)
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 -> throw new IllegalStateException('read timed out') }

        when:
        def result = script.toolRunRmRule([ruleId: [132, 133], action: 'actions'])

        then:
        result.failedRuleIds == [132, 133]
        result.note == "Rule(s) 132 were not run because they are paused; resume them with action:'resume' first. Rule(s) 133 failed: read each one's results[] note before running it again, because a run whose answer was lost may already have run the actions. A repeat of a succeeded id runs that rule's actions again."
    }

    @spock.lang.Unroll
    def "hub_call_rule via dispatch on a paused rule returns isError with the paused text (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        rule(134, false, true)

        when:
        def response = mcpDriver.callTool('hub_call_rule', [ruleId: 134, action: 'actions'])

        then:
        response.error == null
        response.result.isError == true
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error.contains('rule 134 is paused')
        inner.note.contains("action:'resume'")
        clicks('runAction').isEmpty()

        where:
        useGateways << [true, false]
    }

    @spock.lang.Unroll
    def "hub_call_rule refuses #label with a full sentence naming where rule ids come from"() {
        when:
        script.toolRunRmRule([ruleId: ids, action: 'actions'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message == message
        posts.isEmpty()

        where:
        label            | ids                         | message
        'an empty array' | []                          | "ruleId array must not be empty: name one rule id or more. Find rule ids with hub_list_rules (Rule Machine), hub_list_apps(nameFilter:'Button Rule') (Button Rules) or hub_get_visual_rule (Visual Rules)."
        'an overflow'    | [99999999999999999999G]     | "a number outside the hub's id range is not a valid rule id for ruleId (a positive whole number within the hub's id range). Find rule ids with hub_list_rules (Rule Machine), hub_list_apps(nameFilter:'Button Rule') (Button Rules) or hub_get_visual_rule (Visual Rules)."
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
            toggle(path, body)
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
    def "action=#action refuses to click when the rule's status page carries no appState"() {
        given: 'a status page with no appState section, as a gone app answers {}'
        hubGet.register('/installedapp/statusJson/119') { params -> '{}' }

        when:
        def result = script.toolRunRmRule([ruleId: 119, action: action])

        then: 'an absent section is not read as a rule never paused or stopped'
        result.success == false
        result.error.contains('carried no appState')
        result.note.contains('Nothing was clicked')
        posts.isEmpty()

        where:
        action << ['resume', 'start', 'pause']
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

    @spock.lang.Unroll
    def "a landed #action whose page re-renders with an error stays successful and notes the stale status (useGateways=#useGateways)"() {
        given: 'the refresh answers, but its page reports an error'
        settingsMap.useGateways = useGateways
        rule(108, action == 'start', action == 'resume')
        hubGet.register('/installedapp/configure/json/108') { params ->
            JsonOutput.toJson([app: [id: 108, version: 3], configPage: [name: 'mainPage', error: 'Cannot invoke method on null', sections: []]])
        }

        when:
        def response = mcpDriver.callTool('hub_call_rule', [ruleId: 108, action: action])

        then: 'the flag was verified, so success holds; the note names the page error and how to refresh'
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.note.contains('the page reported: Cannot invoke method on null')
        inner.note.contains('hub_list_rules may show the old status')
        inner.note.contains('hub_get_app_config(appId: 108)')
        flags['108'][action in ['stop', 'start'] ? 'stopped' : 'paused'] == (action in ['stop', 'pause'])

        where:
        action   | useGateways
        'stop'   | true
        'pause'  | false
        'resume' | true
    }

    def "a landed toggle whose page renders cleanly carries no note"() {
        given:
        rule(109, false, false)

        when:
        def result = script.toolRunRmRule([ruleId: 109, action: 'pause'])

        then:
        result.success == true
        !result.containsKey('note')
    }

    @spock.lang.Unroll
    def "action=#action whose #button click the hub accepts without changing the flag is a failure with a recovery note"() {
        given: 'the click lands, but the rule state does not move'
        rule(160, action == 'start', action == 'resume')
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        def result = script.toolRunRmRule([ruleId: 160, action: action])

        then:
        result.success == false
        result.error.contains("the ${button} click was accepted, but the rule's ${flag} state then read ${!target}, not ${target}")
        result.note.contains('hub_get_rule')
        result.note.contains('left alone')
        clicks(button).size() == 1

        where:
        action   | button     | flag      | target
        'stop'   | 'stopRule' | 'stopped' | true
        'start'  | 'stopRule' | 'stopped' | false
        'pause'  | 'pausRule' | 'paused'  | true
        'resume' | 'pausRule' | 'paused'  | false
    }

    def "a concurrent toggle that cancels this one out is detected, not reported as landed"() {
        given: "another pause on the same rule clicks between this call's read and its click, so the two toggles cancel out"
        rule(161, false, false)
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            toggle(path, body)
            toggle(path, body)
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        def result = script.toolRunRmRule([ruleId: 161, action: 'pause'])

        then:
        result.success == false
        result.error.contains("the rule's paused state then read false, not true")
        result.note.contains('Another change to the rule may have run at the same time')
    }

    def "a flag that cannot be read after the click is a failure naming why"() {
        given:
        rule(162, false)
        int reads = 0
        hubGet.register('/installedapp/statusJson/162') { params ->
            if (reads++ > 0) throw new IllegalStateException('status timed out')
            statusJson(162, false)
        }

        when:
        def result = script.toolRunRmRule([ruleId: 162, action: 'stop'])

        then:
        result.success == false
        result.error.contains('status timed out')
        clicks('stopRule').size() == 1
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
        def result = script.toolRunRmRule([ruleId: '202', action: 'actions'])

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
        def response = mcpDriver.callTool('hub_call_rule', [ruleId: '202', action: 'actions'])

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
        // A JSON number 400.7 or 4294967696 would act on rule 400 if coerced with toInteger().
        when:
        script.toolRunRmRule([ruleId: badId, action: 'pause'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains(message)
        posts.isEmpty()
        hubGet.calls.isEmpty()

        where:
        kind                   | badId           | message
        'a non-numeric id'     | 'not-a-number'  | 'is not a valid rule id'
        'a null element'       | [400, null]     | 'is not a valid rule id'
        'JSON number 400.7'    | [400.7]         | 'is not a valid rule id'
        'decimal string 400.7' | ['400.7']       | 'is not a valid rule id'
        'an id past int range' | [4294967696]    | 'is not a valid rule id'
        'that id as a string'  | ['4294967696']  | 'is not a valid rule id'
        'rule id zero'         | [0]             | 'is not a valid rule id'
        'a negative id'        | [-1]            | 'is not a valid rule id'
        'zero as a string'     | ['0']           | 'is not a valid rule id'
        'an empty array'       | []              | 'must not be empty'
    }

    @spock.lang.Unroll
    def "hub_call_rule via dispatch refuses an id past the int range instead of acting on the rule it wraps to (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        rule(400, false)

        when:
        def response = mcpDriver.callTool('hub_call_rule', [ruleId: 4294967696L, action: 'actions'])

        then:
        response.result.isError == true
        mcpDriver.parseInner(response).error.contains('is not a valid rule id')
        posts.isEmpty()

        where:
        useGateways << [true, false]
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

    def "an actions batch whose only reached rule failed before the budget checkpoint is not partial, and keeps the unreached rule"() {
        given: "120's click fails and the budget reads exhausted after it"
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            throw new IllegalStateException('btn POST refused')
        }
        script.metaClass._timeBudgetExceeded = { Long t0 -> true }

        when:
        def result = script.toolRunRmRule([ruleId: [120, 121], action: 'actions', __reqT0: 1L])

        then:
        result.success == false
        result.partial == false
        result.failedRuleIds == [120]
        result.remainingRuleIds == [121]
        result.results*.ruleId == [120]
        result.error.contains('failed for EVERY rule reached (120)')
        result.note.contains('ruleId=[121]')
    }

    @spock.lang.Unroll
    def "a multi-rule #action pauses at the response-budget checkpoint and hands back the remainder"() {
        given: "the budget reads exhausted after the first rule"
        rule(114, true, false)
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
        ex.message.contains('App 20 is a Rule Machine app; hub_call_rule acts only on Rule Machine rules, Button Rules and Visual Rules')
        ex.message.contains("hub_list_rules (Rule Machine), hub_list_apps(nameFilter:'Button Rule') (Button Rules) or hub_get_visual_rule (Visual Rules)")
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
        ex.message.contains('hub_call_rule acts only on Rule Machine rules, Button Rules and Visual Rules')
        ex.message.contains('hub_get_visual_rule (Visual Rules)')
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
        inner.error.contains('Apps 20 (Rule Machine), 30 (Basic Rule-1.0) are other app types; hub_call_rule acts only on')
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
        result.note.contains(note)
        posts.isEmpty()
        logged.any { it.level == 'warn' && it.msg.contains('hub_call_rule') }

        where:
        label                       | ids         | detail                                     | note
        'a single id that throws'   | [160]       | 'app 160: hub busy'                        | 'Nothing was changed'
        'a batch with one throwing' | [101, 160]  | 'app 160: hub busy'                        | 'No rule in this lookup was run'
        'an app with no type'       | [161]       | 'app 161: its app type could not be read'  | 'Nothing was changed'
    }

    def "a refused batch names every rule in its own row, so a later continuation slice keeps them"() {
        given:
        script.metaClass.getAppByAppId = { Long id ->
            if (id == 160L) throw new RuntimeException('hub busy')
            [id: id, appType: [namespace: 'hubitat', name: 'Rule-5.1']]
        }

        when:
        def result = script.toolRunRmRule([ruleId: [101, 160], action: 'pause'])

        then: '160 could not be looked up and 101 was not attempted; neither ran'
        result.failedRuleIds == [101, 160]
        result.results*.ruleId == [101, 160]
        result.results.every { it.success == false }
        result.results[0].error.contains('Not run')
        result.results[1].error.contains('hub busy')
        !result.containsKey('remainingRuleIds')
        posts.isEmpty()
    }

    def "a single refused id still carries its ruleId, so a one-rule continuation slice keeps a row"() {
        given:
        script.metaClass.getAppByAppId = { Long id -> throw new RuntimeException('hub busy') }

        expect:
        script.toolRunRmRule([ruleId: [160], action: 'stop']).ruleId == 160
    }

    def "a failed run in a budget-paused actions batch is not put back in the re-issue list"() {
        given: "101's click fails (it may have run), then the budget pauses before 102"
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 -> throw new IllegalStateException('read timed out') }
        script.metaClass._timeBudgetExceeded = { Long t0 -> true }

        when:
        def result = script.toolRunRmRule([ruleId: [101, 102], action: 'actions', __reqT0: 1L])

        then:
        result.failedRuleIds == [101]
        result.remainingRuleIds == [102]
        result.note.contains('ruleId=[102]')
        !result.note.contains('ruleId=[101')
        !result.note.contains('ruleId=[102, 101]')
        result.note.contains("read each one's results[] note before running it again")
    }

    def "a failed run in an actions batch is not called safe to re-issue"() {
        given:
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            if (body.id == '101') throw new IllegalStateException('read timed out')
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        def result = script.toolRunRmRule([ruleId: [101, 102], action: 'actions'])

        then:
        result.failedRuleIds == [101]
        result.note.contains('Rule(s) 101 failed')
        result.note.contains('may already have run')
        !result.note.contains('Re-issue for the failed ids only')
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
        vrPaused[id] = paused
        if (format == 'graph') {
            hubGet.register("/app/ruleBuilderJson/${id}") { params -> '{}' }
            hubGet.register("/app/ruleBuilder20Json/${id}") { params ->
                JsonOutput.toJson([name: 'Porch', rulePaused: vrPaused[id], ruleJson: '', validationErrors: []])
            }
        } else {
            hubGet.register("/app/ruleBuilderJson/${id}") { params ->
                JsonOutput.toJson([name: 'Porch', rulePaused: vrPaused[id], whenNodes: [], thenNodes: [], elseNodes: []])
            }
        }
    }

    def "action=actions on a graph Visual Rule runs it through /app/ruleBuilderRun"() {
        given:
        visualRule(170, 'graph', false)
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
    }

    @spock.lang.Unroll
    def "action=actions naming a classic (1.0) Visual Rule is refused before anything runs (#label, useGateways=#useGateways)"() {
        given: 'the classic builder has no run button; only the 2.0 builder calls /app/ruleBuilderRun'
        settingsMap.useGateways = useGateways
        visualRule(173, 'classic', false)
        visualRule(174, 'graph', false)

        when:
        def response = mcpDriver.callTool('hub_call_rule', [ruleId: ruleId, action: 'actions'])
        def inner = mcpDriver.parseInner(response)

        then: "nothing is dispatched, the batch's other rules included"
        response.result.isError == true
        inner.error.contains('Visual Rule Builder 1.0 rules have no run button')
        inner.error.contains('rule(s) 173 are 1.0 rules')
        inner.error.contains('Nothing was dispatched')
        hubGet.calls*.path.every { !it.startsWith('/app/ruleBuilderRun') }
        posts.isEmpty()

        where:
        label           | ruleId           | useGateways
        'a single rule' | 173              | true
        'a single rule' | 173              | false
        'a mixed batch' | [101, 174, 173]  | true
    }

    @spock.lang.Unroll
    def "action=actions on a Visual Rule typed '#type' is #outcome by the version in its type name alone"() {
        given: "the rule's stored builder format disagrees with its type name, and is not read"
        visualRule(175, classicShape ? 'classic' : 'graph', false)
        appTypes[175] = type
        hubGet.register('/app/ruleBuilderRun/175') { params -> '{"success":true}' }
        hubGet.calls.clear()

        when:
        def result
        def thrown = null
        try { result = script.toolRunRmRule([ruleId: 175, action: 'actions']) } catch (IllegalArgumentException e) { thrown = e }

        then:
        (thrown != null) == refused
        if (refused) assert thrown.message.contains('have no run button') && thrown.message.contains('rule(s) 175 are 1.0 rules')
        else assert result.success == true
        hubGet.calls*.path.count { it == '/app/ruleBuilderRun/175' } == (refused ? 0 : 1)
        hubGet.calls*.path.every { !it.startsWith('/app/ruleBuilderJson') && !it.startsWith('/app/ruleBuilder20Json') }

        where:
        type                      | classicShape || refused | outcome
        'Visual Rule Builder 1.0' | false        || true    | 'refused'
        'Visual Rule Builder 1.1' | false        || true    | 'refused'
        'Visual Rule Builder 2.0' | true         || false   | 'run'
        'Visual Rule Builder 2.1' | false        || false   | 'run'
    }

    def "a Visual Rule the hub does not run comes back as a runtime failure with the hub's message"() {
        given:
        visualRule(171, 'graph', false)
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
        hubGet.register("/app/ruleBuilderPause/172/${action == 'pause'}") { params -> vrPaused[172] = (action == 'pause'); '{"success":true}' }

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

    def "a Visual Rule run answered without a success verdict is uncertain, not a refusal"() {
        given:
        visualRule(177, 'graph', false)
        hubGet.register('/app/ruleBuilderRun/177') { params -> '{"message":"queued"}' }

        when:
        def result = script.toolRunRmRule([ruleId: 177, action: 'actions'])

        then:
        result.success == false
        !result.error.contains('did not run')
        result.error.contains('without a success verdict')
        result.note.contains('may have reached the hub and run the rule')
    }

    @spock.lang.Unroll
    def "a Visual Rule pause answered with #label is checked against the rule's state (#outcome)"() {
        given: 'the rule reads not paused before the call and #after after it'
        appTypes[178] = 'Visual Rule Builder 2.0'
        int reads = 0
        hubGet.register('/app/ruleBuilderJson/178') { params -> '{}' }
        hubGet.register('/app/ruleBuilder20Json/178') { params ->
            reads++
            JsonOutput.toJson([name: 'Porch', rulePaused: reads > 1 ? after : false, ruleJson: '', validationErrors: []])
        }
        hubGet.register('/app/ruleBuilderPause/178/true') { params -> answer }

        when:
        def result = script.toolRunRmRule([ruleId: 178, action: 'pause'])

        then:
        result.success == ok
        reads == 2
        ok || result.error.contains(verdict)

        where:
        label           | answer             | after | ok    | verdict              | outcome
        'an empty body' | ''                 | true  | true  | ''                   | 'landed'
        '{}'            | '{}'               | false | false | 'no success verdict' | 'did not land'
        'success'       | '{"success":true}' | true  | true  | ''                   | 'landed'
        'success'       | '{"success":true}' | false | false | 'reported success and the rule then read paused=false' | 'did not land although the endpoint said it did'
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
        ex.message.contains('App 176 is a Visual Rules Builder app; hub_call_rule acts only on')
        hubGet.calls.isEmpty()
    }
}
