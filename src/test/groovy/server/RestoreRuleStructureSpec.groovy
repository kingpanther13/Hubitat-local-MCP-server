package server

import support.ToolSpecBase

/**
 * A rule restore puts back RM's state-held structure as far as RM's own paths allow. Triggers
 * (capabstrue), actions (actionList) and the condition pool (capabsfalse) live in app state, which
 * the settings replay cannot write: what the live rule has beyond the snapshot is removed, what it
 * lacks is named, and the restore never reports success over either. A device deleted since the
 * backup is left out of the replay, since RM's pages stop rendering on a null device.
 */
class RestoreRuleStructureSpec extends ToolSpecBase {

    private List calls

    def setup() {
        settingsMap.enableWrite = true
        calls = []
        script.metaClass._rmRemoveTrigger = { Integer appId, Integer idx -> calls << "trigger ${idx}".toString(); [success: true] }
        script.metaClass._rmDeleteAction = { Integer appId, Integer idx -> calls << "action ${idx}".toString(); [success: true] }
        script.metaClass._rmDeleteExpressionConditions = { Integer appId, Collection ids -> calls << "conditions ${ids}".toString(); ids as List }
        script.metaClass._rmClickAppButton = { Integer appId, String btn, String attr = null, String page = null, Map cache = null -> calls << btn; [status: 200] }
    }

    private Map snapshotState(Map st) {
        [statusJson: [appSettings: [], appState: st.collect { k, v -> [name: k, value: v] }]]
    }

    private void liveStates(List<Map> states) {
        def queue = new ArrayList(states)
        script.metaClass._rmReadRuleState = { Integer appId -> queue.size() > 1 ? queue.remove(0) : queue[0] }
    }

    def "triggers, actions and conditions the backup did not have are removed"() {
        given:
        def snap = snapshotState(capabstrue: ["1": "A turns on"], actionList: ["1"], capabsfalse: ["1": "Mode is Night"], eval: ["0": [1]])
        liveStates([
            [capabstrue: ["1": "A turns on", "2": "B turns on"], actionList: ["1", "2", "3"], capabsfalse: ["1": "Mode is Night", "4": "X"], eval: ["0": [1]]],
            [capabstrue: ["1": "A turns on"], actionList: ["1"], capabsfalse: ["1": "Mode is Night"], eval: ["0": [1]]]
        ])

        when:
        def out = script._rmReconcileRuleStructure(100, snap)

        then:
        out.structureRestored == true
        out.removedTriggers == ["2"]
        out.removedActions == ["3", "2"]
        out.removedConditionIds == ["4"]
        calls == ["trigger 2", "action 3", "action 2", "conditions [4]", "updateRule"]
    }

    def "a condition the live expression uses is never removed, even when the backup's pool lacks its id"() {
        given: "the expression restore rebuilt the backup's condition 1 in slot 6"
        def snap = snapshotState(capabsfalse: ["1": "Mode is Night"], eval: ["0": [1]])
        liveStates([[capabsfalse: ["6": "Mode is Night"], eval: ["0": [6]]]])

        when:
        def out = script._rmReconcileRuleStructure(100, snap)

        then:
        out.structureRestored == true
        calls.isEmpty()
    }

    def "a trigger or action the backup had and the rule lacks is named, never reported as restored"() {
        given:
        def snap = snapshotState(capabstrue: ["1": "A turns on", "2": "A turns off"], actionList: ["1", "2"])
        liveStates([[capabstrue: ["1": "A turns on"], actionList: ["1"]]])

        when:
        def out = script._rmReconcileRuleStructure(100, snap)

        then:
        out.structureRestored == false
        out.missingTriggers == [[index: "2", text: "A turns off"]]
        out.missingActions == ["2"]
        out.structureError.contains("A turns off")
    }

    def "an extra trigger RM refuses to remove is reported"() {
        given:
        script.metaClass._rmRemoveTrigger = { Integer appId, Integer idx -> throw new IllegalStateException("delete did not take") }
        def snap = snapshotState(capabstrue: ["1": "A turns on"])
        liveStates([[capabstrue: ["1": "A turns on", "2": "B turns on"]]])

        when:
        def out = script._rmReconcileRuleStructure(100, snap)

        then:
        out.structureRestored == false
        out.extraRemaining == [triggers: ["2"]]
        out.structureError.contains("delete did not take")
    }

