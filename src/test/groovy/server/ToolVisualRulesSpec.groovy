package server

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Unroll
import support.ToolSpecBase

/**
 * Spec for the Visual Rules Builder tools whose impls live in the McpVisualRulesLib
 * #include library: hub_get_visual_rule (read), hub_set_visual_rule (write/destructive),
 * hub_delete_visual_rule (write/destructive).
 *
 * Hub API contracts exercised here (all live-verified on fw 2.5.x):
 *   GET  /hub2/appsList                          -> {apps:[{data:{id,name,type,disabled},children:[...]}]}
 *   GET  /app/ruleBuilder20Json/<id>             -> graph rule {name,rulePaused,ruleJson:<JSON STRING>,validationErrors}
 *                                                   or {"success":false,...} for EVERY non-graph id
 *   GET  /app/ruleBuilderJson/<id>               -> raw state of ANY app ({} when nonexistent); only the
 *                                                   whenNodes+thenNodes shape identifies a classic VRB rule
 *   GET  /app/createVisualRuleBuilderRule        -> builder page; new appId rides in a window global
 *                                                   (HubitatRuleBuilder20AppId = graph, HubitatRuleBuilderAppId = classic)
 *   POST /app/ruleBuilder20Json/<id>             -> {name, ruleJson:<JSON STRING>} (double-encoded graph)
 *   POST /app/ruleBuilderJson/<id>               -> {name, rulePaused, whenNodes, thenNodes, elseNodes} (real arrays)
 *   GET  /app/ruleBuilderPause/<id>/<bool>       -> {success}
 *   GET  /installedapp/forcedelete/<id>/quiet    -> delete (verified via /installedapp/json)
 *   GET  /installedapp/json/<id>                 -> {id,name,type,disabled,user} (existence + type check)
 *
 * Mocking: hubInternalGet routes through the harness hubGet.register; hubInternalGetRaw and
 * hubInternalPostJson are purely dynamic main-file methods, stubbed per-test on script.metaClass
 * (the harness wipes metaClass between tests). Dispatch-envelope counterparts ride mcpDriver.
 */
class ToolVisualRulesSpec extends ToolSpecBase {

    private static final String GRAPH_NOT_FOUND = '{"success":false,"message":"Rule builder instance not found"}'

    // Fresh per feature (Spock builds a new spec instance per feature method).
    List rawPaths = []
    List posts = []

    private void enableWrite() {
        settingsMap.enableWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L  // == the fixed harness now(), so "within 24h"
    }

    private static String json(Object o) { JsonOutput.toJson(o) }

    private static Map classicDefinition() {
        [whenNodes: [[result: true, deviceIds: [59], switches: [59], switchEvent: 'Turns off',
                      index: 0, triggerType: 'switch', type: 'when']],
         thenNodes: [[actionType: 'turnOff', deviceIds: [122], switches: [122], index: 0, type: 'then']],
         elseNodes: []]
    }

    private static Map graphDefinition() {
        [version: 1,
         nodes: [[id: 'n1', type: 'trigger', deviceIds: [59]], [id: 'n2', type: 'action', command: 'off']],
         edges: [[from: 'n1', to: 'n2']]]
    }

    private void registerAppsList(List children) {
        hubGet.register('/hub2/appsList') { params ->
            json([apps: [
                [key: 5, data: [id: 5, appTypeId: 1, name: 'Rule Machine', type: 'Rule Machine 5.1', disabled: false], children: []],
                [key: 700, data: [id: 700, appTypeId: 99, name: 'Visual Rules Builder', type: 'Visual Rules Builder', disabled: false],
                 children: children]
            ]])
        }
    }

