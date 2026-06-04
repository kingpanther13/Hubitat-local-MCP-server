package server

import groovy.json.JsonOutput
import support.TestChildApp
import support.ToolSpecBase

/**
 * Spec for the Rule-not-found redirect feature (feat/rule-not-found-redirect).
 *
 * When an MCP-native rule tool (hub_get_custom_rule, hub_export_custom_rule, hub_update_custom_rule,
 * hub_delete_custom_rule, hub_test_custom_rule, hub_clone_custom_rule) receives an id that does not
 * belong to any MCP child-app rule, it calls findRuleAppRedirect() to check whether the
 * id exists as a Hubitat built-in rule-like app. If so, the IllegalArgumentException
 * includes a verb-appropriate redirect hint guiding the AI toward the right tool.
 *
 * Coverage matrix per affected tool:
 *   (a) Valid MCP-native rule id         -> no redirect; original behaviour unchanged
 *   (b) Id is an RM rule (type=Rule-5.1) -> redirect hint in exception
 *   (c) Id not found anywhere            -> generic "not found" (no redirect)
 *   (d) Id is a non-rule-like built-in   -> no redirect (avoid false positives)
 *   (e) /hub2/appsList fetch fails       -> graceful fallback, no secondary exception
 *   (f) Read master disabled             -> no redirect, no info-leak (gate-off scenario)
 *
 * Shared helper scenarios (a/c/d/e/f) are tested once against toolGetRule since
 * they exercise the shared findRuleAppRedirect helper. Per-tool tests cover
 * the redirect hint phrasing (read vs write verb) and the gate/confirm checks
 * that fire before the redirect path is reached (e.g. hub_delete_custom_rule confirm gate).
 */
class RuleNotFoundRedirectSpec extends ToolSpecBase {

    // ------------------------------------------------------------------ helpers

    /**
     * Minimal appsList JSON containing one app with the given type string.
     * Rule-like detection in findRuleAppRedirect is purely type-string based;
     * systemAppTypes is present (as real hub returns it) but not used by the helper.
     */
    private static String appsListJson(int appId, String appType) {
        JsonOutput.toJson([
            systemAppTypes: [],
            apps: [[
                data: [id: appId, name: appType, type: appType, user: false,
                       disabled: false, hidden: false],
                children: []
            ]]
        ])
    }

    /** appsList JSON with no matching app id (app absent). */
    private static String appsListJsonEmpty() {
        JsonOutput.toJson([systemAppTypes: [], apps: []])
    }

    /**
     * appsList JSON where the target app is a child nested under a parent app.
     * Exercises the iterative DFS path that descends into children[].
     */
    private static String appsListJsonNested(int parentId, String parentType, int childId, String childType) {
        JsonOutput.toJson([
            systemAppTypes: [],
            apps: [[
                data: [id: parentId, name: parentType, type: parentType, user: false,
                       disabled: false, hidden: false],
                children: [[
                    data: [id: childId, name: childType, type: childType, user: false,
                           disabled: false, hidden: false],
                    children: []
                ]]
            ]]
        ])
    }

    // ================================================================ toolGetRule

