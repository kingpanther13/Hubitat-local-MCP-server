package server

import groovy.json.JsonOutput
import support.TestChildApp
import support.ToolSpecBase

/**
 * Spec for the Rule-not-found redirect feature (feat/rule-not-found-redirect).
 *
 * When an MCP-native rule tool (get_rule, export_rule, update_rule, delete_rule,
 * test_rule, clone_rule) receives an id that does not belong to any MCP child-app
 * rule, it calls findRuleAppRedirect() to check whether the id exists as a
 * Hubitat built-in rule-like app. If so, the IllegalArgumentException includes
 * a verb-appropriate redirect hint guiding the AI toward get_app_config.
 *
 * Coverage matrix per affected tool:
 *   (a) Valid MCP-native rule id         -> no redirect; original behaviour unchanged
 *   (b) Id is an RM rule (ruleApp51)     -> redirect hint in exception
 *   (c) Id not found anywhere            -> generic "not found" (no redirect)
 *   (d) Id is a non-rule-like built-in   -> no redirect (avoid false positives)
 *   (e) /hub2/appsList fetch fails       -> graceful fallback, no secondary exception
 *
 * Shared helper scenarios (a/c/d/e) are tested once against toolGetRule since
 * they exercise the shared findRuleAppRedirect helper. Per-tool tests cover
 * the redirect hint phrasing (read vs write verb) and the gate/confirm checks
 * that fire before the redirect path is reached (e.g. delete_rule confirm gate).
 */
class RuleNotFoundRedirectSpec extends ToolSpecBase {

    // ------------------------------------------------------------------ helpers

    /** Minimal appsList JSON with one app of the given classLocation. */
    private static String appsListJson(int appId, String appType, String classLocation, int appTypeId = 1) {
        JsonOutput.toJson([
            systemAppTypes: [[id: appTypeId, name: appType, classLocation: classLocation]],
            apps: [[
                data: [id: appId, name: appType, type: appType, user: false,
                       disabled: false, hidden: false, appTypeId: appTypeId],
                children: []
            ]]
        ])
    }

    /** appsList JSON with no systemAppTypes (type-string fallback path). */
    private static String appsListJsonNoTypes(int appId, String typeName) {
        JsonOutput.toJson([
            systemAppTypes: [],
            apps: [[
                data: [id: appId, name: typeName, type: typeName, user: false,
                       disabled: false, hidden: false],
                children: []
            ]]
        ])
    }

    /** appsList JSON with no matching app id (app absent). */
    private static String appsListJsonEmpty() {
        JsonOutput.toJson([systemAppTypes: [], apps: []])
    }

    // ================================================================ toolGetRule

    def "get_rule (a) valid MCP rule id -- no redirect, returns rule data"() {
        given: 'an existing MCP child-app rule'
        def childApp = new TestChildApp(id: 42L, label: 'Motion Rule')
        childApp.ruleData = [
            name: 'Motion Rule', enabled: true,
            triggers: [[type: 'time', time: '08:00']],
            conditions: [], actions: [[type: 'delay', seconds: 1]],
            localVariables: [:]
        ]
        childAppsList << childApp

        when:
        def result = script.toolGetRule('42')

        then: 'rule data returned, no exception, no hub appsList call'
        result.name == 'Motion Rule'
        hubGet.calls.empty
    }

    def "get_rule (b) RM rule id (classLocation=ruleApp51) -- read-verb redirect in exception"() {
        given: 'hub has appId 832 as a Rule Machine 5.1 rule'
        settingsMap.enableBuiltinAppRead = true
        hubGet.register('/hub2/appsList') { _ ->
            appsListJson(832, 'Rule-5.1', 'ruleApp51')
        }

        when:
        script.toolGetRule('832')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found: 832')
        ex.message.contains('get_app_config(appId=832)')
        ex.message.contains('get_rule')
        ex.message.contains('export_rule')
        ex.message.contains('clone_rule')
        // read-verb phrasing: should NOT contain write-verb phrasing
        !ex.message.contains('cannot be programmatically modified')
    }

