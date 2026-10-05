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
        script.metaClass._rmDeleteAction = { Integer appId, Integer idx, boolean skip = false -> calls << "action ${idx}".toString(); [success: true] }
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

    def "an extra IF/END-IF pair is removed as a set, past the per-row balance refusal"() {
        given: "rows 2 (IF) and 3 (END-IF) were added after the backup"
        def flags = []
        script.metaClass._rmDeleteAction = { Integer appId, Integer idx, boolean skip -> flags << [idx, skip]; [success: true] }
        hubGet.register('/installedapp/statusJson/100') { params ->
            groovy.json.JsonOutput.toJson([appSettings: [[name: "actSubType.1", value: "getLogMsg"],
                                                        [name: "actSubType.2", value: "getIfThen"],
                                                        [name: "actSubType.3", value: "getEndIf"]], appState: []])
        }
        script.metaClass._rmOrderedActionIndices = { Integer appId -> [1, 2, 3] }
        def snap = snapshotState(actionList: ["1"])
        liveStates([[actionList: ["1", "2", "3"]], [actionList: ["1"]]])

        when:
        def out = script._rmReconcileRuleStructure(100, snap)

        then:
        out.structureRestored == true
        flags == [[3, true], [2, true]]
    }

    def "a set of extra rows that would unbalance the rule keeps the per-row refusal"() {
        given: "only the END-IF (row 3) is extra; its IF (row 2) is in the backup"
        def flags = []
        script.metaClass._rmDeleteAction = { Integer appId, Integer idx, boolean skip ->
            flags << [idx, skip]
            if (!skip) throw new IllegalArgumentException("removing it would introduce a new structural-balance issue")
            [success: true]
        }
        hubGet.register('/installedapp/statusJson/100') { params ->
            groovy.json.JsonOutput.toJson([appSettings: [[name: "actSubType.2", value: "getIfThen"],
                                                        [name: "actSubType.3", value: "getEndIf"]], appState: []])
        }
        script.metaClass._rmOrderedActionIndices = { Integer appId -> [2, 3] }
        def snap = snapshotState(actionList: ["2"])
        liveStates([[actionList: ["2", "3"]]])

        when:
        def out = script._rmReconcileRuleStructure(100, snap)

        then:
        flags == [[3, false]]
        out.structureRestored == false
        out.extraRemaining == [actions: ["3"]]
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

    // -------- native App Cloner backup + restore --------

    private Map nativeSnapshot(Map extra = [:]) {
        [ruleId: 100, appType: "rule_machine",
         nativeExport: '{"deviceReplacements":{"8":{"deviceLabel":"A"}},"appReplacements":{"100":{"appLabel":"r"}},"appData":{}}',
         configJson: [app: [id: 100], configPage: [sections: []], settings: [tstate1: "on"]],
         statusJson: [appSettings: [[name: "tstate1", type: "enum"]]]] + extra
    }

    private Map nativeStubs(Map opts = [:]) {
        def rec = [imports: [], deletes: [], enables: [], replays: []]
        script.metaClass.toolImportNativeApp = { Map a -> rec.imports << a; opts.importResult ?: [success: true, newAppId: 200, stagedDisabled: [200]] }
        script.metaClass.toolDeleteNativeApp = { Map a -> rec.deletes << a.appId; opts.deleteResult ?: [success: true, backup: [backupKey: "rm-rule_100_pre"]] }
        script.metaClass.toolSetAppDisabled = { Map a -> rec.enables << a.appId; [success: true] }
        script.metaClass._rmRejectDisabledAppEdit = { Integer id, String what -> }
        script.metaClass._rmUpdateAppSettings = { Integer id, Map st, Map schema -> rec.replays << st }
        script.metaClass._rmRestoreRequiredExpression = { Integer id, Map snap -> [:] }
        script.metaClass._rmReconcileRuleStructure = { Integer id, Map snap -> [:] }
        hubGet.register('/installedapp/configure/json/100') { params -> '{"app":{"id":100},"configPage":{"sections":[]},"settings":{}}' }
        hubGet.register('/device/fullJson/8') { params -> opts.deviceGone ? '{}' : '{"device":{"id":8}}' }
        return rec
    }

    def "a backup with an App Cloner export restores as an exact new app and the old rule is deleted"() {
        given:
        def rec = nativeStubs()

        when:
        def out = script._rmRestoreFromBackup([fileName: "mcp-rm-backup-100-x.json"], nativeSnapshot())

        then:
        out.success == true
        out.restoredVia == "nativeImport"
        out.ruleId == 200
        out.originalRuleId == 100
        out.recreated == true
        out.replacedRuleBackup == "rm-rule_100_pre"
        out.note.contains("NEW app 200")
        rec.imports.size() == 1
        rec.imports[0].stageDisabled == true
        rec.imports[0].parentHintAppId == 100
        rec.deletes == [100]
        rec.enables == [200]
        rec.replays.isEmpty()
    }

    def "an app that was disabled at backup time comes back disabled"() {
        given:
        def rec = nativeStubs()

        when:
        script._rmRestoreFromBackup([fileName: "f.json"], nativeSnapshot(configJson: [app: [id: 100, disabled: true], configPage: [sections: []], settings: [:]]))

        then:
        rec.deletes == [100]
        rec.enables.isEmpty()
    }

    def "preserveRuleId restores in place by settings replay"() {
        given:
        def rec = nativeStubs()

        when:
        def out = script._rmRestoreFromBackup([fileName: "f.json"], nativeSnapshot(), true)

        then:
        out.ruleId == 100
        out.restoredVia == null
        rec.imports.isEmpty()
        rec.deletes.isEmpty()
        rec.replays == [[tstate1: "on"]]
    }

    def "an export that names a deleted device falls back to the settings replay"() {
        given:
        def rec = nativeStubs(deviceGone: true)

        when:
        def out = script._rmRestoreFromBackup([fileName: "f.json"], nativeSnapshot())

        then:
        out.ruleId == 100
        rec.imports.isEmpty()
        rec.replays.size() == 1
    }

    def "a restored copy that will not re-enable says how to re-enable it"() {
        given:
        def rec = nativeStubs()
        script.metaClass.toolSetAppDisabled = { Map a -> [success: false] }

        when:
        def out = script._rmRestoreFromBackup([fileName: "f.json"], nativeSnapshot())

        then:
        out.success == false
        out.partial == true
        out.note.contains("hub_set_app_disabled(disabled=false)")
    }

    def "a failed import deletes nothing"() {
        given:
        def rec = nativeStubs(importResult: [success: false, error: "no new child"])

        when:
        def out = script._rmRestoreFromBackup([fileName: "f.json"], nativeSnapshot())

        then:
        out.success == false
        out.error.contains("no new child")
        rec.deletes.isEmpty()
        rec.replays.isEmpty()
    }

    def "an old rule that will not delete leaves the copy disabled and says so"() {
        given:
        def rec = nativeStubs(deleteResult: [success: false, hubMessage: "has children"])

        when:
        def out = script._rmRestoreFromBackup([fileName: "f.json"], nativeSnapshot())

        then:
        out.success == false
        out.partial == true
        out.ruleId == 200
        out.error.contains("has children")
        out.note.contains("DISABLED")
        rec.enables.isEmpty()
    }

    def "a rule backup carries the App Cloner export, except for a rule with a Required Expression"() {
        given:
        def uploads = [:]
        script.metaClass.uploadHubFile = { String fn, byte[] b -> uploads[fn] = new String(b, "UTF-8") }
        def exportCalls = []
        script.metaClass._rmNativeExportForBackup = { Integer id -> exportCalls << id; [json: '{"appReplacements":{}}'] }
        hubGet.register('/installedapp/configure/json/100') { params -> '{"app":{"id":100,"label":"r","appType":{"name":"Rule-5.1"}},"configPage":{"sections":[]},"settings":{}}' }
        hubGet.register('/installedapp/statusJson/100') { params ->
            groovy.json.JsonOutput.toJson([appSettings: [], appState: [[name: "eval", value: tokens ? ["0": tokens] : [:]]]])
        }

        when:
        script._rmBackupRuleSnapshot(100, "pre-test")
        def snap = new groovy.json.JsonSlurper().parseText(uploads.values().first())

        then:
        (snap.nativeExport != null) == exported
        exportCalls.size() == (exported ? 1 : 0)
        exported || snap.nativeExportSkipped.contains("Required Expression")

        where:
        tokens || exported
        []     || true
        [1]    || false
    }
}