    def "hub_get_custom_rule (a) valid MCP rule id -- no redirect, returns rule data"() {
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

    def "hub_get_custom_rule (b) RM rule id (type=Rule-5.1) -- read-verb redirect in exception"() {
        given: 'hub has appId 832 as a Rule Machine 5.1 rule'
        hubGet.register('/hub2/appsList') { _ ->
            appsListJson(832, 'Rule-5.1')
        }

        when:
        script.toolGetRule('832')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found: 832')
        ex.message.contains('hub_get_app_config(appId=832)')
        ex.message.contains('hub_get_custom_rule')
        ex.message.contains('hub_export_custom_rule')
        ex.message.contains('hub_clone_custom_rule')
        // read-verb phrasing: should NOT contain write-verb pointer to hub_update_native_app
        !ex.message.contains('hub_update_native_app')
    }

    def "hub_get_custom_rule (b2) RM rule -- type string Rule Machine 5.1 also matches"() {
        given: 'type string "Rule Machine 5.1" matches via "rule machine" fragment'
        hubGet.register('/hub2/appsList') { _ ->
            appsListJson(900, 'Rule Machine 5.1')
        }

        when:
        script.toolGetRule('900')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found: 900')
        ex.message.contains('hub_get_app_config(appId=900)')
    }

    def "hub_get_custom_rule (b3) Room Lighting type string -- read-verb redirect"() {
        given:
        hubGet.register('/hub2/appsList') { _ ->
            appsListJson(101, 'Room Lights')
        }

        when:
        script.toolGetRule('101')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('hub_get_app_config(appId=101)')
    }

    def "hub_get_custom_rule (c) id not found anywhere -- generic not found, no redirect"() {
        given: 'hub appsList has no app with this id'
        hubGet.register('/hub2/appsList') { _ -> appsListJsonEmpty() }

        when:
        script.toolGetRule('9999')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found: 9999')
        !ex.message.contains('hub_get_app_config')
    }

    def "hub_get_custom_rule (d) non-rule-like built-in app -- no redirect"() {
        given: 'hub has appId 50 as Groups and Scenes (not rule-like)'
        hubGet.register('/hub2/appsList') { _ ->
            appsListJson(50, 'Groups and Scenes')
        }

        when:
        script.toolGetRule('50')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found: 50')
        !ex.message.contains('hub_get_app_config')
    }

    def "hub_get_custom_rule (e) appsList fetch throws -- graceful fallback, generic not found"() {
        given: 'appsList endpoint is not registered (will throw IllegalStateException from mock)'
        // HubInternalGetMock throws IllegalStateException for unregistered paths;
        // findRuleAppRedirect must catch this and return null.
        // Do NOT register /hub2/appsList here -- the mock's default throws.

        when:
        script.toolGetRule('777')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found: 777')
        !ex.message.contains('hub_get_app_config')
    }

    def "hub_get_custom_rule (e2) appsList returns empty string -- graceful fallback"() {
        given:
        hubGet.register('/hub2/appsList') { _ -> '' }

        when:
        script.toolGetRule('888')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found: 888')
        !ex.message.contains('hub_get_app_config')
    }

    def "hub_get_custom_rule (e3) appsList returns non-JSON -- graceful fallback"() {
        given:
        hubGet.register('/hub2/appsList') { _ -> 'not valid json' }

        when:
        script.toolGetRule('889')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found: 889')
        !ex.message.contains('hub_get_app_config')
    }

    def "hub_get_custom_rule (f) Read master disabled -- no redirect even if id is a built-in rule"() {
        given: 'Read master is OFF; findRuleAppRedirect must short-circuit without calling appsList'
        // The redirect is a soft enrichment gated on the Read master: with Read OFF the
        // helper returns null so no app existence / type is leaked. This is the gate-off scenario.
        settingsMap.enableRead = false
        hubGet.register('/hub2/appsList') { _ ->
            appsListJson(832, 'Rule-5.1')
        }

        when:
        script.toolGetRule('832')

        then: 'generic not-found only -- no hint leaking app existence or type'
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found: 832')
        !ex.message.contains('hub_get_app_config')
        // helper short-circuited: appsList endpoint should not have been called
        hubGet.calls.empty
    }

    def "findRuleAppRedirect type '#typeName' fires redirect via fragment match"() {
        given: 'each rule-like type string should trigger a redirect via fragment matching -- the only detection path'
        hubGet.register('/hub2/appsList') { _ ->
            appsListJson(200, typeName)
        }

        when:
        script.toolGetRule('200')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('hub_get_app_config(appId=200)')

        where:
        typeName                | _
        'Rule Machine 5.1'      | _   // fragment: "rule machine"
        'Rule-5.1'              | _   // fragment: "rule-5"
        'Rule-5.0'              | _   // fragment: "rule-5"
        'Rule-4.1'              | _   // fragment: "rule-4"
        'Room Lights'           | _   // fragment: "room light"
        'Room Lighting'         | _   // fragment: "room light"
        'Basic Rules'           | _   // fragment: "basic rules"
        'Visual Rules Builder'  | _   // fragment: "visual rule" (parent app, plural)
        'Visual Rule Builder'   | _   // fragment: "visual rule" (child instance, singular -- live-firmware name)
    }

    def "findRuleAppRedirect nested child app -- DFS descends into children"() {
        given: 'target app is nested under a parent (Room Lighting child instance pattern on live hub)'
        hubGet.register('/hub2/appsList') { _ ->
            appsListJsonNested(999, 'Room Lights', 201, 'Room Lights')
        }

        when:
        script.toolGetRule('201')

        then: 'redirect fires even though the app is not at the top level'
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found: 201')
        ex.message.contains('hub_get_app_config(appId=201)')
    }

    // ============================================================== toolExportRule

    def "hub_export_custom_rule (b) RM rule id -- read-verb redirect in exception"() {
        given:
        hubGet.register('/hub2/appsList') { _ ->
            appsListJson(500, 'Rule-5.0')
        }

        when:
        script.toolExportRule([ruleId: '500'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found: 500')
        ex.message.contains('hub_get_app_config(appId=500)')
        ex.message.contains('hub_get_custom_rule')
        ex.message.contains('hub_export_custom_rule')
        ex.message.contains('hub_clone_custom_rule')
        // read-verb phrasing: should NOT contain write-verb pointer to hub_update_native_app
        !ex.message.contains('hub_update_native_app')
    }

    def "hub_export_custom_rule (c) id not found anywhere -- generic not found"() {
        given:
        hubGet.register('/hub2/appsList') { _ -> appsListJsonEmpty() }

        when:
        script.toolExportRule([ruleId: '1234'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found: 1234')
        !ex.message.contains('hub_get_app_config')
    }

    // ============================================================== toolUpdateRule

    def "hub_update_custom_rule (b) RM rule id -- write-verb redirect in exception"() {
        given:
        hubGet.register('/hub2/appsList') { _ ->
            appsListJson(300, 'Rule-5.1')
        }

        when:
        script.toolUpdateRule('300', [name: 'New Name'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found: 300')
        ex.message.contains('hub_get_app_config(appId=300)')
        ex.message.contains('hub_update_native_app')
        ex.message.contains('hub_update_custom_rule')
        // write-verb phrasing: hub_get_custom_rule appears only in read-verb output (lists get/export/clone tools);
        // its absence here confirms we are not emitting the read-verb hint by mistake.
        !ex.message.contains('hub_get_custom_rule')
    }

    def "hub_update_custom_rule (c) id not found anywhere -- generic not found"() {
        given:
        hubGet.register('/hub2/appsList') { _ -> appsListJsonEmpty() }

        when:
        script.toolUpdateRule('9999', [name: 'X'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found: 9999')
        !ex.message.contains('hub_get_app_config')
    }

    // ============================================================== toolDeleteRule

    def "hub_delete_custom_rule confirm gate fires before redirect check -- no appsList call"() {
        when: 'no confirm param; gate should throw before rule lookup'
        script.toolDeleteRule([ruleId: '300'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('confirm=true')
        // appsList should NOT have been called -- confirm gate fires first
        hubGet.calls.empty
    }

    def "hub_delete_custom_rule (b) RM rule id -- write-verb redirect in exception"() {
        given:
        hubGet.register('/hub2/appsList') { _ ->
            appsListJson(400, 'Rule-5.1')
        }

        when:
        script.toolDeleteRule([ruleId: '400', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found: 400')
        ex.message.contains('hub_get_app_config(appId=400)')
        ex.message.contains('hub_delete_native_app')
        ex.message.contains('hub_delete_custom_rule')
    }

    def "hub_delete_custom_rule (c) id not found anywhere -- generic not found"() {
        given:
        hubGet.register('/hub2/appsList') { _ -> appsListJsonEmpty() }

        when:
        script.toolDeleteRule([ruleId: '9999', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found: 9999')
        !ex.message.contains('hub_get_app_config')
    }

    // =============================================================== toolTestRule

    def "hub_test_custom_rule (b) RM rule id -- test-verb redirect includes hub_call_rule"() {
        given: 'ruleApp51 is RM-specific -- hub_call_rule hint should appear'
        hubGet.register('/hub2/appsList') { _ ->
            appsListJson(600, 'Rule-5.1')
        }

        when:
        script.toolTestRule('600')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found: 600')
        ex.message.contains('hub_get_app_config(appId=600)')
        ex.message.contains('hub_call_rule')
        ex.message.contains('hub_test_custom_rule')
    }

    def "hub_test_custom_rule (b2) non-RM rule-like id -- test-verb redirect omits hub_call_rule"() {
        given: 'roomLightsApp is not RM -- hub_call_rule (RMUtils) would fail; hint must not appear'
        hubGet.register('/hub2/appsList') { _ ->
            appsListJson(601, 'Room Lights')
        }

        when:
        script.toolTestRule('601')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found: 601')
        ex.message.contains('hub_get_app_config(appId=601)')
        !ex.message.contains('hub_call_rule')
    }

    def "hub_test_custom_rule (c) id not found anywhere -- generic not found"() {
        given:
        hubGet.register('/hub2/appsList') { _ -> appsListJsonEmpty() }

        when:
        script.toolTestRule('9999')

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found: 9999')
        !ex.message.contains('hub_get_app_config')
    }

    // =============================================================== toolCloneRule

    def "hub_clone_custom_rule (b) RM rule id -- redirect propagates from toolExportRule"() {
        given: 'clone delegates to toolExportRule which checks the redirect'
        hubGet.register('/hub2/appsList') { _ ->
            appsListJson(700, 'Rule-5.1')
        }

        when:
        script.toolCloneRule([ruleId: '700'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found: 700')
        ex.message.contains('hub_get_app_config(appId=700)')
        // toolExportRule is called internally by toolCloneRule, so read-verb phrasing applies
        ex.message.contains('hub_export_custom_rule')
        ex.message.contains('hub_clone_custom_rule')
    }

    def "hub_clone_custom_rule (c) id not found anywhere -- generic not found"() {
        given:
        hubGet.register('/hub2/appsList') { _ -> appsListJsonEmpty() }

        when:
        script.toolCloneRule([ruleId: '9999'])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Rule not found: 9999')
        !ex.message.contains('hub_get_app_config')
    }
}
