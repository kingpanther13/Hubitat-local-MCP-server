package server

import support.ToolSpecBase
import groovy.json.JsonOutput

/**
 * replaceRequiredExpression -- the in-place Required Expression replace path,
 * with the destructive-window contract: the cancelST delete is immediately
 * destructive, so the spec is validated BEFORE the click, and any POST-delete
 * failure (rebuild error, post-commit health flip, rejected trailing updateRule)
 * auto-restores the pre-op backup.
 *
 * Drives _rmReplaceRequiredExpression directly (the helper that owns the
 * cancelST delete, the validate-before-delete guard, and the restore-on-failure
 * logic) plus the toolSetRule dispatcher branch. STPage is modeled on the REAL
 * RM 5.1.8 schema: a committed Required Expression renders the committed-expression
 * controls -- cancelST ("Delete Required Expression") + editST + stopOnST/evalOnBoot
 * bools + doneST -- with the conditions living on a separate selectConditions
 * sub-page (so the inline cond new-condition selector is withheld). Clicking
 * cancelST deletes the whole expression and returns the rule to the no-RE state,
 * from which the delegated _rmAddRequiredExpression navigates fresh and reaches
 * the cond selector. This mirrors the live-proven 5.1.8 transition the helper drives.
 *
 * The delegated condition build (_rmAddRequiredExpression) and the restore
 * (_rmRestoreFromBackup) are real (internal calls bypass per-instance
 * metaClass); the restore is steered via the dynamic downloadHubFile helper.
 */
class ReplaceRequiredExpressionSpec extends ToolSpecBase {

    // Common write-enable + benign-upload stub shared by every scenario. setup()
    // runs after HarnessSpec.setup() (which resets settingsMap/stateMap/hubGet), so
    // these seed cleanly per test. uploadHubFile is purely-dynamic (reliably
    // interceptable via per-instance metaClass); the no-op keeps backup writes quiet.
    def setup() {
        settingsMap.enableWrite = true
        settingsMap.enableRead = true
        stateMap.lastBackupTimestamp = 1234567890000L
        script.metaClass.uploadHubFile = { String fn, byte[] b -> }
    }

    // ---- Page-JSON builders -------------------------------------------------

    // Generic configPage envelope for any page. `inputs` is the section input list;
    // `extra` overrides/augments the configPage map (e.g. paragraphs, a body block).
    private String pageJson(String pageName, List inputs, Map extra = [:]) {
        def install = (pageName == "mainPage")
        def section = [title: "", input: inputs]
        if (extra.containsKey("sectionBody")) section.body = extra.sectionBody
        if (extra.containsKey("paragraphs")) section.paragraphs = extra.paragraphs
        def configPage = [name: pageName, title: pageName == "mainPage" ? "Edit Rule" : "Required Expression",
                          install: install, error: extra.containsKey("error") ? extra.error : null,
                          sections: [section]]
        JsonOutput.toJson([
            app: [id: 100, name: "Rule-5.1", label: extra.label ?: "r", trueLabel: extra.label ?: "r",
                  installed: true, version: "7", appType: [name: "Rule-5.1", namespace: "hubitat"]],
            configPage: configPage, settings: [:], childApps: []
        ])
    }

    // STPage schema for a given phase. phase 0 = committed RE (the 5.1.8
    // committed-expression controls: cancelST + editST + stopOnST/evalOnBoot +
    // doneST; conditions are on the selectConditions sub-page, so NO inline cond).
    // phase 1 = post-cancelST (the committed-expression controls are gone -- the
    // RE has been deleted and the rule is back in the no-RE state).
    private String stPageForPhase(int phase) {
        def inputs = (phase == 0) ? [
            [name: "cancelST", type: "button"], [name: "editST", type: "button"],
            [name: "stopOnST", type: "bool"], [name: "evalOnBoot", type: "bool"],
            [name: "doneST", type: "button"]
        ] : [
            [name: "doneST", type: "button"]
        ]
        pageJson("STPage", inputs)
    }

    // mainPage with the bare useST toggle (used as the navigate precursor).
    private String mainPageJson() {
        pageJson("mainPage", [[name: "useST", type: "bool"]])
    }

    // Minimal RM rule config JSON (bare-id config + named sub-pages). Mirrors the
    // helper in ToolRmNativeCrudSpec so the dispatcher's health probe + sub-page
    // navigations resolve cleanly (no broken markers -> health.ok). `paragraphs`
    // lets a spec inject a **Broken Condition** marker so the health check fails.
    private String ruleConfigJson(int ruleId, String label = "r", List inputs = [], List paragraphs = null) {
        def section = [title: "", input: inputs]
        if (paragraphs != null) section.paragraphs = paragraphs
        JsonOutput.toJson([
            app: [id: ruleId, name: "Rule-5.1", label: label, trueLabel: label, installed: true,
                  appType: [name: "Rule-5.1", namespace: "hubitat"]],
            configPage: [name: "mainPage", title: "Edit Rule", install: true, error: null,
                         sections: [section]],
            settings: [:], childApps: []
        ])
    }

    private String statusJson100() {
        JsonOutput.toJson([installedApp: [id: 100], appSettings: [], eventSubscriptions: [[name: "evt1"]],
                           scheduledJobs: [], appState: [:], childAppCount: 0, childDeviceCount: 0])
    }

    // statusJson with a PRE-EXISTING unbalanced IF action block: an opener (condActs /
    // getIfThen at index 1) with NO closing endIf. _rmCheckRuleHealth's structural walker
    // flags this as a structuralIssue -> health.ok==false. Because it is STATIC (returned
    // identically on the baseline read and the post-commit read), the imbalance appears in
    // BOTH health snapshots, so the Bug-1 delta gate sees no NEW issue and a clean replace
    // is NOT rolled back. Models a rule still being built (a half-open IF) when its RE is
    // replaced -- the exact pre-existing-imbalance case the absolute-health gate mishandled.
    private String statusJson100ImbalancedIf() {
        JsonOutput.toJson([installedApp: [id: 100],
                           appSettings: [[name: "actType.1", value: "condActs", type: "enum"],
                                         [name: "actSubType.1", value: "getIfThen", type: "enum"]],
                           eventSubscriptions: [[name: "evt1"]],
                           scheduledJobs: [], appState: [:], childAppCount: 0, childDeviceCount: 0])
    }

    // A valid pre-op backup snapshot (schemaVersion 1, rule_machine) that
    // _rmRestoreFromBackup can parse + replay. Empty saved settings means the
    // in-place restore just re-seeds the label and fires updateRule.
    private byte[] backupSnapshotBytes() {
        JsonOutput.toJson([
            schemaVersion: 1, ruleId: 100, appId: 100, appType: "rule_machine",
            reason: "pre-replaceRequiredExpression", timestamp: 1L, appLabel: "r",
            configJson: [app: [id: 100, appType: [name: "Rule-5.1", namespace: "hubitat"]],
                         configPage: [name: "mainPage", sections: [[input: []]]], settings: [:]],
            statusJson: [appSettings: []]
        ]).getBytes("UTF-8")
    }

    private Map backupEntry() {
        [backupKey: "rm-rule_100_20260101-000000", type: "rm-rule", id: 100, ruleId: 100,
         fileName: "mcp-rm-backup-100-20260101-000000.json", reason: "pre-replaceRequiredExpression"]
    }

    // The full condition-building STPage schema (post-delete no-RE state, reached
    // by the delegate's fresh navigate) that lets the delegated _rmAddRequiredExpression
    // walk a Switch condition to completion: cond -> rCapab_1 -> rDev_1 -> state_1 ->
    // hasAll -> hasRule/doneST. No committed-expression controls, so the delegate's
    // existing-RE guard passes.
    private String stPageSuccessSchema(int seq) {
        pageJson("STPage", [
            [name: "cond", type: "enum", options: ["a": "New condition", "b": "( sub-expression"]],
            [name: "rCapab_1", type: "enum", options: ["Switch", "Contact", "Motion"]],
            [name: "rDev_1", type: "capability.switch", multiple: true],
            [name: "state_1", type: "enum", options: ["on", "off"]],
            [name: "hasAll", type: "button"],
            [name: "hasRule", type: "button"],
            [name: "doneST", type: "button"]
        ], [paragraphs: ["seq ${seq}".toString()]])
    }

    // mainPage with the RE paragraph BAKED (post-commit verification looks for the
    // absence of the "Define Required Expression" placeholder here).
    private String mainPageBakedJson() {
        pageJson("mainPage", [[name: "useST", type: "bool"]],
                 [sectionBody: [[element: "paragraph", description: "Switch1 is on"]]])
    }

    // mainPage that did NOT bake -- the paragraph still shows the bare "Define Required
    // Expression" placeholder, so the delegate's post-commit render check fails and it
    // returns a structured success:false + hubRenderError:true (used to drive that branch).
    private String mainPageNotBakedJson() {
        pageJson("mainPage", [[name: "useST", type: "bool"]],
                 [sectionBody: [[element: "paragraph", description: "Define Required Expression"]]])
    }

    // Standard doActPage fixture for the ghost-ifThen / predCapabs-clear navigation
    // the delegate runs after committing the expression (Step 4b).
    private String doActPageJson() {
        ruleConfigJson(100, "r", [
            [name: "actType.1",    type: "enum", options: ["condActs": "Conditional Actions"]],
            [name: "actSubType.1", type: "enum", options: ["getIfThen": "IF Expression THEN"]],
            [name: "actionCancel", type: "button"]
        ])
    }

