package server

import groovy.json.JsonOutput
import support.FakeRmExpressionEditor
import support.ToolSpecBase

/**
 * Restoring an RM rule backup brings its Required Expression back (issue #504). The settings
 * replay cannot -- the expression lives in RM app state -- so the restore compares the snapshot's
 * rendered expression with the live rule and rebuilds it through the wizard, each snapshot
 * condition re-walked from its saved settings. STPage / ruleBuilderJson are served by
 * FakeRmExpressionEditor.
 */
class RestoreRequiredExpressionSpec extends ToolSpecBase {

    private FakeRmExpressionEditor fake

    def setup() {
        settingsMap.enableWrite = true
        settingsMap.enableRead = true
        stateMap.lastBackupTimestamp = 1234567890000L
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        fake = new FakeRmExpressionEditor()
        fake.install(script, hubGet)
    }

    // A backup snapshot carrying `tokens` as its Required Expression, with each condition's
    // settings and rendered text.
    private Map snapshot(List tokens, Map condSettings, Map texts, boolean useST = true) {
        [schemaVersion: 1, ruleId: 100, appId: 100, appType: "rule_machine", reason: "pre-update",
         timestamp: 1L, appLabel: "r",
         configJson: [app: [id: 100, appType: [name: "Rule-5.1", namespace: "hubitat"]],
                      configPage: [name: "mainPage", sections: [[input: [[name: "useST", type: "bool"]]]]],
                      settings: [useST: useST ? "true" : ""] + condSettings],
         statusJson: [appSettings: [],
                      appState: [[name: "eval", value: tokens ? ["0": tokens] : [:]], [name: "capabsfalse", value: texts]]]]
    }

    private Map modeNightSnapshot() {
        snapshot([1], [rCapab_1: "Mode", modes1: ["3"]], ["1": "Mode is 3"])
    }

    def "a snapshot expression is rebuilt on a rule that has none"() {
        given:
        fake.withTokens([])

        when:
        def out = script._rmRestoreRequiredExpression(100, modeNightSnapshot())

        then:
        out.requiredExpressionRestored == true
        fake.renderedExpression() == ["Mode is 3"]
        fake.mode == "committed"
    }

    def "a multi-condition snapshot expression replaces a different live one"() {
        given:
        fake.seedSwitch(1, "on").withTokens([1])
        def snap = snapshot([1, "OR", 2],
            [rCapab_1: "Mode", modes1: ["3"], rCapab_2: "Switch", rDev_2: ["8": "S8"], state_2: "off"],
            ["1": "Mode is 3", "2": "Switch 8 is off"])

        when:
        def out = script._rmRestoreRequiredExpression(100, snap)

        then:
        out.requiredExpressionRestored == true
        fake.renderedExpression() == ["Mode is 3", "OR", "Switch 8 is off"]
        !fake.clicks().contains("cancelST")
    }

    def "a multi-condition snapshot is rebuilt on a rule with none, clearing the insert the editor reopens with"() {
        given:
        fake.withTokens([])
        fake.pendingInsert = true
        def snap = snapshot([1, "OR", 2],
            [rCapab_1: "Mode", modes1: ["3"], rCapab_2: "Mode", modes2: ["4"]],
            ["1": "Mode is 3", "2": "Mode is 4"])

        when:
        def out = script._rmRestoreRequiredExpression(100, snap)

        then:
        out.requiredExpressionRestored == true
        fake.renderedExpression() == ["Mode is 3", "OR", "Mode is 4"]
        fake.clicks().contains("cancelInsert")
    }

    def "a live expression the snapshot did not have is removed"() {
        given:
        fake.seedSwitch(1, "on").withTokens([1])

        when:
        def out = script._rmRestoreRequiredExpression(100, snapshot([], [:], [:], false))

        then:
        out.requiredExpressionRestored == true
        out.requiredExpressionRemoved == true
        fake.tokens == []
    }

