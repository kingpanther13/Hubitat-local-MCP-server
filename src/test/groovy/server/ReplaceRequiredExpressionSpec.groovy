package server

import support.FakeRmExpressionEditor
import support.TestLocation
import support.ToolSpecBase
import spock.lang.Shared

/**
 * replaceRequiredExpression -- the in-place Required Expression replace, driven through RM's own
 * token editor (issue #503): the new expression is appended after the committed one, the old
 * tokens are removed only once it is complete, a failed build backs out to the original, and a
 * post-commit failure rolls back to the original tokens. STPage / ruleBuilderJson are served by
 * FakeRmExpressionEditor, modelled on the live RM 5.1.8 wire format.
 */
class ReplaceRequiredExpressionSpec extends ToolSpecBase {

    @Shared private TestLocation sharedLocation = new TestLocation()
    private FakeRmExpressionEditor fake

    def setupSpec() {
        appExecutor.getLocation() >> sharedLocation
    }

    def setup() {
        settingsMap.enableWrite = true
        settingsMap.enableRead = true
        stateMap.lastBackupTimestamp = 1234567890000L
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
        sharedLocation.modes = []
        fake = new FakeRmExpressionEditor().seedMode(1, ["3"]).withTokens([1])
        fake.install(script, hubGet)
    }

    def cleanup() {
        sharedLocation.modes = []
    }

    private Map switchSpec(String state = "on") { [conditions: [[capability: "Switch", deviceIds: [8], state: state]]] }

    // ---- token-editor replace ----------------------------------------------------------------

    def "replace appends the new expression in the token editor, then removes the old tokens -- no delete-first"() {
        when:
        def result = script._rmReplaceRequiredExpression(100, switchSpec())

        then: "the new expression is committed in place"
        result.success == true
        result.requiredExpressionReplaced == true
        result.conditionIndices == [2]
        fake.tokens == [2]
        fake.mode == "committed"

        and: "the edit went through the token editor and never clicked Delete Required Expression"
        !fake.clicks().contains("cancelST")
        def attrs = fake.clickAttrs()
        attrs.indexOf("1/insertTok") < attrs.indexOf("0/deleteToken")
        fake.clicks().containsAll(["editST", "editToken", "hasAll", "doneToken", "doneST", "updateRule"])

        and: "the replaced condition stays in the pool, unused (as with an edit in the RM UI)"
        fake.conds.containsKey(1)
    }

    def "a multi-condition spec with a sub-expression becomes the matching token sequence"() {
        given:
        def spec = [conditions: [[capability: "Switch", deviceIds: [8], state: "on"],
                                 [subExpression: [conditions: [[capability: "Switch", deviceIds: [8], state: "off"],
                                                               [capability: "Mode", modeIds: ["2"]]], operator: "OR"]]],
                    operator: "AND"]

        when:
        def result = script._rmReplaceRequiredExpression(100, spec)

        then:
        result.success == true
        fake.tokens == [2, "AND", "(", 3, "OR", 4, ")"]
    }

    def "a condition the page rejects backs out: the original expression stays committed and the attempt's condition is removed"() {
        when:
        def result = script._rmReplaceRequiredExpression(100, switchSpec("definitely_not_a_valid_state"))

        then: "the failure is reported with the original preserved"
        result.success == false
        result.requiredExpressionReplaced == false
        result.originalPreserved == true
        result.error.contains("not in capability 'Switch' domain")
        result.error.contains("left in place")

        and: "the rule still carries exactly the original expression, committed"
        fake.tokens == [1]
        fake.mode == "committed"

        and: "the cancelled condition's broken token and broken condition were cleaned up"
        !fake.conds.containsKey(2)
        result.removedConditionIds == [2]
        !fake.clicks().contains("cancelST")
    }