    // restoredRef is the post-restore re-render signal. The Bug-3 read-back re-reads STPage
    // after the in-place restore replay and requires the committed-RE tell before trusting
    // restored:true. A real successful restore re-renders the committed RE; the fixtures
    // model that by flipping restoredRef[0] once downloadHubFile fires (the restore's first
    // action and ONLY caller of it) so the subsequent read-back STPage GET shows the
    // committed-RE controls again. installRestoreDownload wires a downloadHubFile stub that
    // records the read filenames AND flips restoredRef. Specs that want to model a restore
    // that did NOT re-activate the gate (the new Bug-3 negative) install their own stub that
    // leaves restoredRef unset.
    private List installRestoreDownload(List restoredRef, List fileReads = []) {
        script.metaClass.downloadHubFile = { String fileName ->
            fileReads << fileName
            restoredRef[0] = true
            backupSnapshotBytes()
        }
        return fileReads
    }

    // Installs the standard cancelST phase machine on hubInternalPostForm: records every
    // POST and flips the phase ref to 1 when cancelST is clicked. Returns the posts list
    // so a spec can assert on the wire traffic. `onUpdateRule`, when supplied, runs for
    // the settings[updateRule]=clicked POST (used to simulate a rejected trailing click).
    private List cancelStPhaseMachine(List phaseRef, Closure onUpdateRule = null) {
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            if (path == "/installedapp/btn") {
                if (body.name == "cancelST") phaseRef[0] = 1
            }
            if (onUpdateRule != null && body["settings[updateRule]"] == "clicked") {
                onUpdateRule.call(body)
            }
            [status: 200, location: null, data: '']
        }
        return posts
    }

    // Registers the no-mutation page set for the helper-direct restore specs: a bare
    // config + mainPage, an STPage driven off the phase ref, and statusJson for the
    // real restore replay. `stPagePhaseSupplier` lets a spec inject a custom STPage
    // closure (e.g. one that throws on phase 1). `restoredRef`, when supplied, makes the
    // default STPage supplier render the committed-RE tell once the restore has replayed
    // (restoredRef[0]==true) so the Bug-3 read-back confirms the RE re-activated.
    private void registerHelperPages(List phaseRef, Closure stPagePhaseSupplier = null, List restoredRef = null) {
        hubGet.register('/installedapp/configure/json/100') { params -> mainPageJson() }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> mainPageJson() }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            if (stPagePhaseSupplier != null) return stPagePhaseSupplier.call()
            // Post-restore re-render: a confirmed in-place restore puts the committed RE back.
            if (restoredRef != null && restoredRef[0]) return stPageForPhase(0)
            stPageForPhase(phaseRef[0])
        }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson100() }
    }

    // Registers the success-walk page set (committed-RE then full condition-building
    // schema after cancelST): the delegate walks a Switch condition to completion.
    // `bareConfigSupplier` overrides the bare config (default clean; a spec can inject a
    // **Broken Condition** paragraph to flip the post-commit health check). `mainPageSupplier`
    // overrides the post-commit mainPage render (default baked).
    private void registerSuccessWalkPages(List phaseRef, List stSeqRef,
                                          Closure bareConfigSupplier = null, Closure mainPageSupplier = null,
                                          List restoredRef = null, Closure statusSupplier = null) {
        hubGet.register('/installedapp/configure/json/100') { params ->
            (bareConfigSupplier != null) ? bareConfigSupplier.call() : ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
        }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params ->
            (mainPageSupplier != null) ? mainPageSupplier.call() : mainPageBakedJson()
        }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            // Post-restore re-render: once the restore has replayed, the committed RE is back
            // so the Bug-3 read-back confirms (committed-RE tell), independent of the walk seq.
            if (restoredRef != null && restoredRef[0]) return stPageForPhase(0)
            if (phaseRef[0] < 1) return stPageForPhase(phaseRef[0])
            stSeqRef[0]++
            stPageSuccessSchema(stSeqRef[0])
        }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params -> ruleConfigJson(100, "r", [[name: "N", type: "button"]]) }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params -> doActPageJson() }
        hubGet.register('/installedapp/statusJson/100') { params -> (statusSupplier != null) ? statusSupplier.call() : statusJson100() }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }
    }

    private Map singleModeSpec() { [conditions: [[capability: "Mode", modeIds: ["2"]]]] }
    private Map singleSwitchSpec() { [conditions: [[capability: "Switch", deviceIds: [8], state: "on"]]] }

    // ---- Scenarios ----------------------------------------------------------

    def "replaceRequiredExpression clicks cancelST to delete the committed RE then reaches the new-condition walker"() {
        given:
        // cancelST deletes the committed RE; the post-delete STPage no longer shows
        // the committed-expression controls. The minimal phase-1 schema (no cond,
        // no rCapab_<N> reveal chain) makes the delegated _rmAddRequiredExpression
        // walker write cond=a then throw on the missing rCapab_<N> slot -- a
        // POST-delete failure. With NO backup passed, the helper reports the honest
        // DELETED/not-restored envelope. This spec pins the orchestration (the single
        // cancelST delete + reaching the walker); the restore branches are pinned in
        // the dedicated specs below.
        def phase = [0]
        registerHelperPages(phase)
        def posts = cancelStPhaseMachine(phase)

        when: "called with NO backup (the rebuild will fail on the minimal schema)"
        def result = script._rmReplaceRequiredExpression(100, singleModeSpec())

        then: "exactly one cancelST delete was clicked -- NOT the old editST/eraseRule gesture"
        def btnNames = posts.findAll { it.path == "/installedapp/btn" }*.body*.name
        btnNames.count { it == "cancelST" } == 1
        !btnNames.contains("editST")
        !btnNames.contains("eraseRule")

        and: "the delegated walker was reached after the delete -- it wrote the cond new-condition selector"
        posts.find { it.path == "/installedapp/update/json" && it.body["settings[cond]"] == "a" } != null

        and: "with no backup available the post-delete failure is reported honestly -- never a benign no-op"
        result.success == false
        result.requiredExpressionReplaced == false
        result.requiredExpressionRestored == false
        result.error?.contains("DELETED")
        // Disambiguator unique to the no-backup branch (the restore-fail sibling instead
        // says auto-restore failed) -- pins THIS branch, not just any DELETED message.
        result.error?.contains("no backup was available")
    }

    def "replaceRequiredExpression auto-restores the pre-op backup when the post-delete rebuild fails"() {
        given:
        // cancelST deletes the RE. The minimal phase-1 schema makes the delegated
        // walker throw post-delete (rCapab_<N> absent), exercising the
        // restoreAfterDelete path. downloadHubFile returns a valid snapshot so the
        // real _rmRestoreFromBackup completes -> restored:true.
        def phase = [0]
        def restored = [false]
        registerHelperPages(phase, null, restored)
        def restoreFileRead = installRestoreDownload(restored)
        def posts = cancelStPhaseMachine(phase)

        when: "called WITH a valid pre-op backup"
        def result = script._rmReplaceRequiredExpression(100, singleModeSpec(), backupEntry())

        then: "the RE was deleted, the rebuild failed, and the original was auto-restored from the backup"
        result.success == false
        result.requiredExpressionReplaced == false
        result.requiredExpressionRestored == true
        result.error?.contains("restored from backup")
        result.error?.contains(backupEntry().backupKey)

        and: "the restore actually read the pre-op backup file"
        restoreFileRead.contains(backupEntry().fileName)

        and: "cancelST did fire (the destructive step was reached) before the restore"
        posts.any { it.path == "/installedapp/btn" && it.body.name == "cancelST" }
    }

    def "replaceRequiredExpression reports DELETED-and-not-restored when the post-delete auto-restore itself fails"() {
        given:
        def phase = [0]
        registerHelperPages(phase)
        // The restore's first step reads the backup file; make it throw so the
        // restore fails -- the helper must then report DELETED + not-restored + the
        // manual recovery command, never a benign no-op.
        script.metaClass.downloadHubFile = { String fileName -> throw new RuntimeException("file gone") }
        cancelStPhaseMachine(phase)

        when:
        def result = script._rmReplaceRequiredExpression(100, singleModeSpec(), backupEntry())

        then: "the failure is reported as DELETED + auto-restore-also-failed with the manual recovery hint"
        result.success == false
        result.requiredExpressionReplaced == false
        result.requiredExpressionRestored == false
        result.error?.contains("DELETED")
        result.error?.contains("hub_restore_backup")
        result.error?.contains(backupEntry().backupKey)
    }

    def "replaceRequiredExpression validates the spec BEFORE any click -- bad operators leave the RE intact"() {
        given:
        // STPage shows the committed-RE controls (a real RE exists). The spec is
        // malformed (operators length != conditions-1) and MUST be rejected before
        // the cancelST delete so the committed RE is never deleted.
        def phase = [0]
        hubGet.register('/installedapp/configure/json/100') { params -> mainPageJson() }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> mainPageJson() }
        hubGet.register('/installedapp/configure/json/100/STPage') { params -> stPageForPhase(0) }
        def posts = cancelStPhaseMachine(phase)

        when: "two conditions but a two-element operators list (length must be conditions-1 == 1)"
        script._rmReplaceRequiredExpression(100, [
            conditions: [[capability: "Mode", modeIds: ["2"]], [capability: "Mode", modeIds: ["3"]]],
            operators: ["AND", "OR"]
        ], backupEntry())

        then: "validation throws BEFORE any wizard interaction"
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("replaceRequiredExpression.operators")

        and: "NO cancelST click fired and no condition write reached the walker -- the RE is intact"
        !posts.any { it.path == "/installedapp/btn" && it.body.name == "cancelST" }
        !posts.any { it.path == "/installedapp/update/json" && it.body["settings[cond]"] != null }
    }

    def "replaceRequiredExpression validates a bad operator value BEFORE any click"() {
        given:
        def phase = [0]
        hubGet.register('/installedapp/configure/json/100') { params -> mainPageJson() }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> mainPageJson() }
        hubGet.register('/installedapp/configure/json/100/STPage') { params -> stPageForPhase(0) }
        def posts = cancelStPhaseMachine(phase)

        when: "an unsupported operator on a multi-condition spec"
        script._rmReplaceRequiredExpression(100, [
            conditions: [[capability: "Mode", modeIds: ["2"]], [capability: "Mode", modeIds: ["3"]]],
            operator: "NAND"
        ], backupEntry())

        then: "validation throws on the operator before any wizard interaction"
        def ex = thrown(IllegalArgumentException)
        // Singular-operator branch's unique text -- prefix-tight so it cannot pass
        // against the plural `.operators` message.
        ex.message.contains("replaceRequiredExpression.operator must be")

        and: "no cancelST click fired -- the RE is intact"
        !posts.any { it.path == "/installedapp/btn" && it.body.name == "cancelST" }
    }

    def "replaceRequiredExpression refuses when no committed Required Expression exists"() {
        given:
        // STPage is already in the no-RE state -- no committed-expression controls
        // (cancelST/editST), so there is nothing to replace.
        def phase = [1]
        hubGet.register('/installedapp/configure/json/100') { params -> mainPageJson() }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> mainPageJson() }
        hubGet.register('/installedapp/configure/json/100/STPage') { params -> stPageForPhase(1) }
        def posts = cancelStPhaseMachine(phase)

        when:
        def result = script._rmReplaceRequiredExpression(100, singleModeSpec(), backupEntry())

        then: "it returns a clear missing-RE error steering to addRequiredExpression (RE intact)"
        result.success == false
        result.requiredExpressionMissing == true
        result.error?.toLowerCase()?.contains("no committed required expression")
        result.error?.contains("addRequiredExpression")

        and: "no cancelST click fired and no condition write reached the walker"
        !posts.any { it.path == "/installedapp/btn" && it.body.name == "cancelST" }
        !posts.any { it.path == "/installedapp/update/json" && it.body["settings[cond]"] != null }
    }

    def "replaceRequiredExpression restores when cancelST is silently rejected (committed controls survive)"() {
        given:
        // STPage stays in the committed-RE state even after cancelST is clicked --
        // the delete was silently rejected (cancelST/editST never disappear). The
        // helper must auto-restore the pre-op backup and fail loud naming the step,
        // rather than delegating into a page that still withholds cond. statusJson is
        // registered (via registerHelperPages) so the REAL _rmRestoreFromBackup completes
        // its in-place replay cleanly (returns success:true) -- otherwise an unstubbed
        // read makes the real restore partial-fail and (correctly) reports restored:false.
        // The phase stays pinned at 0 (cancelStPhaseMachine WOULD flip it, but the STPage
        // supplier here ignores the phase and always renders the committed controls).
        def phase = [0]
        registerHelperPages(phase, { stPageForPhase(0) })
        def restoreFileRead = []
        script.metaClass.downloadHubFile = { String fileName -> restoreFileRead << fileName; backupSnapshotBytes() }
        def posts = cancelStPhaseMachine(phase)

        when:
        def result = script._rmReplaceRequiredExpression(100, singleModeSpec(), backupEntry())

        then: "it returns success:false naming the cancelST delete that did not take"
        result.success == false
        result.error?.contains("cancelST")

        and: "no condition write reached the walker -- the delegate was never started"
        !posts.any { it.path == "/installedapp/update/json" && it.body["settings[cond]"] != null }

        and: "the pre-op backup was auto-restored (the delete state is uncertain, so restore to be safe)"
        result.requiredExpressionRestored == true
        restoreFileRead.contains(backupEntry().fileName)
    }

    def "hub_set_rule replaceRequiredExpression SUCCESS path: cancelST delete + clean rebuild returns the success envelope"() {
        given:
        // Phase machine: committed RE (0) -> cancelST -> no-RE; the delegate then
        // navigates fresh and the full condition-building schema (success) renders.
        // Driven through toolSetRule / _applyNativeAppEdit, NOT the helper directly --
        // this is the only happy-path that exercises the dispatcher's success envelope.
        def phase = [0]
        def stSeq = [0]
        registerSuccessWalkPages(phase, stSeq)
        cancelStPhaseMachine(phase)

        when:
        def result = script.toolSetRule([
            appId: 100, replaceRequiredExpression: singleSwitchSpec(), confirm: true
        ])

        then: "the dispatcher returns the success envelope with the replace markers"
        result.success == true
        result.requiredExpressionReplaced == true
        result.conditionIndices != null
        result.partial != true
        // A successful replace never reports a restore -- the new RE is live.
        result.requiredExpressionRestored == null
        result.note?.contains("Required Expression replaced")

        and: "the add-only diagnostic key requiredExpressionAlreadyExists is NOT on a replace envelope"
        // The replace delegate runs skipExistingRECheck=true, so that key is structurally
        // always-null on a replace -- contract noise. Goes RED if the finalize re-adds it
        // unconditionally for the replace verb.
        !result.containsKey("requiredExpressionAlreadyExists")
    }

    def "hub_set_rule replaceRequiredExpression trailing updateRule failure auto-restores AND surfaces updateRuleFailed (post-delete failure is recovered)"() {
        given:
        // Same success fixture as above, but the trailing updateRule btn click (fired
        // AFTER the helper's rebuild committed) is rejected. The trailing finalize is
        // INSIDE the helper's restore window now, so a rejected updateRule on a replace
        // -- whose delete already happened -- MUST auto-restore the pre-op backup. The
        // envelope still surfaces updateRuleFailed + expressionNotLive so the caller
        // learns WHY, and requiredExpressionReplaced stays false (the live replace did
        // not stick). This is the #1 regression guard: it goes RED if the restore-on-
        // updateRule-failure is reverted (the old code returned success:false +
        // replaced:true with NO restore, leaving the OLD expression destroyed).
        def phase = [0]
        def stSeq = [0]
        def restored = [false]
        registerSuccessWalkPages(phase, stSeq, null, null, restored)
        def restoreFileRead = installRestoreDownload(restored)
        // Reject ONLY the FIRST updateRule click (the trailing finalize). The restore's
        // own replay also fires updateRule; letting that one succeed proves the restore
        // completed in place (requiredExpressionRestored:true). A blanket throw would
        // also kill the restore replay -> restored:false, masking the contract.
        def updateRuleAttempts = [0]
        cancelStPhaseMachine(phase, { Map body ->
            updateRuleAttempts[0]++
            if (updateRuleAttempts[0] == 1) {
                throw new RuntimeException("simulated updateRule rejection (firmware refused the click)")
            }
        })

        when:
        def result = script.toolSetRule([
            appId: 100, replaceRequiredExpression: singleSwitchSpec(), confirm: true
        ])

        then: "the trailing updateRule was attempted (precondition: the replace reached its trailing click)"
        updateRuleAttempts[0] >= 1

        and: "the envelope carries the dedicated trailing-updateRule failure slots"
        result.updateRuleFailed == true
        result.expressionNotLive == true
        result.updateRuleError?.toString()?.contains("simulated updateRule rejection")

        and: "success is false and the live replace did not stick"
        result.success == false
        result.requiredExpressionReplaced == false

        and: "CONTRACT: the delete already happened, so the rejected trailing updateRule AUTO-RESTORES the pre-op backup"
        result.requiredExpressionRestored == true
        // The dispatcher took its OWN pre-op snapshot (not backupEntry()), so assert the
        // restore actually ran rather than matching a specific generated filename.
        !restoreFileRead.isEmpty()
    }

    def "hub_set_rule replaceRequiredExpression post-commit HEALTH FLIP auto-restores (the rebuild baked but left the rule broken)"() {
        given:
        // The rebuild commits cleanly (the delegate returns success:true) but the
        // post-commit health check finds a **Broken Condition** marker that was NOT present
        // before the replace -- the replace INTRODUCED the breakage. Because the finalize
        // gates its restore on a health DELTA vs the pre-delete baseline, the break must be
        // NEW (absent at baseline, present post-commit) to attribute it to the replace and
        // trigger the restore. So the bare config is CLEAN for the baseline read (phase 0,
        // before cancelST) and carries the broken paragraph only AFTER cancelST (phase 1) --
        // a genuine flip. The pre-op backup MUST be put back: the OLD working expression is
        // never left destroyed-and-broken. #1 regression guard for the health-flip path;
        // goes RED if the restore-on-health-regression is reverted.
        def phase = [0]
        def stSeq = [0]
        def restored = [false]
        registerSuccessWalkPages(phase, stSeq,
            {
                // Clean at baseline (phase 0) and after a confirmed restore; broken only on
                // the post-commit read (phase 1, pre-restore) so the break is a real flip.
                (phase[0] >= 1 && !restored[0]) ?
                    ruleConfigJson(100, "r", [[name: "useST", type: "bool"]], ["IF(**Broken Condition**) THEN"]) :
                    ruleConfigJson(100, "r", [[name: "useST", type: "bool"]])
            }, null, restored)
        def restoreFileRead = installRestoreDownload(restored)
        cancelStPhaseMachine(phase)

        when:
        def result = script.toolSetRule([
            appId: 100, replaceRequiredExpression: singleSwitchSpec(), confirm: true
        ])

        then: "the post-commit health flip is detected"
        result.health?.ok == false

        and: "success is false and the live replace did not stick"
        result.success == false
        result.requiredExpressionReplaced == false

        and: "CONTRACT: the delete already happened + the rule is broken, so the pre-op backup is AUTO-RESTORED"
        result.requiredExpressionRestored == true
        // Dispatcher-driven: the backup is the dispatcher's own pre-op snapshot, so assert
        // the restore ran rather than matching a specific generated filename.
        !restoreFileRead.isEmpty()
    }

    def "replaceRequiredExpression reports the recreated-under-new-id case honestly (NOT a clean in-place restore)"() {
        given:
        // The post-delete rebuild fails (minimal phase-1 schema), then the auto-restore's
        // _rmRestoreFromBackup exists-probe MISSES (the bare config GET throws), so the
        // REAL recreate path fires: it discovers the RM parent, creates a fresh child app
        // (new id 101 via the 302 redirect), and replays the snapshot settings. The helper
        // must then NOT report a clean in-place requiredExpressionRestored:true for the
        // ORIGINAL appId (now a husk) -- it surfaces requiredExpressionRestoredAs:101 and
        // reports restored:false so the caller learns the original id is dead. #4 regression
        // guard; goes RED if the recreated case is mapped to a clean in-place restored:true.
        // The recreate machinery is the REAL _rmRestoreFromBackup (internal-call helpers are
        // not metaClass-stubbable here), driven via the same seams as the existing
        // recreate-on-restore spec: a throwing bare-config exists-probe + /hub2/appsList for
        // parent discovery + a 302 redirect to the new id.
        def phase = [0]
        // The replace flow itself reads STPage/mainPage (page-suffixed) -- never the bare
        // config -- until the restore's exists-probe, so the bare config can always throw.
        hubGet.register('/installedapp/configure/json/100') { params -> throw new RuntimeException("404 -- rule gone (exists-probe miss)") }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> mainPageJson() }
        hubGet.register('/installedapp/configure/json/100/STPage') { params -> stPageForPhase(phase[0]) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson100() }
        // Recreate target id 101: parent discovery + new-id config/status.
        hubGet.register('/hub2/appsList') { params -> JsonOutput.toJson([apps: [[data: [id: 21, name: "Rule Machine", type: "Rule Machine", user: false, hidden: false], children: []]]]) }
        hubGet.register('/installedapp/configure/json/101') { params -> ruleConfigJson(101, "r", [[name: "origLabel", type: "text"]]) }
        hubGet.register('/installedapp/statusJson/101') { params -> statusJson100() }
        script.metaClass.downloadHubFile = { String fileName -> backupSnapshotBytes() }
        script.metaClass.hubInternalGetRaw = { String path, Map q = null, Integer t = 30 ->
            [status: 302, location: "/installedapp/configure/101", data: ""]
        }
        cancelStPhaseMachine(phase)

        when:
        def result = script._rmReplaceRequiredExpression(100, singleModeSpec(), backupEntry())

        then: "the recreated id is surfaced and the in-place restore is reported as NOT clean"
        result.success == false
        result.requiredExpressionReplaced == false
        result.requiredExpressionRestored == false
        result.requiredExpressionRestoredAs == 101
        result.error?.contains("recreated the rule under a new id 101")
        // The original appId is named as the husk to delete.
        result.error?.contains("hub_delete_native_app(appId=100)")
    }

    def "replaceRequiredExpression forwards wizardStuck + the cancelCapab hint when a post-delete cleanup left the wizard open"() {
        given:
        // The REAL post-delete walk fails per-condition (phase-1 minimal schema: cond=a
        // writes, then rCapab_1 is absent), which fires the walker's cancelInFlightCondition
        // -> cancelCapab click. Here that cancelCapab click ALSO fails (stubbed to throw),
        // so the delegate sets wizardCleanupFailed and throws with the [wizardStuck ...]
        // marker. restoreAfterDelete must forward wizardStuck:true + the cancelCapab recovery
        // hint -- parity with the addRequiredExpression dispatcher (which surfaces them via
        // _rmBuildUpdateErrorResponse). #5 regression guard. _rmClickAppButton IS reliably
        // metaClass-stubbable for the internal call (proven by the cancelST-throws spec);
        // the stub flips the phase on cancelST (success) and throws only on cancelCapab.
        def phase = [0]
        def restored = [false]
        registerHelperPages(phase, null, restored)
        def restoreFileRead = installRestoreDownload(restored)
        // _rmClickAppButton now takes a trailing cache param (the page-schema cache threaded by
        // the RM condition builders); the stub must accept it or the 5-arg production call won't
        // match this override and the REAL click runs (no cancelCapab throw -> no wizardStuck).
        script.metaClass._rmClickAppButton = { Integer aId, String name, String stateAttr = null, String pageName = null, Map cache = null ->
            if (name == "cancelST") { phase[0] = 1; return [status: 200, location: null, data: ''] }
            if (name == "cancelCapab") throw new RuntimeException("cancelCapab POST refused (status=400)")
            [status: 200, location: null, data: '']
        }
        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            [status: 200, location: null, data: '']
        }

        when:
        def result = script._rmReplaceRequiredExpression(100, singleModeSpec(), backupEntry())

        then: "the post-delete failure auto-restored AND forwarded the wizardStuck recovery contract"
        result.success == false
        result.requiredExpressionRestored == true
        result.wizardStuck == true
        // Non-vacuous: assert the hint exists, then match the literal cancelCapab recovery
        // command form (button + pageName) the production string actually emits -- bare
        // .contains (no ?.) so a null hint fails loud instead of passing silently.
        result.wizardStuckHint != null
        result.wizardStuckHint.contains("button='cancelCapab', pageName='STPage'")
        restoreFileRead.contains(backupEntry().fileName)
    }

    def "replaceRequiredExpression reports DELETED-and-not-restored when _rmRestoreFromBackup RETURNS a partial-failure (does not throw)"() {
        given:
        // _rmRestoreFromBackup does not always throw on failure: a mid-restore
        // settings-replay error RETURNS [success:false, error:"Restore applied
        // partially..."] (the rule exists but its settings are incomplete). A bare
        // call would discard that return and falsely report restored:true. This drives
        // the REAL _rmRestoreFromBackup into its returned-failure branch -- the in-place
        // restore path runs (exists-probe succeeds), then the restore's own updateRule
        // click throws, which _rmRestoreFromBackup catches and converts to a RETURNED
        // [success:false, error:"Restore applied partially..."]. The helper must map that
        // to restored:false + "did not complete".
        def phase = [0]
        registerHelperPages(phase)
        script.metaClass.downloadHubFile = { String fileName -> backupSnapshotBytes() }

        def posts = []
        script.metaClass.hubInternalPostForm = { String path, Map body, Integer t = 420 ->
            posts << [path: path, body: body]
            if (path == "/installedapp/btn") {
                if (body.name == "cancelST") phase[0] = 1
                // Make ONLY the restore's trailing updateRule click throw. The helper
                // itself never fires updateRule on the direct-call path, so this targets
                // exactly the click inside _rmRestoreFromBackup -- forcing its catch to
                // RETURN [success:false, "Restore applied partially..."] (not throw).
                if (body.name == "updateRule") {
                    throw new RuntimeException("updateRule rejected during restore replay")
                }
            }
            [status: 200, location: null, data: '']
        }

        when:
        def result = script._rmReplaceRequiredExpression(100, singleModeSpec(), backupEntry())

        then: "a returned-failure restore is reported as DELETED + not-restored, never a false restored:true"
        result.success == false
        result.requiredExpressionReplaced == false
        result.requiredExpressionRestored == false
        result.error?.contains("DELETED")
        result.error?.contains("did not complete")
        result.error?.contains("Restore applied partially")
        result.error?.contains("hub_restore_backup")
    }

    def "replaceRequiredExpression returns a read error (RE intact, no restore) when the pre-delete STPage read throws"() {
        given:
        // The very first STPage read (to confirm a committed RE) throws. Nothing has
        // been clicked, so the RE is intact -- distinct "could not read STPage" error,
        // no cancelST, no restore.
        hubGet.register('/installedapp/configure/json/100') { params -> mainPageJson() }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> mainPageJson() }
        script.metaClass._rmCollectPageInputNames = { Integer appId, String pageName ->
            throw new RuntimeException("STPage fetch boom")
        }
        def posts = cancelStPhaseMachine([0])

        when:
        def result = script._rmReplaceRequiredExpression(100, singleModeSpec(), backupEntry())

        then: "a pre-delete read failure is reported as such; the RE is untouched"
        result.success == false
        result.error?.contains("could not read STPage")
        result.requiredExpressionRestored == null

        and: "no cancelST click fired"
        !posts.any { it.path == "/installedapp/btn" && it.body.name == "cancelST" }
    }

    def "replaceRequiredExpression restores when the cancelST click itself throws"() {
        given:
        // The committed-RE tell passes, then the cancelST click throws (e.g. a >=400
        // or transport error). cancelST's destructiveness makes the delete state
        // uncertain, so the helper restores rather than reporting a benign no-op.
        // Distinct from the silent-reject spec (where the click returns 200 but the
        // controls survive).
        hubGet.register('/installedapp/configure/json/100') { params -> mainPageJson() }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> mainPageJson() }
        hubGet.register('/installedapp/configure/json/100/STPage') { params -> stPageForPhase(0) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson100() }

        def restoreFileRead = []
        script.metaClass.downloadHubFile = { String fileName -> restoreFileRead << fileName; backupSnapshotBytes() }
        script.metaClass._rmClickAppButton = { Integer appId, String name, String stateAttr = null, String pageName = null ->
            if (name == "cancelST") throw new RuntimeException("cancelST POST refused (status=400)")
            [status: 200, location: null, data: '']
        }
        def posts = cancelStPhaseMachine([0])

        when:
        def result = script._rmReplaceRequiredExpression(100, singleModeSpec(), backupEntry())

        then: "the thrown cancelST click triggers an auto-restore (delete state uncertain)"
        result.success == false
        result.requiredExpressionReplaced == false
        result.error?.contains("cancelST")
        result.error?.contains("click failed")
        result.requiredExpressionRestored == true
        restoreFileRead.contains(backupEntry().fileName)

        and: "a deleted-then-restored recovery is reported partial (parity with the finalize-path restore)"
        // Every direct-restore branch must carry partial:true so the dispatcher surfaces partial
        // for the same recovery the finalize path already flags. Goes RED if partial:true is
        // dropped from restoreAfterDelete's safe map.
        result.partial == true
    }

    def "replaceRequiredExpression restores when the post-delete STPage re-read throws"() {
        given:
        // The cancelST click lands (200) but the confirming STPage re-read throws.
        // The expression is almost certainly already deleted, so the helper restores.
        // Driven entirely via HTTP (the REAL _rmCollectPageInputNames reads the
        // registered STPage): phase 0 = committed controls for the pre-delete tell and
        // the cancelST click's non-fatal version fetch; after cancelST flips phase to 1,
        // the STPage GET throws -- which is the afterDelete re-read failure.
        def phase = [0]
        def restored = [false]
        // The afterDelete re-read (phase 1, pre-restore) throws -- the failure under test.
        // Once the restore has replayed (restored[0]), the Bug-3 read-back must SUCCEED and
        // see the committed-RE controls again (a confirmed in-place restore re-rendered them).
        registerHelperPages(phase, {
            if (restored[0]) return stPageForPhase(0)
            if (phase[0] == 1) throw new RuntimeException("post-delete STPage fetch boom")
            stPageForPhase(0)
        })
        def restoreFileRead = installRestoreDownload(restored)
        def posts = cancelStPhaseMachine(phase)

        when:
        def result = script._rmReplaceRequiredExpression(100, singleModeSpec(), backupEntry())

        then: "the post-delete re-read failure triggers an auto-restore"
        result.success == false
        result.requiredExpressionReplaced == false
        result.error?.contains("could not re-read STPage after the delete")
        result.requiredExpressionRestored == true
        restoreFileRead.contains(backupEntry().fileName)

        and: "cancelST was clicked before the failing re-read"
        posts.any { it.path == "/installedapp/btn" && it.body.name == "cancelST" }
    }

    def "replaceRequiredExpression restores AND forwards diag fields when the delegate returns a structured success:false"() {
        given:
        // cancelST deletes, the REAL delegate (_rmAddRequiredExpression) walks the new
        // condition to completion, but the post-commit mainPage render still shows the
        // "Define Required Expression" placeholder -- so the delegate returns a STRUCTURED
        // success:false with hubRenderError:true (NOT a throw). The helper must restore AND
        // forward the delegate's diag fields. mainPageNotBaked makes the render check fail.
        def phase = [0]
        def stSeq = [0]
        def restored = [false]
        registerSuccessWalkPages(phase, stSeq, null, { mainPageNotBakedJson() }, restored)
        def restoreFileRead = installRestoreDownload(restored)
        cancelStPhaseMachine(phase)

        when:
        def result = script._rmReplaceRequiredExpression(100, singleSwitchSpec(), backupEntry())

        then: "the structured delegate failure triggers an auto-restore and forwards the diag fields"
        result.success == false
        result.requiredExpressionReplaced == false
        result.requiredExpressionRestored == true
        result.error?.contains("did not commit a live expression")
        result.hubRenderError == true
        restoreFileRead.contains(backupEntry().fileName)

        and: "requiredExpressionAlreadyExists is NOT forwarded -- it is unreachable on the replace path"
        result.requiredExpressionAlreadyExists == null
    }

    def "hub_set_rule replaceRequiredExpression refuses on CREATE (no appId) -- loudly rejected as edit-only"() {
        given:
        // (setup() already enabled the write master.)

        when: "replaceRequiredExpression is bundled on a create (no appId)"
        script.toolSetRule([
            name: "New Rule", replaceRequiredExpression: singleModeSpec(), confirm: true
        ])

        then: "the create gate rejects it as an edit-only operation -- never silently dropped"
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("replaceRequiredExpression")
        ex.message.toLowerCase().contains("edit-only")
    }

    def "hub_set_rule replaceRequiredExpression pre-delete validation throw does NOT carry a misleading rollback restoreHint"() {
        given:
        // A malformed spec throws at the helper's Step-0 validation, BEFORE the destructive
        // delete -- the RE is intact. The dispatcher's catch routes through
        // _rmBuildUpdateErrorResponse, which would normally add a "roll back via backup"
        // restoreHint. On this pre-delete path nothing was deleted, so the hint is replaced
        // with an accurate "no changes were made; the RE is intact" note. #13 regression guard.
        hubGet.register('/installedapp/configure/json/100') { params -> mainPageJson() }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> mainPageJson() }
        hubGet.register('/installedapp/configure/json/100/STPage') { params -> stPageForPhase(0) }
        cancelStPhaseMachine([0])

        when: "a malformed spec (bad operator) on an existing rule"
        def result = script.toolSetRule([
            appId: 100,
            replaceRequiredExpression: [
                conditions: [[capability: "Mode", modeIds: ["2"]], [capability: "Mode", modeIds: ["3"]]],
                operator: "NAND"
            ],
            confirm: true
        ])

        then: "the error response names no-changes/intact and does NOT tell the caller to roll back"
        result.success == false
        // Non-vacuous: pin that the hint EXISTS first, then assert content with bare
        // .contains (no ?.) so a null hint can't pass the positive AND the negative
        // assertions silently. The negative (no "roll back") is meaningful only once the
        // hint is known non-null.
        result.restoreHint != null
        result.restoreHint.contains("No changes were made")
        result.restoreHint.contains("intact")
        !(result.restoreHint.toLowerCase().contains("roll back"))
    }

    def "_rmAddRequiredExpression preValidated=true still rejects a malformed spec -- it skips only the deviceId hub probe, NOT shape validation"() {
        given:
        // #6 negative pin: preValidated=true (the flag the replace delegate passes) must
        // NOT bypass shape/operator validation -- it only skips the per-deviceId existence
        // hub GET. A malformed spec (operator on a single-condition expression is fine, but
        // operators-list length must equal conditions-1) must STILL throw even with
        // preValidated=true, so a replace can never sneak a bad spec past the shared
        // validator. No hub pages registered: the throw must happen before any wizard read.
        when: "a malformed multi-condition spec (operators length != conditions-1) with preValidated=true"
        script._rmAddRequiredExpression(100, [
            conditions: [[capability: "Mode", modeIds: ["2"]], [capability: "Mode", modeIds: ["3"]]],
            operators: ["AND", "OR"]
        ], true, true)

        then: "shape validation still throws even though preValidated skips the deviceId existence probe"
        def ex = thrown(IllegalArgumentException)
        ex.message.contains("addRequiredExpression.operators")
    }

    def "hub_set_rule patches[] replaceRequiredExpression op: success envelope + a failed replace op does not revert a preceding op"() {
        given:
        // Two-op batch: op 0 is a plain button click (a real, clean-success preceding
        // mutation routed through the stubbed hubInternalPostForm -- no internal-helper
        // stub needed), op 1 = replaceRequiredExpression (kept REAL so the per-op
        // snapshot + restore path is exercised). Run twice via the data table: a SUCCESS
        // replace (envelope check) and a FAILED replace (the failed op takes a per-op
        // snapshot and restores ONLY to that point, so op 0 is preserved).
        def phase = [0]
        def stSeq = [0]
        def restored = [false]
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", [[name: "useST", type: "bool"]]) }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> mainPageBakedJson() }
        hubGet.register('/installedapp/configure/json/100/STPage') { params ->
            // Once a restore has replayed (failed-replace branch), the committed RE is back
            // so the Bug-3 read-back confirms restored:true.
            if (restored[0]) return stPageForPhase(0)
            if (phase[0] < 1) return stPageForPhase(phase[0])
            stSeq[0]++
            replaceSucceeds ? stPageSuccessSchema(stSeq[0]) : stPageForPhase(1)
        }
        hubGet.register('/installedapp/configure/json/100/selectActions') { params -> ruleConfigJson(100, "r", [[name: "N", type: "button"]]) }
        hubGet.register('/installedapp/configure/json/100/doActPage') { params -> doActPageJson() }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson100() }
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }

        // Capture every snapshot REASON by parsing the bytes the REAL _rmBackupRuleSnapshot
        // uploads (uploadHubFile is a purely-dynamic helper, reliably interceptable; the
        // script-defined _rmBackupRuleSnapshot is NOT, so we observe its effect instead of
        // stubbing it). The per-op snapshot the patches loop takes carries
        // reason="pre-patch-replaceRequiredExpression"; the dispatcher's pre-batch snapshot
        // carries a different reason. Seeing the per-op reason proves the loop took its OWN
        // snapshot (lazily, via the provider closure) rather than reusing the pre-batch handle.
        def uploadedReasons = []
        script.metaClass.uploadHubFile = { String fn, byte[] b ->
            try {
                def snap = new groovy.json.JsonSlurper().parseText(new String(b, "UTF-8"))
                if (snap?.reason != null) uploadedReasons << snap.reason.toString()
            } catch (ignored) { }
        }
        def restoreFileRead = installRestoreDownload(restored)
        // Record every updateRule click so we can prove the replace op did NOT fire its OWN
        // trailing updateRule mid-batch (defer mode): the ONLY non-restore updateRule click
        // must be the single batch-end one. A restore-replay updateRule (failed branch) is
        // distinguished by happening after a downloadHubFile.
        def updateRuleClicks = []
        cancelStPhaseMachine(phase, { Map body -> updateRuleClicks << body })

        when:
        def result = script.toolSetRule([
            appId: 100,
            patches: [[button: "pauseRule"],
                      [replaceRequiredExpression: singleSwitchSpec()]],
            confirm: true
        ])

        then: "the batch ran both ops; the first op's success entry is present regardless of the replace outcome"
        def firstEntry = result.patches.find { it.op == "button" }
        firstEntry?.success == true

        and: "the replace op took its OWN per-op snapshot (not reusing the pre-batch backup for its restore)"
        uploadedReasons.any { it == "pre-patch-replaceRequiredExpression" }

        and: "the replace op outcome matches the scenario"
        def replEntry = result.patches.find { it.op == "replaceRequiredExpression" }
        if (replaceSucceeds) {
            assert replEntry?.success == true
            assert replEntry?.requiredExpressionReplaced == true
            // Bug-2 CONTRACT (success branch): the replace op did NOT fire its own trailing
            // updateRule mid-batch -- the batch-end click is the ONLY updateRule, so exactly
            // one updateRule click fired for the whole batch (no restore replay on success).
            assert updateRuleClicks.size() == 1
        } else {
            // The failed replace restored to its per-op snapshot, and the preceding
            // button op's success entry is preserved (restore-scope = op-scope, so a
            // failed replace does not revert the earlier op). The restore actually ran.
            assert replEntry?.success == false
            assert replEntry?.requiredExpressionRestored == true
            assert !restoreFileRead.isEmpty()
            assert firstEntry?.success == true
            // Bug-2 CONTRACT (failed branch): the per-op rebuild-failure restore is KEPT in
            // a batch, and the batch-end updateRule still fires once. The restore replay also
            // fires an updateRule, so >=1 -- but the replace op itself contributed NO extra
            // trailing finalize updateRule beyond those (no per-op finalize click on defer).
            assert updateRuleClicks.size() >= 1
        }

        where:
        replaceSucceeds << [true, false]
    }

    def "patches[] replaceRequiredExpression refusal (no committed RE) does NOT take a per-op snapshot"() {
        given:
        // #11 regression guard: a mid-batch replace that REFUSES pre-delete (no committed
        // RE) must NOT pay for the per-op snapshot. The lazy provider closure means the
        // snapshot fires only when the replace is about to delete; a missing-RE refusal
        // returns before that point, so no "pre-patch-replaceRequiredExpression" snapshot
        // should be uploaded. The STPage stays in the no-RE state so the replace refuses.
        hubGet.register('/installedapp/configure/json/100') { params -> ruleConfigJson(100, "r", [[name: "useST", type: "bool"]]) }
        hubGet.register('/installedapp/configure/json/100/mainPage') { params -> mainPageBakedJson() }
        hubGet.register('/installedapp/configure/json/100/STPage') { params -> stPageForPhase(1) }
        hubGet.register('/installedapp/statusJson/100') { params -> statusJson100() }

        def uploadedReasons = []
        script.metaClass.uploadHubFile = { String fn, byte[] b ->
            try {
                def snap = new groovy.json.JsonSlurper().parseText(new String(b, "UTF-8"))
                if (snap?.reason != null) uploadedReasons << snap.reason.toString()
            } catch (ignored) { }
        }
        cancelStPhaseMachine([1])

        when:
        def result = script.toolSetRule([
            appId: 100,
            patches: [[replaceRequiredExpression: singleSwitchSpec()]],
            confirm: true
        ])

        then: "the replace op refused (no committed RE to replace)"
        def replEntry = result.patches.find { it.op == "replaceRequiredExpression" }
        replEntry?.success == false
        replEntry?.requiredExpressionMissing == true

        and: "NO per-op snapshot was taken for the refused replace (the lazy provider never fired)"
        !uploadedReasons.any { it == "pre-patch-replaceRequiredExpression" }
    }

    def "BUG-1: a SUCCESSFUL replace on a rule with a PRE-EXISTING unrelated imbalance does NOT auto-restore"() {
        given:
        // The rule already carries an unbalanced IF action block (a half-open IF -- the rule
        // is still being built) BEFORE the replace. _rmCheckRuleHealth flags that as
        // structuralIssues -> health.ok==false, BOTH before and after the replace (the
        // statusJson is static). The replace itself succeeds cleanly (old RE swapped for the
        // new one). The finalize gates its restore on a health DELTA against the pre-delete
        // baseline, so a pre-existing imbalance present in the baseline is NOT attributable
        // to the replace: no NEW issue -> NO restore, and the replace reports success. This
        // is the Bug-1 regression guard. It goes RED if the gate reverts to the absolute
        // !health.ok check (which would see ok==false from the pre-existing imbalance, fire
        // restoreAfterDelete, and DESTROY the freshly-baked new RE by rolling back).
        def phase = [0]
        def stSeq = [0]
        def restored = [false]
        // Imbalanced statusJson (static) supplies the pre-existing structuralIssue for BOTH
        // the baseline and the post-commit health reads; restoredRef would flip STPage on a
        // restore (asserted NOT to happen below). downloadHubFile is wired so that IF a
        // restore wrongly fired, restoredFileRead would be non-empty -- the negative pin.
        registerSuccessWalkPages(phase, stSeq, null, null, restored, { statusJson100ImbalancedIf() })
        def restoreFileRead = installRestoreDownload(restored)
        cancelStPhaseMachine(phase)

        when:
        def result = script.toolSetRule([
            appId: 100, replaceRequiredExpression: singleSwitchSpec(), confirm: true
        ])

        then: "the pre-existing imbalance is present in the health block (it was never fixed by this op)"
        result.health?.ok == false
        result.health?.structuralIssues?.size() >= 1

        and: "CONTRACT: the replace SUCCEEDED -- the pre-existing imbalance did NOT trigger a restore"
        result.success == true
        result.requiredExpressionReplaced == true
        // No restore fired: requiredExpressionRestored is absent (a restore would set it
        // true/false) and the backup file was never read.
        result.requiredExpressionRestored == null
        restoreFileRead.isEmpty()
    }

    def "BUG-2: a patches-batch replace op does NOT fire its own updateRule (defers to the single batch-end click)"() {
        given:
        // A single-op patches batch with a clean replace. In defer mode the replace op must
        // NOT fire its own trailing updateRule -- the batch-end click is the ONLY updateRule
        // for the whole batch. So exactly ONE updateRule click fires. This is the Bug-2
        // regression guard: it goes RED if the replace self-finalizes mid-batch (which would
        // make TWO updateRule clicks -- the per-op finalize + the batch-end). The replace is
        // kept REAL (success-walk pages) so the real finalize path is exercised.
        def phase = [0]
        def stSeq = [0]
        def restored = [false]
        registerSuccessWalkPages(phase, stSeq, null, null, restored)
        installRestoreDownload(restored)
        def updateRuleClicks = []
        cancelStPhaseMachine(phase, { Map body -> updateRuleClicks << body })

        when:
        def result = script.toolSetRule([
            appId: 100,
            patches: [[replaceRequiredExpression: singleSwitchSpec()]],
            confirm: true
        ])

        then: "the replace op succeeded inside the batch"
        def replEntry = result.patches.find { it.op == "replaceRequiredExpression" }
        replEntry?.success == true
        replEntry?.requiredExpressionReplaced == true

        and: "CONTRACT: exactly ONE updateRule click fired -- the batch-end click, NOT a per-op finalize"
        // No restore on this clean path, so the only updateRule is the batch-end one. If the
        // replace self-finalized mid-batch this would be 2.
        updateRuleClicks.size() == 1

        and: "the batch-level success + note reflect the single batch-end updateRule"
        result.success == true
        result.note?.contains("fired once")
    }

    def "BUG-2b: batch-end updateRule failure after a DEFERRED replace commits -> the replace's backup IS restored"() {
        given:
        // The destructive-window contract, re-homed to the batch end. A single-op batch whose
        // replace op deletes + rebuilds the RE cleanly (defer mode: no per-op updateRule, no
        // per-op restore). Then the SINGLE batch-end updateRule is rejected -- so the deleted-
        // then-rebuilt RE is not live and the rule is left worse than before. The batch-end
        // finalize MUST own the restore for that deferred op: put the op's pre-op snapshot back
        // and surface requiredExpressionRestored. Goes RED if the batch-end restore is dropped
        // (the BUG-2 defer fix without this leaves the OLD RE deleted + the NEW RE not-live +
        // NO restore -- the exact data-loss gap). Reject ONLY the FIRST updateRule (the batch-
        // end click); the restore replay's own updateRule succeeds so the restore completes in
        // place (requiredExpressionRestored:true) -- a blanket throw would also kill the replay.
        def phase = [0]
        def stSeq = [0]
        def restored = [false]
        registerSuccessWalkPages(phase, stSeq, null, null, restored)
        def restoreFileRead = installRestoreDownload(restored)
        def updateRuleAttempts = [0]
        cancelStPhaseMachine(phase, { Map body ->
            updateRuleAttempts[0]++
            if (updateRuleAttempts[0] == 1) {
                throw new RuntimeException("simulated batch-end updateRule rejection (firmware refused the click)")
            }
        })

        when:
        def result = script.toolSetRule([
            appId: 100,
            patches: [[replaceRequiredExpression: singleSwitchSpec()]],
            confirm: true
        ])

        then: "the batch-end updateRule failed (precondition: the batch reached its single trailing click)"
        result.updateRuleFailed == true
        updateRuleAttempts[0] >= 1

        and: "CONTRACT: the deferred replace's pre-op backup WAS restored at batch-end (no silent data loss)"
        def replEntry = result.patches.find { it.op == "replaceRequiredExpression" }
        replEntry?.requiredExpressionRestored == true
        replEntry?.success == false
        replEntry?.partial == true
        !restoreFileRead.isEmpty()

        and: "the restored entry is NOT self-contradictory: requiredExpressionReplaced is false (the RE was rolled back, not left committed)"
        // The committed flag must clear on restore -- an entry carrying BOTH replaced:true AND
        // restored:true is a contradiction (the RE cannot be both newly-committed and rolled back).
        replEntry?.requiredExpressionReplaced == false
        // The stale "...updateRule deferred to the batch-end click" note is replaced with a rollback note.
        replEntry?.note?.toLowerCase()?.contains("replace rolled back in batch")

        and: "the batch envelope reflects the failure (success false, partial true)"
        result.success == false
        result.partial == true
    }

    def "BUG-2b: a sibling-introduced NEW imbalance in a MULTI-op batch does NOT over-restore the deferred replace"() {
        given:
        // OVER-RESTORE guard for a MULTI-op batch. Post-batch health is the CUMULATIVE result of
        // every op, so a structural delta cannot be attributed to any single deferred op. This
        // batch carries the replace PLUS a sibling button op (two ops -- not a sole-op batch), and
        // a NEW structural imbalance appears only AFTER the replace committed: the statusJson is
        // BALANCED while the replace runs (clean pre-delete baseline) and flips to IMBALANCED only
        // after the batch-end updateRule fires (cumulative post-batch health carries a NEW
        // structuralIssue not in the replace's baseline). The batch-end updateRule SUCCEEDS. The
        // sibling could be what left the imbalance, so it is NOT attributable to the replace -- the
        // committed replace MUST NOT be rolled back (that would also silently revert the sibling).
        // Goes RED if the multi-op health-regression suppression is removed (the NEW delta would
        // over-restore the committed replace).
        def phase = [0]
        def stSeq = [0]
        def restored = [false]
        // Flip to imbalanced only after the batch-end updateRule fires. The replace's pre-delete
        // baseline (read during the patch loop, before the batch-end click) sees the balanced
        // statusJson; the post-batch health read (after the click) sees the new imbalance.
        def batchEndFired = [false]
        registerSuccessWalkPages(phase, stSeq, null, null, restored,
            { batchEndFired[0] ? statusJson100ImbalancedIf() : statusJson100() })
        def restoreFileRead = installRestoreDownload(restored)
        def updateRuleClicks = []
        cancelStPhaseMachine(phase, { Map body -> updateRuleClicks << body; batchEndFired[0] = true })

        when: "a MULTI-op batch: the replace plus a sibling button op"
        def result = script.toolSetRule([
            appId: 100,
            patches: [[replaceRequiredExpression: singleSwitchSpec()], [button: "pauseRule"]],
            confirm: true
        ])

        then: "the batch-end updateRule fired clean (so updateRule-failure is NOT the trigger)"
        result.updateRuleFailed != true
        updateRuleClicks.size() == 1

        and: "a NEW imbalance is in the post-batch cumulative health (not present at the replace's baseline)"
        result.health?.ok == false
        result.health?.structuralIssues?.size() >= 1

        and: "CONTRACT: in a multi-op batch a NEW post-batch imbalance does NOT over-restore the committed replace"
        def replEntry = result.patches.find { it.op == "replaceRequiredExpression" }
        replEntry?.success == true
        replEntry?.requiredExpressionReplaced == true
        // No restore fired: the op carries no restore verdict and the backup was never read.
        replEntry?.requiredExpressionRestored == null
        restoreFileRead.isEmpty()
    }

    def "BUG-4: a SOLE-op batch whose deferred replace bakes a NEW imbalance (updateRule OK) IS restored (single-op parity with standalone)"() {
        given:
        // SINGLE-op parity. In a sole-op batch the deferred replace is the ONLY change, so a
        // post-batch health regression vs its pre-delete baseline IS attributable to it (no
        // sibling confound) -- exactly the standalone case. The replace bakes a NEW structural
        // imbalance (statusJson balanced at the replace's baseline, imbalanced once the batch-end
        // updateRule has fired), and the batch-end updateRule SUCCEEDS. The deferred replace MUST
        // restore on the health regression, matching what the standalone path does. Goes RED if
        // the single-op health-restore is dropped (the deferred replace would be left committed-
        // but-unhealthy + success:false + NOT restored). The restore replay's own updateRule
        // succeeds so the restore completes and the read-back confirms.
        def phase = [0]
        def stSeq = [0]
        def restored = [false]
        def batchEndFired = [false]
        registerSuccessWalkPages(phase, stSeq, null, null, restored,
            { batchEndFired[0] ? statusJson100ImbalancedIf() : statusJson100() })
        def restoreFileRead = installRestoreDownload(restored)
        def updateRuleClicks = []
        cancelStPhaseMachine(phase, { Map body -> updateRuleClicks << body; batchEndFired[0] = true })

        when: "a SOLE-op batch: only the replace"
        def result = script.toolSetRule([
            appId: 100,
            patches: [[replaceRequiredExpression: singleSwitchSpec()]],
            confirm: true
        ])

        then: "the batch-end updateRule fired clean (so the trigger is the health regression, not updateRule-failure)"
        result.updateRuleFailed != true
        // Two updateRule clicks: the clean batch-end click, then the restore replay's OWN click
        // (the restore correctly fired). A size==1 assertion would be the click-count trap -- the
        // restore replay always adds a second click.
        updateRuleClicks.size() == 2

        and: "the post-batch health regressed (a NEW imbalance the replace introduced)"
        result.health?.ok == false

        and: "CONTRACT: a sole-op batch restores on the attributable health regression (parity with standalone)"
        def replEntry = result.patches.find { it.op == "replaceRequiredExpression" }
        replEntry?.requiredExpressionRestored == true
        replEntry?.success == false
        replEntry?.requiredExpressionReplaced == false
        !restoreFileRead.isEmpty()
    }

    def "BUG-8-batch: a MULTI-op batch with an UNCHANGED pre-existing **Broken Condition** does NOT restore the replace"() {
        given:
        // Companion no-restore guard, MULTI-op. The SAME pre-existing **Broken Condition** (one
        // instance) is present at the replace's baseline AND post-batch (unchanged count), and a
        // sibling button op makes this a multi-op batch. Neither trigger fires: the batch-end
        // updateRule succeeds, and the multi-op health trigger is suppressed (sibling confound).
        // The committed replace must NOT be rolled back. Goes RED if multi-op suppression breaks
        // or the count delta wrongly treats an unchanged pre-existing marker as new.
        def phase = [0]
        def stSeq = [0]
        def restored = [false]
        registerSuccessWalkPages(phase, stSeq,
            { ruleConfigJson(100, "r", [[name: "useST", type: "bool"]], ["IF(**Broken Condition**) THEN"]) },
            null, restored)
        def restoreFileRead = installRestoreDownload(restored)
        cancelStPhaseMachine(phase)

        when: "a MULTI-op batch: the replace plus a sibling button op"
        def result = script.toolSetRule([
            appId: 100,
            patches: [[replaceRequiredExpression: singleSwitchSpec()], [button: "pauseRule"]],
            confirm: true
        ])

        then: "neither trigger fired and the committed replace was NOT rolled back"
        def replEntry = result.patches.find { it.op == "replaceRequiredExpression" }
        replEntry?.success == true
        replEntry?.requiredExpressionReplaced == true
        replEntry?.requiredExpressionRestored == null
        restoreFileRead.isEmpty()

        and: "the pre-existing marker (unchanged count one) is still surfaced in the health block"
        result.health?.brokenMarkerCounts?.get("**Broken Condition**") == 1
    }

    def "BUG-5: a second replaceRequiredExpression in one batch is REFUSED (a rule has a single Required Expression)"() {
        given:
        // A rule has exactly ONE Required Expression, so only the first replaceRequiredExpression
        // in a batch is valid; a second would replace the first (and its additive snapshot would
        // restore to the intermediate, not the original, while the identity-blind read-back still
        // reported restored:true). The second op must FAIL with an actionable error; the first op
        // is unaffected. Goes RED if the seen-replace guard is removed (the second op would run
        // and the additive-restore corruption path becomes reachable).
        def phase = [0]
        def stSeq = [0]
        def restored = [false]
        registerSuccessWalkPages(phase, stSeq, null, null, restored)
        installRestoreDownload(restored)
        cancelStPhaseMachine(phase)

        when: "a batch with TWO replaceRequiredExpression ops"
        def result = script.toolSetRule([
            appId: 100,
            patches: [[replaceRequiredExpression: singleSwitchSpec()],
                      [replaceRequiredExpression: singleSwitchSpec()]],
            confirm: true
        ])

        then: "the FIRST replace ran (committed) and the SECOND is refused with an actionable error"
        def entries = result.patches.findAll { it.op == "replaceRequiredExpression" }
        entries.size() == 2
        entries[0]?.success == true
        entries[0]?.requiredExpressionReplaced == true
        entries[1]?.success == false
        entries[1]?.error?.contains("only one replaceRequiredExpression is valid")

        and: "the batch reports partial (one op failed) and is not a clean success"
        result.success == false
        result.partial == true
    }

    def "BUG-10: a successful patches replace response does NOT leak the internal _deferredRERestore key"() {
        given:
        // The deferred-restore context is internal plumbing; it must be STRIPPED before the
        // response. Assert neither the batch envelope nor the replace patch entry carries the
        // _deferredRERestore key on a clean success. Goes RED if the strip in the patch loop is
        // removed (the internal key would ride out on the wire).
        def phase = [0]
        def stSeq = [0]
        def restored = [false]
        registerSuccessWalkPages(phase, stSeq, null, null, restored)
        installRestoreDownload(restored)
        cancelStPhaseMachine(phase)

        when:
        def result = script.toolSetRule([
            appId: 100,
            patches: [[replaceRequiredExpression: singleSwitchSpec()]],
            confirm: true
        ])

        then: "the replace committed cleanly"
        def replEntry = result.patches.find { it.op == "replaceRequiredExpression" }
        replEntry?.success == true
        replEntry?.requiredExpressionReplaced == true

        and: "the internal _deferredRERestore key is absent from BOTH the entry and the envelope"
        !replEntry.containsKey("_deferredRERestore")
        !result.containsKey("_deferredRERestore")
    }

    def "BUG-1b: a batch replace whose pre-op snapshot THROWS refuses BEFORE the delete (RE not deleted)"() {
        given:
        // The lazy per-op snapshot provider runs just before the destructive cancelST. If it
        // THROWS, all pre-delete gates have passed but the RE is still intact -- proceeding into
        // cancelST with no backup would convert a recoverable op into guaranteed data loss on the
        // next failure. The op MUST refuse before the delete: no cancelST click, success:false,
        // an actionable error. Goes RED if the snapshot-throw path reverts to "continue with
        // backup=null" (cancelST then fires / the refusal disappears). _rmBackupRuleSnapshot
        // throws here because uploadHubFile (the snapshot's File-Manager write) throws.
        def phase = [0]
        registerHelperPages(phase)
        // The replace's up-front validation existence-probes the condition deviceId; register it
        // so validation passes cleanly and the flow reaches the lazy snapshot (the B1 guard).
        hubGet.register('/device/fullJson/8') { params -> '{"id":"8","name":"S1"}' }
        installRestoreDownload([false])
        // Throw ONLY for the per-op snapshot (reason "pre-patch-replaceRequiredExpression"),
        // not the dispatcher's pre-batch "pre-update" backup taken at the top of the edit engine
        // -- otherwise the throw trips the pre-batch backup and errors the whole tool before the
        // per-op provider (the B1 guard under test) ever runs. The snapshot bytes carry `reason`.
        script.metaClass.uploadHubFile = { String fn, byte[] b ->
            def snap = new groovy.json.JsonSlurper().parseText(new String(b, "UTF-8"))
            if (snap?.reason == "pre-patch-replaceRequiredExpression") {
                throw new RuntimeException("file manager write refused (disk full)")
            }
        }
        def posts = cancelStPhaseMachine(phase)

        when:
        def result = script.toolSetRule([
            appId: 100,
            patches: [[replaceRequiredExpression: singleSwitchSpec()]],
            confirm: true
        ])

        then: "the replace op refused before the destructive delete with an actionable error"
        def replEntry = result.patches.find { it.op == "replaceRequiredExpression" }
        replEntry?.success == false
        replEntry?.error?.toLowerCase()?.contains("could not take the pre-op backup")
        replEntry?.error?.toUpperCase()?.contains("UNCHANGED")

        and: "NO cancelST click fired -- the Required Expression was never deleted"
        !posts.any { it.path == "/installedapp/btn" && it.body.name == "cancelST" }
        !posts.any { it.path == "/installedapp/update/json" && it.body["settings[cond]"] != null }
    }

    def "BUG-3: a NON-EMPTY-backup restore that does NOT re-activate the gate reports restored:false (read-back gating)"() {
        given:
        // The post-delete rebuild fails, so restoreAfterDelete replays a NON-EMPTY saved-
        // settings backup (real reconstruction, not the empty-settings backups the other
        // specs use). The restore's settings-replay + updateRule click do NOT throw, so
        // _rmRestoreFromBackup returns success:true -- but the rule is left WITHOUT the
        // committed Required Expression re-rendered (STPage stays in the no-RE state). The
        // Bug-3 read-back must catch that: it re-reads STPage, sees NO committed-RE tell, and
        // reports requiredExpressionRestored:false with an explicit "could NOT be confirmed"
        // error -- never a false restored:true on a silently-ungated rule. Goes RED if the
        // read-back is removed (the old code reported restored:true purely from the no-throw).
        def phase = [0]
        // STPage stays in the no-RE state for EVERY read after cancelST -- including the
        // post-restore read-back. The restore replay does not re-render the committed RE,
        // modelling a replay that wrote field values but did not re-activate the gate.
        registerHelperPages(phase)  // default supplier: phase-driven, no restoredRef -> stays no-RE post-cancelST
        // NON-EMPTY saved-settings backup: carries an actual RE condition slot so the replay
        // is real reconstruction, not a bare label re-seed.
        def nonEmptyBackupBytes = JsonOutput.toJson([
            schemaVersion: 1, ruleId: 100, appId: 100, appType: "rule_machine",
            reason: "pre-replaceRequiredExpression", timestamp: 1L, appLabel: "r",
            configJson: [app: [id: 100, appType: [name: "Rule-5.1", namespace: "hubitat"]],
                         configPage: [name: "mainPage", sections: [[input: []]]],
                         settings: [useST: [type: "bool", value: true],
                                    rCapab_1: [type: "enum", value: "Switch"]]],
            statusJson: [appSettings: [[name: "useST", value: "true", type: "bool"],
                                       [name: "rCapab_1", value: "Switch", type: "enum"]]]
        ]).getBytes("UTF-8")
        def restoreFileRead = []
        script.metaClass.downloadHubFile = { String fileName -> restoreFileRead << fileName; nonEmptyBackupBytes }
        def posts = cancelStPhaseMachine(phase)

        when:
        def result = script._rmReplaceRequiredExpression(100, singleSwitchSpec(), backupEntry())

        then: "the rebuild failed and the restore replay ran from the NON-EMPTY backup"
        result.success == false
        result.requiredExpressionReplaced == false
        restoreFileRead.contains(backupEntry().fileName)

        and: "CONTRACT: the read-back could NOT confirm the RE re-activated -> restored:false, never a false true"
        result.requiredExpressionRestored == false
        result.error?.contains("could NOT be confirmed")
        result.error?.toLowerCase()?.contains("ungated")

        and: "the restore actually replayed the non-empty settings (the rCapab_1 slot was written)"
        posts.any { it.path == "/installedapp/update/json" && it.body.find { k, v -> k.toString().contains("rCapab_1") } != null }
    }

    def "BUG-3: a NON-EMPTY-backup restore that DOES re-activate the gate reports restored:true (read-back confirms)"() {
        given:
        // Companion to the negative above: same non-empty-backup reconstruction, but the
        // restore DOES re-render the committed RE (STPage shows the committed-RE tell after
        // the replay). The read-back confirms it, so restored:true is honest. This is the
        // must-NOT-catch fixture for the read-back gate -- it proves the gate is not simply
        // hard-coded to false. restoredRef flips on the restore's downloadHubFile, after
        // which the STPage supplier renders the committed-RE controls.
        def phase = [0]
        def restored = [false]
        registerHelperPages(phase, null, restored)
        def nonEmptyBackupBytes = JsonOutput.toJson([
            schemaVersion: 1, ruleId: 100, appId: 100, appType: "rule_machine",
            reason: "pre-replaceRequiredExpression", timestamp: 1L, appLabel: "r",
            configJson: [app: [id: 100, appType: [name: "Rule-5.1", namespace: "hubitat"]],
                         configPage: [name: "mainPage", sections: [[input: []]]],
                         settings: [useST: [type: "bool", value: true],
                                    rCapab_1: [type: "enum", value: "Switch"]]],
            statusJson: [appSettings: [[name: "useST", value: "true", type: "bool"],
                                       [name: "rCapab_1", value: "Switch", type: "enum"]]]
        ]).getBytes("UTF-8")
        def restoreFileRead = []
        script.metaClass.downloadHubFile = { String fileName -> restoreFileRead << fileName; restored[0] = true; nonEmptyBackupBytes }
        cancelStPhaseMachine(phase)

        when:
        def result = script._rmReplaceRequiredExpression(100, singleSwitchSpec(), backupEntry())

        then: "the rebuild failed and the restore replayed from the non-empty backup"
        result.success == false
        result.requiredExpressionReplaced == false
        restoreFileRead.contains(backupEntry().fileName)

        and: "CONTRACT: the read-back confirmed the RE re-activated -> restored:true (honest, not hard-coded false)"
        result.requiredExpressionRestored == true
        result.error?.contains("restored from backup")
    }

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

    def "BUG-8: a pre-existing **Broken Condition** with UNCHANGED count does NOT restore (BUG-1 preserved)"() {
        given:
        // The must-NOT-catch companion: the SAME pre-existing **Broken Condition** is present at
        // BOTH the baseline (phase 0) and post-commit (phase 1) with the SAME count (one). The
        // count is unchanged, so it is NOT a new break and the clean replace must NOT be rolled
        // back -- the BUG-1 over-restore guard still holds under the count-aware delta. Goes RED
        // if the count delta wrongly treats an unchanged pre-existing marker as new.
        def phase = [0]
        def stSeq = [0]
        def restored = [false]
        registerSuccessWalkPages(phase, stSeq,
            { ruleConfigJson(100, "r", [[name: "useST", type: "bool"]], ["IF(**Broken Condition**) THEN"]) },
            null, restored)
        def restoreFileRead = installRestoreDownload(restored)
        cancelStPhaseMachine(phase)

        when:
        def result = script.toolSetRule([
            appId: 100, replaceRequiredExpression: singleSwitchSpec(), confirm: true
        ])

        then: "the pre-existing broken marker (unchanged count) did NOT trigger a restore"
        result.success == true
        result.requiredExpressionReplaced == true
        result.requiredExpressionRestored == null
        restoreFileRead.isEmpty()

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
