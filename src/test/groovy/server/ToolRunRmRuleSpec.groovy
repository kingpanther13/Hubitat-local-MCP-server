package server

import groovy.json.JsonOutput
import support.RMUtilsMock
import support.ToolSpecBase

/**
 * Spec for toolRunRmRule (libraries/mcp-native-rules-lib.groovy).
 * Gateway: hub_manage_native_rules_and_apps -> hub_call_rule.
 *
 * Covers: gate-throw, missing ruleId, action-to-rmAction mapping (rule/actions
 * via RMUtils; stop/start via the stopRule button toggle because RMUtils has
 * no startRule verb), idempotent stop/start behavior based on state.stopped,
 * invalid action rejection, String ruleId coercion, non-numeric ruleId rejection.
 */
class ToolRunRmRuleSpec extends ToolSpecBase {

    RMUtilsMock rmUtils

    def setup() {
        rmUtils = new RMUtilsMock()
        rmUtils.install()
    }

    def cleanup() {
        rmUtils?.uninstall()
    }

    /**
     * Minimal statusJson stub for stop/start toggle tests — carries
     * appState entries so _readAppStateBoolean can find state.stopped.
     */
    private String minimalStatusJson(int ruleId, boolean stopped) {
        JsonOutput.toJson([
            installedApp: [id: ruleId],
            appSettings: [],
            eventSubscriptions: [],
            scheduledJobs: [],
            appState: [
                [name: "running", value: false, type: "Boolean"],
                [name: "stopped", value: stopped, type: "Boolean"]
            ],
            childAppCount: 0,
            childDeviceCount: 0
        ])
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
    def "hub_call_rule via dispatch returns -32602 envelope when Write master is disabled (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        settingsMap.enableWrite = false

        when:
        def response = mcpDriver.callTool('hub_call_rule', [ruleId: 1])

        then:
        response.error.code == -32602
        response.error.message.contains('Write tools are disabled')

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
    def "hub_call_rule via dispatch returns -32602 envelope when ruleId is missing (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_call_rule', [:])

        then:
        response.error.code == -32602
        response.error.message.toLowerCase().contains('ruleid is required')

        where:
        useGateways << [true, false]
    }