    def "get_rule (b2) type-string fallback -- Rule Machine name without systemAppTypes"() {
        given: 'appsList has no systemAppTypes entries but type string matches'
        settingsMap.enableBuiltinAppRead = true
        hubGet.register('/hub2/appsList') { _ ->
            appsListJsonNoTypes(900, 'Rule-5.1')
        }

        when:
        script.toolGetRule('900')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found: 900')
        ex.message.contains('get_app_config(appId=900)')
    }

    def "get_rule (b3) Room Lighting classLocation -- read-verb redirect"() {
        given:
        settingsMap.enableBuiltinAppRead = true
        hubGet.register('/hub2/appsList') { _ ->
            appsListJson(101, 'Room Lights', 'roomLightsApp')
        }

        when:
        script.toolGetRule('101')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('get_app_config(appId=101)')
    }

    def "get_rule (c) id not found anywhere -- generic not found, no redirect"() {
        given: 'hub appsList has no app with this id'
        settingsMap.enableBuiltinAppRead = true
        hubGet.register('/hub2/appsList') { _ -> appsListJsonEmpty() }

        when:
        script.toolGetRule('9999')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found: 9999')
        !ex.message.contains('get_app_config')
    }

    def "get_rule (d) non-rule-like built-in app -- no redirect"() {
        given: 'hub has appId 50 as Groups and Scenes (not rule-like)'
        settingsMap.enableBuiltinAppRead = true
        hubGet.register('/hub2/appsList') { _ ->
            appsListJson(50, 'Groups and Scenes', 'groupsAndScenesApp')
        }

        when:
        script.toolGetRule('50')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found: 50')
        !ex.message.contains('get_app_config')
    }

    def "get_rule (e) appsList fetch throws -- graceful fallback, generic not found"() {
        given: 'appsList endpoint is not registered (will throw IllegalStateException from mock)'
        settingsMap.enableBuiltinAppRead = true
        // HubInternalGetMock throws IllegalStateException for unregistered paths;
        // findRuleAppRedirect must catch this and return null.
        // Do NOT register /hub2/appsList here -- the mock's default throws.

        when:
        script.toolGetRule('777')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found: 777')
        !ex.message.contains('get_app_config')
    }

    def "get_rule (e2) appsList returns empty string -- graceful fallback"() {
        given:
        settingsMap.enableBuiltinAppRead = true
        hubGet.register('/hub2/appsList') { _ -> '' }

        when:
        script.toolGetRule('888')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found: 888')
        !ex.message.contains('get_app_config')
    }

    def "get_rule (e3) appsList returns non-JSON -- graceful fallback"() {
        given:
        settingsMap.enableBuiltinAppRead = true
        hubGet.register('/hub2/appsList') { _ -> 'not valid json' }

        when:
        script.toolGetRule('889')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found: 889')
        !ex.message.contains('get_app_config')
    }

    def "get_rule (f) enableBuiltinAppRead disabled -- no redirect even if id is a built-in rule"() {
        given: 'Built-in App Tools gate is OFF (default); helper must short-circuit without calling appsList'
        // settingsMap.enableBuiltinAppRead is intentionally absent (falsy) -- this is the gate-off scenario
        hubGet.register('/hub2/appsList') { _ ->
            appsListJson(832, 'Rule-5.1', 'ruleApp51')
        }

        when:
        script.toolGetRule('832')

        then: 'generic not-found only -- no hint leaking app existence or type'
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found: 832')
        !ex.message.contains('get_app_config')
        // helper short-circuited: appsList endpoint should not have been called
        hubGet.calls.empty
    }

    // ============================================================== toolExportRule

