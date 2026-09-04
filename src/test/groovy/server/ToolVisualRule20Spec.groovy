package server

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Unroll
import support.ToolSpecBase

/**
 * Spec for the Visual Rule Builder 2.0 surface in McpVisualRulesLib: the pure compose /
 * decompose / validate / classic-translate functions, and the tool behaviour they drive
 * (editor-form authoring, pre-flight refusal, translated classic input, the `editor` read,
 * list-mode `version`, and the stored-but-not-activated draft contract).
 *
 * Ground truth for the graph shape and the error wording is the hub's own VRB2 authoring guide
 * plus the Rule Builder 2.0 Vue chunk's compose/decompose pair, both live-verified on platform
 * 2.5.1.181. Hub API contracts are the same ones ToolVisualRulesSpec documents.
 */
class ToolVisualRule20Spec extends ToolSpecBase {

    private static final String GRAPH_NOT_FOUND = '{"success":false,"message":"Rule builder instance not found"}'

    // The per-version child-create routes the VRB parent (id 700 in registerAppsList) exposes.
    private static final String CREATE_1_0 = '/installedapp/createchild/hubitat/Visual Rule Builder 1.0/parent/700'
    private static final String CREATE_2_0 = '/installedapp/createchild/hubitat/Visual Rule Builder 2.0/parent/700'

    List rawPaths = []
    List posts = []

    private void enableWrite() {
        settingsMap.enableWrite = true
        stateMap.lastBackupTimestamp = 1234567890000L
    }

    private static String json(Object o) { JsonOutput.toJson(o) }

    /** A minimal graph that satisfies the pre-flight: trigger -> triggerMerge -> decision -> action. */
    private static Map validGraph() {
        [version: 1,
         nodes: [[id: 't1', kind: 'trigger', type: 'switch', config: [switches: [59], switchEvent: 'Turns off']],
                 [id: 'tm', kind: 'merge', type: 'triggerMerge', config: [:]],
                 [id: 'd1', kind: 'decision', type: 'all', config: [conditions: []]],
                 [id: 'a1', kind: 'action', type: 'turnOff', config: [switches: [59]]]],
         edges: [[from: 't1', to: 'tm', port: 'next'],
                 [from: 'tm', to: 'd1', port: 'next'],
                 [from: 'd1', to: 'a1', port: 'true']]]
    }

    private static Map editorDefinition() {
        [triggers: [[type: 'motion', config: [motionSensors: [101], motionSensorEvent: 'Motion starts']],
                    [type: 'systemMode', config: [modes: [2]]]],
         conditions: [[type: 'daysOfWeek', config: [daysOfWeek: [1, 2, 3, 4, 5]]],
                      [type: 'timeIsBetween', config: [triggerCondition: 'sunsetToSunrise']]],
         decisionType: 'any',
         thenActions: [[type: 'setBrightness', config: [dimmers: [201], brightness: 45]]],
         elseActions: [[type: 'turnOff', config: [switches: [201]]]],
         commonActions: [[type: 'sendNotification', config: [notificationDevices: [301], notificationMessage: 'Hall rule ran']]]]
    }

    private static Map classicDefinition() {
        [whenNodes: [[result: true, deviceIds: [42], motionSensors: [42], motionSensorEvent: 'Motion starts',
                      index: 0, triggerType: 'motion', type: 'when', description: '<b>Motion starts</b>'],
                     [result: true, deviceIds: [], daysOfWeek: [1, 2], index: 1, triggerType: 'daysOfWeek', type: 'when']],
         thenNodes: [[actionType: 'turnOn', switches: [17], deviceIds: [17], index: 0, type: 'then']],
         elseNodes: []]
    }

    private void registerAppsList(List children) {
        hubGet.register('/hub2/appsList') { params ->
            json([apps: [
                [key: 700, data: [id: 700, appTypeId: 99, name: 'Visual Rules Builder', type: 'Visual Rules Builder', disabled: false],
                 children: children]
            ]])
        }
    }