    def "an unreadable live rule is not reported as matching"() {
        given:
        script.metaClass._rmReadRuleState = { Integer appId -> null }

        expect:
        script._rmReconcileRuleStructure(100, snapshotState(capabstrue: ["1": "A"])).structureRestored == false
    }

    def "a snapshot without app state is not second-guessed"() {
        expect:
        script._rmReconcileRuleStructure(100, [statusJson: [appSettings: []]]) == [:]
    }

    def "expression texts come from the condition pool only, never the same-numbered trigger"() {
        expect:
        script._rmConditionTexts([capabstrue: ["1": "A turns on"], capabsfalse: ["1": "Mode is Night"]]) == ["1": "Mode is Night"]
    }

    def "_rmDeviceGone: #label"() {
        given:
        hubGet.register('/device/fullJson/55') { params -> if (body instanceof Exception) throw body; body }

        expect:
        script._rmDeviceGone('55', [:]) == gone

        where:
        label                        | body                                      || gone
        'empty object = deleted'     | '{}'                                      || true
        '404 = deleted'              | new RuntimeException('HTTP 404')          || true
        'a device = present'         | '{"device":{"id":55}}'                    || false
        'empty answer = kept'        | ''                                        || false
        'other failure = kept'       | new RuntimeException('read timed out')   || false
    }

    def "a rule restore leaves a deleted device out of the replay and says so"() {
        given:
        def written = [:]
        script.metaClass._requireUnprotectedAppMutation = { id, String what -> }
        script.metaClass._rmRejectDisabledAppEdit = { Integer id, String what -> }
        script.metaClass._rmUpdateAppSettings = { Integer id, Map s, Map schema -> written.putAll(s) }
        script.metaClass._rmRestoreRequiredExpression = { Integer id, Map snap -> [:] }
        script.metaClass._rmReconcileRuleStructure = { Integer id, Map snap -> [:] }
        hubGet.register('/installedapp/configure/json/100') { params -> '{"app":{"id":100},"configPage":{"sections":[]},"settings":{}}' }
        hubGet.register('/device/fullJson/8') { params -> '{"device":{"id":8}}' }
        hubGet.register('/device/fullJson/9') { params -> '{}' }
        def snapshot = [ruleId: 100, appType: "rule_machine",
                        configJson: [configPage: [sections: []], settings: [tDev1: ["8": "A", "9": "B"], rDev_1: ["9": "B"], tstate1: "on"]],
                        statusJson: [appSettings: [[name: "tDev1", type: "capability.switch", multiple: true],
                                                   [name: "rDev_1", type: "capability.switch", multiple: true],
                                                   [name: "tstate1", type: "enum"]]]]

        when:
        def out = script._rmRestoreFromBackup([fileName: "mcp-rm-backup-100-x.json"], snapshot)

        then:
        written == [tDev1: ["8"], tstate1: "on"]
        out.partial == true
        out.settingsSkipped.findAll { it.reason.contains("no longer exist") }*.key.sort() == ["rDev_1", "tDev1"]
        out.note.contains("deleted since the backup")
    }

    def "a rule restore whose triggers do not match the backup is NOT reported as success"() {
        given:
        script.metaClass._requireUnprotectedAppMutation = { id, String what -> }
        script.metaClass._rmRejectDisabledAppEdit = { Integer id, String what -> }
        script.metaClass._rmUpdateAppSettings = { Integer id, Map s, Map schema -> }
        script.metaClass._rmRestoreRequiredExpression = { Integer id, Map snap -> [:] }
        script.metaClass._rmReconcileRuleStructure = { Integer id, Map snap ->
            [structureRestored: false, missingTriggers: [[index: "2", text: "A turns off"]], structureError: "the backup's trigger(s) A turns off are not on the rule"]
        }
        hubGet.register('/installedapp/configure/json/100') { params -> '{"app":{"id":100},"configPage":{"sections":[]},"settings":{}}' }
        def snapshot = [ruleId: 100, appType: "rule_machine", configJson: [configPage: [sections: []], settings: [tstate1: "on"]],
                        statusJson: [appSettings: [[name: "tstate1", type: "enum"]]]]

        when:
        def out = script._rmRestoreFromBackup([fileName: "mcp-rm-backup-100-x.json"], snapshot)

        then:
        out.success == false
        out.partial == true
        out.error.contains("triggers/actions do not match the backup")
        out.missingTriggers*.text == ["A turns off"]
    }
}