    def "export_rule (b) RM rule id -- read-verb redirect in exception"() {
        given:
        settingsMap.enableBuiltinAppRead = true
        hubGet.register('/hub2/appsList') { _ ->
            appsListJson(500, 'Rule-5.0', 'ruleApp50')
        }

        when:
        script.toolExportRule([ruleId: '500'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found: 500')
        ex.message.contains('get_app_config(appId=500)')
        ex.message.contains('get_rule')
        ex.message.contains('export_rule')
        ex.message.contains('clone_rule')
        !ex.message.contains('cannot be programmatically modified')
    }

    def "export_rule (c) id not found anywhere -- generic not found"() {
        given:
        settingsMap.enableBuiltinAppRead = true
        hubGet.register('/hub2/appsList') { _ -> appsListJsonEmpty() }

        when:
        script.toolExportRule([ruleId: '1234'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found: 1234')
        !ex.message.contains('get_app_config')
    }

    // ============================================================== toolUpdateRule

    def "update_rule (b) RM rule id -- write-verb redirect in exception"() {
        given:
        settingsMap.enableBuiltinAppRead = true
        hubGet.register('/hub2/appsList') { _ ->
            appsListJson(300, 'Rule-5.1', 'ruleApp51')
        }

        when:
        script.toolUpdateRule('300', [name: 'New Name'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found: 300')
        ex.message.contains('get_app_config(appId=300)')
        ex.message.contains('cannot be programmatically modified')
        // write-verb phrasing: should NOT include the read-verb-exclusive phrase about
        // get_rule / export_rule only handling MCP rules (mis-wiring 'read' verb would fail this)
        !ex.message.contains('only handle MCP')
    }

    def "update_rule (c) id not found anywhere -- generic not found"() {
        given:
        settingsMap.enableBuiltinAppRead = true
        hubGet.register('/hub2/appsList') { _ -> appsListJsonEmpty() }

        when:
        script.toolUpdateRule('9999', [name: 'X'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found: 9999')
        !ex.message.contains('get_app_config')
    }

    // ============================================================== toolDeleteRule

    def "delete_rule confirm gate fires before redirect check -- no appsList call"() {
        when: 'no confirm param; gate should throw before rule lookup'
        script.toolDeleteRule([ruleId: '300'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('confirm=true')
        // appsList should NOT have been called -- confirm gate fires first
        hubGet.calls.empty
    }

    def "delete_rule (b) RM rule id -- write-verb redirect in exception"() {
        given:
        settingsMap.enableBuiltinAppRead = true
        hubGet.register('/hub2/appsList') { _ ->
            appsListJson(400, 'Rule-5.1', 'ruleApp51')
        }

        when:
        script.toolDeleteRule([ruleId: '400', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found: 400')
        ex.message.contains('get_app_config(appId=400)')
        ex.message.contains('cannot be programmatically modified')
    }

    def "delete_rule (c) id not found anywhere -- generic not found"() {
        given:
        settingsMap.enableBuiltinAppRead = true
        hubGet.register('/hub2/appsList') { _ -> appsListJsonEmpty() }

        when:
        script.toolDeleteRule([ruleId: '9999', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found: 9999')
        !ex.message.contains('get_app_config')
    }

    // =============================================================== toolTestRule

    def "test_rule (b) RM rule id -- write-verb redirect in exception"() {
        given:
        settingsMap.enableBuiltinAppRead = true
        hubGet.register('/hub2/appsList') { _ ->
            appsListJson(600, 'Rule-5.1', 'ruleApp51')
        }

        when:
        script.toolTestRule('600')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found: 600')
        ex.message.contains('get_app_config(appId=600)')
        ex.message.contains('cannot be programmatically modified')
    }

    def "test_rule (c) id not found anywhere -- generic not found"() {
        given:
        settingsMap.enableBuiltinAppRead = true
        hubGet.register('/hub2/appsList') { _ -> appsListJsonEmpty() }

        when:
        script.toolTestRule('9999')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found: 9999')
        !ex.message.contains('get_app_config')
    }

    // =============================================================== toolCloneRule

    def "clone_rule (b) RM rule id -- redirect propagates from toolExportRule"() {
        given: 'clone delegates to toolExportRule which checks the redirect'
        settingsMap.enableBuiltinAppRead = true
        hubGet.register('/hub2/appsList') { _ ->
            appsListJson(700, 'Rule-5.1', 'ruleApp51')
        }

        when:
        script.toolCloneRule([ruleId: '700'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found: 700')
        ex.message.contains('get_app_config(appId=700)')
        // export_rule is called internally by clone_rule, so read-verb phrasing applies
        ex.message.contains('export_rule')
        ex.message.contains('clone_rule')
    }

    def "clone_rule (c) id not found anywhere -- generic not found"() {
        given:
        settingsMap.enableBuiltinAppRead = true
        hubGet.register('/hub2/appsList') { _ -> appsListJsonEmpty() }

        when:
        script.toolCloneRule([ruleId: '9999'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found: 9999')
        !ex.message.contains('get_app_config')
    }
}