    /** hubInternalGetRaw stub for the VERSIONED child-create route: answers createchild with the
     *  hub's 302 to the new child's configure page. */
    private void stubCreateChild(int newId) {
        def paths = rawPaths
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, int t = 30, boolean r = false ->
            paths << path
            if (path.startsWith('/installedapp/createchild/')) {
                return [status: 302, location: "/installedapp/configure/${newId}", data: null]
            }
            [status: 302, location: '/installedapp/list', data: null]
        }
    }

    /** hubInternalGetRaw stub for firmware WITHOUT the versioned child types: createchild answers with
     *  no Location, so the legacy builder-page route is what actually creates the child. */
    private void stubLegacyCreateOnly(String html) {
        def paths = rawPaths
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, int t = 30, boolean r = false ->
            paths << path
            if (path.startsWith('/installedapp/createchild/')) {
                return [status: 500, location: null, data: 'No such app type']
            }
            [status: 200, location: null, data: html]
        }
    }

    private void stubPostJson(Closure responder = null) {
        def captured = posts
        script.metaClass.hubInternalPostJson = { String path, String jsonBody, int timeout = 420, boolean isRetry = false ->
            captured << [path: path, body: jsonBody]
            responder ? responder.call(path, jsonBody) : null
        }
    }

    /** Wire a graph child at `appId` whose stored state is whatever the last save POSTed. */
    private Map stubGraphChild(int appId, List validationErrors = []) {
        def state = [name: null, rulePaused: false, ruleJson: null]
        stubPostJson { path, body ->
            def b = new JsonSlurper().parseText(body)
            state.name = b.name
            state.ruleJson = b.ruleJson
            [name: b.name, ruleJson: b.ruleJson, validationErrors: validationErrors]
        }
        hubGet.register("/app/ruleBuilder20Json/${appId}") { params ->
            json([name: state.name, rulePaused: state.rulePaused, ruleJson: state.ruleJson,
                  validationErrors: validationErrors])
        }
        return state
    }

    // ==================== compose ====================

    def "compose lays out the builder's node order and edge set (no common actions)"() {
        when:
        def graph = script._vrb2Compose([
            triggers: [[type: 'motion', config: [motionSensors: [101], motionSensorEvent: 'Motion starts']],
                       [type: 'systemMode', config: [modes: [2]]]],
            conditions: [[type: 'daysOfWeek', config: [daysOfWeek: [1, 2, 3, 4, 5]]]],
            decisionType: 'all',
            thenActions: [[type: 'turnOn', config: [switches: [201]]]],
            elseActions: [[type: 'turnOff', config: [switches: [201]]]]
        ])

        then: 'triggers, triggerMerge, decision, THEN chain, ELSE chain -- and no branchMerge'
        graph.version == 1
        graph.nodes*.id == ['trigger-1', 'trigger-2', 'trigger-merge', 'decision', 'then-1', 'else-1']
        graph.nodes*.kind == ['trigger', 'trigger', 'merge', 'decision', 'action', 'action']
        !graph.nodes.any { it.type == 'branchMerge' }

        and: 'conditions are nested in the decision and carry NO kind'
        def decision = graph.nodes.find { it.id == 'decision' }
        decision.config.conditions == [[id: 'condition-1', type: 'daysOfWeek', config: [daysOfWeek: [1, 2, 3, 4, 5]]]]
        !decision.config.conditions[0].containsKey('kind')

        and: 'every trigger enters the merge, the merge enters the decision, branches terminate'
        graph.edges == [[from: 'trigger-1', to: 'trigger-merge', port: 'next'],
                        [from: 'trigger-2', to: 'trigger-merge', port: 'next'],
                        [from: 'trigger-merge', to: 'decision', port: 'next'],
                        [from: 'decision', to: 'then-1', port: 'true'],
                        [from: 'decision', to: 'else-1', port: 'false']]
    }

    def "compose with a common tail appends branchMerge, and an EMPTY else branch edges the decision straight to it"() {
        when:
        def graph = script._vrb2Compose([
            triggers: [[type: 'switch', config: [switches: [7], switchEvent: 'Turns on']]],
            conditions: [[type: 'switchCondition', config: [switches: [8], switchState: 'Turned on']]],
            decisionType: 'any',
            thenActions: [[type: 'turnOn', config: [switches: [9]]], [type: 'wait', config: [minutes: 1, seconds: 0]]],
            elseActions: [],
            commonActions: [[type: 'sendNotification', config: [notificationDevices: [301], notificationMessage: 'done']]]
        ])

        then: 'branchMerge and the common chain come AFTER the two branches, as the builder emits them'
        graph.nodes*.id == ['trigger-1', 'trigger-merge', 'decision', 'then-1', 'then-2', 'branch-merge', 'common-1']
        graph.nodes.find { it.id == 'branch-merge' } == [id: 'branch-merge', kind: 'merge', type: 'branchMerge', config: [:]]
        graph.nodes.find { it.id == 'decision' }.type == 'any'

        and: 'the THEN chain is linear and terminates on the merge'
        graph.edges.contains([from: 'decision', to: 'then-1', port: 'true'])
        graph.edges.contains([from: 'then-1', to: 'then-2', port: 'next'])
        graph.edges.contains([from: 'then-2', to: 'branch-merge', port: 'next'])

        and: 'the empty ELSE branch is not dangling -- the false port goes to the merge directly'
        graph.edges.contains([from: 'decision', to: 'branch-merge', port: 'false'])
        graph.edges.contains([from: 'branch-merge', to: 'common-1', port: 'next'])
        !graph.edges.any { it.from == 'common-1' }
    }

    def "compose honours explicit ids and structureIds, and never generates one that collides"() {
        when: 'the second trigger is explicitly named trigger-1, which is also the first one default'
        def graph = script._vrb2Compose([
            triggers: [[type: 'switch', config: [switches: [7], switchEvent: 'Turns on']],
                       [id: 'trigger-1', type: 'motion', config: [motionSensors: [8], motionSensorEvent: 'Motion starts']]],
            thenActions: [[type: 'turnOn', config: [switches: [9]]]],
            structureIds: [triggerMerge: 'tm', decision: 'd']
        ])

        then: 'the generated id steps aside instead of clobbering the explicit one'
        graph.nodes*.id == ['trigger-1-1', 'trigger-1', 'tm', 'd', 'then-1']
        graph.edges.contains([from: 'trigger-1-1', to: 'tm', port: 'next'])
        graph.edges.contains([from: 'tm', to: 'd', port: 'next'])
        graph.edges.contains([from: 'd', to: 'then-1', port: 'true'])
    }

    @Unroll
    def "compose throws IllegalArgumentException: #label"() {
        when:
        script._vrb2Compose(editor)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains(fragment)

        where:
        label                       | editor                                                                                          | fragment
        'unknown decision type'     | [decisionType: 'maybe', triggers: [[type: 'switch', config: [:]]]]                              | "Unsupported decision type 'maybe'"
        'OR with no conditions'     | [decisionType: 'any', triggers: [[type: 'switch', config: [:]]]]                                | 'OR decision must contain at least one condition'
        'item with no type'         | [triggers: [[config: [switches: [1]]]]]                                                         | 'triggers[0] has no type'
        'action with no type'       | [triggers: [[type: 'switch', config: [:]]], thenActions: [[config: [:]]]]                       | 'thenActions[0] has no type'
        'non-object item'           | [triggers: ['switch']]                                                                          | 'triggers[0] must be a JSON object'
        'non-array list'            | [triggers: [type: 'switch']]                                                                    | 'triggers must be an array'
    }

    // ==================== decompose ====================

    def "decompose round-trips compose, and recomposing the result reproduces the identical graph"() {
        given:
        def graph = script._vrb2Compose(editorDefinition())

        when:
        def editor = script._vrb2Decompose(graph)

        then:
        editor.decisionType == 'any'
        editor.triggers*.type == ['motion', 'systemMode']
        editor.conditions*.id == ['condition-1', 'condition-2']
        editor.thenActions*.id == ['then-1']
        editor.elseActions*.id == ['else-1']
        editor.commonActions*.id == ['common-1']
        editor.structureIds == [triggerMerge: 'trigger-merge', decision: 'decision', branchMerge: 'branch-merge']

        and: 'the decomposition is a valid compose input that yields the same document'
        script._vrb2Compose(editor) == graph
    }

    def "decompose omits branchMerge from structureIds when the graph has no common tail"() {
        when:
        def editor = script._vrb2Decompose(validGraph())

        then:
        editor.structureIds == [triggerMerge: 'tm', decision: 'd1']
        editor.thenActions*.id == ['a1']
        editor.elseActions == []
        editor.commonActions == []
        editor.conditions == []
        editor.decisionType == 'all'
    }

    @Unroll
    def "decompose throws the builder's own message: #label"() {
        when:
        script._vrb2Decompose(graph)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message == message

        where:
        label                  | graph                                                                              | message
        'wrong version'        | [version: 2, nodes: [], edges: []]                                                 | 'The rule is not a Visual Rule Builder 2.0 schema version 1 document.'
        'nodes not an array'   | [version: 1, nodes: 'x', edges: []]                                                | 'The rule is not a Visual Rule Builder 2.0 schema version 1 document.'
        'no merge or decision' | [version: 1, nodes: [[id: 'a', kind: 'action', type: 'turnOn', config: [:]]], edges: []] | 'The rule must contain a trigger merge and an AND/OR decision.'
    }

    def "decompose refuses a branch chain that runs through a non-action node"() {
        given: 'the true port points at the trigger merge instead of an action'
        def graph = validGraph()
        graph.edges = [[from: 't1', to: 'tm', port: 'next'],
                       [from: 'tm', to: 'd1', port: 'next'],
                       [from: 'd1', to: 't1', port: 'true']]

        when:
        script._vrb2Decompose(graph)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message == "Expected action node 't1'."
    }

    // ==================== classic -> graph translation ====================

    def "classic translation splits conditions from triggers and strips the dialog bookkeeping keys"() {
        when:
        def graph = script._vrbClassicToGraph(classicDefinition())

        then: 'the daysOfWeek whenNode became a nested condition, not a trigger'
        graph.nodes.findAll { it.kind == 'trigger' }*.type == ['motion']
        def decision = graph.nodes.find { it.kind == 'decision' }
        decision.type == 'all'
        decision.config.conditions*.type == ['daysOfWeek']
        decision.config.conditions[0].config == [daysOfWeek: [1, 2]]

        and: 'deviceIds / index / type / result / description never reach config'
        graph.nodes.find { it.kind == 'trigger' }.config == [motionSensors: [42], motionSensorEvent: 'Motion starts']
        graph.nodes.findAll { it.kind == 'action' }*.config == [[switches: [17]]]

        and: 'the result is a document the pre-flight accepts'
        script._vrb2Validate(graph) == []
    }

    // ==================== validation ====================

    def "a conforming graph produces no pre-flight findings"() {
        expect:
        script._vrb2Validate(validGraph()) == []
        script._vrb2Validate(script._vrb2Compose(editorDefinition())) == []
    }

    @Unroll
    def "_vrb2Validate rejects #label"() {
        given: 'a conforming graph, broken one way'
        def graph = validGraph()
        change.call(graph)

        expect:
        script._vrb2Validate(graph).any { it.contains(fragment) }

        where:
        label                            | change                                      | fragment
        'a version other than 1'         | { it.version = 2 }                          | "Rule 'version' must be 1."
        'a missing triggerMerge'         | { it.nodes.remove(1) }                      | 'exactly one triggerMerge node'
        'a missing decision'             | { it.nodes.remove(2) }                      | 'exactly one decision node'
        'zero triggers'                  | { it.nodes.remove(0) }                      | 'at least one trigger node'
        'an unknown action type'         | { it.nodes[3].type = 'bogusAction' }        | "Action node 'a1' has unsupported type 'bogusAction'."
        'an unknown trigger type'        | { it.nodes[0].type = 'bogusTrigger' }       | "Trigger node 't1' has unsupported type 'bogusTrigger'."
        'a 1.0-shaped node'              | { it.nodes[0] = [id: 't1', type: 'switch', deviceIds: [59]] } | "Node 't1' has unsupported kind 'null'."
        'a missing config'               | { it.nodes[0].remove('config') }            | "Node 't1' config must be an object."
        'a duplicate node id'            | { it.nodes[3].id = 't1' }                   | "Duplicate node id 't1'."
        'a blank node id'                | { it.nodes[3].id = '  ' }                   | 'nonblank string id'
        'a wrong port'                   | { it.edges[2].port = 'next' }               | "Edge from 'd1' has invalid port 'next'"
        'an unknown edge endpoint'       | { it.edges[2].to = 'ghost' }                | "references unknown node 'ghost'"
        'a duplicate edge'               | { it.edges << [from: 'd1', to: 'a1', port: 'true'] } | "Duplicate edge 'd1' -> 'a1' on port 'true'."
        'a trigger bypassing the merge'  | { it.edges[0].to = 'd1' }                   | 'must connect to the triggerMerge node'
        'a merge bypassing the decision' | { it.edges[1].to = 'a1' }                   | 'must connect to the decision node'
        'a non-action in a chain'        | { it.edges[2].to = 'tm' }                   | "Expected action node 'tm'."
    }

    def "_vrb2Validate rejects fan-out: two edges leaving the decision on the same port"() {
        given:
        def graph = validGraph()
        graph.nodes << [id: 'a2', kind: 'action', type: 'turnOn', config: [:]]
        graph.edges << [from: 'd1', to: 'a2', port: 'true']

        expect:
        script._vrb2Validate(graph).any { it.contains("more than one outgoing edge on port 'true'") }
    }

    def "_vrb2Validate rejects an action cycle"() {
        given:
        def graph = validGraph()
        graph.nodes << [id: 'a2', kind: 'action', type: 'turnOn', config: [:]]
        graph.edges << [from: 'a1', to: 'a2', port: 'next']
        graph.edges << [from: 'a2', to: 'a1', port: 'next']

        expect:
        script._vrb2Validate(graph).any { it.contains('action cycle') }
    }

    def "_vrb2Validate checks the nested conditions the hub keeps inside the decision"() {
        given:
        def graph = validGraph()
        graph.nodes[2].type = 'any'
        graph.nodes[2].config.conditions = [[id: 'c1', type: 'bogusCondition', config: [:]],
                                            [id: 'c1', type: 'switchCondition', config: [switches: [1], switchState: 'Turned on']]]

        when:
        def errors = script._vrb2Validate(graph)

        then:
        errors.any { it == "Condition 'c1' has unsupported type 'bogusCondition'." }
        errors.any { it == "Duplicate condition id 'c1'." }
    }

    def "_vrb2Validate requires at least one condition on an OR decision"() {
        given:
        def graph = validGraph()
        graph.nodes[2].type = 'any'

        expect:
        script._vrb2Validate(graph).any { it.contains("'any' decision must contain at least one condition") }
    }

    // ==================== hub_set_visual_rule: editor form ====================

    def "editor-form create composes the graph and POSTs it double-encoded, reporting activated"() {
        given:
        enableWrite()
        registerAppsList([])
        stubCreateChild(810)
        stubGraphChild(810)

        when:
        def result = script.toolSetVisualRule([name: 'Hall light', definition: editorDefinition(), confirm: true])

        then: 'a Visual Rule Builder 2.0 child, then one POST to the graph endpoint with ruleJson a JSON STRING'
        rawPaths == [CREATE_2_0]
        posts.size() == 1
        posts[0].path == '/app/ruleBuilder20Json/810'
        def body = new JsonSlurper().parseText(posts[0].body as String)
        body.name == 'Hall light'
        body.ruleJson instanceof String

        and: 'the composed document carries the OR decision, both branches and the common tail'
        def graph = new JsonSlurper().parseText(body.ruleJson as String)
        graph.version == 1
        graph.nodes*.id == ['trigger-1', 'trigger-2', 'trigger-merge', 'decision', 'then-1', 'else-1', 'branch-merge', 'common-1']
        graph.nodes.find { it.id == 'decision' }.type == 'any'
        graph.nodes.find { it.id == 'decision' }.config.conditions*.type == ['daysOfWeek', 'timeIsBetween']

        and:
        result.success == true
        result.created == true
        result.format == 'graph'
        result.verified == true
        result.activated == true
        result.appId == 810
        result.version == '2.0'
        !result.containsKey('translatedFrom')
    }

    def "an editor definition whose composed graph fails pre-flight is refused BEFORE the child app is created"() {
        given: 'no triggers -> the composed graph has no trigger node'
        enableWrite()
        registerAppsList([])
        stubCreateChild(811)
        stubPostJson()

        when:
        def result = script.toolSetVisualRule([
            name: 'Trigger-less',
            definition: [triggers: [], thenActions: [[type: 'turnOn', config: [switches: [9]]]]],
            confirm: true])

        then:
        result.success == false
        result.error == 'Definition failed pre-flight validation; nothing was created/saved.'
        result.validationErrors.any { it.contains('at least one trigger node') }
        result.note.contains("hub_get_tool_guide(section='visual_rule_reference')")

        and: 'no shell was created and nothing was written'
        rawPaths.isEmpty()
        posts.isEmpty()
    }

    def "a compose error (OR decision with no conditions) is reported as a pre-flight refusal, not a raw throw"() {
        given:
        enableWrite()
        registerAppsList([])
        stubCreateChild(812)
        stubPostJson()

        when:
        def result = script.toolSetVisualRule([
            name: 'Bad OR',
            definition: [triggers: [[type: 'switch', config: [switches: [1], switchEvent: 'Turns on']]],
                         decisionType: 'any', conditions: [],
                         thenActions: [[type: 'turnOn', config: [switches: [9]]]]],
            confirm: true])

        then:
        result.success == false
        result.error.contains('pre-flight validation')
        result.validationErrors == ['An OR decision must contain at least one condition.']
        rawPaths.isEmpty()
    }

    def "a classic definition creates a Visual Rule Builder 1.0 child and is NOT translated"() {
        given: 'a hub that offers both builders'
        enableWrite()
        registerAppsList([])
        stubCreateChild(815)
        def savedState = [:]
        stubPostJson { path, body -> savedState.putAll(new JsonSlurper().parseText(body) as Map); null }
        hubGet.register('/app/ruleBuilder20Json/815') { params -> GRAPH_NOT_FOUND }
        hubGet.register('/app/ruleBuilderJson/815') { params -> json(savedState) }

        when:
        def result = script.toolSetVisualRule([name: 'Legacy import', definition: classicDefinition(), confirm: true])

        then: 'the 1.0 route was taken, so nothing was converted'
        rawPaths == [CREATE_1_0]
        result.success == true
        result.format == 'classic'
        result.version == '1.0'
        result.verified == true
        !result.containsKey('translatedFrom')

        and: 'the classic node lists went on the wire verbatim'
        posts[0].path == '/app/ruleBuilderJson/815'
        def body = new JsonSlurper().parseText(posts[0].body as String)
        body.whenNodes*.triggerType == ['motion', 'daysOfWeek']
        body.thenNodes[0].actionType == 'turnOn'
        !body.containsKey('ruleJson')
    }

    def "a classic definition on a hub that can only create 2.0 children IS translated, and says so"() {
        given: 'no versioned child type; the legacy route creates a graph child'
        enableWrite()
        registerAppsList([])
        stubLegacyCreateOnly('<html>window.HubitatRuleBuilder20AppId = 817</html>')
        stubGraphChild(817)

        when:
        def result = script.toolSetVisualRule([name: 'Legacy import', definition: classicDefinition(), confirm: true])

        then:
        rawPaths == [CREATE_1_0, '/app/createVisualRuleBuilderRule']
        result.success == true
        result.format == 'graph'
        result.version == '2.0'
        result.translatedFrom == 'classic'
        result.verified == true

        and: 'what landed on the wire is the translated 2.0 graph, not the classic node lists'
        def body = new JsonSlurper().parseText(posts[0].body as String)
        body.containsKey('ruleJson')
        !body.containsKey('whenNodes')
        def graph = new JsonSlurper().parseText(body.ruleJson as String)
        graph.nodes.findAll { it.kind == 'trigger' }*.type == ['motion']
        graph.nodes.find { it.kind == 'decision' }.config.conditions*.type == ['daysOfWeek']
    }

    def "an editor definition on a hub that can only create 1.0 children is refused and the shell cleaned up"() {
        given: 'no versioned child type; the legacy route creates a classic child'
        enableWrite()
        registerAppsList([])
        stubLegacyCreateOnly('<html>window.HubitatRuleBuilderAppId = 816</html>')
        hubGet.register('/installedapp/json/816') { params -> '' }
        stubPostJson()

        when:
        def result = script.toolSetVisualRule([name: 'Too modern', definition: editorDefinition(), confirm: true])

        then:
        result.success == false
        result.hubNativeFormat == 'classic'
        result.error.contains('Visual Rule Builder 1.0')
        result.error.contains('editor-format')
        result.note.contains('whenNodes')
        posts.isEmpty()
        rawPaths == [CREATE_2_0, '/app/createVisualRuleBuilderRule', '/installedapp/forcedelete/816/quiet']
    }

    def "the editor map a graph READ returns is accepted verbatim as a definition"() {
        given: 'exactly what hub_get_visual_rule hands back -- structureIds, plus per-item id AND kind'
        hubGet.register('/app/ruleBuilder20Json/880') { params ->
            json([name: 'Hall light', rulePaused: false, validationErrors: [],
                  ruleJson: json(script._vrb2Compose(editorDefinition()))])
        }
        def read = script.toolGetVisualRule([appId: 880])
        def editor = read.editor

        expect: 'the round-trip shape still classifies as the editor form, not graph or classic'
        editor.triggers.every { it.containsKey('kind') && it.containsKey('id') }
        editor.structureIds.triggerMerge == 'trigger-merge'
        script._vrbDetectDefinitionFormat(editor) == 'editor'

        and: 'recomposing it reproduces the stored graph exactly and passes pre-flight'
        script._vrb2Compose(editor) == read.definition
        script._vrb2Validate(script._vrb2Compose(editor)) == []
    }

    def "an editor-form edit replaces the graph wholesale and preserves the structure ids it was given"() {
        given:
        enableWrite()
        def existing = script._vrb2Compose(editorDefinition())
        def state = [name: 'Hall light', rulePaused: false, ruleJson: json(existing)]
        hubGet.register('/app/ruleBuilder20Json/820') { params ->
            json([name: state.name, rulePaused: state.rulePaused, ruleJson: state.ruleJson, validationErrors: []])
        }
        stubPostJson { path, body ->
            def b = new JsonSlurper().parseText(body)
            state.name = b.name
            state.ruleJson = b.ruleJson
            [name: b.name, ruleJson: b.ruleJson, validationErrors: []]
        }

        when: 'read the editor form, drop the else branch, send it back'
        def editor = script.toolGetVisualRule([appId: 820]).editor
        editor.elseActions = []
        def result = script.toolSetVisualRule([appId: 820, definition: editor, confirm: true])

        then:
        result.success == true
        result.verified == true
        result.activated == true
        def graph = new JsonSlurper().parseText(new JsonSlurper().parseText(posts[0].body as String).ruleJson as String)
        graph.nodes.find { it.kind == 'decision' }.id == 'decision'
        graph.nodes*.id == ['trigger-1', 'trigger-2', 'trigger-merge', 'decision', 'then-1', 'branch-merge', 'common-1']
        graph.edges.contains([from: 'decision', to: 'branch-merge', port: 'false'])
    }

    def "a hub-reported validation failure comes back as activated=false with the inactive-draft note"() {
        given:
        enableWrite()
        registerAppsList([])
        stubCreateChild(830)
        def state = [name: null, rulePaused: false, ruleJson: null]
        stubPostJson { path, body ->
            def b = new JsonSlurper().parseText(body)
            state.name = b.name
            state.ruleJson = b.ruleJson
            [name: b.name, ruleJson: b.ruleJson, revision: 'r-2', storedSuccessfully: true, activatedSuccessfully: false,
             validationErrors: ["Node 'trigger-1' config.motionSensors references missing device '101'"],
             validationIssues: [[nodeId: 'trigger-1', field: 'motionSensors',
                                 message: "Node 'trigger-1' config.motionSensors references missing device '101'"]],
             referencedDeviceIds: [101, 201, 301]]
        }
        hubGet.register('/app/ruleBuilder20Json/830') { params ->
            json([name: state.name, rulePaused: state.rulePaused, ruleJson: state.ruleJson,
                  validationErrors: ["Node 'trigger-1' config.motionSensors references missing device '101'"]])
        }

        when:
        def result = script.toolSetVisualRule([name: 'Missing device', definition: editorDefinition(), confirm: true])

        then: 'the write landed and verified, but the rule is NOT running'
        result.success == true
        result.verified == true
        result.activated == false
        result.note.contains('INACTIVE DRAFT')
        result.validationErrors.size() == 1
        result.validationIssues[0].nodeId == 'trigger-1'
        result.validationIssues[0].field == 'motionSensors'
        result.referencedDeviceIds == [101, 201, 301]
        result.revision == 'r-2'
    }

    // ==================== hub_get_visual_rule: editor + version ====================

    def "a graph read returns the editor decomposition alongside the raw definition"() {
        given:
        def graph = script._vrb2Compose(editorDefinition())
        hubGet.register('/app/ruleBuilder20Json/840') { params ->
            json([name: 'Hall light', rulePaused: false, ruleJson: json(graph), validationErrors: [],
                  revision: 'r-7', referencedDeviceIds: [101, 201, 301],
                  ruleApps: [[id: 42, label: 'Other rule'], [id: 43, label: "Broken rule <span style='color:red'>*BROKEN*</span>"]],
                  runtimeGraph: [triggerCount: 2, actionCount: 3]])
        }

        when:
        def result = script.toolGetVisualRule([appId: 840])

        then:
        result.success == true
        result.format == 'graph'
        result.activated == true
        result.definition.nodes.size() == 8
        result.editor.decisionType == 'any'
        result.editor.triggers*.type == ['motion', 'systemMode']
        result.editor.conditions*.type == ['daysOfWeek', 'timeIsBetween']
        result.editor.commonActions*.type == ['sendNotification']
        result.editor.structureIds.branchMerge == 'branch-merge'
        !result.containsKey('editorError')

        and: 'the 2.0 read extras pass through when the firmware sends them'
        result.revision == 'r-7'
        result.referencedDeviceIds == [101, 201, 301]
        result.ruleApps*.id == [42, 43]
        result.ruleApps[0].label == 'Other rule'
        result.ruleApps[1].label == 'Broken rule *BROKEN*'
        result.runtimeGraph.triggerCount == 2
    }

    def "a stored graph the builder cannot open reports editorError instead of failing the read"() {
        given: 'a graph with no trigger merge -- stored, but not decomposable'
        hubGet.register('/app/ruleBuilder20Json/841') { params ->
            json([name: 'Broken', rulePaused: false, validationErrors: ['Rule must contain exactly one triggerMerge node'],
                  ruleJson: '{"version":1,"nodes":[{"id":"a1","kind":"action","type":"turnOn","config":{}}],"edges":[]}'])
        }

        when:
        def result = script.toolGetVisualRule([appId: 841])

        then: 'the read still succeeds and hands back the raw graph'
        result.success == true
        result.definition.nodes*.id == ['a1']
        result.editorError == 'The rule must contain a trigger merge and an AND/OR decision.'
        !result.containsKey('editor')

        and: 'and it is honest about the rule not running'
        result.activated == false
        result.validationErrors == ['Rule must contain exactly one triggerMerge node']
    }

    def "list mode reports the builder version parsed from the hub's child app type"() {
        given:
        registerAppsList([
            [key: 850, data: [id: 850, name: 'Graph rule', type: 'Visual Rule Builder 2.0', disabled: false], children: []],
            [key: 851, data: [id: 851, name: 'Old rule', type: 'Visual Rule Builder 1.0', disabled: false], children: []],
            [key: 852, data: [id: 852, name: 'Unversioned', type: 'Visual Rule Builder', disabled: false], children: []]
        ])

        when:
        def result = script.toolGetVisualRule([:])

        then:
        result.rules.find { it.appId == 850 }.version == '2.0'
        result.rules.find { it.appId == 851 }.version == '1.0'
        !result.rules.find { it.appId == 852 }.containsKey('version')
    }

    // ==================== dispatch-envelope (integration) ====================

    @Unroll
    def "hub_get_visual_rule via dispatch carries the editor form through the wire (useGateways=#useGateways)"() {
        given:
        settingsMap.useGateways = useGateways
        hubGet.register('/app/ruleBuilder20Json/860') { params ->
            json([name: 'Hall light', rulePaused: false, ruleJson: json(script._vrb2Compose(editorDefinition())),
                  validationErrors: []])
        }

        when:
        def response = mcpDriver.callTool('hub_get_visual_rule', [appId: 860])

        then:
        response.error == null
        def inner = mcpDriver.parseInner(response)
        inner.success == true
        inner.format == 'graph'
        inner.activated == true
        inner.editor.decisionType == 'any'
        inner.editor.thenActions*.type == ['setBrightness']
        inner.editor.commonActions*.type == ['sendNotification']

        where:
        useGateways << [true, false]
    }

    def "hub_set_visual_rule via dispatch refuses a malformed editor definition without touching the hub"() {
        given:
        enableWrite()
        registerAppsList([])
        stubCreateChild(870)
        stubPostJson()

        when:
        def response = mcpDriver.callTool('hub_set_visual_rule',
                [name: 'Nope', definition: [triggers: [], thenActions: []], confirm: true])

        then:
        response.error == null
        def inner = mcpDriver.parseInner(response)
        inner.success == false
        inner.error.contains('pre-flight validation')
        inner.validationErrors.any { it.contains('at least one trigger node') }
        rawPaths.isEmpty()
        posts.isEmpty()
    }
}