    def "an expression that already matches is left alone"() {
        given:
        fake.seedMode(1, ["3"]).withTokens([1])

        when:
        def out = script._rmRestoreRequiredExpression(100, modeNightSnapshot())

        then:
        out.requiredExpressionRestored == true
        fake.posts.isEmpty()
    }

    def "a live expression that cannot be read is not reported as matching a snapshot without one"() {
        given:
        script.metaClass._rmCollectPageInputNames = { Integer id, String page -> throw new RuntimeException("timeout") }

        when:
        def out = script._rmRestoreRequiredExpression(100, snapshot([], [:], [:], false))

        then:
        out.requiredExpressionRestored == false
        out.requiredExpressionError.contains("could not be read")
    }

    def "a failed rebuild over a live expression trims back to the original"() {
        given: "the snapshot's second condition has no saved capability, so its slot cannot be filled"
        fake.seedSwitch(1, "on").withTokens([1])
        def snap = snapshot([2, "OR", 3],
            [rCapab_2: "Mode", modes2: ["3"], modes3: ["4"]],
            ["2": "Mode is 3", "3": "Mode is 4"])

        when:
        def out = script._rmRestoreRequiredExpression(100, snap)

        then:
        out.requiredExpressionRestored == false
        out.preRestoreExpressionKept == true
        out.requiredExpressionError.contains("left in place")
        fake.tokens == [1]
        fake.mode == "committed"

        and: "the conditions the failed rebuild added are gone again"
        fake.conds.keySet() == [1] as Set
        !out.containsKey("leftoverConditionIds")
    }

    def "a failed multi-condition rebuild on a rule with none keeps the committed first condition and says so"() {
        given:
        fake.withTokens([])
        def snap = snapshot([2, "OR", 3],
            [rCapab_2: "Mode", modes2: ["3"], modes3: ["4"]],
            ["2": "Mode is 3", "3": "Mode is 4"])

        when:
        def out = script._rmRestoreRequiredExpression(100, snap)

        then:
        out.requiredExpressionRestored == false
        out.requiredExpressionPartial == true
        !out.containsKey("preRestoreExpressionKept")
        out.requiredExpressionError.contains("first condition")
        fake.renderedExpression() == ["Mode is 3"]
        fake.mode == "committed"
        fake.conds.keySet() == fake.tokens as Set
    }

    def "a failed rebuild of a snapshot starting with a paren keeps the committed first condition, not the paren"() {
        given:
        fake.withTokens([])
        def snap = snapshot(["(", 2, "OR", 3, ")"],
            [rCapab_2: "Mode", modes2: ["3"], modes3: ["4"]],
            ["2": "Mode is 3", "3": "Mode is 4"])

        when:
        def out = script._rmRestoreRequiredExpression(100, snap)

        then:
        out.requiredExpressionRestored == false
        out.requiredExpressionPartial == true
        fake.renderedExpression() == ["Mode is 3"]
        fake.mode == "committed"
    }

    def "a rebuild that only fails at its last click is finished, not undone"() {
        given: "the rebuild's own updateRule is rejected once"
        fake.seedSwitch(1, "on").withTokens([1])
        int calls = 0
        fake.onUpdateRule = { (++calls == 1) ? 500 : null }
        def snap = snapshot([1, "OR", 2],
            [rCapab_1: "Mode", modes1: ["3"], rCapab_2: "Mode", modes2: ["4"]],
            ["1": "Mode is 3", "2": "Mode is 4"])

        when:
        def out = script._rmRestoreRequiredExpression(100, snap)

        then:
        out.requiredExpressionRestored == true
        fake.renderedExpression() == ["Mode is 3", "OR", "Mode is 4"]
        fake.mode == "committed"
    }

    def "a back-out that cannot put the pre-restore expression back says so and keeps its conditions"() {
        given: "token deletes fail, so the editor cannot be set back"
        fake.seedSwitch(1, "on").withTokens([1])
        fake.onClick = { String n, String a -> if (a == "deleteToken") throw new RuntimeException("hub busy") }
        def snap = snapshot([2, "OR", 3],
            [rCapab_2: "Mode", modes2: ["3"], modes3: ["4"]],
            ["2": "Mode is 3", "3": "Mode is 4"])

        when:
        def out = script._rmRestoreRequiredExpression(100, snap)

        then:
        out.requiredExpressionRestored == false
        out.preRestoreExpressionKept == false
        out.requiredExpressionError.contains("may be partly rebuilt")
        out.leftoverConditionIds
    }