    def "action=rule dispatches runRule"() {
        when:
        def result = script.toolRunRmRule([ruleId: 101, action: 'rule'])

        then:
        result.success == true
        result.ruleId == 101
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'runRule' }
    }

    @spock.lang.Unroll
    def "hub_call_rule via dispatch action=rule dispatches runRule (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_call_rule', [ruleId: 101, action: 'rule'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.ruleId == 101
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'runRule' }

        where:
        useGateways << [true, false]
    }

    def "action=actions dispatches runRuleAct"() {
        when:
        def result = script.toolRunRmRule([ruleId: 102, action: 'actions'])

        then:
        result.success == true
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'runRuleAct' }
    }

    @spock.lang.Unroll
    def "hub_call_rule via dispatch action=actions dispatches runRuleAct (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_call_rule', [ruleId: 102, action: 'actions'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'runRuleAct' }

        where:
        useGateways << [true, false]
    }

    def "action=stop clicks stopRule button when rule is currently running (state.stopped=false)"() {
        given:
        hubGet.register('/installedapp/statusJson/103') { params -> minimalStatusJson(103, false) }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        def result = script.toolRunRmRule([ruleId: 103, action: 'stop'])

        then: "stopRule button POST issued; not routed through RMUtils.sendAction"
        result.success == true
        result.ruleId == 103
        posts.any { it.path == '/installedapp/btn' && it.body.name == 'stopRule' }
        !rmUtils.calls.any { it.method == 'sendAction' && it.action == 'stopRuleAct' }
    }

    @spock.lang.Unroll
    def "hub_call_rule via dispatch action=stop clicks stopRule button when running (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        hubGet.register('/installedapp/statusJson/103') { params -> minimalStatusJson(103, false) }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        def response = mcpDriver.callTool('hub_call_rule', [ruleId: 103, action: 'stop'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.ruleId == 103
        posts.any { it.path == '/installedapp/btn' && it.body.name == 'stopRule' }
        !rmUtils.calls.any { it.method == 'sendAction' && it.action == 'stopRuleAct' }

        where:
        useGateways << [true, false]
    }

    def "action=stop is idempotent — no-ops when rule is already stopped"() {
        given:
        hubGet.register('/installedapp/statusJson/104') { params -> minimalStatusJson(104, true) }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        def result = script.toolRunRmRule([ruleId: 104, action: 'stop'])

        then: "no button click — clicking stopRule while stopped=true would toggle to running"
        result.success == true
        result.rmAction == 'noop'
        posts.isEmpty()
    }

    @spock.lang.Unroll
    def "hub_call_rule via dispatch action=stop is idempotent when already stopped (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        hubGet.register('/installedapp/statusJson/104') { params -> minimalStatusJson(104, true) }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        def response = mcpDriver.callTool('hub_call_rule', [ruleId: 104, action: 'stop'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.rmAction == 'noop'
        posts.isEmpty()

        where:
        useGateways << [true, false]
    }

    def "action=start clicks stopRule button when rule is currently stopped (state.stopped=true)"() {
        given:
        hubGet.register('/installedapp/statusJson/105') { params -> minimalStatusJson(105, true) }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        def result = script.toolRunRmRule([ruleId: 105, action: 'start'])

        then: "stopRule button POST toggles stopped flag off + re-inits + resets private boolean"
        result.success == true
        result.ruleId == 105
        posts.any { it.path == '/installedapp/btn' && it.body.name == 'stopRule' }
    }

    @spock.lang.Unroll
    def "hub_call_rule via dispatch action=start clicks stopRule button when stopped (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        hubGet.register('/installedapp/statusJson/105') { params -> minimalStatusJson(105, true) }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        def response = mcpDriver.callTool('hub_call_rule', [ruleId: 105, action: 'start'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.ruleId == 105
        posts.any { it.path == '/installedapp/btn' && it.body.name == 'stopRule' }

        where:
        useGateways << [true, false]
    }

    def "action=start is idempotent — no-ops when rule is already running"() {
        given:
        hubGet.register('/installedapp/statusJson/106') { params -> minimalStatusJson(106, false) }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        def result = script.toolRunRmRule([ruleId: 106, action: 'start'])

        then: "no button click — rule was already running"
        result.success == true
        result.rmAction == 'noop'
        posts.isEmpty()
    }

    @spock.lang.Unroll
    def "hub_call_rule via dispatch action=start is idempotent when already running (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        hubGet.register('/installedapp/statusJson/106') { params -> minimalStatusJson(106, false) }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '{"status":"success"}']
        }

        when:
        def response = mcpDriver.callTool('hub_call_rule', [ruleId: 106, action: 'start'])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.rmAction == 'noop'
        posts.isEmpty()

        where:
        useGateways << [true, false]
    }

    def "default action (no action arg) dispatches runRule"() {
        when:
        def result = script.toolRunRmRule([ruleId: 107])

        then:
        result.success == true
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'runRule' }
    }

    @spock.lang.Unroll
    def "hub_call_rule via dispatch default action dispatches runRule (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_call_rule', [ruleId: 107])

        then:
        response.error == null
        !response.result.isError
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'runRule' }

        where:
        useGateways << [true, false]
    }

    def "invalid action throws IllegalArgumentException"() {
        when:
        script.toolRunRmRule([ruleId: 108, action: 'explode'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('invalid action')
    }

    @spock.lang.Unroll
    def "hub_call_rule via dispatch returns -32602 envelope on invalid action (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_call_rule', [ruleId: 108, action: 'explode'])

        then:
        response.error.code == -32602
        response.error.message.toLowerCase().contains('invalid action')

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

    def "non-numeric ruleId throws IllegalArgumentException"() {
        when:
        script.toolRunRmRule([ruleId: 'not-a-number'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('integer')
    }

    @spock.lang.Unroll
    def "hub_call_rule via dispatch returns -32602 envelope on non-numeric ruleId (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways

        when:
        def response = mcpDriver.callTool('hub_call_rule', [ruleId: 'not-a-number'])

        then:
        response.error.code == -32602
        response.error.message.toLowerCase().contains('integer')

        where:
        useGateways << [true, false]
    }

    def "gateway dispatch via handleGateway routes to hub_call_rule"() {
        when:
        def result = script.handleGateway('hub_manage_native_rules_and_apps', 'hub_call_rule', [ruleId: 300, action: 'rule'])

        then:
        result.success == true
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'runRule' }
    }
}