    def "the spec is validated before any click -- a bad operators list throws with no hub write"() {
        when:
        script._rmReplaceRequiredExpression(100, [
            conditions: [[capability: "Mode", modeIds: ["2"]], [capability: "Mode", modeIds: ["3"]]],
            operators: ["AND", "OR"]
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("replaceRequiredExpression.operators")
        fake.posts.isEmpty()
    }

    def "a bad operator value throws before any click"() {
        when:
        script._rmReplaceRequiredExpression(100, [
            conditions: [[capability: "Mode", modeIds: ["2"]], [capability: "Mode", modeIds: ["3"]]],
            operator: "NAND"
        ])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("replaceRequiredExpression.operator must be")
        fake.posts.isEmpty()
    }

    def "an unknown mode name is rejected before any click"() {
        given:
        sharedLocation.modes = [[id: "1", name: "Day"], [id: "3", name: "Night"]]

        when:
        script._rmReplaceRequiredExpression(100, [conditions: [[capability: "Mode", state: "NotAMode"]]])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("replaceRequiredExpression.conditions[0]")
        ex.message.contains("Unknown mode 'NotAMode'")
        fake.posts.isEmpty()
    }

    def "an unknown mode inside a sub-expression is rejected before any click"() {
        given:
        sharedLocation.modes = [[id: "1", name: "Day"]]

        when:
        script._rmReplaceRequiredExpression(100, [conditions: [[capability: "Switch", deviceIds: [8], state: "on"],
            [subExpression: [conditions: [[capability: "Mode", state: "Holiday"]]]]], operator: "AND"])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("subExpression.conditions[0]")
        ex.message.contains("Unknown mode 'Holiday'")
        fake.posts.isEmpty()
    }

    def "no committed Required Expression -> requiredExpressionMissing, nothing clicked"() {
        given:
        fake.withTokens([])

        when:
        def result = script._rmReplaceRequiredExpression(100, switchSpec())

        then:
        result.success == false
        result.requiredExpressionMissing == true
        result.error.contains("addRequiredExpression")
        fake.posts.findAll { it.path == "/installedapp/btn" }.isEmpty()
    }

    def "an editor that will not open changes nothing"() {
        given:
        fake.editorOpens = false

        when:
        def result = script._rmReplaceRequiredExpression(100, switchSpec())

        then:
        result.success == false
        result.originalPreserved == true
        result.error.contains("could not open the Required Expression editor")
        fake.tokens == [1]
        !fake.posts.any { it.path == "/installedapp/update/json" && it.body["settings[newToken0]"] != null }
    }

    def "a rejected trailing updateRule rolls back to the original expression"() {
        given: "the first updateRule (the replace's own) is rejected; the rollback's goes through"
        int calls = 0
        fake.onUpdateRule = { (++calls == 1) ? 500 : null }

        when:
        def result = script._rmReplaceRequiredExpression(100, switchSpec())

        then:
        result.success == false
        result.requiredExpressionReplaced == false
        result.requiredExpressionRestored == true
        result.updateRuleFailed == true
        result.error.contains("put back and confirmed")
        fake.tokens == [1]
        fake.mode == "committed"
    }

    def "a rollback that cannot complete says so -- never a false restored:true"() {
        given:
        fake.onUpdateRule = { 500 }

        when:
        def result = script._rmReplaceRequiredExpression(100, switchSpec())

        then:
        result.success == false
        result.requiredExpressionRestored == false
        result.error.contains("ALSO failed")
    }

    def "new rule-health problems after the switch roll the expression back"() {
        given:
        fake.onClick = { String n, String a -> if (n == "updateRule" && fake.tokens == [2]) fake.mainExtra = "**Broken Action**" }

        when:
        def result = script._rmReplaceRequiredExpression(100, switchSpec())

        then:
        result.success == false
        result.requiredExpressionReplaced == false
        result.requiredExpressionRestored == true
        fake.tokens == [1]
    }

    def "_rmRequiredExpressionTokenPlan validates sub-expression operators before any click"() {
        when:
        script._rmRequiredExpressionTokenPlan([[capability: "Switch"], [subExpression: [conditions: [[capability: "Switch"], [capability: "Mode"]]]]], "AND", null)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("conditions[1].subExpression with 2 conds requires operator or operators")
    }

    // ---- dispatcher ---------------------------------------------------------------------------

    def "hub_set_rule replaceRequiredExpression success envelope"() {
        when:
        def result = script.toolSetRule([appId: 100, replaceRequiredExpression: switchSpec(), confirm: true])

        then:
        result.success == true
        result.requiredExpressionReplaced == true
        result.appId == 100
        result.backup != null
        fake.tokens == [2]
    }

    def "hub_set_rule replaceRequiredExpression refuses on CREATE (no appId) -- loudly rejected as edit-only"() {
        when:
        script.toolSetRule([name: "New Rule", replaceRequiredExpression: switchSpec(), confirm: true])

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("replaceRequiredExpression")
        ex.message.toLowerCase().contains("edit-only")
    }

    def "hub_set_rule replaceRequiredExpression validation throw carries a no-changes restoreHint"() {
        when:
        def result = script.toolSetRule([appId: 100, replaceRequiredExpression: [
            conditions: [[capability: "Mode", modeIds: ["2"]], [capability: "Mode", modeIds: ["3"]]], operator: "NAND"], confirm: true])

        then:
        result.success == false
        result.restoreHint != null
        result.restoreHint.contains("No changes were made")
        result.restoreHint.contains("intact")
        !(result.restoreHint.toLowerCase().contains("roll back"))
        fake.tokens == [1]
    }

    def "_rmAddRequiredExpression preValidated=true still rejects a malformed spec -- it skips only the deviceId hub probe, NOT shape validation"() {
        when:
        script._rmAddRequiredExpression(100, [
            conditions: [[capability: "Mode", modeIds: ["2"]], [capability: "Mode", modeIds: ["3"]]],
            operators: ["AND", "OR"]
        ], true, true)

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("addRequiredExpression.operators")
    }

    // ---- patches[] ----------------------------------------------------------------------------

    def "patches[] replace op: a success commits with the single batch-end updateRule; a failed build leaves the earlier op and the original expression"() {
        when:
        def result = script.toolSetRule([appId: 100,
            patches: [[button: "pausRule"], [replaceRequiredExpression: switchSpec(state)]], confirm: true])

        then: "the preceding op is untouched either way"
        result.patches.find { it.op == "button" }?.success == true

        and:
        def repl = result.patches.find { it.op == "replaceRequiredExpression" }
        if (succeeds) {
            assert repl.success == true
            assert repl.requiredExpressionReplaced == true
            assert fake.tokens == [2]
            assert fake.clicks().count { it == "updateRule" } == 1
        } else {
            assert repl.success == false
            assert repl.originalPreserved == true
            assert fake.tokens == [1]
        }
        !result.containsKey("_deferredRERestore")
        !repl.containsKey("_deferredRERestore")

        where:
        state                           || succeeds
        "on"                            || true
        "definitely_not_a_valid_state"  || false
    }

    def "patches[]: a rejected batch-end updateRule rolls the replaced expression back to its original tokens"() {
        given:
        int calls = 0
        fake.onUpdateRule = { (++calls == 1) ? 500 : null }

        when:
        def result = script.toolSetRule([appId: 100, patches: [[replaceRequiredExpression: switchSpec()]], confirm: true])

        then:
        def repl = result.patches.find { it.op == "replaceRequiredExpression" }
        repl.success == false
        repl.requiredExpressionReplaced == false
        repl.requiredExpressionRestored == true
        fake.tokens == [1]
        result.success == false
    }

    def "patches[]: a second replaceRequiredExpression in one batch is refused (a rule has a single Required Expression)"() {
        when:
        def result = script.toolSetRule([appId: 100,
            patches: [[replaceRequiredExpression: switchSpec()], [replaceRequiredExpression: switchSpec("off")]], confirm: true])

        then:
        def entries = result.patches.findAll { it.op == "replaceRequiredExpression" }
        entries.size() == 2
        entries[0].success == true
        entries[1].success == false
        entries[1].error.contains("only one replaceRequiredExpression is valid")
        result.success == false
        result.partial == true
    }

    // ---- shared health / tell helpers -----------------------------------------------------------

    def "BUG-8: _rmHealthRegressedVsBaseline returns TRUE on a broken-marker COUNT increase the string set-diff would miss"() {
        given:
        // Direct unit test of the count-aware delta -- no wizard fixture, no render-flip timing.
        // _rmCheckRuleHealth collapses every broken marker of a type into ONE identical issues
        // string + a deduped list, so a string set-diff cancels a genuinely-NEW broken instance
        // when the baseline already carries one. The maps use the EXACT shape _rmCheckRuleHealth
        // returns (issues + structuralIssues + brokenMarkerCounts). The issues string is identical
        // in both, so only the count layer (1 -> 2) can flag the regression.
        def baseline = [issues: ["broken markers in render: **Broken Condition**"],
                        structuralIssues: [], brokenMarkerCounts: ["**Broken Condition**": 1]]
        def now = [issues: ["broken markers in render: **Broken Condition**"],
                   structuralIssues: [], brokenMarkerCounts: ["**Broken Condition**": 2]]

        expect:
        script._rmHealthRegressedVsBaseline(baseline, now) == true
    }

    def "BUG-8: _rmHealthRegressedVsBaseline returns FALSE on an UNCHANGED pre-existing broken marker (BUG-1 preserved)"() {
        given:
        // The must-NOT-catch companion: the SAME pre-existing **Broken Condition** at baseline AND
        // now with the SAME count (one) and the same issues string. Nothing is new, so a clean
        // replace on an already-imbalanced rule must NOT be rolled back. Goes RED if the count
        // layer wrongly treats any present marker as new.
        def baseline = [issues: ["broken markers in render: **Broken Condition**"],
                        structuralIssues: [], brokenMarkerCounts: ["**Broken Condition**": 1]]
        def now = [issues: ["broken markers in render: **Broken Condition**"],
                   structuralIssues: [], brokenMarkerCounts: ["**Broken Condition**": 1]]

        expect:
        script._rmHealthRegressedVsBaseline(baseline, now) == false
    }

    def "BUG-8: _rmHealthRegressedVsBaseline returns TRUE on a NEW structuralIssues entry (string layer sanity)"() {
        given:
        // String-layer sanity: a structuralIssues entry present now but absent at baseline is a
        // new break the set-diff layer catches (the count layer covers only broken markers).
        def baseline = [issues: [], structuralIssues: [], brokenMarkerCounts: [:]]
        def now = [issues: [], structuralIssues: ["IF block never closed -- missing endIf"],
                   brokenMarkerCounts: [:]]

        expect:
        script._rmHealthRegressedVsBaseline(baseline, now) == true
    }

    def "F-MSG: _rmHealthRegressionNewIssues names ONLY the newly-introduced issue, not a pre-existing baseline issue"() {
        given:
        // The restore message joins this list, so it must NOT blame pre-existing baseline issues
        // while saying the replace "introduced new" problems. Baseline carries issue A (string)
        // plus one pre-existing broken marker (count 1); now carries A (unchanged) PLUS a new
        // structural issue B AND a count increase on the marker (1 -> 2). The returned list must
        // contain B and the count-up marker but NOT A. Goes RED if the new-issues derivation
        // reverts to listing all current issues (it would then include A).
        def baseline = [issues: ["issue A -- pre-existing", "broken markers in render: **Broken Condition**"],
                        structuralIssues: [], brokenMarkerCounts: ["**Broken Condition**": 1]]
        def now = [issues: ["issue A -- pre-existing", "broken markers in render: **Broken Condition**"],
                   structuralIssues: ["issue B -- IF block never closed"],
                   brokenMarkerCounts: ["**Broken Condition**": 2]]

        when:
        def newIssues = script._rmHealthRegressionNewIssues(baseline, now)

        then: "the new structural issue B is named"
        newIssues.any { it.contains("issue B") }

        and: "the count-up marker is named with the count delta (now vs baseline)"
        newIssues.any { it.contains("**Broken Condition**") && it.contains("2 vs 1") }

        and: "the pre-existing issue A is NOT named (it was not introduced by the replace)"
        !newIssues.any { it.contains("issue A") }

        and: "the boolean gate agrees a regression occurred"
        script._rmHealthRegressedVsBaseline(baseline, now) == true
    }

    def "F-MSG: _rmHealthRegressionNewIssues is empty when nothing new (boolean gate false, no message blame)"() {
        given:
        // No new issue: an identical baseline and now (a pre-existing issue + unchanged marker
        // count). The list is empty, so the boolean gate is false and no restore message is built.
        def baseline = [issues: ["issue A -- pre-existing", "broken markers in render: **Broken Condition**"],
                        structuralIssues: [], brokenMarkerCounts: ["**Broken Condition**": 1]]
        def now = [issues: ["issue A -- pre-existing", "broken markers in render: **Broken Condition**"],
                   structuralIssues: [], brokenMarkerCounts: ["**Broken Condition**": 1]]

        expect:
        script._rmHealthRegressionNewIssues(baseline, now).isEmpty()
        script._rmHealthRegressedVsBaseline(baseline, now) == false
    }

    def "F-MSG: an UNREADABLE post-op probe never reads as a regression -- a couldn't-check must not fire the restore on committed work"() {
        given:
        // The unreadable verdict's issue strings are fetch diagnostics ("health check
        // failed: ..."), not evidence. Set-diffed against the baseline they would read
        // as new issues and roll back (or fail) work that committed cleanly -- the same
        // poison the walkStep gate fix closed. Goes RED if the unreadable guard is dropped.
        def baseline = [issues: [], structuralIssues: [], brokenMarkerCounts: [:]]
        def now = [unreadable: true,
                   issues: ["health check failed: status code: 404, reason phrase: Not Found"],
                   structuralIssues: [], brokenMarkerCounts: [:]]

        expect:
        script._rmHealthRegressionNewIssues(baseline, now).isEmpty()
        script._rmHealthRegressedVsBaseline(baseline, now) == false
    }

    def "BUG-8: a pre-existing **Broken Condition** with UNCHANGED count does NOT restore (BUG-1 preserved)"() {
        given:
        // The must-NOT-catch companion: the SAME pre-existing **Broken Condition** is present at
        // BOTH the baseline and post-commit with the SAME count (one). The
        // count is unchanged, so it is NOT a new break and the clean replace must NOT be rolled
        // back -- the BUG-1 over-restore guard still holds under the count-aware delta. Goes RED
        // if the count delta wrongly treats an unchanged pre-existing marker as new.
        fake.mainExtra = "IF(**Broken Condition**) THEN"

        when:
        def result = script.toolSetRule([
            appId: 100, replaceRequiredExpression: switchSpec(), confirm: true
        ])

        then: "the pre-existing broken marker (unchanged count) did NOT trigger a rollback"
        result.success == true
        result.requiredExpressionReplaced == true
        result.requiredExpressionRestored == null
        fake.tokens == [2]

        and: "the pre-existing marker is still surfaced in the health block (it was never the replace's doing)"
        result.health?.ok == false
        result.health?.brokenMarkerCounts?.get("**Broken Condition**") == 1
    }

    def "BUG-9: a preValidated spec with a non-Map condition still throws an actionable shape error"() {
        given:
        // skipDeviceExistence (the flag the replace delegate passes) must NOT skip the
        // condition-shape guard -- only the deviceId existence HUB probe. A malformed
        // conditions:[<non-Map>] must STILL get an actionable IllegalArgumentException, not a raw
        // cast/null dump deep in the walker. No hub pages registered: the throw must precede any
        // wizard read. Goes RED if the non-Map guard is moved back below the skipDeviceExistence
        // early-return.
        when: "a non-Map condition with preValidated=true (skipDeviceExistence)"
        script._rmAddRequiredExpression(100, [conditions: ["not-a-map"]], true, true)

        then: "the shape guard throws an actionable error naming the bad index"
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("conditions[0] is not a Map")
    }

    def "_rmIsCommittedRETell: two-field cancelST+editST tell for replace, three-field (+stopOnST) for the add-path guard"() {
        // The committed-RE marker the replace path detects + deletes against. requireStopOnST=false
        // (replace path): cancelST+editST with NO inline new-condition selector uniquely identifies
        // a committed RE. requireStopOnST=true (the add path's existing-RE refusal guard): the
        // stricter three-field tell, so a transient cancelST+editST render WITHOUT stopOnST does not
        // trip the add refusal. The !newCondSelector half rules out the delete-and-rebuild hybrid
        // render (cancelST+editST AND a cond/rCapab_ selector) so it is never misread as a survived
        // RE. This pins the relaxation directly -- previously it was only exercised transitively.
        expect:
        script._rmIsCommittedRETell(names as Set, requireStopOnST) == expected

        where:
        names                                      | requireStopOnST || expected
        ["cancelST", "editST", "doneST"]           | false           || true
        ["cancelST", "editST", "cond"]             | false           || false
        ["cancelST", "editST", "rCapab_2"]         | false           || false
        ["doneST"]                                 | false           || false
        null                                       | false           || false
        ["cancelST", "editST"]                     | true            || false
        ["cancelST", "editST", "stopOnST"]         | true            || true
        ["cancelST", "editST", "stopOnST", "cond"] | true            || false
    }
}