    def "a rebuild that commits nothing says the rule runs ungated"() {
        given: "the snapshot's only condition has no saved capability"
        fake.withTokens([])
        def snap = snapshot([2], [modes2: ["3"]], ["2": "Mode is 3"])

        when:
        def out = script._rmRestoreRequiredExpression(100, snap)

        then:
        out.requiredExpressionRestored == false
        out.requiredExpressionError.contains("ungated")
        fake.tokens == []
    }

    def "an unreadable read-back is reported as unreadable, never as an empty expression"() {
        given:
        fake.withTokens([])
        int reads = 0
        hubGet.register('/app/ruleBuilderJson/100') { params -> (++reads > 1) ? null : fake.ruleBuilderJson() }

        when:
        def out = script._rmRestoreRequiredExpression(100, modeNightSnapshot())

        then:
        out.requiredExpressionRestored == false
        out.requiredExpressionError.contains("could not be read back")
    }

    def "a condition any expression still uses is never deleted"() {
        given:
        fake.seedSwitch(1, "on").seedMode(2, ["3"]).withTokens([1])

        when:
        def removed = script._rmDeleteExpressionConditions(100, [1, 2])

        then:
        removed*.toString() == ["2"]
        fake.conds.keySet() == [1] as Set
    }

    def "conditions are not deleted when the rule's expressions cannot be read"() {
        given:
        fake.seedMode(2, ["3"]).withTokens([])
        hubGet.register('/app/ruleBuilderJson/100') { params -> throw new RuntimeException("timeout") }

        expect:
        script._rmDeleteExpressionConditions(100, [2]) == []
        fake.conds.containsKey(2)
    }

    def "a snapshot without expression state is not second-guessed"() {
        expect:
        script._rmRestoreRequiredExpression(100, [statusJson: [appSettings: []], configJson: [settings: [:]]]) == [:]
    }

    def "a condition that cannot be rebuilt is reported, never a false restored:true"() {
        given: "the snapshot condition has no saved capability, so the new slot cannot be filled"
        fake.withTokens([])
        def snap = snapshot([1], [modes1: ["3"]], ["1": "Mode is 3"])

        when:
        def out = script._rmRestoreRequiredExpression(100, snap)

        then:
        out.requiredExpressionRestored == false
        out.requiredExpressionError.contains("could not be completed")
    }

    def "hub_restore_backup's rule restore rebuilds the expression and says so"() {
        given:
        fake.withTokens([])
        def bytes = JsonOutput.toJson(modeNightSnapshot()).getBytes("UTF-8")
        script.metaClass.downloadHubFile = { String fn -> bytes }

        when:
        def out = script._rmRestoreFromBackup([fileName: "mcp-rm-backup-100-x.json", backupKey: "rm-rule_100_x"])

        then:
        out.success == true
        out.requiredExpressionRestored == true
        out.note.contains("Required Expression confirmed to match the backup")
        fake.renderedExpression() == ["Mode is 3"]
    }

    def "a rule restore whose expression cannot be rebuilt is NOT reported as success"() {
        given:
        fake.withTokens([])
        def bytes = JsonOutput.toJson(snapshot([1], [modes1: ["3"]], ["1": "Mode is 3"])).getBytes("UTF-8")
        script.metaClass.downloadHubFile = { String fn -> bytes }

        when:
        def out = script._rmRestoreFromBackup([fileName: "mcp-rm-backup-100-x.json", backupKey: "rm-rule_100_x"])

        then:
        out.success == false
        out.partial == true
        out.requiredExpressionRestored == false
        out.error.contains("Required Expression does not match the backup")
    }
}