    /** hubInternalGetRaw stub: records every path, serves the builder page HTML (forcedelete callers ignore the body). */
    private void stubRawPage(String html) {
        def paths = rawPaths
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, int t = 30, boolean r = false ->
            paths << path
            [status: 200, location: null, data: html]
        }
    }

    /** hubInternalGetRaw stub for delete paths: records, fires onDelete, answers like the live 302. */
    private void stubRawDelete(Closure onDelete = null) {
        def paths = rawPaths
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, int t = 30, boolean r = false ->
            paths << path
            if (onDelete) onDelete.call(path)
            [status: 302, location: '/installedapp/list', data: null]
        }
    }

    /** hubInternalPostJson stub: records {path, body}; responder's return is the parsed-Map response. */
    private void stubPostJson(Closure responder = null) {
        def captured = posts
        script.metaClass.hubInternalPostJson = { String path, String jsonBody, int timeout = 420, boolean isRetry = false ->
            captured << [path: path, body: jsonBody]
            responder ? responder.call(path, jsonBody) : null
        }
    }

    // ==================== hub_get_visual_rule: list mode ====================

    def "list mode returns appId/name/disabled for every child of the Visual Rules Builder parent"() {
        given:
        registerAppsList([
            [key: 701, data: [id: 701, appTypeId: 100, name: 'Hall light', type: 'Visual Rule Builder', disabled: false], children: []],
            [key: 702, data: [id: 702, appTypeId: 100, name: 'Door alert', type: 'Visual Rule Builder', disabled: true], children: []]
        ])

        when:
        def result = script.toolGetVisualRule([:])

        then:
        result.success == true
        result.count == 2
        result.rules == [[appId: 701, name: 'Hall light', disabled: false],
                         [appId: 702, name: 'Door alert', disabled: true]]
        result.note.contains('Pass appId')
    }

    def "list mode returns an actionable error when the Visual Rules Builder parent app is not installed"() {
        given:
        hubGet.register('/hub2/appsList') { params ->
            json([apps: [[key: 5, data: [id: 5, name: 'Rule Machine', type: 'Rule Machine 5.1', disabled: false], children: []]]])
        }

        when:
        def result = script.toolGetVisualRule([:])

        then:
        result.success == false
        result.error.contains('Visual Rules Builder parent app is not installed')
        result.error.contains('Add Built-In App')
    }

    def "list mode with an installed parent but zero children notes how to create one"() {
        given:
        registerAppsList([])

        when:
        def result = script.toolGetVisualRule([:])

        then:
        result.success == true
        result.count == 0
        result.rules == []
        result.note.contains('Create one with hub_set_visual_rule')
    }

    // ==================== hub_get_visual_rule: by id ====================

    def "get by id returns a graph rule with the double-encoded ruleJson parsed into definition"() {
        given:
        def ruleJsonStr = json(graphDefinition())
        hubGet.register('/app/ruleBuilder20Json/12') { params ->
            json([name: 'Graph rule', rulePaused: true, ruleJson: ruleJsonStr, validationErrors: ['edge n1->n2 dangling']])
        }

        when:
        def result = script.toolGetVisualRule([appId: 12])

        then:
        result.success == true
        result.appId == 12
        result.format == 'graph'
        result.name == 'Graph rule'
        result.rulePaused == true
        result.validationErrors == ['edge n1->n2 dangling']
        result.definition.nodes*.id == ['n1', 'n2']
        result.definition.edges[0].from == 'n1'
    }

    def "get by id falls through to classic when 20Json says not-found and ruleBuilderJson has when/then nodes"() {
        given:
        hubGet.register('/app/ruleBuilder20Json/31') { params -> GRAPH_NOT_FOUND }
        hubGet.register('/app/ruleBuilderJson/31') { params ->
            json(classicDefinition() + [name: 'Door alert', rulePaused: true, waitTable: [:], promptHistory: []])
        }

        when:
        def result = script.toolGetVisualRule([appId: 31])

        then:
        result.success == true
        result.format == 'classic'
        result.name == 'Door alert'
        result.rulePaused == true
        result.whenNodes[0].triggerType == 'switch'
        result.thenNodes[0].actionType == 'turnOff'
        result.elseNodes == []
    }

    def "get by id does NOT leak a foreign app's state map -- ruleBuilderJson serializes ANY app"() {
        given: 'a non-VRB app: ruleBuilderJson answers with its raw state (no whenNodes/thenNodes)'
        hubGet.register('/app/ruleBuilder20Json/88') { params -> GRAPH_NOT_FOUND }
        hubGet.register('/app/ruleBuilderJson/88') { params ->
            json([label: 'Thermostat Scheduler', schedule: [wake: '06:30'], secretSetting: 'leaky'])
        }
        hubGet.register('/installedapp/json/88') { params ->
            json([id: 88, name: 'Thermostat Scheduler', type: 'Thermostat Scheduler', disabled: false, user: false])
        }

        when:
        def result = script.toolGetVisualRule([appId: 88])

        then:
        result.success == false
        result.appType == 'Thermostat Scheduler'
        result.error.contains('not a Visual Rules Builder rule')

        and: 'none of the foreign state surfaced'
        !result.containsKey('secretSetting')
        !result.containsKey('schedule')
        !result.containsKey('whenNodes')
    }

    def "get by id for a nonexistent app returns a No-installed-app error"() {
        given: 'live shapes: 20Json says not-found, ruleBuilderJson answers {}, installedapp/json is empty'
        hubGet.register('/app/ruleBuilder20Json/999') { params -> GRAPH_NOT_FOUND }
        hubGet.register('/app/ruleBuilderJson/999') { params -> '{}' }
        hubGet.register('/installedapp/json/999') { params -> '' }

        when:
        def result = script.toolGetVisualRule([appId: 999])

        then:
        result.success == false
        result.error.contains('No installed app with appId 999')
        result.note.contains('hub_get_visual_rule')
    }

    // ==================== hub_set_visual_rule: create ====================

    def "create golden path (classic hub): saves nodes, defaults rulePaused=false, verifies via read-back"() {
        given:
        enableWrite()
        stubRawPage('<html><script>window.HubitatRuleBuilderAppId = 1234;</script></html>')
        def savedState = [:]
        stubPostJson { path, body -> savedState.putAll(new JsonSlurper().parseText(body) as Map); null }
        hubGet.register('/app/ruleBuilder20Json/1234') { params -> GRAPH_NOT_FOUND }
        hubGet.register('/app/ruleBuilderJson/1234') { params -> json(savedState) }

        when:
        def result = script.toolSetVisualRule([name: 'Hall light', definition: classicDefinition(), confirm: true])

        then: 'the hub-create page was fetched and the save POSTed to the classic endpoint'
        rawPaths == ['/app/createVisualRuleBuilderRule']
        posts.size() == 1
        posts[0].path == '/app/ruleBuilderJson/1234'

        and: 'POST body carries the full classic envelope with rulePaused defaulting to false'
        def body = new JsonSlurper().parseText(posts[0].body as String)
        body.name == 'Hall light'
        body.rulePaused == false
        body.whenNodes[0].switchEvent == 'Turns off'
        body.thenNodes[0].actionType == 'turnOff'
        body.elseNodes == []

        and: 'result is the verified created rule with the read-back definition echoed'
        result.success == true
        result.created == true
        result.format == 'classic'
        result.verified == true
        result.appId == 1234
        result.name == 'Hall light'
        result.rulePaused == false
        result.definition.whenNodes[0].triggerType == 'switch'
    }

    def "create golden path (graph hub): ruleJson is POSTed as a JSON STRING (double-encoded)"() {
        given:
        enableWrite()
        stubRawPage('<html><script>window.HubitatRuleBuilder20AppId = 777;</script></html>')
        def savedGraph = [:]
        stubPostJson { path, body ->
            def b = new JsonSlurper().parseText(body)
            savedGraph.name = b.name
            savedGraph.ruleJson = b.ruleJson
            [name: b.name, ruleJson: b.ruleJson, validationErrors: []]
        }
        hubGet.register('/app/ruleBuilder20Json/777') { params ->
            json([name: savedGraph.name, rulePaused: false, ruleJson: savedGraph.ruleJson, validationErrors: []])
        }

        when:
        def result = script.toolSetVisualRule([name: 'Graph rule', definition: graphDefinition(), confirm: true])

        then: 'POSTed to the graph endpoint with ruleJson double-encoded'
        posts[0].path == '/app/ruleBuilder20Json/777'
        def body = new JsonSlurper().parseText(posts[0].body as String)
        body.name == 'Graph rule'
        body.ruleJson instanceof String
        def innerGraph = new JsonSlurper().parseText(body.ruleJson as String)
        innerGraph.nodes*.id == ['n1', 'n2']
        innerGraph.version == 1

        and:
        result.success == true
        result.created == true
        result.format == 'graph'
        result.verified == true
        result.appId == 777
        result.definition.nodes*.id == ['n1', 'n2']
    }

    def "create (graph) echoes hub-side validationErrors with a saved-but note"() {
        given:
        enableWrite()
        stubRawPage('<html>window.HubitatRuleBuilder20AppId = 778</html>')
        def savedGraph = [:]
        stubPostJson { path, body ->
            def b = new JsonSlurper().parseText(body)
            savedGraph.name = b.name
            savedGraph.ruleJson = b.ruleJson
            [name: b.name, ruleJson: b.ruleJson, validationErrors: ['node n2 has no device']]
        }
        hubGet.register('/app/ruleBuilder20Json/778') { params ->
            json([name: savedGraph.name, rulePaused: false, ruleJson: savedGraph.ruleJson, validationErrors: ['node n2 has no device']])
        }

        when:
        def result = script.toolSetVisualRule([name: 'Half-built', definition: graphDefinition(), confirm: true])

        then: 'saved + verified, but the validation problems surface'
        result.success == true
        result.verified == true
        result.validationErrors == ['node n2 has no device']
        result.note.contains('validation errors')
    }

    def "create follows the one-redirect shape of createVisualRuleBuilderRule (absolute location URL)"() {
        given:
        enableWrite()
        def paths = rawPaths
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, int t = 30, boolean r = false ->
            paths << path
            [status: 302, location: 'http://127.0.0.1:8080/app/ruleBuilder/1234', data: null]
        }
        hubGet.register('/app/ruleBuilder/1234') { params -> '<html>window.HubitatRuleBuilderAppId = 1234</html>' }
        def savedState = [:]
        stubPostJson { path, body -> savedState.putAll(new JsonSlurper().parseText(body) as Map); null }
        hubGet.register('/app/ruleBuilder20Json/1234') { params -> GRAPH_NOT_FOUND }
        hubGet.register('/app/ruleBuilderJson/1234') { params -> json(savedState) }

        when:
        def result = script.toolSetVisualRule([name: 'Redirected', definition: classicDefinition(), confirm: true])

        then: 'the redirect target was fetched as a hub-relative path and the create completed'
        hubGet.calls*.path.contains('/app/ruleBuilder/1234')
        result.success == true
        result.appId == 1234
    }

    def "create with a definition format that mismatches the hub-native format force-deletes the orphan shell"() {
        given: 'hub creates classic-format children, but the caller supplies a graph definition'
        enableWrite()
        stubRawPage('<html>window.HubitatRuleBuilderAppId = 555</html>')

        when:
        def result = script.toolSetVisualRule([name: 'Mismatch', definition: graphDefinition(), confirm: true])

        then:
        result.success == false
        result.hubNativeFormat == 'classic'
        result.error.contains('classic-format rules')
        result.error.contains('graph-format')
        result.note.contains('cleaned up')

        and: 'the empty child created during the attempt was force-deleted'
        rawPaths == ['/app/createVisualRuleBuilderRule', '/installedapp/forcedelete/555/quiet']
    }

    @Unroll
    def "create argument validation throws IllegalArgumentException: #label"() {
        given:
        enableWrite()

        when:
        script.toolSetVisualRule(args)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains(fragment)

        where:
        label                                  | args                                                                                    | fragment
        'missing name'                         | [definition: [whenNodes: [], thenNodes: [], elseNodes: []], confirm: true]             | 'name is required'
        'missing definition'                   | [name: 'X', confirm: true]                                                              | 'definition is required'
        'definition mixes graph+classic keys'  | [name: 'X', definition: [nodes: [], whenNodes: []], confirm: true]                     | 'mixes graph keys'
        'definition has neither format'        | [name: 'X', definition: [foo: 'bar'], confirm: true]                                   | 'must be either a graph'
        'definition string is invalid JSON'    | [name: 'X', definition: 'not json {{{', confirm: true]                                 | 'not valid JSON'
    }

    // ==================== hub_set_visual_rule: edit ====================

    def "edit full replacement (classic) preserves the rule's CURRENT rulePaused=true when paused is not passed"() {
        given: 'an existing paused classic rule'
        enableWrite()
        def state42 = [name: 'Door alert', rulePaused: true, promptHistory: []] + classicDefinition()
        hubGet.register('/app/ruleBuilder20Json/42') { params -> GRAPH_NOT_FOUND }
        hubGet.register('/app/ruleBuilderJson/42') { params -> json(state42) }
        stubPostJson { path, body -> state42.putAll(new JsonSlurper().parseText(body) as Map); null }
        def newDefinition = [whenNodes: [[result: true, deviceIds: [60], switches: [60], switchEvent: 'Turns on',
                                          index: 0, triggerType: 'switch', type: 'when']],
                             thenNodes: [[actionType: 'turnOn', deviceIds: [123], switches: [123], index: 0, type: 'then']],
                             elseNodes: []]

        when:
        def result = script.toolSetVisualRule([appId: 42, definition: newDefinition, confirm: true])

        then: 'regression-sensitive: the classic POST body always carries rulePaused, so it must echo the CURRENT paused state'
        posts[0].path == '/app/ruleBuilderJson/42'
        def body = new JsonSlurper().parseText(posts[0].body as String)
        body.rulePaused == true
        body.name == 'Door alert'
        body.whenNodes[0].switchEvent == 'Turns on'

        and: 'verified replacement, with the prior definition returned as a recovery aid'
        result.success == true
        result.created == false
        result.verified == true
        result.rulePaused == true
        result.definition.thenNodes[0].actionType == 'turnOn'
        result.previousDefinition.whenNodes[0].switchEvent == 'Turns off'
    }

    def "edit rename-only (graph) re-POSTs the existing ruleJson string under the new name"() {
        given:
        enableWrite()
        def ruleJsonStr = json(graphDefinition())
        def state77 = [name: 'Old name', rulePaused: false, ruleJson: ruleJsonStr]
        hubGet.register('/app/ruleBuilder20Json/77') { params ->
            json([name: state77.name, rulePaused: state77.rulePaused, ruleJson: state77.ruleJson, validationErrors: []])
        }
        stubPostJson { path, body ->
            def b = new JsonSlurper().parseText(body)
            state77.name = b.name
            state77.ruleJson = b.ruleJson
            [name: b.name, ruleJson: b.ruleJson, validationErrors: []]
        }

        when:
        def result = script.toolSetVisualRule([appId: 77, name: 'New name', confirm: true])

        then: 'the save endpoint has no rename-only verb, so the existing graph string rides along unchanged'
        posts[0].path == '/app/ruleBuilder20Json/77'
        def body = new JsonSlurper().parseText(posts[0].body as String)
        body.name == 'New name'
        body.ruleJson == ruleJsonStr

        and:
        result.success == true
        result.format == 'graph'
        result.name == 'New name'
    }

    def "edit pause-only calls /app/ruleBuilderPause/<id>/true and verifies via read-back"() {
        given:
        enableWrite()
        def state9 = [name: 'Hall light', rulePaused: false, promptHistory: []] + classicDefinition()
        hubGet.register('/app/ruleBuilder20Json/9') { params -> GRAPH_NOT_FOUND }
        hubGet.register('/app/ruleBuilderJson/9') { params -> json(state9) }
        hubGet.register('/app/ruleBuilderPause/9/true') { params -> state9.rulePaused = true; '{"success":true}' }
        stubPostJson()  // must NOT be hit; recorded for the assert below

        when:
        def result = script.toolSetVisualRule([appId: 9, paused: true, confirm: true])

        then:
        hubGet.calls*.path.contains('/app/ruleBuilderPause/9/true')
        posts.isEmpty()  // pause-only never re-saves the nodes
        result.success == true
        result.format == 'classic'
        result.rulePaused == true
    }

    def "edit with appId but nothing to change throws IllegalArgumentException"() {
        given:
        enableWrite()

        when:
        script.toolSetVisualRule([appId: 5, confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Nothing to change')
    }

    def "edit with a definition format that mismatches the rule's format returns success=false without saving"() {
        given: 'rule 42 is classic; caller supplies a graph definition'
        enableWrite()
        hubGet.register('/app/ruleBuilder20Json/42') { params -> GRAPH_NOT_FOUND }
        hubGet.register('/app/ruleBuilderJson/42') { params ->
            json([name: 'Door alert', rulePaused: false] + classicDefinition())
        }
        stubPostJson()

        when:
        def result = script.toolSetVisualRule([appId: 42, definition: graphDefinition(), confirm: true])

        then:
        result.success == false
        result.format == 'classic'
        result.error.contains('classic-format')
        result.error.contains('graph-format')
        result.note.contains('hub_get_visual_rule')
        posts.isEmpty()
    }

    // ==================== hub_delete_visual_rule ====================

    def "delete golden path: type-gates, force-deletes, verifies gone, returns the pre-delete definition"() {
        given:
        enableWrite()
        def deleted = false
        hubGet.register('/app/ruleBuilder20Json/31') { params -> GRAPH_NOT_FOUND }
        hubGet.register('/app/ruleBuilderJson/31') { params ->
            json([name: 'Door alert', rulePaused: false] + classicDefinition())
        }
        hubGet.register('/installedapp/json/31') { params ->
            deleted ? '' : json([id: 31, name: 'Door alert', type: 'Visual Rule Builder', disabled: false, user: true])
        }
        stubRawDelete { path -> deleted = true }

        when:
        def result = script.toolDeleteVisualRule([appId: 31, confirm: true])

        then:
        rawPaths == ['/installedapp/forcedelete/31/quiet']
        result.success == true
        result.verified == true
        result.appId == 31
        result.name == 'Door alert'
        result.format == 'classic'
        result.predeleteDefinition.whenNodes[0].triggerType == 'switch'
        result.note.contains('predeleteDefinition')
    }

    def "delete reports success=false verified=false when the hub still answers for the app afterwards"() {
        given:
        enableWrite()
        hubGet.register('/app/ruleBuilder20Json/31') { params -> GRAPH_NOT_FOUND }
        hubGet.register('/app/ruleBuilderJson/31') { params ->
            json([name: 'Sticky', rulePaused: false] + classicDefinition())
        }
        hubGet.register('/installedapp/json/31') { params ->
            json([id: 31, name: 'Sticky', type: 'Visual Rule Builder', disabled: false, user: true])
        }
        stubRawDelete()  // hub accepted the request but the app survives

        when:
        def result = script.toolDeleteVisualRule([appId: 31, confirm: true])

        then:
        result.success == false
        result.verified == false
        result.note.contains('still reports')
    }

    def "delete refuses a non-VRB appId and names its real type"() {
        given:
        enableWrite()
        hubGet.register('/app/ruleBuilder20Json/88') { params -> GRAPH_NOT_FOUND }
        hubGet.register('/app/ruleBuilderJson/88') { params -> json([label: 'some RM rule state']) }
        hubGet.register('/installedapp/json/88') { params ->
            json([id: 88, name: 'My RM Rule', type: 'Rule-5.1', disabled: false, user: true])
        }
        stubRawDelete()

        when:
        def result = script.toolDeleteVisualRule([appId: 88, confirm: true])

        then:
        result.success == false
        result.appType == 'Rule-5.1'
        result.error.contains('not a Visual Rules Builder rule')

        and: 'forcedelete was never reached for the foreign app'
        rawPaths.isEmpty()
    }

    def "delete without appId throws IllegalArgumentException"() {
        given:
        enableWrite()

        when:
        script.toolDeleteVisualRule([confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('appId is required')
    }

    // ==================== safety gates ====================

    def "hub_set_visual_rule throws SAFETY CHECK FAILED when confirm is not provided"() {
        when:
        script.toolSetVisualRule([name: 'X', definition: classicDefinition()])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
    }

    def "hub_delete_visual_rule throws SAFETY CHECK FAILED when confirm is not provided"() {
        when:
        script.toolDeleteVisualRule([appId: 31])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('SAFETY CHECK FAILED')
    }

    def "hub_set_visual_rule is blocked by the Write master via executeTool"() {
        given:
        settingsMap.enableWrite = false

        when:
        script.executeTool('hub_set_visual_rule', [name: 'X', definition: classicDefinition(), confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('Write tools are disabled')
    }

    def "hub_set_native_app appType=visual_rule is rejected with a pointer at hub_set_visual_rule"() {
        given:
        enableWrite()

        when:
        script.executeTool('hub_set_native_app', [appType: 'visual_rule', name: 'X', confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains('hub_set_visual_rule')
        ex.message.contains('Vue-JSON')
    }

    // ==================== dispatch-envelope (integration) ====================

    @Unroll
    def "hub_get_visual_rule via dispatch returns the VRB rule list (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        registerAppsList([
            [key: 701, data: [id: 701, appTypeId: 100, name: 'Hall light', type: 'Visual Rule Builder', disabled: false], children: []]
        ])

        when:
        def response = mcpDriver.callTool('hub_get_visual_rule', [:])

        then:
        response.error == null
        response.id == mcpDriver.lastSentId
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.count == 1
        inner.rules[0].appId == 701
        inner.rules[0].name == 'Hall light'

        where:
        useGateways << [true, false]
    }

    @Unroll
    def "hub_set_visual_rule via dispatch returns -32602 when confirm is not provided (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        enableWrite()

        when:
        def response = mcpDriver.callTool('hub_set_visual_rule', [name: 'X', definition: [whenNodes: [], thenNodes: [], elseNodes: []]])

        then:
        response.error.code == -32602
        response.error.message.contains('SAFETY CHECK FAILED')

        where:
        useGateways << [true, false]
    }

    def "hub_get_visual_rule dispatches as a hub_manage_rule_machine gateway sub-tool"() {
        given:
        settingsMap.useGateways = true
        registerAppsList([
            [key: 701, data: [id: 701, appTypeId: 100, name: 'Hall light', type: 'Visual Rule Builder', disabled: false], children: []],
            [key: 702, data: [id: 702, appTypeId: 100, name: 'Door alert', type: 'Visual Rule Builder', disabled: true], children: []]
        ])
        mcpDriver.pushBody([
            jsonrpc: '2.0', id: 215, method: 'tools/call',
            params: [name: 'hub_manage_rule_machine', arguments: [tool: 'hub_get_visual_rule', args: [:]]]
        ])

        when:
        script.handleMcpRequest()
        def response = mcpDriver.parseResponseJson()

        then:
        response.error == null
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.rules*.name == ['Hall light', 'Door alert']
    }

    def "hub_get_visual_rule also dispatches through the hub_read_rules gateway (multi-gateway membership)"() {
        given:
        settingsMap.useGateways = true
        registerAppsList([
            [key: 701, data: [id: 701, appTypeId: 100, name: 'Hall light', type: 'Visual Rule Builder', disabled: false], children: []]
        ])
        mcpDriver.pushBody([
            jsonrpc: '2.0', id: 216, method: 'tools/call',
            params: [name: 'hub_read_rules', arguments: [tool: 'hub_get_visual_rule', args: [:]]]
        ])

        when:
        script.handleMcpRequest()
        def response = mcpDriver.parseResponseJson()

        then:
        response.error == null
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.rules*.appId == [701]
    }
}
