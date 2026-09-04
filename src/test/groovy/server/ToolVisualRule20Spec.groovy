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
        'a fractional version'           | { it.version = 1.5 }                        | "Rule 'version' must be 1."
        'a string version'               | { it.version = '1' }                        | "Rule 'version' must be 1."
        'a missing triggerMerge'         | { it.nodes.remove(1) }                      | 'exactly one triggerMerge node'
        'a missing decision'             | { it.nodes.remove(2) }                      | 'exactly one decision node'
        'zero triggers'                  | { it.nodes.remove(0) }                      | 'at least one trigger node'
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
        'both branches joining on one action' | { it.edges << [from: 'd1', to: 'a1', port: 'false'] } | "Node 'a1' is reached by more than one path"
        'a numeric edge endpoint'        | { it.nodes[3].id = '7'; it.edges[2].to = 7 } | "Edge at index 2 must have string 'from', 'to' and 'port' values."
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

    def "_vrb2Validate rejects a declared node the flow never reaches"() {
        given: 'an action added but never wired -- the hub would store this as an inactive draft'
        def graph = validGraph()
        graph.nodes << [id: 'orphan', kind: 'action', type: 'turnOn', config: [switches: [59]]]

        expect:
        script._vrb2Validate(graph).any { it == "Node 'orphan' is not connected to the rule's flow." }

        and: 'the same graph with the edge in place is clean'
        graph.edges << [from: 'a1', to: 'orphan', port: 'next']
        script._vrb2Validate(graph).isEmpty()
    }

    def "_vrb2Validate requires every decision branch to reach the branchMerge when one exists"() {
        given: 'a common tail exists, but the THEN chain dead-ends and the ELSE port is unwired'
        def graph = validGraph()
        graph.nodes << [id: 'bm', kind: 'merge', type: 'branchMerge', config: [:]]
        graph.nodes << [id: 'c1', kind: 'action', type: 'turnOn', config: [switches: [59]]]
        graph.edges << [from: 'bm', to: 'c1', port: 'next']

        when:
        def errors = script._vrb2Validate(graph)

        then:
        errors.contains("The action chain leaving port 'true' of node 'd1' must end at the branchMerge node 'bm'.")
        errors.contains("Port 'false' of node 'd1' must connect to the branchMerge node 'bm' (directly, or through a chain of actions).")

        when: 'both branches are wired the way the builder composes them'
        graph.edges << [from: 'a1', to: 'bm', port: 'next']
        graph.edges << [from: 'd1', to: 'bm', port: 'false']

        then:
        script._vrb2Validate(graph).isEmpty()
    }

    def "_vrb2Validate checks the nested conditions the hub keeps inside the decision"() {
        given:
        def graph = validGraph()
        graph.nodes[2].type = 'any'
        graph.nodes[2].config.conditions = [[id: 'c1', type: 'bogusCondition', config: [:]],
                                            [id: 'c1', type: 'switchCondition', config: [switches: [1], switchState: 'Turned on']]]

        when:
        def errors = script._vrb2Validate(graph)

        then: 'the structural problem is an error; the unknown type name is only advisory'
        !errors.any { it.contains('bogusCondition') }
        errors.any { it == "Duplicate condition id 'c1'." }
        script._vrb2CatalogWarnings(graph).any { it.contains("Condition 'c1' has type 'bogusCondition'") }
    }

    def "unknown type names and an action-less rule are advisory warnings, never pre-flight errors"() {
        given: 'firmware may know a type this build does not -- the hub is the oracle'
        def graph = validGraph()
        graph.nodes[0].type = 'newFirmwareTrigger'
        graph.nodes[3].type = 'newFirmwareAction'

        expect:
        script._vrb2Validate(graph).isEmpty()
        script._vrb2CatalogWarnings(graph).any { it.startsWith("Trigger node 't1' has type 'newFirmwareTrigger'") }
        script._vrb2CatalogWarnings(graph).any { it.startsWith("Action node 'a1' has type 'newFirmwareAction'") }

        and: 'a valid rule with no action anywhere is flagged too (the hub activates it; it does nothing)'
        def idle = validGraph()
        idle.nodes.remove(3)
        idle.edges.remove(2)
        script._vrb2Validate(idle).isEmpty()
        script._vrb2CatalogWarnings(idle).any { it.contains('no action on any branch') }
    }

    def "one bad hop in a chain is reported once, not as every downstream node being disconnected"() {
        given: 'THEN chain routed into the trigger merge; a2 hangs off a1 and is perfectly wired'
        def graph = validGraph()
        graph.nodes << [id: 'a2', kind: 'action', type: 'turnOn', config: [switches: [59]]]
        graph.edges << [from: 'a1', to: 'a2', port: 'next']
        graph.edges[2].to = 'tm'

        when:
        def errors = script._vrb2Validate(graph)

        then:
        errors.any { it == "Expected action node 'tm'." }
        !errors.any { it.contains('is not connected') }
    }

    def "compose refuses unknown top-level editor keys instead of silently reading them as empty"() {
        when: 'a typo that would otherwise turn a gated rule into an unconditional one'
        script._vrb2Compose([triggers: [[type: 'switch', config: [switches: [1], switchEvent: 'Turns on']]],
                             conditons: [[type: 'switchCondition', config: [switches: [2], switchState: 'Turned on']]],
                             decisionTyp: 'any',
                             thenActions: [[type: 'turnOn', config: [switches: [9]]]]])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('Unknown editor key(s): conditons, decisionTyp')
    }

    def "compose refuses a node that mixes the 2.0 and 1.0 item shapes instead of composing an empty config"() {
        when:
        script._vrb2Compose([triggers: [[triggerType: 'switch', config: [switches: [7], switchEvent: 'Turns on']]],
                             thenActions: [[type: 'turnOn', config: [switches: [9]]]]])

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("triggers[0] mixes the 2.0 shape")
    }

    def "a hub-authored graph with a branchMerge and an EMPTY common tail round-trips unchanged"() {
        given: 'not something compose emits on its own -- the hub accepts it (live-verified), so read -> edit -> write must keep it'
        def graph = validGraph()
        graph.nodes << [id: 'bm', kind: 'merge', type: 'branchMerge', config: [:]]
        graph.edges << [from: 'a1', to: 'bm', port: 'next']
        graph.edges << [from: 'd1', to: 'bm', port: 'false']

        when:
        def editor = script._vrb2Decompose(graph)
        def back = script._vrb2Compose(editor)

        then:
        editor.commonActions == []
        editor.structureIds.branchMerge == 'bm'
        back.nodes.find { it.type == 'branchMerge' }?.id == 'bm'
        back.edges.find { it.from == 'a1' }.to == 'bm'
        back.edges.find { it.from == 'd1' && it.port == 'false' }.to == 'bm'
        script._vrb2Validate(back).isEmpty()
    }

    def "classic translation orders nodes by their index, not by array position"() {
        given:
        def classic = [whenNodes: [[triggerType: 'switch', switches: [1], deviceIds: [1], switchEvent: 'Turns on', index: 0, type: 'when']],
                       thenNodes: [[actionType: 'turnOff', switches: [2], deviceIds: [2], index: 1, type: 'then'],
                                   [actionType: 'turnOn', switches: [3], deviceIds: [3], index: 0, type: 'then']],
                       elseNodes: []]

        when:
        def graph = script._vrbClassicToGraph(classic)

        then:
        graph.nodes.findAll { it.kind == 'action' }*.type == ['turnOn', 'turnOff']
    }

    def "a graph read prefers the hub's parsed graphDocument and refuses a ruleJson that is not an object"() {
        given:
        def graph = script._vrb2Compose(editorDefinition())
        hubGet.register('/app/ruleBuilder20Json/843') { params ->
            json([name: 'Doc first', rulePaused: false, ruleJson: '{"version":1,"nodes":[],"edges":[]}', graphDocument: graph, validationErrors: []])
        }
        hubGet.register('/app/ruleBuilder20Json/844') { params ->
            json([name: 'Array body', rulePaused: false, ruleJson: '[1,2,3]', validationErrors: []])
        }

        expect: 'graphDocument wins over the string'
        script.toolGetVisualRule([appId: 843]).definition.nodes.size() == graph.nodes.size()

        and: 'an array is reported, not handed back as a success with nothing in it'
        def arr = script.toolGetVisualRule([appId: 844])
        arr.success == true
        !arr.containsKey('definition')
        !arr.containsKey('editor')
        arr.definitionParseError.contains('not a JSON object')
    }

    def "a create whose read-back finds no rule reports activated=false, never true beside verified=false"() {
        given: 'the POST is accepted but the read-back answers not-found'
        enableWrite()
        registerAppsList([])
        stubCreateChild(845)
        stubPostJson { path, body -> [name: 'Vanished', ruleJson: '{}', validationErrors: []] }
        hubGet.register('/app/ruleBuilder20Json/845') { params -> GRAPH_NOT_FOUND }
        hubGet.register('/app/ruleBuilderJson/845') { params -> '{}' }

        when:
        def result = script.toolSetVisualRule([name: 'Vanished', definition: editorDefinition(), confirm: true])

        then:
        result.success == false
        result.verified == false
        result.activated == false
    }

    def "a create surfaces catalog warnings and the route that made the child"() {
        given: 'an action type this build does not know -- the hub decides, we only warn'
        enableWrite()
        registerAppsList([])
        stubCreateChild(846)
        def state = [name: null, ruleJson: null]
        stubPostJson { path, body ->
            def b = new JsonSlurper().parseText(body)
            state.name = b.name
            state.ruleJson = b.ruleJson
            [name: b.name, ruleJson: b.ruleJson, validationErrors: []]
        }
        hubGet.register('/app/ruleBuilder20Json/846') { params ->
            json([name: state.name, rulePaused: false, ruleJson: state.ruleJson, validationErrors: []])
        }

        when:
        def result = script.toolSetVisualRule([name: 'Future type', confirm: true, definition: [
            triggers: [[type: 'switch', config: [switches: [1], switchEvent: 'Turns on']]],
            thenActions: [[type: 'brandNewAction', config: [switches: [9]]]]]])

        then:
        result.success == true
        result.createRoute == 'createchild'
        result.preflightWarnings.any { it.contains("has type 'brandNewAction'") }
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

    def "an editor definition whose composed graph fails pre-flight throws -32602 BEFORE the child app is created"() {
        given: 'no triggers -> the composed graph has no trigger node'
        enableWrite()
        registerAppsList([])
        stubCreateChild(811)
        stubPostJson()

        when:
        script.toolSetVisualRule([
            name: 'Trigger-less',
            definition: [triggers: [], thenActions: [[type: 'turnOn', config: [switches: [9]]]]],
            confirm: true])

        then: 'a plain argument error -- nothing existed yet, so no envelope is needed'
        def e = thrown(IllegalArgumentException)
        e.message.contains('pre-flight validation')
        e.message.contains('at least one trigger node')
        e.message.contains("hub_get_tool_guide(section='visual_rule_reference')")

        and: 'no shell was created and nothing was written'
        rawPaths.isEmpty()
        posts.isEmpty()
    }

    def "a classic definition whose node lists are not arrays is refused BEFORE the 1.0 child is created"() {
        given:
        enableWrite()
        registerAppsList([])
        stubCreateChild(813)
        stubPostJson()

        when:
        script.toolSetVisualRule([name: 'Bad classic', confirm: true,
                                  definition: [whenNodes: [triggerType: 'switch'], thenNodes: [], elseNodes: []]])

        then:
        def e = thrown(IllegalArgumentException)
        e.message == 'definition.whenNodes must be an array of node objects.'
        rawPaths.isEmpty()
        posts.isEmpty()
    }

    def "a versioned create that loses its Location adopts the child that appeared instead of creating a second one"() {
        given: 'createchild answers 200 with no Location (an auto-followed absolute redirect) -- the child EXISTS'
        enableWrite()
        def children = []
        hubGet.register('/hub2/appsList') { params ->
            json([apps: [[key: 700, data: [id: 700, appTypeId: 99, name: 'Visual Rules Builder', type: 'Visual Rules Builder', disabled: false],
                          children: children.collect { [key: it, data: [id: it, name: '', type: 'Visual Rule Builder 2.0', disabled: false], children: []] }]]])
        }
        def paths = rawPaths
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, int t = 30, boolean r = false ->
            paths << path
            if (path.startsWith('/installedapp/createchild/')) {
                children << 814
                return [status: 200, location: null, data: '<html>configure page</html>']
            }
            [status: 302, location: '/installedapp/list', data: null]
        }
        def state = [name: null, ruleJson: null]
        stubPostJson { path, body ->
            def b = new JsonSlurper().parseText(body)
            state.name = b.name
            state.ruleJson = b.ruleJson
            [name: b.name, ruleJson: b.ruleJson, validationErrors: []]
        }
        hubGet.register('/app/ruleBuilder20Json/814') { params ->
            json([name: state.name, rulePaused: false, ruleJson: state.ruleJson, validationErrors: []])
        }
        hubGet.register('/installedapp/json/814') { params -> json([id: 814, name: '', type: 'Visual Rule Builder 2.0', disabled: false, user: false]) }

        when:
        def result = script.toolSetVisualRule([name: 'Adopted', definition: editorDefinition(), confirm: true])

        then: 'the child that appeared was adopted; the legacy route was never tried'
        result.success == true
        result.appId == 814
        result.version == '2.0'
        rawPaths == [CREATE_2_0]
        posts[0].path == '/app/ruleBuilder20Json/814'
    }

    def "a versioned create whose reconcile read fails refuses to create again"() {
        given: 'createchild answers without a Location, and the parent re-read then fails -- existence is UNKNOWN'
        enableWrite()
        int appsListCalls = 0
        hubGet.register('/hub2/appsList') { params ->
            appsListCalls++
            if (appsListCalls > 1) throw new RuntimeException('status code: 500')
            json([apps: [[key: 700, data: [id: 700, appTypeId: 99, name: 'Visual Rules Builder', type: 'Visual Rules Builder', disabled: false], children: []]]])
        }
        def paths = rawPaths
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, int t = 30, boolean r = false ->
            paths << path
            [status: 200, location: null, data: '<html>configure page</html>']
        }
        stubPostJson()

        when:
        def result = script.toolSetVisualRule([name: 'Unknown', definition: editorDefinition(), confirm: true])

        then: 'no second create of any kind, and the caller is told how to recover'
        result.success == false
        result.error.contains('could not be re-read')
        result.error.contains('hub_get_visual_rule')
        rawPaths == [CREATE_2_0]
        posts.isEmpty()
    }

    def "a versioned create that loses its Location refuses to adopt a child that is not an empty shell of the requested version"() {
        given: 'the only child that appeared in the window is somebody else\'s -- it already has a name and content'
        enableWrite()
        def children = []
        hubGet.register('/hub2/appsList') { params ->
            json([apps: [[key: 700, data: [id: 700, appTypeId: 99, name: 'Visual Rules Builder', type: 'Visual Rules Builder', disabled: false],
                          children: children.collect { [key: it, data: [id: it, name: 'Their rule', type: 'Visual Rule Builder 2.0', disabled: false], children: []] }]]])
        }
        def paths = rawPaths
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, int t = 30, boolean r = false ->
            paths << path
            if (path.startsWith('/installedapp/createchild/')) {
                children << 815
                return [status: 200, location: null, data: '<html>configure page</html>']
            }
            [status: 302, location: '/installedapp/list', data: null]
        }
        stubPostJson()
        hubGet.register('/installedapp/json/815') { params -> json([id: 815, name: 'Their rule', type: 'Visual Rule Builder 2.0', disabled: false, user: false]) }
        hubGet.register('/app/ruleBuilder20Json/815') { params -> json([name: 'Their rule', rulePaused: false, ruleJson: json(validGraph()), validationErrors: []]) }

        when:
        def result = script.toolSetVisualRule([name: 'Mine', definition: editorDefinition(), confirm: true])

        then: 'nothing was saved into the other rule, and the legacy route was NOT tried either'
        result.success == false
        result.error.contains('not provably this request')
        result.error.contains('815')
        posts.isEmpty()
        rawPaths == [CREATE_2_0]
    }

    def "a compose error (OR decision with no conditions) surfaces as the pre-flight -32602, before any hub call"() {
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
        def e = thrown(IllegalArgumentException)
        e.message.contains('pre-flight validation')
        e.message.contains('An OR decision must contain at least one condition.')
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

    def "a save whose activation threw on the hub reports activated=false with the hub's activationError"() {
        given: 'storage succeeded, validation passed, activation raised -- validationErrors is EMPTY on this path'
        enableWrite()
        registerAppsList([])
        stubCreateChild(831)
        def state = [name: null, ruleJson: null]
        stubPostJson { path, body ->
            def b = new JsonSlurper().parseText(body)
            state.name = b.name
            state.ruleJson = b.ruleJson
            [name: b.name, ruleJson: b.ruleJson, revision: 'r-3', storedSuccessfully: true, activatedSuccessfully: false,
             validationErrors: [], validationIssues: [], referencedDeviceIds: [101], activationError: 'scheduler unavailable', storageError: null]
        }
        hubGet.register('/app/ruleBuilder20Json/831') { params ->
            json([name: state.name, rulePaused: false, ruleJson: state.ruleJson, validationErrors: [], runtimeGraph: null])
        }

        when:
        def result = script.toolSetVisualRule([name: 'Activation threw', definition: editorDefinition(), confirm: true])

        then: 'verified write, not running, and the ONLY diagnostic the hub gave is kept'
        result.success == true
        result.verified == true
        result.activated == false
        result.activationError == 'scheduler unavailable'
        result.note.contains('Stored but NOT activated')
        result.note.contains('scheduler unavailable')
        !result.containsKey('validationErrors')
    }

    def "a graph read with no active runtime reports activated=false even when validationErrors is empty"() {
        given: 'the hub sends runtimeGraph: null -- nothing is subscribed or scheduled'
        def graph = script._vrb2Compose(editorDefinition())
        hubGet.register('/app/ruleBuilder20Json/841') { params ->
            json([name: 'Dormant', rulePaused: false, ruleJson: json(graph), validationErrors: [], runtimeGraph: null])
        }

        when:
        def result = script.toolGetVisualRule([appId: 841])

        then:
        result.success == true
        result.activated == false
        !result.containsKey('runtimeGraph')
        !result.containsKey('runtimeActive')

        and: 'firmware that does not send the key at all leaves the error list as the only evidence'
        hubGet.register('/app/ruleBuilder20Json/842') { params ->
            json([name: 'Old firmware', rulePaused: false, ruleJson: json(graph), validationErrors: []])
        }
        script.toolGetVisualRule([appId: 842]).activated == true
    }

    def "a storage failure keeps the hub's storageError as the cause"() {
        given: 'the documented exceptional-storage response: success:false, storageError, no errorMessage'
        enableWrite()
        registerAppsList([])
        stubCreateChild(832)
        stubPostJson { path, body ->
            [success: false, storedSuccessfully: false, activatedSuccessfully: false, storageError: 'state write failed',
             activationError: null, validationErrors: [], validationIssues: [], referencedDeviceIds: []]
        }
        hubGet.register('/app/ruleBuilder20Json/832') { params -> GRAPH_NOT_FOUND }
        hubGet.register('/app/ruleBuilderJson/832') { params -> '{}' }
        hubGet.register('/installedapp/json/832') { params -> throw new RuntimeException('status code: 404') }

        when:
        def result = script.toolSetVisualRule([name: 'Storage fails', definition: editorDefinition(), confirm: true])

        then:
        result.success == false
        result.error == 'Hub rejected the graph save: state write failed'
        result.storageError == 'state write failed'
        result.activated == false
    }

    def "a save whose response body was lost takes the hub's verdict from the read-back"() {
        given: 'the POST answers nothing usable (relay drop) but the stored graph reads back with validation errors'
        enableWrite()
        registerAppsList([])
        stubCreateChild(833)
        def state = [name: null, ruleJson: null]
        stubPostJson { path, body ->
            def b = new JsonSlurper().parseText(body)
            state.name = b.name
            state.ruleJson = b.ruleJson
            null
        }
        hubGet.register('/app/ruleBuilder20Json/833') { params ->
            json([name: state.name, rulePaused: false, ruleJson: state.ruleJson,
                  validationErrors: ["Node 'trigger-1' config.motionSensors references missing device '101'"], runtimeGraph: null])
        }

        when:
        def result = script.toolSetVisualRule([name: 'Lost body', definition: editorDefinition(), confirm: true])

        then: 'verified write, and the read-back errors are the diagnostics'
        result.success == true
        result.activated == false
        result.validationErrors == ["Node 'trigger-1' config.motionSensors references missing device '101'"]
        result.note.contains('INACTIVE DRAFT')
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

        then: 'JSON-RPC -32602 with every problem in the message'
        response.error.code == -32602
        response.error.message.contains('pre-flight validation')
        response.error.message.contains('at least one trigger node')
        rawPaths.isEmpty()
        posts.isEmpty()
    }
}
