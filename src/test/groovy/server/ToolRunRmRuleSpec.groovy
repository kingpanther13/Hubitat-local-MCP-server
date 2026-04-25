package server

import groovy.json.JsonOutput
import support.RMUtilsMock
import support.ToolSpecBase

/**
 * Spec for toolRunRmRule (hubitat-mcp-server.groovy approx line 7788).
 * Gateway: manage_native_rules_and_apps -> run_rm_rule.
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

    def "throws when Built-in App Read is disabled"() {
        given:
        settingsMap.enableBuiltinApp = false

        when:
        script.toolRunRmRule([ruleId: 1])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Built-in App')
    }

    def "throws when ruleId is missing"() {
        given:
        settingsMap.enableBuiltinApp = true

        when:
        script.toolRunRmRule([:])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('ruleid is required')
    }

    def "action=rule dispatches runRule"() {
        given:
        settingsMap.enableBuiltinApp = true

        when:
        def result = script.toolRunRmRule([ruleId: 101, action: 'rule'])

        then:
        result.success == true
        result.ruleId == 101
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'runRule' }
    }

    def "action=actions dispatches runRuleAct"() {
        given:
        settingsMap.enableBuiltinApp = true

        when:
        def result = script.toolRunRmRule([ruleId: 102, action: 'actions'])

        then:
        result.success == true
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'runRuleAct' }
    }

    def "action=stop clicks stopRule button when rule is currently running (state.stopped=false)"() {
        given:
        settingsMap.enableBuiltinApp = true
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

    def "action=stop is idempotent — no-ops when rule is already stopped"() {
        given:
        settingsMap.enableBuiltinApp = true
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

    def "action=start clicks stopRule button when rule is currently stopped (state.stopped=true)"() {
        given:
        settingsMap.enableBuiltinApp = true
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

    def "action=start is idempotent — no-ops when rule is already running"() {
        given:
        settingsMap.enableBuiltinApp = true
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

    def "default action (no action arg) dispatches runRule"() {
        given:
        settingsMap.enableBuiltinApp = true

        when:
        def result = script.toolRunRmRule([ruleId: 107])

        then:
        result.success == true
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'runRule' }
    }

    def "invalid action throws IllegalArgumentException"() {
        given:
        settingsMap.enableBuiltinApp = true

        when:
        script.toolRunRmRule([ruleId: 108, action: 'explode'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('invalid action')
    }

    def "String ruleId is coerced to Integer before dispatch"() {
        given:
        settingsMap.enableBuiltinApp = true

        when:
        def result = script.toolRunRmRule([ruleId: '202'])

        then:
        result.success == true
        result.ruleId == 202
        result.ruleId instanceof Integer
    }

    def "non-numeric ruleId throws IllegalArgumentException"() {
        given:
        settingsMap.enableBuiltinApp = true

        when:
        script.toolRunRmRule([ruleId: 'not-a-number'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('integer')
    }

    def "gateway dispatch via handleGateway routes to run_rm_rule"() {
        given:
        settingsMap.enableBuiltinApp = true

        when:
        def result = script.handleGateway('manage_native_rules_and_apps', 'run_rm_rule', [ruleId: 300, action: 'rule'])

        then:
        result.success == true
        rmUtils.calls.any { it.method == 'sendAction' && it.action == 'runRule' }
    }
}
